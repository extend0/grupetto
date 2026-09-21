package com.spop.poverlay.workout

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spop.poverlay.strava.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StravaUploadTest {
    private fun fixture(interceptor: Interceptor, block: suspend (WorkoutRepository, StravaUploader) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "upload-${java.util.UUID.randomUUID()}.db"
        val repository = WorkoutRepository(context, name)
        val vault = CredentialVault(context, "test-upload-auth")
        vault.write(JSONObject().put("athlete_id", 123).put("device_credential", "test-device")
            .put("origin", "https://household.example.invalid"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val auth = StravaAuthManager(context, scope, OkHttpClient.Builder().addInterceptor(interceptor).build(), vault)
        try {
            repository.dao.insert(Workout("ride", 1000, endedAt = 3000, lastPedaledAt = 3000,
                status = WorkoutStatus.QUEUED, athleteId = 123, distanceMeters = 10.0))
            repository.dao.insert(WorkoutSample("ride", 1000, 200f, 80f, 30f, 5.0, 130, 0.0))
            repository.dao.insert(WorkoutSample("ride", 3000, 200f, 80f, 30f, 5.0, 130, 10.0))
            block(repository, StravaUploader(repository, auth))
        } finally { scope.cancel(); vault.clear(); repository.database.close(); context.deleteDatabase(name) }
    }
    private fun response(chain: Interceptor.Chain, json: String, code: Int = 200): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(json.toResponseBody("application/json".toMediaType())).build()

    @Test fun successfulUploadPersistsIdsAndDoesNotPostTwice() {
        val posts = AtomicInteger()
        fixture(Interceptor { chain ->
            assertEquals("Bearer test-device", chain.request().header("Authorization"))
            if (chain.request().method == "PUT") {
                assertEquals("application/xml", chain.request().body!!.contentType().toString())
                posts.incrementAndGet()
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val payload = buffer.readUtf8()
                assertTrue(payload.contains("<ns3:Watts>200"))
                response(chain, """{"status":"uploaded","uploadId":"456","activityId":"789"}""", 202)
            } else response(chain, "{}", 404)
        }) { repo, uploader ->
            uploader.upload("ride")
            assertEquals(WorkoutStatus.UPLOADED, repo.dao.get("ride")!!.status)
            assertEquals(789L, repo.dao.get("ride")!!.activityId)
            uploader.upload("ride")
            assertEquals(1, posts.get())
        }
    }
    @Test fun networkFailureRetainsRetryableWorkout() {
        val calls = AtomicInteger()
        fixture(Interceptor { calls.incrementAndGet(); throw IOException("connection lost") }) { repo, uploader ->
            uploader.upload("ride")
            assertEquals(WorkoutStatus.QUEUED, repo.dao.get("ride")!!.status)
            uploader.upload("ride")
            assertEquals(2, calls.get())
        }
    }
    @Test fun persistedUploadResumesPollingWithoutAnotherPost() {
        fixture(Interceptor { chain ->
            assertEquals("GET", chain.request().method)
            response(chain, """{"status":"uploaded","uploadId":"456","activityId":"789"}""")
        }) { repo, uploader ->
            repo.dao.update(repo.dao.get("ride")!!.copy(uploadId = 456, deliveryId = "ride", deliveryOrigin = "https://household.example.invalid", status = WorkoutStatus.PROCESSING))
            uploader.upload("ride")
            assertEquals(WorkoutStatus.UPLOADED, repo.dao.get("ride")!!.status)
        }
    }
    @Test fun dailyRateLimitDefersNetworkCallsUntilReset() {
        val calls = AtomicInteger()
        fixture(Interceptor { chain ->
            calls.incrementAndGet()
            response(chain, "{}", 429).newBuilder().header("X-RateLimit-Usage", "1,4000")
                .header("X-RateLimit-Limit", "400,4000").build()
        }) { repo, uploader ->
            uploader.upload("ride")
            assertEquals(WorkoutStatus.QUEUED, repo.dao.get("ride")!!.status)
            assertTrue(repo.dao.get("ride")!!.retryAfter > System.currentTimeMillis())
            uploader.upload("ride")
            assertEquals(1, calls.get())
        }
    }
    @Test fun differentAthleteCannotReceiveQueuedWorkout() {
        fixture(Interceptor { error("must not call API") }) { repo, uploader ->
            repo.dao.update(repo.dao.get("ride")!!.copy(athleteId = 999))
            uploader.upload("ride")
            assertEquals(WorkoutStatus.FAILED, repo.dao.get("ride")!!.status)
            assertTrue(repo.dao.get("ride")!!.error!!.contains("different Strava account"))
        }
    }
}

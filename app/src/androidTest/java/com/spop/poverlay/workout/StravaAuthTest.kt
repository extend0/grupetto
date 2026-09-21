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

@RunWith(AndroidJUnit4::class)
class StravaAuthTest {
    @Test fun pairingKeepsStravaSecretsOffTabletAndDisconnectRevokesDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context, "test-pairing")
        vault.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var revoked = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("household.example.invalid", chain.request().url.host)
            assertNull(chain.request().url.fragment)
            val result = if (chain.request().method == "POST") {
                val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                assertEquals("a".repeat(64), body.getString("code"))
                assertEquals(64, body.getString("credential").length)
                """{"athleteId":123,"member":"member@example.com"}"""
            } else {
                revoked = true
                assertTrue(chain.request().header("Authorization")!!.startsWith("Bearer "))
                "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = StravaAuthManager(context, scope, client, vault)
        try {
            auth.connect("https://household.example.invalid/#pair=" + "a".repeat(64))
            assertEquals(123L, auth.connection().athlete)
            assertFalse(vault.read().has("client_secret"))
            assertFalse(vault.read().has("access_token"))
            auth.disconnect()
            assertTrue(revoked)
            assertNull(auth.athleteId.value)
        } finally { scope.cancel(); vault.clear() }
    }
    @Test fun rejectsUnsafePairingLinksWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = CredentialVault(context, "test-pair-validation"); vault.clear()
        val auth = StravaAuthManager(context, scope, OkHttpClient.Builder().addInterceptor { error("No request expected") }.build(), vault)
        try {
            for (link in listOf("http://example.com/#pair=", "https://user:password@example.com/#pair=", "https://example.com/path#pair=")) {
                try { auth.connect(link + "a".repeat(64)); fail("unsafe link accepted") } catch (_: IllegalArgumentException) { }
            }
        } finally { scope.cancel(); vault.clear() }
    }
    @Test fun oldDirectStravaCredentialsAreRemovedOnMigration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = CredentialVault(context, "test-old-auth")
        vault.write(JSONObject().put("client_secret", "test-secret").put("athlete_id", 123))
        val auth = StravaAuthManager(context, scope, vault = vault)
        try { auth.initialized.await(); assertNull(auth.athleteId.value); assertEquals(0, vault.read().length()) }
        finally { scope.cancel(); vault.clear() }
    }
}

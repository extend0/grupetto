package com.spop.poverlay.workout

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.spop.poverlay.strava.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StravaAuthTest {
    @Test fun callbackValidationAndConcurrentTokenRotation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context, "test-oauth")
        vault.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val refreshes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = chain.request().body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            val response = if (fields["grant_type"] == "authorization_code") {
                """{"access_token":"old","refresh_token":"first","expires_at":0,"scope":"activity:write","athlete":{"id":123,"firstname":"Test","lastname":"Rider"}}"""
            } else {
                refreshes.incrementAndGet()
                assertEquals("first", fields["refresh_token"])
                """{"access_token":"new","refresh_token":"rotated","expires_at":9999999999}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = StravaAuthManager(context, scope, client, vault)
        try {
            val url = Uri.parse(auth.connect("123", "test-client-secret"))
            val callback = Uri.parse(url.getQueryParameter("redirect_uri"))
            val valid = callback.buildUpon().appendQueryParameter("state", url.getQueryParameter("state"))
                .appendQueryParameter("code", "one-use").appendQueryParameter("scope", "activity:write").build().toString()
            try { auth.acceptCallback(valid.replace("state=", "badstate=")); fail("invalid state accepted") } catch (_: IllegalArgumentException) { }
            auth.acceptCallback(valid)
            assertEquals(123L, auth.athleteId.value)
            val results = listOf(async { auth.accessToken() }, async { auth.accessToken() }).awaitAll()
            assertTrue(results.all { it.second == "new" })
            assertEquals(1, refreshes.get())
            assertEquals("rotated", vault.read().getString("refresh_token"))
            try { auth.acceptCallback(valid); fail("callback reused") } catch (_: IllegalArgumentException) { }
            auth.disconnect()
            assertNull(auth.athleteId.value)
            assertFalse(vault.read().has("refresh_token"))
        } finally { auth.cancelAuthorization(); scope.cancel(); vault.clear() }
    }

    @Test fun missingWriteScopeCannotConnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = CredentialVault(context, "test-oauth-scope")
        val auth = StravaAuthManager(context, scope, vault = vault)
        try {
            val url = Uri.parse(auth.connect("123", "test-client-secret"))
            val callback = Uri.parse(url.getQueryParameter("redirect_uri")).buildUpon()
                .appendQueryParameter("state", url.getQueryParameter("state")).appendQueryParameter("code", "unused")
                .appendQueryParameter("scope", "read").build()
            try { auth.acceptCallback(callback.toString()); fail("read-only scope accepted") } catch (_: IllegalArgumentException) { }
            assertNull(auth.athleteId.value)
        } finally { auth.cancelAuthorization(); scope.cancel(); vault.clear() }
    }
    @Test fun loopbackCallbackWorksInTabletBrowser() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = CredentialVault(context, "test-browser-oauth")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"test","refresh_token":"test","expires_at":9999999999,"scope":"activity:write","athlete":{"id":123,"firstname":"Test"}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val auth = StravaAuthManager(context, scope, client, vault)
        try {
            val url = Uri.parse(auth.connect("123", "test-client-secret"))
            val callback = Uri.parse(url.getQueryParameter("redirect_uri")).buildUpon()
                .appendQueryParameter("state", url.getQueryParameter("state")).appendQueryParameter("code", "test-code")
                .appendQueryParameter("scope", "activity:write").build()
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, callback)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(15_000) { while (auth.athleteId.value != 123L) delay(100) }
            assertEquals("Test", auth.athleteName.value)
        } finally {
            auth.cancelAuthorization(); scope.cancel(); vault.clear()
            context.startActivity(android.content.Intent(context, com.spop.poverlay.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

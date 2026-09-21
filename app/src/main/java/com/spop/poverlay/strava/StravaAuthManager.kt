package com.spop.poverlay.strava

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.KeyPairGeneratorSpec
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

/** RSA wraps a random AES key, including on API 21; all payload data uses authenticated encryption. */
@Suppress("DEPRECATION")
class CredentialVault(private val context: Context, name: String = "strava-credentials") {
    private val file = File(context.noBackupFilesDir, name)
    private val alias = "grupetto-$name"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun read(): JSONObject {
        val atomic = android.util.AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return JSONObject()
        val parts = atomic.openRead().bufferedReader().use { it.readText() }.split('.')
        val unwrap = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        unwrap.init(Cipher.DECRYPT_MODE, store().getKey(alias, null))
        val aes = unwrap.doFinal(Base64.decode(parts[0], Base64.NO_WRAP))
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        return JSONObject(String(decrypt.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    @Synchronized fun write(value: JSONObject) {
        val keys = store()
        if (!keys.containsAlias(alias)) {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyPairGeneratorSpec.Builder(context).setAlias(alias).setSubject(X500Principal("CN=Grupetto Strava"))
                    .setSerialNumber(BigInteger.ONE).setStartDate(Date()).setEndDate(end).build())
            }.generateKeyPair()
        }
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrap = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, store().getCertificate(alias).publicKey) }
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
        val payload = listOf(wrap.doFinal(aes.encoded), encrypt.iv, encrypt.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
            .joinToString(".") { Base64.encodeToString(it, Base64.NO_WRAP) }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(payload.toByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    @Synchronized fun clear() { android.util.AtomicFile(file).delete() }
}

class StravaHttpException(val status: Int, val retryAt: Long = 0) : Exception(when (status) {
    401, 403 -> "Reconnect this tablet on your upload server."
    409 -> "The workout or Strava connection needs attention on your upload server."
    413 -> "This workout is too large to upload. Export the TCX file."
    429 -> "Upload rate limit reached. Delivery will retry later."
    in 500..599 -> "Upload server is temporarily unavailable."
    else -> "Upload server rejected the request (HTTP $status)."
})

data class UploadServerConnection(val origin: String, val athlete: Long, val credential: String)

/** Stores only a member-scoped upload server device credential. Strava tokens remain on the server. */
class StravaAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val vault: CredentialVault = CredentialVault(context),
) {
    val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val mutex = Mutex()
    val initialized = CompletableDeferred<Unit>()
    val athleteName = MutableStateFlow<String?>(null)
    val athleteId = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)
    val origin = MutableStateFlow<String?>(null)
    init { scope.launch(Dispatchers.IO) {
        try { mutex.withLock {
            val data = vault.read()
            if (data.has("client_secret") || data.has("refresh_token")) {
                vault.clear()
                message.value = "Connect to upload server to resume uploads. Your local workouts are kept."
            } else publish(data)
        } } catch (_: Exception) { message.value = "Saved connection could not be opened. Connect again." }
        finally { initialized.complete(Unit) }
    } }
    private fun publish(data: JSONObject) {
        origin.value = data.optString("origin").takeIf { it.isNotBlank() }
        athleteId.value = data.optLong("athlete_id").takeIf { it > 0 && origin.value != null }
        athleteName.value = data.optString("athlete_name").takeIf { it.isNotBlank() && athleteId.value != null }
    }
    suspend fun connect(link: String) = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val uri = Uri.parse(link.trim())
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && (uri.path.isNullOrEmpty() || uri.path == "/") && uri.query == null) {
                "Paste the HTTPS pairing link from your upload server page."
            }
            val code = uri.fragment?.removePrefix("pair=").orEmpty()
            require(uri.fragment?.startsWith("pair=") == true && code.matches(Regex("[a-f0-9]{64}"))) { "Invalid pairing link." }
            val endpoint = "https://${uri.host}"
            val old = vault.read()
            // Reuse this random credential if a pairing response was lost.
            val credential = if (old.optString("pending_code") == code && old.optString("pending_origin") == endpoint)
                old.getString("pending_credential") else ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
            old.put("pending_code", code).put("pending_origin", endpoint).put("pending_credential", credential)
            vault.write(old)
            val payload = JSONObject().put("code", code).put("credential", credential)
            val data = request(endpoint + "/device/v1/pair", "POST", payload.toString().toRequestBody("application/json".toMediaType()))
            val athlete = data.getLong("athleteId")
            check(athlete > 0) { "Upload server did not return a rider." }
            val saved = JSONObject().put("origin", endpoint).put("device_credential", credential).put("athlete_id", athlete)
                .put("athlete_name", "${data.getString("member")} · athlete $athlete")
            vault.write(saved); publish(saved)
            message.value = "Tablet connected. Finished rides will upload through your upload server."
        }
    }
    suspend fun connection(): UploadServerConnection = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val data = vault.read()
            check(data.optLong("athlete_id") > 0 && data.has("device_credential") && data.has("origin")) { "Connect to upload server in settings first." }
            UploadServerConnection(data.getString("origin"), data.getLong("athlete_id"), data.getString("device_credential"))
        }
    }
    internal fun request(url: String, method: String = "GET", body: RequestBody? = null, credential: String? = null): JSONObject {
        val builder = Request.Builder().url(url).method(method, body)
        if (credential != null) builder.header("Authorization", "Bearer $credential")
        return client.newCall(builder.build()).execute().use {
            if (!it.isSuccessful) throw StravaHttpException(it.code, System.currentTimeMillis() + maxOf(30, it.header("Retry-After")?.toLongOrNull() ?: 60) * 1000)
            val response = it.body ?: error("Upload server response was empty.")
            require(response.contentLength() <= 64 * 1024) { "Upload server response was too large." }
            val source = response.source()
            source.request(64 * 1024 + 1)
            require(source.buffer.size <= 64 * 1024) { "Upload server response was too large." }
            JSONObject(source.readUtf8())
        }
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        initialized.await()
        mutex.withLock {
            val data = vault.read()
            var revoked = true
            if (data.has("device_credential") && data.has("origin")) try {
                request(data.getString("origin") + "/device/v1/connection", "DELETE", credential = data.getString("device_credential"))
            } catch (_: Exception) { revoked = false }
            vault.clear(); publish(JSONObject())
            message.value = if (revoked) "Tablet disconnected. Local workouts are kept. Accepted deliveries continue."
                else "Disconnected locally. Revoke the tablet on your upload server page when online. Accepted deliveries continue."
        }
    }
}

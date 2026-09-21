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
    401 -> "Strava authorization expired. Reconnect your account."
    403 -> "Strava access denied. Check application access and reconnect with upload permission."
    429 -> "Strava rate limit reached. Upload will retry later."
    in 500..599 -> "Strava is temporarily unavailable."
    else -> "Strava rejected the request (HTTP $status)."
})

class StravaAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val vault: CredentialVault = CredentialVault(context),
) {
    private val mutex = Mutex()
    val initialized = CompletableDeferred<Unit>()
    val athleteName = MutableStateFlow<String?>(null)
    val athleteId = MutableStateFlow<Long?>(null)
    val message = MutableStateFlow<String?>(null)
    val authorizationUrl = MutableStateFlow<String?>(null)
    private var listener: ServerSocket? = null
    private var authJob: Job? = null
    private var state: String? = null
    private var callback: Uri? = null
    private var authStartedAt = 0L

    init { scope.launch(Dispatchers.IO) {
        try { mutex.withLock { publish(vault.read()) } } catch (_: Exception) { message.value = "Saved credentials could not be opened. Disconnect and connect again." }
        finally { initialized.complete(Unit) }
    } }
    private fun publish(data: JSONObject) {
        athleteId.value = data.optLong("athlete_id").takeIf { it > 0 }
        athleteName.value = data.optString("athlete_name").takeIf { it.isNotBlank() }
    }
    suspend fun connect(clientId: String, secret: String): String = withContext(Dispatchers.IO) {
        require(clientId.toLongOrNull()?.let { it > 0 } == true && secret.isNotBlank()) { "Enter your personal Strava client ID and secret." }
        cancelAuthorization()
        mutex.withLock {
            vault.write(JSONObject().put("client_id", clientId.trim()).put("client_secret", secret.trim()))
            publish(JSONObject())
        }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10 * 60 * 1000
        listener = server
        state = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        authStartedAt = android.os.SystemClock.elapsedRealtime()
        callback = Uri.parse("http://127.0.0.1:${server.localPort}/strava/callback")
        val url = Uri.parse("https://www.strava.com/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId.trim()).appendQueryParameter("redirect_uri", callback.toString())
            .appendQueryParameter("response_type", "code").appendQueryParameter("scope", "activity:write")
            .appendQueryParameter("approval_prompt", "force").appendQueryParameter("state", state).build().toString()
        authorizationUrl.value = url
        message.value = "Complete authorization in your browser."
        authJob = scope.launch(Dispatchers.IO) {
            try {
                while (!server.isClosed) {
                    val remaining = 600_000 - (android.os.SystemClock.elapsedRealtime() - authStartedAt)
                    if (remaining <= 0) break
                    server.soTimeout = remaining.toInt()
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                        val target = line.split(' ').getOrNull(1).orEmpty()
                        val uri = Uri.parse("http://127.0.0.1:${server.localPort}$target")
                        if (uri.path == "/strava/callback" && uri.getQueryParameter("state") == state) {
                            try {
                                acceptCallback(uri.toString())
                                val body = "<html><body><h2>Strava connected</h2><a href=\"${context.packageName}://strava/return\">Return to Grupetto</a><p>You can close this tab.</p></body></html>"
                                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n$body".toByteArray())
                            } catch (_: Exception) {
                                socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nAuthorization failed. Return to Grupetto and reconnect.".toByteArray())
                                message.value = "Authorization failed. Check credentials and reconnect."
                                throw IllegalStateException("Authorization failed. Check credentials and reconnect.")
                            }
                        } else socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (_: Exception) {
                if (state != null) message.value = "Authorization ended or timed out. Connect again to retry."
            } finally { server.close(); if (listener === server) { listener = null; state = null; authorizationUrl.value = null } }
        }
        url
    }
    suspend fun acceptCallback(url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val uri = Uri.parse(url.trim())
            require(state != null && android.os.SystemClock.elapsedRealtime() - authStartedAt < 600_000 &&
                uri.scheme == callback?.scheme && uri.host == callback?.host && uri.port == callback?.port &&
                uri.path == callback?.path && uri.getQueryParameter("state") == state) { "Invalid or expired authorization response. Connect again." }
            require(uri.getQueryParameter("error") == null) { "Authorization was cancelled." }
            require(uri.getQueryParameter("scope").orEmpty().split(',', ' ').contains("activity:write")) { "Allow activity uploads when connecting Strava." }
            val code = uri.getQueryParameter("code") ?: error("Authorization code is missing.")
            state = null // Single use even if exchange fails.
            val data = vault.read()
            val result = tokenRequest(data, "authorization_code", "code", code)
            if (result.has("scope")) require(result.getString("scope").split(',', ' ').contains("activity:write")) { "Strava did not grant upload permission." }
            val athlete = result.getJSONObject("athlete")
            data.put("athlete_id", athlete.getLong("id"))
            data.put("athlete_name", listOf(athlete.optString("firstname"), athlete.optString("lastname")).joinToString(" ").trim())
            saveTokens(data, result)
            publish(data)
            message.value = "Connected. Finished workouts can upload automatically."
            authorizationUrl.value = null
            listener?.close()
        }
    }
    suspend fun accessToken(force: Boolean = false): Pair<Long, String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val data = vault.read()
            val id = data.optLong("athlete_id")
            check(id > 0 && data.has("refresh_token")) { "Connect Strava in settings first." }
            if (force || data.optLong("expires_at") <= System.currentTimeMillis() / 1000 + 60) {
                saveTokens(data, tokenRequest(data, "refresh_token", "refresh_token", data.getString("refresh_token")))
            }
            id to data.getString("access_token")
        }
    }
    private fun tokenRequest(data: JSONObject, grant: String, key: String, value: String): JSONObject {
        val body = FormBody.Builder().add("client_id", data.getString("client_id"))
            .add("client_secret", data.getString("client_secret")).add("grant_type", grant).add(key, value).build()
        return client.newCall(Request.Builder().url("https://www.strava.com/oauth/token").post(body).build()).execute().use {
            if (!it.isSuccessful) throw StravaHttpException(it.code)
            JSONObject(it.body!!.string())
        }
    }
    private fun saveTokens(data: JSONObject, tokens: JSONObject) {
        data.put("access_token", tokens.getString("access_token")).put("refresh_token", tokens.getString("refresh_token"))
            .put("expires_at", tokens.getLong("expires_at"))
        vault.write(data)
    }
    fun cancelAuthorization() {
        state = null
        listener?.close(); listener = null
        authJob?.cancel(); authJob = null
        authorizationUrl.value = null
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        cancelAuthorization()
        mutex.withLock { vault.clear(); publish(JSONObject()); message.value = "Disconnected. Local workouts are kept." }
    }
}

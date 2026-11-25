package io.github.whywood.superpi.adb

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ShellClient(
    private val port: Int,
    private val tokenProvider: () -> String,
) {
    constructor(port: Int, token: String) : this(port, { token })

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val execHttp: OkHttpClient = http.newBuilder()
        .retryOnConnectionFailure(false)
        .readTimeout(MAX_EXEC_READ_SECONDS, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String = "http://127.0.0.1:$port"

    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        fetchVerifiedChallenge() != null
    }

    suspend fun exec(command: String, timeoutSeconds: Long = 20): String = withContext(Dispatchers.IO) {
        val request = signedRequest(
            method = "POST",
            path = "/exec",
            body = command,
            extraHeaders = mapOf(TIMEOUT_HEADER to timeoutSeconds.toString()),
        ) ?: throw IOException("daemon challenge failed")
        suspendCancellableCoroutine { continuation ->
            val call = execHttp.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            check(it.isSuccessful) { "daemon request failed: ${it.code}" }
                            it.body.string()
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    suspend fun startHelper(apkPath: String, helperPort: Int, nonce: String): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(apkPath, "UTF-8")
            val request = signedRequest(
                method = "GET",
                path = "/start-helper?apk=$encoded&port=$helperPort&nonce=$nonce",
            ) ?: return@withContext null
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                Regex("pid=(\\d+)").find(response.body.string())?.groupValues?.get(1)?.toIntOrNull()
            }
        }.getOrNull()
    }

    suspend fun killHelper(pid: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = signedRequest(
                method = "GET",
                path = "/kill-helper?pid=$pid",
            ) ?: return@withContext false
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun shutdown(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = signedRequest(
                method = "POST",
                path = "/shutdown",
            ) ?: return@withContext false
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun fetchVerifiedChallenge(): String? = runCatching {
        val secret = tokenProvider()
        if (secret.isBlank()) return@runCatching null
        val clientNonce = ShellAuth.randomHex(16)
        val request = Request.Builder()
            .url("$baseUrl/challenge?cnonce=$clientNonce")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body.string()
            val uid = Regex("\\buid=(\\d+)\\b").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@use null
            val protocol = Regex("\\bprotocol=(\\d+)\\b").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@use null
            val serverNonce = Regex("\\bsnonce=([A-Za-z0-9]+)\\b").find(body)?.groupValues?.get(1) ?: return@use null
            val proof = Regex("\\bproof=([0-9a-fA-F]+)\\b").find(body)?.groupValues?.get(1) ?: return@use null
            if (uid != 2000 || protocol != ShellAuth.SHELL_PROTOCOL_VERSION) return@use null
            val expected = ShellAuth.daemonProof(secret, clientNonce, serverNonce, uid)
            if (!ShellAuth.constantTimeEquals(proof, expected)) return@use null
            serverNonce
        }
    }.getOrNull()

    private fun signedRequest(
        method: String,
        path: String,
        body: String = "",
        extraHeaders: Map<String, String> = emptyMap(),
    ): Request? {
        val secret = tokenProvider()
        if (secret.isBlank()) return null
        val serverNonce = fetchVerifiedChallenge() ?: return null
        val auth = ShellAuth.clientProof(secret, serverNonce, method, path, ShellAuth.sha256Hex(body))
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .header(NONCE_HEADER, serverNonce)
            .header(AUTH_HEADER, auth)
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (method.equals("POST", ignoreCase = true)) {
            builder.post(body.toRequestBody(TEXT_PLAIN))
        }
        return builder.build()
    }

    private companion object {
        const val NONCE_HEADER = "X-Nonce"
        const val AUTH_HEADER = "X-Auth"
        const val TIMEOUT_HEADER = "X-Timeout"
        const val MAX_EXEC_READ_SECONDS = 125L
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
    }
}

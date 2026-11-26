package io.github.whywood.superpi.host.server

import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lightweight synchronous client used by CLI subcommands (`status`, `bot`, `relay`, `provider`, `config`)
 * to talk to an active `super-pi serve` daemon via `~/.super-pi/cache/serve.lock`.
 */
class LocalControlClient(
    private val lockFile: File,
) : Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun activeLock(): ServeLockInfo? {
        if (!lockFile.isFile) return null
        val info = runCatching {
            json.decodeFromString(ServeLockInfo.serializer(), lockFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val alive = runCatching {
            info.port in 1..65535 &&
                ProcessHandle.of(info.pid).map { it.isAlive }.orElse(false)
        }.getOrDefault(false)
        if (!alive) {
            // Compare-before-delete to avoid removing a lock written concurrently by a newly starting daemon.
            val stillSame = runCatching {
                val latest = json.decodeFromString(ServeLockInfo.serializer(), lockFile.readText(Charsets.UTF_8))
                latest.pid == info.pid && latest.token == info.token
            }.getOrDefault(false)
            if (stillSame) {
                runCatching { lockFile.delete() }
            }
            return null
        }
        return info
    }

    override fun close() {
        runCatching {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    fun fetchStatus(): StatusResponse? = withLock { info ->
        val req = Request.Builder()
            .url("http://127.0.0.1:${info.port}/api/v1/status")
            .header("Authorization", "Bearer ${info.token}")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withLock null
            val body = resp.body.string()
            json.decodeFromString(StatusResponse.serializer(), body)
        }
    }

    fun requestPairCode(): Result<PairCodeResponse>? {
        val lock = activeLock() ?: return null
        return try {
            val req = Request.Builder()
                .url("http://127.0.0.1:${lock.port}/api/v1/relay/pair-code")
                .header("Authorization", "Bearer ${lock.token}")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    val err = runCatching { json.decodeFromString(ApiErrorResponse.serializer(), body).error }
                        .getOrElse { "HTTP ${resp.code}" }
                    return Result.failure(IllegalStateException(err))
                }
                Result.success(json.decodeFromString(PairCodeResponse.serializer(), body))
            }
        } catch (_: java.io.IOException) {
            null
        } catch (err: Exception) {
            Result.failure(err)
        }
    }

    fun reloadDaemon(): Boolean = withLock { info ->
        val req = Request.Builder()
            .url("http://127.0.0.1:${info.port}/api/v1/reload")
            .header("Authorization", "Bearer ${info.token}")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    } ?: false

    private inline fun <T> withLock(block: (ServeLockInfo) -> T?): T? {
        val info = activeLock() ?: return null
        return runCatching { block(info) }.getOrNull()
    }
}

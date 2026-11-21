package io.github.whywood.superpi.data.relay

import java.io.IOException
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RelayMemorySnapshot(
    val version: Long,
    val files: Map<String, String>,
    val versions: Map<String, Long>,
)

enum class RelayMemoryFailure {
    Conflict,
    Unauthorized,
    NotFound,
    Unreachable,
    BadResponse,
    Rejected,
}

class RelayMemoryException(
    val failure: RelayMemoryFailure,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: failure.name, cause)

data class RelayMemoryRollupLease(
    val acquired: Boolean,
    val leaseId: String? = null,
    val holderDeviceId: String? = null,
    val expiresAt: Long = 0L,
)

interface RelayMemoryApi {
    suspend fun snapshot(account: RelayAccount, token: String): RelayMemorySnapshot

    suspend fun write(account: RelayAccount, token: String, request: RelayMemoryWrite): Long

    suspend fun acquireRollupLease(
        account: RelayAccount,
        token: String,
        ttlMs: Long,
    ): RelayMemoryRollupLease? = null

    suspend fun releaseRollupLease(
        account: RelayAccount,
        token: String,
        leaseId: String?,
    ): Boolean = false
}

class RelayMemoryClient : RelayMemoryApi {
    override suspend fun snapshot(account: RelayAccount, token: String): RelayMemorySnapshot =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${account.origin}/v1/memory/snapshot")
                .header("authorization", "Bearer $token")
                .get()
                .build()
            execute(account, request) { body ->
                RelayProtocol.parseMemorySnapshot(body) ?: failed(RelayMemoryFailure.BadResponse)
            }
        }

    override suspend fun write(
        account: RelayAccount,
        token: String,
        request: RelayMemoryWrite,
    ): Long = withContext(Dispatchers.IO) {
        val body = RelayProtocol
            .memoryWriteRequest(
                op = request.op,
                path = request.path,
                text = request.text,
                section = request.section,
                baseVersion = request.baseVersion,
                idempotencyKey = request.idempotencyKey,
            )
            .toRequestBody(JSON)
        val http = Request.Builder()
            .url("${account.origin}/v1/memory/write")
            .header("authorization", "Bearer $token")
            .post(body)
            .build()
        execute(account, http) { text ->
            RelayProtocol.parseMemoryVersion(text) ?: failed(RelayMemoryFailure.BadResponse)
        }
    }

    override suspend fun acquireRollupLease(
        account: RelayAccount,
        token: String,
        ttlMs: Long,
    ): RelayMemoryRollupLease? = withContext(Dispatchers.IO) {
        val body = RelayProtocol.memoryRollupLeaseRequest(ttlMs).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${account.origin}/v1/memory/rollup-lease")
            .header("authorization", "Bearer $token")
            .post(body)
            .build()
        try {
            execute(account, request) { text ->
                RelayProtocol.parseMemoryRollupLease(text) ?: failed(RelayMemoryFailure.BadResponse)
            }
        } catch (error: RelayMemoryException) {
            if (error.failure == RelayMemoryFailure.NotFound) null else throw error
        }
    }

    override suspend fun releaseRollupLease(
        account: RelayAccount,
        token: String,
        leaseId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val query = leaseId?.takeIf { it.isNotBlank() }?.let {
            "?leaseId=${URLEncoder.encode(it, Charsets.UTF_8.name())}"
        }.orEmpty()
        val request = Request.Builder()
            .url("${account.origin}/v1/memory/rollup-lease$query")
            .header("authorization", "Bearer $token")
            .delete()
            .build()
        runCatching {
            execute(account, request) { text ->
                RelayProtocol.parseMemoryRollupLeaseRelease(text)
            }
        }.getOrDefault(false)
    }

    private fun <T> execute(account: RelayAccount, request: Request, parse: (String) -> T): T {
        val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
        val client = RelayTls.client(pin = pin, remote = true)
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                return when {
                    response.isSuccessful -> parse(text)
                    response.code == 404 -> failed(RelayMemoryFailure.NotFound)
                    response.code == 409 -> failed(RelayMemoryFailure.Conflict)
                    response.code == 401 || response.code == 403 ->
                        failed(RelayMemoryFailure.Unauthorized)
                    else -> failed(
                        RelayMemoryFailure.Rejected,
                        RelayProtocol.parseErrorMessage(text) ?: "HTTP ${response.code}",
                    )
                }
            }
        } catch (error: RelayMemoryException) {
            throw error
        } catch (error: SSLException) {
            throw RelayMemoryException(RelayMemoryFailure.BadResponse, cause = error)
        } catch (error: IOException) {
            throw RelayMemoryException(RelayMemoryFailure.Unreachable, cause = error)
        }
    }

    private fun failed(failure: RelayMemoryFailure, detail: String? = null): Nothing =
        throw RelayMemoryException(failure, detail)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

data class RelayMemoryWrite(
    val op: String,
    val path: String,
    val text: String,
    val section: String?,
    val baseVersion: Long?,
    val idempotencyKey: String,
)

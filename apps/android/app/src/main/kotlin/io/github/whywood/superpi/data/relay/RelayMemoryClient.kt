package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import java.io.IOException
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class RelayMemoryRejectedOp(
    val opId: String,
    val reason: String,
)

data class RelayMemoryPushResponse(
    val serverSeq: Long,
    val appliedCount: Int,
    val skippedCount: Int,
    val lastDreamAt: Long?,
    val rejectedCount: Int = 0,
    val rejected: List<RelayMemoryRejectedOp> = emptyList(),
)

data class RelayMemoryPullResponse(
    val serverSeq: Long,
    val hasMore: Boolean,
    val nextSince: Long,
    val lastDreamAt: Long?,
    val entries: List<MemoryRemotePullEntry>,
)

data class RelayMemoryDreamLease(
    val acquired: Boolean,
    val leaseId: String? = null,
    val holderDeviceId: String? = null,
    val expiresAt: Long = 0L,
    val lastDreamAt: Long? = null,
    val serverSeq: Long = 0L,
)

enum class RelayMemoryFailure {
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

interface RelayMemoryApi {
    suspend fun push(
        account: RelayAccount,
        token: String,
        ops: List<MemorySyncOpItem>,
        dreamCompletedAt: Long? = null,
    ): RelayMemoryPushResponse

    suspend fun pull(
        account: RelayAccount,
        token: String,
        since: Long,
        limit: Int = 200,
    ): RelayMemoryPullResponse

    suspend fun acquireDreamLease(
        account: RelayAccount,
        token: String,
        ttlMs: Long,
        minIntervalMs: Long = 0L,
    ): RelayMemoryDreamLease? = null

    suspend fun releaseDreamLease(
        account: RelayAccount,
        token: String,
        leaseId: String?,
    ): Boolean = false
}

class RelayMemoryClient : RelayMemoryApi {
    override suspend fun push(
        account: RelayAccount,
        token: String,
        ops: List<MemorySyncOpItem>,
        dreamCompletedAt: Long?,
    ): RelayMemoryPushResponse = withContext(Dispatchers.IO) {
        val body = RelayProtocol.memoryPushRequest(ops, dreamCompletedAt).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${account.origin}/v2/memory/push")
            .header("authorization", "Bearer $token")
            .post(body)
            .build()
        execute(account, request) { text ->
            RelayProtocol.parseMemoryPushResponse(text) ?: failed(RelayMemoryFailure.BadResponse)
        }
    }

    override suspend fun pull(
        account: RelayAccount,
        token: String,
        since: Long,
        limit: Int,
    ): RelayMemoryPullResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${account.origin}/v2/memory/pull?since=$since&limit=$limit")
            .header("authorization", "Bearer $token")
            .get()
            .build()
        execute(account, request) { text ->
            RelayProtocol.parseMemoryPullResponse(text) ?: failed(RelayMemoryFailure.BadResponse)
        }
    }

    override suspend fun acquireDreamLease(
        account: RelayAccount,
        token: String,
        ttlMs: Long,
        minIntervalMs: Long,
    ): RelayMemoryDreamLease? = withContext(Dispatchers.IO) {
        val body = RelayProtocol.memoryDreamLeaseRequest(ttlMs, minIntervalMs).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${account.origin}/v2/memory/dream-lease")
            .header("authorization", "Bearer $token")
            .post(body)
            .build()
        try {
            execute(account, request) { text ->
                RelayProtocol.parseMemoryDreamLeaseResponse(text) ?: failed(RelayMemoryFailure.BadResponse)
            }
        } catch (error: RelayMemoryException) {
            if (error.failure == RelayMemoryFailure.NotFound) null else throw error
        }
    }

    override suspend fun releaseDreamLease(
        account: RelayAccount,
        token: String,
        leaseId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val query = leaseId?.takeIf { it.isNotBlank() }?.let {
            "?leaseId=${URLEncoder.encode(it, Charsets.UTF_8.name())}"
        }.orEmpty()
        val request = Request.Builder()
            .url("${account.origin}/v2/memory/dream-lease$query")
            .header("authorization", "Bearer $token")
            .delete()
            .build()
        runCatching {
            execute(account, request) { text ->
                RelayProtocol.parseMemoryDreamLeaseRelease(text)
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

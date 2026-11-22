package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RemoteFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String? = null,
)

data class RemoteFileListing(
    val path: String,
    val entries: List<RemoteFileItem>,
)

data class RemoteFileTransferProgress(
    val transferred: Long,
    val total: Long,
)

sealed interface RemoteFileResult<out T> {
    data class Ok<T>(val value: T) : RemoteFileResult<T>
    data class Error(val code: String, val message: String) : RemoteFileResult<Nothing>
}

class RemoteFileClient(
    private val transport: RemoteAgentTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun list(
        deviceId: String,
        botId: String,
        path: String = "",
        query: String? = null,
        limit: Int = DEFAULT_LIMIT,
    ): RemoteFileResult<RemoteFileListing> {
        checkReady(deviceId)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("path", path)
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { put("query", it) }
            put("limit", limit)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "file.list", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                val resolvedPath = (json?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
                val array = json?.get("entries") as? JsonArray ?: JsonArray(emptyList())
                val entries = array.mapNotNull { parseEntry(it as? JsonObject) }
                RemoteFileResult.Ok(RemoteFileListing(resolvedPath, entries))
            }
            is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun stat(
        deviceId: String,
        botId: String,
        path: String,
    ): RemoteFileResult<RemoteFileItem> {
        checkReady(deviceId)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("path", path)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "file.stat", params)) {
            is RemoteCallOutcome.Ok -> {
                val item = parseEntry(outcome.result?.get("entry") as? JsonObject)
                    ?: return RemoteFileResult.Error("bad_response", "invalid file entry")
                RemoteFileResult.Ok(item)
            }
            is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun downloadToStream(
        deviceId: String,
        botId: String,
        remotePath: String,
        outputStream: OutputStream,
        onProgress: (RemoteFileTransferProgress) -> Unit = {},
    ): RemoteFileResult<Long> {
        checkReady(deviceId)?.let { return it }
        var offset = 0L
        var totalSize = 0L
        while (true) {
            coroutineContext.ensureActive()
            val params = buildJsonObject {
                put("botId", botId)
                put("path", remotePath)
                put("offset", offset)
                put("length", CHUNK_BYTES)
            }
            when (val outcome = transport.callWithOutcome(deviceId, "file.read_chunk", params, TRANSFER_TIMEOUT_MS)) {
                is RemoteCallOutcome.Failed -> return RemoteFileResult.Error(outcome.error.code, outcome.error.message)
                is RemoteCallOutcome.Ok -> {
                    val result = outcome.result ?: return RemoteFileResult.Error("bad_response", "empty chunk response")
                    val dataB64 = (result["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val eof = result["eof"]?.jsonPrimitive?.booleanOrNull == true
                    totalSize = result["size"]?.jsonPrimitive?.longOrNull ?: totalSize
                    val bytes = try {
                        if (dataB64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(dataB64)
                    } catch (_: IllegalArgumentException) {
                        return RemoteFileResult.Error("bad_response", "invalid base64 chunk")
                    }
                    if (bytes.isNotEmpty()) {
                        withContext(ioDispatcher) {
                            outputStream.write(bytes)
                        }
                        offset += bytes.size
                    }
                    onProgress(RemoteFileTransferProgress(offset, totalSize))
                    if (eof) return RemoteFileResult.Ok(offset)
                    if (bytes.isEmpty()) {
                        return RemoteFileResult.Error("bad_response", "unexpected empty chunk before eof")
                    }
                }
            }
        }
    }

    suspend fun downloadToCacheFile(
        deviceId: String,
        botId: String,
        remotePath: String,
        cacheDir: File,
        onProgress: (RemoteFileTransferProgress) -> Unit = {},
    ): RemoteFileResult<File> {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("$deviceId:$botId:$remotePath".toByteArray(Charsets.UTF_8))
        val folderKey = keyBytes.take(8).joinToString("") { "%02x".format(it) }
        val fileName = remotePath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "file.bin" }
        val bucketDir = File(cacheDir, folderKey).apply { mkdirs() }
        val target = File(bucketDir, fileName)
        val tmp = File(bucketDir, ".tmp-${UUID.randomUUID()}.part")
        try {
            val outcome = withContext(ioDispatcher) {
                tmp.outputStream().use { out ->
                    downloadToStream(deviceId, botId, remotePath, out, onProgress)
                }
            }
            return when (outcome) {
                is RemoteFileResult.Error -> {
                    tmp.delete()
                    outcome
                }
                is RemoteFileResult.Ok -> {
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    RemoteFileResult.Ok(target)
                }
            }
        } catch (cancelled: CancellationException) {
            tmp.delete()
            throw cancelled
        } catch (failure: Exception) {
            tmp.delete()
            return RemoteFileResult.Error("io_error", failure.message ?: "download failed")
        }
    }

    suspend fun uploadFromStream(
        deviceId: String,
        botId: String,
        remotePath: String,
        inputStream: InputStream,
        totalBytes: Long? = null,
        overwrite: Boolean = true,
        onProgress: (RemoteFileTransferProgress) -> Unit = {},
    ): RemoteFileResult<Long> {
        checkReady(deviceId)?.let { return it }
        if (totalBytes != null && totalBytes > MAX_TRANSFER_BYTES) {
            return RemoteFileResult.Error("payload_too_large", "file exceeds 50 MB limit")
        }
        val transferId = "ul-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val firstBuf = readExactOrEof(inputStream, CHUNK_BYTES)
        if (firstBuf.isEmpty()) {
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val params = buildJsonObject {
                put("botId", botId)
                put("path", remotePath)
                put("transferId", transferId)
                put("offset", 0L)
                put("data", "")
                put("eof", true)
                put("overwrite", overwrite)
                put("expectedSha256", sha)
            }
            return when (val outcome = transport.callWithOutcome(deviceId, "file.write_chunk", params, TRANSFER_TIMEOUT_MS)) {
                is RemoteCallOutcome.Ok -> {
                    onProgress(RemoteFileTransferProgress(0L, totalBytes ?: 0L))
                    RemoteFileResult.Ok(0L)
                }
                is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
            }
        }

        var current = firstBuf
        var offset = 0L
        while (true) {
            coroutineContext.ensureActive()
            if (offset + current.size > MAX_TRANSFER_BYTES) {
                return RemoteFileResult.Error("payload_too_large", "file exceeds 50 MB limit")
            }
            val next = readExactOrEof(inputStream, CHUNK_BYTES)
            val eof = next.isEmpty()
            digest.update(current)
            val expectedSha = if (eof) digest.digest().joinToString("") { "%02x".format(it) } else null
            val params = buildJsonObject {
                put("botId", botId)
                put("path", remotePath)
                put("transferId", transferId)
                put("offset", offset)
                put("data", Base64.getEncoder().encodeToString(current))
                put("eof", eof)
                if (offset == 0L) put("overwrite", overwrite)
                expectedSha?.let { put("expectedSha256", it) }
            }
            when (val outcome = transport.callWithOutcome(deviceId, "file.write_chunk", params, TRANSFER_TIMEOUT_MS)) {
                is RemoteCallOutcome.Failed -> return RemoteFileResult.Error(outcome.error.code, outcome.error.message)
                is RemoteCallOutcome.Ok -> {
                    offset += current.size
                    onProgress(RemoteFileTransferProgress(offset, totalBytes ?: offset))
                    if (eof) return RemoteFileResult.Ok(offset)
                    current = next
                }
            }
        }
    }

    suspend fun mkdir(deviceId: String, botId: String, path: String): RemoteFileResult<String> {
        checkReady(deviceId)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("path", path)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "file.mkdir", params)) {
            is RemoteCallOutcome.Ok -> {
                val createdPath = outcome.result.stringOrNull("path") ?: path
                RemoteFileResult.Ok(createdPath)
            }
            is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun rename(
        deviceId: String,
        botId: String,
        from: String,
        to: String,
    ): RemoteFileResult<Pair<String, String>> {
        checkReady(deviceId)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("from", from)
            put("to", to)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "file.rename", params)) {
            is RemoteCallOutcome.Ok -> RemoteFileResult.Ok(
                (outcome.result.stringOrNull("from") ?: from) to (outcome.result.stringOrNull("to") ?: to),
            )
            is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun delete(
        deviceId: String,
        botId: String,
        path: String,
        recursive: Boolean = false,
    ): RemoteFileResult<Boolean> {
        checkReady(deviceId)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("path", path)
            if (recursive) put("recursive", true)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "file.delete", params)) {
            is RemoteCallOutcome.Ok -> RemoteFileResult.Ok(true)
            is RemoteCallOutcome.Failed -> RemoteFileResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    private fun checkReady(deviceId: String): RemoteFileResult.Error? {
        if (deviceId !in transport.onlineDevices.value) {
            return RemoteFileResult.Error(RemoteCallCodes.NOT_CONNECTED, "device is offline")
        }
        if (!transport.supportsPeerCapability(deviceId, CAP_WORKSPACE_FILES)) {
            return RemoteFileResult.Error(RemoteBotAdminClient.CAPABILITY_UNAVAILABLE, "peer upgrade required")
        }
        return null
    }

    private suspend fun readExactOrEof(input: InputStream, maxBytes: Int): ByteArray = withContext(ioDispatcher) {
        val buf = ByteArray(maxBytes)
        var filled = 0
        while (filled < maxBytes) {
            val read = input.read(buf, filled, maxBytes - filled)
            if (read <= 0) break
            filled += read
        }
        if (filled == maxBytes) buf else buf.copyOf(filled)
    }

    private fun parseEntry(json: JsonObject?): RemoteFileItem? {
        val obj = json ?: return null
        val name = obj.stringOrNull("name") ?: return null
        val path = obj.stringOrNull("path") ?: return null
        val type = obj.stringOrNull("type") ?: return null
        if (type != "file" && type != "directory") return null
        return RemoteFileItem(
            name = name,
            path = path,
            isDirectory = type == "directory",
            size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            modifiedAt = obj["modifiedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            mimeType = obj.stringOrNull("mimeType"),
        )
    }

    private fun JsonObject?.stringOrNull(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }

    companion object {
        const val CAP_WORKSPACE_FILES = "workspace-files"
        const val CHUNK_BYTES = 256 * 1024
        const val MAX_TRANSFER_BYTES = 50L * 1024L * 1024L
        const val DEFAULT_LIMIT = 500
        private const val TRANSFER_TIMEOUT_MS = 30_000L
    }
}

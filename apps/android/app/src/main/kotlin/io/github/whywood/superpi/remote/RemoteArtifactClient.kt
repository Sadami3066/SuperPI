package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

sealed interface RemoteArtifactResult {
    data class Ok(val file: File) : RemoteArtifactResult
    data object PeerUpgradeRequired : RemoteArtifactResult
    data class Error(val code: String, val message: String) : RemoteArtifactResult
}

class RemoteArtifactClient(
    private val transport: RemoteAgentTransport,
    private val cacheRootForEviction: File? = null,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
) {
    private val inflightLock = Mutex()
    private val inflight = HashMap<String, CompletableDeferred<RemoteArtifactResult>>()

    fun cachedFile(
        workspaceRoot: File,
        artifactId: String,
        name: String,
        expectedSize: Long? = null,
    ): File? {
        val file = targetFile(workspaceRoot, artifactId, name) ?: return null
        if (!file.isFile) return null
        if (expectedSize != null && expectedSize >= 0L && file.length() != expectedSize) return null
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    suspend fun fetch(
        deviceId: String,
        botId: String,
        workspaceRoot: File,
        artifactId: String,
        name: String,
        expectedSize: Long? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): RemoteArtifactResult {
        cachedFile(workspaceRoot, artifactId, name, expectedSize)?.let { existing ->
            val size = expectedSize ?: existing.length()
            onProgress(size, size)
            return RemoteArtifactResult.Ok(existing)
        }
        val safeId = sanitizeId(artifactId)
            ?: return RemoteArtifactResult.Error("bad_request", "invalid artifact id")
        checkReady(deviceId)?.let { return it }
        val key = "$deviceId:$botId:$safeId:${sanitizeName(name)}"
        val (deferred, isLeader) = inflightLock.withLock {
            val existing = inflight[key]
            if (existing != null) {
                existing to false
            } else {
                val created = CompletableDeferred<RemoteArtifactResult>()
                inflight[key] = created
                created to true
            }
        }
        if (!isLeader) {
            return deferred.await()
        }
        return try {
            val result = downloadArtifact(
                deviceId = deviceId,
                botId = botId,
                workspaceRoot = workspaceRoot,
                artifactId = safeId,
                name = name,
                expectedSize = expectedSize,
                onProgress = onProgress,
            )
            deferred.complete(result)
            result
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            throw cancelled
        } catch (error: Exception) {
            val failure = RemoteArtifactResult.Error("internal", error.message ?: "artifact download failed")
            deferred.complete(failure)
            failure
        } finally {
            inflightLock.withLock {
                if (inflight[key] === deferred) {
                    inflight.remove(key)
                }
            }
        }
    }

    private suspend fun downloadArtifact(
        deviceId: String,
        botId: String,
        workspaceRoot: File,
        artifactId: String,
        name: String,
        expectedSize: Long?,
        onProgress: (Long, Long) -> Unit,
    ): RemoteArtifactResult {
        val target = targetFile(workspaceRoot, artifactId, name)
            ?: return RemoteArtifactResult.Error("bad_request", "invalid artifact path")
        val parent = target.parentFile
            ?: return RemoteArtifactResult.Error("bad_request", "invalid artifact directory")
        withContext(Dispatchers.IO) { parent.mkdirs() }
        val temp = File(parent, "${target.name}.part")
        return try {
            withContext(Dispatchers.IO) { temp.delete() }
            val failure = streamChunks(
                deviceId = deviceId,
                botId = botId,
                artifactId = artifactId,
                temp = temp,
                expectedSize = expectedSize,
                onProgress = onProgress,
            )
            if (failure != null) return failure
            withContext(Dispatchers.IO) {
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target.setLastModified(System.currentTimeMillis())
                evictOverflow()
            }
            RemoteArtifactResult.Ok(target)
        } catch (cancelled: CancellationException) {
            temp.delete()
            throw cancelled
        } catch (error: Exception) {
            temp.delete()
            RemoteArtifactResult.Error("internal", error.message ?: "artifact download failed")
        }
    }

    private suspend fun streamChunks(
        deviceId: String,
        botId: String,
        artifactId: String,
        temp: File,
        expectedSize: Long?,
        onProgress: (Long, Long) -> Unit,
    ): RemoteArtifactResult? {
        var offset = 0L
        var total = expectedSize ?: -1L
        val output = withContext(Dispatchers.IO) { FileOutputStream(temp) }
        try {
            while (true) {
                coroutineContext.ensureActive()
                val params = buildJsonObject {
                    put("botId", botId)
                    put("artifactId", artifactId)
                    put("offset", offset)
                    put("length", CHUNK_SIZE)
                }
                val outcome = transport.callWithOutcome(deviceId, "artifact.read_chunk", params)
                when (outcome) {
                    is RemoteCallOutcome.Failed -> {
                        output.close()
                        temp.delete()
                        return RemoteArtifactResult.Error(outcome.error.code, outcome.error.message)
                    }
                    is RemoteCallOutcome.Ok -> {
                        val json = outcome.result ?: run {
                            output.close()
                            temp.delete()
                            return RemoteArtifactResult.Error("bad_response", "missing chunk payload")
                        }
                        val encoded = (json["dataBase64"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                            ?: run {
                                output.close()
                                temp.delete()
                                return RemoteArtifactResult.Error("bad_response", "missing dataBase64")
                            }
                        val reportedSize = (json["size"] as? JsonPrimitive)?.longOrNull ?: total
                        if (reportedSize >= 0L) total = reportedSize
                        val eof = (json["eof"] as? JsonPrimitive)?.booleanOrNull ?: false
                        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: run {
                            output.close()
                            temp.delete()
                            return RemoteArtifactResult.Error("bad_response", "invalid chunk encoding")
                        }
                        withContext(Dispatchers.IO) { output.write(bytes) }
                        offset += bytes.size
                        onProgress(offset, maxOf(total, offset))
                        if (eof) break
                        if (bytes.isEmpty()) {
                            output.close()
                            temp.delete()
                            return RemoteArtifactResult.Error("bad_response", "unexpected empty chunk")
                        }
                    }
                }
            }
        } finally {
            runCatching { output.close() }
        }
        return null
    }

    private fun checkReady(deviceId: String): RemoteArtifactResult? {
        if (!transport.isRelayOnline.value) {
            return RemoteArtifactResult.Error(RemoteCallCodes.NOT_CONNECTED, "relay is not connected")
        }
        if (deviceId !in transport.onlineDevices.value) {
            return RemoteArtifactResult.Error(RemoteCallCodes.NOT_CONNECTED, "device is offline")
        }
        if (!transport.supportsPeerCapability(deviceId, CAP_PRESENT_ARTIFACTS)) {
            return RemoteArtifactResult.PeerUpgradeRequired
        }
        return null
    }

    private fun targetFile(workspaceRoot: File, artifactId: String, name: String): File? {
        val safeId = sanitizeId(artifactId) ?: return null
        val safeName = sanitizeName(name)
        return File(File(File(workspaceRoot, ARTIFACTS_DIR), safeId), safeName)
    }

    private fun sanitizeId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 128) return null
        if (!trimmed.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
            return null
        }
        return trimmed
    }

    private fun sanitizeName(raw: String): String {
        val leaf = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        val clean = leaf.filter { it != '\u0000' && it != '/' && it != '\\' }.trim('.')
        return clean.ifBlank { "artifact" }
    }

    private fun evictOverflow() {
        val root = cacheRootForEviction ?: return
        if (!root.isDirectory || maxCacheBytes <= 0L) return
        val artifactDirs = mutableListOf<Pair<File, Long>>()
        root.listFiles().orEmpty().forEach { deviceDir ->
            if (!deviceDir.isDirectory) return@forEach
            deviceDir.listFiles().orEmpty().forEach { botDir ->
                val artifacts = File(botDir, ARTIFACTS_DIR)
                if (artifacts.isDirectory) {
                    artifacts.listFiles().orEmpty().forEach { dir ->
                        if (dir.isDirectory) {
                            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            artifactDirs += dir to bytes
                        }
                    }
                }
            }
        }
        var totalBytes = artifactDirs.sumOf { it.second }
        if (totalBytes <= maxCacheBytes) return
        val sorted = artifactDirs.sortedBy { (dir, _) ->
            val newest = dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            newest
        }
        for ((dir, size) in sorted) {
            if (totalBytes <= maxCacheBytes) break
            if (dir.deleteRecursively()) {
                totalBytes -= size
            }
        }
    }

    companion object {
        const val CAP_PRESENT_ARTIFACTS = "present-artifacts"
        const val CHUNK_SIZE = 256 * 1024
        const val MAX_CACHE_BYTES = 100L * 1024L * 1024L
        private const val ARTIFACTS_DIR = "artifacts"
    }
}

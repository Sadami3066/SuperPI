package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteHistoryDto
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

object RemoteSessionBrowser {
    private val lineJson = Json { ignoreUnknownKeys = true }

    fun readPeerSessionId(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            var found: String? = null
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.contains(RemoteMirrorSession.CUSTOM_TYPE)) continue
                    val obj = runCatching { lineJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    if ((obj["customType"] as? JsonPrimitive)?.contentOrNull != RemoteMirrorSession.CUSTOM_TYPE) continue
                    val data = obj["data"] as? JsonObject ?: continue
                    val peer = (data["peerSessionId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    found = peer
                }
            }
            found
        }.getOrNull()
    }

    suspend fun ensureStubMirror(
        runtime: AgentRuntime,
        agentDir: File,
        workspace: RuntimeWorkspace,
        peerSessionId: String,
        title: String? = null,
        updatedAt: Long = System.currentTimeMillis(),
        pinned: Boolean = false,
        archived: Boolean = false,
    ): ConversationSummary {
        val remote = workspace.remote ?: error("workspace is not remote")
        val existing = findMirrorByPeerSession(runtime.sessions(workspace.id), peerSessionId)
        if (existing != null) return existing
        val mirror = RemoteMirrorSession.create(File(workspace.cwd), agentDir, persistEmpty = true)
        try {
            mirror.appendRemoteMapping(remote.deviceId, remote.botId, peerSessionId)
            val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanTitle != null) {
                mirror.appendName(cleanTitle)
            }
            val filePath = mirror.sessionFile ?: error("mirror session file is unavailable")
            val sessionId = mirror.sessionId
            mirror.close()
            if (pinned) {
                runCatching { runtime.setSessionPinned(sessionId, true) }
            }
            return ConversationSummary(
                id = sessionId,
                title = cleanTitle ?: "New chat",
                updatedAt = updatedAt,
                filePath = filePath,
                cwd = workspace.cwd,
                pinned = pinned,
                archived = archived,
            )
        } catch (error: Exception) {
            runCatching { mirror.close() }
            throw error
        }
    }

    data class MaterializeInPlaceResult(
        val summary: ConversationSummary,
        val updated: Boolean,
    )

    suspend fun pruneStaleMirrors(
        runtime: AgentRuntime,
        workspace: RuntimeWorkspace,
        authoritativePeerSessionIds: Set<String>,
    ): List<String> {
        if (workspace.remote == null) return emptyList()
        val activeLocal = runCatching { runtime.sessions(workspace.id) }.getOrDefault(emptyList())
        val archivedLocal = runCatching {
            runtime.archivedSessions().filter { it.cwd == workspace.cwd }
        }.getOrDefault(emptyList())
        val candidates = (activeLocal + archivedLocal).distinctBy { it.id }
        val busyIds = runtime.busySessionIds.value
        val deletedIds = mutableListOf<String>()
        for (summary in candidates) {
            if (summary.id in busyIds || RemoteSessionRegistry.isPassiveStreaming(summary.id)) continue
            val peerId = readPeerSessionId(File(summary.filePath))
                ?: RemoteSessionRegistry.peerSessionId(summary.id)
            if (peerId == null || peerId !in authoritativePeerSessionIds) {
                runCatching { runtime.deleteSession(summary.id) }
                deletedIds += summary.id
            }
        }
        return deletedIds
    }

    suspend fun materializeHistoryInPlace(
        runtime: AgentRuntime,
        agentDir: File,
        workspace: RuntimeWorkspace,
        history: RemoteHistoryDto,
        existingSummary: ConversationSummary? = null,
    ): ConversationSummary =
        materializeHistoryInPlaceDetailed(runtime, agentDir, workspace, history, existingSummary).summary

    suspend fun materializeHistoryInPlaceDetailed(
        runtime: AgentRuntime,
        agentDir: File,
        workspace: RuntimeWorkspace,
        history: RemoteHistoryDto,
        existingSummary: ConversationSummary? = null,
    ): MaterializeInPlaceResult {
        val remote = workspace.remote ?: error("workspace is not remote")
        val digest = computeDigest(history)
        val existing = existingSummary ?: findMirrorByPeerSession(runtime.sessions(workspace.id), history.sessionId)
        if (existing == null || existing.filePath.isBlank()) {
            val created = checkNotNull(
                materializeHistory(
                    runtime = runtime,
                    agentDir = agentDir,
                    workspace = workspace,
                    history = history,
                    skipIfUnchanged = false,
                ),
            )
            return MaterializeInPlaceResult(created, updated = true)
        }
        val existingFile = File(existing.filePath)
        if (existingFile.isFile) {
            val existingMirror = runCatching { RemoteMirrorSession.open(existingFile) }.getOrNull()
            if (existingMirror != null) {
                val upToDate = try {
                    existingMirror.historyDigest == digest
                } finally {
                    runCatching { existingMirror.close() }
                }
                if (upToDate) return MaterializeInPlaceResult(existing, updated = false)
            }
        }
        if (existing.id in runtime.busySessionIds.value || RemoteSessionRegistry.isPassiveStreaming(existing.id)) {
            return MaterializeInPlaceResult(existing, updated = false)
        }
        runCatching { runtime.closeSessionWithoutMemory(existing.id) }
        val mirror = RemoteMirrorSession.recreateInPlace(existingFile, existing.id, File(workspace.cwd))
        try {
            mirror.hydrateRemoteHistory(remote.deviceId, remote.botId, history, digest)
            val title = history.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: mirror.name()?.takeIf { it.isNotBlank() }
                ?: existing.title.ifBlank { "New chat" }
            mirror.close()
            return MaterializeInPlaceResult(
                summary = existing.copy(
                    title = title,
                    filePath = existingFile.absolutePath,
                    cwd = workspace.cwd,
                ),
                updated = true,
            )
        } catch (error: Exception) {
            runCatching { mirror.close() }
            throw error
        }
    }

    suspend fun materializeHistory(
        runtime: AgentRuntime,
        agentDir: File,
        workspace: RuntimeWorkspace,
        history: RemoteHistoryDto,
        skipIfUnchanged: Boolean = false,
    ): ConversationSummary? {
        val remote = workspace.remote ?: error("workspace is not remote")
        val digest = computeDigest(history)
        val existingSummaries = runtime.sessions(workspace.id)
        val existing = findMirrorByPeerSession(existingSummaries, history.sessionId)
        var preservePinned = false
        if (existing != null) {
            val existingMirror = runCatching { RemoteMirrorSession.open(File(existing.filePath)) }.getOrNull()
            if (existingMirror != null) {
                val upToDate = try {
                    existingMirror.historyDigest == digest
                } finally {
                    runCatching { existingMirror.close() }
                }
                if (upToDate) return if (skipIfUnchanged) null else existing
            }
            if (existing.id in runtime.busySessionIds.value || RemoteSessionRegistry.isPassiveStreaming(existing.id)) {
                return if (skipIfUnchanged) null else existing
            }
            preservePinned = existing.pinned
            runCatching { runtime.deleteSession(existing.id) }
        }
        val mirror = RemoteMirrorSession.create(File(workspace.cwd), agentDir, persistEmpty = true)
        try {
            mirror.hydrateRemoteHistory(remote.deviceId, remote.botId, history, digest)
            val filePath = mirror.sessionFile ?: error("mirror session file is unavailable")
            val sessionId = mirror.sessionId
            val title = history.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: mirror.name()?.takeIf { it.isNotBlank() }
                ?: "New chat"
            mirror.close()
            if (preservePinned) {
                runCatching { runtime.setSessionPinned(sessionId, true) }
            }
            return ConversationSummary(
                id = sessionId,
                title = title,
                updatedAt = System.currentTimeMillis(),
                filePath = filePath,
                cwd = workspace.cwd,
                pinned = preservePinned,
                archived = false,
            )
        } catch (error: Exception) {
            runCatching { mirror.close() }
            throw error
        }
    }

    suspend fun findMirrorByPeerSession(
        summaries: List<ConversationSummary>,
        peerSessionId: String,
    ): ConversationSummary? {
        val encodedId = JsonPrimitive(peerSessionId).toString()
        val needle = "\"peerSessionId\":$encodedId"
        for (summary in summaries) {
            val file = File(summary.filePath)
            if (!file.isFile) continue
            val candidate = runCatching {
                file.bufferedReader().useLines { lines ->
                    lines.any { it.contains(needle) }
                }
            }.getOrDefault(false)
            if (!candidate) continue
            val mirror = runCatching { RemoteMirrorSession.open(file) }.getOrNull() ?: continue
            val matched = try {
                mirror.peerSessionId == peerSessionId
            } finally {
                runCatching { mirror.close() }
            }
            if (matched) return summary
        }
        return null
    }

    private fun computeDigest(history: RemoteHistoryDto): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(history.sessionId.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update((history.title ?: "").toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(history.busy.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update((history.seq?.toString() ?: "").toByteArray(Charsets.UTF_8))
        digest.update(0)
        for (item in history.messages) {
            digest.update(item.toString().toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

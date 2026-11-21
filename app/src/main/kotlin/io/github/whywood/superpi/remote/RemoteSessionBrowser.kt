package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteHistoryDto
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

internal object RemoteSessionBrowser {
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
            if (existing.id in runtime.busySessionIds.value) {
                return if (skipIfUnchanged) null else existing
            }
            preservePinned = existing.pinned
            runCatching { runtime.deleteSession(existing.id) }
        }
        val mirror = RemoteMirrorSession.create(File(workspace.cwd), agentDir)
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
        for (item in history.messages) {
            digest.update(item.toString().toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

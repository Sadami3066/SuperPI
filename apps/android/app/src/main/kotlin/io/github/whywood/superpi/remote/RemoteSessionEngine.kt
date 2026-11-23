package io.github.whywood.superpi.remote

import io.github.whywood.superpi.runtime.ActiveRunSnapshot
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.SessionEngine
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

object RemoteMirrorRegistry {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun register(sessionId: String) {
        if (sessionId.isNotBlank()) ids.add(sessionId)
    }

    fun contains(sessionId: String): Boolean = ids.contains(sessionId)
}

object RemoteSessionRegistry {
    private val handles = ConcurrentHashMap<String, RemoteSessionHandle>()

    internal fun register(handle: RemoteSessionHandle) {
        handles[handle.sessionId] = handle
    }

    internal fun unregister(handle: RemoteSessionHandle) {
        handles.remove(handle.sessionId, handle)
    }

    fun isWatching(sessionId: String): Boolean = handles[sessionId]?.isWatching() == true

    fun isPassiveStreaming(sessionId: String): Boolean = handles[sessionId]?.isPassiveStreaming() == true

    fun activePassiveRun(sessionId: String): ActiveRunSnapshot? = handles[sessionId]?.activePassiveRun()

    suspend fun sendQueued(
        sessionId: String,
        text: String,
        images: List<ImagePart>,
        followUp: Boolean,
    ): Boolean = handles[sessionId]?.enqueuePassiveInput(text, images, followUp) == true

    fun abort(sessionId: String): Boolean {
        val handle = handles[sessionId] ?: return false
        if (!handle.isPassiveStreaming()) return false
        handle.abort()
        return true
    }

    fun updateUsage(sessionId: String, usage: SessionUsage?) {
        handles[sessionId]?.updateRemoteUsage(usage)
    }

    fun peerSessionId(sessionId: String): String? = handles[sessionId]?.currentPeerSessionId()
}

class RemoteSessionEngine(
    workspace: File,
    agentDir: File,
    private val target: RemoteSessionTarget,
    private val transport: RemoteAgentTransport,
    private val scope: CoroutineScope? = null,
    private val questionCenter: QuestionCenter? = null,
    private val onPassiveRuntimeEvent: (suspend (SessionRuntimeEvent) -> Unit)? = null,
) : SessionEngine {
    private val cwd: File = workspace.absoluteFile

    private val agentDirectory: File = agentDir.absoluteFile

    override suspend fun create(file: String?, config: ModelConfig): SessionHandle {
        val mirror = if (file.isNullOrBlank()) {
            RemoteMirrorSession.create(cwd, agentDirectory)
        } else {
            RemoteMirrorSession.open(File(file))
        }
        RemoteMirrorRegistry.register(mirror.sessionId)
        return RemoteSessionHandle(
            target = target,
            transport = transport,
            mirror = mirror,
            injectedScope = scope,
            questionCenter = questionCenter,
            onPassiveRuntimeEvent = onPassiveRuntimeEvent,
        )
    }

    override suspend fun loadMcpServers(): Map<String, McpServerConfig> = emptyMap()

    override fun titleFor(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return DEFAULT_TITLE
        return if (normalized.length <= TITLE_LENGTH) normalized else normalized.take(TITLE_LENGTH) + ELLIPSIS
    }

    private companion object {
        private const val TITLE_LENGTH = 28
        private const val DEFAULT_TITLE = "New chat"
        private const val ELLIPSIS = "…"
    }
}

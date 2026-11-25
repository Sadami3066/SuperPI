package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.coding.session.AgentSession
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry

data class BoundSession(
    val sessionId: String,
    val sessionFile: String?,
    val cwd: String,
)

class SessionBinding(
    val supportsChatDelivery: Boolean = true,
    private val live: (() -> BoundSession?)? = null,
) {
    @Volatile
    private var bound: BoundSession? = null

    @Volatile
    private var sessionRef: AgentSession? = null

    fun attach(session: AgentSession) {
        sessionRef = session
        bound = BoundSession(
            sessionId = session.sessionId,
            sessionFile = session.sessionFile?.toString(),
            cwd = session.cwd.toString(),
        )
    }

    fun bind(session: BoundSession) {
        bound = session
    }

    fun current(): BoundSession? = live?.invoke() ?: bound

    fun hasCardToolResult(callId: String): Boolean {
        val active = sessionRef ?: return true
        val inLive = active.messages.any { entry ->
            val toolResult = entry.asLlmMessage() as? ToolResultMessage
            toolResult != null && toolResult.toolCallId == callId && !toolResult.isError
        }
        if (inLive) return true
        return runCatching {
            active.sessionManager.getBranch().any { entry ->
                val msg = (entry as? SessionMessageEntry)?.message as? ToolResultMessage
                msg != null && msg.toolCallId == callId && !msg.isError
            }
        }.getOrDefault(false)
    }
}

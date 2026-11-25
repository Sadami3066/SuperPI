package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RelayFrames
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asAgentMessage
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.Content
import io.github.whywood.superpi.sdk.ai.Message
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ThinkingContent
import io.github.whywood.superpi.sdk.ai.ThinkingDeltaEvent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolCallStartEvent
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal data class RemoteUserMessage(
    val text: String,
    val images: List<ImagePart> = emptyList(),
)

internal sealed interface RemoteBotEvent {
    data class AgentStart(
        val sessionId: String? = null,
        val title: String? = null,
        val userMessage: RemoteUserMessage? = null,
        val originRouteId: String? = null,
    ) : RemoteBotEvent

    data class TextDelta(val delta: String) : RemoteBotEvent

    data class ReasoningDelta(val delta: String) : RemoteBotEvent

    data class ToolCall(
        val callId: String,
        val name: String,
        val args: JsonObject,
        val hasArgs: Boolean = args.isNotEmpty(),
    ) : RemoteBotEvent

    data class ToolResult(
        val callId: String,
        val result: String,
        val isError: Boolean,
        val details: JsonElement?,
    ) : RemoteBotEvent

    data object CompactionStart : RemoteBotEvent

    data object CompactionEnd : RemoteBotEvent

    data object RetryStart : RemoteBotEvent

    data object RetryEnd : RemoteBotEvent

    data class Failed(val message: String) : RemoteBotEvent

    data class Ended(val status: String?) : RemoteBotEvent

    data object Settled : RemoteBotEvent

    data class QueueUpdate(
        val steering: List<String>,
        val followUp: List<String>,
        val sessionId: String? = null,
        val userMessage: RemoteUserMessage? = null,
    ) : RemoteBotEvent

    data class UserMessage(
        val text: String,
        val images: List<ImagePart> = emptyList(),
        val sessionId: String? = null,
    ) : RemoteBotEvent

    data class ApprovalRequest(
        val approvalId: String,
        val kind: String?,
        val toolCallId: String?,
        val title: String?,
        val message: String,
        val options: List<String> = emptyList(),
        val questions: List<UserQuestion> = emptyList(),
        val resolved: Boolean = false,
    ) : RemoteBotEvent

    data class PluginCustomMessage(
        val customType: String,
        val payload: String,
        val text: String,
        val display: Boolean = true,
    ) : RemoteBotEvent
}

internal object RemoteEventMapping {
    fun decode(event: JsonObject): RemoteBotEvent? = when (event.string("type")) {
        "agent_start" -> decodeAgentStart(event)
        "user_message" -> decodeUserMessage(event)
        "message_update" -> decodeMessageUpdate(event)
        "tool_call" -> decodeToolCall(event)
        "tool_result" -> decodeToolResult(event)
        "compaction_start" -> RemoteBotEvent.CompactionStart
        "compaction_end" -> RemoteBotEvent.CompactionEnd
        "retry_start" -> RemoteBotEvent.RetryStart
        "retry_end" -> RemoteBotEvent.RetryEnd
        "error" -> RemoteBotEvent.Failed(event.string("message").orEmpty().ifBlank { "The remote bot failed." })
        "agent_end" -> RemoteBotEvent.Ended(event.string("status"))
        "agent_settled" -> RemoteBotEvent.Settled
        "queue_update" -> decodeQueueUpdate(event)
        "approval_request" -> decodeApprovalRequest(event)
        "plugin_custom_message" -> decodePluginCustomMessage(event)

        else -> null
    }

    private fun decodeAgentStart(event: JsonObject): RemoteBotEvent.AgentStart = RemoteBotEvent.AgentStart(
        sessionId = event.string("sessionId")?.takeIf { it.isNotBlank() },
        title = event.string("title")?.takeIf { it.isNotBlank() },
        userMessage = decodeNestedUserMessage(event["userMessage"] as? JsonObject),
        originRouteId = event.string("originRouteId")?.takeIf { it.isNotBlank() },
    )

    private fun decodeUserMessage(event: JsonObject): RemoteBotEvent.UserMessage = RemoteBotEvent.UserMessage(
        text = event.string("text").orEmpty(),
        images = decodeImages(event),
        sessionId = event.string("sessionId")?.takeIf { it.isNotBlank() },
    )

    private fun decodeQueueUpdate(event: JsonObject): RemoteBotEvent.QueueUpdate = RemoteBotEvent.QueueUpdate(
        steering = event.strings("steering"),
        followUp = event.strings("followUp"),
        sessionId = event.string("sessionId")?.takeIf { it.isNotBlank() },
        userMessage = decodeNestedUserMessage(event["userMessage"] as? JsonObject),
    )

    private fun decodeNestedUserMessage(payload: JsonObject?): RemoteUserMessage? {
        if (payload == null) return null
        val text = payload.string("text").orEmpty()
        val images = decodeImages(payload)
        if (text.isEmpty() && images.isEmpty()) return null
        return RemoteUserMessage(text = text, images = images)
    }

    private fun decodeImages(payload: JsonObject): List<ImagePart> =
        RelayFrames.parseImages(payload).map { ImagePart(data = it.data, mimeType = it.mimeType) }

    private fun decodeApprovalRequest(event: JsonObject): RemoteBotEvent.ApprovalRequest? {
        val approvalId = event.string("approvalId") ?: event.string("requestId") ?: return null
        return RemoteBotEvent.ApprovalRequest(
            approvalId = approvalId,
            kind = event.string("kind") ?: event.string("method"),
            toolCallId = event.string("toolCallId"),
            title = event.string("title") ?: event.string("summary"),
            message = event.string("message") ?: event.string("summary").orEmpty(),
            options = event.strings("options"),
            questions = RelayFrames.parseQuestions(event["questions"]),
            resolved = event.boolean("resolved") == true,
        )
    }

    private fun decodeMessageUpdate(event: JsonObject): RemoteBotEvent? {
        val update = event["assistantMessageEvent"] as? JsonObject ?: return null
        val delta = update.string("delta") ?: return null
        return when (update.string("type")) {
            "text_delta" -> RemoteBotEvent.TextDelta(delta)
            "reasoning_delta" -> RemoteBotEvent.ReasoningDelta(delta)
            else -> null
        }
    }

    private fun decodeToolCall(event: JsonObject): RemoteBotEvent.ToolCall? {
        val callId = event.string("callId") ?: return null
        val name = event.string("name") ?: return null
        val argsObj = event["args"] as? JsonObject
        return RemoteBotEvent.ToolCall(
            callId = callId,
            name = name,
            args = argsObj ?: JsonObject(emptyMap()),
            hasArgs = argsObj != null,
        )
    }

    private fun decodeToolResult(event: JsonObject): RemoteBotEvent.ToolResult? {
        val callId = event.string("callId") ?: return null
        return RemoteBotEvent.ToolResult(
            callId = callId,
            result = text(event["result"]),
            isError = (event["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            details = event["details"]?.takeUnless { it is kotlinx.serialization.json.JsonNull },
        )
    }

    private fun decodePluginCustomMessage(event: JsonObject): RemoteBotEvent.PluginCustomMessage? {
        val customType = event.string("customType")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val payload = (event["payload"] as? JsonPrimitive)?.contentOrNull
            ?: event["payload"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.toString()
            ?: "{}"
        val text = (event["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val display = (event["display"] as? JsonPrimitive)?.booleanOrNull ?: true
        return RemoteBotEvent.PluginCustomMessage(
            customType = customType,
            payload = payload,
            text = text,
            display = display,
        )
    }

    private fun text(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> element.toString()
    }
}

internal data class RemoteToolCallRecord(
    val callId: String,
    val name: String,
    val args: JsonObject,
)

internal data class RemoteToolResultRecord(
    val callId: String,
    val toolName: String,
    val result: String,
    val isError: Boolean,
    val details: JsonElement?,
    val timestamp: Long,
)

internal class RemoteTurnState(
    private val modelName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val completedSteps = mutableListOf<StepSnapshot>()
    private val textBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private val calls = LinkedHashMap<String, RemoteToolCallRecord>()
    private val results = mutableListOf<RemoteToolResultRecord>()
    private val started = mutableSetOf<String>()
    private val signaled = mutableSetOf<String>()
    private var failureValue: String? = null
    private var endStatusValue: String? = null

    val failure: String? get() = synchronized(lock) { failureValue }

    val endStatus: String? get() = synchronized(lock) { endStatusValue }

    val text: String get() = synchronized(lock) {
        buildString {
            completedSteps.forEach { step -> append(step.text) }
            append(textBuilder)
        }
    }

    val reasoning: String get() = synchronized(lock) {
        buildString {
            completedSteps.forEach { step -> append(step.reasoning) }
            append(reasoningBuilder)
        }
    }

    val toolResults: List<RemoteToolResultRecord> get() = synchronized(lock) {
        completedSteps.flatMap { it.results } + results
    }

    val hasContent: Boolean
        get() = synchronized(lock) {
            completedSteps.isNotEmpty() ||
                textBuilder.isNotEmpty() ||
                reasoningBuilder.isNotEmpty() ||
                calls.isNotEmpty() ||
                results.isNotEmpty()
        }

    fun runningToolNames(): List<String> = synchronized(lock) {
        val finished = results.mapTo(mutableSetOf()) { it.callId }
        calls.values.filterNot { it.callId in finished }.map { it.name }.filter { it.isNotEmpty() }
    }

    fun consume(event: RemoteBotEvent): List<AgentEvent> = synchronized(lock) { consumeLocked(event) }

    fun assistantMessage(stopReason: StopReason, errorMessage: String?): AssistantMessage = synchronized(lock) {
        AssistantMessage(
            content = contentLocked(),
            api = "",
            provider = REMOTE_PROVIDER,
            model = modelName,
            usage = Usage.empty(),
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = clock(),
        )
    }

    fun toolResultMessages(): List<ToolResultMessage> = synchronized(lock) {
        results.map(::toToolResultMessage)
    }

    fun turnMessages(stopReason: StopReason, errorMessage: String?): List<Message> = synchronized(lock) {
        val out = mutableListOf<Message>()
        completedSteps.forEach { step ->
            out += step.assistant
            out += step.results.map(::toToolResultMessage)
        }
        val currentContent = contentLocked()
        val currentResults = results.map(::toToolResultMessage)
        val hasCurrent = currentContent.isNotEmpty() || currentResults.isNotEmpty()
        if (hasCurrent || (out.isEmpty() && stopReason == StopReason.Aborted) || errorMessage != null) {
            out += AssistantMessage(
                content = currentContent,
                api = "",
                provider = REMOTE_PROVIDER,
                model = modelName,
                usage = Usage.empty(),
                stopReason = stopReason,
                errorMessage = errorMessage,
                timestamp = clock(),
            )
            out += currentResults
        }
        out
    }

    private fun consumeLocked(event: RemoteBotEvent): List<AgentEvent> = when (event) {
        is RemoteBotEvent.AgentStart -> listOf(AgentEvent.AgentStart)
        is RemoteBotEvent.UserMessage -> emptyList()
        is RemoteBotEvent.TextDelta -> {
            rollStepIfNeededLocked()
            textBuilder.append(event.delta)
            val partial = partialMessage()
            listOf(
                AgentEvent.MessageUpdate(
                    message = partial.asAgentMessage(),
                    assistantMessageEvent = TextDeltaEvent(
                        contentIndex = textIndexLocked(),
                        delta = event.delta,
                        partial = partial,
                    ),
                ),
            )
        }

        is RemoteBotEvent.ReasoningDelta -> {
            rollStepIfNeededLocked()
            reasoningBuilder.append(event.delta)
            val partial = partialMessage()
            listOf(
                AgentEvent.MessageUpdate(
                    message = partial.asAgentMessage(),
                    assistantMessageEvent = ThinkingDeltaEvent(
                        contentIndex = THINKING_INDEX,
                        delta = event.delta,
                        partial = partial,
                    ),
                ),
            )
        }

        is RemoteBotEvent.ToolCall -> onToolCallLocked(event)
        is RemoteBotEvent.ToolResult -> onToolResultLocked(event)
        is RemoteBotEvent.Failed -> {
            failureValue = event.message
            emptyList()
        }

        is RemoteBotEvent.Ended -> {
            endStatusValue = event.status
            emptyList()
        }

        RemoteBotEvent.Settled -> emptyList()
        RemoteBotEvent.CompactionStart -> emptyList()
        RemoteBotEvent.CompactionEnd -> emptyList()
        RemoteBotEvent.RetryStart -> emptyList()
        RemoteBotEvent.RetryEnd -> emptyList()
        is RemoteBotEvent.QueueUpdate -> emptyList()
        is RemoteBotEvent.ApprovalRequest -> emptyList()
        is RemoteBotEvent.PluginCustomMessage -> emptyList()
    }

    private fun rollStepIfNeededLocked() {
        if (results.isEmpty()) return
        val finished = results.mapTo(mutableSetOf()) { it.callId }
        if (calls.keys.any { it !in finished }) return
        completedSteps += StepSnapshot(
            text = textBuilder.toString(),
            reasoning = reasoningBuilder.toString(),
            assistant = AssistantMessage(
                content = contentLocked(),
                api = "",
                provider = REMOTE_PROVIDER,
                model = modelName,
                usage = Usage.empty(),
                stopReason = StopReason.ToolUse,
                timestamp = clock(),
            ),
            results = results.toList(),
        )
        textBuilder.clear()
        reasoningBuilder.clear()
        calls.clear()
        results.clear()
    }

    private fun toToolResultMessage(result: RemoteToolResultRecord): ToolResultMessage = ToolResultMessage(
        toolCallId = result.callId,
        toolName = result.toolName,
        content = listOf(TextContent(result.result)),
        details = result.details,
        isError = result.isError,
        timestamp = result.timestamp,
    )

    private data class StepSnapshot(
        val text: String,
        val reasoning: String,
        val assistant: AssistantMessage,
        val results: List<RemoteToolResultRecord>,
    )

    private fun onToolCallLocked(event: RemoteBotEvent.ToolCall): List<AgentEvent> {
        val existing = calls[event.callId]
        val record = RemoteToolCallRecord(
            callId = event.callId,
            name = event.name.ifBlank { existing?.name.orEmpty() },
            args = if (event.args.isNotEmpty()) event.args else existing?.args ?: JsonObject(emptyMap()),
        )
        calls[event.callId] = record
        if (event.hasArgs) {
            if (!started.add(event.callId)) return emptyList()
            return listOf(AgentEvent.ToolExecutionStart(event.callId, record.name, record.args))
        }
        if (event.callId in started || !signaled.add(event.callId)) return emptyList()
        val content = contentLocked()
        val index = content.indexOfFirst { it is ToolCall && it.id == event.callId }
        val message = partialMessage(content)
        return listOf(
            AgentEvent.MessageUpdate(
                message = message.asAgentMessage(),
                assistantMessageEvent = ToolCallStartEvent(contentIndex = index, partial = message),
            ),
        )
    }

    private fun onToolResultLocked(event: RemoteBotEvent.ToolResult): List<AgentEvent> {
        val record = calls[event.callId] ?: RemoteToolCallRecord(
            callId = event.callId,
            name = "",
            args = JsonObject(emptyMap()),
        ).also { calls[event.callId] = it }
        results += RemoteToolResultRecord(
            callId = event.callId,
            toolName = record.name,
            result = event.result,
            isError = event.isError,
            details = event.details,
            timestamp = clock(),
        )
        val events = mutableListOf<AgentEvent>()
        if (started.add(event.callId)) {
            events += AgentEvent.ToolExecutionStart(event.callId, record.name, record.args)
        }
        events += AgentEvent.ToolExecutionEnd(
            toolCallId = event.callId,
            toolName = record.name,
            result = AgentToolResult(
                content = listOf(TextContent(event.result)),
                details = event.details,
                isError = event.isError,
            ),
            isError = event.isError,
        )
        return events
    }

    private fun textIndexLocked(): Int = if (reasoningBuilder.isNotEmpty()) THINKING_INDEX + 1 else 0

    private fun partialMessage(content: MutableList<Content> = contentLocked()): AssistantMessage = AssistantMessage(
        content = content,
        api = "",
        provider = REMOTE_PROVIDER,
        model = modelName,
        usage = Usage.empty(),
        stopReason = StopReason.Stop,
        timestamp = clock(),
    )

    private fun contentLocked(): MutableList<Content> {
        val blocks = mutableListOf<Content>()
        if (reasoningBuilder.isNotEmpty()) blocks += ThinkingContent(reasoningBuilder.toString())
        if (textBuilder.isNotEmpty()) blocks += TextContent(textBuilder.toString())
        calls.values.forEach { call ->
            blocks += ToolCall(id = call.callId, name = call.name, arguments = call.args)
        }
        return blocks
    }

    companion object {
        const val REMOTE_PROVIDER = "remote"
        private const val THINKING_INDEX = 0
    }
}

private fun JsonObject.string(key: String): String? =
    ((this[key] as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotEmpty() }

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.strings(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
}

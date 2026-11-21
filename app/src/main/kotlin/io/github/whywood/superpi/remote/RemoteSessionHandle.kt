package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RelayFrames
import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.data.relay.RemoteEvent
import io.github.whywood.superpi.data.relay.RouteImagePart
import io.github.whywood.superpi.runtime.AssistantMessage as HistoryAssistantMessage
import io.github.whywood.superpi.runtime.CapabilitySnapshot
import io.github.whywood.superpi.runtime.HistoryMapper
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.SessionCompaction
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionMcpServerStatus
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.agent.StandardAgentMessage
import io.github.whywood.superpi.sdk.agent.asAgentMessage
import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.AssistantMessage as PiAssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.Content
import io.github.whywood.superpi.sdk.ai.ImageContent
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.ModelCost
import io.github.whywood.superpi.sdk.ai.ModelInputModality
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.Provider
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class RemoteSessionHandle internal constructor(
    private val target: RemoteSessionTarget,
    private val transport: RemoteAgentTransport,
    private val mirror: RemoteMirrorSession,
    injectedScope: CoroutineScope? = null,
    private val reconnectGraceMillis: Long = RECONNECT_GRACE_MILLIS,
) : SessionHandle {
    private val listeners = CopyOnWriteArrayList<suspend (SessionEvent) -> Unit>()
    private val ownsScope = injectedScope == null
    private val scope: CoroutineScope = injectedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val remoteModel: Model = buildRemoteModel(target)

    @Volatile
    private var activeTurn: RemoteTurn? = null

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var peerSessionId: String? = mirror.peerSessionId

    override val sessionId: String get() = mirror.sessionId

    override val sessionFile: String? get() = mirror.sessionFile

    override val isBusy: Boolean get() = activeTurn != null

    override val isStreaming: Boolean get() = activeTurn?.streaming == true

    override val messages: List<AgentMessage> get() = mirror.messages()

    override val activeToolNames: List<String>
        get() = activeTurn?.state?.runningToolNames().orEmpty()

    override val model: Model get() = remoteModel

    override val thinkingLevel: String get() = THINKING_LEVEL

    override suspend fun prompt(text: String, images: List<ImagePart>?, behavior: QueueBehavior?) {
        if (closed) return
        val safeImages = images.orEmpty().filter { it.data.isNotBlank() && it.mimeType.isNotBlank() }
        when (behavior) {
            QueueBehavior.Steer -> queueInput(BOT_STEER, text, safeImages)
            QueueBehavior.FollowUp -> queueInput(BOT_FOLLOW_UP, text, safeImages)
            null -> runPrompt(text, safeImages)
        }
    }

    override fun abort() {
        val turn = activeTurn
        turn?.aborted = true
        peerSessionId?.let { sessionId -> abortRemote(sessionId) }
        turn?.settled?.complete(Unit)
    }

    override suspend fun close() {
        closed = true
        activeTurn?.settled?.complete(Unit)
        activeTurn = null
        listeners.clear()
        mirror.close()
        if (ownsScope) scope.cancel()
    }

    override fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun clearQueues() {
    }

    override fun removeQueuedInput(text: String, behavior: QueueBehavior): Boolean = false

    override fun canRetryLastAssistant(): Boolean = false

    override suspend fun retryLastAssistant() {
    }

    override fun lastAssistantText(): String? {
        val last = mirror.messages().asReversed().firstOrNull { it.role == ROLE_ASSISTANT } ?: return null
        val message = last.asLlmMessage() as? PiAssistantMessage ?: return null
        return message.content.filterIsInstance<TextContent>().joinToString("") { it.text }.ifBlank { null }
    }

    override suspend fun setModel(model: Model, thinkingLevel: String?) {
    }

    override suspend fun setThinkingLevel(level: String) {
    }

    override fun registerProvider(provider: Provider) {
    }

    override suspend fun compact(): SessionCompaction? = null

    override fun history(): List<HistoryAssistantMessage> = HistoryMapper.mapEntries(mirror.entries())

    override fun usage(): SessionUsage? = null

    override fun sessionName(): String? = mirror.name()

    override suspend fun appendTitle(title: String) {
        mirror.appendName(title)
    }

    override suspend fun appendCustomMessage(
        customType: String,
        payloadJson: String,
        text: String,
        display: Boolean,
    ) {
        mirror.appendCustomMessage(customType, text, payloadJson, display)
    }

    override suspend fun appendCustomEntry(customType: String, dataJson: String) {
        mirror.appendCustomEntry(customType, dataJson)
    }

    override suspend fun capabilitySnapshot(): CapabilitySnapshot = CapabilitySnapshot()

    override fun capabilityBaseline(): CapabilitySnapshot? = CapabilitySnapshot()

    override suspend fun reloadMcp(servers: Map<String, McpServerConfig>) {
    }

    override suspend fun reconnectMcp(server: String) {
    }

    override fun mcpServers(): List<SessionMcpServerStatus> = emptyList()

    override fun deferredMcpToolCount(): Int = 0

    private suspend fun runPrompt(text: String, images: List<ImagePart>) {
        if (!target.isOnline()) {
            emit(SessionEvent.SessionError(offlineMessage()))
            return
        }
        val turn = RemoteTurn(target.botName)
        activeTurn = turn
        try {
            mirror.appendUserMessage(text, images)
            coroutineScope {
                val eventCollector: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.remoteEvents.collect { event -> onRemoteEvent(turn, event) }
                }
                val connectionWatch: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.onlineDevices.collect { devices -> onDevicesChanged(this, turn, devices) }
                }
                try {
                    deliverPrompt(turn, text, images)
                    if (turn.failure == null && turn.aborted) {
                        peerSessionId?.let { sessionId -> abortRemote(sessionId) }
                    } else if (turn.failure == null) {
                        val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MILLIS) { turn.settled.await() }
                        if (settled == null) turn.failure = SETTLE_TIMEOUT_MESSAGE
                    }
                    finishTurn(turn)
                } finally {
                    turn.cancelDisconnectGrace()
                    eventCollector.cancel()
                    connectionWatch.cancel()
                }
            }
        } finally {
            activeTurn = null
        }
    }

    private suspend fun deliverPrompt(turn: RemoteTurn, text: String, images: List<ImagePart>) {
        val sessionId = peerSessionId
        when (val outcome = callPrompt(turn, text, images, sessionId)) {
            is RemoteCallOutcome.Ok -> {
                rememberPeerSession(outcome.result)
                if (peerSessionId == null) turn.failure = NO_SESSION_MESSAGE
                return
            }

            is RemoteCallOutcome.Failed -> {
                if (sessionId != null && outcome.error.code == SESSION_NOT_FOUND) {
                    clearPeerSession()
                    when (val retryOutcome = callPrompt(turn, text, images, null)) {
                        is RemoteCallOutcome.Ok -> {
                            rememberPeerSession(retryOutcome.result)
                            if (peerSessionId == null) turn.failure = NO_SESSION_MESSAGE
                            return
                        }

                        is RemoteCallOutcome.Failed -> {
                            turn.failure = outcomeMessage(retryOutcome.error)
                            return
                        }
                    }
                }
                turn.failure = outcomeMessage(outcome.error)
            }
        }
    }

    private suspend fun callPrompt(
        turn: RemoteTurn,
        text: String,
        images: List<ImagePart>,
        sessionId: String?,
    ): RemoteCallOutcome = transport.callWithOutcome(
        to = target.deviceId,
        method = BOT_PROMPT,
        params = promptParams(text, images, sessionId),
        onRouteId = { route -> turn.setRoute(route) },
    )

    private fun onDevicesChanged(watchScope: CoroutineScope, turn: RemoteTurn, devices: Set<String>) {
        if (turn.settled.isCompleted || turn.aborted) return
        if (target.deviceId !in devices) {
            if (!turn.markDisconnected()) return
            val graceJob = watchScope.launch {
                delay(reconnectGraceMillis)
                if (turn.isDisconnected() && !turn.settled.isCompleted) {
                    turn.connectionLost = true
                    turn.settled.complete(Unit)
                }
            }
            turn.setDisconnectGrace(graceJob)
            return
        }
        if (!turn.markReconnected()) return
        val sessionId = peerSessionId ?: run {
            turn.connectionLost = true
            turn.settled.complete(Unit)
            return
        }
        watchScope.launch { resumeTurn(turn, sessionId) }
    }

    private suspend fun resumeTurn(turn: RemoteTurn, sessionId: String) {
        if (turn.settled.isCompleted || turn.aborted) return
        val outcome = transport.callWithOutcome(
            to = target.deviceId,
            method = BOT_RESUME,
            params = buildJsonObject {
                put("botId", target.botId)
                put("sessionId", sessionId)
                put("afterSeq", turn.lastAcceptedSeq())
            },
            onRouteId = { route -> turn.switchRoute(route) },
        )
        if (turn.settled.isCompleted || turn.aborted) return
        val result = when (outcome) {
            is RemoteCallOutcome.Ok -> outcome.result
            is RemoteCallOutcome.Failed -> {
                if (outcome.error.code == SESSION_NOT_FOUND) clearPeerSession()
                turn.connectionLost = true
                turn.settled.complete(Unit)
                return
            }
        }
        val busy = (result?.get("busy") as? JsonPrimitive)?.booleanOrNull
        if (busy == false && !turn.settled.isCompleted) {
            withTimeoutOrNull(RESUME_IDLE_DRAIN_MILLIS) { turn.settled.await() }
            if (!turn.settled.isCompleted) turn.settled.complete(Unit)
        }
    }

    private suspend fun queueInput(method: String, text: String, images: List<ImagePart>) {
        if (!target.isOnline()) {
            emit(SessionEvent.SessionError(offlineMessage()))
            return
        }
        val sessionId = peerSessionId
        if (sessionId == null) {
            emit(SessionEvent.SessionError(NO_SESSION_MESSAGE))
            return
        }
        val outcome = transport.callWithOutcome(
            to = target.deviceId,
            method = method,
            params = buildJsonObject {
                put("botId", target.botId)
                put("sessionId", sessionId)
                put("text", text)
                if (images.isNotEmpty()) {
                    put(
                        "images",
                        RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }),
                    )
                }
            },
        )
        if (outcome is RemoteCallOutcome.Failed) {
            if (outcome.error.code == SESSION_NOT_FOUND) {
                clearPeerSession()
                val turn = activeTurn
                if (turn != null) {
                    turn.failure = SESSION_RESTARTED_MESSAGE
                    turn.settled.complete(Unit)
                } else {
                    emit(SessionEvent.SessionError(SESSION_RESTARTED_MESSAGE))
                }
                return
            }
            emit(SessionEvent.SessionError(outcomeMessage(outcome.error)))
            return
        }
        val turn = activeTurn
        if (turn != null) {
            turn.queuedMessages.add(QueuedUserMessage(text, images))
            emitAgent(AgentEvent.MessageStart(userAgentMessage(text, images)))
        } else {
            mirror.appendUserMessage(text, images)
        }
    }

    private fun userAgentMessage(text: String, images: List<ImagePart>): AgentMessage {
        val content = if (images.isEmpty()) {
            PlainTextContent(text)
        } else {
            BlockContent(
                buildList<Content> {
                    if (text.isNotEmpty()) add(TextContent(text))
                    images.forEach { image -> add(ImageContent(image.data, image.mimeType)) }
                },
            )
        }
        return StandardAgentMessage(UserMessage(content = content, timestamp = System.currentTimeMillis()))
    }

    private suspend fun onRemoteEvent(turn: RemoteTurn, event: RemoteEvent) {
        val route = turn.routeId ?: return
        if (event.routeId != route) return
        if (!turn.accept(event.seq)) return
        when (val decoded = RemoteEventMapping.decode(event.event)) {
            null -> Unit
            is RemoteBotEvent.AgentStart -> emitAgent(AgentEvent.AgentStart)
            is RemoteBotEvent.Failed -> {
                turn.failure = decoded.message
                turn.settled.complete(Unit)
            }

            is RemoteBotEvent.Settled -> turn.settled.complete(Unit)
            is RemoteBotEvent.TextDelta, is RemoteBotEvent.ReasoningDelta -> {
                turn.streaming = true
                emitMapped(turn, decoded)
            }

            is RemoteBotEvent.ToolCall -> {
                turn.streaming = true
                emitMapped(turn, decoded)
            }

            else -> emitMapped(turn, decoded)
        }
    }

    private suspend fun emitMapped(turn: RemoteTurn, event: RemoteBotEvent) {
        for (agentEvent in turn.state.consume(event)) emitAgent(agentEvent)
    }

    private suspend fun emitAgent(event: AgentEvent) {
        emit(SessionEvent.AgentEventReceived(event))
    }

    private suspend fun finishTurn(turn: RemoteTurn) {
        if (turn.aborted) {
            val messages = persistTurn(turn, StopReason.Aborted, null)
            if (messages.isNotEmpty()) emitAgent(AgentEvent.AgentEnd(messages))
            return
        }
        val failure = if (turn.connectionLost) connectionLostMessage() else turn.failure
        val stopReason = when {
            failure != null -> StopReason.Error
            turn.state.endStatus == STATUS_STOPPED -> StopReason.Aborted
            turn.state.endStatus == STATUS_FAILED -> StopReason.Error
            else -> StopReason.Stop
        }
        val errorMessage = failure
            ?: if (stopReason == StopReason.Error) FAILED_MESSAGE else null
        val messages = persistTurn(turn, stopReason, errorMessage)
        if (messages.isNotEmpty()) emitAgent(AgentEvent.AgentEnd(messages))
        if (failure != null) emit(SessionEvent.SessionError(failure))
    }

    private suspend fun persistTurn(
        turn: RemoteTurn,
        stopReason: StopReason,
        errorMessage: String?,
    ): List<AgentMessage> {
        val state = turn.state
        for (queued in turn.queuedMessages) mirror.appendUserMessage(queued.text, queued.images)
        val messages = state.turnMessages(stopReason, errorMessage)
        if (messages.isEmpty()) return emptyList()
        val persisted = mutableListOf<AgentMessage>()
        for (message in messages) {
            when (message) {
                is PiAssistantMessage -> {
                    mirror.appendAssistantMessage(message)
                    persisted += message.asAgentMessage()
                }

                is ToolResultMessage -> {
                    mirror.appendToolResult(message)
                    persisted += message.asAgentMessage()
                }

                else -> Unit
            }
        }
        return persisted
    }

    private suspend fun rememberPeerSession(result: JsonObject?) {
        val returned = (result?.get("sessionId") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return
        if (returned == peerSessionId) return
        peerSessionId = returned
        mirror.appendRemoteMapping(target.deviceId, target.botId, returned)
    }

    private suspend fun clearPeerSession() {
        if (peerSessionId == null) return
        peerSessionId = null
        mirror.clearRemoteMapping(target.deviceId, target.botId)
    }

    private fun abortRemote(sessionId: String) {
        if (closed) return
        scope.launch {
            val outcome = runCatching {
                transport.callWithOutcome(target.deviceId, BOT_ABORT, buildJsonObject {
                    put("botId", target.botId)
                    put("sessionId", sessionId)
                })
            }.getOrNull()
            if (outcome is RemoteCallOutcome.Failed && outcome.error.code == SESSION_NOT_FOUND) {
                clearPeerSession()
            }
        }
    }

    private suspend fun emit(event: SessionEvent) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    private fun promptParams(text: String, images: List<ImagePart>, sessionId: String?): JsonObject = buildJsonObject {
        put("botId", target.botId)
        if (sessionId != null) put("sessionId", sessionId)
        put("text", text)
        if (images.isNotEmpty()) {
            put(
                "images",
                RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }),
            )
        }
    }

    private fun outcomeMessage(error: RemoteCallError): String =
        error.message.takeIf { it.isNotBlank() } ?: NO_ANSWER_MESSAGE

    private fun offlineMessage(): String =
        "Remote bot ${target.botName} on ${target.deviceName} is offline."

    private fun connectionLostMessage(): String =
        "Lost connection to ${target.deviceName}; the turn may have been interrupted. Retry when the device is back online."

    private data class QueuedUserMessage(
        val text: String,
        val images: List<ImagePart>,
    )

    private class RemoteTurn(modelName: String) {
        val state = RemoteTurnState(modelName)
        val settled = CompletableDeferred<Unit>()
        val queuedMessages = CopyOnWriteArrayList<QueuedUserMessage>()

        @Volatile
        var routeId: String? = null

        @Volatile
        var aborted: Boolean = false

        @Volatile
        var failure: String? = null

        @Volatile
        var streaming: Boolean = false

        @Volatile
        var connectionLost: Boolean = false

        private var acceptedSeq: Long = -1L
        private var disconnected: Boolean = false
        private var disconnectGraceJob: Job? = null

        @Synchronized
        fun setRoute(route: String) {
            if (routeId == route) return
            routeId = route
            acceptedSeq = -1L
        }

        @Synchronized
        fun switchRoute(route: String) {
            routeId = route
        }

        @Synchronized
        fun lastAcceptedSeq(): Long = acceptedSeq

        @Synchronized
        fun accept(seq: Long): Boolean {
            if (seq <= acceptedSeq) return false
            acceptedSeq = seq
            return true
        }

        @Synchronized
        fun markDisconnected(): Boolean {
            if (disconnected) return false
            disconnected = true
            return true
        }

        @Synchronized
        fun isDisconnected(): Boolean = disconnected

        @Synchronized
        fun markReconnected(): Boolean {
            if (!disconnected) return false
            disconnected = false
            disconnectGraceJob?.cancel()
            disconnectGraceJob = null
            return true
        }

        @Synchronized
        fun setDisconnectGrace(job: Job) {
            disconnectGraceJob?.cancel()
            disconnectGraceJob = job
        }

        @Synchronized
        fun cancelDisconnectGrace() {
            disconnectGraceJob?.cancel()
            disconnectGraceJob = null
        }
    }

    private companion object {
        private const val BOT_PROMPT = "bot.prompt"
        private const val BOT_STEER = "bot.steer"
        private const val BOT_FOLLOW_UP = "bot.follow_up"
        private const val BOT_ABORT = "bot.abort"
        private const val BOT_RESUME = "bot.resume"
        private const val STATUS_STOPPED = "stopped"
        private const val STATUS_FAILED = "failed"
        private const val SESSION_NOT_FOUND = "session_not_found"
        private const val ROLE_ASSISTANT = "assistant"
        private const val THINKING_LEVEL = "off"
        private const val SETTLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val RECONNECT_GRACE_MILLIS = 25_000L
        private const val RESUME_IDLE_DRAIN_MILLIS = 500L
        private const val NO_ANSWER_MESSAGE = "The remote device did not answer."
        private const val NO_SESSION_MESSAGE = "The remote bot did not start a session."
        private const val SETTLE_TIMEOUT_MESSAGE = "The remote bot did not finish the turn."
        private const val FAILED_MESSAGE = "The bot on the remote device failed."
        private const val SESSION_RESTARTED_MESSAGE =
            "The remote session was restarted on the device, so that message could not be delivered. Send again to start a new remote session."
    }
}

private fun buildRemoteModel(target: RemoteSessionTarget): Model = Model(
    id = "remote",
    name = target.botName,
    api = "",
    provider = RemoteTurnState.REMOTE_PROVIDER,
    baseUrl = "",
    input = listOf(ModelInputModality.Text, ModelInputModality.Image),
    cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    reasoning = false,
    contextWindow = 0L,
    maxTokens = 0L,
)

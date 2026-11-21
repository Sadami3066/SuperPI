package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RelayFrames
import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.data.relay.RemoteEvent
import io.github.whywood.superpi.data.relay.RouteImagePart
import io.github.whywood.superpi.runtime.ActiveRunSnapshot
import io.github.whywood.superpi.runtime.AssistantMessage as HistoryAssistantMessage
import io.github.whywood.superpi.runtime.BackgroundTurnStarted
import io.github.whywood.superpi.runtime.CapabilitySnapshot
import io.github.whywood.superpi.runtime.HistoryMapper
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.QuestionOutcome
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionCompaction
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionMcpServerStatus
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ToolResultDetails
import io.github.whywood.superpi.runtime.ToolResultPayload
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.text
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.agent.AgentToolResult
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
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ThinkingDeltaEvent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolCallStartEvent
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RemoteSessionHandle internal constructor(
    private val target: RemoteSessionTarget,
    private val transport: RemoteAgentTransport,
    private val mirror: RemoteMirrorSession,
    injectedScope: CoroutineScope? = null,
    private val reconnectGraceMillis: Long = RECONNECT_GRACE_MILLIS,
    private val questionCenter: QuestionCenter? = null,
    private val onPassiveRuntimeEvent: (suspend (SessionRuntimeEvent) -> Unit)? = null,
) : SessionHandle {
    private val listeners = CopyOnWriteArrayList<suspend (SessionEvent) -> Unit>()
    private val ownsScope = injectedScope == null
    private val scope: CoroutineScope = injectedScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val remoteModel: Model = buildRemoteModel(target)
    private val passiveWake = Channel<Unit>(Channel.CONFLATED)
    private val recentOwnedRoutes = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeTurn: RemoteTurn? = null

    @Volatile
    private var passiveTurn: RemoteTurn? = null

    @Volatile
    private var passiveRunId: String? = null

    @Volatile
    private var passiveRunStartedAt: Long = 0L

    @Volatile
    private var passiveWatchRouteId: String? = null

    @Volatile
    private var passiveLastSeq: Long = mirror.historySeq ?: -1L

    @Volatile
    private var passiveWatchJob: Job? = null

    @Volatile
    private var passiveEpochJob: Job? = null

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var peerSessionId: String? = mirror.peerSessionId

    @Volatile
    private var remoteUsage: SessionUsage? = null

    override val sessionId: String get() = mirror.sessionId

    init {
        RemoteMirrorRegistry.register(mirror.sessionId)
        RemoteSessionRegistry.register(this)
        if (onPassiveRuntimeEvent != null && peerSessionId != null) {
            startPassiveWatchIfNeeded()
        }
    }

    override val sessionFile: String? get() = mirror.sessionFile

    override val isBusy: Boolean get() = activeTurn != null

    override val isStreaming: Boolean get() = activeTurn?.streaming == true

    override val messages: List<AgentMessage> get() = mirror.messages()

    override val activeToolNames: List<String>
        get() = activeTurn?.state?.runningToolNames().orEmpty()

    override val model: Model get() = remoteModel

    override val thinkingLevel: String get() = THINKING_LEVEL

    internal fun currentPeerSessionId(): String? = peerSessionId

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
        if (turn != null) {
            turn.aborted = true
            turn.cancelQuestions()
            peerSessionId?.let { sessionId -> abortRemote(sessionId) }
            turn.settled.complete(Unit)
            return
        }
        val passive = passiveTurn
        if (passive != null) {
            passive.aborted = true
            passive.cancelQuestions()
            peerSessionId?.let { sessionId -> abortRemote(sessionId) }
            scope.launch { finishPassiveTurn(passive) }
        }
    }

    override suspend fun close() {
        closed = true
        RemoteSessionRegistry.unregister(this)
        passiveWatchRouteId = null
        passiveEpochJob?.cancel()
        passiveEpochJob = null
        passiveWatchJob?.cancel()
        passiveWatchJob = null
        passiveTurn?.cancelQuestions()
        passiveTurn = null
        passiveRunId = null
        passiveRunStartedAt = 0L
        passiveWake.close()
        activeTurn?.cancelQuestions()
        activeTurn?.settled?.complete(Unit)
        activeTurn = null
        listeners.clear()
        mirror.close()
        if (ownsScope) scope.cancel()
    }

    internal fun isWatching(): Boolean = !closed && passiveWatchJob?.isActive == true

    internal fun isPassiveStreaming(): Boolean = !closed && passiveTurn != null && activeTurn == null

    internal fun activePassiveRun(): ActiveRunSnapshot? {
        if (!isPassiveStreaming()) return null
        val runId = passiveRunId ?: return null
        return ActiveRunSnapshot(runId, passiveRunStartedAt)
    }

    internal suspend fun enqueuePassiveInput(text: String, images: List<ImagePart>, followUp: Boolean): Boolean {
        if (closed || activeTurn != null || passiveTurn == null) return false
        val safeImages = images.filter { it.data.isNotBlank() && it.mimeType.isNotBlank() }
        queueInput(if (followUp) BOT_FOLLOW_UP else BOT_STEER, text, safeImages)
        return true
    }

    override fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    override fun clearQueues() {
    }

    override fun removeQueuedInput(text: String, behavior: QueueBehavior): Boolean = false

    override fun canRetryLastAssistant(): Boolean {
        if (closed || activeTurn != null || passiveTurn != null || peerSessionId == null) return false
        if (!transport.supportsPeerCapability(target.deviceId, RemoteBotControlClient.CAP_SESSION_CONTROLS)) {
            return false
        }
        val currentMessages = mirror.messages()
        val hasUser = currentMessages.any { it.role == ROLE_USER }
        val last = currentMessages.lastOrNull() ?: return false
        return hasUser && (last.role == ROLE_ASSISTANT || last.role == ROLE_USER)
    }

    override suspend fun retryLastAssistant() {
        if (!canRetryLastAssistant()) return
        val sessionId = peerSessionId ?: return
        if (!target.isOnline()) {
            emit(SessionEvent.SessionError(offlineMessage()))
            return
        }
        val turn = RemoteTurn(target.botName)
        executeTurn(turn) {
            val outcome = transport.callWithOutcome(
                to = target.deviceId,
                method = SESSION_RETRY,
                params = buildJsonObject {
                    put("botId", target.botId)
                    put("sessionId", sessionId)
                },
                onRouteId = { route ->
                    recentOwnedRoutes += route
                    turn.setRoute(route)
                },
            )
            when (outcome) {
                is RemoteCallOutcome.Ok -> rememberPeerSession(outcome.result)
                is RemoteCallOutcome.Failed -> {
                    if (outcome.error.code == SESSION_NOT_FOUND) clearPeerSession()
                    turn.failure = outcomeMessage(outcome.error)
                }
            }
        }
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

    override suspend fun compact(): SessionCompaction? {
        val sessionId = peerSessionId ?: return null
        if (closed || !target.isOnline()) return null
        if (!transport.supportsPeerCapability(target.deviceId, RemoteBotControlClient.CAP_SESSION_CONTROLS)) {
            return null
        }
        val outcome = transport.callWithOutcome(
            to = target.deviceId,
            method = SESSION_COMPACT,
            params = buildJsonObject {
                put("botId", target.botId)
                put("sessionId", sessionId)
            },
            timeoutMillis = COMPACT_TIMEOUT_MILLIS,
        )
        if (outcome !is RemoteCallOutcome.Ok) return null
        val result = outcome.result ?: return null
        RemoteBotControlClient.parseUsage(result["usage"] as? JsonObject)?.let { remoteUsage = it }
        val compacted = (result["compacted"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!compacted) return null
        val tokensBefore = (result["tokensBefore"] as? JsonPrimitive)?.longOrNull ?: 0L
        val tokensAfter = (result["tokensAfter"] as? JsonPrimitive)?.longOrNull
        return SessionCompaction(tokensBefore = tokensBefore, estimatedTokensAfter = tokensAfter)
    }

    override fun history(): List<HistoryAssistantMessage> = HistoryMapper.mapEntries(mirror.entries())

    override fun usage(): SessionUsage? = remoteUsage

    internal fun updateRemoteUsage(usage: SessionUsage?) {
        if (usage != null) remoteUsage = usage
    }

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
        mirror.appendUserMessage(text, images)
        executeTurn(turn) { deliverPrompt(turn, text, images) }
    }

    private suspend fun executeTurn(
        turn: RemoteTurn,
        driver: suspend () -> Unit,
    ) {
        activeTurn = turn
        try {
            preemptPassiveWatch()
            coroutineScope {
                val turnJob = SupervisorJob(coroutineContext[Job])
                val turnScope = CoroutineScope(coroutineContext + turnJob)
                val eventCollector: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.remoteEvents.collect { event -> onRemoteEvent(turnScope, turn, event) }
                }
                val connectionWatch: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.onlineDevices.collect { devices -> onDevicesChanged(this, turn, devices) }
                }
                try {
                    driver()
                    if (turn.failure == null && turn.aborted) {
                        peerSessionId?.let { sessionId -> abortRemote(sessionId) }
                    } else if (turn.failure == null && !turn.settled.isCompleted) {
                        val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MILLIS) { turn.settled.await() }
                        if (settled == null) turn.failure = SETTLE_TIMEOUT_MESSAGE
                    }
                    finishTurn(turn)
                } finally {
                    turn.cancelDisconnectGrace()
                    eventCollector.cancelAndJoin()
                    connectionWatch.cancel()
                    turn.cancelQuestions()
                    turnJob.cancel()
                }
            }
        } finally {
            activeTurn = null
            val settledSeq = turn.lastAcceptedSeq()
            if (settledSeq > passiveLastSeq) passiveLastSeq = settledSeq
            if (!closed && onPassiveRuntimeEvent != null && peerSessionId != null) {
                startPassiveWatchIfNeeded()
                passiveWake.trySend(Unit)
            }
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
        onRouteId = { route ->
            recentOwnedRoutes += route
            turn.setRoute(route)
        },
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
                if (outcome.error.code == SESSION_NOT_FOUND) {
                    clearPeerSession()
                }
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
                val passive = passiveTurn
                if (turn != null) {
                    turn.failure = SESSION_RESTARTED_MESSAGE
                    turn.settled.complete(Unit)
                } else if (passive != null) {
                    passive.failure = SESSION_RESTARTED_MESSAGE
                    finishPassiveTurn(passive)
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
            return
        }
        val passive = passiveTurn
        if (passive != null) {
            passive.queuedMessages.add(QueuedUserMessage(text, images))
            val runId = passiveRunId
            if (runId != null && text.isNotBlank()) {
                emitPassiveRuntime(RuntimeEvent.UserInputAdmitted(runId = runId, text = text))
            } else {
                mirror.appendUserMessage(text, images)
            }
            return
        }
        mirror.appendUserMessage(text, images)
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

    private suspend fun onRemoteEvent(turnScope: CoroutineScope, turn: RemoteTurn, event: RemoteEvent) {
        val route = turn.routeId ?: return
        if (event.routeId != route) return
        if (!turn.accept(event.seq)) return
        when (val decoded = RemoteEventMapping.decode(event.event)) {
            null -> Unit
            is RemoteBotEvent.AgentStart -> emitAgent(AgentEvent.AgentStart)
            is RemoteBotEvent.UserMessage -> {
                if (decoded.text.isNotBlank() || decoded.images.isNotEmpty()) {
                    turn.queuedMessages.add(QueuedUserMessage(decoded.text, decoded.images))
                    emitAgent(AgentEvent.MessageStart(userAgentMessage(decoded.text, decoded.images)))
                }
            }

            is RemoteBotEvent.QueueUpdate -> {
                emitMapped(turn, decoded)
                val injected = decoded.userMessage
                if (injected != null && (injected.text.isNotBlank() || injected.images.isNotEmpty())) {
                    turn.queuedMessages.add(QueuedUserMessage(injected.text, injected.images))
                    emitAgent(AgentEvent.MessageStart(userAgentMessage(injected.text, injected.images)))
                }
            }

            is RemoteBotEvent.ApprovalRequest -> handleApprovalRequest(turnScope, turn, decoded)
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

    private fun handleApprovalRequest(
        turnScope: CoroutineScope,
        turn: RemoteTurn,
        request: RemoteBotEvent.ApprovalRequest,
    ) {
        if (request.resolved) {
            turn.cancelQuestion(request.approvalId)
            return
        }
        if (turn.aborted || turn.settled.isCompleted || closed) return
        val center = questionCenter ?: return
        if (turn.hasQuestion(request.approvalId)) return
        val questions = request.questions.ifEmpty {
            val options = when {
                request.kind == "confirm" && request.options.isEmpty() -> listOf(
                    QuestionOption("Allow"),
                    QuestionOption("Decline"),
                )
                else -> request.options.map { QuestionOption(it) }
            }
            listOf(
                UserQuestion(
                    id = "q1",
                    header = request.title,
                    question = request.message.ifBlank { request.title ?: "Confirm" },
                    options = options,
                    multiSelect = false,
                ),
            )
        }
        lateinit var job: Job
        job = turnScope.launch(start = CoroutineStart.LAZY) {
            try {
                val outcome = center.ask(
                    sessionId = mirror.sessionId,
                    toolCallId = request.toolCallId ?: request.approvalId,
                    questions = questions,
                )
                if (turn.aborted || turn.settled.isCompleted || closed) return@launch
                sendApprovalResponse(request, outcome)
            } finally {
                turn.clearQuestion(request.approvalId, job)
            }
        }
        turn.putQuestion(request.approvalId, job)
        job.start()
    }

    private suspend fun sendApprovalResponse(
        request: RemoteBotEvent.ApprovalRequest,
        outcome: QuestionOutcome,
    ) {
        val sessionId = peerSessionId ?: return
        val params = buildJsonObject {
            put("botId", target.botId)
            put("sessionId", sessionId)
            put("approvalId", request.approvalId)
            when (outcome) {
                is QuestionOutcome.Cancelled -> put("cancelled", true)
                is QuestionOutcome.Answered -> {
                    val allSkipped = outcome.answers.isNotEmpty() && outcome.answers.all { it.skipped }
                    if (allSkipped) {
                        put("cancelled", true)
                    } else {
                        put("answers", RelayFrames.questionAnswersArray(outcome.answers))
                        if (request.kind != "question") {
                            val first = outcome.answers.firstOrNull()
                            if (first?.skipped == true) {
                                put("cancelled", true)
                            } else if (request.kind == "confirm") {
                                val choice = first?.selected?.firstOrNull().orEmpty().trim().lowercase()
                                val firstOptionLabel = request.options.firstOrNull()?.trim()?.lowercase()
                                val approved = choice == "allow" ||
                                    choice == "yes" ||
                                    choice == "true" ||
                                    choice == "ok" ||
                                    choice == "confirm" ||
                                    choice == "允许" ||
                                    choice == "确认" ||
                                    (firstOptionLabel != null && choice == firstOptionLabel)
                                put("confirmed", approved)
                            } else {
                                val value = first?.custom?.takeIf { it.isNotBlank() }
                                    ?: first?.selected?.firstOrNull().orEmpty()
                                put("value", value)
                            }
                        }
                    }
                }
            }
        }
        val responseOutcome = transport.callWithOutcome(
            to = target.deviceId,
            method = BOT_APPROVAL,
            params = params,
        )
        if (responseOutcome is RemoteCallOutcome.Failed && responseOutcome.error.code != "not_found") {
            emit(SessionEvent.SessionError(outcomeMessage(responseOutcome.error)))
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
        if (!closed && onPassiveRuntimeEvent != null && activeTurn == null) {
            startPassiveWatchIfNeeded()
            passiveWake.trySend(Unit)
        }
    }

    private suspend fun clearPeerSession() {
        passiveLastSeq = -1L
        if (peerSessionId == null) return
        peerSessionId = null
        mirror.clearRemoteMapping(target.deviceId, target.botId)
    }

    private fun startPassiveWatchIfNeeded() {
        if (closed || onPassiveRuntimeEvent == null || peerSessionId == null) return
        if (passiveWatchJob?.isActive == true) return
        passiveWatchJob = scope.launch { runPassiveSubscriptionLoop() }
    }

    private suspend fun preemptPassiveWatch() {
        passiveWatchRouteId = null
        val job = passiveEpochJob
        passiveEpochJob = null
        if (job != null) {
            job.cancelAndJoin()
        }
        val lingering = passiveTurn
        if (lingering != null) {
            passiveTurn = null
            passiveRunId = null
            passiveRunStartedAt = 0L
            lingering.cancelQuestions()
        }
    }

    private suspend fun runPassiveSubscriptionLoop() {
        while (!closed && currentCoroutineContext().isActive) {
            if (activeTurn != null || peerSessionId == null) {
                if (passiveWake.receiveCatching().isClosed) return
                continue
            }
            if (target.deviceId !in transport.onlineDevices.value) {
                transport.onlineDevices.first { target.deviceId in it || closed }
                continue
            }
            val currentSessionId = peerSessionId ?: continue
            when (watchPassiveSession(currentSessionId)) {
                WatchEpochResult.Preempted -> continue
                WatchEpochResult.ReconnectImmediately -> continue
                WatchEpochResult.ReconnectWhenOnline -> continue
                WatchEpochResult.SessionGone -> {
                    if (passiveWake.receiveCatching().isClosed) return
                }

                WatchEpochResult.RetryDelay -> {
                    delay(PASSIVE_RETRY_DELAY_MILLIS)
                }
            }
        }
    }

    private suspend fun watchPassiveSession(sessionId: String): WatchEpochResult {
        var result = WatchEpochResult.Preempted
        coroutineScope {
            val epoch = launch {
                val epochScope = this
                val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.remoteEvents.collect { event -> onPassiveRemoteEvent(epochScope, event) }
                }
                try {
                    val after = passiveLastSeq
                    val params = buildJsonObject {
                        put("botId", target.botId)
                        put("sessionId", sessionId)
                        if (after >= 0L) {
                            put("afterSeq", after)
                        } else {
                            put("tail", true)
                        }
                    }
                    val outcome = transport.callWithOutcome(
                        to = target.deviceId,
                        method = BOT_RESUME,
                        params = params,
                        onRouteId = { route -> passiveWatchRouteId = route },
                    )
                    if (closed || activeTurn != null) {
                        result = WatchEpochResult.Preempted
                        return@launch
                    }
                    result = when (outcome) {
                        is RemoteCallOutcome.Failed -> {
                            if (outcome.error.code == SESSION_NOT_FOUND) {
                                WatchEpochResult.SessionGone
                            } else {
                                WatchEpochResult.RetryDelay
                            }
                        }

                        is RemoteCallOutcome.Ok -> {
                            transport.onlineDevices.first { target.deviceId !in it || closed || activeTurn != null }
                            if (closed || activeTurn != null) {
                                WatchEpochResult.Preempted
                            } else {
                                val turn = passiveTurn
                                if (turn != null) {
                                    val reconnected = withTimeoutOrNull(reconnectGraceMillis) {
                                        transport.onlineDevices.first { target.deviceId in it || closed || activeTurn != null }
                                    }
                                    if (closed || activeTurn != null) {
                                        WatchEpochResult.Preempted
                                    } else if (reconnected != null) {
                                        WatchEpochResult.ReconnectImmediately
                                    } else {
                                        turn.connectionLost = true
                                        finishPassiveTurn(turn)
                                        WatchEpochResult.ReconnectWhenOnline
                                    }
                                } else {
                                    WatchEpochResult.ReconnectWhenOnline
                                }
                            }
                        }
                    }
                } finally {
                    passiveWatchRouteId = null
                    eventCollector.cancelAndJoin()
                }
            }
            passiveEpochJob = epoch
            try {
                epoch.join()
            } finally {
                if (passiveEpochJob === epoch) {
                    passiveEpochJob = null
                }
            }
        }
        return result
    }

    private suspend fun onPassiveRemoteEvent(watchScope: CoroutineScope, event: RemoteEvent) {
        if (closed || activeTurn != null) return
        val route = passiveWatchRouteId ?: return
        if (event.routeId != route) return
        if (event.seq <= passiveLastSeq) return
        passiveLastSeq = event.seq
        val decoded = RemoteEventMapping.decode(event.event) ?: return
        when (decoded) {
            is RemoteBotEvent.AgentStart -> {
                passiveTurn?.let { existing -> finishPassiveTurn(existing) }
                val turn = RemoteTurn(target.botName).also {
                    it.routeId = route
                    it.acceptSeqUpTo(event.seq)
                }
                passiveTurn = turn
                val title = decoded.title?.trim()?.takeIf { it.isNotEmpty() }
                if (title != null && title != mirror.name()) {
                    mirror.appendName(title)
                }
                val ownOrigin = decoded.originRouteId != null && recentOwnedRoutes.contains(decoded.originRouteId)
                val userMessage = decoded.userMessage
                if (!ownOrigin && userMessage != null) {
                    val alreadyTrailing = mirror.historySeq == null &&
                        userMessage.images.isEmpty() &&
                        isTrailingMirrorUserText(userMessage.text)
                    if (!alreadyTrailing) {
                        mirror.appendUserMessage(userMessage.text, userMessage.images)
                    }
                    ensurePassiveRunStarted(turn, prompt = userMessage.text)
                } else {
                    ensurePassiveRunStarted(turn, prompt = null)
                }
            }

            is RemoteBotEvent.UserMessage -> {
                if (decoded.text.isNotBlank() || decoded.images.isNotEmpty()) {
                    val turn = ensurePassiveTurn(route, event.seq)
                    val runId = ensurePassiveRunStarted(turn, prompt = null)
                    turn.queuedMessages.add(QueuedUserMessage(decoded.text, decoded.images))
                    if (decoded.text.isNotBlank()) {
                        emitPassiveRuntime(RuntimeEvent.UserInputAdmitted(runId = runId, text = decoded.text))
                    }
                }
            }

            is RemoteBotEvent.QueueUpdate -> {
                val turn = ensurePassiveTurn(route, event.seq)
                val runId = ensurePassiveRunStarted(turn, prompt = null)
                emitPassiveRuntime(RuntimeEvent.QueueChanged(runId, decoded.steering, decoded.followUp))
                val injected = decoded.userMessage
                if (injected != null && (injected.text.isNotBlank() || injected.images.isNotEmpty())) {
                    turn.queuedMessages.add(QueuedUserMessage(injected.text, injected.images))
                    if (injected.text.isNotBlank()) {
                        emitPassiveRuntime(RuntimeEvent.UserInputAdmitted(runId = runId, text = injected.text))
                    }
                }
            }

            is RemoteBotEvent.ApprovalRequest -> {
                if (decoded.resolved) {
                    passiveTurn?.cancelQuestion(decoded.approvalId)
                } else {
                    val turn = ensurePassiveTurn(route, event.seq)
                    ensurePassiveRunStarted(turn, prompt = null)
                    handleApprovalRequest(watchScope, turn, decoded)
                }
            }

            is RemoteBotEvent.TextDelta,
            is RemoteBotEvent.ReasoningDelta,
            is RemoteBotEvent.ToolCall,
            is RemoteBotEvent.ToolResult,
            -> {
                val turn = ensurePassiveTurn(route, event.seq)
                val runId = ensurePassiveRunStarted(turn, prompt = null)
                if (decoded is RemoteBotEvent.TextDelta ||
                    decoded is RemoteBotEvent.ReasoningDelta ||
                    decoded is RemoteBotEvent.ToolCall
                ) {
                    turn.streaming = true
                }
                emitPassiveAgentEvents(turn, runId, decoded)
            }

            is RemoteBotEvent.Ended -> {
                passiveTurn?.state?.consume(decoded)
            }

            is RemoteBotEvent.Failed -> {
                val turn = ensurePassiveTurn(route, event.seq)
                ensurePassiveRunStarted(turn, prompt = null)
                turn.failure = decoded.message
                finishPassiveTurn(turn)
            }

            is RemoteBotEvent.Settled -> {
                passiveTurn?.let { finishPassiveTurn(it) }
            }
        }
    }

    private fun ensurePassiveTurn(route: String, seq: Long): RemoteTurn =
        passiveTurn ?: RemoteTurn(target.botName).also {
            it.routeId = route
            it.acceptSeqUpTo(seq)
            passiveTurn = it
        }

    private suspend fun ensurePassiveRunStarted(turn: RemoteTurn, prompt: String?): String {
        passiveRunId?.let { return it }
        val runId = "remote-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        passiveRunId = runId
        passiveRunStartedAt = now
        val startEvent = if (!prompt.isNullOrBlank()) {
            RuntimeEvent.RunStarted(runId = runId, startedAt = now, prompt = prompt)
        } else {
            BackgroundTurnStarted(runId = runId)
        }
        emitPassiveRuntime(startEvent)
        return runId
    }

    private suspend fun emitPassiveAgentEvents(
        turn: RemoteTurn,
        runId: String,
        decoded: RemoteBotEvent,
    ) {
        for (agentEvent in turn.state.consume(decoded)) {
            when (agentEvent) {
                is AgentEvent.MessageUpdate -> when (val update = agentEvent.assistantMessageEvent) {
                    is TextDeltaEvent -> {
                        emitPassiveRuntime(RuntimeEvent.TextDelta(runId, update.delta))
                        emitPassiveRuntime(RuntimeEvent.ActivityChanged(runId, RuntimeActivity.Thinking))
                    }

                    is ThinkingDeltaEvent -> {
                        emitPassiveRuntime(RuntimeEvent.ReasoningDelta(runId, update.delta))
                        emitPassiveRuntime(RuntimeEvent.ActivityChanged(runId, RuntimeActivity.Thinking))
                    }

                    is ToolCallStartEvent -> {
                        val call = update.partial.content.getOrNull(update.contentIndex) as? ToolCall
                        if (call != null && call.id.isNotBlank() && call.name.isNotBlank()) {
                            emitPassiveRuntime(
                                RuntimeEvent.ToolCallStarted(
                                    runId = runId,
                                    callId = call.id,
                                    name = call.name,
                                    startedAt = System.currentTimeMillis(),
                                ),
                            )
                            emitPassiveRuntime(
                                RuntimeEvent.ActivityChanged(runId, RuntimeActivity.RunningTool(call.name)),
                            )
                        }
                    }

                    else -> Unit
                }

                is AgentEvent.ToolExecutionStart -> {
                    emitPassiveRuntime(
                        RuntimeEvent.ToolStarted(
                            runId = runId,
                            callId = agentEvent.toolCallId,
                            name = agentEvent.toolName,
                            arguments = agentEvent.args.toDisplayArguments(),
                            startedAt = System.currentTimeMillis(),
                            rawArguments = agentEvent.args,
                        ),
                    )
                    emitPassiveRuntime(
                        RuntimeEvent.ActivityChanged(runId, RuntimeActivity.RunningTool(agentEvent.toolName)),
                    )
                }

                is AgentEvent.ToolExecutionEnd -> {
                    val payload = agentEvent.result.toToolResultPayload()
                    emitPassiveRuntime(
                        RuntimeEvent.ToolFinished(
                            runId = runId,
                            callId = agentEvent.toolCallId,
                            name = agentEvent.toolName,
                            result = payload.text(),
                            isError = agentEvent.isError,
                            durationMs = agentEvent.durationMs,
                            payload = payload,
                            details = agentEvent.result.details as? JsonObject,
                        ),
                    )
                    emitPassiveRuntime(RuntimeEvent.ActivityChanged(runId, RuntimeActivity.Thinking))
                }

                else -> Unit
            }
        }
    }

    private suspend fun finishPassiveTurn(turn: RemoteTurn) {
        if (passiveTurn !== turn) return
        val runId = passiveRunId
        passiveTurn = null
        passiveRunId = null
        passiveRunStartedAt = 0L
        turn.cancelQuestions()
        val failure = if (turn.connectionLost) connectionLostMessage() else turn.failure
        val stopReason = when {
            turn.aborted -> StopReason.Aborted
            failure != null -> StopReason.Error
            turn.state.endStatus == STATUS_STOPPED -> StopReason.Aborted
            turn.state.endStatus == STATUS_FAILED -> StopReason.Error
            else -> StopReason.Stop
        }
        val errorMessage = failure ?: if (stopReason == StopReason.Error) FAILED_MESSAGE else null
        persistTurn(turn, stopReason, errorMessage)
        if (runId != null) {
            if (failure != null || stopReason == StopReason.Error) {
                emitPassiveRuntime(RuntimeEvent.RunFailed(runId, errorMessage ?: FAILED_MESSAGE))
            } else {
                val status = if (stopReason == StopReason.Aborted) RunStatus.STOPPED else RunStatus.DONE
                emitPassiveRuntime(
                    RuntimeEvent.RunFinished(
                        runId = runId,
                        text = turn.state.text,
                        status = status,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun emitPassiveRuntime(event: RuntimeEvent) {
        val sink = onPassiveRuntimeEvent ?: return
        try {
            sink(SessionRuntimeEvent(mirror.sessionId, event))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private fun isTrailingMirrorUserText(text: String): Boolean {
        val last = mirror.messages().lastOrNull() ?: return false
        val user = last.asLlmMessage() as? UserMessage ?: return false
        val existingText = when (val content = user.content) {
            is PlainTextContent -> content.text
            is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
        }
        return existingText == text
    }

    private fun JsonObject.toDisplayArguments(): Map<String, String> = entries.associate { (key, value) ->
        key to value.toString().trim('"')
    }

    private fun AgentToolResult.toToolResultPayload(): ToolResultPayload {
        val fileRefs = ToolResultDetails.decodeFileRefs(details as? JsonObject)
        val blocks = buildList {
            for (item in content) {
                when (item) {
                    is TextContent -> add(ToolResultPayload.Block.Text(item.text))
                    is ImageContent -> add(ToolResultPayload.Block.Image(item.data, item.mimeType))
                    else -> Unit
                }
            }
            addAll(fileRefs)
        }
        if (blocks.isEmpty()) return ToolResultPayload.Empty
        if (blocks.all { it is ToolResultPayload.Block.Text }) {
            return ToolResultPayload.Text(
                blocks.joinToString("") { (it as ToolResultPayload.Block.Text).text },
            )
        }
        return ToolResultPayload.Blocks(blocks)
    }

    private enum class WatchEpochResult {
        Preempted,
        ReconnectImmediately,
        ReconnectWhenOnline,
        SessionGone,
        RetryDelay,
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

    private class RemoteTurn(
        modelName: String,
    ) {
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
        private val questionJobs = ConcurrentHashMap<String, Job>()

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
        fun acceptSeqUpTo(seq: Long) {
            if (seq > acceptedSeq) acceptedSeq = seq
        }

        @Synchronized
        fun accept(seq: Long): Boolean {
            if (seq <= acceptedSeq) return false
            acceptedSeq = seq
            return true
        }

        fun hasQuestion(approvalId: String): Boolean = questionJobs.containsKey(approvalId)

        fun putQuestion(approvalId: String, job: Job) {
            questionJobs.put(approvalId, job)?.cancel()
        }

        fun clearQuestion(approvalId: String, job: Job) {
            questionJobs.remove(approvalId, job)
        }

        fun cancelQuestion(approvalId: String) {
            questionJobs.remove(approvalId)?.cancel()
        }

        fun cancelQuestions() {
            val entries = questionJobs.values.toList()
            questionJobs.clear()
            entries.forEach { it.cancel() }
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

    companion object {
        private const val BOT_PROMPT = "bot.prompt"
        private const val BOT_STEER = "bot.steer"
        private const val BOT_FOLLOW_UP = "bot.follow_up"
        private const val BOT_ABORT = "bot.abort"
        private const val BOT_RESUME = "bot.resume"
        private const val BOT_APPROVAL = "bot.approval"
        private const val SESSION_COMPACT = "session.compact"
        private const val SESSION_RETRY = "session.retry"
        private const val STATUS_STOPPED = "stopped"
        private const val STATUS_FAILED = "failed"
        private const val SESSION_NOT_FOUND = "session_not_found"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val THINKING_LEVEL = "off"
        private const val SETTLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val COMPACT_TIMEOUT_MILLIS = 120_000L
        private const val RECONNECT_GRACE_MILLIS = 25_000L
        private const val RESUME_IDLE_DRAIN_MILLIS = 500L
        private const val PASSIVE_RETRY_DELAY_MILLIS = 2_000L
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

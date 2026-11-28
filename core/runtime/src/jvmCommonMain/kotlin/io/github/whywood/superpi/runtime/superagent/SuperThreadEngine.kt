package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.AppToolContext
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.BoundSession
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.MiniappStore
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.Personalization
import io.github.whywood.superpi.runtime.PiSessionEngine
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.StorageMountInfo
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperThreadState
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.host.HostEnvironmentProvider
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.runtime.miniappLookup
import io.github.whywood.superpi.runtime.presentTool
import io.github.whywood.superpi.runtime.runtimeValue
import io.github.whywood.superpi.runtime.text
import io.github.whywood.superpi.runtime.thinkingLevelOf
import io.github.whywood.superpi.runtime.timerTools
import io.github.whywood.superpi.runtime.toDisplayArguments
import io.github.whywood.superpi.runtime.toToolResultPayload
import io.github.whywood.superpi.runtime.wrapPromptSection
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ThinkingDeltaEvent
import io.github.whywood.superpi.sdk.ai.ToolCallStartEvent
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.coding.settings.CompactionSettings
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

fun interface SuperEngineFactory {
    suspend fun open(
        workspaceDir: File,
        sessionFile: File,
        config: ModelConfig,
        questionCenter: QuestionCenter,
        extraSectionsProvider: suspend () -> Map<String, String>,
    ): SessionHandle
}

data class SuperPlatformProfile(
    val deviceNoun: String = "this phone",
    val hasCalendar: Boolean = true,
    val hasScreenAutomation: Boolean = true,
    val hasMiniapps: Boolean = true,
    val hasPlugins: Boolean = true,
    val isDesktop: Boolean = false,
)

class SuperThreadEngine(
    private val superDir: File,
    private val providers: ProviderDirectory,
    private val initialModelConfig: suspend () -> ModelConfig,
    private val sessionFactory: SuperEngineFactory,
    private val memoryStore: MemoryStore? = null,
    private val memoryAgent: MemoryAgentService? = null,
    private val scope: CoroutineScope,
    private val idleDebounceMs: Long = DEFAULT_IDLE_DEBOUNCE_MS,
    private val idleDebounceMsProvider: (suspend () -> Long)? = null,
    private val maxSlotsProvider: () -> Int = { SuperRuntime.MAX_WORKERS_PER_BOT },
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localDeviceLabel: String = "This phone",
    private val platformProfile: SuperPlatformProfile = SuperPlatformProfile(),
) {
    val questionCenter = QuestionCenter()
    val routerDir: File = File(superDir, "router")
    val sessionFile: File = File(routerDir, "session.jsonl")
    val workspaceDir: File = File(routerDir, "workspace")
    val archiveDir: File = File(routerDir, "archive")
    private val modelOverrideFile: File = File(routerDir, "model.json")

    private val _state = MutableStateFlow(SuperThreadState())
    val state: StateFlow<SuperThreadState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private val loadMutex = Mutex()
    private val nextId = AtomicLong(100_000L)

    @Volatile
    private var handle: SessionHandle? = null
    private var unsubscribe: (() -> Unit)? = null
    private var activeTurnJob: Job? = null
    private var activePromptGate: CompletableDeferred<Unit>? = null
    private var idleDebounceJob: Job? = null
    private var abortedTurn = false
    private var activeRunId: String? = null
    private var committedMessages: List<AssistantMessage> = emptyList()
    private var liveMessages: MutableList<AssistantMessage> = mutableListOf()
    private var streamingAssistant: AssistantMessage? = null

    @Volatile
    private var currentBlueprints: List<BotBlueprint> = emptyList()

    init {
        scope.launch {
            questionCenter.pending.collect { pending ->
                _state.update { it.copy(pendingQuestion = pending.firstOrNull()) }
            }
        }
    }

    fun updateBlueprints(blueprints: List<BotBlueprint>) {
        currentBlueprints = blueprints
    }

    fun currentSdkSessionId(): String? = handle?.sessionId

    fun ownsSessionId(sessionId: String): Boolean =
        sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID ||
            (handle?.sessionId != null && handle?.sessionId == sessionId)

    suspend fun ensureLoaded() {
        if (handle != null && _state.value.ready) return
        loadMutex.withLock {
            if (handle != null && _state.value.ready) return
            try {
                openFreshOrExistingHandle()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        ready = false,
                        busy = false,
                        error = error.message ?: "Failed to load Super session",
                    )
                }
                throw error
            }
        }
    }

    private suspend fun awaitActivePromptReady(currentHandle: SessionHandle) {
        val (gate, job) = mutex.withLock { activePromptGate to activeTurnJob }
        gate?.await()
        if (!currentHandle.isBusy && job?.isActive == true) {
            job.join()
        }
    }

    suspend fun send(text: String, images: List<ImagePart> = emptyList()) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty() || images.isNotEmpty()) { "Prompt must not be empty" }
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        awaitActivePromptReady(currentHandle)
        if (currentHandle.isBusy || _state.value.busy) {
            steer(cleanText, images)
            return
        }
        launchTurn(
            promptText = cleanText,
            displayUserText = cleanText,
            reminderTitle = null,
            images = images,
        )
    }

    suspend fun steer(text: String, images: List<ImagePart> = emptyList()) {
        val cleanText = text.trim()
        require(cleanText.isNotEmpty() || images.isNotEmpty()) { "Message must not be empty" }
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        awaitActivePromptReady(currentHandle)
        if (!currentHandle.isBusy && !_state.value.busy) {
            launchTurn(
                promptText = cleanText,
                displayUserText = cleanText,
                reminderTitle = null,
                images = images,
            )
            return
        }
        mutex.withLock {
            val existingStreaming = streamingAssistant
            if (existingStreaming != null &&
                (existingStreaming.text.isNotBlank() || existingStreaming.steps.isNotEmpty())
            ) {
                liveMessages += existingStreaming.copy(
                    streaming = false,
                    status = MessageStatus.DONE,
                    finishedAt = clock(),
                )
                streamingAssistant = null
            }
            appendLiveUserMessage(cleanText, reminderTitle = null)
        }
        publishMessagesState()
        withContext(ioDispatcher) {
            currentHandle.prompt(cleanText, images.takeIf { it.isNotEmpty() }, QueueBehavior.Steer)
        }
    }

    suspend fun followUp(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        awaitActivePromptReady(currentHandle)
        if (currentHandle.isBusy || _state.value.busy) {
            withContext(ioDispatcher) {
                currentHandle.prompt(cleanText, null, QueueBehavior.FollowUp)
            }
        } else {
            launchTurn(
                promptText = cleanText,
                displayUserText = null,
                reminderTitle = null,
                images = emptyList(),
            )
        }
    }

    suspend fun deliverReminder(prompt: String, reminderName: String? = null) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return
        ensureLoaded()
        val wireText = when {
            cleanPrompt.startsWith("[Reminder]") -> cleanPrompt
            reminderName.isNullOrBlank() -> "[Reminder] $cleanPrompt"
            else -> "[Reminder] $reminderName: $cleanPrompt"
        }
        val title = reminderName?.takeIf { it.isNotBlank() } ?: "Reminder"
        val bodyPrefix = "[Reminder] $title\nThis is a reminder you set; it is due now.\n\n"
        val displayBody = cleanPrompt.removePrefix(bodyPrefix).trim().ifEmpty { cleanPrompt }
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        awaitActivePromptReady(currentHandle)
        if (currentHandle.isBusy || _state.value.busy) {
            appendLiveUserMessage(displayBody, reminderTitle = title)
            publishMessagesState()
            withContext(ioDispatcher) {
                currentHandle.prompt(wireText, null, QueueBehavior.FollowUp)
            }
        } else {
            launchTurn(
                promptText = wireText,
                displayUserText = displayBody,
                reminderTitle = title,
                images = emptyList(),
            )
        }
    }

    suspend fun compact() {
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        if (currentHandle.isBusy || _state.value.busy) return
        val compacted = withContext(ioDispatcher) {
            currentHandle.compact()
        }
        val committed = runCatching { currentHandle.history() }.getOrElse { committedMessages }
        val updatedUsage = runCatching { currentHandle.usage() }.getOrNull()
        mutex.withLock {
            committedMessages = committed
            if (compacted != null) {
                liveMessages.clear()
            }
        }
        _state.update {
            it.copy(
                messages = committed,
                usage = updatedUsage ?: it.usage,
                error = null,
            )
        }
    }

    fun abort() {
        val activeHandle: SessionHandle?
        synchronized(this) {
            abortedTurn = true
            activeHandle = handle
        }
        questionCenter.pending.value.forEach { q ->
            questionCenter.cancel(q.requestId)
        }
        activeHandle?.abort()
    }

    suspend fun archiveAndReset() {
        val jobToWait: Job?
        val currentHandle: SessionHandle?
        mutex.withLock {
            idleDebounceJob?.cancel()
            idleDebounceJob = null
            abortedTurn = true
            jobToWait = activeTurnJob
            currentHandle = handle
        }
        currentHandle?.abort()
        runCatching { jobToWait?.join() }

        val closedHandle: SessionHandle? = mutex.withLock {
            unsubscribe?.invoke()
            unsubscribe = null
            val prev = handle
            handle = null
            prev
        }
        if (closedHandle != null) {
            val entries = runCatching { closedHandle.branchEntries() }.getOrNull()
            memoryAgent?.stageArchivedSessionDelta(
                sourceSessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                archivedSessionId = "${SuperRuntime.SUPER_THREAD_SESSION_ID}:archived:${clock()}",
                sessionFile = sessionFile.absolutePath,
                entries = entries,
            )
            runCatching { closedHandle.close() }
        }

        withContext(ioDispatcher) {
            if (sessionFile.isFile && sessionFile.length() > 0L) {
                archiveDir.mkdirs()
                val stamp = ARCHIVE_FORMAT.format(LocalDateTime.now())
                var target = File(archiveDir, "session-$stamp.jsonl")
                var suffix = 1
                while (target.exists()) {
                    target = File(archiveDir, "session-$stamp-${suffix++}.jsonl")
                }
                if (!sessionFile.renameTo(target)) {
                    sessionFile.copyTo(target, overwrite = true)
                    sessionFile.delete()
                }
            } else {
                sessionFile.delete()
            }
        }

        committedMessages = emptyList()
        liveMessages.clear()
        streamingAssistant = null
        activeRunId = null
        abortedTurn = false
        openFreshOrExistingHandle()
    }

    suspend fun appendContextNote(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: return
        runCatching {
            currentHandle.appendCustomMessage(
                customType = "super.context_note",
                payloadJson = buildJsonObject { put("note", clean) }.toString(),
                text = clean,
                display = false,
            )
        }
    }

    suspend fun changeModel(providerId: String, modelId: String, thinkingLevel: ThinkingLevel) {
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        val resolved = providers.resolveConfig(ModelConfig(providerId, modelId, thinkingLevel))
        val sdkModel = providers.sdkModel(resolved.providerId, resolved.modelId)
            ?: error("Model not found: ${resolved.providerId}/${resolved.modelId}")
        withContext(ioDispatcher) {
            currentHandle.setModel(sdkModel, resolved.thinkingLevel.runtimeValue())
            writeModelOverride(resolved)
        }
        syncModelState(currentHandle)
    }

    suspend fun changeThinking(thinkingLevel: ThinkingLevel) {
        ensureLoaded()
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        withContext(ioDispatcher) {
            currentHandle.setThinkingLevel(thinkingLevel.runtimeValue())
            val updatedConfig = ModelConfig(
                providerId = currentHandle.model.provider,
                modelId = currentHandle.model.id,
                thinkingLevel = thinkingLevel,
            )
            writeModelOverride(updatedConfig)
        }
        syncModelState(currentHandle)
    }

    suspend fun syncDefaultModel(config: ModelConfig) {
        loadMutex.withLock {
            val hasOverride = withContext(ioDispatcher) { readModelOverride() != null }
            if (hasOverride) return
            val resolved = providers.resolveConfig(config)
            val sdkModel = providers.sdkModel(resolved.providerId, resolved.modelId) ?: return
            val currentHandle = mutex.withLock {
                if (activeTurnJob?.isActive == true) null else handle
            } ?: return
            if (currentHandle.model.provider == resolved.providerId &&
                currentHandle.model.id == resolved.modelId &&
                thinkingLevelOf(currentHandle.thinkingLevel) == resolved.thinkingLevel
            ) {
                return
            }
            withContext(ioDispatcher) {
                currentHandle.setModel(sdkModel, resolved.thinkingLevel.runtimeValue())
            }
            syncModelState(currentHandle)
        }
    }

    suspend fun answerQuestion(requestId: String, answers: List<QuestionAnswer>) {
        questionCenter.answer(requestId, answers)
    }

    suspend fun cancelQuestion(requestId: String) {
        questionCenter.cancel(requestId)
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun openFreshOrExistingHandle() {
        val (opened, history, usage, archivedCount) = withContext(ioDispatcher) {
            routerDir.mkdirs()
            workspaceDir.mkdirs()
            archiveDir.mkdirs()
            val override = readModelOverride()
            val fallbackConfig = override ?: initialModelConfig()
            val hasOverride = override != null
            val session = sessionFactory.open(
                workspaceDir = workspaceDir,
                sessionFile = sessionFile,
                config = fallbackConfig,
                questionCenter = questionCenter,
                extraSectionsProvider = { buildSystemSections() },
            )
            val loadedHistory = session.history()
            if (!hasOverride &&
                (session.model.provider != fallbackConfig.providerId || session.model.id != fallbackConfig.modelId)
            ) {
                val resolved = providers.resolveConfig(fallbackConfig)
                val sdkModel = providers.sdkModel(resolved.providerId, resolved.modelId)
                if (sdkModel != null) {
                    session.setModel(sdkModel, resolved.thinkingLevel.runtimeValue())
                }
            }
            OpenHandleSnapshot(
                handle = session,
                history = loadedHistory,
                usage = session.usage(),
                archivedCount = countArchivedFiles(),
            )
        }
        val unsub = opened.subscribe { event ->
            onSessionEvent(opened, event)
        }
        mutex.withLock {
            unsubscribe?.invoke()
            handle = opened
            unsubscribe = unsub
            committedMessages = history
            liveMessages.clear()
            streamingAssistant = null
        }
        syncModelState(opened)
        _state.update {
            it.copy(
                messages = committedMessages,
                busy = false,
                streamingText = "",
                streamingReasoning = "",
                streamingSteps = emptyList(),
                activeToolText = null,
                usage = usage,
                ready = true,
                error = null,
                archivedCount = archivedCount,
            )
        }
    }

    private data class OpenHandleSnapshot(
        val handle: SessionHandle,
        val history: List<AssistantMessage>,
        val usage: SessionUsage?,
        val archivedCount: Int,
    )

    private suspend fun launchTurn(
        promptText: String,
        displayUserText: String?,
        reminderTitle: String?,
        images: List<ImagePart>,
    ) {
        val currentHandle = mutex.withLock { handle } ?: error("Super session is unavailable")
        val runId = "srun-" + UUID.randomUUID().toString().replace("-", "").take(10)
        val nowMs = clock()
        val promptGate = CompletableDeferred<Unit>()
        mutex.withLock {
            idleDebounceJob?.cancel()
            idleDebounceJob = null
            abortedTurn = false
            activeRunId = runId
            activePromptGate = promptGate
            liveMessages.clear()
            if (displayUserText != null) {
                appendLiveUserMessage(displayUserText, reminderTitle)
            }
            streamingAssistant = AssistantMessage(
                id = nextId.incrementAndGet(),
                role = AssistantRole.ASSISTANT,
                text = "",
                streaming = true,
                status = MessageStatus.RUNNING,
                startedAt = nowMs,
                runId = runId,
            )
        }
        publishMessagesState(busy = true, activeToolText = null, error = null)
        _events.tryEmit(RuntimeEvent.RunStarted(runId, nowMs, displayUserText.orEmpty()))
        _events.tryEmit(RuntimeEvent.ActivityChanged(runId, RuntimeActivity.Thinking))

        val job = scope.launch(ioDispatcher) {
            var runStatus = RunStatus.DONE
            var runError: String? = null
            try {
                promptGate.complete(Unit)
                currentHandle.prompt(promptText, images.takeIf { it.isNotEmpty() }, null)
                if (abortedTurn) {
                    runStatus = RunStatus.STOPPED
                }
            } catch (cancelled: CancellationException) {
                runStatus = RunStatus.STOPPED
                throw cancelled
            } catch (error: Exception) {
                runStatus = RunStatus.FAILED
                runError = error.message ?: "Super turn failed"
            } finally {
                promptGate.complete(Unit)
                val finishedAt = clock()
                val committed = try {
                    currentHandle.history()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logWarn("SuperThreadEngine", "Failed to read Super history", error)
                    committedMessages
                }
                val usage = try {
                    currentHandle.usage()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logWarn("SuperThreadEngine", "Failed to read Super usage", error)
                    null
                }
                mutex.withLock {
                    committedMessages = committed
                    liveMessages.clear()
                    streamingAssistant = null
                    activeRunId = null
                    activeTurnJob = null
                    if (activePromptGate === promptGate) {
                        activePromptGate = null
                    }
                }
                syncModelState(currentHandle)
                _state.update {
                    it.copy(
                        messages = committed,
                        busy = false,
                        streamingText = "",
                        streamingReasoning = "",
                        streamingSteps = emptyList(),
                        activeToolText = null,
                        usage = usage ?: it.usage,
                        error = runError,
                        archivedCount = countArchivedFiles(),
                    )
                }
                val terminalEvent = if (runStatus == RunStatus.FAILED) {
                    RuntimeEvent.RunFailed(runId, runError.orEmpty())
                } else {
                    val lastText = committed.lastOrNull { it.role == AssistantRole.ASSISTANT }?.text.orEmpty()
                    RuntimeEvent.RunFinished(runId, lastText, runStatus, finishedAt)
                }
                if (!_events.tryEmit(terminalEvent)) {
                    scope.launch { _events.emit(terminalEvent) }
                }
                _events.tryEmit(RuntimeEvent.ActivityChanged(runId, null))
                scheduleIdleMemoryDebounce(currentHandle)
            }
        }
        mutex.withLock {
            activeTurnJob = job
        }
    }

    private suspend fun onSessionEvent(session: SessionHandle, event: SessionEvent) {
        when (event) {
            is SessionEvent.CompactionStarted -> {
                val entries = runCatching { session.branchEntries() }.getOrNull()
                memoryAgent?.launchCompactionSession(
                    sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                    sessionFile = sessionFile.absolutePath,
                    entries = entries,
                )
            }

            is SessionEvent.CompactionFinished -> {
                val committed = runCatching { session.history() }.getOrNull() ?: return
                val updatedUsage = runCatching { session.usage() }.getOrNull()
                mutex.withLock {
                    committedMessages = committed
                }
                publishMessagesState()
                if (updatedUsage != null) {
                    _state.update { it.copy(usage = updatedUsage) }
                }
            }

            is SessionEvent.AgentEventReceived -> onAgentEvent(event.event)
            else -> Unit
        }
    }

    private suspend fun onAgentEvent(agentEvent: AgentEvent) {
        val nowMs = clock()
        val runId = activeRunId.orEmpty()
        when (agentEvent) {
            is AgentEvent.MessageStart -> {
                if (agentEvent.message.asLlmMessage() is UserMessage) {
                    val flushed = mutex.withLock {
                        val existingStreaming = streamingAssistant
                        if (existingStreaming != null &&
                            (existingStreaming.text.isNotBlank() || existingStreaming.steps.isNotEmpty())
                        ) {
                            liveMessages += existingStreaming.copy(
                                streaming = false,
                                status = MessageStatus.DONE,
                                finishedAt = nowMs,
                            )
                            streamingAssistant = null
                            true
                        } else {
                            false
                        }
                    }
                    if (flushed) {
                        publishMessagesState(activeToolText = null)
                    }
                }
            }

            is AgentEvent.MessageUpdate -> when (val update = agentEvent.assistantMessageEvent) {
                is TextDeltaEvent -> {
                    mutex.withLock {
                        val assistant = ensureStreamingAssistant(nowMs, runId)
                        val steps = assistant.steps.toMutableList()
                        val last = steps.lastOrNull()
                        if (last is AssistantStep.Text) {
                            steps[steps.lastIndex] = last.copy(text = last.text + update.delta)
                        } else {
                            steps += AssistantStep.Text(nextId.incrementAndGet(), update.delta)
                        }
                        streamingAssistant = assistant.copy(
                            text = assistant.text + update.delta,
                            steps = steps,
                        )
                    }
                    publishMessagesState()
                    _events.tryEmit(RuntimeEvent.TextDelta(runId, update.delta))
                }

                is ThinkingDeltaEvent -> {
                    mutex.withLock {
                        val assistant = ensureStreamingAssistant(nowMs, runId)
                        val steps = assistant.steps.toMutableList()
                        val last = steps.lastOrNull()
                        if (last is AssistantStep.Reasoning) {
                            steps[steps.lastIndex] = last.copy(text = last.text + update.delta)
                        } else {
                            steps += AssistantStep.Reasoning(nextId.incrementAndGet(), update.delta)
                        }
                        streamingAssistant = assistant.copy(steps = steps)
                    }
                    publishMessagesState()
                    _events.tryEmit(RuntimeEvent.ReasoningDelta(runId, update.delta))
                }

                is ToolCallStartEvent -> {
                    val call = update.partial.content.getOrNull(update.contentIndex) as? io.github.whywood.superpi.sdk.ai.ToolCall
                    if (call != null && call.id.isNotBlank() && call.name.isNotBlank()) {
                        mutex.withLock {
                            val assistant = ensureStreamingAssistant(nowMs, runId)
                            if (assistant.steps.none { it is AssistantStep.Tool && it.trace.toolCallId == call.id }) {
                                val step = AssistantStep.Tool(
                                    ToolTrace(
                                        id = nextId.incrementAndGet(),
                                        name = call.name,
                                        arguments = emptyMap(),
                                        toolCallId = call.id,
                                        startedAt = nowMs,
                                    ),
                                )
                                streamingAssistant = assistant.copy(steps = assistant.steps + step)
                            }
                        }
                        publishMessagesState(activeToolText = call.name)
                        _events.tryEmit(RuntimeEvent.ToolCallStarted(runId, call.id, call.name, nowMs))
                    }
                }

                else -> Unit
            }

            is AgentEvent.ToolExecutionStart -> {
                val displayArgs = agentEvent.args.toDisplayArguments()
                mutex.withLock {
                    val assistant = ensureStreamingAssistant(nowMs, runId)
                    val steps = assistant.steps.toMutableList()
                    val index = steps.indexOfLast { it is AssistantStep.Tool && it.trace.toolCallId == agentEvent.toolCallId }
                    if (index >= 0) {
                        val trace = (steps[index] as AssistantStep.Tool).trace
                        steps[index] = AssistantStep.Tool(
                            trace.copy(
                                name = agentEvent.toolName.ifBlank { trace.name },
                                arguments = displayArgs.ifEmpty { trace.arguments },
                                rawArguments = agentEvent.args,
                                startedAt = trace.startedAt.takeIf { it > 0L } ?: nowMs,
                            ),
                        )
                    } else {
                        steps += AssistantStep.Tool(
                            ToolTrace(
                                id = nextId.incrementAndGet(),
                                name = agentEvent.toolName,
                                arguments = displayArgs,
                                rawArguments = agentEvent.args,
                                toolCallId = agentEvent.toolCallId,
                                startedAt = nowMs,
                            ),
                        )
                    }
                    streamingAssistant = assistant.copy(steps = steps)
                }
                publishMessagesState(activeToolText = agentEvent.toolName)
                _events.tryEmit(
                    RuntimeEvent.ToolStarted(
                        runId = runId,
                        callId = agentEvent.toolCallId,
                        name = agentEvent.toolName,
                        arguments = displayArgs,
                        rawArguments = agentEvent.args,
                        startedAt = nowMs,
                    ),
                )
            }

            is AgentEvent.ToolExecutionEnd -> {
                val payload = agentEvent.result.toToolResultPayload()
                val text = payload.text()
                val details = agentEvent.result.details as? JsonObject
                val status = if (agentEvent.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE
                val (toolDurationMs, remainingActive) = mutex.withLock {
                    val assistant = ensureStreamingAssistant(nowMs, runId)
                    val steps = assistant.steps.toMutableList()
                    val index = steps.indexOfLast { it is AssistantStep.Tool && it.trace.toolCallId == agentEvent.toolCallId }
                    var duration = 0L
                    if (index >= 0) {
                        val trace = (steps[index] as AssistantStep.Tool).trace
                        duration = (nowMs - trace.startedAt).coerceAtLeast(0L)
                        steps[index] = AssistantStep.Tool(
                            trace.copy(
                                status = status,
                                output = text.takeIf { !agentEvent.isError && it.isNotBlank() },
                                error = text.takeIf { agentEvent.isError && it.isNotBlank() },
                                resultPayload = payload,
                                resultDetails = details,
                                durationMs = duration,
                            ),
                        )
                    } else {
                        steps += AssistantStep.Tool(
                            ToolTrace(
                                id = nextId.incrementAndGet(),
                                name = agentEvent.toolName,
                                arguments = emptyMap(),
                                status = status,
                                output = text.takeIf { !agentEvent.isError && it.isNotBlank() },
                                error = text.takeIf { agentEvent.isError && it.isNotBlank() },
                                toolCallId = agentEvent.toolCallId,
                                resultPayload = payload,
                                resultDetails = details,
                            ),
                        )
                    }
                    streamingAssistant = assistant.copy(steps = steps)
                    val active = steps.filterIsInstance<AssistantStep.Tool>()
                        .lastOrNull { it.trace.status == ToolActivityStatus.RUNNING }
                        ?.trace
                        ?.name
                    duration to active
                }
                publishMessagesState(activeToolText = remainingActive)
                _events.tryEmit(
                    RuntimeEvent.ToolFinished(
                        runId = runId,
                        callId = agentEvent.toolCallId,
                        name = agentEvent.toolName,
                        result = text,
                        isError = agentEvent.isError,
                        durationMs = toolDurationMs,
                        payload = payload,
                        details = details,
                    ),
                )
            }

            else -> Unit
        }
    }

    private fun appendLiveUserMessage(text: String, reminderTitle: String?) {
        liveMessages += AssistantMessage(
            id = nextId.incrementAndGet(),
            role = AssistantRole.USER,
            text = text,
            reminder = reminderTitle,
            startedAt = clock(),
        )
    }

    private fun ensureStreamingAssistant(nowMs: Long, runId: String): AssistantMessage =
        streamingAssistant ?: AssistantMessage(
            id = nextId.incrementAndGet(),
            role = AssistantRole.ASSISTANT,
            text = "",
            streaming = true,
            status = MessageStatus.RUNNING,
            startedAt = nowMs,
            runId = runId,
        ).also { streamingAssistant = it }

    private suspend fun publishMessagesState(
        busy: Boolean? = null,
        activeToolText: String? = _state.value.activeToolText,
        error: String? = _state.value.error,
    ) {
        val (snapshot, streaming) = mutex.withLock {
            val combined = buildList {
                addAll(committedMessages)
                addAll(liveMessages)
                streamingAssistant?.let { add(it) }
            }
            combined to streamingAssistant
        }
        val reasoning = streaming?.steps
            ?.filterIsInstance<AssistantStep.Reasoning>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        _state.update { current ->
            current.copy(
                messages = snapshot,
                busy = busy ?: current.busy,
                streamingText = streaming?.text.orEmpty(),
                streamingReasoning = reasoning,
                streamingSteps = streaming?.steps.orEmpty(),
                activeToolText = activeToolText,
                error = error,
            )
        }
    }

    private fun syncModelState(session: SessionHandle) {
        val providerId = session.model.provider
        val modelId = session.model.id
        val option = providers.models(providerId).firstOrNull { it.id == modelId }
        val label = option?.displayName ?: modelId
        val thinking = thinkingLevelOf(session.thinkingLevel)
        val levels = option?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: listOf(ThinkingLevel.OFF)
        _state.update {
            it.copy(
                providerId = providerId,
                modelId = modelId,
                modelLabel = label,
                thinkingLevel = thinking,
                thinkingLevels = levels,
            )
        }
    }

    private fun scheduleIdleMemoryDebounce(session: SessionHandle) {
        if (memoryAgent == null) return
        if (idleDebounceMsProvider == null && idleDebounceMs <= 0L) return
        idleDebounceJob?.cancel()
        idleDebounceJob = scope.launch(ioDispatcher) {
            val waitMs = idleDebounceMsProvider?.invoke() ?: idleDebounceMs
            if (waitMs <= 0L) return@launch
            delay(waitMs)
            if (activeTurnJob?.isActive == true) return@launch
            val entries = runCatching { session.branchEntries() }.getOrNull() ?: return@launch
            memoryAgent.launchIdleSession(
                sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                sessionFile = sessionFile.absolutePath,
                entries = entries,
            )
        }
    }

    private fun buildSystemSections(): Map<String, String> = buildMap {
        put(
            "super_role",
            wrapPromptSection(
                "super_role",
                superRoleInstructions(maxSlotsProvider().coerceAtLeast(1), platformProfile),
            ),
        )
        put("available_bots", wrapPromptSection("available_bots", formatAvailableBots(currentBlueprints)))
        readSuperInstructions()?.let { instructions ->
            put("super_instructions", wrapPromptSection("super_instructions", instructions))
        }
    }

    private fun readSuperInstructions(): String? {
        val candidate = listOf("AGENTS.override.md", "AGENTS.md")
            .map { name -> File(workspaceDir, name) }
            .firstOrNull { file -> file.isFile && file.canRead() }
            ?: return null
        val bytes = runCatching {
            candidate.inputStream().use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var remaining = MAX_SUPER_INSTRUCTIONS_BYTES
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun formatAvailableBots(blueprints: List<BotBlueprint>): String {
        val enabled = blueprints.filter { it.enabledInSuper }
        if (enabled.isEmpty()) {
            val directList = buildList {
                add("light conversation")
                add("memory")
                add("timers")
                if (platformProfile.hasCalendar) add("calendar")
                add("web queries")
            }.joinToString(", ")
            return "No assistants are enabled in the team yet. Handle $directList directly, or ask the user to enable team assistants."
        }
        return enabled.joinToString("\n\n") { bot ->
            val deviceLabel = if (bot.isRemote) "${bot.deviceName} (${bot.deviceId})" else localDeviceLabel
            val caps = buildList {
                if (bot.capabilities.skills.isNotEmpty()) {
                    add("skills=${bot.capabilities.skills.take(MAX_CAP_ITEMS).joinToString(",")}")
                }
                if (bot.capabilities.plugins.isNotEmpty()) {
                    add("plugins=${bot.capabilities.plugins.take(MAX_CAP_ITEMS).joinToString(",")}")
                }
                if (bot.capabilities.mcpServers.isNotEmpty()) {
                    add("mcp=${bot.capabilities.mcpServers.take(MAX_CAP_ITEMS).joinToString(",")}")
                }
                if (bot.capabilities.screenTools) add("screen=yes")
                if (bot.capabilities.miniappTools) add("miniapp=yes")
            }.joinToString(" | ")
            buildString {
                append("- ref: ").append(bot.ref)
                append(" | name: ").append(bot.name)
                append(" | device: ").append(deviceLabel)
                if (bot.intro.isNotBlank()) {
                    append("\n  intro: ").append(bot.intro.trim())
                }
                if (caps.isNotBlank()) {
                    append("\n  capabilities: ").append(caps)
                }
            }
        }
    }

    private fun countArchivedFiles(): Int =
        archiveDir.listFiles()?.count { it.isFile && it.name.endsWith(".jsonl") } ?: 0

    private fun readModelOverride(): ModelConfig? {
        if (!modelOverrideFile.isFile) return null
        val obj = runCatching { JSON.parseToJsonElement(modelOverrideFile.readText()) as? JsonObject }.getOrNull()
            ?: return null
        val providerId = (obj["providerId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val modelId = (obj["modelId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val thinking = thinkingLevelOf((obj["thinkingLevel"] as? JsonPrimitive)?.contentOrNull)
        return ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = thinking)
    }

    private fun writeModelOverride(config: ModelConfig) {
        routerDir.mkdirs()
        val obj = buildJsonObject {
            put("providerId", config.providerId)
            put("modelId", config.modelId)
            put("thinkingLevel", config.thinkingLevel.name)
        }
        val tmp = File(routerDir, "model.json.tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(modelOverrideFile)) {
            modelOverrideFile.writeText(obj.toString())
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_IDLE_DEBOUNCE_MS = 5 * 60_000L
        private const val EVENT_BUFFER_CAPACITY = 256
        private const val MAX_CAP_ITEMS = 8
        private const val MAX_SUPER_INSTRUCTIONS_BYTES = 64 * 1024
        private val ARCHIVE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val JSON = Json { ignoreUnknownKeys = true }

        val SUPER_ALLOWED_TOOLS: Set<String> = setOf(
            "read",
            "ls",
            "grep",
            "find",
            "memory_search",
            "memory_inspect_entity",
            "memory_read_archive",
            "memory_dispatch",
            "present",
            "ask_user_question",
            "timer",
            "calendar",
            "web_search",
            "web_fetch",
            "worker",
        )

        private fun superRoleInstructions(
            maxSlotsPerBot: Int,
            profile: SuperPlatformProfile = SuperPlatformProfile(),
        ): String {
            val directCapabilities = buildList {
                add("personal memory (search/inspect/read archive and async memory_dispatch)")
                add("timers (including Super and Worker timers)")
                if (profile.hasCalendar) add("calendar events")
                add("quick web lookups")
            }.joinToString(", ")
            val delegateCapabilities = buildList {
                add("file writes/edits")
                add("shell execution")
                if (profile.hasScreenAutomation) add("screen automation")
                if (profile.hasMiniapps) add("miniapp authoring")
                if (profile.hasPlugins) add("plugins")
                add("MCP")
                add("tasks targeting another device")
            }.joinToString(", ")
            val deviceLocation = if (profile.isDesktop) "the local computer's" else "the phone's"
            return """
            You are Super PI, the user's single persistent Personal Agent & Task Router on ${profile.deviceNoun}.
            - Respond quickly and naturally. Handle light conversation, $directCapabilities directly without delegating.
            - You also have read-only local query tools (read, ls, grep, find) with explicit absolute paths to inspect $deviceLocation files and environment before answering or delegating. Do not fabricate file edits or shell outputs—delegate $delegateCapabilities to a suitable whitelisted bot from <available_bots> using the worker tool.
            - Use worker(action=list) whenever you need to discover existing persistent worker slots and their ids after conversation resets, compaction, or between tasks.
            - When calling worker(action=start), always set a short descriptive `name` so the user and worker lists clearly show what each worker owns.
            - Forward user-provided images, local file paths, or worker:// artifact URIs via worker(action=start|continue, attachments=[...]) when a worker needs them.
            - Each bot keeps at most $maxSlotsPerBot persistent worker slots across callers. Reuse an existing worker with worker(action=continue) when extending related work, or delete an idle slot before starting another.
            - worker(action=start) and worker(action=continue) run asynchronously and return immediately. Acknowledge dispatch clearly and never claim a task finished before receiving its completion update.
            - When a worker asks the user a question (ask_user_question), the user answers directly in the UI. Do not call worker(action=answer|dismiss) unless you receive a [Worker waiting-input timeout] notice handing the decision to you; whenever you decide after a timeout, report your decision clearly to the user.
            - When a worker produces files, images, videos, or html reports (returned as worker://<workerId>/<artifactId> URIs), call present(items=[...]) with those worker:// paths so the user gets native rich preview cards. Do not fabricate local file paths for remote artifacts.
        """.trimIndent()
        }

        fun defaultFactory(
            agentDir: File,
            credentials: CredentialStore,
            transport: HttpTransport,
            providers: ProviderCatalog,
            personalization: suspend () -> Personalization,
            memoryStore: MemoryStore?,
            timerService: () -> TimerService,
            miniappStore: MiniappStore? = null,
            extraTools: () -> List<CodingTool>,
            workerGateway: WorkerOrchestrationGateway,
            cardExists: (String) -> Boolean,
            artifactResolver: suspend (String) -> File?,
            compactionSettingsProvider: () -> CompactionSettings,
            hostEnvironment: HostEnvironmentProvider? = null,
            sharedStorage: suspend () -> Boolean = { false },
            storageMounts: suspend () -> List<StorageMountInfo> = { emptyList() },
            timerFactory: (TimerService, SessionBinding) -> List<CodingTool> = { service, binding ->
                timerTools(service, binding)
            },
            memoryAgent: MemoryAgentService? = null,
            hostPlatformLabel: String = "Android",
            platformProfile: SuperPlatformProfile = SuperPlatformProfile(),
        ): SuperEngineFactory = SuperEngineFactory { workspaceDir, sessionFile, config, questionCenter, extraSectionsProvider ->
            val superTimerBinding = SessionBinding(supportsChatDelivery = true)
            superTimerBinding.bind(
                BoundSession(
                    sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                    sessionFile = sessionFile.absolutePath,
                    cwd = workspaceDir.absolutePath,
                ),
            )
            val toolContext = AppToolContext(
                questionCenter = questionCenter,
                extraToolFactories = listOf(
                    { _, binding ->
                        buildList {
                            if (memoryStore != null) {
                                addAll(
                                    memoryTools(
                                        store = memoryStore,
                                        agentService = memoryAgent,
                                        scope = { MemoryAccessScope.PERSONAL },
                                        allowDispatch = { memoryAgent != null },
                                        sessionId = { SuperRuntime.SUPER_THREAD_SESSION_ID },
                                        isSessionExcluded = { false },
                                    ),
                                )
                            }
                            add(
                                presentTool(
                                    miniappLookup = miniappStore?.let(::miniappLookup),
                                    session = { binding.current() },
                                    cardExists = { id -> binding.hasCardToolResult(id) || cardExists(id) },
                                    artifactResolver = artifactResolver,
                                ),
                            )
                            addAll(timerFactory(timerService(), superTimerBinding))
                            addAll(extraTools())
                            add(workerOrchestrationTool(workerGateway))
                        }
                    },
                ),
            )
            val engine = PiSessionEngine(
                workspace = workspaceDir,
                agentDir = agentDir,
                credentials = credentials,
                transport = transport,
                hostEnvironment = hostEnvironment,
                providers = providers,
                personalization = personalization,
                sharedStorage = sharedStorage,
                storageMounts = storageMounts,
                toolContext = toolContext,
                memory = memoryStore,
                compactionSettingsProvider = compactionSettingsProvider,
                extraSystemPromptSectionsProvider = extraSectionsProvider,
                defaultShellEnabled = false,
                defaultMcpEnabled = false,
                defaultToolsFilter = { it.name in SUPER_ALLOWED_TOOLS },
                captureMemoryOnClose = false,
                includePromptResources = false,
                readOnlyPathsPrompt = true,
                enableReadOnlyQueryTools = true,
                memoryScope = { MemoryAccessScope.PERSONAL },
                awaitMemoryReady = { memoryAgent?.awaitHighPriority(1_500L) },
                environmentLinesOverride = {
                    if (platformProfile.isDesktop) {
                        val envLines = hostEnvironment?.current()?.promptLines.orEmpty()
                        envLines + "Role: Super PI desktop orchestrator (read-only local inspection via read/ls/grep/find; delegate file edits, bash execution, and MCP tasks to workers)."
                    } else {
                        val access = hostEnvironment?.current()?.access?.name?.lowercase() ?: "local"
                        listOf(
                            "Env: $hostPlatformLabel Super PI orchestrator (read-only local inspection via read/ls/grep/find; host access: $access; delegate file writes, bash, screen, miniapp, plugins and MCP to workers).",
                        )
                    }
                },
            )
            engine.create(file = sessionFile.absolutePath, config = config)
        }
    }
}

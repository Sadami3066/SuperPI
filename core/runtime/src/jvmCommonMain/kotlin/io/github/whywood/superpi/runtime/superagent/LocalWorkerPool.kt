package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.HistoryMapper
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.PendingToolApproval
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolApprovalCenter
import io.github.whywood.superpi.runtime.ToolResultDetails
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerJobSummary
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import io.github.whywood.superpi.runtime.WorkerSlotSummary
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import io.github.whywood.superpi.runtime.WorkerTimerSummary
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.runtime.runtimeValue
import io.github.whywood.superpi.runtime.thinkingLevelOf
import io.github.whywood.superpi.runtime.toDisplayArguments
import io.github.whywood.superpi.runtime.toToolResultPayload
import io.github.whywood.superpi.runtime.text
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ThinkingDeltaEvent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolCallStartEvent
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.coding.session.SessionManager
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

data class LocalWorkerBotSpec(
    val botId: String,
    val name: String,
    val avatar: String? = null,
    val cwd: File,
    val defaultModel: ModelConfig = ModelConfig(),
)

fun interface WorkerSessionFactory {
    suspend fun open(
        spec: LocalWorkerBotSpec,
        workerId: String,
        workerWorkspaceDir: File,
        sessionFile: File,
        approvalCenter: QuestionCenter,
    ): SessionHandle
}

data class LocalWorkerEvent(
    val workerId: String,
    val botId: String,
    val seq: Long,
    val event: JsonObject,
    val terminal: Boolean = false,
)

data class LocalWorkerMilestoneEvent(
    val originCaller: String,
    val milestone: WorkerMilestone,
)

data class WorkerReplaySnapshot(
    val record: WorkerRunRecord,
    val seq: Long,
    val evictedBefore: Boolean,
    val events: List<LocalWorkerEvent>,
)

data class WorkerInspectExtras(
    val activeTimers: List<WorkerTimerSummary> = emptyList(),
    val runningJobs: List<WorkerJobSummary> = emptyList(),
)

class LocalWorkerPool(
    private val workersRoot: File,
    private val taskStore: SuperTaskStore,
    private val sessionFactory: WorkerSessionFactory,
    private val resolveBotSpec: suspend (botId: String) -> LocalWorkerBotSpec?,
    private val scope: CoroutineScope,
    private val deviceNameProvider: () -> String = { "This device" },
    private val maxWorkersPerBot: Int = SuperRuntime.MAX_WORKERS_PER_BOT,
    private val maxConcurrentWorkers: Int = MAX_CONCURRENT_LOCAL_WORKERS,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val idleDebounceMs: Long = DEFAULT_IDLE_DEBOUNCE_MS,
    private val idleEvictMs: Long = DEFAULT_IDLE_EVICT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val providers: ProviderDirectory? = null,
    private val onWorkerSessionCleanup: (sessionId: String) -> Unit = {},
    private val questionTimeoutMs: Long = 0L,
    private val settingsProvider: (() -> SuperRuntimeSettings)? = null,
    private val hasRunningJobs: (sessionId: String) -> Boolean = { false },
    private val onWorkerDeleted: suspend (workerId: String) -> Unit = {},
    private val inspectExtrasProvider: suspend (workerId: String, sdkSessionId: String?) -> WorkerInspectExtras = { _, _ ->
        WorkerInspectExtras()
    },
    private val toolApprovalCenter: ToolApprovalCenter = ToolApprovalCenter(),
) {
    val approvalCenter = QuestionCenter()
    val workers: StateFlow<List<WorkerRunRecord>> = taskStore.workers

    private val _progressEvents = MutableSharedFlow<WorkerProgressEvent>(extraBufferCapacity = PROGRESS_BUFFER)
    val progressEvents: SharedFlow<WorkerProgressEvent> = _progressEvents.asSharedFlow()

    private val _milestones = MutableSharedFlow<LocalWorkerMilestoneEvent>(extraBufferCapacity = MILESTONE_BUFFER)
    val milestones: SharedFlow<LocalWorkerMilestoneEvent> = _milestones.asSharedFlow()

    private val _wireEvents = MutableSharedFlow<LocalWorkerEvent>(extraBufferCapacity = WIRE_EVENT_BUFFER)
    val wireEvents: SharedFlow<LocalWorkerEvent> = _wireEvents.asSharedFlow()

    private val poolMutex = Mutex()
    private val initMutex = Mutex()
    private val initialized = AtomicBoolean(false)
    private val slots = LinkedHashMap<String, WorkerSlotRuntime>()
    private val sessionToWorker = ConcurrentHashMap<String, String>()
    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<WorkerSessionSnapshot>>()
    private val pendingQueue = ArrayDeque<QueuedExecution>()
    private var previousQuestions = emptyMap<String, PendingQuestion>()
    private var previousToolApprovals = emptyMap<String, PendingToolApproval>()

    private data class QueuedExecution(
        val workerId: String,
        val botId: String,
        var prompt: String,
        var images: List<ImagePart>,
        val originCaller: String,
        val silentPolling: Boolean = false,
    )

    private data class PendingWorkerFollowUp(
        val message: String,
        val silentPolling: Boolean,
    )

    private data class ResolvedApproval(
        val workerId: String,
        val botId: String,
        val updatedRecord: WorkerRunRecord,
        val queuedFollowUps: List<PendingWorkerFollowUp>,
    )

    private class WorkerSlotRuntime(
        val workerId: String,
        val botId: String,
        var record: WorkerRunRecord,
        var originCaller: String = LOCAL_CALLER,
        var handle: SessionHandle? = null,
        var unsubscribe: (() -> Unit)? = null,
        var turnJob: Job? = null,
        var watchdogJob: Job? = null,
        var questionEscalationJob: Job? = null,
        var idleEvictJob: Job? = null,
        var pendingSnapshotJob: Job? = null,
        val seq: AtomicLong = AtomicLong(0L),
        var evictedBefore: Boolean = false,
        val buffer: ArrayDeque<LocalWorkerEvent> = ArrayDeque(),
        val textBuffer: StringBuilder = StringBuilder(),
        var lastToolName: String? = null,
        var activeQuestion: PendingQuestion? = null,
        var questionEscalatedToSuper: Boolean = false,
        var maintenanceInFlight: Boolean = false,
        var abortedByUser: Boolean = false,
        val activeToolCallIds: MutableSet<String> = mutableSetOf(),
        val pendingFollowUps: ArrayDeque<PendingWorkerFollowUp> = ArrayDeque(),
        var compacting: Boolean = false,
        var retrying: Boolean = false,
        var silentPollingTurn: Boolean = false,
        var turnProducedOutputOrCard: Boolean = false,
        var cachedUsage: SessionUsage? = null,
        @Volatile var lastProgressAtMs: Long = record.lastProgressAt,
        var modelConfig: ModelConfig? = null,
        var committedMessages: List<AssistantMessage> = emptyList(),
        val liveMessages: MutableList<AssistantMessage> = mutableListOf(),
        var streamingAssistant: AssistantMessage? = null,
        val nextUiId: AtomicLong = AtomicLong(100_000L),
    )

    suspend fun initialize() {
        if (initialized.get()) return
        initMutex.withLock {
            if (initialized.get()) return
            taskStore.ensure()
            taskStore.recoverStaleLocalWorkers()
            reconcileDiskAndStore()
            scope.launch {
                approvalCenter.pending.collect { pending ->
                    onApprovalPendingChanged(pending)
                }
            }
            scope.launch {
                toolApprovalCenter.pending.collect { pending ->
                    onToolApprovalPendingChanged(pending)
                }
            }
            initialized.set(true)
        }
    }

    fun list(botId: String? = null): List<WorkerRunRecord> =
        taskStore.workers.value.filter { worker ->
            !worker.isRemote && (botId == null || worker.botId == botId)
        }

    fun usedSlotsForBot(botId: String): Int = list(botId).size

    fun maxSlotsPerBot(): Int = effectiveMaxSlotsPerBot()

    suspend fun onSettingsChanged() {
        drainPendingQueue()
        val active = poolMutex.withLock {
            slots.values.filter {
                it.record.status == WorkerStatus.RUNNING ||
                    it.record.status == WorkerStatus.STALLED ||
                    it.record.status == WorkerStatus.WAITING_INPUT
            }.map { it.workerId to it.record.status }
        }
        for ((workerId, status) in active) {
            when (status) {
                WorkerStatus.RUNNING, WorkerStatus.STALLED -> resetStallWatchdog(workerId)
                WorkerStatus.WAITING_INPUT -> scheduleQuestionTimeout(workerId)
                else -> Unit
            }
        }
    }

    suspend fun start(
        botId: String,
        task: String,
        requestedWorkerId: String? = null,
        images: List<ImagePart> = emptyList(),
        originCaller: String = LOCAL_CALLER,
        modelOverride: ModelConfig? = null,
        title: String? = null,
    ): WorkerRunRecord {
        initialize()
        val cleanTask = task.trim()
        require(cleanTask.isNotEmpty() || images.isNotEmpty()) { "task must not be empty" }
        val spec = resolveBotSpec(botId)
            ?: throw IllegalArgumentException("bot $botId is not available")
        val workerId = requestedWorkerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: ("wrk-" + UUID.randomUUID().toString().replace("-", "").take(12))

        val (record, shouldRunImmediately) = poolMutex.withLock {
            if (slots.containsKey(workerId) ||
                workerMetaFile(botId, workerId).exists() ||
                workerSessionFile(botId, workerId).exists()
            ) {
                throw WorkerConflictException(workerId, "Worker $workerId already exists")
            }
            val existing = slots.values.filter { it.botId == botId }
            val slotCap = effectiveMaxSlotsPerBot()
            if (existing.size >= slotCap) {
                throw WorkerPoolFullException(
                    botId = botId,
                    existing = existing
                        .sortedByDescending { it.record.updatedAt }
                        .map {
                            WorkerSlotSummary(
                                workerId = it.workerId,
                                title = it.record.title,
                                status = it.record.status,
                                updatedAt = it.record.updatedAt,
                            )
                        },
                    maxSlots = slotCap,
                )
            }
            val nowMs = clock()
            val runNow = activeExecutingCountLocked() < effectiveMaxConcurrentWorkers()
            val initialStatus = if (runNow) WorkerStatus.RUNNING else WorkerStatus.QUEUED
            val resolvedTitle = title
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.take(60)
                ?.takeIf { it.isNotEmpty() }
                ?: summarizeTitle(cleanTask)
            val created = WorkerRunRecord(
                workerId = workerId,
                botRef = SuperRuntime.localBotRef(botId),
                botId = botId,
                botName = spec.name,
                botAvatar = spec.avatar,
                deviceId = null,
                deviceName = deviceNameProvider(),
                title = resolvedTitle,
                status = initialStatus,
                lastOutcome = WorkerOutcome.NONE,
                turnCount = 1,
                createdAt = nowMs,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                activeStepText = null,
            )
            ensureWorkerDirectories(botId, workerId)
            writeMetaFile(created)
            val runtime = WorkerSlotRuntime(
                workerId = workerId,
                botId = botId,
                record = created,
                originCaller = originCaller,
                modelConfig = modelOverride ?: spec.defaultModel,
            )
            runtime.liveMessages += AssistantMessage(
                id = runtime.nextUiId.incrementAndGet(),
                role = AssistantRole.USER,
                text = cleanTask,
                startedAt = nowMs,
            )
            if (runNow) {
                runtime.streamingAssistant = AssistantMessage(
                    id = runtime.nextUiId.incrementAndGet(),
                    role = AssistantRole.ASSISTANT,
                    text = "",
                    streaming = true,
                    status = MessageStatus.RUNNING,
                    startedAt = nowMs,
                )
            }
            slots[workerId] = runtime
            if (!runNow) {
                pendingQueue.addLast(QueuedExecution(workerId, botId, cleanTask, images, originCaller))
            }
            publishSessionSnapshotLocked(runtime, loaded = true, error = null)
            created to runNow
        }

        taskStore.upsertWorker(record)
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, record.status, record.activeStepText))
        if (shouldRunImmediately) {
            launchTurn(workerId, spec, cleanTask, images, originCaller)
        }
        return record
    }

    suspend fun continueWorker(
        botId: String,
        workerId: String,
        message: String,
        images: List<ImagePart> = emptyList(),
        originCaller: String = LOCAL_CALLER,
        silentPolling: Boolean = false,
    ): WorkerRunRecord {
        initialize()
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty() || images.isNotEmpty()) { "message must not be empty" }
        val spec = resolveBotSpec(botId)
            ?: throw IllegalArgumentException("bot $botId is not available")
        val preloadedHistory = loadPreloadedHistoryIfNeeded(workerId, botId)

        val (record, shouldRunImmediately) = poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            if (slot.botId != botId) throw WorkerNotFoundException(workerId)
            if (slot.record.status.isBusyForCommands || slot.maintenanceInFlight) {
                throw WorkerConflictException(workerId, "Worker $workerId is busy (${slot.record.status.name})")
            }
            slot.idleEvictJob?.cancel()
            slot.idleEvictJob = null
            slot.questionEscalationJob?.cancel()
            slot.questionEscalationJob = null
            slot.questionEscalatedToSuper = false
            slot.abortedByUser = false
            slot.originCaller = originCaller
            slot.textBuffer.clear()
            slot.lastToolName = null
            slot.activeToolCallIds.clear()
            slot.compacting = false
            slot.retrying = false
            slot.silentPollingTurn = silentPolling
            slot.turnProducedOutputOrCard = false
            if (slot.modelConfig == null) slot.modelConfig = spec.defaultModel
            if (slot.committedMessages.isEmpty()) {
                val baseHistory = slot.handle?.history() ?: preloadedHistory
                if (!baseHistory.isNullOrEmpty()) slot.committedMessages = baseHistory
            }

            val nowMs = clock()
            val runNow = activeExecutingCountLocked() < effectiveMaxConcurrentWorkers()
            val nextStatus = if (runNow) WorkerStatus.RUNNING else WorkerStatus.QUEUED
            val updated = slot.record.copy(
                botName = spec.name,
                botAvatar = spec.avatar,
                deviceName = deviceNameProvider(),
                status = nextStatus,
                turnCount = slot.record.turnCount + 1,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                lastTurnSilent = false,
                lastError = null,
                activeStepText = null,
            )
            slot.record = updated
            writeMetaFile(updated)
            slot.liveMessages += AssistantMessage(
                id = slot.nextUiId.incrementAndGet(),
                role = AssistantRole.USER,
                text = cleanMessage,
                startedAt = nowMs,
            )
            if (runNow) {
                slot.streamingAssistant = AssistantMessage(
                    id = slot.nextUiId.incrementAndGet(),
                    role = AssistantRole.ASSISTANT,
                    text = "",
                    streaming = true,
                    status = MessageStatus.RUNNING,
                    startedAt = nowMs,
                )
            }
            if (!runNow) {
                pendingQueue.addLast(
                    QueuedExecution(
                        workerId = workerId,
                        botId = botId,
                        prompt = cleanMessage,
                        images = images,
                        originCaller = originCaller,
                        silentPolling = silentPolling,
                    ),
                )
            }
            publishSessionSnapshotLocked(slot, loaded = true, error = null)
            updated to runNow
        }

        taskStore.upsertWorker(record)
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, record.status, record.activeStepText))
        if (shouldRunImmediately) {
            launchTurn(workerId, spec, cleanMessage, images, originCaller)
        }
        return record
    }

    suspend fun steer(
        botId: String,
        workerId: String,
        message: String,
        images: List<ImagePart> = emptyList(),
        silentPolling: Boolean = false,
    ): WorkerRunRecord {
        initialize()
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty() || images.isNotEmpty()) { "message must not be empty" }

        val (slot, handle, mode) = poolMutex.withLock {
            val current = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            if (current.botId != botId) throw WorkerNotFoundException(workerId)
            val nowMs = clock()
            if (current.record.status == WorkerStatus.QUEUED) {
                val queuedItem = pendingQueue.firstOrNull { it.workerId == workerId }
                if (queuedItem != null) {
                    queuedItem.prompt = buildString {
                        if (queuedItem.prompt.isNotBlank()) append(queuedItem.prompt)
                        if (cleanMessage.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(cleanMessage)
                        }
                    }
                    if (images.isNotEmpty()) queuedItem.images = queuedItem.images + images
                    current.liveMessages += AssistantMessage(
                        id = current.nextUiId.incrementAndGet(),
                        role = AssistantRole.USER,
                        text = cleanMessage,
                        startedAt = nowMs,
                    )
                    current.record = current.record.copy(updatedAt = nowMs)
                    writeMetaFile(current.record)
                    publishSessionSnapshotLocked(current, loaded = true, error = null)
                    return@withLock Triple(current, null, 0)
                }
            }
            if (current.record.status == WorkerStatus.WAITING_INPUT) {
                throw WorkerConflictException(workerId, "Worker $workerId is waiting for user confirmation")
            }
            if (current.record.status != WorkerStatus.RUNNING &&
                current.record.status != WorkerStatus.STALLED
            ) {
                throw WorkerConflictException(workerId, "Worker $workerId is not currently active")
            }
            current.silentPollingTurn = current.silentPollingTurn && silentPolling
            val existingStreaming = current.streamingAssistant
            if (existingStreaming != null &&
                (existingStreaming.text.isNotBlank() || existingStreaming.steps.isNotEmpty())
            ) {
                current.liveMessages += existingStreaming.copy(
                    streaming = false,
                    status = MessageStatus.DONE,
                    finishedAt = nowMs,
                )
                current.streamingAssistant = null
            }
            current.liveMessages += AssistantMessage(
                id = current.nextUiId.incrementAndGet(),
                role = AssistantRole.USER,
                text = cleanMessage,
                startedAt = nowMs,
            )
            current.streamingAssistant = AssistantMessage(
                id = current.nextUiId.incrementAndGet(),
                role = AssistantRole.ASSISTANT,
                text = "",
                streaming = true,
                status = MessageStatus.RUNNING,
                startedAt = nowMs,
            )
            val preservedStep = current.record.activeStepText.takeIf { current.activeToolCallIds.isNotEmpty() }
            current.record = current.record.copy(
                status = WorkerStatus.RUNNING,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                activeStepText = preservedStep,
            )
            writeMetaFile(current.record)
            publishSessionSnapshotLocked(current, loaded = true, error = null)
            val nextMode = if (current.handle?.isBusy == true) 1 else 2
            Triple(current, current.handle, nextMode)
        }
        taskStore.upsertWorker(slot.record)
        if (mode == 0) {
            return slot.record
        }
        resetStallWatchdog(workerId)
        _progressEvents.tryEmit(
            WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, slot.record.activeStepText),
        )

        if (handle != null && mode == 1) {
            withContext(ioDispatcher) {
                handle.prompt(cleanMessage, images.takeIf { it.isNotEmpty() }, QueueBehavior.Steer)
            }
        } else {
            val spec = resolveBotSpec(botId)
                ?: throw IllegalArgumentException("bot $botId is not available")
            launchTurn(workerId, spec, cleanMessage, images, slot.originCaller)
        }
        return slot.record
    }

    suspend fun stop(botId: String, workerId: String): WorkerRunRecord {
        initialize()
        var handleToAbort: SessionHandle? = null
        var questionToCancel: PendingQuestion? = null
        var turnJobToCancel: Job? = null
        var stoppedOrigin = LOCAL_CALLER

        val updated = poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            if (slot.botId != botId) throw WorkerNotFoundException(workerId)
            val removedQueued = pendingQueue.removeAll { it.workerId == workerId }
            stoppedOrigin = slot.originCaller
            handleToAbort = slot.handle
            questionToCancel = slot.activeQuestion
            turnJobToCancel = slot.turnJob
            slot.watchdogJob?.cancel()
            slot.watchdogJob = null
            slot.questionEscalationJob?.cancel()
            slot.questionEscalationJob = null
            slot.questionEscalatedToSuper = false
            slot.activeToolCallIds.clear()
            slot.pendingFollowUps.clear()
            slot.compacting = false
            slot.retrying = false
            slot.abortedByUser = true
            val nowMs = clock()
            val next = slot.record.copy(
                status = WorkerStatus.IDLE,
                lastOutcome = if (removedQueued || slot.record.status.isBusyForCommands) {
                    WorkerOutcome.ABORTED
                } else {
                    slot.record.lastOutcome
                },
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                activeStepText = null,
                pendingQuestion = null,
                steps = slot.record.steps.map { step ->
                    if (step.status == ToolActivityStatus.RUNNING) {
                        step.copy(status = ToolActivityStatus.FAILED, isError = true, updatedAt = nowMs)
                    } else {
                        step
                    }
                },
            )
            slot.activeQuestion = null
            slot.record = next
            writeMetaFile(next)
            slot.streamingAssistant?.let { partial ->
                if (partial.text.isNotBlank() || partial.steps.isNotEmpty()) {
                    slot.liveMessages += partial.copy(
                        streaming = false,
                        status = MessageStatus.STOPPED,
                        finishedAt = nowMs,
                    )
                }
            }
            slot.streamingAssistant = null
            publishSessionSnapshotLocked(slot, error = null)
            next
        }

        val sessionIdToClean = handleToAbort?.sessionId
        questionToCancel?.let { approvalCenter.cancel(it.requestId) }
        toolApprovalCenter.cancelForWorker(workerId)
        handleToAbort?.abort()
        turnJobToCancel?.cancel()
        if (!sessionIdToClean.isNullOrBlank()) {
            onWorkerSessionCleanup(sessionIdToClean)
        }

        emitWire(workerId, botId, buildJsonObject {
            put("type", "agent_end")
            put("status", "aborted")
        }, terminal = false)
        emitWire(workerId, botId, buildJsonObject { put("type", "agent_settled") }, terminal = true)

        taskStore.upsertWorker(updated)
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.IDLE, null))
        _milestones.tryEmit(
            LocalWorkerMilestoneEvent(
                originCaller = stoppedOrigin,
                milestone = WorkerMilestone.Aborted(
                    workerId = workerId,
                    botRef = updated.botRef,
                    botName = updated.botName,
                    timestamp = updated.updatedAt,
                ),
            ),
        )
        scheduleIdleMaintenance(workerId, botId)
        drainPendingQueue()
        return updated
    }

    suspend fun delete(botId: String, workerId: String) {
        initialize()
        val slot = poolMutex.withLock {
            val current = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            if (current.botId != botId) throw WorkerNotFoundException(workerId)
            if (current.record.status.isBusyForCommands || current.maintenanceInFlight) {
                throw WorkerConflictException(workerId, "Cannot delete active worker $workerId (${current.record.status.name})")
            }
            current.idleEvictJob?.cancel()
            current.idleEvictJob = null
            current.watchdogJob?.cancel()
            current.watchdogJob = null
            current.questionEscalationJob?.cancel()
            current.questionEscalationJob = null
            current.pendingSnapshotJob?.cancel()
            current.pendingSnapshotJob = null
            current.questionEscalatedToSuper = false
            current.activeToolCallIds.clear()
            current.pendingFollowUps.clear()
            slots.remove(workerId)
            current
        }

        slot.handle?.sessionId?.let { sid ->
            toolApprovalCenter.cancelForSession(sid)
            toolApprovalCenter.clearSessionGrants(sid)
            onWorkerSessionCleanup(sid)
        }
        toolApprovalCenter.cancelForWorker(workerId)
        try {
            onWorkerDeleted(workerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("LocalWorkerPool", "Failed to clean up resources for deleted worker $workerId", error)
        }
        detachAndCloseHandle(slot)
        sessionFlows.remove(workerId)
        taskStore.deleteWorker(workerId)
        withContext(ioDispatcher) {
            workerDir(botId, workerId).deleteRecursively()
        }
    }

    internal fun cleanupStagedWorkerDir(botId: String, workerId: String) {
        if (workerId in slots) return
        workerDir(botId, workerId).deleteRecursively()
    }

    fun observeSession(workerId: String): StateFlow<WorkerSessionSnapshot> =
        sessionFlowFor(workerId).asStateFlow()

    fun workerWorkspaceDirFor(workerId: String): File? {
        val botId = slots[workerId]?.botId
            ?: taskStore.currentWorker(workerId)?.takeIf { !it.isRemote }?.botId
            ?: return null
        return workerWorkspaceDir(botId, workerId)
    }

    suspend fun stageInputFile(botId: String, workerId: String, source: File): File? = withContext(ioDispatcher) {
        if (!source.isFile || !source.canRead()) return@withContext null
        val workspace = workerWorkspaceDir(botId, workerId).apply { mkdirs() }
        val canonicalWorkspace = runCatching { workspace.canonicalPath }.getOrNull()
        val canonicalSource = runCatching { source.canonicalPath }.getOrNull()
        if (canonicalWorkspace != null && canonicalSource != null && canonicalSource.startsWith(canonicalWorkspace + File.separator)) {
            return@withContext source
        }
        val inputsDir = File(workspace, "inputs").apply { mkdirs() }
        var target = File(inputsDir, sanitizeFileName(source.name))
        var suffix = 1
        while (target.exists() && target.length() != source.length()) {
            val base = source.nameWithoutExtension.ifBlank { "file" }.take(40)
            val ext = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            target = File(inputsDir, "${sanitizeFileName(base)}_${suffix++}$ext")
        }
        runCatching {
            if (!target.exists() || target.length() != source.length()) {
                source.copyTo(target, overwrite = true)
            }
            target
        }.getOrNull()
    }

    suspend fun sessionHistory(workerId: String): List<AssistantMessage> {
        initialize()
        val (botId, activeHandle) = poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            slot.botId to slot.handle
        }
        if (activeHandle != null) {
            return runCatching { activeHandle.history() }.getOrDefault(emptyList())
        }
        val entries = withContext(ioDispatcher) {
            loadSessionEntries(workerSessionFile(botId, workerId), workerWorkspaceDir(botId, workerId))
        }
        return if (entries.isNullOrEmpty()) emptyList()
        else HistoryMapper.mapEntries(entries, settleUnfinishedTools = true)
    }

    suspend fun loadSession(workerId: String) {
        initialize()
        val (botId, existingConfig, activeHandle) = poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            Triple(slot.botId, slot.modelConfig, slot.handle)
        }
        val spec = resolveBotSpec(botId)
        val modelConfig = activeHandle?.let {
            ModelConfig(it.model.provider, it.model.id, thinkingLevelOf(it.thinkingLevel))
        } ?: existingConfig ?: spec?.defaultModel ?: ModelConfig()
        val usage = runCatching { activeHandle?.usage() }.getOrNull()
        val history = sessionHistory(workerId)
        poolMutex.withLock {
            val slot = slots[workerId] ?: return@withLock
            slot.modelConfig = modelConfig
            if (usage != null) slot.cachedUsage = usage
            if (history.isNotEmpty() || (!slot.record.status.isBusyForCommands && slot.liveMessages.isEmpty())) {
                if (!slot.record.status.isBusyForCommands || (slot.committedMessages.isEmpty() && slot.liveMessages.isEmpty())) {
                    slot.committedMessages = history
                }
            }
            publishSessionSnapshotLocked(slot, loaded = true, error = slot.record.lastError)
        }
    }

    suspend fun compact(workerId: String): WorkerRunRecord {
        initialize()
        return withWorkerMaintenance(workerId) { slot, handle ->
            withContext(ioDispatcher) {
                handle.compact()
            }
            val committed = try {
                handle.history()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to read compacted history for $workerId", error)
                null
            }
            val updatedUsage = try {
                handle.usage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to read compacted usage for $workerId", error)
                null
            }
            val nowMs = clock()
            val updated = poolMutex.withLock {
                if (committed != null) {
                    slot.committedMessages = committed
                    slot.liveMessages.clear()
                }
                if (updatedUsage != null) {
                    slot.cachedUsage = updatedUsage
                }
                slot.record = slot.record.copy(updatedAt = nowMs)
                writeMetaFile(slot.record)
                publishSessionSnapshotLocked(slot, loaded = true, error = null)
                slot.record
            }
            taskStore.upsertWorker(updated)
            updated
        }
    }

    suspend fun dismissError(workerId: String) {
        val updated = poolMutex.withLock {
            val slot = slots[workerId] ?: return
            if (slot.record.lastError == null && sessionFlowFor(workerId).value.error == null) return
            slot.record = slot.record.copy(lastError = null)
            writeMetaFile(slot.record)
            publishSessionSnapshotLocked(slot, error = null)
            slot.record
        }
        taskStore.upsertWorker(updated)
    }

    suspend fun postCustomMessage(
        sdkSessionId: String,
        customType: String,
        payloadJson: String,
        text: String,
        display: Boolean = true,
    ): Boolean {
        val workerId = sessionToWorker[sdkSessionId] ?: poolMutex.withLock {
            slots.values.firstOrNull { it.handle?.sessionId == sdkSessionId }?.workerId
        } ?: return false
        val handle = poolMutex.withLock { slots[workerId]?.handle } ?: return false
        val appended = try {
            handle.appendCustomMessage(customType, payloadJson, text, display)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("LocalWorkerPool", "Failed to append custom message for $workerId", error)
            false
        }
        if (!appended) return false
        val nowMs = clock()
        val botId = poolMutex.withLock {
            val current = slots[workerId] ?: return false
            val assistant = ensureStreamingAssistantLocked(current, nowMs)
            val cardStep = AssistantStep.CustomCard(
                id = current.nextUiId.incrementAndGet(),
                customType = customType,
                payload = payloadJson,
                text = text,
            )
            current.streamingAssistant = assistant.copy(steps = assistant.steps + cardStep)
            publishSessionSnapshotLocked(current)
            current.botId
        }
        emitWire(
            workerId,
            botId,
            buildJsonObject {
                put("type", "plugin_custom_message")
                put("customType", customType)
                put("payload", payloadJson)
                put("text", text)
                put("display", display)
            },
            terminal = false,
        )
        return true
    }

    suspend fun deliverTimerReminder(workerId: String, prompt: String, reminderName: String? = null): Boolean {
        initialize()
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return false
        val wireText = when {
            cleanPrompt.startsWith("[Reminder]") -> cleanPrompt
            reminderName.isNullOrBlank() -> "[Reminder] $cleanPrompt"
            else -> "[Reminder] $reminderName: $cleanPrompt"
        }
        return deliverFollowUpToWorker(workerId, wireText, silentPolling = true)
    }

    suspend fun deliverJobCompletion(sdkSessionId: String, summaryText: String) {
        if (!initialized.get()) return
        val cleanSummary = summaryText.trim()
        if (cleanSummary.isEmpty()) return
        val workerId = sessionToWorker[sdkSessionId] ?: poolMutex.withLock {
            slots.values.firstOrNull { it.handle?.sessionId == sdkSessionId }?.workerId
        } ?: return
        deliverFollowUpToWorker(workerId, cleanSummary, silentPolling = false)
    }

    private suspend fun deliverFollowUpToWorker(
        workerId: String,
        message: String,
        silentPolling: Boolean,
    ): Boolean {
        repeat(2) { attempt ->
            val target = poolMutex.withLock {
                val slot = slots[workerId] ?: return false
                if (slot.record.status == WorkerStatus.WAITING_INPUT) {
                    while (slot.pendingFollowUps.size >= MAX_PENDING_FOLLOW_UPS) {
                        slot.pendingFollowUps.removeFirst()
                    }
                    slot.pendingFollowUps.addLast(PendingWorkerFollowUp(message, silentPolling))
                    return true
                }
                Triple(slot.botId, slot.record.status, slot.originCaller)
            }
            val (botId, status, originCaller) = target
            try {
                if (status.isBusyForCommands) {
                    steer(botId, workerId, message, silentPolling = silentPolling)
                } else {
                    continueWorker(
                        botId = botId,
                        workerId = workerId,
                        message = message,
                        originCaller = originCaller,
                        silentPolling = silentPolling,
                    )
                }
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (conflict: WorkerConflictException) {
                if (attempt == 1) {
                    logWarn("LocalWorkerPool", "Failed to deliver follow-up to $workerId", conflict)
                    return false
                }
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to deliver follow-up to $workerId", error)
                return false
            }
        }
        return false
    }

    suspend fun changeModel(
        workerId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        initialize()
        val resolved = providers?.resolveConfig(ModelConfig(providerId, modelId, thinkingLevel))
            ?: ModelConfig(providerId, modelId, thinkingLevel)
        val sdkModel = providers?.sdkModel(resolved.providerId, resolved.modelId)
            ?: error("Model not found: ${resolved.providerId}/${resolved.modelId}")
        withWorkerMaintenance(workerId) { slot, handle ->
            withContext(ioDispatcher) {
                handle.setModel(sdkModel, resolved.thinkingLevel.runtimeValue())
            }
            poolMutex.withLock {
                slot.modelConfig = ModelConfig(handle.model.provider, handle.model.id, thinkingLevelOf(handle.thinkingLevel))
                publishSessionSnapshotLocked(slot, loaded = true, error = null)
            }
        }
    }

    suspend fun changeThinking(workerId: String, thinkingLevel: ThinkingLevel) {
        initialize()
        withWorkerMaintenance(workerId) { slot, handle ->
            withContext(ioDispatcher) {
                handle.setThinkingLevel(thinkingLevel.runtimeValue())
            }
            poolMutex.withLock {
                slot.modelConfig = ModelConfig(handle.model.provider, handle.model.id, thinkingLevelOf(handle.thinkingLevel))
                publishSessionSnapshotLocked(slot, loaded = true, error = null)
            }
        }
    }

    private suspend fun <T> withWorkerMaintenance(
        workerId: String,
        block: suspend (WorkerSlotRuntime, SessionHandle) -> T,
    ): T {
        val (slot, botId) = poolMutex.withLock {
            val current = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            if (current.record.status.isBusyForCommands || current.maintenanceInFlight) {
                throw WorkerConflictException(workerId, "Worker $workerId is busy")
            }
            current.maintenanceInFlight = true
            current to current.botId
        }
        try {
            val spec = resolveBotSpec(botId) ?: throw IllegalArgumentException("bot $botId is not available")
            val handle = ensureHandle(slot, spec)
            return block(slot, handle)
        } finally {
            withContext(NonCancellable) {
                poolMutex.withLock {
                    slot.maintenanceInFlight = false
                    if (!slot.record.status.isBusyForCommands && slots[workerId] === slot) {
                        scheduleIdleMaintenance(workerId, botId)
                    }
                }
            }
        }
    }

    suspend fun answerQuestion(
        workerId: String,
        requestId: String,
        answers: List<QuestionAnswer>,
        bySuper: Boolean = false,
    ) {
        initialize()
        prepareQuestionAction(workerId, requestId, bySuper, "answer")
        approvalCenter.answer(requestId, answers)
    }

    suspend fun dismissQuestion(
        workerId: String,
        requestId: String,
        bySuper: Boolean = false,
    ) {
        initialize()
        prepareQuestionAction(workerId, requestId, bySuper, "dismiss")
        approvalCenter.cancel(requestId)
    }

    private suspend fun prepareQuestionAction(
        workerId: String,
        requestId: String,
        bySuper: Boolean,
        actionVerb: String,
    ) {
        val nowMs = clock()
        poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            val sessionId = slot.handle?.sessionId
            val hasQuestion = slot.activeQuestion?.requestId == requestId ||
                approvalCenter.pending.value.any {
                    it.requestId == requestId && sessionId != null && it.sessionId == sessionId
                }
            if (!hasQuestion) {
                throw WorkerConflictException(workerId, "Pending approval $requestId is not active on worker $workerId")
            }
            if (bySuper && !slot.questionEscalatedToSuper) {
                throw WorkerConflictException(
                    workerId,
                    "Approval $requestId on worker $workerId is awaiting user decision; Super can $actionVerb only after timeout",
                )
            }
            slot.questionEscalationJob?.cancel()
            slot.questionEscalationJob = null
            slot.questionEscalatedToSuper = false
            slot.lastProgressAtMs = nowMs
        }
    }

    suspend fun inspect(workerId: String, tail: Int = 30): WorkerInspectResult {
        initialize()
        val boundedTail = tail.coerceIn(1, 200)
        val (record, sdkSessionId, usage, escalated) = poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            val liveUsage = slot.cachedUsage
            InspectState(slot.record, slot.handle?.sessionId, liveUsage, slot.questionEscalatedToSuper)
        }
        val effectiveUsage = usage ?: poolMutex.withLock {
            val handle = slots[workerId]?.handle ?: return@withLock null
            try {
                handle.usage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to read usage for $workerId", error)
                null
            }
        }
        val extras = try {
            inspectExtrasProvider(workerId, sdkSessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("LocalWorkerPool", "Failed to load inspect extras for $workerId", error)
            WorkerInspectExtras()
        }
        return WorkerInspectResult(
            record = record,
            recentSteps = record.steps.takeLast(boundedTail),
            usage = effectiveUsage,
            activeTimers = extras.activeTimers,
            runningJobs = extras.runningJobs,
            questionEscalatedToSuper = escalated,
        )
    }

    private data class InspectState(
        val record: WorkerRunRecord,
        val sdkSessionId: String?,
        val usage: SessionUsage?,
        val escalated: Boolean,
    )

    suspend fun replay(workerId: String, afterSeq: Long? = null): WorkerReplaySnapshot {
        initialize()
        return poolMutex.withLock {
            val slot = slots[workerId] ?: throw WorkerNotFoundException(workerId)
            val currentSeq = slot.seq.get()
            val seqReset = afterSeq != null && afterSeq > currentSeq
            val effectiveAfterSeq = if (seqReset) 0L else afterSeq
            val filtered = if (effectiveAfterSeq == null) {
                slot.buffer.toList()
            } else {
                slot.buffer.filter { it.seq > effectiveAfterSeq }
            }
            val bufferHeadSeq = slot.buffer.firstOrNull()?.seq ?: currentSeq
            WorkerReplaySnapshot(
                record = slot.record,
                seq = currentSeq,
                evictedBefore = effectiveAfterSeq != null &&
                    ((slot.evictedBefore && bufferHeadSeq > effectiveAfterSeq + 1) || seqReset),
                events = filtered,
            )
        }
    }

    private suspend fun launchTurn(
        workerId: String,
        spec: LocalWorkerBotSpec,
        prompt: String,
        images: List<ImagePart>,
        originCaller: String,
    ) {
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val slot = poolMutex.withLock { slots[workerId] } ?: return@launch
            try {
                val effectiveSpec = poolMutex.withLock {
                    slot.modelConfig?.let { spec.copy(defaultModel = it) } ?: spec
                }
                val handle = ensureHandle(slot, effectiveSpec)
                val turnStartEvent = buildJsonObject {
                    put("type", "agent_start")
                    put("sessionId", workerId)
                    put("title", slot.record.title)
                }
                emitWire(workerId, spec.botId, turnStartEvent, terminal = false)
                resetStallWatchdog(workerId)

                handle.prompt(prompt, images.takeIf { it.isNotEmpty() }, null)

                val aborted = poolMutex.withLock { slot.abortedByUser }
                if (!aborted) {
                    finalizeTurnSuccess(slot, handle)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val aborted = poolMutex.withLock { slot.abortedByUser }
                    if (!aborted) {
                        finalizeTurnFailure(slot, "Cancelled")
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                val aborted = poolMutex.withLock { slot.abortedByUser }
                if (!aborted) {
                    finalizeTurnFailure(slot, error.message ?: "Worker execution failed")
                }
            } finally {
                withContext(NonCancellable) {
                    drainPendingQueue()
                }
            }
        }
        poolMutex.withLock {
            slots[workerId]?.turnJob = job
        }
        job.start()
    }

    private suspend fun ensureHandle(slot: WorkerSlotRuntime, spec: LocalWorkerBotSpec): SessionHandle {
        poolMutex.withLock {
            slot.handle?.let { return it }
        }
        val workspaceDir = workerWorkspaceDir(spec.botId, slot.workerId)
        val sessionFile = workerSessionFile(spec.botId, slot.workerId)
        workspaceDir.mkdirs()
        val opened = sessionFactory.open(
            spec = spec,
            workerId = slot.workerId,
            workerWorkspaceDir = workspaceDir,
            sessionFile = sessionFile,
            approvalCenter = approvalCenter,
        )
        val unsubscribe = opened.subscribe { event ->
            onSessionEvent(slot.workerId, spec.botId, opened, event)
        }
        val (rejected, chosen) = poolMutex.withLock {
            val activeSlot = slots[slot.workerId]
            val current = slot.handle
            if (activeSlot !== slot || current != null) {
                true to current
            } else {
                sessionToWorker[opened.sessionId] = slot.workerId
                slot.handle = opened
                slot.unsubscribe = unsubscribe
                slot.modelConfig = ModelConfig(opened.model.provider, opened.model.id, thinkingLevelOf(opened.thinkingLevel))
                slot.cachedUsage = readUsageSafely(opened, slot.workerId) ?: slot.cachedUsage
                publishSessionSnapshotLocked(slot)
                false to opened
            }
        }
        if (rejected) {
            unsubscribe()
            sessionToWorker.remove(opened.sessionId, slot.workerId)
            try {
                opened.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to close superseded session handle for ${slot.workerId}", error)
            }
            return chosen ?: throw WorkerNotFoundException(slot.workerId)
        }
        return opened
    }

    private suspend fun onSessionEvent(
        workerId: String,
        botId: String,
        handle: SessionHandle,
        event: SessionEvent,
    ) {
        when (event) {
            is SessionEvent.CompactionStarted -> {
                val nowMs = clock()
                val recovered = poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.compacting = true
                    current.lastProgressAtMs = nowMs
                    recoverStalledSlotLocked(current, nowMs)
                }
                emitWire(workerId, botId, buildJsonObject { put("type", "compaction_start") }, terminal = false)
                if (recovered != null) {
                    taskStore.upsertWorker(recovered.record)
                    _progressEvents.tryEmit(
                        WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, recovered.record.activeStepText),
                    )
                }
                return
            }

            is SessionEvent.CompactionFinished -> {
                val nowMs = clock()
                val committed = readHistorySafely(handle, workerId)
                val updatedUsage = readUsageSafely(handle, workerId)
                val recovered = poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.compacting = false
                    current.lastProgressAtMs = nowMs
                    if (committed != null) current.committedMessages = committed
                    if (updatedUsage != null) current.cachedUsage = updatedUsage
                    val rec = recoverStalledSlotLocked(current, nowMs)
                    publishSessionSnapshotLocked(current, loaded = true)
                    rec
                }
                emitWire(workerId, botId, buildJsonObject { put("type", "compaction_end") }, terminal = false)
                if (recovered != null) {
                    taskStore.upsertWorker(recovered.record)
                    _progressEvents.tryEmit(
                        WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, recovered.record.activeStepText),
                    )
                }
                return
            }

            is SessionEvent.CompactionFailed -> {
                val nowMs = clock()
                poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.compacting = false
                    current.lastProgressAtMs = nowMs
                }
                emitWire(workerId, botId, buildJsonObject { put("type", "compaction_end") }, terminal = false)
                return
            }

            is SessionEvent.RetryScheduled -> {
                val nowMs = clock()
                val recovered = poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.retrying = true
                    current.lastProgressAtMs = nowMs
                    recoverStalledSlotLocked(current, nowMs)
                }
                emitWire(workerId, botId, buildJsonObject { put("type", "retry_start") }, terminal = false)
                if (recovered != null) {
                    taskStore.upsertWorker(recovered.record)
                    _progressEvents.tryEmit(
                        WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, recovered.record.activeStepText),
                    )
                }
                return
            }

            is SessionEvent.RetrySucceeded, is SessionEvent.RetryExhausted -> {
                val nowMs = clock()
                poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.retrying = false
                    current.lastProgressAtMs = nowMs
                }
                emitWire(workerId, botId, buildJsonObject { put("type", "retry_end") }, terminal = false)
                return
            }

            is SessionEvent.AgentEventReceived -> Unit
            else -> return
        }
        val agentEvent = event.event
        val nowMs = clock()
        var recoveredFromStall = false
        val slot = poolMutex.withLock {
            val current = slots[workerId] ?: return
            current.lastProgressAtMs = nowMs
            if (current.record.status == WorkerStatus.STALLED) {
                recoveredFromStall = true
                current.record = current.record.copy(
                    status = WorkerStatus.RUNNING,
                    lastProgressAt = nowMs,
                    updatedAt = nowMs,
                )
            } else {
                current.record = current.record.copy(lastProgressAt = nowMs)
            }
            current
        }
        if (recoveredFromStall) {
            taskStore.upsertWorker(slot.record)
            resetStallWatchdog(workerId)
            _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, slot.record.activeStepText))
        }

        when (agentEvent) {
            is AgentEvent.MessageStart -> {
                if (agentEvent.message.asLlmMessage() is UserMessage) {
                    poolMutex.withLock {
                        val current = slots[workerId] ?: return@withLock
                        val partial = current.streamingAssistant
                        if (partial != null && (partial.text.isNotBlank() || partial.steps.isNotEmpty())) {
                            current.liveMessages += partial.copy(
                                streaming = false,
                                status = MessageStatus.DONE,
                                finishedAt = nowMs,
                            )
                            current.streamingAssistant = null
                            publishSessionSnapshotLocked(current)
                        }
                    }
                }
            }

            is AgentEvent.MessageUpdate -> when (val update = agentEvent.assistantMessageEvent) {
                is TextDeltaEvent -> {
                    synchronized(slot.textBuffer) {
                        slot.textBuffer.append(update.delta)
                    }
                    poolMutex.withLock {
                        val current = slots[workerId] ?: return@withLock
                        val assistant = ensureStreamingAssistantLocked(current, nowMs)
                        val steps = assistant.steps.toMutableList()
                        val last = steps.lastOrNull()
                        if (last is AssistantStep.Text) {
                            steps[steps.lastIndex] = last.copy(text = last.text + update.delta)
                        } else {
                            steps += AssistantStep.Text(current.nextUiId.incrementAndGet(), update.delta)
                        }
                        current.streamingAssistant = assistant.copy(
                            text = assistant.text + update.delta,
                            steps = steps,
                        )
                        scheduleStreamingSnapshotPublishLocked(current)
                    }
                    emitWire(
                        workerId,
                        botId,
                        buildJsonObject {
                            put("type", "message_update")
                            put(
                                "assistantMessageEvent",
                                buildJsonObject {
                                    put("type", "text_delta")
                                    put("delta", update.delta)
                                },
                            )
                        },
                        terminal = false,
                    )
                }

                is ThinkingDeltaEvent -> {
                    poolMutex.withLock {
                        val current = slots[workerId] ?: return@withLock
                        val assistant = ensureStreamingAssistantLocked(current, nowMs)
                        val steps = assistant.steps.toMutableList()
                        val last = steps.lastOrNull()
                        if (last is AssistantStep.Reasoning) {
                            steps[steps.lastIndex] = last.copy(text = last.text + update.delta)
                        } else {
                            steps += AssistantStep.Reasoning(current.nextUiId.incrementAndGet(), update.delta)
                        }
                        current.streamingAssistant = assistant.copy(steps = steps)
                        scheduleStreamingSnapshotPublishLocked(current)
                    }
                    emitWire(
                        workerId,
                        botId,
                        buildJsonObject {
                            put("type", "message_update")
                            put(
                                "assistantMessageEvent",
                                buildJsonObject {
                                    put("type", "reasoning_delta")
                                    put("delta", update.delta)
                                },
                            )
                        },
                        terminal = false,
                    )
                }

                is ToolCallStartEvent -> {
                    val call = update.partial.content.getOrNull(update.contentIndex) as? ToolCall
                    if (call != null && call.id.isNotBlank() && call.name.isNotBlank()) {
                        poolMutex.withLock {
                            val current = slots[workerId] ?: return@withLock
                            val assistant = ensureStreamingAssistantLocked(current, nowMs)
                            if (assistant.steps.none { it is AssistantStep.Tool && it.trace.toolCallId == call.id }) {
                                val step = AssistantStep.Tool(
                                    ToolTrace(
                                        id = current.nextUiId.incrementAndGet(),
                                        name = call.name,
                                        arguments = emptyMap(),
                                        toolCallId = call.id,
                                        startedAt = nowMs,
                                    ),
                                )
                                current.streamingAssistant = assistant.copy(steps = assistant.steps + step)
                                publishSessionSnapshotLocked(current)
                            }
                        }
                        emitWire(
                            workerId,
                            botId,
                            buildJsonObject {
                                put("type", "tool_call")
                                put("callId", call.id)
                                put("name", call.name)
                            },
                            terminal = false,
                        )
                    }
                }

                else -> Unit
            }

            is AgentEvent.ToolExecutionStart -> {
                val argsText = agentEvent.args.toString()
                val displayArgs = agentEvent.args.toDisplayArguments()
                val snapshot = WorkerStepSnapshot(
                    callId = agentEvent.toolCallId,
                    toolName = agentEvent.toolName,
                    argsJson = argsText,
                    status = ToolActivityStatus.RUNNING,
                    updatedAt = nowMs,
                )
                val updated = poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.activeToolCallIds += agentEvent.toolCallId
                    val steps = (current.record.steps.filterNot { it.callId == snapshot.callId } + snapshot)
                        .takeLast(MAX_STORED_STEPS)
                    val stepText = formatStepText(agentEvent.toolName, displayArgs)
                    current.lastToolName = agentEvent.toolName
                    current.record = current.record.copy(
                        activeStepText = stepText,
                        steps = steps,
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                    )
                    writeMetaFile(current.record)
                    val assistant = ensureStreamingAssistantLocked(current, nowMs)
                    val uiSteps = assistant.steps.toMutableList()
                    val existingIdx = uiSteps.indexOfLast { it is AssistantStep.Tool && it.trace.toolCallId == agentEvent.toolCallId }
                    if (existingIdx >= 0) {
                        val existingTrace = (uiSteps[existingIdx] as AssistantStep.Tool).trace
                        uiSteps[existingIdx] = AssistantStep.Tool(
                            existingTrace.copy(
                                name = agentEvent.toolName.ifBlank { existingTrace.name },
                                arguments = displayArgs.ifEmpty { existingTrace.arguments },
                                rawArguments = agentEvent.args,
                                startedAt = existingTrace.startedAt.takeIf { it > 0L } ?: nowMs,
                            ),
                        )
                    } else {
                        uiSteps += AssistantStep.Tool(
                            ToolTrace(
                                id = current.nextUiId.incrementAndGet(),
                                name = agentEvent.toolName,
                                arguments = displayArgs,
                                rawArguments = agentEvent.args,
                                toolCallId = agentEvent.toolCallId,
                                startedAt = nowMs,
                            ),
                        )
                    }
                    current.streamingAssistant = assistant.copy(steps = uiSteps)
                    publishSessionSnapshotLocked(current)
                    current.record
                }
                taskStore.upsertWorker(updated)
                _progressEvents.tryEmit(WorkerProgressEvent.StepUpdated(workerId, snapshot))
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, updated.status, updated.activeStepText))
                emitWire(
                    workerId,
                    botId,
                    buildJsonObject {
                        put("type", "tool_call")
                        put("callId", agentEvent.toolCallId)
                        put("name", agentEvent.toolName)
                        put("args", agentEvent.args)
                    },
                    terminal = false,
                )
            }

            is AgentEvent.ToolExecutionEnd -> {
                val payload = agentEvent.result.toToolResultPayload()
                val rawText = payload.text()
                val details = agentEvent.result.details as? JsonObject
                val harvestedArtifacts = if (agentEvent.toolName == "present" && !agentEvent.isError && rawText.isNotBlank()) {
                    stagePresentedArtifacts(botId, workerId, rawText, nowMs)
                } else {
                    emptyList()
                }
                val harvestedCard = if (agentEvent.toolName != "present" && !agentEvent.isError) {
                    val prevArgs = poolMutex.withLock {
                        slots[workerId]?.record?.steps?.firstOrNull { it.callId == agentEvent.toolCallId }?.argsJson
                    } ?: "{}"
                    harvestRichCard(slot, agentEvent, prevArgs, rawText, details, nowMs)
                } else {
                    null
                }
                if (harvestedCard != null) {
                    taskStore.upsertCard(harvestedCard)
                }

                var matchedStep: WorkerStepSnapshot? = null
                val updated = poolMutex.withLock {
                    val current = slots[workerId] ?: return
                    current.activeToolCallIds -= agentEvent.toolCallId
                    if (harvestedArtifacts.isNotEmpty() || harvestedCard != null) {
                        current.turnProducedOutputOrCard = true
                    }
                    val prev = current.record.steps.firstOrNull { it.callId == agentEvent.toolCallId }
                    val updatedStep = WorkerStepSnapshot(
                        callId = agentEvent.toolCallId,
                        toolName = agentEvent.toolName,
                        argsJson = prev?.argsJson ?: "{}",
                        status = if (agentEvent.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE,
                        outputPreview = rawText.take(MAX_STEP_OUTPUT_PREVIEW),
                        isError = agentEvent.isError,
                        updatedAt = nowMs,
                    )
                    matchedStep = updatedStep
                    val nextSteps = (current.record.steps.filterNot { it.callId == updatedStep.callId } + updatedStep)
                        .takeLast(MAX_STORED_STEPS)
                    val nextArtifacts = (current.record.artifacts + harvestedArtifacts)
                        .distinctBy { it.artifactId }
                    val nextCards = if (harvestedCard != null) {
                        (current.record.cards.filterNot { it.cardId == harvestedCard.cardId } + harvestedCard)
                    } else {
                        current.record.cards
                    }
                    current.record = current.record.copy(
                        steps = nextSteps,
                        artifacts = nextArtifacts,
                        cards = nextCards,
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                    )
                    writeMetaFile(current.record)
                    val assistant = ensureStreamingAssistantLocked(current, nowMs)
                    val uiSteps = assistant.steps.toMutableList()
                    val uiStatus = if (agentEvent.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE
                    val existingIdx = uiSteps.indexOfLast { it is AssistantStep.Tool && it.trace.toolCallId == agentEvent.toolCallId }
                    if (existingIdx >= 0) {
                        val prevTrace = (uiSteps[existingIdx] as AssistantStep.Tool).trace
                        val duration = (nowMs - prevTrace.startedAt).coerceAtLeast(0L)
                        uiSteps[existingIdx] = AssistantStep.Tool(
                            prevTrace.copy(
                                status = uiStatus,
                                output = rawText.takeIf { !agentEvent.isError && it.isNotBlank() },
                                error = rawText.takeIf { agentEvent.isError && it.isNotBlank() },
                                resultPayload = payload,
                                resultDetails = details,
                                durationMs = duration,
                            ),
                        )
                    } else {
                        uiSteps += AssistantStep.Tool(
                            ToolTrace(
                                id = current.nextUiId.incrementAndGet(),
                                name = agentEvent.toolName,
                                arguments = emptyMap(),
                                status = uiStatus,
                                output = rawText.takeIf { !agentEvent.isError && it.isNotBlank() },
                                error = rawText.takeIf { agentEvent.isError && it.isNotBlank() },
                                toolCallId = agentEvent.toolCallId,
                                resultPayload = payload,
                                resultDetails = details,
                            ),
                        )
                    }
                    val remainingActive = uiSteps.filterIsInstance<AssistantStep.Tool>()
                        .lastOrNull { it.trace.status == ToolActivityStatus.RUNNING }
                        ?.trace
                        ?.name
                    current.streamingAssistant = assistant.copy(steps = uiSteps)
                    current.record = current.record.copy(activeStepText = remainingActive)
                    publishSessionSnapshotLocked(current)
                    current.record
                }
                taskStore.upsertWorker(updated)
                matchedStep?.let { _progressEvents.tryEmit(WorkerProgressEvent.StepUpdated(workerId, it)) }
                emitWire(
                    workerId,
                    botId,
                    buildJsonObject {
                        put("type", "tool_result")
                        put("callId", agentEvent.toolCallId)
                        put("name", agentEvent.toolName)
                        put("result", rawText)
                        put("isError", agentEvent.isError)
                        details?.let { put("details", it) }
                    },
                    terminal = false,
                )
            }

            else -> Unit
        }
    }

    private suspend fun finalizeTurnSuccess(slot: WorkerSlotRuntime, handle: SessionHandle) {
        toolApprovalCenter.cancelForWorker(slot.workerId)
        val nowMs = clock()
        val finalText = handle.lastAssistantText()
            ?.takeIf { it.isNotBlank() }
            ?: synchronized(slot.textBuffer) { slot.textBuffer.toString().trim() }
        val committed = readHistorySafely(handle, slot.workerId)
        val updatedUsage = readUsageSafely(handle, slot.workerId)
        val (updated, suppressMilestone, queuedFollowUps) = poolMutex.withLock {
            slot.watchdogJob?.cancel()
            slot.watchdogJob = null
            slot.questionEscalationJob?.cancel()
            slot.questionEscalationJob = null
            slot.questionEscalatedToSuper = false
            slot.activeToolCallIds.clear()
            val queued = if (slot.pendingFollowUps.isNotEmpty()) {
                slot.pendingFollowUps.toList().also { slot.pendingFollowUps.clear() }
            } else {
                emptyList()
            }
            slot.compacting = false
            slot.retrying = false
            if (updatedUsage != null) slot.cachedUsage = updatedUsage
            val suppress = slot.silentPollingTurn &&
                !slot.turnProducedOutputOrCard &&
                isSilentNoUpdateReply(finalText)
            slot.silentPollingTurn = false
            slot.turnProducedOutputOrCard = false
            val next = slot.record.copy(
                status = WorkerStatus.IDLE,
                lastOutcome = WorkerOutcome.SUCCEEDED,
                lastSummary = if (suppress) {
                    slot.record.lastSummary
                } else {
                    finalText.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_CHARS)
                },
                lastTurnSilent = suppress,
                lastError = null,
                activeStepText = null,
                pendingQuestion = null,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
            )
            slot.record = next
            writeMetaFile(next)
            if (committed != null) {
                slot.committedMessages = committed
                slot.liveMessages.clear()
                slot.streamingAssistant = null
            } else {
                slot.streamingAssistant?.let { partial ->
                    if (partial.text.isNotBlank() || partial.steps.isNotEmpty()) {
                        slot.liveMessages += partial.copy(
                            streaming = false,
                            status = MessageStatus.DONE,
                            finishedAt = nowMs,
                        )
                    }
                }
                slot.streamingAssistant = null
            }
            publishSessionSnapshotLocked(slot, loaded = true, error = null)
            Triple(next, suppress, queued)
        }
        taskStore.upsertWorker(updated)
        emitWire(
            slot.workerId,
            slot.botId,
            buildJsonObject {
                put("type", "agent_end")
                put("status", "done")
            },
            terminal = false,
        )
        emitWire(slot.workerId, slot.botId, buildJsonObject { put("type", "agent_settled") }, terminal = true)
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(slot.workerId, WorkerStatus.IDLE, null))
        if (!suppressMilestone) {
            _milestones.tryEmit(
                LocalWorkerMilestoneEvent(
                    originCaller = slot.originCaller,
                    milestone = WorkerMilestone.Completed(
                        workerId = slot.workerId,
                        botRef = updated.botRef,
                        botName = updated.botName,
                        summary = finalText.ifBlank { "Completed." },
                        artifacts = updated.artifacts,
                        cards = updated.cards,
                        timestamp = nowMs,
                    ),
                ),
            )
        }
        scheduleIdleMaintenance(slot.workerId, slot.botId)
        dispatchPendingFollowUps(slot.workerId, queuedFollowUps)
    }

    private suspend fun finalizeTurnFailure(slot: WorkerSlotRuntime, errorMessage: String) {
        toolApprovalCenter.cancelForWorker(slot.workerId)
        val nowMs = clock()
        val activeHandle = poolMutex.withLock { slot.handle }
        val committed = activeHandle?.let { readHistorySafely(it, slot.workerId) }
        val updatedUsage = activeHandle?.let { readUsageSafely(it, slot.workerId) }
        val updated = poolMutex.withLock {
            slot.watchdogJob?.cancel()
            slot.watchdogJob = null
            slot.questionEscalationJob?.cancel()
            slot.questionEscalationJob = null
            slot.questionEscalatedToSuper = false
            slot.activeToolCallIds.clear()
            slot.pendingFollowUps.clear()
            slot.compacting = false
            slot.retrying = false
            slot.silentPollingTurn = false
            slot.turnProducedOutputOrCard = false
            if (updatedUsage != null) slot.cachedUsage = updatedUsage
            val next = slot.record.copy(
                status = WorkerStatus.FAILED,
                lastOutcome = WorkerOutcome.FAILED,
                lastTurnSilent = false,
                lastError = errorMessage.take(MAX_SUMMARY_CHARS),
                activeStepText = null,
                pendingQuestion = null,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                steps = slot.record.steps.map { step ->
                    if (step.status == ToolActivityStatus.RUNNING) {
                        step.copy(status = ToolActivityStatus.FAILED, isError = true, updatedAt = nowMs)
                    } else {
                        step
                    }
                },
            )
            slot.record = next
            writeMetaFile(next)
            if (!committed.isNullOrEmpty()) {
                slot.committedMessages = committed
                slot.liveMessages.clear()
            } else {
                slot.streamingAssistant?.let { partial ->
                    slot.liveMessages += partial.copy(
                        streaming = false,
                        status = MessageStatus.FAILED,
                        error = errorMessage,
                        finishedAt = nowMs,
                    )
                }
            }
            slot.streamingAssistant = null
            publishSessionSnapshotLocked(slot, error = errorMessage)
            next
        }
        taskStore.upsertWorker(updated)
        emitWire(
            slot.workerId,
            slot.botId,
            buildJsonObject {
                put("type", "error")
                put("message", errorMessage)
            },
            terminal = false,
        )
        emitWire(slot.workerId, slot.botId, buildJsonObject { put("type", "agent_settled") }, terminal = true)
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(slot.workerId, WorkerStatus.FAILED, null))
        _milestones.tryEmit(
            LocalWorkerMilestoneEvent(
                originCaller = slot.originCaller,
                milestone = WorkerMilestone.Failed(
                    workerId = slot.workerId,
                    botRef = updated.botRef,
                    botName = updated.botName,
                    error = errorMessage,
                    artifacts = updated.artifacts,
                    cards = updated.cards,
                    timestamp = nowMs,
                ),
            ),
        )
        scheduleIdleMaintenance(slot.workerId, slot.botId)
    }

    private fun resetStallWatchdog(workerId: String) {
        scope.launch {
            val previousJob = poolMutex.withLock {
                val slot = slots[workerId] ?: return@withLock null
                val old = slot.watchdogJob
                val timeoutMs = effectiveStallTimeoutMs()
                if (timeoutMs <= 0L ||
                    (slot.record.status != WorkerStatus.RUNNING && slot.record.status != WorkerStatus.STALLED)
                ) {
                    slot.watchdogJob = null
                    return@withLock old
                }
                old?.cancel()
                slot.lastProgressAtMs = slot.lastProgressAtMs.coerceAtLeast(slot.record.lastProgressAt)
                slot.watchdogJob = scope.launch {
                    while (true) {
                        val currentTimeoutMs = effectiveStallTimeoutMs()
                        if (currentTimeoutMs <= 0L) break
                        val check = poolMutex.withLock {
                            val current = slots[workerId] ?: return@withLock null
                            if (current.record.status != WorkerStatus.RUNNING &&
                                current.record.status != WorkerStatus.STALLED
                            ) {
                                return@withLock null
                            }
                            val busyInSubstep = current.activeToolCallIds.isNotEmpty() ||
                                current.compacting ||
                                current.retrying
                            if (busyInSubstep) {
                                current.lastProgressAtMs = clock()
                            }
                            busyInSubstep to current.lastProgressAtMs
                        } ?: break
                        val (busyInSubstep, currentProgress) = check
                        if (busyInSubstep) {
                            delay(minOf(currentTimeoutMs, WATCHDOG_BUSY_POLL_MS))
                            continue
                        }
                        val elapsed = clock() - currentProgress
                        val remaining = currentTimeoutMs - elapsed
                        if (remaining <= 0L) {
                            onStallTimeout(workerId, currentTimeoutMs)
                            poolMutex.withLock {
                                slots[workerId]?.lastProgressAtMs = clock()
                            }
                            delay(currentTimeoutMs)
                        } else {
                            delay(remaining)
                        }
                    }
                }
                null
            }
            previousJob?.cancel()
        }
    }

    private suspend fun onStallTimeout(workerId: String, timeoutMs: Long) {
        val nowMs = clock()
        val stalled = poolMutex.withLock {
            val slot = slots[workerId] ?: return
            if (slot.record.status != WorkerStatus.RUNNING && slot.record.status != WorkerStatus.STALLED) return
            if (slot.activeToolCallIds.isNotEmpty() || slot.compacting || slot.retrying) return
            val latestProgress = maxOf(slot.record.lastProgressAt, slot.lastProgressAtMs)
            if (nowMs - latestProgress < timeoutMs) return
            val changed = slot.record.status != WorkerStatus.STALLED
            if (changed) {
                val next = slot.record.copy(
                    status = WorkerStatus.STALLED,
                    updatedAt = nowMs,
                )
                slot.record = next
                writeMetaFile(next)
            }
            slot to changed
        }
        val (slot, statusChanged) = stalled
        if (statusChanged) {
            taskStore.upsertWorker(slot.record)
            _progressEvents.tryEmit(
                WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.STALLED, slot.record.activeStepText),
            )
        }
        _milestones.tryEmit(
            LocalWorkerMilestoneEvent(
                originCaller = slot.originCaller,
                milestone = WorkerMilestone.Stalled(
                    workerId = workerId,
                    botRef = slot.record.botRef,
                    botName = slot.record.botName,
                    elapsedMs = (nowMs - maxOf(slot.record.lastProgressAt, slot.lastProgressAtMs)).coerceAtLeast(timeoutMs),
                    lastStepText = slot.record.activeStepText,
                    timestamp = nowMs,
                ),
            ),
        )
    }

    private fun scheduleQuestionTimeout(workerId: String) {
        scope.launch {
            val oldJob = poolMutex.withLock {
                val slot = slots[workerId] ?: return@withLock null
                val previous = slot.questionEscalationJob
                val timeoutMs = effectiveQuestionTimeoutMs()
                val pq = slot.activeQuestion
                if (timeoutMs <= 0L || pq == null || slot.record.status != WorkerStatus.WAITING_INPUT) {
                    slot.questionEscalationJob = null
                    return@withLock previous
                }
                previous?.cancel()
                val reqId = pq.requestId
                val askedAt = slot.record.updatedAt
                slot.questionEscalationJob = scope.launch {
                    val remaining = (askedAt + timeoutMs - clock()).coerceAtLeast(0L)
                    if (remaining > 0L) delay(remaining)
                    val escalation = poolMutex.withLock {
                        val current = slots[workerId] ?: return@withLock null
                        if (current.record.status != WorkerStatus.WAITING_INPUT) return@withLock null
                        val currentQ = current.activeQuestion ?: return@withLock null
                        if (currentQ.requestId != reqId || current.questionEscalatedToSuper) return@withLock null
                        current.questionEscalatedToSuper = true
                        Triple(current.originCaller, current.record, currentQ)
                    } ?: return@launch
                    val (originCaller, record, currentQ) = escalation
                    _milestones.tryEmit(
                        LocalWorkerMilestoneEvent(
                            originCaller = originCaller,
                            milestone = WorkerMilestone.WaitingInputTimeout(
                                workerId = workerId,
                                botRef = record.botRef,
                                botName = record.botName,
                                requestId = currentQ.requestId,
                                questions = currentQ.questions,
                                waitedMs = (clock() - askedAt).coerceAtLeast(timeoutMs),
                                timestamp = clock(),
                            ),
                        ),
                    )
                }
                null
            }
            oldJob?.cancel()
        }
    }

    private fun recoverStalledSlotLocked(slot: WorkerSlotRuntime, nowMs: Long): WorkerSlotRuntime? {
        if (slot.record.status != WorkerStatus.STALLED) return null
        slot.record = slot.record.copy(
            status = WorkerStatus.RUNNING,
            lastProgressAt = nowMs,
            updatedAt = nowMs,
        )
        return slot
    }

    private fun scheduleIdleMaintenance(workerId: String, botId: String) {
        scope.launch {
            poolMutex.withLock {
                val slot = slots[workerId] ?: return@withLock
                slot.idleEvictJob?.cancel()
                scheduleIdleEvictionLocked(slot, workerId)
            }
        }
    }

    private fun scheduleIdleEvictionOnly(workerId: String) {
        scope.launch {
            poolMutex.withLock {
                val slot = slots[workerId] ?: return@withLock
                if (slot.record.status.isBusyForCommands) return@withLock
                slot.idleEvictJob?.cancel()
                scheduleIdleEvictionLocked(slot, workerId)
            }
        }
    }

    private fun scheduleIdleEvictionLocked(slot: WorkerSlotRuntime, workerId: String) {
        val evictDelayMs = effectiveIdleEvictMs()
        slot.idleEvictJob = scope.launch(ioDispatcher) {
            delay(evictDelayMs)
            evictIdleHandle(workerId)
        }
    }

    internal suspend fun evictIdleHandle(workerId: String) {
        val (slot, handle) = poolMutex.withLock {
            val current = slots[workerId] ?: return
            if (current.record.status.isBusyForCommands) return
            val existing = current.handle ?: return
            current to existing
        }
        if (hasRunningJobs(handle.sessionId)) {
            scheduleIdleEvictionOnly(workerId)
            return
        }
        val closed = poolMutex.withLock {
            if (slot.handle === handle && !slot.record.status.isBusyForCommands && !hasRunningJobs(handle.sessionId)) {
                detachAndCloseHandleLocked(slot)
            } else {
                null
            }
        }
        if (closed != null) {
            onWorkerSessionCleanup(closed.sessionId)
            try {
                closed.close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("LocalWorkerPool", "Failed to close evicted handle for $workerId", error)
            }
        }
    }

    private suspend fun readHistorySafely(handle: SessionHandle, workerId: String): List<AssistantMessage>? =
        try {
            handle.history()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("LocalWorkerPool", "Failed to read history for $workerId", error)
            null
        }

    private suspend fun readUsageSafely(handle: SessionHandle, workerId: String): SessionUsage? =
        try {
            handle.usage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("LocalWorkerPool", "Failed to read usage for $workerId", error)
            null
        }

    private suspend fun drainPendingQueue() {
        while (true) {
            val next = poolMutex.withLock {
                if (activeExecutingCountLocked() >= effectiveMaxConcurrentWorkers()) return@withLock null
                val queued = pendingQueue.removeFirstOrNull() ?: return@withLock null
                val slot = slots[queued.workerId] ?: return@withLock queued
                val nowMs = clock()
                slot.silentPollingTurn = queued.silentPolling
                slot.turnProducedOutputOrCard = false
                slot.record = slot.record.copy(
                    status = WorkerStatus.RUNNING,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                    activeStepText = null,
                )
                writeMetaFile(slot.record)
                if (slot.streamingAssistant == null) {
                    slot.streamingAssistant = AssistantMessage(
                        id = slot.nextUiId.incrementAndGet(),
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        streaming = true,
                        status = MessageStatus.RUNNING,
                        startedAt = nowMs,
                    )
                }
                publishSessionSnapshotLocked(slot)
                queued
            } ?: return
            val slot = poolMutex.withLock { slots[next.workerId] } ?: continue
            taskStore.upsertWorker(slot.record)
            _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(next.workerId, WorkerStatus.RUNNING, null))
            val spec = resolveBotSpec(next.botId)
            if (spec == null) {
                finalizeTurnFailure(slot, "bot ${next.botId} is not available")
                continue
            }
            launchTurn(next.workerId, spec, next.prompt, next.images, next.originCaller)
        }
    }

    private fun dispatchPendingFollowUps(workerId: String, queuedFollowUps: List<PendingWorkerFollowUp>) {
        if (queuedFollowUps.isEmpty()) return
        scope.launch {
            for (followUp in queuedFollowUps) {
                deliverFollowUpToWorker(workerId, followUp.message, followUp.silentPolling)
            }
        }
    }

    private suspend fun onApprovalPendingChanged(current: List<PendingQuestion>) {
        val byRequestId = current.associateBy { it.requestId }
        val resolvedIds = previousQuestions.keys - byRequestId.keys
        val added = byRequestId.filterKeys { it !in previousQuestions }
        previousQuestions = byRequestId

        for (requestId in resolvedIds) {
            val resolved = poolMutex.withLock {
                val slot = slots.values.firstOrNull { it.activeQuestion?.requestId == requestId }
                    ?: return@withLock null
                val nowMs = clock()
                slot.activeQuestion = null
                slot.questionEscalationJob?.cancel()
                slot.questionEscalationJob = null
                slot.questionEscalatedToSuper = false
                val nextStatus = if (hasActiveToolApprovalLocked(slot)) {
                    WorkerStatus.WAITING_INPUT
                } else if (slot.turnJob?.isActive == true && !slot.abortedByUser) {
                    WorkerStatus.RUNNING
                } else {
                    slot.record.status
                }
                val updated = slot.record.copy(
                    status = nextStatus,
                    pendingQuestion = null,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                )
                slot.record = updated
                writeMetaFile(updated)
                val queuedFollowUps = if (nextStatus != WorkerStatus.WAITING_INPUT && slot.pendingFollowUps.isNotEmpty()) {
                    slot.pendingFollowUps.toList().also { slot.pendingFollowUps.clear() }
                } else {
                    emptyList()
                }
                ResolvedApproval(slot.workerId, slot.botId, updated, queuedFollowUps)
            }
            if (resolved != null) {
                taskStore.upsertWorker(resolved.updatedRecord)
                resetStallWatchdog(resolved.workerId)
                _progressEvents.tryEmit(WorkerProgressEvent.ApprovalResolved(resolved.workerId, requestId))
                _progressEvents.tryEmit(
                    WorkerProgressEvent.StatusChanged(
                        resolved.workerId,
                        resolved.updatedRecord.status,
                        resolved.updatedRecord.activeStepText,
                    ),
                )
                emitWire(
                    resolved.workerId,
                    resolved.botId,
                    buildJsonObject {
                        put("type", "approval_request")
                        put("approvalId", requestId)
                        put("kind", "resolved")
                        put("summary", "resolved")
                        put("resolved", true)
                    },
                    terminal = false,
                )
                dispatchPendingFollowUps(resolved.workerId, resolved.queuedFollowUps)
            }
        }

        for ((_, pq) in added) {
            val mappedWorkerId = sessionToWorker[pq.sessionId] ?: continue
            val (botId, updatedRecord) = poolMutex.withLock {
                val slot = slots[mappedWorkerId] ?: return@withLock null to null
                val nowMs = clock()
                slot.watchdogJob?.cancel()
                slot.watchdogJob = null
                slot.questionEscalationJob?.cancel()
                slot.questionEscalationJob = null
                slot.questionEscalatedToSuper = false
                slot.activeQuestion = pq
                val updated = slot.record.copy(
                    status = WorkerStatus.WAITING_INPUT,
                    pendingQuestion = pq,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                )
                slot.record = updated
                writeMetaFile(updated)
                slot.botId to updated
            }
            if (botId != null && updatedRecord != null) {
                taskStore.upsertWorker(updatedRecord)
                scheduleQuestionTimeout(mappedWorkerId)
                _progressEvents.tryEmit(WorkerProgressEvent.ApprovalRequested(mappedWorkerId, pq))
                _progressEvents.tryEmit(
                    WorkerProgressEvent.StatusChanged(mappedWorkerId, WorkerStatus.WAITING_INPUT, updatedRecord.activeStepText),
                )
                val firstPrompt = pq.questions.firstOrNull()?.question?.takeIf { it.isNotBlank() } ?: "Approval required"
                emitWire(
                    mappedWorkerId,
                    botId,
                    buildJsonObject {
                        put("type", "approval_request")
                        put("approvalId", pq.requestId)
                        put("kind", "question")
                        if (pq.toolCallId.isNotBlank()) put("toolCallId", pq.toolCallId)
                        put("summary", firstPrompt.take(512))
                        put("title", firstPrompt.take(512))
                        put("questions", encodeQuestions(pq))
                    },
                    terminal = false,
                )
            }
        }
    }

    private fun hasActiveToolApprovalLocked(
        slot: WorkerSlotRuntime,
        current: List<PendingToolApproval> = toolApprovalCenter.pending.value,
    ): Boolean {
        val sid = slot.handle?.sessionId
        return current.any { it.workerId == slot.workerId || (sid != null && it.sessionId == sid) }
    }

    private suspend fun onToolApprovalPendingChanged(current: List<PendingToolApproval>) {
        val byId = current.associateBy { it.id }
        val resolvedEntries = previousToolApprovals.filterKeys { it !in byId }
        val added = byId.filterKeys { it !in previousToolApprovals }
        previousToolApprovals = byId

        for ((_, prev) in resolvedEntries) {
            val mappedWorkerId = prev.workerId ?: sessionToWorker[prev.sessionId] ?: continue
            val resolved = poolMutex.withLock {
                val slot = slots[mappedWorkerId] ?: return@withLock null
                if (slot.activeQuestion != null || hasActiveToolApprovalLocked(slot, current)) {
                    return@withLock null
                }
                val nowMs = clock()
                val nextStatus = if (slot.turnJob?.isActive == true && !slot.abortedByUser) {
                    WorkerStatus.RUNNING
                } else {
                    slot.record.status
                }
                val updated = slot.record.copy(
                    status = nextStatus,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                )
                slot.record = updated
                writeMetaFile(updated)
                val queuedFollowUps = if (nextStatus != WorkerStatus.WAITING_INPUT && slot.pendingFollowUps.isNotEmpty()) {
                    slot.pendingFollowUps.toList().also { slot.pendingFollowUps.clear() }
                } else {
                    emptyList()
                }
                ResolvedApproval(slot.workerId, slot.botId, updated, queuedFollowUps)
            }
            if (resolved != null) {
                taskStore.upsertWorker(resolved.updatedRecord)
                if (resolved.updatedRecord.status == WorkerStatus.RUNNING) {
                    resetStallWatchdog(resolved.workerId)
                }
                _progressEvents.tryEmit(
                    WorkerProgressEvent.StatusChanged(
                        resolved.workerId,
                        resolved.updatedRecord.status,
                        resolved.updatedRecord.activeStepText,
                    ),
                )
                dispatchPendingFollowUps(resolved.workerId, resolved.queuedFollowUps)
            }
        }

        for ((_, approval) in added) {
            val mappedWorkerId = approval.workerId ?: sessionToWorker[approval.sessionId] ?: continue
            val (botId, updatedRecord) = poolMutex.withLock {
                val slot = slots[mappedWorkerId] ?: return@withLock null to null
                val nowMs = clock()
                slot.watchdogJob?.cancel()
                slot.watchdogJob = null
                slot.questionEscalationJob?.cancel()
                slot.questionEscalationJob = null
                slot.questionEscalatedToSuper = false
                val updated = slot.record.copy(
                    status = WorkerStatus.WAITING_INPUT,
                    updatedAt = nowMs,
                    lastProgressAt = nowMs,
                )
                slot.record = updated
                writeMetaFile(updated)
                slot.botId to updated
            }
            if (botId != null && updatedRecord != null) {
                taskStore.upsertWorker(updatedRecord)
                _progressEvents.tryEmit(
                    WorkerProgressEvent.StatusChanged(mappedWorkerId, WorkerStatus.WAITING_INPUT, updatedRecord.activeStepText),
                )
            }
        }
    }

    private fun stagePresentedArtifacts(
        botId: String,
        workerId: String,
        rawOutput: String,
        nowMs: Long,
    ): List<WorkerArtifactRef> {
        val root = runCatching { JSON.parseToJsonElement(rawOutput) as? JsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        val out = ArrayList<WorkerArtifactRef>()
        val artifactsDir = workerArtifactsDir(botId, workerId).apply { mkdirs() }
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim() ?: continue
            if (type !in STAGEABLE_ARTIFACT_TYPES) continue
            val rawPath = (obj["path"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            val source = File(rawPath)
            if (!source.isFile || !source.canRead()) continue
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: source.name
            val mime = (obj["mime"] as? JsonPrimitive)?.contentOrNull?.trim()
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
            val artifactId = artifactHash(workerId, source.absolutePath, source.length())
            val staged = File(artifactsDir, "${artifactId}_${sanitizeFileName(name)}")
            if (!staged.isFile || staged.length() != source.length()) {
                runCatching { source.copyTo(staged, overwrite = true) }
            }
            val effectiveFile = if (staged.isFile) staged else source
            out += WorkerArtifactRef(
                artifactId = artifactId,
                workerId = workerId,
                botRef = SuperRuntime.localBotRef(botId),
                kind = type,
                path = effectiveFile.absolutePath,
                name = name,
                title = title,
                mimeType = mime,
                sizeBytes = effectiveFile.length(),
                createdAt = nowMs,
            )
        }
        return out
    }

    private fun harvestRichCard(
        slot: WorkerSlotRuntime,
        event: AgentEvent.ToolExecutionEnd,
        prevArgs: String,
        rawText: String,
        details: JsonObject?,
        nowMs: Long,
    ): WorkerCardRef? {
        val cardDescriptor = details?.get(CARD_DESCRIPTOR_KEY) as? JsonObject
        val structured = ToolResultDetails.structuredContent(details)
        if (cardDescriptor == null && structured == null) return null
        val title = (cardDescriptor?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ((structured as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: event.toolName
        val payloadJson = (cardDescriptor ?: structured).toString()
        return WorkerCardRef(
            cardId = event.toolCallId,
            workerId = slot.workerId,
            botRef = slot.record.botRef,
            toolName = event.toolName,
            title = title,
            argsJson = prevArgs,
            outputText = rawText,
            detailsJson = details?.toString().orEmpty(),
            payloadJson = payloadJson,
            createdAt = nowMs,
        )
    }

    private suspend fun reconcileDiskAndStore() {
        val dbLocalById = taskStore.workers.value.filter { !it.isRemote }.associateBy { it.workerId }
        val loaded = LinkedHashMap<String, WorkerRunRecord>()
        val loadedSeqs = HashMap<String, Long>()
        if (workersRoot.isDirectory) {
            for (botDir in workersRoot.listFiles().orEmpty()) {
                if (!botDir.isDirectory) continue
                val botId = botDir.name
                for (wDir in botDir.listFiles().orEmpty()) {
                    if (!wDir.isDirectory) continue
                    val workerId = wDir.name
                    val metaEntry = readMetaFileWithSeq(botId, workerId)
                    val meta = metaEntry?.first
                    val savedSeq = metaEntry?.second ?: 0L
                    val record = dbLocalById[workerId] ?: meta ?: continue
                    val normalized = if (record.status.isBusyForCommands) {
                        record.copy(
                            status = WorkerStatus.FAILED,
                            lastOutcome = WorkerOutcome.FAILED,
                            lastError = "Interrupted by app restart",
                            activeStepText = null,
                            pendingQuestion = null,
                        )
                    } else {
                        record
                    }
                    loaded[workerId] = normalized
                    loadedSeqs[workerId] = savedSeq
                    if (meta == null || meta != normalized) writeMetaFile(normalized, savedSeq)
                    if (dbLocalById[workerId] != normalized) taskStore.upsertWorker(normalized)
                }
            }
        }
        for ((workerId, record) in dbLocalById) {
            if (!loaded.containsKey(workerId)) {
                loaded[workerId] = record
                loadedSeqs[workerId] = 0L
                ensureWorkerDirectories(record.botId, workerId)
                writeMetaFile(record, 0L)
            }
        }
        poolMutex.withLock {
            slots.clear()
            for ((workerId, record) in loaded) {
                slots[workerId] = WorkerSlotRuntime(
                    workerId = workerId,
                    botId = record.botId,
                    record = record,
                    seq = AtomicLong(loadedSeqs[workerId] ?: 0L),
                )
            }
        }
    }

    private suspend fun emitWire(workerId: String, botId: String, eventJson: JsonObject, terminal: Boolean) {
        val event = poolMutex.withLock {
            val slot = slots[workerId] ?: return
            val seq = slot.seq.incrementAndGet()
            val item = LocalWorkerEvent(
                workerId = workerId,
                botId = botId,
                seq = seq,
                event = eventJson,
                terminal = terminal,
            )
            slot.buffer.addLast(item)
            while (slot.buffer.size > MAX_REPLAY_BUFFER) {
                slot.buffer.removeFirst()
                slot.evictedBefore = true
            }
            if (terminal) {
                writeMetaFile(slot.record, seq)
            }
            item
        }
        _wireEvents.tryEmit(event)
    }

    private suspend fun detachAndCloseHandle(slot: WorkerSlotRuntime) {
        val handle = poolMutex.withLock {
            detachAndCloseHandleLocked(slot)
        } ?: return
        runCatching { handle.close() }
    }

    private fun detachAndCloseHandleLocked(slot: WorkerSlotRuntime): SessionHandle? {
        slot.unsubscribe?.invoke()
        slot.unsubscribe = null
        val handle = slot.handle ?: return null
        slot.handle = null
        sessionToWorker.remove(handle.sessionId, slot.workerId)
        return handle
    }

    private suspend fun loadSessionEntries(sessionFile: File, workspaceDir: File): List<SessionEntry>? {
        if (!sessionFile.isFile || sessionFile.length() == 0L) return null
        return runCatching {
            SessionManager.open(
                file = sessionFile.absolutePath.toPath(),
                fs = FileSystem.SYSTEM,
                fallbackCwd = workspaceDir.absolutePath.toPath(),
            ).getBranch()
        }.getOrNull()
    }

    private fun activeExecutingCountLocked(): Int =
        slots.values.count {
            it.record.status == WorkerStatus.RUNNING ||
                it.record.status == WorkerStatus.WAITING_INPUT ||
                it.record.status == WorkerStatus.STALLED
        }

    private fun ensureWorkerDirectories(botId: String, workerId: String) {
        workerDir(botId, workerId).mkdirs()
        workerWorkspaceDir(botId, workerId).mkdirs()
        workerArtifactsDir(botId, workerId).mkdirs()
    }

    private fun workerDir(botId: String, workerId: String): File =
        File(File(workersRoot, sanitizeSegment(botId)), sanitizeSegment(workerId))

    fun workerSessionFile(botId: String, workerId: String): File =
        File(workerDir(botId, workerId), "session.jsonl")

    fun workerWorkspaceDir(botId: String, workerId: String): File =
        File(workerDir(botId, workerId), "workspace")

    private fun workerArtifactsDir(botId: String, workerId: String): File =
        File(workerDir(botId, workerId), "artifacts")

    private fun workerMetaFile(botId: String, workerId: String): File =
        File(workerDir(botId, workerId), "meta.json")

    private fun writeMetaFile(
        record: WorkerRunRecord,
        lastSeq: Long = slots[record.workerId]?.seq?.get() ?: 0L,
    ) {
        val file = workerMetaFile(record.botId, record.workerId)
        file.parentFile?.mkdirs()
        val obj = buildJsonObject {
            put("workerId", record.workerId)
            put("botRef", record.botRef)
            put("botId", record.botId)
            put("botName", record.botName)
            record.botAvatar?.let { put("botAvatar", it) }
            put("deviceName", record.deviceName)
            put("title", record.title)
            put("status", record.status.name)
            put("lastOutcome", record.lastOutcome.name)
            put("turnCount", record.turnCount)
            put("createdAt", record.createdAt)
            put("updatedAt", record.updatedAt)
            put("lastProgressAt", record.lastProgressAt)
            put("lastSeq", lastSeq.coerceAtLeast(0L))
            record.lastSummary?.let { put("lastSummary", it) }
            record.lastError?.let { put("lastError", it) }
        }
        val tmp = File(file.parentFile, "meta.json.tmp")
        tmp.writeText(obj.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(obj.toString())
            tmp.delete()
        }
    }

    private fun readMetaFile(botId: String, workerId: String): WorkerRunRecord? =
        readMetaFileWithSeq(botId, workerId)?.first

    private fun readMetaFileWithSeq(botId: String, workerId: String): Pair<WorkerRunRecord, Long>? {
        val file = workerMetaFile(botId, workerId)
        if (!file.isFile) return null
        val obj = runCatching { JSON.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return null
        val id = (obj["workerId"] as? JsonPrimitive)?.contentOrNull ?: workerId
        val bId = (obj["botId"] as? JsonPrimitive)?.contentOrNull ?: botId
        val record = WorkerRunRecord(
            workerId = id,
            botRef = (obj["botRef"] as? JsonPrimitive)?.contentOrNull ?: SuperRuntime.localBotRef(bId),
            botId = bId,
            botName = (obj["botName"] as? JsonPrimitive)?.contentOrNull ?: bId,
            botAvatar = (obj["botAvatar"] as? JsonPrimitive)?.contentOrNull,
            deviceId = null,
            deviceName = (obj["deviceName"] as? JsonPrimitive)?.contentOrNull ?: deviceNameProvider(),
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: id,
            status = (obj["status"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { WorkerStatus.valueOf(it) }.getOrNull() }
                ?: WorkerStatus.IDLE,
            lastOutcome = (obj["lastOutcome"] as? JsonPrimitive)?.contentOrNull
                ?.let { runCatching { WorkerOutcome.valueOf(it) }.getOrNull() }
                ?: WorkerOutcome.NONE,
            turnCount = (obj["turnCount"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 1,
            createdAt = (obj["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            lastProgressAt = (obj["lastProgressAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            lastSummary = (obj["lastSummary"] as? JsonPrimitive)?.contentOrNull,
            lastError = (obj["lastError"] as? JsonPrimitive)?.contentOrNull,
        )
        val savedSeq = (obj["lastSeq"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L
        return record to savedSeq
    }

    private fun encodeQuestions(pq: PendingQuestion): JsonArray = buildJsonArray {
        for (q in pq.questions) {
            add(
                buildJsonObject {
                    put("id", q.id)
                    q.header?.takeIf { it.isNotBlank() }?.let { put("header", it) }
                    put("question", q.question)
                    if (q.multiSelect) put("multiSelect", true)
                    if (q.options.isNotEmpty()) {
                        put(
                            "options",
                            buildJsonArray {
                                for (opt in q.options) {
                                    add(
                                        buildJsonObject {
                                            put("label", opt.label)
                                            opt.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun formatStepText(toolName: String, args: Map<String, String>): String {
        val hint = args.values.firstOrNull { it.isNotBlank() }?.replace('\n', ' ')?.take(48)
        return if (hint.isNullOrBlank()) toolName else "$toolName: $hint"
    }

    private fun summarizeTitle(task: String): String {
        val normalized = task.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return "Worker task"
        return if (normalized.length <= 42) normalized else normalized.take(42) + "…"
    }

    private fun artifactHash(workerId: String, path: String, size: Long): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$workerId|$path|$size".toByteArray(Charsets.UTF_8))
        return "art_" + bytes.take(10).joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }.take(64)

    private fun sanitizeSegment(segment: String): String =
        segment.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "__").ifBlank { "x" }.take(64)

    private suspend fun loadPreloadedHistoryIfNeeded(workerId: String, botId: String): List<AssistantMessage>? {
        val needsLoad = poolMutex.withLock {
            val slot = slots[workerId] ?: return@withLock false
            slot.committedMessages.isEmpty() && slot.handle == null
        }
        if (!needsLoad) return null
        val entries = withContext(ioDispatcher) {
            loadSessionEntries(workerSessionFile(botId, workerId), workerWorkspaceDir(botId, workerId))
        }
        return if (entries.isNullOrEmpty()) null
        else HistoryMapper.mapEntries(entries, settleUnfinishedTools = true)
    }

    private fun sessionFlowFor(workerId: String): MutableStateFlow<WorkerSessionSnapshot> =
        sessionFlows.getOrPut(workerId) { MutableStateFlow(WorkerSessionSnapshot(workerId = workerId)) }

    private fun ensureStreamingAssistantLocked(slot: WorkerSlotRuntime, nowMs: Long): AssistantMessage =
        slot.streamingAssistant ?: AssistantMessage(
            id = slot.nextUiId.incrementAndGet(),
            role = AssistantRole.ASSISTANT,
            text = "",
            streaming = true,
            status = MessageStatus.RUNNING,
            startedAt = nowMs,
        ).also { slot.streamingAssistant = it }

    private fun effectiveMaxConcurrentWorkers(): Int =
        (settingsProvider?.invoke()?.maxConcurrentWorkers ?: maxConcurrentWorkers).coerceAtLeast(1)

    private fun effectiveMaxSlotsPerBot(): Int =
        (settingsProvider?.invoke()?.maxSlotsPerBot ?: maxWorkersPerBot).coerceAtLeast(1)

    private fun effectiveStallTimeoutMs(): Long =
        settingsProvider?.invoke()?.stallTimeoutMs ?: stallTimeoutMs

    private fun effectiveQuestionTimeoutMs(): Long =
        settingsProvider?.invoke()?.questionTimeoutMs ?: questionTimeoutMs

    private fun effectiveIdleEvictMs(): Long =
        settingsProvider?.invoke()?.idleEvictMs ?: idleEvictMs

    private fun scheduleStreamingSnapshotPublishLocked(slot: WorkerSlotRuntime) {
        if (slot.pendingSnapshotJob?.isActive == true) return
        val workerId = slot.workerId
        slot.pendingSnapshotJob = scope.launch {
            delay(STREAM_SNAPSHOT_COALESCE_MS)
            poolMutex.withLock {
                val current = slots[workerId] ?: return@withLock
                if (current !== slot) return@withLock
                current.pendingSnapshotJob = null
                publishSessionSnapshotLocked(current)
            }
        }
    }

    private fun publishSessionSnapshotLocked(
        slot: WorkerSlotRuntime,
        loaded: Boolean? = null,
        error: String? = slot.record.lastError,
    ) {
        slot.pendingSnapshotJob?.cancel()
        slot.pendingSnapshotJob = null
        val flow = sessionFlowFor(slot.workerId)
        val previous = flow.value
        val combined = buildList {
            addAll(slot.committedMessages)
            addAll(slot.liveMessages)
            slot.streamingAssistant?.let { add(it) }
        }
        val config = slot.modelConfig
        val providerId = config?.providerId.orEmpty()
        val options = if (providerId.isNotBlank()) providers?.models(providerId).orEmpty() else emptyList()
        val option = options.firstOrNull { it.id == config?.modelId }
        val levels = option?.thinkingLevels?.takeIf { it.isNotEmpty() } ?: listOf(ThinkingLevel.OFF)
        val thinking = levels.resolveThinkingLevel(config?.thinkingLevel ?: ThinkingLevel.OFF)
        flow.value = WorkerSessionSnapshot(
            workerId = slot.workerId,
            messages = combined,
            streaming = slot.record.status.isBusyForCommands,
            activeToolText = slot.record.activeStepText,
            providerId = providerId,
            modelId = config?.modelId.orEmpty(),
            modelLabel = option?.displayName ?: config?.modelId.orEmpty(),
            thinkingLevel = thinking,
            thinkingLevels = levels,
            usage = slot.cachedUsage ?: previous.usage,
            loaded = loaded ?: previous.loaded,
            error = error,
        )
    }

    companion object {
        const val LOCAL_CALLER = "local"
        const val USER_DIRECT_CALLER = "user_direct"
        const val MAX_CONCURRENT_LOCAL_WORKERS = 5
        const val DEFAULT_STALL_TIMEOUT_MS = 10 * 60_000L
        const val DEFAULT_IDLE_DEBOUNCE_MS = 5 * 60_000L
        const val DEFAULT_IDLE_EVICT_MS = 5 * 60_000L
        const val NO_UPDATE_MARKER = "[NO_UPDATE]"
        private const val WATCHDOG_BUSY_POLL_MS = 5_000L
        private const val MAX_STORED_STEPS = 200
        private const val MAX_STEP_OUTPUT_PREVIEW = 1_200
        private const val MAX_SUMMARY_CHARS = 4_000
        private const val MAX_PENDING_FOLLOW_UPS = 16
        private const val STREAM_SNAPSHOT_COALESCE_MS = 60L
        private const val MAX_REPLAY_BUFFER = 512
        private const val PROGRESS_BUFFER = 512
        private const val MILESTONE_BUFFER = 128
        private const val WIRE_EVENT_BUFFER = 1_024
        private const val CARD_DESCRIPTOR_KEY = "superpi.card"
        private val STAGEABLE_ARTIFACT_TYPES = setOf("file", "image", "video", "html")
        private val JSON = Json { ignoreUnknownKeys = true }

        fun isSilentNoUpdateReply(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            val upper = trimmed.uppercase()
            return upper == NO_UPDATE_MARKER ||
                upper.startsWith("$NO_UPDATE_MARKER ") ||
                upper.startsWith("$NO_UPDATE_MARKER:") ||
                upper.startsWith("$NO_UPDATE_MARKER\n") ||
                upper.startsWith("$NO_UPDATE_MARKER：")
        }
    }
}

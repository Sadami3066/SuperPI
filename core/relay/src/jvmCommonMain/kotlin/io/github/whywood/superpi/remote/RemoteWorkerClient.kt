package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RelayFrames
import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.data.relay.RouteImagePart
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolResultDetails
import io.github.whywood.superpi.runtime.ToolResultPayload
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
import io.github.whywood.superpi.runtime.isActive
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.runtime.superagent.RemoteWorkerGateway
import io.github.whywood.superpi.runtime.superagent.SuperTaskStore
import io.github.whywood.superpi.runtime.superagent.WorkerHistoryWireCodec
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RemoteWorkerClient(
    private val transport: RemoteAgentTransport,
    private val taskStore: SuperTaskStore,
    private val artifactClient: RemoteArtifactClient,
    private val remoteCacheRoot: File,
    private val scope: CoroutineScope,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val questionTimeoutMs: Long = 0L,
    private val settingsProvider: (() -> SuperRuntimeSettings)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : RemoteWorkerGateway {
    private val _progressEvents = MutableSharedFlow<WorkerProgressEvent>(extraBufferCapacity = PROGRESS_BUFFER)
    override val progressEvents: SharedFlow<WorkerProgressEvent> = _progressEvents.asSharedFlow()

    private val _milestones = MutableSharedFlow<WorkerMilestone>(extraBufferCapacity = MILESTONE_BUFFER)
    override val milestones: SharedFlow<WorkerMilestone> = _milestones.asSharedFlow()

    private val timerLock = Any()
    private val routeToWorker = ConcurrentHashMap<String, ActiveRemoteStream>()
    private val workerWatchdogs = ConcurrentHashMap<String, Job>()
    private val questionEscalationJobs = ConcurrentHashMap<String, Job>()
    private val questionEscalatedToSuper = ConcurrentHashMap<String, Boolean>()
    private val questionAskedLocalAtMs = ConcurrentHashMap<String, Long>()
    private val activeRemoteToolCallIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val compactingWorkers = ConcurrentHashMap.newKeySet<String>()
    private val retryingWorkers = ConcurrentHashMap.newKeySet<String>()
    private val remoteMaxSlotsByDevice = ConcurrentHashMap<String, Int>()
    private val localLastProgressAtMs = ConcurrentHashMap<String, Long>()
    private val workerSilentPolling = ConcurrentHashMap<String, Boolean>()
    private val turnProducedArtifactOrCard = ConcurrentHashMap<String, Boolean>()
    private val reattachInFlight = ConcurrentHashMap.newKeySet<String>()
    private val workerNotifySuper = ConcurrentHashMap<String, Boolean>()
    private val sessionBuffers = ConcurrentHashMap<String, RemoteSessionBuffer>()
    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<WorkerSessionSnapshot>>()
    private val sessionStateLock = Any()

    private class ActiveRemoteStream(
        val routeId: String,
        val workerId: String,
        val deviceId: String,
        val persistent: Boolean,
        @Volatile var lastSeq: Long = 0L,
        val textBuffer: StringBuilder = StringBuilder(),
        var lastToolNameByCallId: MutableMap<String, String> = HashMap(),
    )

    private class RemoteSessionBuffer(
        var committedMessages: List<AssistantMessage> = emptyList(),
        val liveUserMessages: MutableList<AssistantMessage> = mutableListOf(),
        var streamingAssistant: AssistantMessage? = null,
        var activeToolText: String? = null,
        var providerId: String = "",
        var modelId: String = "",
        var modelLabel: String = "",
        var thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        var thinkingLevels: List<ThinkingLevel> = listOf(ThinkingLevel.OFF),
        var usage: SessionUsage? = null,
        var loaded: Boolean = false,
        var streaming: Boolean = false,
        var nextLocalId: Long = -1L,
        var stepIdCounter: Long = 1_000L,
        var error: String? = null,
    )

    init {
        scope.launch {
            transport.remoteEvents.collect { remoteEvent ->
                val stream = routeToWorker[remoteEvent.routeId] ?: return@collect
                try {
                    handleRemoteEvent(stream, remoteEvent.seq, remoteEvent.event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logWarn("RemoteWorkerClient", "Failed to handle remote event for ${stream.workerId}", error)
                }
            }
        }
        scope.launch {
            transport.streamClosures.collect { closed ->
                when (closed) {
                    RemoteStreamClosed.TransportDisconnected -> routeToWorker.clear()
                    is RemoteStreamClosed.PeerOffline -> {
                        routeToWorker.entries.removeIf { it.value.deviceId == closed.deviceId }
                    }
                    is RemoteStreamClosed.RouteRejected -> {
                        routeToWorker.remove(closed.routeId)
                    }
                }
            }
        }
        scope.launch {
            taskStore.ensure()
            var previousOnline = emptySet<String>()
            transport.onlineDevices.collect { online ->
                val newlyOnline = online - previousOnline
                val goneOffline = previousOnline - online
                previousOnline = online
                if (goneOffline.isNotEmpty()) {
                    routeToWorker.entries.removeIf { it.value.deviceId in goneOffline }
                }
                if (newlyOnline.isNotEmpty()) {
                    reattachActiveWorkersForDevices(newlyOnline)
                }
            }
        }
    }

    override suspend fun reattachOnlineWorkers() {
        taskStore.ensure()
        val online = transport.onlineDevices.value
        if (online.isNotEmpty()) {
            reattachActiveWorkersForDevices(online)
        }
    }

    override fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot> =
        sessionFlows.computeIfAbsent(workerId) {
            MutableStateFlow(WorkerSessionSnapshot(workerId = workerId))
        }.asStateFlow()

    override suspend fun loadWorkerSession(worker: WorkerRunRecord) {
        val current = taskStore.currentWorker(worker.workerId) ?: worker
        updateSessionBuffer(current.workerId) { buf ->
            buf.loaded = buf.loaded || current.deviceId == null
        }
        if (current.deviceId == null) return
        runCatching { inspectWorker(current, tail = 50) }
            .onFailure { err ->
                updateSessionBuffer(current.workerId) { buf ->
                    buf.loaded = true
                    buf.error = err.message ?: current.lastError
                }
            }
    }

    override suspend fun listRemoteWorkers(deviceId: String, botId: String?): List<WorkerRunRecord> {
        val params = buildJsonObject {
            if (!botId.isNullOrBlank()) put("botId", botId)
        }
        val result = requireOk(transport.callWithOutcome(deviceId, "worker.list", params))
        (result["maxWorkersPerBot"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let {
            remoteMaxSlotsByDevice[deviceId] = it
        }
        val array = result["workers"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            parseSummary(element as? JsonObject, deviceId, deviceName = deviceName(deviceId))
        }
    }

    override fun maxSlotsForDevice(deviceId: String): Int? = remoteMaxSlotsByDevice[deviceId]

    override suspend fun startWorker(
        blueprint: BotBlueprint,
        task: String,
        images: List<ImagePart>,
        modelOverride: ModelConfig?,
    ): WorkerRunRecord = startWorker(blueprint, task, images, modelOverride, title = null)

    override suspend fun startWorker(
        blueprint: BotBlueprint,
        task: String,
        images: List<ImagePart>,
        modelOverride: ModelConfig?,
        title: String?,
    ): WorkerRunRecord {
        val deviceId = blueprint.deviceId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("blueprint is not remote")
        val cleanTask = task.trim()
        require(cleanTask.isNotEmpty() || images.isNotEmpty()) { "task must not be empty" }
        val workerId = "wrk-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val nowMs = clock()
        val resolvedTitle = title
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(60)
            ?.takeIf { it.isNotEmpty() }
            ?: summarizeTitle(cleanTask)
        val initialRecord = WorkerRunRecord(
            workerId = workerId,
            botRef = blueprint.ref,
            botId = blueprint.botId,
            botName = blueprint.name,
            botAvatar = blueprint.avatar,
            deviceId = deviceId,
            deviceName = blueprint.deviceName,
            title = resolvedTitle,
            status = WorkerStatus.RUNNING,
            lastOutcome = WorkerOutcome.NONE,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
            activeStepText = null,
        )
        taskStore.upsertWorker(initialRecord)

        val params = buildJsonObject {
            put("botId", blueprint.botId)
            put("workerId", workerId)
            put("task", cleanTask)
            put("title", resolvedTitle)
            if (modelOverride != null) {
                put("providerId", modelOverride.providerId)
                put("modelId", modelOverride.modelId)
                put("thinkingLevel", modelOverride.thinkingLevel.name)
            }
            if (images.isNotEmpty()) {
                put("images", RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }))
            }
        }
        var boundRouteId: String? = null
        workerNotifySuper[workerId] = true
        workerSilentPolling.remove(workerId)
        turnProducedArtifactOrCard.remove(workerId)
        activeRemoteToolCallIds.remove(workerId)
        compactingWorkers.remove(workerId)
        retryingWorkers.remove(workerId)
        questionEscalatedToSuper.remove(workerId)
        questionAskedLocalAtMs.remove(workerId)
        localLastProgressAtMs[workerId] = nowMs
        beginOptimisticTurn(workerId, formatUserTurnDisplay(cleanTask, images), nowMs)
        val outcome = try {
            transport.callWithOutcome(deviceId, "worker.start", params) { routeId ->
                boundRouteId = routeId
                routeToWorker[routeId] = ActiveRemoteStream(
                    routeId = routeId,
                    workerId = workerId,
                    deviceId = deviceId,
                    persistent = false,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                cleanupFailedStart(workerId, boundRouteId)
            }
            throw cancelled
        }
        return when (outcome) {
            is RemoteCallOutcome.Ok -> {
                (outcome.result?.get("maxSlots") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let {
                    remoteMaxSlotsByDevice[deviceId] = it
                }
                val parsed = (outcome.result?.get("worker") as? JsonObject)?.let {
                    parseSummary(it, deviceId, blueprint.deviceName, blueprint.avatar)
                }
                val effective = (parsed ?: initialRecord).let { rec ->
                    if (rec.title.isBlank() || rec.title == workerId) {
                        rec.copy(title = initialRecord.title)
                    } else {
                        rec
                    }
                }
                taskStore.upsertWorker(effective)
                localLastProgressAtMs[workerId] = clock()
                if (effective.status == WorkerStatus.RUNNING) {
                    restartWatchdog(workerId)
                }
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(workerId, effective.status, effective.activeStepText))
                effective
            }

            is RemoteCallOutcome.Failed -> {
                cleanupFailedStart(workerId, boundRouteId)
                if (outcome.error.code == "conflict" && outcome.error.message.contains("full", ignoreCase = true)) {
                    val existing = try {
                        listRemoteWorkers(deviceId, blueprint.botId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logWarn("RemoteWorkerClient", "Failed to list remote workers after pool full", error)
                        emptyList()
                    }
                    throw WorkerPoolFullException(
                        botId = blueprint.botId,
                        existing = existing.map {
                            WorkerSlotSummary(
                                workerId = it.workerId,
                                title = it.title,
                                status = it.status,
                                updatedAt = it.updatedAt,
                            )
                        },
                        maxSlots = remoteMaxSlotsByDevice[deviceId] ?: effectiveMaxSlotsPerBot(),
                    )
                }
                throw mapRemoteFailure(outcome.error, workerId)
            }
        }
    }

    override suspend fun continueWorker(
        blueprint: BotBlueprint,
        worker: WorkerRunRecord,
        message: String,
        images: List<ImagePart>,
        notifySuper: Boolean,
        silentPolling: Boolean,
    ): WorkerRunRecord {
        val deviceId = blueprint.deviceId?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("blueprint is not remote")
        if (worker.status.isBusyForCommands) {
            throw WorkerConflictException(worker.workerId, "Worker ${worker.workerId} is busy (${worker.status.name})")
        }
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty() || images.isNotEmpty()) { "message must not be empty" }
        val nowMs = clock()
        val optimistic = worker.copy(
            status = WorkerStatus.RUNNING,
            turnCount = worker.turnCount + 1,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
            lastTurnSilent = false,
            lastError = null,
            activeStepText = null,
        )
        taskStore.upsertWorker(optimistic)

        val params = buildJsonObject {
            put("botId", blueprint.botId)
            put("workerId", worker.workerId)
            put("message", cleanMessage)
            if (images.isNotEmpty()) {
                put("images", RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }))
            }
        }
        var boundRouteId: String? = null
        val previousNotify = workerNotifySuper[worker.workerId]
        val previousSilent = workerSilentPolling[worker.workerId]
        val snapshotBeforeTurn = captureSessionState(worker.workerId)
        workerNotifySuper[worker.workerId] = notifySuper
        workerSilentPolling[worker.workerId] = silentPolling
        turnProducedArtifactOrCard.remove(worker.workerId)
        activeRemoteToolCallIds.remove(worker.workerId)
        compactingWorkers.remove(worker.workerId)
        retryingWorkers.remove(worker.workerId)
        cancelQuestionTimeout(worker.workerId)
        questionEscalatedToSuper.remove(worker.workerId)
        questionAskedLocalAtMs.remove(worker.workerId)
        localLastProgressAtMs[worker.workerId] = nowMs
        beginOptimisticTurn(worker.workerId, formatUserTurnDisplay(cleanMessage, images), nowMs)
        val outcome = try {
            transport.callWithOutcome(deviceId, "worker.continue", params) { routeId ->
                boundRouteId = routeId
                routeToWorker[routeId] = ActiveRemoteStream(
                    routeId = routeId,
                    workerId = worker.workerId,
                    deviceId = deviceId,
                    persistent = false,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                rollbackFailedContinue(worker, boundRouteId, previousNotify, previousSilent, snapshotBeforeTurn)
            }
            throw cancelled
        }
        return when (outcome) {
            is RemoteCallOutcome.Ok -> {
                val parsed = (outcome.result?.get("worker") as? JsonObject)?.let {
                    parseSummary(it, deviceId, blueprint.deviceName, blueprint.avatar)
                }
                val baselineSeq = (outcome.result?.get("seq") as? JsonPrimitive)?.longOrNull
                val merged = optimistic.copy(
                    status = parsed?.status ?: WorkerStatus.RUNNING,
                    turnCount = parsed?.turnCount ?: optimistic.turnCount,
                    updatedAt = parsed?.updatedAt ?: nowMs,
                    remoteLastSeq = if (baselineSeq != null && baselineSeq < worker.remoteLastSeq) {
                        baselineSeq
                    } else {
                        optimistic.remoteLastSeq
                    },
                )
                taskStore.upsertWorker(merged)
                localLastProgressAtMs[worker.workerId] = clock()
                if (merged.status == WorkerStatus.RUNNING) {
                    restartWatchdog(worker.workerId)
                }
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(worker.workerId, merged.status, merged.activeStepText))
                merged
            }

            is RemoteCallOutcome.Failed -> {
                rollbackFailedContinue(worker, boundRouteId, previousNotify, previousSilent, snapshotBeforeTurn)
                throw mapRemoteFailure(outcome.error, worker.workerId)
            }
        }
    }

    private suspend fun cleanupFailedStart(workerId: String, boundRouteId: String?) {
        boundRouteId?.let { routeToWorker.remove(it) }
        workerNotifySuper.remove(workerId)
        workerSilentPolling.remove(workerId)
        turnProducedArtifactOrCard.remove(workerId)
        activeRemoteToolCallIds.remove(workerId)
        compactingWorkers.remove(workerId)
        retryingWorkers.remove(workerId)
        questionEscalatedToSuper.remove(workerId)
        questionAskedLocalAtMs.remove(workerId)
        localLastProgressAtMs.remove(workerId)
        synchronized(sessionStateLock) {
            sessionBuffers.remove(workerId)
            sessionFlows.remove(workerId)
        }
        taskStore.deleteWorker(workerId)
    }

    private suspend fun rollbackFailedContinue(
        worker: WorkerRunRecord,
        boundRouteId: String?,
        previousNotify: Boolean?,
        previousSilent: Boolean?,
        snapshotBeforeTurn: SessionStateBackup,
    ) {
        boundRouteId?.let { routeToWorker.remove(it) }
        if (previousNotify == null) {
            workerNotifySuper.remove(worker.workerId)
        } else {
            workerNotifySuper[worker.workerId] = previousNotify
        }
        if (previousSilent == null) {
            workerSilentPolling.remove(worker.workerId)
        } else {
            workerSilentPolling[worker.workerId] = previousSilent
        }
        restoreSessionState(worker.workerId, snapshotBeforeTurn)
        taskStore.upsertWorker(worker)
    }

    override suspend fun steerWorker(
        worker: WorkerRunRecord,
        message: String,
        images: List<ImagePart>,
        silentPolling: Boolean,
    ) {
        val deviceId = worker.deviceId ?: return
        val cleanMessage = message.trim()
        require(cleanMessage.isNotEmpty() || images.isNotEmpty()) { "message must not be empty" }
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("message", cleanMessage)
            if (images.isNotEmpty()) {
                put("images", RelayFrames.imagesArray(images.map { RouteImagePart(it.data, it.mimeType) }))
            }
        }
        requireOk(transport.callWithOutcome(deviceId, "worker.steer", params), worker.workerId)
        val nowMs = clock()
        workerSilentPolling[worker.workerId] = (workerSilentPolling[worker.workerId] == true) && silentPolling
        localLastProgressAtMs[worker.workerId] = nowMs
        appendSteeredTurnToSession(worker.workerId, formatUserTurnDisplay(cleanMessage, images), nowMs)
        restartWatchdog(worker.workerId)
    }

    override suspend fun stopWorker(worker: WorkerRunRecord) {
        val deviceId = worker.deviceId ?: return
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
        }
        requireOk(transport.callWithOutcome(deviceId, "worker.abort", params), worker.workerId)
        cancelWatchdog(worker.workerId)
        cancelQuestionTimeout(worker.workerId)
        questionEscalatedToSuper.remove(worker.workerId)
        questionAskedLocalAtMs.remove(worker.workerId)
        activeRemoteToolCallIds.remove(worker.workerId)
        compactingWorkers.remove(worker.workerId)
        retryingWorkers.remove(worker.workerId)
        workerSilentPolling.remove(worker.workerId)
        turnProducedArtifactOrCard.remove(worker.workerId)
        val nowMs = clock()
        taskStore.updateWorker(worker.workerId) { current ->
            current.copy(
                status = WorkerStatus.IDLE,
                lastOutcome = WorkerOutcome.ABORTED,
                lastTurnSilent = false,
                activeStepText = null,
                pendingQuestion = null,
                updatedAt = nowMs,
            )
        }
        _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(worker.workerId, WorkerStatus.IDLE, null))
    }

    override suspend fun compactWorker(worker: WorkerRunRecord): WorkerRunRecord {
        val deviceId = worker.deviceId ?: throw IllegalArgumentException("blueprint is not remote")
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
        }
        val result = requireOk(transport.callWithOutcome(deviceId, "worker.compact", params), worker.workerId)
        val parsed = (result["worker"] as? JsonObject)?.let {
            parseSummary(it, deviceId, worker.deviceName, worker.botAvatar)
        } ?: worker
        val usage = parseUsage(result["usage"] as? JsonObject) ?: parseUsage((result["worker"] as? JsonObject)?.get("usage") as? JsonObject)
        val updated = parsed.copy(updatedAt = maxOf(parsed.updatedAt, clock()))
        taskStore.upsertWorker(updated)
        if (usage != null) {
            updateSessionBuffer(updated.workerId) { buf ->
                buf.usage = usage
            }
        }
        runCatching { inspectWorker(updated, tail = 50) }
        return taskStore.currentWorker(updated.workerId) ?: updated
    }

    override suspend fun setWorkerModel(
        worker: WorkerRunRecord,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        val deviceId = worker.deviceId ?: throw IllegalArgumentException("blueprint is not remote")
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("providerId", providerId)
            put("modelId", modelId)
            put("thinkingLevel", thinkingLevel.name)
        }
        val result = requireOk(transport.callWithOutcome(deviceId, "worker.set_model", params), worker.workerId)
        updateSessionBuffer(worker.workerId) { buf ->
            buf.providerId = result.str("providerId") ?: providerId
            buf.modelId = result.str("modelId") ?: modelId
            buf.modelLabel = buf.modelId
            buf.thinkingLevel = result.str("thinkingLevel")?.let(::parseThinkingLevelOrNull) ?: thinkingLevel
        }
        (result["worker"] as? JsonObject)?.let { summaryJson ->
            parseSummary(summaryJson, deviceId, worker.deviceName, worker.botAvatar)?.let { taskStore.upsertWorker(it) }
        }
    }

    override suspend fun setWorkerThinking(worker: WorkerRunRecord, thinkingLevel: ThinkingLevel) {
        val deviceId = worker.deviceId ?: throw IllegalArgumentException("blueprint is not remote")
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("thinkingLevel", thinkingLevel.name)
        }
        val result = requireOk(transport.callWithOutcome(deviceId, "worker.set_model", params), worker.workerId)
        updateSessionBuffer(worker.workerId) { buf ->
            buf.thinkingLevel = result.str("thinkingLevel")?.let(::parseThinkingLevelOrNull) ?: thinkingLevel
        }
    }

    override suspend fun answerApproval(
        worker: WorkerRunRecord,
        requestId: String,
        answers: List<QuestionAnswer>,
        bySuper: Boolean,
    ) {
        if (bySuper && questionEscalatedToSuper[worker.workerId] != true) {
            throw WorkerConflictException(worker.workerId, "Worker approval $requestId is still waiting for user confirmation")
        }
        val deviceId = worker.deviceId ?: return
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("requestId", requestId)
            put("answers", RelayFrames.questionAnswersArray(answers))
        }
        requireOk(transport.callWithOutcome(deviceId, "worker.approval", params), worker.workerId)
        cancelQuestionTimeout(worker.workerId)
        questionEscalatedToSuper.remove(worker.workerId)
        questionAskedLocalAtMs.remove(worker.workerId)
        val nowMs = clock()
        localLastProgressAtMs[worker.workerId] = nowMs
        taskStore.updateWorker(worker.workerId) { current ->
            current.copy(status = WorkerStatus.RUNNING, pendingQuestion = null, lastProgressAt = nowMs, updatedAt = nowMs)
        }
        restartWatchdog(worker.workerId)
        _progressEvents.tryEmit(WorkerProgressEvent.ApprovalResolved(worker.workerId, requestId))
    }

    override suspend fun dismissApproval(
        worker: WorkerRunRecord,
        requestId: String,
        bySuper: Boolean,
    ) {
        if (bySuper && questionEscalatedToSuper[worker.workerId] != true) {
            throw WorkerConflictException(worker.workerId, "Worker approval $requestId is still waiting for user confirmation")
        }
        val deviceId = worker.deviceId ?: return
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("requestId", requestId)
            put("dismissed", true)
        }
        requireOk(transport.callWithOutcome(deviceId, "worker.approval", params), worker.workerId)
        cancelQuestionTimeout(worker.workerId)
        questionEscalatedToSuper.remove(worker.workerId)
        questionAskedLocalAtMs.remove(worker.workerId)
        val nowMs = clock()
        localLastProgressAtMs[worker.workerId] = nowMs
        taskStore.updateWorker(worker.workerId) { current ->
            current.copy(status = WorkerStatus.RUNNING, pendingQuestion = null, lastProgressAt = nowMs, updatedAt = nowMs)
        }
        restartWatchdog(worker.workerId)
        _progressEvents.tryEmit(WorkerProgressEvent.ApprovalResolved(worker.workerId, requestId))
    }

    override suspend fun inspectWorker(worker: WorkerRunRecord, tail: Int): WorkerInspectResult {
        val deviceId = worker.deviceId ?: return WorkerInspectResult(worker, worker.steps.takeLast(tail))
        val params = buildJsonObject {
            put("botId", worker.botId)
            put("workerId", worker.workerId)
            put("tail", tail.coerceIn(1, 200))
        }
        val result = requireOk(transport.callWithOutcome(deviceId, "worker.inspect", params), worker.workerId)
        val workerJson = result["worker"] as? JsonObject
        val summary = workerJson?.let {
            parseSummary(it, deviceId, worker.deviceName, worker.botAvatar)
        } ?: worker
        val usage = parseUsage(result["usage"] as? JsonObject) ?: parseUsage(workerJson?.get("usage") as? JsonObject)
        val providerId = result.str("providerId")
        val modelId = result.str("modelId")
        val thinkingLevel = result.str("thinkingLevel")?.let(::parseThinkingLevelOrNull)
        val activeTimers = parseActiveTimers(result["activeTimers"] as? JsonArray)
        val runningJobs = parseRunningJobs(result["runningJobs"] as? JsonArray)
        val steps = (result["steps"] as? JsonArray)?.mapNotNull { parseStep(it as? JsonObject) }.orEmpty()
        val artifacts = (result["artifacts"] as? JsonArray)?.mapNotNull {
            parseInspectedArtifact(it as? JsonObject, worker)
        }.orEmpty()
        val cards = (result["cards"] as? JsonArray)?.mapNotNull {
            parseInspectedCard(it as? JsonObject, worker)
        }.orEmpty()
        cards.forEach { taskStore.upsertCard(it) }
        val merged = summary.copy(
            steps = if (steps.isNotEmpty()) steps else worker.steps,
            artifacts = if (artifacts.isNotEmpty()) artifacts else worker.artifacts,
            cards = if (cards.isNotEmpty()) cards else worker.cards,
        )
        taskStore.upsertWorker(merged)
        val rawMessages = result["messages"] as? JsonArray
        val decodedMessages = WorkerHistoryWireCodec.decodeMessages(rawMessages)
        updateSessionFromInspect(
            worker = merged,
            serverMessages = decodedMessages,
            hasServerMessages = rawMessages != null,
            usage = usage,
            providerId = providerId,
            modelId = modelId,
            thinkingLevel = thinkingLevel,
        )
        return WorkerInspectResult(
            record = merged,
            recentSteps = merged.steps.takeLast(tail),
            historyMessages = decodedMessages,
            usage = usage ?: sessionBuffers[worker.workerId]?.usage,
            activeTimers = activeTimers,
            runningJobs = runningJobs,
            questionEscalatedToSuper = isQuestionEscalatedToSuper(worker.workerId),
        )
    }

    override suspend fun deleteWorker(worker: WorkerRunRecord) {
        val deviceId = worker.deviceId
        if (deviceId != null) {
            val params = buildJsonObject {
                put("botId", worker.botId)
                put("workerId", worker.workerId)
            }
            val outcome = transport.callWithOutcome(deviceId, "worker.delete", params)
            if (outcome is RemoteCallOutcome.Failed && outcome.error.code != "not_found") {
                throw mapRemoteFailure(outcome.error, worker.workerId)
            }
        }
        cancelWatchdog(worker.workerId)
        cancelQuestionTimeout(worker.workerId)
        questionEscalatedToSuper.remove(worker.workerId)
        questionAskedLocalAtMs.remove(worker.workerId)
        activeRemoteToolCallIds.remove(worker.workerId)
        compactingWorkers.remove(worker.workerId)
        retryingWorkers.remove(worker.workerId)
        localLastProgressAtMs.remove(worker.workerId)
        workerSilentPolling.remove(worker.workerId)
        turnProducedArtifactOrCard.remove(worker.workerId)
        routeToWorker.entries.removeIf { it.value.workerId == worker.workerId }
        workerNotifySuper.remove(worker.workerId)
        reattachInFlight.remove(worker.workerId)
        synchronized(sessionStateLock) {
            sessionBuffers.remove(worker.workerId)
            sessionFlows.remove(worker.workerId)
        }
        taskStore.deleteWorker(worker.workerId)
    }

    override fun dismissWorkerError(workerId: String) {
        updateSessionBuffer(workerId) { buf ->
            buf.error = null
        }
        scope.launch {
            taskStore.updateWorker(workerId) { current ->
                current.copy(lastError = null, updatedAt = clock())
            }
        }
    }

    override fun isQuestionEscalatedToSuper(workerId: String): Boolean =
        questionEscalatedToSuper[workerId] == true

    override suspend fun onSettingsChanged() {
        val remoteWorkers = taskStore.workers.value.filter { it.deviceId != null }
        for (worker in remoteWorkers) {
            when (worker.status) {
                WorkerStatus.RUNNING, WorkerStatus.STALLED -> {
                    localLastProgressAtMs.putIfAbsent(worker.workerId, clock())
                    restartWatchdog(worker.workerId)
                }
                WorkerStatus.WAITING_INPUT -> {
                    val pq = worker.pendingQuestion
                    if (pq != null && questionEscalatedToSuper[worker.workerId] != true) {
                        val askedAt = questionAskedLocalAtMs.computeIfAbsent(worker.workerId) { clock() }
                        scheduleQuestionTimeout(worker.workerId, pq, askedAt)
                    }
                }
                else -> Unit
            }
        }
    }

    override suspend fun resolveArtifactFile(artifact: WorkerArtifactRef): File? {
        val existing = File(artifact.path)
        if (artifact.path.isNotBlank() && existing.isFile) return existing
        val deviceId = artifact.remoteDeviceId ?: return null
        val botId = artifact.remoteBotId ?: return null
        val remoteArtifactId = artifact.remoteArtifactId ?: return null
        val cacheWorkspace = File(File(remoteCacheRoot, sanitize(deviceId)), sanitize(botId))
        return when (val fetched = artifactClient.fetch(
            deviceId = deviceId,
            botId = botId,
            workspaceRoot = cacheWorkspace,
            artifactId = remoteArtifactId,
            name = artifact.name,
            expectedSize = artifact.sizeBytes,
        )) {
            is RemoteArtifactResult.Ok -> fetched.file
            else -> null
        }
    }

    private suspend fun reattachActiveWorkersForDevices(deviceIds: Set<String>) {
        val activeRemote = taskStore.workers.value.filter { worker ->
            val dId = worker.deviceId ?: return@filter false
            dId in deviceIds && worker.status.isActive && routeToWorker.values.none { it.workerId == worker.workerId }
        }
        for (worker in activeRemote) {
            if (!reattachInFlight.add(worker.workerId)) continue
            try {
                val deviceId = worker.deviceId ?: continue
                val params = buildJsonObject {
                    put("botId", worker.botId)
                    put("workerId", worker.workerId)
                    put("afterSeq", worker.remoteLastSeq)
                }
                var boundRouteId: String? = null
                var boundStream: ActiveRemoteStream? = null
                val outcome = try {
                    transport.callWithOutcome(deviceId, "worker.resume", params) { routeId ->
                        boundRouteId = routeId
                        val stream = ActiveRemoteStream(
                            routeId = routeId,
                            workerId = worker.workerId,
                            deviceId = deviceId,
                            persistent = true,
                            lastSeq = 0L,
                        )
                        boundStream = stream
                        routeToWorker[routeId] = stream
                    }
                } catch (cancelled: CancellationException) {
                    boundRouteId?.let { routeToWorker.remove(it) }
                    throw cancelled
                }
                when (outcome) {
                    is RemoteCallOutcome.Ok -> {
                        val evictedBefore = (outcome.result?.get("evictedBefore") as? JsonPrimitive)?.booleanOrNull == true
                        val remoteSeq = (outcome.result?.get("seq") as? JsonPrimitive)?.longOrNull ?: worker.remoteLastSeq
                        val seqReset = remoteSeq < worker.remoteLastSeq
                        val streamSeq = boundStream?.lastSeq ?: 0L
                        val summary = (outcome.result?.get("worker") as? JsonObject)?.let {
                            parseSummary(it, deviceId, worker.deviceName, worker.botAvatar)
                        }
                        if (summary != null) {
                            var merged = worker.copy(
                                status = summary.status,
                                lastOutcome = summary.lastOutcome,
                                lastSummary = summary.lastSummary ?: worker.lastSummary,
                                lastTurnSilent = summary.lastTurnSilent,
                                lastError = summary.lastError,
                                pendingQuestion = summary.pendingQuestion
                                    ?: worker.pendingQuestion.takeIf { summary.status == WorkerStatus.WAITING_INPUT },
                                updatedAt = summary.updatedAt,
                                remoteLastSeq = if (seqReset) {
                                    maxOf(remoteSeq, streamSeq)
                                } else {
                                    maxOf(worker.remoteLastSeq, remoteSeq, streamSeq)
                                },
                            )
                            taskStore.upsertWorker(merged)
                            if (evictedBefore || seqReset || !merged.status.isActive) {
                                merged = try {
                                    inspectWorker(merged, tail = 50).record
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    logWarn("RemoteWorkerClient", "Failed to inspect reattached worker ${merged.workerId}", error)
                                    merged
                                }
                            }
                            if (merged.status == WorkerStatus.RUNNING || merged.status == WorkerStatus.STALLED) {
                                cancelQuestionTimeout(merged.workerId)
                                questionEscalatedToSuper.remove(merged.workerId)
                                questionAskedLocalAtMs.remove(merged.workerId)
                                localLastProgressAtMs.putIfAbsent(merged.workerId, clock())
                                restartWatchdog(merged.workerId)
                            } else if (merged.status == WorkerStatus.WAITING_INPUT) {
                                cancelWatchdog(merged.workerId)
                                merged.pendingQuestion?.let { pq ->
                                    val askedAt = questionAskedLocalAtMs.computeIfAbsent(merged.workerId) { clock() }
                                    scheduleQuestionTimeout(merged.workerId, pq, askedAt)
                                }
                            } else if (!merged.status.isActive) {
                                cancelWatchdog(merged.workerId)
                                cancelQuestionTimeout(merged.workerId)
                                questionEscalatedToSuper.remove(merged.workerId)
                                questionAskedLocalAtMs.remove(merged.workerId)
                                activeRemoteToolCallIds.remove(merged.workerId)
                                compactingWorkers.remove(merged.workerId)
                                retryingWorkers.remove(merged.workerId)
                                emitSettlementMilestoneIfNeeded(merged)
                            }
                        }
                    }

                    is RemoteCallOutcome.Failed -> {
                        boundRouteId?.let { routeToWorker.remove(it) }
                    }
                }
            } finally {
                reattachInFlight.remove(worker.workerId)
            }
        }
    }

    private suspend fun handleRemoteEvent(stream: ActiveRemoteStream, seq: Long, eventJson: JsonObject) {
        if (seq > 0L && seq <= stream.lastSeq) return
        stream.lastSeq = seq
        val nowMs = clock()
        if (eventJson.str("type") == "plugin_custom_message") {
            val worker = taskStore.currentWorker(stream.workerId) ?: return
            val customType = eventJson.str("customType") ?: return
            val payloadJson = when (val elem = eventJson["payload"]) {
                is JsonPrimitive -> elem.contentOrNull ?: "{}"
                null -> "{}"
                else -> elem.toString()
            }
            val text = eventJson.str("text").orEmpty()
            val display = (eventJson["display"] as? JsonPrimitive)?.booleanOrNull ?: true
            val wasStalledOrQueued = worker.status == WorkerStatus.STALLED || worker.status == WorkerStatus.QUEUED
            if (display) {
                appendCustomCardToSession(stream.workerId, customType, payloadJson, text, nowMs)
            }
            recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            return
        }

        val decoded = RemoteEventMapping.decode(eventJson) ?: return
        val worker = taskStore.currentWorker(stream.workerId) ?: return
        val wasStalledOrQueued = worker.status == WorkerStatus.STALLED || worker.status == WorkerStatus.QUEUED

        when (decoded) {
            is RemoteBotEvent.AgentStart -> {
                synchronized(stream.textBuffer) { stream.textBuffer.clear() }
                compactingWorkers.remove(stream.workerId)
                retryingWorkers.remove(stream.workerId)
                turnProducedArtifactOrCard[stream.workerId] = false
                localLastProgressAtMs[stream.workerId] = nowMs
                taskStore.updateWorker(stream.workerId) { current ->
                    current.copy(
                        status = if (current.status == WorkerStatus.QUEUED || current.status == WorkerStatus.STALLED) {
                            WorkerStatus.RUNNING
                        } else {
                            current.status
                        },
                        lastTurnSilent = false,
                        lastProgressAt = nowMs,
                        remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                    )
                }
                restartWatchdog(stream.workerId)
                if (wasStalledOrQueued) {
                    _progressEvents.tryEmit(
                        WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.RUNNING, worker.activeStepText),
                    )
                }
            }

            is RemoteBotEvent.TextDelta -> {
                synchronized(stream.textBuffer) { stream.textBuffer.append(decoded.delta) }
                appendTextToSession(stream.workerId, decoded.delta, nowMs)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            is RemoteBotEvent.ReasoningDelta -> {
                appendReasoningToSession(stream.workerId, decoded.delta, nowMs)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            RemoteBotEvent.CompactionStart -> {
                compactingWorkers.add(stream.workerId)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            RemoteBotEvent.CompactionEnd -> {
                compactingWorkers.remove(stream.workerId)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            RemoteBotEvent.RetryStart -> {
                retryingWorkers.add(stream.workerId)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            RemoteBotEvent.RetryEnd -> {
                retryingWorkers.remove(stream.workerId)
                recordRunningStreamActivity(stream.workerId, seq, nowMs, wasStalledOrQueued, worker.activeStepText)
            }

            is RemoteBotEvent.ToolCall -> {
                if (decoded.hasArgs) {
                    activeRemoteToolCallIds.computeIfAbsent(stream.workerId) { ConcurrentHashMap.newKeySet() }.add(decoded.callId)
                }
                stream.lastToolNameByCallId[decoded.callId] = decoded.name
                applyToolCallToSession(stream.workerId, decoded.callId, decoded.name, decoded.args, nowMs)
                val step = WorkerStepSnapshot(
                    callId = decoded.callId,
                    toolName = decoded.name,
                    argsJson = decoded.args.toString(),
                    status = ToolActivityStatus.RUNNING,
                    updatedAt = nowMs,
                )
                taskStore.updateWorker(stream.workerId) { current ->
                    current.copy(
                        status = if (current.status == WorkerStatus.WAITING_INPUT) current.status else WorkerStatus.RUNNING,
                        activeStepText = decoded.name,
                        steps = (current.steps.filterNot { it.callId == step.callId } + step).takeLast(MAX_STORED_STEPS),
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                        remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                    )
                }
                if (wasStalledOrQueued) restartWatchdog(stream.workerId) else touchWatchdogProgress(stream.workerId, nowMs)
                _progressEvents.tryEmit(WorkerProgressEvent.StepUpdated(stream.workerId, step))
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.RUNNING, decoded.name))
            }

            is RemoteBotEvent.ToolResult -> {
                activeRemoteToolCallIds[stream.workerId]?.remove(decoded.callId)
                val toolName = (eventJson["name"] as? JsonPrimitive)?.contentOrNull
                    ?: stream.lastToolNameByCallId[decoded.callId]
                    ?: "tool"
                applyToolResultToSession(
                    workerId = stream.workerId,
                    callId = decoded.callId,
                    toolName = toolName,
                    output = decoded.result,
                    isError = decoded.isError,
                    details = decoded.details as? JsonObject,
                    nowMs = nowMs,
                )
                val prev = worker.steps.firstOrNull { it.callId == decoded.callId }
                val step = WorkerStepSnapshot(
                    callId = decoded.callId,
                    toolName = toolName,
                    argsJson = prev?.argsJson ?: "{}",
                    status = if (decoded.isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE,
                    outputPreview = decoded.result.take(MAX_STEP_PREVIEW),
                    isError = decoded.isError,
                    updatedAt = nowMs,
                )
                val newArtifacts = if (toolName == "present" && !decoded.isError) {
                    harvestRemotePresentedArtifacts(worker, decoded.result, nowMs)
                } else {
                    emptyList()
                }
                val newCard = if (toolName != "present" && !decoded.isError) {
                    harvestRemoteCard(worker, toolName, decoded, prev?.argsJson ?: "{}", nowMs)
                } else {
                    null
                }
                if (newArtifacts.isNotEmpty() || newCard != null) {
                    turnProducedArtifactOrCard[stream.workerId] = true
                }
                if (newCard != null) {
                    taskStore.upsertCard(newCard)
                }
                val nextStepText = sessionBuffers[stream.workerId]?.activeToolText
                val updatedWorker = taskStore.updateWorker(stream.workerId) { current ->
                    current.copy(
                        status = if (current.status == WorkerStatus.STALLED || current.status == WorkerStatus.QUEUED) {
                            WorkerStatus.RUNNING
                        } else {
                            current.status
                        },
                        activeStepText = nextStepText,
                        steps = (current.steps.filterNot { it.callId == step.callId } + step).takeLast(MAX_STORED_STEPS),
                        artifacts = (current.artifacts + newArtifacts).distinctBy { it.artifactId },
                        cards = if (newCard != null) {
                            (current.cards.filterNot { it.cardId == newCard.cardId } + newCard)
                        } else {
                            current.cards
                        },
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                        remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                    )
                }
                if (wasStalledOrQueued) restartWatchdog(stream.workerId) else touchWatchdogProgress(stream.workerId, nowMs)
                _progressEvents.tryEmit(WorkerProgressEvent.StepUpdated(stream.workerId, step))
                _progressEvents.tryEmit(
                    WorkerProgressEvent.StatusChanged(
                        stream.workerId,
                        updatedWorker?.status ?: WorkerStatus.RUNNING,
                        nextStepText,
                    ),
                )
            }

            is RemoteBotEvent.ApprovalRequest -> {
                if (decoded.resolved) {
                    cancelQuestionTimeout(stream.workerId)
                    questionEscalatedToSuper.remove(stream.workerId)
                    questionAskedLocalAtMs.remove(stream.workerId)
                    localLastProgressAtMs[stream.workerId] = nowMs
                    taskStore.updateWorker(stream.workerId) { current ->
                        current.copy(
                            status = WorkerStatus.RUNNING,
                            pendingQuestion = null,
                            updatedAt = nowMs,
                            lastProgressAt = nowMs,
                            remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                        )
                    }
                    restartWatchdog(stream.workerId)
                    _progressEvents.tryEmit(WorkerProgressEvent.ApprovalResolved(stream.workerId, decoded.approvalId))
                    _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.RUNNING, worker.activeStepText))
                } else {
                    cancelWatchdog(stream.workerId)
                    questionEscalatedToSuper.remove(stream.workerId)
                    questionAskedLocalAtMs[stream.workerId] = nowMs
                    val pq = PendingQuestion(
                        requestId = decoded.approvalId,
                        sessionId = stream.workerId,
                        toolCallId = decoded.toolCallId.orEmpty(),
                        questions = decoded.questions,
                    )
                    taskStore.updateWorker(stream.workerId) { current ->
                        current.copy(
                            status = WorkerStatus.WAITING_INPUT,
                            pendingQuestion = pq,
                            updatedAt = nowMs,
                            lastProgressAt = nowMs,
                            remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                        )
                    }
                    scheduleQuestionTimeout(stream.workerId, pq, nowMs)
                    _progressEvents.tryEmit(WorkerProgressEvent.ApprovalRequested(stream.workerId, pq))
                    _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.WAITING_INPUT, worker.activeStepText))
                }
            }

            is RemoteBotEvent.Ended -> {
                cancelWatchdog(stream.workerId)
                cancelQuestionTimeout(stream.workerId)
                questionEscalatedToSuper.remove(stream.workerId)
                questionAskedLocalAtMs.remove(stream.workerId)
                activeRemoteToolCallIds.remove(stream.workerId)
                compactingWorkers.remove(stream.workerId)
                retryingWorkers.remove(stream.workerId)
                val silentPolling = workerSilentPolling.remove(stream.workerId) == true
                val producedOutputOrCard = turnProducedArtifactOrCard.remove(stream.workerId) == true
                val aborted = decoded.status == "aborted"
                finalizeSessionRun(stream.workerId, aborted = aborted, errorMessage = null, nowMs = nowMs)
                val turnText = synchronized(stream.textBuffer) { stream.textBuffer.toString().trim() }
                val suppressMilestone = silentPolling &&
                    !aborted &&
                    !producedOutputOrCard &&
                    LocalWorkerPool.isSilentNoUpdateReply(turnText)
                val summaryText = turnText.ifBlank { worker.lastSummary.orEmpty() }
                val updated = taskStore.updateWorker(stream.workerId) { current ->
                    current.copy(
                        status = WorkerStatus.IDLE,
                        lastOutcome = if (aborted) WorkerOutcome.ABORTED else WorkerOutcome.SUCCEEDED,
                        lastSummary = if (suppressMilestone) {
                            current.lastSummary
                        } else {
                            summaryText.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_CHARS)
                        },
                        lastTurnSilent = suppressMilestone,
                        lastError = null,
                        activeStepText = null,
                        pendingQuestion = null,
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                        remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                    )
                }
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.IDLE, null))
                if (updated != null && !suppressMilestone && workerNotifySuper[updated.workerId] != false) {
                    if (aborted) {
                        _milestones.tryEmit(
                            WorkerMilestone.Aborted(
                                workerId = updated.workerId,
                                botRef = updated.botRef,
                                botName = updated.botName,
                                timestamp = nowMs,
                            ),
                        )
                    } else {
                        _milestones.tryEmit(
                            WorkerMilestone.Completed(
                                workerId = updated.workerId,
                                botRef = updated.botRef,
                                botName = updated.botName,
                                summary = summaryText.ifBlank { "Completed." },
                                artifacts = updated.artifacts,
                                cards = updated.cards,
                                timestamp = nowMs,
                            ),
                        )
                    }
                }
            }

            is RemoteBotEvent.Failed -> {
                cancelWatchdog(stream.workerId)
                cancelQuestionTimeout(stream.workerId)
                questionEscalatedToSuper.remove(stream.workerId)
                questionAskedLocalAtMs.remove(stream.workerId)
                activeRemoteToolCallIds.remove(stream.workerId)
                compactingWorkers.remove(stream.workerId)
                retryingWorkers.remove(stream.workerId)
                workerSilentPolling.remove(stream.workerId)
                turnProducedArtifactOrCard.remove(stream.workerId)
                finalizeSessionRun(stream.workerId, aborted = false, errorMessage = decoded.message, nowMs = nowMs)
                val updated = taskStore.updateWorker(stream.workerId) { current ->
                    current.copy(
                        status = WorkerStatus.FAILED,
                        lastOutcome = WorkerOutcome.FAILED,
                        lastTurnSilent = false,
                        lastError = decoded.message.take(MAX_SUMMARY_CHARS),
                        activeStepText = null,
                        pendingQuestion = null,
                        updatedAt = nowMs,
                        lastProgressAt = nowMs,
                        remoteLastSeq = maxOf(current.remoteLastSeq, seq),
                    )
                }
                _progressEvents.tryEmit(WorkerProgressEvent.StatusChanged(stream.workerId, WorkerStatus.FAILED, null))
                if (updated != null && workerNotifySuper[updated.workerId] != false) {
                    _milestones.tryEmit(
                        WorkerMilestone.Failed(
                            workerId = updated.workerId,
                            botRef = updated.botRef,
                            botName = updated.botName,
                            error = decoded.message,
                            artifacts = updated.artifacts,
                            cards = updated.cards,
                            timestamp = nowMs,
                        ),
                    )
                }
            }

            RemoteBotEvent.Settled -> {
                if (!stream.persistent) {
                    routeToWorker.remove(stream.routeId)
                }
            }

            else -> Unit
        }
    }

    private suspend fun recordRunningStreamActivity(
        workerId: String,
        seq: Long,
        nowMs: Long,
        wasStalledOrQueued: Boolean,
        activeStepText: String?,
    ) {
        taskStore.updateWorker(workerId) { current ->
            current.copy(
                status = if (current.status == WorkerStatus.STALLED || current.status == WorkerStatus.QUEUED) {
                    WorkerStatus.RUNNING
                } else {
                    current.status
                },
                lastProgressAt = nowMs,
                remoteLastSeq = maxOf(current.remoteLastSeq, seq),
            )
        }
        if (wasStalledOrQueued) restartWatchdog(workerId) else touchWatchdogProgress(workerId, nowMs)
        if (wasStalledOrQueued) {
            _progressEvents.tryEmit(
                WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.RUNNING, activeStepText),
            )
        }
    }

    private fun emitSettlementMilestoneIfNeeded(worker: WorkerRunRecord) {
        if (worker.lastTurnSilent || workerNotifySuper[worker.workerId] == false) return
        when {
            worker.status == WorkerStatus.IDLE && worker.lastOutcome == WorkerOutcome.ABORTED -> _milestones.tryEmit(
                WorkerMilestone.Aborted(
                    workerId = worker.workerId,
                    botRef = worker.botRef,
                    botName = worker.botName,
                    timestamp = clock(),
                ),
            )

            worker.status == WorkerStatus.IDLE -> _milestones.tryEmit(
                WorkerMilestone.Completed(
                    workerId = worker.workerId,
                    botRef = worker.botRef,
                    botName = worker.botName,
                    summary = worker.lastSummary ?: "Completed.",
                    artifacts = worker.artifacts,
                    cards = worker.cards,
                    timestamp = clock(),
                ),
            )

            worker.status == WorkerStatus.FAILED -> _milestones.tryEmit(
                WorkerMilestone.Failed(
                    workerId = worker.workerId,
                    botRef = worker.botRef,
                    botName = worker.botName,
                    error = worker.lastError ?: "Worker failed",
                    artifacts = worker.artifacts,
                    cards = worker.cards,
                    timestamp = clock(),
                ),
            )

            else -> Unit
        }
    }

    private fun touchWatchdogProgress(workerId: String, nowMs: Long) {
        localLastProgressAtMs[workerId] = nowMs
        startWatchdogIfNeeded(workerId, restart = false)
    }

    private fun restartWatchdog(workerId: String) {
        localLastProgressAtMs.putIfAbsent(workerId, clock())
        startWatchdogIfNeeded(workerId, restart = true)
    }

    private fun startWatchdogIfNeeded(workerId: String, restart: Boolean) {
        val timeoutMs = effectiveStallTimeoutMs()
        if (timeoutMs <= 0L) {
            synchronized(timerLock) {
                workerWatchdogs.remove(workerId)?.cancel()
            }
            return
        }
        synchronized(timerLock) {
            if (!restart && workerWatchdogs[workerId]?.isActive == true) return
            workerWatchdogs.remove(workerId)?.cancel()
            workerWatchdogs[workerId] = scope.launch {
                var lastRemindedAt = 0L
                while (true) {
                    val activeTimeoutMs = effectiveStallTimeoutMs()
                    if (activeTimeoutMs <= 0L) break
                    val current = taskStore.currentWorker(workerId) ?: break
                    if (current.status != WorkerStatus.RUNNING && current.status != WorkerStatus.STALLED) break
                    val pausedInSubstep = activeRemoteToolCallIds[workerId]?.isNotEmpty() == true ||
                        workerId in compactingWorkers ||
                        workerId in retryingWorkers
                    if (pausedInSubstep) {
                        localLastProgressAtMs[workerId] = clock()
                        delay(minOf(activeTimeoutMs, ACTIVE_WAIT_POLL_MS))
                        continue
                    }
                    val lastProgress = localLastProgressAtMs[workerId] ?: clock().also {
                        localLastProgressAtMs[workerId] = it
                    }
                    val anchor = maxOf(lastProgress, lastRemindedAt)
                    val nowMs = clock()
                    val remaining = (anchor + activeTimeoutMs) - nowMs
                    if (remaining <= 0L) {
                        val totalElapsed = (nowMs - lastProgress).coerceAtLeast(activeTimeoutMs)
                        val updated = if (current.status == WorkerStatus.RUNNING) {
                            val next = taskStore.updateWorker(workerId) { rec ->
                                if (rec.status == WorkerStatus.RUNNING) {
                                    rec.copy(status = WorkerStatus.STALLED, updatedAt = nowMs)
                                } else {
                                    rec
                                }
                            }
                            if (next != null && next.status == WorkerStatus.STALLED) {
                                _progressEvents.tryEmit(
                                    WorkerProgressEvent.StatusChanged(workerId, WorkerStatus.STALLED, next.activeStepText),
                                )
                            }
                            next ?: current
                        } else {
                            current
                        }
                        if (workerNotifySuper[workerId] != false) {
                            _milestones.tryEmit(
                                WorkerMilestone.Stalled(
                                    workerId = workerId,
                                    botRef = updated.botRef,
                                    botName = updated.botName,
                                    elapsedMs = totalElapsed,
                                    lastStepText = updated.activeStepText,
                                    timestamp = nowMs,
                                ),
                            )
                        }
                        lastRemindedAt = nowMs
                        delay(activeTimeoutMs)
                    } else {
                        delay(remaining)
                    }
                }
            }
        }
    }

    private fun cancelWatchdog(workerId: String) {
        synchronized(timerLock) {
            workerWatchdogs.remove(workerId)?.cancel()
        }
    }

    private fun scheduleQuestionTimeout(workerId: String, pq: PendingQuestion, askedAtMs: Long) {
        val timeoutMs = effectiveQuestionTimeoutMs()
        if (timeoutMs <= 0L) {
            synchronized(timerLock) {
                questionEscalationJobs.remove(workerId)?.cancel()
            }
            return
        }
        synchronized(timerLock) {
            questionEscalationJobs.remove(workerId)?.cancel()
            val reqId = pq.requestId
            questionEscalationJobs[workerId] = scope.launch {
                val remaining = (askedAtMs + timeoutMs - clock()).coerceAtLeast(0L)
                if (remaining > 0L) delay(remaining)
                val current = taskStore.currentWorker(workerId) ?: return@launch
                if (current.status != WorkerStatus.WAITING_INPUT) return@launch
                val currentQ = current.pendingQuestion ?: return@launch
                if (currentQ.requestId != reqId || questionEscalatedToSuper[workerId] == true) return@launch
                questionEscalatedToSuper[workerId] = true
                val nowMs = clock()
                if (workerNotifySuper[workerId] != false) {
                    _milestones.tryEmit(
                        WorkerMilestone.WaitingInputTimeout(
                            workerId = workerId,
                            botRef = current.botRef,
                            botName = current.botName,
                            requestId = currentQ.requestId,
                            questions = currentQ.questions,
                            waitedMs = (nowMs - askedAtMs).coerceAtLeast(timeoutMs),
                            timestamp = nowMs,
                        ),
                    )
                }
            }
        }
    }

    private fun cancelQuestionTimeout(workerId: String) {
        synchronized(timerLock) {
            questionEscalationJobs.remove(workerId)?.cancel()
        }
    }

    private fun effectiveStallTimeoutMs(): Long =
        settingsProvider?.invoke()?.stallTimeoutMs ?: stallTimeoutMs

    private fun effectiveQuestionTimeoutMs(): Long =
        settingsProvider?.invoke()?.questionTimeoutMs ?: questionTimeoutMs

    private fun effectiveMaxSlotsPerBot(): Int =
        (settingsProvider?.invoke()?.maxSlotsPerBot ?: SuperRuntime.MAX_WORKERS_PER_BOT).coerceAtLeast(1)

    private fun harvestRemotePresentedArtifacts(
        worker: WorkerRunRecord,
        rawResult: String,
        nowMs: Long,
    ): List<WorkerArtifactRef> {
        val root = runCatching { JSON.parseToJsonElement(rawResult) as? JsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        val out = ArrayList<WorkerArtifactRef>()
        for (element in items) {
            val obj = element as? JsonObject ?: continue
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.trim() ?: continue
            if (type !in STAGEABLE_ARTIFACT_TYPES) continue
            val remoteArtifactId = (obj["artifactId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim() ?: remoteArtifactId
            val mime = (obj["mime"] as? JsonPrimitive)?.contentOrNull?.trim()
            val title = (obj["title"] as? JsonPrimitive)?.contentOrNull?.trim()
            val size = (obj["artifactSize"] as? JsonPrimitive)?.longOrNull
            out += WorkerArtifactRef(
                artifactId = remoteArtifactId,
                workerId = worker.workerId,
                botRef = worker.botRef,
                kind = type,
                path = "",
                name = name,
                title = title,
                mimeType = mime,
                sizeBytes = size,
                remoteDeviceId = worker.deviceId,
                remoteBotId = worker.botId,
                remoteArtifactId = remoteArtifactId,
                createdAt = nowMs,
            )
        }
        return out
    }

    private fun harvestRemoteCard(
        worker: WorkerRunRecord,
        toolName: String,
        result: RemoteBotEvent.ToolResult,
        argsJson: String,
        nowMs: Long,
    ): WorkerCardRef? {
        val details = result.details as? JsonObject ?: return null
        val cardDescriptor = details[CARD_DESCRIPTOR_KEY] as? JsonObject
        val structured = ToolResultDetails.structuredContent(details)
        if (cardDescriptor == null && structured == null) return null
        val title = (cardDescriptor?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ((structured as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: toolName
        val payloadJson = (cardDescriptor ?: structured).toString()
        return WorkerCardRef(
            cardId = result.callId,
            workerId = worker.workerId,
            botRef = worker.botRef,
            toolName = toolName,
            title = title,
            argsJson = argsJson,
            outputText = result.result,
            detailsJson = details.toString(),
            payloadJson = payloadJson,
            createdAt = nowMs,
        )
    }

    private fun parseSummary(
        json: JsonObject?,
        deviceId: String,
        deviceName: String,
        avatar: String? = null,
    ): WorkerRunRecord? {
        if (json == null) return null
        val workerId = json.str("workerId") ?: return null
        val botId = json.str("botId") ?: return null
        val botName = json.str("botName") ?: botId
        val pq = (json["pendingQuestion"] as? JsonObject)?.let { pqJson ->
            val requestId = pqJson.str("requestId")
            val questions = RelayFrames.parseQuestions(pqJson["questions"])
            if (requestId != null && questions.isNotEmpty()) {
                PendingQuestion(
                    requestId = requestId,
                    sessionId = workerId,
                    toolCallId = pqJson.str("toolCallId").orEmpty(),
                    questions = questions,
                )
            } else {
                null
            }
        }
        return WorkerRunRecord(
            workerId = workerId,
            botRef = SuperRuntime.remoteBotRef(deviceId, botId),
            botId = botId,
            botName = botName,
            botAvatar = avatar,
            deviceId = deviceId,
            deviceName = deviceName,
            title = json.str("title") ?: workerId,
            status = json.str("status")?.let { parseEnumOrNull<WorkerStatus>(it) } ?: WorkerStatus.IDLE,
            lastOutcome = json.str("lastOutcome")?.let { parseEnumOrNull<WorkerOutcome>(it) }
                ?: WorkerOutcome.NONE,
            turnCount = json.long("turnCount")?.toInt() ?: 1,
            createdAt = json.long("createdAt") ?: 0L,
            updatedAt = json.long("updatedAt") ?: 0L,
            lastProgressAt = json.long("lastProgressAt") ?: 0L,
            lastSummary = json.str("lastSummary"),
            lastTurnSilent = (json["lastTurnSilent"] as? JsonPrimitive)?.booleanOrNull == true,
            lastError = json.str("lastError"),
            activeStepText = json.str("activeStepText"),
            pendingQuestion = pq,
        )
    }

    private fun parseActiveTimers(array: JsonArray?): List<WorkerTimerSummary> =
        array.orEmpty().mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            WorkerTimerSummary(
                id = id,
                name = obj.str("name") ?: id,
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                nextRunAt = obj.long("nextRunAt"),
            )
        }

    private fun parseRunningJobs(array: JsonArray?): List<WorkerJobSummary> =
        array.orEmpty().mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val jobId = obj.str("jobId") ?: return@mapNotNull null
            val status = obj.str("status")?.let { parseEnumOrNull<JobStatus>(it) } ?: JobStatus.RUNNING
            WorkerJobSummary(
                jobId = jobId,
                command = obj.str("command").orEmpty(),
                status = status,
            )
        }

    private fun parseUsage(json: JsonObject?): SessionUsage? {
        if (json == null) return null
        val contextWindow = json.long("contextWindow") ?: 0L
        val contextTokens = json.long("contextTokens") ?: 0L
        return SessionUsage(
            contextTokens = contextTokens,
            contextWindow = contextWindow,
            inputTokens = json.long("inputTokens") ?: 0L,
            outputTokens = json.long("outputTokens") ?: 0L,
            cacheReadTokens = json.long("cacheReadTokens") ?: 0L,
            cacheWriteTokens = json.long("cacheWriteTokens") ?: 0L,
            cost = json.double("cost") ?: 0.0,
        )
    }

    private fun parseStep(json: JsonObject?): WorkerStepSnapshot? {
        if (json == null) return null
        val callId = json.str("callId") ?: return null
        val toolName = json.str("toolName") ?: return null
        return WorkerStepSnapshot(
            callId = callId,
            toolName = toolName,
            argsJson = json.str("argsJson") ?: "{}",
            status = json.str("status")?.let { parseEnumOrNull<ToolActivityStatus>(it) }
                ?: ToolActivityStatus.DONE,
            outputPreview = json.str("outputPreview"),
            isError = (json["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            updatedAt = json.long("updatedAt") ?: 0L,
        )
    }

    private inline fun <reified E : Enum<E>> parseEnumOrNull(raw: String): E? =
        enumValues<E>().firstOrNull { it.name == raw }

    private fun parseThinkingLevelOrNull(raw: String): ThinkingLevel? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        if (clean.equals("minimal", ignoreCase = true)) return ThinkingLevel.LOW
        return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
    }

    private fun parseInspectedArtifact(json: JsonObject?, worker: WorkerRunRecord): WorkerArtifactRef? {
        if (json == null) return null
        val id = json.str("artifactId") ?: return null
        val remoteArtifactId = json.str("remoteArtifactId") ?: id
        return WorkerArtifactRef(
            artifactId = id,
            workerId = worker.workerId,
            botRef = worker.botRef,
            kind = json.str("kind") ?: "file",
            path = "",
            name = json.str("name") ?: id,
            title = json.str("title"),
            mimeType = json.str("mimeType"),
            sizeBytes = json.long("sizeBytes"),
            remoteDeviceId = worker.deviceId,
            remoteBotId = worker.botId,
            remoteArtifactId = remoteArtifactId,
            createdAt = worker.updatedAt,
        )
    }

    private fun parseInspectedCard(json: JsonObject?, worker: WorkerRunRecord): WorkerCardRef? {
        if (json == null) return null
        val cardId = json.str("cardId") ?: return null
        val toolName = json.str("toolName") ?: return null
        return WorkerCardRef(
            cardId = cardId,
            workerId = worker.workerId,
            botRef = worker.botRef,
            toolName = toolName,
            title = json.str("title"),
            argsJson = json.str("argsJson") ?: "{}",
            outputText = json.str("outputText").orEmpty(),
            detailsJson = json.str("detailsJson").orEmpty(),
            payloadJson = json.str("payloadJson") ?: "{}",
            createdAt = json.long("createdAt") ?: worker.updatedAt,
        )
    }

    private fun requireOk(outcome: RemoteCallOutcome, workerId: String = ""): JsonObject = when (outcome) {
        is RemoteCallOutcome.Ok -> outcome.result ?: JsonObject(emptyMap())
        is RemoteCallOutcome.Failed -> throw mapRemoteFailure(outcome.error, workerId)
    }

    private fun mapRemoteFailure(error: RemoteCallError, workerId: String): Throwable =
        when (error.code) {
            "not_found" -> WorkerNotFoundException(workerId)
            "conflict" -> WorkerConflictException(workerId, error.message)
            "bad_request" -> IllegalArgumentException(error.message)
            RemoteCallCodes.DISCONNECTED, RemoteCallCodes.NOT_CONNECTED, RemoteCallCodes.TIMEOUT ->
                IllegalStateException(error.message)
            else -> IllegalStateException("${error.code}: ${error.message}")
        }

    private fun deviceName(deviceId: String): String =
        transport.devices.value.firstOrNull { it.deviceId == deviceId }?.name ?: deviceId

    private fun summarizeTitle(task: String): String {
        val normalized = task.trim().replace(Regex("\\s+"), " ")
        return if (normalized.isEmpty()) "Worker task" else if (normalized.length <= 42) normalized else normalized.take(42) + "…"
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "__").ifBlank { "x" }.take(64)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private data class SessionStateBackup(
        val committed: List<AssistantMessage>,
        val liveUsers: List<AssistantMessage>,
        val streamingAssistant: AssistantMessage?,
        val activeToolText: String?,
        val loaded: Boolean,
        val streaming: Boolean,
        val error: String?,
    )

    private fun updateSessionBuffer(workerId: String, transform: (RemoteSessionBuffer) -> Unit) {
        synchronized(sessionStateLock) {
            val buf = sessionBuffers.getOrPut(workerId) { RemoteSessionBuffer() }
            transform(buf)
            publishSnapshotLocked(workerId, buf)
        }
    }

    private fun captureSessionState(workerId: String): SessionStateBackup = synchronized(sessionStateLock) {
        val buf = sessionBuffers[workerId] ?: return SessionStateBackup(
            committed = emptyList(),
            liveUsers = emptyList(),
            streamingAssistant = null,
            activeToolText = null,
            loaded = false,
            streaming = false,
            error = null,
        )
        SessionStateBackup(
            committed = buf.committedMessages,
            liveUsers = buf.liveUserMessages.toList(),
            streamingAssistant = buf.streamingAssistant,
            activeToolText = buf.activeToolText,
            loaded = buf.loaded,
            streaming = buf.streaming,
            error = buf.error,
        )
    }

    private fun restoreSessionState(workerId: String, backup: SessionStateBackup) {
        updateSessionBuffer(workerId) { buf ->
            buf.committedMessages = backup.committed
            buf.liveUserMessages.clear()
            buf.liveUserMessages.addAll(backup.liveUsers)
            buf.streamingAssistant = backup.streamingAssistant
            buf.activeToolText = backup.activeToolText
            buf.loaded = backup.loaded
            buf.streaming = backup.streaming
            buf.error = backup.error
        }
    }

    private fun beginOptimisticTurn(workerId: String, userText: String, nowMs: Long) {
        updateSessionBuffer(workerId) { buf ->
            buf.liveUserMessages += AssistantMessage(
                id = buf.nextLocalId--,
                role = AssistantRole.USER,
                text = userText,
                startedAt = nowMs,
            )
            buf.streamingAssistant = AssistantMessage(
                id = buf.nextLocalId--,
                role = AssistantRole.ASSISTANT,
                text = "",
                streaming = true,
                steps = emptyList(),
                status = MessageStatus.RUNNING,
                startedAt = nowMs,
            )
            buf.streaming = true
            buf.activeToolText = null
            buf.error = null
        }
    }

    private fun appendSteeredTurnToSession(workerId: String, userText: String, nowMs: Long) {
        updateSessionBuffer(workerId) { buf ->
            val updatedCommitted = buf.committedMessages.toMutableList()
            if (buf.liveUserMessages.isNotEmpty()) {
                updatedCommitted += buf.liveUserMessages
                buf.liveUserMessages.clear()
            }
            val currentAssistant = buf.streamingAssistant
            if (currentAssistant != null &&
                (currentAssistant.text.isNotBlank() || currentAssistant.steps.isNotEmpty())
            ) {
                updatedCommitted += currentAssistant.copy(
                    streaming = false,
                    steps = finalizeRunningSteps(currentAssistant.steps, failed = false),
                    status = MessageStatus.DONE,
                    finishedAt = nowMs,
                )
            }
            updatedCommitted += AssistantMessage(
                id = buf.nextLocalId--,
                role = AssistantRole.USER,
                text = userText,
                startedAt = nowMs,
            )
            buf.committedMessages = updatedCommitted
            buf.streamingAssistant = null
            buf.activeToolText = null
            buf.streaming = true
        }
    }

    private fun appendTextToSession(workerId: String, delta: String, nowMs: Long) {
        if (delta.isEmpty()) return
        updateSessionBuffer(workerId) { buf ->
            val assistant = ensureStreamingAssistantLocked(buf, nowMs)
            val steps = assistant.steps.toMutableList()
            val last = steps.lastOrNull()
            if (last is AssistantStep.Text) {
                steps[steps.lastIndex] = last.copy(text = last.text + delta)
            } else {
                steps += AssistantStep.Text(buf.stepIdCounter++, delta)
            }
            buf.streamingAssistant = assistant.copy(
                text = assistant.text + delta,
                steps = steps,
            )
            buf.streaming = true
            buf.error = null
        }
    }

    private fun appendCustomCardToSession(
        workerId: String,
        customType: String,
        payload: String,
        text: String,
        nowMs: Long,
    ) {
        updateSessionBuffer(workerId) { buf ->
            val assistant = ensureStreamingAssistantLocked(buf, nowMs)
            val steps = assistant.steps.toMutableList()
            steps += AssistantStep.CustomCard(
                id = buf.stepIdCounter++,
                customType = customType,
                payload = payload,
                text = text,
            )
            buf.streamingAssistant = assistant.copy(steps = steps)
            buf.streaming = true
        }
    }

    private fun appendReasoningToSession(workerId: String, delta: String, nowMs: Long) {
        if (delta.isEmpty()) return
        updateSessionBuffer(workerId) { buf ->
            val assistant = ensureStreamingAssistantLocked(buf, nowMs)
            val steps = assistant.steps.toMutableList()
            val last = steps.lastOrNull()
            if (last is AssistantStep.Reasoning) {
                steps[steps.lastIndex] = last.copy(text = last.text + delta)
            } else {
                steps += AssistantStep.Reasoning(buf.stepIdCounter++, delta)
            }
            buf.streamingAssistant = assistant.copy(steps = steps)
            buf.streaming = true
        }
    }

    private fun applyToolCallToSession(
        workerId: String,
        callId: String,
        name: String,
        args: JsonObject,
        nowMs: Long,
    ) {
        val argumentsMap = args.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        updateSessionBuffer(workerId) { buf ->
            val assistant = ensureStreamingAssistantLocked(buf, nowMs)
            val steps = assistant.steps.toMutableList()
            val trace = ToolTrace(
                id = buf.stepIdCounter++,
                name = name,
                arguments = argumentsMap,
                status = ToolActivityStatus.RUNNING,
                toolCallId = callId,
                startedAt = nowMs,
                rawArguments = args,
            )
            val existingIndex = steps.indexOfFirst {
                it is AssistantStep.Tool && it.trace.toolCallId == callId && callId.isNotBlank()
            }
            if (existingIndex >= 0) {
                steps[existingIndex] = AssistantStep.Tool(trace)
            } else {
                steps += AssistantStep.Tool(trace)
            }
            buf.streamingAssistant = assistant.copy(steps = steps)
            buf.activeToolText = name
            buf.streaming = true
        }
    }

    private fun applyToolResultToSession(
        workerId: String,
        callId: String,
        toolName: String,
        output: String,
        isError: Boolean,
        details: JsonObject?,
        nowMs: Long,
    ) {
        updateSessionBuffer(workerId) { buf ->
            val assistant = ensureStreamingAssistantLocked(buf, nowMs)
            val steps = assistant.steps.toMutableList()
            val idx = steps.indexOfFirst {
                it is AssistantStep.Tool && it.trace.toolCallId == callId && callId.isNotBlank()
            }
            val payload = if (output.isBlank()) null else ToolResultPayload.Text(output)
            if (idx >= 0) {
                val prev = (steps[idx] as AssistantStep.Tool).trace
                steps[idx] = AssistantStep.Tool(
                    prev.copy(
                        name = toolName.ifBlank { prev.name },
                        status = if (isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE,
                        output = if (isError) null else output,
                        error = if (isError) output else null,
                        durationMs = (nowMs - prev.startedAt).coerceAtLeast(0L),
                        resultPayload = payload,
                        resultDetails = details ?: prev.resultDetails,
                    ),
                )
            } else {
                steps += AssistantStep.Tool(
                    ToolTrace(
                        id = buf.stepIdCounter++,
                        name = toolName,
                        arguments = emptyMap(),
                        status = if (isError) ToolActivityStatus.FAILED else ToolActivityStatus.DONE,
                        output = if (isError) null else output,
                        error = if (isError) output else null,
                        toolCallId = callId,
                        startedAt = nowMs,
                        durationMs = 0L,
                        resultPayload = payload,
                        resultDetails = details,
                    ),
                )
            }
            buf.streamingAssistant = assistant.copy(steps = steps)
            val nextRunning = steps.filterIsInstance<AssistantStep.Tool>()
                .lastOrNull { it.trace.status == ToolActivityStatus.RUNNING }
                ?.trace
                ?.name
            buf.activeToolText = nextRunning
        }
    }

    private fun finalizeSessionRun(
        workerId: String,
        aborted: Boolean,
        errorMessage: String?,
        nowMs: Long,
    ) {
        updateSessionBuffer(workerId) { buf ->
            val updatedCommitted = buf.committedMessages.toMutableList()
            if (buf.liveUserMessages.isNotEmpty()) {
                updatedCommitted += buf.liveUserMessages
                buf.liveUserMessages.clear()
            }
            val currentAssistant = buf.streamingAssistant
            if (errorMessage != null) {
                val base = currentAssistant ?: AssistantMessage(
                    id = buf.nextLocalId--,
                    role = AssistantRole.ASSISTANT,
                    text = "",
                    startedAt = nowMs,
                )
                updatedCommitted += base.copy(
                    streaming = false,
                    steps = finalizeRunningSteps(base.steps, failed = true),
                    status = MessageStatus.FAILED,
                    finishedAt = nowMs,
                    error = errorMessage,
                    rawError = errorMessage,
                )
            } else if (currentAssistant != null &&
                (currentAssistant.text.isNotBlank() || currentAssistant.steps.isNotEmpty())
            ) {
                updatedCommitted += currentAssistant.copy(
                    streaming = false,
                    steps = finalizeRunningSteps(currentAssistant.steps, failed = false),
                    status = if (aborted) MessageStatus.STOPPED else MessageStatus.DONE,
                    finishedAt = nowMs,
                )
            }
            buf.committedMessages = updatedCommitted
            buf.streamingAssistant = null
            buf.streaming = false
            buf.activeToolText = null
            buf.loaded = true
            buf.error = errorMessage
        }
    }

    private fun updateSessionFromInspect(
        worker: WorkerRunRecord,
        serverMessages: List<AssistantMessage>,
        hasServerMessages: Boolean,
        usage: SessionUsage? = null,
        providerId: String? = null,
        modelId: String? = null,
        thinkingLevel: ThinkingLevel? = null,
    ) {
        val isBusy = worker.status.isBusyForCommands
        updateSessionBuffer(worker.workerId) { buf ->
            buf.loaded = true
            buf.error = worker.lastError
            if (usage != null) {
                buf.usage = usage
            }
            if (!providerId.isNullOrBlank()) {
                buf.providerId = providerId
            }
            if (!modelId.isNullOrBlank()) {
                buf.modelId = modelId
                buf.modelLabel = modelId
            }
            if (thinkingLevel != null) {
                buf.thinkingLevel = thinkingLevel
            }
            if (hasServerMessages) {
                if (!isBusy) {
                    buf.committedMessages = serverMessages
                    buf.liveUserMessages.clear()
                    buf.streamingAssistant = null
                    buf.streaming = false
                    buf.activeToolText = null
                } else if (!buf.streaming && buf.streamingAssistant == null && buf.liveUserMessages.isEmpty()) {
                    val last = serverMessages.lastOrNull()
                    if (last != null &&
                        last.role == AssistantRole.ASSISTANT &&
                        (last.streaming || last.status == MessageStatus.RUNNING)
                    ) {
                        buf.committedMessages = serverMessages.dropLast(1)
                        buf.streamingAssistant = last.copy(streaming = true, status = MessageStatus.RUNNING)
                    } else {
                        buf.committedMessages = serverMessages
                    }
                    buf.streaming = true
                    buf.activeToolText = worker.activeStepText
                }
            } else if (!buf.streaming && buf.committedMessages.isEmpty() && buf.streamingAssistant == null) {
                buf.committedMessages = synthesizeFallbackMessages(buf, worker)
                buf.streaming = isBusy
                buf.activeToolText = worker.activeStepText.takeIf { isBusy }
            }
        }
    }

    private fun synthesizeFallbackMessages(
        buf: RemoteSessionBuffer,
        worker: WorkerRunRecord,
    ): List<AssistantMessage> {
        val steps = worker.steps.map { snap ->
            AssistantStep.Tool(
                ToolTrace(
                    id = buf.stepIdCounter++,
                    name = snap.toolName,
                    arguments = emptyMap(),
                    status = snap.status,
                    output = if (snap.isError) null else snap.outputPreview,
                    error = if (snap.isError) snap.outputPreview else null,
                    toolCallId = snap.callId,
                    startedAt = snap.updatedAt,
                ),
            )
        }
        val text = worker.lastSummary.orEmpty()
        if (steps.isEmpty() && text.isBlank() && worker.lastError.isNullOrBlank()) {
            return emptyList()
        }
        val status = when {
            worker.status.isBusyForCommands -> MessageStatus.RUNNING
            !worker.lastError.isNullOrBlank() || worker.lastOutcome == WorkerOutcome.FAILED -> MessageStatus.FAILED
            worker.lastOutcome == WorkerOutcome.ABORTED -> MessageStatus.STOPPED
            else -> MessageStatus.DONE
        }
        return listOf(
            AssistantMessage(
                id = buf.nextLocalId--,
                role = AssistantRole.ASSISTANT,
                text = text,
                streaming = status == MessageStatus.RUNNING,
                steps = steps,
                status = status,
                startedAt = worker.createdAt,
                finishedAt = worker.updatedAt.takeIf { status != MessageStatus.RUNNING },
                error = worker.lastError,
                rawError = worker.lastError,
            ),
        )
    }

    private fun ensureStreamingAssistantLocked(buf: RemoteSessionBuffer, nowMs: Long): AssistantMessage =
        buf.streamingAssistant ?: AssistantMessage(
            id = buf.nextLocalId--,
            role = AssistantRole.ASSISTANT,
            text = "",
            streaming = true,
            status = MessageStatus.RUNNING,
            startedAt = nowMs,
        ).also { buf.streamingAssistant = it }

    private fun finalizeRunningSteps(steps: List<AssistantStep>, failed: Boolean): List<AssistantStep> =
        steps.map { step ->
            if (step is AssistantStep.Tool && step.trace.status == ToolActivityStatus.RUNNING) {
                AssistantStep.Tool(
                    step.trace.copy(
                        status = if (failed) ToolActivityStatus.FAILED else ToolActivityStatus.DONE,
                    ),
                )
            } else {
                step
            }
        }

    private fun publishSnapshotLocked(workerId: String, buf: RemoteSessionBuffer) {
        val flow = sessionFlows.computeIfAbsent(workerId) {
            MutableStateFlow(WorkerSessionSnapshot(workerId = workerId))
        }
        flow.value = WorkerSessionSnapshot(
            workerId = workerId,
            messages = buf.committedMessages + buf.liveUserMessages + listOfNotNull(buf.streamingAssistant),
            streaming = buf.streaming,
            activeToolText = buf.activeToolText,
            providerId = buf.providerId,
            modelId = buf.modelId,
            modelLabel = buf.modelLabel,
            thinkingLevel = buf.thinkingLevel,
            thinkingLevels = buf.thinkingLevels,
            usage = buf.usage,
            loaded = buf.loaded,
            error = buf.error,
        )
    }

    private fun formatUserTurnDisplay(text: String, images: List<ImagePart>): String =
        text.ifBlank { if (images.isNotEmpty()) "[Image]" else "" }

    private companion object {
        private const val DEFAULT_STALL_TIMEOUT_MS = 10 * 60_000L
        private const val ACTIVE_WAIT_POLL_MS = 10_000L
        private const val MAX_STORED_STEPS = 200
        private const val MAX_STEP_PREVIEW = 1_200
        private const val MAX_SUMMARY_CHARS = 4_000
        private const val PROGRESS_BUFFER = 512
        private const val MILESTONE_BUFFER = 128
        private const val CARD_DESCRIPTOR_KEY = "superpi.card"
        private val STAGEABLE_ARTIFACT_TYPES = setOf("file", "image", "video", "html")
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

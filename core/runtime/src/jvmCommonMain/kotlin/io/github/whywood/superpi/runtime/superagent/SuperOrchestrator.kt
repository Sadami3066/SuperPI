package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.SuperThreadState
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import io.github.whywood.superpi.runtime.WorkerSlotSummary
import io.github.whywood.superpi.runtime.WorkerTimerSummary
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.logWarn
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SuperOrchestrator(
    private val taskStore: SuperTaskStore,
    private val localWorkerPool: LocalWorkerPool,
    private val remoteWorkerGateway: RemoteWorkerGateway? = null,
    private val localBotsProvider: suspend () -> List<BotBlueprint>,
    private val remoteBotsProvider: suspend () -> List<BotBlueprint> = { emptyList() },
    private val timerService: (() -> TimerService)? = null,
    private val imageNormalizer: suspend (File) -> ImagePart? = { null },
    private val scope: CoroutineScope,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
    private val settingsProvider: (() -> SuperRuntimeSettings)? = null,
    threadEngineFactory: (
        gateway: WorkerOrchestrationGateway,
        cardExists: (String) -> Boolean,
        artifactResolver: suspend (String) -> File?,
    ) -> SuperThreadEngine,
) : SuperRuntime {

    private val workerGateway = object : WorkerOrchestrationGateway {
        override suspend fun listWorkers(botQuery: String?): List<WorkerRunRecord> =
            gatewayListWorkers(botQuery)

        override suspend fun startWorker(
            botQuery: String,
            task: String,
            attachmentPaths: List<String>,
        ): WorkerRunRecord = gatewayStartWorker(botQuery, task, attachmentPaths, modelOverride = null)

        override suspend fun startWorker(
            botQuery: String,
            task: String,
            attachmentPaths: List<String>,
            modelOverride: ModelConfig?,
        ): WorkerRunRecord = gatewayStartWorker(botQuery, task, attachmentPaths, modelOverride)

        override suspend fun startWorker(
            botQuery: String,
            task: String,
            attachmentPaths: List<String>,
            modelOverride: ModelConfig?,
            name: String?,
        ): WorkerRunRecord = gatewayStartWorker(botQuery, task, attachmentPaths, modelOverride, name)

        override suspend fun continueWorker(
            workerId: String,
            message: String,
            attachmentPaths: List<String>,
        ): WorkerRunRecord = gatewayContinueWorker(workerId, message, attachmentPaths)

        override suspend fun steerWorker(workerId: String, message: String): WorkerRunRecord =
            gatewaySteerWorker(workerId, message)

        override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult =
            gatewayInspectWorker(workerId, tail)

        override suspend fun stopWorker(workerId: String): WorkerRunRecord =
            gatewayStopWorker(workerId)

        override suspend fun deleteWorker(workerId: String) {
            this@SuperOrchestrator.deleteWorker(workerId)
        }

        override suspend fun compactWorker(workerId: String): WorkerRunRecord =
            gatewayCompactWorker(workerId)

        override suspend fun setWorkerModel(
            workerId: String,
            providerId: String,
            modelId: String,
            thinkingLevel: ThinkingLevel,
        ): WorkerRunRecord = gatewaySetWorkerModel(workerId, providerId, modelId, thinkingLevel)

        override suspend fun answerWorkerQuestion(
            workerId: String,
            requestId: String,
            answers: List<QuestionAnswer>,
        ): WorkerRunRecord = gatewayAnswerWorkerQuestionBySuper(workerId, requestId, answers)

        override suspend fun dismissWorkerQuestion(
            workerId: String,
            requestId: String,
        ): WorkerRunRecord = gatewayDismissWorkerQuestionBySuper(workerId, requestId)
    }

    private val threadEngine: SuperThreadEngine = threadEngineFactory(
        workerGateway,
        { cardId -> cardById(cardId) != null },
        ::resolveArtifactFile,
    )

    override val threadState: StateFlow<SuperThreadState> = threadEngine.state
    override val workers: StateFlow<List<WorkerRunRecord>> = taskStore.workers

    private val _blueprints = MutableStateFlow<List<BotBlueprint>>(emptyList())
    override val blueprints: StateFlow<List<BotBlueprint>> = _blueprints.asStateFlow()

    override val pendingWorkerQuestions: StateFlow<List<PendingQuestion>> = taskStore.workers
        .map { list -> list.mapNotNull { it.pendingQuestion } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _workerProgress = MutableSharedFlow<WorkerProgressEvent>(extraBufferCapacity = PROGRESS_BUFFER)
    override val workerProgress: SharedFlow<WorkerProgressEvent> = _workerProgress.asSharedFlow()

    override val superThreadEvents: SharedFlow<RuntimeEvent> = threadEngine.events

    private val started = AtomicBoolean(false)
    private val initMutex = Mutex()
    private val milestoneMutex = Mutex()
    private val pendingMilestones = mutableListOf<WorkerMilestone>()
    private var coalesceJob: Job? = null
    private val remoteSlotCounts = ConcurrentHashMap<String, Int>()
    private val queriedRemoteDevices = ConcurrentHashMap.newKeySet<String>()
    private val observedWorkerSessions = ConcurrentHashMap<String, StateFlow<WorkerSessionSnapshot>>()

    override fun bindTimerHandler() {
        registerTimerHandler()
    }

    override suspend fun ensureStarted() = withContext(Dispatchers.IO) {
        if (!started.get()) {
            initMutex.withLock {
                if (!started.get()) {
                    taskStore.ensure()
                    localWorkerPool.initialize()
                    try {
                        remoteWorkerGateway?.reattachOnlineWorkers()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logWarn("SuperOrchestrator", "Failed to reattach remote workers on start", error)
                    }
                    registerTimerHandler()
                    startCollectors()
                    rebuildBlueprints()
                    started.set(true)
                }
            }
        }
        threadEngine.ensureLoaded()
    }

    override suspend fun send(text: String, images: List<ImagePart>) {
        ensureStarted()
        threadEngine.send(text, images)
    }

    override suspend fun steer(text: String, images: List<ImagePart>) {
        ensureStarted()
        threadEngine.steer(text, images)
    }

    override fun abortSuper() {
        threadEngine.abort()
    }

    override suspend fun compactSuper() {
        ensureStarted()
        threadEngine.compact()
    }

    override suspend fun archiveAndResetSuper() {
        ensureStarted()
        threadEngine.archiveAndReset()
    }

    override suspend fun answerSuperQuestion(requestId: String, answers: List<QuestionAnswer>) {
        ensureStarted()
        threadEngine.answerQuestion(requestId, answers)
    }

    override suspend fun cancelSuperQuestion(requestId: String) {
        ensureStarted()
        threadEngine.cancelQuestion(requestId)
    }

    override suspend fun answerWorkerQuestion(
        workerId: String,
        requestId: String,
        answers: List<QuestionAnswer>,
    ) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            val remote = requireRemoteGateway()
            remote.answerApproval(record, requestId, answers)
        } else {
            localWorkerPool.answerQuestion(workerId, requestId, answers)
        }
        val summary = formatAnswerSummary(answers)
        threadEngine.appendContextNote(
            "User directly answered approval request for worker $workerId (${record.botName}): $summary",
        )
    }

    override suspend fun dismissWorkerQuestion(workerId: String, requestId: String) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            val remote = requireRemoteGateway()
            remote.dismissApproval(record, requestId)
        } else {
            localWorkerPool.dismissQuestion(workerId, requestId)
        }
        threadEngine.appendContextNote(
            "User dismissed approval request for worker $workerId (${record.botName}).",
        )
    }

    override suspend fun stopWorker(workerId: String) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().stopWorker(record)
        } else {
            localWorkerPool.stop(record.botId, workerId)
        }
    }

    override suspend fun deleteWorker(workerId: String) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().deleteWorker(record)
            updateRemoteSlotCountDelta(record.botRef, delta = -1)
        } else {
            localWorkerPool.delete(record.botId, workerId)
        }
        deleteLocalWorkerTimers(workerId)
        observedWorkerSessions.remove(workerId)
        rebuildBlueprints()
    }

    override suspend fun compactWorker(workerId: String) {
        ensureStarted()
        gatewayCompactWorker(workerId)
    }

    override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult? {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: return null
        return if (record.isRemote) {
            enrichRemoteInspect(workerId, requireRemoteGateway().inspectWorker(record, tail))
        } else {
            localWorkerPool.inspect(workerId, tail)
        }
    }

    private suspend fun deleteLocalWorkerTimers(workerId: String) {
        val service = timerService?.invoke() ?: return
        service.deleteBySession(SuperRuntime.workerSessionId(workerId))
    }

    private suspend fun enrichRemoteInspect(workerId: String, result: WorkerInspectResult): WorkerInspectResult {
        if (result.activeTimers.isNotEmpty()) return result
        val service = timerService?.invoke() ?: return result
        val targetSessionId = SuperRuntime.workerSessionId(workerId)
        val timers = try {
            service.list()
                .filter { it.sessionId == targetSessionId }
                .map { t ->
                    WorkerTimerSummary(
                        id = t.id,
                        name = t.name,
                        enabled = t.enabled,
                        nextRunAt = t.nextRunAt,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logWarn("SuperOrchestrator", "Failed to read timers during remote inspect for $workerId", error)
            emptyList()
        }
        return if (timers.isEmpty()) result else result.copy(activeTimers = timers)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot> {
        val remote = remoteWorkerGateway ?: return localWorkerPool.observeSession(workerId)
        val record = taskStore.currentWorker(workerId)
        if (record != null) {
            return if (record.isRemote) {
                remote.observeWorkerSession(workerId)
            } else {
                localWorkerPool.observeSession(workerId)
            }
        }
        return observedWorkerSessions.computeIfAbsent(workerId) {
            taskStore.workers
                .map { list -> list.firstOrNull { it.workerId == workerId }?.isRemote }
                .distinctUntilChanged()
                .flatMapLatest { isRemote ->
                    if (isRemote == true) {
                        remote.observeWorkerSession(workerId)
                    } else {
                        localWorkerPool.observeSession(workerId)
                    }
                }
                .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), WorkerSessionSnapshot(workerId = workerId))
        }
    }

    override suspend fun loadWorkerSession(workerId: String) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: return
        if (record.isRemote) {
            remoteWorkerGateway?.loadWorkerSession(record)
        } else {
            localWorkerPool.loadSession(workerId)
        }
    }

    override suspend fun sendToWorker(
        workerId: String,
        text: String,
        images: List<ImagePart>,
    ) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        val cleanText = text.trim()
        require(cleanText.isNotEmpty() || images.isNotEmpty()) { "message must not be empty" }
        if (record.isRemote) {
            val blueprint = blueprintForRecord(record)
            if (!blueprint.online) {
                throw IllegalStateException("Remote device '${blueprint.deviceName}' is currently offline.")
            }
            val remote = requireRemoteGateway()
            if (record.status.isBusyForCommands) {
                remote.steerWorker(record, cleanText, images)
            } else {
                remote.continueWorker(
                    blueprint = blueprint,
                    worker = record,
                    message = cleanText,
                    images = images,
                    notifySuper = false,
                )
            }
        } else {
            if (record.status.isBusyForCommands) {
                localWorkerPool.steer(record.botId, workerId, cleanText, images)
            } else {
                localWorkerPool.continueWorker(
                    botId = record.botId,
                    workerId = workerId,
                    message = cleanText,
                    images = images,
                    originCaller = LocalWorkerPool.USER_DIRECT_CALLER,
                )
            }
        }
    }

    override suspend fun changeWorkerModel(
        workerId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().setWorkerModel(record, providerId, modelId, thinkingLevel)
        } else {
            localWorkerPool.changeModel(workerId, providerId, modelId, thinkingLevel)
        }
    }

    override suspend fun changeWorkerThinking(
        workerId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().setWorkerThinking(record, thinkingLevel)
        } else {
            localWorkerPool.changeThinking(workerId, thinkingLevel)
        }
    }

    override fun workerWorkspaceDir(workerId: String): File? {
        val record = taskStore.currentWorker(workerId) ?: return null
        if (record.isRemote) return null
        return localWorkerPool.workerWorkspaceDirFor(workerId)
    }

    override suspend fun setBotEnabledInSuper(botRef: String, enabled: Boolean) {
        ensureStarted()
        taskStore.setBotEnabled(botRef, enabled)
        rebuildBlueprints()
    }

    override suspend fun refreshBlueprints() {
        taskStore.ensure()
        rebuildBlueprints()
        refreshRemoteSlotCountsAsync()
    }

    override suspend fun changeSuperModel(
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        ensureStarted()
        threadEngine.changeModel(providerId, modelId, thinkingLevel)
    }

    override suspend fun changeSuperThinking(thinkingLevel: ThinkingLevel) {
        ensureStarted()
        threadEngine.changeThinking(thinkingLevel)
    }

    suspend fun syncDefaultModel(config: ModelConfig) {
        threadEngine.syncDefaultModel(config)
    }

    override suspend fun resolveArtifactFile(refOrPath: String): File? {
        val cleaned = refOrPath.trim().trim('<', '>', '"', '\'')
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith(SuperRuntime.WORKER_ARTIFACT_SCHEME)) {
            val rest = cleaned.removePrefix(SuperRuntime.WORKER_ARTIFACT_SCHEME)
            val slash = rest.indexOf('/')
            val artifact = if (slash > 0 && slash < rest.lastIndex) {
                val workerId = rest.substring(0, slash)
                val artifactId = rest.substring(slash + 1)
                taskStore.findArtifact(workerId, artifactId) ?: taskStore.findArtifactById(artifactId)
            } else {
                taskStore.findArtifactById(rest)
            } ?: return null
            if (artifact.path.isNotBlank()) {
                val local = File(artifact.path)
                if (local.isFile) return local
            }
            if (artifact.remoteDeviceId != null) {
                return remoteWorkerGateway?.resolveArtifactFile(artifact)
            }
            return null
        }
        return File(cleaned).takeIf { it.isFile }
    }

    override fun cardById(cardId: String): WorkerCardRef? = taskStore.cardById(cardId)

    override fun dismissError() {
        threadEngine.dismissError()
    }

    override fun dismissWorkerError(workerId: String) {
        val record = taskStore.currentWorker(workerId) ?: return
        if (record.isRemote) {
            remoteWorkerGateway?.dismissWorkerError(workerId)
        } else {
            scope.launch { localWorkerPool.dismissError(workerId) }
        }
    }

    private suspend fun gatewayListWorkers(botQuery: String?): List<WorkerRunRecord> {
        ensureStarted()
        val query = botQuery?.trim()?.takeIf { it.isNotEmpty() }
        val all = taskStore.workers.value
        val filtered = if (query == null) {
            all
        } else {
            all.filter { worker ->
                worker.botRef.equals(query, ignoreCase = true) ||
                    worker.botId.equals(query, ignoreCase = true) ||
                    worker.botName.equals(query, ignoreCase = true)
            }
        }
        return filtered.sortedByDescending { it.updatedAt }
    }

    private suspend fun gatewayStartWorker(
        botQuery: String,
        task: String,
        attachmentPaths: List<String> = emptyList(),
        modelOverride: ModelConfig? = null,
        name: String? = null,
    ): WorkerRunRecord {
        ensureStarted()
        if (_blueprints.value.isEmpty()) {
            rebuildBlueprints()
        }
        val blueprint = resolveEnabledBlueprint(botQuery)
        if (blueprint.isRemote) {
            if (!blueprint.supportsSuperWorker) {
                throw IllegalArgumentException(
                    "Remote device '${blueprint.deviceName}' does not support Super workers yet.",
                )
            }
            if (!blueprint.online) {
                throw IllegalStateException(
                    "Remote device '${blueprint.deviceName}' is currently offline.",
                )
            }
            val prepared = prepareWorkerAttachments(task, attachmentPaths, localBotId = null, localWorkerId = null)
            val startedRemote = requireRemoteGateway().startWorker(
                blueprint = blueprint,
                task = prepared.prompt,
                images = prepared.images,
                modelOverride = modelOverride,
                title = name,
            )
            updateRemoteSlotCountDelta(blueprint.ref, delta = 1)
            rebuildBlueprints()
            return startedRemote
        }
        val preallocatedId = if (attachmentPaths.isNotEmpty()) {
            val existing = localWorkerPool.list(blueprint.botId)
            val maxSlots = localWorkerPool.maxSlotsPerBot()
            if (existing.size >= maxSlots) {
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
                    maxSlots = maxSlots,
                )
            }
            "wrk-" + UUID.randomUUID().toString().replace("-", "").take(12)
        } else {
            null
        }
        val prepared = try {
            prepareWorkerAttachments(
                basePrompt = task,
                attachmentPaths = attachmentPaths,
                localBotId = blueprint.botId.takeIf { preallocatedId != null },
                localWorkerId = preallocatedId,
            )
        } catch (error: Throwable) {
            if (preallocatedId != null) {
                localWorkerPool.cleanupStagedWorkerDir(blueprint.botId, preallocatedId)
            }
            throw error
        }
        val startedLocal = try {
            localWorkerPool.start(
                botId = blueprint.botId,
                task = prepared.prompt,
                requestedWorkerId = preallocatedId,
                images = prepared.images,
                originCaller = LocalWorkerPool.LOCAL_CALLER,
                modelOverride = modelOverride,
                title = name,
            )
        } catch (error: Throwable) {
            if (preallocatedId != null) {
                localWorkerPool.cleanupStagedWorkerDir(blueprint.botId, preallocatedId)
            }
            throw error
        }
        rebuildBlueprints()
        return startedLocal
    }

    private suspend fun gatewayContinueWorker(
        workerId: String,
        message: String,
        attachmentPaths: List<String> = emptyList(),
    ): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            val blueprint = blueprintForRecord(record)
            if (!blueprint.online) {
                throw IllegalStateException("Remote device '${blueprint.deviceName}' is currently offline.")
            }
            val prepared = prepareWorkerAttachments(message, attachmentPaths, localBotId = null, localWorkerId = null)
            return requireRemoteGateway().continueWorker(blueprint, record, prepared.prompt, prepared.images)
        }
        val prepared = prepareWorkerAttachments(
            basePrompt = message,
            attachmentPaths = attachmentPaths,
            localBotId = record.botId,
            localWorkerId = workerId,
        )
        return localWorkerPool.continueWorker(
            botId = record.botId,
            workerId = workerId,
            message = prepared.prompt,
            images = prepared.images,
            originCaller = LocalWorkerPool.LOCAL_CALLER,
        )
    }

    private data class PreparedWorkerInput(
        val prompt: String,
        val images: List<ImagePart>,
    )

    private suspend fun prepareWorkerAttachments(
        basePrompt: String,
        attachmentPaths: List<String>,
        localBotId: String?,
        localWorkerId: String?,
    ): PreparedWorkerInput {
        val cleanBase = basePrompt.trim()
        if (attachmentPaths.isEmpty()) {
            return PreparedWorkerInput(prompt = cleanBase, images = emptyList())
        }
        val images = mutableListOf<ImagePart>()
        val extraBlocks = mutableListOf<String>()
        for (rawPath in attachmentPaths) {
            val resolved = resolveArtifactFile(rawPath)
            if (resolved == null) {
                extraBlocks += """<attached_file path="$rawPath" note="File could not be resolved" />"""
                continue
            }
            val ext = resolved.extension.lowercase(Locale.ROOT)
            if (ext in IMAGE_EXTENSIONS) {
                val normalized = runCatching { imageNormalizer(resolved) }.getOrNull()
                if (normalized != null) {
                    images += normalized
                    continue
                }
            }
            if (localBotId != null && localWorkerId != null) {
                val staged = localWorkerPool.stageInputFile(localBotId, localWorkerId, resolved) ?: resolved
                extraBlocks += """<file path="${staged.absolutePath}" name="${staged.name}" />"""
            } else {
                val inlineContent = readSmallTextAttachment(resolved)
                if (inlineContent != null) {
                    extraBlocks += "<attached_file name=\"${resolved.name}\">\n$inlineContent\n</attached_file>"
                } else {
                    extraBlocks += """<attached_file name="${resolved.name}" note="File on controller device (${resolved.length()} bytes) was not transferred" />"""
                }
            }
        }
        val prompt = buildString {
            append(cleanBase)
            if (extraBlocks.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("<attachments>\n")
                extraBlocks.forEach { block ->
                    append(block)
                    append('\n')
                }
                append("</attachments>")
            }
        }
        return PreparedWorkerInput(prompt = prompt, images = images)
    }

    private fun readSmallTextAttachment(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        val size = file.length()
        if (size <= 0L || size > MAX_REMOTE_INLINE_ATTACHMENT_BYTES) return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.any { it == 0.toByte() }) null else bytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private suspend fun gatewaySteerWorker(workerId: String, message: String): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().steerWorker(record, message)
            return taskStore.currentWorker(workerId) ?: record
        }
        return localWorkerPool.steer(record.botId, workerId, message)
    }

    private suspend fun gatewayInspectWorker(workerId: String, tail: Int): WorkerInspectResult {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        return if (record.isRemote) {
            enrichRemoteInspect(workerId, requireRemoteGateway().inspectWorker(record, tail))
        } else {
            localWorkerPool.inspect(workerId, tail)
        }
    }

    private suspend fun gatewayStopWorker(workerId: String): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().stopWorker(record)
            return taskStore.currentWorker(workerId) ?: record
        }
        return localWorkerPool.stop(record.botId, workerId)
    }

    private suspend fun gatewayCompactWorker(workerId: String): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            return requireRemoteGateway().compactWorker(record)
        }
        return localWorkerPool.compact(workerId)
    }

    private suspend fun gatewaySetWorkerModel(
        workerId: String,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().setWorkerModel(record, providerId, modelId, thinkingLevel)
        } else {
            localWorkerPool.changeModel(workerId, providerId, modelId, thinkingLevel)
        }
        return taskStore.currentWorker(workerId) ?: record
    }

    private suspend fun gatewayAnswerWorkerQuestionBySuper(
        workerId: String,
        requestId: String,
        answers: List<QuestionAnswer>,
    ): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().answerApproval(record, requestId, answers, bySuper = true)
        } else {
            localWorkerPool.answerQuestion(workerId, requestId, answers, bySuper = true)
        }
        val summary = formatAnswerSummary(answers)
        threadEngine.appendContextNote(
            "Super answered timed-out approval request $requestId for worker $workerId (${record.botName}): $summary",
        )
        return taskStore.currentWorker(workerId) ?: record
    }

    private fun formatAnswerSummary(answers: List<QuestionAnswer>): String =
        answers.joinToString("; ") { ans ->
            if (ans.skipped) {
                "${ans.questionId}: skipped"
            } else {
                val parts = ans.selected + listOfNotNull(ans.custom?.trim()?.takeIf { it.isNotEmpty() })
                val values = parts.joinToString(", ").ifBlank { "(empty)" }
                "${ans.questionId}: $values"
            }
        }.take(240)

    private suspend fun gatewayDismissWorkerQuestionBySuper(
        workerId: String,
        requestId: String,
    ): WorkerRunRecord {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: throw WorkerNotFoundException(workerId)
        if (record.isRemote) {
            requireRemoteGateway().dismissApproval(record, requestId, bySuper = true)
        } else {
            localWorkerPool.dismissQuestion(workerId, requestId, bySuper = true)
        }
        threadEngine.appendContextNote(
            "Super dismissed timed-out approval request $requestId for worker $workerId (${record.botName}).",
        )
        return taskStore.currentWorker(workerId) ?: record
    }

    private fun resolveEnabledBlueprint(query: String): BotBlueprint {
        val clean = query.trim()
        require(clean.isNotEmpty()) { "bot is required" }
        val all = _blueprints.value
        val byRef = all.firstOrNull { it.ref.equals(clean, ignoreCase = true) }
        if (byRef != null) {
            if (!byRef.enabledInSuper) {
                throw IllegalArgumentException("Bot '${byRef.name}' (${byRef.ref}) is not enabled in Super mode.")
            }
            return byRef
        }
        val enabled = all.filter { it.enabledInSuper }
        val byId = enabled.filter { it.botId.equals(clean, ignoreCase = true) }
        if (byId.size == 1) return byId.first()
        if (byId.size > 1) {
            val refs = byId.joinToString(", ") { it.ref }
            throw IllegalArgumentException("Ambiguous bot '$clean'. Use exact ref: $refs")
        }
        val byName = enabled.filter { it.name.equals(clean, ignoreCase = true) }
        if (byName.size == 1) return byName.first()
        if (byName.size > 1) {
            val refs = byName.joinToString(", ") { "${it.name} (${it.ref})" }
            throw IllegalArgumentException("Multiple bots named '$clean' are enabled. Use exact ref: $refs")
        }
        throw IllegalArgumentException("Bot '$clean' is not available or not enabled in <available_bots>.")
    }

    private fun blueprintForRecord(record: WorkerRunRecord): BotBlueprint {
        val existing = _blueprints.value.firstOrNull { it.ref == record.botRef }
        if (existing != null) return existing
        return BotBlueprint(
            ref = record.botRef,
            botId = record.botId,
            name = record.botName,
            avatar = record.botAvatar,
            deviceId = record.deviceId,
            deviceName = record.deviceName,
            online = true,
            supportsSuperWorker = true,
            enabledInSuper = taskStore.isBotEnabled(record.botRef),
        )
    }

    private fun registerTimerHandler() {
        val service = timerService?.invoke() ?: return
        service.externalReminderHandler = { sessionId, prompt, reminderName ->
            when {
                !SuperRuntime.isSuperManagedSessionId(sessionId) -> false
                threadEngine.ownsSessionId(sessionId) -> {
                    ensureStarted()
                    threadEngine.deliverReminder(prompt, reminderName)
                    true
                }
                else -> {
                    val workerId = SuperRuntime.parseWorkerSessionId(sessionId)
                    if (workerId != null) {
                        deliverWorkerTimerReminder(workerId, prompt, reminderName)
                    } else {
                        false
                    }
                }
            }
        }
    }

    private suspend fun deliverWorkerTimerReminder(
        workerId: String,
        prompt: String,
        reminderName: String?,
    ): Boolean {
        ensureStarted()
        val record = taskStore.currentWorker(workerId) ?: return false
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return false
        val baseReminder = when {
            cleanPrompt.startsWith("[Reminder]") -> cleanPrompt
            reminderName.isNullOrBlank() -> "[Reminder] $cleanPrompt"
            else -> "[Reminder] $reminderName: $cleanPrompt"
        }
        val wrapped = buildString {
            append(baseReminder)
            append("\n\n[Scheduled background check: if nothing new requires Super or user attention, reply ONLY with ")
            append(LocalWorkerPool.NO_UPDATE_MARKER)
            append(".]")
        }
        return if (!record.isRemote) {
            localWorkerPool.deliverTimerReminder(workerId, wrapped, reminderName = null)
        } else {
            val blueprint = blueprintForRecord(record)
            if (!blueprint.online) return false
            val remote = remoteWorkerGateway ?: return false
            runCatching {
                if (record.status.isBusyForCommands) {
                    remote.steerWorker(record, wrapped, silentPolling = true)
                } else {
                    remote.continueWorker(
                        blueprint = blueprint,
                        worker = record,
                        message = wrapped,
                        notifySuper = true,
                        silentPolling = true,
                    )
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun startCollectors() {
        scope.launch {
            localWorkerPool.progressEvents.collect { event ->
                _workerProgress.tryEmit(event)
            }
        }
        remoteWorkerGateway?.let { remote ->
            scope.launch {
                remote.progressEvents.collect { event ->
                    _workerProgress.tryEmit(event)
                }
            }
            scope.launch {
                remote.milestones.collect { milestone ->
                    enqueueMilestone(milestone)
                }
            }
        }
        scope.launch {
            localWorkerPool.milestones.collect { event ->
                if (event.originCaller == LocalWorkerPool.LOCAL_CALLER) {
                    enqueueMilestone(event.milestone)
                }
            }
        }
        scope.launch {
            taskStore.workers
                .map { workers -> workers.map { Triple(it.botRef, it.isRemote, it.status) } }
                .distinctUntilChanged()
                .collect {
                    rebuildBlueprints()
                }
        }
        scope.launch {
            taskStore.whitelist.collect {
                rebuildBlueprints()
            }
        }
    }

    private suspend fun enqueueMilestone(milestone: WorkerMilestone) {
        if (milestone is WorkerMilestone.Aborted) return
        val windowMs = effectiveCoalesceWindowMs()
        milestoneMutex.withLock {
            pendingMilestones += milestone
            if (coalesceJob?.isActive == true) return
            coalesceJob = scope.launch {
                if (windowMs > 0L) delay(windowMs)
                flushMilestones()
            }
        }
    }

    private suspend fun flushMilestones() {
        val batch = milestoneMutex.withLock {
            if (pendingMilestones.isEmpty()) {
                coalesceJob = null
                return
            }
            val drained = pendingMilestones.toList()
            pendingMilestones.clear()
            coalesceJob = null
            drained
        }
        val latestByWorker = LinkedHashMap<String, WorkerMilestone>()
        for (item in batch) {
            latestByWorker[item.workerId] = item
        }
        val message = formatMilestoneReceipt(latestByWorker.values.toList())
        if (message.isNotBlank()) {
            try {
                threadEngine.followUp(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logWarn("SuperOrchestrator", "Failed to deliver worker update to Super thread", error)
            }
        }
    }

    private fun formatMilestoneReceipt(milestones: List<WorkerMilestone>): String {
        val visible = milestones.filter { it !is WorkerMilestone.Aborted }
        if (visible.isEmpty()) return ""
        val maxReceiptChars = effectiveReceiptMaxChars()
        data class MilestoneParts(
            val header: String,
            val bodyLabel: String?,
            val rawBody: String,
            val footer: String,
        )
        val parts = visible.map { milestone ->
            val workerTitle = taskStore.currentWorker(milestone.workerId)
                ?.title
                ?.takeIf { it.isNotBlank() && it != milestone.workerId }
            val nameSegment = if (workerTitle != null) " | name: $workerTitle" else ""
            when (milestone) {
                is WorkerMilestone.Completed -> MilestoneParts(
                    header = "worker_id: ${milestone.workerId}$nameSegment | bot: ${milestone.botName} (${milestone.botRef}) | status: completed",
                    bodyLabel = "\nsummary: ",
                    rawBody = milestone.summary.trim(),
                    footer = buildString { appendArtifactsAndCards(milestone.artifacts, milestone.cards) },
                )

                is WorkerMilestone.Failed -> MilestoneParts(
                    header = "worker_id: ${milestone.workerId}$nameSegment | bot: ${milestone.botName} (${milestone.botRef}) | status: failed",
                    bodyLabel = "\nerror: ",
                    rawBody = milestone.error.trim(),
                    footer = buildString { appendArtifactsAndCards(milestone.artifacts, milestone.cards) },
                )

                is WorkerMilestone.Stalled -> MilestoneParts(
                    header = "worker_id: ${milestone.workerId}$nameSegment | bot: ${milestone.botName} (${milestone.botRef}) | status: stalled (no progress for ${milestone.elapsedMs / 1000L}s)",
                    bodyLabel = "\nlast_step: ",
                    rawBody = milestone.lastStepText?.trim().orEmpty(),
                    footer = "\nnote: The worker is still active without recent output; avoid stopping unless needed — inspect or steer first.",
                )

                is WorkerMilestone.WaitingInputTimeout -> MilestoneParts(
                    header = "[Worker waiting-input timeout] worker_id: ${milestone.workerId}$nameSegment | bot: ${milestone.botName} (${milestone.botRef}) | request_id: ${milestone.requestId} | waited: ${milestone.waitedMs / 1000L}s",
                    bodyLabel = "\nquestions:\n",
                    rawBody = formatEscalatedQuestions(milestone.questions),
                    footer = "\naction_hint: User did not answer within timeout. Review and decide with worker(action=answer|dismiss) or worker(action=stop), and report to the user.",
                )

                is WorkerMilestone.Aborted -> MilestoneParts("", null, "", "")
            }
        }
        val headerPrefix = "[Worker update]"
        return buildString {
            append(headerPrefix)
            for (part in parts) {
                append("\n\n")
                append(part.header)
                if (part.rawBody.isNotEmpty() && part.bodyLabel != null) {
                    val fixedChars = part.header.length + part.bodyLabel.length + part.footer.length
                    val bodyBudget = (maxReceiptChars - fixedChars)
                        .coerceAtLeast(MIN_MILESTONE_BODY_CHARS)
                    append(part.bodyLabel)
                    append(part.rawBody.take(bodyBudget))
                }
                append(part.footer)
            }
        }
    }

    private fun formatEscalatedQuestions(questions: List<UserQuestion>): String = buildString {
        questions.forEachIndexed { index, q ->
            if (index > 0) append('\n')
            append("- id: ").append(q.id).append(" | prompt: ").append(q.question.trim())
            if (q.options.isNotEmpty()) {
                val opts = q.options.joinToString(", ") { opt ->
                    if (opt.description.isNullOrBlank()) opt.label else "${opt.label} (${opt.description})"
                }
                append(" | options: [").append(opts).append(']')
            }
        }
    }

    private fun StringBuilder.appendArtifactsAndCards(
        artifacts: List<WorkerArtifactRef>,
        cards: List<WorkerCardRef>,
    ) {
        if (artifacts.isNotEmpty()) {
            val formatted = artifacts.joinToString(", ") { art ->
                val uri = SuperRuntime.workerArtifactUri(art.workerId, art.artifactId)
                "$uri (${art.kind}: ${art.name})"
            }
            append("\nartifact_uris: ").append(formatted)
        }
        if (cards.isNotEmpty()) {
            val formatted = cards.joinToString(", ") { card ->
                val title = card.title?.takeIf { it.isNotBlank() } ?: card.toolName
                "${card.cardId} (${card.toolName}: $title)"
            }
            append("\ncard_ids: ").append(formatted)
        }
    }

    private suspend fun rebuildBlueprints() {
        val local = runCatching { localBotsProvider() }.getOrDefault(emptyList())
        val remote = runCatching { remoteBotsProvider() }.getOrDefault(emptyList())
        val currentWorkers = taskStore.workers.value
        val localCounts = currentWorkers.filter { !it.isRemote }.groupingBy { it.botRef }.eachCount()
        val localCachedRemoteCounts = currentWorkers.filter { it.isRemote }.groupingBy { it.botRef }.eachCount()

        val maxSlots = effectiveMaxSlotsPerBot()
        val merged = buildList {
            local.forEach { bot ->
                add(
                    bot.copy(
                        enabledInSuper = taskStore.isBotEnabled(bot.ref),
                        usedWorkerSlots = localCounts[bot.ref] ?: 0,
                        maxWorkerSlots = maxSlots,
                    ),
                )
            }
            remote.forEach { bot ->
                val count = remoteSlotCounts[bot.ref] ?: (localCachedRemoteCounts[bot.ref] ?: 0)
                val remoteMax = bot.deviceId?.let { remoteWorkerGateway?.maxSlotsForDevice(it) } ?: maxSlots
                add(
                    bot.copy(
                        enabledInSuper = taskStore.isBotEnabled(bot.ref),
                        usedWorkerSlots = count,
                        maxWorkerSlots = remoteMax,
                    ),
                )
            }
        }
        _blueprints.value = merged
        threadEngine.updateBlueprints(merged)
    }

    private fun refreshRemoteSlotCountsAsync() {
        val remote = remoteWorkerGateway ?: return
        val onlineDevices = _blueprints.value
            .filter { it.isRemote && it.online && it.supportsSuperWorker }
            .mapNotNull { it.deviceId }
            .distinct()
        for (deviceId in onlineDevices) {
            if (!queriedRemoteDevices.add(deviceId)) continue
            scope.launch {
                try {
                    val list = remote.listRemoteWorkers(deviceId, botId = null)
                    val grouped = list.groupingBy { it.botRef }.eachCount()
                    _blueprints.value
                        .filter { it.deviceId == deviceId }
                        .forEach { bp -> remoteSlotCounts[bp.ref] = grouped[bp.ref] ?: 0 }
                    rebuildBlueprints()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                } finally {
                    queriedRemoteDevices.remove(deviceId)
                }
            }
        }
    }

    private fun updateRemoteSlotCountDelta(botRef: String, delta: Int) {
        val current = remoteSlotCounts[botRef]
            ?: _blueprints.value.firstOrNull { it.ref == botRef }?.usedWorkerSlots
            ?: 0
        remoteSlotCounts[botRef] = (current + delta).coerceAtLeast(0)
    }

    private fun effectiveCoalesceWindowMs(): Long =
        (settingsProvider?.invoke()?.coalesceWindowMs ?: coalesceWindowMs).coerceAtLeast(0L)

    private fun effectiveReceiptMaxChars(): Int =
        (settingsProvider?.invoke()?.receiptMaxChars ?: DEFAULT_RECEIPT_MAX_CHARS)
            .coerceAtLeast(MIN_MILESTONE_BODY_CHARS)

    private fun effectiveMaxSlotsPerBot(): Int =
        (settingsProvider?.invoke()?.maxSlotsPerBot ?: SuperRuntime.MAX_WORKERS_PER_BOT)
            .coerceAtLeast(1)

    private fun requireRemoteGateway(): RemoteWorkerGateway =
        remoteWorkerGateway ?: error("Remote workers are not configured")

    companion object {
        const val DEFAULT_COALESCE_WINDOW_MS = 1_500L
        private const val PROGRESS_BUFFER = 512
        private const val DEFAULT_RECEIPT_MAX_CHARS = 1_500
        private const val MIN_MILESTONE_BODY_CHARS = 120
        private const val MAX_REMOTE_INLINE_ATTACHMENT_BYTES = 32_768L
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    }
}

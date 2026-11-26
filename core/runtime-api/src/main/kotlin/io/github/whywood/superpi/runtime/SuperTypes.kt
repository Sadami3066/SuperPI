package io.github.whywood.superpi.runtime

import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class BotCapabilitiesSummary(
    val skills: List<String> = emptyList(),
    val plugins: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val screenTools: Boolean = false,
    val miniappTools: Boolean = false,
    val memoryDispatch: Boolean = false,
    val memoryScope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
)

data class BotBlueprint(
    val ref: String,
    val botId: String,
    val name: String,
    val avatar: String? = null,
    val intro: String = "",
    val deviceId: String? = null,
    val deviceName: String = "",
    val online: Boolean = true,
    val capabilities: BotCapabilitiesSummary = BotCapabilitiesSummary(),
    val supportsSuperWorker: Boolean = true,
    val enabledInSuper: Boolean = false,
    val defaultModel: ModelConfig? = null,
    val usedWorkerSlots: Int = 0,
    val maxWorkerSlots: Int = SuperRuntime.MAX_WORKERS_PER_BOT,
) {
    val isRemote: Boolean get() = !deviceId.isNullOrBlank()
}

enum class WorkerStatus {
    QUEUED,
    RUNNING,
    WAITING_INPUT,
    STALLED,
    IDLE,
    FAILED,
}

enum class WorkerOutcome {
    NONE,
    SUCCEEDED,
    FAILED,
    ABORTED,
}

val WorkerStatus.isActive: Boolean
    get() = this == WorkerStatus.QUEUED ||
        this == WorkerStatus.RUNNING ||
        this == WorkerStatus.WAITING_INPUT ||
        this == WorkerStatus.STALLED

val WorkerStatus.isBusyForCommands: Boolean
    get() = isActive

data class SuperRuntimeSettings(
    val maxConcurrentWorkers: Int = 5,
    val maxSlotsPerBot: Int = SuperRuntime.MAX_WORKERS_PER_BOT,
    val stallTimeoutMs: Long = 10 * 60_000L,
    val questionTimeoutMs: Long = 3 * 60_000L,
    val workerJobWake: Boolean = true,
    val coalesceWindowMs: Long = 1_500L,
    val receiptMaxChars: Int = 1_500,
    val idleEvictMs: Long = 5 * 60_000L,
    val workerCompactionOverrideEnabled: Boolean = false,
    val workerCompactionTriggerPercent: Int = 75,
)

data class WorkerTimerSummary(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val nextRunAt: Long? = null,
)

data class WorkerJobSummary(
    val jobId: String,
    val command: String,
    val status: JobStatus,
)

data class WorkerArtifactRef(
    val artifactId: String,
    val workerId: String,
    val botRef: String,
    val kind: String,
    val path: String,
    val name: String,
    val title: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val remoteDeviceId: String? = null,
    val remoteBotId: String? = null,
    val remoteArtifactId: String? = null,
    val createdAt: Long = 0L,
)

data class WorkerCardRef(
    val cardId: String,
    val workerId: String,
    val botRef: String,
    val toolName: String,
    val title: String? = null,
    val argsJson: String = "{}",
    val outputText: String = "",
    val detailsJson: String = "",
    val payloadJson: String = "{}",
    val createdAt: Long = 0L,
)

data class WorkerStepSnapshot(
    val callId: String,
    val toolName: String,
    val argsJson: String = "{}",
    val status: ToolActivityStatus = ToolActivityStatus.RUNNING,
    val outputPreview: String? = null,
    val isError: Boolean = false,
    val updatedAt: Long = 0L,
)

data class WorkerRunRecord(
    val workerId: String,
    val botRef: String,
    val botId: String,
    val botName: String,
    val botAvatar: String? = null,
    val deviceId: String? = null,
    val deviceName: String = "",
    val title: String,
    val status: WorkerStatus,
    val lastOutcome: WorkerOutcome = WorkerOutcome.NONE,
    val turnCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastProgressAt: Long = 0L,
    val lastSummary: String? = null,
    val lastTurnSilent: Boolean = false,
    val lastError: String? = null,
    val activeStepText: String? = null,
    val steps: List<WorkerStepSnapshot> = emptyList(),
    val artifacts: List<WorkerArtifactRef> = emptyList(),
    val cards: List<WorkerCardRef> = emptyList(),
    val pendingQuestion: PendingQuestion? = null,
    val remoteLastSeq: Long = 0L,
) {
    val isRemote: Boolean get() = !deviceId.isNullOrBlank()
}

sealed interface WorkerProgressEvent {
    val workerId: String

    data class StatusChanged(
        override val workerId: String,
        val status: WorkerStatus,
        val activeStepText: String? = null,
    ) : WorkerProgressEvent

    data class StepUpdated(
        override val workerId: String,
        val step: WorkerStepSnapshot,
    ) : WorkerProgressEvent

    data class ApprovalRequested(
        override val workerId: String,
        val question: PendingQuestion,
    ) : WorkerProgressEvent

    data class ApprovalResolved(
        override val workerId: String,
        val requestId: String,
    ) : WorkerProgressEvent
}

sealed interface WorkerMilestone {
    val workerId: String
    val botRef: String
    val botName: String
    val timestamp: Long

    data class Completed(
        override val workerId: String,
        override val botRef: String,
        override val botName: String,
        val summary: String,
        val artifacts: List<WorkerArtifactRef> = emptyList(),
        val cards: List<WorkerCardRef> = emptyList(),
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkerMilestone

    data class Failed(
        override val workerId: String,
        override val botRef: String,
        override val botName: String,
        val error: String,
        val artifacts: List<WorkerArtifactRef> = emptyList(),
        val cards: List<WorkerCardRef> = emptyList(),
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkerMilestone

    data class Stalled(
        override val workerId: String,
        override val botRef: String,
        override val botName: String,
        val elapsedMs: Long,
        val lastStepText: String? = null,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkerMilestone

    data class WaitingInputTimeout(
        override val workerId: String,
        override val botRef: String,
        override val botName: String,
        val requestId: String,
        val questions: List<UserQuestion>,
        val waitedMs: Long,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkerMilestone

    data class Aborted(
        override val workerId: String,
        override val botRef: String,
        override val botName: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : WorkerMilestone
}

data class SuperThreadState(
    val messages: List<AssistantMessage> = emptyList(),
    val busy: Boolean = false,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val streamingSteps: List<AssistantStep> = emptyList(),
    val activeToolText: String? = null,
    val providerId: String = "",
    val modelId: String = "",
    val modelLabel: String = "",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val thinkingLevels: List<ThinkingLevel> = listOf(ThinkingLevel.OFF),
    val usage: SessionUsage? = null,
    val pendingQuestion: PendingQuestion? = null,
    val ready: Boolean = false,
    val error: String? = null,
    val archivedCount: Int = 0,
)

data class WorkerSlotSummary(
    val workerId: String,
    val title: String,
    val status: WorkerStatus,
    val updatedAt: Long,
)

data class WorkerInspectResult(
    val record: WorkerRunRecord,
    val recentSteps: List<WorkerStepSnapshot>,
    val historyMessages: List<AssistantMessage> = emptyList(),
    val usage: SessionUsage? = null,
    val activeTimers: List<WorkerTimerSummary> = emptyList(),
    val runningJobs: List<WorkerJobSummary> = emptyList(),
    val questionEscalatedToSuper: Boolean = false,
)

data class WorkerSessionSnapshot(
    val workerId: String,
    val messages: List<AssistantMessage> = emptyList(),
    val streaming: Boolean = false,
    val activeToolText: String? = null,
    val providerId: String = "",
    val modelId: String = "",
    val modelLabel: String = "",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val thinkingLevels: List<ThinkingLevel> = listOf(ThinkingLevel.OFF),
    val usage: SessionUsage? = null,
    val loaded: Boolean = false,
    val error: String? = null,
)

class WorkerPoolFullException(
    val botId: String,
    val existing: List<WorkerSlotSummary>,
    val maxSlots: Int = SuperRuntime.MAX_WORKERS_PER_BOT,
) : IllegalStateException("Worker pool for bot $botId is full (max $maxSlots)")

class WorkerConflictException(
    val workerId: String,
    override val message: String,
) : IllegalStateException(message)

class WorkerNotFoundException(
    val workerId: String,
) : NoSuchElementException("Worker $workerId was not found")

interface SuperRuntime {
    val threadState: StateFlow<SuperThreadState>
    val workers: StateFlow<List<WorkerRunRecord>>
    val blueprints: StateFlow<List<BotBlueprint>>
    val pendingWorkerQuestions: StateFlow<List<PendingQuestion>>
    val workerProgress: SharedFlow<WorkerProgressEvent>
    val superThreadEvents: SharedFlow<RuntimeEvent>

    fun bindTimerHandler()
    suspend fun ensureStarted()
    suspend fun send(text: String, images: List<ImagePart> = emptyList())
    suspend fun steer(text: String, images: List<ImagePart> = emptyList())
    fun abortSuper()
    suspend fun compactSuper()
    suspend fun archiveAndResetSuper()
    suspend fun answerSuperQuestion(requestId: String, answers: List<QuestionAnswer>)
    suspend fun cancelSuperQuestion(requestId: String)
    suspend fun answerWorkerQuestion(workerId: String, requestId: String, answers: List<QuestionAnswer>)
    suspend fun dismissWorkerQuestion(workerId: String, requestId: String)
    suspend fun stopWorker(workerId: String)
    suspend fun deleteWorker(workerId: String)
    suspend fun compactWorker(workerId: String)
    suspend fun inspectWorker(workerId: String, tail: Int = 30): WorkerInspectResult?
    fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot>
    suspend fun loadWorkerSession(workerId: String)
    suspend fun sendToWorker(workerId: String, text: String, images: List<ImagePart> = emptyList())
    suspend fun changeWorkerModel(workerId: String, providerId: String, modelId: String, thinkingLevel: ThinkingLevel)
    suspend fun changeWorkerThinking(workerId: String, thinkingLevel: ThinkingLevel)
    fun workerWorkspaceDir(workerId: String): File?
    suspend fun setBotEnabledInSuper(botRef: String, enabled: Boolean)
    suspend fun refreshBlueprints()
    suspend fun changeSuperModel(providerId: String, modelId: String, thinkingLevel: ThinkingLevel)
    suspend fun changeSuperThinking(thinkingLevel: ThinkingLevel)
    suspend fun resolveArtifactFile(refOrPath: String): File?
    fun cardById(cardId: String): WorkerCardRef?
    fun dismissError()
    fun dismissWorkerError(workerId: String)

    companion object {
        const val MAX_WORKERS_PER_BOT = 5
        const val HOME_BOT_REF = "local:home"
        const val SUPER_THREAD_SESSION_ID = "super:router"
        const val WORKER_SESSION_PREFIX = "worker:"
        const val WORKER_ARTIFACT_SCHEME = "worker://"

        fun localBotRef(botId: String): String = "local:$botId"

        fun remoteBotRef(deviceId: String, botId: String): String = "remote:$deviceId:$botId"

        fun workerSessionId(workerId: String): String = "$WORKER_SESSION_PREFIX$workerId"

        fun parseWorkerSessionId(sessionId: String): String? =
            sessionId.removePrefix(WORKER_SESSION_PREFIX)
                .takeIf { sessionId.startsWith(WORKER_SESSION_PREFIX) && it.isNotBlank() }

        fun isSuperManagedSessionId(sessionId: String): Boolean =
            sessionId == SUPER_THREAD_SESSION_ID || parseWorkerSessionId(sessionId) != null

        fun workerArtifactUri(workerId: String, artifactId: String): String =
            "$WORKER_ARTIFACT_SCHEME$workerId/$artifactId"
    }
}

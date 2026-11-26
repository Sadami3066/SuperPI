package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface RemoteWorkerGateway {
    val progressEvents: SharedFlow<WorkerProgressEvent>
    val milestones: SharedFlow<WorkerMilestone>

    suspend fun reattachOnlineWorkers() {}
    fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot>
    suspend fun loadWorkerSession(worker: WorkerRunRecord)
    suspend fun listRemoteWorkers(deviceId: String, botId: String? = null): List<WorkerRunRecord>
    suspend fun startWorker(
        blueprint: BotBlueprint,
        task: String,
        images: List<ImagePart> = emptyList(),
        modelOverride: ModelConfig? = null,
    ): WorkerRunRecord
    suspend fun startWorker(
        blueprint: BotBlueprint,
        task: String,
        images: List<ImagePart>,
        modelOverride: ModelConfig?,
        title: String?,
    ): WorkerRunRecord = startWorker(blueprint, task, images, modelOverride)
    suspend fun continueWorker(
        blueprint: BotBlueprint,
        worker: WorkerRunRecord,
        message: String,
        images: List<ImagePart> = emptyList(),
        notifySuper: Boolean = true,
        silentPolling: Boolean = false,
    ): WorkerRunRecord
    suspend fun steerWorker(
        worker: WorkerRunRecord,
        message: String,
        images: List<ImagePart> = emptyList(),
        silentPolling: Boolean = false,
    )
    suspend fun stopWorker(worker: WorkerRunRecord)
    suspend fun compactWorker(worker: WorkerRunRecord): WorkerRunRecord {
        throw UnsupportedOperationException("Compaction is not supported for remote workers.")
    }
    suspend fun setWorkerModel(
        worker: WorkerRunRecord,
        providerId: String,
        modelId: String,
        thinkingLevel: ThinkingLevel,
    ) {
        throw UnsupportedOperationException("Model switching is not supported for remote workers.")
    }
    suspend fun setWorkerThinking(worker: WorkerRunRecord, thinkingLevel: ThinkingLevel) {
        throw UnsupportedOperationException("Thinking level switching is not supported for remote workers.")
    }
    suspend fun answerApproval(
        worker: WorkerRunRecord,
        requestId: String,
        answers: List<QuestionAnswer>,
        bySuper: Boolean = false,
    )
    suspend fun dismissApproval(
        worker: WorkerRunRecord,
        requestId: String,
        bySuper: Boolean = false,
    )
    suspend fun inspectWorker(worker: WorkerRunRecord, tail: Int = 30): WorkerInspectResult
    suspend fun deleteWorker(worker: WorkerRunRecord)
    suspend fun resolveArtifactFile(artifact: WorkerArtifactRef): File?
    fun dismissWorkerError(workerId: String) {}
    fun isQuestionEscalatedToSuper(workerId: String): Boolean = false
    fun maxSlotsForDevice(deviceId: String): Int? = null
    suspend fun onSettingsChanged() {}
}

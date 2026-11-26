package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.config.HostSuperSettings
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.InputQueueKind
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryOverview
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QueuedInput
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.TurnNotice
import io.github.whywood.superpi.runtime.WorkerRunRecord
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class ServeLockInfo(
    val pid: Long,
    val port: Int,
    val token: String,
    val startedAt: Long,
)

@Serializable
data class ApiErrorResponse(
    val error: String,
)

@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val version: String = AppBrand.VERSION,
)

@Serializable
data class StatusResponse(
    val version: String,
    val homeDir: String,
    val pid: Long,
    val port: Int,
    val relayState: String,
    val relayOrigin: String? = null,
    val relayDeviceId: String? = null,
    val relayDeviceName: String? = null,
    val botsCount: Int,
    val busySessionsCount: Int,
    val mcpServersCount: Int,
    val memoryEntities: Int,
    val memoryEvents: Int,
    val activeTimersCount: Int,
)

@Serializable
data class BotDto(
    val id: String,
    val name: String,
    val cwd: String,
    val isHome: Boolean,
    val avatar: String? = null,
    val intro: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val hasAgentsDoc: Boolean = false,
    val memoryScope: String = "personal",
    val memoryDispatch: Boolean = false,
    val isRemote: Boolean = false,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val remoteBotId: String? = null,
    val online: Boolean = true,
    val capabilities: List<String> = emptyList(),
    val permissionMode: String = "workspace_auto",
)

@Serializable
data class AddBotRequest(
    val dir: String? = null,
    val name: String? = null,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val intro: String? = null,
    val targetDeviceId: String? = null,
    val avatar: String? = null,
)

@Serializable
data class SlashCommandDto(
    val name: String,
    val description: String = "",
    val source: String = "builtin",
)

@Serializable
data class RemoteBotModelsResponse(
    val providers: List<ProviderDto> = emptyList(),
    val currentProviderId: String? = null,
    val currentModelId: String? = null,
    val currentThinkingLevel: String? = null,
)

@Serializable
data class UpdateBotRequest(
    val name: String? = null,
    val intro: String? = null,
    val clearIntro: Boolean = false,
    val avatar: String? = null,
    val clearAvatar: Boolean = false,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val updateDefaultModel: Boolean = false,
)

@Serializable
data class AgentDocDto(
    val exists: Boolean,
    val fileName: String = "AGENTS.md",
    val content: String = "",
    val isOverride: Boolean = false,
)

@Serializable
data class SaveAgentDocRequest(
    val content: String,
)

@Serializable
data class BotPolicyDto(
    val allCapabilities: Boolean = true,
    val skills: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val availableSkills: List<String> = emptyList(),
    val availableMcpServers: List<String> = emptyList(),
    val memoryScope: String = "personal",
    val memoryDispatch: Boolean = false,
    val superEnabled: Boolean = false,
)

@Serializable
data class UpdateBotPolicyRequest(
    val allCapabilities: Boolean? = null,
    val skills: List<String>? = null,
    val mcpServers: List<String>? = null,
    val memoryScope: String? = null,
    val memoryDispatch: Boolean? = null,
    val superEnabled: Boolean? = null,
)

@Serializable
data class RelayStatusDto(
    val enrolled: Boolean,
    val state: String,
    val origin: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val allowRemote: Boolean = true,
)

@Serializable
data class RelayDeviceDto(
    val deviceId: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val lastSeen: Long,
    val botCount: Int,
    val capabilities: List<String> = emptyList(),
    val isSelf: Boolean = false,
)

@Serializable
data class RenameDeviceRequest(
    val name: String,
)

@Serializable
data class AllowRemoteRequest(
    val allowRemote: Boolean,
)

@Serializable
data class QuestionOptionDto(
    val label: String,
    val description: String? = null,
)

@Serializable
data class UserQuestionDto(
    val id: String,
    val header: String? = null,
    val question: String,
    val options: List<QuestionOptionDto> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class PendingQuestionDto(
    val requestId: String,
    val sessionId: String,
    val toolCallId: String,
    val questions: List<UserQuestionDto> = emptyList(),
)

@Serializable
data class QuestionAnswerDto(
    val questionId: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
    val skipped: Boolean = false,
)

@Serializable
data class AnswerQuestionRequest(
    val answers: List<QuestionAnswerDto> = emptyList(),
)

@Serializable
data class PairCodeResponse(
    val link: String,
    val expiresAt: Long? = null,
)

@Serializable
data class RelayPairRequest(
    val link: String,
    val loginSecret: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class SseTicketResponse(
    val ticket: String,
    val expiresInSeconds: Int = 30,
)

@Serializable
data class SessionSummaryDto(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val filePath: String,
    val cwd: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val memoryExcluded: Boolean = false,
    val busy: Boolean = false,
    val worktreeId: String? = null,
    val worktreeBranch: String? = null,
)

@Serializable
data class CreateSessionRequest(
    val useWorktree: Boolean = false,
    val label: String? = null,
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val filePath: String? = null,
    val cwd: String,
    val worktreeId: String? = null,
    val worktreeBranch: String? = null,
)

@Serializable
data class ToolTraceDto(
    val id: Long,
    val callId: String,
    val name: String,
    val arguments: Map<String, String> = emptyMap(),
    val status: String,
    val output: String? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
    val durationMs: Long? = null,
)

@Serializable
data class MessageStepDto(
    val kind: String,
    val id: Long,
    val text: String? = null,
    val tool: ToolTraceDto? = null,
    val customType: String? = null,
    val payload: String? = null,
)

@Serializable
data class MessageSummaryDto(
    val kind: String,
    val text: String,
    val tokensBefore: Long? = null,
    val tokensAfter: Long? = null,
    val fromId: String? = null,
)

@Serializable
data class MessageDto(
    val id: Long,
    val role: String,
    val text: String,
    val streaming: Boolean = false,
    val status: String,
    val startedAt: Long = 0L,
    val finishedAt: Long? = null,
    val runId: String = "",
    val error: String? = null,
    val reminder: String? = null,
    val retrying: Boolean = false,
    val notice: String? = null,
    val summary: MessageSummaryDto? = null,
    val customType: String? = null,
    val customPayload: String? = null,
    val injectedSource: String? = null,
    val steps: List<MessageStepDto> = emptyList(),
)

@Serializable
data class SessionModelDto(
    val providerId: String,
    val modelId: String,
    val thinkingLevel: String,
)

@Serializable
data class SessionUsageDto(
    val contextTokens: Long = 0L,
    val contextWindow: Long = 0L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val cost: Double = 0.0,
    val compactionTriggerPercent: Int = 75,
)

@Serializable
data class CompactResponse(
    val ok: Boolean,
    val status: String,
    val tokensBefore: Long? = null,
    val tokensAfter: Long? = null,
    val message: String? = null,
)

@Serializable
data class QueuedInputDto(
    val kind: String,
    val text: String,
)

@Serializable
data class RemoveQueueItemRequest(
    val kind: String? = null,
    val text: String? = null,
    val clearAll: Boolean = false,
)

@Serializable
data class PromoteQueueItemRequest(
    val text: String,
)

@Serializable
data class ImageAttachmentDto(
    val data: String,
    val mimeType: String,
    val name: String? = null,
)

@Serializable
data class StageAttachmentRequest(
    val name: String,
    val mimeType: String = "",
    val dataBase64: String,
)

@Serializable
data class StagedAttachmentDto(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val path: String,
    val relativePath: String,
    val kind: String,
)

@Serializable
data class SessionDetailDto(
    val sessionId: String,
    val title: String? = null,
    val cwd: String? = null,
    val busy: Boolean = false,
    val model: SessionModelDto? = null,
    val usage: SessionUsageDto? = null,
    val messages: List<MessageDto> = emptyList(),
    val queuedInputs: List<QueuedInputDto> = emptyList(),
    val pendingQuestion: PendingQuestionDto? = null,
    val worktreeId: String? = null,
    val worktreeBranch: String? = null,
    val worktreeBaseBranch: String? = null,
)

@Serializable
data class UpdateSessionRequest(
    val title: String? = null,
    val pinned: Boolean? = null,
    val archived: Boolean? = null,
    val memoryExcluded: Boolean? = null,
)

@Serializable
data class PromptRequest(
    val text: String = "",
    val steer: Boolean = false,
    val planFirst: Boolean = false,
    val images: List<ImageAttachmentDto> = emptyList(),
)

@Serializable
data class SetModelRequest(
    val providerId: String,
    val modelId: String,
    val thinkingLevel: String = ThinkingLevel.OFF.name,
)

@Serializable
data class WorkspaceFileEntryDto(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
)

@Serializable
data class WorkspaceFileListResponse(
    val botId: String,
    val root: String,
    val path: String,
    val entries: List<WorkspaceFileEntryDto>,
)

@Serializable
data class WorkspaceFileReadResponse(
    val botId: String,
    val path: String,
    val content: String = "",
    val sizeBytes: Long = 0L,
    val truncated: Boolean = false,
    val isBinary: Boolean = false,
)

@Serializable
data class UploadWorkspaceFileRequest(
    val path: String,
    val dataBase64: String,
    val overwrite: Boolean = true,
)

@Serializable
data class WorkspaceMkdirRequest(
    val path: String,
)

@Serializable
data class WorkspaceRenameRequest(
    val from: String,
    val to: String,
)

@Serializable
data class WorkspaceSearchResponse(
    val botId: String,
    val query: String,
    val entries: List<WorkspaceFileEntryDto> = emptyList(),
)

@Serializable
data class GitChangedFileDto(
    val path: String,
    val indexStatus: String,
    val workTreeStatus: String,
)

@Serializable
data class GitStatusResponse(
    val isGitRepo: Boolean,
    val branch: String? = null,
    val files: List<GitChangedFileDto> = emptyList(),
)

@Serializable
data class GitDiffResponse(
    val path: String? = null,
    val diff: String,
    val truncated: Boolean = false,
)

@Serializable
data class WorkerStepDto(
    val label: String,
    val status: String,
    val outputPreview: String? = null,
    val isError: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class WorkerDto(
    val workerId: String,
    val botId: String,
    val botName: String,
    val title: String,
    val status: String,
    val lastOutcome: String,
    val turnCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSummary: String? = null,
    val lastError: String? = null,
    val activeStepText: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val isRemote: Boolean = false,
    val pendingQuestion: PendingQuestionDto? = null,
    val steps: List<WorkerStepDto> = emptyList(),
)

@Serializable
data class WorkerDetailDto(
    val worker: WorkerDto,
    val messages: List<MessageDto> = emptyList(),
    val streaming: Boolean = false,
    val activeToolText: String? = null,
    val providerId: String = "",
    val modelId: String = "",
    val modelLabel: String = "",
    val thinkingLevel: String = "OFF",
    val thinkingLevels: List<String> = listOf("OFF"),
    val usage: SessionUsageDto? = null,
    val loaded: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SuperTeamBotDto(
    val ref: String,
    val botId: String,
    val name: String,
    val avatar: String? = null,
    val intro: String = "",
    val deviceId: String? = null,
    val deviceName: String = "",
    val isRemote: Boolean = false,
    val online: Boolean = true,
    val supportsSuperWorker: Boolean = true,
    val enabledInSuper: Boolean = false,
    val usedWorkerSlots: Int = 0,
    val maxWorkerSlots: Int = 3,
    val skills: List<String> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val memoryScope: String = "personal",
)

@Serializable
data class SetSuperTeamRequest(
    val botRef: String,
    val enabled: Boolean,
)

@Serializable
data class SuperSettingsDto(
    val maxConcurrentWorkers: Int = 5,
    val workerMaxSlotsPerBot: Int = 3,
    val workerIdleCleanupMinutes: Int = 5,
    val workerStallTimeoutMinutes: Int = 10,
    val questionTimeoutMinutes: Int = 3,
    val coalesceWindowMs: Long = 1500L,
    val receiptMaxChars: Int = 1500,
    val workerJobWake: Boolean = true,
    val workerCompactionOverrideEnabled: Boolean = false,
    val workerCompactionTriggerPercent: Int = 75,
)

@Serializable
data class UpdateSuperSettingsRequest(
    val maxConcurrentWorkers: Int? = null,
    val workerMaxSlotsPerBot: Int? = null,
    val workerIdleCleanupMinutes: Int? = null,
    val workerStallTimeoutMinutes: Int? = null,
    val questionTimeoutMinutes: Int? = null,
    val coalesceWindowMs: Long? = null,
    val receiptMaxChars: Int? = null,
    val workerJobWake: Boolean? = null,
    val workerCompactionOverrideEnabled: Boolean? = null,
    val workerCompactionTriggerPercent: Int? = null,
)

@Serializable
data class SuperStateResponse(
    val busy: Boolean,
    val ready: Boolean,
    val providerId: String,
    val modelId: String,
    val modelLabel: String,
    val thinkingLevel: String,
    val thinkingLevels: List<String> = listOf("OFF"),
    val usage: SessionUsageDto? = null,
    val archivedCount: Int = 0,
    val activeToolText: String? = null,
    val error: String? = null,
    val pendingQuestion: PendingQuestionDto? = null,
    val messages: List<MessageDto> = emptyList(),
    val workers: List<WorkerDto> = emptyList(),
)

@Serializable
data class MemoryOverviewDto(
    val activeBlocks: Int,
    val archivedBlocks: Int,
    val activeEvents: Int,
    val entityCount: Int,
    val relationCount: Int,
    val pendingProposals: Int,
    val pendingDispatchJobs: Int,
    val lastConsolidationAt: Long? = null,
)

@Serializable
data class MemoryBlockDto(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val content: String,
    val domain: String,
    val pinned: Boolean,
    val owner: String,
    val status: String,
    val updatedAt: Long,
)

@Serializable
data class MemoryEntityDto(
    val id: String,
    val canonicalName: String,
    val entityType: String,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val domain: String,
    val pinned: Boolean,
    val updatedAt: Long,
)

@Serializable
data class MemoryRelationDto(
    val id: String,
    val fromEntityId: String,
    val fromEntityName: String,
    val relationType: String,
    val toEntityId: String,
    val toEntityName: String,
    val label: String? = null,
    val isActive: Boolean,
)

@Serializable
data class MemoryEventDto(
    val id: String,
    val eventKind: String,
    val content: String,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val entityNames: List<String> = emptyList(),
    val domain: String,
    val importance: Int,
    val pinned: Boolean,
    val eventTime: Long,
    val createdAt: Long,
    val status: String = "ACTIVE",
    val epistemicTier: String = "OBSERVED",
    val sourceSessionId: String? = null,
    val sourceQuote: String? = null,
)

@Serializable
data class MemoryEntityDetailDto(
    val entity: MemoryEntityDto,
    val outgoingRelations: List<MemoryRelationDto> = emptyList(),
    val incomingRelations: List<MemoryRelationDto> = emptyList(),
    val linkedEvents: List<MemoryEventDto> = emptyList(),
)

@Serializable
data class MemorySyncPromptDto(
    val accountKey: String,
    val accountIdentity: String,
    val localRecordCount: Int,
)

@Serializable
data class MemorySyncStatusDto(
    val status: String = "idle",
    val lastSyncedAt: Long? = null,
    val lastErrorCode: String? = null,
    val accountSwitchPrompt: MemorySyncPromptDto? = null,
)

@Serializable
data class ResolveMemorySyncRequest(
    val mode: String,
)

@Serializable
data class MemoryAuditDto(
    val id: String,
    val actor: String,
    val actionType: String,
    val targetTable: String,
    val targetId: String,
    val summaryUserText: String,
    val reason: String? = null,
    val createdAt: Long,
    val revertedAt: Long? = null,
    val canRevert: Boolean,
)

@Serializable
data class MemorySnapshotResponse(
    val overview: MemoryOverviewDto,
    val blocks: List<MemoryBlockDto>,
    val entities: List<MemoryEntityDto>,
    val relations: List<MemoryRelationDto>,
    val events: List<MemoryEventDto>,
    val auditLog: List<MemoryAuditDto>,
)

@Serializable
data class UpdateMemoryBlockRequest(
    val title: String? = null,
    val content: String? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class GoalDto(
    val id: String,
    val objective: String,
    val phase: String,
    val roundsStarted: Int,
    val maxRounds: Int,
    val blockedReason: String? = null,
    val armed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class GoalResponse(
    val goal: GoalDto? = null,
)

@Serializable
data class CreateGoalRequest(
    val objective: String,
    val maxRounds: Int? = null,
)

@Serializable
data class JobSnapshotDto(
    val id: String,
    val sessionId: String,
    val label: String,
    val command: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    val detail: String? = null,
    val outputBytes: Long = 0L,
)

@Serializable
data class JobOutputDto(
    val jobId: String,
    val output: String,
    val status: String,
    val exitCode: Int? = null,
    val truncated: Boolean = false,
)

@Serializable
data class SettingsDto(
    val deviceName: String,
    val defaultProvider: String,
    val defaultModel: String,
    val defaultThinkingLevel: String,
    val compactionTriggerPercent: Int,
    val servePort: Int,
    val language: String = "system",
    val sendKey: String = "enter",
    val sendWhileRunning: String = "steer",
    val chatFontScale: Int = 100,
    val processDetail: String = "detailed",
    val autoRetry: Boolean = true,
    val backgroundJobWake: Boolean = true,
    val userNickname: String = "",
    val userAvatar: String = "",
    val customSystemInstruction: String = "",
    val osName: String,
    val arch: String,
    val shellName: String,
    val homeDir: String,
)

@Serializable
data class PatchSettingsRequest(
    val deviceName: String? = null,
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val compactionTriggerPercent: Int? = null,
    val language: String? = null,
    val sendKey: String? = null,
    val sendWhileRunning: String? = null,
    val chatFontScale: Int? = null,
    val processDetail: String? = null,
    val autoRetry: Boolean? = null,
    val backgroundJobWake: Boolean? = null,
    val userNickname: String? = null,
    val userAvatar: String? = null,
    val clearUserAvatar: Boolean = false,
    val customSystemInstruction: String? = null,
)

@Serializable
data class ModelOptionDto(
    val id: String,
    val displayName: String,
    val thinkingLevels: List<String>,
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
    val baseUrl: String? = null,
    val supportsApiKey: Boolean,
    val hasOAuth: Boolean,
    val isCustom: Boolean = false,
    val configured: Boolean,
    val keyHint: String? = null,
    val hasEnvKey: Boolean = false,
    val models: List<ModelOptionDto> = emptyList(),
)

@Serializable
data class SaveProviderKeyRequest(
    val apiKey: String,
)

@Serializable
data class McpServerDto(
    val name: String,
    val transport: String,
    val target: String,
    val enabled: Boolean,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val hasAuthToken: Boolean = false,
    val probeState: String? = null,
    val toolCount: Int? = null,
    val error: String? = null,
)

@Serializable
data class SseEventPayload(
    val type: String,
    val sessionId: String? = null,
    val workerId: String? = null,
    val runId: String? = null,
    val text: String? = null,
    val callId: String? = null,
    val toolName: String? = null,
    val arguments: Map<String, String>? = null,
    val output: String? = null,
    val isError: Boolean? = null,
    val durationMs: Long? = null,
    val status: String? = null,
    val activity: String? = null,
    val customType: String? = null,
    val payload: String? = null,
    val steering: List<String>? = null,
    val followUp: List<String>? = null,
    val archived: Boolean? = null,
    val deviceId: String? = null,
    val usage: SessionUsageDto? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

internal fun SessionUsage.toDto(compactionTriggerPercent: Int): SessionUsageDto = SessionUsageDto(
    contextTokens = contextTokens,
    contextWindow = contextWindow,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheReadTokens = cacheReadTokens,
    cacheWriteTokens = cacheWriteTokens,
    cost = cost,
    compactionTriggerPercent = compactionTriggerPercent,
)

internal fun ToolTrace.toDto(): ToolTraceDto = ToolTraceDto(
    id = id,
    callId = toolCallId,
    name = name,
    arguments = arguments,
    status = status.name.lowercase(),
    output = output,
    error = error,
    startedAt = startedAt,
    durationMs = durationMs,
)

internal fun AssistantMessage.toDto(): MessageDto = MessageDto(
    id = id,
    role = role.name.lowercase(),
    text = text,
    streaming = streaming,
    status = status.name.lowercase(),
    startedAt = startedAt,
    finishedAt = finishedAt,
    runId = runId,
    error = error?.takeIf { it.isNotBlank() } ?: rawError?.takeIf { it.isNotBlank() },
    reminder = reminder?.takeIf { it.isNotBlank() },
    retrying = retrying,
    notice = notice?.let(::formatTurnNotice),
    summary = summary?.let { s ->
        MessageSummaryDto(
            kind = s.kind.name.lowercase(),
            text = s.text,
            tokensBefore = s.tokensBefore,
            tokensAfter = s.tokensAfter,
            fromId = s.fromId,
        )
    },
    customType = customType?.takeIf { it.isNotBlank() },
    customPayload = customPayload?.takeIf { it.isNotBlank() },
    injectedSource = injectedSource?.name?.lowercase(),
    steps = steps.map { step ->
        when (step) {
            is AssistantStep.Reasoning -> MessageStepDto(kind = "reasoning", id = step.id, text = step.text)
            is AssistantStep.Tool -> MessageStepDto(kind = "tool", id = step.id, tool = step.trace.toDto())
            is AssistantStep.Text -> MessageStepDto(kind = "text", id = step.id, text = step.text)
            is AssistantStep.Notice -> MessageStepDto(
                kind = "notice",
                id = step.id,
                text = formatTurnNotice(step.notice),
            )
            is AssistantStep.CustomCard -> MessageStepDto(
                kind = "custom_card",
                id = step.id,
                text = step.text,
                customType = step.customType,
                payload = step.payload,
            )
        }
    },
)

internal fun WorkerRunRecord.toDto(): WorkerDto = WorkerDto(
    workerId = workerId,
    botId = botId,
    botName = botName,
    title = title,
    status = status.name.lowercase(),
    lastOutcome = lastOutcome.name.lowercase(),
    turnCount = turnCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastSummary = lastSummary,
    lastError = lastError,
    activeStepText = activeStepText,
    deviceId = deviceId,
    deviceName = deviceName.takeIf { it.isNotBlank() },
    isRemote = isRemote,
    pendingQuestion = pendingQuestion?.toDto(),
    steps = steps.map { s ->
        WorkerStepDto(
            label = s.toolName,
            status = s.status.name.lowercase(),
            outputPreview = s.outputPreview,
            isError = s.isError,
            updatedAt = s.updatedAt,
        )
    },
)

internal fun PendingQuestion.toDto(): PendingQuestionDto = PendingQuestionDto(
    requestId = requestId,
    sessionId = sessionId,
    toolCallId = toolCallId,
    questions = questions.map { q ->
        UserQuestionDto(
            id = q.id,
            header = q.header,
            question = q.question,
            options = q.options.map { QuestionOptionDto(label = it.label, description = it.description) },
            multiSelect = q.multiSelect,
        )
    },
)

internal fun QuestionAnswerDto.toDomain(): QuestionAnswer = QuestionAnswer(
    questionId = questionId,
    selected = selected,
    custom = custom,
    skipped = skipped,
)

internal fun QueuedInput.toDto(): QueuedInputDto = QueuedInputDto(
    kind = when (kind) {
        InputQueueKind.STEER -> "steer"
        InputQueueKind.FOLLOW_UP -> "follow_up"
    },
    text = text,
)

internal fun BotBlueprint.toTeamDto(): SuperTeamBotDto = SuperTeamBotDto(
    ref = ref,
    botId = botId,
    name = name,
    avatar = avatar,
    intro = intro,
    deviceId = deviceId,
    deviceName = deviceName,
    isRemote = isRemote,
    online = online,
    supportsSuperWorker = supportsSuperWorker,
    enabledInSuper = enabledInSuper,
    usedWorkerSlots = usedWorkerSlots,
    maxWorkerSlots = maxWorkerSlots,
    skills = capabilities.skills,
    mcpServers = capabilities.mcpServers,
    memoryScope = capabilities.memoryScope.wire.lowercase(),
)

internal fun HostSuperSettings.toDto(): SuperSettingsDto = SuperSettingsDto(
    maxConcurrentWorkers = maxConcurrentWorkers,
    workerMaxSlotsPerBot = workerMaxSlotsPerBot,
    workerIdleCleanupMinutes = workerIdleCleanupMinutes,
    workerStallTimeoutMinutes = workerStallTimeoutMinutes,
    questionTimeoutMinutes = questionTimeoutMinutes,
    coalesceWindowMs = coalesceWindowMs,
    receiptMaxChars = receiptMaxChars,
    workerJobWake = workerJobWake,
    workerCompactionOverrideEnabled = workerCompactionOverrideEnabled,
    workerCompactionTriggerPercent = workerCompactionTriggerPercent,
)

private val ALLOWED_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
private const val MAX_DECODED_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024

private fun matchesImageMagic(mime: String, bytes: ByteArray): Boolean = when (mime) {
    "image/png" -> bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    "image/jpeg" -> bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    "image/gif" -> bytes.size >= 6 &&
        bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
    "image/webp" -> bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
    else -> false
}

internal fun List<ImageAttachmentDto>.toImageParts(): List<ImagePart> {
    if (isEmpty()) return emptyList()
    if (size > 8) throw IllegalArgumentException("At most 8 images can be attached per message.")
    val maxEncodedLen = ((MAX_DECODED_IMAGE_BYTES + 2) / 3) * 4
    var totalDecoded = 0
    return map { item ->
        val mime = item.mimeType.trim().lowercase()
        if (mime !in ALLOWED_IMAGE_MIMES) {
            throw IllegalArgumentException("Unsupported image type: ${item.mimeType}")
        }
        val cleanData = item.data.substringAfter("base64,", item.data).trim()
        if (cleanData.isEmpty()) {
            throw IllegalArgumentException("Image payload must not be empty.")
        }
        if (cleanData.length > maxEncodedLen) {
            throw IllegalArgumentException("Image exceeds maximum size of 10 MB.")
        }
        val decoded = try {
            Base64.getDecoder().decode(cleanData)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base64 image payload.")
        }
        if (decoded.size > MAX_DECODED_IMAGE_BYTES) {
            throw IllegalArgumentException("Image exceeds maximum size of 10 MB.")
        }
        totalDecoded += decoded.size
        if (totalDecoded > MAX_TOTAL_IMAGE_BYTES) {
            throw IllegalArgumentException("Total attached images exceed maximum size of 20 MB.")
        }
        if (!matchesImageMagic(mime, decoded)) {
            throw IllegalArgumentException("Image content does not match declared MIME type: $mime")
        }
        ImagePart(
            data = cleanData,
            mimeType = mime,
            path = item.name?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

private fun formatTurnNotice(notice: TurnNotice): String = when (notice) {
    is TurnNotice.Compacted -> "已压缩上下文（原 ${notice.tokensBefore} tokens）"
    is TurnNotice.GoalRound -> "目标推进第 ${notice.round}/${notice.maxRounds} 轮：${notice.objective}"
    is TurnNotice.GoalFinished -> if (notice.completed) "目标已完成" else "目标已结束"
    is TurnNotice.JobFinished -> if (notice.failed) "后台任务失败：${notice.label}" else "后台任务完成：${notice.label}"
    is TurnNotice.InterruptedQuestions -> "存在 ${notice.count} 个未完成的确认请求"
    is TurnNotice.InterruptedRun -> "上次运行已中断"
    is TurnNotice.CapabilitiesChanged -> "工具能力已更新"
}

internal fun MemoryOverview.toDto() = MemoryOverviewDto(
    activeBlocks = activeBlocks,
    archivedBlocks = archivedBlocks,
    activeEvents = activeEvents,
    entityCount = entityCount,
    relationCount = relationCount,
    pendingProposals = pendingProposals,
    pendingDispatchJobs = pendingDispatchJobs,
    lastConsolidationAt = lastConsolidationAt,
)

internal fun MemoryBlock.toDto() = MemoryBlockDto(
    id = id,
    slug = slug,
    title = title,
    purpose = purpose,
    content = content,
    domain = domain,
    pinned = pinned,
    owner = owner.wire,
    status = status.wire,
    updatedAt = updatedAt,
)

internal fun MemoryEntity.toDto() = MemoryEntityDto(
    id = id,
    canonicalName = canonicalName,
    entityType = entityType,
    aliases = aliases,
    summary = summary,
    domain = domain,
    pinned = pinned,
    updatedAt = updatedAt,
)

internal fun MemoryEntityRelation.toDto() = MemoryRelationDto(
    id = id,
    fromEntityId = fromEntityId,
    fromEntityName = fromEntityName,
    relationType = relationType,
    toEntityId = toEntityId,
    toEntityName = toEntityName,
    label = label,
    isActive = isActive,
)

internal fun MemoryEvent.toDto() = MemoryEventDto(
    id = id,
    eventKind = eventKind.wire,
    content = content,
    summary = summary,
    tags = tags,
    entityNames = entityNames,
    domain = domain,
    importance = importance,
    pinned = pinned,
    eventTime = eventTime,
    createdAt = createdAt,
    status = status.wire,
    epistemicTier = epistemicTier.wire,
    sourceSessionId = sourceSessionId,
    sourceQuote = sourceQuote,
)

internal fun MemoryAuditEntry.toDto() = MemoryAuditDto(
    id = id,
    actor = actor.name,
    actionType = actionType,
    targetTable = targetTable,
    targetId = targetId,
    summaryUserText = summaryUserText,
    reason = reason,
    createdAt = createdAt,
    revertedAt = revertedAt,
    canRevert = canRevert,
)

@Serializable
data class UpdateInboxStatusRequest(
    val status: String,
)

internal fun parseControlModelSpec(raw: String): ModelConfig? {
    val slash = raw.indexOf('/')
    if (slash <= 0 || slash >= raw.lastIndex) return null
    return ModelConfig(
        providerId = raw.substring(0, slash).trim(),
        modelId = raw.substring(slash + 1).trim(),
        thinkingLevel = ThinkingLevel.OFF,
    )
}

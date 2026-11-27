export type ThinkingLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";
export type PermissionMode = "read_only" | "ask_commands" | "workspace_auto" | "full_auto";

export interface HostHealthDto {
  ok: boolean;
  version: string;
}

export interface HostStatusDto {
  version: string;
  homeDir: string;
  pid: number;
  port: number;
  relayState: string;
  relayOrigin?: string | null;
  relayDeviceId?: string | null;
  relayDeviceName?: string | null;
  botsCount: number;
  busySessionsCount: number;
  mcpServersCount: number;
  memoryEntities: number;
  memoryEvents: number;
  activeTimersCount: number;
}

export interface HostBotDto {
  id: string;
  name: string;
  cwd: string;
  isHome: boolean;
  avatar?: string | null;
  intro?: string | null;
  defaultModel?: string | null;
  defaultThinkingLevel?: string | null;
  hasAgentsDoc?: boolean;
  memoryScope?: string;
  memoryDispatch?: boolean;
  isRemote?: boolean;
  deviceId?: string | null;
  deviceName?: string | null;
  remoteBotId?: string | null;
  online?: boolean;
  capabilities?: string[];
  permissionMode?: string;
}

export interface HostSlashCommandDto {
  name: string;
  description: string;
  source: string;
}

export interface HostModelOptionDto {
  id: string;
  displayName: string;
  thinkingLevels: string[];
}

export interface HostProviderDto {
  id: string;
  name: string;
  baseUrl?: string | null;
  supportsApiKey: boolean;
  hasOAuth: boolean;
  isCustom?: boolean;
  configured: boolean;
  keyHint?: string | null;
  hasEnvKey?: boolean;
  models: HostModelOptionDto[];
}

export interface HostBotModelsResponse {
  providers: HostProviderDto[];
  currentProviderId?: string | null;
  currentModelId?: string | null;
  currentThinkingLevel?: string | null;
}

export interface HostAgentDocDto {
  exists: boolean;
  fileName: string;
  content: string;
  isOverride: boolean;
}

export interface HostBotPolicyDto {
  allCapabilities: boolean;
  skills: string[];
  mcpServers: string[];
  availableSkills: string[];
  availableMcpServers: string[];
  memoryScope: string;
  memoryDispatch: boolean;
  superEnabled: boolean;
}

export interface HostSessionSummaryDto {
  id: string;
  title: string;
  updatedAt: number;
  filePath: string;
  cwd: string;
  pinned: boolean;
  archived: boolean;
  memoryExcluded: boolean;
  busy: boolean;
  worktreeId?: string | null;
  worktreeBranch?: string | null;
}

export interface HostToolTraceDto {
  id: number;
  callId: string;
  name: string;
  arguments: Record<string, string>;
  status: string;
  output?: string | null;
  error?: string | null;
  startedAt: number;
  durationMs?: number | null;
}

export interface HostMessageStepDto {
  kind: string;
  id: number;
  text?: string | null;
  tool?: HostToolTraceDto | null;
  customType?: string | null;
  payload?: string | null;
}

export interface HostMessageSummaryDto {
  kind: string;
  text: string;
  tokensBefore?: number | null;
  tokensAfter?: number | null;
  fromId?: string | null;
}

export interface HostMessageDto {
  id: number;
  role: string;
  text: string;
  streaming: boolean;
  status: string;
  startedAt: number;
  finishedAt?: number | null;
  runId: string;
  error?: string | null;
  reminder?: string | null;
  retrying: boolean;
  notice?: string | null;
  summary?: HostMessageSummaryDto | null;
  customType?: string | null;
  customPayload?: string | null;
  injectedSource?: string | null;
  steps: HostMessageStepDto[];
}

export interface HostUsageDto {
  contextTokens: number;
  contextWindow: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  cost: number;
  compactionTriggerPercent: number;
}

export interface HostQueuedInputDto {
  kind: "steer" | "follow_up";
  text: string;
}

export interface HostQuestionOptionDto {
  label: string;
  description?: string | null;
}

export interface HostUserQuestionDto {
  id: string;
  header?: string | null;
  question: string;
  options: HostQuestionOptionDto[];
  multiSelect: boolean;
}

export interface HostPendingQuestionDto {
  requestId: string;
  sessionId: string;
  toolCallId: string;
  questions: HostUserQuestionDto[];
}

export type ToolRiskLevel = "standard" | "outside_workspace" | "destructive";

export type ToolApprovalReason =
  | "command_execution"
  | "outside_workspace_write"
  | "destructive_command";

export type ToolApprovalDecision = "allow_once" | "allow_session" | "deny";

export interface HostPendingToolApprovalDto {
  id: string;
  sessionId: string;
  workerId?: string | null;
  toolCallId: string;
  toolName: string;
  riskLevel: ToolRiskLevel;
  reason: ToolApprovalReason;
  command?: string | null;
  path?: string | null;
  workspaceRoot?: string | null;
  detail: string;
  createdAt: number;
}

export interface HostSessionDetailDto {
  sessionId: string;
  title?: string | null;
  cwd?: string | null;
  busy: boolean;
  model?: {
    providerId: string;
    modelId: string;
    thinkingLevel: string;
  } | null;
  usage?: HostUsageDto | null;
  messages: HostMessageDto[];
  queuedInputs: HostQueuedInputDto[];
  pendingQuestion?: HostPendingQuestionDto | null;
  pendingApprovals?: HostPendingToolApprovalDto[];
  worktreeId?: string | null;
  worktreeBranch?: string | null;
  worktreeBaseBranch?: string | null;
}

export interface HostCompactResponse {
  ok: boolean;
  status: string;
  tokensBefore?: number | null;
  tokensAfter?: number | null;
  message?: string | null;
}

export interface HostStagedAttachmentDto {
  id: string;
  name: string;
  mime: string;
  size: number;
  path: string;
  relativePath: string;
  kind: string;
}

export interface HostSettingsDto {
  deviceName: string;
  defaultProvider: string;
  defaultModel: string;
  defaultThinkingLevel: string;
  compactionTriggerPercent: number;
  servePort: number;
  language: string;
  sendKey: string;
  sendWhileRunning: string;
  chatFontScale: number;
  processDetail: string;
  autoRetry: boolean;
  backgroundJobWake: boolean;
  userNickname: string;
  userAvatar: string;
  customSystemInstruction: string;
  osName: string;
  arch: string;
  shellName: string;
  homeDir: string;
}

export interface HostCustomModelDto {
  id: string;
  name: string;
  contextWindow?: number | null;
  maxTokens?: number | null;
  supportsImages: boolean;
  reasoning: boolean;
  thinkingLevels: string[];
}

export interface HostCustomProviderDto {
  id: string;
  name: string;
  api: string;
  baseUrl: string;
  keyHint?: string | null;
  models: HostCustomModelDto[];
}

export interface HostOAuthStatusDto {
  providerId: string;
  status: string;
  url?: string | null;
  instructions?: string | null;
  message?: string | null;
  promptLabel?: string | null;
  promptPlaceholder?: string | null;
  promptSecret: boolean;
  options: Array<{ id: string; label: string; description?: string | null }>;
  error?: string | null;
}

export interface HostRelayStatusDto {
  enrolled: boolean;
  state: string;
  origin?: string | null;
  deviceId?: string | null;
  deviceName?: string | null;
  allowRemote: boolean;
}

export interface HostRelayDeviceDto {
  deviceId: string;
  name: string;
  platform: string;
  online: boolean;
  lastSeen: number;
  botCount: number;
  capabilities: string[];
  isSelf: boolean;
}

export interface HostSseEvent {
  type: string;
  sessionId?: string | null;
  workerId?: string | null;
  runId?: string | null;
  text?: string | null;
  callId?: string | null;
  toolName?: string | null;
  arguments?: Record<string, string> | null;
  output?: string | null;
  isError?: boolean | null;
  durationMs?: number | null;
  status?: string | null;
  activity?: string | null;
  customType?: string | null;
  payload?: string | null;
  steering?: string[] | null;
  followUp?: string[] | null;
  archived?: boolean | null;
  deviceId?: string | null;
  usage?: HostUsageDto | null;
  timestamp?: number;
}

export interface ToolTraceItem {
  id: string;
  name: string;
  summary: string;
  rawArgs: Record<string, string>;
  argsText: string;
  output: string;
  status: "running" | "done" | "failed";
  durationMs: number;
}

export type ActivityStep =
  | { id: string; kind: "thinking"; text: string }
  | { id: string; kind: "tool"; tool: ToolTraceItem };

export interface PresentedItem {
  type: "file" | "image" | "video" | "html" | "miniapp" | "card";
  path?: string;
  id?: string;
  name: string;
  mime?: string;
  title?: string;
}

export interface AnsweredQuestionEntry {
  id: string;
  header?: string;
  question: string;
  selected: string[];
  custom?: string;
  skipped: boolean;
}

export interface CustomCardItem {
  id: string;
  customType: string;
  payload: string;
  text: string;
}

export type TurnBlock =
  | { key: string; kind: "activity"; steps: ActivityStep[]; durationMs?: number }
  | { key: string; kind: "text"; text: string }
  | { key: string; kind: "present"; items: PresentedItem[] }
  | { key: string; kind: "answered_ask"; entries: AnsweredQuestionEntry[] }
  | { key: string; kind: "custom_card"; card: CustomCardItem };

export interface AttachmentChip {
  id: string;
  name: string;
  mime: string;
  size: number;
  path: string;
  kind: "image" | "text" | "folder" | "other";
  previewUrl?: string;
}

export interface ComposerAttachment {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  kind: "image" | "text" | "folder" | "other";
  isInlineImage: boolean;
  dataBase64: string;
  folderPath?: string;
  previewUrl?: string;
}

export interface ChatTurn {
  id: string;
  runId: string;
  role: "user" | "assistant";
  rawText: string;
  timestamp: string;
  durationMs?: number;
  status: "done" | "stopped" | "failed";
  failed: boolean;
  error?: string;
  reminder?: string;
  notices: string[];
  summary?: {
    kind: string;
    text: string;
    tokensBefore?: number;
  };
  attachments: AttachmentChip[];
  blocks: TurnBlock[];
}

export interface SessionSummary {
  id: string;
  botId: string;
  title: string;
  updatedAtMs: number;
  cwd: string;
  pinned: boolean;
  archived: boolean;
  memoryExcluded: boolean;
  busy: boolean;
  worktreeId?: string | null;
  worktreeBranch?: string | null;
}

export interface SessionTranscript {
  sessionId: string;
  turns: ChatTurn[];
  model?: string;
  thinking?: ThinkingLevel;
  usage: HostUsageDto | null;
  contextPercent: number;
  queuedInputs: HostQueuedInputDto[];
  pendingQuestion: HostPendingQuestionDto | null;
  pendingApprovals?: HostPendingToolApprovalDto[];
  loadedAt: number;
}

export interface QuestionAnswerInput {
  questionId: string;
  selected: string[];
  custom?: string;
  skipped?: boolean;
}

export interface HostWorkspaceFileEntryDto {
  name: string;
  path: string;
  isDirectory: boolean;
  sizeBytes: number;
  modifiedAt: number;
}

export interface HostWorkspaceFileListResponse {
  botId: string;
  root: string;
  path: string;
  entries: HostWorkspaceFileEntryDto[];
}

export interface HostWorkspaceFileReadResponse {
  botId: string;
  path: string;
  content: string;
  sizeBytes: number;
  truncated: boolean;
  isBinary: boolean;
  mtimeMs?: number;
}

export interface HostWorkspaceSearchResponse {
  botId: string;
  query: string;
  entries: HostWorkspaceFileEntryDto[];
}

export interface HostGitFileSummaryDto {
  path: string;
  indexStatus: string;
  workTreeStatus: string;
  staged: boolean;
  additions: number;
  deletions: number;
}

export interface HostGitSummaryResponse {
  isGitRepo: boolean;
  branch?: string | null;
  worktreeId?: string | null;
  totalAdditions: number;
  totalDeletions: number;
  files: HostGitFileSummaryDto[];
}

export interface HostGitDiffResponse {
  path?: string | null;
  diff: string;
  truncated: boolean;
}

export interface HostWorktreeDto {
  id: string;
  botId: string;
  repoPath: string;
  worktreePath: string;
  branch: string;
  baseBranch: string;
  baseCommit: string;
  label: string;
  sessionId?: string | null;
  workerId?: string | null;
  createdAt: number;
}

export interface HostCreateWorktreeResponse {
  worktree: HostWorktreeDto;
  sessionId?: string | null;
}

export interface HostWorktreeOpResponse {
  ok: boolean;
  message: string;
  conflicts: string[];
}

export interface HostWorktreeDiffResponse {
  worktree: HostWorktreeDto;
  files: HostGitFileSummaryDto[];
  diff: string;
  truncated: boolean;
  totalAdditions: number;
  totalDeletions: number;
  aheadCommits: number;
}

export interface HostOpenEditorResponse {
  ok: boolean;
  editor: string;
  message: string;
}

export interface HostCheckpointDto {
  id: string;
  sessionId: string;
  turnIndex: number;
  label: string;
  messageCount: number;
  createdAt: number;
}

export interface HostRestoreCheckpointResponse {
  ok: boolean;
  message: string;
  backupCheckpointId?: string | null;
  restoredFiles: number;
}

export interface HostTerminalInfoDto {
  id: string;
  botId: string;
  cwd: string;
  shell: string;
  alive: boolean;
  createdAt: number;
  history: string;
}

export interface HostTerminalStreamFrame {
  type: "history" | "chunk" | "output" | "exit" | string;
  data?: string;
  exitCode?: number | null;
}

export type OpenPreviewTarget =
  | {
      source: "workspace";
      botId: string;
      path: string;
      name: string;
    }
  | {
      source: "artifact";
      sessionId: string;
      path: string;
      name: string;
      mime?: string;
    };

export interface HostWorkerStepDto {
  label: string;
  status: string;
  outputPreview?: string | null;
  isError: boolean;
  updatedAt: number;
}

export interface HostWorkerDto {
  workerId: string;
  botId: string;
  botName: string;
  title: string;
  status: string;
  lastOutcome: string;
  turnCount: number;
  createdAt: number;
  updatedAt: number;
  lastSummary?: string | null;
  lastError?: string | null;
  activeStepText?: string | null;
  deviceId?: string | null;
  deviceName?: string | null;
  isRemote: boolean;
  pendingQuestion?: HostPendingQuestionDto | null;
  pendingApproval?: HostPendingToolApprovalDto | null;
  steps: HostWorkerStepDto[];
}

export interface HostWorkerDetailDto {
  worker: HostWorkerDto;
  messages: HostMessageDto[];
  streaming: boolean;
  activeToolText?: string | null;
  providerId: string;
  modelId: string;
  modelLabel: string;
  thinkingLevel: string;
  thinkingLevels: string[];
  usage?: HostUsageDto | null;
  loaded: boolean;
  error?: string | null;
}

export interface HostSuperTeamBotDto {
  ref: string;
  botId: string;
  name: string;
  avatar?: string | null;
  intro: string;
  deviceId?: string | null;
  deviceName: string;
  isRemote: boolean;
  online: boolean;
  supportsSuperWorker: boolean;
  enabledInSuper: boolean;
  usedWorkerSlots: number;
  maxWorkerSlots: number;
  skills: string[];
  mcpServers: string[];
  memoryScope: string;
}

export interface HostSuperSettingsDto {
  maxConcurrentWorkers: number;
  workerMaxSlotsPerBot: number;
  workerIdleCleanupMinutes: number;
  workerStallTimeoutMinutes: number;
  questionTimeoutMinutes: number;
  coalesceWindowMs: number;
  receiptMaxChars: number;
  workerJobWake: boolean;
  workerCompactionOverrideEnabled: boolean;
  workerCompactionTriggerPercent: number;
}

export interface HostSuperStateResponse {
  busy: boolean;
  ready: boolean;
  providerId: string;
  modelId: string;
  modelLabel: string;
  thinkingLevel: string;
  thinkingLevels: string[];
  usage?: HostUsageDto | null;
  archivedCount: number;
  activeToolText?: string | null;
  error?: string | null;
  pendingQuestion?: HostPendingQuestionDto | null;
  messages: HostMessageDto[];
  workers: HostWorkerDto[];
}

export interface HostMemoryOverviewDto {
  activeBlocks: number;
  archivedBlocks: number;
  activeEvents: number;
  entityCount: number;
  relationCount: number;
  pendingProposals: number;
  pendingDispatchJobs: number;
  lastConsolidationAt?: number | null;
}

export interface HostMemoryBlockDto {
  id: string;
  slug: string;
  title: string;
  purpose: string;
  content: string;
  domain: string;
  pinned: boolean;
  owner: string;
  status: string;
  updatedAt: number;
}

export interface HostMemoryProposalDto {
  id: string;
  slug: string;
  title: string;
  purpose: string;
  domain: string;
  seedContent: string;
  reason: string;
  status: string;
  createdAt: number;
}

export interface HostMemoryEntityDto {
  id: string;
  canonicalName: string;
  entityType: string;
  aliases: string[];
  summary?: string | null;
  domain: string;
  pinned: boolean;
  updatedAt: number;
}

export interface HostMemoryRelationDto {
  id: string;
  fromEntityId: string;
  fromEntityName: string;
  relationType: string;
  toEntityId: string;
  toEntityName: string;
  label?: string | null;
  isActive: boolean;
}

export interface HostMemoryEventDto {
  id: string;
  eventKind: string;
  content: string;
  summary?: string | null;
  tags: string[];
  entityNames: string[];
  domain: string;
  importance: number;
  pinned: boolean;
  eventTime: number;
  createdAt: number;
  status: string;
  epistemicTier: string;
  sourceSessionId?: string | null;
  sourceQuote?: string | null;
}

export interface HostMemoryEntityDetailDto {
  entity: HostMemoryEntityDto;
  outgoingRelations: HostMemoryRelationDto[];
  incomingRelations: HostMemoryRelationDto[];
  linkedEvents: HostMemoryEventDto[];
}

export interface HostMemoryAuditDto {
  id: string;
  actor: string;
  actionType: string;
  targetTable: string;
  targetId: string;
  summaryUserText: string;
  reason?: string | null;
  createdAt: number;
  revertedAt?: number | null;
  canRevert: boolean;
}

export interface HostMemoryPreferencesDto {
  mode: string;
  hotProviderId?: string | null;
  hotModelId?: string | null;
  coldProviderId?: string | null;
  coldModelId?: string | null;
  hotIdleDebounceSeconds: number;
  coldConsolidationHours: number;
  injectionMaxChars: number;
  profileBudgetChars?: number | null;
  relationsBudgetChars?: number | null;
  eventsBudgetChars?: number | null;
  directoryBudgetChars?: number | null;
  guidance: string;
}

export interface HostMemorySyncPromptDto {
  accountKey: string;
  accountIdentity: string;
  localRecordCount: number;
}

export interface HostMemorySyncStatusDto {
  status: string;
  lastSyncedAt?: number | null;
  lastErrorCode?: string | null;
  accountSwitchPrompt?: HostMemorySyncPromptDto | null;
}

export interface HostMemoryFullSnapshotResponse {
  overview: HostMemoryOverviewDto;
  blocks: HostMemoryBlockDto[];
  proposals: HostMemoryProposalDto[];
  entities: HostMemoryEntityDto[];
  relations: HostMemoryRelationDto[];
  events: HostMemoryEventDto[];
  auditLog: HostMemoryAuditDto[];
  preferences: HostMemoryPreferencesDto;
  consolidating: boolean;
  syncStatus: HostMemorySyncStatusDto;
}

export interface HostInstalledSkillDto {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  installedAt: number;
  source: string;
  removable: boolean;
}

export interface HostSkillContentResponse {
  id: string;
  content: string;
}

export interface HostMcpServerDto {
  name: string;
  transport: string;
  target: string;
  enabled: boolean;
  url?: string | null;
  headers?: Record<string, string>;
  command?: string | null;
  args?: string[];
  env?: Record<string, string>;
  cwd?: string | null;
  hasAuthToken?: boolean;
  probeState?: string | null;
  toolCount?: number | null;
  error?: string | null;
}

export interface HostUpsertMcpRequest {
  transport: "http" | "stdio";
  url?: string;
  headers?: Record<string, string>;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  cwd?: string;
  enabled?: boolean;
}

export interface HostTimerDto {
  id: string;
  name: string;
  prompt: string;
  triggerType: "once" | "daily" | "weekly" | "cron";
  date?: string | null;
  time?: string | null;
  days: number[];
  cronExpression?: string | null;
  enabled: boolean;
  sessionId: string;
  cwd: string;
  nextRunAt?: number | null;
  lastDeliveredAt?: number | null;
  lastError?: string | null;
}

export interface HostSaveTimerRequest {
  name: string;
  prompt: string;
  triggerType: "once" | "daily" | "weekly" | "cron";
  date?: string;
  time?: string;
  days?: number[];
  cronExpression?: string;
  enabled?: boolean;
  botId?: string;
  sessionId?: string;
}

export interface HostInboxItemDto {
  id: string;
  source: "timer" | "worker" | "goal" | string;
  title: string;
  summary: string;
  botId?: string | null;
  sessionId?: string | null;
  workerId?: string | null;
  worktreeId?: string | null;
  status: "unread" | "approved" | "dismissed" | string;
  createdAt: number;
}

export interface HostGoalDto {
  id: string;
  objective: string;
  phase: string;
  roundsStarted: number;
  maxRounds: number;
  blockedReason?: string | null;
  armed: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface HostJobSnapshotDto {
  id: string;
  sessionId: string;
  label: string;
  command: string;
  status: string;
  startedAt: number;
  finishedAt?: number | null;
  exitCode?: number | null;
  detail?: string | null;
  outputBytes: number;
}

export interface HostJobOutputDto {
  jobId: string;
  output: string;
  status: string;
  exitCode?: number | null;
  truncated: boolean;
}

export interface HostWebSearchProviderDto {
  id: string;
  name: string;
  configured: boolean;
  keyHint?: string | null;
}

export interface HostDoctorCheckItemDto {
  category: string;
  name: string;
  status: "PASS" | "WARN" | "FAIL";
  summary: string;
  fixHint?: string | null;
}

export interface HostDoctorReportDto {
  version: string;
  homeDir: string;
  os: string;
  checks: HostDoctorCheckItemDto[];
}

import type { I18nKey } from "../i18n";

type Translate = (key: I18nKey) => string;

const WORKER_STATUS_MAP: Record<string, I18nKey> = {
  queued: "enum.worker.queued",
  running: "enum.worker.running",
  working: "enum.worker.running",
  waiting: "enum.worker.waiting",
  waiting_input: "enum.worker.waiting",
  waiting_approval: "enum.worker.waiting",
  stalled: "enum.worker.stalled",
  idle: "enum.worker.idle",
  done: "enum.worker.done",
  completed: "enum.worker.done",
  failed: "enum.worker.failed",
  stopped: "enum.worker.stopped",
  cancelled: "enum.worker.stopped",
};

const GOAL_PHASE_MAP: Record<string, I18nKey> = {
  active: "enum.goal.active",
  paused: "enum.goal.paused",
  blocked: "enum.goal.blocked",
  completed: "enum.goal.completed",
  complete: "enum.goal.completed",
};

const JOB_STATUS_MAP: Record<string, I18nKey> = {
  running: "enum.job.running",
  done: "enum.job.done",
  completed: "enum.job.done",
  failed: "enum.job.failed",
  killed: "enum.job.killed",
  interrupted: "enum.job.interrupted",
};

const MEMORY_SCOPE_MAP: Record<string, I18nKey> = {
  CUSTOM: "enum.memoryScope.custom",
  PERSONAL: "enum.memoryScope.personal",
  WORK: "enum.memoryScope.work",
  OFF: "enum.memoryScope.off",
};

const MEMORY_ENTITY_KIND_MAP: Record<string, I18nKey> = {
  TOPIC: "enum.entityKind.topic",
  PERSON: "enum.entityKind.person",
  PROJECT: "enum.entityKind.project",
  ORGANIZATION: "enum.entityKind.organization",
  TOOL: "enum.entityKind.tool",
  PLACE: "enum.entityKind.place",
  OTHER: "enum.entityKind.other",
};

const MEMORY_EVENT_KIND_MAP: Record<string, I18nKey> = {
  FACT: "enum.eventKind.fact",
  PREFERENCE: "enum.eventKind.preference",
  DECISION: "enum.eventKind.decision",
  MILESTONE: "enum.eventKind.milestone",
  COMMITMENT: "enum.eventKind.commitment",
  GOAL: "enum.eventKind.goal",
  NOTE: "enum.eventKind.note",
};

const MEMORY_STATUS_MAP: Record<string, I18nKey> = {
  ACTIVE: "enum.memoryStatus.active",
  RESOLVED: "enum.memoryStatus.resolved",
  SUPERSEDED: "enum.memoryStatus.superseded",
  EXPIRED: "enum.memoryStatus.expired",
  ARCHIVED: "enum.memoryStatus.archived",
};

const MEMORY_ACTOR_MAP: Record<string, I18nKey> = {
  AUTO: "enum.actor.auto",
  USER: "enum.actor.user",
  SYSTEM: "enum.actor.system",
  BOT: "enum.actor.auto",
};

const RELATION_TYPE_MAP: Record<string, I18nKey> = {
  related_to: "enum.relation.related_to",
  family_of: "enum.relation.family_of",
  partner_of: "enum.relation.partner_of",
  friend_of: "enum.relation.friend_of",
  colleague_of: "enum.relation.colleague_of",
  lives_in: "enum.relation.lives_in",
  works_at: "enum.relation.works_at",
  prefers: "enum.relation.prefers",
  dislikes: "enum.relation.dislikes",
  owns: "enum.relation.owns",
};

const MCP_STATUS_MAP: Record<string, I18nKey> = {
  READY: "enum.mcp.ready",
  OK: "enum.mcp.ready",
  AUTH_REQUIRED: "enum.mcp.authRequired",
  FAILED: "enum.mcp.failed",
  DISABLED: "enum.mcp.disabled",
  UNKNOWN: "enum.mcp.unknown",
};

const AUTO_STATUS_MAP: Record<string, I18nKey> = {
  unread: "enum.autoStatus.unread",
  read: "enum.autoStatus.read",
  approved: "enum.autoStatus.approved",
  dismissed: "enum.autoStatus.dismissed",
};

const AUTO_SOURCE_MAP: Record<string, I18nKey> = {
  timer: "enum.autoSource.timer",
  background_job: "enum.autoSource.job",
  job: "enum.autoSource.job",
  plugin: "enum.autoSource.plugin",
  system: "enum.autoSource.system",
};

const SKILL_SOURCE_MAP: Record<string, I18nKey> = {
  builtin: "enum.skillSource.builtin",
  user: "enum.skillSource.user",
  imported: "enum.skillSource.user",
  plugin: "enum.skillSource.plugin",
  bot: "enum.skillSource.bot",
};

const DIAG_STATUS_MAP: Record<string, I18nKey> = {
  PASS: "enum.diag.pass",
  WARN: "enum.diag.warn",
  FAIL: "enum.diag.fail",
};

const PLATFORM_MAP: Record<string, I18nKey> = {
  android: "enum.platform.android",
  macos: "enum.platform.macos",
  darwin: "enum.platform.macos",
  windows: "enum.platform.windows",
  win32: "enum.platform.windows",
  linux: "enum.platform.linux",
  desktop: "enum.platform.desktop",
};

function lookup(map: Record<string, I18nKey>, raw: string | null | undefined, t: Translate, upper = false): string {
  const cleaned = (raw ?? "").trim();
  if (!cleaned) return "";
  const key = map[upper ? cleaned.toUpperCase() : cleaned.toLowerCase()];
  return key ? t(key) : cleaned;
}

export function formatWorkerStatus(raw: string, t: Translate): string {
  return lookup(WORKER_STATUS_MAP, raw, t, false);
}

export function formatGoalPhase(raw: string, t: Translate): string {
  return lookup(GOAL_PHASE_MAP, raw, t, false);
}

export function formatJobStatus(raw: string, t: Translate): string {
  return lookup(JOB_STATUS_MAP, raw, t, false);
}

export function formatMemoryScope(raw: string, t: Translate): string {
  return lookup(MEMORY_SCOPE_MAP, raw, t, true);
}

export function formatEntityKind(raw: string, t: Translate): string {
  return lookup(MEMORY_ENTITY_KIND_MAP, raw, t, true);
}

export function formatEventKind(raw: string, t: Translate): string {
  return lookup(MEMORY_EVENT_KIND_MAP, raw, t, true);
}

export function formatMemoryStatus(raw: string, t: Translate): string {
  return lookup(MEMORY_STATUS_MAP, raw, t, true);
}

export function formatMemoryActor(raw: string, t: Translate): string {
  return lookup(MEMORY_ACTOR_MAP, raw, t, true);
}

export function formatRelationType(raw: string, t: Translate): string {
  return lookup(RELATION_TYPE_MAP, raw, t, false);
}

export function formatMcpStatus(raw: string, t: Translate): string {
  return lookup(MCP_STATUS_MAP, raw, t, true);
}

export function formatAutomationStatus(raw: string, t: Translate): string {
  return lookup(AUTO_STATUS_MAP, raw, t, false);
}

export function formatAutomationSource(raw: string, t: Translate): string {
  return lookup(AUTO_SOURCE_MAP, raw, t, false);
}

export function formatSkillSource(raw: string, t: Translate): string {
  return lookup(SKILL_SOURCE_MAP, raw, t, false);
}

export function formatDiagStatus(raw: string, t: Translate): string {
  return lookup(DIAG_STATUS_MAP, raw, t, true);
}

export function formatPlatformName(raw: string, t: Translate): string {
  return lookup(PLATFORM_MAP, raw, t, false);
}

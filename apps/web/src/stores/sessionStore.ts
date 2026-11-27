import { create } from "zustand";
import type { HostClient } from "../api/hostClient";
import {
  computeContextPercent,
  encodeAttachmentBlock,
  formatClockTime,
  mapHostMessageToTurn,
  normalizeThinkingLevel,
} from "../api/mappers";
import type {
  AttachmentChip,
  ChatTurn,
  ComposerAttachment,
  HostBotDto,
  HostPendingQuestionDto,
  HostPendingToolApprovalDto,
  HostQueuedInputDto,
  HostSessionSummaryDto,
  HostUsageDto,
  QuestionAnswerInput,
  SessionSummary,
  SessionTranscript,
  ThinkingLevel,
  ToolApprovalDecision,
  TurnBlock,
} from "../api/types";
import { translate } from "../i18n";
import { useBotStore } from "./botStore";
import { type LiveRunState, useStreamStore } from "./streamStore";
import { useSuperStore } from "./superStore";

export const MAX_CACHED_TRANSCRIPTS = 5;
export const DEFAULT_SESSION_PAGE_SIZE = 40;

export interface SlashCommandStatus {
  kind: "compact" | "goal";
  phase: "running" | "done" | "error";
  message: string;
  retryText?: string;
}

export interface PendingDeleteItem {
  id: string;
  title: string;
  botId: string;
  expiresAt: number;
}

export function draftComposerKey(botId: string): string {
  return `__draft__:${botId}`;
}

const ACTIVE_SESSION_STORAGE_KEY = "superpi:activeSessionId";

function loadStoredSessionId(): string {
  if (typeof window === "undefined") return "";
  try {
    return window.localStorage.getItem(ACTIVE_SESSION_STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

function saveStoredSessionId(sessionId: string): void {
  if (typeof window === "undefined") return;
  try {
    if (sessionId) window.localStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, sessionId);
  } catch {
    return;
  }
}

export function evictTranscriptLru(
  cache: Record<string, SessionTranscript>,
  order: string[],
  keepSessionId: string,
  maxCount = MAX_CACHED_TRANSCRIPTS,
): { transcriptsById: Record<string, SessionTranscript>; lruOrder: string[] } {
  const nextOrder = [...order];
  const nextCache = { ...cache };
  while (nextOrder.length > maxCount) {
    const candidateIdx = nextOrder.findIndex((id) => id !== keepSessionId);
    if (candidateIdx < 0) break;
    const [evictId] = nextOrder.splice(candidateIdx, 1);
    delete nextCache[evictId];
  }
  return { transcriptsById: nextCache, lruOrder: nextOrder };
}

function touchLru(order: string[], sessionId: string): string[] {
  return [...order.filter((id) => id !== sessionId), sessionId];
}

function resolveBotIdByCwd(cwd: string, bots: HostBotDto[]): string {
  const cleanCwd = cwd.replace(/\/+$/, "");
  const matched = bots.find((b) => b.cwd.replace(/\/+$/, "") === cleanCwd);
  return matched?.id ?? bots[0]?.id ?? "home";
}

function clearResolvedQuestionFromState(
  state: Pick<SessionStoreState, "pendingQuestions" | "transcriptsById">,
  requestId: string,
): Partial<SessionStoreState> {
  const nextPending = state.pendingQuestions.filter((q) => q.requestId !== requestId);
  let nextTranscripts = state.transcriptsById;
  let changed = nextPending.length !== state.pendingQuestions.length;
  for (const [sid, tr] of Object.entries(state.transcriptsById)) {
    if (tr.pendingQuestion?.requestId === requestId) {
      if (nextTranscripts === state.transcriptsById) {
        nextTranscripts = { ...state.transcriptsById };
      }
      nextTranscripts[sid] = { ...tr, pendingQuestion: null };
      changed = true;
    }
  }
  if (!changed) return state as Partial<SessionStoreState>;
  return {
    pendingQuestions: nextPending,
    transcriptsById: nextTranscripts,
  };
}

function materializeFinishedLiveRun(
  run: LiveRunState,
  locale: "zh-CN" | "en",
): ChatTurn | null {
  if (run.streaming || run.blocks.length === 0) return null;
  const textParts = run.blocks
    .filter((b): b is Extract<TurnBlock, { kind: "text" }> => b.kind === "text")
    .map((b) => b.text);
  const rawText = textParts.join("\n\n");
  const durationMs =
    run.finishedAt && run.startedAt > 0 && run.finishedAt >= run.startedAt
      ? run.finishedAt - run.startedAt
      : undefined;
  return {
    id: `m-local-${run.runId}`,
    runId: run.runId,
    role: "assistant",
    rawText,
    timestamp: formatClockTime(run.finishedAt ?? run.startedAt, locale),
    durationMs,
    status: run.status === "failed" ? "failed" : run.status === "stopped" ? "stopped" : "done",
    failed: run.failed,
    error: run.error,
    notices: [],
    attachments: [],
    blocks: run.blocks,
  };
}

const inFlightLoads = new Map<string, Promise<void>>();
const pendingLoads = new Map<string, { locale: "zh-CN" | "en"; reconcileRunId?: string }>();
const inFlightSends = new Map<string, Promise<boolean>>();
const slashStatusTimers = new Map<string, ReturnType<typeof setTimeout>>();
const pendingDeleteRecords = new Map<
  string,
  {
    client: HostClient;
    summary: SessionSummary;
    transcript?: SessionTranscript;
    wasArchived: boolean;
    timer: ReturnType<typeof setTimeout>;
  }
>();
let localSeq = 0;
let refreshSeq = 0;
let createInFlight = false;

function pickMostRecentFallbackSessionId(
  summariesById: Record<string, SessionSummary>,
  excludeId?: string,
): string {
  let bestId = "";
  let bestTs = -Infinity;
  for (const [id, sum] of Object.entries(summariesById)) {
    if (id === excludeId) continue;
    if (sum.updatedAtMs > bestTs) {
      bestTs = sum.updatedAtMs;
      bestId = id;
    }
  }
  return bestId;
}

export interface SessionStoreState {
  summariesById: Record<string, SessionSummary>;
  sessionIdsByBot: Record<string, string[]>;
  transcriptsById: Record<string, SessionTranscript>;
  lruOrder: string[];
  archivedSessions: SessionSummary[];
  pendingQuestions: HostPendingQuestionDto[];
  pendingApprovals: HostPendingToolApprovalDto[];
  activeSessionId: string;
  draftBotId: string | null;
  draftModelByBot: Record<string, { model: string; thinking: ThinkingLevel }>;
  unreadSessionIds: Record<string, true>;
  loadingSessionIds: Record<string, true>;
  sessionLoadErrors: Record<string, string>;
  loadingEarlierBySession: Record<string, true>;
  loadEarlierErrorBySession: Record<string, string>;
  pendingDeletes: PendingDeleteItem[];
  composerFocusTick: number;
  actionError: string | null;
  composerTextBySession: Record<string, string>;
  quotedTextBySession: Record<string, string | null>;
  slashStatusBySession: Record<string, SlashCommandStatus>;

  clearActionError: () => void;
  clearSlashStatus: (sessionId: string) => void;
  startDraftSession: (botId: string) => void;
  setDraftModel: (botId: string, model: string, thinking: ThinkingLevel) => void;

  hydrateSessionLists: (
    client: HostClient,
    bots: HostBotDto[],
    locale: "zh-CN" | "en",
  ) => Promise<void>;
  refreshSessionLists: (client: HostClient, bots: HostBotDto[]) => Promise<void>;
  loadSession: (
    client: HostClient,
    sessionId: string,
    locale: "zh-CN" | "en",
    reconcileRunId?: string,
  ) => Promise<void>;
  loadEarlierMessages: (
    client: HostClient,
    sessionId: string,
    locale: "zh-CN" | "en",
  ) => Promise<number>;
  patchSessionUsage: (sessionId: string, usage: HostUsageDto) => void;
  loadArchivedSessions: (client: HostClient) => Promise<void>;
  refreshQuestions: (client: HostClient) => Promise<void>;
  refreshApprovals: (client: HostClient) => Promise<void>;
  selectSession: (client: HostClient, sessionId: string, locale: "zh-CN" | "en") => Promise<void>;
  createSession: (
    client: HostClient,
    botId: string,
    locale: "zh-CN" | "en",
  ) => Promise<string | null>;
  forkSession: (
    client: HostClient,
    sessionId: string,
    locale: "zh-CN" | "en",
    upToUserTurn?: number,
  ) => Promise<string | null>;
  rewindSessionTurn: (
    client: HostClient,
    sessionId: string,
    options: {
      turnIndex: number;
      mode: "chat_only" | "files_only" | "files_and_chat";
      checkpointId?: string;
      restoreText?: string;
      locale: "zh-CN" | "en";
    },
  ) => Promise<boolean>;
  renameSession: (client: HostClient, sessionId: string, title: string) => Promise<void>;
  togglePinSession: (client: HostClient, sessionId: string, pinned: boolean) => Promise<void>;
  toggleArchiveSession: (client: HostClient, sessionId: string, archived: boolean) => Promise<void>;
  toggleMemoryExclude: (
    client: HostClient,
    sessionId: string,
    excluded: boolean,
  ) => Promise<void>;
  deleteSession: (client: HostClient, sessionId: string) => Promise<void>;
  scheduleDeleteSession: (client: HostClient, sessionId: string) => void;
  undoDeleteSession: (sessionId: string) => void;
  flushPendingDeletes: () => Promise<void>;
  updateSessionModel: (
    client: HostClient,
    sessionId: string,
    model: string,
    thinking: ThinkingLevel,
    locale: "zh-CN" | "en",
  ) => Promise<void>;
  compactSession: (
    client: HostClient,
    sessionId: string,
    locale: "zh-CN" | "en",
  ) => Promise<{ ok: boolean; message?: string | null }>;
  setQueueForSession: (sessionId: string, queue: HostQueuedInputDto[]) => void;
  removeQueuedInput: (
    client: HostClient,
    sessionId: string,
    kind: "steer" | "follow_up",
    text: string,
  ) => Promise<void>;
  promoteQueuedInput: (client: HostClient, sessionId: string, text: string) => Promise<void>;
  sendMessage: (
    client: HostClient,
    options: {
      sessionId: string;
      botId: string;
      locale: "zh-CN" | "en";
      sendWhileRunning: "steer" | "queue";
      overrideText?: string;
      attachments?: ComposerAttachment[];
    },
  ) => Promise<boolean>;
  stopSession: (client: HostClient, sessionId: string) => Promise<void>;
  retrySession: (client: HostClient, sessionId: string) => Promise<void>;
  answerQuestion: (
    client: HostClient,
    requestId: string,
    answers: QuestionAnswerInput[],
  ) => Promise<void>;
  dismissQuestion: (client: HostClient, requestId: string) => Promise<void>;
  resolveToolApproval: (
    client: HostClient,
    approvalId: string,
    decision: ToolApprovalDecision,
    note?: string,
  ) => Promise<void>;
  setComposerText: (sessionId: string, value: string) => void;
  setQuotedText: (sessionId: string, value: string | null) => void;
  setSessionBusy: (sessionId: string, busy: boolean) => void;
  markSessionUnread: (sessionId: string) => void;
  clearSessionUnread: (sessionId: string) => void;
  requestComposerFocus: () => void;
  reset: () => void;
}

function mapSummary(dto: HostSessionSummaryDto, botId: string): SessionSummary {
  return {
    id: dto.id,
    botId,
    title: dto.title || "",
    updatedAtMs: dto.updatedAt,
    cwd: dto.cwd,
    pinned: dto.pinned,
    archived: dto.archived,
    memoryExcluded: dto.memoryExcluded,
    busy: dto.busy,
    worktreeId: dto.worktreeId ?? null,
    worktreeBranch: dto.worktreeBranch ?? null,
  };
}

export const useSessionStore = create<SessionStoreState>((set, get) => {
  const setSlashStatus = (
    sessionId: string,
    status: SlashCommandStatus,
    autoClearMs?: number,
  ) => {
    const prevTimer = slashStatusTimers.get(sessionId);
    if (prevTimer) {
      clearTimeout(prevTimer);
      slashStatusTimers.delete(sessionId);
    }
    set((s) => ({
      slashStatusBySession: { ...s.slashStatusBySession, [sessionId]: status },
    }));
    if (autoClearMs && autoClearMs > 0) {
      const timer = setTimeout(() => {
        slashStatusTimers.delete(sessionId);
        get().clearSlashStatus(sessionId);
      }, autoClearMs);
      slashStatusTimers.set(sessionId, timer);
    }
  };

  const restoreDeletedRecord = (
    s: SessionStoreState,
    rec: {
      summary: SessionSummary;
      transcript?: SessionTranscript;
      wasArchived: boolean;
    },
    actionError: string | null,
    activateRestored: boolean,
  ): Partial<SessionStoreState> => {
    const sid = rec.summary.id;
    const baseState: Partial<SessionStoreState> = {
      pendingDeletes: s.pendingDeletes.filter((item) => item.id !== sid),
      actionError,
    };
    if (rec.wasArchived) {
      const exists = s.archivedSessions.some((x) => x.id === sid);
      return {
        ...baseState,
        archivedSessions: exists ? s.archivedSessions : [rec.summary, ...s.archivedSessions],
      };
    }
    const nextSummaries = { ...s.summariesById, [sid]: rec.summary };
    const existingIds = s.sessionIdsByBot[rec.summary.botId] ?? [];
    const nextBotIds = existingIds.includes(sid) ? existingIds : [sid, ...existingIds];
    const nextByBot = { ...s.sessionIdsByBot, [rec.summary.botId]: nextBotIds };
    const nextCache = rec.transcript
      ? { ...s.transcriptsById, [sid]: rec.transcript }
      : s.transcriptsById;
    const nextLru = rec.transcript ? touchLru(s.lruOrder, sid) : s.lruOrder;
    const shouldActivate = activateRestored || !s.activeSessionId;
    const nextActive = shouldActivate ? sid : s.activeSessionId;
    if (shouldActivate) saveStoredSessionId(sid);
    return {
      ...baseState,
      summariesById: nextSummaries,
      sessionIdsByBot: nextByBot,
      transcriptsById: nextCache,
      lruOrder: nextLru,
      activeSessionId: nextActive,
      draftBotId: nextActive ? null : s.draftBotId,
    };
  };

  const commitPendingDelete = async (sessionId: string): Promise<void> => {
    const rec = pendingDeleteRecords.get(sessionId);
    if (!rec) return;
    pendingDeleteRecords.delete(sessionId);
    clearTimeout(rec.timer);
    set((s) => ({
      pendingDeletes: s.pendingDeletes.filter((item) => item.id !== sessionId),
    }));
    try {
      await rec.client.deleteSession(sessionId);
      useStreamStore.getState().clearRun(sessionId);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to delete session";
      set((s) => restoreDeletedRecord(s, rec, msg, false));
    }
  };

  return {
  summariesById: {},
  sessionIdsByBot: {},
  transcriptsById: {},
  lruOrder: [],
  archivedSessions: [],
  pendingQuestions: [],
  pendingApprovals: [],
  activeSessionId: loadStoredSessionId(),
  draftBotId: null,
  draftModelByBot: {},
  unreadSessionIds: {},
  loadingSessionIds: {},
  sessionLoadErrors: {},
  loadingEarlierBySession: {},
  loadEarlierErrorBySession: {},
  pendingDeletes: [],
  composerFocusTick: 0,
  actionError: null,
  composerTextBySession: {},
  quotedTextBySession: {},
  slashStatusBySession: {},

  clearActionError: () => set({ actionError: null }),

  clearSlashStatus: (sessionId) => {
    const prevTimer = slashStatusTimers.get(sessionId);
    if (prevTimer) {
      clearTimeout(prevTimer);
      slashStatusTimers.delete(sessionId);
    }
    set((s) => {
      if (!s.slashStatusBySession[sessionId]) return s;
      const next = { ...s.slashStatusBySession };
      delete next[sessionId];
      return { slashStatusBySession: next };
    });
  },

  startDraftSession: (botId) => {
    useBotStore.getState().setBotCollapsed(botId, false);
    set((s) => ({
      activeSessionId: "",
      draftBotId: botId,
      actionError: null,
      composerFocusTick: s.composerFocusTick + 1,
    }));
  },

  setDraftModel: (botId, model, thinking) => {
    set((s) => ({
      draftModelByBot: {
        ...s.draftModelByBot,
        [botId]: { model, thinking },
      },
    }));
  },

  hydrateSessionLists: async (client, bots, locale) => {
    const results = await Promise.all(
      bots.map(async (b) => {
        const list = await client.listSessions(b.id).catch(() => [] as HostSessionSummaryDto[]);
        return { botId: b.id, list: list.filter((x) => !x.archived) };
      }),
    );

    const summariesById: Record<string, SessionSummary> = {};
    const sessionIdsByBot: Record<string, string[]> = {};

    for (const { botId, list } of results) {
      sessionIdsByBot[botId] = list.map((s) => s.id);
      for (const dto of list) {
        summariesById[dto.id] = mapSummary(dto, botId);
      }
    }

    const allIds = Object.keys(summariesById);
    let targetId = get().activeSessionId;
    let draftBotId: string | null = null;
    if (!targetId || !summariesById[targetId]) {
      if (allIds.length > 0) {
        targetId = allIds[0];
      } else if (bots.length > 0) {
        targetId = "";
        draftBotId = bots[0].id;
      }
    }

    if (targetId) {
      saveStoredSessionId(targetId);
    }
    set({ summariesById, sessionIdsByBot, activeSessionId: targetId, draftBotId });
    if (targetId) {
      void get().loadSession(client, targetId, locale);
    }
    void get().refreshQuestions(client);
  },

  refreshSessionLists: async (client, bots) => {
    const seq = ++refreshSeq;
    const results = await Promise.all(
      bots.map(async (b) => {
        try {
          const list = await client.listSessions(b.id);
          return {
            botId: b.id,
            ok: true as const,
            list: list.filter((x) => !x.archived && !pendingDeleteRecords.has(x.id)),
          };
        } catch {
          return { botId: b.id, ok: false as const, list: [] as HostSessionSummaryDto[] };
        }
      }),
    );
    if (seq !== refreshSeq) return;
    set((s) => {
      const summariesById: Record<string, SessionSummary> = {};
      const sessionIdsByBot: Record<string, string[]> = {};
      for (const { botId, ok, list } of results) {
        if (ok) {
          sessionIdsByBot[botId] = list.map((item) => item.id);
          for (const dto of list) {
            summariesById[dto.id] = mapSummary(dto, botId);
          }
        } else {
          const prevIds = (s.sessionIdsByBot[botId] ?? []).filter(
            (id) => !pendingDeleteRecords.has(id),
          );
          sessionIdsByBot[botId] = prevIds;
          for (const id of prevIds) {
            const prevSum = s.summariesById[id];
            if (prevSum) summariesById[id] = prevSum;
          }
        }
      }
      const curActive = s.activeSessionId;
      if (!curActive && s.draftBotId) {
        return { summariesById, sessionIdsByBot };
      }
      const prevActiveSummary = curActive ? s.summariesById[curActive] : undefined;
      const prevActiveTranscript = curActive ? s.transcriptsById[curActive] : undefined;
      if (
        curActive &&
        !summariesById[curActive] &&
        prevActiveSummary &&
        prevActiveTranscript &&
        prevActiveTranscript.turns.length === 0 &&
        !pendingDeleteRecords.has(curActive)
      ) {
        summariesById[curActive] = prevActiveSummary;
        const botIds = sessionIdsByBot[prevActiveSummary.botId] ?? [];
        if (!botIds.includes(curActive)) {
          sessionIdsByBot[prevActiveSummary.botId] = [curActive, ...botIds];
        }
      }
      const nextActive =
        curActive && summariesById[curActive]
          ? curActive
          : pickMostRecentFallbackSessionId(summariesById) || curActive;
      if (nextActive && nextActive !== curActive) {
        saveStoredSessionId(nextActive);
      }
      return { summariesById, sessionIdsByBot, activeSessionId: nextActive };
    });
  },

  loadSession: async (client, sessionId, locale, reconcileRunId) => {
    const existing = inFlightLoads.get(sessionId);
    if (existing) {
      const prev = pendingLoads.get(sessionId);
      pendingLoads.set(sessionId, {
        locale,
        reconcileRunId: reconcileRunId ?? prev?.reconcileRunId,
      });
      return existing;
    }

    const promise = (async () => {
      set((s) => {
        const nextErrors = { ...s.sessionLoadErrors };
        delete nextErrors[sessionId];
        return {
          loadingSessionIds: { ...s.loadingSessionIds, [sessionId]: true },
          sessionLoadErrors: nextErrors,
        };
      });
      try {
        const existingTranscript = get().transcriptsById[sessionId];
        const limit =
          existingTranscript && existingTranscript.turns.length > DEFAULT_SESSION_PAGE_SIZE
            ? Math.min(500, existingTranscript.turns.length + 10)
            : DEFAULT_SESSION_PAGE_SIZE;
        const detail = await client.getSession(sessionId, { limit });

        const turns: ChatTurn[] = detail.messages
          .filter((m) => !m.streaming)
          .map((m) => mapHostMessageToTurn(m, locale));

        const model =
          detail.model?.providerId && detail.model?.modelId
            ? `${detail.model.providerId}/${detail.model.modelId}`
            : undefined;
        const thinking = detail.model?.thinkingLevel
          ? normalizeThinkingLevel(detail.model.thinkingLevel)
          : undefined;

        const transcript: SessionTranscript = {
          sessionId,
          turns,
          model,
          thinking,
          usage: detail.usage ?? null,
          contextPercent: computeContextPercent(detail.usage),
          queuedInputs: detail.queuedInputs ?? [],
          pendingQuestion: detail.pendingQuestion ?? null,
          pendingApprovals: detail.pendingApprovals ?? [],
          totalMessages: detail.totalMessages ?? turns.length,
          hasMore: Boolean(detail.hasMore),
          nextBefore: detail.nextBefore ?? null,
          userTurnOffset: detail.userTurnOffset ?? 0,
          loadedAt: Date.now(),
        };

        set((s) => {
          const nextOrder = touchLru(s.lruOrder, sessionId);
          const evicted = evictTranscriptLru(
            { ...s.transcriptsById, [sessionId]: transcript },
            nextOrder,
            s.activeSessionId,
          );
          const prevSum = s.summariesById[sessionId];
          const nextLoading = { ...s.loadingSessionIds };
          delete nextLoading[sessionId];
          const nextErrors = { ...s.sessionLoadErrors };
          delete nextErrors[sessionId];
          return {
            ...evicted,
            loadingSessionIds: nextLoading,
            sessionLoadErrors: nextErrors,
            summariesById: prevSum
              ? {
                  ...s.summariesById,
                  [sessionId]: {
                    ...prevSum,
                    title: detail.title || prevSum.title,
                    busy: detail.busy,
                    cwd: detail.cwd || prevSum.cwd,
                    worktreeId: detail.worktreeId ?? prevSum.worktreeId,
                    worktreeBranch: detail.worktreeBranch ?? prevSum.worktreeBranch,
                  },
                }
              : s.summariesById,
          };
        });

        if (reconcileRunId) {
          useStreamStore.getState().clearRun(sessionId, reconcileRunId);
        } else if (!detail.busy) {
          const live = useStreamStore.getState().activeBySession[sessionId];
          if (live && !live.streaming && !live.runId.startsWith("pending-")) {
            useStreamStore.getState().clearRun(sessionId);
          }
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Failed to load session";
        set((s) => {
          const nextLoading = { ...s.loadingSessionIds };
          delete nextLoading[sessionId];
          return {
            loadingSessionIds: nextLoading,
            sessionLoadErrors: { ...s.sessionLoadErrors, [sessionId]: msg },
          };
        });
      } finally {
        inFlightLoads.delete(sessionId);
      }
    })();

    inFlightLoads.set(sessionId, promise);
    await promise;

    const queued = pendingLoads.get(sessionId);
    if (queued) {
      pendingLoads.delete(sessionId);
      await get().loadSession(client, sessionId, queued.locale, queued.reconcileRunId);
    }
  },

  loadEarlierMessages: async (client, sessionId, locale) => {
    const curState = get();
    if (curState.loadingEarlierBySession[sessionId]) return 0;
    const current = curState.transcriptsById[sessionId];
    if (!current || !current.hasMore || current.nextBefore == null) return 0;

    set((s) => {
      const nextErr = { ...s.loadEarlierErrorBySession };
      delete nextErr[sessionId];
      return {
        loadingEarlierBySession: { ...s.loadingEarlierBySession, [sessionId]: true },
        loadEarlierErrorBySession: nextErr,
      };
    });

    try {
      const detail = await client.getSession(sessionId, {
        limit: DEFAULT_SESSION_PAGE_SIZE,
        before: current.nextBefore,
      });
      const olderTurns = detail.messages
        .filter((m) => !m.streaming)
        .map((m) => mapHostMessageToTurn(m, locale));

      let addedCount = 0;
      set((s) => {
        const latest = s.transcriptsById[sessionId];
        if (!latest) {
          const nextLoad = { ...s.loadingEarlierBySession };
          delete nextLoad[sessionId];
          return { loadingEarlierBySession: nextLoad };
        }
        const existingIds = new Set(latest.turns.map((t) => t.id));
        const uniqueOlder = olderTurns.filter((t) => !existingIds.has(t.id));
        addedCount = uniqueOlder.length;
        const nextLoad = { ...s.loadingEarlierBySession };
        delete nextLoad[sessionId];
        return {
          loadingEarlierBySession: nextLoad,
          transcriptsById: {
            ...s.transcriptsById,
            [sessionId]: {
              ...latest,
              turns: [...uniqueOlder, ...latest.turns],
              totalMessages: detail.totalMessages ?? latest.totalMessages,
              hasMore: Boolean(detail.hasMore),
              nextBefore: detail.nextBefore ?? null,
              userTurnOffset: detail.userTurnOffset ?? 0,
            },
          },
        };
      });
      return addedCount;
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to load earlier messages";
      set((s) => {
        const nextLoad = { ...s.loadingEarlierBySession };
        delete nextLoad[sessionId];
        return {
          loadingEarlierBySession: nextLoad,
          loadEarlierErrorBySession: { ...s.loadEarlierErrorBySession, [sessionId]: msg },
        };
      });
      return 0;
    }
  },

  patchSessionUsage: (sessionId, usage) => {
    set((s) => {
      const tr = s.transcriptsById[sessionId];
      if (!tr) return s;
      return {
        transcriptsById: {
          ...s.transcriptsById,
          [sessionId]: {
            ...tr,
            usage,
            contextPercent: computeContextPercent(usage),
          },
        },
      };
    });
  },

  loadArchivedSessions: async (client) => {
    const list = await client.listArchivedSessions().catch(() => [] as HostSessionSummaryDto[]);
    const bots = useBotStore.getState().bots;
    set({
      archivedSessions: list.map((s) => mapSummary(s, resolveBotIdByCwd(s.cwd, bots))),
    });
  },

  refreshQuestions: async (client) => {
    const list = await client.listQuestions().catch(() => [] as HostPendingQuestionDto[]);
    const bySession = new Map(list.map((q) => [q.sessionId, q]));
    set((s) => {
      let changed = false;
      let nextTranscripts = s.transcriptsById;
      for (const [sid, tr] of Object.entries(s.transcriptsById)) {
        const nextQ = bySession.get(sid) ?? null;
        if ((tr.pendingQuestion?.requestId ?? null) !== (nextQ?.requestId ?? null)) {
          if (!changed) {
            nextTranscripts = { ...s.transcriptsById };
            changed = true;
          }
          nextTranscripts[sid] = { ...tr, pendingQuestion: nextQ };
        }
      }
      return changed
        ? { pendingQuestions: list, transcriptsById: nextTranscripts }
        : { pendingQuestions: list };
    });
  },

  refreshApprovals: async (client) => {
    const list = await client.listApprovals().catch(() => [] as HostPendingToolApprovalDto[]);
    set({ pendingApprovals: list });
  },

  selectSession: async (client, sessionId, locale) => {
    saveStoredSessionId(sessionId);
    set((s) => {
      const nextOrder = s.transcriptsById[sessionId] ? touchLru(s.lruOrder, sessionId) : s.lruOrder;
      const nextUnread = { ...s.unreadSessionIds };
      delete nextUnread[sessionId];
      return {
        activeSessionId: sessionId,
        draftBotId: null,
        lruOrder: nextOrder,
        unreadSessionIds: nextUnread,
        composerFocusTick: s.composerFocusTick + 1,
      };
    });
    await get().loadSession(client, sessionId, locale);
  },

  createSession: async (client, botId, locale) => {
    if (createInFlight) return null;
    createInFlight = true;
    set({ actionError: null });
    useBotStore.getState().setBotCollapsed(botId, false);
    try {
      const created = await client.createSession(botId);
      const id = created.sessionId;
      const summary: SessionSummary = {
        id,
        botId,
        title: "",
        updatedAtMs: Date.now(),
        cwd: created.cwd,
        pinned: false,
        archived: false,
        memoryExcluded: false,
        busy: false,
      };
      const transcript: SessionTranscript = {
        sessionId: id,
        turns: [],
        usage: null,
        contextPercent: 0,
        queuedInputs: [],
        pendingQuestion: null,
        loadedAt: Date.now(),
      };
      saveStoredSessionId(id);
      set((s) => {
        const existingIds = s.sessionIdsByBot[botId] ?? [];
        const nextBotIds = existingIds.includes(id) ? existingIds : [id, ...existingIds];
        const evicted = evictTranscriptLru(
          { ...s.transcriptsById, [id]: transcript },
          touchLru(s.lruOrder, id),
          id,
        );
        return {
          ...evicted,
          activeSessionId: id,
          draftBotId: null,
          composerFocusTick: s.composerFocusTick + 1,
          summariesById: { ...s.summariesById, [id]: summary },
          sessionIdsByBot: { ...s.sessionIdsByBot, [botId]: nextBotIds },
        };
      });
      void get().loadSession(client, id, locale);
      return id;
    } catch (err) {
      set({
        actionError: err instanceof Error ? err.message : "Failed to create session",
      });
      return null;
    } finally {
      createInFlight = false;
    }
  },

  forkSession: async (client, sessionId, locale, upToUserTurn) => {
    set({ actionError: null });
    try {
      const src = get().summariesById[sessionId];
      const forkTitle = src?.title ? `${src.title} (branch)` : undefined;
      const payload: { title?: string; upToUserTurn?: number } = {};
      if (forkTitle) payload.title = forkTitle;
      if (upToUserTurn !== undefined) payload.upToUserTurn = upToUserTurn;
      const res = await client.forkSession(
        sessionId,
        Object.keys(payload).length > 0 ? payload : undefined,
      );
      const bots = useBotStore.getState().bots;
      await get().refreshSessionLists(client, bots);
      if (res.sessionId) {
        await get().selectSession(client, res.sessionId, locale);
        return res.sessionId;
      }
      return null;
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to branch session" });
      return null;
    }
  },

  rewindSessionTurn: async (client, sessionId, options) => {
    set({ actionError: null });
    try {
      await client.rewindSession(sessionId, {
        turnIndex: options.turnIndex,
        mode: options.mode,
        checkpointId: options.checkpointId,
      });
      useStreamStore.getState().clearRun(sessionId);
      await get().loadSession(client, sessionId, options.locale);
      await get().refreshSessionLists(client, useBotStore.getState().bots);
      if (options.mode !== "files_only" && options.restoreText) {
        set((s) => ({
          composerTextBySession: {
            ...s.composerTextBySession,
            [sessionId]: options.restoreText ?? "",
          },
          composerFocusTick: s.composerFocusTick + 1,
        }));
      }
      return true;
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to rewind session" });
      return false;
    }
  },

  renameSession: async (client, sessionId, title) => {
    const clean = title.trim();
    if (!clean) return;
    set({ actionError: null });
    try {
      await client.updateSession(sessionId, { title: clean });
      set((s) => {
        const cur = s.summariesById[sessionId];
        if (!cur) return s;
        return {
          summariesById: { ...s.summariesById, [sessionId]: { ...cur, title: clean } },
        };
      });
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to rename session" });
    }
  },

  togglePinSession: async (client, sessionId, pinned) => {
    set({ actionError: null });
    try {
      await client.updateSession(sessionId, { pinned });
      set((s) => {
        const cur = s.summariesById[sessionId];
        if (!cur) return s;
        return {
          summariesById: { ...s.summariesById, [sessionId]: { ...cur, pinned } },
        };
      });
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to update session" });
    }
  },

  toggleArchiveSession: async (client, sessionId, archived) => {
    set({ actionError: null });
    try {
      await client.updateSession(sessionId, { archived });
      set((s) => {
        if (!archived) {
          return {
            archivedSessions: s.archivedSessions.filter((x) => x.id !== sessionId),
          };
        }
        const nextSummaries = { ...s.summariesById };
        const removed = nextSummaries[sessionId];
        delete nextSummaries[sessionId];
        const nextByBot = { ...s.sessionIdsByBot };
        if (removed && nextByBot[removed.botId]) {
          nextByBot[removed.botId] = nextByBot[removed.botId].filter((id) => id !== sessionId);
        }
        const fallbackId =
          s.activeSessionId === sessionId
            ? pickMostRecentFallbackSessionId(nextSummaries)
            : s.activeSessionId;
        if (fallbackId !== s.activeSessionId && fallbackId) saveStoredSessionId(fallbackId);
        const nextDraftBotId =
          !fallbackId ? removed?.botId ?? useBotStore.getState().bots[0]?.id ?? s.draftBotId : null;
        return {
          summariesById: nextSummaries,
          sessionIdsByBot: nextByBot,
          activeSessionId: fallbackId,
          draftBotId: nextDraftBotId,
        };
      });
      if (!archived) {
        await get().refreshSessionLists(client, useBotStore.getState().bots);
      }
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to archive session" });
    }
  },

  toggleMemoryExclude: async (client, sessionId, excluded) => {
    set({ actionError: null });
    try {
      await client.updateSession(sessionId, { memoryExcluded: excluded });
      set((s) => {
        const cur = s.summariesById[sessionId];
        if (!cur) return s;
        return {
          summariesById: {
            ...s.summariesById,
            [sessionId]: { ...cur, memoryExcluded: excluded },
          },
        };
      });
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to update memory option" });
    }
  },

  deleteSession: async (client, sessionId) => {
    const pending = pendingDeleteRecords.get(sessionId);
    if (pending) {
      await commitPendingDelete(sessionId);
      return;
    }
    set({ actionError: null });
    try {
      await client.deleteSession(sessionId);
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to delete session" });
      throw err;
    }
    useStreamStore.getState().clearRun(sessionId);
    set((s) => {
      const nextSummaries = { ...s.summariesById };
      const removed = nextSummaries[sessionId];
      delete nextSummaries[sessionId];
      const nextByBot = { ...s.sessionIdsByBot };
      if (removed && nextByBot[removed.botId]) {
        nextByBot[removed.botId] = nextByBot[removed.botId].filter((id) => id !== sessionId);
      }
      const nextCache = { ...s.transcriptsById };
      delete nextCache[sessionId];
      const fallbackId =
        s.activeSessionId === sessionId
          ? pickMostRecentFallbackSessionId(nextSummaries)
          : s.activeSessionId;
      if (fallbackId !== s.activeSessionId && fallbackId) saveStoredSessionId(fallbackId);
      const nextDraftBotId =
        !fallbackId ? removed?.botId ?? useBotStore.getState().bots[0]?.id ?? s.draftBotId : null;
      return {
        summariesById: nextSummaries,
        sessionIdsByBot: nextByBot,
        transcriptsById: nextCache,
        lruOrder: s.lruOrder.filter((id) => id !== sessionId),
        archivedSessions: s.archivedSessions.filter((x) => x.id !== sessionId),
        activeSessionId: fallbackId,
        draftBotId: nextDraftBotId,
      };
    });
  },

  scheduleDeleteSession: (client, sessionId) => {
    if (pendingDeleteRecords.has(sessionId)) return;
    const s = get();
    const activeSum = s.summariesById[sessionId];
    const archivedSum = s.archivedSessions.find((x) => x.id === sessionId);
    const target = activeSum ?? archivedSum;
    if (!target) {
      void get().deleteSession(client, sessionId).catch(() => void 0);
      return;
    }
    const transcript = s.transcriptsById[sessionId];
    const wasArchived = !activeSum && Boolean(archivedSum);
    const expiresAt = Date.now() + 5000;
    const timer = setTimeout(() => {
      void commitPendingDelete(sessionId);
    }, 5000);
    pendingDeleteRecords.set(sessionId, {
      client,
      summary: target,
      transcript,
      wasArchived,
      timer,
    });
    set((cur) => {
      const nextSummaries = { ...cur.summariesById };
      delete nextSummaries[sessionId];
      const nextByBot = { ...cur.sessionIdsByBot };
      if (nextByBot[target.botId]) {
        nextByBot[target.botId] = nextByBot[target.botId].filter((id) => id !== sessionId);
      }
      const nextCache = { ...cur.transcriptsById };
      delete nextCache[sessionId];
      const fallbackId =
        cur.activeSessionId === sessionId
          ? pickMostRecentFallbackSessionId(nextSummaries)
          : cur.activeSessionId;
      if (fallbackId !== cur.activeSessionId && fallbackId) saveStoredSessionId(fallbackId);
      const nextDraftBotId =
        !fallbackId ? target.botId ?? useBotStore.getState().bots[0]?.id ?? cur.draftBotId : null;
      return {
        actionError: null,
        summariesById: nextSummaries,
        sessionIdsByBot: nextByBot,
        transcriptsById: nextCache,
        lruOrder: cur.lruOrder.filter((id) => id !== sessionId),
        archivedSessions: cur.archivedSessions.filter((x) => x.id !== sessionId),
        activeSessionId: fallbackId,
        draftBotId: nextDraftBotId,
        pendingDeletes: [
          ...cur.pendingDeletes,
          { id: sessionId, title: target.title, botId: target.botId, expiresAt },
        ],
      };
    });
  },

  undoDeleteSession: (sessionId) => {
    const rec = pendingDeleteRecords.get(sessionId);
    if (!rec) return;
    pendingDeleteRecords.delete(sessionId);
    clearTimeout(rec.timer);
    set((s) => restoreDeletedRecord(s, rec, null, !rec.wasArchived));
  },

  flushPendingDeletes: async () => {
    const ids = Array.from(pendingDeleteRecords.keys());
    await Promise.all(ids.map((id) => commitPendingDelete(id)));
  },

  updateSessionModel: async (client, sessionId, model, thinking, locale) => {
    const slash = model.indexOf("/");
    if (slash < 1) return;
    const providerId = model.slice(0, slash);
    const modelId = model.slice(slash + 1);
    const prevTr = get().transcriptsById[sessionId];
    set((s) => {
      const tr = s.transcriptsById[sessionId];
      if (!tr) return s;
      return {
        actionError: null,
        transcriptsById: {
          ...s.transcriptsById,
          [sessionId]: { ...tr, model, thinking },
        },
      };
    });
    try {
      await client.setSessionModel(sessionId, providerId, modelId, thinking.toUpperCase());
      void get().loadSession(client, sessionId, locale);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to switch model";
      set((s) => {
        const cur = s.transcriptsById[sessionId];
        return {
          actionError: msg,
          transcriptsById:
            cur && prevTr
              ? {
                  ...s.transcriptsById,
                  [sessionId]: { ...cur, model: prevTr.model, thinking: prevTr.thinking },
                }
              : s.transcriptsById,
        };
      });
      throw err;
    }
  },

  compactSession: async (client, sessionId, locale) => {
    setSlashStatus(sessionId, {
      kind: "compact",
      phase: "running",
      message: translate(locale, "slash.compacting"),
    });
    try {
      const res = await client.compactSession(sessionId);
      if (res.ok) {
        await get().loadSession(client, sessionId, locale);
        setSlashStatus(
          sessionId,
          {
            kind: "compact",
            phase: "done",
            message: res.message || translate(locale, "slash.compactDone"),
          },
          3600,
        );
      } else {
        setSlashStatus(
          sessionId,
          {
            kind: "compact",
            phase: "done",
            message: res.message || translate(locale, "slash.compactEmpty"),
          },
          3600,
        );
      }
      return { ok: res.ok, message: res.message };
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Compaction failed";
      setSlashStatus(sessionId, {
        kind: "compact",
        phase: "error",
        message: translate(locale, "slash.compactFailed", { error: msg }),
        retryText: "/compact",
      });
      return { ok: false, message: msg };
    }
  },

  setQueueForSession: (sessionId, queue) => {
    set((s) => {
      const tr = s.transcriptsById[sessionId];
      if (!tr) return s;
      return {
        transcriptsById: {
          ...s.transcriptsById,
          [sessionId]: { ...tr, queuedInputs: queue },
        },
      };
    });
  },

  removeQueuedInput: async (client, sessionId, kind, text) => {
    try {
      const queue = await client.removeQueuedInput(sessionId, kind, text);
      get().setQueueForSession(sessionId, queue);
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to remove queued message" });
    }
  },

  promoteQueuedInput: async (client, sessionId, text) => {
    try {
      const queue = await client.promoteQueuedInput(sessionId, text);
      get().setQueueForSession(sessionId, queue);
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to promote queued message" });
    }
  },

  sendMessage: async (client, options) => {
    const lockKey = options.sessionId || draftComposerKey(options.botId);
    if (inFlightSends.has(lockKey)) return false;

    const sendMessageImpl = async (
      c: HostClient,
      opts: Parameters<SessionStoreState["sendMessage"]>[1],
    ): Promise<boolean> => {
      const {
        botId,
        locale,
        sendWhileRunning,
        overrideText,
        attachments = [],
      } = opts;
      const isDraft = !opts.sessionId || opts.sessionId.startsWith("__draft__:");
      const composerKey = opts.sessionId || draftComposerKey(botId);
      const state = get();
      const raw = (overrideText ?? state.composerTextBySession[composerKey] ?? "").trim();
      if (!raw && attachments.length === 0) return false;

      if (attachments.length === 0 && raw === "/compact") {
        state.setComposerText(composerKey, "");
        if (isDraft) {
          setSlashStatus(
            composerKey,
            {
              kind: "compact",
              phase: "done",
              message: translate(locale, "slash.compactEmpty"),
            },
            3600,
          );
          return true;
        }
        await get().compactSession(c, opts.sessionId, locale);
        return true;
      }

      const goalMatch = attachments.length === 0 ? raw.match(/^\/goal(?:\s+([\s\S]*))?$/i) : null;
      if (goalMatch) {
        const arg = (goalMatch[1] ?? "").trim();
        const lowerArg = arg.toLowerCase();
        state.setComposerText(composerKey, "");
        if (!arg) {
          setSlashStatus(composerKey, {
            kind: "goal",
            phase: "error",
            message: translate(locale, "slash.goalUsage"),
          });
          return false;
        }
        if (isDraft && (lowerArg === "pause" || lowerArg === "resume" || lowerArg === "clear" || lowerArg === "stop")) {
          setSlashStatus(composerKey, {
            kind: "goal",
            phase: "error",
            message: translate(locale, "slash.goalUsage"),
          });
          return false;
        }

        let targetSessionId = opts.sessionId;
        if (isDraft) {
          const createdId = await get().createSession(c, botId, locale);
          if (!createdId) return false;
          const draftModel = get().draftModelByBot[botId];
          if (draftModel) {
            await get()
              .updateSessionModel(c, createdId, draftModel.model, draftModel.thinking, locale)
              .catch(() => void 0);
          }
          targetSessionId = createdId;
        }

        try {
          if (lowerArg === "pause") {
            setSlashStatus(targetSessionId, {
              kind: "goal",
              phase: "running",
              message: translate(locale, "common.loading"),
            });
            await c.pauseSessionGoal(targetSessionId);
            useSuperStore.getState().bumpGoalVersion();
            setSlashStatus(
              targetSessionId,
              { kind: "goal", phase: "done", message: translate(locale, "slash.goalPaused") },
              3600,
            );
            return true;
          }
          if (lowerArg === "resume") {
            setSlashStatus(targetSessionId, {
              kind: "goal",
              phase: "running",
              message: translate(locale, "common.loading"),
            });
            await c.resumeSessionGoal(targetSessionId);
            useSuperStore.getState().bumpGoalVersion();
            setSlashStatus(
              targetSessionId,
              { kind: "goal", phase: "done", message: translate(locale, "slash.goalResumed") },
              3600,
            );
            return true;
          }
          if (lowerArg === "clear" || lowerArg === "stop" || lowerArg === "cancel") {
            setSlashStatus(targetSessionId, {
              kind: "goal",
              phase: "running",
              message: translate(locale, "common.loading"),
            });
            await c.deleteSessionGoal(targetSessionId);
            useSuperStore.getState().bumpGoalVersion();
            setSlashStatus(
              targetSessionId,
              { kind: "goal", phase: "done", message: translate(locale, "slash.goalCleared") },
              3600,
            );
            return true;
          }

          setSlashStatus(targetSessionId, {
            kind: "goal",
            phase: "running",
            message: translate(locale, "common.loading"),
          });
          await c.createSessionGoal(targetSessionId, { objective: arg });
          useSuperStore.getState().bumpGoalVersion();
          setSlashStatus(
            targetSessionId,
            {
              kind: "goal",
              phase: "done",
              message: translate(locale, "slash.goalStarted", { objective: arg }),
            },
            3600,
          );
          const live = useStreamStore.getState().activeBySession[targetSessionId];
          const busy = Boolean(live?.streaming) || Boolean(get().summariesById[targetSessionId]?.busy);
          if (!busy) {
            await sendMessageImpl(c, {
              sessionId: targetSessionId,
              botId,
              locale,
              sendWhileRunning,
              overrideText: arg,
            });
          }
          return true;
        } catch (err) {
          const msg = err instanceof Error ? err.message : "Goal command failed";
          setSlashStatus(targetSessionId, {
            kind: "goal",
            phase: "error",
            message: translate(locale, "slash.goalFailed", { error: msg }),
            retryText: raw,
          });
          return false;
        }
      }

      const quoted = overrideText === undefined ? state.quotedTextBySession[composerKey] : null;
      const baseText = quoted ? `> ${quoted}\n\n${raw}` : raw;

      let sessionId = opts.sessionId;
      if (isDraft) {
        const createdId = await get().createSession(c, botId, locale);
        if (!createdId) return false;
        const draftModel = get().draftModelByBot[botId];
        if (draftModel) {
          await get()
            .updateSessionModel(c, createdId, draftModel.model, draftModel.thinking, locale)
            .catch(() => void 0);
        }
        sessionId = createdId;
        if (overrideText === undefined) {
          get().setComposerText(sessionId, raw);
          if (quoted) get().setQuotedText(sessionId, quoted);
        }
      }

      const inlineImages: Array<{ data: string; mimeType: string; name?: string }> = [];
      const stagedMeta: Array<{ path: string; name: string; mime: string; size: number }> = [];
      const chips: AttachmentChip[] = [];

      for (const att of attachments) {
        if (att.kind === "folder" && att.folderPath) {
          try {
            const staged = await c.stageFolderAttachment(botId, att.folderPath);
            stagedMeta.push({
              path: staged.path,
              name: staged.name,
              mime: staged.mime,
              size: staged.size,
            });
            chips.push({
              id: staged.id,
              name: staged.name,
              mime: staged.mime,
              size: staged.size,
              path: staged.path,
              kind: "folder",
            });
          } catch (err) {
            const msg = err instanceof Error ? err.message : "Failed to attach folder";
            set({
              actionError: translate(locale, "chat.folderStageFailed", { error: msg }),
            });
            return false;
          }
          continue;
        }

        if (att.isInlineImage && att.dataBase64) {
          inlineImages.push({
            data: att.dataBase64,
            mimeType: att.mimeType,
            name: att.name,
          });
        }
        try {
          const staged = att.file
            ? await c.stageAttachmentStream(botId, att.file, att.name, att.mimeType)
            : await c.stageAttachment(botId, {
                name: att.name,
                mimeType: att.mimeType,
                dataBase64: att.dataBase64 ?? "",
              });
          stagedMeta.push({
            path: staged.path,
            name: staged.name,
            mime: staged.mime,
            size: staged.size,
          });
          chips.push({
            id: staged.id,
            name: staged.name,
            mime: staged.mime,
            size: staged.size,
            path: staged.path,
            kind: att.kind,
            previewUrl: att.previewUrl,
          });
        } catch (err) {
          if (!att.isInlineImage || !att.dataBase64) {
            set({
              actionError: err instanceof Error ? err.message : "Failed to upload attachment",
            });
            return false;
          }
          chips.push({
            id: att.id,
            name: att.name,
            mime: att.mimeType,
            size: att.size,
            path: att.name,
            kind: "image",
            previewUrl: att.previewUrl,
          });
        }
      }

      const attachmentBlock = encodeAttachmentBlock(stagedMeta);
      const userContent = attachmentBlock
        ? baseText
          ? `${attachmentBlock}\n\n${baseText}`
          : attachmentBlock
        : baseText;

      const clearDraftOnSuccess = () => {
        if (overrideText === undefined) {
          get().setComposerText(composerKey, "");
          get().setQuotedText(composerKey, null);
          if (sessionId !== composerKey) {
            get().setComposerText(sessionId, "");
            get().setQuotedText(sessionId, null);
          }
        }
      };

      const liveRun = useStreamStore.getState().activeBySession[sessionId];
      const isCurrentlyRunning =
        Boolean(liveRun?.streaming) || Boolean(get().summariesById[sessionId]?.busy);

      if (isCurrentlyRunning) {
        try {
          if (sendWhileRunning === "queue") {
            await c.followUpSession(sessionId, userContent, inlineImages);
          } else {
            await c.sendPrompt(sessionId, {
              text: userContent,
              steer: true,
              images: inlineImages,
            });
          }
          clearDraftOnSuccess();
          return true;
        } catch (err) {
          set({ actionError: err instanceof Error ? err.message : "Request failed" });
          return false;
        }
      }

      const carryTurn = liveRun ? materializeFinishedLiveRun(liveRun, locale) : null;
      localSeq += 1;
      const pendingRunId = `pending-${Date.now()}-${localSeq}`;
      const userTurn: ChatTurn = {
        id: `u-local-${Date.now()}-${localSeq}`,
        runId: pendingRunId,
        role: "user",
        rawText: baseText,
        timestamp: formatClockTime(Date.now(), locale),
        status: "done",
        failed: false,
        notices: [],
        attachments: chips,
        blocks: [],
      };

      set((s) => {
        const tr = s.transcriptsById[sessionId];
        const sum = s.summariesById[sessionId];
        const baseTurns = tr ? [...tr.turns] : [];
        if (carryTurn && !baseTurns.some((t) => t.role === "assistant" && t.runId === carryTurn.runId)) {
          baseTurns.push(carryTurn);
        }
        const nextTurns = [...baseTurns, userTurn];
        const nextTr: SessionTranscript = tr
          ? { ...tr, turns: nextTurns }
          : {
              sessionId,
              turns: nextTurns,
              usage: null,
              contextPercent: 0,
              queuedInputs: [],
              pendingQuestion: null,
              loadedAt: Date.now(),
            };
        return {
          transcriptsById: { ...s.transcriptsById, [sessionId]: nextTr },
          summariesById: sum
            ? {
                ...s.summariesById,
                [sessionId]: {
                  ...sum,
                  busy: true,
                  updatedAtMs: Date.now(),
                  title: sum.title || baseText.slice(0, 28),
                },
              }
            : s.summariesById,
        };
      });

      useStreamStore.getState().beginRun(sessionId, pendingRunId);

      try {
        await c.sendPrompt(sessionId, {
          text: userContent,
          steer: false,
          images: inlineImages,
        });
        clearDraftOnSuccess();
        return true;
      } catch (err) {
        const msg = err instanceof Error ? err.message : "Request failed";
        useStreamStore.getState().failRun(sessionId, pendingRunId, msg);
        get().setSessionBusy(sessionId, false);
        set({ actionError: msg });
        return false;
      }
    };

    const promise = sendMessageImpl(client, options).finally(() => {
      inFlightSends.delete(lockKey);
    });
    inFlightSends.set(lockKey, promise);
    return promise;
  },

  stopSession: async (client, sessionId) => {
    const cur = useStreamStore.getState().activeBySession[sessionId];
    if (cur) {
      useStreamStore.getState().finishRun(sessionId, cur.runId, "stopped");
    }
    get().setSessionBusy(sessionId, false);
    await client.abortSession(sessionId).catch(() => void 0);
  },

  retrySession: async (client, sessionId) => {
    const pendingRunId = `pending-retry-${Date.now()}`;
    useStreamStore.getState().beginRun(sessionId, pendingRunId);
    get().setSessionBusy(sessionId, true);
    try {
      await client.retrySession(sessionId);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Retry failed";
      useStreamStore.getState().failRun(sessionId, pendingRunId, msg);
      get().setSessionBusy(sessionId, false);
    }
  },

  answerQuestion: async (client, requestId, answers) => {
    set({ actionError: null });
    try {
      await client.answerQuestion(requestId, answers);
      set((s) => clearResolvedQuestionFromState(s, requestId));
      await get().refreshQuestions(client);
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to submit answer" });
    }
  },

  dismissQuestion: async (client, requestId) => {
    set({ actionError: null });
    try {
      await client.dismissQuestion(requestId);
      set((s) => clearResolvedQuestionFromState(s, requestId));
      await get().refreshQuestions(client);
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to dismiss question" });
    }
  },

  resolveToolApproval: async (client, approvalId, decision, note) => {
    set({ actionError: null });
    try {
      await client.resolveApproval(approvalId, decision, note);
      const activeId = get().activeSessionId;
      await get().refreshApprovals(client);
      if (activeId) {
        set((s) => {
          const tr = s.transcriptsById[activeId];
          if (!tr?.pendingApprovals?.some((a) => a.id === approvalId)) return s;
          return {
            transcriptsById: {
              ...s.transcriptsById,
              [activeId]: {
                ...tr,
                pendingApprovals: tr.pendingApprovals.filter((a) => a.id !== approvalId),
              },
            },
          };
        });
      }
    } catch (err) {
      set({ actionError: err instanceof Error ? err.message : "Failed to resolve approval" });
    }
  },

  setComposerText: (sessionId, value) => {
    set((s) => ({
      composerTextBySession: { ...s.composerTextBySession, [sessionId]: value },
    }));
  },

  setQuotedText: (sessionId, value) => {
    set((s) => ({
      quotedTextBySession: { ...s.quotedTextBySession, [sessionId]: value },
      composerFocusTick: value ? s.composerFocusTick + 1 : s.composerFocusTick,
    }));
  },

  setSessionBusy: (sessionId, busy) => {
    set((s) => {
      const sum = s.summariesById[sessionId];
      if (!sum || sum.busy === busy) return s;
      return {
        summariesById: { ...s.summariesById, [sessionId]: { ...sum, busy } },
      };
    });
  },

  markSessionUnread: (sessionId) => {
    set((s) => ({
      unreadSessionIds: { ...s.unreadSessionIds, [sessionId]: true },
    }));
  },

  clearSessionUnread: (sessionId) => {
    set((s) => {
      if (!s.unreadSessionIds[sessionId]) return s;
      const next = { ...s.unreadSessionIds };
      delete next[sessionId];
      return { unreadSessionIds: next };
    });
  },

  requestComposerFocus: () => {
    set((s) => ({ composerFocusTick: s.composerFocusTick + 1 }));
  },

  reset: () => {
    inFlightLoads.clear();
    pendingLoads.clear();
    inFlightSends.clear();
    for (const rec of pendingDeleteRecords.values()) {
      clearTimeout(rec.timer);
    }
    pendingDeleteRecords.clear();
    for (const timer of slashStatusTimers.values()) {
      clearTimeout(timer);
    }
    slashStatusTimers.clear();
    set({
      summariesById: {},
      sessionIdsByBot: {},
      transcriptsById: {},
      lruOrder: [],
      archivedSessions: [],
      pendingQuestions: [],
      pendingApprovals: [],
      unreadSessionIds: {},
      loadingSessionIds: {},
      sessionLoadErrors: {},
      loadingEarlierBySession: {},
      loadEarlierErrorBySession: {},
      pendingDeletes: [],
      draftBotId: null,
      draftModelByBot: {},
      slashStatusBySession: {},
    });
  },
  };
});

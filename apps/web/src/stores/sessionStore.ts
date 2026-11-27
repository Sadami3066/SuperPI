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
  HostQueuedInputDto,
  HostSessionSummaryDto,
  HostUsageDto,
  QuestionAnswerInput,
  SessionSummary,
  SessionTranscript,
  ThinkingLevel,
  TurnBlock,
} from "../api/types";
import { useBotStore } from "./botStore";
import { type LiveRunState, useStreamStore } from "./streamStore";

export const MAX_CACHED_TRANSCRIPTS = 5;

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
let localSeq = 0;
let refreshSeq = 0;
let createInFlight = false;

export interface SessionStoreState {
  summariesById: Record<string, SessionSummary>;
  sessionIdsByBot: Record<string, string[]>;
  transcriptsById: Record<string, SessionTranscript>;
  lruOrder: string[];
  archivedSessions: SessionSummary[];
  pendingQuestions: HostPendingQuestionDto[];
  activeSessionId: string;
  unreadSessionIds: Record<string, true>;
  composerFocusTick: number;
  actionError: string | null;
  composerTextBySession: Record<string, string>;
  quotedTextBySession: Record<string, string | null>;
  planFirstBySession: Record<string, boolean>;

  clearActionError: () => void;

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
  patchSessionUsage: (sessionId: string, usage: HostUsageDto) => void;
  loadArchivedSessions: (client: HostClient) => Promise<void>;
  refreshQuestions: (client: HostClient) => Promise<void>;
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
  ) => Promise<string | null>;
  renameSession: (client: HostClient, sessionId: string, title: string) => Promise<void>;
  togglePinSession: (client: HostClient, sessionId: string, pinned: boolean) => Promise<void>;
  toggleArchiveSession: (client: HostClient, sessionId: string, archived: boolean) => Promise<void>;
  toggleMemoryExclude: (
    client: HostClient,
    sessionId: string,
    excluded: boolean,
  ) => Promise<void>;
  deleteSession: (client: HostClient, sessionId: string) => Promise<void>;
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
      planFirstOverride?: boolean;
    },
  ) => Promise<void>;
  stopSession: (client: HostClient, sessionId: string) => Promise<void>;
  retrySession: (client: HostClient, sessionId: string) => Promise<void>;
  answerQuestion: (
    client: HostClient,
    requestId: string,
    answers: QuestionAnswerInput[],
  ) => Promise<void>;
  dismissQuestion: (client: HostClient, requestId: string) => Promise<void>;
  setComposerText: (sessionId: string, value: string) => void;
  setQuotedText: (sessionId: string, value: string | null) => void;
  togglePlanFirst: (sessionId: string) => void;
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

export const useSessionStore = create<SessionStoreState>((set, get) => ({
  summariesById: {},
  sessionIdsByBot: {},
  transcriptsById: {},
  lruOrder: [],
  archivedSessions: [],
  pendingQuestions: [],
  activeSessionId: loadStoredSessionId(),
  unreadSessionIds: {},
  composerFocusTick: 0,
  actionError: null,
  composerTextBySession: {},
  quotedTextBySession: {},
  planFirstBySession: {},

  clearActionError: () => set({ actionError: null }),

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
    if (!targetId || !summariesById[targetId]) {
      if (allIds.length > 0) {
        targetId = allIds[0];
      } else if (bots.length > 0) {
        const created = await client.createSession(bots[0].id);
        targetId = created.sessionId;
        summariesById[targetId] = {
          id: targetId,
          botId: bots[0].id,
          title: "",
          updatedAtMs: Date.now(),
          cwd: created.cwd,
          pinned: false,
          archived: false,
          memoryExcluded: false,
          busy: false,
        };
        sessionIdsByBot[bots[0].id] = [targetId];
      }
    }

    saveStoredSessionId(targetId);
    set({ summariesById, sessionIdsByBot, activeSessionId: targetId });
    if (targetId) {
      void get().loadSession(client, targetId, locale);
    }
    void get().refreshQuestions(client);
  },

  refreshSessionLists: async (client, bots) => {
    const seq = ++refreshSeq;
    const results = await Promise.all(
      bots.map(async (b) => {
        const list = await client.listSessions(b.id).catch(() => [] as HostSessionSummaryDto[]);
        return { botId: b.id, list: list.filter((x) => !x.archived) };
      }),
    );
    if (seq !== refreshSeq) return;
    set((s) => {
      const summariesById: Record<string, SessionSummary> = {};
      const sessionIdsByBot: Record<string, string[]> = {};
      for (const { botId, list } of results) {
        sessionIdsByBot[botId] = list.map((item) => item.id);
        for (const dto of list) {
          summariesById[dto.id] = mapSummary(dto, botId);
        }
      }
      const curActive = s.activeSessionId;
      const prevActiveSummary = curActive ? s.summariesById[curActive] : undefined;
      const prevActiveTranscript = curActive ? s.transcriptsById[curActive] : undefined;
      if (
        curActive &&
        !summariesById[curActive] &&
        prevActiveSummary &&
        prevActiveTranscript &&
        prevActiveTranscript.turns.length === 0
      ) {
        summariesById[curActive] = prevActiveSummary;
        const botIds = sessionIdsByBot[prevActiveSummary.botId] ?? [];
        if (!botIds.includes(curActive)) {
          sessionIdsByBot[prevActiveSummary.botId] = [curActive, ...botIds];
        }
      }
      const allIds = Object.keys(summariesById);
      const nextActive = curActive && summariesById[curActive] ? curActive : allIds[0] ?? curActive;
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
      try {
        const detail = await client.getSession(sessionId);

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
          return {
            ...evicted,
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
      } catch {
        return;
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
    set({ pendingQuestions: list });
  },

  selectSession: async (client, sessionId, locale) => {
    saveStoredSessionId(sessionId);
    set((s) => {
      const nextOrder = s.transcriptsById[sessionId] ? touchLru(s.lruOrder, sessionId) : s.lruOrder;
      const nextUnread = { ...s.unreadSessionIds };
      delete nextUnread[sessionId];
      return {
        activeSessionId: sessionId,
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

  forkSession: async (client, sessionId, locale) => {
    set({ actionError: null });
    try {
      const src = get().summariesById[sessionId];
      const forkTitle = src?.title ? `${src.title} (branch)` : undefined;
      const res = await client.forkSession(sessionId, forkTitle ? { title: forkTitle } : undefined);
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
    await client.updateSession(sessionId, { pinned });
    set((s) => {
      const cur = s.summariesById[sessionId];
      if (!cur) return s;
      return {
        summariesById: { ...s.summariesById, [sessionId]: { ...cur, pinned } },
      };
    });
  },

  toggleArchiveSession: async (client, sessionId, archived) => {
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
          ? Object.keys(nextSummaries)[0] ?? ""
          : s.activeSessionId;
      if (fallbackId !== s.activeSessionId) saveStoredSessionId(fallbackId);
      return {
        summariesById: nextSummaries,
        sessionIdsByBot: nextByBot,
        activeSessionId: fallbackId,
      };
    });
    if (!archived) {
      await get().refreshSessionLists(client, useBotStore.getState().bots);
    }
  },

  toggleMemoryExclude: async (client, sessionId, excluded) => {
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
  },

  deleteSession: async (client, sessionId) => {
    await client.deleteSession(sessionId);
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
          ? Object.keys(nextSummaries)[0] ?? ""
          : s.activeSessionId;
      if (fallbackId !== s.activeSessionId) saveStoredSessionId(fallbackId);
      return {
        summariesById: nextSummaries,
        sessionIdsByBot: nextByBot,
        transcriptsById: nextCache,
        lruOrder: s.lruOrder.filter((id) => id !== sessionId),
        archivedSessions: s.archivedSessions.filter((x) => x.id !== sessionId),
        activeSessionId: fallbackId,
      };
    });
  },

  updateSessionModel: async (client, sessionId, model, thinking, locale) => {
    const slash = model.indexOf("/");
    if (slash < 1) return;
    const providerId = model.slice(0, slash);
    const modelId = model.slice(slash + 1);
    set((s) => {
      const tr = s.transcriptsById[sessionId];
      if (!tr) return s;
      return {
        transcriptsById: {
          ...s.transcriptsById,
          [sessionId]: { ...tr, model, thinking },
        },
      };
    });
    await client.setSessionModel(sessionId, providerId, modelId, thinking.toUpperCase());
    void get().loadSession(client, sessionId, locale);
  },

  compactSession: async (client, sessionId, locale) => {
    const res = await client.compactSession(sessionId);
    if (res.ok) {
      await get().loadSession(client, sessionId, locale);
    }
    return { ok: res.ok, message: res.message };
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
    const queue = await client.removeQueuedInput(sessionId, kind, text);
    get().setQueueForSession(sessionId, queue);
  },

  promoteQueuedInput: async (client, sessionId, text) => {
    const queue = await client.promoteQueuedInput(sessionId, text);
    get().setQueueForSession(sessionId, queue);
  },

  sendMessage: async (client, options) => {
    const {
      sessionId,
      botId,
      locale,
      sendWhileRunning,
      overrideText,
      attachments = [],
      planFirstOverride,
    } = options;
    const state = get();
    const raw = (overrideText ?? state.composerTextBySession[sessionId] ?? "").trim();
    if (!raw && attachments.length === 0) return;

    if (attachments.length === 0 && raw === "/compact") {
      state.setComposerText(sessionId, "");
      await state.compactSession(client, sessionId, locale);
      return;
    }

    const quoted = overrideText === undefined ? state.quotedTextBySession[sessionId] : null;
    const baseText = quoted ? `> ${quoted}\n\n${raw}` : raw;
    if (overrideText === undefined) {
      state.setComposerText(sessionId, "");
      state.setQuotedText(sessionId, null);
    }

    const inlineImages: Array<{ data: string; mimeType: string; name?: string }> = [];
    const stagedMeta: Array<{ path: string; name: string; mime: string; size: number }> = [];
    const chips: AttachmentChip[] = [];

    for (const att of attachments) {
      if (att.isInlineImage) {
        inlineImages.push({
          data: att.dataBase64,
          mimeType: att.mimeType,
          name: att.name,
        });
      }
      try {
        const staged = await client.stageAttachment(botId, {
          name: att.name,
          mimeType: att.mimeType,
          dataBase64: att.dataBase64,
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
        if (!att.isInlineImage) throw err;
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

    const liveRun = useStreamStore.getState().activeBySession[sessionId];
    const isCurrentlyRunning =
      Boolean(liveRun?.streaming) || Boolean(state.summariesById[sessionId]?.busy);

    if (isCurrentlyRunning) {
      if (sendWhileRunning === "queue") {
        await client.followUpSession(sessionId, userContent, inlineImages);
      } else {
        await client.sendPrompt(sessionId, {
          text: userContent,
          steer: true,
          images: inlineImages,
        });
      }
      return;
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

    const planFirst = planFirstOverride ?? Boolean(state.planFirstBySession[sessionId]);
    try {
      await client.sendPrompt(sessionId, {
        text: userContent,
        steer: false,
        planFirst,
        images: inlineImages,
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Request failed";
      useStreamStore.getState().failRun(sessionId, pendingRunId, msg);
      get().setSessionBusy(sessionId, false);
    }
  },

  stopSession: async (client, sessionId) => {
    await client.abortSession(sessionId);
    const cur = useStreamStore.getState().activeBySession[sessionId];
    if (cur) {
      useStreamStore.getState().finishRun(sessionId, cur.runId, "aborted");
    }
    get().setSessionBusy(sessionId, false);
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
    await client.answerQuestion(requestId, answers);
    await get().refreshQuestions(client);
  },

  dismissQuestion: async (client, requestId) => {
    await client.dismissQuestion(requestId);
    await get().refreshQuestions(client);
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

  togglePlanFirst: (sessionId) => {
    set((s) => ({
      planFirstBySession: {
        ...s.planFirstBySession,
        [sessionId]: !s.planFirstBySession[sessionId],
      },
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
    set({
      summariesById: {},
      sessionIdsByBot: {},
      transcriptsById: {},
      lruOrder: [],
      archivedSessions: [],
      pendingQuestions: [],
      unreadSessionIds: {},
    });
  },
}));

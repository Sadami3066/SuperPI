import { create } from "zustand";
import { HostClient } from "../api/hostClient";
import type { HostSseEvent } from "../api/types";
import { translate } from "../i18n";
import {
  clearBrowserToken,
  discoverHostConnection,
  getDesktopEnv,
  reconnectHostNative,
  saveManualBrowserToken,
  showDesktopNotification,
  type DesktopEnv,
} from "../platform/bridge";
import {
  collectNewNotificationItems,
  isUserAbortedError,
  resetNotificationBaselines,
  seedNotificationBaselines,
} from "../platform/notifications";
import { useBotStore } from "./botStore";
import { useSessionStore } from "./sessionStore";
import { useSettingsStore } from "./settingsStore";
import { useStreamStore } from "./streamStore";
import { SUPER_SESSION_ID, useSuperStore } from "./superStore";

export type ConnectionPhase = "connecting" | "live" | "error" | "needs_token";

let sseUnsubscribe: (() => void) | null = null;
let connectGeneration = 0;

function handleHostSseEvent(client: HostClient, ev: HostSseEvent): void {
  const sessionStore = useSessionStore.getState();
  const streamStore = useStreamStore.getState();
  const botStore = useBotStore.getState();
  const settingsStore = useSettingsStore.getState();
  const locale = settingsStore.locale;

  if (ev.type === "questions_changed") {
    void sessionStore.refreshQuestions(client).then(() => {
      const fresh = collectNewNotificationItems(
        "question",
        useSessionStore.getState().pendingQuestions,
        (q) => q.requestId,
      );
      for (const q of fresh) {
        const isSuper = ev.status?.startsWith("super:");
        const title = isSuper
          ? "Super PI"
          : useSessionStore.getState().summariesById[q.sessionId]?.title ||
            translate(locale, "nav.untitledChat");
        void showDesktopNotification({
          tag: isSuper ? "super" : `session:${q.sessionId}`,
          title: translate(locale, "notify.questionTitle", { title }),
          body: translate(locale, "notify.questionBody"),
          command: isSuper ? { kind: "super" } : { kind: "chat", sessionId: q.sessionId },
        });
      }
    });
    if (ev.status?.startsWith("super:")) {
      useSuperStore.getState().scheduleRefreshState(client);
    }
    return;
  }

  if (ev.type === "approvals_changed") {
    void sessionStore.refreshApprovals(client).then(() => {
      const fresh = collectNewNotificationItems(
        "approval",
        useSessionStore.getState().pendingApprovals,
        (a) => a.id,
      );
      for (const a of fresh) {
        const isSuper = a.sessionId === "super" || Boolean(a.workerId);
        const title = isSuper
          ? "Super PI"
          : useSessionStore.getState().summariesById[a.sessionId]?.title ||
            translate(locale, "nav.untitledChat");
        void showDesktopNotification({
          tag: isSuper ? "super" : `session:${a.sessionId}`,
          title: translate(locale, "notify.approvalTitle", { title }),
          body: translate(locale, "notify.approvalBody"),
          command: isSuper ? { kind: "super" } : { kind: "chat", sessionId: a.sessionId },
        });
      }
    });
    useSuperStore.getState().scheduleRefreshState(client);
    return;
  }

  if (ev.type === "inbox_changed") {
    void useSuperStore
      .getState()
      .refreshInbox(client)
      .then(() => {
        const fresh = collectNewNotificationItems(
          "inbox",
          useSuperStore.getState().inbox,
          (item) => item.id,
        );
        for (const item of fresh) {
          if (item.source !== "timer" || item.status !== "unread") continue;
          void showDesktopNotification({
            tag: item.sessionId ? `session:${item.sessionId}` : `inbox:${item.id}`,
            title: item.title || translate(locale, "notify.timerTitle"),
            body: item.summary || translate(locale, "notify.chatDoneBody"),
            command: item.sessionId
              ? { kind: "chat", sessionId: item.sessionId }
              : { kind: "feature", view: "inbox" },
          });
        }
      });
    return;
  }

  if (ev.type === "goal_changed") {
    useSuperStore.getState().bumpGoalVersion();
    return;
  }

  if (ev.type === "jobs_changed") {
    useSuperStore.getState().bumpJobsVersion();
    return;
  }

  if (ev.type === "worker_progress" || ev.type === "worker_approval") {
    useSuperStore.getState().scheduleRefreshState(client);
    return;
  }

  if (ev.type.startsWith("super_")) {
    const baseType = ev.type.slice("super_".length);
    const runId = ev.runId || "super-live";
    if (baseType === "run_started") {
      streamStore.beginRun(SUPER_SESSION_ID, runId);
      useSuperStore.getState().scheduleRefreshState(client);
      return;
    }
    if (baseType === "activity_changed") {
      streamStore.setActivity(SUPER_SESSION_ID, runId, ev.activity ?? null);
      return;
    }
    if (baseType === "text_delta" && ev.text) {
      streamStore.enqueueTextDelta(SUPER_SESSION_ID, runId, ev.text);
      return;
    }
    if (baseType === "reasoning_delta" && ev.text) {
      streamStore.enqueueReasoningDelta(SUPER_SESSION_ID, runId, ev.text);
      return;
    }
    if ((baseType === "tool_call_started" || baseType === "tool_started") && ev.toolName) {
      streamStore.upsertToolStart(
        SUPER_SESSION_ID,
        runId,
        ev.callId || `tl-${Date.now()}`,
        ev.toolName,
        ev.arguments,
      );
      return;
    }
    if (baseType === "tool_finished" && ev.toolName) {
      streamStore.finishTool(
        SUPER_SESSION_ID,
        runId,
        ev.callId || "",
        ev.toolName,
        ev.output ?? "",
        Boolean(ev.isError),
        ev.durationMs ?? 0,
      );
      return;
    }
    if (baseType === "custom_card" && (ev.payload || ev.text)) {
      streamStore.addCustomCard(
        SUPER_SESSION_ID,
        runId,
        ev.customType || "card",
        ev.payload || "{}",
        ev.text || "",
      );
      return;
    }
    if (baseType === "run_finished") {
      streamStore.finishRun(SUPER_SESSION_ID, runId, ev.status, ev.text);
      void showDesktopNotification({
        tag: "super",
        title: translate(locale, "notify.superDoneTitle"),
        body: translate(locale, "notify.chatDoneBody"),
        command: { kind: "super" },
      });
      void useSuperStore
        .getState()
        .refreshState(client)
        .finally(() => {
          streamStore.clearRun(SUPER_SESSION_ID, runId);
        });
      return;
    }
    if (baseType === "run_failed") {
      const errText = ev.text?.trim() || "Run failed";
      streamStore.failRun(SUPER_SESSION_ID, runId, errText);
      if (!isUserAbortedError(errText)) {
        void showDesktopNotification({
          tag: "super",
          title: translate(locale, "notify.superFailedTitle"),
          body: translate(locale, "notify.chatFailedBody"),
          command: { kind: "super" },
        });
      }
      void useSuperStore
        .getState()
        .refreshState(client)
        .finally(() => {
          streamStore.clearRun(SUPER_SESSION_ID, runId);
        });
      return;
    }
    if (baseType === "capabilities_changed") {
      useSuperStore.getState().scheduleRefreshState(client);
      return;
    }
    if (baseType === "usage_changed" && ev.usage) {
      useSuperStore.getState().patchUsage(ev.usage);
      return;
    }
    if (
      baseType === "queue_changed" ||
      baseType === "queue_dropped" ||
      baseType === "user_input_admitted" ||
      baseType === "turn_injected" ||
      baseType === "session_updated"
    ) {
      useSuperStore.getState().scheduleRefreshState(client);
    }
    return;
  }

  if (ev.type === "devices_changed") {
    void botStore.refreshBots(client).then((bots) => {
      void sessionStore.refreshSessionLists(client, bots);
    });
    void settingsStore.refreshRelay(client);
    return;
  }

  if (
    ev.type === "session_list_changed" ||
    ev.type === "session_removed" ||
    ev.type === "session_archived"
  ) {
    void sessionStore.refreshSessionLists(client, botStore.bots);
    return;
  }

  const sid = ev.sessionId;
  if (!sid) return;

  const runId = ev.runId || "live";

  if (ev.type === "usage_changed" && ev.usage) {
    sessionStore.patchSessionUsage(sid, ev.usage);
    return;
  }

  if (ev.type === "notice") {
    return;
  }

  if (ev.type === "queue_changed") {
    sessionStore.setQueueForSession(sid, [
      ...(ev.steering ?? []).map((text) => ({ kind: "steer" as const, text })),
      ...(ev.followUp ?? []).map((text) => ({ kind: "follow_up" as const, text })),
    ]);
    return;
  }

  if (ev.type === "queue_dropped" || ev.type === "user_input_admitted" || ev.type === "turn_injected") {
    void sessionStore.loadSession(client, sid, locale);
    return;
  }

  if (ev.type === "run_started") {
    streamStore.beginRun(sid, runId);
    sessionStore.setSessionBusy(sid, true);
    return;
  }

  if (ev.type === "activity_changed") {
    streamStore.setActivity(sid, runId, ev.activity ?? null);
    return;
  }

  if (ev.type === "text_delta" && ev.text) {
    streamStore.enqueueTextDelta(sid, runId, ev.text);
    return;
  }

  if (ev.type === "reasoning_delta" && ev.text) {
    streamStore.enqueueReasoningDelta(sid, runId, ev.text);
    return;
  }

  if ((ev.type === "tool_call_started" || ev.type === "tool_started") && ev.toolName) {
    streamStore.upsertToolStart(sid, runId, ev.callId || `tl-${Date.now()}`, ev.toolName, ev.arguments);
    return;
  }

  if (ev.type === "tool_finished" && ev.toolName) {
    streamStore.finishTool(
      sid,
      runId,
      ev.callId || "",
      ev.toolName,
      ev.output ?? "",
      Boolean(ev.isError),
      ev.durationMs ?? 0,
    );
    return;
  }

  if (ev.type === "custom_card" && (ev.payload || ev.text)) {
    streamStore.addCustomCard(sid, runId, ev.customType || "card", ev.payload || "{}", ev.text || "");
    return;
  }

  if (ev.type === "run_finished") {
    streamStore.finishRun(sid, runId, ev.status, ev.text);
    sessionStore.setSessionBusy(sid, false);
    if (sid !== sessionStore.activeSessionId) {
      sessionStore.markSessionUnread(sid);
    }
    const title =
      useSessionStore.getState().summariesById[sid]?.title ||
      translate(locale, "nav.untitledChat");
    void showDesktopNotification({
      tag: `session:${sid}`,
      title: translate(locale, "notify.chatDoneTitle", { title }),
      body: translate(locale, "notify.chatDoneBody"),
      command: { kind: "chat", sessionId: sid },
    });
    void sessionStore.loadSession(client, sid, locale, runId);
    return;
  }

  if (ev.type === "run_failed") {
    const errText = ev.text?.trim() || "";
    const curLive = streamStore.activeBySession[sid];
    const wasAborted =
      curLive?.status === "stopped" || isUserAbortedError(errText);
    if (wasAborted) {
      streamStore.finishRun(sid, runId, "stopped");
    } else {
      streamStore.failRun(sid, runId, errText || "Run failed");
      const title =
        useSessionStore.getState().summariesById[sid]?.title ||
        translate(locale, "nav.untitledChat");
      void showDesktopNotification({
        tag: `session:${sid}`,
        title: translate(locale, "notify.chatFailedTitle", { title }),
        body: translate(locale, "notify.chatFailedBody"),
        command: { kind: "chat", sessionId: sid },
      });
    }
    sessionStore.setSessionBusy(sid, false);
    void sessionStore.loadSession(client, sid, locale);
    return;
  }

  if (ev.type === "session_updated" || ev.type === "capabilities_changed") {
    void sessionStore.loadSession(client, sid, locale);
  }
}

export interface ConnectionStoreState {
  status: ConnectionPhase;
  sseConnected: boolean;
  sseLost: boolean;
  error: string | null;
  client: HostClient | null;
  desktopEnv: DesktopEnv | null;

  bootstrap: () => Promise<void>;
  reconnect: () => Promise<void>;
  submitBrowserToken: (token: string) => Promise<void>;
  disconnect: () => void;
}

export const useConnectionStore = create<ConnectionStoreState>((set, get) => ({
  status: "connecting",
  sseConnected: false,
  sseLost: false,
  error: null,
  client: null,
  desktopEnv: null,

  bootstrap: async () => {
    const gen = ++connectGeneration;
    if (sseUnsubscribe) {
      sseUnsubscribe();
      sseUnsubscribe = null;
    }
    useStreamStore.getState().resetAll();
    useSuperStore.getState().reset();
    set({ status: "connecting", error: null, sseConnected: false, sseLost: false });

    const env = await getDesktopEnv().catch(() => null);
    if (gen !== connectGeneration) return;
    if (env) set({ desktopEnv: env });

    try {
      const conn = await discoverHostConnection();
      if (gen !== connectGeneration) return;
      if (!conn) {
        set({
          client: null,
          status: env?.isElectron ? "error" : "needs_token",
          error: null,
        });
        return;
      }

      const client = new HostClient({ baseUrl: conn.baseUrl, token: conn.token });
      await client.getHealth();
      if (gen !== connectGeneration) return;

      const settingsStore = useSettingsStore.getState();
      const botStore = useBotStore.getState();
      const sessionStore = useSessionStore.getState();

      await settingsStore.refreshSettingsData(client);
      if (gen !== connectGeneration) return;

      const bots = await botStore.refreshBots(client);
      if (gen !== connectGeneration) return;

      await sessionStore.hydrateSessionLists(client, bots, useSettingsStore.getState().locale);
      if (gen !== connectGeneration) return;

      await Promise.all([
        sessionStore.refreshQuestions(client),
        sessionStore.refreshApprovals(client),
        useSuperStore.getState().refreshInbox(client),
      ]);
      if (gen !== connectGeneration) return;

      const seededSessions = useSessionStore.getState();
      seedNotificationBaselines({
        questionIds: seededSessions.pendingQuestions.map((q) => q.requestId),
        approvalIds: seededSessions.pendingApprovals.map((a) => a.id),
        inboxIds: useSuperStore.getState().inbox.map((item) => item.id),
      });

      let hadSse = false;
      let sseDisconnectedAfterOpen = false;
      sseUnsubscribe = client.subscribeEvents(
        (ev) => handleHostSseEvent(client, ev),
        (connected) => {
          if (gen !== connectGeneration) return;
          const shouldReconcile = connected && hadSse && sseDisconnectedAfterOpen;
          if (connected) {
            hadSse = true;
            sseDisconnectedAfterOpen = false;
          } else if (hadSse) {
            sseDisconnectedAfterOpen = true;
          }
          set({
            sseConnected: connected,
            sseLost: !connected && hadSse,
          });
          if (shouldReconcile) {
            const sStore = useSessionStore.getState();
            const bots = useBotStore.getState().bots;
            const loc = useSettingsStore.getState().locale;
            void sStore.refreshQuestions(client);
            void sStore.refreshApprovals(client);
            void sStore.refreshSessionLists(client, bots);
            if (sStore.activeSessionId) {
              void sStore.loadSession(client, sStore.activeSessionId, loc);
            }
            const sup = useSuperStore.getState();
            void sup.refreshState(client);
            void sup.refreshInbox(client);
          }
        },
      );

      set({ client, status: "live", error: null });
    } catch (err) {
      if (gen !== connectGeneration) return;
      const msg = err instanceof Error ? err.message : "Failed to connect";
      if (!env?.isElectron && msg.includes("401")) {
        clearBrowserToken();
        set({ client: null, status: "needs_token", error: msg });
        return;
      }
      set({ client: null, status: "error", error: msg });
    }
  },

  reconnect: async () => {
    await reconnectHostNative().catch(() => null);
    await get().bootstrap();
  },

  submitBrowserToken: async (token) => {
    saveManualBrowserToken(token);
    await get().bootstrap();
  },

  disconnect: () => {
    connectGeneration++;
    if (sseUnsubscribe) {
      sseUnsubscribe();
      sseUnsubscribe = null;
    }
    useStreamStore.getState().resetAll();
    useSuperStore.getState().reset();
    resetNotificationBaselines();
  },
}));

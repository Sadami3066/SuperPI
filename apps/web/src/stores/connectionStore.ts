import { create } from "zustand";
import { HostClient } from "../api/hostClient";
import type { HostSseEvent } from "../api/types";
import {
  clearBrowserToken,
  discoverHostConnection,
  getDesktopEnv,
  reconnectHostNative,
  saveManualBrowserToken,
  type DesktopEnv,
} from "../platform/bridge";
import { useBotStore } from "./botStore";
import { useSessionStore } from "./sessionStore";
import { useSettingsStore } from "./settingsStore";
import { useStreamStore } from "./streamStore";

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
    void sessionStore.refreshQuestions(client);
    return;
  }

  if (ev.type === "devices_changed") {
    void botStore.refreshBots(client).then((bots) => {
      void sessionStore.refreshSessionLists(client, bots);
    });
    void settingsStore.refreshRelay(client);
    return;
  }

  const sid = ev.sessionId;
  if (!sid) return;

  if (
    ev.type === "session_list_changed" ||
    ev.type === "session_removed" ||
    ev.type === "session_archived"
  ) {
    void sessionStore.refreshSessionLists(client, botStore.bots);
    return;
  }

  const runId = ev.runId || "live";

  if (ev.type === "queue_changed") {
    sessionStore.setQueueForSession(sid, [
      ...(ev.steering ?? []).map((text) => ({ kind: "steer" as const, text })),
      ...(ev.followUp ?? []).map((text) => ({ kind: "follow_up" as const, text })),
    ]);
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
    void sessionStore.loadSession(client, sid, locale, runId);
    return;
  }

  if (ev.type === "run_failed") {
    streamStore.failRun(sid, runId, ev.text?.trim() || "Run failed");
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

      let hadSse = false;
      sseUnsubscribe = client.subscribeEvents(
        (ev) => handleHostSseEvent(client, ev),
        (connected) => {
          if (gen !== connectGeneration) return;
          if (connected) hadSse = true;
          set({
            sseConnected: connected,
            sseLost: !connected && hadSse,
          });
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
  },
}));

import { create } from "zustand";
import type { HostClient } from "../api/hostClient";
import type {
  HostInboxItemDto,
  HostSuperSettingsDto,
  HostSuperStateResponse,
  HostSuperTeamBotDto,
  QuestionAnswerInput,
} from "../api/types";

export const SUPER_SESSION_ID = "__super__";

let refreshTimer: ReturnType<typeof setTimeout> | null = null;

export interface SuperStoreState {
  state: HostSuperStateResponse | null;
  team: HostSuperTeamBotDto[];
  superSettings: HostSuperSettingsDto | null;
  inbox: HostInboxItemDto[];
  unreadInboxCount: number;
  selectedWorkerId: string | null;
  composerText: string;
  goalVersion: number;
  jobsVersion: number;
  loading: boolean;
  error: string | null;

  refreshState: (client: HostClient) => Promise<void>;
  scheduleRefreshState: (client: HostClient) => void;
  refreshTeam: (client: HostClient) => Promise<void>;
  setTeamBot: (client: HostClient, botRef: string, enabled: boolean) => Promise<void>;
  refreshSuperSettings: (client: HostClient) => Promise<void>;
  updateSuperSettings: (
    client: HostClient,
    patch: Partial<HostSuperSettingsDto>,
  ) => Promise<HostSuperSettingsDto>;
  sendPrompt: (
    client: HostClient,
    text: string,
    steer: boolean,
    images?: Array<{ data: string; mimeType: string; name?: string }>,
  ) => Promise<void>;
  abort: (client: HostClient) => Promise<void>;
  compact: (client: HostClient) => Promise<void>;
  resetThread: (client: HostClient) => Promise<void>;
  setModel: (
    client: HostClient,
    providerId: string,
    modelId: string,
    thinkingLevel: string,
  ) => Promise<void>;
  answerQuestion: (
    client: HostClient,
    requestId: string,
    answers: QuestionAnswerInput[],
  ) => Promise<void>;
  dismissQuestion: (client: HostClient, requestId: string) => Promise<void>;
  stopWorker: (client: HostClient, workerId: string) => Promise<void>;
  deleteWorker: (client: HostClient, workerId: string) => Promise<void>;
  setSelectedWorkerId: (workerId: string | null) => void;
  setComposerText: (text: string) => void;
  refreshInbox: (client: HostClient) => Promise<void>;
  setInboxItems: (items: HostInboxItemDto[]) => void;
  bumpGoalVersion: () => void;
  bumpJobsVersion: () => void;
  reset: () => void;
}

export const useSuperStore = create<SuperStoreState>((set, get) => ({
  state: null,
  team: [],
  superSettings: null,
  inbox: [],
  unreadInboxCount: 0,
  selectedWorkerId: null,
  composerText: "",
  goalVersion: 0,
  jobsVersion: 0,
  loading: false,
  error: null,

  refreshState: async (client) => {
    if (!get().state) {
      set({ loading: true });
    }
    try {
      const next = await client.getSuperState();
      set({ state: next, loading: false, error: null });
    } catch (err) {
      set({
        loading: false,
        error: err instanceof Error ? err.message : "Failed to load Super state",
      });
    }
  },

  scheduleRefreshState: (client) => {
    if (refreshTimer !== null) {
      clearTimeout(refreshTimer);
    }
    refreshTimer = setTimeout(() => {
      refreshTimer = null;
      void get().refreshState(client);
    }, 180);
  },

  refreshTeam: async (client) => {
    const team = await client.getSuperTeam();
    set({ team });
  },

  setTeamBot: async (client, botRef, enabled) => {
    const team = await client.setSuperTeamBot(botRef, enabled);
    set({ team });
  },

  refreshSuperSettings: async (client) => {
    const superSettings = await client.getSuperSettings();
    set({ superSettings });
  },

  updateSuperSettings: async (client, patch) => {
    const superSettings = await client.updateSuperSettings(patch);
    set({ superSettings });
    return superSettings;
  },

  sendPrompt: async (client, text, steer, images = []) => {
    set({ composerText: "", error: null });
    try {
      await client.sendSuperPrompt({ text, steer, images });
      await get().refreshState(client);
    } catch (err) {
      set({
        composerText: text,
        error: err instanceof Error ? err.message : "Failed to send message",
      });
      throw err;
    }
  },

  abort: async (client) => {
    await client.abortSuper();
    await get().refreshState(client);
  },

  compact: async (client) => {
    await client.compactSuper();
    await get().refreshState(client);
  },

  resetThread: async (client) => {
    await client.resetSuper();
    await get().refreshState(client);
  },

  setModel: async (client, providerId, modelId, thinkingLevel) => {
    await client.setSuperModel(providerId, modelId, thinkingLevel);
    await get().refreshState(client);
  },

  answerQuestion: async (client, requestId, answers) => {
    await client.answerSuperQuestion(requestId, answers);
    await get().refreshState(client);
  },

  dismissQuestion: async (client, requestId) => {
    await client.dismissSuperQuestion(requestId);
    await get().refreshState(client);
  },

  stopWorker: async (client, workerId) => {
    await client.stopSuperWorker(workerId);
    await get().refreshState(client);
  },

  deleteWorker: async (client, workerId) => {
    await client.deleteSuperWorker(workerId);
    if (get().selectedWorkerId === workerId) {
      set({ selectedWorkerId: null });
    }
    await get().refreshState(client);
  },

  setSelectedWorkerId: (workerId) => set({ selectedWorkerId: workerId }),

  setComposerText: (composerText) => set({ composerText }),

  refreshInbox: async (client) => {
    try {
      const inbox = await client.listInbox();
      set({
        inbox,
        unreadInboxCount: inbox.filter((item) => item.status === "unread").length,
      });
    } catch {
      void 0;
    }
  },

  setInboxItems: (inbox) =>
    set({
      inbox,
      unreadInboxCount: inbox.filter((item) => item.status === "unread").length,
    }),

  bumpGoalVersion: () => set((s) => ({ goalVersion: s.goalVersion + 1 })),

  bumpJobsVersion: () => set((s) => ({ jobsVersion: s.jobsVersion + 1 })),

  reset: () => {
    if (refreshTimer !== null) {
      clearTimeout(refreshTimer);
      refreshTimer = null;
    }
    set({
      state: null,
      team: [],
      superSettings: null,
      inbox: [],
      unreadInboxCount: 0,
      selectedWorkerId: null,
      composerText: "",
      goalVersion: 0,
      jobsVersion: 0,
      loading: false,
      error: null,
    });
  },
}));

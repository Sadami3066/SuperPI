import { create } from "zustand";
import type { HostClient } from "../api/hostClient";
import type {
  HostAgentDocDto,
  HostBotDto,
  HostBotPolicyDto,
  HostProviderDto,
  HostSlashCommandDto,
  PermissionMode,
} from "../api/types";

const COLLAPSED_BOTS_KEY = "superpi:collapsedBots";

function loadCollapsedBots(): Record<string, boolean> {
  if (typeof window === "undefined") return {};
  try {
    const raw = window.localStorage.getItem(COLLAPSED_BOTS_KEY);
    return raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
  } catch {
    return {};
  }
}

export interface BotStoreState {
  bots: HostBotDto[];
  collapsedBotIds: Record<string, boolean>;
  commandsByBot: Record<string, HostSlashCommandDto[]>;
  remoteProvidersByBot: Record<string, HostProviderDto[]>;

  setBots: (bots: HostBotDto[]) => void;
  refreshBots: (client: HostClient) => Promise<HostBotDto[]>;
  toggleBotCollapse: (botId: string) => void;
  setBotCollapsed: (botId: string, collapsed: boolean) => void;
  loadBotCommands: (client: HostClient, botId: string) => Promise<void>;
  loadRemoteBotModels: (client: HostClient, botId: string, sessionId?: string) => Promise<void>;
  createBot: (
    client: HostClient,
    input: { dir?: string; name?: string; intro?: string; targetDeviceId?: string },
  ) => Promise<HostBotDto>;
  updateBotProfile: (
    client: HostClient,
    botId: string,
    patch: {
      name?: string;
      intro?: string;
      clearIntro?: boolean;
      avatar?: string;
      clearAvatar?: boolean;
      defaultModel?: string;
      defaultThinkingLevel?: string;
      updateDefaultModel?: boolean;
    },
  ) => Promise<void>;
  deleteBot: (client: HostClient, botId: string) => Promise<void>;
  setPermissionMode: (client: HostClient, botId: string, mode: PermissionMode) => Promise<void>;
  loadBotDetails: (
    client: HostClient,
    botId: string,
  ) => Promise<{ doc: HostAgentDocDto; policy: HostBotPolicyDto }>;
  saveAgentsDoc: (client: HostClient, botId: string, content: string) => Promise<HostAgentDocDto>;
  updateBotPolicy: (
    client: HostClient,
    botId: string,
    patch: {
      allCapabilities?: boolean;
      skills?: string[];
      mcpServers?: string[];
      memoryScope?: string;
      memoryDispatch?: boolean;
      superEnabled?: boolean;
    },
  ) => Promise<HostBotPolicyDto>;
  reset: () => void;
}

export const useBotStore = create<BotStoreState>((set, get) => ({
  bots: [],
  collapsedBotIds: loadCollapsedBots(),
  commandsByBot: {},
  remoteProvidersByBot: {},

  setBots: (bots) => set({ bots }),

  refreshBots: async (client) => {
    const bots = await client.listBots();
    set({ bots });
    return bots;
  },

  toggleBotCollapse: (botId) => {
    set((s) => {
      const next = { ...s.collapsedBotIds, [botId]: !s.collapsedBotIds[botId] };
      if (typeof window !== "undefined") {
        try {
          window.localStorage.setItem(COLLAPSED_BOTS_KEY, JSON.stringify(next));
        } catch {
          return { collapsedBotIds: next };
        }
      }
      return { collapsedBotIds: next };
    });
  },

  setBotCollapsed: (botId, collapsed) => {
    set((s) => {
      if (Boolean(s.collapsedBotIds[botId]) === collapsed) return s;
      const next = { ...s.collapsedBotIds, [botId]: collapsed };
      if (typeof window !== "undefined") {
        try {
          window.localStorage.setItem(COLLAPSED_BOTS_KEY, JSON.stringify(next));
        } catch {
          return { collapsedBotIds: next };
        }
      }
      return { collapsedBotIds: next };
    });
  },

  loadBotCommands: async (client, botId) => {
    const cmds = await client.getBotCommands(botId).catch(() => [] as HostSlashCommandDto[]);
    set((s) => ({
      commandsByBot: { ...s.commandsByBot, [botId]: cmds },
    }));
  },

  loadRemoteBotModels: async (client, botId, sessionId) => {
    const res = await client.getBotModels(botId, sessionId).catch(() => null);
    if (!res) return;
    set((s) => ({
      remoteProvidersByBot: { ...s.remoteProvidersByBot, [botId]: res.providers },
    }));
  },

  createBot: async (client, input) => {
    const created = await client.createBot(input);
    await get().refreshBots(client);
    return created;
  },

  updateBotProfile: async (client, botId, patch) => {
    const updated = await client.updateBot(botId, patch);
    set((s) => ({
      bots: s.bots.map((b) => (b.id === botId ? updated : b)),
    }));
  },

  deleteBot: async (client, botId) => {
    await client.deleteBot(botId);
    await get().refreshBots(client);
  },

  setPermissionMode: async (client, botId, mode) => {
    await client.setBotPermission(botId, mode);
    set((s) => ({
      bots: s.bots.map((b) => (b.id === botId ? { ...b, permissionMode: mode } : b)),
    }));
  },

  loadBotDetails: async (client, botId) => {
    const [doc, policy] = await Promise.all([
      client.getBotAgentsDoc(botId),
      client.getBotPolicy(botId),
    ]);
    return { doc, policy };
  },

  saveAgentsDoc: (client, botId, content) => client.saveBotAgentsDoc(botId, content),

  updateBotPolicy: (client, botId, patch) => client.updateBotPolicy(botId, patch),

  reset: () => set({ bots: [], commandsByBot: {}, remoteProvidersByBot: {} }),
}));

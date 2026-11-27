import { create } from "zustand";
import type { OpenPreviewTarget } from "../api/types";
import { browserCloseTab } from "../platform/bridge";

const WIDTH_STORAGE_KEY = "superpi:workbenchWidth";
const DEFAULT_WIDTH = 520;
const MIN_WIDTH = 360;
const MAX_WIDTH = 1080;

function clampWidth(width: number): number {
  return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Math.round(width)));
}

function readInitialWidth(): number {
  if (typeof window === "undefined") return DEFAULT_WIDTH;
  try {
    const raw = Number(window.localStorage.getItem(WIDTH_STORAGE_KEY));
    if (Number.isFinite(raw) && raw > 0) return clampWidth(raw);
  } catch {
    return DEFAULT_WIDTH;
  }
  return DEFAULT_WIDTH;
}

export function previewTargetKey(target: OpenPreviewTarget): string {
  return target.source === "workspace"
    ? `ws:${target.botId}:${target.path}`
    : `art:${target.sessionId}:${target.path}`;
}

export type FilesViewMode = "tree" | "editor";

export interface WorkbenchTab {
  id: string;
  panelId: string;
  title?: string;
  payload?: Record<string, unknown> | null;
}

let tabSeq = 1;
function createTab(panelId: string, payload?: Record<string, unknown> | null): WorkbenchTab {
  return {
    id: `wb-tab-${tabSeq++}`,
    panelId,
    payload: payload ?? null,
  };
}

function normalizePanelId(panelId: string): string {
  if (panelId === "preview" || panelId === "session-inspector") return "files";
  return panelId;
}

const MULTI_INSTANCE_PANELS = new Set(["terminal", "browser", "new-tab"]);

export interface WorkbenchStoreState {
  open: boolean;
  tabs: WorkbenchTab[];
  activeTabId: string;
  activePanelId: string;
  filesViewMode: FilesViewMode;
  width: number;
  contextPayload: Record<string, unknown> | null;
  openFiles: OpenPreviewTarget[];
  activeFileKey: string | null;
  overlayCount: number;
  activeWorktreeByBot: Record<string, string | null>;
  openPanel: (panelId: string, payload?: Record<string, unknown>) => void;
  addTab: (panelId: string, payload?: Record<string, unknown>) => void;
  replaceTab: (tabId: string, panelId: string, payload?: Record<string, unknown>) => void;
  selectTab: (tabId: string) => void;
  closeTab: (tabId: string) => void;
  updateTabTitle: (tabId: string, title: string) => void;
  togglePanel: (panelId: string) => void;
  close: () => void;
  setWidth: (width: number, persist?: boolean) => void;
  setFilesViewMode: (mode: FilesViewMode) => void;
  openFileInPreview: (target: OpenPreviewTarget) => void;
  closePreviewFile: (key: string) => void;
  setActivePreviewFile: (key: string) => void;
  pushOverlay: () => void;
  popOverlay: () => void;
  setBotWorktree: (botId: string, worktreeId: string | null) => void;
}

export const useWorkbenchStore = create<WorkbenchStoreState>((set) => ({
  open: false,
  tabs: [],
  activeTabId: "",
  activePanelId: "files",
  filesViewMode: "tree",
  width: readInitialWidth(),
  contextPayload: null,
  openFiles: [],
  activeFileKey: null,
  overlayCount: 0,
  activeWorktreeByBot: {},

  openPanel: (panelId, payload) =>
    set((s) => {
      const resolvedId = normalizePanelId(panelId);
      const nextFilesMode =
        panelId === "preview" ? (s.openFiles.length > 0 ? "editor" : "tree") : s.filesViewMode;

      const existing = s.tabs.find((t) => t.panelId === resolvedId);
      if (existing) {
        const nextTabs =
          payload !== undefined
            ? s.tabs.map((t) => (t.id === existing.id ? { ...t, payload } : t))
            : s.tabs;
        return {
          open: true,
          tabs: nextTabs,
          activeTabId: existing.id,
          activePanelId: resolvedId,
          filesViewMode: nextFilesMode,
          contextPayload: payload ?? existing.payload ?? null,
        };
      }

      const created = createTab(resolvedId, payload ?? null);
      return {
        open: true,
        tabs: [...s.tabs, created],
        activeTabId: created.id,
        activePanelId: resolvedId,
        filesViewMode: nextFilesMode,
        contextPayload: payload ?? null,
      };
    }),

  addTab: (panelId, payload) =>
    set((s) => {
      const resolvedId = normalizePanelId(panelId);
      if (!MULTI_INSTANCE_PANELS.has(resolvedId)) {
        const existing = s.tabs.find((t) => t.panelId === resolvedId);
        if (existing) {
          return {
            open: true,
            activeTabId: existing.id,
            activePanelId: resolvedId,
            contextPayload: payload ?? existing.payload ?? null,
          };
        }
      }
      const hasExistingTerm =
        resolvedId === "terminal" && s.tabs.some((t) => t.panelId === "terminal");
      const nextPayload =
        hasExistingTerm && payload?.fresh === undefined
          ? { ...(payload ?? {}), fresh: true }
          : (payload ?? null);
      const created = createTab(resolvedId, nextPayload);
      return {
        open: true,
        tabs: [...s.tabs, created],
        activeTabId: created.id,
        activePanelId: resolvedId,
        contextPayload: nextPayload,
      };
    }),

  replaceTab: (tabId, panelId, payload) =>
    set((s) => {
      const resolvedId = normalizePanelId(panelId);
      if (!MULTI_INSTANCE_PANELS.has(resolvedId)) {
        const existing = s.tabs.find((t) => t.panelId === resolvedId && t.id !== tabId);
        if (existing) {
          const filtered = s.tabs.filter((t) => t.id !== tabId);
          return {
            open: true,
            tabs: filtered,
            activeTabId: existing.id,
            activePanelId: resolvedId,
            contextPayload: payload ?? existing.payload ?? null,
          };
        }
      }
      const hasOtherTerm =
        resolvedId === "terminal" &&
        s.tabs.some((t) => t.panelId === "terminal" && t.id !== tabId);
      const nextPayload =
        hasOtherTerm && payload?.fresh === undefined
          ? { ...(payload ?? {}), fresh: true }
          : (payload ?? null);
      return {
        open: true,
        tabs: s.tabs.map((t) =>
          t.id === tabId ? { ...t, panelId: resolvedId, title: undefined, payload: nextPayload } : t,
        ),
        activeTabId: tabId,
        activePanelId: resolvedId,
        contextPayload: nextPayload,
      };
    }),

  selectTab: (tabId) =>
    set((s) => {
      const target = s.tabs.find((t) => t.id === tabId);
      if (!target) return s;
      return {
        open: true,
        activeTabId: target.id,
        activePanelId: target.panelId,
        contextPayload: target.payload ?? null,
      };
    }),

  closeTab: (tabId) =>
    set((s) => {
      const idx = s.tabs.findIndex((t) => t.id === tabId);
      if (idx < 0) return s;
      const closing = s.tabs[idx];
      if (closing?.panelId === "browser") {
        void browserCloseTab(closing.id);
      }
      const nextTabs = s.tabs.filter((t) => t.id !== tabId);
      if (nextTabs.length === 0) {
        return {
          open: false,
          tabs: [],
          activeTabId: "",
          contextPayload: null,
        };
      }
      if (s.activeTabId === tabId) {
        const neighbor = nextTabs[Math.min(idx, nextTabs.length - 1)] ?? nextTabs[0];
        return {
          tabs: nextTabs,
          activeTabId: neighbor.id,
          activePanelId: neighbor.panelId,
          contextPayload: neighbor.payload ?? null,
        };
      }
      return { tabs: nextTabs };
    }),

  updateTabTitle: (tabId, title) =>
    set((s) => {
      const cur = s.tabs.find((t) => t.id === tabId);
      if (!cur || cur.title === title) return s;
      return {
        tabs: s.tabs.map((t) => (t.id === tabId ? { ...t, title } : t)),
      };
    }),

  togglePanel: (panelId) =>
    set((s) => {
      const resolvedId = normalizePanelId(panelId);
      if (s.open && s.activePanelId === resolvedId) {
        return { open: false };
      }
      const existing = s.tabs.find((t) => t.panelId === resolvedId);
      if (existing) {
        return {
          open: true,
          activeTabId: existing.id,
          activePanelId: resolvedId,
          contextPayload: existing.payload ?? null,
        };
      }
      const created = createTab(resolvedId, null);
      return {
        open: true,
        tabs: [...s.tabs, created],
        activeTabId: created.id,
        activePanelId: resolvedId,
        contextPayload: null,
      };
    }),

  close: () => set({ open: false }),

  setWidth: (width, persist = true) => {
    const clamped = clampWidth(width);
    if (persist && typeof window !== "undefined") {
      try {
        window.localStorage.setItem(WIDTH_STORAGE_KEY, String(clamped));
      } catch {
        void 0;
      }
    }
    set({ width: clamped });
  },

  setFilesViewMode: (mode) => set({ filesViewMode: mode }),

  openFileInPreview: (target) =>
    set((s) => {
      const key = previewTargetKey(target);
      const exists = s.openFiles.some((f) => previewTargetKey(f) === key);
      const existingTab = s.tabs.find((t) => t.panelId === "files");
      const filesTab = existingTab ?? createTab("files", null);
      const nextTabs = existingTab ? s.tabs : [...s.tabs, filesTab];
      return {
        open: true,
        tabs: nextTabs,
        activeTabId: filesTab.id,
        activePanelId: "files",
        filesViewMode: "editor",
        openFiles: exists ? s.openFiles : [...s.openFiles.slice(-11), target],
        activeFileKey: key,
      };
    }),

  closePreviewFile: (key) =>
    set((s) => {
      const idx = s.openFiles.findIndex((f) => previewTargetKey(f) === key);
      if (idx < 0) return s;
      const nextFiles = s.openFiles.filter((f) => previewTargetKey(f) !== key);
      let nextActive = s.activeFileKey;
      if (s.activeFileKey === key) {
        const neighbor = nextFiles[idx] ?? nextFiles[idx - 1] ?? null;
        nextActive = neighbor ? previewTargetKey(neighbor) : null;
      }
      return {
        openFiles: nextFiles,
        activeFileKey: nextActive,
        filesViewMode: nextFiles.length === 0 ? "tree" : s.filesViewMode,
      };
    }),

  setActivePreviewFile: (key) => set({ activeFileKey: key, filesViewMode: "editor" }),

  pushOverlay: () => set((s) => ({ overlayCount: s.overlayCount + 1 })),
  popOverlay: () => set((s) => ({ overlayCount: Math.max(0, s.overlayCount - 1) })),

  setBotWorktree: (botId, worktreeId) =>
    set((s) => ({
      activeWorktreeByBot: { ...s.activeWorktreeByBot, [botId]: worktreeId },
    })),
}));

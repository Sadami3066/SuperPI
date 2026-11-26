import { create } from "zustand";
import type { OpenPreviewTarget } from "../api/types";

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

export interface WorkbenchStoreState {
  open: boolean;
  activePanelId: string;
  width: number;
  contextPayload: Record<string, unknown> | null;
  openFiles: OpenPreviewTarget[];
  activeFileKey: string | null;
  overlayCount: number;
  activeWorktreeByBot: Record<string, string | null>;
  openPanel: (panelId: string, payload?: Record<string, unknown>) => void;
  togglePanel: (panelId: string) => void;
  close: () => void;
  setWidth: (width: number, persist?: boolean) => void;
  openFileInPreview: (target: OpenPreviewTarget) => void;
  closePreviewFile: (key: string) => void;
  setActivePreviewFile: (key: string) => void;
  pushOverlay: () => void;
  popOverlay: () => void;
  setBotWorktree: (botId: string, worktreeId: string | null) => void;
}

export const useWorkbenchStore = create<WorkbenchStoreState>((set) => ({
  open: false,
  activePanelId: "files",
  width: readInitialWidth(),
  contextPayload: null,
  openFiles: [],
  activeFileKey: null,
  overlayCount: 0,
  activeWorktreeByBot: {},

  openPanel: (panelId, payload) =>
    set({
      open: true,
      activePanelId: panelId,
      contextPayload: payload ?? null,
    }),

  togglePanel: (panelId) =>
    set((s) => {
      if (s.open && s.activePanelId === panelId) {
        return { open: false };
      }
      return { open: true, activePanelId: panelId };
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

  openFileInPreview: (target) =>
    set((s) => {
      const key = previewTargetKey(target);
      const exists = s.openFiles.some((f) => previewTargetKey(f) === key);
      return {
        open: true,
        activePanelId: "preview",
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
      return { openFiles: nextFiles, activeFileKey: nextActive };
    }),

  setActivePreviewFile: (key) => set({ activeFileKey: key }),

  pushOverlay: () => set((s) => ({ overlayCount: s.overlayCount + 1 })),
  popOverlay: () => set((s) => ({ overlayCount: Math.max(0, s.overlayCount - 1) })),

  setBotWorktree: (botId, worktreeId) =>
    set((s) => ({
      activeWorktreeByBot: { ...s.activeWorktreeByBot, [botId]: worktreeId },
    })),
}));

import { create } from "zustand";

const WIDTH_STORAGE_KEY = "superpi:workbenchWidth";

function readInitialWidth(): number {
  if (typeof window === "undefined") return 360;
  try {
    const raw = Number(window.localStorage.getItem(WIDTH_STORAGE_KEY));
    if (Number.isFinite(raw)) return Math.min(720, Math.max(320, Math.round(raw)));
  } catch {
    return 360;
  }
  return 360;
}

export interface WorkbenchStoreState {
  open: boolean;
  activePanelId: string;
  width: number;
  contextPayload: Record<string, unknown> | null;
  openPanel: (panelId: string, payload?: Record<string, unknown>) => void;
  togglePanel: (panelId: string) => void;
  close: () => void;
  setWidth: (width: number) => void;
}

export const useWorkbenchStore = create<WorkbenchStoreState>((set) => ({
  open: false,
  activePanelId: "session-inspector",
  width: readInitialWidth(),
  contextPayload: null,

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

  setWidth: (width) => {
    const clamped = Math.min(720, Math.max(320, Math.round(width)));
    if (typeof window !== "undefined") {
      try {
        window.localStorage.setItem(WIDTH_STORAGE_KEY, String(clamped));
      } catch {
        void 0;
      }
    }
    set({ width: clamped });
  },
}));

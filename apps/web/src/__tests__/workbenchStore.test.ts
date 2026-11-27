import { beforeEach, describe, expect, it } from "vitest";
import { useWorkbenchStore } from "../stores/workbenchStore";

describe("workbenchStore default launcher behavior", () => {
  beforeEach(() => {
    useWorkbenchStore.setState({
      open: false,
      tabs: [],
      activeTabId: "",
      activePanelId: "new-tab",
      filesViewMode: "tree",
      contextPayload: null,
      openFiles: [],
      activeFileKey: null,
      overlayCount: 0,
      activeWorktreeByBot: {},
    });
  });

  it("opens a new-tab launcher when toggled from an empty state", () => {
    useWorkbenchStore.getState().toggleWorkbench();
    const state = useWorkbenchStore.getState();
    expect(state.open).toBe(true);
    expect(state.tabs).toHaveLength(1);
    expect(state.tabs[0]?.panelId).toBe("new-tab");
    expect(state.activePanelId).toBe("new-tab");
  });

  it("deduplicates new-tab when addTab('new-tab') is called repeatedly", () => {
    const store = useWorkbenchStore.getState();
    store.toggleWorkbench();
    const firstTabId = useWorkbenchStore.getState().activeTabId;

    store.addTab("new-tab");
    const after = useWorkbenchStore.getState();
    expect(after.tabs).toHaveLength(1);
    expect(after.activeTabId).toBe(firstTabId);
  });

  it("replaces a new-tab launcher cleanly when a panel is chosen", () => {
    const store = useWorkbenchStore.getState();
    store.toggleWorkbench();
    const tabId = useWorkbenchStore.getState().activeTabId;

    store.replaceTab(tabId, "files");
    const after = useWorkbenchStore.getState();
    expect(after.tabs).toHaveLength(1);
    expect(after.tabs[0]?.panelId).toBe("files");
    expect(after.activePanelId).toBe("files");
  });
});

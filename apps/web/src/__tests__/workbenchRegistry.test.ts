import { lazy } from "react";
import { describe, expect, it } from "vitest";
import {
  getAvailableWorkbenchPanels,
  registerWorkbenchPanel,
  unregisterWorkbenchPanel,
} from "../workbench/registry";

const StubComponent = lazy(async () => ({
  default: () => null,
}));

const StubIcon = () => null;

describe("workbenchRegistry", () => {
  it("sorts panels by order and filters electron-only or unavailable panels", () => {
    registerWorkbenchPanel({
      id: "test-browser",
      labelKey: "wb.inspectorTitle",
      icon: StubIcon,
      order: 20,
      requiresElectron: true,
      Component: StubComponent,
    });
    registerWorkbenchPanel({
      id: "test-files",
      labelKey: "wb.inspectorTitle",
      icon: StubIcon,
      order: 5,
      isAvailable: (ctx) => ctx.botId !== "blocked",
      Component: StubComponent,
    });

    try {
      const electronPanels = getAvailableWorkbenchPanels(
        { botId: "home", sessionId: "s1" },
        true,
      );
      expect(electronPanels.map((p) => p.id)).toContain("test-files");
      expect(electronPanels.map((p) => p.id)).toContain("test-browser");
      expect(electronPanels.findIndex((p) => p.id === "test-files")).toBeLessThan(
        electronPanels.findIndex((p) => p.id === "test-browser"),
      );

      const browserPanels = getAvailableWorkbenchPanels(
        { botId: "blocked", sessionId: "s1" },
        false,
      );
      expect(browserPanels.map((p) => p.id)).not.toContain("test-browser");
      expect(browserPanels.map((p) => p.id)).not.toContain("test-files");
    } finally {
      unregisterWorkbenchPanel("test-browser");
      unregisterWorkbenchPanel("test-files");
    }
  });
});

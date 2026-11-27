import { Component, lazy, type ReactNode, useEffect, useMemo } from "react";
import {
  IconFolder,
  IconGitBranch,
  IconTerminal2,
  IconUsersGroup,
  IconWorld,
} from "@tabler/icons-react";
import { AppShell } from "./features/shell/AppShell";
import { ConnectionScreen } from "./features/shell/ConnectionScreen";
import { I18nContext, translate } from "./i18n";
import { useConnectionStore } from "./stores/connectionStore";
import { useSettingsStore } from "./stores/settingsStore";
import { Button, TooltipProvider } from "./ui";
import { registerWorkbenchPanel } from "./workbench/registry";

registerWorkbenchPanel({
  id: "super-team",
  labelKey: "wb.superTeamTitle",
  icon: IconUsersGroup,
  order: 5,
  isAvailable: (ctx) => ctx.activeView === "super",
  Component: lazy(() => import("./workbench/SuperTeamPanel")),
});

registerWorkbenchPanel({
  id: "files",
  labelKey: "wb.filesTitle",
  icon: IconFolder,
  order: 10,
  Component: lazy(() => import("./workbench/FilesWorkspacePanel")),
});

registerWorkbenchPanel({
  id: "diff",
  labelKey: "wb.diffTitle",
  icon: IconGitBranch,
  order: 20,
  Component: lazy(() => import("./workbench/DiffWorktreePanel")),
});

registerWorkbenchPanel({
  id: "terminal",
  labelKey: "wb.terminalTitle",
  icon: IconTerminal2,
  order: 30,
  Component: lazy(() => import("./workbench/TerminalPanel")),
});

registerWorkbenchPanel({
  id: "browser",
  labelKey: "wb.browserTitle",
  icon: IconWorld,
  order: 40,
  requiresElectron: true,
  Component: lazy(() => import("./workbench/BrowserPanel")),
});

class AppErrorBoundary extends Component<
  { children: ReactNode },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error): { error: Error } {
    return { error };
  }

  render() {
    if (this.state.error) {
      return (
        <div
          className="flex h-screen w-screen items-center justify-center p-6"
          style={{ background: "var(--pi-surface)", color: "var(--pi-ink)" }}
        >
          <div
            className="w-full max-w-[440px] space-y-3 rounded-[16px] p-6"
            style={{
              background: "var(--pi-paper)",
              border: "0.5px solid var(--pi-hairline)",
            }}
          >
            <div className="text-[15px] font-semibold" style={{ color: "var(--pi-danger)" }}>
              UI Render Error
            </div>
            <div
              className="max-h-36 overflow-auto rounded-[8px] px-3 py-2 text-[12px]"
              style={{
                background: "var(--pi-surface)",
                color: "var(--pi-ink-secondary)",
                fontFamily: "var(--pi-font-mono)",
              }}
            >
              {this.state.error.message}
            </div>
            <div className="flex justify-end">
              <Button variant="primary" size="sm" onClick={() => window.location.reload()}>
                Reload
              </Button>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}

export default function App() {
  const status = useConnectionStore((s) => s.status);
  const error = useConnectionStore((s) => s.error);
  const bootstrap = useConnectionStore((s) => s.bootstrap);
  const reconnect = useConnectionStore((s) => s.reconnect);
  const disconnect = useConnectionStore((s) => s.disconnect);
  const submitBrowserToken = useConnectionStore((s) => s.submitBrowserToken);

  const locale = useSettingsStore((s) => s.locale);
  const dark = useSettingsStore((s) => s.dark);
  const appearance = useSettingsStore((s) => s.appearance);
  const applyLocalTheme = useSettingsStore((s) => s.applyLocalTheme);

  useEffect(() => {
    applyLocalTheme();
    if (appearance !== "system" || typeof window === "undefined" || !window.matchMedia) {
      return undefined;
    }
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const listener = () => applyLocalTheme();
    mq.addEventListener?.("change", listener);
    return () => mq.removeEventListener?.("change", listener);
  }, [appearance, applyLocalTheme]);

  useEffect(() => {
    void bootstrap();
    return () => disconnect();
  }, [bootstrap, disconnect]);

  const i18nValue = useMemo(
    () => ({
      locale,
      t: (key: Parameters<typeof translate>[1], params?: Record<string, string | number>) =>
        translate(locale, key, params),
    }),
    [locale],
  );

  return (
    <AppErrorBoundary>
      <TooltipProvider>
        <I18nContext.Provider value={i18nValue}>
          {status === "live" ? (
            <AppShell />
          ) : (
            <ConnectionScreen
              phase={status}
              dark={dark}
              error={error}
              onRetry={() => void reconnect()}
              onSubmitToken={(tok) => void submitBrowserToken(tok)}
            />
          )}
        </I18nContext.Provider>
      </TooltipProvider>
    </AppErrorBoundary>
  );
}

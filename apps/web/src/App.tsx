import { lazy, useEffect, useMemo } from "react";
import { IconInfoCircle } from "@tabler/icons-react";
import { AppShell } from "./features/shell/AppShell";
import { ConnectionScreen } from "./features/shell/ConnectionScreen";
import { I18nContext, translate } from "./i18n";
import { useConnectionStore } from "./stores/connectionStore";
import { useSettingsStore } from "./stores/settingsStore";
import { TooltipProvider } from "./ui";
import { registerWorkbenchPanel } from "./workbench/registry";

registerWorkbenchPanel({
  id: "session-inspector",
  labelKey: "wb.inspectorTitle",
  icon: IconInfoCircle,
  order: 10,
  Component: lazy(() => import("./workbench/SessionInspectorPanel")),
});

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
  );
}

import { Suspense, useEffect, useRef } from "react";
import { IconX } from "@tabler/icons-react";
import { useI18n } from "../i18n";
import { windowToggleMaximize } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { IconButton } from "../ui";
import { WindowControls } from "../features/shell/WindowControls";
import { getAvailableWorkbenchPanels, getWorkbenchPanel } from "./registry";

export function WorkbenchDock({
  showWindowControls = false,
}: {
  showWindowControls?: boolean;
}) {
  const { t } = useI18n();
  const open = useWorkbenchStore((s) => s.open);
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const width = useWorkbenchStore((s) => s.width);
  const payload = useWorkbenchStore((s) => s.contextPayload);
  const openPanel = useWorkbenchStore((s) => s.openPanel);
  const close = useWorkbenchStore((s) => s.close);
  const setWidth = useWorkbenchStore((s) => s.setWidth);

  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const activeSummary = useSessionStore((s) => s.summariesById[s.activeSessionId]);
  const bots = useBotStore((s) => s.bots);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isElectron = Boolean(desktopEnv?.isElectron);
  const isFrameless = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");

  const dragCleanupRef = useRef<(() => void) | null>(null);
  useEffect(() => {
    return () => {
      dragCleanupRef.current?.();
    };
  }, []);

  if (!open) return null;

  const botId = activeSummary?.botId ?? bots[0]?.id ?? "home";
  const ctx = { botId, sessionId: activeSessionId || null, payload };
  const available = getAvailableWorkbenchPanels(ctx, isElectron);
  const resolvedPanel = getWorkbenchPanel(activePanelId) ?? available[0] ?? null;

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = width;
    const onMove = (ev: MouseEvent) => {
      setWidth(startWidth + (startX - ev.clientX));
    };
    const cleanup = () => {
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", cleanup);
      dragCleanupRef.current = null;
    };
    dragCleanupRef.current = cleanup;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", cleanup);
  };

  return (
    <aside
      className="relative flex h-full shrink-0 flex-col"
      style={{
        width,
        background: "var(--pi-paper)",
        borderLeft: "0.5px solid var(--pi-hairline)",
      }}
    >
      <div
        onMouseDown={startResize}
        role="separator"
        aria-orientation="vertical"
        className="absolute top-0 bottom-0 left-0 z-10 w-1 cursor-col-resize transition-colors hover:bg-[var(--pi-accent)]/35"
      />

      <div
        className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between gap-2 pl-3"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 10,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
        onDoubleClick={() => {
          if (isFrameless) void windowToggleMaximize();
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-1 overflow-x-auto">
          {available.map((panel) => {
            const Icon = panel.icon;
            const active = resolvedPanel?.id === panel.id;
            return (
              <button
                key={panel.id}
                type="button"
                onClick={() => openPanel(panel.id)}
                className="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-[7px] px-2.5 text-[12px] font-medium transition-colors"
                style={{
                  background: active ? "var(--pi-wash)" : "transparent",
                  color: active ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                <Icon size={13.5} stroke={1.7} />
                <span>{t(panel.labelKey)}</span>
              </button>
            );
          })}
        </div>

        <div className="pi-no-drag flex shrink-0 items-center gap-0.5">
          <IconButton label={t("common.close")} size="sm" onClick={close}>
            <IconX size={14.5} stroke={1.75} />
          </IconButton>
          {showWindowControls && <WindowControls />}
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-4">
        {resolvedPanel && (
          <Suspense
            fallback={
              <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("common.loading")}
              </div>
            }
          >
            <resolvedPanel.Component context={ctx} />
          </Suspense>
        )}
      </div>
    </aside>
  );
}

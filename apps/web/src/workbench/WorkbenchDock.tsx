import { Suspense, useEffect, useRef, useState } from "react";
import { useI18n } from "../i18n";
import { windowToggleMaximize } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { IconButton } from "../ui";
import { BrandIcon } from "../features/shared/BrandAssets";
import { WindowControls } from "../features/shell/WindowControls";
import { getAvailableWorkbenchPanels, getWorkbenchPanel } from "./registry";

const TAB_BRAND_ICONS: Record<string, string> = {
  files: "file/folder_closed",
  diff: "system/git_branch",
  terminal: "system/terminal",
};

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

  const [resizing, setResizing] = useState(false);
  const hasOpenedRef = useRef(open);
  if (open) hasOpenedRef.current = true;

  const dragCleanupRef = useRef<(() => void) | null>(null);
  useEffect(() => {
    return () => {
      dragCleanupRef.current?.();
    };
  }, []);

  if (!hasOpenedRef.current) return null;

  const botId = activeSummary?.botId ?? bots[0]?.id ?? "home";
  const ctx = { botId, sessionId: activeSessionId || null, payload };
  const available = getAvailableWorkbenchPanels(ctx, isElectron);
  const resolvedPanel = getWorkbenchPanel(activePanelId) ?? available[0] ?? null;

  const clampDynamicWidth = (raw: number) => {
    const maxW = typeof window !== "undefined" ? Math.min(720, Math.max(360, window.innerWidth - 420)) : 720;
    return Math.min(maxW, Math.max(360, Math.round(raw)));
  };
  const effectiveWidth = clampDynamicWidth(width);
  const compactTabs = effectiveWidth < 440;

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = width;
    let latestWidth = startWidth;
    let moveRaf: number | null = null;
    setResizing(true);
    const onMove = (ev: MouseEvent) => {
      latestWidth = clampDynamicWidth(startWidth + (startX - ev.clientX));
      if (moveRaf !== null) return;
      moveRaf = requestAnimationFrame(() => {
        moveRaf = null;
        setWidth(latestWidth, false);
      });
    };
    const cleanup = () => {
      if (moveRaf !== null) {
        cancelAnimationFrame(moveRaf);
        moveRaf = null;
      }
      setResizing(false);
      setWidth(clampDynamicWidth(latestWidth), true);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", cleanup);
      dragCleanupRef.current = null;
    };
    dragCleanupRef.current = cleanup;
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", cleanup);
  };

  return (
    <>
      {resizing && <div className="fixed inset-0 z-40 cursor-col-resize" />}
      <aside
        className="relative h-full shrink-0 flex-col"
        style={{
          display: open ? "flex" : "none",
          width: effectiveWidth,
          background: "var(--pi-paper)",
          borderLeft: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div
          onMouseDown={startResize}
          onDoubleClick={() => setWidth(520, true)}
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
        <div
          className="pi-no-drag flex min-w-0 items-center gap-0.5 rounded-[9px] p-0.5"
          style={{ background: "var(--pi-wash)" }}
        >
          {available.map((panel) => {
            const Icon = panel.icon;
            const brandIcon = TAB_BRAND_ICONS[panel.id];
            const active = resolvedPanel?.id === panel.id;
            const label = t(panel.labelKey);
            return (
              <button
                key={panel.id}
                type="button"
                title={label}
                aria-label={label}
                onClick={() => openPanel(panel.id)}
                className="inline-flex h-7 shrink-0 items-center gap-1.5 rounded-[7px] px-2.5 text-[12px] font-medium transition-colors"
                style={{
                  background: active ? "var(--pi-surface)" : "transparent",
                  color: active ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                {brandIcon ? (
                  <BrandIcon name={brandIcon} size={13.5} />
                ) : (
                  <Icon size={13.5} stroke={1.7} />
                )}
                {!compactTabs && <span>{label}</span>}
              </button>
            );
          })}
        </div>

        <div className="pi-no-drag flex shrink-0 items-center gap-0.5">
          <IconButton label={t("common.close")} size="sm" onClick={close}>
            <BrandIcon name="navigation/close" size={14.5} />
          </IconButton>
          {showWindowControls && <WindowControls />}
        </div>
      </div>

      <div className="flex min-h-0 flex-1 flex-col overflow-hidden">
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
    </>
  );
}

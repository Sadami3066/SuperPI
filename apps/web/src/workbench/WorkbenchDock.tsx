import { Suspense, useEffect, useRef, useState } from "react";
import {
  IconLayoutGrid,
  IconLayoutSidebarRightCollapse,
  IconPlus,
  IconSearch,
  IconX,
} from "@tabler/icons-react";
import { useI18n } from "../i18n";
import { subscribeBrowserOpenTabRequest } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { IconButton, Input } from "../ui";
import { BrandIcon } from "../features/shared/BrandAssets";
import {
  getAvailableWorkbenchPanels,
  getWorkbenchPanel,
  type WorkbenchPanelDefinition,
} from "./registry";

const TAB_BRAND_ICONS: Record<string, string> = {
  files: "file/folder_closed",
  diff: "system/git_branch",
  terminal: "system/terminal",
};

function NewTabLauncher({
  tabId,
  available,
  isElectron,
  onSelectPanel,
  onOpenUrl,
}: {
  tabId: string;
  available: WorkbenchPanelDefinition[];
  isElectron: boolean;
  onSelectPanel: (panelId: string) => void;
  onOpenUrl: (url: string) => void;
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) return;
    if (isElectron) {
      onOpenUrl(trimmed);
    } else {
      onSelectPanel("files");
    }
  };

  return (
    <div
      key={tabId}
      className="flex h-full min-h-0 flex-1 flex-col items-center justify-center px-6 py-8"
      style={{ background: "var(--pi-surface)" }}
    >
      <div className="w-full max-w-[380px] space-y-6">
        {isElectron && (
          <form onSubmit={handleSubmit} className="relative">
            <IconSearch
              size={15}
              stroke={1.7}
              className="pointer-events-none absolute top-1/2 left-3.5 -translate-y-1/2"
              style={{ color: "var(--pi-ink-tertiary)" }}
            />
            <Input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("wb.newTabSearchPlaceholder")}
              className="h-10 rounded-full pl-9 text-[13px]"
            />
          </form>
        )}

        <div className="space-y-2.5">
          <div className="px-1 text-[12px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("wb.quickLaunch")}
          </div>
          <div className="grid grid-cols-2 gap-2.5">
            {available.map((panel) => {
              const Icon = panel.icon;
              const brandIcon = TAB_BRAND_ICONS[panel.id];
              return (
                <button
                  key={panel.id}
                  type="button"
                  onClick={() => onSelectPanel(panel.id)}
                  className="flex flex-col items-start gap-2.5 rounded-[14px] p-4 text-left transition-[background-color,border-color,transform] duration-150 hover:bg-[var(--pi-hover)] motion-safe:active:scale-[0.98]"
                  style={{
                    background: "var(--pi-chrome)",
                    border: "1px solid var(--pi-canvas-border)",
                    color: "var(--pi-ink)",
                  }}
                >
                  <span
                    className="flex h-7 w-7 items-center justify-center rounded-[8px]"
                    style={{
                      background: "var(--pi-canvas)",
                      color: "var(--pi-ink-secondary)",
                    }}
                  >
                    {brandIcon ? (
                      <BrandIcon name={brandIcon} size={15} />
                    ) : (
                      <Icon size={15.5} stroke={1.7} />
                    )}
                  </span>
                  <span className="text-[13px] font-medium">{t(panel.labelKey)}</span>
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

export function WorkbenchDock({
  activeView = "chat",
  sidebarOpen = true,
}: {
  activeView?: string;
  sidebarOpen?: boolean;
}) {
  const { t } = useI18n();
  const open = useWorkbenchStore((s) => s.open);
  const tabs = useWorkbenchStore((s) => s.tabs);
  const activeTabId = useWorkbenchStore((s) => s.activeTabId);
  const width = useWorkbenchStore((s) => s.width);
  const addTab = useWorkbenchStore((s) => s.addTab);
  const replaceTab = useWorkbenchStore((s) => s.replaceTab);
  const selectTab = useWorkbenchStore((s) => s.selectTab);
  const closeTab = useWorkbenchStore((s) => s.closeTab);
  const close = useWorkbenchStore((s) => s.close);
  const setWidth = useWorkbenchStore((s) => s.setWidth);

  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const activeSummary = useSessionStore((s) => s.summariesById[s.activeSessionId]);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const bots = useBotStore((s) => s.bots);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isElectron = Boolean(desktopEnv?.isElectron);

  const [resizing, setResizing] = useState(false);
  const [viewportWidth, setViewportWidth] = useState(() =>
    typeof window !== "undefined" ? window.innerWidth : 1360,
  );
  const hasOpenedRef = useRef(open);
  if (open) hasOpenedRef.current = true;

  useEffect(() => {
    if (typeof window === "undefined") return undefined;
    const onResize = () => setViewportWidth(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const dragCleanupRef = useRef<(() => void) | null>(null);
  useEffect(() => {
    return () => {
      dragCleanupRef.current?.();
    };
  }, []);

  useEffect(() => {
    if (!isElectron) return undefined;
    return subscribeBrowserOpenTabRequest((url) => {
      useWorkbenchStore.getState().addTab("browser", { url });
    });
  }, [isElectron]);

  if (!hasOpenedRef.current) return null;

  const botId = activeSummary?.botId ?? draftBotId ?? bots[0]?.id ?? "home";
  const activeBot = bots.find((b) => b.id === botId);
  const baseCtx = {
    botId,
    sessionId: activeSessionId || null,
    isRemote: Boolean(activeBot?.isRemote),
    activeView,
  };
  const available = getAvailableWorkbenchPanels(baseCtx, isElectron);
  const availableIds = new Set(available.map((p) => p.id));

  const visibleTabs = tabs.filter(
    (tab) => tab.panelId === "new-tab" || availableIds.has(tab.panelId),
  );
  const resolvedActiveTab =
    visibleTabs.find((tab) => tab.id === activeTabId) ?? visibleTabs[0] ?? null;

  const clampDynamicWidth = (raw: number) => {
    const curViewport = typeof window !== "undefined" ? window.innerWidth : viewportWidth;
    const sidebarReserve = sidebarOpen ? 268 : 64;
    const available = Math.max(320, curViewport - sidebarReserve - 400);
    const maxW = Math.min(720, available);
    const minW = Math.min(360, maxW);
    return Math.min(maxW, Math.max(minW, Math.round(raw)));
  };
  const effectiveWidth = clampDynamicWidth(width);

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

  let terminalIndex = 0;
  const terminalTotal = visibleTabs.filter((x) => x.panelId === "terminal").length;

  return (
    <>
      {resizing && <div className="fixed inset-0 z-40 cursor-col-resize" />}
      <aside
        className="pi-inset-canvas relative h-full shrink-0 flex-col overflow-hidden sm:rounded-[14px]"
        style={{
          display: open ? "flex" : "none",
          width: effectiveWidth,
        }}
      >
        <div
          onMouseDown={startResize}
          onDoubleClick={() => setWidth(520, true)}
          role="separator"
          aria-orientation="vertical"
          className="absolute top-0 bottom-0 left-0 z-10 w-1.5 cursor-col-resize transition-colors hover:bg-[var(--pi-accent)]/35"
        />

        <div
          className="flex h-[44px] shrink-0 items-center justify-between gap-2 px-2.5"
          style={{
            background: "var(--pi-chrome)",
            borderBottom: "1px solid var(--pi-canvas-border)",
          }}
        >
          <div className="pi-scroll-fade-x flex min-w-0 flex-1 items-center gap-1 py-1.5 pr-1">
            {visibleTabs.map((tab) => {
              const isNewTab = tab.panelId === "new-tab";
              const panel = isNewTab ? null : getWorkbenchPanel(tab.panelId);
              if (!isNewTab && !panel) return null;
              const Icon = panel?.icon;
              const brandIcon = panel ? TAB_BRAND_ICONS[panel.id] : undefined;
              const active = resolvedActiveTab?.id === tab.id;
              if (tab.panelId === "terminal") terminalIndex += 1;
              const baseLabel = isNewTab ? t("wb.newTabTitle") : t(panel!.labelKey);
              const label =
                tab.title ||
                (tab.panelId === "terminal" && terminalTotal > 1
                  ? `${baseLabel} ${terminalIndex}`
                  : baseLabel);
              const hideCloseBtn = visibleTabs.length === 1 && isNewTab;

              return (
                <div
                  key={tab.id}
                  onMouseDown={(e) => {
                    if (e.button === 1 && !hideCloseBtn) {
                      e.preventDefault();
                      closeTab(tab.id);
                    }
                  }}
                  className="group flex h-[28px] max-w-[172px] shrink-0 items-center gap-1.5 rounded-[9px] px-2 text-[12px] font-medium transition-[background-color,border-color,color,box-shadow] duration-150"
                  style={{
                    background: active ? "var(--pi-canvas)" : "transparent",
                    color: active ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                    border: active ? "1px solid var(--pi-canvas-border)" : "1px solid transparent",
                    boxShadow: active ? "0 1px 2px rgba(0, 0, 0, 0.04)" : "none",
                  }}
                >
                  <button
                    type="button"
                    title={label}
                    onClick={() => selectTab(tab.id)}
                    className="flex min-w-0 flex-1 items-center gap-1.5 whitespace-nowrap"
                  >
                    {isNewTab ? (
                      <IconLayoutGrid size={13} stroke={1.7} className="shrink-0 opacity-75" />
                    ) : brandIcon ? (
                      <BrandIcon name={brandIcon} size={13.5} className="shrink-0" />
                    ) : (
                      Icon && <Icon size={13.5} stroke={1.7} />
                    )}
                    <span className="truncate">{label}</span>
                  </button>
                  {!hideCloseBtn && (
                    <button
                      type="button"
                      aria-label={t("common.close")}
                      onClick={(e) => {
                        e.stopPropagation();
                        closeTab(tab.id);
                      }}
                      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded transition-opacity hover:bg-[var(--pi-hover)] ${
                        active ? "opacity-65 hover:opacity-100" : "pi-hover-reveal"
                      }`}
                    >
                      <IconX size={11} stroke={1.8} />
                    </button>
                  )}
                </div>
              );
            })}

            <IconButton
              size="sm"
              label={t("wb.addTab")}
              className="shrink-0"
              onClick={() => addTab("new-tab")}
            >
              <IconPlus size={14} stroke={1.8} />
            </IconButton>
          </div>

          <div className="flex shrink-0 items-center gap-0.5">
            <IconButton label={t("wb.collapse")} size="sm" onClick={close}>
              <IconLayoutSidebarRightCollapse size={15.5} stroke={1.65} />
            </IconButton>
          </div>
        </div>

        <div className="relative flex min-h-0 flex-1 flex-col overflow-hidden">
          {visibleTabs.map((tab) => {
            const isActive = resolvedActiveTab?.id === tab.id;
            if (tab.panelId === "new-tab") {
              return (
                <div
                  key={tab.id}
                  className="min-h-0 flex-1 flex-col overflow-hidden"
                  style={{ display: isActive ? "flex" : "none" }}
                >
                  <NewTabLauncher
                    tabId={tab.id}
                    available={available}
                    isElectron={isElectron}
                    onSelectPanel={(panelId) => replaceTab(tab.id, panelId)}
                    onOpenUrl={(url) => replaceTab(tab.id, "browser", { url })}
                  />
                </div>
              );
            }
            const panel = getWorkbenchPanel(tab.panelId);
            if (!panel) return null;
            const ctx = {
              ...baseCtx,
              tabId: tab.id,
              isActiveTab: open && isActive,
              payload: tab.payload ?? null,
            };
            return (
              <div
                key={tab.id}
                className="min-h-0 flex-1 flex-col overflow-hidden"
                style={{ display: isActive ? "flex" : "none" }}
              >
                <Suspense
                  fallback={
                    <div
                      className="py-8 text-center text-[13px]"
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    >
                      {t("common.loading")}
                    </div>
                  }
                >
                  <panel.Component context={ctx} />
                </Suspense>
              </div>
            );
          })}
        </div>
      </aside>
    </>
  );
}

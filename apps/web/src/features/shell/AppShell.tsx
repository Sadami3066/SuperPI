import { useCallback, useEffect, useState } from "react";
import type { HostBotDto } from "../../api/types";
import { useI18n } from "../../i18n";
import {
  consumePendingDesktopNavigateCommand,
  onDesktopNavigateCommand,
  type DesktopNavigateCommand,
  updateDesktopTrayState,
} from "../../platform/bridge";
import { isShortcutBlockedByTarget } from "../../platform/shortcuts";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { useSuperStore } from "../../stores/superStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, NoticeBar } from "../../ui";
import { WorkbenchDock } from "../../workbench/WorkbenchDock";
import { BotSettingsDialog } from "../bots/BotSettingsDialog";
import { CreateBotDialog } from "../bots/CreateBotDialog";
import { ChatWorkspace } from "../chat/ChatWorkspace";
import { SettingsModal, type SettingsTabId } from "../settings/SettingsModal";
import { SuperTeamDialog } from "../super/SuperTeamDialog";
import { SuperWorkspace } from "../super/SuperWorkspace";
import { WorkerWorkspace } from "../super/WorkerWorkspace";
import { CommandPalette } from "./CommandPalette";
import { DesktopTitlebar } from "./DesktopTitlebar";
import { type FeatureViewKind, FeatureWorkspaceView } from "./FeatureWorkspaceView";
import { Sidebar } from "./Sidebar";

export type AppMode = "pi" | "super";
export type MainViewId = "chat" | "super" | FeatureViewKind;

export function AppShell() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const sseLost = useConnectionStore((s) => s.sseLost);
  const error = useConnectionStore((s) => s.error);
  const reconnect = useConnectionStore((s) => s.reconnect);

  const bots = useBotStore((s) => s.bots);
  const startDraftSession = useSessionStore((s) => s.startDraftSession);
  const selectSession = useSessionStore((s) => s.selectSession);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const actionError = useSessionStore((s) => s.actionError);
  const clearActionError = useSessionStore((s) => s.clearActionError);
  const pendingDeletes = useSessionStore((s) => s.pendingDeletes);
  const undoDeleteSession = useSessionStore((s) => s.undoDeleteSession);
  const flushPendingDeletes = useSessionStore((s) => s.flushPendingDeletes);
  const loadArchivedSessions = useSessionStore((s) => s.loadArchivedSessions);

  const refreshSuperState = useSuperStore((s) => s.refreshState);
  const refreshSuperTeam = useSuperStore((s) => s.refreshTeam);
  const selectedWorkerId = useSuperStore((s) => s.selectedWorkerId);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);

  useEffect(() => {
    const onUnload = () => {
      void flushPendingDeletes();
    };
    window.addEventListener("beforeunload", onUnload);
    return () => window.removeEventListener("beforeunload", onUnload);
  }, [flushPendingDeletes]);

  const locale = useSettingsStore((s) => s.locale);
  const refreshSettingsData = useSettingsStore((s) => s.refreshSettingsData);
  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const togglePanel = useWorkbenchStore((s) => s.togglePanel);
  const toggleWorkbench = useWorkbenchStore((s) => s.toggleWorkbench);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));

  useEffect(() => {
    document.documentElement.classList.toggle("pi-electron", isElectron);
    return () => {
      document.documentElement.classList.remove("pi-electron");
    };
  }, [isElectron]);

  const [appMode, setAppMode] = useState<AppMode>("pi");
  const [activeView, setActiveView] = useState<MainViewId>("chat");
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsTab, setSettingsTab] = useState<SettingsTabId>("general");
  const [autoCheckUpdateTick, setAutoCheckUpdateTick] = useState(0);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const [createBotTargetDevice, setCreateBotTargetDevice] = useState<{
    deviceId: string;
    deviceName: string;
  } | null>(null);
  const [settingsBot, setSettingsBot] = useState<HostBotDto | null>(null);
  const [superTeamDialogOpen, setSuperTeamDialogOpen] = useState(false);

  const activeSummary = useSessionStore((s) => s.summariesById[s.activeSessionId]);
  const summariesById = useSessionStore((s) => s.summariesById);
  const activeRunsBySession = useStreamStore((s) => s.activeBySession);

  const switchAppMode = useCallback(
    (nextMode: AppMode) => {
      setAppMode(nextMode);
      setActiveView(nextMode === "super" ? "super" : "chat");
      if (nextMode === "super") {
        setSelectedWorkerId(null);
        if (client) {
          void refreshSuperState(client);
          void refreshSuperTeam(client);
        }
      }
    },
    [client, refreshSuperState, refreshSuperTeam, setSelectedWorkerId],
  );

  const selectMainView = useCallback(
    (next: MainViewId) => {
      if (next === "super") {
        setAppMode("super");
        if (client) {
          void refreshSuperState(client);
          void refreshSuperTeam(client);
        }
      } else if (next === "chat") {
        setAppMode("pi");
      }
      setActiveView(next);
    },
    [client, refreshSuperState, refreshSuperTeam],
  );

  useEffect(() => {
    if (activeView === "super") {
      document.title = `Super PI — ${t("app.name")}`;
      return;
    }
    if (activeView === "timers") {
      document.title = `${appMode === "super" ? t("auto.superTimersTitle") : t("auto.piTimersTitle")} — Super PI`;
      return;
    }
    if (activeView === "memory") {
      document.title = `${t("settings.navMemory")} — Super PI`;
      return;
    }
    if (activeView === "skills") {
      document.title = `${t("nav.skills")} — Super PI`;
      return;
    }
    if (activeView === "mcp") {
      document.title = `${t("nav.mcp")} — Super PI`;
      return;
    }
    const activeBot = bots.find((b) => b.id === (activeSummary?.botId ?? draftBotId ?? bots[0]?.id));
    const titlePart = activeSummary?.title || activeBot?.name;
    document.title = titlePart ? `${titlePart} — Super PI` : "Super PI";
  }, [activeSummary?.botId, activeSummary?.title, activeView, appMode, bots, draftBotId, t]);

  const openSettings = useCallback(
    (tab: SettingsTabId = "general") => {
      setSettingsTab(tab);
      setSettingsOpen(true);
      if (client) {
        void refreshSettingsData(client);
        if (tab === "archived") {
          void loadArchivedSessions(client);
        }
      }
    },
    [client, loadArchivedSessions, refreshSettingsData],
  );

  const handleNavigateCommand = useCallback(
    (cmd: DesktopNavigateCommand) => {
      if (cmd.kind === "chat") {
        setSettingsOpen(false);
        selectMainView("chat");
        if (client) {
          void selectSession(client, cmd.sessionId, locale);
        }
        return;
      }
      if (cmd.kind === "super") {
        setSettingsOpen(false);
        switchAppMode("super");
        return;
      }
      if (cmd.kind === "new_chat") {
        setSettingsOpen(false);
        selectMainView("chat");
        const targetBot =
          bots.find((b) => !b.isRemote || b.online)?.id ?? bots[0]?.id;
        if (targetBot) startDraftSession(targetBot);
        return;
      }
      if (cmd.kind === "feature") {
        setSettingsOpen(false);
        const mapped: FeatureViewKind = cmd.view === "inbox" ? "timers" : cmd.view;
        selectMainView(mapped);
        return;
      }
      if (cmd.kind === "settings") {
        openSettings((cmd.tab as SettingsTabId) || "general");
        if (cmd.autoCheckUpdate) {
          setAutoCheckUpdateTick((n) => n + 1);
        }
      }
    },
    [bots, client, locale, openSettings, selectMainView, selectSession, startDraftSession, switchAppMode],
  );

  useEffect(() => {
    if (!isElectron) return undefined;
    let cancelled = false;
    void consumePendingDesktopNavigateCommand().then((cmd) => {
      if (!cancelled && cmd) {
        handleNavigateCommand(cmd);
      }
    });
    const unsubscribe = onDesktopNavigateCommand(handleNavigateCommand);
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [handleNavigateCommand, isElectron]);

  useEffect(() => {
    if (!isElectron) return undefined;
    const timer = setTimeout(() => {
      const runningIds = new Set<string>();
      for (const [sid, sum] of Object.entries(summariesById)) {
        if (sum.busy) runningIds.add(sid);
      }
      for (const [sid, run] of Object.entries(activeRunsBySession)) {
        if (run.streaming) runningIds.add(sid);
      }
      const recentSessions = Object.values(summariesById)
        .filter((s) => !s.archived)
        .toSorted((a, b) => b.updatedAtMs - a.updatedAtMs)
        .slice(0, 5)
        .map((s) => ({ id: s.id, title: s.title }));
      void updateDesktopTrayState({
        activeRunsCount: runningIds.size,
        recentSessions,
      });
    }, 300);
    return () => clearTimeout(timer);
  }, [activeRunsBySession, isElectron, summariesById]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && !e.metaKey && !e.altKey && e.key === "Tab") {
        const state = useSessionStore.getState();
        const ordered = Object.values(state.summariesById)
          .filter((s) => !s.archived)
          .toSorted((a, b) => b.updatedAtMs - a.updatedAtMs);
        if (ordered.length > 1 && client) {
          e.preventDefault();
          const curIdx = ordered.findIndex((s) => s.id === state.activeSessionId);
          const step = e.shiftKey ? -1 : 1;
          const next = ordered[(curIdx + step + ordered.length) % ordered.length];
          if (next) {
            selectMainView("chat");
            void state.selectSession(client, next.id, locale);
          }
        }
        return;
      }

      if (e.altKey && !e.metaKey && !e.ctrlKey && e.key.toLowerCase() === "s") {
        if (isShortcutBlockedByTarget(e.target, { hasMod: false })) return;
        e.preventDefault();
        setSettingsOpen(false);
        switchAppMode(appMode === "super" ? "pi" : "super");
        return;
      }

      const mod = e.metaKey || e.ctrlKey;
      if (!mod) return;
      if (isShortcutBlockedByTarget(e.target, { allowWhenModPressed: true, hasMod: true })) return;
      const key = e.key.toLowerCase();
      if (key === "b" && !e.shiftKey) {
        e.preventDefault();
        setSidebarOpen((v) => !v);
      } else if (key === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      } else if (key === "n") {
        e.preventDefault();
        const st = useSessionStore.getState();
        const curBotId = st.summariesById[activeSessionId]?.botId ?? st.draftBotId;
        const curBot = bots.find((b) => b.id === curBotId);
        const targetBotId =
          curBot && (!curBot.isRemote || curBot.online)
            ? curBot.id
            : bots.find((b) => !b.isRemote || b.online)?.id ?? bots[0]?.id;
        if (targetBotId) {
          setSettingsOpen(false);
          selectMainView("chat");
          startDraftSession(targetBotId);
        }
      } else if (key === ",") {
        e.preventDefault();
        if (settingsOpen) {
          setSettingsOpen(false);
        } else {
          openSettings("general");
        }
      } else if (key === "j" && !e.shiftKey) {
        e.preventDefault();
        togglePanel("terminal");
      } else if (e.shiftKey && key === "e") {
        e.preventDefault();
        togglePanel("files");
      } else if (e.shiftKey && key === "g") {
        e.preventDefault();
        togglePanel("diff");
      } else if (e.shiftKey && key === "b" && isElectron) {
        e.preventDefault();
        togglePanel("browser");
      } else if (e.shiftKey && key === "s") {
        e.preventDefault();
        setSettingsOpen(false);
        switchAppMode(appMode === "super" ? "pi" : "super");
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    activeSessionId,
    appMode,
    bots,
    client,
    isElectron,
    locale,
    openSettings,
    selectMainView,
    settingsOpen,
    startDraftSession,
    switchAppMode,
    togglePanel,
  ]);

  return (
    <div
      className="flex h-screen w-screen flex-col overflow-hidden"
      style={{ background: "var(--pi-chrome)", color: "var(--pi-ink)" }}
    >
      <DesktopTitlebar
        appMode={appMode}
        onChangeAppMode={switchAppMode}
        sidebarOpen={sidebarOpen}
        onToggleSidebar={() => setSidebarOpen((v) => !v)}
        workbenchOpen={workbenchOpen}
        onToggleWorkbench={toggleWorkbench}
      />

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <Sidebar
          open={sidebarOpen}
          hideHeader={isElectron}
          appMode={appMode}
          activeView={activeView}
          onChangeAppMode={switchAppMode}
          onSelectChat={() => selectMainView("chat")}
          onSelectSuperMain={() => switchAppMode("super")}
          onSelectFeature={(view) => selectMainView(view)}
          onToggleCollapse={() => setSidebarOpen((v) => !v)}
          onOpenSearch={() => setPaletteOpen(true)}
          onOpenCreateBot={(target) => {
            setCreateBotTargetDevice(target ?? null);
            setCreateBotOpen(true);
          }}
          onOpenBotSettings={(bot) => setSettingsBot(bot)}
          onOpenSuperTeam={() => setSuperTeamDialogOpen(true)}
          onOpenSettings={() => openSettings("general")}
        />

        <div
          className={`flex min-h-0 min-w-0 flex-1 sm:pr-2 sm:pb-2 sm:pl-1 ${
            isElectron ? "sm:pt-0" : "sm:pt-2"
          }`}
        >
          <main className="pi-chat-container pi-inset-canvas flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden sm:rounded-[14px]">
            {(sseLost || error) && (
              <div className="px-4 pt-2.5">
                <NoticeBar
                  tone="warning"
                  message={error || t("conn.bannerOffline")}
                  action={
                    <Button variant="primary" size="sm" onClick={() => void reconnect()}>
                      {t("conn.retry")}
                    </Button>
                  }
                />
              </div>
            )}

            {actionError && (
              <div className="px-4 pt-2.5">
                <NoticeBar
                  tone="danger"
                  message={actionError}
                  action={
                    <Button variant="ghost" size="sm" onClick={clearActionError}>
                      {t("common.close")}
                    </Button>
                  }
                />
              </div>
            )}

            <div
              key={activeView === "super" && selectedWorkerId ? `worker:${selectedWorkerId}` : activeView}
              className="pi-view-enter flex min-h-0 min-w-0 flex-1 flex-col"
            >
              {activeView === "super" ? (
                selectedWorkerId ? (
                  <WorkerWorkspace
                    onOpenProvidersSettings={() => openSettings("providers")}
                  />
                ) : (
                  <SuperWorkspace
                    onOpenTeamDialog={() => setSuperTeamDialogOpen(true)}
                    onOpenProvidersSettings={() => openSettings("providers")}
                  />
                )
              ) : activeView === "timers" ||
                activeView === "memory" ||
                activeView === "skills" ||
                activeView === "mcp" ? (
                <FeatureWorkspaceView
                  kind={activeView}
                  timerScope={appMode}
                  onBack={() => setActiveView(appMode === "super" ? "super" : "chat")}
                  onOpenSession={(sid) => {
                    if (!client) return;
                    selectMainView("chat");
                    void selectSession(client, sid, locale);
                  }}
                  onOpenSuperMain={() => switchAppMode("super")}
                  onOpenSuperWorker={(workerId) => {
                    switchAppMode("super");
                    setSelectedWorkerId(workerId);
                  }}
                />
              ) : (
                <ChatWorkspace
                  onOpenBotSettings={(bot) => setSettingsBot(bot)}
                  onOpenProvidersSettings={() => openSettings("providers")}
                />
              )}
            </div>
          </main>

          <WorkbenchDock
            activeView={activeView}
            sidebarOpen={sidebarOpen}
          />
        </div>
      </div>

      <SettingsModal
        open={settingsOpen}
        tab={settingsTab}
        autoCheckUpdateTick={autoCheckUpdateTick}
        onSelectTab={(tab) => openSettings(tab)}
        onOpenChange={setSettingsOpen}
        onOpenSession={() => {
          selectMainView("chat");
          setSettingsOpen(false);
        }}
      />

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onOpenSettings={(tab = "general") => openSettings(tab)}
        onOpenCreateBot={() => {
          setCreateBotTargetDevice(null);
          setCreateBotOpen(true);
        }}
        onSelectChatView={() => {
          setSettingsOpen(false);
          selectMainView("chat");
        }}
        onSelectSuperView={() => {
          setSettingsOpen(false);
          switchAppMode("super");
        }}
        onSelectFeatureView={(view) => {
          setSettingsOpen(false);
          selectMainView(view);
        }}
      />

      <CreateBotDialog
        open={createBotOpen}
        targetDeviceId={createBotTargetDevice?.deviceId}
        targetDeviceName={createBotTargetDevice?.deviceName}
        onOpenChange={setCreateBotOpen}
        onCreated={(botId) => {
          selectMainView("chat");
          startDraftSession(botId);
        }}
      />

      <BotSettingsDialog bot={settingsBot} onClose={() => setSettingsBot(null)} />

      <SuperTeamDialog
        open={superTeamDialogOpen}
        onOpenChange={setSuperTeamDialogOpen}
      />

      {pendingDeletes.length > 0 && (
        <div className="fixed right-5 bottom-5 z-50 flex flex-col gap-2">
          {pendingDeletes.map((item) => (
            <div
              key={item.id}
              className="flex items-center gap-3 rounded-[12px] px-3.5 py-2.5 text-[13px] shadow-lg"
              style={{
                background: "var(--pi-paper)",
                color: "var(--pi-ink)",
                border: "1px solid var(--pi-canvas-border)",
              }}
            >
              <span className="max-w-[240px] truncate">
                {t("chat.undoDeleteMessage", {
                  title: item.title || t("nav.untitledChat"),
                })}
              </span>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => undoDeleteSession(item.id)}
              >
                {t("chat.undo")}
              </Button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

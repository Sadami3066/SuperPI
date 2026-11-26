import { useEffect, useState } from "react";
import type { HostBotDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { isShortcutBlockedByTarget } from "../../platform/shortcuts";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, NoticeBar } from "../../ui";
import { WorkbenchDock } from "../../workbench/WorkbenchDock";
import { BotSettingsDialog } from "../bots/BotSettingsDialog";
import { CreateBotDialog } from "../bots/CreateBotDialog";
import { ChatWorkspace } from "../chat/ChatWorkspace";
import { SettingsModal, type SettingsTabId } from "../settings/SettingsModal";
import { SuperWorkspace } from "../super/SuperWorkspace";
import { CommandPalette } from "./CommandPalette";
import { Sidebar } from "./Sidebar";

export function AppShell() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const sseLost = useConnectionStore((s) => s.sseLost);
  const error = useConnectionStore((s) => s.error);
  const reconnect = useConnectionStore((s) => s.reconnect);

  const bots = useBotStore((s) => s.bots);
  const createSession = useSessionStore((s) => s.createSession);
  const selectSession = useSessionStore((s) => s.selectSession);
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const loadArchivedSessions = useSessionStore((s) => s.loadArchivedSessions);
  const locale = useSettingsStore((s) => s.locale);
  const refreshSettingsData = useSettingsStore((s) => s.refreshSettingsData);
  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const togglePanel = useWorkbenchStore((s) => s.togglePanel);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));

  const [activeView, setActiveView] = useState<"chat" | "super">("chat");
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsTab, setSettingsTab] = useState<SettingsTabId>("general");
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const [createBotTargetDevice, setCreateBotTargetDevice] = useState<{
    deviceId: string;
    deviceName: string;
  } | null>(null);
  const [settingsBot, setSettingsBot] = useState<HostBotDto | null>(null);

  const activeSummary = useSessionStore((s) => s.summariesById[s.activeSessionId]);

  useEffect(() => {
    if (activeView === "super") {
      document.title = `Super PI — ${t("app.name")}`;
      return;
    }
    const activeBot = bots.find((b) => b.id === (activeSummary?.botId ?? bots[0]?.id));
    const titlePart = activeSummary?.title || activeBot?.name;
    document.title = titlePart ? `${titlePart} — Super PI` : "Super PI";
  }, [activeSummary?.botId, activeSummary?.title, activeView, bots, t]);

  const openSettings = (tab: SettingsTabId = "general") => {
    setSettingsTab(tab);
    setSettingsOpen(true);
    if (client) {
      void refreshSettingsData(client);
      if (tab === "archived") {
        void loadArchivedSessions(client);
      }
    }
  };

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.ctrlKey && !e.metaKey && !e.altKey && e.key === "Tab") {
        const state = useSessionStore.getState();
        const ordered = Object.values(state.summariesById)
          .filter((s) => !s.archived)
          .sort((a, b) => b.updatedAtMs - a.updatedAtMs);
        if (ordered.length > 1 && client) {
          e.preventDefault();
          const curIdx = ordered.findIndex((s) => s.id === state.activeSessionId);
          const step = e.shiftKey ? -1 : 1;
          const next = ordered[(curIdx + step + ordered.length) % ordered.length];
          if (next) {
            setActiveView("chat");
            void state.selectSession(client, next.id, locale);
          }
        }
        return;
      }

      if (e.altKey && !e.metaKey && !e.ctrlKey && e.key.toLowerCase() === "s") {
        if (isShortcutBlockedByTarget(e.target, { hasMod: false })) return;
        e.preventDefault();
        setSettingsOpen(false);
        setActiveView((v) => (v === "super" ? "chat" : "super"));
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
        const botId = useSessionStore.getState().summariesById[activeSessionId]?.botId ?? bots[0]?.id;
        if (client && botId) {
          setSettingsOpen(false);
          setActiveView("chat");
          void createSession(client, botId, locale);
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
        setActiveView((v) => (v === "super" ? "chat" : "super"));
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    activeSessionId,
    bots,
    client,
    createSession,
    isElectron,
    locale,
    settingsOpen,
    togglePanel,
  ]);

  return (
    <div
      className="flex h-screen w-screen overflow-hidden"
      style={{ background: "var(--pi-surface)", color: "var(--pi-ink)" }}
    >
      <Sidebar
        open={sidebarOpen}
        activeView={activeView}
        onSelectChat={() => setActiveView("chat")}
        onToggleCollapse={() => setSidebarOpen((v) => !v)}
        onOpenSearch={() => setPaletteOpen(true)}
        onOpenCreateBot={(target) => {
          setCreateBotTargetDevice(target ?? null);
          setCreateBotOpen(true);
        }}
        onOpenBotSettings={(bot) => setSettingsBot(bot)}
        onOpenSettings={() => openSettings("general")}
        onOpenAutomations={() => openSettings("automations")}
        onOpenArchived={() => openSettings("archived")}
      />

      <main className="pi-chat-container flex min-h-0 min-w-0 flex-1 flex-col">
        {(sseLost || error) && (
          <div className="px-4 pt-2">
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

        <div key={activeView} className="pi-view-enter flex min-h-0 min-w-0 flex-1 flex-col">
          {activeView === "super" ? (
            <SuperWorkspace
              activeView={activeView}
              onChangeView={(view) => setActiveView(view)}
              showWindowControls={!workbenchOpen}
              onOpenProvidersSettings={() => openSettings("providers")}
            />
          ) : (
            <ChatWorkspace
              activeView={activeView}
              onChangeView={(view) => setActiveView(view)}
              showWindowControls={!workbenchOpen}
              onOpenBotSettings={(bot) => setSettingsBot(bot)}
              onOpenProvidersSettings={() => openSettings("providers")}
            />
          )}
        </div>
      </main>

      <WorkbenchDock showWindowControls={workbenchOpen} />

      <SettingsModal
        open={settingsOpen}
        tab={settingsTab}
        onSelectTab={(tab) => openSettings(tab)}
        onOpenChange={setSettingsOpen}
        onOpenSession={(sessionId) => {
          if (!client) return;
          setActiveView("chat");
          setSettingsOpen(false);
          void selectSession(client, sessionId, locale);
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
          setActiveView("chat");
        }}
        onSelectSuperView={() => {
          setSettingsOpen(false);
          setActiveView("super");
        }}
      />

      <CreateBotDialog
        open={createBotOpen}
        targetDeviceId={createBotTargetDevice?.deviceId}
        targetDeviceName={createBotTargetDevice?.deviceName}
        onOpenChange={setCreateBotOpen}
        onCreated={(botId) => {
          setActiveView("chat");
          if (client) void createSession(client, botId, locale);
        }}
      />

      <BotSettingsDialog bot={settingsBot} onClose={() => setSettingsBot(null)} />
    </div>
  );
}

import { useEffect, useState } from "react";
import type { HostBotDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Button, NoticeBar } from "../../ui";
import { WorkbenchDock } from "../../workbench/WorkbenchDock";
import { BotSettingsDialog } from "../bots/BotSettingsDialog";
import { CreateBotDialog } from "../bots/CreateBotDialog";
import { ChatWorkspace } from "../chat/ChatWorkspace";
import { SettingsView, type SettingsTabId } from "../settings/SettingsView";
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
  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const summariesById = useSessionStore((s) => s.summariesById);
  const loadArchivedSessions = useSessionStore((s) => s.loadArchivedSessions);
  const locale = useSettingsStore((s) => s.locale);
  const refreshSettingsData = useSettingsStore((s) => s.refreshSettingsData);

  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [mainView, setMainView] = useState<"chat" | "settings">("chat");
  const [settingsTab, setSettingsTab] = useState<SettingsTabId>("general");
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [createBotOpen, setCreateBotOpen] = useState(false);
  const [settingsBot, setSettingsBot] = useState<HostBotDto | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey;
      if (!mod) return;
      const key = e.key.toLowerCase();
      if (key === "b") {
        e.preventDefault();
        setSidebarOpen((v) => !v);
      } else if (key === "k") {
        e.preventDefault();
        setPaletteOpen((v) => !v);
      } else if (key === "n") {
        e.preventDefault();
        const botId = summariesById[activeSessionId]?.botId ?? bots[0]?.id;
        if (client && botId) {
          setMainView("chat");
          void createSession(client, botId, locale);
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [activeSessionId, bots, client, createSession, locale, summariesById]);

  const openSettings = (tab: SettingsTabId = "general") => {
    setSettingsTab(tab);
    setMainView("settings");
    if (client) {
      void refreshSettingsData(client);
      if (tab === "archived") {
        void loadArchivedSessions(client);
      }
    }
  };

  return (
    <div
      className="flex h-screen w-screen overflow-hidden"
      style={{ background: "var(--pi-paper)", color: "var(--pi-ink)" }}
    >
      <Sidebar
        open={sidebarOpen}
        mainView={mainView}
        onToggleCollapse={() => setSidebarOpen((v) => !v)}
        onOpenSearch={() => setPaletteOpen(true)}
        onOpenCreateBot={() => setCreateBotOpen(true)}
        onOpenBotSettings={(bot) => setSettingsBot(bot)}
        onOpenSettings={() => openSettings("general")}
        onOpenArchived={() => openSettings("archived")}
        onSelectChatView={() => setMainView("chat")}
      />

      <main className="flex min-h-0 min-w-0 flex-1 flex-col">
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

        {mainView === "chat" ? (
          <ChatWorkspace
            sidebarOpen={sidebarOpen}
            onOpenSidebar={() => setSidebarOpen(true)}
            onOpenBotSettings={(bot) => setSettingsBot(bot)}
            onOpenProvidersSettings={() => openSettings("providers")}
          />
        ) : (
          <SettingsView
            sidebarOpen={sidebarOpen}
            tab={settingsTab}
            onSelectTab={(tab) => openSettings(tab)}
            onOpenSidebar={() => setSidebarOpen(true)}
            onBackToChat={() => setMainView("chat")}
          />
        )}
      </main>

      {mainView === "chat" && <WorkbenchDock />}

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onOpenSettings={() => openSettings("general")}
        onOpenCreateBot={() => setCreateBotOpen(true)}
        onSelectChatView={() => setMainView("chat")}
      />

      <CreateBotDialog
        open={createBotOpen}
        onOpenChange={setCreateBotOpen}
        onCreated={(botId) => {
          setMainView("chat");
          if (client) void createSession(client, botId, locale);
        }}
      />

      <BotSettingsDialog bot={settingsBot} onClose={() => setSettingsBot(null)} />
    </div>
  );
}

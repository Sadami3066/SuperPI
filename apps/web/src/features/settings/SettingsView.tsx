import {
  IconArchive,
  IconArrowLeft,
  IconDevices,
  IconLayoutSidebarLeftExpand,
  IconPalette,
  IconSparkles,
} from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { IconButton } from "../../ui";
import { ArchivedSettings } from "./ArchivedSettings";
import { GeneralSettings } from "./GeneralSettings";
import { ProvidersSettings } from "./ProvidersSettings";
import { RelaySettings } from "./RelaySettings";

export type SettingsTabId = "general" | "providers" | "relay" | "archived";

export function SettingsView({
  sidebarOpen,
  tab,
  onSelectTab,
  onOpenSidebar,
  onBackToChat,
}: {
  sidebarOpen: boolean;
  tab: SettingsTabId;
  onSelectTab: (tab: SettingsTabId) => void;
  onOpenSidebar: () => void;
  onBackToChat: () => void;
}) {
  const { t } = useI18n();
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isMacOverlay = desktopEnv?.isElectron && desktopEnv.platform === "darwin";

  const navItems: Array<{ id: SettingsTabId; label: string; icon: typeof IconPalette }> = [
    { id: "general", label: t("settings.navGeneral"), icon: IconPalette },
    { id: "providers", label: t("settings.navProviders"), icon: IconSparkles },
    { id: "relay", label: t("settings.navRelay"), icon: IconDevices },
    { id: "archived", label: t("settings.navArchived"), icon: IconArchive },
  ];

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <header
        className="pi-drag-region flex h-[48px] shrink-0 items-center justify-between px-4"
        style={{
          paddingLeft: !sidebarOpen && isMacOverlay ? 78 : undefined,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div className="pi-no-drag flex items-center gap-2">
          {!sidebarOpen && (
            <IconButton label={t("nav.expandSidebar")} onClick={onOpenSidebar}>
              <IconLayoutSidebarLeftExpand size={18} stroke={1.65} />
            </IconButton>
          )}
          <IconButton label={t("settings.back")} onClick={onBackToChat}>
            <IconArrowLeft size={18} stroke={1.7} />
          </IconButton>
          <span className="text-[15px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("settings.title")}
          </span>
        </div>
      </header>

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <nav
          className="hidden w-[200px] shrink-0 space-y-1 p-3 sm:block"
          style={{ borderRight: "0.5px solid var(--pi-hairline)" }}
        >
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = item.id === tab;
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onSelectTab(item.id)}
                className="flex w-full items-center gap-2.5 rounded-[10px] px-3 py-2 text-left text-[13px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                style={{
                  background: active ? "var(--pi-wash)" : "transparent",
                  color: active ? "var(--pi-ink)" : "var(--pi-ink-secondary)",
                }}
              >
                <Icon size={16} stroke={1.65} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        <div className="min-h-0 flex-1 overflow-y-auto p-6">
          <div className="mx-auto w-full max-w-[680px]">
            {tab === "general" && <GeneralSettings />}
            {tab === "providers" && <ProvidersSettings />}
            {tab === "relay" && <RelaySettings />}
            {tab === "archived" && <ArchivedSettings onOpenSession={onBackToChat} />}
          </div>
        </div>
      </div>
    </div>
  );
}

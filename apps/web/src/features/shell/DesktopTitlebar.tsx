import {
  IconLayoutSidebarLeftCollapse,
  IconLayoutSidebarLeftExpand,
  IconLayoutSidebarRight,
} from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { IconButton } from "../../ui";
import { PiBrandMark } from "../shared/BrandAssets";
import { ModeSwitcher } from "./ModeSwitcher";
import { WindowControls } from "./WindowControls";

export function DesktopTitlebar({
  appMode,
  onChangeAppMode,
  sidebarOpen,
  onToggleSidebar,
  workbenchOpen,
  onToggleWorkbench,
}: {
  appMode: "pi" | "super";
  onChangeAppMode: (mode: "pi" | "super") => void;
  sidebarOpen: boolean;
  onToggleSidebar: () => void;
  workbenchOpen: boolean;
  onToggleWorkbench: () => void;
}) {
  const { t } = useI18n();
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);

  const isElectron = Boolean(desktopEnv?.isElectron);
  const isMac = desktopEnv?.platform === "darwin";
  const isFrameless = Boolean(isElectron && !isMac);

  if (!isElectron) return null;

  return (
    <header
      className="pi-drag-region grid h-[36px] w-full shrink-0 grid-cols-[1fr_auto_1fr] items-center select-none"
      style={{ background: "var(--pi-chrome)" }}
      onDoubleClick={() => {
        if (isFrameless) void windowToggleMaximize();
      }}
    >
      <div
        className="flex min-w-0 items-center gap-2 justify-self-start"
        style={{ paddingLeft: isMac ? 76 : 12 }}
      >
        <div className="pi-no-drag flex items-center gap-2">
          {!isMac && (
            <div className="flex items-center gap-1.5">
              <PiBrandMark size={16} dark={dark} />
              <span
                className="text-[12.5px] font-semibold tracking-tight"
                style={{ color: "var(--pi-ink)" }}
              >
                {t("app.name")}
              </span>
            </div>
          )}
          <IconButton
            size="sm"
            label={sidebarOpen ? t("nav.collapseSidebar") : t("nav.expandSidebar")}
            onClick={onToggleSidebar}
          >
            {sidebarOpen ? (
              <IconLayoutSidebarLeftCollapse size={15.5} stroke={1.65} />
            ) : (
              <IconLayoutSidebarLeftExpand size={15.5} stroke={1.65} />
            )}
          </IconButton>
        </div>
      </div>

      <div className="pi-no-drag flex items-center justify-center">
        <ModeSwitcher
          compact
          activeView={appMode === "super" ? "super" : "chat"}
          onChange={(v) => onChangeAppMode(v === "super" ? "super" : "pi")}
        />
      </div>

      <div className="flex shrink-0 items-center justify-self-end">
        <div className={isFrameless ? "pi-no-drag pr-1.5" : "pi-no-drag pr-3"}>
          <IconButton
            size="sm"
            label={t("chat.workbenchToggle")}
            active={workbenchOpen}
            onClick={onToggleWorkbench}
          >
            <IconLayoutSidebarRight size={15.5} stroke={1.65} />
          </IconButton>
        </div>
        <WindowControls />
      </div>
    </header>
  );
}

import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { Button } from "../../ui";
import { AutomationsSettings } from "../settings/AutomationsSettings";
import { McpSettings } from "../settings/McpSettings";
import { MemorySettings } from "../settings/MemorySettings";
import { SkillsSettings } from "../settings/SkillsSettings";
import { BrandIcon } from "../shared/BrandAssets";
import { WindowControls } from "./WindowControls";

export type FeatureViewKind = "timers" | "inbox" | "memory" | "skills" | "mcp";

export function FeatureWorkspaceView({
  kind,
  showWindowControls,
  onBack,
  onOpenSession,
}: {
  kind: FeatureViewKind;
  showWindowControls: boolean;
  onBack: () => void;
  onOpenSession: (sessionId: string) => void;
}) {
  const { t } = useI18n();
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isFrameless = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");

  const title =
    kind === "timers"
      ? t("nav.timers")
      : kind === "inbox"
        ? t("nav.inbox")
        : kind === "memory"
          ? t("settings.navMemory")
          : kind === "skills"
            ? t("nav.skills")
            : t("nav.mcp");

  return (
    <div className="flex min-h-0 flex-1 flex-col" style={{ background: "var(--pi-canvas)" }}>
      <header
        className="pi-drag-region flex h-[44px] shrink-0 items-center justify-between gap-2 pl-4"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 12,
          borderBottom: "1px solid var(--pi-canvas-border)",
        }}
        onDoubleClick={() => {
          if (isFrameless) void windowToggleMaximize();
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-2.5">
          <Button variant="ghost" size="sm" onClick={onBack}>
            <BrandIcon name="navigation/back" size={14} />
            <span>{t("common.back")}</span>
          </Button>
          <span className="truncate text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {title}
          </span>
        </div>

        <div className="pi-no-drag flex shrink-0 items-center gap-2">
          {showWindowControls && <WindowControls />}
        </div>
      </header>

      <div className="pi-scroll-shadow min-h-0 flex-1 px-6 py-6">
        <div className="mx-auto w-full max-w-[820px]">
          {kind === "timers" && <AutomationsSettings mode="timers" onOpenSession={onOpenSession} />}
          {kind === "inbox" && <AutomationsSettings mode="inbox" onOpenSession={onOpenSession} />}
          {kind === "memory" && <MemorySettings />}
          {kind === "skills" && <SkillsSettings />}
          {kind === "mcp" && <McpSettings />}
        </div>
      </div>
    </div>
  );
}

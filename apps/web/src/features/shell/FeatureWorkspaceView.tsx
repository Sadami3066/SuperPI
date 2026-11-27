import { useState } from "react";
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

export type FeatureViewKind = "automations" | "memory" | "capabilities";

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
  const [capTab, setCapTab] = useState<"skills" | "mcp">("skills");

  const title =
    kind === "automations"
      ? t("settings.navAutomations")
      : kind === "memory"
        ? t("settings.navMemory")
        : t("nav.capabilities");

  return (
    <div className="flex min-h-0 flex-1 flex-col" style={{ background: "var(--pi-surface)" }}>
      <header
        className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between gap-2 pl-4"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 12,
          borderBottom: "0.5px solid var(--pi-hairline)",
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
          {kind === "capabilities" && (
            <div
              className="flex items-center gap-1 rounded-full p-0.5"
              style={{
                background: "var(--pi-wash)",
                border: "0.5px solid var(--pi-hairline)",
              }}
            >
              <button
                type="button"
                onClick={() => setCapTab("skills")}
                className="rounded-full px-3 py-1 text-[12px] font-medium transition-colors"
                style={{
                  background: capTab === "skills" ? "var(--pi-surface)" : "transparent",
                  color: capTab === "skills" ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                {t("nav.capabilitiesSkills")}
              </button>
              <button
                type="button"
                onClick={() => setCapTab("mcp")}
                className="rounded-full px-3 py-1 text-[12px] font-medium transition-colors"
                style={{
                  background: capTab === "mcp" ? "var(--pi-surface)" : "transparent",
                  color: capTab === "mcp" ? "var(--pi-ink)" : "var(--pi-ink-tertiary)",
                }}
              >
                {t("nav.capabilitiesConnectors")}
              </button>
            </div>
          )}

          {showWindowControls && <WindowControls />}
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto px-6 py-6">
        <div className="mx-auto w-full max-w-[820px]">
          {kind === "automations" && <AutomationsSettings onOpenSession={onOpenSession} />}
          {kind === "memory" && <MemorySettings />}
          {kind === "capabilities" && (capTab === "skills" ? <SkillsSettings /> : <McpSettings />)}
        </div>
      </div>
    </div>
  );
}

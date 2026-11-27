import { useI18n } from "../../i18n";
import { Button } from "../../ui";
import { AutomationsSettings } from "../settings/AutomationsSettings";
import { McpSettings } from "../settings/McpSettings";
import { MemorySettings } from "../settings/MemorySettings";
import { SkillsSettings } from "../settings/SkillsSettings";
import { BrandIcon } from "../shared/BrandAssets";

export type FeatureViewKind = "timers" | "inbox" | "memory" | "skills" | "mcp";

export function FeatureWorkspaceView({
  kind,
  onBack,
  onOpenSession,
}: {
  kind: FeatureViewKind;
  onBack: () => void;
  onOpenSession: (sessionId: string) => void;
}) {
  const { t } = useI18n();

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
        className="flex h-[44px] shrink-0 items-center justify-between gap-2 px-4"
        style={{
          borderBottom: "1px solid var(--pi-canvas-border)",
        }}
      >
        <div className="flex min-w-0 items-center gap-2.5">
          <Button variant="ghost" size="sm" onClick={onBack}>
            <BrandIcon name="navigation/back" size={14} />
            <span>{t("common.back")}</span>
          </Button>
          <span className="truncate text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {title}
          </span>
        </div>
      </header>

      <div className="pi-scroll-shadow min-h-0 flex-1">
        <div className="mx-auto w-full max-w-[820px] px-6 py-6">
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

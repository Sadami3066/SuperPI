import type { HostUsageDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { Button } from "../../ui";

export function UsageSheet({
  percent,
  usage,
  compacting,
  busy,
  onCompact,
  extraAction,
}: {
  percent: number;
  usage: HostUsageDto | null | undefined;
  compacting: boolean;
  busy: boolean;
  onCompact: () => void;
  extraAction?: React.ReactNode;
}) {
  const { t } = useI18n();
  const clamped = Math.max(0, Math.min(100, percent));
  const barColor =
    clamped >= 85 ? "var(--pi-danger)" : clamped >= 65 ? "var(--pi-warning)" : "var(--pi-accent)";

  return (
    <div className="space-y-3 text-[12.5px]">
      <div className="flex items-center justify-between">
        <span className="font-semibold" style={{ color: "var(--pi-ink)" }}>
          {t("usage.title")}
        </span>
        <span className="text-[12px] font-semibold tabular-nums" style={{ color: barColor }}>
          {clamped}%
        </span>
      </div>

      <div className="space-y-1">
        <div
          className="h-1.5 w-full overflow-hidden rounded-full"
          style={{ background: "var(--pi-wash)" }}
        >
          <div
            className="h-full rounded-full transition-all duration-200"
            style={{ width: `${clamped}%`, background: barColor }}
          />
        </div>
        {usage && usage.compactionTriggerPercent > 0 && (
          <div className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("usage.autoTriggerAt", { pct: usage.compactionTriggerPercent })}
          </div>
        )}
      </div>

      {usage ? (
        <div className="grid grid-cols-2 gap-x-3 gap-y-1.5" style={{ color: "var(--pi-ink-secondary)" }}>
          <div className="col-span-2 flex justify-between">
            <span>{t("usage.context")}</span>
            <span className="tabular-nums font-medium" style={{ color: "var(--pi-ink)" }}>
              {usage.contextTokens.toLocaleString()} / {usage.contextWindow.toLocaleString()}
            </span>
          </div>
          <div className="flex justify-between">
            <span>{t("usage.input")}</span>
            <span className="tabular-nums">{usage.inputTokens.toLocaleString()}</span>
          </div>
          <div className="flex justify-between">
            <span>{t("usage.output")}</span>
            <span className="tabular-nums">{usage.outputTokens.toLocaleString()}</span>
          </div>
          {usage.cacheReadTokens > 0 && (
            <div className="flex justify-between">
              <span>{t("usage.cacheRead")}</span>
              <span className="tabular-nums">{usage.cacheReadTokens.toLocaleString()}</span>
            </div>
          )}
          {usage.cost > 0 && (
            <div className="flex justify-between">
              <span>{t("usage.cost")}</span>
              <span className="tabular-nums">${usage.cost.toFixed(4)}</span>
            </div>
          )}
        </div>
      ) : (
        <div style={{ color: "var(--pi-ink-tertiary)" }}>—</div>
      )}

      <div className="flex items-center gap-2 pt-0.5">
        <Button
          variant="secondary"
          size="sm"
          className="flex-1"
          disabled={compacting || busy}
          onClick={onCompact}
        >
          {compacting ? t("usage.compacting") : t("usage.compactNow")}
        </Button>
        {extraAction}
      </div>
    </div>
  );
}

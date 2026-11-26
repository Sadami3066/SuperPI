import type { HostUsageDto } from "../../api/types";
import { useI18n } from "../../i18n";

export function ContextMeter({
  percent,
  usage,
  onClick,
}: {
  percent: number;
  usage: HostUsageDto | null | undefined;
  onClick?: () => void;
}) {
  const { t } = useI18n();
  const fraction = Math.min(1, Math.max(0, percent / 100));
  const radius = 6.8;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = circumference * (1 - fraction);

  const progressColor =
    percent >= 90
      ? "var(--pi-danger)"
      : percent >= 75
        ? "var(--pi-warning)"
        : "var(--pi-accent)";

  const hasUsage = Boolean(usage && usage.contextTokens > 0) || percent > 0;

  return (
    <button
      type="button"
      onClick={onClick}
      title={t("usage.title")}
      aria-label={`${t("usage.title")} ${percent}%`}
      className="pi-no-drag inline-flex h-7 items-center gap-1.5 rounded-[8px] px-2 text-[11.5px] font-medium tabular-nums transition-colors hover:bg-[var(--pi-hover)]"
      style={{ color: "var(--pi-ink-secondary)" }}
    >
      <svg
        width={17}
        height={17}
        viewBox="0 0 18 18"
        aria-hidden="true"
        className="shrink-0 -rotate-90"
      >
        <circle
          cx={9}
          cy={9}
          r={radius}
          fill="none"
          stroke="var(--pi-hairline)"
          strokeWidth={2.3}
        />
        {fraction > 0 && (
          <circle
            cx={9}
            cy={9}
            r={radius}
            fill="none"
            stroke={progressColor}
            strokeWidth={2.3}
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
          />
        )}
      </svg>
      {hasUsage && <span>{percent}%</span>}
    </button>
  );
}

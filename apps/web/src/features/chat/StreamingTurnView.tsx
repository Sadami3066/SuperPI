import { useEffect } from "react";
import { IconAlertCircle, IconLoader2, IconRefresh } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useStreamStore } from "../../stores/streamStore";
import { Button } from "../../ui";
import { PiActivityIcon } from "../shared/BrandAssets";
import { renderTurnBlock } from "./TurnItem";

export function StreamingTurnView({
  sessionId,
  compactProcess,
  dark,
  onRetry,
  onLayoutTick,
}: {
  sessionId: string;
  compactProcess: boolean;
  dark: boolean;
  onRetry: () => void;
  onLayoutTick: () => void;
}) {
  const { t } = useI18n();
  const liveRun = useStreamStore((s) => s.activeBySession[sessionId]);

  const lastBlk = liveRun?.blocks[liveRun.blocks.length - 1];
  const lastSize =
    lastBlk?.kind === "text"
      ? lastBlk.text.length
      : lastBlk?.kind === "activity"
        ? lastBlk.steps.length
        : 0;

  useEffect(() => {
    if (liveRun) {
      onLayoutTick();
    }
  }, [liveRun, liveRun?.blocks.length, lastSize, onLayoutTick]);

  if (!liveRun) return null;

  const liveDurationMs =
    liveRun.finishedAt && liveRun.startedAt > 0 && liveRun.finishedAt >= liveRun.startedAt
      ? liveRun.finishedAt - liveRun.startedAt
      : undefined;

  return (
    <div className="py-2">
      {liveRun.retrying && (
        <div
          className="mb-2 inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] font-medium"
          style={{ background: "var(--pi-wash)", color: "var(--pi-accent)" }}
        >
          <IconLoader2 size={13} stroke={1.85} className="animate-spin" />
          <span>{t("activity.thinking")}</span>
        </div>
      )}

      {liveRun.blocks.length === 0 && liveRun.streaming && !liveRun.failed && (
        <div
          className="flex items-center gap-2 py-2 text-[12.5px] font-medium"
          style={{ color: "var(--pi-ink-secondary)" }}
        >
          <PiActivityIcon size={17} dark={dark} />
          <span className="pi-text-shimmer">{t("activity.thinking")}</span>
        </div>
      )}

      {liveRun.blocks.map((block, idx) =>
        renderTurnBlock(
          block,
          sessionId,
          compactProcess,
          Boolean(liveRun.streaming && idx === liveRun.blocks.length - 1),
          {
            status: liveRun.status,
            durationMs: liveDurationMs,
            dark,
          },
        ),
      )}

      {liveRun.failed && (
        <div
          className="mt-2.5 rounded-[14px] px-3.5 py-2.5"
          style={{
            background: dark ? "rgba(248, 113, 113, 0.12)" : "rgba(196, 37, 29, 0.06)",
            border: dark
              ? "0.5px solid rgba(248, 113, 113, 0.25)"
              : "0.5px solid rgba(196, 37, 29, 0.16)",
          }}
        >
          <div className="flex items-start gap-2">
            <IconAlertCircle
              size={16}
              stroke={1.75}
              className="mt-0.5 shrink-0"
              style={{ color: "var(--pi-danger)" }}
            />
            <div className="min-w-0 flex-1">
              <div className="text-[13px] leading-[1.5]" style={{ color: "var(--pi-ink)" }}>
                {liveRun.error || t("chat.failedGeneric")}
              </div>
              <div className="mt-2">
                <Button variant="primary" size="sm" onClick={onRetry}>
                  <IconRefresh size={13} stroke={1.75} />
                  <span>{t("chat.retry")}</span>
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

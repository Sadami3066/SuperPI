import { useEffect } from "react";
import { IconAlertCircle, IconLoader2 } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import { useStreamStore } from "../../stores/streamStore";
import { Button } from "../../ui";
import { BrandIcon, PiActivityIcon } from "../shared/BrandAssets";
import { renderTurnBlock } from "./TurnItem";

export function StreamingTurnView({
  sessionId,
  compactProcess,
  dark,
  canRetry = true,
  onRetry,
  onLayoutTick,
}: {
  sessionId: string;
  compactProcess: boolean;
  dark: boolean;
  canRetry?: boolean;
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

  let activityCount = 0;
  let lastActivityIdx = -1;
  for (let i = 0; i < liveRun.blocks.length; i++) {
    if (liveRun.blocks[i].kind === "activity") {
      activityCount += 1;
      lastActivityIdx = i;
    }
  }

  return (
    <div className="py-2">
      {liveRun.retrying && (
        <div
          className="mb-2 inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[12px] font-medium"
          style={{ background: "var(--pi-wash)", color: "var(--pi-accent)" }}
        >
          <IconLoader2 size={13} stroke={1.85} className="animate-spin" />
          <span>{t("activity.retrying")}</span>
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

      {liveRun.blocks.map((block, idx) => {
        let blockDuration: number | undefined;
        let blockStatus: "done" | "stopped" | "failed" | undefined;
        if (block.kind === "activity") {
          let toolSum = 0;
          for (const st of block.steps) {
            if (st.kind === "tool" && st.tool.durationMs > 0) toolSum += st.tool.durationMs;
          }
          blockDuration =
            block.durationMs !== undefined
              ? block.durationMs
              : toolSum > 0
                ? toolSum
                : activityCount === 1
                  ? liveDurationMs
                  : undefined;
          blockStatus =
            idx === lastActivityIdx
              ? liveRun.status
              : block.steps.some((s) => s.kind === "tool" && s.tool.status === "failed")
                ? "failed"
                : "done";
        }
        return renderTurnBlock(
          block,
          sessionId,
          compactProcess,
          Boolean(liveRun.streaming && idx === liveRun.blocks.length - 1),
          {
            status: blockStatus,
            durationMs: blockDuration,
            dark,
            persistPrefix: `${sessionId}:${liveRun.runId}:b${idx}`,
          },
        );
      })}

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
              {canRetry && (
                <div className="mt-2">
                  <Button variant="primary" size="sm" onClick={onRetry}>
                    <BrandIcon name="action/retry" size={13} />
                    <span>{t("chat.retry")}</span>
                  </Button>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

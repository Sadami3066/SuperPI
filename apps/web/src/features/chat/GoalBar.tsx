import { useCallback, useEffect, useState } from "react";
import { formatGoalPhase } from "../../api/formatters";
import type { HostGoalDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSuperStore } from "../../stores/superStore";
import { Badge, Button, IconButton } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

export function GoalBar({ sessionId }: { sessionId: string }) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const goalVersion = useSuperStore((s) => s.goalVersion);

  const [goal, setGoal] = useState<HostGoalDto | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!client || !sessionId) {
      setGoal(null);
      return;
    }
    try {
      const res = await client.getSessionGoal(sessionId);
      setGoal(res.goal ?? null);
    } catch {
      setGoal(null);
    }
  }, [client, sessionId]);

  useEffect(() => {
    void refresh();
  }, [goalVersion, refresh]);

  if (!goal || goal.phase === "completed" || goal.phase === "complete") {
    return null;
  }

  const isPaused = goal.phase === "paused";
  const isBlocked = goal.phase === "blocked";
  const hasMaxRounds = goal.maxRounds > 0;
  const pct = hasMaxRounds
    ? Math.min(100, Math.round((goal.roundsStarted / goal.maxRounds) * 100))
    : 0;

  return (
    <div className="mx-auto mb-2 w-full max-w-[760px]">
      <div
        className="flex flex-wrap items-center justify-between gap-2.5 rounded-[12px] px-3 py-1.5"
        style={{
          background: "var(--pi-chrome)",
          border: "1px solid var(--pi-canvas-border)",
        }}
      >
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <span
            aria-hidden="true"
            className={`h-2 w-2 shrink-0 rounded-full ${!isPaused && !isBlocked ? "animate-pulse" : ""}`}
            style={{
              background: isPaused
                ? "var(--pi-ink-tertiary)"
                : isBlocked
                  ? "var(--pi-warning)"
                  : "var(--pi-accent)",
            }}
          />
          <span
            title={goal.objective}
            className="min-w-0 truncate text-[12.5px] font-medium"
            style={{ color: "var(--pi-ink)" }}
          >
            {goal.objective}
          </span>
          {(isPaused || isBlocked) && (
            <Badge tone={isBlocked ? "warning" : "neutral"}>
              {formatGoalPhase(goal.phase, t)}
            </Badge>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-2.5">
          <div className="flex items-center gap-2">
            {hasMaxRounds && (
              <div
                className="hidden h-1 w-14 overflow-hidden rounded-full sm:block"
                style={{ background: "var(--pi-wash)" }}
              >
                <div
                  className="h-full rounded-full transition-all duration-200"
                  style={{ width: `${pct}%`, background: "var(--pi-accent)" }}
                />
              </div>
            )}
            <span className="text-[11.5px] tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
              {hasMaxRounds
                ? t("goal.roundsProgress", { current: goal.roundsStarted, max: goal.maxRounds })
                : t("goal.roundOnly", { current: Math.max(1, goal.roundsStarted) })}
            </span>
          </div>

          <div className="flex items-center gap-0.5">
            {isPaused ? (
              <Button
                variant="ghost"
                size="sm"
                disabled={busy || !client}
                onClick={() => {
                  if (!client) return;
                  setBusy(true);
                  void client
                    .resumeSessionGoal(sessionId)
                    .then((res) => setGoal(res.goal ?? null))
                    .finally(() => setBusy(false));
                }}
              >
                {t("goal.resume")}
              </Button>
            ) : (
              <Button
                variant="ghost"
                size="sm"
                disabled={busy || !client}
                onClick={() => {
                  if (!client) return;
                  setBusy(true);
                  void client
                    .pauseSessionGoal(sessionId)
                    .then((res) => setGoal(res.goal ?? null))
                    .finally(() => setBusy(false));
                }}
              >
                {t("goal.pause")}
              </Button>
            )}
            <IconButton
              size="sm"
              label={t("goal.stop")}
              disabled={busy || !client}
              onClick={() => {
                if (!client) return;
                setBusy(true);
                void client
                  .deleteSessionGoal(sessionId)
                  .then(() => setGoal(null))
                  .finally(() => setBusy(false));
              }}
            >
              <BrandIcon name="navigation/close" size={13} />
            </IconButton>
          </div>
        </div>
      </div>
    </div>
  );
}

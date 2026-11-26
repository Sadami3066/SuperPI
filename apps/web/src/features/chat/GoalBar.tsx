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
  const pct =
    goal.maxRounds > 0
      ? Math.min(100, Math.round((goal.roundsStarted / goal.maxRounds) * 100))
      : 0;

  return (
    <div className="mx-auto mb-2 w-full max-w-[820px]">
      <div
        className="flex flex-wrap items-center justify-between gap-2.5 rounded-[12px] px-3 py-2"
        style={{
          background: "var(--pi-surface)",
          border: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div className="flex min-w-0 flex-1 items-center gap-2.5">
          <BrandIcon name="ai/goal" size={15} style={{ color: "var(--pi-accent)" }} className="shrink-0" />
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <span className="truncate text-[12.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {goal.objective}
              </span>
              <Badge tone={isPaused ? "neutral" : goal.phase === "blocked" ? "warning" : "accent"}>
                {formatGoalPhase(goal.phase, t)}
              </Badge>
            </div>
            <div className="mt-1 flex items-center gap-2">
              <div
                className="h-1 flex-1 overflow-hidden rounded-full"
                style={{ background: "var(--pi-wash)" }}
              >
                <div
                  className="h-full rounded-full transition-all duration-200"
                  style={{ width: `${pct}%`, background: "var(--pi-accent)" }}
                />
              </div>
              <span className="shrink-0 text-[11px] tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("goal.roundsProgress", { current: goal.roundsStarted, max: goal.maxRounds })}
              </span>
            </div>
          </div>
        </div>

        <div className="flex shrink-0 items-center gap-1">
          {isPaused ? (
            <Button
              variant="secondary"
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
  );
}

import { useCallback, useEffect, useState } from "react";
import {
  IconFolder,
  IconFolderOpen,
  IconGitBranch,
  IconPlayerPause,
  IconPlayerPlay,
  IconPlayerStop,
  IconTerminal2,
  IconTrash,
  IconWorld,
} from "@tabler/icons-react";
import type { HostGoalDto, HostJobOutputDto, HostJobSnapshotDto } from "../api/types";
import { useI18n } from "../i18n";
import { revealInFileManager } from "../platform/bridge";
import { useBotStore } from "../stores/botStore";
import { useConnectionStore } from "../stores/connectionStore";
import { useSessionStore } from "../stores/sessionStore";
import { useSettingsStore } from "../stores/settingsStore";
import { useSuperStore } from "../stores/superStore";
import { useWorkbenchStore } from "../stores/workbenchStore";
import { Badge, Button, Card, Dialog, IconButton, Input } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

export default function SessionInspectorPanel({
  context,
}: {
  context: WorkbenchPanelContext;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const bot = useBotStore((s) => s.bots.find((b) => b.id === context.botId) ?? s.bots[0]);
  const transcript = useSessionStore((s) =>
    context.sessionId ? s.transcriptsById[context.sessionId] : undefined,
  );
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const openPanel = useWorkbenchStore((s) => s.openPanel);
  const goalVersion = useSuperStore((s) => s.goalVersion);
  const jobsVersion = useSuperStore((s) => s.jobsVersion);

  const [goal, setGoal] = useState<HostGoalDto | null>(null);
  const [goalObjective, setGoalObjective] = useState("");
  const [goalRounds, setGoalRounds] = useState("6");
  const [goalBusy, setGoalBusy] = useState(false);

  const [jobs, setJobs] = useState<HostJobSnapshotDto[]>([]);
  const [jobOutput, setJobOutput] = useState<{ label: string; detail: HostJobOutputDto } | null>(null);

  const refreshGoalAndJobs = useCallback(async () => {
    if (!client || !context.sessionId || bot?.isRemote) return;
    try {
      const [g, j] = await Promise.all([
        client.getSessionGoal(context.sessionId).catch(() => ({ goal: null })),
        client.listSessionJobs(context.sessionId).catch(() => []),
      ]);
      setGoal(g.goal ?? null);
      setJobs(j);
    } catch {
      void 0;
    }
  }, [bot?.isRemote, client, context.sessionId]);

  useEffect(() => {
    void refreshGoalAndJobs();
  }, [goalVersion, jobsVersion, refreshGoalAndJobs]);

  if (!bot) return null;

  const activeModel =
    transcript?.model ||
    bot.defaultModel ||
    (hostSettings ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}` : "—");
  const permKey = `perm.${bot.permissionMode || "workspace_auto"}` as const;

  const quickEntries = [
    { id: "files", label: t("wb.filesTitle"), icon: IconFolder, show: true },
    { id: "diff", label: t("wb.diffTitle"), icon: IconGitBranch, show: !bot.isRemote },
    { id: "terminal", label: t("wb.terminalTitle"), icon: IconTerminal2, show: !bot.isRemote },
    { id: "browser", label: t("wb.browserTitle"), icon: IconWorld, show: isElectron },
  ].filter((x) => x.show);

  return (
    <div className="h-full overflow-y-auto p-4">
      <div className="space-y-4">
        <Card className="space-y-3 p-4">
          <div className="flex items-center justify-between gap-2">
            <div className="truncate text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {bot.name}
            </div>
            {bot.isRemote && (
              <Badge tone={bot.online ? "success" : "neutral"}>
                {bot.online ? t("nav.online") : t("nav.offline")}
              </Badge>
            )}
          </div>
          {bot.intro && (
            <p className="text-[12.5px] leading-[1.55]" style={{ color: "var(--pi-ink-secondary)" }}>
              {bot.intro}
            </p>
          )}

          <div className="space-y-2 pt-1 text-[12.5px]">
            <div>
              <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("wb.model")}
              </div>
              <div
                className="mt-0.5 truncate font-medium"
                style={{ color: "var(--pi-ink)", fontFamily: "var(--pi-font-mono)" }}
              >
                {activeModel}
              </div>
            </div>

            {!bot.isRemote && (
              <div>
                <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("wb.permission")}
                </div>
                <div className="mt-0.5" style={{ color: "var(--pi-ink)" }}>
                  {t(permKey as Parameters<typeof t>[0])}
                </div>
              </div>
            )}

            {bot.deviceName && (
              <div>
                <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("wb.remoteDevice")}
                </div>
                <div className="mt-0.5" style={{ color: "var(--pi-ink)" }}>
                  {bot.deviceName}
                </div>
              </div>
            )}

            {bot.cwd && !bot.isRemote && (
              <div>
                <div className="text-[11px] font-medium" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("wb.path")}
                </div>
                <div
                  className="mt-0.5 break-all rounded-[8px] px-2.5 py-1.5 text-[11.5px]"
                  style={{
                    background: "var(--pi-paper)",
                    color: "var(--pi-ink-secondary)",
                    fontFamily: "var(--pi-font-mono)",
                  }}
                >
                  {bot.cwd}
                </div>
                {isElectron && (
                  <Button
                    variant="secondary"
                    size="sm"
                    className="mt-2 w-full"
                    onClick={() => void revealInFileManager(bot.cwd)}
                  >
                    <IconFolderOpen size={14} stroke={1.7} />
                    <span>{t("wb.revealDir")}</span>
                  </Button>
                )}
              </div>
            )}
          </div>
        </Card>

        {!bot.isRemote && context.sessionId && (
          <Card className="space-y-3 p-4">
            <div className="flex items-center justify-between gap-2">
              <span className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("inspector.goalTitle")}
              </span>
              {goal && <Badge tone={goal.phase === "running" ? "accent" : "neutral"}>{goal.phase}</Badge>}
            </div>

            {goal ? (
              <div className="space-y-2 text-[12.5px]">
                <div style={{ color: "var(--pi-ink)" }}>{goal.objective}</div>
                <div className="text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("inspector.goalRounds", {
                    current: goal.roundsStarted,
                    max: goal.maxRounds,
                  })}
                </div>
                {goal.blockedReason && (
                  <div className="text-[12px]" style={{ color: "var(--pi-danger)" }}>
                    {goal.blockedReason}
                  </div>
                )}
                <div className="flex gap-1.5 pt-1">
                  {goal.phase === "paused" ? (
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={goalBusy}
                      onClick={() => {
                        if (!client || !context.sessionId) return;
                        setGoalBusy(true);
                        void client
                          .resumeSessionGoal(context.sessionId)
                          .then((res) => setGoal(res.goal ?? null))
                          .finally(() => setGoalBusy(false));
                      }}
                    >
                      <IconPlayerPlay size={13.5} />
                      <span>{t("inspector.goalResume")}</span>
                    </Button>
                  ) : (
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={goalBusy}
                      onClick={() => {
                        if (!client || !context.sessionId) return;
                        setGoalBusy(true);
                        void client
                          .pauseSessionGoal(context.sessionId)
                          .then((res) => setGoal(res.goal ?? null))
                          .finally(() => setGoalBusy(false));
                      }}
                    >
                      <IconPlayerPause size={13.5} />
                      <span>{t("inspector.goalPause")}</span>
                    </Button>
                  )}
                  <IconButton
                    size="sm"
                    label={t("common.delete")}
                    onClick={() => {
                      if (!client || !context.sessionId) return;
                      setGoalBusy(true);
                      void client
                        .deleteSessionGoal(context.sessionId)
                        .then(() => setGoal(null))
                        .finally(() => setGoalBusy(false));
                    }}
                  >
                    <IconTrash size={13.5} />
                  </IconButton>
                </div>
              </div>
            ) : (
              <div className="space-y-2">
                <Input
                  value={goalObjective}
                  onChange={(e) => setGoalObjective(e.target.value)}
                  placeholder={t("inspector.goalPlaceholder")}
                />
                <div className="flex items-center gap-2">
                  <Input
                    type="number"
                    min={1}
                    max={50}
                    value={goalRounds}
                    onChange={(e) => setGoalRounds(e.target.value)}
                    className="w-20"
                  />
                  <Button
                    variant="secondary"
                    size="sm"
                    className="flex-1"
                    disabled={goalBusy || !goalObjective.trim()}
                    onClick={() => {
                      if (!client || !context.sessionId || !goalObjective.trim()) return;
                      setGoalBusy(true);
                      void client
                        .createSessionGoal(context.sessionId, {
                          objective: goalObjective.trim(),
                          maxRounds: Number(goalRounds) || undefined,
                        })
                        .then((res) => {
                          setGoal(res.goal ?? null);
                          setGoalObjective("");
                        })
                        .finally(() => setGoalBusy(false));
                    }}
                  >
                    {t("inspector.goalStart")}
                  </Button>
                </div>
              </div>
            )}
          </Card>
        )}

        {!bot.isRemote && jobs.length > 0 && context.sessionId && (
          <Card className="space-y-2.5 p-4">
            <div className="flex items-center justify-between gap-2">
              <span className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("inspector.jobsTitle")} ({jobs.length})
              </span>
              {jobs.some((j) => j.status !== "running") && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    if (!client || !context.sessionId) return;
                    void client
                      .clearSettledSessionJobs(context.sessionId)
                      .then(() => refreshGoalAndJobs());
                  }}
                >
                  {t("inspector.jobsClearSettled")}
                </Button>
              )}
            </div>

            <div className="space-y-2">
              {jobs.map((job) => (
                <div
                  key={job.id}
                  className="rounded-[10px] p-2.5 text-[12px]"
                  style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate font-medium" style={{ color: "var(--pi-ink)" }}>
                      {job.label || job.command}
                    </span>
                    <Badge tone={job.status === "running" ? "accent" : job.status === "failed" ? "danger" : "neutral"}>
                      {job.status}
                    </Badge>
                  </div>
                  <div className="mt-2 flex items-center justify-end gap-1">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        if (!client || !context.sessionId) return;
                        void client
                          .getSessionJobOutput(context.sessionId, job.id)
                          .then((detail) =>
                            setJobOutput({ label: job.label || job.command, detail }),
                          );
                      }}
                    >
                      {t("inspector.jobOutput")}
                    </Button>
                    {job.status === "running" && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => {
                          if (!client || !context.sessionId) return;
                          void client
                            .killSessionJob(context.sessionId, job.id)
                            .then(() => refreshGoalAndJobs());
                        }}
                      >
                        <IconPlayerStop size={13} />
                        <span>{t("inspector.jobKill")}</span>
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </Card>
        )}

        <Card className="space-y-2.5 p-4">
          <div className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("wb.quickLaunch")}
          </div>
          <div className="grid grid-cols-2 gap-2">
            {quickEntries.map((entry) => {
              const Icon = entry.icon;
              return (
                <Button
                  key={entry.id}
                  variant="secondary"
                  size="sm"
                  className="justify-start"
                  onClick={() => openPanel(entry.id)}
                >
                  <Icon size={14} stroke={1.7} />
                  <span>{entry.label}</span>
                </Button>
              );
            })}
          </div>
        </Card>
      </div>

      <Dialog
        open={Boolean(jobOutput)}
        onOpenChange={(v) => {
          if (!v) setJobOutput(null);
        }}
        title={jobOutput?.label}
        maxWidthClass="max-w-[640px]"
      >
        {jobOutput && (
          <pre
            className="max-h-[60vh] overflow-auto rounded-[10px] p-3 text-[12px] whitespace-pre-wrap"
            style={{
              background: "var(--pi-paper)",
              color: "var(--pi-ink)",
              fontFamily: "var(--pi-font-mono)",
            }}
          >
            {jobOutput.detail.output || "(empty)"}
          </pre>
        )}
      </Dialog>
    </div>
  );
}

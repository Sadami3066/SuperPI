import { useCallback, useEffect, useState } from "react";
import { formatJobStatus } from "../../api/formatters";
import type { HostJobOutputDto, HostJobSnapshotDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSuperStore } from "../../stores/superStore";
import { Badge, Button, Dialog, IconButton } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

export function JobsBar({ sessionId }: { sessionId: string }) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const jobsVersion = useSuperStore((s) => s.jobsVersion);

  const [jobs, setJobs] = useState<HostJobSnapshotDto[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [jobOutput, setJobOutput] = useState<{ label: string; detail: HostJobOutputDto } | null>(null);

  const refresh = useCallback(async () => {
    if (!client || !sessionId) {
      setJobs([]);
      return;
    }
    try {
      const list = await client.listSessionJobs(sessionId);
      setJobs(list);
    } catch {
      setJobs([]);
    }
  }, [client, sessionId]);

  useEffect(() => {
    void refresh();
  }, [jobsVersion, refresh]);

  if (jobs.length === 0) return null;

  const runningCount = jobs.filter((j) => j.status === "running").length;

  return (
    <>
      <div className="mx-auto mb-2 w-full max-w-[820px]">
        <div
          className="rounded-[12px] px-3 py-2"
          style={{
            background: "var(--pi-surface)",
            border: "0.5px solid var(--pi-hairline)",
          }}
        >
          <div className="flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => setExpanded((v) => !v)}
              className="flex min-w-0 flex-1 items-center gap-2 text-left"
            >
              <BrandIcon
                name={expanded ? "navigation/chevron_down" : "navigation/chevron_right"}
                size={12}
                style={{ color: "var(--pi-ink-tertiary)" }}
              />
              <BrandIcon name="system/terminal" size={14} style={{ color: "var(--pi-accent)" }} />
              <span className="truncate text-[12.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("inspector.jobsTitle")}
              </span>
              <Badge tone={runningCount > 0 ? "accent" : "neutral"}>{jobs.length}</Badge>
            </button>

            {jobs.some((j) => j.status !== "running") && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  if (!client) return;
                  void client
                    .clearSettledSessionJobs(sessionId)
                    .then(() => refresh())
                    .catch((err) => {
                      useSessionStore.setState({
                        actionError: err instanceof Error ? err.message : "Failed to clear jobs",
                      });
                    });
                }}
              >
                {t("inspector.jobsClearSettled")}
              </Button>
            )}
          </div>

          {expanded && (
            <div className="mt-2 space-y-1.5 border-t pt-2" style={{ borderColor: "var(--pi-hairline)" }}>
              {jobs.map((job) => (
                <div
                  key={job.id}
                  className="flex items-center justify-between gap-2 rounded-[8px] px-2.5 py-1.5 text-[12px]"
                  style={{ background: "var(--pi-paper)" }}
                >
                  <div className="flex min-w-0 flex-1 items-center gap-2">
                    <span className="truncate font-medium" style={{ color: "var(--pi-ink)" }}>
                      {job.label || job.command}
                    </span>
                    <Badge
                      tone={
                        job.status === "running"
                          ? "accent"
                          : job.status === "failed"
                            ? "danger"
                            : "neutral"
                      }
                    >
                      {formatJobStatus(job.status, t)}
                    </Badge>
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        if (!client) return;
                        void client
                          .getSessionJobOutput(sessionId, job.id)
                          .then((detail) =>
                            setJobOutput({ label: job.label || job.command, detail }),
                          )
                          .catch((err) => {
                            useSessionStore.setState({
                              actionError:
                                err instanceof Error ? err.message : "Failed to load job output",
                            });
                          });
                      }}
                    >
                      {t("inspector.jobOutput")}
                    </Button>
                    {job.status === "running" && (
                      <IconButton
                        size="sm"
                        label={t("inspector.jobKill")}
                        disabled={busyId === job.id}
                        onClick={() => {
                          if (!client) return;
                          setBusyId(job.id);
                          void client
                            .killSessionJob(sessionId, job.id)
                            .then(() => refresh())
                            .finally(() => setBusyId(null));
                        }}
                      >
                        <BrandIcon name="action/stop" size={13} />
                      </IconButton>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
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
    </>
  );
}

import { useMemo, useState } from "react";
import { formatWorkerStatus } from "../api/formatters";
import type { HostWorkerDto } from "../api/types";
import { BrandIcon } from "../features/shared/BrandAssets";
import { useI18n } from "../i18n";
import { useConnectionStore } from "../stores/connectionStore";
import { useSuperStore } from "../stores/superStore";
import { Badge, Button, ConfirmDialog, IconButton } from "../ui";
import type { WorkbenchPanelContext } from "./registry";

function workerTone(status: string): "accent" | "warning" | "success" | "danger" | "neutral" {
  if (status === "running" || status === "working") return "accent";
  if (status === "waiting_input" || status === "waiting_approval" || status === "waiting") {
    return "warning";
  }
  if (status === "failed" || status === "error") return "danger";
  if (status === "done" || status === "completed" || status === "idle") return "success";
  return "neutral";
}

function workerPriority(w: HostWorkerDto): number {
  if (w.status === "waiting_input" || w.status === "waiting_approval" || w.status === "waiting") {
    return 0;
  }
  if (w.status === "running" || w.status === "working") return 1;
  if (w.status === "idle") return 2;
  return 3;
}

function isWorkerActiveStatus(status: string): boolean {
  return (
    status === "running" ||
    status === "working" ||
    status === "waiting_input" ||
    status === "waiting_approval" ||
    status === "waiting" ||
    status === "queued" ||
    status === "stalled"
  );
}

export default function SuperTeamPanel(_props: { context: WorkbenchPanelContext }) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const rawWorkers = useSuperStore((s) => s.state?.workers);
  const refreshState = useSuperStore((s) => s.refreshState);
  const stopWorker = useSuperStore((s) => s.stopWorker);
  const deleteWorker = useSuperStore((s) => s.deleteWorker);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);

  const [deleteTarget, setDeleteTarget] = useState<HostWorkerDto | null>(null);

  const workers = useMemo(() => {
    const list = rawWorkers ?? [];
    return [...list].sort((a, b) => {
      const pa = workerPriority(a);
      const pb = workerPriority(b);
      if (pa !== pb) return pa - pb;
      return b.updatedAt - a.updatedAt;
    });
  }, [rawWorkers]);

  const activeWorkers = useMemo(
    () => workers.filter((w) => isWorkerActiveStatus(w.status)),
    [workers],
  );

  const savedWorkers = useMemo(
    () => workers.filter((w) => !isWorkerActiveStatus(w.status)),
    [workers],
  );

  const renderWorkerCard = (w: HostWorkerDto) => {
    const isActive = isWorkerActiveStatus(w.status);
    return (
      <div
        key={w.workerId}
        className="rounded-[12px] p-3 transition-colors"
        style={{
          background: "var(--pi-surface)",
          border: "0.5px solid var(--pi-hairline)",
        }}
      >
        <button
          type="button"
          onClick={() => setSelectedWorkerId(w.workerId)}
          className="w-full space-y-1.5 text-left"
        >
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="truncate text-[13px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                {w.title || w.botName}
              </div>
              <div className="truncate text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {w.botName}
                {w.isRemote && w.deviceName ? ` · ${w.deviceName}` : ""}
              </div>
            </div>
            <Badge tone={workerTone(w.status)}>{formatWorkerStatus(w.status, t)}</Badge>
          </div>

          {(w.activeStepText || w.lastSummary || w.lastError) && (
            <div
              className="line-clamp-2 text-[12px] leading-[1.45]"
              style={{ color: w.lastError ? "var(--pi-danger)" : "var(--pi-ink-secondary)" }}
            >
              {w.activeStepText || w.lastError || w.lastSummary}
            </div>
          )}

          {w.steps.length > 0 && (
            <div className="space-y-1 pt-0.5">
              {w.steps.slice(-2).map((step, idx) => (
                <div
                  key={`${step.label}-${idx}`}
                  className="flex items-center justify-between gap-2 text-[11px]"
                  style={{ color: step.isError ? "var(--pi-danger)" : "var(--pi-ink-tertiary)" }}
                >
                  <span className="truncate">{step.label}</span>
                  <span className="shrink-0">{formatWorkerStatus(step.status, t)}</span>
                </div>
              ))}
            </div>
          )}
        </button>

        <div
          className="mt-2.5 flex items-center justify-between gap-1 pt-2"
          style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
        >
          <Button variant="ghost" size="sm" onClick={() => setSelectedWorkerId(w.workerId)}>
            {t("super.inspectWorker")}
          </Button>
          <div className="flex items-center gap-1">
            {isActive && (
              <IconButton
                size="sm"
                label={t("chat.stop")}
                onClick={() => {
                  if (client) void stopWorker(client, w.workerId);
                }}
              >
                <BrandIcon name="action/stop" size={13.5} />
              </IconButton>
            )}
            <IconButton size="sm" label={t("common.delete")} onClick={() => setDeleteTarget(w)}>
              <BrandIcon name="action/delete" size={13.5} />
            </IconButton>
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div
        className="flex h-[38px] shrink-0 items-center justify-between gap-2 px-3"
        style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
      >
        <span className="text-[12.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
          {t("super.workersDrawerTitle")} ({workers.length})
        </span>
        <IconButton
          size="sm"
          label={t("common.refresh")}
          onClick={() => {
            if (client) void refreshState(client);
          }}
        >
          <BrandIcon name="action/retry" size={13.5} />
        </IconButton>
      </div>

      <div data-scrollable className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
        {workers.length === 0 ? (
          <div
            className="rounded-[12px] px-3 py-8 text-center text-[12.5px] leading-[1.6] whitespace-pre-line"
            style={{
              background: "var(--pi-surface)",
              border: "0.5px solid var(--pi-hairline)",
              color: "var(--pi-ink-tertiary)",
            }}
          >
            {t("super.noWorkers")}
          </div>
        ) : (
          <>
            {activeWorkers.length > 0 && (
              <div className="space-y-2">
                <div
                  className="px-1 text-[11px] font-semibold tracking-wide"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  {t("super.workersActiveGroup")} ({activeWorkers.length})
                </div>
                {activeWorkers.map(renderWorkerCard)}
              </div>
            )}
            {savedWorkers.length > 0 && (
              <div className="space-y-2">
                <div
                  className="px-1 text-[11px] font-semibold tracking-wide"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  {t("super.workersSavedGroup")} ({savedWorkers.length})
                </div>
                {savedWorkers.map(renderWorkerCard)}
              </div>
            )}
          </>
        )}
      </div>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("super.deleteWorkerTitle")}
        description={t("super.deleteWorkerDesc", {
          name: deleteTarget?.title || deleteTarget?.botName || "",
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          const id = deleteTarget.workerId;
          setDeleteTarget(null);
          void deleteWorker(client, id);
        }}
      />
    </div>
  );
}

import { useState } from "react";
import type { HostPendingToolApprovalDto, ToolApprovalDecision } from "../../api/types";
import { useI18n } from "../../i18n";
import { Button, NoticeBar } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

export function ToolApprovalCard({
  approval,
  onResolve,
}: {
  approval: HostPendingToolApprovalDto;
  onResolve: (approvalId: string, decision: ToolApprovalDecision) => Promise<void>;
}) {
  const { t } = useI18n();
  const [submitting, setSubmitting] = useState<ToolApprovalDecision | null>(null);
  const [error, setError] = useState<string | null>(null);

  const isDestructive = approval.riskLevel === "destructive";
  const isOutsideWorkspace = approval.riskLevel === "outside_workspace";

  const titleKey = isDestructive
    ? "approval.titleDestructive"
    : isOutsideWorkspace
      ? "approval.titleOutsideWorkspace"
      : "approval.titleCommand";

  const reasonKey = isDestructive
    ? "approval.reasonDestructive"
    : isOutsideWorkspace
      ? "approval.reasonOutsideWorkspace"
      : "approval.reasonCommand";

  const accentColor = isDestructive
    ? "var(--pi-danger)"
    : isOutsideWorkspace
      ? "var(--pi-warning)"
      : "var(--pi-ink-secondary)";

  const previewTarget = approval.command || approval.path || approval.detail;

  const submit = async (decision: ToolApprovalDecision) => {
    if (submitting) return;
    setSubmitting(decision);
    setError(null);
    try {
      await onResolve(approval.id, decision);
    } catch (err) {
      setError(err instanceof Error ? err.message : t("approval.submitError"));
    } finally {
      setSubmitting(null);
    }
  };

  return (
    <div
      className="my-3 rounded-[14px] p-3.5"
      style={{
        background: "var(--pi-surface)",
        border: "0.5px solid var(--pi-hairline)",
        borderLeft: `2.5px solid ${accentColor}`,
      }}
    >
      <div className="flex items-start justify-between gap-2.5">
        <div className="flex items-center gap-2">
          <span
            className="flex h-6 w-6 shrink-0 items-center justify-center rounded-[7px]"
            style={{ background: "var(--pi-wash)", color: accentColor }}
          >
            <BrandIcon name="system/permissions" size={14} />
          </span>
          <div>
            <div className="text-[13px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {t(titleKey)}
            </div>
            <div className="text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t(reasonKey)}
            </div>
          </div>
        </div>
        <span
          className="shrink-0 rounded-[6px] px-2 py-0.5 font-mono text-[11px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
        >
          {approval.toolName}
        </span>
      </div>

      {previewTarget && (
        <pre
          className="mt-2.5 max-h-[140px] overflow-x-auto overflow-y-auto rounded-[10px] px-3 py-2 font-mono text-[12px] leading-[1.45] whitespace-pre-wrap break-all"
          style={{
            background: "var(--pi-code-bg)",
            border: "0.5px solid var(--pi-hairline)",
            color: "var(--pi-ink)",
          }}
        >
          {previewTarget}
        </pre>
      )}

      {isOutsideWorkspace && approval.workspaceRoot && (
        <div className="mt-1.5 truncate text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("approval.workspaceRoot", { path: approval.workspaceRoot })}
        </div>
      )}

      {error && (
        <div className="mt-2">
          <NoticeBar tone="danger" message={error} />
        </div>
      )}

      <div className="mt-3 flex flex-wrap items-center justify-end gap-2">
        <Button
          variant="ghost"
          size="sm"
          disabled={submitting !== null}
          onClick={() => void submit("deny")}
        >
          {t("approval.deny")}
        </Button>
        {!isDestructive && (
          <Button
            variant="secondary"
            size="sm"
            disabled={submitting !== null}
            onClick={() => void submit("allow_session")}
          >
            {t("approval.allowSession")}
          </Button>
        )}
        <Button
          variant={isDestructive ? "danger" : "primary"}
          size="sm"
          disabled={submitting !== null}
          onClick={() => void submit("allow_once")}
        >
          {t("approval.allowOnce")}
        </Button>
      </div>
    </div>
  );
}

import { useCallback, useEffect, useState } from "react";
import { formatDiagStatus } from "../../api/formatters";
import type { HostDoctorReportDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { Badge, Button, Card, Switch } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

function downloadMarkdown(filename: string, content: string): void {
  const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1200);
}

export function DiagnosticsSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const [probeMcp, setProbeMcp] = useState(false);
  const [report, setReport] = useState<HostDoctorReportDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runCheck = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      const next = await client.runDoctor(probeMcp);
      setReport(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Diagnostics failed");
    } finally {
      setLoading(false);
    }
  }, [client, probeMcp]);

  useEffect(() => {
    void runCheck();
  }, [runCheck]);

  return (
    <div className="space-y-4">
      <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="flex items-center gap-2.5 text-[13px]" style={{ color: "var(--pi-ink)" }}>
          <Switch checked={probeMcp} onCheckedChange={setProbeMcp} />
          <span>{t("diag.probeMcp")}</span>
        </div>

        <div className="flex items-center gap-1.5">
          <Button variant="secondary" size="sm" disabled={loading} onClick={() => void runCheck()}>
            <BrandIcon name="action/retry" size={14} />
            <span>{t("diag.runCheck")}</span>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={loading}
            onClick={() => {
              if (!client) return;
              void client
                .exportDoctorMarkdown(probeMcp)
                .then((md) => downloadMarkdown("superpi-diagnostics.md", md))
                .catch((err) => setError(err instanceof Error ? err.message : "Export failed"));
            }}
          >
            <BrandIcon name="action/download" size={14} />
            <span>{t("nav.export")}</span>
          </Button>
        </div>
      </Card>

      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void runCheck()}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {report && (
        <Card className="space-y-1.5 p-4 text-[12.5px]">
          <div className="flex justify-between gap-2">
            <span style={{ color: "var(--pi-ink-tertiary)" }}>{t("diag.version")}</span>
            <span className="font-medium" style={{ color: "var(--pi-ink)" }}>
              {report.version}
            </span>
          </div>
          <div className="flex justify-between gap-2">
            <span style={{ color: "var(--pi-ink-tertiary)" }}>{t("diag.os")}</span>
            <span className="font-medium" style={{ color: "var(--pi-ink)" }}>
              {report.os}
            </span>
          </div>
          <div className="flex justify-between gap-2">
            <span style={{ color: "var(--pi-ink-tertiary)" }}>{t("diag.homeDir")}</span>
            <span
              className="truncate font-medium"
              style={{ color: "var(--pi-ink)", fontFamily: "var(--pi-font-mono)" }}
            >
              {report.homeDir}
            </span>
          </div>
        </Card>
      )}

      {loading && !report ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("common.loading")}
        </div>
      ) : (
        <div className="space-y-2">
          {report?.checks.map((item, idx) => (
            <Card key={`${item.category}-${item.name}-${idx}`} className="space-y-1.5 p-3.5">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <Badge
                    tone={
                      item.status === "PASS"
                        ? "success"
                        : item.status === "WARN"
                          ? "warning"
                          : "danger"
                    }
                  >
                    {formatDiagStatus(item.status, t)}
                  </Badge>
                  <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {item.name}
                  </span>
                </div>
                <span className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {item.category}
                </span>
              </div>
              <div className="text-[12.5px] leading-[1.5]" style={{ color: "var(--pi-ink-secondary)" }}>
                {item.summary}
              </div>
              {item.fixHint && (
                <div
                  className="rounded-[8px] px-2.5 py-1.5 text-[12px]"
                  style={{ background: "var(--pi-paper)", color: "var(--pi-ink-secondary)" }}
                >
                  {item.fixHint}
                </div>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

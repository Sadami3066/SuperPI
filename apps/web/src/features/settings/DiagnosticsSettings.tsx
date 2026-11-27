import { useCallback, useEffect, useState } from "react";
import { formatDiagStatus } from "../../api/formatters";
import type { HostDoctorReportDto } from "../../api/types";
import { useI18n } from "../../i18n";
import {
  checkDesktopUpdates,
  clearDesktopCrashLogs,
  type DesktopCrashRecord,
  type DesktopUpdateCheckResult,
  listDesktopCrashLogs,
  openExternalUrl,
} from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, Card, NoticeBar, Skeleton, Switch } from "../../ui";
import { BrandIcon, PiBrandMark } from "../shared/BrandAssets";

const RELEASES_PAGE_URL = "https://github.com/Sadami3066/SuperPI/releases/latest";

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

export function DiagnosticsSettings({
  autoCheckUpdateTick = 0,
}: {
  autoCheckUpdateTick?: number;
}) {
  const { t, locale } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);
  const isElectron = Boolean(desktopEnv?.isElectron);

  const [probeMcp, setProbeMcp] = useState(false);
  const [report, setReport] = useState<HostDoctorReportDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [checkingUpdate, setCheckingUpdate] = useState(false);
  const [updateResult, setUpdateResult] = useState<DesktopUpdateCheckResult | null>(null);
  const [crashLogs, setCrashLogs] = useState<DesktopCrashRecord[]>([]);

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

  const handleCheckUpdate = useCallback(async () => {
    if (!isElectron) {
      await openExternalUrl(RELEASES_PAGE_URL);
      return;
    }
    setCheckingUpdate(true);
    try {
      const res = await checkDesktopUpdates();
      if (res) setUpdateResult(res);
    } finally {
      setCheckingUpdate(false);
    }
  }, [isElectron]);

  useEffect(() => {
    void runCheck();
  }, [runCheck]);

  useEffect(() => {
    if (!isElectron) return;
    void listDesktopCrashLogs().then(setCrashLogs).catch(() => setCrashLogs([]));
  }, [isElectron]);

  useEffect(() => {
    if (isElectron && autoCheckUpdateTick > 0) {
      void handleCheckUpdate();
    }
  }, [autoCheckUpdateTick, handleCheckUpdate, isElectron]);

  const currentVersion = desktopEnv?.version || report?.version || "2.0.3";

  return (
    <div className="space-y-6">
      <Card className="space-y-4 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <PiBrandMark size={38} dark={dark} />
            <div>
              <div className="text-[15px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                {t("app.name")}
              </div>
              <div className="text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("about.appTagline")} · {t("about.currentVersion", { version: currentVersion })}
              </div>
            </div>
          </div>

          <Button
            variant="secondary"
            size="sm"
            disabled={checkingUpdate}
            onClick={() => void handleCheckUpdate()}
          >
            {checkingUpdate ? t("about.checking") : t("about.checkUpdate")}
          </Button>
        </div>

        {updateResult?.status === "up_to_date" && (
          <NoticeBar
            tone="info"
            message={t("about.upToDate", {
              version: updateResult.latestVersion || updateResult.currentVersion,
            })}
          />
        )}

        {updateResult?.status === "error" && (
          <NoticeBar
            tone="danger"
            message={t("about.updateCheckFailed", { error: updateResult.error || "Network error" })}
            action={
              <Button variant="ghost" size="sm" onClick={() => void handleCheckUpdate()}>
                {t("conn.retry")}
              </Button>
            }
          />
        )}

        {updateResult?.status === "update_available" && (
          <div
            className="space-y-3 rounded-[10px] p-3.5"
            style={{ background: "var(--pi-paper)", border: "1px solid var(--pi-canvas-border)" }}
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <Badge tone="accent">
                  {t("about.updateAvailable", { version: updateResult.latestVersion || "" })}
                </Badge>
              </div>
              <div className="flex items-center gap-1.5">
                {updateResult.releaseUrl && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => void openExternalUrl(updateResult.releaseUrl!)}
                  >
                    {t("about.viewRelease")}
                  </Button>
                )}
                {updateResult.downloadUrl && (
                  <Button
                    variant="primary"
                    size="sm"
                    onClick={() => void openExternalUrl(updateResult.downloadUrl!)}
                  >
                    <BrandIcon name="action/download" size={13.5} />
                    <span>{t("about.downloadUpdate")}</span>
                  </Button>
                )}
              </div>
            </div>
            {updateResult.releaseNotes && (
              <pre
                className="max-h-36 overflow-y-auto whitespace-pre-wrap text-[12px] leading-[1.55]"
                style={{ color: "var(--pi-ink-secondary)", fontFamily: "inherit" }}
              >
                {updateResult.releaseNotes}
              </pre>
            )}
          </div>
        )}
      </Card>

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
        <div className="space-y-2" aria-busy="true">
          <Card className="space-y-2 p-4">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-3 w-3/4" />
          </Card>
          <Card className="space-y-2 p-4">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-2/3" />
          </Card>
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

      {isElectron && (
        <Card className="space-y-3 p-4">
          <div className="flex items-center justify-between gap-2">
            <div className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {t("about.crashesTitle")}
            </div>
            {crashLogs.length > 0 && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  void clearDesktopCrashLogs().then(() => setCrashLogs([]));
                }}
              >
                {t("about.crashesClear")}
              </Button>
            )}
          </div>
          {crashLogs.length === 0 ? (
            <div className="text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("about.crashesEmpty")}
            </div>
          ) : (
            <div className="space-y-1.5">
              {crashLogs.slice(0, 8).map((item) => (
                <div
                  key={item.id}
                  className="flex flex-wrap items-center justify-between gap-2 rounded-[8px] px-3 py-2 text-[12px]"
                  style={{ background: "var(--pi-paper)" }}
                >
                  <span style={{ color: "var(--pi-ink-secondary)" }}>
                    {t("about.crashesReason", { reason: item.reason })}
                  </span>
                  <span className="tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {new Date(item.timestamp).toLocaleString(locale)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </Card>
      )}

      <Card className="space-y-3.5 p-4 text-[12.5px]">
        <div className="space-y-1">
          <div className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("about.licensesTitle")}
          </div>
          <p className="leading-[1.6]" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("about.licensesBody")}
          </p>
        </div>

        <div
          className="space-y-1 pt-3"
          style={{ borderTop: "1px solid var(--pi-canvas-border)" }}
        >
          <div className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("about.privacyTitle")}
          </div>
          <p className="leading-[1.6]" style={{ color: "var(--pi-ink-secondary)" }}>
            {t("about.privacyBody")}
          </p>
        </div>
      </Card>
    </div>
  );
}

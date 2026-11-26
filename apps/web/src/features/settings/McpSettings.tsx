import { useCallback, useEffect, useRef, useState } from "react";
import { IconBolt, IconKey } from "@tabler/icons-react";
import { formatMcpStatus } from "../../api/formatters";
import type {
  HostMcpServerDto,
  HostOAuthStatusDto,
  HostUpsertMcpRequest,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { openExternalUrl } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  Dialog,
  IconButton,
  Input,
  Select,
  Switch,
  Textarea,
} from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

function formatKeyValueLines(rec?: Record<string, string>, sep = ": "): string {
  if (!rec) return "";
  return Object.entries(rec)
    .map(([k, v]) => `${k}${sep}${v}`)
    .join("\n");
}

function parseKeyValueLines(raw: string, sep: ":" | "="): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of raw.split(/\r?\n/)) {
    const idx = line.indexOf(sep);
    if (idx <= 0) continue;
    const k = line.slice(0, idx).trim();
    const v = line.slice(idx + 1).trim();
    if (k) out[k] = v;
  }
  return out;
}

function probeTone(state?: string | null): "success" | "warning" | "danger" | "neutral" {
  if (state === "READY" || state === "CONNECTED" || state === "OK") return "success";
  if (state === "AUTH_REQUIRED" || state === "NEEDS_AUTH" || state === "SKIPPED") return "warning";
  if (state === "FAILED" || state === "ERROR") return "danger";
  return "neutral";
}

export function McpSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const [servers, setServers] = useState<HostMcpServerDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingName, setEditingName] = useState<string | null>(null);
  const [nameDraft, setNameDraft] = useState("");
  const [transport, setTransport] = useState<"http" | "stdio">("http");
  const [urlDraft, setUrlDraft] = useState("");
  const [headersDraft, setHeadersDraft] = useState("");
  const [commandDraft, setCommandDraft] = useState("");
  const [argsDraft, setArgsDraft] = useState("");
  const [envDraft, setEnvDraft] = useState("");
  const [cwdDraft, setCwdDraft] = useState("");
  const [testResult, setTestResult] = useState<HostMcpServerDto | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const [importOpen, setImportOpen] = useState(false);
  const [importJson, setImportJson] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<HostMcpServerDto | null>(null);

  const [oauthServerName, setOauthServerName] = useState<string | null>(null);
  const [oauthState, setOauthState] = useState<HostOAuthStatusDto | null>(null);
  const [oauthInput, setOauthInput] = useState("");
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopOAuthPolling = useCallback(() => {
    if (pollTimerRef.current !== null) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  useEffect(() => {
    return () => stopOAuthPolling();
  }, [stopOAuthPolling]);

  const loadServers = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      const list = await client.listMcpServers();
      setServers(list);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load MCP servers");
    } finally {
      setLoading(false);
    }
  }, [client]);

  useEffect(() => {
    void loadServers();
  }, [loadServers]);

  const buildPayload = (): HostUpsertMcpRequest => {
    if (transport === "stdio") {
      const args = argsDraft
        .split(/\r?\n|\s+/)
        .map((s) => s.trim())
        .filter(Boolean);
      return {
        transport: "stdio",
        command: commandDraft.trim(),
        args,
        env: parseKeyValueLines(envDraft, "="),
        cwd: cwdDraft.trim() || undefined,
        enabled: true,
      };
    }
    return {
      transport: "http",
      url: urlDraft.trim(),
      headers: parseKeyValueLines(headersDraft, ":"),
      enabled: true,
    };
  };

  const openEditor = (existing?: HostMcpServerDto) => {
    setEditingName(existing ? existing.name : null);
    setNameDraft(existing?.name ?? "");
    setTransport(existing?.transport === "stdio" ? "stdio" : "http");
    setUrlDraft(existing?.url ?? "");
    setHeadersDraft(formatKeyValueLines(existing?.headers, ": "));
    setCommandDraft(existing?.command ?? "");
    setArgsDraft((existing?.args ?? []).join(" "));
    setEnvDraft(formatKeyValueLines(existing?.env, "="));
    setCwdDraft(existing?.cwd ?? "");
    setTestResult(null);
    setFormError(null);
    setEditorOpen(true);
  };

  const startOAuth = async (serverName: string) => {
    if (!client) return;
    stopOAuthPolling();
    setOauthServerName(serverName);
    setOauthInput("");
    try {
      const initial = await client.startMcpOAuth(serverName);
      setOauthState(initial);
      pollTimerRef.current = setInterval(() => {
        void client
          .getMcpOAuthStatus(serverName)
          .then((next) => {
            setOauthState(next);
            if (next.status === "completed") {
              stopOAuthPolling();
              void loadServers();
            } else if (next.status === "failed" || next.status === "cancelled") {
              stopOAuthPolling();
            }
          })
          .catch(() => stopOAuthPolling());
      }, 1200);
    } catch (err) {
      setOauthState({
        providerId: serverName,
        status: "failed",
        promptSecret: false,
        options: [],
        error: err instanceof Error ? err.message : "Failed to start OAuth",
      });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
          {t("mcp.summary", { count: servers.length })}
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <Button
            variant="ghost"
            size="sm"
            disabled={busy || servers.length === 0}
            onClick={() => {
              if (!client) return;
              setBusy(true);
              setError(null);
              void client
                .probeAllMcpServers()
                .then(setServers)
                .catch((err) => setError(err instanceof Error ? err.message : "Probe failed"))
                .finally(() => setBusy(false));
            }}
          >
            <IconBolt size={14} />
            <span>{t("mcp.probeAll")}</span>
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => {
              setImportJson("");
              setError(null);
              setImportOpen(true);
            }}
          >
            <BrandIcon name="file/code" size={14} />
            <span>{t("mcp.importJson")}</span>
          </Button>
          <Button variant="secondary" size="sm" onClick={() => openEditor()}>
            <BrandIcon name="action/add" size={14} />
            <span>{t("mcp.addServer")}</span>
          </Button>
        </div>
      </div>

      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadServers()}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {loading && servers.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("common.loading")}
        </div>
      ) : servers.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("mcp.empty")}
        </div>
      ) : (
        <div className="space-y-2.5">
          {servers.map((srv) => (
            <Card key={srv.name} className="space-y-2 p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                      {srv.name}
                    </span>
                    <Badge tone="neutral">{srv.transport.toUpperCase()}</Badge>
                    {srv.probeState && (
                      <Badge tone={probeTone(srv.probeState)}>
                        {formatMcpStatus(srv.probeState, t)}
                        {typeof srv.toolCount === "number" ? ` · ${srv.toolCount}` : ""}
                      </Badge>
                    )}
                    {srv.hasAuthToken && <Badge tone="success">OAuth</Badge>}
                  </div>
                  <div
                    className="truncate text-[12px]"
                    style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                  >
                    {srv.target}
                  </div>
                  {srv.error && (
                    <div className="text-[12px]" style={{ color: "var(--pi-danger)" }}>
                      {srv.error}
                    </div>
                  )}
                </div>

                <Switch
                  checked={srv.enabled}
                  disabled={busy}
                  onCheckedChange={(checked) => {
                    if (!client) return;
                    setBusy(true);
                    void client
                      .toggleMcpServer(srv.name, checked)
                      .then(() => loadServers())
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                />
              </div>

              <div
                className="flex flex-wrap items-center justify-end gap-1.5 pt-2"
                style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
              >
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    if (!client) return;
                    setBusy(true);
                    void client
                      .probeMcpServer(srv.name)
                      .then((updated) =>
                        setServers((prev) =>
                          prev.map((item) => (item.name === updated.name ? updated : item)),
                        ),
                      )
                      .catch((err) => setError(err instanceof Error ? err.message : "Probe failed"))
                      .finally(() => setBusy(false));
                  }}
                >
                  <BrandIcon name="action/retry" size={13.5} />
                  <span>{t("mcp.probe")}</span>
                </Button>

                {srv.transport === "http" && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => void startOAuth(srv.name)}
                  >
                    <IconKey size={13.5} />
                    <span>OAuth</span>
                  </Button>
                )}

                <Button variant="ghost" size="sm" onClick={() => openEditor(srv)}>
                  {t("common.edit")}
                </Button>

                <IconButton
                  size="sm"
                  label={t("common.delete")}
                  onClick={() => setDeleteTarget(srv)}
                >
                  <BrandIcon name="action/delete" size={14} />
                </IconButton>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Dialog
        open={editorOpen}
        onOpenChange={setEditorOpen}
        title={editingName ? `${t("mcp.editServer")} · ${editingName}` : t("mcp.addServer")}
        maxWidthClass="max-w-[540px]"
      >
        <div className="space-y-3">
          {!editingName && (
            <div className="space-y-1">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("mcp.serverName")}
              </label>
              <Input
                value={nameDraft}
                onChange={(e) => setNameDraft(e.target.value)}
                placeholder="filesystem"
              />
            </div>
          )}

          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("mcp.transport")}
            </label>
            <Select
              value={transport}
              onValueChange={(v) => setTransport(v as "http" | "stdio")}
              options={[
                { value: "http", label: "HTTP" },
                { value: "stdio", label: "Stdio" },
              ]}
              className="w-full"
            />
          </div>

          {transport === "http" ? (
            <>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  URL
                </label>
                <Input
                  value={urlDraft}
                  onChange={(e) => setUrlDraft(e.target.value)}
                  placeholder="https://mcp.example.com/sse"
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("mcp.headersLabel")}
                </label>
                <Textarea
                  rows={3}
                  value={headersDraft}
                  onChange={(e) => setHeadersDraft(e.target.value)}
                  placeholder="Authorization: Bearer ..."
                  style={{ fontFamily: "var(--pi-font-mono)" }}
                />
              </div>
            </>
          ) : (
            <>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("mcp.commandLabel")}
                </label>
                <Input
                  value={commandDraft}
                  onChange={(e) => setCommandDraft(e.target.value)}
                  placeholder="npx"
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("mcp.argsLabel")}
                </label>
                <Input
                  value={argsDraft}
                  onChange={(e) => setArgsDraft(e.target.value)}
                  placeholder="-y @modelcontextprotocol/server-filesystem ."
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("mcp.envLabel")}
                </label>
                <Textarea
                  rows={2}
                  value={envDraft}
                  onChange={(e) => setEnvDraft(e.target.value)}
                  placeholder="API_KEY=..."
                  style={{ fontFamily: "var(--pi-font-mono)" }}
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("mcp.cwdLabel")}
                </label>
                <Input value={cwdDraft} onChange={(e) => setCwdDraft(e.target.value)} />
              </div>
            </>
          )}

          {testResult && (
            <div
              className="rounded-[8px] px-3 py-2 text-[12px]"
              style={{ background: "var(--pi-paper)" }}
            >
              <div className="flex items-center gap-2">
                <Badge tone={probeTone(testResult.probeState)}>
                  {formatMcpStatus(testResult.probeState ?? "", t)}
                </Badge>
                {typeof testResult.toolCount === "number" && (
                  <span>{t("mcp.toolsCount", { count: testResult.toolCount })}</span>
                )}
              </div>
              {testResult.error && (
                <div className="mt-1" style={{ color: "var(--pi-danger)" }}>
                  {testResult.error}
                </div>
              )}
            </div>
          )}

          {formError && (
            <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
              {formError}
            </div>
          )}

          <div className="flex items-center justify-between gap-2 pt-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={busy}
              onClick={() => {
                if (!client) return;
                setBusy(true);
                setFormError(null);
                void client
                  .testMcpServer(buildPayload())
                  .then(setTestResult)
                  .catch((err) => setFormError(err instanceof Error ? err.message : "Test failed"))
                  .finally(() => setBusy(false));
              }}
            >
              {t("mcp.testConnection")}
            </Button>

            <div className="flex gap-2">
              <Button variant="ghost" onClick={() => setEditorOpen(false)}>
                {t("common.cancel")}
              </Button>
              <Button
                variant="primary"
                disabled={busy || (!editingName && !nameDraft.trim())}
                onClick={() => {
                  const targetName = (editingName || nameDraft).trim();
                  if (!client || !targetName) return;
                  setBusy(true);
                  setFormError(null);
                  void client
                    .upsertMcpServer(targetName, buildPayload())
                    .then(() => {
                      setEditorOpen(false);
                      return loadServers();
                    })
                    .catch((err) => setFormError(err instanceof Error ? err.message : "Save failed"))
                    .finally(() => setBusy(false));
                }}
              >
                {t("common.save")}
              </Button>
            </div>
          </div>
        </div>
      </Dialog>

      <Dialog open={importOpen} onOpenChange={setImportOpen} title={t("mcp.importJson")}>
        <div className="space-y-3">
          <Textarea
            rows={8}
            value={importJson}
            onChange={(e) => setImportJson(e.target.value)}
            placeholder='{"mcpServers": {...}}'
            style={{ fontFamily: "var(--pi-font-mono)" }}
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setImportOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !importJson.trim()}
              onClick={() => {
                if (!client) return;
                setBusy(true);
                setError(null);
                void client
                  .importMcpRaw(importJson.trim())
                  .then(() => {
                    setImportOpen(false);
                    return loadServers();
                  })
                  .catch((err) => setError(err instanceof Error ? err.message : "Import failed"))
                  .finally(() => setBusy(false));
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={Boolean(oauthServerName)}
        onOpenChange={(v) => {
          if (!v) {
            stopOAuthPolling();
            if (client && oauthServerName && oauthState?.status !== "completed") {
              void client.cancelMcpOAuth(oauthServerName).catch(() => {});
            }
            setOauthServerName(null);
            setOauthState(null);
          }
        }}
        title={oauthServerName ? `OAuth · ${oauthServerName}` : "OAuth"}
      >
        {oauthServerName && (
          <div className="space-y-3 text-[13px]">
            {oauthState?.url && (
              <Button
                variant="secondary"
                size="sm"
                onClick={() => void openExternalUrl(oauthState.url!)}
              >
                <BrandIcon name="navigation/external_link" size={14} />
                <span>{t("providers.oauthOpen")}</span>
              </Button>
            )}
            {oauthState?.instructions && (
              <p style={{ color: "var(--pi-ink-secondary)" }}>{oauthState.instructions}</p>
            )}
            {(oauthState?.status === "awaiting_browser" || oauthState?.status === "awaiting_prompt") && (
              <div className="flex gap-2">
                <Input
                  value={oauthInput}
                  onChange={(e) => setOauthInput(e.target.value)}
                  placeholder={oauthState.promptPlaceholder || "http://127.0.0.1:.../callback?code=..."}
                />
                <Button
                  variant="primary"
                  size="sm"
                  onClick={() => {
                    if (!client || !oauthInput.trim()) return;
                    void client
                      .submitMcpOAuth(oauthServerName, oauthInput.trim())
                      .then(setOauthState)
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
                  }}
                >
                  {t("common.confirm")}
                </Button>
              </div>
            )}
            {oauthState?.status === "completed" && (
              <Badge tone="success">{t("providers.configured")}</Badge>
            )}
            {oauthState?.error && (
              <div style={{ color: "var(--pi-danger)" }}>{oauthState.error}</div>
            )}
            <div className="flex justify-end gap-2 pt-2">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  if (!client) return;
                  void client
                    .logoutMcpOAuth(oauthServerName)
                    .then(() => {
                      setOauthServerName(null);
                      void loadServers();
                    })
                    .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
                }}
              >
                {t("providers.oauthLogout")}
              </Button>
            </div>
          </div>
        )}
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("mcp.deleteTitle")}
        description={deleteTarget?.name || ""}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          const serverName = deleteTarget.name;
          setDeleteTarget(null);
          void client
            .deleteMcpServer(serverName)
            .then(() => loadServers())
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
        }}
      />
    </div>
  );
}

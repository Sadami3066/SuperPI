import { useEffect, useMemo, useRef, useState } from "react";
import { IconKey } from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import type {
  HostCustomProviderDto,
  HostOAuthStatusDto,
  HostProviderDto,
  HostWebSearchProviderDto,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { openExternalUrl } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, Card, Dialog, Input, Select } from "../../ui";
import { BrandIcon, ProviderIcon } from "../shared/BrandAssets";

export function ProvidersSettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const dark = useSettingsStore((s) => s.dark);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);
  const customProviders = useSettingsStore((s) => s.customProviders);
  const patchHostSettings = useSettingsStore((s) => s.patchHostSettings);
  const saveProviderKey = useSettingsStore((s) => s.saveProviderKey);
  const clearProviderKey = useSettingsStore((s) => s.clearProviderKey);
  const saveCustomProvider = useSettingsStore((s) => s.saveCustomProvider);
  const deleteCustomProvider = useSettingsStore((s) => s.deleteCustomProvider);
  const logoutOAuth = useSettingsStore((s) => s.logoutOAuth);
  const refreshSettingsData = useSettingsStore((s) => s.refreshSettingsData);

  const [query, setQuery] = useState("");
  const [editingProvider, setEditingProvider] = useState<HostProviderDto | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [oauthState, setOauthState] = useState<HostOAuthStatusDto | null>(null);
  const [oauthInput, setOauthInput] = useState("");
  const [customModalOpen, setCustomModalOpen] = useState(false);
  const [editingCustom, setEditingCustom] = useState<HostCustomProviderDto | null>(null);
  const [customName, setCustomName] = useState("");
  const [customProtocol, setCustomProtocol] = useState("openai-completions");
  const [customUrl, setCustomUrl] = useState("");
  const [customKey, setCustomKey] = useState("");
  const [customModelsText, setCustomModelsText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchProviders, setSearchProviders] = useState<HostWebSearchProviderDto[]>([]);
  const [editingSearch, setEditingSearch] = useState<HostWebSearchProviderDto | null>(null);
  const [searchKeyDraft, setSearchKeyDraft] = useState("");
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (!client) return;
    void client
      .listWebSearchProviders()
      .then(setSearchProviders)
      .catch(() => setSearchProviders([]));
  }, [client]);

  const stopOAuthPolling = () => {
    if (pollTimerRef.current !== null) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  useEffect(() => {
    return () => stopOAuthPolling();
  }, []);

  const configuredProviders = useMemo(
    () => providers.filter((p) => p.configured && p.models.length > 0),
    [providers],
  );

  const globalModelValue = hostSettings
    ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}`
    : "";

  const globalModelOptions = useMemo(() => {
    const list: Array<{ value: string; label: string }> = [];
    for (const p of configuredProviders) {
      for (const m of p.models) {
        list.push({ value: `${p.id}/${m.id}`, label: `${p.name} · ${m.displayName || m.id}` });
      }
    }
    return list;
  }, [configuredProviders]);

  const filteredProviders = useMemo(() => {
    const q = query.trim().toLowerCase();
    const sorted = providers.toSorted((a, b) => {
      if (a.configured !== b.configured) return a.configured ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    if (!q) return sorted;
    return sorted.filter(
      (p) => p.name.toLowerCase().includes(q) || p.id.toLowerCase().includes(q),
    );
  }, [providers, query]);

  const startOAuthFlow = async (providerId: string) => {
    if (!client) return;
    stopOAuthPolling();
    setError(null);
    try {
      const st = await client.startOAuth(providerId);
      setOauthState(st);
      pollTimerRef.current = setInterval(() => {
        void client
          .getOAuthStatus(providerId)
          .then((next) => {
            setOauthState(next);
            if (next.status === "completed") {
              stopOAuthPolling();
              void refreshSettingsData(client);
            } else if (next.status === "failed" || next.status === "cancelled") {
              stopOAuthPolling();
            }
          })
          .catch(() => {
            stopOAuthPolling();
          });
      }, 1200);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start sign in");
    }
  };

  const openCustomEditor = (existing?: HostCustomProviderDto) => {
    setEditingCustom(existing ?? null);
    setCustomName(existing?.name ?? "");
    setCustomProtocol(existing?.api ?? "openai-completions");
    setCustomUrl(existing?.baseUrl ?? "");
    setCustomKey("");
    setCustomModelsText(existing?.models.map((m) => m.id).join(", ") ?? "");
    setError(null);
    setCustomModalOpen(true);
  };

  return (
    <div className="space-y-5">
      {hostSettings && globalModelOptions.length > 0 && (
        <Card className="space-y-3 p-5">
          <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("providers.globalDefault")}
          </div>
          <Select
            value={globalModelValue}
            options={globalModelOptions}
            className="w-full"
            onValueChange={(val) => {
              if (!client) return;
              const slash = val.indexOf("/");
              if (slash < 1) return;
              const defaultProvider = val.slice(0, slash);
              const defaultModel = val.slice(slash + 1);
              const thinking = normalizeThinkingLevel(hostSettings.defaultThinkingLevel);
              void patchHostSettings(client, {
                defaultProvider,
                defaultModel,
                defaultThinkingLevel: thinking.toUpperCase(),
              });
            }}
          />
        </Card>
      )}

      <Card className="space-y-3 p-5">
        <div className="flex items-center justify-between gap-2">
          <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("providers.custom")}
          </div>
          <Button variant="secondary" size="sm" onClick={() => openCustomEditor()}>
            <BrandIcon name="action/add" size={14} />
            <span>{t("providers.addCustom")}</span>
          </Button>
        </div>

        {customProviders.length > 0 && (
          <div className="space-y-2">
            {customProviders.map((cp) => (
              <div
                key={cp.id}
                className="flex items-center justify-between gap-3 rounded-[10px] px-3 py-2"
                style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
              >
                <div className="min-w-0">
                  <div className="truncate text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {cp.name}
                  </div>
                  <div className="truncate text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {cp.baseUrl}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Button variant="ghost" size="sm" onClick={() => openCustomEditor(cp)}>
                    {t("common.edit")}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      if (client) void deleteCustomProvider(client, cp.id);
                    }}
                  >
                    <BrandIcon name="action/delete" size={14} />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      {searchProviders.length > 0 && (
        <Card className="space-y-3 p-5">
          <div className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("providers.webSearch")}
          </div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {searchProviders.map((sp) => (
              <button
                key={sp.id}
                type="button"
                onClick={() => {
                  setEditingSearch(sp);
                  setSearchKeyDraft("");
                  setError(null);
                }}
                className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-left transition-colors hover:bg-[var(--pi-hover)]"
                style={{
                  background: "var(--pi-paper)",
                  border: "0.5px solid var(--pi-hairline)",
                }}
              >
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {sp.name}
                  </div>
                  {sp.keyHint && (
                    <div
                      className="truncate text-[11px]"
                      style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                    >
                      {sp.keyHint}
                    </div>
                  )}
                </div>
                {sp.configured && <Badge tone="success">{t("providers.configured")}</Badge>}
              </button>
            ))}
          </div>
        </Card>
      )}

      <Card className="space-y-3.5 p-5">
        <div className="relative">
          <BrandIcon
            name="action/search"
            size={14}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
            style={{ color: "var(--pi-ink-tertiary)" }}
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("providers.search")}
            className="pl-9"
          />
        </div>

        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          {filteredProviders.map((p) => (
            <button
              key={p.id}
              type="button"
              onClick={() => {
                setEditingProvider(p);
                setApiKeyDraft("");
                setOauthState(null);
                setOauthInput("");
                setError(null);
                stopOAuthPolling();
              }}
              className="flex items-center justify-between gap-2.5 rounded-[12px] px-3 py-2.5 text-left transition-colors hover:bg-[var(--pi-hover)]"
              style={{
                background: "var(--pi-paper)",
                border: "0.5px solid var(--pi-hairline)",
              }}
            >
              <div className="flex min-w-0 items-center gap-2.5">
                <ProviderIcon providerId={p.id} name={p.name} dark={dark} size={22} />
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {p.name}
                  </div>
                  {p.keyHint && (
                    <div
                      className="truncate text-[11px]"
                      style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                    >
                      {p.keyHint}
                    </div>
                  )}
                </div>
              </div>
              {p.configured && <Badge tone="success">{t("providers.configured")}</Badge>}
            </button>
          ))}
        </div>
      </Card>

      <Dialog
        open={Boolean(editingProvider)}
        onOpenChange={(v) => {
          if (!v) {
            stopOAuthPolling();
            if (client && editingProvider && oauthState && oauthState.status !== "completed") {
              void client.cancelOAuth(editingProvider.id).catch(() => {});
            }
            setEditingProvider(null);
            setOauthState(null);
          }
        }}
        title={editingProvider?.name}
      >
        {editingProvider && (
          <div className="space-y-4">
            {editingProvider.supportsApiKey && (
              <div className="space-y-2.5">
                <div className="flex items-center gap-1.5 text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  <IconKey size={14} stroke={1.7} />
                  <span>{t("providers.apiKey")}</span>
                </div>
                <Input
                  type="password"
                  value={apiKeyDraft}
                  onChange={(e) => setApiKeyDraft(e.target.value)}
                  placeholder={editingProvider.keyHint || t("providers.keyPlaceholder")}
                />
                <div className="flex justify-end gap-2">
                  {editingProvider.configured && (
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => {
                        if (!client) return;
                        setBusy(true);
                        void clearProviderKey(client, editingProvider.id)
                          .then(() => setEditingProvider(null))
                          .finally(() => setBusy(false));
                      }}
                    >
                      {t("providers.clearKey")}
                    </Button>
                  )}
                  <Button
                    variant="primary"
                    size="sm"
                    disabled={busy || !apiKeyDraft.trim()}
                    onClick={() => {
                      if (!client || !apiKeyDraft.trim()) return;
                      setBusy(true);
                      void saveProviderKey(client, editingProvider.id, apiKeyDraft.trim())
                        .then(() => setEditingProvider(null))
                        .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                        .finally(() => setBusy(false));
                    }}
                  >
                    {t("providers.saveKey")}
                  </Button>
                </div>
              </div>
            )}

            {editingProvider.hasOAuth && (
              <div
                className="space-y-2.5 pt-3"
                style={{
                  borderTop: editingProvider.supportsApiKey
                    ? "0.5px solid var(--pi-hairline)"
                    : "none",
                }}
              >
                <div className="text-[13px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {t("providers.oauth")}
                </div>
                {!oauthState || oauthState.status === "idle" || oauthState.status === "cancelled" ? (
                  <div className="flex items-center gap-2">
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => void startOAuthFlow(editingProvider.id)}
                    >
                      {t("providers.oauthOpen")}
                    </Button>
                    {editingProvider.configured && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => {
                          if (!client) return;
                          void logoutOAuth(client, editingProvider.id).then(() =>
                            setEditingProvider(null),
                          );
                        }}
                      >
                        {t("providers.oauthLogout")}
                      </Button>
                    )}
                  </div>
                ) : (
                  <div className="space-y-2 text-[12.5px]">
                    {oauthState.url && (
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void openExternalUrl(oauthState.url!)}
                      >
                        <BrandIcon name="navigation/external_link" size={14} />
                        <span>{t("providers.oauthOpen")}</span>
                      </Button>
                    )}
                    {oauthState.instructions && (
                      <p style={{ color: "var(--pi-ink-secondary)" }}>{oauthState.instructions}</p>
                    )}
                    {oauthState.status === "awaiting_prompt" && (
                      <div className="flex gap-2">
                        <Input
                          type={oauthState.promptSecret ? "password" : "text"}
                          value={oauthInput}
                          onChange={(e) => setOauthInput(e.target.value)}
                          placeholder={oauthState.promptPlaceholder || oauthState.promptLabel || ""}
                        />
                        <Button
                          variant="primary"
                          size="sm"
                          onClick={() => {
                            if (!client || !oauthInput.trim()) return;
                            void client
                              .submitOAuth(editingProvider.id, oauthInput.trim())
                              .then(setOauthState);
                          }}
                        >
                          {t("common.confirm")}
                        </Button>
                      </div>
                    )}
                    {oauthState.status === "awaiting_select" && oauthState.options.length > 0 && (
                      <div className="space-y-1">
                        {oauthState.options.map((opt) => (
                          <Button
                            key={opt.id}
                            variant="secondary"
                            size="sm"
                            className="w-full justify-start"
                            onClick={() => {
                              if (!client) return;
                              void client.submitOAuth(editingProvider.id, opt.id).then(setOauthState);
                            }}
                          >
                            {opt.label}
                          </Button>
                        ))}
                      </div>
                    )}
                    {(oauthState.status === "starting" ||
                      oauthState.status === "awaiting_browser" ||
                      oauthState.status === "working") && (
                      <div style={{ color: "var(--pi-ink-tertiary)" }}>{t("providers.oauthWorking")}</div>
                    )}
                    {oauthState.error && (
                      <div style={{ color: "var(--pi-danger)" }}>{oauthState.error}</div>
                    )}
                  </div>
                )}
              </div>
            )}

            {error && (
              <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
                {error}
              </div>
            )}
          </div>
        )}
      </Dialog>

      <Dialog
        open={customModalOpen}
        onOpenChange={setCustomModalOpen}
        title={t("providers.addCustom")}
      >
        <div className="space-y-3">
          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("providers.customName")}
            </label>
            <Input value={customName} onChange={(e) => setCustomName(e.target.value)} />
          </div>
          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("providers.customProtocol")}
            </label>
            <Select
              value={customProtocol}
              onValueChange={setCustomProtocol}
              options={[
                { value: "openai-completions", label: "OpenAI Compatible" },
                { value: "anthropic-messages", label: "Anthropic Messages" },
              ]}
              className="w-full"
            />
          </div>
          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("providers.customBaseUrl")}
            </label>
            <Input
              value={customUrl}
              onChange={(e) => setCustomUrl(e.target.value)}
              placeholder="https://api.openai.com/v1"
            />
          </div>
          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("providers.apiKey")}
            </label>
            <Input
              type="password"
              value={customKey}
              onChange={(e) => setCustomKey(e.target.value)}
              placeholder={editingCustom?.keyHint || t("providers.keyPlaceholder")}
            />
          </div>
          <div className="space-y-1">
            <div className="flex items-center justify-between">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("providers.customModels")}
              </label>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy || !customUrl.trim()}
                onClick={() => {
                  if (!client || !customUrl.trim()) return;
                  setBusy(true);
                  void client
                    .discoverCustomModels({
                      baseUrl: customUrl.trim(),
                      apiKey: customKey.trim(),
                      api: customProtocol,
                    })
                    .then((res) => setCustomModelsText(res.models.join(", ")))
                    .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                    .finally(() => setBusy(false));
                }}
              >
                {t("providers.discover")}
              </Button>
            </div>
            <Input
              value={customModelsText}
              onChange={(e) => setCustomModelsText(e.target.value)}
              placeholder="gpt-4o, deepseek-chat"
            />
          </div>
          {error && (
            <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
              {error}
            </div>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setCustomModalOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy}
              onClick={() => {
                if (!client) return;
                const models = customModelsText
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean)
                  .map((id) => ({
                    id,
                    name: id,
                    supportsImages: false,
                    reasoning: false,
                    thinkingLevels: ["OFF"],
                  }));
                setBusy(true);
                void saveCustomProvider(client, {
                  id: editingCustom?.id,
                  name: customName.trim(),
                  api: customProtocol,
                  baseUrl: customUrl.trim(),
                  apiKey: customKey.trim(),
                  models,
                })
                  .then(() => setCustomModalOpen(false))
                  .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                  .finally(() => setBusy(false));
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={Boolean(editingSearch)}
        onOpenChange={(v) => {
          if (!v) setEditingSearch(null);
        }}
        title={editingSearch?.name}
      >
        {editingSearch && (
          <div className="space-y-3">
            <Input
              type="password"
              value={searchKeyDraft}
              onChange={(e) => setSearchKeyDraft(e.target.value)}
              placeholder={editingSearch.keyHint || t("providers.keyPlaceholder")}
            />
            <div className="flex justify-end gap-2">
              {editingSearch.configured && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    if (!client) return;
                    setBusy(true);
                    void client
                      .deleteWebSearchKey(editingSearch.id)
                      .then(() => client.listWebSearchProviders())
                      .then((list) => {
                        setSearchProviders(list);
                        setEditingSearch(null);
                      })
                      .finally(() => setBusy(false));
                  }}
                >
                  {t("providers.clearKey")}
                </Button>
              )}
              <Button
                variant="primary"
                size="sm"
                disabled={busy || !searchKeyDraft.trim()}
                onClick={() => {
                  if (!client || !searchKeyDraft.trim()) return;
                  setBusy(true);
                  void client
                    .saveWebSearchKey(editingSearch.id, searchKeyDraft.trim())
                    .then(() => client.listWebSearchProviders())
                    .then((list) => {
                      setSearchProviders(list);
                      setEditingSearch(null);
                    })
                    .finally(() => setBusy(false));
                }}
              >
                {t("providers.saveKey")}
              </Button>
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}

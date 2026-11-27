import { useEffect, useMemo, useRef, useState } from "react";
import { IconCheck, IconFolderPlus, IconKey } from "@tabler/icons-react";
import type { HostOAuthStatusDto, HostProviderDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { openExternalUrl, pickWorkspaceDirectory } from "../../platform/bridge";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, Card, Input } from "../../ui";
import { BrandIcon, PiBrandMark, ProviderIcon } from "../shared/BrandAssets";
import { WindowControls } from "../shell/WindowControls";

const FEATURED_PROVIDER_IDS = [
  "openai",
  "anthropic",
  "deepseek",
  "google",
  "openrouter",
  "moonshot",
  "qwen",
  "zai",
];

function folderDisplayName(dirPath: string): string {
  const clean = dirPath.replace(/[\\/]+$/, "");
  const parts = clean.split(/[\\/]/);
  return parts[parts.length - 1] || clean || "Workspace";
}

export function OnboardingFlow({ onComplete }: { onComplete: () => void }) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const dark = useSettingsStore((s) => s.dark);
  const providers = useSettingsStore((s) => s.providers);
  const saveProviderKey = useSettingsStore((s) => s.saveProviderKey);
  const refreshSettingsData = useSettingsStore((s) => s.refreshSettingsData);
  const bots = useBotStore((s) => s.bots);
  const createBot = useBotStore((s) => s.createBot);
  const startDraftSession = useSessionStore((s) => s.startDraftSession);

  const isElectron = Boolean(desktopEnv?.isElectron);
  const isFrameless = Boolean(isElectron && desktopEnv?.platform !== "darwin");
  const defaultBot = bots.find((b) => b.isHome) ?? bots[0] ?? null;

  const [step, setStep] = useState<1 | 2>(1);
  const [pickedBotName, setPickedBotName] = useState<string | null>(null);
  const [pickingFolder, setPickingFolder] = useState(false);

  const [query, setQuery] = useState("");
  const [selectedProvider, setSelectedProvider] = useState<HostProviderDto | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [savingKey, setSavingKey] = useState(false);
  const [oauthState, setOauthState] = useState<HostOAuthStatusDto | null>(null);
  const [oauthInput, setOauthInput] = useState("");
  const [error, setError] = useState<string | null>(null);
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopOAuthPolling = () => {
    if (pollTimerRef.current !== null) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  };

  useEffect(() => {
    return () => stopOAuthPolling();
  }, []);

  const hasConfiguredProvider = useMemo(
    () => providers.some((p) => p.configured),
    [providers],
  );

  const visibleProviders = useMemo(() => {
    const q = query.trim().toLowerCase();
    const sorted = [...providers].sort((a, b) => {
      if (a.configured !== b.configured) return a.configured ? -1 : 1;
      const aRank = FEATURED_PROVIDER_IDS.indexOf(a.id);
      const bRank = FEATURED_PROVIDER_IDS.indexOf(b.id);
      const aScore = aRank >= 0 ? aRank : 999;
      const bScore = bRank >= 0 ? bRank : 999;
      if (aScore !== bScore) return aScore - bScore;
      return a.name.localeCompare(b.name);
    });
    if (!q) return sorted.slice(0, 8);
    return sorted.filter(
      (p) => p.name.toLowerCase().includes(q) || p.id.toLowerCase().includes(q),
    );
  }, [providers, query]);

  const handlePickWorkspace = async () => {
    if (!client || pickingFolder) return;
    setPickingFolder(true);
    setError(null);
    try {
      const dir = await pickWorkspaceDirectory(t("onboarding.wsPickFolder"));
      if (!dir) return;
      const name = folderDisplayName(dir);
      const created = await createBot(client, { dir, name });
      startDraftSession(created.id);
      setPickedBotName(created.name);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to add workspace");
    } finally {
      setPickingFolder(false);
    }
  };

  const handleSaveKey = async () => {
    if (!client || !selectedProvider || !apiKeyDraft.trim() || savingKey) return;
    setSavingKey(true);
    setError(null);
    try {
      await saveProviderKey(client, selectedProvider.id, apiKeyDraft.trim());
      setApiKeyDraft("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to save key");
    } finally {
      setSavingKey(false);
    }
  };

  const handleStartOAuth = async (providerId: string) => {
    if (!client) return;
    stopOAuthPolling();
    setError(null);
    try {
      const st = await client.startOAuth(providerId);
      setOauthState(st);
      if (st.url) {
        void openExternalUrl(st.url);
      }
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
          .catch(() => stopOAuthPolling());
      }, 1200);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to start sign in");
    }
  };

  return (
    <div
      className="flex h-screen w-screen flex-col"
      style={{ background: "var(--pi-paper)", color: "var(--pi-ink)" }}
    >
      <header
        className="pi-drag-region flex h-[44px] shrink-0 items-center justify-between px-4"
        style={{ paddingRight: isFrameless ? 0 : 16 }}
      >
        <div className="pi-no-drag flex items-center gap-2">
          <PiBrandMark size={22} dark={dark} />
          <span className="text-[13px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("app.name")}
          </span>
          <span className="text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            · {t("onboarding.stepLabel", { step })}
          </span>
        </div>

        <div className="pi-no-drag flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={onComplete}>
            {t("onboarding.skip")}
          </Button>
          {isFrameless && <WindowControls />}
        </div>
      </header>

      <div className="flex min-h-0 flex-1 items-center justify-center overflow-y-auto p-6">
        <Card className="w-full max-w-[540px] space-y-5 p-6">
          {step === 1 ? (
            <>
              <div className="space-y-1.5">
                <h1 className="text-[20px] font-semibold tracking-[-0.02em]">
                  {t("onboarding.wsTitle")}
                </h1>
                <p className="text-[13px] leading-[1.6]" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("onboarding.wsDesc")}
                </p>
              </div>

              {defaultBot && (
                <div
                  className="rounded-[12px] px-4 py-3 text-[12.5px]"
                  style={{ background: "var(--pi-surface)", border: "0.5px solid var(--pi-hairline)" }}
                >
                  <div className="font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("onboarding.wsDefaultLabel")}
                  </div>
                  <div
                    className="mt-1 truncate"
                    style={{ color: "var(--pi-ink-tertiary)", fontFamily: "var(--pi-font-mono)" }}
                  >
                    {defaultBot.cwd}
                  </div>
                </div>
              )}

              {pickedBotName && (
                <div
                  className="flex items-center gap-2 rounded-[10px] px-3.5 py-2.5 text-[13px]"
                  style={{ background: "var(--pi-wash)", color: "var(--pi-success)" }}
                >
                  <IconCheck size={15} />
                  <span>{t("onboarding.wsPickedPrefix", { name: pickedBotName })}</span>
                </div>
              )}

              {error && (
                <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
                  {error}
                </div>
              )}

              <div className="flex flex-wrap items-center justify-between gap-2 pt-2">
                {isElectron ? (
                  <Button
                    variant="secondary"
                    disabled={pickingFolder}
                    onClick={() => void handlePickWorkspace()}
                  >
                    <IconFolderPlus size={15} />
                    <span>{t("onboarding.wsPickFolder")}</span>
                  </Button>
                ) : (
                  <div />
                )}

                <Button variant="primary" onClick={() => setStep(2)}>
                  <span>
                    {pickedBotName ? t("onboarding.wsNext") : t("onboarding.wsContinueDefault")}
                  </span>
                </Button>
              </div>
            </>
          ) : (
            <>
              <div className="space-y-1.5">
                <h1 className="text-[20px] font-semibold tracking-[-0.02em]">
                  {t("onboarding.modelTitle")}
                </h1>
                <p className="text-[13px] leading-[1.6]" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("onboarding.modelDesc")}
                </p>
              </div>

              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("onboarding.modelSearch")}
              />

              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {visibleProviders.map((p) => {
                  const active = selectedProvider?.id === p.id;
                  return (
                    <button
                      key={p.id}
                      type="button"
                      onClick={() => {
                        stopOAuthPolling();
                        setOauthState(null);
                        setOauthInput("");
                        setApiKeyDraft("");
                        setError(null);
                        setSelectedProvider(p);
                      }}
                      className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2.5 text-left transition-colors hover:bg-[var(--pi-hover)]"
                      style={{
                        background: active ? "var(--pi-wash)" : "var(--pi-surface)",
                        border: active
                          ? "1px solid var(--pi-accent)"
                          : "0.5px solid var(--pi-hairline)",
                      }}
                    >
                      <div className="flex min-w-0 items-center gap-2.5">
                        <ProviderIcon providerId={p.id} name={p.name} size={18} dark={dark} />
                        <span className="truncate text-[13px] font-medium">{p.name}</span>
                      </div>
                      {p.configured && <Badge tone="success">{t("onboarding.modelConnected")}</Badge>}
                    </button>
                  );
                })}
              </div>

              {selectedProvider && (
                <div
                  className="space-y-3 rounded-[12px] p-4"
                  style={{ background: "var(--pi-surface)", border: "0.5px solid var(--pi-hairline)" }}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[13.5px] font-semibold">{selectedProvider.name}</span>
                    {selectedProvider.configured && (
                      <Badge tone="success">{t("onboarding.modelConnected")}</Badge>
                    )}
                  </div>

                  {selectedProvider.supportsApiKey && (
                    <div className="flex gap-2">
                      <div className="relative flex-1">
                        <IconKey
                          size={14}
                          className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        />
                        <Input
                          type="password"
                          value={apiKeyDraft}
                          onChange={(e) => setApiKeyDraft(e.target.value)}
                          placeholder={t("onboarding.modelApiKeyPlaceholder")}
                          aria-label={t("onboarding.modelApiKeyLabel")}
                          className="pl-8"
                        />
                      </div>
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={savingKey || !apiKeyDraft.trim()}
                        onClick={() => void handleSaveKey()}
                      >
                        {t("onboarding.modelSaveKey")}
                      </Button>
                    </div>
                  )}

                  {selectedProvider.hasOAuth && (
                    <div className="space-y-2">
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => void handleStartOAuth(selectedProvider.id)}
                      >
                        <BrandIcon name="navigation/external_link" size={14} />
                        <span>{t("providers.oauthOpen")}</span>
                      </Button>
                      {oauthState?.instructions && (
                        <p className="text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
                          {oauthState.instructions}
                        </p>
                      )}
                      {oauthState?.status === "awaiting_prompt" && (
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
                                .submitOAuth(selectedProvider.id, oauthInput.trim())
                                .then(setOauthState);
                            }}
                          >
                            {t("common.confirm")}
                          </Button>
                        </div>
                      )}
                      {oauthState?.error && (
                        <div className="text-[12px]" style={{ color: "var(--pi-danger)" }}>
                          {oauthState.error}
                        </div>
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

              <div className="flex items-center justify-between gap-2 pt-2">
                <Button variant="ghost" onClick={onComplete}>
                  {t("onboarding.modelLater")}
                </Button>
                <Button variant="primary" disabled={!hasConfiguredProvider} onClick={onComplete}>
                  {t("onboarding.modelFinish")}
                </Button>
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

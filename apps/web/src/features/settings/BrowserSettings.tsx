import { useCallback, useEffect, useMemo, useState } from "react";
import { IconRefresh, IconTrash, IconWorld } from "@tabler/icons-react";
import { useI18n } from "../../i18n";
import {
  type BrowserStorageSummary,
  browserClearAllData,
  browserClearCache,
  browserClearDomainCookies,
  browserGetStorageSummary,
  browserSetPersistSession,
} from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { type BrowserSearchEngine, useSettingsStore } from "../../stores/settingsStore";
import { Badge, Button, ConfirmDialog, IconButton, Input, Select, Switch } from "../../ui";

export function BrowserSettings() {
  const { t } = useI18n();
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const searchEngine = useSettingsStore((s) => s.browserSearchEngine);
  const setSearchEngine = useSettingsStore((s) => s.setBrowserSearchEngine);

  const [summary, setSummary] = useState<BrowserStorageSummary | null>(null);
  const [siteQuery, setSiteQuery] = useState("");
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmClearAll, setConfirmClearAll] = useState(false);

  const refresh = useCallback(async () => {
    if (!isElectron) return;
    setLoading(true);
    try {
      const res = await browserGetStorageSummary();
      if (res) setSummary(res);
    } finally {
      setLoading(false);
    }
  }, [isElectron]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const searchEngineOptions = useMemo(
    () => [
      { value: "google", label: "Google" },
      { value: "bing", label: "Bing" },
      { value: "duckduckgo", label: "DuckDuckGo" },
      { value: "baidu", label: t("settings.browserEngineBaidu") },
    ],
    [t],
  );

  const filteredSites = useMemo(() => {
    const list = summary?.sites ?? [];
    const q = siteQuery.trim().toLowerCase();
    if (!q) return list;
    return list.filter((s) => s.domain.toLowerCase().includes(q));
  }, [siteQuery, summary?.sites]);

  const showNotice = (msg: string) => {
    setNotice(msg);
    setTimeout(() => setNotice((cur) => (cur === msg ? null : cur)), 2400);
  };

  return (
    <div className="space-y-8">
      {notice && (
        <div
          className="rounded-[10px] px-3.5 py-2 text-[12.5px]"
          style={{
            background: "var(--pi-wash)",
            color: "var(--pi-success)",
          }}
        >
          {notice}
        </div>
      )}

      <section className="space-y-1">
        <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("settings.browserSectionAccounts")}
        </h3>

        {!isElectron ? (
          <div
            className="rounded-[12px] px-4 py-5 text-[12.5px]"
            style={{
              background: "var(--pi-paper)",
              border: "0.5px solid var(--pi-hairline)",
              color: "var(--pi-ink-tertiary)",
            }}
          >
            {t("settings.browserDesktopOnly")}
          </div>
        ) : (
          <div className="divide-y divide-[var(--pi-hairline)]">
            <div className="flex items-center justify-between gap-3 py-4">
              <div className="min-w-0">
                <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                  {t("settings.browserPersistLabel")}
                </div>
                <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("settings.browserPersistDesc")}
                </div>
              </div>
              <Switch
                checked={summary ? summary.persistSession : true}
                disabled={busy || !summary}
                ariaLabel={t("settings.browserPersistLabel")}
                onCheckedChange={(checked) => {
                  setBusy(true);
                  void browserSetPersistSession(checked)
                    .then((next) => {
                      if (next) setSummary(next);
                      showNotice(
                        checked
                          ? t("settings.browserPersistEnabled")
                          : t("settings.browserPersistDisabled"),
                      );
                    })
                    .finally(() => setBusy(false));
                }}
              />
            </div>

            <div className="space-y-3 py-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                      {t("settings.browserSavedSites")}
                    </span>
                    {summary && (
                      <Badge tone="neutral">
                        {t("settings.browserCookiesTotal", { count: summary.totalCookies })}
                      </Badge>
                    )}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.browserSavedSitesDesc")}
                  </div>
                </div>

                <IconButton
                  size="sm"
                  label={t("common.refresh")}
                  disabled={loading || busy}
                  onClick={() => void refresh()}
                >
                  <IconRefresh size={14} stroke={1.7} />
                </IconButton>
              </div>

              {(summary?.sites.length ?? 0) > 4 && (
                <Input
                  value={siteQuery}
                  onChange={(e) => setSiteQuery(e.target.value)}
                  placeholder={t("settings.browserSearchSites")}
                  className="h-8 text-[12.5px]"
                />
              )}

              {loading && !summary ? (
                <div className="py-5 text-center text-[12.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                  {t("common.loading")}
                </div>
              ) : filteredSites.length === 0 ? (
                <div
                  className="rounded-[10px] px-3 py-5 text-center text-[12.5px]"
                  style={{
                    background: "var(--pi-paper)",
                    color: "var(--pi-ink-tertiary)",
                  }}
                >
                  {t("settings.browserEmptySites")}
                </div>
              ) : (
                <div
                  className="max-h-[240px] divide-y divide-[var(--pi-hairline)] overflow-y-auto rounded-[12px]"
                  style={{
                    background: "var(--pi-paper)",
                    border: "0.5px solid var(--pi-hairline)",
                  }}
                >
                  {filteredSites.map((site) => (
                    <div
                      key={site.domain}
                      className="flex items-center justify-between gap-3 px-3.5 py-2.5"
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        <IconWorld
                          size={14}
                          stroke={1.65}
                          className="shrink-0"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        />
                        <span
                          className="truncate text-[13px] font-medium"
                          style={{ color: "var(--pi-ink)" }}
                        >
                          {site.domain}
                        </span>
                        <span
                          className="shrink-0 text-[11.5px] tabular-nums"
                          style={{ color: "var(--pi-ink-tertiary)" }}
                        >
                          {t("settings.browserCookiesCount", { count: site.cookieCount })}
                        </span>
                      </div>

                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          setBusy(true);
                          void browserClearDomainCookies(site.domain)
                            .then((next) => {
                              if (next) setSummary(next);
                              showNotice(
                                t("settings.browserSiteCleared", { domain: site.domain }),
                              );
                            })
                            .finally(() => setBusy(false));
                        }}
                      >
                        <IconTrash size={13} stroke={1.7} />
                        <span>{t("settings.browserClearSite")}</span>
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </section>

      <section className="space-y-1">
        <h3 className="pb-2 text-[12px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("settings.browserSectionData")}
        </h3>

        <div className="divide-y divide-[var(--pi-hairline)]">
          <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
            <div className="min-w-0">
              <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                {t("settings.browserSearchEngine")}
              </div>
              <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("settings.browserSearchEngineDesc")}
              </div>
            </div>
            <Select
              value={searchEngine}
              onValueChange={(v) => setSearchEngine(v as BrowserSearchEngine)}
              options={searchEngineOptions}
              className="w-[180px]"
            />
          </div>

          {isElectron && (
            <>
              <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.browserClearCache")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.browserClearCacheDesc")}
                  </div>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    setBusy(true);
                    void browserClearCache()
                      .then(() => showNotice(t("settings.browserCacheCleared")))
                      .finally(() => setBusy(false));
                  }}
                >
                  {t("settings.browserClearCache")}
                </Button>
              </div>

              <div className="flex flex-col justify-between gap-3 py-4 sm:flex-row sm:items-center">
                <div className="min-w-0">
                  <div className="text-[13.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
                    {t("settings.browserClearAll")}
                  </div>
                  <div className="mt-0.5 text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {t("settings.browserClearAllDesc")}
                  </div>
                </div>
                <Button
                  variant="danger"
                  size="sm"
                  disabled={busy}
                  onClick={() => setConfirmClearAll(true)}
                >
                  {t("settings.browserClearAllBtn")}
                </Button>
              </div>
            </>
          )}
        </div>
      </section>

      <ConfirmDialog
        open={confirmClearAll}
        onOpenChange={setConfirmClearAll}
        title={t("settings.browserClearAllConfirmTitle")}
        description={t("settings.browserClearAllConfirmDesc")}
        confirmLabel={t("settings.browserClearAllBtn")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          setConfirmClearAll(false);
          setBusy(true);
          void browserClearAllData()
            .then((next) => {
              if (next) setSummary(next);
              showNotice(t("settings.browserAllCleared"));
            })
            .finally(() => setBusy(false));
        }}
      />
    </div>
  );
}

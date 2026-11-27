import { useCallback, useEffect, useMemo, useState } from "react";
import { IconCheck, IconDownload, IconExternalLink, IconLoader2, IconSearch } from "@tabler/icons-react";
import type {
  HostMarketCatalogResponse,
  HostMarketEntryDto,
  HostMarketInstallResponse,
} from "../../api/types";
import { useI18n, type ResolvedLocale } from "../../i18n";
import { openExternalUrl } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { Badge, Button, Card, Dialog, IconButton, Input } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

function resolveLocalized(
  map: Record<string, string> | undefined,
  locale: ResolvedLocale,
): string {
  if (!map) return "";
  const lang = locale === "zh-CN" ? "zh" : "en";
  return (
    map[lang] ??
    map["zh"] ??
    map["default"] ??
    map["en"] ??
    Object.values(map)[0] ??
    ""
  );
}

function resolveTags(
  tags: Record<string, string[]> | undefined,
  locale: ResolvedLocale,
): string[] {
  if (!tags) return [];
  const lang = locale === "zh-CN" ? "zh" : "en";
  return tags[lang] ?? tags["zh"] ?? tags["default"] ?? Object.values(tags)[0] ?? [];
}

export function MarketBrowser({
  type,
  onInstalled,
}: {
  type: "skill" | "mcp";
  onInstalled: (result: HostMarketInstallResponse) => void;
}) {
  const { t, locale } = useI18n();
  const client = useConnectionStore((s) => s.client);

  const [catalog, setCatalog] = useState<HostMarketCatalogResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState("all");
  const [installingId, setInstallingId] = useState<string | null>(null);
  const [entryErrors, setEntryErrors] = useState<Record<string, string>>({});

  const [keyEntry, setKeyEntry] = useState<HostMarketEntryDto | null>(null);
  const [apiKeyDraft, setApiKeyDraft] = useState("");
  const [keySubmitError, setKeySubmitError] = useState<string | null>(null);

  const loadCatalog = useCallback(
    async (forceRefresh = false) => {
      if (!client) return;
      setLoading(true);
      setError(null);
      try {
        const res = forceRefresh
          ? await client.refreshMarketEntries(type)
          : await client.listMarketEntries(type);
        setCatalog(res);
      } catch (err) {
        setError(err instanceof Error ? err.message : t("market.loadFailed"));
      } finally {
        setLoading(false);
      }
    },
    [client, t, type],
  );

  useEffect(() => {
    void loadCatalog(false);
  }, [loadCatalog]);

  const entries = catalog?.entries ?? [];

  const categories = useMemo(() => {
    const set = new Set<string>();
    for (const item of entries) {
      if (item.category?.trim()) set.add(item.category.trim());
    }
    return ["all", ...Array.from(set).sort()];
  }, [entries]);

  const filteredEntries = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return entries.filter((item) => {
      if (selectedCategory !== "all" && item.category?.trim() !== selectedCategory) {
        return false;
      }
      if (!needle) return true;
      const name = resolveLocalized(item.name, locale).toLowerCase();
      const summary = resolveLocalized(item.summary, locale).toLowerCase();
      const desc = resolveLocalized(item.description, locale).toLowerCase();
      const tags = resolveTags(item.tags, locale).join(" ").toLowerCase();
      return (
        name.includes(needle) ||
        summary.includes(needle) ||
        desc.includes(needle) ||
        tags.includes(needle) ||
        item.id.toLowerCase().includes(needle)
      );
    });
  }, [entries, locale, query, selectedCategory]);

  const performInstall = async (entry: HostMarketEntryDto, apiKey?: string): Promise<boolean> => {
    if (!client || installingId) return false;
    setInstallingId(entry.id);
    setEntryErrors((prev) => {
      if (!(entry.id in prev)) return prev;
      const next = { ...prev };
      delete next[entry.id];
      return next;
    });
    try {
      const res = await client.installMarketEntry(entry.id, apiKey);
      await loadCatalog(false);
      onInstalled(res);
      return true;
    } catch (err) {
      const msg = err instanceof Error ? err.message : t("market.installFailed");
      setEntryErrors((prev) => ({ ...prev, [entry.id]: msg }));
      setKeySubmitError(msg);
      return false;
    } finally {
      setInstallingId(null);
    }
  };

  const handleInstallClick = (entry: HostMarketEntryDto) => {
    if (entry.type === "mcp" && entry.mcp?.auth?.type === "apiKey") {
      setKeyEntry(entry);
      setApiKeyDraft("");
      setKeySubmitError(null);
      return;
    }
    void performInstall(entry);
  };

  const categoryLabel = (cat: string) => {
    if (cat === "all") return t("market.categoryAll");
    const known: Record<string, string> = {
      office: locale === "zh-CN" ? "办公文档" : "Office",
      dev: locale === "zh-CN" ? "开发编程" : "Development",
      productivity: locale === "zh-CN" ? "效率工具" : "Productivity",
      research: locale === "zh-CN" ? "研究检索" : "Research",
      data: locale === "zh-CN" ? "数据分析" : "Data",
      writing: locale === "zh-CN" ? "写作排版" : "Writing",
      design: locale === "zh-CN" ? "设计与媒体" : "Design",
    };
    return known[cat.toLowerCase()] ?? cat;
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-[200px] flex-1">
          <IconSearch
            size={14}
            stroke={1.7}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
            style={{ color: "var(--pi-ink-tertiary)" }}
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("market.searchPlaceholder")}
            className="pl-8"
          />
        </div>
        <IconButton
          size="sm"
          label={t("common.refresh")}
          disabled={loading}
          onClick={() => void loadCatalog(true)}
        >
          <BrandIcon name="action/retry" size={14} />
        </IconButton>
      </div>

      {categories.length > 1 && (
        <div className="flex flex-wrap gap-1.5">
          {categories.map((cat) => {
            const active = selectedCategory === cat;
            return (
              <button
                key={cat}
                type="button"
                onClick={() => setSelectedCategory(cat)}
                className="rounded-full px-3 py-1 text-[12px] font-medium transition-colors"
                style={{
                  background: active ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                  color: active ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                  border: active
                    ? "0.5px solid var(--pi-accent)"
                    : "0.5px solid var(--pi-hairline)",
                }}
              >
                {categoryLabel(cat)}
              </button>
            );
          })}
        </div>
      )}

      {catalog?.stale && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12px]"
          style={{ background: "var(--pi-wash)", color: "var(--pi-warning)" }}
        >
          <span>{t("market.staleNotice")}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadCatalog(true)}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadCatalog(true)}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {loading && entries.length === 0 ? (
        <div className="flex items-center justify-center gap-2 py-10 text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          <IconLoader2 size={15} className="animate-spin" style={{ color: "var(--pi-accent)" }} />
          <span>{t("common.loading")}</span>
        </div>
      ) : entries.length === 0 ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("market.empty")}
        </div>
      ) : filteredEntries.length === 0 ? (
        <div className="py-10 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("market.noMatches")}
        </div>
      ) : (
        <div className="space-y-2.5">
          {filteredEntries.map((entry) => {
            const title = resolveLocalized(entry.name, locale) || entry.id;
            const summary = resolveLocalized(entry.summary, locale) || resolveLocalized(entry.description, locale);
            const tags = resolveTags(entry.tags, locale).slice(0, 3);
            const isInstalling = installingId === entry.id;
            const entryError = entryErrors[entry.id];

            return (
              <Card key={entry.id} className="space-y-2.5 p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                        {title}
                      </span>
                      {entry.version && <Badge tone="neutral">v{entry.version}</Badge>}
                      {entry.category && (
                        <Badge tone="neutral">{categoryLabel(entry.category)}</Badge>
                      )}
                      {entry.type === "mcp" && entry.mcp?.auth?.type === "apiKey" && (
                        <Badge tone="warning">{t("market.requiresKeyBadge")}</Badge>
                      )}
                      {entry.type === "mcp" && entry.mcp?.auth?.type === "oauth" && (
                        <Badge tone="warning">{t("market.requiresSignInBadge")}</Badge>
                      )}
                    </div>
                    {summary && (
                      <p className="text-[12.5px] leading-[1.5]" style={{ color: "var(--pi-ink-secondary)" }}>
                        {summary}
                      </p>
                    )}
                    {(tags.length > 0 || entry.license || entry.sourceUrl) && (
                      <div className="flex flex-wrap items-center gap-2 pt-0.5 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                        {tags.map((tag) => (
                          <span
                            key={tag}
                            className="rounded px-1.5 py-0.5"
                            style={{ background: "var(--pi-wash)" }}
                          >
                            {tag}
                          </span>
                        ))}
                        {entry.license && <span>{entry.license}</span>}
                        {entry.sourceUrl && (
                          <button
                            type="button"
                            onClick={() => void openExternalUrl(entry.sourceUrl!)}
                            className="inline-flex items-center gap-0.5 underline hover:opacity-80"
                          >
                            <span>{t("market.sourceLink")}</span>
                            <IconExternalLink size={11} />
                          </button>
                        )}
                      </div>
                    )}
                  </div>

                  <div className="shrink-0">
                    {entry.installState === "installed" ? (
                      <Badge tone="success">
                        <IconCheck size={12} className="mr-0.5 inline" />
                        {t("market.installed")}
                      </Badge>
                    ) : entry.installState === "update_available" ? (
                      <Button
                        variant="secondary"
                        size="sm"
                        disabled={Boolean(installingId)}
                        onClick={() => handleInstallClick(entry)}
                      >
                        {isInstalling ? (
                          <IconLoader2 size={13.5} className="animate-spin" />
                        ) : (
                          <IconDownload size={13.5} />
                        )}
                        <span>{isInstalling ? t("market.installing") : t("market.update")}</span>
                      </Button>
                    ) : (
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={Boolean(installingId)}
                        onClick={() => handleInstallClick(entry)}
                      >
                        {isInstalling ? (
                          <IconLoader2 size={13.5} className="animate-spin" />
                        ) : (
                          <IconDownload size={13.5} />
                        )}
                        <span>{isInstalling ? t("market.installing") : t("market.install")}</span>
                      </Button>
                    )}
                  </div>
                </div>

                {entryError && (
                  <div
                    className="flex items-center justify-between gap-2 rounded-[8px] px-2.5 py-1.5 text-[12px]"
                    style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
                  >
                    <span className="truncate">{entryError}</span>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={Boolean(installingId)}
                      onClick={() => handleInstallClick(entry)}
                    >
                      {t("conn.retry")}
                    </Button>
                  </div>
                )}
              </Card>
            );
          })}
        </div>
      )}

      <Dialog
        open={Boolean(keyEntry)}
        onOpenChange={(open) => {
          if (!open) setKeyEntry(null);
        }}
        title={keyEntry ? resolveLocalized(keyEntry.name, locale) || keyEntry.id : ""}
        description={keyEntry ? resolveLocalized(keyEntry.mcp?.authHint, locale) : undefined}
        maxWidthClass="max-w-[440px]"
      >
        {keyEntry && (
          <div className="space-y-3">
            <div className="space-y-1.5">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("market.apiKeyLabel")}
              </label>
              <Input
                type="password"
                value={apiKeyDraft}
                onChange={(e) => setApiKeyDraft(e.target.value)}
                placeholder={
                  resolveLocalized(keyEntry.mcp?.auth?.placeholder, locale) ||
                  t("market.apiKeyPlaceholder")
                }
              />
              {keyEntry.mcp?.auth?.helpUrl && (
                <button
                  type="button"
                  onClick={() => void openExternalUrl(keyEntry.mcp!.auth!.helpUrl!)}
                  className="inline-flex items-center gap-1 text-[11.5px] underline"
                  style={{ color: "var(--pi-accent)" }}
                >
                  <span>{t("market.apiKeyHelp")}</span>
                  <IconExternalLink size={11} />
                </button>
              )}
            </div>

            {keySubmitError && (
              <div className="text-[12px]" style={{ color: "var(--pi-danger)" }}>
                {keySubmitError}
              </div>
            )}

            <div className="flex justify-end gap-2 pt-1">
              <Button variant="ghost" size="sm" onClick={() => setKeyEntry(null)}>
                {t("common.cancel")}
              </Button>
              <Button
                variant="primary"
                size="sm"
                disabled={!apiKeyDraft.trim() || installingId === keyEntry.id}
                onClick={async () => {
                  const ok = await performInstall(keyEntry, apiKeyDraft.trim());
                  if (ok) setKeyEntry(null);
                }}
              >
                {installingId === keyEntry.id ? t("market.installing") : t("market.install")}
              </Button>
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}

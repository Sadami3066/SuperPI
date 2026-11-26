import { useCallback, useEffect, useState } from "react";
import {
  IconCheck,
  IconDownload,
  IconLink,
  IconPin,
  IconPlus,
  IconRefresh,
  IconRotateClockwise,
  IconSearch,
  IconSparkles,
  IconTrash,
  IconX,
} from "@tabler/icons-react";
import { formatRelativeTime } from "../../api/mappers";
import type {
  HostMemoryBlockDto,
  HostMemoryEntityDetailDto,
  HostMemoryFullSnapshotResponse,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
} from "../../ui";

function downloadText(filename: string, content: string): void {
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

export function MemorySettings() {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const relayStatus = useSettingsStore((s) => s.relayStatus);

  const [snapshot, setSnapshot] = useState<HostMemoryFullSnapshotResponse | null>(null);
  const [innerTab, setInnerTab] = useState("blocks");
  const [query, setQuery] = useState("");
  const [includeArchived, setIncludeArchived] = useState(false);
  const [includeHistory, setIncludeHistory] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [editingBlock, setEditingBlock] = useState<HostMemoryBlockDto | null>(null);
  const [createBlockOpen, setCreateBlockOpen] = useState(false);
  const [blockTitle, setBlockTitle] = useState("");
  const [blockPurpose, setBlockPurpose] = useState("");
  const [blockContent, setBlockContent] = useState("");
  const [blockDomain, setBlockDomain] = useState("CUSTOM");
  const [deleteBlockTarget, setDeleteBlockTarget] = useState<HostMemoryBlockDto | null>(null);

  const [entityModalOpen, setEntityModalOpen] = useState(false);
  const [entityName, setEntityName] = useState("");
  const [entityType, setEntityType] = useState("TOPIC");
  const [entitySummary, setEntitySummary] = useState("");

  const [relationModalOpen, setRelationModalOpen] = useState(false);
  const [relFrom, setRelFrom] = useState("");
  const [relType, setRelType] = useState("related_to");
  const [relTo, setRelTo] = useState("");

  const [eventModalOpen, setEventModalOpen] = useState(false);
  const [eventKind, setEventKind] = useState("FACT");
  const [eventContent, setEventContent] = useState("");
  const [eventTags, setEventTags] = useState("");

  const [entityDetail, setEntityDetail] = useState<HostMemoryEntityDetailDto | null>(null);

  const [prefsMode, setPrefsMode] = useState("AUTO");
  const [hotDebounce, setHotDebounce] = useState(60);
  const [coldHours, setColdHours] = useState(12);
  const [injectionChars, setInjectionChars] = useState(16000);
  const [guidance, setGuidance] = useState("");
  const [prefsSaved, setPrefsSaved] = useState(false);

  const loadSnapshot = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      const next = await client.getMemorySnapshot({
        includeArchived,
        includeHistory,
        query: query.trim() || undefined,
      });
      setSnapshot(next);
      setPrefsMode(next.preferences.mode || "AUTO");
      setHotDebounce(next.preferences.hotIdleDebounceSeconds);
      setColdHours(next.preferences.coldConsolidationHours);
      setInjectionChars(next.preferences.injectionMaxChars);
      setGuidance(next.preferences.guidance || "");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load memory");
    } finally {
      setLoading(false);
    }
  }, [client, includeArchived, includeHistory, query]);

  useEffect(() => {
    void loadSnapshot();
  }, [loadSnapshot]);

  const runAction = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await loadSnapshot();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Operation failed");
    } finally {
      setBusy(false);
    }
  };

  const overview = snapshot?.overview;

  return (
    <div className="space-y-4">
      <Card className="space-y-3 p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex flex-wrap items-center gap-2 text-[12px]">
            <Badge tone="neutral">
              {t("memory.statBlocks", { count: overview?.activeBlocks ?? 0 })}
            </Badge>
            <Badge tone="neutral">
              {t("memory.statEntities", { count: overview?.entityCount ?? 0 })}
            </Badge>
            <Badge tone="neutral">
              {t("memory.statRelations", { count: overview?.relationCount ?? 0 })}
            </Badge>
            <Badge tone="neutral">
              {t("memory.statEvents", { count: overview?.activeEvents ?? 0 })}
            </Badge>
          </div>

          <div className="flex flex-wrap items-center gap-1.5">
            <Button
              variant="secondary"
              size="sm"
              disabled={busy || Boolean(snapshot?.consolidating)}
              onClick={() => {
                if (client) void runAction(() => client.consolidateMemory());
              }}
            >
              <IconSparkles size={13.5} />
              <span>
                {snapshot?.consolidating ? t("memory.consolidating") : t("memory.consolidate")}
              </span>
            </Button>

            {relayStatus?.enrolled && (
              <Button
                variant="secondary"
                size="sm"
                disabled={busy}
                onClick={() => {
                  if (client) void runAction(() => client.syncMemoryNow());
                }}
              >
                <IconRefresh size={13.5} />
                <span>{t("memory.syncNow")}</span>
              </Button>
            )}

            <Button
              variant="ghost"
              size="sm"
              disabled={busy}
              onClick={() => {
                if (!client) return;
                void client
                  .exportMemoryMarkdown()
                  .then((md) => downloadText("superpi-memory.md", md))
                  .catch((err) => setError(err instanceof Error ? err.message : "Export failed"));
              }}
            >
              <IconDownload size={13.5} />
              <span>{t("nav.export")}</span>
            </Button>
          </div>
        </div>

        {snapshot?.syncStatus.accountSwitchPrompt && (
          <div
            className="flex flex-wrap items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
            style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
          >
            <span style={{ color: "var(--pi-ink)" }}>
              {t("memory.syncSwitchPrompt", {
                identity: snapshot.syncStatus.accountSwitchPrompt.accountIdentity,
                count: snapshot.syncStatus.accountSwitchPrompt.localRecordCount,
              })}
            </span>
            <div className="flex gap-1.5">
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  if (client) void runAction(() => client.resolveMemorySync("MERGE"));
                }}
              >
                {t("memory.syncMerge")}
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  if (client) void runAction(() => client.resolveMemorySync("REPLACE_LOCAL"));
                }}
              >
                {t("memory.syncReplace")}
              </Button>
            </div>
          </div>
        )}

        {error && (
          <div className="flex items-center justify-between gap-2 text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
            <span className="truncate">{error}</span>
            <Button variant="ghost" size="sm" onClick={() => void loadSnapshot()}>
              {t("conn.retry")}
            </Button>
          </div>
        )}
      </Card>

      <Tabs value={innerTab} onValueChange={setInnerTab} className="space-y-3">
        <TabsList>
          <TabsTrigger value="blocks">{t("memory.tabBlocks")}</TabsTrigger>
          <TabsTrigger value="graph">{t("memory.tabGraph")}</TabsTrigger>
          <TabsTrigger value="audit">{t("memory.tabAudit")}</TabsTrigger>
          <TabsTrigger value="prefs">{t("memory.tabPrefs")}</TabsTrigger>
        </TabsList>

        <TabsContent value="blocks" className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
              <Switch checked={includeArchived} onCheckedChange={setIncludeArchived} />
              <span>{t("memory.includeArchived")}</span>
            </div>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setBlockTitle("");
                setBlockPurpose("");
                setBlockContent("");
                setBlockDomain("CUSTOM");
                setCreateBlockOpen(true);
              }}
            >
              <IconPlus size={14} />
              <span>{t("memory.newBlock")}</span>
            </Button>
          </div>

          {(snapshot?.proposals.length ?? 0) > 0 && (
            <div className="space-y-2">
              <div className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("memory.proposalsTitle")}
              </div>
              {snapshot?.proposals.map((p) => (
                <Card key={p.id} className="space-y-2 p-3.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                        {p.title}
                      </div>
                      <div className="text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
                        {p.reason || p.purpose}
                      </div>
                    </div>
                    <div className="flex shrink-0 gap-1">
                      <Button
                        variant="primary"
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          if (client) void runAction(() => client.acceptMemoryProposal(p.id));
                        }}
                      >
                        <IconCheck size={13.5} />
                        <span>{t("memory.accept")}</span>
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busy}
                        onClick={() => {
                          if (client) void runAction(() => client.dismissMemoryProposal(p.id));
                        }}
                      >
                        <IconX size={13.5} />
                      </Button>
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          )}

          {loading && !snapshot ? (
            <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("common.loading")}
            </div>
          ) : (snapshot?.blocks.length ?? 0) === 0 ? (
            <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("memory.emptyBlocks")}
            </div>
          ) : (
            <div className="space-y-2">
              {snapshot?.blocks.map((block) => (
                <Card key={block.id} className="space-y-2 p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                          {block.title}
                        </span>
                        <Badge tone="neutral">{block.domain}</Badge>
                        {block.pinned && <Badge tone="accent">{t("nav.pin")}</Badge>}
                        {block.status === "ARCHIVED" && <Badge tone="neutral">{t("nav.archive")}</Badge>}
                      </div>
                      {block.purpose && (
                        <div className="text-[12px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                          {block.purpose}
                        </div>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <IconButton
                        size="sm"
                        label={t("nav.pin")}
                        onClick={() => {
                          if (client) {
                            void runAction(() =>
                              client.updateMemoryBlock(block.id, { pinned: !block.pinned }),
                            );
                          }
                        }}
                      >
                        <IconPin
                          size={14}
                          style={{ color: block.pinned ? "var(--pi-accent)" : undefined }}
                        />
                      </IconButton>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => {
                          setEditingBlock(block);
                          setBlockTitle(block.title);
                          setBlockPurpose(block.purpose);
                          setBlockContent(block.content);
                          setBlockDomain(block.domain);
                        }}
                      >
                        {t("common.edit")}
                      </Button>
                      {block.owner !== "SYSTEM" && (
                        <IconButton
                          size="sm"
                          label={t("common.delete")}
                          onClick={() => setDeleteBlockTarget(block)}
                        >
                          <IconTrash size={14} />
                        </IconButton>
                      )}
                    </div>
                  </div>
                  {block.content && (
                    <pre
                      className="max-h-36 overflow-y-auto rounded-[8px] px-3 py-2 text-[12px] whitespace-pre-wrap"
                      style={{
                        background: "var(--pi-paper)",
                        color: "var(--pi-ink-secondary)",
                        fontFamily: "var(--pi-font-mono)",
                      }}
                    >
                      {block.content}
                    </pre>
                  )}
                </Card>
              ))}
            </div>
          )}
        </TabsContent>

        <TabsContent value="graph" className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative min-w-[180px] flex-1">
              <IconSearch
                size={14}
                className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
                style={{ color: "var(--pi-ink-tertiary)" }}
              />
              <Input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder={t("memory.searchPlaceholder")}
                className="pl-9"
              />
            </div>
            <div className="flex items-center gap-2 text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
              <Switch checked={includeHistory} onCheckedChange={setIncludeHistory} />
              <span>{t("memory.includeHistory")}</span>
            </div>
          </div>

          <div className="flex flex-wrap gap-1.5">
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setEventKind("FACT");
                setEventContent("");
                setEventTags("");
                setEventModalOpen(true);
              }}
            >
              <IconPlus size={13.5} />
              <span>{t("memory.newEvent")}</span>
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setEntityName("");
                setEntityType("TOPIC");
                setEntitySummary("");
                setEntityModalOpen(true);
              }}
            >
              <IconPlus size={13.5} />
              <span>{t("memory.newEntity")}</span>
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setRelFrom("");
                setRelType("related_to");
                setRelTo("");
                setRelationModalOpen(true);
              }}
            >
              <IconLink size={13.5} />
              <span>{t("memory.newRelation")}</span>
            </Button>
          </div>

          {(snapshot?.entities.length ?? 0) > 0 && (
            <Card className="space-y-2 p-3.5">
              <div className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("memory.entitiesTitle")}
              </div>
              <div className="flex flex-wrap gap-1.5">
                {snapshot?.entities.slice(0, 48).map((ent) => (
                  <button
                    key={ent.id}
                    type="button"
                    onClick={() => {
                      if (!client) return;
                      void client
                        .getMemoryEntity(ent.id, includeHistory)
                        .then(setEntityDetail)
                        .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
                    }}
                    className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: "var(--pi-paper)",
                      border: "0.5px solid var(--pi-hairline)",
                      color: "var(--pi-ink)",
                    }}
                  >
                    <span>{ent.canonicalName}</span>
                    <span className="text-[10.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                      {ent.entityType}
                    </span>
                  </button>
                ))}
              </div>
            </Card>
          )}

          <div className="space-y-2">
            {(snapshot?.events.length ?? 0) === 0 ? (
              <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("memory.emptyEvents")}
              </div>
            ) : (
              snapshot?.events.map((ev) => (
                <Card key={ev.id} className="space-y-1.5 p-3.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <Badge tone="neutral">{ev.eventKind}</Badge>
                      <Badge tone="neutral">{ev.domain}</Badge>
                      {ev.status !== "ACTIVE" && <Badge tone="warning">{ev.status}</Badge>}
                      {ev.pinned && <Badge tone="accent">{t("nav.pin")}</Badge>}
                      <span className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                        {formatRelativeTime(ev.createdAt, locale)}
                      </span>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <IconButton
                        size="sm"
                        label={t("nav.pin")}
                        onClick={() => {
                          if (client) {
                            void runAction(() =>
                              client.updateMemoryEvent(ev.id, { pinned: !ev.pinned }),
                            );
                          }
                        }}
                      >
                        <IconPin
                          size={13.5}
                          style={{ color: ev.pinned ? "var(--pi-accent)" : undefined }}
                        />
                      </IconButton>
                      {ev.status === "ACTIVE" ? (
                        <IconButton
                          size="sm"
                          label={t("common.delete")}
                          onClick={() => {
                            if (client) void runAction(() => client.deleteMemoryEvent(ev.id));
                          }}
                        >
                          <IconTrash size={13.5} />
                        </IconButton>
                      ) : (
                        <IconButton
                          size="sm"
                          label={t("memory.restore")}
                          onClick={() => {
                            if (client) void runAction(() => client.restoreMemoryEvent(ev.id));
                          }}
                        >
                          <IconRotateClockwise size={13.5} />
                        </IconButton>
                      )}
                    </div>
                  </div>
                  <div className="text-[13px] leading-[1.5]" style={{ color: "var(--pi-ink)" }}>
                    {ev.content}
                  </div>
                  {ev.tags.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {ev.tags.map((tag) => (
                        <span
                          key={tag}
                          className="rounded px-1.5 py-0.5 text-[11px]"
                          style={{ background: "var(--pi-paper)", color: "var(--pi-ink-tertiary)" }}
                        >
                          #{tag}
                        </span>
                      ))}
                    </div>
                  )}
                </Card>
              ))
            )}
          </div>
        </TabsContent>

        <TabsContent value="audit" className="space-y-2">
          {(snapshot?.auditLog.length ?? 0) === 0 ? (
            <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {t("memory.emptyAudit")}
            </div>
          ) : (
            snapshot?.auditLog.map((entry) => (
              <Card key={entry.id} className="flex items-center justify-between gap-3 p-3.5">
                <div className="min-w-0 space-y-0.5">
                  <div className="flex flex-wrap items-center gap-1.5 text-[12px]">
                    <Badge tone="neutral">{entry.actor}</Badge>
                    <span className="font-medium" style={{ color: "var(--pi-ink)" }}>
                      {entry.summaryUserText || entry.actionType}
                    </span>
                  </div>
                  <div className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {formatRelativeTime(entry.createdAt, locale)}
                    {entry.revertedAt ? ` · ${t("memory.reverted")}` : ""}
                  </div>
                </div>
                {entry.canRevert && !entry.revertedAt && (
                  <Button
                    variant="secondary"
                    size="sm"
                    disabled={busy}
                    onClick={() => {
                      if (client) void runAction(() => client.revertMemoryAudit(entry.id));
                    }}
                  >
                    {t("memory.revert")}
                  </Button>
                )}
              </Card>
            ))
          )}
        </TabsContent>

        <TabsContent value="prefs" className="space-y-4">
          <Card className="space-y-4 p-4">
            <div className="space-y-1">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("memory.prefsMode")}
              </label>
              <Select
                value={prefsMode}
                onValueChange={setPrefsMode}
                options={[
                  { value: "AUTO", label: t("memory.modeAuto") },
                  { value: "CUSTOM", label: t("memory.modeCustom") },
                  { value: "OFF", label: t("memory.modeOff") },
                ]}
                className="w-full"
              />
            </div>

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("memory.hotDebounce")}
                </label>
                <Input
                  type="number"
                  min={15}
                  max={600}
                  value={hotDebounce}
                  onChange={(e) => setHotDebounce(Number(e.target.value) || 60)}
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("memory.coldHours")}
                </label>
                <Input
                  type="number"
                  min={0}
                  max={168}
                  value={coldHours}
                  onChange={(e) => setColdHours(Number(e.target.value) || 0)}
                />
              </div>
              <div className="space-y-1">
                <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("memory.injectionBudget")}
                </label>
                <Input
                  type="number"
                  min={2000}
                  max={64000}
                  value={injectionChars}
                  onChange={(e) => setInjectionChars(Number(e.target.value) || 16000)}
                />
              </div>
            </div>

            <div className="space-y-1">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("memory.guidance")}
              </label>
              <Textarea
                rows={4}
                value={guidance}
                onChange={(e) => setGuidance(e.target.value)}
                placeholder={t("memory.guidancePlaceholder")}
              />
            </div>

            <div className="flex justify-end">
              <Button
                variant="primary"
                size="sm"
                disabled={busy}
                onClick={() => {
                  if (!client) return;
                  void runAction(async () => {
                    await client.updateMemoryPreferences({
                      mode: prefsMode,
                      hotIdleDebounceSeconds: hotDebounce,
                      coldConsolidationHours: coldHours,
                      injectionMaxChars: injectionChars,
                      guidance,
                    });
                    setPrefsSaved(true);
                    setTimeout(() => setPrefsSaved(false), 1600);
                  });
                }}
              >
                {prefsSaved ? t("bot.saved") : t("common.save")}
              </Button>
            </div>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog
        open={createBlockOpen || Boolean(editingBlock)}
        onOpenChange={(v) => {
          if (!v) {
            setCreateBlockOpen(false);
            setEditingBlock(null);
          }
        }}
        title={editingBlock ? t("memory.editBlock") : t("memory.newBlock")}
      >
        <div className="space-y-3">
          <Input
            value={blockTitle}
            onChange={(e) => setBlockTitle(e.target.value)}
            placeholder={t("memory.blockTitlePlaceholder")}
          />
          <Input
            value={blockPurpose}
            onChange={(e) => setBlockPurpose(e.target.value)}
            placeholder={t("memory.blockPurposePlaceholder")}
          />
          <Select
            value={blockDomain}
            onValueChange={setBlockDomain}
            options={[
              { value: "CUSTOM", label: "CUSTOM" },
              { value: "PERSONAL", label: "PERSONAL" },
              { value: "WORK", label: "WORK" },
            ]}
            className="w-full"
          />
          <Textarea
            rows={6}
            value={blockContent}
            onChange={(e) => setBlockContent(e.target.value)}
            placeholder={t("memory.blockContentPlaceholder")}
            style={{ fontFamily: "var(--pi-font-mono)" }}
          />
          <div className="flex justify-end gap-2">
            <Button
              variant="ghost"
              onClick={() => {
                setCreateBlockOpen(false);
                setEditingBlock(null);
              }}
            >
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !blockTitle.trim()}
              onClick={() => {
                if (!client) return;
                void runAction(async () => {
                  if (editingBlock) {
                    await client.updateMemoryBlock(editingBlock.id, {
                      title: blockTitle.trim(),
                      purpose: blockPurpose.trim(),
                      content: blockContent,
                      domain: blockDomain,
                    });
                  } else {
                    await client.createMemoryBlock({
                      title: blockTitle.trim(),
                      purpose: blockPurpose.trim(),
                      content: blockContent,
                      domain: blockDomain,
                    });
                  }
                  setCreateBlockOpen(false);
                  setEditingBlock(null);
                });
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={entityModalOpen}
        onOpenChange={setEntityModalOpen}
        title={t("memory.newEntity")}
      >
        <div className="space-y-3">
          <Input
            value={entityName}
            onChange={(e) => setEntityName(e.target.value)}
            placeholder={t("memory.entityNamePlaceholder")}
          />
          <Select
            value={entityType}
            onValueChange={setEntityType}
            options={[
              { value: "TOPIC", label: "TOPIC" },
              { value: "PERSON", label: "PERSON" },
              { value: "PROJECT", label: "PROJECT" },
              { value: "ORGANIZATION", label: "ORGANIZATION" },
              { value: "TOOL", label: "TOOL" },
            ]}
            className="w-full"
          />
          <Textarea
            rows={3}
            value={entitySummary}
            onChange={(e) => setEntitySummary(e.target.value)}
            placeholder={t("memory.entitySummaryPlaceholder")}
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setEntityModalOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !entityName.trim()}
              onClick={() => {
                if (!client) return;
                void runAction(async () => {
                  await client.createMemoryEntity({
                    canonicalName: entityName.trim(),
                    entityType,
                    summary: entitySummary.trim() || undefined,
                  });
                  setEntityModalOpen(false);
                });
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={relationModalOpen}
        onOpenChange={setRelationModalOpen}
        title={t("memory.newRelation")}
      >
        <div className="space-y-3">
          <Input
            value={relFrom}
            onChange={(e) => setRelFrom(e.target.value)}
            placeholder={t("memory.relationFrom")}
          />
          <Input
            value={relType}
            onChange={(e) => setRelType(e.target.value)}
            placeholder="related_to"
          />
          <Input
            value={relTo}
            onChange={(e) => setRelTo(e.target.value)}
            placeholder={t("memory.relationTo")}
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setRelationModalOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !relFrom.trim() || !relTo.trim()}
              onClick={() => {
                if (!client) return;
                void runAction(async () => {
                  await client.createMemoryRelation({
                    fromEntityName: relFrom.trim(),
                    relationType: relType.trim() || "related_to",
                    toEntityName: relTo.trim(),
                  });
                  setRelationModalOpen(false);
                });
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={eventModalOpen}
        onOpenChange={setEventModalOpen}
        title={t("memory.newEvent")}
      >
        <div className="space-y-3">
          <Select
            value={eventKind}
            onValueChange={setEventKind}
            options={[
              { value: "FACT", label: "FACT" },
              { value: "PREFERENCE", label: "PREFERENCE" },
              { value: "DECISION", label: "DECISION" },
              { value: "MILESTONE", label: "MILESTONE" },
            ]}
            className="w-full"
          />
          <Textarea
            rows={4}
            value={eventContent}
            onChange={(e) => setEventContent(e.target.value)}
            placeholder={t("memory.eventContentPlaceholder")}
          />
          <Input
            value={eventTags}
            onChange={(e) => setEventTags(e.target.value)}
            placeholder={t("memory.eventTagsPlaceholder")}
          />
          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setEventModalOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="primary"
              disabled={busy || !eventContent.trim()}
              onClick={() => {
                if (!client) return;
                const tags = eventTags
                  .split(",")
                  .map((s) => s.trim())
                  .filter(Boolean);
                void runAction(async () => {
                  await client.createMemoryEvent({
                    eventKind,
                    content: eventContent.trim(),
                    tags,
                  });
                  setEventModalOpen(false);
                });
              }}
            >
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <Dialog
        open={Boolean(entityDetail)}
        onOpenChange={(v) => {
          if (!v) setEntityDetail(null);
        }}
        title={entityDetail?.entity.canonicalName}
        maxWidthClass="max-w-[560px]"
      >
        {entityDetail && (
          <div className="max-h-[65vh] space-y-3 overflow-y-auto text-[13px]">
            <div className="flex items-center gap-2">
              <Badge tone="neutral">{entityDetail.entity.entityType}</Badge>
              <Badge tone="neutral">{entityDetail.entity.domain}</Badge>
            </div>
            {entityDetail.entity.summary && (
              <p style={{ color: "var(--pi-ink-secondary)" }}>{entityDetail.entity.summary}</p>
            )}
            {(entityDetail.outgoingRelations.length > 0 ||
              entityDetail.incomingRelations.length > 0) && (
              <div className="space-y-1.5">
                <div className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("memory.relationsLabel")}
                </div>
                {[...entityDetail.outgoingRelations, ...entityDetail.incomingRelations].map((r) => (
                  <div
                    key={r.id}
                    className="flex items-center justify-between gap-2 rounded-[8px] px-2.5 py-1.5 text-[12px]"
                    style={{ background: "var(--pi-paper)" }}
                  >
                    <span>
                      {r.fromEntityName} · <strong>{r.relationType}</strong> · {r.toEntityName}
                    </span>
                    <IconButton
                      size="sm"
                      label={t("common.delete")}
                      onClick={() => {
                        if (!client) return;
                        void runAction(async () => {
                          await client.deleteMemoryRelation(r.id);
                          const refreshed = await client.getMemoryEntity(
                            entityDetail.entity.id,
                            includeHistory,
                          );
                          setEntityDetail(refreshed);
                        });
                      }}
                    >
                      <IconTrash size={13} />
                    </IconButton>
                  </div>
                ))}
              </div>
            )}
            {entityDetail.linkedEvents.length > 0 && (
              <div className="space-y-1.5">
                <div className="text-[12px] font-semibold" style={{ color: "var(--pi-ink-secondary)" }}>
                  {t("memory.linkedEventsLabel")}
                </div>
                {entityDetail.linkedEvents.map((ev) => (
                  <div
                    key={ev.id}
                    className="rounded-[8px] px-2.5 py-2 text-[12px]"
                    style={{ background: "var(--pi-paper)", color: "var(--pi-ink-secondary)" }}
                  >
                    {ev.content}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteBlockTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteBlockTarget(null);
        }}
        title={t("memory.deleteBlockTitle")}
        description={deleteBlockTarget?.title || ""}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteBlockTarget) return;
          const id = deleteBlockTarget.id;
          setDeleteBlockTarget(null);
          void runAction(() => client.deleteMemoryBlock(id));
        }}
      />
    </div>
  );
}

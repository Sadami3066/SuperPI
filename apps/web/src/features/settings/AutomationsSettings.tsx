import { useCallback, useEffect, useState } from "react";
import { formatAutomationSource, formatAutomationStatus } from "../../api/formatters";
import { formatRelativeTime } from "../../api/mappers";
import type { HostSaveTimerRequest, HostTimerDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
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
import { BrandIcon } from "../shared/BrandAssets";
import { formatTimerSchedule, validateTimerDraft, weekdayLabel } from "./timerSchedule";

export function AutomationsSettings({
  mode,
  onOpenSession,
}: {
  mode?: "timers" | "inbox";
  onOpenSession?: (sessionId: string) => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);

  const inbox = useSuperStore((s) => s.inbox);
  const unreadInboxCount = useSuperStore((s) => s.unreadInboxCount);
  const refreshInbox = useSuperStore((s) => s.refreshInbox);
  const setInboxItems = useSuperStore((s) => s.setInboxItems);

  const [tab, setTab] = useState<string>(() => mode ?? (unreadInboxCount > 0 ? "inbox" : "timers"));
  const [timers, setTimers] = useState<HostTimerDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [editorOpen, setEditorOpen] = useState(false);
  const [editingTimer, setEditingTimer] = useState<HostTimerDto | null>(null);
  const [name, setName] = useState("");
  const [prompt, setPrompt] = useState("");
  const [triggerType, setTriggerType] = useState<"once" | "daily" | "weekly" | "cron">("daily");
  const [date, setDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [time, setTime] = useState("09:00");
  const [days, setDays] = useState<number[]>([1, 2, 3, 4, 5]);
  const [cronExpression, setCronExpression] = useState("0 9 * * 1-5");
  const [botId, setBotId] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<HostTimerDto | null>(null);

  const localBots = bots.filter((b) => !b.isRemote);

  const loadAll = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      if (mode === "timers") {
        const timerList = await client.listTimers();
        setTimers(timerList);
      } else if (mode === "inbox") {
        await refreshInbox(client);
      } else {
        const [timerList] = await Promise.all([client.listTimers(), refreshInbox(client)]);
        setTimers(timerList);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load automations");
    } finally {
      setLoading(false);
    }
  }, [client, mode, refreshInbox]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const openEditor = (existing?: HostTimerDto) => {
    setEditingTimer(existing ?? null);
    setName(existing?.name ?? "");
    setPrompt(existing?.prompt ?? "");
    setTriggerType(existing?.triggerType ?? "daily");
    setDate(existing?.date || new Date().toISOString().slice(0, 10));
    setTime(existing?.time || "09:00");
    setDays(existing?.days.length ? existing.days : [1, 2, 3, 4, 5]);
    setCronExpression(existing?.cronExpression || "0 9 * * 1-5");
    setBotId(localBots[0]?.id ?? "");
    setFormError(null);
    setEditorOpen(true);
  };

  const handleSaveTimer = async () => {
    if (!client) return;
    const payload: HostSaveTimerRequest = {
      name: name.trim(),
      prompt: prompt.trim(),
      triggerType,
      date: triggerType === "once" ? date.trim() : undefined,
      time: triggerType !== "cron" ? time.trim() : undefined,
      days: triggerType === "weekly" ? days : undefined,
      cronExpression: triggerType === "cron" ? cronExpression.trim() : undefined,
      enabled: editingTimer ? editingTimer.enabled : true,
      botId: botId || undefined,
    };
    const issue = validateTimerDraft(payload);
    if (issue) {
      setFormError(t("auto.invalidSchedule"));
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      if (editingTimer) {
        await client.updateTimer(editingTimer.id, payload);
      } else {
        await client.createTimer(payload);
      }
      setEditorOpen(false);
      await loadAll();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Failed to save timer");
    } finally {
      setBusy(false);
    }
  };

  const timersSection = (
    <div className="space-y-3">
      <div className="flex justify-end">
        <Button variant="secondary" size="sm" onClick={() => openEditor()}>
          <BrandIcon name="action/add" size={14} />
          <span>{t("auto.newTimer")}</span>
        </Button>
      </div>

      {loading && timers.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("common.loading")}
        </div>
      ) : timers.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("auto.emptyTimers")}
        </div>
      ) : (
        <div className="space-y-2.5">
          {timers.map((timer) => (
            <Card key={timer.id} className="space-y-2 p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                      {timer.name}
                    </span>
                    <Badge tone="neutral">{formatTimerSchedule(timer, locale)}</Badge>
                    {timer.lastError && <Badge tone="danger">{timer.lastError}</Badge>}
                  </div>
                  <p className="line-clamp-2 text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
                    {timer.prompt}
                  </p>
                  <div className="flex flex-wrap gap-3 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {timer.nextRunAt && (
                      <span>
                        {t("auto.nextRun", {
                          time: formatRelativeTime(timer.nextRunAt, locale),
                        })}
                      </span>
                    )}
                    {timer.lastDeliveredAt && (
                      <span>
                        {t("auto.lastRun", {
                          time: formatRelativeTime(timer.lastDeliveredAt, locale),
                        })}
                      </span>
                    )}
                  </div>
                </div>

                <Switch
                  checked={timer.enabled}
                  disabled={busy}
                  onCheckedChange={(checked) => {
                    if (!client) return;
                    setBusy(true);
                    void client
                      .toggleTimer(timer.id, checked)
                      .then(() => loadAll())
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                />
              </div>

              <div
                className="flex items-center justify-end gap-1.5 pt-2"
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
                      .runTimerNow(timer.id)
                      .then(() => loadAll())
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                      .finally(() => setBusy(false));
                  }}
                >
                  <BrandIcon name="action/play" size={13.5} />
                  <span>{t("auto.runNow")}</span>
                </Button>
                <Button variant="ghost" size="sm" onClick={() => openEditor(timer)}>
                  {t("common.edit")}
                </Button>
                <IconButton
                  size="sm"
                  label={t("common.delete")}
                  onClick={() => setDeleteTarget(timer)}
                >
                  <BrandIcon name="action/delete" size={14} />
                </IconButton>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );

  const inboxSection = (
    <div className="space-y-3">
      {inbox.some((item) => item.status !== "unread") && (
        <div className="flex justify-end">
          <Button
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => {
              if (!client) return;
              setBusy(true);
              void client
                .clearSettledInbox()
                .then(setInboxItems)
                .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                .finally(() => setBusy(false));
            }}
          >
            {t("auto.clearSettled")}
          </Button>
        </div>
      )}

      {inbox.length === 0 ? (
        <div className="py-8 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
          {t("auto.emptyInbox")}
        </div>
      ) : (
        <div className="space-y-2.5">
          {inbox.map((item) => (
            <Card key={item.id} className="space-y-2 p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <Badge tone={item.status === "unread" ? "accent" : "neutral"}>
                      {formatAutomationSource(item.source, t)}
                    </Badge>
                    <span className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                      {item.title}
                    </span>
                    <span className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                      {formatRelativeTime(item.createdAt, locale)}
                    </span>
                  </div>
                  {item.summary && (
                    <p className="text-[12.5px] leading-[1.5]" style={{ color: "var(--pi-ink-secondary)" }}>
                      {item.summary}
                    </p>
                  )}
                </div>

                <Badge
                  tone={
                    item.status === "approved"
                      ? "success"
                      : item.status === "unread"
                        ? "accent"
                        : "neutral"
                  }
                >
                  {formatAutomationStatus(item.status, t)}
                </Badge>
              </div>

              <div
                className="flex flex-wrap items-center justify-end gap-1.5 pt-2"
                style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
              >
                {item.sessionId && onOpenSession && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => onOpenSession(item.sessionId!)}
                  >
                    <BrandIcon name="navigation/external_link" size={13.5} />
                    <span>{t("auto.openSession")}</span>
                  </Button>
                )}
                {item.status === "unread" && (
                  <>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={busy}
                      onClick={() => {
                        if (!client) return;
                        setBusy(true);
                        void client
                          .updateInboxStatus(item.id, "approved")
                          .then(() => refreshInbox(client))
                          .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                          .finally(() => setBusy(false));
                      }}
                    >
                      <BrandIcon name="status/confirm" size={13.5} />
                      <span>{t("auto.approve")}</span>
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => {
                        if (!client) return;
                        setBusy(true);
                        void client
                          .updateInboxStatus(item.id, "dismissed")
                          .then(() => refreshInbox(client))
                          .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                          .finally(() => setBusy(false));
                      }}
                    >
                      <BrandIcon name="navigation/close" size={13.5} />
                      <span>{t("auto.dismiss")}</span>
                    </Button>
                  </>
                )}
                <IconButton
                  size="sm"
                  label={t("common.delete")}
                  onClick={() => {
                    if (!client) return;
                    void client
                      .deleteInboxItem(item.id)
                      .then(() => refreshInbox(client))
                      .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
                  }}
                >
                  <BrandIcon name="action/delete" size={14} />
                </IconButton>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <div className="space-y-4">
      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{ background: "var(--pi-paper)", color: "var(--pi-danger)" }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadAll()}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      {mode === "timers" ? (
        timersSection
      ) : mode === "inbox" ? (
        inboxSection
      ) : (
        <Tabs value={tab} onValueChange={setTab} className="space-y-3">
          <TabsList>
            <TabsTrigger value="timers">{t("auto.tabTimers")}</TabsTrigger>
            <TabsTrigger value="inbox">
              {t("auto.tabInbox")}
              {unreadInboxCount > 0 ? ` (${unreadInboxCount})` : ""}
            </TabsTrigger>
          </TabsList>

          <TabsContent value="timers">{timersSection}</TabsContent>
          <TabsContent value="inbox">{inboxSection}</TabsContent>
        </Tabs>
      )}

      <Dialog
        open={editorOpen}
        onOpenChange={setEditorOpen}
        title={editingTimer ? t("auto.editTimer") : t("auto.newTimer")}
      >
        <div className="space-y-3">
          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("auto.timerName")}
            </label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>

          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("auto.timerPrompt")}
            </label>
            <Textarea rows={3} value={prompt} onChange={(e) => setPrompt(e.target.value)} />
          </div>

          {!editingTimer && localBots.length > 0 && (
            <div className="space-y-1">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("auto.targetBot")}
              </label>
              <Select
                value={botId || localBots[0].id}
                onValueChange={setBotId}
                options={localBots.map((b) => ({ value: b.id, label: b.name }))}
                className="w-full"
              />
            </div>
          )}

          <div className="space-y-1">
            <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("auto.triggerType")}
            </label>
            <Select
              value={triggerType}
              onValueChange={(v) =>
                setTriggerType(v as "once" | "daily" | "weekly" | "cron")
              }
              options={[
                { value: "daily", label: t("auto.typeDaily") },
                { value: "weekly", label: t("auto.typeWeekly") },
                { value: "once", label: t("auto.typeOnce") },
                { value: "cron", label: t("auto.typeCron") },
              ]}
              className="w-full"
            />
          </div>

          {triggerType === "once" && (
            <div className="grid grid-cols-2 gap-2">
              <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              <Input type="time" value={time} onChange={(e) => setTime(e.target.value)} />
            </div>
          )}

          {triggerType === "daily" && (
            <Input type="time" value={time} onChange={(e) => setTime(e.target.value)} />
          )}

          {triggerType === "weekly" && (
            <div className="space-y-2">
              <div className="flex flex-wrap gap-1.5">
                {[1, 2, 3, 4, 5, 6, 7].map((d) => {
                  const selected = days.includes(d);
                  return (
                    <button
                      key={d}
                      type="button"
                      onClick={() =>
                        setDays((prev) =>
                          prev.includes(d) ? prev.filter((x) => x !== d) : [...prev, d],
                        )
                      }
                      className="rounded-[8px] px-2.5 py-1 text-[12px] font-medium transition-colors"
                      style={{
                        background: selected ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                        color: selected ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                        border: "0.5px solid var(--pi-hairline)",
                      }}
                    >
                      {weekdayLabel(d, locale)}
                    </button>
                  );
                })}
              </div>
              <Input type="time" value={time} onChange={(e) => setTime(e.target.value)} />
            </div>
          )}

          {triggerType === "cron" && (
            <Input
              value={cronExpression}
              onChange={(e) => setCronExpression(e.target.value)}
              placeholder="0 9 * * 1-5"
              style={{ fontFamily: "var(--pi-font-mono)" }}
            />
          )}

          {formError && (
            <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
              {formError}
            </div>
          )}

          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setEditorOpen(false)}>
              {t("common.cancel")}
            </Button>
            <Button variant="primary" disabled={busy} onClick={() => void handleSaveTimer()}>
              {t("common.save")}
            </Button>
          </div>
        </div>
      </Dialog>

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteTarget(null);
        }}
        title={t("auto.deleteTimerTitle")}
        description={deleteTarget?.name || ""}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          const id = deleteTarget.id;
          setDeleteTarget(null);
          void client
            .deleteTimer(id)
            .then(() => loadAll())
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"));
        }}
      />
    </div>
  );
}

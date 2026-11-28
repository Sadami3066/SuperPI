import { useCallback, useEffect, useMemo, useState } from "react";
import { IconClock } from "@tabler/icons-react";
import { formatRelativeTime } from "../../api/mappers";
import type { HostSaveTimerRequest, HostTimerDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  Dialog,
  EmptyState,
  IconButton,
  Input,
  Select,
  Skeleton,
  Switch,
  Textarea,
} from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";
import { formatTimerSchedule, validateTimerDraft, weekdayLabel } from "./timerSchedule";

const SUPER_ROUTER_SESSION_ID = "super:router";
const SUPER_WORKER_PREFIX = "super:worker:";

function isSuperSessionId(sessionId: string): boolean {
  return sessionId === SUPER_ROUTER_SESSION_ID || sessionId.startsWith(SUPER_WORKER_PREFIX);
}

function parseWorkerIdFromTimerSession(sessionId: string): string | null {
  return sessionId.startsWith(SUPER_WORKER_PREFIX)
    ? sessionId.slice(SUPER_WORKER_PREFIX.length)
    : null;
}

export function AutomationsSettings({
  scope = "pi",
  onOpenSession,
  onOpenSuperMain,
  onOpenSuperWorker,
}: {
  scope?: "pi" | "super";
  onOpenSession?: (sessionId: string) => void;
  onOpenSuperMain?: () => void;
  onOpenSuperWorker?: (workerId: string) => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const locale = useSettingsStore((s) => s.locale);
  const bots = useBotStore((s) => s.bots);
  const summariesById = useSessionStore((s) => s.summariesById);
  const superState = useSuperStore((s) => s.state);
  const refreshSuperState = useSuperStore((s) => s.refreshState);

  const [timers, setTimers] = useState<HostTimerDto[]>([]);
  const [query, setQuery] = useState("");
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
  const [targetValue, setTargetValue] = useState("");
  const [enabledDraft, setEnabledDraft] = useState(true);
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<HostTimerDto | null>(null);

  const isSuperScope = scope === "super";
  const localBots = useMemo(() => bots.filter((b) => !b.isRemote), [bots]);
  const localBotIds = useMemo(() => new Set(localBots.map((b) => b.id)), [localBots]);
  const botNameById = useMemo(() => new Map(bots.map((b) => [b.id, b.name])), [bots]);
  const workers = useMemo(() => superState?.workers ?? [], [superState?.workers]);

  const localSessions = useMemo(
    () =>
      Object.values(summariesById)
        .filter((s) => !s.archived && localBotIds.has(s.botId))
        .toSorted((a, b) => b.updatedAtMs - a.updatedAtMs),
    [localBotIds, summariesById],
  );

  const loadTimers = useCallback(async () => {
    if (!client) return;
    setLoading(true);
    setError(null);
    try {
      const [timerList] = await Promise.all([
        client.listTimers(scope),
        isSuperScope ? refreshSuperState(client) : Promise.resolve(),
      ]);
      const scoped = timerList.filter((item) =>
        isSuperScope ? isSuperSessionId(item.sessionId) : !isSuperSessionId(item.sessionId),
      );
      setTimers(scoped);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load timers");
    } finally {
      setLoading(false);
    }
  }, [client, isSuperScope, refreshSuperState, scope]);

  useEffect(() => {
    void loadTimers();
  }, [loadTimers]);

  const targetOptions = useMemo(() => {
    if (isSuperScope) {
      const opts = [{ value: `sess:${SUPER_ROUTER_SESSION_ID}`, label: t("auto.targetSuperMain") }];
      for (const w of workers.toSorted((a, b) => b.updatedAt - a.updatedAt)) {
        opts.push({
          value: `sess:${SUPER_WORKER_PREFIX}${w.workerId}`,
          label: `${w.botName} · ${w.title || w.workerId}`,
        });
      }
      return opts;
    }
    const opts: Array<{ value: string; label: string }> = [];
    for (const s of localSessions.slice(0, 30)) {
      const botName = botNameById.get(s.botId);
      const title = s.title || t("nav.untitledChat");
      opts.push({
        value: `sess:${s.id}`,
        label: botName ? `${botName} · ${title}` : title,
      });
    }
    if (editingTimer?.sessionId) {
      const editingVal = `sess:${editingTimer.sessionId}`;
      if (!opts.some((o) => o.value === editingVal)) {
        const s = summariesById[editingTimer.sessionId];
        const botName = s ? botNameById.get(s.botId) : undefined;
        const title = s?.title || editingTimer.sessionId;
        opts.unshift({
          value: editingVal,
          label: botName ? `${botName} · ${title}` : title,
        });
      }
    }
    for (const b of localBots) {
      opts.push({
        value: `bot:${b.id}`,
        label: `${b.name} (${t("nav.newChat")})`,
      });
    }
    return opts;
  }, [botNameById, editingTimer?.sessionId, isSuperScope, localBots, localSessions, summariesById, t, workers]);

  const resolveTimerTargetLabel = useCallback(
    (timer: HostTimerDto): string | null => {
      if (!timer.sessionId) return null;
      if (timer.sessionId === SUPER_ROUTER_SESSION_ID) {
        return t("auto.targetSuperMain");
      }
      const workerId = parseWorkerIdFromTimerSession(timer.sessionId);
      if (workerId) {
        const w = workers.find((x) => x.workerId === workerId);
        return w ? `${w.botName} · ${w.title || workerId}` : workerId;
      }
      const sess = summariesById[timer.sessionId];
      if (sess) {
        const botName = botNameById.get(sess.botId);
        const title = sess.title || t("nav.untitledChat");
        return botName ? `${botName} · ${title}` : title;
      }
      return null;
    },
    [botNameById, summariesById, t, workers],
  );

  const formatTimerError = (code: string): string => {
    const upper = code.trim().toUpperCase();
    if (upper === "SESSION_MISSING") return t("auto.errorSessionMissing");
    if (upper === "OPEN_FAILED") return t("auto.errorOpenFailed");
    if (upper === "DELIVERY_FAILED") return t("auto.errorDeliveryFailed");
    return code;
  };

  const openEditor = (existing?: HostTimerDto) => {
    setEditingTimer(existing ?? null);
    setName(existing?.name ?? "");
    setPrompt(existing?.prompt ?? "");
    setTriggerType(existing?.triggerType ?? "daily");
    setDate(existing?.date || new Date().toISOString().slice(0, 10));
    setTime(existing?.time || "09:00");
    setDays(existing?.days.length ? existing.days : [1, 2, 3, 4, 5]);
    setCronExpression(existing?.cronExpression || "0 9 * * 1-5");
    setEnabledDraft(existing ? existing.enabled : true);
    if (existing?.sessionId) {
      setTargetValue(`sess:${existing.sessionId}`);
    } else {
      setTargetValue(targetOptions[0]?.value ?? "");
    }
    setFormError(null);
    setEditorOpen(true);
  };

  const handleSaveTimer = async () => {
    if (!client) return;
    const trimmedPrompt = prompt.trim();
    const trimmedName =
      name.trim() ||
      (trimmedPrompt ? trimmedPrompt.replace(/\s+/g, " ").slice(0, 28) : "");

    const isSessionTarget = targetValue.startsWith("sess:");
    const isBotTarget = targetValue.startsWith("bot:");
    const targetSessionId = isSessionTarget
      ? targetValue.slice("sess:".length)
      : isSuperScope
        ? SUPER_ROUTER_SESSION_ID
        : undefined;
    const targetBotId = isBotTarget ? targetValue.slice("bot:".length) : undefined;

    const payload: HostSaveTimerRequest = {
      name: trimmedName,
      prompt: trimmedPrompt,
      triggerType,
      date: triggerType === "once" ? date.trim() : undefined,
      time: triggerType !== "cron" ? time.trim() : undefined,
      days: triggerType === "weekly" ? days : undefined,
      cronExpression: triggerType === "cron" ? cronExpression.trim() : undefined,
      enabled: enabledDraft,
      sessionId: targetSessionId,
      botId: targetBotId,
    };

    const issue = validateTimerDraft(payload);
    if (issue) {
      setFormError(t("auto.invalidSchedule"));
      return;
    }
    if (triggerType === "once" && enabledDraft) {
      const scheduled = new Date(`${date.trim()}T${time.trim()}`);
      if (!Number.isNaN(scheduled.getTime()) && scheduled.getTime() <= Date.now()) {
        setFormError(t("auto.pastTimeError"));
        return;
      }
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
      await loadTimers();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Failed to save timer");
    } finally {
      setBusy(false);
    }
  };

  const handleOpenTimerTarget = (timer: HostTimerDto) => {
    if (!timer.sessionId) return;
    if (timer.sessionId === SUPER_ROUTER_SESSION_ID) {
      onOpenSuperMain?.();
      return;
    }
    const workerId = parseWorkerIdFromTimerSession(timer.sessionId);
    if (workerId) {
      onOpenSuperWorker?.(workerId);
      return;
    }
    onOpenSession?.(timer.sessionId);
  };

  const filteredTimers = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return timers;
    return timers.filter((timer) => {
      const targetLabel = (resolveTimerTargetLabel(timer) ?? "").toLowerCase();
      return (
        timer.name.toLowerCase().includes(q) ||
        timer.prompt.toLowerCase().includes(q) ||
        targetLabel.includes(q)
      );
    });
  }, [query, resolveTimerTargetLabel, timers]);

  const activeTimers = useMemo(
    () =>
      filteredTimers
        .filter((x) => x.enabled)
        .toSorted((a, b) => (a.nextRunAt ?? Number.MAX_SAFE_INTEGER) - (b.nextRunAt ?? Number.MAX_SAFE_INTEGER)),
    [filteredTimers],
  );

  const inactiveTimers = useMemo(
    () => filteredTimers.filter((x) => !x.enabled),
    [filteredTimers],
  );

  const renderTimerCard = (timer: HostTimerDto) => {
    const targetLabel = resolveTimerTargetLabel(timer);
    return (
      <Card key={timer.id} className={`space-y-2 p-4 transition-opacity ${timer.enabled ? "" : "opacity-65"}`}>
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-[14px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                {timer.name}
              </span>
              <Badge tone="neutral">{formatTimerSchedule(timer, locale)}</Badge>
              {targetLabel && <Badge tone="accent">{targetLabel}</Badge>}
              {timer.lastError && <Badge tone="danger">{formatTimerError(timer.lastError)}</Badge>}
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
                .then(() => loadTimers())
                .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
                .finally(() => setBusy(false));
            }}
          />
        </div>

        <div
          className="flex flex-wrap items-center justify-end gap-1.5 pt-2"
          style={{ borderTop: "1px solid var(--pi-canvas-border)" }}
        >
          {timer.sessionId && (
            <Button variant="ghost" size="sm" onClick={() => handleOpenTimerTarget(timer)}>
              <BrandIcon name="navigation/external_link" size={13.5} />
              <span>{t("auto.openSession")}</span>
            </Button>
          )}
          <Button
            variant="ghost"
            size="sm"
            disabled={busy}
            onClick={() => {
              if (!client) return;
              setBusy(true);
              void client
                .runTimerNow(timer.id)
                .then(() => loadTimers())
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
    );
  };

  return (
    <div className="space-y-4">
      {error && (
        <div
          className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
          style={{
            background: "var(--pi-paper)",
            border: "1px solid var(--pi-canvas-border)",
            color: "var(--pi-danger)",
          }}
        >
          <span className="truncate">{error}</span>
          <Button variant="ghost" size="sm" onClick={() => void loadTimers()}>
            {t("conn.retry")}
          </Button>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2.5">
        <div className="relative min-w-[200px] flex-1">
          <BrandIcon
            name="action/search"
            size={14}
            className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2"
            style={{ color: "var(--pi-ink-tertiary)" }}
          />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("auto.searchPlaceholder")}
            className="h-8.5 pl-8.5 text-[12.5px]"
          />
        </div>
        <Button variant="secondary" size="sm" onClick={() => openEditor()}>
          <BrandIcon name="action/add" size={14} />
          <span>{t("auto.newTimer")}</span>
        </Button>
      </div>

      {loading && timers.length === 0 ? (
        <div className="space-y-2.5" aria-busy="true">
          <Card className="space-y-2.5 p-4">
            <Skeleton className="h-4 w-44" />
            <Skeleton className="h-3 w-4/5" />
            <Skeleton className="h-3 w-32" />
          </Card>
          <Card className="space-y-2.5 p-4">
            <Skeleton className="h-4 w-36" />
            <Skeleton className="h-3 w-2/3" />
          </Card>
        </div>
      ) : timers.length === 0 ? (
        <EmptyState
          icon={<IconClock size={20} stroke={1.65} />}
          title={t("auto.emptyTimers")}
          description={isSuperScope ? t("auto.superEmptyDesc") : undefined}
          action={
            <Button variant="secondary" size="sm" onClick={() => openEditor()}>
              <BrandIcon name="action/add" size={14} />
              <span>{t("auto.newTimer")}</span>
            </Button>
          }
        />
      ) : filteredTimers.length === 0 ? (
        <EmptyState
          icon={<BrandIcon name="action/search" size={18} />}
          title={t("files.searchEmpty")}
        />
      ) : (
        <div className="space-y-5">
          {activeTimers.length > 0 && (
            <div className="space-y-2.5">
              <div className="px-0.5 text-[11.5px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("auto.groupActive")} ({activeTimers.length})
              </div>
              {activeTimers.map(renderTimerCard)}
            </div>
          )}
          {inactiveTimers.length > 0 && (
            <div className="space-y-2.5">
              <div className="px-0.5 text-[11.5px] font-semibold" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("auto.groupInactive")} ({inactiveTimers.length})
              </div>
              {inactiveTimers.map(renderTimerCard)}
            </div>
          )}
        </div>
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

          {targetOptions.length > 0 && (
            <div className="space-y-1">
              <label className="block text-[12px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
                {t("auto.targetSession")}
              </label>
              <Select
                value={targetValue || targetOptions[0].value}
                onValueChange={setTargetValue}
                options={targetOptions}
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
                        background: selected ? "var(--pi-accent-soft)" : "var(--pi-chrome)",
                        color: selected ? "var(--pi-accent)" : "var(--pi-ink-secondary)",
                        border: "1px solid var(--pi-canvas-border)",
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

          <div className="flex items-center justify-between pt-1">
            <span className="text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("auto.enabledLabel")}
            </span>
            <Switch checked={enabledDraft} onCheckedChange={setEnabledDraft} />
          </div>

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
        description={deleteTarget?.name ?? ""}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        danger
        onConfirm={() => {
          if (!client || !deleteTarget) return;
          const targetId = deleteTarget.id;
          setDeleteTarget(null);
          setBusy(true);
          void client
            .deleteTimer(targetId)
            .then(() => loadTimers())
            .catch((err) => setError(err instanceof Error ? err.message : "Failed"))
            .finally(() => setBusy(false));
        }}
      />
    </div>
  );
}

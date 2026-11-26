import { useEffect, useMemo, useRef, useState } from "react";
import {
  IconArrowUp,
  IconLayoutSidebarRight,
  IconPhoto,
  IconPlayerStop,
  IconRefresh,
  IconSettings,
  IconTrash,
  IconUsersGroup,
  IconX,
} from "@tabler/icons-react";
import {
  computeContextPercent,
  mapHostMessageToTurn,
  normalizeThinkingLevel,
} from "../../api/mappers";
import type { HostWorkerDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { SUPER_SESSION_ID, useSuperStore } from "../../stores/superStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Badge, Button, ConfirmDialog, IconButton, Popover } from "../../ui";
import { AskQuestionCard } from "../chat/AskQuestionCard";
import { ContextMeter } from "../chat/ContextMeter";
import { ModelPicker } from "../chat/ModelPicker";
import { StreamingTurnView } from "../chat/StreamingTurnView";
import { TurnItem } from "../chat/TurnItem";
import { BrandIcon, PiActivityIcon, PiMascot } from "../shared/BrandAssets";
import { WindowControls } from "../shell/WindowControls";
import { SuperTeamDialog } from "./SuperTeamDialog";
import { WorkerDetailDialog } from "./WorkerDetailDialog";

interface StagedImage {
  id: string;
  name: string;
  mimeType: string;
  data: string;
  previewUrl: string;
}

function workerTone(status: string): "accent" | "warning" | "success" | "danger" | "neutral" {
  if (status === "running" || status === "working") return "accent";
  if (status === "waiting_approval") return "warning";
  if (status === "failed" || status === "error") return "danger";
  if (status === "done" || status === "completed") return "success";
  return "neutral";
}

function workerPriority(w: HostWorkerDto): number {
  if (w.status === "waiting_approval") return 0;
  if (w.status === "running" || w.status === "working") return 1;
  if (w.status === "idle") return 2;
  return 3;
}

export function SuperWorkspace({
  showWindowControls,
  onOpenProvidersSettings,
}: {
  showWindowControls: boolean;
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);

  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);

  const state = useSuperStore((s) => s.state);
  const team = useSuperStore((s) => s.team);
  const loading = useSuperStore((s) => s.loading);
  const error = useSuperStore((s) => s.error);
  const composerText = useSuperStore((s) => s.composerText);
  const setComposerText = useSuperStore((s) => s.setComposerText);
  const refreshState = useSuperStore((s) => s.refreshState);
  const refreshTeam = useSuperStore((s) => s.refreshTeam);
  const sendPrompt = useSuperStore((s) => s.sendPrompt);
  const abort = useSuperStore((s) => s.abort);
  const compact = useSuperStore((s) => s.compact);
  const resetThread = useSuperStore((s) => s.resetThread);
  const setModel = useSuperStore((s) => s.setModel);
  const answerQuestion = useSuperStore((s) => s.answerQuestion);
  const dismissQuestion = useSuperStore((s) => s.dismissQuestion);
  const stopWorker = useSuperStore((s) => s.stopWorker);
  const deleteWorker = useSuperStore((s) => s.deleteWorker);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);

  const liveRun = useStreamStore((s) => s.activeBySession[SUPER_SESSION_ID]);
  const isStreaming = Boolean(liveRun?.streaming);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const openPanel = useWorkbenchStore((s) => s.openPanel);
  const closeWorkbench = useWorkbenchStore((s) => s.close);

  const [teamDialogOpen, setTeamDialogOpen] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [sending, setSending] = useState(false);
  const [images, setImages] = useState<StagedImage[]>([]);
  const [showWorkersRail, setShowWorkersRail] = useState(true);
  const [deleteWorkerTarget, setDeleteWorkerTarget] = useState<HostWorkerDto | null>(null);

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!client) return;
    void refreshState(client);
    void refreshTeam(client);
  }, [client, refreshState, refreshTeam]);

  const turns = useMemo(() => {
    if (!state) return [];
    return state.messages
      .filter((m) => !m.streaming)
      .map((m) => mapHostMessageToTurn(m, locale));
  }, [locale, state]);

  const workers = useMemo(() => {
    const list = state?.workers ?? [];
    return [...list].sort((a, b) => {
      const pa = workerPriority(a);
      const pb = workerPriority(b);
      if (pa !== pb) return pa - pb;
      return b.updatedAt - a.updatedAt;
    });
  }, [state?.workers]);

  const runningWorkersCount = useMemo(
    () =>
      workers.filter(
        (w) => w.status === "running" || w.status === "working" || w.status === "waiting_approval",
      ).length,
    [workers],
  );

  const enabledTeamCount = useMemo(
    () => team.filter((b) => b.enabledInSuper).length,
    [team],
  );

  const currentModel = state?.providerId && state?.modelId
    ? `${state.providerId}/${state.modelId}`
    : hostSettings
      ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}`
      : "";
  const currentThinking = normalizeThinkingLevel(
    state?.thinkingLevel || hostSettings?.defaultThinkingLevel,
  );
  const usage = state?.usage ?? null;
  const ctxPct = computeContextPercent(usage);
  const busy = Boolean(state?.busy || isStreaming);
  const compactProcess = hostSettings?.processDetail === "compact";
  const isFrameless = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");

  const scrollToBottom = () => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  };

  useEffect(() => {
    scrollToBottom();
  }, [turns.length, state?.pendingQuestion?.requestId]);

  const handleAttachImages = async (files: FileList | null) => {
    if (!files || files.length === 0) return;
    const next: StagedImage[] = [];
    for (const file of Array.from(files)) {
      if (!file.type.startsWith("image/") || file.size > 10 * 1024 * 1024) continue;
      const dataUrl = await new Promise<string>((resolve) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result ?? ""));
        reader.onerror = () => resolve("");
        reader.readAsDataURL(file);
      });
      const comma = dataUrl.indexOf(",");
      if (comma <= 0) continue;
      next.push({
        id: `${file.name}-${Date.now()}-${next.length}`,
        name: file.name,
        mimeType: file.type,
        data: dataUrl.slice(comma + 1),
        previewUrl: dataUrl,
      });
    }
    if (next.length > 0) {
      setImages((prev) => [...prev, ...next].slice(0, 8));
    }
  };

  const handleSend = async () => {
    if (!client || sending) return;
    const text = composerText.trim();
    if (!text && images.length === 0) return;
    setSending(true);
    const snapshotImages = images;
    setImages([]);
    try {
      await sendPrompt(
        client,
        text,
        busy && hostSettings?.sendWhileRunning !== "queue",
        snapshotImages.map((img) => ({
          data: img.data,
          mimeType: img.mimeType,
          name: img.name,
        })),
      );
      scrollToBottom();
    } catch {
      setImages(snapshotImages);
    } finally {
      setSending(false);
    }
  };

  const lastAssistantIdx = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return i;
    }
    return -1;
  }, [turns]);

  const isEmpty = turns.length === 0 && !liveRun && !state?.pendingQuestion;

  return (
    <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
      <header
        className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between gap-3 pl-4"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 12,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
        onDoubleClick={() => {
          if (isFrameless) void windowToggleMaximize();
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-2">
          <BrandIcon name="ai/agent" size={16} />
          <span className="truncate text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {t("super.title")}
          </span>
          {runningWorkersCount > 0 && (
            <Badge tone="accent">
              {t("super.workersRunning", { count: runningWorkersCount })}
            </Badge>
          )}
          <span className="hidden text-[12px] sm:inline" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("super.teamCount", { count: enabledTeamCount })}
          </span>
        </div>

        <div className="pi-no-drag flex items-center gap-1">
          <Popover
            align="end"
            className="w-[260px] p-3.5"
            trigger={<ContextMeter percent={ctxPct} usage={usage} />}
          >
            <div className="space-y-3 text-[12.5px]">
              <div className="font-semibold" style={{ color: "var(--pi-ink)" }}>
                {t("usage.title")}
              </div>
              {usage ? (
                <div className="space-y-1.5" style={{ color: "var(--pi-ink-secondary)" }}>
                  <div className="flex justify-between">
                    <span>{t("usage.context")}</span>
                    <span className="tabular-nums">
                      {usage.contextTokens.toLocaleString()} / {usage.contextWindow.toLocaleString()}
                    </span>
                  </div>
                  <div className="flex justify-between">
                    <span>{t("usage.input")}</span>
                    <span className="tabular-nums">{usage.inputTokens.toLocaleString()}</span>
                  </div>
                  <div className="flex justify-between">
                    <span>{t("usage.output")}</span>
                    <span className="tabular-nums">{usage.outputTokens.toLocaleString()}</span>
                  </div>
                </div>
              ) : (
                <div style={{ color: "var(--pi-ink-tertiary)" }}>—</div>
              )}
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  className="flex-1"
                  disabled={compacting || busy}
                  onClick={() => {
                    if (!client) return;
                    setCompacting(true);
                    void compact(client).finally(() => setCompacting(false));
                  }}
                >
                  {compacting ? t("usage.compacting") : t("usage.compactNow")}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={resetting || busy}
                  onClick={() => setConfirmReset(true)}
                >
                  {t("super.reset")}
                </Button>
              </div>
            </div>
          </Popover>

          <Button
            variant="ghost"
            size="sm"
            onClick={() => setTeamDialogOpen(true)}
          >
            <IconUsersGroup size={14.5} stroke={1.7} />
            <span className="hidden sm:inline">{t("super.teamButton")}</span>
          </Button>

          <IconButton
            size="sm"
            label={t("super.workersTitle")}
            onClick={() => setShowWorkersRail((v) => !v)}
          >
            <IconSettings size={14} stroke={1.7} className="hidden" />
            <Badge tone={runningWorkersCount > 0 ? "accent" : "neutral"}>
              {workers.length}
            </Badge>
          </IconButton>

          <IconButton
            size="sm"
            label={t("chat.workbenchToggle")}
            onClick={() => {
              if (workbenchOpen) {
                closeWorkbench();
              } else {
                openPanel(activePanelId || "files");
              }
            }}
          >
            <IconLayoutSidebarRight
              size={15.5}
              stroke={1.65}
              style={{ color: workbenchOpen ? "var(--pi-accent)" : undefined }}
            />
          </IconButton>

          {showWindowControls && <WindowControls />}
        </div>
      </header>

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <div className="flex min-h-0 min-w-0 flex-1 flex-col">
          {error && (
            <div className="px-4 pt-2">
              <div
                className="flex items-center justify-between gap-2 rounded-[10px] px-3 py-2 text-[12.5px]"
                style={{
                  background: "var(--pi-paper)",
                  border: "0.5px solid var(--pi-hairline)",
                  color: "var(--pi-danger)",
                }}
              >
                <span className="truncate">{error}</span>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    if (client) void refreshState(client);
                  }}
                >
                  <IconRefresh size={13} />
                  <span>{t("conn.retry")}</span>
                </Button>
              </div>
            </div>
          )}

          {isEmpty ? (
            <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-6">
              <div className="flex w-full max-w-[540px] flex-col items-center text-center">
                <PiMascot mood={loading ? "thinking" : "wave"} size={64} dark={dark} className="mb-4" />
                <h2 className="text-[20px] font-semibold tracking-tight" style={{ color: "var(--pi-ink)" }}>
                  {t("super.heroTitle")}
                </h2>
                <p
                  className="mt-1.5 text-[13.5px] leading-[1.6]"
                  style={{ color: "var(--pi-ink-secondary)" }}
                >
                  {t("super.heroDesc")}
                </p>
                {enabledTeamCount === 0 && (
                  <Button
                    variant="secondary"
                    size="sm"
                    className="mt-4"
                    onClick={() => setTeamDialogOpen(true)}
                  >
                    <IconUsersGroup size={14} />
                    <span>{t("super.configureTeam")}</span>
                  </Button>
                )}
              </div>
            </div>
          ) : (
            <div ref={scrollRef} data-scrollable className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
              <div className="mx-auto w-full max-w-[760px] space-y-1">
                {turns.map((turn, idx) => (
                  <TurnItem
                    key={turn.id}
                    turn={turn}
                    sessionId={SUPER_SESSION_ID}
                    isLastAssistant={idx === lastAssistantIdx}
                    compactProcess={compactProcess}
                    dark={dark}
                    onQuote={(quoted) => {
                      setComposerText(composerText ? `${composerText}\n\n> ${quoted}` : `> ${quoted}`);
                    }}
                    onRetry={() => {
                      if (client) void refreshState(client);
                    }}
                  />
                ))}

                <StreamingTurnView
                  sessionId={SUPER_SESSION_ID}
                  compactProcess={compactProcess}
                  dark={dark}
                  onRetry={() => {
                    if (client) void refreshState(client);
                  }}
                  onLayoutTick={scrollToBottom}
                />

                {state?.activeToolText && !liveRun && (
                  <div className="flex items-center gap-2 py-1 text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
                    <PiActivityIcon size={16} dark={dark} />
                    <span>{state.activeToolText}</span>
                  </div>
                )}

                {state?.pendingQuestion && client && (
                  <AskQuestionCard
                    question={state.pendingQuestion}
                    onSubmit={(reqId, answers) => answerQuestion(client, reqId, answers)}
                    onDismiss={(reqId) => dismissQuestion(client, reqId)}
                  />
                )}
              </div>
            </div>
          )}

          <div className="shrink-0 px-4 pt-2 pb-4">
            <div
              className="mx-auto w-full max-w-[760px] rounded-[22px] p-2.5"
              style={{
                background: "var(--pi-surface)",
                border: "0.5px solid var(--pi-hairline-strong)",
                boxShadow: "var(--pi-shadow-card)",
              }}
            >
              {images.length > 0 && (
                <div className="mb-2 flex flex-wrap gap-2 px-1">
                  {images.map((img) => (
                    <div
                      key={img.id}
                      className="relative flex items-center gap-1.5 rounded-[10px] py-1 pr-6 pl-1.5 text-[12px]"
                      style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
                    >
                      <img src={img.previewUrl} alt={img.name} className="h-6 w-6 rounded object-cover" />
                      <span className="max-w-[120px] truncate">{img.name}</span>
                      <button
                        type="button"
                        onClick={() => setImages((prev) => prev.filter((x) => x.id !== img.id))}
                        className="absolute top-1 right-1 flex h-4 w-4 items-center justify-center rounded-full hover:bg-[var(--pi-hover)]"
                      >
                        <IconX size={11} />
                      </button>
                    </div>
                  ))}
                </div>
              )}

              <textarea
                rows={2}
                value={composerText}
                onChange={(e) => setComposerText(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key !== "Enter" || e.nativeEvent.isComposing) return;
                  const useModEnter = hostSettings?.sendKey === "mod_enter";
                  const trigger = useModEnter ? e.metaKey || e.ctrlKey : !e.shiftKey;
                  if (trigger) {
                    e.preventDefault();
                    void handleSend();
                  }
                }}
                placeholder={t("super.composerPlaceholder")}
                className="w-full resize-none bg-transparent px-2 py-1 text-[14px] leading-[1.5] outline-none placeholder:text-[var(--pi-ink-tertiary)]"
                style={{ color: "var(--pi-ink)" }}
              />

              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                multiple
                className="hidden"
                onChange={(e) => {
                  void handleAttachImages(e.target.files);
                  e.target.value = "";
                }}
              />

              <div className="mt-1.5 flex items-center justify-between gap-2">
                <div className="flex items-center gap-1">
                  <IconButton
                    size="sm"
                    label={t("chat.attach")}
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <IconPhoto size={15.5} stroke={1.7} />
                  </IconButton>

                  <ModelPicker
                    providers={providers}
                    currentModel={currentModel}
                    currentThinking={currentThinking}
                    dark={dark}
                    onSelect={(modelSpec, thinking) => {
                      if (!client) return;
                      const slash = modelSpec.indexOf("/");
                      if (slash < 1) return;
                      void setModel(
                        client,
                        modelSpec.slice(0, slash),
                        modelSpec.slice(slash + 1),
                        thinking.toUpperCase(),
                      );
                    }}
                    onOpenProvidersSettings={onOpenProvidersSettings}
                  />
                </div>

                <div className="flex items-center gap-1.5">
                  {busy && (
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => {
                        if (client) void abort(client);
                      }}
                    >
                      <IconPlayerStop size={14} />
                      <span>{t("chat.stop")}</span>
                    </Button>
                  )}

                  <Button
                    variant="primary"
                    size="sm"
                    disabled={sending || (!composerText.trim() && images.length === 0)}
                    onClick={() => void handleSend()}
                  >
                    <IconArrowUp size={14.5} stroke={2} />
                    <span>{busy ? t("chat.steerTag") : t("chat.send")}</span>
                  </Button>
                </div>
              </div>
            </div>
          </div>
        </div>

        {showWorkersRail && (
          <aside
            className="hidden w-[296px] shrink-0 flex-col lg:flex"
            style={{
              background: "var(--pi-paper)",
              borderLeft: "0.5px solid var(--pi-hairline)",
            }}
          >
            <div
              className="flex h-[40px] shrink-0 items-center justify-between px-3.5"
              style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
            >
              <span className="text-[12.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                {t("super.workersTitle")} ({workers.length})
              </span>
              <IconButton
                size="sm"
                label={t("common.refresh")}
                onClick={() => {
                  if (client) void refreshState(client);
                }}
              >
                <IconRefresh size={13.5} stroke={1.7} />
              </IconButton>
            </div>

            <div data-scrollable className="min-h-0 flex-1 space-y-2.5 overflow-y-auto p-3">
              {workers.length === 0 ? (
                <div
                  className="rounded-[12px] px-3 py-6 text-center text-[12.5px] leading-[1.55]"
                  style={{
                    background: "var(--pi-surface)",
                    border: "0.5px solid var(--pi-hairline)",
                    color: "var(--pi-ink-tertiary)",
                  }}
                >
                  {t("super.noWorkers")}
                </div>
              ) : (
                workers.map((w) => {
                  const isActive =
                    w.status === "running" ||
                    w.status === "working" ||
                    w.status === "waiting_approval";
                  return (
                    <div
                      key={w.workerId}
                      className="rounded-[12px] p-3 transition-colors"
                      style={{
                        background: "var(--pi-surface)",
                        border: "0.5px solid var(--pi-hairline)",
                      }}
                    >
                      <button
                        type="button"
                        onClick={() => setSelectedWorkerId(w.workerId)}
                        className="w-full space-y-1.5 text-left"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="truncate text-[13px] font-semibold" style={{ color: "var(--pi-ink)" }}>
                              {w.title || w.botName}
                            </div>
                            <div className="truncate text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                              {w.botName}
                              {w.isRemote && w.deviceName ? ` · ${w.deviceName}` : ""}
                            </div>
                          </div>
                          <Badge tone={workerTone(w.status)}>{w.status}</Badge>
                        </div>

                        {(w.activeStepText || w.lastSummary || w.lastError) && (
                          <div
                            className="line-clamp-2 text-[12px] leading-[1.45]"
                            style={{ color: w.lastError ? "var(--pi-danger)" : "var(--pi-ink-secondary)" }}
                          >
                            {w.activeStepText || w.lastError || w.lastSummary}
                          </div>
                        )}

                        {w.steps.length > 0 && (
                          <div className="space-y-1 pt-0.5">
                            {w.steps.slice(-2).map((step, idx) => (
                              <div
                                key={`${step.label}-${idx}`}
                                className="flex items-center justify-between gap-2 text-[11px]"
                                style={{ color: step.isError ? "var(--pi-danger)" : "var(--pi-ink-tertiary)" }}
                              >
                                <span className="truncate">{step.label}</span>
                                <span className="shrink-0">{step.status}</span>
                              </div>
                            ))}
                          </div>
                        )}
                      </button>

                      <div
                        className="mt-2.5 flex items-center justify-between gap-1 pt-2"
                        style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
                      >
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setSelectedWorkerId(w.workerId)}
                        >
                          {t("super.inspectWorker")}
                        </Button>
                        <div className="flex items-center gap-1">
                          {isActive && (
                            <IconButton
                              size="sm"
                              label={t("chat.stop")}
                              onClick={() => {
                                if (client) void stopWorker(client, w.workerId);
                              }}
                            >
                              <IconPlayerStop size={13.5} />
                            </IconButton>
                          )}
                          <IconButton
                            size="sm"
                            label={t("common.delete")}
                            onClick={() => setDeleteWorkerTarget(w)}
                          >
                            <IconTrash size={13.5} />
                          </IconButton>
                        </div>
                      </div>
                    </div>
                  );
                })
              )}
            </div>
          </aside>
        )}
      </div>

      <WorkerDetailDialog onOpenProvidersSettings={onOpenProvidersSettings} />

      <SuperTeamDialog open={teamDialogOpen} onOpenChange={setTeamDialogOpen} />

      <ConfirmDialog
        open={confirmReset}
        onOpenChange={setConfirmReset}
        title={t("super.resetTitle")}
        description={t("super.resetDesc")}
        confirmLabel={t("super.reset")}
        cancelLabel={t("common.cancel")}
        danger={false}
        onConfirm={() => {
          if (!client) return;
          setResetting(true);
          setConfirmReset(false);
          void resetThread(client).finally(() => setResetting(false));
        }}
      />

      <ConfirmDialog
        open={Boolean(deleteWorkerTarget)}
        onOpenChange={(v) => {
          if (!v) setDeleteWorkerTarget(null);
        }}
        title={t("super.deleteWorkerTitle")}
        description={t("super.deleteWorkerDesc", {
          name: deleteWorkerTarget?.title || deleteWorkerTarget?.botName || "",
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !deleteWorkerTarget) return;
          const id = deleteWorkerTarget.workerId;
          setDeleteWorkerTarget(null);
          void deleteWorker(client, id);
        }}
      />
    </div>
  );
}

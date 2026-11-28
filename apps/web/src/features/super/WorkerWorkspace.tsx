import { useEffect, useMemo, useRef, useState } from "react";
import { IconArrowDown, IconArrowLeft, IconLayoutSidebarRight } from "@tabler/icons-react";
import { formatWorkerStatus } from "../../api/formatters";
import {
  computeContextPercent,
  mapHostMessageToTurn,
  normalizeThinkingLevel,
} from "../../api/mappers";
import type { HostWorkerDetailDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Badge, ConfirmDialog, IconButton, NoticeBar, Popover } from "../../ui";
import { AskQuestionCard } from "../chat/AskQuestionCard";
import { ContextMeter } from "../chat/ContextMeter";
import { ModelPicker } from "../chat/ModelPicker";
import { useChatScroll } from "../chat/scrollPolicy";
import { ToolApprovalCard } from "../chat/ToolApprovalCard";
import { TurnItem } from "../chat/TurnItem";
import { UsageSheet } from "../chat/UsageSheet";
import { BotAvatar, BrandIcon, PiActivityIcon } from "../shared/BrandAssets";

export function WorkerWorkspace({
  onOpenProvidersSettings,
}: {
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const isElectron = useConnectionStore((s) => Boolean(s.desktopEnv?.isElectron));
  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);
  const bots = useBotStore((s) => s.bots);

  const selectedWorkerId = useSuperStore((s) => s.selectedWorkerId);
  const superTeam = useSuperStore((s) => s.team);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);
  const stopWorker = useSuperStore((s) => s.stopWorker);
  const deleteWorker = useSuperStore((s) => s.deleteWorker);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const toggleWorkbench = useWorkbenchStore((s) => s.toggleWorkbench);

  const [detail, setDetail] = useState<HostWorkerDetailDto | null>(null);
  const [prompt, setPrompt] = useState("");
  const [sending, setSending] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  useEffect(() => {
    if (!client || !selectedWorkerId) {
      setDetail(null);
      setError(null);
      return undefined;
    }
    setDetail(null);
    setError(null);
    void client
      .getSuperWorker(selectedWorkerId)
      .then(setDetail)
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load worker"));

    const unsubscribe = client.streamSuperWorker(selectedWorkerId, {
      onSnapshot: (snap) => {
        setDetail(snap);
        setError(null);
      },
      onDeleted: () => {
        setSelectedWorkerId(null);
      },
      onError: (err) => {
        setError(err.message);
      },
    });
    return () => {
      unsubscribe();
    };
  }, [client, selectedWorkerId, setSelectedWorkerId]);

  const turns = useMemo(() => {
    if (!detail) return [];
    return detail.messages.map((m) => mapHostMessageToTurn(m, locale));
  }, [detail, locale]);

  const lastAssistantIdx = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return i;
    }
    return -1;
  }, [turns]);

  const worker = detail?.worker;

  const { matchedBot, resolvedAvatar } = useMemo(() => {
    if (!worker) return { matchedBot: undefined, resolvedAvatar: null as string | null };
    const bot =
      bots.find((b) => b.id === worker.botId) ??
      (worker.deviceId
        ? bots.find((b) => b.id === `remote:${worker.deviceId}:${worker.botId}`)
        : undefined) ??
      bots.find(
        (b) => b.remoteBotId === worker.botId && (!worker.deviceId || b.deviceId === worker.deviceId),
      );
    const teamMatch =
      superTeam.find((m) => m.botId === worker.botId) ??
      superTeam.find(
        (m) =>
          worker.deviceId &&
          m.deviceId === worker.deviceId &&
          (m.botId === worker.botId || m.name === worker.botName),
      );
    return {
      matchedBot: bot,
      resolvedAvatar: bot?.avatar ?? teamMatch?.avatar ?? null,
    };
  }, [bots, superTeam, worker]);

  const currentModel =
    detail?.providerId && detail?.modelId ? `${detail.providerId}/${detail.modelId}` : "";
  const currentThinking = normalizeThinkingLevel(detail?.thinkingLevel);
  const compactProcess = hostSettings?.processDetail === "compact";
  const usage = detail?.usage ?? null;
  const ctxPct = computeContextPercent(usage);
  const workerArtifactSessionId = selectedWorkerId ? `worker:${selectedWorkerId}` : "";
  const isRunning = Boolean(
    detail?.streaming || worker?.status === "running" || worker?.status === "working",
  );

  const { scrollRef, contentRef, showJumpToBottom, snapToBottom } = useChatScroll(
    selectedWorkerId || "",
    `${turns.length}:${detail?.worker.pendingQuestion?.requestId ?? ""}:${detail?.worker.pendingApproval?.id ?? ""}:${detail?.messages[detail.messages.length - 1]?.text.length ?? 0}`,
    { isStreaming: Boolean(detail?.streaming) },
  );

  const handleSend = async () => {
    if (!client || !selectedWorkerId || !prompt.trim() || sending) return;
    setSending(true);
    setError(null);
    try {
      await client.sendSuperWorkerPrompt(selectedWorkerId, { text: prompt.trim() });
      setPrompt("");
      snapToBottom();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send message");
    } finally {
      setSending(false);
    }
  };

  const handleStop = () => {
    if (!client || !selectedWorkerId) return;
    setError(null);
    void stopWorker(client, selectedWorkerId).catch((err) =>
      setError(err instanceof Error ? err.message : "Failed to stop worker"),
    );
  };

  return (
    <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
      <header
        className="flex h-[44px] shrink-0 items-center justify-between gap-2 px-4"
        style={{
          borderBottom: "1px solid var(--pi-canvas-border)",
        }}
      >
        <div className="flex min-w-0 flex-1 items-center gap-2 overflow-hidden whitespace-nowrap">
          <IconButton
            size="sm"
            label={t("common.back")}
            onClick={() => setSelectedWorkerId(null)}
          >
            <IconArrowLeft size={15.5} stroke={1.75} />
          </IconButton>

          {worker ? (
            <>
              <BotAvatar
                avatar={resolvedAvatar}
                name={worker.botName}
                isHome={matchedBot?.isHome}
                isRemote={worker.isRemote}
                dark={dark}
                size={20}
              />
              <span
                className="max-w-[260px] truncate text-[13.5px] font-semibold"
                style={{ color: "var(--pi-ink)" }}
              >
                {worker.title || worker.botName}
              </span>
              {worker.title && worker.title !== worker.botName && (
                <span
                  className="hidden max-w-[140px] truncate text-[12px] sm:inline"
                  style={{ color: "var(--pi-ink-tertiary)" }}
                >
                  · {worker.botName}
                </span>
              )}
              <Badge
                tone={
                  worker.status === "failed"
                    ? "danger"
                    : worker.status === "running" || worker.status === "working"
                      ? "accent"
                      : "neutral"
                }
              >
                {formatWorkerStatus(worker.status, t)}
              </Badge>
              {worker.isRemote && worker.deviceName && (
                <Badge tone="neutral">{worker.deviceName}</Badge>
              )}
            </>
          ) : (
            <span className="text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {t("super.workerDetailTitle")}
            </span>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1 whitespace-nowrap">
          <Popover
            align="end"
            className="w-[272px] p-3.5"
            trigger={<ContextMeter percent={ctxPct} usage={usage} />}
          >
            <UsageSheet
              percent={ctxPct}
              usage={usage}
              compacting={compacting}
              busy={Boolean(detail?.streaming) || !client}
              onCompact={() => {
                if (!client || !selectedWorkerId) return;
                setCompacting(true);
                setError(null);
                void client
                  .compactSuperWorker(selectedWorkerId)
                  .catch((err) =>
                    setError(err instanceof Error ? err.message : "Failed to compact"),
                  )
                  .finally(() => setCompacting(false));
              }}
            />
          </Popover>

          <IconButton
            size="sm"
            label={t("super.drawerWorkerDelete")}
            onClick={() => setConfirmDelete(true)}
          >
            <BrandIcon name="action/delete" size={14.5} />
          </IconButton>

          {!isElectron && (
            <IconButton
              size="sm"
              label={t("chat.workbenchToggle")}
              active={workbenchOpen}
              onClick={toggleWorkbench}
            >
              <IconLayoutSidebarRight size={15.5} stroke={1.65} />
            </IconButton>
          )}
        </div>
      </header>

      {error && (
        <div className="px-4 pt-2">
          <NoticeBar tone="danger" message={error} />
        </div>
      )}

      <div className="relative flex min-h-0 flex-1 flex-col">
        <div ref={scrollRef} data-scrollable className="min-h-0 flex-1 overflow-y-auto px-4 py-4">
          <div ref={contentRef} className="mx-auto w-full max-w-[760px] space-y-1">
            {!detail ? (
              <div className="py-12 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                {t("common.loading")}
              </div>
            ) : (
              <>
                {turns.map((turn, idx) => (
                  <TurnItem
                    key={turn.id}
                    turn={turn}
                    sessionId={workerArtifactSessionId}
                    isLastAssistant={idx === lastAssistantIdx}
                    compactProcess={compactProcess}
                    dark={dark}
                    canRetry={false}
                    onQuote={(q) => {
                      setPrompt((prev) => (prev ? `${prev}\n> ${q}` : `> ${q}`));
                      textareaRef.current?.focus();
                    }}
                    onRetry={() => void 0}
                  />
                ))}

                {(detail.streaming || detail.activeToolText) && (
                  <div
                    className="flex items-center gap-2 py-2 text-[12.5px]"
                    style={{ color: "var(--pi-ink-secondary)" }}
                  >
                    <PiActivityIcon size={16} dark={dark} />
                    <span>{detail.activeToolText || t("activity.thinking")}</span>
                  </div>
                )}

                {detail.worker.pendingApproval && client && selectedWorkerId && (
                  <ToolApprovalCard
                    key={detail.worker.pendingApproval.id}
                    approval={detail.worker.pendingApproval}
                    onResolve={async (approvalId, decision) => {
                      await client.resolveApproval(approvalId, decision);
                    }}
                  />
                )}

                {detail.worker.pendingQuestion && client && selectedWorkerId && (
                  <AskQuestionCard
                    key={detail.worker.pendingQuestion.requestId}
                    question={detail.worker.pendingQuestion}
                    onSubmit={async (reqId, answers) => {
                      await client.answerSuperWorkerQuestion(selectedWorkerId, reqId, answers);
                    }}
                    onDismiss={async (reqId) => {
                      await client.dismissSuperWorkerQuestion(selectedWorkerId, reqId);
                    }}
                  />
                )}
              </>
            )}
          </div>
        </div>

        {showJumpToBottom && (
          <button
            type="button"
            onClick={snapToBottom}
            aria-label={t("chat.jumpBottom")}
            title={t("chat.jumpBottom")}
            className="absolute bottom-3 left-1/2 z-20 flex h-8 w-8 -translate-x-1/2 items-center justify-center rounded-full transition-transform duration-150 hover:scale-105"
            style={{
              background: "var(--pi-surface)",
              color: "var(--pi-ink)",
              border: "0.5px solid var(--pi-surface-rim)",
              boxShadow: "var(--pi-composer-shadow)",
            }}
          >
            <IconArrowDown size={16} stroke={1.8} />
          </button>
        )}
      </div>

      <div className="shrink-0 px-4 pt-1 pb-4">
        <div
          className="pi-composer-shell mx-auto w-full max-w-[760px] rounded-[20px] px-3.5 py-2.5"
          data-streaming={isRunning ? "true" : "false"}
        >
          <textarea
            ref={textareaRef}
            rows={1}
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void handleSend();
              }
            }}
            placeholder={t("super.workerPromptPlaceholder")}
            className="pi-composer-textarea pi-field-auto max-h-[140px] min-h-[36px] w-full resize-none bg-transparent px-1 py-1 text-[14px] leading-[1.55] placeholder:opacity-55"
            style={{ color: "var(--pi-ink)" }}
          />
          <div className="mt-1.5 flex items-center justify-between gap-2 pt-0.5">
            <div className="flex min-w-0 items-center gap-1.5">
              {worker && !worker.isRemote && (
                <ModelPicker
                  providers={providers}
                  currentModel={currentModel}
                  currentThinking={currentThinking}
                  dark={dark}
                  onSelect={(modelSpec, thinking) => {
                    if (!client || !selectedWorkerId) return;
                    const slash = modelSpec.indexOf("/");
                    if (slash < 1) return;
                    setError(null);
                    void client
                      .setSuperWorkerModel(
                        selectedWorkerId,
                        modelSpec.slice(0, slash),
                        modelSpec.slice(slash + 1),
                        thinking.toUpperCase(),
                      )
                      .catch((err) =>
                        setError(err instanceof Error ? err.message : t("chat.switchFailed")),
                      );
                  }}
                  onOpenProvidersSettings={onOpenProvidersSettings}
                />
              )}
            </div>

            {isRunning && !prompt.trim() ? (
              <button
                type="button"
                onClick={handleStop}
                aria-label={t("chat.stop")}
                title={t("chat.stop")}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-[opacity,transform] duration-150 hover:opacity-90 motion-safe:active:scale-[0.94]"
                style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
              >
                <span
                  aria-hidden="true"
                  className="h-[11px] w-[11px] rounded-[2.5px]"
                  style={{ background: "currentColor" }}
                />
              </button>
            ) : (
              <button
                type="button"
                disabled={sending || !prompt.trim()}
                onClick={() => void handleSend()}
                aria-label={t("chat.send")}
                title={t("chat.send")}
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full transition-opacity disabled:opacity-35"
                style={{ background: "var(--pi-accent)", color: "var(--pi-on-accent)" }}
              >
                <BrandIcon name="navigation/arrow_up" size={15} />
              </button>
            )}
          </div>
        </div>
      </div>

      <ConfirmDialog
        open={confirmDelete}
        onOpenChange={setConfirmDelete}
        title={t("super.deleteWorkerTitle")}
        description={t("super.deleteWorkerDesc", {
          name: worker?.title || worker?.botName || "",
        })}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        onConfirm={() => {
          if (!client || !selectedWorkerId) return;
          setConfirmDelete(false);
          setError(null);
          void deleteWorker(client, selectedWorkerId).catch((err) =>
            setError(err instanceof Error ? err.message : "Failed to delete worker"),
          );
        }}
      />
    </div>
  );
}

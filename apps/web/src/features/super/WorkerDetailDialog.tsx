import { useEffect, useMemo, useState } from "react";
import { IconArrowDown } from "@tabler/icons-react";
import { formatWorkerStatus } from "../../api/formatters";
import {
  computeContextPercent,
  mapHostMessageToTurn,
  normalizeThinkingLevel,
} from "../../api/mappers";
import { extractLatestTodosFromTurns } from "../../api/todo";
import type { HostWorkerDetailDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { Badge, Button, ConfirmDialog, Dialog, NoticeBar, Popover } from "../../ui";
import { AskQuestionCard } from "../chat/AskQuestionCard";
import { ContextMeter } from "../chat/ContextMeter";
import { ModelPicker } from "../chat/ModelPicker";
import { useChatScroll } from "../chat/scrollPolicy";
import { TodoPanel } from "../chat/TodoPanel";
import { ToolApprovalCard } from "../chat/ToolApprovalCard";
import { TurnItem } from "../chat/TurnItem";
import { UsageSheet } from "../chat/UsageSheet";
import { BotAvatar, BrandIcon, PiActivityIcon } from "../shared/BrandAssets";

export function WorkerDetailDialog({
  onOpenProvidersSettings,
}: {
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);
  const bots = useBotStore((s) => s.bots);

  const selectedWorkerId = useSuperStore((s) => s.selectedWorkerId);
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);
  const stopWorker = useSuperStore((s) => s.stopWorker);
  const deleteWorker = useSuperStore((s) => s.deleteWorker);

  const [detail, setDetail] = useState<HostWorkerDetailDto | null>(null);
  const [prompt, setPrompt] = useState("");
  const [sending, setSending] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!client || !selectedWorkerId) {
      setDetail(null);
      setError(null);
      return undefined;
    }
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

  const latestTodos = useMemo(() => extractLatestTodosFromTurns(turns), [turns]);

  const lastAssistantIdx = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return i;
    }
    return -1;
  }, [turns]);

  const worker = detail?.worker;
  const matchedBot = useMemo(
    () => (worker ? bots.find((b) => b.id === worker.botId) : undefined),
    [bots, worker],
  );
  const currentModel =
    detail?.providerId && detail?.modelId ? `${detail.providerId}/${detail.modelId}` : "";
  const currentThinking = normalizeThinkingLevel(detail?.thinkingLevel);
  const compactProcess = hostSettings?.processDetail === "compact";
  const usage = detail?.usage ?? null;
  const ctxPct = computeContextPercent(usage);
  const workerArtifactSessionId = selectedWorkerId ? `worker:${selectedWorkerId}` : "";

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

  return (
    <>
      <Dialog
        open={Boolean(selectedWorkerId)}
        onOpenChange={(v) => {
          if (!v) setSelectedWorkerId(null);
        }}
        title={worker ? `${worker.title || worker.botName} · ${worker.botName}` : t("super.workerDetailTitle")}
        maxWidthClass="max-w-[740px]"
      >
        <div className="flex max-h-[72vh] flex-col gap-3">
          {worker && (
            <div
              className="flex flex-wrap items-center justify-between gap-2 pb-2"
              style={{ borderBottom: "0.5px solid var(--pi-hairline)" }}
            >
              <div className="flex flex-wrap items-center gap-2">
                <BotAvatar
                  avatar={matchedBot?.avatar}
                  name={worker.botName}
                  isHome={matchedBot?.isHome}
                  isRemote={worker.isRemote}
                  dark={dark}
                  size={20}
                />
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
                {!worker.isRemote && (
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

              <div className="flex items-center gap-1.5">
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
                {(worker.status === "running" || worker.status === "working" || detail?.streaming) && (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      if (!client || !selectedWorkerId) return;
                      setError(null);
                      void stopWorker(client, selectedWorkerId).catch((err) =>
                        setError(err instanceof Error ? err.message : "Failed to stop worker"),
                      );
                    }}
                  >
                    <BrandIcon name="action/stop" size={14} />
                    <span>{t("chat.stop")}</span>
                  </Button>
                )}
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => setConfirmDelete(true)}
                >
                  <BrandIcon name="action/delete" size={14} />
                  <span>{t("common.delete")}</span>
                </Button>
              </div>
            </div>
          )}

          {error && <NoticeBar tone="danger" message={error} />}

          <div className="relative flex min-h-[260px] flex-1 flex-col">
            <div
              ref={scrollRef}
              data-scrollable
              className="min-h-0 flex-1 overflow-y-auto rounded-[12px] p-3"
              style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
            >
              <div ref={contentRef} className="space-y-1">
                {!detail ? (
                  <div className="py-10 text-center text-[13px]" style={{ color: "var(--pi-ink-tertiary)" }}>
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
                        onQuote={(q) => setPrompt((prev) => (prev ? `${prev}\n> ${q}` : `> ${q}`))}
                        onRetry={() => void 0}
                      />
                    ))}

                    {(detail.streaming || detail.activeToolText) && (
                      <div className="flex items-center gap-2 py-2 text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
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

          <TodoPanel todos={latestTodos} sessionKey={workerArtifactSessionId} />

          <div
            className="pi-composer-shell flex items-end gap-2 rounded-[18px] px-3 py-2"
            data-streaming={detail?.streaming ? "true" : "false"}
          >
            <textarea
              rows={2}
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
              className="pi-composer-textarea max-h-[120px] min-h-[38px] flex-1 resize-none bg-transparent py-0.5 text-[13.5px] leading-[1.5] placeholder:text-[var(--pi-ink-tertiary)]"
              style={{ color: "var(--pi-ink)" }}
            />
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
          </div>
        </div>
      </Dialog>

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
    </>
  );
}

import { useEffect, useMemo, useState } from "react";
import { IconArrowUp, IconPlayerStop, IconTrash } from "@tabler/icons-react";
import { mapHostMessageToTurn, normalizeThinkingLevel } from "../../api/mappers";
import type { HostWorkerDetailDto } from "../../api/types";
import { useI18n } from "../../i18n";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useSuperStore } from "../../stores/superStore";
import { Badge, Button, ConfirmDialog, Dialog, Input } from "../../ui";
import { AskQuestionCard } from "../chat/AskQuestionCard";
import { ModelPicker } from "../chat/ModelPicker";
import { TurnItem } from "../chat/TurnItem";
import { PiActivityIcon } from "../shared/BrandAssets";

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
    return detail.messages
      .filter((m) => !m.streaming)
      .map((m) => mapHostMessageToTurn(m, locale));
  }, [detail, locale]);

  const lastAssistantIdx = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return i;
    }
    return -1;
  }, [turns]);

  const worker = detail?.worker;
  const currentModel = detail?.providerId && detail?.modelId
    ? `${detail.providerId}/${detail.modelId}`
    : "";
  const currentThinking = normalizeThinkingLevel(detail?.thinkingLevel);
  const compactProcess = hostSettings?.processDetail === "compact";

  const handleSend = async () => {
    if (!client || !selectedWorkerId || !prompt.trim() || sending) return;
    setSending(true);
    try {
      await client.sendSuperWorkerPrompt(selectedWorkerId, { text: prompt.trim() });
      setPrompt("");
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
                <Badge tone={worker.status === "failed" ? "danger" : worker.status === "running" || worker.status === "working" ? "accent" : "neutral"}>
                  {worker.status}
                </Badge>
                <ModelPicker
                  providers={providers}
                  currentModel={currentModel}
                  currentThinking={currentThinking}
                  dark={dark}
                  onSelect={(modelSpec, thinking) => {
                    if (!client || !selectedWorkerId) return;
                    const slash = modelSpec.indexOf("/");
                    if (slash < 1) return;
                    void client.setSuperWorkerModel(
                      selectedWorkerId,
                      modelSpec.slice(0, slash),
                      modelSpec.slice(slash + 1),
                      thinking.toUpperCase(),
                    );
                  }}
                  onOpenProvidersSettings={onOpenProvidersSettings}
                />
              </div>

              <div className="flex items-center gap-1.5">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={compacting || Boolean(detail?.streaming)}
                  onClick={() => {
                    if (!client || !selectedWorkerId) return;
                    setCompacting(true);
                    void client.compactSuperWorker(selectedWorkerId).finally(() => setCompacting(false));
                  }}
                >
                  {compacting ? t("usage.compacting") : t("usage.compactNow")}
                </Button>
                {(worker.status === "running" || worker.status === "working" || detail?.streaming) && (
                  <Button
                    variant="secondary"
                    size="sm"
                    onClick={() => {
                      if (client && selectedWorkerId) void stopWorker(client, selectedWorkerId);
                    }}
                  >
                    <IconPlayerStop size={14} />
                    <span>{t("chat.stop")}</span>
                  </Button>
                )}
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => setConfirmDelete(true)}
                >
                  <IconTrash size={14} />
                  <span>{t("common.delete")}</span>
                </Button>
              </div>
            </div>
          )}

          {error && (
            <div className="text-[12.5px]" style={{ color: "var(--pi-danger)" }}>
              {error}
            </div>
          )}

          <div
            data-scrollable
            className="min-h-[260px] flex-1 space-y-1 overflow-y-auto rounded-[12px] p-3"
            style={{ background: "var(--pi-paper)", border: "0.5px solid var(--pi-hairline)" }}
          >
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
                    sessionId={selectedWorkerId || ""}
                    isLastAssistant={idx === lastAssistantIdx}
                    compactProcess={compactProcess}
                    dark={dark}
                    onQuote={(q) => setPrompt((prev) => (prev ? `${prev}\n> ${q}` : `> ${q}`))}
                    onRetry={() => {
                      if (client && selectedWorkerId) {
                        void client.getSuperWorker(selectedWorkerId).then(setDetail);
                      }
                    }}
                  />
                ))}

                {(detail.streaming || detail.activeToolText) && (
                  <div className="flex items-center gap-2 py-2 text-[12.5px]" style={{ color: "var(--pi-ink-secondary)" }}>
                    <PiActivityIcon size={16} dark={dark} />
                    <span>{detail.activeToolText || t("activity.thinking")}</span>
                  </div>
                )}

                {detail.worker.pendingQuestion && client && selectedWorkerId && (
                  <AskQuestionCard
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

          <div className="flex items-center gap-2 pt-1">
            <Input
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void handleSend();
                }
              }}
              placeholder={t("super.workerPromptPlaceholder")}
              className="flex-1"
            />
            <Button
              variant="primary"
              disabled={sending || !prompt.trim()}
              onClick={() => void handleSend()}
            >
              <IconArrowUp size={14.5} stroke={2} />
              <span>{t("chat.send")}</span>
            </Button>
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
          void deleteWorker(client, selectedWorkerId);
        }}
      />
    </>
  );
}

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { IconLayoutSidebarRight } from "@tabler/icons-react";
import {
  computeContextPercent,
  mapHostMessageToTurn,
  normalizeThinkingLevel,
} from "../../api/mappers";
import { extractLatestTodosFromTurns } from "../../api/todo";
import type {
  ChatTurn,
  ComposerAttachment,
  HostWorkerDto,
  QuestionAnswerInput,
  TurnBlock,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { SUPER_SESSION_ID, useSuperStore } from "../../stores/superStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Badge, Button, ConfirmDialog, IconButton, NoticeBar, Popover } from "../../ui";
import { Composer } from "../chat/Composer";
import { ContextMeter } from "../chat/ContextMeter";
import { MessageList } from "../chat/MessageList";
import { TodoPanel } from "../chat/TodoPanel";
import { UsageSheet } from "../chat/UsageSheet";
import { BrandIcon, PiActivityIcon, PiMascot } from "../shared/BrandAssets";
import { ModeSwitcher } from "../shell/ModeSwitcher";
import { WindowControls } from "../shell/WindowControls";
import { SuperTeamDialog } from "./SuperTeamDialog";
import { WorkerDetailDialog } from "./WorkerDetailDialog";

function workerPriority(w: HostWorkerDto): number {
  if (w.status === "waiting_input" || w.status === "waiting_approval" || w.status === "waiting") {
    return 0;
  }
  if (w.status === "running" || w.status === "working") return 1;
  if (w.status === "idle") return 2;
  return 3;
}

function isWorkerActiveStatus(status: string): boolean {
  return (
    status === "running" ||
    status === "working" ||
    status === "waiting_input" ||
    status === "waiting_approval" ||
    status === "waiting" ||
    status === "queued" ||
    status === "stalled"
  );
}

const SUPER_PLAIN_GREETINGS = [
  "super.greeting1",
  "super.greeting2",
  "super.greeting3",
  "super.greeting4",
] as const;

const SUPER_NAMED_GREETINGS = [
  "super.greetingNamed1",
  "super.greetingNamed2",
  "super.greetingNamed3",
  "super.greetingNamed4",
] as const;

const SuperTodoPanel = memo(function SuperTodoPanel({ turns }: { turns: ChatTurn[] }) {
  const liveTodoBlock = useStreamStore((s): TurnBlock | null => {
    const blocks = s.activeBySession[SUPER_SESSION_ID]?.blocks;
    if (!blocks) return null;
    for (let i = blocks.length - 1; i >= 0; i--) {
      const b = blocks[i];
      if (b.kind === "custom_card" && b.card.customType === "todo_write") {
        return b;
      }
    }
    return null;
  });

  const latestTodos = useMemo(
    () => extractLatestTodosFromTurns(turns, liveTodoBlock ? [liveTodoBlock] : undefined),
    [turns, liveTodoBlock],
  );

  return <TodoPanel todos={latestTodos} sessionKey={SUPER_SESSION_ID} />;
});

export function SuperWorkspace({
  activeView,
  onChangeView,
  showWindowControls,
  onOpenProvidersSettings,
}: {
  activeView: "chat" | "super";
  onChangeView: (view: "chat" | "super") => void;
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
  const setSelectedWorkerId = useSuperStore((s) => s.setSelectedWorkerId);

  const hasLiveRun = useStreamStore((s) => Boolean(s.activeBySession[SUPER_SESSION_ID]));
  const isStreaming = useStreamStore((s) => Boolean(s.activeBySession[SUPER_SESSION_ID]?.streaming));

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const openPanel = useWorkbenchStore((s) => s.openPanel);
  const closeWorkbench = useWorkbenchStore((s) => s.close);

  const [teamDialogOpen, setTeamDialogOpen] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const [compacting, setCompacting] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [greetingIndex] = useState(() => Math.floor(Math.random() * SUPER_PLAIN_GREETINGS.length));

  const dragDepthRef = useRef(0);
  const snapToBottomRef = useRef<(() => void) | null>(null);
  const attachFilesRef = useRef<((files: FileList | File[]) => void) | null>(null);

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
        (w) =>
          w.status === "running" ||
          w.status === "working" ||
          w.status === "waiting_input" ||
          w.status === "waiting_approval" ||
          w.status === "waiting",
      ).length,
    [workers],
  );

  const activeWorkers = useMemo(
    () => workers.filter((w) => isWorkerActiveStatus(w.status)),
    [workers],
  );

  const waitingWorker = useMemo(
    () =>
      workers.find(
        (w) =>
          w.status === "waiting_input" ||
          w.status === "waiting_approval" ||
          w.status === "waiting" ||
          Boolean(w.pendingQuestion) ||
          Boolean(w.pendingApproval),
      ) ?? null,
    [workers],
  );

  const enabledTeamCount = useMemo(
    () => team.filter((b) => b.enabledInSuper).length,
    [team],
  );

  const currentModel =
    state?.providerId && state?.modelId
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
  const userNickname = hostSettings?.userNickname?.trim() || "";

  const handleQuote = useCallback(
    (quoted: string) => {
      const cur = useSuperStore.getState().composerText;
      setComposerText(cur ? `${cur}\n\n> ${quoted}` : `> ${quoted}`);
    },
    [setComposerText],
  );

  const handleRetry = useCallback(() => {
    if (client) void refreshState(client);
  }, [client, refreshState]);

  const handleAnswerQuestion = useCallback(
    async (reqId: string, answers: QuestionAnswerInput[]) => {
      if (client) await answerQuestion(client, reqId, answers);
    },
    [answerQuestion, client],
  );

  const handleDismissQuestion = useCallback(
    async (reqId: string) => {
      if (client) await dismissQuestion(client, reqId);
    },
    [client, dismissQuestion],
  );

  const handleSend = useCallback(
    async (overrideText?: string, atts?: ComposerAttachment[]) => {
      if (!client) return false;
      const text = (overrideText ?? useSuperStore.getState().composerText).trim();
      const attachments = atts ?? [];
      if (!text && attachments.length === 0) return false;
      if (attachments.length === 0 && text === "/compact") {
        setComposerText("");
        setCompacting(true);
        void compact(client).finally(() => setCompacting(false));
        return true;
      }
      const images = attachments
        .filter((a) => a.isInlineImage && a.dataBase64)
        .map((a) => ({
          data: a.dataBase64,
          mimeType: a.mimeType,
          name: a.name,
        }));
      const ok = await sendPrompt(
        client,
        text,
        busy && hostSettings?.sendWhileRunning !== "queue",
        images,
      );
      if (ok) snapToBottomRef.current?.();
      return ok;
    },
    [busy, client, compact, hostSettings?.sendWhileRunning, sendPrompt, setComposerText],
  );

  const isEmpty = turns.length === 0 && !hasLiveRun && !state?.pendingQuestion;

  const composerElement = (variant: "hero" | "docked") => (
    <Composer
      dark={dark}
      variant={variant}
      sessionId={SUPER_SESSION_ID}
      controlledValue={composerText}
      onControlledValueChange={setComposerText}
      providers={providers}
      currentModel={currentModel}
      currentThinking={currentThinking}
      showPermissions={false}
      allowFolderAttach={false}
      isStreamingOverride={busy}
      placeholderOverride={t("super.composerPlaceholder")}
      sendKey={hostSettings?.sendKey === "mod_enter" ? "mod_enter" : "enter"}
      sendWhileRunning={hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer"}
      onModelChange={(modelSpec, thinking) => {
        if (!client) return;
        const slash = modelSpec.indexOf("/");
        if (slash < 1) return;
        return setModel(
          client,
          modelSpec.slice(0, slash),
          modelSpec.slice(slash + 1),
          thinking.toUpperCase(),
        );
      }}
      onOpenProvidersSettings={onOpenProvidersSettings}
      onSend={handleSend}
      onStop={() => {
        if (client) void abort(client);
      }}
      onSnapToBottom={() => snapToBottomRef.current?.()}
      attachFilesRef={attachFilesRef}
    />
  );

  return (
    <div
      className="relative flex min-h-0 min-w-0 flex-1 flex-col"
      onDragEnter={(e) => {
        if (!Array.from(e.dataTransfer.types).includes("Files")) return;
        e.preventDefault();
        dragDepthRef.current += 1;
        setDraggingFiles(true);
      }}
      onDragOver={(e) => {
        if (!Array.from(e.dataTransfer.types).includes("Files")) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "copy";
      }}
      onDragLeave={(e) => {
        if (!draggingFiles) return;
        e.preventDefault();
        dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
        if (dragDepthRef.current === 0) setDraggingFiles(false);
      }}
      onDrop={(e) => {
        dragDepthRef.current = 0;
        setDraggingFiles(false);
        const files = e.dataTransfer.files;
        if (files && files.length > 0) {
          e.preventDefault();
          attachFilesRef.current?.(files);
        }
      }}
    >
      <header
        className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between gap-2 pl-4"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 12,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
        onDoubleClick={() => {
          if (isFrameless) void windowToggleMaximize();
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-2 whitespace-nowrap">
          <ModeSwitcher activeView={activeView} onChange={onChangeView} />
          {runningWorkersCount > 0 && (
            <Badge tone="accent">
              {t("super.workersRunning", { count: runningWorkersCount })}
            </Badge>
          )}
          <span className="hidden text-[12px] sm:inline" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("super.teamCount", { count: enabledTeamCount })}
          </span>
        </div>

        <div className="pi-no-drag flex shrink-0 items-center gap-1 whitespace-nowrap">
          <Popover
            align="end"
            className="w-[272px] p-3.5"
            trigger={<ContextMeter percent={ctxPct} usage={usage} />}
          >
            <UsageSheet
              percent={ctxPct}
              usage={usage}
              compacting={compacting}
              busy={busy}
              onCompact={() => {
                if (!client) return;
                setCompacting(true);
                void compact(client).finally(() => setCompacting(false));
              }}
              extraAction={
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={resetting || busy}
                  onClick={() => setConfirmReset(true)}
                >
                  {t("super.reset")}
                </Button>
              }
            />
          </Popover>

          <Button variant="ghost" size="sm" onClick={() => setTeamDialogOpen(true)}>
            <BrandIcon name="ai/agent" size={14.5} />
            <span className="hidden sm:inline">{t("super.teamButton")}</span>
          </Button>

          <IconButton
            size="sm"
            label={t("chat.workbenchToggle")}
            active={workbenchOpen}
            onClick={() => {
              if (workbenchOpen) {
                closeWorkbench();
              } else {
                openPanel(activePanelId || "super-team");
              }
            }}
          >
            <IconLayoutSidebarRight size={15.5} stroke={1.65} />
          </IconButton>

          {showWindowControls && <WindowControls />}
        </div>
      </header>

      {activeWorkers.length > 0 && (
        <div
          className="flex shrink-0 items-center justify-between gap-2 px-4 py-1.5"
          style={{
            background: "var(--pi-paper)",
            borderBottom: "0.5px solid var(--pi-hairline)",
          }}
        >
          <div className="flex min-w-0 flex-1 items-center gap-1.5 overflow-x-auto">
            {activeWorkers.map((w) => {
              const needsApproval =
                w.status === "waiting_input" ||
                w.status === "waiting_approval" ||
                w.status === "waiting" ||
                Boolean(w.pendingQuestion) ||
                Boolean(w.pendingApproval);
              return (
                <button
                  key={w.workerId}
                  type="button"
                  onClick={() => setSelectedWorkerId(w.workerId)}
                  className="flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                  style={{
                    background: "var(--pi-surface)",
                    border: "0.5px solid var(--pi-hairline)",
                    color: "var(--pi-ink)",
                  }}
                >
                  <span
                    className={`h-1.5 w-1.5 rounded-full ${needsApproval ? "" : "animate-pulse"}`}
                    style={{
                      background: needsApproval ? "var(--pi-warning)" : "var(--pi-accent)",
                    }}
                  />
                  <span className="max-w-[140px] truncate">{w.title || w.botName}</span>
                </button>
              );
            })}
          </div>
          <button
            type="button"
            onClick={() => openPanel("super-team")}
            className="shrink-0 rounded-full px-2.5 py-0.5 text-[11.5px] font-medium whitespace-nowrap transition-colors hover:bg-[var(--pi-hover)]"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            {t("super.activeStripCount", { count: activeWorkers.length })}
          </button>
        </div>
      )}

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
              <BrandIcon name="action/retry" size={13} />
              <span>{t("conn.retry")}</span>
            </Button>
          </div>
        </div>
      )}

      {isEmpty ? (
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-6 pb-10">
          <div className="flex w-full max-w-[680px] flex-col items-center">
            <PiMascot mood={loading ? "thinking" : "wave"} size={64} dark={dark} className="mb-3" />
            <h1
              className="text-center text-[24px] font-semibold tracking-[-0.02em]"
              style={{ color: "var(--pi-ink)" }}
            >
              {userNickname
                ? t(SUPER_NAMED_GREETINGS[greetingIndex], { name: userNickname })
                : t(SUPER_PLAIN_GREETINGS[greetingIndex])}
            </h1>
            <p
              className="mt-1.5 mb-5 text-center text-[13.5px] leading-[1.6]"
              style={{ color: "var(--pi-ink-secondary)" }}
            >
              {t("super.heroDesc")}
            </p>
            {composerElement("hero")}
            {enabledTeamCount === 0 && (
              <Button
                variant="secondary"
                size="sm"
                className="mt-4"
                onClick={() => setTeamDialogOpen(true)}
              >
                <BrandIcon name="ai/agent" size={14} />
                <span>{t("super.configureTeam")}</span>
              </Button>
            )}
          </div>
        </div>
      ) : (
        <>
          <MessageList
            sessionId={SUPER_SESSION_ID}
            turns={turns}
            pendingQuestion={state?.pendingQuestion ?? null}
            compactProcess={compactProcess}
            dark={dark}
            canRetry={false}
            onQuote={handleQuote}
            onRetry={handleRetry}
            onAnswerQuestion={handleAnswerQuestion}
            onDismissQuestion={handleDismissQuestion}
            scrollRefOut={snapToBottomRef}
          />
          <div className="shrink-0 px-4">
            {state?.activeToolText && !hasLiveRun && (
              <div
                className="mx-auto mb-2 flex w-full max-w-[740px] items-center gap-2 px-1 text-[12.5px]"
                style={{ color: "var(--pi-ink-secondary)" }}
              >
                <PiActivityIcon size={16} dark={dark} />
                <span>{state.activeToolText}</span>
              </div>
            )}

            {waitingWorker && (
              <div className="mx-auto mb-2 w-full max-w-[740px]">
                <NoticeBar
                  tone="warning"
                  message={t("super.waitingApproval", {
                    name: waitingWorker.title || waitingWorker.botName,
                  })}
                  action={
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => setSelectedWorkerId(waitingWorker.workerId)}
                    >
                      {t("super.openWorker")}
                    </Button>
                  }
                />
              </div>
            )}

            <SuperTodoPanel turns={turns} />
          </div>
          {composerElement("docked")}
        </>
      )}

      {draggingFiles && (
        <div
          className="pointer-events-none absolute inset-3 z-40 flex items-center justify-center rounded-[18px] border-2 border-dashed"
          style={{
            borderColor: "var(--pi-accent)",
            background: "var(--pi-accent-soft)",
            color: "var(--pi-accent)",
          }}
        >
          <span className="text-[14px] font-medium">{t("chat.dropFiles")}</span>
        </div>
      )}

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
    </div>
  );
}

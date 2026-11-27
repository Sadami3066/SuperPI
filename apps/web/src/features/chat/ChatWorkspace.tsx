import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { IconAlertCircle, IconLayoutSidebarRight, IconLoader2 } from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import { extractLatestTodosFromTurns } from "../../api/todo";
import type {
  ChatTurn,
  HostBotDto,
  PermissionMode,
  QuestionAnswerInput,
  ToolApprovalDecision,
  TurnBlock,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { draftComposerKey, useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, ChatLoadingSkeleton, IconButton, NoticeBar, Popover } from "../../ui";
import { BotAvatar, BrandIcon, PiMascot } from "../shared/BrandAssets";
import { Composer } from "./Composer";
import { ContextMeter } from "./ContextMeter";
import { GoalBar } from "./GoalBar";
import { JobsBar } from "./JobsBar";
import { MessageList } from "./MessageList";
import { TodoPanel } from "./TodoPanel";
import { UsageSheet } from "./UsageSheet";

type GreetingPair = {
  plain:
    | "chat.greetingMorning"
    | "chat.greetingAfternoon"
    | "chat.greetingEvening"
    | "chat.greetingNight"
    | "chat.greetingDefault";
  named:
    | "chat.greetingMorningNamed"
    | "chat.greetingAfternoonNamed"
    | "chat.greetingEveningNamed"
    | "chat.greetingNightNamed"
    | "chat.greetingDefaultNamed";
};

function resolveGreetingKeys(): GreetingPair {
  const hour = new Date().getHours();
  if (hour >= 5 && hour < 12) {
    return { plain: "chat.greetingMorning", named: "chat.greetingMorningNamed" };
  }
  if (hour >= 12 && hour < 17) {
    return { plain: "chat.greetingAfternoon", named: "chat.greetingAfternoonNamed" };
  }
  if (hour >= 17 && hour < 21) {
    return { plain: "chat.greetingEvening", named: "chat.greetingEveningNamed" };
  }
  if (hour >= 21 || hour < 4) {
    return { plain: "chat.greetingNight", named: "chat.greetingNightNamed" };
  }
  return { plain: "chat.greetingDefault", named: "chat.greetingDefaultNamed" };
}

const SessionTodoPanel = memo(function SessionTodoPanel({
  sessionId,
  turns,
}: {
  sessionId: string;
  turns: ChatTurn[];
}) {
  const liveTodoBlock = useStreamStore((s): TurnBlock | null => {
    const blocks = s.activeBySession[sessionId]?.blocks;
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

  return <TodoPanel todos={latestTodos} sessionKey={sessionId} />;
});

export function ChatWorkspace({
  onOpenBotSettings,
  onOpenProvidersSettings,
}: {
  onOpenBotSettings: (bot: HostBotDto) => void;
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);
  const isElectron = Boolean(desktopEnv?.isElectron);

  const bots = useBotStore((s) => s.bots);
  const commandsByBot = useBotStore((s) => s.commandsByBot);
  const remoteProvidersByBot = useBotStore((s) => s.remoteProvidersByBot);
  const loadBotCommands = useBotStore((s) => s.loadBotCommands);
  const loadRemoteBotModels = useBotStore((s) => s.loadRemoteBotModels);
  const setPermissionMode = useBotStore((s) => s.setPermissionMode);

  const activeSessionId = useSessionStore((s) => s.activeSessionId);
  const draftBotId = useSessionStore((s) => s.draftBotId);
  const draftModelByBot = useSessionStore((s) => s.draftModelByBot);
  const summariesById = useSessionStore((s) => s.summariesById);
  const summaryFromList = useSessionStore((s) => s.summariesById[s.activeSessionId]);
  const archivedMatch = useSessionStore((s) =>
    s.archivedSessions.find((x) => x.id === s.activeSessionId),
  );
  const summary = summaryFromList ?? archivedMatch;
  const transcript = useSessionStore((s) => s.transcriptsById[s.activeSessionId]);
  const pendingQuestions = useSessionStore((s) => s.pendingQuestions);
  const pendingApprovals = useSessionStore((s) => s.pendingApprovals);
  const setQuotedText = useSessionStore((s) => s.setQuotedText);
  const setComposerText = useSessionStore((s) => s.setComposerText);
  const requestComposerFocus = useSessionStore((s) => s.requestComposerFocus);
  const loadSession = useSessionStore((s) => s.loadSession);
  const selectSession = useSessionStore((s) => s.selectSession);
  const setDraftModel = useSessionStore((s) => s.setDraftModel);
  const sendMessage = useSessionStore((s) => s.sendMessage);
  const isLoadingSession = useSessionStore((s) =>
    Boolean(s.activeSessionId && s.loadingSessionIds[s.activeSessionId]),
  );
  const sessionLoadError = useSessionStore((s) =>
    s.activeSessionId ? s.sessionLoadErrors[s.activeSessionId] ?? "" : "",
  );
  const loadingEarlier = useSessionStore((s) =>
    Boolean(s.activeSessionId && s.loadingEarlierBySession[s.activeSessionId]),
  );
  const earlierError = useSessionStore((s) =>
    s.activeSessionId ? s.loadEarlierErrorBySession[s.activeSessionId] ?? "" : "",
  );
  const loadEarlierMessages = useSessionStore((s) => s.loadEarlierMessages);
  const stopSession = useSessionStore((s) => s.stopSession);
  const retrySession = useSessionStore((s) => s.retrySession);
  const compactSession = useSessionStore((s) => s.compactSession);
  const forkSession = useSessionStore((s) => s.forkSession);
  const rewindSessionTurn = useSessionStore((s) => s.rewindSessionTurn);
  const updateSessionModel = useSessionStore((s) => s.updateSessionModel);
  const removeQueuedInput = useSessionStore((s) => s.removeQueuedInput);
  const promoteQueuedInput = useSessionStore((s) => s.promoteQueuedInput);
  const answerQuestion = useSessionStore((s) => s.answerQuestion);
  const dismissQuestion = useSessionStore((s) => s.dismissQuestion);
  const resolveToolApproval = useSessionStore((s) => s.resolveToolApproval);
  const clearSlashStatus = useSessionStore((s) => s.clearSlashStatus);
  const renameSession = useSessionStore((s) => s.renameSession);

  const hasLiveRun = useStreamStore((s) =>
    Boolean(activeSessionId && s.activeBySession[activeSessionId]),
  );
  const isStreaming = useStreamStore((s) =>
    Boolean(activeSessionId && s.activeBySession[activeSessionId]?.streaming),
  );

  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const toggleWorkbench = useWorkbenchStore((s) => s.toggleWorkbench);

  const [compacting, setCompacting] = useState(false);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");

  const dragDepthRef = useRef(0);
  const rewindingRef = useRef(false);
  const snapToBottomRef = useRef<(() => void) | null>(null);
  const attachFilesRef = useRef<((files: FileList | File[]) => void) | null>(null);

  useEffect(() => {
    dragDepthRef.current = 0;
    rewindingRef.current = false;
    setDraggingFiles(false);
  }, [activeSessionId, draftBotId]);

  const activeBot = useMemo(() => {
    if (summary) {
      return bots.find((b) => b.id === summary.botId) ?? bots[0];
    }
    if (draftBotId) {
      return bots.find((b) => b.id === draftBotId) ?? bots[0];
    }
    return bots[0];
  }, [bots, draftBotId, summary]);

  const composerKey = activeSessionId || (activeBot ? draftComposerKey(activeBot.id) : "");
  const slashStatus = useSessionStore((s) =>
    composerKey ? s.slashStatusBySession[composerKey] : undefined,
  );

  useEffect(() => {
    if (!client || !activeBot) return;
    void loadBotCommands(client, activeBot.id);
    if (activeBot.isRemote && activeBot.online) {
      void loadRemoteBotModels(client, activeBot.id, activeSessionId || undefined);
    }
  }, [
    activeBot?.id,
    activeBot?.isRemote,
    activeBot?.online,
    activeSessionId,
    client,
    loadBotCommands,
    loadRemoteBotModels,
  ]);

  const effectiveProviders = useMemo(() => {
    if (activeBot?.isRemote && remoteProvidersByBot[activeBot.id]) {
      return remoteProvidersByBot[activeBot.id];
    }
    return providers;
  }, [activeBot, providers, remoteProvidersByBot]);

  const draftModel = !activeSessionId && activeBot ? draftModelByBot[activeBot.id] : undefined;

  const currentModel =
    draftModel?.model ||
    transcript?.model ||
    activeBot?.defaultModel ||
    (hostSettings ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}` : "");

  const currentThinking =
    draftModel?.thinking ??
    transcript?.thinking ??
    (activeBot?.defaultThinkingLevel
      ? normalizeThinkingLevel(activeBot.defaultThinkingLevel)
      : normalizeThinkingLevel(hostSettings?.defaultThinkingLevel));

  const activeQuestion = useMemo(() => {
    if (!activeSessionId) return null;
    return (
      pendingQuestions.find((q) => q.sessionId === activeSessionId) ??
      transcript?.pendingQuestion ??
      null
    );
  }, [activeSessionId, pendingQuestions, transcript?.pendingQuestion]);

  const activeApprovals = useMemo(() => {
    if (!activeSessionId) return [];
    const live = pendingApprovals.filter((a) => a.sessionId === activeSessionId);
    if (live.length > 0) return live;
    return transcript?.pendingApprovals ?? [];
  }, [activeSessionId, pendingApprovals, transcript?.pendingApprovals]);

  const foreignPendingSessionId = useMemo(
    () =>
      pendingQuestions.find((q) => q.sessionId !== activeSessionId)?.sessionId ??
      pendingApprovals.find((a) => !a.workerId && a.sessionId !== activeSessionId)?.sessionId ??
      null,
    [activeSessionId, pendingApprovals, pendingQuestions],
  );

  const greetingKeys = useMemo(() => resolveGreetingKeys(), [activeSessionId, draftBotId]);

  const handleQuote = useCallback(
    (txt: string) => {
      if (composerKey) setQuotedText(composerKey, txt);
    },
    [composerKey, setQuotedText],
  );

  const handleRetry = useCallback(() => {
    if (client && activeSessionId) void retrySession(client, activeSessionId);
  }, [activeSessionId, client, retrySession]);

  const handleForkFromTurn = useCallback(
    (upToUserTurn: number) => {
      if (client && activeSessionId) {
        void forkSession(client, activeSessionId, locale, upToUserTurn);
      }
    },
    [activeSessionId, client, forkSession, locale],
  );

  const handleRequestRewind = useCallback(
    (turnIndex: number, rawText: string) => {
      if (!client || !activeSessionId || rewindingRef.current) return;
      rewindingRef.current = true;
      void rewindSessionTurn(client, activeSessionId, {
        turnIndex,
        mode: "files_and_chat",
        restoreText: rawText,
        locale,
      }).finally(() => {
        rewindingRef.current = false;
      });
    },
    [activeSessionId, client, locale, rewindSessionTurn],
  );

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

  const handleResolveApproval = useCallback(
    async (approvalId: string, decision: ToolApprovalDecision) => {
      if (client) await resolveToolApproval(client, approvalId, decision);
    },
    [client, resolveToolApproval],
  );

  const turns = useMemo(() => transcript?.turns ?? [], [transcript?.turns]);

  const workspaceShortName = useMemo(() => {
    const raw = activeBot?.cwd?.trim();
    if (!raw) return "";
    const clean = raw.replace(/[\\/]+$/, "");
    const parts = clean.split(/[\\/]/);
    return parts[parts.length - 1] || clean;
  }, [activeBot?.cwd]);

  const starterChips = useMemo(
    () => [
      { label: t("chat.starterExploreLabel"), prompt: t("chat.starterExplorePrompt") },
      { label: t("chat.starterWriteLabel"), prompt: t("chat.starterWritePrompt") },
      { label: t("chat.starterReviewLabel"), prompt: t("chat.starterReviewPrompt") },
    ],
    [t],
  );

  if (!activeBot) {
    return null;
  }

  const remoteOffline = Boolean(activeBot.isRemote && !activeBot.online);
  const ctxPct = transcript?.contextPercent ?? 0;
  const usage = transcript?.usage;
  const userNickname = hostSettings?.userNickname?.trim() || "";
  const isHeroEmpty =
    !activeSessionId ||
    (!isLoadingSession &&
      !sessionLoadError &&
      turns.length === 0 &&
      !hasLiveRun &&
      !activeQuestion &&
      activeApprovals.length === 0);

  const slashStatusBar = slashStatus ? (
    <div className="my-2 w-full">
      <div
        className="flex items-center justify-between gap-2 rounded-[12px] px-3.5 py-2 text-[12.5px]"
        style={{
          background: "var(--pi-paper)",
          border: `0.5px solid ${slashStatus.phase === "error" ? "var(--pi-danger)" : "var(--pi-hairline)"}`,
          color: slashStatus.phase === "error" ? "var(--pi-danger)" : "var(--pi-ink-secondary)",
        }}
      >
        <div className="flex min-w-0 items-center gap-2">
          {slashStatus.phase === "running" ? (
            <IconLoader2 size={14} className="shrink-0 animate-spin" style={{ color: "var(--pi-accent)" }} />
          ) : slashStatus.phase === "error" ? (
            <IconAlertCircle size={14} className="shrink-0" style={{ color: "var(--pi-danger)" }} />
          ) : (
            <BrandIcon name="status/confirm" size={14} className="shrink-0" style={{ color: "var(--pi-success)" }} />
          )}
          <span className="truncate">{slashStatus.message}</span>
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {slashStatus.phase === "error" && slashStatus.retryText && client && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                void sendMessage(client, {
                  sessionId: activeSessionId,
                  botId: activeBot.id,
                  locale,
                  sendWhileRunning: hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer",
                  overrideText: slashStatus.retryText,
                });
              }}
            >
              {t("chat.retry")}
            </Button>
          )}
          {slashStatus.phase !== "running" && (
            <button
              type="button"
              onClick={() => clearSlashStatus(composerKey)}
              aria-label={t("common.close")}
              className="flex h-5 w-5 items-center justify-center rounded-full hover:bg-[var(--pi-hover)]"
            >
              <BrandIcon name="navigation/close" size={12} />
            </button>
          )}
        </div>
      </div>
    </div>
  ) : null;

  const composerElement = (variant: "hero" | "docked") => (
    <Composer
      dark={dark}
      variant={variant}
      sessionId={composerKey}
      queuedInputs={transcript?.queuedInputs ?? []}
      providers={effectiveProviders}
      slashCommands={commandsByBot[activeBot.id] ?? []}
      currentModel={currentModel}
      currentThinking={currentThinking}
      permissionMode={(activeBot.permissionMode as PermissionMode) || "workspace_auto"}
      isRemote={Boolean(activeBot.isRemote)}
      remoteOffline={remoteOffline}
      isBusy={Boolean(summary?.busy)}
      sendKey={hostSettings?.sendKey === "mod_enter" ? "mod_enter" : "enter"}
      sendWhileRunning={hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer"}
      onChangePermissionMode={async (mode) => {
        if (client) await setPermissionMode(client, activeBot.id, mode);
      }}
      onModelChange={async (model, thinking) => {
        if (!activeSessionId) {
          setDraftModel(activeBot.id, model, thinking);
          return;
        }
        if (client) await updateSessionModel(client, activeSessionId, model, thinking, locale);
      }}
      onOpenProvidersSettings={onOpenProvidersSettings}
      onSend={(overrideText, atts) => {
        if (!client) return false;
        return sendMessage(client, {
          sessionId: activeSessionId,
          botId: activeBot.id,
          locale,
          sendWhileRunning: hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer",
          overrideText,
          attachments: atts,
        });
      }}
      onStop={() => {
        if (client && activeSessionId) void stopSession(client, activeSessionId);
      }}
      onRemoveQueued={async (kind, text) => {
        if (client && activeSessionId) await removeQueuedInput(client, activeSessionId, kind, text);
      }}
      onPromoteQueued={async (text) => {
        if (client && activeSessionId) await promoteQueuedInput(client, activeSessionId, text);
      }}
      onSnapToBottom={() => snapToBottomRef.current?.()}
      attachFilesRef={attachFilesRef}
    />
  );

  return (
    <div
      className="relative flex min-h-0 min-w-0 flex-1 flex-col"
      onDragEnter={(e) => {
        if (remoteOffline || !Array.from(e.dataTransfer.types).includes("Files")) return;
        e.preventDefault();
        dragDepthRef.current += 1;
        setDraggingFiles(true);
      }}
      onDragOver={(e) => {
        if (remoteOffline || !Array.from(e.dataTransfer.types).includes("Files")) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "copy";
      }}
      onDragLeave={(e) => {
        if (!draggingFiles) return;
        e.preventDefault();
        dragDepthRef.current = Math.max(0, dragDepthRef.current - 1);
        if (dragDepthRef.current === 0) {
          setDraggingFiles(false);
        }
      }}
      onDrop={(e) => {
        dragDepthRef.current = 0;
        setDraggingFiles(false);
        if (remoteOffline) return;
        const files = e.dataTransfer.files;
        if (files && files.length > 0) {
          e.preventDefault();
          attachFilesRef.current?.(files);
        }
      }}
    >
      <header
        className="flex h-[44px] shrink-0 items-center justify-between gap-2 px-4"
        style={{
          borderBottom: "1px solid var(--pi-canvas-border)",
        }}
      >
        <div className="flex min-w-0 flex-1 items-center gap-2 overflow-hidden">
          <button
            type="button"
            onClick={() => onOpenBotSettings(activeBot)}
            title={t("nav.botSettings")}
            className="flex h-7 min-w-0 items-center gap-1.5 rounded-full px-2 whitespace-nowrap transition-colors hover:bg-[var(--pi-hover)]"
          >
            <BotAvatar
              avatar={activeBot.avatar}
              name={activeBot.name}
              isHome={activeBot.isHome}
              isRemote={activeBot.isRemote}
              dark={dark}
              size={18}
            />
            <span className="max-w-[140px] truncate text-[13px] font-semibold" style={{ color: "var(--pi-ink)" }}>
              {activeBot.name}
            </span>
          </button>

          {editingTitle && summary ? (
            <input
              autoFocus
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              onBlur={() => {
                if (client && titleDraft.trim() && titleDraft.trim() !== summary.title) {
                  void renameSession(client, summary.id, titleDraft);
                }
                setEditingTitle(false);
              }}
              onKeyDown={(e) => {
                if (e.nativeEvent.isComposing || e.keyCode === 229) return;
                if (e.key === "Enter") {
                  if (client && titleDraft.trim() && titleDraft.trim() !== summary.title) {
                    void renameSession(client, summary.id, titleDraft);
                  }
                  setEditingTitle(false);
                } else if (e.key === "Escape") {
                  setEditingTitle(false);
                }
              }}
              className="h-6 min-w-[140px] max-w-[240px] rounded-[6px] px-2 text-[12.5px] outline-none"
              style={{
                background: "var(--pi-paper)",
                border: "0.5px solid var(--pi-accent)",
                color: "var(--pi-ink)",
              }}
            />
          ) : (
            summary?.title && (
              <button
                type="button"
                onClick={() => {
                  setTitleDraft(summary.title);
                  setEditingTitle(true);
                }}
                title={t("nav.rename")}
                className="hidden max-w-[220px] truncate rounded-[6px] px-1.5 py-0.5 text-[12.5px] whitespace-nowrap transition-colors hover:bg-[var(--pi-hover)] sm:inline-block"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                / {summary.title}
              </button>
            )
          )}
          {summary?.worktreeBranch && (
            <span
              className="hidden items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium whitespace-nowrap md:inline-flex"
              style={{ background: "var(--pi-accent-soft)", color: "var(--pi-accent)" }}
            >
              <BrandIcon name="system/git_branch" size={11} />
              <span>{summary.worktreeBranch}</span>
            </span>
          )}
          {activeBot.isRemote && activeBot.deviceName && (
            <span
              className="hidden items-center gap-1.5 text-[11.5px] whitespace-nowrap md:inline-flex"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              <span
                className="h-1.5 w-1.5 rounded-full"
                style={{
                  background: activeBot.online ? "var(--pi-success)" : "var(--pi-ink-tertiary)",
                }}
              />
              <span>{activeBot.deviceName}</span>
            </span>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-1 whitespace-nowrap">
          {activeSessionId && (
            <Popover
              align="end"
              className="w-[272px] p-3.5"
              trigger={<ContextMeter percent={ctxPct} usage={usage} />}
            >
              <UsageSheet
                percent={ctxPct}
                usage={usage}
                compacting={compacting}
                busy={isStreaming || !client}
                onCompact={() => {
                  if (!client || !activeSessionId) return;
                  setCompacting(true);
                  void compactSession(client, activeSessionId, locale).finally(() =>
                    setCompacting(false),
                  );
                }}
              />
            </Popover>
          )}

          {!isElectron && (
            <IconButton
              size="sm"
              label={t("chat.workbenchToggle")}
              active={workbenchOpen}
              onClick={toggleWorkbench}
            >
              <IconLayoutSidebarRight size={16} stroke={1.65} />
            </IconButton>
          )}
        </div>
      </header>

      {remoteOffline && (
        <div className="px-4 pt-2">
          <NoticeBar
            tone="warning"
            message={t("chat.remoteOffline", { device: activeBot.deviceName || activeBot.name })}
          />
        </div>
      )}

      {foreignPendingSessionId && (
        <div className="px-4 pt-2">
          <NoticeBar
            tone="warning"
            message={t("chat.foreignQuestionBanner", {
              title: summariesById[foreignPendingSessionId]?.title || t("nav.untitledChat"),
            })}
            action={
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  if (client) void selectSession(client, foreignPendingSessionId, locale);
                }}
              >
                {t("chat.foreignQuestionJump")}
              </Button>
            }
          />
        </div>
      )}

      {isHeroEmpty ? (
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-6 pb-10">
          <div className="flex w-full max-w-[680px] flex-col items-center">
            <PiMascot mood="wave" size={62} dark={dark} className="mb-3" />
            <div
              className="mb-3 inline-flex max-w-full items-center gap-2 rounded-full px-3 py-1 text-[12px]"
              style={{
                background: "var(--pi-chrome)",
                border: "1px solid var(--pi-canvas-border)",
                color: "var(--pi-ink-secondary)",
              }}
            >
              <BotAvatar
                avatar={activeBot.avatar}
                name={activeBot.name}
                isHome={activeBot.isHome}
                isRemote={activeBot.isRemote}
                dark={dark}
                size={16}
              />
              <span className="truncate font-medium" style={{ color: "var(--pi-ink)" }}>
                {activeBot.name}
              </span>
              {activeBot.isRemote && activeBot.deviceName ? (
                <>
                  <span style={{ color: "var(--pi-ink-tertiary)" }}>·</span>
                  <span
                    className="h-1.5 w-1.5 shrink-0 rounded-full"
                    style={{
                      background: activeBot.online ? "var(--pi-success)" : "var(--pi-ink-tertiary)",
                    }}
                  />
                  <span className="truncate" style={{ color: "var(--pi-ink-tertiary)" }}>
                    {activeBot.deviceName}
                  </span>
                </>
              ) : (
                workspaceShortName && (
                  <>
                    <span style={{ color: "var(--pi-ink-tertiary)" }}>·</span>
                    <BrandIcon
                      name="file/folder_closed"
                      size={12.5}
                      style={{ color: "var(--pi-ink-tertiary)" }}
                    />
                    <span className="truncate" style={{ color: "var(--pi-ink-tertiary)" }}>
                      {workspaceShortName}
                    </span>
                  </>
                )
              )}
            </div>
            <h1
              className="mb-5 text-center text-[25px] font-semibold tracking-[-0.025em]"
              style={{ color: "var(--pi-ink)" }}
            >
              {userNickname
                ? t(greetingKeys.named, { name: userNickname })
                : t(greetingKeys.plain)}
            </h1>
            {slashStatusBar}
            {composerElement("hero")}
            {!remoteOffline && (
              <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
                {starterChips.map((chip) => (
                  <button
                    key={chip.label}
                    type="button"
                    onClick={() => {
                      if (!composerKey) return;
                      setComposerText(composerKey, chip.prompt);
                      requestComposerFocus();
                    }}
                    className="rounded-full px-3.5 py-1.5 text-[12px] font-medium transition-[background-color,border-color,color,transform] duration-150 hover:bg-[var(--pi-hover)] hover:text-[var(--pi-ink)] motion-safe:active:scale-[0.97]"
                    style={{
                      background: "var(--pi-chrome)",
                      border: "1px solid var(--pi-canvas-border)",
                      color: "var(--pi-ink-secondary)",
                    }}
                  >
                    {chip.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      ) : !transcript && sessionLoadError ? (
        <>
          <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-6">
            <div className="w-full max-w-[480px]">
              <NoticeBar
                tone="danger"
                message={t("chat.sessionLoadFailed", { error: sessionLoadError })}
                action={
                  client ? (
                    <Button
                      variant="primary"
                      size="sm"
                      onClick={() => void loadSession(client, activeSessionId, locale)}
                    >
                      {t("chat.retry")}
                    </Button>
                  ) : undefined
                }
              />
            </div>
          </div>
          {composerElement("docked")}
        </>
      ) : !transcript && isLoadingSession ? (
        <>
          <div className="min-h-0 flex-1 overflow-y-auto">
            <ChatLoadingSkeleton />
          </div>
          {composerElement("docked")}
        </>
      ) : (
        <>
          <MessageList
            sessionId={activeSessionId}
            turns={turns}
            pendingQuestion={activeQuestion}
            pendingApprovals={activeApprovals}
            compactProcess={hostSettings?.processDetail === "compact"}
            dark={dark}
            canBranchOrRewind={!activeBot.isRemote && Boolean(activeSessionId)}
            hasMoreEarlier={Boolean(transcript?.hasMore)}
            earlierCount={
              transcript?.nextBefore ??
              Math.max(0, (transcript?.totalMessages ?? turns.length) - turns.length)
            }
            loadingEarlier={loadingEarlier}
            earlierError={earlierError || null}
            userTurnOffset={transcript?.userTurnOffset ?? 0}
            onLoadEarlier={
              client && activeSessionId
                ? () => {
                    void loadEarlierMessages(client, activeSessionId, locale);
                  }
                : undefined
            }
            onQuote={handleQuote}
            onRetry={handleRetry}
            onForkFromTurn={handleForkFromTurn}
            onRewindTurn={handleRequestRewind}
            onAnswerQuestion={handleAnswerQuestion}
            onDismissQuestion={handleDismissQuestion}
            onResolveApproval={handleResolveApproval}
            footerSlot={slashStatusBar}
            scrollRefOut={snapToBottomRef}
          />
          <div className="shrink-0 px-4">
            <GoalBar sessionId={activeSessionId} />
            <JobsBar sessionId={activeSessionId} />
            <SessionTodoPanel sessionId={activeSessionId} turns={turns} />
          </div>
          {composerElement("docked")}
        </>
      )}

      {draggingFiles && !remoteOffline && (
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
    </div>
  );
}

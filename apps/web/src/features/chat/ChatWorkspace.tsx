import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { IconAlertCircle, IconLayoutSidebarRight, IconLoader2 } from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import { extractLatestTodosFromTurns } from "../../api/todo";
import type {
  ChatTurn,
  HostBotDto,
  HostCheckpointDto,
  PermissionMode,
  QuestionAnswerInput,
  ToolApprovalDecision,
  TurnBlock,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { draftComposerKey, useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, Dialog, IconButton, NoticeBar, Popover } from "../../ui";
import { BotAvatar, BrandIcon, PiMascot } from "../shared/BrandAssets";
import { ModeSwitcher } from "../shell/ModeSwitcher";
import { WindowControls } from "../shell/WindowControls";
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
  activeView,
  onChangeView,
  showWindowControls,
  onOpenBotSettings,
  onOpenProvidersSettings,
}: {
  activeView: "chat" | "super";
  onChangeView: (view: "chat" | "super") => void;
  showWindowControls: boolean;
  onOpenBotSettings: (bot: HostBotDto) => void;
  onOpenProvidersSettings: () => void;
}) {
  const { t } = useI18n();
  const client = useConnectionStore((s) => s.client);
  const desktopEnv = useConnectionStore((s) => s.desktopEnv);

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
  const activePanelId = useWorkbenchStore((s) => s.activePanelId);
  const openPanel = useWorkbenchStore((s) => s.openPanel);
  const closeWorkbench = useWorkbenchStore((s) => s.close);

  const [compacting, setCompacting] = useState(false);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [rewindTarget, setRewindTarget] = useState<{
    turnIndex: number;
    rawText: string;
    preview: string;
  } | null>(null);
  const [rewindCheckpoint, setRewindCheckpoint] = useState<HostCheckpointDto | null>(null);
  const [rewinding, setRewinding] = useState(false);

  const dragDepthRef = useRef(0);
  const snapToBottomRef = useRef<(() => void) | null>(null);
  const attachFilesRef = useRef<((files: FileList | File[]) => void) | null>(null);

  useEffect(() => {
    dragDepthRef.current = 0;
    setDraggingFiles(false);
    setRewindTarget(null);
    setRewindCheckpoint(null);
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
      if (!activeSessionId) return;
      const preview = rawText.replace(/\s+/g, " ").trim().slice(0, 48) || `#${turnIndex + 1}`;
      setRewindCheckpoint(null);
      setRewindTarget({ turnIndex, rawText, preview });
      if (client) {
        void client
          .listCheckpoints(activeSessionId)
          .then((list) => {
            const matched =
              list.find((c) => c.turnIndex === turnIndex) ??
              [...list].reverse().find((c) => c.turnIndex <= turnIndex) ??
              null;
            setRewindCheckpoint(matched);
          })
          .catch(() => setRewindCheckpoint(null));
      }
    },
    [activeSessionId, client],
  );

  const executeRewind = async (mode: "chat_only" | "files_only" | "files_and_chat") => {
    if (!client || !activeSessionId || !rewindTarget) return;
    setRewinding(true);
    try {
      const ok = await rewindSessionTurn(client, activeSessionId, {
        turnIndex: rewindTarget.turnIndex,
        mode,
        checkpointId: rewindCheckpoint?.id,
        restoreText: rewindTarget.rawText,
        locale,
      });
      if (ok) {
        setRewindTarget(null);
        setRewindCheckpoint(null);
      }
    } finally {
      setRewinding(false);
    }
  };

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

  if (!activeBot) {
    return null;
  }

  const isFrameless = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");
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
        className="pi-drag-region flex h-[40px] shrink-0 items-center justify-between gap-2 pl-4"
        style={{
          paddingRight: showWindowControls && isFrameless ? 0 : 12,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
        onDoubleClick={() => {
          if (isFrameless) void windowToggleMaximize();
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-2">
          <ModeSwitcher activeView={activeView} onChange={onChangeView} />

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

        <div className="pi-no-drag flex shrink-0 items-center gap-1 whitespace-nowrap">
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

          <IconButton
            size="sm"
            label={t("chat.workbenchToggle")}
            active={workbenchOpen}
            onClick={() => {
              if (workbenchOpen) closeWorkbench();
              else openPanel(activePanelId || "files");
            }}
          >
            <IconLayoutSidebarRight size={16} stroke={1.65} />
          </IconButton>

          {showWindowControls && <WindowControls />}
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
            <PiMascot mood="wave" size={64} dark={dark} className="mb-3" />
            <h1
              className="mb-5 text-center text-[24px] font-semibold tracking-[-0.02em]"
              style={{ color: "var(--pi-ink)" }}
            >
              {userNickname
                ? t(greetingKeys.named, { name: userNickname })
                : t(greetingKeys.plain)}
            </h1>
            {slashStatusBar}
            {composerElement("hero")}
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
          <div className="flex min-h-0 flex-1 items-center justify-center px-6">
            <div
              className="flex items-center gap-2 text-[13px]"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              <IconLoader2 size={15} className="animate-spin" style={{ color: "var(--pi-accent)" }} />
              <span>{t("common.loading")}</span>
            </div>
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

      <Dialog
        open={Boolean(rewindTarget)}
        onOpenChange={(v) => {
          if (!v && !rewinding) {
            setRewindTarget(null);
            setRewindCheckpoint(null);
          }
        }}
        title={t("chat.rewindTitle")}
        maxWidthClass="max-w-[440px]"
      >
        {rewindTarget && (
          <div className="space-y-4">
            <p className="text-[13px] leading-[1.6]" style={{ color: "var(--pi-ink-secondary)" }}>
              {t("chat.rewindDesc", { preview: rewindTarget.preview })}
            </p>
            <div className="flex flex-wrap justify-end gap-2">
              <Button
                variant="ghost"
                disabled={rewinding}
                onClick={() => {
                  setRewindTarget(null);
                  setRewindCheckpoint(null);
                }}
              >
                {t("common.cancel")}
              </Button>
              {rewindCheckpoint ? (
                <>
                  <Button
                    variant="ghost"
                    disabled={rewinding}
                    onClick={() => void executeRewind("chat_only")}
                  >
                    {t("chat.rewindChatOnly")}
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={rewinding}
                    onClick={() => void executeRewind("files_only")}
                  >
                    {t("chat.rewindFilesOnly")}
                  </Button>
                  <Button
                    variant="primary"
                    disabled={rewinding}
                    onClick={() => void executeRewind("files_and_chat")}
                  >
                    {t("chat.rewindFilesAndChat")}
                  </Button>
                </>
              ) : (
                <Button
                  variant="primary"
                  disabled={rewinding}
                  onClick={() => void executeRewind("chat_only")}
                >
                  {t("chat.rewindChatOnly")}
                </Button>
              )}
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}

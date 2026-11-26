import { useEffect, useMemo, useRef, useState } from "react";
import { IconLayoutSidebarRight, IconSettings } from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import type { HostBotDto, PermissionMode } from "../../api/types";
import { useI18n } from "../../i18n";
import { windowToggleMaximize } from "../../platform/bridge";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, IconButton, NoticeBar, Popover } from "../../ui";
import { BotAvatar, PiMascot } from "../shared/BrandAssets";
import { WindowControls } from "../shell/WindowControls";
import { Composer } from "./Composer";
import { ContextMeter } from "./ContextMeter";
import { MessageList } from "./MessageList";

function resolveGreetingKey():
  | "chat.greetingMorning"
  | "chat.greetingAfternoon"
  | "chat.greetingEvening"
  | "chat.greetingNight"
  | "chat.greetingDefault" {
  const hour = new Date().getHours();
  if (hour >= 5 && hour < 12) return "chat.greetingMorning";
  if (hour >= 12 && hour < 17) return "chat.greetingAfternoon";
  if (hour >= 17 && hour < 21) return "chat.greetingEvening";
  if (hour >= 21 || hour < 4) return "chat.greetingNight";
  return "chat.greetingDefault";
}

export function ChatWorkspace({
  showWindowControls,
  onOpenBotSettings,
  onOpenProvidersSettings,
}: {
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
  const summary = useSessionStore((s) => s.summariesById[s.activeSessionId]);
  const transcript = useSessionStore((s) => s.transcriptsById[s.activeSessionId]);
  const pendingQuestions = useSessionStore((s) => s.pendingQuestions);
  const composerText = useSessionStore((s) => s.composerTextBySession[s.activeSessionId] ?? "");
  const quotedText = useSessionStore((s) => s.quotedTextBySession[s.activeSessionId] ?? null);
  const planFirst = useSessionStore((s) => Boolean(s.planFirstBySession[s.activeSessionId]));
  const setComposerText = useSessionStore((s) => s.setComposerText);
  const setQuotedText = useSessionStore((s) => s.setQuotedText);
  const togglePlanFirst = useSessionStore((s) => s.togglePlanFirst);
  const sendMessage = useSessionStore((s) => s.sendMessage);
  const stopSession = useSessionStore((s) => s.stopSession);
  const retrySession = useSessionStore((s) => s.retrySession);
  const compactSession = useSessionStore((s) => s.compactSession);
  const updateSessionModel = useSessionStore((s) => s.updateSessionModel);
  const removeQueuedInput = useSessionStore((s) => s.removeQueuedInput);
  const promoteQueuedInput = useSessionStore((s) => s.promoteQueuedInput);
  const answerQuestion = useSessionStore((s) => s.answerQuestion);
  const dismissQuestion = useSessionStore((s) => s.dismissQuestion);

  const liveRun = useStreamStore((s) => s.activeBySession[activeSessionId]);
  const isStreaming = Boolean(liveRun?.streaming);

  const dark = useSettingsStore((s) => s.dark);
  const locale = useSettingsStore((s) => s.locale);
  const hostSettings = useSettingsStore((s) => s.hostSettings);
  const providers = useSettingsStore((s) => s.providers);

  const workbenchOpen = useWorkbenchStore((s) => s.open);
  const toggleWorkbench = useWorkbenchStore((s) => s.togglePanel);

  const [compacting, setCompacting] = useState(false);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const dragDepthRef = useRef(0);
  const snapToBottomRef = useRef<(() => void) | null>(null);
  const attachFilesRef = useRef<((files: FileList | File[]) => void) | null>(null);

  useEffect(() => {
    dragDepthRef.current = 0;
    setDraggingFiles(false);
  }, [activeSessionId]);

  const activeBot = useMemo(() => {
    if (summary) {
      return bots.find((b) => b.id === summary.botId) ?? bots[0];
    }
    return bots[0];
  }, [bots, summary]);

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

  const currentModel =
    transcript?.model ||
    activeBot?.defaultModel ||
    (hostSettings ? `${hostSettings.defaultProvider}/${hostSettings.defaultModel}` : "");

  const currentThinking =
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

  const greetingKey = useMemo(() => resolveGreetingKey(), [activeSessionId]);

  if (!activeBot || !activeSessionId) {
    return null;
  }

  const isFrameless = Boolean(desktopEnv?.isElectron && desktopEnv.platform !== "darwin");
  const remoteOffline = Boolean(activeBot.isRemote && !activeBot.online);
  const ctxPct = transcript?.contextPercent ?? 0;
  const usage = transcript?.usage;
  const turns = transcript?.turns ?? [];
  const isHeroEmpty = turns.length === 0 && !liveRun && !activeQuestion;

  const composerElement = (variant: "hero" | "docked") => (
    <Composer
      dark={dark}
      variant={variant}
      workspaceLabel={activeBot.name}
      workspacePath={activeBot.cwd}
      sessionId={activeSessionId}
      composerText={composerText}
      quotedText={quotedText}
      queuedInputs={transcript?.queuedInputs ?? []}
      providers={effectiveProviders}
      slashCommands={commandsByBot[activeBot.id] ?? []}
      currentModel={currentModel}
      currentThinking={currentThinking}
      permissionMode={(activeBot.permissionMode as PermissionMode) || "workspace_auto"}
      isRemote={Boolean(activeBot.isRemote)}
      remoteOffline={remoteOffline}
      isStreaming={isStreaming || Boolean(summary?.busy)}
      sendKey={hostSettings?.sendKey === "mod_enter" ? "mod_enter" : "enter"}
      sendWhileRunning={hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer"}
      planFirst={planFirst}
      onComposerChange={(val) => setComposerText(activeSessionId, val)}
      onClearQuote={() => setQuotedText(activeSessionId, null)}
      onTogglePlanFirst={() => togglePlanFirst(activeSessionId)}
      onChangePermissionMode={async (mode) => {
        if (client) await setPermissionMode(client, activeBot.id, mode);
      }}
      onModelChange={(model, thinking) => {
        if (client) void updateSessionModel(client, activeSessionId, model, thinking, locale);
      }}
      onOpenProvidersSettings={onOpenProvidersSettings}
      onSend={(overrideText, atts) => {
        if (!client) return;
        void sendMessage(client, {
          sessionId: activeSessionId,
          botId: activeBot.id,
          locale,
          sendWhileRunning: hostSettings?.sendWhileRunning === "queue" ? "queue" : "steer",
          overrideText,
          attachments: atts,
        });
      }}
      onStop={() => {
        if (client) void stopSession(client, activeSessionId);
      }}
      onRemoveQueued={async (kind, text) => {
        if (client) await removeQueuedInput(client, activeSessionId, kind, text);
      }}
      onPromoteQueued={async (text) => {
        if (client) await promoteQueuedInput(client, activeSessionId, text);
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
          <BotAvatar
            avatar={activeBot.avatar}
            name={activeBot.name}
            isHome={activeBot.isHome}
            isRemote={activeBot.isRemote}
            dark={dark}
            size={20}
          />
          <span className="truncate text-[13.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {activeBot.name}
          </span>
          {summary?.title && (
            <span
              className="hidden truncate text-[12.5px] sm:inline"
              style={{ color: "var(--pi-ink-tertiary)" }}
            >
              / {summary.title}
            </span>
          )}
          {activeBot.isRemote && activeBot.deviceName && (
            <span
              className="hidden items-center gap-1.5 text-[11.5px] md:inline-flex"
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
          <IconButton
            size="sm"
            label={t("nav.botSettings")}
            onClick={() => onOpenBotSettings(activeBot)}
          >
            <IconSettings size={14} stroke={1.65} />
          </IconButton>
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
              <div className="space-y-1.5" style={{ color: "var(--pi-ink-secondary)" }}>
                <div className="flex justify-between">
                  <span>{t("usage.context")}</span>
                  <span>
                    {usage ? `${usage.contextTokens.toLocaleString()} / ${usage.contextWindow.toLocaleString()}` : "—"}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span>{t("usage.input")}</span>
                  <span>{usage ? usage.inputTokens.toLocaleString() : "0"}</span>
                </div>
                <div className="flex justify-between">
                  <span>{t("usage.output")}</span>
                  <span>{usage ? usage.outputTokens.toLocaleString() : "0"}</span>
                </div>
                {usage && usage.cost > 0 && (
                  <div className="flex justify-between">
                    <span>{t("usage.cost")}</span>
                    <span>${usage.cost.toFixed(4)}</span>
                  </div>
                )}
              </div>
              <Button
                variant="secondary"
                size="sm"
                className="w-full"
                disabled={compacting || isStreaming || !client}
                onClick={() => {
                  if (!client) return;
                  setCompacting(true);
                  void compactSession(client, activeSessionId, locale).finally(() =>
                    setCompacting(false),
                  );
                }}
              >
                {compacting ? t("usage.compacting") : t("usage.compactNow")}
              </Button>
            </div>
          </Popover>

          <IconButton
            size="sm"
            label={t("chat.workbenchToggle")}
            active={workbenchOpen}
            onClick={() => toggleWorkbench("session-inspector")}
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

      {isHeroEmpty ? (
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center px-6 pb-10">
          <div className="flex w-full max-w-[680px] flex-col items-center">
            <PiMascot mood="wave" size={64} dark={dark} className="mb-3" />
            <h1
              className="mb-5 text-center text-[24px] font-semibold tracking-[-0.02em]"
              style={{ color: "var(--pi-ink)" }}
            >
              {t(greetingKey)}
            </h1>
            {composerElement("hero")}
          </div>
        </div>
      ) : (
        <>
          <MessageList
            sessionId={activeSessionId}
            turns={turns}
            pendingQuestion={activeQuestion}
            compactProcess={hostSettings?.processDetail === "compact"}
            dark={dark}
            onQuote={(txt) => setQuotedText(activeSessionId, txt)}
            onRetry={() => {
              if (client) void retrySession(client, activeSessionId);
            }}
            onAnswerQuestion={async (reqId, answers) => {
              if (client) await answerQuestion(client, reqId, answers);
            }}
            onDismissQuestion={async (reqId) => {
              if (client) await dismissQuestion(client, reqId);
            }}
            scrollRefOut={snapToBottomRef}
          />
          {composerElement("docked")}
        </>
      )}

      {draggingFiles && !remoteOffline && (
        <div
          className="pointer-events-none absolute inset-3 z-40 flex items-center justify-center rounded-[18px] border-2 border-dashed backdrop-blur-[2px]"
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

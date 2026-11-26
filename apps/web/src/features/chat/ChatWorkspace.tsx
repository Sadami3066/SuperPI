import { useEffect, useMemo, useRef, useState } from "react";
import {
  IconLayoutSidebarLeftExpand,
  IconLayoutSidebarRight,
  IconSettings,
} from "@tabler/icons-react";
import { normalizeThinkingLevel } from "../../api/mappers";
import type { HostBotDto, PermissionMode } from "../../api/types";
import { useI18n } from "../../i18n";
import { useBotStore } from "../../stores/botStore";
import { useConnectionStore } from "../../stores/connectionStore";
import { useSessionStore } from "../../stores/sessionStore";
import { useSettingsStore } from "../../stores/settingsStore";
import { useStreamStore } from "../../stores/streamStore";
import { useWorkbenchStore } from "../../stores/workbenchStore";
import { Button, IconButton, NoticeBar, Popover } from "../../ui";
import { BotAvatar, PiBrandMark } from "../shared/BrandAssets";
import { Composer } from "./Composer";
import { MessageList } from "./MessageList";

export function ChatWorkspace({
  sidebarOpen,
  onOpenSidebar,
  onOpenBotSettings,
  onOpenProvidersSettings,
}: {
  sidebarOpen: boolean;
  onOpenSidebar: () => void;
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

  const isStreaming = useStreamStore((s) =>
    Boolean(s.activeBySession[activeSessionId]?.streaming),
  );

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

  if (!activeBot || !activeSessionId) {
    return null;
  }

  const isMacOverlay = desktopEnv?.isElectron && desktopEnv.platform === "darwin";
  const remoteOffline = Boolean(activeBot.isRemote && !activeBot.online);
  const ctxPct = transcript?.contextPercent ?? 0;
  const usage = transcript?.usage;

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
        className="pi-drag-region flex h-[48px] shrink-0 items-center justify-between gap-3 px-4"
        style={{
          paddingLeft: !sidebarOpen && isMacOverlay ? 78 : undefined,
          borderBottom: "0.5px solid var(--pi-hairline)",
        }}
      >
        <div className="pi-no-drag flex min-w-0 items-center gap-2">
          {!sidebarOpen && (
            <IconButton label={t("nav.expandSidebar")} onClick={onOpenSidebar}>
              <IconLayoutSidebarLeftExpand size={18} stroke={1.65} />
            </IconButton>
          )}
          {activeBot.avatar ? (
            <BotAvatar
              avatar={activeBot.avatar}
              name={activeBot.name}
              isHome={activeBot.isHome}
              isRemote={activeBot.isRemote}
              dark={dark}
              size={22}
            />
          ) : (
            !sidebarOpen && <PiBrandMark size={20} dark={dark} />
          )}
          <span className="truncate text-[14.5px] font-semibold" style={{ color: "var(--pi-ink)" }}>
            {activeBot.name}
          </span>
          {activeBot.isRemote && activeBot.deviceName && (
            <span
              className="hidden items-center gap-1.5 text-[12px] sm:inline-flex"
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
            <IconSettings size={15} stroke={1.65} />
          </IconButton>
        </div>

        <div className="pi-no-drag flex items-center gap-1">
          <Popover
            align="end"
            className="w-[260px] p-3.5"
            trigger={
              <button
                type="button"
                title={t("usage.title")}
                aria-label={t("usage.title")}
                className="inline-flex h-8 items-center gap-1.5 rounded-full px-2.5 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)]"
                style={{ color: "var(--pi-ink-secondary)" }}
              >
                <span
                  className="inline-block h-2 w-2 rounded-full"
                  style={{
                    background:
                      ctxPct >= 80
                        ? "var(--pi-warning)"
                        : ctxPct > 0
                          ? "var(--pi-accent)"
                          : "var(--pi-hairline)",
                  }}
                />
                <span>{ctxPct}%</span>
              </button>
            }
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
            label={t("chat.workbenchToggle")}
            active={workbenchOpen}
            onClick={() => toggleWorkbench("session-inspector")}
          >
            <IconLayoutSidebarRight size={17} stroke={1.65} />
          </IconButton>
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

      <MessageList
        sessionId={activeSessionId}
        turns={transcript?.turns ?? []}
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

      <Composer
        dark={dark}
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

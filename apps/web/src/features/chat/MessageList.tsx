import { type ReactNode, useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { IconArrowDown, IconLoader2 } from "@tabler/icons-react";
import type {
  ChatTurn,
  HostPendingQuestionDto,
  HostPendingToolApprovalDto,
  QuestionAnswerInput,
  ToolApprovalDecision,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { useStreamStore } from "../../stores/streamStore";
import { AskQuestionCard } from "./AskQuestionCard";
import { MessageAnchorRail } from "./MessageAnchorRail";
import { useChatScroll } from "./scrollPolicy";
import { SelectionReplyBar } from "./SelectionReplyBar";
import { StreamingTurnView } from "./StreamingTurnView";
import { ToolApprovalCard } from "./ToolApprovalCard";
import { TurnItem } from "./TurnItem";

export function MessageList({
  sessionId,
  turns,
  pendingQuestion,
  pendingApprovals,
  compactProcess,
  dark,
  canRetry = true,
  canBranchOrRewind = false,
  hasMoreEarlier = false,
  earlierCount = 0,
  loadingEarlier = false,
  earlierError,
  userTurnOffset = 0,
  onLoadEarlier,
  onQuote,
  onRetry,
  onForkFromTurn,
  onRewindTurn,
  onAnswerQuestion,
  onDismissQuestion,
  onResolveApproval,
  footerSlot,
  scrollRefOut,
}: {
  sessionId: string;
  turns: ChatTurn[];
  pendingQuestion: HostPendingQuestionDto | null;
  pendingApprovals?: HostPendingToolApprovalDto[];
  compactProcess: boolean;
  dark: boolean;
  canRetry?: boolean;
  canBranchOrRewind?: boolean;
  hasMoreEarlier?: boolean;
  earlierCount?: number;
  loadingEarlier?: boolean;
  earlierError?: string | null;
  userTurnOffset?: number;
  onLoadEarlier?: () => Promise<void> | void;
  onQuote: (text: string) => void;
  onRetry: () => void;
  onForkFromTurn?: (upToUserTurn: number) => void;
  onRewindTurn?: (turnIndex: number, rawText: string) => void;
  onAnswerQuestion: (requestId: string, answers: QuestionAnswerInput[]) => Promise<void>;
  onDismissQuestion: (requestId: string) => Promise<void>;
  onResolveApproval?: (approvalId: string, decision: ToolApprovalDecision) => Promise<void>;
  footerSlot?: ReactNode;
  scrollRefOut?: React.MutableRefObject<(() => void) | null>;
}) {
  const { t } = useI18n();
  const hasLiveRun = useStreamStore((s) => Boolean(s.activeBySession[sessionId]));
  const isStreaming = useStreamStore((s) => Boolean(s.activeBySession[sessionId]?.streaming));
  const approvalTrigger = (pendingApprovals ?? []).map((a) => a.id).join(",");
  const tailTurnId = turns[turns.length - 1]?.id ?? "";
  const headTurnId = turns[0]?.id ?? "";

  const {
    scrollRef,
    contentRef,
    showJumpToBottom,
    snapToBottom,
    disengageFollowing,
    scheduleStickToBottom,
  } = useChatScroll(sessionId, `${tailTurnId}:${pendingQuestion?.requestId ?? ""}:${approvalTrigger}`, {
    isStreaming,
  });

  if (scrollRefOut) {
    scrollRefOut.current = snapToBottom;
  }

  const prependAnchorRef = useRef<{
    scrollHeight: number;
    scrollTop: number;
    prevHeadId: string;
  } | null>(null);

  const handleLoadEarlierClick = useCallback(() => {
    if (!onLoadEarlier || loadingEarlier) return;
    const el = scrollRef.current;
    disengageFollowing();
    if (el) {
      prependAnchorRef.current = {
        scrollHeight: el.scrollHeight,
        scrollTop: el.scrollTop,
        prevHeadId: headTurnId,
      };
    }
    void onLoadEarlier();
  }, [disengageFollowing, headTurnId, loadingEarlier, onLoadEarlier, scrollRef]);

  useLayoutEffect(() => {
    const anchor = prependAnchorRef.current;
    if (!anchor || headTurnId === anchor.prevHeadId) return;
    prependAnchorRef.current = null;
    const el = scrollRef.current;
    if (!el) return;
    const delta = el.scrollHeight - anchor.scrollHeight;
    if (delta > 0) {
      el.scrollTop = anchor.scrollTop + delta;
    }
  }, [headTurnId, scrollRef, turns.length]);

  const virtualizer = useVirtualizer({
    count: turns.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 136,
    overscan: 6,
    getItemKey: (index) => turns[index]?.id ?? index,
  });

  const [activeTurnId, setActiveTurnId] = useState<string | null>(null);
  const handleJumpToIndex = useCallback(
    (idx: number) => {
      setActiveTurnId(turns[idx]?.id ?? null);
      virtualizer.scrollToIndex(idx, { align: "start" });
    },
    [turns, virtualizer],
  );

  const lastAssistantId = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return turns[i].id;
    }
    return "";
  }, [turns]);

  const userTurnIndices = useMemo(() => {
    const out: Array<number | undefined> = new Array(turns.length);
    let userCount = userTurnOffset - 1;
    for (let i = 0; i < turns.length; i++) {
      const turn = turns[i];
      if (turn.summary) {
        out[i] = undefined;
      } else if (turn.role === "user") {
        userCount += 1;
        out[i] = userCount;
      } else {
        out[i] = userCount >= 0 ? userCount : undefined;
      }
    }
    return out;
  }, [turns, userTurnOffset]);

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div ref={scrollRef} className="pi-chat-gutter min-h-0 flex-1 overflow-y-auto">
        <div ref={contentRef} className="mx-auto w-full max-w-[740px] pt-4 pb-16">
          {(hasMoreEarlier || Boolean(earlierError)) && onLoadEarlier && (
            <div className="mb-3 flex flex-col items-center gap-1.5">
              {hasMoreEarlier && (
                <button
                  type="button"
                  disabled={loadingEarlier}
                  onClick={handleLoadEarlierClick}
                  className="inline-flex h-8 items-center gap-1.5 rounded-full px-3.5 text-[12px] font-medium transition-colors hover:bg-[var(--pi-hover)] disabled:opacity-60"
                  style={{
                    background: "var(--pi-paper)",
                    color: "var(--pi-ink-secondary)",
                    border: "0.5px solid var(--pi-hairline)",
                  }}
                >
                  {loadingEarlier && (
                    <IconLoader2 size={13} className="animate-spin" style={{ color: "var(--pi-accent)" }} />
                  )}
                  <span>
                    {loadingEarlier
                      ? t("common.loading")
                      : t("chat.loadEarlier", { count: Math.max(1, earlierCount) })}
                  </span>
                </button>
              )}
              {earlierError && (
                <div className="flex items-center gap-2 text-[12px]" style={{ color: "var(--pi-danger)" }}>
                  <span>{earlierError}</span>
                  <button
                    type="button"
                    onClick={handleLoadEarlierClick}
                    className="underline hover:opacity-80"
                  >
                    {t("chat.retry")}
                  </button>
                </div>
              )}
            </div>
          )}
          <div
            className="relative w-full"
            style={{ height: `${virtualizer.getTotalSize()}px` }}
          >
            {virtualizer.getVirtualItems().map((vRow) => {
              const turn = turns[vRow.index];
              if (!turn) return null;
              return (
                <div
                  key={vRow.key}
                  data-index={vRow.index}
                  ref={virtualizer.measureElement}
                  className="absolute top-0 left-0 w-full"
                  style={{ transform: `translateY(${vRow.start}px)` }}
                >
                  <TurnItem
                    turn={turn}
                    sessionId={sessionId}
                    userTurnIndex={userTurnIndices[vRow.index]}
                    isLastAssistant={turn.id === lastAssistantId && !hasLiveRun}
                    compactProcess={compactProcess}
                    dark={dark}
                    canRetry={canRetry}
                    canBranchOrRewind={canBranchOrRewind && !isStreaming}
                    onQuote={onQuote}
                    onRetry={onRetry}
                    onForkFromTurn={onForkFromTurn}
                    onRewindTurn={onRewindTurn}
                  />
                </div>
              );
            })}
          </div>

          <StreamingTurnView
            sessionId={sessionId}
            compactProcess={compactProcess}
            dark={dark}
            canRetry={canRetry}
            onRetry={onRetry}
            onLayoutTick={scheduleStickToBottom}
          />

          {pendingApprovals &&
            pendingApprovals.length > 0 &&
            onResolveApproval &&
            pendingApprovals.map((approval) => (
              <ToolApprovalCard
                key={approval.id}
                approval={approval}
                onResolve={onResolveApproval}
              />
            ))}

          {pendingQuestion && (
            <AskQuestionCard
              key={pendingQuestion.requestId}
              question={pendingQuestion}
              onSubmit={onAnswerQuestion}
              onDismiss={onDismissQuestion}
            />
          )}

          {footerSlot}
        </div>
      </div>

      <div
        className="pointer-events-none absolute top-0 right-0 left-0 z-10 h-3.5"
        style={{
          background: "linear-gradient(to bottom, var(--pi-surface), transparent)",
        }}
      />

      <div
        className="pointer-events-none absolute right-0 bottom-0 left-0 z-10 h-6"
        style={{
          background: "linear-gradient(to top, var(--pi-surface), transparent)",
        }}
      />

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

      <MessageAnchorRail
        turns={turns}
        activeTurnId={activeTurnId}
        onJumpToIndex={handleJumpToIndex}
      />

      <SelectionReplyBar containerRef={scrollRef} onQuote={onQuote} />
    </div>
  );
}

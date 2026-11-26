import { useMemo } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { IconArrowDown } from "@tabler/icons-react";
import type { ChatTurn, HostPendingQuestionDto, QuestionAnswerInput } from "../../api/types";
import { useI18n } from "../../i18n";
import { useStreamStore } from "../../stores/streamStore";
import { AskQuestionCard } from "./AskQuestionCard";
import { useChatScroll } from "./scrollPolicy";
import { StreamingTurnView } from "./StreamingTurnView";
import { TurnItem } from "./TurnItem";

export function MessageList({
  sessionId,
  turns,
  pendingQuestion,
  compactProcess,
  dark,
  onQuote,
  onRetry,
  onAnswerQuestion,
  onDismissQuestion,
  scrollRefOut,
}: {
  sessionId: string;
  turns: ChatTurn[];
  pendingQuestion: HostPendingQuestionDto | null;
  compactProcess: boolean;
  dark: boolean;
  onQuote: (text: string) => void;
  onRetry: () => void;
  onAnswerQuestion: (requestId: string, answers: QuestionAnswerInput[]) => Promise<void>;
  onDismissQuestion: (requestId: string) => Promise<void>;
  scrollRefOut?: React.MutableRefObject<(() => void) | null>;
}) {
  const { t } = useI18n();
  const hasLiveRun = useStreamStore((s) => Boolean(s.activeBySession[sessionId]));
  const isStreaming = useStreamStore((s) => Boolean(s.activeBySession[sessionId]?.streaming));

  const { scrollRef, contentRef, showJumpToBottom, snapToBottom, scheduleStickToBottom } =
    useChatScroll(sessionId, `${turns.length}:${pendingQuestion?.requestId ?? ""}`, {
      isStreaming,
    });

  if (scrollRefOut) {
    scrollRefOut.current = snapToBottom;
  }

  const virtualizer = useVirtualizer({
    count: turns.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 136,
    overscan: 6,
    getItemKey: (index) => turns[index]?.id ?? index,
  });

  const lastAssistantId = useMemo(() => {
    for (let i = turns.length - 1; i >= 0; i--) {
      if (turns[i].role === "assistant") return turns[i].id;
    }
    return "";
  }, [turns]);

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto px-6">
        <div ref={contentRef} className="mx-auto w-full max-w-[740px] pt-4 pb-16">
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
                    isLastAssistant={turn.id === lastAssistantId && !hasLiveRun}
                    compactProcess={compactProcess}
                    dark={dark}
                    onQuote={onQuote}
                    onRetry={onRetry}
                  />
                </div>
              );
            })}
          </div>

          <StreamingTurnView
            sessionId={sessionId}
            compactProcess={compactProcess}
            dark={dark}
            onRetry={onRetry}
            onLayoutTick={scheduleStickToBottom}
          />

          {pendingQuestion && (
            <AskQuestionCard
              key={pendingQuestion.requestId}
              question={pendingQuestion}
              onSubmit={onAnswerQuestion}
              onDismiss={onDismissQuestion}
            />
          )}
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
    </div>
  );
}

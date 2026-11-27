import { memo, useEffect, useMemo, useRef, useState } from "react";
import { IconAlertCircle, IconArrowBackUp, IconSparkles } from "@tabler/icons-react";
import { formatAttachmentSize } from "../../api/mappers";
import type { ChatTurn, CustomCardItem, TurnBlock } from "../../api/types";
import { useI18n } from "../../i18n";
import { Button, ContextMenu, ContextMenuItem } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";
import { ActivityDisclosure } from "./ActivityDisclosure";
import { AnsweredAskCard } from "./AskQuestionCard";
import { MarkdownView } from "./MarkdownView";
import { PresentCard } from "./PresentCard";

function SummaryCard({ summary }: { summary: NonNullable<ChatTurn["summary"]> }) {
  const { t } = useI18n();
  const [expanded, setExpanded] = useState(false);
  const isBranch = summary.kind === "branch";
  const label = isBranch ? t("chat.summaryBranch") : t("chat.summaryCompaction");

  return (
    <div className="my-2">
      <div
        className="overflow-hidden rounded-[12px]"
        style={{ background: "var(--pi-surface)", border: "0.5px solid var(--pi-hairline)" }}
      >
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="flex w-full items-center justify-between gap-2 px-3.5 py-2 text-left transition-colors hover:bg-[var(--pi-hover)]"
        >
          <div
            className="flex min-w-0 items-center gap-2 text-[12.5px] font-medium"
            style={{ color: "var(--pi-ink-secondary)" }}
          >
            {isBranch ? (
              <BrandIcon name="system/git_branch" size={14} className="shrink-0" />
            ) : (
              <IconSparkles size={14} stroke={1.7} className="shrink-0" />
            )}
            <span className="truncate">{label}</span>
            {summary.tokensBefore && summary.tokensBefore > 0 && (
              <span className="shrink-0 text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                · {t("chat.summaryTokens", { tokens: summary.tokensBefore.toLocaleString() })}
              </span>
            )}
          </div>
          <BrandIcon
            name="navigation/chevron_down"
            size={14}
            className="shrink-0 transition-transform duration-150"
            style={{
              color: "var(--pi-ink-tertiary)",
              transform: expanded ? "rotate(180deg)" : "rotate(0deg)",
            }}
          />
        </button>
        {expanded && summary.text && (
          <div
            data-scrollable
            className="max-h-[240px] overflow-y-auto px-3.5 py-2.5 text-[12.5px]"
            style={{ borderTop: "0.5px solid var(--pi-hairline)" }}
          >
            <MarkdownView content={summary.text} />
          </div>
        )}
      </div>
    </div>
  );
}

function CustomCardFallback({ card }: { card: CustomCardItem }) {
  return (
    <div
      className="my-2 rounded-[14px] p-3.5"
      style={{ background: "var(--pi-surface)", border: "0.5px solid var(--pi-hairline)" }}
    >
      {card.text ? (
        <MarkdownView content={card.text} />
      ) : (
        <pre
          data-scrollable
          className="max-h-48 overflow-auto text-[12px]"
          style={{ fontFamily: "var(--pi-font-mono)", color: "var(--pi-ink-secondary)" }}
        >
          {card.payload}
        </pre>
      )}
    </div>
  );
}

export function renderTurnBlock(
  block: TurnBlock,
  sessionId: string,
  compactProcess: boolean,
  streaming?: boolean,
  turnMeta?: {
    status?: "done" | "stopped" | "failed";
    durationMs?: number;
    dark?: boolean;
    persistPrefix?: string;
  },
) {
  if (block.kind === "activity") {
    const resolvedDuration = block.durationMs !== undefined ? block.durationMs : turnMeta?.durationMs;
    return (
      <ActivityDisclosure
        key={block.key}
        steps={block.steps}
        streaming={streaming}
        status={turnMeta?.status}
        durationMs={resolvedDuration}
        compact={compactProcess}
        dark={turnMeta?.dark}
        persistKey={turnMeta?.persistPrefix ? `${turnMeta.persistPrefix}:${block.key}` : undefined}
      />
    );
  }
  if (block.kind === "text") {
    return <MarkdownView key={block.key} content={block.text} streaming={streaming} />;
  }
  if (block.kind === "present") {
    return <PresentCard key={block.key} sessionId={sessionId} items={block.items} />;
  }
  if (block.kind === "answered_ask") {
    return <AnsweredAskCard key={block.key} entries={block.entries} />;
  }
  if (block.card.customType === "todo_write") {
    return null;
  }
  return <CustomCardFallback key={block.key} card={block.card} />;
}

export const TurnItem = memo(function TurnItem({
  turn,
  sessionId,
  userTurnIndex,
  isLastAssistant,
  compactProcess,
  dark,
  canRetry = true,
  canBranchOrRewind = false,
  onQuote,
  onRetry,
  onForkFromTurn,
  onRewindTurn,
}: {
  turn: ChatTurn;
  sessionId: string;
  userTurnIndex?: number;
  isLastAssistant: boolean;
  compactProcess: boolean;
  dark: boolean;
  canRetry?: boolean;
  canBranchOrRewind?: boolean;
  onQuote: (text: string) => void;
  onRetry: () => void;
  onForkFromTurn?: (upToUserTurn: number) => void;
  onRewindTurn?: (turnIndex: number, rawText: string) => void;
}) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const [showErrorDetails, setShowErrorDetails] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    };
  }, []);

  const copyText = useMemo(() => {
    const parts = turn.blocks
      .filter((b): b is Extract<TurnBlock, { kind: "text" }> => b.kind === "text")
      .map((b) => b.text.trim())
      .filter(Boolean);
    return parts.length > 0 ? parts.join("\n\n") : turn.rawText;
  }, [turn.blocks, turn.rawText]);

  const lastActivityIdx = useMemo(() => {
    for (let i = turn.blocks.length - 1; i >= 0; i--) {
      if (turn.blocks[i].kind === "activity") return i;
    }
    return -1;
  }, [turn.blocks]);

  if (turn.summary) {
    return <SummaryCard summary={turn.summary} />;
  }

  if (turn.role === "user") {
    return (
      <ContextMenu
        trigger={
          <div className="group flex flex-col items-end gap-1.5 py-2">
            {turn.attachments.length > 0 && (
              <div className="mb-1 flex max-w-[78%] flex-wrap justify-end gap-1.5">
                {turn.attachments.map((chip) => (
                  <div
                    key={chip.id}
                    className="inline-flex items-center gap-1.5 rounded-[10px] px-2.5 py-1.5 text-[12px]"
                    style={{
                      background: "var(--pi-surface)",
                      border: "0.5px solid var(--pi-hairline)",
                      color: "var(--pi-ink)",
                    }}
                  >
                    {chip.previewUrl ? (
                      <img
                        src={chip.previewUrl}
                        alt={chip.name}
                        className="h-5 w-5 shrink-0 rounded-[5px] object-cover"
                      />
                    ) : chip.kind === "folder" ? (
                      <BrandIcon name="file/folder_closed" size={14} style={{ color: "var(--pi-accent)" }} />
                    ) : chip.kind === "image" ? (
                      <BrandIcon name="file/image" size={14} style={{ color: "var(--pi-ink-tertiary)" }} />
                    ) : chip.kind === "text" ? (
                      <BrandIcon name="file/plain" size={14} style={{ color: "var(--pi-ink-tertiary)" }} />
                    ) : (
                      <BrandIcon name="file/attachment" size={14} style={{ color: "var(--pi-ink-tertiary)" }} />
                    )}
                    <span className="max-w-[180px] truncate font-medium">{chip.name}</span>
                    {chip.size > 0 && (
                      <span className="shrink-0 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                        {formatAttachmentSize(chip.size)}
                      </span>
                    )}
                  </div>
                ))}
              </div>
            )}

            {turn.rawText && (
              <div
                className="max-w-[78%] rounded-[18px] rounded-br-[6px] px-4 py-2.5 whitespace-pre-wrap"
                style={{
                  background: "var(--pi-wash)",
                  color: "var(--pi-ink)",
                  fontSize: "calc(14.5px * var(--pi-chat-scale))",
                  lineHeight: 1.6,
                }}
              >
                {turn.rawText}
              </div>
            )}

            {(turn.rawText || (canBranchOrRewind && onRewindTurn && userTurnIndex !== undefined)) && (
              <div
                className="flex items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100"
                style={{ color: "var(--pi-ink-tertiary)" }}
              >
                {turn.rawText && (
                  <>
                    <button
                      type="button"
                      onClick={() => {
                        void navigator.clipboard?.writeText(turn.rawText);
                        setCopied(true);
                        if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
                        copyTimerRef.current = setTimeout(() => setCopied(false), 1200);
                      }}
                      aria-label={t("chat.copy")}
                      title={t("chat.copy")}
                      className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                    >
                      {copied ? <BrandIcon name="status/confirm" size={13} /> : <BrandIcon name="action/copy" size={13} />}
                    </button>
                    <button
                      type="button"
                      onClick={() => onQuote(turn.rawText.slice(0, 140))}
                      aria-label={t("chat.quote")}
                      title={t("chat.quote")}
                      className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                    >
                      <BrandIcon name="action/quote" size={13} />
                    </button>
                  </>
                )}
                {canBranchOrRewind && onRewindTurn && userTurnIndex !== undefined && (
                  <button
                    type="button"
                    onClick={() => onRewindTurn(userTurnIndex, turn.rawText)}
                    aria-label={t("chat.rewindFromHere")}
                    title={t("chat.rewindFromHere")}
                    className="flex h-6 w-6 items-center justify-center rounded-md transition-colors hover:bg-[var(--pi-hover)]"
                  >
                    <IconArrowBackUp size={13.5} stroke={1.75} />
                  </button>
                )}
              </div>
            )}
          </div>
        }
      >
        <ContextMenuItem
          icon={<BrandIcon name="action/copy" size={14} />}
          label={t("chat.copy")}
          disabled={!turn.rawText}
          onSelect={() => {
            if (turn.rawText) void navigator.clipboard?.writeText(turn.rawText);
          }}
        />
        <ContextMenuItem
          icon={<BrandIcon name="action/quote" size={14} />}
          label={t("chat.quote")}
          disabled={!turn.rawText}
          onSelect={() => {
            if (turn.rawText) onQuote(turn.rawText.slice(0, 140));
          }}
        />
        {canBranchOrRewind && onRewindTurn && userTurnIndex !== undefined && (
          <ContextMenuItem
            icon={<IconArrowBackUp size={14} stroke={1.75} />}
            label={t("chat.rewindFromHere")}
            onSelect={() => onRewindTurn(userTurnIndex, turn.rawText)}
          />
        )}
      </ContextMenu>
    );
  }

  return (
    <ContextMenu
      trigger={
        <div className="group py-2">
      {(turn.notices.length > 0 || turn.reminder) && (
        <div className="mb-2 flex flex-wrap gap-1.5">
          {turn.notices.map((n, i) => (
            <span
              key={`${n}-${i}`}
              className="rounded-full px-3 py-1 text-[12px]"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-secondary)" }}
            >
              {n}
            </span>
          ))}
          {turn.reminder && (
            <span
              className="rounded-full px-3 py-1 text-[12px]"
              style={{ background: "var(--pi-wash)", color: "var(--pi-ink-tertiary)" }}
            >
              {turn.reminder}
            </span>
          )}
        </div>
      )}

      {turn.blocks.map((block, idx) => {
        const blockStatus =
          block.kind === "activity"
            ? idx === lastActivityIdx
              ? turn.status
              : block.steps.some((s) => s.kind === "tool" && s.tool.status === "failed")
                ? "failed"
                : "done"
            : undefined;
        return renderTurnBlock(block, sessionId, compactProcess, false, {
          status: blockStatus,
          dark,
          persistPrefix: turn.runId ? `${sessionId}:${turn.runId}:b${idx}` : undefined,
        });
      })}

      {turn.failed && (
        <div
          className="mt-2.5 rounded-[14px] px-3.5 py-2.5"
          style={{
            background: dark ? "rgba(248, 113, 113, 0.12)" : "rgba(196, 37, 29, 0.06)",
            border: dark
              ? "0.5px solid rgba(248, 113, 113, 0.25)"
              : "0.5px solid rgba(196, 37, 29, 0.16)",
          }}
        >
          <div className="flex items-start gap-2">
            <IconAlertCircle
              size={16}
              stroke={1.75}
              className="mt-0.5 shrink-0"
              style={{ color: "var(--pi-danger)" }}
            />
            <div className="min-w-0 flex-1">
              <div className="text-[13px] leading-[1.5]" style={{ color: "var(--pi-ink)" }}>
                {turn.error && turn.error.length <= 220
                  ? turn.error
                  : turn.error
                    ? `${turn.error.slice(0, 220)}…`
                    : t("chat.failedGeneric")}
              </div>
              {showErrorDetails && turn.error && turn.error.length > 220 && (
                <pre
                  data-scrollable
                  className="mt-2 max-h-[160px] overflow-auto rounded-[10px] p-2.5 text-[11.5px] leading-[1.45] whitespace-pre-wrap break-words"
                  style={{
                    background: "var(--pi-surface)",
                    color: "var(--pi-ink-secondary)",
                    fontFamily: "var(--pi-font-mono)",
                  }}
                >
                  {turn.error}
                </pre>
              )}
              <div className="mt-2 flex items-center gap-2">
                {canRetry && isLastAssistant && (
                  <Button variant="primary" size="sm" onClick={onRetry}>
                    <BrandIcon name="action/retry" size={13} />
                    <span>{t("chat.retry")}</span>
                  </Button>
                )}
                {turn.error && turn.error.length > 220 && (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setShowErrorDetails((v) => !v)}
                  >
                    {showErrorDetails ? t("chat.hideDetails") : t("chat.errorDetails")}
                  </Button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {(copyText || (canBranchOrRewind && onForkFromTurn && userTurnIndex !== undefined && userTurnIndex >= 0)) && (
        <div
          className={`mt-2 flex items-center gap-1 transition-opacity duration-150 ${
            isLastAssistant ? "opacity-90" : "opacity-0 group-hover:opacity-100"
          }`}
          style={{ color: "var(--pi-ink-tertiary)" }}
        >
          {copyText && (
            <>
              <button
                type="button"
                onClick={() => {
                  void navigator.clipboard?.writeText(copyText);
                  setCopied(true);
                  if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
                  copyTimerRef.current = setTimeout(() => setCopied(false), 1200);
                }}
                aria-label={t("chat.copy")}
                title={t("chat.copy")}
                className="flex h-7 w-7 items-center justify-center rounded-lg transition-colors hover:bg-[var(--pi-hover)]"
              >
                {copied ? <BrandIcon name="status/confirm" size={14} /> : <BrandIcon name="action/copy" size={14} />}
              </button>
              <button
                type="button"
                onClick={() => onQuote(copyText.slice(0, 140))}
                aria-label={t("chat.quote")}
                title={t("chat.quote")}
                className="flex h-7 w-7 items-center justify-center rounded-lg transition-colors hover:bg-[var(--pi-hover)]"
              >
                <BrandIcon name="action/quote" size={14} />
              </button>
            </>
          )}
          {canBranchOrRewind && onForkFromTurn && userTurnIndex !== undefined && userTurnIndex >= 0 && (
            <button
              type="button"
              onClick={() => onForkFromTurn(userTurnIndex)}
              aria-label={t("chat.forkFromHere")}
              title={t("chat.forkFromHere")}
              className="flex h-7 w-7 items-center justify-center rounded-lg transition-colors hover:bg-[var(--pi-hover)]"
            >
              <BrandIcon name="system/git_branch" size={13.5} />
            </button>
          )}
          {turn.timestamp && (
            <span className="ml-1.5 text-[11.5px]">{copied ? t("chat.copied") : turn.timestamp}</span>
          )}
        </div>
      )}
        </div>
      }
    >
      <ContextMenuItem
        icon={<BrandIcon name="action/copy" size={14} />}
        label={t("chat.copy")}
        disabled={!copyText}
        onSelect={() => {
          if (copyText) void navigator.clipboard?.writeText(copyText);
        }}
      />
      <ContextMenuItem
        icon={<BrandIcon name="action/quote" size={14} />}
        label={t("chat.quote")}
        disabled={!copyText}
        onSelect={() => {
          if (copyText) onQuote(copyText.slice(0, 140));
        }}
      />
      {canBranchOrRewind && onForkFromTurn && userTurnIndex !== undefined && userTurnIndex >= 0 && (
        <ContextMenuItem
          icon={<BrandIcon name="system/git_branch" size={14} />}
          label={t("chat.forkFromHere")}
          onSelect={() => onForkFromTurn(userTurnIndex)}
        />
      )}
    </ContextMenu>
  );
});

import { useEffect, useState } from "react";
import type {
  AnsweredQuestionEntry,
  HostPendingQuestionDto,
  QuestionAnswerInput,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { Button, Input, NoticeBar } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

function splitRecommendedLabel(raw: string): { clean: string; recommended: boolean } {
  const suffixMatch = raw.match(/^(.*?)\s*(?:\(Recommended\)|\(推荐\)|（推荐）)$/i);
  if (suffixMatch) {
    return { clean: suffixMatch[1].trim(), recommended: true };
  }
  const prefixMatch = raw.match(/^(?:\(Recommended\)|\(推荐\)|（推荐）)\s*(.*)$/i);
  if (prefixMatch) {
    return { clean: prefixMatch[1].trim(), recommended: true };
  }
  return { clean: raw, recommended: false };
}

export function AnsweredAskCard({ entries }: { entries: AnsweredQuestionEntry[] }) {
  const { t } = useI18n();
  return (
    <div
      className="my-2.5 space-y-2 pt-2.5"
      style={{ borderTop: "1px solid var(--pi-hairline)" }}
    >
      {entries.map((entry) => (
        <div key={entry.id} className="space-y-1">
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-[12.5px] font-medium" style={{ color: "var(--pi-ink-secondary)" }}>
              {entry.question}
            </span>
            <span className="shrink-0 text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
              {entry.skipped ? t("ask.skipped") : t("ask.answered")}
            </span>
          </div>
          {!entry.skipped && (
            <div className="flex flex-wrap items-center gap-2 text-[12.5px]" style={{ color: "var(--pi-ink)" }}>
              {entry.selected.map((s) => (
                <span key={s} className="inline-flex items-center gap-1 font-medium">
                  <BrandIcon name="status/confirm" size={12} style={{ color: "var(--pi-accent)" }} />
                  <span>{s}</span>
                </span>
              ))}
              {entry.custom && <span style={{ color: "var(--pi-ink-secondary)" }}>{entry.custom}</span>}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

export function AskQuestionCard({
  question,
  onSubmit,
  onDismiss,
}: {
  question: HostPendingQuestionDto;
  onSubmit: (requestId: string, answers: QuestionAnswerInput[]) => Promise<void>;
  onDismiss: (requestId: string) => Promise<void>;
}) {
  const { t } = useI18n();
  const [selectedMap, setSelectedMap] = useState<Record<string, string[]>>({});
  const [customMap, setCustomMap] = useState<Record<string, string>>({});
  const [stepIdx, setStepIdx] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const questions = question.questions;
  const total = questions.length;
  const safeIdx = Math.max(0, Math.min(total - 1, stepIdx));
  const currentQ = questions[safeIdx];

  useEffect(() => {
    setStepIdx(0);
    setSelectedMap({});
    setCustomMap({});
    setCollapsed(false);
    setError(null);
  }, [question.requestId]);

  const selectOption = (qId: string, label: string, multi: boolean) => {
    setSelectedMap((prev) => {
      const current = prev[qId] ?? [];
      if (!multi) {
        return { ...prev, [qId]: [label] };
      }
      const next = current.includes(label)
        ? current.filter((x) => x !== label)
        : [...current, label];
      return { ...prev, [qId]: next };
    });
    if (!multi && safeIdx < total - 1 && !(customMap[qId] ?? "").trim()) {
      setStepIdx((i) => Math.min(total - 1, i + 1));
    }
  };

  useEffect(() => {
    if (!currentQ || collapsed || busy) return undefined;
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const tag = target?.tagName?.toLowerCase();
      if (tag === "input" || tag === "textarea" || target?.isContentEditable) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const num = Number(e.key);
      if (!Number.isInteger(num) || num < 1 || num > 9) return;
      const opt = currentQ.options[num - 1];
      if (!opt) return;
      e.preventDefault();
      selectOption(currentQ.id, opt.label, currentQ.multiSelect);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [busy, collapsed, currentQ, customMap, safeIdx, total]);

  if (!currentQ) return null;

  const handleSubmit = async () => {
    setBusy(true);
    setError(null);
    try {
      const answers: QuestionAnswerInput[] = questions.map((q) => {
        const selected = selectedMap[q.id] ?? [];
        const custom = customMap[q.id]?.trim() || undefined;
        return {
          questionId: q.id,
          selected,
          custom,
          skipped: selected.length === 0 && !custom,
        };
      });
      await onSubmit(question.requestId, answers);
    } catch (err) {
      setError(err instanceof Error && err.message.trim() ? err.message : t("approval.submitError"));
    } finally {
      setBusy(false);
    }
  };

  const handleDismiss = async () => {
    setBusy(true);
    setError(null);
    try {
      await onDismiss(question.requestId);
    } catch (err) {
      setError(err instanceof Error && err.message.trim() ? err.message : t("approval.submitError"));
    } finally {
      setBusy(false);
    }
  };

  const chosen = selectedMap[currentQ.id] ?? [];
  const pct = total > 0 ? Math.round(((safeIdx + 1) / total) * 100) : 100;

  return (
    <div
      className="my-3 pt-3"
      style={{ borderTop: "1px solid var(--pi-hairline)" }}
    >
      <div className="flex items-center justify-between gap-2 pb-2">
        <button
          type="button"
          onClick={() => setCollapsed((v) => !v)}
          className="flex min-w-0 items-center gap-1.5 text-left transition-opacity hover:opacity-80"
          style={{ color: "var(--pi-ink-secondary)" }}
        >
          <BrandIcon
            name={collapsed ? "navigation/chevron_right" : "navigation/chevron_down"}
            size={13}
          />
          <span className="truncate text-[12px] font-medium">
            {currentQ.header || t("ask.title")}
          </span>
        </button>
        {total > 1 && (
          <span className="shrink-0 text-[11.5px] tabular-nums" style={{ color: "var(--pi-ink-tertiary)" }}>
            {t("ask.stepProgress", { current: safeIdx + 1, total })}
          </span>
        )}
      </div>

      {total > 1 && !collapsed && (
        <div className="mb-3 h-[1px] w-full overflow-hidden" style={{ background: "var(--pi-hairline)" }}>
          <div
            className="h-full transition-all duration-200"
            style={{ width: `${pct}%`, background: "var(--pi-accent)" }}
          />
        </div>
      )}

      {!collapsed && (
        <div className="space-y-3">
          <div className="text-[14px] font-semibold leading-[1.5]" style={{ color: "var(--pi-ink)" }}>
            {currentQ.question}
          </div>

          {currentQ.options.length > 0 && (
            <div className="divide-y" style={{ borderColor: "var(--pi-hairline)" }}>
              {currentQ.options.map((opt, idx) => {
                const active = chosen.includes(opt.label);
                const { clean, recommended } = splitRecommendedLabel(opt.label);
                return (
                  <button
                    key={opt.label}
                    type="button"
                    disabled={busy}
                    onClick={() => selectOption(currentQ.id, opt.label, currentQ.multiSelect)}
                    className="flex w-full items-start gap-2.5 rounded-[9px] px-2.5 py-2 text-left transition-colors hover:bg-[var(--pi-hover)]"
                    style={{
                      background: active ? "var(--pi-accent-soft)" : "transparent",
                    }}
                  >
                    <span
                      className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-[5px] text-[11px] font-medium tabular-nums"
                      style={{
                        background: active ? "var(--pi-accent)" : "var(--pi-wash)",
                        color: active ? "var(--pi-on-accent)" : "var(--pi-ink-secondary)",
                      }}
                    >
                      {active ? <BrandIcon name="status/confirm" size={11} /> : idx + 1}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-baseline gap-2">
                        <span
                          className="text-[13.5px] font-medium leading-[1.45]"
                          style={{ color: active ? "var(--pi-accent)" : "var(--pi-ink)" }}
                        >
                          {clean || opt.label}
                        </span>
                        {recommended && (
                          <span className="text-[11px]" style={{ color: "var(--pi-ink-tertiary)" }}>
                            {t("ask.recommended")}
                          </span>
                        )}
                      </div>
                      {opt.description && (
                        <div className="mt-0.5 text-[12px] leading-[1.45]" style={{ color: "var(--pi-ink-tertiary)" }}>
                          {opt.description}
                        </div>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
          )}

          <Input
            value={customMap[currentQ.id] ?? ""}
            disabled={busy}
            onChange={(e) => setCustomMap((prev) => ({ ...prev, [currentQ.id]: e.target.value }))}
            placeholder={t("ask.customPlaceholder")}
          />

          {error && <NoticeBar tone="danger" message={error} />}

          <div className="flex items-center justify-between gap-2 pt-0.5">
            <div>
              {safeIdx > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => setStepIdx((i) => Math.max(0, i - 1))}
                >
                  {t("ask.prev")}
                </Button>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() => void handleDismiss()}
              >
                {t("ask.dismiss")}
              </Button>
              {safeIdx < total - 1 ? (
                <Button
                  variant="primary"
                  size="sm"
                  disabled={busy}
                  onClick={() => setStepIdx((i) => Math.min(total - 1, i + 1))}
                >
                  {t("ask.next")}
                </Button>
              ) : (
                <Button variant="primary" size="sm" disabled={busy} onClick={() => void handleSubmit()}>
                  {t("ask.submit")}
                </Button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

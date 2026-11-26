import { useEffect, useState } from "react";
import type {
  AnsweredQuestionEntry,
  HostPendingQuestionDto,
  QuestionAnswerInput,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { Badge, Button, Card, Input } from "../../ui";
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
    <Card className="my-2.5 space-y-2 p-3.5">
      {entries.map((entry) => (
        <div key={entry.id} className="space-y-1">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[12.5px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {entry.question}
            </span>
            <Badge tone={entry.skipped ? "neutral" : "success"}>
              {entry.skipped ? t("ask.skipped") : t("ask.answered")}
            </Badge>
          </div>
          {!entry.skipped && (
            <div className="flex flex-wrap gap-1.5 text-[12px]" style={{ color: "var(--pi-ink-secondary)" }}>
              {entry.selected.map((s) => (
                <span
                  key={s}
                  className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5"
                  style={{ background: "var(--pi-accent-soft)", color: "var(--pi-accent)" }}
                >
                  <BrandIcon name="status/confirm" size={12} />
                  {s}
                </span>
              ))}
              {entry.custom && <span>{entry.custom}</span>}
            </div>
          )}
        </div>
      ))}
    </Card>
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
  const [busy, setBusy] = useState(false);

  const questions = question.questions;
  const total = questions.length;
  const safeIdx = Math.max(0, Math.min(total - 1, stepIdx));
  const currentQ = questions[safeIdx];

  useEffect(() => {
    setStepIdx(0);
    setSelectedMap({});
    setCustomMap({});
  }, [question.requestId]);

  if (!currentQ) return null;

  const toggleOption = (qId: string, label: string, multi: boolean) => {
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

  const handleSubmit = async () => {
    setBusy(true);
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
    } finally {
      setBusy(false);
    }
  };

  const chosen = selectedMap[currentQ.id] ?? [];
  const pct = total > 0 ? Math.round(((safeIdx + 1) / total) * 100) : 100;

  return (
    <Card className="my-3 space-y-3.5 p-4">
      {total > 1 && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-[11.5px]" style={{ color: "var(--pi-ink-tertiary)" }}>
            <span>{t("ask.stepProgress", { current: safeIdx + 1, total })}</span>
            {currentQ.header && (
              <span className="font-semibold uppercase tracking-wide">{currentQ.header}</span>
            )}
          </div>
          <div className="h-1 w-full overflow-hidden rounded-full" style={{ background: "var(--pi-wash)" }}>
            <div
              className="h-full rounded-full transition-all duration-200"
              style={{ width: `${pct}%`, background: "var(--pi-accent)" }}
            />
          </div>
        </div>
      )}

      <div className="space-y-2.5">
        {total <= 1 && currentQ.header && (
          <div className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: "var(--pi-ink-tertiary)" }}>
            {currentQ.header}
          </div>
        )}
        <div className="text-[14px] font-medium leading-[1.45]" style={{ color: "var(--pi-ink)" }}>
          {currentQ.question}
        </div>
        {currentQ.options.length > 0 && (
          <div className="flex flex-wrap gap-2 pt-0.5">
            {currentQ.options.map((opt) => {
              const active = chosen.includes(opt.label);
              const { clean, recommended } = splitRecommendedLabel(opt.label);
              return (
                <button
                  key={opt.label}
                  type="button"
                  disabled={busy}
                  onClick={() => toggleOption(currentQ.id, opt.label, currentQ.multiSelect)}
                  className="rounded-[10px] px-3 py-1.5 text-left text-[13px] transition-colors"
                  style={{
                    background: active ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                    color: active ? "var(--pi-accent)" : "var(--pi-ink)",
                    border: active
                      ? "0.5px solid var(--pi-accent)"
                      : "0.5px solid var(--pi-hairline)",
                  }}
                >
                  <div className="flex items-center gap-1.5 font-medium">
                    <span>{clean || opt.label}</span>
                    {recommended && <Badge tone="accent">{t("ask.recommended")}</Badge>}
                  </div>
                  {opt.description && (
                    <div className="mt-0.5 text-[11.5px] opacity-75">{opt.description}</div>
                  )}
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
      </div>

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
            onClick={() => {
              setBusy(true);
              void onDismiss(question.requestId).finally(() => setBusy(false));
            }}
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
    </Card>
  );
}

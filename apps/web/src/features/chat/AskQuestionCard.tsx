import { useState } from "react";
import type {
  AnsweredQuestionEntry,
  HostPendingQuestionDto,
  QuestionAnswerInput,
} from "../../api/types";
import { useI18n } from "../../i18n";
import { Badge, Button, Card, Input } from "../../ui";
import { BrandIcon } from "../shared/BrandAssets";

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
  const [busy, setBusy] = useState(false);

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
  };

  const handleSubmit = async () => {
    setBusy(true);
    try {
      const answers: QuestionAnswerInput[] = question.questions.map((q) => {
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

  return (
    <Card className="my-3 space-y-4 p-4">
      {question.questions.map((q) => {
        const chosen = selectedMap[q.id] ?? [];
        return (
          <div key={q.id} className="space-y-2">
            {q.header && (
              <div className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: "var(--pi-ink-tertiary)" }}>
                {q.header}
              </div>
            )}
            <div className="text-[14px] font-medium" style={{ color: "var(--pi-ink)" }}>
              {q.question}
            </div>
            {q.options.length > 0 && (
              <div className="flex flex-wrap gap-2 pt-0.5">
                {q.options.map((opt) => {
                  const active = chosen.includes(opt.label);
                  return (
                    <button
                      key={opt.label}
                      type="button"
                      disabled={busy}
                      onClick={() => toggleOption(q.id, opt.label, q.multiSelect)}
                      className="rounded-[10px] px-3 py-1.5 text-left text-[13px] transition-colors"
                      style={{
                        background: active ? "var(--pi-accent-soft)" : "var(--pi-paper)",
                        color: active ? "var(--pi-accent)" : "var(--pi-ink)",
                        border: active
                          ? "0.5px solid var(--pi-accent)"
                          : "0.5px solid var(--pi-hairline)",
                      }}
                    >
                      <div className="font-medium">{opt.label}</div>
                      {opt.description && (
                        <div className="mt-0.5 text-[11.5px] opacity-75">{opt.description}</div>
                      )}
                    </button>
                  );
                })}
              </div>
            )}
            <Input
              value={customMap[q.id] ?? ""}
              disabled={busy}
              onChange={(e) => setCustomMap((prev) => ({ ...prev, [q.id]: e.target.value }))}
              placeholder={t("ask.customPlaceholder")}
            />
          </div>
        );
      })}
      <div className="flex justify-end gap-2">
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
        <Button variant="primary" size="sm" disabled={busy} onClick={() => void handleSubmit()}>
          {t("ask.submit")}
        </Button>
      </div>
    </Card>
  );
}

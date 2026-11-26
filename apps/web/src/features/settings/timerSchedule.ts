import type { HostSaveTimerRequest, HostTimerDto } from "../../api/types";

const WEEKDAY_ZH = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const WEEKDAY_EN = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export function weekdayLabel(day: number, locale: "zh-CN" | "en"): string {
  const idx = day - 1;
  if (idx < 0 || idx > 6) return String(day);
  return locale === "en" ? WEEKDAY_EN[idx] : WEEKDAY_ZH[idx];
}

export function formatTimerSchedule(
  timer: Pick<HostTimerDto, "triggerType" | "date" | "time" | "days" | "cronExpression">,
  locale: "zh-CN" | "en",
): string {
  const time = timer.time || "09:00";
  if (timer.triggerType === "once") {
    return `${timer.date || ""} ${time}`.trim();
  }
  if (timer.triggerType === "daily") {
    return locale === "en" ? `Daily at ${time}` : `每天 ${time}`;
  }
  if (timer.triggerType === "weekly") {
    const days = [...timer.days]
      .filter((d) => d >= 1 && d <= 7)
      .sort((a, b) => a - b)
      .map((d) => weekdayLabel(d, locale))
      .join(locale === "en" ? ", " : "、");
    return `${days || (locale === "en" ? "Weekly" : "每周")} ${time}`.trim();
  }
  return timer.cronExpression || "cron";
}

export function validateTimerDraft(draft: HostSaveTimerRequest): string | null {
  if (!draft.name.trim()) return "name_required";
  if (!draft.prompt.trim()) return "prompt_required";
  if (draft.triggerType === "once") {
    if (!/^\d{4}-\d{2}-\d{2}$/.test((draft.date || "").trim())) return "invalid_date";
    if (!/^\d{2}:\d{2}(:\d{2})?$/.test((draft.time || "").trim())) return "invalid_time";
    return null;
  }
  if (draft.triggerType === "daily") {
    if (!/^\d{2}:\d{2}(:\d{2})?$/.test((draft.time || "").trim())) return "invalid_time";
    return null;
  }
  if (draft.triggerType === "weekly") {
    if (!draft.days || draft.days.length === 0) return "days_required";
    if (!/^\d{2}:\d{2}(:\d{2})?$/.test((draft.time || "").trim())) return "invalid_time";
    return null;
  }
  if (draft.triggerType === "cron") {
    const parts = (draft.cronExpression || "").trim().split(/\s+/).filter(Boolean);
    if (parts.length !== 5) return "invalid_cron";
    return null;
  }
  return "invalid_type";
}

export function parseSuperSseEventType(eventType: string): { isSuper: boolean; baseType: string } {
  if (eventType.startsWith("super_")) {
    return { isSuper: true, baseType: eventType.slice("super_".length) };
  }
  return { isSuper: false, baseType: eventType };
}

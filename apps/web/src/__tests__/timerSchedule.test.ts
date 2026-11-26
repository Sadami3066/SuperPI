import { describe, expect, it } from "vitest";
import {
  formatTimerSchedule,
  parseSuperSseEventType,
  validateTimerDraft,
} from "../features/settings/timerSchedule";

describe("timerSchedule helpers", () => {
  it("formats once, daily, weekly, and cron schedules cleanly", () => {
    expect(
      formatTimerSchedule(
        { triggerType: "once", date: "2026-12-01", time: "08:30", days: [] },
        "zh-CN",
      ),
    ).toBe("2026-12-01 08:30");

    expect(
      formatTimerSchedule({ triggerType: "daily", time: "09:00", days: [] }, "en"),
    ).toBe("Daily at 09:00");

    expect(
      formatTimerSchedule(
        { triggerType: "weekly", time: "18:00", days: [5, 1] },
        "zh-CN",
      ),
    ).toBe("周一、周五 18:00");

    expect(
      formatTimerSchedule(
        { triggerType: "cron", cronExpression: "0 9 * * 1-5", days: [] },
        "en",
      ),
    ).toBe("0 9 * * 1-5");
  });

  it("validates timer draft payloads across trigger types", () => {
    expect(
      validateTimerDraft({
        name: "Standup",
        prompt: "Summarize updates",
        triggerType: "daily",
        time: "09:30",
      }),
    ).toBeNull();

    expect(
      validateTimerDraft({
        name: "Weekly",
        prompt: "Review",
        triggerType: "weekly",
        days: [],
        time: "09:30",
      }),
    ).toBe("days_required");

    expect(
      validateTimerDraft({
        name: "Cron",
        prompt: "Check",
        triggerType: "cron",
        cronExpression: "invalid cron",
      }),
    ).toBe("invalid_cron");
  });

  it("parses super prefixed SSE event names", () => {
    expect(parseSuperSseEventType("super_text_delta")).toEqual({
      isSuper: true,
      baseType: "text_delta",
    });
    expect(parseSuperSseEventType("text_delta")).toEqual({
      isSuper: false,
      baseType: "text_delta",
    });
  });
});

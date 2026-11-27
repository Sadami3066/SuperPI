import { afterEach, describe, expect, it } from "vitest";
import {
  collectNewNotificationItems,
  isUserAbortedError,
  resetNotificationBaselines,
  seedNotificationBaselines,
} from "../platform/notifications";

describe("desktop notification dedupe and abort filters", () => {
  afterEach(() => {
    resetNotificationBaselines();
  });

  it("seeds baselines and emits only newly arrived items", () => {
    seedNotificationBaselines({
      questionIds: ["q1"],
      approvalIds: ["a1"],
      inboxIds: ["i1"],
    });

    expect(
      collectNewNotificationItems("question", [{ id: "q1" }], (x) => x.id),
    ).toEqual([]);

    expect(
      collectNewNotificationItems("question", [{ id: "q1" }, { id: "q2" }], (x) => x.id),
    ).toEqual([{ id: "q2" }]);

    // Once seen, q2 is not re-emitted on the next poll
    expect(
      collectNewNotificationItems("question", [{ id: "q1" }, { id: "q2" }], (x) => x.id),
    ).toEqual([]);

    // If q2 disappears and later returns under a new id q3, only q3 is emitted
    expect(
      collectNewNotificationItems("question", [{ id: "q1" }, { id: "q3" }], (x) => x.id),
    ).toEqual([{ id: "q3" }]);
  });

  it("identifies user-aborted error text in Chinese and English", () => {
    expect(isUserAbortedError("Aborted by user")).toBe(true);
    expect(isUserAbortedError("用户中断了当前生成")).toBe(true);
    expect(isUserAbortedError("Rate limit exceeded")).toBe(false);
    expect(isUserAbortedError("")).toBe(false);
  });
});

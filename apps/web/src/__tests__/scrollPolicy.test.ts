import { describe, expect, it } from "vitest";
import { evaluateScrollPosition } from "../features/chat/scrollPolicy";

describe("scrollPolicy", () => {
  it("disengages stick-to-bottom immediately when scrolling upward", () => {
    const res = evaluateScrollPosition({
      scrollTop: 820,
      prevScrollTop: 900,
      scrollHeight: 1500,
      clientHeight: 600,
      wasSticky: true,
    });
    expect(res.sticky).toBe(false);
    expect(res.showJumpToBottom).toBe(true);
  });

  it("re-engages stick-to-bottom when scrolling near the bottom", () => {
    const res = evaluateScrollPosition({
      scrollTop: 870,
      prevScrollTop: 820,
      scrollHeight: 1500,
      clientHeight: 600,
      wasSticky: false,
    });
    expect(res.sticky).toBe(true);
    expect(res.showJumpToBottom).toBe(false);
  });
});

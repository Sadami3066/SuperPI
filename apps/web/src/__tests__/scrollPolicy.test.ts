import { describe, expect, it } from "vitest";
import {
  evaluateScrollPosition,
  NEAR_BOTTOM_THRESHOLD_PX,
  softSpringStep,
} from "../features/chat/scrollPolicy";

describe("scrollPolicy", () => {
  it("disengages stick-to-bottom immediately when scrolling upward", () => {
    const res = evaluateScrollPosition({
      scrollTop: 720,
      prevScrollTop: 900,
      scrollHeight: 1500,
      clientHeight: 600,
      wasSticky: true,
    });
    expect(res.sticky).toBe(false);
    expect(res.showJumpToBottom).toBe(true);
  });

  it("re-engages stick-to-bottom within 80px near-bottom threshold", () => {
    const res = evaluateScrollPosition({
      scrollTop: 1500 - 600 - (NEAR_BOTTOM_THRESHOLD_PX - 10),
      prevScrollTop: 700,
      scrollHeight: 1500,
      clientHeight: 600,
      wasSticky: false,
    });
    expect(res.sticky).toBe(true);
    expect(res.showJumpToBottom).toBe(false);
  });

  it("converges smoothly using critically damped soft spring", () => {
    let remaining = 240;
    let velocity = 0;
    let steps = 0;
    while (remaining > 0 && steps < 180) {
      const step = softSpringStep(remaining, velocity, 1 / 60, true);
      expect(step.stepPx).toBeGreaterThanOrEqual(0);
      expect(step.stepPx).toBeLessThanOrEqual(remaining + 0.001);
      remaining = Math.max(0, remaining - step.stepPx);
      velocity = step.nextVelocityPxPerSec;
      steps++;
      if (step.settled) break;
    }
    expect(remaining).toBe(0);
    expect(steps).toBeGreaterThan(2);
    expect(steps).toBeLessThan(120);
  });

  it("snaps immediately when reduced-motion is enabled", () => {
    const step = softSpringStep(150, 0, 1 / 60, false);
    expect(step.settled).toBe(true);
    expect(step.stepPx).toBe(150);
  });
});

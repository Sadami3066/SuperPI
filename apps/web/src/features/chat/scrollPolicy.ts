import { useCallback, useEffect, useRef, useState } from "react";

export const NEAR_BOTTOM_THRESHOLD_PX = 80;
export const STICK_BOTTOM_THRESHOLD_PX = NEAR_BOTTOM_THRESHOLD_PX;
export const SPRING_OMEGA = 10;
export const SPRING_ZETA = 1;
const SNAP_THRESHOLD_PX = 0.5;
const SETTLE_VELOCITY_PX_PER_SEC = 8;
const MIN_DT_SEC = 0.004;
const MAX_DT_SEC = 1 / 30;
const PROGRAMMATIC_GUARD_MS = 140;

export interface SoftSpringStepResult {
  stepPx: number;
  nextVelocityPxPerSec: number;
  settled: boolean;
}

export function softSpringStep(
  overflowPx: number,
  velocityPxPerSec: number,
  deltaSeconds: number,
  smooth: boolean,
): SoftSpringStepResult {
  if (overflowPx <= 0) {
    return { stepPx: 0, nextVelocityPxPerSec: 0, settled: true };
  }
  if (!smooth) {
    return { stepPx: overflowPx, nextVelocityPxPerSec: 0, settled: true };
  }
  const h = Math.min(MAX_DT_SEC, Math.max(MIN_DT_SEC, deltaSeconds));
  const omega = SPRING_OMEGA;
  const zeta = SPRING_ZETA;
  const k = omega * omega;
  const d = 2 * zeta * omega;
  const gamma = 1 / (h * (d + h * k));
  const bias = h * k * gamma * -overflowPx;
  const impulse = -(velocityPxPerSec + bias) / (1 + gamma);
  const nextVelocity = Math.max(0, velocityPxPerSec + impulse);
  const rawStep = h * nextVelocity;
  const remaining = overflowPx - rawStep;
  if ((remaining <= SNAP_THRESHOLD_PX && nextVelocity <= SETTLE_VELOCITY_PX_PER_SEC) || rawStep >= overflowPx) {
    return { stepPx: overflowPx, nextVelocityPxPerSec: 0, settled: true };
  }
  return { stepPx: rawStep, nextVelocityPxPerSec: nextVelocity, settled: false };
}

export interface ScrollPolicyInput {
  scrollTop: number;
  prevScrollTop: number;
  scrollHeight: number;
  clientHeight: number;
  wasSticky: boolean;
}

export interface ScrollPolicyResult {
  sticky: boolean;
  showJumpToBottom: boolean;
  distanceFromBottom: number;
}

export function evaluateScrollPosition(input: ScrollPolicyInput): ScrollPolicyResult {
  const distanceFromBottom = Math.max(0, input.scrollHeight - input.clientHeight - input.scrollTop);
  const movedUp = input.scrollTop < input.prevScrollTop - 2;
  if (movedUp && distanceFromBottom > 6) {
    return {
      sticky: false,
      showJumpToBottom: true,
      distanceFromBottom,
    };
  }
  if (distanceFromBottom <= NEAR_BOTTOM_THRESHOLD_PX) {
    return {
      sticky: true,
      showJumpToBottom: false,
      distanceFromBottom,
    };
  }
  return {
    sticky: input.wasSticky && distanceFromBottom <= NEAR_BOTTOM_THRESHOLD_PX,
    showJumpToBottom: !input.wasSticky || distanceFromBottom > NEAR_BOTTOM_THRESHOLD_PX,
    distanceFromBottom,
  };
}

interface SavedAnchor {
  scrollTop: number;
  sticky: boolean;
}

export function useChatScroll(
  sessionId: string,
  contentSignal: string | number,
  options?: { isStreaming?: boolean },
) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const contentRef = useRef<HTMLDivElement | null>(null);
  const followingRef = useRef(true);
  const velocityRef = useRef(0);
  const subPixelCarryRef = useRef(0);
  const lastFrameAtRef = useRef<number | null>(null);
  const programmaticUntilRef = useRef(0);
  const lastScrollTopRef = useRef(0);
  const anchorsRef = useRef<Map<string, SavedAnchor>>(new Map());
  const prevSessionRef = useRef(sessionId);
  const prevStreamingRef = useRef(Boolean(options?.isStreaming));
  const rafRef = useRef<number | null>(null);
  const settleFrameRef = useRef<number | null>(null);

  const [showJumpToBottom, setShowJumpToBottom] = useState(false);

  const cancelSpring = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
    velocityRef.current = 0;
    subPixelCarryRef.current = 0;
    lastFrameAtRef.current = null;
  }, []);

  const kickSpring = useCallback(() => {
    if (!followingRef.current || rafRef.current !== null) return;
    const reducedMotion =
      typeof window !== "undefined" &&
      Boolean(window.matchMedia?.("(prefers-reduced-motion: reduce)").matches);

    const stepFrame = (nowMs: number) => {
      rafRef.current = null;
      const el = scrollRef.current;
      if (!el || !followingRef.current) {
        cancelSpring();
        return;
      }
      const maxTop = Math.max(0, el.scrollHeight - el.clientHeight);
      const overflow = maxTop - el.scrollTop;
      if (overflow <= 0.5) {
        if (overflow > 0) {
          programmaticUntilRef.current = performance.now() + PROGRAMMATIC_GUARD_MS;
          el.scrollTop = maxTop;
          lastScrollTopRef.current = el.scrollTop;
        }
        velocityRef.current = 0;
        subPixelCarryRef.current = 0;
        lastFrameAtRef.current = null;
        return;
      }

      const dtSec =
        lastFrameAtRef.current !== null ? (nowMs - lastFrameAtRef.current) / 1000 : 1 / 60;
      lastFrameAtRef.current = nowMs;

      const step = softSpringStep(overflow, velocityRef.current, dtSec, !reducedMotion);
      velocityRef.current = step.nextVelocityPxPerSec;

      subPixelCarryRef.current += step.stepPx;
      const movePx = Math.floor(subPixelCarryRef.current);
      if (movePx > 0 || step.settled) {
        programmaticUntilRef.current = performance.now() + PROGRAMMATIC_GUARD_MS;
        if (step.settled) {
          el.scrollTop = maxTop;
          subPixelCarryRef.current = 0;
        } else {
          subPixelCarryRef.current -= movePx;
          el.scrollTop = Math.min(maxTop, el.scrollTop + movePx);
        }
        lastScrollTopRef.current = el.scrollTop;
      }

      if (!step.settled && followingRef.current) {
        rafRef.current = requestAnimationFrame(stepFrame);
      } else {
        velocityRef.current = 0;
        subPixelCarryRef.current = 0;
        lastFrameAtRef.current = null;
      }
    };

    rafRef.current = requestAnimationFrame(stepFrame);
  }, [cancelSpring]);

  const snapToBottom = useCallback(() => {
    cancelSpring();
    const el = scrollRef.current;
    followingRef.current = true;
    setShowJumpToBottom(false);
    if (!el) return;
    programmaticUntilRef.current = performance.now() + PROGRAMMATIC_GUARD_MS;
    el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight);
    lastScrollTopRef.current = el.scrollTop;
  }, [cancelSpring]);

  const scheduleStickToBottom = useCallback(() => {
    if (!followingRef.current) return;
    kickSpring();
  }, [kickSpring]);

  useEffect(() => {
    const prev = prevSessionRef.current;
    if (prev && prev !== sessionId) {
      anchorsRef.current.set(prev, {
        scrollTop: lastScrollTopRef.current,
        sticky: followingRef.current,
      });
      if (anchorsRef.current.size > 24) {
        const firstKey = anchorsRef.current.keys().next().value;
        if (firstKey) anchorsRef.current.delete(firstKey);
      }
      prevSessionRef.current = sessionId;
    }

    cancelSpring();
    const saved = anchorsRef.current.get(sessionId);
    const shouldFollow = saved ? saved.sticky : true;
    followingRef.current = shouldFollow;
    setShowJumpToBottom(!shouldFollow);

    const frame = requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (!el) return;
      programmaticUntilRef.current = performance.now() + PROGRAMMATIC_GUARD_MS;
      if (saved && !saved.sticky) {
        el.scrollTop = saved.scrollTop;
        lastScrollTopRef.current = el.scrollTop;
        const dist = Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
        const reStick = dist <= NEAR_BOTTOM_THRESHOLD_PX;
        followingRef.current = reStick;
        setShowJumpToBottom(!reStick);
      } else {
        el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight);
        lastScrollTopRef.current = el.scrollTop;
        followingRef.current = true;
        setShowJumpToBottom(false);
      }
    });

    return () => cancelAnimationFrame(frame);
  }, [sessionId, cancelSpring]);

  useEffect(() => {
    if (followingRef.current) {
      kickSpring();
    }
  }, [contentSignal, kickSpring]);

  useEffect(() => {
    const isNowStreaming = Boolean(options?.isStreaming);
    const wasStreaming = prevStreamingRef.current;
    prevStreamingRef.current = isNowStreaming;
    if (wasStreaming && !isNowStreaming && followingRef.current) {
      const f1 = requestAnimationFrame(() => {
        if (followingRef.current) kickSpring();
        settleFrameRef.current = requestAnimationFrame(() => {
          if (followingRef.current) kickSpring();
        });
      });
      return () => {
        cancelAnimationFrame(f1);
        if (settleFrameRef.current !== null) {
          cancelAnimationFrame(settleFrameRef.current);
          settleFrameRef.current = null;
        }
      };
    }
    return undefined;
  }, [options?.isStreaming, kickSpring]);

  useEffect(() => {
    const scrollEl = scrollRef.current;
    const contentEl = contentRef.current;
    if (!scrollEl || typeof ResizeObserver !== "function") return undefined;

    const ro = new ResizeObserver(() => {
      if (followingRef.current) {
        kickSpring();
      }
    });
    ro.observe(scrollEl);
    if (contentEl) ro.observe(contentEl);
    return () => ro.disconnect();
  }, [kickSpring, sessionId]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return undefined;

    const disengage = () => {
      cancelSpring();
      followingRef.current = false;
      const dist = Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
      if (dist > NEAR_BOTTOM_THRESHOLD_PX) {
        setShowJumpToBottom(true);
      }
    };

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY >= 0) return;
      const nested = e.target instanceof Element ? e.target.closest("[data-scrollable]") : null;
      if (nested && nested !== el) return;
      disengage();
    };

    const onPointerDown = (e: PointerEvent) => {
      const rect = el.getBoundingClientRect();
      if (e.clientX >= rect.right - 20) {
        cancelSpring();
        followingRef.current = false;
      }
    };

    const onScroll = () => {
      const isProgrammatic = performance.now() < programmaticUntilRef.current;
      const dist = Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
      const prevTop = lastScrollTopRef.current;
      lastScrollTopRef.current = el.scrollTop;

      if (isProgrammatic) {
        if (dist <= NEAR_BOTTOM_THRESHOLD_PX && followingRef.current) {
          setShowJumpToBottom(false);
        }
        return;
      }

      const result = evaluateScrollPosition({
        scrollTop: el.scrollTop,
        prevScrollTop: prevTop,
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
        wasSticky: followingRef.current,
      });
      followingRef.current = result.sticky;
      setShowJumpToBottom(result.showJumpToBottom);
      if (!result.sticky) {
        cancelSpring();
      }
    };

    el.addEventListener("wheel", onWheel, { passive: true });
    el.addEventListener("pointerdown", onPointerDown, { passive: true });
    el.addEventListener("scroll", onScroll, { passive: true });

    return () => {
      el.removeEventListener("wheel", onWheel);
      el.removeEventListener("pointerdown", onPointerDown);
      el.removeEventListener("scroll", onScroll);
      cancelSpring();
    };
  }, [cancelSpring]);

  return {
    scrollRef,
    contentRef,
    showJumpToBottom,
    snapToBottom,
    scheduleStickToBottom,
  };
}

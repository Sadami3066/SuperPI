import { useCallback, useEffect, useRef, useState } from "react";

export const STICK_BOTTOM_THRESHOLD_PX = 48;

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
  if (distanceFromBottom <= STICK_BOTTOM_THRESHOLD_PX) {
    return {
      sticky: true,
      showJumpToBottom: false,
      distanceFromBottom,
    };
  }
  return {
    sticky: input.wasSticky && distanceFromBottom <= STICK_BOTTOM_THRESHOLD_PX,
    showJumpToBottom: !input.wasSticky || distanceFromBottom > STICK_BOTTOM_THRESHOLD_PX,
    distanceFromBottom,
  };
}

interface SavedAnchor {
  scrollTop: number;
  sticky: boolean;
}

export function useChatScroll(sessionId: string, contentSignal: string | number) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const stickyRef = useRef(true);
  const lastScrollTopRef = useRef(0);
  const anchorsRef = useRef<Map<string, SavedAnchor>>(new Map());
  const prevSessionRef = useRef(sessionId);
  const rafRef = useRef<number | null>(null);

  const [showJumpToBottom, setShowJumpToBottom] = useState(false);

  const cancelPendingRaf = useCallback(() => {
    if (rafRef.current !== null) {
      cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    }
  }, []);

  const snapToBottom = useCallback(() => {
    cancelPendingRaf();
    const el = scrollRef.current;
    stickyRef.current = true;
    setShowJumpToBottom(false);
    if (!el) return;
    el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight);
    lastScrollTopRef.current = el.scrollTop;
  }, [cancelPendingRaf]);

  const scheduleStickToBottom = useCallback(() => {
    if (!stickyRef.current || rafRef.current !== null) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      const el = scrollRef.current;
      if (!el || !stickyRef.current) return;
      el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight);
      lastScrollTopRef.current = el.scrollTop;
    });
  }, []);

  useEffect(() => {
    const prev = prevSessionRef.current;
    if (prev && prev !== sessionId) {
      anchorsRef.current.set(prev, {
        scrollTop: lastScrollTopRef.current,
        sticky: stickyRef.current,
      });
      if (anchorsRef.current.size > 20) {
        const firstKey = anchorsRef.current.keys().next().value;
        if (firstKey) anchorsRef.current.delete(firstKey);
      }
      prevSessionRef.current = sessionId;
    }

    cancelPendingRaf();
    const saved = anchorsRef.current.get(sessionId);
    const shouldStick = saved ? saved.sticky : true;
    stickyRef.current = shouldStick;
    setShowJumpToBottom(!shouldStick);

    const frame = requestAnimationFrame(() => {
      const el = scrollRef.current;
      if (!el) return;
      if (saved && !saved.sticky) {
        el.scrollTop = saved.scrollTop;
        lastScrollTopRef.current = el.scrollTop;
        const dist = Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
        const reStick = dist <= STICK_BOTTOM_THRESHOLD_PX;
        stickyRef.current = reStick;
        setShowJumpToBottom(!reStick);
      } else {
        el.scrollTop = Math.max(0, el.scrollHeight - el.clientHeight);
        lastScrollTopRef.current = el.scrollTop;
        stickyRef.current = true;
        setShowJumpToBottom(false);
      }
    });

    return () => cancelAnimationFrame(frame);
  }, [sessionId, cancelPendingRaf]);

  useEffect(() => {
    if (stickyRef.current) {
      scheduleStickToBottom();
    }
  }, [contentSignal, scheduleStickToBottom]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    const disengage = () => {
      cancelPendingRaf();
      if (stickyRef.current) {
        stickyRef.current = false;
      }
      const dist = Math.max(0, el.scrollHeight - el.clientHeight - el.scrollTop);
      if (dist > STICK_BOTTOM_THRESHOLD_PX) {
        setShowJumpToBottom(true);
      }
    };

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY >= 0) return;
      const nested = e.target instanceof Element ? e.target.closest("[data-scrollable]") : null;
      if (nested && nested !== el) return;
      disengage();
    };

    const onScroll = () => {
      const result = evaluateScrollPosition({
        scrollTop: el.scrollTop,
        prevScrollTop: lastScrollTopRef.current,
        scrollHeight: el.scrollHeight,
        clientHeight: el.clientHeight,
        wasSticky: stickyRef.current,
      });
      lastScrollTopRef.current = el.scrollTop;
      stickyRef.current = result.sticky;
      setShowJumpToBottom(result.showJumpToBottom);
      if (!result.sticky) cancelPendingRaf();
    };

    el.addEventListener("wheel", onWheel, { passive: true });
    el.addEventListener("scroll", onScroll, { passive: true });

    return () => {
      el.removeEventListener("wheel", onWheel);
      el.removeEventListener("scroll", onScroll);
      cancelPendingRaf();
    };
  }, [cancelPendingRaf]);

  return {
    scrollRef,
    showJumpToBottom,
    snapToBottom,
    scheduleStickToBottom,
  };
}

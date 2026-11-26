import { describe, expect, it } from "vitest";
import type { SessionTranscript } from "../api/types";
import { evictTranscriptLru, MAX_CACHED_TRANSCRIPTS } from "../stores/sessionStore";

function makeTranscript(id: string): SessionTranscript {
  return {
    sessionId: id,
    turns: [],
    usage: null,
    contextPercent: 0,
    queuedInputs: [],
    pendingQuestion: null,
    loadedAt: 1,
  };
}

describe("sessionStore LRU eviction", () => {
  it("caps cached transcripts at MAX_CACHED_TRANSCRIPTS while preserving activeSessionId", () => {
    const cache: Record<string, SessionTranscript> = {};
    const order: string[] = [];
    for (let i = 1; i <= 7; i++) {
      const id = `s-${i}`;
      cache[id] = makeTranscript(id);
      order.push(id);
    }

    const result = evictTranscriptLru(cache, order, "s-1", MAX_CACHED_TRANSCRIPTS);
    expect(Object.keys(result.transcriptsById)).toHaveLength(MAX_CACHED_TRANSCRIPTS);
    expect(result.transcriptsById["s-1"]).toBeDefined();
    expect(result.transcriptsById["s-2"]).toBeUndefined();
    expect(result.transcriptsById["s-3"]).toBeUndefined();
    expect(result.transcriptsById["s-7"]).toBeDefined();
  });
});

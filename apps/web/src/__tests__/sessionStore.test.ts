import { describe, expect, it, vi } from "vitest";
import type { HostClient } from "../api/hostClient";
import type { HostSessionDetailDto } from "../api/types";
import { useSessionStore } from "../stores/sessionStore";
import { useStreamStore } from "../stores/streamStore";

function makeAssistantMessage(id: number, runId: string, text: string) {
  return {
    id,
    role: "assistant",
    text,
    streaming: false,
    status: "done",
    startedAt: 1000,
    finishedAt: 2000,
    runId,
    error: null,
    reminder: null,
    retrying: false,
    notice: null,
    summary: null,
    customType: null,
    customPayload: null,
    injectedSource: null,
    steps: [{ kind: "text", id: id * 10, text, tool: null, customType: null, payload: null }],
  };
}

function makeUserMessage(id: number, runId: string, text: string) {
  return {
    id,
    role: "user",
    text,
    streaming: false,
    status: "done",
    startedAt: 900,
    finishedAt: 900,
    runId,
    error: null,
    reminder: null,
    retrying: false,
    notice: null,
    summary: null,
    customType: null,
    customPayload: null,
    injectedSource: null,
    steps: [],
  };
}

describe("sessionStore resilience", () => {
  it("coalesces concurrent loadSession calls without discarding final transcript", async () => {
    useStreamStore.getState().resetAll();
    useSessionStore.getState().reset();
    useSessionStore.setState({
      summariesById: {
        s1: {
          id: "s1",
          botId: "home",
          title: "Test",
          updatedAtMs: 1000,
          cwd: "/tmp",
          pinned: false,
          archived: false,
          memoryExcluded: false,
          busy: false,
        },
      },
      sessionIdsByBot: { home: ["s1"] },
      activeSessionId: "s1",
    });

    let callCount = 0;
    let resolveFirst!: (val: HostSessionDetailDto) => void;
    let resolveSecond!: (val: HostSessionDetailDto) => void;

    const fakeClient = {
      getSession: vi.fn(() => {
        callCount++;
        if (callCount === 1) {
          return new Promise<HostSessionDetailDto>((res) => {
            resolveFirst = res;
          });
        }
        return new Promise<HostSessionDetailDto>((res) => {
          resolveSecond = res;
        });
      }),
    } as unknown as HostClient;

    const p1 = useSessionStore.getState().loadSession(fakeClient, "s1", "zh-CN");
    const p2 = useSessionStore.getState().loadSession(fakeClient, "s1", "zh-CN");

    resolveFirst({
      sessionId: "s1",
      busy: false,
      messages: [makeUserMessage(1, "r1", "hi"), makeAssistantMessage(2, "r1", "first")],
      queuedInputs: [],
    });

    await Promise.resolve();
    await Promise.resolve();

    resolveSecond({
      sessionId: "s1",
      busy: false,
      messages: [
        makeUserMessage(1, "r1", "hi"),
        makeAssistantMessage(2, "r1", "first"),
        makeUserMessage(3, "r2", "next"),
        makeAssistantMessage(4, "r2", "second"),
      ],
      queuedInputs: [],
    });

    await Promise.all([p1, p2]);
    const turns = useSessionStore.getState().transcriptsById["s1"]?.turns ?? [];
    expect(turns.map((t) => `${t.role}:${t.rawText}`)).toEqual([
      "user:hi",
      "assistant:first",
      "user:next",
      "assistant:second",
    ]);
  });

  it("carries finished liveRun forward before starting next run if host transcript lags", async () => {
    useStreamStore.getState().resetAll();
    useSessionStore.getState().reset();
    useSessionStore.setState({
      summariesById: {
        s2: {
          id: "s2",
          botId: "home",
          title: "Chat",
          updatedAtMs: 1000,
          cwd: "/tmp",
          pinned: false,
          archived: false,
          memoryExcluded: false,
          busy: false,
        },
      },
      sessionIdsByBot: { home: ["s2"] },
      transcriptsById: {
        s2: {
          sessionId: "s2",
          turns: [],
          usage: null,
          contextPercent: 0,
          queuedInputs: [],
          pendingQuestion: null,
          loadedAt: 1000,
        },
      },
      activeSessionId: "s2",
    });

    const fakeClient = {
      sendPrompt: vi.fn(async () => ({ ok: true })),
    } as unknown as HostClient;

    for (let i = 1; i <= 5; i++) {
      await useSessionStore.getState().sendMessage(fakeClient, {
        sessionId: "s2",
        botId: "home",
        locale: "zh-CN",
        sendWhileRunning: "steer",
        overrideText: `msg-${i}`,
      });
      const live = useStreamStore.getState().activeBySession["s2"];
      expect(live).toBeDefined();
      useStreamStore.getState().finishRun("s2", live!.runId, "done", `reply-${i}`);
      useSessionStore.getState().setSessionBusy("s2", false);
    }

    const committed = useSessionStore.getState().transcriptsById["s2"]?.turns ?? [];
    const latestLive = useStreamStore.getState().activeBySession["s2"];
    const committedReplies = committed.filter((t) => t.role === "assistant").map((t) => t.rawText);
    expect(committedReplies).toEqual(["reply-1", "reply-2", "reply-3", "reply-4"]);
    expect(latestLive?.blocks.some((b) => b.kind === "text" && b.text === "reply-5")).toBe(true);
  });
});

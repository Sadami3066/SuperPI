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

  it("defers session creation until first message is sent in draft mode", async () => {
    useStreamStore.getState().resetAll();
    useSessionStore.getState().reset();
    useSessionStore.getState().startDraftSession("home");
    useSessionStore.getState().setDraftModel("home", "openai/gpt-5", "high");

    expect(useSessionStore.getState().activeSessionId).toBe("");
    expect(useSessionStore.getState().draftBotId).toBe("home");

    const fakeClient = {
      createSession: vi.fn(async () => ({ sessionId: "s-lazy-1", cwd: "/tmp" })),
      getSession: vi.fn(async () => ({
        sessionId: "s-lazy-1",
        busy: false,
        messages: [],
        queuedInputs: [],
      })),
      setSessionModel: vi.fn(async () => ({ ok: true })),
      sendPrompt: vi.fn(async () => ({ ok: true })),
    } as unknown as HostClient;

    await useSessionStore.getState().sendMessage(fakeClient, {
      sessionId: "",
      botId: "home",
      locale: "zh-CN",
      sendWhileRunning: "steer",
      overrideText: "hello lazy session",
    });

    expect(fakeClient.createSession).toHaveBeenCalledTimes(1);
    expect(fakeClient.setSessionModel).toHaveBeenCalledWith("s-lazy-1", "openai", "gpt-5", "HIGH");
    expect(fakeClient.sendPrompt).toHaveBeenCalledWith(
      "s-lazy-1",
      expect.objectContaining({ text: "hello lazy session", steer: false }),
    );
    expect(useSessionStore.getState().activeSessionId).toBe("s-lazy-1");
    expect(useSessionStore.getState().draftBotId).toBeNull();
  });

  it("reports instant running and completion feedback for /compact and /goal", async () => {
    useStreamStore.getState().resetAll();
    useSessionStore.getState().reset();
    useSessionStore.setState({
      summariesById: {
        s3: {
          id: "s3",
          botId: "home",
          title: "Slash",
          updatedAtMs: 1000,
          cwd: "/tmp",
          pinned: false,
          archived: false,
          memoryExcluded: false,
          busy: false,
        },
      },
      sessionIdsByBot: { home: ["s3"] },
      activeSessionId: "s3",
    });

    let resolveCompact!: (val: { ok: boolean; message?: string }) => void;
    const fakeClient = {
      compactSession: vi.fn(
        () =>
          new Promise<{ ok: boolean; message?: string }>((res) => {
            resolveCompact = res;
          }),
      ),
      getSession: vi.fn(async () => ({
        sessionId: "s3",
        busy: false,
        messages: [],
        queuedInputs: [],
      })),
      createSessionGoal: vi.fn(async () => ({ ok: true })),
      pauseSessionGoal: vi.fn(async () => ({ ok: true })),
      resumeSessionGoal: vi.fn(async () => ({ ok: true })),
      deleteSessionGoal: vi.fn(async () => ({ ok: true })),
      sendPrompt: vi.fn(async () => ({ ok: true })),
    } as unknown as HostClient;

    const compactPromise = useSessionStore.getState().sendMessage(fakeClient, {
      sessionId: "s3",
      botId: "home",
      locale: "zh-CN",
      sendWhileRunning: "steer",
      overrideText: "/compact",
    });

    expect(useSessionStore.getState().slashStatusBySession["s3"]?.phase).toBe("running");
    resolveCompact({ ok: true });
    await compactPromise;
    expect(useSessionStore.getState().slashStatusBySession["s3"]?.phase).toBe("done");

    await useSessionStore.getState().sendMessage(fakeClient, {
      sessionId: "s3",
      botId: "home",
      locale: "zh-CN",
      sendWhileRunning: "steer",
      overrideText: "/goal Ship desktop polish",
    });

    expect(fakeClient.createSessionGoal).toHaveBeenCalledWith("s3", {
      objective: "Ship desktop polish",
    });
    expect(fakeClient.sendPrompt).toHaveBeenCalledWith(
      "s3",
      expect.objectContaining({ text: "Ship desktop polish" }),
    );

    await useSessionStore.getState().sendMessage(fakeClient, {
      sessionId: "s3",
      botId: "home",
      locale: "zh-CN",
      sendWhileRunning: "steer",
      overrideText: "/goal pause",
    });
    expect(fakeClient.pauseSessionGoal).toHaveBeenCalledWith("s3");
  });

  it("clears cached transcript pendingQuestion on answer, dismiss, and refreshQuestions", async () => {
    useStreamStore.getState().resetAll();
    useSessionStore.getState().reset();
    const q1 = {
      requestId: "q-req-1",
      sessionId: "sq1",
      toolCallId: "tc-1",
      questions: [
        {
          id: "q1",
          question: "Pick one?",
          options: [{ label: "A" }],
          multiSelect: false,
        },
      ],
    };
    useSessionStore.setState({
      activeSessionId: "sq1",
      pendingQuestions: [q1],
      transcriptsById: {
        sq1: {
          sessionId: "sq1",
          turns: [],
          usage: null,
          contextPercent: 0,
          queuedInputs: [],
          pendingQuestion: q1,
          loadedAt: 1000,
        },
      },
    });

    const listSpy = vi.fn(async () => []);
    const fakeClient = {
      answerQuestion: vi.fn(async () => ({ ok: true })),
      dismissQuestion: vi.fn(async () => ({ ok: true })),
      listQuestions: listSpy,
    } as unknown as HostClient;

    await useSessionStore.getState().answerQuestion(fakeClient, "q-req-1", [
      { questionId: "q1", selected: ["A"], skipped: false },
    ]);
    expect(useSessionStore.getState().pendingQuestions).toEqual([]);
    expect(useSessionStore.getState().transcriptsById["sq1"]?.pendingQuestion).toBeNull();

    const q2 = { ...q1, requestId: "q-req-2" };
    useSessionStore.setState({
      pendingQuestions: [q2],
      transcriptsById: {
        sq1: {
          ...useSessionStore.getState().transcriptsById["sq1"]!,
          pendingQuestion: q2,
        },
      },
    });
    await useSessionStore.getState().dismissQuestion(fakeClient, "q-req-2");
    expect(useSessionStore.getState().transcriptsById["sq1"]?.pendingQuestion).toBeNull();

    const q3 = { ...q1, requestId: "q-req-3" };
    useSessionStore.setState({
      pendingQuestions: [q3],
      transcriptsById: {
        sq1: {
          ...useSessionStore.getState().transcriptsById["sq1"]!,
          pendingQuestion: q3,
        },
      },
    });
    await useSessionStore.getState().refreshQuestions(fakeClient);
    expect(useSessionStore.getState().transcriptsById["sq1"]?.pendingQuestion).toBeNull();
  });
});

import { beforeEach, describe, expect, it } from "vitest";
import { useStreamStore } from "../stores/streamStore";

describe("streamStore", () => {
  beforeEach(() => {
    useStreamStore.getState().resetAll();
  });

  it("batches deltas, adopts pending runId, tracks tools, and clears atomically", () => {
    const store = useStreamStore.getState();
    store.beginRun("sess-1", "pending-100");
    store.beginRun("sess-1", "run-42");

    let run = useStreamStore.getState().activeBySession["sess-1"];
    expect(run?.runId).toBe("run-42");

    store.enqueueReasoningDelta("sess-1", "run-42", "Step 1 ");
    store.enqueueReasoningDelta("sess-1", "run-42", "analyze.");
    store.enqueueTextDelta("sess-1", "run-42", "Hello ");
    store.enqueueTextDelta("sess-1", "run-42", "world!");
    store.flushPending();

    run = useStreamStore.getState().activeBySession["sess-1"];
    expect(run?.blocks).toHaveLength(2);
    expect(run?.blocks[0]?.kind).toBe("activity");
    expect(run?.blocks[1]).toMatchObject({ kind: "text", text: "Hello world!" });

    store.upsertToolStart("sess-1", "run-42", "call-1", "bash", { command: "ls" });
    store.finishTool("sess-1", "run-42", "call-1", "bash", "file.txt", false, 45);
    store.finishRun("sess-1", "run-42", "completed");

    run = useStreamStore.getState().activeBySession["sess-1"];
    expect(run?.streaming).toBe(false);
    expect(run?.failed).toBe(false);

    store.clearRun("sess-1", "other-run");
    expect(useStreamStore.getState().activeBySession["sess-1"]).toBeDefined();

    store.clearRun("sess-1", "run-42");
    expect(useStreamStore.getState().activeBySession["sess-1"]).toBeUndefined();
  });

  it("marks running tools failed when run fails", () => {
    const store = useStreamStore.getState();
    store.beginRun("sess-2", "run-7");
    store.upsertToolStart("sess-2", "run-7", "c-1", "read", { path: "/tmp/x" });
    store.failRun("sess-2", "run-7", "Network error");

    const run = useStreamStore.getState().activeBySession["sess-2"];
    expect(run?.failed).toBe(true);
    expect(run?.error).toBe("Network error");
    const act = run?.blocks[0];
    expect(act?.kind).toBe("activity");
    if (act?.kind === "activity") {
      expect(act.steps[0]?.kind).toBe("tool");
      if (act.steps[0]?.kind === "tool") {
        expect(act.steps[0].tool.status).toBe("failed");
      }
    }
  });
});

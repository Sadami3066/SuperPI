import { describe, expect, it } from "vitest";
import { mapHostMessageToTurn } from "../api/mappers";
import { extractLatestTodosFromTurns, parseTodoPayload } from "../api/todo";
import type { ChatTurn, TurnBlock } from "../api/types";
import { useStreamStore } from "../stores/streamStore";

function makeTurn(id: string, blocks: TurnBlock[]): ChatTurn {
  return {
    id,
    runId: `run-${id}`,
    role: "assistant",
    rawText: "",
    attachments: [],
    blocks,
    notices: [],
    status: "done",
    failed: false,
    timestamp: "",
  };
}

describe("todo helpers", () => {
  it("parses valid todo_write payload and ignores empty or malformed items", () => {
    expect(parseTodoPayload(null)).toEqual([]);
    expect(parseTodoPayload("{bad")).toEqual([]);
    expect(
      parseTodoPayload(
        JSON.stringify({
          todos: [
            { content: "  Analyze schema  ", status: "completed" },
            { content: "Implement API", status: "in_progress" },
            { content: "Verify", status: "unknown_status" },
            { content: "   ", status: "pending" },
          ],
        }),
      ),
    ).toEqual([
      { content: "Analyze schema", status: "completed" },
      { content: "Implement API", status: "in_progress" },
      { content: "Verify", status: "pending" },
    ]);
  });

  it("extracts latest todos from finalized turns and prioritizes liveBlocks", () => {
    const turns: ChatTurn[] = [
      makeTurn("t1", [
        {
          key: "c1",
          kind: "custom_card",
          card: {
            id: "1",
            customType: "todo_write",
            payload: JSON.stringify({ todos: [{ content: "Step 1", status: "in_progress" }] }),
            text: "",
          },
        },
      ]),
      makeTurn("t2", [
        {
          key: "c2",
          kind: "custom_card",
          card: {
            id: "2",
            customType: "todo_write",
            payload: JSON.stringify({
              todos: [
                { content: "Step 1", status: "completed" },
                { content: "Step 2", status: "pending" },
              ],
            }),
            text: "",
          },
        },
      ]),
    ];

    expect(extractLatestTodosFromTurns(turns)).toEqual([
      { content: "Step 1", status: "completed" },
      { content: "Step 2", status: "pending" },
    ]);

    const liveBlocks: TurnBlock[] = [
      {
        key: "live-c",
        kind: "custom_card",
        card: {
          id: "live-1",
          customType: "todo_write",
          payload: JSON.stringify({
            todos: [
              { content: "Step 1", status: "completed" },
              { content: "Step 2", status: "completed" },
            ],
          }),
          text: "",
        },
      },
    ];

    expect(extractLatestTodosFromTurns(turns, liveBlocks)).toEqual([
      { content: "Step 1", status: "completed" },
      { content: "Step 2", status: "completed" },
    ]);
  });

  it("keeps adjacent activity steps merged across invisible todo_write cards", () => {
    const turn = mapHostMessageToTurn(
      {
        id: 10,
        role: "assistant",
        text: "All done",
        streaming: false,
        status: "done",
        startedAt: 1000,
        finishedAt: 2000,
        runId: "r-todo",
        retrying: false,
        steps: [
          { kind: "reasoning", id: 1, text: "Plan step 1" },
          {
            kind: "custom_card",
            id: 2,
            customType: "todo_write",
            payload: JSON.stringify({ todos: [{ content: "Task A", status: "in_progress" }] }),
            text: "",
          },
          { kind: "reasoning", id: 3, text: "Execute step 1" },
          { kind: "text", id: 4, text: "All done" },
        ],
      },
      "zh-CN",
    );

    const activityBlocks = turn.blocks.filter((b) => b.kind === "activity");
    expect(activityBlocks).toHaveLength(1);
    expect(extractLatestTodosFromTurns([turn])).toEqual([
      { content: "Task A", status: "in_progress" },
    ]);

    useStreamStore.getState().resetAll();
    useStreamStore.getState().beginRun("s-todo", "r-live");
    useStreamStore.getState().enqueueReasoningDelta("s-todo", "r-live", "First thought");
    useStreamStore.getState().flushPending();
    useStreamStore
      .getState()
      .addCustomCard(
        "s-todo",
        "r-live",
        "todo_write",
        JSON.stringify({ todos: [{ content: "Live task", status: "completed" }] }),
        "",
      );
    useStreamStore.getState().enqueueReasoningDelta("s-todo", "r-live", "Next thought");
    useStreamStore.getState().flushPending();

    const live = useStreamStore.getState().activeBySession["s-todo"];
    expect(live?.blocks.filter((b) => b.kind === "activity")).toHaveLength(1);
    expect(extractLatestTodosFromTurns([], live?.blocks)).toEqual([
      { content: "Live task", status: "completed" },
    ]);
  });

  it("folds multiple todo_write cards per turn into one inline block and updates live card in place", () => {
    const turn = mapHostMessageToTurn(
      {
        id: 20,
        role: "assistant",
        text: "Finished everything",
        streaming: false,
        status: "done",
        startedAt: 1000,
        finishedAt: 3000,
        runId: "r-fold",
        retrying: false,
        steps: [
          { kind: "reasoning", id: 1, text: "Step 1" },
          {
            kind: "custom_card",
            id: 2,
            customType: "todo_write",
            payload: JSON.stringify({ todos: [{ content: "A", status: "in_progress" }] }),
            text: "",
          },
          { kind: "reasoning", id: 3, text: "Step 2" },
          {
            kind: "custom_card",
            id: 4,
            customType: "todo_write",
            payload: JSON.stringify({
              todos: [
                { content: "A", status: "completed" },
                { content: "B", status: "completed" },
              ],
            }),
            text: "",
          },
          { kind: "text", id: 5, text: "Finished everything" },
        ],
      },
      "zh-CN",
    );

    const todoCards = turn.blocks.filter(
      (b) => b.kind === "custom_card" && b.card.customType === "todo_write",
    );
    expect(todoCards).toHaveLength(1);
    expect(turn.blocks.filter((b) => b.kind === "activity")).toHaveLength(1);
    expect(extractLatestTodosFromTurns([turn])).toEqual([
      { content: "A", status: "completed" },
      { content: "B", status: "completed" },
    ]);

    useStreamStore.getState().resetAll();
    useStreamStore.getState().beginRun("s-live-todo", "r-live-2");
    useStreamStore
      .getState()
      .addCustomCard(
        "s-live-todo",
        "r-live-2",
        "todo_write",
        JSON.stringify({ todos: [{ content: "One", status: "in_progress" }] }),
        "",
      );
    useStreamStore
      .getState()
      .addCustomCard(
        "s-live-todo",
        "r-live-2",
        "todo_write",
        JSON.stringify({ todos: [{ content: "One", status: "completed" }] }),
        "",
      );
    const liveBlocks = useStreamStore.getState().activeBySession["s-live-todo"]?.blocks ?? [];
    const liveTodos = liveBlocks.filter(
      (b) => b.kind === "custom_card" && b.card.customType === "todo_write",
    );
    expect(liveTodos).toHaveLength(1);
    expect(liveTodos[0].key).toBe("cc-todo");
    expect(extractLatestTodosFromTurns([], liveBlocks)).toEqual([
      { content: "One", status: "completed" },
    ]);
  });
});

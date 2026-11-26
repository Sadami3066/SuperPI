import { describe, expect, it } from "vitest";
import { extractLatestTodosFromTurns, parseTodoPayload } from "../api/todo";
import type { ChatTurn, TurnBlock } from "../api/types";

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
});

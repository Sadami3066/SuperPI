import { describe, expect, it } from "vitest";
import { formatWorkerStatus } from "../api/formatters";
import { mapHostMessageToTurn, normalizeThinkingLevel } from "../api/mappers";
import type { HostMessageDto } from "../api/types";
import { translate } from "../i18n";

describe("activity duration, thinking max, and worker waiting status", () => {
  it("computes per-block tool durations without repeating whole-turn duration across blocks", () => {
    const dto: HostMessageDto = {
      id: 1,
      role: "assistant",
      text: "Done",
      streaming: false,
      status: "done",
      startedAt: 1000,
      finishedAt: 52000,
      runId: "r-1",
      retrying: false,
      steps: [
        { kind: "reasoning", id: 1, text: "First step" },
        {
          kind: "tool",
          id: 2,
          tool: {
            id: 1,
            callId: "c1",
            name: "read",
            arguments: { path: "/a" },
            status: "done",
            startedAt: 1000,
            durationMs: 1200,
          },
        },
        { kind: "text", id: 3, text: "Interim text" },
        {
          kind: "tool",
          id: 4,
          tool: {
            id: 2,
            callId: "c2",
            name: "bash",
            arguments: { command: "ls" },
            status: "done",
            startedAt: 3000,
            durationMs: 3400,
          },
        },
      ],
    };

    const turn = mapHostMessageToTurn(dto, "zh-CN");
    const activityBlocks = turn.blocks.filter((b) => b.kind === "activity");
    expect(activityBlocks).toHaveLength(2);
    expect(activityBlocks[0].durationMs).toBe(1200);
    expect(activityBlocks[1].durationMs).toBe(3400);
    expect(turn.durationMs).toBe(51000);
  });

  it("falls back to whole-turn duration only for single pure-thinking activity block", () => {
    const dto: HostMessageDto = {
      id: 2,
      role: "assistant",
      text: "Answer",
      streaming: false,
      status: "done",
      startedAt: 1000,
      finishedAt: 12000,
      runId: "r-2",
      retrying: false,
      steps: [
        { kind: "reasoning", id: 1, text: "Thinking deeply..." },
        { kind: "text", id: 2, text: "Answer" },
      ],
    };

    const turn = mapHostMessageToTurn(dto, "zh-CN");
    const activityBlocks = turn.blocks.filter((b) => b.kind === "activity");
    expect(activityBlocks).toHaveLength(1);
    expect(activityBlocks[0].durationMs).toBe(11000);
  });

  it("preserves max thinking level and formats waiting_input worker status", () => {
    expect(normalizeThinkingLevel("max")).toBe("max");
    expect(normalizeThinkingLevel("MAX")).toBe("max");
    const t = (key: Parameters<typeof translate>[1]) => translate("zh-CN", key);
    expect(formatWorkerStatus("waiting_input", t)).toBe("等待确认");
  });
});

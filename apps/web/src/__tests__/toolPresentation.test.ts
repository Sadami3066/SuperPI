import { describe, expect, it } from "vitest";
import type { ActivityStep, ToolTraceItem } from "../api/types";
import {
  extractLiveThinkingLine,
  formatElapsed,
  groupProcessSteps,
  toolArgsPreview,
  toolDescriptionArg,
  toolIconKind,
} from "../features/chat/toolPresentation";

function makeTool(id: string, name = "bash"): ToolTraceItem {
  return {
    id,
    name,
    summary: id,
    rawArgs: { command: "echo hi" },
    argsText: "command: echo hi",
    output: "hi",
    status: "done",
    durationMs: 120,
  };
}

describe("toolPresentation", () => {
  it("maps tool names to semantic icon kinds", () => {
    expect(toolIconKind("read")).toBe("book");
    expect(toolIconKind("edit")).toBe("pen");
    expect(toolIconKind("bash")).toBe("terminal");
    expect(toolIconKind("system_shell")).toBe("terminal");
    expect(toolIconKind("web_search")).toBe("search");
    expect(toolIconKind("memory_recall")).toBe("brain");
    expect(toolIconKind("todo_write")).toBe("check");
    expect(toolIconKind("unknown_tool")).toBe("dot");
  });

  it("extracts description and prefers key arguments cleanly", () => {
    expect(toolDescriptionArg({ description: "  Read  CPU   info " })).toBe("Read CPU info");
    expect(
      toolArgsPreview({
        description: "Ignore me",
        path: "/data/user/0/io.github.whywood.superpi/files/notes.md",
      }),
    ).toBe("~/notes.md");
  });

  it("cleans markdown prefixes from live thinking line", () => {
    const line = extractLiveThinkingLine("## **Check `/proc/cpuinfo` now**");
    expect(line?.text).toBe("Check /proc/cpuinfo now");
  });

  it("groups 3 or more consecutive tools into tool_run while keeping pairs single", () => {
    const twoTools: ActivityStep[] = [
      { id: "r1", kind: "thinking", text: "Think" },
      { id: "t1", kind: "tool", tool: makeTool("1") },
      { id: "t2", kind: "tool", tool: makeTool("2") },
    ];
    expect(groupProcessSteps(twoTools).map((n) => n.kind)).toEqual(["single", "single", "single"]);

    const threeTools: ActivityStep[] = [
      { id: "r1", kind: "thinking", text: "Think" },
      { id: "t1", kind: "tool", tool: makeTool("1") },
      { id: "t2", kind: "tool", tool: makeTool("2") },
      { id: "t3", kind: "tool", tool: makeTool("3") },
    ];
    const grouped = groupProcessSteps(threeTools);
    expect(grouped.map((n) => n.kind)).toEqual(["single", "tool_run"]);
    if (grouped[1]?.kind === "tool_run") {
      expect(grouped[1].tools).toHaveLength(3);
    }
  });

  it("formats elapsed duration cleanly in zh-CN and en", () => {
    expect(formatElapsed(17_200, "zh-CN")).toBe("17 秒");
    expect(formatElapsed(75_000, "zh-CN")).toBe("1 分 15 秒");
    expect(formatElapsed(17_200, "en")).toBe("17s");
  });
});

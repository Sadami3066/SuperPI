import { describe, expect, it } from "vitest";
import {
  splitMarkdownBlocks,
  splitTextByAlpha,
  STREAM_MAX_BATCHES,
  StreamTextFadeAnimator,
} from "../features/chat/streamReveal";

describe("streamReveal", () => {
  it("splits markdown into blocks while preserving fenced code blocks and closing trailing open fences when streaming", () => {
    const md = "First paragraph.\n\n```ts\nconst a = 1;\n\nconst b = 2;\n```\n\nThird paragraph.";
    const blocks = splitMarkdownBlocks(md, false);
    expect(blocks).toHaveLength(3);
    expect(blocks[1]).toContain("const a = 1;\n\nconst b = 2;");

    const openStreaming = splitMarkdownBlocks("Intro\n\n```python\nprint(1)", true);
    expect(openStreaming).toHaveLength(2);
    expect(openStreaming[1].endsWith("```")).toBe(true);
  });

  it("animates newly appended text incrementally from 0 to 1 and settles smoothly", () => {
    const animator = new StreamTextFadeAnimator();
    animator.sync("Hello ", true, false, 100);
    expect(animator.hasActiveBatches).toBe(false);

    animator.sync("Hello world!", true, false, 120);
    expect(animator.hasActiveBatches).toBe(true);
    expect(animator.alphaAt(0, 120)).toBe(1);
    expect(animator.alphaAt(11, 120)).toBe(0);

    const midRuns = splitTextByAlpha("Hello world!", 0, animator, 260);
    expect(midRuns.some((r) => r.alpha > 0 && r.alpha < 1)).toBe(true);

    animator.advance(700);
    expect(animator.hasActiveBatches).toBe(false);
    expect(animator.alphaAt(11, 700)).toBe(1);
  });

  it("preserves already revealed prefix when markdown closing delimiters strip leading markers", () => {
    const animator = new StreamTextFadeAnimator();
    animator.sync("say **wor", true, false, 100);
    animator.advance(500);
    expect(animator.alphaAt(6, 500)).toBe(1);

    animator.sync("say world", true, false, 520);
    const runs = splitTextByAlpha("say world", 0, animator, 520);
    expect(runs).toEqual([
      { text: "say wor", alpha: 1 },
      { text: "ld", alpha: 0 },
    ]);
  });

  it("caps active fade batches at STREAM_MAX_BATCHES", () => {
    const animator = new StreamTextFadeAnimator();
    let text = "";
    animator.sync("", true, false, 0);
    for (let i = 0; i < STREAM_MAX_BATCHES + 10; i++) {
      text += `w${i} `;
      animator.sync(text, true, false, i * 5);
    }
    expect(animator.activeBatchCount).toBeLessThanOrEqual(STREAM_MAX_BATCHES);
  });
});

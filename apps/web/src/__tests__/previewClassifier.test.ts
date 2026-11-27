import { describe, expect, it } from "vitest";
import { classifyPreviewKind, parseCsvRows } from "../workbench/previewClassifier";

describe("previewClassifier", () => {
  it("classifies code, knowledge documents, and binary extensions accurately", () => {
    expect(classifyPreviewKind("README.md")).toBe("markdown");
    expect(classifyPreviewKind("index.html")).toBe("html");
    expect(classifyPreviewKind("paper.pdf")).toBe("pdf");
    expect(classifyPreviewKind("report.docx")).toBe("docx");
    expect(classifyPreviewKind("metrics.csv")).toBe("csv");
    expect(classifyPreviewKind("diagram.webp")).toBe("image");
    expect(classifyPreviewKind("clip.mp4")).toBe("video");
    expect(classifyPreviewKind("demo.webm")).toBe("video");
    expect(classifyPreviewKind("recording", "video/quicktime")).toBe("video");
    expect(classifyPreviewKind("MainActivity.kt")).toBe("code");
    expect(classifyPreviewKind("archive.zip")).toBe("binary");
  });

  it("parses CSV rows including quoted commas and escaped quotes", () => {
    const csv = "name,role,notes\n\"Alice, B.\",engineer,\"uses \"\"SuperPI\"\" daily\"\nBob,pm,ok\n";
    expect(parseCsvRows(csv)).toEqual([
      ["name", "role", "notes"],
      ["Alice, B.", "engineer", "uses \"SuperPI\" daily"],
      ["Bob", "pm", "ok"],
    ]);
  });
});

import { describe, expect, it } from "vitest";
import { parseUnifiedDiff } from "../workbench/diffParser";

describe("diffParser", () => {
  it("parses unified git diff with hunks, line numbers, and stats", () => {
    const sample = [
      "diff --git a/src/app.ts b/src/app.ts",
      "index 1234567..89abcde 100644",
      "--- a/src/app.ts",
      "+++ b/src/app.ts",
      "@@ -10,4 +10,5 @@ function main() {",
      " const x = 1;",
      "-const y = 2;",
      "+const y = 3;",
      "+const z = 4;",
      " return x + y;",
    ].join("\n");

    const files = parseUnifiedDiff(sample);
    expect(files).toHaveLength(1);
    const f = files[0];
    expect(f.displayPath).toBe("src/app.ts");
    expect(f.additions).toBe(2);
    expect(f.deletions).toBe(1);
    expect(f.hunks).toHaveLength(1);
    expect(f.hunks[0].lines).toEqual([
      { kind: "ctx", oldLine: 10, newLine: 10, text: "const x = 1;" },
      { kind: "del", oldLine: 11, newLine: null, text: "const y = 2;" },
      { kind: "add", oldLine: null, newLine: 11, text: "const y = 3;" },
      { kind: "add", oldLine: null, newLine: 12, text: "const z = 4;" },
      { kind: "ctx", oldLine: 12, newLine: 13, text: "return x + y;" },
    ]);
  });

  it("handles new and binary files cleanly", () => {
    const sample = [
      "diff --git a/logo.png b/logo.png",
      "new file mode 100644",
      "Binary files /dev/null and b/logo.png differ",
    ].join("\n");
    const files = parseUnifiedDiff(sample);
    expect(files).toHaveLength(1);
    expect(files[0].isNew).toBe(true);
    expect(files[0].isBinary).toBe(true);
  });
});

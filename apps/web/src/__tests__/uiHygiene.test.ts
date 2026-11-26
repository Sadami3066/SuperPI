import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const srcRoot = path.resolve(__dirname, "..");

function collectSourceFiles(dir: string, includeUi = false): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "__tests__" || (!includeUi && entry.name === "ui")) continue;
      results.push(...collectSourceFiles(full, includeUi));
    } else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) {
      results.push(full);
    }
  }
  return results;
}

const FORBIDDEN_PATTERNS: Array<{ label: string; regex: RegExp }> = [
  { label: "native <select>", regex: /<select\b/ },
  { label: "native checkbox input", regex: /type=["']checkbox["']/i },
  { label: "native radio input", regex: /type=["']radio["']/i },
  { label: "native range input", regex: /type=["']range["']/i },
  { label: "browser blocking dialog", regex: /\bwindow\.(confirm|alert|prompt)\b/ },
];

describe("UI hygiene guardrails", () => {
  it("contains zero raw native controls or browser blocking dialogs outside src/ui", () => {
    const files = collectSourceFiles(srcRoot);
    expect(files.length).toBeGreaterThan(10);

    const violations: string[] = [];
    for (const file of files) {
      const content = fs.readFileSync(file, "utf8");
      const rel = path.relative(srcRoot, file);
      for (const rule of FORBIDDEN_PATTERNS) {
        if (rule.regex.test(content)) {
          violations.push(`${rel}: matched ${rule.label}`);
        }
      }
    }

    expect(violations).toEqual([]);
  });

  it("contains zero allocating Zustand selectors that destabilize React snapshots", () => {
    const files = collectSourceFiles(srcRoot, true);
    const unstablePattern = /(\?\?|\|\|)\s*(\[\]|\{\})|\.filter\(|\.map\(|\.slice\(|=>\s*\(\s*\{/;
    const storeCallRe = /use[A-Za-z]+Store\(/g;
    const violations: string[] = [];

    for (const file of files) {
      const content = fs.readFileSync(file, "utf8");
      const rel = path.relative(srcRoot, file);
      let m: RegExpExecArray | null;
      while ((m = storeCallRe.exec(content))) {
        let i = m.index + m[0].length;
        let depth = 1;
        while (i < content.length && depth > 0) {
          if (content[i] === "(") depth++;
          else if (content[i] === ")") depth--;
          i++;
        }
        const call = content.slice(m.index, i);
        if (unstablePattern.test(call)) {
          const line = content.slice(0, m.index).split("\n").length;
          violations.push(`${rel}:${line}: ${call.replace(/\s+/g, " ")}`);
        }
      }
    }

    expect(violations).toEqual([]);
  });
});

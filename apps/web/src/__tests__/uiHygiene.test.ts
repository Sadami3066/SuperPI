import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const srcRoot = path.resolve(__dirname, "..");

function collectSourceFiles(dir: string): string[] {
  const results: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "ui" || entry.name === "__tests__") continue;
      results.push(...collectSourceFiles(full));
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
});

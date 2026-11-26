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

  it("uses only defined --pi-* CSS variables and avoids hardcoded #fff on var(--pi-accent)", () => {
    const appCss = fs.readFileSync(path.join(srcRoot, "styles", "app.css"), "utf8");
    const themeTs = fs.readFileSync(path.join(srcRoot, "platform", "theme.ts"), "utf8");
    const defined = new Set<string>();
    const defRe = /(--pi-[a-z0-9-]+)\s*[:"]/g;
    for (const src of [appCss, themeTs]) {
      let dm: RegExpExecArray | null;
      while ((dm = defRe.exec(src))) {
        defined.add(dm[1]);
      }
    }

    const files = collectSourceFiles(srcRoot, true);
    const useRe = /var\((--pi-[a-z0-9-]+)\)/g;
    const whiteOnAccentRe = /background:\s*["']var\(--pi-accent\)["'][^}]*color:\s*["']#fff/i;
    const violations: string[] = [];

    for (const file of files) {
      const content = fs.readFileSync(file, "utf8");
      const rel = path.relative(srcRoot, file);
      let um: RegExpExecArray | null;
      while ((um = useRe.exec(content))) {
        if (!defined.has(um[1])) {
          violations.push(`${rel}: undefined CSS var ${um[1]}`);
        }
      }
      if (whiteOnAccentRe.test(content)) {
        violations.push(`${rel}: hardcoded #fff text on var(--pi-accent)`);
      }
    }

    expect(violations).toEqual([]);
  });
});

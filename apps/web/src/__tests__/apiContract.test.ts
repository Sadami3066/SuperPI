import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "../../../..");
const hostServerDir = path.join(
  repoRoot,
  "apps/host/src/main/kotlin/io/github/whywood/superpi/host/server",
);
const hostClientPath = path.resolve(__dirname, "../api/hostClient.ts");

function normalizePath(raw: string): string {
  // Strip `?query...` or trailing query interpolation like `/v1/timers${query}` or `/v1/approvals${suffix ? ...`
  const withoutQuery = (raw.split("?")[0] ?? raw)
    .replace(/(?<!\/)\$\{[^}]*\}?.*$/, "");
  const trimmed = withoutQuery.replace(/^\/api/, "").replace(/\/+$/, "");
  return trimmed
    .replace(/\$\{[^}]+\}/g, "{param}")
    .replace(/\{[^}]+\}/g, "{param}");
}

function collectHostRoutes(): Set<string> {
  const routes = new Set<string>();
  const files = fs.readdirSync(hostServerDir).filter((f) => f.endsWith(".kt"));
  const routeRe = /\b(get|post|put|patch|delete|webSocket)\(\s*"(\/v1\/[^"]+)"\s*\)/g;
  const subRouteRe = /\broute\(\s*"(\/v1\/[^"]+)"\s*\)\s*\{([\s\S]*?)\n\s*\}/g;

  for (const file of files) {
    const content = fs.readFileSync(path.join(hostServerDir, file), "utf8");
    let m: RegExpExecArray | null;
    while ((m = routeRe.exec(content))) {
      const method = m[1].toUpperCase();
      const norm = normalizePath(m[2]);
      routes.add(`${method} ${norm}`);
      routes.add(norm);
    }
    let sm: RegExpExecArray | null;
    while ((sm = subRouteRe.exec(content))) {
      const base = sm[1].replace(/\/+$/, "");
      const body = sm[2];
      const innerRe = /\b(get|post|put|patch|delete)\(\s*(?:"([^"]*)")?\s*\)/g;
      let im: RegExpExecArray | null;
      while ((im = innerRe.exec(body))) {
        const method = im[1].toUpperCase();
        const suffix = im[2] ? (im[2].startsWith("/") ? im[2] : `/${im[2]}`) : "";
        const norm = normalizePath(`${base}${suffix}`);
        routes.add(`${method} ${norm}`);
        routes.add(norm);
      }
    }
  }
  return routes;
}

function collectClientEndpoints(): Array<{ line: number; raw: string; normalized: string }> {
  const content = fs.readFileSync(hostClientPath, "utf8");
  const found: Array<{ line: number; raw: string; normalized: string }> = [];
  const urlRe = /["'`](\/api\/v1\/[^"'`]+)["'`]/g;
  let m: RegExpExecArray | null;
  while ((m = urlRe.exec(content))) {
    const raw = m[1];
    const line = content.slice(0, m.index).split("\n").length;
    found.push({
      line,
      raw,
      normalized: normalizePath(raw),
    });
  }
  return found;
}

describe("Host <-> Web API route contract", () => {
  it("matches every /api/v1/* path called in hostClient.ts to a registered Host Ktor route", () => {
    const hostRoutes = collectHostRoutes();
    const clientEndpoints = collectClientEndpoints();

    expect(hostRoutes.size).toBeGreaterThan(50);
    expect(clientEndpoints.length).toBeGreaterThan(50);

    const missing: string[] = [];
    for (const ep of clientEndpoints) {
      if (!hostRoutes.has(ep.normalized)) {
        missing.push(`hostClient.ts:${ep.line} -> ${ep.normalized} (from ${ep.raw})`);
      }
    }

    expect(missing).toEqual([]);
  });
});

export interface ParsedDiffLine {
  kind: "ctx" | "add" | "del" | "meta";
  oldLine: number | null;
  newLine: number | null;
  text: string;
}

export interface ParsedDiffHunk {
  header: string;
  lines: ParsedDiffLine[];
}

export interface ParsedDiffFile {
  oldPath: string;
  newPath: string;
  displayPath: string;
  isNew: boolean;
  isDeleted: boolean;
  isBinary: boolean;
  additions: number;
  deletions: number;
  hunks: ParsedDiffHunk[];
}

const HUNK_RE = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/;

function stripDiffPrefix(raw: string): string {
  const clean = raw.trim().replace(/^"|"$/g, "");
  if (clean.startsWith("a/") || clean.startsWith("b/")) return clean.slice(2);
  return clean;
}

export function parseUnifiedDiff(diffText: string): ParsedDiffFile[] {
  if (!diffText.trim()) return [];
  const lines = diffText.replace(/\r\n/g, "\n").split("\n");
  const files: ParsedDiffFile[] = [];
  let current: ParsedDiffFile | null = null;
  let currentHunk: ParsedDiffHunk | null = null;
  let oldCursor = 0;
  let newCursor = 0;

  const flushHunk = () => {
    if (current && currentHunk) {
      current.hunks.push(currentHunk);
      currentHunk = null;
    }
  };

  const flushFile = () => {
    flushHunk();
    if (current) {
      if (!current.displayPath) {
        current.displayPath =
          current.newPath && current.newPath !== "/dev/null"
            ? current.newPath
            : current.oldPath;
      }
      files.push(current);
      current = null;
    }
  };

  for (const line of lines) {
    if (line.startsWith("diff --git ")) {
      flushFile();
      const rest = line.slice("diff --git ".length);
      const mid = rest.indexOf(" b/");
      const oldP = mid >= 0 ? stripDiffPrefix(rest.slice(0, mid)) : "";
      const newP = mid >= 0 ? stripDiffPrefix(rest.slice(mid + 1)) : "";
      current = {
        oldPath: oldP,
        newPath: newP,
        displayPath: newP || oldP,
        isNew: false,
        isDeleted: false,
        isBinary: false,
        additions: 0,
        deletions: 0,
        hunks: [],
      };
      continue;
    }

    if (!current) {
      if (line.startsWith("--- ") || line.startsWith("@@ ")) {
        current = {
          oldPath: "",
          newPath: "",
          displayPath: "patch",
          isNew: false,
          isDeleted: false,
          isBinary: false,
          additions: 0,
          deletions: 0,
          hunks: [],
        };
      } else {
        continue;
      }
    }

    if (!currentHunk) {
      if (line.startsWith("new file mode ")) {
        current.isNew = true;
        continue;
      }
      if (line.startsWith("deleted file mode ")) {
        current.isDeleted = true;
        continue;
      }
      if (line.startsWith("Binary files ") || line === "GIT binary patch") {
        current.isBinary = true;
        continue;
      }
      if (line.startsWith("--- ")) {
        const raw = line.slice(4).trim();
        current.oldPath = raw === "/dev/null" ? raw : stripDiffPrefix(raw);
        if (raw === "/dev/null") current.isNew = true;
        continue;
      }
      if (line.startsWith("+++ ")) {
        const raw = line.slice(4).trim();
        current.newPath = raw === "/dev/null" ? raw : stripDiffPrefix(raw);
        if (raw === "/dev/null") current.isDeleted = true;
        else current.displayPath = current.newPath;
        continue;
      }
    }

    const hunkMatch = HUNK_RE.exec(line);
    if (hunkMatch) {
      flushHunk();
      oldCursor = Number.parseInt(hunkMatch[1], 10) || 1;
      newCursor = Number.parseInt(hunkMatch[2], 10) || 1;
      currentHunk = { header: line, lines: [] };
      continue;
    }

    if (!currentHunk) continue;

    if (line.startsWith("+")) {
      current.additions++;
      currentHunk.lines.push({
        kind: "add",
        oldLine: null,
        newLine: newCursor++,
        text: line.slice(1),
      });
    } else if (line.startsWith("-")) {
      current.deletions++;
      currentHunk.lines.push({
        kind: "del",
        oldLine: oldCursor++,
        newLine: null,
        text: line.slice(1),
      });
    } else if (line.startsWith("\\")) {
      currentHunk.lines.push({
        kind: "meta",
        oldLine: null,
        newLine: null,
        text: line,
      });
    } else {
      const text = line.startsWith(" ") ? line.slice(1) : line;
      currentHunk.lines.push({
        kind: "ctx",
        oldLine: oldCursor++,
        newLine: newCursor++,
        text,
      });
    }
  }

  flushFile();
  return files;
}

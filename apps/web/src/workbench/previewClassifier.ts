export type PreviewKind =
  | "markdown"
  | "html"
  | "pdf"
  | "docx"
  | "csv"
  | "image"
  | "code"
  | "binary";

const TEXT_CODE_EXTS = new Set([
  "txt",
  "log",
  "ini",
  "conf",
  "env",
  "toml",
  "yaml",
  "yml",
  "json",
  "jsonl",
  "xml",
  "ts",
  "tsx",
  "mts",
  "cts",
  "js",
  "jsx",
  "mjs",
  "cjs",
  "py",
  "kt",
  "kts",
  "java",
  "rs",
  "go",
  "c",
  "h",
  "cpp",
  "hpp",
  "css",
  "scss",
  "less",
  "sql",
  "sh",
  "bash",
  "zsh",
  "rb",
  "php",
  "swift",
  "gradle",
  "properties",
  "gitignore",
  "dockerfile",
]);

const IMAGE_EXTS = new Set(["png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "avif"]);

export function classifyPreviewKind(name: string, mime?: string): PreviewKind {
  const lowerName = name.toLowerCase();
  const base = lowerName.split("/").pop() ?? lowerName;
  const ext = base.includes(".") ? base.split(".").pop() ?? "" : "";
  const lowerMime = (mime ?? "").toLowerCase();

  if (ext === "md" || ext === "markdown" || lowerMime === "text/markdown") return "markdown";
  if (ext === "html" || ext === "htm" || lowerMime === "text/html") return "html";
  if (ext === "pdf" || lowerMime === "application/pdf") return "pdf";
  if (
    ext === "docx" ||
    lowerMime === "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  ) {
    return "docx";
  }
  if (ext === "csv" || ext === "tsv" || lowerMime === "text/csv") return "csv";
  if (IMAGE_EXTS.has(ext) || lowerMime.startsWith("image/")) return "image";
  if (
    TEXT_CODE_EXTS.has(ext) ||
    lowerMime.startsWith("text/") ||
    lowerMime === "application/json" ||
    lowerMime === "application/javascript" ||
    lowerMime === "application/x-yaml" ||
    lowerMime === "application/xml" ||
    base === "dockerfile" ||
    base === "makefile" ||
    base === ".gitignore"
  ) {
    return "code";
  }
  return "binary";
}

export function parseCsvRows(raw: string, delimiter = ",", maxRows = 500): string[][] {
  if (!raw) return [];
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let inQuotes = false;

  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    if (inQuotes) {
      if (ch === "\"") {
        if (raw[i + 1] === "\"") {
          field += "\"";
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += ch;
      }
      continue;
    }

    if (ch === "\"") {
      inQuotes = true;
    } else if (ch === delimiter) {
      row.push(field);
      field = "";
    } else if (ch === "\r") {
      if (raw[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
      if (rows.length >= maxRows) return rows;
    } else if (ch === "\n") {
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
      if (rows.length >= maxRows) return rows;
    } else {
      field += ch;
    }
  }

  if (field.length > 0 || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

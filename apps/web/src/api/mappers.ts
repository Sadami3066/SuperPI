import type {
  ActivityStep,
  AnsweredQuestionEntry,
  AttachmentChip,
  ChatTurn,
  HostMessageDto,
  HostToolTraceDto,
  HostUsageDto,
  PresentedItem,
  ThinkingLevel,
  ToolTraceItem,
  TurnBlock,
} from "./types";

const ATTACHMENTS_OPEN = "<attachments>";
const ATTACHMENTS_CLOSE = "</attachments>";

export function isAbortErrorMessage(message?: string | null): boolean {
  if (!message) return false;
  return /\b(abort(ed)?|cancel(led|ed)?|interrupted|stopped by user|user_aborted)\b|已取消|已停止|用户中断/i.test(
    message,
  );
}

export function normalizeThinkingLevel(raw?: string | null): ThinkingLevel {
  const lower = (raw ?? "").trim().toLowerCase();
  if (
    lower === "minimal" ||
    lower === "low" ||
    lower === "medium" ||
    lower === "high" ||
    lower === "xhigh" ||
    lower === "max"
  ) {
    return lower;
  }
  return "off";
}

export function formatClockTime(ts?: number | null, locale: "zh-CN" | "en" = "zh-CN"): string {
  if (!ts || ts <= 0) return "";
  return new Date(ts).toLocaleTimeString(locale === "en" ? "en-US" : "zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

export function formatRelativeTime(
  ts?: number | null,
  locale: "zh-CN" | "en" = "zh-CN",
  nowMs = Date.now(),
): string {
  if (!ts || ts <= 0) return "";
  const diffSec = Math.max(0, Math.floor((nowMs - ts) / 1000));
  if (diffSec < 60) return locale === "en" ? "now" : "刚刚";
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m`;
  const diffHours = Math.floor(diffMin / 60);
  if (diffHours < 24) return `${diffHours}h`;
  const diffDays = Math.floor(diffHours / 24);
  if (diffDays < 30) return `${diffDays}d`;
  return `${Math.floor(diffDays / 30)}mo`;
}

export function computeContextPercent(usage?: HostUsageDto | null): number {
  if (!usage || usage.contextWindow <= 0) return 0;
  const active = usage.contextTokens > 0 ? usage.contextTokens : 0;
  return Math.min(100, Math.max(0, Math.round((active / usage.contextWindow) * 100)));
}

export function classifyAttachmentKind(
  mime: string,
  name: string,
): "image" | "text" | "folder" | "other" {
  const m = mime.toLowerCase();
  if (m === "inode/directory") return "folder";
  if (m.startsWith("image/")) return "image";
  if (
    m.startsWith("text/") ||
    m === "application/json" ||
    m === "application/xml" ||
    m === "application/javascript" ||
    m === "application/x-yaml"
  ) {
    return "text";
  }
  const ext = name.includes(".") ? name.split(".").pop()!.toLowerCase() : "";
  if (["png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "avif"].includes(ext)) return "image";
  if (
    [
      "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java",
      "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "css", "html", "htm", "sh",
      "bash", "sql", "rs", "go", "swift", "rb", "log", "csv", "ini", "conf",
    ].includes(ext)
  ) {
    return "text";
  }
  return "other";
}

export function formatAttachmentSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "0 B";
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${bytes} B`;
}

export function encodeAttachmentBlock(
  attachments: Array<{ path: string; name: string; mime: string; size: number }>,
): string {
  if (attachments.length === 0) return "";
  return [
    ATTACHMENTS_OPEN,
    ...attachments.map(
      (a) =>
        `- ${a.path} | ${a.name.replace(/\|/g, "-")} | ${a.mime || "application/octet-stream"} | ${a.size}`,
    ),
    "use these absolute paths with file tools to inspect the attachments",
    ATTACHMENTS_CLOSE,
  ].join("\n");
}

export function parseAttachmentBlock(source: string): {
  attachments: AttachmentChip[];
  text: string;
} {
  const open = source.indexOf(ATTACHMENTS_OPEN);
  if (open < 0) return { attachments: [], text: source.trim() };
  const close = source.indexOf(ATTACHMENTS_CLOSE, open + ATTACHMENTS_OPEN.length);
  if (close < 0) return { attachments: [], text: source.trim() };

  const block = source.slice(open + ATTACHMENTS_OPEN.length, close);
  const attachments: AttachmentChip[] = [];
  for (const rawLine of block.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line.startsWith("- ")) continue;
    const parts = line
      .slice(2)
      .split(" | ")
      .map((p) => p.trim());
    if (parts.length < 4) continue;
    const filePath = parts[0];
    const name = parts[1] || filePath.split("/").pop() || "attachment";
    const mime = parts[2] || "application/octet-stream";
    const size = Number.parseInt(parts[3], 10) || 0;
    attachments.push({
      id: `${filePath}-${attachments.length}`,
      name,
      mime,
      size,
      path: filePath,
      kind: classifyAttachmentKind(mime, name),
    });
  }
  const text = (source.slice(0, open) + source.slice(close + ATTACHMENTS_CLOSE.length)).trim();
  return { attachments, text };
}

export function parsePresentedItemsFromOutput(output?: string | null): PresentedItem[] | null {
  if (!output || !output.trim()) return null;
  try {
    const parsed = JSON.parse(output) as { items?: unknown };
    if (!parsed || !Array.isArray(parsed.items)) return null;
    const validTypes = new Set(["file", "image", "video", "html", "miniapp", "card"]);
    const items: PresentedItem[] = [];
    for (const raw of parsed.items) {
      if (!raw || typeof raw !== "object") continue;
      const obj = raw as Record<string, unknown>;
      const type = String(obj.type ?? "").toLowerCase();
      if (!validTypes.has(type)) continue;
      const filePath = typeof obj.path === "string" ? obj.path.trim() : undefined;
      const id = typeof obj.id === "string" ? obj.id.trim() : undefined;
      const name =
        (typeof obj.name === "string" && obj.name.trim()) ||
        (filePath ? filePath.split("/").pop() || filePath : undefined) ||
        id ||
        type;
      items.push({
        type: type as PresentedItem["type"],
        path: filePath,
        id,
        name,
        mime: typeof obj.mime === "string" ? obj.mime : undefined,
        title: typeof obj.title === "string" && obj.title.trim() ? obj.title.trim() : undefined,
      });
    }
    return items.length > 0 ? items : null;
  } catch {
    return null;
  }
}

export function parseQuestionEntriesFromTool(tool: ToolTraceItem): AnsweredQuestionEntry[] | null {
  if (tool.name !== "ask_user_question" || tool.status !== "done" || !tool.output) return null;
  const idx = tool.argsText.indexOf("questions:");
  if (idx < 0) return null;
  const after = tool.argsText.slice(idx + "questions:".length).trim();
  const start = after.indexOf("[");
  if (start < 0) return null;
  let jsonStr = after.slice(start);
  try {
    let parsedQuestions: unknown;
    try {
      parsedQuestions = JSON.parse(jsonStr);
    } catch {
      const end = jsonStr.lastIndexOf("]");
      if (end < 0) return null;
      jsonStr = jsonStr.slice(0, end + 1);
      parsedQuestions = JSON.parse(jsonStr);
    }
    if (!Array.isArray(parsedQuestions) || parsedQuestions.length === 0) return null;
    const parsedOut = JSON.parse(tool.output) as { answers?: unknown };
    if (!parsedOut || !Array.isArray(parsedOut.answers)) return null;
    const answerById = new Map<string, Record<string, unknown>>();
    for (const a of parsedOut.answers) {
      if (a && typeof a === "object") {
        const rec = a as Record<string, unknown>;
        const id = String(rec.id ?? "").trim();
        if (id) answerById.set(id, rec);
      }
    }
    const entries: AnsweredQuestionEntry[] = [];
    for (const q of parsedQuestions) {
      if (!q || typeof q !== "object") continue;
      const rec = q as Record<string, unknown>;
      const id = String(rec.id ?? "").trim();
      const question = String(rec.question ?? "").trim();
      if (!id || !question) continue;
      const header =
        typeof rec.header === "string" && rec.header.trim() ? rec.header.trim() : undefined;
      const ans = answerById.get(id);
      if (!ans) {
        entries.push({ id, header, question, selected: [], skipped: true });
      } else {
        const selected = Array.isArray(ans.selected)
          ? ans.selected.map((v) => String(v).trim()).filter(Boolean)
          : [];
        const custom =
          typeof ans.custom === "string" && ans.custom.trim() ? ans.custom.trim() : undefined;
        entries.push({
          id,
          header,
          question,
          selected,
          custom,
          skipped: selected.length === 0 && !custom,
        });
      }
    }
    return entries.length > 0 ? entries : null;
  } catch {
    return null;
  }
}

export function mapToolDto(dto: HostToolTraceDto, fallbackId: string): ToolTraceItem {
  const rawArgs = dto.arguments ?? {};
  const argsLines = Object.entries(rawArgs)
    .map(([k, v]) => `${k}: ${v}`)
    .join("\n");
  const summary =
    rawArgs.description ??
    rawArgs.command ??
    rawArgs.path ??
    dto.name;
  return {
    id: dto.callId || fallbackId,
    name: dto.name,
    summary,
    rawArgs,
    argsText: argsLines,
    output: dto.error ? `${dto.output ?? ""}\n${dto.error}`.trim() : (dto.output ?? ""),
    status: dto.status === "failed" ? "failed" : dto.status === "running" ? "running" : "done",
    durationMs: dto.durationMs ?? 0,
  };
}

function artifactBlockForTool(tool: ToolTraceItem): TurnBlock | null {
  if (tool.name === "present" && tool.status === "done") {
    const items = parsePresentedItemsFromOutput(tool.output);
    if (items) return { key: `present-${tool.id}`, kind: "present", items };
  }
  if (tool.name === "ask_user_question" && tool.status === "done") {
    const entries = parseQuestionEntriesFromTool(tool);
    if (entries) return { key: `ask-${tool.id}`, kind: "answered_ask", entries };
  }
  return null;
}

export function mapHostMessageToTurn(
  dto: HostMessageDto,
  locale: "zh-CN" | "en" = "zh-CN",
): ChatTurn {
  const isUser = dto.role.toLowerCase() === "user";
  const notices: string[] = [];
  if (dto.notice && dto.notice.trim()) {
    notices.push(dto.notice.trim());
  }

  if (isUser) {
    const parsed = parseAttachmentBlock(dto.text);
    return {
      id: `m-${dto.id}-${dto.runId || "hist"}`,
      runId: dto.runId,
      role: "user",
      rawText: parsed.text,
      timestamp: formatClockTime(dto.finishedAt ?? dto.startedAt, locale),
      status: "done",
      failed: false,
      notices,
      attachments: parsed.attachments,
      blocks: [],
    };
  }

  const blocks: TurnBlock[] = [];
  const deferredTodoCards: TurnBlock[] = [];
  let activity: ActivityStep[] = [];
  const flushActivity = () => {
    if (activity.length === 0) return;
    blocks.push({
      key: `act-${activity[0].id}-${blocks.length}`,
      kind: "activity",
      steps: activity,
    });
    activity = [];
  };

  for (const st of dto.steps ?? []) {
    if (st.kind === "reasoning" && st.text) {
      activity.push({ id: `r-${st.id}`, kind: "thinking", text: st.text });
    } else if (st.kind === "tool" && st.tool) {
      const tool = mapToolDto(st.tool, `tl-${st.id}`);
      activity.push({ id: `t-${tool.id}`, kind: "tool", tool });
      const artifact = artifactBlockForTool(tool);
      if (artifact) {
        flushActivity();
        blocks.push(artifact);
      }
    } else if (st.kind === "text" && st.text && st.text.trim()) {
      flushActivity();
      blocks.push({ key: `txt-${st.id}-${blocks.length}`, kind: "text", text: st.text });
    } else if (st.kind === "notice" && st.text && st.text.trim()) {
      notices.push(st.text.trim());
    } else if (st.kind === "custom_card" && (st.payload || st.text)) {
      const cardBlock: TurnBlock = {
        key: `cc-${st.id}-${blocks.length + deferredTodoCards.length}`,
        kind: "custom_card",
        card: {
          id: `cc-${st.id}`,
          customType: st.customType || "card",
          payload: st.payload || "{}",
          text: st.text || "",
        },
      };
      if (st.customType === "todo_write") {
        deferredTodoCards.push(cardBlock);
      } else {
        flushActivity();
        blocks.push(cardBlock);
      }
    }
  }
  flushActivity();
  blocks.push(...deferredTodoCards);

  if (!blocks.some((b) => b.kind === "text") && dto.text && dto.text.trim()) {
    blocks.push({ key: `txt-top-${dto.id}`, kind: "text", text: dto.text });
  }
  if (
    dto.customType &&
    dto.customPayload &&
    !blocks.some((b) => b.kind === "custom_card" && b.card.customType === dto.customType)
  ) {
    blocks.push({
      key: `cc-top-${dto.id}`,
      kind: "custom_card",
      card: {
        id: `cc-top-${dto.id}`,
        customType: dto.customType,
        payload: dto.customPayload,
        text: dto.text || "",
      },
    });
  }

  const lowerStatus = dto.status.toLowerCase();
  const isStopped =
    lowerStatus === "stopped" ||
    lowerStatus === "aborted" ||
    lowerStatus === "cancelled" ||
    lowerStatus === "canceled" ||
    (Boolean(dto.error) && isAbortErrorMessage(dto.error));
  const isFailed = !isStopped && (lowerStatus === "failed" || Boolean(dto.error));
  const turnStatus: ChatTurn["status"] = isStopped
    ? "stopped"
    : isFailed
      ? "failed"
      : "done";
  const durationMs =
    dto.startedAt > 0 && dto.finishedAt && dto.finishedAt >= dto.startedAt
      ? dto.finishedAt - dto.startedAt
      : undefined;

  let activityCount = 0;
  for (const b of blocks) {
    if (b.kind === "activity") activityCount += 1;
  }
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    if (b.kind !== "activity") continue;
    let toolSum = 0;
    for (const s of b.steps) {
      if (s.kind === "tool" && s.tool.durationMs > 0) {
        toolSum += s.tool.durationMs;
      }
    }
    const blockDurationMs =
      toolSum > 0
        ? toolSum
        : activityCount === 1 && durationMs && durationMs > 0
          ? durationMs
          : undefined;
    if (blockDurationMs !== undefined) {
      blocks[i] = { ...b, durationMs: blockDurationMs };
    }
  }

  return {
    id: `m-${dto.id}-${dto.runId || "hist"}`,
    runId: dto.runId,
    role: "assistant",
    rawText: dto.text,
    timestamp: formatClockTime(dto.finishedAt ?? dto.startedAt, locale),
    durationMs,
    status: turnStatus,
    failed: isFailed,
    error: isFailed ? dto.error?.trim() || undefined : undefined,
    reminder: dto.reminder?.trim() || undefined,
    notices,
    summary: dto.summary
      ? {
          kind: dto.summary.kind,
          text: dto.summary.text,
          tokensBefore: dto.summary.tokensBefore ?? undefined,
        }
      : undefined,
    attachments: [],
    blocks,
  };
}

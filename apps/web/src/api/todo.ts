import type { ChatTurn, TurnBlock } from "./types";

export type TodoItemStatus = "pending" | "in_progress" | "completed";

export interface TodoItem {
  content: string;
  status: TodoItemStatus;
}

export function parseTodoPayload(payload?: string | null): TodoItem[] {
  if (!payload || !payload.trim()) return [];
  try {
    const parsed = JSON.parse(payload) as { todos?: unknown };
    if (!Array.isArray(parsed.todos)) return [];
    const items: TodoItem[] = [];
    for (const raw of parsed.todos) {
      if (!raw || typeof raw !== "object") continue;
      const obj = raw as { content?: unknown; status?: unknown };
      const content = typeof obj.content === "string" ? obj.content.trim() : "";
      if (!content) continue;
      const rawStatus = typeof obj.status === "string" ? obj.status : "pending";
      const status: TodoItemStatus =
        rawStatus === "completed" || rawStatus === "in_progress" ? rawStatus : "pending";
      items.push({ content, status });
    }
    return items;
  } catch {
    return [];
  }
}

function findTodoInBlocks(blocks: TurnBlock[]): TodoItem[] | null {
  for (let j = blocks.length - 1; j >= 0; j--) {
    const b = blocks[j];
    if (b.kind === "custom_card" && b.card.customType === "todo_write" && b.card.payload) {
      const parsed = parseTodoPayload(b.card.payload);
      if (parsed.length > 0) return parsed;
    }
  }
  return null;
}

export function extractLatestTodosFromTurns(
  turns: ChatTurn[],
  liveBlocks?: TurnBlock[],
): TodoItem[] {
  if (liveBlocks && liveBlocks.length > 0) {
    const liveTodos = findTodoInBlocks(liveBlocks);
    if (liveTodos) return liveTodos;
  }
  for (let i = turns.length - 1; i >= 0; i--) {
    const found = findTodoInBlocks(turns[i].blocks);
    if (found) return found;
  }
  return [];
}

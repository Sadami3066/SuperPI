import { create } from "zustand";
import { parsePresentedItemsFromOutput, parseQuestionEntriesFromTool } from "../api/mappers";
import type { ActivityStep, CustomCardItem, ToolTraceItem, TurnBlock } from "../api/types";

export interface LiveRunState {
  sessionId: string;
  runId: string;
  startedAt: number;
  finishedAt?: number;
  status?: "done" | "stopped" | "failed";
  activityText: string | null;
  retrying: boolean;
  streaming: boolean;
  failed: boolean;
  error?: string;
  blocks: TurnBlock[];
}

interface PendingDeltas {
  text: string;
  reasoning: string;
}

const pendingMap = new Map<string, PendingDeltas>();
let scheduledHandle: number | ReturnType<typeof setTimeout> | null = null;
let scheduledViaRaf = false;

function scheduleFrame(cb: () => void): void {
  if (scheduledHandle !== null) return;
  if (typeof globalThis.requestAnimationFrame === "function") {
    scheduledViaRaf = true;
    scheduledHandle = globalThis.requestAnimationFrame(() => {
      scheduledHandle = null;
      cb();
    });
  } else {
    scheduledViaRaf = false;
    scheduledHandle = setTimeout(() => {
      scheduledHandle = null;
      cb();
    }, 16);
  }
}

function cancelScheduledFrame(): void {
  if (scheduledHandle === null) return;
  if (scheduledViaRaf && typeof globalThis.cancelAnimationFrame === "function") {
    globalThis.cancelAnimationFrame(scheduledHandle as number);
  } else {
    clearTimeout(scheduledHandle as ReturnType<typeof setTimeout>);
  }
  scheduledHandle = null;
}

function makeDeltaKey(sessionId: string, runId: string): string {
  return `${sessionId}::${runId}`;
}

function appendReasoningToBlocks(blocks: TurnBlock[], delta: string): TurnBlock[] {
  const next = [...blocks];
  const last = next[next.length - 1];
  if (last && last.kind === "activity") {
    const steps = [...last.steps];
    const lastStep = steps[steps.length - 1];
    if (lastStep && lastStep.kind === "thinking") {
      steps[steps.length - 1] = { ...lastStep, text: lastStep.text + delta };
    } else {
      steps.push({ id: `r-${steps.length}`, kind: "thinking", text: delta });
    }
    next[next.length - 1] = { ...last, steps };
    return next;
  }
  next.push({
    key: `act-${next.length}`,
    kind: "activity",
    steps: [{ id: "r-0", kind: "thinking", text: delta }],
  });
  return next;
}

function appendTextToBlocks(blocks: TurnBlock[], delta: string): TurnBlock[] {
  const next = [...blocks];
  const last = next[next.length - 1];
  if (last && last.kind === "text") {
    next[next.length - 1] = { ...last, text: last.text + delta };
    return next;
  }
  next.push({
    key: `txt-${next.length}`,
    kind: "text",
    text: delta,
  });
  return next;
}

function createInitialRun(sessionId: string, runId: string): LiveRunState {
  return {
    sessionId,
    runId,
    startedAt: Date.now(),
    activityText: null,
    retrying: false,
    streaming: true,
    failed: false,
    blocks: [],
  };
}

function artifactBlockFromTool(tool: ToolTraceItem): TurnBlock | null {
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

export interface StreamStoreState {
  activeBySession: Record<string, LiveRunState>;
  beginRun: (sessionId: string, runId: string) => void;
  setActivity: (sessionId: string, runId: string, activity: string | null) => void;
  enqueueTextDelta: (sessionId: string, runId: string, delta: string) => void;
  enqueueReasoningDelta: (sessionId: string, runId: string, delta: string) => void;
  upsertToolStart: (
    sessionId: string,
    runId: string,
    callId: string,
    toolName: string,
    args?: Record<string, string> | null,
  ) => void;
  finishTool: (
    sessionId: string,
    runId: string,
    callId: string,
    toolName: string,
    output: string,
    isError: boolean,
    durationMs: number,
  ) => void;
  addCustomCard: (
    sessionId: string,
    runId: string,
    customType: string,
    payload: string,
    text: string,
  ) => void;
  finishRun: (sessionId: string, runId: string, status?: string | null, fallbackText?: string | null) => void;
  failRun: (sessionId: string, runId: string, message: string) => void;
  clearRun: (sessionId: string, expectedRunId?: string) => void;
  flushPending: () => void;
  resetAll: () => void;
}

function ensureTargetRun(
  activeBySession: Record<string, LiveRunState>,
  sessionId: string,
  runId: string,
): LiveRunState {
  const existing = activeBySession[sessionId];
  if (!existing) return createInitialRun(sessionId, runId);
  if (existing.runId.startsWith("pending-") && !runId.startsWith("pending-")) {
    return { ...existing, runId };
  }
  return existing;
}

export const useStreamStore = create<StreamStoreState>((set, get) => ({
  activeBySession: {},

  beginRun: (sessionId, runId) => {
    const current = get().activeBySession[sessionId];
    if (current && current.runId.startsWith("pending-") && !runId.startsWith("pending-")) {
      set((s) => ({
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: { ...current, runId, streaming: true, failed: false, error: undefined },
        },
      }));
      return;
    }
    if (current && current.runId === runId) return;
    set((s) => ({
      activeBySession: {
        ...s.activeBySession,
        [sessionId]: createInitialRun(sessionId, runId),
      },
    }));
  },

  setActivity: (sessionId, runId, activity) => {
    set((s) => {
      const base = ensureTargetRun(s.activeBySession, sessionId, runId);
      if (runId && base.runId !== runId && !base.runId.startsWith("pending-")) return s;
      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: {
            ...base,
            activityText: activity,
            retrying: Boolean(activity?.startsWith("retrying:")),
          },
        },
      };
    });
  },

  enqueueTextDelta: (sessionId, runId, delta) => {
    if (!delta) return;
    const key = makeDeltaKey(sessionId, runId || "live");
    const entry = pendingMap.get(key) ?? { text: "", reasoning: "" };
    entry.text += delta;
    pendingMap.set(key, entry);
    scheduleFrame(() => get().flushPending());
  },

  enqueueReasoningDelta: (sessionId, runId, delta) => {
    if (!delta) return;
    const key = makeDeltaKey(sessionId, runId || "live");
    const entry = pendingMap.get(key) ?? { text: "", reasoning: "" };
    entry.reasoning += delta;
    pendingMap.set(key, entry);
    scheduleFrame(() => get().flushPending());
  },

  flushPending: () => {
    if (pendingMap.size === 0) return;
    const snapshot = Array.from(pendingMap.entries());
    pendingMap.clear();

    set((s) => {
      const nextMap = { ...s.activeBySession };
      let changed = false;

      for (const [key, deltas] of snapshot) {
        const sep = key.indexOf("::");
        if (sep < 0) continue;
        const sessionId = key.slice(0, sep);
        const runId = key.slice(sep + 2);
        const cur = ensureTargetRun(nextMap, sessionId, runId);
        if (runId !== "live" && cur.runId !== runId) continue;

        let blocks = cur.blocks;
        if (deltas.reasoning) {
          blocks = appendReasoningToBlocks(blocks, deltas.reasoning);
        }
        if (deltas.text) {
          blocks = appendTextToBlocks(blocks, deltas.text);
        }
        nextMap[sessionId] = { ...cur, blocks };
        changed = true;
      }

      return changed ? { activeBySession: nextMap } : s;
    });
  },

  upsertToolStart: (sessionId, runId, callId, toolName, args) => {
    get().flushPending();
    set((s) => {
      const cur = ensureTargetRun(s.activeBySession, sessionId, runId);
      if (runId && cur.runId !== runId) return s;

      const rawArgs = args ?? {};
      const argsLines = Object.entries(rawArgs)
        .map(([k, v]) => `${k}: ${v}`)
        .join("\n");
      const summary = rawArgs.description ?? rawArgs.command ?? rawArgs.path ?? toolName;
      const trace: ToolTraceItem = {
        id: callId,
        name: toolName,
        summary,
        rawArgs,
        argsText: argsLines,
        output: "",
        status: "running",
        durationMs: 0,
      };

      const blocks = [...cur.blocks];
      let found = false;
      for (let i = blocks.length - 1; i >= 0; i--) {
        const blk = blocks[i];
        if (blk.kind !== "activity") continue;
        const idx = blk.steps.findIndex((st) => st.kind === "tool" && st.tool.id === callId);
        if (idx >= 0) {
          const steps = [...blk.steps];
          steps[idx] = { id: `t-${callId}`, kind: "tool", tool: trace };
          blocks[i] = { ...blk, steps };
          found = true;
          break;
        }
      }

      if (!found) {
        const last = blocks[blocks.length - 1];
        if (last && last.kind === "activity") {
          blocks[blocks.length - 1] = {
            ...last,
            steps: [...last.steps, { id: `t-${callId}`, kind: "tool", tool: trace }],
          };
        } else {
          blocks.push({
            key: `act-${blocks.length}`,
            kind: "activity",
            steps: [{ id: `t-${callId}`, kind: "tool", tool: trace }],
          });
        }
      }

      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: { ...cur, blocks },
        },
      };
    });
  },

  finishTool: (sessionId, runId, callId, toolName, output, isError, durationMs) => {
    get().flushPending();
    set((s) => {
      const cur = ensureTargetRun(s.activeBySession, sessionId, runId);
      if (runId && cur.runId !== runId) return s;

      let updatedTool: ToolTraceItem | null = null;
      const blocks = cur.blocks.map((blk) => {
        if (blk.kind !== "activity") return blk;
        const idx = blk.steps.findIndex((st) => st.kind === "tool" && st.tool.id === callId);
        if (idx < 0) return blk;
        const existing = blk.steps[idx];
        if (existing.kind !== "tool") return blk;
        updatedTool = {
          ...existing.tool,
          name: toolName || existing.tool.name,
          status: isError ? "failed" : "done",
          output,
          durationMs,
        };
        const steps = [...blk.steps];
        steps[idx] = { ...existing, tool: updatedTool };
        return { ...blk, steps };
      });

      if (updatedTool) {
        const artifact = artifactBlockFromTool(updatedTool);
        if (artifact) {
          blocks.push(artifact);
        }
      }

      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: { ...cur, blocks },
        },
      };
    });
  },

  addCustomCard: (sessionId, runId, customType, payload, text) => {
    get().flushPending();
    set((s) => {
      const cur = ensureTargetRun(s.activeBySession, sessionId, runId);
      if (runId && cur.runId !== runId) return s;
      const card: CustomCardItem = {
        id: `cc-live-${cur.blocks.length}`,
        customType,
        payload,
        text,
      };
      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: {
            ...cur,
            blocks: [...cur.blocks, { key: `cc-${cur.blocks.length}`, kind: "custom_card", card }],
          },
        },
      };
    });
  },

  finishRun: (sessionId, runId, status, fallbackText) => {
    get().flushPending();
    set((s) => {
      const cur = s.activeBySession[sessionId];
      if (!cur) return s;
      if (runId && cur.runId !== runId && !cur.runId.startsWith("pending-")) return s;
      let blocks = cur.blocks;
      if (fallbackText && !blocks.some((b) => b.kind === "text")) {
        blocks = appendTextToBlocks(blocks, fallbackText);
      }
      const lowerStatus = (status ?? "").toLowerCase();
      const resolvedStatus: LiveRunState["status"] =
        lowerStatus === "failed"
          ? "failed"
          : lowerStatus === "stopped" || lowerStatus === "aborted" || lowerStatus === "cancelled"
            ? "stopped"
            : "done";
      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: {
            ...cur,
            finishedAt: Date.now(),
            status: resolvedStatus,
            streaming: false,
            retrying: false,
            activityText: null,
            failed: resolvedStatus === "failed",
            blocks,
          },
        },
      };
    });
  },

  failRun: (sessionId, runId, message) => {
    get().flushPending();
    set((s) => {
      const cur = ensureTargetRun(s.activeBySession, sessionId, runId);
      if (runId && cur.runId !== runId && !cur.runId.startsWith("pending-")) return s;
      const blocks = cur.blocks.map((blk) => {
        if (blk.kind !== "activity") return blk;
        const steps: ActivityStep[] = blk.steps.map((st) =>
          st.kind === "tool" && st.tool.status === "running"
            ? { ...st, tool: { ...st.tool, status: "failed" } }
            : st,
        );
        return { ...blk, steps };
      });
      return {
        activeBySession: {
          ...s.activeBySession,
          [sessionId]: {
            ...cur,
            finishedAt: Date.now(),
            status: "failed",
            streaming: false,
            retrying: false,
            activityText: null,
            failed: true,
            error: message,
            blocks,
          },
        },
      };
    });
  },

  clearRun: (sessionId, expectedRunId) => {
    set((s) => {
      const cur = s.activeBySession[sessionId];
      if (!cur) return s;
      if (expectedRunId && cur.runId !== expectedRunId && !cur.runId.startsWith("pending-")) {
        return s;
      }
      const next = { ...s.activeBySession };
      delete next[sessionId];
      return { activeBySession: next };
    });
  },

  resetAll: () => {
    cancelScheduledFrame();
    pendingMap.clear();
    set({ activeBySession: {} });
  },
}));

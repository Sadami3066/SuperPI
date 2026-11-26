export const STREAM_COALESCE_INTERVAL_MS = 32;
export const STREAM_FADE_DURATION_MS = 320;
export const STREAM_STAGGER_INTERVAL_MS = 14;
export const STREAM_STAGGER_MAX_DELAY_MS = 160;
export const STREAM_ALPHA_LEVELS = 32;
export const STREAM_MAX_BATCHES = 24;
export const STREAM_MAX_UNITS_PER_BATCH = 48;

const RESTYLE_SKIP_LIMIT = 256;
const RESTYLE_LENGTH_LIMIT = 4096;
const RESTYLE_MINIMUM_RUN = 3;
const MARKDOWN_LEADING_SYNTAX = new Set(["*", "_", "~", "`", "["]);
const MARKDOWN_BOUNDARY_SYNTAX = new Set(["*", "_", "~", "`", "]", "("]);

function isHighSurrogate(ch: string): boolean {
  const code = ch.charCodeAt(0);
  return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(ch: string): boolean {
  const code = ch.charCodeAt(0);
  return code >= 0xdc00 && code <= 0xdfff;
}

function fenceMarker(trimmedLine: string): { ch: string; len: number } | null {
  const first = trimmedLine[0];
  if (first !== "`" && first !== "~") return null;
  let len = 0;
  while (len < trimmedLine.length && trimmedLine[len] === first) len++;
  if (len < 3) return null;
  if (first === "`" && trimmedLine.slice(len).includes("`")) return null;
  return { ch: first, len };
}

export function splitMarkdownBlocks(source: string, streaming = false): string[] {
  if (!source) return [];
  const normalized = source.replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  const blocks: string[] = [];
  let start = 0;
  let openFence: { ch: string; len: number } | null = null;

  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trimStart();
    if (openFence) {
      const m = fenceMarker(trimmed);
      if (m && m.ch === openFence.ch && m.len >= openFence.len && trimmed.slice(m.len).trim() === "") {
        openFence = null;
      }
      continue;
    }

    const opened = fenceMarker(trimmed);
    if (opened) {
      openFence = opened;
      continue;
    }

    if (lines[i].trim() === "") {
      const nextLine = i + 1 < lines.length ? lines[i + 1] : "";
      const isIndentedContinuation = /^(?: {2,}|\t)/.test(nextLine);
      const isOrderedListItem = /^\s*\d+[.)]\s+/.test(nextLine);
      if (isIndentedContinuation || isOrderedListItem) {
        continue;
      }
      const chunk = lines.slice(start, i).join("\n");
      if (chunk.trim()) blocks.push(chunk);
      start = i + 1;
    }
  }

  const tail = lines.slice(start).join("\n");
  if (tail.trim()) {
    if (streaming && openFence) {
      blocks.push(`${tail}\n${openFence.ch.repeat(openFence.len)}`);
    } else {
      blocks.push(tail);
    }
  }
  return blocks;
}

export interface StreamTextChange {
  previousLength: number;
  currentLength: number;
  commonPrefixLength: number;
  commonSuffixLength: number;
  removedStart: number;
  removedEnd: number;
  removedLength: number;
  insertedStart: number;
  insertedEnd: number;
  insertedLength: number;
  hasRemoval: boolean;
  hasInsertion: boolean;
  isTextUnchanged: boolean;
}

export function computeStreamTextChange(previous: string, current: string): StreamTextChange {
  const minLen = Math.min(previous.length, current.length);
  let prefix = 0;
  while (prefix < minLen && previous[prefix] === current[prefix]) {
    prefix++;
  }
  if (prefix > 0 && prefix < minLen && isHighSurrogate(previous[prefix - 1])) {
    prefix--;
  }
  const maxSuffix = minLen - prefix;
  let suffix = 0;
  while (
    suffix < maxSuffix &&
    previous[previous.length - 1 - suffix] === current[current.length - 1 - suffix]
  ) {
    suffix++;
  }
  if (suffix > 0 && suffix < maxSuffix && isLowSurrogate(previous[previous.length - suffix])) {
    suffix--;
  }
  const removedStart = prefix;
  const removedEnd = previous.length - suffix;
  const removedLength = Math.max(0, removedEnd - removedStart);
  const insertedStart = prefix;
  const insertedEnd = current.length - suffix;
  const insertedLength = Math.max(0, insertedEnd - insertedStart);
  return {
    previousLength: previous.length,
    currentLength: current.length,
    commonPrefixLength: prefix,
    commonSuffixLength: suffix,
    removedStart,
    removedEnd,
    removedLength,
    insertedStart,
    insertedEnd,
    insertedLength,
    hasRemoval: removedLength > 0,
    hasInsertion: insertedLength > 0,
    isTextUnchanged: removedLength === 0 && insertedLength === 0,
  };
}

interface StreamOffsetPair {
  newOffset: number;
  previousOffset: number;
}

class StreamRestyleMatch {
  readonly pairs: StreamOffsetPair[];
  readonly freshStart: number;

  private constructor(pairs: StreamOffsetPair[], freshStart: number) {
    this.pairs = pairs;
    this.freshStart = freshStart;
  }

  newOffset(atOrAfter: number, before: number): number | null {
    let low = 0;
    let high = this.pairs.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (this.pairs[mid].previousOffset < atOrAfter) low = mid + 1;
      else high = mid;
    }
    return low < this.pairs.length && this.pairs[low].previousOffset < before
      ? this.pairs[low].newOffset
      : null;
  }

  newEnd(before: number): number | null {
    let low = 0;
    let high = this.pairs.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (this.pairs[mid].previousOffset < before) low = mid + 1;
      else high = mid;
    }
    return low > 0 ? this.pairs[low - 1].newOffset + 1 : null;
  }

  static compute(change: StreamTextChange, previous: string, current: string): StreamRestyleMatch {
    const { insertedStart, insertedLength, removedStart, removedLength } = change;
    if (insertedLength <= 0 || removedLength <= 0 || removedLength > RESTYLE_LENGTH_LIMIT) {
      return new StreamRestyleMatch([], insertedStart);
    }
    const matchedPairs =
      matchDelimiterStrip(previous, removedStart, removedLength, current, insertedStart, insertedLength) ??
      matchRuns(previous, removedStart, removedLength, current, insertedStart, insertedLength);

    if (matchedPairs.length === 0) {
      return new StreamRestyleMatch([], insertedStart);
    }
    let start = matchedPairs[matchedPairs.length - 1].newOffset + 1;
    if (start > 0 && start < current.length && isLowSurrogate(current[start])) {
      start++;
    }
    return new StreamRestyleMatch(matchedPairs, Math.max(start, insertedStart));
  }
}

function matchDelimiterStrip(
  previous: string,
  removedStart: number,
  removedLen: number,
  current: string,
  insertedStart: number,
  insertedLen: number,
): StreamOffsetPair[] | null {
  let stripCount = 0;
  while (stripCount < removedLen && MARKDOWN_LEADING_SYNTAX.has(previous[removedStart + stripCount])) {
    stripCount++;
  }
  if (stripCount === 0 || stripCount >= removedLen) return null;
  const remainingOld = removedLen - stripCount;
  const limit = Math.min(remainingOld, insertedLen);
  let matched = 0;
  while (
    matched < limit &&
    previous[removedStart + stripCount + matched] === current[insertedStart + matched]
  ) {
    matched++;
  }
  if (matched === 0) return null;
  const closedAtBoundary =
    stripCount + matched === removedLen ||
    MARKDOWN_BOUNDARY_SYNTAX.has(previous[removedStart + stripCount + matched]);
  if (!closedAtBoundary && matched < RESTYLE_MINIMUM_RUN) return null;

  const pairs: StreamOffsetPair[] = [];
  for (let i = 0; i < matched; i++) {
    pairs.push({ newOffset: insertedStart + i, previousOffset: removedStart + stripCount + i });
  }
  return pairs;
}

function matchRuns(
  previous: string,
  removedStart: number,
  removedLen: number,
  current: string,
  insertedStart: number,
  insertedLen: number,
): StreamOffsetPair[] {
  const checkLen = Math.min(insertedLen, removedLen + RESTYLE_LENGTH_LIMIT);
  const minRun = Math.min(RESTYLE_MINIMUM_RUN, removedLen);
  const accepted: StreamOffsetPair[] = [];
  let run: StreamOffsetPair[] = [];

  const closeRun = () => {
    if (run.length >= minRun) accepted.push(...run);
    run = [];
  };

  let oldIndex = 0;
  let newIndex = 0;
  while (newIndex < checkLen && oldIndex < removedLen) {
    const end = Math.min(oldIndex + RESTYLE_SKIP_LIMIT, removedLen);
    let found = oldIndex;
    while (found < end && previous[removedStart + found] !== current[insertedStart + newIndex]) {
      found++;
    }
    if (found < end) {
      const last = run[run.length - 1];
      if (
        last &&
        (last.newOffset !== insertedStart + newIndex - 1 ||
          last.previousOffset !== removedStart + found - 1)
      ) {
        closeRun();
      }
      run.push({ newOffset: insertedStart + newIndex, previousOffset: removedStart + found });
      oldIndex = found + 1;
    } else {
      closeRun();
    }
    newIndex++;
  }
  closeRun();
  return accepted;
}

interface StreamFadeBatch {
  start: number;
  end: number;
  unitStarts: number[];
  startTimeMs: number;
  intervalMs: number;
  durationMs: number;
  levelSum: number;
}

function easeOutCubic(t: number): number {
  const inv = 1 - t;
  return 1 - inv * inv * inv;
}

function lastIndexNotAfter(values: number[], value: number): number {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (values[mid] <= value) low = mid + 1;
    else high = mid;
  }
  return Math.max(low - 1, 0);
}

const graphemeSegmenter =
  typeof Intl !== "undefined" && typeof Intl.Segmenter === "function"
    ? new Intl.Segmenter(undefined, { granularity: "grapheme" })
    : null;

function clusterStarts(text: string, start: number, end: number, maxCount: number): number[] {
  if (start >= end) return [];
  const slice = text.slice(start, end);
  const raw: number[] = [];
  if (graphemeSegmenter) {
    for (const seg of graphemeSegmenter.segment(slice)) {
      raw.push(start + seg.index);
    }
  } else {
    let i = 0;
    while (i < slice.length) {
      raw.push(start + i);
      const cp = slice.codePointAt(i) ?? 0;
      i += cp > 0xffff ? 2 : 1;
    }
  }
  if (raw.length === 0) raw.push(start);
  const cap = Math.max(1, maxCount);
  if (raw.length <= cap) return raw;
  const step = Math.ceil(raw.length / cap);
  const grouped: number[] = [];
  for (let i = 0; i < raw.length; i += step) {
    grouped.push(raw[i]);
  }
  return grouped;
}

export class StreamTextFadeAnimator {
  private previousText = "";
  private initialized = false;
  private wasLive = false;
  private batches: StreamFadeBatch[] = [];

  get hasActiveBatches(): boolean {
    return this.batches.length > 0;
  }

  get activeBatchCount(): number {
    return this.batches.length;
  }

  finish(): void {
    this.batches = [];
  }

  sync(currentText: string, live: boolean, animateOnFirstFill: boolean, nowMs: number): boolean {
    if (!this.initialized) {
      this.initialized = true;
      this.wasLive = live;
      if (live && animateOnFirstFill && currentText.length > 0) {
        this.previousText = "";
        this.applyTextChange("", currentText, nowMs);
        this.previousText = currentText;
        return true;
      }
      this.previousText = currentText;
      this.batches = [];
      return false;
    }
    if (currentText === this.previousText) {
      this.wasLive = live;
      return false;
    }
    const shouldAnimate = live || this.wasLive;
    this.wasLive = live;
    if (!shouldAnimate) {
      this.previousText = currentText;
      this.batches = [];
      return true;
    }
    this.applyTextChange(this.previousText, currentText, nowMs);
    this.previousText = currentText;
    return true;
  }

  advance(nowMs: number): boolean {
    if (this.batches.length === 0) return false;
    let changed = false;
    let index = 0;
    while (index < this.batches.length) {
      const batch = this.batches[index];
      const sum = this.levelSum(batch, nowMs);
      if (sum !== batch.levelSum) {
        batch.levelSum = sum;
        changed = true;
      }
      if (sum === STREAM_ALPHA_LEVELS * batch.unitStarts.length) {
        this.batches.splice(index, 1);
        changed = true;
      } else {
        index++;
      }
    }
    return changed;
  }

  levelAt(offset: number, nowMs: number): number {
    const batch = this.batchContaining(offset);
    if (!batch) return STREAM_ALPHA_LEVELS;
    const unitIndex = lastIndexNotAfter(batch.unitStarts, offset);
    return this.levelOfUnit(batch, unitIndex, nowMs);
  }

  alphaAt(offset: number, nowMs: number): number {
    const level = this.levelAt(offset, nowMs);
    return level >= STREAM_ALPHA_LEVELS ? 1 : level / STREAM_ALPHA_LEVELS;
  }

  private applyTextChange(previous: string, current: string, nowMs: number): void {
    const change = computeStreamTextChange(previous, current);
    if (change.isTextUnchanged) return;
    const restyle =
      change.hasInsertion && change.hasRemoval
        ? StreamRestyleMatch.compute(change, previous, current)
        : null;
    this.remapBatches(change, restyle);
    const freshStart = restyle ? restyle.freshStart : change.insertedStart;
    if (change.insertedEnd > freshStart) {
      this.addBatch(freshStart, change.insertedEnd, current, nowMs);
    }
  }

  private remapBatches(change: StreamTextChange, restyle: StreamRestyleMatch | null): void {
    if (this.batches.length === 0) return;
    const prefix = change.commonPrefixLength;
    const suffixStart = change.previousLength - change.commonSuffixLength;
    const delta = change.currentLength - change.previousLength;
    const remapped: StreamFadeBatch[] = [];

    for (const batch of this.batches) {
      if (batch.end <= prefix) {
        remapped.push(batch);
        continue;
      }
      if (batch.start >= suffixStart) {
        remapped.push({
          ...batch,
          start: batch.start + delta,
          end: batch.end + delta,
          unitStarts: batch.unitStarts.map((u) => u + delta),
        });
        continue;
      }
      if (batch.start < prefix) {
        const headUnits = batch.unitStarts.filter((u) => u < prefix);
        if (headUnits.length > 0) {
          remapped.push({
            ...batch,
            end: prefix,
            unitStarts: headUnits,
            levelSum: -1,
          });
        }
      }
      if (restyle) {
        const carried = this.carryBatch(batch, prefix, restyle);
        if (carried) remapped.push(carried);
      }
    }
    this.batches = remapped;
  }

  private carryBatch(
    batch: StreamFadeBatch,
    fromOffset: number,
    restyle: StreamRestyleMatch,
  ): StreamFadeBatch | null {
    const from = Math.max(fromOffset, batch.start);
    const location = restyle.newOffset(from, batch.end);
    const newEnd = restyle.newEnd(batch.end);
    if (location === null || newEnd === null || newEnd <= location) return null;

    let firstUnit = -1;
    const starts: number[] = [];
    for (let u = 0; u < batch.unitStarts.length; u++) {
      const unitStart = batch.unitStarts[u];
      const unitEnd = u + 1 < batch.unitStarts.length ? batch.unitStarts[u + 1] : batch.end;
      if (unitEnd <= from) continue;
      const mapped = restyle.newOffset(Math.max(unitStart, from), unitEnd);
      if (mapped === null) continue;
      if (firstUnit < 0) firstUnit = u;
      if (starts.length === 0 || starts[starts.length - 1] < mapped) {
        starts.push(mapped);
      }
    }
    if (firstUnit < 0 || starts.length === 0) return null;
    starts[0] = location;
    return {
      start: location,
      end: newEnd,
      unitStarts: starts,
      startTimeMs: batch.startTimeMs + firstUnit * batch.intervalMs,
      intervalMs: batch.intervalMs,
      durationMs: batch.durationMs,
      levelSum: -1,
    };
  }

  private addBatch(start: number, end: number, currentText: string, nowMs: number): void {
    const starts = clusterStarts(currentText, start, end, STREAM_MAX_UNITS_PER_BATCH);
    if (starts.length === 0) return;

    let interval =
      starts.length <= 1
        ? 0
        : Math.min(STREAM_STAGGER_INTERVAL_MS, STREAM_STAGGER_MAX_DELAY_MS / (starts.length - 1));
    let position = this.batches.findIndex((b) => b.start > start);
    if (position < 0) position = this.batches.length;

    let startTime = nowMs;
    if (position > 0) {
      const prev = this.batches[position - 1];
      const prevLast = prev.startTimeMs + (prev.unitStarts.length - 1) * prev.intervalMs;
      startTime = Math.max(startTime, prevLast + Math.max(interval, prev.intervalMs));
      if (starts.length > 1) {
        const latest = nowMs + STREAM_STAGGER_MAX_DELAY_MS;
        const room = Math.max(0, latest - startTime);
        interval = Math.min(interval, room / (starts.length - 1));
      }
    }

    this.batches.splice(position, 0, {
      start,
      end,
      unitStarts: starts,
      startTimeMs: startTime,
      intervalMs: interval,
      durationMs: STREAM_FADE_DURATION_MS,
      levelSum: -1,
    });

    while (this.batches.length > STREAM_MAX_BATCHES) {
      let oldestIdx = 0;
      for (let i = 1; i < this.batches.length; i++) {
        if (this.batches[i].startTimeMs < this.batches[oldestIdx].startTimeMs) {
          oldestIdx = i;
        }
      }
      this.batches.splice(oldestIdx, 1);
    }
  }

  private levelOfUnit(batch: StreamFadeBatch, unitIndex: number, nowMs: number): number {
    const elapsed = nowMs - batch.startTimeMs - unitIndex * batch.intervalMs;
    if (elapsed <= 0) return 0;
    if (elapsed >= batch.durationMs) return STREAM_ALPHA_LEVELS;
    const progress = Math.min(1, Math.max(0, elapsed / batch.durationMs));
    const eased = easeOutCubic(progress);
    if (eased <= 0) return 0;
    if (eased >= 1) return STREAM_ALPHA_LEVELS;
    return Math.min(STREAM_ALPHA_LEVELS, Math.max(0, Math.round(eased * STREAM_ALPHA_LEVELS)));
  }

  private levelSum(batch: StreamFadeBatch, nowMs: number): number {
    let sum = 0;
    for (let u = 0; u < batch.unitStarts.length; u++) {
      sum += this.levelOfUnit(batch, u, nowMs);
    }
    return sum;
  }

  private batchContaining(offset: number): StreamFadeBatch | null {
    let low = 0;
    let high = this.batches.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (this.batches[mid].end <= offset) low = mid + 1;
      else high = mid;
    }
    if (low >= this.batches.length) return null;
    const batch = this.batches[low];
    return offset >= batch.start && offset < batch.end ? batch : null;
  }
}

export interface AlphaTextRun {
  text: string;
  alpha: number;
}

export function splitTextByAlpha(
  text: string,
  baseOffset: number,
  animator: StreamTextFadeAnimator,
  nowMs: number,
): AlphaTextRun[] {
  if (!text) return [];
  if (!animator.hasActiveBatches) return [{ text, alpha: 1 }];

  const runs: AlphaTextRun[] = [];
  let cursor = 0;
  let currentLevel = animator.levelAt(baseOffset, nowMs);

  for (let i = 1; i < text.length; i++) {
    const nextLevel = animator.levelAt(baseOffset + i, nowMs);
    if (nextLevel !== currentLevel) {
      runs.push({
        text: text.slice(cursor, i),
        alpha: currentLevel >= STREAM_ALPHA_LEVELS ? 1 : currentLevel / STREAM_ALPHA_LEVELS,
      });
      cursor = i;
      currentLevel = nextLevel;
    }
  }
  runs.push({
    text: text.slice(cursor),
    alpha: currentLevel >= STREAM_ALPHA_LEVELS ? 1 : currentLevel / STREAM_ALPHA_LEVELS,
  });
  return runs;
}

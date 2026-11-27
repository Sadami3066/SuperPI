const seenQuestionIds = new Set<string>();
const seenApprovalIds = new Set<string>();
const seenInboxIds = new Set<string>();

const USER_ABORT_RE =
  /\b(abort(ed)?|cancel(led|ed)?|interrupted|stopped by user|user_aborted)\b|已取消|已停止|用户中断/i;

export function isUserAbortedError(text?: string | null): boolean {
  const clean = text?.trim() || "";
  if (!clean) return false;
  return USER_ABORT_RE.test(clean);
}

export function resetNotificationBaselines(): void {
  seenQuestionIds.clear();
  seenApprovalIds.clear();
  seenInboxIds.clear();
}

export function seedNotificationBaselines(params: {
  questionIds?: readonly string[];
  approvalIds?: readonly string[];
  inboxIds?: readonly string[];
}): void {
  if (params.questionIds) {
    seenQuestionIds.clear();
    for (const id of params.questionIds) seenQuestionIds.add(id);
  }
  if (params.approvalIds) {
    seenApprovalIds.clear();
    for (const id of params.approvalIds) seenApprovalIds.add(id);
  }
  if (params.inboxIds) {
    seenInboxIds.clear();
    for (const id of params.inboxIds) seenInboxIds.add(id);
  }
}

export function collectNewNotificationItems<T>(
  kind: "question" | "approval" | "inbox",
  items: readonly T[],
  getId: (item: T) => string,
): T[] {
  const set =
    kind === "question"
      ? seenQuestionIds
      : kind === "approval"
        ? seenApprovalIds
        : seenInboxIds;
  const fresh: T[] = [];
  const nextIds = new Set<string>();
  for (const item of items) {
    const id = getId(item);
    if (!id) continue;
    nextIds.add(id);
    if (!set.has(id)) {
      fresh.push(item);
    }
  }
  set.clear();
  for (const id of nextIds) set.add(id);
  return fresh;
}

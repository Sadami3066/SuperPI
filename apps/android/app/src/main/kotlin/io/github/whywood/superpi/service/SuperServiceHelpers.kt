package io.github.whywood.superpi.service

import io.github.whywood.superpi.conversation.ActiveRunView
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperThreadState
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.isActive

internal fun formatSuperWorkerTitle(botName: String, workerTitle: String): String {
    val bot = botName.trim()
    val title = workerTitle.trim()
    return when {
        bot.isNotEmpty() && title.isNotEmpty() -> "$bot · $title"
        bot.isNotEmpty() -> bot
        title.isNotEmpty() -> title
        else -> NotificationCenter.SUPER_DISPLAY_TITLE
    }
}

internal fun buildSuperActiveRuns(
    thread: SuperThreadState,
    workers: List<WorkerRunRecord>,
    nowMs: () -> Long = System::currentTimeMillis,
): Map<String, ActiveRunView> {
    val map = LinkedHashMap<String, ActiveRunView>()
    if (thread.busy) {
        val toolName = thread.activeToolText?.takeIf { it.isNotBlank() }
        val activity = if (toolName != null) RuntimeActivity.RunningTool(toolName) else RuntimeActivity.Thinking
        val startedAt = thread.messages.lastOrNull()?.startedAt?.takeIf { it > 0L } ?: nowMs()
        map[SuperRuntime.SUPER_THREAD_SESSION_ID] = ActiveRunView(
            sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
            title = NotificationCenter.SUPER_DISPLAY_TITLE,
            activity = activity,
            startedAt = startedAt,
        )
    }
    workers.filter { it.status.isActive }.forEach { worker ->
        val targetId = NotificationCenter.workerSessionTarget(worker.workerId)
        val step = worker.activeStepText?.takeIf { it.isNotBlank() }
        val activity = if (step != null) RuntimeActivity.RunningTool(step) else RuntimeActivity.Thinking
        map[targetId] = ActiveRunView(
            sessionId = targetId,
            title = formatSuperWorkerTitle(worker.botName, worker.title),
            activity = activity,
            startedAt = worker.updatedAt.takeIf { it > 0L } ?: worker.createdAt,
        )
    }
    return map
}

internal fun buildSuperPendingQuestions(
    thread: SuperThreadState,
    workers: List<WorkerRunRecord>,
): List<PendingQuestion> {
    val superQ = thread.pendingQuestion?.copy(sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID)
    val workerQs = workers.mapNotNull { worker ->
        worker.pendingQuestion?.copy(sessionId = NotificationCenter.workerSessionTarget(worker.workerId))
    }
    return listOfNotNull(superQ) + workerQs
}

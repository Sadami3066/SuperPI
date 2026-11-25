package io.github.whywood.superpi.ui.common.chat

import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ToolActivityStatus

internal fun reasoningPreview(text: String): String {
    val paragraph = text.split(Regex("\\n[ \\t]*\\n"))
        .asReversed()
        .firstOrNull { it.isNotBlank() }
        ?: return ""
    val line = paragraph.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
    return cleanPreviewLine(line)
}

private fun cleanPreviewLine(line: String): String = line.trim()
    .replace(Regex("^#{1,6}\\s+"), "")
    .replace(Regex("^>\\s?"), "")
    .replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
    .replace("**", "")
    .replace("__", "")
    .replace("`", "")
    .replace(Regex("\\s+"), " ")
    .trim()

internal enum class ProcessActivity { Thinking, CallingTool, Replying }

internal sealed interface LiveProcessPhase {
    data class Thinking(val reasoning: AssistantStep.Reasoning? = null) : LiveProcessPhase

    data class CallingTool(val tool: AssistantStep.Tool) : LiveProcessPhase

    data object Replying : LiveProcessPhase
}

internal fun liveProcessPhase(steps: List<AssistantStep>, streaming: Boolean): LiveProcessPhase? {
    if (!streaming) return null
    val runningTool = steps
        .filterIsInstance<AssistantStep.Tool>()
        .lastOrNull { it.trace.status == ToolActivityStatus.RUNNING }
    if (runningTool != null) return LiveProcessPhase.CallingTool(runningTool)
    return when (val last = steps.lastOrNull()) {
        is AssistantStep.Text -> if (last.text.isNotBlank()) LiveProcessPhase.Replying else LiveProcessPhase.Thinking()
        is AssistantStep.Reasoning -> LiveProcessPhase.Thinking(reasoning = last)
        else -> LiveProcessPhase.Thinking()
    }
}

internal fun processActivity(steps: List<AssistantStep>, streaming: Boolean): ProcessActivity? =
    when (liveProcessPhase(steps, streaming)) {
        is LiveProcessPhase.Thinking -> ProcessActivity.Thinking
        is LiveProcessPhase.CallingTool -> ProcessActivity.CallingTool
        LiveProcessPhase.Replying -> ProcessActivity.Replying
        null -> null
    }

internal fun terminatedReasoningId(steps: List<AssistantStep>, terminated: Boolean): Long? =
    if (terminated) (steps.lastOrNull() as? AssistantStep.Reasoning)?.id else null

internal enum class ProcessStatus { Running, Done, Stopped, Failed }

internal fun processStatus(streaming: Boolean, status: MessageStatus): ProcessStatus = when {
    streaming -> ProcessStatus.Running
    status == MessageStatus.STOPPED -> ProcessStatus.Stopped
    status == MessageStatus.FAILED -> ProcessStatus.Failed
    else -> ProcessStatus.Done
}

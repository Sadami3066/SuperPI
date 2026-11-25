package io.github.whywood.superpi.ui.common.chat

import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSemanticsTest {
    @Test
    fun reasoningPreviewUsesLastParagraphFirstLine() {
        val text = "先想一下 **重点**\n第二行\n\n## 结论\n- 细节"
        assertEquals("结论", reasoningPreview(text))
    }

    @Test
    fun reasoningPreviewUsesFirstLineOfSingleParagraph() {
        assertEquals("第一行", reasoningPreview("第一行\n第二行"))
    }

    @Test
    fun reasoningPreviewStripsMarkdownMarkers() {
        assertEquals("思考中 重点 内容", reasoningPreview("思考中 **重点** 内容"))
        assertEquals("引用", reasoningPreview("> 引用"))
        assertEquals("列表", reasoningPreview("- 列表"))
    }

    @Test
    fun reasoningPreviewBlankReturnsEmpty() {
        assertEquals("", reasoningPreview("\n\n   "))
    }

    private fun tool(id: Long, status: ToolActivityStatus = ToolActivityStatus.RUNNING): AssistantStep.Tool =
        AssistantStep.Tool(ToolTrace(id = id, name = "tool", arguments = emptyMap(), status = status))

    @Test
    fun processActivityOnlyWhileStreaming() {
        assertEquals(null, processActivity(emptyList(), streaming = false))
        assertEquals(null, processActivity(listOf(tool(1)), streaming = false))
    }

    @Test
    fun processActivityDefaultsToThinking() {
        assertEquals(ProcessActivity.Thinking, processActivity(emptyList(), streaming = true))
        assertEquals(
            ProcessActivity.Thinking,
            processActivity(listOf(AssistantStep.Reasoning(id = 1, text = "想")), streaming = true),
        )
        assertEquals(
            ProcessActivity.Thinking,
            processActivity(listOf(tool(1, ToolActivityStatus.DONE)), streaming = true),
        )
    }

    @Test
    fun processActivityReportsRunningTool() {
        assertEquals(
            ProcessActivity.CallingTool,
            processActivity(listOf(tool(1, ToolActivityStatus.RUNNING)), streaming = true),
        )
        assertEquals(
            ProcessActivity.CallingTool,
            processActivity(
                listOf(AssistantStep.Text(id = 1, text = "上段"), tool(2, ToolActivityStatus.RUNNING)),
                streaming = true,
            ),
        )
    }

    @Test
    fun processActivityReportsReplying() {
        assertEquals(
            ProcessActivity.Replying,
            processActivity(listOf(AssistantStep.Text(id = 1, text = "答")), streaming = true),
        )
        assertEquals(
            ProcessActivity.Thinking,
            processActivity(listOf(AssistantStep.Text(id = 1, text = " ")), streaming = true),
        )
    }

    @Test
    fun processStatusMapsMessageState() {
        assertEquals(ProcessStatus.Running, processStatus(streaming = true, status = MessageStatus.RUNNING))
        assertEquals(ProcessStatus.Stopped, processStatus(streaming = false, status = MessageStatus.STOPPED))
        assertEquals(ProcessStatus.Failed, processStatus(streaming = false, status = MessageStatus.FAILED))
        assertEquals(ProcessStatus.Done, processStatus(streaming = false, status = MessageStatus.DONE))
    }

    @Test
    fun liveProcessPhaseDoesNotRevertToStaleReasoningAfterToolCompletes() {
        val reasoning1 = AssistantStep.Reasoning(id = 1L, text = "First thought before bash")
        val reasoning3 = AssistantStep.Reasoning(id = 3L, text = "Next thought after bash")
        val runningTool = tool(2L, ToolActivityStatus.RUNNING)
        val finishedTool = tool(2L, ToolActivityStatus.DONE)
        val laterFinishedTool = tool(3L, ToolActivityStatus.DONE)

        assertNull(liveProcessPhase(listOf(reasoning1), streaming = false))
        assertEquals(
            LiveProcessPhase.Thinking(reasoning1),
            liveProcessPhase(listOf(reasoning1), streaming = true),
        )
        assertEquals(
            LiveProcessPhase.CallingTool(runningTool),
            liveProcessPhase(listOf(reasoning1, runningTool), streaming = true),
        )
        assertEquals(
            LiveProcessPhase.Thinking(reasoning = null),
            liveProcessPhase(listOf(reasoning1, finishedTool), streaming = true),
        )
        assertEquals(
            LiveProcessPhase.Thinking(reasoning3),
            liveProcessPhase(listOf(reasoning1, finishedTool, reasoning3), streaming = true),
        )
        assertEquals(
            LiveProcessPhase.CallingTool(runningTool),
            liveProcessPhase(listOf(reasoning1, runningTool, laterFinishedTool), streaming = true),
        )
    }

    @Test
    fun terminatedReasoningIdMatchesOnlyTrailingReasoningStep() {
        val reasoning = AssistantStep.Reasoning(id = 1L, text = "Interrupted")
        val failedTool = tool(2L, ToolActivityStatus.FAILED)

        assertEquals(1L, terminatedReasoningId(listOf(reasoning), terminated = true))
        assertNull(terminatedReasoningId(listOf(reasoning), terminated = false))
        assertNull(terminatedReasoningId(listOf(reasoning, failedTool), terminated = true))
    }
}

package io.github.whywood.superpi.ui.superagent

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.ui.common.chat.PresentedCardRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperChatViewModelTest {
    @Test
    fun mergePresentedCardsHandlesSuperPiPayloadToolTraceAndDbLookup() {
        val payloadCard = WorkerCardRef(
            cardId = "call-payload",
            workerId = "wrk-1",
            botRef = "local:home",
            toolName = "weather",
            title = "Forecast",
            argsJson = """{"city":"Tokyo"}""",
            outputText = "Sunny",
            detailsJson = """{"superpi.card":{"customType":"weather.card","title":"Tokyo Weather","payload":{"temp":22}}}""",
            createdAt = 100L,
        )
        val fallbackCard = WorkerCardRef(
            cardId = "call-tool",
            workerId = "wrk-2",
            botRef = "local:home",
            toolName = "lookup",
            title = "Lookup Result",
            argsJson = """{"query":"flights","limit":5}""",
            outputText = "3 matches",
            detailsJson = "{}",
            createdAt = 200L,
        )
        val archivedCard = WorkerCardRef(
            cardId = "call-archived",
            workerId = "wrk-deleted",
            botRef = "local:home",
            toolName = "search",
            title = "Archived Hit",
            argsJson = """{"q":"notes"}""",
            outputText = "Found",
            detailsJson = "{}",
            createdAt = 300L,
        )

        val worker = WorkerRunRecord(
            workerId = "wrk-1",
            botRef = "local:home",
            botId = "home",
            botName = "PI",
            title = "Trip",
            status = WorkerStatus.IDLE,
            cards = listOf(payloadCard, fallbackCard),
        )

        val presentTrace = ToolTrace(
            id = 1L,
            name = "present",
            arguments = emptyMap(),
            status = ToolActivityStatus.DONE,
            output = """{"items":[{"type":"card","id":"call-archived","title":"Archived Hit"}]}""",
            toolCallId = "present-1",
        )
        val messages = listOf(
            AssistantMessage(
                id = 10L,
                role = AssistantRole.ASSISTANT,
                text = "Done",
                steps = listOf(AssistantStep.Tool(presentTrace)),
            ),
        )

        val cards = mergePresentedCards(
            messages = messages,
            workers = listOf(worker),
            lookupById = { id -> if (id == "call-archived") archivedCard else null },
        )

        val payload = cards["call-payload"] as PresentedCardRef.Payload
        assertEquals("weather.card", payload.customType)
        assertEquals("Tokyo Weather", payload.title)

        val toolRef = cards["call-tool"] as PresentedCardRef.Tool
        assertEquals("lookup", toolRef.trace.name)
        assertEquals("flights", toolRef.trace.arguments["query"])
        assertEquals("5", toolRef.trace.arguments["limit"])

        val archivedRef = cards["call-archived"] as PresentedCardRef.Tool
        assertEquals("search", archivedRef.trace.name)
    }

    @Test
    fun applyExpandedStepOverridesTogglesMatchingReasoningAndToolSteps() {
        val message = AssistantMessage(
            id = 1L,
            role = AssistantRole.ASSISTANT,
            text = "Result",
            steps = listOf(
                AssistantStep.Reasoning(id = 11L, text = "Thinking", expanded = false),
                AssistantStep.Tool(
                    ToolTrace(id = 12L, name = "worker", arguments = emptyMap(), expanded = false),
                ),
                AssistantStep.Text(id = 13L, text = "Done"),
            ),
        )

        val toggled = applyExpandedStepOverrides(listOf(message), setOf(11L))
        val steps = toggled.single().steps
        assertTrue((steps[0] as AssistantStep.Reasoning).expanded)
        assertFalse((steps[1] as AssistantStep.Tool).trace.expanded)
    }
}

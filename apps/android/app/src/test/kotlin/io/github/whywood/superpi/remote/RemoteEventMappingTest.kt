package io.github.whywood.superpi.remote

import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ThinkingContent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolCallStartEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEventMappingTest {
    @Test
    fun decodesBotEvents() {
        assertEquals(RemoteBotEvent.AgentStart(), decode("""{"type":"agent_start"}"""))
        assertEquals(
            RemoteBotEvent.TextDelta("hi"),
            decode("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hi"}}"""),
        )
        assertEquals(
            RemoteBotEvent.ReasoningDelta("think"),
            decode("""{"type":"message_update","assistantMessageEvent":{"type":"reasoning_delta","delta":"think"}}"""),
        )
        assertEquals(
            RemoteBotEvent.ToolCall("call-1", "shell", buildJsonObject { put("command", "ls") }),
            decode("""{"type":"tool_call","callId":"call-1","name":"shell","args":{"command":"ls"}}"""),
        )
        assertEquals(
            RemoteBotEvent.ToolResult("call-1", "ok", false, null),
            decode("""{"type":"tool_result","callId":"call-1","result":"ok"}"""),
        )
        assertEquals(RemoteBotEvent.Failed("boom"), decode("""{"type":"error","message":"boom"}"""))
        assertEquals(RemoteBotEvent.Ended("stopped"), decode("""{"type":"agent_end","status":"stopped"}"""))
        assertEquals(RemoteBotEvent.Settled, decode("""{"type":"agent_settled"}"""))
        assertEquals(
            RemoteBotEvent.QueueUpdate(listOf("a"), listOf("b")),
            decode("""{"type":"queue_update","steering":["a"],"followUp":["b"]}"""),
        )
        assertEquals(
            RemoteBotEvent.UserMessage("queued"),
            decode("""{"type":"user_message","text":"queued"}"""),
        )
        assertEquals(
            RemoteBotEvent.ApprovalRequest(
                approvalId = "a",
                kind = "k",
                toolCallId = "call_q",
                title = "Choose",
                message = "Choose",
                options = listOf("Yes", "No"),
                questions = listOf(
                    io.github.whywood.superpi.runtime.UserQuestion(
                        id = "q1",
                        header = "Mode",
                        question = "Pick one",
                        options = listOf(io.github.whywood.superpi.runtime.QuestionOption("Yes")),
                        multiSelect = false,
                    ),
                ),
                resolved = false,
            ),
            decode(
                """{"type":"approval_request","approvalId":"a","kind":"k","toolCallId":"call_q","summary":"Choose","options":["Yes","No"],"questions":[{"id":"q1","header":"Mode","question":"Pick one","options":[{"label":"Yes"}]}]}""",
            ),
        )
    }

    @Test
    fun ignoresUnknownAndMalformedEvents() {
        assertNull(decode("""{"type":"approval_request","kind":"k","summary":"s"}"""))
        assertNull(decode("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta"}}"""))
        assertNull(decode("""{"type":"message_update","assistantMessageEvent":{"type":"image_delta","delta":"x"}}"""))
        assertNull(decode("""{"type":"tool_call","name":"shell"}"""))
    }

    @Test
    fun mapsTextDeltaToMessageUpdate() {
        val state = RemoteTurnState("PI")
        val update = state.consume(RemoteBotEvent.TextDelta("Hel")).single() as AgentEvent.MessageUpdate

        val delta = update.assistantMessageEvent as TextDeltaEvent
        assertEquals("Hel", delta.delta)
        assertEquals("Hel", state.text)
        assertEquals("Hel", (update.message.asLlmMessage() as AssistantMessage).text())
    }

    @Test
    fun mapsReasoningDeltaBeforeText() {
        val state = RemoteTurnState("PI")
        state.consume(RemoteBotEvent.ReasoningDelta("think"))
        val update = state.consume(RemoteBotEvent.TextDelta("hi")).single() as AgentEvent.MessageUpdate

        assertEquals(1, (update.assistantMessageEvent as TextDeltaEvent).contentIndex)
        val assistant = state.assistantMessage(StopReason.Stop, null)
        assertEquals("think", (assistant.content[0] as ThinkingContent).thinking)
        assertEquals("hi", (assistant.content[1] as TextContent).text)
    }

    @Test
    fun mapsToolCallWithoutArgumentsToToolCallStart() {
        val state = RemoteTurnState("PI")
        val update = state.consume(RemoteBotEvent.ToolCall("call-1", "shell", JsonObject(emptyMap()))).single()
            as AgentEvent.MessageUpdate
        val start = update.assistantMessageEvent as ToolCallStartEvent
        val call = (update.message.asLlmMessage() as AssistantMessage).content[start.contentIndex] as ToolCall

        assertEquals("call-1", call.id)
        assertEquals("shell", call.name)
        assertEquals(
            emptyList<AgentEvent>(),
            state.consume(RemoteBotEvent.ToolCall("call-1", "shell", JsonObject(emptyMap()))),
        )
    }

    @Test
    fun mapsToolCallWithArgumentsToExecutionStart() {
        val state = RemoteTurnState("PI")
        val args = buildJsonObject { put("command", "ls") }
        val start = state.consume(RemoteBotEvent.ToolCall("call-1", "shell", args)).single()
            as AgentEvent.ToolExecutionStart

        assertEquals("call-1", start.toolCallId)
        assertEquals("shell", start.toolName)
        assertEquals(args, start.args)
        assertEquals(emptyList<AgentEvent>(), state.consume(RemoteBotEvent.ToolCall("call-1", "shell", args)))
    }

    @Test
    fun mapsToolResultToExecutionEnd() {
        val state = RemoteTurnState("PI")
        state.consume(RemoteBotEvent.ToolCall("call-1", "shell", buildJsonObject { put("command", "ls") }))
        val end = state.consume(RemoteBotEvent.ToolResult("call-1", "a.txt", false, null)).single()
            as AgentEvent.ToolExecutionEnd

        assertEquals("call-1", end.toolCallId)
        assertEquals("shell", end.toolName)
        assertEquals(false, end.isError)
        assertEquals("a.txt", end.result.content.filterIsInstance<TextContent>().joinToString("") { it.text })
        assertEquals(listOf("a.txt"), state.toolResults.map { it.result })
        assertTrue(state.runningToolNames().isEmpty())
    }

    @Test
    fun toolResultWithoutStartStillEnds() {
        val state = RemoteTurnState("PI")
        val events = state.consume(RemoteBotEvent.ToolResult("call-9", "boom", true, null))

        assertEquals(2, events.size)
        assertTrue(events[0] is AgentEvent.ToolExecutionStart)
        assertTrue(events[1] is AgentEvent.ToolExecutionEnd)
        assertTrue((events[1] as AgentEvent.ToolExecutionEnd).isError)
    }

    @Test
    fun recordsFailureAndEndStatus() {
        val state = RemoteTurnState("PI")
        assertTrue(state.consume(RemoteBotEvent.Failed("boom")).isEmpty())
        assertEquals("boom", state.failure)
        assertTrue(state.consume(RemoteBotEvent.Ended("stopped")).isEmpty())
        assertEquals("stopped", state.endStatus)
        assertTrue(state.consume(RemoteBotEvent.Settled).isEmpty())
    }

    @Test
    fun turnMessagesPreservesInterleavedMultiStepToolHistory() {
        val state = RemoteTurnState("PI")
        state.consume(RemoteBotEvent.TextDelta("Checking files."))
        state.consume(RemoteBotEvent.ToolCall("call-1", "shell", buildJsonObject { put("command", "ls") }))
        state.consume(RemoteBotEvent.ToolResult("call-1", "a.txt", false, null))
        state.consume(RemoteBotEvent.TextDelta("Found a.txt."))

        val messages = state.turnMessages(StopReason.Stop, null)

        assertEquals(3, messages.size)
        val firstAssistant = messages[0] as AssistantMessage
        assertEquals("Checking files.", firstAssistant.text())
        assertEquals(StopReason.ToolUse, firstAssistant.stopReason)
        assertTrue(firstAssistant.content.any { it is ToolCall && it.id == "call-1" })
        val toolResult = messages[1] as io.github.whywood.superpi.sdk.ai.ToolResultMessage
        assertEquals("call-1", toolResult.toolCallId)
        val finalAssistant = messages[2] as AssistantMessage
        assertEquals("Found a.txt.", finalAssistant.text())
        assertEquals(StopReason.Stop, finalAssistant.stopReason)
    }

    @Test
    fun decodesLiveSyncFieldsOnAgentStartUserMessageAndQueueUpdate() {
        val imageJson = """[{"data":"YWJj","mimeType":"image/png"}]"""
        val expectedImage = io.github.whywood.superpi.runtime.ImagePart(data = "YWJj", mimeType = "image/png")

        val agentStart = decode(
            """{"type":"agent_start","sessionId":"s-1","title":"Hello","originRouteId":"r-1","userMessage":{"text":"hi","images":$imageJson}}""",
        )
        assertEquals(
            RemoteBotEvent.AgentStart(
                sessionId = "s-1",
                title = "Hello",
                userMessage = RemoteUserMessage(text = "hi", images = listOf(expectedImage)),
                originRouteId = "r-1",
            ),
            agentStart,
        )

        val userMessage = decode(
            """{"type":"user_message","sessionId":"s-1","text":"hi","images":$imageJson}""",
        )
        assertEquals(
            RemoteBotEvent.UserMessage(
                text = "hi",
                images = listOf(expectedImage),
                sessionId = "s-1",
            ),
            userMessage,
        )

        val queueUpdate = decode(
            """{"type":"queue_update","sessionId":"s-1","steering":["s1"],"followUp":["f1"],"userMessage":{"text":"steer","images":$imageJson}}""",
        )
        assertEquals(
            RemoteBotEvent.QueueUpdate(
                steering = listOf("s1"),
                followUp = listOf("f1"),
                sessionId = "s-1",
                userMessage = RemoteUserMessage(text = "steer", images = listOf(expectedImage)),
            ),
            queueUpdate,
        )
    }

    private fun decode(json: String): RemoteBotEvent? =
        RemoteEventMapping.decode(Json.parseToJsonElement(json).jsonObject)

    private fun AssistantMessage.text(): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }
}

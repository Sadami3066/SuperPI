package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.ui.chat.testing.FakeAgentRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAgentServerTest {
    private val defaultBots = listOf(RelayBot("home", "PI"))

    @Test
    fun listsNonArchivedSessionsOrderedByPinnedThenUpdatedAt() = runTest {
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-old", "Old", 10L, "/old.jsonl", "/home"),
                ConversationSummary("s-arch", "Archived", 99L, "/arch.jsonl", "/home", archived = true),
                ConversationSummary("s-pin", "Pinned", 5L, "/pin.jsonl", "/home", pinned = true),
                ConversationSummary("s-new", "", 50L, "/new.jsonl", "/home"),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)

        server.handle(
            RelayServerFrame.Route(
                id = "r-1",
                from = "dev-2",
                method = "bot.sessions",
                params = buildJsonObject { put("botId", "home") },
            ),
        )
        val ok = sent.single()
        assertEquals("route_ok", ok.getValue("type").jsonPrimitive.content)
        val items = ok.getValue("result").jsonObject.getValue("sessions").jsonArray
        assertEquals(listOf("s-pin", "s-new", "s-old"), items.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(true, items[0].jsonObject.getValue("pinned").jsonPrimitive.boolean)
        assertEquals("Untitled", items[1].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(50L, items[1].jsonObject.getValue("updatedAt").jsonPrimitive.long)

        server.handle(
            RelayServerFrame.Route(
                id = "r-2",
                from = "dev-2",
                method = "bot.sessions",
                params = buildJsonObject { put("botId", "missing") },
            ),
        )
        val err = sent.last()
        assertEquals("route_error", err.getValue("type").jsonPrimitive.content)
        assertEquals("bot_not_found", err.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun loadsSessionHistoryOpeningStoredSessionWhenNeededAndRejectsMissing() = runTest {
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-1", "Inspect repo", 100L, "/s-1.jsonl", "/home"),
            )
            histories = mapOf(
                "s-1" to listOf(
                    AssistantMessage(id = 1L, role = AssistantRole.USER, text = "Check status"),
                    AssistantMessage(
                        id = 2L,
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        steps = listOf(
                            AssistantStep.Reasoning(10L, "Checking"),
                            AssistantStep.Tool(
                                ToolTrace(
                                    id = 11L,
                                    name = "bash",
                                    arguments = mapOf("command" to "ls"),
                                    status = ToolActivityStatus.DONE,
                                    output = "ok",
                                    toolCallId = "call-1",
                                ),
                            ),
                            AssistantStep.Text(12L, "All clear"),
                        ),
                    ),
                ),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)

        server.handle(
            RelayServerFrame.Route(
                id = "r-h1",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-1")
                },
            ),
        )
        val ok = sent.single()
        assertEquals("route_ok", ok.getValue("type").jsonPrimitive.content)
        val result = ok.getValue("result").jsonObject
        assertEquals("s-1", result.getValue("sessionId").jsonPrimitive.content)
        assertEquals("Inspect repo", result.getValue("title").jsonPrimitive.content)
        assertFalse(result.getValue("busy").jsonPrimitive.boolean)
        val messages = result.getValue("messages").jsonArray
        assertEquals(3, messages.size)
        assertEquals("user", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        val assistantBlocks = messages[1].jsonObject.getValue("content").jsonArray
        assertEquals("thinking", assistantBlocks[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("toolCall", assistantBlocks[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("text", assistantBlocks[2].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("toolResult", messages[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("call-1", messages[2].jsonObject.getValue("toolCallId").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "r-h2",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "does-not-exist")
                },
            ),
        )
        val missing = sent.last()
        assertEquals("route_error", missing.getValue("type").jsonPrimitive.content)
        assertEquals("session_not_found", missing.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun promptReopensStoredHistoricalSessionBeforeSending() = runTest {
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-stored", "Stored", 80L, "/stored.jsonl", "/home"),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)

        server.handle(
            RelayServerFrame.Route(
                id = "r-p1",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-stored")
                    put("text", "Continue here")
                },
            ),
        )

        assertTrue(host.openCalls.contains("/stored.jsonl"))
        assertEquals(listOf("Continue here"), host.sent)
        val ok = sent.single()
        assertEquals("route_ok", ok.getValue("type").jsonPrimitive.content)
        assertEquals("s-stored", ok.getValue("result").jsonObject.getValue("sessionId").jsonPrimitive.content)
    }

    @Test
    fun rejectsCrossBotHistoryAccessAcceptsImagesOnPromptAndReportsUnsupportedApproval() = runTest {
        val bots = listOf(RelayBot("home", "PI"), RelayBot("work", "Work"))
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-home", "Home", 10L, "/home.jsonl", "/home"),
            )
            histories = mapOf(
                "s-home" to listOf(AssistantMessage(id = 1L, role = AssistantRole.USER, text = "hi")),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { bots }, { sent.add(it); true }, backgroundScope)

        server.handle(
            RelayServerFrame.Route(
                id = "r-owner",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-home")
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "r-cross",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", "work")
                    put("sessionId", "s-home")
                },
            ),
        )
        assertEquals("route_error", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("session_not_found", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "r-img",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", "home")
                    put("text", "")
                    put(
                        "images",
                        kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("data", "YWJj"); put("mimeType", "image/png") })
                        },
                    )
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals(1, host.sentImages.size)

        server.handle(
            RelayServerFrame.Route(
                id = "r-app",
                from = "dev-2",
                method = "bot.approval",
                params = buildJsonObject { put("requestId", "req-1") },
            ),
        )
        assertEquals("unsupported", sent.last().getValue("code").jsonPrimitive.content)
    }
}

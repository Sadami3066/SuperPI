package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CompactionOutcome
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionModelSnapshot
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.ui.chat.testing.FakeAgentRuntime
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAgentServerTest {
    private val defaultBots = listOf(RelayBot("home", "PI"))

    @get:Rule
    val temp = TemporaryFolder()

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
        runCurrent()

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
        runCurrent()

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
        runCurrent()

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

    @Test
    fun forwardsQuestionCenterRequestsAndAcceptsRemoteBotApprovalBidirectionally() = runTest {
        val questionCenter = io.github.whywood.superpi.runtime.QuestionCenter(persistScope = backgroundScope)
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-local", "Local Run", 100L, "/local.jsonl", "/home"),
            )
            histories = mapOf(
                "s-local" to listOf(AssistantMessage(id = 1L, role = AssistantRole.USER, text = "Ask me")),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = host,
            bots = { defaultBots },
            send = { sent.add(it); true },
            scope = backgroundScope,
            questionCenter = questionCenter,
        )

        val askDeferred = async {
            questionCenter.ask(
                sessionId = "s-local",
                toolCallId = "tool-q1",
                questions = listOf(
                    io.github.whywood.superpi.runtime.UserQuestion(
                        id = "q1",
                        header = "Target",
                        question = "Choose one",
                        options = listOf(io.github.whywood.superpi.runtime.QuestionOption("Alpha")),
                    ),
                ),
            )
        }
        testScheduler.advanceUntilIdle()
        val reqId = questionCenter.pending.value.single().requestId

        server.handle(
            RelayServerFrame.Route(
                id = "r-hist",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-local")
                },
            ),
        )
        val histOk = sent.last()
        assertEquals("route_ok", histOk["type"]?.jsonPrimitive?.content)
        val histSeq = histOk.getValue("result").jsonObject.getValue("seq").jsonPrimitive.content.toLong()
        sent.clear()

        server.handle(
            RelayServerFrame.Route(
                id = "r-res",
                from = "dev-2",
                method = "bot.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-local")
                    put("afterSeq", histSeq)
                },
            ),
        )
        val replayed = sent.firstOrNull { frame ->
            frame["type"]?.jsonPrimitive?.content == "route_event" &&
                frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "approval_request"
        }
        assertTrue(replayed != null)
        assertTrue(replayed!!.getValue("seq").jsonPrimitive.content.toLong() > histSeq)
        assertEquals(reqId, replayed.getValue("event").jsonObject.getValue("approvalId").jsonPrimitive.content)

        sent.clear()
        server.handle(
            RelayServerFrame.Route(
                id = "r-empty-ans",
                from = "dev-2",
                method = "bot.approval",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-local")
                    put("approvalId", reqId)
                },
            ),
        )
        assertEquals("bad_request", sent.single().getValue("code").jsonPrimitive.content)

        sent.clear()
        io.github.whywood.superpi.remote.RemoteMirrorRegistry.register("s-mirror-1")
        server.handle(
            RelayServerFrame.Route(
                id = "r-mirror-res",
                from = "dev-2",
                method = "bot.resume",
                params = buildJsonObject {
                    put("sessionId", "s-mirror-1")
                },
            ),
        )
        assertEquals("session_not_found", sent.single().getValue("code").jsonPrimitive.content)

        sent.clear()
        server.handle(
            RelayServerFrame.Route(
                id = "r-ans",
                from = "dev-2",
                method = "bot.approval",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-local")
                    put("approvalId", reqId)
                    put(
                        "answers",
                        kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", "q1")
                                    put("selected", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("Alpha")) })
                                },
                            )
                        },
                    )
                },
            ),
        )
        testScheduler.advanceUntilIdle()

        val outcome = askDeferred.await() as io.github.whywood.superpi.runtime.QuestionOutcome.Answered
        assertEquals(listOf("Alpha"), outcome.answers.single().selected)
        assertTrue(
            sent.any { frame ->
                frame["type"]?.jsonPrimitive?.content == "route_ok" &&
                    frame["id"]?.jsonPrimitive?.content == "r-ans"
            },
        )
        assertTrue(
            sent.any { frame ->
                frame["type"]?.jsonPrimitive?.content == "route_event" &&
                    frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "approval_request" &&
                    frame["event"]?.jsonObject?.get("resolved")?.jsonPrimitive?.boolean == true
            },
        )
    }

    @Test
    fun resumesActiveSentinelWhenIdleAndStreamsConsecutiveLocalTurnsToSameWatcher() = runTest {
        val host = FakeAgentRuntime()
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-watch",
                from = "phone-2",
                method = "bot.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "__active__")
                    put("tail", true)
                },
            ),
        )
        testScheduler.advanceUntilIdle()

        val ack = sent.single()
        assertEquals("route_ok", ack.getValue("type").jsonPrimitive.content)
        val ackResult = ack.getValue("result").jsonObject
        assertEquals("__active__", ackResult.getValue("sessionId").jsonPrimitive.content)
        assertFalse(ackResult.getValue("busy").jsonPrimitive.boolean)

        host.emitFor("s-local-1", RuntimeEvent.RunStarted("run-1", 100L, "local turn 1"))
        runCurrent()
        host.emitFor("s-local-1", RuntimeEvent.TextDelta("run-1", "hi 1"))
        runCurrent()
        host.emitFor("s-local-1", RuntimeEvent.RunFinished("run-1", "hi 1", RunStatus.DONE, 200L))
        runCurrent()
        advanceUntilIdle()

        val turn1Events = sent.filter {
            it["type"]?.jsonPrimitive?.content == "route_event" &&
                it["id"]?.jsonPrimitive?.content == "r-watch"
        }.map { it.getValue("event").jsonObject }
        assertEquals(
            listOf("agent_start", "message_update", "agent_end", "agent_settled"),
            turn1Events.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("s-local-1", turn1Events.first().getValue("sessionId").jsonPrimitive.content)
        assertEquals(
            "local turn 1",
            turn1Events.first().getValue("userMessage").jsonObject.getValue("text").jsonPrimitive.content,
        )

        sent.clear()
        host.emitFor("s-local-1", RuntimeEvent.RunStarted("run-2", 300L, "local turn 2"))
        runCurrent()
        host.emitFor("s-local-1", RuntimeEvent.TextDelta("run-2", "hi 2"))
        runCurrent()
        host.emitFor("s-local-1", RuntimeEvent.RunFinished("run-2", "hi 2", RunStatus.DONE, 400L))
        runCurrent()
        advanceUntilIdle()

        val turn2Events = sent.filter {
            it["type"]?.jsonPrimitive?.content == "route_event" &&
                it["id"]?.jsonPrimitive?.content == "r-watch"
        }.map { it.getValue("event").jsonObject }
        assertEquals(
            listOf("agent_start", "message_update", "agent_end", "agent_settled"),
            turn2Events.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "local turn 2",
            turn2Events.first().getValue("userMessage").jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun promptTurnUsesPromptRouteWhileWatchingSameDeviceAndSwitchesBackOnNextLocalTurn() = runTest {
        val host = FakeAgentRuntime().apply {
            sessions = listOf(ConversationSummary("s-stored", "Stored", 80L, "/stored.jsonl", "/home"))
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-watch",
                from = "phone-2",
                method = "bot.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-stored")
                    put("tail", true)
                },
            ),
        )
        testScheduler.advanceUntilIdle()

        server.handle(
            RelayServerFrame.Route(
                id = "r-p1",
                from = "phone-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-stored")
                    put("text", "from peer")
                },
            ),
        )
        testScheduler.advanceUntilIdle()
        sent.clear()

        host.emitFor("s-stored", RuntimeEvent.RunStarted("run-p1", 10L, "from peer"))
        runCurrent()
        host.emitFor("s-stored", RuntimeEvent.TextDelta("run-p1", "ok"))
        runCurrent()
        host.emitFor("s-stored", RuntimeEvent.RunFinished("run-p1", "ok", RunStatus.DONE, 20L))
        runCurrent()
        advanceUntilIdle()

        val promptRouteEvents = sent.filter {
            it["type"]?.jsonPrimitive?.content == "route_event" &&
                it["id"]?.jsonPrimitive?.content == "r-p1"
        }.map { it.getValue("event").jsonObject }
        assertEquals(
            listOf("agent_start", "message_update", "agent_end", "agent_settled"),
            promptRouteEvents.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals("r-p1", promptRouteEvents.first().getValue("originRouteId").jsonPrimitive.content)
        assertFalse(
            sent.any {
                it["type"]?.jsonPrimitive?.content == "route_event" &&
                    it["id"]?.jsonPrimitive?.content == "r-watch"
            },
        )

        sent.clear()
        host.emitFor("s-stored", RuntimeEvent.RunStarted("run-local-2", 30L, "from phone"))
        runCurrent()
        host.emitFor("s-stored", RuntimeEvent.TextDelta("run-local-2", "ok 2"))
        runCurrent()
        host.emitFor("s-stored", RuntimeEvent.RunFinished("run-local-2", "ok 2", RunStatus.DONE, 40L))
        runCurrent()
        advanceUntilIdle()

        val watchRouteEvents = sent.filter {
            it["type"]?.jsonPrimitive?.content == "route_event" &&
                it["id"]?.jsonPrimitive?.content == "r-watch"
        }.map { it.getValue("event").jsonObject }
        assertEquals(
            listOf("agent_start", "message_update", "agent_end", "agent_settled"),
            watchRouteEvents.map { it.getValue("type").jsonPrimitive.content },
        )
        assertEquals(
            "from phone",
            watchRouteEvents.first().getValue("userMessage").jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun managesBotsAndSessionsWithHomeProtectionBusyConflictsAndSyncEvents() = runTest {
        val avatarDir = temp.newFolder("avatars")
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-arch", "Archived", 20L, "/arch.jsonl", "/home", archived = true),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val syncEvents = mutableListOf<RelaySyncEvent>()
        var botsChanged = 0
        val server = RemoteAgentServer(
            host = host,
            bots = {
                host.workspaces().filter { it.remote == null }.map { RelayBot(it.id, it.name, avatar = it.avatar) }
            },
            send = { sent.add(it); true },
            scope = backgroundScope,
            onBotsChanged = { botsChanged += 1 },
            onSyncEvent = { syncEvents += it },
            avatarStorageDir = avatarDir,
        )
        runCurrent()

        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        val avatarUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

        server.handle(
            RelayServerFrame.Route(
                id = "b-create",
                from = "dev-2",
                method = "bot.create",
                params = buildJsonObject {
                    put("name", "Research")
                    put("avatar", avatarUrl)
                    put("intro", "Helper")
                },
            ),
        )
        val createOk = sent.last()
        assertEquals("route_ok", createOk.getValue("type").jsonPrimitive.content)
        val createdBot = createOk.getValue("result").jsonObject.getValue("bot").jsonObject
        val createdBotId = createdBot.getValue("id").jsonPrimitive.content
        assertEquals("Research", createdBot.getValue("name").jsonPrimitive.content)
        assertEquals(1, botsChanged)
        assertEquals(1, avatarDir.listFiles().orEmpty().size)

        server.handle(
            RelayServerFrame.Route(
                id = "b-update",
                from = "dev-2",
                method = "bot.update",
                params = buildJsonObject {
                    put("botId", createdBotId)
                    put("name", "Research Pro")
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("Research Pro", host.workspaces().first { it.id == createdBotId }.name)
        assertEquals(2, botsChanged)

        server.handle(
            RelayServerFrame.Route(
                id = "b-del-home",
                from = "dev-2",
                method = "bot.delete",
                params = buildJsonObject { put("botId", "home") },
            ),
        )
        assertEquals("route_error", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("permission_denied", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "s-create",
                from = "dev-2",
                method = "session.create",
                params = buildJsonObject { put("botId", "home"); put("title", "Planning") },
            ),
        )
        val sessionId = sent.last().getValue("result").jsonObject.getValue("session").jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(syncEvents.any { it.kind == "sessions_changed" && it.botId == "home" && it.sessionId == sessionId })

        server.handle(
            RelayServerFrame.Route(
                id = "s-rename",
                from = "dev-2",
                method = "session.rename",
                params = buildJsonObject { put("botId", "home"); put("sessionId", sessionId); put("title", "Planning V2") },
            ),
        )
        assertEquals("Planning V2", host.sessions("home").first { it.id == sessionId }.title)

        server.handle(
            RelayServerFrame.Route(
                id = "s-meta",
                from = "dev-2",
                method = "session.update_meta",
                params = buildJsonObject { put("botId", "home"); put("sessionId", sessionId); put("pinned", true); put("archived", true) },
            ),
        )
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("archived").jsonPrimitive.boolean)

        server.handle(
            RelayServerFrame.Route(
                id = "s-list-arch",
                from = "dev-2",
                method = "bot.sessions",
                params = buildJsonObject { put("botId", "home"); put("includeArchived", true) },
            ),
        )
        val listedIds = sent.last().getValue("result").jsonObject.getValue("sessions").jsonArray.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        }
        assertTrue(listedIds.contains(sessionId))
        assertTrue(listedIds.contains("s-arch"))

        host.setBusy(setOf(sessionId))
        server.handle(
            RelayServerFrame.Route(
                id = "s-del-busy",
                from = "dev-2",
                method = "session.delete",
                params = buildJsonObject { put("botId", "home"); put("sessionId", sessionId) },
            ),
        )
        assertEquals("conflict", sent.last().getValue("code").jsonPrimitive.content)

        host.setBusy(emptySet())
        server.handle(
            RelayServerFrame.Route(
                id = "s-del-ok",
                from = "dev-2",
                method = "session.delete",
                params = buildJsonObject { put("botId", "home"); put("sessionId", sessionId) },
            ),
        )
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("deleted").jsonPrimitive.boolean)

        server.handle(
            RelayServerFrame.Route(
                id = "b-del-ok",
                from = "dev-2",
                method = "bot.delete",
                params = buildJsonObject { put("botId", createdBotId) },
            ),
        )
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("deleted").jsonPrimitive.boolean)
        assertEquals(3, botsChanged)
    }

    @Test
    fun handlesFileRoutesAndMapsFileErrors() = runTest {
        val ws = temp.newFolder("bot-ws")
        File(ws, "hello.txt").writeText("world")
        val host = FakeAgentRuntime().apply {
            createWorkspace("Files", ws.absolutePath)
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = host,
            bots = {
                host.workspaces().filter { it.remote == null }.map { RelayBot(it.id, it.name) }
            },
            send = { sent.add(it); true },
            scope = backgroundScope,
        )
        runCurrent()

        val botId = host.workspaces().first { it.name == "Files" }.id
        server.handle(
            RelayServerFrame.Route(
                id = "f-list",
                from = "dev-2",
                method = "file.list",
                params = buildJsonObject { put("botId", botId) },
            ),
        )
        val entries = sent.last().getValue("result").jsonObject.getValue("entries").jsonArray
        assertEquals("hello.txt", entries.single().jsonObject.getValue("name").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "f-bad",
                from = "dev-2",
                method = "file.stat",
                params = buildJsonObject { put("botId", botId); put("path", "../escape") },
            ),
        )
        assertEquals("route_error", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
    }

    @Test
    fun handlesControlsInstructionsCompactRetryReloadAndRejectsUnsupported() = runTest {
        val ws = temp.newFolder("ctrl-ws")
        val delegate = FakeAgentRuntime()
        val wsCreated = delegate.createWorkspace("Ctrl", ws.absolutePath)
        val botId = wsCreated.id
        delegate.sessions = listOf(ConversationSummary("s-1", "Main", 10L, "/s-1.jsonl", ws.absolutePath))
        delegate.sessionModels["s-1"] = SessionModelSnapshot("openai", "gpt-5", ThinkingLevel.MEDIUM)
        delegate.usages = mapOf("s-1" to SessionUsage(400L, 128_000L, 300L, 100L, 50L, 10L, 0.02))
        var reloadedTarget: String? = null
        val host = object : AgentRuntime by delegate {
            override suspend fun compact(sessionId: String): CompactionOutcome = CompactionOutcome.Done(800L, 250L)
            override suspend fun reloadSession(sessionId: String): String {
                reloadedTarget = sessionId
                return sessionId
            }
        }
        val catalog = object : ProviderDirectory {
            override val summaries = listOf(
                ProviderSummary("openai", "OpenAI", 1, null, false, true),
                ProviderSummary("anthropic", "Anthropic", 1, null, false, true),
            )
            override fun summary(providerId: String) = summaries.firstOrNull { it.id == providerId }
            override fun models(providerId: String) = when (providerId) {
                "openai" -> listOf(ModelOption("openai", "gpt-5", "GPT-5", listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)))
                "anthropic" -> listOf(ModelOption("anthropic", "claude-sonnet", "Claude Sonnet", listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH, ThinkingLevel.XHIGH)))
                else -> emptyList()
            }
            override fun resolveConfig(config: ModelConfig) = config
            override fun sdkModel(providerId: String, modelId: String): Model? = null
            override fun modelReference(providerId: String, modelId: String) = "$providerId/$modelId"
        }
        val sent = mutableListOf<JsonObject>()
        val syncEvents = mutableListOf<RelaySyncEvent>()
        val server = RemoteAgentServer(
            host = host,
            bots = { listOf(RelayBot(botId, "Ctrl")) },
            send = { sent.add(it); true },
            scope = backgroundScope,
            onSyncEvent = { syncEvents += it },
            providerCatalog = { catalog },
            configuredProviderIds = { setOf("openai", "anthropic") },
            agentDocStore = { AgentDocStore(kotlinx.coroutines.Dispatchers.Unconfined) },
            slashCommands = { listOf(RemoteCommandSpec("compact", "Compact", "builtin"), RemoteCommandSpec("skill:git", "Git", "skill")) },
        )
        runCurrent()

        server.handle(RelayServerFrame.Route("st-1", "dev-2", "bot.state", buildJsonObject { put("botId", botId); put("sessionId", "s-1") }))
        val stResult = sent.last().getValue("result").jsonObject
        assertEquals("gpt-5", stResult.getValue("model").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("medium", stResult.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals(400L, stResult.getValue("usage").jsonObject.getValue("contextTokens").jsonPrimitive.long)
        assertEquals(0.02, stResult.getValue("usage").jsonObject.getValue("cost").jsonPrimitive.double, 0.0001)

        server.handle(RelayServerFrame.Route("m-1", "dev-2", "bot.models", buildJsonObject { put("botId", botId) }))
        val modelsResult = sent.last().getValue("result").jsonObject
        assertEquals(2, modelsResult.getValue("models").jsonArray.size)
        assertEquals(2, modelsResult.getValue("providers").jsonArray.size)

        server.handle(
            RelayServerFrame.Route(
                "sm-1",
                "dev-2",
                "bot.set_model",
                buildJsonObject {
                    put("botId", botId)
                    put("sessionId", "s-1")
                    put("provider", "anthropic")
                    put("modelId", "claude-sonnet")
                    put("thinkingLevel", "xhigh")
                },
            ),
        )
        assertEquals("anthropic", delegate.sessionModels["s-1"]?.providerId)
        assertEquals(ThinkingLevel.XHIGH, delegate.sessionModels["s-1"]?.thinkingLevel)
        assertEquals("model_changed", syncEvents.last().kind)
        assertEquals("claude-sonnet", syncEvents.last().modelId)

        server.handle(
            RelayServerFrame.Route(
                "sth-1",
                "dev-2",
                "bot.set_thinking",
                buildJsonObject { put("botId", botId); put("sessionId", "s-1"); put("thinkingLevel", "low") },
            ),
        )
        assertEquals(ThinkingLevel.LOW, delegate.sessionModels["s-1"]?.thinkingLevel)

        server.handle(RelayServerFrame.Route("cmd-1", "dev-2", "bot.commands", buildJsonObject { put("botId", botId) }))
        assertEquals(2, sent.last().getValue("result").jsonObject.getValue("commands").jsonArray.size)

        server.handle(
            RelayServerFrame.Route(
                "is-1",
                "dev-2",
                "bot.instructions.set",
                buildJsonObject { put("botId", botId); put("content", "Be helpful") },
            ),
        )
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("saved").jsonPrimitive.boolean)
        server.handle(RelayServerFrame.Route("ig-1", "dev-2", "bot.instructions.get", buildJsonObject { put("botId", botId) }))
        assertEquals("Be helpful", sent.last().getValue("result").jsonObject.getValue("content").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                "is-big",
                "dev-2",
                "bot.instructions.set",
                buildJsonObject { put("botId", botId); put("content", "a".repeat(65_537)) },
            ),
        )
        assertEquals("payload_too_large", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(RelayServerFrame.Route("rel-1", "dev-2", "bot.reload", buildJsonObject { put("botId", botId); put("sessionId", "s-1") }))
        assertEquals("s-1", reloadedTarget)
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("reloaded").jsonPrimitive.boolean)

        server.handle(RelayServerFrame.Route("cmp-1", "dev-2", "session.compact", buildJsonObject { put("botId", botId); put("sessionId", "s-1") }))
        assertEquals(800L, sent.last().getValue("result").jsonObject.getValue("tokensBefore").jsonPrimitive.long)

        server.handle(RelayServerFrame.Route("ret-1", "dev-2", "session.retry", buildJsonObject { put("botId", botId); put("sessionId", "s-1") }))
        assertEquals(1, delegate.retried)
        assertEquals(true, sent.last().getValue("result").jsonObject.getValue("retried").jsonPrimitive.boolean)

        delegate.setBusy(setOf("s-1"))
        server.handle(RelayServerFrame.Route("busy-1", "dev-2", "bot.set_thinking", buildJsonObject { put("botId", botId); put("thinkingLevel", "high") }))
        assertEquals("conflict", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(RelayServerFrame.Route("unk-1", "dev-2", "bot.unknown_method", buildJsonObject { put("botId", botId) }))
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
    }
}

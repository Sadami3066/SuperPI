package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CompactionOutcome
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.MemoryAccessScope
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
        assertEquals("permission_denied", sent.last().getValue("code").jsonPrimitive.content)
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

    @Test
    fun getsSetsBotCapabilitiesAndEmitsSyncEvent() = runTest {
        val ws = temp.newFolder("cap-ws")
        val policyFile = temp.newFile("policies.json")
        val policyStore = WorkspaceToolPolicyStore(policyFile)
        val delegate = FakeAgentRuntime()
        val created = delegate.createWorkspace("CapBot", ws.absolutePath)
        val botId = created.id
        val sent = mutableListOf<JsonObject>()
        val syncEvents = mutableListOf<RelaySyncEvent>()
        val server = RemoteAgentServer(
            host = delegate,
            bots = { listOf(RelayBot("home", "PI"), RelayBot(botId, "CapBot")) },
            send = { sent.add(it); true },
            scope = backgroundScope,
            onSyncEvent = { syncEvents += it },
            workspaceToolPolicies = { policyStore },
            installedCapabilities = {
                RemoteCapabilitiesInventory(
                    skills = listOf("git", "pdf"),
                    plugins = listOf(RemoteCapabilityPluginInfo("p1", "Notes")),
                    mcpServers = listOf("github"),
                    screenToolsReady = true,
                )
            },
        )
        runCurrent()

        server.handle(RelayServerFrame.Route("cg-1", "dev-2", "bot.capabilities.get", buildJsonObject { put("botId", botId) }))
        val getRes = sent.last().getValue("result").jsonObject
        assertEquals(false, getRes.getValue("isHome").jsonPrimitive.boolean)
        assertEquals(false, getRes.getValue("policy").jsonObject.getValue("allCapabilities").jsonPrimitive.boolean)
        assertEquals(2, getRes.getValue("available").jsonObject.getValue("skills").jsonArray.size)
        assertEquals(true, getRes.getValue("available").jsonObject.getValue("screenToolsReady").jsonPrimitive.boolean)

        server.handle(
            RelayServerFrame.Route(
                "cs-1",
                "dev-2",
                "bot.capabilities.set",
                buildJsonObject {
                    put("botId", botId)
                    put(
                        "policy",
                        buildJsonObject {
                            put("allCapabilities", false)
                            put("skills", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("pdf")) })
                            put("plugins", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("p1")) })
                            put("mcpServers", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("github")) })
                            put("screenTools", true)
                            put("miniappTools", true)
                            put("memoryScope", "WORK_ONLY")
                            put("memoryDispatch", true)
                        },
                    )
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        val stored = policyStore.policyForCwd(ws.absolutePath)
        assertEquals(setOf("pdf"), stored.skills)
        assertEquals(setOf("p1"), stored.plugins)
        assertEquals(setOf("github"), stored.mcpServers)
        assertEquals(true, stored.screenTools)
        assertEquals(true, stored.miniappTools)
        assertEquals(MemoryAccessScope.WORK_ONLY, stored.memoryScope)
        assertEquals(true, stored.memoryDispatch)
        assertEquals("capabilities_changed", syncEvents.last().kind)
        assertEquals(botId, syncEvents.last().botId)

        server.handle(RelayServerFrame.Route("cg-2", "dev-2", "bot.capabilities.get", buildJsonObject { put("botId", botId) }))
        val afterPolicy = sent.last().getValue("result").jsonObject.getValue("policy").jsonObject
        assertEquals("WORK_ONLY", afterPolicy.getValue("memoryScope").jsonPrimitive.content)
        assertEquals(true, afterPolicy.getValue("memoryDispatch").jsonPrimitive.boolean)

        server.handle(
            RelayServerFrame.Route(
                "cs-miss",
                "dev-2",
                "bot.capabilities.get",
                buildJsonObject { put("botId", "missing-bot") },
            ),
        )
        assertEquals("bot_not_found", sent.last().getValue("code").jsonPrimitive.content)
    }

    @Test
    fun enrichesPresentLiveEventsAndHistoryAndServesAllowlistedArtifactChunks() = runTest {
        val ws = temp.newFolder("present-ws")
        val img = File(ws, "chart.png").apply { writeBytes(ByteArray(12) { (it + 1).toByte() }) }
        val histFile = File(ws, "notes.txt").apply { writeText("hello remote") }
        val host = FakeAgentRuntime()
        val created = host.createWorkspace("ArtBot", ws.absolutePath)
        val botId = created.id
        host.sessions = listOf(ConversationSummary("s-hist", "Past", 10L, "/s-hist.jsonl", ws.absolutePath))
        host.histories = mapOf(
            "s-hist" to listOf(
                AssistantMessage(id = 1L, role = AssistantRole.USER, text = "show"),
                AssistantMessage(
                    id = 2L,
                    role = AssistantRole.ASSISTANT,
                    text = "",
                    steps = listOf(
                        AssistantStep.Tool(
                            ToolTrace(
                                id = 10L,
                                name = "present",
                                arguments = emptyMap(),
                                status = ToolActivityStatus.DONE,
                                output = """{"items":[{"type":"file","path":"${histFile.name}","name":"notes.txt","mime":"text/plain"}]}""",
                                toolCallId = "call-hist",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = host,
            bots = { listOf(RelayBot("home", "PI"), RelayBot(botId, "ArtBot")) },
            send = { sent.add(it); true },
            scope = backgroundScope,
        )
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                "p-live",
                "dev-2",
                "bot.prompt",
                buildJsonObject { put("botId", botId); put("text", "draw") },
            ),
        )
        val liveSid = sent.last().getValue("result").jsonObject.getValue("sessionId").jsonPrimitive.content
        host.emitFor(
            liveSid,
            RuntimeEvent.ToolFinished(
                runId = "run-live",
                callId = "call-live",
                name = "present",
                result = """{"items":[{"type":"image","path":"${img.absolutePath}","name":"chart.png","mime":"image/png"}]}""",
                isError = false,
                durationMs = 5L,
            ),
        )
        runCurrent()
        val liveEvent = sent.last { it["type"]?.jsonPrimitive?.content == "route_event" }.getValue("event").jsonObject
        val liveItem = kotlinx.serialization.json.Json.parseToJsonElement(
            liveEvent.getValue("result").jsonPrimitive.content,
        ).jsonObject.getValue("items").jsonArray.single().jsonObject
        val liveArtifactId = liveItem.getValue("artifactId").jsonPrimitive.content
        assertEquals(12L, liveItem.getValue("artifactSize").jsonPrimitive.long)

        server.handle(
            RelayServerFrame.Route(
                "art-1",
                "dev-2",
                "artifact.read_chunk",
                buildJsonObject {
                    put("botId", botId)
                    put("artifactId", liveArtifactId)
                    put("offset", 0L)
                    put("length", 64)
                },
            ),
        )
        val chunkOk = sent.last().getValue("result").jsonObject
        assertEquals(true, chunkOk.getValue("eof").jsonPrimitive.boolean)
        val decoded = Base64.getDecoder().decode(chunkOk.getValue("dataBase64").jsonPrimitive.content)
        assertEquals(12, decoded.size)
        assertEquals(1, decoded[0].toInt())

        server.handle(
            RelayServerFrame.Route(
                "art-deny",
                "dev-2",
                "artifact.read_chunk",
                buildJsonObject {
                    put("botId", botId)
                    put("artifactId", "unregistered_secret")
                    put("offset", 0L)
                    put("length", 64)
                },
            ),
        )
        assertEquals("not_found", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                "hist-1",
                "dev-2",
                "bot.history",
                buildJsonObject { put("botId", botId); put("sessionId", "s-hist") },
            ),
        )
        val histMessages = sent.last().getValue("result").jsonObject.getValue("messages").jsonArray
        val toolResultText = histMessages[2].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        val histItem = kotlinx.serialization.json.Json.parseToJsonElement(toolResultText)
            .jsonObject.getValue("items").jsonArray.single().jsonObject
        val histArtifactId = histItem.getValue("artifactId").jsonPrimitive.content
        assertEquals(12L, histItem.getValue("artifactSize").jsonPrimitive.long)

        server.handle(
            RelayServerFrame.Route(
                "art-2",
                "dev-2",
                "artifact.read_chunk",
                buildJsonObject {
                    put("botId", botId)
                    put("artifactId", histArtifactId)
                    put("offset", 0L)
                    put("length", 64)
                },
            ),
        )
        val histBytes = Base64.getDecoder().decode(sent.last().getValue("result").jsonObject.getValue("dataBase64").jsonPrimitive.content)
        assertEquals("hello remote", String(histBytes, Charsets.UTF_8))
    }

    @Test
    fun expandsRemoteSkillPromptsOnCalleeAndReloadsIdleSessionOnCapabilitiesChange() = runTest {
        val ws = temp.newFolder("skill-ws")
        val policyFile = File(temp.newFolder("skill-policy"), "policies.json")
        val policyStore = WorkspaceToolPolicyStore(policyFile)
        val delegate = FakeAgentRuntime()
        val created = delegate.createWorkspace("SkillBot", ws.absolutePath)
        val botId = created.id
        val reloadedSessionIds = mutableListOf<String>()
        val host = object : AgentRuntime by delegate {
            override suspend fun reloadSession(sessionId: String): String {
                reloadedSessionIds += sessionId
                return sessionId
            }
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = host,
            bots = { listOf(RelayBot("home", "PI"), RelayBot(botId, "SkillBot")) },
            send = { sent.add(it); true },
            scope = backgroundScope,
            workspaceToolPolicies = { policyStore },
            expandSkillPrompt = { cwd, rawText ->
                assertEquals(ws.absolutePath, cwd)
                if (rawText.startsWith("/skill:missing")) {
                    throw IllegalArgumentException("Skill not found: missing")
                }
                if (rawText.startsWith("/skill:writer ")) {
                    "<skill name=\"writer\">Write concisely</skill>\n\n" + rawText.removePrefix("/skill:writer ")
                } else {
                    rawText
                }
            },
        )
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                "sk-miss",
                "dev-2",
                "bot.prompt",
                buildJsonObject {
                    put("botId", botId)
                    put("text", "/skill:missing hello")
                },
            ),
        )
        assertEquals("route_error", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
        assertTrue(delegate.sent.isEmpty())

        server.handle(
            RelayServerFrame.Route(
                "sk-ok",
                "dev-2",
                "bot.prompt",
                buildJsonObject {
                    put("botId", botId)
                    put("text", "/skill:writer draft memo")
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        val sessionId = sent.last().getValue("result").jsonObject.getValue("sessionId").jsonPrimitive.content
        assertEquals("<skill name=\"writer\">Write concisely</skill>\n\ndraft memo", delegate.sent.single())

        delegate.emitFor(sessionId, RuntimeEvent.RunFinished("r-1", "done", RunStatus.DONE, 200L))
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                "cap-reload",
                "dev-2",
                "bot.capabilities.set",
                buildJsonObject {
                    put("botId", botId)
                    put(
                        "policy",
                        buildJsonObject {
                            put("allCapabilities", true)
                            put("screenTools", true)
                            put("miniappTools", false)
                        },
                    )
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals(listOf(sessionId), reloadedSessionIds)
    }

    @Test
    fun bindsCallerRouteWhenSessionIsAlreadyBusySignalsSeqResetAfterRestartAndPrunesOfflinePeers() = runTest {
        val host = FakeAgentRuntime().apply {
            sessions = listOf(
                ConversationSummary("s-shared", "Shared", 100L, "/shared.jsonl", "/home"),
                ConversationSummary("s-restarted", "Restarted", 100L, "/restarted.jsonl", "/home"),
            )
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(host, { defaultBots }, { sent.add(it); true }, backgroundScope)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-caller-a",
                from = "phone-a",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-shared")
                    put("text", "first prompt")
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)

        sent.clear()
        server.handle(
            RelayServerFrame.Route(
                id = "r-caller-b",
                from = "phone-b",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-shared")
                    put("text", "second prompt")
                },
            ),
        )
        assertEquals(listOf("first prompt"), host.sent)
        assertEquals(listOf("second prompt"), host.followUps)
        assertTrue(
            sent.any { frame ->
                frame["type"]?.jsonPrimitive?.content == "route_ok" &&
                    frame["id"]?.jsonPrimitive?.content == "r-caller-b"
            },
        )
        assertTrue(
            sent.any { frame ->
                frame["type"]?.jsonPrimitive?.content == "route_event" &&
                    frame["id"]?.jsonPrimitive?.content == "r-caller-a" &&
                    frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "queue_update"
            },
        )
        assertFalse(
            sent.any { frame ->
                frame["type"]?.jsonPrimitive?.content == "route_event" &&
                    frame["id"]?.jsonPrimitive?.content == "r-caller-b" &&
                    frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "queue_update"
            },
        )

        sent.clear()
        host.emitFor("s-shared", RuntimeEvent.TextDelta("run-1", "done both"))
        host.emitFor("s-shared", RuntimeEvent.RunFinished("run-1", "done both", RunStatus.DONE, 200L))
        runCurrent()
        assertTrue(
            sent.any { frame ->
                frame["id"]?.jsonPrimitive?.content == "r-caller-b" &&
                    frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "agent_settled"
            },
        )

        sent.clear()
        server.handle(
            RelayServerFrame.Route(
                id = "r-stale-resume",
                from = "phone-a",
                method = "bot.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("sessionId", "s-restarted")
                    put("afterSeq", 1500L)
                },
            ),
        )
        val resumeOk = sent.single().getValue("result").jsonObject
        assertEquals(true, resumeOk.getValue("reset").jsonPrimitive.boolean)
        assertEquals(-1L, resumeOk.getValue("seq").jsonPrimitive.long)
        assertEquals(true, resumeOk.getValue("gap").jsonPrimitive.boolean)

        sent.clear()
        host.emitFor("s-restarted", RuntimeEvent.RunStarted("run-2", 300L, "after restart"))
        host.emitFor("s-restarted", RuntimeEvent.RunFinished("run-2", "ok", RunStatus.DONE, 400L))
        runCurrent()
        assertTrue(
            sent.any { frame ->
                frame["id"]?.jsonPrimitive?.content == "r-stale-resume" &&
                    frame["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "agent_start"
            },
        )

        sent.clear()
        server.onPeerOffline("phone-a")
        host.emitFor("s-restarted", RuntimeEvent.RunStarted("run-3", 500L, "no offline send"))
        runCurrent()
        assertFalse(sent.any { it["id"]?.jsonPrimitive?.content == "r-stale-resume" })
    }

    @Test
    fun appliesOptionalModelOnNewSessionPromptValidatesProvidersAndReplaysLiveBufferedTools() = runTest {
        val host = FakeAgentRuntime()
        val ws = temp.newFolder("prompt-model-ws")
        val created = host.createWorkspace("WorkBot", ws.absolutePath)
        val botId = created.id
        val catalog = object : ProviderDirectory {
            override val summaries = listOf(ProviderSummary("openai", "OpenAI", 1, null, false, true))
            override fun summary(providerId: String) = summaries.firstOrNull { it.id == providerId }
            override fun models(providerId: String) = when (providerId) {
                "openai" -> listOf(ModelOption("openai", "gpt-5", "GPT-5", listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH)))
                else -> emptyList()
            }
            override fun resolveConfig(config: ModelConfig) = config
            override fun sdkModel(providerId: String, modelId: String): Model? = null
            override fun modelReference(providerId: String, modelId: String) = "$providerId/$modelId"
        }
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = host,
            bots = { listOf(RelayBot("home", "PI"), RelayBot(botId, "WorkBot")) },
            send = { sent.add(it); true },
            scope = backgroundScope,
            providerCatalog = { catalog },
            configuredProviderIds = { setOf("openai") },
        )
        runCurrent()

        val initialNewCalls = host.newSessionCalls.size
        server.handle(
            RelayServerFrame.Route(
                id = "p-bad-prov",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", botId)
                    put("provider", "anthropic")
                    put("modelId", "claude-sonnet")
                    put("text", "hi")
                },
            ),
        )
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
        assertEquals(initialNewCalls, host.newSessionCalls.size)

        server.handle(
            RelayServerFrame.Route(
                id = "p-bad-model",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", botId)
                    put("provider", "openai")
                    put("modelId", "gpt-unknown")
                    put("text", "hi")
                },
            ),
        )
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
        assertEquals(initialNewCalls, host.newSessionCalls.size)

        server.handle(
            RelayServerFrame.Route(
                id = "p-bad-think",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", botId)
                    put("provider", "openai")
                    put("modelId", "gpt-5")
                    put("thinkingLevel", "galaxy")
                    put("text", "hi")
                },
            ),
        )
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)
        assertEquals(initialNewCalls, host.newSessionCalls.size)

        host.onSend = { sid, _ ->
            host.setBusy(setOf(sid))
        }
        server.handle(
            RelayServerFrame.Route(
                id = "p-ok",
                from = "dev-2",
                method = "bot.prompt",
                params = buildJsonObject {
                    put("botId", botId)
                    put("provider", "openai")
                    put("modelId", "gpt-5")
                    put("thinkingLevel", "high")
                    put("text", "run tools")
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        val sid = sent.last().getValue("result").jsonObject.getValue("sessionId").jsonPrimitive.content
        assertEquals("openai", host.sessionModels[sid]?.providerId)
        assertEquals("gpt-5", host.sessionModels[sid]?.modelId)
        assertEquals(ThinkingLevel.HIGH, host.sessionModels[sid]?.thinkingLevel)

        server.handle(
            RelayServerFrame.Route(
                id = "sm-unconf",
                from = "dev-2",
                method = "bot.set_model",
                params = buildJsonObject {
                    put("botId", botId)
                    put("sessionId", sid)
                    put("provider", "anthropic")
                    put("modelId", "claude-sonnet")
                },
            ),
        )
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "sm-busy",
                from = "dev-2",
                method = "bot.set_model",
                params = buildJsonObject {
                    put("botId", botId)
                    put("sessionId", sid)
                    put("provider", "openai")
                    put("modelId", "gpt-5")
                },
            ),
        )
        assertEquals("conflict", sent.last().getValue("code").jsonPrimitive.content)

        host.emitFor(sid, RuntimeEvent.RunStarted("r-live", 10L, "run tools"))
        host.emitFor(
            sid,
            RuntimeEvent.ToolStarted(
                runId = "r-live",
                callId = "call-live-1",
                name = "bash",
                arguments = mapOf("command" to "pwd"),
                startedAt = 11L,
            ),
        )
        host.emitFor(
            sid,
            RuntimeEvent.ToolFinished(
                runId = "r-live",
                callId = "call-live-1",
                name = "bash",
                result = "/home",
                isError = false,
                durationMs = 5L,
            ),
        )
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "hist-live",
                from = "dev-2",
                method = "bot.history",
                params = buildJsonObject {
                    put("botId", botId)
                    put("sessionId", sid)
                },
            ),
        )
        val histMessages = sent.last().getValue("result").jsonObject.getValue("messages").jsonArray
        assertTrue(
            histMessages.any { msg ->
                val obj = msg.jsonObject
                obj["role"]?.jsonPrimitive?.content == "assistant" &&
                    obj["content"]?.jsonArray?.any { blk ->
                        blk.jsonObject["type"]?.jsonPrimitive?.content == "toolCall" &&
                            blk.jsonObject["id"]?.jsonPrimitive?.content == "call-live-1"
                    } == true
            },
        )
        assertTrue(
            histMessages.any { msg ->
                val obj = msg.jsonObject
                obj["role"]?.jsonPrimitive?.content == "toolResult" &&
                    obj["toolCallId"]?.jsonPrimitive?.content == "call-live-1" &&
                    obj["toolName"]?.jsonPrimitive?.content == "bash"
            },
        )
    }

    @Test
    fun validatesStrictMemoryScopeNullResetAndIntroSerialization() = runTest {
        val ws = temp.newFolder("cap-strict-ws")
        val policyFile = temp.newFile("strict-policies.json")
        val policyStore = WorkspaceToolPolicyStore(policyFile)
        val delegate = FakeAgentRuntime()
        val created = delegate.createWorkspace("IntroBot", ws.absolutePath, intro = "Initial bio")
        val botId = created.id
        val sent = mutableListOf<JsonObject>()
        val server = RemoteAgentServer(
            host = delegate,
            bots = { listOf(RelayBot("home", "PI"), RelayBot(botId, created.name, intro = created.intro.orEmpty())) },
            send = { sent.add(it); true },
            scope = backgroundScope,
            workspaceToolPolicies = { policyStore },
        )
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "c-create",
                from = "dev-2",
                method = "bot.create",
                params = buildJsonObject {
                    put("name", "Researcher")
                    put("intro", "Finds papers")
                },
            ),
        )
        val createBot = sent.last().getValue("result").jsonObject.getValue("bot").jsonObject
        assertEquals("Finds papers", createBot.getValue("intro").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "c-update",
                from = "dev-2",
                method = "bot.update",
                params = buildJsonObject {
                    put("botId", botId)
                    put("intro", "Updated bio")
                },
            ),
        )
        val updatedBot = sent.last().getValue("result").jsonObject.getValue("bot").jsonObject
        assertEquals("Updated bio", updatedBot.getValue("intro").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "cap-bad-scope",
                from = "dev-2",
                method = "bot.capabilities.set",
                params = buildJsonObject {
                    put("botId", botId)
                    put("policy", buildJsonObject { put("memoryScope", "INVALID_SCOPE") })
                },
            ),
        )
        assertEquals("bad_request", sent.last().getValue("code").jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "cap-off",
                from = "dev-2",
                method = "bot.capabilities.set",
                params = buildJsonObject {
                    put("botId", botId)
                    put(
                        "policy",
                        buildJsonObject {
                            put("memoryScope", "OFF")
                            put("memoryDispatch", true)
                        },
                    )
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        val offStored = policyStore.policyForCwd(ws.absolutePath)
        assertEquals(MemoryAccessScope.OFF, offStored.memoryScope)
        assertEquals(false, offStored.memoryDispatch)

        server.handle(
            RelayServerFrame.Route(
                id = "cap-null-reset",
                from = "dev-2",
                method = "bot.capabilities.set",
                params = buildJsonObject {
                    put("botId", botId)
                    put("policy", buildJsonObject { put("memoryScope", kotlinx.serialization.json.JsonNull) })
                },
            ),
        )
        assertEquals("route_ok", sent.last().getValue("type").jsonPrimitive.content)
        assertEquals(null, policyStore.policyForCwd(ws.absolutePath).memoryScope)
    }

    @Test
    fun historyPreservesLivePostToolTextAndResolvesCommittedToolName() = runTest {
        val ws = temp.newFolder("hist-live-ws")
        val sid = "s-live-post"
        val host = FakeAgentRuntime().apply {
            val bot = createWorkspace("LiveBot", ws.absolutePath)
            sessions = listOf(ConversationSummary(sid, "Live", 10L, "/live.jsonl", ws.absolutePath))
            histories = mapOf(
                sid to listOf(
                    AssistantMessage(id = 1L, role = AssistantRole.USER, text = "Check files"),
                    AssistantMessage(
                        id = 2L,
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        steps = listOf(
                            AssistantStep.Text(10L, "Checking now."),
                            AssistantStep.Tool(
                                ToolTrace(
                                    id = 11L,
                                    name = "bash",
                                    arguments = mapOf("command" to "ls"),
                                    output = "ok",
                                    status = ToolActivityStatus.DONE,
                                    toolCallId = "call-done",
                                ),
                            ),
                            AssistantStep.Tool(
                                ToolTrace(
                                    id = 12L,
                                    name = "read",
                                    arguments = mapOf("path" to "a.txt"),
                                    status = ToolActivityStatus.RUNNING,
                                    toolCallId = "call-pending-name",
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val sent = mutableListOf<JsonObject>()
            val server = RemoteAgentServer(
                host = this,
                bots = { listOf(RelayBot(bot.id, bot.name)) },
                send = { sent.add(it); true },
                scope = backgroundScope,
            )
            runCurrent()

            emitFor(sid, RuntimeEvent.RunStarted("r-1", 100L, "Checking"))
            emitFor(sid, RuntimeEvent.ReasoningDelta("r-1", "Need final summary"))
            emitFor(sid, RuntimeEvent.TextDelta("r-1", "After tool answer"))
            emitFor(
                sid,
                RuntimeEvent.ToolFinished(
                    runId = "r-1",
                    callId = "call-pending-name",
                    name = "",
                    result = "file body",
                    isError = false,
                    durationMs = 4L,
                ),
            )
            emitFor(sid, RuntimeEvent.TextDelta("r-1", "Final tail"))
            runCurrent()

            server.handle(
                RelayServerFrame.Route(
                    id = "hist-post-tool",
                    from = "dev-2",
                    method = "bot.history",
                    params = buildJsonObject {
                        put("botId", bot.id)
                        put("sessionId", sid)
                    },
                ),
            )
            val messages = sent.last().getValue("result").jsonObject.getValue("messages").jsonArray
            assertTrue(
                messages.any { msg ->
                    val obj = msg.jsonObject
                    obj["role"]?.jsonPrimitive?.content == "toolResult" &&
                        obj["toolCallId"]?.jsonPrimitive?.content == "call-pending-name" &&
                        obj["toolName"]?.jsonPrimitive?.content == "read"
                },
            )
            assertTrue(
                messages.any { msg ->
                    val obj = msg.jsonObject
                    obj["role"]?.jsonPrimitive?.content == "assistant" &&
                        obj["content"]?.jsonArray?.any { blk ->
                            blk.jsonObject["type"]?.jsonPrimitive?.content == "text" &&
                                blk.jsonObject["text"]?.jsonPrimitive?.content == "Final tail"
                        } == true
                },
            )
            assertTrue(
                messages.any { msg ->
                    val obj = msg.jsonObject
                    obj["role"]?.jsonPrimitive?.content == "assistant" &&
                        obj["content"]?.jsonArray?.any { blk ->
                            blk.jsonObject["type"]?.jsonPrimitive?.content == "thinking" &&
                                blk.jsonObject["thinking"]?.jsonPrimitive?.content == "Need final summary"
                        } == true
                },
            )
        }
        assertTrue(host.sessions.isNotEmpty())
    }

    @Test
    fun botHistoryCapsOversizedToolOutputAndDropsOldestMessagesWithinWireBudget() = runTest {
        val ws = temp.newFolder("hist-budget-ws")
        val sid = "s-budget"
        val bigToolOut = "A".repeat(200_000)
        val manyMessages = buildList {
            for (i in 1..22) {
                add(AssistantMessage(id = (i * 2 - 1).toLong(), role = AssistantRole.USER, text = "Q$i"))
                add(
                    AssistantMessage(
                        id = (i * 2).toLong(),
                        role = AssistantRole.ASSISTANT,
                        text = "",
                        steps = listOf(
                            AssistantStep.Tool(
                                ToolTrace(
                                    id = (i * 100).toLong(),
                                    name = "read",
                                    arguments = mapOf("path" to "f$i.txt"),
                                    output = bigToolOut,
                                    status = ToolActivityStatus.DONE,
                                    toolCallId = "tc-$i",
                                ),
                            ),
                            AssistantStep.Text((i * 100 + 1).toLong(), if (i == 22) "Newest answer" else "Mid answer $i"),
                        ),
                    ),
                )
            }
        }
        FakeAgentRuntime().apply {
            val bot = createWorkspace("BudgetBot", ws.absolutePath)
            sessions = listOf(ConversationSummary(sid, "Budget", 10L, "/budget.jsonl", ws.absolutePath))
            histories = mapOf(sid to manyMessages)
            val sent = mutableListOf<JsonObject>()
            val server = RemoteAgentServer(
                host = this,
                bots = { listOf(RelayBot(bot.id, bot.name)) },
                send = { sent.add(it); true },
                scope = backgroundScope,
            )
            runCurrent()

            server.handle(
                RelayServerFrame.Route(
                    id = "hist-budget",
                    from = "dev-2",
                    method = "bot.history",
                    params = buildJsonObject {
                        put("botId", bot.id)
                        put("sessionId", sid)
                    },
                ),
            )
            val result = sent.last().getValue("result").jsonObject
            assertEquals(true, result.getValue("truncated").jsonPrimitive.boolean)
            assertTrue(result.toString().length <= 2_700_000)
            val serializedText = result.getValue("messages").toString()
            assertTrue(serializedText.contains("Newest answer"))
            assertTrue(serializedText.contains("...[truncated]"))
        }
    }
}

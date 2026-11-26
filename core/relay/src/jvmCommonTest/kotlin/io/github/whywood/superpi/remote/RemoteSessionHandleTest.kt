package io.github.whywood.superpi.remote

import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RouteImagePart
import io.github.whywood.superpi.runtime.BackgroundTurnStarted
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.RunStatus
import io.github.whywood.superpi.runtime.RuntimeEvent
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionRuntimeEvent
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asLlmMessage
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.BlockContent
import io.github.whywood.superpi.sdk.ai.MessageContent
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionHandleTest {
    private lateinit var root: File
    private lateinit var cwd: File
    private lateinit var agentDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("remote-session").toFile()
        cwd = File(root, "remote/device-1/pi")
        agentDir = File(root, "agent")
        cwd.mkdirs()
        agentDir.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun promptMirrorsStreamingTurnAndSettles() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        assertEquals(1, transport.calls.size)
        val call = transport.calls.single()
        assertEquals("bot.prompt", call.method)
        assertEquals(FakeRemoteTransport.DEVICE_ID, call.to)
        assertEquals("pi", call.params.getValue("botId").jsonPrimitive.content)
        assertEquals("hello", call.params.getValue("text").jsonPrimitive.content)
        assertNull(call.params["sessionId"])
        assertTrue(handle.isBusy)

        transport.emit(call.routeId, 0, FakeEvents.agentStart())
        transport.emit(call.routeId, 1, FakeEvents.textDelta("Hel"))
        transport.emit(call.routeId, 2, FakeEvents.textDelta("lo"))
        transport.emit(call.routeId, 3, FakeEvents.toolCall("call-1", "shell"))
        runCurrent()
        assertTrue(handle.isBusy)

        transport.emit(call.routeId, 4, FakeEvents.toolResult("call-1", "a.txt"))
        transport.emit(call.routeId, 5, FakeEvents.agentEnd("done"))
        transport.emit(call.routeId, 6, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(handle.isBusy)

        val agentEvents = received.filterIsInstance<SessionEvent.AgentEventReceived>().map { it.event }
        val deltas = agentEvents.filterIsInstance<AgentEvent.MessageUpdate>()
            .mapNotNull { (it.assistantMessageEvent as? TextDeltaEvent)?.delta }
        assertEquals(listOf("Hel", "lo"), deltas)
        assertTrue(agentEvents.any { it is AgentEvent.ToolExecutionStart })
        assertTrue(agentEvents.any { it is AgentEvent.ToolExecutionEnd })
        assertTrue(agentEvents.any { it is AgentEvent.AgentStart })
        assertTrue(agentEvents.any { it is AgentEvent.AgentEnd })

        assertEquals("Hello", handle.lastAssistantText())
        val messages = handle.messages.mapNotNull { it.asLlmMessage() }
        assertEquals(3, messages.size)
        assertTrue(messages[0] is UserMessage)
        val assistant = messages[1] as AssistantMessage
        assertEquals(StopReason.Stop, assistant.stopReason)
        assertEquals("Hello", assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text })
        val toolCall = assistant.content.filterIsInstance<ToolCall>().single()
        assertEquals("call-1", toolCall.id)
        assertEquals("shell", toolCall.name)
        val toolResult = messages[2] as ToolResultMessage
        assertEquals("call-1", toolResult.toolCallId)
        assertEquals("a.txt", toolResult.content.filterIsInstance<TextContent>().joinToString("") { it.text })
        assertFalse(toolResult.isError)

        val history = handle.history()
        assertEquals(AssistantRole.USER, history.first().role)
        val assistantHistory = history.first { it.role == AssistantRole.ASSISTANT }
        val historyText = assistantHistory.text + assistantHistory.steps.filterIsInstance<AssistantStep.Text>()
            .joinToString("") { step -> step.text }
        assertTrue(historyText.contains("Hello"))
        assertTrue(assistantHistory.steps.filterIsInstance<AssistantStep.Tool>().any { step -> step.trace.name == "shell" })

        val file = File(handle.sessionFile!!)
        assertTrue(file.isFile)
        assertTrue(file.readLines().any { it.contains("superpi.remote") && it.contains(FakeRemoteTransport.PEER_SESSION_ID) })

        val next = launch { handle.prompt("again") }
        runCurrent()
        val second = transport.calls[1]
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, second.params.getValue("sessionId").jsonPrimitive.content)
        transport.emit(second.routeId, 0, FakeEvents.textDelta("ok"))
        transport.emit(second.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(next.isCompleted)
    }

    @Test
    fun steerAndFollowUpCallRemoteMethods() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }
        finishPrompt(scope = this, transport = transport, handle = handle, text = "hello", reply = "hi")

        val turn = launch { handle.prompt("working") }
        runCurrent()
        val promptCall = transport.calls.last()
        assertEquals("bot.prompt", promptCall.method)

        handle.prompt("stop that", behavior = QueueBehavior.Steer)
        handle.prompt("then this", behavior = QueueBehavior.FollowUp)
        runCurrent()

        val steer = transport.calls.first { it.method == "bot.steer" }
        assertEquals("pi", steer.params.getValue("botId").jsonPrimitive.content)
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, steer.params.getValue("sessionId").jsonPrimitive.content)
        assertEquals("stop that", steer.params.getValue("text").jsonPrimitive.content)

        val followUp = transport.calls.first { it.method == "bot.follow_up" }
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, followUp.params.getValue("sessionId").jsonPrimitive.content)
        assertEquals("then this", followUp.params.getValue("text").jsonPrimitive.content)

        val steered = received.filterIsInstance<SessionEvent.AgentEventReceived>()
            .map { it.event }
            .filterIsInstance<AgentEvent.MessageStart>()
            .mapNotNull { (it.message.asLlmMessage() as? UserMessage)?.content?.text() }
        assertEquals(listOf("stop that", "then this"), steered)

        transport.emit(promptCall.routeId, 0, FakeEvents.textDelta("done"))
        transport.emit(promptCall.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(turn.isCompleted)
        val userTexts = handle.messages.mapNotNull { it.asLlmMessage() as? UserMessage }
            .map { message -> message.content.text() }
        assertEquals(listOf("hello", "working", "stop that", "then this"), userTexts)
        assertTrue(handle.messages.last().asLlmMessage() is AssistantMessage)
    }

    @Test
    fun abortSendsRemoteAbortAndStopsTurn() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("long turn") }
        runCurrent()
        val call = transport.calls.single()
        transport.emit(call.routeId, 0, FakeEvents.textDelta("partial"))
        runCurrent()

        handle.abort()
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(handle.isBusy)
        val abort = transport.calls.first { it.method == "bot.abort" }
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, abort.params.getValue("sessionId").jsonPrimitive.content)

        val assistant = handle.messages.mapNotNull { it.asLlmMessage() }.filterIsInstance<AssistantMessage>().last()
        assertEquals(StopReason.Aborted, assistant.stopReason)
        assertEquals("partial", assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text })
        assertTrue(received.filterIsInstance<SessionEvent.SessionError>().isEmpty())
    }

    @Test
    fun offlinePromptEmitsSessionError() = runTest {
        val transport = FakeRemoteTransport(online = false)
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        handle.prompt("hello")

        assertTrue(transport.calls.isEmpty())
        assertFalse(handle.isBusy)
        val error = received.filterIsInstance<SessionEvent.SessionError>().single()
        assertTrue(error.message.contains("offline"))
    }

    @Test
    fun failureEventFailsTurnAndMirrorsPartial() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        val call = transport.calls.single()
        transport.emit(call.routeId, 0, FakeEvents.textDelta("partial"))
        transport.emit(call.routeId, 1, FakeEvents.failure("model exploded"))
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("model exploded", received.filterIsInstance<SessionEvent.SessionError>().single().message)
        val assistant = handle.messages.mapNotNull { it.asLlmMessage() }
            .filterIsInstance<AssistantMessage>().last()
        assertEquals(StopReason.Error, assistant.stopReason)
        assertEquals("model exploded", assistant.errorMessage)
        assertEquals("partial", assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text })
    }

    @Test
    fun duplicateAndForeignRouteEventsAreDeliveredOnce() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        val call = transport.calls.single()

        val delta = FakeEvents.textDelta("x")
        transport.emit(call.routeId, 0, delta)
        transport.emit(call.routeId, 0, delta)
        transport.emit("another-route", 0, FakeEvents.textDelta("foreign"))
        runCurrent()

        val deltas = received.filterIsInstance<SessionEvent.AgentEventReceived>()
            .map { it.event }
            .filterIsInstance<AgentEvent.MessageUpdate>()
            .mapNotNull { (it.assistantMessageEvent as? TextDeltaEvent)?.delta }
        assertEquals(listOf("x"), deltas)

        transport.emit(call.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun turnTimesOutWithSessionError() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        advanceTimeBy(10 * 60 * 1000L + 1)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(
            "The remote bot did not finish the turn.",
            received.filterIsInstance<SessionEvent.SessionError>().single().message,
        )
        assertFalse(handle.isBusy)
    }

    @Test
    fun reopenedMirrorRestoresHistoryAndPeerSession() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val first = engine.create(null, ModelConfig())
        finishPrompt(scope = this, transport = transport, handle = first, text = "hello", reply = "hi there")
        first.appendTitle("Remote chat")
        val file = first.sessionFile!!
        first.close()

        val reopened = engine.create(file, ModelConfig())
        assertEquals(first.sessionId, reopened.sessionId)
        assertEquals("Remote chat", reopened.sessionName())
        assertEquals("hi there", reopened.lastAssistantText())
        assertEquals(2, reopened.messages.size)
        val history = reopened.history()
        assertEquals(AssistantRole.USER, history.first().role)
        assertTrue(history.any { it.role == AssistantRole.ASSISTANT && it.text.contains("hi there") })

        val job = launch { reopened.prompt("next") }
        runCurrent()
        val call = transport.calls.last()
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, call.params.getValue("sessionId").jsonPrimitive.content)
        transport.emit(call.routeId, 0, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun connectionLossEndsTurnWithSessionError() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        val call = transport.calls.single()
        transport.emit(call.routeId, 0, FakeEvents.textDelta("partial"))
        runCurrent()
        assertTrue(handle.isBusy)

        transport.setOnline(false)
        testScheduler.advanceTimeBy(25_000L)
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(handle.isBusy)
        val error = received.filterIsInstance<SessionEvent.SessionError>().single()
        assertTrue(error.message.contains("Lost connection"))
        assertTrue(error.message.contains("Desktop"))
        val assistant = handle.messages.mapNotNull { it.asLlmMessage() }.filterIsInstance<AssistantMessage>().last()
        assertEquals(StopReason.Error, assistant.stopReason)
        assertEquals("partial", assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text })
    }

    @Test
    fun briefConnectionDropResumesStreamingViaBotResume() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }

        val job = launch { handle.prompt("hello") }
        runCurrent()
        val promptCall = transport.calls.single()
        transport.emit(promptCall.routeId, 0, FakeEvents.textDelta("part-1 "))
        runCurrent()
        assertTrue(handle.isBusy)

        transport.setOnline(false)
        testScheduler.advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(job.isCompleted)

        transport.result = { buildJsonObject { put("sessionId", FakeRemoteTransport.PEER_SESSION_ID); put("busy", true) } }
        transport.setOnline(true)
        runCurrent()

        val resumeCall = transport.calls.last()
        assertEquals("bot.resume", resumeCall.method)
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, resumeCall.params.getValue("sessionId").jsonPrimitive.content)
        assertEquals("0", resumeCall.params.getValue("afterSeq").jsonPrimitive.content)

        transport.emit(resumeCall.routeId, 1, FakeEvents.textDelta("part-2"))
        transport.emit(resumeCall.routeId, 2, FakeEvents.settled())
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(handle.isBusy)
        assertTrue(received.filterIsInstance<SessionEvent.SessionError>().isEmpty())
        val assistant = handle.messages.mapNotNull { it.asLlmMessage() }.filterIsInstance<AssistantMessage>().last()
        assertEquals(StopReason.Stop, assistant.stopReason)
        assertEquals("part-1 part-2", assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text })
    }

    @Test
    fun multiStepToolTurnPersistsInterleavedMessagesInMirror() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        val job = launch { handle.prompt("check files") }
        runCurrent()
        val call = transport.calls.single()
        transport.emit(call.routeId, 0, FakeEvents.textDelta("Checking."))
        transport.emit(call.routeId, 1, FakeEvents.toolCall("call-1", "ls", buildJsonObject { put("path", ".") }))
        transport.emit(call.routeId, 2, FakeEvents.toolResult("call-1", "README.md"))
        transport.emit(call.routeId, 3, FakeEvents.textDelta("Done."))
        transport.emit(call.routeId, 4, FakeEvents.settled())
        runCurrent()

        assertTrue(job.isCompleted)
        val roles = handle.messages.map { it.role }
        assertEquals(listOf("user", "assistant", "toolResult", "assistant"), roles)
    }

    @Test
    fun sessionNotFoundClearsMappingAndRetriesOnce() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val first = engine.create(null, ModelConfig())
        finishPrompt(scope = this, transport = transport, handle = first, text = "hello", reply = "hi")
        val file = first.sessionFile!!
        first.close()
        transport.calls.clear()

        val reopened = engine.create(file, ModelConfig())
        transport.result = { buildJsonObject { put("sessionId", "peer-2") } }
        transport.failNext = RemoteCallError("session_not_found", "the session is gone")

        val job = launch { reopened.prompt("next") }
        runCurrent()

        val prompts = transport.calls.filter { it.method == "bot.prompt" }
        assertEquals(2, prompts.size)
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, prompts[0].params.getValue("sessionId").jsonPrimitive.content)
        assertNull(prompts[1].params["sessionId"])

        val retry = prompts[1]
        transport.emit(retry.routeId, 0, FakeEvents.textDelta("fresh"))
        transport.emit(retry.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)
        assertFalse(reopened.isBusy)

        val lines = File(reopened.sessionFile!!).readLines()
        assertTrue(lines.any { it.contains("\"peerSessionId\":null") })
        assertTrue(lines.any { it.contains("\"peerSessionId\":\"peer-2\"") })

        val next = launch { reopened.prompt("again") }
        runCurrent()
        val nextPrompt = transport.calls.last()
        assertEquals("peer-2", nextPrompt.params.getValue("sessionId").jsonPrimitive.content)
        transport.emit(nextPrompt.routeId, 0, FakeEvents.settled())
        runCurrent()
        assertTrue(next.isCompleted)
    }

    @Test
    fun staleAbortDoesNotLeaveHandleBusy() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        finishPrompt(scope = this, transport = transport, handle = handle, text = "hello", reply = "hi")

        val turn = launch { handle.prompt("working") }
        runCurrent()
        transport.failNext = RemoteCallError("session_not_found", "gone")
        handle.abort()
        runCurrent()

        assertTrue(turn.isCompleted)
        assertFalse(handle.isBusy)
        assertTrue(transport.calls.any { it.method == "bot.abort" })

        transport.result = { buildJsonObject { put("sessionId", "peer-2") } }
        val next = launch { handle.prompt("fresh") }
        runCurrent()
        val fresh = transport.calls.last()
        assertNull(fresh.params["sessionId"])
        transport.emit(fresh.routeId, 0, FakeEvents.settled())
        runCurrent()
        assertTrue(next.isCompleted)
    }

    @Test
    fun staleSteerEndsActiveTurnWithSessionError() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val received = mutableListOf<SessionEvent>()
        handle.subscribe { event -> received += event }
        finishPrompt(scope = this, transport = transport, handle = handle, text = "hello", reply = "hi")

        val turn = launch { handle.prompt("working") }
        runCurrent()
        transport.failNext = RemoteCallError("session_not_found", "gone")
        handle.prompt("adjust", behavior = QueueBehavior.Steer)
        runCurrent()

        assertTrue(turn.isCompleted)
        assertFalse(handle.isBusy)
        val error = received.filterIsInstance<SessionEvent.SessionError>().single()
        assertTrue(error.message.contains("restarted"))

        transport.result = { buildJsonObject { put("sessionId", "peer-2") } }
        val next = launch { handle.prompt("fresh") }
        runCurrent()
        assertNull(transport.calls.last().params["sessionId"])
        transport.emit(transport.calls.last().routeId, 0, FakeEvents.settled())
        runCurrent()
        assertTrue(next.isCompleted)
    }

    @Test
    fun promptForwardsImagesAndHydratesRemoteHistoryIntoMirror() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())
        val image = io.github.whywood.superpi.runtime.ImagePart(data = "YWJj", mimeType = "image/png")

        val job = launch { handle.prompt("see image", images = listOf(image)) }
        runCurrent()
        val call = transport.calls.single()
        val sentImages = call.params["images"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, sentImages.size)
        assertEquals("image/png", sentImages[0].jsonObject.getValue("mimeType").jsonPrimitive.content)
        transport.emit(call.routeId, 0, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)

        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        val dto = io.github.whywood.superpi.data.relay.RemoteHistoryDto(
            sessionId = "peer-history-1",
            title = "Desktop Thread",
            busy = false,
            seq = null,
            messages = listOf(
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", "Describe image") })
                            add(buildJsonObject { put("type", "image"); put("data", "YWJj"); put("mimeType", "image/jpeg") })
                        },
                    )
                },
                buildJsonObject {
                    put("role", "assistant")
                    put(
                        "content",
                        kotlinx.serialization.json.buildJsonArray {
                            add(buildJsonObject { put("type", "thinking"); put("thinking", "Inspecting") })
                            add(buildJsonObject { put("type", "text"); put("text", "Looks clean") })
                        },
                    )
                },
            ),
        )
        mirror.hydrateRemoteHistory("device-1", "pi", dto)
        assertEquals("peer-history-1", mirror.peerSessionId)
        assertEquals("Desktop Thread", mirror.name())
        assertEquals(2, mirror.messages().size)
        mirror.close()
    }

    @Test
    fun attachesToBusyRemoteTurnRoutesQuestionsToQuestionCenterAndCancelsOnResolved() = runTest {
        val transport = FakeRemoteTransport()
        val questionCenter = io.github.whywood.superpi.runtime.QuestionCenter(persistScope = backgroundScope)
        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        mirror.appendRemoteMapping("device-1", "pi", "peer-busy-1", historySeq = 2L)
        mirror.appendUserMessage("Ask me", emptyList())
        val mirrorPath = mirror.sessionFile!!
        mirror.close()

        val passiveEvents = mutableListOf<SessionRuntimeEvent>()
        val engine = RemoteSessionEngine(
            cwd,
            agentDir,
            target(transport),
            transport,
            backgroundScope,
            questionCenter,
            onPassiveRuntimeEvent = { passiveEvents += it },
        )
        val handle = engine.create(mirrorPath, ModelConfig())
        runCurrent()
        val resumeCall = transport.calls.single()
        assertEquals("bot.resume", resumeCall.method)
        assertEquals(2L, resumeCall.params.getValue("afterSeq").jsonPrimitive.content.toLong())

        transport.emit(
            resumeCall.routeId,
            2,
            buildJsonObject { put("type", "message_update"); put("assistantMessageEvent", buildJsonObject { put("type", "text_delta"); put("delta", "stale") }) },
        )
        transport.emit(
            resumeCall.routeId,
            3,
            buildJsonObject {
                put("type", "approval_request")
                put("approvalId", "ask:c1")
                put("kind", "question")
                put("toolCallId", "c1")
                put("summary", "Pick one")
                put(
                    "questions",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "q1")
                                put("question", "Pick one")
                                put(
                                    "options",
                                    kotlinx.serialization.json.buildJsonArray {
                                        add(buildJsonObject { put("label", "Option A") })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        runCurrent()
        assertEquals(1, questionCenter.pending.value.size)
        val pendingId = questionCenter.pending.value.single().requestId

        questionCenter.answer(
            pendingId,
            listOf(io.github.whywood.superpi.runtime.QuestionAnswer(questionId = "q1", selected = listOf("Option A"))),
        )
        runCurrent()

        val approvalCall = transport.calls.last()
        assertEquals("bot.approval", approvalCall.method)
        assertEquals("ask:c1", approvalCall.params.getValue("approvalId").jsonPrimitive.content)

        transport.emit(
            resumeCall.routeId,
            4,
            buildJsonObject {
                put("type", "approval_request")
                put("approvalId", "ask:c2")
                put("kind", "confirm")
                put("title", "Continue?")
                put("message", "Continue?")
            },
        )
        runCurrent()
        assertEquals(1, questionCenter.pending.value.size)

        transport.emit(
            resumeCall.routeId,
            5,
            buildJsonObject {
                put("type", "approval_request")
                put("approvalId", "ask:c2")
                put("kind", "resolved")
                put("summary", "resolved")
                put("resolved", true)
            },
        )
        runCurrent()
        assertTrue(questionCenter.pending.value.isEmpty())

        transport.emit(resumeCall.routeId, 6, FakeEvents.textDelta("Done"))
        transport.emit(resumeCall.routeId, 7, FakeEvents.settled())
        runCurrent()
        assertFalse(handle.isBusy)
        assertTrue(passiveEvents.any { it.event is BackgroundTurnStarted })
        val deltas = passiveEvents.map { it.event }
            .filterIsInstance<RuntimeEvent.TextDelta>()
            .map { it.text }
        assertEquals(listOf("Done"), deltas)
        val finished = passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.RunFinished>().single()
        assertEquals(RunStatus.DONE, finished.status)
        assertEquals("Done", finished.text)
    }

    @Test
    fun passiveWatchStreamsMultipleHostInitiatedLocalTurnsAndPreemptsOnLocalPrompt() = runTest {
        val transport = FakeRemoteTransport()
        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        mirror.appendRemoteMapping("device-1", "pi", "peer-idle-1")
        mirror.appendUserMessage("Initial", emptyList())
        val mirrorPath = mirror.sessionFile!!
        mirror.close()

        val passiveEvents = mutableListOf<SessionRuntimeEvent>()
        val engine = RemoteSessionEngine(
            cwd,
            agentDir,
            target(transport),
            transport,
            backgroundScope,
            onPassiveRuntimeEvent = { passiveEvents += it },
        )
        val handle = engine.create(mirrorPath, ModelConfig())
        runCurrent()

        val watchCall = transport.calls.single()
        assertEquals("bot.resume", watchCall.method)
        assertEquals("peer-idle-1", watchCall.params.getValue("sessionId").jsonPrimitive.content)
        assertEquals(true, watchCall.params.getValue("tail").jsonPrimitive.boolean)
        assertNull(watchCall.params["afterSeq"])

        transport.emit(
            watchCall.routeId,
            0,
            buildJsonObject {
                put("type", "agent_start")
                put("sessionId", "peer-idle-1")
                put("userMessage", buildJsonObject { put("text", "desktop turn 1") })
            },
        )
        transport.emit(watchCall.routeId, 1, FakeEvents.textDelta("reply 1"))
        transport.emit(watchCall.routeId, 2, FakeEvents.agentEnd("done"))
        transport.emit(watchCall.routeId, 3, FakeEvents.settled())
        runCurrent()

        val turn1Started = passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.RunStarted>().single()
        assertEquals("desktop turn 1", turn1Started.prompt)
        assertEquals(
            listOf("reply 1"),
            passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.TextDelta>().map { it.text },
        )
        assertEquals(1, passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.RunFinished>().size)

        transport.emit(
            watchCall.routeId,
            4,
            buildJsonObject {
                put("type", "agent_start")
                put("sessionId", "peer-idle-1")
                put("userMessage", buildJsonObject { put("text", "desktop turn 2") })
            },
        )
        transport.emit(watchCall.routeId, 5, FakeEvents.textDelta("reply 2"))
        transport.emit(watchCall.routeId, 6, FakeEvents.agentEnd("done"))
        transport.emit(watchCall.routeId, 7, FakeEvents.settled())
        runCurrent()

        assertEquals(1, transport.calls.count { it.method == "bot.resume" })
        val starts = passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.RunStarted>()
        assertEquals(listOf("desktop turn 1", "desktop turn 2"), starts.map { it.prompt })
        assertEquals(
            listOf("reply 1", "reply 2"),
            passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.TextDelta>().map { it.text },
        )
        assertEquals(2, passiveEvents.map { it.event }.filterIsInstance<RuntimeEvent.RunFinished>().size)

        val localTurn = launch { handle.prompt("phone turn 3") }
        runCurrent()
        val promptCall = transport.calls.last()
        assertEquals("bot.prompt", promptCall.method)
        assertEquals("peer-idle-1", promptCall.params.getValue("sessionId").jsonPrimitive.content)
        assertEquals("phone turn 3", promptCall.params.getValue("text").jsonPrimitive.content)

        transport.emit(promptCall.routeId, 8, FakeEvents.agentStart())
        transport.emit(promptCall.routeId, 9, FakeEvents.textDelta("reply 3"))
        transport.emit(promptCall.routeId, 10, FakeEvents.settled())
        runCurrent()
        assertTrue(localTurn.isCompleted)

        val resumedAfterPrompt = transport.calls.last()
        assertEquals("bot.resume", resumedAfterPrompt.method)
        assertEquals(10L, resumedAfterPrompt.params.getValue("afterSeq").jsonPrimitive.content.toLong())
    }

    @Test
    fun retriesCompactsAndTracksRemoteUsage() = runTest {
        val transport = FakeRemoteTransport()
        transport.result = { call ->
            when (call.method) {
                "bot.prompt" -> buildJsonObject { put("sessionId", FakeRemoteTransport.PEER_SESSION_ID) }
                "bot.state" -> buildJsonObject {
                    put("sessionId", FakeRemoteTransport.PEER_SESSION_ID)
                    put("busy", false)
                    put(
                        "usage",
                        buildJsonObject {
                            put("contextTokens", 500L)
                            put("contextWindow", 100_000L)
                            put("inputTokens", 350L)
                            put("outputTokens", 150L)
                            put("cacheReadTokens", 0L)
                            put("cacheWriteTokens", 0L)
                            put("cost", 0.03)
                        },
                    )
                }
                "session.compact" -> buildJsonObject {
                    put("sessionId", FakeRemoteTransport.PEER_SESSION_ID)
                    put("compacted", true)
                    put("tokensBefore", 900L)
                    put("tokensAfter", 300L)
                    put(
                        "usage",
                        buildJsonObject {
                            put("contextTokens", 500L)
                            put("contextWindow", 100_000L)
                            put("inputTokens", 350L)
                            put("outputTokens", 150L)
                            put("cacheReadTokens", 0L)
                            put("cacheWriteTokens", 0L)
                            put("cost", 0.03)
                        },
                    )
                }
                "session.retry" -> buildJsonObject {
                    put("sessionId", FakeRemoteTransport.PEER_SESSION_ID)
                    put("retried", true)
                }
                else -> buildJsonObject {}
            }
        }
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        assertFalse(handle.canRetryLastAssistant())
        finishPrompt(this, transport, handle, "explain this", "partial")
        runCurrent()

        assertTrue(handle.canRetryLastAssistant())

        val retryJob = launch { handle.retryLastAssistant() }
        runCurrent()
        val retryCall = transport.calls.last { it.method == "session.retry" }
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, retryCall.params.getValue("sessionId").jsonPrimitive.content)
        transport.emit(retryCall.routeId, 2, FakeEvents.textDelta("complete"))
        transport.emit(retryCall.routeId, 3, FakeEvents.settled())
        runCurrent()
        assertTrue(retryJob.isCompleted)

        val compacted = handle.compact()
        assertEquals(900L, compacted?.tokensBefore)
        assertEquals(300L, compacted?.estimatedTokensAfter)
        assertEquals(500L, handle.usage()?.contextTokens)
    }

    @Test
    fun promptWithLocalAttachmentUploadsToRemoteWorkspaceAndRewritesPromptAndMirrorPath() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        val attachmentDir = File(cwd, "attachments").apply { mkdirs() }
        val localFile = File(attachmentDir, "a1b2c3d4e5f6_notes.txt").apply {
            writeText("cross-phone attachment payload")
        }
        val promptWithAttachment = """
            summarize the file

            <attachments>
            - ${localFile.absolutePath} | notes.txt | text/plain | ${localFile.length()}
            use these absolute paths with file tools to inspect the attachments
            </attachments>
        """.trimIndent()

        val job = launch { handle.prompt(promptWithAttachment) }
        runCurrent()

        val uploadCall = transport.calls.firstOrNull { it.method == "file.write_chunk" }
        assertTrue(uploadCall != null)
        assertEquals("pi", uploadCall!!.params.getValue("botId").jsonPrimitive.content)
        assertEquals("attachments/a1b2c3d4e5f6_notes.txt", uploadCall.params.getValue("path").jsonPrimitive.content)

        val promptCall = transport.calls.last { it.method == "bot.prompt" }
        val sentText = promptCall.params.getValue("text").jsonPrimitive.content
        assertTrue(sentText.contains("- attachments/a1b2c3d4e5f6_notes.txt | notes.txt | text/plain | ${localFile.length()}"))
        assertFalse(sentText.contains(localFile.absolutePath))

        transport.emit(promptCall.routeId, 0, FakeEvents.textDelta("Summary ready"))
        transport.emit(promptCall.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)

        val mirroredUserText = (handle.messages.first().asLlmMessage() as UserMessage).content.text()
        assertTrue(mirroredUserText.contains("- attachments/a1b2c3d4e5f6_notes.txt | notes.txt | text/plain | ${localFile.length()}"))
        assertFalse(mirroredUserText.contains(localFile.absolutePath))
    }

    @Test
    fun failedInitialAttachmentUploadCanBeRetriedWithoutDuplicatingUserMessage() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        val attachmentDir = File(cwd, "attachments").apply { mkdirs() }
        val localFile = File(attachmentDir, "b2c3d4e5f6a1_report.csv").apply {
            writeText("id,value\n1,42\n")
        }
        val promptWithAttachment = """
            analyze report

            <attachments>
            - ${localFile.absolutePath} | report.csv | text/csv | ${localFile.length()}
            use these absolute paths with file tools to inspect the attachments
            </attachments>
        """.trimIndent()

        transport.failNext = RemoteCallError("unreachable", "Upload interrupted")
        handle.prompt(promptWithAttachment)
        runCurrent()

        assertTrue(transport.calls.none { it.method == "bot.prompt" })
        assertTrue(handle.canRetryLastAssistant())

        val retryJob = launch { handle.retryLastAssistant() }
        runCurrent()
        val promptCall = transport.calls.last { it.method == "bot.prompt" }
        assertTrue(
            promptCall.params.getValue("text").jsonPrimitive.content
                .contains("- attachments/b2c3d4e5f6a1_report.csv | report.csv | text/csv | ${localFile.length()}"),
        )
        transport.emit(promptCall.routeId, 0, FakeEvents.textDelta("Report analyzed"))
        transport.emit(promptCall.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(retryJob.isCompleted)

        val userMessages = handle.messages.mapNotNull { it.asLlmMessage() as? UserMessage }
        assertEquals(1, userMessages.size)
    }

    @Test
    fun promptWithCollidingAttachmentBasenamesUsesDistinctRemotePathsWithoutOverwrite() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        val firstFile = File(File(root, "dir1").apply { mkdirs() }, "report.pdf").apply {
            writeText("first version of report")
        }
        val secondFile = File(File(root, "dir2").apply { mkdirs() }, "report.pdf").apply {
            writeText("second different report")
        }

        val job1 = launch {
            handle.prompt(
                """
                first file

                <attachments>
                - ${firstFile.absolutePath} | report.pdf | application/pdf | ${firstFile.length()}
                </attachments>
                """.trimIndent(),
            )
        }
        runCurrent()
        val upload1 = transport.calls.last { it.method == "file.write_chunk" }
        val remotePath1 = upload1.params.getValue("path").jsonPrimitive.content
        assertFalse(upload1.params.getValue("overwrite").jsonPrimitive.boolean)
        assertTrue(Regex("^attachments/[0-9a-f]{8}_report\\.pdf$").matches(remotePath1))
        val prompt1 = transport.calls.last { it.method == "bot.prompt" }
        assertTrue(prompt1.params.getValue("text").jsonPrimitive.content.contains("- $remotePath1 |"))
        transport.emit(prompt1.routeId, 0, FakeEvents.textDelta("ok1"))
        transport.emit(prompt1.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(job1.isCompleted)

        val job2 = launch {
            handle.prompt(
                """
                second file

                <attachments>
                - ${secondFile.absolutePath} | report.pdf | application/pdf | ${secondFile.length()}
                </attachments>
                """.trimIndent(),
            )
        }
        runCurrent()
        val upload2 = transport.calls.last { it.method == "file.write_chunk" }
        val remotePath2 = upload2.params.getValue("path").jsonPrimitive.content
        assertFalse(upload2.params.getValue("overwrite").jsonPrimitive.boolean)
        assertTrue(Regex("^attachments/[0-9a-f]{8}_report\\.pdf$").matches(remotePath2))
        assertTrue(remotePath1 != remotePath2)
        val prompt2 = transport.calls.last { it.method == "bot.prompt" }
        assertTrue(prompt2.params.getValue("text").jsonPrimitive.content.contains("- $remotePath2 |"))
        transport.emit(prompt2.routeId, 0, FakeEvents.textDelta("ok2"))
        transport.emit(prompt2.routeId, 1, FakeEvents.settled())
        runCurrent()
        assertTrue(job2.isCompleted)

        val userTexts = handle.messages.mapNotNull { it.asLlmMessage() as? UserMessage }.map { it.content.text() }
        assertTrue(userTexts[0].contains("- $remotePath1 |"))
        assertTrue(userTexts[1].contains("- $remotePath2 |"))
    }

    @Test
    fun passiveWatchResetsSeqWhenHostSignalsEpochRestart() = runTest {
        val transport = FakeRemoteTransport()
        transport.result = { call ->
            when (call.method) {
                "bot.resume" -> buildJsonObject {
                    put("sessionId", "peer-restarted")
                    put("busy", false)
                    put("gap", true)
                    put("seq", -1L)
                    put("reset", true)
                }
                else -> buildJsonObject {}
            }
        }
        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        mirror.appendRemoteMapping("device-1", "pi", "peer-restarted", historySeq = 1500L)
        mirror.appendUserMessage("Old msg", emptyList())
        val mirrorPath = mirror.sessionFile!!
        mirror.close()

        val passiveEvents = mutableListOf<SessionRuntimeEvent>()
        val engine = RemoteSessionEngine(
            cwd,
            agentDir,
            target(transport),
            transport,
            backgroundScope,
            onPassiveRuntimeEvent = { passiveEvents += it },
        )
        engine.create(mirrorPath, ModelConfig())
        runCurrent()

        val resumeCall = transport.calls.single { it.method == "bot.resume" }
        assertEquals(1500L, resumeCall.params.getValue("afterSeq").jsonPrimitive.content.toLong())

        transport.emit(
            resumeCall.routeId,
            0L,
            buildJsonObject {
                put("type", "agent_start")
                put("sessionId", "peer-restarted")
                put("userMessage", buildJsonObject { put("text", "fresh turn after restart") })
            },
        )
        transport.emit(resumeCall.routeId, 1L, FakeEvents.textDelta("restarted ok"))
        transport.emit(resumeCall.routeId, 2L, FakeEvents.agentEnd("done"))
        transport.emit(resumeCall.routeId, 3L, FakeEvents.settled())
        runCurrent()

        val deltas = passiveEvents.map { it.event }
            .filterIsInstance<RuntimeEvent.TextDelta>()
            .map { it.text }
        assertEquals(listOf("restarted ok"), deltas)
    }

    @Test
    fun fitsOversizedImagesBeforePromptAndFailsTurnWhenFittingFails() = runTest {
        val transport = FakeRemoteTransport()
        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        var shouldFail = false
        val handle = RemoteSessionHandle(
            target = target(transport),
            transport = transport,
            mirror = mirror,
            injectedScope = backgroundScope,
            fitRemoteImages = { parts ->
                if (shouldFail) {
                    Result.failure(IllegalArgumentException("Images too large"))
                } else {
                    Result.success(parts.map { RouteImagePart(data = "c21hbGw=", mimeType = "image/jpeg") })
                }
            },
        )

        val job1 = launch {
            handle.prompt(
                text = "inspect photos",
                images = listOf(ImagePart(data = "A".repeat(1_200_000), mimeType = "image/jpeg")),
            )
        }
        runCurrent()
        val call1 = transport.calls.single { it.method == "bot.prompt" }
        val sentImg = call1.params.getValue("images").jsonArray.single().jsonObject
        assertEquals("c21hbGw=", sentImg.getValue("data").jsonPrimitive.content)
        transport.emit(call1.routeId, 0L, FakeEvents.textDelta("ok"))
        transport.emit(call1.routeId, 1L, FakeEvents.settled())
        runCurrent()
        assertTrue(job1.isCompleted)

        shouldFail = true
        val errors = mutableListOf<String>()
        val unsub = handle.subscribe { event ->
            if (event is SessionEvent.SessionError) errors += event.message
        }
        handle.prompt(
            text = "too big",
            images = listOf(ImagePart(data = "B".repeat(1_200_000), mimeType = "image/jpeg")),
        )
        unsub()
        assertEquals(1, transport.calls.count { it.method == "bot.prompt" })
        assertEquals(listOf("Images too large"), errors)
    }

    @Test
    fun pendingModelAndThinkingAttachToFirstPromptAndClearOncePeerSessionBound() = runTest {
        val transport = FakeRemoteTransport()
        val engine = engine(transport, backgroundScope)
        val handle = engine.create(null, ModelConfig())

        RemoteSessionRegistry.setPendingModel(
            sessionId = handle.sessionId,
            providerId = "openai",
            modelId = "gpt-5",
            thinkingLevel = io.github.whywood.superpi.runtime.ThinkingLevel.HIGH,
        )
        RemoteSessionRegistry.setPendingThinking(
            sessionId = handle.sessionId,
            thinkingLevel = io.github.whywood.superpi.runtime.ThinkingLevel.XHIGH,
        )
        val pendingBefore = RemoteSessionRegistry.pendingModelSelection(handle.sessionId)
        assertEquals("openai", pendingBefore?.providerId)
        assertEquals("gpt-5", pendingBefore?.modelId)
        assertEquals(io.github.whywood.superpi.runtime.ThinkingLevel.XHIGH, pendingBefore?.thinkingLevel)

        val job = launch { handle.prompt("hello with custom model") }
        runCurrent()
        val promptCall = transport.calls.single { it.method == "bot.prompt" }
        assertEquals("openai", promptCall.params.getValue("provider").jsonPrimitive.content)
        assertEquals("gpt-5", promptCall.params.getValue("modelId").jsonPrimitive.content)
        assertEquals("xhigh", promptCall.params.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, RemoteSessionRegistry.peerSessionId(handle.sessionId))
        assertNull(RemoteSessionRegistry.pendingModelSelection(handle.sessionId))

        transport.emit(promptCall.routeId, 0L, FakeEvents.textDelta("ok"))
        transport.emit(promptCall.routeId, 1L, FakeEvents.settled())
        runCurrent()
        assertTrue(job.isCompleted)

        val secondJob = launch { handle.prompt("follow up without overrides") }
        runCurrent()
        val secondCall = transport.calls.last { it.method == "bot.prompt" }
        assertEquals(FakeRemoteTransport.PEER_SESSION_ID, secondCall.params.getValue("sessionId").jsonPrimitive.content)
        assertNull(secondCall.params["provider"])
        assertNull(secondCall.params["modelId"])
        assertNull(secondCall.params["thinkingLevel"])
        transport.emit(secondCall.routeId, 0L, FakeEvents.textDelta("ok2"))
        transport.emit(secondCall.routeId, 1L, FakeEvents.settled())
        runCurrent()
        assertTrue(secondJob.isCompleted)
    }

    @Test
    fun persistsAndForwardsPluginCustomMessageInActiveAndPassiveModes() = runTest {
        val transport = FakeRemoteTransport()
        val activeEvents = mutableListOf<SessionRuntimeEvent>()
        val activeEngine = RemoteSessionEngine(
            cwd,
            agentDir,
            target(transport),
            transport,
            backgroundScope,
            onPassiveRuntimeEvent = { activeEvents += it },
        )
        val activeHandle = activeEngine.create(null, ModelConfig())
        val activeJob = launch { activeHandle.prompt("trigger plugin") }
        runCurrent()
        val promptCall = transport.calls.single { it.method == "bot.prompt" }
        transport.emit(
            promptCall.routeId,
            0L,
            buildJsonObject {
                put("type", "plugin_custom_message")
                put("customType", "active_card")
                put("payload", """{"score":10}""")
                put("text", "Active plugin output")
                put("display", true)
            },
        )
        transport.emit(promptCall.routeId, 1L, FakeEvents.textDelta("done"))
        transport.emit(promptCall.routeId, 2L, FakeEvents.settled())
        runCurrent()
        assertTrue(activeJob.isCompleted)

        val activeCustom = activeEvents.map { it.event }
            .filterIsInstance<RuntimeEvent.PluginCustomMessage>()
            .single()
        assertEquals("active_card", activeCustom.customType)
        assertEquals("""{"score":10}""", activeCustom.payload)
        assertEquals("Active plugin output", activeCustom.text)
        assertEquals(true, activeCustom.display)
        assertTrue(File(activeHandle.sessionFile!!).readText().contains("active_card"))
        activeHandle.close()

        transport.calls.clear()
        transport.result = { call ->
            when (call.method) {
                "bot.resume" -> buildJsonObject {
                    put("sessionId", "peer-custom-watch")
                    put("busy", true)
                    put("gap", false)
                    put("seq", 0L)
                }
                else -> buildJsonObject {}
            }
        }
        val mirror = RemoteMirrorSession.create(cwd, agentDir)
        mirror.appendRemoteMapping("device-1", "pi", "peer-custom-watch", historySeq = 0L)
        mirror.appendUserMessage("Old msg", emptyList())
        val mirrorPath = mirror.sessionFile!!
        mirror.close()

        val passiveEvents = mutableListOf<SessionRuntimeEvent>()
        val passiveEngine = RemoteSessionEngine(
            cwd,
            agentDir,
            target(transport),
            transport,
            backgroundScope,
            onPassiveRuntimeEvent = { passiveEvents += it },
        )
        val passiveHandle = passiveEngine.create(mirrorPath, ModelConfig())
        runCurrent()
        val resumeCall = transport.calls.single { it.method == "bot.resume" }
        transport.emit(
            resumeCall.routeId,
            1L,
            buildJsonObject {
                put("type", "plugin_custom_message")
                put("customType", "passive_card")
                put("payload", """{"ok":true}""")
                put("text", "Passive plugin output")
                put("display", false)
            },
        )
        transport.emit(resumeCall.routeId, 2L, FakeEvents.settled())
        runCurrent()

        val passiveCustom = passiveEvents.map { it.event }
            .filterIsInstance<RuntimeEvent.PluginCustomMessage>()
            .single()
        assertEquals("passive_card", passiveCustom.customType)
        assertEquals("""{"ok":true}""", passiveCustom.payload)
        assertEquals("Passive plugin output", passiveCustom.text)
        assertEquals(false, passiveCustom.display)
        assertTrue(File(passiveHandle.sessionFile!!).readText().contains("passive_card"))
    }

    private fun engine(transport: FakeRemoteTransport, scope: CoroutineScope): RemoteSessionEngine =
        RemoteSessionEngine(cwd, agentDir, target(transport), transport, scope)

    private fun target(transport: FakeRemoteTransport): RemoteSessionTarget = RemoteSessionTarget(
        deviceId = FakeRemoteTransport.DEVICE_ID,
        deviceName = "Desktop",
        botId = "pi",
        botName = "PI",
        isOnline = { transport.onlineDevices.value.contains(FakeRemoteTransport.DEVICE_ID) },
    )

    private suspend fun finishPrompt(
        scope: TestScope,
        transport: FakeRemoteTransport,
        handle: SessionHandle,
        text: String,
        reply: String,
    ) {
        val job = scope.launch { handle.prompt(text) }
        scope.runCurrent()
        val call = transport.calls.last()
        transport.emit(call.routeId, 0, FakeEvents.textDelta(reply))
        transport.emit(call.routeId, 1, FakeEvents.settled())
        scope.runCurrent()
        assertTrue(job.isCompleted)
    }

    private fun MessageContent.text(): String = when (this) {
        is PlainTextContent -> text
        is BlockContent -> blocks.filterIsInstance<io.github.whywood.superpi.sdk.ai.TextContent>().joinToString("") { it.text }
    }
}

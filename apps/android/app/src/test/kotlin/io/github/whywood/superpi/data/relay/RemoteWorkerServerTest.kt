package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CapabilitySnapshot
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.QuestionOutcome
import io.github.whywood.superpi.runtime.SessionCompaction
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionMcpServerStatus
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolResultPayload
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerJobSummary
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerTimerSummary
import io.github.whywood.superpi.runtime.superagent.LocalWorkerBotSpec
import io.github.whywood.superpi.runtime.superagent.LocalWorkerPool
import io.github.whywood.superpi.runtime.superagent.SuperTaskStore
import io.github.whywood.superpi.runtime.superagent.WorkerHistoryWireCodec
import io.github.whywood.superpi.runtime.superagent.WorkerInspectExtras
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.agent.asAgentMessage
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.ModelCost
import io.github.whywood.superpi.sdk.ai.Provider
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteWorkerServerTest {
    private lateinit var root: File
    private lateinit var taskStore: SuperTaskStore
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("remote-worker-server-test").toFile()
        taskStore = SuperTaskStore(File(root, "super"), clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        taskStore.close()
        root.deleteRecursively()
    }

    @Test
    fun handlesAllWorkerMethodsReplayAndSharedFiveSlotPool() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val handles = mutableMapOf<String, StubHandle>()
        lateinit var approvalCenter: QuestionCenter
        val artifactFile = File(root, "out/result.txt").apply {
            parentFile?.mkdirs()
            writeText("artifact-body")
        }

        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, qCenter ->
                approvalCenter = qCenter
                val gate = CompletableDeferred<Unit>()
                gates[workerId] = gate
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ ->
                        val partialMsg = io.github.whywood.superpi.sdk.ai.AssistantMessage(
                            content = mutableListOf(),
                            api = "fake",
                            provider = "fake",
                            model = "fake",
                            usage = io.github.whywood.superpi.sdk.ai.Usage.empty(),
                            stopReason = io.github.whywood.superpi.sdk.ai.StopReason.Stop,
                            timestamp = nowMs,
                        )
                        h.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.MessageUpdate(
                                    partialMsg.asAgentMessage(),
                                    TextDeltaEvent(0, "Working...", partialMsg),
                                ),
                            ),
                        )
                        val presentOut = buildJsonObject {
                            put(
                                "items",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "file")
                                            put("path", artifactFile.absolutePath)
                                            put("name", "result.txt")
                                        },
                                    )
                                },
                            )
                        }.toString()
                        h.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionEnd(
                                    "call-p1",
                                    "present",
                                    AgentToolResult(content = listOf(TextContent(presentOut))),
                                    isError = false,
                                ),
                            ),
                        )
                        h.lastText = "Finished work."
                        gate.await()
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            maxWorkersPerBot = 5,
            maxConcurrentWorkers = 3,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val sent = CopyOnWriteArrayList<JsonObject>()
        val registry = PresentedArtifactRegistry()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = registry,
        )

        server.handle(
            RelayServerFrame.Route(
                id = "r-start",
                from = "dev-2",
                method = "worker.start",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("task", "Build report")
                },
            ),
        )
        runCurrent()

        val startOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-start" }
        assertEquals("wrk-1", startOk["result"]!!.jsonObject["workerId"]!!.jsonPrimitive.content)

        val askDeferred = async {
            approvalCenter.ask(
                sessionId = "sdk-wrk-1",
                toolCallId = "call-ask",
                questions = listOf(
                    UserQuestion(
                        id = "q1",
                        header = "Ship",
                        question = "Ship it?",
                        options = listOf(QuestionOption("Yes")),
                    ),
                ),
            )
        }
        runCurrent()
        val reqId = pool.inspect("wrk-1").record.pendingQuestion!!.requestId
        pool.start("home", "Idle peer worker", requestedWorkerId = "wrk-idle")
        runCurrent()
        gates.getValue("wrk-idle").complete(Unit)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-approval-wrong-worker",
                from = "dev-2",
                method = "worker.approval",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-idle")
                    put("requestId", reqId)
                    put("dismissed", true)
                },
            ),
        )
        val wrongWorkerErr = sent.first {
            it["type"]?.jsonPrimitive?.content == "route_error" &&
                it["id"]?.jsonPrimitive?.content == "r-approval-wrong-worker"
        }
        assertEquals("conflict", wrongWorkerErr["code"]!!.jsonPrimitive.content)

        pool.stop("home", "wrk-idle")
        pool.delete("home", "wrk-idle")
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-approval",
                from = "dev-2",
                method = "worker.approval",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("requestId", reqId)
                    put(
                        "answers",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("questionId", "q1")
                                    put("selected", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Yes")) })
                                },
                            )
                        },
                    )
                },
            ),
        )
        val outcome = askDeferred.await() as QuestionOutcome.Answered
        assertEquals(listOf("Yes"), outcome.answers.single().selected)

        server.handle(
            RelayServerFrame.Route(
                id = "r-steer",
                from = "dev-2",
                method = "worker.steer",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("message", "Add executive summary")
                },
            ),
        )
        runCurrent()
        assertTrue(handles.getValue("wrk-1").steeredMessages.contains("Add executive summary"))

        gates.getValue("wrk-1").complete(Unit)
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("wrk-1").record.status)

        server.handle(
            RelayServerFrame.Route(
                id = "r-resume",
                from = "dev-3",
                method = "worker.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("afterSeq", 0L)
                },
            ),
        )
        val resumeOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-resume" }
        assertTrue(resumeOk["result"]!!.jsonObject["seq"]!!.jsonPrimitive.long > 0L)
        assertTrue(sent.any { it["type"]?.jsonPrimitive?.content == "route_event" && it["id"]?.jsonPrimitive?.content == "r-resume" })

        server.handle(
            RelayServerFrame.Route(
                id = "r-inspect",
                from = "dev-2",
                method = "worker.inspect",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("tail", 10)
                },
            ),
        )
        val inspectOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-inspect" }
        val artRemoteId = inspectOk["result"]!!.jsonObject["artifacts"]!!.jsonArray.single().jsonObject["remoteArtifactId"]?.jsonPrimitive?.content
        assertTrue(!artRemoteId.isNullOrBlank())

        server.handle(
            RelayServerFrame.Route(
                id = "r-cont",
                from = "dev-2",
                method = "worker.continue",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                    put("message", "Follow up")
                },
            ),
        )
        runCurrent()
        server.handle(
            RelayServerFrame.Route(
                id = "r-abort",
                from = "dev-2",
                method = "worker.abort",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                },
            ),
        )
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("wrk-1").record.status)

        pool.start("home", "Local 2", requestedWorkerId = "wrk-2")
        pool.start("home", "Local 3", requestedWorkerId = "wrk-3")
        pool.start("home", "Local 4", requestedWorkerId = "wrk-4")
        pool.start("home", "Local 5", requestedWorkerId = "wrk-5")
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-list",
                from = "dev-2",
                method = "worker.list",
                params = buildJsonObject { put("botId", "home") },
            ),
        )
        val listOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-list" }
        assertEquals(5, listOk["result"]!!.jsonObject["workers"]!!.jsonArray.size)

        server.handle(
            RelayServerFrame.Route(
                id = "r-full",
                from = "dev-2",
                method = "worker.start",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-6")
                    put("task", "Overflow")
                },
            ),
        )
        val fullErr = sent.first { it["type"]?.jsonPrimitive?.content == "route_error" && it["id"]?.jsonPrimitive?.content == "r-full" }
        assertEquals("conflict", fullErr["code"]!!.jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "r-del",
                from = "dev-2",
                method = "worker.delete",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-1")
                },
            ),
        )
        runCurrent()
        val delOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-del" }
        assertTrue(delOk["result"]!!.jsonObject["deleted"]!!.jsonPrimitive.boolean)
        assertNull(taskStore.currentWorker("wrk-1"))

        gates.values.forEach { it.complete(Unit) }
        runCurrent()
    }

    @Test
    fun inspectIncludesEnrichedMessagesAndSteerAcceptsImagesOnly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        lateinit var stubHandle: StubHandle
        val reportFile = File(root, "out/summary.html").apply {
            parentFile?.mkdirs()
            writeText("<html>ok</html>")
        }
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    stubHandle = h
                    h.onPrompt = { _, _ -> gate.await() }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val sent = CopyOnWriteArrayList<JsonObject>()
        val registry = PresentedArtifactRegistry()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = registry,
        )

        server.handle(
            RelayServerFrame.Route(
                id = "r-start-img",
                from = "dev-2",
                method = "worker.start",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-img")
                    put("task", "Generate summary")
                },
            ),
        )
        runCurrent()

        stubHandle.historyMessages = listOf(
            AssistantMessage(id = 1L, role = AssistantRole.USER, text = "Generate summary"),
            AssistantMessage(
                id = 2L,
                role = AssistantRole.ASSISTANT,
                text = "Here is your report",
                status = MessageStatus.DONE,
                steps = listOf(
                    AssistantStep.Tool(
                        ToolTrace(
                            id = 10L,
                            name = "present",
                            arguments = emptyMap(),
                            status = ToolActivityStatus.DONE,
                            output = """{"items":[{"type":"html","path":"${reportFile.absolutePath}","name":"summary.html"}]}""",
                            toolCallId = "call-present-1",
                        ),
                    ),
                ),
            ),
        )

        server.handle(
            RelayServerFrame.Route(
                id = "r-steer-img",
                from = "dev-2",
                method = "worker.steer",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-img")
                    put("message", "")
                    put(
                        "images",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("data", "aGVsbG8=")
                                    put("mimeType", "image/png")
                                },
                            )
                        },
                    )
                },
            ),
        )
        runCurrent()
        assertTrue(sent.any { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-steer-img" })
        assertEquals(1, stubHandle.steeredImages.single().size)
        assertEquals("image/png", stubHandle.steeredImages.single().single().mimeType)

        server.handle(
            RelayServerFrame.Route(
                id = "r-inspect-msg",
                from = "dev-2",
                method = "worker.inspect",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-img")
                    put("tail", 10)
                },
            ),
        )
        val inspectOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-inspect-msg" }
        val decodedMessages = WorkerHistoryWireCodec.decodeMessages(inspectOk["result"]!!.jsonObject["messages"]!!.jsonArray)
        assertEquals(2, decodedMessages.size)
        val toolStep = decodedMessages[1].steps.single() as AssistantStep.Tool
        assertTrue(toolStep.trace.output.orEmpty().contains("artifactId"))
        assertTrue(toolStep.trace.output.orEmpty().contains("artifactSize"))

        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun supportsCompactSetModelModelOverrideStartAndPendingQuestionUsageSummary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-ext"), clock = { nowMs }, ioDispatcher = dispatcher)
        val handles = mutableMapOf<String, StubHandle>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        lateinit var approvalCenter: QuestionCenter
        val providers = object : ProviderDirectory {
            override val summaries: List<ProviderSummary> = emptyList()
            override fun summary(providerId: String): ProviderSummary? = null
            override fun models(providerId: String): List<ModelOption> {
                val levels = listOf(
                    io.github.whywood.superpi.runtime.ThinkingLevel.OFF,
                    io.github.whywood.superpi.runtime.ThinkingLevel.LOW,
                    io.github.whywood.superpi.runtime.ThinkingLevel.MEDIUM,
                    io.github.whywood.superpi.runtime.ThinkingLevel.HIGH,
                    io.github.whywood.superpi.runtime.ThinkingLevel.XHIGH,
                )
                return when (providerId) {
                    "fake" -> listOf(ModelOption("fake", "fake-model", "Fake Model", levels))
                    "openai" -> listOf(ModelOption("openai", "gpt-5", "GPT-5", levels))
                    else -> emptyList()
                }
            }
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model = Model(
                id = modelId,
                name = modelId,
                api = "fake",
                provider = providerId,
                baseUrl = "http://127.0.0.1",
                input = emptyList(),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                reasoning = false,
                contextWindow = 128_000L,
                maxTokens = 4_096L,
            )

            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super-ext/workers"),
            taskStore = taskStore,
            sessionFactory = { spec, workerId, _, sessionFile, qCenter ->
                approvalCenter = qCenter
                val gate = CompletableDeferred<Unit>()
                gates[workerId] = gate
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    handles[workerId] = h
                    h.model = providers.sdkModel(spec.defaultModel.providerId, spec.defaultModel.modelId)
                    h.thinkingLevel = spec.defaultModel.thinkingLevel.name.lowercase()
                    h.sessionUsage = SessionUsage(
                        contextTokens = 2_000L,
                        contextWindow = 128_000L,
                        inputTokens = 1_500L,
                        outputTokens = 500L,
                        cacheReadTokens = 100L,
                        cacheWriteTokens = 50L,
                        cost = 0.012,
                    )
                    h.onPrompt = { _, _ -> gate.await() }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig("fake", "fake-model"))
            },
            scope = backgroundScope,
            clock = { nowMs },
            ioDispatcher = dispatcher,
            providers = providers,
        )
        val sent = CopyOnWriteArrayList<JsonObject>()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = PresentedArtifactRegistry(),
        )

        server.handle(
            RelayServerFrame.Route(
                id = "r-start-override",
                from = "dev-2",
                method = "worker.start",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-ov")
                    put("task", "Check status")
                    put("providerId", "openai")
                    put("modelId", "gpt-5")
                    put("thinkingLevel", "HIGH")
                },
            ),
        )
        runCurrent()
        val handle = handles.getValue("wrk-ov")
        assertEquals("openai", handle.model.provider)
        assertEquals("gpt-5", handle.model.id)
        assertEquals("high", handle.thinkingLevel.lowercase())

        val askJob = async {
            approvalCenter.ask(
                sessionId = handle.sessionId,
                toolCallId = "call-pq",
                questions = listOf(
                    UserQuestion(
                        id = "q-approve",
                        header = "Deploy",
                        question = "Proceed?",
                        options = listOf(QuestionOption("Yes"), QuestionOption("No")),
                    ),
                ),
            )
        }
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-inspect-pq",
                from = "dev-2",
                method = "worker.inspect",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-ov")
                    put("tail", 5)
                },
            ),
        )
        runCurrent()
        val inspectOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-inspect-pq" }
        val resultJson = inspectOk["result"]!!.jsonObject
        val workerJson = resultJson["worker"]!!.jsonObject
        val pqJson = workerJson["pendingQuestion"]!!.jsonObject
        assertEquals("Proceed?", pqJson["questions"]!!.jsonArray[0].jsonObject["question"]!!.jsonPrimitive.content)
        assertEquals(2_000L, resultJson["usage"]!!.jsonObject["contextTokens"]!!.jsonPrimitive.long)

        approvalCenter.cancel(pqJson["requestId"]!!.jsonPrimitive.content)
        askJob.await()
        gates.getValue("wrk-ov").complete(Unit)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-set-model",
                from = "dev-2",
                method = "worker.set_model",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-ov")
                    put("providerId", "anthropic")
                    put("modelId", "claude-sonnet")
                    put("thinkingLevel", "MEDIUM")
                },
            ),
        )
        runCurrent()
        val setModelOk = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-set-model" }
        assertEquals("anthropic", setModelOk["result"]!!.jsonObject["providerId"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet", setModelOk["result"]!!.jsonObject["modelId"]!!.jsonPrimitive.content)

        server.handle(
            RelayServerFrame.Route(
                id = "r-compact",
                from = "dev-2",
                method = "worker.compact",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-ov")
                },
            ),
        )
        runCurrent()
        assertEquals(1, handle.compactCalls)
        assertTrue(sent.any { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "r-compact" })
        taskStore.close()
    }

    @Test
    fun evictsDuplicateSubscribersPerDeviceAndExposesInspectTimersJobsAndModelFields() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-dedup"), clock = { nowMs }, ioDispatcher = dispatcher)
        val handles = mutableMapOf<String, StubHandle>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var nextReply = "Initial reply"
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super-dedup/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ ->
                        val turnGate = CompletableDeferred<Unit>()
                        gates[workerId] = turnGate
                        turnGate.await()
                        h.lastText = nextReply
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig("fake", "fake-model"))
            },
            scope = backgroundScope,
            clock = { nowMs },
            ioDispatcher = dispatcher,
            inspectExtrasProvider = { _, _ ->
                WorkerInspectExtras(
                    activeTimers = listOf(WorkerTimerSummary("t-1", "Poll build", enabled = true, nextRunAt = nowMs + 5_000L)),
                    runningJobs = listOf(WorkerJobSummary("j-1", "npm test", io.github.whywood.superpi.runtime.JobStatus.RUNNING)),
                )
            },
        )
        val sent = CopyOnWriteArrayList<JsonObject>()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = PresentedArtifactRegistry(),
        )

        server.handle(
            RelayServerFrame.Route(
                id = "r-start-dup",
                from = "dev-2",
                method = "worker.start",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-dup")
                    put("task", "Monitor")
                },
            ),
        )
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-res-1",
                from = "dev-2",
                method = "worker.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-dup")
                },
            ),
        )
        server.handle(
            RelayServerFrame.Route(
                id = "r-res-2",
                from = "dev-2",
                method = "worker.resume",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-dup")
                },
            ),
        )
        runCurrent()
        sent.clear()

        val msg = io.github.whywood.superpi.sdk.ai.AssistantMessage(
            content = mutableListOf(),
            api = "fake",
            provider = "fake",
            model = "fake-model",
            usage = io.github.whywood.superpi.sdk.ai.Usage.empty(),
            stopReason = io.github.whywood.superpi.sdk.ai.StopReason.Stop,
            timestamp = nowMs,
        )
        handles.getValue("wrk-dup").emit(
            SessionEvent.AgentEventReceived(
                AgentEvent.MessageUpdate(msg.asAgentMessage(), TextDeltaEvent(0, "ping", msg)),
            ),
        )
        runCurrent()
        val deltaFrames = sent.filter {
            it["type"]?.jsonPrimitive?.content == "route_event" &&
                it["event"]?.jsonObject?.get("assistantMessageEvent")?.jsonObject?.get("delta")?.jsonPrimitive?.content == "ping"
        }
        assertEquals(listOf("r-res-2"), deltaFrames.map { it["id"]!!.jsonPrimitive.content })

        gates.getValue("wrk-dup").complete(Unit)
        runCurrent()

        nextReply = LocalWorkerPool.NO_UPDATE_MARKER
        assertTrue(pool.deliverTimerReminder("wrk-dup", "Poll check", "t-1"))
        runCurrent()
        gates.getValue("wrk-dup").complete(Unit)
        runCurrent()

        server.handle(
            RelayServerFrame.Route(
                id = "r-inspect-extra",
                from = "dev-2",
                method = "worker.inspect",
                params = buildJsonObject {
                    put("botId", "home")
                    put("workerId", "wrk-dup")
                },
            ),
        )
        runCurrent()
        val inspectOk = sent.first { it["id"]?.jsonPrimitive?.content == "r-inspect-extra" }
        val resultJson = inspectOk["result"]!!.jsonObject
        assertEquals("fake", resultJson["providerId"]?.jsonPrimitive?.content)
        assertEquals("fake-model", resultJson["modelId"]?.jsonPrimitive?.content)
        assertEquals("OFF", resultJson["thinkingLevel"]?.jsonPrimitive?.content)
        assertEquals("t-1", resultJson["activeTimers"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("j-1", resultJson["runningJobs"]!!.jsonArray[0].jsonObject["jobId"]!!.jsonPrimitive.content)
        assertEquals(true, resultJson["worker"]!!.jsonObject["lastTurnSilent"]!!.jsonPrimitive.content.toBooleanStrict())
        taskStore.close()
    }

    @Test
    fun replaysFromOneBasedSeqAcceptsMinimalThinkingPreservesOmittedAndEnrichesBotCwdPaths() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val localTaskStore = SuperTaskStore(File(root, "super-seq"), clock = { nowMs }, ioDispatcher = dispatcher)
        val botWorkspace = File(root, "ws-home").apply { mkdirs() }
        File(botWorkspace, "rel/report.txt").apply {
            parentFile?.mkdirs()
            writeText("relative report")
        }
        File(botWorkspace, "sub/ws.txt").apply {
            parentFile?.mkdirs()
            writeText("workspace prefixed")
        }
        val uriFile = File(root, "direct/uri.txt").apply {
            parentFile?.mkdirs()
            writeText("uri file")
        }
        val providers = object : ProviderDirectory {
            override val summaries: List<ProviderSummary> = emptyList()
            override fun summary(providerId: String): ProviderSummary? = null
            override fun models(providerId: String): List<ModelOption> {
                val levels = listOf(
                    io.github.whywood.superpi.runtime.ThinkingLevel.OFF,
                    io.github.whywood.superpi.runtime.ThinkingLevel.LOW,
                    io.github.whywood.superpi.runtime.ThinkingLevel.MEDIUM,
                    io.github.whywood.superpi.runtime.ThinkingLevel.HIGH,
                    io.github.whywood.superpi.runtime.ThinkingLevel.XHIGH,
                )
                return when (providerId) {
                    "fake" -> listOf(ModelOption("fake", "fake-model", "Fake Model", levels))
                    "openai" -> listOf(ModelOption("openai", "gpt-5", "GPT-5", levels))
                    else -> emptyList()
                }
            }
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model = Model(
                id = modelId,
                name = modelId,
                api = "fake",
                provider = providerId,
                baseUrl = "http://127.0.0.1",
                input = emptyList(),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                reasoning = true,
                contextWindow = 128_000L,
                maxTokens = 4_096L,
            )

            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }
        var turnGate = CompletableDeferred<Unit>()
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super-seq/workers"),
            taskStore = localTaskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    h.onPrompt = { _, message ->
                        if (message == "first") {
                            val presentOut = buildJsonObject {
                                put(
                                    "items",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "file"); put("path", "rel/report.txt"); put("name", "report.txt") })
                                        add(buildJsonObject { put("type", "file"); put("path", "/workspace/sub/ws.txt"); put("name", "ws.txt") })
                                        add(buildJsonObject { put("type", "file"); put("path", uriFile.toURI().toString()); put("name", "uri.txt") })
                                        add(buildJsonObject { put("type", "file"); put("path", "file://${uriFile.absolutePath}"); put("name", "uri2.txt") })
                                    },
                                )
                            }.toString()
                            h.emit(
                                SessionEvent.AgentEventReceived(
                                    AgentEvent.ToolExecutionEnd(
                                        "call-rel",
                                        "present",
                                        AgentToolResult(content = listOf(TextContent(presentOut))),
                                        isError = false,
                                    ),
                                ),
                            )
                        } else {
                            val msg = io.github.whywood.superpi.sdk.ai.AssistantMessage(
                                content = mutableListOf(),
                                api = "fake",
                                provider = "fake",
                                model = "fake",
                                usage = io.github.whywood.superpi.sdk.ai.Usage.empty(),
                                stopReason = io.github.whywood.superpi.sdk.ai.StopReason.Stop,
                                timestamp = nowMs,
                            )
                            h.emit(
                                SessionEvent.AgentEventReceived(
                                    AgentEvent.MessageUpdate(msg.asAgentMessage(), TextDeltaEvent(0, "turn2", msg)),
                                ),
                            )
                        }
                        turnGate.await()
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, botWorkspace, ModelConfig())
            },
            scope = backgroundScope,
            providers = providers,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val sent = CopyOnWriteArrayList<JsonObject>()
        val registry = PresentedArtifactRegistry()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = registry,
            resolveBotCwd = { botWorkspace.absolutePath },
        )

        try {
            server.handle(
                RelayServerFrame.Route(
                    id = "w-start-1",
                    from = "dev-2",
                    method = "worker.start",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("task", "first")
                    },
                ),
            )
            runCurrent()
            turnGate.complete(Unit)
            runCurrent()

            val startEvents = sent.filter {
                it["type"]?.jsonPrimitive?.content == "route_event" && it["id"]?.jsonPrimitive?.content == "w-start-1"
            }
            assertTrue(startEvents.any { it["seq"]!!.jsonPrimitive.long == 1L })
            val presentEvent = startEvents.first {
                it["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "tool_result"
            }
            val items = kotlinx.serialization.json.Json.parseToJsonElement(
                presentEvent["event"]!!.jsonObject["result"]!!.jsonPrimitive.content,
            ).jsonObject["items"]!!.jsonArray
            assertTrue(items.all { it.jsonObject["artifactId"]?.jsonPrimitive?.content?.isNotBlank() == true })

            val resumeSentBefore = sent.size
            server.handle(
                RelayServerFrame.Route(
                    id = "w-res-zero",
                    from = "dev-3",
                    method = "worker.resume",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("afterSeq", 0L)
                    },
                ),
            )
            runCurrent()
            val replayed = sent.drop(resumeSentBefore).filter {
                it["type"]?.jsonPrimitive?.content == "route_event" && it["id"]?.jsonPrimitive?.content == "w-res-zero"
            }
            assertEquals(1L, replayed.first()["seq"]!!.jsonPrimitive.long)

            server.handle(
                RelayServerFrame.Route(
                    id = "w-think-minimal",
                    from = "dev-2",
                    method = "worker.set_model",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("thinkingLevel", "minimal")
                    },
                ),
            )
            assertEquals(
                "LOW",
                sent.last()["result"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
            )

            server.handle(
                RelayServerFrame.Route(
                    id = "w-think-invalid",
                    from = "dev-2",
                    method = "worker.set_model",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("thinkingLevel", "bogus")
                    },
                ),
            )
            assertEquals("bad_request", sent.last()["code"]!!.jsonPrimitive.content)

            server.handle(
                RelayServerFrame.Route(
                    id = "w-model-omit-think",
                    from = "dev-2",
                    method = "worker.set_model",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("providerId", "openai")
                        put("modelId", "gpt-5")
                    },
                ),
            )
            assertEquals(
                "LOW",
                sent.last()["result"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content,
            )

            val baselineSeq = pool.replay("wrk-seq").seq
            val contStartIndex = sent.size
            turnGate = CompletableDeferred()
            server.handle(
                RelayServerFrame.Route(
                    id = "w-cont-1",
                    from = "dev-2",
                    method = "worker.continue",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-seq")
                        put("message", "second")
                    },
                ),
            )
            runCurrent()
            turnGate.complete(Unit)
            runCurrent()

            val contEvents = sent.drop(contStartIndex).filter {
                it["type"]?.jsonPrimitive?.content == "route_event" && it["id"]?.jsonPrimitive?.content == "w-cont-1"
            }
            assertTrue(contEvents.isNotEmpty())
            assertTrue(contEvents.all { it["seq"]!!.jsonPrimitive.long > baselineSeq })
        } finally {
            localTaskStore.close()
        }
    }

    @Test
    fun enrichPresentUsesWorkerWorkspaceResetsStaleResumeSeqAndPersistsSeqAcrossRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stateDir = File(root, "super-ws-restart")
        val workersRoot = File(stateDir, "workers")
        val localTaskStore = SuperTaskStore(stateDir, clock = { nowMs }, ioDispatcher = dispatcher)
        val botWorkspace = File(root, "ws-only-bot").apply { mkdirs() }
        val providers = object : ProviderDirectory {
            override val summaries: List<ProviderSummary> = emptyList()
            override fun summary(providerId: String): ProviderSummary? = null
            override fun models(providerId: String): List<ModelOption> = listOf(
                ModelOption("fake", "fake-model", "Fake", listOf(io.github.whywood.superpi.runtime.ThinkingLevel.OFF)),
            )
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model = Model(
                id = modelId,
                name = modelId,
                api = "fake",
                provider = providerId,
                baseUrl = "http://127.0.0.1",
                input = emptyList(),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                reasoning = true,
                contextWindow = 128_000L,
                maxTokens = 4_096L,
            )
            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }
        val turnGate = CompletableDeferred<Unit>()
        val pool = LocalWorkerPool(
            workersRoot = workersRoot,
            taskStore = localTaskStore,
            sessionFactory = { _, workerId, workerWorkspace, sessionFile, _ ->
                File(workerWorkspace, "out/worker-only.txt").apply {
                    parentFile?.mkdirs()
                    writeText("from worker workspace")
                }
                StubHandle("sdk-$workerId", sessionFile.absolutePath).also { h ->
                    h.onPrompt = { _, _ ->
                        val presentOut = buildJsonObject {
                            put(
                                "items",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "file")
                                            put("path", "out/worker-only.txt")
                                            put("name", "worker-only.txt")
                                        },
                                    )
                                },
                            )
                        }.toString()
                        h.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionEnd(
                                    "call-ws",
                                    "present",
                                    AgentToolResult(content = listOf(TextContent(presentOut))),
                                    isError = false,
                                ),
                            ),
                        )
                        turnGate.await()
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, botWorkspace, ModelConfig())
            },
            scope = backgroundScope,
            providers = providers,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val sent = CopyOnWriteArrayList<JsonObject>()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = PresentedArtifactRegistry(),
            resolveBotCwd = { botWorkspace.absolutePath },
        )

        try {
            server.handle(
                RelayServerFrame.Route(
                    id = "w-start-ws",
                    from = "dev-2",
                    method = "worker.start",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-ws")
                        put("task", "artifact")
                    },
                ),
            )
            runCurrent()
            turnGate.complete(Unit)
            runCurrent()

            val presentEvent = sent.first {
                it["type"]?.jsonPrimitive?.content == "route_event" &&
                    it["id"]?.jsonPrimitive?.content == "w-start-ws" &&
                    it["event"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "tool_result"
            }
            val items = kotlinx.serialization.json.Json.parseToJsonElement(
                presentEvent["event"]!!.jsonObject["result"]!!.jsonPrimitive.content,
            ).jsonObject["items"]!!.jsonArray
            assertTrue(items.single().jsonObject["artifactId"]?.jsonPrimitive?.content?.isNotBlank() == true)

            val beforeStaleResume = sent.size
            server.handle(
                RelayServerFrame.Route(
                    id = "w-res-stale",
                    from = "dev-3",
                    method = "worker.resume",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", "wrk-ws")
                        put("afterSeq", 999L)
                    },
                ),
            )
            runCurrent()
            val resumeOk = sent.drop(beforeStaleResume).first {
                it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "w-res-stale"
            }
            assertEquals(true, resumeOk["result"]!!.jsonObject["evictedBefore"]!!.jsonPrimitive.boolean)
            val replayed = sent.drop(beforeStaleResume).filter {
                it["type"]?.jsonPrimitive?.content == "route_event" && it["id"]?.jsonPrimitive?.content == "w-res-stale"
            }
            assertTrue(replayed.isNotEmpty())
            assertEquals(1L, replayed.first()["seq"]!!.jsonPrimitive.long)

            val expectedSeq = pool.replay("wrk-ws").seq
            assertTrue(expectedSeq >= 2L)

            val reloadedTaskStore = SuperTaskStore(stateDir, clock = { nowMs }, ioDispatcher = dispatcher)
            val reloadedPool = LocalWorkerPool(
                workersRoot = workersRoot,
                taskStore = reloadedTaskStore,
                sessionFactory = { _, workerId, _, sessionFile, _ -> StubHandle("sdk-$workerId", sessionFile.absolutePath) },
                resolveBotSpec = { botId -> LocalWorkerBotSpec(botId, "PI", null, botWorkspace, ModelConfig()) },
                scope = backgroundScope,
                providers = providers,
                clock = { nowMs },
                ioDispatcher = dispatcher,
            )
            try {
                assertEquals(expectedSeq, reloadedPool.replay("wrk-ws").seq)
            } finally {
                reloadedTaskStore.close()
            }
        } finally {
            localTaskStore.close()
        }
    }

    @Test
    fun workerInspectStripsInlineImagesAndBudgetsOversizedHistory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val stateDir = File(root, "super-budget")
        val workersRoot = File(stateDir, "workers")
        val localTaskStore = SuperTaskStore(stateDir, clock = { nowMs }, ioDispatcher = dispatcher)
        val botWorkspace = File(root, "ws-budget-bot").apply { mkdirs() }
        val providers = object : ProviderDirectory {
            override val summaries: List<ProviderSummary> = emptyList()
            override fun summary(providerId: String): ProviderSummary? = null
            override fun models(providerId: String): List<ModelOption> = listOf(
                ModelOption("fake", "fake-model", "Fake", listOf(ThinkingLevel.OFF)),
            )
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model = Model(
                id = modelId,
                name = modelId,
                api = "fake",
                provider = providerId,
                baseUrl = "http://127.0.0.1",
                input = emptyList(),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                reasoning = true,
                contextWindow = 128_000L,
                maxTokens = 4_096L,
            )
            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }
        val massiveHistory = buildList {
            for (i in 1..24) {
                add(AssistantMessage(id = (i * 2 - 1).toLong(), role = AssistantRole.USER, text = "Work $i"))
                add(
                    AssistantMessage(
                        id = (i * 2).toLong(),
                        role = AssistantRole.ASSISTANT,
                        text = if (i == 24) "Latest inspect tail" else "Body $i",
                        steps = listOf(
                            AssistantStep.Tool(
                                ToolTrace(
                                    id = (i * 100).toLong(),
                                    name = "screen",
                                    arguments = emptyMap(),
                                    output = "O".repeat(200_000),
                                    resultPayload = if (i == 24) {
                                        ToolResultPayload.Blocks(
                                            listOf(
                                                ToolResultPayload.Block.Image(
                                                    data = "B".repeat(300_000),
                                                    mimeType = "image/png",
                                                ),
                                                ToolResultPayload.Block.Text("tail-ok"),
                                            ),
                                        )
                                    } else {
                                        ToolResultPayload.Text("T".repeat(200_000))
                                    },
                                    status = ToolActivityStatus.DONE,
                                    toolCallId = "tc-$i",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val pool = LocalWorkerPool(
            workersRoot = workersRoot,
            taskStore = localTaskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                StubHandle("sdk-$workerId", sessionFile.absolutePath).apply {
                    historyMessages = massiveHistory
                    onPrompt = { h, _ -> h.lastText = "done" }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, botWorkspace, ModelConfig())
            },
            scope = backgroundScope,
            providers = providers,
            stallTimeoutMs = 0L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val sent = CopyOnWriteArrayList<JsonObject>()
        val server = RemoteWorkerServer(
            pool = pool,
            bots = { listOf(RelayBot("home", "PI")) },
            send = { sent += it; true },
            scope = backgroundScope,
            presentedArtifacts = PresentedArtifactRegistry(),
            resolveBotCwd = { botWorkspace.absolutePath },
        )
        try {
            val started = pool.start("home", "Inspect budget", requestedWorkerId = "wrk-budget")
            runCurrent()
            sent.clear()

            server.handle(
                RelayServerFrame.Route(
                    id = "w-insp-budget",
                    from = "dev-3",
                    method = "worker.inspect",
                    params = buildJsonObject {
                        put("botId", "home")
                        put("workerId", started.workerId)
                        put("tail", 50)
                    },
                ),
            )
            runCurrent()

            val ok = sent.first { it["type"]?.jsonPrimitive?.content == "route_ok" && it["id"]?.jsonPrimitive?.content == "w-insp-budget" }
            val result = ok["result"]!!.jsonObject
            assertEquals(true, result["truncated"]!!.jsonPrimitive.boolean)
            val raw = result.toString()
            assertTrue(raw.length <= 2_750_000)
            assertTrue(raw.contains("Latest inspect tail"))
            assertTrue(raw.contains("[Image omitted]"))
        } finally {
            localTaskStore.close()
        }
    }

    private class StubHandle(
        override val sessionId: String,
        override val sessionFile: String,
    ) : SessionHandle {
        override var isBusy: Boolean = false
        override val isStreaming: Boolean get() = isBusy
        override var messages: List<AgentMessage> = emptyList()
        override var activeToolNames: List<String> = emptyList()
        override var model: Model = Model(
            id = "fake-model",
            name = "Fake Model",
            api = "fake",
            provider = "fake",
            baseUrl = "http://127.0.0.1",
            input = emptyList(),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            reasoning = false,
            contextWindow = 128_000L,
            maxTokens = 4_096L,
        )
        override var thinkingLevel: String = "off"
        var onPrompt: (suspend (StubHandle, String) -> Unit)? = null
        val steeredMessages = mutableListOf<String>()
        val steeredImages = mutableListOf<List<ImagePart>>()
        var historyMessages: List<AssistantMessage> = emptyList()
        var lastText: String? = null
        var compactCalls: Int = 0
        var sessionUsage: SessionUsage? = null
        private var listener: (suspend (SessionEvent) -> Unit)? = null

        override suspend fun prompt(text: String, images: List<ImagePart>?, behavior: QueueBehavior?) {
            if (behavior == QueueBehavior.Steer) {
                steeredMessages += text
                steeredImages += images.orEmpty()
                return
            }
            isBusy = true
            try {
                onPrompt?.invoke(this, text)
            } finally {
                isBusy = false
            }
        }

        suspend fun emit(event: SessionEvent) {
            listener?.invoke(event)
        }

        override fun abort() {
            isBusy = false
        }

        override suspend fun close() = Unit
        override fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit {
            this.listener = listener
            return { this.listener = null }
        }

        override fun clearQueues() = Unit
        override fun removeQueuedInput(text: String, behavior: QueueBehavior): Boolean = false
        override fun canRetryLastAssistant(): Boolean = false
        override suspend fun retryLastAssistant() = Unit
        override fun lastAssistantText(): String? = lastText
        override suspend fun setModel(model: Model, thinkingLevel: String?) {
            this.model = model
            if (thinkingLevel != null) {
                this.thinkingLevel = thinkingLevel
            }
        }

        override suspend fun setThinkingLevel(level: String) {
            thinkingLevel = level
        }

        override fun registerProvider(provider: Provider) = Unit
        override suspend fun compact(): SessionCompaction? {
            compactCalls += 1
            return null
        }

        override fun history(): List<AssistantMessage> = historyMessages
        override fun branchEntries(): List<SessionEntry> = emptyList()
        override fun usage(): SessionUsage? = sessionUsage
        override fun sessionName(): String? = null
        override suspend fun appendTitle(title: String) = Unit
        override suspend fun appendCustomMessage(customType: String, payloadJson: String, text: String, display: Boolean) = Unit
        override suspend fun appendCustomEntry(customType: String, dataJson: String) = Unit
        override suspend fun capabilitySnapshot(): CapabilitySnapshot = CapabilitySnapshot()
        override fun capabilityBaseline(): CapabilitySnapshot? = null
        override suspend fun reloadMcp(servers: Map<String, McpServerConfig>) = Unit
        override suspend fun reconnectMcp(server: String) = Unit
        override fun mcpServers(): List<SessionMcpServerStatus> = emptyList()
        override fun deferredMcpToolCount(): Int = 0
    }
}

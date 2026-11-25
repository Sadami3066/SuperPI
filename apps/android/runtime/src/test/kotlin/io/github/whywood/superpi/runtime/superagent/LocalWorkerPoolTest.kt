package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.CapabilitySnapshot
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.QuestionOutcome
import io.github.whywood.superpi.runtime.SessionCompaction
import io.github.whywood.superpi.runtime.SessionHandle
import io.github.whywood.superpi.runtime.SessionMcpServerStatus
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.testing.fakeModel
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.AgentMessage
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.ModelCost
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.Provider
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalWorkerPoolTest {
    private lateinit var root: File
    private lateinit var memoryStore: MemoryStore
    private var openStore: SuperTaskStore? = null
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("local-worker-pool-test").toFile()
        memoryStore = MemoryStore(File(root, "memory"), clock = { nowMs })
    }

    @After
    fun tearDown() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                memoryStore.close()
            }
        }
        root.deleteRecursively()
    }

    private fun newTaskStore(dispatcher: kotlinx.coroutines.CoroutineDispatcher): SuperTaskStore =
        SuperTaskStore(File(root, "super"), clock = { nowMs }, ioDispatcher = dispatcher).also { openStore = it }

    @Test
    fun enforcesSharedFiveSlotPoolAcrossLocalAndRemoteCallersAndCapsConcurrencyToThree() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                TestWorkerHandle(workerId, sessionFile).apply {
                    val gate = CompletableDeferred<Unit>()
                    gates[workerId] = gate
                    onPrompt = { _, _ -> gate.await() }
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

        val w1 = pool.start("home", "task 1", requestedWorkerId = "w1", originCaller = LocalWorkerPool.LOCAL_CALLER)
        val w2 = pool.start("home", "task 2", requestedWorkerId = "w2", originCaller = "remote-dev-2")
        val w3 = pool.start("home", "task 3", requestedWorkerId = "w3", originCaller = LocalWorkerPool.LOCAL_CALLER)
        val w4 = pool.start("home", "task 4", requestedWorkerId = "w4", originCaller = "remote-dev-3")
        val w5 = pool.start("home", "task 5", requestedWorkerId = "w5", originCaller = LocalWorkerPool.LOCAL_CALLER)

        assertEquals(WorkerStatus.RUNNING, w1.status)
        assertEquals(WorkerStatus.RUNNING, w2.status)
        assertEquals(WorkerStatus.RUNNING, w3.status)
        assertEquals(WorkerStatus.QUEUED, w4.status)
        assertEquals(WorkerStatus.QUEUED, w5.status)
        assertEquals(5, pool.usedSlotsForBot("home"))

        val overflow = runCatching {
            pool.start("home", "task 6", originCaller = "remote-dev-9")
        }.exceptionOrNull() as? WorkerPoolFullException
        assertNotNull(overflow)
        assertEquals(5, overflow!!.existing.size)

        val conflictContinue = runCatching {
            pool.continueWorker("home", "w4", "again")
        }.exceptionOrNull()
        assertTrue(conflictContinue is WorkerConflictException)

        val conflictDelete = runCatching {
            pool.delete("home", "w1")
        }.exceptionOrNull()
        assertTrue(conflictDelete is WorkerConflictException)

        runCurrent()
        gates.getValue("w1").complete(Unit)
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("w1").record.status)
        assertEquals(WorkerStatus.RUNNING, pool.inspect("w4").record.status)
        assertEquals(WorkerStatus.QUEUED, pool.inspect("w5").record.status)

        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("w4").record.status)
        gates["w5"]?.complete(Unit)
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("w5").record.status)
        taskStore.close()
    }

    @Test
    fun stallWatchdogPausesDuringWaitingInputAndEmitsStalledWithoutKilling() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        lateinit var sharedApprovalCenter: QuestionCenter
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, approvalCenter ->
                sharedApprovalCenter = approvalCenter
                val promptGate = CompletableDeferred<Unit>()
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ -> promptGate.await() }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 1_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val milestones = CopyOnWriteArrayList<LocalWorkerMilestoneEvent>()
        val collectJob = backgroundScope.launch {
            pool.milestones.collect { milestones += it }
        }

        pool.start("home", "long task", requestedWorkerId = "w-stall", originCaller = "remote-caller")
        runCurrent()
        val handle = handles.getValue("w-stall")

        val questionWaiter = async {
            sharedApprovalCenter.ask(
                sessionId = handle.sessionId,
                toolCallId = "q-call-1",
                questions = listOf(
                    UserQuestion(
                        id = "q1",
                        header = "Approval",
                        question = "Continue?",
                        options = listOf(QuestionOption("Yes")),
                    ),
                ),
            )
        }
        runCurrent()
        assertEquals(WorkerStatus.WAITING_INPUT, pool.inspect("w-stall").record.status)

        nowMs += 5_000L
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(WorkerStatus.WAITING_INPUT, pool.inspect("w-stall").record.status)
        assertTrue(milestones.isEmpty())

        pool.start("home", "other worker", requestedWorkerId = "w-other", originCaller = "remote-caller")
        runCurrent()
        val pendingReqId = pool.inspect("w-stall").record.pendingQuestion!!.requestId
        val crossDismissError = runCatching {
            pool.dismissQuestion("w-other", pendingReqId)
        }.exceptionOrNull()
        assertTrue(crossDismissError is WorkerConflictException)
        assertEquals(WorkerStatus.WAITING_INPUT, pool.inspect("w-stall").record.status)
        pool.stop("home", "w-other")
        pool.delete("home", "w-other")
        runCurrent()
        milestones.clear()

        pool.dismissQuestion("w-stall", pendingReqId)
        assertTrue(questionWaiter.await() is QuestionOutcome.Cancelled)
        runCurrent()
        assertEquals(WorkerStatus.RUNNING, pool.inspect("w-stall").record.status)

        nowMs += 1_200L
        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(WorkerStatus.STALLED, pool.inspect("w-stall").record.status)
        val stalledEvent = milestones.single()
        assertEquals("remote-caller", stalledEvent.originCaller)
        assertTrue(stalledEvent.milestone is WorkerMilestone.Stalled)

        pool.stop("home", "w-stall")
        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun evictIdleHandleKeepsFilesAndNeverTriggersMemoryIngestion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        memoryStore.ensure()
        val createdHandles = CopyOnWriteArrayList<TestWorkerHandle>()
        val stagedSource = File(root, "chart.png").apply { writeText("png-data") }

        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{}\n")
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    createdHandles += h
                    h.branch = listOf(
                        userEntry("u1", "Analyze sales report Q3"),
                        assistantEntry("a1", "Analyzed sales metrics and generated chart."),
                    )
                    h.lastText = "Sales up 12%."
                    h.onPrompt = { handle, _ ->
                        handle.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionStart(
                                    "c-present",
                                    "present",
                                    buildJsonObject { put("items", "chart.png") },
                                ),
                            ),
                        )
                        val presentResultJson = buildJsonObject {
                            put(
                                "items",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            put("path", stagedSource.absolutePath)
                                            put("name", "chart.png")
                                        },
                                    )
                                },
                            )
                        }.toString()
                        handle.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionEnd(
                                    "c-present",
                                    "present",
                                    AgentToolResult(content = listOf(TextContent(presentResultJson))),
                                    isError = false,
                                ),
                            ),
                        )
                        handle.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionStart("c-card", "weather", buildJsonObject {}),
                            ),
                        )
                        handle.emit(
                            SessionEvent.AgentEventReceived(
                                AgentEvent.ToolExecutionEnd(
                                    "c-card",
                                    "weather",
                                    AgentToolResult(
                                        content = listOf(TextContent("Sunny 24C")),
                                        details = buildJsonObject {
                                            put(
                                                "superpi.card",
                                                buildJsonObject {
                                                    put("customType", "weather.card")
                                                    put("title", "Weather")
                                                    put("payload", buildJsonObject { put("temp", 24) })
                                                },
                                            )
                                        },
                                    ),
                                    isError = false,
                                ),
                            ),
                        )
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 500L,
            idleEvictMs = 1_500L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val record = pool.start("home", "Analyze Q3", requestedWorkerId = "w-mem")
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("w-mem").record.status)
        assertEquals(WorkerOutcome.SUCCEEDED, pool.inspect("w-mem").record.lastOutcome)

        val inspected = pool.inspect("w-mem").record
        assertEquals(1, inspected.artifacts.size)
        assertTrue(File(inspected.artifacts.single().path).isFile)
        assertEquals(1, inspected.cards.size)
        assertEquals("Weather", inspected.cards.single().title)
        assertEquals(record.workerId, inspected.cards.single().workerId)

        val firstHandle = createdHandles.single()
        firstHandle.emit(SessionEvent.CompactionStarted(1_024L))
        runCurrent()

        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()
        val waitDeadline = System.currentTimeMillis() + 2_000L
        while (firstHandle.closedCount == 0 && System.currentTimeMillis() < waitDeadline) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.delay(20L) }
            runCurrent()
        }
        assertTrue(firstHandle.closedCount > 0)
        val workerRoot = File(root, "super/workers/home/w-mem")
        assertTrue(File(workerRoot, "session.jsonl").isFile)
        assertTrue(File(workerRoot, "workspace").isDirectory)
        assertTrue(File(workerRoot, "artifacts").isDirectory)
        assertEquals(0, memoryStore.overview().pendingDispatchJobs)
        assertTrue(memoryStore.listEvents().isEmpty())

        pool.continueWorker("home", "w-mem", "One more check")
        runCurrent()
        assertEquals(2, createdHandles.size)

        pool.delete("home", "w-mem")
        runCurrent()
        assertFalse(workerRoot.exists())
        assertNull(taskStore.currentWorker("w-mem"))
        taskStore.close()
    }

    @Test
    fun sessionSnapshotsModelSwitchQueuedSteerDirectCallerAndSessionCleanupWorkTogether() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val createdHandles = mutableMapOf<String, TestWorkerHandle>()
        val executedPrompts = mutableListOf<Triple<String, String, List<ImagePart>>>()
        val cleanedSessions = mutableListOf<String>()
        val altModel = Model(
            id = "alt-model",
            name = "Alt Model",
            api = fakeModel.api,
            provider = fakeModel.provider,
            baseUrl = fakeModel.baseUrl,
            input = fakeModel.input,
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            reasoning = true,
            contextWindow = fakeModel.contextWindow,
            maxTokens = fakeModel.maxTokens,
        )
        val fakeProviders = object : ProviderDirectory {
            override val summaries = listOf(ProviderSummary(fakeModel.provider, "Fake", 2, null, false, true))
            override fun summary(providerId: String): ProviderSummary? = summaries.firstOrNull { it.id == providerId }
            override fun models(providerId: String): List<ModelOption> = listOf(
                ModelOption(fakeModel.provider, fakeModel.id, "Fake Model", listOf(ThinkingLevel.OFF, ThinkingLevel.MEDIUM)),
                ModelOption(fakeModel.provider, altModel.id, "Alt Model", listOf(ThinkingLevel.OFF, ThinkingLevel.HIGH)),
            )
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model =
                if (modelId == altModel.id) altModel else fakeModel
            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }

        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                val gate = gates.getOrPut(workerId) { CompletableDeferred() }
                TestWorkerHandle("worker-sdk-$workerId", sessionFile).also { handle ->
                    createdHandles[workerId] = handle
                    handle.onPrompt = { h, text ->
                        val imgs = h.promptImages.lastOrNull().orEmpty()
                        executedPrompts += Triple(workerId, text, imgs)
                        if (!gate.isCompleted && text == "Hold 1") {
                            gate.await()
                        }
                        h.historyList = h.historyList + AssistantMessage(
                            id = (h.historyList.size + 1).toLong(),
                            role = AssistantRole.ASSISTANT,
                            text = "reply:$text",
                        )
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(
                    botId = botId,
                    name = "PI",
                    avatar = null,
                    cwd = File(root, "bot-$botId"),
                    defaultModel = ModelConfig(fakeModel.provider, fakeModel.id, ThinkingLevel.OFF),
                )
            },
            scope = backgroundScope,
            maxConcurrentWorkers = 1,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
            providers = fakeProviders,
            onWorkerSessionCleanup = { sessionId -> cleanedSessions += sessionId },
        )

        val milestones = mutableListOf<LocalWorkerMilestoneEvent>()
        val milestoneJob = backgroundScope.launch(dispatcher) {
            pool.milestones.collect { milestones += it }
        }

        val s1Flow = pool.observeSession("w-s1")
        pool.start("home", "Hold 1", requestedWorkerId = "w-s1")
        runCurrent()
        assertTrue(s1Flow.value.streaming)

        pool.start("home", "Initial queued", requestedWorkerId = "w-s2")
        runCurrent()
        assertEquals(WorkerStatus.QUEUED, pool.inspect("w-s2").record.status)

        pool.steer("home", "w-s2", "Extra queued detail", listOf(ImagePart("Zm9v", "image/png")))
        runCurrent()

        pool.stop("home", "w-s1")
        runCurrent()
        assertTrue("worker-sdk-w-s1" in cleanedSessions)

        gates.getValue("w-s1").complete(Unit)
        runCurrent()
        val w2Exec = executedPrompts.first { it.first == "w-s2" }
        assertTrue(w2Exec.second.contains("Initial queued"))
        assertTrue(w2Exec.second.contains("Extra queued detail"))
        assertEquals(1, w2Exec.third.size)

        pool.changeModel("w-s1", fakeModel.provider, altModel.id, ThinkingLevel.HIGH)
        runCurrent()
        assertEquals(altModel.id, s1Flow.value.modelId)
        assertEquals(ThinkingLevel.HIGH, s1Flow.value.thinkingLevel)

        pool.changeThinking("w-s1", ThinkingLevel.OFF)
        runCurrent()
        assertEquals(ThinkingLevel.OFF, s1Flow.value.thinkingLevel)

        pool.continueWorker(
            botId = "home",
            workerId = "w-s1",
            message = "Direct prompt",
            originCaller = LocalWorkerPool.USER_DIRECT_CALLER,
        )
        runCurrent()
        val directMilestone = milestones.last { it.milestone.workerId == "w-s1" }
        assertEquals(LocalWorkerPool.USER_DIRECT_CALLER, directMilestone.originCaller)

        pool.loadSession("w-s1")
        runCurrent()
        assertTrue(s1Flow.value.loaded)
        assertFalse(s1Flow.value.streaming)

        pool.delete("home", "w-s1")
        runCurrent()
        assertEquals(2, cleanedSessions.count { it == "worker-sdk-w-s1" })

        milestoneJob.cancel()
        taskStore.close()
    }

    private fun userEntry(id: String, text: String): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = "2026-11-24T00:00:00Z",
        message = UserMessage(content = PlainTextContent(text), timestamp = nowMs),
    )

    private fun assistantEntry(id: String, text: String): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = "u1",
        timestamp = "2026-11-24T00:00:01Z",
        message = io.github.whywood.superpi.sdk.ai.AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "fake",
            provider = "fake",
            model = "fake",
            usage = Usage.empty(),
            stopReason = StopReason.Stop,
            timestamp = nowMs,
        ),
    )

    private class TestWorkerHandle(
        override val sessionId: String,
        private val file: File,
    ) : SessionHandle {
        override val sessionFile: String = file.absolutePath
        override var isBusy: Boolean = false
        override val isStreaming: Boolean get() = isBusy
        override var messages: List<AgentMessage> = emptyList()
        override var activeToolNames: List<String> = emptyList()
        override var model: Model = fakeModel
        override var thinkingLevel: String = "off"
        var onPrompt: (suspend (TestWorkerHandle, String) -> Unit)? = null
        val promptImages = mutableListOf<List<ImagePart>>()
        val steeredPrompts = mutableListOf<String>()
        var historyList: List<AssistantMessage> = emptyList()
        var branch: List<SessionEntry> = emptyList()
        var lastText: String? = null
        var closedCount: Int = 0
        var abortedCount: Int = 0
        var compactCalls: Int = 0
        var onCompact: (suspend () -> SessionCompaction?)? = null
        var sessionUsage: SessionUsage? = null
        val customMessages = mutableListOf<String>()
        private var listener: (suspend (SessionEvent) -> Unit)? = null

        override suspend fun prompt(text: String, images: List<ImagePart>?, behavior: QueueBehavior?) {
            promptImages += images.orEmpty()
            if (behavior == QueueBehavior.Steer) {
                steeredPrompts += text
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
            abortedCount += 1
            isBusy = false
        }

        override suspend fun close() {
            closedCount += 1
        }

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
            if (thinkingLevel != null) this.thinkingLevel = thinkingLevel
        }

        override suspend fun setThinkingLevel(level: String) {
            thinkingLevel = level
        }

        override fun registerProvider(provider: Provider) = Unit
        override suspend fun compact(): SessionCompaction? {
            compactCalls += 1
            return onCompact?.invoke()
        }

        override fun history(): List<AssistantMessage> = historyList
        override fun branchEntries(): List<SessionEntry> = branch
        override fun usage(): SessionUsage? = sessionUsage
        override fun sessionName(): String? = null
        override suspend fun appendTitle(title: String) = Unit
        override suspend fun appendCustomMessage(customType: String, payloadJson: String, text: String, display: Boolean) {
            customMessages += "$customType:$text"
        }

        override suspend fun appendCustomEntry(customType: String, dataJson: String) = Unit
        override suspend fun capabilitySnapshot(): CapabilitySnapshot = CapabilitySnapshot()
        override fun capabilityBaseline(): CapabilitySnapshot? = null
        override suspend fun reloadMcp(servers: Map<String, McpServerConfig>) = Unit
        override suspend fun reconnectMcp(server: String) = Unit
        override fun mcpServers(): List<SessionMcpServerStatus> = emptyList()
        override fun deferredMcpToolCount(): Int = 0
    }

    @Test
    fun smartWatchdogPausesDuringActiveToolRearmsAndRecoversWithoutAborting() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        val gate = CompletableDeferred<Unit>()
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ ->
                        gate.await()
                        h.lastText = "Tool completed cleanly"
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 1_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val milestones = CopyOnWriteArrayList<LocalWorkerMilestoneEvent>()
        val collectJob = backgroundScope.launch { pool.milestones.collect { milestones += it } }

        pool.start("home", "Run slow tool", requestedWorkerId = "w-tool")
        runCurrent()
        val handle = handles.getValue("w-tool")
        handle.emit(
            SessionEvent.AgentEventReceived(
                AgentEvent.ToolExecutionStart("tc-1", "bash", buildJsonObject { put("command", "sleep 60") }),
            ),
        )
        runCurrent()

        nowMs += 6_000L
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(WorkerStatus.RUNNING, pool.inspect("w-tool").record.status)
        assertTrue(milestones.isEmpty())

        handle.emit(
            SessionEvent.AgentEventReceived(
                AgentEvent.ToolExecutionEnd(
                    "tc-1",
                    "bash",
                    AgentToolResult(content = listOf(TextContent("ok"))),
                    isError = false,
                ),
            ),
        )
        runCurrent()

        nowMs += 1_200L
        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(WorkerStatus.STALLED, pool.inspect("w-tool").record.status)
        assertTrue(WorkerStatus.STALLED.isBusyForCommands)
        assertEquals(0, handle.abortedCount)
        assertEquals(1, milestones.count { it.milestone is WorkerMilestone.Stalled })

        nowMs += 1_200L
        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(2, milestones.count { it.milestone is WorkerMilestone.Stalled })
        assertEquals(0, handle.abortedCount)

        gate.complete(Unit)
        runCurrent()
        assertEquals(WorkerStatus.IDLE, pool.inspect("w-tool").record.status)
        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun questionTimeoutEscalatesToSuperAndGatesBySuperAnswersUntilTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        val gate = CompletableDeferred<Unit>()
        lateinit var approvalCenter: QuestionCenter
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, qCenter ->
                approvalCenter = qCenter
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ -> gate.await() }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            questionTimeoutMs = 2_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val milestones = CopyOnWriteArrayList<LocalWorkerMilestoneEvent>()
        val collectJob = backgroundScope.launch { pool.milestones.collect { milestones += it } }

        pool.start("home", "Deploy change", requestedWorkerId = "w-ask")
        runCurrent()
        val handle = handles.getValue("w-ask")
        val askJob = async {
            approvalCenter.ask(
                sessionId = handle.sessionId,
                toolCallId = "tc-ask",
                questions = listOf(
                    UserQuestion(
                        id = "q1",
                        header = "Confirm",
                        question = "Ship release?",
                        options = listOf(QuestionOption("Yes"), QuestionOption("No")),
                    ),
                ),
            )
        }
        runCurrent()
        val reqId = pool.inspect("w-ask").record.pendingQuestion!!.requestId
        assertFalse(pool.inspect("w-ask").questionEscalatedToSuper)

        val prematureError = runCatching {
            pool.answerQuestion(
                workerId = "w-ask",
                requestId = reqId,
                answers = listOf(QuestionAnswer("q1", selected = listOf("Yes"))),
                bySuper = true,
            )
        }.exceptionOrNull()
        assertTrue(prematureError is WorkerConflictException)

        nowMs += 2_200L
        advanceTimeBy(2_200L)
        runCurrent()
        assertTrue(pool.inspect("w-ask").questionEscalatedToSuper)
        val timeoutMilestone = milestones.firstOrNull { it.milestone is WorkerMilestone.WaitingInputTimeout }?.milestone
            as? WorkerMilestone.WaitingInputTimeout
        assertNotNull(timeoutMilestone)
        assertEquals(reqId, timeoutMilestone!!.requestId)

        pool.answerQuestion(
            workerId = "w-ask",
            requestId = reqId,
            answers = listOf(QuestionAnswer("q1", selected = listOf("Yes"))),
            bySuper = true,
        )
        val outcome = askJob.await()
        assertTrue(outcome is QuestionOutcome.Answered)
        gate.complete(Unit)
        runCurrent()
        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun silentPollingSuppressesNoUpdateAndAllowsRealUpdateAndSupportsCompactCustomAndJobEvictionGuard() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        var jobsRunning = true
        var turnReply = LocalWorkerPool.NO_UPDATE_MARKER
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.sessionUsage = SessionUsage(500L, 100_000L, 400L, 100L, 0L, 0L, 0.01)
                    h.onPrompt = { _, _ -> h.lastText = turnReply }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 1_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
            hasRunningJobs = { jobsRunning },
        )
        val milestones = CopyOnWriteArrayList<LocalWorkerMilestoneEvent>()
        val collectJob = backgroundScope.launch { pool.milestones.collect { milestones += it } }

        pool.start("home", "Watch inbox", requestedWorkerId = "w-watch")
        runCurrent()
        val handle = handles.getValue("w-watch")
        milestones.clear()

        assertTrue(pool.deliverTimerReminder("w-watch", "Check PR", "pr-watch"))
        runCurrent()
        assertEquals(0, milestones.count { it.milestone is WorkerMilestone.Completed })

        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, handle.closedCount)

        jobsRunning = false
        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, handle.closedCount)

        turnReply = "Found a failing build"
        assertTrue(pool.deliverTimerReminder("w-watch", "Check PR again", "pr-watch"))
        runCurrent()
        assertEquals(1, milestones.count { it.milestone is WorkerMilestone.Completed })

        val reopened = handles.getValue("w-watch")
        assertTrue(
            pool.postCustomMessage(
                sdkSessionId = reopened.sessionId,
                customType = "demo.card",
                payloadJson = "{\"ok\":true}",
                text = "Card shown",
            ),
        )
        runCurrent()
        assertTrue(reopened.customMessages.any { it.contains("demo.card:Card shown") })
        val sessionSnap = pool.observeSession("w-watch").value
        assertTrue(
            sessionSnap.messages.flatMap { it.steps }.any { it is AssistantStep.CustomCard && it.customType == "demo.card" },
        )

        pool.compact("w-watch")
        assertEquals(1, reopened.compactCalls)
        assertEquals(500L, pool.inspect("w-watch").usage?.contextTokens)

        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun maintenanceInFlightRejectsConcurrentOpsAndSteerAndWireEventsAndSilentStateBehaveCorrectly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        lateinit var approvalCenter: QuestionCenter
        val promptGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val compactGate = CompletableDeferred<Unit>()
        var jobsRunning = true
        var nextReply = "Initial meaningful summary"
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, qCenter ->
                approvalCenter = qCenter
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, _ ->
                        val g = CompletableDeferred<Unit>().also { promptGates[workerId] = it }
                        g.await()
                        h.lastText = nextReply
                    }
                    h.onCompact = {
                        compactGate.await()
                        null
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 500L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 1_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
            hasRunningJobs = { jobsRunning },
        )
        val wireEvents = CopyOnWriteArrayList<LocalWorkerEvent>()
        val wireJob = backgroundScope.launch { pool.wireEvents.collect { wireEvents += it } }
        val milestones = CopyOnWriteArrayList<LocalWorkerMilestoneEvent>()
        val milestoneJob = backgroundScope.launch { pool.milestones.collect { milestones += it } }

        pool.start("home", "Do initial work", requestedWorkerId = "w-m")
        runCurrent()
        wireEvents.clear()
        val handle = handles.getValue("w-m")

        handle.emit(SessionEvent.CompactionStarted(100L))
        handle.emit(SessionEvent.CompactionFinished(100L, 40L))
        handle.emit(SessionEvent.RetryScheduled(1, 200L, "rate limit"))
        handle.emit(SessionEvent.RetrySucceeded(1))
        runCurrent()
        assertEquals(
            listOf("compaction_start", "compaction_end", "retry_start", "retry_end"),
            wireEvents.mapNotNull { it.event["type"]?.let { t -> (t as? JsonPrimitive)?.content } },
        )

        promptGates.getValue("w-m").complete(Unit)
        runCurrent()
        val firstInspect = pool.inspect("w-m").record
        assertFalse(firstInspect.lastTurnSilent)
        assertEquals("Initial meaningful summary", firstInspect.lastSummary)

        nowMs += 1_200L
        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(0, handle.closedCount)
        assertEquals(WorkerStatus.IDLE, pool.inspect("w-m").record.status)
        jobsRunning = false

        val compactJob = async { pool.compact("w-m") }
        runCurrent()
        assertTrue(
            runCatching { pool.continueWorker("home", "w-m", "too soon") }.exceptionOrNull() is WorkerConflictException,
        )
        assertTrue(
            runCatching { pool.delete("home", "w-m") }.exceptionOrNull() is WorkerConflictException,
        )
        assertTrue(
            runCatching { pool.compact("w-m") }.exceptionOrNull() is WorkerConflictException,
        )
        compactGate.complete(Unit)
        compactJob.await()

        nextReply = LocalWorkerPool.NO_UPDATE_MARKER
        assertTrue(pool.deliverTimerReminder("w-m", "poll check", "poll-1"))
        runCurrent()
        promptGates.getValue("w-m").complete(Unit)
        runCurrent()
        val silentInspect = pool.inspect("w-m").record
        assertTrue(silentInspect.lastTurnSilent)
        assertEquals("Initial meaningful summary", silentInspect.lastSummary)

        milestones.clear()
        assertTrue(pool.deliverTimerReminder("w-m", "poll 2", "poll-1"))
        runCurrent()
        pool.steer("home", "w-m", "Human urgent steer", silentPolling = false)
        nextReply = "Handled human steer"
        promptGates.getValue("w-m").complete(Unit)
        runCurrent()
        assertEquals(1, milestones.count { it.milestone is WorkerMilestone.Completed })
        assertFalse(pool.inspect("w-m").record.lastTurnSilent)

        pool.continueWorker("home", "w-m", "ask question")
        runCurrent()
        val askDeferred = async {
            approvalCenter.ask(
                sessionId = handle.sessionId,
                toolCallId = "tc-steer-reject",
                questions = listOf(UserQuestion("q1", "Q", "Confirm?", listOf(QuestionOption("Yes")))),
            )
        }
        runCurrent()
        val reqId = pool.inspect("w-m").record.pendingQuestion!!.requestId
        assertTrue(
            runCatching { pool.steer("home", "w-m", "cannot steer waiting") }.exceptionOrNull() is WorkerConflictException,
        )
        approvalCenter.cancel(reqId)
        askDeferred.await()
        promptGates.getValue("w-m").complete(Unit)
        runCurrent()

        wireJob.cancel()
        milestoneJob.cancel()
        taskStore.close()
    }

    @Test
    fun deliverJobCompletionDuringWaitingInputIsDeliveredWhenQuestionResolves() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val handles = mutableMapOf<String, TestWorkerHandle>()
        val receivedPrompts = CopyOnWriteArrayList<String>()
        lateinit var approvalCenter: QuestionCenter
        val promptGate = CompletableDeferred<Unit>()
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers-followup"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, qCenter ->
                approvalCenter = qCenter
                TestWorkerHandle(workerId, sessionFile).also { h ->
                    handles[workerId] = h
                    h.onPrompt = { _, text ->
                        receivedPrompts += text
                        if (receivedPrompts.size == 1) {
                            promptGate.await()
                            h.lastText = "First turn done"
                        } else {
                            h.lastText = "Follow-up handled"
                        }
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-followup-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        pool.start("home", "Initial work", requestedWorkerId = "w-q")
        runCurrent()
        val handle = handles.getValue("w-q")
        assertEquals(listOf("Initial work"), receivedPrompts.toList())

        val askDeferred = async {
            approvalCenter.ask(
                sessionId = handle.sessionId,
                toolCallId = "tc-wait",
                questions = listOf(UserQuestion("q1", "Confirm", "Proceed?", listOf(QuestionOption("Yes")))),
            )
        }
        runCurrent()
        assertEquals(WorkerStatus.WAITING_INPUT, pool.inspect("w-q").record.status)
        val reqId = pool.inspect("w-q").record.pendingQuestion!!.requestId

        pool.deliverJobCompletion(handle.sessionId, "Background job 1 done")
        pool.deliverJobCompletion(handle.sessionId, "Background job 2 done")
        runCurrent()
        assertEquals(listOf("Initial work"), receivedPrompts.toList())

        approvalCenter.answer(reqId, listOf(QuestionAnswer("q1", listOf("Yes"))))
        askDeferred.await()
        runCurrent()
        assertTrue(
            handle.steeredPrompts.contains("Background job 1 done") ||
                receivedPrompts.contains("Background job 1 done"),
        )
        assertTrue(
            handle.steeredPrompts.contains("Background job 2 done") ||
                receivedPrompts.contains("Background job 2 done"),
        )

        promptGate.complete(Unit)
        runCurrent()
        taskStore.close()
    }
}

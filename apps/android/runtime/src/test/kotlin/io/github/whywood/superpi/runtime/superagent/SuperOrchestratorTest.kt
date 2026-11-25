package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.QuestionOutcome
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.TimerStore
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerProgressEvent
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSessionSnapshot
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.testing.FakeAgentRuntime
import io.github.whywood.superpi.runtime.testing.FakeSessionHandle
import io.github.whywood.superpi.runtime.testing.fakeModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.github.whywood.superpi.sdk.agent.AgentEvent
import io.github.whywood.superpi.sdk.agent.asAgentMessage
import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.TextDeltaEvent
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.compaction.sessionEntryToContextMessages
import io.github.whywood.superpi.sdk.coding.session.CustomMessageEntry
import io.github.whywood.superpi.sdk.coding.session.QueueBehavior
import io.github.whywood.superpi.sdk.coding.session.SessionEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuperOrchestratorTest {
    private lateinit var root: File
    private lateinit var superDir: File
    private var openStore: SuperTaskStore? = null
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("super-orchestrator-test").toFile()
        superDir = File(root, "super")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newTaskStore(dispatcher: kotlinx.coroutines.CoroutineDispatcher): SuperTaskStore =
        SuperTaskStore(superDir, clock = { nowMs }, ioDispatcher = dispatcher).also { openStore = it }

    @Test
    fun enforcesWhitelistCoalescesLocalMilestonesIgnoresForeignCallerAndArchivesSuperOnly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val routerHandles = mutableListOf<FakeSessionHandle>()
        val workerHandles = mutableMapOf<String, FakeSessionHandle>()
        lateinit var capturedGateway: WorkerOrchestrationGateway

        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{}\n")
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath).also {
                    workerHandles[workerId] = it
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, botId.uppercase(), null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val remoteFile = File(root, "remote-cache/doc.pdf").apply {
            parentFile?.mkdirs()
            writeText("pdf")
        }
        val fakeRemote = FakeRemoteWorkerGateway(remoteArtifactFile = remoteFile)

        val localFile = File(root, "local-art/out.txt").apply {
            parentFile?.mkdirs()
            writeText("hello")
        }

        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            remoteWorkerGateway = fakeRemote,
            localBotsProvider = {
                listOf(
                    BotBlueprint(SuperRuntime.localBotRef("home"), "home", "PI"),
                    BotBlueprint(SuperRuntime.localBotRef("coding"), "coding", "Coder"),
                )
            },
            scope = backgroundScope,
            coalesceWindowMs = 1_500L,
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        sessionFile.parentFile?.mkdirs()
                        sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-1\"}\n")
                        FakeSessionHandle("router-${routerHandles.size + 1}", sessionFile.absolutePath).also {
                            routerHandles += it
                        }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )

        orchestrator.ensureStarted()
        runCurrent()

        val disabledFailure = runCatching {
            capturedGateway.startWorker("coding", "Refactor app")
        }.exceptionOrNull()
        assertTrue(disabledFailure is IllegalArgumentException)

        val localWorker1 = capturedGateway.startWorker("PI", "Task A")
        val localWorker2 = capturedGateway.startWorker("local:home", "Task B")
        pool.start("coding", "Remote peer call", requestedWorkerId = "w-foreign", originCaller = "peer-dev-9")
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, routerHandles.single().prompts.size)

        advanceTimeBy(600L)
        runCurrent()
        assertEquals(1, routerHandles.single().prompts.size)
        val receipt = routerHandles.single().prompts.single().text
        assertTrue(receipt.startsWith("[Worker update]"))
        assertTrue(receipt.contains(localWorker1.workerId))
        assertTrue(receipt.contains(localWorker2.workerId))
        assertFalse(receipt.contains("w-foreign"))

        val localRec = WorkerRunRecord(
            workerId = localWorker1.workerId,
            botRef = SuperRuntime.HOME_BOT_REF,
            botId = "home",
            botName = "PI",
            deviceName = "Pixel",
            title = "Task A",
            status = WorkerStatus.IDLE,
            artifacts = listOf(
                WorkerArtifactRef("art_local", localWorker1.workerId, SuperRuntime.HOME_BOT_REF, "file", localFile.absolutePath, "out.txt"),
            ),
        )
        taskStore.upsertWorker(localRec)
        val remoteRec = WorkerRunRecord(
            workerId = "w-remote",
            botRef = SuperRuntime.remoteBotRef("dev-2", "home"),
            botId = "home",
            botName = "Remote PI",
            deviceId = "dev-2",
            deviceName = "Tablet",
            title = "Remote art",
            status = WorkerStatus.IDLE,
            artifacts = listOf(
                WorkerArtifactRef(
                    artifactId = "art_remote",
                    workerId = "w-remote",
                    botRef = SuperRuntime.remoteBotRef("dev-2", "home"),
                    kind = "file",
                    path = "",
                    name = "doc.pdf",
                    remoteDeviceId = "dev-2",
                    remoteBotId = "home",
                    remoteArtifactId = "art_remote",
                ),
            ),
        )
        taskStore.upsertWorker(remoteRec)
        assertEquals(localFile, orchestrator.resolveArtifactFile("worker://${localWorker1.workerId}/art_local"))
        assertEquals(remoteFile, orchestrator.resolveArtifactFile("worker://w-remote/art_remote"))
        assertNull(orchestrator.resolveArtifactFile("worker://missing/art"))

        val routerSessionBefore = File(superDir, "router/session.jsonl")
        assertTrue(routerSessionBefore.isFile)
        orchestrator.archiveAndResetSuper()
        runCurrent()
        val archives = File(superDir, "router/archive").listFiles()?.toList().orEmpty()
        assertEquals(1, archives.size)
        assertTrue(orchestrator.threadState.value.archivedCount >= 1)
        assertTrue(taskStore.currentWorker(localWorker1.workerId) != null)
        taskStore.close()
    }

    @Test
    fun zeroHopWorkerApprovalResolvesQuestionAndRecordsContextNote() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val routerHandles = mutableListOf<FakeSessionHandle>()
        lateinit var approvalHub: QuestionCenter
        val promptGate = CompletableDeferred<Unit>()

        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, approvalCenter ->
                approvalHub = approvalCenter
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath).apply {
                    suspendPrompt = true
                    onPrompt = { }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        lateinit var capturedGateway: WorkerOrchestrationGateway
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            localBotsProvider = { listOf(BotBlueprint(SuperRuntime.HOME_BOT_REF, "home", "PI")) },
            scope = backgroundScope,
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        FakeSessionHandle("router-1", sessionFile.absolutePath).also {
                            routerHandles += it
                        }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )
        orchestrator.ensureStarted()
        runCurrent()

        val worker = capturedGateway.startWorker("PI", "Deploy build")
        runCurrent()

        val askDeferred = async {
            approvalHub.ask(
                sessionId = "worker-sdk-${worker.workerId}",
                toolCallId = "call-ask",
                questions = listOf(
                    UserQuestion(
                        id = "q1",
                        header = "Deploy",
                        question = "Approve deploy?",
                        options = listOf(QuestionOption("Proceed")),
                    ),
                ),
            )
        }
        runCurrent()
        val pending = orchestrator.pendingWorkerQuestions.value.single()
        assertTrue(pending.requestId.isNotBlank())
        assertEquals(pending.requestId, orchestrator.workers.value.first { it.workerId == worker.workerId }.pendingQuestion?.requestId)

        orchestrator.answerWorkerQuestion(
            workerId = worker.workerId,
            requestId = pending.requestId,
            answers = listOf(QuestionAnswer("q1", selected = listOf("Proceed"))),
        )
        val outcome = askDeferred.await() as QuestionOutcome.Answered
        assertEquals(listOf("Proceed"), outcome.answers.single().selected)
        runCurrent()
        val noteRecord = routerHandles.single().customMessages.single { it.customType == "super.context_note" }
        assertFalse(noteRecord.display)
        assertTrue(noteRecord.text.contains("Proceed"))
        val projected = sessionEntryToContextMessages(
            CustomMessageEntry(
                id = "cm-1",
                parentId = null,
                timestamp = "2026-11-24T00:00:00Z",
                customType = noteRecord.customType,
                content = PlainTextContent(noteRecord.text),
                display = noteRecord.display,
            ),
        )
        assertEquals(1, projected.size)

        orchestrator.stopWorker(worker.workerId)
        promptGate.complete(Unit)
        taskStore.close()
    }

    @Test
    fun coalescedReceiptAllocatesPerWorker1500CharBudgetWhilePreservingMetadata() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val routerHandles = mutableListOf<FakeSessionHandle>()
        val remoteFile = File(root, "remote-cache/unused.txt").apply {
            parentFile?.mkdirs()
            writeText("ok")
        }
        val fakeRemote = FakeRemoteWorkerGateway(remoteArtifactFile = remoteFile)
        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath)
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            idleDebounceMs = 60_000L,
            idleEvictMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            remoteWorkerGateway = fakeRemote,
            localBotsProvider = { listOf(BotBlueprint(SuperRuntime.HOME_BOT_REF, "home", "PI")) },
            scope = backgroundScope,
            coalesceWindowMs = 1_500L,
            threadEngineFactory = { _, _, _ ->
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        FakeSessionHandle("router-1", sessionFile.absolutePath).also {
                            routerHandles += it
                        }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )
        orchestrator.ensureStarted()
        runCurrent()

        val longSummaryA = "A".repeat(2_000)
        val longSummaryB = "B".repeat(2_000)
        fakeRemote.emitMilestone(
            WorkerMilestone.Completed(
                workerId = "w-budget-1",
                botRef = "remote:dev-2:home",
                botName = "Remote PI",
                summary = longSummaryA,
                artifacts = listOf(WorkerArtifactRef("art_1", "w-budget-1", "remote:dev-2:home", "html", "", "report-1.html")),
                cards = listOf(WorkerCardRef("card_1", "w-budget-1", "remote:dev-2:home", "flight", "Flight 1")),
            ),
        )
        fakeRemote.emitMilestone(
            WorkerMilestone.Completed(
                workerId = "w-budget-2",
                botRef = "remote:dev-2:home",
                botName = "Remote PI",
                summary = longSummaryB,
                artifacts = listOf(WorkerArtifactRef("art_2", "w-budget-2", "remote:dev-2:home", "file", "", "report-2.txt")),
                cards = listOf(WorkerCardRef("card_2", "w-budget-2", "remote:dev-2:home", "hotel", "Hotel 2")),
            ),
        )
        runCurrent()
        advanceTimeBy(1_600L)
        runCurrent()

        val receipt = routerHandles.single().prompts.single().text
        assertTrue(receipt.length in 2_600..3_100)
        val sections = receipt.removePrefix("[Worker update]\n\n").split("\n\nworker_id: ")
        assertEquals(2, sections.size)
        val firstSection = sections[0]
        val secondSection = "worker_id: " + sections[1]
        assertEquals(1_500, firstSection.length)
        assertEquals(1_500, secondSection.length)
        assertTrue(firstSection.contains("worker://w-budget-1/art_1"))
        assertTrue(firstSection.contains("card_1"))
        assertTrue(secondSection.contains("worker://w-budget-2/art_2"))
        assertTrue(secondSection.contains("card_2"))
        assertTrue(firstSection.contains("A".repeat(1_000)))
        assertTrue(secondSection.contains("B".repeat(1_000)))
        taskStore.close()
    }

    @Test
    fun listsWorkersForwardsAttachmentsAndSupportsDirectUserChatWithoutWakingSuper() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super"), clock = { nowMs }, ioDispatcher = dispatcher)
        val superDir = File(root, "super")
        val remoteFile = File(root, "remote-out.txt").apply { writeText("remote-data") }
        val localImage = File(root, "photo.png").apply { writeText("png-bytes") }
        val localDoc = File(root, "notes.md").apply { writeText("Release checklist") }
        val remoteTextFile = File(root, "context.txt").apply { writeText("Remote inline context") }
        val fakeRemote = FakeRemoteWorkerGateway(remoteArtifactFile = remoteFile)
        val routerHandles = mutableListOf<FakeSessionHandle>()
        val workerHandles = mutableMapOf<String, FakeSessionHandle>()
        val workerPrompts = mutableMapOf<String, MutableList<Pair<String, List<ImagePart>>>>()
        val busyLocalGate = CompletableDeferred<Unit>()

        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath).also { handle ->
                    workerHandles[workerId] = handle
                    handle.onPrompt = { promptText ->
                        val lastImages = handle.prompts.lastOrNull()?.images.orEmpty()
                        workerPrompts.getOrPut(workerId) { mutableListOf() }.add(promptText to lastImages)
                        if (promptText == "hold-busy") {
                            handle.busy = true
                            busyLocalGate.await()
                            handle.busy = false
                        }
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, if (botId == "home") "PI" else "Coder", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        lateinit var capturedGateway: WorkerOrchestrationGateway
        var remoteOnline = true
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            remoteWorkerGateway = fakeRemote,
            localBotsProvider = {
                listOf(
                    BotBlueprint(SuperRuntime.localBotRef("home"), "home", "PI"),
                    BotBlueprint(SuperRuntime.localBotRef("coding"), "coding", "Coder"),
                )
            },
            remoteBotsProvider = {
                listOf(
                    BotBlueprint(
                        ref = SuperRuntime.remoteBotRef("dev-2", "home"),
                        botId = "home",
                        name = "Remote PI",
                        deviceId = "dev-2",
                        deviceName = "Desktop",
                        online = remoteOnline,
                        supportsSuperWorker = true,
                    ),
                )
            },
            imageNormalizer = { file ->
                if (file.extension == "png") {
                    ImagePart(data = "YmFzZTY0", mimeType = "image/png", path = file.absolutePath)
                } else {
                    null
                }
            },
            scope = backgroundScope,
            coalesceWindowMs = 1_500L,
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        sessionFile.parentFile?.mkdirs()
                        sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-1\"}\n")
                        FakeSessionHandle("router-${routerHandles.size + 1}", sessionFile.absolutePath).also {
                            routerHandles += it
                        }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )
        taskStore.setBotEnabled(SuperRuntime.remoteBotRef("dev-2", "home"), true)
        orchestrator.ensureStarted()
        runCurrent()
        assertEquals(1, fakeRemote.reattachCalls)

        val localStarted = capturedGateway.startWorker(
            botQuery = "PI",
            task = "Inspect image and notes",
            attachmentPaths = listOf(localImage.absolutePath, localDoc.absolutePath),
        )
        runCurrent()
        assertEquals(WorkerStatus.IDLE, orchestrator.inspectWorker(localStarted.workerId)!!.record.status)
        val firstWorkerCall = workerPrompts.getValue(localStarted.workerId).single()
        assertEquals(1, firstWorkerCall.second.size)
        assertEquals("image/png", firstWorkerCall.second.single().mimeType)
        assertTrue(firstWorkerCall.first.contains("notes.md"))
        val stagedFile = File(File(orchestrator.workerWorkspaceDir(localStarted.workerId)!!, "inputs"), "notes.md")
        assertTrue(stagedFile.isFile)
        assertEquals("Release checklist", stagedFile.readText())

        val remoteStarted = capturedGateway.startWorker(
            botQuery = SuperRuntime.remoteBotRef("dev-2", "home"),
            task = "Review remote attachment",
            attachmentPaths = listOf(localImage.absolutePath, remoteTextFile.absolutePath),
        )
        taskStore.upsertWorker(remoteStarted)
        runCurrent()
        assertEquals(1, fakeRemote.startedTasks.size)
        assertTrue(fakeRemote.startedTasks.single().contains("Remote inline context"))
        assertEquals(1, fakeRemote.startedImages.single().size)

        val listedAll = capturedGateway.listWorkers()
        assertEquals(2, listedAll.size)
        val listedHome = capturedGateway.listWorkers(SuperRuntime.localBotRef("home"))
        assertEquals(listOf(localStarted.workerId), listedHome.map { it.workerId })

        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()
        val routerPromptCountBeforeDirectChat = routerHandles.single().prompts.size
        orchestrator.sendToWorker(localStarted.workerId, "Direct hello from user")
        runCurrent()
        assertEquals(2, workerPrompts.getValue(localStarted.workerId).size)
        assertEquals("Direct hello from user", workerPrompts.getValue(localStarted.workerId).last().first)

        nowMs += 5_000L
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(routerPromptCountBeforeDirectChat, routerHandles.single().prompts.size)

        orchestrator.sendToWorker(localStarted.workerId, "hold-busy")
        runCurrent()
        assertEquals(WorkerStatus.RUNNING, pool.inspect(localStarted.workerId).record.status)
        orchestrator.sendToWorker(localStarted.workerId, "Interrupt and focus on A")
        runCurrent()
        assertEquals(
            QueueBehavior.Steer,
            workerHandles.getValue(localStarted.workerId).prompts.last().behavior,
        )
        busyLocalGate.complete(Unit)
        runCurrent()

        orchestrator.sendToWorker(remoteStarted.workerId, "Direct remote follow-up")
        runCurrent()
        assertEquals(listOf(false), fakeRemote.continuedNotifySuper)

        remoteStarted.let {
            fakeRemote.remoteStatusOverride[it.workerId] = WorkerStatus.RUNNING
        }
        taskStore.upsertWorker(remoteStarted.copy(status = WorkerStatus.RUNNING))
        orchestrator.sendToWorker(remoteStarted.workerId, "Direct remote steer")
        runCurrent()
        assertEquals(listOf("Direct remote steer"), fakeRemote.steeredMessages)

        remoteOnline = false
        orchestrator.refreshBlueprints()
        runCurrent()
        val offlineErr = runCatching {
            orchestrator.sendToWorker(remoteStarted.workerId, "Should fail offline")
        }.exceptionOrNull()
        assertTrue(offlineErr is IllegalStateException)
        taskStore.close()
    }

    @Test
    fun superThreadEngineSplitsStreamingBubblesOnSteerAndMessageStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val superDir = File(root, "super-steer")
        val handles = mutableListOf<FakeSessionHandle>()
        val firstTurnGate = CompletableDeferred<Unit>()
        val engine = SuperThreadEngine(
            superDir = superDir,
            providers = FakeProviders(),
            initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
            sessionFactory = { _, sessionFile, _, _, _ ->
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-steer\"}\n")
                FakeSessionHandle("router-steer", sessionFile.absolutePath).also { handle ->
                    handles += handle
                    handle.onPrompt = { text ->
                        if (text == "Start long job") {
                            handle.busy = true
                            val partial = SdkAssistantMessage(
                                content = mutableListOf(TextContent("First phase")),
                                api = "fake",
                                provider = "fake",
                                model = "fake-model",
                                usage = Usage.empty(),
                                stopReason = StopReason.Pending,
                                timestamp = nowMs,
                            )
                            handle.emit(
                                SessionEvent.AgentEventReceived(
                                    AgentEvent.MessageUpdate(
                                        message = partial.asAgentMessage(),
                                        assistantMessageEvent = TextDeltaEvent(0, "First phase", partial),
                                    ),
                                ),
                            )
                            firstTurnGate.await()
                            handle.busy = false
                        }
                    }
                }
            },
            scope = backgroundScope,
            idleDebounceMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        val turnJob = async { engine.send("Start long job") }
        runCurrent()
        val handle = handles.single()
        assertEquals("First phase", engine.state.value.messages.last().text)

        engine.steer("Actually switch focus")
        runCurrent()

        val afterSteer = engine.state.value.messages
        assertEquals(3, afterSteer.size)
        assertEquals("First phase", afterSteer[1].text)
        assertEquals(MessageStatus.DONE, afterSteer[1].status)
        assertFalse(afterSteer[1].streaming)
        assertEquals("Actually switch focus", afterSteer[2].text)

        val postSteerPartial = SdkAssistantMessage(
            content = mutableListOf(TextContent("Second phase")),
            api = "fake",
            provider = "fake",
            model = "fake-model",
            usage = Usage.empty(),
            stopReason = StopReason.Pending,
            timestamp = nowMs + 10,
        )
        handle.emit(
            SessionEvent.AgentEventReceived(
                AgentEvent.MessageUpdate(
                    message = postSteerPartial.asAgentMessage(),
                    assistantMessageEvent = TextDeltaEvent(0, "Second phase", postSteerPartial),
                ),
            ),
        )
        runCurrent()
        assertEquals(4, engine.state.value.messages.size)
        assertEquals("Second phase", engine.state.value.messages.last().text)

        handle.emit(
            SessionEvent.AgentEventReceived(
                AgentEvent.MessageStart(
                    message = UserMessage(PlainTextContent("[Worker update] wrk-1 done"), nowMs + 20).asAgentMessage(),
                ),
            ),
        )
        runCurrent()
        assertEquals(4, engine.state.value.messages.size)
        assertEquals(MessageStatus.DONE, engine.state.value.messages.last().status)
        assertFalse(engine.state.value.messages.any { it.text.contains("[Worker update]") })

        firstTurnGate.complete(Unit)
        turnJob.await()
    }

    private class FakeProviders : ProviderDirectory {
        override val summaries: List<ProviderSummary> = emptyList()
        override fun summary(providerId: String): ProviderSummary? = null
        override fun models(providerId: String): List<ModelOption> = emptyList()
        override fun resolveConfig(config: ModelConfig): ModelConfig = config
        override fun sdkModel(providerId: String, modelId: String): Model = fakeModel
        override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
    }

    private open class FakeRemoteWorkerGateway(
        private val remoteArtifactFile: File,
    ) : RemoteWorkerGateway {
        private val _progress = MutableSharedFlow<WorkerProgressEvent>()
        override val progressEvents: SharedFlow<WorkerProgressEvent> = _progress.asSharedFlow()
        private val _milestones = MutableSharedFlow<WorkerMilestone>()
        override val milestones: SharedFlow<WorkerMilestone> = _milestones.asSharedFlow()
        var reattachCalls: Int = 0
        val startedTasks = mutableListOf<String>()
        val startedImages = mutableListOf<List<ImagePart>>()
        val continuedNotifySuper = mutableListOf<Boolean>()
        val steeredMessages = mutableListOf<String>()
        val remoteStatusOverride = mutableMapOf<String, WorkerStatus>()

        suspend fun emitMilestone(milestone: WorkerMilestone) {
            _milestones.emit(milestone)
        }

        override suspend fun reattachOnlineWorkers() {
            reattachCalls += 1
        }

        override fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot> =
            MutableStateFlow(WorkerSessionSnapshot(workerId = workerId)).asStateFlow()

        override suspend fun loadWorkerSession(worker: WorkerRunRecord) = Unit

        override suspend fun listRemoteWorkers(deviceId: String, botId: String?): List<WorkerRunRecord> = emptyList()

        override suspend fun startWorker(
            blueprint: BotBlueprint,
            task: String,
            images: List<ImagePart>,
            modelOverride: ModelConfig?,
        ): WorkerRunRecord {
            startedTasks += task
            startedImages += images
            return WorkerRunRecord(
                workerId = "wrk-remote-${startedTasks.size}",
                botRef = blueprint.ref,
                botId = blueprint.botId,
                botName = blueprint.name,
                deviceId = blueprint.deviceId,
                deviceName = blueprint.deviceName,
                title = task.take(24),
                status = WorkerStatus.IDLE,
                lastOutcome = WorkerOutcome.SUCCEEDED,
                turnCount = 1,
                createdAt = 1L,
                updatedAt = 2L,
                lastProgressAt = 2L,
            )
        }

        override suspend fun continueWorker(
            blueprint: BotBlueprint,
            worker: WorkerRunRecord,
            message: String,
            images: List<ImagePart>,
            notifySuper: Boolean,
            silentPolling: Boolean,
        ): WorkerRunRecord {
            continuedNotifySuper += notifySuper
            return worker.copy(turnCount = worker.turnCount + 1)
        }

        override suspend fun steerWorker(
            worker: WorkerRunRecord,
            message: String,
            images: List<ImagePart>,
            silentPolling: Boolean,
        ) {
            steeredMessages += message
        }

        override suspend fun stopWorker(worker: WorkerRunRecord) = Unit
        override suspend fun answerApproval(
            worker: WorkerRunRecord,
            requestId: String,
            answers: List<QuestionAnswer>,
            bySuper: Boolean,
        ) = Unit
        override suspend fun dismissApproval(
            worker: WorkerRunRecord,
            requestId: String,
            bySuper: Boolean,
        ) = Unit
        override suspend fun inspectWorker(worker: WorkerRunRecord, tail: Int): WorkerInspectResult = error("unused")
        override suspend fun deleteWorker(worker: WorkerRunRecord) = Unit
        override suspend fun resolveArtifactFile(artifact: WorkerArtifactRef): File = remoteArtifactFile
    }

    @Test
    fun routesWorkerTimerRemindersAndFormatsWaitingInputTimeoutIntoSuperThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val workerPrompts = mutableListOf<String>()
        val routerHandles = mutableListOf<FakeSessionHandle>()
        val timerService = TimerService(
            runtime = FakeAgentRuntime(),
            store = TimerStore(File(root, "timers"), clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)),
            scope = backgroundScope,
            clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC),
            ioDispatcher = dispatcher,
        )
        val pool = LocalWorkerPool(
            workersRoot = File(root, "super/workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                FakeSessionHandle("sdk-$workerId", sessionFile.absolutePath).apply {
                    onPrompt = { text ->
                        workerPrompts += text
                    }
                }
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            stallTimeoutMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val fakeRemote = FakeRemoteWorkerGateway(File(root, "unused.txt"))
        lateinit var capturedGateway: WorkerOrchestrationGateway
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            remoteWorkerGateway = fakeRemote,
            localBotsProvider = { listOf(BotBlueprint(SuperRuntime.localBotRef("home"), "home", "PI")) },
            timerService = { timerService },
            scope = backgroundScope,
            coalesceWindowMs = 100L,
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        sessionFile.parentFile?.mkdirs()
                        sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-timer\"}\n")
                        FakeSessionHandle("router-timer", sessionFile.absolutePath).also { routerHandles += it }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )
        orchestrator.bindTimerHandler()
        orchestrator.ensureStarted()
        runCurrent()

        val worker = capturedGateway.startWorker("PI", "Monitor build")
        runCurrent()
        val delivered = timerService.externalReminderHandler!!.invoke(
            SuperRuntime.workerSessionId(worker.workerId),
            "Check latest build status",
            "build-poll",
        )
        runCurrent()
        assertTrue(delivered)
        assertTrue(workerPrompts.any { it.contains("build-poll") && it.contains("Check latest build status") })

        fakeRemote.emitMilestone(
            WorkerMilestone.WaitingInputTimeout(
                workerId = "wrk-remote-timeout",
                botRef = SuperRuntime.remoteBotRef("dev-2", "home"),
                botName = "Desktop PI",
                requestId = "req-9",
                questions = listOf(
                    UserQuestion(
                        id = "q1",
                        header = "Approve",
                        question = "Delete cache?",
                        options = listOf(QuestionOption("Yes"), QuestionOption("No")),
                    ),
                ),
                waitedMs = 180_000L,
                timestamp = nowMs,
            ),
        )
        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()

        val routerPromptText = routerHandles.single().prompts.joinToString("\n") { it.text }
        assertTrue(routerPromptText.contains("[Worker waiting-input timeout]"))
        assertTrue(routerPromptText.contains("req-9"))
        assertTrue(routerPromptText.contains("Delete cache?"))

        orchestrator.compactWorker(worker.workerId)
        orchestrator.compactSuper()
        runCurrent()
        taskStore.close()
    }

    @Test
    fun honorsDynamicSettingsRetryAfterEnsureStartedFailureColdStartRemoteSessionAndDeletesWorkerTimers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val timerDir = File(root, "timers").apply { mkdirs() }
        val timerStore = TimerStore(timerDir, clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC))
        val timerService = TimerService(
            runtime = FakeAgentRuntime(),
            store = timerStore,
            scope = backgroundScope,
            clock = Clock.fixed(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC),
            ioDispatcher = dispatcher,
        )
        var settings = SuperRuntimeSettings(maxConcurrentWorkers = 7, maxSlotsPerBot = 7)
        val poolGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath).apply {
                    suspendPrompt = true
                    val g = CompletableDeferred<Unit>().also { poolGates[workerId] = it }
                    onPrompt = { }
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
            settingsProvider = { settings },
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val remoteBotRef = SuperRuntime.remoteBotRef("dev-2", "home")
        val remoteSessionFlow = MutableStateFlow(
            WorkerSessionSnapshot(workerId = "wrk-cold-remote", loaded = true),
        )
        val remoteRecord = WorkerRunRecord(
            workerId = "wrk-cold-remote",
            botRef = remoteBotRef,
            botId = "home",
            botName = "Desktop PI",
            deviceId = "dev-2",
            deviceName = "Desktop",
            title = "Cold remote",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
        )
        val fakeRemote = object : FakeRemoteWorkerGateway(File(root, "remote-artifact.txt")) {
            override fun observeWorkerSession(workerId: String): StateFlow<WorkerSessionSnapshot> =
                if (workerId == remoteRecord.workerId) remoteSessionFlow else super.observeWorkerSession(workerId)
        }
        var shouldFailInit = true
        lateinit var capturedGateway: WorkerOrchestrationGateway
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            remoteWorkerGateway = fakeRemote,
            localBotsProvider = {
                listOf(BotBlueprint(SuperRuntime.HOME_BOT_REF, "home", "PI"))
            },
            remoteBotsProvider = {
                listOf(BotBlueprint(remoteBotRef, "home", "Desktop PI", deviceId = "dev-2", deviceName = "Desktop"))
            },
            timerService = { timerService },
            scope = backgroundScope,
            settingsProvider = { settings },
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        if (shouldFailInit) {
                            shouldFailInit = false
                            throw java.io.IOException("transient init failure")
                        }
                        FakeSessionHandle("router-retry", sessionFile.absolutePath)
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )

        val firstAttempt = runCatching { orchestrator.ensureStarted() }.exceptionOrNull()
        assertTrue(firstAttempt is java.io.IOException)
        orchestrator.ensureStarted()
        runCurrent()

        assertEquals(7, orchestrator.blueprints.value.first { it.ref == SuperRuntime.HOME_BOT_REF }.maxWorkerSlots)

        val coldObserver = orchestrator.observeWorkerSession(remoteRecord.workerId)
        val observeJob = backgroundScope.launch { coldObserver.collect {} }
        runCurrent()
        assertFalse(coldObserver.value.loaded)
        taskStore.upsertWorker(remoteRecord)
        runCurrent()
        assertTrue(coldObserver.value.loaded)
        observeJob.cancel()

        val w7Ids = (1..7).map { idx ->
            capturedGateway.startWorker("PI", "Task $idx").workerId
        }
        assertEquals(7, pool.usedSlotsForBot("home"))

        val targetId = w7Ids.first()
        timerStore.create(
            io.github.whywood.superpi.runtime.NewTimer(
                name = "watch",
                prompt = "check",
                trigger = io.github.whywood.superpi.runtime.TimerTrigger.Daily(java.time.LocalTime.of(9, 0)),
                sessionId = SuperRuntime.workerSessionId(targetId),
                sessionFile = "",
                cwd = root.absolutePath,
            ),
        )
        assertEquals(1, timerService.list().size)
        orchestrator.stopWorker(targetId)
        runCurrent()
        orchestrator.deleteWorker(targetId)
        runCurrent()
        assertEquals(0, timerService.list().size)

        taskStore.close()
    }

    @Test
    fun superThreadEngineInjectsDynamicAgentsMdAndOverrideSections() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val localSuperDir = File(root, "super-instructions-test")
        lateinit var capturedProvider: suspend () -> Map<String, String>
        val engine = SuperThreadEngine(
            superDir = localSuperDir,
            providers = FakeProviders(),
            initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
            sessionFactory = { _, sessionFile, _, _, extraSectionsProvider ->
                capturedProvider = extraSectionsProvider
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-instr\"}\n")
                FakeSessionHandle("router-instr", sessionFile.absolutePath)
            },
            scope = backgroundScope,
            idleDebounceMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        engine.ensureLoaded()
        runCurrent()

        val initialSections = capturedProvider()
        assertFalse(initialSections.containsKey("super_instructions"))

        val routerWorkspace = File(localSuperDir, "router/workspace").apply { mkdirs() }
        val agentsFile = File(routerWorkspace, "AGENTS.md")
        agentsFile.writeText("Always report disk usage in GB.")
        val withBase = capturedProvider()
        assertTrue(withBase.getValue("super_instructions").contains("Always report disk usage in GB."))
        assertTrue(withBase.getValue("super_instructions").contains("<super_instructions>"))

        val overrideFile = File(routerWorkspace, "AGENTS.override.md")
        overrideFile.writeText("Use TB instead.")
        val withOverride = capturedProvider()
        assertTrue(withOverride.getValue("super_instructions").contains("Use TB instead."))
        assertFalse(withOverride.getValue("super_instructions").contains("Always report disk usage in GB."))
    }

    @Test
    fun namedWorkerFlowsIntoRecordTitleAndMilestoneReceipt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = newTaskStore(dispatcher)
        val routerHandles = mutableListOf<FakeSessionHandle>()
        lateinit var capturedGateway: WorkerOrchestrationGateway

        val pool = LocalWorkerPool(
            workersRoot = File(superDir, "workers-named"),
            taskStore = taskStore,
            sessionFactory = { _, workerId, _, sessionFile, _ ->
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{}\n")
                FakeSessionHandle("worker-sdk-$workerId", sessionFile.absolutePath)
            },
            resolveBotSpec = { botId ->
                LocalWorkerBotSpec(botId, "PI", null, File(root, "bot-$botId"), ModelConfig())
            },
            scope = backgroundScope,
            idleDebounceMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )
        val orchestrator = SuperOrchestrator(
            taskStore = taskStore,
            localWorkerPool = pool,
            localBotsProvider = { listOf(BotBlueprint(SuperRuntime.HOME_BOT_REF, "home", "PI")) },
            scope = backgroundScope,
            coalesceWindowMs = 1_000L,
            threadEngineFactory = { gateway, _, _ ->
                capturedGateway = gateway
                SuperThreadEngine(
                    superDir = superDir,
                    providers = FakeProviders(),
                    initialModelConfig = { ModelConfig("fake", "fake-model", ThinkingLevel.OFF) },
                    sessionFactory = { _, sessionFile, _, _, _ ->
                        sessionFile.parentFile?.mkdirs()
                        sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-named\"}\n")
                        FakeSessionHandle("router-named", sessionFile.absolutePath).also { routerHandles += it }
                    },
                    scope = backgroundScope,
                    idleDebounceMs = 60_000L,
                    clock = { nowMs },
                    ioDispatcher = dispatcher,
                )
            },
        )

        orchestrator.ensureStarted()
        runCurrent()

        val record = capturedGateway.startWorker(
            botQuery = "PI",
            task = "Inspect internal storage usage and list largest folders",
            attachmentPaths = emptyList(),
            modelOverride = null,
            name = "Disk Inspector",
        )
        assertEquals("Disk Inspector", record.title)
        runCurrent()
        advanceTimeBy(1_100L)
        runCurrent()

        val receipt = routerHandles.single().prompts.single().text
        assertTrue(receipt.contains("| name: Disk Inspector |"))
        taskStore.close()
    }

    @Test
    fun superThreadEngineSyncsDefaultModelUntilOverridden() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val localSuperDir = File(root, "super-model-sync-test")
        val deepseekModel = fakeModel.copy(id = "deepseek-chat", name = "DeepSeek Chat", provider = "deepseek")
        val opencodeModel = fakeModel.copy(id = "claude-sonnet", name = "Claude Sonnet", provider = "opencode")
        val customModel = fakeModel.copy(id = "gpt-5", name = "GPT-5", provider = "openai")
        val catalog = object : ProviderDirectory {
            override val summaries: List<ProviderSummary> = emptyList()
            override fun summary(providerId: String): ProviderSummary? = null
            override fun models(providerId: String): List<ModelOption> = when (providerId) {
                "deepseek" -> listOf(ModelOption("deepseek", "deepseek-chat", "DeepSeek Chat", listOf(ThinkingLevel.OFF)))
                "opencode" -> listOf(ModelOption("opencode", "claude-sonnet", "Claude Sonnet", listOf(ThinkingLevel.OFF)))
                "openai" -> listOf(ModelOption("openai", "gpt-5", "GPT-5", listOf(ThinkingLevel.OFF)))
                else -> emptyList()
            }
            override fun resolveConfig(config: ModelConfig): ModelConfig = config
            override fun sdkModel(providerId: String, modelId: String): Model = when (providerId) {
                "opencode" -> opencodeModel
                "openai" -> customModel
                else -> deepseekModel
            }
            override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"
        }
        val engine = SuperThreadEngine(
            superDir = localSuperDir,
            providers = catalog,
            initialModelConfig = { ModelConfig("deepseek", "deepseek-chat", ThinkingLevel.OFF) },
            sessionFactory = { _, sessionFile, config, _, _ ->
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText("{\"type\":\"session\",\"id\":\"router-sync\"}\n")
                FakeSessionHandle("router-sync", sessionFile.absolutePath).apply {
                    model = catalog.sdkModel(config.providerId, config.modelId)
                }
            },
            scope = backgroundScope,
            idleDebounceMs = 60_000L,
            clock = { nowMs },
            ioDispatcher = dispatcher,
        )

        engine.ensureLoaded()
        runCurrent()
        assertEquals("deepseek", engine.state.value.providerId)

        engine.syncDefaultModel(ModelConfig("opencode", "claude-sonnet", ThinkingLevel.OFF))
        runCurrent()
        assertEquals("opencode", engine.state.value.providerId)
        assertEquals("claude-sonnet", engine.state.value.modelId)

        engine.changeModel("openai", "gpt-5", ThinkingLevel.OFF)
        runCurrent()
        assertEquals("openai", engine.state.value.providerId)

        engine.syncDefaultModel(ModelConfig("deepseek", "deepseek-chat", ThinkingLevel.OFF))
        runCurrent()
        assertEquals("openai", engine.state.value.providerId)
        assertEquals("gpt-5", engine.state.value.modelId)
    }
}

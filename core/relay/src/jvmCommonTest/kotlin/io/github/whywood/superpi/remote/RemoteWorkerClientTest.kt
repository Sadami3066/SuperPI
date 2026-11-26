package io.github.whywood.superpi.remote

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ImagePart
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerMilestone
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import io.github.whywood.superpi.runtime.superagent.SuperTaskStore
import io.github.whywood.superpi.runtime.superagent.WorkerHistoryWireCodec
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class RemoteWorkerClientTest {
    private lateinit var root: File
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("remote-worker-client-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restartReattachesOnlineWorkersAfterStoreLoadsWithoutDuplicateResume() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val superDir = File(root, "super")
        val initialStore = SuperTaskStore(superDir, clock = { nowMs }, ioDispatcher = dispatcher)
        initialStore.upsertWorker(
            WorkerRunRecord(
                workerId = "wrk-reattach",
                botRef = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
                botId = "home",
                botName = "Remote PI",
                deviceId = FakeRemoteTransport.DEVICE_ID,
                deviceName = "Desktop",
                title = "Long build",
                status = WorkerStatus.RUNNING,
                lastOutcome = WorkerOutcome.NONE,
                turnCount = 1,
                createdAt = nowMs,
                updatedAt = nowMs,
                lastProgressAt = nowMs,
                remoteLastSeq = 3L,
            ),
        )
        runCurrent()
        initialStore.close()

        val reopenedStore = SuperTaskStore(superDir, clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        transport.result = { call ->
            if (call.method == "worker.resume") {
                buildJsonObject {
                    put("evictedBefore", false)
                    put("seq", 4L)
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", "wrk-reattach")
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Long build")
                            put("status", WorkerStatus.RUNNING.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 1)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                }
            } else {
                buildJsonObject {}
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = reopenedStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        runCurrent()
        client.reattachOnlineWorkers()
        runCurrent()

        val resumeCalls = transport.calls.filter { it.method == "worker.resume" }
        assertEquals(1, resumeCalls.size)
        reopenedStore.close()
    }

    @Test
    fun suppressesSuperMilestonesWhenNotifySuperIsFalseAndStreamsSessionSnapshots() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super"), clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        val inspectHistory = listOf(
            AssistantMessage(id = 1L, role = AssistantRole.USER, text = "Initial prompt"),
            AssistantMessage(id = 2L, role = AssistantRole.ASSISTANT, text = "Initial answer", status = MessageStatus.DONE),
        )
        var includeInspectMessages = true
        transport.result = { call ->
            when (call.method) {
                "worker.continue" -> buildJsonObject {
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", "wrk-stream")
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Task")
                            put("status", WorkerStatus.RUNNING.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 2)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                }

                "worker.inspect" -> buildJsonObject {
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", "wrk-stream")
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Task")
                            put("status", WorkerStatus.IDLE.name)
                            put("lastOutcome", WorkerOutcome.SUCCEEDED.name)
                            put("lastSummary", "Fallback summary")
                            put("turnCount", 2)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                    put(
                        "steps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("callId", "c1")
                                    put("toolName", "bash")
                                    put("argsJson", "{}")
                                    put("status", ToolActivityStatus.DONE.name)
                                    put("outputPreview", "ok")
                                    put("isError", false)
                                    put("updatedAt", nowMs)
                                },
                            )
                        },
                    )
                    if (includeInspectMessages) {
                        put("messages", WorkerHistoryWireCodec.encodeMessages(inspectHistory))
                    }
                }

                else -> buildJsonObject {}
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        val milestones = mutableListOf<WorkerMilestone>()
        val collectJob = backgroundScope.launch(dispatcher) {
            client.milestones.collect { milestones += it }
        }
        val blueprint = BotBlueprint(
            ref = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            name = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            online = true,
            supportsSuperWorker = true,
        )
        val baseRecord = WorkerRunRecord(
            workerId = "wrk-stream",
            botRef = blueprint.ref,
            botId = "home",
            botName = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            title = "Task",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
            steps = listOf(WorkerStepSnapshot("c1", "bash", "{}", ToolActivityStatus.DONE, "ok")),
        )
        taskStore.upsertWorker(baseRecord)
        runCurrent()

        val sessionFlow = client.observeWorkerSession("wrk-stream")
        client.loadWorkerSession(baseRecord)
        runCurrent()
        assertTrue(sessionFlow.value.loaded)
        assertEquals(2, sessionFlow.value.messages.size)

        val continued = client.continueWorker(
            blueprint = blueprint,
            worker = baseRecord,
            message = "Direct manual follow-up",
            images = listOf(ImagePart("Zm9v", "image/png")),
            notifySuper = false,
        )
        val routeId = transport.latestRouteId()
        assertEquals(WorkerStatus.RUNNING, continued.status)

        transport.emit(
            routeId,
            1L,
            buildJsonObject {
                put("type", "message_update")
                put(
                    "assistantMessageEvent",
                    buildJsonObject {
                        put("type", "text_delta")
                        put("delta", "Streaming part 1")
                    },
                )
            },
        )
        transport.emit(
            routeId,
            2L,
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "tc-1")
                put("name", "read")
                put("args", buildJsonObject { put("path", "a.txt") })
            },
        )
        transport.emit(
            routeId,
            3L,
            buildJsonObject {
                put("type", "tool_result")
                put("callId", "tc-1")
                put("name", "read")
                put("result", "hello")
                put("isError", false)
            },
        )
        runCurrent()

        assertTrue(sessionFlow.value.streaming)
        assertEquals("Streaming part 1", sessionFlow.value.messages.last().text)
        assertEquals(2, sessionFlow.value.messages.last().steps.size)

        client.steerWorker(continued, "Switch target")
        runCurrent()
        assertTrue(sessionFlow.value.messages.any { it.role == AssistantRole.USER && it.text == "Switch target" })

        transport.emit(
            routeId,
            4L,
            buildJsonObject {
                put("type", "agent_end")
                put("status", "stop")
            },
        )
        runCurrent()
        assertFalse(sessionFlow.value.streaming)
        assertTrue(milestones.isEmpty())

        includeInspectMessages = false
        val clientLegacy = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-legacy"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        val legacySession = clientLegacy.observeWorkerSession("wrk-stream")
        clientLegacy.loadWorkerSession(baseRecord.copy(lastSummary = "Fallback summary"))
        runCurrent()
        assertTrue(legacySession.value.loaded)
        assertEquals(1, legacySession.value.messages.size)
        assertEquals("Fallback summary", legacySession.value.messages.single().text)
        assertTrue(legacySession.value.messages.single().steps.single() is AssistantStep.Tool)

        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun smartWatchdogQuestionTimeoutSilentPollingAndControlMethodsWorkOnRemoteClient() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-remote"), clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        transport.result = { call ->
            when (call.method) {
                "worker.start" -> buildJsonObject {
                    put("workerId", call.params["workerId"] ?: kotlinx.serialization.json.JsonPrimitive("wrk-rem"))
                    put("status", "RUNNING")
                    put("title", "Remote watcher")
                }
                "worker.continue" -> buildJsonObject {
                    put("workerId", call.params["workerId"]!!)
                    put("status", "RUNNING")
                }
                "worker.compact" -> buildJsonObject {
                    put("workerId", call.params["workerId"]!!)
                    put("status", "IDLE")
                }
                "worker.set_model" -> buildJsonObject {
                    put("providerId", "openai")
                    put("modelId", "gpt-5")
                    put("thinkingLevel", "HIGH")
                }
                "worker.inspect" -> buildJsonObject {
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", call.params["workerId"]!!)
                            put("botId", "home")
                            put("botName", "PI")
                            put("title", "Remote watcher")
                            put("status", "WAITING_INPUT")
                            put("pendingRequestId", "req-rem-1")
                            put(
                                "pendingQuestion",
                                buildJsonObject {
                                    put("requestId", "req-rem-1")
                                    put("toolCallId", "tc-q")
                                    put(
                                        "questions",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("id", "q1")
                                                    put("header", "Confirm")
                                                    put("question", "Continue remote deploy?")
                                                    put("options", buildJsonArray {})
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put(
                        "usage",
                        buildJsonObject {
                            put("contextTokens", 3_200L)
                            put("contextWindow", 128_000L)
                            put("inputTokens", 2_000L)
                            put("outputTokens", 1_200L)
                            put("cacheReadTokens", 0L)
                            put("cacheWriteTokens", 0L)
                            put("cost", 0.02)
                        },
                    )
                    put("steps", buildJsonArray {})
                }
                else -> buildJsonObject { put("resolved", true) }
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-remote"),
            scope = backgroundScope,
            stallTimeoutMs = 2_000L,
            questionTimeoutMs = 1_500L,
            clock = { nowMs },
        )
        val milestones = mutableListOf<WorkerMilestone>()
        val collectJob = backgroundScope.launch(dispatcher) {
            client.milestones.collect { milestones += it }
        }
        val blueprint = BotBlueprint(
            ref = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            name = "Desktop PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            online = true,
            supportsSuperWorker = true,
        )

        val started = client.startWorker(
            blueprint = blueprint,
            task = "Monitor remote service",
            modelOverride = ModelConfig("openai", "gpt-5", ThinkingLevel.HIGH),
        )
        val startCall = transport.calls.last { it.method == "worker.start" }
        assertEquals("openai", startCall.params["providerId"]?.jsonPrimitive?.content)
        val route1 = transport.latestRouteId()

        transport.emit(
            route1,
            1L,
            buildJsonObject {
                put("type", "tool_call")
                put("callId", "tc-watch")
                put("name", "bash")
                put("args", buildJsonObject { put("command", "poll") })
            },
        )
        runCurrent()

        nowMs += 11_000L
        advanceTimeBy(11_000L)
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.Stalled })

        transport.emit(
            route1,
            2L,
            buildJsonObject {
                put("type", "tool_result")
                put("callId", "tc-watch")
                put("name", "bash")
                put("result", "ok")
                put("isError", false)
            },
        )
        transport.emit(
            route1,
            3L,
            buildJsonObject {
                put("type", "plugin_custom_message")
                put("customType", "status.card")
                put("payload", "{\"state\":\"ok\"}")
                put("text", "Custom card rendered")
            },
        )
        runCurrent()
        val sessionSnap = client.observeWorkerSession(started.workerId).value
        assertTrue(
            sessionSnap.messages.flatMap { it.steps }.any { it is AssistantStep.CustomCard && it.customType == "status.card" },
        )

        nowMs += 11_000L
        advanceTimeBy(11_000L)
        runCurrent()
        assertTrue(milestones.any { it is WorkerMilestone.Stalled })
        assertEquals(WorkerStatus.STALLED, taskStore.getWorker(started.workerId)?.status)

        transport.emit(
            route1,
            4L,
            buildJsonObject {
                put("type", "approval_request")
                put("approvalId", "req-rem-1")
                put("requestId", "req-rem-1")
                put("toolCallId", "tc-q")
                put(
                    "questions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("id", "q1")
                                put("header", "Confirm")
                                put("question", "Continue remote deploy?")
                                put("options", buildJsonArray {})
                            },
                        )
                    },
                )
            },
        )
        runCurrent()

        val prematureBySuper = runCatching {
            client.answerApproval(
                worker = taskStore.getWorker(started.workerId)!!,
                requestId = "req-rem-1",
                answers = listOf(QuestionAnswer("q1", selected = listOf("Yes"))),
                bySuper = true,
            )
        }.exceptionOrNull()
        assertTrue(prematureBySuper is WorkerConflictException)

        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(client.isQuestionEscalatedToSuper(started.workerId))
        assertTrue(milestones.any { it is WorkerMilestone.WaitingInputTimeout && it.requestId == "req-rem-1" })

        client.answerApproval(
            worker = taskStore.getWorker(started.workerId)!!,
            requestId = "req-rem-1",
            answers = listOf(QuestionAnswer("q1", selected = listOf("Yes"))),
            bySuper = true,
        )
        transport.emit(
            route1,
            5L,
            buildJsonObject {
                put("type", "agent_end")
                put("status", "stop")
            },
        )
        runCurrent()

        val inspected = client.inspectWorker(taskStore.getWorker(started.workerId)!!, tail = 5)
        assertNotNull(inspected.usage)
        assertEquals(3_200L, inspected.usage?.contextTokens)
        assertEquals("req-rem-1", inspected.record.pendingQuestion?.requestId)

        milestones.clear()
        val pollContinued = client.continueWorker(
            blueprint = blueprint,
            worker = taskStore.getWorker(started.workerId)!!.copy(status = WorkerStatus.IDLE),
            message = "[Reminder] silent poll",
            silentPolling = true,
        )
        val routePoll = transport.latestRouteId()
        transport.emit(
            routePoll,
            1L,
            buildJsonObject {
                put("type", "message_update")
                put(
                    "assistantMessageEvent",
                    buildJsonObject {
                        put("type", "text_delta")
                        put("delta", "[NO_UPDATE]")
                    },
                )
            },
        )
        transport.emit(
            routePoll,
            2L,
            buildJsonObject {
                put("type", "agent_end")
                put("status", "stop")
            },
        )
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.Completed })

        client.compactWorker(pollContinued.copy(status = WorkerStatus.IDLE))
        client.setWorkerModel(pollContinued.copy(status = WorkerStatus.IDLE), "openai", "gpt-5", ThinkingLevel.HIGH)
        client.setWorkerThinking(pollContinued.copy(status = WorkerStatus.IDLE), ThinkingLevel.LOW)
        assertTrue(transport.calls.any { it.method == "worker.compact" })
        assertTrue(transport.calls.any { it.method == "worker.set_model" })

        client.dismissWorkerError(pollContinued.workerId)
        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun seqDedupFreshTurnAgentStartClearCompactionRetryToolPauseLocalQuestionClockAbortedAndMaxSlots() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-edge"), clock = { nowMs }, ioDispatcher = dispatcher)
        val blueprint = BotBlueprint(
            ref = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            name = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            online = true,
            supportsSuperWorker = true,
        )
        val waitingWorker = WorkerRunRecord(
            workerId = "wrk-q-skew",
            botRef = blueprint.ref,
            botId = "home",
            botName = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            title = "Skewed clock question",
            status = WorkerStatus.WAITING_INPUT,
            lastOutcome = WorkerOutcome.NONE,
            turnCount = 1,
            createdAt = nowMs - 10_000_000L,
            updatedAt = nowMs - 10_000_000L,
            lastProgressAt = nowMs - 10_000_000L,
            remoteLastSeq = 1L,
            pendingQuestion = PendingQuestion(
                requestId = "q-skew",
                sessionId = "wrk-q-skew",
                toolCallId = "tc-q",
                questions = listOf(UserQuestion("q1", "Approve", "Deploy?", listOf(QuestionOption("Yes")))),
            ),
        )
        taskStore.upsertWorker(waitingWorker)
        val transport = FakeRemoteTransport(online = true)
        transport.result = { call ->
            when (call.method) {
                "worker.list" -> buildJsonObject {
                    put("maxWorkersPerBot", 8)
                    put("workers", buildJsonArray {})
                }
                "worker.resume" -> buildJsonObject {
                    put("evictedBefore", false)
                    put("seq", 1L)
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", "wrk-q-skew")
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Skewed clock question")
                            put("status", WorkerStatus.WAITING_INPUT.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 1)
                            put("createdAt", waitingWorker.createdAt)
                            put("updatedAt", waitingWorker.updatedAt)
                            put("lastProgressAt", waitingWorker.lastProgressAt)
                        },
                    )
                }
                "worker.start" -> buildJsonObject {
                    val requestedId = call.params["workerId"]!!.jsonPrimitive.content
                    put("maxSlots", 8)
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", requestedId)
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", call.params["task"]!!.jsonPrimitive.content)
                            put("status", WorkerStatus.RUNNING.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 1)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                }
                "worker.continue" -> buildJsonObject {
                    val requestedId = call.params["workerId"]!!.jsonPrimitive.content
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", requestedId)
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Check")
                            put("status", WorkerStatus.RUNNING.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 2)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                }
                else -> buildJsonObject {}
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-edge"),
            scope = backgroundScope,
            stallTimeoutMs = 1_000L,
            questionTimeoutMs = 2_000L,
            clock = { nowMs },
        )
        val milestones = mutableListOf<WorkerMilestone>()
        val collectJob = backgroundScope.launch(dispatcher) { client.milestones.collect { milestones += it } }
        runCurrent()

        client.listRemoteWorkers(FakeRemoteTransport.DEVICE_ID, "home")
        assertEquals(8, client.maxSlotsForDevice(FakeRemoteTransport.DEVICE_ID))

        nowMs += 500L
        advanceTimeBy(500L)
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.WaitingInputTimeout })

        nowMs += 2_000L
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, milestones.count { it is WorkerMilestone.WaitingInputTimeout && it.workerId == "wrk-q-skew" })
        milestones.clear()

        val started = client.startWorker(blueprint, "First run")
        val route1 = transport.latestRouteId()
        transport.emit(route1, 1L, FakeEvents.textDelta("Hello"))
        transport.emit(route1, 1L, FakeEvents.textDelta(" DUPLICATE"))
        transport.emit(route1, 2L, FakeEvents.agentStart())
        transport.emit(route1, 3L, FakeEvents.textDelta("Clean summary"))
        transport.emit(route1, 4L, buildJsonObject { put("type", "compaction_start") })
        runCurrent()

        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.Stalled })

        transport.emit(route1, 5L, buildJsonObject { put("type", "compaction_end") })
        transport.emit(route1, 6L, buildJsonObject { put("type", "retry_start") })
        runCurrent()
        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.Stalled })

        transport.emit(route1, 7L, buildJsonObject { put("type", "retry_end") })
        transport.emit(route1, 8L, FakeEvents.toolCall("tc-empty", "screen_info", buildJsonObject {}))
        runCurrent()
        nowMs += 1_500L
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse(milestones.any { it is WorkerMilestone.Stalled })

        transport.emit(route1, 9L, FakeEvents.toolResult("tc-empty", "ok"))
        transport.emit(route1, 10L, FakeEvents.toolCall("tc-noargs", "bash", null))
        runCurrent()
        nowMs += 1_200L
        advanceTimeBy(1_200L)
        runCurrent()
        assertEquals(1, milestones.count { it is WorkerMilestone.Stalled && it.workerId == started.workerId })
        milestones.clear()

        transport.emit(route1, 11L, FakeEvents.agentEnd("stop"))
        transport.emit(route1, 12L, FakeEvents.settled())
        runCurrent()
        val completed1 = milestones.single() as WorkerMilestone.Completed
        assertEquals("Clean summary", completed1.summary)
        assertFalse(taskStore.currentWorker(started.workerId)!!.lastTurnSilent)
        milestones.clear()

        client.continueWorker(
            blueprint = blueprint,
            worker = taskStore.currentWorker(started.workerId)!!,
            message = "Silent check",
            notifySuper = true,
            silentPolling = true,
        )
        val route2 = transport.latestRouteId()
        transport.emit(route2, 1L, FakeEvents.textDelta("[NO_UPDATE]"))
        transport.emit(route2, 2L, FakeEvents.agentEnd("stop"))
        transport.emit(route2, 3L, FakeEvents.settled())
        runCurrent()
        assertTrue(milestones.isEmpty())
        val afterSilent = taskStore.currentWorker(started.workerId)!!
        assertTrue(afterSilent.lastTurnSilent)
        assertEquals("Clean summary", afterSilent.lastSummary)

        val abortedSummary = buildJsonObject {
            put("workerId", started.workerId)
            put("botId", "home")
            put("botName", "Remote PI")
            put("title", "Check")
            put("status", WorkerStatus.IDLE.name)
            put("lastOutcome", WorkerOutcome.ABORTED.name)
            put("turnCount", 2)
            put("createdAt", nowMs)
            put("updatedAt", nowMs)
            put("lastProgressAt", nowMs)
        }
        transport.result = { call ->
            when (call.method) {
                "worker.resume" -> buildJsonObject {
                    put("evictedBefore", false)
                    put("seq", 5L)
                    put("worker", abortedSummary)
                }
                "worker.inspect" -> buildJsonObject {
                    put("worker", abortedSummary)
                    put("steps", buildJsonArray {})
                }
                else -> buildJsonObject {}
            }
        }
        taskStore.updateWorker(started.workerId) { it.copy(status = WorkerStatus.RUNNING) }
        client.reattachOnlineWorkers()
        runCurrent()
        assertTrue(milestones.any { it is WorkerMilestone.Aborted && it.workerId == started.workerId })

        collectJob.cancel()
        taskStore.close()
    }

    @Test
    fun parsesCaseInsensitiveAndMinimalThinkingLevelInSetModelSetThinkingAndInspect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-think"), clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        transport.result = { call ->
            when (call.method) {
                "worker.set_model" -> {
                    val hasProvider = call.params.containsKey("providerId")
                    buildJsonObject {
                        put("workerId", "wrk-thk")
                        put("providerId", "openai")
                        put("modelId", if (hasProvider) "gpt-5" else "gpt-4")
                        put("thinkingLevel", if (hasProvider) "minimal" else "XHIGH")
                    }
                }

                "worker.inspect" -> buildJsonObject {
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", "wrk-thk")
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Thinking test")
                            put("status", WorkerStatus.IDLE.name)
                            put("lastOutcome", WorkerOutcome.SUCCEEDED.name)
                            put("turnCount", 1)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                    put("providerId", "anthropic")
                    put("modelId", "claude-sonnet")
                    put("thinkingLevel", "minimal")
                    put("steps", buildJsonArray {})
                }

                else -> buildJsonObject {}
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-think"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        val record = WorkerRunRecord(
            workerId = "wrk-thk",
            botRef = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            botName = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            title = "Thinking test",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
        )
        taskStore.upsertWorker(record)
        runCurrent()

        val snapFlow = client.observeWorkerSession("wrk-thk")

        client.setWorkerModel(record, "openai", "gpt-5", ThinkingLevel.HIGH)
        assertEquals(ThinkingLevel.LOW, snapFlow.value.thinkingLevel)

        client.setWorkerThinking(record, ThinkingLevel.MEDIUM)
        assertEquals(ThinkingLevel.XHIGH, snapFlow.value.thinkingLevel)

        client.inspectWorker(record, tail = 10)
        assertEquals(ThinkingLevel.LOW, snapFlow.value.thinkingLevel)
        assertEquals("anthropic", snapFlow.value.providerId)
        assertEquals("claude-sonnet", snapFlow.value.modelId)

        taskStore.close()
    }

    @Test
    fun streamClosuresAndReconnectReattachActiveWorkers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-reconnect"), clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        transport.result = { call ->
            when (call.method) {
                "worker.start" -> {
                    val workerId = call.params["workerId"]?.jsonPrimitive?.content.orEmpty()
                    buildJsonObject {
                        put(
                            "worker",
                            buildJsonObject {
                                put("workerId", workerId)
                                put("botId", "home")
                                put("botName", "Remote PI")
                                put("title", "Long build")
                                put("status", WorkerStatus.RUNNING.name)
                                put("lastOutcome", WorkerOutcome.NONE.name)
                                put("turnCount", 1)
                                put("createdAt", nowMs)
                                put("updatedAt", nowMs)
                                put("lastProgressAt", nowMs)
                            },
                        )
                    }
                }

                "worker.resume" -> {
                    val workerId = call.params["workerId"]?.jsonPrimitive?.content.orEmpty()
                    buildJsonObject {
                        put("evictedBefore", false)
                        put("seq", 5L)
                        put(
                            "worker",
                            buildJsonObject {
                                put("workerId", workerId)
                                put("botId", "home")
                                put("botName", "Remote PI")
                                put("title", "Long build")
                                put("status", WorkerStatus.RUNNING.name)
                                put("lastOutcome", WorkerOutcome.NONE.name)
                                put("turnCount", 1)
                                put("createdAt", nowMs)
                                put("updatedAt", nowMs)
                                put("lastProgressAt", nowMs)
                            },
                        )
                    }
                }

                else -> buildJsonObject {}
            }
        }

        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-reconnect"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        runCurrent()

        val blueprint = BotBlueprint(
            ref = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            name = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
        )
        val started = client.startWorker(blueprint, "Run long task", emptyList(), null)
        val startRoute = transport.latestRouteId()
        assertEquals(WorkerStatus.RUNNING, started.status)

        transport.emitStreamClosure(RemoteStreamClosed.PeerOffline(FakeRemoteTransport.DEVICE_ID))
        transport.setOnline(false)
        runCurrent()

        transport.emit(startRoute, 1L, FakeEvents.textDelta("stale"))
        runCurrent()
        assertFalse(
            client.observeWorkerSession(started.workerId).value.messages
                .any { it.text.contains("stale") },
        )

        transport.setOnline(true)
        runCurrent()

        val resumeCall = transport.calls.lastOrNull { it.method == "worker.resume" }
        assertNotNull(resumeCall)
        assertEquals(started.workerId, resumeCall?.params?.get("workerId")?.jsonPrimitive?.content)
        val resumeRoute = resumeCall?.routeId.orEmpty()

        transport.emit(resumeRoute, 6L, FakeEvents.textDelta("resumed-ok"))
        runCurrent()
        assertTrue(
            client.observeWorkerSession(started.workerId).value.messages
                .any { it.text.contains("resumed-ok") },
        )

        transport.emitStreamClosure(RemoteStreamClosed.RouteRejected(resumeRoute, "not_found", "dropped"))
        runCurrent()
        client.reattachOnlineWorkers()
        runCurrent()
        assertEquals(2, transport.calls.count { it.method == "worker.resume" })

        taskStore.close()
    }

    @Test
    fun cancellingStartOrContinueRollsBackOptimisticStateAndRoute() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taskStore = SuperTaskStore(File(root, "super-cancel"), clock = { nowMs }, ioDispatcher = dispatcher)
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteWorkerClient(
            transport = transport,
            taskStore = taskStore,
            artifactClient = RemoteArtifactClient(transport),
            remoteCacheRoot = File(root, "cache-cancel"),
            scope = backgroundScope,
            stallTimeoutMs = 0L,
            clock = { nowMs },
        )
        runCurrent()

        val blueprint = BotBlueprint(
            ref = SuperRuntime.remoteBotRef(FakeRemoteTransport.DEVICE_ID, "home"),
            botId = "home",
            name = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
        )

        val startGate = CompletableDeferred<Unit>()
        transport.beforeCallReturn = { call ->
            if (call.method == "worker.start") startGate.await()
        }
        val startJob = launch {
            client.startWorker(blueprint, "Initial task", emptyList(), null)
        }
        runCurrent()
        assertEquals(1, taskStore.workers.value.size)
        val orphanId = taskStore.workers.value.single().workerId
        val orphanRoute = transport.latestRouteId()

        startJob.cancel()
        runCurrent()
        assertTrue(taskStore.workers.value.isEmpty())
        transport.emit(orphanRoute, 1L, FakeEvents.textDelta("orphan"))
        runCurrent()
        assertTrue(client.observeWorkerSession(orphanId).value.messages.isEmpty())

        transport.beforeCallReturn = null
        val idleRecord = WorkerRunRecord(
            workerId = "wrk-cancel-continue",
            botRef = blueprint.ref,
            botId = "home",
            botName = "Remote PI",
            deviceId = FakeRemoteTransport.DEVICE_ID,
            deviceName = "Desktop",
            title = "Ready",
            status = WorkerStatus.IDLE,
            lastOutcome = WorkerOutcome.SUCCEEDED,
            turnCount = 1,
            createdAt = nowMs,
            updatedAt = nowMs,
            lastProgressAt = nowMs,
        )
        taskStore.upsertWorker(idleRecord)
        runCurrent()

        val continueGate = CompletableDeferred<Unit>()
        transport.beforeCallReturn = { call ->
            if (call.method == "worker.continue") continueGate.await()
        }
        val continueJob = launch {
            client.continueWorker(blueprint, idleRecord, "Follow up", emptyList())
        }
        runCurrent()
        assertEquals(WorkerStatus.RUNNING, taskStore.workers.value.single { it.workerId == idleRecord.workerId }.status)

        continueJob.cancel()
        runCurrent()
        val restored = taskStore.workers.value.single { it.workerId == idleRecord.workerId }
        assertEquals(WorkerStatus.IDLE, restored.status)
        assertEquals(1, restored.turnCount)
        assertTrue(client.observeWorkerSession(idleRecord.workerId).value.messages.isEmpty())

        transport.beforeCallReturn = null
        transport.result = { call ->
            if (call.method == "worker.continue") {
                buildJsonObject {
                    put("seq", 2L)
                    put(
                        "worker",
                        buildJsonObject {
                            put("workerId", idleRecord.workerId)
                            put("botId", "home")
                            put("botName", "Remote PI")
                            put("title", "Ready")
                            put("status", WorkerStatus.RUNNING.name)
                            put("lastOutcome", WorkerOutcome.NONE.name)
                            put("turnCount", 2)
                            put("createdAt", nowMs)
                            put("updatedAt", nowMs)
                            put("lastProgressAt", nowMs)
                        },
                    )
                }
            } else {
                buildJsonObject {}
            }
        }
        val restarted = client.continueWorker(blueprint, restored, "Retry follow up", emptyList())
        assertEquals(WorkerStatus.RUNNING, restarted.status)

        taskStore.close()
    }
}

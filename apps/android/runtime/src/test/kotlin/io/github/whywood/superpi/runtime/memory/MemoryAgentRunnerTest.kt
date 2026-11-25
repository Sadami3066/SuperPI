package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryDispatchIntent
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.Content
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.ToolResultMessage
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

class MemoryAgentRunnerTest {
    private lateinit var rootDir: File
    private var nowMs = 1_760_200_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-agent-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun transcriptExtractorUsesPureConversationTextOnlyAndRecoversBranchSwitchesAndPauseWindows() {
        val entries = listOf<SessionEntry>(
            userMsg("u1", "你好呀", nowMs + 1_000L),
            userMsg("w1", "[Worker update] build succeeded on /tmp/app", nowMs + 2_000L),
            userMsg("w2", "[Worker waiting-input timeout] please decide", nowMs + 3_000L),
            assistantToolMsg("a1", "bash tool call output should not appear", "tool-1", "bash", nowMs + 4_000L),
            toolResultMsg("tr1", "tool-1", "secret stdout from shell", nowMs + 5_000L),
            assistantTextMsg("a2", "我每天早上都会喝无糖冰美式", nowMs + 6_000L),
            userMsg("u2", "顺便记一下我周五晚上去打羽毛球", nowMs + 7_000L),
            assistantTextMsg("a3", "好的，周五羽毛球很棒！", nowMs + 8_000L),
        )

        val pausedWindows = listOf(PauseWindow(startMs = nowMs + 5_500L, endMs = nowMs + 6_500L))
        val unpaused = MemoryTranscriptExtractor.filterOutPausedEntries(entries, pausedWindows)
        val extracted = MemoryTranscriptExtractor.extract(unpaused, minMessages = 2)!!
        assertTrue(extracted.text.contains("你好呀"))
        assertTrue(extracted.text.contains("周五晚上去打羽毛球"))
        assertFalse(extracted.text.contains("[Worker update]"))
        assertFalse(extracted.text.contains("[Worker waiting-input timeout]"))
        assertFalse(extracted.text.contains("secret stdout"))
        assertFalse(extracted.text.contains("无糖冰美式"))

        val branchedDelta = MemoryTranscriptExtractor.deltaEntries(
            entries = entries,
            watermark = IngestionWatermark(
                sessionId = "s1",
                sessionFile = null,
                lastEntryId = "missing-branch-entry",
                lastHash = null,
                updatedAt = nowMs + 6_500L,
            ),
        )
        assertEquals(listOf("u2", "a3"), branchedDelta.map { it.id })
    }

    @Test
    fun hotPathRunsMultiStepToolUseEnforcesGuardAndSupportsNoop() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val profile = store.getBlockBySlug("self_profile")!!
            store.updateBlock(
                id = profile.id,
                content = "常住上海",
                pinned = true,
                actor = MemoryMutationActor.USER,
            )

            var stepCount = 0
            val recordedContexts = CopyOnWriteArrayList<Context>()
            val gateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    assertEquals(MemoryModelTier.HOT, tier)
                    recordedContexts += context
                    assertEquals(10, context.tools?.size ?: 0)
                    stepCount++
                    return when (stepCount) {
                        1 -> successMessage(
                            ToolCall(
                                id = "c1",
                                name = "search_ledger",
                                arguments = buildJsonObject {
                                    put("queries", buildJsonArray { add(JsonPrimitive("咖啡")) })
                                },
                            ),
                        )
                        2 -> successMessage(
                            ToolCall(
                                id = "c2",
                                name = "commit_memory_changes",
                                arguments = buildJsonObject {
                                    put("user_summary", "记录咖啡与朋友关系")
                                    put(
                                        "patch_blocks",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("slug", "self_profile")
                                                    put("append_text", "- shouldn't patch pinned")
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("slug", "preferences_boundaries")
                                                    put("append_text", "- 只喝无糖冷萃咖啡")
                                                },
                                            )
                                        },
                                    )
                                    put(
                                        "record_events",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("kind", "PREFERENCE")
                                                    put("content", "只喝无糖冷萃咖啡")
                                                    put("domain", "PERSONAL")
                                                    put("epistemic_tier", "USER_EXPLICIT")
                                                    put(
                                                        "entities",
                                                        buildJsonArray {
                                                            add(
                                                                buildJsonObject {
                                                                    put("name", "林夕")
                                                                    put("type", "FRIEND")
                                                                },
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    put(
                                        "upsert_relations",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("from_name", "我")
                                                    put("relation_type", "friend_of")
                                                    put("to_name", "林夕")
                                                },
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                        3 -> {
                            val lastToolResult = context.messages.filterIsInstance<ToolResultMessage>().last()
                            val toolText = lastToolResult.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                            assertTrue(toolText.contains("Blocked by protection guard"))
                            successMessage(
                                ToolCall(
                                    id = "c3",
                                    name = "noop",
                                    arguments = buildJsonObject { put("reason", "done after commit") },
                                ),
                            )
                        }
                        else -> successMessage(
                            ToolCall(
                                id = "c4",
                                name = "noop",
                                arguments = buildJsonObject { put("reason", "small talk") },
                            ),
                        )
                    }
                }
            }

            val runner = MemoryAgentRunner(store, gateway, clock = { nowMs })
            val dispatchJob = store.enqueueDispatchJob(
                jobKind = DispatchJobKind.DISPATCH_INTENT,
                sessionId = "s1",
                payloadJson = buildJsonObject {
                    put("intent", "REMEMBER")
                    put("detail", "和林夕聊了咖啡偏好，我只喝无糖冷萃")
                }.toString(),
            )
            val outcome = runner.runHotJob(dispatchJob)
            assertTrue(outcome is MemoryAgentOutcome.Done)
            val summary = (outcome as MemoryAgentOutcome.Done).summary
            assertEquals("DONE", summary.status)
            assertEquals(3, summary.stepsUsed)
            assertEquals(1, summary.blocksPatched)
            assertEquals(1, summary.eventsRecorded)

            assertEquals("常住上海", store.getBlockBySlug("self_profile")!!.content)
            assertTrue(store.getBlockBySlug("preferences_boundaries")!!.content.contains("无糖冷萃"))
            assertTrue(store.listEvents(MemoryEventFilter(limit = 20)).any { it.content.contains("无糖冷萃") })
            assertTrue(store.listRelations().any { it.toEntityName == "林夕" })

            val chitchatJob = store.enqueueDispatchJob(
                jobKind = DispatchJobKind.SESSION_IDLE,
                sessionId = "s2",
                payloadJson = buildJsonObject {
                    put("reason", "session_idle")
                    put("transcript", "User: 晚安\nAssistant: 晚安")
                }.toString(),
            )
            val chitchatOutcome = runner.runHotJob(chitchatJob)
            assertTrue(chitchatOutcome is MemoryAgentOutcome.Done)
            assertEquals(1, (chitchatOutcome as MemoryAgentOutcome.Done).summary.stepsUsed)
            assertEquals(0, chitchatOutcome.summary.eventsRecorded)
        } finally {
            store.close()
        }
    }

    @Test
    fun coldPathEnforcesFiveEventRuleForProposalsAndMergesEntities() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.ensure()
            val e1 = store.upsertEntity(MemoryEntityRef("阿旺", "PET"))
            val e2 = store.upsertEntity(MemoryEntityRef("阿旺狗狗", "PET"))
            val eventIds = (1..5).map { idx ->
                store.saveEvent(
                    MemoryEventDraft(
                        eventKind = MemoryEventKind.EPISODE,
                        content = "带阿旺看宠物医生记录 $idx",
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                    ),
                ).id
            }

            var coldStep = 0
            val gateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    assertEquals(MemoryModelTier.COLD, tier)
                    coldStep++
                    return when (coldStep) {
                        1 -> successMessage(
                            ToolCall(
                                id = "cold-1",
                                name = "propose_new_block",
                                arguments = buildJsonObject {
                                    put("slug", "pet_care")
                                    put("title", "养宠日记")
                                    put("purpose", "记录阿旺医疗和习惯")
                                    put("domain", "LIFE")
                                    put("seed_content", "阿旺体检")
                                    put("supporting_event_ids", JsonArray(eventIds.take(2).map(::JsonPrimitive)))
                                    put("reason", "多次就医记录")
                                },
                            ),
                        )
                        2 -> {
                            val err = context.messages.filterIsInstance<ToolResultMessage>().last()
                            assertTrue(err.isError)
                            successMessage(
                                ToolCall(
                                    id = "cold-2",
                                    name = "propose_new_block",
                                    arguments = buildJsonObject {
                                        put("slug", "pet_care")
                                        put("title", "养宠日记")
                                        put("purpose", "记录阿旺医疗和习惯")
                                        put("domain", "LIFE")
                                        put("seed_content", "阿旺体检与饮食")
                                        put("supporting_event_ids", JsonArray(eventIds.map(::JsonPrimitive)))
                                        put("reason", "已累积5条以上相关事件")
                                    },
                                ),
                                ToolCall(
                                    id = "cold-3",
                                    name = "merge_entities",
                                    arguments = buildJsonObject {
                                        put("keep_id", e1.id)
                                        put("merge_ids", buildJsonArray { add(JsonPrimitive(e2.id)) })
                                        put("merged_summary", "家里的金毛阿旺")
                                    },
                                ),
                            )
                        }
                        else -> successMessage(
                            ToolCall(
                                id = "cold-4",
                                name = "noop",
                                arguments = buildJsonObject { put("reason", "consolidation complete") },
                            ),
                        )
                    }
                }
            }

            val service = MemoryAgentService(store, gateway, scope, clock = { nowMs })
            val outcome = service.runConsolidation(manual = true, minIntervalMs = 0L, minNewEvents = 0)
            assertTrue(outcome is ConsolidationOutcome.Done)
            val summary = (outcome as ConsolidationOutcome.Done).summary
            assertEquals(1, summary.proposalsCreated)
            assertEquals(1, summary.entitiesMerged)
            assertEquals(1, store.listProposals(MemoryProposalStatus.PENDING).size)
            assertEquals(listOf(e1.id), store.listEntities().map { it.id })
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun serviceOrdersDispatchAheadOfIdleAndWakesAwaitHighPriority() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.ensure()
            val executedPrompts = CopyOnWriteArrayList<String>()
            val dispatchGate = CompletableDeferred<Unit>()

            val gateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    val userText = context.messages.filterIsInstance<UserMessage>()
                        .joinToString("\n") { (it.content as PlainTextContent).text }
                    executedPrompts += userText
                    if (userText.contains("urgent coffee recall")) {
                        dispatchGate.await()
                    }
                    return successMessage(
                        ToolCall(
                            id = "noop-${executedPrompts.size}",
                            name = "noop",
                            arguments = buildJsonObject { put("reason", "ok") },
                        ),
                    )
                }
            }

            store.enqueueDispatchJob(
                jobKind = DispatchJobKind.SESSION_IDLE,
                sessionId = "s-idle",
                payloadJson = buildJsonObject {
                    put("reason", "session_idle")
                    put("transcript", "User: idle message\nAssistant: idle reply")
                }.toString(),
            )
            store.enqueueDispatchJob(
                jobKind = DispatchJobKind.DISPATCH_INTENT,
                sessionId = "s-hot",
                payloadJson = buildJsonObject {
                    put("intent", "REMEMBER")
                    put("detail", "nonblocking dispatch detail")
                }.toString(),
            )

            val service = MemoryAgentService(store, gateway, scope, clock = { nowMs })
            service.drainQueueOnceForTesting()
            assertEquals(2, executedPrompts.size)
            assertTrue(executedPrompts[0].contains("nonblocking dispatch detail"))
            assertTrue(executedPrompts[1].contains("idle message"))

            service.enqueueDispatch("s-await", MemoryDispatchIntent.REMEMBER, "urgent coffee recall")
            dispatchGate.complete(Unit)
            service.awaitHighPriority(2_000L)
            assertFalse(store.hasPendingHighPriorityJob())
            assertTrue(executedPrompts.any { it.contains("urgent coffee recall") })
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun enforcesUserGuidanceHotTerminalCommitColdFinalStepBuiltinGuardEmptyBlockClearAndQueueBackoff() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.ensure()
            store.createBlock(
                io.github.whywood.superpi.runtime.MemoryBlockDraft(
                    slug = "temp_notes",
                    title = "临时笔记",
                    purpose = "可清空",
                    content = "旧内容",
                ),
            )

            val dup1 = store.upsertEntity(MemoryEntityRef("豆包", "PET"))
            val dup2 = store.upsertEntity(MemoryEntityRef("豆包猫", "PET"))

            var hotSteps = 0
            var capturedSessionId: String? = null
            var capturedSystemPrompt = ""
            var capturedToolsCount = 0
            val hotGateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult =
                    error("expected 4-arg step call")

                override suspend fun step(
                    tier: MemoryModelTier,
                    context: Context,
                    sessionId: String?,
                    maxTokens: Int,
                ): MemoryAgentStepResult {
                    hotSteps++
                    capturedSessionId = sessionId
                    capturedSystemPrompt = context.systemPrompt.orEmpty()
                    capturedToolsCount = context.tools?.size ?: 0
                    return when (hotSteps) {
                        1 -> successMessage(
                            ToolCall(
                                id = "s1",
                                name = "search_ledger",
                                arguments = buildJsonObject { put("queries", "咖啡") },
                            ),
                        )
                        2 -> successMessage(
                            ToolCall(
                                id = "s2",
                                name = "commit_memory_changes",
                                arguments = buildJsonObject {
                                    put("user_summary", "清空临时笔记")
                                    put(
                                        "patch_blocks",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("slug", "temp_notes")
                                                    put("full_content", "")
                                                },
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                        3 -> successMessage(
                            ToolCall(
                                id = "s3",
                                name = "merge_entities",
                                arguments = buildJsonObject {
                                    put("keep_id", dup1.id)
                                    put("merge_ids", buildJsonArray { add(JsonPrimitive(dup2.id)) })
                                    put("merged_summary", "橘猫豆包")
                                },
                            ),
                        )
                        else -> MemoryAgentStepResult.Success(
                            modelRef = "fake/memory",
                            message = SdkAssistantMessage(
                                content = mutableListOf(TextContent("Done")),
                                api = "fake",
                                provider = "fake",
                                model = "fake-memory",
                                usage = Usage.empty(),
                                stopReason = StopReason.Stop,
                                timestamp = nowMs,
                            ),
                        )
                    }
                }
            }

            val runner = MemoryAgentRunner(
                store = store,
                gateway = hotGateway,
                userGuidanceProvider = { "始终使用简洁中文条目记录饮食偏好" },
                clock = { nowMs },
            )
            val hotJob = store.enqueueDispatchJob(
                jobKind = DispatchJobKind.DISPATCH_INTENT,
                sessionId = "s-term",
                payloadJson = buildJsonObject {
                    put("intent", "FORGET")
                    put("detail", "清空临时笔记")
                }.toString(),
            )
            val hotOutcome = runner.runHotJob(hotJob) as MemoryAgentOutcome.Done
            assertEquals("DONE", hotOutcome.summary.status)
            assertEquals(4, hotOutcome.summary.stepsUsed)
            assertEquals(1, hotOutcome.summary.entitiesMerged)
            assertEquals(10, capturedToolsCount)
            assertEquals(hotOutcome.summary.id, capturedSessionId)
            assertTrue(capturedSystemPrompt.contains("## User Guidance"))
            assertTrue(capturedSystemPrompt.contains("始终使用简洁中文条目记录饮食偏好"))
            assertEquals("", store.getBlockBySlug("temp_notes")!!.content)

            var coldSteps = 0
            val coldGateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    coldSteps++
                    return when (coldSteps) {
                        1 -> successMessage(
                            ToolCall(
                                id = "c1",
                                name = "archive_block",
                                arguments = buildJsonObject { put("slug", "self_profile") },
                            ),
                        )
                        2 -> {
                            val err = context.messages.filterIsInstance<ToolResultMessage>().last()
                            assertTrue(err.isError)
                            successMessage(
                                ToolCall(
                                    id = "c2",
                                    name = "propose_new_block",
                                    arguments = buildJsonObject {
                                        put("slug", "fake_block")
                                        put("title", "Fake")
                                        put("purpose", "Fake")
                                        put("domain", "LIFE")
                                        put("seed_content", "none")
                                        put(
                                            "supporting_event_ids",
                                            buildJsonArray {
                                                repeat(5) { add(JsonPrimitive("missing-id-1")) }
                                            },
                                        )
                                        put("reason", "fake")
                                    },
                                ),
                            )
                        }
                        3 -> {
                            val err = context.messages.filterIsInstance<ToolResultMessage>().last()
                            assertTrue(err.isError)
                            successMessage(
                                ToolCall(
                                    id = "c3",
                                    name = "list_recent_events",
                                    arguments = buildJsonObject { put("limit", 5) },
                                ),
                            )
                        }
                        4 -> successMessage(
                            ToolCall(
                                id = "c4",
                                name = "commit_memory_changes",
                                arguments = buildJsonObject {
                                    put("user_summary", "终步补充习惯")
                                    put(
                                        "record_events",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("kind", "HABIT")
                                                    put("content", "睡前拉伸十分钟")
                                                },
                                            )
                                        },
                                    )
                                },
                            ),
                        )
                        else -> MemoryAgentStepResult.Success(
                            modelRef = "fake/memory",
                            message = SdkAssistantMessage(
                                content = mutableListOf(TextContent("Consolidated")),
                                api = "fake",
                                provider = "fake",
                                model = "fake-memory",
                                usage = Usage.empty(),
                                stopReason = StopReason.Stop,
                                timestamp = nowMs,
                            ),
                        )
                    }
                }
            }
            val coldRunner = MemoryAgentRunner(
                store = store,
                gateway = coldGateway,
                clock = { nowMs },
            )
            val coldOutcome = coldRunner.runColdConsolidation() as MemoryAgentOutcome.Done
            assertEquals("DONE", coldOutcome.summary.status)
            assertEquals(5, coldOutcome.summary.stepsUsed)
            assertEquals(1, coldOutcome.summary.eventsRecorded)

            val longCode = "```kotlin\n" + "x".repeat(600) + "\n```"
            val noisyEntries = listOf<SessionEntry>(
                userMsg("r1", "[Reminder] check status", nowMs + 1_000L),
                userMsg("u10", "U".repeat(3_200), nowMs + 2_000L),
                assistantTextMsg("a10", "分析如下：$longCode " + "B".repeat(1_500), nowMs + 3_000L),
            )
            val fullExtracted = MemoryTranscriptExtractor.extract(
                entries = noisyEntries,
                minMessages = 2,
            )!!
            assertFalse(fullExtracted.text.contains("[Reminder]"))
            assertTrue(fullExtracted.text.contains("U".repeat(3_200)))
            assertTrue(fullExtracted.text.contains("x".repeat(600)))
            assertTrue(fullExtracted.text.contains("B".repeat(1_500)))

            val boundedExtracted = MemoryTranscriptExtractor.extract(
                entries = noisyEntries,
                minMessages = 2,
                maxTotalChars = 1_500,
            )!!
            assertTrue(boundedExtracted.text.length <= 1_532)

            store.enqueueDispatchJob(
                jobKind = DispatchJobKind.SESSION_IDLE,
                sessionId = "s-bad",
                payloadJson = buildJsonObject { put("transcript", "bad") }.toString(),
            )
            nowMs += 10L
            store.enqueueDispatchJob(
                jobKind = DispatchJobKind.SESSION_IDLE,
                sessionId = "s-good",
                payloadJson = buildJsonObject { put("transcript", "good") }.toString(),
            )
            val poisonGateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    val prompt = context.messages.filterIsInstance<UserMessage>()
                        .joinToString("\n") { (it.content as PlainTextContent).text }
                    return if (prompt.contains("bad")) {
                        MemoryAgentStepResult.Failed("transient parse error")
                    } else {
                        successMessage(
                            ToolCall(
                                id = "noop-good",
                                name = "noop",
                                arguments = buildJsonObject { put("reason", "handled second job") },
                            ),
                        )
                    }
                }
            }
            val queueService = MemoryAgentService(store, poisonGateway, scope, clock = { nowMs })
            queueService.drainQueueOnceForTesting()
            assertEquals(1, queueService.pendingDispatchCount())
            assertTrue(store.earliestNextRetryAt(5)!! > nowMs)
            assertNull(store.dequeueNextJob(5))
            assertFalse(store.hasPendingHighPriorityJob())

            val validHistory = listOf<SessionEntry>(
                userMsg("old-1", "旧提问", nowMs + 1_000L),
                assistantTextMsg("old-2", "旧回答", nowMs + 2_000L),
            )
            val emptyDelta = MemoryTranscriptExtractor.deltaEntries(
                entries = validHistory,
                watermark = IngestionWatermark(
                    sessionId = "s-compacted",
                    sessionFile = null,
                    lastEntryId = "missing-id",
                    lastHash = "hash",
                    updatedAt = nowMs + 5_000L,
                ),
            )
            assertTrue(emptyDelta.isEmpty())

            store.saveWatermark(
                IngestionWatermark(
                    sessionId = "super:router",
                    sessionFile = null,
                    lastEntryId = "old-2",
                    lastHash = "old-hash",
                    updatedAt = nowMs + 2_000L,
                ),
            )
            val fullHistory = validHistory + listOf(
                userMsg("new-1", "重置前新消息", nowMs + 6_000L),
                assistantTextMsg("new-2", "重置前新回复", nowMs + 7_000L),
            )
            queueService.stageArchivedSessionDelta(
                sourceSessionId = "super:router",
                archivedSessionId = "super:router:archived:1",
                sessionFile = null,
                entries = fullHistory,
            )
            assertNull(store.readWatermark("super:router"))
            queueService.drainQueueOnceForTesting()
            val archivedWm = store.readWatermark("super:router:archived:1")
            assertNotNull(archivedWm)
            assertEquals("new-2", archivedWm!!.lastEntryId)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    private fun userMsg(id: String, text: String, ts: Long): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = java.time.Instant.ofEpochMilli(ts).toString(),
        message = UserMessage(content = PlainTextContent(text), timestamp = ts),
    )

    private fun assistantTextMsg(id: String, text: String, ts: Long): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = java.time.Instant.ofEpochMilli(ts).toString(),
        message = SdkAssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "fake",
            provider = "fake",
            model = "fake",
            usage = Usage.empty(),
            stopReason = StopReason.Stop,
            timestamp = ts,
        ),
    )

    private fun assistantToolMsg(id: String, text: String, callId: String, toolName: String, ts: Long): SessionEntry =
        SessionMessageEntry(
            id = id,
            parentId = null,
            timestamp = java.time.Instant.ofEpochMilli(ts).toString(),
            message = SdkAssistantMessage(
                content = mutableListOf<Content>(ToolCall(callId, toolName, buildJsonObject { put("x", text) })),
                api = "fake",
                provider = "fake",
                model = "fake",
                usage = Usage.empty(),
                stopReason = StopReason.ToolUse,
                timestamp = ts,
            ),
        )

    private fun toolResultMsg(id: String, callId: String, text: String, ts: Long): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = java.time.Instant.ofEpochMilli(ts).toString(),
        message = ToolResultMessage(
            toolCallId = callId,
            toolName = "bash",
            content = listOf(TextContent(text)),
            isError = false,
            timestamp = ts,
        ),
    )

    private fun successMessage(vararg calls: ToolCall): MemoryAgentStepResult.Success =
        MemoryAgentStepResult.Success(
            modelRef = "fake/memory",
            message = SdkAssistantMessage(
                content = calls.toMutableList<Content>(),
                api = "fake",
                provider = "fake",
                model = "fake-memory",
                usage = Usage.empty(),
                stopReason = StopReason.ToolUse,
                timestamp = nowMs,
            ),
        )
}

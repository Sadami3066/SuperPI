package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryIngestorTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore
    private var nowMs: Long = 1_760_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-ingestor-test").toFile()
        store = MemoryStore(rootDir, clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun compactionFlushAdvancesWatermarkSoSessionCloseOnlyProcessesDelta() = runTest {
        val prompts = ArrayList<String>()
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                prompts += prompt
                return if (prompts.size == 1) {
                    MemoryCompletionResult.Success(
                        MemoryCompletionResponse(
                            "mock/fast",
                            """{"items":[{"action":"ADD","kind":"IDENTITY","content":"用户喜欢喝拿铁","tags":["饮品"],"entities":[],"confidence":"OBSERVED","importance":4,"quote":"我平时爱喝拿铁"}]}""",
                        ),
                    )
                } else {
                    val savedId = store.list().first().id
                    MemoryCompletionResult.Success(
                        MemoryCompletionResponse(
                            "mock/fast",
                            """```json
{"items":[{"action":"UPDATE","supersedes_id":"$savedId","kind":"IDENTITY","content":"用户改喝美式咖啡","tags":["饮品"],"entities":[],"confidence":"OBSERVED","importance":4,"quote":"最近改喝美式了"}]}
```""",
                        ),
                    )
                }
            }
        }
        val ingestor = MemoryIngestor(store, gateway, this, clock = { nowMs })

        val e1 = userEntry("e1", "我平时爱喝拿铁")
        val e2 = assistantEntry("e2", "记住了")
        val first = ingestor.ingestCaptured(CapturedTranscript("s1", null, listOf(e1, e2)), "compaction_flush", minIncrementalMessages = 2)
        assertEquals(IngestOutcome.WRITTEN, first)
        val firstId = store.list().single().id

        val e3 = userEntry("e3", "对了，我最近改喝美式了")
        val e4 = assistantEntry("e4", "好的")
        val second = ingestor.ingestCaptured(CapturedTranscript("s1", null, listOf(e1, e2, e3, e4)), "session_close", minIncrementalMessages = 2)
        assertEquals(IngestOutcome.WRITTEN, second)

        assertEquals(2, prompts.size)
        assertTrue(!prompts[1].contains("我平时爱喝拿铁") || prompts[1].contains(firstId))
        val active = store.list()
        assertEquals(1, active.size)
        assertEquals("用户改喝美式咖啡", active.single().content)
        assertEquals(MemoryItemStatus.SUPERSEDED, store.get(firstId)!!.status)
    }

    @Test
    fun skipsExcludedSessionsAndRetriesPendingFailures() = runTest {
        var failCalls = true
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                if (failCalls) return MemoryCompletionResult.Failed("network_error")
                return MemoryCompletionResult.Success(
                    MemoryCompletionResponse(
                        "mock/fast",
                        """{"items":[{"action":"ADD","kind":"ACTIVE","content":"周五交周报","tags":["工作"],"entities":[],"confidence":"OBSERVED","importance":3,"quote":"周五交周报"}]}""",
                    ),
                )
            }
        }
        val excluded = setOf("s-private")
        val ingestor = MemoryIngestor(store, gateway, this, isSessionExcluded = { it in excluded }, clock = { nowMs })

        val excludedEntries = listOf(userEntry("p1", "记住我的秘密"), assistantEntry("p2", "好的"))
        assertEquals(
            IngestOutcome.EXCLUDED,
            ingestor.ingestCaptured(CapturedTranscript("s-private", null, excludedEntries), "compaction_flush"),
        )
        assertTrue(store.list().isEmpty())

        val entries = listOf(
            userEntry("a1", "周五交周报"),
            assistantEntry("a2", "好的"),
            userEntry("a3", "还有别的事吗"),
            assistantEntry("a4", "没了"),
        )
        assertEquals(IngestOutcome.FAILED, ingestor.ingestCaptured(CapturedTranscript("s-normal", null, entries), "session_close"))
        assertEquals(1, store.listPendingIngestions().size)

        failCalls = false
        ingestor.retryPending()

        assertTrue(store.listPendingIngestions().isEmpty())
        assertEquals(1, store.list().size)
        assertEquals(MemoryKind.ACTIVE, store.list().single().kind)
    }

    @Test
    fun pausedAndExcludedWatermarksPreventBackfillAfterResuming() = runTest {
        var paused = true
        var mode = MemoryCompletionResult.Paused as MemoryCompletionResult
        val seenPrompts = ArrayList<String>()
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                seenPrompts += prompt
                return mode
            }
        }
        var excludedSessions = emptySet<String>()
        val ingestor = MemoryIngestor(
            store = store,
            completionGateway = gateway,
            scope = this,
            isSessionExcluded = { it in excludedSessions },
            isPaused = { paused },
            clock = { nowMs },
        )

        val pausedTurn = listOf(userEntry("u1", "暂停期隐私消息"), assistantEntry("u2", "收到"))
        assertEquals(IngestOutcome.PAUSED, ingestor.ingestCaptured(CapturedTranscript("s-pause", null, pausedTurn), "session_close"))
        assertTrue(store.listPendingIngestions().isEmpty())

        paused = false
        mode = MemoryCompletionResult.Success(
            MemoryCompletionResponse(
                "mock/fast",
                """{"items":[{"action":"ADD","kind":"ACTIVE","content":"恢复后的待办","tags":[],"entities":[],"confidence":"OBSERVED","importance":3}]}""",
            ),
        )
        val afterResume = pausedTurn + listOf(
            userEntry("u3", "恢复后安排周三开会"),
            assistantEntry("u4", "好的"),
        )
        assertEquals(IngestOutcome.WRITTEN, ingestor.ingestCaptured(CapturedTranscript("s-pause", null, afterResume), "session_close"))
        assertTrue(seenPrompts.single().contains("恢复后安排周三开会"))
        assertTrue(!seenPrompts.single().contains("暂停期隐私消息"))

        excludedSessions = setOf("s-priv")
        val privTurn = listOf(userEntry("e1", "关闭记忆期的秘密内容"), assistantEntry("e2", "好"))
        assertEquals(IngestOutcome.EXCLUDED, ingestor.ingestCaptured(CapturedTranscript("s-priv", null, privTurn), "compaction_flush"))
        excludedSessions = emptySet()
        ingestor.onSessionExclusionChanged("s-priv", excluded = false, sessionFile = null, entries = privTurn)
        seenPrompts.clear()
        mode = MemoryCompletionResult.Success(
            MemoryCompletionResponse(
                "mock/fast",
                """{"items":[{"action":"ADD","kind":"ACTIVE","content":"排除解除后的新待办","tags":[],"entities":[],"confidence":"OBSERVED","importance":3}]}""",
            ),
        )
        val postPriv = privTurn + listOf(
            userEntry("e3", "恢复记忆后的新内容"),
            assistantEntry("e4", "明白"),
        )
        assertEquals(IngestOutcome.WRITTEN, ingestor.ingestCaptured(CapturedTranscript("s-priv", null, postPriv), "session_close"))
        assertTrue(seenPrompts.single().contains("恢复记忆后的新内容"))
        assertTrue(!seenPrompts.single().contains("关闭记忆期的秘密内容"))
    }

    @Test
    fun malformedItemsRetriesAndNoModelDoesNotConsumeAttempts() = runTest {
        var result: MemoryCompletionResult = MemoryCompletionResult.Success(
            MemoryCompletionResponse("mock/fast", """{"items":null}"""),
        )
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult = result
        }
        val ingestor = MemoryIngestor(store, gateway, this, clock = { nowMs })
        val entries = listOf(
            userEntry("m1", "周五提交版本"),
            assistantEntry("m2", "好的"),
            userEntry("m3", "记住"),
            assistantEntry("m4", "明白"),
        )
        assertEquals(IngestOutcome.FAILED, ingestor.ingestCaptured(CapturedTranscript("s-malformed", null, entries), "session_close"))
        val pending = store.listPendingIngestions()
        assertEquals(1, pending.size)
        assertEquals(1, pending.single().attempts)

        result = MemoryCompletionResult.NoModel
        ingestor.retryPending()
        ingestor.retryPending()
        assertEquals(1, store.listPendingIngestions().single().attempts)

        result = MemoryCompletionResult.Success(
            MemoryCompletionResponse(
                "mock/fast",
                """{"items":[{"action":"ADD","kind":"ACTIVE","content":"周五提交版本","tags":[],"entities":[],"confidence":"OBSERVED","importance":3}]}""",
            ),
        )
        ingestor.retryPending()
        assertTrue(store.listPendingIngestions().isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test
    fun pauseWindowsPreventLeakingPausedMessagesAndPreservePrePauseContentOnClose() = runTest {
        var paused = false
        val seenPrompts = ArrayList<String>()
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                seenPrompts += prompt
                return MemoryCompletionResult.Success(
                    MemoryCompletionResponse(
                        "mock/fast",
                        """{"items":[{"action":"ADD","kind":"ACTIVE","content":"恢复后提取的事项","tags":[],"entities":[],"confidence":"OBSERVED","importance":3}]}""",
                    ),
                )
            }
        }
        val ingestor = MemoryIngestor(
            store = store,
            completionGateway = gateway,
            scope = this,
            isPaused = { paused },
            clock = { nowMs },
        )

        // Forward boundary: open session -> pause -> chat during pause -> resume -> close
        val u1 = userEntry("f1", "正常前提一句", ts = nowMs)
        val u2 = assistantEntry("f2", "收到", ts = nowMs + 100L)
        nowMs += 1_000L
        paused = true
        ingestor.onPauseModeChanged(true)
        val p1 = userEntry("fp1", "暂停期间的隐私对话A", ts = nowMs + 100L)
        val p2 = assistantEntry("fp2", "暂停回复A", ts = nowMs + 200L)
        nowMs += 1_000L
        paused = false
        ingestor.onPauseModeChanged(false)
        val u3 = userEntry("f3", "恢复后的关键安排", ts = nowMs + 100L)
        val u4 = assistantEntry("f4", "明白", ts = nowMs + 200L)

        assertEquals(
            IngestOutcome.WRITTEN,
            ingestor.ingestCaptured(CapturedTranscript("s-open", null, listOf(u1, u2, p1, p2, u3, u4)), "session_close"),
        )
        assertEquals(1, seenPrompts.size)
        assertTrue(seenPrompts.single().contains("正常前提一句"))
        assertTrue(seenPrompts.single().contains("恢复后的关键安排"))
        assertTrue(!seenPrompts.single().contains("暂停期间的隐私对话A"))

        // Reverse boundary: pre-pause messages preserved even if session closes while paused
        seenPrompts.clear()
        val r1 = userEntry("r1", "暂停前的重要事项1", ts = nowMs + 1_000L)
        val r2 = assistantEntry("r2", "明白1", ts = nowMs + 1_100L)
        val r3 = userEntry("r3", "暂停前的重要事项2", ts = nowMs + 1_200L)
        val r4 = assistantEntry("r4", "明白2", ts = nowMs + 1_300L)
        nowMs += 2_000L
        paused = true
        ingestor.onPauseModeChanged(true)
        val rp1 = userEntry("rp1", "暂停后的秘密内容B", ts = nowMs + 100L)
        val rp2 = assistantEntry("rp2", "保密", ts = nowMs + 200L)

        assertEquals(
            IngestOutcome.PAUSED,
            ingestor.ingestCaptured(CapturedTranscript("s-close-paused", null, listOf(r1, r2, r3, r4, rp1, rp2)), "session_close"),
        )
        // Retrying while still paused must keep the pre-pause pending entry intact.
        ingestor.retryPending()
        assertEquals(1, store.listPendingIngestions().size)

        nowMs += 1_000L
        paused = false
        ingestor.onPauseModeChanged(false)
        ingestor.retryPending()
        assertEquals(1, seenPrompts.size)
        assertTrue(seenPrompts.single().contains("暂停前的重要事项1"))
        assertTrue(seenPrompts.single().contains("暂停前的重要事项2"))
        assertTrue(!seenPrompts.single().contains("暂停后的秘密内容B"))
        assertTrue(store.listPendingIngestions().isEmpty())
    }

    @Test
    fun subsequentIngestionClearsPendingAndMonotonicGuardPreventsWatermarkRewind() = runTest {
        var shouldFail = true
        var callCount = 0
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                callCount++
                if (shouldFail) return MemoryCompletionResult.Failed("network_err")
                return MemoryCompletionResult.Success(
                    MemoryCompletionResponse(
                        "mock/fast",
                        """{"items":[{"action":"ADD","kind":"ACTIVE","content":"新进展","tags":[],"entities":[],"confidence":"OBSERVED","importance":3}]}""",
                    ),
                )
            }
        }
        val ingestor = MemoryIngestor(store, gateway, this, clock = { nowMs })

        val e1 = userEntry("e1", "前期消息1", ts = nowMs)
        val e2 = assistantEntry("e2", "回复1", ts = nowMs + 10L)
        val e3 = userEntry("e3", "前期消息2", ts = nowMs + 20L)
        val e4 = assistantEntry("e4", "回复2", ts = nowMs + 30L)
        assertEquals(IngestOutcome.FAILED, ingestor.ingestCaptured(CapturedTranscript("s-wm", null, listOf(e1, e2, e3, e4)), "session_close"))
        assertEquals(1, store.listPendingIngestions().size)

        nowMs += 5_000L
        shouldFail = false
        val e5 = userEntry("e5", "后续消息3", ts = nowMs)
        val e6 = assistantEntry("e6", "回复3", ts = nowMs + 10L)
        assertEquals(
            IngestOutcome.WRITTEN,
            ingestor.ingestCaptured(CapturedTranscript("s-wm", null, listOf(e1, e2, e3, e4, e5, e6)), "idle_debounce"),
        )
        // Successful ingest must clear stale pending_ingestions immediately.
        assertTrue(store.listPendingIngestions().isEmpty())
        assertEquals("e6", store.readWatermark("s-wm")!!.lastEntryId)

        // Even if an older pending snapshot is manually present, retryPending must not rewind watermark.
        val callsBeforeRetry = callCount
        store.savePendingIngestion(
            PendingIngestionRecord(
                sessionId = "s-wm",
                sessionFile = null,
                transcript = "User: old transcript",
                lastEntryId = "e4",
                reason = "idle_debounce",
                attempts = 1,
                createdAt = nowMs - 4_000L,
                lastError = "old_error",
            ),
        )
        ingestor.retryPending()
        assertEquals(callsBeforeRetry, callCount)
        assertEquals("e6", store.readWatermark("s-wm")!!.lastEntryId)
        assertTrue(store.listPendingIngestions().isEmpty())
    }

    private fun userEntry(id: String, text: String, ts: Long = nowMs): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = "2026-11-23T00:00:00Z",
        message = UserMessage(PlainTextContent(text), ts),
    )

    private fun assistantEntry(id: String, text: String, ts: Long = nowMs): SessionEntry = SessionMessageEntry(
        id = id,
        parentId = null,
        timestamp = "2026-11-23T00:00:01Z",
        message = AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "openai-completions",
            provider = "mock",
            model = "fast",
            usage = Usage.empty(),
            stopReason = StopReason.Stop,
            timestamp = ts,
        ),
    )
}

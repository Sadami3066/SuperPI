package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryDreamerTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore
    private var nowMs: Long = 1_760_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-dream-test").toFile()
        store = MemoryStore(rootDir, clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun expiresTtlArchivesStaleInferredAndProtectsPinnedAndConfirmedInDreamPass() = runTest {
        val expiredActive = store.save(
            MemoryDraft(
                kind = MemoryKind.ACTIVE,
                content = "这周三交水电费",
                validFrom = nowMs - 5L * 86_400_000L,
                validUntil = nowMs - 86_400_000L,
            ),
        )
        val presentNow = nowMs
        nowMs = presentNow - 60L * 86_400_000L
        val staleInferred = store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "某天顺口提到的天气偏好",
                confidence = MemoryConfidence.INFERRED,
                importance = 2,
                validFrom = nowMs,
            ),
        )
        nowMs = presentNow
        val pinnedRecord = store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "用户严重对花生过敏",
                confidence = MemoryConfidence.CONFIRMED,
                pinned = true,
                validFrom = nowMs - 10L * 86_400_000L,
            ),
        )
        val confirmedRecord = store.save(
            MemoryDraft(
                kind = MemoryKind.HABIT,
                content = "永远用中文回复",
                confidence = MemoryConfidence.CONFIRMED,
                pinned = false,
                validFrom = nowMs - 10L * 86_400_000L,
            ),
        )
        val oldPlan = store.save(
            MemoryDraft(
                kind = MemoryKind.ACTIVE,
                content = "计划去日本旅游",
                confidence = MemoryConfidence.OBSERVED,
                entities = listOf(MemoryEntityRef("小王", EntityKind.PERSON)),
                validFrom = nowMs - 3L * 86_400_000L,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "老王推荐改去云南大理",
                entities = listOf(MemoryEntityRef("老王", EntityKind.PERSON)),
                validFrom = nowMs - 86_400_000L,
            ),
        )

        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                return MemoryCompletionResult.Success(
                    MemoryCompletionResponse(
                        "mock/dream",
                        """
                    {
                      "promotions": [
                        {"kind":"HABIT","content":"旅行攻略先比较交通和淡旺季","tags":["旅行"],"entities":[],"confidence":"INFERRED","importance":4}
                      ],
                      "supersessions": [
                        {"old_id":"${oldPlan.id}","kind":"ACTIVE","content":"旅行目的地改定为云南大理","tags":["旅行"],"entities":["老王"],"confidence":"OBSERVED","importance":4},
                        {"old_id":"${pinnedRecord.id}","kind":"IDENTITY","content":"试图覆盖置顶项"}
                      ],
                      "expirations": ["${confirmedRecord.id}"],
                      "archives": ["${pinnedRecord.id}"],
                      "entity_merges": [
                        {"keep_name":"老王","merge_names":["小王"],"summary":"一起讨论旅行的好友"}
                      ]
                    }
                    """.trimIndent(),
                    ),
                )
            }
        }

        val dreamer = MemoryDreamer(store, gateway, this, clock = { nowMs })
        val outcome = dreamer.runNow() as DreamOutcome.Done

        assertTrue(outcome.summary.expiredCount >= 2)
        assertEquals(1, outcome.summary.promotedCount)
        assertEquals(1, outcome.summary.supersededCount)
        assertEquals(1, outcome.summary.mergedEntities)

        assertEquals(MemoryItemStatus.EXPIRED, store.get(expiredActive.id)!!.status)
        assertEquals(MemoryItemStatus.ARCHIVED, store.get(staleInferred.id)!!.status)
        assertEquals(MemoryItemStatus.ACTIVE, store.get(pinnedRecord.id)!!.status)
        assertEquals("用户严重对花生过敏", store.get(pinnedRecord.id)!!.content)
        assertEquals(MemoryItemStatus.ACTIVE, store.get(confirmedRecord.id)!!.status)
        assertEquals(MemoryItemStatus.SUPERSEDED, store.get(oldPlan.id)!!.status)

        val activeList = store.list(MemoryFilter(includeHistory = false))
        assertTrue(activeList.any { it.content == "旅行目的地改定为云南大理" })
        assertTrue(activeList.any { it.content == "旅行攻略先比较交通和淡旺季" })

        val mergedEntity = store.listEntities().single { it.canonicalName == "老王" }
        assertTrue("小王" in mergedEntity.aliases)
    }

    @Test
    fun distinguishesPausedNoModelAndTransientFailures() = runTest {
        store.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "讨论下个月出行安排", validFrom = nowMs - 86_400_000L))

        val pausedDreamer = MemoryDreamer(
            store = store,
            completionGateway = object : MemoryCompletionGateway {
                override suspend fun complete(tier: MemoryModelTier, prompt: String) = MemoryCompletionResult.Paused
            },
            scope = this,
            clock = { nowMs },
        )
        assertEquals(DreamOutcome.Skipped("paused"), pausedDreamer.runNow())

        val noModelDreamer = MemoryDreamer(
            store = store,
            completionGateway = object : MemoryCompletionGateway {
                override suspend fun complete(tier: MemoryModelTier, prompt: String) = MemoryCompletionResult.NoModel
            },
            scope = this,
            clock = { nowMs },
        )
        assertEquals(DreamOutcome.Skipped("no_model"), noModelDreamer.runNow())

        val failedDreamer = MemoryDreamer(
            store = store,
            completionGateway = object : MemoryCompletionGateway {
                override suspend fun complete(tier: MemoryModelTier, prompt: String) = MemoryCompletionResult.Failed("timeout")
            },
            scope = this,
            clock = { nowMs },
        )
        assertTrue(failedDreamer.runNow() is DreamOutcome.Failed)
    }

    @Test
    fun respectsSyncCoordinatorOfflineCooldownAndBusyLeaseOutcomes() = runTest {
        store.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "讨论同步状态下的休眠整理", validFrom = nowMs - 86_400_000L))
        var completedLease: String? = null
        var finishedLease: String? = null
        var llmCalls = 0
        val gateway = object : MemoryCompletionGateway {
            override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                llmCalls++
                return MemoryCompletionResult.Success(MemoryCompletionResponse("mock/dream", "{}"))
            }
        }

        val offlineDreamer = MemoryDreamer(
            store = store,
            completionGateway = gateway,
            scope = this,
            clock = { nowMs },
            coordinator = {
                object : MemoryDreamCoordinator {
                    override suspend fun prepareForDream(forceManual: Boolean, minIntervalMs: Long) = DreamLeaseResult.Offline
                    override suspend fun onDreamCompleted(leaseId: String?, finishedAt: Long) {}
                    override suspend fun onDreamFinished(leaseId: String?) {}
                }
            },
        )
        assertEquals(DreamOutcome.Skipped("sync_offline"), offlineDreamer.runFromWorker())
        assertEquals(DreamOutcome.Failed("sync_offline"), offlineDreamer.runNow())
        assertEquals(0, llmCalls)

        val busyDreamer = MemoryDreamer(
            store = store,
            completionGateway = gateway,
            scope = this,
            clock = { nowMs },
            coordinator = {
                object : MemoryDreamCoordinator {
                    override suspend fun prepareForDream(forceManual: Boolean, minIntervalMs: Long) = DreamLeaseResult.Blocked("busy")
                    override suspend fun onDreamCompleted(leaseId: String?, finishedAt: Long) {}
                    override suspend fun onDreamFinished(leaseId: String?) {}
                }
            },
        )
        assertEquals(DreamOutcome.Busy, busyDreamer.runNow())
        assertEquals(DreamOutcome.Skipped("busy"), busyDreamer.runFromWorker())

        val leasedDreamer = MemoryDreamer(
            store = store,
            completionGateway = gateway,
            scope = this,
            clock = { nowMs },
            coordinator = {
                object : MemoryDreamCoordinator {
                    override suspend fun prepareForDream(forceManual: Boolean, minIntervalMs: Long) = DreamLeaseResult.Acquired("lease-xyz")
                    override suspend fun onDreamCompleted(leaseId: String?, finishedAt: Long) {
                        completedLease = leaseId
                    }
                    override suspend fun onDreamFinished(leaseId: String?) {
                        finishedLease = leaseId
                    }
                }
            },
        )
        assertTrue(leasedDreamer.runNow() is DreamOutcome.Done)
        assertEquals(1, llmCalls)
        assertEquals("lease-xyz", completedLease)
        assertEquals("lease-xyz", finishedLease)
    }
}

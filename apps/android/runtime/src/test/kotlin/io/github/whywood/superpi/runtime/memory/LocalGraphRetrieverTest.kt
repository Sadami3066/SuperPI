package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalGraphRetrieverTest {
    private lateinit var rootDir: File
    private var nowMs = 1_760_400_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-retriever-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun supportsTwoHopSpreadingActivationSemanticKeywordsDateRangesScopeAndRecordAccessFlag() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            store.upsertRelation(
                fromEntityName = "张伟",
                relationType = MemoryRelationTypes.COLLEAGUE_OF,
                toEntityName = "林夕",
                domain = MemoryDomains.WORK,
            )
            store.upsertRelation(
                fromEntityName = "林夕",
                relationType = MemoryRelationTypes.WORKS_AT,
                toEntityName = "星辰项目",
                domain = MemoryDomains.WORK,
            )

            val twoHopEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.COMMITMENT,
                    content = "下周五完成系统架构评审演示",
                    entities = listOf(MemoryEntityRef("星辰项目", MemoryEntityTypes.PROJECT, domain = MemoryDomains.WORK)),
                    domain = MemoryDomains.WORK,
                    sensitivity = MemorySensitivity.NORMAL,
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                    eventTime = nowMs - 2 * 86_400_000L,
                ),
            )

            val semanticEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.PREFERENCE,
                    content = "每周三晚上去操场跑八公里间歇",
                    semanticKeywords = listOf("健身", "有氧运动", "体能训练"),
                    domain = MemoryDomains.HEALTH,
                    sensitivity = MemorySensitivity.PRIVATE,
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                    eventTime = nowMs - 10 * 86_400_000L,
                ),
            )

            val hopHits = store.search(
                queries = listOf("张伟"),
                filter = MemoryEventFilter(scope = MemoryAccessScope.WORK_ONLY),
                recordAccess = false,
            )
            assertEquals(1, hopHits.size)
            assertEquals(twoHopEvent.id, hopHits.single().event.id)
            assertTrue(hopHits.single().matchedViaRelation)
            assertEquals(0, store.getEvent(twoHopEvent.id)!!.accessCount)

            val semanticHits = store.search(
                queries = listOf("健身"),
                filter = MemoryEventFilter(scope = MemoryAccessScope.PERSONAL),
                recordAccess = true,
            )
            assertTrue(semanticHits.any { it.event.id == semanticEvent.id })
            assertEquals(1, store.getEvent(semanticEvent.id)!!.accessCount)

            val isolatedWorkHits = store.search(
                queries = listOf("健身", "八公里"),
                filter = MemoryEventFilter(scope = MemoryAccessScope.WORK_ONLY),
                recordAccess = false,
            )
            assertTrue(isolatedWorkHits.isEmpty())

            val rangeHits = store.search(
                queries = listOf("架构评审"),
                filter = MemoryEventFilter(
                    sinceEventTimeMs = nowMs - 5 * 86_400_000L,
                    untilEventTimeMs = nowMs,
                    scope = MemoryAccessScope.WORK_ONLY,
                ),
                recordAccess = false,
            )
            assertFalse(rangeHits.isEmpty())
            val outOfRangeHits = store.search(
                queries = listOf("架构评审"),
                filter = MemoryEventFilter(
                    sinceEventTimeMs = nowMs - 1_000L,
                    untilEventTimeMs = nowMs,
                    scope = MemoryAccessScope.WORK_ONLY,
                ),
                recordAccess = false,
            )
            assertTrue(outOfRangeHits.isEmpty())

            val aliasedMatch = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "周五评审需要准备演示文稿",
                    tags = listOf("review"),
                    entities = listOf(MemoryEntityRef("李雷", MemoryEntityTypes.COLLEAGUE, domain = MemoryDomains.WORK)),
                    domain = MemoryDomains.WORK,
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                    eventTime = nowMs,
                ),
            )
            store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "周五评审需要准备演示文稿和备忘录",
                    tags = listOf("reviews"),
                    entities = listOf(MemoryEntityRef("韩梅梅", MemoryEntityTypes.COLLEAGUE, domain = MemoryDomains.WORK)),
                    domain = MemoryDomains.WORK,
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                    eventTime = nowMs,
                ),
            )
            val entityFiltered = store.search(
                queries = listOf("演示文稿"),
                filter = MemoryEventFilter(
                    entityName = "李雷",
                    tag = "review",
                    scope = MemoryAccessScope.WORK_ONLY,
                ),
                recordAccess = false,
            )
            assertEquals(listOf(aliasedMatch.id), entityFiltered.map { it.event.id })
        } finally {
            store.close()
        }
    }
}

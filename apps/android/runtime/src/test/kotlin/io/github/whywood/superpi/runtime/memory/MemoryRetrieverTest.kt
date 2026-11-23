package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryRetrieverTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore
    private var nowMs: Long = 1_750_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-retriever-test").toFile()
        store = MemoryStore(rootDir, clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun searchesChineseBigramsLatinAndSpecialCharsWithoutSyntaxErrors() = runTest {
        store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "早上固定喝无糖冰美式和乌龙茶",
                tags = listOf("饮品"),
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.HABIT,
                content = "Kotlin Android 项目在 SuperPI-Remote 仓库用 SQLite FTS5",
                tags = listOf("技术"),
            ),
        )

        val teaHits = store.search(listOf("乌龙茶"))
        assertEquals(1, teaHits.size)
        assertTrue(teaHits.first().record.content.contains("乌龙茶"))

        val ftsHits = store.search(listOf("SuperPI-Remote", "SQLite (v2): *AND* NOT -crash"))
        assertEquals(1, ftsHits.size)
        assertTrue(ftsHits.first().record.content.contains("SuperPI-Remote"))
    }

    @Test
    fun expandsEntityAliasesAcrossDifferentNicknames() = runTest {
        store.save(
            MemoryDraft(
                kind = MemoryKind.ACTIVE,
                content = "小美下个月过生日，想挑一只微单相机做礼物",
                entities = listOf(MemoryEntityRef("林小美", EntityKind.PERSON, aliases = listOf("小美", "老婆"))),
                tags = listOf("礼物"),
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "陪她去了西湖散步",
                entities = listOf(MemoryEntityRef("林小美", EntityKind.PERSON)),
            ),
        )

        val hits = store.search(listOf("老婆生日礼物"))
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.any { it.record.content.contains("微单相机") })
        assertTrue(hits.first().matchedEntities.contains("林小美"))
    }

    @Test
    fun filtersSupersededUnlessIncludeHistoryRequestedAndPrefersRecentConfirmed() = runTest {
        val oldTime = nowMs - 90L * 86_400_000L
        val old = store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "曾经在杭州余杭工作",
                confidence = MemoryConfidence.CONFIRMED,
                validFrom = oldTime,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "现在在苏州工业园区工作",
                confidence = MemoryConfidence.CONFIRMED,
                validFrom = nowMs,
            ),
            supersedesId = old.id,
        )

        val currentHits = store.search(listOf("工作地点", "杭州", "苏州"))
        assertEquals(1, currentHits.size)
        assertTrue(currentHits.first().record.content.contains("苏州"))

        val historyHits = store.search(listOf("工作", "杭州"), MemoryFilter(includeHistory = true))
        assertEquals(2, historyHits.size)
        assertTrue(historyHits.any { it.record.status == MemoryItemStatus.SUPERSEDED })
    }

    @Test
    fun collapsesNearDuplicatesAndRanksRecentHighConfidenceAboveStaleInferred() = runTest {
        val staleMs = nowMs - 180L * 86_400_000L
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "周五去跑了十公里夜跑感觉很不错",
                confidence = MemoryConfidence.INFERRED,
                importance = 2,
                validFrom = staleMs,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "今晚夜跑五公里配速五分半状态很好",
                confidence = MemoryConfidence.CONFIRMED,
                importance = 5,
                validFrom = nowMs,
            ),
        )

        val hits = store.search(listOf("夜跑"))
        assertEquals(2, hits.size)
        assertTrue(hits.first().record.content.contains("今晚夜跑"))
    }

    @Test
    fun searchesDiacriticsAndGlobalUnicodeScripts() = runTest {
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "Met François at the Café about Über design patterns",
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "東京のラーメン屋でみそラーメンを食べた",
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "서울 강남역에서 프로젝트 회의 예정",
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "Встреча по проекту Аврора в Москве",
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "اجتماع مشروع السلام في القاهرة",
            ),
        )

        val latinHits = store.search(listOf("Francois cafe Uber"))
        assertTrue(latinHits.any { it.record.content.contains("François") })

        val jpHits = store.search(listOf("みそラーメン"))
        assertTrue(jpHits.any { it.record.content.contains("東京") })

        val koHits = store.search(listOf("강남역 회의"))
        assertTrue(koHits.any { it.record.content.contains("서울") })

        val ruHits = store.search(listOf("Аврора Москве"))
        assertTrue(ruHits.any { it.record.content.contains("Встреча") })

        val arHits = store.search(listOf("القاهرة"))
        assertTrue(arHits.any { it.record.content.contains("السلام") })
    }
}

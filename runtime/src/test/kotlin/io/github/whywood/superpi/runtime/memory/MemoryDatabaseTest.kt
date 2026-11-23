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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryDatabaseTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore
    private var nowMs: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-db-test").toFile()
        store = MemoryStore(rootDir, clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun cleansLegacyFilesAndCreatesSqliteDatabase() = runTest {
        File(rootDir, "profile.md").writeText("# old")
        File(rootDir, "daily/2026").apply { mkdirs() }
        File(rootDir, "daily/2026/2026-01-01.md").writeText("old day")
        File(rootDir, "pending").apply { mkdirs() }

        store.ensure()

        assertFalse(File(rootDir, "profile.md").exists())
        assertFalse(File(rootDir, "daily").exists())
        assertFalse(File(rootDir, "pending").exists())
        assertTrue(File(rootDir, "memory.db").isFile)
    }

    @Test
    fun recoversCorruptDatabaseByBackingUpAndRebuilding() = runTest {
        File(rootDir, "memory.db").writeBytes("not-a-sqlite-database".toByteArray())

        store.ensure()
        val saved = store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "喜欢乌龙茶"))

        assertNotNull(store.get(saved.id))
        val corruptBackups = rootDir.listFiles().orEmpty().filter { it.name.contains(".corrupt") }
        assertTrue(corruptBackups.isNotEmpty())
    }

    @Test
    fun crudSupersedeSoftDeleteRestoreAndBatchPurge() = runTest {
        val old = store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "目前居住在北京海淀",
                tags = listOf("居住地", "生活"),
                entities = listOf(MemoryEntityRef("北京", EntityKind.PLACE)),
                sourceKind = MemorySourceKind.CHAT_EXPLICIT,
                sourceSessionId = "s-1",
            ),
        )
        nowMs += 1_000L
        val replacement = store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "今年夏天搬到上海徐汇居住",
                tags = listOf("居住地", "生活"),
                entities = listOf(MemoryEntityRef("上海", EntityKind.PLACE)),
                sourceKind = MemorySourceKind.CHAT_EXPLICIT,
                sourceSessionId = "s-2",
            ),
            supersedesId = old.id,
        )

        val reloadedOld = store.get(old.id)!!
        assertEquals(MemoryItemStatus.SUPERSEDED, reloadedOld.status)
        assertEquals(replacement.id, reloadedOld.supersededBy)

        val activeOnly = store.list(MemoryFilter(includeHistory = false))
        assertEquals(listOf(replacement.id), activeOnly.map { it.id })

        val updated = store.update(
            id = replacement.id,
            content = "现居上海徐汇区",
            kind = MemoryKind.IDENTITY,
            tags = listOf("城市", "生活"),
            confidence = MemoryConfidence.INFERRED,
            pinned = true,
            validUntil = null,
        )!!
        assertEquals("现居上海徐汇区", updated.content)
        assertTrue(updated.pinned)
        assertEquals(MemoryConfidence.CONFIRMED, store.confirm(updated.id)!!.confidence)

        assertTrue(store.softDelete(replacement.id))
        assertNull(store.get(replacement.id))
        assertEquals("现居上海徐汇区", store.restore(replacement.id)!!.content)

        assertEquals(1, store.countBySession("s-2"))
        assertEquals(1, store.deleteBySession("s-2"))
        assertEquals(0, store.countBySession("s-2"))

        store.save(MemoryDraft(kind = MemoryKind.HABIT, content = "用 Kotlin", tags = listOf("开发")))
        store.save(MemoryDraft(kind = MemoryKind.HABIT, content = "写单元测试", tags = listOf("开发")))
        assertEquals(2, store.deleteByTag("开发"))
        assertTrue(store.list().isEmpty())
    }
}

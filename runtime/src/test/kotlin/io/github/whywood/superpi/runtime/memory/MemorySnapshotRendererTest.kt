package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemorySnapshotRendererTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore
    private var nowMs: Long = 1_760_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-snap-test").toFile()
        store = MemoryStore(rootDir, clock = { nowMs })
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun rendersFrozenSectionsWithBudgetInferredMarkersAndOmitsExpiredActive() = runTest {
        store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "软件工程师，日常用 Kotlin",
                confidence = MemoryConfidence.CONFIRMED,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "偏爱安静的工作环境",
                confidence = MemoryConfidence.INFERRED,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.ACTIVE,
                content = "准备下周的技术分享会",
                validFrom = nowMs,
                validUntil = nowMs + 7L * 86_400_000L,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.ACTIVE,
                content = "已过期的一次性临时安排",
                validFrom = nowMs - 10L * 86_400_000L,
                validUntil = nowMs - 86_400_000L,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.HABIT,
                content = "给技术方案时先给结论再展开细节",
                tags = listOf("沟通"),
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "和张明对齐了新版个人记忆架构",
                entities = listOf(MemoryEntityRef("张明", EntityKind.PERSON, aliases = listOf("小明"), summary = "同事")),
                validFrom = nowMs,
            ),
        )

        val section = store.section()
        assertNotNull(section)
        val text = section!!
        assertTrue(text.contains("# About the User"))
        assertTrue(text.contains("[inferred]"))
        assertTrue(text.contains("# Active & Follow-ups"))
        assertTrue(text.contains("技术分享会"))
        assertFalse(text.contains("已过期的一次性临时安排"))
        assertTrue(text.contains("# Collaboration Habits"))
        assertTrue(text.contains("# Recent Episodes"))
        assertTrue(text.contains("# People & Topics"))
        assertTrue(text.contains("张明 (aliases: 小明): 同事"))

        val exportedDefault = store.exportMarkdown()
        assertTrue(exportedDefault.contains("# SuperPI Personal Memory Backup"))
        assertTrue(exportedDefault.contains("软件工程师"))

        val customExport = store.exportMarkdown(
            MemoryExportLabels.ENGLISH.copy(
                title = "个人记忆导出测试",
                sectionIdentity = "关于我",
            ),
        )
        assertTrue(customExport.contains("# 个人记忆导出测试"))
        assertTrue(customExport.contains("## 关于我"))
    }

    @Test
    fun respectsTotalBudgetUnderHeavyLoad() = runTest {
        repeat(80) { idx ->
            store.save(
                MemoryDraft(
                    kind = MemoryKind.IDENTITY,
                    content = "长期稳定个人事实条目编号 $idx " + "内容填充".repeat(35),
                ),
            )
        }
        val section = store.section()!!
        assertTrue(section.length <= MemorySnapshotRenderer.TOTAL_BUDGET_CHARS)
        assertTrue(MemorySnapshotRenderer.estimateTokens(section) <= MemorySnapshotRenderer.TOTAL_BUDGET_TOKENS)
    }

    @Test
    fun keepsOldIdentityAndHabitBeyond500RecentRecordsAndPrunesOrphanEntities() = runTest {
        store.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "最早建立的身份设定：建筑设计师",
                pinned = true,
                validFrom = nowMs - 1_000_000L,
            ),
        )
        store.save(
            MemoryDraft(
                kind = MemoryKind.HABIT,
                content = "最早建立的习惯：列清单输出",
                validFrom = nowMs - 1_000_000L,
            ),
        )
        val orphan = store.save(
            MemoryDraft(
                kind = MemoryKind.EPISODIC,
                content = "和孤立联系人开过短会",
                entities = listOf(MemoryEntityRef("孤立联系人", EntityKind.PERSON)),
                validFrom = nowMs,
            ),
        )
        repeat(510) { idx ->
            store.save(
                MemoryDraft(
                    kind = MemoryKind.EPISODIC,
                    content = "高频日常日记条目 $idx",
                    validFrom = nowMs + idx * 1_000L,
                ),
            )
        }

        val withOrphan = store.section()!!
        assertTrue(withOrphan.contains("建筑设计师"))
        assertTrue(withOrphan.contains("列清单输出"))
        assertTrue(withOrphan.contains("孤立联系人"))

        store.softDelete(orphan.id)
        val afterDelete = store.section()!!
        assertFalse(afterDelete.contains("孤立联系人"))
        assertFalse(store.exportMarkdown().contains("孤立联系人"))
    }

    @Test
    fun enforcesWeightedTokenBudgetForLatinText() = runTest {
        repeat(120) { idx ->
            store.save(
                MemoryDraft(
                    kind = MemoryKind.IDENTITY,
                    content = "Long English profile preference item number $idx with rich descriptive context about architecture and workflows.",
                ),
            )
        }
        val section = store.section()!!
        assertTrue(section.length <= MemorySnapshotRenderer.TOTAL_BUDGET_CHARS)
        assertTrue(MemorySnapshotRenderer.estimateTokens(section) <= MemorySnapshotRenderer.TOTAL_BUDGET_TOKENS)
    }
}

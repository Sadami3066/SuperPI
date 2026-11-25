package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySettings
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextRendererTest {
    private val nowMs = 1_760_300_000_000L

    @Test
    fun respects16kBudgetAndRendersPersonalVsWorkOnlyVsOffScopes() {
        val hugeBlock = MemoryBlock(
            id = "b-personal",
            slug = "self_profile",
            title = "关于我",
            purpose = "个人主线",
            content = "个人档案细节 " + "P".repeat(9_000),
            domain = MemoryDomains.PERSONAL,
            sensitivity = MemorySensitivity.NORMAL,
        )
        val workBlock = MemoryBlock(
            id = "b-work",
            slug = "work_study",
            title = "工作与学习域",
            purpose = "工作项",
            content = "负责 Android 架构与 Kotlin 跨端工具开发",
            domain = MemoryDomains.WORK,
            sensitivity = MemorySensitivity.NORMAL,
        )
        val privateWorkBlock = MemoryBlock(
            id = "b-work-priv",
            slug = "work_secret",
            title = "私人笔记",
            purpose = "隐私",
            content = "薪酬与跳槽机密",
            domain = MemoryDomains.WORK,
            sensitivity = MemorySensitivity.PRIVATE,
        )
        val healthBlock = MemoryBlock(
            id = "b-health",
            slug = "life_rhythm_health",
            title = "生活与健康",
            purpose = "作息",
            content = "对青霉素过敏",
            domain = MemoryDomains.HEALTH,
            sensitivity = MemorySensitivity.PRIVATE,
        )

        val mom = MemoryEntity(
            id = "e-mom",
            canonicalName = "母亲",
            entityType = MemoryEntityTypes.FAMILY,
            domain = MemoryDomains.RELATIONSHIPS,
            sensitivity = MemorySensitivity.PRIVATE,
        )
        val colleague = MemoryEntity(
            id = "e-alex",
            canonicalName = "Alex",
            entityType = MemoryEntityTypes.COLLEAGUE,
            domain = MemoryDomains.WORK,
            sensitivity = MemorySensitivity.NORMAL,
        )

        val relFamily = MemoryEntityRelation(
            id = "r1",
            fromEntityId = mom.id,
            fromEntityName = "母亲",
            relationType = MemoryRelationTypes.LIVES_IN,
            toEntityId = "e-hz",
            toEntityName = "杭州",
            domain = MemoryDomains.RELATIONSHIPS,
            sensitivity = MemorySensitivity.PRIVATE,
        )
        val relWork = MemoryEntityRelation(
            id = "r2",
            fromEntityId = colleague.id,
            fromEntityName = "Alex",
            relationType = MemoryRelationTypes.WORKS_AT,
            toEntityId = "e-team",
            toEntityName = "Platform Team",
            domain = MemoryDomains.WORK,
            sensitivity = MemorySensitivity.NORMAL,
        )

        val personalEvent = MemoryEvent(
            id = "ev-personal",
            eventKind = MemoryEventKind.COMMITMENT,
            content = "周六带妈妈去西湖边散步",
            domain = MemoryDomains.RELATIONSHIPS,
            sensitivity = MemorySensitivity.PRIVATE,
            epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
            eventTime = nowMs,
        )
        val workEvents = (1..30).map { i ->
            MemoryEvent(
                id = "ev-work-$i",
                eventKind = MemoryEventKind.FACT,
                content = "工作项目迭代记录 $i " + "W".repeat(300),
                domain = MemoryDomains.WORK,
                sensitivity = MemorySensitivity.NORMAL,
                epistemicTier = MemoryEpistemicTier.OBSERVED,
                eventTime = nowMs - i * 1_000L,
            )
        }

        val snapshot = ContextSnapshotData(
            blocks = listOf(hugeBlock, workBlock, privateWorkBlock, healthBlock),
            archivedBlocks = listOf(
                workBlock.copy(id = "b-arch", slug = "old_marathon", title = "马拉松备赛", status = MemoryBlockStatus.ARCHIVED),
            ),
            entities = listOf(mom, colleague),
            relations = listOf(relFamily, relWork),
            recentEvents = listOf(personalEvent) + workEvents,
            unexpandedEntityNames = listOf("外婆", "羽毛球馆"),
            archivedTitlesAndSlugs = listOf("old_marathon" to "马拉松备赛"),
            monthlyMilestones = listOf("2026-10" to 12),
        )

        val personalRendered = MemoryContextRenderer.render(snapshot, MemoryAccessScope.PERSONAL)
        assertTrue(personalRendered.isNotBlank())
        assertTrue(personalRendered.length <= MemoryLimits.MAX_RENDERED_CONTEXT_CHARS)
        assertTrue(personalRendered.contains("Deep Memory Index"))
        assertTrue(personalRendered.contains("old_marathon"))
        assertTrue(personalRendered.contains("2026-10 (12)"))
        assertTrue(personalRendered.contains("青霉素过敏"))
        assertTrue(personalRendered.contains("母亲"))

        val workRendered = MemoryContextRenderer.render(snapshot, MemoryAccessScope.WORK_ONLY)
        assertTrue(workRendered.isNotBlank())
        assertTrue(workRendered.length <= MemoryLimits.MAX_RENDERED_CONTEXT_CHARS)
        assertTrue(workRendered.contains("Kotlin 跨端工具开发"))
        assertTrue(workRendered.contains("Alex"))
        assertFalse(workRendered.contains("青霉素过敏"))
        assertFalse(workRendered.contains("母亲"))
        assertFalse(workRendered.contains("薪酬与跳槽机密"))
        assertFalse(workRendered.contains("带妈妈去西湖"))
        assertFalse(workRendered.contains("Deep Memory Index"))

        assertEquals("", MemoryContextRenderer.render(snapshot, MemoryAccessScope.OFF))
    }

    @Test
    fun supportsUnlimitedCustomScalingSectionTogglesAndCacheInvalidation() = runBlocking {
        val hugeBlock = MemoryBlock(
            id = "b-huge",
            slug = "self_profile",
            title = "关于我",
            purpose = "主线",
            content = "A".repeat(12_000),
            domain = MemoryDomains.PERSONAL,
        )
        val manyEvents = (1..20).map { idx ->
            MemoryEvent(
                id = "ev-$idx",
                eventKind = MemoryEventKind.FACT,
                content = "生活记录 $idx " + "E".repeat(150),
                domain = MemoryDomains.PERSONAL,
                epistemicTier = MemoryEpistemicTier.OBSERVED,
                eventTime = nowMs - idx * 1_000L,
            )
        }
        val entity = MemoryEntity(
            id = "e1",
            canonicalName = "林夕",
            entityType = MemoryEntityTypes.FRIEND,
            domain = MemoryDomains.RELATIONSHIPS,
        )
        val snapshot = ContextSnapshotData(
            blocks = listOf(hugeBlock),
            archivedBlocks = emptyList(),
            entities = listOf(entity),
            relations = emptyList(),
            recentEvents = manyEvents,
            archivedTitlesAndSlugs = listOf("old_notes" to "旧笔记"),
        )

        val scaledSmall = MemoryContextRenderer.render(
            snapshot = snapshot,
            scope = MemoryAccessScope.PERSONAL,
            budget = MemoryInjectionBudget(totalMaxChars = 8_000),
        )
        assertTrue(scaledSmall.length <= 8_000)

        val unlimited = MemoryContextRenderer.render(
            snapshot = snapshot,
            scope = MemoryAccessScope.PERSONAL,
            budget = MemoryInjectionBudget(totalMaxChars = MemorySettings.INJECTION_UNLIMITED),
        )
        assertTrue(unlimited.contains("A".repeat(12_000)))
        assertTrue(unlimited.contains("ev-20"))
        assertTrue(unlimited.contains("林夕"))
        assertTrue(unlimited.contains("Deep Memory Index"))

        val profileOnly = MemoryContextRenderer.render(
            snapshot = snapshot,
            scope = MemoryAccessScope.PERSONAL,
            budget = MemoryInjectionBudget(
                totalMaxChars = MemorySettings.INJECTION_UNLIMITED,
                relationsMaxChars = MemorySettings.SECTION_DISABLED,
                eventsMaxChars = MemorySettings.SECTION_DISABLED,
                directoryMaxChars = MemorySettings.SECTION_DISABLED,
            ),
        )
        assertTrue(profileOnly.contains("## Living Profile"))
        assertFalse(profileOnly.contains("## Key People & Topics"))
        assertFalse(profileOnly.contains("## Active Commitments & Recent Events"))
        assertFalse(profileOnly.contains("## Deep Memory Index"))

        val dir = Files.createTempDirectory("superpi-budget-store").toFile()
        var currentBudget = MemoryInjectionBudget(totalMaxChars = 4_000)
        val store = MemoryStore(dir, clock = { nowMs }, injectionBudgetProvider = { currentBudget })
        try {
            store.ensure()
            val profile = store.getBlockBySlug("self_profile")!!
            store.updateBlock(
                id = profile.id,
                content = "B".repeat(1_800),
            )
            store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "事件记录验证动态刷新",
                    epistemicTier = MemoryEpistemicTier.OBSERVED,
                ),
            )
            val initial = store.section(MemoryAccessScope.PERSONAL)
            assertTrue(initial.contains("B".repeat(1_800)))
            assertTrue(initial.contains("事件记录验证动态刷新"))

            currentBudget = MemoryInjectionBudget(
                totalMaxChars = MemorySettings.INJECTION_UNLIMITED,
                eventsMaxChars = MemorySettings.SECTION_DISABLED,
            )
            assertTrue(store.section(MemoryAccessScope.PERSONAL).contains("事件记录验证动态刷新"))
            store.invalidateContextCache()
            val updated = store.section(MemoryAccessScope.PERSONAL)
            assertTrue(updated.contains("B".repeat(1_800)))
            assertFalse(updated.contains("事件记录验证动态刷新"))
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun clampsOversizedFirstEventEntityAndPreservesBlockNewline() {
        val b1 = MemoryBlock(
            id = "b1",
            slug = "self_profile",
            title = "关于我",
            purpose = "主线",
            content = "X".repeat(300),
            domain = MemoryDomains.PERSONAL,
        )
        val b2 = MemoryBlock(
            id = "b2",
            slug = "work_study",
            title = "工作域",
            purpose = "工作",
            content = "Y".repeat(400),
            domain = MemoryDomains.PERSONAL,
        )
        val longEntity = MemoryEntity(
            id = "e-long",
            canonicalName = "林夕",
            entityType = MemoryEntityTypes.FRIEND,
            summary = "Z".repeat(1_200),
            domain = MemoryDomains.RELATIONSHIPS,
        )
        val longEvent = MemoryEvent(
            id = "ev-long",
            eventKind = MemoryEventKind.COMMITMENT,
            content = "重要约定 " + "Q".repeat(1_500),
            domain = MemoryDomains.PERSONAL,
            epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
            eventTime = nowMs,
        )
        val snapshot = ContextSnapshotData(
            blocks = listOf(b1, b2),
            archivedBlocks = emptyList(),
            entities = listOf(longEntity),
            relations = emptyList(),
            recentEvents = listOf(longEvent),
        )

        val rendered = MemoryContextRenderer.render(
            snapshot = snapshot,
            scope = MemoryAccessScope.PERSONAL,
            budget = MemoryInjectionBudget(
                totalMaxChars = MemorySettings.INJECTION_UNLIMITED,
                profileMaxChars = 500,
                relationsMaxChars = 500,
                eventsMaxChars = 500,
            ),
        )
        assertTrue(rendered.contains("\n### 工作域 [slug=work_study]"))
        assertTrue(rendered.contains("## Key People & Topics\n- 林夕"))
        assertTrue(rendered.contains("## Active Commitments & Recent Events\n- [id=ev-long"))
    }
}

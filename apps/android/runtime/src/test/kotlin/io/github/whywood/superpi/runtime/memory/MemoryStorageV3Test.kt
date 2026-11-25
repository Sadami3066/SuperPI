package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryStorageV3Test {
    private lateinit var rootDir: File
    private var nowMs = 1_760_100_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-storage-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun epistemicGuardProtectsPinnedAndUserExplicitRecordsFromWeakerAgentEdits() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val profile = store.getBlockBySlug("self_profile")!!
            store.updateBlock(
                id = profile.id,
                content = "常住上海徐汇",
                pinned = true,
                actor = MemoryMutationActor.USER,
            )

            val pinnedEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.BOUNDARY,
                    content = "绝对不吃香菜",
                    epistemicTier = MemoryEpistemicTier.USER_PINNED,
                    pinned = true,
                ),
                actor = MemoryMutationActor.USER,
            )
            val explicitEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.PREFERENCE,
                    content = "偏好深烘美式咖啡",
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                ),
                actor = MemoryMutationActor.USER,
            )
            val explicitRel = store.upsertRelation(
                fromEntityName = "用户",
                relationType = MemoryRelationTypes.LIVES_IN,
                toEntityName = "上海",
                epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
            )!!

            val receipt = store.applyAgentCommitBatch(
                runId = "run-guard",
                actor = MemoryMutationActor.MEMORY_AGENT_HOT,
                batch = MemoryAgentCommitBatch(
                    patchBlocks = listOf(
                        BlockPatchOp(
                            slug = "self_profile",
                            fullContent = "Agent attempted overwrite",
                            userSummary = "overwrite",
                        ),
                    ),
                    contractEvents = listOf(
                        ContractEventOp(
                            eventId = pinnedEvent.id,
                            softDelete = true,
                            actingTier = MemoryEpistemicTier.USER_EXPLICIT,
                            userSummary = "delete pinned",
                        ),
                        ContractEventOp(
                            eventId = explicitEvent.id,
                            newStatus = MemoryItemStatus.RESOLVED,
                            actingTier = MemoryEpistemicTier.INFERRED,
                            userSummary = "inferred resolve",
                        ),
                    ),
                    invalidateRelations = listOf(
                        InvalidateRelationOp(
                            relationId = explicitRel.id,
                            actingTier = MemoryEpistemicTier.INFERRED,
                            reason = "weak guess",
                            userSummary = "inferred invalidate",
                        ),
                    ),
                ),
            )

            assertEquals(0, receipt.blocksPatched)
            assertEquals(0, receipt.eventsForgotten)
            assertEquals(0, receipt.relationsInvalidated)
            assertEquals(4, receipt.blockedByGuard.size)

            assertEquals("常住上海徐汇", store.getBlockBySlug("self_profile")!!.content)
            assertNotNull(store.getEvent(pinnedEvent.id))
            assertEquals(MemoryItemStatus.ACTIVE, store.getEvent(explicitEvent.id)!!.status)
            assertTrue(store.listRelations().first { it.id == explicitRel.id }.isActive)

            nowMs += 1_000L
            store.updateBlock(
                id = profile.id,
                content = "常住上海徐汇区（用户确认更新）",
                actor = MemoryMutationActor.USER,
            )
            assertEquals("常住上海徐汇区（用户确认更新）", store.getBlockBySlug("self_profile")!!.content)
        } finally {
            store.close()
        }
    }

    @Test
    fun mergeEntitiesAndOneTapAuditUndoRestoreAllMutationsCleanly() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val keeper = store.upsertEntity(
                MemoryEntityRef(
                    name = "小陈",
                    entityType = MemoryEntityTypes.FRIEND,
                    aliases = listOf("阿陈"),
                    attributes = mapOf("city" to "杭州"),
                ),
            )
            val duplicate = store.upsertEntity(
                MemoryEntityRef(
                    name = "陈同学",
                    entityType = MemoryEntityTypes.FRIEND,
                    aliases = listOf("Chen"),
                    attributes = mapOf("hobby" to "攀岩"),
                ),
            )
            val sharedEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.EPISODE,
                    content = "周末和小陈一起去攀岩",
                    entities = listOf(
                        MemoryEntityRef(name = "小陈", entityType = MemoryEntityTypes.FRIEND),
                        MemoryEntityRef(name = "陈同学", entityType = MemoryEntityTypes.FRIEND),
                    ),
                ),
            )
            val dupOnlyEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "陈同学买了新岩鞋",
                    entities = listOf(MemoryEntityRef(name = "陈同学", entityType = MemoryEntityTypes.FRIEND)),
                ),
            )
            store.upsertRelation(
                fromEntityName = "陈同学",
                relationType = MemoryRelationTypes.LIVES_IN,
                toEntityName = "杭州",
            )

            nowMs += 1_000L
            val merged = store.mergeEntities(
                keepId = keeper.id,
                mergeIds = listOf(duplicate.id),
                mergedSummary = "挚友小陈，喜欢攀岩",
                actor = MemoryMutationActor.MEMORY_AGENT_COLD,
                runId = "run-merge",
            )
            assertEquals(1, merged)

            val entitiesAfterMerge = store.listEntities()
            assertEquals(listOf(keeper.id), entitiesAfterMerge.filter { it.id == keeper.id || it.id == duplicate.id }.map { it.id })
            val keeperDetail = store.inspectEntity(keeper.id)!!
            assertTrue(keeperDetail.entity.aliases.containsAll(listOf("阿陈", "陈同学", "Chen")))
            assertEquals("攀岩", keeperDetail.entity.attributes["hobby"])
            assertEquals(setOf(sharedEvent.id, dupOnlyEvent.id), keeperDetail.linkedEvents.map { it.id }.toSet())

            val mergeAudit = store.listAuditLog(20).first { it.actionType == "MERGE_ENTITIES" }
            assertTrue(store.revertAuditEntry(mergeAudit.id))
            assertFalse(store.listAuditLog(20).first { it.id == mergeAudit.id }.canRevert)

            val keeperAfterUndo = store.inspectEntity(keeper.id)!!
            val dupAfterUndo = store.inspectEntity(duplicate.id)
            assertNotNull(dupAfterUndo)
            assertTrue(keeperAfterUndo.linkedEvents.any { it.id == sharedEvent.id })
            assertFalse(keeperAfterUndo.linkedEvents.any { it.id == dupOnlyEvent.id })
            assertTrue(dupAfterUndo!!.linkedEvents.any { it.id == dupOnlyEvent.id })

            nowMs += 1_000L
            store.applyAgentCommitBatch(
                runId = "run-undo",
                actor = MemoryMutationActor.MEMORY_AGENT_HOT,
                batch = MemoryAgentCommitBatch(
                    patchBlocks = listOf(
                        BlockPatchOp(
                            slug = "preferences_boundaries",
                            appendText = "- 睡前看纸质书",
                            userSummary = "补充了阅读偏好",
                        ),
                    ),
                    recordEvents = listOf(
                        RecordEventOp(
                            draft = MemoryEventDraft(
                                eventKind = MemoryEventKind.PREFERENCE,
                                content = "睡前看半小时纸质书",
                            ),
                            userSummary = "记录了睡前习惯",
                        ),
                    ),
                ),
            )
            assertTrue(store.getBlockBySlug("preferences_boundaries")!!.content.contains("纸质书"))
            assertTrue(store.listEvents(MemoryEventFilter(limit = 20)).any { it.content.contains("纸质书") })

            val patchAudit = store.listAuditLog(20).first { it.actionType == "UPDATE_BLOCK" && it.summaryUserText.contains("阅读") }
            val createAudit = store.listAuditLog(20).first { it.actionType == "RECORD_EVENT" && it.summaryUserText.contains("睡前") }
            assertTrue(store.revertAuditEntry(patchAudit.id))
            assertTrue(store.revertAuditEntry(createAudit.id))

            assertFalse(store.getBlockBySlug("preferences_boundaries")!!.content.contains("纸质书"))
            assertFalse(store.listEvents(MemoryEventFilter(limit = 20)).any { it.content.contains("纸质书") })
        } finally {
            store.close()
        }
    }

    @Test
    fun boundedAccommodationProposalsSupportAcceptDismissAndRespectSoftCap() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val p1 = store.createProposal(
                slug = "pet_care",
                title = "宠物照顾",
                purpose = "记录猫咪饮食、体检与疫苗日期",
                domain = MemoryDomains.LIFE,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "布丁（英短）每年三月打狂犬疫苗",
                supportingEventIds = listOf("e1", "e2", "e3", "e4", "e5"),
                reason = "近期连续提到猫咪健康安排",
            )
            assertNotNull(p1)
            val accepted = store.acceptProposal(p1!!.id)
            assertNotNull(accepted)
            assertEquals(MemoryBlockOwner.AGENT, accepted!!.owner)
            assertEquals(MemoryProposalStatus.ACCEPTED, store.listProposals(null).first { it.id == p1.id }.status)
            assertNotNull(store.getBlockBySlug("pet_care"))

            val p2 = store.createProposal(
                slug = "travel_notes",
                title = "旅行备忘",
                purpose = "旅行常带装备与偏好",
                domain = MemoryDomains.LIFE,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "靠窗座位",
                supportingEventIds = listOf("t1", "t2", "t3", "t4", "t5"),
                reason = "多次行程记录",
            )
            assertNotNull(p2)
            assertTrue(store.dismissProposal(p2!!.id))
            assertTrue(store.listProposals(MemoryProposalStatus.PENDING).isEmpty())

            while (store.listBlocks(includeArchived = false).size < MemoryLimits.SOFT_MAX_ACTIVE_BLOCKS) {
                val n = store.listBlocks(includeArchived = false).size
                store.createBlock(
                    MemoryBlockDraft(
                        slug = "extra_block_$n",
                        title = "Extra $n",
                        purpose = "Capacity test",
                    ),
                )
            }
            val blockedAtCap = store.createProposal(
                slug = "over_cap_block",
                title = "Over Cap",
                purpose = "Should be skipped",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "data",
                supportingEventIds = listOf("x1", "x2", "x3", "x4", "x5"),
                reason = "cap reached",
            )
            assertNull(blockedAtCap)
        } finally {
            store.close()
        }
    }

    @Test
    fun auditRevertCleansSupersedeReplacementRestoresCollapsedSelfLoopRelationsAndClearsEntitySummary() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val teaEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.PREFERENCE,
                    content = "喜欢乌龙茶",
                    tags = listOf("tea"),
                ),
            )
            val teachingEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "周三有教学会议",
                    tags = listOf("teaching"),
                ),
            )
            val teaOnly = store.listEvents(MemoryEventFilter(tag = "tea", limit = 20))
            assertEquals(listOf(teaEvent.id), teaOnly.map { it.id })
            assertEquals(listOf(teachingEvent.id), store.listEvents(MemoryEventFilter(tag = "teaching", limit = 20)).map { it.id })

            nowMs += 1_000L
            store.applyAgentCommitBatch(
                runId = "run-sup",
                actor = MemoryMutationActor.MEMORY_AGENT_HOT,
                batch = MemoryAgentCommitBatch(
                    supersedeEvents = listOf(
                        SupersedeEventOp(
                            oldEventId = teaEvent.id,
                            replacement = MemoryEventDraft(
                                eventKind = MemoryEventKind.PREFERENCE,
                                content = "更偏好岩茶",
                                tags = listOf("tea"),
                                epistemicTier = MemoryEpistemicTier.OBSERVED,
                            ),
                            userSummary = "更新饮茶偏好",
                        ),
                    ),
                ),
            )
            val supAudit = store.listAuditLog(20).first { it.actionType == "SUPERSEDE_EVENT" }
            assertNull(supAudit.reason)
            val replacementId = store.getEvent(teaEvent.id)!!.supersededBy!!
            assertNotNull(store.getEvent(replacementId))
            assertTrue(store.revertAuditEntry(supAudit.id))
            assertEquals(MemoryItemStatus.ACTIVE, store.getEvent(teaEvent.id)!!.status)
            assertNull(store.getEvent(teaEvent.id)!!.supersededBy)
            assertNull(store.getEvent(replacementId))

            val e1 = store.upsertEntity(MemoryEntityRef("李四", MemoryEntityTypes.FRIEND, summary = "初期备注"))
            val e2 = store.upsertEntity(MemoryEntityRef("小李", MemoryEntityTypes.FRIEND))
            val mutualRel = store.upsertRelation(
                fromEntityName = "李四",
                relationType = MemoryRelationTypes.FRIEND_OF,
                toEntityName = "小李",
            )!!
            nowMs += 1_000L
            assertEquals(1, store.mergeEntities(e1.id, listOf(e2.id), actor = MemoryMutationActor.MEMORY_AGENT_COLD))
            assertTrue(store.listRelations().none { it.id == mutualRel.id })

            val mergeAudit = store.listAuditLog(20).first { it.actionType == "MERGE_ENTITIES" }
            assertTrue(store.revertAuditEntry(mergeAudit.id))
            assertTrue(store.listRelations().any { it.id == mutualRel.id && it.isActive })

            val cleared = store.updateEntity(id = e1.id, clearSummary = true)!!
            assertNull(cleared.summary)

            assertTrue(store.invalidateRelation(mutualRel.id))
            assertTrue(store.listRelations(includeInvalidated = false).none { it.id == mutualRel.id })
        } finally {
            store.close()
        }
    }

    @Test
    fun handlesDeleteRevertSlugCollisionCacheInvalidationActiveEventCountAndHistoryExport() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val first = store.createBlock(
                io.github.whywood.superpi.runtime.MemoryBlockDraft(
                    slug = "pet_notes",
                    title = "宠物笔记",
                    purpose = "宠物记录",
                    content = "橘猫体检",
                    owner = io.github.whywood.superpi.runtime.MemoryBlockOwner.USER,
                ),
            )
            assertTrue(store.deleteCustomBlock(first.id))
            val deleteAudit = store.listAuditLog(20).first { it.actionType == "DELETE_BLOCK" && it.targetId == first.id }
            store.createBlock(
                io.github.whywood.superpi.runtime.MemoryBlockDraft(
                    slug = "pet_notes",
                    title = "宠物笔记",
                    purpose = "宠物记录",
                    content = "新记录",
                    owner = io.github.whywood.superpi.runtime.MemoryBlockOwner.USER,
                ),
            )
            assertTrue(store.revertAuditEntry(deleteAudit.id))
            val blocksWithRestored = store.listBlocks().filter { it.content == "橘猫体检" }
            assertEquals(1, blocksWithRestored.size)
            assertFalse(blocksWithRestored.first().slug == "pet_notes")

            val initialSection = store.section(MemoryAccessScope.PERSONAL)
            assertFalse(initialSection.contains("林夕"))
            store.upsertRelation(
                fromEntityName = "我",
                relationType = io.github.whywood.superpi.runtime.MemoryRelationTypes.FRIEND_OF,
                toEntityName = "林夕",
                label = "咖啡搭子",
            )
            assertTrue(store.section(MemoryAccessScope.PERSONAL).contains("林夕"))

            val activeEv = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "活跃事实",
                    epistemicTier = MemoryEpistemicTier.OBSERVED,
                ),
            )
            val archivedEv = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "历史已归档事件标记",
                    epistemicTier = MemoryEpistemicTier.OBSERVED,
                ),
            )
            store.applyAgentCommitBatch(
                runId = "r-arch",
                actor = MemoryMutationActor.MEMORY_AGENT_COLD,
                batch = MemoryAgentCommitBatch(
                    contractEvents = listOf(
                        ContractEventOp(
                            eventId = archivedEv.id,
                            newStatus = MemoryItemStatus.ARCHIVED,
                            userSummary = "归档旧事件",
                        ),
                    ),
                ),
            )
            assertEquals(1, store.countActiveEventsByIds(listOf(activeEv.id, archivedEv.id)))

            for (i in 1..205) {
                store.saveEvent(
                    MemoryEventDraft(
                        eventKind = MemoryEventKind.NOTE,
                        content = "批量活跃记录 $i",
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                        eventTime = nowMs + i * 1_000L,
                    ),
                )
            }
            val exported = store.exportMarkdown()
            assertTrue(exported.contains("历史已归档事件标记"))
        } finally {
            store.close()
        }
    }
}

package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.sdk.coding.session.SessionEntry

internal data class CapturedTranscript(
    val sessionId: String,
    val sessionFile: String?,
    val entries: List<SessionEntry>,
)

internal data class IngestionWatermark(
    val sessionId: String,
    val sessionFile: String?,
    val lastEntryId: String?,
    val lastHash: String?,
    val updatedAt: Long,
)

internal data class PauseWindow(
    val startMs: Long,
    val endMs: Long? = null,
)

internal data class CachedContextSection(
    val scopeWire: String,
    val renderedText: String,
    val charCount: Int,
    val revision: Long,
    val generatedAt: Long,
    val reason: String,
)

internal enum class DispatchJobKind(val wire: String, val defaultPriority: Int) {
    DISPATCH_INTENT("DISPATCH_INTENT", 100),
    SESSION_IDLE("SESSION_IDLE", 50),
    SESSION_COMPACT("SESSION_COMPACT", 50),
    SESSION_CLOSE("SESSION_CLOSE", 50),
    MIGRATION_REHYDRATE("MIGRATION_REHYDRATE", 30),
    SLEEP_CONSOLIDATE("SLEEP_CONSOLIDATE", 10),
    ;

    companion object {
        fun fromWire(value: String?): DispatchJobKind = when (value?.trim()?.uppercase()) {
            "DISPATCH_INTENT" -> DISPATCH_INTENT
            "SESSION_COMPACT" -> SESSION_COMPACT
            "SESSION_CLOSE" -> SESSION_CLOSE
            "MIGRATION_REHYDRATE" -> MIGRATION_REHYDRATE
            "SLEEP_CONSOLIDATE" -> SLEEP_CONSOLIDATE
            else -> SESSION_IDLE
        }
    }
}

internal data class DispatchQueueJob(
    val id: String,
    val jobKind: DispatchJobKind,
    val priority: Int,
    val sessionId: String? = null,
    val payloadJson: String,
    val transcriptHash: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

internal data class BlockPatchOp(
    val slug: String,
    val findText: String? = null,
    val replaceText: String? = null,
    val appendText: String? = null,
    val fullContent: String? = null,
    val userSummary: String,
)

internal data class RecordEventOp(
    val draft: MemoryEventDraft,
    val userSummary: String,
)

internal data class SupersedeEventOp(
    val oldEventId: String,
    val replacement: MemoryEventDraft,
    val userSummary: String,
)

internal data class ContractEventOp(
    val eventId: String,
    val newStatus: MemoryItemStatus? = null,
    val softDelete: Boolean = false,
    val actingTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val userSummary: String,
    val reason: String? = null,
)

internal data class UpsertEntityOp(
    val canonicalName: String,
    val entityType: String,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val summary: String? = null,
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val userSummary: String,
)

internal data class UpsertRelationOp(
    val fromEntityName: String,
    val relationType: String,
    val toEntityName: String,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val epistemicTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val userSummary: String,
)

internal data class InvalidateRelationOp(
    val relationId: String,
    val actingTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val reason: String? = null,
    val userSummary: String,
)

internal data class MemoryAgentCommitBatch(
    val patchBlocks: List<BlockPatchOp> = emptyList(),
    val recordEvents: List<RecordEventOp> = emptyList(),
    val supersedeEvents: List<SupersedeEventOp> = emptyList(),
    val contractEvents: List<ContractEventOp> = emptyList(),
    val upsertEntities: List<UpsertEntityOp> = emptyList(),
    val upsertRelations: List<UpsertRelationOp> = emptyList(),
    val invalidateRelations: List<InvalidateRelationOp> = emptyList(),
    val defaultSummary: String = "Updated personal memory",
) {
    val isEmpty: Boolean
        get() = patchBlocks.isEmpty() &&
            recordEvents.isEmpty() &&
            supersedeEvents.isEmpty() &&
            contractEvents.isEmpty() &&
            upsertEntities.isEmpty() &&
            upsertRelations.isEmpty() &&
            invalidateRelations.isEmpty()
}

internal data class CommitBatchReceipt(
    val blocksPatched: Int = 0,
    val eventsRecorded: Int = 0,
    val eventsSuperseded: Int = 0,
    val eventsForgotten: Int = 0,
    val entitiesUpserted: Int = 0,
    val relationsUpserted: Int = 0,
    val relationsInvalidated: Int = 0,
    val blockedByGuard: List<String> = emptyList(),
) {
    val totalApplied: Int
        get() = blocksPatched +
            eventsRecorded +
            eventsSuperseded +
            eventsForgotten +
            entitiesUpserted +
            relationsUpserted +
            relationsInvalidated
}

internal data class ContextSnapshotData(
    val blocks: List<io.github.whywood.superpi.runtime.MemoryBlock>,
    val archivedBlocks: List<io.github.whywood.superpi.runtime.MemoryBlock>,
    val entities: List<io.github.whywood.superpi.runtime.MemoryEntity>,
    val relations: List<io.github.whywood.superpi.runtime.MemoryEntityRelation>,
    val recentEvents: List<io.github.whywood.superpi.runtime.MemoryEvent>,
    val historyEvents: List<io.github.whywood.superpi.runtime.MemoryEvent> = emptyList(),
    val unexpandedEntityNames: List<String> = emptyList(),
    val archivedTitlesAndSlugs: List<Pair<String, String>> = emptyList(),
    val monthlyMilestones: List<Pair<String, Int>> = emptyList(),
)

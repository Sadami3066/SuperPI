package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind

enum class MemorySyncKind(val wire: String, val pushOrder: Int) {
    BLOCK("block", 0),
    ENTITY("entity", 1),
    EVENT("event", 2),
    RELATION("relation", 3),
    PROPOSAL("proposal", 4),
    ;

    companion object {
        fun fromWire(raw: String?): MemorySyncKind? = when (raw?.trim()?.lowercase()) {
            "block" -> BLOCK
            "entity" -> ENTITY
            "event" -> EVENT
            "relation" -> RELATION
            "proposal" -> PROPOSAL
            else -> null
        }
    }
}

data class MemoryBlockSyncRecord(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val schemaHint: String? = null,
    val content: String,
    val domain: String,
    val sensitivity: MemorySensitivity,
    val injectionMode: MemoryBlockInjectionMode,
    val maxChars: Int,
    val owner: MemoryBlockOwner,
    val pinned: Boolean,
    val status: MemoryBlockStatus,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long,
    val updatedByDeviceId: String? = null,
)

data class MemoryEntitySyncRecord(
    val id: String,
    val canonicalName: String,
    val entityType: String,
    val aliases: List<String>,
    val attributesJson: String,
    val summary: String? = null,
    val domain: String,
    val sensitivity: MemorySensitivity,
    val pinned: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long,
    val mergedInto: String? = null,
    val updatedByDeviceId: String? = null,
)

data class MemoryEventEntityLink(
    val entityId: String,
    val role: String,
)

data class MemoryEventSyncRecord(
    val id: String,
    val eventKind: MemoryEventKind,
    val content: String,
    val summary: String? = null,
    val qualifiersJson: String,
    val semanticKeywords: List<String>,
    val tags: List<String>,
    val domain: String,
    val sensitivity: MemorySensitivity,
    val epistemicTier: MemoryEpistemicTier,
    val importance: Int,
    val pinned: Boolean,
    val eventTime: Long,
    val eventTimePrecision: MemoryEventTimePrecision,
    val validFrom: Long,
    val validUntil: Long? = null,
    val status: MemoryItemStatus,
    val supersededBy: String? = null,
    val sourceKind: MemorySourceKind,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
    val entityLinks: List<MemoryEventEntityLink> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long,
    val updatedByDeviceId: String? = null,
)

data class MemoryRelationSyncRecord(
    val id: String,
    val fromEntityId: String,
    val relationType: String,
    val toEntityId: String,
    val label: String? = null,
    val attributesJson: String,
    val validFrom: Long,
    val validUntil: Long? = null,
    val invalidatedAt: Long? = null,
    val invalidatedBy: String? = null,
    val epistemicTier: MemoryEpistemicTier,
    val domain: String,
    val sensitivity: MemorySensitivity,
    val sourceEventId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long,
    val updatedByDeviceId: String? = null,
)

data class MemoryProposalSyncRecord(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val domain: String,
    val sensitivity: MemorySensitivity,
    val seedContent: String,
    val supportingEventIds: List<String>,
    val reason: String,
    val status: MemoryProposalStatus,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long,
    val updatedByDeviceId: String? = null,
)

data class MemorySyncOpItem(
    val opId: String,
    val kind: MemorySyncKind,
    val block: MemoryBlockSyncRecord? = null,
    val entity: MemoryEntitySyncRecord? = null,
    val event: MemoryEventSyncRecord? = null,
    val relation: MemoryRelationSyncRecord? = null,
    val proposal: MemoryProposalSyncRecord? = null,
)

data class MemoryRemotePullEntry(
    val seq: Long,
    val kind: MemorySyncKind,
    val deviceId: String,
    val block: MemoryBlockSyncRecord? = null,
    val entity: MemoryEntitySyncRecord? = null,
    val event: MemoryEventSyncRecord? = null,
    val relation: MemoryRelationSyncRecord? = null,
    val proposal: MemoryProposalSyncRecord? = null,
)

data class MemoryPullApplyResult(
    val appliedCount: Int,
    val deferredEntries: List<MemoryRemotePullEntry>,
    val hadLocalAndRemoteOverlap: Boolean,
)

data class MemoryAccountSwitchCheck(
    val requiresPrompt: Boolean,
    val localRecordCount: Int,
)

enum class MemoryAccountSwitchMode {
    MERGE,
    REPLACE_LOCAL,
}

sealed interface ConsolidationLeaseResult {
    data class Acquired(val leaseId: String?) : ConsolidationLeaseResult

    data class Blocked(val reason: String) : ConsolidationLeaseResult

    data object Offline : ConsolidationLeaseResult

    data object Unauthorized : ConsolidationLeaseResult

    data object Rejected : ConsolidationLeaseResult
}

interface MemoryConsolidationCoordinator {
    suspend fun prepareForConsolidation(forceManual: Boolean, minIntervalMs: Long): ConsolidationLeaseResult

    suspend fun onConsolidationCompleted(leaseId: String?, finishedAt: Long)

    suspend fun onConsolidationFinished(leaseId: String?)

    companion object {
        val NOOP = object : MemoryConsolidationCoordinator {
            override suspend fun prepareForConsolidation(
                forceManual: Boolean,
                minIntervalMs: Long,
            ): ConsolidationLeaseResult = ConsolidationLeaseResult.Acquired(null)

            override suspend fun onConsolidationCompleted(leaseId: String?, finishedAt: Long) = Unit

            override suspend fun onConsolidationFinished(leaseId: String?) = Unit
        }
    }
}

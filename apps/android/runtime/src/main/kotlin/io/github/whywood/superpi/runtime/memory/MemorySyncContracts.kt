package io.github.whywood.superpi.runtime.memory

import java.security.MessageDigest
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind

data class MemorySyncRecord(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val confidence: MemoryConfidence,
    val importance: Int,
    val pinned: Boolean,
    val validFrom: Long,
    val validUntil: Long? = null,
    val status: MemoryItemStatus,
    val supersededBy: String? = null,
    val sourceKind: MemorySourceKind,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val updatedByDeviceId: String? = null,
)

data class EntitySyncRecord(
    val canonicalKey: String,
    val id: String,
    val canonicalName: String,
    val kind: EntityKind,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val mergedInto: String? = null,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val revision: Long = 1L,
    val updatedByDeviceId: String? = null,
)

enum class MemorySyncOpKind(val wire: String) {
    MEMORY("memory"),
    ENTITY("entity"),
    ;

    companion object {
        fun fromWire(value: String?): MemorySyncOpKind? = when (value?.trim()?.lowercase()) {
            "memory" -> MEMORY
            "entity" -> ENTITY
            else -> null
        }
    }
}

data class MemorySyncOpItem(
    val opId: String,
    val kind: MemorySyncOpKind,
    val memory: MemorySyncRecord? = null,
    val entity: EntitySyncRecord? = null,
)

data class MemoryPendingPushBatch(
    val ops: List<MemorySyncOpItem>,
    val isBaseline: Boolean,
    val nextBaselineStage: String,
    val nextBaselineCursorId: String,
    val nextInitialPushDone: Boolean,
    val maxLocalSyncSeq: Long,
    val baselineCutoffSeq: Long,
)

data class MemoryRemotePullEntry(
    val seq: Long,
    val kind: MemorySyncOpKind,
    val deviceId: String,
    val memory: MemorySyncRecord? = null,
    val entity: EntitySyncRecord? = null,
)

data class MemoryRemoteMergeResult(
    val appliedCount: Int,
    val hadLocalAndRemoteOverlap: Boolean,
    val serverSeq: Long,
)

data class MemorySyncCursor(
    val accountKey: String,
    val lastPulledServerSeq: Long,
    val lastPushedLocalSeq: Long,
    val initialPushDone: Boolean,
    val baselineStage: String,
    val baselineCursorId: String,
    val remoteLastDreamAt: Long?,
    val postMergeConsolidated: Boolean,
)

object MemoryLimits {
    const val MAX_ID_LENGTH = 128
    const val MAX_CONTENT_CHARS = 8_192
    const val MAX_SUMMARY_CHARS = 2_048
    const val MAX_QUOTE_CHARS = 1_024
    const val MAX_ENTITY_NAME_CHARS = 128
    const val MAX_TAG_CHARS = 64
    const val MAX_TAGS_PER_MEMORY = 20
    const val MAX_ENTITIES_PER_MEMORY = 20
    const val MAX_ALIASES_PER_ENTITY = 32

    fun clampText(raw: String, maxChars: Int): String {
        if (maxChars <= 0) return ""
        val trimmed = raw.trim()
        if (trimmed.length <= maxChars) return trimmed
        var end = maxChars
        if (end > 0 && trimmed[end - 1].isHighSurrogate()) {
            end--
        }
        return trimmed.substring(0, end).trimEnd()
    }

    fun clampOptionalText(raw: String?, maxChars: Int): String? =
        raw?.let { clampText(it, maxChars) }?.takeIf { it.isNotEmpty() }

    fun clampOpId(raw: String): String {
        val clean = raw.trim()
        if (clean.length <= MAX_ID_LENGTH) return clean
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
        val prefixLen = MAX_ID_LENGTH - digest.length - 1
        return "${clean.take(prefixLen)}:$digest"
    }
}

sealed interface MemoryAccountSwitchCheck {
    data object Ready : MemoryAccountSwitchCheck

    data class NeedsDecision(val localMemoryCount: Int) : MemoryAccountSwitchCheck
}

enum class MemoryAccountSwitchMode {
    MERGE_ALL,
    REPLACE_WITH_REMOTE,
}

sealed interface DreamLeaseResult {
    data class Acquired(val leaseId: String? = null) : DreamLeaseResult

    data class Blocked(val reason: String) : DreamLeaseResult

    data object Offline : DreamLeaseResult

    data object Unauthorized : DreamLeaseResult

    data object Rejected : DreamLeaseResult
}

interface MemoryDreamCoordinator {
    suspend fun prepareForDream(forceManual: Boolean, minIntervalMs: Long): DreamLeaseResult

    suspend fun onDreamCompleted(leaseId: String?, finishedAt: Long)

    suspend fun onDreamFinished(leaseId: String?)
}

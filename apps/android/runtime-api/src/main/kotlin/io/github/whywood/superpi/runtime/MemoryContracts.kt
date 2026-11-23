package io.github.whywood.superpi.runtime

enum class MemoryKind(val wire: String) {
    IDENTITY("IDENTITY"),
    ACTIVE("ACTIVE"),
    HABIT("HABIT"),
    EPISODIC("EPISODIC"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryKind? = when (value?.trim()?.uppercase()) {
            "IDENTITY", "PROFILE" -> IDENTITY
            "ACTIVE", "CONTEXT", "PROSPECTIVE" -> ACTIVE
            "HABIT", "COOKBOOK", "PROCEDURAL" -> HABIT
            "EPISODIC", "DIARY", "DAILY" -> EPISODIC
            else -> null
        }
    }
}

enum class MemoryConfidence(val wire: String, val weight: Double) {
    CONFIRMED("CONFIRMED", 1.15),
    OBSERVED("OBSERVED", 1.0),
    INFERRED("INFERRED", 0.82),
    ;

    companion object {
        fun fromWire(value: String?): MemoryConfidence = when (value?.trim()?.uppercase()) {
            "CONFIRMED" -> CONFIRMED
            "INFERRED" -> INFERRED
            else -> OBSERVED
        }
    }
}

enum class MemoryItemStatus(val wire: String) {
    ACTIVE("ACTIVE"),
    SUPERSEDED("SUPERSEDED"),
    EXPIRED("EXPIRED"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryItemStatus = when (value?.trim()?.uppercase()) {
            "SUPERSEDED" -> SUPERSEDED
            "EXPIRED" -> EXPIRED
            "ARCHIVED" -> ARCHIVED
            else -> ACTIVE
        }
    }
}

enum class MemorySourceKind(val wire: String) {
    CHAT_EXPLICIT("CHAT_EXPLICIT"),
    CHAT_AUTO("CHAT_AUTO"),
    DREAM_SYNTH("DREAM_SYNTH"),
    USER_MANUAL("USER_MANUAL"),
    ;

    companion object {
        fun fromWire(value: String?): MemorySourceKind = when (value?.trim()?.uppercase()) {
            "CHAT_EXPLICIT" -> CHAT_EXPLICIT
            "DREAM_SYNTH" -> DREAM_SYNTH
            "USER_MANUAL" -> USER_MANUAL
            else -> CHAT_AUTO
        }
    }
}

enum class EntityKind(val wire: String) {
    PERSON("PERSON"),
    PROJECT("PROJECT"),
    PLACE("PLACE"),
    TOPIC("TOPIC"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromWire(value: String?): EntityKind = when (value?.trim()?.uppercase()) {
            "PERSON" -> PERSON
            "PROJECT" -> PROJECT
            "PLACE" -> PLACE
            "TOPIC" -> TOPIC
            else -> OTHER
        }
    }
}

enum class MemoryModelMode(val wire: String) {
    AUTO("AUTO"),
    FIXED("FIXED"),
    PAUSED("PAUSED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryModelMode = when (value?.trim()?.uppercase()) {
            "FIXED" -> FIXED
            "PAUSED" -> PAUSED
            else -> AUTO
        }
    }
}

data class MemorySettings(
    val mode: MemoryModelMode = MemoryModelMode.AUTO,
    val fixedProviderId: String? = null,
    val fixedModelId: String? = null,
)

data class MemoryEntity(
    val id: String,
    val canonicalName: String,
    val kind: EntityKind,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
    val updatedAt: Long = 0L,
)

data class MemoryEntityRef(
    val name: String,
    val kind: EntityKind = EntityKind.TOPIC,
    val aliases: List<String> = emptyList(),
    val summary: String? = null,
)

data class MemoryRecord(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val confidence: MemoryConfidence = MemoryConfidence.OBSERVED,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val validFrom: Long = 0L,
    val validUntil: Long? = null,
    val status: MemoryItemStatus = MemoryItemStatus.ACTIVE,
    val supersededBy: String? = null,
    val sourceKind: MemorySourceKind = MemorySourceKind.CHAT_AUTO,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
    val accessCount: Int = 0,
    val lastAccessedAt: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val revision: Long = 1L,
)

data class MemoryDraft(
    val kind: MemoryKind,
    val content: String,
    val summary: String? = null,
    val tags: List<String> = emptyList(),
    val entities: List<MemoryEntityRef> = emptyList(),
    val confidence: MemoryConfidence = MemoryConfidence.OBSERVED,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val sourceKind: MemorySourceKind = MemorySourceKind.USER_MANUAL,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
)

data class MemoryFilter(
    val query: String? = null,
    val kinds: Set<MemoryKind> = emptySet(),
    val tag: String? = null,
    val entityName: String? = null,
    val sessionId: String? = null,
    val sinceEpochMs: Long? = null,
    val untilEpochMs: Long? = null,
    val includeHistory: Boolean = false,
    val limit: Int = 50,
)

data class MemorySearchHit(
    val record: MemoryRecord,
    val score: Double,
    val matchedEntities: List<String> = emptyList(),
)

data class MemoryTagCount(
    val tag: String,
    val count: Int,
)

data class MemoryOverview(
    val totalActive: Int,
    val byKind: Map<MemoryKind, Int>,
    val entityCount: Int,
    val topTags: List<MemoryTagCount>,
    val lastDreamAt: Long?,
    val pendingIngestions: Int,
)

data class DreamRunSummary(
    val id: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: String,
    val modelRef: String?,
    val promotedCount: Int,
    val supersededCount: Int,
    val expiredCount: Int,
    val mergedEntities: Int,
    val error: String?,
)

data class MemoryExportLabels(
    val title: String,
    val exportedOn: String,
    val sectionIdentity: String,
    val sectionActive: String,
    val sectionHabit: String,
    val sectionEpisodic: String,
    val sectionEntities: String,
    val sectionHistory: String,
    val pinnedPrefix: String,
    val inferredSuffix: String,
    val statusSuperseded: String,
    val statusExpired: String,
    val statusArchived: String,
    val aliasesPrefix: String,
) {
    companion object {
        val ENGLISH = MemoryExportLabels(
            title = "SuperPI Personal Memory Backup",
            exportedOn = "Exported",
            sectionIdentity = "About Me",
            sectionActive = "Current & Follow-ups",
            sectionHabit = "Work Style",
            sectionEpisodic = "Journal",
            sectionEntities = "People & Topics",
            sectionHistory = "Superseded & Expired",
            pinnedPrefix = "[Pinned] ",
            inferredSuffix = " (inferred)",
            statusSuperseded = "Updated",
            statusExpired = "Expired",
            statusArchived = "Archived",
            aliasesPrefix = "aliases",
        )
    }
}

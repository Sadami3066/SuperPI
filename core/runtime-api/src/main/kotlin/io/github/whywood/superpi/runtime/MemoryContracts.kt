package io.github.whywood.superpi.runtime

import java.util.Locale

enum class MemoryAccessScope(val wire: String) {
    PERSONAL("PERSONAL"),
    WORK_ONLY("WORK_ONLY"),
    OFF("OFF"),
    ;

    fun allows(
        domain: String,
        sensitivity: MemorySensitivity,
    ): Boolean = when (this) {
        OFF -> false
        WORK_ONLY -> domain.trim().uppercase(Locale.ROOT) in MemoryDomains.WORK_SCOPED &&
            (sensitivity == MemorySensitivity.PUBLIC || sensitivity == MemorySensitivity.NORMAL)
        PERSONAL -> true
    }

    companion object {
        fun fromWire(value: String?, default: MemoryAccessScope = PERSONAL): MemoryAccessScope =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "PERSONAL" -> PERSONAL
                "WORK_ONLY" -> WORK_ONLY
                "OFF" -> OFF
                else -> default
            }
    }
}

enum class MemorySensitivity(val wire: String) {
    PUBLIC("PUBLIC"),
    NORMAL("NORMAL"),
    PRIVATE("PRIVATE"),
    ;

    companion object {
        fun fromWire(value: String?, default: MemorySensitivity = NORMAL): MemorySensitivity =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "PUBLIC" -> PUBLIC
                "NORMAL" -> NORMAL
                "PRIVATE", "RESTRICTED" -> PRIVATE
                else -> default
            }
    }
}

enum class MemoryBlockOwner(val wire: String) {
    BUILTIN("BUILTIN"),
    USER("USER"),
    AGENT("AGENT"),
    PLUGIN("PLUGIN"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryBlockOwner =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "BUILTIN" -> BUILTIN
                "AGENT" -> AGENT
                "PLUGIN" -> PLUGIN
                else -> USER
            }
    }
}

enum class MemoryBlockInjectionMode(val wire: String) {
    ALWAYS("ALWAYS"),
    ON_DEMAND("ON_DEMAND"),
    NEVER("NEVER"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryBlockInjectionMode =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "ON_DEMAND" -> ON_DEMAND
                "NEVER" -> NEVER
                else -> ALWAYS
            }
    }
}

enum class MemoryBlockStatus(val wire: String) {
    ACTIVE("ACTIVE"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryBlockStatus =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "ARCHIVED" -> ARCHIVED
                else -> ACTIVE
            }
    }
}

enum class MemoryProposalStatus(val wire: String) {
    PENDING("PENDING"),
    ACCEPTED("ACCEPTED"),
    DISMISSED("DISMISSED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryProposalStatus =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "ACCEPTED" -> ACCEPTED
                "DISMISSED" -> DISMISSED
                else -> PENDING
            }
    }
}

enum class MemoryEventKind(val wire: String) {
    FACT("FACT"),
    PREFERENCE("PREFERENCE"),
    COMMITMENT("COMMITMENT"),
    EPISODE("EPISODE"),
    GOAL("GOAL"),
    BOUNDARY("BOUNDARY"),
    NOTE("NOTE"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryEventKind? =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "FACT", "IDENTITY", "PROFILE" -> FACT
                "PREFERENCE", "HABIT", "COOKBOOK", "PROCEDURAL" -> PREFERENCE
                "COMMITMENT", "ACTIVE", "CONTEXT", "PROSPECTIVE" -> COMMITMENT
                "EPISODE", "EPISODIC", "DIARY", "DAILY" -> EPISODE
                "GOAL" -> GOAL
                "BOUNDARY" -> BOUNDARY
                "NOTE" -> NOTE
                else -> null
            }
    }
}

enum class MemoryEpistemicTier(val wire: String, val rank: Int, val retrievalWeight: Double) {
    USER_PINNED("USER_PINNED", 4, 1.25),
    USER_EXPLICIT("USER_EXPLICIT", 3, 1.15),
    OBSERVED("OBSERVED", 2, 1.0),
    INFERRED("INFERRED", 1, 0.82),
    ;

    companion object {
        fun fromWire(value: String?): MemoryEpistemicTier =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "USER_PINNED", "PINNED" -> USER_PINNED
                "USER_EXPLICIT", "CONFIRMED" -> USER_EXPLICIT
                "INFERRED" -> INFERRED
                else -> OBSERVED
            }
    }
}

enum class MemoryEventTimePrecision(val wire: String) {
    EXACT("EXACT"),
    DAY("DAY"),
    MONTH("MONTH"),
    YEAR("YEAR"),
    APPROX("APPROX"),
    TIMELESS("TIMELESS"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryEventTimePrecision =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "EXACT" -> EXACT
                "MONTH" -> MONTH
                "YEAR" -> YEAR
                "APPROX" -> APPROX
                "TIMELESS" -> TIMELESS
                else -> DAY
            }
    }
}

enum class MemoryItemStatus(val wire: String) {
    ACTIVE("ACTIVE"),
    SUPERSEDED("SUPERSEDED"),
    RESOLVED("RESOLVED"),
    EXPIRED("EXPIRED"),
    ARCHIVED("ARCHIVED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryItemStatus =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "SUPERSEDED" -> SUPERSEDED
                "RESOLVED" -> RESOLVED
                "EXPIRED" -> EXPIRED
                "ARCHIVED" -> ARCHIVED
                else -> ACTIVE
            }
    }
}

enum class MemorySourceKind(val wire: String) {
    USER_MANUAL("USER_MANUAL"),
    AGENT_DISPATCH("AGENT_DISPATCH"),
    AGENT_AUTO("AGENT_AUTO"),
    SLEEP_CONSOLIDATION("SLEEP_CONSOLIDATION"),
    MIGRATION_V2("MIGRATION_V2"),
    ;

    companion object {
        fun fromWire(value: String?): MemorySourceKind =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "USER_MANUAL" -> USER_MANUAL
                "AGENT_DISPATCH", "CHAT_EXPLICIT" -> AGENT_DISPATCH
                "SLEEP_CONSOLIDATION", "DREAM_SYNTH" -> SLEEP_CONSOLIDATION
                "MIGRATION_V2" -> MIGRATION_V2
                else -> AGENT_AUTO
            }
    }
}

enum class MemoryDispatchIntent(val wire: String) {
    REMEMBER("REMEMBER"),
    UPDATE("UPDATE"),
    FORGET("FORGET"),
    ORGANIZE("ORGANIZE"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryDispatchIntent =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "UPDATE" -> UPDATE
                "FORGET" -> FORGET
                "ORGANIZE" -> ORGANIZE
                else -> REMEMBER
            }
    }
}

enum class MemoryMutationActor(val wire: String) {
    MEMORY_AGENT_HOT("MEMORY_AGENT_HOT"),
    MEMORY_AGENT_COLD("MEMORY_AGENT_COLD"),
    MEMORY_AGENT_MIGRATION("MEMORY_AGENT_MIGRATION"),
    USER("USER"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryMutationActor =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "MEMORY_AGENT_COLD" -> MEMORY_AGENT_COLD
                "MEMORY_AGENT_MIGRATION" -> MEMORY_AGENT_MIGRATION
                "USER" -> USER
                else -> MEMORY_AGENT_HOT
            }
    }
}

enum class MemoryModelMode(val wire: String) {
    AUTO("AUTO"),
    FIXED("FIXED"),
    PAUSED("PAUSED"),
    ;

    companion object {
        fun fromWire(value: String?): MemoryModelMode =
            when (value?.trim()?.uppercase(Locale.ROOT)) {
                "FIXED" -> FIXED
                "PAUSED" -> PAUSED
                else -> AUTO
            }
    }
}

object MemoryDomains {
    const val PERSONAL = "PERSONAL"
    const val RELATIONSHIPS = "RELATIONSHIPS"
    const val LIFE = "LIFE"
    const val HEALTH = "HEALTH"
    const val WORK = "WORK"
    const val STUDY = "STUDY"
    const val CUSTOM = "CUSTOM"

    val WORK_SCOPED: Set<String> = setOf(WORK, STUDY)

    fun normalize(raw: String?, fallback: String = PERSONAL): String {
        val clean = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return if (clean.isEmpty()) fallback else clean.take(48)
    }
}

object MemoryEntityTypes {
    const val PERSON = "PERSON"
    const val FAMILY = "FAMILY"
    const val FRIEND = "FRIEND"
    const val COLLEAGUE = "COLLEAGUE"
    const val PLACE = "PLACE"
    const val ORGANIZATION = "ORGANIZATION"
    const val PROJECT = "PROJECT"
    const val PET = "PET"
    const val TOPIC = "TOPIC"
    const val OTHER = "OTHER"

    fun normalize(raw: String?, fallback: String = TOPIC): String {
        val clean = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return if (clean.isEmpty()) fallback else clean.take(48)
    }
}

object MemoryRelationTypes {
    const val FAMILY_OF = "family_of"
    const val PARTNER_OF = "partner_of"
    const val FRIEND_OF = "friend_of"
    const val COLLEAGUE_OF = "colleague_of"
    const val LIVES_IN = "lives_in"
    const val WORKS_AT = "works_at"
    const val STUDIES_AT = "studies_at"
    const val PREFERS = "prefers"
    const val DISLIKES = "dislikes"
    const val ALLERGIC_TO = "allergic_to"
    const val OWNS = "owns"
    const val LOCATED_IN = "located_in"
    const val RELATED_TO = "related_to"

    fun normalize(raw: String?, fallback: String = RELATED_TO): String {
        val clean = raw?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_').orEmpty()
        return if (clean.isEmpty()) fallback else clean.take(64)
    }
}

data class MemoryInjectionBudget(
    val totalMaxChars: Int = MemorySettings.DEFAULT_INJECTION_MAX_CHARS,
    val profileMaxChars: Int? = null,
    val relationsMaxChars: Int? = null,
    val eventsMaxChars: Int? = null,
    val directoryMaxChars: Int? = null,
) {
    companion object {
        const val DEFAULT_PERSONAL_PROFILE_CHARS = 10_500
        const val DEFAULT_PERSONAL_RELATIONS_CHARS = 2_500
        const val DEFAULT_PERSONAL_EVENTS_CHARS = 1_800
        const val DEFAULT_PERSONAL_DIRECTORY_CHARS = 1_200
        const val DEFAULT_WORK_PROFILE_CHARS = 8_000
        const val DEFAULT_WORK_RELATIONS_CHARS = 2_000
        const val DEFAULT_WORK_EVENTS_CHARS = 2_000
        const val MIN_AUTO_PROFILE_CHARS = 1_500
        const val MIN_AUTO_RELATIONS_CHARS = 500
        const val MIN_AUTO_EVENTS_CHARS = 500
        const val MIN_AUTO_DIRECTORY_CHARS = 300

        fun autoSectionChars(baseChars: Int, totalMaxChars: Int, minChars: Int): Int {
            if (totalMaxChars <= 0) return MemorySettings.INJECTION_UNLIMITED
            val scale = totalMaxChars.toDouble() / MemorySettings.DEFAULT_INJECTION_MAX_CHARS.toDouble()
            return ((baseChars * scale) + 0.5).toInt().coerceAtLeast(minChars)
        }
    }
}

data class MemorySettings(
    val mode: MemoryModelMode = MemoryModelMode.AUTO,
    val fixedProviderId: String? = null,
    val fixedModelId: String? = null,
    val hotProviderId: String? = null,
    val hotModelId: String? = null,
    val coldProviderId: String? = null,
    val coldModelId: String? = null,
    val hotIdleDebounceSeconds: Int = DEFAULT_HOT_IDLE_DEBOUNCE_SECONDS,
    val coldConsolidationHours: Int = DEFAULT_COLD_CONSOLIDATION_HOURS,
    val injectionMaxChars: Int = DEFAULT_INJECTION_MAX_CHARS,
    val profileBudgetChars: Int? = null,
    val relationsBudgetChars: Int? = null,
    val eventsBudgetChars: Int? = null,
    val directoryBudgetChars: Int? = null,
) {
    fun effectiveHotProviderId(): String? = hotProviderId ?: fixedProviderId

    fun effectiveHotModelId(): String? = hotModelId ?: fixedModelId

    fun effectiveColdProviderId(): String? = coldProviderId ?: fixedProviderId

    fun effectiveColdModelId(): String? = coldModelId ?: fixedModelId

    fun injectionBudget(): MemoryInjectionBudget = MemoryInjectionBudget(
        totalMaxChars = injectionMaxChars,
        profileMaxChars = profileBudgetChars,
        relationsMaxChars = relationsBudgetChars,
        eventsMaxChars = eventsBudgetChars,
        directoryMaxChars = directoryBudgetChars,
    )

    fun hasCustomSectionBudgets(): Boolean =
        profileBudgetChars != null ||
            relationsBudgetChars != null ||
            eventsBudgetChars != null ||
            directoryBudgetChars != null

    companion object {
        const val DEFAULT_HOT_IDLE_DEBOUNCE_SECONDS = 60
        const val DEFAULT_COLD_CONSOLIDATION_HOURS = 12
        const val DEFAULT_INJECTION_MAX_CHARS = 16_000
        const val INJECTION_MIN_CHARS = 4_000
        const val INJECTION_MAX_CHARS = 64_000
        const val INJECTION_STEP_CHARS = 4_000
        const val INJECTION_UNLIMITED = 0
        const val SECTION_DISABLED = -1
        const val SECTION_MIN_CHARS = 500
        const val SECTION_MAX_CHARS = 64_000

        fun normalizeInjectionMaxChars(value: Int): Int {
            if (value <= 0) return INJECTION_UNLIMITED
            val clamped = value.coerceIn(INJECTION_MIN_CHARS, INJECTION_MAX_CHARS)
            val steps = (clamped + INJECTION_STEP_CHARS / 2) / INJECTION_STEP_CHARS
            return (steps * INJECTION_STEP_CHARS).coerceIn(INJECTION_MIN_CHARS, INJECTION_MAX_CHARS)
        }

        fun normalizeSectionBudgetChars(value: Int?): Int? {
            if (value == null) return null
            if (value <= SECTION_DISABLED) return SECTION_DISABLED
            if (value == INJECTION_UNLIMITED) return INJECTION_UNLIMITED
            return value.coerceIn(SECTION_MIN_CHARS, SECTION_MAX_CHARS)
        }
    }
}

data class MemoryBlock(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val schemaHint: String? = null,
    val content: String = "",
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val injectionMode: MemoryBlockInjectionMode = MemoryBlockInjectionMode.ALWAYS,
    val maxChars: Int = 2_000,
    val owner: MemoryBlockOwner = MemoryBlockOwner.BUILTIN,
    val pinned: Boolean = false,
    val status: MemoryBlockStatus = MemoryBlockStatus.ACTIVE,
    val sortOrder: Int = 100,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val revision: Long = 1L,
)

data class MemoryBlockDraft(
    val slug: String,
    val title: String,
    val purpose: String,
    val schemaHint: String? = null,
    val content: String = "",
    val domain: String = MemoryDomains.CUSTOM,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val injectionMode: MemoryBlockInjectionMode = MemoryBlockInjectionMode.ALWAYS,
    val maxChars: Int = 2_000,
    val owner: MemoryBlockOwner = MemoryBlockOwner.USER,
    val pinned: Boolean = false,
    val sortOrder: Int = 100,
)

data class MemoryBlockProposal(
    val id: String,
    val slug: String,
    val title: String,
    val purpose: String,
    val domain: String,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val seedContent: String,
    val supportingEventIds: List<String> = emptyList(),
    val reason: String,
    val status: MemoryProposalStatus = MemoryProposalStatus.PENDING,
    val createdAt: Long = 0L,
    val resolvedAt: Long? = null,
)

data class MemoryEntity(
    val id: String,
    val canonicalName: String,
    val entityType: String = MemoryEntityTypes.TOPIC,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val summary: String? = null,
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val revision: Long = 1L,
)

data class MemoryEntityRef(
    val name: String,
    val entityType: String = MemoryEntityTypes.TOPIC,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val summary: String? = null,
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val role: String = "MENTIONED",
)

data class MemoryEntityRelation(
    val id: String,
    val fromEntityId: String,
    val fromEntityName: String,
    val relationType: String,
    val toEntityId: String,
    val toEntityName: String,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val validFrom: Long = 0L,
    val validUntil: Long? = null,
    val invalidatedAt: Long? = null,
    val invalidatedBy: String? = null,
    val epistemicTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val sourceEventId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val revision: Long = 1L,
) {
    val isActive: Boolean get() = invalidatedAt == null
}

data class MemoryEvent(
    val id: String,
    val eventKind: MemoryEventKind,
    val content: String,
    val summary: String? = null,
    val qualifiers: Map<String, String> = emptyMap(),
    val semanticKeywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val entityNames: List<String> = emptyList(),
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val epistemicTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val eventTime: Long = 0L,
    val eventTimePrecision: MemoryEventTimePrecision = MemoryEventTimePrecision.DAY,
    val validFrom: Long = 0L,
    val validUntil: Long? = null,
    val status: MemoryItemStatus = MemoryItemStatus.ACTIVE,
    val supersededBy: String? = null,
    val sourceKind: MemorySourceKind = MemorySourceKind.AGENT_AUTO,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
    val accessCount: Int = 0,
    val lastAccessedAt: Long? = null,
    val activationScore: Double = 1.0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val revision: Long = 1L,
)

data class MemoryEventDraft(
    val eventKind: MemoryEventKind = MemoryEventKind.FACT,
    val content: String,
    val summary: String? = null,
    val qualifiers: Map<String, String> = emptyMap(),
    val semanticKeywords: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val entities: List<MemoryEntityRef> = emptyList(),
    val domain: String = MemoryDomains.PERSONAL,
    val sensitivity: MemorySensitivity = MemorySensitivity.NORMAL,
    val epistemicTier: MemoryEpistemicTier = MemoryEpistemicTier.OBSERVED,
    val importance: Int = 3,
    val pinned: Boolean = false,
    val eventTime: Long? = null,
    val eventTimePrecision: MemoryEventTimePrecision = MemoryEventTimePrecision.DAY,
    val validFrom: Long? = null,
    val validUntil: Long? = null,
    val sourceKind: MemorySourceKind = MemorySourceKind.USER_MANUAL,
    val sourceSessionId: String? = null,
    val sourceEntryId: String? = null,
    val sourceQuote: String? = null,
)

data class MemoryEventFilter(
    val query: String? = null,
    val eventKinds: Set<MemoryEventKind> = emptySet(),
    val domains: Set<String> = emptySet(),
    val tag: String? = null,
    val entityName: String? = null,
    val sessionId: String? = null,
    val sinceEventTimeMs: Long? = null,
    val untilEventTimeMs: Long? = null,
    val includeHistory: Boolean = false,
    val scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
    val limit: Int = 50,
)

data class MemorySearchHit(
    val event: MemoryEvent,
    val score: Double,
    val matchedEntities: List<String> = emptyList(),
    val matchedViaRelation: Boolean = false,
)

data class MemoryEntityDetail(
    val entity: MemoryEntity,
    val outgoingRelations: List<MemoryEntityRelation> = emptyList(),
    val incomingRelations: List<MemoryEntityRelation> = emptyList(),
    val linkedEvents: List<MemoryEvent> = emptyList(),
)

data class MemoryAuditEntry(
    val id: String,
    val runId: String? = null,
    val actor: MemoryMutationActor,
    val actionType: String,
    val targetTable: String,
    val targetId: String,
    val summaryUserText: String,
    val reason: String? = null,
    val revertedAt: Long? = null,
    val createdAt: Long = 0L,
    val revertible: Boolean = true,
) {
    val isReverted: Boolean get() = revertedAt != null

    val canRevert: Boolean get() = revertible && revertedAt == null
}

data class MemoryAgentRunSummary(
    val id: String,
    val mode: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: String,
    val modelRef: String? = null,
    val stepsUsed: Int = 0,
    val blocksPatched: Int = 0,
    val eventsRecorded: Int = 0,
    val eventsSuperseded: Int = 0,
    val eventsForgotten: Int = 0,
    val entitiesMerged: Int = 0,
    val proposalsCreated: Int = 0,
    val error: String? = null,
)

data class MemoryTagCount(
    val tag: String,
    val count: Int,
)

data class MemoryOverview(
    val activeBlocks: Int,
    val archivedBlocks: Int,
    val activeEvents: Int,
    val entityCount: Int,
    val relationCount: Int,
    val pendingProposals: Int,
    val pendingDispatchJobs: Int,
    val lastConsolidationAt: Long? = null,
)

data class MemoryExportLabels(
    val title: String,
    val exportedOn: String,
    val sectionProfiles: String,
    val sectionArchivedProfiles: String,
    val sectionRelationships: String,
    val sectionTimeline: String,
    val sectionHistory: String,
    val pinnedPrefix: String,
    val inferredSuffix: String,
    val statusSuperseded: String,
    val statusResolved: String,
    val statusExpired: String,
    val statusArchived: String,
    val aliasesPrefix: String,
) {
    companion object {
        val ENGLISH = MemoryExportLabels(
            title = "SuperPI Personal Memory Archive",
            exportedOn = "Exported",
            sectionProfiles = "Living Profiles",
            sectionArchivedProfiles = "Archived Profiles",
            sectionRelationships = "People & Relationships",
            sectionTimeline = "Active Timeline",
            sectionHistory = "History & Resolved",
            pinnedPrefix = "[Pinned] ",
            inferredSuffix = " (inferred)",
            statusSuperseded = "Updated",
            statusResolved = "Resolved",
            statusExpired = "Expired",
            statusArchived = "Archived",
            aliasesPrefix = "aliases",
        )
    }
}

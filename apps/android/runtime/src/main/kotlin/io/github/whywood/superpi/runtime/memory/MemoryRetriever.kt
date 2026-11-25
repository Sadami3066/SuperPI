package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemorySearchHit
import java.util.Locale
import kotlin.math.abs

interface MemoryVectorChannel {
    suspend fun scoreCandidates(
        queries: List<String>,
        candidateIds: List<String>,
    ): Map<String, Double> = emptyMap()

    companion object {
        val NOOP: MemoryVectorChannel = object : MemoryVectorChannel {}
    }
}

internal object LocalGraphRetriever {
    private const val DIRECT_ENTITY_BONUS = 0.40
    private const val ONE_HOP_ENTITY_BONUS = 0.25
    private const val TWO_HOP_ENTITY_BONUS = 0.12
    private const val EXACT_SUBSTRING_BONUS = 0.45
    private const val VECTOR_WEIGHT = 0.50
    private const val NEAR_DUPLICATE_JACCARD = 0.78

    private data class CandidateAccumulator(
        val event: MemoryEvent,
        var lexicalScore: Double = 0.0,
        var graphBonus: Double = 0.0,
        var viaRelation: Boolean = false,
        val matchedEntities: LinkedHashSet<String> = LinkedHashSet(),
    )

    suspend fun search(
        database: MemoryDatabase,
        queries: List<String>,
        filter: MemoryEventFilter,
        nowMs: Long,
        recordAccess: Boolean,
        vectorChannel: MemoryVectorChannel = MemoryVectorChannel.NOOP,
    ): List<MemorySearchHit> {
        if (filter.scope == MemoryAccessScope.OFF) return emptyList()
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val limit = filter.limit.coerceIn(1, 25)
        if (cleanQueries.isEmpty()) {
            return database.read { conn ->
                MemoryStorage.listEvents(conn, filter.copy(limit = limit))
                    .map { MemorySearchHit(event = it, score = 1.0) }
            }
        }

        val candidates = database.read { conn ->
            collectCandidates(conn, cleanQueries, filter, limit)
        }
        if (candidates.isEmpty()) return emptyList()

        val vectorScores = if (vectorChannel === MemoryVectorChannel.NOOP) {
            emptyMap()
        } else {
            runCatching {
                vectorChannel.scoreCandidates(cleanQueries, candidates.keys.toList())
            }.getOrDefault(emptyMap())
        }

        val scored = candidates.values.map { acc ->
            val vectorBoost = (vectorScores[acc.event.id] ?: 0.0).coerceAtLeast(0.0) * VECTOR_WEIGHT
            val baseSignal = maxOf(0.1, acc.lexicalScore + acc.graphBonus + vectorBoost)
            val finalScore = baseSignal *
                recencyFactor(acc.event, nowMs) *
                acc.event.epistemicTier.retrievalWeight *
                importancePinFactor(acc.event) *
                statusFactor(acc.event.status)
            MemorySearchHit(
                event = acc.event,
                score = finalScore,
                matchedEntities = acc.matchedEntities.toList(),
                matchedViaRelation = acc.viaRelation && acc.graphBonus > 0.0,
            )
        }.sortedWith(
            compareByDescending<MemorySearchHit> { it.score }
                .thenByDescending { it.event.pinned }
                .thenByDescending { it.event.eventTime },
        )

        val deduped = collapseNearDuplicates(scored, limit)
        if (recordAccess && deduped.isNotEmpty()) {
            database.writeTransaction { conn ->
                MemoryStorage.recordAccessHits(conn, deduped.map { it.event.id }, nowMs)
            }
        }
        return deduped
    }

    private fun collectCandidates(
        conn: SQLiteConnection,
        cleanQueries: List<String>,
        filter: MemoryEventFilter,
        limit: Int,
    ): LinkedHashMap<String, CandidateAccumulator> {
        val candidates = LinkedHashMap<String, CandidateAccumulator>()
        val directMatched = matchEntities(conn, cleanQueries, filter.scope)
        val activatedEntities = spreadGraphActivation(conn, directMatched, filter.scope)

        val entityLookup = MemoryGraphStorage.listEntities(conn, scope = filter.scope).associateBy { it.id }
        val resolvedEntityName = filter.entityName?.trim()?.takeIf { it.isNotEmpty() }?.let { rawName ->
            MemoryGraphStorage.findEntityByNameOrAlias(conn, rawName)?.canonicalName ?: rawName
        }
        val extraTerms = ArrayList<String>()
        activatedEntities.keys.forEach { entityId ->
            entityLookup[entityId]?.let { entity ->
                extraTerms += entity.canonicalName
                extraTerms.addAll(entity.aliases.take(4))
            }
        }

        val matchClause = MemoryTokenizer.toFtsMatchClause(cleanQueries, extraTerms)
        if (matchClause != null) {
            val candidatePool = (limit * 4).coerceIn(20, 200)
            MemoryStorage.ftsSearchCandidates(conn, matchClause, filter, candidatePool).forEach { (event, rawBm25) ->
                if (!matchesNonTextFilters(event, filter, resolvedEntityName)) return@forEach
                val acc = candidates.getOrPut(event.id) { CandidateAccumulator(event = event) }
                val magnitude = abs(rawBm25)
                acc.lexicalScore = maxOf(acc.lexicalScore, (magnitude / (1.0 + magnitude)) + 0.15)
            }
        }

        activatedEntities.forEach { (entityId, activation) ->
            val entityName = entityLookup[entityId]?.canonicalName
            val linked = MemoryStorage.listEventsForEntity(
                conn = conn,
                entityId = entityId,
                scope = filter.scope,
                includeHistory = filter.includeHistory,
                limit = 80,
            )
            linked.forEach { event ->
                if (!matchesNonTextFilters(event, filter, resolvedEntityName)) return@forEach
                val acc = candidates.getOrPut(event.id) { CandidateAccumulator(event = event) }
                if (activation.bonus > acc.graphBonus) {
                    acc.graphBonus = activation.bonus
                }
                if (activation.hop > 0) acc.viaRelation = true
                if (entityName != null) acc.matchedEntities += entityName
            }
        }

        if (candidates.isEmpty()) {
            MemoryStorage.listEvents(conn, filter.copy(limit = 200)).forEach { event ->
                if (containsExactOrSemanticMatch(event, cleanQueries)) {
                    candidates[event.id] = CandidateAccumulator(
                        event = event,
                        lexicalScore = EXACT_SUBSTRING_BONUS,
                    )
                }
            }
        } else {
            candidates.values.forEach { acc ->
                if (containsExactOrSemanticMatch(acc.event, cleanQueries)) {
                    acc.lexicalScore += EXACT_SUBSTRING_BONUS
                }
            }
        }
        return candidates
    }

    private data class EntityActivation(
        val hop: Int,
        val bonus: Double,
    )

    private fun matchEntities(
        conn: SQLiteConnection,
        queries: List<String>,
        scope: MemoryAccessScope,
    ): List<MemoryEntity> {
        val entities = MemoryGraphStorage.listEntities(conn, scope = scope)
        if (entities.isEmpty()) return emptyList()
        val loweredQueries = queries.map { it.lowercase(Locale.ROOT) }
        val queryTokenSet = queries.flatMap { MemoryTokenizer.queryTokens(it) }.toSet()

        return entities.filter { entity ->
            val terms = (listOf(entity.canonicalName) + entity.aliases)
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
            terms.any { term ->
                val isCjk = term.any { MemoryTokenizer.isNoSpaceScript(it) }
                if (isCjk) {
                    term.length >= 2 && (loweredQueries.any { it.contains(term) || term.contains(it) } || term in queryTokenSet)
                } else {
                    term.length >= 2 && (
                        term in queryTokenSet ||
                            loweredQueries.any { q -> q == term || Regex("\\b${Regex.escape(term)}\\b").containsMatchIn(q) }
                        )
                }
            }
        }
    }

    private fun spreadGraphActivation(
        conn: SQLiteConnection,
        seeds: List<MemoryEntity>,
        scope: MemoryAccessScope,
    ): Map<String, EntityActivation> {
        val activated = LinkedHashMap<String, EntityActivation>()
        if (seeds.isEmpty()) return activated

        seeds.forEach { activated[it.id] = EntityActivation(hop = 0, bonus = DIRECT_ENTITY_BONUS) }

        val activeRelations = MemoryGraphStorage.listActiveRelations(conn, scope = scope)
        val neighborsByEntity = HashMap<String, MutableList<String>>()
        activeRelations.forEach { rel: MemoryEntityRelation ->
            neighborsByEntity.getOrPut(rel.fromEntityId) { ArrayList() }.add(rel.toEntityId)
            neighborsByEntity.getOrPut(rel.toEntityId) { ArrayList() }.add(rel.fromEntityId)
        }

        val frontier1 = ArrayList<String>()
        seeds.forEach { seed ->
            neighborsByEntity[seed.id].orEmpty().forEach { neighborId ->
                val existing = activated[neighborId]
                if (existing == null || ONE_HOP_ENTITY_BONUS > existing.bonus) {
                    activated[neighborId] = EntityActivation(hop = 1, bonus = ONE_HOP_ENTITY_BONUS)
                    frontier1 += neighborId
                }
            }
        }

        frontier1.distinct().forEach { hop1Id ->
            neighborsByEntity[hop1Id].orEmpty().forEach { hop2Id ->
                if (hop2Id !in activated) {
                    activated[hop2Id] = EntityActivation(hop = 2, bonus = TWO_HOP_ENTITY_BONUS)
                }
            }
        }

        return activated
    }

    private fun matchesNonTextFilters(
        event: MemoryEvent,
        filter: MemoryEventFilter,
        resolvedEntityName: String? = null,
    ): Boolean {
        if (filter.eventKinds.isNotEmpty() && event.eventKind !in filter.eventKinds) return false
        if (filter.domains.isNotEmpty() && event.domain !in filter.domains) return false
        val wantedTag = filter.tag?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }
        if (wantedTag != null) {
            if (event.tags.none { it.equals(wantedTag, ignoreCase = true) }) return false
        }
        val wantedEntity = resolvedEntityName ?: filter.entityName?.trim()?.takeIf { it.isNotEmpty() }
        if (wantedEntity != null) {
            if (event.entityNames.none { it.equals(wantedEntity, ignoreCase = true) }) return false
        }
        filter.sinceEventTimeMs?.let { if (event.eventTime < it) return false }
        filter.untilEventTimeMs?.let { if (event.eventTime > it) return false }
        filter.sessionId?.takeIf { it.isNotBlank() }?.let { if (event.sourceSessionId != it) return false }
        return true
    }

    private fun containsExactOrSemanticMatch(event: MemoryEvent, queries: List<String>): Boolean {
        val haystack = buildString {
            append(event.content.lowercase(Locale.ROOT))
            event.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { append(' ').append(it.lowercase(Locale.ROOT)) }
            if (event.semanticKeywords.isNotEmpty()) append(' ').append(event.semanticKeywords.joinToString(" ").lowercase(Locale.ROOT))
            if (event.tags.isNotEmpty()) append(' ').append(event.tags.joinToString(" ").lowercase(Locale.ROOT))
        }
        return queries.any { q -> haystack.contains(q.lowercase(Locale.ROOT)) }
    }

    private fun recencyFactor(event: MemoryEvent, nowMs: Long): Double {
        val ageDays = ((nowMs - event.eventTime).coerceAtLeast(0L)).toDouble() / MemoryLimits.MS_PER_DAY.toDouble()
        return when (event.eventKind) {
            MemoryEventKind.FACT, MemoryEventKind.PREFERENCE, MemoryEventKind.BOUNDARY ->
                (1.0 / (1.0 + ageDays / 365.0)).coerceAtLeast(0.85)
            MemoryEventKind.COMMITMENT, MemoryEventKind.GOAL -> {
                val base = (1.0 / (1.0 + ageDays / 90.0)).coerceAtLeast(0.75)
                val validUntilMs = event.validUntil
                if (validUntilMs != null && validUntilMs < nowMs) base * 0.6 else base
            }
            MemoryEventKind.EPISODE, MemoryEventKind.NOTE ->
                (1.0 / (1.0 + ageDays / 60.0)).coerceAtLeast(0.25)
        }
    }

    private fun importancePinFactor(event: MemoryEvent): Double {
        val importanceDelta = (event.importance.coerceIn(1, 5) - 3) * 0.06
        val pinBonus = if (event.pinned) 0.15 else 0.0
        val activationBonus = ((event.activationScore - 1.0) * 0.04).coerceIn(0.0, 0.16)
        return 1.0 + importanceDelta + pinBonus + activationBonus
    }

    private fun statusFactor(status: MemoryItemStatus): Double = when (status) {
        MemoryItemStatus.ACTIVE -> 1.0
        MemoryItemStatus.RESOLVED -> 0.78
        MemoryItemStatus.SUPERSEDED, MemoryItemStatus.EXPIRED, MemoryItemStatus.ARCHIVED -> 0.70
    }

    private fun collapseNearDuplicates(hits: List<MemorySearchHit>, limit: Int): List<MemorySearchHit> {
        val kept = ArrayList<MemorySearchHit>(limit)
        for (candidate in hits) {
            val duplicate = kept.any { existing ->
                existing.event.eventKind == candidate.event.eventKind &&
                    MemoryTokenizer.bigramJaccard(existing.event.content, candidate.event.content) >= NEAR_DUPLICATE_JACCARD
            }
            if (!duplicate) {
                kept += candidate
                if (kept.size >= limit) break
            }
        }
        return kept
    }
}

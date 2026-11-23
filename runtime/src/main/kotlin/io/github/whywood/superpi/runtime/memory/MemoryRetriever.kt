package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySearchHit
import java.util.Locale

internal interface MemoryVectorChannel {
    suspend fun score(queries: List<String>, candidateIds: Set<String>?): Map<String, Double>
}

internal object NoopVectorChannel : MemoryVectorChannel {
    override suspend fun score(queries: List<String>, candidateIds: Set<String>?): Map<String, Double> = emptyMap()
}

internal interface MemoryRetriever {
    suspend fun search(queries: List<String>, filter: MemoryFilter): List<MemorySearchHit>
}

internal class FtsEntityRetriever(
    private val db: MemoryDatabase,
    private val vectorChannel: MemoryVectorChannel = NoopVectorChannel,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryRetriever {
    override suspend fun search(queries: List<String>, filter: MemoryFilter): List<MemorySearchHit> {
        val cleanQueries = queries.map { it.trim() }.filter { it.isNotEmpty() }
        val limit = filter.limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val nowMs = clock()
        if (cleanQueries.isEmpty()) {
            val browsed = db.read { conn ->
                MemoryStorage.queryRecords(conn, filter.copy(limit = limit))
            }
            return browsed.map { MemorySearchHit(record = it, score = 1.0) }
        }

        val initialCandidates = db.read { conn ->
            gatherCandidates(conn, cleanQueries, filter, limit)
        }
        if (initialCandidates.records.isEmpty()) return emptyList()

        val vectorScores = runCatching {
            vectorChannel.score(cleanQueries, initialCandidates.records.keys)
        }.getOrDefault(emptyMap())

        val allowedEntityNames = initialCandidates.allowedEntityNames
        val scored = initialCandidates.records.values
            .filter { matchesExtraFilter(it, filter, allowedEntityNames) }
            .map { record ->
                val lexical = initialCandidates.lexicalScores[record.id] ?: 0.0
                val entityHitNames = initialCandidates.matchedEntitiesByMemory[record.id].orEmpty()
                val entityBonus = if (entityHitNames.isNotEmpty()) ENTITY_LINK_BONUS else 0.0
                val vectorBoost = (vectorScores[record.id] ?: 0.0).coerceAtLeast(0.0) * VECTOR_WEIGHT
                val baseScore = (lexical + entityBonus + vectorBoost).coerceAtLeast(MIN_BASE_SCORE)
                val finalScore = baseScore *
                    recencyFactor(record, nowMs) *
                    record.confidence.weight *
                    importanceFactor(record) *
                    historyFactor(record)
                MemorySearchHit(
                    record = record,
                    score = finalScore,
                    matchedEntities = entityHitNames.distinct(),
                )
            }
            .sortedWith(
                compareByDescending<MemorySearchHit> { it.score }
                    .thenByDescending { it.record.updatedAt },
            )

        val deduplicated = collapseNearDuplicates(scored).take(limit)
        if (deduplicated.isNotEmpty()) {
            db.writeTransaction { conn ->
                MemoryStorage.incrementAccess(conn, deduplicated.map { it.record.id }, nowMs)
            }
        }
        return deduplicated
    }

    private data class CandidatePool(
        val records: Map<String, MemoryRecord>,
        val lexicalScores: Map<String, Double>,
        val matchedEntitiesByMemory: Map<String, MutableList<String>>,
        val allowedEntityNames: Set<String>?,
    )

    private fun gatherCandidates(
        conn: SQLiteConnection,
        queries: List<String>,
        filter: MemoryFilter,
        limit: Int,
    ): CandidatePool {
        val entities = MemoryStorage.listActiveEntities(conn)
        val matchedEntities = resolveMatchedEntities(queries, filter.entityName, entities)
        val explicitEntity = filter.entityName?.trim()?.takeIf { it.isNotEmpty() }
        val allowedEntityNames = explicitEntity?.let { raw ->
            val resolved = resolveMatchedEntities(emptyList(), raw, entities)
            buildSet {
                add(raw.lowercase(Locale.ROOT))
                resolved.forEach { entity ->
                    add(entity.canonicalName.lowercase(Locale.ROOT))
                    entity.aliases.forEach { alias -> add(alias.lowercase(Locale.ROOT)) }
                }
            }
        }
        val extraAliasTerms = matchedEntities.flatMap { entity ->
            listOf(entity.canonicalName) + entity.aliases
        }
        val ftsClause = MemoryTokenizer.toFtsMatchClause(queries, extraAliasTerms)

        val lexicalScores = HashMap<String, Double>()
        val matchedByMemory = HashMap<String, MutableList<String>>()
        val candidateIds = LinkedHashSet<String>()
        val candidateLimit = (limit * CANDIDATE_MULTIPLIER).coerceIn(20, 200)

        if (ftsClause != null) {
            val statusClause = if (filter.includeHistory) "" else " AND m.status = 'ACTIVE'"
            val kindClause = if (filter.kinds.isEmpty()) {
                ""
            } else {
                " AND m.kind IN (${filter.kinds.joinToString(",") { "?" }})"
            }
            conn.prepare(
                """
                SELECT f.memory_id, bm25(memories_fts, 0.0, 2.0, 1.2, 1.8) AS score
                FROM memories_fts f
                JOIN memories m ON m.id = f.memory_id
                WHERE memories_fts MATCH ? AND m.deleted_at IS NULL$statusClause$kindClause
                ORDER BY score ASC
                LIMIT ?
                """.trimIndent(),
            ).use { stmt ->
                var bindIndex = 1
                stmt.bindText(bindIndex++, ftsClause)
                filter.kinds.forEach { kind -> stmt.bindText(bindIndex++, kind.wire) }
                stmt.bindLong(bindIndex, candidateLimit.toLong())
                while (stmt.step()) {
                    val memoryId = stmt.getText(0)
                    val bm25Positive = (-stmt.getDouble(1)).coerceAtLeast(0.0)
                    val normalized = bm25Positive / (1.0 + bm25Positive)
                    candidateIds += memoryId
                    lexicalScores[memoryId] = normalized + SUBSTRING_BASE
                }
            }
        }

        if (matchedEntities.isNotEmpty()) {
            val entityIds = matchedEntities.map { it.id }
            val entityNamesById = matchedEntities.associate { it.id to it.canonicalName }
            val placeholders = entityIds.joinToString(",") { "?" }
            conn.prepare(
                "SELECT memory_id, entity_id FROM memory_entities WHERE entity_id IN ($placeholders)",
            ).use { stmt ->
                entityIds.forEachIndexed { index, id -> stmt.bindText(index + 1, id) }
                while (stmt.step()) {
                    val memoryId = stmt.getText(0)
                    val entityId = stmt.getText(1)
                    candidateIds += memoryId
                    entityNamesById[entityId]?.let { name ->
                        matchedByMemory.getOrPut(memoryId) { ArrayList() }.add(name)
                    }
                }
            }
        }

        val records = MemoryStorage.findByIds(conn, candidateIds.toList(), filter.includeHistory)
        val loweredQueries = queries.map { it.lowercase(Locale.ROOT) }
        for ((id, record) in records) {
            val lowerContent = record.content.lowercase(Locale.ROOT)
            if (loweredQueries.any { lowerContent.contains(it) }) {
                lexicalScores[id] = (lexicalScores[id] ?: 0.0) + EXACT_PHRASE_BONUS
            }
        }
        return CandidatePool(records, lexicalScores, matchedByMemory, allowedEntityNames)
    }

    private fun resolveMatchedEntities(
        queries: List<String>,
        explicitEntityName: String?,
        entities: List<MemoryEntity>,
    ): List<MemoryEntity> {
        val searchStrings = ArrayList<String>(queries.size + 1)
        searchStrings.addAll(queries)
        explicitEntityName?.trim()?.takeIf { it.isNotEmpty() }?.let { searchStrings += it }
        if (searchStrings.isEmpty() || entities.isEmpty()) return emptyList()

        val loweredTerms = searchStrings
            .flatMap { q -> MemoryTokenizer.queryTokens(q) + listOf(q.lowercase(Locale.ROOT)) }
            .distinct()

        return entities.filter { entity ->
            val names = listOf(entity.canonicalName) + entity.aliases
            names.any { rawName ->
                val lowerName = rawName.lowercase(Locale.ROOT)
                loweredTerms.any { term ->
                    (term.length >= 2 && (lowerName.contains(term) || term.contains(lowerName))) ||
                        lowerName == term
                }
            }
        }
    }

    private fun matchesExtraFilter(
        record: MemoryRecord,
        filter: MemoryFilter,
        allowedEntityNames: Set<String>?,
    ): Boolean {
        if (filter.kinds.isNotEmpty() && record.kind !in filter.kinds) return false
        val cleanTag = filter.tag?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTag != null && record.tags.none { it.equals(cleanTag, ignoreCase = true) }) return false
        if (allowedEntityNames != null) {
            val matchesEntity = record.entities.any { it.lowercase(Locale.ROOT) in allowedEntityNames }
            if (!matchesEntity) return false
        }
        val cleanSession = filter.sessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanSession != null && record.sourceSessionId != cleanSession) return false
        if (filter.sinceEpochMs != null && record.validFrom < filter.sinceEpochMs!!) return false
        if (filter.untilEpochMs != null && record.validFrom > filter.untilEpochMs!!) return false
        return true
    }

    private fun recencyFactor(record: MemoryRecord, nowMs: Long): Double {
        val ageDays = ((nowMs - record.validFrom).coerceAtLeast(0L)).toDouble() / MS_PER_DAY
        return when (record.kind) {
            MemoryKind.IDENTITY, MemoryKind.HABIT -> (1.0 / (1.0 + ageDays / 365.0)).coerceAtLeast(0.85)
            MemoryKind.ACTIVE -> {
                val expired = record.validUntil != null && record.validUntil!! < nowMs
                if (expired) 0.45 else (1.0 / (1.0 + ageDays / 90.0)).coerceAtLeast(0.75)
            }
            MemoryKind.EPISODIC -> (1.0 / (1.0 + ageDays / EPISODIC_HALF_LIFE_DAYS)).coerceAtLeast(0.25)
        }
    }

    private fun importanceFactor(record: MemoryRecord): Double =
        1.0 + 0.06 * (record.importance - 3) + if (record.pinned) 0.15 else 0.0

    private fun historyFactor(record: MemoryRecord): Double =
        if (record.status == MemoryItemStatus.ACTIVE) 1.0 else 0.72

    private fun collapseNearDuplicates(sorted: List<MemorySearchHit>): List<MemorySearchHit> {
        if (sorted.size <= 1) return sorted
        val kept = ArrayList<MemorySearchHit>(sorted.size)
        for (candidate in sorted) {
            val duplicate = kept.any { existing ->
                existing.record.kind == candidate.record.kind &&
                    MemoryTokenizer.bigramJaccard(existing.record.content, candidate.record.content) > DUPLICATE_JACCARD_THRESHOLD
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private companion object {
        private const val MAX_SEARCH_LIMIT = 25
        private const val CANDIDATE_MULTIPLIER = 4
        private const val MS_PER_DAY = 86_400_000.0
        private const val EPISODIC_HALF_LIFE_DAYS = 60.0
        private const val ENTITY_LINK_BONUS = 0.35
        private const val VECTOR_WEIGHT = 0.5
        private const val SUBSTRING_BASE = 0.15
        private const val EXACT_PHRASE_BONUS = 0.45
        private const val MIN_BASE_SCORE = 0.1
        private const val DUPLICATE_JACCARD_THRESHOLD = 0.75
    }
}

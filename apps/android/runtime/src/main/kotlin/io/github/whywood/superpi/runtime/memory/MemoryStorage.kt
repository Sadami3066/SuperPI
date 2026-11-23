package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.DreamRunSummary
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryOverview
import io.github.whywood.superpi.runtime.MemoryRecord
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.MemoryTagCount
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class PendingIngestionRecord(
    val sessionId: String,
    val sessionFile: String?,
    val transcript: String,
    val lastEntryId: String?,
    val reason: String,
    val attempts: Int,
    val createdAt: Long,
    val lastError: String?,
)

internal data class IngestionWatermark(
    val sessionId: String,
    val sessionFile: String?,
    val lastEntryId: String?,
    val lastHash: String?,
    val updatedAt: Long,
)

internal data class CachedSnapshot(
    val renderedText: String,
    val charCount: Int,
    val revision: Long,
    val generatedAt: Long,
    val reason: String,
)

internal object MemoryStorage {
    private val JSON = Json { ignoreUnknownKeys = true }
    const val PLACEHOLDER_DEVICE_ID = "__placeholder__"

    fun insertMemory(
        conn: SQLiteConnection,
        draft: MemoryDraft,
        nowMs: Long,
        supersedesId: String? = null,
    ): MemoryRecord {
        val id = UUID.randomUUID().toString()
        val cleanContent = MemoryLimits.clampText(draft.content, MemoryLimits.MAX_CONTENT_CHARS)
        val cleanSummary = MemoryLimits.clampOptionalText(draft.summary, MemoryLimits.MAX_SUMMARY_CHARS)
        val cleanTags = cleanStringList(draft.tags)
        val importance = draft.importance.coerceIn(1, 5)
        val validFrom = (draft.validFrom ?: nowMs).coerceAtLeast(0L)

        conn.prepare(
            """
            INSERT INTO memories(
                id, kind, content, summary, tags_json, confidence, importance, pinned,
                valid_from, valid_until, status, superseded_by, source_kind,
                source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                created_at, updated_at, deleted_at, revision
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, NULL, 1)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, draft.kind.wire)
            stmt.bindText(3, cleanContent)
            stmt.bindNullableText(4, cleanSummary)
            stmt.bindText(5, encodeStringList(cleanTags))
            stmt.bindText(6, draft.confidence.wire)
            stmt.bindLong(7, importance.toLong())
            stmt.bindLong(8, if (draft.pinned) 1L else 0L)
            stmt.bindLong(9, validFrom)
            stmt.bindNullableLong(10, draft.validUntil?.coerceAtLeast(0L))
            stmt.bindText(11, MemoryItemStatus.ACTIVE.wire)
            stmt.bindNullableText(12, null)
            stmt.bindText(13, draft.sourceKind.wire)
            stmt.bindNullableText(14, MemoryLimits.clampOptionalText(draft.sourceSessionId, MemoryLimits.MAX_ID_LENGTH))
            stmt.bindNullableText(15, MemoryLimits.clampOptionalText(draft.sourceEntryId, MemoryLimits.MAX_ID_LENGTH))
            stmt.bindNullableText(16, MemoryLimits.clampOptionalText(draft.sourceQuote, MemoryLimits.MAX_QUOTE_CHARS))
            stmt.bindLong(17, nowMs)
            stmt.bindLong(18, nowMs)
            stmt.step()
        }

        val entityNames = linkEntitiesForMemory(conn, id, draft.entities, nowMs)
        if (!supersedesId.isNullOrBlank() && supersedesId != id) {
            markSuperseded(conn, supersedesId, id, nowMs)
        }

        upsertFts(conn, id, cleanContent, cleanSummary, cleanTags, entityNames)
        recordSyncOp(conn, "MEMORY", id, "UPSERT", 1L, nowMs)
        return checkNotNull(findById(conn, id))
    }

    fun updateMemory(
        conn: SQLiteConnection,
        id: String,
        content: String,
        kind: MemoryKind,
        tags: List<String>,
        confidence: MemoryConfidence,
        pinned: Boolean,
        validUntil: Long?,
        nowMs: Long,
    ): MemoryRecord? {
        val existing = findById(conn, id, includeDeleted = false) ?: return null
        val cleanContent = MemoryLimits.clampText(content, MemoryLimits.MAX_CONTENT_CHARS)
        if (cleanContent.isEmpty()) return existing
        val cleanTags = cleanStringList(tags)
        val nextRevision = existing.revision + 1L
        conn.prepare(
            """
            UPDATE memories
            SET content = ?, summary = NULL, kind = ?, tags_json = ?, confidence = ?,
                pinned = ?, valid_until = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, cleanContent)
            stmt.bindText(2, kind.wire)
            stmt.bindText(3, encodeStringList(cleanTags))
            stmt.bindText(4, confidence.wire)
            stmt.bindLong(5, if (pinned) 1L else 0L)
            stmt.bindNullableLong(6, validUntil?.coerceAtLeast(0L))
            stmt.bindLong(7, nowMs)
            stmt.bindLong(8, nextRevision)
            stmt.bindText(9, id)
            stmt.step()
        }
        val entityTokens = entitySearchTermsForMemory(conn, id)
        upsertFts(conn, id, cleanContent, null, cleanTags, entityTokens)
        recordSyncOp(conn, "MEMORY", id, "UPSERT", nextRevision, nowMs)
        return findById(conn, id)
    }

    fun confirmMemory(conn: SQLiteConnection, id: String, nowMs: Long): MemoryRecord? {
        val existing = findById(conn, id, includeDeleted = false) ?: return null
        val nextRevision = existing.revision + 1L
        conn.prepare(
            """
            UPDATE memories
            SET confidence = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, MemoryConfidence.CONFIRMED.wire)
            stmt.bindLong(2, nowMs)
            stmt.bindLong(3, nextRevision)
            stmt.bindText(4, id)
            stmt.step()
        }
        recordSyncOp(conn, "MEMORY", id, "UPSERT", nextRevision, nowMs)
        return findById(conn, id)
    }

    fun markStatus(
        conn: SQLiteConnection,
        id: String,
        status: MemoryItemStatus,
        supersededBy: String?,
        nowMs: Long,
    ): Boolean {
        val existing = findById(conn, id, includeDeleted = false) ?: return false
        val nextRevision = existing.revision + 1L
        conn.prepare(
            """
            UPDATE memories
            SET status = ?, superseded_by = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, status.wire)
            stmt.bindNullableText(2, supersededBy)
            stmt.bindLong(3, nowMs)
            stmt.bindLong(4, nextRevision)
            stmt.bindText(5, id)
            stmt.step()
        }
        recordSyncOp(conn, "MEMORY", id, "UPSERT", nextRevision, nowMs)
        return true
    }

    fun markSuperseded(conn: SQLiteConnection, oldId: String, newId: String, nowMs: Long): Boolean =
        markStatus(conn, oldId, MemoryItemStatus.SUPERSEDED, newId, nowMs)

    fun softDelete(conn: SQLiteConnection, id: String, nowMs: Long): Boolean {
        val existing = findById(conn, id, includeDeleted = false) ?: return false
        val nextRevision = existing.revision + 1L
        conn.prepare(
            """
            UPDATE memories
            SET deleted_at = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nowMs)
            stmt.bindLong(3, nextRevision)
            stmt.bindText(4, id)
            stmt.step()
        }
        deleteFts(conn, id)
        recordSyncOp(conn, "MEMORY", id, "DELETE", nextRevision, nowMs)
        return true
    }

    fun restore(conn: SQLiteConnection, id: String, nowMs: Long): MemoryRecord? {
        val existing = findById(conn, id, includeDeleted = true) ?: return null
        val nextRevision = existing.revision + 1L
        conn.prepare(
            """
            UPDATE memories
            SET deleted_at = NULL, updated_at = ?, revision = ?, updated_by_device_id = NULL
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nextRevision)
            stmt.bindText(3, id)
            stmt.step()
        }
        val restored = findById(conn, id, includeDeleted = false) ?: return null
        upsertFts(conn, id, restored.content, restored.summary, restored.tags, entitySearchTermsForMemory(conn, id))
        recordSyncOp(conn, "MEMORY", id, "UPSERT", nextRevision, nowMs)
        return restored
    }

    fun softDeleteByTag(conn: SQLiteConnection, tag: String, nowMs: Long): Int {
        val cleanTag = tag.trim()
        if (cleanTag.isEmpty()) return 0
        val candidates = queryRecords(
            conn,
            MemoryFilter(tag = cleanTag, includeHistory = true, limit = 10_000),
        )
        var removed = 0
        for (item in candidates) {
            if (item.tags.any { it.equals(cleanTag, ignoreCase = true) }) {
                if (softDelete(conn, item.id, nowMs)) removed++
            }
        }
        return removed
    }

    fun softDeleteBySession(conn: SQLiteConnection, sessionId: String, nowMs: Long): Int {
        val cleanSession = sessionId.trim()
        if (cleanSession.isEmpty()) return 0
        val ids = ArrayList<String>()
        conn.prepare("SELECT id FROM memories WHERE source_session_id = ? AND deleted_at IS NULL").use { stmt ->
            stmt.bindText(1, cleanSession)
            while (stmt.step()) ids += stmt.getText(0)
        }
        var removed = 0
        for (id in ids) {
            if (softDelete(conn, id, nowMs)) removed++
        }
        return removed
    }

    fun countBySession(conn: SQLiteConnection, sessionId: String): Int {
        val cleanSession = sessionId.trim()
        if (cleanSession.isEmpty()) return 0
        return conn.prepare(
            "SELECT COUNT(*) FROM memories WHERE source_session_id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.bindText(1, cleanSession)
            if (stmt.step()) stmt.getInt(0) else 0
        }
    }

    fun findById(conn: SQLiteConnection, id: String, includeDeleted: Boolean = false): MemoryRecord? {
        val sql = if (includeDeleted) {
            "SELECT ${MEMORY_COLUMNS} FROM memories WHERE id = ? LIMIT 1"
        } else {
            "SELECT ${MEMORY_COLUMNS} FROM memories WHERE id = ? AND deleted_at IS NULL LIMIT 1"
        }
        val base = conn.prepare(sql).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readMemoryRow(stmt) else null
        } ?: return null
        val entities = entityNamesForMemory(conn, base.id)
        return base.copy(entities = entities)
    }

    fun findByIds(conn: SQLiteConnection, ids: List<String>, includeHistory: Boolean): Map<String, MemoryRecord> {
        if (ids.isEmpty()) return emptyMap()
        val distinct = ids.distinct()
        val placeholders = distinct.joinToString(",") { "?" }
        val statusSql = if (includeHistory) "" else " AND status = 'ACTIVE'"
        val sql = "SELECT $MEMORY_COLUMNS FROM memories WHERE id IN ($placeholders) AND deleted_at IS NULL$statusSql"
        val raw = ArrayList<MemoryRecord>(distinct.size)
        conn.prepare(sql).use { stmt ->
            distinct.forEachIndexed { index, id -> stmt.bindText(index + 1, id) }
            while (stmt.step()) raw += readMemoryRow(stmt)
        }
        return attachEntities(conn, raw).associateBy { it.id }
    }

    fun queryRecords(conn: SQLiteConnection, filter: MemoryFilter): List<MemoryRecord> {
        val where = ArrayList<String>()
        val args = ArrayList<Any>()
        where += "deleted_at IS NULL"
        if (!filter.includeHistory) {
            where += "status = 'ACTIVE'"
        }
        if (filter.kinds.isNotEmpty()) {
            val placeholders = filter.kinds.joinToString(",") { "?" }
            where += "kind IN ($placeholders)"
            filter.kinds.forEach { args += it.wire }
        }
        if (!filter.sessionId.isNullOrBlank()) {
            where += "source_session_id = ?"
            args += filter.sessionId!!.trim()
        }
        if (filter.sinceEpochMs != null) {
            where += "valid_from >= ?"
            args += filter.sinceEpochMs!!
        }
        if (filter.untilEpochMs != null) {
            where += "valid_from <= ?"
            args += filter.untilEpochMs!!
        }
        if (!filter.tag.isNullOrBlank()) {
            where += "tags_json LIKE ?"
            args += "%" + filter.tag!!.trim() + "%"
        }
        if (!filter.entityName.isNullOrBlank()) {
            where += "id IN (SELECT me.memory_id FROM memory_entities me JOIN entities e ON e.id = me.entity_id WHERE e.deleted_at IS NULL AND (e.canonical_name = ? OR e.aliases_json LIKE ?))"
            val clean = filter.entityName!!.trim()
            args += clean
            args += "%$clean%"
        }
        val sqlLimit = (filter.limit.coerceIn(1, 10_000) * 3).coerceAtMost(10_000)
        val sql = buildString {
            append("SELECT ").append(MEMORY_COLUMNS).append(" FROM memories WHERE ")
            append(where.joinToString(" AND "))
            append(" ORDER BY pinned DESC, updated_at DESC LIMIT ?")
        }
        args += sqlLimit
        val raw = ArrayList<MemoryRecord>()
        conn.prepare(sql).use { stmt ->
            bindDynamicArgs(stmt, args)
            while (stmt.step()) raw += readMemoryRow(stmt)
        }
        val hydrated = attachEntities(conn, raw)
        val cleanTag = filter.tag?.trim()?.takeIf { it.isNotEmpty() }
        val filtered = if (cleanTag == null) {
            hydrated
        } else {
            hydrated.filter { record -> record.tags.any { it.equals(cleanTag, ignoreCase = true) } }
        }
        return filtered.take(filter.limit.coerceIn(1, 10_000))
    }

    fun listActiveEntities(conn: SQLiteConnection): List<MemoryEntity> {
        val result = ArrayList<MemoryEntity>()
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, summary, updated_at
            FROM entities
            WHERE deleted_at IS NULL
            ORDER BY updated_at DESC
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                result += MemoryEntity(
                    id = stmt.getText(0),
                    canonicalName = stmt.getText(1),
                    kind = EntityKind.fromWire(stmt.getText(2)),
                    aliases = decodeStringList(stmt.getText(3)),
                    summary = stmt.getNullableText(4),
                    updatedAt = stmt.getLong(5),
                )
            }
        }
        return result
    }

    fun linkEntitiesForMemory(
        conn: SQLiteConnection,
        memoryId: String,
        refs: List<MemoryEntityRef>,
        nowMs: Long,
    ): List<String> {
        if (refs.isEmpty()) return emptyList()
        val linkedNames = LinkedHashSet<String>()
        var linkedCount = 0
        for (ref in refs) {
            if (linkedCount >= MemoryLimits.MAX_ENTITIES_PER_MEMORY) break
            val cleanName = MemoryLimits.clampText(ref.name, MemoryLimits.MAX_ENTITY_NAME_CHARS)
            if (cleanName.isEmpty()) continue
            val entity = upsertEntity(conn, ref.copy(name = cleanName), nowMs)
            conn.prepare(
                "INSERT OR IGNORE INTO memory_entities(memory_id, entity_id) VALUES (?, ?)",
            ).use { stmt ->
                stmt.bindText(1, memoryId)
                stmt.bindText(2, entity.id)
                stmt.step()
            }
            linkedNames += entity.canonicalName
            linkedNames.addAll(entity.aliases)
            linkedCount++
        }
        return linkedNames.toList()
    }

    fun upsertEntity(conn: SQLiteConnection, ref: MemoryEntityRef, nowMs: Long): MemoryEntity {
        val cleanName = MemoryLimits.clampText(ref.name, MemoryLimits.MAX_ENTITY_NAME_CHARS)
        val cleanSummary = MemoryLimits.clampOptionalText(ref.summary, MemoryLimits.MAX_SUMMARY_CHARS)
        val allEntities = listActiveEntities(conn)
        val existing = allEntities.firstOrNull { entity ->
            entity.canonicalName.equals(cleanName, ignoreCase = true) ||
                entity.aliases.any { it.equals(cleanName, ignoreCase = true) }
        }
        val cleanAliases = cleanAliasesForCanonical(ref.aliases, cleanName)
        if (existing == null) {
            val id = UUID.randomUUID().toString()
            conn.prepare(
                """
                INSERT INTO entities(id, canonical_name, entity_type, aliases_json, summary, updated_at, deleted_at, revision)
                VALUES (?, ?, ?, ?, ?, ?, NULL, 1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, cleanName)
                stmt.bindText(3, ref.kind.wire)
                stmt.bindText(4, encodeStringList(cleanAliases))
                stmt.bindNullableText(5, cleanSummary)
                stmt.bindLong(6, nowMs)
                stmt.step()
            }
            recordSyncOp(conn, "ENTITY", id, "UPSERT", 1L, nowMs)
            return MemoryEntity(id, cleanName, ref.kind, cleanAliases, cleanSummary, nowMs)
        }

        val combinedAliases = LinkedHashSet<String>(existing.aliases)
        if (!existing.canonicalName.equals(cleanName, ignoreCase = true)) {
            combinedAliases += cleanName
        }
        combinedAliases.addAll(cleanAliases)
        val nextAliasesList = cleanAliasesForCanonical(combinedAliases.toList(), existing.canonicalName)
        val nextSummary = cleanSummary ?: existing.summary
        val nextKind = if (existing.kind == EntityKind.OTHER || existing.kind == EntityKind.TOPIC) ref.kind else existing.kind
        val placeholder = existing.updatedAt == 0L || isPlaceholderEntity(conn, existing.id)
        if (placeholder || nextAliasesList != existing.aliases || nextSummary != existing.summary || nextKind != existing.kind) {
            val currentRevision = entityRevision(conn, existing.id)
            val nextRevision = (currentRevision + 1L).coerceAtLeast(1L)
            conn.prepare(
                """
                UPDATE entities
                SET entity_type = ?, aliases_json = ?, summary = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL
                WHERE id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, nextKind.wire)
                stmt.bindText(2, encodeStringList(nextAliasesList))
                stmt.bindNullableText(3, nextSummary)
                stmt.bindLong(4, nowMs)
                stmt.bindLong(5, nextRevision)
                stmt.bindText(6, existing.id)
                stmt.step()
            }
            recordSyncOp(conn, "ENTITY", existing.id, "UPSERT", nextRevision, nowMs)
            refreshFtsForEntity(conn, existing.id)
        }
        return existing.copy(kind = nextKind, aliases = nextAliasesList, summary = nextSummary, updatedAt = nowMs)
    }

    fun mergeEntities(
        conn: SQLiteConnection,
        keepName: String,
        mergeNames: List<String>,
        summary: String?,
        nowMs: Long,
    ): Int {
        val cleanKeepName = MemoryLimits.clampText(keepName, MemoryLimits.MAX_ENTITY_NAME_CHARS)
        if (cleanKeepName.isEmpty()) return 0
        val entities = listActiveEntities(conn)
        val keeper = entities.firstOrNull { it.canonicalName.equals(cleanKeepName, ignoreCase = true) }
            ?: upsertEntity(conn, MemoryEntityRef(cleanKeepName), nowMs)
        var mergedCount = 0
        val combinedAliases = LinkedHashSet<String>(keeper.aliases)
        val affectedMemories = HashSet<String>()

        for (rawVictim in mergeNames) {
            val victimName = MemoryLimits.clampText(rawVictim, MemoryLimits.MAX_ENTITY_NAME_CHARS)
            if (victimName.isEmpty() || victimName.equals(keeper.canonicalName, ignoreCase = true)) continue
            val victim = entities.firstOrNull {
                it.id != keeper.id &&
                    (it.canonicalName.equals(victimName, ignoreCase = true) ||
                        it.aliases.any { alias -> alias.equals(victimName, ignoreCase = true) })
            }
            if (victim == null) {
                combinedAliases += victimName
                continue
            }
            combinedAliases += victim.canonicalName
            combinedAliases.addAll(victim.aliases)

            val victimMemoryIds = ArrayList<String>()
            conn.prepare("SELECT memory_id FROM memory_entities WHERE entity_id = ?").use { stmt ->
                stmt.bindText(1, victim.id)
                while (stmt.step()) victimMemoryIds += stmt.getText(0)
            }
            for (memoryId in victimMemoryIds) {
                affectedMemories += memoryId
                conn.prepare("INSERT OR IGNORE INTO memory_entities(memory_id, entity_id) VALUES (?, ?)").use { stmt ->
                    stmt.bindText(1, memoryId)
                    stmt.bindText(2, keeper.id)
                    stmt.step()
                }
            }
            conn.prepare("DELETE FROM memory_entities WHERE entity_id = ?").use { stmt ->
                stmt.bindText(1, victim.id)
                stmt.step()
            }
            val victimRevision = (entityRevision(conn, victim.id) + 1L).coerceAtLeast(1L)
            conn.prepare(
                "UPDATE entities SET deleted_at = ?, updated_at = ?, revision = ?, merged_into = ?, updated_by_device_id = NULL WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                stmt.bindLong(2, nowMs)
                stmt.bindLong(3, victimRevision)
                stmt.bindText(4, keeper.canonicalName.take(MemoryLimits.MAX_ENTITY_NAME_CHARS))
                stmt.bindText(5, victim.id)
                stmt.step()
            }
            recordSyncOp(conn, "ENTITY", victim.id, "DELETE", victimRevision, nowMs)
            mergedCount++
        }

        val cleanCombinedAliases = cleanAliasesForCanonical(combinedAliases.toList(), keeper.canonicalName)
        val nextSummary = MemoryLimits.clampOptionalText(summary, MemoryLimits.MAX_SUMMARY_CHARS)
            ?: MemoryLimits.clampOptionalText(keeper.summary, MemoryLimits.MAX_SUMMARY_CHARS)
        val keeperRevision = (entityRevision(conn, keeper.id) + 1L).coerceAtLeast(1L)
        conn.prepare(
            "UPDATE entities SET aliases_json = ?, summary = ?, updated_at = ?, revision = ?, updated_by_device_id = NULL WHERE id = ?",
        ).use { stmt ->
            stmt.bindText(1, encodeStringList(cleanCombinedAliases))
            stmt.bindNullableText(2, nextSummary)
            stmt.bindLong(3, nowMs)
            stmt.bindLong(4, keeperRevision)
            stmt.bindText(5, keeper.id)
            stmt.step()
        }
        recordSyncOp(conn, "ENTITY", keeper.id, "UPSERT", keeperRevision, nowMs)
        refreshFtsForEntity(conn, keeper.id)
        for (memoryId in affectedMemories) {
            findById(conn, memoryId)?.let { record ->
                val nextRevision = record.revision + 1L
                conn.prepare(
                    "UPDATE memories SET updated_at = ?, revision = ?, updated_by_device_id = NULL WHERE id = ? AND deleted_at IS NULL",
                ).use { stmt ->
                    stmt.bindLong(1, nowMs)
                    stmt.bindLong(2, nextRevision)
                    stmt.bindText(3, record.id)
                    stmt.step()
                }
                upsertFts(
                    conn,
                    record.id,
                    record.content,
                    record.summary,
                    record.tags,
                    entitySearchTermsForMemory(conn, record.id),
                )
                recordSyncOp(conn, "MEMORY", record.id, "UPSERT", nextRevision, nowMs)
            }
        }
        return mergedCount
    }

    fun expireOutdatedActive(conn: SQLiteConnection, nowMs: Long): Int {
        val ids = ArrayList<String>()
        conn.prepare(
            """
            SELECT id FROM memories
            WHERE deleted_at IS NULL AND status = 'ACTIVE' AND kind = 'ACTIVE'
              AND pinned = 0 AND valid_until IS NOT NULL AND valid_until < ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            while (stmt.step()) ids += stmt.getText(0)
        }
        var updated = 0
        for (id in ids) {
            if (markStatus(conn, id, MemoryItemStatus.EXPIRED, null, nowMs)) updated++
        }
        return updated
    }

    fun archiveStaleInferred(conn: SQLiteConnection, cutoffMs: Long, nowMs: Long): Int {
        val ids = ArrayList<String>()
        conn.prepare(
            """
            SELECT id FROM memories
            WHERE deleted_at IS NULL AND status = 'ACTIVE'
              AND pinned = 0 AND confidence = 'INFERRED'
              AND importance <= 2 AND access_count = 0 AND updated_at < ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, cutoffMs)
            while (stmt.step()) ids += stmt.getText(0)
        }
        var archived = 0
        for (id in ids) {
            if (markStatus(conn, id, MemoryItemStatus.ARCHIVED, null, nowMs)) archived++
        }
        return archived
    }

    fun incrementAccess(conn: SQLiteConnection, ids: List<String>, nowMs: Long) {
        if (ids.isEmpty()) return
        conn.prepare(
            "UPDATE memories SET access_count = access_count + 1, last_accessed_at = ? WHERE id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            for (id in ids) {
                stmt.bindLong(1, nowMs)
                stmt.bindText(2, id)
                stmt.step()
                stmt.reset()
            }
        }
    }

    fun listTags(conn: SQLiteConnection): List<MemoryTagCount> {
        val counts = LinkedHashMap<String, Int>()
        conn.prepare(
            "SELECT tags_json FROM memories WHERE deleted_at IS NULL AND status = 'ACTIVE'",
        ).use { stmt ->
            while (stmt.step()) {
                for (tag in decodeStringList(stmt.getText(0))) {
                    counts[tag] = (counts[tag] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (tag, count) -> MemoryTagCount(tag, count) }
    }

    fun overview(conn: SQLiteConnection): MemoryOverview {
        val byKind = LinkedHashMap<MemoryKind, Int>()
        MemoryKind.entries.forEach { byKind[it] = 0 }
        var total = 0
        conn.prepare(
            "SELECT kind, COUNT(*) FROM memories WHERE deleted_at IS NULL AND status = 'ACTIVE' GROUP BY kind",
        ).use { stmt ->
            while (stmt.step()) {
                val kind = MemoryKind.fromWire(stmt.getText(0)) ?: continue
                val count = stmt.getInt(1)
                byKind[kind] = count
                total += count
            }
        }
        val entityCount = conn.prepare("SELECT COUNT(*) FROM entities WHERE deleted_at IS NULL").use { stmt ->
            if (stmt.step()) stmt.getInt(0) else 0
        }
        val lastDreamAt = conn.prepare(
            "SELECT MAX(finished_at) FROM dream_runs WHERE status = 'DONE'",
        ).use { stmt ->
            if (stmt.step() && !stmt.isNull(0)) stmt.getLong(0) else null
        }
        val pendingCount = conn.prepare("SELECT COUNT(*) FROM pending_ingestions").use { stmt ->
            if (stmt.step()) stmt.getInt(0) else 0
        }
        return MemoryOverview(
            totalActive = total,
            byKind = byKind,
            entityCount = entityCount,
            topTags = listTags(conn).take(20),
            lastDreamAt = lastDreamAt,
            pendingIngestions = pendingCount,
        )
    }

    fun readSnapshot(conn: SQLiteConnection): CachedSnapshot? =
        conn.prepare(
            "SELECT rendered_text, char_count, revision, generated_at, reason FROM core_snapshot WHERE id = 1",
        ).use { stmt ->
            if (!stmt.step()) null
            else CachedSnapshot(
                renderedText = stmt.getText(0),
                charCount = stmt.getInt(1),
                revision = stmt.getLong(2),
                generatedAt = stmt.getLong(3),
                reason = stmt.getText(4),
            )
        }

    fun saveSnapshot(
        conn: SQLiteConnection,
        renderedText: String,
        revision: Long,
        generatedAt: Long,
        reason: String,
    ) {
        conn.prepare(
            """
            INSERT INTO core_snapshot(id, rendered_text, char_count, revision, generated_at, reason)
            VALUES (1, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                rendered_text = excluded.rendered_text,
                char_count = excluded.char_count,
                revision = excluded.revision,
                generated_at = excluded.generated_at,
                reason = excluded.reason
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, renderedText)
            stmt.bindLong(2, renderedText.length.toLong())
            stmt.bindLong(3, revision)
            stmt.bindLong(4, generatedAt)
            stmt.bindText(5, reason)
            stmt.step()
        }
    }

    fun currentMaxRevision(conn: SQLiteConnection): Long =
        conn.prepare("SELECT COALESCE(MAX(seq), 0) FROM sync_ops").use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }

    fun currentMaxSyncSeq(conn: SQLiteConnection): Long =
        conn.prepare("SELECT COALESCE(MAX(seq), 0) FROM sync_ops").use { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }

    fun pruneSyncOps(conn: SQLiteConnection, maxSeqToPrune: Long, keepTail: Int = 200) {
        if (maxSeqToPrune <= 0L) return
        val tail = keepTail.coerceAtLeast(1)
        conn.prepare(
            """
            DELETE FROM sync_ops
            WHERE seq <= ?
              AND seq NOT IN (SELECT seq FROM sync_ops ORDER BY seq DESC LIMIT ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, maxSeqToPrune)
            stmt.bindLong(2, tail.toLong())
            stmt.step()
        }
    }

    fun pruneDreamRuns(conn: SQLiteConnection, keepLatest: Int = 100) {
        val keep = keepLatest.coerceAtLeast(1)
        conn.prepare(
            """
            DELETE FROM dream_runs
            WHERE id NOT IN (SELECT id FROM dream_runs ORDER BY started_at DESC LIMIT ?)
              AND id IS NOT (
                  SELECT id FROM dream_runs WHERE status = 'DONE' ORDER BY finished_at DESC LIMIT 1
              )
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, keep.toLong())
            stmt.step()
        }
    }

    fun markAllLocalDataOwnedForMerge(conn: SQLiteConnection) {
        conn.execSQL("UPDATE memories SET updated_by_device_id = NULL WHERE deleted_at IS NULL")
        conn.execSQL(
            """
            UPDATE entities
            SET updated_by_device_id = NULL
            WHERE deleted_at IS NULL
              AND (updated_by_device_id IS NULL OR updated_by_device_id != '$PLACEHOLDER_DEVICE_ID')
            """.trimIndent(),
        )
    }

    fun wipeAllMemoryDataForAccountSwitch(conn: SQLiteConnection) {
        conn.execSQL("DELETE FROM memory_entities")
        conn.execSQL("DELETE FROM memories_fts")
        conn.execSQL("DELETE FROM memories")
        conn.execSQL("DELETE FROM entities")
        conn.execSQL("DELETE FROM sync_ops")
        conn.execSQL("DELETE FROM pending_ingestions")
        conn.execSQL("DELETE FROM dream_runs")
        conn.execSQL("DELETE FROM core_snapshot")
    }

    fun countLocalMemories(conn: SQLiteConnection): Int =
        conn.prepare("SELECT COUNT(*) FROM memories WHERE deleted_at IS NULL").use { stmt ->
            if (stmt.step()) stmt.getInt(0) else 0
        }

    fun readSyncState(conn: SQLiteConnection, key: String): String? =
        conn.prepare("SELECT value FROM sync_state WHERE key = ?").use { stmt ->
            stmt.bindText(1, key)
            if (stmt.step()) stmt.getText(0) else null
        }

    fun writeSyncState(conn: SQLiteConnection, key: String, value: String) {
        conn.prepare(
            "INSERT INTO sync_state(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        ).use { stmt ->
            stmt.bindText(1, key)
            stmt.bindText(2, value)
            stmt.step()
        }
    }

    fun deleteSyncState(conn: SQLiteConnection, key: String) {
        conn.prepare("DELETE FROM sync_state WHERE key = ?").use { stmt ->
            stmt.bindText(1, key)
            stmt.step()
        }
    }

    fun deduplicateExactActiveMemories(conn: SQLiteConnection, nowMs: Long): Int {
        val active = queryRecords(conn, MemoryFilter(includeHistory = false, limit = 10_000))
        if (active.size < 2) return 0
        val groups = active.groupBy { it.kind to normalizeExactDedup(it.content) }
        var archived = 0
        val rankComparator = compareByDescending<MemoryRecord> { it.pinned }
            .thenByDescending {
                when (it.confidence) {
                    MemoryConfidence.CONFIRMED -> 3
                    MemoryConfidence.OBSERVED -> 2
                    MemoryConfidence.INFERRED -> 1
                }
            }
            .thenBy { it.createdAt }
            .thenBy { it.id }
        for (group in groups.values) {
            if (group.size < 2) continue
            val sorted = group.sortedWith(rankComparator)
            for (loser in sorted.drop(1)) {
                if (!loser.pinned && loser.confidence != MemoryConfidence.CONFIRMED) {
                    if (markStatus(conn, loser.id, MemoryItemStatus.ARCHIVED, null, nowMs)) {
                        archived++
                    }
                }
            }
        }
        return archived
    }

    fun exportBaselineEntities(
        conn: SQLiteConnection,
        afterId: String,
        deviceId: String,
        limit: Int,
    ): List<MemorySyncOpItem> {
        val safeDeviceId = MemoryLimits.clampText(deviceId, MemoryLimits.MAX_ID_LENGTH)
        val records = ArrayList<EntitySyncRecord>()
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, summary, merged_into,
                   updated_at, deleted_at, revision, updated_by_device_id
            FROM entities
            WHERE (updated_by_device_id IS NULL OR updated_by_device_id = ?) AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, safeDeviceId)
            stmt.bindText(2, afterId)
            stmt.bindLong(3, limit.coerceIn(1, 500).toLong())
            while (stmt.step()) {
                val id = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
                val canonicalName = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_ENTITY_NAME_CHARS)
                if (id.isEmpty() || canonicalName.isEmpty()) continue
                records += EntitySyncRecord(
                    canonicalKey = canonicalName.lowercase(),
                    id = id,
                    canonicalName = canonicalName,
                    kind = EntityKind.fromWire(stmt.getText(2)),
                    aliases = cleanAliasesForCanonical(decodeStringList(stmt.getText(3)), canonicalName),
                    summary = MemoryLimits.clampOptionalText(stmt.getNullableText(4), MemoryLimits.MAX_SUMMARY_CHARS),
                    mergedInto = MemoryLimits.clampOptionalText(stmt.getNullableText(5), MemoryLimits.MAX_ENTITY_NAME_CHARS),
                    updatedAt = stmt.getLong(6).coerceAtLeast(0L),
                    deletedAt = stmt.getNullableLong(7)?.coerceAtLeast(0L),
                    revision = stmt.getLong(8).coerceAtLeast(1L),
                    updatedByDeviceId = MemoryLimits.clampOptionalText(stmt.getNullableText(9), MemoryLimits.MAX_ID_LENGTH)
                        ?: safeDeviceId,
                )
            }
        }
        return records.map { entity ->
            MemorySyncOpItem(
                opId = MemoryLimits.clampOpId("$safeDeviceId:base:entity:${entity.id}:${entity.revision}"),
                kind = MemorySyncOpKind.ENTITY,
                entity = entity,
            )
        }
    }

    fun exportBaselineMemories(
        conn: SQLiteConnection,
        afterId: String,
        deviceId: String,
        limit: Int,
    ): List<MemorySyncOpItem> {
        val safeDeviceId = MemoryLimits.clampText(deviceId, MemoryLimits.MAX_ID_LENGTH)
        val ids = ArrayList<String>()
        conn.prepare(
            "SELECT id FROM memories WHERE (updated_by_device_id IS NULL OR updated_by_device_id = ?) AND id > ? ORDER BY id ASC LIMIT ?",
        ).use { stmt ->
            stmt.bindText(1, safeDeviceId)
            stmt.bindText(2, afterId)
            stmt.bindLong(3, limit.coerceIn(1, 500).toLong())
            while (stmt.step()) ids += stmt.getText(0)
        }
        return ids.mapNotNull { id ->
            val syncRecord = readMemorySyncRecord(conn, id, safeDeviceId) ?: return@mapNotNull null
            MemorySyncOpItem(
                opId = MemoryLimits.clampOpId("$safeDeviceId:base:memory:${syncRecord.id}:${syncRecord.revision}"),
                kind = MemorySyncOpKind.MEMORY,
                memory = syncRecord,
            )
        }
    }

    fun exportIncrementalOps(
        conn: SQLiteConnection,
        afterSeq: Long,
        deviceId: String,
        limit: Int,
    ): Pair<Long, List<MemorySyncOpItem>> {
        val safeDeviceId = MemoryLimits.clampText(deviceId, MemoryLimits.MAX_ID_LENGTH)
        val rows = ArrayList<Triple<Long, String, String>>()
        conn.prepare(
            "SELECT seq, entity_kind, entity_id FROM sync_ops WHERE seq > ? ORDER BY seq ASC LIMIT ?",
        ).use { stmt ->
            stmt.bindLong(1, afterSeq)
            stmt.bindLong(2, limit.coerceIn(1, 200).toLong())
            while (stmt.step()) {
                rows += Triple(stmt.getLong(0), stmt.getText(1), stmt.getText(2))
            }
        }
        if (rows.isEmpty()) return afterSeq to emptyList()
        val maxSeq = rows.last().first
        val latestByTarget = LinkedHashMap<Pair<String, String>, Long>()
        for ((seq, kind, id) in rows) {
            latestByTarget[kind to id] = seq
        }
        val items = ArrayList<MemorySyncOpItem>(latestByTarget.size)
        for ((target, seq) in latestByTarget) {
            val (kind, id) = target
            when (kind.uppercase()) {
                "ENTITY" -> {
                    val entity = readEntitySyncRecord(conn, id, safeDeviceId) ?: continue
                    items += MemorySyncOpItem(
                        opId = MemoryLimits.clampOpId("$safeDeviceId:inc:$seq:entity:$id"),
                        kind = MemorySyncOpKind.ENTITY,
                        entity = entity,
                    )
                }
                "MEMORY" -> {
                    val memory = readMemorySyncRecord(conn, id, safeDeviceId) ?: continue
                    items += MemorySyncOpItem(
                        opId = MemoryLimits.clampOpId("$safeDeviceId:inc:$seq:memory:$id"),
                        kind = MemorySyncOpKind.MEMORY,
                        memory = memory,
                    )
                }
            }
        }
        return maxSeq to items
    }

    fun applyRemoteEntity(conn: SQLiteConnection, remote: EntitySyncRecord, nowMs: Long): Boolean {
        val canonicalName = remote.canonicalName.trim()
        if (canonicalName.isEmpty()) return false
        val canonicalKey = canonicalName.lowercase()
        val existing = findEntityRowForSync(conn, canonicalKey, remote.id)
        if (existing == null) {
            val cleanAliases = cleanAliasesForCanonical(remote.aliases, canonicalName)
            conn.prepare(
                """
                INSERT INTO entities(
                    id, canonical_name, entity_type, aliases_json, summary,
                    updated_at, deleted_at, revision, merged_into, updated_by_device_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, remote.id)
                stmt.bindText(2, canonicalName)
                stmt.bindText(3, remote.kind.wire)
                stmt.bindText(4, encodeStringList(cleanAliases))
                stmt.bindNullableText(5, remote.summary?.trim()?.takeIf { it.isNotEmpty() })
                stmt.bindLong(6, remote.updatedAt)
                stmt.bindNullableLong(7, remote.deletedAt)
                stmt.bindLong(8, remote.revision.coerceAtLeast(1L))
                stmt.bindNullableText(9, remote.mergedInto?.trim()?.takeIf { it.isNotEmpty() })
                stmt.bindNullableText(10, remote.updatedByDeviceId)
                stmt.step()
            }
            val mergedIntoTarget = remote.mergedInto?.trim()?.takeIf { it.isNotEmpty() }
            if (mergedIntoTarget != null && remote.deletedAt != null) {
                retargetMergedEntitySync(
                    conn = conn,
                    victimEntityId = remote.id,
                    victimCanonicalName = canonicalName,
                    victimAliases = cleanAliases,
                    targetName = mergedIntoTarget,
                    nowMs = nowMs,
                )
            }
            return true
        }

        val existingIsPlaceholder = existing.updatedByDeviceId == PLACEHOLDER_DEVICE_ID
        val incomingWins = existingIsPlaceholder || isNewerSyncTuple(
            incomingUpdatedAt = remote.updatedAt,
            incomingDeviceId = remote.updatedByDeviceId.orEmpty(),
            incomingRevision = remote.revision,
            existingUpdatedAt = existing.updatedAt,
            existingDeviceId = existing.updatedByDeviceId.orEmpty(),
            existingRevision = existing.revision,
        )
        val winnerName = if (incomingWins) canonicalName else existing.canonicalName
        val winnerKind = if (incomingWins) remote.kind else existing.kind
        val winnerDeletedAt = if (incomingWins) remote.deletedAt else existing.deletedAt
        val winnerMergedInto = if (incomingWins) {
            MemoryLimits.clampOptionalText(remote.mergedInto, MemoryLimits.MAX_ENTITY_NAME_CHARS)
        } else {
            existing.mergedInto
        }
        val remoteSummary = MemoryLimits.clampOptionalText(remote.summary, MemoryLimits.MAX_SUMMARY_CHARS)
        val winnerSummary = if (incomingWins) {
            remoteSummary ?: existing.summary
        } else {
            existing.summary ?: remoteSummary
        }
        val mergedAliases = cleanAliasesForCanonical(existing.aliases + remote.aliases, winnerName)
        val aliasesChanged = mergedAliases != existing.aliases
        val summaryChanged = winnerSummary != existing.summary
        if (!incomingWins && !aliasesChanged && !summaryChanged) {
            return false
        }

        val cleanRemoteAliases = cleanAliasesForCanonical(remote.aliases, winnerName)
        val localContributed = !existingIsPlaceholder && (
            mergedAliases != cleanRemoteAliases ||
                (winnerSummary != null && remoteSummary == null)
            )
        val nextUpdatedAt = if (incomingWins) remote.updatedAt else existing.updatedAt
        val nextDeviceId = if (incomingWins && !localContributed) remote.updatedByDeviceId else existing.updatedByDeviceId
        val nextRevision = maxOf(existing.revision, remote.revision).coerceAtLeast(1L)
        conn.prepare(
            """
            UPDATE entities
            SET canonical_name = ?, entity_type = ?, aliases_json = ?, summary = ?,
                updated_at = ?, deleted_at = ?, revision = ?, merged_into = ?, updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, winnerName)
            stmt.bindText(2, winnerKind.wire)
            stmt.bindText(3, encodeStringList(mergedAliases))
            stmt.bindNullableText(4, winnerSummary)
            stmt.bindLong(5, nextUpdatedAt)
            stmt.bindNullableLong(6, winnerDeletedAt)
            stmt.bindLong(7, nextRevision)
            stmt.bindNullableText(8, winnerMergedInto)
            stmt.bindNullableText(9, nextDeviceId)
            stmt.bindText(10, existing.id)
            stmt.step()
        }
        if (winnerMergedInto != null && winnerDeletedAt != null) {
            retargetMergedEntitySync(
                conn = conn,
                victimEntityId = existing.id,
                victimCanonicalName = winnerName,
                victimAliases = mergedAliases,
                targetName = winnerMergedInto,
                nowMs = nowMs,
            )
        } else {
            refreshFtsForEntity(conn, existing.id)
        }
        return true
    }

    fun applyRemoteMemory(conn: SQLiteConnection, remote: MemorySyncRecord, nowMs: Long): Boolean {
        val cleanContent = remote.content.trim()
        if (cleanContent.isEmpty()) return false
        val existingMeta = conn.prepare(
            "SELECT updated_at, updated_by_device_id, revision FROM memories WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, remote.id)
            if (stmt.step()) {
                Triple(stmt.getLong(0), stmt.getNullableText(1), stmt.getLong(2))
            } else {
                null
            }
        }
        if (existingMeta != null) {
            val wins = isNewerSyncTuple(
                incomingUpdatedAt = remote.updatedAt,
                incomingDeviceId = remote.updatedByDeviceId.orEmpty(),
                incomingRevision = remote.revision,
                existingUpdatedAt = existingMeta.first,
                existingDeviceId = existingMeta.second.orEmpty(),
                existingRevision = existingMeta.third,
            )
            if (!wins) return false
        }

        val cleanSummary = remote.summary?.trim()?.takeIf { it.isNotEmpty() }
        val cleanTags = cleanStringList(remote.tags)
        conn.prepare(
            """
            INSERT INTO memories(
                id, kind, content, summary, tags_json, confidence, importance, pinned,
                valid_from, valid_until, status, superseded_by, source_kind,
                source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                kind = excluded.kind,
                content = excluded.content,
                summary = excluded.summary,
                tags_json = excluded.tags_json,
                confidence = excluded.confidence,
                importance = excluded.importance,
                pinned = excluded.pinned,
                valid_from = excluded.valid_from,
                valid_until = excluded.valid_until,
                status = excluded.status,
                superseded_by = excluded.superseded_by,
                source_kind = excluded.source_kind,
                source_session_id = excluded.source_session_id,
                source_entry_id = excluded.source_entry_id,
                source_quote = excluded.source_quote,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                revision = excluded.revision,
                updated_by_device_id = excluded.updated_by_device_id
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, remote.id)
            stmt.bindText(2, remote.kind.wire)
            stmt.bindText(3, cleanContent)
            stmt.bindNullableText(4, cleanSummary)
            stmt.bindText(5, encodeStringList(cleanTags))
            stmt.bindText(6, remote.confidence.wire)
            stmt.bindLong(7, remote.importance.coerceIn(1, 5).toLong())
            stmt.bindLong(8, if (remote.pinned) 1L else 0L)
            stmt.bindLong(9, remote.validFrom)
            stmt.bindNullableLong(10, remote.validUntil)
            stmt.bindText(11, remote.status.wire)
            stmt.bindNullableText(12, remote.supersededBy?.trim()?.takeIf { it.isNotEmpty() })
            stmt.bindText(13, remote.sourceKind.wire)
            stmt.bindNullableText(14, remote.sourceSessionId?.trim()?.takeIf { it.isNotEmpty() })
            stmt.bindNullableText(15, remote.sourceEntryId?.trim()?.takeIf { it.isNotEmpty() })
            stmt.bindNullableText(16, remote.sourceQuote?.trim()?.takeIf { it.isNotEmpty() })
            stmt.bindLong(17, remote.createdAt)
            stmt.bindLong(18, remote.updatedAt)
            stmt.bindNullableLong(19, remote.deletedAt)
            stmt.bindLong(20, remote.revision.coerceAtLeast(1L))
            stmt.bindNullableText(21, remote.updatedByDeviceId)
            stmt.step()
        }

        conn.prepare("DELETE FROM memory_entities WHERE memory_id = ?").use { stmt ->
            stmt.bindText(1, remote.id)
            stmt.step()
        }
        for (rawEntity in remote.entities) {
            val cleanName = rawEntity.trim()
            if (cleanName.isEmpty()) continue
            val entityId = ensureRemoteEntityByName(conn, cleanName, nowMs)
            conn.prepare("INSERT OR IGNORE INTO memory_entities(memory_id, entity_id) VALUES (?, ?)").use { stmt ->
                stmt.bindText(1, remote.id)
                stmt.bindText(2, entityId)
                stmt.step()
            }
        }
        if (remote.deletedAt != null) {
            deleteFts(conn, remote.id)
        } else {
            upsertFts(
                conn,
                remote.id,
                cleanContent,
                cleanSummary,
                cleanTags,
                entitySearchTermsForMemory(conn, remote.id),
            )
        }
        return true
    }

    private fun readMemorySyncRecord(conn: SQLiteConnection, id: String, fallbackDeviceId: String): MemorySyncRecord? {
        val row = conn.prepare(
            """
            SELECT $MEMORY_COLUMNS, deleted_at, updated_by_device_id
            FROM memories
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) null
            else readMemoryRow(stmt) to (stmt.getNullableLong(21) to stmt.getNullableText(22))
        } ?: return null
        val base = row.first
        val cleanId = MemoryLimits.clampText(base.id, MemoryLimits.MAX_ID_LENGTH)
        val cleanContent = MemoryLimits.clampText(base.content, MemoryLimits.MAX_CONTENT_CHARS)
        if (cleanId.isEmpty() || cleanContent.isEmpty()) return null
        val deletedAt = row.second.first?.coerceAtLeast(0L)
        val updatedBy = MemoryLimits.clampOptionalText(row.second.second, MemoryLimits.MAX_ID_LENGTH)
            ?: MemoryLimits.clampText(fallbackDeviceId, MemoryLimits.MAX_ID_LENGTH)
        val entities = cleanEntityNames(
            items = entityNamesForMemory(conn, base.id),
            maxItems = MemoryLimits.MAX_ENTITIES_PER_MEMORY,
            maxChars = MemoryLimits.MAX_ENTITY_NAME_CHARS,
        )
        return MemorySyncRecord(
            id = cleanId,
            kind = base.kind,
            content = cleanContent,
            summary = MemoryLimits.clampOptionalText(base.summary, MemoryLimits.MAX_SUMMARY_CHARS),
            tags = cleanStringList(base.tags),
            entities = entities,
            confidence = base.confidence,
            importance = base.importance.coerceIn(1, 5),
            pinned = base.pinned,
            validFrom = base.validFrom.coerceAtLeast(0L),
            validUntil = base.validUntil?.coerceAtLeast(0L),
            status = base.status,
            supersededBy = MemoryLimits.clampOptionalText(base.supersededBy, MemoryLimits.MAX_ID_LENGTH),
            sourceKind = base.sourceKind,
            sourceSessionId = MemoryLimits.clampOptionalText(base.sourceSessionId, MemoryLimits.MAX_ID_LENGTH),
            sourceEntryId = MemoryLimits.clampOptionalText(base.sourceEntryId, MemoryLimits.MAX_ID_LENGTH),
            sourceQuote = MemoryLimits.clampOptionalText(base.sourceQuote, MemoryLimits.MAX_QUOTE_CHARS),
            createdAt = base.createdAt.coerceAtLeast(0L),
            updatedAt = base.updatedAt.coerceAtLeast(0L),
            deletedAt = deletedAt,
            revision = base.revision.coerceAtLeast(1L),
            updatedByDeviceId = updatedBy,
        )
    }

    private fun readEntitySyncRecord(conn: SQLiteConnection, id: String, fallbackDeviceId: String): EntitySyncRecord? =
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, summary, merged_into,
                   updated_at, deleted_at, revision, updated_by_device_id
            FROM entities
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) null
            else {
                val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
                val canonicalName = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_ENTITY_NAME_CHARS)
                if (cleanId.isEmpty() || canonicalName.isEmpty()) {
                    null
                } else {
                    EntitySyncRecord(
                        canonicalKey = canonicalName.lowercase(),
                        id = cleanId,
                        canonicalName = canonicalName,
                        kind = EntityKind.fromWire(stmt.getText(2)),
                        aliases = cleanAliasesForCanonical(decodeStringList(stmt.getText(3)), canonicalName),
                        summary = MemoryLimits.clampOptionalText(stmt.getNullableText(4), MemoryLimits.MAX_SUMMARY_CHARS),
                        mergedInto = MemoryLimits.clampOptionalText(stmt.getNullableText(5), MemoryLimits.MAX_ENTITY_NAME_CHARS),
                        updatedAt = stmt.getLong(6).coerceAtLeast(0L),
                        deletedAt = stmt.getNullableLong(7)?.coerceAtLeast(0L),
                        revision = stmt.getLong(8).coerceAtLeast(1L),
                        updatedByDeviceId = MemoryLimits.clampOptionalText(stmt.getNullableText(9), MemoryLimits.MAX_ID_LENGTH)
                            ?: MemoryLimits.clampText(fallbackDeviceId, MemoryLimits.MAX_ID_LENGTH),
                    )
                }
            }
        }

    private data class LocalEntityRow(
        val id: String,
        val canonicalName: String,
        val kind: EntityKind,
        val aliases: List<String>,
        val summary: String?,
        val updatedAt: Long,
        val deletedAt: Long?,
        val revision: Long,
        val mergedInto: String?,
        val updatedByDeviceId: String?,
    )

    private fun findEntityRowForSync(conn: SQLiteConnection, canonicalKey: String, id: String): LocalEntityRow? {
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, summary,
                   updated_at, deleted_at, revision, merged_into, updated_by_device_id
            FROM entities
            WHERE LOWER(canonical_name) = ?
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, canonicalKey)
            if (stmt.step()) return readLocalEntityRow(stmt)
        }
        return conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, summary,
                   updated_at, deleted_at, revision, merged_into, updated_by_device_id
            FROM entities
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readLocalEntityRow(stmt) else null
        }
    }

    private fun readLocalEntityRow(stmt: SQLiteStatement): LocalEntityRow = LocalEntityRow(
        id = stmt.getText(0),
        canonicalName = stmt.getText(1),
        kind = EntityKind.fromWire(stmt.getText(2)),
        aliases = decodeStringList(stmt.getText(3)),
        summary = stmt.getNullableText(4),
        updatedAt = stmt.getLong(5),
        deletedAt = stmt.getNullableLong(6),
        revision = stmt.getLong(7),
        mergedInto = stmt.getNullableText(8),
        updatedByDeviceId = stmt.getNullableText(9),
    )

    private fun ensureRemoteEntityByName(conn: SQLiteConnection, name: String, nowMs: Long): String {
        val cleanName = name.trim()
        val entities = listActiveEntities(conn)
        val matched = entities.firstOrNull { entity ->
            entity.canonicalName.equals(cleanName, ignoreCase = true) ||
                entity.aliases.any { it.equals(cleanName, ignoreCase = true) }
        }
        if (matched != null) return matched.id

        val mergedTarget = conn.prepare(
            "SELECT merged_into FROM entities WHERE LOWER(canonical_name) = ? AND deleted_at IS NOT NULL AND merged_into IS NOT NULL LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, cleanName.lowercase())
            if (stmt.step()) stmt.getNullableText(0) else null
        }
        if (!mergedTarget.isNullOrBlank() && !mergedTarget.equals(cleanName, ignoreCase = true)) {
            return ensureRemoteEntityByName(conn, mergedTarget, nowMs)
        }

        val newId = UUID.randomUUID().toString()
        val boundedName = MemoryLimits.clampText(cleanName, MemoryLimits.MAX_ENTITY_NAME_CHARS)
        conn.prepare(
            """
            INSERT INTO entities(id, canonical_name, entity_type, aliases_json, summary, updated_at, deleted_at, revision, merged_into, updated_by_device_id)
            VALUES (?, ?, ?, '[]', NULL, 0, NULL, 0, NULL, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, newId)
            stmt.bindText(2, boundedName)
            stmt.bindText(3, EntityKind.TOPIC.wire)
            stmt.bindText(4, PLACEHOLDER_DEVICE_ID)
            stmt.step()
        }
        return newId
    }

    private fun isPlaceholderEntity(conn: SQLiteConnection, entityId: String): Boolean =
        conn.prepare("SELECT revision, updated_by_device_id FROM entities WHERE id = ? LIMIT 1").use { stmt ->
            stmt.bindText(1, entityId)
            if (!stmt.step()) false
            else stmt.getLong(0) <= 0L || stmt.getNullableText(1) == PLACEHOLDER_DEVICE_ID
        }

    private fun retargetMergedEntitySync(
        conn: SQLiteConnection,
        victimEntityId: String,
        victimCanonicalName: String,
        victimAliases: List<String>,
        targetName: String,
        nowMs: Long,
    ) {
        val cleanTarget = targetName.trim()
        if (cleanTarget.isEmpty() || cleanTarget.equals(victimCanonicalName, ignoreCase = true)) return
        val keeperId = ensureRemoteEntityByName(conn, cleanTarget, nowMs)
        if (keeperId == victimEntityId) return

        val affectedMemories = ArrayList<String>()
        conn.prepare("SELECT memory_id FROM memory_entities WHERE entity_id = ?").use { stmt ->
            stmt.bindText(1, victimEntityId)
            while (stmt.step()) affectedMemories += stmt.getText(0)
        }
        for (memoryId in affectedMemories) {
            conn.prepare("INSERT OR IGNORE INTO memory_entities(memory_id, entity_id) VALUES (?, ?)").use { stmt ->
                stmt.bindText(1, memoryId)
                stmt.bindText(2, keeperId)
                stmt.step()
            }
        }
        conn.prepare("DELETE FROM memory_entities WHERE entity_id = ?").use { stmt ->
            stmt.bindText(1, victimEntityId)
            stmt.step()
        }

        val keeperRow = conn.prepare(
            "SELECT canonical_name, aliases_json FROM entities WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, keeperId)
            if (stmt.step()) stmt.getText(0) to decodeStringList(stmt.getText(1)) else null
        }
        if (keeperRow != null) {
            val merged = cleanAliasesForCanonical(keeperRow.second + victimCanonicalName + victimAliases, keeperRow.first)
            if (merged != keeperRow.second) {
                conn.prepare("UPDATE entities SET aliases_json = ? WHERE id = ?").use { stmt ->
                    stmt.bindText(1, encodeStringList(merged))
                    stmt.bindText(2, keeperId)
                    stmt.step()
                }
            }
        }
        refreshFtsForEntity(conn, keeperId)
    }

    private fun cleanAliasesForCanonical(aliases: List<String>, canonicalName: String): List<String> =
        cleanEntityNames(
            items = aliases.filterNot { it.trim().equals(canonicalName.trim(), ignoreCase = true) },
            maxItems = MemoryLimits.MAX_ALIASES_PER_ENTITY,
            maxChars = MemoryLimits.MAX_ENTITY_NAME_CHARS,
        )

    private fun isNewerSyncTuple(
        incomingUpdatedAt: Long,
        incomingDeviceId: String,
        incomingRevision: Long,
        existingUpdatedAt: Long,
        existingDeviceId: String,
        existingRevision: Long,
    ): Boolean {
        if (incomingUpdatedAt != existingUpdatedAt) return incomingUpdatedAt > existingUpdatedAt
        if (incomingDeviceId != existingDeviceId) return incomingDeviceId > existingDeviceId
        return incomingRevision > existingRevision
    }

    private fun normalizeExactDedup(content: String): String =
        content.trim().replace(WHITESPACE_REGEX, " ").lowercase()

    private val WHITESPACE_REGEX = Regex("\\s+")

    fun readWatermark(conn: SQLiteConnection, sessionId: String): IngestionWatermark? =
        conn.prepare(
            "SELECT session_id, session_file, last_entry_id, last_hash, updated_at FROM ingestion_watermarks WHERE session_id = ?",
        ).use { stmt ->
            stmt.bindText(1, sessionId)
            if (!stmt.step()) null
            else IngestionWatermark(
                sessionId = stmt.getText(0),
                sessionFile = stmt.getNullableText(1),
                lastEntryId = stmt.getNullableText(2),
                lastHash = stmt.getNullableText(3),
                updatedAt = stmt.getLong(4),
            )
        }

    fun saveWatermark(conn: SQLiteConnection, watermark: IngestionWatermark) {
        conn.prepare(
            """
            INSERT INTO ingestion_watermarks(session_id, session_file, last_entry_id, last_hash, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                session_file = excluded.session_file,
                last_entry_id = excluded.last_entry_id,
                last_hash = excluded.last_hash,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, watermark.sessionId)
            stmt.bindNullableText(2, watermark.sessionFile)
            stmt.bindNullableText(3, watermark.lastEntryId)
            stmt.bindNullableText(4, watermark.lastHash)
            stmt.bindLong(5, watermark.updatedAt)
            stmt.step()
        }
    }

    fun savePendingIngestion(conn: SQLiteConnection, pending: PendingIngestionRecord) {
        conn.prepare(
            """
            INSERT INTO pending_ingestions(session_id, session_file, transcript, last_entry_id, reason, attempts, created_at, last_error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                session_file = excluded.session_file,
                transcript = excluded.transcript,
                last_entry_id = excluded.last_entry_id,
                reason = excluded.reason,
                attempts = excluded.attempts,
                created_at = excluded.created_at,
                last_error = excluded.last_error
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, pending.sessionId)
            stmt.bindNullableText(2, pending.sessionFile)
            stmt.bindText(3, pending.transcript)
            stmt.bindNullableText(4, pending.lastEntryId)
            stmt.bindText(5, pending.reason)
            stmt.bindLong(6, pending.attempts.toLong())
            stmt.bindLong(7, pending.createdAt)
            stmt.bindNullableText(8, pending.lastError)
            stmt.step()
        }
    }

    fun listPendingIngestions(conn: SQLiteConnection, maxAttempts: Int = 5): List<PendingIngestionRecord> {
        val result = ArrayList<PendingIngestionRecord>()
        conn.prepare(
            """
            SELECT session_id, session_file, transcript, last_entry_id, reason, attempts, created_at, last_error
            FROM pending_ingestions
            WHERE attempts < ?
            ORDER BY created_at ASC
            LIMIT 30
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            while (stmt.step()) {
                result += PendingIngestionRecord(
                    sessionId = stmt.getText(0),
                    sessionFile = stmt.getNullableText(1),
                    transcript = stmt.getText(2),
                    lastEntryId = stmt.getNullableText(3),
                    reason = stmt.getText(4),
                    attempts = stmt.getInt(5),
                    createdAt = stmt.getLong(6),
                    lastError = stmt.getNullableText(7),
                )
            }
        }
        return result
    }

    fun hasPendingIngestion(conn: SQLiteConnection, sessionId: String): Boolean =
        conn.prepare("SELECT 1 FROM pending_ingestions WHERE session_id = ? LIMIT 1").use { stmt ->
            stmt.bindText(1, sessionId)
            stmt.step()
        }

    fun deletePendingIngestion(conn: SQLiteConnection, sessionId: String) {
        conn.prepare("DELETE FROM pending_ingestions WHERE session_id = ?").use { stmt ->
            stmt.bindText(1, sessionId)
            stmt.step()
        }
    }

    fun pruneExhaustedPendingIngestions(conn: SQLiteConnection, maxAttempts: Int) {
        conn.prepare("DELETE FROM pending_ingestions WHERE attempts >= ?").use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            stmt.step()
        }
    }

    fun recordDreamRun(conn: SQLiteConnection, summary: DreamRunSummary) {
        conn.prepare(
            """
            INSERT INTO dream_runs(
                id, started_at, finished_at, status, model_ref,
                promoted_count, superseded_count, expired_count, merged_entities, error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, summary.id)
            stmt.bindLong(2, summary.startedAt)
            stmt.bindNullableLong(3, summary.finishedAt)
            stmt.bindText(4, summary.status)
            stmt.bindNullableText(5, summary.modelRef)
            stmt.bindLong(6, summary.promotedCount.toLong())
            stmt.bindLong(7, summary.supersededCount.toLong())
            stmt.bindLong(8, summary.expiredCount.toLong())
            stmt.bindLong(9, summary.mergedEntities.toLong())
            stmt.bindNullableText(10, summary.error)
            stmt.step()
        }
    }

    fun countMemoriesCreatedAfter(conn: SQLiteConnection, sinceMs: Long): Int =
        conn.prepare("SELECT COUNT(*) FROM memories WHERE deleted_at IS NULL AND created_at > ?").use { stmt ->
            stmt.bindLong(1, sinceMs)
            if (stmt.step()) stmt.getInt(0) else 0
        }

    fun lastSuccessfulDreamAt(conn: SQLiteConnection): Long? =
        conn.prepare("SELECT MAX(finished_at) FROM dream_runs WHERE status = 'DONE'").use { stmt ->
            if (stmt.step() && !stmt.isNull(0)) stmt.getLong(0) else null
        }

    private fun refreshFtsForEntity(conn: SQLiteConnection, entityId: String) {
        val ids = ArrayList<String>()
        conn.prepare("SELECT memory_id FROM memory_entities WHERE entity_id = ?").use { stmt ->
            stmt.bindText(1, entityId)
            while (stmt.step()) ids += stmt.getText(0)
        }
        for (memoryId in ids) {
            findById(conn, memoryId, includeDeleted = false)?.let { record ->
                val tokens = entitySearchTermsForMemory(conn, record.id)
                upsertFts(conn, record.id, record.content, record.summary, record.tags, tokens)
            }
        }
    }

    private fun upsertFts(
        conn: SQLiteConnection,
        memoryId: String,
        content: String,
        summary: String?,
        tags: List<String>,
        entities: List<String>,
    ) {
        deleteFts(conn, memoryId)
        val combinedText = listOfNotNull(content, summary).joinToString(" ")
        conn.prepare(
            "INSERT INTO memories_fts(memory_id, content_tokens, tag_tokens, entity_tokens) VALUES (?, ?, ?, ?)",
        ).use { stmt ->
            stmt.bindText(1, memoryId)
            stmt.bindText(2, MemoryTokenizer.indexText(combinedText))
            stmt.bindText(3, MemoryTokenizer.indexText(tags.joinToString(" ")))
            stmt.bindText(4, MemoryTokenizer.indexText(entities.joinToString(" ")))
            stmt.step()
        }
    }

    private fun deleteFts(conn: SQLiteConnection, memoryId: String) {
        conn.prepare("DELETE FROM memories_fts WHERE memory_id = ?").use { stmt ->
            stmt.bindText(1, memoryId)
            stmt.step()
        }
    }

    private fun recordSyncOp(
        conn: SQLiteConnection,
        entityKind: String,
        entityId: String,
        op: String,
        revision: Long,
        nowMs: Long,
    ) {
        conn.prepare(
            "INSERT INTO sync_ops(entity_kind, entity_id, op, revision, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { stmt ->
            stmt.bindText(1, entityKind)
            stmt.bindText(2, entityId)
            stmt.bindText(3, op)
            stmt.bindLong(4, revision)
            stmt.bindLong(5, nowMs)
            stmt.step()
        }
    }

    private fun entityRevision(conn: SQLiteConnection, entityId: String): Long =
        conn.prepare("SELECT revision FROM entities WHERE id = ?").use { stmt ->
            stmt.bindText(1, entityId)
            if (stmt.step()) stmt.getLong(0) else 1L
        }

    private fun attachEntities(conn: SQLiteConnection, records: List<MemoryRecord>): List<MemoryRecord> {
        if (records.isEmpty()) return emptyList()
        val ids = records.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val byMemory = HashMap<String, MutableList<String>>()
        conn.prepare(
            """
            SELECT me.memory_id, e.canonical_name
            FROM memory_entities me
            JOIN entities e ON e.id = me.entity_id
            WHERE me.memory_id IN ($placeholders) AND e.deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            ids.forEachIndexed { index, id -> stmt.bindText(index + 1, id) }
            while (stmt.step()) {
                byMemory.getOrPut(stmt.getText(0)) { ArrayList() }.add(stmt.getText(1))
            }
        }
        return records.map { record ->
            record.copy(entities = byMemory[record.id].orEmpty())
        }
    }

    private fun entityNamesForMemory(conn: SQLiteConnection, memoryId: String): List<String> {
        val result = ArrayList<String>()
        conn.prepare(
            """
            SELECT e.canonical_name
            FROM memory_entities me
            JOIN entities e ON e.id = me.entity_id
            WHERE me.memory_id = ? AND e.deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, memoryId)
            while (stmt.step()) result += stmt.getText(0)
        }
        return result
    }

    private fun entitySearchTermsForMemory(conn: SQLiteConnection, memoryId: String): List<String> {
        val result = LinkedHashSet<String>()
        conn.prepare(
            """
            SELECT e.canonical_name, e.aliases_json
            FROM memory_entities me
            JOIN entities e ON e.id = me.entity_id
            WHERE me.memory_id = ? AND e.deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, memoryId)
            while (stmt.step()) {
                result += stmt.getText(0)
                result.addAll(decodeStringList(stmt.getText(1)))
            }
        }
        return result.toList()
    }

    private fun readMemoryRow(stmt: SQLiteStatement): MemoryRecord = MemoryRecord(
        id = stmt.getText(0),
        kind = MemoryKind.fromWire(stmt.getText(1)) ?: MemoryKind.EPISODIC,
        content = stmt.getText(2),
        summary = stmt.getNullableText(3),
        tags = decodeStringList(stmt.getText(4)),
        entities = emptyList(),
        confidence = MemoryConfidence.fromWire(stmt.getText(5)),
        importance = stmt.getInt(6),
        pinned = stmt.getInt(7) != 0,
        validFrom = stmt.getLong(8),
        validUntil = stmt.getNullableLong(9),
        status = MemoryItemStatus.fromWire(stmt.getText(10)),
        supersededBy = stmt.getNullableText(11),
        sourceKind = MemorySourceKind.fromWire(stmt.getText(12)),
        sourceSessionId = stmt.getNullableText(13),
        sourceEntryId = stmt.getNullableText(14),
        sourceQuote = stmt.getNullableText(15),
        accessCount = stmt.getInt(16),
        lastAccessedAt = stmt.getNullableLong(17),
        createdAt = stmt.getLong(18),
        updatedAt = stmt.getLong(19),
        revision = stmt.getLong(20),
    )

    internal fun cleanStringList(
        items: List<String>,
        maxItems: Int = MemoryLimits.MAX_TAGS_PER_MEMORY,
        maxChars: Int = MemoryLimits.MAX_TAG_CHARS,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in items) {
            val clean = MemoryLimits.clampText(raw.trim().removePrefix("#"), maxChars)
            if (clean.isNotEmpty() && seen.none { it.equals(clean, ignoreCase = true) }) {
                seen += clean
                if (seen.size >= maxItems) break
            }
        }
        return seen.toList()
    }

    internal fun cleanEntityNames(
        items: List<String>,
        maxItems: Int = MemoryLimits.MAX_ENTITIES_PER_MEMORY,
        maxChars: Int = MemoryLimits.MAX_ENTITY_NAME_CHARS,
    ): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in items) {
            val clean = MemoryLimits.clampText(raw, maxChars)
            if (clean.isNotEmpty() && seen.none { it.equals(clean, ignoreCase = true) }) {
                seen += clean
                if (seen.size >= maxItems) break
            }
        }
        return seen.toList()
    }

    internal fun encodeStringList(items: List<String>): String =
        JsonArray(items.map { JsonPrimitive(it) }).toString()

    internal fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSON.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { item -> item.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }

    private fun bindDynamicArgs(stmt: SQLiteStatement, args: List<Any>) {
        args.forEachIndexed { index, value ->
            val col = index + 1
            when (value) {
                is String -> stmt.bindText(col, value)
                is Long -> stmt.bindLong(col, value)
                is Int -> stmt.bindLong(col, value.toLong())
                is Double -> stmt.bindDouble(col, value)
                else -> stmt.bindText(col, value.toString())
            }
        }
    }

    private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun SQLiteStatement.getNullableText(index: Int): String? =
        if (isNull(index)) null else getText(index)

    private fun SQLiteStatement.getNullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private const val MEMORY_COLUMNS =
        "id, kind, content, summary, tags_json, confidence, importance, pinned, " +
            "valid_from, valid_until, status, superseded_by, source_kind, " +
            "source_session_id, source_entry_id, source_quote, access_count, last_accessed_at, " +
            "created_at, updated_at, revision"
}

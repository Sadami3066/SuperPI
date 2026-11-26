package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryAgentRunSummary
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryOverview
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.MemoryTagCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MemoryStorage {
    private const val KEY_PAUSE_WINDOWS = "pause_windows"
    private const val MAX_PAUSE_WINDOWS = 50
    private const val PAUSE_RETENTION_MS = 90L * MemoryLimits.MS_PER_DAY
    private const val DEFAULT_STALE_INFERRED_DAYS = 45L
    private const val DEFAULT_RUN_HISTORY_KEEP = 100
    private val MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM")

    fun insertEvent(
        conn: SQLiteConnection,
        draft: MemoryEventDraft,
        nowMs: Long,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        runId: String? = null,
        userSummary: String? = null,
        deviceId: String? = null,
    ): MemoryEvent {
        val id = UUID.randomUUID().toString()
        val cleanSummary = MemoryLimits.clampOptionalText(draft.summary, MemoryLimits.MAX_SUMMARY_CHARS)
        val cleanContent = MemoryLimits.clampText(draft.content, MemoryLimits.MAX_CONTENT_CHARS)
            .ifEmpty { cleanSummary ?: "-" }
        val cleanKeywords = cleanStringList(draft.semanticKeywords, MemoryLimits.MAX_KEYWORDS_PER_EVENT, MemoryLimits.MAX_KEYWORD_CHARS)
        val cleanTags = cleanStringList(draft.tags, MemoryLimits.MAX_TAGS_PER_EVENT, MemoryLimits.MAX_TAG_CHARS)
        val cleanDomain = MemoryDomains.normalize(draft.domain, MemoryDomains.PERSONAL)
        val importance = draft.importance.coerceIn(1, 5)
        val effectivePinned = draft.pinned || draft.epistemicTier == MemoryEpistemicTier.USER_PINNED
        val effectiveTier = if (effectivePinned) MemoryEpistemicTier.USER_PINNED else draft.epistemicTier
        val eventTime = (draft.eventTime ?: nowMs).coerceAtLeast(0L)
        val validFrom = (draft.validFrom ?: eventTime).coerceAtLeast(0L)

        conn.prepare(
            """
            INSERT INTO memory_events(
                id, event_kind, content, summary, qualifiers_json, semantic_keywords_json,
                tags_json, domain, sensitivity, epistemic_tier, importance, pinned,
                event_time, event_time_precision, valid_from, valid_until, status, superseded_by,
                source_kind, source_session_id, source_entry_id, source_quote,
                access_count, last_accessed_at, activation_score,
                created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', NULL, ?, ?, ?, ?, 0, NULL, 1.0, ?, ?, NULL, 1, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, draft.eventKind.wire)
            stmt.bindText(3, cleanContent)
            stmt.bindNullableText(4, cleanSummary)
            stmt.bindText(5, MemoryJsonSupport.encodeStringMap(draft.qualifiers))
            stmt.bindText(6, MemoryJsonSupport.encodeStringList(cleanKeywords))
            stmt.bindText(7, MemoryJsonSupport.encodeStringList(cleanTags))
            stmt.bindText(8, cleanDomain)
            stmt.bindText(9, draft.sensitivity.wire)
            stmt.bindText(10, effectiveTier.wire)
            stmt.bindLong(11, importance.toLong())
            stmt.bindLong(12, if (effectivePinned) 1L else 0L)
            stmt.bindLong(13, eventTime)
            stmt.bindText(14, draft.eventTimePrecision.wire)
            stmt.bindLong(15, validFrom)
            stmt.bindNullableLong(16, draft.validUntil?.coerceAtLeast(0L))
            stmt.bindText(17, draft.sourceKind.wire)
            stmt.bindNullableText(18, MemoryLimits.clampOptionalText(draft.sourceSessionId, MemoryLimits.MAX_ID_LENGTH))
            stmt.bindNullableText(19, MemoryLimits.clampOptionalText(draft.sourceEntryId, MemoryLimits.MAX_ID_LENGTH))
            stmt.bindNullableText(20, MemoryLimits.clampOptionalText(draft.sourceQuote, MemoryLimits.MAX_QUOTE_CHARS))
            stmt.bindLong(21, nowMs)
            stmt.bindLong(22, nowMs)
            stmt.bindNullableText(23, deviceId)
            stmt.step()
        }

        val entityNames = linkEntitiesToEvent(conn, id, draft.entities, cleanDomain, draft.sensitivity, actor, nowMs, runId, deviceId)
        upsertFts(conn, id, cleanContent, cleanSummary, cleanKeywords, cleanTags, entityNames)
        MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
        if (userSummary != null) {
            MemoryAuditSupport.recordAudit(
                conn = conn,
                runId = runId,
                actor = actor,
                actionType = "RECORD_EVENT",
                targetTable = "memory_events",
                targetId = id,
                summaryUserText = userSummary,
                reason = null,
                beforeSnapshotJson = null,
                afterSnapshotJson = MemoryAuditSupport.eventSnapshotJson(conn, id),
                nowMs = nowMs,
            )
        }
        invalidateContextCache(conn)
        return checkNotNull(findEventById(conn, id))
    }

    fun updateEventDirect(
        conn: SQLiteConnection,
        id: String,
        eventKind: MemoryEventKind? = null,
        content: String? = null,
        summary: String? = null,
        domain: String? = null,
        sensitivity: MemorySensitivity? = null,
        tags: List<String>? = null,
        epistemicTier: MemoryEpistemicTier? = null,
        pinned: Boolean? = null,
        eventTime: Long? = null,
        validUntil: Long? = null,
        clearValidUntil: Boolean = false,
        status: MemoryItemStatus? = null,
        nowMs: Long,
        deviceId: String? = null,
    ): MemoryEvent? {
        val existing = findEventById(conn, id) ?: return null
        val beforeJson = MemoryAuditSupport.eventSnapshotJson(conn, id)
        val nextKind = eventKind ?: existing.eventKind
        val nextContent = content?.let { MemoryLimits.clampText(it, MemoryLimits.MAX_CONTENT_CHARS) }
            ?.takeIf { it.isNotEmpty() } ?: existing.content
        val nextSummary = if (summary != null) {
            MemoryLimits.clampOptionalText(summary, MemoryLimits.MAX_SUMMARY_CHARS)
        } else {
            existing.summary
        }
        val nextDomain = domain?.let { MemoryDomains.normalize(it, existing.domain) } ?: existing.domain
        val nextSensitivity = sensitivity ?: existing.sensitivity
        val nextTags = tags?.let { cleanStringList(it, MemoryLimits.MAX_TAGS_PER_EVENT, MemoryLimits.MAX_TAG_CHARS) }
            ?: existing.tags
        val nextPinned = pinned ?: existing.pinned
        val nextTier = when {
            nextPinned -> MemoryEpistemicTier.USER_PINNED
            epistemicTier != null -> epistemicTier
            existing.epistemicTier == MemoryEpistemicTier.USER_PINNED -> MemoryEpistemicTier.USER_EXPLICIT
            else -> existing.epistemicTier
        }
        val nextEventTime = eventTime ?: existing.eventTime
        val nextValidUntil = when {
            clearValidUntil -> null
            validUntil != null -> validUntil
            else -> existing.validUntil
        }
        val nextStatus = status ?: existing.status

        conn.prepare(
            """
            UPDATE memory_events
            SET event_kind = ?, content = ?, summary = ?, domain = ?, sensitivity = ?,
                tags_json = ?, epistemic_tier = ?, pinned = ?, event_time = ?,
                valid_until = ?, status = ?, updated_at = ?, revision = revision + 1,
                updated_by_device_id = ?
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, nextKind.wire)
            stmt.bindText(2, nextContent)
            stmt.bindNullableText(3, nextSummary)
            stmt.bindText(4, nextDomain)
            stmt.bindText(5, nextSensitivity.wire)
            stmt.bindText(6, MemoryJsonSupport.encodeStringList(nextTags))
            stmt.bindText(7, nextTier.wire)
            stmt.bindLong(8, if (nextPinned) 1L else 0L)
            stmt.bindLong(9, nextEventTime)
            stmt.bindNullableLong(10, nextValidUntil)
            stmt.bindText(11, nextStatus.wire)
            stmt.bindLong(12, nowMs)
            stmt.bindNullableText(13, deviceId)
            stmt.bindText(14, id)
            stmt.step()
        }

        refreshEventFts(conn, id)
        MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = null,
            actor = MemoryMutationActor.USER,
            actionType = "UPDATE_EVENT",
            targetTable = "memory_events",
            targetId = id,
            summaryUserText = "更新了一条记忆记录",
            reason = null,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = MemoryAuditSupport.eventSnapshotJson(conn, id),
            nowMs = nowMs,
        )
        invalidateContextCache(conn)
        return findEventById(conn, id)
    }

    fun softDeleteEvent(
        conn: SQLiteConnection,
        id: String,
        actor: MemoryMutationActor = MemoryMutationActor.USER,
        runId: String? = null,
        nowMs: Long,
        userSummary: String = "删除了一条记忆记录",
        deviceId: String? = null,
    ): Boolean {
        val existing = findEventById(conn, id) ?: return false
        if (actor != MemoryMutationActor.USER && (existing.pinned || existing.epistemicTier == MemoryEpistemicTier.USER_PINNED)) {
            return false
        }
        val beforeJson = MemoryAuditSupport.eventSnapshotJson(conn, id)
        conn.prepare(
            "UPDATE memory_events SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindLong(2, nowMs)
            stmt.bindNullableText(3, deviceId)
            stmt.bindText(4, id)
            stmt.step()
        }
        conn.prepare("DELETE FROM memory_fts WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "DELETE_EVENT",
            targetTable = "memory_events",
            targetId = id,
            summaryUserText = userSummary,
            reason = null,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = MemoryAuditSupport.eventSnapshotJson(conn, id),
            nowMs = nowMs,
        )
        invalidateContextCache(conn)
        return true
    }

    fun countEventsBySession(conn: SQLiteConnection, sessionId: String): Int {
        val clean = sessionId.trim()
        if (clean.isEmpty()) return 0
        return conn.prepare(
            "SELECT COUNT(*) FROM memory_events WHERE source_session_id = ? AND deleted_at IS NULL",
        ).use { stmt ->
            stmt.bindText(1, clean)
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
    }

    fun restoreEvent(conn: SQLiteConnection, id: String, nowMs: Long, deviceId: String? = null): Boolean {
        val existing = findEventById(conn, id, includeDeleted = true) ?: return false
        if (existing.status == MemoryItemStatus.ACTIVE) {
            var isDeleted = false
            conn.prepare("SELECT deleted_at FROM memory_events WHERE id = ? LIMIT 1").use { stmt ->
                stmt.bindText(1, id)
                if (stmt.step()) isDeleted = !stmt.isNull(0)
            }
            if (!isDeleted) return false
        }
        conn.prepare(
            "UPDATE memory_events SET deleted_at = NULL, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ? AND deleted_at IS NOT NULL",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindNullableText(2, deviceId)
            stmt.bindText(3, id)
            stmt.step()
        }
        val restored = conn.prepare("SELECT changes()").use { it.step() && it.getLong(0) > 0L }
        refreshEventFts(conn, id)
        invalidateContextCache(conn)
        if (restored) {
            MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
        }
        return findEventById(conn, id) != null
    }

    fun deleteEventsBySession(conn: SQLiteConnection, sessionId: String, nowMs: Long, deviceId: String? = null): Int {
        val clean = sessionId.trim()
        if (clean.isEmpty()) return 0
        val ids = ArrayList<String>()
        conn.prepare("SELECT id FROM memory_events WHERE source_session_id = ? AND deleted_at IS NULL").use { stmt ->
            stmt.bindText(1, clean)
            while (stmt.step()) ids += stmt.getText(0)
        }
        var count = 0
        ids.forEach { id ->
            if (softDeleteEvent(conn, id, MemoryMutationActor.USER, null, nowMs, "清除了该会话产生的记忆", deviceId)) {
                count++
            }
        }
        return count
    }

    fun applyAgentCommitBatch(
        conn: SQLiteConnection,
        runId: String,
        actor: MemoryMutationActor,
        batch: MemoryAgentCommitBatch,
        nowMs: Long,
        deviceId: String? = null,
    ): CommitBatchReceipt {
        var blocksPatched = 0
        var eventsRecorded = 0
        var eventsSuperseded = 0
        var eventsForgotten = 0
        var entitiesUpserted = 0
        var relationsUpserted = 0
        var relationsInvalidated = 0
        val blocked = ArrayList<String>()

        batch.upsertEntities.forEach { op ->
            val (_, decision) = MemoryGraphStorage.upsertEntity(
                conn = conn,
                ref = MemoryEntityRef(
                    name = op.canonicalName,
                    entityType = op.entityType,
                    aliases = op.aliases,
                    attributes = op.attributes,
                    summary = op.summary,
                    domain = op.domain,
                    sensitivity = op.sensitivity,
                ),
                actor = actor,
                nowMs = nowMs,
                runId = runId,
                auditSummary = op.userSummary,
                deviceId = deviceId,
            )
            when (decision) {
                GuardDecision.Allowed -> entitiesUpserted++
                is GuardDecision.Blocked -> blocked += decision.reason
            }
        }

        batch.patchBlocks.forEach { op ->
            when (val decision = MemoryBlockStorage.patchBlock(conn, op, actor, runId, nowMs, deviceId)) {
                GuardDecision.Allowed -> blocksPatched++
                is GuardDecision.Blocked -> blocked += decision.reason
            }
        }

        batch.recordEvents.forEach { op ->
            insertEvent(
                conn = conn,
                draft = op.draft,
                nowMs = nowMs,
                actor = actor,
                runId = runId,
                userSummary = op.userSummary,
                deviceId = deviceId,
            )
            eventsRecorded++
        }

        batch.supersedeEvents.forEach { op ->
            val existing = findEventById(conn, op.oldEventId)
            if (existing == null || existing.status != MemoryItemStatus.ACTIVE) {
                blocked += "Target event '${op.oldEventId}' not active or missing"
                return@forEach
            }
            when (val guard = EpistemicGuard.canSupersedeEvent(existing, op.replacement.epistemicTier, actor)) {
                is GuardDecision.Blocked -> blocked += guard.reason
                GuardDecision.Allowed -> {
                    val created = insertEvent(
                        conn = conn,
                        draft = op.replacement,
                        nowMs = nowMs,
                        actor = actor,
                        runId = runId,
                        userSummary = null,
                        deviceId = deviceId,
                    )
                    val beforeJson = MemoryAuditSupport.eventSnapshotJson(conn, existing.id)
                    conn.prepare(
                        """
                        UPDATE memory_events
                        SET status = 'SUPERSEDED', superseded_by = ?, updated_at = ?, revision = revision + 1,
                            updated_by_device_id = ?
                        WHERE id = ?
                        """.trimIndent(),
                    ).use { stmt ->
                        stmt.bindText(1, created.id)
                        stmt.bindLong(2, nowMs)
                        stmt.bindNullableText(3, deviceId)
                        stmt.bindText(4, existing.id)
                        stmt.step()
                    }
                    MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, existing.id, nowMs)
                    MemoryAuditSupport.recordAudit(
                        conn = conn,
                        runId = runId,
                        actor = actor,
                        actionType = "SUPERSEDE_EVENT",
                        targetTable = "memory_events",
                        targetId = existing.id,
                        summaryUserText = op.userSummary,
                        reason = null,
                        beforeSnapshotJson = beforeJson,
                        afterSnapshotJson = MemoryAuditSupport.eventSnapshotJson(conn, existing.id),
                        nowMs = nowMs,
                    )
                    eventsSuperseded++
                }
            }
        }

        batch.contractEvents.forEach { op ->
            val existing = findEventById(conn, op.eventId)
            if (existing == null) {
                blocked += "Target event '${op.eventId}' not found"
                return@forEach
            }
            when (val guard = EpistemicGuard.canContractEvent(existing, op.actingTier, actor)) {
                is GuardDecision.Blocked -> blocked += guard.reason
                GuardDecision.Allowed -> {
                    if (op.softDelete) {
                        if (softDeleteEvent(conn, existing.id, actor, runId, nowMs, op.userSummary, deviceId)) {
                            eventsForgotten++
                        }
                    } else {
                        val nextStatus = op.newStatus ?: MemoryItemStatus.ARCHIVED
                        val beforeJson = MemoryAuditSupport.eventSnapshotJson(conn, existing.id)
                        conn.prepare(
                            "UPDATE memory_events SET status = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
                        ).use { stmt ->
                            stmt.bindText(1, nextStatus.wire)
                            stmt.bindLong(2, nowMs)
                            stmt.bindNullableText(3, deviceId)
                            stmt.bindText(4, existing.id)
                            stmt.step()
                        }
                        MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, existing.id, nowMs)
                        MemoryAuditSupport.recordAudit(
                            conn = conn,
                            runId = runId,
                            actor = actor,
                            actionType = "CONTRACT_EVENT",
                            targetTable = "memory_events",
                            targetId = existing.id,
                            summaryUserText = op.userSummary,
                            reason = op.reason,
                            beforeSnapshotJson = beforeJson,
                            afterSnapshotJson = MemoryAuditSupport.eventSnapshotJson(conn, existing.id),
                            nowMs = nowMs,
                        )
                        eventsForgotten++
                    }
                }
            }
        }

        batch.upsertRelations.forEach { op ->
            val (_, decision) = MemoryGraphStorage.upsertRelation(conn, op, actor, nowMs, runId, deviceId)
            when (decision) {
                GuardDecision.Allowed -> relationsUpserted++
                is GuardDecision.Blocked -> blocked += decision.reason
            }
        }

        batch.invalidateRelations.forEach { op ->
            when (val decision = MemoryGraphStorage.invalidateRelation(conn, op, actor, nowMs, runId, deviceId)) {
                GuardDecision.Allowed -> relationsInvalidated++
                is GuardDecision.Blocked -> blocked += decision.reason
            }
        }

        invalidateContextCache(conn)
        return CommitBatchReceipt(
            blocksPatched = blocksPatched,
            eventsRecorded = eventsRecorded,
            eventsSuperseded = eventsSuperseded,
            eventsForgotten = eventsForgotten,
            entitiesUpserted = entitiesUpserted,
            relationsUpserted = relationsUpserted,
            relationsInvalidated = relationsInvalidated,
            blockedByGuard = blocked,
        )
    }

    fun findEventById(conn: SQLiteConnection, id: String, includeDeleted: Boolean = false): MemoryEvent? {
        val deletedClause = if (includeDeleted) "" else "AND deleted_at IS NULL"
        val row = conn.prepare(
            "SELECT $EVENT_COLUMNS FROM memory_events WHERE id = ? $deletedClause LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readEventRow(stmt) else null
        } ?: return null
        return row.copy(entityNames = loadEntityNamesForEvent(conn, id))
    }

    fun listEvents(
        conn: SQLiteConnection,
        filter: MemoryEventFilter,
        historyOnly: Boolean = false,
    ): List<MemoryEvent> {
        if (filter.scope == MemoryAccessScope.OFF) return emptyList()
        val limit = filter.limit.coerceIn(1, 500)
        val conditions = ArrayList<String>()
        val args = ArrayList<Any>()
        conditions += "deleted_at IS NULL"
        when {
            historyOnly -> conditions += "status != 'ACTIVE'"
            !filter.includeHistory -> conditions += "status = 'ACTIVE'"
        }
        if (filter.eventKinds.isNotEmpty()) {
            conditions += "event_kind IN (${filter.eventKinds.joinToString(",") { "?" }})"
            filter.eventKinds.forEach { args += it.wire }
        }
        if (filter.domains.isNotEmpty()) {
            conditions += "domain IN (${filter.domains.joinToString(",") { "?" }})"
            filter.domains.forEach { args += MemoryDomains.normalize(it) }
        }
        filter.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionId ->
            conditions += "source_session_id = ?"
            args += sessionId
        }
        filter.sinceEventTimeMs?.let { since ->
            conditions += "event_time >= ?"
            args += since
        }
        filter.untilEventTimeMs?.let { until ->
            conditions += "event_time <= ?"
            args += until
        }
        filter.tag?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { tag ->
            conditions += "LOWER(tags_json) LIKE ?"
            args += "%\"${tag.lowercase()}\"%"
        }
        filter.entityName?.trim()?.takeIf { it.isNotEmpty() }?.let { entityName ->
            val entityId = MemoryGraphStorage.findEntityByNameOrAlias(conn, entityName)?.id
            if (entityId == null) return emptyList()
            conditions += "id IN (SELECT event_id FROM event_entities WHERE entity_id = ?)"
            args += entityId
        }

        val fetchLimit = (limit * 2).coerceIn(50, 800)
        val sql = """
            SELECT $EVENT_COLUMNS
            FROM memory_events
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY pinned DESC, event_time DESC, updated_at DESC
            LIMIT ?
        """.trimIndent()

        val raw = ArrayList<MemoryEvent>()
        conn.prepare(sql).use { stmt ->
            bindDynamicArgs(stmt, args + fetchLimit)
            while (stmt.step()) {
                val item = readEventRow(stmt)
                if (filter.scope.allows(item.domain, item.sensitivity)) {
                    raw += item
                    if (raw.size >= limit) break
                }
            }
        }
        val namesMap = loadEntityNamesMap(conn, raw.map { it.id })
        return raw.map { it.copy(entityNames = namesMap[it.id].orEmpty()) }
    }

    fun listEventsForEntity(
        conn: SQLiteConnection,
        entityId: String,
        scope: MemoryAccessScope,
        includeHistory: Boolean = false,
        limit: Int = 50,
    ): List<MemoryEvent> {
        if (scope == MemoryAccessScope.OFF) return emptyList()
        val statusClause = if (includeHistory) "" else "AND e.status = 'ACTIVE'"
        val raw = ArrayList<MemoryEvent>()
        conn.prepare(
            """
            SELECT ${EVENT_COLUMNS_ALIASED}
            FROM memory_events e
            JOIN event_entities ee ON ee.event_id = e.id
            WHERE ee.entity_id = ? AND e.deleted_at IS NULL $statusClause
            ORDER BY e.pinned DESC, e.event_time DESC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, entityId)
            stmt.bindLong(2, limit.coerceIn(1, 200).toLong())
            while (stmt.step()) {
                val item = readEventRow(stmt)
                if (scope.allows(item.domain, item.sensitivity)) {
                    raw += item
                }
            }
        }
        val namesMap = loadEntityNamesMap(conn, raw.map { it.id })
        return raw.map { it.copy(entityNames = namesMap[it.id].orEmpty()) }
    }

    fun ftsSearchCandidates(
        conn: SQLiteConnection,
        matchClause: String,
        filter: MemoryEventFilter,
        candidateLimit: Int,
    ): List<Pair<MemoryEvent, Double>> {
        if (filter.scope == MemoryAccessScope.OFF) return emptyList()
        val conditions = ArrayList<String>()
        val args = ArrayList<Any>()
        args += matchClause
        conditions += "e.deleted_at IS NULL"
        if (!filter.includeHistory) {
            conditions += "e.status = 'ACTIVE'"
        }
        if (filter.eventKinds.isNotEmpty()) {
            conditions += "e.event_kind IN (${filter.eventKinds.joinToString(",") { "?" }})"
            filter.eventKinds.forEach { args += it.wire }
        }
        if (filter.domains.isNotEmpty()) {
            conditions += "e.domain IN (${filter.domains.joinToString(",") { "?" }})"
            filter.domains.forEach { args += MemoryDomains.normalize(it) }
        }
        filter.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionId ->
            conditions += "e.source_session_id = ?"
            args += sessionId
        }
        filter.sinceEventTimeMs?.let {
            conditions += "e.event_time >= ?"
            args += it
        }
        filter.untilEventTimeMs?.let {
            conditions += "e.event_time <= ?"
            args += it
        }
        filter.tag?.trim()?.removePrefix("#")?.takeIf { it.isNotEmpty() }?.let { tag ->
            conditions += "LOWER(e.tags_json) LIKE ?"
            args += "%\"${tag.lowercase()}\"%"
        }
        filter.entityName?.trim()?.takeIf { it.isNotEmpty() }?.let { entityName ->
            val entityId = MemoryGraphStorage.findEntityByNameOrAlias(conn, entityName)?.id
                ?: return emptyList()
            conditions += "e.id IN (SELECT event_id FROM event_entities WHERE entity_id = ?)"
            args += entityId
        }

        val sql = """
            SELECT ${EVENT_COLUMNS_ALIASED}, bm25(memory_fts, 0.0, 2.0, 1.8, 1.2, 1.8) AS rank_score
            FROM memory_fts
            JOIN memory_events e ON e.id = memory_fts.event_id
            WHERE memory_fts MATCH ? AND ${conditions.joinToString(" AND ")}
            ORDER BY rank_score ASC
            LIMIT ?
        """.trimIndent()

        val hits = ArrayList<Pair<MemoryEvent, Double>>()
        val events = ArrayList<MemoryEvent>()
        val scores = ArrayList<Double>()
        conn.prepare(sql).use { stmt ->
            bindDynamicArgs(stmt, args + candidateLimit)
            while (stmt.step()) {
                val event = readEventRow(stmt)
                if (!filter.scope.allows(event.domain, event.sensitivity)) continue
                events += event
                scores += stmt.getDouble(28)
            }
        }
        val namesMap = loadEntityNamesMap(conn, events.map { it.id })
        events.forEachIndexed { idx, event ->
            hits += event.copy(entityNames = namesMap[event.id].orEmpty()) to scores[idx]
        }
        return hits
    }

    fun recordAccessHits(conn: SQLiteConnection, eventIds: List<String>, nowMs: Long) {
        if (eventIds.isEmpty()) return
        conn.prepare(
            """
            UPDATE memory_events
            SET access_count = access_count + 1,
                last_accessed_at = ?,
                activation_score = MIN(5.0, activation_score + 0.15)
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            eventIds.distinct().forEach { id ->
                stmt.bindLong(1, nowMs)
                stmt.bindText(2, id)
                stmt.step()
                stmt.reset()
            }
        }
    }

    fun runDeterministicCleanup(
        conn: SQLiteConnection,
        nowMs: Long,
        staleInferredDays: Long = DEFAULT_STALE_INFERRED_DAYS,
        deviceId: String? = null,
    ): Triple<Int, Int, Int> {
        var expired = 0
        conn.prepare(
            "SELECT id FROM memory_events WHERE deleted_at IS NULL AND status = 'ACTIVE' AND pinned = 0 AND valid_until IS NOT NULL AND valid_until < ?",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            val ids = ArrayList<String>()
            while (stmt.step()) ids += stmt.getText(0)
            ids.forEach { id ->
                conn.prepare(
                    "UPDATE memory_events SET status = 'EXPIRED', updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
                ).use { upd ->
                    upd.bindLong(1, nowMs)
                    upd.bindNullableText(2, deviceId)
                    upd.bindText(3, id)
                    upd.step()
                }
                MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
                expired++
            }
        }

        var archived = 0
        val cutoffMs = nowMs - staleInferredDays * MemoryLimits.MS_PER_DAY
        conn.prepare(
            """
            SELECT id FROM memory_events
            WHERE deleted_at IS NULL AND status = 'ACTIVE' AND pinned = 0
              AND epistemic_tier = 'INFERRED' AND importance <= 2 AND access_count = 0 AND updated_at < ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, cutoffMs)
            val ids = ArrayList<String>()
            while (stmt.step()) ids += stmt.getText(0)
            ids.forEach { id ->
                conn.prepare(
                    "UPDATE memory_events SET status = 'ARCHIVED', updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
                ).use { upd ->
                    upd.bindLong(1, nowMs)
                    upd.bindNullableText(2, deviceId)
                    upd.bindText(3, id)
                    upd.step()
                }
                MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
                archived++
            }
        }

        val prunedRuns = pruneOldAgentRuns(conn)
        if (expired > 0 || archived > 0) invalidateContextCache(conn)
        return Triple(expired, archived, prunedRuns)
    }

    fun countNewEventsSince(conn: SQLiteConnection, sinceEpochMs: Long): Int =
        conn.prepare(
            "SELECT COUNT(*) FROM memory_events WHERE deleted_at IS NULL AND created_at > ?",
        ).use { stmt ->
            stmt.bindLong(1, sinceEpochMs)
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    fun listTags(conn: SQLiteConnection, scope: MemoryAccessScope = MemoryAccessScope.PERSONAL, limit: Int = 20): List<MemoryTagCount> {
        if (scope == MemoryAccessScope.OFF) return emptyList()
        val counts = HashMap<String, Pair<String, Int>>()
        conn.prepare(
            "SELECT tags_json, domain, sensitivity FROM memory_events WHERE deleted_at IS NULL AND status = 'ACTIVE'",
        ).use { stmt ->
            while (stmt.step()) {
                val domain = stmt.getText(1)
                val sensitivity = MemorySensitivity.fromWire(stmt.getText(2))
                if (!scope.allows(domain, sensitivity)) continue
                MemoryJsonSupport.decodeStringList(stmt.getText(0)).forEach { rawTag ->
                    val key = rawTag.lowercase()
                    val existing = counts[key]
                    counts[key] = if (existing == null) rawTag to 1 else existing.first to (existing.second + 1)
                }
            }
        }
        return counts.values
            .map { MemoryTagCount(it.first, it.second) }
            .sortedWith(compareByDescending<MemoryTagCount> { it.count }.thenBy { it.tag })
            .take(limit)
    }

    fun loadSnapshotData(conn: SQLiteConnection, nowMs: Long): ContextSnapshotData {
        val allBlocks = MemoryBlockStorage.listBlocks(conn, includeArchived = true)
        val activeBlocks = allBlocks.filter { it.status == io.github.whywood.superpi.runtime.MemoryBlockStatus.ACTIVE }
        val archivedBlocks = allBlocks.filter { it.status == io.github.whywood.superpi.runtime.MemoryBlockStatus.ARCHIVED }
        val entities = MemoryGraphStorage.listEntities(conn, scope = MemoryAccessScope.PERSONAL)
        val relations = MemoryGraphStorage.listActiveRelations(conn, scope = MemoryAccessScope.PERSONAL)
        val recentCutoff = nowMs - 14L * MemoryLimits.MS_PER_DAY
        val recentEvents = listEvents(
            conn,
            MemoryEventFilter(includeHistory = false, scope = MemoryAccessScope.PERSONAL, limit = 160),
        ).filter { event ->
            event.pinned ||
                event.eventKind == MemoryEventKind.COMMITMENT ||
                event.eventKind == MemoryEventKind.GOAL ||
                event.eventTime >= recentCutoff ||
                event.importance >= 4
        }

        val expandedEntityNames = entities.take(24).map { it.canonicalName.lowercase() }.toSet()
        val unexpandedEntityNames = entities
            .drop(24)
            .take(40)
            .map { it.canonicalName }
            .filterNot { it.lowercase() in expandedEntityNames }

        val archivedTitles = archivedBlocks.map { it.slug to it.title }

        val monthlyCounts = LinkedHashMap<String, Int>()
        conn.prepare(
            "SELECT event_time FROM memory_events WHERE deleted_at IS NULL AND event_time < ? ORDER BY event_time DESC LIMIT 400",
        ).use { stmt ->
            stmt.bindLong(1, recentCutoff)
            while (stmt.step()) {
                val month = MONTH_FORMAT.format(
                    Instant.ofEpochMilli(stmt.getLong(0)).atZone(ZoneId.systemDefault()).toLocalDate(),
                )
                monthlyCounts[month] = (monthlyCounts[month] ?: 0) + 1
            }
        }

        val historyEvents = listEvents(
            conn,
            MemoryEventFilter(includeHistory = true, scope = MemoryAccessScope.PERSONAL, limit = 200),
            historyOnly = true,
        )

        return ContextSnapshotData(
            blocks = activeBlocks,
            archivedBlocks = archivedBlocks,
            entities = entities,
            relations = relations,
            recentEvents = recentEvents,
            historyEvents = historyEvents,
            unexpandedEntityNames = unexpandedEntityNames,
            archivedTitlesAndSlugs = archivedTitles,
            monthlyMilestones = monthlyCounts.entries.take(12).map { it.key to it.value },
        )
    }

    fun enqueueDispatchJob(
        conn: SQLiteConnection,
        jobKind: DispatchJobKind,
        sessionId: String?,
        payloadJson: String,
        transcriptHash: String? = null,
        nowMs: Long,
        replaceSameSessionKind: Boolean = false,
    ): DispatchQueueJob {
        if (replaceSameSessionKind && !sessionId.isNullOrBlank()) {
            conn.prepare(
                "DELETE FROM memory_dispatch_queue WHERE job_kind = ? AND session_id = ?",
            ).use { stmt ->
                stmt.bindText(1, jobKind.wire)
                stmt.bindText(2, sessionId)
                stmt.step()
            }
        }
        val id = UUID.randomUUID().toString()
        conn.prepare(
            """
            INSERT INTO memory_dispatch_queue(
                id, job_kind, priority, session_id, payload_json, transcript_hash,
                attempts, last_error, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, jobKind.wire)
            stmt.bindLong(3, jobKind.defaultPriority.toLong())
            stmt.bindNullableText(4, sessionId)
            stmt.bindText(5, payloadJson)
            stmt.bindNullableText(6, transcriptHash)
            stmt.bindLong(7, nowMs)
            stmt.bindLong(8, nowMs)
            stmt.step()
        }
        return checkNotNull(findDispatchJobById(conn, id))
    }

    fun dequeueNextJob(
        conn: SQLiteConnection,
        maxAttempts: Int = 5,
        nowMs: Long = Long.MAX_VALUE,
    ): DispatchQueueJob? =
        conn.prepare(
            """
            SELECT id, job_kind, priority, session_id, payload_json, transcript_hash,
                   attempts, last_error, created_at, updated_at
            FROM memory_dispatch_queue
            WHERE attempts < ? AND next_retry_at <= ?
            ORDER BY priority DESC, created_at ASC
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            stmt.bindLong(2, nowMs)
            if (stmt.step()) readDispatchJob(stmt) else null
        }

    fun completeDispatchJob(conn: SQLiteConnection, id: String) {
        conn.prepare("DELETE FROM memory_dispatch_queue WHERE id = ?").use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }
    }

    fun failDispatchJob(conn: SQLiteConnection, id: String, error: String, nowMs: Long) {
        val currentAttempts = conn.prepare(
            "SELECT attempts FROM memory_dispatch_queue WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
        val nextAttempts = currentAttempts + 1
        val delayMs = when (nextAttempts) {
            1 -> 30_000L
            2 -> 120_000L
            3 -> 600_000L
            else -> 1_800_000L
        }
        conn.prepare(
            "UPDATE memory_dispatch_queue SET attempts = ?, last_error = ?, next_retry_at = ?, updated_at = ? WHERE id = ?",
        ).use { stmt ->
            stmt.bindLong(1, nextAttempts.toLong())
            stmt.bindText(2, MemoryLimits.clampText(error, 300))
            stmt.bindLong(3, nowMs + delayMs)
            stmt.bindLong(4, nowMs)
            stmt.bindText(5, id)
            stmt.step()
        }
    }

    fun earliestNextRetryAt(conn: SQLiteConnection, maxAttempts: Int = 5): Long? =
        conn.prepare(
            "SELECT MIN(next_retry_at) FROM memory_dispatch_queue WHERE attempts < ? AND next_retry_at > 0",
        ).use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            if (stmt.step() && !stmt.isNull(0)) stmt.getLong(0) else null
        }

    fun resetRetryBackoffNow(conn: SQLiteConnection, nowMs: Long): Int {
        conn.prepare(
            "UPDATE memory_dispatch_queue SET next_retry_at = 0, attempts = 0, updated_at = ?",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.step()
        }
        return countPendingJobs(conn)
    }

    fun countActiveEventsByIds(conn: SQLiteConnection, ids: List<String>): Int {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return 0
        val placeholders = clean.joinToString(",") { "?" }
        return conn.prepare(
            "SELECT COUNT(DISTINCT id) FROM memory_events WHERE deleted_at IS NULL AND status = 'ACTIVE' AND id IN ($placeholders)",
        ).use { stmt ->
            clean.forEachIndexed { idx, id -> stmt.bindText(idx + 1, id) }
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
    }

    fun deleteEventFts(conn: SQLiteConnection, eventId: String) {
        conn.prepare("DELETE FROM memory_fts WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, eventId)
            stmt.step()
        }
    }

    fun deleteSessionJobs(conn: SQLiteConnection, sessionId: String) {
        conn.prepare(
            "DELETE FROM memory_dispatch_queue WHERE session_id = ? AND job_kind IN ('SESSION_IDLE', 'SESSION_COMPACT', 'SESSION_CLOSE')",
        ).use { stmt ->
            stmt.bindText(1, sessionId)
            stmt.step()
        }
    }

    fun pruneExhaustedJobs(conn: SQLiteConnection, maxAttempts: Int = 5) {
        conn.prepare("DELETE FROM memory_dispatch_queue WHERE attempts >= ?").use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            stmt.step()
        }
    }

    fun countPendingJobs(conn: SQLiteConnection, maxAttempts: Int = 5): Int =
        conn.prepare("SELECT COUNT(*) FROM memory_dispatch_queue WHERE attempts < ?").use { stmt ->
            stmt.bindLong(1, maxAttempts.toLong())
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    fun hasPendingHighPriorityJob(conn: SQLiteConnection, nowMs: Long): Boolean =
        conn.prepare(
            "SELECT 1 FROM memory_dispatch_queue WHERE job_kind = 'DISPATCH_INTENT' AND attempts < 5 AND next_retry_at <= ? LIMIT 1",
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.step()
        }

    fun deleteWatermark(conn: SQLiteConnection, sessionId: String) {
        conn.prepare("DELETE FROM ingestion_watermarks WHERE session_id = ?").use { stmt ->
            stmt.bindText(1, sessionId)
            stmt.step()
        }
    }

    fun readWatermark(conn: SQLiteConnection, sessionId: String): IngestionWatermark? =
        conn.prepare(
            "SELECT session_id, session_file, last_entry_id, last_hash, updated_at FROM ingestion_watermarks WHERE session_id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, sessionId)
            if (!stmt.step()) return null
            IngestionWatermark(
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

    fun readPauseWindows(conn: SQLiteConnection): List<PauseWindow> {
        val raw = conn.prepare("SELECT value FROM sync_state WHERE key = ? LIMIT 1").use { stmt ->
            stmt.bindText(1, KEY_PAUSE_WINDOWS)
            if (stmt.step()) stmt.getText(0) else null
        } ?: return emptyList()
        return runCatching {
            val array = MemoryJsonSupport.json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            array.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val start = obj.longVal("start_ms") ?: return@mapNotNull null
                PauseWindow(startMs = start, endMs = obj.longVal("end_ms"))
            }
        }.getOrDefault(emptyList())
    }

    fun openPauseWindow(conn: SQLiteConnection, nowMs: Long) {
        val current = prunePauseWindows(readPauseWindows(conn), nowMs)
        if (current.any { it.endMs == null }) return
        savePauseWindows(conn, current + PauseWindow(startMs = nowMs, endMs = null))
    }

    fun closePauseWindow(conn: SQLiteConnection, nowMs: Long): Boolean {
        val current = prunePauseWindows(readPauseWindows(conn), nowMs)
        var changed = false
        val updated = current.map { window ->
            if (window.endMs == null) {
                changed = true
                window.copy(endMs = maxOf(window.startMs, nowMs))
            } else {
                window
            }
        }
        if (changed) savePauseWindows(conn, updated)
        return changed
    }

    fun recordAgentRun(conn: SQLiteConnection, summary: MemoryAgentRunSummary) {
        conn.prepare(
            """
            INSERT INTO memory_agent_runs(
                id, mode, started_at, finished_at, status, model_ref, steps_used,
                blocks_patched, events_recorded, events_superseded, events_forgotten,
                entities_merged, proposals_created, error
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, summary.id)
            stmt.bindText(2, summary.mode)
            stmt.bindLong(3, summary.startedAt)
            stmt.bindNullableLong(4, summary.finishedAt)
            stmt.bindText(5, summary.status)
            stmt.bindNullableText(6, summary.modelRef)
            stmt.bindLong(7, summary.stepsUsed.toLong())
            stmt.bindLong(8, summary.blocksPatched.toLong())
            stmt.bindLong(9, summary.eventsRecorded.toLong())
            stmt.bindLong(10, summary.eventsSuperseded.toLong())
            stmt.bindLong(11, summary.eventsForgotten.toLong())
            stmt.bindLong(12, summary.entitiesMerged.toLong())
            stmt.bindLong(13, summary.proposalsCreated.toLong())
            stmt.bindNullableText(14, summary.error)
            stmt.step()
        }
    }

    fun latestSuccessfulColdRunAt(conn: SQLiteConnection): Long? =
        conn.prepare(
            "SELECT MAX(finished_at) FROM memory_agent_runs WHERE mode = 'COLD' AND status = 'DONE'",
        ).use { stmt ->
            if (stmt.step() && !stmt.isNull(0)) stmt.getLong(0) else null
        }

    fun overview(conn: SQLiteConnection): MemoryOverview {
        val activeBlocks = conn.prepare(
            "SELECT COUNT(*) FROM memory_blocks WHERE deleted_at IS NULL AND status = 'ACTIVE'",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        val archivedBlocks = conn.prepare(
            "SELECT COUNT(*) FROM memory_blocks WHERE deleted_at IS NULL AND status = 'ARCHIVED'",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        val activeEvents = conn.prepare(
            "SELECT COUNT(*) FROM memory_events WHERE deleted_at IS NULL AND status = 'ACTIVE'",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        val entities = conn.prepare(
            "SELECT COUNT(*) FROM entities WHERE deleted_at IS NULL",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        val relations = conn.prepare(
            "SELECT COUNT(*) FROM entity_relations WHERE deleted_at IS NULL AND invalidated_at IS NULL",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        val proposals = conn.prepare(
            "SELECT COUNT(*) FROM memory_block_proposals WHERE deleted_at IS NULL AND status = 'PENDING'",
        ).use { if (it.step()) it.getLong(0).toInt() else 0 }
        return MemoryOverview(
            activeBlocks = activeBlocks,
            archivedBlocks = archivedBlocks,
            activeEvents = activeEvents,
            entityCount = entities,
            relationCount = relations,
            pendingProposals = proposals,
            pendingDispatchJobs = countPendingJobs(conn),
            lastConsolidationAt = latestSuccessfulColdRunAt(conn),
        )
    }

    fun readCachedContext(conn: SQLiteConnection, scope: MemoryAccessScope): CachedContextSection? =
        conn.prepare(
            "SELECT scope_wire, rendered_text, char_count, revision, generated_at, reason FROM context_cache WHERE scope_wire = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, scope.wire)
            if (!stmt.step()) return null
            CachedContextSection(
                scopeWire = stmt.getText(0),
                renderedText = stmt.getText(1),
                charCount = stmt.getLong(2).toInt(),
                revision = stmt.getLong(3),
                generatedAt = stmt.getLong(4),
                reason = stmt.getText(5),
            )
        }

    fun writeCachedContext(conn: SQLiteConnection, section: CachedContextSection) {
        conn.prepare(
            """
            INSERT INTO context_cache(scope_wire, rendered_text, char_count, revision, generated_at, reason)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(scope_wire) DO UPDATE SET
                rendered_text = excluded.rendered_text,
                char_count = excluded.char_count,
                revision = excluded.revision,
                generated_at = excluded.generated_at,
                reason = excluded.reason
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, section.scopeWire)
            stmt.bindText(2, section.renderedText)
            stmt.bindLong(3, section.charCount.toLong())
            stmt.bindLong(4, section.revision)
            stmt.bindLong(5, section.generatedAt)
            stmt.bindText(6, section.reason)
            stmt.step()
        }
    }

    fun invalidateContextCache(conn: SQLiteConnection) {
        conn.execSQL("DELETE FROM context_cache")
    }

    fun refreshEventFts(conn: SQLiteConnection, eventId: String) {
        val event = findEventById(conn, eventId) ?: return
        upsertFts(
            conn = conn,
            eventId = event.id,
            content = event.content,
            summary = event.summary,
            semanticKeywords = event.semanticKeywords,
            tags = event.tags,
            entityNames = event.entityNames,
        )
    }

    private fun upsertFts(
        conn: SQLiteConnection,
        eventId: String,
        content: String,
        summary: String?,
        semanticKeywords: List<String>,
        tags: List<String>,
        entityNames: List<String>,
    ) {
        conn.prepare("DELETE FROM memory_fts WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, eventId)
            stmt.step()
        }
        val contentText = listOfNotNull(content, summary).joinToString(" ")
        conn.prepare(
            "INSERT INTO memory_fts(event_id, content_tokens, semantic_tokens, tag_tokens, entity_tokens) VALUES (?, ?, ?, ?, ?)",
        ).use { stmt ->
            stmt.bindText(1, eventId)
            stmt.bindText(2, MemoryTokenizer.indexText(contentText))
            stmt.bindText(3, MemoryTokenizer.indexText(semanticKeywords.joinToString(" ")))
            stmt.bindText(4, MemoryTokenizer.indexText(tags.joinToString(" ")))
            stmt.bindText(5, MemoryTokenizer.indexText(entityNames.joinToString(" ")))
            stmt.step()
        }
    }

    private fun linkEntitiesToEvent(
        conn: SQLiteConnection,
        eventId: String,
        refs: List<MemoryEntityRef>,
        defaultDomain: String,
        defaultSensitivity: MemorySensitivity,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String?,
        deviceId: String? = null,
    ): List<String> {
        val names = ArrayList<String>()
        refs.take(MemoryLimits.MAX_ENTITIES_PER_EVENT).forEach { ref ->
            val cleanName = MemoryLimits.clampText(ref.name, MemoryLimits.MAX_ENTITY_NAME_CHARS)
            if (cleanName.isEmpty()) return@forEach
            val adjustedRef = ref.copy(
                domain = ref.domain.ifBlank { defaultDomain },
                sensitivity = if (ref.sensitivity == MemorySensitivity.NORMAL) defaultSensitivity else ref.sensitivity,
            )
            val (entity, _) = MemoryGraphStorage.upsertEntity(conn, adjustedRef, actor, nowMs, runId, deviceId = deviceId)
            conn.prepare(
                "INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.bindText(1, eventId)
                stmt.bindText(2, entity.id)
                stmt.bindText(3, ref.role.ifBlank { "MENTIONED" })
                stmt.step()
            }
            names += entity.canonicalName
        }
        return names.distinct()
    }

    private fun loadEntityNamesForEvent(conn: SQLiteConnection, eventId: String): List<String> {
        val names = ArrayList<String>()
        conn.prepare(
            """
            SELECT e.canonical_name FROM entities e
            JOIN event_entities ee ON ee.entity_id = e.id
            WHERE ee.event_id = ? AND e.deleted_at IS NULL
            ORDER BY e.canonical_name ASC
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, eventId)
            while (stmt.step()) names += stmt.getText(0)
        }
        return names
    }

    private fun loadEntityNamesMap(conn: SQLiteConnection, eventIds: List<String>): Map<String, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        val placeholders = eventIds.joinToString(",") { "?" }
        val result = HashMap<String, MutableList<String>>()
        conn.prepare(
            """
            SELECT ee.event_id, e.canonical_name
            FROM event_entities ee
            JOIN entities e ON e.id = ee.entity_id
            WHERE ee.event_id IN ($placeholders) AND e.deleted_at IS NULL
            ORDER BY e.canonical_name ASC
            """.trimIndent(),
        ).use { stmt ->
            eventIds.forEachIndexed { idx, id -> stmt.bindText(idx + 1, id) }
            while (stmt.step()) {
                result.getOrPut(stmt.getText(0)) { ArrayList() }.add(stmt.getText(1))
            }
        }
        return result
    }

    private fun findDispatchJobById(conn: SQLiteConnection, id: String): DispatchQueueJob? =
        conn.prepare(
            """
            SELECT id, job_kind, priority, session_id, payload_json, transcript_hash,
                   attempts, last_error, created_at, updated_at
            FROM memory_dispatch_queue WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readDispatchJob(stmt) else null
        }

    private fun savePauseWindows(conn: SQLiteConnection, windows: List<PauseWindow>) {
        val json = JsonArray(
            windows.map { w ->
                buildJsonObject {
                    put("start_ms", w.startMs)
                    w.endMs?.let { put("end_ms", it) }
                }
            },
        ).toString()
        conn.prepare(
            """
            INSERT INTO sync_state(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, KEY_PAUSE_WINDOWS)
            stmt.bindText(2, json)
            stmt.step()
        }
    }

    private fun prunePauseWindows(windows: List<PauseWindow>, nowMs: Long): List<PauseWindow> {
        val cutoff = nowMs - PAUSE_RETENTION_MS
        return windows
            .filter { it.endMs == null || it.endMs >= cutoff }
            .takeLast(MAX_PAUSE_WINDOWS)
    }

    private fun pruneOldAgentRuns(conn: SQLiteConnection): Int {
        val total = conn.prepare("SELECT COUNT(*) FROM memory_agent_runs").use {
            if (it.step()) it.getLong(0).toInt() else 0
        }
        if (total <= DEFAULT_RUN_HISTORY_KEEP) return 0
        val toDelete = total - DEFAULT_RUN_HISTORY_KEEP
        conn.prepare(
            """
            DELETE FROM memory_agent_runs
            WHERE id IN (
                SELECT id FROM memory_agent_runs
                ORDER BY started_at ASC
                LIMIT ?
            )
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, toDelete.toLong())
            stmt.step()
        }
        return toDelete
    }

    private fun cleanStringList(items: List<String>, maxItems: Int, maxChars: Int): List<String> {
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

    private fun readEventRow(stmt: SQLiteStatement): MemoryEvent = MemoryEvent(
        id = stmt.getText(0),
        eventKind = MemoryEventKind.fromWire(stmt.getText(1)) ?: MemoryEventKind.FACT,
        content = stmt.getText(2),
        summary = stmt.getNullableText(3),
        qualifiers = MemoryJsonSupport.decodeStringMap(stmt.getText(4)),
        semanticKeywords = MemoryJsonSupport.decodeStringList(stmt.getText(5)),
        tags = MemoryJsonSupport.decodeStringList(stmt.getText(6)),
        domain = stmt.getText(7),
        sensitivity = MemorySensitivity.fromWire(stmt.getText(8)),
        epistemicTier = MemoryEpistemicTier.fromWire(stmt.getText(9)),
        importance = stmt.getLong(10).toInt(),
        pinned = stmt.getLong(11) != 0L,
        eventTime = stmt.getLong(12),
        eventTimePrecision = MemoryEventTimePrecision.fromWire(stmt.getText(13)),
        validFrom = stmt.getLong(14),
        validUntil = stmt.getNullableLong(15),
        status = MemoryItemStatus.fromWire(stmt.getText(16)),
        supersededBy = stmt.getNullableText(17),
        sourceKind = MemorySourceKind.fromWire(stmt.getText(18)),
        sourceSessionId = stmt.getNullableText(19),
        sourceEntryId = stmt.getNullableText(20),
        sourceQuote = stmt.getNullableText(21),
        accessCount = stmt.getLong(22).toInt(),
        lastAccessedAt = stmt.getNullableLong(23),
        activationScore = stmt.getDouble(24),
        createdAt = stmt.getLong(25),
        updatedAt = stmt.getLong(26),
        revision = stmt.getLong(27),
    )

    private fun readDispatchJob(stmt: SQLiteStatement): DispatchQueueJob = DispatchQueueJob(
        id = stmt.getText(0),
        jobKind = DispatchJobKind.fromWire(stmt.getText(1)),
        priority = stmt.getLong(2).toInt(),
        sessionId = stmt.getNullableText(3),
        payloadJson = stmt.getText(4),
        transcriptHash = stmt.getNullableText(5),
        attempts = stmt.getLong(6).toInt(),
        lastError = stmt.getNullableText(7),
        createdAt = stmt.getLong(8),
        updatedAt = stmt.getLong(9),
    )

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

    private const val EVENT_COLUMNS =
        "id, event_kind, content, summary, qualifiers_json, semantic_keywords_json, " +
            "tags_json, domain, sensitivity, epistemic_tier, importance, pinned, " +
            "event_time, event_time_precision, valid_from, valid_until, status, superseded_by, " +
            "source_kind, source_session_id, source_entry_id, source_quote, " +
            "access_count, last_accessed_at, activation_score, created_at, updated_at, revision"

    private const val EVENT_COLUMNS_ALIASED =
        "e.id, e.event_kind, e.content, e.summary, e.qualifiers_json, e.semantic_keywords_json, " +
            "e.tags_json, e.domain, e.sensitivity, e.epistemic_tier, e.importance, e.pinned, " +
            "e.event_time, e.event_time_precision, e.valid_from, e.valid_until, e.status, e.superseded_by, " +
            "e.source_kind, e.source_session_id, e.source_entry_id, e.source_quote, " +
            "e.access_count, e.last_accessed_at, e.activation_score, e.created_at, e.updated_at, e.revision"
}

package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.runtime.MemorySensitivity
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MemoryGraphStorage {
    fun listEntities(
        conn: SQLiteConnection,
        entityType: String? = null,
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
    ): List<MemoryEntity> {
        if (scope == MemoryAccessScope.OFF) return emptyList()
        val out = ArrayList<MemoryEntity>()
        conn.prepare(
            "SELECT $ENTITY_COLUMNS FROM entities WHERE deleted_at IS NULL ORDER BY pinned DESC, updated_at DESC",
        ).use { stmt ->
            while (stmt.step()) {
                val entity = readEntityRow(stmt)
                if (!scope.allows(entity.domain, entity.sensitivity)) continue
                if (!entityType.isNullOrBlank() && !entity.entityType.equals(entityType.trim(), ignoreCase = true)) {
                    continue
                }
                out += entity
            }
        }
        return out
    }

    fun findEntityById(conn: SQLiteConnection, id: String, includeDeleted: Boolean = false): MemoryEntity? {
        val deletedClause = if (includeDeleted) "" else "AND deleted_at IS NULL"
        return conn.prepare(
            "SELECT $ENTITY_COLUMNS FROM entities WHERE id = ? $deletedClause LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) readEntityRow(stmt) else null
        }
    }

    fun findEntityByNameOrAlias(conn: SQLiteConnection, name: String): MemoryEntity? {
        val clean = name.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null
        val exact = conn.prepare(
            "SELECT $ENTITY_COLUMNS FROM entities WHERE deleted_at IS NULL AND LOWER(canonical_name) = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, clean)
            if (stmt.step()) readEntityRow(stmt) else null
        }
        if (exact != null) return exact
        return conn.prepare(
            "SELECT $ENTITY_COLUMNS FROM entities WHERE deleted_at IS NULL ORDER BY updated_at DESC",
        ).use { stmt ->
            var matched: MemoryEntity? = null
            while (stmt.step()) {
                val candidate = readEntityRow(stmt)
                if (candidate.aliases.any { it.equals(clean, ignoreCase = true) }) {
                    matched = candidate
                    break
                }
            }
            matched
        }
    }

    fun upsertEntity(
        conn: SQLiteConnection,
        ref: MemoryEntityRef,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String? = null,
        auditSummary: String? = null,
        deviceId: String? = null,
    ): Pair<MemoryEntity, GuardDecision> {
        val cleanName = MemoryLimits.clampText(ref.name, MemoryLimits.MAX_ENTITY_NAME_CHARS).ifEmpty { "-" }
        val cleanType = MemoryEntityTypes.normalize(ref.entityType, MemoryEntityTypes.TOPIC)
        val cleanDomain = MemoryDomains.normalize(ref.domain, MemoryDomains.PERSONAL)
        val existing = findEntityByNameOrAlias(conn, cleanName)
        if (existing == null) {
            val id = UUID.randomUUID().toString()
            val aliases = cleanAliases(ref.aliases, cleanName)
            val cleanSummary = MemoryLimits.clampOptionalText(ref.summary, MemoryLimits.MAX_SUMMARY_CHARS)
            conn.prepare(
                """
                INSERT INTO entities(
                    id, canonical_name, entity_type, aliases_json, attributes_json, summary,
                    domain, sensitivity, pinned, created_at, updated_at, deleted_at, revision, updated_by_device_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, NULL, 1, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, id)
                stmt.bindText(2, cleanName)
                stmt.bindText(3, cleanType)
                stmt.bindText(4, MemoryJsonSupport.encodeStringList(aliases))
                stmt.bindText(5, MemoryJsonSupport.encodeStringMap(ref.attributes))
                if (cleanSummary == null) stmt.bindNull(6) else stmt.bindText(6, cleanSummary)
                stmt.bindText(7, cleanDomain)
                stmt.bindText(8, ref.sensitivity.wire)
                stmt.bindLong(9, nowMs)
                stmt.bindLong(10, nowMs)
                if (deviceId == null) stmt.bindNull(11) else stmt.bindText(11, deviceId)
                stmt.step()
            }
            if (auditSummary != null) {
                val afterJson = MemoryAuditSupport.entitySnapshotJson(conn, id)
                MemoryAuditSupport.recordAudit(
                    conn = conn,
                    runId = runId,
                    actor = actor,
                    actionType = "CREATE_ENTITY",
                    targetTable = "entities",
                    targetId = id,
                    summaryUserText = auditSummary,
                    reason = null,
                    beforeSnapshotJson = null,
                    afterSnapshotJson = afterJson,
                    nowMs = nowMs,
                )
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, id, nowMs)
            MemoryStorage.invalidateContextCache(conn)
            return checkNotNull(findEntityById(conn, id)) to GuardDecision.Allowed
        }

        val guard = EpistemicGuard.canMutateEntity(existing, actor)
        if (guard is GuardDecision.Blocked) return existing to guard

        val beforeJson = if (auditSummary != null) MemoryAuditSupport.entitySnapshotJson(conn, existing.id) else null
        val mergedAliases = cleanAliases(existing.aliases + ref.aliases, existing.canonicalName)
        val mergedAttrs = existing.attributes + ref.attributes
        val nextType = if (existing.entityType == MemoryEntityTypes.OTHER || existing.entityType == MemoryEntityTypes.TOPIC) {
            cleanType
        } else {
            existing.entityType
        }
        val nextSummary = MemoryLimits.clampOptionalText(ref.summary, MemoryLimits.MAX_SUMMARY_CHARS) ?: existing.summary
        val changed = mergedAliases != existing.aliases ||
            mergedAttrs != existing.attributes ||
            nextType != existing.entityType ||
            nextSummary != existing.summary

        if (changed) {
            conn.prepare(
                """
                UPDATE entities
                SET entity_type = ?, aliases_json = ?, attributes_json = ?, summary = ?,
                    updated_at = ?, revision = revision + 1, updated_by_device_id = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, nextType)
                stmt.bindText(2, MemoryJsonSupport.encodeStringList(mergedAliases))
                stmt.bindText(3, MemoryJsonSupport.encodeStringMap(mergedAttrs))
                if (nextSummary == null) stmt.bindNull(4) else stmt.bindText(4, nextSummary)
                stmt.bindLong(5, nowMs)
                if (deviceId == null) stmt.bindNull(6) else stmt.bindText(6, deviceId)
                stmt.bindText(7, existing.id)
                stmt.step()
            }
            if (auditSummary != null) {
                val afterJson = MemoryAuditSupport.entitySnapshotJson(conn, existing.id)
                MemoryAuditSupport.recordAudit(
                    conn = conn,
                    runId = runId,
                    actor = actor,
                    actionType = "UPDATE_ENTITY",
                    targetTable = "entities",
                    targetId = existing.id,
                    summaryUserText = auditSummary,
                    reason = null,
                    beforeSnapshotJson = beforeJson,
                    afterSnapshotJson = afterJson,
                    nowMs = nowMs,
                )
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, existing.id, nowMs)
            MemoryStorage.invalidateContextCache(conn)
        }
        return checkNotNull(findEntityById(conn, existing.id)) to GuardDecision.Allowed
    }

    fun updateEntityDirect(
        conn: SQLiteConnection,
        id: String,
        canonicalName: String?,
        entityType: String?,
        aliases: List<String>?,
        attributes: Map<String, String>?,
        summary: String?,
        clearSummary: Boolean = false,
        domain: String?,
        sensitivity: MemorySensitivity?,
        pinned: Boolean?,
        nowMs: Long,
        deviceId: String? = null,
    ): MemoryEntity? {
        val existing = findEntityById(conn, id) ?: return null
        val beforeJson = MemoryAuditSupport.entitySnapshotJson(conn, id)
        val nextName = canonicalName?.let { MemoryLimits.clampText(it, MemoryLimits.MAX_ENTITY_NAME_CHARS) }
            ?.takeIf { it.isNotEmpty() } ?: existing.canonicalName
        val nextType = entityType?.let { MemoryEntityTypes.normalize(it, existing.entityType) } ?: existing.entityType
        val nextAliases = aliases?.let { cleanAliases(it, nextName) } ?: existing.aliases
        val nextAttrs = attributes ?: existing.attributes
        val nextSummary = when {
            clearSummary -> null
            summary != null -> MemoryLimits.clampOptionalText(summary, MemoryLimits.MAX_SUMMARY_CHARS)
            else -> existing.summary
        }
        val nextDomain = domain?.let { MemoryDomains.normalize(it, existing.domain) } ?: existing.domain
        val nextSensitivity = sensitivity ?: existing.sensitivity
        val nextPinned = pinned ?: existing.pinned

        conn.prepare(
            """
            UPDATE entities
            SET canonical_name = ?, entity_type = ?, aliases_json = ?, attributes_json = ?,
                summary = ?, domain = ?, sensitivity = ?, pinned = ?,
                updated_at = ?, revision = revision + 1, updated_by_device_id = ?
            WHERE id = ? AND deleted_at IS NULL
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, nextName)
            stmt.bindText(2, nextType)
            stmt.bindText(3, MemoryJsonSupport.encodeStringList(nextAliases))
            stmt.bindText(4, MemoryJsonSupport.encodeStringMap(nextAttrs))
            if (nextSummary == null) stmt.bindNull(5) else stmt.bindText(5, nextSummary)
            stmt.bindText(6, nextDomain)
            stmt.bindText(7, nextSensitivity.wire)
            stmt.bindLong(8, if (nextPinned) 1L else 0L)
            stmt.bindLong(9, nowMs)
            if (deviceId == null) stmt.bindNull(10) else stmt.bindText(10, deviceId)
            stmt.bindText(11, id)
            stmt.step()
        }
        val afterJson = MemoryAuditSupport.entitySnapshotJson(conn, id)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = null,
            actor = MemoryMutationActor.USER,
            actionType = "UPDATE_ENTITY",
            targetTable = "entities",
            targetId = id,
            summaryUserText = "更新了「$nextName」的信息",
            reason = null,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, id, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return findEntityById(conn, id)
    }

    fun listActiveRelations(
        conn: SQLiteConnection,
        scope: MemoryAccessScope = MemoryAccessScope.PERSONAL,
        entityId: String? = null,
        includeInvalidated: Boolean = false,
    ): List<MemoryEntityRelation> {
        if (scope == MemoryAccessScope.OFF) return emptyList()
        val invClause = if (includeInvalidated) "" else "AND r.invalidated_at IS NULL"
        val entityClause = if (entityId == null) "" else "AND (r.from_entity_id = ? OR r.to_entity_id = ?)"
        val out = ArrayList<MemoryEntityRelation>()
        conn.prepare(
            """
            SELECT r.id, r.from_entity_id, fe.canonical_name, r.relation_type, r.to_entity_id, te.canonical_name,
                   r.label, r.attributes_json, r.valid_from, r.valid_until, r.invalidated_at, r.invalidated_by,
                   r.epistemic_tier, r.domain, r.sensitivity, r.source_event_id,
                   r.created_at, r.updated_at, r.revision
            FROM entity_relations r
            JOIN entities fe ON fe.id = r.from_entity_id AND fe.deleted_at IS NULL
            JOIN entities te ON te.id = r.to_entity_id AND te.deleted_at IS NULL
            WHERE r.deleted_at IS NULL $invClause $entityClause
            ORDER BY r.updated_at DESC
            """.trimIndent(),
        ).use { stmt ->
            if (entityId != null) {
                stmt.bindText(1, entityId)
                stmt.bindText(2, entityId)
            }
            while (stmt.step()) {
                val rel = readRelationRow(stmt)
                if (scope.allows(rel.domain, rel.sensitivity)) {
                    out += rel
                }
            }
        }
        return out
    }

    fun getRelationById(conn: SQLiteConnection, relationId: String): MemoryEntityRelation? =
        conn.prepare(
            """
            SELECT r.id, r.from_entity_id, fe.canonical_name, r.relation_type, r.to_entity_id, te.canonical_name,
                   r.label, r.attributes_json, r.valid_from, r.valid_until, r.invalidated_at, r.invalidated_by,
                   r.epistemic_tier, r.domain, r.sensitivity, r.source_event_id,
                   r.created_at, r.updated_at, r.revision
            FROM entity_relations r
            JOIN entities fe ON fe.id = r.from_entity_id
            JOIN entities te ON te.id = r.to_entity_id
            WHERE r.id = ? AND r.deleted_at IS NULL
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, relationId)
            if (stmt.step()) readRelationRow(stmt) else null
        }

    fun upsertRelation(
        conn: SQLiteConnection,
        op: UpsertRelationOp,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String? = null,
        deviceId: String? = null,
    ): Pair<MemoryEntityRelation?, GuardDecision> {
        val (fromEntity, _) = upsertEntity(
            conn = conn,
            ref = MemoryEntityRef(name = op.fromEntityName, entityType = MemoryEntityTypes.TOPIC, domain = op.domain, sensitivity = op.sensitivity),
            actor = actor,
            nowMs = nowMs,
            runId = runId,
            deviceId = deviceId,
        )
        val (toEntity, _) = upsertEntity(
            conn = conn,
            ref = MemoryEntityRef(name = op.toEntityName, entityType = MemoryEntityTypes.TOPIC, domain = op.domain, sensitivity = op.sensitivity),
            actor = actor,
            nowMs = nowMs,
            runId = runId,
            deviceId = deviceId,
        )
        val relType = MemoryRelationTypes.normalize(op.relationType)
        val existingId = conn.prepare(
            """
            SELECT id FROM entity_relations
            WHERE from_entity_id = ? AND relation_type = ? AND to_entity_id = ?
              AND deleted_at IS NULL AND invalidated_at IS NULL
            LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, fromEntity.id)
            stmt.bindText(2, relType)
            stmt.bindText(3, toEntity.id)
            if (stmt.step()) stmt.getText(0) else null
        }

        val cleanLabel = MemoryLimits.clampOptionalText(op.label, 200)
        val cleanDomain = MemoryDomains.normalize(op.domain, MemoryDomains.PERSONAL)

        if (existingId != null) {
            val existing = checkNotNull(getRelationById(conn, existingId))
            val beforeJson = MemoryAuditSupport.relationSnapshotJson(conn, existingId)
            val mergedAttrs = existing.attributes + op.attributes
            val nextLabel = cleanLabel ?: existing.label
            val nextTier = if (op.epistemicTier.rank > existing.epistemicTier.rank) op.epistemicTier else existing.epistemicTier
            conn.prepare(
                """
                UPDATE entity_relations
                SET label = ?, attributes_json = ?, valid_until = ?, epistemic_tier = ?,
                    domain = ?, sensitivity = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { stmt ->
                if (nextLabel == null) stmt.bindNull(1) else stmt.bindText(1, nextLabel)
                stmt.bindText(2, MemoryJsonSupport.encodeStringMap(mergedAttrs))
                if (op.validUntil == null) stmt.bindNull(3) else stmt.bindLong(3, op.validUntil)
                stmt.bindText(4, nextTier.wire)
                stmt.bindText(5, cleanDomain)
                stmt.bindText(6, op.sensitivity.wire)
                stmt.bindLong(7, nowMs)
                if (deviceId == null) stmt.bindNull(8) else stmt.bindText(8, deviceId)
                stmt.bindText(9, existingId)
                stmt.step()
            }
            val afterJson = MemoryAuditSupport.relationSnapshotJson(conn, existingId)
            MemoryAuditSupport.recordAudit(
                conn = conn,
                runId = runId,
                actor = actor,
                actionType = "UPDATE_RELATION",
                targetTable = "entity_relations",
                targetId = existingId,
                summaryUserText = op.userSummary,
                reason = null,
                beforeSnapshotJson = beforeJson,
                afterSnapshotJson = afterJson,
                nowMs = nowMs,
            )
            MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, existingId, nowMs)
            MemoryStorage.invalidateContextCache(conn)
            return getRelationById(conn, existingId) to GuardDecision.Allowed
        }

        val id = UUID.randomUUID().toString()
        conn.prepare(
            """
            INSERT INTO entity_relations(
                id, from_entity_id, relation_type, to_entity_id, label, attributes_json,
                valid_from, valid_until, invalidated_at, invalidated_by, epistemic_tier,
                domain, sensitivity, source_event_id, created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, NULL, ?, ?, NULL, 1, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            stmt.bindText(2, fromEntity.id)
            stmt.bindText(3, relType)
            stmt.bindText(4, toEntity.id)
            if (cleanLabel == null) stmt.bindNull(5) else stmt.bindText(5, cleanLabel)
            stmt.bindText(6, MemoryJsonSupport.encodeStringMap(op.attributes))
            stmt.bindLong(7, op.validFrom ?: nowMs)
            if (op.validUntil == null) stmt.bindNull(8) else stmt.bindLong(8, op.validUntil)
            stmt.bindText(9, op.epistemicTier.wire)
            stmt.bindText(10, cleanDomain)
            stmt.bindText(11, op.sensitivity.wire)
            stmt.bindLong(12, nowMs)
            stmt.bindLong(13, nowMs)
            if (deviceId == null) stmt.bindNull(14) else stmt.bindText(14, deviceId)
            stmt.step()
        }
        val afterJson = MemoryAuditSupport.relationSnapshotJson(conn, id)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "CREATE_RELATION",
            targetTable = "entity_relations",
            targetId = id,
            summaryUserText = op.userSummary,
            reason = null,
            beforeSnapshotJson = null,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, id, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return getRelationById(conn, id) to GuardDecision.Allowed
    }

    fun invalidateRelation(
        conn: SQLiteConnection,
        op: InvalidateRelationOp,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String? = null,
        deviceId: String? = null,
    ): GuardDecision {
        val existing = getRelationById(conn, op.relationId)
            ?: return GuardDecision.Blocked("Relation '${op.relationId}' not found")
        val guard = EpistemicGuard.canInvalidateRelation(existing, op.actingTier, actor)
        if (guard is GuardDecision.Blocked) return guard

        val beforeJson = MemoryAuditSupport.relationSnapshotJson(conn, op.relationId)
        conn.prepare(
            """
            UPDATE entity_relations
            SET invalidated_at = ?, invalidated_by = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, nowMs)
            val reasonText = MemoryLimits.clampOptionalText(op.reason, 200) ?: "invalidated"
            stmt.bindText(2, reasonText)
            stmt.bindLong(3, nowMs)
            if (deviceId == null) stmt.bindNull(4) else stmt.bindText(4, deviceId)
            stmt.bindText(5, op.relationId)
            stmt.step()
        }
        val afterJson = MemoryAuditSupport.relationSnapshotJson(conn, op.relationId)
        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "INVALIDATE_RELATION",
            targetTable = "entity_relations",
            targetId = op.relationId,
            summaryUserText = op.userSummary,
            reason = op.reason,
            beforeSnapshotJson = beforeJson,
            afterSnapshotJson = afterJson,
            nowMs = nowMs,
        )
        MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, op.relationId, nowMs)
        MemoryStorage.invalidateContextCache(conn)
        return GuardDecision.Allowed
    }

    fun mergeEntities(
        conn: SQLiteConnection,
        keepId: String,
        mergeIds: List<String>,
        mergedSummary: String?,
        actor: MemoryMutationActor,
        nowMs: Long,
        runId: String? = null,
        userSummary: String? = null,
        deviceId: String? = null,
    ): Int {
        val keeper = findEntityById(conn, keepId) ?: return 0
        val victims = mergeIds.distinct().filter { it != keeper.id }.mapNotNull { findEntityById(conn, it) }
        if (victims.isEmpty()) return 0

        if (actor != MemoryMutationActor.USER && victims.any { it.pinned }) return 0

        val entitySnapshots = ArrayList<JsonObject>()
        MemoryAuditSupport.entitySnapshotJson(conn, keeper.id)?.let { raw ->
            MemoryJsonSupport.parseObject(raw)?.let { entitySnapshots += it }
        }
        victims.forEach { v ->
            MemoryAuditSupport.entitySnapshotJson(conn, v.id)?.let { raw ->
                MemoryJsonSupport.parseObject(raw)?.let { entitySnapshots += it }
            }
        }

        val movedLinks = ArrayList<JsonObject>()
        val movedRelations = ArrayList<JsonObject>()
        val seenRelationIds = HashSet<String>()
        val touchedEventIds = LinkedHashSet<String>()

        val combinedAliases = LinkedHashSet(keeper.aliases)
        val combinedAttrs = LinkedHashMap(keeper.attributes)

        victims.forEach { victim ->
            combinedAliases += victim.canonicalName
            combinedAliases += victim.aliases
            victim.attributes.forEach { (k, v) -> combinedAttrs.putIfAbsent(k, v) }

            conn.prepare("SELECT event_id, role FROM event_entities WHERE entity_id = ?").use { stmt ->
                stmt.bindText(1, victim.id)
                val rows = ArrayList<Pair<String, String>>()
                while (stmt.step()) rows += stmt.getText(0) to stmt.getText(1)
                rows.forEach { (eventId, role) ->
                    val alreadyOnKeeper = conn.prepare(
                        "SELECT 1 FROM event_entities WHERE event_id = ? AND entity_id = ? LIMIT 1",
                    ).use { check ->
                        check.bindText(1, eventId)
                        check.bindText(2, keeper.id)
                        check.step()
                    }
                    movedLinks += buildJsonObject {
                        put("event_id", eventId)
                        put("old_entity_id", victim.id)
                        put("new_entity_id", keeper.id)
                        put("role", role)
                        put("existed_on_keeper", alreadyOnKeeper)
                    }
                    conn.prepare("INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, ?)").use { ins ->
                        ins.bindText(1, eventId)
                        ins.bindText(2, keeper.id)
                        ins.bindText(3, role)
                        ins.step()
                    }
                    conn.prepare("DELETE FROM event_entities WHERE event_id = ? AND entity_id = ?").use { del ->
                        del.bindText(1, eventId)
                        del.bindText(2, victim.id)
                        del.step()
                    }
                    touchedEventIds += eventId
                }
            }

            conn.prepare(
                "SELECT id, from_entity_id, to_entity_id FROM entity_relations WHERE (from_entity_id = ? OR to_entity_id = ?) AND deleted_at IS NULL",
            ).use { stmt ->
                stmt.bindText(1, victim.id)
                stmt.bindText(2, victim.id)
                val relRows = ArrayList<Triple<String, String, String>>()
                while (stmt.step()) relRows += Triple(stmt.getText(0), stmt.getText(1), stmt.getText(2))
                relRows.forEach { (relId, fromId, toId) ->
                    if (seenRelationIds.add(relId)) {
                        movedRelations += buildJsonObject {
                            put("id", relId)
                            put("from_entity_id", fromId)
                            put("to_entity_id", toId)
                        }
                    }
                    val nextFrom = if (fromId == victim.id) keeper.id else fromId
                    val nextTo = if (toId == victim.id) keeper.id else toId
                    if (nextFrom == nextTo) {
                        conn.prepare(
                            "UPDATE entity_relations SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
                        ).use { del ->
                            del.bindLong(1, nowMs)
                            del.bindLong(2, nowMs)
                            if (deviceId == null) del.bindNull(3) else del.bindText(3, deviceId)
                            del.bindText(4, relId)
                            del.step()
                        }
                    } else {
                        conn.prepare(
                            "UPDATE entity_relations SET from_entity_id = ?, to_entity_id = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
                        ).use { upd ->
                            upd.bindText(1, nextFrom)
                            upd.bindText(2, nextTo)
                            upd.bindLong(3, nowMs)
                            if (deviceId == null) upd.bindNull(4) else upd.bindText(4, deviceId)
                            upd.bindText(5, relId)
                            upd.step()
                        }
                    }
                    MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, relId, nowMs)
                }
            }

            conn.prepare(
                "UPDATE entities SET deleted_at = ?, merged_into = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { upd ->
                upd.bindLong(1, nowMs)
                upd.bindText(2, keeper.id)
                upd.bindLong(3, nowMs)
                if (deviceId == null) upd.bindNull(4) else upd.bindText(4, deviceId)
                upd.bindText(5, victim.id)
                upd.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, victim.id, nowMs)
        }

        val finalAliases = cleanAliases(combinedAliases.toList(), keeper.canonicalName)
        val finalSummary = MemoryLimits.clampOptionalText(mergedSummary, MemoryLimits.MAX_SUMMARY_CHARS)
            ?: keeper.summary
            ?: victims.firstNotNullOfOrNull { it.summary }

        conn.prepare(
            """
            UPDATE entities
            SET aliases_json = ?, attributes_json = ?, summary = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { upd ->
            upd.bindText(1, MemoryJsonSupport.encodeStringList(finalAliases))
            upd.bindText(2, MemoryJsonSupport.encodeStringMap(combinedAttrs))
            if (finalSummary == null) upd.bindNull(3) else upd.bindText(3, finalSummary)
            upd.bindLong(4, nowMs)
            if (deviceId == null) upd.bindNull(5) else upd.bindText(5, deviceId)
            upd.bindText(6, keeper.id)
            upd.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, keeper.id, nowMs)

        touchedEventIds.forEach { eventId ->
            conn.prepare(
                "UPDATE memory_events SET updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { upd ->
                upd.bindLong(1, nowMs)
                if (deviceId == null) upd.bindNull(2) else upd.bindText(2, deviceId)
                upd.bindText(3, eventId)
                upd.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, eventId, nowMs)
            MemoryStorage.refreshEventFts(conn, eventId)
        }

        val mergeBeforeSnapshot = buildJsonObject {
            put("entities", JsonArray(entitySnapshots))
            put("moved_event_links", JsonArray(movedLinks))
            put("moved_relations", JsonArray(movedRelations))
        }.toString()

        MemoryAuditSupport.recordAudit(
            conn = conn,
            runId = runId,
            actor = actor,
            actionType = "MERGE_ENTITIES",
            targetTable = "entity_merge",
            targetId = keeper.id,
            summaryUserText = userSummary ?: "合并了重复条目到「${keeper.canonicalName}」",
            reason = victims.joinToString(", ") { it.canonicalName },
            beforeSnapshotJson = mergeBeforeSnapshot,
            afterSnapshotJson = MemoryAuditSupport.entitySnapshotJson(conn, keeper.id),
            nowMs = nowMs,
        )
        MemoryStorage.invalidateContextCache(conn)
        return victims.size
    }

    private fun cleanAliases(items: List<String>, canonicalName: String): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in items) {
            val clean = MemoryLimits.clampText(raw, MemoryLimits.MAX_ENTITY_NAME_CHARS)
            if (clean.isEmpty() || clean.equals(canonicalName, ignoreCase = true)) continue
            if (seen.none { it.equals(clean, ignoreCase = true) }) {
                seen += clean
                if (seen.size >= MemoryLimits.MAX_ALIASES_PER_ENTITY) break
            }
        }
        return seen.toList()
    }

    private fun readEntityRow(stmt: SQLiteStatement): MemoryEntity = MemoryEntity(
        id = stmt.getText(0),
        canonicalName = stmt.getText(1),
        entityType = stmt.getText(2),
        aliases = MemoryJsonSupport.decodeStringList(stmt.getText(3)),
        attributes = MemoryJsonSupport.decodeStringMap(stmt.getText(4)),
        summary = if (stmt.isNull(5)) null else stmt.getText(5),
        domain = stmt.getText(6),
        sensitivity = MemorySensitivity.fromWire(stmt.getText(7)),
        pinned = stmt.getLong(8) != 0L,
        createdAt = stmt.getLong(9),
        updatedAt = stmt.getLong(10),
        revision = stmt.getLong(11),
    )

    private fun readRelationRow(stmt: SQLiteStatement): MemoryEntityRelation = MemoryEntityRelation(
        id = stmt.getText(0),
        fromEntityId = stmt.getText(1),
        fromEntityName = stmt.getText(2),
        relationType = stmt.getText(3),
        toEntityId = stmt.getText(4),
        toEntityName = stmt.getText(5),
        label = if (stmt.isNull(6)) null else stmt.getText(6),
        attributes = MemoryJsonSupport.decodeStringMap(stmt.getText(7)),
        validFrom = stmt.getLong(8),
        validUntil = if (stmt.isNull(9)) null else stmt.getLong(9),
        invalidatedAt = if (stmt.isNull(10)) null else stmt.getLong(10),
        invalidatedBy = if (stmt.isNull(11)) null else stmt.getText(11),
        epistemicTier = MemoryEpistemicTier.fromWire(stmt.getText(12)),
        domain = stmt.getText(13),
        sensitivity = MemorySensitivity.fromWire(stmt.getText(14)),
        sourceEventId = if (stmt.isNull(15)) null else stmt.getText(15),
        createdAt = stmt.getLong(16),
        updatedAt = stmt.getLong(17),
        revision = stmt.getLong(18),
    )

    private const val ENTITY_COLUMNS =
        "id, canonical_name, entity_type, aliases_json, attributes_json, summary, " +
            "domain, sensitivity, pinned, created_at, updated_at, revision"
}

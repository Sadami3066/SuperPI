package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import io.github.whywood.superpi.runtime.MemoryAuditEntry
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryMutationActor
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface GuardDecision {
    data object Allowed : GuardDecision

    data class Blocked(val reason: String) : GuardDecision
}

internal object EpistemicGuard {
    fun canMutateBlock(block: MemoryBlock, actor: MemoryMutationActor): GuardDecision {
        if (actor == MemoryMutationActor.USER) return GuardDecision.Allowed
        if (block.pinned) {
            return GuardDecision.Blocked("Protected: profile block '${block.slug}' is pinned by the user")
        }
        return GuardDecision.Allowed
    }

    fun canSupersedeEvent(
        target: MemoryEvent,
        replacementTier: MemoryEpistemicTier,
        actor: MemoryMutationActor,
    ): GuardDecision {
        if (actor == MemoryMutationActor.USER) return GuardDecision.Allowed
        if (target.pinned || target.epistemicTier == MemoryEpistemicTier.USER_PINNED) {
            return GuardDecision.Blocked("Protected: memory '${target.id}' is pinned by the user")
        }
        if (target.epistemicTier.rank >= MemoryEpistemicTier.USER_EXPLICIT.rank &&
            replacementTier.rank < target.epistemicTier.rank
        ) {
            return GuardDecision.Blocked(
                "Protected: inferred claim cannot silently overwrite user-explicit memory '${target.id}'",
            )
        }
        return GuardDecision.Allowed
    }

    fun canContractEvent(
        target: MemoryEvent,
        actingTier: MemoryEpistemicTier,
        actor: MemoryMutationActor,
    ): GuardDecision {
        if (actor == MemoryMutationActor.USER) return GuardDecision.Allowed
        if (target.pinned || target.epistemicTier == MemoryEpistemicTier.USER_PINNED) {
            return GuardDecision.Blocked("Protected: memory '${target.id}' is pinned by the user")
        }
        if (target.epistemicTier.rank >= MemoryEpistemicTier.USER_EXPLICIT.rank &&
            actingTier.rank < target.epistemicTier.rank
        ) {
            return GuardDecision.Blocked(
                "Protected: inferred reasoning cannot retract user-explicit memory '${target.id}'",
            )
        }
        return GuardDecision.Allowed
    }

    fun canInvalidateRelation(
        relation: MemoryEntityRelation,
        actingTier: MemoryEpistemicTier,
        actor: MemoryMutationActor,
    ): GuardDecision {
        if (actor == MemoryMutationActor.USER) return GuardDecision.Allowed
        if (relation.epistemicTier == MemoryEpistemicTier.USER_PINNED) {
            return GuardDecision.Blocked("Protected: relation '${relation.id}' is pinned by the user")
        }
        if (relation.epistemicTier.rank >= MemoryEpistemicTier.USER_EXPLICIT.rank &&
            actingTier.rank < relation.epistemicTier.rank
        ) {
            return GuardDecision.Blocked(
                "Protected: inferred observation cannot invalidate user-explicit relation '${relation.id}'",
            )
        }
        return GuardDecision.Allowed
    }

    fun canMutateEntity(entity: MemoryEntity, actor: MemoryMutationActor): GuardDecision {
        if (actor == MemoryMutationActor.USER) return GuardDecision.Allowed
        if (entity.pinned) {
            return GuardDecision.Blocked("Protected: entity '${entity.canonicalName}' is pinned by the user")
        }
        return GuardDecision.Allowed
    }
}

internal object MemoryAuditSupport {
    fun recordAudit(
        conn: SQLiteConnection,
        runId: String?,
        actor: MemoryMutationActor,
        actionType: String,
        targetTable: String,
        targetId: String,
        summaryUserText: String,
        reason: String?,
        beforeSnapshotJson: String?,
        afterSnapshotJson: String?,
        nowMs: Long,
        revertedAt: Long? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        conn.prepare(
            """
            INSERT INTO memory_audit_log(
                id, run_id, actor, action_type, target_table, target_id,
                summary_user_text, reason, before_snapshot_json, after_snapshot_json,
                reverted_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            bindNullableText(stmt, 2, runId)
            stmt.bindText(3, actor.wire)
            stmt.bindText(4, actionType)
            stmt.bindText(5, targetTable)
            stmt.bindText(6, targetId)
            stmt.bindText(7, MemoryLimits.clampText(summaryUserText, 240))
            bindNullableText(stmt, 8, MemoryLimits.clampOptionalText(reason, 400))
            bindNullableText(stmt, 9, beforeSnapshotJson)
            bindNullableText(stmt, 10, afterSnapshotJson)
            bindNullableLong(stmt, 11, revertedAt)
            stmt.bindLong(12, nowMs)
            stmt.step()
        }
        return id
    }

    fun listAuditLog(conn: SQLiteConnection, limit: Int): List<MemoryAuditEntry> {
        val capped = limit.coerceIn(1, 200)
        val out = ArrayList<MemoryAuditEntry>(capped)
        conn.prepare(
            """
            SELECT id, run_id, actor, action_type, target_table, target_id,
                   summary_user_text, reason, reverted_at, created_at,
                   (before_snapshot_json IS NOT NULL) AS has_before_snapshot
            FROM memory_audit_log
            ORDER BY created_at DESC, rowid DESC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, capped.toLong())
            while (stmt.step()) {
                val actionType = stmt.getText(3)
                val targetTable = stmt.getText(4)
                val hasBefore = stmt.getLong(10) != 0L
                val isRevertible = actionType != "SYSTEM_MIGRATION" &&
                    (hasBefore || targetTable != "entity_merge")
                out += MemoryAuditEntry(
                    id = stmt.getText(0),
                    runId = if (stmt.isNull(1)) null else stmt.getText(1),
                    actor = MemoryMutationActor.fromWire(stmt.getText(2)),
                    actionType = actionType,
                    targetTable = targetTable,
                    targetId = stmt.getText(5),
                    summaryUserText = stmt.getText(6),
                    reason = if (stmt.isNull(7)) null else stmt.getText(7),
                    revertedAt = if (stmt.isNull(8)) null else stmt.getLong(8),
                    createdAt = stmt.getLong(9),
                    revertible = isRevertible,
                )
            }
        }
        return out
    }

    fun blockSnapshotJson(conn: SQLiteConnection, id: String): String? =
        conn.prepare(
            """
            SELECT id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                   injection_mode, max_chars, owner, pinned, status, sort_order,
                   created_at, updated_at, deleted_at, revision
            FROM memory_blocks WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            buildJsonObject {
                put("id", stmt.getText(0))
                put("slug", stmt.getText(1))
                put("title", stmt.getText(2))
                put("purpose", stmt.getText(3))
                if (stmt.isNull(4)) put("schema_hint", JsonNull) else put("schema_hint", stmt.getText(4))
                put("content", stmt.getText(5))
                put("domain", stmt.getText(6))
                put("sensitivity", stmt.getText(7))
                put("injection_mode", stmt.getText(8))
                put("max_chars", stmt.getLong(9))
                put("owner", stmt.getText(10))
                put("pinned", stmt.getLong(11))
                put("status", stmt.getText(12))
                put("sort_order", stmt.getLong(13))
                put("created_at", stmt.getLong(14))
                put("updated_at", stmt.getLong(15))
                if (stmt.isNull(16)) put("deleted_at", JsonNull) else put("deleted_at", stmt.getLong(16))
                put("revision", stmt.getLong(17))
            }.toString()
        }

    fun eventSnapshotJson(conn: SQLiteConnection, id: String): String? {
        val base = conn.prepare(
            """
            SELECT id, event_kind, content, summary, qualifiers_json, semantic_keywords_json,
                   tags_json, domain, sensitivity, epistemic_tier, importance, pinned,
                   event_time, event_time_precision, valid_from, valid_until, status, superseded_by,
                   source_kind, source_session_id, source_entry_id, source_quote,
                   created_at, updated_at, deleted_at, revision
            FROM memory_events WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            buildJsonObject {
                put("id", stmt.getText(0))
                put("event_kind", stmt.getText(1))
                put("content", stmt.getText(2))
                if (stmt.isNull(3)) put("summary", JsonNull) else put("summary", stmt.getText(3))
                put("qualifiers_json", stmt.getText(4))
                put("semantic_keywords_json", stmt.getText(5))
                put("tags_json", stmt.getText(6))
                put("domain", stmt.getText(7))
                put("sensitivity", stmt.getText(8))
                put("epistemic_tier", stmt.getText(9))
                put("importance", stmt.getLong(10))
                put("pinned", stmt.getLong(11))
                put("event_time", stmt.getLong(12))
                put("event_time_precision", stmt.getText(13))
                put("valid_from", stmt.getLong(14))
                if (stmt.isNull(15)) put("valid_until", JsonNull) else put("valid_until", stmt.getLong(15))
                put("status", stmt.getText(16))
                if (stmt.isNull(17)) put("superseded_by", JsonNull) else put("superseded_by", stmt.getText(17))
                put("source_kind", stmt.getText(18))
                if (stmt.isNull(19)) put("source_session_id", JsonNull) else put("source_session_id", stmt.getText(19))
                if (stmt.isNull(20)) put("source_entry_id", JsonNull) else put("source_entry_id", stmt.getText(20))
                if (stmt.isNull(21)) put("source_quote", JsonNull) else put("source_quote", stmt.getText(21))
                put("created_at", stmt.getLong(22))
                put("updated_at", stmt.getLong(23))
                if (stmt.isNull(24)) put("deleted_at", JsonNull) else put("deleted_at", stmt.getLong(24))
                put("revision", stmt.getLong(25))
            }
        } ?: return null

        val links = ArrayList<JsonObject>()
        conn.prepare("SELECT entity_id, role FROM event_entities WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, id)
            while (stmt.step()) {
                links += buildJsonObject {
                    put("entity_id", stmt.getText(0))
                    put("role", stmt.getText(1))
                }
            }
        }
        return JsonObject(base + ("entity_links" to JsonArray(links))).toString()
    }

    fun entitySnapshotJson(conn: SQLiteConnection, id: String): String? =
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, attributes_json, summary,
                   domain, sensitivity, pinned, created_at, updated_at, deleted_at, revision, merged_into
            FROM entities WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            buildJsonObject {
                put("id", stmt.getText(0))
                put("canonical_name", stmt.getText(1))
                put("entity_type", stmt.getText(2))
                put("aliases_json", stmt.getText(3))
                put("attributes_json", stmt.getText(4))
                if (stmt.isNull(5)) put("summary", JsonNull) else put("summary", stmt.getText(5))
                put("domain", stmt.getText(6))
                put("sensitivity", stmt.getText(7))
                put("pinned", stmt.getLong(8))
                put("created_at", stmt.getLong(9))
                put("updated_at", stmt.getLong(10))
                if (stmt.isNull(11)) put("deleted_at", JsonNull) else put("deleted_at", stmt.getLong(11))
                put("revision", stmt.getLong(12))
                if (stmt.isNull(13)) put("merged_into", JsonNull) else put("merged_into", stmt.getText(13))
            }.toString()
        }

    fun relationSnapshotJson(conn: SQLiteConnection, id: String): String? =
        conn.prepare(
            """
            SELECT id, from_entity_id, relation_type, to_entity_id, label, attributes_json,
                   valid_from, valid_until, invalidated_at, invalidated_by, epistemic_tier,
                   domain, sensitivity, source_event_id, created_at, updated_at, deleted_at, revision
            FROM entity_relations WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            buildJsonObject {
                put("id", stmt.getText(0))
                put("from_entity_id", stmt.getText(1))
                put("relation_type", stmt.getText(2))
                put("to_entity_id", stmt.getText(3))
                if (stmt.isNull(4)) put("label", JsonNull) else put("label", stmt.getText(4))
                put("attributes_json", stmt.getText(5))
                put("valid_from", stmt.getLong(6))
                if (stmt.isNull(7)) put("valid_until", JsonNull) else put("valid_until", stmt.getLong(7))
                if (stmt.isNull(8)) put("invalidated_at", JsonNull) else put("invalidated_at", stmt.getLong(8))
                if (stmt.isNull(9)) put("invalidated_by", JsonNull) else put("invalidated_by", stmt.getText(9))
                put("epistemic_tier", stmt.getText(10))
                put("domain", stmt.getText(11))
                put("sensitivity", stmt.getText(12))
                if (stmt.isNull(13)) put("source_event_id", JsonNull) else put("source_event_id", stmt.getText(13))
                put("created_at", stmt.getLong(14))
                put("updated_at", stmt.getLong(15))
                if (stmt.isNull(16)) put("deleted_at", JsonNull) else put("deleted_at", stmt.getLong(16))
                put("revision", stmt.getLong(17))
            }.toString()
        }

    fun revertEntry(conn: SQLiteConnection, auditId: String, nowMs: Long, deviceId: String? = null): Boolean {
        var targetTable = ""
        var targetId = ""
        var beforeRaw: String? = null
        var afterRaw: String? = null
        var alreadyReverted = false
        var actionType = ""
        val found = conn.prepare(
            """
            SELECT action_type, target_table, target_id, before_snapshot_json, after_snapshot_json, reverted_at
            FROM memory_audit_log WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, auditId)
            if (!stmt.step()) {
                false
            } else {
                actionType = stmt.getText(0)
                targetTable = stmt.getText(1)
                targetId = stmt.getText(2)
                beforeRaw = if (stmt.isNull(3)) null else stmt.getText(3)
                afterRaw = if (stmt.isNull(4)) null else stmt.getText(4)
                alreadyReverted = !stmt.isNull(5)
                true
            }
        }
        if (!found || alreadyReverted || actionType == "SYSTEM_MIGRATION") return false

        val applied = when (targetTable) {
            "memory_blocks" -> revertBlock(conn, targetId, beforeRaw, nowMs, deviceId)
            "memory_events" -> revertEvent(conn, targetId, beforeRaw, afterRaw, actionType, nowMs, deviceId)
            "entities" -> revertEntity(conn, targetId, beforeRaw, nowMs, deviceId)
            "entity_relations" -> revertRelation(conn, targetId, beforeRaw, nowMs, deviceId)
            "entity_merge" -> revertEntityMerge(conn, beforeRaw, nowMs, deviceId)
            else -> false
        }
        if (!applied) return false

        conn.prepare("UPDATE memory_audit_log SET reverted_at = ? WHERE id = ?").use { stmt ->
            stmt.bindLong(1, nowMs)
            stmt.bindText(2, auditId)
            stmt.step()
        }
        MemoryStorage.invalidateContextCache(conn)
        return true
    }

    private fun revertBlock(conn: SQLiteConnection, id: String, beforeRaw: String?, nowMs: Long, deviceId: String?): Boolean {
        if (beforeRaw == null) {
            conn.prepare(
                "UPDATE memory_blocks SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                stmt.bindLong(2, nowMs)
                bindNullableText(stmt, 3, deviceId)
                stmt.bindText(4, id)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.BLOCK, id, nowMs)
            return true
        }
        val obj = MemoryJsonSupport.parseObject(beforeRaw) ?: return false
        val targetDeletedAt = obj.longVal("deleted_at")
        val baseSlug = obj.optStr("slug")?.takeIf { it.isNotBlank() } ?: conn.prepare(
            "SELECT slug FROM memory_blocks WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, id)
            if (stmt.step()) stmt.getText(0) else null
        } ?: return false
        val resolvedSlug = if (targetDeletedAt == null) {
            resolveAvailableBlockSlug(conn, baseSlug, id)
        } else {
            baseSlug
        }
        conn.prepare(
            """
            UPDATE memory_blocks
            SET slug = ?, title = ?, purpose = ?, schema_hint = ?, content = ?, domain = ?, sensitivity = ?,
                injection_mode = ?, max_chars = ?, pinned = ?, status = ?, sort_order = ?,
                updated_at = ?, deleted_at = ?, revision = revision + 1, updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, resolvedSlug)
            stmt.bindText(2, obj.str("title"))
            stmt.bindText(3, obj.str("purpose"))
            bindNullableText(stmt, 4, obj.optStr("schema_hint"))
            stmt.bindText(5, obj.str("content"))
            stmt.bindText(6, obj.str("domain"))
            stmt.bindText(7, obj.str("sensitivity"))
            stmt.bindText(8, obj.str("injection_mode"))
            stmt.bindLong(9, obj.longVal("max_chars") ?: 2_000L)
            stmt.bindLong(10, obj.longVal("pinned") ?: 0L)
            stmt.bindText(11, obj.str("status"))
            stmt.bindLong(12, obj.longVal("sort_order") ?: 100L)
            stmt.bindLong(13, nowMs)
            bindNullableLong(stmt, 14, targetDeletedAt)
            bindNullableText(stmt, 15, deviceId)
            stmt.bindText(16, id)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.BLOCK, id, nowMs)
        return true
    }

    private fun resolveAvailableBlockSlug(conn: SQLiteConnection, baseSlug: String, excludeId: String): String {
        var candidate = baseSlug
        var suffix = 2
        while (
            conn.prepare(
                "SELECT 1 FROM memory_blocks WHERE slug = ? AND deleted_at IS NULL AND id != ? LIMIT 1",
            ).use { stmt ->
                stmt.bindText(1, candidate)
                stmt.bindText(2, excludeId)
                stmt.step()
            }
        ) {
            candidate = "${baseSlug.take(MemoryLimits.MAX_SLUG_LENGTH - 4)}_${suffix++}"
        }
        return candidate
    }

    private fun revertEvent(
        conn: SQLiteConnection,
        id: String,
        beforeRaw: String?,
        afterRaw: String?,
        actionType: String,
        nowMs: Long,
        deviceId: String?,
    ): Boolean {
        if (beforeRaw == null) {
            conn.prepare(
                "UPDATE memory_events SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                stmt.bindLong(2, nowMs)
                bindNullableText(stmt, 3, deviceId)
                stmt.bindText(4, id)
                stmt.step()
            }
            conn.prepare("DELETE FROM memory_fts WHERE event_id = ?").use { stmt ->
                stmt.bindText(1, id)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
            return true
        }
        val obj = MemoryJsonSupport.parseObject(beforeRaw) ?: return false
        conn.prepare(
            """
            UPDATE memory_events
            SET event_kind = ?, content = ?, summary = ?, qualifiers_json = ?,
                semantic_keywords_json = ?, tags_json = ?, domain = ?, sensitivity = ?,
                epistemic_tier = ?, importance = ?, pinned = ?, event_time = ?,
                event_time_precision = ?, valid_from = ?, valid_until = ?, status = ?,
                superseded_by = ?, updated_at = ?, deleted_at = ?, revision = revision + 1,
                updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, obj.str("event_kind"))
            stmt.bindText(2, obj.str("content"))
            bindNullableText(stmt, 3, obj.optStr("summary"))
            stmt.bindText(4, obj.optStr("qualifiers_json") ?: "{}")
            stmt.bindText(5, obj.optStr("semantic_keywords_json") ?: "[]")
            stmt.bindText(6, obj.optStr("tags_json") ?: "[]")
            stmt.bindText(7, obj.str("domain"))
            stmt.bindText(8, obj.str("sensitivity"))
            stmt.bindText(9, obj.str("epistemic_tier"))
            stmt.bindLong(10, obj.longVal("importance") ?: 3L)
            stmt.bindLong(11, obj.longVal("pinned") ?: 0L)
            stmt.bindLong(12, obj.longVal("event_time") ?: nowMs)
            stmt.bindText(13, obj.optStr("event_time_precision") ?: "DAY")
            stmt.bindLong(14, obj.longVal("valid_from") ?: nowMs)
            bindNullableLong(stmt, 15, obj.longVal("valid_until"))
            stmt.bindText(16, obj.str("status"))
            bindNullableText(stmt, 17, obj.optStr("superseded_by"))
            stmt.bindLong(18, nowMs)
            bindNullableLong(stmt, 19, obj.longVal("deleted_at"))
            bindNullableText(stmt, 20, deviceId)
            stmt.bindText(21, id)
            stmt.step()
        }
        val links = obj["entity_links"] as? JsonArray
        if (links != null) {
            conn.prepare("DELETE FROM event_entities WHERE event_id = ?").use { stmt ->
                stmt.bindText(1, id)
                stmt.step()
            }
            links.forEach { entry ->
                val linkObj = entry as? JsonObject ?: return@forEach
                val entityId = linkObj.optStr("entity_id") ?: return@forEach
                val role = linkObj.optStr("role") ?: "MENTIONED"
                conn.prepare(
                    "INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, ?)",
                ).use { stmt ->
                    stmt.bindText(1, id)
                    stmt.bindText(2, entityId)
                    stmt.bindText(3, role)
                    stmt.step()
                }
            }
        }
        if (obj.longVal("deleted_at") != null) {
            conn.prepare("DELETE FROM memory_fts WHERE event_id = ?").use { stmt ->
                stmt.bindText(1, id)
                stmt.step()
            }
        } else {
            MemoryStorage.refreshEventFts(conn, id)
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, id, nowMs)
        if (actionType == "SUPERSEDE_EVENT" && afterRaw != null) {
            val replacementId = MemoryJsonSupport.parseObject(afterRaw)?.optStr("superseded_by")
            if (!replacementId.isNullOrBlank()) {
                conn.prepare(
                    "UPDATE memory_events SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ? AND deleted_at IS NULL",
                ).use { stmt ->
                    stmt.bindLong(1, nowMs)
                    stmt.bindLong(2, nowMs)
                    bindNullableText(stmt, 3, deviceId)
                    stmt.bindText(4, replacementId)
                    stmt.step()
                }
                val replacedChanged = conn.prepare("SELECT changes()").use { it.step() && it.getLong(0) > 0L }
                MemoryStorage.deleteEventFts(conn, replacementId)
                if (replacedChanged) {
                    MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, replacementId, nowMs)
                }
            }
        }
        return true
    }

    private fun revertEntity(conn: SQLiteConnection, id: String, beforeRaw: String?, nowMs: Long, deviceId: String?): Boolean {
        if (beforeRaw == null) {
            conn.prepare(
                "UPDATE entities SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                stmt.bindLong(2, nowMs)
                bindNullableText(stmt, 3, deviceId)
                stmt.bindText(4, id)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, id, nowMs)
            return true
        }
        val obj = MemoryJsonSupport.parseObject(beforeRaw) ?: return false
        conn.prepare(
            """
            UPDATE entities
            SET canonical_name = ?, entity_type = ?, aliases_json = ?, attributes_json = ?,
                summary = ?, domain = ?, sensitivity = ?, pinned = ?,
                updated_at = ?, deleted_at = ?, merged_into = ?, revision = revision + 1,
                updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, obj.str("canonical_name"))
            stmt.bindText(2, obj.str("entity_type"))
            stmt.bindText(3, obj.optStr("aliases_json") ?: "[]")
            stmt.bindText(4, obj.optStr("attributes_json") ?: "{}")
            bindNullableText(stmt, 5, obj.optStr("summary"))
            stmt.bindText(6, obj.str("domain"))
            stmt.bindText(7, obj.str("sensitivity"))
            stmt.bindLong(8, obj.longVal("pinned") ?: 0L)
            stmt.bindLong(9, nowMs)
            bindNullableLong(stmt, 10, obj.longVal("deleted_at"))
            bindNullableText(stmt, 11, obj.optStr("merged_into"))
            bindNullableText(stmt, 12, deviceId)
            stmt.bindText(13, id)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.ENTITY, id, nowMs)
        return true
    }

    private fun revertRelation(conn: SQLiteConnection, id: String, beforeRaw: String?, nowMs: Long, deviceId: String?): Boolean {
        if (beforeRaw == null) {
            conn.prepare(
                "UPDATE entity_relations SET deleted_at = ?, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                stmt.bindLong(2, nowMs)
                bindNullableText(stmt, 3, deviceId)
                stmt.bindText(4, id)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, id, nowMs)
            return true
        }
        val obj = MemoryJsonSupport.parseObject(beforeRaw) ?: return false
        conn.prepare(
            """
            UPDATE entity_relations
            SET relation_type = ?, label = ?, attributes_json = ?, valid_from = ?, valid_until = ?,
                invalidated_at = ?, invalidated_by = ?, epistemic_tier = ?, domain = ?, sensitivity = ?,
                updated_at = ?, deleted_at = ?, revision = revision + 1, updated_by_device_id = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, obj.str("relation_type"))
            bindNullableText(stmt, 2, obj.optStr("label"))
            stmt.bindText(3, obj.optStr("attributes_json") ?: "{}")
            stmt.bindLong(4, obj.longVal("valid_from") ?: nowMs)
            bindNullableLong(stmt, 5, obj.longVal("valid_until"))
            bindNullableLong(stmt, 6, obj.longVal("invalidated_at"))
            bindNullableText(stmt, 7, obj.optStr("invalidated_by"))
            stmt.bindText(8, obj.str("epistemic_tier"))
            stmt.bindText(9, obj.str("domain"))
            stmt.bindText(10, obj.str("sensitivity"))
            stmt.bindLong(11, nowMs)
            bindNullableLong(stmt, 12, obj.longVal("deleted_at"))
            bindNullableText(stmt, 13, deviceId)
            stmt.bindText(14, id)
            stmt.step()
        }
        MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, id, nowMs)
        return true
    }

    private fun revertEntityMerge(conn: SQLiteConnection, beforeRaw: String?, nowMs: Long, deviceId: String?): Boolean {
        val obj = MemoryJsonSupport.parseObject(beforeRaw ?: return false) ?: return false
        (obj["entities"] as? JsonArray)?.forEach { item ->
            val entityObj = item as? JsonObject ?: return@forEach
            val entityId = entityObj.optStr("id") ?: return@forEach
            revertEntity(conn, entityId, entityObj.toString(), nowMs, deviceId)
        }
        val revertedEventIds = LinkedHashSet<String>()
        (obj["moved_event_links"] as? JsonArray)?.forEach { item ->
            val link = item as? JsonObject ?: return@forEach
            val eventId = link.optStr("event_id") ?: return@forEach
            val oldEntityId = link.optStr("old_entity_id") ?: return@forEach
            val newEntityId = link.optStr("new_entity_id") ?: return@forEach
            val role = link.optStr("role") ?: "MENTIONED"
            val existedOnKeeper = link.boolVal("existed_on_keeper") == true
            if (!existedOnKeeper) {
                conn.prepare("DELETE FROM event_entities WHERE event_id = ? AND entity_id = ?").use { stmt ->
                    stmt.bindText(1, eventId)
                    stmt.bindText(2, newEntityId)
                    stmt.step()
                }
            }
            conn.prepare("INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, ?)").use { stmt ->
                stmt.bindText(1, eventId)
                stmt.bindText(2, oldEntityId)
                stmt.bindText(3, role)
                stmt.step()
            }
            MemoryStorage.refreshEventFts(conn, eventId)
            revertedEventIds += eventId
        }
        revertedEventIds.forEach { eventId ->
            conn.prepare(
                "UPDATE memory_events SET updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindLong(1, nowMs)
                bindNullableText(stmt, 2, deviceId)
                stmt.bindText(3, eventId)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.EVENT, eventId, nowMs)
        }
        (obj["moved_relations"] as? JsonArray)?.forEach { item ->
            val rel = item as? JsonObject ?: return@forEach
            val relId = rel.optStr("id") ?: return@forEach
            val fromId = rel.optStr("from_entity_id") ?: return@forEach
            val toId = rel.optStr("to_entity_id") ?: return@forEach
            conn.prepare(
                "UPDATE entity_relations SET from_entity_id = ?, to_entity_id = ?, deleted_at = NULL, updated_at = ?, revision = revision + 1, updated_by_device_id = ? WHERE id = ?",
            ).use { stmt ->
                stmt.bindText(1, fromId)
                stmt.bindText(2, toId)
                stmt.bindLong(3, nowMs)
                bindNullableText(stmt, 4, deviceId)
                stmt.bindText(5, relId)
                stmt.step()
            }
            MemorySyncStorage.markDirty(conn, MemorySyncKind.RELATION, relId, nowMs)
        }
        return true
    }

    private fun bindNullableText(stmt: SQLiteStatement, index: Int, value: String?) {
        if (value == null) stmt.bindNull(index) else stmt.bindText(index, value)
    }

    private fun bindNullableLong(stmt: SQLiteStatement, index: Int, value: Long?) {
        if (value == null) stmt.bindNull(index) else stmt.bindLong(index, value)
    }
}

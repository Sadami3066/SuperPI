package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import java.util.UUID

internal object MemorySyncStorage {
    private const val KEY_ACCOUNT_IDENTITY = "v3_account_identity"
    private const val KEY_LAST_CONSOLIDATION_GLOBAL = "v3_last_consolidation_at_global"
    private const val PREFIX_CURSOR = "v3_cursor:"
    private const val PREFIX_BASELINE = "v3_baseline:"
    private const val PREFIX_LAST_CONSOLIDATION = "v3_last_consolidation_at:"

    fun markDirty(conn: SQLiteConnection, kind: MemorySyncKind, targetId: String, nowMs: Long) {
        if (targetId.isBlank()) return
        val effectiveAt = if (nowMs > 0L) nowMs else 1L
        conn.prepare(
            """
            INSERT INTO memory_sync_ops(target_kind, target_id, op_id, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(target_kind, target_id) DO UPDATE SET
                op_id = excluded.op_id,
                updated_at = excluded.updated_at
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, kind.wire)
            stmt.bindText(2, targetId)
            stmt.bindText(3, UUID.randomUUID().toString())
            stmt.bindLong(4, effectiveAt)
            stmt.step()
        }
    }

    fun readPullCursor(conn: SQLiteConnection, accountKey: String): Long =
        readSyncState(conn, "$PREFIX_CURSOR$accountKey")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    fun commitPullCursor(
        conn: SQLiteConnection,
        accountKey: String,
        nextSince: Long,
        remoteLastConsolidationAt: Long?,
    ) {
        if (nextSince > 0L) {
            val existing = readPullCursor(conn, accountKey)
            if (nextSince > existing) {
                writeSyncState(conn, "$PREFIX_CURSOR$accountKey", nextSince.toString())
            }
        }
        if (remoteLastConsolidationAt != null && remoteLastConsolidationAt > 0L) {
            updateRemoteLastConsolidationAt(conn, accountKey, remoteLastConsolidationAt)
        }
    }

    fun remoteLastConsolidationAt(conn: SQLiteConnection, accountKey: String?): Long? {
        val raw = if (!accountKey.isNullOrBlank()) {
            readSyncState(conn, "$PREFIX_LAST_CONSOLIDATION$accountKey")
                ?: readSyncState(conn, KEY_LAST_CONSOLIDATION_GLOBAL)
        } else {
            readSyncState(conn, KEY_LAST_CONSOLIDATION_GLOBAL)
        }
        return raw?.toLongOrNull()?.takeIf { it > 0L }
    }

    fun updateRemoteLastConsolidationAt(conn: SQLiteConnection, accountKey: String, timestampMs: Long) {
        if (timestampMs <= 0L) return
        val existingAccount = readSyncState(conn, "$PREFIX_LAST_CONSOLIDATION$accountKey")?.toLongOrNull() ?: 0L
        if (timestampMs > existingAccount) {
            writeSyncState(conn, "$PREFIX_LAST_CONSOLIDATION$accountKey", timestampMs.toString())
        }
        val existingGlobal = readSyncState(conn, KEY_LAST_CONSOLIDATION_GLOBAL)?.toLongOrNull() ?: 0L
        if (timestampMs > existingGlobal) {
            writeSyncState(conn, KEY_LAST_CONSOLIDATION_GLOBAL, timestampMs.toString())
        }
    }

    fun clearSyncStateForAccount(conn: SQLiteConnection, accountKey: String) {
        deleteSyncState(conn, "$PREFIX_CURSOR$accountKey")
        deleteSyncState(conn, "$PREFIX_BASELINE$accountKey")
        deleteSyncState(conn, "$PREFIX_LAST_CONSOLIDATION$accountKey")
    }

    fun inspectAccountSwitch(conn: SQLiteConnection, accountIdentity: String): MemoryAccountSwitchCheck {
        val currentIdentity = readSyncState(conn, KEY_ACCOUNT_IDENTITY)
        val localCount = countSyncableRecords(conn)
        if (currentIdentity == null) {
            writeSyncState(conn, KEY_ACCOUNT_IDENTITY, accountIdentity)
            return MemoryAccountSwitchCheck(requiresPrompt = false, localRecordCount = localCount)
        }
        if (currentIdentity == accountIdentity) {
            return MemoryAccountSwitchCheck(requiresPrompt = false, localRecordCount = localCount)
        }
        if (localCount <= 0) {
            writeSyncState(conn, KEY_ACCOUNT_IDENTITY, accountIdentity)
            return MemoryAccountSwitchCheck(requiresPrompt = false, localRecordCount = 0)
        }
        return MemoryAccountSwitchCheck(requiresPrompt = true, localRecordCount = localCount)
    }

    fun resolveAccountSwitch(
        conn: SQLiteConnection,
        accountKey: String,
        accountIdentity: String,
        mode: MemoryAccountSwitchMode,
        nowMs: Long,
    ) {
        when (mode) {
            MemoryAccountSwitchMode.REPLACE_LOCAL -> {
                conn.execSQL("DELETE FROM event_entities")
                conn.execSQL("DELETE FROM entity_relations")
                conn.execSQL("DELETE FROM memory_events")
                conn.execSQL("DELETE FROM entities")
                conn.execSQL("DELETE FROM memory_block_proposals")
                conn.execSQL("DELETE FROM memory_blocks")
                conn.execSQL("DELETE FROM memory_fts")
                conn.execSQL("DELETE FROM memory_sync_ops")
                conn.execSQL("DELETE FROM context_cache")
                MemoryDatabase.seedBuiltInBlocks(conn, nowMs)
                writeSyncState(conn, "$PREFIX_CURSOR$accountKey", "0")
                writeSyncState(conn, "$PREFIX_BASELINE$accountKey", "1")
                deleteSyncState(conn, "$PREFIX_LAST_CONSOLIDATION$accountKey")
                deleteSyncState(conn, KEY_LAST_CONSOLIDATION_GLOBAL)
                writeSyncState(conn, KEY_ACCOUNT_IDENTITY, accountIdentity)
            }

            MemoryAccountSwitchMode.MERGE -> {
                writeSyncState(conn, "$PREFIX_CURSOR$accountKey", "0")
                writeSyncState(conn, "$PREFIX_BASELINE$accountKey", "0")
                writeSyncState(conn, KEY_ACCOUNT_IDENTITY, accountIdentity)
            }
        }
    }

    fun collectPendingPushBatch(
        conn: SQLiteConnection,
        accountKey: String,
        deviceId: String,
        limit: Int,
        nowMs: Long,
    ): List<MemorySyncOpItem> {
        val capped = limit.coerceIn(1, 200)
        if (readSyncState(conn, "$PREFIX_BASELINE$accountKey") != "1") {
            enqueueBaselineOps(conn, nowMs)
            writeSyncState(conn, "$PREFIX_BASELINE$accountKey", "1")
        }

        val pending = ArrayList<Triple<MemorySyncKind, String, String>>(capped)
        conn.prepare(
            """
            SELECT target_kind, target_id, op_id
            FROM memory_sync_ops
            ORDER BY CASE target_kind
                WHEN 'block' THEN 0
                WHEN 'entity' THEN 1
                WHEN 'event' THEN 2
                WHEN 'relation' THEN 3
                ELSE 4
            END ASC, updated_at ASC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, capped.toLong())
            while (stmt.step()) {
                val kind = MemorySyncKind.fromWire(stmt.getText(0)) ?: continue
                pending += Triple(kind, stmt.getText(1), stmt.getText(2))
            }
        }

        val items = ArrayList<MemorySyncOpItem>(pending.size)
        for ((kind, targetId, opId) in pending) {
            val loaded = when (kind) {
                MemorySyncKind.BLOCK -> loadBlockSyncRecord(conn, targetId, deviceId)?.let {
                    MemorySyncOpItem(opId = opId, kind = MemorySyncKind.BLOCK, block = it)
                }

                MemorySyncKind.ENTITY -> loadEntitySyncRecord(conn, targetId, deviceId)?.let {
                    MemorySyncOpItem(opId = opId, kind = MemorySyncKind.ENTITY, entity = it)
                }

                MemorySyncKind.EVENT -> loadEventSyncRecord(conn, targetId, deviceId)?.let {
                    MemorySyncOpItem(opId = opId, kind = MemorySyncKind.EVENT, event = it)
                }

                MemorySyncKind.RELATION -> loadRelationSyncRecord(conn, targetId, deviceId)?.let {
                    MemorySyncOpItem(opId = opId, kind = MemorySyncKind.RELATION, relation = it)
                }

                MemorySyncKind.PROPOSAL -> loadProposalSyncRecord(conn, targetId, deviceId)?.let {
                    MemorySyncOpItem(opId = opId, kind = MemorySyncKind.PROPOSAL, proposal = it)
                }
            }
            if (loaded != null) {
                items += loaded
            } else {
                conn.prepare("DELETE FROM memory_sync_ops WHERE target_kind = ? AND target_id = ?").use { del ->
                    del.bindText(1, kind.wire)
                    del.bindText(2, targetId)
                    del.step()
                }
            }
        }
        return items
    }

    fun markPushAcked(
        conn: SQLiteConnection,
        accountKey: String,
        acked: List<MemorySyncOpItem>,
        remoteLastConsolidationAt: Long?,
    ) {
        if (acked.isNotEmpty()) {
            conn.prepare(
                "DELETE FROM memory_sync_ops WHERE target_kind = ? AND target_id = ? AND op_id = ?",
            ).use { stmt ->
                acked.forEach { item ->
                    val targetId = itemTargetId(item) ?: return@forEach
                    stmt.bindText(1, item.kind.wire)
                    stmt.bindText(2, targetId)
                    stmt.bindText(3, item.opId)
                    stmt.step()
                    stmt.reset()
                }
            }
        }
        if (remoteLastConsolidationAt != null && remoteLastConsolidationAt > 0L) {
            updateRemoteLastConsolidationAt(conn, accountKey, remoteLastConsolidationAt)
        }
    }

    fun applyRemotePullBatch(
        conn: SQLiteConnection,
        accountKey: String,
        selfDeviceId: String,
        entries: List<MemoryRemotePullEntry>,
        nextSince: Long,
        remoteLastConsolidationAt: Long?,
        nowMs: Long,
        advanceCursor: Boolean = true,
    ): MemoryPullApplyResult {
        var appliedCount = 0
        var hadOverlap = false
        val deferred = ArrayList<MemoryRemotePullEntry>()
        val ordered = entries.sortedWith(compareBy<MemoryRemotePullEntry> { it.kind.pushOrder }.thenBy { it.seq })

        for (entry in ordered) {
            val isPeerOrigin = entry.deviceId.isNotBlank() && entry.deviceId != selfDeviceId
            when (entry.kind) {
                MemorySyncKind.BLOCK -> {
                    val block = entry.block ?: continue
                    if (isPeerOrigin && hasLocalEditOrDirty(conn, MemorySyncKind.BLOCK, block.id, selfDeviceId)) {
                        hadOverlap = true
                    }
                    if (applyRemoteBlock(conn, block, entry.deviceId, nowMs)) {
                        appliedCount++
                    }
                }

                MemorySyncKind.ENTITY -> {
                    val entity = entry.entity ?: continue
                    if (isPeerOrigin && hasLocalEditOrDirty(conn, MemorySyncKind.ENTITY, entity.id, selfDeviceId)) {
                        hadOverlap = true
                    }
                    if (applyRemoteEntity(conn, entity, entry.deviceId)) {
                        appliedCount++
                    }
                }

                MemorySyncKind.EVENT -> {
                    val event = entry.event ?: continue
                    if (isPeerOrigin && hasLocalEditOrDirty(conn, MemorySyncKind.EVENT, event.id, selfDeviceId)) {
                        hadOverlap = true
                    }
                    val (applied, missingEntityLinks) = applyRemoteEvent(conn, event, entry.deviceId)
                    if (applied) appliedCount++
                    if (missingEntityLinks) deferred += entry
                }

                MemorySyncKind.RELATION -> {
                    val relation = entry.relation ?: continue
                    if (isPeerOrigin && hasLocalEditOrDirty(conn, MemorySyncKind.RELATION, relation.id, selfDeviceId)) {
                        hadOverlap = true
                    }
                    val (applied, missingEntities) = applyRemoteRelation(conn, relation, entry.deviceId)
                    if (missingEntities) {
                        deferred += entry
                    } else if (applied) {
                        appliedCount++
                    }
                }

                MemorySyncKind.PROPOSAL -> {
                    val proposal = entry.proposal ?: continue
                    if (isPeerOrigin && hasLocalEditOrDirty(conn, MemorySyncKind.PROPOSAL, proposal.id, selfDeviceId)) {
                        hadOverlap = true
                    }
                    if (applyRemoteProposal(conn, proposal, entry.deviceId)) {
                        appliedCount++
                    }
                }
            }
        }

        if (appliedCount > 0) {
            MemoryStorage.invalidateContextCache(conn)
        }
        commitPullCursor(
            conn = conn,
            accountKey = accountKey,
            nextSince = if (advanceCursor) nextSince else 0L,
            remoteLastConsolidationAt = remoteLastConsolidationAt,
        )

        return MemoryPullApplyResult(
            appliedCount = appliedCount,
            deferredEntries = deferred,
            hadLocalAndRemoteOverlap = hadOverlap,
        )
    }

    private fun enqueueBaselineOps(conn: SQLiteConnection, nowMs: Long) {
        val fallbackTs = if (nowMs > 0L) nowMs else 1L
        val pairs = ArrayList<Triple<MemorySyncKind, String, Long>>()
        conn.prepare(
            """
            SELECT id, updated_at
            FROM memory_blocks
            WHERE NOT (owner = 'BUILTIN' AND updated_at = 0 AND content = '' AND deleted_at IS NULL)
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                pairs += Triple(MemorySyncKind.BLOCK, stmt.getText(0), stmt.getLong(1).coerceAtLeast(fallbackTs))
            }
        }
        conn.prepare("SELECT id, updated_at FROM entities").use { stmt ->
            while (stmt.step()) {
                pairs += Triple(MemorySyncKind.ENTITY, stmt.getText(0), stmt.getLong(1).coerceAtLeast(fallbackTs))
            }
        }
        conn.prepare("SELECT id, updated_at FROM memory_events").use { stmt ->
            while (stmt.step()) {
                pairs += Triple(MemorySyncKind.EVENT, stmt.getText(0), stmt.getLong(1).coerceAtLeast(fallbackTs))
            }
        }
        conn.prepare("SELECT id, updated_at FROM entity_relations").use { stmt ->
            while (stmt.step()) {
                pairs += Triple(MemorySyncKind.RELATION, stmt.getText(0), stmt.getLong(1).coerceAtLeast(fallbackTs))
            }
        }
        conn.prepare("SELECT id, updated_at FROM memory_block_proposals").use { stmt ->
            while (stmt.step()) {
                pairs += Triple(MemorySyncKind.PROPOSAL, stmt.getText(0), stmt.getLong(1).coerceAtLeast(fallbackTs))
            }
        }
        if (pairs.isEmpty()) return
        conn.prepare(
            """
            INSERT OR IGNORE INTO memory_sync_ops(target_kind, target_id, op_id, updated_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            pairs.forEach { (kind, id, ts) ->
                stmt.bindText(1, kind.wire)
                stmt.bindText(2, id)
                stmt.bindText(3, UUID.randomUUID().toString())
                stmt.bindLong(4, ts)
                stmt.step()
                stmt.reset()
            }
        }
    }

    private fun countSyncableRecords(conn: SQLiteConnection): Int {
        var total = 0
        val queries = listOf(
            "SELECT COUNT(*) FROM memory_blocks WHERE NOT (owner = 'BUILTIN' AND updated_at = 0 AND content = '' AND deleted_at IS NULL)",
            "SELECT COUNT(*) FROM entities WHERE deleted_at IS NULL",
            "SELECT COUNT(*) FROM memory_events WHERE deleted_at IS NULL",
            "SELECT COUNT(*) FROM entity_relations WHERE deleted_at IS NULL",
            "SELECT COUNT(*) FROM memory_block_proposals WHERE deleted_at IS NULL",
        )
        queries.forEach { sql ->
            conn.prepare(sql).use { stmt ->
                if (stmt.step()) total += stmt.getLong(0).toInt()
            }
        }
        return total
    }

    private fun applyRemoteBlock(
        conn: SQLiteConnection,
        incoming: MemoryBlockSyncRecord,
        sourceDeviceId: String,
        nowMs: Long,
    ): Boolean {
        val incomingDevice = incoming.updatedByDeviceId?.takeIf { it.isNotBlank() } ?: sourceDeviceId
        val existing = conn.prepare(
            """
            SELECT updated_at, updated_by_device_id, revision, owner, content, deleted_at, slug
            FROM memory_blocks WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            if (stmt.step()) {
                ExistingBlockMeta(
                    updatedAt = stmt.getLong(0),
                    deviceId = stmt.getNullableText(1).orEmpty(),
                    revision = stmt.getLong(2),
                    owner = stmt.getText(3),
                    content = stmt.getText(4),
                    deletedAt = stmt.getNullableLong(5),
                    slug = stmt.getText(6),
                )
            } else {
                null
            }
        }

        if (existing != null) {
            val isUntouchedSeed =
                existing.owner == MemoryBlockOwner.BUILTIN.wire &&
                    existing.updatedAt == 0L &&
                    existing.content.isEmpty() &&
                    existing.deletedAt == null
            if (!isUntouchedSeed) {
                val wins = compareVersionTuple(
                    incoming.updatedAt,
                    incomingDevice,
                    incoming.revision,
                    existing.updatedAt,
                    existing.deviceId,
                    existing.revision,
                )
                if (!wins) return false
            }
        }

        var collidingIdToSoftDelete: String? = null
        if (incoming.deletedAt == null) {
            conn.prepare(
                """
                SELECT id, updated_at, updated_by_device_id, revision, owner, content
                FROM memory_blocks
                WHERE slug = ? AND deleted_at IS NULL AND id != ?
                LIMIT 1
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, incoming.slug)
                stmt.bindText(2, incoming.id)
                if (stmt.step()) {
                    val otherId = stmt.getText(0)
                    val otherUpdated = stmt.getLong(1)
                    val otherDevice = stmt.getNullableText(2).orEmpty()
                    val otherRev = stmt.getLong(3)
                    val otherOwner = stmt.getText(4)
                    val otherContent = stmt.getText(5)
                    val otherIsUntouchedSeed =
                        otherOwner == MemoryBlockOwner.BUILTIN.wire &&
                            otherUpdated == 0L &&
                            otherContent.isEmpty()
                    val incomingWinsCollision = otherIsUntouchedSeed || compareVersionTuple(
                        incoming.updatedAt,
                        incomingDevice,
                        incoming.revision,
                        otherUpdated,
                        otherDevice,
                        otherRev,
                    )
                    if (!incomingWinsCollision) {
                        return false
                    }
                    collidingIdToSoftDelete = otherId
                }
            }
        }

        collidingIdToSoftDelete?.let { otherId ->
            val tombstoneAt = maxOf(nowMs, incoming.updatedAt)
            conn.prepare(
                "UPDATE memory_blocks SET deleted_at = ?, updated_at = ?, revision = revision + 1 WHERE id = ?",
            ).use { del ->
                del.bindLong(1, tombstoneAt)
                del.bindLong(2, tombstoneAt)
                del.bindText(3, otherId)
                del.step()
            }
            conn.prepare("DELETE FROM memory_sync_ops WHERE target_kind = 'block' AND target_id = ?").use { cleanOp ->
                cleanOp.bindText(1, otherId)
                cleanOp.step()
            }
        }

        conn.prepare(
            """
            INSERT INTO memory_blocks(
                id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                injection_mode, max_chars, owner, pinned, status, sort_order,
                created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                slug = excluded.slug,
                title = excluded.title,
                purpose = excluded.purpose,
                schema_hint = excluded.schema_hint,
                content = excluded.content,
                domain = excluded.domain,
                sensitivity = excluded.sensitivity,
                injection_mode = excluded.injection_mode,
                max_chars = excluded.max_chars,
                owner = excluded.owner,
                pinned = excluded.pinned,
                status = excluded.status,
                sort_order = excluded.sort_order,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                revision = excluded.revision,
                updated_by_device_id = excluded.updated_by_device_id
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            stmt.bindText(2, incoming.slug)
            stmt.bindText(3, incoming.title)
            stmt.bindText(4, incoming.purpose)
            stmt.bindNullableText(5, incoming.schemaHint)
            stmt.bindText(6, incoming.content)
            stmt.bindText(7, MemoryDomains.normalize(incoming.domain, MemoryDomains.PERSONAL))
            stmt.bindText(8, incoming.sensitivity.wire)
            stmt.bindText(9, incoming.injectionMode.wire)
            stmt.bindLong(10, incoming.maxChars.toLong())
            stmt.bindText(11, incoming.owner.wire)
            stmt.bindLong(12, if (incoming.pinned) 1L else 0L)
            stmt.bindText(13, incoming.status.wire)
            stmt.bindLong(14, incoming.sortOrder.toLong())
            stmt.bindLong(15, incoming.createdAt)
            stmt.bindLong(16, incoming.updatedAt)
            stmt.bindNullableLong(17, incoming.deletedAt)
            stmt.bindLong(18, incoming.revision)
            stmt.bindText(19, incomingDevice)
            stmt.step()
        }

        clearSupersededDirtyOp(conn, MemorySyncKind.BLOCK, incoming.id, incoming.updatedAt)
        return true
    }

    private fun applyRemoteEntity(
        conn: SQLiteConnection,
        incoming: MemoryEntitySyncRecord,
        sourceDeviceId: String,
    ): Boolean {
        val incomingDevice = incoming.updatedByDeviceId?.takeIf { it.isNotBlank() } ?: sourceDeviceId
        var existingName: String? = null
        var existingDeletedAt: Long? = null
        val existing = conn.prepare(
            """
            SELECT canonical_name, deleted_at, updated_at, updated_by_device_id, revision
            FROM entities WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            if (stmt.step()) {
                existingName = stmt.getText(0)
                existingDeletedAt = stmt.getNullableLong(1)
                VersionTuple(
                    updatedAt = stmt.getLong(2),
                    deviceId = stmt.getNullableText(3).orEmpty(),
                    revision = stmt.getLong(4),
                )
            } else {
                null
            }
        }

        if (existing != null) {
            val wins = compareVersionTuple(
                incoming.updatedAt,
                incomingDevice,
                incoming.revision,
                existing.updatedAt,
                existing.deviceId,
                existing.revision,
            )
            if (!wins) return false
        }

        conn.prepare(
            """
            INSERT INTO entities(
                id, canonical_name, entity_type, aliases_json, attributes_json, summary,
                domain, sensitivity, pinned, created_at, updated_at, deleted_at, revision,
                merged_into, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                canonical_name = excluded.canonical_name,
                entity_type = excluded.entity_type,
                aliases_json = excluded.aliases_json,
                attributes_json = excluded.attributes_json,
                summary = excluded.summary,
                domain = excluded.domain,
                sensitivity = excluded.sensitivity,
                pinned = excluded.pinned,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                revision = excluded.revision,
                merged_into = excluded.merged_into,
                updated_by_device_id = excluded.updated_by_device_id
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            stmt.bindText(2, incoming.canonicalName)
            stmt.bindText(3, incoming.entityType)
            stmt.bindText(4, MemoryJsonSupport.encodeStringList(incoming.aliases))
            stmt.bindText(5, incoming.attributesJson)
            stmt.bindNullableText(6, incoming.summary)
            stmt.bindText(7, MemoryDomains.normalize(incoming.domain, MemoryDomains.PERSONAL))
            stmt.bindText(8, incoming.sensitivity.wire)
            stmt.bindLong(9, if (incoming.pinned) 1L else 0L)
            stmt.bindLong(10, incoming.createdAt)
            stmt.bindLong(11, incoming.updatedAt)
            stmt.bindNullableLong(12, incoming.deletedAt)
            stmt.bindLong(13, incoming.revision)
            stmt.bindNullableText(14, incoming.mergedInto)
            stmt.bindText(15, incomingDevice)
            stmt.step()
        }

        if (existingName != incoming.canonicalName || existingDeletedAt != incoming.deletedAt) {
            val linkedEventIds = ArrayList<String>()
            conn.prepare("SELECT event_id FROM event_entities WHERE entity_id = ?").use { stmt ->
                stmt.bindText(1, incoming.id)
                while (stmt.step()) linkedEventIds += stmt.getText(0)
            }
            linkedEventIds.forEach { MemoryStorage.refreshEventFts(conn, it) }
        }

        clearSupersededDirtyOp(conn, MemorySyncKind.ENTITY, incoming.id, incoming.updatedAt)
        return true
    }

    private fun applyRemoteEvent(
        conn: SQLiteConnection,
        incoming: MemoryEventSyncRecord,
        sourceDeviceId: String,
    ): Pair<Boolean, Boolean> {
        val incomingDevice = incoming.updatedByDeviceId?.takeIf { it.isNotBlank() } ?: sourceDeviceId
        val existing = conn.prepare(
            "SELECT updated_at, updated_by_device_id, revision FROM memory_events WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            if (stmt.step()) {
                VersionTuple(
                    updatedAt = stmt.getLong(0),
                    deviceId = stmt.getNullableText(1).orEmpty(),
                    revision = stmt.getLong(2),
                )
            } else {
                null
            }
        }

        var rowApplied = true
        if (existing != null) {
            val wins = compareVersionTuple(
                incoming.updatedAt,
                incomingDevice,
                incoming.revision,
                existing.updatedAt,
                existing.deviceId,
                existing.revision,
            )
            if (!wins) {
                val isSameVersion = existing.updatedAt == incoming.updatedAt &&
                    existing.deviceId == incomingDevice &&
                    existing.revision == incoming.revision
                if (!isSameVersion) {
                    return false to false
                }
                rowApplied = false
            }
        }

        if (rowApplied) {
            conn.prepare(
                """
                INSERT INTO memory_events(
                    id, event_kind, content, summary, qualifiers_json, semantic_keywords_json,
                    tags_json, domain, sensitivity, epistemic_tier, importance, pinned,
                    event_time, event_time_precision, valid_from, valid_until, status, superseded_by,
                    source_kind, source_session_id, source_entry_id, source_quote,
                    access_count, last_accessed_at, activation_score,
                    created_at, updated_at, deleted_at, revision, updated_by_device_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, 1.0, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    event_kind = excluded.event_kind,
                    content = excluded.content,
                    summary = excluded.summary,
                    qualifiers_json = excluded.qualifiers_json,
                    semantic_keywords_json = excluded.semantic_keywords_json,
                    tags_json = excluded.tags_json,
                    domain = excluded.domain,
                    sensitivity = excluded.sensitivity,
                    epistemic_tier = excluded.epistemic_tier,
                    importance = excluded.importance,
                    pinned = excluded.pinned,
                    event_time = excluded.event_time,
                    event_time_precision = excluded.event_time_precision,
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
                stmt.bindText(1, incoming.id)
                stmt.bindText(2, incoming.eventKind.wire)
                stmt.bindText(3, incoming.content)
                stmt.bindNullableText(4, incoming.summary)
                stmt.bindText(5, incoming.qualifiersJson)
                stmt.bindText(6, MemoryJsonSupport.encodeStringList(incoming.semanticKeywords))
                stmt.bindText(7, MemoryJsonSupport.encodeStringList(incoming.tags))
                stmt.bindText(8, MemoryDomains.normalize(incoming.domain, MemoryDomains.PERSONAL))
                stmt.bindText(9, incoming.sensitivity.wire)
                stmt.bindText(10, incoming.epistemicTier.wire)
                stmt.bindLong(11, incoming.importance.toLong())
                stmt.bindLong(12, if (incoming.pinned) 1L else 0L)
                stmt.bindLong(13, incoming.eventTime)
                stmt.bindText(14, incoming.eventTimePrecision.wire)
                stmt.bindLong(15, incoming.validFrom)
                stmt.bindNullableLong(16, incoming.validUntil)
                stmt.bindText(17, incoming.status.wire)
                stmt.bindNullableText(18, incoming.supersededBy)
                stmt.bindText(19, incoming.sourceKind.wire)
                stmt.bindNullableText(20, incoming.sourceSessionId)
                stmt.bindNullableText(21, incoming.sourceEntryId)
                stmt.bindNullableText(22, incoming.sourceQuote)
                stmt.bindLong(23, incoming.createdAt)
                stmt.bindLong(24, incoming.updatedAt)
                stmt.bindNullableLong(25, incoming.deletedAt)
                stmt.bindLong(26, incoming.revision)
                stmt.bindText(27, incomingDevice)
                stmt.step()
            }
            clearSupersededDirtyOp(conn, MemorySyncKind.EVENT, incoming.id, incoming.updatedAt)
        }

        val (existingEntityIds, hasMissingEntity) = partitionEntityLinks(conn, incoming.entityLinks)
        val linksChanged = syncEventEntityLinks(conn, incoming.id, existingEntityIds)
        if (incoming.deletedAt != null) {
            MemoryStorage.deleteEventFts(conn, incoming.id)
        } else if (rowApplied || linksChanged) {
            MemoryStorage.refreshEventFts(conn, incoming.id)
        }
        return (rowApplied || linksChanged) to hasMissingEntity
    }

    private fun partitionEntityLinks(
        conn: SQLiteConnection,
        links: List<MemoryEventEntityLink>,
    ): Pair<List<MemoryEventEntityLink>, Boolean> {
        if (links.isEmpty()) return emptyList<MemoryEventEntityLink>() to false
        val present = ArrayList<MemoryEventEntityLink>(links.size)
        var missing = false
        conn.prepare("SELECT 1 FROM entities WHERE id = ? LIMIT 1").use { stmt ->
            links.forEach { link ->
                stmt.bindText(1, link.entityId)
                val exists = stmt.step()
                stmt.reset()
                if (exists) present += link else missing = true
            }
        }
        return present to missing
    }

    private fun syncEventEntityLinks(
        conn: SQLiteConnection,
        eventId: String,
        targetLinks: List<MemoryEventEntityLink>,
    ): Boolean {
        val current = HashMap<String, String>()
        conn.prepare("SELECT entity_id, role FROM event_entities WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, eventId)
            while (stmt.step()) {
                current[stmt.getText(0)] = stmt.getText(1)
            }
        }
        val desired = targetLinks.associate { it.entityId to it.role }
        if (current == desired) return false

        conn.prepare("DELETE FROM event_entities WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, eventId)
            stmt.step()
        }
        if (targetLinks.isNotEmpty()) {
            conn.prepare(
                "INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, ?)",
            ).use { stmt ->
                targetLinks.forEach { link ->
                    stmt.bindText(1, eventId)
                    stmt.bindText(2, link.entityId)
                    stmt.bindText(3, link.role)
                    stmt.step()
                    stmt.reset()
                }
            }
        }
        return true
    }

    private fun applyRemoteRelation(
        conn: SQLiteConnection,
        incoming: MemoryRelationSyncRecord,
        sourceDeviceId: String,
    ): Pair<Boolean, Boolean> {
        val fromExists = entityRowExists(conn, incoming.fromEntityId)
        val toExists = if (incoming.toEntityId == incoming.fromEntityId) {
            fromExists
        } else {
            entityRowExists(conn, incoming.toEntityId)
        }
        if (!fromExists || !toExists) {
            return false to true
        }

        val incomingDevice = incoming.updatedByDeviceId?.takeIf { it.isNotBlank() } ?: sourceDeviceId
        val existing = conn.prepare(
            "SELECT updated_at, updated_by_device_id, revision FROM entity_relations WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            if (stmt.step()) {
                VersionTuple(
                    updatedAt = stmt.getLong(0),
                    deviceId = stmt.getNullableText(1).orEmpty(),
                    revision = stmt.getLong(2),
                )
            } else {
                null
            }
        }

        if (existing != null) {
            val wins = compareVersionTuple(
                incoming.updatedAt,
                incomingDevice,
                incoming.revision,
                existing.updatedAt,
                existing.deviceId,
                existing.revision,
            )
            if (!wins) return false to false
        }

        conn.prepare(
            """
            INSERT INTO entity_relations(
                id, from_entity_id, relation_type, to_entity_id, label, attributes_json,
                valid_from, valid_until, invalidated_at, invalidated_by, epistemic_tier,
                domain, sensitivity, source_event_id, created_at, updated_at, deleted_at,
                revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                from_entity_id = excluded.from_entity_id,
                relation_type = excluded.relation_type,
                to_entity_id = excluded.to_entity_id,
                label = excluded.label,
                attributes_json = excluded.attributes_json,
                valid_from = excluded.valid_from,
                valid_until = excluded.valid_until,
                invalidated_at = excluded.invalidated_at,
                invalidated_by = excluded.invalidated_by,
                epistemic_tier = excluded.epistemic_tier,
                domain = excluded.domain,
                sensitivity = excluded.sensitivity,
                source_event_id = excluded.source_event_id,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                revision = excluded.revision,
                updated_by_device_id = excluded.updated_by_device_id
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            stmt.bindText(2, incoming.fromEntityId)
            stmt.bindText(3, incoming.relationType)
            stmt.bindText(4, incoming.toEntityId)
            stmt.bindNullableText(5, incoming.label)
            stmt.bindText(6, incoming.attributesJson)
            stmt.bindLong(7, incoming.validFrom)
            stmt.bindNullableLong(8, incoming.validUntil)
            stmt.bindNullableLong(9, incoming.invalidatedAt)
            stmt.bindNullableText(10, incoming.invalidatedBy)
            stmt.bindText(11, incoming.epistemicTier.wire)
            stmt.bindText(12, MemoryDomains.normalize(incoming.domain, MemoryDomains.PERSONAL))
            stmt.bindText(13, incoming.sensitivity.wire)
            stmt.bindNullableText(14, incoming.sourceEventId)
            stmt.bindLong(15, incoming.createdAt)
            stmt.bindLong(16, incoming.updatedAt)
            stmt.bindNullableLong(17, incoming.deletedAt)
            stmt.bindLong(18, incoming.revision)
            stmt.bindText(19, incomingDevice)
            stmt.step()
        }

        clearSupersededDirtyOp(conn, MemorySyncKind.RELATION, incoming.id, incoming.updatedAt)
        return true to false
    }

    private fun applyRemoteProposal(
        conn: SQLiteConnection,
        incoming: MemoryProposalSyncRecord,
        sourceDeviceId: String,
    ): Boolean {
        val incomingDevice = incoming.updatedByDeviceId?.takeIf { it.isNotBlank() } ?: sourceDeviceId
        val existing = conn.prepare(
            "SELECT updated_at, updated_by_device_id, revision FROM memory_block_proposals WHERE id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            if (stmt.step()) {
                VersionTuple(
                    updatedAt = stmt.getLong(0),
                    deviceId = stmt.getNullableText(1).orEmpty(),
                    revision = stmt.getLong(2),
                )
            } else {
                null
            }
        }

        if (existing != null) {
            val wins = compareVersionTuple(
                incoming.updatedAt,
                incomingDevice,
                incoming.revision,
                existing.updatedAt,
                existing.deviceId,
                existing.revision,
            )
            if (!wins) return false
        }

        conn.prepare(
            """
            INSERT INTO memory_block_proposals(
                id, slug, title, purpose, domain, sensitivity, seed_content,
                supporting_event_ids_json, reason, status, created_at, resolved_at,
                updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                slug = excluded.slug,
                title = excluded.title,
                purpose = excluded.purpose,
                domain = excluded.domain,
                sensitivity = excluded.sensitivity,
                seed_content = excluded.seed_content,
                supporting_event_ids_json = excluded.supporting_event_ids_json,
                reason = excluded.reason,
                status = excluded.status,
                created_at = excluded.created_at,
                resolved_at = excluded.resolved_at,
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                revision = excluded.revision,
                updated_by_device_id = excluded.updated_by_device_id
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, incoming.id)
            stmt.bindText(2, incoming.slug)
            stmt.bindText(3, incoming.title)
            stmt.bindText(4, incoming.purpose)
            stmt.bindText(5, MemoryDomains.normalize(incoming.domain, MemoryDomains.PERSONAL))
            stmt.bindText(6, incoming.sensitivity.wire)
            stmt.bindText(7, incoming.seedContent)
            stmt.bindText(8, MemoryJsonSupport.encodeStringList(incoming.supportingEventIds))
            stmt.bindText(9, incoming.reason)
            stmt.bindText(10, incoming.status.wire)
            stmt.bindLong(11, incoming.createdAt)
            stmt.bindNullableLong(12, incoming.resolvedAt)
            stmt.bindLong(13, incoming.updatedAt)
            stmt.bindNullableLong(14, incoming.deletedAt)
            stmt.bindLong(15, incoming.revision)
            stmt.bindText(16, incomingDevice)
            stmt.step()
        }

        clearSupersededDirtyOp(conn, MemorySyncKind.PROPOSAL, incoming.id, incoming.updatedAt)
        return true
    }

    private fun loadBlockSyncRecord(
        conn: SQLiteConnection,
        id: String,
        fallbackDeviceId: String,
    ): MemoryBlockSyncRecord? =
        conn.prepare(
            """
            SELECT id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                   injection_mode, max_chars, owner, pinned, status, sort_order,
                   created_at, updated_at, deleted_at, revision, updated_by_device_id
            FROM memory_blocks WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            val createdAt = stmt.getLong(14)
            val rawUpdatedAt = stmt.getLong(15)
            val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
            val cleanSlug = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { cleanId.take(MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS).ifEmpty { "block" } }
            val cleanTitle = MemoryLimits.clampText(stmt.getText(2), MemoryLimits.MAX_ENTITY_NAME_CHARS)
                .ifEmpty { cleanSlug }
            val cleanPurpose = MemoryLimits.clampText(stmt.getText(3), MemoryLimits.MAX_SUMMARY_CHARS)
                .ifEmpty { cleanTitle }
            val cleanDomain = MemoryLimits.clampText(stmt.getText(6), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { MemoryDomains.PERSONAL }
            val safeDeviceId = MemoryLimits.clampText(
                stmt.getNullableText(18)?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
                MemoryLimits.MAX_ID_LENGTH,
            )
            MemoryBlockSyncRecord(
                id = cleanId,
                slug = cleanSlug,
                title = cleanTitle,
                purpose = cleanPurpose,
                schemaHint = MemoryLimits.clampOptionalText(stmt.getNullableText(4), MemoryLimits.MAX_SUMMARY_CHARS),
                content = MemoryLimits.clampText(stmt.getText(5), MemoryLimits.MAX_SYNC_BLOCK_CONTENT_CHARS),
                domain = cleanDomain,
                sensitivity = MemorySensitivity.fromWire(stmt.getText(7)),
                injectionMode = MemoryBlockInjectionMode.fromWire(stmt.getText(8)),
                maxChars = stmt.getLong(9).toInt().coerceIn(1, MemoryLimits.MAX_SYNC_BLOCK_CONTENT_CHARS),
                owner = MemoryBlockOwner.fromWire(stmt.getText(10)),
                pinned = stmt.getLong(11) != 0L,
                status = MemoryBlockStatus.fromWire(stmt.getText(12)),
                sortOrder = stmt.getLong(13).toInt(),
                createdAt = createdAt,
                updatedAt = if (rawUpdatedAt > 0L) rawUpdatedAt else createdAt,
                deletedAt = stmt.getNullableLong(16),
                revision = stmt.getLong(17).coerceAtLeast(1L),
                updatedByDeviceId = safeDeviceId,
            )
        }

    private fun loadEntitySyncRecord(
        conn: SQLiteConnection,
        id: String,
        fallbackDeviceId: String,
    ): MemoryEntitySyncRecord? =
        conn.prepare(
            """
            SELECT id, canonical_name, entity_type, aliases_json, attributes_json, summary,
                   domain, sensitivity, pinned, created_at, updated_at, deleted_at, revision,
                   merged_into, updated_by_device_id
            FROM entities WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
            val cleanName = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_ENTITY_NAME_CHARS)
                .ifEmpty { cleanId.take(MemoryLimits.MAX_ENTITY_NAME_CHARS).ifEmpty { "-" } }
            val cleanEntityType = MemoryLimits.clampText(stmt.getText(2), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { "OTHER" }
            val cleanDomain = MemoryLimits.clampText(stmt.getText(6), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { MemoryDomains.PERSONAL }
            val cleanAliases = MemoryJsonSupport.decodeStringList(stmt.getText(3))
                .asSequence()
                .map { MemoryLimits.clampText(it, MemoryLimits.MAX_ENTITY_NAME_CHARS) }
                .filter { it.isNotEmpty() && !it.equals(cleanName, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(MemoryLimits.MAX_SYNC_LIST_ITEMS)
                .toList()
            val safeDeviceId = MemoryLimits.clampText(
                stmt.getNullableText(14)?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
                MemoryLimits.MAX_ID_LENGTH,
            )
            MemoryEntitySyncRecord(
                id = cleanId,
                canonicalName = cleanName,
                entityType = cleanEntityType,
                aliases = cleanAliases,
                attributesJson = normalizeObjectJson(stmt.getText(4)),
                summary = MemoryLimits.clampOptionalText(stmt.getNullableText(5), MemoryLimits.MAX_SUMMARY_CHARS),
                domain = cleanDomain,
                sensitivity = MemorySensitivity.fromWire(stmt.getText(7)),
                pinned = stmt.getLong(8) != 0L,
                createdAt = stmt.getLong(9),
                updatedAt = stmt.getLong(10),
                deletedAt = stmt.getNullableLong(11),
                revision = stmt.getLong(12).coerceAtLeast(1L),
                mergedInto = MemoryLimits.clampOptionalText(stmt.getNullableText(13), MemoryLimits.MAX_ID_LENGTH),
                updatedByDeviceId = safeDeviceId,
            )
        }

    private fun loadEventSyncRecord(
        conn: SQLiteConnection,
        id: String,
        fallbackDeviceId: String,
    ): MemoryEventSyncRecord? {
        val base = conn.prepare(
            """
            SELECT id, event_kind, content, summary, qualifiers_json, semantic_keywords_json,
                   tags_json, domain, sensitivity, epistemic_tier, importance, pinned,
                   event_time, event_time_precision, valid_from, valid_until, status, superseded_by,
                   source_kind, source_session_id, source_entry_id, source_quote,
                   created_at, updated_at, deleted_at, revision, updated_by_device_id
            FROM memory_events WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
            val cleanSummary = MemoryLimits.clampOptionalText(stmt.getNullableText(3), MemoryLimits.MAX_SUMMARY_CHARS)
            val cleanContent = MemoryLimits.clampText(stmt.getText(2), MemoryLimits.MAX_CONTENT_CHARS)
                .ifEmpty { cleanSummary ?: "-" }
            val cleanKeywords = MemoryJsonSupport.decodeStringList(stmt.getText(5))
                .asSequence()
                .map { MemoryLimits.clampText(it, MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS) }
                .filter { it.isNotEmpty() }
                .take(MemoryLimits.MAX_SYNC_LIST_ITEMS)
                .toList()
            val cleanTags = MemoryJsonSupport.decodeStringList(stmt.getText(6))
                .asSequence()
                .map { MemoryLimits.clampText(it, MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS) }
                .filter { it.isNotEmpty() }
                .take(MemoryLimits.MAX_SYNC_LIST_ITEMS)
                .toList()
            val cleanDomain = MemoryLimits.clampText(stmt.getText(7), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { MemoryDomains.PERSONAL }
            val safeDeviceId = MemoryLimits.clampText(
                stmt.getNullableText(26)?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
                MemoryLimits.MAX_ID_LENGTH,
            )
            MemoryEventSyncRecord(
                id = cleanId,
                eventKind = MemoryEventKind.fromWire(stmt.getText(1)) ?: MemoryEventKind.FACT,
                content = cleanContent,
                summary = cleanSummary,
                qualifiersJson = normalizeObjectJson(stmt.getText(4)),
                semanticKeywords = cleanKeywords,
                tags = cleanTags,
                domain = cleanDomain,
                sensitivity = MemorySensitivity.fromWire(stmt.getText(8)),
                epistemicTier = MemoryEpistemicTier.fromWire(stmt.getText(9)),
                importance = stmt.getLong(10).toInt().coerceIn(1, 5),
                pinned = stmt.getLong(11) != 0L,
                eventTime = stmt.getLong(12),
                eventTimePrecision = MemoryEventTimePrecision.fromWire(stmt.getText(13)),
                validFrom = stmt.getLong(14),
                validUntil = stmt.getNullableLong(15),
                status = MemoryItemStatus.fromWire(stmt.getText(16)),
                supersededBy = MemoryLimits.clampOptionalText(stmt.getNullableText(17), MemoryLimits.MAX_ID_LENGTH),
                sourceKind = MemorySourceKind.fromWire(stmt.getText(18)),
                sourceSessionId = MemoryLimits.clampOptionalText(stmt.getNullableText(19), MemoryLimits.MAX_ID_LENGTH),
                sourceEntryId = MemoryLimits.clampOptionalText(stmt.getNullableText(20), MemoryLimits.MAX_ID_LENGTH),
                sourceQuote = MemoryLimits.clampOptionalText(stmt.getNullableText(21), MemoryLimits.MAX_SUMMARY_CHARS),
                createdAt = stmt.getLong(22),
                updatedAt = stmt.getLong(23),
                deletedAt = stmt.getNullableLong(24),
                revision = stmt.getLong(25).coerceAtLeast(1L),
                updatedByDeviceId = safeDeviceId,
            )
        }

        val links = ArrayList<MemoryEventEntityLink>()
        conn.prepare("SELECT entity_id, role FROM event_entities WHERE event_id = ?").use { stmt ->
            stmt.bindText(1, id)
            while (stmt.step() && links.size < MemoryLimits.MAX_SYNC_LIST_ITEMS) {
                val entityId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
                if (entityId.isEmpty()) continue
                val role = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                    .ifEmpty { "MENTIONED" }
                links += MemoryEventEntityLink(entityId = entityId, role = role)
            }
        }
        return base.copy(entityLinks = links)
    }

    private fun loadRelationSyncRecord(
        conn: SQLiteConnection,
        id: String,
        fallbackDeviceId: String,
    ): MemoryRelationSyncRecord? =
        conn.prepare(
            """
            SELECT id, from_entity_id, relation_type, to_entity_id, label, attributes_json,
                   valid_from, valid_until, invalidated_at, invalidated_by, epistemic_tier,
                   domain, sensitivity, source_event_id, created_at, updated_at, deleted_at,
                   revision, updated_by_device_id
            FROM entity_relations WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
            val fromId = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_ID_LENGTH)
            val toId = MemoryLimits.clampText(stmt.getText(3), MemoryLimits.MAX_ID_LENGTH)
            if (cleanId.isEmpty() || fromId.isEmpty() || toId.isEmpty()) return null
            val relType = MemoryLimits.clampText(stmt.getText(2), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { "RELATED_TO" }
            val cleanDomain = MemoryLimits.clampText(stmt.getText(11), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { MemoryDomains.PERSONAL }
            val safeDeviceId = MemoryLimits.clampText(
                stmt.getNullableText(18)?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
                MemoryLimits.MAX_ID_LENGTH,
            )
            MemoryRelationSyncRecord(
                id = cleanId,
                fromEntityId = fromId,
                relationType = relType,
                toEntityId = toId,
                label = MemoryLimits.clampOptionalText(stmt.getNullableText(4), MemoryLimits.MAX_ENTITY_NAME_CHARS),
                attributesJson = normalizeObjectJson(stmt.getText(5)),
                validFrom = stmt.getLong(6),
                validUntil = stmt.getNullableLong(7),
                invalidatedAt = stmt.getNullableLong(8),
                invalidatedBy = MemoryLimits.clampOptionalText(stmt.getNullableText(9), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS),
                epistemicTier = MemoryEpistemicTier.fromWire(stmt.getText(10)),
                domain = cleanDomain,
                sensitivity = MemorySensitivity.fromWire(stmt.getText(12)),
                sourceEventId = MemoryLimits.clampOptionalText(stmt.getNullableText(13), MemoryLimits.MAX_ID_LENGTH),
                createdAt = stmt.getLong(14),
                updatedAt = stmt.getLong(15),
                deletedAt = stmt.getNullableLong(16),
                revision = stmt.getLong(17).coerceAtLeast(1L),
                updatedByDeviceId = safeDeviceId,
            )
        }

    private fun loadProposalSyncRecord(
        conn: SQLiteConnection,
        id: String,
        fallbackDeviceId: String,
    ): MemoryProposalSyncRecord? =
        conn.prepare(
            """
            SELECT id, slug, title, purpose, domain, sensitivity, seed_content,
                   supporting_event_ids_json, reason, status, created_at, resolved_at,
                   updated_at, deleted_at, revision, updated_by_device_id
            FROM memory_block_proposals WHERE id = ? LIMIT 1
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) return null
            val createdAt = stmt.getLong(10)
            val resolvedAt = stmt.getNullableLong(11)
            val rawUpdatedAt = stmt.getLong(12)
            val cleanId = MemoryLimits.clampText(stmt.getText(0), MemoryLimits.MAX_ID_LENGTH)
            val cleanSlug = MemoryLimits.clampText(stmt.getText(1), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { cleanId.take(MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS).ifEmpty { "proposal" } }
            val cleanTitle = MemoryLimits.clampText(stmt.getText(2), MemoryLimits.MAX_ENTITY_NAME_CHARS)
                .ifEmpty { cleanSlug }
            val cleanPurpose = MemoryLimits.clampText(stmt.getText(3), MemoryLimits.MAX_SUMMARY_CHARS)
                .ifEmpty { cleanTitle }
            val cleanDomain = MemoryLimits.clampText(stmt.getText(4), MemoryLimits.MAX_SYNC_SHORT_TOKEN_CHARS)
                .ifEmpty { MemoryDomains.PERSONAL }
            val cleanReason = MemoryLimits.clampText(stmt.getText(8), MemoryLimits.MAX_SUMMARY_CHARS)
                .ifEmpty { cleanPurpose }
            val cleanEventIds = MemoryJsonSupport.decodeStringList(stmt.getText(7))
                .asSequence()
                .map { MemoryLimits.clampText(it, MemoryLimits.MAX_ID_LENGTH) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MemoryLimits.MAX_SYNC_LIST_ITEMS)
                .toList()
            val safeDeviceId = MemoryLimits.clampText(
                stmt.getNullableText(15)?.takeIf { it.isNotBlank() } ?: fallbackDeviceId,
                MemoryLimits.MAX_ID_LENGTH,
            )
            MemoryProposalSyncRecord(
                id = cleanId,
                slug = cleanSlug,
                title = cleanTitle,
                purpose = cleanPurpose,
                domain = cleanDomain,
                sensitivity = MemorySensitivity.fromWire(stmt.getText(5)),
                seedContent = MemoryLimits.clampText(stmt.getText(6), MemoryLimits.MAX_SYNC_BLOCK_CONTENT_CHARS),
                supportingEventIds = cleanEventIds,
                reason = cleanReason,
                status = MemoryProposalStatus.fromWire(stmt.getText(9)),
                createdAt = createdAt,
                resolvedAt = resolvedAt,
                updatedAt = if (rawUpdatedAt > 0L) rawUpdatedAt else (resolvedAt ?: createdAt),
                deletedAt = stmt.getNullableLong(13),
                revision = stmt.getLong(14).coerceAtLeast(1L),
                updatedByDeviceId = safeDeviceId,
            )
        }

    private fun hasLocalEditOrDirty(
        conn: SQLiteConnection,
        kind: MemorySyncKind,
        id: String,
        selfDeviceId: String,
    ): Boolean {
        val hasDirtyOp = conn.prepare(
            "SELECT 1 FROM memory_sync_ops WHERE target_kind = ? AND target_id = ? LIMIT 1",
        ).use { stmt ->
            stmt.bindText(1, kind.wire)
            stmt.bindText(2, id)
            stmt.step()
        }
        if (hasDirtyOp) return true
        val sql = when (kind) {
            MemorySyncKind.BLOCK ->
                "SELECT updated_by_device_id FROM memory_blocks WHERE id = ? AND NOT (owner = 'BUILTIN' AND updated_at = 0 AND content = '' AND deleted_at IS NULL) LIMIT 1"
            MemorySyncKind.ENTITY -> "SELECT updated_by_device_id FROM entities WHERE id = ? LIMIT 1"
            MemorySyncKind.EVENT -> "SELECT updated_by_device_id FROM memory_events WHERE id = ? LIMIT 1"
            MemorySyncKind.RELATION -> "SELECT updated_by_device_id FROM entity_relations WHERE id = ? LIMIT 1"
            MemorySyncKind.PROPOSAL -> "SELECT updated_by_device_id FROM memory_block_proposals WHERE id = ? LIMIT 1"
        }
        return conn.prepare(sql).use { stmt ->
            stmt.bindText(1, id)
            if (!stmt.step()) {
                false
            } else {
                val ownerDevice = stmt.getNullableText(0)
                ownerDevice == null || ownerDevice == selfDeviceId
            }
        }
    }

    private fun clearSupersededDirtyOp(
        conn: SQLiteConnection,
        kind: MemorySyncKind,
        targetId: String,
        remoteUpdatedAt: Long,
    ) {
        conn.prepare(
            "DELETE FROM memory_sync_ops WHERE target_kind = ? AND target_id = ? AND updated_at <= ?",
        ).use { stmt ->
            stmt.bindText(1, kind.wire)
            stmt.bindText(2, targetId)
            stmt.bindLong(3, remoteUpdatedAt)
            stmt.step()
        }
    }

    private fun entityRowExists(conn: SQLiteConnection, id: String): Boolean =
        conn.prepare("SELECT 1 FROM entities WHERE id = ? LIMIT 1").use { stmt ->
            stmt.bindText(1, id)
            stmt.step()
        }

    private fun itemTargetId(item: MemorySyncOpItem): String? = when (item.kind) {
        MemorySyncKind.BLOCK -> item.block?.id
        MemorySyncKind.ENTITY -> item.entity?.id
        MemorySyncKind.EVENT -> item.event?.id
        MemorySyncKind.RELATION -> item.relation?.id
        MemorySyncKind.PROPOSAL -> item.proposal?.id
    }

    private fun compareVersionTuple(
        incomingUpdatedAt: Long,
        incomingDeviceId: String,
        incomingRevision: Long,
        existingUpdatedAt: Long,
        existingDeviceId: String,
        existingRevision: Long,
    ): Boolean {
        if (incomingUpdatedAt != existingUpdatedAt) {
            return incomingUpdatedAt > existingUpdatedAt
        }
        if (incomingDeviceId != existingDeviceId) {
            return incomingDeviceId > existingDeviceId
        }
        return incomingRevision > existingRevision
    }

    private fun normalizeObjectJson(raw: String?): String =
        MemoryJsonSupport.boundJsonObject(raw, MemoryLimits.MAX_JSON_OBJECT_CHARS)

    private fun readSyncState(conn: SQLiteConnection, key: String): String? =
        conn.prepare("SELECT value FROM sync_state WHERE key = ? LIMIT 1").use { stmt ->
            stmt.bindText(1, key)
            if (stmt.step()) stmt.getText(0) else null
        }

    private fun writeSyncState(conn: SQLiteConnection, key: String, value: String) {
        conn.prepare(
            """
            INSERT INTO sync_state(key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, key)
            stmt.bindText(2, value)
            stmt.step()
        }
    }

    private fun deleteSyncState(conn: SQLiteConnection, key: String) {
        conn.prepare("DELETE FROM sync_state WHERE key = ?").use { stmt ->
            stmt.bindText(1, key)
            stmt.step()
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

    private data class ExistingBlockMeta(
        val updatedAt: Long,
        val deviceId: String,
        val revision: Long,
        val owner: String,
        val content: String,
        val deletedAt: Long?,
        val slug: String,
    )

    private data class VersionTuple(
        val updatedAt: Long,
        val deviceId: String,
        val revision: Long,
    )
}

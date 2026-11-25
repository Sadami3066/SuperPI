package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MemoryV2Migrator {
    private const val BACKUP_DB_NAME = "memory.v2.backup.db"
    private const val BACKUP_MD_NAME = "memory-v2-backup.md"
    private const val REHYDRATE_BATCH_SIZE = 40
    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    private data class LegacyMemoryRow(
        val id: String,
        val kind: String,
        val content: String,
        val summary: String?,
        val tagsJson: String,
        val confidence: String,
        val importance: Int,
        val pinned: Boolean,
        val validFrom: Long,
        val validUntil: Long?,
        val status: String,
        val supersededBy: String?,
        val sourceKind: String,
        val sourceSessionId: String?,
        val sourceEntryId: String?,
        val sourceQuote: String?,
        val accessCount: Int,
        val lastAccessedAt: Long?,
        val createdAt: Long,
        val updatedAt: Long,
        val revision: Long,
    )

    fun copyDbFileBackup(dbFile: File, backupsDir: File) {
        if (!dbFile.isFile) return
        backupsDir.mkdirs()
        val target = File(backupsDir, BACKUP_DB_NAME)
        if (!target.exists()) {
            dbFile.copyTo(target, overwrite = false)
        }
    }

    fun writeMarkdownBackup(conn: SQLiteConnection, backupsDir: File, nowMs: Long) {
        backupsDir.mkdirs()
        val outFile = File(backupsDir, BACKUP_MD_NAME)
        if (outFile.exists()) return
        val markdown = buildString {
            appendLine("# SuperPI Memory v2 Backup")
            appendLine()
            appendLine("Exported: ${formatDate(nowMs)}")
            appendLine()
            conn.prepare(
                """
                SELECT kind, content, summary, tags_json, confidence, pinned, status, valid_from, valid_until
                FROM memories
                WHERE deleted_at IS NULL
                ORDER BY pinned DESC, updated_at DESC
                """.trimIndent(),
            ).use { stmt ->
                while (stmt.step()) {
                    val kind = stmt.getText(0)
                    val content = stmt.getText(1)
                    val summary = if (stmt.isNull(2)) null else stmt.getText(2)
                    val tags = MemoryJsonSupport.decodeStringList(stmt.getText(3))
                    val confidence = stmt.getText(4)
                    val pinned = stmt.getLong(5) != 0L
                    val status = stmt.getText(6)
                    val validFrom = stmt.getLong(7)
                    val prefix = if (pinned) "[Pinned] " else ""
                    val tagsSuffix = if (tags.isEmpty()) "" else " #${tags.joinToString(" #")}"
                    appendLine("- $prefix[$kind/$status/$confidence ${formatDate(validFrom)}] ${summary ?: content}$tagsSuffix")
                }
            }
        }
        outFile.writeText(markdown)
    }

    fun migrateV2ToV3InTx(conn: SQLiteConnection, nowMs: Long) {
        MemoryDatabase.ensureV3Columns(conn)
        conn.execSQL("UPDATE entities SET created_at = updated_at WHERE created_at = 0")

        val legacyRows = readLegacyActiveRows(conn)
        val pinnedOrManual = legacyRows.filter { it.pinned || it.sourceKind == "USER_MANUAL" }
        val rehydratePool = legacyRows.filterNot { it.pinned || it.sourceKind == "USER_MANUAL" }

        val entityNamesById = loadActiveEntityNames(conn)
        val linksByMemory = loadMemoryEntityLinks(conn)

        var migratedDirectCount = 0
        pinnedOrManual.forEach { row ->
            insertMigratedEvent(
                conn = conn,
                row = row,
                linkedEntityIds = linksByMemory[row.id].orEmpty(),
                entityNamesById = entityNamesById,
            )
            migratedDirectCount++
        }

        val batches = rehydratePool.chunked(REHYDRATE_BATCH_SIZE)
        batches.forEachIndexed { index, batch ->
            val itemsJson = JsonArray(
                batch.map { item ->
                    buildJsonObject {
                        put("legacy_id", item.id)
                        put("legacy_kind", item.kind)
                        put("content", item.content)
                        item.summary?.let { put("summary", it) }
                        put("confidence", item.confidence)
                        put("importance", item.importance)
                        put("tags", JsonArray(MemoryJsonSupport.decodeStringList(item.tagsJson).map(::JsonPrimitive)))
                        put("date", formatDate(item.validFrom))
                        put("event_time_ms", item.validFrom)
                        item.validUntil?.let { put("valid_until_ms", it) }
                        val entities = linksByMemory[item.id].orEmpty().mapNotNull { entityNamesById[it] }
                        put("entities", JsonArray(entities.map(::JsonPrimitive)))
                    }
                },
            )
            val payload = buildJsonObject {
                put("batch_index", index + 1)
                put("total_batches", batches.size)
                put("items", itemsJson)
            }.toString()
            conn.prepare(
                """
                INSERT INTO memory_dispatch_queue(
                    id, job_kind, priority, session_id, payload_json, transcript_hash,
                    attempts, last_error, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, ?, NULL, 0, NULL, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, UUID.randomUUID().toString())
                stmt.bindText(2, DispatchJobKind.MIGRATION_REHYDRATE.wire)
                stmt.bindLong(3, DispatchJobKind.MIGRATION_REHYDRATE.defaultPriority.toLong())
                stmt.bindText(4, payload)
                stmt.bindLong(5, nowMs)
                stmt.bindLong(6, nowMs)
                stmt.step()
            }
        }

        if (migratedDirectCount > 0 || batches.isNotEmpty()) {
            conn.prepare(
                """
                INSERT INTO memory_audit_log(
                    id, run_id, actor, action_type, target_table, target_id,
                    summary_user_text, reason, before_snapshot_json, after_snapshot_json,
                    reverted_at, created_at
                ) VALUES (?, ?, 'MEMORY_AGENT_MIGRATION', 'SYSTEM_MIGRATION', 'memory_events', 'v2_to_v3', ?, ?, NULL, NULL, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.bindText(1, UUID.randomUUID().toString())
                stmt.bindText(2, "migration-v2-v3")
                stmt.bindText(
                    3,
                    "已从旧版记忆保留 $migratedDirectCount 条置顶/手动记录，并排队提炼 ${rehydratePool.size} 条历史记忆",
                )
                stmt.bindNull(4)
                stmt.bindNull(5)
                stmt.bindLong(6, nowMs)
                stmt.step()
            }
        }

        conn.execSQL("DROP TABLE IF EXISTS memories_fts")
        conn.execSQL("DROP TABLE IF EXISTS memory_entities")
        conn.execSQL("DROP TABLE IF EXISTS memories")
        conn.execSQL("DROP TABLE IF EXISTS pending_ingestions")
        conn.execSQL("DROP TABLE IF EXISTS dream_runs")
        conn.execSQL("DROP TABLE IF EXISTS core_snapshot")
        conn.execSQL("DROP TABLE IF EXISTS sync_ops")
    }

    private fun readLegacyActiveRows(conn: SQLiteConnection): List<LegacyMemoryRow> {
        val result = ArrayList<LegacyMemoryRow>()
        conn.prepare(
            """
            SELECT id, kind, content, summary, tags_json, confidence, importance, pinned,
                   valid_from, valid_until, status, superseded_by, source_kind,
                   source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                   created_at, updated_at, revision
            FROM memories
            WHERE deleted_at IS NULL AND status = 'ACTIVE'
            ORDER BY pinned DESC, updated_at DESC
            """.trimIndent(),
        ).use { stmt ->
            while (stmt.step()) {
                result += LegacyMemoryRow(
                    id = stmt.getText(0),
                    kind = stmt.getText(1),
                    content = stmt.getText(2),
                    summary = if (stmt.isNull(3)) null else stmt.getText(3),
                    tagsJson = stmt.getText(4),
                    confidence = stmt.getText(5),
                    importance = stmt.getLong(6).toInt(),
                    pinned = stmt.getLong(7) != 0L,
                    validFrom = stmt.getLong(8),
                    validUntil = if (stmt.isNull(9)) null else stmt.getLong(9),
                    status = stmt.getText(10),
                    supersededBy = if (stmt.isNull(11)) null else stmt.getText(11),
                    sourceKind = stmt.getText(12),
                    sourceSessionId = if (stmt.isNull(13)) null else stmt.getText(13),
                    sourceEntryId = if (stmt.isNull(14)) null else stmt.getText(14),
                    sourceQuote = if (stmt.isNull(15)) null else stmt.getText(15),
                    accessCount = stmt.getLong(16).toInt(),
                    lastAccessedAt = if (stmt.isNull(17)) null else stmt.getLong(17),
                    createdAt = stmt.getLong(18),
                    updatedAt = stmt.getLong(19),
                    revision = stmt.getLong(20),
                )
            }
        }
        return result
    }

    private fun loadActiveEntityNames(conn: SQLiteConnection): Map<String, String> {
        val map = HashMap<String, String>()
        conn.prepare("SELECT id, canonical_name FROM entities WHERE deleted_at IS NULL").use { stmt ->
            while (stmt.step()) {
                map[stmt.getText(0)] = stmt.getText(1)
            }
        }
        return map
    }

    private fun loadMemoryEntityLinks(conn: SQLiteConnection): Map<String, List<String>> {
        if (!MemoryDatabase.tableExists(conn, "memory_entities")) return emptyMap()
        val map = HashMap<String, MutableList<String>>()
        conn.prepare("SELECT memory_id, entity_id FROM memory_entities").use { stmt ->
            while (stmt.step()) {
                map.getOrPut(stmt.getText(0)) { ArrayList() }.add(stmt.getText(1))
            }
        }
        return map
    }

    private fun insertMigratedEvent(
        conn: SQLiteConnection,
        row: LegacyMemoryRow,
        linkedEntityIds: List<String>,
        entityNamesById: Map<String, String>,
    ) {
        val eventKind = MemoryEventKind.fromWire(row.kind) ?: MemoryEventKind.FACT
        val domain = if (row.kind.equals("HABIT", ignoreCase = true)) MemoryDomains.WORK else MemoryDomains.PERSONAL
        val tier = when {
            row.pinned -> MemoryEpistemicTier.USER_PINNED
            row.sourceKind == "USER_MANUAL" || row.confidence == "CONFIRMED" -> MemoryEpistemicTier.USER_EXPLICIT
            row.confidence == "INFERRED" -> MemoryEpistemicTier.INFERRED
            else -> MemoryEpistemicTier.OBSERVED
        }
        val cleanContent = MemoryLimits.clampText(row.content, MemoryLimits.MAX_CONTENT_CHARS)
        val cleanTags = MemoryJsonSupport.decodeStringList(row.tagsJson)
        val entityNames = linkedEntityIds.mapNotNull { entityNamesById[it] }

        conn.prepare(
            """
            INSERT OR REPLACE INTO memory_events(
                id, event_kind, content, summary, qualifiers_json, semantic_keywords_json,
                tags_json, domain, sensitivity, epistemic_tier, importance, pinned,
                event_time, event_time_precision, valid_from, valid_until, status, superseded_by,
                source_kind, source_session_id, source_entry_id, source_quote,
                access_count, last_accessed_at, activation_score,
                created_at, updated_at, deleted_at, revision, updated_by_device_id
            ) VALUES (?, ?, ?, ?, '{}', '[]', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.0, ?, ?, NULL, ?, NULL)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindText(1, row.id)
            stmt.bindText(2, eventKind.wire)
            stmt.bindText(3, cleanContent)
            if (row.summary == null) stmt.bindNull(4) else stmt.bindText(4, row.summary)
            stmt.bindText(5, row.tagsJson)
            stmt.bindText(6, domain)
            stmt.bindText(7, MemorySensitivity.NORMAL.wire)
            stmt.bindText(8, tier.wire)
            stmt.bindLong(9, row.importance.coerceIn(1, 5).toLong())
            stmt.bindLong(10, if (row.pinned) 1L else 0L)
            stmt.bindLong(11, row.validFrom)
            stmt.bindText(12, MemoryEventTimePrecision.DAY.wire)
            stmt.bindLong(13, row.validFrom)
            if (row.validUntil == null) stmt.bindNull(14) else stmt.bindLong(14, row.validUntil)
            stmt.bindText(15, MemoryItemStatus.fromWire(row.status).wire)
            if (row.supersededBy == null) stmt.bindNull(16) else stmt.bindText(16, row.supersededBy)
            stmt.bindText(17, MemorySourceKind.MIGRATION_V2.wire)
            if (row.sourceSessionId == null) stmt.bindNull(18) else stmt.bindText(18, row.sourceSessionId)
            if (row.sourceEntryId == null) stmt.bindNull(19) else stmt.bindText(19, row.sourceEntryId)
            if (row.sourceQuote == null) stmt.bindNull(20) else stmt.bindText(20, row.sourceQuote)
            stmt.bindLong(21, row.accessCount.toLong())
            if (row.lastAccessedAt == null) stmt.bindNull(22) else stmt.bindLong(22, row.lastAccessedAt)
            stmt.bindLong(23, row.createdAt)
            stmt.bindLong(24, row.updatedAt)
            stmt.bindLong(25, row.revision)
            stmt.step()
        }

        linkedEntityIds.filter { it in entityNamesById }.forEach { entityId ->
            conn.prepare(
                "INSERT OR IGNORE INTO event_entities(event_id, entity_id, role) VALUES (?, ?, 'MENTIONED')",
            ).use { stmt ->
                stmt.bindText(1, row.id)
                stmt.bindText(2, entityId)
                stmt.step()
            }
        }

        val contentText = listOfNotNull(cleanContent, row.summary).joinToString(" ")
        conn.prepare(
            "INSERT INTO memory_fts(event_id, content_tokens, semantic_tokens, tag_tokens, entity_tokens) VALUES (?, ?, '', ?, ?)",
        ).use { stmt ->
            stmt.bindText(1, row.id)
            stmt.bindText(2, MemoryTokenizer.indexText(contentText))
            stmt.bindText(3, MemoryTokenizer.indexText(cleanTags.joinToString(" ")))
            stmt.bindText(4, MemoryTokenizer.indexText(entityNames.joinToString(" ")))
            stmt.step()
        }
    }

    private fun formatDate(epochMs: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate())
}

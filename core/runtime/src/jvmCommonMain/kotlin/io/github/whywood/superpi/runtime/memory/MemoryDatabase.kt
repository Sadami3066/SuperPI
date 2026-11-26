package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.logWarn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class MemoryDatabase(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dbFile = File(rootDir, DB_FILE_NAME)
    private val driver = BundledSQLiteDriver()

    @Volatile
    private var connection: SQLiteConnection? = null

    fun ensure() {
        rootDir.mkdirs()
        cleanLegacyFiles()
        if (connection != null) return
        connection = openVerifiedConnection()
    }

    fun <T> read(block: (SQLiteConnection) -> T): T {
        ensure()
        val conn = checkNotNull(connection)
        return block(conn)
    }

    fun <T> writeTransaction(block: (SQLiteConnection) -> T): T {
        ensure()
        val conn = checkNotNull(connection)
        return inTransaction(conn, block)
    }

    fun close() {
        runCatching { connection?.close() }
        connection = null
    }

    private fun openVerifiedConnection(): SQLiteConnection {
        val opened = runCatching { openAndInit() }.getOrNull()
        if (opened != null) return opened
        quarantineCorruptDb()
        return openAndInit()
    }

    private fun openAndInit(): SQLiteConnection {
        var conn = openConfiguredConnection()
        try {
            val ok = conn.prepare("PRAGMA quick_check").use { stmt ->
                stmt.step() && stmt.getText(0).equals("ok", ignoreCase = true)
            }
            if (!ok) error("SQLite quick_check failed")

            if (needsV2Migration(conn)) {
                MemoryV2Migrator.writeMarkdownBackup(conn, File(rootDir, BACKUPS_DIR_NAME), clock())
                runCatching { conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
                conn.close()
                MemoryV2Migrator.copyDbFileBackup(dbFile, File(rootDir, BACKUPS_DIR_NAME))
                conn = openConfiguredConnection()
                inTransaction(conn) { tx ->
                    createV3Schema(tx)
                    seedBuiltInBlocks(tx, clock())
                    MemoryV2Migrator.migrateV2ToV3InTx(tx, clock())
                    tx.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
            } else {
                inTransaction(conn) { tx ->
                    val userVersion = tx.prepare("PRAGMA user_version").use { stmt ->
                        if (stmt.step()) stmt.getLong(0).toInt() else 0
                    }
                    createV3Schema(tx)
                    ensureV3Columns(tx)
                    if (userVersion < SCHEMA_VERSION) {
                        migrateV3ToV4IfNeeded(tx)
                        migrateV4ToV5IfNeeded(tx)
                    }
                    seedBuiltInBlocks(tx, clock())
                    if (userVersion < SCHEMA_VERSION) {
                        tx.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                    }
                }
            }
            return conn
        } catch (error: Throwable) {
            runCatching { conn.close() }
            throw error
        }
    }

    private fun openConfiguredConnection(): SQLiteConnection {
        val conn = driver.open(dbFile.absolutePath)
        try {
            conn.execSQL("PRAGMA journal_mode=WAL")
            conn.execSQL("PRAGMA foreign_keys=ON")
            conn.execSQL("PRAGMA busy_timeout=5000")
            return conn
        } catch (error: Throwable) {
            runCatching { conn.close() }
            throw error
        }
    }

    private fun <T> inTransaction(conn: SQLiteConnection, block: (SQLiteConnection) -> T): T {
        conn.execSQL("BEGIN IMMEDIATE TRANSACTION")
        return try {
            val result = block(conn)
            conn.execSQL("COMMIT")
            result
        } catch (error: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw error
        }
    }

    private fun needsV2Migration(conn: SQLiteConnection): Boolean =
        tableExists(conn, "memories") && !tableExists(conn, "memory_events")

    private fun quarantineCorruptDb() {
        val stamp = CORRUPT_STAMP.format(LocalDateTime.now())
        listOf("", "-wal", "-shm").forEach { suffix ->
            val candidate = File(rootDir, "$DB_FILE_NAME$suffix")
            if (candidate.exists()) {
                val backup = File(rootDir, "$DB_FILE_NAME.$stamp.corrupt$suffix")
                if (!candidate.renameTo(backup)) {
                    logWarn(TAG, "failed to backup corrupt memory file ${candidate.name}")
                    candidate.delete()
                }
            }
        }
    }

    private fun cleanLegacyFiles() {
        LEGACY_FILES.forEach { name ->
            val file = File(rootDir, name)
            if (file.isFile) runCatching { file.delete() }
        }
        LEGACY_DIRS.forEach { name ->
            val dir = File(rootDir, name)
            if (dir.isDirectory) runCatching { dir.deleteRecursively() }
        }
    }

    internal companion object {
        private const val TAG = "MemoryDatabase"
        const val DB_FILE_NAME = "memory.db"
        const val BACKUPS_DIR_NAME = "backups"
        const val SCHEMA_VERSION = 5

        fun builtInBlockId(slug: String): String = "builtin_$slug"

        private val LEGACY_FILES = listOf(
            "profile.md",
            "context.md",
            "cookbook.md",
            "playbook.md",
            "summaries.log",
            "index.sqlite",
            "snapshot.md",
            "rollup.state",
        )
        private val LEGACY_DIRS = listOf("daily", "pending")
        private val CORRUPT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        private data class BuiltinBlockSeed(
            val slug: String,
            val title: String,
            val purpose: String,
            val domain: String,
            val sensitivity: MemorySensitivity,
            val maxChars: Int,
            val sortOrder: Int,
        )

        private val BUILTIN_BLOCKS = listOf(
            BuiltinBlockSeed(
                slug = "self_profile",
                title = "关于我与人生阶段",
                purpose = "个人背景、身份角色与当前人生阶段主线",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                maxChars = 2_000,
                sortOrder = 10,
            ),
            BuiltinBlockSeed(
                slug = "relationships",
                title = "重要关系圈",
                purpose = "家人、伴侣、挚友与核心合作者速览",
                domain = MemoryDomains.RELATIONSHIPS,
                sensitivity = MemorySensitivity.PRIVATE,
                maxChars = 2_200,
                sortOrder = 20,
            ),
            BuiltinBlockSeed(
                slug = "preferences_boundaries",
                title = "品味偏好与雷区",
                purpose = "饮食、审美、消费、沟通风格、禁忌与相处默契（保留语境限定）",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                maxChars = 2_000,
                sortOrder = 30,
            ),
            BuiltinBlockSeed(
                slug = "life_rhythm_health",
                title = "生活节律与健康",
                purpose = "作息、运动、健康注意、常住地与出行偏好",
                domain = MemoryDomains.LIFE,
                sensitivity = MemorySensitivity.PRIVATE,
                maxChars = 1_800,
                sortOrder = 40,
            ),
            BuiltinBlockSeed(
                slug = "active_threads",
                title = "当前在办与长期目标",
                purpose = "正在推进的个人事项、约定与中长期愿景",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                maxChars = 2_200,
                sortOrder = 50,
            ),
            BuiltinBlockSeed(
                slug = "work_study",
                title = "工作与学习域",
                purpose = "职业或学业背景、当前项目与专业习惯（与私人生活解耦）",
                domain = MemoryDomains.WORK,
                sensitivity = MemorySensitivity.NORMAL,
                maxChars = 2_000,
                sortOrder = 60,
            ),
        )

        fun tableExists(conn: SQLiteConnection, tableName: String): Boolean =
            conn.prepare("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ? LIMIT 1").use { stmt ->
                stmt.bindText(1, tableName)
                stmt.step()
            }

        fun tableColumns(conn: SQLiteConnection, tableName: String): Set<String> {
            val cols = HashSet<String>()
            conn.prepare("PRAGMA table_info($tableName)").use { stmt ->
                while (stmt.step()) cols += stmt.getText(1).lowercase()
            }
            return cols
        }

        fun createV3Schema(conn: SQLiteConnection) {
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_blocks (
                    id TEXT PRIMARY KEY,
                    slug TEXT NOT NULL,
                    title TEXT NOT NULL,
                    purpose TEXT NOT NULL,
                    schema_hint TEXT,
                    content TEXT NOT NULL DEFAULT '',
                    domain TEXT NOT NULL,
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                    injection_mode TEXT NOT NULL DEFAULT 'ALWAYS',
                    max_chars INTEGER NOT NULL DEFAULT 2000,
                    owner TEXT NOT NULL DEFAULT 'BUILTIN',
                    pinned INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    sort_order INTEGER NOT NULL DEFAULT 100,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1,
                    updated_by_device_id TEXT
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_blocks_active ON memory_blocks(deleted_at, status, sort_order, updated_at DESC)",
            )
            conn.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_blocks_slug_active ON memory_blocks(slug) WHERE deleted_at IS NULL",
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_block_proposals (
                    id TEXT PRIMARY KEY,
                    slug TEXT NOT NULL,
                    title TEXT NOT NULL,
                    purpose TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                    seed_content TEXT NOT NULL,
                    supporting_event_ids_json TEXT NOT NULL DEFAULT '[]',
                    reason TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at INTEGER NOT NULL,
                    resolved_at INTEGER,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1,
                    updated_by_device_id TEXT
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS entities (
                    id TEXT PRIMARY KEY,
                    canonical_name TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    aliases_json TEXT NOT NULL DEFAULT '[]',
                    attributes_json TEXT NOT NULL DEFAULT '{}',
                    summary TEXT,
                    domain TEXT NOT NULL DEFAULT 'PERSONAL',
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                    pinned INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1,
                    merged_into TEXT,
                    updated_by_device_id TEXT
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS entity_relations (
                    id TEXT PRIMARY KEY,
                    from_entity_id TEXT NOT NULL,
                    relation_type TEXT NOT NULL,
                    to_entity_id TEXT NOT NULL,
                    label TEXT,
                    attributes_json TEXT NOT NULL DEFAULT '{}',
                    valid_from INTEGER NOT NULL,
                    valid_until INTEGER,
                    invalidated_at INTEGER,
                    invalidated_by TEXT,
                    epistemic_tier TEXT NOT NULL DEFAULT 'OBSERVED',
                    domain TEXT NOT NULL DEFAULT 'PERSONAL',
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                    source_event_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1,
                    updated_by_device_id TEXT,
                    FOREIGN KEY (from_entity_id) REFERENCES entities(id) ON DELETE CASCADE,
                    FOREIGN KEY (to_entity_id) REFERENCES entities(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_rel_from ON entity_relations(from_entity_id, deleted_at, invalidated_at)",
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_rel_to ON entity_relations(to_entity_id, deleted_at, invalidated_at)",
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_events (
                    id TEXT PRIMARY KEY,
                    event_kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    summary TEXT,
                    qualifiers_json TEXT NOT NULL DEFAULT '{}',
                    semantic_keywords_json TEXT NOT NULL DEFAULT '[]',
                    tags_json TEXT NOT NULL DEFAULT '[]',
                    domain TEXT NOT NULL DEFAULT 'PERSONAL',
                    sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                    epistemic_tier TEXT NOT NULL DEFAULT 'OBSERVED',
                    importance INTEGER NOT NULL DEFAULT 3,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    event_time INTEGER NOT NULL,
                    event_time_precision TEXT NOT NULL DEFAULT 'DAY',
                    valid_from INTEGER NOT NULL,
                    valid_until INTEGER,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    superseded_by TEXT,
                    source_kind TEXT NOT NULL,
                    source_session_id TEXT,
                    source_entry_id TEXT,
                    source_quote TEXT,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    last_accessed_at INTEGER,
                    activation_score REAL NOT NULL DEFAULT 1.0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1,
                    updated_by_device_id TEXT
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_events_active ON memory_events(deleted_at, status, domain, event_time DESC)",
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_events_session ON memory_events(source_session_id, deleted_at)",
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_events_expiry ON memory_events(status, valid_until) WHERE valid_until IS NOT NULL",
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS event_entities (
                    event_id TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'MENTIONED',
                    PRIMARY KEY (event_id, entity_id),
                    FOREIGN KEY (event_id) REFERENCES memory_events(id) ON DELETE CASCADE,
                    FOREIGN KEY (entity_id) REFERENCES entities(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_audit_log (
                    id TEXT PRIMARY KEY,
                    run_id TEXT,
                    actor TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    target_table TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    summary_user_text TEXT NOT NULL,
                    reason TEXT,
                    before_snapshot_json TEXT,
                    after_snapshot_json TEXT,
                    reverted_at INTEGER,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_audit_recent ON memory_audit_log(created_at DESC)",
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_dispatch_queue (
                    id TEXT PRIMARY KEY,
                    job_kind TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    session_id TEXT,
                    payload_json TEXT NOT NULL,
                    transcript_hash TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    next_retry_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_dispatch_priority ON memory_dispatch_queue(attempts, priority DESC, created_at ASC)",
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_dispatch_retry ON memory_dispatch_queue(attempts, next_retry_at, priority DESC, created_at ASC)",
            )
            conn.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                    event_id UNINDEXED,
                    content_tokens,
                    semantic_tokens,
                    tag_tokens,
                    entity_tokens,
                    tokenize='unicode61'
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ingestion_watermarks (
                    session_id TEXT PRIMARY KEY,
                    session_file TEXT,
                    last_entry_id TEXT,
                    last_hash TEXT,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_agent_runs (
                    id TEXT PRIMARY KEY,
                    mode TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    finished_at INTEGER,
                    status TEXT NOT NULL,
                    model_ref TEXT,
                    steps_used INTEGER NOT NULL DEFAULT 0,
                    blocks_patched INTEGER NOT NULL DEFAULT 0,
                    events_recorded INTEGER NOT NULL DEFAULT 0,
                    events_superseded INTEGER NOT NULL DEFAULT 0,
                    events_forgotten INTEGER NOT NULL DEFAULT 0,
                    entities_merged INTEGER NOT NULL DEFAULT 0,
                    proposals_created INTEGER NOT NULL DEFAULT 0,
                    error TEXT
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS context_cache (
                    scope_wire TEXT PRIMARY KEY,
                    rendered_text TEXT NOT NULL,
                    char_count INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    generated_at INTEGER NOT NULL,
                    reason TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_sync_ops (
                    target_kind TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    op_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (target_kind, target_id)
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_sync_ops_order ON memory_sync_ops(updated_at ASC)",
            )
        }

        fun ensureV3Columns(conn: SQLiteConnection) {
            val entityCols = tableColumns(conn, "entities")
            if ("attributes_json" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN attributes_json TEXT NOT NULL DEFAULT '{}'")
            }
            if ("domain" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN domain TEXT NOT NULL DEFAULT 'PERSONAL'")
            }
            if ("sensitivity" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN sensitivity TEXT NOT NULL DEFAULT 'NORMAL'")
            }
            if ("pinned" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
            if ("created_at" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
            }
            if ("merged_into" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN merged_into TEXT")
            }
            if ("updated_by_device_id" !in entityCols) {
                conn.execSQL("ALTER TABLE entities ADD COLUMN updated_by_device_id TEXT")
            }
            if (tableExists(conn, "entity_relations")) {
                val relCols = tableColumns(conn, "entity_relations")
                if ("updated_by_device_id" !in relCols) {
                    conn.execSQL("ALTER TABLE entity_relations ADD COLUMN updated_by_device_id TEXT")
                }
            }
            if (tableExists(conn, "memory_block_proposals")) {
                val propCols = tableColumns(conn, "memory_block_proposals")
                if ("updated_at" !in propCols) {
                    conn.execSQL("ALTER TABLE memory_block_proposals ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
                }
                if ("deleted_at" !in propCols) {
                    conn.execSQL("ALTER TABLE memory_block_proposals ADD COLUMN deleted_at INTEGER")
                }
                if ("revision" !in propCols) {
                    conn.execSQL("ALTER TABLE memory_block_proposals ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
                }
                if ("updated_by_device_id" !in propCols) {
                    conn.execSQL("ALTER TABLE memory_block_proposals ADD COLUMN updated_by_device_id TEXT")
                }
            }
        }

        fun migrateV3ToV4IfNeeded(conn: SQLiteConnection) {
            var hasAutoSlugIndex = false
            conn.prepare("PRAGMA index_list(memory_blocks)").use { stmt ->
                while (stmt.step()) {
                    if (stmt.getText(3) == "u") {
                        hasAutoSlugIndex = true
                        break
                    }
                }
            }
            if (hasAutoSlugIndex) {
                conn.execSQL(
                    """
                    CREATE TABLE memory_blocks_v4 (
                        id TEXT PRIMARY KEY,
                        slug TEXT NOT NULL,
                        title TEXT NOT NULL,
                        purpose TEXT NOT NULL,
                        schema_hint TEXT,
                        content TEXT NOT NULL DEFAULT '',
                        domain TEXT NOT NULL,
                        sensitivity TEXT NOT NULL DEFAULT 'NORMAL',
                        injection_mode TEXT NOT NULL DEFAULT 'ALWAYS',
                        max_chars INTEGER NOT NULL DEFAULT 2000,
                        owner TEXT NOT NULL DEFAULT 'BUILTIN',
                        pinned INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        sort_order INTEGER NOT NULL DEFAULT 100,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        deleted_at INTEGER,
                        revision INTEGER NOT NULL DEFAULT 1,
                        updated_by_device_id TEXT
                    )
                    """.trimIndent(),
                )
                conn.execSQL(
                    """
                    INSERT INTO memory_blocks_v4(
                        id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                        injection_mode, max_chars, owner, pinned, status, sort_order,
                        created_at, updated_at, deleted_at, revision, updated_by_device_id
                    )
                    SELECT id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                           injection_mode, max_chars, owner, pinned, status, sort_order,
                           created_at, updated_at, deleted_at, revision, updated_by_device_id
                    FROM memory_blocks
                    """.trimIndent(),
                )
                conn.execSQL("DROP TABLE memory_blocks")
                conn.execSQL("ALTER TABLE memory_blocks_v4 RENAME TO memory_blocks")
            }
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_blocks_active ON memory_blocks(deleted_at, status, sort_order, updated_at DESC)",
            )
            conn.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_blocks_slug_active ON memory_blocks(slug) WHERE deleted_at IS NULL",
            )
            val queueCols = tableColumns(conn, "memory_dispatch_queue")
            if ("next_retry_at" !in queueCols) {
                conn.execSQL("ALTER TABLE memory_dispatch_queue ADD COLUMN next_retry_at INTEGER NOT NULL DEFAULT 0")
            }
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_dispatch_retry ON memory_dispatch_queue(attempts, next_retry_at, priority DESC, created_at ASC)",
            )
        }

        fun migrateV4ToV5IfNeeded(conn: SQLiteConnection) {
            ensureV3Columns(conn)
            conn.execSQL(
                """
                UPDATE memory_block_proposals
                SET updated_at = COALESCE(resolved_at, created_at)
                WHERE updated_at = 0
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE IF NOT EXISTS memory_sync_ops (
                    target_kind TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    op_id TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (target_kind, target_id)
                )
                """.trimIndent(),
            )
            conn.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_sync_ops_order ON memory_sync_ops(updated_at ASC)",
            )
            val builtinRows = ArrayList<Pair<String, String>>()
            conn.prepare("SELECT id, slug FROM memory_blocks WHERE owner = 'BUILTIN'").use { stmt ->
                while (stmt.step()) {
                    builtinRows += stmt.getText(0) to stmt.getText(1)
                }
            }
            builtinRows.forEach { (oldId, slug) ->
                val targetId = builtInBlockId(slug)
                if (oldId != targetId) {
                    val targetExists = conn.prepare("SELECT 1 FROM memory_blocks WHERE id = ? LIMIT 1").use { stmt ->
                        stmt.bindText(1, targetId)
                        stmt.step()
                    }
                    if (!targetExists) {
                        conn.prepare("UPDATE memory_blocks SET id = ? WHERE id = ?").use { stmt ->
                            stmt.bindText(1, targetId)
                            stmt.bindText(2, oldId)
                            stmt.step()
                        }
                        if (tableExists(conn, "memory_audit_log")) {
                            conn.prepare(
                                "UPDATE memory_audit_log SET target_id = ? WHERE target_table = 'memory_blocks' AND target_id = ?",
                            ).use { stmt ->
                                stmt.bindText(1, targetId)
                                stmt.bindText(2, oldId)
                                stmt.step()
                            }
                        }
                    }
                }
            }
            conn.execSQL(
                """
                UPDATE memory_blocks
                SET updated_at = 0
                WHERE owner = 'BUILTIN' AND content = '' AND revision = 1
                  AND pinned = 0 AND status = 'ACTIVE' AND deleted_at IS NULL
                """.trimIndent(),
            )
        }

        fun seedBuiltInBlocks(conn: SQLiteConnection, nowMs: Long) {
            BUILTIN_BLOCKS.forEach { seed ->
                conn.prepare(
                    """
                    INSERT OR IGNORE INTO memory_blocks(
                        id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                        injection_mode, max_chars, owner, pinned, status, sort_order,
                        created_at, updated_at, deleted_at, revision
                    ) VALUES (?, ?, ?, ?, NULL, '', ?, ?, ?, ?, ?, 0, ?, ?, ?, 0, NULL, 1)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.bindText(1, builtInBlockId(seed.slug))
                    stmt.bindText(2, seed.slug)
                    stmt.bindText(3, seed.title)
                    stmt.bindText(4, seed.purpose)
                    stmt.bindText(5, seed.domain)
                    stmt.bindText(6, seed.sensitivity.wire)
                    stmt.bindText(7, MemoryBlockInjectionMode.ALWAYS.wire)
                    stmt.bindLong(8, seed.maxChars.toLong())
                    stmt.bindText(9, MemoryBlockOwner.BUILTIN.wire)
                    stmt.bindText(10, MemoryBlockStatus.ACTIVE.wire)
                    stmt.bindLong(11, seed.sortOrder.toLong())
                    stmt.bindLong(12, nowMs)
                    stmt.step()
                }
            }
        }
    }
}

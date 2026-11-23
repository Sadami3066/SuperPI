package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.logWarn
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class MemoryDatabase(private val rootDir: File) {
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
        val conn = driver.open(dbFile.absolutePath)
        try {
            conn.execSQL("PRAGMA journal_mode=WAL")
            conn.execSQL("PRAGMA foreign_keys=ON")
            conn.execSQL("PRAGMA busy_timeout=5000")
            val ok = conn.prepare("PRAGMA quick_check").use { stmt ->
                stmt.step() && stmt.getText(0).equals("ok", ignoreCase = true)
            }
            if (!ok) error("SQLite quick_check failed")
            createSchema(conn)
            migrateSchema(conn)
            conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            return conn
        } catch (error: Throwable) {
            runCatching { conn.close() }
            throw error
        }
    }

    private fun createSchema(conn: SQLiteConnection) {
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                content TEXT NOT NULL,
                summary TEXT,
                tags_json TEXT NOT NULL DEFAULT '[]',
                confidence TEXT NOT NULL,
                importance INTEGER NOT NULL DEFAULT 3,
                pinned INTEGER NOT NULL DEFAULT 0,
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
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                revision INTEGER NOT NULL DEFAULT 1,
                updated_by_device_id TEXT
            )
            """.trimIndent(),
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_active ON memories(deleted_at, status, kind, updated_at DESC)",
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_session ON memories(source_session_id, deleted_at)",
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_expiry ON memories(status, valid_until) WHERE valid_until IS NOT NULL",
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS entities (
                id TEXT PRIMARY KEY,
                canonical_name TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                aliases_json TEXT NOT NULL DEFAULT '[]',
                summary TEXT,
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
            CREATE TABLE IF NOT EXISTS memory_entities (
                memory_id TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                PRIMARY KEY (memory_id, entity_id),
                FOREIGN KEY (memory_id) REFERENCES memories(id) ON DELETE CASCADE,
                FOREIGN KEY (entity_id) REFERENCES entities(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                memory_id UNINDEXED,
                content_tokens,
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
            CREATE TABLE IF NOT EXISTS pending_ingestions (
                session_id TEXT PRIMARY KEY,
                session_file TEXT,
                transcript TEXT NOT NULL,
                last_entry_id TEXT,
                reason TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                last_error TEXT
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS dream_runs (
                id TEXT PRIMARY KEY,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                status TEXT NOT NULL,
                model_ref TEXT,
                promoted_count INTEGER NOT NULL DEFAULT 0,
                superseded_count INTEGER NOT NULL DEFAULT 0,
                expired_count INTEGER NOT NULL DEFAULT 0,
                merged_entities INTEGER NOT NULL DEFAULT 0,
                error TEXT
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS core_snapshot (
                id INTEGER PRIMARY KEY CHECK (id = 1),
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
            CREATE TABLE IF NOT EXISTS sync_ops (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                entity_kind TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                op TEXT NOT NULL,
                revision INTEGER NOT NULL,
                created_at INTEGER NOT NULL
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
    }

    private fun migrateSchema(conn: SQLiteConnection) {
        val memoryCols = tableColumns(conn, "memories")
        if ("updated_by_device_id" !in memoryCols) {
            conn.execSQL("ALTER TABLE memories ADD COLUMN updated_by_device_id TEXT")
        }
        val entityCols = tableColumns(conn, "entities")
        if ("merged_into" !in entityCols) {
            conn.execSQL("ALTER TABLE entities ADD COLUMN merged_into TEXT")
        }
        if ("updated_by_device_id" !in entityCols) {
            conn.execSQL("ALTER TABLE entities ADD COLUMN updated_by_device_id TEXT")
        }
    }

    private fun tableColumns(conn: SQLiteConnection, tableName: String): Set<String> {
        val cols = HashSet<String>()
        conn.prepare("PRAGMA table_info($tableName)").use { stmt ->
            while (stmt.step()) cols += stmt.getText(1).lowercase()
        }
        return cols
    }

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

    private companion object {
        private const val TAG = "MemoryDatabase"
        private const val DB_FILE_NAME = "memory.db"
        private const val SCHEMA_VERSION = 2
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
    }
}

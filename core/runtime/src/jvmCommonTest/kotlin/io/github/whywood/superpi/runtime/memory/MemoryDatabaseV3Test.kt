package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemorySensitivity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryDatabaseV3Test {
    private lateinit var rootDir: File
    private var nowMs = 1_760_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-db-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun initializesAllV3TablesAndSeedsSixBuiltinPersonalBlocksAndAllowsCustomBlocks() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val blocks = store.listBlocks(includeArchived = true)
            val slugs = blocks.map { it.slug }.toSet()
            assertEquals(
                setOf(
                    "self_profile",
                    "relationships",
                    "preferences_boundaries",
                    "life_rhythm_health",
                    "active_threads",
                    "work_study",
                ),
                slugs,
            )

            val db = MemoryDatabase(rootDir, clock = { nowMs })
            try {
                db.read { conn ->
                    val expectedTables = listOf(
                        "memory_blocks",
                        "memory_block_proposals",
                        "entities",
                        "entity_relations",
                        "memory_events",
                        "event_entities",
                        "memory_audit_log",
                        "memory_dispatch_queue",
                        "memory_fts",
                        "ingestion_watermarks",
                        "memory_agent_runs",
                        "context_cache",
                        "sync_state",
                    )
                    expectedTables.forEach { table ->
                        assertTrue("missing table $table", MemoryDatabase.tableExists(conn, table))
                    }
                }
            } finally {
                db.close()
            }

            val custom1 = store.createBlock(
                MemoryBlockDraft(
                    slug = "coffee_log",
                    title = "手冲咖啡档案",
                    purpose = "记录豆种、冲煮水温与风味偏好",
                    domain = MemoryDomains.LIFE,
                    sensitivity = MemorySensitivity.NORMAL,
                    content = "偏爱水洗耶加雪菲，V60 92度",
                    owner = MemoryBlockOwner.USER,
                ),
            )
            val custom2 = store.createBlock(
                MemoryBlockDraft(
                    slug = "coffee_log",
                    title = "Plugin Coffee Tracker",
                    purpose = "Plugin structured block",
                    owner = MemoryBlockOwner.PLUGIN,
                ),
            )
            assertEquals("coffee_log", custom1.slug)
            assertEquals("coffee_log_2", custom2.slug)
            assertEquals(8, store.listBlocks().size)
        } finally {
            store.close()
        }
    }

    @Test
    fun migratesLegacyV2DatabaseWithBackupsPreservesPinnedManualEntitiesAndPauseWindows() = runBlocking {
        rootDir.mkdirs()
        val dbFile = File(rootDir, MemoryDatabase.DB_FILE_NAME)
        val driver = BundledSQLiteDriver()
        val v2Conn = driver.open(dbFile.absolutePath)
        try {
            v2Conn.execSQL(
                """
                CREATE TABLE memories (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    summary TEXT,
                    tags_json TEXT NOT NULL DEFAULT '[]',
                    confidence TEXT NOT NULL DEFAULT 'CONFIRMED',
                    importance INTEGER NOT NULL DEFAULT 3,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    valid_from INTEGER NOT NULL,
                    valid_until INTEGER,
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    superseded_by TEXT,
                    source_kind TEXT NOT NULL DEFAULT 'CHAT_AUTO',
                    source_session_id TEXT,
                    source_entry_id TEXT,
                    source_quote TEXT,
                    access_count INTEGER NOT NULL DEFAULT 0,
                    last_accessed_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            v2Conn.execSQL(
                """
                CREATE TABLE entities (
                    id TEXT PRIMARY KEY,
                    canonical_name TEXT NOT NULL,
                    entity_type TEXT NOT NULL,
                    aliases_json TEXT NOT NULL DEFAULT '[]',
                    summary TEXT,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    revision INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            v2Conn.execSQL(
                """
                CREATE TABLE memory_entities (
                    memory_id TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    PRIMARY KEY (memory_id, entity_id)
                )
                """.trimIndent(),
            )
            v2Conn.execSQL("CREATE TABLE memories_fts (memory_id UNINDEXED, content_tokens)")
            v2Conn.execSQL("CREATE TABLE pending_ingestions (session_id TEXT PRIMARY KEY)")
            v2Conn.execSQL("CREATE TABLE dream_runs (id TEXT PRIMARY KEY)")
            v2Conn.execSQL("CREATE TABLE core_snapshot (id TEXT PRIMARY KEY)")
            v2Conn.execSQL("CREATE TABLE sync_ops (op_id TEXT PRIMARY KEY)")
            v2Conn.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

            v2Conn.execSQL(
                """
                INSERT INTO sync_state(key, value) VALUES(
                    'pause_windows',
                    '[{"start_ms":1759990000000,"end_ms":1759995000000}]'
                )
                """.trimIndent(),
            )
            v2Conn.execSQL(
                """
                INSERT INTO entities(id, canonical_name, entity_type, aliases_json, summary, updated_at, deleted_at, revision)
                VALUES('ent-mom', '母亲', 'PERSON', '["妈妈"]', '住在杭州', 1759996000000, NULL, 2)
                """.trimIndent(),
            )
            v2Conn.execSQL(
                """
                INSERT INTO memories(
                    id, kind, content, summary, tags_json, confidence, importance, pinned,
                    valid_from, valid_until, status, superseded_by, source_kind,
                    source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                    created_at, updated_at, deleted_at, revision
                ) VALUES(
                    'mem-pinned', 'IDENTITY', '对花生严重过敏', NULL, '["健康"]', 'CONFIRMED', 5, 1,
                    1759991000000, NULL, 'ACTIVE', NULL, 'CHAT_EXPLICIT',
                    's-1', NULL, NULL, 2, 1759992000000,
                    1759991000000, 1759991000000, NULL, 1
                )
                """.trimIndent(),
            )
            v2Conn.execSQL(
                """
                INSERT INTO memories(
                    id, kind, content, summary, tags_json, confidence, importance, pinned,
                    valid_from, valid_until, status, superseded_by, source_kind,
                    source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                    created_at, updated_at, deleted_at, revision
                ) VALUES(
                    'mem-manual', 'PREFERENCE', '妈妈喜欢低糖清淡饮食', NULL, '["家人"]', 'CONFIRMED', 4, 0,
                    1759992000000, NULL, 'ACTIVE', NULL, 'USER_MANUAL',
                    NULL, NULL, NULL, 0, NULL,
                    1759992000000, 1759992000000, NULL, 1
                )
                """.trimIndent(),
            )
            v2Conn.execSQL("INSERT INTO memory_entities(memory_id, entity_id) VALUES('mem-manual', 'ent-mom')")
            v2Conn.execSQL(
                """
                INSERT INTO memories(
                    id, kind, content, summary, tags_json, confidence, importance, pinned,
                    valid_from, valid_until, status, superseded_by, source_kind,
                    source_session_id, source_entry_id, source_quote, access_count, last_accessed_at,
                    created_at, updated_at, deleted_at, revision
                ) VALUES(
                    'mem-auto-noise', 'EPISODIC', '曾经聊过一次周末散步', NULL, '[]', 'OBSERVED', 2, 0,
                    1759993000000, NULL, 'ACTIVE', NULL, 'CHAT_AUTO',
                    's-2', NULL, NULL, 0, NULL,
                    1759993000000, 1759993000000, NULL, 1
                )
                """.trimIndent(),
            )
        } finally {
            v2Conn.close()
        }

        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()

            val backupDb = File(rootDir, "${MemoryDatabase.BACKUPS_DIR_NAME}/memory.v2.backup.db")
            val backupMd = File(rootDir, "${MemoryDatabase.BACKUPS_DIR_NAME}/memory-v2-backup.md")
            assertTrue(backupDb.isFile && backupDb.length() > 0L)
            assertTrue(backupMd.isFile)
            val backupMdText = backupMd.readText()
            assertTrue(backupMdText.contains("对花生严重过敏"))
            assertTrue(backupMdText.contains("妈妈喜欢低糖清淡饮食"))
            assertTrue(backupMdText.contains("曾经聊过一次周末散步"))

            val events = store.listEvents(MemoryEventFilter(limit = 50))
            assertEquals(setOf("mem-pinned", "mem-manual"), events.map { it.id }.toSet())
            val pinnedMigrated = store.getEvent("mem-pinned")!!
            assertEquals(MemoryEpistemicTier.USER_PINNED, pinnedMigrated.epistemicTier)
            assertTrue(pinnedMigrated.pinned)

            val manualMigrated = store.getEvent("mem-manual")!!
            assertEquals(listOf("母亲"), manualMigrated.entityNames)

            val entities = store.listEntities()
            assertEquals(listOf("母亲"), entities.map { it.canonicalName })

            val pauseWindows = store.readPauseWindows()
            assertEquals(1, pauseWindows.size)
            assertEquals(1759990000000L, pauseWindows.single().startMs)
            assertEquals(1759995000000L, pauseWindows.single().endMs)

            val overview = store.overview()
            assertEquals(1, overview.pendingDispatchJobs)
            val dequeued = store.dequeueNextJob()
            assertNotNull(dequeued)
            assertEquals(DispatchJobKind.MIGRATION_REHYDRATE, dequeued!!.jobKind)
            assertTrue(dequeued.payloadJson.contains("mem-auto-noise"))

            val verifyConn = BundledSQLiteDriver().open(dbFile.absolutePath)
            try {
                assertFalse(MemoryDatabase.tableExists(verifyConn, "memories"))
                assertFalse(MemoryDatabase.tableExists(verifyConn, "memories_fts"))
                assertFalse(MemoryDatabase.tableExists(verifyConn, "dream_runs"))
                assertFalse(MemoryDatabase.tableExists(verifyConn, "sync_ops"))
            } finally {
                verifyConn.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun migratesV3SchemaToV4AllowsRecreatingDeletedCustomBlockAndAddsNextRetryAt() = runBlocking {
        rootDir.mkdirs()
        val dbFile = File(rootDir, MemoryDatabase.DB_FILE_NAME)
        val driver = BundledSQLiteDriver()
        val v3Conn = driver.open(dbFile.absolutePath)
        try {
            v3Conn.execSQL(
                """
                CREATE TABLE memory_blocks (
                    id TEXT PRIMARY KEY,
                    slug TEXT NOT NULL UNIQUE,
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
            v3Conn.execSQL(
                """
                CREATE TABLE memory_dispatch_queue (
                    id TEXT PRIMARY KEY,
                    job_kind TEXT NOT NULL,
                    priority INTEGER NOT NULL,
                    session_id TEXT,
                    payload_json TEXT NOT NULL,
                    transcript_hash TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            v3Conn.execSQL("PRAGMA user_version = 3")
        } finally {
            v3Conn.close()
        }

        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            store.ensure()
            val first = store.createBlock(
                MemoryBlockDraft(
                    slug = "travel_gear",
                    title = "出行装备",
                    purpose = "常带物品",
                    content = "降噪耳机",
                    owner = MemoryBlockOwner.USER,
                ),
            )
            assertTrue(store.deleteCustomBlock(first.id))
            val recreated = store.createBlock(
                MemoryBlockDraft(
                    slug = "travel_gear",
                    title = "出行装备",
                    purpose = "常带物品",
                    content = "折叠伞",
                    owner = MemoryBlockOwner.USER,
                ),
            )
            assertEquals("travel_gear", recreated.slug)
            assertEquals("折叠伞", store.getBlockBySlug("travel_gear")!!.content)

            val db = MemoryDatabase(rootDir, clock = { nowMs })
            try {
                db.read { conn ->
                    val queueCols = MemoryDatabase.tableColumns(conn, "memory_dispatch_queue")
                    assertTrue("next_retry_at" in queueCols)
                    val version = conn.prepare("PRAGMA user_version").use { stmt ->
                        if (stmt.step()) stmt.getLong(0).toInt() else 0
                    }
                    assertEquals(MemoryDatabase.SCHEMA_VERSION, version)
                    var hasUniqueAutoIndex = false
                    conn.prepare("PRAGMA index_list(memory_blocks)").use { stmt ->
                        while (stmt.step()) {
                            if (stmt.getText(3) == "u") {
                                hasUniqueAutoIndex = true
                            }
                        }
                    }
                    assertFalse(hasUniqueAutoIndex)
                }
            } finally {
                db.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun reopeningV4DatabasePreservesExistingRowsWithoutRebuildingTable() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs })
        store.ensure()
        val created = store.createBlock(
            MemoryBlockDraft(
                slug = "keep_rowid",
                title = "保留检查",
                purpose = "冷启动不重建表",
                content = "稳定内容",
                owner = MemoryBlockOwner.USER,
            ),
        )
        store.close()

        val initialRowId: Long
        val checkDb = MemoryDatabase(rootDir, clock = { nowMs })
        try {
            initialRowId = checkDb.read { conn ->
                conn.prepare("SELECT rowid FROM memory_blocks WHERE id = ?").use { stmt ->
                    stmt.bindText(1, created.id)
                    assertTrue(stmt.step())
                    stmt.getLong(0)
                }
            }
            checkDb.read { conn ->
                var hasUniqueAutoIndex = false
                conn.prepare("PRAGMA index_list(memory_blocks)").use { stmt ->
                    while (stmt.step()) {
                        if (stmt.getText(3) == "u") hasUniqueAutoIndex = true
                    }
                }
                assertFalse(hasUniqueAutoIndex)
            }
        } finally {
            checkDb.close()
        }

        val reopened = MemoryDatabase(rootDir, clock = { nowMs })
        try {
            val rowIdAfterReopen = reopened.read { conn ->
                conn.prepare("SELECT rowid FROM memory_blocks WHERE id = ?").use { stmt ->
                    stmt.bindText(1, created.id)
                    assertTrue(stmt.step())
                    stmt.getLong(0)
                }
            }
            assertEquals(initialRowId, rowIdAfterReopen)
        } finally {
            reopened.close()
        }
    }
}

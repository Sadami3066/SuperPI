package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryMutationActor
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.Content
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.Usage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemorySyncV3Test {
    private lateinit var rootDir: File
    private var nowMs = 1_760_000_000_000L
    private var currentDeviceId: String? = "dev-a"

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("superpi-mem-sync-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun migratesV4DatabaseToV5WithDeterministicBuiltinIdsAndSyncStructures() = runBlocking {
        rootDir.mkdirs()
        val dbFile = File(rootDir, MemoryDatabase.DB_FILE_NAME)
        val conn = BundledSQLiteDriver().open(dbFile.absolutePath)
        try {
            conn.execSQL(
                """
                CREATE TABLE memory_blocks (
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
                CREATE TABLE memory_block_proposals (
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
                    resolved_at INTEGER
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE entities (
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
                CREATE TABLE entity_relations (
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
                    revision INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE memory_events (
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
                """
                CREATE TABLE event_entities (
                    event_id TEXT NOT NULL,
                    entity_id TEXT NOT NULL,
                    role TEXT NOT NULL DEFAULT 'MENTIONED',
                    PRIMARY KEY (event_id, entity_id)
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE TABLE memory_audit_log (
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
                    next_retry_at INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                CREATE VIRTUAL TABLE memory_fts USING fts5(
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
                CREATE TABLE ingestion_watermarks (
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
                CREATE TABLE memory_agent_runs (
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
                CREATE TABLE context_cache (
                    scope_wire TEXT PRIMARY KEY,
                    rendered_text TEXT NOT NULL,
                    char_count INTEGER NOT NULL,
                    revision INTEGER NOT NULL,
                    generated_at INTEGER NOT NULL,
                    reason TEXT NOT NULL
                )
                """.trimIndent(),
            )
            conn.execSQL("CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL)")

            conn.execSQL(
                """
                INSERT INTO memory_blocks(
                    id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                    injection_mode, max_chars, owner, pinned, status, sort_order,
                    created_at, updated_at, deleted_at, revision, updated_by_device_id
                ) VALUES (
                    'old-builtin-self', 'self_profile', '关于我与人生阶段', '个人背景', NULL, '',
                    'PERSONAL', 'NORMAL', 'ALWAYS', 2000, 'BUILTIN', 0, 'ACTIVE', 10,
                    1759990000000, 1759990000000, NULL, 1, NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO memory_blocks(
                    id, slug, title, purpose, schema_hint, content, domain, sensitivity,
                    injection_mode, max_chars, owner, pinned, status, sort_order,
                    created_at, updated_at, deleted_at, revision, updated_by_device_id
                ) VALUES (
                    'old-builtin-work', 'work_study', '工作与学习域', '职业背景', NULL, 'Android engineer',
                    'WORK', 'NORMAL', 'ALWAYS', 2000, 'BUILTIN', 0, 'ACTIVE', 60,
                    1759990000000, 1759991000000, NULL, 2, NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO memory_audit_log(
                    id, run_id, actor, action_type, target_table, target_id,
                    summary_user_text, created_at
                ) VALUES (
                    'aud-1', NULL, 'USER', 'UPDATE', 'memory_blocks', 'old-builtin-self',
                    'seeded', 1759990000000
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO memory_block_proposals(
                    id, slug, title, purpose, domain, sensitivity, seed_content,
                    supporting_event_ids_json, reason, status, created_at, resolved_at
                ) VALUES (
                    'prop-v4', 'pet_log', 'Pet Log', 'Track pet visits', 'LIFE', 'NORMAL',
                    'Cat checkup', '["ev-1"]', '5 events recorded', 'PENDING', 1759992000000, NULL
                )
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO entities(id, canonical_name, entity_type, created_at, updated_at)
                VALUES ('ent-a', 'Alice', 'PERSON', 1759990000000, 1759990000000),
                       ('ent-b', 'Bob', 'PERSON', 1759990000000, 1759990000000)
                """.trimIndent(),
            )
            conn.execSQL(
                """
                INSERT INTO entity_relations(
                    id, from_entity_id, relation_type, to_entity_id, valid_from, created_at, updated_at
                ) VALUES ('rel-v4', 'ent-a', 'friend_of', 'ent-b', 1759990000000, 1759990000000, 1759990000000)
                """.trimIndent(),
            )
            conn.execSQL("PRAGMA user_version = 4")
        } finally {
            conn.close()
        }

        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val selfBlock = store.getBlockBySlug("self_profile")!!
            assertEquals("builtin_self_profile", selfBlock.id)
            assertEquals(0L, selfBlock.updatedAt)

            val workBlock = store.getBlockBySlug("work_study")!!
            assertEquals("builtin_work_study", workBlock.id)
            assertEquals("Android engineer", workBlock.content)
            assertEquals(1759991000000L, workBlock.updatedAt)

            val db = MemoryDatabase(rootDir, clock = { nowMs })
            try {
                db.read { upgraded ->
                    upgraded.prepare("PRAGMA user_version").use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(MemoryDatabase.SCHEMA_VERSION.toLong(), stmt.getLong(0))
                    }
                    assertTrue(MemoryDatabase.tableExists(upgraded, "memory_sync_ops"))
                    assertTrue("updated_by_device_id" in MemoryDatabase.tableColumns(upgraded, "entity_relations"))
                    val propCols = MemoryDatabase.tableColumns(upgraded, "memory_block_proposals")
                    assertTrue(setOf("updated_at", "deleted_at", "revision", "updated_by_device_id").all { it in propCols })
                    upgraded.prepare("SELECT updated_at FROM memory_block_proposals WHERE id = 'prop-v4'").use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals(1759992000000L, stmt.getLong(0))
                    }
                    upgraded.prepare("SELECT target_id FROM memory_audit_log WHERE id = 'aud-1'").use { stmt ->
                        assertTrue(stmt.step())
                        assertEquals("builtin_self_profile", stmt.getText(0))
                    }
                }
            } finally {
                db.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun excludesUntouchedBuiltinSeedsFromBaselinePushAndSortsDirtyOpsInFkOrder() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            assertTrue(store.collectPendingPushBatch("acct-1", "dev-a", 100).isEmpty())

            val profile = store.getBlockBySlug("self_profile")!!
            nowMs += 1_000L
            store.updateBlock(id = profile.id, content = "Lives in Hangzhou")

            nowMs += 1_000L
            val customBlock = store.createBlock(
                MemoryBlockDraft(
                    slug = "travel_notes",
                    title = "Travel Notes",
                    purpose = "Track preferred airlines",
                    content = "Window seat",
                ),
            )

            nowMs += 1_000L
            val alice = store.upsertEntity(MemoryEntityRef("Alice", "PERSON"))
            val eventIds = (1..5).map { idx ->
                nowMs += 100L
                store.saveEvent(
                    MemoryEventDraft(
                        eventKind = MemoryEventKind.EPISODE,
                        content = "Coffee chat #$idx with Alice",
                        epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                        entities = listOf(MemoryEntityRef("Alice", "PERSON", role = "SUBJECT")),
                    ),
                ).id
            }

            nowMs += 1_000L
            val relation = store.upsertRelation(
                fromEntityName = "Alice",
                relationType = "works_with",
                toEntityName = "Bob",
            )!!

            nowMs += 1_000L
            val proposal = store.createProposal(
                slug = "coffee_habit",
                title = "Coffee Habit",
                purpose = "Track pour-over beans",
                domain = MemoryDomains.LIFE,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "Washed beans",
                supportingEventIds = eventIds,
                reason = "Frequent coffee logs",
            )!!

            val batch = store.collectPendingPushBatch("acct-1", "dev-a", 100)
            val kinds = batch.map { it.kind }
            assertTrue(kinds.zipWithNext { a, b -> a.pushOrder <= b.pushOrder }.all { it })

            val blockIds = batch.filter { it.kind == MemorySyncKind.BLOCK }.map { it.block!!.id }.toSet()
            assertEquals(setOf("builtin_self_profile", customBlock.id), blockIds)
            assertFalse("builtin_work_study" in blockIds)

            val eventOp = batch.first { it.kind == MemorySyncKind.EVENT && it.event?.id == eventIds.first() }
            assertEquals(listOf(MemoryEventEntityLink(alice.id, "SUBJECT")), eventOp.event?.entityLinks)
            assertTrue(batch.any { it.kind == MemorySyncKind.RELATION && it.relation?.id == relation.id })
            assertTrue(batch.any { it.kind == MemorySyncKind.PROPOSAL && it.proposal?.id == proposal.id })
        } finally {
            store.close()
        }
    }

    @Test
    fun casAckKeepsNewerOpWhenRecordMutatedBeforeAck() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val work = store.getBlockBySlug("work_study")!!
            nowMs += 1_000L
            store.updateBlock(id = work.id, content = "Version 1")

            val firstBatch = store.collectPendingPushBatch("acct-1", "dev-a", 100)
            val firstOp = firstBatch.single { it.block?.id == work.id }
            assertEquals("Version 1", firstOp.block?.content)

            nowMs += 1_000L
            store.updateBlock(id = work.id, content = "Version 2")

            store.markPushAcked("acct-1", listOf(firstOp), remoteLastConsolidationAt = nowMs + 500L)
            assertEquals(nowMs + 500L, store.remoteLastConsolidationAt("acct-1"))

            val secondBatch = store.collectPendingPushBatch("acct-1", "dev-a", 100)
            val secondOp = secondBatch.single { it.block?.id == work.id }
            assertNotEquals(firstOp.opId, secondOp.opId)
            assertEquals("Version 2", secondOp.block?.content)

            store.markPushAcked("acct-1", secondBatch, remoteLastConsolidationAt = null)
            assertTrue(store.collectPendingPushBatch("acct-1", "dev-a", 100).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun resolvesLwwConflictsAcrossAllRecordKindsIncludingBuiltinSlugCollision() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val localSelf = store.getBlockBySlug("self_profile")!!
            nowMs = 1_000L
            store.updateBlock(id = localSelf.id, content = "Local profile v1000")
            val entity = store.upsertEntity(MemoryEntityRef("Alice", "PERSON"))
            val bob = store.upsertEntity(MemoryEntityRef("Bob", "PERSON"))
            val carol = store.upsertEntity(MemoryEntityRef("Carol", "PERSON"))
            val ev = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "Local fact",
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                ),
            )
            val rel = store.upsertRelation("Alice", "friend_of", "Bob")!!
            val supporting = (1..5).map { idx ->
                store.saveEvent(
                    MemoryEventDraft(
                        eventKind = MemoryEventKind.EPISODE,
                        content = "Support $idx",
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                    ),
                ).id
            }
            val prop = store.createProposal(
                slug = "reading_list",
                title = "Reading List",
                purpose = "Books to read",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "Dune",
                supportingEventIds = supporting,
                reason = "Repeated book mentions",
            )!!

            val staleEntries = listOf(
                MemoryRemotePullEntry(
                    seq = 1L,
                    kind = MemorySyncKind.BLOCK,
                    deviceId = "dev-b",
                    block = MemoryBlockSyncRecord(
                        id = "remote-legacy-self",
                        slug = "self_profile",
                        title = "About Me",
                        purpose = "Bio",
                        content = "Stale remote profile",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        injectionMode = MemoryBlockInjectionMode.ALWAYS,
                        maxChars = 2000,
                        owner = MemoryBlockOwner.BUILTIN,
                        pinned = false,
                        status = MemoryBlockStatus.ACTIVE,
                        sortOrder = 10,
                        createdAt = 100L,
                        updatedAt = 500L,
                        revision = 9L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 2L,
                    kind = MemorySyncKind.EVENT,
                    deviceId = "dev-b",
                    event = MemoryEventSyncRecord(
                        id = ev.id,
                        eventKind = MemoryEventKind.FACT,
                        content = "Stale event",
                        qualifiersJson = "{}",
                        semanticKeywords = emptyList(),
                        tags = emptyList(),
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                        importance = 3,
                        pinned = false,
                        eventTime = 500L,
                        eventTimePrecision = MemoryEventTimePrecision.DAY,
                        validFrom = 500L,
                        status = MemoryItemStatus.ACTIVE,
                        sourceKind = MemorySourceKind.USER_MANUAL,
                        createdAt = 500L,
                        updatedAt = 500L,
                        revision = 9L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
            )
            val staleRes = store.applyRemotePullBatch(
                accountKey = "acct-1",
                selfDeviceId = "dev-a",
                entries = staleEntries,
                nextSince = 2L,
                remoteLastConsolidationAt = null,
            )
            assertEquals(0, staleRes.appliedCount)
            assertEquals("Local profile v1000", store.getBlockBySlug("self_profile")!!.content)
            assertEquals("Local fact", store.getEvent(ev.id)!!.content)

            val winningEntries = listOf(
                MemoryRemotePullEntry(
                    seq = 3L,
                    kind = MemorySyncKind.BLOCK,
                    deviceId = "dev-z",
                    block = MemoryBlockSyncRecord(
                        id = "remote-legacy-self",
                        slug = "self_profile",
                        title = "About Me",
                        purpose = "Bio",
                        content = "Remote winning profile",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        injectionMode = MemoryBlockInjectionMode.ALWAYS,
                        maxChars = 2000,
                        owner = MemoryBlockOwner.BUILTIN,
                        pinned = true,
                        status = MemoryBlockStatus.ACTIVE,
                        sortOrder = 10,
                        createdAt = 100L,
                        updatedAt = 5_000L,
                        revision = 3L,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 4L,
                    kind = MemorySyncKind.ENTITY,
                    deviceId = "dev-z",
                    entity = MemoryEntitySyncRecord(
                        id = entity.id,
                        canonicalName = "Alice Updated",
                        entityType = "PERSON",
                        aliases = listOf("Ali"),
                        attributesJson = """{"city":"Shanghai"}""",
                        summary = "Updated profile",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        pinned = false,
                        createdAt = entity.createdAt,
                        updatedAt = 5_000L,
                        revision = 3L,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 8L,
                    kind = MemorySyncKind.ENTITY,
                    deviceId = "dev-z",
                    entity = MemoryEntitySyncRecord(
                        id = carol.id,
                        canonicalName = "Carol Merged",
                        entityType = "PERSON",
                        aliases = emptyList(),
                        attributesJson = "{}",
                        summary = "Merged into Bob",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        pinned = false,
                        createdAt = carol.createdAt,
                        updatedAt = 5_000L,
                        deletedAt = 5_000L,
                        revision = 2L,
                        mergedInto = bob.id,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 5L,
                    kind = MemorySyncKind.EVENT,
                    deviceId = "dev-z",
                    event = MemoryEventSyncRecord(
                        id = ev.id,
                        eventKind = MemoryEventKind.FACT,
                        content = "Tiebreak winner fact",
                        qualifiersJson = "{}",
                        semanticKeywords = listOf("tiebreak"),
                        tags = listOf("remote"),
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                        importance = 4,
                        pinned = true,
                        eventTime = 1_000L,
                        eventTimePrecision = MemoryEventTimePrecision.DAY,
                        validFrom = 1_000L,
                        status = MemoryItemStatus.ACTIVE,
                        sourceKind = MemorySourceKind.USER_MANUAL,
                        createdAt = 1_000L,
                        updatedAt = 1_000L,
                        revision = 1L,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 6L,
                    kind = MemorySyncKind.RELATION,
                    deviceId = "dev-z",
                    relation = MemoryRelationSyncRecord(
                        id = rel.id,
                        fromEntityId = entity.id,
                        relationType = "colleague_of",
                        toEntityId = bob.id,
                        label = "Teammate",
                        attributesJson = "{}",
                        validFrom = 1_000L,
                        epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                        domain = MemoryDomains.WORK,
                        sensitivity = MemorySensitivity.NORMAL,
                        createdAt = rel.createdAt,
                        updatedAt = 5_000L,
                        revision = 2L,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 7L,
                    kind = MemorySyncKind.PROPOSAL,
                    deviceId = "dev-z",
                    proposal = MemoryProposalSyncRecord(
                        id = prop.id,
                        slug = prop.slug,
                        title = prop.title,
                        purpose = prop.purpose,
                        domain = prop.domain,
                        sensitivity = prop.sensitivity,
                        seedContent = prop.seedContent,
                        supportingEventIds = supporting,
                        reason = prop.reason,
                        status = MemoryProposalStatus.DISMISSED,
                        createdAt = prop.createdAt,
                        resolvedAt = 5_000L,
                        updatedAt = 5_000L,
                        deletedAt = 5_000L,
                        revision = 3L,
                        updatedByDeviceId = "dev-z",
                    ),
                ),
            )

            val winRes = store.applyRemotePullBatch(
                accountKey = "acct-1",
                selfDeviceId = "dev-a",
                entries = winningEntries,
                nextSince = 7L,
                remoteLastConsolidationAt = null,
            )
            assertEquals(6, winRes.appliedCount)
            assertTrue(winRes.hadLocalAndRemoteOverlap)

            val activeSelf = store.getBlockBySlug("self_profile")!!
            assertEquals("remote-legacy-self", activeSelf.id)
            assertEquals("Remote winning profile", activeSelf.content)
            assertNull(store.getBlockById(localSelf.id))

            assertEquals("Tiebreak winner fact", store.getEvent(ev.id)!!.content)
            assertEquals("colleague_of", store.listRelations(MemoryAccessScope.PERSONAL).single { it.id == rel.id }.relationType)
            assertTrue(store.listProposals(null).none { it.id == prop.id })
            val remainingEntities = store.listEntities()
            assertEquals(setOf(entity.id, bob.id), remainingEntities.map { it.id }.toSet())
            assertEquals("Alice Updated", remainingEntities.single { it.id == entity.id }.canonicalName)
            assertFalse(remainingEntities.any { it.id == carol.id })
        } finally {
            store.close()
        }
    }

    @Test
    fun defersAndReplaysCrossPageEntityLinksAndRelations() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val page1Entries = listOf(
                MemoryRemotePullEntry(
                    seq = 1L,
                    kind = MemorySyncKind.EVENT,
                    deviceId = "dev-b",
                    event = MemoryEventSyncRecord(
                        id = "ev-cross",
                        eventKind = MemoryEventKind.EPISODE,
                        content = "Met Clara in Kyoto",
                        qualifiersJson = "{}",
                        semanticKeywords = listOf("Kyoto"),
                        tags = emptyList(),
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                        importance = 3,
                        pinned = false,
                        eventTime = 2_000L,
                        eventTimePrecision = MemoryEventTimePrecision.DAY,
                        validFrom = 2_000L,
                        status = MemoryItemStatus.ACTIVE,
                        sourceKind = MemorySourceKind.USER_MANUAL,
                        entityLinks = listOf(MemoryEventEntityLink("ent-1", "SUBJECT")),
                        createdAt = 2_000L,
                        updatedAt = 2_000L,
                        revision = 1L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 2L,
                    kind = MemorySyncKind.RELATION,
                    deviceId = "dev-b",
                    relation = MemoryRelationSyncRecord(
                        id = "rel-cross",
                        fromEntityId = "ent-1",
                        relationType = "visited",
                        toEntityId = "ent-2",
                        label = "Visited",
                        attributesJson = "{}",
                        validFrom = 2_000L,
                        epistemicTier = MemoryEpistemicTier.OBSERVED,
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        createdAt = 2_000L,
                        updatedAt = 2_000L,
                        revision = 1L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
            )

            val res1 = store.applyRemotePullBatch(
                accountKey = "acct-1",
                selfDeviceId = "dev-a",
                entries = page1Entries,
                nextSince = 2L,
                remoteLastConsolidationAt = null,
            )
            assertEquals(1, res1.appliedCount)
            assertEquals(2, res1.deferredEntries.size)
            assertNotNull(store.getEvent("ev-cross"))
            assertTrue(store.listRelations().isEmpty())

            val page2Entries = listOf(
                MemoryRemotePullEntry(
                    seq = 3L,
                    kind = MemorySyncKind.ENTITY,
                    deviceId = "dev-b",
                    entity = MemoryEntitySyncRecord(
                        id = "ent-1",
                        canonicalName = "Clara",
                        entityType = "PERSON",
                        aliases = emptyList(),
                        attributesJson = "{}",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        pinned = false,
                        createdAt = 1_500L,
                        updatedAt = 1_500L,
                        revision = 1L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
                MemoryRemotePullEntry(
                    seq = 4L,
                    kind = MemorySyncKind.ENTITY,
                    deviceId = "dev-b",
                    entity = MemoryEntitySyncRecord(
                        id = "ent-2",
                        canonicalName = "Kyoto",
                        entityType = "PLACE",
                        aliases = emptyList(),
                        attributesJson = "{}",
                        domain = MemoryDomains.PERSONAL,
                        sensitivity = MemorySensitivity.NORMAL,
                        pinned = false,
                        createdAt = 1_500L,
                        updatedAt = 1_500L,
                        revision = 1L,
                        updatedByDeviceId = "dev-b",
                    ),
                ),
            )
            store.applyRemotePullBatch(
                accountKey = "acct-1",
                selfDeviceId = "dev-a",
                entries = page2Entries,
                nextSince = 4L,
                remoteLastConsolidationAt = null,
            )
            assertEquals(4L, store.readPullCursor("acct-1"))

            val replayRes = store.applyRemotePullBatch(
                accountKey = "acct-1",
                selfDeviceId = "dev-a",
                entries = res1.deferredEntries,
                nextSince = 0L,
                remoteLastConsolidationAt = null,
                advanceCursor = false,
            )
            assertEquals(2, replayRes.appliedCount)
            assertTrue(replayRes.deferredEntries.isEmpty())
            assertEquals(4L, store.readPullCursor("acct-1"))

            val detail = store.inspectEntity("ent-1")!!
            assertEquals(listOf("ev-cross"), detail.linkedEvents.map { it.id })
            assertEquals("rel-cross", detail.outgoingRelations.single().id)
        } finally {
            store.close()
        }
    }

    @Test
    fun inspectsAndResolvesAccountSwitchWithMergeAndReplaceLocal() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val check1 = store.inspectAccountSwitch("https://relay.a|acct-1")
            assertFalse(check1.requiresPrompt)

            val custom = store.createBlock(
                MemoryBlockDraft(
                    slug = "custom_switch",
                    title = "Switch Test",
                    purpose = "Verify account switch",
                    content = "Keep me on merge",
                ),
            )
            val entity = store.upsertEntity(MemoryEntityRef("Kai", "PERSON"))
            val initialBatch = store.collectPendingPushBatch("https://relay.a|acct-1", "dev-a", 100)
            assertTrue(initialBatch.isNotEmpty())
            store.markPushAcked("https://relay.a|acct-1", initialBatch, remoteLastConsolidationAt = nowMs)
            assertTrue(store.collectPendingPushBatch("https://relay.a|acct-1", "dev-a", 100).isEmpty())

            val check2 = store.inspectAccountSwitch("https://relay.b|acct-2")
            assertTrue(check2.requiresPrompt)
            assertTrue(check2.localRecordCount >= 2)

            store.resolveAccountSwitch(
                accountKey = "https://relay.b|acct-2",
                accountIdentity = "https://relay.b|acct-2",
                mode = MemoryAccountSwitchMode.MERGE,
            )
            assertEquals(0L, store.readPullCursor("https://relay.b|acct-2"))
            assertFalse(store.inspectAccountSwitch("https://relay.b|acct-2").requiresPrompt)
            assertNotNull(store.getBlockById(custom.id))
            val mergeBatch = store.collectPendingPushBatch("https://relay.b|acct-2", "dev-a", 100)
            assertTrue(mergeBatch.any { it.block?.id == custom.id })
            assertTrue(mergeBatch.any { it.entity?.id == entity.id })

            val check3 = store.inspectAccountSwitch("https://relay.c|acct-3")
            assertTrue(check3.requiresPrompt)
            store.resolveAccountSwitch(
                accountKey = "https://relay.c|acct-3",
                accountIdentity = "https://relay.c|acct-3",
                mode = MemoryAccountSwitchMode.REPLACE_LOCAL,
            )
            assertNull(store.getBlockById(custom.id))
            assertTrue(store.listEntities().isEmpty())
            assertTrue(store.listEvents(MemoryEventFilter(limit = 50)).isEmpty())
            assertEquals(0L, store.getBlockBySlug("self_profile")!!.updatedAt)
            assertNull(store.remoteLastConsolidationAt("https://relay.c|acct-3"))
            assertTrue(store.collectPendingPushBatch("https://relay.c|acct-3", "dev-a", 100).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun coordinatesColdConsolidationLeasesCooldownsAndAlwaysReleasesLease() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            store.ensure()
            val events = CopyOnWriteArrayList<String>()
            var nextLeaseResult: ConsolidationLeaseResult = ConsolidationLeaseResult.Acquired("lease-1")
            var gatewayShouldThrow = false
            val coordinator = object : MemoryConsolidationCoordinator {
                override suspend fun prepareForConsolidation(
                    forceManual: Boolean,
                    minIntervalMs: Long,
                ): ConsolidationLeaseResult {
                    events += "prepare:$forceManual:$minIntervalMs"
                    return nextLeaseResult
                }

                override suspend fun onConsolidationCompleted(leaseId: String?, finishedAt: Long) {
                    events += "completed:$leaseId:$finishedAt"
                }

                override suspend fun onConsolidationFinished(leaseId: String?) {
                    events += "finished:$leaseId"
                }
            }
            val gateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult {
                    if (gatewayShouldThrow) throw IllegalStateException("gateway crashed")
                    return MemoryAgentStepResult.Success(
                        modelRef = "fake/cold",
                        message = SdkAssistantMessage(
                            content = mutableListOf<Content>(
                                ToolCall(
                                    id = "c-noop",
                                    name = "noop",
                                    arguments = buildJsonObject { put("reason", "nothing to do") },
                                ),
                            ),
                            api = "fake",
                            provider = "fake",
                            model = "fake-cold",
                            usage = Usage.empty(),
                            stopReason = StopReason.ToolUse,
                            timestamp = nowMs,
                        ),
                    )
                }
            }

            val service = MemoryAgentService(
                store = store,
                gateway = gateway,
                scope = scope,
                settingsProvider = { MemorySettings(coldConsolidationHours = 12) },
                clock = { nowMs },
                consolidationCoordinator = coordinator,
            )

            nextLeaseResult = ConsolidationLeaseResult.Blocked("busy")
            service.launchManualConsolidation()
            for (i in 0..40) {
                if (!service.manualState.value.running && service.manualState.value.outcomeSeq > 0L) break
                delay(15L)
            }
            assertFalse(service.manualState.value.running)
            assertEquals(ConsolidationOutcome.Busy, service.manualState.value.outcome)

            nextLeaseResult = ConsolidationLeaseResult.Blocked("cooldown")
            assertEquals(
                ConsolidationOutcome.Skipped("cooldown"),
                service.runConsolidation(manual = false, minIntervalMs = 3_600_000L, minNewEvents = 1),
            )

            nextLeaseResult = ConsolidationLeaseResult.Offline
            assertEquals(
                ConsolidationOutcome.Skipped("sync_unavailable"),
                service.runConsolidation(manual = false, minIntervalMs = 3_600_000L, minNewEvents = 0),
            )
            events.clear()
            val offlineManualOutcome = service.runConsolidation(manual = true, minIntervalMs = 0L, minNewEvents = 0)
            assertTrue(offlineManualOutcome is ConsolidationOutcome.Done)
            assertTrue("finished:null" in events)

            nowMs += 10_000L
            store.recordRemoteConsolidationAt("acct-1", nowMs - 1_000L)
            nextLeaseResult = ConsolidationLeaseResult.Acquired("lease-cooldown")
            events.clear()
            val remoteCooldown = service.runConsolidation(manual = false, minIntervalMs = 60_000L, minNewEvents = 0)
            assertEquals(ConsolidationOutcome.Skipped("cooldown_active"), remoteCooldown)
            assertTrue("finished:lease-cooldown" in events)
            assertFalse(events.any { it.startsWith("completed:") })

            events.clear()
            nextLeaseResult = ConsolidationLeaseResult.Acquired("lease-42")
            gatewayShouldThrow = true
            val failedOutcome = service.runConsolidation(manual = true, minIntervalMs = 0L, minNewEvents = 0)
            assertTrue(failedOutcome is ConsolidationOutcome.Failed)
            assertTrue("finished:lease-42" in events)
            assertFalse(events.any { it.startsWith("completed:") })

            events.clear()
            gatewayShouldThrow = false
            nextLeaseResult = ConsolidationLeaseResult.Acquired("lease-99")
            val okOutcome = service.runConsolidation(manual = true, minIntervalMs = 0L, minNewEvents = 0)
            assertTrue(okOutcome is ConsolidationOutcome.Done)
            assertTrue(events.any { it.startsWith("completed:lease-99:") })
            assertTrue("finished:lease-99" in events)
        } finally {
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun boundsSyncRecordsAndMaintainsMonotonicCommitPullCursor() = runBlocking {
        val store = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { currentDeviceId })
        try {
            store.ensure()
            val oversizedQualifiers = (1..220).associate { idx -> "k$idx" to "v".repeat(60) }
            val blankEvent = store.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.FACT,
                    content = "   ",
                    qualifiers = oversizedQualifiers,
                    semanticKeywords = (1..80).map { "kw$it" },
                    tags = (1..80).map { "tag$it" },
                ),
            )
            assertEquals("-", blankEvent.content)

            val blankProposal = store.createProposal(
                slug = "blank_reason",
                title = "Blank Reason",
                purpose = "Keep coffee log",
                domain = MemoryDomains.LIFE,
                sensitivity = MemorySensitivity.NORMAL,
                seedContent = "a".repeat(35_000),
                supportingEventIds = (1..80).map { "ev-$it" },
                reason = "   ",
            )!!
            assertEquals("Keep coffee log", blankProposal.reason)

            val batch = store.collectPendingPushBatch("acct-1", "dev-a", 100)
            val eventRecord = batch.first { it.event?.id == blankEvent.id }.event!!
            assertTrue(eventRecord.content.isNotBlank())
            assertTrue(eventRecord.qualifiersJson.length <= MemoryLimits.MAX_JSON_OBJECT_CHARS)
            assertTrue(eventRecord.semanticKeywords.size <= MemoryLimits.MAX_SYNC_LIST_ITEMS)
            assertTrue(eventRecord.tags.size <= MemoryLimits.MAX_SYNC_LIST_ITEMS)

            val propRecord = batch.first { it.proposal?.id == blankProposal.id }.proposal!!
            assertEquals("Keep coffee log", propRecord.reason)
            assertTrue(propRecord.seedContent.length <= MemoryLimits.MAX_SYNC_BLOCK_CONTENT_CHARS)
            assertTrue(propRecord.supportingEventIds.size <= MemoryLimits.MAX_SYNC_LIST_ITEMS)

            store.commitPullCursor("acct-1", nextSince = 10L, remoteLastConsolidationAt = nowMs)
            store.commitPullCursor("acct-1", nextSince = 25L, remoteLastConsolidationAt = null)
            store.commitPullCursor("acct-1", nextSince = 12L, remoteLastConsolidationAt = nowMs - 500L)
            assertEquals(25L, store.readPullCursor("acct-1"))
            assertEquals(nowMs, store.remoteLastConsolidationAt("acct-1"))
        } finally {
            store.close()
        }
    }
}

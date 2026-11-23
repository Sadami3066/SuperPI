package io.github.whywood.superpi.runtime.memory

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemorySyncStoreTest {
    private lateinit var rootDir: File
    private var nowMs: Long = 1_760_000_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-sync-store-test").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun migratesV1SchemaToV2WithoutLosingExistingRecords() = runTest {
        val dbFile = File(rootDir, "memory.db")
        val conn = BundledSQLiteDriver().open(dbFile.absolutePath)
        conn.execSQL(
            """
            CREATE TABLE memories (
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
                revision INTEGER NOT NULL DEFAULT 1
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
                summary TEXT,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                revision INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
        )
        conn.execSQL(
            """
            INSERT INTO memories(
                id, kind, content, confidence, valid_from, status, source_kind, created_at, updated_at, revision
            ) VALUES ('legacy-1', 'IDENTITY', 'Migrated user fact', 'CONFIRMED', 1000, 'ACTIVE', 'USER_MANUAL', 1000, 1000, 1)
            """.trimIndent(),
        )
        conn.execSQL("PRAGMA user_version = 1")
        conn.close()

        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val loaded = store.get("legacy-1")
            assertNotNull(loaded)
            assertEquals("Migrated user fact", loaded!!.content)
            val cursor = store.readSyncCursor("acct-1")
            assertFalse(cursor.initialPushDone)
        } finally {
            store.close()
        }
    }

    @Test
    fun exportsBaselineThenCapturesConcurrentLocalWritesWithoutEchoOnRemotePull() = runTest {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val existing = store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "Local pre-login fact"))
            val accountKey = "acct|https://relay.test|dev-a"

            val cursor = store.readSyncCursor(accountKey)
            assertFalse(cursor.initialPushDone)

            val addedDuringBaseline = store.save(MemoryDraft(kind = MemoryKind.HABIT, content = "Created while baseline running"))

            val exportedIds = HashSet<String>()
            while (true) {
                val batch = store.collectPendingPushBatch(accountKey, "dev-a", limit = 1)
                if (batch.ops.isEmpty()) break
                batch.ops.forEach { op ->
                    op.memory?.let { exportedIds += it.id }
                }
                store.markPushAcked(accountKey, batch, 10L, null)
            }
            while (true) {
                val inc = store.collectPendingPushBatch(accountKey, "dev-a", limit = 50)
                if (inc.ops.isEmpty()) break
                inc.ops.forEach { op -> op.memory?.let { exportedIds += it.id } }
                store.markPushAcked(accountKey, inc, 11L, null)
            }
            assertTrue(existing.id in exportedIds)
            assertTrue(addedDuringBaseline.id in exportedIds)
            assertTrue(store.readSyncCursor(accountKey).initialPushDone)

            val remoteRecord = MemorySyncRecord(
                id = "remote-mem-1",
                kind = MemoryKind.ACTIVE,
                content = "Plan dentist visit",
                entities = listOf("Dentist"),
                confidence = MemoryConfidence.OBSERVED,
                importance = 4,
                pinned = false,
                validFrom = nowMs + 50_000L,
                status = MemoryItemStatus.ACTIVE,
                sourceKind = MemorySourceKind.USER_MANUAL,
                createdAt = nowMs + 50_000L,
                updatedAt = nowMs + 50_000L,
                revision = 1L,
                updatedByDeviceId = "dev-b",
            )
            val mergeResult = store.applyRemotePullBatch(
                accountKey = accountKey,
                selfDeviceId = "dev-a",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 12L,
                        kind = MemorySyncOpKind.MEMORY,
                        deviceId = "dev-b",
                        memory = remoteRecord,
                    ),
                ),
                nextSince = 12L,
                serverSeq = 12L,
                remoteLastDreamAt = null,
            )
            assertEquals(1, mergeResult.appliedCount)
            assertTrue(mergeResult.hadLocalAndRemoteOverlap)
            assertNotNull(store.get("remote-mem-1"))

            val afterPullBatch = store.collectPendingPushBatch(accountKey, "dev-a", limit = 50)
            assertTrue(afterPullBatch.ops.isEmpty())

            nowMs = 1_759_000_000_000L
            val editedOnSlowClock = store.update(
                id = "remote-mem-1",
                content = "Rescheduled dentist visit",
                kind = MemoryKind.ACTIVE,
                tags = emptyList(),
                confidence = MemoryConfidence.CONFIRMED,
                pinned = true,
                validUntil = null,
            )!!
            assertTrue(editedOnSlowClock.updatedAt > remoteRecord.updatedAt)

            val afterEditBatch = store.collectPendingPushBatch(accountKey, "dev-a", limit = 50)
            assertEquals(1, afterEditBatch.ops.size)
            assertEquals("dev-a", afterEditBatch.ops.single().memory?.updatedByDeviceId)
            assertEquals(editedOnSlowClock.updatedAt, afterEditBatch.ops.single().memory?.updatedAt)
        } finally {
            store.close()
        }
    }

    @Test
    fun propagatesEntityMergeRedirectsAndArchivesExactUnconfirmedDuplicates() = runTest {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val first = store.save(
                MemoryDraft(
                    kind = MemoryKind.EPISODIC,
                    content = "Went cycling with Xiaoming",
                    entities = listOf(MemoryEntityRef("Xiaoming", EntityKind.PERSON)),
                ),
            )
            val pinnedDup = store.save(
                MemoryDraft(
                    kind = MemoryKind.HABIT,
                    content = "prefers dark mode",
                    confidence = MemoryConfidence.INFERRED,
                    pinned = true,
                ),
            )
            store.save(
                MemoryDraft(
                    kind = MemoryKind.HABIT,
                    content = "  Prefers dark   mode  ",
                    confidence = MemoryConfidence.OBSERVED,
                ),
            )
            val confirmedDup = store.save(
                MemoryDraft(
                    kind = MemoryKind.HABIT,
                    content = "prefers dark mode",
                    confidence = MemoryConfidence.CONFIRMED,
                ),
            )
            val extraUnconfirmed = store.save(
                MemoryDraft(
                    kind = MemoryKind.HABIT,
                    content = "PREFERS DARK MODE",
                    confidence = MemoryConfidence.INFERRED,
                ),
            )

            val (_, archived) = store.runDeterministicCleanup()
            assertEquals(2, archived)
            assertEquals(MemoryItemStatus.ARCHIVED, store.get(extraUnconfirmed.id)!!.status)
            assertEquals(MemoryItemStatus.ACTIVE, store.get(pinnedDup.id)!!.status)
            assertEquals(MemoryItemStatus.ACTIVE, store.get(confirmedDup.id)!!.status)
            assertEquals(2, store.list(MemoryFilter(kinds = setOf(MemoryKind.HABIT), includeHistory = false)).size)

            val accountKey = "acct|relay|dev-b"
            store.applyRemotePullBatch(
                accountKey = accountKey,
                selfDeviceId = "dev-b",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 1L,
                        kind = MemorySyncOpKind.ENTITY,
                        deviceId = "dev-a",
                        entity = EntitySyncRecord(
                            canonicalKey = "xiaoming",
                            id = "ent-remote-old",
                            canonicalName = "Xiaoming",
                            kind = EntityKind.PERSON,
                            aliases = listOf("Ming"),
                            mergedInto = "Zhang Ming",
                            updatedAt = nowMs + 10_000L,
                            deletedAt = nowMs + 10_000L,
                            revision = 2L,
                            updatedByDeviceId = "dev-a",
                        ),
                    ),
                ),
                nextSince = 1L,
                serverSeq = 1L,
                remoteLastDreamAt = null,
            )

            val updatedMemory = store.get(first.id)!!
            assertTrue("Zhang Ming" in updatedMemory.entities)
            val hits = store.search(listOf("Ming"))
            assertTrue(hits.any { it.record.id == first.id })
        } finally {
            store.close()
        }
    }

    @Test
    fun clampsOversizedFieldsOnWriteAndLegacyTombstoneExport() = runTest {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val oversizedContent = "x".repeat(10_000)
            val oversizedSummary = "s".repeat(3_000)
            val oversizedQuote = "q".repeat(2_000)
            val manyTags = (1..25).map { idx -> "tag-${idx}-${"t".repeat(70)}" }
            val manyEntities = (1..25).map { idx -> MemoryEntityRef("entity-${idx}-${"e".repeat(140)}") }

            val saved = store.save(
                MemoryDraft(
                    kind = MemoryKind.IDENTITY,
                    content = oversizedContent,
                    summary = oversizedSummary,
                    sourceQuote = oversizedQuote,
                    tags = manyTags,
                    entities = manyEntities,
                ),
            )
            assertEquals(MemoryLimits.MAX_CONTENT_CHARS, saved.content.length)
            assertEquals(MemoryLimits.MAX_SUMMARY_CHARS, saved.summary!!.length)
            assertEquals(MemoryLimits.MAX_QUOTE_CHARS, saved.sourceQuote!!.length)
            assertEquals(MemoryLimits.MAX_TAGS_PER_MEMORY, saved.tags.size)
            assertTrue(saved.tags.all { it.length <= MemoryLimits.MAX_TAG_CHARS })
            assertEquals(MemoryLimits.MAX_ENTITIES_PER_MEMORY, saved.entities.size)
            assertTrue(saved.entities.all { it.length <= MemoryLimits.MAX_ENTITY_NAME_CHARS })
            store.close()

            val db = BundledSQLiteDriver().open(File(rootDir, "memory.db").absolutePath)
            try {
                db.execSQL(
                    """
                    INSERT INTO memories(
                        id, kind, content, summary, tags_json, confidence, importance, pinned,
                        valid_from, status, source_kind, created_at, updated_at, deleted_at, revision
                    ) VALUES (
                        'legacy-huge', 'ACTIVE', '${"z".repeat(9_500)}', '${"y".repeat(2_500)}', '[]',
                        'CONFIRMED', 4, 0, $nowMs, 'ACTIVE', 'USER_MANUAL', $nowMs, $nowMs, $nowMs, 1
                    )
                    """.trimIndent(),
                )
            } finally {
                db.close()
            }

            val reopened = MemoryStore(rootDir, clock = { nowMs })
            try {
                val exported = ArrayList<MemorySyncOpItem>()
                val accountKey = "acct|https://relay.test|dev-a"
                while (true) {
                    val batch = reopened.collectPendingPushBatch(accountKey, "dev-a", limit = 50)
                    if (batch.ops.isEmpty()) {
                        reopened.markPushAcked(accountKey, batch, 1L, null)
                        break
                    }
                    exported += batch.ops
                    reopened.markPushAcked(accountKey, batch, 1L, null)
                }
                val legacyExport = exported.firstOrNull { it.memory?.id == "legacy-huge" }?.memory
                assertNotNull(legacyExport)
                assertEquals(MemoryLimits.MAX_CONTENT_CHARS, legacyExport!!.content.length)
                assertEquals(MemoryLimits.MAX_SUMMARY_CHARS, legacyExport.summary!!.length)
            } finally {
                reopened.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun placeholderEntityYieldsToOlderRemoteEntityAndPrunesSyncLogs() = runTest {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val accountKey = "acct|https://relay.test|dev-b"
            store.applyRemotePullBatch(
                accountKey = accountKey,
                selfDeviceId = "dev-b",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 1L,
                        kind = MemorySyncOpKind.MEMORY,
                        deviceId = "dev-a",
                        memory = MemorySyncRecord(
                            id = "mem-bob",
                            kind = MemoryKind.IDENTITY,
                            content = "Works with Bob",
                            entities = listOf("Bob"),
                            confidence = MemoryConfidence.CONFIRMED,
                            importance = 4,
                            pinned = false,
                            validFrom = nowMs,
                            status = MemoryItemStatus.ACTIVE,
                            sourceKind = MemorySourceKind.USER_MANUAL,
                            createdAt = nowMs,
                            updatedAt = nowMs,
                            revision = 1L,
                            updatedByDeviceId = "dev-a",
                        ),
                    ),
                ),
                nextSince = 1L,
                serverSeq = 1L,
                remoteLastDreamAt = null,
            )

            // Placeholder should not be exported as a real local baseline entity.
            val baselineBeforeRealEntity = store.collectPendingPushBatch(accountKey, "dev-b", limit = 50)
            assertTrue(baselineBeforeRealEntity.ops.isEmpty())
            store.markPushAcked(accountKey, baselineBeforeRealEntity, 1L, null)

            // Real remote entity with an OLDER timestamp must overwrite the placeholder's kind & summary.
            store.applyRemotePullBatch(
                accountKey = accountKey,
                selfDeviceId = "dev-b",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 2L,
                        kind = MemorySyncOpKind.ENTITY,
                        deviceId = "dev-a",
                        entity = EntitySyncRecord(
                            canonicalKey = "bob",
                            id = "ent-bob-real",
                            canonicalName = "Bob",
                            kind = EntityKind.PERSON,
                            aliases = listOf("Bobby"),
                            summary = "Team lead",
                            updatedAt = nowMs - 100_000L,
                            revision = 1L,
                            updatedByDeviceId = "dev-a",
                        ),
                    ),
                ),
                nextSince = 2L,
                serverSeq = 2L,
                remoteLastDreamAt = null,
            )
            val entity = store.listEntities().single { it.canonicalName == "Bob" }
            assertEquals(EntityKind.PERSON, entity.kind)
            assertEquals("Team lead", entity.summary)
            assertTrue("Bobby" in entity.aliases)

            // Unbounded logs: create > 240 ops and > 110 dream runs, then prune cleanly.
            repeat(240) { idx ->
                store.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "Note $idx"))
            }
            val inc = store.collectPendingPushBatch(accountKey, "dev-b", limit = 100)
            store.markPushAcked(accountKey, inc, 10L, null)
            store.runDeterministicCleanup()
            store.close()

            val db = BundledSQLiteDriver().open(File(rootDir, "memory.db").absolutePath)
            try {
                val opsCount = db.prepare("SELECT COUNT(*) FROM sync_ops").use { stmt ->
                    stmt.step()
                    stmt.getInt(0)
                }
                assertTrue(opsCount in 200..240)
            } finally {
                db.close()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun accountSwitchPromptAndSameAccountReEnrollPreserveConsistency() = runTest {
        val store = MemoryStore(rootDir, clock = { nowMs })
        try {
            val localItem = store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "Local note"))
            val acct1Key = "acct-1|https://relay-1.test|dev-1"
            assertEquals(MemoryAccountSwitchCheck.Ready, store.inspectAccountSwitch("acct-1|https://relay-1.test"))

            // Finish initial sync & pull one remote item from peer dev-2
            while (true) {
                val b = store.collectPendingPushBatch(acct1Key, "dev-1", limit = 50)
                store.markPushAcked(acct1Key, b, 1L, null)
                if (b.ops.isEmpty()) break
            }
            store.applyRemotePullBatch(
                accountKey = acct1Key,
                selfDeviceId = "dev-1",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 2L,
                        kind = MemorySyncOpKind.MEMORY,
                        deviceId = "dev-2",
                        memory = MemorySyncRecord(
                            id = "peer-1",
                            kind = MemoryKind.HABIT,
                            content = "Peer note",
                            confidence = MemoryConfidence.CONFIRMED,
                            importance = 4,
                            pinned = false,
                            validFrom = nowMs,
                            status = MemoryItemStatus.ACTIVE,
                            sourceKind = MemorySourceKind.USER_MANUAL,
                            createdAt = nowMs,
                            updatedAt = nowMs,
                            revision = 1L,
                            updatedByDeviceId = "dev-2",
                        ),
                    ),
                ),
                nextSince = 2L,
                serverSeq = 2L,
                remoteLastDreamAt = null,
            )

            // Re-enrolling same account with a new deviceId stays Ready and does not re-trigger overlap dream.
            assertEquals(MemoryAccountSwitchCheck.Ready, store.inspectAccountSwitch("acct-1|https://relay-1.test"))
            val sameAcctNewDevKey = "acct-1|https://relay-1.test|dev-1-re"
            val reEnrollPull = store.applyRemotePullBatch(
                accountKey = sameAcctNewDevKey,
                selfDeviceId = "dev-1-re",
                entries = listOf(
                    MemoryRemotePullEntry(
                        seq = 1L,
                        kind = MemorySyncOpKind.MEMORY,
                        deviceId = "dev-2",
                        memory = MemorySyncRecord(
                            id = "peer-1",
                            kind = MemoryKind.HABIT,
                            content = "Peer note",
                            confidence = MemoryConfidence.CONFIRMED,
                            importance = 4,
                            pinned = false,
                            validFrom = nowMs,
                            status = MemoryItemStatus.ACTIVE,
                            sourceKind = MemorySourceKind.USER_MANUAL,
                            createdAt = nowMs,
                            updatedAt = nowMs,
                            revision = 1L,
                            updatedByDeviceId = "dev-2",
                        ),
                    ),
                ),
                nextSince = 2L,
                serverSeq = 2L,
                remoteLastDreamAt = null,
            )
            assertFalse(reEnrollPull.hadLocalAndRemoteOverlap)

            // Switching to a different account requires a decision when local memories exist.
            val check = store.inspectAccountSwitch("acct-2|https://relay-2.test")
            assertEquals(MemoryAccountSwitchCheck.NeedsDecision(2), check)

            // MERGE_ALL exports both local-created and previously peer-synced memories.
            val acct2Key = "acct-2|https://relay-2.test|dev-x"
            store.resolveAccountSwitch(acct2Key, "acct-2|https://relay-2.test", MemoryAccountSwitchMode.MERGE_ALL)
            val mergedIds = HashSet<String>()
            while (true) {
                val b = store.collectPendingPushBatch(acct2Key, "dev-x", limit = 50)
                b.ops.forEach { op -> op.memory?.id?.let { mergedIds += it } }
                store.markPushAcked(acct2Key, b, 1L, null)
                if (b.ops.isEmpty()) break
            }
            assertTrue(localItem.id in mergedIds)
            assertTrue("peer-1" in mergedIds)

            // Switching to acct-3 with REPLACE_WITH_REMOTE clears local memories cleanly.
            val check3 = store.inspectAccountSwitch("acct-3|https://relay-3.test")
            assertEquals(MemoryAccountSwitchCheck.NeedsDecision(2), check3)
            store.resolveAccountSwitch(
                "acct-3|https://relay-3.test|dev-y",
                "acct-3|https://relay-3.test",
                MemoryAccountSwitchMode.REPLACE_WITH_REMOTE,
            )
            assertTrue(store.list().isEmpty())
        } finally {
            store.close()
        }
    }
}

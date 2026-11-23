package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.memory.DreamLeaseResult
import io.github.whywood.superpi.runtime.memory.DreamOutcome
import io.github.whywood.superpi.runtime.memory.EntitySyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.runtime.memory.MemoryCompletionGateway
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResponse
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResult
import io.github.whywood.superpi.runtime.memory.MemoryDreamer
import io.github.whywood.superpi.runtime.memory.MemoryModelTier
import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import io.github.whywood.superpi.runtime.memory.MemorySyncOpKind
import io.github.whywood.superpi.runtime.memory.MemorySyncRecord
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RelayMemorySyncTest {
    private val tempDirs = ArrayList<File>()
    private val storesToClose = ArrayList<MemoryStore>()

    @Before
    fun setUp() {
        tempDirs.clear()
        storesToClose.clear()
    }

    @After
    fun tearDown() = runTest {
        storesToClose.forEach { it.close() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun twoMaturePhonesMergeToLosslessUnionAndConsolidateOnceUnderDreamLease() = runTest {
        var nowMs = 1_760_000_000_000L
        val server = FakeRelayMemoryServer(clock = { nowMs })
        val storeA = openStore(clock = { nowMs })
        val storeB = openStore(clock = { nowMs })

        storeA.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "Prefers morning americano",
                confidence = MemoryConfidence.OBSERVED,
                entities = listOf(MemoryEntityRef("Alice")),
            ),
        )
        storeA.save(MemoryDraft(kind = MemoryKind.ACTIVE, content = "Submit quarterly report Friday"))

        storeB.save(
            MemoryDraft(
                kind = MemoryKind.IDENTITY,
                content = "  prefers morning americano ",
                confidence = MemoryConfidence.INFERRED,
            ),
        )
        storeB.save(MemoryDraft(kind = MemoryKind.HABIT, content = "Put conclusion first in responses"))

        val accountA = testAccount(deviceId = "dev-a")
        val accountB = testAccount(deviceId = "dev-b")
        val accountsA = FakeRelayAccountStore(accountA, "tok-a")
        val accountsB = FakeRelayAccountStore(accountB, "tok-b")

        var dreamCallsA = 0
        var dreamCallsB = 0
        var dreamCoordinatorA: RelayMemorySync? = null
        var dreamCoordinatorB: RelayMemorySync? = null

        val dreamerA = MemoryDreamer(
            store = storeA,
            completionGateway = okDreamGateway { dreamCallsA++; """{"promotions":[]}""" },
            scope = this,
            clock = { nowMs },
            coordinator = { dreamCoordinatorA },
        )
        val dreamerB = MemoryDreamer(
            store = storeB,
            completionGateway = okDreamGateway { dreamCallsB++; """{"promotions":[]}""" },
            scope = this,
            clock = { nowMs },
            coordinator = { dreamCoordinatorB },
        )

        val syncA = RelayMemorySync(
            store = storeA,
            accounts = accountsA,
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        val syncB = RelayMemorySync(
            store = storeB,
            accounts = accountsB,
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        dreamCoordinatorA = syncA
        dreamCoordinatorB = syncB

        assertEquals(SyncRunStatus.Synced, syncA.syncNow())
        assertEquals(SyncRunStatus.Synced, syncB.syncNow())
        assertEquals(SyncRunStatus.Synced, syncA.syncNow())

        val contentsA = storeA.list(MemoryFilter(includeHistory = false)).map { it.content }
        val contentsB = storeB.list(MemoryFilter(includeHistory = false)).map { it.content }
        assertTrue("Submit quarterly report Friday" in contentsB)
        assertTrue("Put conclusion first in responses" in contentsA)

        assertTrue(dreamerB.runFromWorker() is DreamOutcome.Done)
        assertEquals(1, dreamCallsB)

        nowMs += 300_000L
        assertEquals(DreamOutcome.Skipped("not_due"), dreamerA.runFromWorker())
        assertEquals(0, dreamCallsA)

        syncA.syncNow()
        val activeAfterDedupA = storeA.list(MemoryFilter(kinds = setOf(MemoryKind.IDENTITY), includeHistory = false))
        assertEquals(1, activeAfterDedupA.size)
        assertEquals("Prefers morning americano", activeAfterDedupA.single().content)
    }

    @Test
    fun offlinePhoneFiveMinutesBeforeDreamSkipsAndDoesNotDuplicateAfterReconnect() = runTest {
        var nowMs = 1_760_000_000_000L
        val server = FakeRelayMemoryServer(clock = { nowMs })
        val storeA = openStore(clock = { nowMs })
        val storeB = openStore(clock = { nowMs })

        storeA.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "Planned trip to Kyoto"))
        storeB.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "Booked return flight"))

        val syncA = RelayMemorySync(
            store = storeA,
            accounts = FakeRelayAccountStore(testAccount("dev-a"), "tok-a"),
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        val syncB = RelayMemorySync(
            store = storeB,
            accounts = FakeRelayAccountStore(testAccount("dev-b"), "tok-b"),
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        syncA.syncNow()
        syncB.syncNow()

        var dreamCallsA = 0
        var dreamCallsB = 0
        val dreamerA = MemoryDreamer(
            store = storeA,
            completionGateway = okDreamGateway {
                dreamCallsA++
                """{"promotions":[{"kind":"ACTIVE","content":"Preparing Kyoto trip","tags":["travel"],"entities":[],"confidence":"OBSERVED","importance":4}]}"""
            },
            scope = this,
            clock = { nowMs },
            coordinator = { syncA },
        )
        val dreamerB = MemoryDreamer(
            store = storeB,
            completionGateway = okDreamGateway {
                dreamCallsB++
                """{"promotions":[{"kind":"ACTIVE","content":"Duplicate Kyoto note","tags":["travel"],"entities":[],"confidence":"INFERRED","importance":3}]}"""
            },
            scope = this,
            clock = { nowMs },
            coordinator = { syncB },
        )

        server.offlineDevices += "dev-b"
        assertEquals(DreamOutcome.Skipped("sync_offline"), dreamerB.runFromWorker())
        assertEquals(0, dreamCallsB)

        val outcomeA = dreamerA.runFromWorker()
        assertTrue(outcomeA is DreamOutcome.Done)
        assertEquals(1, dreamCallsA)

        nowMs += 300_000L
        server.offlineDevices.remove("dev-b")
        assertEquals(DreamOutcome.Skipped("not_due"), dreamerB.runFromWorker())
        assertEquals(0, dreamCallsB)

        val contentsB = storeB.list(MemoryFilter(includeHistory = false)).map { it.content }
        assertTrue("Preparing Kyoto trip" in contentsB)
        assertFalse("Duplicate Kyoto note" in contentsB)
    }

    @Test
    fun fallsBackCleanlyOnLegacyRelayAndReplaysOfflineMutationsOnReconnect() = runTest {
        var nowMs = 1_760_000_000_000L
        val server = FakeRelayMemoryServer(clock = { nowMs })
        val store = openStore(clock = { nowMs })
        store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "Local baseline"))

        val legacySync = RelayMemorySync(
            store = store,
            accounts = FakeRelayAccountStore(testAccount("dev-a"), "tok-a"),
            client = object : RelayMemoryApi {
                override suspend fun push(
                    account: RelayAccount,
                    token: String,
                    ops: List<MemorySyncOpItem>,
                    dreamCompletedAt: Long?,
                ): RelayMemoryPushResponse = throw RelayMemoryException(RelayMemoryFailure.NotFound)

                override suspend fun pull(
                    account: RelayAccount,
                    token: String,
                    since: Long,
                    limit: Int,
                ): RelayMemoryPullResponse = throw RelayMemoryException(RelayMemoryFailure.NotFound)
            },
            scope = this,
            clock = { nowMs },
        )
        assertEquals(SyncRunStatus.Unsupported, legacySync.syncNow())
        assertFalse(legacySync.state.value.supported)
        assertTrue(legacySync.prepareForDream(forceManual = false, minIntervalMs = 3_600_000L) is DreamLeaseResult.Acquired)

        val sync = RelayMemorySync(
            store = store,
            accounts = FakeRelayAccountStore(testAccount("dev-b"), "tok-b"),
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        server.offlineDevices += "dev-b"
        assertEquals(SyncRunStatus.Offline, sync.syncNow())

        nowMs += 5_000L
        store.save(MemoryDraft(kind = MemoryKind.ACTIVE, content = "Added offline"))
        server.offlineDevices.remove("dev-b")
        assertEquals(SyncRunStatus.Synced, sync.syncNow())
        assertTrue(sync.state.value.serverSeq >= 2L)
        assertTrue(server.items.values.any { it.content == "Added offline" })
    }

    @Test
    fun splitsOversizedPushBatchesByByteBudgetAndSkipsServerRejectedOps() = runTest {
        var nowMs = 1_760_000_000_000L
        val store = openStore(clock = { nowMs })
        val chunkSizes = ArrayList<Int>()
        val chunkCounts = ArrayList<Int>()
        val server = FakeRelayMemoryServer(clock = { nowMs })

        // Write 100 entries x 4,500 Chinese chars (~13.5 KB UTF-8 each -> ~1.35 MiB for 100-op batch) -> must split.
        val bodyText = "测".repeat(4_500)
        repeat(100) { idx ->
            store.save(MemoryDraft(kind = MemoryKind.EPISODIC, content = "$idx-$bodyText"))
        }

        val sync = RelayMemorySync(
            store = store,
            accounts = FakeRelayAccountStore(testAccount("dev-a"), "tok-a"),
            client = object : RelayMemoryApi {
                override suspend fun push(
                    account: RelayAccount,
                    token: String,
                    ops: List<MemorySyncOpItem>,
                    dreamCompletedAt: Long?,
                ): RelayMemoryPushResponse {
                    val bytes = RelayProtocol.memoryPushRequest(ops, dreamCompletedAt).toByteArray(Charsets.UTF_8).size
                    chunkSizes += bytes
                    chunkCounts += ops.size
                    val baseResp = server.api.push(account, token, ops, dreamCompletedAt)
                    return if (ops.isNotEmpty()) {
                        baseResp.copy(
                            rejectedCount = 1,
                            rejected = listOf(RelayMemoryRejectedOp(ops.first().opId, "simulated_reject")),
                        )
                    } else {
                        baseResp
                    }
                }

                override suspend fun pull(
                    account: RelayAccount,
                    token: String,
                    since: Long,
                    limit: Int,
                ): RelayMemoryPullResponse = server.api.pull(account, token, since, limit)
            },
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )

        assertEquals(SyncRunStatus.Synced, sync.syncNow())
        assertEquals(100, chunkCounts.sum())
        assertTrue(chunkSizes.size >= 2)
        assertTrue(chunkSizes.all { it <= 1_024 * 1_024 })
    }

    @Test
    fun releasesDreamLeaseAndResetsSyncingWhenCancelledAndClassifiesErrors() = runTest {
        val nowMs = 1_760_000_000_000L
        val server = FakeRelayMemoryServer(clock = { nowMs })
        val store = openStore(clock = { nowMs })
        store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "Has memory"))

        val sync = RelayMemorySync(
            store = store,
            accounts = FakeRelayAccountStore(testAccount("dev-a"), "tok-a"),
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )

        val enteredDream = CompletableDeferred<Unit>()
        val holdDream = CompletableDeferred<Unit>()
        val dreamer = MemoryDreamer(
            store = store,
            completionGateway = object : MemoryCompletionGateway {
                override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                    enteredDream.complete(Unit)
                    holdDream.await()
                    return MemoryCompletionResult.Paused
                }
            },
            scope = this,
            clock = { nowMs },
            coordinator = { sync },
        )

        val dreamJob = launch { dreamer.runNow() }
        enteredDream.await()
        assertEquals("dev-a", server.leaseHolder)
        dreamJob.cancel()
        dreamJob.join()
        assertEquals(null, server.leaseHolder)

        // Error classification & syncing flag reset on cancellation
        val pullStarted = CompletableDeferred<Unit>()
        val pullGate = CompletableDeferred<Unit>()
        var failMode: RelayMemoryFailure? = null
        val customSync = RelayMemorySync(
            store = store,
            accounts = FakeRelayAccountStore(testAccount("dev-err"), "tok-e"),
            client = object : RelayMemoryApi {
                override suspend fun push(
                    account: RelayAccount,
                    token: String,
                    ops: List<MemorySyncOpItem>,
                    dreamCompletedAt: Long?,
                ): RelayMemoryPushResponse = throw RelayMemoryException(failMode ?: RelayMemoryFailure.Unreachable)

                override suspend fun pull(
                    account: RelayAccount,
                    token: String,
                    since: Long,
                    limit: Int,
                ): RelayMemoryPullResponse {
                    failMode?.let { throw RelayMemoryException(it) }
                    pullStarted.complete(Unit)
                    pullGate.await()
                    return RelayMemoryPullResponse(0L, false, 0L, null, emptyList())
                }
            },
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )

        val syncJob = launch { customSync.syncNow() }
        pullStarted.await()
        assertTrue(customSync.state.value.syncing)
        syncJob.cancel()
        syncJob.join()
        assertFalse(customSync.state.value.syncing)

        failMode = RelayMemoryFailure.Unauthorized
        assertEquals(SyncRunStatus.Unauthorized, customSync.syncNow())
        assertEquals(RelayMemorySyncErrorKind.Unauthorized, customSync.state.value.errorKind)
        assertEquals(DreamLeaseResult.Unauthorized, customSync.prepareForDream(forceManual = true, minIntervalMs = 0L))

        failMode = RelayMemoryFailure.Rejected
        assertEquals(SyncRunStatus.Rejected, customSync.syncNow())
        assertEquals(RelayMemorySyncErrorKind.Rejected, customSync.state.value.errorKind)
        assertEquals(DreamLeaseResult.Rejected, customSync.prepareForDream(forceManual = true, minIntervalMs = 0L))
    }

    @Test
    fun accountSwitchGatedUntilUserResolvesMergeOrClear() = runTest {
        val nowMs = 1_760_000_000_000L
        val server = FakeRelayMemoryServer(clock = { nowMs })
        val store = openStore(clock = { nowMs })
        store.save(MemoryDraft(kind = MemoryKind.IDENTITY, content = "Local pre-switch memory"))

        val accounts = FakeRelayAccountStore(testAccount("dev-a"), "tok-a")
        val sync = RelayMemorySync(
            store = store,
            accounts = accounts,
            client = server.api,
            scope = this,
            relayCapabilities = { listOf("memory-v2") },
            clock = { nowMs },
        )
        assertEquals(SyncRunStatus.Synced, sync.syncNow())

        // Switch to another relay account
        accounts.save(
            RelayAccount(
                origin = "https://other.relay:8443",
                fingerprint = "fp-2",
                caPem = null,
                deviceId = "dev-x",
                deviceName = "dev-x",
                accountId = "acct-other",
                enrolledAt = 2_000L,
            ),
            "tok-other",
        )
        assertEquals(SyncRunStatus.PendingAccountSwitch, sync.syncNow())
        assertEquals(1, sync.state.value.pendingAccountSwitch?.localMemoryCount)
        assertEquals(DreamLeaseResult.Blocked("account_switch_pending"), sync.prepareForDream(forceManual = true, minIntervalMs = 0L))

        assertEquals(SyncRunStatus.Synced, sync.resolveAccountSwitch(MemoryAccountSwitchMode.MERGE_ALL))
        assertEquals(null, sync.state.value.pendingAccountSwitch)
        assertTrue(server.items.values.any { it.content == "Local pre-switch memory" })
    }

    private fun openStore(clock: () -> Long): MemoryStore {
        val dir = Files.createTempDirectory("mem-relay-sync-test").toFile()
        tempDirs += dir
        return MemoryStore(dir, clock = clock).also { storesToClose += it }
    }

    private fun testAccount(deviceId: String) = RelayAccount(
        origin = "https://relay.test:8443",
        fingerprint = "fp-1",
        caPem = null,
        deviceId = deviceId,
        deviceName = deviceId,
        accountId = "acct-main",
        enrolledAt = 1_000L,
    )

    private fun okDreamGateway(onCall: () -> String) = object : MemoryCompletionGateway {
        override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult =
            MemoryCompletionResult.Success(MemoryCompletionResponse("mock/dream", onCall()))
    }

    private class FakeRelayAccountStore(
        initial: RelayAccount?,
        private val tokenValue: String?,
    ) : RelayAccountStore {
        private val flow = MutableStateFlow(initial)
        override val account: Flow<RelayAccount?> = flow
        override suspend fun read(): RelayAccount? = flow.value
        override suspend fun token(): String? = tokenValue
        override suspend fun save(account: RelayAccount, token: String) {
            flow.value = account
        }
        override suspend fun clear() {
            flow.value = null
        }
    }

    private class FakeRelayMemoryServer(private val clock: () -> Long) {
        val items = LinkedHashMap<String, MemorySyncRecord>()
        val entities = LinkedHashMap<String, EntitySyncRecord>()
        val opsLog = ArrayList<MemoryRemotePullEntry>()
        val seenOps = HashSet<String>()
        val offlineDevices = HashSet<String>()
        var serverSeq = 0L
        var lastDreamAt: Long? = null
        var leaseHolder: String? = null
        var leaseId: String? = null
        var leaseExpiresAt: Long = 0L

        val api: RelayMemoryApi = object : RelayMemoryApi {
            override suspend fun push(
                account: RelayAccount,
                token: String,
                ops: List<MemorySyncOpItem>,
                dreamCompletedAt: Long?,
            ): RelayMemoryPushResponse {
                if (account.deviceId in offlineDevices) throw RelayMemoryException(RelayMemoryFailure.Unreachable)
                var applied = 0
                var skipped = 0
                for (op in ops) {
                    if (!seenOps.add("${account.deviceId}:${op.opId}")) {
                        skipped++
                        continue
                    }
                    when (op.kind) {
                        MemorySyncOpKind.MEMORY -> {
                            val mem = op.memory!!
                            val prev = items[mem.id]
                            if (prev == null ||
                                mem.updatedAt > prev.updatedAt ||
                                (mem.updatedAt == prev.updatedAt && account.deviceId > prev.updatedByDeviceId.orEmpty())
                            ) {
                                serverSeq++
                                val stored = mem.copy(updatedByDeviceId = account.deviceId)
                                items[mem.id] = stored
                                opsLog.removeAll { it.kind == MemorySyncOpKind.MEMORY && it.memory?.id == mem.id }
                                opsLog += MemoryRemotePullEntry(serverSeq, MemorySyncOpKind.MEMORY, account.deviceId, memory = stored)
                                applied++
                            } else {
                                skipped++
                            }
                        }
                        MemorySyncOpKind.ENTITY -> {
                            val ent = op.entity!!
                            serverSeq++
                            val stored = ent.copy(updatedByDeviceId = account.deviceId)
                            entities[ent.canonicalKey] = stored
                            opsLog.removeAll { it.kind == MemorySyncOpKind.ENTITY && it.entity?.canonicalKey == ent.canonicalKey }
                            opsLog += MemoryRemotePullEntry(serverSeq, MemorySyncOpKind.ENTITY, account.deviceId, entity = stored)
                            applied++
                        }
                    }
                }
                if (dreamCompletedAt != null && dreamCompletedAt > 0L) {
                    val bounded = minOf(dreamCompletedAt, clock())
                    if (lastDreamAt == null || bounded > lastDreamAt!!) {
                        lastDreamAt = bounded
                    }
                }
                return RelayMemoryPushResponse(serverSeq, applied, skipped, lastDreamAt)
            }

            override suspend fun pull(
                account: RelayAccount,
                token: String,
                since: Long,
                limit: Int,
            ): RelayMemoryPullResponse {
                if (account.deviceId in offlineDevices) throw RelayMemoryException(RelayMemoryFailure.Unreachable)
                val matched = opsLog.filter { it.seq > since }.sortedBy { it.seq }
                val page = matched.take(limit)
                return RelayMemoryPullResponse(
                    serverSeq = serverSeq,
                    hasMore = matched.size > page.size,
                    nextSince = page.lastOrNull()?.seq ?: since,
                    lastDreamAt = lastDreamAt,
                    entries = page,
                )
            }

            override suspend fun acquireDreamLease(
                account: RelayAccount,
                token: String,
                ttlMs: Long,
                minIntervalMs: Long,
            ): RelayMemoryDreamLease {
                if (account.deviceId in offlineDevices) throw RelayMemoryException(RelayMemoryFailure.Unreachable)
                val now = clock()
                if (minIntervalMs > 0L && lastDreamAt != null && now - lastDreamAt!! < minIntervalMs) {
                    return RelayMemoryDreamLease(
                        acquired = false,
                        expiresAt = lastDreamAt!! + minIntervalMs,
                        lastDreamAt = lastDreamAt,
                        serverSeq = serverSeq,
                    )
                }
                if (leaseHolder != null && leaseExpiresAt > now && leaseHolder != account.deviceId) {
                    return RelayMemoryDreamLease(
                        acquired = false,
                        holderDeviceId = leaseHolder,
                        expiresAt = leaseExpiresAt,
                        lastDreamAt = lastDreamAt,
                        serverSeq = serverSeq,
                    )
                }
                leaseHolder = account.deviceId
                leaseId = "lease-${account.deviceId}-$now"
                leaseExpiresAt = now + ttlMs
                return RelayMemoryDreamLease(
                    acquired = true,
                    leaseId = leaseId,
                    expiresAt = leaseExpiresAt,
                    lastDreamAt = lastDreamAt,
                    serverSeq = serverSeq,
                )
            }

            override suspend fun releaseDreamLease(account: RelayAccount, token: String, leaseId: String?): Boolean {
                if (leaseHolder == account.deviceId) {
                    leaseHolder = null
                    return true
                }
                return false
            }
        }
    }
}

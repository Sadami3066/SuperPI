package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlockDraft
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryBlockOwner
import io.github.whywood.superpi.runtime.MemoryBlockStatus
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.runtime.MemoryEventTimePrecision
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryProposalStatus
import io.github.whywood.superpi.runtime.MemorySensitivity
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.memory.ConsolidationLeaseResult
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.runtime.memory.MemoryBlockSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryEntitySyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryEventEntityLink
import io.github.whywood.superpi.runtime.memory.MemoryEventSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryProposalSyncRecord
import io.github.whywood.superpi.runtime.memory.MemoryRemotePullEntry
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.MemorySyncKind
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import io.github.whywood.superpi.runtime.memory.MemoryRelationSyncRecord
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayMemorySyncV3Test {
    private lateinit var rootDir: File
    private var nowMs = 1_760_100_000_000L

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("relay-memory-sync-v3").toFile()
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun clientEncodesPushAndDecodesPushPullAndLeaseResponses() {
        val client = RelayMemoryClient(tokenProvider = { "tok" })

        val block = MemoryBlockSyncRecord(
            id = "builtin_self_profile",
            slug = "self_profile",
            title = "About Me",
            purpose = "Bio",
            schemaHint = "bullets",
            content = "Software engineer",
            domain = MemoryDomains.PERSONAL,
            sensitivity = MemorySensitivity.NORMAL,
            injectionMode = MemoryBlockInjectionMode.ALWAYS,
            maxChars = 2000,
            owner = MemoryBlockOwner.BUILTIN,
            pinned = true,
            status = MemoryBlockStatus.ACTIVE,
            sortOrder = 10,
            createdAt = 100L,
            updatedAt = 200L,
            deletedAt = null,
            revision = 2L,
            updatedByDeviceId = "dev-1",
        )
        val entity = MemoryEntitySyncRecord(
            id = "ent-1",
            canonicalName = "Alice",
            entityType = "PERSON",
            aliases = listOf("Ali"),
            attributesJson = """{"role":"friend"}""",
            summary = "Close friend",
            domain = MemoryDomains.PERSONAL,
            sensitivity = MemorySensitivity.NORMAL,
            pinned = false,
            createdAt = 100L,
            updatedAt = 210L,
            deletedAt = null,
            revision = 1L,
            mergedInto = null,
            updatedByDeviceId = "dev-1",
        )
        val event = MemoryEventSyncRecord(
            id = "ev-1",
            eventKind = MemoryEventKind.PREFERENCE,
            content = "Drinks espresso",
            summary = "Coffee preference",
            qualifiersJson = """{"time":"morning"}""",
            semanticKeywords = listOf("coffee", "espresso"),
            tags = listOf("drink"),
            domain = MemoryDomains.PERSONAL,
            sensitivity = MemorySensitivity.NORMAL,
            epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
            importance = 4,
            pinned = false,
            eventTime = 205L,
            eventTimePrecision = MemoryEventTimePrecision.DAY,
            validFrom = 205L,
            validUntil = null,
            status = MemoryItemStatus.ACTIVE,
            supersededBy = null,
            sourceKind = MemorySourceKind.USER_MANUAL,
            sourceSessionId = "s-1",
            sourceEntryId = "e-1",
            sourceQuote = "I drink espresso",
            entityLinks = listOf(MemoryEventEntityLink("ent-1", "SUBJECT")),
            createdAt = 205L,
            updatedAt = 220L,
            deletedAt = null,
            revision = 1L,
            updatedByDeviceId = "dev-1",
        )
        val relation = MemoryRelationSyncRecord(
            id = "rel-1",
            fromEntityId = "ent-1",
            relationType = "knows",
            toEntityId = "ent-2",
            label = "Knows",
            attributesJson = "{}",
            validFrom = 200L,
            validUntil = null,
            invalidatedAt = null,
            invalidatedBy = null,
            epistemicTier = MemoryEpistemicTier.OBSERVED,
            domain = MemoryDomains.PERSONAL,
            sensitivity = MemorySensitivity.NORMAL,
            sourceEventId = "ev-1",
            createdAt = 200L,
            updatedAt = 230L,
            deletedAt = null,
            revision = 1L,
            updatedByDeviceId = "dev-1",
        )
        val proposal = MemoryProposalSyncRecord(
            id = "prop-1",
            slug = "coffee_block",
            title = "Coffee",
            purpose = "Coffee notes",
            domain = MemoryDomains.LIFE,
            sensitivity = MemorySensitivity.NORMAL,
            seedContent = "Espresso",
            supportingEventIds = listOf("ev-1", "ev-2"),
            reason = "Frequent mentions",
            status = MemoryProposalStatus.PENDING,
            createdAt = 240L,
            resolvedAt = null,
            updatedAt = 240L,
            deletedAt = null,
            revision = 1L,
            updatedByDeviceId = "dev-1",
        )

        val encoded = client.encodePushRequest(
            ops = listOf(
                MemorySyncOpItem("op-1", MemorySyncKind.BLOCK, block = block),
                MemorySyncOpItem("op-2", MemorySyncKind.ENTITY, entity = entity),
                MemorySyncOpItem("op-3", MemorySyncKind.EVENT, event = event),
                MemorySyncOpItem("op-4", MemorySyncKind.RELATION, relation = relation),
                MemorySyncOpItem("op-5", MemorySyncKind.PROPOSAL, proposal = proposal),
            ),
            consolidationCompletedAt = 9_999L,
        )
        val encodedJson = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(9_999L, encodedJson["consolidationCompletedAt"]?.jsonPrimitive?.long)
        val encodedOps = encodedJson["ops"]!!.jsonArray
        assertEquals(5, encodedOps.size)
        assertEquals("block", encodedOps[0].jsonObject["kind"]?.jsonPrimitive?.content)
        assertEquals("ent-1", encodedOps[2].jsonObject["event"]!!.jsonObject["entityLinks"]!!.jsonArray[0].jsonObject["entityId"]?.jsonPrimitive?.content)
        assertEquals("ev-2", encodedOps[4].jsonObject["proposal"]!!.jsonObject["supportingEventIds"]!!.jsonArray[1].jsonPrimitive.content)

        val pushResp = client.parsePushResponse(
            """{"serverSeq":42,"appliedCount":4,"skippedCount":0,"lastConsolidationAt":8888,"rejected":[{"opId":"op-3","reason":"invalid_content"}]}""",
        )!!
        assertEquals(42L, pushResp.serverSeq)
        assertEquals(4, pushResp.appliedCount)
        assertEquals(8888L, pushResp.lastConsolidationAt)
        assertEquals(listOf(MemoryV3PushRejectedOp("op-3", "invalid_content")), pushResp.rejected)

        val pullJson = """
            {
              "serverSeq": 10,
              "hasMore": true,
              "nextSince": 5,
              "lastConsolidationAt": 7777,
              "entries": [
                {"seq":1,"kind":"block","deviceId":"dev-1","block":${encodedOps[0].jsonObject["block"]}},
                {"seq":2,"kind":"entity","deviceId":"dev-1","entity":${encodedOps[1].jsonObject["entity"]}},
                {"seq":3,"kind":"event","deviceId":"dev-1","event":${encodedOps[2].jsonObject["event"]}},
                {"seq":4,"kind":"relation","deviceId":"dev-1","relation":${encodedOps[3].jsonObject["relation"]}},
                {"seq":5,"kind":"proposal","deviceId":"dev-1","proposal":${encodedOps[4].jsonObject["proposal"]}}
              ]
            }
        """.trimIndent()
        val pullResp = client.parsePullResponse(pullJson)!!
        assertEquals(10L, pullResp.serverSeq)
        assertTrue(pullResp.hasMore)
        assertEquals(5L, pullResp.nextSince)
        assertEquals(7777L, pullResp.lastConsolidationAt)
        assertEquals(5, pullResp.entries.size)
        assertEquals(block, pullResp.entries[0].block)
        assertEquals(entity, pullResp.entries[1].entity)
        assertEquals(event, pullResp.entries[2].event)
        assertEquals(relation, pullResp.entries[3].relation)
        assertEquals(proposal, pullResp.entries[4].proposal)

        val leaseAcquired = client.parseLeaseAcquireResponse(
            """{"acquired":true,"leaseId":"l-1","holderDeviceId":"dev-1","expiresAt":5000,"lastConsolidationAt":4000,"serverSeq":10}""",
        )!!
        assertTrue(leaseAcquired.acquired)
        assertEquals("l-1", leaseAcquired.leaseId)

        val leaseCooldown = client.parseLeaseAcquireResponse(
            """{"acquired":false,"holderDeviceId":null,"expiresAt":5000,"lastConsolidationAt":4500,"serverSeq":10}""",
        )!!
        assertFalse(leaseCooldown.acquired)
        assertNull(leaseCooldown.holderDeviceId)
        assertEquals(4500L, leaseCooldown.lastConsolidationAt)
    }

    @Test
    fun syncNowHandlesMultiPagePullWithCrossPageDeferredFkAndPushAckLoop() = runBlocking {
        val account = account("https://relay.example:8443", "acct-1", "dev-a")
        val accounts = FakeRelayAccountStore(account, savedToken = "tok-1")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()

        try {
            memory.ensure()
            val localBlock = memory.createBlock(
                MemoryBlockDraft(
                    slug = "local_pref",
                    title = "Local Pref",
                    purpose = "Local data to upload",
                    content = "Push me",
                ),
            )

            val entity1 = MemoryEntitySyncRecord(
                id = "ent-page2-a",
                canonicalName = "Mina",
                entityType = "PERSON",
                aliases = emptyList(),
                attributesJson = "{}",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                pinned = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                revision = 1L,
                updatedByDeviceId = "dev-b",
            )
            val entity2 = MemoryEntitySyncRecord(
                id = "ent-page2-b",
                canonicalName = "Osaka",
                entityType = "PLACE",
                aliases = emptyList(),
                attributesJson = "{}",
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                pinned = false,
                createdAt = 1_000L,
                updatedAt = 1_000L,
                revision = 1L,
                updatedByDeviceId = "dev-b",
            )
            val page1Event = MemoryEventSyncRecord(
                id = "ev-page1",
                eventKind = MemoryEventKind.EPISODE,
                content = "Mina flew to Osaka",
                qualifiersJson = "{}",
                semanticKeywords = listOf("Osaka"),
                tags = emptyList(),
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                epistemicTier = MemoryEpistemicTier.OBSERVED,
                importance = 3,
                pinned = false,
                eventTime = 1_200L,
                eventTimePrecision = MemoryEventTimePrecision.DAY,
                validFrom = 1_200L,
                status = MemoryItemStatus.ACTIVE,
                sourceKind = MemorySourceKind.USER_MANUAL,
                entityLinks = listOf(MemoryEventEntityLink("ent-page2-a", "SUBJECT")),
                createdAt = 1_200L,
                updatedAt = 1_200L,
                revision = 1L,
                updatedByDeviceId = "dev-b",
            )
            val page1Relation = MemoryRelationSyncRecord(
                id = "rel-page1",
                fromEntityId = "ent-page2-a",
                relationType = "visited",
                toEntityId = "ent-page2-b",
                label = "Visited",
                attributesJson = "{}",
                validFrom = 1_200L,
                epistemicTier = MemoryEpistemicTier.OBSERVED,
                domain = MemoryDomains.PERSONAL,
                sensitivity = MemorySensitivity.NORMAL,
                createdAt = 1_200L,
                updatedAt = 1_200L,
                revision = 1L,
                updatedByDeviceId = "dev-b",
            )

            fakeClient.onPull = { _, since, _ ->
                when (since) {
                    0L -> RelayMemoryOutcome.Ok(
                        MemoryV3PullResult(
                            serverSeq = 4L,
                            hasMore = true,
                            nextSince = 2L,
                            lastConsolidationAt = null,
                            entries = listOf(
                                MemoryRemotePullEntry(1L, MemorySyncKind.EVENT, "dev-b", event = page1Event),
                                MemoryRemotePullEntry(2L, MemorySyncKind.RELATION, "dev-b", relation = page1Relation),
                            ),
                        ),
                    )

                    else -> RelayMemoryOutcome.Ok(
                        MemoryV3PullResult(
                            serverSeq = 4L,
                            hasMore = false,
                            nextSince = 4L,
                            lastConsolidationAt = 5_555L,
                            entries = listOf(
                                MemoryRemotePullEntry(3L, MemorySyncKind.ENTITY, "dev-b", entity = entity1),
                                MemoryRemotePullEntry(4L, MemorySyncKind.ENTITY, "dev-b", entity = entity2),
                            ),
                        ),
                    )
                }
            }

            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { setOf("remote-bot", "memory-v3") },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            assertTrue(sync.syncNow(manual = true))
            assertEquals(RelayMemorySyncStatus.Synced, sync.state.value.status)
            assertEquals(4L, memory.readPullCursor("https://relay.example:8443|acct-1"))
            assertEquals(5_555L, memory.remoteLastConsolidationAt("https://relay.example:8443|acct-1"))

            val minaDetail = memory.inspectEntity("ent-page2-a", MemoryAccessScope.PERSONAL)!!
            assertEquals(listOf("ev-page1"), minaDetail.linkedEvents.map { it.id })
            assertEquals(listOf("rel-page1"), minaDetail.outgoingRelations.map { it.id })

            val pushedBlockIds = fakeClient.pushedOps.flatMap { it.ops }.mapNotNull { it.block?.id }
            assertTrue(localBlock.id in pushedBlockIds)
            assertTrue(memory.collectPendingPushBatch("https://relay.example:8443|acct-1", "dev-a").isEmpty())
        } finally {
            memory.close()
        }
    }

    @Test
    fun accountSwitchPromptsAndResolvesMergeOrReplaceLocal() = runBlocking {
        val accounts = FakeRelayAccountStore(account("https://relay-1.example:8443", "acct-1", "dev-a"), "tok")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()

        try {
            memory.ensure()
            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { setOf("memory-v3") },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            val custom = memory.createBlock(
                MemoryBlockDraft(
                    slug = "travel_gear",
                    title = "Travel Gear",
                    purpose = "Packing list",
                    content = "Noise cancelling headphones",
                ),
            )
            memory.upsertEntity(MemoryEntityRef("Sora", "PERSON"))
            assertTrue(sync.syncNow(manual = true))
            assertEquals(RelayMemorySyncStatus.Synced, sync.state.value.status)

            accounts.set(account("https://relay-2.example:8443", "acct-2", "dev-a"))
            assertFalse(sync.syncNow(manual = false))
            assertEquals(RelayMemorySyncStatus.AwaitingAccountSwitch, sync.state.value.status)
            assertTrue((sync.state.value.accountSwitchPrompt?.localRecordCount ?: 0) >= 2)

            fakeClient.pushedOps.clear()
            sync.resolveAccountSwitch(MemoryAccountSwitchMode.MERGE)
            assertEquals(RelayMemorySyncStatus.Synced, sync.state.value.status)
            assertNull(sync.state.value.accountSwitchPrompt)
            assertNotNull(memory.getBlockById(custom.id))
            val pushedToAcct2 = fakeClient.pushedOps.filter { it.account.accountId == "acct-2" }
                .flatMap { it.ops }
                .mapNotNull { it.block?.id }
            assertTrue(custom.id in pushedToAcct2)

            accounts.set(account("https://relay-3.example:8443", "acct-3", "dev-a"))
            assertFalse(sync.syncNow(manual = false))
            assertEquals(RelayMemorySyncStatus.AwaitingAccountSwitch, sync.state.value.status)

            fakeClient.pushedOps.clear()
            sync.resolveAccountSwitch(MemoryAccountSwitchMode.REPLACE_LOCAL)
            assertEquals(RelayMemorySyncStatus.Synced, sync.state.value.status)
            assertNull(memory.getBlockById(custom.id))
            assertTrue(memory.listEntities().isEmpty())
            val pushedToAcct3 = fakeClient.pushedOps.filter { it.account.accountId == "acct-3" }.flatMap { it.ops }
            assertTrue(pushedToAcct3.isEmpty())
        } finally {
            memory.close()
        }
    }

    @Test
    fun coordinatesConsolidationLeasesBusyCooldownAndSingleReleaseDedupe() = runBlocking {
        val account = account("https://relay.example:8443", "acct-1", "dev-a")
        val accounts = FakeRelayAccountStore(account, "tok")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()

        try {
            memory.ensure()
            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { setOf("memory-v3") },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            fakeClient.nextLeaseResult = RelayMemoryOutcome.Ok(
                ConsolidationLeaseAcquireResult(
                    acquired = false,
                    holderDeviceId = "dev-other",
                    expiresAt = nowMs + 60_000L,
                    lastConsolidationAt = null,
                    serverSeq = 1L,
                ),
            )
            assertEquals(
                ConsolidationLeaseResult.Blocked("busy"),
                sync.prepareForConsolidation(forceManual = true, minIntervalMs = 0L),
            )

            fakeClient.nextLeaseResult = RelayMemoryOutcome.Ok(
                ConsolidationLeaseAcquireResult(
                    acquired = false,
                    holderDeviceId = null,
                    expiresAt = nowMs + 60_000L,
                    lastConsolidationAt = 123_456L,
                    serverSeq = 1L,
                ),
            )
            assertEquals(
                ConsolidationLeaseResult.Blocked("cooldown"),
                sync.prepareForConsolidation(forceManual = false, minIntervalMs = 3_600_000L),
            )
            assertEquals(123_456L, memory.remoteLastConsolidationAt("https://relay.example:8443|acct-1"))

            fakeClient.nextLeaseResult = RelayMemoryOutcome.Ok(
                ConsolidationLeaseAcquireResult(
                    acquired = true,
                    leaseId = "lease-9",
                    holderDeviceId = "dev-a",
                    expiresAt = nowMs + 60_000L,
                    lastConsolidationAt = 123_456L,
                    serverSeq = 1L,
                ),
            )
            assertEquals(
                ConsolidationLeaseResult.Acquired("lease-9"),
                sync.prepareForConsolidation(forceManual = true, minIntervalMs = 0L),
            )
            sync.onConsolidationCompleted("lease-9", finishedAt = 234_567L)
            sync.onConsolidationFinished("lease-9")

            assertEquals(234_567L, memory.remoteLastConsolidationAt("https://relay.example:8443|acct-1"))
            assertTrue(fakeClient.pushedOps.any { it.consolidationCompletedAt == 234_567L })
            assertEquals(listOf("lease-9"), fakeClient.releasedLeases)

            sync.onConsolidationFinished("lease-fail")
            assertEquals(listOf("lease-9", "lease-fail"), fakeClient.releasedLeases)
        } finally {
            memory.close()
        }
    }

    @Test
    fun triggersConsolidationOnOverlapAndHandlesUnsupportedRelay() = runBlocking {
        val account = account("https://relay.example:8443", "acct-1", "dev-a")
        val accounts = FakeRelayAccountStore(account, "tok")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()
        var caps = setOf("remote-bot")
        var overlapTriggers = 0

        try {
            memory.ensure()
            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { caps },
                onConsolidationTrigger = { overlapTriggers++ },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            assertFalse(sync.syncNow(manual = true))
            assertEquals(RelayMemorySyncStatus.Unsupported, sync.state.value.status)
            assertEquals(
                ConsolidationLeaseResult.Blocked("unsupported"),
                sync.prepareForConsolidation(forceManual = false, minIntervalMs = 1_000L),
            )
            assertEquals(
                ConsolidationLeaseResult.Acquired(null),
                sync.prepareForConsolidation(forceManual = true, minIntervalMs = 0L),
            )

            caps = setOf("remote-bot", "memory-v3")
            val selfBlock = memory.getBlockBySlug("self_profile")!!
            nowMs += 1_000L
            memory.updateBlock(id = selfBlock.id, content = "Local edit")

            fakeClient.onPull = { _, _, _ ->
                RelayMemoryOutcome.Ok(
                    MemoryV3PullResult(
                        serverSeq = 1L,
                        hasMore = false,
                        nextSince = 1L,
                        lastConsolidationAt = null,
                        entries = listOf(
                            MemoryRemotePullEntry(
                                seq = 1L,
                                kind = MemorySyncKind.BLOCK,
                                deviceId = "dev-b",
                                block = MemoryBlockSyncRecord(
                                    id = selfBlock.id,
                                    slug = "self_profile",
                                    title = selfBlock.title,
                                    purpose = selfBlock.purpose,
                                    content = "Remote overlapping edit",
                                    domain = selfBlock.domain,
                                    sensitivity = selfBlock.sensitivity,
                                    injectionMode = selfBlock.injectionMode,
                                    maxChars = selfBlock.maxChars,
                                    owner = selfBlock.owner,
                                    pinned = false,
                                    status = MemoryBlockStatus.ACTIVE,
                                    sortOrder = selfBlock.sortOrder,
                                    createdAt = selfBlock.createdAt,
                                    updatedAt = nowMs + 5_000L,
                                    revision = 5L,
                                    updatedByDeviceId = "dev-b",
                                ),
                            ),
                        ),
                    ),
                )
            }

            assertTrue(sync.syncNow(manual = false))
            assertEquals(1, overlapTriggers)
            assertEquals("Remote overlapping edit", memory.getBlockBySlug("self_profile")!!.content)
        } finally {
            memory.close()
        }
    }

    @Test
    fun midPullFailureHoldsCursorBeforeDeferredFkAndClearsRejectedOpsWithoutWedging() = runBlocking {
        val account = account("https://relay.example:8443", "acct-1", "dev-a")
        val accounts = FakeRelayAccountStore(account, "tok")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()
        var failSecondPull = true
        val pullSinces = CopyOnWriteArrayList<Long>()

        try {
            memory.ensure()
            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { setOf("memory-v3") },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            fakeClient.onPull = { _, since, _ ->
                pullSinces += since
                when (since) {
                    0L -> RelayMemoryOutcome.Ok(
                        MemoryV3PullResult(
                            serverSeq = 20L,
                            hasMore = true,
                            nextSince = 10L,
                            lastConsolidationAt = null,
                            entries = listOf(
                                MemoryRemotePullEntry(
                                    seq = 10L,
                                    kind = MemorySyncKind.EVENT,
                                    deviceId = "dev-b",
                                    event = MemoryEventSyncRecord(
                                        id = "ev-deferred-1",
                                        eventKind = MemoryEventKind.FACT,
                                        content = "Met Nina",
                                        qualifiersJson = "{}",
                                        semanticKeywords = emptyList(),
                                        tags = emptyList(),
                                        domain = MemoryDomains.PERSONAL,
                                        sensitivity = MemorySensitivity.NORMAL,
                                        epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                                        importance = 4,
                                        pinned = false,
                                        eventTime = nowMs,
                                        eventTimePrecision = MemoryEventTimePrecision.DAY,
                                        validFrom = nowMs,
                                        status = MemoryItemStatus.ACTIVE,
                                        sourceKind = MemorySourceKind.USER_MANUAL,
                                        entityLinks = listOf(MemoryEventEntityLink("ent-later", "SUBJECT")),
                                        createdAt = nowMs,
                                        updatedAt = nowMs,
                                        revision = 1L,
                                        updatedByDeviceId = "dev-b",
                                    ),
                                ),
                            ),
                        ),
                    )

                    else -> if (failSecondPull) {
                        RelayMemoryOutcome.Offline
                    } else {
                        RelayMemoryOutcome.Ok(
                            MemoryV3PullResult(
                                serverSeq = 20L,
                                hasMore = false,
                                nextSince = 20L,
                                lastConsolidationAt = null,
                                entries = listOf(
                                    MemoryRemotePullEntry(
                                        seq = 20L,
                                        kind = MemorySyncKind.ENTITY,
                                        deviceId = "dev-b",
                                        entity = MemoryEntitySyncRecord(
                                            id = "ent-later",
                                            canonicalName = "Nina",
                                            entityType = "PERSON",
                                            aliases = emptyList(),
                                            attributesJson = "{}",
                                            domain = MemoryDomains.PERSONAL,
                                            sensitivity = MemorySensitivity.NORMAL,
                                            pinned = false,
                                            createdAt = nowMs,
                                            updatedAt = nowMs,
                                            revision = 1L,
                                            updatedByDeviceId = "dev-b",
                                        ),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }

            assertFalse(sync.syncNow(manual = true))
            assertEquals(0L, memory.readPullCursor("https://relay.example:8443|acct-1"))

            failSecondPull = false
            assertTrue(sync.syncNow(manual = true))
            assertEquals(20L, memory.readPullCursor("https://relay.example:8443|acct-1"))
            val resolved = memory.listEntities().firstOrNull { it.id == "ent-later" }
            assertNotNull(resolved)
            assertEquals(listOf(0L, 10L, 0L, 10L), pullSinces.toList())

            nowMs += 1_000L
            val poisoned = memory.saveEvent(MemoryEventDraft(content = "Bad item"))
            val clean = memory.saveEvent(MemoryEventDraft(content = "Clean follow up"))
            var injectReject = true
            fakeClient.onPush = { pushedAccount, ops, completedAt ->
                fakeClient.pushedOps += RecordedPush(pushedAccount, ops, completedAt)
                val poisonOp = ops.firstOrNull { it.event?.id == poisoned.id }
                val rejected = if (injectReject && poisonOp != null) {
                    injectReject = false
                    listOf(MemoryV3PushRejectedOp(poisonOp.opId, "event_rejected"))
                } else {
                    emptyList()
                }
                RelayMemoryOutcome.Ok(
                    MemoryV3PushResult(
                        serverSeq = 30L,
                        appliedCount = ops.size - rejected.size,
                        skippedCount = 0,
                        lastConsolidationAt = completedAt,
                        rejected = rejected,
                    ),
                )
            }

            assertFalse(sync.syncNow(manual = true))
            assertEquals(RelayMemorySyncStatus.Error, sync.state.value.status)
            assertEquals("event_rejected", sync.state.value.lastErrorCode)
            assertTrue(memory.collectPendingPushBatch("https://relay.example:8443|acct-1", "dev-a").isEmpty())

            nowMs += 1_000L
            memory.saveEvent(MemoryEventDraft(content = "After reject ${clean.id}"))
            assertTrue(sync.syncNow(manual = true))
            assertEquals(RelayMemorySyncStatus.Synced, sync.state.value.status)
        } finally {
            memory.close()
        }
    }

    @Test
    fun consolidationAccountSwitchPinsLeaseReleaseAndPreservesConcurrentWriteChangeVersion() = runBlocking {
        val account1 = account("https://relay-1.example:8443", "acct-1", "dev-a")
        val account2 = account("https://relay-2.example:8443", "acct-2", "dev-a")
        val accounts = FakeRelayAccountStore(account1, "tok")
        val memory = MemoryStore(rootDir, clock = { nowMs }, deviceIdProvider = { accounts.current()?.deviceId })
        val fakeClient = FakeRelayMemoryClient()

        try {
            memory.ensure()
            val sync = RelayMemorySync(
                scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO),
                accounts = accounts,
                memory = memory,
                client = fakeClient,
                relayCapabilities = { setOf("memory-v3") },
                clock = { nowMs },
                ioDispatcher = Dispatchers.Unconfined,
            )

            fakeClient.nextLeaseResult = RelayMemoryOutcome.Ok(
                ConsolidationLeaseAcquireResult(
                    acquired = true,
                    leaseId = "lease-acct1",
                    holderDeviceId = "dev-a",
                    expiresAt = nowMs + 60_000L,
                    serverSeq = 1L,
                ),
            )
            val acquired = sync.prepareForConsolidation(forceManual = true, minIntervalMs = 0L)
            assertEquals(ConsolidationLeaseResult.Acquired("lease-acct1"), acquired)

            accounts.set(account2)
            fakeClient.pushedOps.clear()
            sync.onConsolidationCompleted("lease-acct1", nowMs + 500L)
            sync.onConsolidationFinished("lease-acct1")

            assertTrue(fakeClient.pushedOps.isEmpty())
            assertNull(memory.remoteLastConsolidationAt("https://relay-1.example:8443|acct-1"))
            assertNull(memory.remoteLastConsolidationAt("https://relay-2.example:8443|acct-2"))
            assertEquals(listOf("acct-1" to "lease-acct1"), fakeClient.releasedLeaseAccounts.toList())

            accounts.set(account1)
            sync.resolveAccountSwitch(MemoryAccountSwitchMode.REPLACE_LOCAL)
            fakeClient.pushedOps.clear()
            nowMs += 1_000L
            memory.saveEvent(MemoryEventDraft(content = "Initial push item"))

            var concurrentWritten = false
            fakeClient.onPush = { pushedAccount, ops, completedAt ->
                fakeClient.pushedOps += RecordedPush(pushedAccount, ops, completedAt)
                if (!concurrentWritten && ops.isNotEmpty()) {
                    concurrentWritten = true
                    nowMs += 500L
                    memory.saveEvent(MemoryEventDraft(content = "Concurrent local write during push"))
                }
                RelayMemoryOutcome.Ok(
                    MemoryV3PushResult(
                        serverSeq = 10L,
                        appliedCount = ops.size,
                        skippedCount = 0,
                        lastConsolidationAt = completedAt,
                        rejected = emptyList(),
                    ),
                )
            }

            assertTrue(sync.syncNow(manual = true))
            val pushedContents = fakeClient.pushedOps.flatMap { it.ops }.mapNotNull { it.event?.content }
            assertTrue("Initial push item" in pushedContents)
            assertTrue("Concurrent local write during push" in pushedContents)
        } finally {
            memory.close()
        }
    }

    private data class RecordedPush(
        val account: RelayAccount,
        val ops: List<MemorySyncOpItem>,
        val consolidationCompletedAt: Long?,
    )

    private class FakeRelayMemoryClient : RelayMemoryClient() {
        val pushedOps = CopyOnWriteArrayList<RecordedPush>()
        val releasedLeases = CopyOnWriteArrayList<String>()
        val releasedLeaseAccounts = CopyOnWriteArrayList<Pair<String, String>>()
        var onPull: (suspend (RelayAccount, Long, Int) -> RelayMemoryOutcome<MemoryV3PullResult>)? = null
        var onPush: (suspend (RelayAccount, List<MemorySyncOpItem>, Long?) -> RelayMemoryOutcome<MemoryV3PushResult>)? = null
        var nextLeaseResult: RelayMemoryOutcome<ConsolidationLeaseAcquireResult> = RelayMemoryOutcome.Ok(
            ConsolidationLeaseAcquireResult(
                acquired = true,
                leaseId = "default-lease",
                holderDeviceId = "dev-a",
                expiresAt = 9_999_999_999L,
                serverSeq = 0L,
            ),
        )

        override suspend fun push(
            account: RelayAccount,
            ops: List<MemorySyncOpItem>,
            consolidationCompletedAt: Long?,
        ): RelayMemoryOutcome<MemoryV3PushResult> {
            val override = onPush
            if (override != null) {
                return override(account, ops, consolidationCompletedAt)
            }
            pushedOps += RecordedPush(account, ops, consolidationCompletedAt)
            return RelayMemoryOutcome.Ok(
                MemoryV3PushResult(
                    serverSeq = pushedOps.size.toLong(),
                    appliedCount = ops.size,
                    skippedCount = 0,
                    lastConsolidationAt = consolidationCompletedAt,
                    rejected = emptyList(),
                ),
            )
        }

        override suspend fun pull(
            account: RelayAccount,
            since: Long,
            limit: Int,
        ): RelayMemoryOutcome<MemoryV3PullResult> =
            onPull?.invoke(account, since, limit) ?: RelayMemoryOutcome.Ok(
                MemoryV3PullResult(
                    serverSeq = since,
                    hasMore = false,
                    nextSince = since,
                    lastConsolidationAt = null,
                    entries = emptyList(),
                ),
            )

        override suspend fun acquireConsolidationLease(
            account: RelayAccount,
            minIntervalMs: Long,
            force: Boolean,
            ttlMs: Long,
        ): RelayMemoryOutcome<ConsolidationLeaseAcquireResult> = nextLeaseResult

        override suspend fun releaseConsolidationLease(
            account: RelayAccount,
            leaseId: String,
        ): RelayMemoryOutcome<Boolean> {
            releasedLeases += leaseId
            releasedLeaseAccounts += account.accountId to leaseId
            return RelayMemoryOutcome.Ok(true)
        }
    }

    private class FakeRelayAccountStore(
        initial: RelayAccount? = null,
        private var savedToken: String? = null,
    ) : RelayAccountStore {
        private val flow = MutableStateFlow(initial)
        override val account: Flow<RelayAccount?> = flow

        override suspend fun read(): RelayAccount? = flow.value

        override suspend fun token(): String? = savedToken

        override suspend fun save(account: RelayAccount, token: String) {
            savedToken = token
            flow.value = account
        }

        override suspend fun clear() {
            savedToken = null
            flow.value = null
        }

        fun set(account: RelayAccount?) {
            flow.value = account
        }

        fun current(): RelayAccount? = flow.value
    }

    private fun account(origin: String, accountId: String, deviceId: String) = RelayAccount(
        origin = origin,
        fingerprint = null,
        caPem = null,
        deviceId = deviceId,
        deviceName = "Pixel",
        accountId = accountId,
        enrolledAt = 1L,
    )
}

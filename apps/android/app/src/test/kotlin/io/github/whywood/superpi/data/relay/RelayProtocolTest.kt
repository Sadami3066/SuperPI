package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.runtime.memory.EntitySyncRecord
import io.github.whywood.superpi.runtime.memory.MemorySyncOpItem
import io.github.whywood.superpi.runtime.memory.MemorySyncOpKind
import io.github.whywood.superpi.runtime.memory.MemorySyncRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {
    @Test
    fun buildsEnrollRequestWithEscaping() {
        val body = RelayProtocol.enrollRequest(
            deviceName = "My \"phone\"",
            platform = "android",
            appVersion = "2.0.1",
            pairCode = "ABCD2345",
            loginSecret = null,
        )
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("My \"phone\"", json["deviceName"]?.jsonPrimitive?.content)
        assertEquals("android", json["platform"]?.jsonPrimitive?.content)
        assertEquals("ABCD2345", json["pairCode"]?.jsonPrimitive?.content)
        assertNull(json["loginSecret"])
    }

    @Test
    fun omitsBlankCredentials() {
        val body = RelayProtocol.enrollRequest("Phone", "android", "2.0.1", pairCode = " ", loginSecret = "")
        val json = Json.parseToJsonElement(body).jsonObject
        assertNull(json["pairCode"])
        assertNull(json["loginSecret"])
    }

    @Test
    fun parsesEnrollmentWithEntries() {
        val enrollment = RelayProtocol.parseEnrollment(
            """
            {"deviceId":"dev-1","token":"tok","accountId":"acct","fingerprint":"${"AB".repeat(32)}",
             "entries":[{"kind":"lan","origin":"https://192.168.1.5:8443"},{"kind":"public","origin":"https://203.0.113.10:8443"}]}
            """.trimIndent(),
        )
        assertEquals("dev-1", enrollment?.deviceId)
        assertEquals("tok", enrollment?.token)
        assertEquals("acct", enrollment?.accountId)
        assertEquals("ab".repeat(32), enrollment?.fingerprint)
        assertEquals(2, enrollment?.entries?.size)
        assertEquals(RelayEntryKind.Lan, enrollment?.entries?.get(0)?.kind)
        assertEquals("https://203.0.113.10:8443", enrollment?.entries?.get(1)?.origin)
    }

    @Test
    fun rejectsIncompleteEnrollment() {
        assertNull(RelayProtocol.parseEnrollment("""{"deviceId":"dev-1"}"""))
        assertNull(RelayProtocol.parseEnrollment("not json"))
        assertNull(RelayProtocol.parseEnrollment(""))
    }

    @Test
    fun parsesPairCodeResponse() {
        val pairCode = RelayProtocol.parsePairCode(
            """{"code":"FELLJ4E5","expiresAt":1795000000000,"entries":[],"fingerprint":null}""",
        )
        assertEquals("FELLJ4E5", pairCode?.code)
        assertEquals(1795000000000L, pairCode?.expiresAt)
        assertTrue(pairCode?.entries?.isEmpty() == true)
        assertNull(pairCode?.fingerprint)
    }

    @Test
    fun readsApiErrorMessages() {
        assertEquals("invalid login secret", RelayProtocol.parseErrorMessage("""{"code":"unauthorized","message":"invalid login secret"}"""))
        assertEquals("unauthorized", RelayProtocol.parseErrorMessage("""{"code":"unauthorized"}"""))
        assertNull(RelayProtocol.parseErrorMessage("oops"))
    }

    @Test
    fun encodesAndParsesMemoryV2PushPullAndDreamLeasePayloads() {
        val pushBody = RelayProtocol.memoryPushRequest(
            ops = listOf(
                MemorySyncOpItem(
                    opId = "op-m1",
                    kind = MemorySyncOpKind.MEMORY,
                    memory = MemorySyncRecord(
                        id = "mem-1",
                        kind = MemoryKind.IDENTITY,
                        content = "Prefers tea",
                        tags = listOf("drink"),
                        entities = listOf("Alice"),
                        confidence = MemoryConfidence.CONFIRMED,
                        importance = 4,
                        pinned = true,
                        validFrom = 100L,
                        status = MemoryItemStatus.ACTIVE,
                        sourceKind = MemorySourceKind.USER_MANUAL,
                        createdAt = 100L,
                        updatedAt = 200L,
                        revision = 1L,
                        updatedByDeviceId = "dev-a",
                    ),
                ),
                MemorySyncOpItem(
                    opId = "op-e1",
                    kind = MemorySyncOpKind.ENTITY,
                    entity = EntitySyncRecord(
                        canonicalKey = "alice",
                        id = "ent-1",
                        canonicalName = "Alice",
                        kind = EntityKind.PERSON,
                        aliases = listOf("Ali"),
                        summary = "Friend",
                        updatedAt = 200L,
                        revision = 1L,
                        updatedByDeviceId = "dev-a",
                    ),
                ),
            ),
            dreamCompletedAt = 5_000L,
        )
        val parsedPushJson = Json.parseToJsonElement(pushBody).jsonObject
        assertEquals(2, parsedPushJson.getValue("ops").jsonArray.size)
        assertEquals(5_000L, parsedPushJson.getValue("dreamCompletedAt").jsonPrimitive.long)

        val pushResp = RelayProtocol.parseMemoryPushResponse(
            """{"serverSeq":9,"appliedCount":2,"skippedCount":0,"lastDreamAt":5000}""",
        )
        assertEquals(9L, pushResp?.serverSeq)
        assertEquals(5000L, pushResp?.lastDreamAt)

        val pullResp = RelayProtocol.parseMemoryPullResponse(
            """
            {
              "serverSeq": 9,
              "hasMore": true,
              "nextSince": 8,
              "lastDreamAt": 5000,
              "entries": [
                {
                  "seq": 8,
                  "kind": "memory",
                  "deviceId": "dev-a",
                  "memory": {
                    "id": "mem-1",
                    "kind": "IDENTITY",
                    "content": "Prefers tea",
                    "summary": null,
                    "tags": ["drink"],
                    "entities": ["Alice"],
                    "confidence": "CONFIRMED",
                    "importance": 4,
                    "pinned": true,
                    "validFrom": 100,
                    "validUntil": null,
                    "status": "ACTIVE",
                    "supersededBy": null,
                    "sourceKind": "USER_MANUAL",
                    "createdAt": 100,
                    "updatedAt": 200,
                    "deletedAt": null,
                    "revision": 1,
                    "updatedByDeviceId": "dev-a"
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(pullResp)
        assertEquals(true, pullResp!!.hasMore)
        assertEquals("Prefers tea", pullResp.entries.single().memory?.content)
        assertNull(pullResp.entries.single().memory?.summary)

        val leaseReq = Json.parseToJsonElement(RelayProtocol.memoryDreamLeaseRequest(60_000L, 43_200_000L)).jsonObject
        assertEquals(60_000L, leaseReq.getValue("ttlMs").jsonPrimitive.long)
        assertEquals(43_200_000L, leaseReq.getValue("minIntervalMs").jsonPrimitive.long)

        val leaseResp = RelayProtocol.parseMemoryDreamLeaseResponse(
            """{"acquired":false,"holderDeviceId":"dev-a","expiresAt":9000,"lastDreamAt":5000,"serverSeq":9}""",
        )
        assertEquals(false, leaseResp?.acquired)
        assertEquals("dev-a", leaseResp?.holderDeviceId)
        assertTrue(RelayProtocol.parseMemoryDreamLeaseRelease("""{"released":true}"""))
    }
}

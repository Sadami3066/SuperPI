package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBotAdminClientTest {
    @Test
    fun createsUpdatesDeletesBotsAndProtectsDefaultIds() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteBotAdminClient(transport)

        transport.result = { call ->
            when (call.method) {
                "bot.create" -> buildJsonObject {
                    put("bot", buildJsonObject { put("id", "research"); put("name", "Research") })
                }
                "bot.update" -> buildJsonObject {
                    put("bot", buildJsonObject { put("id", "research"); put("name", "Research Pro") })
                }
                else -> buildJsonObject { put("deleted", true); put("botId", "research") }
            }
        }

        val created = client.createBot(FakeRemoteTransport.DEVICE_ID, "Research")
        assertTrue(created is RemoteAdminResult.Ok)
        assertEquals("research", (created as RemoteAdminResult.Ok).value.id)

        val updated = client.updateBot(FakeRemoteTransport.DEVICE_ID, "research", name = "Research Pro")
        assertEquals("Research Pro", (updated as RemoteAdminResult.Ok).value.name)

        val delHome = client.deleteBot(FakeRemoteTransport.DEVICE_ID, "home")
        assertEquals("permission_denied", (delHome as RemoteAdminResult.Error).code)

        val deleted = client.deleteBot(FakeRemoteTransport.DEVICE_ID, "research")
        assertTrue(deleted is RemoteAdminResult.Ok)
    }

    @Test
    fun managesSessionsAndPropagatesRemoteErrors() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteBotAdminClient(transport)

        transport.result = { call ->
            when (call.method) {
                "session.create" -> buildJsonObject {
                    put(
                        "session",
                        buildJsonObject {
                            put("id", "s-1")
                            put("title", "Draft")
                            put("updatedAt", 10L)
                        },
                    )
                }
                "session.rename" -> buildJsonObject {
                    put("sessionId", "s-1")
                    put("title", "Renamed")
                }
                "session.update_meta" -> buildJsonObject {
                    put("sessionId", "s-1")
                    put("pinned", true)
                    put("archived", false)
                }
                else -> buildJsonObject { put("deleted", true) }
            }
        }

        val created = client.createSession(FakeRemoteTransport.DEVICE_ID, "home", "Draft") as RemoteAdminResult.Ok
        assertEquals("s-1", created.value.id)

        val renamed = client.renameSession(FakeRemoteTransport.DEVICE_ID, "home", "s-1", "Renamed") as RemoteAdminResult.Ok
        assertEquals("Renamed", renamed.value)

        val meta = client.updateSessionMeta(FakeRemoteTransport.DEVICE_ID, "home", "s-1", pinned = true) as RemoteAdminResult.Ok
        assertTrue(meta.value.pinned)

        transport.failNext = RemoteCallError("conflict", "busy")
        val conflict = client.deleteSession(FakeRemoteTransport.DEVICE_ID, "home", "s-1") as RemoteAdminResult.Error
        assertEquals("conflict", conflict.code)
        assertEquals("session.delete", transport.calls.last().method)
        assertEquals("s-1", transport.calls.last().params.getValue("sessionId").jsonPrimitive.content)
    }
}

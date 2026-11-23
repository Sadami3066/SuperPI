package io.github.whywood.superpi.remote

import java.io.File
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteArtifactClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fetchesMultiChunkArtifactUsesOfflineCacheDeduplicatesInflightAndEvictsLru() = runTest {
        val remoteRoot = temp.newFolder("remote")
        val ws1 = File(remoteRoot, "device-1/bot-1").apply { mkdirs() }
        val transport = FakeRemoteTransport(online = true)
        val payload = ByteArray(150) { (it * 3).toByte() }
        var chunkCalls = 0
        transport.result = { call ->
            when (call.method) {
                "artifact.read_chunk" -> {
                    chunkCalls += 1
                    val offset = call.params.getValue("offset").jsonPrimitive.long.toInt()
                    val end = minOf(payload.size, offset + 64)
                    val slice = payload.copyOfRange(offset, end)
                    buildJsonObject {
                        put("dataBase64", Base64.getEncoder().encodeToString(slice))
                        put("size", payload.size.toLong())
                        put("eof", end >= payload.size)
                        put("mime", "application/octet-stream")
                        put("name", "sample.bin")
                    }
                }
                else -> buildJsonObject {}
            }
        }
        val client = RemoteArtifactClient(transport, cacheRootForEviction = remoteRoot, maxCacheBytes = 200L)

        val progress = mutableListOf<Pair<Long, Long>>()
        val res1 = client.fetch(
            deviceId = FakeRemoteTransport.DEVICE_ID,
            botId = "bot-1",
            workspaceRoot = ws1,
            artifactId = "art_1",
            name = "sample.bin",
            expectedSize = payload.size.toLong(),
            onProgress = { done, total -> progress += done to total },
        ) as RemoteArtifactResult.Ok
        assertArrayEquals(payload, res1.file.readBytes())
        assertTrue(res1.file.absolutePath.endsWith("/artifacts/art_1/sample.bin"))
        assertEquals(3, chunkCalls)
        assertEquals(150L to 150L, progress.last())

        transport.setOnline(false)
        val offlineCached = client.fetch(
            deviceId = FakeRemoteTransport.DEVICE_ID,
            botId = "bot-1",
            workspaceRoot = ws1,
            artifactId = "art_1",
            name = "sample.bin",
            expectedSize = payload.size.toLong(),
        ) as RemoteArtifactResult.Ok
        assertEquals(res1.file.absolutePath, offlineCached.file.absolutePath)
        assertEquals(3, chunkCalls)

        transport.setOnline(true)
        val slowTransport = FakeRemoteTransport(online = true)
        var singleCalls = 0
        slowTransport.result = { _ ->
            singleCalls += 1
            buildJsonObject {
                put("dataBase64", Base64.getEncoder().encodeToString(ByteArray(120) { 9 }))
                put("size", 120L)
                put("eof", true)
            }
        }
        val slowClient = RemoteArtifactClient(slowTransport, cacheRootForEviction = remoteRoot, maxCacheBytes = 200L)
        res1.file.setLastModified(1_000L)
        val concurrent = listOf(
            async {
                delay(1)
                slowClient.fetch(FakeRemoteTransport.DEVICE_ID, "bot-1", ws1, "art_2", "second.bin", 120L)
            },
            async {
                slowClient.fetch(FakeRemoteTransport.DEVICE_ID, "bot-1", ws1, "art_2", "second.bin", 120L)
            },
        ).awaitAll()
        assertTrue(concurrent.all { it is RemoteArtifactResult.Ok })
        assertEquals(1, singleCalls)

        val secondFile = (concurrent.first() as RemoteArtifactResult.Ok).file
        assertTrue(secondFile.exists())
        assertTrue(!res1.file.exists())
    }

    @Test
    fun gatesFetchWhenPeerLacksPresentArtifactsCapability() = runTest {
        val ws = temp.newFolder("ws-legacy")
        val legacyTransport = FakeRemoteTransport(online = true, capabilities = listOf("remote-bot"))
        val client = RemoteArtifactClient(legacyTransport)
        val res = client.fetch(
            deviceId = FakeRemoteTransport.DEVICE_ID,
            botId = "bot-1",
            workspaceRoot = ws,
            artifactId = "art_1",
            name = "a.png",
            expectedSize = 10L,
        )
        assertEquals(RemoteArtifactResult.PeerUpgradeRequired, res)
        assertNotNull(legacyTransport)
    }
}

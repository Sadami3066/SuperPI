package io.github.whywood.superpi.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteFileClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun listsDownloadsToCacheAndUploadsMultiChunkWithSha256() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteFileClient(transport)

        val sampleBytes = ByteArray(RemoteFileClient.CHUNK_BYTES + 123) { (it % 251).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(sampleBytes).joinToString("") { "%02x".format(it) }

        val uploadedChunks = mutableListOf<ByteArray>()
        var lastExpectedSha: String? = null

        transport.result = { call ->
            when (call.method) {
                "file.list" -> buildJsonObject {
                    put("path", "src")
                    put(
                        "entries",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", "big.bin")
                                    put("path", "src/big.bin")
                                    put("type", "file")
                                    put("size", sampleBytes.size.toLong())
                                    put("modifiedAt", 42L)
                                },
                            )
                        },
                    )
                }
                "file.read_chunk" -> {
                    val offset = call.params.getValue("offset").jsonPrimitive.long.toInt()
                    val length = call.params.getValue("length").jsonPrimitive.long.toInt()
                    val end = minOf(sampleBytes.size, offset + length)
                    val slice = sampleBytes.copyOfRange(offset, end)
                    buildJsonObject {
                        put("path", "src/big.bin")
                        put("offset", offset.toLong())
                        put("data", Base64.getEncoder().encodeToString(slice))
                        put("size", sampleBytes.size.toLong())
                        put("eof", end >= sampleBytes.size)
                        put("binary", true)
                    }
                }
                "file.write_chunk" -> {
                    val b64 = call.params.getValue("data").jsonPrimitive.content
                    uploadedChunks += Base64.getDecoder().decode(b64)
                    val eof = call.params.getValue("eof").jsonPrimitive.boolean
                    if (eof) {
                        lastExpectedSha = call.params.getValue("expectedSha256").jsonPrimitive.content
                    }
                    buildJsonObject {
                        put("transferId", "ul-1")
                        put("written", sampleBytes.size.toLong())
                        put("eof", eof)
                    }
                }
                else -> buildJsonObject { put("deleted", true) }
            }
        }

        val listing = client.list(FakeRemoteTransport.DEVICE_ID, "pi", path = "src") as RemoteFileResult.Ok
        assertEquals("big.bin", listing.value.entries.single().name)

        val out = ByteArrayOutputStream()
        val progressSteps = mutableListOf<Long>()
        val downloaded = client.downloadToStream(FakeRemoteTransport.DEVICE_ID, "pi", "src/big.bin", out) {
            progressSteps += it.transferred
        } as RemoteFileResult.Ok
        assertEquals(sampleBytes.size.toLong(), downloaded.value)
        assertArrayEquals(sampleBytes, out.toByteArray())
        assertEquals(2, progressSteps.size)

        val cached = client.downloadToCacheFile(
            FakeRemoteTransport.DEVICE_ID,
            "pi",
            "src/big.bin",
            temp.newFolder("cache"),
        ) as RemoteFileResult.Ok
        assertTrue(cached.value.exists())
        assertArrayEquals(sampleBytes, cached.value.readBytes())

        val uploadRes = client.uploadFromStream(
            FakeRemoteTransport.DEVICE_ID,
            "pi",
            "dst/big.bin",
            ByteArrayInputStream(sampleBytes),
            totalBytes = sampleBytes.size.toLong(),
        ) as RemoteFileResult.Ok
        assertEquals(sampleBytes.size.toLong(), uploadRes.value)
        assertEquals(2, uploadedChunks.size)
        assertEquals(expectedSha, lastExpectedSha)
        assertArrayEquals(sampleBytes, uploadedChunks[0] + uploadedChunks[1])
    }

    @Test
    fun rejectsOversizedUploadBeforeSendingRoute() = runTest {
        val transport = FakeRemoteTransport(online = true)
        val client = RemoteFileClient(transport)
        val result = client.uploadFromStream(
            FakeRemoteTransport.DEVICE_ID,
            "pi",
            "huge.bin",
            ByteArrayInputStream(ByteArray(0)),
            totalBytes = RemoteFileClient.MAX_TRANSFER_BYTES + 1L,
        )
        assertEquals("payload_too_large", (result as RemoteFileResult.Error).code)
        assertTrue(transport.calls.isEmpty())
    }
}

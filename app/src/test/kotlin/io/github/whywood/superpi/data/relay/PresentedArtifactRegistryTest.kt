package io.github.whywood.superpi.data.relay

import java.io.File
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PresentedArtifactRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun registersReadsChunksAndEnforcesAllowlistAndBounds() {
        val file = temp.newFile("report.txt")
        val content = ByteArray(100) { it.toByte() }
        file.writeBytes(content)

        val registry = PresentedArtifactRegistry(maxEntries = 2, maxFileBytes = 1024L, maxChunkBytes = 64)
        val first = registry.register("bot-a", file, "text/plain", "report.txt")
        val second = registry.register("bot-a", file, "text/plain", "report.txt")
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first!!.artifactId, second!!.artifactId)
        assertEquals(100L, first.size)

        val chunk1 = registry.readChunk("bot-a", first.artifactId, 0L, 64) as ArtifactChunkOutcome.Ok
        assertEquals(false, chunk1.eof)
        assertEquals(100L, chunk1.size)
        val chunk2 = registry.readChunk("bot-a", first.artifactId, 64L, 64) as ArtifactChunkOutcome.Ok
        assertEquals(true, chunk2.eof)
        val assembled = Base64.getDecoder().decode(chunk1.dataBase64) + Base64.getDecoder().decode(chunk2.dataBase64)
        assertArrayEquals(content, assembled)

        val crossBot = registry.readChunk("bot-b", first.artifactId, 0L, 64) as ArtifactChunkOutcome.Error
        assertEquals("not_found", crossBot.code)

        val unknown = registry.readChunk("bot-a", "deadbeef", 0L, 64) as ArtifactChunkOutcome.Error
        assertEquals("not_found", unknown.code)

        val tooLargeChunk = registry.readChunk("bot-a", first.artifactId, 0L, 65) as ArtifactChunkOutcome.Error
        assertEquals("payload_too_large", tooLargeChunk.code)

        val f2 = temp.newFile("two.txt").apply { writeText("two") }
        val f3 = temp.newFile("three.txt").apply { writeText("three") }
        val reg2 = registry.register("bot-a", f2, "text/plain", "two.txt")!!
        registry.register("bot-a", f3, "text/plain", "three.txt")!!
        val evicted = registry.readChunk("bot-a", first.artifactId, 0L, 64) as ArtifactChunkOutcome.Error
        assertEquals("not_found", evicted.code)
        assertTrue(registry.readChunk("bot-a", reg2.artifactId, 0L, 64) is ArtifactChunkOutcome.Ok)

        val oversized = File(temp.root, "big.bin").apply { writeBytes(ByteArray(1025)) }
        assertNull(registry.register("bot-a", oversized, "application/octet-stream", "big.bin"))
    }
}

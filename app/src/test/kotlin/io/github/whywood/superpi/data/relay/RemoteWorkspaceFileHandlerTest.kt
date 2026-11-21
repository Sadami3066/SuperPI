package io.github.whywood.superpi.data.relay

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteWorkspaceFileHandlerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun listsDirectoriesFirstSearchesRecursivelyAndHidesTempFiles() {
        val root = temp.newFolder("ws")
        File(root, "src/nested").mkdirs()
        File(root, "node_modules/pkg").mkdirs()
        File(root, "README.md").writeText("hello")
        File(root, "src/nested/Main.kt").writeText("fun main() {}")
        File(root, "node_modules/pkg/ignored.js").writeText("ignored")
        File(root, ".superpi-upload-temp1.tmp").writeText("partial")

        val handler = RemoteWorkspaceFileHandler()
        val (listedPath, entries) = handler.list(root.absolutePath)
        assertEquals("", listedPath)
        assertEquals(
            listOf("node_modules" to "directory", "src" to "directory", "README.md" to "file"),
            entries.map { it.name to it.type },
        )

        val (_, searched) = handler.list(root.absolutePath, query = "main.kt")
        assertEquals(listOf("src/nested/Main.kt"), searched.map { it.path })

        val st = handler.stat(root.absolutePath, "src/nested/Main.kt")
        assertEquals("text/x-kotlin", st.mimeType)

        val err = runCatching { handler.stat(root.absolutePath, ".superpi-upload-temp1.tmp") }.exceptionOrNull() as RemoteFileException
        assertEquals("permission_denied", err.code)
    }

    @Test
    fun readsChunksAndEnforcesLengthBounds() {
        val root = temp.newFolder("ws-read")
        File(root, "note.txt").writeText("abcdef")
        File(root, "bin.dat").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        val handler = RemoteWorkspaceFileHandler()

        val c1 = handler.readChunk(root.absolutePath, "note.txt", 0, 4)
        assertEquals("abcd", String(Base64.getDecoder().decode(c1.data)))
        assertFalse(c1.eof)
        assertFalse(c1.binary)
        assertEquals(6L, c1.size)

        val c2 = handler.readChunk(root.absolutePath, "note.txt", 4, 4)
        assertEquals("ef", String(Base64.getDecoder().decode(c2.data)))
        assertTrue(c2.eof)

        val bin = handler.readChunk(root.absolutePath, "bin.dat", 0, 8)
        assertTrue(bin.binary)

        val err = runCatching {
            handler.readChunk(root.absolutePath, "note.txt", 0, RelayFrames.MAX_FILE_CHUNK_BYTES + 1)
        }.exceptionOrNull() as RemoteFileException
        assertEquals("payload_too_large", err.code)
    }

    @Test
    fun uploadsMultiChunkWithSha256VerificationAndPrunesStaleTransfers() {
        var now = 1_000_000L
        val root = temp.newFolder("ws-write")
        val handler = RemoteWorkspaceFileHandler(clock = { now })

        val payload = "chunk-one-and-chunk-two".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val part1 = payload.copyOfRange(0, 9)
        val part2 = payload.copyOfRange(9, payload.size)

        val r1 = handler.writeChunk(
            root.absolutePath,
            RemoteFileWriteChunkInput(
                path = "uploads/data.txt",
                offset = 0,
                data = Base64.getEncoder().encodeToString(part1),
                eof = false,
            ),
        )
        assertFalse(r1.eof)
        assertEquals(9L, r1.written)
        assertFalse(File(root, "uploads/data.txt").exists())

        val mismatch = runCatching {
            handler.writeChunk(
                root.absolutePath,
                RemoteFileWriteChunkInput(
                    path = "uploads/data.txt",
                    transferId = r1.transferId,
                    offset = 3,
                    data = Base64.getEncoder().encodeToString(part2),
                    eof = true,
                ),
            )
        }.exceptionOrNull() as RemoteFileException
        assertEquals("conflict", mismatch.code)

        val r2 = handler.writeChunk(
            root.absolutePath,
            RemoteFileWriteChunkInput(
                path = "uploads/data.txt",
                transferId = r1.transferId,
                offset = 9,
                data = Base64.getEncoder().encodeToString(part2),
                eof = true,
                expectedSha256 = sha256,
            ),
        )
        assertTrue(r2.eof)
        assertEquals(sha256, r2.sha256)
        assertEquals(String(payload), File(root, "uploads/data.txt").readText())

        val existsErr = runCatching {
            handler.writeChunk(
                root.absolutePath,
                RemoteFileWriteChunkInput(
                    path = "uploads/data.txt",
                    offset = 0,
                    data = Base64.getEncoder().encodeToString("new".toByteArray()),
                    eof = true,
                    overwrite = false,
                ),
            )
        }.exceptionOrNull() as RemoteFileException
        assertEquals("already_exists", existsErr.code)

        val badSha = runCatching {
            handler.writeChunk(
                root.absolutePath,
                RemoteFileWriteChunkInput(
                    path = "uploads/bad.txt",
                    offset = 0,
                    data = Base64.getEncoder().encodeToString("bad".toByteArray()),
                    eof = true,
                    expectedSha256 = "0".repeat(64),
                ),
            )
        }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", badSha.code)
        assertFalse(File(root, "uploads/bad.txt").exists())

        val badB64 = runCatching {
            handler.writeChunk(
                root.absolutePath,
                RemoteFileWriteChunkInput(
                    path = "uploads/bad64.txt",
                    offset = 0,
                    data = "%%%not-base64%%%",
                    eof = true,
                ),
            )
        }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", badB64.code)

        handler.writeChunk(
            root.absolutePath,
            RemoteFileWriteChunkInput(
                path = "stale.txt",
                transferId = "stale-1",
                offset = 0,
                data = Base64.getEncoder().encodeToString("part".toByteArray()),
                eof = false,
            ),
        )
        assertTrue(root.list().orEmpty().any { it.contains("stale-1") })

        now += 16L * 60L * 1000L
        handler.writeChunk(
            root.absolutePath,
            RemoteFileWriteChunkInput(
                path = "fresh.txt",
                offset = 0,
                data = Base64.getEncoder().encodeToString("ok".toByteArray()),
                eof = true,
            ),
        )
        assertFalse(root.list().orEmpty().any { it.contains("stale-1") })
    }

    @Test
    fun supportsMkdirRenameDeleteAndBlocksTraversalAndSymlinkEscapes() {
        val root = temp.newFolder("ws-ops")
        val outside = temp.newFolder("ws-outside")
        val outsideSecret = File(outside, "secret.txt").apply { writeText("secret") }
        val handler = RemoteWorkspaceFileHandler()

        assertEquals("a/b" to true, handler.mkdir(root.absolutePath, "a/b"))
        assertEquals("a/b" to false, handler.mkdir(root.absolutePath, "a/b"))

        File(root, "a/b/doc.txt").writeText("hi")
        assertEquals("a/b/doc.txt" to "moved/doc.txt", handler.rename(root.absolutePath, "a/b/doc.txt", "moved/doc.txt"))
        assertTrue(File(root, "moved/doc.txt").exists())

        val selfMoveErr = runCatching {
            handler.rename(root.absolutePath, "moved", "moved/sub")
        }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", selfMoveErr.code)

        val nonEmptyErr = runCatching {
            handler.delete(root.absolutePath, "moved", recursive = false)
        }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", nonEmptyErr.code)

        assertEquals(true to "moved", handler.delete(root.absolutePath, "moved", recursive = true))
        assertFalse(File(root, "moved").exists())

        val linkFile = File(root, "escape-link")
        Files.createSymbolicLink(linkFile.toPath(), outsideSecret.toPath())

        val statErr = runCatching { handler.stat(root.absolutePath, "escape-link") }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", statErr.code)

        val readErr = runCatching { handler.readChunk(root.absolutePath, "escape-link", 0, 8) }.exceptionOrNull() as RemoteFileException
        assertEquals("bad_request", readErr.code)

        assertEquals(true to "escape-link", handler.delete(root.absolutePath, "escape-link", recursive = false))
        assertTrue(outsideSecret.exists())

        for (invalid in listOf("../outside", "/abs/path", "a\\b", "bad\u0000path", "")) {
            val err = runCatching { handler.stat(root.absolutePath, invalid) }.exceptionOrNull() as RemoteFileException
            assertEquals("bad_request", err.code)
        }
    }
}

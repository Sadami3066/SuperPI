package io.github.whywood.superpi.ui.miniapp

import android.content.ContentResolver
import io.github.whywood.superpi.runtime.MiniappHostException
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiniappFileStoreTest {
    private lateinit var dir: File
    private lateinit var store: MiniappFileStore

    private class StubContentResolver : ContentResolver(null)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("miniapp-file-store-test").toFile()
        store = MiniappFileStore(StubContentResolver(), dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writeOverTotalQuotaFailsBeforeModifyingDisk() = runTest {
        val existing = File(dir, "existing.bin")
        val existingSize = 95 * 1024 * 1024
        java.io.RandomAccessFile(existing, "rw").use { raf ->
            raf.setLength(existingSize.toLong())
        }
        val marker = File(dir, "note.txt")
        marker.writeText("keep-me")

        val rejectedNew = runCatching {
            store.write("overflow.bin", "utf8", "x".repeat(6 * 1024 * 1024))
        }.exceptionOrNull() as? MiniappHostException
        assertEquals(MiniappHostException.TOO_LARGE, rejectedNew?.code)
        assertFalse(File(dir, "overflow.bin").exists())

        val rejectedOverwrite = runCatching {
            store.write("note.txt", "utf8", "y".repeat(6 * 1024 * 1024))
        }.exceptionOrNull() as? MiniappHostException
        assertEquals(MiniappHostException.TOO_LARGE, rejectedOverwrite?.code)
        assertEquals("keep-me", marker.readText())
    }

    @Test
    fun writeOverEntryQuotaFailsBeforeCreatingFile() = runTest {
        repeat(500) { index ->
            File(dir, "f-$index.txt").writeText("a")
        }

        val rejected = runCatching {
            store.write("f-500.txt", "utf8", "b")
        }.exceptionOrNull() as? MiniappHostException
        assertEquals(MiniappHostException.TOO_LARGE, rejected?.code)
        assertFalse(File(dir, "f-500.txt").exists())
        assertTrue(File(dir, "f-0.txt").exists())
    }

    @Test
    fun importOverTotalQuotaDeletesPartialTarget() = runTest {
        val existing = File(dir, "existing.bin")
        java.io.RandomAccessFile(existing, "rw").use { raf ->
            raf.setLength(95L * 1024 * 1024)
        }

        val rejected = runCatching {
            store.importStream("photo.bin", ByteArrayInputStream(ByteArray(6 * 1024 * 1024)))
        }.exceptionOrNull() as? MiniappHostException

        assertEquals(MiniappHostException.TOO_LARGE, rejected?.code)
        assertFalse(File(dir, "photo.bin").exists())
    }
}

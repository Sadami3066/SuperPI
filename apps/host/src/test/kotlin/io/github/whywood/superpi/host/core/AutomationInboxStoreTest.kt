package io.github.whywood.superpi.host.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationInboxStoreTest {

    @Test
    fun `persists records, updates status, and clears settled items`() {
        val tmpDir = Files.createTempDirectory("superpi-inbox-test").toFile()
        try {
            val file = File(tmpDir, "inbox.json")
            val store = AutomationInboxStore(file, maxItems = 50)
            assertEquals(0, store.unreadCount())

            val a = store.record(source = "timer", title = "Daily Digest", summary = "All tests green", sessionId = "s-1")
            val b = store.record(source = "worker", title = "Refactor API", summary = "Done cleanly", workerId = "w-1")
            assertEquals(2, store.unreadCount())

            assertNotNull(store.updateStatus(a.id, "approved"))
            assertNull(store.updateStatus(a.id, "bogus"))
            assertEquals(1, store.unreadCount())

            // Reload from disk verifies persistence
            val reloaded = AutomationInboxStore(file)
            assertEquals(2, reloaded.list().size)
            assertEquals(1, reloaded.unreadCount())
            assertEquals("approved", reloaded.list().first { it.id == a.id }.status)

            val cleared = reloaded.clearSettled()
            assertEquals(1, cleared)
            assertEquals(1, reloaded.list().size)
            assertEquals(b.id, reloaded.list().first().id)

            assertTrue(reloaded.delete(b.id))
            assertEquals(0, reloaded.list().size)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

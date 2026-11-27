package io.github.whywood.superpi.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionJsonlHistoryOpsTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session-jsonl-ops").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sampleLines(sessionId: String = "orig-1"): List<String> = listOf(
        """{"type":"session","version":3,"id":"$sessionId","timestamp":"2026-01-01T00:00:00Z","cwd":"/tmp/ws"}""",
        """{"type":"message","message":{"role":"user","content":"first question"}}""",
        """{"type":"message","message":{"role":"assistant","content":"first answer"}}""",
        """{"type":"message","message":{"role":"user","content":"second question"}}""",
        """{"type":"message","message":{"role":"assistant","content":"second answer"}}""",
    )

    @Test
    fun hasUserTurnChecksBounds() {
        val file = File(tempDir, "s1.jsonl").apply {
            writeText(sampleLines().joinToString("\n") + "\n")
        }
        assertTrue(SessionJsonlHistoryOps.hasUserTurnAtIndex(file, 0))
        assertTrue(SessionJsonlHistoryOps.hasUserTurnAtIndex(file, 1))
        assertFalse(SessionJsonlHistoryOps.hasUserTurnAtIndex(file, 2))
        assertFalse(SessionJsonlHistoryOps.hasUserTurnAtIndex(file, -1))
    }

    @Test
    fun truncateBeforeSecondUserTurnKeepsFirstRoundAndCreatesBackup() {
        val file = File(tempDir, "s2.jsonl").apply {
            writeText(sampleLines().joinToString("\n") + "\n")
        }
        assertTrue(SessionJsonlHistoryOps.truncateBeforeUserTurn(file, 1))
        val remaining = file.readLines().filter { it.isNotBlank() }
        assertEquals(3, remaining.size)
        assertTrue(remaining[1].contains("first question"))
        assertTrue(remaining[2].contains("first answer"))
        val bak = File(tempDir, "s2.jsonl.pre-rewind.bak")
        assertTrue(bak.isFile)
        assertEquals(5, bak.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun truncateBeforeFirstUserTurnKeepsSessionHeader() {
        val file = File(tempDir, "s3.jsonl").apply {
            writeText(sampleLines().joinToString("\n") + "\n")
        }
        assertTrue(SessionJsonlHistoryOps.truncateBeforeUserTurn(file, 0))
        val remaining = file.readLines().filter { it.isNotBlank() }
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().contains("\"type\":\"session\""))
    }

    @Test
    fun truncateRejectsOutOfBoundsTurn() {
        val file = File(tempDir, "s4.jsonl").apply {
            writeText(sampleLines().joinToString("\n") + "\n")
        }
        assertFalse(SessionJsonlHistoryOps.truncateBeforeUserTurn(file, 5))
        assertEquals(5, file.readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun sliceAndForkRewritesSessionIdAndCutsAtTurn() {
        val source = File(tempDir, "orig.jsonl").apply {
            writeText(sampleLines("orig-id").joinToString("\n") + "\n")
        }
        val target = File(tempDir, "fork-id.jsonl")
        assertTrue(SessionJsonlHistoryOps.forkSessionFile(source, target, 0, "fork-id", "/tmp/ws"))
        val forked = target.readLines().filter { it.isNotBlank() }
        assertEquals(3, forked.size)
        assertTrue(forked[0].contains("\"id\":\"fork-id\""))
        assertTrue(forked[1].contains("first question"))
        assertTrue(forked[2].contains("first answer"))
    }

    @Test
    fun sliceSynthesizesHeaderWhenMissing() {
        val lines = listOf(
            """{"type":"message","message":{"role":"user","content":"hello"}}""",
            """{"type":"message","message":{"role":"assistant","content":"hi"}}""",
        )
        val forked = SessionJsonlHistoryOps.sliceLinesForFork(lines, null, "new-id", "/tmp/demo")
        assertEquals(3, forked.size)
        assertTrue(forked[0].contains("\"type\":\"session\""))
        assertTrue(forked[0].contains("\"id\":\"new-id\""))
    }
}

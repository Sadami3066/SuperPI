package io.github.whywood.superpi.host.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeAndCheckpointTest {
    private fun runGit(dir: File, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        assertEquals("git ${args.joinToString(" ")} failed: $out", 0, code)
    }

    @Test
    fun worktreeCreateDiffApplyAndRemove() = runBlocking {
        val tempRoot = Files.createTempDirectory("superpi-wt-test").toFile()
        try {
            val repo = File(tempRoot, "repo").apply { mkdirs() }
            runGit(repo, "init")
            runGit(repo, "config", "user.name", "Tester")
            runGit(repo, "config", "user.email", "tester@example.com")
            File(repo, "hello.txt").writeText("v1\n")
            runGit(repo, "add", "hello.txt")
            runGit(repo, "commit", "-m", "initial")

            val mgr = WorktreeManager(File(tempRoot, "worktrees"))
            assertTrue(mgr.isGitRepo(repo))

            val wt = mgr.create(botId = "bot-1", repoDir = repo, label = "feature-a")
            assertTrue(File(wt.worktreePath).isDirectory)
            assertEquals(1, mgr.listForBot("bot-1").size)

            File(wt.worktreePath, "hello.txt").writeText("v1\nv2\n")
            File(wt.worktreePath, "added.txt").writeText("new file\n")

            val diff = mgr.diff(wt.id)
            assertTrue(diff.files.any { it.path == "hello.txt" })
            assertTrue(diff.totalAdditions >= 2)

            val applied = mgr.applyToBase(wt.id)
            assertTrue(applied.message, applied.ok)
            assertEquals("v1\nv2\n", File(repo, "hello.txt").readText())
            assertEquals("new file\n", File(repo, "added.txt").readText())

            assertTrue(mgr.remove(wt.id))
            assertFalse(File(wt.worktreePath).exists())
            assertTrue(mgr.listForBot("bot-1").isEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun shadowCheckpointCapturesAndRestoresWithoutTouchingRealGit() = runBlocking {
        val tempRoot = Files.createTempDirectory("superpi-cp-test").toFile()
        try {
            val ws = File(tempRoot, "workspace").apply { mkdirs() }
            runGit(ws, "init")
            runGit(ws, "config", "user.name", "Tester")
            runGit(ws, "config", "user.email", "tester@example.com")
            File(ws, "app.txt").writeText("original\n")
            runGit(ws, "add", "app.txt")
            runGit(ws, "commit", "-m", "init")

            val store = ShadowCheckpointStore(File(tempRoot, "checkpoints"))
            val cp = store.capture("sess-1", ws, turnIndex = 0, label = "before edit", messageCount = 0)
            assertNotNull(cp)

            // Modify existing file & create a new file
            File(ws, "app.txt").writeText("broken edit\n")
            File(ws, "extra.txt").writeText("temp artifact\n")

            val res = store.restoreFiles("sess-1", cp!!.id)
            assertTrue(res.message, res.ok)
            assertNotNull(res.backupCheckpointId)
            assertEquals("original\n", File(ws, "app.txt").readText())
            assertFalse(File(ws, "extra.txt").exists())

            // Verify user's real git status is completely clean and untouched
            val proc = ProcessBuilder("git", "status", "--porcelain").directory(ws).start()
            val statusOut = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            assertEquals("", statusOut)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}

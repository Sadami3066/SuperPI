package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutionException
import java.io.File
import java.nio.file.Files
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinuxShellBackendTest {
    private lateinit var tempDir: File
    private lateinit var logsDir: File
    private lateinit var platform: LinuxHostPlatform

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-shell-test").toFile()
        val home = File(tempDir, "home").apply { mkdirs() }
        platform = LinuxHostPlatform(PathLayout(home))
        logsDir = platform.paths.shellLogsDir
    }

    @After
    fun tearDown() {
        platform.cleanupAllChildProcesses()
        tempDir.deleteRecursively()
    }

    @Test
    fun capturesStdoutStderrAndExitCode() = runBlocking {
        val env = platform.createHostEnvironment()
        val bash = env.bash(tempDir.absolutePath) as DetailedShellExecutor
        val res = bash.runDetailed("echo out_line; echo err_line >&2; exit 7", 10, null)
        assertEquals(7, res.exitCode)
        assertFalse(res.timedOut)
        assertTrue(res.output.contains("out_line"))
        assertTrue(res.output.contains("err_line"))
    }

    @Test
    fun killsNestedChildProcessTreeOnTimeout() = runBlocking {
        val pidFile = File(tempDir, "child.pid")
        val executor = platform.shell.executor(tempDir.absolutePath)
        val res = executor.runDetailed(
            "sleep 60 & echo \$! > '${pidFile.absolutePath}'; wait",
            timeoutSeconds = 1,
            signal = null,
        )
        assertTrue("Expected command to time out", res.timedOut)
        assertTrue("Expected child pid file to be written", pidFile.isFile)
        val pid = pidFile.readText().trim().toLong()
        delay(200)
        val alive = ProcessHandle.of(pid).getOrNull()?.isAlive ?: false
        assertFalse("Orphan child process $pid should have been terminated on timeout", alive)
    }

    @Test
    fun killsNestedChildProcessTreeOnAbortSignal() = runBlocking {
        val pidFile = File(tempDir, "abort-child.pid")
        val signal = RunSignal()
        val executor = platform.shell.executor(tempDir.absolutePath)
        val job = launch {
            try {
                executor.runDetailed(
                    "sleep 60 & echo \$! > '${pidFile.absolutePath}'; wait",
                    timeoutSeconds = 30,
                    signal = signal,
                )
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
        }
        val deadline = System.currentTimeMillis() + 5_000L
        while (!pidFile.isFile && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        assertTrue("Child pid file not created in time", pidFile.isFile)
        val pid = pidFile.readText().trim().toLong()
        signal.abort()
        job.join()
        delay(200)
        val alive = ProcessHandle.of(pid).getOrNull()?.isAlive ?: false
        assertFalse("Orphan child process $pid should die on abort signal", alive)
    }

    @Test
    fun spillsOversizedOutputToLogFile() = runBlocking {
        val killer = LinuxProcessTreeKiller()
        val backend = LinuxShellBackend(
            logsDir = logsDir,
            processKiller = killer,
            onRegisterProcess = { _, _ -> },
            onUnregisterProcess = { },
            maxOutputLines = 10,
            maxOutputBytes = 256,
        )
        val res = backend.executor(tempDir.absolutePath).runDetailed(
            "i=1; while [ \$i -le 60 ]; do echo \"line_\${i}_payload_for_spill_test\"; i=\$((i+1)); done",
            timeoutSeconds = 10,
            signal = null,
        )
        assertEquals(0, res.exitCode)
        assertTrue(res.truncated)
        assertNotNull("Expected spill log file path", res.fullOutputPath)
        val logFile = File(res.fullOutputPath!!)
        assertTrue(logFile.isFile)
        val spilled = logFile.readText()
        assertTrue(spilled.contains("line_1_payload"))
        assertTrue(res.output.contains("line_60_payload"))
        for (i in 1..60) {
            val marker = "line_${i}_payload_for_spill_test"
            val count = spilled.lines().count { it == marker }
            assertEquals("Expected exact single occurrence of $marker in spill log", 1, count)
        }
    }

    @Test(expected = ShellExecutionException::class)
    fun failsWhenExplicitWorkingDirectoryDoesNotExist() = runBlocking {
        val missing = File(tempDir, "does-not-exist")
        platform.shell.executor(missing.absolutePath).runDetailed("pwd", 5, null)
        Unit
    }
}

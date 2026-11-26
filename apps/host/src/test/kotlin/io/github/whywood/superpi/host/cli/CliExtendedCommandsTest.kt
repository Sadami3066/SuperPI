package io.github.whywood.superpi.host.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliExtendedCommandsTest {
    private fun runCli(home: java.io.File, vararg args: String): Triple<Int, String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val env = CliRuntimeEnv(
            homeOverride = home,
            out = PrintStream(outBuf, true, Charsets.UTF_8),
            err = PrintStream(errBuf, true, Charsets.UTF_8),
        )
        val cmd = SuperPiRootCommand(env)
        val code = try {
            cmd.parse(args.toList())
            0
        } catch (res: ProgramResult) {
            res.statusCode
        }
        return Triple(code, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    @Test
    fun providerConfigSessionAndRelayDevicesSubcommandsWork() {
        val home = Files.createTempDirectory("superpi-cli-m3-").toFile()
        try {
            // provider set-key / list / default / rm-key
            val (c1, out1, _) = runCli(home, "provider", "set-key", "opencode-go", "--key", "test-key-12345678")
            assertEquals(0, c1)
            assertTrue(out1.contains("opencode-go"))

            val (c2, out2, _) = runCli(home, "provider", "list")
            assertEquals(0, c2)
            assertTrue(out2.contains("opencode-go"))
            assertTrue(out2.contains("configured"))

            val (c3, out3, _) = runCli(home, "provider", "default", "opencode-go/glm-5", "--thinking", "xhigh")
            assertEquals(0, c3)
            assertTrue(out3.contains("opencode-go/glm-5"))
            assertTrue(out3.contains("XHIGH"))

            val (c4, out4, _) = runCli(home, "provider", "rm-key", "opencode-go")
            assertEquals(0, c4)
            assertTrue(out4.contains("Removed"))

            // config set / get / list
            val (c5, _, _) = runCli(home, "config", "set", "language", "en")
            assertEquals(0, c5)
            val (c6, out6, _) = runCli(home, "config", "get", "language")
            assertEquals(0, c6)
            assertEquals("en", out6.trim())

            val (c7, out7, _) = runCli(home, "config", "list")
            assertEquals(0, c7)
            assertTrue(out7.contains("sendKey="))

            // session list (empty)
            val (c8, out8, _) = runCli(home, "session", "list")
            assertEquals(0, c8)
            assertTrue(out8.contains("No sessions"))

            // relay devices list (empty)
            val (c9, out9, _) = runCli(home, "relay", "devices", "list")
            assertEquals(0, c9)
            assertTrue(out9.contains("No paired devices"))

            // invalid thinking error advertises xhigh/max, not minimal
            val (c10, _, err10) = runCli(home, "--thinking", "minimal", "-p", "hi")
            assertEquals(2, c10)
            assertTrue(err10.contains("xhigh"))
            assertTrue(!err10.contains("minimal."))
        } finally {
            home.deleteRecursively()
        }
    }
}

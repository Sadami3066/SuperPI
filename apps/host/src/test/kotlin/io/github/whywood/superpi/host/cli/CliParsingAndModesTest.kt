package io.github.whywood.superpi.host.cli

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.core.DoctorService
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.executeCliForTest
import io.github.whywood.superpi.host.platform.LinuxHostPlatform
import io.github.whywood.superpi.host.platform.PathLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CliParsingAndModesTest {
    private lateinit var tempDir: File
    private lateinit var homeDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-cli-test").toFile()
        homeDir = File(tempDir, ".super-pi")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun versionAndSubcommandsRunCleanlyThroughClikt() {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        try {
            executeCliForTest(
                args = listOf("--version"),
                homeDir = homeDir,
                out = PrintStream(outBytes, true, Charsets.UTF_8),
                err = PrintStream(errBytes, true, Charsets.UTF_8),
            )
            fail("Expected ProgramResult(0) from --version")
        } catch (result: com.github.ajalt.clikt.core.ProgramResult) {
            assertEquals(0, result.statusCode)
        }
        assertTrue(outBytes.toString(Charsets.UTF_8).contains("${AppBrand.CLI_NAME} ${AppBrand.VERSION}"))

        executeCliForTest(
            args = listOf("bot", "list"),
            homeDir = homeDir,
        )
        assertTrue(homeDir.isDirectory)

        val customHome = File(tempDir, "cli-flag-home")
        executeCliForTest(
            args = listOf("--home", customHome.absolutePath, "bot", "list"),
            homeDir = homeDir,
        )
        assertTrue(customHome.isDirectory)
    }

    @Test
    fun rpcRunnerHandlesPingBotsDoctorSessionAndErrors() = runBlocking {
        val platform = LinuxHostPlatform(PathLayout(homeDir))
        val controller = SuperPiController(platform)
        controller.bootstrap()
        try {
            val doctor = DoctorService(controller, platform, controller.extensionRegistry)
            val ping = RpcRunner.handleLine(
                controller,
                doctor,
                tempDir,
                """{"jsonrpc":"2.0","id":1,"method":"rpc.ping"}""",
            )
            assertEquals("true", ping["result"]?.jsonObject?.get("pong")?.jsonPrimitive?.content)

            val bots = RpcRunner.handleLine(
                controller,
                doctor,
                tempDir,
                """{"jsonrpc":"2.0","id":2,"method":"bots.list"}""",
            )
            assertNotNull(bots["result"])

            val docResp = RpcRunner.handleLine(
                controller,
                doctor,
                tempDir,
                """{"jsonrpc":"2.0","id":3,"method":"doctor.run"}""",
            )
            assertEquals(
                AppBrand.VERSION,
                docResp["result"]?.jsonObject?.get("version")?.jsonPrimitive?.content,
            )

            val created = RpcRunner.handleLine(
                controller,
                doctor,
                tempDir,
                """{"jsonrpc":"2.0","id":4,"method":"session.create","params":{"cwd":"${tempDir.absolutePath}"}}""",
            )
            assertNotNull(created["result"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content)

            val badJson = RpcRunner.handleLine(controller, doctor, tempDir, "{not json")
            assertEquals("-32700", badJson["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)

            val unknown = RpcRunner.handleLine(
                controller,
                doctor,
                tempDir,
                """{"jsonrpc":"2.0","id":5,"method":"does.not.exist"}""",
            )
            assertEquals("-32601", unknown["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun terminalQrAndSystemdUnitRenderProperly() {
        val qr = TerminalQr.render("https://relay.example.com/pair#code=TEST123")
        val lines = qr.lines()
        assertTrue(lines.size > 5)
        assertTrue(lines.any { line -> line.any { it in listOf('█', '▀', '▄') } })

        val spacedHome = File(tempDir, "home 100% dir")
        val unit = io.github.whywood.superpi.host.platform.LinuxServiceManager.renderUnitContent(
            homeDir = spacedHome,
            port = 7788,
            execPrefix = listOf("/opt/super-pi/bin/super-pi"),
        )
        assertTrue(unit.contains("ExecStart=/opt/super-pi/bin/super-pi --home \""))
        assertTrue(unit.contains("100%% dir"))
        assertTrue(unit.contains("serve --port 7788"))
        assertTrue(unit.contains("Restart=on-failure"))
    }

    @Test
    fun rejectsNoSessionCombinedWithContinue() {
        try {
            executeCliForTest(
                args = listOf("-p", "--no-session", "--continue", "hello"),
                homeDir = homeDir,
            )
            fail("Expected ProgramResult(2) when combining --no-session and --continue")
        } catch (result: com.github.ajalt.clikt.core.ProgramResult) {
            assertEquals(2, result.statusCode)
        }
    }

    @Test
    fun versionDoesNotInvokeSubcommand() {
        val outBytes = ByteArrayOutputStream()
        try {
            executeCliForTest(
                args = listOf("--version", "status"),
                homeDir = homeDir,
                out = PrintStream(outBytes, true, Charsets.UTF_8),
            )
            fail("Expected ProgramResult(0) from --version")
        } catch (result: com.github.ajalt.clikt.core.ProgramResult) {
            assertEquals(0, result.statusCode)
        }
        val printed = outBytes.toString(Charsets.UTF_8)
        assertTrue(printed.contains("${AppBrand.CLI_NAME} ${AppBrand.VERSION}"))
        assertFalse(printed.contains("daemon"))
    }
}

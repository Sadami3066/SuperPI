package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.runtime.host.HostAccess
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrossPlatformHostTest {
    private lateinit var tempDir: File
    private lateinit var homeDir: File
    private lateinit var paths: PathLayout

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-cross-host").toFile()
        homeDir = File(tempDir, ".super-pi")
        paths = PathLayout(homeDir)
        paths.ensureDirectories()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun macosHostPlatformExposesProfileAndRendersEscapedLaunchdPlist() {
        val fakeZsh = File(tempDir, "fake-zsh").apply {
            writeText("#!/bin/sh\necho ok\n")
            setExecutable(true)
        }
        val macPlatform = MacosHostPlatform(paths, customShellPath = fakeZsh.absolutePath)
        assertEquals(OsKind.MACOS, macPlatform.profile.os)
        assertEquals("fake-zsh", macPlatform.profile.shellName)
        assertTrue(macPlatform.shell.probe().available)

        val env = macPlatform.createHostEnvironment()
        assertEquals(HostAccess.LOCAL, env.access)
        assertTrue(env.capabilities.bashAvailable)
        assertEquals(macPlatform, macPlatform)
        assertEquals(MacosServiceManager, HostServiceManagers.forPlatform(macPlatform))

        val trickyHome = PathLayout(File(tempDir, "A & <Home>"))
        val plist = MacosServiceManager.renderPlistContent(
            paths = trickyHome,
            port = 4321,
            execPrefix = listOf("/Applications/Super PI.app/Contents/MacOS/super-pi"),
        )
        assertTrue(plist.contains("<string>${MacosServiceManager.LABEL}</string>"))
        assertTrue(plist.contains("A &amp; &lt;Home&gt;"))
        assertTrue(plist.contains("<string>4321</string>"))
        assertTrue(plist.contains("/Applications/Super PI.app/Contents/MacOS/super-pi"))
        assertTrue(plist.contains("<key>RunAtLoad</key>"))
    }

    @Test
    fun windowsHostPlatformBuildsUtf8CommandPlansAndTaskXml() {
        val fakePwsh = File(tempDir, "pwsh.exe").apply { writeText("stub") }
        val fakeCmd = File(tempDir, "cmd.exe").apply { writeText("stub") }
        val winPlatform = WindowsHostPlatform(paths, customShellPath = fakePwsh.absolutePath)
        assertEquals(OsKind.WINDOWS, winPlatform.profile.os)
        assertTrue(winPlatform.shell.probe().available)
        assertEquals(WindowsServiceManager, HostServiceManagers.forPlatform(winPlatform))

        val pwshBackend = WindowsShellBackend(
            logsDir = paths.shellLogsDir,
            processKiller = winPlatform.processKiller,
            onRegisterProcess = { _, _ -> },
            onUnregisterProcess = {},
            customShellPath = fakePwsh.absolutePath,
        )
        val pwshPlan = pwshBackend.buildWindowsPlan(fakePwsh.absolutePath, "Get-ChildItem")
        assertEquals("-NoLogo", pwshPlan.argv[1])
        assertEquals("-EncodedCommand", pwshPlan.argv[4])
        val decodedScript = String(java.util.Base64.getDecoder().decode(pwshPlan.argv[5]), Charsets.UTF_16LE)
        assertTrue(decodedScript.contains("try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}"))
        assertTrue(decodedScript.contains("Get-ChildItem"))
        assertTrue(decodedScript.contains("LASTEXITCODE"))

        val cmdPlan = pwshBackend.buildWindowsPlan(fakeCmd.absolutePath, "dir /b")
        assertEquals(listOf(fakeCmd.absolutePath, "/d", "/s", "/c", "chcp 65001>nul 2>&1 & dir /b"), cmdPlan.argv)

        val batXml = WindowsServiceManager.renderTaskXml(
            paths = PathLayout(File(tempDir, "My & Space Home")),
            port = 8765,
            execPrefix = listOf("""C:\Program Files\SuperPI\bin\super-pi.bat"""),
        )
        assertTrue(batXml.contains("<?xml version=\"1.0\" encoding=\"UTF-16\"?>"))
        assertTrue(batXml.contains("My &amp; Space Home"))
        assertTrue(batXml.contains("--port 8765"))
        assertTrue(batXml.contains("<Command>"))
        assertFalse(batXml.contains("My & Space Home"))
    }

    @Test
    fun hostServiceManagerRoutesLinuxCorrectly() {
        val linux = LinuxHostPlatform(paths)
        assertEquals(LinuxServiceManager, HostServiceManagers.forPlatform(linux))
    }
}

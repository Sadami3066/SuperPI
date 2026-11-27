package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.host.platform.HostPlatforms
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPromptHygieneTest {
    private val tempDirs = mutableListOf<File>()

    private fun newTempDir(): File = createTempDirectory("superpi-prompt-hygiene").toFile().also {
        tempDirs += it
    }

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `desktop host environment and super orchestrator contain zero Android phone leaks`() = runBlocking {
        val home = newTempDir()
        val controller = SuperPiController(HostPlatforms.detect(home))
        try {
            controller.bootstrap()
            val env = controller.hostEnvironmentProvider.current()
            assertFalse("Desktop HostEnvironment must disable Android sharedStorage", env.sharedStorageEnabled)
            val envText = (env.promptLines + listOfNotNull(env.shellToolProfile?.toolDescription) + (env.shellToolProfile?.guidelines ?: emptyList())).joinToString("\n")
            assertFalse("Desktop env must not mention Android", envText.contains("Android"))
            assertFalse("Desktop env must not mention proot", envText.contains("proot"))
            assertFalse("Desktop env must not mention /storage/emulated/0", envText.contains("/storage/emulated/0"))
            assertTrue("Desktop env preserves shell description", envText.isNotBlank())

            val opened = controller.runtimeHost.newSession("home")
            val sessions = controller.runtimeHost.sessions("home")
            assertTrue(
                "Newly created session must be immediately visible in sessions(botId)",
                sessions.any { it.id == opened.sessionId },
            )
        } finally {
            controller.shutdown()
        }
    }
}

package io.github.whywood.superpi.host.config

import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.host.platform.PathLayout
import io.github.whywood.superpi.runtime.MemoryAccessScope
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BotRegistryTest {
    private lateinit var tempDir: File
    private lateinit var paths: PathLayout
    private lateinit var settingsStore: HostSettingsStore
    private lateinit var registry: BotRegistry

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-bot-test").toFile()
        paths = PathLayout(File(tempDir, ".super-pi"))
        paths.ensureDirectories()
        settingsStore = HostSettingsStore(paths.settingsFile)
        registry = BotRegistry(paths = paths, settingsStore = settingsStore)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun ensureDefaultHomeBotCreatesWorkspaceAndAgentsMd() = runBlocking {
        registry.ensureDefaultHomeBot()
        assertTrue(paths.defaultWorkspaceDir.isDirectory)
        assertTrue(File(paths.defaultWorkspaceDir, "AGENTS.md").isFile)
        val bots = registry.listAllBots()
        assertEquals(1, bots.size)
        assertTrue(bots.first().isHome)
    }

    @Test
    fun resolvesRegisteredBotAndNestedSubdirWithoutPollutingUnregisteredDirs() = runBlocking {
        registry.ensureDefaultHomeBot()
        val project = File(tempDir, "repo").apply { mkdirs() }
        val nested = File(project, "sub/deep").apply { mkdirs() }
        val ephemeral = File(tempDir, "ephemeral").apply { mkdirs() }
        File(project, "AGENTS.md").writeText("Project root rules", Charsets.UTF_8)
        File(ephemeral, "AGENTS.md").writeText("Base doc", Charsets.UTF_8)
        File(ephemeral, "AGENTS.override.md").writeText("Override doc", Charsets.UTF_8)

        val added = registry.addBot(project, name = "MyRepo")
        val exactMatch = registry.resolveForCwd(project)
        assertTrue(exactMatch.isRegisteredBot)
        assertEquals(added.id, exactMatch.workspace?.id)
        assertEquals("Project root rules", exactMatch.agentDoc?.content)

        val subMatch = registry.resolveForCwd(nested)
        assertTrue(subMatch.isRegisteredBot)
        assertEquals(added.id, subMatch.workspace?.id)
        assertEquals("Project root rules", subMatch.agentDoc?.content)

        val beforeBotsJson = if (paths.botsFile.isFile) paths.botsFile.readText() else ""
        val ephMatch = registry.resolveForCwd(ephemeral)
        assertFalse(ephMatch.isRegisteredBot)
        assertNull(ephMatch.workspace)
        assertEquals("Override doc", ephMatch.agentDoc?.content)
        val afterBotsJson = if (paths.botsFile.isFile) paths.botsFile.readText() else ""
        assertEquals("Resolving unregistered dir must not mutate bots.json", beforeBotsJson, afterBotsJson)
    }

    @Test
    fun syncPolicyResolutionReflectsUpdatedWorkspaceToolPolicy() = runBlocking {
        registry.ensureDefaultHomeBot()
        val project = File(tempDir, "work").apply { mkdirs() }
        registry.addBot(project, name = "WorkBot")

        val canonical = paths.normalizePath(project.absolutePath)
        registry.policyStore.update(
            canonical,
            WorkspaceToolPolicy(
                allCapabilities = true,
                mcpServers = setOf("blocked-mcp"),
                memoryScope = MemoryAccessScope.WORK_ONLY,
                memoryDispatch = false,
            ),
        )

        val (policy, isHome) = registry.resolvePolicySync(project.absolutePath)
        assertFalse(isHome)
        assertEquals(MemoryAccessScope.WORK_ONLY, policy.effectiveMemoryScope())
        assertFalse(policy.effectiveMemoryDispatch(isHome))
        assertTrue("blocked-mcp" in policy.mcpServers)
        assertNotNull(registry.findBot("WorkBot"))
    }
}

package io.github.whywood.superpi.host.extensions

import io.github.whywood.superpi.host.config.HostSettingsStore
import io.github.whywood.superpi.host.platform.PathLayout
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.runtime.McpServerProbe
import io.github.whywood.superpi.runtime.SkillCatalog
import io.github.whywood.superpi.sdk.ai.http.okHttpTransport
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import io.github.whywood.superpi.sdk.mcp.oauth.FileMcpTokenStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExtensionRegistryTest {
    private lateinit var tempDir: File
    private lateinit var paths: PathLayout
    private lateinit var settingsStore: HostSettingsStore
    private lateinit var mcpConfigStore: McpConfigStore
    private lateinit var registry: ExtensionRegistry

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("superpi-ext-test").toFile()
        paths = PathLayout(File(tempDir, ".super-pi"))
        paths.ensureDirectories()
        settingsStore = HostSettingsStore(paths.settingsFile)
        mcpConfigStore = McpConfigStore(paths.homeDir)
        val transport = okHttpTransport()
        val tokenStore = FileMcpTokenStore(paths.mcpAuthFile.absolutePath.toPath())
        val probe = McpServerProbe(transport, tokenStore)
        val skills = SkillCatalog(paths.homeDir)
        registry = ExtensionRegistry(
            paths = paths,
            settingsStore = settingsStore,
            mcpConfigStore = mcpConfigStore,
            mcpServerProbe = probe,
            skillCatalog = skills,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun discoversPromptsReservedManifestsAndMcpRespectingDisabledSettings() = runBlocking {
        File(paths.promptsDir, "review.md").writeText("# Code Review\nInspect diff for \$ARGUMENTS carefully.\n", Charsets.UTF_8)
        val extDir = File(paths.extensionsDir, "sample-ext").apply { mkdirs() }
        File(extDir, "extension.json").writeText(
            """{"id":"sample-ext","name":"Sample Extension","version":"0.2.0"}""",
            Charsets.UTF_8,
        )
        mcpConfigStore.upsert("local-tools", McpServerConfig.Http(url = "http://127.0.0.1:9999/mcp"))
        settingsStore.update { it.copy(disabledExtensions = listOf("builtin:web")) }

        val snap = registry.reload()
        assertTrue(snap.prompts.any { it.name == "review" })
        val template = registry.findPromptTemplate("review")
        assertNotNull(template)
        assertTrue(template!!.render("PR #42").contains("Inspect diff for PR #42 carefully."))

        val webExt = snap.descriptors.firstOrNull { it.id == "builtin:web" }
        assertNotNull(webExt)
        assertFalse(webExt!!.enabled)

        val mcpExt = snap.descriptors.firstOrNull { it.id == "mcp:local-tools" }
        assertNotNull(mcpExt)
        assertTrue(mcpExt!!.enabled)

        val reserved = snap.descriptors.firstOrNull { it.id == "ext:sample-ext" }
        assertNotNull(reserved)
        assertEquals(HostExtensionSource.EXTERNAL, reserved!!.source)
        assertFalse(reserved.enabled)
    }
}

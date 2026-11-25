package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.ai.auth.Credential
import io.github.whywood.superpi.sdk.ai.auth.CredentialInfo
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.http.HttpRequest
import io.github.whywood.superpi.sdk.ai.http.HttpResponse
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.FileMutationQueue
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellResult
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.runtime.host.HostAccessUnavailableException
import io.github.whywood.superpi.runtime.host.HostCapabilities
import io.github.whywood.superpi.runtime.host.FakeHostEnvironment
import io.github.whywood.superpi.runtime.host.HostEnvironmentProvider
import io.github.whywood.superpi.sdk.coding.tools.defaultCodingTools
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PiSessionEngineScopeTest {
    private val transport = object : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse = error("network is not used")
    }

    private val credentials = object : CredentialStore {
        override fun read(providerId: String): Credential? = null
        override fun readAll(): Map<String, Credential> = emptyMap()
        override fun readApiKey(providerId: String): String? = null
        override fun readEnv(providerId: String): Map<String, String>? = null
        override fun list(): List<CredentialInfo> = emptyList()
        override fun keys(): List<String> = emptyList()
        override fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential? = null
        override fun writeApiKey(providerId: String, key: String, env: Map<String, String>?) = Unit
        override fun write(providerId: String, credential: Credential) = Unit
        override fun writeRawCredential(providerId: String, credential: JsonElement) = Unit
        override fun delete(providerId: String) = Unit
        override fun readRaw(): Map<String, JsonElement> = emptyMap()
    }

    @Test
    fun temporarySessionFilesStayOutsideWorkspace() = runTest {
        val root = Files.createTempDirectory("superpi-engine-scope").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        val catalog = ProviderCatalog(transport, credentials)
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            shellExecutor = null,
            providers = catalog,
            fs = FileSystem.SYSTEM,
        )
        try {
            val session = engine.createSession(
                file = null,
                config = null,
                shellEnabled = false,
                toolsFilter = { tool -> miniappAgentToolVisible(tool.name) },
                mcpEnabled = false,
            )
            try {
                assertEquals(
                    listOf("edit", "read", "write"),
                    session.activeToolNames.sorted(),
                )
                val sessionFile = session.sessionFile
                assertNotNull(sessionFile)
                assertTrue(sessionFile.toString().startsWith(agentDir.absolutePath))
            } finally {
                session.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileToolsAccessAbsolutePathsOutsideWorkspace() = runTest {
        val root = Files.createTempDirectory("superpi-engine-tools").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        File(root, "outside.txt").writeText("outside secret")
        val catalog = ProviderCatalog(transport, credentials)
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            shellExecutor = null,
            providers = catalog,
            fs = FileSystem.SYSTEM,
        )
        try {
            assertEquals(
                listOf("edit", "read", "write"),
                buildMiniappAgentTools(engine.availableTools()).map { it.name },
            )
            File(workspace, "notes.txt").writeText("hello scope\n")
            File(workspace, "draft").mkdirs()
            File(workspace, "draft/page.html").writeText("<html></html>")
            val sourceFileTools = defaultCodingTools(engine.cwd, FileSystem.SYSTEM, FileMutationQueue())
                .filter { tool -> tool.name in setOf("ls", "find", "grep") }
                .map { tool -> tool.withAbsolutePaths() }
            val tools = (engine.availableTools() + sourceFileTools).associateBy { it.name }

            assertTrue(runTool(tools.getValue("ls"), buildJsonObject { put("path", workspace.absolutePath) }).contains("notes.txt"))
            assertTrue(
                runTool(tools.getValue("find"), buildJsonObject { put("pattern", "*.html"); put("path", workspace.absolutePath) })
                    .contains("draft/page.html"),
            )
            assertTrue(
                runTool(tools.getValue("grep"), buildJsonObject { put("pattern", "hello scope"); put("path", workspace.absolutePath) })
                    .contains("notes.txt"),
            )
            assertTrue(
                runTool(
                    tools.getValue("write"),
                    buildJsonObject {
                        put("path", File(workspace, "fresh.txt").absolutePath)
                        put("content", "written inside")
                    },
                ).isNotEmpty(),
            )
            assertEquals(
                "written inside",
                runTool(tools.getValue("read"), buildJsonObject { put("path", File(workspace, "fresh.txt").absolutePath) }).trim(),
            )

            val escape = runCatching {
                tools.getValue("read").execute("call", buildJsonObject { put("path", "../outside.txt") }, null, null)
            }.exceptionOrNull()
            assertTrue("expected absolute-path rejection but got $escape", escape is IllegalArgumentException)
            assertEquals("outside secret", runTool(tools.getValue("read"), buildJsonObject {
                put("path", File(root, "outside.txt").absolutePath)
            }).trim())

        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun engineRegistersCustomBashAndWorkspacePaths() = runTest {
        val root = Files.createTempDirectory("superpi-engine-shell").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        File(workspace, "notes.txt").writeText("hello")
        val catalog = ProviderCatalog(transport, credentials)
        val shell = object : ShellExecutor {
            override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult =
                ShellResult("ran $command\n", 0)
        }
        try {
            val engine = PiSessionEngine(
                workspace = workspace,
                agentDir = agentDir,
                credentials = credentials,
                transport = transport,
                shellExecutor = shell,
                providers = catalog,
            )
            val tools = engine.availableTools().associateBy { it.name }
            val bash = tools.getValue("bash")
            assertTrue(bash.description.contains("[exit: N]"))
            assertTrue(runTool(bash, buildJsonObject { put("command", "true"); put("description", "Verify shell") }).contains("[exit: 0]"))

            val read = tools.getValue("read")
            val prepared = read.prepareArguments!!.invoke(buildJsonObject { put("path", File(workspace, "notes.txt").absolutePath) })
            assertEquals(
                File(workspace, "notes.txt").absolutePath,
                prepared.getValue("path").jsonPrimitive.content,
            )
            assertEquals("hello", runTool(read, prepared).trim())

            val withoutShell = PiSessionEngine(
                workspace = workspace,
                agentDir = agentDir,
                credentials = credentials,
                transport = transport,
                shellExecutor = null,
                providers = catalog,
            )
            assertNull(withoutShell.availableTools().firstOrNull { it.name == "bash" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun createSessionFailsWhenRootEnvironmentIsNotReady() = runTest {
        val root = Files.createTempDirectory("superpi-engine-host").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            hostEnvironment = object : HostEnvironmentProvider {
                override fun current() = FakeHostEnvironment(
                    access = HostAccess.ROOT,
                    capabilities = HostCapabilities(isRoot = true, rootLinuxReady = false),
                )
            },
            providers = ProviderCatalog(transport, credentials),
            fs = FileSystem.SYSTEM,
        )
        try {
            val nonShellSession = engine.createSession(
                file = null,
                config = null,
                shellEnabled = false,
                mcpEnabled = false,
            )
            nonShellSession.close()

            val failure = runCatching {
                engine.createSession(
                    file = null,
                    config = null,
                    shellEnabled = true,
                    mcpEnabled = false,
                )
            }.exceptionOrNull()

            assertTrue(failure is HostAccessUnavailableException)
            assertEquals(
                "Root Linux is not ready; retry after preparation finishes",
                failure?.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun openSessionPreservesRestoredModelOverDefaultModelConfig() = runTest {
        val root = Files.createTempDirectory("superpi-engine-model").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        val catalog = ProviderCatalog(transport, credentials)
        val models = catalog.models("deepseek")
        assertTrue(models.size >= 2)
        val firstModel = models[0]
        val secondModel = models[1]
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            shellExecutor = null,
            providers = catalog,
            fs = FileSystem.SYSTEM,
        )
        try {
            val created = engine.createSession(
                file = null,
                config = ModelConfig("deepseek", firstModel.id, ThinkingLevel.OFF),
                shellEnabled = false,
                mcpEnabled = false,
            )
            val filePath = created.sessionFile!!
            created.sessionManager.appendMessage(
                UserMessage(content = PlainTextContent("hello"), timestamp = 1_000L),
            )
            created.sessionManager.appendModelChange("deepseek", firstModel.id)
            created.close()

            val reopened = engine.createSession(
                file = filePath,
                config = ModelConfig("deepseek", secondModel.id, ThinkingLevel.HIGH),
                shellEnabled = false,
                mcpEnabled = false,
            )
            try {
                assertEquals("deepseek", reopened.model.provider)
                assertEquals(firstModel.id, reopened.model.id)
            } finally {
                reopened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun superReadOnlyConfigExposesQueryToolsWithoutSkillsOrProjectContextLeak() = runTest {
        val root = Files.createTempDirectory("superpi-engine-super-ro").toFile()
        val workspace = File(root, "workspace").apply { mkdirs() }
        val agentDir = File(root, "agent").apply { mkdirs() }
        val skillDir = File(agentDir, "skills/secret-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: secret-skill\ndescription: Secret\n---\nbody")
        File(agentDir, "APPEND_SYSTEM.md").writeText("APPEND_SECRET")
        File(workspace, "AGENTS.md").writeText("Workspace instructions")
        File(workspace, "readme.txt").writeText("hello")
        val catalog = ProviderCatalog(transport, credentials)
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            shellExecutor = null,
            providers = catalog,
            fs = FileSystem.SYSTEM,
            includePromptResources = false,
            readOnlyPathsPrompt = true,
            enableReadOnlyQueryTools = true,
            defaultShellEnabled = false,
            defaultMcpEnabled = false,
            defaultToolsFilter = { it.name in setOf("read", "ls", "grep", "find") },
        )
        try {
            val session = engine.createSession(
                file = null,
                config = ModelConfig(),
                shellEnabled = false,
                toolsFilter = { it.name in setOf("read", "ls", "grep", "find") },
                mcpEnabled = false,
            )
            try {
                assertEquals(
                    listOf("find", "grep", "ls", "read"),
                    session.activeToolNames.sorted(),
                )
                assertFalse(session.systemPrompt.contains("secret-skill"))
                assertFalse(session.systemPrompt.contains("Workspace instructions"))
                assertFalse(session.systemPrompt.contains("APPEND_SECRET"))
                assertTrue(engine.capabilitySnapshot(session).skills.isEmpty())
            } finally {
                session.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private suspend fun runTool(tool: CodingTool, args: JsonObject): String {
        val result: AgentToolResult = tool.execute("call", args, null, null)
        return result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    }
}

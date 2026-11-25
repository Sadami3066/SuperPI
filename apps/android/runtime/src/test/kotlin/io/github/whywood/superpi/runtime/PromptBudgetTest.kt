package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEventDraft
import io.github.whywood.superpi.runtime.MemoryEventKind
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.ai.auth.ApiKeyCredential
import io.github.whywood.superpi.sdk.ai.auth.Credential
import io.github.whywood.superpi.sdk.ai.auth.CredentialInfo
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.http.HttpRequest
import io.github.whywood.superpi.sdk.ai.http.HttpResponse
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellResult
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromptBudgetTest {
    private lateinit var directory: File
    private lateinit var cwd: Path

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("superpi-prompt-budget").toFile()
        cwd = directory.absolutePath.toPath()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun personalizationInstructionIsCappedAt4000Chars() {
        val raw = "I".repeat(MAX_PERSONALIZATION_INSTRUCTION_CHARS + 500)
        val sections = buildPersonalizationSections(
            personalization = Personalization(instruction = raw),
            providerId = "deepseek",
            modelId = "deepseek-chat",
            now = OffsetDateTime.parse("2026-10-23T12:00:00+08:00"),
        )
        val instruction = sections.getValue("instruction")
        assertTrue(instruction.contains("I".repeat(MAX_PERSONALIZATION_INSTRUCTION_CHARS)))
        assertFalse(instruction.contains("I".repeat(MAX_PERSONALIZATION_INSTRUCTION_CHARS + 1)))
    }

    @Test
    fun pluginPromptOverridesTotalBudgetBoundsLegacyBody() {
        val merge = mergePluginPromptOverrides(
            overrides = PluginPromptOverrides(
                listOf(
                    PluginPromptContribution(
                        pluginId = "p1",
                        priority = 0,
                        sections = listOf(PluginPromptSection("p1", "s1", "hello", 1)),
                    ),
                ),
            ),
            legacyBody = "L".repeat(PLUGIN_PROMPT_TOTAL_BUDGET + 200),
            warn = {},
        )
        assertTrue(merge.pluginsBody.orEmpty().length <= PLUGIN_PROMPT_TOTAL_BUDGET)
        assertTrue(merge.pluginsBody.orEmpty().contains("...(truncated)"))
    }

    @Test
    fun piSessionEngineCapsPluginContextAndKeepsMemoryPrefixImmutableUntilCompactionOrColdIdle() = runBlocking {
        val workspace = File(directory, "workspace").apply { mkdirs() }
        val agentDir = File(directory, "agent").apply { mkdirs() }
        var nowMs = 1_760_000_000_000L
        val memoryStore = MemoryStore(File(directory, "memory"), clock = { nowMs })
        memoryStore.ensure()
        memoryStore.saveEvent(
            MemoryEventDraft(
                eventKind = MemoryEventKind.PREFERENCE,
                content = "Initial coffee preference",
                epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
            ),
        )

        val recordedSystemPrompts = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): HttpResponse {
                val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
                val systemPrompt = (body["messages"] as? JsonArray)
                    ?.filter {
                        val role = (it.jsonObject["role"] as? JsonPrimitive)?.content
                        role == "system" || role == "developer"
                    }
                    ?.joinToString("\n") { it.jsonObject["content"]?.toString().orEmpty() }
                    .orEmpty()
                if (systemPrompt.isNotBlank()) recordedSystemPrompts += systemPrompt
                val payload = "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"summary response\"},\"finish_reason\":\"stop\"}]}"
                return HttpResponse(
                    200,
                    mapOf("content-type" to "text/event-stream"),
                    Buffer().writeUtf8("data: $payload\n\ndata: [DONE]\n\n"),
                )
            }
        }
        val credentials = object : CredentialStore {
            override fun read(providerId: String): Credential = ApiKeyCredential(key = "test-key")
            override fun readAll(): Map<String, Credential> = mapOf("deepseek" to ApiKeyCredential(key = "test-key"))
            override fun readApiKey(providerId: String): String = "test-key"
            override fun readEnv(providerId: String): Map<String, String>? = null
            override fun list(): List<CredentialInfo> = emptyList()
            override fun keys(): List<String> = listOf("deepseek")
            override fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential? = null
            override fun writeApiKey(providerId: String, key: String, env: Map<String, String>?) = Unit
            override fun write(providerId: String, credential: Credential) = Unit
            override fun writeRawCredential(providerId: String, credential: JsonElement) = Unit
            override fun delete(providerId: String) = Unit
            override fun readRaw(): Map<String, JsonElement> = emptyMap()
        }
        val catalog = ProviderCatalog(transport, credentials)
        val engine = PiSessionEngine(
            workspace = workspace,
            agentDir = agentDir,
            credentials = credentials,
            transport = transport,
            shellExecutor = NoopShell,
            providers = catalog,
            fs = FileSystem.SYSTEM,
            memory = memoryStore,
            clock = { nowMs },
            hooks = RuntimeHooks(
                systemContext = { "<plugin_context plugin=\"demo\">\n" + "P".repeat(9_000) + "\n</plugin_context>" },
            ),
        )
        val model = catalog.defaultModel("deepseek")!!
        val session = engine.createSession(
            file = null,
            config = ModelConfig("deepseek", model.id, ThinkingLevel.OFF),
            shellEnabled = false,
            mcpEnabled = false,
        )
        try {
            session.prompt("turn 1 " + "A".repeat(40_000))
            val turn1Prompt = recordedSystemPrompts.first()
            assertTrue(turn1Prompt.contains("<plugin_context plugin=\\\"demo\\\">"))
            assertFalse(turn1Prompt.contains("&lt;plugin_context"))
            assertFalse(turn1Prompt.contains("<plugin_context>\\n<plugin_context"))
            val startIdx = turn1Prompt.indexOf("<plugin_context plugin=")
            val endIdx = turn1Prompt.indexOf("</plugin_context>", startIdx)
            assertTrue(endIdx > startIdx)
            assertTrue((endIdx - startIdx + "</plugin_context>".length) <= 8_050)
            assertTrue(turn1Prompt.contains("Initial coffee preference"))

            memoryStore.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.PREFERENCE,
                    content = "Mid-session updated tea preference",
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                ),
            )
            nowMs += 5 * 60_000L
            session.prompt("turn 2 " + "B".repeat(90_000))
            val turn2Prompt = recordedSystemPrompts[1]
            assertTrue(turn2Prompt.contains("Initial coffee preference"))
            assertFalse(turn2Prompt.contains("Mid-session updated tea preference"))

            assertTrue(session.compact() != null)
            val promptsBeforeTurn3 = recordedSystemPrompts.size
            session.prompt("turn 3")
            val turn3Prompt = recordedSystemPrompts[promptsBeforeTurn3]
            assertTrue(turn3Prompt.contains("Mid-session updated tea preference"))

            memoryStore.saveEvent(
                MemoryEventDraft(
                    eventKind = MemoryEventKind.PREFERENCE,
                    content = "Post-idle espresso preference",
                    epistemicTier = MemoryEpistemicTier.USER_EXPLICIT,
                ),
            )
            nowMs += 5 * 60_000L
            session.prompt("turn 4")
            assertFalse(recordedSystemPrompts.last().contains("Post-idle espresso preference"))

            nowMs += 46 * 60_000L
            session.prompt("turn 5")
            assertTrue(recordedSystemPrompts.last().contains("Post-idle espresso preference"))
        } finally {
            session.close()
            memoryStore.close()
        }
    }

    private object NoopShell : ShellExecutor {
        override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult =
            ShellResult(output = "", exitCode = 0)
    }
}

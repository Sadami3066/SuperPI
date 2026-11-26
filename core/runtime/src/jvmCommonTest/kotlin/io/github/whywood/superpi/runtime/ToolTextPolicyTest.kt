package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.runtime.memory.MemoryAgentGateway
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryAgentStepResult
import io.github.whywood.superpi.runtime.memory.MemoryModelTier
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolTextPolicyTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        root = Files.createTempDirectory("superpi-tool-text").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun runtimeToolTextStaysTaskOriented() {
        val allowedSnippets = setOf(
            "present", "todo_write", "memory_search", "memory_inspect_entity",
            "memory_read_archive", "memory_dispatch", "tool_search", "tool_execute",
        )
        tools().forEach { tool ->
            assertTrue("${tool.name} description is empty", tool.description.isNotBlank())
            if (tool.name !in allowedSnippets) {
                assertNull("${tool.name} should not send promptSnippet", tool.promptSnippet)
            } else {
                val snippet = tool.promptSnippet.orEmpty()
                assertTrue("${tool.name} promptSnippet is blank", snippet.isNotBlank())
                assertFalse("${tool.name} promptSnippet should stay one line", snippet.contains("\n"))
                assertFalse(
                    "${tool.name} promptSnippet must not duplicate tool name prefix",
                    snippet.lowercase().startsWith("${tool.name.lowercase()}:"),
                )
            }
            tool.promptGuidelines.orEmpty().forEach { guideline ->
                assertTrue("${tool.name} guideline is empty", guideline.isNotBlank())
                assertFalse("${tool.name} guideline should stay one line", guideline.contains("\n"))
            }
            scan(tool.name, tool.description)
            scan(tool.name, tool.parameters)
        }
    }

    @Test
    fun presentAdvertisesCardDelivery() {
        val present = presentTool(null) { null }

        assertTrue(present.promptSnippet.orEmpty().contains("card"))
        assertTrue(present.description.contains("type=card"))
    }

    private fun scan(tool: String, text: String) {
        assertTrue("$tool contains empty guidance", text.isNotBlank())
    }

    private fun scan(tool: String, element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                scan(tool, key)
                scan(tool, value)
            }
            is JsonArray -> element.forEach { scan(tool, it) }
            is JsonPrimitive -> scan(tool, element.toString())
        }
    }

    private fun tools(): List<CodingTool> {
        val binding = SessionBinding()
        val registry = JobRegistry(scope = scope, notifier = { _, _, _ -> })
        val store = MiniappStore(File(root, "miniapp"))
        val timers = TimerService(
            io.github.whywood.superpi.runtime.testing.FakeAgentRuntime(),
            TimerStore(File(root, "timers")),
            scope,
        )
        val memoryStore = MemoryStore(File(root, "memory"))
        val memoryAgent = MemoryAgentService(
            store = memoryStore,
            gateway = object : MemoryAgentGateway {
                override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult =
                    MemoryAgentStepResult.NoModel
            },
            scope = scope,
        )
        return buildList {
            add(askUserQuestionTool(QuestionCenter(), binding))
            add(presentTool(null) { null })
            addAll(goalTools(binding, GoalService()))
            addAll(jobTools(binding, registry))
            addAll(timerTools(timers, binding))
            addAll(miniappTools(store))
            add(miniappDataTool(store))
            add(todoWriteTool({ "session-1" }) { _, _, _, _ -> true })
            addAll(
                memoryTools(
                    store = memoryStore,
                    agentService = memoryAgent,
                    allowDispatch = { true },
                    sessionId = { "session-1" },
                ),
            )
            addAll(deferredTools(AppToolContext(QuestionCenter()), binding))
            add(
                systemShellTool {
                    object : ShellExecutor {
                        override suspend fun run(
                            command: String,
                            timeoutSeconds: Long?,
                            signal: RunSignal?,
                        ): ShellResult = ShellResult(output = "", exitCode = 0, truncated = false)
                    }
                },
            )
        }
    }

}

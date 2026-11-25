package io.github.whywood.superpi.plugin.host

import io.github.whywood.superpi.arrange.ArrangeCalendarGateway
import io.github.whywood.superpi.arrange.arrangeTools
import io.github.whywood.superpi.runtime.AppToolContext
import io.github.whywood.superpi.runtime.GoalService
import io.github.whywood.superpi.runtime.JobRegistry
import io.github.whywood.superpi.runtime.MiniappStore
import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.TimerService
import io.github.whywood.superpi.runtime.TimerStore
import io.github.whywood.superpi.runtime.appTools
import io.github.whywood.superpi.runtime.goalTools
import io.github.whywood.superpi.runtime.jobTools
import io.github.whywood.superpi.runtime.memory.MemoryAgentGateway
import io.github.whywood.superpi.runtime.memory.MemoryAgentService
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.runtime.miniappDataTool
import io.github.whywood.superpi.runtime.miniappTools
import io.github.whywood.superpi.runtime.presentTool
import io.github.whywood.superpi.runtime.superagent.WorkerOrchestrationGateway
import io.github.whywood.superpi.runtime.superagent.workerOrchestrationTool
import io.github.whywood.superpi.runtime.systemShellTool
import io.github.whywood.superpi.runtime.timerTools
import io.github.whywood.superpi.runtime.todoWriteTool
import io.github.whywood.superpi.screen.ScreenController
import io.github.whywood.superpi.screen.screenTools
import io.github.whywood.superpi.sdk.coding.tools.FileMutationQueue
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellResult
import io.github.whywood.superpi.sdk.coding.tools.defaultCodingTools
import io.github.whywood.superpi.vscreen.VirtualScreenAccess
import io.github.whywood.superpi.vscreen.virtualScreenTools
import io.github.whywood.superpi.web.WebProvider
import io.github.whywood.superpi.web.webTools
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostToolNamesTest {
    private val expected = setOf(
        "read", "write", "edit", "bash", "ls", "grep", "find",
        "ask_user_question", "present", "system_shell",
        "goal", "job", "timer",
        "miniapp_create", "miniapp_load", "miniapp_list", "miniapp_delete", "miniapp_data",
        "todo_write",
        "memory_search", "memory_inspect_entity", "memory_read_archive", "memory_dispatch",
        "web_search", "web_fetch", "calendar", "virtual_screen",
        "screen_status", "screen_observe", "screen_screenshot", "screen_tap", "screen_swipe",
        "screen_scroll", "screen_type", "screen_key", "screen_wait", "screen_list_apps", "screen_launch", "screen_batch",
        "worker",
        "tool_search", "tool_execute",
        "list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource",
    )

    @Test
    fun reservedNamesMatchRegisteredTools() {
        assertEquals(expected.sorted(), HostToolNames.names.sorted())
    }

    @Test
    fun reservesMcpPrefix() {
        assertTrue(HostToolNames.isReserved("mcp__server__tool"))
        assertFalse(HostToolNames.isReserved("mcpx"))
    }

    @Test
    fun pluginOwnedNamesAreNotReserved() {
        assertFalse(HostToolNames.isReserved("sample_echo"))
        assertFalse(HostToolNames.isReserved("plugin_note"))
    }

    @Test
    fun conflictsFiltersAndSorts() {
        assertEquals(
            listOf("bash", "job"),
            HostToolNames.conflicts(listOf("job", "sample_echo", "bash")).toList(),
        )
    }

    @Test
    fun liveHostToolFactoriesMatchReservedNamesAndTextPolicy() {
        val root = Files.createTempDirectory("host-tool-names-parity").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val binding = SessionBinding()
            val noopShell = object : ShellExecutor {
                override suspend fun run(
                    command: String,
                    timeoutSeconds: Long?,
                    signal: io.github.whywood.superpi.sdk.ai.RunSignal?,
                ): ShellResult = ShellResult("", 0)
            }
            val defaults = defaultCodingTools(
                cwd = root.absolutePath.toPath(),
                fs = FileSystem.SYSTEM,
                mutationQueue = FileMutationQueue(),
                shellExecutor = noopShell,
            ).filter { it.name !in setOf("ls", "grep", "find") }
            val store = MiniappStore(File(root, "miniapp"))
            val memoryStore = MemoryStore(File(root, "memory"))
            val memoryAgent = MemoryAgentService(
                store = memoryStore,
                gateway = noopProxy<MemoryAgentGateway>(),
                scope = scope,
            )
            val registry = JobRegistry(scope = scope, notifier = { _, _, _ -> })
            val timers = TimerService(noopProxy<io.github.whywood.superpi.runtime.AgentRuntime>(), TimerStore(File(root, "timers")), scope)
            val appBuiltIns = listOf(
                webTools(noopProxy<WebProvider>()),
                arrangeTools(noopProxy<ArrangeCalendarGateway>()),
                screenTools(noopProxy<ScreenController>(), File(root, "shots")),
                virtualScreenTools(noopProxy<VirtualScreenAccess>()),
                listOf(workerOrchestrationTool(noopProxy<WorkerOrchestrationGateway>())),
            ).flatten()
            val context = AppToolContext(
                questionCenter = QuestionCenter(),
                extraToolFactories = listOf({ _, sessionBinding ->
                    buildList {
                        add(presentTool(miniappLookup = null, session = { sessionBinding.current() }))
                        add(systemShellTool { noopShell })
                        add(todoWriteTool({ "s-1" }) { _, _, _, _ -> true })
                        addAll(goalTools(sessionBinding, GoalService()))
                        addAll(jobTools(sessionBinding, registry))
                        addAll(timerTools(timers, sessionBinding))
                        addAll(miniappTools(store))
                        add(miniappDataTool(store))
                        addAll(
                            memoryTools(
                                store = memoryStore,
                                agentService = memoryAgent,
                                allowDispatch = { true },
                                sessionId = { "s-1" },
                            ),
                        )
                        addAll(appBuiltIns)
                    }
                }),
            )
            val allLiveTools = defaults + appTools(root.absolutePath.toPath(), context, binding)
            val missingFromReserved = allLiveTools.map { it.name }.filterNot { it in HostToolNames.names }
            assertEquals(emptyList<String>(), missingFromReserved)
            appBuiltIns.forEach { tool ->
                assertTrue("${tool.name} description blank", tool.description.isNotBlank())
                tool.promptSnippet?.let { snippet ->
                    assertFalse(
                        "${tool.name} promptSnippet must not duplicate tool prefix",
                        snippet.lowercase().startsWith("${tool.name.lowercase()}:"),
                    )
                }
                tool.promptGuidelines.orEmpty().forEach { line ->
                    assertTrue("${tool.name} guideline blank", line.isNotBlank())
                    assertFalse("${tool.name} guideline multiline", line.contains("\n"))
                }
            }
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private inline fun <reified T> noopProxy(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> error("not called") } as T
}

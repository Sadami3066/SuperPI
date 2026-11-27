package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.runtime.superagent.WorkerOrchestrationGateway
import io.github.whywood.superpi.runtime.superagent.workerOrchestrationTool
import io.github.whywood.superpi.runtime.testing.FakeAgentRuntime
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPromptToolsTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `desktop-configured tools and paths emit zero Android phone strings`() {
        val home = createTempDirectory("superpi-desktop-tools").toFile().also { tempDirs += it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val timerService = TimerService(FakeAgentRuntime(), TimerStore(File(home, "timers")), scope = scope)
            val dummyGateway = object : WorkerOrchestrationGateway {
                override suspend fun startWorker(
                    botQuery: String,
                    task: String,
                    attachmentPaths: List<String>,
                ): WorkerRunRecord = error("unused")
                override suspend fun continueWorker(
                    workerId: String,
                    message: String,
                    attachmentPaths: List<String>,
                ): WorkerRunRecord = error("unused")
                override suspend fun steerWorker(workerId: String, message: String): WorkerRunRecord = error("unused")
                override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult = error("unused")
                override suspend fun stopWorker(workerId: String): WorkerRunRecord = error("unused")
                override suspend fun deleteWorker(workerId: String) = Unit
            }
            val tools = listOf(
                superPiLsTool(FileSystem.SYSTEM, supportsRootAccess = false),
                superPiGrepTool(FileSystem.SYSTEM, supportsRootAccess = false),
                superPiFindTool(FileSystem.SYSTEM, supportsRootAccess = false),
                presentTool(miniappLookup = null, session = { null }, cardExists = { false }),
                workerOrchestrationTool(dummyGateway),
            ) + timerTools(timerService, SessionBinding(), hasCalendarTool = false)

            val toolText = tools.joinToString("\n") { "${it.name}: ${it.description} ${it.parameters}" }
            assertFalse("Tools must not contain Android", toolText.contains("Android"))
            assertFalse("Tools must not contain phone", toolText.contains("phone"))
            assertFalse("Tools must not contain Root access", toolText.contains("Root access"))

            val paths = buildPathsSection(
                skillsDir = File(home, "skills").absolutePath,
                appDataDir = home.absolutePath,
                sharedStorage = false,
                mounts = emptyList(),
                readOnly = false,
                sharedStorageRoot = null,
            )
            assertFalse("Desktop paths must not contain /storage/emulated/0", paths.contains("/storage/emulated/0"))
            assertTrue("Require Windows drive paths", requireAbsolutePath("C:/Users/demo/file.txt").isNotEmpty())
        } finally {
            scope.cancel()
        }
    }
}

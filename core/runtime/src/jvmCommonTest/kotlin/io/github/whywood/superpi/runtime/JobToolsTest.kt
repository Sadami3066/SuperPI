package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobToolsTest {
    private fun AgentToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }

    @Test
    fun startListKillAndOutputTailTruncationWorkEndToEnd() = runTest {
        val largeOutput = (1..2_200).joinToString("\n") { "L$it" }
        val runner = BackgroundCommandRunner { _, sink ->
            sink(largeOutput)
            0
        }
        val registry = JobRegistry(backgroundScope, runner, notifier = { _, _, _ -> })
        val binding = SessionBinding().apply {
            bind(BoundSession("s-job", null, "/tmp"))
        }
        val tool = jobTools(binding, registry).single()

        val started = tool.execute(
            "j1",
            buildJsonObject { put("action", "start"); put("command", "emit large log"); put("label", "biglog") },
            null,
            null,
        )
        assertNull(started.isError)
        assertTrue(started.text().contains("Use job with action=output"))
        val jobId = Regex("job ([^.\\s]+)\\.").find(started.text())!!.groupValues[1]

        runCurrent()

        val listed = tool.execute("j2", buildJsonObject { put("action", "list") }, null, null)
        assertNull(listed.isError)
        assertTrue(listed.text().contains("biglog"))

        val output = tool.execute(
            "j3",
            buildJsonObject { put("action", "output"); put("jobId", jobId) },
            null,
            null,
        )
        assertNull(output.isError)
        val text = output.text()
        assertTrue(text.contains("[output truncated to the last"))
        assertTrue(text.contains("L2200"))
        assertTrue(!text.contains("\nL1\n") && !text.startsWith("L1\n"))
        assertTrue(text.contains("[status: done exit=0]"))
        assertTrue(text.encodeToByteArray().size <= 55 * 1024)

        val killAfterDone = tool.execute(
            "j4",
            buildJsonObject { put("action", "kill"); put("jobId", jobId) },
            null,
            null,
        )
        assertNull(killAfterDone.isError)
        assertTrue(killAfterDone.text().contains("already finished"))
    }
}

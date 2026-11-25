package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.runtime.testing.FakeAgentRuntime
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.TextContent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimerToolsTest {
    private lateinit var root: File
    private lateinit var store: TimerStore
    private lateinit var runtime: FakeAgentRuntime

    @Before
    fun setUp() {
        root = Files.createTempDirectory("superpi-timer-tools").toFile()
        store = TimerStore(File(root, "timers"))
        runtime = FakeAgentRuntime()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun AgentToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun AgentToolResult.json(): JsonObject =
        Json.parseToJsonElement(text()).jsonObject

    @Test
    fun supportsFullTimerCrudAndRejectsInvalidOperations() = runTest {
        val service = TimerService(runtime, store, backgroundScope)
        val sessionFile = File(root, "session.jsonl").apply { writeText("") }.absolutePath
        val binding = SessionBinding().apply {
            bind(BoundSession(sessionId = "s-1", sessionFile = sessionFile, cwd = root.absolutePath))
        }
        val tool = timerTools(service, binding).single()
        assertEquals("timer", tool.name)

        val createdRes = tool.execute(
            "t1",
            buildJsonObject {
                put("action", "create")
                put("prompt", "喝水提醒")
                put("frequency", "once")
                put("date", "2028-05-01")
                put("time", "09:30")
            },
            null,
            null,
        )
        assertNull(createdRes.text(), createdRes.isError)
        val created = createdRes.json()
        val id = created.getValue("id").jsonPrimitive.content
        assertEquals("once", created.getValue("trigger").jsonObject.getValue("type").jsonPrimitive.content)

        val listedRes = tool.execute("t2", buildJsonObject { put("action", "list") }, null, null)
        assertNull(listedRes.isError)
        assertEquals(1, listedRes.json().getValue("timers").jsonArray.size)

        val noFieldUpdate = tool.execute(
            "t3",
            buildJsonObject { put("action", "update"); put("id", id) },
            null,
            null,
        )
        assertEquals(true, noFieldUpdate.isError)
        assertTrue(noFieldUpdate.text().contains("requires at least one changed field"))

        val updatedRes = tool.execute(
            "t4",
            buildJsonObject { put("action", "update"); put("id", id); put("name", "早上喝水") },
            null,
            null,
        )
        assertNull(updatedRes.text(), updatedRes.isError)
        assertEquals("早上喝水", updatedRes.json().getValue("name").jsonPrimitive.content)

        val deletedRes = tool.execute(
            "t5",
            buildJsonObject { put("action", "delete"); put("id", id) },
            null,
            null,
        )
        assertNull(deletedRes.isError)
        assertEquals(true, deletedRes.json().getValue("deleted").jsonPrimitive.boolean)
        assertEquals(id, deletedRes.json().getValue("id").jsonPrimitive.content)

        val missingDelete = tool.execute(
            "t6",
            buildJsonObject { put("action", "delete"); put("id", id) },
            null,
            null,
        )
        assertEquals(true, missingDelete.isError)
        assertTrue(missingDelete.text().contains("Timer not found"))
    }
}

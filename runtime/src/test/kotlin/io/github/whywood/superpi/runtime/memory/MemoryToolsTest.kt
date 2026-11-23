package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryKind
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryToolsTest {
    private lateinit var rootDir: File
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("mem-tools-test").toFile()
        store = MemoryStore(rootDir)
    }

    @After
    fun tearDown() = runTest {
        store.close()
        rootDir.deleteRecursively()
    }

    @Test
    fun blocksSaveWhenSessionExcludedAndSupportsSaveSearchForgetOverview() = runTest {
        var excluded = true
        val tools = memoryTools(store, sessionId = { "session-a" }, isSessionExcluded = { excluded })
        val save = tools.first { it.name == "memory_save" }
        val search = tools.first { it.name == "memory_search" }
        val forget = tools.first { it.name == "memory_forget" }
        val overview = tools.first { it.name == "memory_overview" }

        val rejected = save.execute(
            "c1",
            buildJsonObject {
                put("content", JsonPrimitive("测试不记入记忆"))
                put("kind", JsonPrimitive("IDENTITY"))
            },
            null,
            null,
        )
        assertTrue(rejected.textContent().contains("paused"))
        assertTrue(store.list().isEmpty())

        excluded = false
        val saved = save.execute(
            "c2",
            buildJsonObject {
                put("content", JsonPrimitive("周日下午打羽毛球"))
                put("kind", JsonPrimitive("ACTIVE"))
                put("tags", buildJsonArray { add(JsonPrimitive("运动")) })
                put("entities", buildJsonArray { add(JsonPrimitive("羽毛球")) })
            },
            null,
            null,
        )
        assertTrue(saved.textContent().contains("Saved"))

        val found = search.execute(
            "c3",
            buildJsonObject {
                put("queries", buildJsonArray { add(JsonPrimitive("羽毛球")) })
            },
            null,
            null,
        )
        assertTrue(found.textContent().contains("周日下午打羽毛球"))

        val summary = overview.execute("c4", buildJsonObject {}, null, null)
        assertTrue(summary.textContent().contains("Active memories: 1"))

        val id = store.list().single().id
        val forgotten = forget.execute(
            "c5",
            buildJsonObject { put("id", JsonPrimitive(id)) },
            null,
            null,
        )
        assertTrue(forgotten.textContent().contains("Forgot"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun forgetQueryRequiresConfidentSingleMatchAndSearchStrictlyFiltersEntity() = runTest {
        val tools = memoryTools(store, sessionId = { "session-b" }, isSessionExcluded = { false })
        val save = tools.first { it.name == "memory_save" }
        val search = tools.first { it.name == "memory_search" }
        val forget = tools.first { it.name == "memory_forget" }

        save.execute(
            "s1",
            buildJsonObject {
                put("content", JsonPrimitive("出差提前预订高铁车票"))
                put("kind", JsonPrimitive("HABIT"))
                put("entities", buildJsonArray { add(JsonPrimitive("张三")) })
            },
            null,
            null,
        )
        save.execute(
            "s2",
            buildJsonObject {
                put("content", JsonPrimitive("出差喜欢住靠地铁的酒店"))
                put("kind", JsonPrimitive("HABIT"))
                put("entities", buildJsonArray { add(JsonPrimitive("李四")) })
            },
            null,
            null,
        )

        val scoped = search.execute(
            "q1",
            buildJsonObject {
                put("queries", buildJsonArray { add(JsonPrimitive("出差")) })
                put("entity", JsonPrimitive("张三"))
            },
            null,
            null,
        ).textContent()
        assertTrue(scoped.contains("高铁车票"))
        assertTrue(!scoped.contains("靠地铁的酒店"))

        val ambiguousForget = forget.execute(
            "f1",
            buildJsonObject { put("query", JsonPrimitive("出差")) },
            null,
            null,
        ).textContent()
        assertTrue(ambiguousForget.contains("exact id(s)"))
        assertTrue(store.list().size == 2)

        val clearForget = forget.execute(
            "f2",
            buildJsonObject { put("query", JsonPrimitive("高铁车票")) },
            null,
            null,
        ).textContent()
        assertTrue(clearForget.contains("Forgot 1 memory item"))
        assertTrue(store.list().single().content.contains("酒店"))
    }

    private fun io.github.whywood.superpi.sdk.agent.AgentToolResult.textContent(): String =
        content.filterIsInstance<io.github.whywood.superpi.sdk.ai.TextContent>().joinToString("\n") { it.text }
}

package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.coding.tools.textResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredToolsTest {
    private fun tool(
        name: String,
        description: String,
        source: String = "plugin:demo",
        parameters: JsonObject = buildJsonObject { put("type", "object") },
        execute: suspend (String, JsonObject) -> AgentToolResult = { _, _ -> textResult("ok:$name") },
    ) = DeferredTool(
        name = name,
        description = description,
        parameters = parameters,
        source = source,
        execute = { id, args, _, _ -> execute(id, args) },
    )

    private fun AgentToolResult.text(): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }

    @Test
    fun catalogSearchesByNameAndFindsExactTool() {
        val catalog = DeferredToolCatalog {
            listOf(
                tool("mail_send", "send an email message"),
                tool("calendar_read", "read calendar events"),
            )
        }
        assertEquals("mail_send", catalog.search("mail").first().name)
        assertEquals("calendar_read", catalog.find("calendar_read")?.name)
        assertNull(catalog.find("missing"))
    }

    @Test
    fun catalogSearchRespectsLimit() {
        val catalog = DeferredToolCatalog {
            (1..12).map { tool("tool_$it", "utility $it") }
        }
        assertTrue(catalog.search("utility", limit = 3).size <= 3)
    }

    @Test
    fun buildDeferredToolsSectionIsNullWhenEmptyAndSortedWhenPopulated() {
        assertNull(buildDeferredToolsSection(emptyList()))
        val section = buildDeferredToolsSection(
            listOf(
                tool("ride_estimate", "estimate", source = "plugin:ride"),
                tool("amap_weather", "weather", source = "plugin:amap"),
                tool("amap_regeo", "regeo", source = "plugin:amap"),
            ),
        ).orEmpty()
        assertTrue(section.startsWith("<deferred_tools>"))
        assertTrue(section.endsWith("</deferred_tools>"))
        assertTrue(section.contains("- amap: amap_regeo, amap_weather"))
        assertTrue(section.contains("- ride: ride_estimate"))
        assertTrue(section.indexOf("- amap:") < section.indexOf("- ride:"))
    }

    @Test
    fun toolSearchHardeningHandlesEmptyBrowseSourcesLimitsAndTruncation() = runTest {
        val emptySearch = deferredTools(AppToolContext(QuestionCenter()), SessionBinding())
            .first { it.name == "tool_search" }
        val emptyResult = emptySearch.execute("s0", buildJsonObject { put("query", "mail") }, null, null)
        assertNull(emptyResult.isError)
        assertEquals("No deferred tools are configured in this session.", emptyResult.text())

        val catalog = (1..25).map { idx ->
            tool(
                name = "alpha_tool_$idx",
                description = "A".repeat(300) + " item$idx",
                source = if (idx <= 12) "plugin:amap" else "plugin:mail",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("payload") {
                            put("type", "string")
                            put("description", "P".repeat(400))
                        }
                    }
                },
            )
        }
        val context = AppToolContext(QuestionCenter(), pluginDeferredTools = { catalog })
        val search = deferredTools(context, SessionBinding()).first { it.name == "tool_search" }

        val defaultRes = search.execute("s1", buildJsonObject { put("query", "*") }, null, null)
        assertNull(defaultRes.isError)
        assertTrue(defaultRes.text().contains("Matched 8 deferred tool(s)"))
        assertFalse(defaultRes.text().contains("A".repeat(250)))
        assertTrue(defaultRes.text().contains("..."))

        val maxLimitRes = search.execute(
            "s2",
            buildJsonObject { put("query", "all"); put("limit", 100) },
            null,
            null,
        )
        assertNull(maxLimitRes.isError)
        assertTrue(maxLimitRes.text().contains("Matched 20 deferred tool(s)"))
        assertTrue(maxLimitRes.text().length <= 12_200)

        val zeroMatch = search.execute("s3", buildJsonObject { put("query", "nonexistent_xyz") }, null, null)
        assertNull(zeroMatch.isError)
        assertTrue(zeroMatch.text().contains("Available sources: amap, mail"))
    }

    @Test
    fun toolExecuteAllowsOptionalArgumentsAndValidatesSchemaBeforeRunning() = runTest {
        var executed = 0
        val schemaTool = tool(
            name = "demo_fetch",
            description = "fetch demo",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("count") { put("type", "integer") }
                }
                put("required", JsonArray(listOf(JsonPrimitive("id"))))
            },
        ) { _, args ->
            executed++
            textResult("fetched:${(args["id"] as JsonPrimitive).content}")
        }
        val zeroArgTool = tool(name = "demo_ping", description = "ping") { _, _ ->
            executed++
            textResult("pong")
        }
        val context = AppToolContext(QuestionCenter(), pluginDeferredTools = { listOf(schemaTool, zeroArgTool) })
        val execute = deferredTools(context, SessionBinding()).first { it.name == "tool_execute" }
        assertEquals(
            listOf("name"),
            execute.parameters.getValue("required").let { it as JsonArray }.map { (it as JsonPrimitive).content },
        )

        val pingResult = execute.execute("e1", buildJsonObject { put("name", "demo_ping") }, null, null)
        assertNull(pingResult.isError)
        assertEquals("pong", pingResult.text())
        assertEquals(1, executed)

        val badMissing = execute.execute(
            "e2",
            buildJsonObject { put("name", "demo_fetch"); put("arguments", buildJsonObject {}) },
            null,
            null,
        )
        assertEquals(true, badMissing.isError)
        assertTrue(badMissing.text().contains("id"))
        assertEquals(1, executed)

        val badType = execute.execute(
            "e3",
            buildJsonObject { put("name", "demo_fetch"); put("arguments", JsonPrimitive(123)) },
            null,
            null,
        )
        assertEquals(true, badType.isError)
        assertEquals(1, executed)

        val ok = execute.execute(
            "e4",
            buildJsonObject {
                put("name", "demo_fetch")
                put("arguments", buildJsonObject { put("id", "42"); put("count", 2) })
            },
            null,
            null,
        )
        assertNull(ok.isError)
        assertEquals("fetched:42", ok.text())
        assertEquals(2, executed)
    }
}

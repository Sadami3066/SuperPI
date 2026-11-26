package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.runtime.host.HostAccessUnavailableException
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import io.github.whywood.superpi.sdk.coding.tools.ShellResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemShellToolTest {
    @Test
    fun reportsRetryableErrorWhenEnvironmentIsNotReady() = runTest {
        val tool = systemShellTool { throw HostAccessUnavailableException("Root Linux is being prepared") }

        val result = tool.execute(
            "call",
            buildJsonObject {
                put("command", "pm list packages")
                put("description", "List packages")
            },
            null,
            null,
        )

        assertEquals(true, result.isError)
        val message = textOf(result)
        assertTrue(message.contains("Root Linux is being prepared"))
        assertTrue(message.contains("Retry once it becomes available"))
    }

    @Test
    fun keepsMissingShellMessageAsError() = runTest {
        val tool = systemShellTool { null }

        val result = tool.execute(
            "call",
            buildJsonObject {
                put("command", "pm list packages")
                put("description", "List packages")
            },
            null,
            null,
        )

        assertEquals(true, result.isError)
        assertTrue(textOf(result).contains("not available in the current access tier"))
    }

    @Test
    fun requiresDescriptionInSchemaAndExecution() = runTest {
        var executedCommand: String? = null
        val tool = systemShellTool {
            object : ShellExecutor {
                override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult {
                    executedCommand = command
                    return ShellResult("ok\n", 0)
                }
            }
        }
        val properties = tool.parameters.getValue("properties").jsonObject
        val required = tool.parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(properties.containsKey("description"))
        assertTrue(required.contains("description"))

        val missingDesc = tool.execute(
            "missing-desc",
            buildJsonObject { put("command", "getprop ro.build.version.release") },
            null,
            null,
        )
        assertEquals(true, missingDesc.isError)

        val blankDesc = tool.execute(
            "blank-desc",
            buildJsonObject {
                put("command", "getprop ro.build.version.release")
                put("description", "   ")
            },
            null,
            null,
        )
        assertEquals(true, blankDesc.isError)

        val result = tool.execute(
            "call",
            buildJsonObject {
                put("command", "getprop ro.build.version.release")
                put("description", "Read Android OS version")
            },
            null,
            null,
        )

        assertNull(result.isError)
        assertEquals("getprop ro.build.version.release", executedCommand)
        assertTrue(textOf(result).contains("[exit: 0]"))
    }

    private fun textOf(result: AgentToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
}

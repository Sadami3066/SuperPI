package io.github.whywood.superpi.ui.common.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolPresentationTest {
    @Test
    fun toolIconKindMapsFileTools() {
        assertEquals(ToolIconKind.Book, toolIconKind("read"))
        assertEquals(ToolIconKind.Pen, toolIconKind("write"))
        assertEquals(ToolIconKind.Pen, toolIconKind("edit"))
        assertEquals(ToolIconKind.Folder, toolIconKind("ls"))
        assertEquals(ToolIconKind.Folder, toolIconKind("find"))
        assertEquals(ToolIconKind.File, toolIconKind("grep"))
    }

    @Test
    fun toolIconKindMapsShellAndWebTools() {
        assertEquals(ToolIconKind.Terminal, toolIconKind("bash"))
        assertEquals(ToolIconKind.Terminal, toolIconKind("system_shell"))
        assertEquals(ToolIconKind.Search, toolIconKind("web_search"))
        assertEquals(ToolIconKind.Search, toolIconKind("web_fetch"))
    }

    @Test
    fun toolIconKindMapsPrefixedTools() {
        assertEquals(ToolIconKind.Brain, toolIconKind("memory_search"))
        assertEquals(ToolIconKind.Brain, toolIconKind("memory_inspect_entity"))
        assertEquals(ToolIconKind.Brain, toolIconKind("memory_read_archive"))
        assertEquals(ToolIconKind.Brain, toolIconKind("memory_dispatch"))
        assertEquals(ToolIconKind.Apps, toolIconKind("miniapp_load"))
        assertEquals(ToolIconKind.Apps, toolIconKind("miniapp_data"))
        assertEquals(ToolIconKind.Apps, toolIconKind("worker"))
        assertEquals(ToolIconKind.Clock, toolIconKind("job"))
        assertEquals(ToolIconKind.Clock, toolIconKind("job_start"))
        assertEquals(ToolIconKind.Clock, toolIconKind("timer"))
        assertEquals(ToolIconKind.Clock, toolIconKind("timer_create"))
        assertEquals(ToolIconKind.Check, toolIconKind("todo_write"))
        assertEquals(ToolIconKind.Target, toolIconKind("goal"))
        assertEquals(ToolIconKind.Target, toolIconKind("get_goal"))
        assertEquals(ToolIconKind.Target, toolIconKind("update_goal"))
    }

    @Test
    fun toolIconKindFallsBackToDot() {
        assertEquals(ToolIconKind.Dot, toolIconKind("unknown_tool"))
        assertEquals(ToolIconKind.Dot, toolIconKind("mcp__github__create_issue"))
        assertEquals(ToolIconKind.Dot, toolIconKind("present"))
    }

    @Test
    fun toolUsedLabelUsesDisplayName() {
        assertEquals("使用工具 read", toolUsedLabel("使用工具 %1\$s", "read"))
        assertEquals("Used tool bash", toolUsedLabel("Used tool %1\$s", "bash"))
    }

    @Test
    fun toolUsedLabelFormatsMcpName() {
        assertEquals(
            "使用工具 github · create_issue",
            toolUsedLabel("使用工具 %1\$s", "mcp__github__create_issue"),
        )
    }

    @Test
    fun toolDescriptionAndProgressLabelPreferDescriptionAndExcludeFromRawArgs() {
        val raw = buildJsonObject {
            put("command", "ls -la /data/user/0/io.github.whywood.superpi/files/notes")
            put("description", "  List workspace   notes  ")
        }
        assertEquals("List workspace notes", toolDescriptionArg(raw, emptyMap()))
        assertEquals("ls -la ~/notes", toolArgsPreview(raw, emptyMap()))
        assertEquals("List workspace notes", toolProgressLabel("bash", raw, emptyMap()))

        val descriptionOnly = buildJsonObject {
            put("description", "Inspect logs")
        }
        assertEquals("", toolArgsPreview(descriptionOnly, emptyMap()))
        assertNull(toolDescriptionArg(null, mapOf("command" to "echo hi")))
        assertEquals("bash echo hi", toolProgressLabel("bash", null, mapOf("command" to "echo hi")))
        assertEquals("read", toolProgressLabel("read", null, emptyMap()))
    }
}

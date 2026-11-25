package io.github.whywood.superpi.ui.common.chat

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ToolIconKind {
    Book,
    Pen,
    Terminal,
    Search,
    Brain,
    Apps,
    Clock,
    File,
    Folder,
    Target,
    Check,
    Dot,
}

internal fun toolIconKind(name: String): ToolIconKind = when {
    name == "read" -> ToolIconKind.Book
    name == "write" || name == "edit" -> ToolIconKind.Pen
    name == "bash" || name == "system_shell" -> ToolIconKind.Terminal
    name == "web_search" || name == "web_fetch" -> ToolIconKind.Search
    name.startsWith("memory_") -> ToolIconKind.Brain
    name == "miniapp_data" || name.startsWith("miniapp_") || name == "worker" -> ToolIconKind.Apps
    name == "job" || name.startsWith("job_") || name == "timer" || name.startsWith("timer_") -> ToolIconKind.Clock
    name == "ls" || name == "find" -> ToolIconKind.Folder
    name == "grep" -> ToolIconKind.File
    name == "todo_write" -> ToolIconKind.Check
    name == "goal" || name.endsWith("_goal") -> ToolIconKind.Target
    else -> ToolIconKind.Dot
}

internal fun toolUsedLabel(format: String, name: String): String =
    String.format(Locale.ROOT, format, displayToolName(name))

private val ANDROID_PRIVATE_FILES_PREFIX = Regex("""/data/(?:user/\d+|data)/[^/\s]+/files/""")
private val ANDROID_SHARED_STORAGE_PREFIX = Regex("""/storage/emulated/\d+/?""")

internal fun sanitizeToolPath(raw: String): String = raw
    .replace(ANDROID_PRIVATE_FILES_PREFIX, "~/")
    .replace(ANDROID_SHARED_STORAGE_PREFIX) { match ->
        if (match.value.endsWith("/")) "Storage/" else "Storage"
    }

internal fun toolDescriptionArg(raw: JsonObject?, legacy: Map<String, String>): String? {
    val fromRaw = (raw?.get("description") as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }
    if (fromRaw != null) return fromRaw
    return legacy["description"]
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }
}

internal fun toolArgsPreview(raw: JsonObject?, legacy: Map<String, String>): String {
    val preferred = listOf(
        "command",
        "cmd",
        "path",
        "file_path",
        "pattern",
        "query",
        "url",
        "prompt",
    )
    val value = if (raw != null) {
        preferred.firstNotNullOfOrNull { key ->
            (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: raw[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" && it != "\"\"" }
        } ?: raw.entries.firstOrNull { (k, v) ->
            k != "description" && v.toString().let { s -> s.isNotBlank() && s != "null" && s != "\"\"" }
        }?.value?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }
    } else {
        preferred.firstNotNullOfOrNull { key -> legacy[key]?.takeIf { it.isNotBlank() } }
            ?: legacy.entries.firstOrNull { (k, v) -> k != "description" && v.isNotBlank() }?.value
    } ?: return ""
    return sanitizeToolPath(value.replace('\n', ' ').trim())
}

internal fun toolProgressLabel(name: String, raw: JsonObject?, legacy: Map<String, String>): String {
    val description = toolDescriptionArg(raw, legacy)
    if (description != null) return description
    val display = displayToolName(name)
    val preview = toolArgsPreview(raw, legacy)
    return if (preview.isNotEmpty()) "$display $preview" else display
}

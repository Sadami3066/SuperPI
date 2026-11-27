package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

private val FILE_TOOL_NAMES = setOf("read", "write", "edit")

internal const val ABSOLUTE_PATH_GUIDELINE =
    "File tool arguments need explicit absolute paths; the workspace is only a suggested location, not an access boundary."

private val WINDOWS_DRIVE_REGEX = Regex("^[A-Za-z]:[/\\\\].*")

internal fun requireAbsolutePath(path: String): String {
    val trimmed = path.trim()
    val isAbsolute = trimmed.startsWith("/") || WINDOWS_DRIVE_REGEX.matches(trimmed) || trimmed.startsWith("\\\\")
    require(isAbsolute) { "path must be an explicit absolute path: $path" }
    return trimmed.toPath(normalize = true).toString()
}

internal fun CodingTool.withAbsolutePaths(fs: FileSystem? = null): CodingTool {
    if (name !in FILE_TOOL_NAMES) return this
    val original = this
    val properties = parameters.getValue("properties").jsonObject.toMutableMap()
    properties["path"] = buildJsonObject {
        put("type", "string")
        put("description", "Absolute path in the local filesystem.")
    }
    val required = (parameters["required"] as? JsonArray).orEmpty().toMutableList()
    if (JsonPrimitive("path") !in required) required += JsonPrimitive("path")
    val schema = JsonObject(parameters + mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
    fun prepare(args: JsonObject): JsonObject {
        val prepared = original.prepareArguments?.invoke(args) ?: args
        val raw = (prepared["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("path is required and must be an absolute path")
        return JsonObject(prepared + ("path" to JsonPrimitive(requireAbsolutePath(raw))))
    }
    return CodingTool(
        name = name,
        description = description.replace("relative to the search directory", "as absolute paths"),
        parameters = schema,
        label = label,
        promptSnippet = original.promptSnippet,
        promptGuidelines = original.promptGuidelines.orEmpty() + ABSOLUTE_PATH_GUIDELINE,
        prepareArguments = ::prepare,
        execute = { id, args, signal, update ->
            val prepared = prepare(args)
            if (name == "read" && fs != null) {
                val pathStr = (prepared.getValue("path") as JsonPrimitive).content
                val path = pathStr.toPath(normalize = true)
                val directMeta = runCatching { fs.metadataOrNull(path) }.getOrNull()
                val targetMeta = if (directMeta?.symlinkTarget != null) {
                    runCatching { fs.metadataOrNull(fs.canonicalize(path)) }.getOrNull()
                } else {
                    directMeta
                }
                if (targetMeta != null) {
                    if (targetMeta.isDirectory) {
                        throw IllegalArgumentException("Path is a directory, not a file: $pathStr")
                    }
                    if (!targetMeta.isRegularFile) {
                        throw IllegalArgumentException("Path is not a regular file: $pathStr")
                    }
                }
            }
            original.execute(id, prepared, signal, update)
        },
    )
}

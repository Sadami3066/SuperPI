package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.ai.Tool
import io.github.whywood.superpi.sdk.ai.ToolCall
import io.github.whywood.superpi.sdk.ai.utils.ToolArgumentValidationException
import io.github.whywood.superpi.sdk.ai.utils.validateToolArguments
import io.github.whywood.superpi.sdk.coding.mcp.McpDeferredTool
import io.github.whywood.superpi.sdk.coding.mcp.McpToolSearch
import io.github.whywood.superpi.sdk.coding.session.AgentSession
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DeferredTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val source: String,
    val execute: suspend (
        toolCallId: String,
        args: JsonObject,
        signal: RunSignal?,
        onUpdate: (suspend (AgentToolResult) -> Unit)?,
    ) -> AgentToolResult,
)

class DeferredToolCatalog(
    private val tools: () -> List<DeferredTool>,
) {
    fun search(query: String, limit: Int = 8): List<DeferredTool> {
        val all = tools()
        if (all.isEmpty()) return emptyList()
        val byName = all.associateBy { it.name }
        val search = McpToolSearch(
            catalog = {
                all.map { tool ->
                    McpDeferredTool(
                        name = tool.name,
                        description = tool.description,
                        server = tool.source,
                        parameters = tool.parameters,
                    )
                }
            },
            onLoaded = {},
        )
        return search.search(query, limit).mapNotNull { byName[it.name] }
    }

    fun find(name: String): DeferredTool? = tools().firstOrNull { it.name == name }
}


internal fun deferredTools(
    context: AppToolContext,
    binding: SessionBinding,
    session: () -> AgentSession? = { null },
): List<CodingTool> = listOf(
    deferredSearchTool(context, binding, session),
    deferredExecuteTool(context, binding, session),
)

private const val DEFERRED_SEARCH_LIMIT = 20
private const val DEFAULT_DEFERRED_SEARCH_LIMIT = 8
private const val DEFERRED_DESCRIPTION_LIMIT = 240
private const val MAX_DEFERRED_SEARCH_OUTPUT_CHARS = 12_000

internal fun buildDeferredToolsSection(deferred: List<DeferredTool>): String? {
    if (deferred.isEmpty()) return null
    val grouped = deferred
        .groupBy { it.source.removePrefix("plugin:").ifBlank { it.source } }
        .toSortedMap()
    val body = buildString {
        appendLine("Additional plugin tools are available on demand. Use tool_search to inspect parameters and tool_execute to run one:")
        grouped.forEach { (source, tools) ->
            val names = tools.map { it.name }.distinct().sorted().joinToString(", ")
            appendLine("- $source: $names")
        }
    }.trim()
    return wrapPromptSection("deferred_tools", body)
}

private fun deferredSearchTool(
    context: AppToolContext,
    binding: SessionBinding,
    session: () -> AgentSession?,
): CodingTool = CodingTool(
    name = "tool_search",
    description = "Search deferred tools from MCP servers and plugins. Returns tool names and parameters; call tool_execute to run one.",
    parameters = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords describing what the tool should do, or '*' / 'all' to browse available deferred tools.")
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of tools to return (1-$DEFERRED_SEARCH_LIMIT, default $DEFAULT_DEFERRED_SEARCH_LIMIT).")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("query"))))
    },
    label = "Search tools",
    promptSnippet = "Use tool_search to discover deferred MCP or plugin tools, then tool_execute to call one.",
    promptGuidelines = listOf(
        "Deferred tools are not callable directly; search first, then execute by name.",
        "Do not guess deferred tool names without calling tool_search first.",
    ),
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        try {
            val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (query.isEmpty()) {
                return@CodingTool textResult("tool_search requires a non-empty query.", isError = true)
            }
            val catalog = deferredCatalog(context, binding, session)
            if (catalog.isEmpty()) {
                return@CodingTool textResult("No deferred tools are configured in this session.")
            }
            val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, DEFERRED_SEARCH_LIMIT)
                ?: DEFAULT_DEFERRED_SEARCH_LIMIT
            val matches = DeferredToolCatalog { catalog }.search(query, limit)
            if (matches.isEmpty()) {
                val sources = catalog
                    .map { it.source.removePrefix("plugin:").ifBlank { it.source } }
                    .distinct()
                    .sorted()
                    .joinToString(", ")
                return@CodingTool textResult(
                    "No deferred tools matched \"$query\". Available sources: $sources. Try broader keywords or query=\"*\" to browse.",
                )
            }
            val included = mutableListOf<DeferredTool>()
            val body = buildString {
                append("Matched ${matches.size} deferred tool(s). Call tool_execute with name and arguments.")
                for (tool in matches) {
                    val normalized = tool.description.replace(Regex("\\s+"), " ").trim()
                    val summary = if (normalized.length > DEFERRED_DESCRIPTION_LIMIT) {
                        normalized.take(DEFERRED_DESCRIPTION_LIMIT - 3) + "..."
                    } else {
                        normalized
                    }
                    val entry = "\n\n- ${tool.name} [${tool.source}]\n  $summary\n  parameters: ${tool.parameters}"
                    if (included.isNotEmpty() && length + entry.length + 96 > MAX_DEFERRED_SEARCH_OUTPUT_CHARS) {
                        break
                    }
                    append(entry)
                    included += tool
                }
                val omitted = matches.size - included.size
                if (omitted > 0) {
                    append("\n\n...($omitted more matched tool(s) omitted; narrow query or lower limit)")
                }
            }
            val details = buildJsonObject {
                put(
                    "tools",
                    JsonArray(
                        included.map { tool ->
                            buildJsonObject {
                                put("name", tool.name)
                                put("source", tool.source)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        },
                    ),
                )
            }
            textResult(body.trimEnd(), details)
        } catch (aborted: RequestAbortedException) {
            throw aborted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            textResult(error.message ?: "tool_search failed.", isError = true)
        }
    },
)

private fun deferredExecuteTool(
    context: AppToolContext,
    binding: SessionBinding,
    session: () -> AgentSession?,
): CodingTool = CodingTool(
    name = "tool_execute",
    description = "Execute one deferred MCP or plugin tool found with tool_search.",
    parameters = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "name",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Deferred tool name returned by tool_search.")
                    },
                )
                put(
                    "arguments",
                    buildJsonObject {
                        put("type", "object")
                        put("description", "Optional JSON object of arguments for the deferred tool (defaults to {}).")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("name"))))
    },
    label = "Execute tool",
    promptSnippet = "Run a deferred tool by name after finding it with tool_search.",
    promptGuidelines = listOf("Unknown names fail; call tool_search before tool_execute."),
    execute = { toolCallId, args, signal, onUpdate ->
        signal?.throwIfAborted()
        try {
            val name = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (name.isEmpty()) {
                return@CodingTool textResult("tool_execute requires a tool name.", isError = true)
            }
            val tool = DeferredToolCatalog { deferredCatalog(context, binding, session) }.find(name)
                ?: return@CodingTool textResult("Unknown deferred tool: $name. Call tool_search first.", isError = true)
            val rawArguments = when (val value = args["arguments"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> value
                is JsonPrimitive -> {
                    if (!value.isString) {
                        return@CodingTool textResult("tool_execute arguments must be a JSON object.", isError = true)
                    }
                    val parsed = runCatching { Json.parseToJsonElement(value.content) }.getOrNull()
                    parsed as? JsonObject
                        ?: return@CodingTool textResult("tool_execute arguments must be a JSON object.", isError = true)
                }
                else -> return@CodingTool textResult("tool_execute arguments must be a JSON object.", isError = true)
            }
            val validatedArguments = try {
                validateToolArguments(
                    Tool(name = tool.name, description = tool.description, parameters = tool.parameters),
                    ToolCall(id = toolCallId, name = tool.name, arguments = rawArguments),
                )
            } catch (error: ToolArgumentValidationException) {
                return@CodingTool textResult(error.message ?: "Invalid arguments for ${tool.name}", isError = true)
            }
            tool.execute(toolCallId, validatedArguments, signal, onUpdate)
        } catch (aborted: RequestAbortedException) {
            throw aborted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val targetName = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().ifEmpty { "tool" }
            textResult(error.message ?: "Deferred tool $targetName failed.", isError = true)
        }
    },
)

private fun deferredCatalog(
    context: AppToolContext,
    binding: SessionBinding,
    session: () -> AgentSession?,
): List<DeferredTool> {
    val seen = LinkedHashSet<String>()
    val result = mutableListOf<DeferredTool>()
    runCatching { context.pluginDeferredTools(binding) }.getOrDefault(emptyList()).forEach { tool ->
        if (seen.add(tool.name)) result += tool
    }
    val mcp = runCatching { session()?.mcp?.deferredCatalog().orEmpty() }.getOrDefault(emptyList())
    val activeSession = session()
    mcp.forEach { tool ->
        if (seen.add(tool.name)) {
            result += DeferredTool(
                name = tool.name,
                description = tool.description.orEmpty(),
                parameters = tool.parameters,
                source = "mcp:${tool.server}",
                execute = { _, arguments, mcpSignal, mcpUpdate ->
                    val active = activeSession
                    if (active == null) {
                        textResult("MCP session is not available.", isError = true)
                    } else {
                        try {
                            active.mcp.callDeferredTool(tool.name, arguments, mcpSignal, mcpUpdate)
                                ?: textResult("MCP tool ${tool.name} is not available.", isError = true)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (aborted: RequestAbortedException) {
                            throw aborted
                        } catch (error: Exception) {
                            textResult(
                                "MCP tool ${tool.name} failed: ${error.message ?: "unknown error"}",
                                isError = true,
                            )
                        }
                    }
                },
            )
        }
    }
    return result
}

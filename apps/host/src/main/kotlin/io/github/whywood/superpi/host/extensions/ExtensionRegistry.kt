package io.github.whywood.superpi.host.extensions

import io.github.whywood.superpi.host.config.HostSettingsStore
import io.github.whywood.superpi.host.platform.PathLayout
import io.github.whywood.superpi.runtime.AnswerCardPayload
import io.github.whywood.superpi.runtime.McpConfigSnapshot
import io.github.whywood.superpi.runtime.McpConfigStore
import io.github.whywood.superpi.runtime.McpProbeResult
import io.github.whywood.superpi.runtime.McpProbeState
import io.github.whywood.superpi.runtime.McpServerProbe
import io.github.whywood.superpi.runtime.SkillCatalog
import io.github.whywood.superpi.runtime.SkillSummary
import io.github.whywood.superpi.runtime.logWarn
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.mcp.McpClient
import io.github.whywood.superpi.sdk.mcp.McpClientOptions
import io.github.whywood.superpi.sdk.mcp.McpRequestOptions
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import io.github.whywood.superpi.sdk.mcp.oauth.McpTokenStore
import io.github.whywood.superpi.sdk.mcp.platformStdioTransportFactory
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class HostExtensionSource {
    BUILTIN,
    SKILL,
    PROMPT,
    MCP,
    EXTERNAL,
}

data class HostExtensionDescriptor(
    val id: String,
    val name: String,
    val source: HostExtensionSource,
    val enabled: Boolean,
    val detail: String = "",
)

data class PromptTemplate(
    val name: String,
    val description: String,
    val body: String,
    val file: File,
) {
    fun render(args: String): String {
        val trimmedArgs = args.trim()
        return body
            .replace("{{args}}", trimmedArgs)
            .replace("\$ARGUMENTS", trimmedArgs)
            .let { rendered ->
                if (trimmedArgs.isEmpty() || rendered != body) rendered else "$rendered\n\n$trimmedArgs"
            }
            .trim()
    }
}

@Serializable
data class ReservedExtensionManifest(
    val id: String,
    val name: String = "",
    val version: String = "0.0.0",
    val description: String = "",
    val kind: String = "reserved",
)

interface HostExtension {
    val descriptor: HostExtensionDescriptor

    fun tools(): List<CodingTool> = emptyList()

    fun promptSections(): List<String> = emptyList()
}

/**
 * Unified extension pipeline across built-ins, Skills, Prompt templates, stdio/HTTP MCP,
 * and reserved external PC plugin manifests in ~/.super-pi/extensions.
 */
class ExtensionRegistry(
    private val paths: PathLayout,
    private val settingsStore: HostSettingsStore,
    private val mcpConfigStore: McpConfigStore,
    private val mcpServerProbe: McpServerProbe,
    private val skillCatalog: SkillCatalog,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val answerCardListeners = CopyOnWriteArrayList<(AnswerCardPayload) -> Unit>()

    @Volatile
    var promptTemplates: List<PromptTemplate> = emptyList()
        private set

    @Volatile
    var externalManifests: List<ReservedExtensionManifest> = emptyList()
        private set

    @Volatile
    var scanErrors: List<String> = emptyList()
        private set

    init {
        refreshStaticCatalogs()
    }

    fun emitAnswerCard(card: AnswerCardPayload) {
        answerCardListeners.forEach { listener ->
            runCatching { listener(card) }
                .onFailure { logWarn("ExtensionRegistry", "Answer card listener failed", it) }
        }
    }

    fun onAnswerCard(listener: (AnswerCardPayload) -> Unit): () -> Unit {
        answerCardListeners.add(listener)
        return { answerCardListeners.remove(listener) }
    }

    suspend fun reload(): ExtensionSnapshot = withContext(Dispatchers.IO) {
        settingsStore.reload()
        refreshStaticCatalogs()
        snapshot(paths.defaultWorkspaceDir.absolutePath)
    }

    fun refreshStaticCatalogs() {
        val errors = mutableListOf<String>()
        promptTemplates = loadPromptTemplates(paths.promptsDir)
        externalManifests = loadReservedExtensions(paths.extensionsDir, errors)
        scanErrors = errors.toList()
    }

    fun findPromptTemplate(name: String): PromptTemplate? =
        promptTemplates.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    suspend fun snapshot(cwd: String): ExtensionSnapshot {
        val disabled = settingsStore.current().disabledExtensions.toSet()
        val mcp = mcpConfigStore.load()
        val skills = runCatching { skillCatalog.list(cwd) }.getOrDefault(emptyList())
        val descriptors = buildList {
            BUILTIN_EXTENSIONS.forEach { (id, name, detail) ->
                add(HostExtensionDescriptor(id, name, HostExtensionSource.BUILTIN, enabled = id !in disabled, detail = detail))
            }
            mcp.entries.keys.sorted().forEach { serverName ->
                val server = mcp.servers[serverName]
                val enabled = (server?.enabled != false) && ("mcp:$serverName" !in disabled)
                val detail = when (server) {
                    is McpServerConfig.Stdio -> "stdio: ${server.command} ${server.args.joinToString(" ")}".trim()
                    is McpServerConfig.Http -> "http: ${server.url}"
                    null -> "invalid configuration"
                }
                add(HostExtensionDescriptor("mcp:$serverName", serverName, HostExtensionSource.MCP, enabled = enabled, detail = detail))
            }
            skills.forEach { skill ->
                add(HostExtensionDescriptor("skill:${skill.name}", skill.name, HostExtensionSource.SKILL, enabled = "skill:${skill.name}" !in disabled, detail = skill.description))
            }
            promptTemplates.forEach { prompt ->
                add(HostExtensionDescriptor("prompt:${prompt.name}", prompt.name, HostExtensionSource.PROMPT, enabled = "prompt:${prompt.name}" !in disabled, detail = prompt.description))
            }
            externalManifests.forEach { manifest ->
                add(
                    HostExtensionDescriptor(
                        id = "ext:${manifest.id}",
                        name = manifest.name.ifBlank { manifest.id },
                        source = HostExtensionSource.EXTERNAL,
                        enabled = false,
                        detail = "Reserved extension slot (${manifest.version}): external plugin loader arrives in a later release",
                    ),
                )
            }
        }
        return ExtensionSnapshot(
            descriptors = descriptors,
            mcp = mcp,
            skills = skills,
            prompts = promptTemplates,
            errors = mcp.errors + scanErrors,
        )
    }

    suspend fun probeMcp(name: String, config: McpServerConfig, timeoutMs: Long = 15_000L): McpProbeResult =
        withContext(Dispatchers.IO) {
            when (config) {
                is McpServerConfig.Http -> mcpServerProbe.probe(name, config, timeoutMs)
                is McpServerConfig.Stdio -> probeStdio(name, config, timeoutMs)
            }
        }

    private suspend fun probeStdio(
        name: String,
        config: McpServerConfig.Stdio,
        timeoutMs: Long,
    ): McpProbeResult {
        val transport = try {
            platformStdioTransportFactory().create(
                command = config.command,
                args = config.args,
                env = config.env,
                cwd = config.cwd,
            ) ?: return McpProbeResult(
                state = McpProbeState.UNSUPPORTED,
                error = "Stdio MCP transport is unavailable on this platform",
            )
        } catch (error: Exception) {
            return McpProbeResult(
                state = McpProbeState.FAILED,
                error = error.message ?: "Failed to create stdio transport",
            )
        }
        val client = McpClient(
            serverName = name,
            transport = transport,
            options = McpClientOptions(requestTimeoutMs = timeoutMs),
        )
        return try {
            val initialized = client.initialize()
            val tools = client.listTools(McpRequestOptions(timeoutMs = timeoutMs))
            McpProbeResult(
                state = McpProbeState.OK,
                toolCount = tools.size,
                serverName = initialized.serverInfo.name,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            McpProbeResult(
                state = McpProbeState.FAILED,
                error = error.message ?: error::class.java.simpleName,
            )
        } finally {
            runCatching { client.close() }
        }
    }

    private fun loadPromptTemplates(dir: File): List<PromptTemplate> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
                val name = file.nameWithoutExtension.trim()
                if (name.isEmpty()) return@mapNotNull null
                val firstLine = raw.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("---") }
                    .orEmpty()
                    .trimStart('#', ' ')
                    .take(80)
                PromptTemplate(
                    name = name,
                    description = firstLine.ifEmpty { "Prompt template ($name)" },
                    body = raw,
                    file = file,
                )
            }
            .orEmpty()
    }

    private fun loadReservedExtensions(dir: File, errors: MutableList<String>): List<ReservedExtensionManifest> {
        if (!dir.isDirectory) return emptyList()
        val manifests = mutableListOf<ReservedExtensionManifest>()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { entry ->
            val manifestFile = when {
                entry.isDirectory -> File(entry, "extension.json")
                entry.isFile && entry.extension.equals("json", ignoreCase = true) -> entry
                else -> null
            } ?: return@forEach
            if (!manifestFile.isFile) return@forEach
            runCatching {
                json.decodeFromString(ReservedExtensionManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
            }.onSuccess { parsed ->
                if (parsed.id.isNotBlank()) manifests += parsed
            }.onFailure { err ->
                errors += "Failed to read extension manifest ${manifestFile.name}: ${err.message}"
            }
        }
        return manifests
    }

    private companion object {
        val BUILTIN_EXTENSIONS = listOf(
            Triple("builtin:web", "Web Search", "Multi-provider search & fetch tools"),
            Triple("builtin:memory", "Memory v3", "Persistent SQLite memory & recall"),
            Triple("builtin:planning", "Goals, Jobs & Timers", "Background jobs, timers, todo & goal tracking"),
        )
    }
}

data class ExtensionSnapshot(
    val descriptors: List<HostExtensionDescriptor>,
    val mcp: McpConfigSnapshot,
    val skills: List<SkillSummary>,
    val prompts: List<PromptTemplate>,
    val errors: List<String>,
)

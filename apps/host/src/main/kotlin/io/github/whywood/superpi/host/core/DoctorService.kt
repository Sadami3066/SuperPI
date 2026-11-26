package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.extensions.ExtensionRegistry
import io.github.whywood.superpi.host.platform.HostPlatform
import io.github.whywood.superpi.host.server.LocalControlClient
import io.github.whywood.superpi.runtime.McpProbeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
enum class CheckSeverity {
    PASS,
    WARN,
    FAIL,
}

@Serializable
data class DoctorCheckItem(
    val category: String,
    val name: String,
    val status: CheckSeverity,
    val summary: String,
    val fixHint: String? = null,
)

@Serializable
data class DoctorReport(
    val version: String,
    val homeDir: String,
    val os: String,
    val checks: List<DoctorCheckItem>,
) {
    val hasFailures: Boolean get() = checks.any { it.status == CheckSeverity.FAIL }
}

class DoctorService(
    private val controller: SuperPiController,
    private val platform: HostPlatform,
    private val extensions: ExtensionRegistry,
) {
    suspend fun run(probeMcpServers: Boolean = true): DoctorReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<DoctorCheckItem>()

        // 1. OS & Shell
        val shellProbe = platform.shell.probe()
        items += if (shellProbe.available) {
            DoctorCheckItem(
                category = "environment",
                name = "Host shell",
                status = CheckSeverity.PASS,
                summary = "${platform.profile.osName} (${platform.profile.arch}) · ${shellProbe.shellPath}",
            )
        } else {
            DoctorCheckItem(
                category = "environment",
                name = "Host shell",
                status = CheckSeverity.FAIL,
                summary = shellProbe.message,
                fixHint = "Install bash or ensure /bin/sh is executable in PATH.",
            )
        }

        // 2. Models & Credentials
        val configuredIds = controller.configuredProviderIds()
        val defaultModel = controller.settingsStore.read()
        items += if (configuredIds.isEmpty()) {
            DoctorCheckItem(
                category = "models",
                name = "Model credentials",
                status = CheckSeverity.WARN,
                summary = "No provider API keys or OAuth credentials configured",
                fixHint = "Run `${AppBrand.CLI_NAME} init` or export OPENAI_API_KEY / ANTHROPIC_API_KEY / DEEPSEEK_API_KEY.",
            )
        } else {
            val defaultReady = defaultModel.providerId in configuredIds
            DoctorCheckItem(
                category = "models",
                name = "Model credentials",
                status = if (defaultReady) CheckSeverity.PASS else CheckSeverity.WARN,
                summary = "Configured: ${configuredIds.sorted().joinToString(", ")} · Default: ${defaultModel.providerId}/${defaultModel.modelId}",
                fixHint = if (defaultReady) null else "Default provider '${defaultModel.providerId}' has no stored credential; switch default model or run `${AppBrand.CLI_NAME} init`.",
            )
        }

        // 3. Memory v3 SQLite
        val memoryCheck = runCatching {
            controller.memoryStore.ensure()
            val overview = controller.memoryStore.overview()
            DoctorCheckItem(
                category = "memory",
                name = "Memory v3 SQLite",
                status = CheckSeverity.PASS,
                summary = "Healthy (${overview.entityCount} entities, ${overview.relationCount} relations, ${overview.activeEvents} events, ${overview.activeBlocks} blocks)",
            )
        }.getOrElse { error ->
            DoctorCheckItem(
                category = "memory",
                name = "Memory v3 SQLite",
                status = CheckSeverity.FAIL,
                summary = "Failed to open memory.db: ${error.message}",
                fixHint = "Check permissions on ${platform.paths.memoryDir.absolutePath}",
            )
        }
        items += memoryCheck

        // 4. Registered Bots
        val bots = controller.botRegistry.listAllBots()
        val missingDirs = bots.filter { !java.io.File(it.cwd).isDirectory }
        items += if (missingDirs.isEmpty()) {
            DoctorCheckItem(
                category = "bots",
                name = "Registered bots",
                status = CheckSeverity.PASS,
                summary = "${bots.size} bot(s) ready (${bots.joinToString(", ") { it.name }})",
            )
        } else {
            DoctorCheckItem(
                category = "bots",
                name = "Registered bots",
                status = CheckSeverity.WARN,
                summary = "${missingDirs.size} bot directory missing: ${missingDirs.joinToString { it.cwd }}",
                fixHint = "Remove missing bots with `${AppBrand.CLI_NAME} bot rm <id>`.",
            )
        }

        // 5. Relay Status
        val relayAccount = controller.relayStore.read()
        items += if (relayAccount == null) {
            DoctorCheckItem(
                category = "relay",
                name = "Relay pairing",
                status = CheckSeverity.WARN,
                summary = "Not paired to a Relay server (local usage works normally)",
                fixHint = "Run `${AppBrand.CLI_NAME} relay pair <link>` or `${AppBrand.CLI_NAME} relay login` to connect your phone.",
            )
        } else {
            val tokenPresent = !controller.relayStore.token().isNullOrBlank()
            val liveLabel = if (controller.isRelayLiveInitialized) {
                controller.relayLive.state.value.name
            } else {
                val liveStatus = LocalControlClient(platform.paths.serveLockFile).use { it.fetchStatus() }
                liveStatus?.relayState ?: "daemon stopped"
            }
            DoctorCheckItem(
                category = "relay",
                name = "Relay pairing",
                status = if (tokenPresent) CheckSeverity.PASS else CheckSeverity.FAIL,
                summary = "Origin ${relayAccount.origin} · Device '${relayAccount.deviceName}' (${relayAccount.deviceId}) · Live=$liveLabel",
                fixHint = if (tokenPresent) null else "Relay token missing in secrets vault; re-run `${AppBrand.CLI_NAME} relay pair <link>`.",
            )
        }

        // 6. Extensions, Skills & MCP
        extensions.refreshStaticCatalogs()
        val extSnap = extensions.snapshot(platform.paths.defaultWorkspaceDir.absolutePath)
        if (extSnap.errors.isNotEmpty()) {
            items += DoctorCheckItem(
                category = "extensions",
                name = "Extension & MCP config",
                status = CheckSeverity.FAIL,
                summary = extSnap.errors.joinToString("; "),
                fixHint = "Fix malformed JSON in ${platform.paths.mcpConfigFile.absolutePath} or ${platform.paths.extensionsDir.absolutePath}.",
            )
        } else {
            items += DoctorCheckItem(
                category = "extensions",
                name = "Skills & prompts",
                status = CheckSeverity.PASS,
                summary = "${extSnap.skills.size} skill(s), ${extSnap.prompts.size} prompt template(s), ${extSnap.mcp.entries.size} MCP server(s)",
            )
        }

        if (probeMcpServers && extSnap.mcp.servers.isNotEmpty()) {
            extSnap.mcp.servers.entries.sortedBy { it.key }.forEach { (serverName, config) ->
                if (!config.enabled) {
                    items += DoctorCheckItem(
                        category = "mcp",
                        name = "MCP:$serverName",
                        status = CheckSeverity.WARN,
                        summary = "Disabled in mcp.json",
                    )
                } else {
                    val result = extensions.probeMcp(serverName, config, timeoutMs = 8_000L)
                    items += when (result.state) {
                        McpProbeState.OK -> DoctorCheckItem(
                            category = "mcp",
                            name = "MCP:$serverName",
                            status = CheckSeverity.PASS,
                            summary = "Connected (${result.toolCount} tool(s), server=${result.serverName ?: "ok"})",
                        )
                        McpProbeState.AUTH_REQUIRED -> DoctorCheckItem(
                            category = "mcp",
                            name = "MCP:$serverName",
                            status = CheckSeverity.WARN,
                            summary = "OAuth authentication required",
                        )
                        McpProbeState.FAILED, McpProbeState.UNSUPPORTED -> DoctorCheckItem(
                            category = "mcp",
                            name = "MCP:$serverName",
                            status = CheckSeverity.FAIL,
                            summary = result.error ?: "Handshake failed",
                            fixHint = "Check command/URL in ${platform.paths.mcpConfigFile.absolutePath}.",
                        )
                    }
                }
            }
        }

        DoctorReport(
            version = AppBrand.VERSION,
            homeDir = platform.paths.homeDir.absolutePath,
            os = "${platform.profile.osName} (${platform.profile.arch})",
            checks = items,
        )
    }
}

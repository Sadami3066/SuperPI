package io.github.whywood.superpi.host.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.github.whywood.superpi.data.relay.RelayException
import io.github.whywood.superpi.host.platform.HostSecretEnv
import io.github.whywood.superpi.host.server.LocalControlClient
import io.github.whywood.superpi.host.server.maskApiKey
import io.github.whywood.superpi.runtime.ThinkingLevel
import java.io.File

internal class ProviderCommand : CliktCommand(name = "provider") {
    override fun help(context: Context): String = "Manage model providers, API keys, and global default model."

    init {
        subcommands(
            ProviderListCommand(),
            ProviderSetKeyCommand(),
            ProviderRmKeyCommand(),
            ProviderDefaultCommand(),
        )
    }

    override fun run() = Unit
}

private class ProviderListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val configured = controller.configuredProviderIds()
            val stored = runCatching { controller.apiKeyStore.readAll() }.getOrDefault(emptyMap())
            controller.providerCatalog.summaries.forEach { summary ->
                val envKey = HostSecretEnv.envSecretFallback("api_key_", summary.id)
                val mask = maskApiKey(stored[summary.id] ?: envKey) ?: "-"
                val status = if (summary.id in configured) "configured" else "unconfigured"
                val count = controller.providerCatalog.models(summary.id).size
                holder.env.out.println("${summary.id} (${summary.name}) · $status · $count models · key=$mask")
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ProviderSetKeyCommand : CliktCommand(name = "set-key") {
    private val holder by requireObject<ControllerHolder>()
    private val providerId by argument("provider", help = "Provider ID (e.g. openai, anthropic, opencode-go)")
    private val keyOpt by option("--key", help = "API key (omit to read from stdin/console)")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val cleanProvider = providerId.trim()
            val rawKey = keyOpt?.trim()?.takeIf { it.isNotEmpty() } ?: run {
                val console = System.console()
                if (console != null) {
                    holder.env.out.print("API key for $cleanProvider: ")
                    holder.env.out.flush()
                    String(console.readPassword() ?: CharArray(0)).trim()
                } else {
                    System.`in`.bufferedReader(Charsets.UTF_8).readLine()?.trim().orEmpty()
                }
            }
            if (cleanProvider.isEmpty() || rawKey.isEmpty()) {
                holder.env.err.println("Provider ID and API key are required.")
                return@withController 2
            }
            controller.apiKeyStore.save(cleanProvider, rawKey)
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Saved API key for '$cleanProvider'.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ProviderRmKeyCommand : CliktCommand(name = "rm-key") {
    private val holder by requireObject<ControllerHolder>()
    private val providerId by argument("provider", help = "Provider ID")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val cleanProvider = providerId.trim()
            if (cleanProvider.isEmpty()) {
                holder.env.err.println("Provider ID is required.")
                return@withController 2
            }
            controller.apiKeyStore.save(cleanProvider, null)
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Removed stored API key for '$cleanProvider'.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ProviderDefaultCommand : CliktCommand(name = "default") {
    private val holder by requireObject<ControllerHolder>()
    private val spec by argument("model", help = "Model spec as provider/model").optional()
    private val thinkingOpt by option("--thinking", help = "Thinking level: off, low, medium, high, xhigh, max")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val raw = spec?.trim()?.takeIf { it.isNotEmpty() }
            if (raw == null) {
                val cur = controller.settingsStore.current()
                holder.env.out.println("${cur.defaultProvider}/${cur.defaultModel} (thinking=${cur.defaultThinkingLevel})")
                return@withController 0
            }
            val parsed = HeadlessRunner.parseModelSpec(raw) ?: run {
                holder.env.err.println("Invalid model '$raw'. Expected provider/model.")
                return@withController 2
            }
            val thinking = thinkingOpt?.trim()?.takeIf { it.isNotEmpty() }?.let { tRaw ->
                ThinkingLevel.entries.firstOrNull { it.name.equals(tRaw, ignoreCase = true) } ?: run {
                    holder.env.err.println("Invalid --thinking '$tRaw'. Expected off, low, medium, high, xhigh, or max.")
                    return@withController 2
                }
            }
            controller.settingsStore.update { cur ->
                cur.copy(
                    defaultProvider = parsed.providerId,
                    defaultModel = parsed.modelId,
                    defaultThinkingLevel = thinking?.name ?: cur.defaultThinkingLevel,
                )
            }
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            val updated = controller.settingsStore.current()
            holder.env.out.println("Default model set to ${updated.defaultProvider}/${updated.defaultModel} (thinking=${updated.defaultThinkingLevel}).")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

internal class SessionCommand : CliktCommand(name = "session") {
    override fun help(context: Context): String = "List, rename, export, or delete local conversations."

    init {
        subcommands(
            SessionListCommand(),
            SessionRenameCommand(),
            SessionDeleteCommand(),
            SessionExportCommand(),
        )
    }

    override fun run() = Unit
}

private class SessionListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()
    private val botOpt by option("--bot", help = "Filter by Bot ID or name")
    private val archivedFlag by option("--archived", help = "List archived sessions instead").flag(default = false)

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val list = if (archivedFlag) {
                controller.runtimeHost.archivedSessions()
            } else if (!botOpt.isNullOrBlank()) {
                val bot = controller.botRegistry.findBot(botOpt!!) ?: run {
                    holder.env.err.println("Bot not found: $botOpt")
                    return@withController 1
                }
                controller.runtimeHost.sessions(bot.id)
            } else {
                controller.runtimeHost.allSessions().filterNot { it.archived }
            }
            if (list.isEmpty()) {
                holder.env.out.println("No sessions found.")
            } else {
                list.forEach { s ->
                    val flags = buildList {
                        if (s.pinned) add("pinned")
                        if (s.archived) add("archived")
                        if (s.memoryExcluded) add("no-memory")
                    }.joinToString(",")
                    val suffix = if (flags.isNotEmpty()) " [$flags]" else ""
                    holder.env.out.println("${s.id} · ${s.title}$suffix")
                }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class SessionRenameCommand : CliktCommand(name = "rename") {
    private val holder by requireObject<ControllerHolder>()
    private val sessionId by argument("sessionId", help = "Session ID")
    private val title by argument("title", help = "New conversation title")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) {
                holder.env.err.println("Title must not be empty.")
                return@withController 2
            }
            controller.runtimeHost.renameSession(sessionId.trim(), cleanTitle)
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Renamed session ${sessionId.trim()} -> '$cleanTitle'.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class SessionDeleteCommand : CliktCommand(name = "delete") {
    private val holder by requireObject<ControllerHolder>()
    private val sessionId by argument("sessionId", help = "Session ID")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            controller.runtimeHost.deleteSession(sessionId.trim())
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Deleted session ${sessionId.trim()}.")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class SessionExportCommand : CliktCommand(name = "export") {
    private val holder by requireObject<ControllerHolder>()
    private val sessionId by argument("sessionId", help = "Session ID")
    private val outFile by option("--out", help = "Destination file path (defaults to stdout)")
        .file(canBeDir = false, mustExist = false)

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val id = sessionId.trim()
            val summary = controller.runtimeHost.allSessions().firstOrNull { it.id == id }
                ?: controller.runtimeHost.archivedSessions().firstOrNull { it.id == id }
            if (summary == null || summary.filePath.isBlank()) {
                holder.env.err.println("Session not found: $id")
                return@withController 1
            }
            val file = File(summary.filePath)
            if (!file.isFile) {
                holder.env.err.println("Session transcript file does not exist on disk.")
                return@withController 1
            }
            val text = file.readText(Charsets.UTF_8)
            val target = outFile
            if (target != null) {
                target.parentFile?.mkdirs()
                target.writeText(text, Charsets.UTF_8)
                holder.env.out.println("Exported session $id to ${target.absolutePath}")
            } else {
                holder.env.out.print(text)
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

internal class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context): String = "Read or update host settings in ~/.super-pi/settings.json."

    init {
        subcommands(ConfigListCommand(), ConfigGetCommand(), ConfigSetCommand())
    }

    override fun run() = Unit
}

private class ConfigListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val s = controller.settingsStore.current()
            configEntries(s).forEach { (k, v) -> holder.env.out.println("$k=$v") }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ConfigGetCommand : CliktCommand(name = "get") {
    private val holder by requireObject<ControllerHolder>()
    private val key by argument("key", help = "Setting key")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val map = configEntries(controller.settingsStore.current()).toMap()
            val value = map[key.trim()]
            if (value == null) {
                holder.env.err.println("Unknown config key: $key")
                return@withController 1
            }
            holder.env.out.println(value)
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class ConfigSetCommand : CliktCommand(name = "set") {
    private val holder by requireObject<ControllerHolder>()
    private val key by argument("key", help = "Setting key")
    private val value by argument("value", help = "New value")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val k = key.trim()
            val v = value.trim()
            val validationError: String? = when (k) {
                "deviceName", "defaultProvider", "defaultModel" ->
                    if (v.isEmpty()) "$k must not be empty." else null
                "defaultThinkingLevel" ->
                    if (ThinkingLevel.entries.none { it.name.equals(v, ignoreCase = true) }) {
                        "Invalid thinking level '$v'. Expected off|low|medium|high|xhigh|max."
                    } else null
                "compactionTriggerPercent" ->
                    if (v.toIntOrNull()?.let { it in 50..95 } != true) {
                        "compactionTriggerPercent must be an integer between 50 and 95."
                    } else null
                "servePort" ->
                    if (v.toIntOrNull()?.let { it in 0..65535 } != true) {
                        "servePort must be between 0 and 65535."
                    } else null
                "language" ->
                    if (v !in setOf("system", "zh-CN", "en")) {
                        "language must be one of system, zh-CN, en."
                    } else null
                "sendKey" ->
                    if (v !in setOf("enter", "mod_enter")) {
                        "sendKey must be enter or mod_enter."
                    } else null
                "sendWhileRunning" ->
                    if (v !in setOf("steer", "queue")) {
                        "sendWhileRunning must be steer or queue."
                    } else null
                "chatFontScale" ->
                    if (v.toIntOrNull()?.let { it in 85..140 } != true) {
                        "chatFontScale must be between 85 and 140."
                    } else null
                "processDetail" ->
                    if (v !in setOf("detailed", "compact")) {
                        "processDetail must be detailed or compact."
                    } else null
                "autoRetry" ->
                    if (v.lowercase().toBooleanStrictOrNull() == null) {
                        "autoRetry must be true or false."
                    } else null
                else -> "Unknown config key: $k"
            }
            if (validationError != null) {
                holder.env.err.println(validationError)
                return@withController 2
            }
            val updated = controller.settingsStore.update { cur ->
                when (k) {
                    "deviceName" -> cur.copy(deviceName = v)
                    "defaultProvider" -> cur.copy(defaultProvider = v)
                    "defaultModel" -> cur.copy(defaultModel = v)
                    "defaultThinkingLevel" -> cur.copy(
                        defaultThinkingLevel = ThinkingLevel.entries.first { it.name.equals(v, ignoreCase = true) }.name,
                    )
                    "compactionTriggerPercent" -> cur.copy(compactionTriggerPercent = v.toInt())
                    "servePort" -> cur.copy(servePort = v.toInt())
                    "language" -> cur.copy(language = v)
                    "sendKey" -> cur.copy(sendKey = v)
                    "sendWhileRunning" -> cur.copy(sendWhileRunning = v)
                    "chatFontScale" -> cur.copy(chatFontScale = v.toInt())
                    "processDetail" -> cur.copy(processDetail = v)
                    "autoRetry" -> cur.copy(autoRetry = v.lowercase().toBooleanStrict())
                    else -> cur
                }
            }
            val persisted = configEntries(updated).firstOrNull { it.first == k }?.second ?: v
            LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
            holder.env.out.println("Updated $k=$persisted")
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private fun configEntries(s: io.github.whywood.superpi.host.config.HostSettings): List<Pair<String, String>> = listOf(
    "deviceName" to s.deviceName,
    "defaultProvider" to s.defaultProvider,
    "defaultModel" to s.defaultModel,
    "defaultThinkingLevel" to s.defaultThinkingLevel,
    "compactionTriggerPercent" to s.compactionTriggerPercent.toString(),
    "servePort" to s.servePort.toString(),
    "language" to s.language,
    "sendKey" to s.sendKey,
    "sendWhileRunning" to s.sendWhileRunning,
    "chatFontScale" to s.chatFontScale.toString(),
    "processDetail" to s.processDetail,
    "autoRetry" to s.autoRetry.toString(),
)

internal class RelayDevicesCommand : CliktCommand(name = "devices") {
    override fun help(context: Context): String = "List, rename, or revoke paired Relay devices."

    init {
        subcommands(
            RelayDevicesListCommand(),
            RelayDevicesRenameCommand(),
            RelayDevicesRevokeCommand(),
        )
    }

    override fun run() = Unit
}

private class RelayDevicesListCommand : CliktCommand(name = "list") {
    private val holder by requireObject<ControllerHolder>()

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val selfId = controller.relayStore.read()?.deviceId
            val devices = runCatching { controller.relayStore.readDevices() }.getOrDefault(emptyList())
            if (devices.isEmpty()) {
                holder.env.out.println("No paired devices cached yet.")
            } else {
                devices.forEach { d ->
                    val tag = if (d.deviceId == selfId) " [self]" else ""
                    val status = if (d.online) "online" else "offline"
                    holder.env.out.println("${d.deviceId}$tag · ${d.name} (${d.platform}, $status, ${d.bots.size} bots)")
                }
            }
            0
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class RelayDevicesRenameCommand : CliktCommand(name = "rename") {
    private val holder by requireObject<ControllerHolder>()
    private val deviceId by argument("deviceId", help = "Device ID")
    private val name by argument("name", help = "New display name")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val account = controller.relayStore.read()
            val token = runCatching { controller.relayStore.token() }.getOrNull()
            if (account == null || token.isNullOrBlank()) {
                holder.env.err.println("Not signed in to Relay.")
                return@withController 1
            }
            val cleanId = deviceId.trim()
            val cleanName = name.trim()
            if (cleanId.isEmpty() || cleanName.isEmpty()) {
                holder.env.err.println("Device ID and new name must not be empty.")
                return@withController 2
            }
            try {
                controller.relayClient.renameDevice(account, token, cleanId, cleanName)
                if (cleanId == account.deviceId) {
                    controller.settingsStore.update { it.copy(deviceName = cleanName) }
                    controller.relayStore.save(account.copy(deviceName = cleanName), token)
                }
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
                holder.env.out.println("Renamed device $cleanId -> '$cleanName'.")
                0
            } catch (err: RelayException) {
                holder.env.err.println("Failed to rename device: ${err.failure.name}")
                1
            } catch (err: java.io.IOException) {
                holder.env.err.println("Failed to rename device: ${err.message ?: "network error"}")
                1
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

private class RelayDevicesRevokeCommand : CliktCommand(name = "revoke") {
    private val holder by requireObject<ControllerHolder>()
    private val deviceId by argument("deviceId", help = "Device ID to revoke")

    override fun run() {
        val code = holder.withController { controller ->
            controller.bootstrap()
            val account = controller.relayStore.read()
            val token = runCatching { controller.relayStore.token() }.getOrNull()
            if (account == null || token.isNullOrBlank()) {
                holder.env.err.println("Not signed in to Relay.")
                return@withController 1
            }
            val cleanId = deviceId.trim()
            if (cleanId.isEmpty()) {
                holder.env.err.println("Device ID must not be empty.")
                return@withController 2
            }
            try {
                controller.relayClient.revokeDevice(account, token, cleanId)
                if (cleanId == account.deviceId) {
                    controller.relayStore.clear()
                }
                LocalControlClient(controller.paths.serveLockFile).use { it.reloadDaemon() }
                holder.env.out.println("Revoked device $cleanId.")
                0
            } catch (err: RelayException) {
                holder.env.err.println("Failed to revoke device: ${err.failure.name}")
                1
            } catch (err: java.io.IOException) {
                holder.env.err.println("Failed to revoke device: ${err.message ?: "network error"}")
                1
            }
        }
        if (code != 0) throw ProgramResult(code)
    }
}

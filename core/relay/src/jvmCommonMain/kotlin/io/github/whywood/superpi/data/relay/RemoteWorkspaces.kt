package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.runtime.RemoteBotRef
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import java.io.File

data class RemoteBot(
    val deviceId: String,
    val deviceName: String,
    val botId: String,
    val botName: String,
    val online: Boolean,
    val avatar: String? = null,
    val capabilities: List<String> = emptyList(),
    val intro: String = "",
    val botCapabilities: RelayBotCapabilities? = null,
    val platform: String = "",
)

fun resolveDeviceOs(platform: String, capabilities: List<String> = emptyList()): String {
    val capOs = capabilities
        .firstOrNull { it.startsWith("os:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    val raw = capOs.ifEmpty { platform.trim().lowercase() }
    return when {
        raw.contains("mac") || raw.contains("darwin") || raw.contains("apple") -> "macos"
        raw.contains("win") -> "windows"
        raw.contains("android") -> "android"
        raw.contains("ios") || raw.contains("iphone") || raw.contains("ipad") -> "ios"
        raw.contains("linux") -> "linux"
        raw.contains("desktop") || raw.contains("cli") -> "desktop"
        else -> raw
    }
}

object RemoteWorkspaces {
    private const val ID_PREFIX = "remote:"

    fun workspaceId(bot: RemoteBot): String = "$ID_PREFIX${bot.deviceId}:${bot.botId}"

    fun directory(root: File, bot: RemoteBot): File =
        File(File(root, safeSegment(bot.deviceId)), safeSegment(bot.botId))

    fun workspace(root: File, bot: RemoteBot): RuntimeWorkspace = RuntimeWorkspace(
        id = workspaceId(bot),
        name = bot.botName,
        cwd = directory(root, bot).absolutePath,
        avatar = bot.avatar,
        intro = bot.intro.trim().takeIf { it.isNotEmpty() },
        remote = RemoteBotRef(
            deviceId = bot.deviceId,
            deviceName = bot.deviceName,
            botId = bot.botId,
            botName = bot.botName,
            online = bot.online,
            devicePlatform = resolveDeviceOs(bot.platform, bot.capabilities),
        ),
    )

    fun workspaces(root: File, bots: List<RemoteBot>): List<RuntimeWorkspace> =
        bots.map { bot -> workspace(root, bot) }

    fun remoteRoot(filesDir: File): File = File(filesDir, "remote")

    private fun safeSegment(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").replace("..", "__").trim('.')
        return cleaned.ifEmpty { "x" }.take(64)
    }
}

package io.github.whywood.superpi.plugin.relational

import java.io.File
import org.json.JSONObject

class AutonomousRelationalAgent(
    private val store: LocalRelationalStore,
    private val configDir: File,
) {
    private val configFile = File(configDir, "autonomous_config.json")
    private var cachedConfig: AutonomousIngestionConfig = loadConfig()

    fun getConfig(): AutonomousIngestionConfig = cachedConfig

    fun updateConfig(config: AutonomousIngestionConfig) {
        cachedConfig = config
        configFile.parentFile?.mkdirs()
        configFile.writeText(config.toJson().toString())
    }

    fun setAuthorization(isAuthorized: Boolean): AutonomousIngestionConfig {
        val updated = cachedConfig.copy(isAuthorized = isAuthorized)
        updateConfig(updated)
        return updated
    }

    /**
     * 执行后台自主聊天记录浏览与同步。
     * 双重安全与性能门禁：
     * 1. 授权门禁：必须在用户明确开启后台授权 (isAuthorized == true) 下方可运行，严禁擅自后台扫描聊天；
     * 2. 空闲门禁：必须在系统无正在运行的前台任务或用户会话 (isSystemIdle == true) 时运行，避免抢占计算资源或侵扰用户。
     */
    fun executeAutonomousBrowse(
        isSystemIdle: Boolean,
        discoveredChatFrames: List<IncomingChatFrame>,
    ): AutonomousScanResult {
        if (!cachedConfig.isAuthorized) {
            return AutonomousScanResult(
                executed = false,
                reason = "UNAUTHORIZED: 用户尚未开启后台自动浏览授权，严禁扫描任何聊天记录以保障隐私安全",
            )
        }

        if (!isSystemIdle) {
            return AutonomousScanResult(
                executed = false,
                reason = "SYSTEM_BUSY: 系统当前有正在执行的前台任务或用户会话，暂缓后台聊天记录同步",
            )
        }

        if (discoveredChatFrames.isEmpty()) {
            return AutonomousScanResult(
                executed = true,
                reason = "NO_NEW_CHAT_FRAMES: 未检测到待同步的聊天记录帧",
                framesProcessed = 0,
                messagesIngested = 0,
            )
        }

        var totalIngested = 0
        val reconciled = mutableListOf<String>()

        for (frame in discoveredChatFrames) {
            val resolution = store.ingestChatFrame(frame)
            totalIngested += frame.newMessages.size
            if (resolution.nameChanged || resolution.avatarChanged || resolution.strategy == MatchStrategy.DIALOGUE_CONTINUITY) {
                val currentContact = store.getContact(resolution.contactId)
                val contactLabel = currentContact?.name ?: resolution.contactId
                reconciled.add("${resolution.contactId} ($contactLabel): ${resolution.explanation}")
            }
        }

        updateConfig(cachedConfig.copy(lastScannedAt = System.currentTimeMillis()))

        return AutonomousScanResult(
            executed = true,
            reason = "AUTONOMOUS_SCAN_SUCCESS",
            framesProcessed = discoveredChatFrames.size,
            messagesIngested = totalIngested,
            reconciledContacts = reconciled,
        )
    }

    private fun loadConfig(): AutonomousIngestionConfig {
        if (!configFile.exists()) {
            return AutonomousIngestionConfig()
        }
        return runCatching {
            AutonomousIngestionConfig.fromJson(JSONObject(configFile.readText()))
        }.getOrDefault(AutonomousIngestionConfig())
    }
}

package io.github.whywood.superpi.host.config

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.SuperRuntimeSettings
import io.github.whywood.superpi.runtime.ThinkingLevel
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HostSuperSettings(
    val maxConcurrentWorkers: Int = 5,
    val workerMaxSlotsPerBot: Int = SuperRuntime.MAX_WORKERS_PER_BOT,
    val workerIdleCleanupMinutes: Int = 15,
    val workerStallTimeoutMinutes: Int = 10,
    val questionTimeoutMinutes: Int = 3,
    val coalesceWindowMs: Long = 1_500L,
    val receiptMaxChars: Int = 1_500,
    val workerJobWake: Boolean = true,
    val workerCompactionOverrideEnabled: Boolean = false,
    val workerCompactionTriggerPercent: Int = 75,
) {
    fun toRuntime(): SuperRuntimeSettings = SuperRuntimeSettings(
        maxConcurrentWorkers = maxConcurrentWorkers.coerceIn(1, 10),
        maxSlotsPerBot = workerMaxSlotsPerBot.coerceIn(1, 16),
        idleEvictMs = workerIdleCleanupMinutes.coerceIn(5, 1440) * 60_000L,
        stallTimeoutMs = workerStallTimeoutMinutes.coerceIn(0, 60) * 60_000L,
        questionTimeoutMs = questionTimeoutMinutes.coerceIn(0, 60) * 60_000L,
        coalesceWindowMs = coalesceWindowMs.coerceIn(0L, 10_000L),
        receiptMaxChars = receiptMaxChars.coerceIn(500, 10_000),
        workerJobWake = workerJobWake,
        workerCompactionOverrideEnabled = workerCompactionOverrideEnabled,
        workerCompactionTriggerPercent = workerCompactionTriggerPercent.coerceIn(50, 95),
    )
}

@Serializable
data class HostSettings(
    val `$schema`: String = AppBrand.SETTINGS_SCHEMA_URL,
    val deviceName: String = defaultDeviceName(),
    val defaultProvider: String = ProviderCatalog.DEFAULT_PROVIDER_ID,
    val defaultModel: String = ProviderCatalog.DEFAULT_MODEL_ID,
    val defaultThinkingLevel: String = ThinkingLevel.OFF.name,
    val compactionTriggerPercent: Int = 75,
    val servePort: Int = 0,
    val language: String = "system",
    val sendKey: String = "enter",
    val sendWhileRunning: String = "steer",
    val chatFontScale: Int = 100,
    val processDetail: String = "detailed",
    val autoRetry: Boolean = true,
    val backgroundJobWake: Boolean = true,
    val autoSessionTitle: Boolean = true,
    val sessionTitleModel: String = "",
    val userNickname: String = "",
    val userAvatar: String = "",
    val customSystemInstruction: String = "",
    val botPermissionModes: Map<String, String> = emptyMap(),
    val superMode: HostSuperSettings = HostSuperSettings(),
    val disabledExtensions: List<String> = emptyList(),
) {
    fun toModelConfig(): ModelConfig = ModelConfig(
        providerId = defaultProvider.trim().ifEmpty { ProviderCatalog.DEFAULT_PROVIDER_ID },
        modelId = defaultModel.trim().ifEmpty { ProviderCatalog.DEFAULT_MODEL_ID },
        thinkingLevel = ThinkingLevel.entries.firstOrNull {
            it.name.equals(defaultThinkingLevel.trim(), ignoreCase = true)
        } ?: ThinkingLevel.OFF,
    )

    companion object {
        fun defaultDeviceName(): String =
            runCatching { InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("localhost", ignoreCase = true) }
                ?: run {
                    val os = System.getProperty("os.name").orEmpty().lowercase()
                    when {
                        "mac" in os || "darwin" in os -> "macOS Host"
                        "win" in os -> "Windows Host"
                        else -> "Linux Host"
                    }
                }
    }
}

class HostSettingsStore(
    private val file: File,
) : ModelSettings {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val mutex = Mutex()
    private val state = MutableStateFlow(readFromDisk())

    val settings: StateFlow<HostSettings> = state.asStateFlow()

    override val config: Flow<ModelConfig> = state
        .map { it.toModelConfig() }
        .distinctUntilChanged()

    fun current(): HostSettings = state.value

    override suspend fun read(): ModelConfig = state.value.toModelConfig()

    override suspend fun save(config: ModelConfig) {
        update { current ->
            current.copy(
                defaultProvider = config.providerId,
                defaultModel = config.modelId,
                defaultThinkingLevel = config.thinkingLevel.name,
            )
        }
    }

    suspend fun update(transform: (HostSettings) -> HostSettings): HostSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = normalize(transform(state.value))
            writeToDisk(next)
            state.value = next
            next
        }
    }

    suspend fun ensureInitialized() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.isFile) {
                    val initial = normalize(state.value)
                    writeToDisk(initial)
                    state.value = initial
                }
            }
        }
    }

    suspend fun reload(): HostSettings = withContext(Dispatchers.IO) {
        mutex.withLock {
            val reloaded = readFromDisk()
            state.value = reloaded
            reloaded
        }
    }

    private fun readFromDisk(): HostSettings {
        if (!file.isFile) return HostSettings()
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return HostSettings()
        return runCatching {
            normalize(json.decodeFromString(HostSettings.serializer(), raw))
        }.getOrDefault(HostSettings())
    }

    private fun normalize(value: HostSettings): HostSettings = value.copy(
        `$schema` = value.`$schema`.ifBlank { AppBrand.SETTINGS_SCHEMA_URL },
        deviceName = value.deviceName.trim().ifEmpty { HostSettings.defaultDeviceName() },
        defaultProvider = value.defaultProvider.trim().ifEmpty { ProviderCatalog.DEFAULT_PROVIDER_ID },
        defaultModel = value.defaultModel.trim().ifEmpty { ProviderCatalog.DEFAULT_MODEL_ID },
        defaultThinkingLevel = (ThinkingLevel.entries.firstOrNull {
            it.name.equals(value.defaultThinkingLevel.trim(), ignoreCase = true)
        } ?: ThinkingLevel.OFF).name,
        compactionTriggerPercent = value.compactionTriggerPercent.coerceIn(50, 95),
        servePort = value.servePort.coerceIn(0, 65535),
        language = when (value.language.trim().lowercase()) {
            "zh", "zh-cn", "zh_cn" -> "zh-CN"
            "en", "en-us", "en_us" -> "en"
            else -> "system"
        },
        sendKey = if (value.sendKey.trim().equals("mod_enter", ignoreCase = true)) "mod_enter" else "enter",
        sendWhileRunning = if (value.sendWhileRunning.trim().equals("queue", ignoreCase = true)) "queue" else "steer",
        chatFontScale = value.chatFontScale.coerceIn(85, 140),
        processDetail = if (value.processDetail.trim().equals("compact", ignoreCase = true)) "compact" else "detailed",
        sessionTitleModel = value.sessionTitleModel.trim().take(128),
        userNickname = value.userNickname.trim().take(64),
        userAvatar = value.userAvatar.trim().take(262_144),
        customSystemInstruction = value.customSystemInstruction.trim().take(4_000),
        disabledExtensions = value.disabledExtensions.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    )

    private fun writeToDisk(value: HostSettings) {
        val parent = file.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create settings directory: ${parent.absolutePath}")
        }
        val tempPath = Files.createTempFile(parent.toPath(), ".${file.name}.", ".tmp")
        val temp = tempPath.toFile()
        try {
            temp.writeText(json.encodeToString(HostSettings.serializer(), value) + "\n", Charsets.UTF_8)
            try {
                Files.move(
                    tempPath,
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
    }
}

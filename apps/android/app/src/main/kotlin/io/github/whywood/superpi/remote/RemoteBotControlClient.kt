package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.WorkspaceToolPolicy
import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.ThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface RemoteControlResult<out T> {
    data class Ok<T>(val value: T) : RemoteControlResult<T>
    data class Error(val code: String, val message: String) : RemoteControlResult<Nothing>
}

data class RemoteModelRef(
    val providerId: String,
    val modelId: String,
    val name: String,
)

data class RemoteBotState(
    val sessionId: String,
    val busy: Boolean,
    val model: RemoteModelRef? = null,
    val thinkingLevel: ThinkingLevel? = null,
    val usage: SessionUsage? = null,
)

data class RemoteModelsSnapshot(
    val models: List<ModelOption>,
    val providers: List<ProviderSummary>,
    val thinkingLevels: List<ThinkingLevel>,
    val currentModel: RemoteModelRef? = null,
    val currentThinkingLevel: ThinkingLevel? = null,
)

data class RemoteSlashCommand(
    val name: String,
    val description: String,
    val source: String,
)

data class RemoteCompactResult(
    val sessionId: String,
    val compacted: Boolean,
    val tokensBefore: Long? = null,
    val tokensAfter: Long? = null,
    val summary: String? = null,
    val usage: SessionUsage? = null,
)

data class RemoteInstructionsResult(
    val content: String,
    val saved: Boolean = false,
)

data class RemoteReloadResult(
    val reloaded: Boolean,
    val botId: String,
    val sessionId: String? = null,
)

data class RemoteCapabilityPlugin(
    val id: String,
    val name: String,
)

data class RemoteBotCapabilitiesSnapshot(
    val botId: String,
    val isHome: Boolean,
    val policy: WorkspaceToolPolicy,
    val skills: List<String> = emptyList(),
    val plugins: List<RemoteCapabilityPlugin> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val screenToolsReady: Boolean = false,
)

class RemoteBotControlClient(
    private val transport: RemoteAgentTransport,
) {
    suspend fun fetchState(
        deviceId: String,
        botId: String,
        sessionId: String,
    ): RemoteControlResult<RemoteBotState> {
        checkConnected(deviceId)?.let { return it }
        val params = buildJsonObject {
            if (botId.isNotBlank()) put("botId", botId)
            put("sessionId", sessionId)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.state", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                    ?: return RemoteControlResult.Error("bad_response", "invalid bot state response")
                RemoteControlResult.Ok(
                    RemoteBotState(
                        sessionId = json.str("sessionId").orEmpty(),
                        busy = json.bool("busy") ?: false,
                        model = parseModelRef(json["model"] as? JsonObject),
                        thinkingLevel = json.str("thinkingLevel")?.let(::parseThinkingLevel),
                        usage = parseUsage(json["usage"] as? JsonObject),
                    ),
                )
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun fetchModels(
        deviceId: String,
        botId: String,
        sessionId: String? = null,
    ): RemoteControlResult<RemoteModelsSnapshot> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionId", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.models", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                    ?: return RemoteControlResult.Error("bad_response", "invalid models response")
                val topThinkingLevels = parseThinkingLevels(json["thinkingLevels"])
                    .ifEmpty { ThinkingLevel.entries }
                val models = parseModels(json["models"], topThinkingLevels)
                val providers = parseProviders(json["providers"], models)
                RemoteControlResult.Ok(
                    RemoteModelsSnapshot(
                        models = models,
                        providers = providers,
                        thinkingLevels = topThinkingLevels,
                        currentModel = parseModelRef(json["model"] as? JsonObject),
                        currentThinkingLevel = json.str("thinkingLevel")?.let(::parseThinkingLevel),
                    ),
                )
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun setModel(
        deviceId: String,
        botId: String,
        providerId: String,
        modelId: String,
        sessionId: String? = null,
        thinkingLevel: ThinkingLevel? = null,
    ): RemoteControlResult<Pair<RemoteModelRef?, ThinkingLevel?>> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("provider", providerId)
            put("modelId", modelId)
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionId", it) }
            thinkingLevel?.let { put("thinkingLevel", it.name.lowercase()) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.set_model", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result ?: buildJsonObject {}
                val ref = parseModelRef(json["model"] as? JsonObject)
                    ?: RemoteModelRef(providerId = providerId, modelId = modelId, name = modelId)
                val level = json.str("thinkingLevel")?.let(::parseThinkingLevel) ?: thinkingLevel
                RemoteControlResult.Ok(ref to level)
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun setThinkingLevel(
        deviceId: String,
        botId: String,
        thinkingLevel: ThinkingLevel,
        sessionId: String? = null,
    ): RemoteControlResult<ThinkingLevel> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("thinkingLevel", thinkingLevel.name.lowercase())
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionId", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.set_thinking", params)) {
            is RemoteCallOutcome.Ok -> {
                val returned = outcome.result?.str("thinkingLevel")?.let(::parseThinkingLevel) ?: thinkingLevel
                RemoteControlResult.Ok(returned)
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun fetchCommands(
        deviceId: String,
        botId: String,
    ): RemoteControlResult<List<RemoteSlashCommand>> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject { put("botId", botId) }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.commands", params)) {
            is RemoteCallOutcome.Ok -> {
                val array = outcome.result?.get("commands") as? JsonArray ?: JsonArray(emptyList())
                val commands = array.mapNotNull { item ->
                    val json = item as? JsonObject ?: return@mapNotNull null
                    val name = json.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    RemoteSlashCommand(
                        name = name,
                        description = json.str("description").orEmpty(),
                        source = json.str("source") ?: "builtin",
                    )
                }
                RemoteControlResult.Ok(commands)
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun readInstructions(
        deviceId: String,
        botId: String,
    ): RemoteControlResult<RemoteInstructionsResult> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject { put("botId", botId) }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.instructions.get", params)) {
            is RemoteCallOutcome.Ok -> {
                val content = outcome.result?.str("content").orEmpty()
                RemoteControlResult.Ok(RemoteInstructionsResult(content = content))
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun writeInstructions(
        deviceId: String,
        botId: String,
        content: String,
    ): RemoteControlResult<RemoteInstructionsResult> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_INSTRUCTIONS_BYTES) {
            return RemoteControlResult.Error("payload_too_large", "instructions exceed 64 KiB limit")
        }
        val params = buildJsonObject {
            put("botId", botId)
            put("content", content)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.instructions.set", params)) {
            is RemoteCallOutcome.Ok -> {
                val savedContent = outcome.result?.str("content") ?: content
                val saved = outcome.result?.bool("saved") ?: true
                RemoteControlResult.Ok(RemoteInstructionsResult(content = savedContent, saved = saved))
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun reloadBot(
        deviceId: String,
        botId: String,
        sessionId: String? = null,
    ): RemoteControlResult<RemoteReloadResult> {
        checkCapability(deviceId, CAP_BOT_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionId", it) }
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.reload", params, timeoutMillis = RELOAD_TIMEOUT_MILLIS)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result ?: buildJsonObject {}
                RemoteControlResult.Ok(
                    RemoteReloadResult(
                        reloaded = json.bool("reloaded") ?: true,
                        botId = json.str("botId") ?: botId,
                        sessionId = json.str("sessionId"),
                    ),
                )
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun compactSession(
        deviceId: String,
        botId: String,
        sessionId: String? = null,
        customInstructions: String? = null,
    ): RemoteControlResult<RemoteCompactResult> {
        checkCapability(deviceId, CAP_SESSION_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sessionId", it) }
            customInstructions?.trim()?.takeIf { it.isNotEmpty() }?.let { put("customInstructions", it) }
        }
        return when (
            val outcome = transport.callWithOutcome(deviceId, "session.compact", params, timeoutMillis = COMPACT_TIMEOUT_MILLIS)
        ) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                    ?: return RemoteControlResult.Error("bad_response", "invalid compact response")
                RemoteControlResult.Ok(
                    RemoteCompactResult(
                        sessionId = json.str("sessionId") ?: sessionId.orEmpty(),
                        compacted = json.bool("compacted") ?: true,
                        tokensBefore = json.long("tokensBefore"),
                        tokensAfter = json.long("tokensAfter"),
                        summary = json.str("summary"),
                        usage = parseUsage(json["usage"] as? JsonObject),
                    ),
                )
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun retrySession(
        deviceId: String,
        botId: String,
        sessionId: String,
    ): RemoteControlResult<String> {
        checkCapability(deviceId, CAP_SESSION_CONTROLS)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put("sessionId", sessionId)
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "session.retry", params)) {
            is RemoteCallOutcome.Ok -> RemoteControlResult.Ok(outcome.result?.str("sessionId") ?: sessionId)
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun getCapabilities(
        deviceId: String,
        botId: String,
    ): RemoteControlResult<RemoteBotCapabilitiesSnapshot> {
        checkCapability(deviceId, CAP_BOT_CAPABILITIES)?.let { return it }
        val params = buildJsonObject { put("botId", botId) }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.capabilities.get", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                    ?: return RemoteControlResult.Error("bad_response", "invalid capabilities response")
                val parsed = parseCapabilitiesSnapshot(json, botId)
                    ?: return RemoteControlResult.Error("bad_response", "invalid capabilities response")
                RemoteControlResult.Ok(parsed)
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    suspend fun setCapabilities(
        deviceId: String,
        botId: String,
        policy: WorkspaceToolPolicy,
    ): RemoteControlResult<RemoteBotCapabilitiesSnapshot> {
        checkCapability(deviceId, CAP_BOT_CAPABILITIES)?.let { return it }
        val params = buildJsonObject {
            put("botId", botId)
            put(
                "policy",
                buildJsonObject {
                    put("allCapabilities", policy.allCapabilities)
                    put("skills", buildJsonArray { policy.skills.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("plugins", buildJsonArray { policy.plugins.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("mcpServers", buildJsonArray { policy.mcpServers.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("screenTools", policy.screenTools)
                    put("miniappTools", policy.miniappTools)
                    policy.memoryScope?.let { put("memoryScope", it.wire) }
                    policy.memoryDispatch?.let { put("memoryDispatch", it) }
                },
            )
        }
        return when (val outcome = transport.callWithOutcome(deviceId, "bot.capabilities.set", params)) {
            is RemoteCallOutcome.Ok -> {
                val json = outcome.result
                    ?: return RemoteControlResult.Error("bad_response", "invalid capabilities response")
                val parsed = parseCapabilitiesSnapshot(json, botId)
                    ?: return RemoteControlResult.Error("bad_response", "invalid capabilities response")
                RemoteControlResult.Ok(parsed)
            }
            is RemoteCallOutcome.Failed -> RemoteControlResult.Error(outcome.error.code, outcome.error.message)
        }
    }

    private fun parseCapabilitiesSnapshot(json: JsonObject, fallbackBotId: String): RemoteBotCapabilitiesSnapshot? {
        val policyObj = json["policy"] as? JsonObject ?: return null
        val availableObj = json["available"] as? JsonObject ?: buildJsonObject {}
        val isHome = json.bool("isHome") ?: false
        val policy = WorkspaceToolPolicy(
            allCapabilities = policyObj.bool("allCapabilities") ?: isHome,
            skills = parseStringSet(policyObj["skills"]),
            plugins = parseStringSet(policyObj["plugins"]),
            mcpServers = parseStringSet(policyObj["mcpServers"]),
            screenTools = policyObj.bool("screenTools") ?: false,
            miniappTools = policyObj.bool("miniappTools") ?: false,
            memoryScope = policyObj.str("memoryScope")?.let(::parseMemoryScopeStrict),
            memoryDispatch = policyObj.bool("memoryDispatch"),
        )
        val plugins = (availableObj["plugins"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: id
            RemoteCapabilityPlugin(id = id, name = name)
        }
        return RemoteBotCapabilitiesSnapshot(
            botId = json.str("botId")?.takeIf { it.isNotBlank() } ?: fallbackBotId,
            isHome = isHome,
            policy = policy,
            skills = parseStringList(availableObj["skills"]),
            plugins = plugins,
            mcpServers = parseStringList(availableObj["mcpServers"]),
            screenToolsReady = availableObj.bool("screenToolsReady") ?: false,
        )
    }

    private fun parseStringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun parseStringSet(element: kotlinx.serialization.json.JsonElement?): Set<String> =
        parseStringList(element).toCollection(LinkedHashSet())

    private fun checkConnected(deviceId: String): RemoteControlResult.Error? {
        if (!transport.isRelayOnline.value) {
            return RemoteControlResult.Error(RemoteCallCodes.NOT_CONNECTED, "relay is not connected")
        }
        if (deviceId !in transport.onlineDevices.value) {
            return RemoteControlResult.Error(RemoteCallCodes.NOT_CONNECTED, "device is offline")
        }
        return null
    }

    private fun checkCapability(deviceId: String, capability: String): RemoteControlResult.Error? {
        checkConnected(deviceId)?.let { return it }
        if (!transport.supportsPeerCapability(deviceId, capability)) {
            return RemoteControlResult.Error(CAPABILITY_UNAVAILABLE, "upgrade required")
        }
        return null
    }

    private fun parseModels(
        element: kotlinx.serialization.json.JsonElement?,
        defaultThinkingLevels: List<ThinkingLevel>,
    ): List<ModelOption> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val json = item as? JsonObject ?: return@mapNotNull null
            val providerId = json.str("provider")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = json.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = json.str("name")?.takeIf { it.isNotBlank() } ?: id
            val explicitLevels = parseThinkingLevels(json["thinkingLevels"])
            val levels = if (explicitLevels.isNotEmpty()) {
                explicitLevels
            } else if (json.bool("reasoning") == true) {
                defaultThinkingLevels
            } else {
                listOf(ThinkingLevel.OFF)
            }
            ModelOption(
                providerId = providerId,
                id = id,
                displayName = name,
                thinkingLevels = levels,
            )
        }
    }

    private fun parseProviders(
        element: kotlinx.serialization.json.JsonElement?,
        models: List<ModelOption>,
    ): List<ProviderSummary> {
        val array = element as? JsonArray
        if (array != null && array.isNotEmpty()) {
            val parsed = array.mapNotNull { item ->
                val json = item as? JsonObject ?: return@mapNotNull null
                val id = json.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ProviderSummary(
                    id = id,
                    name = json.str("name")?.takeIf { it.isNotBlank() } ?: providerDisplayName(id),
                    modelCount = json.int("modelCount") ?: models.count { it.providerId == id },
                    baseUrl = json.str("baseUrl"),
                    hasOAuth = json.bool("hasOAuth") ?: false,
                    supportsApiKey = json.bool("supportsApiKey") ?: true,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return models.groupBy { it.providerId }.map { (providerId, group) ->
            ProviderSummary(
                id = providerId,
                name = providerDisplayName(providerId),
                modelCount = group.size,
                baseUrl = null,
                hasOAuth = false,
                supportsApiKey = true,
            )
        }
    }

    private fun providerDisplayName(id: String): String = when (id.lowercase()) {
        "anthropic" -> "Anthropic"
        "openai", "openai-codex" -> "OpenAI"
        "google", "google-gemini-cli", "google-antigravity" -> "Google"
        "deepseek" -> "DeepSeek"
        "xai" -> "xAI"
        "openrouter" -> "OpenRouter"
        "groq" -> "Groq"
        else -> id.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun parseModelRef(json: JsonObject?): RemoteModelRef? {
        if (json == null) return null
        val provider = json.str("provider")?.takeIf { it.isNotBlank() } ?: return null
        val id = json.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = json.str("name")?.takeIf { it.isNotBlank() } ?: id
        return RemoteModelRef(providerId = provider, modelId = id, name = name)
    }

    companion object {
        const val CAP_BOT_CONTROLS = "bot-controls"
        const val CAP_SESSION_CONTROLS = "session-controls"
        const val CAP_BOT_CAPABILITIES = "bot-capabilities"
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
        const val MAX_INSTRUCTIONS_BYTES = 64 * 1024
        private const val RELOAD_TIMEOUT_MILLIS = 30_000L
        private const val COMPACT_TIMEOUT_MILLIS = 120_000L

        fun parseThinkingLevel(raw: String): ThinkingLevel? {
            val clean = raw.trim().lowercase()
            if (clean.isEmpty()) return null
            if (clean == "minimal") return ThinkingLevel.LOW
            return ThinkingLevel.entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        }

        fun parseThinkingLevels(element: kotlinx.serialization.json.JsonElement?): List<ThinkingLevel> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(::parseThinkingLevel)
            }.distinct()
        }

        fun parseUsage(json: JsonObject?): SessionUsage? {
            if (json == null) return null
            val contextTokens = json.long("contextTokens") ?: 0L
            val contextWindow = json.long("contextWindow") ?: 0L
            val inputTokens = json.long("inputTokens") ?: 0L
            val outputTokens = json.long("outputTokens") ?: 0L
            val cacheReadTokens = json.long("cacheReadTokens") ?: 0L
            val cacheWriteTokens = json.long("cacheWriteTokens") ?: 0L
            val cost = json.double("cost") ?: 0.0
            if (
                contextTokens == 0L &&
                contextWindow == 0L &&
                inputTokens == 0L &&
                outputTokens == 0L &&
                cacheReadTokens == 0L &&
                cacheWriteTokens == 0L &&
                cost == 0.0
            ) {
                return null
            }
            return SessionUsage(
                contextTokens = contextTokens,
                contextWindow = contextWindow,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
                cacheWriteTokens = cacheWriteTokens,
                cost = cost,
            )
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.bool(key: String): Boolean? =
            (this[key] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.intOrNull

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.longOrNull

        private fun JsonObject.double(key: String): Double? =
            (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun parseMemoryScopeStrict(raw: String): MemoryAccessScope? =
            when (raw.trim().uppercase()) {
                "OFF" -> MemoryAccessScope.OFF
                "WORK_ONLY" -> MemoryAccessScope.WORK_ONLY
                "PERSONAL" -> MemoryAccessScope.PERSONAL
                else -> null
            }
    }
}

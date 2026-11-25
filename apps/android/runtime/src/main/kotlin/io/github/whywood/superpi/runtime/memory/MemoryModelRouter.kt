package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.ToolCall
import kotlinx.coroutines.CancellationException

enum class MemoryModelTier {
    HOT,
    COLD,
}

data class ResolvedMemoryModel(
    val providerId: String,
    val modelId: String,
) {
    val ref: String get() = "$providerId/$modelId"
}

sealed interface MemoryModelResolution {
    data class Ready(val model: ResolvedMemoryModel) : MemoryModelResolution

    data object Paused : MemoryModelResolution

    data object Unavailable : MemoryModelResolution
}

sealed interface MemoryAgentStepResult {
    data class Success(
        val modelRef: String,
        val message: SdkAssistantMessage,
    ) : MemoryAgentStepResult

    data object Paused : MemoryAgentStepResult

    data object NoModel : MemoryAgentStepResult

    data class Failed(val reason: String) : MemoryAgentStepResult
}

interface MemoryAgentGateway {
    suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult

    suspend fun step(
        tier: MemoryModelTier,
        context: Context,
        sessionId: String?,
        maxTokens: Int,
    ): MemoryAgentStepResult = step(tier, context)
}

class MemoryModelRouter(
    private val providers: ProviderDirectory,
    private val modelSettings: ModelSettings,
    private val memorySettings: MemorySettingsSource,
    private val configuredProviders: () -> Set<String>,
) {
    suspend fun resolve(tier: MemoryModelTier): ResolvedMemoryModel? =
        (resolveDetailed(tier) as? MemoryModelResolution.Ready)?.model

    suspend fun resolve(isColdPath: Boolean): ResolvedMemoryModel? =
        resolve(if (isColdPath) MemoryModelTier.COLD else MemoryModelTier.HOT)

    suspend fun resolveDetailed(tier: MemoryModelTier): MemoryModelResolution {
        val currentSettings = memorySettings.read()
        if (currentSettings.mode == MemoryModelMode.PAUSED) return MemoryModelResolution.Paused

        val configured = configuredProviders()
        if (configured.isEmpty()) return MemoryModelResolution.Unavailable

        if (currentSettings.mode == MemoryModelMode.FIXED) {
            val fixedProvider = if (tier == MemoryModelTier.COLD) {
                currentSettings.effectiveColdProviderId()
            } else {
                currentSettings.effectiveHotProviderId()
            }
            val fixedModel = if (tier == MemoryModelTier.COLD) {
                currentSettings.effectiveColdModelId()
            } else {
                currentSettings.effectiveHotModelId()
            }
            if (!fixedProvider.isNullOrBlank() &&
                !fixedModel.isNullOrBlank() &&
                fixedProvider in configured &&
                providers.models(fixedProvider).any { it.id == fixedModel }
            ) {
                return MemoryModelResolution.Ready(ResolvedMemoryModel(fixedProvider, fixedModel))
            }
        }

        val defaultConfig = modelSettings.read()
        val providerId = if (defaultConfig.providerId in configured) {
            defaultConfig.providerId
        } else {
            providers.summaries.firstOrNull { it.id in configured }?.id ?: return MemoryModelResolution.Unavailable
        }

        val allModels = providers.models(providerId)
        val candidates = allModels.filter(::isToolCapableTextModel).ifEmpty { allModels }
        if (candidates.isEmpty()) return MemoryModelResolution.Unavailable

        val fallbackId = candidates.firstOrNull { it.id == defaultConfig.modelId }?.id
            ?: selectBestModel(candidates).id

        if (tier == MemoryModelTier.COLD) {
            return MemoryModelResolution.Ready(ResolvedMemoryModel(providerId, fallbackId))
        }

        val fastCandidate = selectFastestModel(candidates, fallbackId)
        return MemoryModelResolution.Ready(ResolvedMemoryModel(providerId, fastCandidate.id))
    }

    internal fun isToolCapableTextModel(model: ModelOption): Boolean {
        val tokens = "${model.id.lowercase()} ${model.displayName.lowercase()}"
            .split(TOKEN_SPLIT_REGEX)
            .filter { it.isNotEmpty() }
            .toSet()
        val idLower = model.id.lowercase()
        return NON_TOOL_TOKENS.none { it in tokens } &&
            !idLower.contains("search-preview") &&
            !idLower.contains("dall-e")
    }

    internal fun selectBestModel(models: List<ModelOption>): ModelOption {
        val pool = models.filter(::isToolCapableTextModel).ifEmpty { models }
        return pool.sortedWith(
            compareByDescending<ModelOption> { bestScore(it) }
                .thenComparator { a, b -> compareVersionsDescending(a.id, b.id) }
                .thenByDescending { it.id },
        ).first()
    }

    internal fun selectFastestModel(models: List<ModelOption>, fallbackId: String): ModelOption {
        if (models.isEmpty()) return ModelOption("", fallbackId, fallbackId, emptyList())
        val pool = models.filter(::isToolCapableTextModel).ifEmpty { models }
        val ranked = pool.sortedWith(
            compareByDescending<ModelOption> { fastScore(it.id) }
                .thenComparator { a, b -> compareVersionsDescending(a.id, b.id) }
                .thenByDescending { it.id == fallbackId }
                .thenByDescending { it.id },
        )
        val best = ranked.first()
        val fallbackScore = pool.firstOrNull { it.id == fallbackId }?.let { fastScore(it.id) } ?: Int.MIN_VALUE
        return if (fastScore(best.id) > fallbackScore) {
            best
        } else {
            pool.firstOrNull { it.id == fallbackId } ?: best
        }
    }

    internal fun bestScore(model: ModelOption): Int {
        val tokens = model.id.lowercase().split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }.toSet()
        return when {
            isReasoningModelTokens(tokens) -> 120
            "opus" in tokens || "ultra" in tokens || "max" in tokens -> 115
            "sonnet" in tokens || "pro" in tokens -> 110
            "lite" in tokens || "nano" in tokens -> 60
            "flash" in tokens || "haiku" in tokens || "mini" in tokens -> 80
            else -> 100
        }
    }

    internal fun fastScore(modelId: String): Int {
        val tokens = modelId.lowercase().split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }.toSet()
        if (isReasoningModelTokens(tokens)) return 50
        val isLiteOrNano = "lite" in tokens || "nano" in tokens
        return when {
            !isLiteOrNano && ("flash" in tokens || "haiku" in tokens || "mini" in tokens) -> 400
            "fast" in tokens || "chat" in tokens || "turbo" in tokens || "air" in tokens -> 300
            isLiteOrNano -> 200
            "opus" in tokens || "pro" in tokens || "sonnet" in tokens || "max" in tokens || "ultra" in tokens -> 80
            else -> 150
        }
    }

    private fun isReasoningModelTokens(tokens: Set<String>): Boolean =
        "reasoner" in tokens ||
            "thinking" in tokens ||
            "r1" in tokens ||
            "o1" in tokens ||
            "o3" in tokens ||
            "o4" in tokens ||
            tokens.any { it.contains("reasoner") || it.contains("thinking") }

    private fun compareVersionsDescending(aId: String, bId: String): Int {
        val va = VERSION_TOKEN_REGEX.findAll(aId).mapNotNull { it.value.toIntOrNull() }.toList()
        val vb = VERSION_TOKEN_REGEX.findAll(bId).mapNotNull { it.value.toIntOrNull() }.toList()
        val maxLen = maxOf(va.size, vb.size)
        for (i in 0 until maxLen) {
            val left = va.getOrElse(i) { 0 }
            val right = vb.getOrElse(i) { 0 }
            if (left != right) return right.compareTo(left)
        }
        return 0
    }

    private companion object {
        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
        private val VERSION_TOKEN_REGEX = Regex("\\d+")
        private val NON_TOOL_TOKENS = setOf(
            "audio",
            "tts",
            "image",
            "realtime",
            "embed",
            "embedding",
            "embeddings",
            "whisper",
            "transcribe",
            "moderation",
            "sora",
            "veo",
            "imagen",
        )
    }
}

class CatalogMemoryAgentGateway(
    private val providers: ProviderCatalog,
    private val router: MemoryModelRouter,
) : MemoryAgentGateway {
    override suspend fun step(tier: MemoryModelTier, context: Context): MemoryAgentStepResult =
        step(tier = tier, context = context, sessionId = null, maxTokens = 4096)

    override suspend fun step(
        tier: MemoryModelTier,
        context: Context,
        sessionId: String?,
        maxTokens: Int,
    ): MemoryAgentStepResult {
        val target = when (val resolution = router.resolveDetailed(tier)) {
            is MemoryModelResolution.Ready -> resolution.model
            MemoryModelResolution.Paused -> return MemoryAgentStepResult.Paused
            MemoryModelResolution.Unavailable -> return MemoryAgentStepResult.NoModel
        }
        return try {
            val message = providers.completeWithTools(
                providerId = target.providerId,
                modelId = target.modelId,
                context = context,
                temperature = MEMORY_TEMPERATURE,
                timeoutMs = MEMORY_TIMEOUT_MS,
                maxTokens = maxTokens,
                sessionId = sessionId,
            )
            if (message == null) {
                MemoryAgentStepResult.Failed("empty response from ${target.ref}")
            } else if (message.stopReason == StopReason.Error) {
                MemoryAgentStepResult.Failed(message.errorMessage ?: "error from ${target.ref}")
            } else if (message.stopReason == StopReason.Length && message.content.none { it is ToolCall }) {
                MemoryAgentStepResult.Failed("truncated response from ${target.ref}")
            } else {
                MemoryAgentStepResult.Success(modelRef = target.ref, message = message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MemoryAgentStepResult.Failed(error.message ?: "completion failed")
        }
    }

    private companion object {
        private const val MEMORY_TEMPERATURE = 0.1
        private const val MEMORY_TIMEOUT_MS = 45_000L
    }
}

package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.ProviderDirectory
import kotlinx.coroutines.CancellationException

enum class MemoryModelTier {
    FAST,
    DREAM,
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

data class MemoryCompletionResponse(
    val modelRef: String,
    val text: String,
)

sealed interface MemoryCompletionResult {
    data class Success(val response: MemoryCompletionResponse) : MemoryCompletionResult

    data object Paused : MemoryCompletionResult

    data object NoModel : MemoryCompletionResult

    data class Failed(val reason: String) : MemoryCompletionResult
}

interface MemoryCompletionGateway {
    suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult
}

class MemoryModelRouter(
    private val providers: ProviderDirectory,
    private val modelSettings: ModelSettings,
    private val memorySettings: MemorySettingsSource,
    private val configuredProviders: () -> Set<String>,
) {
    suspend fun resolve(tier: MemoryModelTier): ResolvedMemoryModel? =
        (resolveDetailed(tier) as? MemoryModelResolution.Ready)?.model

    suspend fun resolveDetailed(tier: MemoryModelTier): MemoryModelResolution {
        val currentSettings = memorySettings.read()
        if (currentSettings.mode == MemoryModelMode.PAUSED) return MemoryModelResolution.Paused

        val configured = configuredProviders()
        if (configured.isEmpty()) return MemoryModelResolution.Unavailable

        if (currentSettings.mode == MemoryModelMode.FIXED) {
            val fixedProvider = currentSettings.fixedProviderId
            val fixedModel = currentSettings.fixedModelId
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

        val models = providers.models(providerId)
        val fallbackId = models.firstOrNull { it.id == defaultConfig.modelId }?.id
            ?: models.firstOrNull()?.id
            ?: return MemoryModelResolution.Unavailable

        if (tier == MemoryModelTier.DREAM) {
            return MemoryModelResolution.Ready(ResolvedMemoryModel(providerId, fallbackId))
        }

        val fastCandidate = pickFastModel(models, fallbackId)
        return MemoryModelResolution.Ready(ResolvedMemoryModel(providerId, fastCandidate.id))
    }

    internal fun pickFastModel(models: List<ModelOption>, fallbackId: String): ModelOption {
        if (models.isEmpty()) return ModelOption("", fallbackId, fallbackId, emptyList())
        val ranked = models.sortedWith(
            compareByDescending<ModelOption> { fastScore(it.id, fallbackId) }
                .thenBy { it.id },
        )
        val best = ranked.first()
        return if (fastScore(best.id, fallbackId) > fastScore(fallbackId, fallbackId)) {
            best
        } else {
            models.firstOrNull { it.id == fallbackId } ?: best
        }
    }

    internal fun fastScore(modelId: String, fallbackId: String): Int {
        val id = modelId.lowercase()
        var score = 0
        if (id.contains("flash")) score += 100
        if (id.contains("haiku")) score += 95
        if (id.contains("mini")) score += 90
        if (id.contains("nano")) score += 85
        if (id.contains("lite")) score += 80
        if (id.contains("fast")) score += 75
        if (id.contains("chat")) score += 40
        if (id.contains("reasoner")) score -= 80
        if (id.contains("thinking")) score -= 80
        if (id.contains("-r1") || id == "r1") score -= 80
        if (id.contains("opus")) score -= 70
        if (id.contains("pro")) score -= 25
        if (Regex("(^|[-/])o[134]($|[-/])").containsMatchIn(id)) score -= 70
        if (modelId == fallbackId) score += 5
        return score
    }
}

class CatalogMemoryCompletionGateway(
    private val providers: ProviderCatalog,
    private val router: MemoryModelRouter,
) : MemoryCompletionGateway {
    override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
        val target = when (val resolution = router.resolveDetailed(tier)) {
            is MemoryModelResolution.Ready -> resolution.model
            MemoryModelResolution.Paused -> return MemoryCompletionResult.Paused
            MemoryModelResolution.Unavailable -> return MemoryCompletionResult.NoModel
        }
        return try {
            val text = providers.complete(
                providerId = target.providerId,
                modelId = target.modelId,
                prompt = prompt,
                temperature = MEMORY_TEMPERATURE,
                timeoutMs = MEMORY_TIMEOUT_MS,
            )?.takeIf { it.isNotBlank() }
            if (text == null) {
                MemoryCompletionResult.Failed("empty completion from ${target.ref}")
            } else {
                MemoryCompletionResult.Success(MemoryCompletionResponse(modelRef = target.ref, text = text))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            MemoryCompletionResult.Failed(error.message ?: "completion failed")
        }
    }

    private companion object {
        private const val MEMORY_TEMPERATURE = 0.1
        private const val MEMORY_TIMEOUT_MS = 45_000L
    }
}

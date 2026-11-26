package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ProviderDirectory {
    val summaries: List<ProviderSummary>

    val revision: StateFlow<Long>
        get() = STATIC_REVISION

    fun summary(providerId: String): ProviderSummary?

    fun models(providerId: String): List<ModelOption>

    fun resolveConfig(config: ModelConfig): ModelConfig

    fun sdkModel(providerId: String, modelId: String): io.github.whywood.superpi.sdk.ai.Model?

    fun modelReference(providerId: String, modelId: String): String

    companion object {
        private val STATIC_REVISION: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
    }
}

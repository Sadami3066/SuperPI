package io.github.whywood.superpi.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ModelConfigStore(private val store: KeyValueStore) : ModelSettings {
    override val config: Flow<ModelConfig> = store.entries
        .map { values ->
            val providerId = values.stringValue(PROVIDER_KEY)?.takeIf { it.isNotBlank() }
                ?: ProviderCatalog.DEFAULT_PROVIDER_ID
            val modelId = values.stringValue(MODEL_KEY)?.takeIf { it.isNotBlank() }
                ?: ProviderCatalog.DEFAULT_MODEL_ID
            val thinking = runCatching {
                ThinkingLevel.valueOf(values.stringValue(THINKING_KEY) ?: ThinkingLevel.OFF.name)
            }.getOrDefault(ThinkingLevel.OFF)
            ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = thinking)
        }

    override suspend fun read(): ModelConfig = config.first()

    override suspend fun save(config: ModelConfig) {
        store.edit { values ->
            values.putString(PROVIDER_KEY, config.providerId)
            values.putString(MODEL_KEY, config.modelId)
            values.putString(THINKING_KEY, config.thinkingLevel.name)
        }
    }

    private companion object {
        const val PROVIDER_KEY = "provider_id"
        const val MODEL_KEY = "model_id"
        const val THINKING_KEY = "thinking_level"
    }
}

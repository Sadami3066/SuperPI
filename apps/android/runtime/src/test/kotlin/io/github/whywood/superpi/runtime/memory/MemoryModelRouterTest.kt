package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.sdk.ai.Model
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryModelRouterTest {
    @Test
    fun autoModeNeverLeavesDefaultProviderAndSplitsFastFromDream() = runTest {
        val router = createRouter(
            defaultConfig = ModelConfig("anthropic", "claude-sonnet-4-5"),
            configured = setOf("anthropic", "deepseek"),
            memorySettings = MemorySettings(MemoryModelMode.AUTO),
        )

        val fast = router.resolve(MemoryModelTier.FAST)!!
        val dream = router.resolve(MemoryModelTier.DREAM)!!

        assertEquals("anthropic", fast.providerId)
        assertEquals("claude-haiku-4-5", fast.modelId)
        assertEquals("anthropic", dream.providerId)
        assertEquals("claude-sonnet-4-5", dream.modelId)
    }

    @Test
    fun fixedModeHonorsExplicitModelAndPausedReturnsNull() = runTest {
        val routerFixed = createRouter(
            defaultConfig = ModelConfig("anthropic", "claude-sonnet-4-5"),
            configured = setOf("anthropic", "deepseek"),
            memorySettings = MemorySettings(
                mode = MemoryModelMode.FIXED,
                fixedProviderId = "deepseek",
                fixedModelId = "deepseek-chat",
            ),
        )
        assertEquals("deepseek/deepseek-chat", routerFixed.resolve(MemoryModelTier.DREAM)!!.ref)

        val routerPaused = createRouter(
            defaultConfig = ModelConfig("anthropic", "claude-sonnet-4-5"),
            configured = setOf("anthropic"),
            memorySettings = MemorySettings(MemoryModelMode.PAUSED),
        )
        assertNull(routerPaused.resolve(MemoryModelTier.FAST))
        assertNull(routerPaused.resolve(MemoryModelTier.DREAM))
    }

    private fun createRouter(
        defaultConfig: ModelConfig,
        configured: Set<String>,
        memorySettings: MemorySettings,
    ): MemoryModelRouter {
        val modelsMap = mapOf(
            "anthropic" to listOf(
                ModelOption("anthropic", "claude-opus-4-5", "Claude Opus", emptyList()),
                ModelOption("anthropic", "claude-sonnet-4-5", "Claude Sonnet", emptyList()),
                ModelOption("anthropic", "claude-haiku-4-5", "Claude Haiku", emptyList()),
            ),
            "deepseek" to listOf(
                ModelOption("deepseek", "deepseek-reasoner", "DeepSeek Reasoner", emptyList()),
                ModelOption("deepseek", "deepseek-chat", "DeepSeek Chat", emptyList()),
            ),
        )
        val directory = object : ProviderDirectory {
            override val summaries = modelsMap.keys.map { id ->
                ProviderSummary(id, id, modelsMap[id]?.size ?: 0, "", false, true)
            }

            override fun summary(providerId: String) = summaries.firstOrNull { it.id == providerId }

            override fun models(providerId: String) = modelsMap[providerId].orEmpty()

            override fun resolveConfig(config: ModelConfig) = config

            override fun sdkModel(providerId: String, modelId: String): Model? = null

            override fun modelReference(providerId: String, modelId: String) = "$providerId/$modelId"
        }
        val modelSettings = object : ModelSettings {
            private val flow = MutableStateFlow(defaultConfig)
            override val config: Flow<ModelConfig> = flow
            override suspend fun read() = flow.value
            override suspend fun save(config: ModelConfig) {
                flow.value = config
            }
        }
        val settingsSource = object : MemorySettingsSource {
            private val flow = MutableStateFlow(memorySettings)
            override val settings: Flow<MemorySettings> = flow
            override suspend fun read() = flow.value
            override suspend fun save(value: MemorySettings) {
                flow.value = value
            }
        }
        return MemoryModelRouter(directory, modelSettings, settingsSource) { configured }
    }
}

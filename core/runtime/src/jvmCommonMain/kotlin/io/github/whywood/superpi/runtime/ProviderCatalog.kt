package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.AssistantMessage as SdkAssistantMessage
import io.github.whywood.superpi.sdk.ai.Context
import io.github.whywood.superpi.sdk.ai.Model
import io.github.whywood.superpi.sdk.ai.ModelThinkingLevel
import io.github.whywood.superpi.sdk.ai.Models
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.Provider
import io.github.whywood.superpi.sdk.ai.StreamOptions
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.ai.auth.CredentialStore
import io.github.whywood.superpi.sdk.ai.auth.oauth.bundledOAuthFlows
import io.github.whywood.superpi.sdk.ai.getSupportedThinkingLevels
import io.github.whywood.superpi.sdk.ai.http.HttpTransport
import io.github.whywood.superpi.sdk.ai.providers.builtinProviders
import io.github.whywood.superpi.sdk.coding.models.CustomProviderConfig
import io.github.whywood.superpi.sdk.coding.models.ProviderComposer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProviderCatalog(
    private val transport: HttpTransport,
    private val credentials: CredentialStore,
    customProviders: List<CustomProviderConfig> = emptyList(),
) : ProviderDirectory {
    private data class CatalogSnapshot(
        val customProviderMap: Map<String, Provider>,
        val providerMap: Map<String, Provider>,
        val registry: Models,
        val summaries: List<ProviderSummary>,
    )

    private val updateMutex = Mutex()
    @Volatile
    private var snapshot: CatalogSnapshot = buildSnapshotFromComposed(composeCustomProvidersSync(customProviders))
    private val _revision = MutableStateFlow(0L)

    override val revision: StateFlow<Long> = _revision.asStateFlow()

    override val summaries: List<ProviderSummary>
        get() = snapshot.summaries

    fun customSdkProviders(): List<Provider> = snapshot.customProviderMap.values.toList()

    fun setCustomProviders(customProviders: List<CustomProviderConfig>) {
        val next = buildSnapshotFromComposed(composeCustomProvidersSync(customProviders))
        snapshot = next
        _revision.value += 1L
    }

    suspend fun updateCustomProviders(customProviders: List<CustomProviderConfig>) {
        updateMutex.withLock {
            val composed = withContext(Dispatchers.IO) {
                composeCustomProvidersSuspend(customProviders)
            }
            snapshot = buildSnapshotFromComposed(composed)
            _revision.value += 1L
        }
    }

    private fun buildSnapshotFromComposed(composed: List<Provider>): CatalogSnapshot {
        val providers = builtinProviders(transport, credentials).associateBy { it.id }.toMutableMap()
        val customMap = composed.associateBy { it.id }
        composed.forEach { provider -> providers[provider.id] = provider }
        val immutableProviders = providers.toMap()
        return CatalogSnapshot(
            customProviderMap = customMap,
            providerMap = immutableProviders,
            registry = Models(immutableProviders.toMutableMap()),
            summaries = buildSummaries(immutableProviders),
        )
    }

    private fun composeCustomProvidersSync(customProviders: List<CustomProviderConfig>): List<Provider> {
        if (customProviders.isEmpty()) return emptyList()
        return runBlocking { composeCustomProvidersSuspend(customProviders) }
    }

    private suspend fun composeCustomProvidersSuspend(customProviders: List<CustomProviderConfig>): List<Provider> {
        if (customProviders.isEmpty()) return emptyList()
        val ids = customProviders.map { it.id }.toSet()
        val composed = runCatching {
            ProviderComposer(transport, credentials, customProviders).compose()
        }.getOrNull()
        if (composed != null) {
            return composed.getProviders().filter { provider -> provider.id in ids }
        }
        return customProviders.mapNotNull { config ->
            runCatching {
                ProviderComposer(transport, credentials, listOf(config)).compose()
                    .getProviders()
                    .firstOrNull { provider -> provider.id == config.id }
            }.getOrNull()
        }
    }

    private fun buildSummaries(providerMap: Map<String, Provider>): List<ProviderSummary> = providerMap.values.map { provider ->
        ProviderSummary(
            id = provider.id,
            name = provider.name,
            modelCount = provider.models.size,
            baseUrl = provider.baseUrl,
            hasOAuth = provider.id in OAUTH_PROVIDERS,
            supportsApiKey = provider.id !in KEYLESS_PROVIDERS,
        )
    }

    override fun summary(providerId: String): ProviderSummary? = snapshot.summaries.firstOrNull { it.id == providerId }

    override fun models(providerId: String): List<ModelOption> =
        snapshot.providerMap[providerId]?.models.orEmpty().map { model -> model.toOption(providerId) }

    fun defaultModel(providerId: String): ModelOption? = models(providerId).firstOrNull()

    fun model(providerId: String, modelId: String): ModelOption? =
        models(providerId).firstOrNull { it.id == modelId }

    override fun sdkModel(providerId: String, modelId: String): Model? =
        snapshot.providerMap[providerId]?.models?.firstOrNull { it.id == modelId }

    override fun resolveConfig(config: ModelConfig): ModelConfig {
        val snap = snapshot
        val provider = if (config.providerId in snap.providerMap) config.providerId else DEFAULT_PROVIDER_ID
        val models = snap.providerMap[provider]?.models.orEmpty().map { it.toOption(provider) }
        val model = models.firstOrNull { it.id == config.modelId }
            ?: models.firstOrNull()
            ?: ModelOption(provider, config.modelId, config.modelId, listOf(ThinkingLevel.OFF))
        val thinking = model.thinkingLevels.resolveThinkingLevel(config.thinkingLevel)
        return ModelConfig(provider, model.id, thinking)
    }

    override fun modelReference(providerId: String, modelId: String): String = "$providerId/$modelId"

    suspend fun complete(
        providerId: String,
        modelId: String,
        prompt: String,
        temperature: Double = 0.3,
        timeoutMs: Long = 20_000L,
        systemPrompt: String? = null,
    ): String? {
        val snap = snapshot
        val provider = snap.providerMap[providerId] ?: run {
            logWarn("SessionTitle", "provider not found: $providerId")
            return null
        }
        val model = provider.models.firstOrNull { it.id == modelId } ?: run {
            logWarn("SessionTitle", "model not found: $providerId/$modelId")
            return null
        }
        val message = snap.registry.complete(
            model = model,
            context = Context(
                systemPrompt = systemPrompt?.takeIf { it.isNotBlank() },
                messages = listOf(UserMessage(PlainTextContent(prompt), System.currentTimeMillis())),
            ),
            options = StreamOptions(
                maxTokens = 4096,
                temperature = temperature,
                timeoutMs = timeoutMs,
                sessionId = UUID.randomUUID().toString(),
            ),
        )
        val text = message.content
            .mapNotNull { (it as? TextContent)?.text }
            .joinToString("")
            .trim()
        if (text.isBlank()) {
            logWarn("SessionTitle", "empty completion $providerId/$modelId stop=${message.stopReason} error=${message.errorMessage} parts=${message.content.map { it::class.simpleName }}")
            return null
        }
        return text
    }

    suspend fun completeWithTools(
        providerId: String,
        modelId: String,
        context: Context,
        temperature: Double = 0.1,
        timeoutMs: Long = 45_000L,
        maxTokens: Int = 4096,
        sessionId: String? = null,
    ): SdkAssistantMessage? {
        val snap = snapshot
        val provider = snap.providerMap[providerId] ?: run {
            logWarn("MemoryAgent", "provider not found: $providerId")
            return null
        }
        val model = provider.models.firstOrNull { it.id == modelId } ?: run {
            logWarn("MemoryAgent", "model not found: $providerId/$modelId")
            return null
        }
        return snap.registry.complete(
            model = model,
            context = context,
            options = StreamOptions(
                maxTokens = maxTokens.toLong(),
                temperature = if (model.reasoning) null else temperature,
                timeoutMs = timeoutMs,
                sessionId = sessionId ?: UUID.randomUUID().toString(),
            ),
        )
    }

    fun modelsForConfiguredProviders(): List<ModelOption> =
        snapshot.summaries.flatMap { models(it.id) }

    private fun Model.toOption(providerId: String): ModelOption = ModelOption(
        providerId = providerId,
        id = id,
        displayName = name,
        thinkingLevels = thinkingLevels(this),
    )

    private fun thinkingLevels(model: Model): List<ThinkingLevel> =
        getSupportedThinkingLevels(model).mapNotNull { level ->
            when (level) {
                ModelThinkingLevel.Off -> ThinkingLevel.OFF
                ModelThinkingLevel.Minimal -> null
                ModelThinkingLevel.Low -> ThinkingLevel.LOW
                ModelThinkingLevel.Medium -> ThinkingLevel.MEDIUM
                ModelThinkingLevel.High -> ThinkingLevel.HIGH
                ModelThinkingLevel.Xhigh -> ThinkingLevel.XHIGH
                ModelThinkingLevel.Max -> ThinkingLevel.MAX
            }
        }.distinct()

    companion object {
        const val DEFAULT_PROVIDER_ID = ProviderDefaults.DEFAULT_PROVIDER_ID
        const val DEFAULT_MODEL_ID = ProviderDefaults.DEFAULT_MODEL_ID

        private val OAUTH_PROVIDERS = bundledOAuthFlows().keys
        private val KEYLESS_PROVIDERS = setOf(
            "amazon-bedrock",
            "github-copilot",
            "openai-codex",
            "kimi-coding",
            "meta",
            "radius",
        )
    }
}

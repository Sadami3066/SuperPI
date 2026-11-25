package io.github.whywood.superpi.ui.navigation.routes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.ui.navigation.AppNavigator
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.data.ProviderModelDiscovery
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.ui.settings.CustomProviderEditorScreen
import io.github.whywood.superpi.ui.settings.ProviderDetailScreen
import io.github.whywood.superpi.ui.settings.ProviderListScreen
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProvidersRoute(
    dependencies: AppDependencies,
    navigator: AppNavigator,
    configuredProviderIds: Set<String>,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val catalogRevision by dependencies.providerCatalog.revision.collectAsStateWithLifecycle()
    val defaultModel by dependencies.modelConfigStore.config.collectAsStateWithLifecycle(initialValue = ModelConfig())
    val customProviders by dependencies.customProviderStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val customProviderIds = remember(customProviders) { customProviders.map { it.id }.toSet() }
    val providers = remember(catalogRevision) { dependencies.providerCatalog.summaries }
    val modelsByProvider = remember(configuredProviderIds, catalogRevision) {
        configuredProviderIds.associateWith { id -> dependencies.providerCatalog.models(id) }
    }
    ProviderListScreen(
        providers = providers,
        customProviderIds = customProviderIds,
        configuredProviderIds = configuredProviderIds,
        onBack = onBack,
        onAddCustomProvider = { navigator.push(AppDestination.CustomProviderEditor()) },
        onProviderClick = { id ->
            if (id in customProviderIds) {
                navigator.push(AppDestination.CustomProviderEditor(id))
            } else {
                navigator.push(AppDestination.ProviderDetail(id))
            }
        },
        defaultModel = defaultModel,
        modelsByProvider = modelsByProvider,
        onSelectDefaultModel = { providerId, modelId ->
            scope.launch {
                val current = dependencies.modelConfigStore.read()
                val resolved = dependencies.providerCatalog.resolveConfig(
                    ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = current.thinkingLevel),
                )
                dependencies.modelConfigStore.save(resolved)
            }
        },
    )
}

@Composable
internal fun CustomProviderEditorRoute(
    dependencies: AppDependencies,
    navigator: AppNavigator,
    providerId: String?,
    onBack: () -> Unit,
) {
    val entries by dependencies.customProviderStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val entry = remember(entries, providerId) { entries.firstOrNull { it.id == providerId } }
    var closing by remember(providerId) { mutableStateOf(false) }
    LaunchedEffect(closing, providerId, entries, entry) {
        if (!closing && providerId != null && entry == null && entries.isNotEmpty()) navigator.pop()
    }
    val scope = rememberCoroutineScope()
    CustomProviderEditorScreen(
        entry = entry,
        onBack = onBack,
        onDiscoverModels = { baseUrl, apiKey, protocol ->
            ProviderModelDiscovery.fetch(baseUrl, apiKey, protocol)
        },
        onSave = { updated ->
            if (closing) return@CustomProviderEditorScreen
            closing = true
            scope.launch {
                try {
                    withContext(NonCancellable) {
                        dependencies.customProviderStore.saveEntry(updated)
                        dependencies.reloadCustomProviders()
                    }
                    navigator.pop()
                } catch (error: Throwable) {
                    closing = false
                    throw error
                }
            }
        },
        onDelete = entry?.let { existing ->
            {
                if (!closing) {
                    closing = true
                    scope.launch {
                        try {
                            withContext(NonCancellable) {
                                dependencies.customProviderStore.delete(existing.id)
                                dependencies.reloadCustomProviders()
                            }
                            navigator.pop()
                        } catch (error: Throwable) {
                            closing = false
                            throw error
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun ProviderDetailRoute(
    dependencies: AppDependencies,
    navigator: AppNavigator,
    providerId: String,
    configuredProviderIds: Set<String>,
    onBack: () -> Unit,
) {
    val catalogRevision by dependencies.providerCatalog.revision.collectAsStateWithLifecycle()
    val provider = remember(providerId, catalogRevision) { dependencies.providerCatalog.summary(providerId) }
    if (provider == null) {
        LaunchedEffect(providerId) { navigator.pop() }
        return
    }
    val scope = rememberCoroutineScope()
    val defaultModel by dependencies.modelConfigStore.config.collectAsStateWithLifecycle(initialValue = ModelConfig())
    val models = remember(providerId, catalogRevision) {
        dependencies.providerCatalog.models(providerId)
    }
    val authKinds by dependencies.credentials.authKinds.collectAsStateWithLifecycle(initialValue = emptyMap())
    val credentialRevision by dependencies.credentials.revision.collectAsStateWithLifecycle(initialValue = 0L)
    val savedApiKey = remember(providerId, credentialRevision) {
        dependencies.credentials.cachedApiKey(providerId)
    }
    ProviderDetailScreen(
        provider = provider,
        configured = providerId in configuredProviderIds,
        savedApiKey = savedApiKey,
        authKind = authKinds[providerId],
        oauthService = dependencies.providerOAuthService,
        onAuthChanged = {},
        onBack = onBack,
        onSaveApiKey = { key ->
            runCatching {
                dependencies.credentials.writeApiKey(providerId, key, null)
                dependencies.credentials.awaitCommit(providerId)
            }
        },
        onClearApiKey = {
            runCatching {
                dependencies.credentials.delete(providerId)
                dependencies.credentials.awaitCommit(providerId)
            }
        },
        models = models,
        defaultModel = defaultModel,
        onSelectDefaultModel = { modelId ->
            scope.launch {
                val current = dependencies.modelConfigStore.read()
                val resolved = dependencies.providerCatalog.resolveConfig(
                    ModelConfig(providerId = providerId, modelId = modelId, thinkingLevel = current.thinkingLevel),
                )
                dependencies.modelConfigStore.save(resolved)
            }
        },
    )
}

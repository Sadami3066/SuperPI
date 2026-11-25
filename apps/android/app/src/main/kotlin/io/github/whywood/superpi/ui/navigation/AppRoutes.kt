package io.github.whywood.superpi.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.ui.mcp.McpScreen
import io.github.whywood.superpi.ui.navigation.routes.AndroidSettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.ArchivedRoute
import io.github.whywood.superpi.ui.navigation.routes.AgentSettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.MemorySettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.SuperSettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.WebSearchRoute
import io.github.whywood.superpi.ui.navigation.routes.ChatSettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.FilesRoute
import io.github.whywood.superpi.ui.navigation.routes.FontsRoute
import io.github.whywood.superpi.ui.navigation.routes.ThemeRoute
import io.github.whywood.superpi.ui.navigation.routes.PersonalizationRoute
import io.github.whywood.superpi.ui.navigation.routes.PluginPageRoute
import io.github.whywood.superpi.ui.navigation.routes.PluginsRoute
import io.github.whywood.superpi.ui.navigation.routes.CustomProviderEditorRoute
import io.github.whywood.superpi.ui.navigation.routes.ProviderDetailRoute
import io.github.whywood.superpi.ui.navigation.routes.ProvidersRoute
import io.github.whywood.superpi.ui.navigation.routes.RelayRoute
import io.github.whywood.superpi.ui.navigation.routes.RemoteFilesRoute
import io.github.whywood.superpi.ui.navigation.routes.ScanSettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.SearchRoute
import io.github.whywood.superpi.ui.navigation.routes.SettingsRoute
import io.github.whywood.superpi.ui.navigation.routes.SoftwareUpdateRoute
import io.github.whywood.superpi.ui.navigation.routes.SystemAccessRoute
import io.github.whywood.superpi.ui.navigation.routes.TimersRoute
import io.github.whywood.superpi.ui.navigation.routes.MiniappDetailRoute
import io.github.whywood.superpi.ui.navigation.routes.MemoryRoute
import io.github.whywood.superpi.ui.navigation.routes.MiniappRoute
import io.github.whywood.superpi.ui.navigation.routes.WorkspaceSettingsRoute
import io.github.whywood.superpi.ui.settings.AgreementScreen
import io.github.whywood.superpi.ui.skills.SkillsScreen
import io.github.whywood.superpi.ui.superagent.SuperTeamScreen
import io.github.whywood.superpi.ui.superagent.WorkerChatViewModel
import io.github.whywood.superpi.ui.superagent.WorkerMonitorScreen
import java.io.File

data class RouteSpec(
    val routeKey: String,
    val depth: Int,
    val parentRouteKey: String?,
)

private const val PLUGIN_DETAIL_PREFIX = "plugins/"
private const val PLUGIN_PAGE_MARKER = "/page/"
private const val MINIAPP_DETAIL_PREFIX = "miniapp/"

fun parentRouteKey(routeKey: String): String? = when {
    routeKey.startsWith(PLUGIN_DETAIL_PREFIX) -> "plugins"
    routeKey.startsWith(MINIAPP_DETAIL_PREFIX) -> "miniapp"
    else -> null
}

fun routeSpec(destination: AppDestination): RouteSpec =
    RouteSpec(destination.routeKey, destination.depth, parentRouteKey(destination.routeKey))

fun routeSpec(routeKey: String): RouteSpec? = AppDestination.fromRouteKey(routeKey)?.let(::routeSpec)

fun deepLinkStack(destination: AppDestination): List<AppDestination> {
    val parent = parentRouteKey(destination.routeKey)?.let(AppDestination::fromRouteKey)
    return if (parent == null) listOf(destination) else listOf(parent, destination)
}

fun deepLinkStack(routeKey: String): List<AppDestination> =
    AppDestination.fromRouteKey(routeKey)?.let(::deepLinkStack).orEmpty()

@Composable
internal fun AppRouteContent(destination: AppDestination, host: AppRouteHost) {
    val dependencies = host.dependencies
    val onBack = host::back
    when (destination) {
        AppDestination.Chat -> Box(Modifier.fillMaxSize())

        is AppDestination.Files -> FilesRoute(
            rootPath = destination.rootPath,
            onOpenFile = host.onOpenFile,
            onBack = onBack,
        )

        is AppDestination.RemoteFiles -> RemoteFilesRoute(
            destination = destination,
            dependencies = dependencies,
            onBack = onBack,
        )

        AppDestination.Settings -> SettingsRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            scope = host.scope,
            ui = host.ui,
            archivedCount = host.chat.archivedCount,
            onBack = onBack,
        )

        AppDestination.Providers -> ProvidersRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            configuredProviderIds = host.ui.configuredProviderIds,
            onBack = onBack,
        )

        is AppDestination.CustomProviderEditor -> CustomProviderEditorRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            providerId = destination.id,
            onBack = onBack,
        )

        AppDestination.SystemAccess -> SystemAccessRoute(dependencies, host.scope, onBack)

        AppDestination.AndroidSettings -> AndroidSettingsRoute(dependencies, host.scope, onBack)

        AppDestination.AgentSettings -> AgentSettingsRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            configuredProviderIds = host.ui.configuredProviderIds,
            onBack = onBack,
        )

        AppDestination.SuperSettings -> SuperSettingsRoute(
            dependencies = dependencies,
            scope = host.scope,
            onBack = onBack,
        )

        AppDestination.MemorySettings -> MemorySettingsRoute(
            dependencies = dependencies,
            scope = host.scope,
            configuredProviderIds = host.ui.configuredProviderIds,
            onBack = onBack,
        )


        AppDestination.WebSearch -> WebSearchRoute(dependencies, onBack)

        AppDestination.Update -> SoftwareUpdateRoute(dependencies, onBack)

        AppDestination.Personalization -> PersonalizationRoute(dependencies, host.scope, onBack)

        AppDestination.Privacy -> AgreementScreen(onBack = onBack)

        AppDestination.Theme -> ThemeRoute(dependencies, onBack)

        AppDestination.Fonts -> FontsRoute(host.ui, onBack)

        AppDestination.ChatSettings -> ChatSettingsRoute(dependencies, host.scope, onBack)

        AppDestination.Scan -> ScanSettingsRoute(onBack = onBack)

        is AppDestination.ProviderDetail -> ProviderDetailRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            providerId = destination.id,
            configuredProviderIds = host.ui.configuredProviderIds,
            onBack = onBack,
        )

        AppDestination.Mcp -> McpScreen(
            store = dependencies.mcpConfigStore,
            probe = dependencies.mcpServerProbe,
            oauth = dependencies.mcpOAuthService,
            marketRepository = dependencies.marketRepository,
            onBack = onBack,
            onReloadSessions = { dependencies.host.reloadMcp() },
            onReconnect = { server -> dependencies.host.reconnectMcp(server) },
        )

        AppDestination.Plugins -> PluginsRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            onBack = onBack,
            pendingImportUri = host.pluginImportUri,
            onPendingImportHandled = host.onPluginImportHandled,
        )

        is AppDestination.PluginPage -> PluginPageRoute(
            destination = destination,
            manager = dependencies.pluginManager,
            onBack = onBack,
        )

        AppDestination.Archived -> ArchivedRoute(
            chatViewModel = host.chatViewModel,
            archived = host.chat.archived,
            onOpenChat = host.onOpenChat,
            onBack = onBack,
        )

        AppDestination.Search -> SearchRoute(host.chatViewModel, host.navigator, onBack)

        AppDestination.Skills -> SkillsScreen(
            repository = dependencies.skillRepository,
            marketRepository = dependencies.marketRepository,
            onBack = onBack,
        )

        AppDestination.Timers -> TimersRoute(
            dependencies = dependencies,
            chatViewModel = host.chatViewModel,
            conversationId = if (host.ui.appMode == AppMode.SUPER) {
                SuperRuntime.SUPER_THREAD_SESSION_ID
            } else {
                host.chat.conversationId
            },
            appMode = host.ui.appMode,
            navigator = host.navigator,
            onBack = onBack,
        )

        AppDestination.Memory -> MemoryRoute(
            dependencies = dependencies,
            chatViewModel = host.chatViewModel,
            navigator = host.navigator,
            onBack = onBack,
        )

        AppDestination.Relay -> RelayRoute(
            dependencies = dependencies,
            onBack = onBack,
        )

        is AppDestination.WorkspaceSettings -> WorkspaceSettingsRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            workspaces = host.chat.workspaces,
            workspaceId = destination.workspaceId,
            configuredProviderIds = host.ui.configuredProviderIds,
            globalModelConfig = host.ui.modelConfig,
            onUpdateProfile = host.chatViewModel::updateWorkspaceProfile,
            onUpdateDefaultModel = host.chatViewModel::updateWorkspaceDefaultModel,
            onRenameWorkspace = host.chatViewModel::renameWorkspace,
            onBack = onBack,
        )

        AppDestination.Miniapp -> MiniappRoute(
            dependencies = dependencies,
            navigator = host.navigator,
            onOpenMiniappCreate = host.onOpenMiniappCreate,
            onBack = onBack,
        )

        is AppDestination.MiniappDetail -> MiniappDetailRoute(
            dependencies = dependencies,
            creationId = destination.id,
            onBack = onBack,
        )

        AppDestination.SuperTeam -> SuperTeamScreen(
            superRuntime = dependencies.superRuntime,
            onBack = onBack,
        )

        is AppDestination.WorkerMonitor -> {
            val superDir = File(dependencies.agentDir, "super")
            val routerWorkspaceDir = File(superDir, "router/workspace")
            val workerScope = rememberCoroutineScope()
            val configuredProviderList = remember(
                dependencies.providerCatalog.summaries,
                host.ui.configuredProviderIds,
            ) {
                dependencies.providerCatalog.summaries.filter { it.id in host.ui.configuredProviderIds }
            }
            val viewModel = remember(destination.workerId, dependencies, workerScope) {
                WorkerChatViewModel(
                    workerId = destination.workerId,
                    superRuntime = dependencies.superRuntime,
                    providers = dependencies.providerCatalog,
                    configuredProviderIds = dependencies::configuredProviderIds,
                    attachmentStore = dependencies.attachmentStore,
                    superDir = superDir,
                    routerWorkspaceDir = routerWorkspaceDir,
                    scope = workerScope,
                    strings = host.ui.conversationStrings,
                )
            }
            LaunchedEffect(viewModel, host.ui.conversationStrings) {
                viewModel.updateStrings(host.ui.conversationStrings)
            }
            val workerUi by viewModel.state.collectAsStateWithLifecycle()
            val relayState by dependencies.relayLive.state.collectAsStateWithLifecycle()
            val worker = workerUi.worker
            val remotePresentContext = rememberRemotePresentContext(
                deviceId = worker?.deviceId?.takeIf { worker.isRemote },
                deviceName = worker?.deviceName.orEmpty(),
                botId = worker?.botId,
                attachmentRoot = workerUi.attachmentRoot,
                isRemoteOnline = workerUi.isRemoteOnline,
                relayState = relayState,
                remoteTransport = dependencies.remoteTransport,
                remoteArtifactClient = dependencies.remoteArtifactClient,
            )
            WorkerMonitorScreen(
                workerId = destination.workerId,
                viewModel = viewModel,
                attachmentStore = dependencies.attachmentStore,
                superDir = superDir,
                configuredProviders = configuredProviderList,
                processDetail = host.ui.processDetail,
                chatFontScale = host.ui.chatFontScale,
                voiceLanguage = host.ui.voiceLanguage,
                onBack = onBack,
                onManageProviders = { host.navigator.push(AppDestination.Providers) },
                onOpenMiniapp = { id -> host.navigator.push(AppDestination.MiniappDetail(id)) },
                onOpenPluginPage = { pluginId, pageId ->
                    host.navigator.push(AppDestination.PluginPage(pluginId, pageId))
                },
                remotePresentContext = remotePresentContext,
                compactionTriggerPercent = host.ui.workerCompactionTriggerPercent,
            )
        }
    }
}

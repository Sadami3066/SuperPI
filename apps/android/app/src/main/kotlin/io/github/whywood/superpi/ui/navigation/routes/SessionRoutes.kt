package io.github.whywood.superpi.ui.navigation.routes

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.whywood.superpi.data.BotAvatarStore
import io.github.whywood.superpi.runtime.host.HostAccess
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.ui.chat.ChatViewModel
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.navigation.AppNavigator
import io.github.whywood.superpi.ui.sessions.ArchivedSessionsScreen
import io.github.whywood.superpi.ui.sessions.SessionSearchScreen
import io.github.whywood.superpi.ui.timers.TimerScreen
import io.github.whywood.superpi.ui.workspaces.WorkspaceSettingsScreen

@Composable
internal fun ArchivedRoute(
    chatViewModel: ChatViewModel,
    archived: List<ConversationSummary>,
    onOpenChat: () -> Unit,
    onBack: () -> Unit,
) {
    ArchivedSessionsScreen(
        archivedSessions = archived,
        onBack = onBack,
        onOpen = { id ->
            chatViewModel.openConversation(id)
            onOpenChat()
        },
        onRename = chatViewModel::renameSession,
        onUnarchive = { id -> chatViewModel.setSessionArchived(id, false) },
        onDelete = chatViewModel::deleteSession,
    )
}

@Composable
internal fun SearchRoute(
    chatViewModel: ChatViewModel,
    navigator: AppNavigator,
    onBack: () -> Unit,
) {
    SessionSearchScreen(
        onBack = onBack,
        loadSessions = chatViewModel::loadSearchSessions,
        onOpen = { id ->
            chatViewModel.openConversation(id)
            navigator.reset(AppDestination.Chat)
        },
    )
}

@Composable
internal fun TimersRoute(
    dependencies: AppDependencies,
    chatViewModel: ChatViewModel,
    conversationId: String?,
    appMode: AppMode,
    navigator: AppNavigator,
    onBack: () -> Unit,
) {
    val superMode = appMode == AppMode.SUPER
    TimerScreen(
        service = dependencies.timerService,
        runtime = dependencies.host,
        initialConversationId = conversationId,
        onBack = onBack,
        onOpenConversation = { sessionId ->
            when {
                sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID -> navigator.reset(AppDestination.Chat)
                SuperRuntime.parseWorkerSessionId(sessionId) != null -> {
                    SuperRuntime.parseWorkerSessionId(sessionId)?.let { workerId ->
                        navigator.reset(AppDestination.Chat)
                        navigator.push(AppDestination.WorkerMonitor(workerId))
                    }
                }
                else -> {
                    chatViewModel.openConversationById(sessionId)
                    navigator.reset(AppDestination.Chat)
                }
            }
        },
        superRuntime = dependencies.superRuntime,
        superMode = superMode,
    )
}

@Composable
internal fun WorkspaceSettingsRoute(
    dependencies: AppDependencies,
    navigator: AppNavigator,
    workspaces: List<RuntimeWorkspace>,
    workspaceId: String,
    configuredProviderIds: Set<String> = emptySet(),
    globalModelConfig: io.github.whywood.superpi.runtime.ModelConfig = io.github.whywood.superpi.runtime.ModelConfig(),
    onUpdateProfile: (String, String?, String?) -> Unit,
    onUpdateDefaultModel: (String, io.github.whywood.superpi.runtime.ModelConfig?) -> Unit = { _, _ -> },
    onRenameWorkspace: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val workspace = workspaces.firstOrNull { it.id == workspaceId }
    val context = LocalContext.current
    val botAvatars = remember { BotAvatarStore(context) }
    val scope = rememberCoroutineScope()
    val hostState by dependencies.hostAccess.state.collectAsStateWithLifecycle()
    val screenToolsReady = hostState.effective == HostAccess.ADB || hostState.effective == HostAccess.ROOT
    if (workspace == null || workspace.remote != null) {
        LaunchedEffect(workspaceId) { navigator.pop() }
        return
    }
    WorkspaceSettingsScreen(
        workspace = workspace,
        skillRepository = dependencies.skillRepository,
        agentDocStore = dependencies.agentDocStore,
        workspaceToolPolicies = dependencies.workspaceToolPolicies,
        pluginManager = dependencies.pluginManager,
        mcpConfigStore = dependencies.mcpConfigStore,
        providerCatalog = dependencies.providerCatalog,
        configuredProviderIds = configuredProviderIds,
        globalModelConfig = globalModelConfig,
        existingNames = workspaces.filterNot { it.id == workspaceId }.map { it.name },
        screenToolsReady = screenToolsReady,
        onRename = { name -> onRenameWorkspace(workspaceId, name) },
        onUpdateDefaultModel = { model -> onUpdateDefaultModel(workspaceId, model) },
        onAvatarPicked = { bitmap ->
            scope.launch {
                val previous = workspace.avatar
                val path = if (bitmap == null) null else botAvatars.save(bitmap)
                if (bitmap == null || path != null) {
                    onUpdateProfile(workspaceId, path, workspace.intro)
                    botAvatars.delete(previous)
                }
            }
        },
        onIntroChange = { intro ->
            onUpdateProfile(workspaceId, workspace.avatar, intro.trim().takeIf { it.isNotEmpty() })
        },
        onBack = onBack,
    )
}

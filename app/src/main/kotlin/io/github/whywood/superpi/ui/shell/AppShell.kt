package io.github.whywood.superpi.ui.shell

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.core.resolveAttachmentRoot
import io.github.whywood.superpi.data.AttachmentExport
import io.github.whywood.superpi.data.AttachmentKind
import io.github.whywood.superpi.data.LanguagePreferences
import io.github.whywood.superpi.data.PersonalizationUi
import io.github.whywood.superpi.data.ProfileAvatarStore
import io.github.whywood.superpi.data.SharePayload
import io.github.whywood.superpi.data.relay.RelayLiveState
import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.data.relay.RemoteHistoryOutcome
import io.github.whywood.superpi.data.relay.RemoteSessionsOutcome
import io.github.whywood.superpi.data.session.SessionExporter
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.data.exportShareIntent
import io.github.whywood.superpi.data.update.hasUpdate
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.design.DdFonts
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.host.services.PluginUiBridge
import io.github.whywood.superpi.remote.RemoteAdminResult
import io.github.whywood.superpi.remote.RemoteArtifactClient
import io.github.whywood.superpi.remote.RemoteArtifactResult
import io.github.whywood.superpi.remote.RemoteBotAdminClient
import io.github.whywood.superpi.remote.RemoteBotControlClient
import io.github.whywood.superpi.remote.RemoteControlResult
import io.github.whywood.superpi.remote.RemoteFileClient
import io.github.whywood.superpi.remote.RemoteModelRef
import io.github.whywood.superpi.remote.RemoteModelsSnapshot
import io.github.whywood.superpi.remote.RemoteSessionBrowser
import io.github.whywood.superpi.remote.RemoteSessionRegistry
import io.github.whywood.superpi.remote.RemoteSlashCommand
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.runtime.resolveThinkingLevel
import io.github.whywood.superpi.ui.RemoteDrawerState
import io.github.whywood.superpi.ui.RemoteDrawerStatus
import io.github.whywood.superpi.ui.workspaces.RemoteHostTarget
import io.github.whywood.superpi.ui.chat.ChatScreen
import io.github.whywood.superpi.ui.chat.ChatScreenActions
import io.github.whywood.superpi.ui.chat.ChatScreenOverlay
import io.github.whywood.superpi.ui.chat.ChatViewModel
import io.github.whywood.superpi.ui.chat.CommandItem
import io.github.whywood.superpi.ui.chat.RemoteSessionOpenResult
import io.github.whywood.superpi.ui.common.LocalHapticsEnabled
import io.github.whywood.superpi.ui.common.ProfileAvatarCache
import io.github.whywood.superpi.ui.common.chat.ChatUiState
import io.github.whywood.superpi.ui.common.chat.RemotePresentContext
import io.github.whywood.superpi.ui.common.rememberAvatarBitmap
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.sessionIdsWithEnabledTimers
import io.github.whywood.superpi.ui.sessionIdsWithRunningJobs
import io.github.whywood.superpi.ui.navigation.AnimatedScreenHost
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.navigation.AppRouteContent
import io.github.whywood.superpi.ui.navigation.AppRouteHost
import io.github.whywood.superpi.ui.navigation.ChatRouteData
import io.github.whywood.superpi.ui.navigation.ShellRouteUi
import io.github.whywood.superpi.ui.navigation.deepLinkStack
import java.io.File
import io.github.whywood.superpi.ui.navigation.rememberAppNavigator
import io.github.whywood.superpi.ui.shell.ShellCommandAction
import io.github.whywood.superpi.ui.shell.ShellCommandExecutor
import io.github.whywood.superpi.ui.shell.ShellCommandPalette
import io.github.whywood.superpi.ui.shell.ShellDrawerActions
import io.github.whywood.superpi.ui.shell.ShellDrawerState
import io.github.whywood.superpi.ui.shell.ShellIntakeActions
import io.github.whywood.superpi.ui.shell.ShellIntakeChatState
import io.github.whywood.superpi.ui.shell.ShellIntakeHost
import io.github.whywood.superpi.ui.shell.ShellNavigationBackHandler
import io.github.whywood.superpi.ui.shell.ShellNavigationChrome
import io.github.whywood.superpi.ui.shell.ShellSubmitPlan
import io.github.whywood.superpi.ui.shell.buildCommandItems
import io.github.whywood.superpi.ui.shell.commandInsertion
import io.github.whywood.superpi.ui.shell.planSubmission
import io.github.whywood.superpi.ui.shell.rememberFrozenChatState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



@Composable
internal fun AppShell(
    graph: AppDependencies,
    chatViewModel: ChatViewModel,
    state: ChatUiState,
    openSessionId: String? = null,
    onOpenSessionConsumed: () -> Unit = {},
    pendingDeepDestination: AppDestination? = null,
    onDeepDestinationConsumed: () -> Unit = {},
    pendingShare: SharePayload? = null,
    onShareConsumed: () -> Unit = {},
    pendingPluginImportUri: String? = null,
    onPluginImportConsumed: () -> Unit = {},
    onFontStyleChange: (DdFontStyle) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val themeState by graph.themeController.state.collectAsStateWithLifecycle()
    val overlays = rememberSaveable(saver = AppOverlaysState.Saver) { AppOverlaysState() }
    var pluginImportUri by remember { mutableStateOf<String?>(null) }
    val reloadDoneMessage = stringResource(R.string.chat_reload_done)
    val reloadFailedMessage = stringResource(R.string.chat_reload_failed)
    val reloadBusyMessage = stringResource(R.string.chat_reload_busy)
    val switchModelFailedMessage = stringResource(R.string.chat_error_switch_model)
    val switchThinkingFailedMessage = stringResource(R.string.chat_error_switch_thinking)
    val sessionMissingMessage = stringResource(R.string.chat_error_session_missing)
    val shareImportTimeoutMessage = stringResource(R.string.chat_share_import_timeout)
    val availableSkills by chatViewModel.availableSkills.collectAsStateWithLifecycle()
    val attachmentRoot = remember(state.conversationCwd) {
        resolveAttachmentRoot(state.conversationCwd, graph.homeDir)
    }
    val context = LocalContext.current
    val avatarStore = remember(context) { ProfileAvatarStore(context) }
    val shareChooserTitle = stringResource(R.string.assistant_attach_share)
    val authKinds by graph.credentials.authKinds.collectAsStateWithLifecycle(initialValue = emptyMap())
    val credentialRevision by graph.credentials.revision.collectAsStateWithLifecycle(initialValue = 0L)
    val avatarRevision by ProfileAvatarStore.revision.collectAsStateWithLifecycle(initialValue = 0L)
    val avatarKey = remember(avatarRevision) { "profile:${avatarStore.current()?.lastModified() ?: 0L}" }
    val avatarBitmap by rememberAvatarBitmap(avatarKey) { avatarStore.load() }
    val customProviders by graph.customProviderStore.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    val configuredProviders = remember(authKinds, customProviders) {
        graph.configuredProviderIds()
    }
    val configuredProviderList = remember(graph.providerCatalog.summaries, configuredProviders) {
        graph.providerCatalog.summaries.filter { it.id in configuredProviders }
    }
    LaunchedEffect(customProviders) {
        chatViewModel.refreshModelCatalog()
    }
    val storedModelConfig by graph.modelConfigStore.config.collectAsStateWithLifecycle(initialValue = null)
    val defaultModelConfig = storedModelConfig ?: ModelConfig()
    val defaultProvider = graph.providerCatalog.summary(defaultModelConfig.providerId)
    val defaultModelLabel = graph.providerCatalog.models(defaultModelConfig.providerId)
        .firstOrNull { it.id == defaultModelConfig.modelId }
        ?.displayName
        ?: defaultModelConfig.modelId
    LaunchedEffect(configuredProviders, storedModelConfig, state.providerId, state.conversationId, state.ready) {
        val config = storedModelConfig ?: return@LaunchedEffect
        val workspace = state.workspaces.firstOrNull { it.id == state.currentWorkspaceId }
        if (workspace?.remote != null) return@LaunchedEffect
        if (!state.ready || state.providerId.isBlank() || configuredProviders.isEmpty()) return@LaunchedEffect
        if (state.providerId in configuredProviders || config.providerId !in configuredProviders) return@LaunchedEffect
        chatViewModel.adoptConfiguredModel(config)
    }
    val timers by graph.timerService.timers.collectAsStateWithLifecycle()
    val remoteBots by graph.relayLive.remoteBots.collectAsStateWithLifecycle()
    val remoteDevices by graph.remoteTransport.devices.collectAsStateWithLifecycle()
    val relayState by graph.relayLive.state.collectAsStateWithLifecycle()
    val relayCapabilities by graph.relayLive.relayCapabilities.collectAsStateWithLifecycle()
    LaunchedEffect(remoteBots) { chatViewModel.refreshWorkspaces() }
    val linuxState by graph.linuxEnvironment.state.collectAsStateWithLifecycle()
    val linuxEnabled by graph.linuxSettings.enabled.collectAsStateWithLifecycle(initialValue = false)
    val sharedStorageEnabled by graph.linuxSettings.sharedStorageEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val devPackages by graph.devPackageManager.state.collectAsStateWithLifecycle()
    val voiceAutoSpeak by graph.voicePreferences.autoSpeak.collectAsStateWithLifecycle(initialValue = false)
    val updateUi by graph.updateManager.ui.collectAsStateWithLifecycle()
    val appLanguage = remember(context) { LanguagePreferences(context).read() }
    val colors = superPiColors()
    val attachmentMissingMessage = stringResource(R.string.assistant_attach_missing)
    val presentOpenFailed = stringResource(R.string.present_open_failed)
    val presentShareFailed = stringResource(R.string.present_share_failed)
    val filesExportDone = stringResource(R.string.files_export_done)
    val filesExportDonePathFormat = stringResource(R.string.files_export_done_path)
    val openWorkspaceFile: (String) -> Unit = { raw ->
        scope.launch {
            val resolved = graph.attachmentStore.resolve(raw, attachmentRoot)
            if (resolved == null) {
                Toast.makeText(context, attachmentMissingMessage, Toast.LENGTH_SHORT).show()
            } else {
                overlays.previewRoot = attachmentRoot
                overlays.previewAttachment = resolved
            }
        }
    }
    val navigator = rememberAppNavigator()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val pendingPluginPage by PluginUiBridge.pendingPage.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPluginPage) {
        val request = pendingPluginPage ?: return@LaunchedEffect
        drawerOpen = false
        navigator.push(
            AppDestination.PluginPage(
                pluginId = request.pluginId,
                pageId = request.pageId,
                arguments = request.arguments,
            ),
        )
        PluginUiBridge.consumePage(request)
    }
    val pendingPluginSheet by PluginUiBridge.pendingSheet.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPluginSheet) {
        val request = pendingPluginSheet ?: return@LaunchedEffect
        drawerOpen = false
        overlays.pluginPage = request
        PluginUiBridge.consumeSheet(request)
    }
    val pendingPluginPageClose by PluginUiBridge.pendingClosePage.collectAsStateWithLifecycle()
    LaunchedEffect(pendingPluginPageClose) {
        if (!pendingPluginPageClose) return@LaunchedEffect
        if (overlays.pluginPage != null) {
            overlays.pluginPage = null
        } else if (navigator.current is AppDestination.PluginPage) {
            navigator.pop()
        }
        PluginUiBridge.consumeClosePage()
    }
    val uiPreferences by graph.appPreferences.preferences.collectAsStateWithLifecycle(initialValue = UiPreferences())
    val pendingQuestions by graph.questionCenter.pending.collectAsStateWithLifecycle()
    val activeQuestions = pendingQuestions.filter { it.sessionId == state.conversationId }
    val foreignQuestion = pendingQuestions.firstOrNull { it.sessionId != state.conversationId }
    val goals by graph.goalService.goals.collectAsStateWithLifecycle()
    val activeGoal = state.conversationId?.let { goals[it] }?.takeIf { it.goal.phase != io.github.whywood.superpi.runtime.GoalPhase.COMPLETE }
    val allJobs by graph.jobRegistry.jobs.collectAsStateWithLifecycle()
    val activeJobs = remember(allJobs, state.conversationId) {
        allJobs.filter { it.sessionId == state.conversationId }
    }
    val timerSessionIds = remember(timers) { sessionIdsWithEnabledTimers(timers) }
    val jobSessionIds = remember(allJobs) { sessionIdsWithRunningJobs(allJobs) }
    var modelsOpen by remember { mutableStateOf(false) }
    var exportSummary by remember { mutableStateOf<ConversationSummary?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val summary = exportSummary
        exportSummary = null
        if (uri != null && summary != null) {
            scope.launch {
                val ok = runCatching {
                    SessionExporter.write(context.contentResolver, uri, summary.filePath)
                }.isSuccess
                Toast.makeText(
                    context,
                    if (ok) R.string.assistant_export_done else R.string.assistant_export_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val personalization by graph.personalizationStore.personalization.collectAsStateWithLifecycle(
        initialValue = PersonalizationUi(),
    )
    val nickname = personalization.nickname.trim().ifBlank { "PI" }
    val expandedWorkspacesSaver = remember {
        Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        )
    }
    var expandedWorkspaceIds by rememberSaveable(stateSaver = expandedWorkspacesSaver) {
        mutableStateOf(emptySet<String>())
    }
    var remoteDrawerStates by remember {
        mutableStateOf<Map<String, RemoteDrawerState>>(emptyMap())
    }
    var showArchivedRemoteWorkspaces by rememberSaveable(stateSaver = expandedWorkspacesSaver) {
        mutableStateOf(emptySet<String>())
    }
    var remoteRefreshEpoch by remember { mutableStateOf(0) }
    val remoteRelayUpgradeMessage = stringResource(R.string.drawer_remote_relay_upgrade)
    val remotePeerUpgradeMessage = stringResource(R.string.drawer_remote_peer_upgrade)
    val remoteOpenFailedMessage = stringResource(R.string.drawer_remote_open_failed)
    val remoteLoadFailedMessage = stringResource(R.string.drawer_remote_load_failed)
    val remoteActionFailedMessage = stringResource(R.string.drawer_remote_action_failed)
    val remoteOfflineMessage = stringResource(R.string.drawer_remote_device_offline)
    val remoteTimeoutMessage = stringResource(R.string.drawer_remote_timeout)
    fun localizeRemoteError(code: String, fallback: String): String = when (code) {
        RemoteCallCodes.NOT_CONNECTED,
        RemoteCallCodes.DISCONNECTED,
        "device_offline",
        -> remoteOfflineMessage

        "capability_unavailable" -> remotePeerUpgradeMessage
        RemoteCallCodes.TIMEOUT -> remoteTimeoutMessage
        else -> fallback
    }
    val remoteTargets = remember(remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptyList()
        } else {
            remoteDevices
                .filter {
                    it.online &&
                        graph.remoteTransport.supportsPeerCapability(it.deviceId, RemoteBotAdminClient.CAP_BOT_MANAGE)
                }
                .map { RemoteHostTarget(it.deviceId, it.name) }
        }
    }
    val remoteBotNamesByDevice = remember(state.workspaces) {
        state.workspaces
            .mapNotNull { ws -> ws.remote?.let { it.deviceId to ws.name } }
            .groupBy({ it.first }, { it.second })
    }
    val canCreateRemoteBotDeviceIds = remember(remoteTargets) {
        remoteTargets.map { it.deviceId }.toSet()
    }
    val remoteBotManageWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(remote.deviceId, RemoteBotAdminClient.CAP_BOT_MANAGE)
            }.map { it.id }.toSet()
        }
    }
    val remoteSessionManageWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(remote.deviceId, RemoteBotAdminClient.CAP_SESSION_MANAGE)
            }.map { it.id }.toSet()
        }
    }
    val remoteFilesWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(remote.deviceId, RemoteFileClient.CAP_WORKSPACE_FILES)
            }.map { it.id }.toSet()
        }
    }
    val remoteControlsWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(remote.deviceId, RemoteBotControlClient.CAP_BOT_CONTROLS)
            }.map { it.id }.toSet()
        }
    }
    val remoteCapabilitiesWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(
                        remote.deviceId,
                        RemoteBotControlClient.CAP_BOT_CAPABILITIES,
                    )
            }.map { it.id }.toSet()
        }
    }
    val remoteSessionControlsWorkspaceIds = remember(state.workspaces, remoteDevices, relayState) {
        if (relayState != RelayLiveState.Online) {
            emptySet()
        } else {
            state.workspaces.filter { ws ->
                val remote = ws.remote ?: return@filter false
                remote.online &&
                    graph.remoteTransport.supportsPeerCapability(remote.deviceId, RemoteBotControlClient.CAP_SESSION_CONTROLS)
            }.map { it.id }.toSet()
        }
    }
    val activeWorkspace = remember(state.workspaces, state.currentWorkspaceId) {
        state.workspaces.firstOrNull { it.id == state.currentWorkspaceId }
    }
    val activeRemote = activeWorkspace?.remote
    var remoteModelsSnapshot by remember { mutableStateOf<RemoteModelsSnapshot?>(null) }
    var remoteCurrentModel by remember { mutableStateOf<RemoteModelRef?>(null) }
    var remoteCurrentThinking by remember { mutableStateOf<ThinkingLevel?>(null) }
    var remoteCommands by remember { mutableStateOf<List<RemoteSlashCommand>>(emptyList()) }
    var remoteModelsLoading by remember { mutableStateOf(false) }
    var remoteReloading by remember { mutableStateOf(false) }
    var remoteControlRefreshEpoch by remember { mutableStateOf(0) }
    var remoteLoadedWorkspaceId by remember { mutableStateOf<String?>(null) }
    val latestWorkspaces by rememberUpdatedState(state.workspaces)
    val latestActiveWorkspace by rememberUpdatedState(activeWorkspace)
    val latestDrawerOpen by rememberUpdatedState(drawerOpen)
    LaunchedEffect(graph.remoteTransport) {
        graph.remoteTransport.syncEvents.collect { event ->
            val targetWorkspaceIds = latestWorkspaces
                .filter { ws ->
                    val remote = ws.remote ?: return@filter false
                    remote.deviceId == event.deviceId && remote.botId == event.botId
                }
                .map { it.id }
            val currentRemote = latestActiveWorkspace?.remote
            val matchesActive =
                currentRemote != null && currentRemote.deviceId == event.deviceId && currentRemote.botId == event.botId
            when (event.kind) {
                "bot_activity" -> {
                    val sessionId = event.sessionId
                    val busy = event.busy
                    if (sessionId != null && busy != null && targetWorkspaceIds.isNotEmpty()) {
                        var matchedAny = false
                        var updated = remoteDrawerStates
                        targetWorkspaceIds.forEach { wsId ->
                            val currentState = updated[wsId] ?: return@forEach
                            if (currentState.sessions.any { it.id == sessionId }) {
                                matchedAny = true
                                val nextSessions = currentState.sessions.map { item ->
                                    if (item.id == sessionId) item.copy(busy = busy) else item
                                }
                                updated = updated + (wsId to currentState.copy(sessions = nextSessions))
                            }
                        }
                        remoteDrawerStates = updated
                        if (!matchedAny && latestDrawerOpen) remoteRefreshEpoch += 1
                    } else if (latestDrawerOpen) {
                        remoteRefreshEpoch += 1
                    }
                    if (busy == false && matchesActive) {
                        remoteControlRefreshEpoch += 1
                    }
                }

                "model_changed" -> {
                    if (matchesActive) {
                        remoteControlRefreshEpoch += 1
                    }
                }

                "approval_changed",
                "sessions_changed" -> {
                    if (latestDrawerOpen) remoteRefreshEpoch += 1
                    if (matchesActive && event.kind == "sessions_changed") {
                        remoteControlRefreshEpoch += 1
                    }
                }
            }
        }
    }
    LaunchedEffect(
        activeWorkspace?.id,
        activeRemote?.deviceId,
        activeRemote?.botId,
        activeRemote?.online,
        state.conversationId,
        relayState,
        remoteControlRefreshEpoch,
        state.sending,
    ) {
        val workspace = activeWorkspace
        val remote = workspace?.remote
        if (workspace == null || remote == null) {
            remoteLoadedWorkspaceId = null
            remoteModelsSnapshot = null
            remoteCurrentModel = null
            remoteCurrentThinking = null
            remoteCommands = emptyList()
            remoteModelsLoading = false
            return@LaunchedEffect
        }
        if (remoteLoadedWorkspaceId != workspace.id) {
            remoteLoadedWorkspaceId = workspace.id
            remoteModelsSnapshot = null
            remoteCurrentModel = null
            remoteCurrentThinking = null
            remoteCommands = emptyList()
        }
        if (!remote.online || relayState != RelayLiveState.Online) {
            remoteModelsLoading = false
            return@LaunchedEffect
        }
        val canControl = workspace.id in remoteControlsWorkspaceIds
        if (state.sending && (!canControl || remoteModelsSnapshot != null)) {
            return@LaunchedEffect
        }
        val conversationId = state.conversationId
        val peerSid = conversationId?.let(RemoteSessionRegistry::peerSessionId)
        val stateSessionId = peerSid ?: "__active__"
        if (canControl && remoteModelsSnapshot == null) {
            remoteModelsLoading = true
        }
        coroutineScope {
            val stateDeferred = async {
                graph.remoteBotControlClient.fetchState(remote.deviceId, remote.botId, stateSessionId)
            }
            val modelsDeferred = if (canControl) {
                async { graph.remoteBotControlClient.fetchModels(remote.deviceId, remote.botId, peerSid) }
            } else {
                null
            }
            val commandsDeferred = if (canControl) {
                async { graph.remoteBotControlClient.fetchCommands(remote.deviceId, remote.botId) }
            } else {
                null
            }
            val stateRes = stateDeferred.await()
            val modelsRes = modelsDeferred?.await()
            val commandsRes = commandsDeferred?.await()
            if (stateRes is RemoteControlResult.Ok) {
                stateRes.value.model?.let { remoteCurrentModel = it }
                stateRes.value.thinkingLevel?.let { remoteCurrentThinking = it }
                if (conversationId != null) {
                    RemoteSessionRegistry.updateUsage(conversationId, stateRes.value.usage)
                    chatViewModel.updateSessionUsage(conversationId, stateRes.value.usage)
                }
            }
            if (modelsRes is RemoteControlResult.Ok) {
                remoteModelsSnapshot = modelsRes.value
                if (remoteCurrentModel == null) remoteCurrentModel = modelsRes.value.currentModel
                if (remoteCurrentThinking == null) remoteCurrentThinking = modelsRes.value.currentThinkingLevel
            }
            if (commandsRes is RemoteControlResult.Ok) {
                remoteCommands = commandsRes.value
            }
            remoteModelsLoading = false
        }
    }
    LaunchedEffect(
        drawerOpen,
        expandedWorkspaceIds,
        remoteBots,
        state.workspaces,
        relayState,
        relayCapabilities,
        showArchivedRemoteWorkspaces,
        remoteRefreshEpoch,
    ) {
        if (!drawerOpen) return@LaunchedEffect
        val expandedRemote = state.workspaces.filter { workspace ->
            workspace.id in expandedWorkspaceIds && workspace.remote?.online == true
        }
        if (relayState != RelayLiveState.Online) {
            if (expandedRemote.isNotEmpty()) {
                var next = remoteDrawerStates
                expandedRemote.forEach { ws ->
                    next = next + (ws.id to RemoteDrawerState(status = RemoteDrawerStatus.Offline))
                }
                remoteDrawerStates = next
            }
            return@LaunchedEffect
        }
        expandedRemote.forEach { workspace ->
            val remote = workspace.remote ?: return@forEach
            val existing = remoteDrawerStates[workspace.id]
            if (existing == null || existing.sessions.isEmpty()) {
                remoteDrawerStates = remoteDrawerStates + (
                    workspace.id to RemoteDrawerState(status = RemoteDrawerStatus.Loading)
                )
            }
            val includeArchived = workspace.id in showArchivedRemoteWorkspaces &&
                workspace.id in remoteSessionManageWorkspaceIds
            launch {
                val nextState = when (
                    val outcome = graph.relayLive.listRemoteSessions(
                        deviceId = remote.deviceId,
                        botId = remote.botId,
                        includeArchived = includeArchived,
                    )
                ) {
                    is RemoteSessionsOutcome.Ok -> RemoteDrawerState(sessions = outcome.sessions, status = null)
                    RemoteSessionsOutcome.RelayUpgradeRequired ->
                        RemoteDrawerState(status = RemoteDrawerStatus.RelayUpgradeRequired)

                    RemoteSessionsOutcome.PeerUpgradeRequired ->
                        RemoteDrawerState(status = RemoteDrawerStatus.PeerUpgradeRequired)

                    is RemoteSessionsOutcome.Failed -> RemoteDrawerState(
                        status = RemoteDrawerStatus.Error(
                            localizeRemoteError(outcome.error.code, remoteLoadFailedMessage),
                        ),
                    )
                }
                remoteDrawerStates = remoteDrawerStates + (workspace.id to nextState)
            }
        }
    }
    val appResources = LocalResources.current
    val intakeChat = ShellIntakeChatState(
        ready = state.ready,
        historyLoaded = state.historyLoaded,
        sending = state.sending,
        switchingConversation = state.switchingConversation,
        conversationId = state.conversationId,
        conversationCwd = state.conversationCwd,
        conversations = state.conversations,
        archivedConversations = state.archivedConversations,
        archivedCount = state.archivedCount,
    )
    ShellIntakeHost(
        openSessionId = openSessionId,
        pendingShare = pendingShare,
        pendingDeepDestination = pendingDeepDestination,
        pendingPluginImportUri = pendingPluginImportUri,
        chat = intakeChat,
        chatSnapshot = {
            val latest = chatViewModel.state.value
            ShellIntakeChatState(
                latest.ready, latest.historyLoaded, latest.sending, latest.switchingConversation,
                latest.conversationId, latest.conversationCwd, latest.conversations,
                latest.archivedConversations, latest.archivedCount,
            )
        },
        actions = ShellIntakeActions(
            onOpenSession = { sessionId ->
                drawerOpen = false
                navigator.reset(AppDestination.Chat)
                chatViewModel.openConversation(sessionId)
                onOpenSessionConsumed()
            },
            onSessionMissing = {
                Toast.makeText(context, sessionMissingMessage, Toast.LENGTH_SHORT).show()
                onOpenSessionConsumed()
            },
            onRefreshSessions = chatViewModel::requestSessionsRefresh,
            onApplyDeepLink = { destination ->
                drawerOpen = false
                navigator.reset(AppDestination.Chat)
                deepLinkStack(destination).forEach(navigator::push)
                onDeepDestinationConsumed()
            },
            onPluginImport = { uri ->
                drawerOpen = false
                navigator.push(AppDestination.Plugins)
                pluginImportUri = uri
                onPluginImportConsumed()
            },
            onShareStaged = { payload ->
                val staged = withContext(Dispatchers.IO) { graph.shareStaging.stage(payload.streams) }
                staged.files.forEach { stagedFile ->
                    graph.shareStaging.shareUri(stagedFile.file)?.let(chatViewModel::attach)
                }
                if (payload.text.isNotBlank()) chatViewModel.onInputChange(payload.text)
                staged.failures.forEach { failure ->
                    Toast.makeText(context, graph.shareStaging.messageFor(failure), Toast.LENGTH_SHORT).show()
                }
                if (payload.streams.isEmpty() && payload.text.isBlank()) {
                    Toast.makeText(context, R.string.share_error_no_content, Toast.LENGTH_SHORT).show()
                }
                navigator.reset(AppDestination.Chat)
                onShareConsumed()
            },
            onShareTimeout = {
                Toast.makeText(context, shareImportTimeoutMessage, Toast.LENGTH_SHORT).show()
                onShareConsumed()
            },
        ),
    )
    LaunchedEffect(state.ready, state.conversationId, state.conversationCwd) {
        if (state.ready) chatViewModel.refreshAvailableSkills()
    }
    ShellCommandPalette(input = state.input) { chatViewModel.refreshAvailableSkills() }

    val compactTitle = stringResource(R.string.chat_cmd_compact)
    val compactDescription = stringResource(R.string.chat_cmd_compact_desc)
    val goalTitle = stringResource(R.string.chat_cmd_goal)
    val goalDescription = stringResource(R.string.chat_cmd_goal_desc)
    val isRemoteWorkspace = activeRemote != null
    val canCompactRemote = activeWorkspace != null && activeWorkspace.id in remoteSessionControlsWorkspaceIds
    val canCompactCommand = !isRemoteWorkspace || canCompactRemote
    val commandItems = remember(
        state.conversationId,
        state.sending,
        state.compacting,
        availableSkills,
        compactTitle,
        compactDescription,
        goalTitle,
        goalDescription,
        isRemoteWorkspace,
        canCompactCommand,
        remoteCommands,
    ) {
        buildCommandItems(
            conversationId = state.conversationId,
            sending = state.sending,
            compacting = state.compacting,
            skills = availableSkills,
            compactTitle = compactTitle,
            compactDescription = compactDescription,
            goalTitle = goalTitle,
            goalDescription = goalDescription,
            isRemote = isRemoteWorkspace,
            canCompact = canCompactCommand,
            remoteCommands = remoteCommands,
        )
    }
    val commandExecutor = object : ShellCommandExecutor {
        override fun perform(action: ShellCommandAction) {
            val sessionId = chatViewModel.state.value.conversationId
            when (action) {
                ShellCommandAction.Compact -> {
                    if (activeRemote != null && !canCompactRemote) {
                        chatViewModel.showCommandError(remotePeerUpgradeMessage)
                    } else {
                        chatViewModel.compactContext()
                    }
                }
                ShellCommandAction.GoalPause,
                ShellCommandAction.GoalResume,
                ShellCommandAction.GoalClear,
                ShellCommandAction.GoalInvalid,
                is ShellCommandAction.GoalCreate,
                -> if (sessionId == null) {
                    chatViewModel.showCommandError(appResources.getString(R.string.chat_error_session_not_ready))
                } else {
                    when (action) {
                        ShellCommandAction.GoalPause -> scope.launch { graph.goalService.pause(sessionId) }
                        ShellCommandAction.GoalResume -> scope.launch { graph.goalService.resume(sessionId) }
                        ShellCommandAction.GoalClear -> scope.launch { graph.goalService.clear(sessionId) }
                        is ShellCommandAction.GoalCreate -> scope.launch {
                            if (graph.goalService.create(sessionId, action.objective) == null) {
                                chatViewModel.showCommandError(appResources.getString(R.string.chat_cmd_goal_invalid))
                            }
                        }
                        else -> chatViewModel.showCommandError(appResources.getString(R.string.chat_cmd_goal_invalid))
                    }
                }
            }
        }
    }

    fun pickCommand(item: CommandItem) {
        chatViewModel.onInputChange(commandInsertion(state.input, item.insertText))
    }

    fun submitChatInput() {
        when (val plan = planSubmission(state.input, state.sending, isRemote = isRemoteWorkspace)) {
            ShellSubmitPlan.Send -> chatViewModel.send(uiPreferences.sendWhileRunning)
            ShellSubmitPlan.RejectWhileSending ->
                chatViewModel.showCommandError(appResources.getString(R.string.chat_error_stop_before_command))
            is ShellSubmitPlan.RunCommand -> {
                chatViewModel.onInputChange("")
                commandExecutor.perform(plan.action)
            }
            is ShellSubmitPlan.UnknownCommand ->
                chatViewModel.showCommandError(appResources.getString(R.string.chat_cmd_unknown, plan.name))
        }
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun openMiniappCreate() {
        drawerOpen = false
        chatViewModel.newSession(AgentRuntimeHost.HOME_ID, MINIAPP_SKILL_COMMAND)
        navigator.reset(AppDestination.Chat)
    }

    fun openChat() {
        drawerOpen = false
        navigator.reset(AppDestination.Chat)
    }

    fun openSettings() {
        drawerOpen = false
        navigator.push(AppDestination.Settings)
    }

    fun openProviders() {
        drawerOpen = false
        navigator.push(AppDestination.Providers)
    }

    fun back() {
        if (drawerOpen) {
            drawerOpen = false
        } else {
            navigator.pop()
        }
    }

    ShellNavigationBackHandler(navigator = navigator, drawerOpen = drawerOpen, onBack = ::back)
    ShellNavigationChrome(navigator = navigator)

    CompositionLocalProvider(LocalHapticsEnabled provides true) {
    Box(Modifier.fillMaxSize().background(colors.paper)) {
        val reminderCount = remember(timers, state.conversationId) {
            timers.count { it.enabled && it.sessionId == state.conversationId }
        }
        val runningJobCount = remember(activeJobs) {
            activeJobs.count { it.status == JobStatus.RUNNING }
        }
        AnimatedScreenHost(
            navigator = navigator,
            modifier = Modifier.fillMaxSize(),
            predictiveBackEnabled = !drawerOpen,
            base = { baseFrozen ->
                val frozenState = rememberFrozenChatState(state, baseFrozen)
                val remoteWs = activeRemote
                val effectiveProviders = if (remoteWs != null) {
                    remoteModelsSnapshot?.providers.orEmpty()
                } else {
                    configuredProviderList
                }
                val canControlRemoteModel = activeWorkspace != null && activeWorkspace.id in remoteControlsWorkspaceIds
                val remoteFilesVisible = activeWorkspace != null && activeWorkspace.id in remoteFilesWorkspaceIds
                val reloadVisible = remoteWs == null || canControlRemoteModel
                val remotePresentContext = remember(remoteWs, attachmentRoot, remoteDevices, relayState) {
                    val remote = remoteWs ?: return@remember null
                    val supportsArtifacts = relayState == RelayLiveState.Online &&
                        remote.online &&
                        graph.remoteTransport.supportsPeerCapability(
                            remote.deviceId,
                            RemoteArtifactClient.CAP_PRESENT_ARTIFACTS,
                        )
                    object : RemotePresentContext {
                        override val deviceName: String = remote.deviceName
                        override val supportsArtifacts: Boolean = supportsArtifacts
                        override fun cachedFile(artifactId: String, name: String, size: Long?): File? =
                            graph.remoteArtifactClient.cachedFile(attachmentRoot, artifactId, name, size)

                        override suspend fun fetch(
                            artifactId: String,
                            name: String,
                            size: Long?,
                            onProgress: (Long, Long) -> Unit,
                        ): Result<File> = when (
                            val res = graph.remoteArtifactClient.fetch(
                                deviceId = remote.deviceId,
                                botId = remote.botId,
                                workspaceRoot = attachmentRoot,
                                artifactId = artifactId,
                                name = name,
                                expectedSize = size,
                                onProgress = onProgress,
                            )
                        ) {
                            is RemoteArtifactResult.Ok -> Result.success(res.file)
                            RemoteArtifactResult.PeerUpgradeRequired ->
                                Result.failure(IllegalStateException(remotePeerUpgradeMessage))
                            is RemoteArtifactResult.Error ->
                                Result.failure(IllegalStateException(localizeRemoteError(res.code, remoteActionFailedMessage)))
                        }
                    }
                }
                val effectiveState = remember(
                    frozenState,
                    configuredProviders,
                    remoteWs,
                    remoteModelsSnapshot,
                    remoteCurrentModel,
                    remoteCurrentThinking,
                    remoteReloading,
                ) {
                    if (remoteWs != null) {
                        val selectedProviderId = remoteCurrentModel?.providerId
                            ?: remoteModelsSnapshot?.providers?.firstOrNull()?.id.orEmpty()
                        val filteredModels = remoteModelsSnapshot?.models
                            ?.filter { selectedProviderId.isBlank() || it.providerId == selectedProviderId }
                            .orEmpty()
                            .ifEmpty { remoteModelsSnapshot?.models.orEmpty() }
                        val selectedModelId = remoteCurrentModel?.modelId.orEmpty()
                        val selectedModelLabel = remoteCurrentModel?.name?.takeIf { it.isNotBlank() }
                            ?: filteredModels.firstOrNull { it.id == selectedModelId }?.displayName
                            ?: selectedModelId
                        frozenState.copy(
                            configured = true,
                            providerId = selectedProviderId,
                            modelId = selectedModelId,
                            modelLabel = selectedModelLabel,
                            models = filteredModels,
                            thinkingLevel = remoteCurrentThinking ?: ThinkingLevel.OFF,
                            reloading = frozenState.reloading || remoteReloading,
                        )
                    } else {
                        frozenState.copy(configured = frozenState.providerId in configuredProviders)
                    }
                }
                ChatScreen(
                    state = effectiveState,
                    nickname = nickname,
                    avatar = avatarBitmap,
                    providers = effectiveProviders,
                    attachmentRoot = attachmentRoot,
                    canControlRemoteModel = canControlRemoteModel,
                    remoteFilesVisible = remoteFilesVisible,
                    reloadVisible = reloadVisible,
                    remoteModelsLoading = remoteModelsLoading,
                    remoteThinkingLevels = remoteModelsSnapshot?.thinkingLevels.orEmpty(),
                    remotePresentContext = remotePresentContext,
                    actions = ChatScreenActions(
                        onInputChange = chatViewModel::onInputChange,
                        onSend = ::submitChatInput,
                        onStop = chatViewModel::stop,
                        onOpenDrawer = { drawerOpen = true },
                        onManageProviders = ::openProviders,
                        onReload = {
                            if (remoteWs != null) {
                                if (state.sending) {
                                    Toast.makeText(context, reloadBusyMessage, Toast.LENGTH_SHORT).show()
                                } else if (!canControlRemoteModel) {
                                    Toast.makeText(context, remotePeerUpgradeMessage, Toast.LENGTH_SHORT).show()
                                } else {
                                    val peerSid = state.conversationId?.let(RemoteSessionRegistry::peerSessionId)
                                    remoteReloading = true
                                    scope.launch {
                                        val res = graph.remoteBotControlClient.reloadBot(
                                            remoteWs.deviceId,
                                            remoteWs.botId,
                                            peerSid,
                                        )
                                        remoteReloading = false
                                        when (res) {
                                            is RemoteControlResult.Ok -> {
                                                remoteControlRefreshEpoch += 1
                                                remoteRefreshEpoch += 1
                                                Toast.makeText(context, reloadDoneMessage, Toast.LENGTH_SHORT).show()
                                            }
                                            is RemoteControlResult.Error -> {
                                                Toast.makeText(
                                                    context,
                                                    localizeRemoteError(res.code, reloadFailedMessage),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                scope.launch {
                                    val result = chatViewModel.reloadSession()
                                    Toast.makeText(
                                        context,
                                        result.exceptionOrNull()?.message ?: reloadDoneMessage,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onModelChange = { modelId ->
                            if (remoteWs != null) {
                                val option = effectiveState.models.firstOrNull { it.id == modelId }
                                    ?: remoteModelsSnapshot?.models?.firstOrNull { it.id == modelId }
                                if (option != null) {
                                    val previousModel = remoteCurrentModel
                                    val previousThinking = remoteCurrentThinking
                                    val targetProvider = option.providerId.ifBlank { effectiveState.providerId }
                                    val targetThinking = option.thinkingLevels.resolveThinkingLevel(effectiveState.thinkingLevel)
                                    val peerSid = state.conversationId?.let(RemoteSessionRegistry::peerSessionId)
                                    remoteCurrentModel = RemoteModelRef(targetProvider, option.id, option.displayName)
                                    remoteCurrentThinking = targetThinking
                                    scope.launch {
                                        when (
                                            val res = graph.remoteBotControlClient.setModel(
                                                remoteWs.deviceId,
                                                remoteWs.botId,
                                                targetProvider,
                                                option.id,
                                                peerSid,
                                                targetThinking,
                                            )
                                        ) {
                                            is RemoteControlResult.Ok -> {
                                                res.value.first?.let { remoteCurrentModel = it }
                                                res.value.second?.let { remoteCurrentThinking = it }
                                                remoteControlRefreshEpoch += 1
                                            }
                                            is RemoteControlResult.Error -> {
                                                remoteCurrentModel = previousModel
                                                remoteCurrentThinking = previousThinking
                                                Toast.makeText(
                                                    context,
                                                    localizeRemoteError(res.code, switchModelFailedMessage),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                chatViewModel.setModel(modelId)
                            }
                        },
                        onProviderChange = { providerId ->
                            if (remoteWs != null) {
                                val providerModels = remoteModelsSnapshot?.models
                                    ?.filter { it.providerId == providerId }
                                    .orEmpty()
                                val targetModel = providerModels.firstOrNull { it.id == effectiveState.modelId }
                                    ?: providerModels.firstOrNull()
                                if (targetModel != null) {
                                    val previousModel = remoteCurrentModel
                                    val previousThinking = remoteCurrentThinking
                                    val targetThinking = targetModel.thinkingLevels.resolveThinkingLevel(effectiveState.thinkingLevel)
                                    val peerSid = state.conversationId?.let(RemoteSessionRegistry::peerSessionId)
                                    remoteCurrentModel = RemoteModelRef(providerId, targetModel.id, targetModel.displayName)
                                    remoteCurrentThinking = targetThinking
                                    scope.launch {
                                        when (
                                            val res = graph.remoteBotControlClient.setModel(
                                                remoteWs.deviceId,
                                                remoteWs.botId,
                                                providerId,
                                                targetModel.id,
                                                peerSid,
                                                targetThinking,
                                            )
                                        ) {
                                            is RemoteControlResult.Ok -> {
                                                res.value.first?.let { remoteCurrentModel = it }
                                                res.value.second?.let { remoteCurrentThinking = it }
                                                remoteControlRefreshEpoch += 1
                                            }
                                            is RemoteControlResult.Error -> {
                                                remoteCurrentModel = previousModel
                                                remoteCurrentThinking = previousThinking
                                                Toast.makeText(
                                                    context,
                                                    localizeRemoteError(res.code, switchModelFailedMessage),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            } else {
                                chatViewModel.setProvider(providerId)
                            }
                        },
                        onThinkingLevelChange = { level ->
                            if (remoteWs != null) {
                                val previousThinking = remoteCurrentThinking
                                val peerSid = state.conversationId?.let(RemoteSessionRegistry::peerSessionId)
                                remoteCurrentThinking = level
                                scope.launch {
                                    when (
                                        val res = graph.remoteBotControlClient.setThinkingLevel(
                                            remoteWs.deviceId,
                                            remoteWs.botId,
                                            level,
                                            peerSid,
                                        )
                                    ) {
                                        is RemoteControlResult.Ok -> {
                                            remoteCurrentThinking = res.value
                                            remoteControlRefreshEpoch += 1
                                        }
                                        is RemoteControlResult.Error -> {
                                            remoteCurrentThinking = previousThinking
                                            Toast.makeText(
                                                context,
                                                localizeRemoteError(res.code, switchThinkingFailedMessage),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            } else {
                                chatViewModel.setThinkingLevel(level)
                            }
                        },
                        onToggleStep = chatViewModel::toggleStep,
                        onQuoteMessage = chatViewModel::quote,
                        onClearQuote = chatViewModel::clearQuote,
                        onPickAttachment = { overlays.attachmentSheetOpen = true },
                        onRemoveAttachment = chatViewModel::removeAttachment,
                        onOpenAttachment = { attachment ->
                            val targetFile = graph.attachmentStore.file(attachment, attachmentRoot)
                            val remote = remoteWs
                            if (!targetFile.isFile && remote != null && !File(attachment.path).isAbsolute) {
                                scope.launch {
                                    val cached = withContext(Dispatchers.IO) {
                                        targetFile.parentFile?.mkdirs()
                                        val tmp = File(targetFile.parentFile ?: attachmentRoot, ".tmp-${targetFile.name}.part")
                                        val outcome = try {
                                            tmp.outputStream().use { out ->
                                                graph.remoteFileClient.downloadToStream(
                                                    deviceId = remote.deviceId,
                                                    botId = remote.botId,
                                                    remotePath = attachment.path,
                                                    outputStream = out,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            tmp.delete()
                                            io.github.whywood.superpi.remote.RemoteFileResult.Error("io_error", e.message.orEmpty())
                                        }
                                        when (outcome) {
                                            is io.github.whywood.superpi.remote.RemoteFileResult.Ok -> {
                                                if (!tmp.renameTo(targetFile)) {
                                                    tmp.copyTo(targetFile, overwrite = true)
                                                    tmp.delete()
                                                }
                                                outcome
                                            }
                                            is io.github.whywood.superpi.remote.RemoteFileResult.Error -> {
                                                tmp.delete()
                                                outcome
                                            }
                                        }
                                    }
                                    when (cached) {
                                        is io.github.whywood.superpi.remote.RemoteFileResult.Ok -> {
                                            overlays.previewRoot = attachmentRoot
                                            overlays.previewAttachment = attachment
                                        }
                                        is io.github.whywood.superpi.remote.RemoteFileResult.Error -> {
                                            Toast.makeText(
                                                context,
                                                localizeRemoteError(cached.code, remoteActionFailedMessage),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                }
                            } else {
                                overlays.previewRoot = attachmentRoot
                                overlays.previewAttachment = attachment
                            }
                        },
                        onOpenFile = openWorkspaceFile,
                        onOpenTimers = { navigator.push(AppDestination.Timers) },
                        onRetry = chatViewModel::retry,
                        onPickCommand = ::pickCommand,
                        onPauseGoal = {
                                                state.conversationId?.let { id -> scope.launch { graph.goalService.pause(id) } }
                                            },
                        onResumeGoal = {
                                                state.conversationId?.let { id -> scope.launch { graph.goalService.resume(id) } }
                                            },
                        onOpenJobs = {
                                                overlays.selectedJobId = null
                                                overlays.jobOutput = null
                                                overlays.jobsSheetOpen = true
                                            },
                        onModelsOpenChange = { modelsOpen = it },
                        onOpenUsage = { overlays.usageSheetOpen = true },
                        onAnswerQuestion = { pending, answers ->
                                                graph.questionCenter.answer(pending.requestId, answers)
                                            },
                        onOpenForeignQuestion = { sessionId ->
                                                navigator.reset(AppDestination.Chat)
                                                chatViewModel.openConversationById(sessionId)
                                            },
                        onOpenPresentFile = { raw ->
                                                scope.launch {
                                                    val resolved = graph.attachmentStore.resolve(raw, attachmentRoot)
                                                    if (resolved == null) {
                                                        Toast.makeText(
                                                            context,
                                                            attachmentMissingMessage,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    } else if (
                                                        resolved.kind != AttachmentKind.OTHER ||
                                                        resolved.mime.startsWith("video/")
                                                    ) {
                                                        overlays.previewRoot = attachmentRoot
                                                        overlays.previewAttachment = resolved
                                                    } else {
                                                        val intent = graph.attachmentStore.viewIntent(resolved, attachmentRoot)
                                                        val opened = intent != null && runCatching { context.startActivity(intent) }.isSuccess
                                                        if (!opened) {
                                                            Toast.makeText(context, presentOpenFailed, Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                        onSharePresentFile = { raw ->
                                                scope.launch {
                                                    val resolved = graph.attachmentStore.resolve(raw, attachmentRoot)
                                                    if (resolved == null) {
                                                        Toast.makeText(
                                                            context,
                                                            attachmentMissingMessage,
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    } else {
                                                        val intent = graph.attachmentStore.shareIntent(resolved, attachmentRoot)
                                                        if (intent != null) {
                                                            context.startActivity(shareChooser(context, intent, shareChooserTitle))
                                                        } else {
                                                            val export = graph.attachmentStore.export(resolved, attachmentRoot)
                                                            val exportIntent = export?.let {
                                                                exportShareIntent(context, it, resolved.name, resolved.mime)
                                                            }
                                                            if (export != null && exportIntent != null) {
                                                                val message = if (export is AttachmentExport.SharedStorage) {
                                                                    String.format(filesExportDonePathFormat, export.file.absolutePath)
                                                                } else {
                                                                    filesExportDone
                                                                }
                                                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                                                context.startActivity(shareChooser(context, exportIntent, shareChooserTitle))
                                                            } else {
                                                                Toast.makeText(context, presentShareFailed, Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                        onOpenMiniapp = { id -> navigator.push(AppDestination.MiniappDetail(id)) },
                        onOpenPluginPage = { pluginId, pageId -> navigator.push(AppDestination.PluginPage(pluginId, pageId)) },
                        onOpenFiles = {
                            val activeWorkspace = state.workspaces.firstOrNull { it.id == state.currentWorkspaceId }
                            val remote = activeWorkspace?.remote
                            if (remote != null && remote.online && activeWorkspace.id in remoteFilesWorkspaceIds) {
                                navigator.push(
                                    AppDestination.RemoteFiles(
                                        deviceId = remote.deviceId,
                                        deviceName = remote.deviceName,
                                        botId = remote.botId,
                                        botName = remote.botName,
                                    ),
                                )
                            } else {
                                navigator.push(AppDestination.Files(attachmentRoot.absolutePath))
                            }
                        },
                        onExportSession = {
                            val summary = state.conversations.firstOrNull { it.id == state.conversationId }
                            if (summary == null) {
                                Toast.makeText(context, R.string.assistant_export_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                exportSummary = summary
                                exportLauncher.launch(
                                    SessionExporter.suggestedFileName(summary.title, summary.id),
                                )
                            }
                        },
                        onRemoveQueued = chatViewModel::removeQueuedInput,
                        onSendQueued = chatViewModel::sendQueuedNow,
                        onScrollAnchorChange = chatViewModel::updateScrollAnchor,
                        onOpenBotSettings = { id -> navigator.push(AppDestination.WorkspaceSettings(id)) },
                    ),
                    overlay = ChatScreenOverlay(
                        reminderCount = reminderCount,
                        reloading = effectiveState.reloading,
                        compacting = frozenState.compacting,
                        commandItems = commandItems,
                        goal = activeGoal,
                        jobCount = runningJobCount,
                        modelsOpen = modelsOpen,
                        pendingQuestions = activeQuestions,
                        foreignQuestion = foreignQuestion,
                    ),
                    scrollAnchorFor = chatViewModel::scrollAnchorFor,
                    autoCollapseProcess = uiPreferences.autoCollapseProcess,
                    processDetail = uiPreferences.processDetail,
                    chatFontScale = uiPreferences.chatFontScale,
                    voiceLanguage = appLanguage,
                )
            },
        ) { destination ->
            AppRouteContent(
                destination = destination,
                host = AppRouteHost(
                    navigator = navigator,
                    dependencies = graph,
                    scope = scope,
                    chatViewModel = chatViewModel,
                    chat = ChatRouteData(
                        conversationId = state.conversationId,
                        workspaces = state.workspaces,
                        archived = state.archivedConversations,
                        archivedCount = state.archivedCount,
                    ),
                    ui = ShellRouteUi(
                        themeLabel = themeState.label,
                        fontStyle = DdFonts.style,
                        onFontStyleChange = onFontStyleChange,
                        nickname = nickname,
                        avatar = avatarBitmap,
                        providerName = defaultProvider?.name,
                        modelLabel = defaultModelLabel,
                        modelConfig = defaultModelConfig,
                        configuredProviderIds = configuredProviders,
                    ),
                    onOpenChat = ::openChat,
                    onOpenMiniappCreate = ::openMiniappCreate,
                    onOpenFile = openWorkspaceFile,
                    onOpenAssistantSettings = { navigator.push(AppDestination.AndroidSettings) },
                    pluginImportUri = pluginImportUri,
                    onPluginImportHandled = { pluginImportUri = null },
                ),
            )
        }

        fun openRemoteSession(workspace: RuntimeWorkspace, sessionId: String, sessionTitle: String) {
            val remote = workspace.remote ?: return
            drawerOpen = false
            chatViewModel.openRemoteMirrorSession(
                workspace = workspace,
                sessionTitle = sessionTitle,
                errorFallback = remoteOpenFailedMessage,
                loadSession = {
                    when (val outcome = graph.relayLive.getRemoteHistory(remote.deviceId, remote.botId, sessionId)) {
                        is RemoteHistoryOutcome.Ok -> {
                            val summary = RemoteSessionBrowser.materializeHistory(
                                runtime = graph.host,
                                agentDir = graph.agentDir,
                                workspace = workspace,
                                history = outcome.history,
                                skipIfUnchanged = false,
                            ) ?: return@openRemoteMirrorSession RemoteSessionOpenResult.Failure(remoteOpenFailedMessage)
                            RemoteSessionOpenResult.Success(summary = summary)
                        }

                        RemoteHistoryOutcome.RelayUpgradeRequired ->
                            RemoteSessionOpenResult.Failure(remoteRelayUpgradeMessage)

                        RemoteHistoryOutcome.PeerUpgradeRequired ->
                            RemoteSessionOpenResult.Failure(remotePeerUpgradeMessage)

                        is RemoteHistoryOutcome.Failed ->
                            RemoteSessionOpenResult.Failure(
                                localizeRemoteError(outcome.error.code, remoteOpenFailedMessage),
                            )
                    }
                },
            )
        }
        val drawerState = ShellDrawerState(
            open = drawerOpen,
            sessions = state.conversations,
            workspaces = state.workspaces,
            activeWorkspaceId = state.currentWorkspaceId,
            activeSessionId = state.conversationId,
            pinnedWorkspaceIds = uiPreferences.pinnedWorkspaceIds,
            expandedWorkspaceIds = expandedWorkspaceIds,
            busySessionIds = state.busySessionIds,
            timerSessionIds = timerSessionIds,
            jobSessionIds = jobSessionIds,
            userName = nickname,
            avatar = avatarBitmap,
            hasUpdate = updateUi.hasUpdate,
            remoteDrawerStates = remoteDrawerStates,
            relayOnline = relayState == RelayLiveState.Online,
            remoteBotManageWorkspaceIds = remoteBotManageWorkspaceIds,
            remoteSessionManageWorkspaceIds = remoteSessionManageWorkspaceIds,
            remoteFilesWorkspaceIds = remoteFilesWorkspaceIds,
            remoteControlsWorkspaceIds = remoteControlsWorkspaceIds,
            remoteCapabilitiesWorkspaceIds = remoteCapabilitiesWorkspaceIds,
            remoteShowArchivedWorkspaceIds = showArchivedRemoteWorkspaces,
            canCreateRemoteBotDeviceIds = canCreateRemoteBotDeviceIds,
        )
        val drawerActions = ShellDrawerActions(
            onNavigate = { destination -> drawerOpen = false; navigator.push(destination) },
            onDismiss = { drawerOpen = false },
            onCreateWorkspace = {
                drawerOpen = false
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
                overlays.workspaceCreateDeviceId = null
                overlays.workspaceCreateOpen = true
            },
            onCreateWorkspaceForDevice = { deviceId ->
                drawerOpen = false
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
                overlays.workspaceCreateDeviceId = deviceId
                overlays.workspaceCreateOpen = true
            },
            onToggleWorkspaceExpanded = { id ->
                expandedWorkspaceIds = if (id in expandedWorkspaceIds) expandedWorkspaceIds - id else expandedWorkspaceIds + id
            },
            onToggleWorkspacePinned = { id ->
                val pinned = id in uiPreferences.pinnedWorkspaceIds
                scope.launch { graph.appPreferences.setWorkspacePinned(id, !pinned) }
            },
            onNewSessionInWorkspace = { id ->
                val target = state.workspaces.firstOrNull { it.id == id }
                val remote = target?.remote
                if (remote != null && remote.online && id in remoteSessionManageWorkspaceIds) {
                    drawerOpen = false
                    scope.launch {
                        when (val res = graph.remoteBotAdminClient.createSession(remote.deviceId, remote.botId)) {
                            is RemoteAdminResult.Ok -> {
                                remoteRefreshEpoch += 1
                                openRemoteSession(target, res.value.id, res.value.title)
                            }
                            is RemoteAdminResult.Error -> {
                                Toast.makeText(
                                    context,
                                    localizeRemoteError(res.code, remoteActionFailedMessage),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                } else {
                    drawerOpen = false
                    chatViewModel.newSession(id)
                }
            },
            onWorkspaceSettings = { id ->
                drawerOpen = false
                navigator.push(AppDestination.WorkspaceSettings(id))
            },
            onRenameWorkspace = { id -> drawerOpen = false; overlays.workspaceRenameId = id },
            onDeleteWorkspace = { id ->
                drawerOpen = false
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
                state.workspaces.firstOrNull { it.id == id }?.let {
                    overlays.workspaceDeleteTarget = it
                    scope.launch {
                        overlays.workspaceDeleteCount = if (it.remote != null) {
                            0
                        } else {
                            chatViewModel.loadWorkspaceSessionCount(id)
                        }
                    }
                }
            },
            onConversation = { id -> drawerOpen = false; chatViewModel.openConversation(id) },
            onRename = chatViewModel::renameSession,
            onSetPinned = chatViewModel::setSessionPinned,
            onSetArchived = chatViewModel::setSessionArchived,
            onDelete = chatViewModel::deleteSession,
            onSettings = ::openSettings,
            onOpenRemoteSession = { workspace, remoteSession ->
                openRemoteSession(workspace, remoteSession.id, remoteSession.title)
            },
            onRetryRemoteSessions = { workspace ->
                if (relayState != RelayLiveState.Online) {
                    graph.relayLive.reconnectNow()
                    remoteDrawerStates = remoteDrawerStates + (
                        workspace.id to RemoteDrawerState(status = RemoteDrawerStatus.Offline)
                    )
                } else {
                    remoteDrawerStates = remoteDrawerStates + (
                        workspace.id to RemoteDrawerState(status = RemoteDrawerStatus.Loading)
                    )
                    remoteRefreshEpoch += 1
                }
            },
            onOpenRemoteFiles = { workspace ->
                val remote = workspace.remote
                if (remote != null) {
                    drawerOpen = false
                    navigator.push(
                        AppDestination.RemoteFiles(
                            deviceId = remote.deviceId,
                            deviceName = remote.deviceName,
                            botId = remote.botId,
                            botName = remote.botName,
                        ),
                    )
                }
            },
            onOpenRemoteInstructions = { workspace ->
                if (workspace.remote != null) {
                    drawerOpen = false
                    overlays.remoteAgentsDocTarget = workspace
                }
            },
            onOpenRemoteCapabilities = { workspace ->
                if (workspace.remote != null) {
                    drawerOpen = false
                    overlays.remoteCapabilitiesTarget = workspace
                }
            },
            onEditRemoteBot = { workspace ->
                if (workspace.remote != null) {
                    drawerOpen = false
                    overlays.remoteOpSubmitting = false
                    overlays.remoteOpError = null
                    overlays.remoteBotEditTarget = workspace
                }
            },
            onToggleRemoteShowArchived = { workspace ->
                showArchivedRemoteWorkspaces = if (workspace.id in showArchivedRemoteWorkspaces) {
                    showArchivedRemoteWorkspaces - workspace.id
                } else {
                    showArchivedRemoteWorkspaces + workspace.id
                }
                remoteRefreshEpoch += 1
            },
            onRenameRemoteSession = { workspace, remoteSession, title ->
                val remote = workspace.remote ?: return@ShellDrawerActions
                scope.launch {
                    when (
                        val res = graph.remoteBotAdminClient.renameSession(
                            remote.deviceId,
                            remote.botId,
                            remoteSession.id,
                            title,
                        )
                    ) {
                        is RemoteAdminResult.Ok -> {
                            remoteRefreshEpoch += 1
                            chatViewModel.renameRemoteMirrorSession(workspace.id, remoteSession.id, res.value)
                        }
                        is RemoteAdminResult.Error -> {
                            Toast.makeText(
                                context,
                                localizeRemoteError(res.code, remoteActionFailedMessage),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onSetRemoteSessionPinned = { workspace, remoteSession, pinned ->
                val remote = workspace.remote ?: return@ShellDrawerActions
                scope.launch {
                    when (
                        val res = graph.remoteBotAdminClient.updateSessionMeta(
                            remote.deviceId,
                            remote.botId,
                            remoteSession.id,
                            pinned = pinned,
                        )
                    ) {
                        is RemoteAdminResult.Ok -> {
                            remoteRefreshEpoch += 1
                            chatViewModel.setRemoteMirrorPinned(workspace.id, remoteSession.id, res.value.pinned)
                        }
                        is RemoteAdminResult.Error -> {
                            Toast.makeText(
                                context,
                                localizeRemoteError(res.code, remoteActionFailedMessage),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onSetRemoteSessionArchived = { workspace, remoteSession, archived ->
                val remote = workspace.remote ?: return@ShellDrawerActions
                scope.launch {
                    when (
                        val res = graph.remoteBotAdminClient.updateSessionMeta(
                            remote.deviceId,
                            remote.botId,
                            remoteSession.id,
                            archived = archived,
                        )
                    ) {
                        is RemoteAdminResult.Ok -> {
                            remoteRefreshEpoch += 1
                            chatViewModel.setRemoteMirrorArchived(workspace.id, remoteSession.id, res.value.archived)
                        }
                        is RemoteAdminResult.Error -> {
                            Toast.makeText(
                                context,
                                localizeRemoteError(res.code, remoteActionFailedMessage),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
            onDeleteRemoteSession = { workspace, remoteSession ->
                val remote = workspace.remote ?: return@ShellDrawerActions
                scope.launch {
                    when (
                        val res = graph.remoteBotAdminClient.deleteSession(
                            remote.deviceId,
                            remote.botId,
                            remoteSession.id,
                        )
                    ) {
                        is RemoteAdminResult.Ok -> {
                            remoteRefreshEpoch += 1
                            chatViewModel.deleteRemoteMirrorSession(workspace.id, remoteSession.id)
                        }
                        is RemoteAdminResult.Error -> {
                            Toast.makeText(
                                context,
                                localizeRemoteError(res.code, remoteActionFailedMessage),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            },
        )
        AppOverlays(
            graph = graph,
            chatViewModel = chatViewModel,
            state = state,
            activeJobs = activeJobs,
            attachmentRoot = attachmentRoot,
            drawer = drawerState,
            drawerActions = drawerActions,
            overlays = overlays,
            remoteTargets = remoteTargets,
            remoteBotNamesByDevice = remoteBotNamesByDevice,
        )

    }
    }
}

private const val MINIAPP_SKILL_COMMAND = "/skill:miniapp "

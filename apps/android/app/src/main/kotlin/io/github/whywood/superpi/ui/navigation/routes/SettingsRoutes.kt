package io.github.whywood.superpi.ui.navigation.routes
import androidx.compose.runtime.getValue

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.BuildConfig
import io.github.whywood.superpi.R
import io.github.whywood.superpi.adb.AdbBridgeRuntime
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.data.PersonalizationUi
import io.github.whywood.superpi.data.StorageMount
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.data.update.releaseOrNull
import io.github.whywood.superpi.runtime.MemoryModelMode
import io.github.whywood.superpi.runtime.MemorySettings
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.runtime.memory.MemoryModelTier
import io.github.whywood.superpi.service.AgentService
import io.github.whywood.superpi.ui.navigation.AppNavigator
import io.github.whywood.superpi.ui.navigation.ShellRouteUi
import io.github.whywood.superpi.ui.settings.AndroidSettingsScreen
import io.github.whywood.superpi.ui.settings.ChatSettingsScreen
import io.github.whywood.superpi.ui.settings.FontScreen
import io.github.whywood.superpi.ui.settings.MemorySettingsScreen
import io.github.whywood.superpi.ui.settings.PersonalizationScreen
import io.github.whywood.superpi.ui.settings.SettingsScreen
import io.github.whywood.superpi.ui.settings.SystemAccessScreen
import io.github.whywood.superpi.ui.settings.ScanSettingsScreen
import io.github.whywood.superpi.ui.settings.SoftwareUpdateScreen
import io.github.whywood.superpi.ui.settings.SuperSettingsScreen
import io.github.whywood.superpi.ui.settings.ThemeScreen
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.workspaces.AgentsDocSheet
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsRoute(
    dependencies: AppDependencies,
    navigator: AppNavigator,
    scope: CoroutineScope,
    ui: ShellRouteUi,
    archivedCount: Int,
    onBack: () -> Unit,
) {
    val hostState by dependencies.hostAccess.state.collectAsStateWithLifecycle()
    val accessLabel = stringResource(
        when (hostState.effective) {
            HostAccess.LOCAL -> R.string.access_level_local
            HostAccess.ADB -> R.string.access_level_adb
            HostAccess.ROOT -> R.string.access_level_root
        },
    )
    val updateUi by dependencies.updateManager.ui.collectAsStateWithLifecycle()
    SettingsScreen(
        nickname = ui.nickname,
        avatar = ui.avatar,
        providerName = ui.providerName,
        modelName = ui.modelLabel,
        configured = ui.modelConfig.providerId in ui.configuredProviderIds,
        themeLabel = ui.themeLabel,
        versionName = BuildConfig.VERSION_NAME,
        fontLabel = if (ui.fontStyle == DdFontStyle.SYSTEM) {
            stringResource(R.string.settings_font_system)
        } else {
            ui.fontStyle.displayName
        },
        onBack = onBack,
        onOpenTheme = { navigator.push(AppDestination.Theme) },
        onOpenChatSettings = { navigator.push(AppDestination.ChatSettings) },
        onOpenScan = { navigator.push(AppDestination.Scan) },
        onOpenProviders = { navigator.push(AppDestination.Providers) },
        onOpenAccess = { navigator.push(AppDestination.SystemAccess) },
        accessLabel = accessLabel,
        onOpenAgentSettings = { navigator.push(AppDestination.AgentSettings) },
        onOpenSuperSettings = { navigator.push(AppDestination.SuperSettings) },
        onOpenMemorySettings = { navigator.push(AppDestination.MemorySettings) },
        onOpenAndroidSettings = { navigator.push(AppDestination.AndroidSettings) },
        onOpenPersonalization = { navigator.push(AppDestination.Personalization) },
        onOpenFonts = { navigator.push(AppDestination.Fonts) },
        onOpenPrivacy = { navigator.push(AppDestination.Privacy) },
        archivedCount = archivedCount,
        onOpenArchived = { navigator.push(AppDestination.Archived) },
        onLanguageChange = {
            dependencies.pluginManager.refresh()
            dependencies.themeController.refresh()
        },
        updateVersion = updateUi.phase.releaseOrNull?.versionName,
        onOpenUpdate = { navigator.push(AppDestination.Update) },
        crashLogs = dependencies.crashLogs,
    )
}

@Composable
internal fun SoftwareUpdateRoute(dependencies: AppDependencies, onBack: () -> Unit) {
    val ui by dependencies.updateManager.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { dependencies.updateManager.onPageOpened() }
    SoftwareUpdateScreen(
        ui = ui,
        currentVersion = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onCheck = dependencies.updateManager::checkManually,
        onRetry = dependencies.updateManager::retry,
        onDownload = dependencies.updateManager::download,
        onInstall = dependencies.updateManager::install,
        onOpenInstallPermission = dependencies.updateManager::openInstallPermission,
        onResume = dependencies.updateManager::onScreenResumed,
    )
}

@Composable
internal fun SuperSettingsRoute(
    dependencies: AppDependencies,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val preferences by dependencies.appPreferences.preferences.collectAsStateWithLifecycle(
        initialValue = UiPreferences(),
    )
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val routerWorkspaceCwd = remember(dependencies.agentDir) {
        File(dependencies.agentDir, "super/router/workspace").absolutePath
    }
    SuperSettingsScreen(
        preferences = preferences,
        onMaxConcurrentWorkersChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperMaxConcurrentWorkers(value) }
        },
        onMaxSlotsPerBotChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperMaxSlotsPerBot(value) }
        },
        onStallTimeoutMinutesChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperStallTimeoutMinutes(value) }
        },
        onQuestionTimeoutMinutesChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperQuestionTimeoutMinutes(value) }
        },
        onWorkerJobWakeChange = { enabled ->
            scope.launch { dependencies.appPreferences.setSuperWorkerJobWake(enabled) }
        },
        onCoalesceWindowMsChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperCoalesceWindowMs(value) }
        },
        onReceiptMaxCharsChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperReceiptMaxChars(value) }
        },
        onIdleEvictMinutesChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperIdleEvictMinutes(value) }
        },
        onWorkerCompactionOverrideChange = { enabled ->
            scope.launch { dependencies.appPreferences.setSuperWorkerCompactionOverrideEnabled(enabled) }
        },
        onWorkerCompactionPercentChange = { value ->
            scope.launch { dependencies.appPreferences.setSuperWorkerCompactionPercent(value) }
        },
        onResetDefaults = {
            scope.launch { dependencies.appPreferences.resetSuperDefaults() }
        },
        onOpenInstructions = { showInstructions = true },
        onBack = onBack,
    )
    if (showInstructions) {
        AgentsDocSheet(
            cwd = routerWorkspaceCwd,
            agentDocStore = dependencies.agentDocStore,
            onSaved = {},
            onDismiss = { showInstructions = false },
            noteRes = R.string.super_agents_doc_note,
        )
    }
}

@Composable
internal fun MemorySettingsRoute(
    dependencies: AppDependencies,
    scope: CoroutineScope,
    configuredProviderIds: Set<String>,
    onBack: () -> Unit,
) {
    val settings by dependencies.memoryPreferences.settings.collectAsStateWithLifecycle(
        initialValue = MemorySettings(),
    )
    val modelConfig by dependencies.modelConfigStore.config.collectAsStateWithLifecycle(
        initialValue = io.github.whywood.superpi.runtime.ModelConfig(),
    )
    val storeRevision by dependencies.memory.changes.collectAsStateWithLifecycle()
    val memoryDirPath = remember(dependencies.agentDir) {
        File(dependencies.agentDir, "memory").apply { mkdirs() }.absolutePath
    }
    var showGuidanceSheet by rememberSaveable { mutableStateOf(false) }
    var guidanceNonce by rememberSaveable { mutableStateOf(0) }
    var guidanceSet by remember { mutableStateOf(false) }
    var pendingJobs by remember { mutableStateOf(0) }
    var retryingQueue by remember { mutableStateOf(false) }
    var autoHotLabel by remember { mutableStateOf<String?>(null) }
    var autoColdLabel by remember { mutableStateOf<String?>(null) }

    val catalogRevision by dependencies.providerCatalog.revision.collectAsStateWithLifecycle()
    val configuredProviders = remember(configuredProviderIds, catalogRevision) {
        dependencies.providerCatalog.summaries.filter { it.id in configuredProviderIds }
    }
    val modelsByProvider = remember(configuredProviders, catalogRevision) {
        configuredProviders.associate { summary ->
            summary.id to dependencies.providerCatalog.models(summary.id)
        }
    }

    LaunchedEffect(guidanceNonce) {
        guidanceSet = withContext(Dispatchers.IO) {
            runCatching { dependencies.agentDocStore.read(memoryDirPath) != null }.getOrDefault(false)
        }
    }

    LaunchedEffect(storeRevision, settings.mode) {
        pendingJobs = if (settings.mode == MemoryModelMode.PAUSED) {
            0
        } else {
            withContext(Dispatchers.IO) {
                runCatching { dependencies.memoryAgent.pendingDispatchCount() }.getOrDefault(0)
            }
        }
    }

    LaunchedEffect(settings, configuredProviderIds, modelConfig, catalogRevision) {
        val hot = dependencies.memoryModelRouter.resolve(MemoryModelTier.HOT)
        val cold = dependencies.memoryModelRouter.resolve(MemoryModelTier.COLD)
        autoHotLabel = hot?.let { resolved ->
            val pName = dependencies.providerCatalog.summary(resolved.providerId)?.name ?: resolved.providerId
            val mName = dependencies.providerCatalog.models(resolved.providerId)
                .firstOrNull { it.id == resolved.modelId }
                ?.displayName ?: resolved.modelId
            "$pName · $mName"
        }
        autoColdLabel = cold?.let { resolved ->
            val pName = dependencies.providerCatalog.summary(resolved.providerId)?.name ?: resolved.providerId
            val mName = dependencies.providerCatalog.models(resolved.providerId)
                .firstOrNull { it.id == resolved.modelId }
                ?.displayName ?: resolved.modelId
            "$pName · $mName"
        }
    }

    MemorySettingsScreen(
        settings = settings,
        configuredProviders = configuredProviders,
        modelsByProvider = modelsByProvider,
        autoHotModelLabel = autoHotLabel,
        autoColdModelLabel = autoColdLabel,
        guidanceSet = guidanceSet,
        pendingDispatchJobs = pendingJobs,
        retryingQueue = retryingQueue,
        onSaveSettings = { next ->
            scope.launch { dependencies.memoryPreferences.save(next) }
        },
        onOpenGuidance = { showGuidanceSheet = true },
        onRetryPendingJobs = {
            if (!retryingQueue) {
                retryingQueue = true
                scope.launch {
                    try {
                        dependencies.memoryAgent.retryPendingNow()
                    } finally {
                        retryingQueue = false
                    }
                }
            }
        },
        onBack = onBack,
    )

    if (showGuidanceSheet) {
        AgentsDocSheet(
            cwd = memoryDirPath,
            agentDocStore = dependencies.agentDocStore,
            onSaved = { guidanceNonce += 1 },
            onDismiss = { showGuidanceSheet = false },
            noteRes = R.string.memory_agents_doc_note,
        )
    }
}

@Composable
internal fun ChatSettingsRoute(dependencies: AppDependencies, scope: CoroutineScope, onBack: () -> Unit) {
    val preferences by dependencies.appPreferences.preferences.collectAsStateWithLifecycle(
        initialValue = UiPreferences(),
    )
    val voiceAutoSpeak by dependencies.voicePreferences.autoSpeak.collectAsStateWithLifecycle(
        initialValue = false,
    )
    ChatSettingsScreen(
        preferences = preferences,
        sendWhileRunning = preferences.sendWhileRunning,
        processDetail = preferences.processDetail,
        onBack = onBack,
        onToggle = { toggle, enabled -> scope.launch { dependencies.appPreferences.set(toggle, enabled) } },
        voiceAutoSpeak = voiceAutoSpeak,
        onVoiceAutoSpeakChange = { enabled -> scope.launch { dependencies.voicePreferences.setAutoSpeak(enabled) } },
        onChatFontScaleChange = { value -> scope.launch { dependencies.appPreferences.setChatFontScale(value) } },
        onSendWhileRunningChange = { value ->
            scope.launch { dependencies.appPreferences.setSendWhileRunning(value) }
        },
        onProcessDetailChange = { value ->
            scope.launch { dependencies.appPreferences.setProcessDetail(value) }
        },
    )
}

@Composable
internal fun AndroidSettingsRoute(dependencies: AppDependencies, scope: CoroutineScope, onBack: () -> Unit) {
    val mode by dependencies.overlayEntryPreferences.mode.collectAsStateWithLifecycle(
        initialValue = OverlayEntryMode.VOICE,
    )
    AndroidSettingsScreen(
        overlayEntryMode = mode,
        onOverlayEntryModeChange = { value -> scope.launch { dependencies.overlayEntryPreferences.setMode(value) } },
        onBack = onBack,
    )
}

@Composable
internal fun ThemeRoute(dependencies: AppDependencies, onBack: () -> Unit) {
    val state by dependencies.themeController.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    ThemeScreen(
        builtIn = dependencies.themeController.builtInOptions(systemDark),
        fromPlugins = dependencies.themeController.pluginOptions(),
        current = state.choice,
        appearance = state.appearance,
        onSelectAppearance = dependencies.themeController::setAppearance,
        onBack = onBack,
        onSelect = dependencies.themeController::setChoice,
    )
}

@Composable
internal fun FontsRoute(ui: ShellRouteUi, onBack: () -> Unit) {
    FontScreen(
        current = ui.fontStyle,
        onBack = onBack,
        onSelect = ui.onFontStyleChange,
    )
}

@Composable
internal fun PersonalizationRoute(dependencies: AppDependencies, scope: CoroutineScope, onBack: () -> Unit) {
    val value by dependencies.personalizationStore.personalization.collectAsStateWithLifecycle(
        initialValue = PersonalizationUi(),
    )
    PersonalizationScreen(
        value = value,
        onBack = onBack,
        onSave = { next -> scope.launch { dependencies.personalizationStore.save(next) } },
    )
}

@Composable
internal fun ScanSettingsRoute(onBack: () -> Unit) {
    ScanSettingsScreen(onBack = onBack)
}

@Composable
internal fun SystemAccessRoute(dependencies: AppDependencies, scope: CoroutineScope, onBack: () -> Unit) {
    val context = LocalContext.current
    val adbBridge = remember { AdbBridgeRuntime.get(context) }
    val hostState by dependencies.hostAccess.state.collectAsStateWithLifecycle()
    val adb by adbBridge.state.collectAsStateWithLifecycle()
    val rootfs by dependencies.rootfsBootstrap.status.collectAsStateWithLifecycle()
    val linuxState by dependencies.linuxEnvironment.state.collectAsStateWithLifecycle()
    val linuxEnabled by dependencies.linuxSettings.enabled.collectAsStateWithLifecycle(initialValue = false)
    val sharedStorageEnabled by dependencies.linuxSettings.sharedStorageEnabled.collectAsStateWithLifecycle(
        initialValue = false,
    )
    val devPackages by dependencies.devPackageManager.state.collectAsStateWithLifecycle()
    val devPackagesAvailable = remember(hostState.effective, linuxState, rootfs.state) {
        dependencies.hostEnvironmentProvider.current().capabilities.bashAvailable
    }
    val mounts by dependencies.storageMounts.mounts.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(Unit) { dependencies.linuxEnvironment.refreshState() }
    LaunchedEffect(hostState.effective, linuxState, rootfs.state) { dependencies.devPackageManager.refresh() }
    SystemAccessScreen(
        state = hostState,
        adb = adb,
        rootfs = rootfs,
        linuxState = linuxState,
        linuxSupported = dependencies.linuxEnvironment.isSupported,
        linuxEnabled = linuxEnabled,
        sharedStorageEnabled = sharedStorageEnabled,
        mounts = mounts,
        devPackages = devPackages,
        devPackagesAvailable = devPackagesAvailable,
        onSelect = { access -> scope.launch { dependencies.hostAccess.select(access) } },
        onRefresh = { scope.launch { dependencies.hostAccess.refresh() } },
        onPair = { dependencies.hostAccess.startAdbPairing() },
        onPairingCode = { code -> AgentService.applyPairingCode(context, code) },
        onConnect = { scope.launch { dependencies.hostAccess.connectAdb() } },
        onAdbModeChange = { mode -> scope.launch { adbBridge.selectMode(mode) } },
        onAdbConnectLocal = { host, port -> scope.launch { adbBridge.connectLocalAndStart(host, port) } },
        onOpenWirelessDebugging = { dependencies.hostAccess.openWirelessDebugging(context) },
        onRetryRootfs = { scope.launch { dependencies.rootfsBootstrap.ensureReady() } },
        onRebuildRootfs = { scope.launch { dependencies.rootfsBootstrap.rebuild() } },
        onLinuxInstall = { scope.launch { dependencies.linuxEnvironment.install() } },
        onLinuxReset = {
            scope.launch {
                dependencies.linuxSettings.setEnabled(false)
                dependencies.linuxEnvironment.reset()
                dependencies.host.reconfigure()
            }
        },
        onLinuxEnabledChange = { enabled ->
            scope.launch {
                dependencies.linuxSettings.setEnabled(enabled)
                dependencies.host.reconfigure()
            }
        },
        onSharedStorageChange = { enabled -> scope.launch { dependencies.linuxSettings.setSharedStorageEnabled(enabled) } },
        onSaveMount = { mount -> scope.launch { dependencies.storageMounts.save(mergeMount(mounts, mount)) } },
        onRemoveMount = { id -> scope.launch { dependencies.storageMounts.remove(id) } },
        onInstallBundle = dependencies.devPackageManager::installBundle,
        onRefreshPackages = dependencies.devPackageManager::refresh,
        onBack = onBack,
    )
}


private fun mergeMount(mounts: List<StorageMount>, mount: StorageMount): List<StorageMount> =
    mounts.filterNot { it.id == mount.id } + mount

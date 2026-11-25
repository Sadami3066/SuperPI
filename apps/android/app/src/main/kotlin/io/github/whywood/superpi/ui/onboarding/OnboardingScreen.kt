package io.github.whywood.superpi.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.BotAvatarStore
import io.github.whywood.superpi.data.DevPackageCatalog
import io.github.whywood.superpi.data.FloatingWindowPreferences
import io.github.whywood.superpi.data.FloatingWindowSettings
import io.github.whywood.superpi.data.LanguagePreferences
import io.github.whywood.superpi.data.OnboardingPreferences
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.data.PersonalizationStore
import io.github.whywood.superpi.data.ProfileAvatarStore
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.design.DdFontStyle
import io.github.whywood.superpi.design.DdFonts
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.DdWordmark
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.host.HostAccess
import io.github.whywood.superpi.shell.LinuxInstallState
import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ProviderAuthKind
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.ui.common.AvatarCropDialog
import io.github.whywood.superpi.ui.common.OAuthLoginSession
import io.github.whywood.superpi.ui.common.OAuthLoginSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STEP_WELCOME = 0
private const val STEP_ACCOUNT = 1
private const val STEP_BOT = 2
private const val STEP_APPEARANCE = 3
private const val STEP_PROVIDER = 4
private const val STEP_MODE = 5
private const val STEP_PERMISSIONS = 6
private const val STEP_ENVIRONMENT = 7
private const val STEP_ACCESS = 8
private const val LAST_STEP = STEP_ACCESS

@Composable
fun OnboardingScreen(
    graph: AppDependencies,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onFontStyleChange: (DdFontStyle) -> Unit = {},
) {
    val colors = superPiColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onboardingStore = remember(context) { OnboardingPreferences(context) }
    val avatarStore = remember(context) { ProfileAvatarStore(context) }
    val botAvatarStore = remember(context) { BotAvatarStore(context) }
    val floatingPrefs = remember(context) { FloatingWindowPreferences(context) }
    val languagePreferences = remember(context) { LanguagePreferences(context) }

    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    var nickname by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("") }
    var languageTag by rememberSaveable { mutableStateOf(languagePreferences.read().tag) }
    var avatarVersion by remember { mutableIntStateOf(0) }
    var botName by rememberSaveable { mutableStateOf(AgentRuntimeHost.HOME_NAME) }
    var botIntro by rememberSaveable { mutableStateOf("") }
    var botAvatarPath by rememberSaveable { mutableStateOf<String?>(null) }
    var botCropUri by rememberSaveable { mutableStateOf<String?>(null) }
    var providerId by rememberSaveable { mutableStateOf(ProviderCatalog.DEFAULT_PROVIDER_ID) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var keyState by rememberSaveable { mutableIntStateOf(KEY_STATE_IDLE) }
    var accessName by rememberSaveable { mutableStateOf(HostAccess.LOCAL.name) }
    var finishing by remember { mutableStateOf(false) }

    val language = AppLanguage.fromTag(languageTag)
    val access = HostAccess.entries.firstOrNull { it.name == accessName } ?: HostAccess.LOCAL
    val providers = remember(graph.providerCatalog) { graph.providerCatalog.summaries }
    val provider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    val deepseek = remember(providers) { providers.firstOrNull { it.id == ProviderCatalog.DEFAULT_PROVIDER_ID } }
    val linuxState by graph.linuxEnvironment.state.collectAsStateWithLifecycle()
    val packagesState by graph.devPackageManager.state.collectAsStateWithLifecycle()
    val themeState by graph.themeController.state.collectAsStateWithLifecycle()
    val uiPreferences by graph.appPreferences.preferences.collectAsStateWithLifecycle(initialValue = UiPreferences())
    val floatingSettings by floatingPrefs.settings.collectAsStateWithLifecycle(
        initialValue = FloatingWindowSettings(),
    )
    val overlayEntryMode by graph.overlayEntryPreferences.mode.collectAsStateWithLifecycle(
        initialValue = OverlayEntryMode.VOICE,
    )
    val systemDark = isSystemInDarkTheme()
    val builtInThemes = remember(systemDark, themeState.appearance) { graph.themeController.builtInOptions(systemDark) }
    val environmentBundles = remember { DevPackageCatalog.bundles }
    var environmentSelection by rememberSaveable { mutableIntStateOf(1) }
    var environmentScanned by rememberSaveable { mutableStateOf(false) }
    val selectedEnvironment = remember(environmentBundles, environmentSelection) {
        environmentBundles.filterIndexed { index, _ -> (environmentSelection shr index) and 1 == 1 }
    }
    val installedEnvironment = remember(environmentBundles, packagesState.installed) {
        environmentBundles
            .filter { bundle -> bundle.packageIds.isNotEmpty() && bundle.packageIds.all { it in packagesState.installed } }
            .mapTo(mutableSetOf()) { it.id }
    }
    val authKinds by graph.credentials.authKinds.collectAsStateWithLifecycle()
    val oauthSignedIn = provider?.let { authKinds[it.id] == ProviderAuthKind.OAUTH } == true
    var oauthSession by remember(providerId) { mutableStateOf<OAuthLoginSession?>(null) }
    var oauthBusy by remember(providerId) { mutableStateOf(false) }
    var oauthError by remember(providerId) { mutableStateOf<String?>(null) }
    var webSearchConfigured by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val oauthFailedText = stringResource(R.string.onboarding_provider_oauth_failed)
    val avatarFailedText = stringResource(R.string.onboarding_avatar_failed)

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) { avatarStore.save(uri) }
            if (saved) {
                avatarVersion += 1
            } else {
                Toast.makeText(context, avatarFailedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val botAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) botCropUri = uri.toString()
    }

    LaunchedEffect(step, linuxState) {
        if (step != STEP_ENVIRONMENT || environmentScanned) return@LaunchedEffect
        if (graph.linuxEnvironment.isSupported && linuxState !is LinuxInstallState.Installed) return@LaunchedEffect
        graph.devPackageManager.refresh()
        environmentScanned = true
    }

    LaunchedEffect(Unit) {
        if (keyState == KEY_STATE_SAVING) keyState = KEY_STATE_IDLE
        val existingProfile = graph.personalizationStore.read()
        if (nickname.isEmpty() && existingProfile.nickname.isNotEmpty()) {
            nickname = existingProfile.nickname
        }
        if (instruction.isEmpty() && existingProfile.instruction.isNotEmpty()) {
            instruction = existingProfile.instruction
        }
        val searchStore = graph.webSearchSettings
        webSearchConfigured = searchStore.readAll().keys.associateWith { true }
        WebSearchSettingsStore.providers.forEach { id ->
            launch {
                searchStore.hasKey(id).collect { present ->
                    webSearchConfigured = webSearchConfigured + (id to present)
                }
            }
        }
        graph.credentials.preload(WebSearchSettingsStore.DEEPSEEK)
        launch {
            graph.credentials.revision.collect {
                webSearchConfigured = webSearchConfigured + (
                    WebSearchSettingsStore.DEEPSEEK to
                        (graph.credentials.cachedApiKey(WebSearchSettingsStore.DEEPSEEK) != null)
                    )
            }
        }
    }

    fun back() {
        if (step > STEP_WELCOME) step -= 1
    }

    fun goNext() {
        if (step < LAST_STEP) step += 1
    }

    suspend fun persistProfile() {
        val trimmed = nickname.trim()
        val trimmedInstruction = instruction.trim().take(PersonalizationStore.MAX_INSTRUCTION_LENGTH)
        val existing = graph.personalizationStore.read()
        graph.personalizationStore.save(
            existing.copy(
                nickname = trimmed,
                instruction = trimmedInstruction,
            ),
        )
    }

    suspend fun persistBotIdentity() {
        val resolvedName = botName.trim().ifEmpty { AgentRuntimeHost.HOME_NAME }
        val resolvedAvatar = botAvatarPath?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedIntro = botIntro.trim().takeIf { it.isNotEmpty() }
        graph.host.renameWorkspace(AgentRuntimeHost.HOME_ID, resolvedName)
        graph.host.updateWorkspaceProfile(AgentRuntimeHost.HOME_ID, resolvedAvatar, resolvedIntro)
    }

    fun saveProfile() {
        scope.launch { persistProfile() }
    }

    fun saveBotIdentity() {
        scope.launch { persistBotIdentity() }
    }

    fun finish() {
        if (finishing) return
        finishing = true
        scope.launch {
            try {
                persistProfile()
                persistBotIdentity()
                onboardingStore.markCompleted()
                graph.pluginManager.refresh()
                onFinished()
            } finally {
                finishing = false
            }
        }
    }

    fun changeLanguage(next: AppLanguage) {
        if (next.tag == languageTag) return
        languageTag = next.tag
        languagePreferences.save(next)
        graph.pluginManager.refresh()
        scope.launch {
            try {
                persistProfile()
                persistBotIdentity()
            } finally {
                withContext(Dispatchers.Main) {
                    context.findActivity()?.recreate()
                }
            }
        }
    }

    fun selectAccess(value: HostAccess) {
        accessName = value.name
        scope.launch { graph.hostAccess.select(value) }
    }

    fun enqueueEnvironment() {
        val pending = selectedEnvironment.filter { it.id !in installedEnvironment }
        if (pending.isEmpty()) return
        OnboardingEnvironmentInstaller.enqueue(graph.devPackageManager, graph.linuxEnvironment, pending)
    }

    fun saveKeyThenAdvance() {
        val key = apiKey.trim()
        if (key.isEmpty() || provider?.supportsApiKey != true) {
            goNext()
            return
        }
        keyState = KEY_STATE_SAVING
        scope.launch {
            val result = runCatching {
                graph.credentials.writeApiKey(providerId, key, null)
                graph.credentials.awaitCommit(providerId)
            }
            keyState = if (result.isSuccess) KEY_STATE_SAVED else KEY_STATE_FAILED
            if (result.isSuccess) {
                apiKey = ""
                graph.modelConfigStore.save(
                    graph.providerCatalog.resolveConfig(ModelConfig(providerId = providerId, modelId = "")),
                )
                if (step == STEP_PROVIDER) step = STEP_MODE
            }
        }
    }

    fun beginOAuthLogin() {
        val target = provider ?: return
        if (oauthSession != null) return
        oauthError = null
        val session = OAuthLoginSession(
            providerName = target.name,
            onOpenUrl = { url ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                }
            },
        )
        oauthSession = session
        oauthBusy = true
        scope.launch {
            val result = graph.providerOAuthService.login(target.id, session)
            oauthBusy = false
            if (oauthSession !== session) return@launch
            if (result.isSuccess) {
                graph.modelConfigStore.save(
                    graph.providerCatalog.resolveConfig(ModelConfig(providerId = target.id, modelId = "")),
                )
                session.succeed()
                oauthSession = null
            } else {
                val detail = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                val message = if (detail == null) oauthFailedText else "$oauthFailedText: $detail"
                session.fail(message)
                oauthError = message
            }
        }
    }

    fun signOutOAuth() {
        val target = provider ?: return
        oauthError = null
        scope.launch {
            runCatching { graph.providerOAuthService.logout(target.id) }
        }
    }

    fun primaryAction() {
        when (step) {
            STEP_WELCOME -> goNext()
            STEP_ACCOUNT -> {
                saveProfile()
                goNext()
            }

            STEP_BOT -> {
                saveBotIdentity()
                goNext()
            }

            STEP_PROVIDER -> saveKeyThenAdvance()
            STEP_ENVIRONMENT -> {
                enqueueEnvironment()
                goNext()
            }

            STEP_ACCESS -> finish()
            else -> goNext()
        }
    }

    fun secondaryAction() {
        if (step == STEP_BOT) {
            botName = AgentRuntimeHost.HOME_NAME
            botIntro = ""
            botAvatarPath?.let { path ->
                botAvatarStore.delete(path)
                botAvatarPath = null
            }
            scope.launch {
                graph.host.renameWorkspace(AgentRuntimeHost.HOME_ID, AgentRuntimeHost.HOME_NAME)
                graph.host.updateWorkspaceProfile(AgentRuntimeHost.HOME_ID, null, null)
            }
        }
        goNext()
    }

    val motionEnabled = LocalDdMotionEnabled.current
    val primaryLabel = when (step) {
        STEP_WELCOME -> stringResource(R.string.onboarding_primary_start)
        STEP_PROVIDER -> stringResource(R.string.onboarding_primary_connect)
        STEP_ACCESS -> stringResource(R.string.onboarding_primary_done)
        else -> stringResource(R.string.onboarding_primary_continue)
    }
    val hint = when (step) {
        STEP_WELCOME -> stringResource(R.string.onboarding_welcome_note)
        STEP_ACCESS -> stringResource(R.string.onboarding_access_note)
        else -> null
    }
    val secondaryLabel = when (step) {
        STEP_PROVIDER -> stringResource(R.string.onboarding_skip)
        STEP_BOT, STEP_APPEARANCE, STEP_MODE, STEP_PERMISSIONS, STEP_ENVIRONMENT ->
            stringResource(R.string.onboarding_later)
        else -> null
    }
    val savingKey = step == STEP_PROVIDER && keyState == KEY_STATE_SAVING
    val primaryEnabled = !savingKey && !finishing
    val secondaryEnabled = !savingKey && !finishing

    BackHandler(onBack = ::back)

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("onboarding_root"),
    ) {
        OnboardingHeader(step = step, onBack = ::back)
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (!motionEnabled) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState > initialState
                    val direction = if (forward) 1 else -1
                    val enter = slideInHorizontally(
                        animationSpec = tween(DdMotion.SCREEN, easing = DdMotion.EaseOut),
                    ) { width -> direction * width / 8 } +
                        fadeIn(tween(DdMotion.SCREEN, easing = DdMotion.EaseOut))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(DdMotion.SCREEN, easing = DdMotion.EaseInOut),
                    ) { width -> -direction * width / 8 } +
                        fadeOut(tween(DdMotion.FAST, easing = DdMotion.EaseOut))
                    enter togetherWith exit
                }
            },
            label = "onboarding-step",
        ) { target ->
            when (target) {
                STEP_WELCOME -> WelcomeStep()
                STEP_ACCOUNT -> AccountStep(
                    nickname = nickname,
                    onNicknameChange = { nickname = it },
                    instruction = instruction,
                    onInstructionChange = { instruction = it },
                    language = language,
                    onLanguageChange = ::changeLanguage,
                    avatarVersion = avatarVersion,
                    loadAvatar = avatarStore::load,
                    onPickAvatar = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )

                STEP_BOT -> BotStep(
                    botName = botName,
                    onBotNameChange = { botName = it },
                    botIntro = botIntro,
                    onBotIntroChange = { botIntro = it },
                    botAvatarPath = botAvatarPath,
                    onPickAvatar = {
                        botAvatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onClearAvatar = {
                        botAvatarPath?.let(botAvatarStore::delete)
                        botAvatarPath = null
                    },
                )

                STEP_APPEARANCE -> AppearanceStep(
                    builtInThemes = builtInThemes,
                    selectedTheme = themeState.choice,
                    appearance = themeState.appearance,
                    fontStyle = DdFonts.style,
                    onSelectAppearance = graph.themeController::setAppearance,
                    onSelectTheme = graph.themeController::setChoice,
                    onSelectFontStyle = onFontStyleChange,
                )

                STEP_PROVIDER -> ProviderStep(
                    provider = provider,
                    deepseek = deepseek,
                    providers = providers,
                    apiKey = apiKey,
                    onApiKeyChange = { value ->
                        apiKey = value
                        if (keyState != KEY_STATE_SAVING) keyState = KEY_STATE_IDLE
                    },
                    keyState = keyState,
                    oauthSignedIn = oauthSignedIn,
                    oauthBusy = oauthBusy,
                    oauthError = oauthError,
                    onPickDeepSeek = {
                        providerId = ProviderCatalog.DEFAULT_PROVIDER_ID
                        apiKey = ""
                        keyState = KEY_STATE_IDLE
                    },
                    onSelectProvider = { picked ->
                        providerId = picked.id
                        apiKey = ""
                        keyState = KEY_STATE_IDLE
                    },
                    onStartOAuth = ::beginOAuthLogin,
                    onSignOutOAuth = ::signOutOAuth,
                    webSearchConfigured = webSearchConfigured,
                    onSaveWebSearch = { id, key ->
                        scope.launch {
                            if (id == WebSearchSettingsStore.DEEPSEEK) {
                                graph.credentials.writeApiKey(id, key, null)
                                graph.credentials.awaitCommit(id)
                            } else {
                                graph.webSearchSettings.save(id, key)
                            }
                            webSearchConfigured = webSearchConfigured + (id to true)
                        }
                    },
                    onClearWebSearch = { id ->
                        scope.launch {
                            if (id == WebSearchSettingsStore.DEEPSEEK) {
                                graph.credentials.delete(id)
                                graph.credentials.awaitCommit(id)
                            } else {
                                graph.webSearchSettings.save(id, null)
                            }
                            webSearchConfigured = webSearchConfigured + (id to false)
                        }
                    },
                )

                STEP_MODE -> ModeStep(
                    appMode = uiPreferences.appMode,
                    processDetail = uiPreferences.processDetail,
                    sendWhileRunning = uiPreferences.sendWhileRunning,
                    chatFontScale = uiPreferences.chatFontScale,
                    onSelectAppMode = { mode -> scope.launch { graph.appPreferences.setAppMode(mode) } },
                    onSelectProcessDetail = { detail -> scope.launch { graph.appPreferences.setProcessDetail(detail) } },
                    onSelectSendWhileRunning = { mode ->
                        scope.launch { graph.appPreferences.setSendWhileRunning(mode) }
                    },
                    onChatFontScaleChange = { scale ->
                        scope.launch { graph.appPreferences.setChatFontScale(scale) }
                    },
                )

                STEP_PERMISSIONS -> PermissionsStep(
                    floatingEnabled = floatingSettings.enabled,
                    overlayEntryMode = overlayEntryMode,
                    onToggleFloating = { enabled ->
                        scope.launch { floatingPrefs.setEnabled(enabled) }
                    },
                    onOverlayEntryModeChange = { mode ->
                        scope.launch { graph.overlayEntryPreferences.setMode(mode) }
                    },
                    onSharedStorageGranted = {
                        scope.launch { graph.linuxSettings.setSharedStorageEnabled(true) }
                    },
                )

                STEP_ENVIRONMENT -> EnvironmentStep(
                    bundles = environmentBundles,
                    installedIds = installedEnvironment,
                    selectedIds = selectedEnvironment.mapTo(mutableSetOf()) { it.id },
                    onToggle = { id ->
                        val index = environmentBundles.indexOfFirst { it.id == id }
                        if (index >= 0) environmentSelection = environmentSelection xor (1 shl index)
                    },
                )

                else -> AccessStep(selected = access, onSelect = ::selectAccess)
            }
        }
        OnboardingFooter(
            primaryLabel = primaryLabel,
            primaryEnabled = primaryEnabled,
            primaryTestTag = if (step == STEP_ACCESS) "onboarding_access_done" else "onboarding_primary",
            onPrimary = ::primaryAction,
            hint = hint,
            secondaryLabel = secondaryLabel,
            secondaryEnabled = secondaryEnabled,
            onSecondary = ::secondaryAction,
        )
    }

    botCropUri?.let { raw ->
        AvatarCropDialog(
            source = Uri.parse(raw),
            onDismiss = { botCropUri = null },
            onCropped = { cropped ->
                botCropUri = null
                scope.launch {
                    val saved = try {
                        botAvatarStore.save(cropped)
                    } finally {
                        if (!cropped.isRecycled) cropped.recycle()
                    }
                    if (saved != null) {
                        val previous = botAvatarPath
                        botAvatarPath = saved
                        if (previous != null && previous != saved) {
                            botAvatarStore.delete(previous)
                        }
                    } else {
                        Toast.makeText(context, avatarFailedText, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    oauthSession?.let { session ->
        OAuthLoginSheet(
            providerName = provider?.name.orEmpty(),
            session = session,
            onDismiss = {
                session.cancel()
                oauthBusy = false
                if (oauthSession === session) oauthSession = null
            },
        )
    }
}

@Composable
private fun OnboardingFooter(
    primaryLabel: String,
    primaryEnabled: Boolean,
    primaryTestTag: String,
    onPrimary: () -> Unit,
    hint: String?,
    secondaryLabel: String?,
    secondaryEnabled: Boolean,
    onSecondary: () -> Unit,
) {
    val colors = superPiColors()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Xxl)
            .padding(top = 6.dp, bottom = 20.dp),
    ) {
        DdPrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.fillMaxWidth().testTag(primaryTestTag),
        )
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            if (hint != null) {
                Text(
                    text = hint,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                )
            } else if (secondaryLabel != null) {
                DdTextAction(
                    text = secondaryLabel,
                    onClick = onSecondary,
                    color = colors.inkTertiary,
                    enabled = secondaryEnabled,
                )
            }
        }
    }
}

@Composable
fun OnboardingSplash(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    Box(
        modifier.fillMaxSize().background(colors.paper),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(if (motionEnabled) tween(DdMotion.ENTER, easing = DdMotion.EaseOut) else snap()),
        ) {
            DdWordmark()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

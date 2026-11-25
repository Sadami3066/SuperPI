package io.github.whywood.superpi.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.SuperPIApplication
import io.github.whywood.superpi.MainActivity
import io.github.whywood.superpi.R
import io.github.whywood.superpi.conversation.ConversationState
import io.github.whywood.superpi.core.AssistantEntry
import io.github.whywood.superpi.service.Diagnostics
import io.github.whywood.superpi.core.resolveAttachmentRoot
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.LanguagePreferences
import io.github.whywood.superpi.data.OverlayEntryMode
import io.github.whywood.superpi.data.UiPreferences
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.service.AgentService
import io.github.whywood.superpi.theme.HostTheme
import io.github.whywood.superpi.service.AppVisibility
import io.github.whywood.superpi.service.NotificationCenter
import io.github.whywood.superpi.ui.common.chat.AttachmentSource
import io.github.whywood.superpi.ui.common.chat.AttachmentSourceSheet
import io.github.whywood.superpi.ui.common.chat.CameraCaptureSheet
import io.github.whywood.superpi.ui.common.chat.rememberAttachmentPickers
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.common.ProvideHostFiles
import io.github.whywood.superpi.ui.common.chat.ProvidePluginManager
import io.github.whywood.superpi.ui.shell.rememberShellConversationStrings
import io.github.whywood.superpi.ui.superagent.SuperChatViewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch



class AssistantOverlayActivity : ComponentActivity() {
    private val graph by lazy { (application as SuperPIApplication).graph }
    private val draftStore by lazy {
        OverlayDraftStore(PreferencesOverlayDraftPersistence(this))
    }
    private var controller: AssistantOverlayController? = null
    private var requestedSessionId by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AgentService.start(this)
        requestedSessionId = savedInstanceState?.getString(STATE_SESSION_ID)
            ?: intent.requestedSessionId()
        requestedSessionId?.let { graph.activeSessionId.value = it }
        val language = LanguagePreferences(this).read()
        setContent {
            val chatUiStrings = rememberShellConversationStrings()
            val scope = rememberCoroutineScope()
            val uiPreferences by graph.appPreferences.preferences.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val autoSpeak by graph.voicePreferences.autoSpeak.collectAsStateWithLifecycle(
                initialValue = false,
            )
            val overlayEntryMode by graph.overlayEntryPreferences.mode.collectAsStateWithLifecycle(
                initialValue = OverlayEntryMode.VOICE,
            )
            val prefs = uiPreferences
            val isSuperOverlay = prefs?.appMode == AppMode.SUPER &&
                (requestedSessionId == null ||
                    requestedSessionId == SuperRuntime.SUPER_THREAD_SESSION_ID ||
                    requestedSessionId?.startsWith(NotificationCenter.WORKER_TARGET_PREFIX) == true)
            val controller = remember {
                AssistantOverlayController(
                    runtime = graph.host,
                    store = graph.conversationStore,
                    activeSessionId = { graph.activeSessionId.value },
                    homeWorkspaceId = AgentRuntimeHost.HOME_ID,
                    scope = graph.appScope,
                    providers = graph.providerCatalog,
                    attachmentStore = graph.attachmentStore,
                    homeDir = graph.homeDir,
                    configuredProviders = { graph.configuredProviderIds() },
                    defaultModel = { graph.modelConfigStore.read() },
                    onSessionSelected = { sessionId -> graph.activeSessionId.value = sessionId },
                    initialSessionId = { requestedSessionId },
                )
            }
            this.controller = controller
            LaunchedEffect(controller, prefs != null, isSuperOverlay) {
                if (prefs == null) return@LaunchedEffect
                try {
                    graph.processRuntime.start()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Diagnostics.log(this@AssistantOverlayActivity, "overlay: process start failed", error)
                }
                if (!isSuperOverlay) {
                    controller.start()
                }
            }
            val superDir = remember { File(graph.agentDir, "super") }
            val superRouterWorkspace = remember(superDir) { File(superDir, "router/workspace") }
            val superChatViewModel = remember(superDir, superRouterWorkspace, scope) {
                SuperChatViewModel(
                    superRuntime = graph.superRuntime,
                    providers = graph.providerCatalog,
                    configuredProviderIds = graph::configuredProviderIds,
                    attachmentStore = graph.attachmentStore,
                    superDir = superDir,
                    routerWorkspaceDir = superRouterWorkspace,
                    scope = scope,
                    strings = chatUiStrings,
                )
            }
            LaunchedEffect(chatUiStrings) {
                superChatViewModel.updateStrings(chatUiStrings)
            }
            LaunchedEffect(prefs != null, isSuperOverlay) {
                if (prefs != null && isSuperOverlay) {
                    try {
                        graph.superRuntime.ensureStarted()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Diagnostics.log(this@AssistantOverlayActivity, "overlay: super start failed", error)
                    }
                }
            }
            val normalState by controller.state.collectAsStateWithLifecycle()
            val superUiState by superChatViewModel.state.collectAsStateWithLifecycle()
            val superTitle = NotificationCenter.SUPER_DISPLAY_TITLE
            val state = remember(isSuperOverlay, normalState, superUiState, superTitle, superRouterWorkspace) {
                if (!isSuperOverlay) {
                    normalState
                } else {
                    val thread = superUiState.thread
                    val toolName = thread.activeToolText?.takeIf { it.isNotBlank() }
                    val activity = when {
                        !thread.busy -> null
                        toolName != null -> RuntimeActivity.RunningTool(toolName)
                        else -> RuntimeActivity.Thinking
                    }
                    val ws = RuntimeWorkspace(
                        id = SuperRuntime.SUPER_THREAD_SESSION_ID,
                        name = superTitle,
                        cwd = superRouterWorkspace.absolutePath,
                    )
                    val targetSessionId = requestedSessionId?.takeIf {
                        it.startsWith(NotificationCenter.WORKER_TARGET_PREFIX)
                    } ?: SuperRuntime.SUPER_THREAD_SESSION_ID
                    val overlayError = when {
                        !thread.ready && thread.error != null -> AssistantOverlayError.StartFailed
                        superUiState.error != null -> AssistantOverlayError.SendFailed
                        else -> null
                    }
                    AssistantOverlayState(
                        conversation = ConversationState(
                            messages = superUiState.messages,
                            loaded = thread.ready,
                            sending = thread.busy,
                            activity = activity,
                        ),
                        sessionId = targetSessionId,
                        sessionTitle = superTitle,
                        conversationCwd = superRouterWorkspace.absolutePath,
                        workspaceId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                        workspaces = listOf(ws),
                        allSessions = emptyList(),
                        providers = superChatViewModel.configuredProviders(),
                        providerId = thread.providerId,
                        modelId = thread.modelId,
                        modelLabel = thread.modelLabel,
                        models = superUiState.models,
                        thinkingLevel = thread.thinkingLevel,
                        thinkingLevels = superUiState.thinkingLevels,
                        attachments = superUiState.draft.attachments,
                        importingAttachment = superUiState.draft.importingAttachment,
                        error = overlayError,
                    )
                }
            }
            val pendingQuestions by graph.questionCenter.pending.collectAsStateWithLifecycle()
            val sessionQuestions = remember(isSuperOverlay, pendingQuestions, state.sessionId, superUiState) {
                if (!isSuperOverlay) {
                    pendingQuestions.filter { it.sessionId == state.sessionId }
                } else {
                    val superQ = superUiState.thread.pendingQuestion?.copy(
                        sessionId = SuperRuntime.SUPER_THREAD_SESSION_ID,
                    )
                    val workerQs = superUiState.pendingWorkerApprovals.map { (worker, q) ->
                        q.copy(sessionId = NotificationCenter.workerSessionTarget(worker.workerId))
                    }
                    listOfNotNull(superQ) + workerQs
                }
            }
            val context = LocalContext.current
            val missingFileMessage = stringResource(R.string.assistant_attach_missing)
            val shareFailedMessage = stringResource(R.string.present_share_failed)
            val shareTitle = stringResource(R.string.assistant_attach_share)
            var preview by remember { mutableStateOf<AssistantOverlayPreview?>(null) }
            var attachSheetOpen by remember { mutableStateOf(false) }
            var cameraSheetOpen by remember { mutableStateOf(false) }
            val attachmentPickers = rememberAttachmentPickers { uri ->
                if (isSuperOverlay) superChatViewModel.attach(uri) else controller.attach(uri)
            }
            val attachmentRoot = remember(isSuperOverlay, state.conversationCwd, superRouterWorkspace) {
                if (isSuperOverlay) {
                    superRouterWorkspace
                } else {
                    resolveAttachmentRoot(state.conversationCwd, graph.homeDir)
                }
            }

            val openFile: (String) -> Unit = { raw ->
                scope.launch {
                    if (isSuperOverlay) {
                        val resolved = superChatViewModel.resolveFile(raw)
                        if (resolved == null) {
                            Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            preview = AssistantOverlayPreview(
                                attachment = resolved.first,
                                file = resolved.second,
                            )
                        }
                    } else {
                        val resolved = graph.attachmentStore.resolve(raw, attachmentRoot)
                        if (resolved == null) {
                            Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            preview = AssistantOverlayPreview(
                                attachment = resolved,
                                file = graph.attachmentStore.file(resolved, attachmentRoot),
                            )
                        }
                    }
                }
            }
            val shareFile: (String) -> Unit = { raw ->
                scope.launch {
                    if (isSuperOverlay) {
                        val resolved = superChatViewModel.resolveFile(raw)
                        if (resolved == null) {
                            Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = graph.attachmentStore.shareIntent(resolved.first, resolved.second.parentFile ?: attachmentRoot)
                            if (intent != null) {
                                context.startActivity(shareChooser(context, intent, shareTitle))
                            } else {
                                Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        val resolved = graph.attachmentStore.resolve(raw, attachmentRoot)
                        if (resolved == null) {
                            Toast.makeText(context, missingFileMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            val intent = graph.attachmentStore.shareIntent(resolved, attachmentRoot)
                            if (intent != null) {
                                context.startActivity(shareChooser(context, intent, shareTitle))
                            } else {
                                Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            HostTheme(controller = graph.themeController) {
                if (prefs != null) {
                    ProvidePluginManager(graph.pluginManager) {
                        ProvideHostFiles(graph.hostFiles) {
                    AssistantOverlayScreen(
                        state = state,
                        pendingQuestions = sessionQuestions,
                        preview = preview,
                        draftStore = draftStore,
                        config = AssistantOverlayConfig(
                            autoSpeak = autoSpeak,
                            entryMode = overlayEntryMode,
                            processDetail = prefs.processDetail,
                            chatFontScale = prefs.chatFontScale,
                            language = language,
                            attachmentRoot = attachmentRoot,
                            switcherVisible = !isSuperOverlay,
                        ),
                    actions = AssistantOverlayActions(
                        onDismiss = { finish() },
                        onOpenInApp = ::openInApp,
                        onSend = { text, followUp ->
                            if (isSuperOverlay) {
                                superChatViewModel.sendDirect(text)
                            } else {
                                controller.send(text, followUp)
                            }
                        },
                        onStop = {
                            if (isSuperOverlay) superChatViewModel.stop() else controller.stop()
                        },
                        onRetry = { msgId ->
                            if (isSuperOverlay) superChatViewModel.retryMessage(msgId) else controller.retry(msgId)
                        },
                        onToggleStep = { msgId, stepId ->
                            if (isSuperOverlay) superChatViewModel.toggleStep(stepId) else controller.toggleStep(msgId, stepId)
                        },
                        onAnswerQuestion = { pending, answers ->
                            when {
                                pending.sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID -> {
                                    superChatViewModel.answerSuperQuestion(pending.requestId, answers)
                                }

                                pending.sessionId.startsWith(NotificationCenter.WORKER_TARGET_PREFIX) -> {
                                    val workerId = pending.sessionId.removePrefix(NotificationCenter.WORKER_TARGET_PREFIX)
                                    superChatViewModel.answerWorkerQuestion(workerId, pending.requestId, answers)
                                }

                                else -> graph.questionCenter.answer(pending.requestId, answers)
                            }
                        },
                        onOpenPreview = { attachment ->
                            preview = AssistantOverlayPreview(
                                attachment = attachment,
                                file = graph.attachmentStore.file(attachment, attachmentRoot),
                            )
                        },
                        onPickAttachment = { attachSheetOpen = true },
                        onOpenCamera = { cameraSheetOpen = true },
                        onRemoveAttachment = { id ->
                            if (isSuperOverlay) superChatViewModel.removeAttachment(id) else controller.removeAttachment(id)
                        },
                        onOpenFile = openFile,
                        onShareFile = shareFile,
                        onOpenMiniapp = { state.sessionId?.let(::openInApp) },
                        onOpenSession = controller::openSession,
                        onNewSessionInWorkspace = { workspaceId -> controller.newSession(workspaceId) },
                        onRefreshLists = controller::refreshLists,
                        onSelectProvider = { providerId ->
                            if (isSuperOverlay) superChatViewModel.setProvider(providerId) else controller.selectProvider(providerId)
                        },
                        onSelectModel = { modelId ->
                            if (isSuperOverlay) superChatViewModel.setModel(modelId) else controller.selectModel(modelId)
                        },
                        onSelectThinking = { level ->
                            if (isSuperOverlay) superChatViewModel.setThinkingLevel(level) else controller.selectThinking(level)
                        },
                        onClearQueue = controller::clearQueue,
                        onRemoveQueued = controller::removeQueued,
                        onSendQueued = controller::sendQueued,
                        onRetryStart = {
                            if (isSuperOverlay) superChatViewModel.retryLastError() else controller.start()
                        },
                        onDismissPreview = { preview = null },
                        onSharePreview = {
                            scope.launch {
                                preview?.let { current ->
                                    val intent = graph.attachmentStore.shareIntent(current.attachment, current.file.parentFile ?: attachmentRoot)
                                    if (intent != null) {
                                        context.startActivity(shareChooser(context, intent, shareTitle))
                                    } else {
                                        Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    ),
                )
                if (attachSheetOpen) {
                    AttachmentSourceSheet(
                        onDismiss = { attachSheetOpen = false },
                        onPick = { source ->
                            attachSheetOpen = false
                            when (source) {
                                AttachmentSource.IMAGE -> attachmentPickers.pickImages()
                                AttachmentSource.FILE -> attachmentPickers.pickFiles()
                                AttachmentSource.CAMERA -> cameraSheetOpen = true
                            }
                        },
                    )
                }
                if (cameraSheetOpen) {
                    CameraCaptureSheet(
                        onDismiss = { cameraSheetOpen = false },
                        onCaptured = { uri ->
                            cameraSheetOpen = false
                            if (isSuperOverlay) superChatViewModel.attach(uri) else controller.attach(uri)
                        },
                    )
                }
                }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sessionId = intent.requestedSessionId() ?: return
        requestedSessionId = sessionId
        graph.activeSessionId.value = sessionId
        val isSuperTarget = sessionId == SuperRuntime.SUPER_THREAD_SESSION_ID ||
            sessionId.startsWith(NotificationCenter.WORKER_TARGET_PREFIX)
        if (!isSuperTarget) {
            controller?.openSession(sessionId)
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.activityStarted()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val superSession = requestedSessionId?.takeIf {
            it == SuperRuntime.SUPER_THREAD_SESSION_ID ||
                it.startsWith(NotificationCenter.WORKER_TARGET_PREFIX)
        }
        val toSave = superSession ?: controller?.state?.value?.sessionId
        toSave?.let { outState.putString(STATE_SESSION_ID, it) }
    }

    override fun onStop() {
        AppVisibility.activityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        controller?.reset()
        controller = null
        super.onDestroy()
    }

    private fun openInApp(sessionId: String?) {
        val target = sessionId ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(NotificationCenter.EXTRA_SESSION_ID, target),
        )
        finish()
    }

    private companion object {
        const val STATE_SESSION_ID = "io.github.whywood.superpi.extra.STATE_SESSION_ID"
    }
}

private fun Intent.requestedSessionId(): String? =
    getStringExtra(AssistantEntry.EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }

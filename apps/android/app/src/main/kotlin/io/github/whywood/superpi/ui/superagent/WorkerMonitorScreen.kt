package io.github.whywood.superpi.ui.superagent

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.AttachmentStore
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.ui.common.chat.ContextMeter
import io.github.whywood.superpi.ui.common.chat.UsageSheet
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.chat.AskQuestionCard
import io.github.whywood.superpi.ui.common.chat.ChatComposer
import io.github.whywood.superpi.ui.common.chat.ChatComposerActions
import io.github.whywood.superpi.ui.common.chat.ChatComposerState
import io.github.whywood.superpi.ui.common.chat.ChatErrorNoticeBar
import io.github.whywood.superpi.ui.common.chat.ChatScrollFollowEffect
import io.github.whywood.superpi.ui.common.chat.LoadEarlierPill
import io.github.whywood.superpi.ui.common.chat.rememberChatHistoryWindow
import io.github.whywood.superpi.ui.common.chat.ChatTurn
import io.github.whywood.superpi.ui.common.chat.RemotePresentContext
import io.github.whywood.superpi.ui.common.chat.TodoPanel
import io.github.whywood.superpi.ui.common.chat.copyToClipboard
import io.github.whywood.superpi.ui.common.chat.latestTodoPayload
import io.github.whywood.superpi.ui.common.chat.rememberAttachmentPickers
import io.github.whywood.superpi.ui.common.chat.rememberChatScrollController
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun WorkerMonitorScreen(
    workerId: String,
    viewModel: WorkerChatViewModel,
    attachmentStore: AttachmentStore,
    superDir: File,
    configuredProviders: List<ProviderSummary>,
    processDetail: ProcessDetail,
    chatFontScale: Float,
    voiceLanguage: AppLanguage,
    onBack: () -> Unit,
    onManageProviders: () -> Unit,
    onOpenMiniapp: (String) -> Unit,
    onOpenPluginPage: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    remotePresentContext: RemotePresentContext? = null,
    compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val worker = ui.worker
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scrollController = rememberChatScrollController(listState)
    val density = LocalDensity.current
    val chatDensity = remember(density, chatFontScale) {
        Density(density = density.density, fontScale = density.fontScale * chatFontScale)
    }

    val imeInsets = WindowInsets.ime
    val insetsState = rememberSuperWindowInsets()

    var overlayHeightPx by remember { mutableIntStateOf(0) }
    var deleteConfirmOpen by remember(workerId) { mutableStateOf(false) }
    var usageSheetOpen by remember { mutableStateOf(false) }
    var pendingLocalSend by remember { mutableStateOf(false) }
    var attachmentSheetOpen by remember { mutableStateOf(false) }
    var cameraSheetOpen by remember { mutableStateOf(false) }
    var previewTarget by remember { mutableStateOf<Pair<AttachmentRef, File>?>(null) }

    val attachmentPickers = rememberAttachmentPickers { uri -> viewModel.attach(uri) }
    val copiedMessage = stringResource(R.string.assistant_copied)
    val missingMessage = stringResource(R.string.assistant_attach_missing)
    val openFailedMessage = stringResource(R.string.present_open_failed)
    val shareFailedMessage = stringResource(R.string.present_share_failed)
    val shareChooserTitle = stringResource(R.string.assistant_attach_share)
    val exportDone = stringResource(R.string.files_export_done)
    val exportDonePathFormat = stringResource(R.string.files_export_done_path)

    fun copyText(text: String) {
        if (copyToClipboard(context, text)) {
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun openFileRef(raw: String) {
        scope.launch {
            handleOpenSuperFileRef(
                context = context,
                resolveFile = viewModel::resolveFile,
                raw = raw,
                attachmentStore = attachmentStore,
                superDir = superDir,
                attachmentRoot = ui.attachmentRoot,
                missingMessage = missingMessage,
                openFailedMessage = openFailedMessage,
                onPreview = { previewTarget = it },
            )
        }
    }

    fun shareFileRef(raw: String) {
        scope.launch {
            handleShareSuperFileRef(
                context = context,
                resolveFile = viewModel::resolveFile,
                raw = raw,
                attachmentStore = attachmentStore,
                superDir = superDir,
                attachmentRoot = ui.attachmentRoot,
                missingMessage = missingMessage,
                shareTitle = shareChooserTitle,
                shareFailedMessage = shareFailedMessage,
                exportDone = exportDone,
                exportDonePathFormat = exportDonePathFormat,
            )
        }
    }

    var knownMessageCount by remember(workerId) { mutableIntStateOf(0) }
    LaunchedEffect(workerId, ui.messages.size, pendingLocalSend) {
        if (ui.messages.isNotEmpty() && (scrollController.following || pendingLocalSend)) {
            val shouldAnimate = motionEnabled && (knownMessageCount > 0 || pendingLocalSend)
            scrollController.jumpToBottom(animate = shouldAnimate)
            pendingLocalSend = false
        }
        knownMessageCount = ui.messages.size
    }

    val todoPayload = remember(ui.messages) { latestTodoPayload(ui.messages) }
    val isSending = (worker?.status?.isBusyForCommands == true) || ui.session.streaming || ui.stopping
    ChatScrollFollowEffect(
        controller = scrollController,
        sessionKey = "worker-$workerId",
        sending = isSending,
        smooth = motionEnabled,
    )

    LaunchedEffect(scrollController, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) to overlayHeightPx }.collect {
            scrollController.anchorToBottom()
        }
    }

    LaunchedEffect(worker?.pendingQuestion?.requestId) {
        if (worker?.pendingQuestion != null) {
            delay(60)
            scrollController.jumpToBottom(animate = true)
        }
    }

    val sessionConfigured = remember(ui.session.providerId, configuredProviders) {
        ui.session.providerId.isNotBlank() &&
            configuredProviders.any { it.id == ui.session.providerId }
    }
    val historyWindow = rememberChatHistoryWindow(
        sessionKey = workerId,
        messages = ui.messages,
        listState = listState,
    )

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .then(insetsState.bottomInset)
            .testTag("worker_monitor_screen"),
    ) {
        WorkerMonitorTopBar(
            worker = worker,
            usage = ui.session.usage,
            deleting = ui.deleting,
            onBack = {
                keyboard?.hide()
                onBack()
            },
            onOpenUsage = {
                keyboard?.hide()
                usageSheetOpen = true
            },
            onRequestDelete = {
                keyboard?.hide()
                deleteConfirmOpen = true
            },
            modifier = Modifier.padding(top = with(density) { insetsState.statusTopPx.toDp() }),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (worker == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DdEmptyState(
                        text = stringResource(R.string.super_worker_monitor_missing),
                        icon = DdIcons.Sparkles,
                    )
                }
            } else if (ui.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = with(density) { overlayHeightPx.toDp() }),
                    contentAlignment = Alignment.Center,
                ) {
                    if ((!ui.session.loaded || worker.status.isBusyForCommands) && ui.error == null) {
                        DdSpinner(size = 20.dp, strokeWidth = 2.dp, color = colors.inkTertiary)
                    } else {
                        DdEmptyState(
                            text = stringResource(R.string.super_worker_empty_chat),
                            icon = DdIcons.Sparkles,
                        )
                    }
                }
            } else {
                CompositionLocalProvider(LocalDensity provides chatDensity) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                }
                            },
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 6.dp,
                            bottom = with(chatDensity) {
                                (overlayHeightPx + DdSpacing.Md.roundToPx()).toDp()
                            },
                        ),
                        verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxl),
                    ) {
                        if (historyWindow.hiddenMessageCount > 0) {
                            item(key = "history-load-earlier", contentType = "history-load-earlier") {
                                Box(
                                    Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadEarlierPill(
                                        hiddenCount = historyWindow.hiddenMessageCount,
                                        onClick = historyWindow.loadEarlier,
                                    )
                                }
                            }
                        }
                        items(
                            items = historyWindow.visibleMessages,
                            key = { it.id },
                            contentType = { it.role },
                        ) { message ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(Modifier.widthIn(max = 780.dp).fillMaxWidth()) {
                                    ChatTurn(
                                        message = message,
                                        onToggleStep = { stepId -> viewModel.toggleStep(stepId) },
                                        onCopyOutput = ::copyText,
                                        onQuote = viewModel::quote,
                                        onOpenAttachment = { ref ->
                                            val file = attachmentStore.file(ref, ui.attachmentRoot)
                                            previewTarget = ref to file
                                        },
                                        onOpenFile = ::openFileRef,
                                        onRetry = viewModel::retryMessage,
                                        attachmentRoot = ui.attachmentRoot,
                                        processDetail = processDetail,
                                        pendingQuestions = emptyList(),
                                        onAnswerQuestion = { pending, answers ->
                                            viewModel.answerQuestion(pending.requestId, answers)
                                        },
                                        onOpenPresentFile = ::openFileRef,
                                        onSharePresentFile = ::shareFileRef,
                                        onOpenMiniapp = onOpenMiniapp,
                                        presentedCards = ui.presentedCards,
                                        remotePresentContext = remotePresentContext,
                                    )
                                }
                            }
                        }
                        worker.pendingQuestion?.let { pending ->
                            item(key = "worker-question-${pending.requestId}", contentType = "pending-question") {
                                Box(
                                    Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    Box(Modifier.widthIn(max = 780.dp).fillMaxWidth()) {
                                        AskQuestionCard(
                                            questions = pending.questions,
                                            answers = null,
                                            submitted = false,
                                            onAnswer = { answers ->
                                                viewModel.answerQuestion(pending.requestId, answers)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val showTopFade by remember(listState) {
                    derivedStateOf { listState.canScrollBackward }
                }
                val topFadeAlpha by animateFloatAsState(
                    targetValue = if (showTopFade) 1f else 0f,
                    animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
                    label = "worker-chat-top-fade",
                )
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(14.dp)
                        .graphicsLayer { alpha = topFadeAlpha }
                        .background(Brush.verticalGradient(listOf(colors.paper, colors.paper.copy(alpha = 0f)))),
                )
            }

            if (worker != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                ) {
                    val jumpSpec = if (motionEnabled) tween<Float>(DdMotion.FAST, easing = DdMotion.EaseOut) else snap()
                    AnimatedVisibility(
                        visible = scrollController.showJumpToBottom,
                        enter = fadeIn(jumpSpec) + scaleIn(
                            initialScale = if (motionEnabled) 0.92f else 1f,
                            animationSpec = jumpSpec,
                        ),
                        exit = fadeOut(jumpSpec) + scaleOut(
                            targetScale = if (motionEnabled) 0.92f else 1f,
                            animationSpec = jumpSpec,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(bottom = DdSpacing.Xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            SuperJumpToBottomChip(
                                onClick = { scope.launch { scrollController.jumpToBottom(animate = true) } },
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { overlayHeightPx = it.height },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .background(Brush.verticalGradient(listOf(colors.paper.copy(alpha = 0f), colors.paper))),
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.paper),
                        ) {
                            ui.error?.takeIf { it.isNotBlank() }?.let { errText ->
                                ChatErrorNoticeBar(
                                    error = errText,
                                    onRetry = viewModel::retryLastError,
                                    onDismiss = viewModel::dismissError,
                                )
                            }
                            key(workerId) {
                                TodoPanel(payload = todoPayload)
                            }
                            val inputAllowed = !worker.isRemote || ui.isRemoteOnline
                            ChatComposer(
                                state = ChatComposerState(
                                    value = ui.draft.input,
                                    ready = inputAllowed,
                                    sending = isSending,
                                    configured = if (worker.isRemote) true else sessionConfigured || configuredProviders.isNotEmpty(),
                                    modelLabel = ui.session.modelLabel,
                                    modelId = ui.session.modelId,
                                    models = ui.models,
                                    providerId = ui.session.providerId,
                                    providers = configuredProviders,
                                    thinkingLevel = ui.session.thinkingLevel,
                                    thinkingLevels = ui.thinkingLevels,
                                    quote = ui.draft.quote,
                                    attachments = ui.draft.attachments,
                                    attachmentRoot = ui.attachmentRoot,
                                    queuedInputs = emptyList(),
                                    importingAttachment = ui.draft.importingAttachment,
                                    modelsOpen = ui.draft.modelsOpen,
                                    inputEnabled = inputAllowed,
                                    modelControlsVisible = !worker.isRemote,
                                    manageProvidersVisible = !worker.isRemote,
                                    blockedReason = if (worker.isRemote && !ui.isRemoteOnline) {
                                        stringResource(R.string.super_worker_remote_offline)
                                    } else {
                                        null
                                    },
                                ),
                                actions = ChatComposerActions(
                                    onValueChange = viewModel::onInputChange,
                                    onSend = {
                                        if (!isSending) {
                                            keyboard?.hide()
                                            focusManager.clearFocus()
                                        }
                                        pendingLocalSend = true
                                        viewModel.send()
                                    },
                                    onStop = viewModel::stop,
                                    onModelChange = viewModel::setModel,
                                    onProviderChange = viewModel::setProvider,
                                    onThinkingLevelChange = viewModel::setThinkingLevel,
                                    onClearQuote = viewModel::clearQuote,
                                    onPickAttachment = { attachmentSheetOpen = true },
                                    onRemoveAttachment = viewModel::removeAttachment,
                                    onOpenAttachment = { ref ->
                                        previewTarget = ref to attachmentStore.file(ref, ui.attachmentRoot)
                                    },
                                    onManageProviders = onManageProviders,
                                    onRemoveQueued = {},
                                    onSendQueued = {},
                                    onModelsOpenChange = viewModel::setModelsOpen,
                                    onOpenPluginPage = onOpenPluginPage,
                                ),
                                voiceLanguage = voiceLanguage,
                                modifier = Modifier.padding(top = 6.dp, bottom = DdSpacing.Sm),
                            )
                        }
                    }
                }
            }
        }
    }

    SuperAttachmentOverlays(
        attachmentSheetOpen = attachmentSheetOpen,
        onDismissSheet = { attachmentSheetOpen = false },
        cameraSheetOpen = cameraSheetOpen,
        onDismissCamera = { cameraSheetOpen = false },
        onPickImages = attachmentPickers.pickImages,
        onPickFiles = attachmentPickers.pickFiles,
        onOpenCamera = { cameraSheetOpen = true },
        onAttachUri = viewModel::attach,
        previewTarget = previewTarget,
        onDismissPreview = { previewTarget = null },
        attachmentStore = attachmentStore,
        superDir = superDir,
        attachmentRoot = ui.attachmentRoot,
        shareChooserTitle = shareChooserTitle,
        shareFailedMessage = shareFailedMessage,
        exportDone = exportDone,
        exportDonePathFormat = exportDonePathFormat,
    )

    if (usageSheetOpen) {
        val currentUsage = ui.session.usage
        if (currentUsage != null) {
            val canCompact = worker != null &&
                !worker.status.isBusyForCommands &&
                !ui.session.streaming &&
                (!worker.isRemote || ui.isRemoteOnline) &&
                ui.messages.isNotEmpty()
            UsageSheet(
                usage = currentUsage,
                compactionTriggerPercent = compactionTriggerPercent,
                onDismiss = { usageSheetOpen = false },
                onCompact = if (canCompact) {
                    {
                        usageSheetOpen = false
                        viewModel.compact()
                    }
                } else {
                    null
                },
            )
        }
    }

    if (deleteConfirmOpen && worker != null) {
        DdDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = stringResource(R.string.super_worker_delete_title),
            content = {
                DdText(
                    text = stringResource(R.string.super_worker_delete_body, worker.title),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.super_worker_cancel),
                    onClick = { deleteConfirmOpen = false },
                )
                DdTextAction(
                    text = stringResource(R.string.super_worker_delete_confirm),
                    color = colors.danger,
                    onClick = {
                        deleteConfirmOpen = false
                        viewModel.deleteWorker(onDeleted = onBack)
                    },
                )
            },
        )
    }
}

@Composable
private fun WorkerMonitorTopBar(
    worker: WorkerRunRecord?,
    usage: SessionUsage?,
    deleting: Boolean,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIconButton(
            icon = DdIcons.ArrowLeft,
            onClick = onBack,
            contentDescription = stringResource(R.string.super_team_back),
            modifier = Modifier.testTag("worker_monitor_back"),
            tint = colors.ink,
            iconSize = 21.dp,
        )
        Spacer(Modifier.width(DdSpacing.Xs))
        if (worker != null) {
            val deviceLabel = if (worker.isRemote && worker.deviceName.isNotBlank()) {
                worker.deviceName
            } else {
                stringResource(R.string.super_drawer_this_device)
            }
            val displayTitle = worker.title.ifBlank { worker.botName }
            val subtitle = "${worker.botName} · $deviceLabel"
            BotAvatar(
                name = worker.botName,
                avatar = worker.botAvatar,
                size = 28.dp,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = DdType.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (usage != null && usage.contextWindow > 0L) {
                Spacer(Modifier.width(DdSpacing.Xs))
                ContextMeter(
                    usedTokens = usage.contextTokens,
                    windowTokens = usage.contextWindow,
                    onClick = onOpenUsage,
                    showPercent = false,
                )
            }
            Spacer(Modifier.width(DdSpacing.Xs))
            val statusColor = workerStatusColor(worker.status, colors)
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = workerStatusLabel(worker.status),
                    style = DdType.chip,
                    color = statusColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (!worker.status.isBusyForCommands) {
                DdIconButton(
                    icon = DdIcons.Trash,
                    onClick = onRequestDelete,
                    contentDescription = stringResource(R.string.super_worker_monitor_delete),
                    modifier = Modifier.testTag("worker_monitor_delete"),
                    enabled = !deleting,
                    tint = colors.inkSecondary,
                    iconSize = 18.dp,
                )
            } else {
                Spacer(Modifier.width(DdSpacing.Sm))
            }
        } else {
            Text(
                text = stringResource(R.string.super_worker_monitor_title),
                modifier = Modifier.weight(1f),
                style = DdType.heading,
                color = colors.ink,
            )
        }
    }
}

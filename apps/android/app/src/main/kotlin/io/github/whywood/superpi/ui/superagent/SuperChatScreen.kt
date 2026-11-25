package io.github.whywood.superpi.ui.superagent

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.AttachmentExport
import io.github.whywood.superpi.data.AttachmentKind
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.AttachmentStore
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.data.exportShareIntent
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.ui.common.PiBrandMark
import io.github.whywood.superpi.ui.common.chat.UsageSheet
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.common.chat.AskQuestionCard
import io.github.whywood.superpi.ui.common.chat.AttachmentPreviewDialog
import io.github.whywood.superpi.ui.common.chat.AttachmentSource
import io.github.whywood.superpi.ui.common.chat.AttachmentSourceSheet
import io.github.whywood.superpi.ui.common.chat.CameraCaptureSheet
import io.github.whywood.superpi.ui.common.chat.ChatComposer
import io.github.whywood.superpi.ui.common.chat.ChatComposerActions
import io.github.whywood.superpi.ui.common.chat.ChatComposerState
import io.github.whywood.superpi.ui.common.chat.ChatErrorNoticeBar
import io.github.whywood.superpi.ui.common.chat.ChatScrollFollowEffect
import io.github.whywood.superpi.ui.common.chat.LoadEarlierPill
import io.github.whywood.superpi.ui.common.chat.rememberChatHistoryWindow
import io.github.whywood.superpi.ui.common.chat.ChatTurn
import io.github.whywood.superpi.ui.common.chat.copyToClipboard
import io.github.whywood.superpi.ui.common.chat.rememberAttachmentPickers
import io.github.whywood.superpi.ui.common.chat.rememberChatScrollController
import io.github.whywood.superpi.ui.common.chat.rememberSuperEmptyCopy
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SuperChatScreen(
    viewModel: SuperChatViewModel,
    attachmentStore: AttachmentStore,
    superDir: File,
    attachmentRoot: File,
    configuredProviders: List<ProviderSummary>,
    processDetail: ProcessDetail,
    chatFontScale: Float,
    voiceLanguage: AppLanguage,
    onOpenDrawer: () -> Unit,
    onOpenTeam: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenWorkerMonitor: (String) -> Unit,
    onManageProviders: () -> Unit,
    onOpenMiniapp: (String) -> Unit,
    onOpenPluginPage: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    nickname: String = "",
    compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
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
    val stableStatusTopPx = insetsState.statusTopPx
    val bottomInset = insetsState.bottomInset

    var overlayHeightPx by remember { mutableIntStateOf(0) }
    var archiveConfirmOpen by rememberSaveable { mutableStateOf(false) }
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
                attachmentRoot = attachmentRoot,
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
                attachmentRoot = attachmentRoot,
                missingMessage = missingMessage,
                shareTitle = shareChooserTitle,
                shareFailedMessage = shareFailedMessage,
                exportDone = exportDone,
                exportDonePathFormat = exportDonePathFormat,
            )
        }
    }

    var knownMessageCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.messages.size, pendingLocalSend) {
        if (ui.messages.isNotEmpty() && (scrollController.following || pendingLocalSend)) {
            val shouldAnimate = motionEnabled && (knownMessageCount > 0 || pendingLocalSend)
            scrollController.jumpToBottom(animate = shouldAnimate)
            pendingLocalSend = false
        }
        knownMessageCount = ui.messages.size
    }

    ChatScrollFollowEffect(
        controller = scrollController,
        sessionKey = "super-router",
        sending = ui.thread.busy,
        smooth = motionEnabled,
    )

    LaunchedEffect(scrollController, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) to overlayHeightPx }.collect {
            scrollController.anchorToBottom()
        }
    }

    LaunchedEffect(ui.thread.pendingQuestion?.requestId) {
        if (ui.thread.pendingQuestion != null) {
            delay(60)
            scrollController.jumpToBottom(animate = true)
        }
    }

    val threadConfigured = remember(ui.thread.providerId, configuredProviders) {
        ui.thread.providerId.isNotBlank() &&
            configuredProviders.any { it.id == ui.thread.providerId }
    }
    val historyWindow = rememberChatHistoryWindow(
        sessionKey = "super-chat-${ui.thread.archivedCount}",
        messages = ui.messages,
        listState = listState,
    )

    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .then(bottomInset)
            .testTag("super_chat_screen"),
    ) {
        SuperTopBar(
            onOpenDrawer = {
                keyboard?.hide()
                onOpenDrawer()
            },
            onOpenTeam = {
                keyboard?.hide()
                onOpenTeam()
            },
            onRequestArchiveAndReset = {
                keyboard?.hide()
                archiveConfirmOpen = true
            },
            modifier = Modifier.padding(top = with(density) { stableStatusTopPx.toDp() }),
            usage = ui.thread.usage,
            onOpenUsage = {
                keyboard?.hide()
                usageSheetOpen = true
            },
            onOpenSettings = {
                keyboard?.hide()
                onOpenSettings()
            },
        )
        ActiveWorkersStrip(
            workers = ui.activeWorkers,
            onOpenWorkerMonitor = onOpenWorkerMonitor,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!ui.thread.ready && ui.messages.isEmpty() && ui.error == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DdSpinner(size = 20.dp, strokeWidth = 2.dp, color = colors.inkTertiary)
                }
            } else if (ui.messages.isEmpty()) {
                SuperEmptyState(
                    sessionKey = ui.thread.archivedCount,
                    nickname = nickname,
                    enabledTeamCount = ui.enabledTeamCount,
                    onPickStarter = viewModel::onInputChange,
                    onOpenTeam = onOpenTeam,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = with(density) { overlayHeightPx.toDp() }),
                )
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
                            top = 14.dp,
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
                                            val file = attachmentStore.file(ref, attachmentRoot)
                                            previewTarget = ref to file
                                        },
                                        onOpenFile = ::openFileRef,
                                        onRetry = viewModel::retryMessage,
                                        attachmentRoot = attachmentRoot,
                                        processDetail = processDetail,
                                        pendingQuestions = emptyList(),
                                        onAnswerQuestion = { pending, answers ->
                                            viewModel.answerSuperQuestion(pending.requestId, answers)
                                        },
                                        onOpenPresentFile = ::openFileRef,
                                        onSharePresentFile = ::shareFileRef,
                                        onOpenMiniapp = onOpenMiniapp,
                                        presentedCards = ui.presentedCards,
                                    )
                                }
                            }
                        }
                        ui.thread.pendingQuestion?.let { pending ->
                            item(key = "super-question-${pending.requestId}", contentType = "pending-question") {
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
                                                viewModel.answerSuperQuestion(pending.requestId, answers)
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
                    label = "super-chat-top-fade",
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
                        ui.pendingWorkerApprovals.firstOrNull()?.let { (worker, question) ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xs),
                                contentAlignment = Alignment.Center,
                            ) {
                                WorkerApprovalBanner(
                                    worker = worker,
                                    question = question,
                                    onOpenWorkerMonitor = onOpenWorkerMonitor,
                                    onAnswer = { answers ->
                                        viewModel.answerWorkerQuestion(worker.workerId, question.requestId, answers)
                                    },
                                    onDismiss = {
                                        viewModel.dismissWorkerQuestion(worker.workerId, question.requestId)
                                    },
                                )
                            }
                        }
                        ChatComposer(
                            state = ChatComposerState(
                                value = ui.draft.input,
                                ready = ui.thread.ready,
                                sending = ui.thread.busy,
                                configured = threadConfigured || configuredProviders.isNotEmpty(),
                                modelLabel = ui.thread.modelLabel,
                                modelId = ui.thread.modelId,
                                models = ui.models,
                                providerId = ui.thread.providerId,
                                providers = configuredProviders,
                                thinkingLevel = ui.thread.thinkingLevel,
                                thinkingLevels = ui.thinkingLevels,
                                quote = ui.draft.quote,
                                attachments = ui.draft.attachments,
                                attachmentRoot = attachmentRoot,
                                queuedInputs = emptyList(),
                                importingAttachment = ui.draft.importingAttachment,
                                modelsOpen = ui.draft.modelsOpen,
                            ),
                            actions = ChatComposerActions(
                                onValueChange = viewModel::onInputChange,
                                onSend = {
                                    if (!ui.thread.busy) {
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
                                    previewTarget = ref to attachmentStore.file(ref, attachmentRoot)
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

    if (usageSheetOpen) {
        val currentUsage = ui.thread.usage
        if (currentUsage != null) {
            val canCompact = ui.thread.ready && !ui.thread.busy && ui.messages.isNotEmpty()
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
        attachmentRoot = attachmentRoot,
        shareChooserTitle = shareChooserTitle,
        shareFailedMessage = shareFailedMessage,
        exportDone = exportDone,
        exportDonePathFormat = exportDonePathFormat,
    )

    if (archiveConfirmOpen) {
        DdDialog(
            onDismissRequest = { archiveConfirmOpen = false },
            title = stringResource(R.string.super_chat_archive_title),
            content = {
                DdText(
                    text = stringResource(R.string.super_chat_archive_body),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.super_worker_cancel),
                    onClick = { archiveConfirmOpen = false },
                )
                DdTextAction(
                    text = stringResource(R.string.super_chat_archive_confirm),
                    color = colors.danger,
                    onClick = {
                        archiveConfirmOpen = false
                        viewModel.archiveAndReset()
                    },
                )
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuperEmptyState(
    sessionKey: Any?,
    nickname: String,
    enabledTeamCount: Int,
    onPickStarter: (String) -> Unit,
    onOpenTeam: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val emptyCopy = rememberSuperEmptyCopy(sessionKey = sessionKey, nickname = nickname)
    Box(
        modifier = modifier.padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                PiBrandMark(size = 76.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(colors.paper)
                        .padding(2.5.dp)
                        .clip(CircleShape)
                        .background(colors.signal),
                )
            }
            Text(
                text = emptyCopy.greeting,
                style = DdType.heading,
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                emptyCopy.starters.forEach { prompt ->
                    DdPressable(
                        onClick = { onPickStarter(prompt) },
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .border(0.5.dp, colors.surfaceRim, CircleShape),
                        shape = CircleShape,
                        background = colors.surface,
                        contentDescription = prompt,
                    ) {
                        Text(
                            text = prompt,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = DdType.chip,
                            color = colors.inkSecondary,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (enabledTeamCount > 0) {
                Text(
                    text = stringResource(R.string.super_chat_empty_team_summary, enabledTeamCount),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            DdTextAction(
                text = stringResource(R.string.super_chat_empty_team_action),
                onClick = onOpenTeam,
                color = if (enabledTeamCount == 0) colors.accent else colors.inkSecondary,
            )
        }
    }
}

@Composable
internal fun SuperJumpToBottomChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val label = stringResource(R.string.assistant_jump_bottom)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline.copy(alpha = 0.65f), CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            DdIcon(icon = DdIcons.ChevronDown, tint = colors.inkSecondary, size = 13.dp)
            Text(
                text = label,
                style = DdType.chip,
                color = colors.inkSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

internal data class SuperWindowInsetsState(
    val statusTopPx: Int,
    val bottomInset: Modifier,
)

@Composable
internal fun rememberSuperWindowInsets(): SuperWindowInsetsState {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navigationBottomPx = with(density) { WindowInsets.navigationBars.getBottom(density) }
    val statusTopPx = with(density) { WindowInsets.statusBars.getTop(density) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var stableStatusTopPx by remember { mutableIntStateOf(statusTopPx) }
    var stableNavigationBottomPx by remember { mutableIntStateOf(navigationBottomPx) }
    SideEffect {
        if (windowFocused) {
            stableStatusTopPx = statusTopPx
            stableNavigationBottomPx = navigationBottomPx
        }
    }
    val bottomInset = Modifier.layout { measurable, constraints ->
        val inset = maxOf(imeInsets.getBottom(this), stableNavigationBottomPx).coerceAtLeast(0)
        val available = constraints.maxHeight
        if (available == androidx.compose.ui.unit.Constraints.Infinity) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val contentHeight = (available - inset).coerceAtLeast(0)
            val placeable = measurable.measure(
                constraints.copy(minHeight = 0, maxHeight = contentHeight),
            )
            layout(placeable.width, placeable.height + inset) { placeable.place(0, 0) }
        }
    }
    return SuperWindowInsetsState(statusTopPx = stableStatusTopPx, bottomInset = bottomInset)
}

@Composable
internal fun SuperAttachmentOverlays(
    attachmentSheetOpen: Boolean,
    onDismissSheet: () -> Unit,
    cameraSheetOpen: Boolean,
    onDismissCamera: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onOpenCamera: () -> Unit,
    onAttachUri: (Uri) -> Unit,
    previewTarget: Pair<AttachmentRef, File>?,
    onDismissPreview: () -> Unit,
    attachmentStore: AttachmentStore,
    superDir: File,
    attachmentRoot: File,
    shareChooserTitle: String,
    shareFailedMessage: String,
    exportDone: String,
    exportDonePathFormat: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (attachmentSheetOpen) {
        AttachmentSourceSheet(
            onDismiss = onDismissSheet,
            onPick = { source ->
                onDismissSheet()
                when (source) {
                    AttachmentSource.IMAGE -> onPickImages()
                    AttachmentSource.FILE -> onPickFiles()
                    AttachmentSource.CAMERA -> onOpenCamera()
                }
            },
        )
    }
    if (cameraSheetOpen) {
        CameraCaptureSheet(
            onDismiss = onDismissCamera,
            onCaptured = { uri ->
                onDismissCamera()
                onAttachUri(uri)
            },
        )
    }
    previewTarget?.let { (ref, file) ->
        AttachmentPreviewDialog(
            attachment = ref,
            file = file,
            onDismiss = onDismissPreview,
            onShare = {
                scope.launch {
                    val root = if (file.absolutePath.startsWith(attachmentRoot.absolutePath)) attachmentRoot else superDir
                    shareResolvedAttachment(
                        context = context,
                        attachmentStore = attachmentStore,
                        ref = ref,
                        root = root,
                        shareTitle = shareChooserTitle,
                        shareFailedMessage = shareFailedMessage,
                        exportDone = exportDone,
                        exportDonePathFormat = exportDonePathFormat,
                    )
                }
            },
            onExport = {
                scope.launch {
                    val root = if (file.absolutePath.startsWith(attachmentRoot.absolutePath)) attachmentRoot else superDir
                    exportResolvedAttachment(
                        context = context,
                        attachmentStore = attachmentStore,
                        ref = ref,
                        root = root,
                        shareTitle = shareChooserTitle,
                        exportDonePathFormat = exportDonePathFormat,
                    )
                }
            },
        )
    }
}

internal suspend fun handleOpenSuperFileRef(
    context: Context,
    resolveFile: suspend (String) -> Pair<AttachmentRef, File>?,
    raw: String,
    attachmentStore: AttachmentStore,
    superDir: File,
    attachmentRoot: File,
    missingMessage: String,
    openFailedMessage: String,
    onPreview: (Pair<AttachmentRef, File>) -> Unit,
) {
    val pair = resolveFile(raw)
    if (pair == null) {
        Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
        return
    }
    val (ref, file) = pair
    if (ref.kind != AttachmentKind.OTHER || ref.mime.startsWith("video/")) {
        onPreview(ref to file)
    } else {
        val root = if (file.absolutePath.startsWith(attachmentRoot.absolutePath)) attachmentRoot else superDir
        val intent = attachmentStore.viewIntent(ref, root)
        val opened = intent != null && runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            Toast.makeText(context, openFailedMessage, Toast.LENGTH_SHORT).show()
        }
    }
}

internal suspend fun handleShareSuperFileRef(
    context: Context,
    resolveFile: suspend (String) -> Pair<AttachmentRef, File>?,
    raw: String,
    attachmentStore: AttachmentStore,
    superDir: File,
    attachmentRoot: File,
    missingMessage: String,
    shareTitle: String,
    shareFailedMessage: String,
    exportDone: String,
    exportDonePathFormat: String,
) {
    val pair = resolveFile(raw)
    if (pair == null) {
        Toast.makeText(context, missingMessage, Toast.LENGTH_SHORT).show()
        return
    }
    val (ref, file) = pair
    shareResolvedAttachment(
        context = context,
        attachmentStore = attachmentStore,
        ref = ref,
        root = if (file.absolutePath.startsWith(attachmentRoot.absolutePath)) attachmentRoot else superDir,
        shareTitle = shareTitle,
        shareFailedMessage = shareFailedMessage,
        exportDone = exportDone,
        exportDonePathFormat = exportDonePathFormat,
    )
}

internal suspend fun shareResolvedAttachment(
    context: Context,
    attachmentStore: AttachmentStore,
    ref: AttachmentRef,
    root: File,
    shareTitle: String,
    shareFailedMessage: String,
    exportDone: String,
    exportDonePathFormat: String,
) {
    val intent = attachmentStore.shareIntent(ref, root)
    if (intent != null) {
        context.startActivity(shareChooser(context, intent, shareTitle))
        return
    }
    val export = attachmentStore.export(ref, root)
    val exportIntent = export?.let { exportShareIntent(context, it, ref.name, ref.mime) }
    if (export != null && exportIntent != null) {
        val message = if (export is AttachmentExport.SharedStorage) {
            String.format(exportDonePathFormat, export.file.absolutePath)
        } else {
            exportDone
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        context.startActivity(shareChooser(context, exportIntent, shareTitle))
    } else {
        Toast.makeText(context, shareFailedMessage, Toast.LENGTH_SHORT).show()
    }
}

internal suspend fun exportResolvedAttachment(
    context: Context,
    attachmentStore: AttachmentStore,
    ref: AttachmentRef,
    root: File,
    shareTitle: String,
    exportDonePathFormat: String,
) {
    when (val export = attachmentStore.export(ref, root)) {
        null -> Toast.makeText(context, R.string.files_export_failed, Toast.LENGTH_SHORT).show()
        is AttachmentExport.SharedStorage -> {
            Toast.makeText(
                context,
                String.format(exportDonePathFormat, export.file.absolutePath),
                Toast.LENGTH_LONG,
            ).show()
            exportShareIntent(context, export, ref.name, ref.mime)
                ?.let { context.startActivity(shareChooser(context, it, shareTitle)) }
        }
        is AttachmentExport.Download -> Toast.makeText(context, R.string.files_export_done, Toast.LENGTH_SHORT).show()
    }
}

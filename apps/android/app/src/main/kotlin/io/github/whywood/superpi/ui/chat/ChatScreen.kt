package io.github.whywood.superpi.ui.chat

import io.github.whywood.superpi.ui.common.chat.activeBotFor
import io.github.whywood.superpi.ui.common.chat.commandQuery

import io.github.whywood.superpi.ui.common.chat.ChatErrorNoticeBar
import io.github.whywood.superpi.ui.common.chat.ChatTurn
import io.github.whywood.superpi.ui.common.chat.LoadEarlierPill
import io.github.whywood.superpi.ui.common.chat.expandVisibleMessages
import io.github.whywood.superpi.ui.common.chat.resolveVisibleMessages

import io.github.whywood.superpi.ui.common.chat.presentedCardLookup

import io.github.whywood.superpi.ui.common.chat.LocalSelectionCoordinator

import io.github.whywood.superpi.ui.common.chat.copyToClipboard

import io.github.whywood.superpi.ui.common.chat.SelectionCoordinator

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
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
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AppLanguage
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.DdWordmark
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.AgentRuntimeHost
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.GoalView
import io.github.whywood.superpi.runtime.PendingQuestion
import io.github.whywood.superpi.runtime.ProviderSummary
import io.github.whywood.superpi.runtime.QuestionAnswer
import io.github.whywood.superpi.runtime.QueuedInput
import io.github.whywood.superpi.runtime.RuntimeActivity
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.PiBrandMark
import io.github.whywood.superpi.ui.common.remoteChatControls
import io.github.whywood.superpi.ui.common.chat.ChatComposer
import io.github.whywood.superpi.ui.common.chat.ChatComposerActions
import io.github.whywood.superpi.ui.common.chat.ChatComposerState
import io.github.whywood.superpi.ui.common.chat.ChatScrollController
import io.github.whywood.superpi.ui.common.chat.ChatScrollFollowEffect
import io.github.whywood.superpi.ui.common.chat.ChatUiState
import io.github.whywood.superpi.ui.common.chat.RemotePresentContext
import io.github.whywood.superpi.ui.common.chat.ScrollAnchor
import io.github.whywood.superpi.ui.common.chat.rememberChatScrollController
import io.github.whywood.superpi.ui.common.chat.rememberNormalEmptyCopy
import io.github.whywood.superpi.ui.common.chat.TodoWriteCustomType
import io.github.whywood.superpi.ui.common.chat.parseTodos
import io.github.whywood.superpi.ui.goal.GoalBar
import io.github.whywood.superpi.ui.common.chat.AskQuestionCard
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch







data class ChatScreenActions(
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onOpenDrawer: () -> Unit,
    val onManageProviders: () -> Unit,
    val onReload: () -> Unit,
    val onModelChange: (String) -> Unit,
    val onProviderChange: (String) -> Unit,
    val onThinkingLevelChange: (ThinkingLevel) -> Unit,
    val onToggleStep: (messageId: Long, stepId: Long) -> Unit,
    val onQuoteMessage: (Long, String) -> Unit,
    val onClearQuote: () -> Unit,
    val onPickAttachment: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onOpenAttachment: (AttachmentRef) -> Unit,
    val onOpenFile: (String) -> Unit,
    val onOpenTimers: () -> Unit,
    val onRetry: (Long) -> Unit,
    val onPickCommand: (CommandItem) -> Unit,
    val onPauseGoal: () -> Unit,
    val onResumeGoal: () -> Unit,
    val onOpenJobs: () -> Unit,
    val onModelsOpenChange: (Boolean) -> Unit,
    val onOpenUsage: () -> Unit,
    val onAnswerQuestion: (PendingQuestion, List<QuestionAnswer>) -> Unit,
    val onOpenForeignQuestion: (String) -> Unit,
    val onOpenPresentFile: (String) -> Unit,
    val onSharePresentFile: (String) -> Unit,
    val onOpenMiniapp: (String) -> Unit,
    val onOpenPluginPage: (String, String) -> Unit,
    val onOpenFiles: () -> Unit,
    val onExportSession: () -> Unit,
    val onRemoveQueued: (QueuedInput) -> Unit,
    val onSendQueued: (QueuedInput) -> Unit,
    val onScrollAnchorChange: (sessionId: String, index: Int, offset: Int, atBottom: Boolean) -> Unit,
    val onOpenBotSettings: (String) -> Unit,
    val onEndGoal: () -> Unit = {},
    val onToggleMemoryExcluded: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onRewindMessage: ((Long) -> Unit)? = null,
    val onForkMessage: ((Long) -> Unit)? = null,
)

data class ChatScreenOverlay(
    val reminderCount: Int,
    val reloading: Boolean,
    val compacting: Boolean = false,
    val commandItems: List<CommandItem> = emptyList(),
    val goal: GoalView? = null,
    val jobCount: Int = 0,
    val modelsOpen: Boolean = false,
    val pendingQuestions: List<PendingQuestion> = emptyList(),
    val foreignQuestion: PendingQuestion? = null,
    val memoryExcluded: Boolean = false,
    val memoryToggleVisible: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    nickname: String,
    avatar: ImageBitmap? = null,
    providers: List<ProviderSummary>,
    attachmentRoot: File,
    actions: ChatScreenActions,
    overlay: ChatScreenOverlay,
    scrollAnchorFor: (String) -> ScrollAnchor?,
    modifier: Modifier = Modifier,
    processDetail: ProcessDetail = ProcessDetail.DETAILED,
    chatFontScale: Float = 1f,
    voiceLanguage: AppLanguage = AppLanguage.SYSTEM,
    canControlRemoteModel: Boolean = false,
    remoteFilesVisible: Boolean = false,
    reloadVisible: Boolean = true,
    remoteModelsLoading: Boolean = false,
    remoteThinkingLevels: List<ThinkingLevel> = emptyList(),
    remotePresentContext: RemotePresentContext? = null,
) {
    val (
        onInputChange, onSend, onStop, onOpenDrawer, onManageProviders, onReload,
        onModelChange, onProviderChange, onThinkingLevelChange, onToggleStep,
        onQuoteMessage, onClearQuote, onPickAttachment, onRemoveAttachment,
        onOpenAttachment, onOpenFile, onOpenTimers, onRetry, onPickCommand,
        onPauseGoal, onResumeGoal, onOpenJobs, onModelsOpenChange, onOpenUsage,
        onAnswerQuestion, onOpenForeignQuestion, onOpenPresentFile, onSharePresentFile,
        onOpenMiniapp, onOpenPluginPage, onOpenFiles, onExportSession, onRemoveQueued, onSendQueued, onScrollAnchorChange,
        onOpenBotSettings, onEndGoal,
    ) = actions
    val (reminderCount, reloading, compacting, commandItems, goal, jobCount, modelsOpen, pendingQuestions, foreignQuestion) = overlay
    val attachments = state.attachments
    val queuedInputs = state.queuedInputs
    val importingAttachment = state.importingAttachment
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val controller = rememberChatScrollController(listState)
    val density = LocalDensity.current
    val chatDensity = remember(density, chatFontScale) {
        Density(density = density.density, fontScale = density.fontScale * chatFontScale)
    }
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
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val copiedMessage = stringResource(R.string.assistant_copied)
    val configured = state.configured
    val remoteControls = remember(state.workspaces, state.currentWorkspaceId, canControlRemoteModel) {
        remoteChatControls(state.workspaces, state.currentWorkspaceId, canControlRemoteModel)
    }
    val remoteBot = remoteControls.remote
    val remoteOnline = remoteControls.inputEnabled
    val modelControlsVisible = remoteControls.modelControlsVisible
    val messages = state.messages
    val presentedCards = remember(messages) { presentedCardLookup(messages) }
    val streamMessages = remember(messages) {
        messages.filterNot { it.customType == TodoWriteCustomType && parseTodos(it.customPayload).isEmpty() }
    }
    val sessionKey = state.conversationId.orEmpty()
    var visibleMessageCount by rememberSaveable(sessionKey) { mutableStateOf(0) }
    var trackedMessageTotal by remember { mutableIntStateOf(streamMessages.size) }
    LaunchedEffect(streamMessages.size) {
        visibleMessageCount = resolveVisibleMessages(visibleMessageCount, trackedMessageTotal, streamMessages.size)
        trackedMessageTotal = streamMessages.size
    }
    val shownMessageCount = resolveVisibleMessages(visibleMessageCount, trackedMessageTotal, streamMessages.size)
        .coerceIn(0, streamMessages.size)
    val visibleMessages = remember(streamMessages, shownMessageCount) {
        if (shownMessageCount >= streamMessages.size) streamMessages else streamMessages.takeLast(shownMessageCount)
    }
    val hiddenMessageCount = (streamMessages.size - shownMessageCount).coerceAtLeast(0)
    val userMessageIndices = remember(streamMessages) {
        streamMessages.mapIndexedNotNull { index, message ->
            index.takeIf { message.role == AssistantRole.USER }
        }
    }
    val showMessageNav = userMessageIndices.size > ChatHistoryNavWindowTicks
    val retryActivity = state.activity as? RuntimeActivity.Retrying
    val retryTargetId = messages.lastOrNull { it.streaming || it.retrying }?.id
    val stablePendingQuestions = remember(pendingQuestions) { pendingQuestions }
    val thinkingLevels = remember(state.models, state.modelId, remoteBot, remoteThinkingLevels) {
        state.models.firstOrNull { it.id == state.modelId }?.thinkingLevels.orEmpty().ifEmpty {
            if (remoteBot != null) remoteThinkingLevels else emptyList()
        }
    }
    val latestScrollAnchorFor by rememberUpdatedState(scrollAnchorFor)
    val latestScrollAnchorChange by rememberUpdatedState(onScrollAnchorChange)
    val selectionCoordinator = remember { SelectionCoordinator() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val latestRootOrigin by rememberUpdatedState(rootOrigin)
    val selectionClearMargin = with(density) { 28.dp.toPx() }

    fun copyText(text: String) {
        if (copyToClipboard(context, text)) {
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    fun loadEarlier() {
        if (hiddenMessageCount <= 0) return
        val previousShown = shownMessageCount
        val nextShown = expandVisibleMessages(previousShown, streamMessages.size)
        if (nextShown <= previousShown) return
        val itemCountBefore = listState.layoutInfo.totalItemsCount
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val firstVisibleOffset = listState.firstVisibleItemScrollOffset
        visibleMessageCount = nextShown
        trackedMessageTotal = streamMessages.size
        scope.launch {
            var frames = 0
            while (listState.layoutInfo.totalItemsCount <= itemCountBefore && frames < 120) {
                withFrameNanos { }
                frames++
            }
            val pillBefore = previousShown < streamMessages.size
            val pillAfter = nextShown < streamMessages.size
            val shift = nextShown - previousShown + if (pillBefore && !pillAfter) -1 else 0
            if (firstVisibleIndex > 0) {
                listState.scrollToItem((firstVisibleIndex + shift).coerceAtLeast(0), firstVisibleOffset)
            }
        }
    }

    fun jumpToMessage(messageIndex: Int) {
        if (streamMessages.isEmpty()) return
        val targetIndex = messageIndex.coerceIn(0, streamMessages.size - 1)
        val neededShown = streamMessages.size - targetIndex
        val targetShown = maxOf(shownMessageCount, neededShown).coerceAtMost(streamMessages.size)
        if (targetShown > shownMessageCount) {
            visibleMessageCount = targetShown
            trackedMessageTotal = streamMessages.size
        }
        scope.launch {
            val itemIndex = messageItemIndex(targetIndex, streamMessages.size, targetShown)
            var frames = 0
            while (listState.layoutInfo.totalItemsCount <= itemIndex && frames < 120) {
                withFrameNanos { }
                frames++
            }
            controller.jumpToItem(itemIndex)
        }
    }

    val latestLoadEarlier by rememberUpdatedState(::loadEarlier)
    val latestHasHiddenMessages by rememberUpdatedState(hiddenMessageCount > 0)
    val overscrollThresholdPx = with(density) { 72.dp.toPx() }
    val historyOverscroll = remember(overscrollThresholdPx) {
        object : NestedScrollConnection {
            private var pull = 0f

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y <= 0f || !latestHasHiddenMessages) {
                    pull = 0f
                    return Offset.Zero
                }
                pull += available.y
                if (pull >= overscrollThresholdPx) {
                    pull = 0f
                    latestLoadEarlier()
                }
                return Offset.Zero
            }
        }
    }

    var knownCount by remember { mutableIntStateOf(state.messages.size) }
    var knownSessionId by remember { mutableStateOf(state.conversationId) }
    var pendingLocalSend by remember { mutableStateOf(false) }

    LaunchedEffect(state.conversationId, state.historyLoaded) {
        val sessionId = state.conversationId ?: return@LaunchedEffect
        if (!state.historyLoaded) return@LaunchedEffect
        knownCount = state.messages.size
        knownSessionId = sessionId
        val anchor = latestScrollAnchorFor(sessionId)
        if (anchor == null || anchor.atBottom || hiddenMessageCount > 0) {
            controller.jumpToBottom(animate = false)
        } else {
            controller.restoreAnchor(anchor.index, anchor.offset)
        }
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                latestScrollAnchorChange(sessionId, index, offset, controller.atBottom)
            }
    }

    LaunchedEffect(state.conversationId, state.messages.size) {
        val size = state.messages.size
        val grew = state.conversationId == knownSessionId && size > knownCount
        if (grew && (controller.following || pendingLocalSend)) controller.jumpToBottom(animate = motionEnabled)
        pendingLocalSend = false
        knownCount = size
        knownSessionId = state.conversationId
    }

    val latestChatState by rememberUpdatedState(state)

    ChatScrollFollowEffect(
        controller = controller,
        sessionKey = state.conversationId,
        sending = state.sending,
        smooth = motionEnabled,
        onStreamingStopped = { pendingLocalSend = false },
    )

    LaunchedEffect(pendingLocalSend) {
        if (!pendingLocalSend) return@LaunchedEffect
        delay(2_000)
        if (!latestChatState.sending) pendingLocalSend = false
    }

    LaunchedEffect(controller, imeInsets) {
        snapshotFlow { imeInsets.getBottom(density) to overlayHeightPx }.collect {
            controller.anchorToBottom()
        }
    }

    val pendingQuestionIds = remember(pendingQuestions) { pendingQuestions.map { it.requestId } }
    LaunchedEffect(pendingQuestionIds) {
        if (pendingQuestionIds.isNotEmpty()) {
            delay(60)
            controller.jumpToBottom(animate = true)
        }
    }


    Column(
        modifier
            .fillMaxSize()
            .background(colors.paper)
            .then(bottomInset)
            .onGloballyPositioned { rootOrigin = it.positionInWindow() }
            .pointerInput(selectionCoordinator, selectionClearMargin) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val windowPosition = down.position + latestRootOrigin
                    if (!selectionCoordinator.isInsideSelectionArea(windowPosition, selectionClearMargin)) {
                        selectionCoordinator.clear()
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalSelectionCoordinator provides selectionCoordinator) {
        val activeBot = remember(state.workspaces, state.currentWorkspaceId, state.conversationCwd) {
            activeBotFor(state.workspaces, state.currentWorkspaceId, state.conversationCwd)
                ?: state.workspaces.firstOrNull { it.isHome }
        }
        ChatTopBar(
            activeBot = activeBot,
            state = ChatTopBarState(
                reminderCount = reminderCount,
                reloading = reloading,
                usage = state.usage,
                jobCount = jobCount,
                remoteFilesVisible = remoteFilesVisible,
                reloadVisible = reloadVisible,
                memoryExcluded = overlay.memoryExcluded,
                memoryToggleVisible = overlay.memoryToggleVisible,
            ),
            actions = ChatTopBarActions(
                onMenu = {
                    keyboard?.hide()
                    onOpenDrawer()
                },
                onReload = {
                    keyboard?.hide()
                    onReload()
                },
                onOpenTimers = {
                    keyboard?.hide()
                    onOpenTimers()
                },
                onOpenUsage = {
                    keyboard?.hide()
                    onOpenUsage()
                },
                onOpenJobs = {
                    keyboard?.hide()
                    onOpenJobs()
                },
                onOpenFiles = {
                    keyboard?.hide()
                    onOpenFiles()
                },
                onExportSession = {
                    keyboard?.hide()
                    onExportSession()
                },
                onOpenBotSettings = onOpenBotSettings,
                onToggleMemoryExcluded = {
                    keyboard?.hide()
                    actions.onToggleMemoryExcluded()
                },
            ),
            modifier = Modifier.padding(top = with(density) { stableStatusTopPx.toDp() }),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.switchingConversation) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    DdSpinner(
                        modifier = Modifier.testTag("switching_progress"),
                        color = colors.inkTertiary,
                        size = 18.dp,
                        strokeWidth = 2.dp,
                    )
                }
            } else if (messages.isEmpty()) {
                EmptyState(
                    loaded = state.historyLoaded,
                    sessionKey = (state.conversationId ?: "") to (activeBot?.id ?: AgentRuntimeHost.HOME_ID),
                    nickname = nickname,
                    botName = activeBot?.name ?: AgentRuntimeHost.HOME_NAME,
                    botAvatar = activeBot?.avatar,
                    error = state.error,
                    onRetryLoad = actions.onRetryLoad,
                    onPickStarter = onInputChange,
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
                            .nestedScroll(historyOverscroll)
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
                        if (hiddenMessageCount > 0) {
                            item(key = "history-load-earlier", contentType = "history-load-earlier") {
                                Box(
                                    Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadEarlierPill(
                                        hiddenCount = hiddenMessageCount,
                                        onClick = ::loadEarlier,
                                    )
                                }
                            }
                        }
                        items(
                            items = visibleMessages,
                            key = { it.id },
                            contentType = { it.role },
                        ) { message ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(Modifier.widthIn(max = 780.dp).fillMaxWidth()) {
                                    val localHistoryActionsAllowed = remoteBot == null &&
                                        !state.sending &&
                                        !state.reloading &&
                                        !state.switchingConversation
                                    ChatTurn(
                                        message = message,
                                        onToggleStep = { stepId -> onToggleStep(message.id, stepId) },
                                        onCopyOutput = ::copyText,
                                        onQuote = onQuoteMessage,
                                        onOpenAttachment = onOpenAttachment,
                                        onOpenFile = onOpenFile,
                                        onRetry = onRetry,
                                        attachmentRoot = attachmentRoot,
                                        processDetail = processDetail,
                                        pendingQuestions = emptyList(),
                                        onAnswerQuestion = onAnswerQuestion,
                                        onOpenPresentFile = onOpenPresentFile,
                                        onSharePresentFile = onSharePresentFile,
                                        onOpenMiniapp = onOpenMiniapp,
                                        presentedCards = presentedCards,
                                        remotePresentContext = remotePresentContext,
                                        retryAttempt = if (message.id == retryTargetId) {
                                            retryActivity?.attempt
                                        } else {
                                            null
                                        },
                                        onRewind = actions.onRewindMessage?.takeIf { localHistoryActionsAllowed },
                                        onFork = actions.onForkMessage?.takeIf { localHistoryActionsAllowed },
                                    )
                                }
                            }
                        }
                        stablePendingQuestions
                            .firstOrNull()
                            ?.let { pending ->
                                item(key = "pending-question-${pending.requestId}", contentType = "pending-question") {
                                    Box(
                                        Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.TopCenter,
                                    ) {
                                        Box(Modifier.widthIn(max = 780.dp).fillMaxWidth()) {
                                            AskQuestionCard(
                                                questions = pending.questions,
                                                answers = null,
                                                submitted = false,
                                                onAnswer = { answers -> onAnswerQuestion(pending, answers) },
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
                ChatTopFadeOverlay(
                    listState = listState,
                    motionEnabled = motionEnabled,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                ChatJumpToBottomOverlay(
                    controller = controller,
                    motionEnabled = motionEnabled,
                    onJump = { scope.launch { controller.jumpToBottom(animate = true) } },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { overlayHeightPx = it.height },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(colors.paper.copy(alpha = 0f), colors.paper),
                                ),
                            ),
                    )
                    Column(Modifier.fillMaxWidth().background(colors.paper)) {
                AnimatedVisibility(
                    visible = compacting || state.compacting,
                    enter = if (motionEnabled) {
                        fadeIn(tween(DdMotion.FAST)) + expandVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeIn(snap()) + expandVertically(snap())
                    },
                    exit = if (motionEnabled) {
                        fadeOut(tween(DdMotion.FAST)) + shrinkVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeOut(snap()) + shrinkVertically(snap())
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Xs),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_compacting),
                            style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                            color = colors.inkTertiary,
                        )
                    }
                }
                foreignQuestion?.let { foreign ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xs), contentAlignment = Alignment.Center) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 780.dp)
                                .fillMaxWidth()
                                .clip(DdShapes.Segmented)
                                .background(colors.raised)
                                .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Sm)
                                .testTag("chat_foreign_question"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.chat_question_other),
                                modifier = Modifier.weight(1f),
                                style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                                color = colors.inkSecondary,
                            )
                            DdTextAction(
                                text = stringResource(R.string.chat_question_other_action),
                                onClick = { onOpenForeignQuestion(foreign.sessionId) },
                            )
                        }
                    }
                }
                goal?.let { view ->
                    GoalBar(
                        view = view,
                        onPause = onPauseGoal,
                        onResume = onResumeGoal,
                        onEnd = onEndGoal,
                        modifier = Modifier.padding(horizontal = DdSpacing.Md),
                    )
                }
                state.error?.takeIf { it.isNotBlank() }?.takeIf { messages.isNotEmpty() || state.historyLoaded }?.let { errText ->
                    ChatErrorNoticeBar(
                        error = errText,
                        onRetry = if (!state.historyLoaded || state.conversationId == null) actions.onRetryLoad else null,
                        onDismiss = actions.onDismissError,
                    )
                }
                val paletteQuery = commandQuery(state.input)
                var lastPaletteQuery by remember { mutableStateOf("") }
                LaunchedEffect(paletteQuery) {
                    if (paletteQuery != null) lastPaletteQuery = paletteQuery
                }
                AnimatedVisibility(
                    visible = paletteQuery != null && !state.sending,
                    enter = if (motionEnabled) {
                        fadeIn(tween(DdMotion.FAST)) + expandVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeIn(snap()) + expandVertically(snap())
                    },
                    exit = if (motionEnabled) {
                        fadeOut(tween(DdMotion.FAST)) + shrinkVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
                    } else {
                        fadeOut(snap()) + shrinkVertically(snap())
                    },
                ) {
                    SlashPalette(
                        items = commandItems,
                        query = paletteQuery ?: lastPaletteQuery,
                        onPick = { item -> onPickCommand(item) },
                        modifier = Modifier.padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xs),
                    )
                }
                ChatComposer(
                    state = ChatComposerState(
                        value = state.input,
                        ready = state.ready && state.historyLoaded && !state.switchingConversation,
                        sending = state.sending,
                        configured = configured || remoteBot != null,
                        modelLabel = if (configured || remoteBot != null) state.modelLabel else "",
                        modelId = state.modelId,
                        models = state.models,
                        providerId = state.providerId,
                        providers = providers,
                        thinkingLevel = state.thinkingLevel,
                        thinkingLevels = thinkingLevels,
                        quote = state.quote,
                        attachments = attachments,
                        attachmentRoot = attachmentRoot,
                        queuedInputs = queuedInputs,
                        importingAttachment = importingAttachment,
                        modelsOpen = modelsOpen,
                        inputEnabled = remoteOnline,
                        modelControlsVisible = modelControlsVisible,
                        manageProvidersVisible = remoteBot == null,
                        modelsLoading = remoteBot != null && remoteModelsLoading,
                        blockedReason = if (remoteOnline) {
                            null
                        } else {
                            stringResource(R.string.assistant_remote_offline)
                        },
                    ),
                    actions = ChatComposerActions(
                        onValueChange = onInputChange,
                        onSend = {
                            if (!state.sending) {
                                keyboard?.hide()
                                focusManager.clearFocus()
                                pendingLocalSend = true
                            }
                            onSend()
                        },
                        onStop = onStop,
                        onModelChange = onModelChange,
                        onProviderChange = onProviderChange,
                        onThinkingLevelChange = onThinkingLevelChange,
                        onClearQuote = onClearQuote,
                        onPickAttachment = onPickAttachment,
                        onRemoveAttachment = onRemoveAttachment,
                        onOpenAttachment = onOpenAttachment,
                        onManageProviders = onManageProviders,
                        onRemoveQueued = onRemoveQueued,
                        onSendQueued = onSendQueued,
                        onModelsOpenChange = onModelsOpenChange,
                        onOpenPluginPage = onOpenPluginPage,
                    ),
                    voiceLanguage = voiceLanguage,
                    modifier = Modifier.padding(top = 6.dp, bottom = DdSpacing.Sm),
                )
                }
                }
            }
            if (showMessageNav && !state.switchingConversation) {
                ChatMessageNavOverlay(
                    listState = listState,
                    streamMessagesSize = streamMessages.size,
                    shownMessageCount = shownMessageCount,
                    userMessageIndices = userMessageIndices,
                    onJump = ::jumpToMessage,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(
                            top = DdSpacing.Sm,
                            end = 2.dp,
                            bottom = with(density) { overlayHeightPx.toDp() } + DdSpacing.Sm,
                        ),
                )
            }
        }
        }
    }
}

@Composable
private fun ChatTopFadeOverlay(
    listState: LazyListState,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val showTopFade by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val topFadeAlpha = animateFloatAsState(
        targetValue = if (showTopFade) 1f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "chat-top-fade",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(14.dp)
            .graphicsLayer { alpha = topFadeAlpha.value }
            .background(
                Brush.verticalGradient(
                    listOf(colors.paper, colors.paper.copy(alpha = 0f)),
                ),
            ),
    )
}

@Composable
private fun ChatJumpToBottomOverlay(
    controller: ChatScrollController,
    motionEnabled: Boolean,
    onJump: () -> Unit,
) {
    val jumpSpec = if (motionEnabled) tween<Float>(DdMotion.FAST, easing = DdMotion.EaseOut) else snap()
    AnimatedVisibility(
        visible = controller.showJumpToBottom,
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
            JumpToBottomChip(onClick = onJump)
        }
    }
}

@Composable
private fun ChatMessageNavOverlay(
    listState: LazyListState,
    streamMessagesSize: Int,
    shownMessageCount: Int,
    userMessageIndices: List<Int>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeMessageIndex by remember(listState, streamMessagesSize, shownMessageCount) {
        derivedStateOf {
            if (!listState.canScrollForward) {
                (streamMessagesSize - 1).coerceAtLeast(0)
            } else {
                messageIndexForItemIndex(
                    itemIndex = listState.firstVisibleItemIndex,
                    total = streamMessagesSize,
                    visible = shownMessageCount,
                )
            }
        }
    }
    MessageNavBar(
        userMessageIndices = userMessageIndices,
        activeMessageIndex = activeMessageIndex,
        onJump = onJump,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(
    loaded: Boolean,
    sessionKey: Any?,
    nickname: String,
    botName: String,
    botAvatar: String?,
    onPickStarter: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    onRetryLoad: () -> Unit = {},
) {
    val colors = superPiColors()
    val emptyCopy = rememberNormalEmptyCopy(sessionKey = sessionKey, nickname = nickname)
    Box(
        modifier.fillMaxSize().padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            if (!error.isNullOrBlank()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    Text(
                        text = error,
                        modifier = Modifier.testTag("chat_empty_error"),
                        style = DdType.body,
                        color = colors.danger,
                        textAlign = TextAlign.Center,
                    )
                    DdTextAction(
                        text = stringResource(R.string.super_chat_error_retry),
                        onClick = onRetryLoad,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.assistant_loading),
                    style = DdType.body,
                    color = colors.inkTertiary,
                )
            }
        } else {
            val motionEnabled = LocalDdMotionEnabled.current
            var shown by remember { mutableStateOf(!motionEnabled) }
            LaunchedEffect(Unit) { shown = true }
            AnimatedVisibility(
                visible = shown,
                enter = if (motionEnabled) {
                    fadeIn(tween(300, easing = DdMotion.EaseOut)) +
                        slideInVertically(
                            animationSpec = tween(300, easing = DdMotion.EaseOut),
                            initialOffsetY = { it / 40 },
                        )
                } else {
                    fadeIn(snap())
                },
                exit = ExitTransition.None,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!botAvatar.isNullOrBlank()) {
                        BotAvatar(
                            name = botName,
                            avatar = botAvatar,
                            size = 76.dp,
                            modifier = Modifier.testTag("home_avatar"),
                        )
                        Spacer(Modifier.height(DdSpacing.Lg))
                    } else {
                        PiBrandMark(
                            size = 92.dp,
                            modifier = Modifier.testTag("home_avatar"),
                        )
                        Spacer(Modifier.height(DdSpacing.Lg))
                    }
                    Text(
                        text = emptyCopy.greeting,
                        modifier = Modifier.testTag("home_greeting"),
                        style = DdType.heading,
                        color = colors.ink,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(DdSpacing.Sm))
                    Text(
                        text = stringResource(R.string.assistant_home_prompt),
                        style = DdType.body,
                        color = colors.inkSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(DdSpacing.Lg))
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
                }
            }
        }
    }
}

@Composable
private fun JumpToBottomChip(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val feedbackColor = if (colors.surface.luminance() < 0.45f) Color.White else colors.ink
    val wash by animateFloatAsState(
        targetValue = if (pressed) DdAlpha.Press else 0f,
        animationSpec = if (!motionEnabled || pressed) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "jump-bottom-wash",
    )
    val label = stringResource(R.string.assistant_jump_bottom)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("jump_to_bottom"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline.copy(alpha = 0.65f), CircleShape)
                .indication(interaction, ripple(color = feedbackColor.copy(alpha = DdAlpha.Ripple)))
                .drawWithContent {
                    drawContent()
                    if (wash > 0f) drawRect(feedbackColor.copy(alpha = wash))
                }
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

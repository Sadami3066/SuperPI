package io.github.whywood.superpi.ui.shell

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.COMPACTION_TRIGGER_DEFAULT
import io.github.whywood.superpi.data.relay.RemoteSessionSummaryDto
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdSheet
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import io.github.whywood.superpi.plugin.api.PluginPagePresentation
import io.github.whywood.superpi.plugin.host.services.PluginPageRequest
import io.github.whywood.superpi.R
import io.github.whywood.superpi.ui.plugins.PluginPageHost
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.JobSnapshot
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SessionUsage
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.ui.RemoteDrawerState
import io.github.whywood.superpi.ui.SuperPIDrawer
import io.github.whywood.superpi.ui.common.chat.AttachmentPreviewDialog
import io.github.whywood.superpi.ui.common.chat.ChatUiState
import io.github.whywood.superpi.ui.common.chat.UsageSheet
import io.github.whywood.superpi.ui.jobs.JobsSheet
import io.github.whywood.superpi.ui.navigation.AppDestination
import io.github.whywood.superpi.ui.workspaces.DeleteWorkspaceDialog
import io.github.whywood.superpi.ui.workspaces.RemoteBotEditDialog
import io.github.whywood.superpi.ui.workspaces.RemoteHostTarget
import io.github.whywood.superpi.ui.workspaces.WorkspaceCreateDialog
import io.github.whywood.superpi.ui.workspaces.WorkspaceNameDialog
import java.io.File



sealed interface ShellOverlay {
    data class WorkspaceCreate(
        val existingNames: List<String>,
        val privateDirectory: String,
        val remoteTargets: List<RemoteHostTarget> = emptyList(),
        val remoteBotNamesByDevice: Map<String, List<String>> = emptyMap(),
        val initialTargetDeviceId: String? = null,
        val submitting: Boolean = false,
        val remoteError: String? = null,
    ) : ShellOverlay
    data class WorkspaceRename(val workspaceId: String, val initial: String, val existingNames: List<String>) : ShellOverlay
    data class RemoteBotEdit(
        val workspaceId: String,
        val initialName: String,
        val initialIntro: String?,
        val hasAvatar: Boolean,
        val existingNames: List<String>,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
    ) : ShellOverlay
    data class WorkspaceDelete(
        val workspaceId: String,
        val name: String,
        val sessionCount: Int,
        val isRemote: Boolean = false,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
    ) : ShellOverlay
    data class AttachmentPreview(val attachment: AttachmentRef, val file: File) : ShellOverlay
    data class Usage(
        val usage: SessionUsage,
        val canCompact: Boolean = false,
        val compactionTriggerPercent: Int = COMPACTION_TRIGGER_DEFAULT,
    ) : ShellOverlay
    data class Jobs(val jobs: List<JobSnapshot>, val selectedJobId: String?, val output: String?) : ShellOverlay
}

data class ShellOverlayState(
    val pluginPage: PluginPageRequest? = null,
    val workspaceCreate: ShellOverlay.WorkspaceCreate? = null,
    val workspaceRename: ShellOverlay.WorkspaceRename? = null,
    val remoteBotEdit: ShellOverlay.RemoteBotEdit? = null,
    val workspaceDelete: ShellOverlay.WorkspaceDelete? = null,
    val attachmentPreview: ShellOverlay.AttachmentPreview? = null,
    val usage: ShellOverlay.Usage? = null,
    val jobs: ShellOverlay.Jobs? = null,
)

data class ShellOverlayActions(
    val onDismissPluginPage: () -> Unit,
    val onConfirmWorkspaceCreate: (String, String?, Bitmap?, String?) -> Unit, val onDismissWorkspaceCreate: () -> Unit,
    val onConfirmWorkspaceCreateRemote: (RemoteHostTarget, String, Bitmap?, String?) -> Unit = { _, _, _, _ -> },
    val onConfirmWorkspaceRename: (String) -> Unit, val onDismissWorkspaceRename: () -> Unit,
    val onConfirmRemoteBotEdit: (String?, String?, Boolean, Bitmap?, Boolean) -> Unit = { _, _, _, _, _ -> },
    val onDismissRemoteBotEdit: () -> Unit = {},
    val onConfirmWorkspaceDelete: () -> Unit, val onDismissWorkspaceDelete: () -> Unit,
    val onDismissAttachmentPreview: () -> Unit, val onShareAttachmentPreview: () -> Unit,
    val onExportAttachmentPreview: () -> Unit,
    val onDismissUsage: () -> Unit,
    val onCompactUsage: () -> Unit = {},
    val onSelectJob: (String) -> Unit,
    val onCloseJobOutput: () -> Unit, val onKillJob: (String) -> Unit,
    val onClearSettledJobs: () -> Unit, val onDismissJobs: () -> Unit,
)

data class ShellDrawerState(
    val open: Boolean,
    val sessions: List<ConversationSummary>,
    val workspaces: List<RuntimeWorkspace>,
    val activeWorkspaceId: String,
    val activeSessionId: String?,
    val activeRemoteSessionId: String? = null,
    val pinnedWorkspaceIds: Set<String>, val expandedWorkspaceIds: Set<String>,
    val busySessionIds: Set<String>,
    val timerSessionIds: Set<String>,
    val jobSessionIds: Set<String>,
    val userName: String,
    val avatar: ImageBitmap? = null,
    val hasUpdate: Boolean = false,
    val remoteDrawerStates: Map<String, RemoteDrawerState> = emptyMap(),
    val relayOnline: Boolean = true,
    val remoteBotManageWorkspaceIds: Set<String> = emptySet(),
    val remoteSessionManageWorkspaceIds: Set<String> = emptySet(),
    val remoteFilesWorkspaceIds: Set<String> = emptySet(),
    val remoteControlsWorkspaceIds: Set<String> = emptySet(),
    val remoteCapabilitiesWorkspaceIds: Set<String> = emptySet(),
    val remoteShowArchivedWorkspaceIds: Set<String> = emptySet(),
    val canCreateRemoteBotDeviceIds: Set<String> = emptySet(),
    val appMode: AppMode = AppMode.NORMAL,
    val superWorkers: List<WorkerRunRecord> = emptyList(),
    val superBlueprints: List<BotBlueprint> = emptyList(),
    val activeWorkerId: String? = null,
    val userSubtitle: String = "",
)
data class ShellDrawerActions(
    val onNavigate: (AppDestination) -> Unit, val onDismiss: () -> Unit, val onOpen: () -> Unit = {},
    val onCreateWorkspace: () -> Unit, val onCreateWorkspaceForDevice: (String) -> Unit = {},
    val onToggleWorkspaceExpanded: (String) -> Unit,
    val onToggleWorkspacePinned: (String) -> Unit, val onNewSessionInWorkspace: (String) -> Unit,
    val onWorkspaceSettings: (String) -> Unit, val onRenameWorkspace: (String) -> Unit,
    val onDeleteWorkspace: (String) -> Unit, val onConversation: (String) -> Unit,
    val onRename: (String, String) -> Unit, val onSetPinned: (String, Boolean) -> Unit,
    val onSetArchived: (String, Boolean) -> Unit, val onDelete: (String) -> Unit,
    val onSettings: () -> Unit,
    val onOpenRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto) -> Unit = { _, _ -> },
    val onRetryRemoteSessions: (RuntimeWorkspace) -> Unit = {},
    val onOpenRemoteFiles: (RuntimeWorkspace) -> Unit = {},
    val onOpenRemoteInstructions: (RuntimeWorkspace) -> Unit = {},
    val onOpenRemoteCapabilities: (RuntimeWorkspace) -> Unit = {},
    val onEditRemoteBot: (RuntimeWorkspace) -> Unit = {},
    val onToggleRemoteShowArchived: (RuntimeWorkspace) -> Unit = {},
    val onRenameRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto, String) -> Unit = { _, _, _ -> },
    val onSetRemoteSessionPinned: (RuntimeWorkspace, RemoteSessionSummaryDto, Boolean) -> Unit = { _, _, _ -> },
    val onSetRemoteSessionArchived: (RuntimeWorkspace, RemoteSessionSummaryDto, Boolean) -> Unit = { _, _, _ -> },
    val onDeleteRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto) -> Unit = { _, _ -> },
    val onToggleAppMode: () -> Unit = {},
    val onOpenSuperTeam: () -> Unit = {},
    val onOpenWorkerMonitor: (String) -> Unit = {},
    val onDeleteWorker: (String) -> Unit = {},
)
fun resolveOverlays(state: ShellOverlayState): List<ShellOverlay> = buildList {
    state.workspaceCreate?.let(::add)
    state.workspaceRename?.let(::add)
    state.remoteBotEdit?.let(::add)
    state.workspaceDelete?.let(::add)
    state.attachmentPreview?.let(::add)
    state.usage?.let(::add)
    state.jobs?.let(::add)
}
fun resolveOverlay(state: ShellOverlayState): ShellOverlay? = resolveOverlays(state).lastOrNull()

@Composable
internal fun OverlayHost(
    overlays: ShellOverlayState,
    overlayActions: ShellOverlayActions,
    drawer: ShellDrawerState,
    drawerActions: ShellDrawerActions,
    drawerController: ShellDrawerController? = null,
) {
    val activeDrawerController = drawerController ?: rememberShellDrawerController(
        open = drawer.open,
        onOpen = drawerActions.onOpen,
        onDismiss = drawerActions.onDismiss,
    )
    ShellDrawerHost(drawer, drawerActions, activeDrawerController)
    resolveOverlays(overlays).forEach { overlay ->
        key(overlay::class) {
            when (overlay) {
                is ShellOverlay.WorkspaceCreate -> WorkspaceCreateDialog(
                    existingNames = overlay.existingNames,
                    privateDirectory = overlay.privateDirectory,
                    remoteTargets = overlay.remoteTargets,
                    remoteBotNamesByDevice = overlay.remoteBotNamesByDevice,
                    initialTargetDeviceId = overlay.initialTargetDeviceId,
                    submitting = overlay.submitting,
                    remoteError = overlay.remoteError,
                    onConfirm = overlayActions.onConfirmWorkspaceCreate,
                    onConfirmRemote = overlayActions.onConfirmWorkspaceCreateRemote,
                    onDismiss = overlayActions.onDismissWorkspaceCreate,
                )

                is ShellOverlay.WorkspaceRename -> WorkspaceNameDialog(
                    initial = overlay.initial, existingNames = overlay.existingNames,
                    onConfirm = overlayActions.onConfirmWorkspaceRename,
                    onDismiss = overlayActions.onDismissWorkspaceRename,
                )

                is ShellOverlay.RemoteBotEdit -> RemoteBotEditDialog(
                    initialName = overlay.initialName,
                    initialIntro = overlay.initialIntro,
                    hasAvatar = overlay.hasAvatar,
                    existingNames = overlay.existingNames,
                    submitting = overlay.submitting,
                    errorMessage = overlay.errorMessage,
                    onConfirm = overlayActions.onConfirmRemoteBotEdit,
                    onDismiss = overlayActions.onDismissRemoteBotEdit,
                )

                is ShellOverlay.WorkspaceDelete -> DeleteWorkspaceDialog(
                    name = overlay.name,
                    sessionCount = overlay.sessionCount,
                    isRemote = overlay.isRemote,
                    submitting = overlay.submitting,
                    errorMessage = overlay.errorMessage,
                    onConfirm = overlayActions.onConfirmWorkspaceDelete,
                    onDismiss = overlayActions.onDismissWorkspaceDelete,
                )

                is ShellOverlay.AttachmentPreview -> AttachmentPreviewDialog(
                    overlay.attachment, overlay.file,
                    onDismiss = overlayActions.onDismissAttachmentPreview,
                    onShare = overlayActions.onShareAttachmentPreview,
                    onExport = overlayActions.onExportAttachmentPreview,
                )

                is ShellOverlay.Usage -> UsageSheet(
                    usage = overlay.usage,
                    compactionTriggerPercent = overlay.compactionTriggerPercent,
                    onDismiss = overlayActions.onDismissUsage,
                    onCompact = if (overlay.canCompact) overlayActions.onCompactUsage else null,
                )

                is ShellOverlay.Jobs -> JobsSheet(
                    overlay.jobs, overlay.selectedJobId, overlay.output,
                    onSelect = overlayActions.onSelectJob,
                    onCloseOutput = overlayActions.onCloseJobOutput,
                    onKill = overlayActions.onKillJob,
                    onClearSettled = overlayActions.onClearSettledJobs,
                    onDismiss = overlayActions.onDismissJobs,
                )

            }
        }
    }
    overlays.pluginPage?.let { request ->
        key(request) {
            PluginPageSheet(request, overlayActions.onDismissPluginPage)
        }
    }
}

@Composable
private fun PluginPageSheet(request: PluginPageRequest, onDismiss: () -> Unit) {
    val colors = superPiColors()
    DdSheet(onDismiss = onDismiss) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PluginPageHost(
                pluginId = request.pluginId,
                pageId = request.pageId,
                arguments = request.arguments,
                presentation = PluginPagePresentation.SHEET,
                fallback = {
                    Box(
                        Modifier.fillMaxSize().padding(DdSpacing.Lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdText(
                            text = stringResource(R.string.plugins_page_unavailable),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                    }
                },
            )
        }
    }
}

@Stable
internal class ShellDrawerController(
    private val scope: CoroutineScope,
    private val drawerProgress: Animatable<Float, AnimationVector1D>,
    private val draggingState: MutableState<Boolean>,
    private val currentStateOpen: State<Boolean>,
    private val currentOnOpen: State<() -> Unit>,
    private val currentOnDismiss: State<() -> Unit>,
) {
    var drawerWidthPx: Float = 0f
    var directionSign: Float = 1f
    var openFlingVelocityPx: Float = 0f
    var closeFlingVelocityPx: Float = 0f
    var motionEnabled: Boolean = true
    var onSwipeStart: () -> Unit = {}

    private var dragGeneration = 0

    val dragState: DraggableState = DraggableState { delta -> onDragDelta(delta) }
    val progress: Float get() = drawerProgress.value
    val dragging: Boolean get() = draggingState.value

    fun onDragStart() {
        dragGeneration += 1
        draggingState.value = true
    }

    fun onDragDelta(deltaPx: Float) {
        if (drawerWidthPx <= 0f) return
        val next = (drawerProgress.value + (deltaPx * directionSign / drawerWidthPx)).coerceIn(0f, 1f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerProgress.snapTo(next)
        }
    }

    fun onDragEnd(velocityPx: Float) {
        val gen = dragGeneration
        val effectiveVelocity = velocityPx * directionSign
        val wasOpen = currentStateOpen.value
        val openTarget = if (!wasOpen) {
            effectiveVelocity > openFlingVelocityPx ||
                (effectiveVelocity >= -openFlingVelocityPx && drawerProgress.value >= 0.14f)
        } else {
            !(
                effectiveVelocity < -closeFlingVelocityPx ||
                    (effectiveVelocity <= closeFlingVelocityPx && drawerProgress.value <= 0.82f)
                )
        }
        scope.launch {
            val targetValue = if (openTarget) 1f else 0f
            if (motionEnabled && drawerWidthPx > 0f) {
                drawerProgress.animateTo(
                    targetValue = targetValue,
                    initialVelocity = (effectiveVelocity / drawerWidthPx).coerceIn(-6f, 6f),
                    animationSpec = tween(DdMotion.BASE, easing = DdMotion.EaseOut),
                )
            } else {
                drawerProgress.snapTo(targetValue)
            }
            if (gen != dragGeneration) return@launch
            draggingState.value = false
            val stillOpen = currentStateOpen.value
            if (openTarget && !stillOpen) {
                currentOnOpen.value.invoke()
            } else if (!openTarget && stillOpen) {
                currentOnDismiss.value.invoke()
            }
        }
    }
}

@Composable
internal fun rememberShellDrawerController(
    open: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSwipeStart: () -> Unit = {},
): ShellDrawerController {
    val motionEnabled = LocalDdMotionEnabled.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val directionSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val drawerWidthPx = with(density) { 304.dp.toPx() }
    val openFlingVelocityPx = with(density) { 180.dp.toPx() }
    val closeFlingVelocityPx = with(density) { 220.dp.toPx() }
    val drawerProgress = remember { Animatable(if (open) 1f else 0f) }
    val draggingState = remember { mutableStateOf(false) }
    val currentOnOpen = rememberUpdatedState(onOpen)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentStateOpen = rememberUpdatedState(open)
    val currentOnSwipeStart = rememberUpdatedState(onSwipeStart)

    val controller = remember(scope, drawerProgress, draggingState) {
        ShellDrawerController(
            scope = scope,
            drawerProgress = drawerProgress,
            draggingState = draggingState,
            currentStateOpen = currentStateOpen,
            currentOnOpen = currentOnOpen,
            currentOnDismiss = currentOnDismiss,
        )
    }
    SideEffect {
        controller.drawerWidthPx = drawerWidthPx
        controller.directionSign = directionSign
        controller.openFlingVelocityPx = openFlingVelocityPx
        controller.closeFlingVelocityPx = closeFlingVelocityPx
        controller.motionEnabled = motionEnabled
        controller.onSwipeStart = { currentOnSwipeStart.value.invoke() }
    }

    LaunchedEffect(open, draggingState.value, motionEnabled) {
        if (draggingState.value) return@LaunchedEffect
        val target = if (open) 1f else 0f
        if (drawerProgress.value != target) {
            if (motionEnabled) {
                drawerProgress.animateTo(target, tween(DdMotion.BASE, easing = DdMotion.EaseOut))
            } else {
                drawerProgress.snapTo(target)
            }
        }
    }
    return controller
}

internal fun Modifier.drawerOpenSwipeGesture(
    enabled: Boolean,
    controller: ShellDrawerController,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(enabled, controller) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var tracking = true
            var startedDragging = false
            val velocityTracker = VelocityTracker()
            try {
                while (tracking) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || change.isConsumed) {
                        tracking = false
                    } else {
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val slop = viewConfiguration.touchSlop
                        if (hypot(dx, dy) >= slop) {
                            val directionalDx = dx * controller.directionSign
                            if (directionalDx > 0f && abs(dx) > abs(dy) * 1.15f) {
                                startedDragging = true
                                controller.onSwipeStart()
                                controller.onDragStart()
                                velocityTracker.addPosition(down.uptimeMillis, down.position)
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                                controller.onDragDelta(dx)
                                tracking = false
                            } else {
                                tracking = false
                            }
                        }
                    }
                }
                while (startedDragging) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        val velocityX = velocityTracker.calculateVelocity().x
                        startedDragging = false
                        change?.consume()
                        controller.onDragEnd(velocityX)
                        break
                    }
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val deltaX = change.positionChange().x
                    change.consume()
                    if (deltaX != 0f) {
                        controller.onDragDelta(deltaX)
                    }
                }
            } finally {
                if (startedDragging) {
                    controller.onDragEnd(0f)
                }
            }
        }
    }
}

@Composable
private fun ShellDrawerHost(
    state: ShellDrawerState,
    actions: ShellDrawerActions,
    controller: ShellDrawerController,
) {
    val colors = superPiColors()
    val drawerWidthPx = controller.drawerWidthPx
    val directionSign = controller.directionSign
    val currentOpen = rememberUpdatedState(state.open)
    val mounted by remember(controller) {
        derivedStateOf { currentOpen.value || controller.dragging || controller.progress > 0.005f }
    }
    if (!mounted) return
    Box(
        Modifier
            .fillMaxSize()
            .draggable(
                state = controller.dragState,
                orientation = Orientation.Horizontal,
                enabled = state.open || controller.dragging,
                onDragStarted = { controller.onDragStart() },
                onDragStopped = { velocity -> controller.onDragEnd(velocity) },
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * controller.progress))
                }
                .then(
                    if (state.open) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { actions.onDismiss() }
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            Modifier
                .fillMaxHeight()
                .width(304.dp)
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = -drawerWidthPx * (1f - controller.progress) * directionSign
                }
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            SuperPIDrawer(
                sessions = state.sessions,
                workspaces = state.workspaces,
                activeWorkspaceId = state.activeWorkspaceId,
                activeSessionId = state.activeSessionId,
                activeRemoteSessionId = state.activeRemoteSessionId,
                pinnedWorkspaceIds = state.pinnedWorkspaceIds,
                expandedWorkspaceIds = state.expandedWorkspaceIds,
                busySessionIds = state.busySessionIds,
                timerSessionIds = state.timerSessionIds,
                jobSessionIds = state.jobSessionIds,
                userName = state.userName,
                avatar = state.avatar,
                hasUpdate = state.hasUpdate,
                remoteDrawerStates = state.remoteDrawerStates,
                relayOnline = state.relayOnline,
                remoteBotManageWorkspaceIds = state.remoteBotManageWorkspaceIds,
                remoteSessionManageWorkspaceIds = state.remoteSessionManageWorkspaceIds,
                remoteFilesWorkspaceIds = state.remoteFilesWorkspaceIds,
                remoteControlsWorkspaceIds = state.remoteControlsWorkspaceIds,
                remoteCapabilitiesWorkspaceIds = state.remoteCapabilitiesWorkspaceIds,
                remoteShowArchivedWorkspaceIds = state.remoteShowArchivedWorkspaceIds,
                canCreateRemoteBotDeviceIds = state.canCreateRemoteBotDeviceIds,
                onOpenSearch = { actions.onNavigate(AppDestination.Search) },
                onOpenMiniapp = { actions.onNavigate(AppDestination.Miniapp) },
                onOpenSkills = { actions.onNavigate(AppDestination.Skills) },
                onOpenMcp = { actions.onNavigate(AppDestination.Mcp) },
                onOpenPlugins = { actions.onNavigate(AppDestination.Plugins) },
                onOpenTimers = { actions.onNavigate(AppDestination.Timers) },
                onOpenMemory = { actions.onNavigate(AppDestination.Memory) },
                onOpenRelay = { actions.onNavigate(AppDestination.Relay) },
                onCreateWorkspace = actions.onCreateWorkspace,
                onCreateWorkspaceForDevice = actions.onCreateWorkspaceForDevice,
                onToggleWorkspaceExpanded = actions.onToggleWorkspaceExpanded,
                onToggleWorkspacePinned = actions.onToggleWorkspacePinned,
                onNewSessionInWorkspace = actions.onNewSessionInWorkspace,
                onWorkspaceSettings = actions.onWorkspaceSettings,
                onRenameWorkspace = actions.onRenameWorkspace,
                onDeleteWorkspace = actions.onDeleteWorkspace,
                onConversation = actions.onConversation,
                onRename = actions.onRename,
                onSetPinned = actions.onSetPinned,
                onSetArchived = actions.onSetArchived,
                onDelete = actions.onDelete,
                onSettings = actions.onSettings,
                onOpenRemoteSession = actions.onOpenRemoteSession,
                onRetryRemoteSessions = actions.onRetryRemoteSessions,
                onOpenRemoteFiles = actions.onOpenRemoteFiles,
                onOpenRemoteInstructions = actions.onOpenRemoteInstructions,
                onOpenRemoteCapabilities = actions.onOpenRemoteCapabilities,
                onEditRemoteBot = actions.onEditRemoteBot,
                onToggleRemoteShowArchived = actions.onToggleRemoteShowArchived,
                onRenameRemoteSession = actions.onRenameRemoteSession,
                onSetRemoteSessionPinned = actions.onSetRemoteSessionPinned,
                onSetRemoteSessionArchived = actions.onSetRemoteSessionArchived,
                onDeleteRemoteSession = actions.onDeleteRemoteSession,
                appMode = state.appMode,
                superWorkers = state.superWorkers,
                superBlueprints = state.superBlueprints,
                activeWorkerId = state.activeWorkerId,
                userSubtitle = state.userSubtitle,
                onToggleAppMode = actions.onToggleAppMode,
                onOpenSuperTeam = actions.onOpenSuperTeam,
                onOpenWorkerMonitor = actions.onOpenWorkerMonitor,
                onDeleteWorker = actions.onDeleteWorker,
            )
        }
    }
}

@Composable
internal fun rememberFrozenChatState(live: ChatUiState, frozen: Boolean): ChatUiState {
    val snapshot = remember { mutableStateOf(live) }
    if (!frozen) {
        SideEffect { snapshot.value = live }
    }
    return if (frozen) snapshot.value else live
}

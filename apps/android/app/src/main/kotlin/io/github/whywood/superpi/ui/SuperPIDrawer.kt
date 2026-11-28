package io.github.whywood.superpi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.AppMode
import io.github.whywood.superpi.data.relay.RemoteSessionSummaryDto
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.ui.common.PiBrandMark
import io.github.whywood.superpi.design.DdEmptyState
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.LocalDdThemeVisualFamily
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.ui.common.resolveSuperModePillStyle
import io.github.whywood.superpi.ui.common.superModeShimmer
import io.github.whywood.superpi.runtime.BotBlueprint
import io.github.whywood.superpi.runtime.ConversationSummary
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.isActive
import io.github.whywood.superpi.runtime.isBusyForCommands
import io.github.whywood.superpi.ui.common.AvatarBadge
import io.github.whywood.superpi.ui.common.BotAvatar
import io.github.whywood.superpi.ui.common.WorkspaceMenuAction
import io.github.whywood.superpi.ui.common.WorkspaceRowMeta
import io.github.whywood.superpi.ui.common.preferredNewChatWorkspaceId
import io.github.whywood.superpi.ui.common.workspaceMenuActions
import io.github.whywood.superpi.ui.common.workspaceRowMeta
import io.github.whywood.superpi.ui.common.LocalHapticsEnabled
import io.github.whywood.superpi.ui.common.rememberRelativeTime
import io.github.whywood.superpi.ui.sessions.DeleteSessionDialog
import io.github.whywood.superpi.ui.sessions.RenameSessionDialog
import io.github.whywood.superpi.ui.sessions.SessionAction
import io.github.whywood.superpi.ui.sessions.SessionActionMenu

sealed interface RemoteDrawerStatus {
    data object Loading : RemoteDrawerStatus

    data object Offline : RemoteDrawerStatus

    data object RelayUpgradeRequired : RemoteDrawerStatus

    data object PeerUpgradeRequired : RemoteDrawerStatus

    data class Error(val message: String? = null) : RemoteDrawerStatus
}

data class RemoteDrawerState(
    val sessions: List<RemoteSessionSummaryDto> = emptyList(),
    val status: RemoteDrawerStatus? = null,
)

internal sealed interface DrawerEntry {
    val key: String

    data object WorkspacesHeader : DrawerEntry {
        override val key: String = "workspaces_header"
    }

    data class RemoteDeviceHeader(
        val deviceId: String,
        val deviceName: String,
        val online: Boolean,
        val expanded: Boolean,
        val canCreateBot: Boolean,
        val devicePlatform: String = "",
    ) : DrawerEntry {
        override val key: String = "remote_device_$deviceId"
    }

    data class WorkspaceEntry(val workspace: RuntimeWorkspace, val first: Boolean) : DrawerEntry {
        override val key: String = "workspace_${workspace.id}"
    }

    data class WorkspaceEmpty(val workspaceId: String) : DrawerEntry {
        override val key: String = "workspace_empty_$workspaceId"
    }

    data class RemoteLoading(val workspaceId: String) : DrawerEntry {
        override val key: String = "remote_loading_$workspaceId"
    }

    data class RemoteNotice(val workspace: RuntimeWorkspace, val status: RemoteDrawerStatus) : DrawerEntry {
        override val key: String = "remote_notice_${workspace.id}"
    }

    data class RemoteSession(
        val workspace: RuntimeWorkspace,
        val session: RemoteSessionSummaryDto,
    ) : DrawerEntry {
        override val key: String = "remote_session_${workspace.id}_${session.id}"
    }

    data class RemoteArchivedToggle(
        val workspace: RuntimeWorkspace,
        val showingArchived: Boolean,
    ) : DrawerEntry {
        override val key: String = "remote_archived_toggle_${workspace.id}"
    }

    data class Session(val conversation: ConversationSummary, val workspaceId: String) : DrawerEntry {
        override val key: String = "session_${workspaceId}_${conversation.id}"
    }

    data class SessionMore(val workspaceId: String, val remaining: Int) : DrawerEntry {
        override val key: String = "session_more_$workspaceId"
    }

    data class SessionLess(val workspaceId: String) : DrawerEntry {
        override val key: String = "session_less_$workspaceId"
    }
}

internal fun buildDrawerEntries(
    workspaces: List<RuntimeWorkspace>,
    sessionsByCwd: Map<String, List<ConversationSummary>>,
    expandedWorkspaceIds: Set<String>,
    expandedSessionWorkspaceIds: Set<String>,
    remoteDrawerStates: Map<String, RemoteDrawerState> = emptyMap(),
    relayOnline: Boolean = true,
    collapsedCount: Int = 5,
    remoteSessionManageWorkspaceIds: Set<String> = emptySet(),
    remoteShowArchivedWorkspaceIds: Set<String> = emptySet(),
    collapsedOnlineRemoteDeviceIds: Set<String> = emptySet(),
    expandedOfflineRemoteDeviceIds: Set<String> = emptySet(),
    canCreateRemoteBotDeviceIds: Set<String> = emptySet(),
): List<DrawerEntry> = buildList {
    val localWorkspaces = workspaces.filter { it.remote == null }
    val remoteWorkspaces = workspaces.filter { it.remote != null }
    add(DrawerEntry.WorkspacesHeader)
    localWorkspaces.forEachIndexed { index, workspace ->
        appendWorkspaceEntries(
            workspace = workspace,
            first = index == 0,
            sessionsByCwd = sessionsByCwd,
            expandedWorkspaceIds = expandedWorkspaceIds,
            expandedSessionWorkspaceIds = expandedSessionWorkspaceIds,
            remoteDrawerStates = remoteDrawerStates,
            relayOnline = relayOnline,
            collapsedCount = collapsedCount,
            remoteSessionManageWorkspaceIds = remoteSessionManageWorkspaceIds,
            remoteShowArchivedWorkspaceIds = remoteShowArchivedWorkspaceIds,
        )
    }
    data class RemoteGroup(
        val deviceId: String,
        val deviceName: String,
        val devicePlatform: String,
        val online: Boolean,
        val bots: List<RuntimeWorkspace>,
    )
    val remoteGroups = remoteWorkspaces
        .groupBy { it.remote!!.deviceId }
        .entries
        .map { (deviceId, bots) ->
            val name = bots.firstNotNullOfOrNull { bot ->
                bot.remote?.deviceName?.takeIf { it.isNotBlank() }
            } ?: deviceId
            val platform = bots.firstNotNullOfOrNull { bot ->
                bot.remote?.devicePlatform?.takeIf { it.isNotBlank() }
            }.orEmpty()
            val groupOnline = relayOnline && bots.any { it.remote?.online == true }
            RemoteGroup(
                deviceId = deviceId,
                deviceName = name,
                devicePlatform = platform,
                online = groupOnline,
                bots = bots,
            )
        }
        .sortedWith(
            compareByDescending<RemoteGroup> { it.online }
                .thenBy { it.deviceName.lowercase() }
                .thenBy { it.deviceId },
        )
    remoteGroups.forEach { group ->
        val expanded = if (group.online) {
            group.deviceId !in collapsedOnlineRemoteDeviceIds
        } else {
            group.deviceId in expandedOfflineRemoteDeviceIds
        }
        val canCreateBot = group.online && group.deviceId in canCreateRemoteBotDeviceIds
        add(
            DrawerEntry.RemoteDeviceHeader(
                deviceId = group.deviceId,
                deviceName = group.deviceName,
                online = group.online,
                expanded = expanded,
                canCreateBot = canCreateBot,
                devicePlatform = group.devicePlatform,
            ),
        )
        if (!expanded) return@forEach
        group.bots.forEachIndexed { index, workspace ->
            appendWorkspaceEntries(
                workspace = workspace,
                first = index == 0,
                sessionsByCwd = sessionsByCwd,
                expandedWorkspaceIds = expandedWorkspaceIds,
                expandedSessionWorkspaceIds = expandedSessionWorkspaceIds,
                remoteDrawerStates = remoteDrawerStates,
                relayOnline = relayOnline,
                collapsedCount = collapsedCount,
                remoteSessionManageWorkspaceIds = remoteSessionManageWorkspaceIds,
                remoteShowArchivedWorkspaceIds = remoteShowArchivedWorkspaceIds,
            )
        }
    }
}

private fun normalizeDrawerCwd(raw: String): String =
    if (raw.isBlank()) raw else runCatching { java.io.File(raw).canonicalPath }.getOrDefault(raw)

private fun MutableList<DrawerEntry>.appendWorkspaceEntries(
    workspace: RuntimeWorkspace,
    first: Boolean,
    sessionsByCwd: Map<String, List<ConversationSummary>>,
    expandedWorkspaceIds: Set<String>,
    expandedSessionWorkspaceIds: Set<String>,
    remoteDrawerStates: Map<String, RemoteDrawerState>,
    relayOnline: Boolean,
    collapsedCount: Int,
    remoteSessionManageWorkspaceIds: Set<String>,
    remoteShowArchivedWorkspaceIds: Set<String>,
) {
    add(DrawerEntry.WorkspaceEntry(workspace, first))
    if (workspace.id !in expandedWorkspaceIds) return
    val remote = workspace.remote
    if (remote != null) {
        val remoteState = remoteDrawerStates[workspace.id]
        val localFallback = sessionsByCwd[normalizeDrawerCwd(workspace.cwd)].orEmpty()
            .filterNot { it.archived }
            .sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }
                    .thenByDescending { it.updatedAt },
            )
        if (remote.online) {
            val remoteSessions = remoteState?.sessions.orEmpty()
                .filterNot { it.archived }
                .distinctBy { it.id }
                .sortedByDescending { it.pinned }
            if (remoteSessions.isNotEmpty()) {
                appendSessionSlice(
                    count = remoteSessions.size,
                    collapsedCount = collapsedCount,
                    expanded = workspace.id in expandedSessionWorkspaceIds,
                    workspaceId = workspace.id,
                    emit = { limit ->
                        remoteSessions.take(limit).forEach { add(DrawerEntry.RemoteSession(workspace, it)) }
                    },
                )
                return
            }
            when (val status = remoteState?.status) {
                null -> if (remoteState == null) {
                    if (relayOnline) {
                        add(DrawerEntry.RemoteLoading(workspace.id))
                    } else {
                        add(DrawerEntry.RemoteNotice(workspace, RemoteDrawerStatus.Offline))
                    }
                } else {
                    add(DrawerEntry.WorkspaceEmpty(workspace.id))
                }

                RemoteDrawerStatus.Loading -> if (relayOnline) {
                    add(DrawerEntry.RemoteLoading(workspace.id))
                } else {
                    add(DrawerEntry.RemoteNotice(workspace, RemoteDrawerStatus.Offline))
                }

                else -> add(DrawerEntry.RemoteNotice(workspace, status))
            }
            return
        }
        if (localFallback.isNotEmpty()) {
            appendSessionSlice(
                count = localFallback.size,
                collapsedCount = collapsedCount,
                expanded = workspace.id in expandedSessionWorkspaceIds,
                workspaceId = workspace.id,
                emit = { limit ->
                    localFallback.take(limit).forEach { add(DrawerEntry.Session(it, workspace.id)) }
                },
            )
        } else {
            add(DrawerEntry.RemoteNotice(workspace, RemoteDrawerStatus.Offline))
        }
        return
    }
    val workspaceSessions = sessionsByCwd[normalizeDrawerCwd(workspace.cwd)].orEmpty()
        .filterNot { it.archived }
        .sortedWith(
            compareByDescending<ConversationSummary> { it.pinned }
                .thenByDescending { it.updatedAt },
        )
    if (workspaceSessions.isEmpty()) {
        add(DrawerEntry.WorkspaceEmpty(workspace.id))
        return
    }
    appendSessionSlice(
        count = workspaceSessions.size,
        collapsedCount = collapsedCount,
        expanded = workspace.id in expandedSessionWorkspaceIds,
        workspaceId = workspace.id,
        emit = { limit ->
            workspaceSessions.take(limit).forEach { add(DrawerEntry.Session(it, workspace.id)) }
        },
    )
}

private inline fun MutableList<DrawerEntry>.appendSessionSlice(
    count: Int,
    collapsedCount: Int,
    expanded: Boolean,
    workspaceId: String,
    emit: (limit: Int) -> Unit,
) {
    if (count <= collapsedCount) {
        emit(count)
    } else if (expanded) {
        emit(count)
        add(DrawerEntry.SessionLess(workspaceId))
    } else {
        emit(collapsedCount)
        add(DrawerEntry.SessionMore(workspaceId, count - collapsedCount))
    }
}

@Composable
fun SuperPIDrawer(
    sessions: List<ConversationSummary>,
    workspaces: List<RuntimeWorkspace>,
    activeWorkspaceId: String,
    activeSessionId: String?,
    pinnedWorkspaceIds: Set<String>,
    expandedWorkspaceIds: Set<String>,
    busySessionIds: Set<String>,
    timerSessionIds: Set<String>,
    jobSessionIds: Set<String>,
    userName: String,
    avatar: ImageBitmap?,
    onOpenSearch: () -> Unit,
    onOpenMiniapp: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRelay: () -> Unit,
    onCreateWorkspace: () -> Unit,
    onCreateWorkspaceForDevice: (String) -> Unit = {},
    onToggleWorkspaceExpanded: (String) -> Unit,
    onToggleWorkspacePinned: (String) -> Unit,
    onNewSessionInWorkspace: (String) -> Unit,
    onWorkspaceSettings: (String) -> Unit,
    onRenameWorkspace: (String) -> Unit,
    onDeleteWorkspace: (String) -> Unit,
    onConversation: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    hasUpdate: Boolean = false,
    activeRemoteSessionId: String? = null,
    remoteDrawerStates: Map<String, RemoteDrawerState> = emptyMap(),
    relayOnline: Boolean = true,
    remoteBotManageWorkspaceIds: Set<String> = emptySet(),
    remoteSessionManageWorkspaceIds: Set<String> = emptySet(),
    remoteFilesWorkspaceIds: Set<String> = emptySet(),
    remoteControlsWorkspaceIds: Set<String> = emptySet(),
    remoteCapabilitiesWorkspaceIds: Set<String> = emptySet(),
    remoteShowArchivedWorkspaceIds: Set<String> = emptySet(),
    canCreateRemoteBotDeviceIds: Set<String> = emptySet(),
    onOpenRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto) -> Unit = { _, _ -> },
    onRetryRemoteSessions: (RuntimeWorkspace) -> Unit = {},
    onOpenRemoteFiles: (RuntimeWorkspace) -> Unit = {},
    onOpenRemoteInstructions: (RuntimeWorkspace) -> Unit = {},
    onOpenRemoteCapabilities: (RuntimeWorkspace) -> Unit = {},
    onEditRemoteBot: (RuntimeWorkspace) -> Unit = {},
    onToggleRemoteShowArchived: (RuntimeWorkspace) -> Unit = {},
    onRenameRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto, String) -> Unit = { _, _, _ -> },
    onSetRemoteSessionPinned: (RuntimeWorkspace, RemoteSessionSummaryDto, Boolean) -> Unit = { _, _, _ -> },
    onSetRemoteSessionArchived: (RuntimeWorkspace, RemoteSessionSummaryDto, Boolean) -> Unit = { _, _, _ -> },
    onDeleteRemoteSession: (RuntimeWorkspace, RemoteSessionSummaryDto) -> Unit = { _, _ -> },
    appMode: AppMode = AppMode.NORMAL,
    superWorkers: List<WorkerRunRecord> = emptyList(),
    superBlueprints: List<BotBlueprint> = emptyList(),
    activeWorkerId: String? = null,
    userSubtitle: String = "",
    onToggleAppMode: () -> Unit = {},
    onOpenSuperTeam: () -> Unit = {},
    onOpenWorkerMonitor: (String) -> Unit = {},
    onDeleteWorker: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var sessionMenuTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var sessionMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var renameTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationSummary?>(null) }
    var remoteSessionMenuTarget by remember { mutableStateOf<Pair<RuntimeWorkspace, RemoteSessionSummaryDto>?>(null) }
    var remoteSessionMenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var remoteRenameTarget by remember { mutableStateOf<Pair<RuntimeWorkspace, RemoteSessionSummaryDto>?>(null) }
    var remoteDeleteTarget by remember { mutableStateOf<Pair<RuntimeWorkspace, RemoteSessionSummaryDto>?>(null) }
    val isSuperMode = appMode == AppMode.SUPER
    val orderedWorkspaces = remember(workspaces, pinnedWorkspaceIds, isSuperMode) {
        if (isSuperMode) {
            emptyList()
        } else {
            workspaces.filter { it.id in pinnedWorkspaceIds } + workspaces.filterNot { it.id in pinnedWorkspaceIds }
        }
    }
    val sessionsByCwd = remember(sessions, isSuperMode) {
        if (isSuperMode) {
            emptyMap()
        } else {
            sessions.filterNot { it.archived }.groupBy { normalizeDrawerCwd(it.cwd) }
        }
    }
    val expandedSessionsSaver = remember {
        Saver<Set<String>, ArrayList<String>>(
            save = { ArrayList(it) },
            restore = { it.toSet() },
        )
    }
    var expandedSessionWorkspaceIds by rememberSaveable(stateSaver = expandedSessionsSaver) {
        mutableStateOf(emptySet<String>())
    }
    var collapsedOnlineRemoteDeviceIds by rememberSaveable(stateSaver = expandedSessionsSaver) {
        mutableStateOf(emptySet<String>())
    }
    var expandedOfflineRemoteDeviceIds by rememberSaveable(stateSaver = expandedSessionsSaver) {
        mutableStateOf(emptySet<String>())
    }
    val entries = remember(
        isSuperMode,
        orderedWorkspaces,
        sessionsByCwd,
        expandedWorkspaceIds,
        expandedSessionWorkspaceIds,
        remoteDrawerStates,
        relayOnline,
        remoteSessionManageWorkspaceIds,
        remoteShowArchivedWorkspaceIds,
        collapsedOnlineRemoteDeviceIds,
        expandedOfflineRemoteDeviceIds,
        canCreateRemoteBotDeviceIds,
    ) {
        if (isSuperMode) {
            emptyList()
        } else {
            buildDrawerEntries(
                workspaces = orderedWorkspaces,
                sessionsByCwd = sessionsByCwd,
                expandedWorkspaceIds = expandedWorkspaceIds,
                expandedSessionWorkspaceIds = expandedSessionWorkspaceIds,
                remoteDrawerStates = remoteDrawerStates,
                relayOnline = relayOnline,
                remoteSessionManageWorkspaceIds = remoteSessionManageWorkspaceIds,
                remoteShowArchivedWorkspaceIds = remoteShowArchivedWorkspaceIds,
                collapsedOnlineRemoteDeviceIds = collapsedOnlineRemoteDeviceIds,
                expandedOfflineRemoteDeviceIds = expandedOfflineRemoteDeviceIds,
                canCreateRemoteBotDeviceIds = canCreateRemoteBotDeviceIds,
            )
        }
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(304.dp)
            .background(colors.paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = DdSpacing.Xxl)
            .testTag("drawer_panel"),
    ) {
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PiBrandMark(
                size = 30.dp,
                modifier = Modifier.testTag("drawer_brand"),
            )
            Spacer(Modifier.width(8.dp))
            DrawerModeSegmentedSwitcher(
                appMode = appMode,
                onClick = onToggleAppMode,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            if (appMode == AppMode.NORMAL) {
                DdIconButton(
                    icon = DdIcons.Search,
                    onClick = onOpenSearch,
                    contentDescription = stringResource(R.string.drawer_search),
                    modifier = Modifier.testTag("drawer_search"),
                    size = 44.dp,
                    iconSize = 20.dp,
                    tint = colors.inkSecondary,
                )
            } else {
                Spacer(Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        if (appMode == AppMode.SUPER) {
            SuperDrawerBody(
                superWorkers = superWorkers,
                superBlueprints = superBlueprints,
                activeWorkerId = activeWorkerId,
                onOpenSuperTeam = onOpenSuperTeam,
                onOpenMemory = onOpenMemory,
                onOpenRelay = onOpenRelay,
                onOpenTimers = onOpenTimers,
                onOpenWorkerMonitor = onOpenWorkerMonitor,
                onDeleteWorker = onDeleteWorker,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
        val newChatWorkspaceId = preferredNewChatWorkspaceId(workspaces, activeWorkspaceId)
        var featuresExpanded by rememberSaveable { mutableStateOf(false) }
        DrawerNewChatButton(
            onClick = { newChatWorkspaceId?.let(onNewSessionInWorkspace) },
        )
        Spacer(Modifier.height(DdSpacing.Sm))
        DrawerFeaturesSection(
            expanded = featuresExpanded,
            onToggle = { featuresExpanded = !featuresExpanded },
            onOpenMiniapp = onOpenMiniapp,
            onOpenSkills = onOpenSkills,
            onOpenPlugins = onOpenPlugins,
            onOpenMemory = onOpenMemory,
            onOpenRelay = onOpenRelay,
            onOpenMcp = onOpenMcp,
            onOpenTimers = onOpenTimers,
        )
        Spacer(Modifier.height(DdSpacing.Md))
        DdHairline(color = colors.hairline.copy(alpha = DdAlpha.Hairline))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = DdSpacing.Sm, bottom = DdSpacing.Xs),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    DrawerEntry.WorkspacesHeader -> DrawerWorkspacesHeader(
                        onCreate = onCreateWorkspace,
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.RemoteDeviceHeader -> DrawerRemoteDeviceHeaderRow(
                        entry = entry,
                        onToggle = {
                            if (entry.online) {
                                collapsedOnlineRemoteDeviceIds = if (entry.deviceId in collapsedOnlineRemoteDeviceIds) {
                                    collapsedOnlineRemoteDeviceIds - entry.deviceId
                                } else {
                                    collapsedOnlineRemoteDeviceIds + entry.deviceId
                                }
                            } else {
                                expandedOfflineRemoteDeviceIds = if (entry.deviceId in expandedOfflineRemoteDeviceIds) {
                                    expandedOfflineRemoteDeviceIds - entry.deviceId
                                } else {
                                    expandedOfflineRemoteDeviceIds + entry.deviceId
                                }
                            }
                        },
                        onCreateBot = { onCreateWorkspaceForDevice(entry.deviceId) },
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.WorkspaceEntry -> DrawerWorkspaceRow(
                        workspace = entry.workspace,
                        meta = workspaceRowMeta(entry.workspace),
                        first = entry.first,
                        active = entry.workspace.id == activeWorkspaceId,
                        pinned = entry.workspace.id in pinnedWorkspaceIds,
                        expanded = entry.workspace.id in expandedWorkspaceIds,
                        supportsBotManage = entry.workspace.id in remoteBotManageWorkspaceIds,
                        supportsWorkspaceFiles = entry.workspace.id in remoteFilesWorkspaceIds,
                        supportsBotControls = entry.workspace.id in remoteControlsWorkspaceIds,
                        supportsBotCapabilities = entry.workspace.id in remoteCapabilitiesWorkspaceIds,
                        modifier = Modifier.animateItem(),
                        onToggleExpanded = {
                            if (entry.workspace.id in expandedWorkspaceIds) {
                                expandedSessionWorkspaceIds = expandedSessionWorkspaceIds - entry.workspace.id
                            }
                            onToggleWorkspaceExpanded(entry.workspace.id)
                        },
                        onTogglePinned = { onToggleWorkspacePinned(entry.workspace.id) },
                        onNewSession = { onNewSessionInWorkspace(entry.workspace.id) },
                        onFiles = { onOpenRemoteFiles(entry.workspace) },
                        onRemoteInstructions = { onOpenRemoteInstructions(entry.workspace) },
                        onRemoteCapabilities = { onOpenRemoteCapabilities(entry.workspace) },
                        onEditRemote = { onEditRemoteBot(entry.workspace) },
                        onSettings = { onWorkspaceSettings(entry.workspace.id) },
                        onRename = { onRenameWorkspace(entry.workspace.id) },
                        onDelete = { onDeleteWorkspace(entry.workspace.id) },
                    )

                    is DrawerEntry.WorkspaceEmpty -> DrawerWorkspaceEmptyRow(
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.RemoteLoading -> DrawerRemoteLoadingRow(
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.RemoteNotice -> DrawerRemoteNoticeRow(
                        status = entry.status,
                        onRetry = { onRetryRemoteSessions(entry.workspace) },
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.RemoteSession -> {
                        val remoteKey = entry.workspace.id to entry.session.id
                        val currentMenuKey = remoteSessionMenuTarget?.let { it.first.id to it.second.id }
                        val selected = entry.workspace.id == activeWorkspaceId &&
                            activeRemoteSessionId != null &&
                            entry.session.id == activeRemoteSessionId
                        DrawerRemoteSessionRow(
                            session = entry.session,
                            selected = selected,
                            manageable = entry.workspace.id in remoteSessionManageWorkspaceIds,
                            menuExpanded = currentMenuKey == remoteKey,
                            menuAnchor = remoteSessionMenuAnchor,
                            onClick = { onOpenRemoteSession(entry.workspace, entry.session) },
                            onLongClick = { offset ->
                                remoteSessionMenuAnchor = offset
                                remoteSessionMenuTarget = entry.workspace to entry.session
                            },
                            onDismissMenu = { remoteSessionMenuTarget = null },
                            onAction = { action ->
                                when (action) {
                                    SessionAction.RENAME ->
                                        remoteRenameTarget = entry.workspace to entry.session

                                    SessionAction.TOGGLE_PIN ->
                                        onSetRemoteSessionPinned(entry.workspace, entry.session, !entry.session.pinned)

                                    SessionAction.TOGGLE_ARCHIVE ->
                                        onSetRemoteSessionArchived(entry.workspace, entry.session, !entry.session.archived)

                                    SessionAction.DELETE ->
                                        remoteDeleteTarget = entry.workspace to entry.session
                                }
                            },
                            testTag = "drawer_remote_session_${entry.workspace.id}_${entry.session.id}",
                            modifier = Modifier.animateItem(),
                        )
                    }

                    is DrawerEntry.RemoteArchivedToggle -> DrawerSessionsToggleRow(
                        text = stringResource(
                            if (entry.showingArchived) R.string.drawer_remote_hide_archived else R.string.drawer_remote_show_archived,
                        ),
                        icon = DdIcons.Archive,
                        rotation = 0f,
                        testTag = "drawer_remote_archived_${entry.workspace.id}",
                        onClick = { onToggleRemoteShowArchived(entry.workspace) },
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.SessionMore -> DrawerSessionsToggleRow(
                        text = stringResource(R.string.drawer_sessions_more, entry.remaining),
                        icon = DdIcons.ChevronDown,
                        rotation = 0f,
                        testTag = "drawer_sessions_more_${entry.workspaceId}",
                        onClick = {
                            expandedSessionWorkspaceIds = expandedSessionWorkspaceIds + entry.workspaceId
                        },
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.SessionLess -> DrawerSessionsToggleRow(
                        text = stringResource(R.string.drawer_sessions_less),
                        icon = DdIcons.ChevronDown,
                        rotation = 180f,
                        testTag = "drawer_sessions_less_${entry.workspaceId}",
                        onClick = {
                            expandedSessionWorkspaceIds = expandedSessionWorkspaceIds - entry.workspaceId
                        },
                        modifier = Modifier.animateItem(),
                    )

                    is DrawerEntry.Session -> DrawerSessionRow(
                        conversation = entry.conversation,
                        selected = entry.conversation.id == activeSessionId,
                        busy = entry.conversation.id in busySessionIds,
                        hasTimer = entry.conversation.id in timerSessionIds,
                        hasJob = entry.conversation.id in jobSessionIds,
                        menuExpanded = sessionMenuTarget?.id == entry.conversation.id,
                        menuAnchor = sessionMenuAnchor,
                        modifier = Modifier.animateItem(),
                        onClick = { onConversation(entry.conversation.id) },
                        onLongClick = { offset ->
                            sessionMenuAnchor = offset
                            sessionMenuTarget = entry.conversation
                        },
                        onDismissMenu = { sessionMenuTarget = null },
                        onAction = { action ->
                            when (action) {
                                SessionAction.RENAME -> renameTarget = entry.conversation
                                SessionAction.TOGGLE_PIN ->
                                    onSetPinned(entry.conversation.id, !entry.conversation.pinned)

                                SessionAction.TOGGLE_ARCHIVE ->
                                    onSetArchived(entry.conversation.id, !entry.conversation.archived)

                                SessionAction.DELETE -> deleteTarget = entry.conversation
                            }
                        },
                        testTag = "recent_${entry.conversation.id}",
                    )
                }
            }
        }
        }
        DdHairline(color = colors.hairline.copy(alpha = DdAlpha.Hairline))
        Spacer(Modifier.height(DdSpacing.Sm))
        DrawerAccountRow(
            userName = userName,
            userSubtitle = userSubtitle,
            avatar = avatar,
            hasUpdate = hasUpdate,
            onClick = onSettings,
        )
        Spacer(Modifier.height(DdSpacing.Lg))
    }
    renameTarget?.let { session ->
        RenameSessionDialog(
            initialTitle = session.title,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                onRename(session.id, title)
            },
        )
    }
    deleteTarget?.let { session ->
        DeleteSessionDialog(
            sessionTitle = session.title,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                onDelete(session.id)
            },
        )
    }
    remoteRenameTarget?.let { (workspace, session) ->
        RenameSessionDialog(
            initialTitle = session.title,
            onDismiss = { remoteRenameTarget = null },
            onConfirm = { title ->
                remoteRenameTarget = null
                onRenameRemoteSession(workspace, session, title)
            },
        )
    }
    remoteDeleteTarget?.let { (workspace, session) ->
        DeleteSessionDialog(
            sessionTitle = session.title,
            onDismiss = { remoteDeleteTarget = null },
            onConfirm = {
                remoteDeleteTarget = null
                onDeleteRemoteSession(workspace, session)
            },
        )
    }
}

@Composable
private fun DrawerWorkspacesHeader(
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.workspace_section),
            modifier = Modifier.weight(1f),
            style = DdType.chip,
            color = colors.inkTertiary,
            maxLines = 1,
        )
        DdPressable(
            onClick = onCreate,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(end = DdSpacing.Xs)
                .testTag("drawer_workspace_create"),
            shape = DdShapes.Small,
            contentDescription = stringResource(R.string.drawer_workspace_create_short),
        ) {
            Text(
                text = stringResource(R.string.drawer_workspace_create_short),
                modifier = Modifier.padding(horizontal = DdSpacing.Sm, vertical = 5.dp),
                style = DdType.bodySmall.copy(fontSize = 12.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private fun remoteDevicePlatformIcon(platform: String): DdIcon {
    val p = platform.trim().lowercase()
    return when {
        p.contains("mac") || p.contains("darwin") || p.contains("apple") -> DdIcons.Apple
        p.contains("win") -> DdIcons.Windows
        p.contains("android") || p.contains("ios") || p.contains("phone") || p.contains("mobile") -> DdIcons.Mobile
        else -> DdIcons.Desktop
    }
}

@Composable
private fun DrawerRemoteDeviceHeaderRow(
    entry: DrawerEntry.RemoteDeviceHeader,
    onToggle: () -> Unit,
    onCreateBot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val toggleCd = stringResource(R.string.drawer_remote_device_toggle, entry.deviceName)
    DdPressable(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = DdSpacing.Sm)
            .heightIn(min = 44.dp)
            .testTag("drawer_remote_device_${entry.deviceId}"),
        shape = RoundedCornerShape(10.dp),
        contentDescription = toggleCd,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = DdSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(
                icon = remoteDevicePlatformIcon(entry.devicePlatform),
                tint = if (entry.online && entry.expanded) colors.inkSecondary else colors.inkTertiary,
                size = 15.dp,
            )
            Spacer(Modifier.width(6.dp))
            if (entry.online) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.success),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = entry.deviceName,
                modifier = Modifier.weight(1f),
                style = DdType.chip,
                color = if (entry.online) colors.inkSecondary else colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.online) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.drawer_remote_device_offline_badge),
                    style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            } else if (entry.canCreateBot) {
                DdIconButton(
                    icon = DdIcons.Plus,
                    onClick = onCreateBot,
                    contentDescription = stringResource(R.string.drawer_remote_device_add_bot, entry.deviceName),
                    modifier = Modifier.testTag("drawer_remote_device_add_${entry.deviceId}"),
                    size = 44.dp,
                    iconSize = 15.dp,
                    tint = colors.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun DrawerNewChatButton(onClick: () -> Unit) {
    val colors = superPiColors()
    val label = stringResource(R.string.drawer_new_chat)
    DdPressable(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("drawer_new_chat"),
        shape = DdShapes.Segmented,
        background = colors.accent,
        contentDescription = label,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DdIcon(icon = DdIcons.Compose, tint = colors.onAccent, size = 17.dp)
            Text(
                text = label,
                style = DdType.label.copy(fontSize = 15.5.sp),
                color = colors.onAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerWorkspaceRow(
    workspace: RuntimeWorkspace,
    meta: WorkspaceRowMeta,
    first: Boolean,
    active: Boolean,
    pinned: Boolean,
    expanded: Boolean,
    supportsBotManage: Boolean,
    supportsWorkspaceFiles: Boolean,
    supportsBotControls: Boolean,
    supportsBotCapabilities: Boolean,
    onToggleExpanded: () -> Unit,
    onTogglePinned: () -> Unit,
    onNewSession: () -> Unit,
    onFiles: () -> Unit,
    onRemoteInstructions: () -> Unit,
    onRemoteCapabilities: () -> Unit,
    onEditRemote: () -> Unit,
    onSettings: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.05f else 0f,
        animationSpec = if (pressed || !motionEnabled) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "workspace-row-wash",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "workspace-chevron",
    )
    var menuOpen by remember { mutableStateOf(false) }
    val menuActions = workspaceMenuActions(
        workspace = workspace,
        supportsBotManage = supportsBotManage,
        supportsWorkspaceFiles = supportsWorkspaceFiles,
        supportsBotControls = supportsBotControls,
        supportsBotCapabilities = supportsBotCapabilities,
    )
    Box(modifier.fillMaxWidth().padding(top = if (first) DdSpacing.None else 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.ink.copy(alpha = wash))
                .then(
                    if (meta.selectable) {
                        Modifier.pointerInput(workspace.id) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    interaction.emit(press)
                                    if (tryAwaitRelease()) {
                                        interaction.emit(PressInteraction.Release(press))
                                    } else {
                                        interaction.emit(PressInteraction.Cancel(press))
                                    }
                                },
                                onTap = { onToggleExpanded() },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { alpha = if (meta.offline) DdAlpha.Disabled else 1f }
                .testTag("workspace_${workspace.id}")
                .padding(start = 10.dp, end = DdSpacing.None),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatar(workspace = workspace, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = workspace.name,
                    style = DdType.label.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    color = when {
                        meta.offline -> colors.inkTertiary
                        active -> colors.accent
                        else -> colors.ink
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sourceDevice = meta.sourceDevice
                if (sourceDevice != null) {
                    Text(
                        text = if (meta.offline) {
                            stringResource(R.string.workspace_remote_offline_annotation, sourceDevice)
                        } else {
                            sourceDevice
                        },
                        style = DdType.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (meta.selectable) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdIcon(
                    icon = DdIcons.ChevronRight,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                    tint = if (active) colors.accent else colors.inkTertiary,
                    size = 13.dp,
                )
            }
            DdIconButton(
                icon = DdIcons.More,
                onClick = { menuOpen = true },
                contentDescription = stringResource(R.string.drawer_workspace_more),
                modifier = Modifier.testTag("workspace_more_${workspace.id}"),
                size = 44.dp,
                iconSize = 18.dp,
                tint = colors.inkTertiary,
            )
        }
        DdMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(x = 52.dp, y = DdSpacing.None),
            menuWidth = 196.dp,
        ) {
            menuActions.forEach { action ->
                when (action) {
                    WorkspaceMenuAction.NEW_SESSION -> DdMenuItem(
                        text = stringResource(R.string.drawer_action_new_session),
                        icon = DdIcons.Compose,
                        testTag = "workspace_menu_new_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onNewSession()
                        },
                    )

                    WorkspaceMenuAction.FILES -> DdMenuItem(
                        text = stringResource(R.string.drawer_workspace_files),
                        icon = DdIcons.Folder,
                        testTag = "workspace_menu_files_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onFiles()
                        },
                    )

                    WorkspaceMenuAction.INSTRUCTIONS -> DdMenuItem(
                        text = stringResource(R.string.bot_instructions),
                        icon = DdIcons.File,
                        testTag = "workspace_menu_instructions_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onRemoteInstructions()
                        },
                    )

                    WorkspaceMenuAction.CAPABILITIES -> DdMenuItem(
                        text = stringResource(R.string.bot_capabilities),
                        icon = DdIcons.Puzzle,
                        testTag = "workspace_menu_capabilities_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onRemoteCapabilities()
                        },
                    )

                    WorkspaceMenuAction.EDIT_REMOTE -> DdMenuItem(
                        text = stringResource(R.string.drawer_workspace_edit),
                        icon = DdIcons.Pen,
                        testTag = "workspace_menu_edit_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onEditRemote()
                        },
                    )

                    WorkspaceMenuAction.RENAME -> DdMenuItem(
                        text = stringResource(R.string.drawer_action_rename),
                        icon = DdIcons.Compose,
                        testTag = "workspace_menu_rename_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )

                    WorkspaceMenuAction.PIN -> DdMenuItem(
                        text = stringResource(if (pinned) R.string.drawer_action_unpin else R.string.drawer_action_pin),
                        icon = DdIcons.Pin,
                        testTag = "workspace_menu_pin_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onTogglePinned()
                        },
                    )

                    WorkspaceMenuAction.SETTINGS -> DdMenuItem(
                        text = stringResource(R.string.drawer_workspace_settings),
                        icon = DdIcons.Settings,
                        testTag = "workspace_menu_settings_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onSettings()
                        },
                    )

                    WorkspaceMenuAction.DELETE -> DdMenuItem(
                        text = stringResource(R.string.drawer_action_delete),
                        icon = DdIcons.Trash,
                        destructive = true,
                        testTag = "workspace_menu_delete_${workspace.id}",
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSessionsToggleRow(
    text: String,
    icon: DdIcon,
    rotation: Float,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        contentDescription = text,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(
                icon = icon,
                tint = colors.inkTertiary,
                size = 13.dp,
                modifier = Modifier.graphicsLayer { rotationZ = rotation },
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = DdType.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerWorkspaceEmptyRow(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    Text(
        text = stringResource(R.string.drawer_workspace_empty),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        style = DdType.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        color = colors.inkTertiary,
        maxLines = 1,
    )
}

@Composable
private fun DrawerRemoteLoadingRow(modifier: Modifier = Modifier) {
    val colors = superPiColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 34.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DdSpinner(
            color = colors.inkTertiary,
            size = 13.dp,
            strokeWidth = 1.5.dp,
        )
        Text(
            text = stringResource(R.string.drawer_remote_loading),
            style = DdType.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = colors.inkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DrawerRemoteNoticeRow(
    status: RemoteDrawerStatus,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val text = when (status) {
        RemoteDrawerStatus.Loading -> stringResource(R.string.drawer_remote_loading)
        RemoteDrawerStatus.Offline -> stringResource(R.string.drawer_remote_device_offline)
        RemoteDrawerStatus.RelayUpgradeRequired -> stringResource(R.string.drawer_remote_relay_upgrade)
        RemoteDrawerStatus.PeerUpgradeRequired -> stringResource(R.string.drawer_remote_peer_upgrade)
        is RemoteDrawerStatus.Error -> status.message?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.drawer_remote_load_failed)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 34.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = colors.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (status is RemoteDrawerStatus.Error) {
            DdPressable(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 44.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.drawer_remote_retry),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = DdType.label.copy(fontSize = 12.5.sp),
                    color = colors.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DrawerRemoteSessionRow(
    session: RemoteSessionSummaryDto,
    selected: Boolean = false,
    manageable: Boolean,
    menuExpanded: Boolean,
    menuAnchor: Offset,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onAction: (SessionAction) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val haptic = LocalHapticFeedback.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.06f else 0f,
        animationSpec = if (pressed || !motionEnabled) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "remote-session-row-wash",
    )
    val relativeTime = remember(session.updatedAt) { session.updatedAt }.let { updatedAt ->
        if (updatedAt > 0L) rememberRelativeTime(updatedAt) else ""
    }
    val displayTitle = session.title.trim().let { title ->
        if (title.isEmpty() || title == "Untitled") {
            stringResource(R.string.drawer_new_chat)
        } else {
            title
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pointerInput(manageable, session.id) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interaction.emit(press)
                        if (tryAwaitRelease()) {
                            interaction.emit(PressInteraction.Release(press))
                        } else {
                            interaction.emit(PressInteraction.Cancel(press))
                        }
                    },
                    onTap = { onClick() },
                    onLongPress = if (manageable) {
                        { offset ->
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick(offset)
                        }
                    } else {
                        null
                    },
                )
            }
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 2.dp, top = 3.dp, bottom = 3.dp)
                .heightIn(min = 36.dp)
                .clip(DdShapes.Small)
                .background(if (selected) colors.wash else colors.ink.copy(alpha = wash))
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayTitle,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (selected) FontWeight.Medium else null,
                ),
                color = when {
                    selected -> colors.ink
                    session.archived -> colors.inkTertiary
                    else -> colors.inkSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.archived) {
                Spacer(Modifier.width(DdSpacing.Xs))
                Text(
                    text = stringResource(R.string.drawer_remote_archived_badge),
                    style = DdType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (session.busy) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdSpinner(
                    color = colors.accent,
                    size = 12.dp,
                    strokeWidth = 1.5.dp,
                )
            } else if (session.pinned) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdIcon(
                    icon = DdIcons.Pin,
                    tint = colors.inkTertiary,
                    size = 12.dp,
                )
            }
            if (relativeTime.isNotEmpty() && !session.pinned) {
                Spacer(Modifier.width(DdSpacing.Sm))
                Text(
                    text = relativeTime,
                    style = DdType.bodySmall.copy(fontSize = 10.5.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (manageable) {
            SessionActionMenu(
                session = ConversationSummary(
                    id = session.id,
                    title = displayTitle,
                    updatedAt = session.updatedAt,
                    filePath = "",
                    cwd = "",
                    pinned = session.pinned,
                    archived = session.archived,
                ),
                expanded = menuExpanded,
                onDismissRequest = onDismissMenu,
                onAction = onAction,
                anchor = menuAnchor,
            )
        }
    }
}

@Composable
private fun DrawerFeaturesSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenMiniapp: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenTimers: () -> Unit,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val featuresLabel = stringResource(R.string.drawer_nav_features)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = if (motionEnabled) tween(180) else snap(),
        label = "drawer_features_chevron",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        DdPressable(
            onClick = onToggle,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .border(0.5.dp, colors.surfaceRim, DdShapes.Medium)
                .testTag("drawer_features_toggle"),
            shape = DdShapes.Medium,
            background = colors.surface,
            contentDescription = featuresLabel,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DdIcon(
                    icon = DdIcons.Apps,
                    tint = colors.inkSecondary,
                    size = 17.dp,
                )
                Text(
                    text = featuresLabel,
                    modifier = Modifier.weight(1f),
                    style = DdType.label.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = colors.ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                DdIcon(
                    icon = DdIcons.ChevronDown,
                    tint = colors.inkTertiary,
                    size = 15.dp,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }

        if (motionEnabled) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(6.dp))
                    DrawerShortcutsGrid(
                        onOpenMiniapp = onOpenMiniapp,
                        onOpenSkills = onOpenSkills,
                        onOpenPlugins = onOpenPlugins,
                        onOpenMemory = onOpenMemory,
                        onOpenRelay = onOpenRelay,
                        onOpenMcp = onOpenMcp,
                        onOpenTimers = onOpenTimers,
                    )
                }
            }
        } else if (expanded) {
            Spacer(Modifier.height(6.dp))
            DrawerShortcutsGrid(
                onOpenMiniapp = onOpenMiniapp,
                onOpenSkills = onOpenSkills,
                onOpenPlugins = onOpenPlugins,
                onOpenMemory = onOpenMemory,
                onOpenRelay = onOpenRelay,
                onOpenMcp = onOpenMcp,
                onOpenTimers = onOpenTimers,
            )
        }
    }
}

@Composable
private fun DrawerShortcutsGrid(
    onOpenMiniapp: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenTimers: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DrawerShortcutTile(
                icon = DdIcons.Apps,
                label = stringResource(R.string.drawer_nav_miniapp),
                onClick = onOpenMiniapp,
                testTag = "drawer_miniapp",
                modifier = Modifier.weight(1f),
            )
            DrawerShortcutTile(
                icon = DdIcons.Book,
                label = stringResource(R.string.drawer_nav_skills),
                onClick = onOpenSkills,
                testTag = "drawer_skills",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DrawerShortcutTile(
                icon = DdIcons.Puzzle,
                label = stringResource(R.string.drawer_nav_plugins),
                onClick = onOpenPlugins,
                testTag = "drawer_plugins",
                modifier = Modifier.weight(1f),
            )
            DrawerShortcutTile(
                icon = DdIcons.Brain,
                label = stringResource(R.string.drawer_nav_memory),
                onClick = onOpenMemory,
                testTag = "drawer_memory",
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DrawerShortcutTile(
                icon = DdIcons.Phone,
                label = stringResource(R.string.drawer_nav_relay),
                onClick = onOpenRelay,
                testTag = "drawer_relay",
                modifier = Modifier.weight(1f),
            )
            DrawerShortcutTile(
                icon = DdIcons.Plug,
                label = stringResource(R.string.drawer_nav_mcp),
                onClick = onOpenMcp,
                testTag = "drawer_mcp",
                modifier = Modifier.weight(1f),
            )
        }
        DrawerShortcutTile(
            icon = DdIcons.Clock,
            label = stringResource(R.string.drawer_nav_timer),
            onClick = onOpenTimers,
            testTag = "drawer_timer",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DrawerShortcutTile(
    icon: DdIcon,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 44.dp)
            .border(0.5.dp, colors.surfaceRim, DdShapes.Medium)
            .testTag(testTag),
        shape = DdShapes.Medium,
        background = colors.surface,
        contentDescription = label,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DdIcon(
                icon = icon,
                tint = colors.inkSecondary,
                size = 17.dp,
            )
            Text(
                text = label,
                style = DdType.label.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                color = colors.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerNavItem(
    icon: DdIcon,
    label: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag),
        shape = DdShapes.Medium,
        contentDescription = label,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DdIcon(
                icon = icon,
                tint = colors.inkTertiary,
                size = 20.dp,
            )
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = DdType.body.copy(fontSize = 15.5.sp, lineHeight = 21.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}

@Composable
private fun DrawerSessionRow(
    conversation: ConversationSummary,
    selected: Boolean,
    busy: Boolean,
    hasTimer: Boolean,
    hasJob: Boolean,
    menuExpanded: Boolean,
    menuAnchor: Offset,
    onClick: () -> Unit,
    onLongClick: (Offset) -> Unit,
    onDismissMenu: () -> Unit,
    onAction: (SessionAction) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val haptic = LocalHapticFeedback.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.06f else 0f,
        animationSpec = if (pressed || !motionEnabled) snap() else tween(DdMotion.PRESS_OUT, easing = DdMotion.EaseOut),
        label = "session-row-wash",
    )
    val relativeTime = rememberRelativeTime(conversation.updatedAt)
    val timerMark = stringResource(R.string.drawer_session_has_timer)
    val jobMark = stringResource(R.string.drawer_session_has_job)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .pointerInput(conversation.id) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interaction.emit(press)
                        if (tryAwaitRelease()) {
                            interaction.emit(PressInteraction.Release(press))
                        } else {
                            interaction.emit(PressInteraction.Cancel(press))
                        }
                    },
                    onTap = { currentOnClick() },
                    onLongPress = { offset ->
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnLongClick(offset)
                    },
                )
            }
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 2.dp, top = 3.dp, bottom = 3.dp)
                .heightIn(min = 36.dp)
                .clip(DdShapes.Small)
                .background(if (selected) colors.wash else colors.ink.copy(alpha = wash))
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall.copy(
                    fontSize = 13.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = if (selected) FontWeight.Medium else null,
                ),
                color = if (selected) colors.ink else colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Box(
                modifier = Modifier.widthIn(min = 40.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (hasTimer) {
                        DdIcon(
                            icon = DdIcons.Clock,
                            tint = colors.inkTertiary,
                            size = 11.dp,
                            contentDescription = timerMark,
                            modifier = Modifier.testTag("session_timer_${conversation.id}"),
                        )
                    }
                    if (hasJob) {
                        DdIcon(
                            icon = DdIcons.Terminal,
                            tint = colors.inkTertiary,
                            size = 11.dp,
                            contentDescription = jobMark,
                            modifier = Modifier.testTag("session_job_${conversation.id}"),
                        )
                    }
                    when {
                        busy -> DdSpinner(
                            modifier = Modifier.testTag("session_busy_${conversation.id}"),
                            color = colors.accent,
                            size = 12.dp,
                            strokeWidth = 1.5.dp,
                        )

                        conversation.pinned -> DdIcon(
                            icon = DdIcons.Pin,
                            tint = colors.inkTertiary,
                            size = 12.dp,
                        )

                        else -> Text(
                            text = relativeTime,
                            style = DdType.bodySmall.copy(fontSize = 10.5.sp),
                            color = colors.inkTertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
        SessionActionMenu(
            session = conversation,
            expanded = menuExpanded,
            onDismissRequest = onDismissMenu,
            onAction = onAction,
            anchor = menuAnchor,
        )
    }
}

@Composable
private fun DrawerAccountRow(
    userName: String,
    userSubtitle: String,
    avatar: ImageBitmap?,
    hasUpdate: Boolean,
    onClick: () -> Unit,
) {
    val colors = superPiColors()
    val subtitle = userSubtitle.ifBlank { stringResource(R.string.drawer_account_hint) }
    DdPressable(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("drawer_account"),
        shape = DdShapes.Segmented,
        contentDescription = userName,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarBadge(avatar = avatar, name = userName, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = userName,
                    style = DdType.label,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = DdType.micro.copy(letterSpacing = 0.2.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            Box(Modifier.size(18.dp)) {
                DdIcon(
                    icon = DdIcons.Settings,
                    tint = colors.inkTertiary,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.Center).testTag("drawer_account_settings"),
                )
                if (hasUpdate) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .background(colors.signal, CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerModeSegmentedSwitcher(
    appMode: AppMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val family = LocalDdThemeVisualFamily.current
    val motionEnabled = LocalDdMotionEnabled.current
    val options = remember { listOf(AppMode.NORMAL, AppMode.SUPER) }
    val isSuper = appMode == AppMode.SUPER
    val selectedIndex = if (isSuper) 1 else 0
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (motionEnabled) DdMotion.SegmentedSpring else snap(),
        label = "drawer-mode-segment",
    )
    val superPillStyle = remember(family, colors) {
        resolveSuperModePillStyle(family, colors)
    }
    val contentDesc = stringResource(
        if (isSuper) {
            R.string.super_mode_switch_to_normal
        } else {
            R.string.super_mode_switch_to_super
        },
    )
    val directionSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    BoxWithConstraints(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(colors.wash)
            .semantics { stateDescription = contentDesc }
            .testTag("drawer_mode_switch"),
    ) {
        val segmentWidth = maxWidth / 2f
        val thumbColor = if (colors.dark) colors.raised else colors.surface
        Box(
            Modifier
                .width(segmentWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = size.width * animatedIndex * directionSign }
                .padding(3.dp)
                .then(
                    if (isSuper) {
                        Modifier
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                ambientColor = superPillStyle.glow,
                                spotColor = superPillStyle.glow,
                                clip = false,
                            )
                            .clip(CircleShape)
                            .background(Brush.horizontalGradient(superPillStyle.gradient))
                            .border(0.75.dp, Color.White.copy(alpha = 0.24f), CircleShape)
                            .superModeShimmer(active = true, motionEnabled = motionEnabled)
                    } else {
                        Modifier
                            .then(
                                if (colors.dark) {
                                    Modifier.border(0.75.dp, colors.surfaceRim, CircleShape)
                                } else {
                                    Modifier.shadow(2.dp, CircleShape, clip = false)
                                },
                            )
                            .clip(CircleShape)
                            .background(thumbColor)
                    },
                ),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val targetTextColor = when {
                    isSelected && option == AppMode.SUPER -> Color(0xFFFAFAFC)
                    isSelected -> colors.ink
                    else -> colors.inkTertiary
                }
                val textColor by animateColorAsState(
                    targetValue = targetTextColor,
                    animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
                    label = "drawer-mode-text-$index",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { if (!isSelected) onClick() },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        )
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (option == AppMode.SUPER) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            DdIcon(
                                icon = DdIcons.Sparkles,
                                tint = textColor,
                                size = 13.dp,
                            )
                            Text(
                                text = "Super PI",
                                style = DdType.chip.copy(
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                ),
                                color = textColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = "PI",
                            style = DdType.chip.copy(
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = textColor,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperDrawerBody(
    superWorkers: List<WorkerRunRecord>,
    superBlueprints: List<BotBlueprint>,
    activeWorkerId: String? = null,
    onOpenSuperTeam: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenTimers: () -> Unit,
    onOpenWorkerMonitor: (String) -> Unit,
    onDeleteWorker: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    var deleteTarget by remember { mutableStateOf<WorkerRunRecord?>(null) }
    val enabledCount = remember(superBlueprints) { superBlueprints.count { it.enabledInSuper } }
    val activeWorkers = remember(superWorkers) {
        superWorkers.filter { it.status.isActive }.sortedByDescending { it.updatedAt }
    }
    val savedWorkers = remember(superWorkers) {
        superWorkers.filterNot { it.status.isActive }.sortedByDescending { it.updatedAt }
    }
    val slotCountsByBotRef = remember(superWorkers, superBlueprints) {
        val counts = superWorkers.groupingBy { it.botRef }.eachCount().toMutableMap()
        superBlueprints.forEach { bp ->
            if (bp.usedWorkerSlots > 0 && bp.ref !in counts) {
                counts[bp.ref] = bp.usedWorkerSlots
            }
        }
        counts
    }
    val maxSlotsByBotRef = remember(superBlueprints) {
        superBlueprints.associate { it.ref to it.maxWorkerSlots }
    }
    Column(modifier) {
        DrawerNavItem(
            icon = DdIcons.Sparkles,
            label = stringResource(R.string.super_drawer_team),
            onClick = onOpenSuperTeam,
            testTag = "drawer_super_team",
            trailing = {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.wash)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.super_drawer_team_enabled_count, enabledCount),
                        style = DdType.chip,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                }
            },
        )
        DrawerNavItem(
            icon = DdIcons.Brain,
            label = stringResource(R.string.drawer_nav_memory),
            onClick = onOpenMemory,
            testTag = "drawer_super_memory",
        )
        DrawerNavItem(
            icon = DdIcons.Phone,
            label = stringResource(R.string.drawer_nav_relay),
            onClick = onOpenRelay,
            testTag = "drawer_super_relay",
        )
        DrawerNavItem(
            icon = DdIcons.Clock,
            label = stringResource(R.string.drawer_nav_timer),
            onClick = onOpenTimers,
            testTag = "drawer_super_timer",
        )
        Spacer(Modifier.height(14.dp))
        DdHairline(color = colors.hairline.copy(alpha = DdAlpha.Hairline))
        if (activeWorkers.isEmpty() && savedWorkers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = DdSpacing.Xxl),
                contentAlignment = Alignment.TopCenter,
            ) {
                DdEmptyState(
                    text = stringResource(R.string.super_drawer_workers_empty),
                    icon = DdIcons.Sparkles,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = DdSpacing.Sm, bottom = DdSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (activeWorkers.isNotEmpty()) {
                    item(key = "super_active_header") {
                        Text(
                            text = stringResource(R.string.super_drawer_workers_active),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    items(activeWorkers, key = { "active_${it.workerId}" }) { worker ->
                        SuperDrawerWorkerRow(
                            worker = worker,
                            usedSlots = slotCountsByBotRef[worker.botRef] ?: 1,
                            maxSlots = maxSlotsByBotRef[worker.botRef] ?: SuperRuntime.MAX_WORKERS_PER_BOT,
                            selected = worker.workerId == activeWorkerId,
                            onClick = { onOpenWorkerMonitor(worker.workerId) },
                            onRequestDelete = if (!worker.status.isBusyForCommands) {
                                { deleteTarget = worker }
                            } else {
                                null
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (savedWorkers.isNotEmpty()) {
                    item(key = "super_saved_header") {
                        Text(
                            text = stringResource(R.string.super_drawer_workers_saved),
                            style = DdType.chip,
                            color = colors.inkTertiary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    items(savedWorkers, key = { "saved_${it.workerId}" }) { worker ->
                        SuperDrawerWorkerRow(
                            worker = worker,
                            usedSlots = slotCountsByBotRef[worker.botRef] ?: 1,
                            maxSlots = maxSlotsByBotRef[worker.botRef] ?: SuperRuntime.MAX_WORKERS_PER_BOT,
                            selected = worker.workerId == activeWorkerId,
                            onClick = { onOpenWorkerMonitor(worker.workerId) },
                            onRequestDelete = { deleteTarget = worker },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    deleteTarget?.let { target ->
        DdDialog(
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.super_worker_delete_title),
            content = {
                DdText(
                    text = stringResource(R.string.super_worker_delete_body, target.title),
                    style = DdType.body,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.super_worker_cancel),
                    onClick = { deleteTarget = null },
                )
                DdTextAction(
                    text = stringResource(R.string.super_worker_delete_confirm),
                    color = colors.danger,
                    onClick = {
                        deleteTarget = null
                        onDeleteWorker(target.workerId)
                    },
                )
            },
        )
    }
}

@Composable
private fun SuperDrawerWorkerRow(
    worker: WorkerRunRecord,
    usedSlots: Int,
    maxSlots: Int,
    selected: Boolean = false,
    onClick: () -> Unit,
    onRequestDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val relativeTime = rememberRelativeTime(worker.updatedAt)
    val thisDeviceLabel = stringResource(R.string.super_drawer_this_device)
    val effectiveMaxSlots = maxSlots.coerceAtLeast(1)
    val slotText = stringResource(
        R.string.super_team_slots,
        usedSlots.coerceIn(0, effectiveMaxSlots),
        effectiveMaxSlots,
    )
    val subtitle = buildString {
        append(worker.botName)
        if (worker.isRemote && worker.deviceName.isNotBlank()) {
            append(" · ")
            append(worker.deviceName)
        } else {
            append(" · ")
            append(thisDeviceLabel)
        }
        append(" · ")
        append(slotText)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("drawer_worker_${worker.workerId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdPressable(
            onClick = onClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            shape = DdShapes.Medium,
            background = if (selected) colors.wash else Color.Transparent,
            contentDescription = worker.title,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BotAvatar(
                    name = worker.botName,
                    avatar = worker.botAvatar,
                    size = 28.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = worker.title,
                        style = DdType.bodySmall.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (selected) FontWeight.Medium else null,
                        ),
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
                when (worker.status) {
                    WorkerStatus.RUNNING -> DdSpinner(
                        color = colors.accent,
                        size = 13.dp,
                        strokeWidth = 1.5.dp,
                    )

                    WorkerStatus.QUEUED,
                    WorkerStatus.WAITING_INPUT,
                    -> Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.signal),
                    )

                    WorkerStatus.STALLED -> Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.warning),
                    )

                    WorkerStatus.FAILED -> Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.danger),
                    )

                    WorkerStatus.IDLE -> Text(
                        text = relativeTime,
                        style = DdType.bodySmall.copy(fontSize = 10.5.sp),
                        color = colors.inkTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        if (onRequestDelete != null) {
            DdIconButton(
                icon = DdIcons.Trash,
                onClick = onRequestDelete,
                contentDescription = stringResource(R.string.super_drawer_worker_delete),
                modifier = Modifier.testTag("drawer_worker_delete_${worker.workerId}"),
                size = 44.dp,
                iconSize = 16.dp,
                tint = colors.inkTertiary,
            )
        }
    }
}

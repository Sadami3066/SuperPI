package io.github.whywood.superpi.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.whywood.superpi.R
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.AttachmentExport
import io.github.whywood.superpi.data.BotAvatarStore
import io.github.whywood.superpi.data.AttachmentRef
import io.github.whywood.superpi.data.exportShareIntent
import io.github.whywood.superpi.plugin.host.services.PluginPageRequest
import io.github.whywood.superpi.remote.RemoteAdminResult
import io.github.whywood.superpi.remote.RemoteControlResult
import io.github.whywood.superpi.runtime.JobSnapshot
import io.github.whywood.superpi.runtime.JobStatus
import io.github.whywood.superpi.runtime.RuntimeWorkspace
import io.github.whywood.superpi.ui.chat.ChatViewModel
import io.github.whywood.superpi.ui.common.chat.AttachmentSource
import io.github.whywood.superpi.ui.common.chat.AttachmentSourceSheet
import io.github.whywood.superpi.ui.common.chat.CameraCaptureSheet
import io.github.whywood.superpi.ui.common.chat.ChatUiState
import io.github.whywood.superpi.ui.common.chat.rememberAttachmentPickers
import io.github.whywood.superpi.ui.common.encodeAvatarBitmapDataUrl
import io.github.whywood.superpi.ui.common.shareChooser
import io.github.whywood.superpi.ui.workspaces.RemoteAgentsDocSheet
import io.github.whywood.superpi.ui.workspaces.RemoteBotCapabilitiesSheet
import io.github.whywood.superpi.ui.workspaces.RemoteHostTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



internal class AppOverlaysState {
    var attachmentSheetOpen by mutableStateOf(false)
    var cameraSheetOpen by mutableStateOf(false)
    var pluginPage by mutableStateOf<PluginPageRequest?>(null)
    var previewAttachment by mutableStateOf<AttachmentRef?>(null)
    var previewRoot by mutableStateOf<File?>(null)
    var usageSheetOpen by mutableStateOf(false)
    var jobsSheetOpen by mutableStateOf(false)
    var selectedJobId by mutableStateOf<String?>(null)
    var jobOutput by mutableStateOf<String?>(null)
    var workspaceCreateOpen by mutableStateOf(false)
    var workspaceCreateDeviceId by mutableStateOf<String?>(null)
    var workspaceRenameId by mutableStateOf<String?>(null)
    var remoteBotEditTarget by mutableStateOf<RuntimeWorkspace?>(null)
    var remoteAgentsDocTarget by mutableStateOf<RuntimeWorkspace?>(null)
    var remoteCapabilitiesTarget by mutableStateOf<RuntimeWorkspace?>(null)
    var workspaceDeleteTarget by mutableStateOf<RuntimeWorkspace?>(null)
    var workspaceDeleteCount by mutableIntStateOf(0)
    var remoteOpSubmitting by mutableStateOf(false)
    var remoteOpError by mutableStateOf<String?>(null)

    companion object {
        val Saver: Saver<AppOverlaysState, Any> = listSaver(
            save = {
                listOf(
                    it.attachmentSheetOpen,
                    it.cameraSheetOpen,
                    it.workspaceCreateOpen,
                    it.workspaceRenameId ?: "",
                    it.usageSheetOpen,
                )
            },
            restore = { saved ->
                AppOverlaysState().apply {
                    attachmentSheetOpen = saved[0] as Boolean
                    cameraSheetOpen = saved[1] as Boolean
                    workspaceCreateOpen = saved[2] as Boolean
                    workspaceRenameId = (saved[3] as String).ifBlank { null }
                    usageSheetOpen = saved[4] as Boolean
                }
            },
        )
    }
}

@Composable
internal fun AppOverlays(
    graph: AppDependencies,
    chatViewModel: ChatViewModel,
    state: ChatUiState,
    activeJobs: List<JobSnapshot>,
    attachmentRoot: File,
    drawer: ShellDrawerState,
    drawerActions: ShellDrawerActions,
    overlays: AppOverlaysState,
    remoteTargets: List<RemoteHostTarget> = emptyList(),
    remoteBotNamesByDevice: Map<String, List<String>> = emptyMap(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privateWorkspacesDir = remember(context) { File(context.filesDir, "workspaces").absolutePath }
    val shareChooserTitle = stringResource(R.string.assistant_attach_share)
    val exportDone = stringResource(R.string.files_export_done)
    val exportDonePathFormat = stringResource(R.string.files_export_done_path)
    val remoteOfflineText = stringResource(R.string.drawer_remote_device_offline)
    val remoteUpgradeText = stringResource(R.string.drawer_remote_peer_upgrade)
    val remoteTimeoutText = stringResource(R.string.drawer_remote_timeout)
    val remoteActionFailedText = stringResource(R.string.drawer_remote_action_failed)
    fun localizeAdminError(code: String, fallback: String): String = when (code) {
        "not_connected", "disconnected", "device_offline" -> remoteOfflineText
        "capability_unavailable" -> remoteUpgradeText
        "timeout" -> remoteTimeoutText
        else -> fallback.ifBlank { remoteActionFailedText }
    }
    val attachmentPickers = rememberAttachmentPickers { uri -> chatViewModel.attach(uri) }
    val botAvatarStore = remember { BotAvatarStore(context) }
    LaunchedEffect(state.workspaces, state.ready) {
        if (!state.ready || state.workspaces.isEmpty()) return@LaunchedEffect
        val avatars = state.workspaces.mapNotNull { it.avatar }
        withContext(Dispatchers.IO) { botAvatarStore.cleanup(avatars) }
    }
    LaunchedEffect(overlays.jobsSheetOpen, overlays.selectedJobId) {
        val id = overlays.selectedJobId
        if (!overlays.jobsSheetOpen || id == null) return@LaunchedEffect
        while (true) {
            val job = graph.jobRegistry.jobs.value.firstOrNull { it.id == id } ?: break
            overlays.jobOutput = runCatching { graph.jobRegistry.read(id).output }.getOrNull()
            if (job.status != JobStatus.RUNNING) break
            delay(800)
        }
    }
    OverlayHost(
        overlays = ShellOverlayState(
            pluginPage = overlays.pluginPage,
            workspaceCreate = ShellOverlay.WorkspaceCreate(
                existingNames = state.workspaces.filter { it.remote == null }.map { it.name },
                privateDirectory = privateWorkspacesDir,
                remoteTargets = remoteTargets,
                remoteBotNamesByDevice = remoteBotNamesByDevice,
                initialTargetDeviceId = overlays.workspaceCreateDeviceId,
                submitting = overlays.remoteOpSubmitting,
                remoteError = overlays.remoteOpError,
            ).takeIf { overlays.workspaceCreateOpen },
            workspaceRename = overlays.workspaceRenameId
                ?.let { id -> state.workspaces.firstOrNull { it.id == id } }
                ?.let { target ->
                    ShellOverlay.WorkspaceRename(
                        target.id,
                        target.name,
                        state.workspaces.filterNot { it.id == target.id }.map { it.name },
                    )
                },
            remoteBotEdit = overlays.remoteBotEditTarget?.let { target ->
                val deviceId = target.remote?.deviceId
                val deviceNames = if (deviceId != null) {
                    remoteBotNamesByDevice[deviceId].orEmpty().filterNot { it == target.name }
                } else {
                    emptyList()
                }
                ShellOverlay.RemoteBotEdit(
                    workspaceId = target.id,
                    initialName = target.name,
                    initialIntro = target.intro,
                    hasAvatar = !target.avatar.isNullOrBlank(),
                    existingNames = deviceNames,
                    submitting = overlays.remoteOpSubmitting,
                    errorMessage = overlays.remoteOpError,
                )
            },
            workspaceDelete = overlays.workspaceDeleteTarget?.let {
                ShellOverlay.WorkspaceDelete(
                    workspaceId = it.id,
                    name = it.name,
                    sessionCount = overlays.workspaceDeleteCount,
                    isRemote = it.remote != null,
                    submitting = overlays.remoteOpSubmitting,
                    errorMessage = overlays.remoteOpError,
                )
            },
            attachmentPreview = overlays.previewAttachment?.let {
                ShellOverlay.AttachmentPreview(it, graph.attachmentStore.file(it, overlays.previewRoot ?: attachmentRoot))
            },
            usage = state.usage?.takeIf { overlays.usageSheetOpen }?.let(ShellOverlay::Usage),
            jobs = ShellOverlay.Jobs(activeJobs, overlays.selectedJobId, overlays.jobOutput).takeIf { overlays.jobsSheetOpen },
        ),
        overlayActions = ShellOverlayActions(
            onDismissPluginPage = { overlays.pluginPage = null },
            onConfirmWorkspaceCreate = { name, directory, avatar, intro ->
                overlays.workspaceCreateOpen = false
                overlays.workspaceCreateDeviceId = null
                scope.launch {
                    val avatarPath = avatar?.let { botAvatarStore.save(it) }
                    chatViewModel.createWorkspace(name, directory, avatarPath, intro) {
                        Toast.makeText(context, R.string.bot_created_toast, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismissWorkspaceCreate = {
                overlays.workspaceCreateOpen = false
                overlays.workspaceCreateDeviceId = null
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
            },
            onConfirmWorkspaceCreateRemote = { target, name, avatar, intro ->
                overlays.remoteOpSubmitting = true
                overlays.remoteOpError = null
                scope.launch {
                    val dataUrl = avatar?.let { withContext(Dispatchers.IO) { encodeAvatarBitmapDataUrl(it) } }
                    when (val res = graph.remoteBotAdminClient.createBot(target.deviceId, name, dataUrl, intro)) {
                        is RemoteAdminResult.Ok -> {
                            overlays.remoteOpSubmitting = false
                            overlays.remoteOpError = null
                            overlays.workspaceCreateOpen = false
                            overlays.workspaceCreateDeviceId = null
                            Toast.makeText(context, R.string.bot_created_toast, Toast.LENGTH_SHORT).show()
                        }
                        is RemoteAdminResult.Error -> {
                            overlays.remoteOpSubmitting = false
                            overlays.remoteOpError = localizeAdminError(res.code, res.message)
                        }
                    }
                }
            },
            onConfirmWorkspaceRename = { name ->
                val id = overlays.workspaceRenameId
                overlays.workspaceRenameId = null
                id?.let { chatViewModel.renameWorkspace(it, name) }
            },
            onDismissWorkspaceRename = { overlays.workspaceRenameId = null },
            onConfirmRemoteBotEdit = { nextName, nextIntro, clearIntro, avatarBitmap, clearAvatar ->
                val target = overlays.remoteBotEditTarget
                val remote = target?.remote
                if (target != null && remote != null) {
                    overlays.remoteOpSubmitting = true
                    overlays.remoteOpError = null
                    scope.launch {
                        val dataUrl = avatarBitmap?.let { withContext(Dispatchers.IO) { encodeAvatarBitmapDataUrl(it) } }
                        when (
                            val res = graph.remoteBotAdminClient.updateBot(
                                deviceId = remote.deviceId,
                                botId = remote.botId,
                                name = nextName,
                                avatarDataUrl = dataUrl,
                                clearAvatar = clearAvatar,
                                intro = nextIntro,
                                clearIntro = clearIntro,
                            )
                        ) {
                            is RemoteAdminResult.Ok -> {
                                overlays.remoteOpSubmitting = false
                                overlays.remoteOpError = null
                                overlays.remoteBotEditTarget = null
                            }
                            is RemoteAdminResult.Error -> {
                                overlays.remoteOpSubmitting = false
                                overlays.remoteOpError = localizeAdminError(res.code, res.message)
                            }
                        }
                    }
                }
            },
            onDismissRemoteBotEdit = {
                overlays.remoteBotEditTarget = null
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
            },
            onConfirmWorkspaceDelete = {
                val target = overlays.workspaceDeleteTarget
                val remote = target?.remote
                if (target != null && remote != null) {
                    overlays.remoteOpSubmitting = true
                    overlays.remoteOpError = null
                    scope.launch {
                        when (val res = graph.remoteBotAdminClient.deleteBot(remote.deviceId, remote.botId)) {
                            is RemoteAdminResult.Ok -> {
                                overlays.remoteOpSubmitting = false
                                overlays.remoteOpError = null
                                overlays.workspaceDeleteTarget = null
                                chatViewModel.onRemoteBotDeleted(target.id)
                            }
                            is RemoteAdminResult.Error -> {
                                overlays.remoteOpSubmitting = false
                                overlays.remoteOpError = localizeAdminError(res.code, res.message)
                            }
                        }
                    }
                } else {
                    overlays.workspaceDeleteTarget = null
                    target?.let { workspace ->
                        chatViewModel.deleteWorkspace(workspace.id) {
                            scope.launch(Dispatchers.IO) { botAvatarStore.delete(workspace.avatar) }
                        }
                    }
                }
            },
            onDismissWorkspaceDelete = {
                overlays.workspaceDeleteTarget = null
                overlays.remoteOpSubmitting = false
                overlays.remoteOpError = null
            },
            onDismissAttachmentPreview = { overlays.previewAttachment = null; overlays.previewRoot = null },
            onShareAttachmentPreview = {
                scope.launch {
                    overlays.previewAttachment?.let { attachment ->
                        graph.attachmentStore.shareIntent(attachment, overlays.previewRoot ?: attachmentRoot)
                    }?.let { context.startActivity(shareChooser(context, it, shareChooserTitle)) }
                }
            },
            onExportAttachmentPreview = {
                scope.launch {
                    val attachment = overlays.previewAttachment ?: return@launch
                    when (val export = graph.attachmentStore.export(attachment, overlays.previewRoot ?: attachmentRoot)) {
                        null -> Toast.makeText(context, R.string.files_export_failed, Toast.LENGTH_SHORT).show()
                        is AttachmentExport.SharedStorage -> {
                            Toast.makeText(
                                context,
                                String.format(exportDonePathFormat, export.file.absolutePath),
                                Toast.LENGTH_LONG,
                            ).show()
                            exportShareIntent(context, export, attachment.name, attachment.mime)
                                ?.let { context.startActivity(shareChooser(context, it, shareChooserTitle)) }
                        }
                        is AttachmentExport.Download -> Toast.makeText(
                            context,
                            R.string.files_export_done,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
            onDismissUsage = { overlays.usageSheetOpen = false },
            onSelectJob = { id ->
                val same = overlays.selectedJobId == id
                overlays.selectedJobId = if (same) null else id
                overlays.jobOutput = null
                if (!same) scope.launch { overlays.jobOutput = graph.jobRegistry.read(id).output }
            },
            onCloseJobOutput = { overlays.selectedJobId = null; overlays.jobOutput = null },
            onKillJob = { id -> scope.launch { graph.jobRegistry.kill(id, null) } },
            onClearSettledJobs = { graph.jobRegistry.clearSettled() },
            onDismissJobs = { overlays.jobsSheetOpen = false },
        ),
        drawer = drawer,
        drawerActions = drawerActions,
    )
    if (overlays.attachmentSheetOpen) {
        AttachmentSourceSheet(
            onDismiss = { overlays.attachmentSheetOpen = false },
            onPick = { source ->
                overlays.attachmentSheetOpen = false
                when (source) {
                    AttachmentSource.IMAGE -> attachmentPickers.pickImages()
                    AttachmentSource.FILE -> attachmentPickers.pickFiles()
                    AttachmentSource.CAMERA -> overlays.cameraSheetOpen = true
                }
            },
        )
    }
    if (overlays.cameraSheetOpen) {
        CameraCaptureSheet(
            onDismiss = { overlays.cameraSheetOpen = false },
            onCaptured = { uri ->
                overlays.cameraSheetOpen = false
                chatViewModel.attach(uri)
            },
        )
    }
    overlays.remoteAgentsDocTarget?.let { target ->
        val remote = target.remote
        if (remote != null) {
            RemoteAgentsDocSheet(
                botName = remote.botName,
                deviceName = remote.deviceName,
                loadContent = {
                    when (val res = graph.remoteBotControlClient.readInstructions(remote.deviceId, remote.botId)) {
                        is RemoteControlResult.Ok -> Result.success(res.value.content)
                        is RemoteControlResult.Error ->
                            Result.failure(IllegalStateException(localizeAdminError(res.code, res.message)))
                    }
                },
                saveContent = { content ->
                    when (val res = graph.remoteBotControlClient.writeInstructions(remote.deviceId, remote.botId, content)) {
                        is RemoteControlResult.Ok -> {
                            Toast.makeText(context, R.string.agents_doc_remote_saved, Toast.LENGTH_SHORT).show()
                            Result.success(res.value.content)
                        }
                        is RemoteControlResult.Error ->
                            Result.failure(IllegalStateException(localizeAdminError(res.code, res.message)))
                    }
                },
                onDismiss = { overlays.remoteAgentsDocTarget = null },
            )
        }
    }
    overlays.remoteCapabilitiesTarget?.let { target ->
        val remote = target.remote
        if (remote != null) {
            RemoteBotCapabilitiesSheet(
                deviceId = remote.deviceId,
                botId = remote.botId,
                botName = remote.botName,
                deviceName = remote.deviceName,
                syncEvents = graph.remoteTransport.syncEvents,
                loadCapabilities = {
                    when (val res = graph.remoteBotControlClient.getCapabilities(remote.deviceId, remote.botId)) {
                        is RemoteControlResult.Ok -> Result.success(res.value)
                        is RemoteControlResult.Error ->
                            Result.failure(IllegalStateException(localizeAdminError(res.code, res.message)))
                    }
                },
                saveCapabilities = { nextPolicy ->
                    when (val res = graph.remoteBotControlClient.setCapabilities(remote.deviceId, remote.botId, nextPolicy)) {
                        is RemoteControlResult.Ok -> Result.success(res.value)
                        is RemoteControlResult.Error ->
                            Result.failure(IllegalStateException(localizeAdminError(res.code, res.message)))
                    }
                },
                onDismiss = { overlays.remoteCapabilitiesTarget = null },
            )
        }
    }
}

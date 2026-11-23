package io.github.whywood.superpi.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.FilePreviewKind
import io.github.whywood.superpi.data.filePreviewKind
import io.github.whywood.superpi.data.formatAttachmentSize
import io.github.whywood.superpi.data.guessMime
import io.github.whywood.superpi.design.DdDialog
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMenu
import io.github.whywood.superpi.design.DdMenuItem
import io.github.whywood.superpi.design.DdPageScaffold
import io.github.whywood.superpi.design.DdProgressBar
import io.github.whywood.superpi.design.DdRowDivider
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdTextField
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.remote.RemoteAgentTransport
import io.github.whywood.superpi.remote.RemoteFileClient
import io.github.whywood.superpi.remote.RemoteFileItem
import io.github.whywood.superpi.remote.RemoteFileResult
import io.github.whywood.superpi.ui.common.chat.FileViewerDialog
import io.github.whywood.superpi.ui.common.rememberHapticFeedback
import io.github.whywood.superpi.ui.common.shareChooser
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class RemoteFileNameValidation {
    VALID,
    EMPTY,
    INVALID,
}

internal object RemoteFileBrowserLogic {
    fun sortEntries(entries: List<RemoteFileItem>): List<RemoteFileItem> = entries.sortedWith(
        compareByDescending<RemoteFileItem> { it.isDirectory }
            .thenBy { it.name.lowercase(Locale.US) },
    )

    fun parentPath(path: String): String {
        val clean = normalize(path)
        if (clean.isEmpty()) return ""
        val idx = clean.lastIndexOf('/')
        return if (idx <= 0) "" else clean.substring(0, idx)
    }

    fun childPath(dir: String, name: String): String {
        val cleanDir = normalize(dir)
        val cleanName = name.trim().trim('/')
        return when {
            cleanName.isEmpty() -> cleanDir
            cleanDir.isEmpty() -> cleanName
            else -> "$cleanDir/$cleanName"
        }
    }

    fun validateEntryName(raw: String): RemoteFileNameValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return RemoteFileNameValidation.EMPTY
        if (trimmed == "." || trimmed == ".." || '/' in trimmed || '\\' in trimmed) {
            return RemoteFileNameValidation.INVALID
        }
        if (trimmed.any { it.code < 32 }) return RemoteFileNameValidation.INVALID
        return RemoteFileNameValidation.VALID
    }

    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val clean = normalize(path)
        if (clean.isEmpty()) return emptyList()
        val parts = clean.split('/').filter { it.isNotEmpty() }
        val result = ArrayList<Pair<String, String>>(parts.size)
        var current = ""
        for (part in parts) {
            current = if (current.isEmpty()) part else "$current/$part"
            result += part to current
        }
        return result
    }

    fun isOfflineCode(code: String): Boolean =
        code == "not_connected" || code == "disconnected" || code == "device_offline"

    fun isUpgradeCode(code: String): Boolean =
        code == "capability_unavailable" || code == "upgrade_required" || code == "unsupported_method"

    private fun normalize(path: String): String =
        path.trim().trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")
}

private sealed interface RemoteFileListingUi {
    data object Loading : RemoteFileListingUi
    data object Offline : RemoteFileListingUi
    data object UpgradeRequired : RemoteFileListingUi
    data class Failed(val message: String) : RemoteFileListingUi
    data class Ready(val entries: List<RemoteFileItem>) : RemoteFileListingUi
}

private data class RemoteTransferUi(
    val uploading: Boolean,
    val fileName: String,
    val transferred: Long,
    val total: Long,
)

private data class RemotePreviewTarget(
    val item: RemoteFileItem,
    val file: File,
)

private data class PendingUploadOverwrite(
    val uri: Uri,
    val name: String,
    val size: Long?,
    val targetPath: String,
)

private data class UploadSourceMeta(
    val name: String,
    val size: Long?,
)

@Composable
fun RemoteFileBrowserScreen(
    deviceId: String,
    deviceName: String,
    botId: String,
    botName: String,
    remoteFileClient: RemoteFileClient,
    transport: RemoteAgentTransport,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val isRelayOnline by transport.isRelayOnline.collectAsStateWithLifecycle()
    val devices by transport.devices.collectAsStateWithLifecycle()
    val deviceOnline = isRelayOnline && (devices.firstOrNull { it.deviceId == deviceId }?.online == true)
    val supportsFiles = remember(devices, deviceOnline, deviceId) {
        transport.supportsPeerCapability(deviceId, RemoteFileClient.CAP_WORKSPACE_FILES)
    }
    var currentPath by rememberSaveable(deviceId, botId) { mutableStateOf("") }
    var searchQuery by rememberSaveable(deviceId, botId) { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<RemoteFileListingUi>(RemoteFileListingUi.Loading) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var rowMenuTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteFileItem?>(null) }
    var dialogSubmitting by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var transferUi by remember { mutableStateOf<RemoteTransferUi?>(null) }
    var transferJob by remember { mutableStateOf<Job?>(null) }
    var previewTarget by remember { mutableStateOf<RemotePreviewTarget?>(null) }
    var pendingSaveItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    var pendingSaveCachedFile by remember { mutableStateOf<File?>(null) }
    var pendingUploadOverwrite by remember { mutableStateOf<PendingUploadOverwrite?>(null) }

    val loadFailedText = stringResource(R.string.files_remote_load_failed)
    val opFailedText = stringResource(R.string.files_remote_op_failed)
    val tooLargeText = stringResource(R.string.files_remote_too_large)
    val offlineText = stringResource(R.string.files_remote_offline)
    val upgradeText = stringResource(R.string.files_remote_upgrade)
    val shareFailedText = stringResource(R.string.files_share_failed)
    val saveFailedText = stringResource(R.string.files_save_failed)
    val shareChooserTitle = stringResource(R.string.files_share)
    val remoteCacheDir = remember(context) { File(context.cacheDir, "remote-files") }

    DisposableEffect(Unit) {
        onDispose { transferJob?.cancel() }
    }

    LaunchedEffect(searchQuery) {
        delay(200)
        debouncedQuery = searchQuery.trim()
    }

    LaunchedEffect(currentPath, debouncedQuery, refreshEpoch, deviceOnline, supportsFiles) {
        if (!deviceOnline) {
            listing = RemoteFileListingUi.Offline
            return@LaunchedEffect
        }
        if (!supportsFiles) {
            listing = RemoteFileListingUi.UpgradeRequired
            return@LaunchedEffect
        }
        listing = RemoteFileListingUi.Loading
        val queryArg = debouncedQuery.takeIf { it.isNotEmpty() }
        listing = when (val result = remoteFileClient.list(deviceId, botId, currentPath, queryArg)) {
            is RemoteFileResult.Ok -> RemoteFileListingUi.Ready(RemoteFileBrowserLogic.sortEntries(result.value.entries))
            is RemoteFileResult.Error -> when {
                RemoteFileBrowserLogic.isOfflineCode(result.code) -> RemoteFileListingUi.Offline
                RemoteFileBrowserLogic.isUpgradeCode(result.code) -> RemoteFileListingUi.UpgradeRequired
                else -> RemoteFileListingUi.Failed(result.message.ifBlank { loadFailedText })
            }
        }
    }

    fun errorMessageFor(code: String, fallback: String): String = when {
        code == "payload_too_large" -> tooLargeText
        RemoteFileBrowserLogic.isOfflineCode(code) -> offlineText
        RemoteFileBrowserLogic.isUpgradeCode(code) -> upgradeText
        else -> fallback
    }

    fun startTransfer(
        uploading: Boolean,
        fileName: String,
        totalBytes: Long,
        block: suspend (onProgress: (Long, Long) -> Unit) -> Unit,
    ) {
        transferJob?.cancel()
        transferUi = RemoteTransferUi(uploading, fileName, 0L, totalBytes)
        lateinit var launched: Job
        launched = scope.launch {
            try {
                block { transferred, total ->
                    transferUi = RemoteTransferUi(
                        uploading = uploading,
                        fileName = fileName,
                        transferred = transferred,
                        total = maxOf(total, totalBytes),
                    )
                }
            } finally {
                if (transferJob === launched) {
                    transferJob = null
                    transferUi = null
                }
            }
        }
        transferJob = launched
    }

    fun launchUpload(
        uri: Uri,
        name: String,
        size: Long?,
        targetPath: String,
        overwrite: Boolean,
    ) {
        startTransfer(uploading = true, fileName = name, totalBytes = size ?: 0L) { onProgress ->
            val result = withContext(Dispatchers.IO) {
                val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?: return@withContext RemoteFileResult.Error("io_error", opFailedText)
                input.use { stream ->
                    remoteFileClient.uploadFromStream(
                        deviceId = deviceId,
                        botId = botId,
                        remotePath = targetPath,
                        inputStream = stream,
                        totalBytes = size,
                        overwrite = overwrite,
                        onProgress = { progress -> onProgress(progress.transferred, progress.total) },
                    )
                }
            }
            when (result) {
                is RemoteFileResult.Ok -> {
                    Toast.makeText(context, resources.getString(R.string.files_remote_upload_done, name), Toast.LENGTH_SHORT).show()
                    refreshEpoch += 1
                }
                is RemoteFileResult.Error -> {
                    if (!overwrite && result.code == "already_exists") {
                        pendingUploadOverwrite = PendingUploadOverwrite(
                            uri = uri,
                            name = name,
                            size = size,
                            targetPath = targetPath,
                        )
                    } else {
                        Toast.makeText(context, errorMessageFor(result.code, opFailedText), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val meta = queryUploadMeta(context, uri)
        if (meta.size != null && meta.size > RemoteFileClient.MAX_TRANSFER_BYTES) {
            Toast.makeText(context, tooLargeText, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val targetPath = RemoteFileBrowserLogic.childPath(currentPath, meta.name)
        launchUpload(
            uri = uri,
            name = meta.name,
            size = meta.size,
            targetPath = targetPath,
            overwrite = false,
        )
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val item = pendingSaveItem
        val cached = pendingSaveCachedFile
        pendingSaveItem = null
        pendingSaveCachedFile = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        if (cached != null && cached.isFile) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val out = context.contentResolver.openOutputStream(uri) ?: return@runCatching false
                        out.use { stream -> cached.inputStream().use { input -> input.copyTo(stream) } }
                        true
                    }.getOrDefault(false)
                }
                Toast.makeText(
                    context,
                    if (ok) resources.getString(R.string.files_remote_save_done, item.name) else saveFailedText,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@rememberLauncherForActivityResult
        }
        startTransfer(uploading = false, fileName = item.name, totalBytes = item.size) { onProgress ->
            val result = withContext(Dispatchers.IO) {
                val out = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
                    ?: return@withContext RemoteFileResult.Error("io_error", saveFailedText)
                out.use { stream ->
                    remoteFileClient.downloadToStream(
                        deviceId = deviceId,
                        botId = botId,
                        remotePath = item.path,
                        outputStream = stream,
                        onProgress = { progress -> onProgress(progress.transferred, progress.total) },
                    )
                }
            }
            when (result) {
                is RemoteFileResult.Ok -> {
                    Toast.makeText(context, resources.getString(R.string.files_remote_save_done, item.name), Toast.LENGTH_SHORT).show()
                }
                is RemoteFileResult.Error -> {
                    Toast.makeText(context, errorMessageFor(result.code, saveFailedText), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openRemoteFile(entry: RemoteFileItem) {
        if (entry.size > RemoteFileClient.MAX_TRANSFER_BYTES) {
            Toast.makeText(context, tooLargeText, Toast.LENGTH_SHORT).show()
            return
        }
        startTransfer(uploading = false, fileName = entry.name, totalBytes = entry.size) { onProgress ->
            val result = remoteFileClient.downloadToCacheFile(
                deviceId = deviceId,
                botId = botId,
                remotePath = entry.path,
                cacheDir = remoteCacheDir,
                onProgress = { progress -> onProgress(progress.transferred, progress.total) },
            )
            when (result) {
                is RemoteFileResult.Ok -> previewTarget = RemotePreviewTarget(entry, result.value)
                is RemoteFileResult.Error -> {
                    Toast.makeText(context, errorMessageFor(result.code, opFailedText), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun saveRemoteFileCopy(entry: RemoteFileItem) {
        if (entry.size > RemoteFileClient.MAX_TRANSFER_BYTES) {
            Toast.makeText(context, tooLargeText, Toast.LENGTH_SHORT).show()
            return
        }
        pendingSaveItem = entry
        pendingSaveCachedFile = null
        saveDocumentLauncher.launch(entry.name)
    }

    val canGoUp = currentPath.isNotEmpty()
    val goUp: () -> Unit = {
        searchQuery = ""
        currentPath = RemoteFileBrowserLogic.parentPath(currentPath)
    }

    BackHandler(enabled = canGoUp && previewTarget == null) {
        goUp()
    }

    DdPageScaffold(
        topBar = {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onBack,
                contentDescription = stringResource(R.string.files_back),
                tint = colors.ink,
                iconSize = 21.dp,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            Column(Modifier.weight(1f)) {
                Text(
                    text = botName,
                    style = DdType.heading,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$deviceName · ${stringResource(R.string.files_remote_title)}",
                    style = DdType.micro,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DdIconButton(
                icon = DdIcons.Refresh,
                onClick = { refreshEpoch += 1 },
                contentDescription = stringResource(R.string.files_refresh),
                tint = colors.inkSecondary,
                iconSize = 18.dp,
                modifier = Modifier.testTag("remote_files_refresh"),
            )
            Box {
                DdIconButton(
                    icon = DdIcons.More,
                    onClick = { topMenuExpanded = true },
                    contentDescription = stringResource(R.string.files_more_actions),
                    tint = colors.inkSecondary,
                    iconSize = 18.dp,
                    modifier = Modifier.testTag("remote_files_more"),
                )
                DdMenu(
                    expanded = topMenuExpanded,
                    onDismissRequest = { topMenuExpanded = false },
                    menuWidth = 180.dp,
                ) {
                    DdMenuItem(
                        text = stringResource(R.string.files_upload),
                        icon = DdIcons.Plus,
                        testTag = "remote_files_upload",
                        onClick = {
                            topMenuExpanded = false
                            if (deviceOnline && supportsFiles) uploadLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DdMenuItem(
                        text = stringResource(R.string.files_new_folder),
                        icon = DdIcons.Folder,
                        testTag = "remote_files_new_folder",
                        onClick = {
                            topMenuExpanded = false
                            if (deviceOnline && supportsFiles) {
                                dialogError = null
                                newFolderOpen = true
                            }
                        },
                    )
                }
            }
        },
        modifier = modifier,
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Xxl),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = DdSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.files_search_placeholder),
                    modifier = Modifier.weight(1f),
                    testTag = "remote_files_search",
                )
                if (searchQuery.isNotEmpty()) {
                    Spacer(Modifier.width(DdSpacing.Xs))
                    DdIconButton(
                        icon = DdIcons.Close,
                        onClick = { searchQuery = "" },
                        contentDescription = stringResource(R.string.files_search_clear),
                        tint = colors.inkTertiary,
                        iconSize = 16.dp,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Sm))
            RemoteBreadcrumbsRow(
                currentPath = currentPath,
                canGoUp = canGoUp,
                onSelectPath = { target ->
                    searchQuery = ""
                    currentPath = target
                },
                onUp = goUp,
            )
            transferUi?.let { transfer ->
                Spacer(Modifier.height(DdSpacing.Xs))
                TransferBanner(
                    transfer = transfer,
                    onCancel = {
                        transferJob?.cancel()
                        transferJob = null
                        transferUi = null
                    },
                )
            }
            Spacer(Modifier.height(DdSpacing.Xs))
            DdRowDivider()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(DdSpacing.Xs))
                when (val current = listing) {
                    RemoteFileListingUi.Loading -> RemoteFilesLoading()
                    RemoteFileListingUi.Offline -> RemoteFilesStatusMessage(
                        icon = DdIcons.Folder,
                        text = stringResource(R.string.files_remote_offline),
                        actionLabel = stringResource(R.string.files_retry),
                        onAction = { refreshEpoch += 1 },
                    )
                    RemoteFileListingUi.UpgradeRequired -> RemoteFilesStatusMessage(
                        icon = DdIcons.Info,
                        text = stringResource(R.string.files_remote_upgrade),
                    )
                    is RemoteFileListingUi.Failed -> RemoteFilesStatusMessage(
                        icon = DdIcons.Folder,
                        text = current.message,
                        actionLabel = stringResource(R.string.files_retry),
                        onAction = { refreshEpoch += 1 },
                    )
                    is RemoteFileListingUi.Ready -> {
                        if (current.entries.isEmpty()) {
                            RemoteFilesStatusMessage(
                                icon = DdIcons.Folder,
                                text = stringResource(
                                    if (debouncedQuery.isEmpty()) R.string.files_empty else R.string.drawer_search_empty,
                                ),
                            )
                        } else {
                            current.entries.forEachIndexed { index, entry ->
                                if (index > 0) DdRowDivider()
                                RemoteFileRow(
                                    entry = entry,
                                    menuExpanded = rowMenuTarget?.path == entry.path,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            searchQuery = ""
                                            currentPath = entry.path
                                        } else {
                                            openRemoteFile(entry)
                                        }
                                    },
                                    onOpenMenu = { rowMenuTarget = entry },
                                    onDismissMenu = { rowMenuTarget = null },
                                    onPreview = {
                                        rowMenuTarget = null
                                        openRemoteFile(entry)
                                    },
                                    onSaveCopy = {
                                        rowMenuTarget = null
                                        saveRemoteFileCopy(entry)
                                    },
                                    onRename = {
                                        rowMenuTarget = null
                                        dialogError = null
                                        renameTarget = entry
                                    },
                                    onDelete = {
                                        rowMenuTarget = null
                                        dialogError = null
                                        deleteTarget = entry
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(DdSpacing.Xxl))
            }
        }
    }

    previewTarget?.let { preview ->
        FileViewerDialog(
            file = preview.file,
            name = preview.item.name,
            mime = preview.item.mimeType ?: guessMime(preview.item.name),
            onDismiss = { previewTarget = null },
            onShare = {
                val shareUri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", preview.file)
                }.getOrNull()
                if (shareUri == null) {
                    Toast.makeText(context, shareFailedText, Toast.LENGTH_SHORT).show()
                } else {
                    val mime = preview.item.mimeType ?: guessMime(preview.item.name)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime.ifBlank { "application/octet-stream" }
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_TITLE, preview.item.name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(shareChooser(context, intent, shareChooserTitle))
                }
            },
            onExport = {
                pendingSaveItem = preview.item
                pendingSaveCachedFile = preview.file
                saveDocumentLauncher.launch(preview.item.name)
            },
        )
    }

    if (newFolderOpen) {
        RemoteFileNameDialog(
            title = stringResource(R.string.files_new_folder),
            initial = "",
            placeholder = stringResource(R.string.files_new_folder_placeholder),
            confirmLabel = stringResource(R.string.files_new_folder_create),
            submitting = dialogSubmitting,
            errorMessage = dialogError,
            onDismiss = { if (!dialogSubmitting) newFolderOpen = false },
            onConfirm = { name ->
                val targetPath = RemoteFileBrowserLogic.childPath(currentPath, name)
                scope.launch {
                    dialogSubmitting = true
                    dialogError = null
                    when (val result = remoteFileClient.mkdir(deviceId, botId, targetPath)) {
                        is RemoteFileResult.Ok -> {
                            dialogSubmitting = false
                            newFolderOpen = false
                            refreshEpoch += 1
                        }
                        is RemoteFileResult.Error -> {
                            dialogSubmitting = false
                            dialogError = errorMessageFor(result.code, opFailedText)
                        }
                    }
                }
            },
        )
    }

    renameTarget?.let { target ->
        RemoteFileNameDialog(
            title = stringResource(R.string.files_rename),
            initial = target.name,
            placeholder = stringResource(R.string.files_rename_placeholder),
            confirmLabel = stringResource(R.string.files_rename_save),
            submitting = dialogSubmitting,
            errorMessage = dialogError,
            onDismiss = { if (!dialogSubmitting) renameTarget = null },
            onConfirm = { nextName ->
                if (nextName == target.name) {
                    renameTarget = null
                    return@RemoteFileNameDialog
                }
                val toPath = RemoteFileBrowserLogic.childPath(
                    RemoteFileBrowserLogic.parentPath(target.path),
                    nextName,
                )
                scope.launch {
                    dialogSubmitting = true
                    dialogError = null
                    when (val result = remoteFileClient.rename(deviceId, botId, target.path, toPath)) {
                        is RemoteFileResult.Ok -> {
                            dialogSubmitting = false
                            renameTarget = null
                            refreshEpoch += 1
                        }
                        is RemoteFileResult.Error -> {
                            dialogSubmitting = false
                            dialogError = errorMessageFor(result.code, opFailedText)
                        }
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        RemoteFileDeleteDialog(
            entry = target,
            submitting = dialogSubmitting,
            errorMessage = dialogError,
            onDismiss = { if (!dialogSubmitting) deleteTarget = null },
            onConfirm = {
                scope.launch {
                    dialogSubmitting = true
                    dialogError = null
                    when (val result = remoteFileClient.delete(deviceId, botId, target.path, recursive = target.isDirectory)) {
                        is RemoteFileResult.Ok -> {
                            dialogSubmitting = false
                            deleteTarget = null
                            refreshEpoch += 1
                        }
                        is RemoteFileResult.Error -> {
                            dialogSubmitting = false
                            dialogError = errorMessageFor(result.code, opFailedText)
                        }
                    }
                }
            },
        )
    }

    pendingUploadOverwrite?.let { pending ->
        DdDialog(
            onDismissRequest = { pendingUploadOverwrite = null },
            title = stringResource(R.string.files_upload_overwrite_title),
            content = {
                Text(
                    text = stringResource(R.string.files_upload_overwrite_message, pending.name),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            },
            actions = {
                DdTextAction(
                    text = stringResource(R.string.files_cancel),
                    onClick = { pendingUploadOverwrite = null },
                    color = colors.inkSecondary,
                )
                DdTextAction(
                    text = stringResource(R.string.files_upload_overwrite_confirm),
                    onClick = {
                        pendingUploadOverwrite = null
                        launchUpload(
                            uri = pending.uri,
                            name = pending.name,
                            size = pending.size,
                            targetPath = pending.targetPath,
                            overwrite = true,
                        )
                    },
                    color = colors.danger,
                )
            },
        )
    }
}

@Composable
private fun RemoteBreadcrumbsRow(
    currentPath: String,
    canGoUp: Boolean,
    onSelectPath: (String) -> Unit,
    onUp: () -> Unit,
) {
    val colors = superPiColors()
    val segments = remember(currentPath) { RemoteFileBrowserLogic.breadcrumbs(currentPath) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoUp) {
            DdIconButton(
                icon = DdIcons.ArrowLeft,
                onClick = onUp,
                contentDescription = stringResource(R.string.files_up),
                tint = colors.inkSecondary,
                iconSize = 16.dp,
                modifier = Modifier.testTag("remote_files_up"),
            )
            Spacer(Modifier.width(DdSpacing.Xs))
        }
        Text(
            text = "/",
            style = DdType.bodySmall,
            color = if (segments.isEmpty()) colors.ink else colors.inkSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSelectPath("") }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        segments.forEachIndexed { index, (label, fullPath) ->
            DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 12.dp)
            val isLast = index == segments.lastIndex
            Text(
                text = label,
                style = DdType.bodySmall,
                color = if (isLast) colors.ink else colors.inkSecondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = !isLast) { onSelectPath(fullPath) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TransferBanner(
    transfer: RemoteTransferUi,
    onCancel: () -> Unit,
) {
    val colors = superPiColors()
    val fraction = if (transfer.total > 0L) {
        (transfer.transferred.toFloat() / transfer.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairline.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Sm),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (transfer.uploading) R.string.files_remote_uploading else R.string.files_remote_downloading,
                    transfer.fileName,
                ),
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = if (transfer.total > 0L) {
                    "${formatAttachmentSize(transfer.transferred)} / ${formatAttachmentSize(transfer.total)}"
                } else {
                    formatAttachmentSize(transfer.transferred)
                },
                style = DdType.micro,
                color = colors.inkTertiary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(DdSpacing.Sm))
            DdTextAction(
                text = stringResource(R.string.files_cancel),
                onClick = onCancel,
                color = colors.inkSecondary,
            )
        }
        Spacer(Modifier.height(6.dp))
        DdProgressBar(
            progress = if (transfer.total > 0L) fraction else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteFileRow(
    entry: RemoteFileItem,
    menuExpanded: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onPreview: () -> Unit,
    onSaveCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = superPiColors()
    val haptics = rememberHapticFeedback()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics(HapticFeedbackType.LongPress)
                        onOpenMenu()
                    },
                )
                .testTag("remote_file_row_${entry.path}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(
                icon = if (entry.isDirectory) DdIcons.Folder else remoteEntryIcon(entry.name),
                tint = if (entry.isDirectory) colors.inkSecondary else colors.inkTertiary,
                size = 16.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = entry.name,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isDirectory) {
                Spacer(Modifier.width(DdSpacing.Sm))
                DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 14.dp)
            } else {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = formatAttachmentSize(entry.size),
                    style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = colors.inkTertiary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            DdIconButton(
                icon = DdIcons.More,
                onClick = onOpenMenu,
                contentDescription = stringResource(R.string.files_more_actions),
                tint = colors.inkTertiary,
                iconSize = 16.dp,
                modifier = Modifier.testTag("remote_file_more_${entry.path}"),
            )
        }
        DdMenu(
            expanded = menuExpanded,
            onDismissRequest = onDismissMenu,
            offset = DpOffset(x = 0.dp, y = DdSpacing.None),
            menuWidth = 176.dp,
        ) {
            if (!entry.isDirectory) {
                DdMenuItem(
                    text = stringResource(R.string.files_preview),
                    icon = DdIcons.File,
                    onClick = onPreview,
                )
                DdMenuItem(
                    text = stringResource(R.string.files_save_as),
                    icon = DdIcons.Download,
                    onClick = onSaveCopy,
                )
            }
            DdMenuItem(
                text = stringResource(R.string.files_rename),
                icon = DdIcons.Pen,
                onClick = onRename,
            )
            DdMenuItem(
                text = stringResource(R.string.files_delete),
                icon = DdIcons.Trash,
                destructive = true,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun RemoteFileNameDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirmLabel: String,
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = superPiColors()
    var value by remember(initial) { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val validation = RemoteFileBrowserLogic.validateEntryName(value)
    val submit: () -> Unit = {
        if (!submitting) {
            submitted = true
            if (validation == RemoteFileNameValidation.VALID) {
                onConfirm(value.trim())
            }
        }
    }
    DdDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column {
                DdTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = placeholder,
                    focusRequester = focusRequester,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    onDone = { submit() },
                )
                val localError = when {
                    submitted && validation == RemoteFileNameValidation.EMPTY -> stringResource(R.string.files_name_error_empty)
                    submitted && validation == RemoteFileNameValidation.INVALID -> stringResource(R.string.files_name_error_invalid)
                    else -> errorMessage
                }
                if (localError != null) {
                    Text(
                        text = localError,
                        modifier = Modifier.padding(top = 8.dp),
                        style = DdType.bodySmall,
                        color = colors.danger,
                    )
                }
            }
        },
        actions = {
            DdTextAction(
                text = stringResource(R.string.files_cancel),
                onClick = onDismiss,
                enabled = !submitting,
                color = colors.inkSecondary,
            )
            if (submitting) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DdSpinner(size = 16.dp)
                }
            } else {
                DdTextAction(
                    text = confirmLabel,
                    onClick = { submit() },
                )
            }
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun RemoteFileDeleteDialog(
    entry: RemoteFileItem,
    submitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = superPiColors()
    val haptic = rememberHapticFeedback()
    DdDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.files_delete_title),
        content = {
            Column {
                Text(
                    text = stringResource(
                        if (entry.isDirectory) R.string.files_delete_folder_message else R.string.files_delete_message,
                        entry.name,
                    ),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(top = 8.dp),
                        style = DdType.bodySmall,
                        color = colors.danger,
                    )
                }
            }
        },
        actions = {
            DdTextAction(
                text = stringResource(R.string.files_cancel),
                onClick = onDismiss,
                enabled = !submitting,
                color = colors.inkSecondary,
            )
            if (submitting) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    DdSpinner(size = 16.dp)
                }
            } else {
                DdTextAction(
                    text = stringResource(R.string.files_delete_confirm),
                    onClick = {
                        haptic(HapticFeedbackType.Reject)
                        onConfirm()
                    },
                    color = colors.danger,
                )
            }
        },
    )
}

@Composable
private fun RemoteFilesLoading() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        DdSpinner(size = 18.dp)
    }
}

@Composable
private fun RemoteFilesStatusMessage(
    icon: io.github.whywood.superpi.design.DdIcon,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = superPiColors()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DdIcon(icon = icon, tint = colors.inkTertiary, size = 22.dp)
        Spacer(Modifier.height(DdSpacing.Md))
        Text(
            text = text,
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(DdSpacing.Sm))
            DdTextAction(
                text = actionLabel,
                onClick = onAction,
            )
        }
    }
}

private fun remoteEntryIcon(name: String): io.github.whywood.superpi.design.DdIcon =
    when (filePreviewKind(name, guessMime(name))) {
        FilePreviewKind.IMAGE -> DdIcons.Image
        FilePreviewKind.VIDEO -> DdIcons.Play
        FilePreviewKind.HTML -> DdIcons.Globe
        FilePreviewKind.CODE -> DdIcons.Terminal
        FilePreviewKind.MARKDOWN, FilePreviewKind.UNSUPPORTED -> DdIcons.File
    }

private fun queryUploadMeta(context: Context, uri: Uri): UploadSourceMeta {
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
    }
    val resolvedName = name?.trim()?.takeIf { it.isNotEmpty() && !it.contains('/') }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        ?: "upload.bin"
    return UploadSourceMeta(resolvedName, size)
}

package io.github.whywood.superpi.ui.common.chat

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.SuperPIColors
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.ui.common.chat.HtmlFileView
import io.github.whywood.superpi.ui.common.chat.MiniappCardApp
import io.github.whywood.superpi.ui.common.chat.MiniappChatCard
import java.io.File
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull




enum class PresentedItemType { FILE, IMAGE, VIDEO, HTML, MINIAPP, CARD }

data class PresentedItem(
    val type: PresentedItemType,
    val name: String,
    val path: String? = null,
    val mime: String? = null,
    val title: String? = null,
    val id: String? = null,
    val icon: String? = null,
    val version: String? = null,
    val artifactId: String? = null,
    val artifactSize: Long? = null,
)

interface RemotePresentContext {
    val deviceName: String
    val supportsArtifacts: Boolean
    fun cachedFile(artifactId: String, name: String, size: Long?): File?
    suspend fun fetch(
        artifactId: String,
        name: String,
        size: Long?,
        onProgress: (Long, Long) -> Unit,
    ): Result<File>
}

fun parsePresentedItems(trace: ToolTrace): List<PresentedItem>? {
    if (trace.name != "present") return null
    val raw = trace.output?.takeIf { it.isNotBlank() } ?: return null
    return parsePresentedItems(raw)
}

internal fun parsePresentedItems(raw: String): List<PresentedItem>? = try {
    val root = Json.parseToJsonElement(raw).jsonObject
    val items = root["items"] as? JsonArray
    items?.mapNotNull(::parsePresentedItem)?.distinctBy { it.key }?.takeIf { it.isNotEmpty() }
} catch (_: Exception) {
    null
}

private fun parsePresentedItem(element: JsonElement): PresentedItem? {
    val item = element as? JsonObject ?: return null
    val type = when (item.stringOrNull("type")?.trim()?.lowercase(Locale.US)) {
        "file" -> PresentedItemType.FILE
        "image" -> PresentedItemType.IMAGE
        "video" -> PresentedItemType.VIDEO
        "html" -> PresentedItemType.HTML
        "miniapp" -> PresentedItemType.MINIAPP
        "card" -> PresentedItemType.CARD
        else -> return null
    }
    val title = item.stringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
    if (type == PresentedItemType.CARD) {
        val id = item.stringOrNull("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return PresentedItem(
            type = type,
            name = title ?: id,
            title = title,
            id = id,
        )
    }
    if (type == PresentedItemType.MINIAPP) {
        val id = item.stringOrNull("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return PresentedItem(
            type = type,
            name = item.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            title = title,
            id = id,
            icon = item.stringOrNull("icon")?.trim()?.takeIf { it.isNotEmpty() },
            version = item.stringOrNull("version")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
    val path = item.stringOrNull("path")?.trim().orEmpty()
    if (path.isEmpty()) return null
    return PresentedItem(
        type = type,
        name = item.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: derivePresentedName(path),
        path = path,
        mime = item.stringOrNull("mime")?.trim()?.takeIf { it.isNotEmpty() },
        title = title,
        artifactId = item.stringOrNull("artifactId")?.trim()?.takeIf { it.isNotEmpty() },
        artifactSize = (item["artifactSize"] as? JsonPrimitive)?.longOrNull,
    )
}

private fun derivePresentedName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

private val PresentedItem.key: String
    get() = when (type) {
        PresentedItemType.MINIAPP -> "miniapp:${id.orEmpty()}"
        PresentedItemType.CARD -> "card:${id.orEmpty()}"
        else -> "${type.name}:${path.orEmpty()}"
    }

internal fun resolvePresentedPath(raw: String, root: File): String {
    val trimmed = raw.trim().trim('<', '>', '"', '\'')
    if (trimmed.isEmpty()) return raw
    val file = when {
        trimmed.startsWith("file://") -> File(runCatching { URI(trimmed).path }.getOrNull() ?: return raw)
        trimmed.startsWith("/workspace/") -> File(root, trimmed.removePrefix("/workspace/"))
        trimmed.startsWith("/") -> File(trimmed)
        else -> File(root, trimmed.removePrefix("workspace/"))
    }
    return file.absolutePath
}

@Composable
internal fun PresentCard(
    items: List<PresentedItem>,
    attachmentRoot: File,
    onOpenFile: (String) -> Unit,
    onShareFile: (String) -> Unit,
    onOpenMiniapp: (String) -> Unit,
    modifier: Modifier = Modifier,
    resolveCard: (String) -> PresentedCardRef? = { null },
    remoteContext: RemotePresentContext? = null,
) {
    val colors = superPiColors()
    Column(
        modifier = modifier.fillMaxWidth().testTag("present_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            when (item.type) {
                PresentedItemType.IMAGE -> if (remoteContext != null) {
                    RemotePresentedImageItem(item, attachmentRoot, colors, remoteContext)
                } else {
                    PresentedImageItem(item, attachmentRoot, colors)
                }
                PresentedItemType.VIDEO -> if (remoteContext != null) {
                    RemotePresentedVideoItem(item, attachmentRoot, colors, remoteContext, onOpenFile)
                } else {
                    PresentedVideoItem(
                        item = item,
                        root = attachmentRoot,
                        colors = colors,
                        onOpen = { onOpenFile(item.path.orEmpty()) },
                    )
                }

                PresentedItemType.HTML -> if (remoteContext != null) {
                    RemotePresentedHtmlItem(item, attachmentRoot, colors, remoteContext)
                } else {
                    PresentedHtmlItem(item, attachmentRoot, colors)
                }
                PresentedItemType.FILE -> if (remoteContext != null) {
                    RemotePresentedFileItem(item, colors, remoteContext, onOpenFile, onShareFile)
                } else {
                    PresentedFileItem(
                        item = item,
                        colors = colors,
                        onOpen = { onOpenFile(item.path.orEmpty()) },
                        onShare = { onShareFile(item.path.orEmpty()) },
                    )
                }

                PresentedItemType.MINIAPP -> if (remoteContext != null) {
                    PresentedRemoteStatusRow(
                        name = item.title ?: item.name,
                        status = stringResource(R.string.present_remote_miniapp_hint, remoteContext.deviceName),
                        icon = DdIcons.Apps,
                        colors = colors,
                    )
                } else {
                    PresentedMiniappItem(item, onOpenMiniapp)
                }
                PresentedItemType.CARD -> PresentedCardItem(item, colors, resolveCard)
            }
        }
    }
}

private const val AUTO_PULL_IMAGE_MAX_BYTES = 15L * 1024L * 1024L
private const val AUTO_PULL_HTML_MAX_BYTES = 5L * 1024L * 1024L

private class RemoteArtifactState(
    val cached: File?,
    val loading: Boolean,
    val failed: Boolean,
    val downloaded: Long,
    val total: Long,
    val pull: ((File) -> Unit) -> Unit,
)

@Composable
private fun rememberRemoteArtifactState(
    item: PresentedItem,
    remoteContext: RemotePresentContext,
    autoPullMaxBytes: Long?,
): RemoteArtifactState {
    val artifactId = item.artifactId
    val scope = rememberCoroutineScope()
    var localFile by remember(artifactId, item.name, item.artifactSize, remoteContext) {
        mutableStateOf(
            artifactId?.let { remoteContext.cachedFile(it, item.name, item.artifactSize) },
        )
    }
    var loading by remember(artifactId) { mutableStateOf(false) }
    var failed by remember(artifactId) { mutableStateOf(false) }
    var downloaded by remember(artifactId) { mutableStateOf(0L) }
    var total by remember(artifactId) { mutableStateOf(item.artifactSize ?: 0L) }

    fun pullNow(onDone: (File) -> Unit) {
        val id = artifactId ?: return
        val existing = remoteContext.cachedFile(id, item.name, item.artifactSize)
        if (existing != null) {
            localFile = existing
            onDone(existing)
            return
        }
        if (loading || !remoteContext.supportsArtifacts) return
        loading = true
        failed = false
        scope.launch {
            val res = remoteContext.fetch(id, item.name, item.artifactSize) { done, size ->
                downloaded = done
                total = size
            }
            loading = false
            res.fold(
                onSuccess = { file ->
                    localFile = file
                    failed = false
                    onDone(file)
                },
                onFailure = {
                    failed = true
                },
            )
        }
    }

    LaunchedEffect(artifactId, remoteContext) {
        val id = artifactId ?: return@LaunchedEffect
        val maxBytes = autoPullMaxBytes ?: return@LaunchedEffect
        if (localFile != null || !remoteContext.supportsArtifacts) return@LaunchedEffect
        val expectedSize = item.artifactSize ?: 0L
        if (expectedSize <= maxBytes) {
            pullNow {}
        }
    }

    return RemoteArtifactState(
        cached = localFile,
        loading = loading,
        failed = failed,
        downloaded = downloaded,
        total = total,
        pull = { onDone -> pullNow(onDone) },
    )
}

@Composable
private fun RemotePresentedImageItem(
    item: PresentedItem,
    root: File,
    colors: SuperPIColors,
    remoteContext: RemotePresentContext,
) {
    val label = item.title ?: item.name
    val artifactId = item.artifactId
    if (artifactId == null) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_unavailable), DdIcons.Image, colors)
        return
    }
    val state = rememberRemoteArtifactState(item, remoteContext, AUTO_PULL_IMAGE_MAX_BYTES)
    val cached = state.cached
    if (cached != null) {
        PresentedImageItem(item.copy(path = cached.absolutePath), root, colors)
        return
    }
    if (!remoteContext.supportsArtifacts) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_upgrade), DdIcons.Image, colors)
        return
    }
    PresentedRemoteActionRow(
        title = label,
        subtitle = remoteArtifactSubtitle(item.artifactSize, state),
        icon = DdIcons.Image,
        loading = state.loading,
        colors = colors,
        onClick = { state.pull {} },
    )
}

@Composable
private fun RemotePresentedHtmlItem(
    item: PresentedItem,
    root: File,
    colors: SuperPIColors,
    remoteContext: RemotePresentContext,
) {
    val label = item.title ?: item.name
    val artifactId = item.artifactId
    if (artifactId == null) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_unavailable), DdIcons.Globe, colors)
        return
    }
    val state = rememberRemoteArtifactState(item, remoteContext, AUTO_PULL_HTML_MAX_BYTES)
    val cached = state.cached
    if (cached != null) {
        PresentedHtmlItem(item.copy(path = cached.absolutePath), root, colors)
        return
    }
    if (!remoteContext.supportsArtifacts) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_upgrade), DdIcons.Globe, colors)
        return
    }
    PresentedRemoteActionRow(
        title = label,
        subtitle = remoteArtifactSubtitle(item.artifactSize, state),
        icon = DdIcons.Globe,
        loading = state.loading,
        colors = colors,
        onClick = { state.pull {} },
    )
}

@Composable
private fun RemotePresentedVideoItem(
    item: PresentedItem,
    root: File,
    colors: SuperPIColors,
    remoteContext: RemotePresentContext,
    onOpenFile: (String) -> Unit,
) {
    val label = item.title ?: item.name
    val artifactId = item.artifactId
    if (artifactId == null) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_unavailable), DdIcons.Play, colors)
        return
    }
    val state = rememberRemoteArtifactState(item, remoteContext, autoPullMaxBytes = null)
    val cached = state.cached
    if (cached != null) {
        PresentedVideoItem(
            item = item.copy(path = cached.absolutePath),
            root = root,
            colors = colors,
            onOpen = { onOpenFile(cached.absolutePath) },
        )
        return
    }
    if (!remoteContext.supportsArtifacts) {
        PresentedRemoteStatusRow(label, stringResource(R.string.present_remote_upgrade), DdIcons.Play, colors)
        return
    }
    PresentedRemoteActionRow(
        title = label,
        subtitle = remoteArtifactSubtitle(item.artifactSize, state),
        icon = DdIcons.Play,
        loading = state.loading,
        colors = colors,
        onClick = { state.pull { file -> onOpenFile(file.absolutePath) } },
    )
}

@Composable
private fun RemotePresentedFileItem(
    item: PresentedItem,
    colors: SuperPIColors,
    remoteContext: RemotePresentContext,
    onOpenFile: (String) -> Unit,
    onShareFile: (String) -> Unit,
) {
    val artifactId = item.artifactId
    if (artifactId == null) {
        PresentedRemoteStatusRow(item.name, stringResource(R.string.present_remote_unavailable), DdIcons.File, colors)
        return
    }
    val state = rememberRemoteArtifactState(item, remoteContext, autoPullMaxBytes = null)
    val cached = state.cached
    if (cached != null) {
        PresentedFileItem(
            item = item.copy(path = cached.absolutePath),
            colors = colors,
            onOpen = { onOpenFile(cached.absolutePath) },
            onShare = { onShareFile(cached.absolutePath) },
        )
        return
    }
    if (!remoteContext.supportsArtifacts) {
        PresentedRemoteStatusRow(item.name, stringResource(R.string.present_remote_upgrade), DdIcons.File, colors)
        return
    }
    PresentedRemoteActionRow(
        title = item.name,
        subtitle = remoteArtifactSubtitle(item.artifactSize, state),
        icon = DdIcons.File,
        loading = state.loading,
        colors = colors,
        onClick = { state.pull { file -> onOpenFile(file.absolutePath) } },
        onShare = { state.pull { file -> onShareFile(file.absolutePath) } },
    )
}

@Composable
private fun remoteArtifactSubtitle(
    size: Long?,
    state: RemoteArtifactState,
): String {
    val sizeText = size?.takeIf { it > 0L }?.let(::formatArtifactSize)
    return when {
        state.loading -> {
            val pct = if (state.total > 0L) ((state.downloaded * 100L) / state.total).coerceIn(0L, 100L) else null
            if (pct != null && pct > 0L) "${stringResource(R.string.present_remote_loading)} $pct%" else stringResource(R.string.present_remote_loading)
        }
        state.failed -> stringResource(R.string.present_remote_retry)
        sizeText != null -> "$sizeText · ${stringResource(R.string.present_remote_download)}"
        else -> stringResource(R.string.present_remote_download)
    }
}

private fun formatArtifactSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
    else -> "$bytes B"
}

@Composable
private fun PresentedRemoteStatusRow(
    name: String,
    status: String,
    icon: DdIcon,
    colors: SuperPIColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = 14.dp, vertical = DdSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = icon, tint = colors.inkTertiary, size = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = status,
            style = DdType.bodySmall,
            color = colors.inkTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun PresentedRemoteActionRow(
    title: String,
    subtitle: String,
    icon: DdIcon,
    loading: Boolean,
    colors: SuperPIColors,
    onClick: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    val shape = DdShapes.Medium
    DdPressable(
        onClick = { if (!loading) onClick() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("present_remote_action"),
        shape = shape,
        background = colors.surface,
        contentDescription = subtitle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Secondary), shape)
                .padding(start = 14.dp, end = if (onShare != null) 6.dp else 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                DdSpinner(size = 16.dp, strokeWidth = 1.8.dp)
            } else {
                DdIcon(icon = icon, tint = colors.inkTertiary, size = 16.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(DdSpacing.Xxs))
                Text(
                    text = subtitle,
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onShare != null) {
                DdIconButton(
                    icon = DdIcons.Share,
                    onClick = { if (!loading) onShare() },
                    contentDescription = stringResource(R.string.present_share),
                    modifier = Modifier.testTag("present_remote_share"),
                    size = 36.dp,
                    iconSize = 15.dp,
                    tint = colors.inkTertiary,
                )
            }
        }
    }
}

@Composable
private fun PresentedCardItem(
    item: PresentedItem,
    colors: SuperPIColors,
    resolveCard: (String) -> PresentedCardRef?,
) {
    val id = item.id.orEmpty()
    val card = remember(id) { resolveCard(id) }
    val fallback: @Composable () -> Unit = {
        PresentedCardFallback(item.title ?: item.name, colors)
    }
    when (card) {
        is PresentedCardRef.Payload -> PluginCustomMessageSlot(
            messageId = "present-$id",
            customType = card.customType,
            payload = card.payload,
            fallback = fallback,
        )

        is PresentedCardRef.Tool -> PluginToolCardSlot(
            toolName = card.trace.name,
            callId = card.trace.toolCallId,
            args = card.trace.rawArguments ?: JsonObject(emptyMap()),
            result = card.trace.pluginSlotResult(),
            trace = card.trace,
            fallback = fallback,
        )

        null -> PresentedCardFallback(item.title ?: item.name, colors)
    }
}

@Composable
private fun PresentedCardFallback(name: String, colors: SuperPIColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = 14.dp, vertical = DdSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.File, tint = colors.inkTertiary, size = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name.takeIf { it.isNotBlank() } ?: stringResource(R.string.present_card_unavailable),
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.present_card_unavailable),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun PresentedImageItem(item: PresentedItem, root: File, colors: SuperPIColors) {
    val path = remember(item.path, root) { resolvePresentedPath(item.path.orEmpty(), root) }
    MarkdownImage(
        alt = item.title ?: item.name,
        source = path,
        title = item.name,
        colors = colors,
    )
}

@Composable
private fun PresentedHtmlItem(item: PresentedItem, root: File, colors: SuperPIColors) {
    val path = remember(item.path, root) { resolvePresentedPath(item.path.orEmpty(), root) }
    val label = item.title ?: item.name
    val shape = DdShapes.Medium
    var fullscreen by rememberSaveable(path) { mutableStateOf(false) }
    val available = remember(path) { File(path).isFile }
    if (!available) {
        PresentedFailureRow(name = label, colors = colors)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HtmlFileView(
            path = path,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(shape)
                .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Secondary), shape)
                .testTag("present_html"),
            onActivate = { fullscreen = true },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = DdIcons.Globe, tint = colors.inkTertiary, size = 12.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DdTextAction(
                text = stringResource(R.string.present_fullscreen),
                onClick = { fullscreen = true },
                color = colors.inkSecondary,
            )
        }
    }
    if (fullscreen) {
        PresentedHtmlDialog(path = path, onDismiss = { fullscreen = false })
    }
}

@Composable
private fun PresentedVideoItem(
    item: PresentedItem,
    root: File,
    colors: SuperPIColors,
    onOpen: () -> Unit,
) {
    val path = remember(item.path, root) { resolvePresentedPath(item.path.orEmpty(), root) }
    val label = item.title ?: item.name
    val file = remember(path) { File(path) }
    val available = remember(path) { file.isFile }
    if (!available) {
        PresentedFailureRow(name = label, colors = colors)
        return
    }
    val cover by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { videoCover(path) }
    }
    val shape = DdShapes.Medium
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DdPressable(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().testTag("present_video"),
            shape = shape,
            contentDescription = stringResource(R.string.present_open),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(198.dp)
                    .clip(shape)
                    .background(colors.wash),
                contentAlignment = Alignment.Center,
            ) {
                cover?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = label,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.ink.copy(alpha = DdAlpha.Muted)),
                    contentAlignment = Alignment.Center,
                ) {
                    DdIcon(icon = DdIcons.Play, tint = colors.surface, size = 22.dp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(icon = DdIcons.Play, tint = colors.inkTertiary, size = 12.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun videoCover(path: String): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        val frame = retriever.getFrameAtTime(1_000_000L) ?: retriever.getFrameAtTime(0L)
        frame?.let(::scaleCover)
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

private fun scaleCover(bitmap: Bitmap): Bitmap {
    val maxWidth = 720
    if (bitmap.width <= maxWidth) return bitmap
    val height = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

@Composable
private fun PresentedFileItem(
    item: PresentedItem,
    colors: SuperPIColors,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    val shape = DdShapes.Medium
    DdPressable(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("present_file"),
        shape = shape,
        background = colors.surface,
        contentDescription = stringResource(R.string.present_open),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.hairline.copy(alpha = DdAlpha.Secondary), shape)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdIcon(icon = item.type.icon, tint = colors.inkTertiary, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.title?.takeIf { it != item.name }
                if (subtitle != null) {
                    Spacer(Modifier.height(DdSpacing.Xxs))
                    Text(
                        text = subtitle,
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DdIconButton(
                icon = DdIcons.Share,
                onClick = onShare,
                contentDescription = stringResource(R.string.present_share),
                modifier = Modifier.testTag("present_share"),
                size = 36.dp,
                iconSize = 15.dp,
                tint = colors.inkTertiary,
            )
            DdIcon(icon = DdIcons.ChevronRight, tint = colors.inkTertiary, size = 14.dp)
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun PresentedMiniappItem(item: PresentedItem, onOpenMiniapp: (String) -> Unit) {
    MiniappChatCard(
        app = MiniappCardApp(
            id = item.id.orEmpty(),
            name = item.title ?: item.name,
            icon = item.icon.orEmpty().ifBlank { "✦" },
            version = item.version.orEmpty(),
            entryPath = item.path,
        ),
        onOpen = onOpenMiniapp,
        subtitle = stringResource(R.string.present_miniapp),
    )
}

@Composable
private fun PresentedFailureRow(name: String, colors: SuperPIColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Medium)
            .background(colors.wash)
            .padding(horizontal = 14.dp, vertical = DdSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.Globe, tint = colors.inkTertiary, size = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall,
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.present_failed),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
    }
}

private val PresentedItemType.icon: DdIcon
    get() = when (this) {
        PresentedItemType.IMAGE -> DdIcons.Image
        PresentedItemType.VIDEO -> DdIcons.Play
        PresentedItemType.HTML -> DdIcons.Globe
        PresentedItemType.FILE, PresentedItemType.MINIAPP, PresentedItemType.CARD -> DdIcons.File
    }

@Composable
private fun PresentedHtmlDialog(path: String, onDismiss: () -> Unit) {
    val colors = superPiColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(colors.paper)) {
            HtmlFileView(path = path, modifier = Modifier.fillMaxSize())
            DdIconButton(
                icon = DdIcons.Close,
                onClick = onDismiss,
                contentDescription = stringResource(R.string.assistant_close),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = DdSpacing.Sm, end = 10.dp)
                    .testTag("present_html_close"),
                tint = colors.ink,
            )
        }
    }
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

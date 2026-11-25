package io.github.whywood.superpi.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.plugin.api.Cards
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.PluginToolResult
import io.github.whywood.superpi.plugin.api.TextBlock
import io.github.whywood.superpi.plugin.api.fileRefBlock
import io.github.whywood.superpi.plugin.api.imageBlock
import io.github.whywood.superpi.plugin.api.richResult
import io.github.whywood.superpi.plugin.api.textBlock
import io.github.whywood.superpi.plugin.api.textResult
import io.github.whywood.superpi.plugin.api.ToolBlock
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.ToolResultPayload
import io.github.whywood.superpi.runtime.ToolTrace
import io.github.whywood.superpi.runtime.text
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object ProcessRowMetrics {
    val IconSize = 13.dp
    val IconGap = 6.dp
    val DotSize = 6.dp
    val ChevronSize = 12.dp
    val OuterInset = DdSpacing.Xs
    val InnerInset = DdSpacing.Sm
    val HeaderMinHeight = 44.dp
    val MarkerCenterX = OuterInset + InnerInset + IconSize / 2
    val BodyStartPadding = OuterInset + InnerInset + IconSize + IconGap
}

@Composable
internal fun ToolCard(
    tool: ToolTrace,
    onToggle: () -> Unit,
    onCopyOutput: (String) -> Unit,
    titleOverride: String? = null,
    onFileClick: ((String) -> Unit)? = null,
    compact: Boolean = false,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val rotation = animateFloatAsState(
        targetValue = if (tool.expanded) 90f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "tool-chevron",
    )
    val state = stringResource(
        if (tool.expanded) R.string.assistant_step_expanded else R.string.assistant_step_collapsed,
    )
    val rawArguments = tool.rawArguments
    val output = tool.resultText()
    val toolUsedFormat = stringResource(R.string.chat_process_tool_used)
    val compactTitle = if (compact) toolUsedLabel(toolUsedFormat, tool.name) else null
    val isFailed = tool.status == ToolActivityStatus.FAILED
    val descriptionTitle = remember(rawArguments, tool.arguments, titleOverride, tool.status, compact) {
        if (!compact && !isFailed && titleOverride == null) {
            toolDescriptionArg(rawArguments, tool.arguments)
        } else {
            null
        }
    }
    val title = compactTitle ?: descriptionTitle ?: titleOverride ?: displayToolName(tool.name)
    val preview = remember(rawArguments, tool.arguments, titleOverride, descriptionTitle, output, tool.status, compact) {
        when {
            compact -> ""
            isFailed -> output
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.replace('\n', ' ')
                ?.let(::sanitizeToolPath)
                .orEmpty()

            descriptionTitle != null -> ""
            titleOverride == null -> toolArgsPreview(rawArguments, tool.arguments)
            else -> ""
        }
    }
    val statusText = when (tool.status) {
        ToolActivityStatus.RUNNING -> stringResource(R.string.assistant_tool_running)
        ToolActivityStatus.DONE -> stringResource(R.string.assistant_tool_done)
        ToolActivityStatus.FAILED -> stringResource(R.string.assistant_tool_failed)
    }
    val iconTint = if (isFailed) colors.danger else colors.inkTertiary.copy(alpha = DdAlpha.Secondary)
    val titleColor = when {
        isFailed -> colors.danger
        descriptionTitle != null -> colors.ink
        else -> colors.inkSecondary
    }
    val previewColor = if (isFailed) colors.danger else colors.inkTertiary
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProcessRowMetrics.OuterInset)
                .heightIn(min = ProcessRowMetrics.HeaderMinHeight)
                .clip(RoundedCornerShape(6.dp))
                .semantics { stateDescription = "$statusText · $state" }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = ProcessRowMetrics.InnerInset)
                .testTag("tool_toggle_${tool.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tool.status == ToolActivityStatus.RUNNING) {
                DdSpinner(
                    color = colors.accent,
                    size = ProcessRowMetrics.IconSize,
                    strokeWidth = 1.5.dp,
                )
            } else {
                ToolRowIcon(
                    kind = toolIconKind(tool.name),
                    tint = iconTint,
                )
            }
            Spacer(Modifier.width(ProcessRowMetrics.IconGap))
            Text(
                text = title,
                modifier = if (preview.isEmpty()) {
                    Modifier.weight(1f)
                } else {
                    Modifier.widthIn(max = 180.dp)
                },
                style = DdType.bodySmall.copy(
                    fontFamily = if (compactTitle == null && descriptionTitle == null && titleOverride == null) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotEmpty()) {
                Spacer(Modifier.width(DdSpacing.Sm))
                Text(
                    text = preview,
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                    ),
                    color = previewColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(DdSpacing.Sm))
            DdIcon(
                icon = DdIcons.ChevronRight,
                modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                tint = colors.inkTertiary.copy(alpha = DdAlpha.Secondary),
                size = ProcessRowMetrics.ChevronSize,
            )
            Spacer(Modifier.width(DdSpacing.Xxs))
        }
        AnimatedVisibility(
            visible = tool.expanded,
            enter = expandVertically(
                animationSpec = if (motionEnabled) tween(DdMotion.BASE, easing = DdMotion.EaseOut) else snap(),
                expandFrom = Alignment.Top,
            ) + fadeIn(if (motionEnabled) tween(DdMotion.FAST) else snap()),
            exit = shrinkVertically(
                animationSpec = if (motionEnabled) tween(DdMotion.BASE, easing = DdMotion.EaseOut) else snap(),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(if (motionEnabled) tween(DdMotion.FAST) else snap()),
        ) {
            ToolCardDefaultBody(
                tool = tool,
                output = output,
                onCopyOutput = onCopyOutput,
                onFileClick = onFileClick,
            )
        }
    }
}

@Composable
private fun ToolCardDefaultBody(
    tool: ToolTrace,
    output: String?,
    onCopyOutput: (String) -> Unit,
    onFileClick: ((String) -> Unit)?,
) {
    val colors = superPiColors()
    val rawArguments = tool.rawArguments
    val argumentsText = remember(rawArguments, tool.arguments) { formatToolArguments(rawArguments, tool.arguments) }
    val fileRefs = remember(tool.resultPayload) { tool.resultPayload.fileRefs() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                start = ProcessRowMetrics.BodyStartPadding,
                end = ProcessRowMetrics.OuterInset,
                top = DdSpacing.Xxs,
                bottom = 6.dp,
            ),
    ) {
        if (argumentsText.isNotEmpty()) {
            Text(
                text = stringResource(R.string.assistant_tool_args),
                style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.inkTertiary,
            )
            Spacer(Modifier.height(DdSpacing.Xxs))
            Text(
                text = argumentsText,
                style = DdType.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                ),
                color = colors.inkTertiary,
            )
        }
        if (output != null) {
            if (argumentsText.isNotEmpty()) Spacer(Modifier.height(DdSpacing.Sm))
            var copied by remember(tool.id, output) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(1500L)
                    copied = false
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_tool_output),
                    style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = colors.inkTertiary,
                )
                Spacer(Modifier.weight(1f))
                DdPressable(
                    onClick = {
                        copied = true
                        onCopyOutput(output)
                    },
                    modifier = Modifier.size(44.dp).testTag("tool_copy_${tool.id}"),
                    shape = RoundedCornerShape(8.dp),
                    contentDescription = stringResource(R.string.assistant_tool_copy),
                ) {
                    DdIcon(
                        icon = if (copied) DdIcons.Check else DdIcons.Copy,
                        tint = if (copied) colors.accent else colors.inkTertiary,
                        size = 14.dp,
                    )
                }
            }
            Spacer(Modifier.height(DdSpacing.Xxs))
            Text(
                text = output.trim(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                style = DdType.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 18.sp,
                ),
                color = colors.inkSecondary,
            )
        }
        if (fileRefs.isNotEmpty()) {
            if (argumentsText.isNotEmpty() || output != null) Spacer(Modifier.height(DdSpacing.Sm))
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                fileRefs.forEachIndexed { index, fileRef ->
                    ToolFileRefRow(
                        fileRef = fileRef,
                        onClick = onFileClick?.let { open -> { open(fileRef.path) } },
                        testTag = "tool_file_ref_${tool.id}_$index",
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolFileRefRow(
    fileRef: ToolResultPayload.Block.FileRef,
    onClick: (() -> Unit)?,
    testTag: String,
) {
    val colors = superPiColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DdShapes.Small)
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .padding(horizontal = DdSpacing.Sm, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdIcon(icon = DdIcons.File, tint = colors.inkTertiary, size = 14.dp)
        Spacer(Modifier.width(DdSpacing.Sm))
        val displayPath = remember(fileRef.path) { sanitizeToolPath(fileRef.path) }
        Text(
            text = displayPath,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        fileRef.mimeType?.takeIf { it.isNotBlank() }?.let { mimeType ->
            Spacer(Modifier.width(DdSpacing.Sm))
            Text(
                text = mimeType,
                modifier = Modifier.widthIn(max = 100.dp),
                style = DdType.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = colors.inkTertiary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun toolArgumentsJson(raw: JsonObject?, legacy: Map<String, String>): JsonObject =
    raw ?: JsonObject(legacy.mapValues { (_, value) -> JsonPrimitive(value) })

private fun formatToolArguments(raw: JsonObject?, legacy: Map<String, String>): String =
    if (raw != null) {
        raw.entries.joinToString("\n") { (key, value) -> "$key = ${sanitizeToolPath(value.displayValue())}" }
    } else {
        legacy.entries.joinToString("\n") { (key, value) -> "$key = ${sanitizeToolPath(value)}" }
    }

private fun JsonElement.displayValue(): String = if (this is JsonPrimitive && isString) content else toString()

private fun ToolTrace.resultText(): String? =
    error?.takeIf { it.isNotBlank() }
        ?: displayText()
        ?: resultPayload?.text()?.takeIf { it.isNotBlank() }
        ?: output?.takeIf { it.isNotBlank() }

private fun ToolTrace.displayText(): String? =
    (resultDetails?.get(Cards.DISPLAY_KEY) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun ToolTrace.pluginSlotResult(): PluginToolResult? {
    val isError = status == ToolActivityStatus.FAILED
    val details = resultDetails
    val payload = resultPayload
    if (payload != null) {
        val blocks = payload.toPluginToolBlocks()
        if (blocks.isEmpty()) return null
        val first = blocks.firstOrNull()
        return if (blocks.size == 1 && first is TextBlock) {
            textResult(first.text, details, isError = isError)
        } else {
            richResult(blocks, details, isError = isError)
        }
    }
    val text = error?.takeIf { it.isNotBlank() } ?: output?.takeIf { it.isNotBlank() } ?: return null
    return textResult(text, details, isError = isError)
}

internal fun ToolResultPayload.toPluginToolBlocks(): List<ToolBlock> = when (this) {
    is ToolResultPayload.Text -> listOf(textBlock(text))
    is ToolResultPayload.Empty -> emptyList()
    is ToolResultPayload.Blocks -> blocks.map { block ->
        when (block) {
            is ToolResultPayload.Block.Text -> textBlock(block.text)
            is ToolResultPayload.Block.Image -> imageBlock(
                data = runCatching { Base64.getDecoder().decode(block.data) }.getOrDefault(ByteArray(0)),
                mimeType = block.mimeType,
            )

            is ToolResultPayload.Block.FileRef -> fileRefBlock(block.path, block.mimeType)
        }
    }
}

private fun ToolResultPayload?.fileRefs(): List<ToolResultPayload.Block.FileRef> =
    (this as? ToolResultPayload.Blocks)?.blocks
        ?.filterIsInstance<ToolResultPayload.Block.FileRef>()
        .orEmpty()

@Composable
private fun ToolRowIcon(kind: ToolIconKind, tint: Color) {
    val icon = toolIcon(kind)
    if (icon != null) {
        DdIcon(icon = icon, tint = tint, size = ProcessRowMetrics.IconSize)
        return
    }
    Box(Modifier.size(ProcessRowMetrics.IconSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(ProcessRowMetrics.DotSize)
                .clip(DdShapes.Pill)
                .background(tint),
        )
    }
}

private fun toolIcon(kind: ToolIconKind): DdIcon? = when (kind) {
    ToolIconKind.Book -> DdIcons.Book
    ToolIconKind.Pen -> DdIcons.Pen
    ToolIconKind.Terminal -> DdIcons.Terminal
    ToolIconKind.Search -> DdIcons.Search
    ToolIconKind.Brain -> DdIcons.Brain
    ToolIconKind.Apps -> DdIcons.Apps
    ToolIconKind.Clock -> DdIcons.Clock
    ToolIconKind.File -> DdIcons.File
    ToolIconKind.Folder -> DdIcons.Folder
    ToolIconKind.Target -> DdIcons.Target
    ToolIconKind.Check -> DdIcons.Check
    ToolIconKind.Dot -> null
}

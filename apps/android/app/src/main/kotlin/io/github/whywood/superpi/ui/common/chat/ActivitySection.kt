package io.github.whywood.superpi.ui.common.chat

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.ProcessDetail
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantStep
import io.github.whywood.superpi.runtime.MessageStatus
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.TurnNotice
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun ActivitySection(
    steps: List<AssistantStep>,
    message: AssistantMessage,
    isTrailingBlock: Boolean,
    showTotalDuration: Boolean = true,
    processDetail: ProcessDetail,
    onToggleStep: (Long) -> Unit,
    onCopyOutput: (String) -> Unit,
    onFileClick: (String) -> Unit,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val firstStepId = steps.firstOrNull()?.id
    var expansionOverride by rememberSaveable(message.id, firstStepId) { mutableIntStateOf(-1) }
    val running = message.streaming && (
        isTrailingBlock ||
            steps.any { it is AssistantStep.Tool && it.trace.status == ToolActivityStatus.RUNNING }
        )
    val expanded = expansionOverride == 1
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "activity-chevron",
    )
    val state = stringResource(
        if (expanded) R.string.assistant_step_expanded else R.string.assistant_step_collapsed,
    )
    val semanticStatus = if (running) {
        ProcessStatus.Running
    } else {
        processStatus(streaming = false, status = if (isTrailingBlock) message.status else MessageStatus.DONE)
    }
    val statusLabel = when (semanticStatus) {
        ProcessStatus.Running -> stringResource(R.string.assistant_ux_process_running)
        ProcessStatus.Done -> stringResource(R.string.assistant_ux_process_done)
        ProcessStatus.Stopped -> stringResource(R.string.assistant_stopped)
        ProcessStatus.Failed -> stringResource(R.string.assistant_ux_process_failed)
    }
    fun <T> settleSpec(): AnimationSpec<T> =
        if (motionEnabled) tween(DdMotion.BASE, easing = DdMotion.EaseOut) else snap()

    val statusColor by animateColorAsState(
        targetValue = when (semanticStatus) {
            ProcessStatus.Running -> colors.ink
            ProcessStatus.Failed -> colors.danger
            else -> colors.inkTertiary
        },
        animationSpec = settleSpec(),
        label = "activity_summary_color",
    )
    val duration = processDurationMs(
        steps = steps,
        message = message,
        running = running,
        showTotalDuration = showTotalDuration,
    )
    val durationText = duration?.let { elapsed ->
        formatElapsed(
            processDurationSeconds(elapsed),
            stringResource(R.string.assistant_elapsed_hours_minutes_seconds),
            stringResource(R.string.assistant_elapsed_minutes_seconds),
            stringResource(R.string.assistant_elapsed_seconds),
        )
    }
    val phase = if (running) liveProcessPhase(steps, streaming = true) else null
    val pulseAlphaState = if (running && motionEnabled) {
        val pulse = rememberInfiniteTransition(label = "activity_text_pulse")
        pulse.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = DdMotion.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "activity_text_alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    val thinkingTextStyle = DdType.bodySmall.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val textMeasurer = rememberTextMeasurer()
    var thinkingWidthPx by remember { mutableIntStateOf(0) }
    val latestThinkingWidthPx = rememberUpdatedState(thinkingWidthPx)
    val activeReasoningStep = (phase as? LiveProcessPhase.Thinking)?.reasoning
    val activeReasoningId = activeReasoningStep?.id
    val latestReasoningText = rememberUpdatedState(activeReasoningStep?.text.orEmpty())
    var displayedThinking by remember(message.id, firstStepId, activeReasoningId) {
        mutableStateOf<LiveThinkingLine?>(null)
    }
    LaunchedEffect(message.id, firstStepId, activeReasoningId, processDetail, thinkingTextStyle) {
        if (activeReasoningId == null || processDetail != ProcessDetail.DETAILED) {
            displayedThinking = null
            return@LaunchedEffect
        }
        var lastSwapAtMs = 0L
        var lastRaw: String? = null
        var lastWidthPx = -1
        var cachedNext: LiveThinkingLine? = null
        while (true) {
            val raw = latestReasoningText.value
            val widthPx = latestThinkingWidthPx.value
            if (raw != lastRaw || widthPx != lastWidthPx) {
                cachedNext = extractLiveThinkingLine(raw) { line ->
                    measureThinkingSegments(
                        textMeasurer = textMeasurer,
                        text = line,
                        style = thinkingTextStyle,
                        maxWidthPx = widthPx,
                    )
                }
                lastRaw = raw
                lastWidthPx = widthPx
            }
            val next = cachedNext
            val now = SystemClock.uptimeMillis()
            val previous = displayedThinking
            if (shouldSwapThinkingLine(previous, next, now - lastSwapAtMs)) {
                displayedThinking = next
                if (next != null && next.slotKey != previous?.slotKey) {
                    lastSwapAtMs = now
                } else if (next == null) {
                    lastSwapAtMs = 0L
                }
            }
            delay(THINKING_POLL_INTERVAL_MS)
        }
    }
    val activityLabel = when (phase) {
        is LiveProcessPhase.Thinking -> stringResource(R.string.assistant_process_thinking)
        is LiveProcessPhase.CallingTool -> if (processDetail == ProcessDetail.DETAILED) {
            toolProgressLabel(
                phase.tool.trace.name,
                phase.tool.trace.rawArguments,
                phase.tool.trace.arguments,
            )
        } else {
            stringResource(R.string.assistant_process_calling_tool)
        }
        LiveProcessPhase.Replying -> stringResource(R.string.assistant_process_replying)
        null -> null
    }
    val completedSummary = when (semanticStatus) {
        ProcessStatus.Done -> if (durationText != null) {
            stringResource(R.string.assistant_process_worked_for, durationText)
        } else {
            statusLabel
        }
        ProcessStatus.Stopped, ProcessStatus.Failed -> listOfNotNull(statusLabel, durationText).joinToString(" · ")
        ProcessStatus.Running -> statusLabel
    }
    val summary = activityLabel ?: completedSummary
    val liveThinking = if (activeReasoningStep != null && processDetail == ProcessDetail.DETAILED) displayedThinking else null
    val activeSummaryDescription = liveThinking?.text ?: summary
    val terminatedStatus = semanticStatus.takeIf {
        !running && (it == ProcessStatus.Stopped || it == ProcessStatus.Failed)
    }
    val lastReasoningId = terminatedReasoningId(steps, terminatedStatus != null)
    val processNodes = groupProcessSteps(steps)
    Column(Modifier.fillMaxWidth()) {
        ProcessDisclosure(
            expanded = expanded,
            streaming = message.streaming,
            header = {
        DdPressable(
            onClick = { expansionOverride = if (expanded) 0 else 1 },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .semantics { stateDescription = state }
                .testTag("process_toggle_${message.id}_${firstStepId ?: "live"}"),
            shape = DdShapes.Small,
            contentDescription = activeSummaryDescription,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    Box(
                        modifier = Modifier
                            .width(ActivityIconRowWidth)
                            .height(ActivityIconSize)
                            .clipToBounds(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        ActivityGifIcon(size = ActivityIconSize)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { thinkingWidthPx = it.width },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (liveThinking != null && activeReasoningId != null) {
                        key(activeReasoningId) {
                            FlowingThinkingText(
                                slotKey = liveThinking.slotKey,
                                text = liveThinking.text,
                                textStyle = thinkingTextStyle,
                                baseColor = colors.inkSecondary,
                                shimmerHighlight = colors.ink,
                                motionEnabled = motionEnabled,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Text(
                            text = summary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = pulseAlphaState.value },
                            style = thinkingTextStyle,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.size(DdSpacing.Xs))
                DdIcon(
                    icon = DdIcons.ChevronRight,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                    tint = colors.inkTertiary.copy(alpha = DdAlpha.Secondary),
                    size = ActivityChevronSize,
                )
                Spacer(Modifier.width(DdSpacing.Xxs))
            }
        }
            },
            content = {
            val railColor = colors.hairline.copy(alpha = DdAlpha.Hairline)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = DdSpacing.Xs, bottom = DdSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs),
            ) {
                processNodes.forEachIndexed { index, node ->
                    val clearance = when (node) {
                        is ProcessNode.Single -> if (node.step is AssistantStep.Tool) {
                            ProcessRowMetrics.IconSize / 2 + 3.dp
                        } else {
                            ProcessRowMetrics.DotSize / 2 + 4.dp
                        }

                        is ProcessNode.ToolRun -> ProcessRowMetrics.DotSize / 2 + 4.dp
                    }
                    ProcessTimelineNode(
                        connectTop = index > 0,
                        connectBottom = index < processNodes.lastIndex,
                        markerClearance = clearance,
                        railColor = railColor,
                    ) {
                        when (node) {
                            is ProcessNode.Single -> when (val step = node.step) {
                                is AssistantStep.Reasoning -> ReasoningCard(
                                    step = step,
                                    active = step.id == activeReasoningId,
                                    onToggle = { onToggleStep(step.id) },
                                    compact = processDetail == ProcessDetail.COMPACT,
                                    terminatedStatus = terminatedStatus.takeIf { step.id == lastReasoningId },
                                )

                                is AssistantStep.Tool -> ProcessToolStep(
                                    step = step,
                                    onToggleStep = onToggleStep,
                                    onCopyOutput = onCopyOutput,
                                    onFileClick = onFileClick,
                                    processDetail = processDetail,
                                )

                                is AssistantStep.Text -> Unit

                                is AssistantStep.Notice -> NoticeStep(step)

                                is AssistantStep.CustomCard -> Unit
                            }

                            is ProcessNode.ToolRun -> ToolRunGroup(
                                tools = node.tools,
                                messageId = message.id,
                                streaming = message.streaming,
                                onToggleStep = onToggleStep,
                                onCopyOutput = onCopyOutput,
                                onFileClick = onFileClick,
                                processDetail = processDetail,
                            )
                        }
                    }
                }
            }
            },
        )
    }
}

@Composable
private fun ProcessTimelineNode(
    connectTop: Boolean,
    connectBottom: Boolean,
    markerClearance: Dp,
    railColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (connectTop || connectBottom) {
                    Modifier.drawBehind {
                        val strokeWidth = 1.dp.toPx()
                        val inset = ProcessRowMetrics.MarkerCenterX.toPx()
                        val railX = if (layoutDirection == LayoutDirection.Rtl) size.width - inset else inset
                        val headerCenterY = maxOf(ProcessRowMetrics.HeaderMinHeight.toPx(), 18.sp.toPx()) / 2f
                        val clearPx = markerClearance.toPx()
                        val gapPx = DdSpacing.Xs.toPx()
                        if (connectTop) {
                            val topEnd = headerCenterY - clearPx
                            if (topEnd > 0f) {
                                drawLine(
                                    color = railColor,
                                    start = Offset(railX, 0f),
                                    end = Offset(railX, topEnd),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Butt,
                                )
                            }
                        }
                        if (connectBottom) {
                            val bottomStart = headerCenterY + clearPx
                            val bottomEnd = size.height + gapPx
                            if (bottomEnd > bottomStart) {
                                drawLine(
                                    color = railColor,
                                    start = Offset(railX, bottomStart),
                                    end = Offset(railX, bottomEnd),
                                    strokeWidth = strokeWidth,
                                    cap = StrokeCap.Butt,
                                )
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

private const val ToolGroupMinCount = 3

private val ActivityIconSize = 15.dp
private val ActivityIconRowWidth = 21.dp
private val ActivityChevronSize = 13.dp

private sealed interface ProcessNode {
    data class Single(val step: AssistantStep) : ProcessNode
    data class ToolRun(val tools: List<AssistantStep.Tool>) : ProcessNode
}

private fun groupProcessSteps(steps: List<AssistantStep>): List<ProcessNode> {
    val nodes = mutableListOf<ProcessNode>()
    var index = 0
    while (index < steps.size) {
        val step = steps[index]
        if (step is AssistantStep.Tool) {
            var end = index
            while (end < steps.size && steps[end] is AssistantStep.Tool) end++
            val tools = steps.subList(index, end).filterIsInstance<AssistantStep.Tool>()
            if (tools.size >= ToolGroupMinCount) {
                nodes += ProcessNode.ToolRun(tools)
            } else {
                tools.forEach { nodes += ProcessNode.Single(it) }
            }
            index = end
        } else {
            nodes += ProcessNode.Single(step)
            index++
        }
    }
    return nodes
}

@Composable
private fun ToolRunGroup(
    tools: List<AssistantStep.Tool>,
    messageId: Long,
    streaming: Boolean,
    onToggleStep: (Long) -> Unit,
    onCopyOutput: (String) -> Unit,
    onFileClick: (String) -> Unit,
    processDetail: ProcessDetail,
) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val firstToolId = tools.first().id
    var expanded by rememberSaveable(messageId, firstToolId) { mutableStateOf(false) }
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "tool-group-chevron",
    )
    val running = tools.any { it.trace.status == ToolActivityStatus.RUNNING }
    val failed = tools.any { it.trace.status == ToolActivityStatus.FAILED }
    val dotTint = when {
        failed -> colors.danger
        running -> colors.warning
        else -> colors.inkTertiary.copy(alpha = DdAlpha.Muted)
    }
    val state = stringResource(
        if (expanded) R.string.assistant_step_expanded else R.string.assistant_step_collapsed,
    )
    val title = stringResource(R.string.assistant_process_tool_group, tools.size)
    ProcessDisclosure(
        expanded = expanded,
        streaming = streaming,
        header = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProcessRowMetrics.OuterInset)
                .heightIn(min = ProcessRowMetrics.HeaderMinHeight)
                .clip(RoundedCornerShape(6.dp))
                .semantics { stateDescription = state }
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .padding(horizontal = ProcessRowMetrics.InnerInset)
                .testTag("tool_group_$firstToolId"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(ProcessRowMetrics.IconSize), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(ProcessRowMetrics.DotSize)
                        .clip(DdShapes.Pill)
                        .background(dotTint),
                )
            }
            Spacer(Modifier.width(ProcessRowMetrics.IconGap))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DdSpacing.Xs))
            DdIcon(
                icon = DdIcons.ChevronRight,
                modifier = Modifier.graphicsLayer { rotationZ = rotation.value },
                tint = colors.inkTertiary.copy(alpha = DdAlpha.Secondary),
                size = ProcessRowMetrics.ChevronSize,
            )
            Spacer(Modifier.width(DdSpacing.Xxs))
        }
        },
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ProcessRowMetrics.IconSize + ProcessRowMetrics.IconGap,
                        top = DdSpacing.Xxs,
                    ),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Xxs),
            ) {
                tools.forEach { tool ->
                    ProcessToolStep(
                        step = tool,
                        onToggleStep = onToggleStep,
                        onCopyOutput = onCopyOutput,
                        onFileClick = onFileClick,
                        processDetail = processDetail,
                    )
                }
            }
        },
    )
}

@Composable
private fun NoticeStep(step: AssistantStep.Notice) {
    val colors = superPiColors()
    val notice = step.notice as? TurnNotice.JobFinished ?: return
    val title = stringResource(
        if (notice.failed) R.string.chat_job_card_failed else R.string.chat_job_card_done,
    )
    val text = if (notice.label.isBlank()) title else "$title · ${notice.label}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProcessRowMetrics.OuterInset)
            .heightIn(min = ProcessRowMetrics.HeaderMinHeight)
            .padding(horizontal = ProcessRowMetrics.InnerInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ProcessRowMetrics.IconSize), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(ProcessRowMetrics.DotSize)
                    .clip(DdShapes.Pill)
                    .background(if (notice.failed) colors.danger else colors.inkTertiary.copy(alpha = DdAlpha.Muted)),
            )
        }
        Spacer(Modifier.width(ProcessRowMetrics.IconGap))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProcessToolStep(
    step: AssistantStep.Tool,
    onToggleStep: (Long) -> Unit,
    onCopyOutput: (String) -> Unit,
    onFileClick: (String) -> Unit,
    processDetail: ProcessDetail,
) {
    val trace = step.trace
    val deliveredCount = if (trace.name == "present") {
        remember(trace) { parsePresentedItems(trace)?.size }
    } else {
        null
    }
    ToolCard(
        tool = trace,
        onToggle = { onToggleStep(trace.id) },
        onCopyOutput = onCopyOutput,
        titleOverride = deliveredCount?.let { count ->
            stringResource(R.string.present_tool_delivered, count)
        },
        onFileClick = onFileClick,
        compact = processDetail == ProcessDetail.COMPACT,
    )
}

internal fun processDurationMs(
    steps: List<AssistantStep>,
    message: AssistantMessage,
    running: Boolean,
    showTotalDuration: Boolean,
): Long? {
    val finishedAt = message.finishedAt
    return when {
        showTotalDuration && !message.streaming && finishedAt != null &&
            message.startedAt > 0 && finishedAt >= message.startedAt -> finishedAt - message.startedAt

        !showTotalDuration && !running -> steps
            .filterIsInstance<AssistantStep.Tool>()
            .mapNotNull { it.trace.durationMs?.takeIf { ms -> ms >= 0L } }
            .takeIf { it.isNotEmpty() }
            ?.sum()

        else -> null
    }
}

internal fun processDurationSeconds(durationMs: Long): Long =
    (durationMs / 1000L).coerceAtLeast(1L)

internal fun formatElapsed(
    totalSeconds: Long,
    hoursMinutesSeconds: String,
    minutesSeconds: String,
    secondsOnly: String,
): String {
    val clamped = totalSeconds.coerceAtLeast(0L)
    val hours = clamped / 3600
    val minutes = (clamped % 3600) / 60
    val seconds = clamped % 60
    return when {
        hours > 0 -> String.format(Locale.ROOT, hoursMinutesSeconds, hours, minutes, seconds)
        minutes > 0 -> String.format(Locale.ROOT, minutesSeconds, minutes, seconds)
        else -> String.format(Locale.ROOT, secondsOnly, seconds)
    }
}

private const val THINKING_POLL_INTERVAL_MS = 120L
internal const val MIN_THINKING_LINE_DWELL_MS = 380L
private const val DEFAULT_THINKING_LINE_UNITS = 36
private val THINKING_BREAK_PUNCTUATION = setOf('，', '。', '、', '；', '：', '！', '？', '）', '》', '】')

@Composable
private fun FlowingThinkingText(
    slotKey: Long,
    text: String,
    textStyle: TextStyle,
    baseColor: Color,
    shimmerHighlight: Color,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slideOffsetPx = remember(density) { with(density) { 7.dp.roundToPx() } }
    AnimatedContent(
        targetState = slotKey,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            if (motionEnabled) {
                (
                    fadeIn(tween(durationMillis = 200, easing = DdMotion.EaseOut)) +
                        slideInVertically(tween(durationMillis = 200, easing = DdMotion.EaseOut)) { slideOffsetPx }
                    ) togetherWith (
                    fadeOut(tween(durationMillis = 150, easing = DdMotion.EaseOut)) +
                        slideOutVertically(tween(durationMillis = 150, easing = DdMotion.EaseOut)) { -slideOffsetPx }
                    )
            } else {
                fadeIn(snap()) togetherWith fadeOut(snap())
            }
        },
        label = "flowing-thinking-line",
    ) { renderedSlotKey ->
        var pinnedText by remember { mutableStateOf(text) }
        if (renderedSlotKey == slotKey) {
            pinnedText = text
        }
        Text(
            text = pinnedText,
            modifier = Modifier
                .fillMaxWidth()
                .textShimmer(
                    active = motionEnabled,
                    base = baseColor,
                    highlight = shimmerHighlight,
                ),
            style = textStyle,
            color = baseColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun measureThinkingSegments(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int,
): List<String> {
    if (text.isEmpty()) return emptyList()
    if (maxWidthPx <= 0) return splitThinkingSegments(text)
    val layout = textMeasurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = maxWidthPx),
    )
    if (layout.lineCount <= 0) return listOf(text)
    val segments = buildList {
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line).coerceIn(0, text.length)
            val end = layout.getLineEnd(line, visibleEnd = true).coerceIn(start, text.length)
            val segment = text.substring(start, end).trim()
            if (segment.isNotEmpty()) add(segment)
        }
    }
    return segments.ifEmpty { listOf(text) }
}

internal fun splitThinkingSegments(
    text: String,
    maxUnits: Int = DEFAULT_THINKING_LINE_UNITS,
): List<String> {
    if (text.isBlank()) return emptyList()
    if (maxUnits <= 0) {
        val trimmed = text.trim()
        return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
    }
    val segments = mutableListOf<String>()
    var start = 0
    while (start < text.length && text[start].isWhitespace()) {
        start++
    }
    while (start < text.length) {
        var units = 0
        var end = start
        var lastBreak = -1
        while (end < text.length) {
            val ch = text[end]
            val charUnits = if (ch.code < 0x80) 1 else 2
            if (end > start && units + charUnits > maxUnits) break
            units += charUnits
            end++
            if (end < text.length && units * 2 >= maxUnits && (ch.isWhitespace() || ch in THINKING_BREAK_PUNCTUATION)) {
                lastBreak = end
            }
        }
        val splitEnd = if (end < text.length && lastBreak > start) lastBreak else end
        val segment = text.substring(start, splitEnd).trim()
        if (segment.isNotEmpty()) segments.add(segment)
        start = splitEnd
        while (start < text.length && text[start].isWhitespace()) {
            start++
        }
    }
    return segments
}

internal fun shouldSwapThinkingLine(
    current: LiveThinkingLine?,
    latest: LiveThinkingLine?,
    elapsedSinceSwapMs: Long,
    minDwellMs: Long = MIN_THINKING_LINE_DWELL_MS,
): Boolean = when {
    latest == null -> current != null
    current == null -> true
    latest.slotKey == current.slotKey -> latest.text != current.text
    else -> elapsedSinceSwapMs >= minDwellMs
}

private val THINKING_PREFIX_REGEX =
    Regex("""^(?:\s*(?:#{1,6}\s+|>\s*|[-*+]\s+(?:\[[ xX]]\s+)?|\d+[.)]\s+))+""")
private val THINKING_INLINE_MARKER_REGEX = Regex("""(\*\*|__|~~|`)""")
private val THINKING_SYMBOL_ONLY_REGEX = Regex("""^[-*_~=>#\s.·•]+$""")

internal data class LiveThinkingLine(
    val paragraphIndex: Int,
    val segmentIndex: Int,
    val text: String,
) {
    val slotKey: Long
        get() = (paragraphIndex.toLong() shl 20) or (segmentIndex.toLong() and 0xFFFFFL)
}

internal fun cleanThinkingLine(line: String): String? {
    val withoutPrefix = line.trim().replace(THINKING_PREFIX_REGEX, "")
    val withoutInline = withoutPrefix
        .replace(THINKING_INLINE_MARKER_REGEX, "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (withoutInline.isEmpty() || THINKING_SYMBOL_ONLY_REGEX.matches(withoutInline)) {
        return null
    }
    return withoutInline
}

internal fun extractLiveThinkingLine(
    raw: String,
    splitLine: (String) -> List<String> = ::splitThinkingSegments,
): LiveThinkingLine? {
    if (raw.isBlank()) return null
    val lines = raw.lines()
    for (index in lines.lastIndex downTo 0) {
        val cleaned = cleanThinkingLine(lines[index]) ?: continue
        val segments = splitLine(cleaned)
        if (segments.isEmpty()) continue
        return LiveThinkingLine(
            paragraphIndex = index,
            segmentIndex = segments.lastIndex,
            text = segments.last(),
        )
    }
    return null
}

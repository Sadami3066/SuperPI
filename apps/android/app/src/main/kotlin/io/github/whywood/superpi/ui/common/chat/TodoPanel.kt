package io.github.whywood.superpi.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIcons
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdRadius
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val PendingStatus = "pending"
private const val InProgressStatus = "in_progress"
private const val CompletedStatus = "completed"
private val PanelShape = DdShapes.Card
private val HeaderHeight = 44.dp

internal data class TodoItem(val content: String, val status: String)

@Composable
internal fun InlineTodoCard(
    todos: List<TodoItem>,
    cardKey: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val completedCount = todos.count { it.status == CompletedStatus }
    val allCompleted = completedCount == todos.size
    val progress = completedCount.toFloat() / todos.size.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (motionEnabled) tween(DdMotion.BASE, easing = DdMotion.EaseOut) else snap(),
        label = "todo-progress",
    )
    var userToggled by rememberSaveable(cardKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(cardKey) { mutableStateOf(streaming || !allCompleted) }
    LaunchedEffect(allCompleted, streaming) {
        if (!userToggled) {
            expanded = streaming || !allCompleted
        }
    }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "todo-chevron",
    )
    val stateLabel = stringResource(
        if (expanded) R.string.assistant_step_expanded else R.string.assistant_step_collapsed,
    )
    val current = todos.firstOrNull { it.status == InProgressStatus }
        ?: todos.firstOrNull { it.status != CompletedStatus }
    val collapsedSummary = current?.content ?: stringResource(R.string.assistant_todo_title)
    val summary = if (expanded) stringResource(R.string.assistant_todo_title) else collapsedSummary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(colors.surface)
            .border(0.75.dp, colors.hairline, PanelShape)
            .animateContentSize(
                animationSpec = if (motionEnabled) {
                    tween(DdMotion.BASE, easing = DdMotion.EaseOut)
                } else {
                    snap()
                },
            )
            .testTag("inline_todo_card"),
    ) {
        DdPressable(
            onClick = {
                userToggled = true
                expanded = !expanded
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HeaderHeight)
                .semantics { stateDescription = stateLabel }
                .testTag("todo_panel_toggle"),
            shape = if (expanded) {
                RoundedCornerShape(topStart = DdRadius.Card, topEnd = DdRadius.Card)
            } else {
                PanelShape
            },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusIndicator(
                    status = if (allCompleted) CompletedStatus else current?.status.orEmpty(),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = summary,
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall.copy(
                        fontWeight = if (expanded) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = if (allCompleted && !expanded) colors.inkTertiary else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DdSpacing.Sm))
                Text(
                    text = stringResource(
                        R.string.assistant_todo_progress,
                        completedCount,
                        todos.size,
                    ),
                    style = DdType.numeric.copy(fontSize = 12.sp),
                    color = if (allCompleted) colors.success else colors.inkTertiary,
                )
                Spacer(Modifier.width(6.dp))
                DdIcon(
                    icon = DdIcons.ChevronDown,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation.value },
                    tint = colors.inkTertiary.copy(alpha = DdAlpha.Secondary),
                    size = 14.dp,
                )
            }
        }
        // Subtle slim progress rail
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(colors.wash),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(if (allCompleted) colors.success else colors.accent),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(if (motionEnabled) tween(DdMotion.FAST) else snap()),
            exit = fadeOut(if (motionEnabled) tween(DdMotion.FAST) else snap()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Md, vertical = 6.dp),
            ) {
                todos.forEachIndexed { idx, todo ->
                    if (idx > 0) {
                        DdHairline(Modifier.padding(start = 24.dp))
                    }
                    InlineTodoRow(todo = todo)
                }
            }
        }
    }
}

@Composable
private fun InlineTodoRow(todo: TodoItem) {
    val colors = superPiColors()
    val completed = todo.status == CompletedStatus
    val inProgress = todo.status == InProgressStatus
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 3.dp)) {
            StatusIndicator(status = todo.status)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = todo.content,
            modifier = Modifier.weight(1f),
            style = DdType.bodySmall.copy(
                fontWeight = if (inProgress) FontWeight.Medium else FontWeight.Normal,
            ),
            color = when {
                completed -> colors.inkTertiary
                inProgress -> colors.ink
                else -> colors.inkSecondary
            },
            textDecoration = if (completed) TextDecoration.LineThrough else null,
        )
    }
}

@Composable
private fun StatusIndicator(status: String) {
    val colors = superPiColors()
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            CompletedStatus -> DdIcon(
                icon = DdIcons.Check,
                tint = colors.success,
                size = 13.dp,
            )

            InProgressStatus -> Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )

            else -> Box(
                Modifier
                    .size(8.dp)
                    .border(1.5.dp, colors.inkTertiary.copy(alpha = 0.6f), CircleShape),
            )
        }
    }
}

internal fun parseTodos(payload: String?): List<TodoItem> {
    if (payload.isNullOrBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val array = root["todos"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val status = (item["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (content.isEmpty()) null else TodoItem(content, status)
    }
}

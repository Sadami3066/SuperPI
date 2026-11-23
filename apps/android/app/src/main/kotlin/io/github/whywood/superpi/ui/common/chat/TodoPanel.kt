package io.github.whywood.superpi.ui.common.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdAlpha
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdIcon
import io.github.whywood.superpi.design.DdIconButton
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
private val DividerHeight = 1.dp

internal data class TodoItem(val content: String, val status: String)

@Composable
internal fun TodoPanel(payload: String?, modifier: Modifier = Modifier) {
    val todos = remember(payload) { parseTodos(payload) }
    var hidden by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var lastTodos by remember { mutableStateOf(emptyList<TodoItem>()) }
    LaunchedEffect(todos) {
        if (todos.isNotEmpty()) lastTodos = todos
    }
    val source = todos.ifEmpty { lastTodos }
    val visibleTodos = remember(source, hidden) { source.filterNot { it.content in hidden } }
    val motionEnabled = LocalDdMotionEnabled.current
    AnimatedVisibility(
        visible = visibleTodos.isNotEmpty(),
        modifier = modifier,
        enter = if (motionEnabled) {
            fadeIn(tween(DdMotion.FAST)) +
                expandVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
        } else {
            fadeIn(snap())
        },
        exit = if (motionEnabled) {
            fadeOut(tween(DdMotion.FAST)) +
                shrinkVertically(tween(DdMotion.BASE, easing = DdMotion.EaseOut))
        } else {
            fadeOut(snap())
        },
    ) {
        TodoPanelContent(
            todos = visibleTodos,
            onDelete = { content ->
                if (content !in hidden) hidden = ArrayList(hidden + content)
            },
        )
    }
}

@Composable
private fun TodoPanelContent(todos: List<TodoItem>, onDelete: (String) -> Unit) {
    val colors = superPiColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val deleteLabel = stringResource(R.string.assistant_todo_delete)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionEnabled) tween(DdMotion.FAST, easing = DdMotion.EaseOut) else snap(),
        label = "todo-chevron",
    )
    val stateLabel = stringResource(
        if (expanded) R.string.assistant_step_expanded else R.string.assistant_step_collapsed,
    )
    val completedCount = todos.count { it.status == CompletedStatus }
    val allCompleted = completedCount == todos.size
    val current = todos.firstOrNull { it.status == InProgressStatus }
        ?: todos.firstOrNull { it.status != CompletedStatus }
    val collapsedSummary = current?.content ?: stringResource(R.string.assistant_todo_title)
    val summary = if (expanded) stringResource(R.string.assistant_todo_title) else collapsedSummary
    val scrollState = rememberScrollState()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DdSpacing.Md, vertical = DdSpacing.Xs),
    ) {
        val listMaxHeight = (maxHeight * 0.4f - HeaderHeight - DividerHeight).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 780.dp)
                    .fillMaxWidth()
                    .clip(PanelShape)
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, PanelShape)
                    .animateContentSize(
                        animationSpec = if (motionEnabled) {
                            tween(DdMotion.BASE, easing = DdMotion.EaseOut)
                        } else {
                            snap()
                        },
                    ),
            ) {
                DdPressable(
                    onClick = { expanded = !expanded },
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusIndicator(
                            status = if (allCompleted) CompletedStatus else current?.status.orEmpty(),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = summary,
                            modifier = Modifier.weight(1f),
                            style = DdType.bodySmall,
                            color = if (allCompleted) colors.inkTertiary else colors.ink,
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
                            style = DdType.chip,
                            color = colors.inkTertiary,
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
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(if (motionEnabled) tween(DdMotion.FAST) else snap()),
                    exit = fadeOut(if (motionEnabled) tween(DdMotion.FAST) else snap()),
                ) {
                    Column {
                        DdHairline(Modifier.padding(horizontal = DdSpacing.Lg))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = listMaxHeight)
                                .padding(vertical = DdSpacing.Xxs)
                                .verticalScroll(scrollState),
                        ) {
                            todos.forEach { todo ->
                                TodoPanelRow(todo = todo, deleteLabel = deleteLabel, onDelete = onDelete)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoPanelRow(todo: TodoItem, deleteLabel: String, onDelete: (String) -> Unit) {
    val colors = superPiColors()
    val completed = todo.status == CompletedStatus
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = DdSpacing.Lg, end = DdSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIndicator(status = todo.status)
        Spacer(Modifier.width(10.dp))
        Text(
            text = todo.content,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            style = DdType.bodySmall,
            color = if (completed) colors.inkTertiary else colors.ink,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        DdIconButton(
            icon = DdIcons.Close,
            onClick = { onDelete(todo.content) },
            contentDescription = deleteLabel,
            size = 44.dp,
            iconSize = 14.dp,
            tint = colors.inkTertiary,
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

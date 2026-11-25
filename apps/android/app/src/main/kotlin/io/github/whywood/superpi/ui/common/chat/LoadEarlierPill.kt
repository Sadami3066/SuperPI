package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.whywood.superpi.R
import io.github.whywood.superpi.design.DdPressable
import io.github.whywood.superpi.design.DdShapes
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.runtime.AssistantMessage
import kotlinx.coroutines.launch

internal const val ChatHistoryFoldThreshold = 20
internal const val ChatHistoryInitialVisible = 3
internal const val ChatHistoryLoadStep = 20

internal fun initialVisibleMessages(total: Int): Int =
    if (total > ChatHistoryFoldThreshold) ChatHistoryInitialVisible else total.coerceAtLeast(0)

internal fun expandVisibleMessages(visible: Int, total: Int): Int {
    val safeTotal = total.coerceAtLeast(0)
    val safeVisible = visible.coerceIn(0, safeTotal)
    if (safeVisible >= safeTotal) return safeVisible
    return (safeVisible + ChatHistoryLoadStep).coerceAtMost(safeTotal)
}

internal fun resolveVisibleMessages(visible: Int, previousTotal: Int, total: Int): Int {
    if (total <= 0) return 0
    if (visible <= 0 || previousTotal <= 0) return initialVisibleMessages(total)
    if (total <= previousTotal) return visible.coerceAtMost(total)
    val collapsed = visible <= ChatHistoryInitialVisible && previousTotal > ChatHistoryFoldThreshold
    return if (collapsed) {
        ChatHistoryInitialVisible.coerceAtMost(total)
    } else {
        (visible + (total - previousTotal)).coerceAtMost(total)
    }
}

internal data class ChatHistoryWindowState(
    val visibleMessages: List<AssistantMessage>,
    val hiddenMessageCount: Int,
    val loadEarlier: () -> Unit,
)

@Composable
internal fun rememberChatHistoryWindow(
    sessionKey: Any?,
    messages: List<AssistantMessage>,
    listState: LazyListState,
): ChatHistoryWindowState {
    val scope = rememberCoroutineScope()
    var visibleMessageCount by rememberSaveable(sessionKey) { mutableStateOf(0) }
    var trackedMessageTotal by remember(sessionKey) { mutableIntStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        visibleMessageCount = resolveVisibleMessages(visibleMessageCount, trackedMessageTotal, messages.size)
        trackedMessageTotal = messages.size
    }
    val shownMessageCount = resolveVisibleMessages(visibleMessageCount, trackedMessageTotal, messages.size)
        .coerceIn(0, messages.size)
    val visibleMessages = remember(messages, shownMessageCount) {
        if (shownMessageCount >= messages.size) messages else messages.takeLast(shownMessageCount)
    }
    val hiddenMessageCount = (messages.size - shownMessageCount).coerceAtLeast(0)
    fun loadEarlier() {
        if (hiddenMessageCount <= 0) return
        val previousShown = shownMessageCount
        val nextShown = expandVisibleMessages(previousShown, messages.size)
        if (nextShown <= previousShown) return
        val itemCountBefore = listState.layoutInfo.totalItemsCount
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val firstVisibleOffset = listState.firstVisibleItemScrollOffset
        visibleMessageCount = nextShown
        trackedMessageTotal = messages.size
        scope.launch {
            var frames = 0
            while (listState.layoutInfo.totalItemsCount <= itemCountBefore && frames < 120) {
                withFrameNanos { }
                frames++
            }
            val pillBefore = previousShown < messages.size
            val pillAfter = nextShown < messages.size
            val shift = nextShown - previousShown + if (pillBefore && !pillAfter) -1 else 0
            if (firstVisibleIndex > 0) {
                listState.scrollToItem((firstVisibleIndex + shift).coerceAtLeast(0), firstVisibleOffset)
            }
        }
    }
    return ChatHistoryWindowState(visibleMessages, hiddenMessageCount, ::loadEarlier)
}

@Composable
internal fun LoadEarlierPill(
    hiddenCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = superPiColors()
    DdPressable(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp).testTag("chat_load_earlier"),
        shape = DdShapes.Small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_history_load_earlier),
                style = DdType.chip,
                color = colors.inkSecondary,
            )
            Text(
                text = stringResource(R.string.chat_history_remaining, hiddenCount),
                style = DdType.chip,
                color = colors.inkTertiary,
            )
        }
    }
}

package io.github.whywood.superpi.ui.common.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

data class ScrollAnchor(val index: Int, val offset: Int, val atBottom: Boolean = false)

@Stable
internal class ChatScrollController(
    private val listState: LazyListState,
    private val thresholdPx: Float,
) {
    var following by mutableStateOf(true)
        private set

    var atBottom by mutableStateOf(true)
        private set

    val showJumpToBottom: Boolean
        get() = !following && !atBottom

    private var dragging = false
    private var dragSeen = false
    private var springVelocityPxPerSec = 0f
    private var subPixelCarryPx = 0f

    fun onDraggingChanged(value: Boolean) {
        dragging = value
        if (value) {
            dragSeen = true
            resetPhysics()
        }
    }

    suspend fun anchorToBottom() {
        if (!following) return
        withFrameNanos { }
        val overflow = overflowToBottom()
        if (overflow > 0f && listState.canScrollForward) {
            listState.scrollBy(overflow)
        }
    }

    suspend fun jumpToBottom(animate: Boolean) {
        dragSeen = false
        following = true
        if (!animate) {
            resetPhysics()
            awaitSettled()
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex < 0) return
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            if (last == null || last.index != lastIndex) {
                listState.scrollToItem(lastIndex)
                awaitSettled()
            }
            val overflow = overflowToBottom()
            if (overflow > 0f && listState.canScrollForward) {
                listState.scrollBy(overflow)
            }
            resetPhysics()
            return
        }
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex < 0) return
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        if (last == null || last.index < lastIndex - 2) {
            resetPhysics()
            listState.animateScrollToItem((lastIndex - 1).coerceAtLeast(0))
            awaitSettled()
        }
        followUntilSettled(smooth = true)
    }

    suspend fun restoreAnchor(index: Int, offset: Int) {
        resetPhysics()
        following = false
        awaitSettled()
        val count = listState.layoutInfo.totalItemsCount
        if (count <= 0) {
            jumpToBottom(animate = false)
            return
        }
        listState.scrollToItem(index.coerceIn(0, count - 1), offset.coerceAtLeast(0))
        awaitSettled()
        if (!listState.canScrollForward) onBottomChanged(true)
    }

    suspend fun jumpToItem(index: Int, offset: Int = 0) {
        resetPhysics()
        following = false
        awaitSettled()
        val count = listState.layoutInfo.totalItemsCount
        if (count <= 0) return
        listState.scrollToItem(index.coerceIn(0, count - 1), offset.coerceAtLeast(0))
        awaitSettled()
        if (!listState.canScrollForward) onBottomChanged(true)
    }

    fun needsFollow(): Boolean =
        following && !dragging && !dragSeen && listState.canScrollForward && overflowToBottom() > 0.5f

    suspend fun followUntilSettled(smooth: Boolean) {
        var lastNanos = Long.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            val dt = withFrameNanos { now ->
                val elapsed = if (lastNanos == Long.MIN_VALUE) {
                    1f / 60f
                } else {
                    (now - lastNanos) / 1_000_000_000f
                }
                lastNanos = now
                elapsed
            }
            if (!following || dragging || dragSeen || listState.isScrollInProgress) {
                resetPhysics()
                return
            }
            val overflow = overflowToBottom()
            if (overflow <= 0f || !listState.canScrollForward) {
                resetPhysics()
                return
            }
            if (!smooth) {
                listState.scrollBy(overflow)
                resetPhysics()
                return
            }
            val effectiveOverflow = (overflow - subPixelCarryPx).coerceAtLeast(0f)
            val step = ScrollFollowPolicy.softSpringStep(
                overflowPx = effectiveOverflow,
                velocityPxPerSec = springVelocityPxPerSec,
                deltaSeconds = dt,
                smooth = true,
            )
            springVelocityPxPerSec = step.nextVelocityPxPerSec
            if (step.settled) {
                if (overflow > 0f && listState.canScrollForward) {
                    listState.scrollBy(overflow)
                }
                resetPhysics()
                return
            }
            subPixelCarryPx += step.stepPx
            val wholePx = floor(subPixelCarryPx).coerceAtMost(overflow)
            if (wholePx >= 1f) {
                val consumed = listState.scrollBy(wholePx)
                subPixelCarryPx = (subPixelCarryPx - consumed).coerceAtLeast(0f)
                if (consumed <= 0.05f) {
                    resetPhysics()
                    return
                }
            }
        }
    }

    suspend fun settleScroll() {
        if (!dragSeen) return
        dragSeen = false
        val distance = bottomDistancePx()
        if (distance != null && ScrollFollowPolicy.nearBottom(distance, thresholdPx)) {
            following = true
            atBottom = distance <= 0f
            if (distance > 0f && listState.canScrollForward) {
                if (distance <= 2f) {
                    listState.scrollBy(distance)
                    resetPhysics()
                } else {
                    followUntilSettled(smooth = true)
                }
            }
        } else {
            resetPhysics()
            following = false
        }
    }

    fun onBottomChanged(value: Boolean) {
        atBottom = value
        if (value) following = true
    }

    private fun resetPhysics() {
        springVelocityPxPerSec = 0f
        subPixelCarryPx = 0f
    }

    private suspend fun awaitSettled() {
        withFrameNanos { }
        withFrameNanos { }
    }

    private fun bottomDistancePx(): Float? {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return 0f
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index != info.totalItemsCount - 1) return null
        val viewportBottom = info.viewportEndOffset - info.afterContentPadding
        return (last.offset + last.size - viewportBottom).toFloat().coerceAtLeast(0f)
    }

    private fun overflowToBottom(): Float {
        val info = listState.layoutInfo
        if (info.totalItemsCount == 0) return 0f
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        val viewportBottom = info.viewportEndOffset - info.afterContentPadding
        val tailOverflow = (last.offset + last.size - viewportBottom).toFloat().coerceAtLeast(0f)
        if (last.index >= info.totalItemsCount - 1) return tailOverflow
        val remainingItems = info.totalItemsCount - 1 - last.index
        val avgSize = if (info.visibleItemsInfo.isNotEmpty()) {
            info.visibleItemsInfo.sumOf { it.size }.toFloat() / info.visibleItemsInfo.size.toFloat()
        } else {
            thresholdPx
        }
        val gap = info.mainAxisItemSpacing.toFloat().coerceAtLeast(0f)
        return tailOverflow + remainingItems * (maxOf(avgSize, thresholdPx) + gap)
    }
}

@Composable
internal fun rememberChatScrollController(listState: LazyListState): ChatScrollController {
    val density = LocalDensity.current
    val thresholdPx = with(density) { ScrollFollowPolicy.NearBottomThresholdDp.dp.toPx() }
    val controller = remember(listState, thresholdPx) {
        ChatScrollController(listState, thresholdPx)
    }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(controller, dragged) {
        controller.onDraggingChanged(dragged)
        if (!dragged && !listState.isScrollInProgress) {
            controller.settleScroll()
        }
    }
    LaunchedEffect(controller, listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (!scrolling) controller.settleScroll() }
    }
    LaunchedEffect(controller, listState) {
        snapshotFlow { !listState.canScrollForward }
            .distinctUntilChanged()
            .collect { atEnd -> controller.onBottomChanged(atEnd) }
    }
    return controller
}

@Composable
internal fun ChatScrollFollowEffect(
    controller: ChatScrollController,
    sessionKey: Any?,
    sending: Boolean,
    smooth: Boolean,
    onStreamingStopped: () -> Unit = {},
) {
    var hadSending by remember(sessionKey) { mutableStateOf(sending) }
    val currentOnStopped = rememberUpdatedState(onStreamingStopped)
    LaunchedEffect(controller, sessionKey, sending, smooth) {
        if (sending) {
            hadSending = true
            while (isActive) {
                snapshotFlow { controller.needsFollow() }.first { it }
                controller.followUntilSettled(smooth = smooth)
            }
        } else {
            currentOnStopped.value.invoke()
            if (hadSending) {
                hadSending = false
                if (controller.following) {
                    withFrameNanos { }
                    controller.followUntilSettled(smooth = smooth)
                    withFrameNanos { }
                    if (controller.needsFollow()) {
                        controller.followUntilSettled(smooth = smooth)
                    }
                }
            }
        }
    }
}

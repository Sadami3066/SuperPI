package io.github.whywood.superpi.ui.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.github.whywood.superpi.design.DdMotion
import io.github.whywood.superpi.design.LocalDdMotionEnabled
import io.github.whywood.superpi.design.superPiColors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UNFREEZE_GRACE_MILLIS = 160L

private class ActiveTransition(
    val outgoing: AppDestination,
    val incoming: AppDestination,
    val direction: Int,
    initialProgress: Float = 0f,
) {
    val progress = Animatable(initialProgress)
}

object AppScreenTransition {
    const val SHARED_AXIS_FRACTION = 0.18f

    const val OUTGOING_PARALLAX_FRACTION = 0.12f

    const val OUTGOING_DIM_ALPHA = 0.10f

    fun direction(initial: AppDestination, target: AppDestination): Int = when {
        target.depth > initial.depth -> 1
        target.depth < initial.depth -> -1
        else -> 0
    }

    fun popTarget(stack: List<AppDestination>): AppDestination? = stack.getOrNull(stack.size - 2)

    fun translationFraction(settled: Float, incoming: Boolean, direction: Int): Float = when {
        direction == 0 -> 0f
        incoming -> (1f - settled) * direction
        else -> -settled * direction
    }

    fun alpha(settled: Float, incoming: Boolean): Float = if (incoming) settled else 1f - settled
}

@Composable
fun AnimatedScreenHost(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    predictiveBackEnabled: Boolean = true,
    base: @Composable (frozen: Boolean) -> Unit,
    content: @Composable (AppDestination) -> Unit,
) {
    val saveableState = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val motionEnabled = LocalDdMotionEnabled.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val directionSign = if (rtl) -1f else 1f
    val previewFraction = remember { mutableFloatStateOf(0f) }
    val visited = remember { mutableSetOf<String>() }
    var settledDestination by remember { mutableStateOf(navigator.current) }
    var activeTransition by remember { mutableStateOf<ActiveTransition?>(null) }
    var previewTarget by remember { mutableStateOf<AppDestination?>(null) }
    var previewDirection by remember { mutableIntStateOf(0) }
    var skipNextTransition by remember { mutableStateOf(false) }
    var reboundJob by remember { mutableStateOf<Job?>(null) }
    val preview = previewTarget.takeIf { activeTransition == null }
    val screenCovered = settledDestination != AppDestination.Chat &&
        activeTransition == null &&
        preview == null
    var baseFrozen by remember { mutableStateOf(screenCovered) }
    var baseDrawSuspended by remember { mutableStateOf(screenCovered) }
    LaunchedEffect(screenCovered, motionEnabled) {
        if (screenCovered) {
            baseFrozen = true
            baseDrawSuspended = true
        } else {
            baseDrawSuspended = false
            if (motionEnabled) delay(UNFREEZE_GRACE_MILLIS)
            baseFrozen = false
        }
    }

    PredictiveBackHandler(
        enabled = predictiveBackEnabled && navigator.canPop,
    ) { events ->
        val anchor = settledDestination
        val target = if (activeTransition == null && navigator.current == anchor) {
            AppScreenTransition.popTarget(navigator.stack)
        } else {
            null
        }
        if (target == null) {
            var completed = false
            try {
                events.collect { }
                completed = true
            } catch (_: CancellationException) {
            }
            if (completed && navigator.canPop) {
                navigator.pop()
            }
            return@PredictiveBackHandler
        }
        reboundJob?.cancel()
        previewDirection = AppScreenTransition.direction(anchor, target)
        previewFraction.floatValue = 0f
        previewTarget = target
        var predictive = false
        var collected = false
        try {
            events.collect { event ->
                predictive = true
                previewFraction.floatValue = event.progress
            }
            collected = true
        } catch (_: CancellationException) {
            if (previewTarget == target) {
                if (predictive) {
                    reboundJob = scope.launch {
                        animate(
                            initialValue = previewFraction.floatValue,
                            targetValue = 0f,
                            animationSpec = tween(DdMotion.FAST, easing = DdMotion.EaseOut),
                        ) { value, _ -> previewFraction.floatValue = value }
                        if (previewTarget == target) previewTarget = null
                    }
                } else {
                    previewTarget = null
                }
            }
            return@PredictiveBackHandler
        } finally {
            if (!collected && !predictive && previewTarget == target) {
                previewTarget = null
            }
        }
        if (previewTarget != target || activeTransition != null || navigator.current != anchor) {
            if (previewTarget == target) previewTarget = null
            return@PredictiveBackHandler
        }
        if (predictive) {
            try {
                if (motionEnabled && previewFraction.floatValue < 0.99f) {
                    animate(
                        initialValue = previewFraction.floatValue,
                        targetValue = 1f,
                        animationSpec = tween(DdMotion.FAST, easing = DdMotion.EaseOut),
                    ) { value, _ -> previewFraction.floatValue = value }
                } else {
                    previewFraction.floatValue = 1f
                }
                if (previewTarget != target || activeTransition != null || navigator.current != anchor) {
                    if (previewTarget == target) previewTarget = null
                    return@PredictiveBackHandler
                }
                skipNextTransition = true
                if (!navigator.pop()) {
                    skipNextTransition = false
                    previewTarget = null
                }
            } catch (cancelled: CancellationException) {
                if (previewTarget == target) previewTarget = null
                throw cancelled
            }
        } else {
            previewTarget = null
            navigator.pop()
        }
    }

    LaunchedEffect(navigator.current) {
        val target = navigator.current
        reboundJob?.cancel()
        if (skipNextTransition) {
            Snapshot.withMutableSnapshot {
                skipNextTransition = false
                previewTarget = null
                activeTransition = null
                settledDestination = target
            }
            withFrameNanos { }
            pruneVisitedStates(navigator, saveableState, visited)
            return@LaunchedEffect
        }
        val currentFront = activeTransition?.incoming ?: settledDestination
        if (target == currentFront) {
            if (activeTransition == null) previewTarget = null
            return@LaunchedEffect
        }
        val transition = ActiveTransition(
            outgoing = currentFront,
            incoming = target,
            direction = AppScreenTransition.direction(currentFront, target),
            initialProgress = if (motionEnabled) 0f else 1f,
        )
        Snapshot.withMutableSnapshot {
            previewTarget = null
            activeTransition = transition
        }
        try {
            if (motionEnabled) {
                transition.progress.animateTo(
                    1f,
                    animationSpec = tween(DdMotion.SCREEN, easing = DdMotion.EaseOut),
                )
            }
        } finally {
            Snapshot.withMutableSnapshot {
                if (activeTransition === transition) {
                    settledDestination = target
                    activeTransition = null
                }
            }
        }
        withFrameNanos { }
        pruneVisitedStates(navigator, saveableState, visited)
    }

    Box(modifier) {
        val currentTransition = activeTransition
        val activePreview = previewTarget.takeIf { currentTransition == null }
        val scrim = superPiColors().scrim
        val baseReveal: () -> Float = {
            when {
                activePreview == AppDestination.Chat -> (1f - previewFraction.floatValue).coerceIn(0f, 1f)
                currentTransition != null && currentTransition.outgoing == AppDestination.Chat ->
                    currentTransition.progress.value
                currentTransition != null && currentTransition.incoming == AppDestination.Chat ->
                    (1f - currentTransition.progress.value).coerceIn(0f, 1f)
                settledDestination != AppDestination.Chat -> 1f
                else -> 0f
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (!screenCovered || !baseDrawSuspended || activePreview == AppDestination.Chat) {
                        drawContent()
                        val reveal = baseReveal()
                        if (reveal > 0f) {
                            drawRect(
                                color = scrim,
                                alpha = (reveal * AppScreenTransition.OUTGOING_DIM_ALPHA).coerceIn(
                                    0f,
                                    AppScreenTransition.OUTGOING_DIM_ALPHA,
                                ),
                            )
                        }
                    }
                }
                .graphicsLayer {
                    if (!screenCovered || !baseDrawSuspended || activePreview == AppDestination.Chat) {
                        val reveal = baseReveal()
                        translationX = -reveal *
                            AppScreenTransition.OUTGOING_PARALLAX_FRACTION *
                            directionSign *
                            size.width
                    }
                },
        ) {
            base(baseFrozen)
        }
        val incoming = activePreview ?: currentTransition?.incoming ?: settledDestination
        val outgoing = when {
            activePreview != null -> settledDestination.takeIf { it != activePreview }
            currentTransition != null -> currentTransition.outgoing
            else -> null
        }
        val activeDirection = when {
            activePreview != null -> previewDirection
            currentTransition != null -> currentTransition.direction
            else -> 0
        }
        val pages = buildList {
            if (activeDirection < 0) {
                if (incoming != AppDestination.Chat) add(incoming)
                if (outgoing != null && outgoing != AppDestination.Chat) add(outgoing)
            } else {
                if (outgoing != null && outgoing != AppDestination.Chat) add(outgoing)
                if (incoming != AppDestination.Chat) add(incoming)
            }
        }
        val fraction: () -> Float = when {
            activePreview != null -> { { previewFraction.floatValue } }
            currentTransition != null -> { { currentTransition.progress.value } }
            else -> { { 1f } }
        }
        pages.forEach { page ->
            key(page.routeKey) {
                ScreenPage(
                    destination = page,
                    fraction = fraction,
                    incoming = page == incoming,
                    direction = activeDirection,
                    directionSign = directionSign,
                    saveableState = saveableState,
                    visited = visited,
                    content = content,
                )
            }
        }
        if (currentTransition != null) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
    }
}

private fun pruneVisitedStates(
    navigator: AppNavigator,
    saveableState: SaveableStateHolder,
    visited: MutableSet<String>,
) {
    val retained = navigator.stack.map { it.routeKey }.toSet()
    visited.retainAll { key ->
        val keep = key in retained
        if (!keep) saveableState.removeState(key)
        keep
    }
}

@Composable
private fun ScreenPage(
    destination: AppDestination,
    fraction: () -> Float,
    incoming: Boolean,
    direction: Int,
    directionSign: Float,
    saveableState: SaveableStateHolder,
    visited: MutableSet<String>,
    content: @Composable (AppDestination) -> Unit,
) {
    SideEffect { visited.add(destination.routeKey) }
    val scrim = superPiColors().scrim
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val settled = fraction()
                val travel = if (incoming) {
                    AppScreenTransition.SHARED_AXIS_FRACTION
                } else {
                    AppScreenTransition.OUTGOING_PARALLAX_FRACTION
                }
                translationX = AppScreenTransition.translationFraction(settled, incoming, direction) *
                    travel * directionSign * size.width
                alpha = AppScreenTransition.alpha(settled, incoming)
            }
            .drawWithContent {
                drawContent()
                if (!incoming) {
                    drawRect(
                        color = scrim,
                        alpha = (fraction() * AppScreenTransition.OUTGOING_DIM_ALPHA).coerceIn(
                            0f,
                            AppScreenTransition.OUTGOING_DIM_ALPHA,
                        ),
                    )
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
    ) {
        saveableState.SaveableStateProvider(destination.routeKey) {
            content(destination)
        }
    }
}

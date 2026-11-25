package io.github.whywood.superpi.design

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import java.text.BreakIterator
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal const val STREAM_COALESCE_INTERVAL_MS = 32L
internal const val STREAM_FADE_DURATION_MS = 320L
internal const val STREAM_STAGGER_INTERVAL_MS = 14.0
internal const val STREAM_STAGGER_MAX_DELAY_MS = 160.0
internal const val STREAM_ALPHA_LEVELS = 32
internal const val STREAM_MAX_BATCHES = 24
internal const val STREAM_MAX_UNITS_PER_BATCH = 48
internal const val STREAM_INITIAL_ANIMATE_MAX_LENGTH = 48

private const val RESTYLE_SKIP_LIMIT = 256
private const val RESTYLE_LENGTH_LIMIT = 4096
private const val RESTYLE_MINIMUM_RUN = 3
private val MARKDOWN_LEADING_SYNTAX = setOf('*', '_', '~', '`', '[')
private val MARKDOWN_BOUNDARY_SYNTAX = setOf('*', '_', '~', '`', ']', '(')

internal data class StreamTextChange(
    val previousLength: Int,
    val currentLength: Int,
    val commonPrefixLength: Int,
    val commonSuffixLength: Int,
) {
    val removedStart: Int get() = commonPrefixLength
    val removedEnd: Int get() = previousLength - commonSuffixLength
    val removedLength: Int get() = (removedEnd - removedStart).coerceAtLeast(0)
    val insertedStart: Int get() = commonPrefixLength
    val insertedEnd: Int get() = currentLength - commonSuffixLength
    val insertedLength: Int get() = (insertedEnd - insertedStart).coerceAtLeast(0)
    val hasRemoval: Boolean get() = removedLength > 0
    val hasInsertion: Boolean get() = insertedLength > 0
    val isTextUnchanged: Boolean get() = !hasRemoval && !hasInsertion

    companion object {
        fun compute(previous: String, current: String): StreamTextChange {
            val minLen = minOf(previous.length, current.length)
            var prefix = 0
            while (prefix < minLen && previous[prefix] == current[prefix]) {
                prefix++
            }
            if (prefix > 0 && prefix < minLen && Character.isHighSurrogate(previous[prefix - 1])) {
                prefix--
            }
            val maxSuffix = minLen - prefix
            var suffix = 0
            while (
                suffix < maxSuffix &&
                previous[previous.length - 1 - suffix] == current[current.length - 1 - suffix]
            ) {
                suffix++
            }
            if (suffix > 0 && suffix < maxSuffix && Character.isLowSurrogate(previous[previous.length - suffix])) {
                suffix--
            }
            return StreamTextChange(
                previousLength = previous.length,
                currentLength = current.length,
                commonPrefixLength = prefix,
                commonSuffixLength = suffix,
            )
        }
    }
}

internal data class StreamOffsetPair(
    val newOffset: Int,
    val previousOffset: Int,
)

internal class StreamRestyleMatch private constructor(
    val pairs: List<StreamOffsetPair>,
    val freshStart: Int,
) {
    fun newOffset(atOrAfter: Int, before: Int): Int? {
        var low = 0
        var high = pairs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pairs[mid].previousOffset < atOrAfter) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return if (low < pairs.size && pairs[low].previousOffset < before) {
            pairs[low].newOffset
        } else {
            null
        }
    }

    fun newEnd(before: Int): Int? {
        var low = 0
        var high = pairs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (pairs[mid].previousOffset < before) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return if (low > 0) pairs[low - 1].newOffset + 1 else null
    }

    companion object {
        fun compute(
            change: StreamTextChange,
            previous: String,
            current: String,
        ): StreamRestyleMatch {
            val insertedStart = change.insertedStart
            val insertedLen = change.insertedLength
            val removedStart = change.removedStart
            val removedLen = change.removedLength
            if (insertedLen <= 0 || removedLen <= 0 || removedLen > RESTYLE_LENGTH_LIMIT) {
                return StreamRestyleMatch(emptyList(), insertedStart)
            }
            val matchedPairs = matchDelimiterStrip(previous, removedStart, removedLen, current, insertedStart, insertedLen)
                ?: matchRuns(previous, removedStart, removedLen, current, insertedStart, insertedLen)
            if (matchedPairs.isEmpty()) {
                return StreamRestyleMatch(emptyList(), insertedStart)
            }
            var start = matchedPairs.last().newOffset + 1
            if (start > 0 && start < current.length && Character.isLowSurrogate(current[start])) {
                start++
            }
            return StreamRestyleMatch(matchedPairs, maxOf(start, insertedStart))
        }

        private fun matchDelimiterStrip(
            previous: String,
            removedStart: Int,
            removedLen: Int,
            current: String,
            insertedStart: Int,
            insertedLen: Int,
        ): List<StreamOffsetPair>? {
            var stripCount = 0
            while (stripCount < removedLen && previous[removedStart + stripCount] in MARKDOWN_LEADING_SYNTAX) {
                stripCount++
            }
            if (stripCount == 0 || stripCount >= removedLen) return null
            val remainingOld = removedLen - stripCount
            val limit = minOf(remainingOld, insertedLen)
            var matched = 0
            while (
                matched < limit &&
                previous[removedStart + stripCount + matched] == current[insertedStart + matched]
            ) {
                matched++
            }
            if (matched == 0) return null
            val closedAtBoundary = stripCount + matched == removedLen ||
                previous[removedStart + stripCount + matched] in MARKDOWN_BOUNDARY_SYNTAX
            if (!closedAtBoundary && matched < RESTYLE_MINIMUM_RUN) return null
            val pairs = ArrayList<StreamOffsetPair>(matched)
            for (i in 0 until matched) {
                pairs += StreamOffsetPair(
                    newOffset = insertedStart + i,
                    previousOffset = removedStart + stripCount + i,
                )
            }
            return pairs
        }

        private fun matchRuns(
            previous: String,
            removedStart: Int,
            removedLen: Int,
            current: String,
            insertedStart: Int,
            insertedLen: Int,
        ): List<StreamOffsetPair> {
            val checkLen = minOf(insertedLen, removedLen + RESTYLE_LENGTH_LIMIT)
            val minRun = minOf(RESTYLE_MINIMUM_RUN, removedLen)
            val accepted = ArrayList<StreamOffsetPair>()
            val run = ArrayList<StreamOffsetPair>()

            fun closeRun() {
                if (run.size >= minRun) {
                    accepted.addAll(run)
                }
                run.clear()
            }

            var oldIndex = 0
            var newIndex = 0
            while (newIndex < checkLen && oldIndex < removedLen) {
                val end = minOf(oldIndex + RESTYLE_SKIP_LIMIT, removedLen)
                var found = oldIndex
                while (found < end && previous[removedStart + found] != current[insertedStart + newIndex]) {
                    found++
                }
                if (found < end) {
                    val last = run.lastOrNull()
                    if (last != null && (last.newOffset != insertedStart + newIndex - 1 || last.previousOffset != removedStart + found - 1)) {
                        closeRun()
                    }
                    run += StreamOffsetPair(
                        newOffset = insertedStart + newIndex,
                        previousOffset = removedStart + found,
                    )
                    oldIndex = found + 1
                } else {
                    closeRun()
                }
                newIndex++
            }
            closeRun()
            return accepted
        }
    }
}

internal data class StreamFadeBatch(
    val start: Int,
    val end: Int,
    val unitStarts: List<Int>,
    val startTimeMs: Double,
    val intervalMs: Double,
    val durationMs: Double = STREAM_FADE_DURATION_MS.toDouble(),
    var levelSum: Int = -1,
)

internal class StreamTextFadeAnimator {
    private var previousText: String = ""
    private var initialized: Boolean = false
    private var wasLive: Boolean = false
    private val batches = mutableListOf<StreamFadeBatch>()

    val hasActiveBatches: Boolean get() = batches.isNotEmpty()
    internal val activeBatchCount: Int get() = batches.size

    fun finish() {
        batches.clear()
    }

    fun sync(
        currentText: String,
        live: Boolean,
        animateOnFirstFill: Boolean,
        nowMs: Long,
    ): Boolean {
        if (!initialized) {
            initialized = true
            wasLive = live
            if (live && animateOnFirstFill && currentText.isNotEmpty()) {
                previousText = ""
                applyTextChange("", currentText, nowMs)
                previousText = currentText
                return true
            }
            previousText = currentText
            batches.clear()
            return false
        }
        if (currentText == previousText) {
            wasLive = live
            return false
        }
        val shouldAnimate = live || wasLive
        wasLive = live
        if (!shouldAnimate) {
            previousText = currentText
            batches.clear()
            return true
        }
        applyTextChange(previousText, currentText, nowMs)
        previousText = currentText
        return true
    }

    fun advance(nowMs: Long): Boolean {
        if (batches.isEmpty()) return false
        var changed = false
        var index = 0
        while (index < batches.size) {
            val batch = batches[index]
            val sum = levelSum(batch, nowMs)
            if (sum != batch.levelSum) {
                batch.levelSum = sum
                changed = true
            }
            if (sum == STREAM_ALPHA_LEVELS * batch.unitStarts.size) {
                batches.removeAt(index)
                changed = true
            } else {
                index++
            }
        }
        return changed
    }

    fun levelAt(offset: Int, nowMs: Long): Int {
        val batch = batchContaining(offset) ?: return STREAM_ALPHA_LEVELS
        val unitIndex = lastIndexNotAfter(batch.unitStarts, offset)
        return levelOfUnit(batch, unitIndex, nowMs)
    }

    fun alphaAt(offset: Int, nowMs: Long): Float {
        val level = levelAt(offset, nowMs)
        return if (level >= STREAM_ALPHA_LEVELS) 1f else level.toFloat() / STREAM_ALPHA_LEVELS.toFloat()
    }

    fun applyToAnnotatedString(
        base: AnnotatedString,
        defaultColor: Color,
        nowMs: Long,
    ): AnnotatedString {
        if (batches.isEmpty() || base.isEmpty()) return base
        val length = base.length
        var anyNonOpaque = false
        val levels = IntArray(length) { STREAM_ALPHA_LEVELS }
        for (batchIndex in batches.indices) {
            val batch = batches[batchIndex]
            val bStart = batch.start.coerceIn(0, length)
            val bEnd = batch.end.coerceIn(bStart, length)
            for (offset in bStart until bEnd) {
                val level = levelAt(offset, nowMs)
                if (level < STREAM_ALPHA_LEVELS) {
                    levels[offset] = level
                    anyNonOpaque = true
                }
            }
        }
        if (!anyNonOpaque) return base
        val builder = AnnotatedString.Builder(base)
        for (batchIndex in batches.indices) {
            val batch = batches[batchIndex]
            val bStart = batch.start.coerceIn(0, length)
            val bEnd = batch.end.coerceIn(bStart, length)
            var cursor = bStart
            while (cursor < bEnd) {
                val level = levels[cursor]
                if (level >= STREAM_ALPHA_LEVELS) {
                    cursor++
                    continue
                }
                val baseColor = resolvedColorAt(base, cursor, defaultColor)
                var runEnd = cursor + 1
                while (
                    runEnd < bEnd &&
                    levels[runEnd] == level &&
                    resolvedColorAt(base, runEnd, defaultColor) == baseColor
                ) {
                    runEnd++
                }
                val alpha = level.toFloat() / STREAM_ALPHA_LEVELS.toFloat()
                val fadedColor = if (alpha <= 0f) Color.Transparent else baseColor.copy(alpha = baseColor.alpha * alpha)
                builder.addStyle(SpanStyle(color = fadedColor), cursor, runEnd)
                cursor = runEnd
            }
        }
        return builder.toAnnotatedString()
    }

    private fun applyTextChange(previous: String, current: String, nowMs: Long) {
        val change = StreamTextChange.compute(previous, current)
        if (change.isTextUnchanged) return
        val restyle = if (change.hasInsertion && change.hasRemoval) {
            StreamRestyleMatch.compute(change, previous, current)
        } else {
            null
        }
        remapBatches(change, restyle)
        val freshStart = restyle?.freshStart ?: change.insertedStart
        if (change.insertedEnd > freshStart) {
            addBatch(freshStart, change.insertedEnd, current, nowMs)
        }
    }

    private fun remapBatches(change: StreamTextChange, restyle: StreamRestyleMatch?) {
        if (batches.isEmpty()) return
        val prefix = change.commonPrefixLength
        val suffixStart = change.previousLength - change.commonSuffixLength
        val delta = change.currentLength - change.previousLength
        val remapped = ArrayList<StreamFadeBatch>(batches.size)
        for (batch in batches) {
            if (batch.end <= prefix) {
                remapped += batch
                continue
            }
            if (batch.start >= suffixStart) {
                remapped += batch.copy(
                    start = batch.start + delta,
                    end = batch.end + delta,
                    unitStarts = batch.unitStarts.map { it + delta },
                )
                continue
            }
            if (batch.start < prefix) {
                val headUnits = batch.unitStarts.filter { it < prefix }
                if (headUnits.isNotEmpty()) {
                    remapped += batch.copy(
                        end = prefix,
                        unitStarts = headUnits,
                        levelSum = -1,
                    )
                }
            }
            if (restyle != null) {
                carryBatch(batch, prefix, restyle)?.let { remapped += it }
            }
        }
        batches.clear()
        batches.addAll(remapped)
    }

    private fun carryBatch(
        batch: StreamFadeBatch,
        fromOffset: Int,
        restyle: StreamRestyleMatch,
    ): StreamFadeBatch? {
        val from = maxOf(fromOffset, batch.start)
        val location = restyle.newOffset(from, batch.end) ?: return null
        val newEnd = restyle.newEnd(batch.end) ?: return null
        if (newEnd <= location) return null
        var firstUnit = -1
        val starts = ArrayList<Int>(batch.unitStarts.size)
        for (unit in batch.unitStarts.indices) {
            val unitStart = batch.unitStarts[unit]
            val unitEnd = if (unit + 1 < batch.unitStarts.size) batch.unitStarts[unit + 1] else batch.end
            if (unitEnd <= from) continue
            val mapped = restyle.newOffset(maxOf(unitStart, from), unitEnd) ?: continue
            if (firstUnit < 0) firstUnit = unit
            if ((starts.lastOrNull() ?: -1) < mapped) {
                starts += mapped
            }
        }
        if (firstUnit < 0 || starts.isEmpty()) return null
        starts[0] = location
        return StreamFadeBatch(
            start = location,
            end = newEnd,
            unitStarts = starts,
            startTimeMs = batch.startTimeMs + firstUnit * batch.intervalMs,
            intervalMs = batch.intervalMs,
            durationMs = batch.durationMs,
        )
    }

    private fun addBatch(start: Int, end: Int, currentText: String, nowMs: Long) {
        val starts = clusterStarts(currentText, start, end, STREAM_MAX_UNITS_PER_BATCH)
        if (starts.isEmpty()) return
        var interval = if (starts.size <= 1) {
            0.0
        } else {
            minOf(STREAM_STAGGER_INTERVAL_MS, STREAM_STAGGER_MAX_DELAY_MS / (starts.size - 1).toDouble())
        }
        val position = batches.indexOfFirst { it.start > start }.let { if (it < 0) batches.size else it }
        var startTime = nowMs.toDouble()
        if (position > 0) {
            val previous = batches[position - 1]
            val previousLast = previous.startTimeMs + (previous.unitStarts.size - 1) * previous.intervalMs
            startTime = maxOf(startTime, previousLast + maxOf(interval, previous.intervalMs))
            if (starts.size > 1) {
                val latest = nowMs.toDouble() + STREAM_STAGGER_MAX_DELAY_MS
                val room = maxOf(0.0, latest - startTime)
                interval = minOf(interval, room / (starts.size - 1).toDouble())
            }
        }
        val batch = StreamFadeBatch(
            start = start,
            end = end,
            unitStarts = starts,
            startTimeMs = startTime,
            intervalMs = interval,
        )
        batches.add(position, batch)
        while (batches.size > STREAM_MAX_BATCHES) {
            val oldestIndex = batches.indices.minByOrNull { batches[it].startTimeMs } ?: break
            batches.removeAt(oldestIndex)
        }
    }

    private fun levelOfUnit(batch: StreamFadeBatch, unitIndex: Int, nowMs: Long): Int {
        val elapsed = nowMs.toDouble() - batch.startTimeMs - unitIndex.toDouble() * batch.intervalMs
        if (elapsed <= 0.0) return 0
        if (elapsed >= batch.durationMs) return STREAM_ALPHA_LEVELS
        val progress = (elapsed / batch.durationMs).toFloat().coerceIn(0f, 1f)
        val eased = easeOutCubic(progress)
        return when {
            eased <= 0f -> 0
            eased >= 1f -> STREAM_ALPHA_LEVELS
            else -> (eased * STREAM_ALPHA_LEVELS).roundToInt().coerceIn(0, STREAM_ALPHA_LEVELS)
        }
    }

    private fun levelSum(batch: StreamFadeBatch, nowMs: Long): Int {
        var sum = 0
        for (unit in batch.unitStarts.indices) {
            sum += levelOfUnit(batch, unit, nowMs)
        }
        return sum
    }

    private fun batchContaining(offset: Int): StreamFadeBatch? {
        var low = 0
        var high = batches.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (batches[mid].end <= offset) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        if (low >= batches.size) return null
        val batch = batches[low]
        return if (offset in batch.start until batch.end) batch else null
    }
}

private fun resolvedColorAt(
    base: AnnotatedString,
    offset: Int,
    defaultColor: Color,
): Color {
    var resolved = defaultColor
    val styles = base.spanStyles
    for (i in styles.indices) {
        val range = styles[i]
        if (offset in range.start until range.end && range.item.color != Color.Unspecified) {
            resolved = range.item.color
        }
    }
    return resolved
}

private fun lastIndexNotAfter(values: List<Int>, value: Int): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] <= value) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return maxOf(low - 1, 0)
}

private fun clusterStarts(text: String, start: Int, end: Int, maxCount: Int): List<Int> {
    if (start >= end) return emptyList()
    val raw = ArrayList<Int>((end - start).coerceAtMost(maxCount * 2))
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text.substring(start, end))
    var boundary = iterator.first()
    val spanLen = end - start
    while (boundary != BreakIterator.DONE && boundary < spanLen) {
        raw += start + boundary
        boundary = iterator.next()
    }
    if (raw.isEmpty()) raw += start
    val cap = maxCount.coerceAtLeast(1)
    if (raw.size <= cap) return raw
    val step = (raw.size + cap - 1) / cap
    val grouped = ArrayList<Int>(cap)
    var index = 0
    while (index < raw.size) {
        grouped += raw[index]
        index += step
    }
    return grouped
}

private fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}

@Composable
internal fun rememberCoalescedStreamText(
    text: String,
    live: Boolean,
): String {
    if (!live || text.isEmpty()) return text
    val latestText = rememberUpdatedState(text)
    var displayed by remember { mutableStateOf(text) }
    LaunchedEffect(live) {
        if (latestText.value != displayed) {
            displayed = latestText.value
        }
        while (true) {
            delay(STREAM_COALESCE_INTERVAL_MS)
            val next = latestText.value
            if (next != displayed) {
                displayed = next
            }
        }
    }
    return if (displayed.isEmpty()) text else displayed
}

@Composable
internal fun StreamingBlockReveal(
    animateOnMount: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motionEnabled = LocalDdMotionEnabled.current
    val shouldAnimate = remember { animateOnMount && motionEnabled }
    val alpha = remember { Animatable(if (shouldAnimate) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if (alpha.value < 1f) {
            if (motionEnabled) {
                alpha.animateTo(1f, tween(durationMillis = 240, easing = DdMotion.EaseOut))
            } else {
                alpha.snapTo(1f)
            }
        }
    }
    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha.value
        },
    ) {
        content()
    }
}

internal data class StreamRenderedText(
    val text: AnnotatedString,
    val alphaAt: (Int) -> Float,
)

@Composable
internal fun rememberStreamAnimatedText(
    base: AnnotatedString,
    defaultColor: Color,
    live: Boolean,
    animateOnFirstFill: Boolean,
): StreamRenderedText {
    val motionEnabled = LocalDdMotionEnabled.current
    val animator = remember { StreamTextFadeAnimator() }
    var frameClockMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var tick by remember { mutableIntStateOf(0) }

    if (!motionEnabled) {
        animator.finish()
        return StreamRenderedText(base) { 1f }
    }

    val syncTime = SystemClock.uptimeMillis()
    if (animator.sync(base.text, live, animateOnFirstFill, syncTime)) {
        frameClockMs = syncTime
    }

    val active = animator.hasActiveBatches
    LaunchedEffect(active, motionEnabled) {
        if (!active || !motionEnabled) return@LaunchedEffect
        while (animator.hasActiveBatches) {
            withFrameMillis { }
            val now = SystemClock.uptimeMillis()
            if (animator.advance(now)) {
                frameClockMs = now
                tick++
            }
        }
    }

    tick
    val renderTime = frameClockMs
    val rendered = animator.applyToAnnotatedString(base, defaultColor, renderTime)
    return StreamRenderedText(rendered) { offset -> animator.alphaAt(offset, renderTime) }
}

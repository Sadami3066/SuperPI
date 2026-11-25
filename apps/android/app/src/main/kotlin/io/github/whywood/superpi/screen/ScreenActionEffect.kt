package io.github.whywood.superpi.screen

import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ScreenFrameGrid(
    val cols: Int,
    val rows: Int,
    val luma: IntArray,
) {
    init {
        require(cols > 0 && rows > 0 && luma.size == cols * rows) {
            "Invalid ScreenFrameGrid dimensions ${cols}x$rows for ${luma.size} cells"
        }
    }

    fun diffRatio(other: ScreenFrameGrid): Float {
        if (cols != other.cols || rows != other.rows || luma.isEmpty()) return 1f
        var changed = 0
        for (i in luma.indices) {
            if (abs(luma[i] - other.luma[i]) >= LUMA_CELL_THRESHOLD) changed++
        }
        return changed.toFloat() / luma.size.toFloat()
    }

    companion object {
        const val DEFAULT_COLS = 16
        const val DEFAULT_ROWS = 16
        private const val LUMA_CELL_THRESHOLD = 12

        fun fromEncodedImage(
            bytes: ByteArray,
            cols: Int = DEFAULT_COLS,
            rows: Int = DEFAULT_ROWS,
        ): ScreenFrameGrid? {
            if (bytes.isEmpty() || cols <= 0 || rows <= 0) return null
            return runCatching {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                try {
                    val width = bitmap.width
                    val height = bitmap.height
                    if (width <= 0 || height <= 0) return@runCatching null
                    val grid = IntArray(cols * rows)
                    for (r in 0 until rows) {
                        val y0 = (r * height / rows).coerceIn(0, height - 1)
                        val y1 = (((r + 1) * height - 1) / rows).coerceIn(y0, height - 1)
                        val yc0 = y0 + (y1 - y0) / 3
                        val yc1 = y0 + ((y1 - y0) * 2) / 3
                        for (c in 0 until cols) {
                            val x0 = (c * width / cols).coerceIn(0, width - 1)
                            val x1 = (((c + 1) * width - 1) / cols).coerceIn(x0, width - 1)
                            val xc0 = x0 + (x1 - x0) / 3
                            val xc1 = x0 + ((x1 - x0) * 2) / 3
                            val sum = pixelLuma(bitmap.getPixel(xc0, yc0)) +
                                pixelLuma(bitmap.getPixel(xc1, yc0)) +
                                pixelLuma(bitmap.getPixel(xc0, yc1)) +
                                pixelLuma(bitmap.getPixel(xc1, yc1))
                            grid[r * cols + c] = sum shr 2
                        }
                    }
                    ScreenFrameGrid(cols, rows, grid)
                } finally {
                    bitmap.recycle()
                }
            }.getOrNull()
        }

        private fun pixelLuma(argb: Int): Int {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            return (77 * r + 150 * g + 29 * b) shr 8
        }
    }
}

internal enum class ScreenEffectKind(val wire: String) {
    APP_SWITCHED("app_switched"),
    WINDOW_CHANGED("window_changed"),
    UI_CHANGED("ui_changed"),
    FRAME_CHANGED("frame_changed"),
    NO_CHANGE("no_change"),
    UNKNOWN("unknown"),
}

internal enum class ScreenActionVerb {
    TAP,
    SWIPE,
    SCROLL,
    TYPE,
    KEY,
    LAUNCH,
}

internal data class ScreenActionEffect(
    val kind: ScreenEffectKind,
    val changed: Boolean,
    val packageName: String? = null,
    val windowTitle: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val updated: Int = 0,
    val focusChanged: Boolean = false,
    val frameChanged: Float? = null,
    val atEnd: Boolean = false,
    val treeSparse: Boolean = false,
    val hint: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        put("changed", changed)
        packageName?.let { put("package", it) }
        windowTitle?.let { put("title", it) }
        if (added > 0) put("added", added)
        if (removed > 0) put("removed", removed)
        if (updated > 0) put("updated", updated)
        if (focusChanged) put("focusChanged", true)
        frameChanged?.let { put("frameChanged", (it * 100f).roundToInt() / 100f) }
        if (atEnd) put("atEnd", true)
        if (treeSparse) put("treeSparse", true)
        hint?.let { put("hint", it) }
    }
}

internal data class ScreenEffectPayload(
    val effect: ScreenActionEffect? = null,
    val screen: JsonObject? = null,
) {
    companion object {
        val EMPTY = ScreenEffectPayload()
    }
}

internal fun evaluateScreenEffect(
    verb: ScreenActionVerb,
    before: GuiSnapshot?,
    after: GuiSnapshot?,
    beforeFrame: ScreenFrameGrid?,
    afterFrame: ScreenFrameGrid?,
    displayBounds: GuiBounds?,
): ScreenActionEffect {
    val sparse = after != null && ScreenDiffTracker.isTreeSparse(after, displayBounds)
    val diff = if (before != null && after != null) ScreenDiffTracker.diffNodes(before, after) else null
    val frameRatio = if (beforeFrame != null && afterFrame != null) beforeFrame.diffRatio(afterFrame) else null
    val frameSignificant = frameRatio != null && frameRatio >= FRAME_DIFF_SIGNIFICANT

    val kind = when {
        before != null && after != null &&
            before.packageName != null && after.packageName != null &&
            before.packageName != after.packageName -> ScreenEffectKind.APP_SWITCHED

        before != null && after != null &&
            ScreenDiffTracker.windowKeyOf(before) != ScreenDiffTracker.windowKeyOf(after) ->
            ScreenEffectKind.WINDOW_CHANGED

        diff != null && (diff.added.isNotEmpty() || diff.removed.isNotEmpty() ||
            diff.updated.isNotEmpty() || diff.focusChanged) -> ScreenEffectKind.UI_CHANGED

        frameSignificant -> ScreenEffectKind.FRAME_CHANGED

        (before != null && after != null) || frameRatio != null -> ScreenEffectKind.NO_CHANGE

        else -> ScreenEffectKind.UNKNOWN
    }

    val changed = kind != ScreenEffectKind.NO_CHANGE && kind != ScreenEffectKind.UNKNOWN
    val atEnd = verb == ScreenActionVerb.SCROLL &&
        kind == ScreenEffectKind.NO_CHANGE &&
        (!sparse || (frameRatio != null && !frameSignificant))

    val hints = buildList {
        if (atEnd) {
            add("Scroll produced no visible change; list may already be at the boundary")
        }
        if (sparse) {
            add(ScreenDiffTracker.SPARSE_TREE_HINT)
        }
        if (kind == ScreenEffectKind.UNKNOWN) {
            add("Action completed, but post-action state could not be observed; call screen_observe or screen_screenshot if needed")
        }
    }

    return ScreenActionEffect(
        kind = kind,
        changed = changed,
        packageName = after?.packageName,
        windowTitle = after?.windowTitle,
        added = diff?.added?.size ?: 0,
        removed = diff?.removed?.size ?: 0,
        updated = diff?.updated?.size ?: 0,
        focusChanged = diff?.focusChanged == true,
        frameChanged = frameRatio,
        atEnd = atEnd,
        treeSparse = sparse,
        hint = hints.takeIf { it.isNotEmpty() }?.joinToString("; "),
    )
}

internal suspend fun <T> runWithScreenEffect(
    controller: ScreenController,
    tracker: ScreenDiffTracker,
    verb: ScreenActionVerb,
    succeeded: (T) -> Boolean = { (it as? ScreenLaunchReport)?.let { report -> report.ok && report.landedOnExpectedDisplay } ?: true },
    action: suspend () -> T,
): Pair<T, ScreenEffectPayload> {
    val before = readEffectFact(BEFORE_OBSERVE_TIMEOUT_MS) {
        controller.observe(SCREEN_DEFAULT_MAX_DEPTH, GUI_MAX_NODES, null).snapshot
    }
    val beforeFrame = readEffectFact(FRAME_READ_TIMEOUT_MS) { controller.frameGrid() }

    val result = action()
    if (!succeeded(result)) return result to ScreenEffectPayload.EMPTY

    runCatching { controller.awaitQuiet(EFFECT_QUIET_MS, EFFECT_SETTLE_MAX_MS) }
        .onFailure { if (it is CancellationException) throw it }

    val displayBounds = readEffectFact(FRAME_READ_TIMEOUT_MS) {
        controller.effectDisplayInfo()?.let { info -> GuiBounds(0, 0, info.width, info.height) }
    }
    val afterObserved = readEffectFact(AFTER_OBSERVE_TIMEOUT_MS) {
        controller.observe(SCREEN_DEFAULT_MAX_DEPTH, GUI_MAX_NODES, null)
    }
    val afterFrame = readEffectFact(FRAME_READ_TIMEOUT_MS) { controller.frameGrid() }

    val afterScopeKey = controller.screenScopeKey()
    val effect = evaluateScreenEffect(
        verb = verb,
        before = before,
        after = afterObserved?.snapshot,
        beforeFrame = beforeFrame,
        afterFrame = afterFrame,
        displayBounds = displayBounds,
    )
    val screen = afterObserved?.let {
        tracker.formatObserve(
            observed = it,
            scopeKey = afterScopeKey,
            scanLimits = SCREEN_DEFAULT_MAX_DEPTH to GUI_MAX_NODES,
            displayBounds = displayBounds,
        )
    }
    return result to ScreenEffectPayload(effect = effect, screen = screen)
}

private suspend fun <V> readEffectFact(timeoutMs: Long, block: suspend () -> V): V? = try {
    withTimeoutOrNull(timeoutMs) { block() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

private const val FRAME_DIFF_SIGNIFICANT = 0.02f
private const val BEFORE_OBSERVE_TIMEOUT_MS = 300L
private const val AFTER_OBSERVE_TIMEOUT_MS = 500L
private const val FRAME_READ_TIMEOUT_MS = 150L
private const val EFFECT_QUIET_MS = 300L
private const val EFFECT_SETTLE_MAX_MS = 700L

package io.github.whywood.superpi.screen

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenActionEffectTest {
    private val displayBounds = GuiBounds(0, 0, 1080, 2400)

    @Test
    fun evaluatesAppSwitchWindowChangeUiChangeFrameChangeNoChangeAndUnknown() {
        val base = snapshot(
            packageName = "com.app.a",
            windowTitle = "Home",
            children = listOf(
                GuiNode("android.widget.Button", GuiBounds(0, 0, 200, 100), text = "Go", resourceId = "id/go"),
                GuiNode("android.widget.TextView", GuiBounds(0, 120, 200, 180), text = "A"),
                GuiNode("android.widget.TextView", GuiBounds(0, 200, 200, 260), text = "B"),
            ),
        )

        val appSwitch = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = base,
            after = base.copy(packageName = "com.app.b"),
            beforeFrame = null,
            afterFrame = null,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.APP_SWITCHED, appSwitch.kind)
        assertTrue(appSwitch.changed)

        val winChange = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = base,
            after = base.copy(windowTitle = "Dialog"),
            beforeFrame = null,
            afterFrame = null,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.WINDOW_CHANGED, winChange.kind)

        val edited = snapshot(
            packageName = "com.app.a",
            windowTitle = "Home",
            children = listOf(
                GuiNode("android.widget.Button", GuiBounds(0, 0, 200, 100), text = "Gone", resourceId = "id/go"),
                GuiNode("android.widget.TextView", GuiBounds(0, 120, 200, 180), text = "A"),
                GuiNode("android.widget.TextView", GuiBounds(0, 200, 200, 260), text = "B"),
            ),
        )
        val uiChange = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = base,
            after = edited,
            beforeFrame = null,
            afterFrame = null,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.UI_CHANGED, uiChange.kind)
        assertEquals(1, uiChange.updated)

        val f1 = ScreenFrameGrid(4, 4, IntArray(16) { 0 })
        val f2 = ScreenFrameGrid(4, 4, IntArray(16) { if (it < 4) 100 else 0 })
        val frameChange = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = base,
            after = base,
            beforeFrame = f1,
            afterFrame = f2,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.FRAME_CHANGED, frameChange.kind)
        assertTrue(frameChange.changed)

        val scrollAtEnd = evaluateScreenEffect(
            verb = ScreenActionVerb.SCROLL,
            before = base,
            after = base,
            beforeFrame = f1,
            afterFrame = f1,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.NO_CHANGE, scrollAtEnd.kind)
        assertFalse(scrollAtEnd.changed)
        assertTrue(scrollAtEnd.atEnd)

        val unknown = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = base,
            after = null,
            beforeFrame = null,
            afterFrame = null,
            displayBounds = displayBounds,
        )
        assertEquals(ScreenEffectKind.UNKNOWN, unknown.kind)
        assertFalse(unknown.changed)

        val json = appSwitch.toJson().toString()
        assertFalse(json.contains("\"backend\""))
        assertFalse(json.contains("\"displayId\""))
        assertFalse(json.contains("\"taskDisplayId\""))
        assertFalse(json.contains("\"landedOnMain\""))
    }

    @Test
    fun detectsSparseTreeAndHintsScreenshot() {
        val sparse = snapshot(
            packageName = "com.game",
            children = listOf(GuiNode("android.view.SurfaceView", GuiBounds(0, 0, 1080, 2400))),
        )
        assertTrue(ScreenDiffTracker.isTreeSparse(sparse, displayBounds))
        val effect = evaluateScreenEffect(
            verb = ScreenActionVerb.TAP,
            before = sparse,
            after = sparse,
            beforeFrame = null,
            afterFrame = null,
            displayBounds = displayBounds,
        )
        assertTrue(effect.treeSparse)
        assertTrue(effect.hint.orEmpty().contains("screen_screenshot"))
    }

    @Test
    fun diffTrackerResetsAtMaxStreakTruncationAndLargeChangeFallback() {
        val tracker = ScreenDiffTracker()
        val s1 = snapshot(
            packageName = "com.app",
            children = (1..8).map {
                GuiNode("android.widget.TextView", GuiBounds(0, it * 50, 200, it * 50 + 40), text = "Item $it", resourceId = "id/item_$it")
            },
        )
        val first = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1), "main", 20 to 400)
        assertEquals("full", first["mode"]?.toString()?.trim('"'))

        repeat(ScreenDiffTracker.MAX_DIFF_STREAK) {
            val d = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1), "main", 20 to 400)
            assertEquals("diff", d["mode"]?.toString()?.trim('"'))
        }
        val resetAfterStreak = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1), "main", 20 to 400)
        assertEquals("full", resetAfterStreak["mode"]?.toString()?.trim('"'))

        val filtered = tracker.formatObserve(
            ScreenObserveResult(BACKEND_ACCESSIBILITY, s1),
            "main",
            20 to 400,
            filters = listOf(GuiSelector.Text("Item 1")),
        )
        assertEquals("full", filtered["mode"]?.toString()?.trim('"'))
        val afterFiltered = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1), "main", 20 to 400)
        assertEquals("diff", afterFiltered["mode"]?.toString()?.trim('"'))

        val replaced = snapshot(
            packageName = "com.app",
            children = (1..8).map {
                GuiNode("android.widget.Button", GuiBounds(500, it * 60, 800, it * 60 + 40), text = "New $it")
            },
        )
        val largeDiffFallback = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, replaced), "main", 20 to 400)
        assertEquals("full", largeDiffFallback["mode"]?.toString()?.trim('"'))

        tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1.copy(truncated = true)), "main", 20 to 400)
        val afterTruncated = tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, s1), "main", 20 to 400)
        assertEquals("full", afterTruncated["mode"]?.toString()?.trim('"'))
    }

    @Test
    fun executionLockSerializesConcurrentRuns() = runBlocking {
        val lock = ScreenExecutionLock()
        val trace = mutableListOf<String>()
        coroutineScope {
            val first = async {
                lock.withLock {
                    trace += "a-start"
                    delay(40)
                    trace += "a-end"
                }
            }
            val second = async {
                delay(10)
                lock.withLock {
                    trace += "b-start"
                    trace += "b-end"
                }
            }
            first.await()
            second.await()
        }
        assertEquals(listOf("a-start", "a-end", "b-start", "b-end"), trace)
    }

    @Test
    fun runWithScreenEffectObservesLiveBeforeStateInsteadOfStaleBaseline() = runBlocking {
        val tracker = ScreenDiffTracker()
        val oldApp = snapshot(
            packageName = "com.app.a",
            children = listOf(GuiNode("android.widget.Button", GuiBounds(0, 0, 200, 100), text = "Old")),
        )
        tracker.formatObserve(ScreenObserveResult(BACKEND_ACCESSIBILITY, oldApp), "main", SCREEN_DEFAULT_MAX_DEPTH to GUI_MAX_NODES)

        val liveApp = snapshot(
            packageName = "com.app.b",
            children = listOf(GuiNode("android.widget.Button", GuiBounds(0, 0, 200, 100), text = "Live")),
        )
        val controller = object : ScreenController {
            override fun screenScopeKey(): String = "main"
            override suspend fun status() = ScreenStatusSnapshot(
                accessibilityConnected = true,
                accessibilityEnabled = true,
                virtualState = "offline",
            )
            override suspend fun observe(maxDepth: Int, maxNodes: Int, expectedPackage: String?) =
                ScreenObserveResult(BACKEND_ACCESSIBILITY, liveApp)
            override suspend fun find(selectors: List<GuiSelector>, limit: Int) =
                ScreenFindResult(BACKEND_ACCESSIBILITY, liveApp, emptyList())
            override suspend fun tap(selector: GuiSelector, durationMs: Int) = "Tapped"
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = "Swiped"
            override suspend fun scroll(direction: GuiScrollDirection, amount: Int, selector: GuiSelector?) = "Scrolled"
            override suspend fun typeText(selector: GuiSelector?, text: String) = ScreenTextResult(BACKEND_ACCESSIBILITY, text, true)
            override suspend fun key(name: String) = "Keyed"
            override suspend fun waitFor(packageName: String?, text: String?, textGone: String?, timeoutMs: Long) =
                ScreenWaitOutcome(true, packageName)
            override suspend fun screenshot() = ScreenCapture(byteArrayOf(), 0, 0, BACKEND_ACCESSIBILITY)
            override suspend fun launch(packageName: String) = ScreenLaunchReport(true, "Launched", packageName, 0, 0)
            override suspend fun listApps() = emptyList<ScreenApp>()
        }

        val (_, payload) = runWithScreenEffect(controller, tracker, ScreenActionVerb.TAP) { "Tapped" }
        assertEquals(ScreenEffectKind.NO_CHANGE, payload.effect?.kind)
        assertEquals("com.app.b", payload.effect?.packageName)
    }

    private fun snapshot(
        packageName: String,
        windowTitle: String? = null,
        children: List<GuiNode>,
    ): GuiSnapshot = GuiNodeTree.buildSnapshot(
        root = GuiNode(
            className = "android.widget.FrameLayout",
            bounds = displayBounds,
            children = children,
        ),
        packageName = packageName,
        windowTitle = windowTitle,
    )
}

package io.github.whywood.superpi.screen

import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.ai.TextContent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenBatchToolTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validatesStepsUpFrontBeforeExecutingAnyStep() = runBlocking {
        val controller = BatchFakeController()
        val batch = batchToolFrom(controller)

        val empty = batch.execute("1", buildJsonObject { put("steps", buildJsonArray {}) }, null, null)
        assertEquals(true, empty.isError)
        assertEquals(0, controller.actionCalls)

        val tooMany = batch.execute(
            "2",
            buildJsonObject {
                put("steps", buildJsonArray { repeat(11) { add(step("key", buildJsonObject { put("key", "back") })) } })
            },
            null,
            null,
        )
        assertEquals(true, tooMany.isError)

        val nestedTarget = batch.execute(
            "3",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                        add(step("key", buildJsonObject { put("key", "enter"); put("target", "main") }))
                    },
                )
            },
            null,
            null,
        )
        assertEquals(true, nestedTarget.isError)
        assertEquals(0, controller.actionCalls)
    }

    @Test
    fun executesMultiStepBatchUnderSingleLockAndRedactsSecretType() = runBlocking {
        val controller = BatchFakeController().apply {
            typeResult = ScreenTextResult(BACKEND_ACCESSIBILITY, "", false, secret = true)
        }
        val batch = batchToolFrom(controller)
        val result = batch.execute(
            "b1",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 50); put("y", 60) }))
                        add(step("type", buildJsonObject { put("ref", "1"); put("text", "Secret99") }))
                        add(step("key", buildJsonObject { put("key", "enter") }))
                    },
                )
            },
            null,
            null,
        )

        assertNull(result.isError)
        assertEquals(1, controller.lockEntries.get())
        assertEquals(3, controller.actionCalls)

        val raw = (result.content.single() as TextContent).text
        assertFalse(raw.contains("Secret99"))
        val json = Json.parseToJsonElement(raw).jsonObject
        assertEquals(true, json["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(3, json["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertTrue(json["screen"] is JsonObject)
        val typeStep = json.getValue("steps").jsonArray[1].jsonObject
        assertEquals(true, typeStep["secret"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("(hidden)", typeStep["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun stopsEarlyOnPackageChangeAtEndAndStepFailure() = runBlocking {
        val controller = BatchFakeController()
        controller.onTap = {
            controller.snapshot = snapshot(packageName = "com.other.app", text = "Moved")
        }
        val batch = batchToolFrom(controller)

        val pkgStopped = batch.execute(
            "pkg",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                        add(step("key", buildJsonObject { put("key", "back") }))
                    },
                )
            },
            null,
            null,
        )
        assertNull(pkgStopped.isError)
        val pkgJson = Json.parseToJsonElement((pkgStopped.content.single() as TextContent).text).jsonObject
        assertEquals(false, pkgJson["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("package_changed", pkgJson["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, pkgJson["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, controller.actionCalls)

        controller.resetActions()
        val atEndStopped = batch.execute(
            "end",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("scroll", buildJsonObject { put("direction", "down") }))
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                    },
                )
            },
            null,
            null,
        )
        val endJson = Json.parseToJsonElement((atEndStopped.content.single() as TextContent).text).jsonObject
        assertEquals("at_end", endJson["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, controller.actionCalls)

        controller.resetActions()
        controller.failNextTap = IllegalStateException("tap failed")
        val failed = batch.execute(
            "fail",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("key", buildJsonObject { put("key", "back") }))
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                        add(step("key", buildJsonObject { put("key", "enter") }))
                    },
                )
            },
            null,
            null,
        )
        assertEquals(true, failed.isError)
        val failJson = Json.parseToJsonElement((failed.content.single() as TextContent).text).jsonObject
        assertEquals("step_failed", failJson["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, failJson["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, failJson["steps"]?.jsonArray?.size)
    }

    @Test
    fun abortsCleanlyMidBatchWhenSignalTriggered() {
        val controller = BatchFakeController()
        val signal = RunSignal()
        controller.onTap = { signal.abort("user stopped") }
        val batch = batchToolFrom(controller)

        assertThrows(RequestAbortedException::class.java) {
            runBlocking {
                batch.execute(
                    "abort",
                    buildJsonObject {
                        put(
                            "steps",
                            buildJsonArray {
                                add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                                add(step("key", buildJsonObject { put("key", "back") }))
                            },
                        )
                    },
                    signal,
                    null,
                )
            }
        }
        assertEquals(1, controller.actionCalls)
    }

    @Test
    fun abortsBeforeFirstStepWhenInitialObserveFails() = runBlocking {
        val controller = BatchFakeController().apply { failObserve = true }
        val batch = batchToolFrom(controller)

        val result = batch.execute(
            "init-fail",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                    },
                )
            },
            null,
            null,
        )

        assertEquals(true, result.isError)
        assertEquals(0, controller.actionCalls)
        val json = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals(false, json["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, json["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertEquals("observe_failed", json["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertNull(json["screen"])
        assertTrue(json["hint"]?.jsonPrimitive?.contentOrNull.orEmpty().isNotBlank())
    }

    @Test
    fun abortsBeforeFirstStepWhenPackageChangedSinceLastObserve() = runBlocking {
        val controller = BatchFakeController()
        val tools = toolsFrom(controller)
        tools.getValue("screen_observe").execute("obs", buildJsonObject {}, null, null)

        controller.snapshot = snapshot(packageName = "com.other.app", text = "Switched")
        val result = tools.getValue("screen_batch").execute(
            "pkg-drift",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                    },
                )
            },
            null,
            null,
        )

        assertNull(result.isError)
        assertEquals(0, controller.actionCalls)
        val json = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals(false, json["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, json["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertEquals("package_changed", json["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertEquals("com.other.app", json["screen"]?.jsonObject?.get("package")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun stopsOnMidBatchObserveFailureWithoutStaleScreen() = runBlocking {
        val controller = BatchFakeController()
        controller.onTap = { controller.failObserve = true }
        val batch = batchToolFrom(controller)

        val result = batch.execute(
            "mid-fail",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("tap", buildJsonObject { put("x", 10); put("y", 20) }))
                        add(step("key", buildJsonObject { put("key", "back") }))
                    },
                )
            },
            null,
            null,
        )

        assertNull(result.isError)
        assertEquals(1, controller.actionCalls)
        val json = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals(false, json["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, json["completedSteps"]?.jsonPrimitive?.intOrNull)
        assertEquals("observe_failed", json["stoppedReason"]?.jsonPrimitive?.contentOrNull)
        assertNull(json["screen"])
        assertTrue(json["hint"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("screen_observe"))
    }

    @Test
    fun hidesSelectorlessTypeTextInBatchOutput() = runBlocking {
        val controller = BatchFakeController()
        val batch = batchToolFrom(controller)

        val result = batch.execute(
            "blind-type",
            buildJsonObject {
                put(
                    "steps",
                    buildJsonArray {
                        add(step("type", buildJsonObject { put("text", "BlindBatchSecret88") }))
                    },
                )
            },
            null,
            null,
        )

        assertNull(result.isError)
        val raw = (result.content.single() as TextContent).text
        assertFalse(raw.contains("BlindBatchSecret88"))
        val json = Json.parseToJsonElement(raw).jsonObject
        val step0 = json.getValue("steps").jsonArray.single().jsonObject
        assertEquals("(hidden)", step0["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, step0["verified"]?.jsonPrimitive?.booleanOrNull)
    }

    private fun toolsFrom(controller: ScreenController) =
        screenTools(controller, File(tempFolder.root, "screens")).associateBy { it.name }

    private fun batchToolFrom(controller: ScreenController) =
        toolsFrom(controller).getValue("screen_batch")

    private fun step(
        action: String,
        args: JsonObject,
        stopOnPackageChange: Boolean? = null,
    ): JsonObject = buildJsonObject {
        put("action", action)
        put("args", args)
        stopOnPackageChange?.let { put("stopOnPackageChange", it) }
    }

    private class BatchFakeController : ScreenController {
        private val lock = ScreenExecutionLock()
        val lockEntries = AtomicInteger()
        var actionCalls = 0
        var failObserve = false
        var failNextTap: Throwable? = null
        var onObserve: (() -> Unit)? = null
        var onTap: (() -> Unit)? = null
        var typeResult: ScreenTextResult? = null
        var snapshot = snapshot(packageName = "com.example", text = "Initial")

        fun resetActions() {
            actionCalls = 0
            failObserve = false
            failNextTap = null
            onObserve = null
            onTap = null
        }

        override suspend fun <T> withExecutionLock(block: suspend () -> T): T {
            lockEntries.incrementAndGet()
            return lock.withLock(block)
        }

        override suspend fun effectDisplayInfo(): ScreenDisplayInfo =
            ScreenDisplayInfo(9, 1080, 2400, BACKEND_ACCESSIBILITY)

        override fun screenScopeKey(): String = "virtual:inst"

        override suspend fun status(): ScreenStatusSnapshot =
            ScreenStatusSnapshot(accessibilityConnected = true, accessibilityEnabled = true, virtualState = "online")

        override suspend fun observe(maxDepth: Int, maxNodes: Int, expectedPackage: String?): ScreenObserveResult {
            onObserve?.invoke()
            if (failObserve) error("observe unavailable")
            return ScreenObserveResult(BACKEND_ACCESSIBILITY, snapshot)
        }

        override suspend fun find(selectors: List<GuiSelector>, limit: Int): ScreenFindResult =
            ScreenFindResult(BACKEND_ACCESSIBILITY, snapshot, emptyList())

        override suspend fun tap(selector: GuiSelector, durationMs: Int): String {
            failNextTap?.let { throw it }
            actionCalls++
            onTap?.invoke()
            return "Tapped"
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): String {
            actionCalls++
            return "Swiped"
        }

        override suspend fun scroll(direction: GuiScrollDirection, amount: Int, selector: GuiSelector?): String {
            actionCalls++
            return "Scrolled"
        }

        override suspend fun typeText(selector: GuiSelector?, text: String): ScreenTextResult {
            actionCalls++
            return typeResult ?: ScreenTextResult(BACKEND_ACCESSIBILITY, text, true)
        }

        override suspend fun key(name: String): String {
            actionCalls++
            return "Keyed"
        }

        override suspend fun waitFor(packageName: String?, text: String?, textGone: String?, timeoutMs: Long): ScreenWaitOutcome {
            actionCalls++
            return ScreenWaitOutcome(matched = true, packageName = snapshot.packageName)
        }

        override suspend fun screenshot(): ScreenCapture = ScreenCapture(byteArrayOf(1), 1080, 2400, BACKEND_ACCESSIBILITY)

        override suspend fun launch(packageName: String): ScreenLaunchReport {
            actionCalls++
            snapshot = snapshot(packageName = packageName, text = "Launched")
            return ScreenLaunchReport(true, "Launched", packageName, 9, 9)
        }

        override suspend fun listApps(): List<ScreenApp> = emptyList()
    }

    private companion object {
        fun snapshot(packageName: String, text: String): GuiSnapshot = GuiNodeTree.buildSnapshot(
            root = GuiNode(
                className = "android.widget.FrameLayout",
                bounds = GuiBounds(0, 0, 1080, 2400),
                children = listOf(
                    GuiNode("android.widget.Button", GuiBounds(10, 10, 200, 100), text = text, clickable = true),
                    GuiNode("android.widget.TextView", GuiBounds(10, 120, 200, 180), text = "Static 1"),
                    GuiNode("android.widget.TextView", GuiBounds(10, 200, 200, 260), text = "Static 2"),
                ),
            ),
            packageName = packageName,
        )
    }
}

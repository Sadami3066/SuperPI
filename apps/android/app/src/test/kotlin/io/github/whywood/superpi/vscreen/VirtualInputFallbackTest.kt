package io.github.whywood.superpi.vscreen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualInputFallbackTest {
    private val display = VirtualScreenState.Online(
        displayId = 9,
        width = 720,
        height = 1280,
        backend = "adb",
        instanceId = "inst-1",
    )

    @Test
    fun buildsFallbackCommandsForTapLongPressSwipeAndKey() {
        val tap = VirtualFallbackInput.fromCommand(
            display,
            VirtualScreenCommands.TAP,
            buildJsonObject { put("x", 100); put("y", 200); put("durationMs", 60) },
        )
        assertEquals("/system/bin/input -d 9 tap 100 200", tap?.line)

        val longPress = VirtualFallbackInput.fromCommand(
            display,
            VirtualScreenCommands.TAP,
            buildJsonObject { put("x", 100); put("y", 200); put("durationMs", 500) },
        )
        assertEquals("/system/bin/input -d 9 swipe 100 200 100 200 500", longPress?.line)

        val swipe = VirtualFallbackInput.fromCommand(
            display,
            VirtualScreenCommands.SWIPE,
            buildJsonObject { put("x1", 10); put("y1", 20); put("x2", 300); put("y2", 400); put("durationMs", 250) },
        )
        assertEquals("/system/bin/input -d 9 swipe 10 20 300 400 250", swipe?.line)

        val key = VirtualFallbackInput.fromCommand(
            display,
            VirtualScreenCommands.KEY,
            buildJsonObject { put("key", "back") },
        )
        assertEquals("/system/bin/input -d 9 keyevent 4", key?.line)
    }

    @Test
    fun rejectsOutOfBoundsInvalidDisplayAndTextCommands() {
        assertNull(
            VirtualFallbackInput.fromCommand(
                display,
                VirtualScreenCommands.TAP,
                buildJsonObject { put("x", 720); put("y", 100) },
            ),
        )
        assertNull(
            VirtualFallbackInput.fromCommand(
                display.copy(displayId = 0),
                VirtualScreenCommands.TAP,
                buildJsonObject { put("x", 10); put("y", 10) },
            ),
        )
        assertNull(
            VirtualFallbackInput.fromCommand(
                display,
                VirtualScreenCommands.TEXT,
                buildJsonObject { put("text", "hello") },
            ),
        )
        assertNull(
            VirtualFallbackInput.fromCommand(
                display,
                VirtualScreenCommands.KEY,
                buildJsonObject { put("key", "power") },
            ),
        )
    }

    @Test
    fun fallbackTriggersOnlyWhenDeniedBeforeDispatchOnSameDisplayInstance() {
        val params = buildJsonObject { put("x", 10); put("y", 20) }
        val allowed = virtualInputFallback(
            expected = display,
            current = display.copy(),
            command = VirtualScreenCommands.TAP,
            params = params,
            errorCode = VirtualScreenCommands.INPUT_DENIED_BEFORE_DISPATCH,
        )
        assertEquals("/system/bin/input -d 9 tap 10 20", allowed?.line)

        assertNull(
            virtualInputFallback(
                expected = display,
                current = display.copy(instanceId = "inst-2"),
                command = VirtualScreenCommands.TAP,
                params = params,
                errorCode = VirtualScreenCommands.INPUT_DENIED_BEFORE_DISPATCH,
            ),
        )
        assertNull(
            virtualInputFallback(
                expected = display,
                current = display.copy(displayId = 10),
                command = VirtualScreenCommands.TAP,
                params = params,
                errorCode = VirtualScreenCommands.INPUT_DENIED_BEFORE_DISPATCH,
            ),
        )
        assertNull(
            virtualInputFallback(
                expected = display,
                current = VirtualScreenState.Offline,
                command = VirtualScreenCommands.TAP,
                params = params,
                errorCode = VirtualScreenCommands.INPUT_DENIED_BEFORE_DISPATCH,
            ),
        )
        assertNull(
            virtualInputFallback(
                expected = display,
                current = display,
                command = VirtualScreenCommands.TAP,
                params = params,
                errorCode = null,
            ),
        )
    }

    @Test
    fun applyFallbackWrapsAcknowledgeErrorsAndPropagatesCancellation() {
        runBlocking {
            var acked = false
            applyVirtualFallbackInput(send = {}, acknowledge = { acked = true })
            assertTrue(acked)
        }

        val wrapped = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                applyVirtualFallbackInput(
                    send = {},
                    acknowledge = { error("socket closed") },
                )
            }
        }
        assertTrue(wrapped.message.orEmpty().contains("Observe before retrying"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                applyVirtualFallbackInput(
                    send = {},
                    acknowledge = { throw CancellationException("cancelled") },
                )
            }
        }
        assertTrue(virtualTextDeniedMessage().contains("Accessibility"))
    }
}

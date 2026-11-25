package io.github.whywood.superpi.ui.common.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityThinkingTest {
    @Test
    fun cleanThinkingLineStripsMarkdownAndRejectsDividers() {
        assertEquals(
            "Check active workers and inspect logs",
            cleanThinkingLine("### **Check active workers** and `inspect` logs"),
        )
        assertEquals(
            "Reviewing SuperThreadEngine fallback behavior",
            cleanThinkingLine("1. Reviewing __SuperThreadEngine__ fallback behavior"),
        )
        assertEquals(
            "Verify route transitions",
            cleanThinkingLine("- [x] ~~Verify~~ route transitions"),
        )
        assertNull(cleanThinkingLine("   ---   "))
        assertNull(cleanThinkingLine("   >   "))
    }

    @Test
    fun extractLiveThinkingLineReturnsLastMeaningfulLineWithIndices() {
        val raw = """
            ### Plan
            1. Check existing code

            ---

            > Inspecting **AppScreenTransition** now
        """.trimIndent()
        val line = extractLiveThinkingLine(raw)
        assertEquals(
            LiveThinkingLine(
                paragraphIndex = 5,
                segmentIndex = 0,
                text = "Inspecting AppScreenTransition now",
            ),
            line,
        )
        assertNull(extractLiveThinkingLine("   \n***\n   "))
    }

    @Test
    fun splitThinkingSegmentsBreaksLongParagraphsAtScreenWidth() {
        val cjk = "先检查悬浮窗点击逻辑，再确认工具参数校验是否同步生效，最后查看真机实际表现"
        val cjkSegments = splitThinkingSegments(cjk, maxUnits = 32)
        assertEquals(
            listOf(
                "先检查悬浮窗点击逻辑，",
                "再确认工具参数校验是否同步生效，",
                "最后查看真机实际表现",
            ),
            cjkSegments,
        )

        val latin = "Inspecting FloatingWindowController tap handling across overlay windows"
        val latinSegments = splitThinkingSegments(latin, maxUnits = 24)
        assertTrue(latinSegments.size >= 3)
        assertFalse(latinSegments.any { it.isBlank() })

        val unbrokenToken = "SuperPIApplicationConfigurationManagerFactoryImplementation"
        val tokenSegments = splitThinkingSegments(unbrokenToken, maxUnits = 18)
        assertEquals(4, tokenSegments.size)
        assertEquals(unbrokenToken, tokenSegments.joinToString(""))
    }

    @Test
    fun extractLiveThinkingLineAdvancesSegmentIndexAsSingleParagraphGrows() {
        val initial = "Inspecting FloatingWindowController"
        val extended = "Inspecting FloatingWindowController tap dispatch and panel dismissal state across windows"
        val first = extractLiveThinkingLine(initial) { splitThinkingSegments(it, maxUnits = 24) }!!
        val later = extractLiveThinkingLine(extended) { splitThinkingSegments(it, maxUnits = 24) }!!

        assertEquals(0, first.paragraphIndex)
        assertEquals(0, later.paragraphIndex)
        assertTrue(later.segmentIndex > first.segmentIndex)
        assertNotEquals(first.slotKey, later.slotKey)
    }

    @Test
    fun shouldSwapThinkingLineSwapsSnappilyAcrossSegmentsAndParagraphs() {
        val first = LiveThinkingLine(paragraphIndex = 0, segmentIndex = 0, text = "Step one")
        val grownFirst = LiveThinkingLine(paragraphIndex = 0, segmentIndex = 0, text = "Step one extended")
        val nextSegment = LiveThinkingLine(paragraphIndex = 0, segmentIndex = 1, text = "second line segment")
        val nextParagraph = LiveThinkingLine(paragraphIndex = 4, segmentIndex = 0, text = "Step five")

        assertEquals(380L, MIN_THINKING_LINE_DWELL_MS)
        assertTrue(shouldSwapThinkingLine(current = null, latest = first, elapsedSinceSwapMs = 0L))
        assertTrue(shouldSwapThinkingLine(current = first, latest = grownFirst, elapsedSinceSwapMs = 120L))
        assertFalse(shouldSwapThinkingLine(current = grownFirst, latest = nextSegment, elapsedSinceSwapMs = 200L))
        assertTrue(
            shouldSwapThinkingLine(
                current = grownFirst,
                latest = nextSegment,
                elapsedSinceSwapMs = MIN_THINKING_LINE_DWELL_MS,
            ),
        )
        assertFalse(shouldSwapThinkingLine(current = nextSegment, latest = nextParagraph, elapsedSinceSwapMs = 250L))
        assertTrue(
            shouldSwapThinkingLine(
                current = nextSegment,
                latest = nextParagraph,
                elapsedSinceSwapMs = MIN_THINKING_LINE_DWELL_MS,
            ),
        )
        assertTrue(shouldSwapThinkingLine(current = nextParagraph, latest = null, elapsedSinceSwapMs = 100L))
    }
}

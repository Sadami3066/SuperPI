package io.github.whywood.superpi.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DdMarkdownStreamRevealTest {
    @Test
    fun firstFillImmediateModeAvoidsReplayingHistory() {
        val animator = StreamTextFadeAnimator()
        val changed = animator.sync(
            currentText = "Existing streamed paragraph loaded into view",
            live = true,
            animateOnFirstFill = false,
            nowMs = 1_000L,
        )

        assertFalse(changed)
        assertFalse(animator.hasActiveBatches)
        assertEquals(1f, animator.alphaAt(0, 1_000L), 0.001f)
        assertEquals(1f, animator.alphaAt(43, 1_000L), 0.001f)
    }

    @Test
    fun freshFirstFillStartsAtZeroAlphaWithoutOpaqueFlash() {
        val animator = StreamTextFadeAnimator()
        val changed = animator.sync(
            currentText = "你好",
            live = true,
            animateOnFirstFill = true,
            nowMs = 1_000L,
        )

        assertTrue(changed)
        assertTrue(animator.hasActiveBatches)
        assertEquals(0, animator.levelAt(0, 1_000L))
        assertEquals(0f, animator.alphaAt(0, 1_000L), 0.001f)
        assertEquals(0f, animator.alphaAt(1, 1_000L), 0.001f)
    }

    @Test
    fun chainedStaggerCarriesWaveSmoothlyAcrossBurstsAndFinishesCleanly() {
        val animator = StreamTextFadeAnimator()
        animator.sync("你好", live = true, animateOnFirstFill = true, nowMs = 1_000L)
        assertTrue(animator.advance(1_016L))
        val firstCharMidAlpha = animator.alphaAt(0, 1_016L)
        assertTrue(firstCharMidAlpha > 0f && firstCharMidAlpha < 1f)

        animator.sync("你好世界测试", live = true, animateOnFirstFill = true, nowMs = 1_032L)
        assertEquals(firstCharMidAlpha, animator.alphaAt(0, 1_032L), 0.25f)
        assertEquals(0f, animator.alphaAt(2, 1_032L), 0.001f)

        assertTrue(animator.advance(1_120L))
        assertTrue(animator.alphaAt(0, 1_120L) > animator.alphaAt(2, 1_120L))
        assertTrue(animator.alphaAt(2, 1_120L) > animator.alphaAt(5, 1_120L))

        val endTime = 1_032L + STREAM_FADE_DURATION_MS + STREAM_STAGGER_MAX_DELAY_MS.toLong() + 50L
        assertTrue(animator.advance(endTime))
        assertFalse(animator.hasActiveBatches)
        assertEquals(1f, animator.alphaAt(5, endTime), 0.001f)
        assertFalse(animator.advance(endTime + 16L))
    }

    @Test
    fun inlineMarkdownRestylingKeepsSeenCharactersOpaqueAndFadesOnlyNewSuffix() {
        val animator = StreamTextFadeAnimator()
        animator.sync("这是 **重", live = true, animateOnFirstFill = false, nowMs = 1_000L)
        assertFalse(animator.hasActiveBatches)

        animator.sync("这是 重点", live = true, animateOnFirstFill = false, nowMs = 1_032L)
        assertEquals(1f, animator.alphaAt(3, 1_032L), 0.001f)
        assertEquals(0f, animator.alphaAt(4, 1_032L), 0.001f)

        val linkAnimator = StreamTextFadeAnimator()
        linkAnimator.sync("打开 [首页](https://example.com", live = true, animateOnFirstFill = false, nowMs = 2_000L)
        linkAnimator.sync("打开 首页，继续", live = true, animateOnFirstFill = false, nowMs = 2_032L)
        assertEquals(1f, linkAnimator.alphaAt(3, 2_032L), 0.001f)
        assertEquals(1f, linkAnimator.alphaAt(4, 2_032L), 0.001f)
        assertEquals(0f, linkAnimator.alphaAt(5, 2_032L), 0.001f)
        assertEquals(0f, linkAnimator.alphaAt(7, 2_032L), 0.001f)
    }

    @Test
    fun applyToAnnotatedStringPreservesSpanColorsAndSkipsAllocationWhenSettled() {
        val accent = Color(0xFF0A84FF)
        val defaultInk = Color(0xFF111111)
        val base = buildAnnotatedString {
            append("读 ")
            withStyle(SpanStyle(color = accent)) {
                append("文档")
            }
        }
        val animator = StreamTextFadeAnimator()
        animator.sync(base.text, live = true, animateOnFirstFill = true, nowMs = 1_000L)
        animator.advance(1_120L)

        val mid = animator.applyToAnnotatedString(base, defaultInk, 1_120L)
        assertEquals(base.text, mid.text)
        val fadedLinkColor = mid.spanStyles
            .last { 2 in it.start until it.end && it.item.color != Color.Unspecified }
            .item
            .color
        assertEquals(accent.red, fadedLinkColor.red, 0.01f)
        assertEquals(accent.green, fadedLinkColor.green, 0.01f)
        assertEquals(accent.blue, fadedLinkColor.blue, 0.01f)
        assertTrue(fadedLinkColor.alpha in 0.05f..0.95f)

        val settledTime = 1_000L + STREAM_FADE_DURATION_MS + STREAM_STAGGER_MAX_DELAY_MS.toLong() + 50L
        animator.advance(settledTime)
        val settled = animator.applyToAnnotatedString(base, defaultInk, settledTime)
        assertSame(base, settled)
    }

    @Test
    fun fastBurstExceedingMaxBatchesRetiresOldestBatchCleanly() {
        val animator = StreamTextFadeAnimator()
        var text = ""
        animator.sync(text, live = true, animateOnFirstFill = false, nowMs = 1_000L)
        repeat(STREAM_MAX_BATCHES + 6) { index ->
            text += "${index % 10}"
            animator.sync(text, live = true, animateOnFirstFill = false, nowMs = 1_000L + index * 20L)
        }
        assertTrue(animator.activeBatchCount <= STREAM_MAX_BATCHES)
        assertEquals(1f, animator.alphaAt(0, 1_500L), 0.001f)
    }
}

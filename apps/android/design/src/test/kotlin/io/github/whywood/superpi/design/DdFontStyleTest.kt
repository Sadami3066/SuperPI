package io.github.whywood.superpi.design

import org.junit.Assert.assertEquals
import org.junit.Test

class DdFontStyleTest {
    @Test
    fun missingPreferenceFallsBackToManrope() {
        assertEquals(DdFontStyle.MANROPE, DdFontStyle.fromId(null))
        assertEquals(DdFontStyle.MANROPE, DdFontStyle.fromId(""))
        assertEquals(DdFontStyle.MANROPE, DdFontStyle.fromId("unknown"))
    }

    @Test
    fun savedStylesRoundTrip() {
        DdFontStyle.entries.forEach { style ->
            assertEquals(style, DdFontStyle.fromId(style.id))
        }
    }
}

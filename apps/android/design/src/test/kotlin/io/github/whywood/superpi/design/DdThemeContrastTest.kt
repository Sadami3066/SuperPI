package io.github.whywood.superpi.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class DdThemeContrastTest {
    @Test
    fun allPalettesKeepTextAndSurfacesReadable() {
        val palettes = listOf(
            "lightPi" to DdTheme.lightPi(),
            "darkPi" to DdTheme.darkPi(),
            "lightPaper" to DdTheme.lightPaper(),
            "darkPaper" to DdTheme.darkPaper(),
            "lightSlate" to DdTheme.lightSlate(),
            "darkSlate" to DdTheme.darkSlate(),
            "lightClassicBlue" to DdTheme.lightClassicBlue(),
            "lightSantorini" to DdTheme.lightSantorini(),
            "darkOceanic" to DdTheme.darkOceanic(),
        )
        palettes.forEach { (name, colors) ->
            assertContrast("$name ink/paper", colors.ink, colors.paper, 7f)
            assertContrast("$name ink/surface", colors.ink, colors.surface, 7f)
            assertContrast("$name inkSecondary/paper", colors.inkSecondary, colors.paper, 4.5f)
            assertContrast("$name inkTertiary/paper", colors.inkTertiary, colors.paper, 4.5f)
            assertContrast("$name accent/surface", colors.accent, colors.surface, 4.5f)
            assertContrast("$name onAccent/accent", colors.onAccent, colors.accent, 4.5f)
            assertContrast("$name danger/paper", colors.danger, colors.paper, 4.5f)
            assertContrast("$name success/paper", colors.success, colors.paper, 4.5f)
            assertContrast("$name warning/paper", colors.warning, colors.paper, 4.5f)
            assertContrast("$name codeComment/codeBackground", colors.codeComment, colors.codeBackground, 4.5f)
            assertContrast("$name wash/raised", colors.wash, colors.raised, 1.12f)
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val ratio = ratio(foreground, background)
        assertTrue("$label contrast $ratio is below $minimum", ratio >= minimum)
    }

    private fun ratio(a: Color, b: Color): Float {
        val first = luminance(a)
        val second = luminance(b)
        val high = maxOf(first, second)
        val low = minOf(first, second)
        return (high + 0.05f) / (low + 0.05f)
    }

    private fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    private fun channel(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
}

package io.github.whywood.superpi.theme

import io.github.whywood.superpi.design.DdTheme
import io.github.whywood.superpi.design.DdThemeVisualFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeChoiceTest {
    @Test
    fun parsesBuiltInChoicesAndMigratesLegacy() {
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pi"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("system"))
        assertEquals(ThemeChoice.TokyoNight, ThemeChoice.parse("tokyo-night"))
        assertEquals(ThemeChoice.NightOwl, ThemeChoice.parse("night-owl"))
        assertEquals(ThemeChoice.Catppuccin, ThemeChoice.parse("catppuccin"))
        assertEquals(ThemeChoice.Nord, ThemeChoice.parse("nord"))
        assertEquals(ThemeChoice.RosePine, ThemeChoice.parse("rose-pine"))
        assertEquals(ThemeChoice.Gruvbox, ThemeChoice.parse("gruvbox"))
        assertEquals(ThemeChoice.Dracula, ThemeChoice.parse("dracula"))
        assertEquals(ThemeChoice.Solarized, ThemeChoice.parse("solarized"))

        // Legacy preset migration
        assertEquals(ThemeChoice.Gruvbox, ThemeChoice.parse("paper"))
        assertEquals(ThemeChoice.Nord, ThemeChoice.parse("slate"))
        assertEquals(ThemeChoice.TokyoNight, ThemeChoice.parse("classic"))
        assertEquals(ThemeChoice.TokyoNight, ThemeChoice.parse("classic-blue"))
        assertEquals(ThemeChoice.Solarized, ThemeChoice.parse("santorini"))
        assertEquals(ThemeChoice.NightOwl, ThemeChoice.parse("oceanic"))
    }

    @Test
    fun visualFamilyMapsAllChoices() {
        assertEquals(DdThemeVisualFamily.MONO, ThemeChoice.Pi.visualFamily())
        assertEquals(DdThemeVisualFamily.VIOLET, ThemeChoice.TokyoNight.visualFamily())
        assertEquals(DdThemeVisualFamily.VIOLET, ThemeChoice.Catppuccin.visualFamily())
        assertEquals(DdThemeVisualFamily.VIOLET, ThemeChoice.Dracula.visualFamily())
        assertEquals(DdThemeVisualFamily.OCEAN, ThemeChoice.NightOwl.visualFamily())
        assertEquals(DdThemeVisualFamily.OCEAN, ThemeChoice.Nord.visualFamily())
        assertEquals(DdThemeVisualFamily.OCEAN, ThemeChoice.Solarized.visualFamily())
        assertEquals(DdThemeVisualFamily.WARM, ThemeChoice.Gruvbox.visualFamily())
        assertEquals(DdThemeVisualFamily.WARM, ThemeChoice.RosePine.visualFamily())
        assertEquals(DdThemeVisualFamily.CUSTOM, ThemeChoice.Pack("com.example.plugin", "aurora").visualFamily())
    }

    @Test
    fun parsesAppearanceModesAndResolvesDark() {
        assertEquals(ThemeAppearanceMode.SYSTEM, ThemeAppearanceMode.parse(null))
        assertEquals(ThemeAppearanceMode.SYSTEM, ThemeAppearanceMode.parse("unknown"))
        assertEquals(ThemeAppearanceMode.LIGHT, ThemeAppearanceMode.parse("light"))
        assertEquals(ThemeAppearanceMode.DARK, ThemeAppearanceMode.parse("dark"))
        assertEquals(true, ThemeAppearanceMode.SYSTEM.resolveDark(true))
        assertEquals(false, ThemeAppearanceMode.SYSTEM.resolveDark(false))
        assertEquals(false, ThemeAppearanceMode.LIGHT.resolveDark(true))
        assertEquals(true, ThemeAppearanceMode.DARK.resolveDark(false))
    }

    @Test
    fun parsesPackChoice() {
        val choice = ThemeChoice.parse("pack:com.example.plugin/aurora")
        assertEquals(ThemeChoice.Pack("com.example.plugin", "aurora"), choice)
        assertEquals("pack:com.example.plugin/aurora", choice.key)
    }

    @Test
    fun invalidValuesFallBackToPi() {
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse(null))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse(""))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("unknown"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pack:missing"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pack:com.example.plugin/"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pack:com.example.plugin/a/b"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pack:Com.Example/Aurora"))
        assertEquals(ThemeChoice.Pi, ThemeChoice.parse("pack:plain/aurora"))
    }

    @Test
    fun packFactoryValidatesIds() {
        assertEquals(ThemeChoice.Pack("com.example.plugin", "aurora"), ThemeChoice.pack("com.example.plugin", "aurora"))
        assertNull(ThemeChoice.pack("com.example.plugin", "Aurora"))
        assertNull(ThemeChoice.pack("com.example.plugin", ""))
        assertNull(ThemeChoice.pack("plugin", "aurora"))
    }

    @Test
    fun builtInChoicesIncludeAllNineThemesAndExcludeFollowSystem() {
        assertEquals(
            listOf(
                ThemeChoice.Pi,
                ThemeChoice.TokyoNight,
                ThemeChoice.NightOwl,
                ThemeChoice.Catppuccin,
                ThemeChoice.Nord,
                ThemeChoice.RosePine,
                ThemeChoice.Gruvbox,
                ThemeChoice.Dracula,
                ThemeChoice.Solarized,
            ),
            ThemeChoice.builtIn,
        )
        assertEquals(null, ThemeChoice.fromKey("system"))
    }

    @Test
    fun keyRoundTrips() {
        ThemeChoice.builtIn.forEach { choice ->
            assertEquals(choice, ThemeChoice.fromKey(choice.key))
        }
        val pack = ThemeChoice.Pack("com.example.plugin", "aurora")
        assertEquals(pack, ThemeChoice.fromKey(pack.key))
    }

    @Test
    fun paletteCodecRoundTripsAllBuiltInThemesAndLegacy25ColorStrings() {
        ThemeChoice.builtIn.forEach { choice ->
            val light = ThemePalettes.of(choice, dark = false)
            val dark = ThemePalettes.of(choice, dark = true)
            assertEquals(light, ThemePaletteCodec.decode(ThemePaletteCodec.encode(light)))
            assertEquals(dark, ThemePaletteCodec.decode(ThemePaletteCodec.encode(dark)))
        }
        // Backward compatibility with legacy 25-color encoded plugin cache strings
        val legacyEncoded = ThemePaletteCodec.encode(DdTheme.lightClassicBlue())
            .let { full ->
                val prefix = full.substringBefore(';')
                val first25 = full.substringAfter(';').split(',').take(25).joinToString(",")
                "$prefix;$first25"
            }
        assertEquals(DdTheme.lightClassicBlue(), ThemePaletteCodec.decode(legacyEncoded))
        assertNull(ThemePaletteCodec.decode(null))
        assertNull(ThemePaletteCodec.decode("nonsense"))
    }
}

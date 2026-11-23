package io.github.whywood.superpi.data

import android.content.Context
import io.github.whywood.superpi.design.SuperPIColors
import io.github.whywood.superpi.theme.ThemeAppearanceMode
import io.github.whywood.superpi.theme.ThemeChoice
import io.github.whywood.superpi.theme.ThemePaletteCodec

data class ThemeSelection(
    val choice: ThemeChoice,
    val appearance: ThemeAppearanceMode = ThemeAppearanceMode.SYSTEM,
    val palette: SuperPIColors?,
    val label: String?,
)

class ThemePreferences(context: Context) {
    private val store = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(): ThemeSelection {
        val rawChoice = store.getString(CHOICE_KEY, null)
        val rawAppearance = store.getString(APPEARANCE_KEY, null)
        val appearance = if (rawAppearance != null) {
            ThemeAppearanceMode.parse(rawAppearance)
        } else {
            when (rawChoice) {
                "oceanic" -> ThemeAppearanceMode.DARK
                "classic", "santorini" -> ThemeAppearanceMode.LIGHT
                else -> ThemeAppearanceMode.SYSTEM
            }
        }
        val choice = ThemeChoice.parse(rawChoice)
        if (choice !is ThemeChoice.Pack) return ThemeSelection(choice, appearance, null, null)
        return ThemeSelection(
            choice = choice,
            appearance = appearance,
            palette = ThemePaletteCodec.decode(store.getString(PALETTE_KEY, null)),
            label = store.getString(LABEL_KEY, null),
        )
    }

    fun write(
        choice: ThemeChoice,
        appearance: ThemeAppearanceMode,
        palette: SuperPIColors?,
        label: String?,
    ) {
        store.edit().apply {
            putString(CHOICE_KEY, choice.key)
            putString(APPEARANCE_KEY, appearance.key)
            if (palette == null) {
                remove(PALETTE_KEY)
                remove(LABEL_KEY)
            } else {
                putString(PALETTE_KEY, ThemePaletteCodec.encode(palette))
                putString(LABEL_KEY, label)
            }
        }.apply()
    }

    private companion object {
        const val NAME = "appearance"
        const val CHOICE_KEY = "theme_choice"
        const val APPEARANCE_KEY = "theme_appearance"
        const val PALETTE_KEY = "theme_palette"
        const val LABEL_KEY = "theme_label"
    }
}

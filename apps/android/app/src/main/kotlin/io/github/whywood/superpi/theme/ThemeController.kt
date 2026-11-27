package io.github.whywood.superpi.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.ThemePreferences
import io.github.whywood.superpi.design.DdTheme
import io.github.whywood.superpi.design.SuperPIColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HostThemeState(
    val choice: ThemeChoice,
    val appearance: ThemeAppearanceMode,
    val colors: SuperPIColors?,
    val label: String,
)

sealed interface ThemeSource {
    data object BuiltIn : ThemeSource

    data class Plugin(val pluginId: String, val pluginName: String) : ThemeSource
}

data class ThemeOption(
    val id: ThemeChoice,
    val label: String,
    val source: ThemeSource,
    val dark: Boolean,
    val swatch: List<Color>,
    val colors: SuperPIColors,
)

object ThemePalettes {
    fun of(choice: ThemeChoice, dark: Boolean): SuperPIColors = when (choice) {
        ThemeChoice.Pi -> if (dark) DdTheme.darkPi() else DdTheme.lightPi()
        ThemeChoice.TokyoNight -> if (dark) DdTheme.darkTokyoNight() else DdTheme.lightTokyoNight()
        ThemeChoice.NightOwl -> if (dark) DdTheme.darkNightOwl() else DdTheme.lightNightOwl()
        ThemeChoice.Catppuccin -> if (dark) DdTheme.darkCatppuccin() else DdTheme.lightCatppuccin()
        ThemeChoice.Nord -> if (dark) DdTheme.darkNord() else DdTheme.lightNord()
        ThemeChoice.RosePine -> if (dark) DdTheme.darkRosePine() else DdTheme.lightRosePine()
        ThemeChoice.Gruvbox -> if (dark) DdTheme.darkGruvbox() else DdTheme.lightGruvbox()
        ThemeChoice.Dracula -> if (dark) DdTheme.darkDracula() else DdTheme.lightDracula()
        ThemeChoice.Solarized -> if (dark) DdTheme.darkSolarized() else DdTheme.lightSolarized()
        is ThemeChoice.Pack -> if (dark) DdTheme.darkPi() else DdTheme.lightPi()
    }
}

object HostThemeRuntime {
    @Volatile
    var dark: Boolean = false
}

class ThemeController(
    private val context: Context,
    private val preferences: ThemePreferences,
    private val catalog: ThemeCatalog,
    private val pluginSources: () -> List<PluginThemeSource>,
    private val language: () -> String,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HostThemeState> = _state.asStateFlow()

    init {
        HostThemeRuntime.dark = _state.value.colors?.dark
            ?: ThemePalettes.of(
                _state.value.choice,
                _state.value.appearance.resolveDark(systemDark()),
            ).dark
    }

    fun refresh() {
        catalog.refresh(pluginSources())
        catalog.packs()
        resolve(_state.value.choice, _state.value.appearance)
    }

    fun setChoice(choice: ThemeChoice) {
        if (choice is ThemeChoice.Pack && catalog.find(choice.pluginId, choice.themeId) == null) return
        resolve(choice, _state.value.appearance)
    }

    fun setAppearance(mode: ThemeAppearanceMode) {
        val current = _state.value
        if (current.choice is ThemeChoice.Pack) {
            preferences.write(current.choice, mode, current.colors, current.label)
            _state.value = current.copy(appearance = mode)
            return
        }
        applyBuiltIn(current.choice, mode)
    }

    fun builtInOptions(systemDark: Boolean = systemDark()): List<ThemeOption> =
        catalog.builtInOptions(
            label = ::builtInLabel,
            systemDark = _state.value.appearance.resolveDark(systemDark),
        )

    fun pluginOptions(): List<ThemeOption> = catalog.pluginOptions(language())

    private fun resolve(choice: ThemeChoice, appearance: ThemeAppearanceMode) {
        when (choice) {
            is ThemeChoice.Pack -> {
                val theme = catalog.find(choice.pluginId, choice.themeId)
                if (theme == null) fallbackToDefault(appearance) else applyPack(choice, appearance, theme)
            }

            else -> applyBuiltIn(choice, appearance)
        }
    }

    private fun applyPack(choice: ThemeChoice.Pack, appearance: ThemeAppearanceMode, theme: ThemePackTheme) {
        val label = theme.name.resolve(language())
        HostThemeRuntime.dark = theme.dark
        preferences.write(choice, appearance, theme.colors, label)
        _state.value = HostThemeState(choice, appearance, theme.colors, label)
    }

    private fun applyBuiltIn(choice: ThemeChoice, appearance: ThemeAppearanceMode) {
        HostThemeRuntime.dark = ThemePalettes.of(choice, appearance.resolveDark(systemDark())).dark
        preferences.write(choice, appearance, null, null)
        _state.value = HostThemeState(choice, appearance, null, builtInLabel(choice))
    }

    private fun fallbackToDefault(appearance: ThemeAppearanceMode) {
        HostThemeRuntime.dark = ThemePalettes.of(ThemeChoice.Pi, appearance.resolveDark(systemDark())).dark
        preferences.write(ThemeChoice.Pi, appearance, null, null)
        _state.value = HostThemeState(ThemeChoice.Pi, appearance, null, builtInLabel(ThemeChoice.Pi))
    }

    private fun initialState(): HostThemeState {
        val selection = preferences.read()
        return when (val choice = selection.choice) {
            is ThemeChoice.Pack -> HostThemeState(
                choice = choice,
                appearance = selection.appearance,
                colors = selection.palette,
                label = selection.label ?: choice.themeId,
            )

            else -> HostThemeState(choice, selection.appearance, null, builtInLabel(choice))
        }
    }

    private fun builtInLabel(choice: ThemeChoice): String = context.getString(
        when (choice) {
            ThemeChoice.Pi -> R.string.settings_theme_preset_pi
            ThemeChoice.TokyoNight -> R.string.settings_theme_preset_tokyo_night
            ThemeChoice.NightOwl -> R.string.settings_theme_preset_night_owl
            ThemeChoice.Catppuccin -> R.string.settings_theme_preset_catppuccin
            ThemeChoice.Nord -> R.string.settings_theme_preset_nord
            ThemeChoice.RosePine -> R.string.settings_theme_preset_rose_pine
            ThemeChoice.Gruvbox -> R.string.settings_theme_preset_gruvbox
            ThemeChoice.Dracula -> R.string.settings_theme_preset_dracula
            ThemeChoice.Solarized -> R.string.settings_theme_preset_solarized
            is ThemeChoice.Pack -> R.string.settings_theme
        },
    )

    private fun systemDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

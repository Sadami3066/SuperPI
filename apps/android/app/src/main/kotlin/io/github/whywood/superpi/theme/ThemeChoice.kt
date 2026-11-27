package io.github.whywood.superpi.theme

import io.github.whywood.superpi.design.DdThemeVisualFamily

enum class ThemeAppearanceMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun parse(raw: String?): ThemeAppearanceMode = entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}

sealed interface ThemeChoice {
    val key: String

    data object Pi : ThemeChoice {
        override val key: String = "pi"
    }

    data object TokyoNight : ThemeChoice {
        override val key: String = "tokyo-night"
    }

    data object NightOwl : ThemeChoice {
        override val key: String = "night-owl"
    }

    data object Catppuccin : ThemeChoice {
        override val key: String = "catppuccin"
    }

    data object Nord : ThemeChoice {
        override val key: String = "nord"
    }

    data object RosePine : ThemeChoice {
        override val key: String = "rose-pine"
    }

    data object Gruvbox : ThemeChoice {
        override val key: String = "gruvbox"
    }

    data object Dracula : ThemeChoice {
        override val key: String = "dracula"
    }

    data object Solarized : ThemeChoice {
        override val key: String = "solarized"
    }

    data class Pack(val pluginId: String, val themeId: String) : ThemeChoice {
        override val key: String = "$PACK_PREFIX$pluginId/$themeId"
    }

    fun visualFamily(): DdThemeVisualFamily = when (this) {
        Pi -> DdThemeVisualFamily.MONO
        TokyoNight, Catppuccin, Dracula -> DdThemeVisualFamily.VIOLET
        NightOwl, Nord, Solarized -> DdThemeVisualFamily.OCEAN
        Gruvbox, RosePine -> DdThemeVisualFamily.WARM
        is Pack -> DdThemeVisualFamily.CUSTOM
    }

    companion object {
        const val PACK_PREFIX: String = "pack:"

        val builtIn: List<ThemeChoice> = listOf(
            Pi,
            TokyoNight,
            NightOwl,
            Catppuccin,
            Nord,
            RosePine,
            Gruvbox,
            Dracula,
            Solarized,
        )

        fun parse(raw: String?): ThemeChoice = fromKey(raw) ?: Pi

        fun fromKey(raw: String?): ThemeChoice? {
            if (raw.isNullOrBlank()) return null
            return when (raw) {
                Pi.key -> Pi
                TokyoNight.key, "classic", "classic-blue" -> TokyoNight
                NightOwl.key, "oceanic" -> NightOwl
                Catppuccin.key -> Catppuccin
                Nord.key, "slate" -> Nord
                RosePine.key -> RosePine
                Gruvbox.key, "paper" -> Gruvbox
                Dracula.key -> Dracula
                Solarized.key, "santorini" -> Solarized
                else -> pack(raw)
            }
        }

        fun pack(pluginId: String, themeId: String): Pack? =
            if (isValidPluginId(pluginId) && ThemePackCodec.isValidThemeId(themeId)) {
                Pack(pluginId, themeId)
            } else {
                null
            }

        private fun pack(raw: String): Pack? {
            if (!raw.startsWith(PACK_PREFIX)) return null
            val body = raw.removePrefix(PACK_PREFIX)
            val slash = body.indexOf('/')
            if (slash <= 0 || slash != body.lastIndexOf('/')) return null
            return pack(body.substring(0, slash), body.substring(slash + 1))
        }

        private fun isValidPluginId(value: String): Boolean =
            value.length <= 128 && PLUGIN_ID.matches(value)

        private val PLUGIN_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
    }
}

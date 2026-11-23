package io.github.whywood.superpi.theme

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

    data object Paper : ThemeChoice {
        override val key: String = "paper"
    }

    data object Slate : ThemeChoice {
        override val key: String = "slate"
    }

    data class Pack(val pluginId: String, val themeId: String) : ThemeChoice {
        override val key: String = "$PACK_PREFIX$pluginId/$themeId"
    }

    companion object {
        const val PACK_PREFIX: String = "pack:"

        val builtIn: List<ThemeChoice> = listOf(Pi, Paper, Slate)

        fun parse(raw: String?): ThemeChoice = fromKey(raw) ?: Pi

        fun fromKey(raw: String?): ThemeChoice? {
            if (raw.isNullOrBlank()) return null
            return when (raw) {
                Pi.key -> Pi
                Paper.key -> Paper
                Slate.key, "classic", "santorini", "oceanic" -> Slate
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

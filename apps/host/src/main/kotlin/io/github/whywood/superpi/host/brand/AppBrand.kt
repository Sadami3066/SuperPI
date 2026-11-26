package io.github.whywood.superpi.host.brand

import java.util.Properties

/**
 * Single source of truth for user-facing Host product naming, CLI command name, and home directory.
 * Internal Kotlin package names stay decoupled so renaming the product only touches this object.
 */
object AppBrand {
    const val CLI_NAME = "super-pi"
    const val DISPLAY_NAME = "SuperPI"
    const val HOME_DIR_NAME = ".super-pi"
    const val ENV_PREFIX = "SUPER_PI"
    const val ENV_HOME = "${ENV_PREFIX}_HOME"
    const val RELAY_PLATFORM_CLI = "cli"
    const val SETTINGS_SCHEMA_URL = "https://sadami3066.github.io/SuperPI/schemas/settings.schema.json"

    val VERSION: String by lazy {
        val props = Properties()
        AppBrand::class.java.classLoader
            ?.getResourceAsStream("super-pi-build.properties")
            ?.use { props.load(it) }
        props.getProperty("version")?.takeIf { it.isNotBlank() } ?: "2.0.3"
    }
}

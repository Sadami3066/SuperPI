package io.github.whywood.superpi.core

import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.ModelSettings
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.ThinkingLevel

internal fun resolve(
    current: ModelConfig,
    configured: Set<String>,
    catalog: ProviderDirectory,
): ModelConfig? {
    if (configured.isEmpty()) return null
    if (current.providerId in configured && catalog.models(current.providerId).isNotEmpty()) {
        val resolved = catalog.resolveConfig(current)
        return resolved.takeIf { it != current }
    }
    val target = catalog.summaries.firstOrNull { it.id in configured && catalog.models(it.id).isNotEmpty() }
        ?: catalog.summaries.firstOrNull { it.id in configured }
        ?: return null
    val resolved = catalog.resolveConfig(
        ModelConfig(providerId = target.id, modelId = "", thinkingLevel = ThinkingLevel.OFF),
    )
    return resolved.takeIf { it != current }
}

class ModelSelection(
    private val catalog: ProviderDirectory,
    private val settings: ModelSettings,
) {
    suspend fun ensure(configured: Set<String>): ModelConfig? {
        val current = settings.read()
        val next = resolve(current, configured, catalog) ?: return null
        if (next == current) return null
        settings.save(next)
        return next
    }
}

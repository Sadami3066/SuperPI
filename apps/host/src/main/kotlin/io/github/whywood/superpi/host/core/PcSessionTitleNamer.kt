package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.runtime.ModelOption
import io.github.whywood.superpi.runtime.ProviderCatalog
import io.github.whywood.superpi.runtime.SessionTitleNamer
import io.github.whywood.superpi.runtime.logWarn

class PcSessionTitleNamer(
    private val catalog: ProviderCatalog,
    private val configuredProviders: () -> Set<String>,
) : SessionTitleNamer {
    override suspend fun title(firstUserText: String): String? {
        val configured = configuredProviders()
        val models = configured.flatMap { providerId -> catalog.models(providerId) }
        val candidates = rank(models).ifEmpty { models.take(1) }
        if (candidates.isEmpty()) return null
        val prompt = buildString {
            append("Write a short session title for the user message below, at most 6 words. ")
            append("Use the language the user wrote in and keep names and terms as the user wrote them. ")
            append("Output only the title: no quotes, punctuation or explanation.\n\nUser message:\n")
            append(firstUserText.take(2000))
        }
        for (model in candidates.take(2)) {
            val raw = runCatching { catalog.complete(model.providerId, model.id, prompt) }
                .onFailure { logWarn("SessionTitle", "title completion failed ${model.providerId}/${model.id}", it) }
                .getOrNull() ?: continue
            val sanitized = sanitizeTitle(raw)
            if (sanitized != null) return sanitized
        }
        return null
    }

    private fun rank(models: List<ModelOption>): List<ModelOption> = models.sortedWith(
        compareByDescending<ModelOption> { score(it) }
            .thenBy { it.providerId }
            .thenBy { it.displayName },
    ).filter { score(it) > 0 }

    private fun score(model: ModelOption): Int {
        val key = "${model.id} ${model.displayName}".lowercase()
        return when {
            "deepseek" in key && "flash" in key -> 100
            "flash" in key -> 90
            "haiku" in key -> 80
            "mini" in key -> 75
            else -> 0
        }
    }

    private fun sanitizeTitle(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val stripped = line
            .removePrefix("Title:")
            .removePrefix("Title：")
            .removePrefix("标题：")
            .removePrefix("标题:")
            .trim()
            .trim('"', '\'', '“', '”', '「', '」', '《', '》', '*', '#', '-', ' ')
            .trimEnd('.', '。', '，', ',', '！', '!', '？', '?', '；', ';', ':')
            .replace(Regex("\\s+"), " ")
        if (stripped.isBlank()) return null
        return if (stripped.length <= 48) stripped else stripped.take(48).trimEnd()
    }
}

package io.github.whywood.superpi.host.market

import kotlinx.serialization.Serializable

internal object HostMarketDefaults {
    const val PAGE_BASE_URL = "https://sadami3066.github.io/SuperPI-Market/"
    const val REGISTRY_URL = PAGE_BASE_URL + "registry.json"
    const val SIGNATURE_SUFFIX = ".sig"
    const val KEY_ID = "ec256:db63256431bd66db"
    const val PUBLIC_KEY_DER_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEycLAgCQiJ8JZqgHDTJdV5GqJobXPr6zfbr4GyL0UlXo19QnEtTfBLHo3uMaVHUVDkX67nDhkwqdp94JbIGONA=="
    const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
    const val MAX_REGISTRY_BYTES = 2L * 1024L * 1024L
    const val MAX_ENTRIES = 512
    const val MAX_PLUGIN_BYTES = 50L * 1024L * 1024L
    const val MAX_SKILL_BYTES = 20L * 1024L * 1024L
}

enum class HostMarketEntryType { Plugin, Skill, Mcp }

data class HostLocalizedText(
    val values: Map<String, String>,
) {
    fun resolve(language: String = "zh"): String {
        val key = language.lowercase().substringBefore('-').substringBefore('_')
        return values[key] ?: values["zh"] ?: values.values.firstOrNull().orEmpty()
    }
}

data class HostMarketArtifact(
    val url: String,
    val sha256: String,
    val size: Long,
)

data class HostMarketSkillMeta(
    val license: String?,
    val source: String?,
)

enum class HostMarketMcpAuthType { None, ApiKey, OAuth }

data class HostMarketMcpAuth(
    val type: HostMarketMcpAuthType,
    val header: String? = null,
    val prefix: String? = null,
    val placeholder: HostLocalizedText? = null,
    val helpUrl: String? = null,
)

data class HostMarketMcpMeta(
    val url: String,
    val transport: String,
    val headers: Map<String, String>,
    val authHint: HostLocalizedText?,
    val auth: HostMarketMcpAuth? = null,
) {
    fun resolvedHeaders(apiKey: String?): Map<String, String> {
        val resolved = headers.toMutableMap()
        val authCfg = auth ?: return resolved
        if (authCfg.type == HostMarketMcpAuthType.ApiKey) {
            val token = apiKey?.trim().orEmpty()
            if (token.isEmpty()) error("Access key is required")
            val header = authCfg.header?.takeIf { it.isNotBlank() }
                ?: error("Connector is missing authorization header configuration")
            resolved[header] = authCfg.prefix.orEmpty() + token
        }
        return resolved
    }
}

data class HostMarketEntry(
    val type: HostMarketEntryType,
    val id: String,
    val category: String? = null,
    val name: HostLocalizedText,
    val summary: HostLocalizedText,
    val description: HostLocalizedText,
    val icon: String? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val version: String,
    val artifact: HostMarketArtifact? = null,
    val skill: HostMarketSkillMeta? = null,
    val mcp: HostMarketMcpMeta? = null,
)

data class HostMarketRegistry(
    val schemaVersion: Int,
    val generatedAt: String,
    val keyId: String,
    val entries: List<HostMarketEntry>,
)

@Serializable
data class MarketMcpAuthDto(
    val type: String,
    val header: String? = null,
    val prefix: String? = null,
    val placeholder: Map<String, String> = emptyMap(),
    val helpUrl: String? = null,
)

@Serializable
data class MarketMcpMetaDto(
    val url: String,
    val transport: String,
    val authHint: Map<String, String> = emptyMap(),
    val auth: MarketMcpAuthDto? = null,
)

@Serializable
data class MarketEntryDto(
    val id: String,
    val type: String,
    val category: String? = null,
    val name: Map<String, String>,
    val summary: Map<String, String>,
    val description: Map<String, String>,
    val icon: String? = null,
    val tags: Map<String, List<String>> = emptyMap(),
    val version: String,
    val license: String? = null,
    val sourceUrl: String? = null,
    val mcp: MarketMcpMetaDto? = null,
    val installState: String,
    val installedVersion: String? = null,
    val installedName: String? = null,
)

@Serializable
data class MarketCatalogResponse(
    val entries: List<MarketEntryDto>,
    val source: String,
    val fetchedAt: Long,
    val stale: Boolean = false,
    val warning: String? = null,
)

@Serializable
data class MarketInstallRequest(
    val entryId: String,
    val apiKey: String? = null,
)

@Serializable
data class MarketInstallResponse(
    val ok: Boolean = true,
    val entryId: String,
    val type: String,
    val installedName: String,
    val requiresOAuth: Boolean = false,
)

package io.github.whywood.superpi.ui.common.market

import io.github.whywood.superpi.market.MarketDefaults
import io.github.whywood.superpi.market.MarketEntry
import io.github.whywood.superpi.market.MarketInstallState
import io.github.whywood.superpi.market.MarketRequiredAccess
import io.github.whywood.superpi.runtime.host.HostAccess
import java.util.Locale

internal enum class MarketEntryStatus { Install, Installed, Update, Installing, Failed }

internal const val MARKET_PAGE_SIZE = 10

internal data class MarketPageSlice(
    val items: List<MarketEntry>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Int,
)

internal fun paginateMarketEntries(
    entries: List<MarketEntry>,
    page: Int,
    pageSize: Int = MARKET_PAGE_SIZE,
): MarketPageSlice {
    val safeSize = pageSize.coerceAtLeast(1)
    val totalItems = entries.size
    val totalPages = if (totalItems == 0) 0 else (totalItems + safeSize - 1) / safeSize
    if (totalPages == 0) {
        return MarketPageSlice(items = emptyList(), page = 0, totalPages = 0, totalItems = 0)
    }
    val clampedPage = page.coerceIn(0, totalPages - 1)
    val fromIndex = clampedPage * safeSize
    val toIndex = minOf(fromIndex + safeSize, totalItems)
    return MarketPageSlice(
        items = entries.subList(fromIndex, toIndex),
        page = clampedPage,
        totalPages = totalPages,
        totalItems = totalItems,
    )
}

internal val MARKET_CATEGORY_IDS = listOf(
    "office",
    "content",
    "dev",
    "study",
    "research",
    "business",
    "design",
    "life",
)

internal fun filterMarketEntries(
    entries: List<MarketEntry>,
    query: String,
    language: String,
): List<MarketEntry> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return entries
    return entries.filter { entry ->
        entry.name(language).contains(keyword, ignoreCase = true) ||
            entry.summary(language).contains(keyword, ignoreCase = true) ||
            entry.tags(language).any { it.contains(keyword, ignoreCase = true) }
    }
}

internal fun filterMarketEntriesByCategory(
    entries: List<MarketEntry>,
    category: String?,
): List<MarketEntry> {
    val target = category?.trim().orEmpty()
    if (target.isEmpty()) return entries
    return entries.filter { it.category?.trim() == target }
}

internal fun marketCategoriesIn(entries: List<MarketEntry>): List<String> {
    val present = entries.mapNotNullTo(HashSet()) { it.category?.trim()?.takeIf(String::isNotEmpty) }
    return MARKET_CATEGORY_IDS.filter { it in present }
}

internal fun marketEntryStatus(
    entry: MarketEntry,
    installState: MarketInstallState?,
    installedVersion: String?,
): MarketEntryStatus = when (installState) {
    is MarketInstallState.Installing -> MarketEntryStatus.Installing
    is MarketInstallState.Installed -> MarketEntryStatus.Installed
    is MarketInstallState.UpdateAvailable -> MarketEntryStatus.Update
    is MarketInstallState.Failed -> MarketEntryStatus.Failed
    MarketInstallState.NotInstalled, null -> when {
        installedVersion == null -> MarketEntryStatus.Install
        installedVersion == entry.version -> MarketEntryStatus.Installed
        else -> MarketEntryStatus.Update
    }
}

internal fun formatMarketSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val text = if (value >= 100.0) {
        String.format(Locale.ROOT, "%.0f", value)
    } else {
        String.format(Locale.ROOT, "%.1f", value)
    }
    val trimmed = if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    return "$trimmed ${units[index]}"
}

internal fun marketIconUrl(icon: String?): String? {
    val path = icon?.trim().orEmpty()
    if (path.isEmpty()) return null
    return MarketDefaults.PAGE_BASE_URL + path.trimStart('/')
}

internal fun MarketRequiredAccess.satisfiedBy(access: HostAccess): Boolean = when (this) {
    MarketRequiredAccess.Normal -> true
    MarketRequiredAccess.Adb -> access != HostAccess.LOCAL
    MarketRequiredAccess.Root -> access == HostAccess.ROOT
}

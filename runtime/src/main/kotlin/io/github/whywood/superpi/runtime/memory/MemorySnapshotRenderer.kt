package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemoryRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

internal object MemorySnapshotRenderer {
    const val TOTAL_BUDGET_TOKENS = 4_500
    const val TOTAL_BUDGET_CHARS = 18_000
    private const val IDENTITY_TOKEN_BUDGET = 1_200
    private const val ACTIVE_TOKEN_BUDGET = 1_200
    private const val HABIT_TOKEN_BUDGET = 800
    private const val EPISODIC_TOKEN_BUDGET = 900
    private const val ENTITY_TOKEN_BUDGET = 400
    private const val RECENT_EPISODIC_DAYS = 2L
    private const val IMPORTANT_EPISODIC_DAYS = 14L
    private const val MS_PER_DAY = 86_400_000L

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun render(
        records: List<MemoryRecord>,
        entities: List<MemoryEntity>,
        nowMs: Long,
    ): String {
        val active = records.filter { it.status == MemoryItemStatus.ACTIVE }
        val activeEntityNames = active.flatMapTo(HashSet()) { it.entities }
        val visibleEntities = entities.filter { it.canonicalName in activeEntityNames }
        if (active.isEmpty() && visibleEntities.isEmpty()) return ""

        val sections = ArrayList<String>(5)

        val identityLines = active
            .filter { it.kind == MemoryKind.IDENTITY }
            .sortedWith(coreComparator())
            .map { formatCoreLine(it) }
        appendSection(sections, "About the User", identityLines, IDENTITY_TOKEN_BUDGET)

        val activeLines = active
            .filter { it.kind == MemoryKind.ACTIVE && (it.validUntil == null || it.validUntil!! >= nowMs) }
            .sortedWith(coreComparator())
            .map { record ->
                val expiry = record.validUntil?.let { " (until ${formatDate(it)})" }.orEmpty()
                formatCoreLine(record, suffix = expiry)
            }
        appendSection(sections, "Active & Follow-ups", activeLines, ACTIVE_TOKEN_BUDGET)

        val habitLines = active
            .filter { it.kind == MemoryKind.HABIT }
            .sortedWith(coreComparator())
            .map { formatCoreLine(it) }
        appendSection(sections, "Collaboration Habits", habitLines, HABIT_TOKEN_BUDGET)

        val recentCutoff = nowMs - RECENT_EPISODIC_DAYS * MS_PER_DAY
        val importantCutoff = nowMs - IMPORTANT_EPISODIC_DAYS * MS_PER_DAY
        val episodicLines = active
            .filter {
                it.kind == MemoryKind.EPISODIC &&
                    (it.validFrom >= recentCutoff || (it.validFrom >= importantCutoff && it.importance >= 4))
            }
            .sortedWith(compareByDescending<MemoryRecord> { it.validFrom }.thenByDescending { it.importance })
            .map { record -> "- [${formatDate(record.validFrom)}] ${record.content.trim()}" }
        appendSection(sections, "Recent Episodes", episodicLines, EPISODIC_TOKEN_BUDGET)

        val entityLines = visibleEntities
            .sortedWith(compareByDescending<MemoryEntity> { it.updatedAt }.thenBy { it.canonicalName })
            .map { formatNeutralEntityLine(it) }
        appendSection(sections, "People & Topics", entityLines, ENTITY_TOKEN_BUDGET)

        if (sections.isEmpty()) return ""
        val joined = sections.joinToString("\n\n")
        return truncateToBudget(joined)
    }

    fun exportMarkdown(
        records: List<MemoryRecord>,
        entities: List<MemoryEntity>,
        nowMs: Long,
        labels: MemoryExportLabels = MemoryExportLabels.ENGLISH,
    ): String = buildString {
        appendLine("# ${labels.title}")
        appendLine()
        appendLine("${labels.exportedOn}: ${formatDate(nowMs)}")
        appendLine()

        val active = records.filter { it.status == MemoryItemStatus.ACTIVE }
        writeGroup(
            this,
            labels.sectionIdentity,
            active.filter { it.kind == MemoryKind.IDENTITY }.sortedWith(coreComparator()),
            labels,
        )
        writeGroup(
            this,
            labels.sectionActive,
            active.filter { it.kind == MemoryKind.ACTIVE }.sortedWith(coreComparator()),
            labels,
        )
        writeGroup(
            this,
            labels.sectionHabit,
            active.filter { it.kind == MemoryKind.HABIT }.sortedWith(coreComparator()),
            labels,
        )
        writeGroup(
            this,
            labels.sectionEpisodic,
            active.filter { it.kind == MemoryKind.EPISODIC }.sortedByDescending { it.validFrom },
            labels,
        )

        val activeEntityNames = active.flatMapTo(HashSet()) { it.entities }
        val visibleEntities = entities.filter { it.canonicalName in activeEntityNames }
        if (visibleEntities.isNotEmpty()) {
            appendLine("## ${labels.sectionEntities}")
            appendLine()
            visibleEntities.sortedBy { it.canonicalName }.forEach { entity ->
                val aliases = if (entity.aliases.isNotEmpty()) {
                    " (${labels.aliasesPrefix}: ${entity.aliases.joinToString(", ")})"
                } else {
                    ""
                }
                val summary = entity.summary?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                appendLine("- ${entity.canonicalName}$aliases$summary")
            }
            appendLine()
        }

        val historical = records.filter { it.status != MemoryItemStatus.ACTIVE }
        if (historical.isNotEmpty()) {
            appendLine("## ${labels.sectionHistory}")
            appendLine()
            historical.sortedByDescending { it.updatedAt }.forEach { item ->
                val stateLabel = when (item.status) {
                    MemoryItemStatus.SUPERSEDED -> labels.statusSuperseded
                    MemoryItemStatus.EXPIRED -> labels.statusExpired
                    MemoryItemStatus.ARCHIVED -> labels.statusArchived
                    MemoryItemStatus.ACTIVE -> ""
                }
                appendLine("- [$stateLabel · ${formatDate(item.validFrom)}] ${item.content.trim()}")
            }
            appendLine()
        }
    }.trimEnd() + "\n"

    internal fun estimateTokens(text: String): Int {
        var score = 0.0
        for (ch in text) {
            score += if (MemoryTokenizer.isNoSpaceScript(ch)) 1.5 else 0.3
        }
        return ceil(score).toInt()
    }

    private fun truncateToBudget(text: String): String {
        if (estimateTokens(text) <= TOTAL_BUDGET_TOKENS && text.length <= TOTAL_BUDGET_CHARS) {
            return text
        }
        var current = text
        while (current.length > 64 && (estimateTokens(current) > TOTAL_BUDGET_TOKENS || current.length > TOTAL_BUDGET_CHARS)) {
            val cut = (current.length * 9 / 10).coerceAtLeast(1)
            current = current.take(cut).trimEnd()
        }
        return current + "\n…"
    }

    private fun writeGroup(
        builder: StringBuilder,
        heading: String,
        items: List<MemoryRecord>,
        labels: MemoryExportLabels,
    ) {
        if (items.isEmpty()) return
        builder.appendLine("## $heading")
        builder.appendLine()
        items.forEach { item ->
            val tags = if (item.tags.isEmpty()) "" else " `#${item.tags.joinToString(" #")}`"
            val pin = if (item.pinned) labels.pinnedPrefix else ""
            val inferred = if (item.confidence == MemoryConfidence.INFERRED) labels.inferredSuffix else ""
            builder.appendLine("- $pin${item.content.trim()}$inferred$tags (${formatDate(item.validFrom)})")
        }
        builder.appendLine()
    }

    private fun appendSection(
        sections: MutableList<String>,
        heading: String,
        lines: List<String>,
        tokenBudget: Int,
    ) {
        if (lines.isEmpty()) return
        val header = "# $heading\n"
        val body = StringBuilder(header)
        var usedTokens = estimateTokens(header)
        var included = 0
        for (line in lines) {
            val clean = line.trimEnd()
            if (clean.isBlank()) continue
            val lineTokens = estimateTokens(clean) + 1
            if (usedTokens + lineTokens > tokenBudget && included > 0) break
            body.append('\n').append(clean)
            usedTokens += lineTokens
            included++
        }
        if (included > 0) sections += body.toString().trim()
    }

    private fun formatCoreLine(record: MemoryRecord, suffix: String = ""): String {
        val text = record.summary?.takeIf { it.isNotBlank() } ?: record.content.trim()
        val inferred = if (record.confidence == MemoryConfidence.INFERRED) " [inferred]" else ""
        val tags = if (record.tags.isNotEmpty()) " [${record.tags.joinToString(", ")}]" else ""
        return "- $text$inferred$suffix$tags"
    }

    private fun formatNeutralEntityLine(entity: MemoryEntity): String {
        val aliases = if (entity.aliases.isNotEmpty()) " (aliases: ${entity.aliases.joinToString(", ")})" else ""
        val summary = entity.summary?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
        return "- ${entity.canonicalName}$aliases$summary"
    }

    private fun coreComparator(): Comparator<MemoryRecord> =
        compareByDescending<MemoryRecord> { it.pinned }
            .thenByDescending { confidenceRank(it.confidence) }
            .thenByDescending { it.importance }
            .thenByDescending { it.updatedAt }

    private fun confidenceRank(confidence: MemoryConfidence): Int = when (confidence) {
        MemoryConfidence.CONFIRMED -> 3
        MemoryConfidence.OBSERVED -> 2
        MemoryConfidence.INFERRED -> 1
    }

    internal fun formatDate(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DAY_FORMAT)

    internal fun todayKey(nowMs: Long = System.currentTimeMillis()): String = formatDate(nowMs)

    internal fun parseDayStartMs(day: String): Long? =
        runCatching {
            LocalDate.parse(day.trim(), DAY_FORMAT)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
}

package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryBlock
import io.github.whywood.superpi.runtime.MemoryBlockInjectionMode
import io.github.whywood.superpi.runtime.MemoryEntity
import io.github.whywood.superpi.runtime.MemoryEntityRelation
import io.github.whywood.superpi.runtime.MemoryEpistemicTier
import io.github.whywood.superpi.runtime.MemoryEvent
import io.github.whywood.superpi.runtime.MemoryExportLabels
import io.github.whywood.superpi.runtime.MemoryInjectionBudget
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemorySettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object MemoryContextRenderer {
    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    fun render(
        snapshot: ContextSnapshotData,
        scope: MemoryAccessScope,
        maxChars: Int = MemoryLimits.MAX_RENDERED_CONTEXT_CHARS,
    ): String = render(snapshot, scope, MemoryInjectionBudget(totalMaxChars = maxChars))

    fun render(
        snapshot: ContextSnapshotData,
        scope: MemoryAccessScope,
        budget: MemoryInjectionBudget,
    ): String {
        if (scope == MemoryAccessScope.OFF) return ""

        val visibleBlocks = snapshot.blocks.filter { block ->
            block.injectionMode != MemoryBlockInjectionMode.NEVER &&
                block.content.isNotBlank() &&
                scope.allows(block.domain, block.sensitivity)
        }
        val visibleEntities = snapshot.entities.filter { scope.allows(it.domain, it.sensitivity) }
        val visibleRelations = snapshot.relations.filter { scope.allows(it.domain, it.sensitivity) }
        val visibleEvents = snapshot.recentEvents.filter { scope.allows(it.domain, it.sensitivity) }

        val hasColdDirectory = scope == MemoryAccessScope.PERSONAL && (
            snapshot.unexpandedEntityNames.isNotEmpty() ||
                snapshot.archivedTitlesAndSlugs.isNotEmpty() ||
                snapshot.monthlyMilestones.isNotEmpty()
            )

        if (visibleBlocks.isEmpty() && visibleEntities.isEmpty() && visibleRelations.isEmpty() &&
            visibleEvents.isEmpty() && !hasColdDirectory
        ) {
            return ""
        }

        val totalUnlimited = budget.totalMaxChars <= 0
        val blocksBudget = resolveSectionBudget(
            override = budget.profileMaxChars,
            totalMaxChars = budget.totalMaxChars,
            baseChars = if (scope == MemoryAccessScope.WORK_ONLY) {
                MemoryInjectionBudget.DEFAULT_WORK_PROFILE_CHARS
            } else {
                MemoryInjectionBudget.DEFAULT_PERSONAL_PROFILE_CHARS
            },
            minAutoChars = MemoryInjectionBudget.MIN_AUTO_PROFILE_CHARS,
        )
        val relationsBudget = resolveSectionBudget(
            override = budget.relationsMaxChars,
            totalMaxChars = budget.totalMaxChars,
            baseChars = if (scope == MemoryAccessScope.WORK_ONLY) {
                MemoryInjectionBudget.DEFAULT_WORK_RELATIONS_CHARS
            } else {
                MemoryInjectionBudget.DEFAULT_PERSONAL_RELATIONS_CHARS
            },
            minAutoChars = MemoryInjectionBudget.MIN_AUTO_RELATIONS_CHARS,
        )
        val eventsBudget = resolveSectionBudget(
            override = budget.eventsMaxChars,
            totalMaxChars = budget.totalMaxChars,
            baseChars = if (scope == MemoryAccessScope.WORK_ONLY) {
                MemoryInjectionBudget.DEFAULT_WORK_EVENTS_CHARS
            } else {
                MemoryInjectionBudget.DEFAULT_PERSONAL_EVENTS_CHARS
            },
            minAutoChars = MemoryInjectionBudget.MIN_AUTO_EVENTS_CHARS,
        )
        val directoryBudget = resolveSectionBudget(
            override = budget.directoryMaxChars,
            totalMaxChars = budget.totalMaxChars,
            baseChars = MemoryInjectionBudget.DEFAULT_PERSONAL_DIRECTORY_CHARS,
            minAutoChars = MemoryInjectionBudget.MIN_AUTO_DIRECTORY_CHARS,
        )

        val sections = ArrayList<String>()
        renderBlocksSection(visibleBlocks, blocksBudget)?.let { sections += it }
        renderRelationsSection(visibleEntities, visibleRelations, relationsBudget)?.let { sections += it }
        renderEventsSection(visibleEvents, eventsBudget)?.let { sections += it }
        if (hasColdDirectory && directoryBudget > 0) {
            renderColdDirectory(snapshot, directoryBudget)?.let { sections += it }
        }

        val joined = sections.joinToString("\n\n").trim()
        return if (totalUnlimited || joined.length <= budget.totalMaxChars) {
            joined
        } else {
            MemoryLimits.clampText(joined, budget.totalMaxChars)
        }
    }

    private fun resolveSectionBudget(
        override: Int?,
        totalMaxChars: Int,
        baseChars: Int,
        minAutoChars: Int,
    ): Int {
        val effective = override ?: MemoryInjectionBudget.autoSectionChars(baseChars, totalMaxChars, minAutoChars)
        return when {
            effective <= MemorySettings.SECTION_DISABLED -> 0
            effective == MemorySettings.INJECTION_UNLIMITED -> Int.MAX_VALUE
            else -> effective
        }
    }

    fun exportMarkdown(
        snapshot: ContextSnapshotData,
        labels: MemoryExportLabels = MemoryExportLabels.ENGLISH,
        nowMs: Long = System.currentTimeMillis(),
    ): String = buildString {
        appendLine("# ${labels.title}")
        appendLine("${labels.exportedOn}: ${formatDate(nowMs)}")
        appendLine()

        appendLine("## ${labels.sectionProfiles}")
        val nonEmptyBlocks = snapshot.blocks.filter { it.content.isNotBlank() }
        if (nonEmptyBlocks.isEmpty()) {
            appendLine("- (none)")
        } else {
            nonEmptyBlocks.forEach { block ->
                val pinPrefix = if (block.pinned) labels.pinnedPrefix else ""
                appendLine("### $pinPrefix${block.title} (`${block.slug}` · ${block.domain})")
                appendLine(block.content.trim())
                appendLine()
            }
        }
        appendLine()

        if (snapshot.archivedBlocks.isNotEmpty()) {
            appendLine("## ${labels.sectionArchivedProfiles}")
            snapshot.archivedBlocks.filter { it.content.isNotBlank() }.forEach { block ->
                appendLine("### ${block.title} (`${block.slug}` · ${block.domain})")
                appendLine(block.content.trim())
                appendLine()
            }
        }

        appendLine("## ${labels.sectionRelationships}")
        if (snapshot.entities.isEmpty() && snapshot.relations.isEmpty()) {
            appendLine("- (none)")
        } else {
            val relsByEntity = snapshot.relations.groupBy { it.fromEntityId }
            snapshot.entities.forEach { entity ->
                val aliases = if (entity.aliases.isEmpty()) {
                    ""
                } else {
                    " (${labels.aliasesPrefix}: ${entity.aliases.joinToString(", ")})"
                }
                val attrs = if (entity.attributes.isEmpty()) {
                    ""
                } else {
                    " {${entity.attributes.entries.joinToString(", ") { "${it.key}: ${it.value}" }}}"
                }
                val summary = entity.summary?.let { " — $it" }.orEmpty()
                appendLine("- **${entity.canonicalName}** [${entity.entityType}]$aliases$attrs$summary")
                relsByEntity[entity.id].orEmpty().forEach { rel ->
                    val labelPart = rel.label?.let { " ($it)" }.orEmpty()
                    appendLine("  - ${rel.relationType} -> ${rel.toEntityName}$labelPart")
                }
            }
        }
        appendLine()

        appendLine("## ${labels.sectionTimeline}")
        if (snapshot.recentEvents.isEmpty()) {
            appendLine("- (none)")
        } else {
            snapshot.recentEvents.forEach { event ->
                appendLine(formatExportEventLine(event, labels))
            }
        }

        if (snapshot.historyEvents.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.sectionHistory}")
            snapshot.historyEvents.forEach { event ->
                appendLine(formatExportEventLine(event, labels))
            }
        }
    }.trim()

    private fun renderBlocksSection(blocks: List<MemoryBlock>, budgetChars: Int): String? {
        if (blocks.isEmpty() || budgetChars <= 0) return null
        val sb = StringBuilder("## Living Profile")
        for (block in blocks) {
            val pinTag = if (block.pinned) " [PINNED]" else ""
            val entry = "\n### ${block.title} [slug=${block.slug}]$pinTag\n${block.content.trim()}"
            if (budgetChars == Int.MAX_VALUE || sb.length + entry.length <= budgetChars) {
                sb.append(entry)
            } else {
                val remaining = budgetChars - sb.length
                if (remaining > 80) {
                    sb.append('\n').append(MemoryLimits.clampText(entry, remaining - 4)).append("...")
                }
                break
            }
        }
        return if (sb.length <= 18) null else sb.toString()
    }

    private fun renderRelationsSection(
        entities: List<MemoryEntity>,
        relations: List<MemoryEntityRelation>,
        budgetChars: Int,
    ): String? {
        if ((entities.isEmpty() && relations.isEmpty()) || budgetChars <= 0) return null
        val outgoing = relations.groupBy { it.fromEntityId }
        val entityLimit = if (budgetChars == Int.MAX_VALUE) {
            entities.size
        } else {
            val scale = (budgetChars.toDouble() / MemoryInjectionBudget.DEFAULT_PERSONAL_RELATIONS_CHARS).coerceAtLeast(1.0)
            (24 * scale).toInt()
        }
        val topEntities = entities.take(entityLimit)
        val sb = StringBuilder("## Key People & Topics")
        var hasLine = false
        for (entity in topEntities) {
            val parts = ArrayList<String>()
            if (entity.aliases.isNotEmpty()) parts += "aliases=${entity.aliases.take(4).joinToString("/")}"
            if (entity.attributes.isNotEmpty()) {
                parts += entity.attributes.entries.take(4).joinToString(", ") { "${it.key}=${it.value}" }
            }
            entity.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
            val relText = outgoing[entity.id].orEmpty().take(4).joinToString("; ") { rel ->
                val detail = rel.label?.let { " ($it)" }.orEmpty()
                "${rel.relationType} ${rel.toEntityName}$detail"
            }
            if (relText.isNotEmpty()) parts += relText
            val suffix = if (parts.isEmpty()) "" else ": ${parts.joinToString(" | ")}"
            val line = "\n- ${entity.canonicalName} [${entity.entityType}]$suffix"
            if (budgetChars != Int.MAX_VALUE && sb.length + line.length > budgetChars) {
                val remaining = budgetChars - sb.length
                if (remaining > 80) {
                    sb.append('\n').append(MemoryLimits.clampText(line, remaining - 4)).append("...")
                    hasLine = true
                }
                break
            }
            sb.append(line)
            hasLine = true
        }
        return if (hasLine) sb.toString() else null
    }

    private fun renderEventsSection(events: List<MemoryEvent>, budgetChars: Int): String? {
        if (events.isEmpty() || budgetChars <= 0) return null
        val ordered = events.sortedWith(
            compareByDescending<MemoryEvent> { it.pinned }
                .thenByDescending { it.importance }
                .thenByDescending { it.eventTime },
        )
        val sb = StringBuilder("## Active Commitments & Recent Events")
        var hasLine = false
        for (event in ordered) {
            val pin = if (event.pinned) "[PINNED] " else ""
            val date = formatDate(event.eventTime)
            val until = event.validUntil?.let { " (until ${formatDate(it)})" }.orEmpty()
            val inferred = if (event.epistemicTier == MemoryEpistemicTier.INFERRED) " [inferred]" else ""
            val body = event.summary?.takeIf { it.isNotBlank() } ?: event.content
            val line = "\n- [id=${event.id} $date ${event.eventKind.wire}] $pin$body$until$inferred"
            if (budgetChars != Int.MAX_VALUE && sb.length + line.length > budgetChars) {
                val remaining = budgetChars - sb.length
                if (remaining > 80) {
                    sb.append('\n').append(MemoryLimits.clampText(line, remaining - 4)).append("...")
                    hasLine = true
                }
                break
            }
            sb.append(line)
            hasLine = true
        }
        return if (hasLine) sb.toString() else null
    }

    private fun renderColdDirectory(snapshot: ContextSnapshotData, budgetChars: Int): String? {
        if (budgetChars <= 0) return null
        val parts = ArrayList<String>()
        if (snapshot.archivedTitlesAndSlugs.isNotEmpty()) {
            val list = snapshot.archivedTitlesAndSlugs.joinToString(", ") { (slug, title) -> "$title ($slug)" }
            parts += "- Archived profiles: $list"
        }
        if (snapshot.unexpandedEntityNames.isNotEmpty()) {
            parts += "- More tracked entities: ${snapshot.unexpandedEntityNames.joinToString(", ")}"
        }
        if (snapshot.monthlyMilestones.isNotEmpty()) {
            val buckets = snapshot.monthlyMilestones.joinToString(", ") { (month, count) -> "$month ($count)" }
            parts += "- Past timeline months: $buckets"
        }
        if (parts.isEmpty()) return null
        val text = "## Deep Memory Index (use memory_search / memory_inspect_entity / memory_read_archive)\n" +
            parts.joinToString("\n")
        return if (budgetChars == Int.MAX_VALUE) text else MemoryLimits.clampText(text, budgetChars)
    }

    private fun formatExportEventLine(event: MemoryEvent, labels: MemoryExportLabels): String {
        val pin = if (event.pinned) labels.pinnedPrefix else ""
        val inferred = if (event.epistemicTier == MemoryEpistemicTier.INFERRED) labels.inferredSuffix else ""
        val statusSuffix = when (event.status) {
            MemoryItemStatus.ACTIVE -> ""
            MemoryItemStatus.SUPERSEDED -> " [${labels.statusSuperseded}]"
            MemoryItemStatus.RESOLVED -> " [${labels.statusResolved}]"
            MemoryItemStatus.EXPIRED -> " [${labels.statusExpired}]"
            MemoryItemStatus.ARCHIVED -> " [${labels.statusArchived}]"
        }
        val tags = if (event.tags.isEmpty()) "" else " #${event.tags.joinToString(" #")}"
        val body = event.summary ?: event.content
        return "- $pin[${formatDate(event.eventTime)} · ${event.domain}] $body$inferred$statusSuffix$tags"
    }

    private fun formatDate(epochMs: Long): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate())
}

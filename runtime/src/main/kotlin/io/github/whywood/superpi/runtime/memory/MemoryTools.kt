package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.EntityKind
import io.github.whywood.superpi.runtime.MemoryConfidence
import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryEntityRef
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryItemStatus
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.MemorySearchHit
import io.github.whywood.superpi.runtime.MemorySourceKind
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val DEFAULT_SEARCH_LIMIT = 8
private const val MAX_SEARCH_LIMIT = 25
private const val FORGET_CONFIDENT_SCORE = 0.4
private const val FORGET_SINGLE_HIT_SCORE = 0.25

private fun stringProperty(description: String, values: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (values != null) put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun stringArrayProperty(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

private fun integerProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private val memoryKinds = listOf("IDENTITY", "ACTIVE", "HABIT", "EPISODIC")

private val searchParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put(
            "queries",
            stringArrayProperty(
                "2 to 4 short distinctive keywords, synonyms, or entity names to search in personal memory.",
            ),
        )
        put(
            "kinds",
            buildJsonObject {
                put("type", "array")
                put("description", "Optional filter by category.")
                put(
                    "items",
                    stringProperty("Memory category", memoryKinds),
                )
            },
        )
        put("tag", stringProperty("Optional topic tag filter, such as work, health, food, or study."))
        put("entity", stringProperty("Optional person, project, place, or topic entity name."))
        put("since", stringProperty("Optional start date filter in yyyy-MM-dd format."))
        put("until", stringProperty("Optional end date filter in yyyy-MM-dd format."))
        put(
            "includeHistory",
            booleanProperty(
                "Set true when the user asks about superseded/outdated past facts, old addresses, or earlier plans.",
            ),
        )
        put("limit", integerProperty("Maximum hits to return (1-25, default 8)."))
    })
    put("required", buildJsonArray { add(JsonPrimitive("queries")) })
}

private val saveParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put(
            "content",
            stringProperty(
                "Concise, standalone statement in the user's language keeping exact names, projects, and terms (max 8192 chars).",
            ),
        )
        put(
            "kind",
            stringProperty(
                "IDENTITY: durable traits, identity, relationships, stable preferences. " +
                    "ACTIVE: current ongoing work, near-term plans, commitments, reminders. " +
                    "HABIT: reusable collaboration preferences or workflow lessons. " +
                    "EPISODIC: notable dated events or decisions.",
                memoryKinds,
            ),
        )
        put("tags", stringArrayProperty("Short topic tags, e.g. ['咖啡', '饮食'] or ['工作', 'SuperPI']."))
        put("entities", stringArrayProperty("People, projects, places, or key topics referenced."))
        put("validUntil", stringProperty("Optional expiry date (yyyy-MM-dd) for time-bounded ACTIVE items."))
        put("supersedesId", stringProperty("Optional memory ID returned by memory_search that this update replaces."))
        put("summary", stringProperty("Optional ultra-short 1-line summary if content is detailed."))
    })
    put("required", buildJsonArray { add(JsonPrimitive("content")); add(JsonPrimitive("kind")) })
}

private val forgetParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("id", stringProperty("Exact memory ID returned by memory_search to delete."))
        put("tag", stringProperty("Delete all active memories under a topic tag when asked to forget a topic."))
        put("query", stringProperty("Keywords to locate specific memory entries when exact ID is unknown."))
    })
}

private val overviewParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {})
}

fun memoryTools(
    store: MemoryStore,
    sessionId: () -> String?,
    isSessionExcluded: suspend (String) -> Boolean = { false },
): List<CodingTool> = listOf(
    CodingTool(
        name = "memory_search",
        label = "Search personal memory",
        description = "Search long-term personal memory across profile traits, active plans, collaboration habits, events, and entity aliases.",
        parameters = searchParameters,
        promptSnippet = "Search personal memory before answering past-context questions",
        promptGuidelines = listOf(
            "Use stored memory silently to align tone, choices, and defaults; do not show off or say 'as you told me last Tuesday' unnecessarily.",
            "Only bring up active follow-ups in conversation when the user brings up a fitting context; never invent proactive notifications.",
            "Search memory with 2-4 synonymous or entity keywords before guessing past facts; set includeHistory=true when asking about earlier or changed states.",
            "If memory_search finds nothing relevant, say so honestly instead of fabricating a memory.",
        ),
    ) { _, args, signal, _ ->
        signal?.throwIfAborted()
        withContext(Dispatchers.IO) {
            try {
                val queries = args.stringArrayArg("queries").ifEmpty {
                    listOfNotNull(args.stringArg("query").takeIf { it.isNotBlank() })
                }
                val kinds = args.stringArrayArg("kinds")
                    .mapNotNull { MemoryKind.fromWire(it) }
                    .toSet()
                val filter = MemoryFilter(
                    kinds = kinds,
                    tag = args.stringArg("tag").takeIf { it.isNotBlank() },
                    entityName = args.stringArg("entity").takeIf { it.isNotBlank() },
                    sinceEpochMs = args.stringArg("since").takeIf { it.isNotBlank() }?.let(::parseDateStart),
                    untilEpochMs = args.stringArg("until").takeIf { it.isNotBlank() }?.let(::parseDateEnd),
                    includeHistory = args.booleanArg("includeHistory") ?: false,
                    limit = args.intArg("limit")?.coerceIn(1, MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT,
                )
                val hits = if (queries.isEmpty()) {
                    store.list(filter).map { MemorySearchHit(record = it, score = 1.0) }
                } else {
                    store.search(queries, filter)
                }
                textResult(formatSearchHits(hits))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                textResult("memory_search failed: ${error.message}", isError = true)
            }
        }
    },
    CodingTool(
        name = "memory_save",
        label = "Save personal memory",
        description = "Save or update an explicit durable personal memory entry.",
        parameters = saveParameters,
        promptSnippet = "Save explicit user preferences, facts, plans, or updates",
        promptGuidelines = listOf(
            "When the user explicitly asks to remember or update something, call memory_save immediately.",
            "When replacing a changed fact (like moving cities or changing preferences), search first and pass supersedesId so old state is marked superseded cleanly.",
            "Only pass durable personal understanding, active commitments, or reusable habits; leave out casual banter, sarcasm, and secrets.",
        ),
    ) { _, args, signal, _ ->
        signal?.throwIfAborted()
        withContext(Dispatchers.IO) {
            try {
                val activeSession = sessionId()?.takeIf { it.isNotBlank() }
                if (activeSession != null && isSessionExcluded(activeSession)) {
                    return@withContext textResult(
                        "Memory recording is paused for this conversation, so nothing was saved. " +
                            "Let the user know they can re-enable memory recording from the top-right menu if needed.",
                    )
                }
                val rawContent = args.stringArg("content").trim()
                if (rawContent.isEmpty()) {
                    return@withContext textResult("Nothing saved: content cannot be empty.", isError = true)
                }
                val content = MemoryLimits.clampText(rawContent, MemoryLimits.MAX_CONTENT_CHARS)
                val truncatedNote = if (rawContent.length > MemoryLimits.MAX_CONTENT_CHARS) {
                    " (truncated to ${MemoryLimits.MAX_CONTENT_CHARS} chars)"
                } else {
                    ""
                }
                val kind = MemoryKind.fromWire(args.stringArg("kind")) ?: MemoryKind.ACTIVE
                val tags = args.stringArrayArg("tags")
                    .map { MemoryLimits.clampText(it, MemoryLimits.MAX_TAG_CHARS) }
                    .filter { it.isNotEmpty() }
                    .take(MemoryLimits.MAX_TAGS_PER_MEMORY)
                val entities = args.stringArrayArg("entities")
                    .map { MemoryLimits.clampText(it, MemoryLimits.MAX_ENTITY_NAME_CHARS) }
                    .filter { it.isNotEmpty() }
                    .take(MemoryLimits.MAX_ENTITIES_PER_MEMORY)
                    .map { MemoryEntityRef(name = it, kind = EntityKind.TOPIC) }
                val validUntil = args.stringArg("validUntil").takeIf { it.isNotBlank() }?.let(::parseDateEnd)
                val supersedesId = MemoryLimits.clampOptionalText(args.stringArg("supersedesId"), MemoryLimits.MAX_ID_LENGTH)
                val summary = MemoryLimits.clampOptionalText(args.stringArg("summary"), MemoryLimits.MAX_SUMMARY_CHARS)
                val record = store.save(
                    draft = MemoryDraft(
                        kind = kind,
                        content = content,
                        summary = summary,
                        tags = tags,
                        entities = entities,
                        confidence = MemoryConfidence.CONFIRMED,
                        importance = if (kind == MemoryKind.IDENTITY || kind == MemoryKind.HABIT) 4 else 3,
                        validUntil = validUntil,
                        sourceKind = MemorySourceKind.CHAT_EXPLICIT,
                        sourceSessionId = activeSession,
                    ),
                    supersedesId = supersedesId,
                )
                textResult("Saved$truncatedNote (${record.kind.wire}, id=${record.id}): ${record.content}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                textResult("memory_save failed: ${error.message}", isError = true)
            }
        }
    },
    CodingTool(
        name = "memory_forget",
        label = "Forget personal memory",
        description = "Delete a specific memory by id, forget a topic tag, or remove closely matching memories when asked to forget.",
        parameters = forgetParameters,
        promptSnippet = "Forget requested memories cleanly",
        promptGuidelines = listOf(
            "When the user asks you to forget something or stop assuming a preference, call memory_forget immediately.",
        ),
    ) { _, args, signal, _ ->
        signal?.throwIfAborted()
        withContext(Dispatchers.IO) {
            try {
                val id = args.stringArg("id").trim()
                val tag = args.stringArg("tag").trim()
                val query = args.stringArg("query").trim()
                when {
                    id.isNotEmpty() -> {
                        val removed = store.softDelete(id)
                        if (removed) textResult("Forgot memory $id.") else textResult("Memory $id was not found.")
                    }
                    tag.isNotEmpty() -> {
                        val count = store.deleteByTag(tag)
                        textResult("Forgot $count memory item(s) under tag #$tag.")
                    }
                    query.isNotEmpty() -> {
                        val hits = store.search(listOf(query), MemoryFilter(limit = 5))
                        val strong = hits.filter { it.score >= FORGET_CONFIDENT_SCORE }
                        when {
                            hits.isEmpty() -> textResult("No matching memory found for \"$query\".")
                            strong.size == 1 -> {
                                val target = strong.single()
                                store.softDelete(target.record.id)
                                textResult("Forgot 1 memory item:\n- ${target.record.content}")
                            }
                            hits.size == 1 && hits.single().score >= FORGET_SINGLE_HIT_SCORE -> {
                                val target = hits.single()
                                store.softDelete(target.record.id)
                                textResult("Forgot 1 memory item:\n- ${target.record.content}")
                            }
                            else -> {
                                val candidates = strong.ifEmpty { hits }
                                val options = candidates.joinToString("\n") { "- [${it.record.id}] ${it.record.content}" }
                                textResult(
                                    "Multiple or low-confidence memories matched \"$query\". Call memory_forget with the exact id(s) to remove:\n$options",
                                )
                            }
                        }
                    }
                    else -> textResult("Provide id, tag, or query to forget.", isError = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                textResult("memory_forget failed: ${error.message}", isError = true)
            }
        }
    },
    CodingTool(
        name = "memory_overview",
        label = "Personal memory overview",
        description = "Show a summary of stored personal memory counts and top topics.",
        parameters = overviewParameters,
        promptSnippet = "Inspect personal memory summary",
    ) { _, _, signal, _ ->
        signal?.throwIfAborted()
        withContext(Dispatchers.IO) {
            try {
                val overview = store.overview()
                textResult(buildString {
                    appendLine("Active memories: ${overview.totalActive}")
                    appendLine("- About me: ${overview.byKind[MemoryKind.IDENTITY] ?: 0}")
                    appendLine("- Active & follow-ups: ${overview.byKind[MemoryKind.ACTIVE] ?: 0}")
                    appendLine("- Collaboration habits: ${overview.byKind[MemoryKind.HABIT] ?: 0}")
                    appendLine("- Episodes & diary: ${overview.byKind[MemoryKind.EPISODIC] ?: 0}")
                    appendLine("Tracked entities: ${overview.entityCount}")
                    if (overview.topTags.isNotEmpty()) {
                        appendLine("Top tags: ${overview.topTags.take(10).joinToString(", ") { "#${it.tag}(${it.count})" }}")
                    }
                }.trim())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                textResult("memory_overview failed: ${error.message}", isError = true)
            }
        }
    },
)

private fun formatSearchHits(hits: List<MemorySearchHit>): String {
    if (hits.isEmpty()) return "No matching personal memories found."
    return buildString {
        hits.forEachIndexed { index, hit ->
            val r = hit.record
            val stateLabel = when (r.status) {
                MemoryItemStatus.ACTIVE -> ""
                MemoryItemStatus.SUPERSEDED -> " [SUPERSEDED by ${r.supersededBy ?: "newer"}]"
                MemoryItemStatus.EXPIRED -> " [EXPIRED]"
                MemoryItemStatus.ARCHIVED -> " [ARCHIVED]"
            }
            val tags = if (r.tags.isEmpty()) "" else " tags=[${r.tags.joinToString(", ")}]"
            val entities = if (r.entities.isEmpty()) "" else " entities=[${r.entities.joinToString(", ")}]"
            val date = MemorySnapshotRenderer.formatDate(r.validFrom)
            appendLine("${index + 1}. [id=${r.id}] [${r.kind.wire}]$stateLabel ($date, ${r.confidence.wire}, score=${String.format(Locale.US, "%.2f", hit.score)})")
            appendLine("   ${r.content}$tags$entities")
        }
    }.trim()
}

private fun parseDateStart(raw: String): Long? =
    MemorySnapshotRenderer.parseDayStartMs(raw)
        ?: runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()

private fun parseDateEnd(raw: String): Long? =
    MemorySnapshotRenderer.parseDayStartMs(raw)?.let { it + 86_399_999L }
        ?: runCatching { Instant.parse(raw.trim()).toEpochMilli() }.getOrNull()

private fun JsonObject.stringArg(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.stringArrayArg(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .orEmpty()

private fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.booleanArg(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

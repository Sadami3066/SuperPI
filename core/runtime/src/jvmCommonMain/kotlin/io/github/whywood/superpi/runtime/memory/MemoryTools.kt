package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryAccessScope
import io.github.whywood.superpi.runtime.MemoryDispatchIntent
import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEventFilter
import io.github.whywood.superpi.runtime.MemorySearchHit
import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
private const val MAX_SEARCH_OUTPUT_CHARS = 16_000

private val DISPATCH_INTENTS = listOf("REMEMBER", "UPDATE", "FORGET", "ORGANIZE")

private fun strProp(description: String, values: List<String>? = null): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    if (values != null) put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun strArrayProp(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    put("items", buildJsonObject { put("type", "string") })
}

private fun intProp(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private val searchParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put(
            "description",
            strProp("Short plain-language description of why you are searching memory for UI display."),
        )
        put(
            "queries",
            strArrayProp("2 to 4 distinctive keywords, synonyms, or names to search across personal memory."),
        )
        put("domain", strProp("Optional life domain filter such as PERSONAL, RELATIONSHIPS, LIFE, HEALTH, WORK, STUDY."))
        put("entity", strProp("Optional person, place, organization, or project name filter."))
        put("since", strProp("Optional event start date in yyyy-MM-dd format."))
        put("until", strProp("Optional event end date in yyyy-MM-dd format."))
        put(
            "includeHistory",
            boolProp("Set true when looking up superseded, expired, resolved, or archived historical facts."),
        )
        put("limit", intProp("Maximum results to return (1-25, default 8)."))
    })
    put("required", buildJsonArray { add(JsonPrimitive("queries")); add(JsonPrimitive("description")) })
}

private val inspectEntityParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("name", strProp("Canonical name, alias, or ID of the person, place, organization, or topic to inspect."))
        put("includeHistory", boolProp("Set true to also include historical invalidated relations and non-active events."))
        put("description", strProp("Short plain-language description for UI display."))
    })
    put("required", buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("description")) })
}

private val readArchiveParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("slug", strProp("Optional archived profile slug from the Deep Memory Index."))
        put("month", strProp("Optional month in yyyy-MM format to browse past events."))
        put("limit", intProp("Maximum events to list when reading by month (default 20)."))
        put("description", strProp("Short plain-language description for UI display."))
    })
    put("required", buildJsonArray { add(JsonPrimitive("description")) })
}

private val dispatchParameters: JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put(
            "intent",
            strProp("Intended memory operation type.", DISPATCH_INTENTS),
        )
        put(
            "detail",
            strProp("Complete natural-language description of what to remember, update, forget, or organize, preserving context and dates."),
        )
        put(
            "description",
            strProp("Short plain-language description for UI display."),
        )
    })
    put(
        "required",
        buildJsonArray {
            add(JsonPrimitive("intent"))
            add(JsonPrimitive("detail"))
            add(JsonPrimitive("description"))
        },
    )
}

fun memoryTools(
    store: MemoryStore,
    agentService: MemoryAgentService? = null,
    scope: () -> MemoryAccessScope = { MemoryAccessScope.PERSONAL },
    allowDispatch: () -> Boolean = { false },
    sessionId: () -> String? = { null },
    isSessionExcluded: suspend (String) -> Boolean = { false },
): List<CodingTool> = if (scope() == MemoryAccessScope.OFF) {
    emptyList()
} else {
    buildList {
    add(
        CodingTool(
            name = "memory_search",
            label = "Search personal memory",
            description = "Search long-term memory across living profiles, entity relationships, and bi-temporal events.",
            parameters = searchParameters,
            promptSnippet = "Search personal memory before answering past-context or preference questions",
            promptGuidelines = listOf(
                "Use stored memory naturally without awkwardly announcing you looked it up.",
                "Check the Deep Memory Index at the bottom of <memory> and search before guessing older personal facts.",
                "Set includeHistory=true when asking about past residences, former plans, or changed preferences.",
                "If memory_search returns nothing relevant, say so honestly instead of guessing.",
            ),
        ) { _, args, signal, _ ->
            signal?.throwIfAborted()
            withContext(Dispatchers.IO) {
                try {
                    val activeScope = scope()
                    if (activeScope == MemoryAccessScope.OFF) {
                        return@withContext textResult("Memory is disabled for this conversation.")
                    }
                    val queries = args.stringArrayArg("queries").ifEmpty {
                        listOfNotNull(args.stringArg("query").takeIf { it.isNotBlank() })
                    }
                    val domainSet = args.stringArg("domain").takeIf { it.isNotBlank() }
                        ?.let { setOf(MemoryDomains.normalize(it)) }
                        ?: emptySet()
                    val filter = MemoryEventFilter(
                        domains = domainSet,
                        entityName = args.stringArg("entity").takeIf { it.isNotBlank() },
                        sinceEventTimeMs = args.stringArg("since").takeIf { it.isNotBlank() }?.let { requireDateStart(it, "since") },
                        untilEventTimeMs = args.stringArg("until").takeIf { it.isNotBlank() }?.let { requireDateEnd(it, "until") },
                        includeHistory = args.booleanArg("includeHistory") ?: false,
                        scope = activeScope,
                        limit = args.intArg("limit")?.coerceIn(1, MAX_SEARCH_LIMIT) ?: DEFAULT_SEARCH_LIMIT,
                    )
                    val hits = if (queries.isEmpty()) {
                        store.listEvents(filter).map { MemorySearchHit(event = it, score = 1.0) }
                    } else {
                        store.search(queries, filter, recordAccess = true)
                    }
                    textResult(formatSearchHits(hits))
                } catch (aborted: RequestAbortedException) {
                    throw aborted
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    textResult("memory_search failed: ${error.message}", isError = true)
                }
            }
        },
    )

    add(
        CodingTool(
            name = "memory_inspect_entity",
            label = "Inspect memory entity",
            description = "Inspect structured attributes, relationship graph edges, and linked timeline events for a person or topic.",
            parameters = inspectEntityParameters,
            promptSnippet = "Inspect a person, place, or topic relationship card from memory",
            promptGuidelines = listOf(
                "Use memory_inspect_entity when answering questions involving family members, friends, colleagues, or places.",
            ),
        ) { _, args, signal, _ ->
            signal?.throwIfAborted()
            withContext(Dispatchers.IO) {
                try {
                    val activeScope = scope()
                    if (activeScope == MemoryAccessScope.OFF) {
                        return@withContext textResult("Memory is disabled for this conversation.")
                    }
                    val name = args.stringArg("name").trim()
                    if (name.isEmpty()) {
                        return@withContext textResult("Entity name cannot be empty.", isError = true)
                    }
                    val includeHistory = args.booleanArg("includeHistory") ?: false
                    val detail = store.inspectEntity(name, activeScope, includeHistory)
                        ?: return@withContext textResult("No visible memory entry found for '$name'.")
                    textResult(formatEntityDetail(detail))
                } catch (aborted: RequestAbortedException) {
                    throw aborted
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    textResult("memory_inspect_entity failed: ${error.message}", isError = true)
                }
            }
        },
    )

    add(
        CodingTool(
            name = "memory_read_archive",
            label = "Read archived memory",
            description = "Read an archived living profile block or historical monthly events listed in the Deep Memory Index.",
            parameters = readArchiveParameters,
            promptSnippet = "Read archived profiles or past monthly timelines from memory",
            promptGuidelines = listOf(
                "Use slug or month pointers listed under Deep Memory Index to retrieve older context.",
            ),
        ) { _, args, signal, _ ->
            signal?.throwIfAborted()
            withContext(Dispatchers.IO) {
                try {
                    val activeScope = scope()
                    if (activeScope == MemoryAccessScope.OFF) {
                        return@withContext textResult("Memory is disabled for this conversation.")
                    }
                    val slug = args.stringArg("slug").takeIf { it.isNotBlank() }
                    val month = args.stringArg("month").takeIf { it.isNotBlank() }
                    val limit = args.intArg("limit")?.coerceIn(1, 50) ?: 20
                    textResult(store.readArchive(slug = slug, month = month, scope = activeScope, limit = limit))
                } catch (aborted: RequestAbortedException) {
                    throw aborted
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    textResult("memory_read_archive failed: ${error.message}", isError = true)
                }
            }
        },
    )

    if (agentService != null && allowDispatch()) {
        add(
            CodingTool(
                name = "memory_dispatch",
                label = "Dispatch memory update",
                description = "Delegate remembering, updating, correcting, or forgetting personal facts cleanly to the dedicated memory agent.",
                parameters = dispatchParameters,
                promptSnippet = "Send durable personal facts, updates, or forgetting requests to the memory agent",
                promptGuidelines = listOf(
                    "Invoke memory_dispatch asynchronously whenever the user shares lasting personal preferences, relationship changes, commitments, corrections, or asks to forget something.",
                    "Provide full unambiguous detail including relative dates resolved against current time and situational qualifiers.",
                ),
            ) { _, args, signal, _ ->
                signal?.throwIfAborted()
                withContext(Dispatchers.IO) {
                    try {
                        val activeScope = scope()
                        if (activeScope == MemoryAccessScope.OFF) {
                            return@withContext textResult(
                                "Memory is turned off for this assistant, so nothing was queued.",
                            )
                        }
                        val activeSession = sessionId()?.takeIf { it.isNotBlank() }
                        if (activeSession != null && isSessionExcluded(activeSession)) {
                            return@withContext textResult(
                                "Memory recording is paused for this conversation, so nothing was queued.",
                            )
                        }
                        val detail = args.stringArg("detail").trim()
                        if (detail.isEmpty()) {
                            return@withContext textResult("Nothing dispatched: detail cannot be empty.", isError = true)
                        }
                        val intent = MemoryDispatchIntent.fromWire(args.stringArg("intent"))
                        val jobId = agentService.enqueueDispatch(
                            sessionId = activeSession,
                            intent = intent,
                            detail = detail,
                        )
                        textResult("Memory update queued ($intent, id=$jobId). Continue responding naturally without blocking.")
                    } catch (aborted: RequestAbortedException) {
                        throw aborted
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        textResult("memory_dispatch failed: ${error.message}", isError = true)
                    }
                }
            },
        )
    }
    }
}

private fun formatSearchHits(hits: List<MemorySearchHit>): String {
    if (hits.isEmpty()) return "No matching memories found."
    val sb = StringBuilder()
    hits.forEachIndexed { index, hit ->
        val ev = hit.event
        val dateStr = formatDate(ev.eventTime)
        val entities = if (ev.entityNames.isEmpty()) "" else " entities=[${ev.entityNames.joinToString(", ")}]"
        val relTag = if (hit.matchedViaRelation) " via_relation" else ""
        val pinTag = if (ev.pinned) " PINNED" else ""
        val line = buildString {
            append("${index + 1}. [id=${ev.id}] [${ev.eventKind.wire}] [${ev.domain}] [${ev.status.wire}$pinTag] ")
            append("($dateStr, ${ev.epistemicTier.wire}, score=${"%.2f".format(Locale.US, hit.score)}$relTag)\n")
            append("   ${ev.summary?.takeIf { it.isNotBlank() } ?: ev.content}")
            if (ev.tags.isNotEmpty()) append(" tags=[${ev.tags.joinToString(", ")}]")
            append(entities)
        }
        if (sb.length + line.length + 2 > MAX_SEARCH_OUTPUT_CHARS) return@forEachIndexed
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append(line)
    }
    return sb.toString()
}

private fun formatEntityDetail(detail: io.github.whywood.superpi.runtime.MemoryEntityDetail): String = buildString {
    val entity = detail.entity
    appendLine("${entity.canonicalName} [${entity.entityType}] (${entity.domain})")
    if (entity.aliases.isNotEmpty()) appendLine("Aliases: ${entity.aliases.joinToString(", ")}")
    if (entity.attributes.isNotEmpty()) {
        appendLine("Attributes: ${entity.attributes.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
    }
    if (!entity.summary.isNullOrBlank()) appendLine("Summary: ${entity.summary}")
    if (detail.outgoingRelations.isNotEmpty() || detail.incomingRelations.isNotEmpty()) {
        appendLine("Relationships:")
        detail.outgoingRelations.forEach { rel ->
            val state = if (rel.isActive) "" else " [historical]"
            val note = rel.label?.let { " ($it)" }.orEmpty()
            appendLine("- ${rel.relationType} -> ${rel.toEntityName}$note$state")
        }
        detail.incomingRelations.forEach { rel ->
            val state = if (rel.isActive) "" else " [historical]"
            val note = rel.label?.let { " ($it)" }.orEmpty()
            appendLine("- ${rel.fromEntityName} ${rel.relationType} -> ${entity.canonicalName}$note$state")
        }
    }
    if (detail.linkedEvents.isNotEmpty()) {
        appendLine("Linked timeline events:")
        detail.linkedEvents.take(15).forEach { ev ->
            appendLine("- [${formatDate(ev.eventTime)} ${ev.status.wire}] ${ev.summary ?: ev.content}")
        }
    }
}.trim()

private fun formatDate(epochMs: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate(),
    )

private fun requireDateStart(raw: String, fieldName: String): Long {
    val clean = raw.trim()
    val zone = ZoneId.systemDefault()
    runCatching { LocalDate.parse(clean) }.getOrNull()?.let { date ->
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }
    runCatching { Instant.parse(clean) }.getOrNull()?.let { instant ->
        return instant.toEpochMilli()
    }
    throw IllegalArgumentException("Invalid $fieldName format '$raw': expected yyyy-MM-dd or ISO instant")
}

private fun requireDateEnd(raw: String, fieldName: String): Long {
    val clean = raw.trim()
    val zone = ZoneId.systemDefault()
    runCatching { LocalDate.parse(clean) }.getOrNull()?.let { date ->
        return date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
    }
    runCatching { Instant.parse(clean) }.getOrNull()?.let { instant ->
        return instant.toEpochMilli()
    }
    throw IllegalArgumentException("Invalid $fieldName format '$raw': expected yyyy-MM-dd or ISO instant")
}

private fun JsonObject.stringArg(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.stringArrayArg(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
        else -> emptyList()
    }
}

private fun JsonObject.booleanArg(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.intArg(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

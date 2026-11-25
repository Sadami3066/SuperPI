package io.github.whywood.superpi.runtime.memory

import io.github.whywood.superpi.runtime.MemoryDomains
import io.github.whywood.superpi.runtime.MemoryEntityTypes
import io.github.whywood.superpi.runtime.MemoryRelationTypes
import io.github.whywood.superpi.sdk.ai.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MemoryAgentToolsSchema {
    private val EVENT_KINDS = listOf("FACT", "PREFERENCE", "COMMITMENT", "EPISODE", "GOAL", "BOUNDARY", "NOTE")
    private val SENSITIVITIES = listOf("PUBLIC", "NORMAL", "PRIVATE")
    private val EPISTEMIC_TIERS = listOf("USER_EXPLICIT", "OBSERVED", "INFERRED")
    private val CONTRACT_STATUSES = listOf("RESOLVED", "EXPIRED", "ARCHIVED")

    fun allTools(): List<Tool> = listOf(
        readBlockTool,
        searchLedgerTool,
        inspectEntityTool,
        listRecentEventsTool,
        commitTool,
        noopTool,
        mergeEntitiesTool,
        proposeNewBlockTool,
        archiveBlockTool,
        archiveEventsTool,
    )

    private fun strProp(desc: String, enumValues: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
        if (enumValues != null) {
            put("enum", buildJsonArray { enumValues.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun intProp(desc: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    private fun boolProp(desc: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", desc)
    }

    private fun strArrayProp(desc: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", desc)
        put("items", buildJsonObject { put("type", "string") })
    }

    private fun strMapProp(desc: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", desc)
        put("additionalProperties", buildJsonObject { put("type", "string") })
    }

    private val eventDraftObjectSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("kind", strProp("Event kind", EVENT_KINDS))
            put("content", strProp("Standalone factual description in the user's language, keeping qualifiers intact."))
            put("summary", strProp("Optional concise 1-line summary."))
            put("domain", strProp("Life domain such as PERSONAL, RELATIONSHIPS, LIFE, HEALTH, WORK, STUDY, CUSTOM."))
            put("sensitivity", strProp("Privacy tier", SENSITIVITIES))
            put("epistemic_tier", strProp("Evidence certainty", EPISTEMIC_TIERS))
            put("importance", intProp("Importance 1-5 (default 3)."))
            put("event_date", strProp("True date when the event happened or will happen: yyyy-MM-dd or yyyy-MM."))
            put("valid_until_days", intProp("Optional expiry window in days (1-365) for temporary plans."))
            put("valid_until_date", strProp("Optional explicit expiry date yyyy-MM-dd."))
            put("qualifiers", strMapProp("Qualifiers preventing flattening, e.g. frequency/condition/timeframe."))
            put("semantic_keywords", strArrayProp("2-6 synonyms, scenarios, or related concepts for non-exact recall."))
            put("tags", strArrayProp("Short topic tags."))
            put("quote", strProp("Short verbatim snippet from user/assistant supporting this record."))
            put(
                "entities",
                buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", strProp("Canonical entity name."))
                            put("type", strProp("Type such as ${MemoryEntityTypes.PERSON}, PLACE, ORGANIZATION, PROJECT, TOPIC."))
                            put("role", strProp("Optional role in event, e.g. SUBJECT, LOCATION, PARTICIPANT."))
                            put("aliases", strArrayProp("Optional aliases."))
                            put("attributes", strMapProp("Optional structured attributes e.g. birthday, diet."))
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    })
                },
            )
        })
        put("required", buildJsonArray { add(JsonPrimitive("kind")); add(JsonPrimitive("content")) })
    }

    private val readBlockTool = Tool(
        name = "read_block",
        description = "Read the full content and metadata of a living profile block by slug (including archived blocks).",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("slug", strProp("Profile block slug, e.g. self_profile, relationships, active_threads."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("slug")) })
        },
    )

    private val searchLedgerTool = Tool(
        name = "search_ledger",
        description = "Search the lower bi-temporal memory events ledger before writing, revising, or forgetting.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("queries", strArrayProp("2-4 distinctive keywords or synonyms."))
                put("domain", strProp("Optional domain filter."))
                put("entity", strProp("Optional entity name filter."))
                put("since", strProp("Optional start date yyyy-MM-dd."))
                put("until", strProp("Optional end date yyyy-MM-dd."))
                put("include_history", boolProp("Set true to include superseded/resolved/expired items."))
                put("limit", intProp("Max results (1-100, default 15)."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("queries")) })
        },
    )

    private val inspectEntityTool = Tool(
        name = "inspect_entity",
        description = "Inspect an entity's attributes, aliases, active/historical relations, and linked events.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name_or_id", strProp("Canonical entity name, alias, or entity ID."))
                put("include_history", boolProp("Include invalidated relations and non-active events."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("name_or_id")) })
        },
    )

    private val listRecentEventsTool = Tool(
        name = "list_recent_events",
        description = "List recent active events to check context and avoid creating duplicate fragments.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("limit", intProp("Number of recent events (1-100, default 25)."))
                put("domain", strProp("Optional domain filter (e.g. ${MemoryDomains.PERSONAL}, WORK)."))
            })
        },
    )

    private val commitTool = Tool(
        name = "commit_memory_changes",
        description = "Atomically commit all profile patches, events, revisions, contractions, entities, and relations in one transaction.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(
                    "user_summary",
                    strProp("Concise plain-language summary for the user audit log (no internal/dev jargon)."),
                )
                put(
                    "patch_blocks",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "Patch existing living profile blocks.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("slug", strProp("Target block slug."))
                                put("find_text", strProp("Optional exact snippet in current block to replace."))
                                put("replace_text", strProp("Replacement text when find_text is specified."))
                                put("append_text", strProp("Optional new lines/bullets to append cleanly."))
                                put("full_content", strProp("Optional full rewritten block content (preserve important unmentioned details)."))
                                put("summary", strProp("Optional plain-language summary of this patch."))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("slug")) })
                        })
                    },
                )
                put(
                    "record_events",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "Record new bi-temporal events or durable personal facts.")
                        put("items", eventDraftObjectSchema)
                    },
                )
                put(
                    "supersede_events",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "Replace an outdated active event with updated truth.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("old_event_id", strProp("Exact ID of active event to supersede."))
                                put("replacement", eventDraftObjectSchema)
                                put("summary", strProp("Optional user summary."))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("old_event_id")); add(JsonPrimitive("replacement")) })
                        })
                    },
                )
                put(
                    "contract_or_forget_events",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "Resolve, expire, archive, or soft-delete cancelled/retracted events.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("event_id", strProp("Exact event ID."))
                                put("new_status", strProp("Target status", CONTRACT_STATUSES))
                                put("soft_delete", boolProp("Set true only when explicitly asked to forget/remove."))
                                put("acting_tier", strProp("Evidence level", EPISTEMIC_TIERS))
                                put("reason", strProp("Brief explanation."))
                                put("summary", strProp("Optional user summary."))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("event_id")) })
                        })
                    },
                )
                put(
                    "upsert_entities",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("name", strProp("Canonical name."))
                                put("type", strProp("Entity type (PERSON, FAMILY, FRIEND, PLACE, PET, PROJECT, TOPIC, etc.)."))
                                put("aliases", strArrayProp("Aliases."))
                                put("attributes", strMapProp("Structured key-value traits, e.g. birthday, residence, diet."))
                                put("summary", strProp("Short profile summary."))
                                put("domain", strProp("Domain."))
                                put("sensitivity", strProp("Sensitivity", SENSITIVITIES))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("name")) })
                        })
                    },
                )
                put(
                    "upsert_relations",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("from_name", strProp("Subject entity name."))
                                put("relation_type", strProp("Relation predicate such as ${MemoryRelationTypes.FAMILY_OF}, lives_in, works_at, prefers, allergic_to."))
                                put("to_name", strProp("Object entity name."))
                                put("label", strProp("Optional natural-language note."))
                                put("domain", strProp("Domain."))
                                put("sensitivity", strProp("Sensitivity", SENSITIVITIES))
                                put("epistemic_tier", strProp("Certainty", EPISTEMIC_TIERS))
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("from_name"))
                                add(JsonPrimitive("relation_type"))
                                add(JsonPrimitive("to_name"))
                            })
                        })
                    },
                )
                put(
                    "invalidate_relations",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "Non-destructively mark an outdated relationship edge as no longer active.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("relation_id", strProp("Exact relation ID from inspect_entity."))
                                put("reason", strProp("Why the relation changed."))
                                put("acting_tier", strProp("Certainty", EPISTEMIC_TIERS))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("relation_id")) })
                        })
                    },
                )
            })
            put("required", buildJsonArray { add(JsonPrimitive("user_summary")) })
        },
    )

    private val noopTool = Tool(
        name = "noop",
        description = "Call immediately and stop when the input contains no prediction error, new personal fact, preference update, or action.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("reason", strProp("Brief reason why no memory mutation is warranted."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("reason")) })
        },
    )

    private val mergeEntitiesTool = Tool(
        name = "merge_entities",
        description = "Merge duplicate entity records into one canonical keeper entity.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("keep_id", strProp("Entity ID to keep."))
                put("merge_ids", strArrayProp("Duplicate entity IDs to merge into keep_id."))
                put("merged_summary", strProp("Optional combined entity summary."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("keep_id")); add(JsonPrimitive("merge_ids")) })
        },
    )

    private val proposeNewBlockTool = Tool(
        name = "propose_new_block",
        description = "Bounded accommodation: propose a new thematic living profile block ONLY after >=5 related events accumulate for a recurring life theme.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("slug", strProp("Clean identifier slug, e.g. pet_care, marathon_prep."))
                put("title", strProp("Short user-facing title."))
                put("purpose", strProp("One-sentence description of what belongs in this profile card."))
                put("domain", strProp("Life domain."))
                put("sensitivity", strProp("Privacy level", SENSITIVITIES))
                put("seed_content", strProp("Initial synthesized content."))
                put("supporting_event_ids", strArrayProp("IDs of >=5 supporting events."))
                put("reason", strProp("User-facing explanation for why this new profile card helps."))
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("slug"))
                add(JsonPrimitive("title"))
                add(JsonPrimitive("purpose"))
                add(JsonPrimitive("domain"))
                add(JsonPrimitive("seed_content"))
                add(JsonPrimitive("supporting_event_ids"))
                add(JsonPrimitive("reason"))
            })
        },
    )

    private val archiveBlockTool = Tool(
        name = "archive_block",
        description = "Archive a completed user/agent custom profile block.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("slug", strProp("Profile block slug to archive."))
                put("reason", strProp("Brief reason."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("slug")) })
        },
    )

    private val archiveEventsTool = Tool(
        name = "archive_events",
        description = "Archive stale or completed events by ID.",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("event_ids", strArrayProp("Event IDs to archive."))
                put("reason", strProp("Brief reason."))
            })
            put("required", buildJsonArray { add(JsonPrimitive("event_ids")) })
        },
    )
}

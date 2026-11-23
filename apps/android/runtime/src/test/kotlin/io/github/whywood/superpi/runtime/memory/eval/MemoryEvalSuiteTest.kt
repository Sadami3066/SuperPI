package io.github.whywood.superpi.runtime.memory.eval

import io.github.whywood.superpi.runtime.MemoryDraft
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.MemoryKind
import io.github.whywood.superpi.runtime.memory.MemoryCompletionGateway
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResponse
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResult
import io.github.whywood.superpi.runtime.memory.MemoryDreamer
import io.github.whywood.superpi.runtime.memory.MemoryIngestor
import io.github.whywood.superpi.runtime.memory.MemoryModelTier
import io.github.whywood.superpi.runtime.memory.MemoryStore
import io.github.whywood.superpi.runtime.memory.memoryTools
import io.github.whywood.superpi.sdk.ai.AssistantMessage
import io.github.whywood.superpi.sdk.ai.PlainTextContent
import io.github.whywood.superpi.sdk.ai.StopReason
import io.github.whywood.superpi.sdk.ai.TextContent
import io.github.whywood.superpi.sdk.ai.Usage
import io.github.whywood.superpi.sdk.ai.UserMessage
import io.github.whywood.superpi.sdk.coding.session.SessionEntry
import io.github.whywood.superpi.sdk.coding.session.SessionMessageEntry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEvalSuiteTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun passesAll24PersonalMemoryEvaluationScenariosAcross6Categories() = runTest {
        val raw = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("memory_eval_cases.json"),
        ).bufferedReader().use { it.readText() }
        val cases = json.parseToJsonElement(raw).jsonObject.getValue("cases").jsonArray
        assertTrue(cases.size == 24)

        val categoryScores = LinkedHashMap<String, MutableList<Boolean>>()
        val baseNow = 1_760_000_000_000L

        for (caseEl in cases) {
            val caseObj = caseEl.jsonObject
            val id = caseObj.getValue("id").jsonPrimitive.content
            val category = caseObj.getValue("category").jsonPrimitive.content
            val dir = Files.createTempDirectory("mem-eval-$id").toFile()
            var virtualNow = baseNow
            val store = MemoryStore(dir, clock = { virtualNow })
            try {
                store.ensure()
                val sessions = caseObj.getValue("sessions").jsonArray
                val excludedIds = sessions.mapNotNull { s ->
                    val obj = s.jsonObject
                    if (obj["excluded"]?.jsonPrimitive?.booleanOrNull == true) {
                        obj.getValue("sessionId").jsonPrimitive.content
                    } else {
                        null
                    }
                }.toSet()
                val mockFastPass = caseObj.getValue("mockFastPass").jsonObject
                val mockDream = caseObj["mockDream"]?.jsonObject

                for (sessionEl in sessions) {
                    val sObj = sessionEl.jsonObject
                    val sid = sObj.getValue("sessionId").jsonPrimitive.content
                    val daysAgo = sObj["daysAgo"]?.jsonPrimitive?.intOrNull ?: 0
                    virtualNow = baseNow - daysAgo * 86_400_000L

                    val fastPayload = mockFastPass[sid]?.jsonObject
                    val gateway = object : MemoryCompletionGateway {
                        override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult {
                            val itemsArray = (fastPayload?.get("items") as? JsonArray) ?: JsonArray(emptyList())
                            val resolvedItems = JsonArray(
                                itemsArray.map { itemEl ->
                                    val item = itemEl.jsonObject.toMutableMap()
                                    val supersedeKeyword = item["supersedes_match"]?.jsonPrimitive?.contentOrNull
                                    if (!supersedeKeyword.isNullOrBlank()) {
                                        val hit = store.search(listOf(supersedeKeyword)).firstOrNull()
                                        if (hit != null) {
                                            item["supersedes_id"] = JsonPrimitive(hit.record.id)
                                        }
                                    }
                                    item.remove("supersedes_match")
                                    JsonObject(item)
                                },
                            )
                            return MemoryCompletionResult.Success(
                                MemoryCompletionResponse(
                                    "eval/fast",
                                    JsonObject(mapOf("items" to resolvedItems)).toString(),
                                ),
                            )
                        }
                    }

                    val ingestor = MemoryIngestor(
                        store = store,
                        completionGateway = gateway,
                        scope = this,
                        isSessionExcluded = { it in excludedIds },
                        clock = { virtualNow },
                    )
                    val entries = buildSessionEntries(sObj.getValue("turns").jsonArray, virtualNow)
                    ingestor.ingestMessages(sid, entries, reason = "eval")
                }

                virtualNow = baseNow
                val pinMatch = caseObj["pinMatching"]?.jsonPrimitive?.contentOrNull
                if (!pinMatch.isNullOrBlank()) {
                    val rec = store.search(listOf(pinMatch)).firstOrNull()?.record
                    if (rec != null) {
                        store.update(rec.id, rec.content, rec.kind, rec.tags, rec.confidence, pinned = true, validUntil = rec.validUntil)
                    }
                }

                val forgetTag = caseObj["forgetTagBeforeEval"]?.jsonPrimitive?.contentOrNull
                if (!forgetTag.isNullOrBlank()) {
                    store.deleteByTag(forgetTag)
                }

                if (mockDream != null) {
                    val dreamMap = mockDream.toMutableMap()
                    val pinAttack = dreamMap.remove("attemptPinAttack")?.jsonPrimitive?.booleanOrNull == true
                    if (pinAttack && !pinMatch.isNullOrBlank()) {
                        val pinnedId = store.search(listOf(pinMatch)).firstOrNull()?.record?.id
                        if (pinnedId != null) {
                            dreamMap["archives"] = JsonArray(listOf(JsonPrimitive(pinnedId)))
                        }
                    }
                    val dreamPayload = JsonObject(dreamMap).toString()
                    val dreamer = MemoryDreamer(
                        store = store,
                        completionGateway = object : MemoryCompletionGateway {
                            override suspend fun complete(tier: MemoryModelTier, prompt: String) =
                                MemoryCompletionResult.Success(MemoryCompletionResponse("eval/dream", dreamPayload))
                        },
                        scope = this,
                        clock = { virtualNow },
                    )
                    dreamer.runNow()
                } else {
                    val dreamer = MemoryDreamer(
                        store = store,
                        completionGateway = object : MemoryCompletionGateway {
                            override suspend fun complete(tier: MemoryModelTier, prompt: String) =
                                MemoryCompletionResult.NoModel
                        },
                        scope = this,
                        clock = { virtualNow },
                    )
                    dreamer.runNow()
                }

                val passed = evaluateAssertions(caseObj, store)
                categoryScores.getOrPut(category) { ArrayList() }.add(passed)
                assertTrue("Eval case $id ($category) failed", passed)
            } finally {
                store.close()
                dir.deleteRecursively()
            }
        }

        val total = categoryScores.values.flatten()
        val overallRate = total.count { it }.toDouble() / total.size.toDouble()
        assertTrue("Overall eval score too low: $overallRate", overallRate >= 0.85)
    }

    private suspend fun evaluateAssertions(caseObj: JsonObject, store: MemoryStore): Boolean {
        if (caseObj["checkToolPromptGuidelines"]?.jsonPrimitive?.booleanOrNull == true) {
            val tools = memoryTools(store, { "s" })
            val joined = tools.flatMap { it.promptGuidelines.orEmpty() }.joinToString("\n").lowercase()
            return joined.contains("silently") && joined.contains("honestly") && tools.any { it.name == "memory_save" }
        }
        val queries = caseObj["queries"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val tagFilter = caseObj["filterTag"]?.jsonPrimitive?.contentOrNull
        val activeHits = if (queries.isEmpty()) {
            emptyList()
        } else {
            store.search(queries, MemoryFilter(tag = tagFilter, includeHistory = false, limit = 10))
        }
        val activeText = activeHits.joinToString("\n") { it.record.content }

        val expectActive = caseObj["expectActiveContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (expectActive.any { !activeText.contains(it) }) return false

        val forbidActive = caseObj["forbidActiveContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (forbidActive.any { activeText.contains(it) }) return false

        val expectFirst = caseObj["expectFirstContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (expectFirst.isNotEmpty()) {
            val firstText = activeHits.firstOrNull()?.record?.content.orEmpty()
            if (expectFirst.any { !firstText.contains(it) }) return false
        }

        val expectHistory = caseObj["expectHistoryContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (expectHistory.isNotEmpty()) {
            val historyHits = store.search(queries, MemoryFilter(includeHistory = true, limit = 10))
            val historyText = historyHits.joinToString("\n") { it.record.content }
            if (expectHistory.any { !historyText.contains(it) }) return false
        }

        return true
    }

    private fun buildSessionEntries(turns: JsonArray, baseTime: Long): List<io.github.whywood.superpi.runtime.AssistantMessage> {
        val list = ArrayList<io.github.whywood.superpi.runtime.AssistantMessage>()
        var idCounter = 1L
        for (turnEl in turns) {
            val t = turnEl.jsonObject
            list += io.github.whywood.superpi.runtime.AssistantMessage(
                id = idCounter++,
                role = io.github.whywood.superpi.runtime.AssistantRole.USER,
                text = t.getValue("user").jsonPrimitive.content,
                startedAt = baseTime,
            )
            list += io.github.whywood.superpi.runtime.AssistantMessage(
                id = idCounter++,
                role = io.github.whywood.superpi.runtime.AssistantRole.ASSISTANT,
                text = t.getValue("assistant").jsonPrimitive.content,
                startedAt = baseTime + 1_000L,
            )
        }
        if (list.size < 4) {
            list += io.github.whywood.superpi.runtime.AssistantMessage(
                id = idCounter++,
                role = io.github.whywood.superpi.runtime.AssistantRole.USER,
                text = "好",
                startedAt = baseTime + 2_000L,
            )
            list += io.github.whywood.superpi.runtime.AssistantMessage(
                id = idCounter,
                role = io.github.whywood.superpi.runtime.AssistantRole.ASSISTANT,
                text = "随时沟通",
                startedAt = baseTime + 3_000L,
            )
        }
        return list
    }
}

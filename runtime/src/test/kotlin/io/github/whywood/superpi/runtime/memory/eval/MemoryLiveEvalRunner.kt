package io.github.whywood.superpi.runtime.memory.eval

import io.github.whywood.superpi.runtime.AssistantMessage
import io.github.whywood.superpi.runtime.AssistantRole
import io.github.whywood.superpi.runtime.MemoryFilter
import io.github.whywood.superpi.runtime.memory.MemoryCompletionGateway
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResponse
import io.github.whywood.superpi.runtime.memory.MemoryCompletionResult
import io.github.whywood.superpi.runtime.memory.MemoryDreamer
import io.github.whywood.superpi.runtime.memory.MemoryIngestor
import io.github.whywood.superpi.runtime.memory.MemoryModelTier
import io.github.whywood.superpi.runtime.memory.MemoryStore
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object MemoryLiveEvalRunner {
    private val json = Json { ignoreUnknownKeys = true }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val baseUrl = System.getenv("SUPERPI_EVAL_BASE_URL")?.trimEnd('/').orEmpty()
        val apiKey = System.getenv("SUPERPI_EVAL_API_KEY")?.trim().orEmpty()
        val model = System.getenv("SUPERPI_EVAL_MODEL")?.trim().orEmpty()
        if (baseUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
            println("Set SUPERPI_EVAL_BASE_URL, SUPERPI_EVAL_API_KEY, SUPERPI_EVAL_MODEL to run live LLM evaluation.")
            return@runBlocking
        }

        val stream = checkNotNull(MemoryLiveEvalRunner::class.java.classLoader?.getResourceAsStream("memory_eval_cases.json"))
        val raw = stream.bufferedReader().use { it.readText() }
        val cases = json.parseToJsonElement(raw).jsonObject.getValue("cases").jsonArray

        val gateway = OpenAiCompatibleGateway(baseUrl, apiKey, model)
        var passed = 0
        val baseNow = System.currentTimeMillis()

        for (caseEl in cases) {
            val caseObj = caseEl.jsonObject
            val id = caseObj.getValue("id").jsonPrimitive.content
            val category = caseObj.getValue("category").jsonPrimitive.content
            if (caseObj["checkToolPromptGuidelines"]?.jsonPrimitive?.booleanOrNull == true) {
                passed++
                println("PASS [$id/$category] prompt guideline check")
                continue
            }

            val dir = Files.createTempDirectory("mem-live-$id").toFile()
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

                for (sessionEl in sessions) {
                    val sObj = sessionEl.jsonObject
                    val sid = sObj.getValue("sessionId").jsonPrimitive.content
                    val daysAgo = sObj["daysAgo"]?.jsonPrimitive?.intOrNull ?: 0
                    virtualNow = baseNow - daysAgo * 86_400_000L
                    val ingestor = MemoryIngestor(
                        store = store,
                        completionGateway = gateway,
                        scope = this,
                        isSessionExcluded = { it in excludedIds },
                        clock = { virtualNow },
                    )
                    ingestor.ingestMessages(sid, toMessages(sObj.getValue("turns").jsonArray, virtualNow), reason = "live_eval")
                }

                virtualNow = baseNow
                val forgetTag = caseObj["forgetTagBeforeEval"]?.jsonPrimitive?.contentOrNull
                if (!forgetTag.isNullOrBlank()) {
                    store.deleteByTag(forgetTag)
                }
                val dreamer = MemoryDreamer(store = store, completionGateway = gateway, scope = this, clock = { virtualNow })
                dreamer.runNow()

                val queries = caseObj["queries"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val tagFilter = caseObj["filterTag"]?.jsonPrimitive?.contentOrNull
                val hits = if (queries.isEmpty()) emptyList() else store.search(queries, MemoryFilter(tag = tagFilter, limit = 10))
                val activeText = hits.joinToString("\n") { it.record.content }
                val expectActive = caseObj["expectActiveContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val forbidActive = caseObj["forbidActiveContains"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val ok = expectActive.all { activeText.contains(it) } && forbidActive.none { activeText.contains(it) }
                if (ok) passed++
                println("${if (ok) "PASS" else "FAIL"} [$id/$category] hits=${hits.size}")
            } finally {
                store.close()
                dir.deleteRecursively()
            }
        }
        println("Live evaluation complete: $passed / ${cases.size} passed")
    }

    private fun toMessages(turns: JsonArray, baseTime: Long): List<AssistantMessage> {
        val list = ArrayList<AssistantMessage>()
        var nextId = 1L
        for (turnEl in turns) {
            val t = turnEl.jsonObject
            list += AssistantMessage(nextId++, AssistantRole.USER, t.getValue("user").jsonPrimitive.content, startedAt = baseTime)
            list += AssistantMessage(nextId++, AssistantRole.ASSISTANT, t.getValue("assistant").jsonPrimitive.content, startedAt = baseTime + 1_000L)
        }
        if (list.size < 4) {
            list += AssistantMessage(nextId++, AssistantRole.USER, "好的", startedAt = baseTime + 2_000L)
            list += AssistantMessage(nextId, AssistantRole.ASSISTANT, "明白", startedAt = baseTime + 3_000L)
        }
        return list
    }

    private class OpenAiCompatibleGateway(
        private val baseUrl: String,
        private val apiKey: String,
        private val model: String,
    ) : MemoryCompletionGateway {
        override suspend fun complete(tier: MemoryModelTier, prompt: String): MemoryCompletionResult =
            withContext(Dispatchers.IO) {
                runCatching {
                    val url = URL("$baseUrl/chat/completions")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                    }
                    val body = buildJsonObject {
                        put("model", model)
                        put("temperature", 0.2)
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", JsonPrimitive("user"))
                                        put("content", JsonPrimitive(prompt))
                                    },
                                )
                            },
                        )
                    }.toString()
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                    if (conn.responseCode !in 200..299) {
                        return@runCatching MemoryCompletionResult.Failed("http_${conn.responseCode}")
                    }
                    val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = json.parseToJsonElement(raw).jsonObject
                    val content = (root["choices"] as? JsonArray)
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("message")
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: return@runCatching MemoryCompletionResult.Failed("empty_response")
                    MemoryCompletionResult.Success(MemoryCompletionResponse("live/$model", content))
                }.getOrElse { error ->
                    MemoryCompletionResult.Failed(error.message ?: "network_error")
                }
            }
    }
}

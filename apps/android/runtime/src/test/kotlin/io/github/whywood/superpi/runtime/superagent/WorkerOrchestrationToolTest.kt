package io.github.whywood.superpi.runtime.superagent

import io.github.whywood.superpi.runtime.ModelConfig
import io.github.whywood.superpi.runtime.SuperRuntime
import io.github.whywood.superpi.runtime.ToolActivityStatus
import io.github.whywood.superpi.runtime.WorkerArtifactRef
import io.github.whywood.superpi.runtime.WorkerCardRef
import io.github.whywood.superpi.runtime.WorkerConflictException
import io.github.whywood.superpi.runtime.WorkerInspectResult
import io.github.whywood.superpi.runtime.WorkerNotFoundException
import io.github.whywood.superpi.runtime.WorkerOutcome
import io.github.whywood.superpi.runtime.WorkerPoolFullException
import io.github.whywood.superpi.runtime.WorkerRunRecord
import io.github.whywood.superpi.runtime.WorkerSlotSummary
import io.github.whywood.superpi.runtime.WorkerStatus
import io.github.whywood.superpi.runtime.WorkerStepSnapshot
import io.github.whywood.superpi.runtime.text
import io.github.whywood.superpi.runtime.toToolResultPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerOrchestrationToolTest {
    @Test
    fun supportsAllSixActionsAndEncodesPayloadsCleanly() = runTest {
        val sample = WorkerRunRecord(
            workerId = "wrk-1",
            botRef = SuperRuntime.HOME_BOT_REF,
            botId = "home",
            botName = "PI",
            deviceName = "Pixel",
            title = "Research flight",
            status = WorkerStatus.RUNNING,
            lastOutcome = WorkerOutcome.NONE,
            turnCount = 1,
            createdAt = 1_760_000_000_000L,
            updatedAt = 1_760_000_001_000L,
            lastProgressAt = 1_760_000_001_000L,
            activeStepText = "web_search",
            steps = listOf(WorkerStepSnapshot("c1", "web_search", "{\"q\":\"flight\"}", ToolActivityStatus.DONE, "3 options")),
            artifacts = listOf(WorkerArtifactRef("art_1", "wrk-1", SuperRuntime.HOME_BOT_REF, "html", "/tmp/a.html", "report.html")),
            cards = listOf(WorkerCardRef("c2", "wrk-1", SuperRuntime.HOME_BOT_REF, "flight", "Flights")),
        )
        val calls = mutableListOf<String>()
        val gateway = object : WorkerOrchestrationGateway {
            override suspend fun listWorkers(botQuery: String?): List<WorkerRunRecord> {
                calls += "list:${botQuery.orEmpty()}"
                return listOf(sample)
            }

            override suspend fun startWorker(
                botQuery: String,
                task: String,
                attachmentPaths: List<String>,
            ): WorkerRunRecord = startWorker(botQuery, task, attachmentPaths, null, null)

            override suspend fun startWorker(
                botQuery: String,
                task: String,
                attachmentPaths: List<String>,
                modelOverride: ModelConfig?,
                name: String?,
            ): WorkerRunRecord {
                calls += "start:$botQuery:${name.orEmpty()}:$task:${attachmentPaths.joinToString(",")}"
                return sample.copy(title = name ?: sample.title)
            }

            override suspend fun continueWorker(
                workerId: String,
                message: String,
                attachmentPaths: List<String>,
            ): WorkerRunRecord {
                calls += "continue:$workerId:$message:${attachmentPaths.joinToString(",")}"
                return sample.copy(turnCount = 2)
            }

            override suspend fun steerWorker(workerId: String, message: String): WorkerRunRecord {
                calls += "steer:$workerId:$message"
                return sample
            }

            override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult {
                calls += "inspect:$workerId:$tail"
                return WorkerInspectResult(sample, sample.steps)
            }

            override suspend fun stopWorker(workerId: String): WorkerRunRecord {
                calls += "stop:$workerId"
                return sample.copy(status = WorkerStatus.IDLE, lastOutcome = WorkerOutcome.ABORTED)
            }

            override suspend fun deleteWorker(workerId: String) {
                calls += "delete:$workerId"
            }
        }
        val tool = workerOrchestrationTool(gateway)

        assertTrue(tool.parameters["properties"]!!.jsonObject.containsKey("workerId"))
        assertTrue(tool.parameters["properties"]!!.jsonObject.containsKey("name"))
        assertTrue(tool.parameters["properties"]!!.jsonObject.containsKey("attachments"))
        assertFalse(tool.parameters["properties"]!!.jsonObject.containsKey("worker_id"))

        val listJson = parse(tool.execute("t0", buildJsonObject { put("action", "list"); put("bot", "PI") }, null, null).toToolResultPayload().text())
        assertEquals("list", listJson["action"]!!.jsonPrimitive.content)
        assertEquals(1, listJson["count"]!!.jsonPrimitive.int)
        assertEquals("wrk-1", listJson["workers"]!!.jsonArray[0].jsonObject["workerId"]!!.jsonPrimitive.content)

        val startJson = parse(
            tool.execute(
                "t1",
                buildJsonObject {
                    put("action", "start")
                    put("bot", "PI")
                    put("name", "Flight Search")
                    put("task", "Search flights")
                    putJsonArray("attachments") { add(JsonPrimitive("/tmp/ticket.png")) }
                },
                null,
                null,
            ).toToolResultPayload().text(),
        )
        assertEquals("started", startJson["action"]!!.jsonPrimitive.content)
        assertEquals("Flight Search", startJson["name"]!!.jsonPrimitive.content)
        assertEquals("Flight Search", startJson["title"]!!.jsonPrimitive.content)
        assertEquals("wrk-1", startJson["workerId"]!!.jsonPrimitive.content)
        assertEquals("wrk-1", startJson["worker_id"]!!.jsonPrimitive.content)

        val contJson = parse(
            tool.execute(
                "t2",
                buildJsonObject {
                    put("action", "continue")
                    put("workerId", "wrk-1")
                    put("message", "Filter direct")
                    putJsonArray("attachments") { add(JsonPrimitive("worker://wrk-0/a1")) }
                },
                null,
                null,
            ).toToolResultPayload().text(),
        )
        assertEquals("continued", contJson["action"]!!.jsonPrimitive.content)

        val steerJson = parse(tool.execute("t3", buildJsonObject { put("action", "steer"); put("workerId", "wrk-1"); put("message", "Switch date") }, null, null).toToolResultPayload().text())
        assertEquals("steered", steerJson["action"]!!.jsonPrimitive.content)

        val inspectJson = parse(tool.execute("t4", buildJsonObject { put("action", "inspect"); put("workerId", "wrk-1"); put("tail", 10) }, null, null).toToolResultPayload().text())
        assertEquals("worker://wrk-1/art_1", inspectJson["artifacts"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content)
        assertEquals("c2", inspectJson["cards"]!!.jsonArray[0].jsonObject["card_id"]!!.jsonPrimitive.content)

        val stopJson = parse(tool.execute("t5", buildJsonObject { put("action", "stop"); put("workerId", "wrk-1") }, null, null).toToolResultPayload().text())
        assertEquals("stopped", stopJson["action"]!!.jsonPrimitive.content)

        val delJson = parse(tool.execute("t6", buildJsonObject { put("action", "delete"); put("worker_id", "wrk-1") }, null, null).toToolResultPayload().text())
        assertTrue(delJson["deleted"]!!.jsonPrimitive.boolean)

        assertEquals(
            listOf(
                "list:PI",
                "start:PI:Flight Search:Search flights:/tmp/ticket.png",
                "continue:wrk-1:Filter direct:worker://wrk-0/a1",
                "steer:wrk-1:Switch date",
                "inspect:wrk-1:10",
                "stop:wrk-1",
                "delete:wrk-1",
            ),
            calls,
        )
    }

    @Test
    fun formatsPoolFullConflictNotFoundAndMatchesToolTextPolicy() = runTest {
        val gateway = object : WorkerOrchestrationGateway {
            override suspend fun startWorker(
                botQuery: String,
                task: String,
                attachmentPaths: List<String>,
            ): WorkerRunRecord {
                throw WorkerPoolFullException(
                    botId = "home",
                    existing = (1..5).map { i ->
                        WorkerSlotSummary("wrk-$i", "Task $i", WorkerStatus.IDLE, 1_760_000_000_000L + i)
                    },
                )
            }

            override suspend fun continueWorker(
                workerId: String,
                message: String,
                attachmentPaths: List<String>,
            ): WorkerRunRecord = throw WorkerConflictException(workerId, "Busy now")

            override suspend fun steerWorker(workerId: String, message: String): WorkerRunRecord =
                throw WorkerNotFoundException(workerId)

            override suspend fun inspectWorker(workerId: String, tail: Int): WorkerInspectResult =
                throw WorkerNotFoundException(workerId)

            override suspend fun stopWorker(workerId: String): WorkerRunRecord =
                throw WorkerNotFoundException(workerId)

            override suspend fun deleteWorker(workerId: String) = Unit
        }
        val tool = workerOrchestrationTool(gateway)

        assertTrue(tool.description.isNotBlank())
        assertNull(tool.promptSnippet)
        tool.promptGuidelines.orEmpty().forEach { line ->
            assertTrue(line.isNotBlank())
            assertFalse(line.contains("\n"))
        }

        val fullJson = parse(tool.execute("t1", buildJsonObject { put("action", "start"); put("bot", "PI"); put("name", "Sixth worker"); put("task", "6th") }, null, null).toToolResultPayload().text())
        assertEquals("worker_pool_full", fullJson["error"]!!.jsonPrimitive.content)
        assertEquals(5, fullJson["existing_workers"]!!.jsonArray.size)

        val conflictJson = parse(tool.execute("t2", buildJsonObject { put("action", "continue"); put("worker_id", "wrk-1"); put("message", "go") }, null, null).toToolResultPayload().text())
        assertEquals("worker_conflict", conflictJson["error"]!!.jsonPrimitive.content)

        val missingJson = parse(tool.execute("t3", buildJsonObject { put("action", "inspect"); put("worker_id", "wrk-x") }, null, null).toToolResultPayload().text())
        assertEquals("worker_not_found", missingJson["error"]!!.jsonPrimitive.content)

        val missingNameResult = tool.execute(
            "t4",
            buildJsonObject {
                put("action", "start")
                put("bot", "PI")
                put("task", "Run without name")
            },
            null,
            null,
        )
        assertEquals(true, missingNameResult.isError)
        assertTrue(missingNameResult.toToolResultPayload().text().contains("name"))
    }

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}

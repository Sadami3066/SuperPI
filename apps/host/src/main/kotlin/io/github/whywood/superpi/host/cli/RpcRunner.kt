package io.github.whywood.superpi.host.cli

import io.github.whywood.superpi.host.core.DoctorService
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.RuntimeEvent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Line-delimited JSON-RPC 2.0 interface (`--mode rpc`) over stdin/stdout so editors, scripts,
 * and external harnesses can drive super-pi programmatically.
 */
object RpcRunner {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        controller: SuperPiController,
        defaultCwd: File,
        input: BufferedReader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8)),
        out: PrintStream = System.out,
    ): Int {
        controller.bootstrap()
        val doctorService = DoctorService(controller, controller.platform, controller.extensionRegistry)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val promptJobs = ConcurrentHashMap<String, Job>()
        val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.runtimeHost.events.collect { wrapped ->
                val notification = serializeRuntimeEvent(wrapped.sessionId, wrapped.event) ?: return@collect
                emit(out, notification)
            }
        }
        try {
            while (true) {
                val line = kotlinx.coroutines.withContext(Dispatchers.IO) { input.readLine() } ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val response = handleLine(
                    controller = controller,
                    doctorService = doctorService,
                    defaultCwd = defaultCwd,
                    rawLine = trimmed,
                    asyncPromptLauncher = { sid, text ->
                        promptJobs[sid]?.cancel()
                        val job = scope.launch {
                            try {
                                controller.runtimeHost.send(sid, text)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (err: Exception) {
                                emit(
                                    out,
                                    buildJsonObject {
                                        put("jsonrpc", "2.0")
                                        put("method", "session.event")
                                        put(
                                            "params",
                                            buildJsonObject {
                                                put("sessionId", sid)
                                                put(
                                                    "event",
                                                    buildJsonObject {
                                                        put("type", "error")
                                                        put("message", err.message ?: err::class.java.simpleName)
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        promptJobs[sid] = job
                        job.invokeOnCompletion { promptJobs.remove(sid, job) }
                    },
                )
                emit(out, response)
            }
            return 0
        } finally {
            promptJobs.values.forEach { it.cancel() }
            eventJob.cancel()
            scope.cancel()
        }
    }

    internal suspend fun handleLine(
        controller: SuperPiController,
        doctorService: DoctorService,
        defaultCwd: File,
        rawLine: String,
        asyncPromptLauncher: ((sessionId: String, text: String) -> Unit)? = null,
    ): JsonObject {
        val req = runCatching { json.parseToJsonElement(rawLine).jsonObject }.getOrNull()
            ?: return errorResponse(JsonNull, -32700, "Parse error: invalid JSON")
        val id: JsonElement = req["id"] ?: JsonNull
        val method = (req["method"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val params = req["params"] as? JsonObject ?: JsonObject(emptyMap())
        if (method.isEmpty()) {
            return errorResponse(id, -32600, "Invalid Request: missing method")
        }
        return try {
            val result: JsonElement = when (method) {
                "rpc.ping" -> buildJsonObject { put("pong", true) }
                "bots.list" -> buildJsonArray {
                    controller.botRegistry.listAllBots().forEach { bot ->
                        add(
                            buildJsonObject {
                                put("id", bot.id)
                                put("name", bot.name)
                                put("cwd", bot.cwd)
                                put("isHome", bot.isHome)
                            },
                        )
                    }
                }
                "doctor.run" -> {
                    val report = doctorService.run(probeMcpServers = false)
                    json.parseToJsonElement(Json.encodeToString(io.github.whywood.superpi.host.core.DoctorReport.serializer(), report))
                }
                "session.list" -> buildJsonArray {
                    controller.sessionCatalog.listAllExisting().forEach { s ->
                        add(
                            buildJsonObject {
                                put("id", s.id)
                                put("title", s.title)
                                put("cwd", s.cwd)
                                put("updatedAt", s.updatedAt)
                            },
                        )
                    }
                }
                "session.create" -> {
                    val cwd = params.str("cwd")?.takeIf { it.isNotBlank() } ?: defaultCwd.absolutePath
                    val opened = controller.runtimeHost.newSessionInDirectory(cwd)
                    buildJsonObject {
                        put("sessionId", opened.sessionId)
                        put("cwd", opened.cwd)
                        opened.filePath?.let { put("filePath", it) }
                    }
                }
                "session.resume" -> {
                    val sid = params.str("sessionId") ?: throw IllegalArgumentException("sessionId required")
                    val all = controller.sessionCatalog.listAllExisting()
                    val match = all.firstOrNull { it.id == sid || it.id.startsWith(sid) }
                        ?: throw IllegalArgumentException("Session not found: $sid")
                    val opened = controller.runtimeHost.openSession(match.filePath, match.cwd.ifBlank { defaultCwd.absolutePath })
                    buildJsonObject {
                        put("sessionId", opened.sessionId)
                        put("cwd", opened.cwd)
                    }
                }
                "session.prompt" -> {
                    val sid = params.str("sessionId") ?: throw IllegalArgumentException("sessionId required")
                    val text = params.str("text") ?: throw IllegalArgumentException("text required")
                    if (asyncPromptLauncher != null) {
                        asyncPromptLauncher.invoke(sid, text)
                    } else {
                        controller.runtimeHost.send(sid, text)
                    }
                    buildJsonObject { put("accepted", true) }
                }
                "session.steer" -> {
                    val sid = params.str("sessionId") ?: throw IllegalArgumentException("sessionId required")
                    val text = params.str("text") ?: throw IllegalArgumentException("text required")
                    controller.runtimeHost.steer(sid, text)
                    buildJsonObject { put("accepted", true) }
                }
                "session.followup" -> {
                    val sid = params.str("sessionId") ?: throw IllegalArgumentException("sessionId required")
                    val text = params.str("text") ?: throw IllegalArgumentException("text required")
                    controller.runtimeHost.followUp(sid, text)
                    buildJsonObject { put("accepted", true) }
                }
                "session.abort" -> {
                    val sid = params.str("sessionId") ?: throw IllegalArgumentException("sessionId required")
                    val dropped = controller.runtimeHost.stop(sid)
                    buildJsonObject {
                        put("aborted", true)
                        put("droppedInputs", dropped.size)
                    }
                }
                else -> return errorResponse(id, -32601, "Method not found: $method")
            }
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
        } catch (err: IllegalArgumentException) {
            errorResponse(id, -32602, err.message ?: "Invalid params")
        } catch (err: Exception) {
            errorResponse(id, -32000, err.message ?: "Internal error")
        }
    }

    private fun serializeRuntimeEvent(sessionId: String, event: RuntimeEvent): JsonObject? {
        val payload = when (event) {
            is RuntimeEvent.RunStarted -> buildJsonObject {
                put("type", "run_started")
                put("runId", event.runId)
            }
            is RuntimeEvent.TextDelta -> buildJsonObject {
                put("type", "assistant_delta")
                put("runId", event.runId)
                put("text", event.text)
            }
            is RuntimeEvent.ReasoningDelta -> buildJsonObject {
                put("type", "thinking_delta")
                put("runId", event.runId)
                put("text", event.text)
            }
            is RuntimeEvent.ToolStarted -> buildJsonObject {
                put("type", "tool_start")
                put("runId", event.runId)
                put("callId", event.callId)
                put("name", event.name)
            }
            is RuntimeEvent.ToolFinished -> buildJsonObject {
                put("type", "tool_end")
                put("runId", event.runId)
                put("callId", event.callId)
                put("name", event.name)
                put("isError", event.isError)
                put("output", event.result)
            }
            is RuntimeEvent.RunFinished -> buildJsonObject {
                put("type", "turn_done")
                put("runId", event.runId)
                put("status", event.status.name)
                put("text", event.text)
            }
            is RuntimeEvent.RunFailed -> buildJsonObject {
                put("type", "error")
                put("runId", event.runId)
                put("message", event.message)
            }
            else -> return null
        }
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "session.event")
            put(
                "params",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("event", payload)
                },
            )
        }
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun emit(out: PrintStream, obj: JsonObject) {
        synchronized(out) {
            out.println(obj.toString())
            out.flush()
        }
    }
}

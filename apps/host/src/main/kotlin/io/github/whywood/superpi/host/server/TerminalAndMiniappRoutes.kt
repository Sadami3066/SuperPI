package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.host.core.PermissionMode
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.host.core.TerminalEvent
import io.github.whywood.superpi.host.core.TerminalInfo
import io.github.whywood.superpi.runtime.MiniappBridgeRouter
import io.github.whywood.superpi.runtime.MiniappEntry
import io.github.whywood.superpi.runtime.SqliteMiniappData
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeStringUtf8
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SetBotPermissionRequest(
    val mode: String,
)

@Serializable
data class CreateTerminalRequest(
    val worktreeId: String? = null,
    val fresh: Boolean = false,
)

@Serializable
data class TerminalInfoDto(
    val id: String,
    val botId: String,
    val cwd: String,
    val shell: String,
    val alive: Boolean,
    val createdAt: Long,
    val history: String = "",
)

@Serializable
data class TerminalInputRequest(
    val data: String,
)

@Serializable
data class TerminalStreamFrame(
    val type: String,
    val data: String = "",
    val exitCode: Int? = null,
)

@Serializable
data class MiniappSummaryDto(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String = "",
    val entry: String = "index.html",
    val version: String = "1.0.0",
)

private const val MAX_TERMINAL_INPUT_CHARS = 64 * 1024
private const val MAX_MINIAPP_ENTRY_BYTES = 5L * 1024L * 1024L
private const val MAX_MINIAPP_BRIDGE_CHARS = 512 * 1024
private val termJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

internal fun Route.installTerminalAndMiniappRoutes(
    controller: SuperPiController,
    serverScope: CoroutineScope,
) {
    // ── Bot 4-Tier Permission Policy ─────────────────────────────────────────
    put("/v1/bots/{id}/permission") {
        val botId = call.parameters["id"].orEmpty().trim()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@put
        }
        val req = call.receive<SetBotPermissionRequest>()
        val mode = PermissionMode.parseWireOrNull(req.mode)
        if (mode == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Invalid permission mode: ${req.mode}. Expected one of: read_only, ask_commands, workspace_auto, full_auto"),
            )
            return@put
        }
        controller.settingsStore.update { cur ->
            val nextMap = cur.botPermissionModes.toMutableMap()
            nextMap[bot.id] = mode.wire
            cur.copy(botPermissionModes = nextMap)
        }
        call.respond(HealthResponse(ok = true))
    }

    // ── Embedded Interactive Terminal ────────────────────────────────────────
    get("/v1/bots/{id}/terminals") {
        val botId = call.parameters["id"].orEmpty().trim()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@get
        }
        call.respond(controller.ptySessionManager.listForBot(bot.id).map { it.toDto() })
    }

    post("/v1/bots/{id}/terminals") {
        val botId = call.parameters["id"].orEmpty().trim()
        val bot = controller.botRegistry.findBotById(botId)
        if (bot == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Bot not found: $botId"))
            return@post
        }
        val req = runCatching { call.receive<CreateTerminalRequest>() }.getOrDefault(CreateTerminalRequest())
        val targetDir = if (!req.worktreeId.isNullOrBlank()) {
            val wt = controller.worktreeManager.findById(req.worktreeId.trim())
            if (wt == null || wt.botId != bot.id) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Worktree not found"))
                return@post
            }
            File(wt.worktreePath)
        } else {
            File(bot.cwd)
        }
        try {
            val created = withContext(Dispatchers.IO) {
                controller.ptySessionManager.createOrReuse(bot.id, targetDir, fresh = req.fresh)
            }
            call.respond(HttpStatusCode.Created, created.toDto())
        } catch (err: Exception) {
            if (err is CancellationException) throw err
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Unable to start terminal"))
        }
    }

    get("/v1/terminals/{termId}/stream") {
        val termId = call.parameters["termId"].orEmpty().trim()
        val info = controller.ptySessionManager.getInfo(termId)
        val flow = controller.ptySessionManager.eventsFlow(termId)
        if (info == null || flow == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Terminal not found: $termId"))
            return@get
        }
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            val outbound = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            try {
                coroutineScope {
                    val parentJob = coroutineContext[Job]
                    val shutdownWatcher = serverScope.coroutineContext[Job]?.invokeOnCompletion {
                        outbound.close()
                        parentJob?.cancel()
                    }
                    try {
                        val writerJob = launch {
                            for (frame in outbound) {
                                writeStringUtf8(frame)
                                flush()
                            }
                        }
                        if (info.history.isNotEmpty()) {
                            val initFrame = termJson.encodeToString(
                                TerminalStreamFrame.serializer(),
                                TerminalStreamFrame(type = "history", data = info.history),
                            )
                            outbound.trySend("event: history\ndata: $initFrame\n\n")
                        }
                        if (!info.alive) {
                            val exitFrame = termJson.encodeToString(
                                TerminalStreamFrame.serializer(),
                                TerminalStreamFrame(type = "exit", exitCode = info.exitCode ?: -1),
                            )
                            outbound.trySend("event: exit\ndata: $exitFrame\n\n")
                            outbound.close()
                            writerJob.join()
                            return@coroutineScope
                        }
                        val heartbeatJob = launch {
                            while (isActive) {
                                delay(15_000L)
                                outbound.trySend(": heartbeat\n\n")
                            }
                        }
                        val collectorJob = launch {
                            var exited = false
                            flow.takeWhile { !exited }.collect { ev: TerminalEvent ->
                                val payload = termJson.encodeToString(
                                    TerminalStreamFrame.serializer(),
                                    TerminalStreamFrame(type = ev.type, data = ev.data, exitCode = ev.exitCode),
                                )
                                outbound.trySend("event: ${ev.type}\ndata: $payload\n\n")
                                if (ev.type == "exit") {
                                    exited = true
                                }
                            }
                            heartbeatJob.cancel()
                            outbound.close()
                        }
                        writerJob.join()
                        collectorJob.cancel()
                        heartbeatJob.cancel()
                    } finally {
                        shutdownWatcher?.dispose()
                        outbound.close()
                    }
                }
            } catch (_: ClosedWriteChannelException) {
            } catch (_: IOException) {
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    post("/v1/terminals/{termId}/input") {
        val termId = call.parameters["termId"].orEmpty().trim()
        val req = call.receive<TerminalInputRequest>()
        if (req.data.length > MAX_TERMINAL_INPUT_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("Terminal input exceeds 64 KB limit"))
            return@post
        }
        val ok = withContext(Dispatchers.IO) {
            controller.ptySessionManager.writeInput(termId, req.data)
        }
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Terminal is closed or not found"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    delete("/v1/terminals/{termId}") {
        val termId = call.parameters["termId"].orEmpty().trim()
        val ok = controller.ptySessionManager.kill(termId)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Terminal not found: $termId"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── MiniApps Sandbox & Bridge ────────────────────────────────────────────
    get("/v1/miniapps") {
        val scan = withContext(Dispatchers.IO) { controller.miniappStore.scan() }
        val items = scan.creations.map { c ->
            MiniappSummaryDto(
                id = c.manifest.id,
                name = c.manifest.name,
                description = c.manifest.description,
                icon = c.manifest.icon,
                entry = c.manifest.entry,
                version = c.manifest.version,
            )
        }
        call.respond(items)
    }

    get("/v1/miniapps/{id}/entry") {
        val id = call.parameters["id"].orEmpty().trim()
        val loaded = withContext(Dispatchers.IO) { controller.miniappStore.load(id) } as? MiniappEntry.Valid
        if (loaded == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("MiniApp not found: $id"))
            return@get
        }
        val entryRes = withContext(Dispatchers.IO) {
            WorkspacePathGuard.resolveSafe(loaded.creation.directory, loaded.creation.manifest.entry)
        }
        val entryFile = (entryRes as? WorkspacePathGuard.Resolution.Allowed)?.targetReal?.toFile()
        if (entryFile == null || !entryFile.isFile || entryFile.length() > MAX_MINIAPP_ENTRY_BYTES) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("MiniApp entry file missing or too large"))
            return@get
        }
        val rawHtml = withContext(Dispatchers.IO) { entryFile.readText(Charsets.UTF_8) }
        val injected = injectMiniappBridgeShim(rawHtml, loaded.creation.manifest.id)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(
            "Content-Security-Policy",
            "sandbox allow-scripts allow-forms; default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; form-action 'none';",
        )
        call.respondText(injected, ContentType.Text.Html)
    }

    get("/v1/miniapps/{id}/asset/{path...}") {
        val id = call.parameters["id"].orEmpty().trim()
        val relPath = call.parameters.getAll("path")?.joinToString("/").orEmpty()
        val loaded = withContext(Dispatchers.IO) { controller.miniappStore.load(id) } as? MiniappEntry.Valid
        if (loaded == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("MiniApp not found: $id"))
            return@get
        }
        when (val resolved = withContext(Dispatchers.IO) { WorkspacePathGuard.resolveSafe(loaded.creation.directory, relPath) }) {
            is WorkspacePathGuard.Resolution.Forbidden ->
                call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.NotFound ->
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse(resolved.reason))
            is WorkspacePathGuard.Resolution.Allowed -> {
                val file = resolved.targetReal.toFile()
                if (!file.isFile || file.length() > 20L * 1024L * 1024L) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Asset unreadable or too large"))
                    return@get
                }
                val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(file.toPath()) }
                respondSafeRawBytes(call, bytes, file.name, download = false)
            }
        }
    }

    post("/v1/miniapps/{id}/bridge") {
        val id = call.parameters["id"].orEmpty().trim()
        val loaded = withContext(Dispatchers.IO) { controller.miniappStore.load(id) } as? MiniappEntry.Valid
        if (loaded == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("MiniApp not found: $id"))
            return@post
        }
        val rawBody = call.receiveText()
        if (rawBody.length > MAX_MINIAPP_BRIDGE_CHARS) {
            call.respond(HttpStatusCode.PayloadTooLarge, ApiErrorResponse("Bridge request exceeds 512 KB limit"))
            return@post
        }
        val reply = withContext(Dispatchers.IO) {
            val dataFile = controller.miniappStore.dataFile(id)
            dataFile.parentFile?.mkdirs()
            val data = SqliteMiniappData(dataFile)
            try {
                val router = MiniappBridgeRouter(
                    manifest = loaded.creation.manifest,
                    data = data,
                    toast = {},
                )
                router.handle(rawBody) {}
            } finally {
                runCatching { data.close() }
            }
        }
        call.respondText(reply, ContentType.Application.Json)
    }
}

private fun injectMiniappBridgeShim(html: String, miniappId: String): String {
    val safeId = miniappId.replace(Regex("[^a-zA-Z0-9_-]"), "")
    val cspMeta =
        """<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; form-action 'none';">"""
    val shim = """
$cspMeta
<script>
(function(){
  if (window.superpi) return;
  var nextId = 1;
  var pending = {};
  window.addEventListener("message", function(ev) {
    if (ev.source !== window.parent) return;
    var msg = ev.data;
    if (!msg || msg.__superpiBridgeReply !== true) return;
    var cb = pending[msg.id];
    if (!cb) return;
    delete pending[msg.id];
    if (msg.error) cb.reject(new Error(msg.error.message || msg.error.code || "Bridge error"));
    else cb.resolve(msg.result);
  });
  function call(method, params) {
    return new Promise(function(resolve, reject) {
      var id = nextId++;
      pending[id] = { resolve: resolve, reject: reject };
      window.parent.postMessage({
        __superpiBridgeRequest: true,
        miniappId: "$safeId",
        payload: JSON.stringify({ id: id, method: method, params: params || {} })
      }, "*");
    });
  }
  window.superpi = {
    call: call,
    info: function(){ return call("info"); },
    data: {
      get: function(key){ return call("data.get", { key: key }); },
      set: function(key, value){ return call("data.set", { key: key, value: value }); },
      remove: function(key){ return call("data.remove", { key: key }); },
      keys: function(){ return call("data.keys"); }
    },
    ui: {
      toast: function(text){ return call("ui.toast", { text: String(text || "") }); }
    }
  };
})();
</script>
""".trimIndent()
    val headOpen = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    return if (headOpen != null) {
        val insertAt = headOpen.range.last + 1
        html.substring(0, insertAt) + "\n" + shim + "\n" + html.substring(insertAt)
    } else {
        shim + "\n" + html
    }
}

private fun TerminalInfo.toDto() = TerminalInfoDto(
    id = id,
    botId = botId,
    cwd = cwd,
    shell = shell,
    alive = alive,
    createdAt = createdAt,
    history = history,
)

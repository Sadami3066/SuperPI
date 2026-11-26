package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.SkillArchiveResult
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.Timer
import io.github.whywood.superpi.runtime.TimerPatch
import io.github.whywood.superpi.runtime.TimerTrigger
import io.github.whywood.superpi.runtime.validationError
import io.github.whywood.superpi.sdk.ai.auth.oauth.OAuthCallbackServer
import io.github.whywood.superpi.sdk.ai.auth.oauth.OAuthCallbackServerOptions
import io.github.whywood.superpi.sdk.ai.auth.oauth.startOAuthCallbackServerOrNull
import io.github.whywood.superpi.sdk.mcp.config.McpServerConfig
import io.github.whywood.superpi.sdk.mcp.oauth.McpOAuthInteraction
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
data class InstalledSkillDto(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val source: String = "super-pi",
    val removable: Boolean = true,
)

@Serializable
data class SkillContentResponse(
    val id: String,
    val content: String,
)

@Serializable
data class InstallSkillRequest(
    val fileName: String,
    val base64Zip: String,
)

@Serializable
data class ToggleSkillRequest(
    val enabled: Boolean,
)

@Serializable
data class UpsertMcpServerRequest(
    val transport: String = "http",
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class ToggleMcpRequest(
    val enabled: Boolean,
)

@Serializable
data class ImportMcpRawRequest(
    val json: String,
    val originalName: String? = null,
)

@Serializable
data class TimerDto(
    val id: String,
    val name: String,
    val prompt: String,
    val triggerType: String,
    val date: String? = null,
    val time: String? = null,
    val days: List<Int> = emptyList(),
    val cronExpression: String? = null,
    val enabled: Boolean,
    val sessionId: String,
    val cwd: String,
    val nextRunAt: Long? = null,
    val lastDeliveredAt: Long? = null,
    val lastError: String? = null,
)

@Serializable
data class SaveTimerRequest(
    val name: String,
    val prompt: String,
    val triggerType: String,
    val date: String? = null,
    val time: String? = null,
    val days: List<Int> = emptyList(),
    val cronExpression: String? = null,
    val enabled: Boolean = true,
    val botId: String? = null,
    val sessionId: String? = null,
)

@Serializable
data class ToggleTimerRequest(
    val enabled: Boolean,
)

private const val MAX_SKILL_ZIP_BYTES = 10 * 1024 * 1024
private const val MCP_OAUTH_FALLBACK_REDIRECT_URI = "http://127.0.0.1:53682/callback"

private class ActiveMcpOAuthAttempt(
    val serverName: String,
    var state: OAuthFlowStatusDto,
    var job: Job? = null,
    var callbackServer: OAuthCallbackServer<String>? = null,
    var manualInput: CompletableDeferred<String>? = null,
    var updatedAtMs: Long = System.currentTimeMillis(),
)

private class McpOAuthSessionRegistry(
    private val controller: SuperPiController,
    private val serverScope: CoroutineScope,
) {
    private val lock = Any()
    private val attempts = HashMap<String, ActiveMcpOAuthAttempt>()

    private fun evictStaleLocked(now: Long = System.currentTimeMillis()) {
        val it = attempts.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (entry.state.status in TERMINAL_STATUSES && now - entry.updatedAtMs > TERMINAL_TTL_MS) {
                it.remove()
            }
        }
    }

    private fun updateState(attempt: ActiveMcpOAuthAttempt, transform: (OAuthFlowStatusDto) -> OAuthFlowStatusDto) {
        synchronized(lock) {
            if (attempts[attempt.serverName] !== attempt) return
            attempt.state = transform(attempt.state)
            attempt.updatedAtMs = System.currentTimeMillis()
        }
    }

    fun status(serverName: String): OAuthFlowStatusDto = synchronized(lock) {
        evictStaleLocked()
        attempts[serverName]?.state ?: OAuthFlowStatusDto(providerId = serverName, status = "idle")
    }

    fun start(serverName: String, cfg: McpServerConfig.Http): OAuthFlowStatusDto {
        var prevJob: Job? = null
        var prevInput: CompletableDeferred<String>? = null
        var prevCb: OAuthCallbackServer<String>? = null
        val attempt = synchronized(lock) {
            evictStaleLocked()
            val prev = attempts.remove(serverName)
            prevJob = prev?.job
            prevInput = prev?.manualInput
            prevCb = prev?.callbackServer
            val created = ActiveMcpOAuthAttempt(
                serverName = serverName,
                state = OAuthFlowStatusDto(providerId = serverName, status = "starting"),
            )
            attempts[serverName] = created
            created
        }
        runCatching { prevInput?.cancel() }
        runCatching { prevCb?.cancel() }
        runCatching { prevJob?.cancel() }

        val job = serverScope.launch {
            val redirectUriRef = java.util.concurrent.atomic.AtomicReference(
                cfg.oauth?.callbackUrl ?: MCP_OAUTH_FALLBACK_REDIRECT_URI,
            )
            val cbServer = runCatching {
                startOAuthCallbackServerOrNull(
                    OAuthCallbackServerOptions<String>(
                        providerName = serverName,
                        host = "127.0.0.1",
                        port = cfg.oauth?.callbackPort ?: 0,
                        path = "/callback",
                        timeoutMs = CALLBACK_TIMEOUT_MS,
                        complete = { request ->
                            val base = redirectUriRef.get()
                            val query = request.params.entries.joinToString("&") { (k, v) ->
                                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                            }
                            if (query.isEmpty()) base else "$base?$query"
                        },
                    ),
                )
            }.getOrNull()
            val redirectUri = cbServer?.redirectUri ?: cfg.oauth?.callbackUrl ?: MCP_OAUTH_FALLBACK_REDIRECT_URI
            redirectUriRef.set(redirectUri)
            synchronized(lock) {
                if (attempts[serverName] === attempt) {
                    attempt.callbackServer = cbServer
                } else {
                    runCatching { cbServer?.cancel() }
                    return@launch
                }
            }

            val interaction = object : McpOAuthInteraction {
                override suspend fun authorize(url: String, redirectUri: String): String {
                    val manual = CompletableDeferred<String>()
                    synchronized(lock) {
                        if (attempts[serverName] !== attempt) throw CancellationException("Replaced")
                        attempt.manualInput = manual
                        attempt.state = attempt.state.copy(
                            status = "awaiting_browser",
                            url = url,
                            instructions = "Complete authorization in your browser. If redirected to a localhost page that cannot connect, paste the full callback URL below.",
                            promptLabel = "Callback URL (if browser did not complete automatically)",
                            promptPlaceholder = "http://127.0.0.1:.../callback?code=...",
                        )
                        attempt.updatedAtMs = System.currentTimeMillis()
                    }
                    if (cbServer != null) {
                        val cbJob = serverScope.launch {
                            try {
                                val cbResult = cbServer.wait()
                                if (!cbResult.isNullOrBlank()) {
                                    manual.complete(cbResult)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (err: Throwable) {
                                manual.completeExceptionally(err)
                            }
                        }
                        try {
                            return withTimeout(CALLBACK_TIMEOUT_MS) { manual.await() }
                        } finally {
                            cbJob.cancel()
                        }
                    }
                    return withTimeout(CALLBACK_TIMEOUT_MS) { manual.await() }
                }
            }

            try {
                val res = controller.mcpOAuthService.signIn(
                    server = serverName,
                    config = cfg,
                    interaction = interaction,
                    redirectUri = redirectUri,
                )
                res.fold(
                    onSuccess = {
                        runCatching { controller.reloadAll() }
                        updateState(attempt) {
                            OAuthFlowStatusDto(providerId = serverName, status = "completed")
                        }
                    },
                    onFailure = { err ->
                        when (err) {
                            is TimeoutCancellationException -> updateState(attempt) {
                                OAuthFlowStatusDto(
                                    providerId = serverName,
                                    status = "failed",
                                    error = "OAuth authorization timed out.",
                                )
                            }
                            is CancellationException -> updateState(attempt) {
                                OAuthFlowStatusDto(providerId = serverName, status = "cancelled")
                            }
                            else -> updateState(attempt) {
                                OAuthFlowStatusDto(
                                    providerId = serverName,
                                    status = "failed",
                                    error = err.message ?: "MCP OAuth failed",
                                )
                            }
                        }
                    },
                )
            } catch (_: TimeoutCancellationException) {
                updateState(attempt) {
                    OAuthFlowStatusDto(
                        providerId = serverName,
                        status = "failed",
                        error = "OAuth authorization timed out.",
                    )
                }
            } catch (cancelled: CancellationException) {
                updateState(attempt) {
                    OAuthFlowStatusDto(providerId = serverName, status = "cancelled")
                }
                throw cancelled
            } finally {
                runCatching { cbServer?.cancel() }
            }
        }
        synchronized(lock) {
            if (attempts[serverName] === attempt) attempt.job = job else job.cancel()
            return attempt.state
        }
    }

    fun submit(serverName: String, value: String): Boolean {
        val clean = value.trim()
        if (clean.isEmpty()) return false
        val deferred = synchronized(lock) {
            val attempt = attempts[serverName] ?: return false
            val pending = attempt.manualInput ?: return false
            attempt.manualInput = null
            attempt.state = attempt.state.copy(status = "working", promptLabel = null)
            attempt.updatedAtMs = System.currentTimeMillis()
            pending
        }
        return deferred.complete(clean)
    }

    fun cancel(serverName: String) {
        val prev = synchronized(lock) { attempts.remove(serverName) } ?: return
        runCatching { prev.manualInput?.cancel() }
        runCatching { prev.callbackServer?.cancel() }
        runCatching { prev.job?.cancel() }
    }

    private companion object {
        const val CALLBACK_TIMEOUT_MS = 5L * 60_000L
        const val TERMINAL_TTL_MS = 10L * 60_000L
        val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled")
    }
}

internal fun Route.installSkillMcpTimerRoutes(
    controller: SuperPiController,
    serverScope: CoroutineScope,
) {
    val mcpOAuthSessions = McpOAuthSessionRegistry(controller, serverScope)
    // ── Skills ───────────────────────────────────────────────────────────────
    get("/v1/skills") {
        runCatching { controller.skillRepository.syncIfChanged() }
        val disabled = controller.settingsStore.current().disabledExtensions.toSet()
        val seenNames = HashSet<String>()
        val managed = controller.skillRepository.list().getOrDefault(emptyList()).map { sk ->
            seenNames.add(sk.name.lowercase())
            InstalledSkillDto(
                id = sk.id,
                name = sk.name,
                description = sk.description,
                enabled = "skill:${sk.name}" !in disabled,
                installedAt = sk.installedAt,
                source = "super-pi",
                removable = true,
            )
        }
        val defaultCwd = controller.paths.defaultWorkspaceDir.absolutePath
        val discovered = controller.skillCatalog.listDiscovered(defaultCwd)
            .filter { seenNames.add(it.skill.name.lowercase()) }
            .map { entry ->
                val file = java.io.File(entry.skill.filePath)
                InstalledSkillDto(
                    id = "ext:${entry.skill.name}",
                    name = entry.skill.name,
                    description = entry.skill.description,
                    enabled = "skill:${entry.skill.name}" !in disabled,
                    installedAt = if (file.isFile) file.lastModified() else 0L,
                    source = entry.sourceLabel,
                    removable = false,
                )
            }
        call.respond((managed + discovered).sortedWith(compareBy({ !it.removable }, { it.name.lowercase() })))
    }

    get("/v1/skills/{id}/content") {
        val id = call.parameters["id"].orEmpty().trim()
        val content = if (id.startsWith("ext:")) {
            val skillName = id.removePrefix("ext:")
            val found = controller.skillCatalog.findDiscovered(
                controller.paths.defaultWorkspaceDir.absolutePath,
                skillName,
            )
            found?.let { runCatching { java.io.File(it.skill.filePath).readText(Charsets.UTF_8) }.getOrNull() ?: it.skill.content }
        } else {
            controller.skillRepository.readContent(id)
        }
        if (content == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Skill not found: $id"))
        } else {
            call.respond(SkillContentResponse(id = id, content = content))
        }
    }

    post("/v1/skills/install") {
        val req = call.receive<InstallSkillRequest>()
        val cleanB64 = req.base64Zip.substringAfter("base64,", req.base64Zip).trim()
        val maxEncodedLen = ((MAX_SKILL_ZIP_BYTES + 2) / 3) * 4
        if (cleanB64.isEmpty() || cleanB64.length > maxEncodedLen) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Skill archive must be between 1 B and 10 MB"))
            return@post
        }
        val bytes = try {
            Base64.getDecoder().decode(cleanB64)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid base64 zip payload"))
            return@post
        }
        if (bytes.isEmpty() || bytes.size > MAX_SKILL_ZIP_BYTES) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Skill archive must be between 1 B and 10 MB"))
            return@post
        }
        val safeFallbackName = req.fileName
            .replace('\\', '/')
            .substringAfterLast('/')
            .removeSuffix(".zip")
            .removeSuffix(".ZIP")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
            .ifBlank { "skill" }
        when (val result = controller.skillRepository.install(bytes, safeFallbackName)) {
            is SkillArchiveResult.Success -> {
                controller.reloadAll()
                call.respond(
                    HttpStatusCode.Created,
                    InstalledSkillDto(
                        id = result.skill.id,
                        name = result.skill.name,
                        description = result.skill.description,
                        enabled = true,
                        installedAt = System.currentTimeMillis(),
                    ),
                )
            }
            is SkillArchiveResult.Failure -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorResponse("Skill installation failed: ${result.error.name}"),
                )
            }
        }
    }

    put("/v1/skills/{name}/toggle") {
        val skillName = call.parameters["name"].orEmpty().trim()
        val req = call.receive<ToggleSkillRequest>()
        val token = "skill:$skillName"
        controller.settingsStore.update { cur ->
            val next = cur.disabledExtensions.toMutableSet()
            if (req.enabled) next.remove(token) else next.add(token)
            cur.copy(disabledExtensions = next.sorted())
        }
        controller.reloadAll()
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/skills/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        if (id.startsWith("ext:")) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("External shared skills are read-only here; disable them or remove them from their source folder."),
            )
            return@delete
        }
        val ok = controller.skillRepository.delete(id)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Skill not found: $id"))
            return@delete
        }
        controller.reloadAll()
        call.respond(HealthResponse(ok = true))
    }

    // ── MCP Servers ──────────────────────────────────────────────────────────
    get("/v1/mcp") {
        val snap = controller.mcpConfigStore.load()
        val items = snap.entries.keys.sorted().map { name ->
            buildMcpDto(controller, name, snap.servers[name])
        }
        call.respond(items)
    }

    post("/v1/mcp/test") {
        val req = call.receive<UpsertMcpServerRequest>()
        val cfg = buildMcpConfigFromRequest(req)
        if (cfg == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Valid URL or command is required"))
            return@post
        }
        val probe = controller.extensionRegistry.probeMcp("test-mcp", cfg)
        val (transport, target) = mcpSummary(cfg)
        call.respond(
            McpServerDto(
                name = "test-mcp",
                transport = transport,
                target = target,
                enabled = cfg.enabled,
                probeState = probe.state.name,
                toolCount = probe.toolCount,
                error = probe.error,
            ),
        )
    }

    post("/v1/mcp/{name}/probe") {
        val name = call.parameters["name"].orEmpty().trim()
        val snap = controller.mcpConfigStore.load()
        val cfg = snap.servers[name]
        if (cfg == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("MCP server not found: $name"))
            return@post
        }
        val probe = controller.extensionRegistry.probeMcp(name, cfg)
        call.respond(
            buildMcpDto(controller, name, cfg).copy(
                probeState = probe.state.name,
                toolCount = probe.toolCount,
                error = probe.error,
            ),
        )
    }

    post("/v1/mcp/{name}/oauth/start") {
        val name = call.parameters["name"].orEmpty().trim()
        val cfg = controller.mcpConfigStore.load().servers[name] as? McpServerConfig.Http
        if (cfg == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("HTTP MCP server not found: $name"))
            return@post
        }
        call.respond(mcpOAuthSessions.start(name, cfg))
    }

    get("/v1/mcp/{name}/oauth/status") {
        val name = call.parameters["name"].orEmpty().trim()
        call.respond(mcpOAuthSessions.status(name))
    }

    post("/v1/mcp/{name}/oauth/submit") {
        val name = call.parameters["name"].orEmpty().trim()
        val req = call.receive<OAuthSubmitRequest>()
        if (!mcpOAuthSessions.submit(name, req.value)) {
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse("No active OAuth flow for $name"))
            return@post
        }
        call.respond(mcpOAuthSessions.status(name))
    }

    post("/v1/mcp/{name}/oauth/cancel") {
        val name = call.parameters["name"].orEmpty().trim()
        mcpOAuthSessions.cancel(name)
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/mcp/{name}/oauth") {
        val name = call.parameters["name"].orEmpty().trim()
        mcpOAuthSessions.cancel(name)
        val cfg = controller.mcpConfigStore.load().servers[name] as? McpServerConfig.Http
        if (cfg != null) {
            controller.mcpOAuthService.signOut(name, cfg)
            controller.reloadAll()
        }
        call.respond(HealthResponse(ok = true))
    }

    put("/v1/mcp/raw") {
        val req = call.receive<ImportMcpRawRequest>()
        if (req.json.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("JSON configuration is required"))
            return@put
        }
        try {
            if (!req.originalName.isNullOrBlank()) {
                controller.mcpConfigStore.updateFromJson(req.originalName.trim(), req.json)
            } else {
                controller.mcpConfigStore.importServers(req.json)
            }
            controller.reloadAll()
            call.respond(HealthResponse(ok = true))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid MCP JSON"))
        }
    }

    put("/v1/mcp/{name}") {
        val name = call.parameters["name"].orEmpty().trim()
        if (name.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("MCP server name is required"))
            return@put
        }
        val req = call.receive<UpsertMcpServerRequest>()
        val cfg = when (req.transport.trim().lowercase()) {
            "stdio" -> {
                val cmd = req.command?.trim().orEmpty()
                if (cmd.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Command is required for stdio MCP"))
                    return@put
                }
                McpServerConfig.Stdio(
                    command = cmd,
                    args = req.args,
                    env = req.env,
                    cwd = req.cwd?.trim()?.takeIf { it.isNotEmpty() },
                    enabled = req.enabled,
                )
            }
            else -> {
                val url = req.url?.trim().orEmpty()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Valid HTTP(S) URL is required"))
                    return@put
                }
                val existingOAuth = (controller.mcpConfigStore.load().servers[name] as? McpServerConfig.Http)?.oauth
                McpServerConfig.Http(
                    url = url,
                    headers = req.headers,
                    enabled = req.enabled,
                    oauth = existingOAuth,
                )
            }
        }
        try {
            controller.mcpConfigStore.upsert(name, cfg)
            controller.reloadAll()
            call.respond(HealthResponse(ok = true))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid MCP configuration"))
        }
    }

    post("/v1/mcp/{name}/toggle") {
        val name = call.parameters["name"].orEmpty().trim()
        val req = call.receive<ToggleMcpRequest>()
        try {
            controller.mcpConfigStore.setEnabled(name, req.enabled)
            controller.reloadAll()
            call.respond(HealthResponse(ok = true))
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid MCP server configuration"))
        }
    }

    delete("/v1/mcp/{name}") {
        val name = call.parameters["name"].orEmpty().trim()
        mcpOAuthSessions.cancel(name)
        val existing = controller.mcpConfigStore.load().servers[name] as? McpServerConfig.Http
        if (existing != null) {
            runCatching { controller.mcpOAuthService.signOut(name, existing) }
        }
        controller.mcpConfigStore.remove(name)
        controller.reloadAll()
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/mcp/probe") {
        val snap = controller.mcpConfigStore.load()
        val items = snap.entries.keys.sorted().map { name ->
            val cfg = snap.servers[name]
            val base = buildMcpDto(controller, name, cfg)
            if (cfg == null) {
                base.copy(probeState = "FAILED")
            } else if (!cfg.enabled) {
                base.copy(probeState = "SKIPPED")
            } else {
                val probe = controller.extensionRegistry.probeMcp(name, cfg)
                base.copy(
                    probeState = probe.state.name,
                    toolCount = probe.toolCount,
                    error = probe.error,
                )
            }
        }
        call.respond(items)
    }

    // ── Timers ───────────────────────────────────────────────────────────────
    get("/v1/timers") {
        val list = controller.timerService.list().map { it.toDto() }
        call.respond(list)
    }

    post("/v1/timers") {
        val req = call.receive<SaveTimerRequest>()
        val name = req.name.trim()
        val prompt = req.prompt.trim()
        if (name.isEmpty() || prompt.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Timer name and prompt are required"))
            return@post
        }
        val trigger = parseTimerTrigger(req) ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid timer schedule"))
            return@post
        }
        if (req.enabled) {
            val err = trigger.validationError(ZonedDateTime.now())
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err))
                return@post
            }
        }
        val (sessionId, sessionFile, cwd) = resolveTimerTargetSession(controller, req.botId, req.sessionId)
            ?: run {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Target bot or session not found"))
                return@post
            }
        try {
            val created = controller.timerService.create(
                name = name,
                prompt = prompt,
                trigger = trigger,
                enabled = req.enabled,
                sessionId = sessionId,
                sessionFile = sessionFile,
                cwd = cwd,
            )
            call.respond(HttpStatusCode.Created, created.toDto())
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid timer"))
        }
    }

    put("/v1/timers/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        val req = call.receive<SaveTimerRequest>()
        val trigger = parseTimerTrigger(req) ?: run {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid timer schedule"))
            return@put
        }
        if (req.enabled) {
            val err = trigger.validationError(ZonedDateTime.now())
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err))
                return@put
            }
        }
        try {
            val updated = controller.timerService.update(
                id,
                TimerPatch(
                    name = req.name.trim().takeIf { it.isNotEmpty() },
                    prompt = req.prompt.trim().takeIf { it.isNotEmpty() },
                    trigger = trigger,
                    enabled = req.enabled,
                ),
            )
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Timer not found: $id"))
            } else {
                call.respond(updated.toDto())
            }
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid timer"))
        }
    }

    post("/v1/timers/{id}/toggle") {
        val id = call.parameters["id"].orEmpty().trim()
        val req = call.receive<ToggleTimerRequest>()
        try {
            val updated = controller.timerService.update(id, TimerPatch(enabled = req.enabled))
            if (updated == null) {
                call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Timer not found: $id"))
            } else {
                call.respond(updated.toDto())
            }
        } catch (err: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Invalid timer schedule"))
        }
    }

    post("/v1/timers/{id}/run-now") {
        val id = call.parameters["id"].orEmpty().trim()
        val res = controller.timerService.runNow(id)
        res.fold(
            onSuccess = { call.respond(HealthResponse(ok = true)) },
            onFailure = { err ->
                call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(err.message ?: "Timer run failed"))
            },
        )
    }

    delete("/v1/timers/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        val ok = controller.timerService.delete(id)
        if (!ok) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Timer not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }

    // ── Automations Review Inbox ─────────────────────────────────────────────
    get("/v1/inbox") {
        call.respond(controller.inboxStore.list())
    }

    post("/v1/inbox/{id}/status") {
        val id = call.parameters["id"].orEmpty().trim()
        val req = call.receive<UpdateInboxStatusRequest>()
        val updated = withContext(Dispatchers.IO) { controller.inboxStore.updateStatus(id, req.status) }
        if (updated == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Inbox item not found or invalid status"))
        } else {
            call.respond(updated)
        }
    }

    delete("/v1/inbox/cleared") {
        val remaining = withContext(Dispatchers.IO) {
            controller.inboxStore.clearSettled()
            controller.inboxStore.list()
        }
        call.respond(remaining)
    }

    delete("/v1/inbox/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        val removed = withContext(Dispatchers.IO) { controller.inboxStore.delete(id) }
        if (!removed) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Inbox item not found: $id"))
        } else {
            call.respond(HealthResponse(ok = true))
        }
    }
}

private fun mcpSummary(cfg: McpServerConfig): Pair<String, String> = when (cfg) {
    is McpServerConfig.Http -> "http" to cfg.url
    is McpServerConfig.Stdio -> "stdio" to (listOf(cfg.command) + cfg.args).joinToString(" ").trim()
}

private suspend fun buildMcpDto(
    controller: SuperPiController,
    name: String,
    cfg: McpServerConfig?,
): McpServerDto = when (cfg) {
    is McpServerConfig.Http -> McpServerDto(
        name = name,
        transport = "http",
        target = cfg.url,
        enabled = cfg.enabled,
        url = cfg.url,
        headers = cfg.headers,
        hasAuthToken = runCatching { controller.mcpOAuthService.hasToken(name, cfg) }.getOrDefault(false),
    )
    is McpServerConfig.Stdio -> McpServerDto(
        name = name,
        transport = "stdio",
        target = (listOf(cfg.command) + cfg.args).joinToString(" ").trim(),
        enabled = cfg.enabled,
        command = cfg.command,
        args = cfg.args,
        env = cfg.env,
        cwd = cfg.cwd,
    )
    null -> McpServerDto(
        name = name,
        transport = "invalid",
        target = "",
        enabled = false,
        error = "Invalid MCP configuration",
    )
}

private fun buildMcpConfigFromRequest(req: UpsertMcpServerRequest): McpServerConfig? =
    when (req.transport.trim().lowercase()) {
        "stdio" -> {
            val cmd = req.command?.trim().orEmpty()
            if (cmd.isEmpty()) null
            else McpServerConfig.Stdio(
                command = cmd,
                args = req.args,
                env = req.env,
                cwd = req.cwd?.trim()?.takeIf { it.isNotEmpty() },
                enabled = req.enabled,
            )
        }
        else -> {
            val url = req.url?.trim().orEmpty()
            if (!url.startsWith("http://") && !url.startsWith("https://")) null
            else McpServerConfig.Http(
                url = url,
                headers = req.headers,
                enabled = req.enabled,
            )
        }
    }

private fun parseTimerTrigger(req: SaveTimerRequest): TimerTrigger? = runCatching {
    when (req.triggerType.trim().lowercase()) {
        "once" -> TimerTrigger.Once(
            date = LocalDate.parse(req.date?.trim().orEmpty()),
            time = LocalTime.parse(req.time?.trim().orEmpty()),
        )
        "daily" -> TimerTrigger.Daily(
            time = LocalTime.parse(req.time?.trim().orEmpty()),
        )
        "weekly" -> TimerTrigger.Weekly(
            days = req.days.map { DayOfWeek.of(it) }.toSet(),
            time = LocalTime.parse(req.time?.trim().orEmpty()),
        )
        "cron" -> TimerTrigger.Cron(req.cronExpression?.trim().orEmpty())
        else -> null
    }
}.getOrNull()

private suspend fun resolveTimerTargetSession(
    controller: SuperPiController,
    botId: String?,
    sessionId: String?,
): Triple<String, String, String>? {
    val cleanSessionId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanSessionId != null) {
        val existing = controller.runtimeHost.allSessions().firstOrNull { it.id == cleanSessionId }
            ?: return null
        if (controller.isRemoteCwd(existing.cwd)) return null
        ensureSessionFileInitialized(existing.id, existing.filePath, existing.cwd)
        return Triple(existing.id, existing.filePath, existing.cwd)
    }
    val cleanBotId = botId?.trim()?.takeIf { it.isNotEmpty() }
    val bot = if (cleanBotId != null) {
        controller.botRegistry.findBotById(cleanBotId) ?: return null
    } else {
        controller.botRegistry.homeBot()
    }
    val existing = controller.runtimeHost.sessions(bot.id).firstOrNull()
    if (existing != null) {
        val cwd = existing.cwd.ifBlank { bot.cwd }
        ensureSessionFileInitialized(existing.id, existing.filePath, cwd)
        return Triple(existing.id, existing.filePath, cwd)
    }
    val created = controller.runtimeHost.newSessionInDirectory(bot.cwd)
    val filePath = created.filePath?.takeIf { it.isNotBlank() } ?: return null
    ensureSessionFileInitialized(created.sessionId, filePath, created.cwd)
    return Triple(created.sessionId, filePath, created.cwd)
}

private fun ensureSessionFileInitialized(sessionId: String, filePath: String, cwd: String) {
    if (filePath.isBlank()) return
    val file = java.io.File(filePath)
    if (file.isFile && file.length() > 0L) return
    file.parentFile?.mkdirs()
    val escapedCwd = cwd.replace("\\", "\\\\").replace("\"", "\\\"")
    val header = """{"type":"session","version":3,"id":"$sessionId","timestamp":"${java.time.Instant.now()}","cwd":"$escapedCwd"}"""
    file.writeText(header + "\n", Charsets.UTF_8)
}

private fun Timer.toDto(): TimerDto = when (val tr = trigger) {
    is TimerTrigger.Once -> TimerDto(
        id = id,
        name = name,
        prompt = prompt,
        triggerType = "once",
        date = tr.date.toString(),
        time = tr.time.toString(),
        enabled = enabled,
        sessionId = sessionId,
        cwd = cwd,
        nextRunAt = nextRunAt,
        lastDeliveredAt = lastDeliveredAt,
        lastError = lastError?.name,
    )
    is TimerTrigger.Daily -> TimerDto(
        id = id,
        name = name,
        prompt = prompt,
        triggerType = "daily",
        time = tr.time.toString(),
        enabled = enabled,
        sessionId = sessionId,
        cwd = cwd,
        nextRunAt = nextRunAt,
        lastDeliveredAt = lastDeliveredAt,
        lastError = lastError?.name,
    )
    is TimerTrigger.Weekly -> TimerDto(
        id = id,
        name = name,
        prompt = prompt,
        triggerType = "weekly",
        time = tr.time.toString(),
        days = tr.days.map { it.value }.sorted(),
        enabled = enabled,
        sessionId = sessionId,
        cwd = cwd,
        nextRunAt = nextRunAt,
        lastDeliveredAt = lastDeliveredAt,
        lastError = lastError?.name,
    )
    is TimerTrigger.Cron -> TimerDto(
        id = id,
        name = name,
        prompt = prompt,
        triggerType = "cron",
        cronExpression = tr.expression,
        enabled = enabled,
        sessionId = sessionId,
        cwd = cwd,
        nextRunAt = nextRunAt,
        lastDeliveredAt = lastDeliveredAt,
        lastError = lastError?.name,
    )
}

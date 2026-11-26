package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.CustomProviderEntry
import io.github.whywood.superpi.data.CustomProviderModel
import io.github.whywood.superpi.data.CustomProviderProtocol
import io.github.whywood.superpi.data.CustomProviderStore
import io.github.whywood.superpi.data.WebSearchSettingsStore
import io.github.whywood.superpi.host.core.SuperPiController
import io.github.whywood.superpi.runtime.ThinkingLevel
import io.github.whywood.superpi.sdk.ai.auth.oauth.OAuthInteraction
import io.github.whywood.superpi.sdk.ai.auth.oauth.OAuthSelectOption
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

@Serializable
data class CustomModelDto(
    val id: String,
    val name: String = "",
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val supportsImages: Boolean = false,
    val reasoning: Boolean = false,
    val thinkingLevels: List<String> = emptyList(),
)

@Serializable
data class CustomProviderDto(
    val id: String,
    val name: String,
    val api: String,
    val baseUrl: String,
    val keyHint: String? = null,
    val models: List<CustomModelDto> = emptyList(),
)

@Serializable
data class SaveCustomProviderRequest(
    val id: String? = null,
    val name: String,
    val api: String = CustomProviderProtocol.OpenAiCompletions.value,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<CustomModelDto> = emptyList(),
)

@Serializable
data class DiscoverModelsRequest(
    val baseUrl: String,
    val apiKey: String = "",
    val api: String = CustomProviderProtocol.OpenAiCompletions.value,
)

@Serializable
data class DiscoverModelsResponse(
    val models: List<String> = emptyList(),
    val detailedModels: List<CustomModelDto> = emptyList(),
)

@Serializable
data class OAuthSelectOptionDto(
    val id: String,
    val label: String,
    val description: String? = null,
)

@Serializable
data class OAuthFlowStatusDto(
    val providerId: String,
    val status: String,
    val url: String? = null,
    val instructions: String? = null,
    val message: String? = null,
    val promptLabel: String? = null,
    val promptPlaceholder: String? = null,
    val promptSecret: Boolean = false,
    val options: List<OAuthSelectOptionDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class OAuthSubmitRequest(
    val value: String,
)

@Serializable
data class WebSearchProviderDto(
    val id: String,
    val name: String,
    val configured: Boolean,
    val keyHint: String? = null,
)

private class ActiveOAuthAttempt(
    val providerId: String,
    var state: OAuthFlowStatusDto,
    var job: Job? = null,
    var pendingInput: CompletableDeferred<String>? = null,
    var updatedAtMs: Long = System.currentTimeMillis(),
)

private class ProviderOAuthSessionRegistry(
    private val controller: SuperPiController,
    private val serverScope: CoroutineScope,
) {
    private val lock = Any()
    private val attempts = HashMap<String, ActiveOAuthAttempt>()

    private fun evictStaleLocked(now: Long = System.currentTimeMillis()) {
        val it = attempts.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            val terminal = entry.state.status in TERMINAL_STATUSES
            if (terminal && now - entry.updatedAtMs > TERMINAL_TTL_MS) {
                it.remove()
            }
        }
    }

    private fun updateState(attempt: ActiveOAuthAttempt, transform: (OAuthFlowStatusDto) -> OAuthFlowStatusDto) {
        synchronized(lock) {
            if (attempts[attempt.providerId] !== attempt) return
            attempt.state = transform(attempt.state)
            attempt.updatedAtMs = System.currentTimeMillis()
        }
    }

    fun status(providerId: String): OAuthFlowStatusDto = synchronized(lock) {
        evictStaleLocked()
        attempts[providerId]?.state ?: OAuthFlowStatusDto(providerId = providerId, status = "idle")
    }

    fun start(providerId: String): OAuthFlowStatusDto {
        var prevJob: Job? = null
        var prevInput: CompletableDeferred<String>? = null
        val attempt = synchronized(lock) {
            evictStaleLocked()
            val prev = attempts.remove(providerId)
            prevJob = prev?.job
            prevInput = prev?.pendingInput
            val created = ActiveOAuthAttempt(
                providerId = providerId,
                state = OAuthFlowStatusDto(providerId = providerId, status = "starting"),
            )
            attempts[providerId] = created
            created
        }
        runCatching { prevInput?.cancel() }
        runCatching { prevJob?.cancel() }

        val interaction = object : OAuthInteraction {
            override suspend fun openUrl(url: String, instructions: String?) {
                updateState(attempt) {
                    it.copy(
                        status = "awaiting_browser",
                        url = url,
                        instructions = instructions,
                    )
                }
            }

            override suspend fun showMessage(message: String) {
                updateState(attempt) { it.copy(message = message) }
            }

            override suspend fun prompt(message: String, placeholder: String?, secret: Boolean): String {
                val deferred = CompletableDeferred<String>()
                synchronized(lock) {
                    if (attempts[providerId] !== attempt) throw CancellationException("OAuth flow replaced")
                    attempt.pendingInput = deferred
                    attempt.state = attempt.state.copy(
                        status = "awaiting_prompt",
                        promptLabel = message,
                        promptPlaceholder = placeholder,
                        promptSecret = secret,
                        options = emptyList(),
                    )
                    attempt.updatedAtMs = System.currentTimeMillis()
                }
                return try {
                    withTimeout(PROMPT_TIMEOUT_MS) { deferred.await() }
                } finally {
                    synchronized(lock) {
                        if (attempt.pendingInput === deferred) attempt.pendingInput = null
                    }
                }
            }

            override suspend fun select(message: String, options: List<OAuthSelectOption>): String {
                val deferred = CompletableDeferred<String>()
                synchronized(lock) {
                    if (attempts[providerId] !== attempt) throw CancellationException("OAuth flow replaced")
                    attempt.pendingInput = deferred
                    attempt.state = attempt.state.copy(
                        status = "awaiting_select",
                        promptLabel = message,
                        options = options.map { OAuthSelectOptionDto(id = it.id, label = it.label, description = it.description) },
                    )
                    attempt.updatedAtMs = System.currentTimeMillis()
                }
                return try {
                    withTimeout(PROMPT_TIMEOUT_MS) { deferred.await() }
                } finally {
                    synchronized(lock) {
                        if (attempt.pendingInput === deferred) attempt.pendingInput = null
                    }
                }
            }
        }

        val job = serverScope.launch {
            try {
                val result = controller.providerOAuthService.login(providerId, interaction)
                result.fold(
                    onSuccess = {
                        runCatching {
                            controller.reloadAll()
                            controller.ensureConfiguredDefaultModel(preferredProviderId = providerId)
                        }
                        updateState(attempt) {
                            OAuthFlowStatusDto(providerId = providerId, status = "completed")
                        }
                    },
                    onFailure = { err ->
                        when (err) {
                            is TimeoutCancellationException -> updateState(attempt) {
                                OAuthFlowStatusDto(
                                    providerId = providerId,
                                    status = "failed",
                                    error = "OAuth login timed out waiting for confirmation.",
                                )
                            }
                            is CancellationException -> updateState(attempt) {
                                OAuthFlowStatusDto(providerId = providerId, status = "cancelled")
                            }
                            else -> updateState(attempt) {
                                OAuthFlowStatusDto(
                                    providerId = providerId,
                                    status = "failed",
                                    error = err.message ?: "OAuth login failed",
                                )
                            }
                        }
                    },
                )
            } catch (err: TimeoutCancellationException) {
                updateState(attempt) {
                    OAuthFlowStatusDto(
                        providerId = providerId,
                        status = "failed",
                        error = "OAuth login timed out waiting for confirmation.",
                    )
                }
            } catch (cancelled: CancellationException) {
                updateState(attempt) {
                    OAuthFlowStatusDto(providerId = providerId, status = "cancelled")
                }
                throw cancelled
            }
        }
        synchronized(lock) {
            if (attempts[providerId] === attempt) {
                attempt.job = job
            } else {
                job.cancel()
            }
            return attempt.state
        }
    }

    fun submit(providerId: String, value: String): Boolean {
        val deferred = synchronized(lock) {
            val attempt = attempts[providerId] ?: return false
            val pending = attempt.pendingInput ?: return false
            attempt.pendingInput = null
            attempt.state = attempt.state.copy(status = "working", promptLabel = null, options = emptyList())
            attempt.updatedAtMs = System.currentTimeMillis()
            pending
        }
        return deferred.complete(value)
    }

    fun cancel(providerId: String) {
        val prev = synchronized(lock) { attempts.remove(providerId) } ?: return
        runCatching { prev.pendingInput?.cancel() }
        runCatching { prev.job?.cancel() }
    }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 10L * 60_000L
        const val TERMINAL_TTL_MS = 10L * 60_000L
        val TERMINAL_STATUSES = setOf("completed", "failed", "cancelled")
    }
}

private val discoverJson = Json { ignoreUnknownKeys = true }
private val CUSTOM_PROVIDER_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
private const val MAX_DISCOVER_RESPONSE_BYTES = 2 * 1024 * 1024

private object SafeDiscoverDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        for (addr in addresses) {
            if (addr.isLinkLocalAddress || addr.isAnyLocalAddress || addr.isMulticastAddress) {
                throw UnknownHostException("Blocked address for host '$hostname'")
            }
        }
        return addresses
    }
}

internal fun Route.installProviderExtensionRoutes(
    controller: SuperPiController,
    serverScope: CoroutineScope,
) {
    val oauthSessions = ProviderOAuthSessionRegistry(controller, serverScope)
    val discoverClient = controller.sharedHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(SafeDiscoverDns)
        .build()

    get("/v1/providers/custom") {
        val list = controller.customProviderStore.entries.value.map { it.toDto() }
        call.respond(list)
    }

    post("/v1/providers/custom") {
        val req = call.receive<SaveCustomProviderRequest>()
        val id = req.id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: CustomProviderStore.newId()
        if (!CUSTOM_PROVIDER_ID_REGEX.matches(id)) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Invalid custom provider ID '$id'. Use lowercase letters, digits, '.', '_' or '-'."),
            )
            return@post
        }
        val builtInIds = controller.providerCatalog.summaries
            .map { it.id.lowercase() }
            .toSet() - controller.customProviderStore.entries.value.map { it.id.lowercase() }.toSet()
        if (id in builtInIds) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Provider ID '$id' conflicts with a built-in provider."),
            )
            return@post
        }
        if (controller.customProviderStore.entries.value.any { it.id.equals(id, ignoreCase = true) }) {
            call.respond(
                HttpStatusCode.Conflict,
                ApiErrorResponse("Custom provider '$id' already exists."),
            )
            return@post
        }
        val entry = buildCustomProviderEntry(id = id, req = req, existingKey = null)
        if (entry == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse("Invalid custom provider: name, http(s) baseUrl, protocol, apiKey, and at least one model are required."),
            )
            return@post
        }
        controller.customProviderStore.saveEntry(entry)
        controller.reloadAll()
        controller.ensureConfiguredDefaultModel(preferredProviderId = entry.id)
        call.respond(HttpStatusCode.Created, entry.toDto())
    }

    put("/v1/providers/custom/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        val existing = controller.customProviderStore.entries.value.firstOrNull { it.id == id }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, ApiErrorResponse("Custom provider not found: $id"))
            return@put
        }
        val req = call.receive<SaveCustomProviderRequest>()
        val entry = buildCustomProviderEntry(id = id, req = req, existingKey = existing.apiKey)
        if (entry == null) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid custom provider configuration."))
            return@put
        }
        controller.customProviderStore.saveEntry(entry)
        controller.reloadAll()
        call.respond(entry.toDto())
    }

    delete("/v1/providers/custom/{id}") {
        val id = call.parameters["id"].orEmpty().trim()
        if (id.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Provider ID is required"))
            return@delete
        }
        controller.customProviderStore.delete(id)
        controller.reloadAll()
        call.respond(HealthResponse(ok = true))
    }

    post("/v1/providers/custom/discover") {
        val req = call.receive<DiscoverModelsRequest>()
        val base = req.baseUrl.trim().trimEnd('/')
        val modelsUrl = if (base.endsWith("/models")) base else "$base/models"
        val parsedUrl = modelsUrl.toHttpUrlOrNull()
        if (parsedUrl == null ||
            (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") ||
            parsedUrl.username.isNotEmpty() ||
            parsedUrl.password.isNotEmpty()
        ) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid http(s) base URL"))
            return@post
        }
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(parsedUrl).get()
                val key = req.apiKey.trim()
                if (key.isNotEmpty()) {
                    if (req.api == CustomProviderProtocol.AnthropicMessages.value) {
                        builder.header("x-api-key", key)
                        builder.header("anthropic-version", "2023-06-01")
                    } else {
                        builder.header("Authorization", "Bearer $key")
                    }
                }
                discoverClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code} from ${parsedUrl.host}")
                    }
                    val source = resp.body.source()
                    val capped = okio.Buffer()
                    var total = 0L
                    while (true) {
                        val read = source.read(capped, 8192L)
                        if (read < 0L) break
                        total += read
                        if (total > MAX_DISCOVER_RESPONSE_BYTES) {
                            throw IllegalStateException("Models response exceeded 2 MB limit")
                        }
                    }
                    val raw = capped.readUtf8()
                    val parsed = discoverJson.parseToJsonElement(raw)
                    val arr = when (parsed) {
                        is JsonObject -> (parsed["data"] as? JsonArray) ?: (parsed["models"] as? JsonArray)
                        is JsonArray -> parsed
                        else -> null
                    } ?: JsonArray(emptyList())
                    val detailed = arr.mapNotNull { el ->
                        when (el) {
                            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                                CustomModelDto(id = id, name = id)
                            }
                            is JsonObject -> {
                                val id = (el["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: return@mapNotNull null
                                val displayName = (el["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: (el["display_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: id
                                val ctx = (el["context_length"] as? JsonPrimitive)?.longOrNull
                                    ?: (el["context_window"] as? JsonPrimitive)?.longOrNull
                                    ?: (el["input_token_limit"] as? JsonPrimitive)?.longOrNull
                                val maxTok = (el["max_output_tokens"] as? JsonPrimitive)?.longOrNull
                                    ?: (el["max_tokens"] as? JsonPrimitive)?.longOrNull
                                    ?: (el["output_token_limit"] as? JsonPrimitive)?.longOrNull
                                val arch = el["architecture"] as? JsonObject
                                val inputModalities = (arch?.get("input_modalities") as? JsonArray)
                                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }
                                    .orEmpty()
                                val supportsImg = (el["supports_images"] as? JsonPrimitive)?.booleanOrNull
                                    ?: (el["vision"] as? JsonPrimitive)?.booleanOrNull
                                    ?: ("image" in inputModalities)
                                val reasoning = (el["reasoning"] as? JsonPrimitive)?.booleanOrNull
                                    ?: id.contains("o1", ignoreCase = true)
                                    || id.contains("o3", ignoreCase = true)
                                    || id.contains("r1", ignoreCase = true)
                                    || id.contains("thinking", ignoreCase = true)
                                    || id.contains("reasoner", ignoreCase = true)
                                CustomModelDto(
                                    id = id,
                                    name = displayName,
                                    contextWindow = ctx,
                                    maxTokens = maxTok,
                                    supportsImages = supportsImg,
                                    reasoning = reasoning,
                                )
                            }
                            else -> null
                        }
                    }.distinctBy { it.id }.sortedBy { it.id }
                    DiscoverModelsResponse(
                        models = detailed.map { it.id },
                        detailedModels = detailed,
                    )
                }
            }
        }
        res.fold(
            onSuccess = { call.respond(it) },
            onFailure = { err ->
                call.respond(HttpStatusCode.BadGateway, ApiErrorResponse(err.message ?: "Failed to discover models"))
            },
        )
    }

    // ── Built-in Provider OAuth ──────────────────────────────────────────────
    post("/v1/providers/{providerId}/oauth/start") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        if (!controller.providerOAuthService.supports(providerId)) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Provider $providerId does not support OAuth"))
            return@post
        }
        call.respond(oauthSessions.start(providerId))
    }

    get("/v1/providers/{providerId}/oauth/status") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        call.respond(oauthSessions.status(providerId))
    }

    post("/v1/providers/{providerId}/oauth/submit") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        val req = call.receive<OAuthSubmitRequest>()
        if (!oauthSessions.submit(providerId, req.value)) {
            call.respond(HttpStatusCode.Conflict, ApiErrorResponse("No active OAuth prompt for $providerId"))
            return@post
        }
        call.respond(oauthSessions.status(providerId))
    }

    post("/v1/providers/{providerId}/oauth/cancel") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        oauthSessions.cancel(providerId)
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/providers/{providerId}/oauth") {
        val providerId = call.parameters["providerId"].orEmpty().trim()
        oauthSessions.cancel(providerId)
        controller.providerOAuthService.logout(providerId)
        controller.reloadAll()
        call.respond(HealthResponse(ok = true))
    }

    // ── Web Search API Keys ──────────────────────────────────────────────────
    get("/v1/web-search") {
        val saved = runCatching { controller.webSearchSettings.readAll() }.getOrDefault(emptyMap())
        val items = listOf(
            WebSearchSettingsStore.EXA to "Exa",
            WebSearchSettingsStore.TAVILY to "Tavily",
            WebSearchSettingsStore.BRAVE to "Brave Search",
            WebSearchSettingsStore.SERPER to "Serper",
        ).map { (id, label) ->
            val key = saved[id]
            WebSearchProviderDto(
                id = id,
                name = label,
                configured = !key.isNullOrBlank(),
                keyHint = maskApiKey(key),
            )
        }
        call.respond(items)
    }

    put("/v1/web-search/{providerId}") {
        val providerId = call.parameters["providerId"].orEmpty().trim().lowercase()
        val req = call.receive<SaveProviderKeyRequest>()
        if (providerId !in WebSearchSettingsStore.providers || req.apiKey.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid web search provider or empty API key"))
            return@put
        }
        controller.webSearchSettings.save(providerId, req.apiKey.trim())
        call.respond(HealthResponse(ok = true))
    }

    delete("/v1/web-search/{providerId}") {
        val providerId = call.parameters["providerId"].orEmpty().trim().lowercase()
        if (providerId !in WebSearchSettingsStore.providers) {
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("Invalid web search provider"))
            return@delete
        }
        controller.webSearchSettings.save(providerId, null)
        call.respond(HealthResponse(ok = true))
    }
}

private fun CustomProviderEntry.toDto(): CustomProviderDto = CustomProviderDto(
    id = id,
    name = name,
    api = api,
    baseUrl = baseUrl,
    keyHint = maskApiKey(apiKey),
    models = models.map { m ->
        CustomModelDto(
            id = m.id,
            name = m.name,
            contextWindow = m.contextWindow,
            maxTokens = m.maxTokens,
            supportsImages = m.supportsImages,
            reasoning = m.reasoning,
            thinkingLevels = m.thinkingLevels.map { it.name },
        )
    },
)

private fun buildCustomProviderEntry(
    id: String,
    req: SaveCustomProviderRequest,
    existingKey: String?,
): CustomProviderEntry? {
    val cleanName = req.name.trim()
    val cleanApi = req.api.trim()
    val cleanUrl = req.baseUrl.trim()
    val cleanKey = req.apiKey.trim().ifEmpty { existingKey.orEmpty().trim() }
    if (!CUSTOM_PROVIDER_ID_REGEX.matches(id)) return null
    if (cleanName.isEmpty() || cleanKey.isEmpty() || CustomProviderProtocol.fromValue(cleanApi) == null) return null
    val parsedUrl = cleanUrl.toHttpUrlOrNull() ?: return null
    if (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") return null
    val models = req.models.mapNotNull { m ->
        val mid = m.id.trim()
        if (mid.isEmpty()) return@mapNotNull null
        val levels = m.thinkingLevels.mapNotNull { raw ->
            ThinkingLevel.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }.toSet()
        val effectiveLevels = if (m.reasoning && levels.isEmpty()) {
            setOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
        } else {
            levels
        }
        CustomProviderModel(
            id = mid,
            name = m.name.trim().ifBlank { mid },
            contextWindow = m.contextWindow?.takeIf { it > 0 },
            maxTokens = m.maxTokens?.takeIf { it > 0 },
            supportsImages = m.supportsImages,
            reasoning = m.reasoning && effectiveLevels.isNotEmpty(),
            thinkingLevels = if (m.reasoning) effectiveLevels else emptySet(),
        )
    }
    if (models.isEmpty()) return null
    return CustomProviderEntry(
        id = id,
        name = cleanName,
        api = cleanApi,
        baseUrl = cleanUrl,
        apiKey = cleanKey,
        models = models,
    )
}

package io.github.whywood.superpi.host.server

import io.github.whywood.superpi.data.relay.RelayController
import io.github.whywood.superpi.data.relay.RelayError
import io.github.whywood.superpi.data.relay.RelayLiveState
import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.host.core.SuperPiController
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Loopback-only HTTP + SSE + Embedded Web UI control server (`127.0.0.1`).
 * Validates Host + Origin headers, enforces Bearer auth at the `/api` route level
 * (after Ktor path normalization/decoding), and uses single-use SSE tickets for `/api/v1/events`.
 */
class LocalControlServer(
    private val controller: SuperPiController,
    private val requestedPort: Int = 0,
    private val lockFile: File? = controller.paths.serveLockFile,
    private val onShutdownRequested: suspend () -> Unit = {},
) {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val pairMutex = Mutex()
    private val lifecycleLock = Any()
    private val sseTickets = SseTicketStore()

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile
    private var serverScope: CoroutineScope? = null
    private var instanceLockChannel: FileChannel? = null
    private var instanceLock: FileLock? = null

    val bearerToken: String = run {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Volatile
    var actualPort: Int = 0
        private set

    suspend fun start(): Int {
        synchronized(lifecycleLock) {
            if (server != null) return actualPort
            acquireInstanceLock()
        }
        controller.ensureAutoRetryStarted()
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var created: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        try {
            created = embeddedServer(CIO, port = requestedPort, host = "127.0.0.1") {
                install(ContentNegotiation) {
                    json(this@LocalControlServer.json)
                }
                intercept(ApplicationCallPipeline.Call) {
                    val hostHeader = call.request.headers[HttpHeaders.Host]?.trim()
                    if (!hostHeader.isNullOrEmpty() && !isAllowedHost(hostHeader)) {
                        call.respondText(
                            text = json.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse("Forbidden host")),
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.Forbidden,
                        )
                        finish()
                        return@intercept
                    }

                    val origin = call.request.headers[HttpHeaders.Origin]?.trim()
                    if (!origin.isNullOrEmpty()) {
                        if (!isAllowedOrigin(origin)) {
                            call.respondText(
                                text = json.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse("Forbidden origin")),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.Forbidden,
                            )
                            finish()
                            return@intercept
                        }
                        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
                        call.response.header(HttpHeaders.AccessControlAllowHeaders, "Authorization, Content-Type")
                        call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, PUT, PATCH, DELETE, OPTIONS")
                        call.response.header(HttpHeaders.Vary, "Origin")
                    }
                    if (call.request.httpMethod == HttpMethod.Options) {
                        call.respond(HttpStatusCode.NoContent)
                        finish()
                        return@intercept
                    }

                    val normalizedSegments = normalizeRequestPathSegments(call.request.path())
                    if (normalizedSegments == null) {
                        call.respondText(
                            text = json.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse("Invalid request path")),
                            contentType = ContentType.Application.Json,
                            status = HttpStatusCode.BadRequest,
                        )
                        finish()
                        return@intercept
                    }
                    val firstRawSegment = call.request.path()
                        .split('/')
                        .firstOrNull { it.isNotEmpty() }
                        ?.let { runCatching { it.decodeURLPart() }.getOrNull() }
                    val isApiCall = normalizedSegments.firstOrNull()?.equals("api", ignoreCase = true) == true ||
                        firstRawSegment?.equals("api", ignoreCase = true) == true
                    if (isApiCall) {
                        val isSseEventsEndpoint = call.request.httpMethod == HttpMethod.Get &&
                            normalizedSegments.size == 3 &&
                            normalizedSegments[1].equals("v1", ignoreCase = true) &&
                            normalizedSegments[2].equals("events", ignoreCase = true)
                        val sseAuthorized = isSseEventsEndpoint && sseTickets.consume(call.request.queryParameters["ticket"])
                        val authorized = isAuthorized(call) || sseAuthorized
                        if (!authorized) {
                            call.respondText(
                                text = json.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse("Unauthorized")),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.Unauthorized,
                            )
                            finish()
                            return@intercept
                        }
                        if (sseAuthorized) {
                            call.attributes.put(SSE_AUTHORIZED_KEY, true)
                        }
                    }

                    try {
                        proceed()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (err: BadRequestException) {
                        if (!call.response.isCommitted) {
                            call.respondText(
                                text = json.encodeToString(
                                    ApiErrorResponse.serializer(),
                                    ApiErrorResponse(err.message ?: "Invalid request body"),
                                ),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        finish()
                    } catch (err: ContentTransformationException) {
                        if (!call.response.isCommitted) {
                            call.respondText(
                                text = json.encodeToString(
                                    ApiErrorResponse.serializer(),
                                    ApiErrorResponse(err.message ?: "Malformed JSON payload"),
                                ),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        finish()
                    } catch (err: SerializationException) {
                        if (!call.response.isCommitted) {
                            call.respondText(
                                text = json.encodeToString(
                                    ApiErrorResponse.serializer(),
                                    ApiErrorResponse(err.message ?: "Invalid JSON payload"),
                                ),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        finish()
                    }
                }
                val apiAuthPlugin = createRouteScopedPlugin("SuperPiApiAuth") {
                    onCall { call ->
                        val authorized = isAuthorized(call) || (call.attributes.getOrNull(SSE_AUTHORIZED_KEY) == true)
                        if (!authorized) {
                            call.respondText(
                                text = json.encodeToString(ApiErrorResponse.serializer(), ApiErrorResponse("Unauthorized")),
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.Unauthorized,
                            )
                            return@onCall
                        }
                    }
                }
                routing {
                    route("/api") {
                        // Defense-in-depth: unconditionally authenticate every request Ktor routes into /api.
                        install(apiAuthPlugin)

                        route("/v1/events") {
                            installSseEventRoute(controller, this@LocalControlServer.json, activeScope)
                        }

                        get("/v1/health") {
                            call.respond(HealthResponse())
                        }
                        get("/v1/status") {
                            call.respond(buildStatus())
                        }
                        get("/v1/relay/status") {
                            val account = controller.relayStore.read()
                            call.respond(
                                RelayStatusDto(
                                    enrolled = account != null,
                                    state = if (controller.isRelayLiveInitialized) controller.relayLive.state.value.name else "Disabled",
                                    origin = account?.origin,
                                    deviceId = account?.deviceId,
                                    deviceName = account?.deviceName,
                                    allowRemote = controller.relayStore.readAllowRemote(),
                                ),
                            )
                        }
                        post("/v1/relay/pair-code") {
                            val outcome = issuePairCode(controller.relayController)
                            outcome.fold(
                                onSuccess = { resp -> call.respond(resp) },
                                onFailure = { err ->
                                    call.respond(
                                        HttpStatusCode.BadRequest,
                                        ApiErrorResponse(err.message ?: "Failed to generate pair code"),
                                    )
                                },
                            )
                        }
                        post("/v1/reload") {
                            controller.reloadAll()
                            call.respond(HealthResponse(ok = true))
                        }
                        post("/v1/shutdown") {
                            call.respond(HealthResponse(ok = true))
                            controller.scope.launch { onShutdownRequested() }
                        }

                        installBotRoutes(controller)
                        installRemoteDeviceRoutes(controller)
                        installWorkspaceFileRoutes(controller)
                        installWorktreeAndCheckpointRoutes(controller)
                        installTerminalAndMiniappRoutes(controller, activeScope)
                        installSessionRoutes(controller, sseTickets)
                        installMemoryRoutes(controller)
                        installProviderExtensionRoutes(controller, activeScope)
                        installSkillMcpTimerRoutes(controller, activeScope)
                        installSuperMemoryAndSettingsRoutes(controller, activeScope)
                    }

                    installWebStaticRoutes()
                }
            }
            created.start(wait = false)
            val boundPort = created.engine.resolvedConnectors().first().port
            synchronized(lifecycleLock) {
                server = created
                serverScope = activeScope
                actualPort = boundPort
                writeLockFile(boundPort)
            }
            return boundPort
        } catch (err: Throwable) {
            runCatching { activeScope.cancel() }
            runCatching { created?.stop(100, 500) }
            releaseInstanceLock()
            throw err
        }
    }

    fun stop() {
        val (activeServer, activeScope) = synchronized(lifecycleLock) {
            val existing = server
            val existingScope = serverScope
            server = null
            serverScope = null
            actualPort = 0
            existing to existingScope
        }
        runCatching { activeScope?.cancel() }
        runCatching { activeServer?.stop(250, 1_000) }
        deleteOwnLockFile()
        releaseInstanceLock()
    }

    private suspend fun buildStatus(): StatusResponse {
        val account = controller.relayStore.read()
        val relayState: RelayLiveState = controller.relayLive.state.value
        val bots = controller.botRegistry.listAllBots()
        val mcpCount = runCatching { controller.mcpConfigStore.load().servers.size }.getOrDefault(0)
        val memOverview = runCatching { controller.memoryStore.overview() }.getOrNull()
        val activeTimers = runCatching { controller.timerService.timers.value.count { it.enabled } }.getOrDefault(0)
        return StatusResponse(
            version = AppBrand.VERSION,
            homeDir = controller.paths.homeDir.absolutePath,
            pid = ProcessHandle.current().pid(),
            port = actualPort,
            relayState = relayState.name,
            relayOrigin = account?.origin,
            relayDeviceId = account?.deviceId,
            relayDeviceName = account?.deviceName,
            botsCount = bots.size,
            busySessionsCount = controller.runtimeHost.busySessionIds.value.size,
            mcpServersCount = mcpCount,
            memoryEntities = memOverview?.entityCount ?: 0,
            memoryEvents = memOverview?.activeEvents ?: 0,
            activeTimersCount = activeTimers,
        )
    }

    private suspend fun issuePairCode(relayController: RelayController): Result<PairCodeResponse> = pairMutex.withLock {
        if (controller.relayStore.read() == null) {
            return@withLock Result.failure(IllegalStateException("Not paired with a Relay server yet."))
        }
        val hydrated = withTimeoutOrNull(2_000L) {
            relayController.state.first { it.account != null }
        }
        if (hydrated == null) {
            return@withLock Result.failure(IllegalStateException("Not paired with a Relay server yet."))
        }
        relayController.dismissPairCode()
        relayController.generatePairCode()
        val settled = withTimeoutOrNull(15_000L) {
            relayController.state.first { !it.generatingPairCode }
        } ?: return@withLock Result.failure(IllegalStateException("Timed out waiting for pair code."))
        val link = settled.pairLink
        return@withLock if (link != null) {
            Result.success(PairCodeResponse(link = link, expiresAt = settled.pairExpiresAt))
        } else {
            val msg = when (settled.error) {
                RelayError.Unreachable -> "Relay server unreachable"
                RelayError.Unauthorized -> "Relay device unauthorized"
                else -> settled.error?.name ?: "Failed to issue pair code"
            }
            Result.failure(IllegalStateException(msg))
        }
    }

    private fun isAuthorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization]?.trim().orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val provided = header.substring(7).trim()
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            bearerToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun acquireInstanceLock() {
        val target = lockFile ?: return
        val parent = target.parentFile ?: File(".")
        if (!parent.exists()) parent.mkdirs()
        val instanceFile = File(parent, "${target.name}.instance")
        val ch = FileChannel.open(
            instanceFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val acquired = runCatching { ch.tryLock() }.getOrNull()
        if (acquired == null) {
            runCatching { ch.close() }
            throw IllegalStateException("Another ${AppBrand.CLI_NAME} serve daemon is already running for ${controller.paths.homeDir.absolutePath}")
        }
        instanceLockChannel = ch
        instanceLock = acquired
    }

    private fun releaseInstanceLock() {
        synchronized(lifecycleLock) {
            runCatching { instanceLock?.release() }
            runCatching { instanceLockChannel?.close() }
            instanceLock = null
            instanceLockChannel = null
        }
    }

    private fun deleteOwnLockFile() {
        val target = lockFile ?: return
        if (!target.isFile) return
        val currentPid = ProcessHandle.current().pid()
        val matchesOwn = runCatching {
            val parsed = json.decodeFromString(ServeLockInfo.serializer(), target.readText(Charsets.UTF_8))
            parsed.pid == currentPid && parsed.token == bearerToken
        }.getOrDefault(false)
        if (matchesOwn) {
            runCatching { target.delete() }
        }
    }

    private fun writeLockFile(port: Int) {
        val target = lockFile ?: return
        val parent = target.parentFile ?: File(".")
        if (!parent.exists()) parent.mkdirs()
        val info = ServeLockInfo(
            pid = ProcessHandle.current().pid(),
            port = port,
            token = bearerToken,
            startedAt = System.currentTimeMillis(),
        )
        val tempPath = try {
            Files.createTempFile(
                parent.toPath(),
                ".${target.name}.",
                ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
        } catch (_: UnsupportedOperationException) {
            Files.createTempFile(parent.toPath(), ".${target.name}.", ".tmp")
        }
        val temp = tempPath.toFile()
        runCatching {
            Files.setPosixFilePermissions(tempPath, PosixFilePermissions.fromString("rw-------"))
        }
        try {
            temp.writeText(json.encodeToString(ServeLockInfo.serializer(), info) + "\n", Charsets.UTF_8)
            try {
                Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tempPath) }
        }
        runCatching {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        private val SSE_AUTHORIZED_KEY = io.ktor.util.AttributeKey<Boolean>("superpi.sseAuthorized")

        private val ALLOWED_ORIGIN_REGEX = Regex(
            pattern = """^(https?://(127\.0\.0\.1|localhost)(:\d{1,5})?|superpi://app)$""",
            option = RegexOption.IGNORE_CASE,
        )

        private val ALLOWED_HOST_REGEX = Regex(
            pattern = """^(127\.0\.0\.1|localhost|\[::1])(:\d{1,5})?$""",
            option = RegexOption.IGNORE_CASE,
        )

        internal fun isAllowedOrigin(origin: String): Boolean = ALLOWED_ORIGIN_REGEX.matches(origin.trim())

        internal fun isAllowedHost(host: String): Boolean = ALLOWED_HOST_REGEX.matches(host.trim())

        internal fun normalizeRequestPathSegments(rawPath: String): List<String>? {
            if (rawPath.contains('\\') || rawPath.contains('\u0000')) return null
            val segments = ArrayList<String>()
            for (rawPart in rawPath.split('/')) {
                if (rawPart.isEmpty()) continue
                val decoded = try {
                    rawPart.decodeURLPart()
                } catch (_: Throwable) {
                    return null
                }
                if (decoded.contains('/') ||
                    decoded.contains('\\') ||
                    decoded.contains('\u0000') ||
                    decoded == "." ||
                    decoded == ".."
                ) {
                    return null
                }
                segments.add(decoded)
            }
            return segments
        }
    }
}

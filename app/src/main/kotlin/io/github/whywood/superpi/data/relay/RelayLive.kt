package io.github.whywood.superpi.data.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.whywood.superpi.data.AgentDocStore
import io.github.whywood.superpi.data.WorkspaceToolPolicyStore
import io.github.whywood.superpi.runtime.AgentRuntime
import io.github.whywood.superpi.runtime.ProviderDirectory
import io.github.whywood.superpi.runtime.QuestionCenter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class RelayLiveState {
    Disabled,
    Connecting,
    Online,
    Offline,
    Unauthorized,
}

sealed interface RemoteSessionsOutcome {
    data class Ok(val sessions: List<RemoteSessionSummaryDto>) : RemoteSessionsOutcome

    data object RelayUpgradeRequired : RemoteSessionsOutcome

    data object PeerUpgradeRequired : RemoteSessionsOutcome

    data class Failed(val error: RemoteCallError) : RemoteSessionsOutcome
}

sealed interface RemoteHistoryOutcome {
    data class Ok(val history: RemoteHistoryDto) : RemoteHistoryOutcome

    data object RelayUpgradeRequired : RemoteHistoryOutcome

    data object PeerUpgradeRequired : RemoteHistoryOutcome

    data class Failed(val error: RemoteCallError) : RemoteHistoryOutcome
}

class RelayLive(
    private val scope: CoroutineScope,
    private val accounts: RelayStore,
    private val host: AgentRuntime,
    private val memorySync: RelayMemorySync,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    questionCenter: QuestionCenter? = null,
    avatarStorageDir: File? = null,
    providerCatalog: () -> ProviderDirectory? = { null },
    configuredProviderIds: () -> Set<String> = { emptySet() },
    agentDocStore: () -> AgentDocStore? = { null },
    slashCommands: suspend (String) -> List<RemoteCommandSpec> = { emptyList() },
    workspaceToolPolicies: () -> WorkspaceToolPolicyStore? = { null },
    installedCapabilities: suspend (String) -> RemoteCapabilitiesInventory = { RemoteCapabilitiesInventory() },
    expandSkillPrompt: suspend (String, String) -> String = { _, text -> text },
) {
    private val socket = RelaySocket(scope, io)
    private val mutex = Mutex()
    private val devicesMutex = Mutex()
    private val calls = RemoteCallTracker()
    private val avatarCacheLock = Any()
    private val avatarCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_AVATAR_CACHE
    }
    private val server = RemoteAgentServer(
        host = host,
        bots = ::bots,
        send = ::send,
        scope = scope,
        questionCenter = questionCenter,
        onBotsChanged = { refreshBots() },
        onSyncEvent = ::emitSyncEvent,
        avatarStorageDir = avatarStorageDir,
        providerCatalog = providerCatalog,
        configuredProviderIds = configuredProviderIds,
        agentDocStore = agentDocStore,
        slashCommands = slashCommands,
        workspaceToolPolicies = workspaceToolPolicies,
        installedCapabilities = installedCapabilities,
        expandSkillPrompt = expandSkillPrompt,
    )

    private val _state = MutableStateFlow(RelayLiveState.Disabled)
    val state: StateFlow<RelayLiveState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<RelayDevice>>(emptyList())
    val devices: StateFlow<List<RelayDevice>> = _devices.asStateFlow()

    private val _relayCapabilities = MutableStateFlow<List<String>>(emptyList())
    val relayCapabilities: StateFlow<List<String>> = _relayCapabilities.asStateFlow()

    val remoteBots: StateFlow<List<RemoteBot>> = combine(_devices, _state) { devices, state ->
        val linked = state == RelayLiveState.Online
        devices.flatMap { device ->
            device.bots.map { bot ->
                RemoteBot(
                    deviceId = device.deviceId,
                    deviceName = device.name,
                    botId = bot.id,
                    botName = bot.name,
                    online = linked && device.online,
                    avatar = bot.avatar,
                    capabilities = device.capabilities,
                )
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _allowRemote = MutableStateFlow(false)
    val allowRemote: StateFlow<Boolean> = _allowRemote.asStateFlow()

    private val _remoteEvents = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = REMOTE_EVENT_BUFFER)
    val remoteEvents: SharedFlow<RemoteEvent> = _remoteEvents.asSharedFlow()

    private val _syncEvents = MutableSharedFlow<RelaySyncEvent>(extraBufferCapacity = SYNC_EVENT_BUFFER)
    val syncEvents: SharedFlow<RelaySyncEvent> = _syncEvents.asSharedFlow()

    private val _lastCallError = MutableStateFlow<RemoteCallError?>(null)
    val lastCallError: StateFlow<RemoteCallError?> = _lastCallError.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var pendingOnline = false

    @Volatile
    private var helloApplied = false

    private val runEpoch = AtomicLong(0L)

    @Volatile
    private var account: RelayAccount? = null

    private var collectorJob: Job? = null

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
            runEpoch.incrementAndGet()
            pendingOnline = false
            helloApplied = false
            collectorJob?.cancel()
            val parent = SupervisorJob(scope.coroutineContext[Job])
            collectorJob = parent
            val collectorScope = CoroutineScope(scope.coroutineContext + parent)
            collectorScope.launch {
                val cached = runCatching { accounts.readDevices() }.getOrDefault(emptyList())
                devicesMutex.withLock {
                    if (_devices.value.isEmpty() && cached.isNotEmpty() && started && account != null) {
                        _devices.value = cached
                    }
                }
                launchCollectors(collectorScope)
            }
        }
    }

    private fun launchCollectors(collectorScope: CoroutineScope) {
        collectorScope.launch {
            accounts.account.collect { value ->
                val previous = account
                account = value
                if (value == null && previous != null) {
                    devicesMutex.withLock {
                        _devices.value = emptyList()
                    }
                    runCatching { accounts.saveDevices(emptyList()) }
                }
                reconcile()
            }
        }
        collectorScope.launch { accounts.allowRemote.collect { value -> _allowRemote.value = value; reconcile() } }
        collectorScope.launch { socket.state.collect { value -> onSocketState(value) } }
        collectorScope.launch { socket.frames.collect { frame -> onFrame(frame) } }
    }

    fun stop() {
        synchronized(this) {
            started = false
            runEpoch.incrementAndGet()
            pendingOnline = false
            helloApplied = false
            collectorJob?.cancel()
            collectorJob = null
        }
        socket.stop()
        server.reset()
        calls.failAll(RemoteCallError(RemoteCallCodes.DISCONNECTED, "relay stopped"))
        _relayCapabilities.value = emptyList()
        _devices.value = emptyList()
        _state.value = RelayLiveState.Disabled
    }

    fun reconnectNow(forceCycle: Boolean = false) {
        if (!started || account == null) return
        socket.reconnectNow(forceCycle = forceCycle)
    }

    fun refreshBots() {
        if (!started || account == null) return
        scope.launch { reconcile() }
    }

    fun removeDevice(deviceId: String) {
        scope.launch {
            devicesMutex.withLock {
                val updated = _devices.value.filterNot { it.deviceId == deviceId }
                if (updated.size == _devices.value.size) return@withLock
                _devices.value = updated
                persistDevicesLocked(updated)
            }
        }
    }

    fun setAllowRemote(enabled: Boolean) {
        scope.launch { accounts.setAllowRemote(enabled) }
    }

    fun sendRoute(to: String, method: String, params: JsonObject): Boolean =
        send(RelayFrames.route(routeId(), to, method, params))

    fun supportsCapability(deviceId: String, capability: String): Boolean {
        if (!_relayCapabilities.value.contains(capability)) return false
        val peer = _devices.value.firstOrNull { it.deviceId == deviceId }
        if (capability !in LEGACY_CAPABILITIES) {
            return peer != null && peer.capabilities.contains(capability)
        }
        if (peer == null) return true
        return peer.capabilities.isEmpty() || peer.capabilities.contains(capability)
    }

    fun supportsPeerCapability(deviceId: String, capability: String): Boolean {
        if (!_relayCapabilities.value.contains(capability)) return false
        val peer = _devices.value.firstOrNull { it.deviceId == deviceId } ?: return false
        return peer.capabilities.contains(capability)
    }

    fun emitSyncEvent(event: RelaySyncEvent) {
        if (!started || socket.state.value != RelaySocketState.Online) return
        if (!_relayCapabilities.value.contains(CAPABILITY_SYNC_EVENTS)) return
        send(
            RelayFrames.syncEvent(
                kind = event.kind,
                botId = event.botId,
                sessionId = event.sessionId,
                busy = event.busy,
                pendingApprovals = event.pendingApprovals,
                provider = event.provider,
                modelId = event.modelId,
                thinkingLevel = event.thinkingLevel,
                ts = event.ts,
            ),
        )
    }

    suspend fun listRemoteSessions(
        deviceId: String,
        botId: String,
        includeArchived: Boolean = false,
    ): RemoteSessionsOutcome {
        when (val check = checkSessionBrowseSupport(deviceId)) {
            SessionBrowseCheck.Ok -> Unit
            SessionBrowseCheck.RelayUpgrade -> return RemoteSessionsOutcome.RelayUpgradeRequired
            SessionBrowseCheck.PeerUpgrade -> return RemoteSessionsOutcome.PeerUpgradeRequired
            is SessionBrowseCheck.Failed -> return RemoteSessionsOutcome.Failed(check.error)
        }
        val params = buildJsonObject {
            put("botId", botId)
            if (includeArchived) put("includeArchived", true)
        }
        return when (val outcome = callWithOutcome(deviceId, BOT_SESSIONS, params)) {
            is RemoteCallOutcome.Ok -> RemoteSessionsOutcome.Ok(RelayFrames.parseRemoteSessions(outcome.result))
            is RemoteCallOutcome.Failed -> {
                if (isUnsupportedMethodError(outcome.error)) {
                    RemoteSessionsOutcome.PeerUpgradeRequired
                } else {
                    RemoteSessionsOutcome.Failed(outcome.error)
                }
            }
        }
    }

    suspend fun getRemoteHistory(deviceId: String, botId: String, sessionId: String): RemoteHistoryOutcome {
        when (val check = checkSessionBrowseSupport(deviceId)) {
            SessionBrowseCheck.Ok -> Unit
            SessionBrowseCheck.RelayUpgrade -> return RemoteHistoryOutcome.RelayUpgradeRequired
            SessionBrowseCheck.PeerUpgrade -> return RemoteHistoryOutcome.PeerUpgradeRequired
            is SessionBrowseCheck.Failed -> return RemoteHistoryOutcome.Failed(check.error)
        }
        val params = buildJsonObject {
            put("botId", botId)
            put("sessionId", sessionId)
        }
        return when (val outcome = callWithOutcome(deviceId, BOT_HISTORY, params)) {
            is RemoteCallOutcome.Ok -> {
                val parsed = RelayFrames.parseRemoteHistory(outcome.result)
                if (parsed != null) {
                    RemoteHistoryOutcome.Ok(parsed)
                } else {
                    RemoteHistoryOutcome.Failed(RemoteCallError("bad_response", "invalid session history response"))
                }
            }

            is RemoteCallOutcome.Failed -> {
                if (isUnsupportedMethodError(outcome.error)) {
                    RemoteHistoryOutcome.PeerUpgradeRequired
                } else {
                    RemoteHistoryOutcome.Failed(outcome.error)
                }
            }
        }
    }

    suspend fun call(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
        onRouteId: (String) -> Unit = {},
    ): JsonObject? {
        return when (val outcome = callWithOutcome(to, method, params, timeoutMillis, onRouteId)) {
            is RemoteCallOutcome.Ok -> {
                _lastCallError.value = null
                outcome.result
            }

            is RemoteCallOutcome.Failed -> {
                _lastCallError.value = outcome.error
                null
            }
        }
    }

    suspend fun callWithOutcome(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
        onRouteId: (String) -> Unit = {},
    ): RemoteCallOutcome {
        if (socket.state.value != RelaySocketState.Online) {
            val error = RemoteCallError(RemoteCallCodes.NOT_CONNECTED, "relay is not connected")
            _lastCallError.value = error
            return RemoteCallOutcome.Failed(error)
        }
        val id = routeId()
        onRouteId(id)
        val pending = calls.open(id)
        if (!send(RelayFrames.route(id, to, method, params))) {
            calls.settle(
                id,
                RemoteCallOutcome.Failed(RemoteCallError(RemoteCallCodes.NOT_CONNECTED, "relay is not connected")),
            )
        }
        val completed = try {
            withTimeoutOrNull(timeoutMillis) { pending.await() }
        } catch (cancelled: CancellationException) {
            calls.settle(id, RemoteCallOutcome.Failed(RemoteCallError(RemoteCallCodes.DISCONNECTED, "call cancelled")))
            throw cancelled
        }
        val outcome = completed
            ?: RemoteCallOutcome.Failed(RemoteCallError(RemoteCallCodes.TIMEOUT, "the device did not answer"))
        calls.settle(id, outcome)
        when (outcome) {
            is RemoteCallOutcome.Ok -> _lastCallError.value = null
            is RemoteCallOutcome.Failed -> _lastCallError.value = outcome.error
        }
        return outcome
    }

    private fun checkSessionBrowseSupport(deviceId: String): SessionBrowseCheck {
        if (socket.state.value != RelaySocketState.Online) {
            return SessionBrowseCheck.Failed(RemoteCallError(RemoteCallCodes.NOT_CONNECTED, "relay is not connected"))
        }
        if (!_relayCapabilities.value.contains(CAPABILITY_BOT_SESSIONS)) {
            return SessionBrowseCheck.RelayUpgrade
        }
        val peer = _devices.value.firstOrNull { it.deviceId == deviceId }
        if (peer != null && !peer.online) {
            return SessionBrowseCheck.Failed(RemoteCallError(RemoteCallCodes.NOT_CONNECTED, "device is offline"))
        }
        if (peer != null && peer.capabilities.isNotEmpty() && !peer.capabilities.contains(CAPABILITY_BOT_SESSIONS)) {
            return SessionBrowseCheck.PeerUpgrade
        }
        return SessionBrowseCheck.Ok
    }

    private fun isUnsupportedMethodError(error: RemoteCallError): Boolean =
        error.code == "bad_request" && error.message.contains("unknown method", ignoreCase = true)

    private sealed interface SessionBrowseCheck {
        data object Ok : SessionBrowseCheck
        data object RelayUpgrade : SessionBrowseCheck
        data object PeerUpgrade : SessionBrowseCheck
        data class Failed(val error: RemoteCallError) : SessionBrowseCheck
    }

    private fun mergePresenceLocked(frame: RelayServerFrame.Presence, existing: List<RelayDevice>): List<RelayDevice> {
        val previous = existing.firstOrNull { it.deviceId == frame.deviceId }
        if (previous != null && frame.lastSeen > 0L && previous.lastSeen > frame.lastSeen) {
            return existing
        }
        val merged = RelayDevice(
            deviceId = frame.deviceId,
            name = previous?.name ?: frame.deviceId,
            platform = previous?.platform.orEmpty(),
            online = frame.online,
            lastSeen = frame.lastSeen,
            bots = if (frame.online) frame.bots ?: previous?.bots.orEmpty() else previous?.bots.orEmpty(),
            capabilities = frame.capabilities ?: previous?.capabilities.orEmpty(),
        )
        return existing.filterNot { it.deviceId == frame.deviceId } + merged
    }

    private fun mergeDevices(incoming: List<RelayDevice>, previous: List<RelayDevice>): List<RelayDevice> =
        incoming.map { device ->
            val cached = previous.firstOrNull { it.deviceId == device.deviceId }
            if (cached == null) {
                device
            } else {
                device.copy(
                    name = device.name.takeIf { it != device.deviceId } ?: cached.name,
                    platform = device.platform.ifEmpty { cached.platform },
                    bots = if (device.online) device.bots else cached.bots.ifEmpty { device.bots },
                    capabilities = if (device.capabilities.isNotEmpty()) device.capabilities else cached.capabilities,
                )
            }
        }

    private fun persistDevicesLocked(snapshot: List<RelayDevice>) {
        val expectedEpoch = runEpoch.get()
        val expectedAccountId = account?.accountId ?: return
        scope.launch {
            devicesMutex.withLock {
                if (!started || runEpoch.get() != expectedEpoch || account?.accountId != expectedAccountId) {
                    return@withLock
                }
                runCatching { accounts.saveDevices(snapshot) }
            }
        }
    }

    private suspend fun bots(): List<RelayBot> = runCatching {
        host.workspaces()
            .filter { workspace -> workspace.remote == null }
            .map { workspace -> RelayBot(workspace.id, workspace.name, avatar = encodeBotAvatar(workspace.avatar)) }
    }.getOrDefault(emptyList())

    private fun encodeBotAvatar(path: String?): String? {
        val raw = path?.takeIf { it.isNotBlank() } ?: return null
        if (raw.startsWith("data:image/") && raw.contains(";base64,")) {
            return raw.takeIf { it.length <= MAX_AVATAR_DATA_URL_CHARS }
        }
        val file = File(raw)
        if (!file.isFile) return null
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        synchronized(avatarCacheLock) {
            avatarCache[cacheKey]?.let { return it }
        }
        val encoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options()
            if (maxDim > MAX_AVATAR_PX * 2) {
                var sample = 1
                while (maxDim / (sample * 2) >= MAX_AVATAR_PX) sample *= 2
                options.inSampleSize = sample
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                val squareSize = minOf(decoded.width, decoded.height).coerceAtLeast(1)
                val x = (decoded.width - squareSize) / 2
                val y = (decoded.height - squareSize) / 2
                cropped = if (decoded.width == decoded.height) {
                    decoded
                } else {
                    Bitmap.createBitmap(decoded, x, y, squareSize, squareSize)
                }
                scaled = if (cropped.width > MAX_AVATAR_PX) {
                    Bitmap.createScaledBitmap(cropped, MAX_AVATAR_PX, MAX_AVATAR_PX, true)
                } else {
                    cropped
                }
                for (quality in intArrayOf(80, 65)) {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val candidate = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
                    if (candidate.length <= MAX_AVATAR_DATA_URL_CHARS) return@runCatching candidate
                }
                null
            } finally {
                if (scaled != null && scaled !== cropped && scaled !== decoded) scaled.recycle()
                if (cropped != null && cropped !== decoded) cropped.recycle()
                decoded.recycle()
            }
        }.getOrNull()
        val resolved = encoded ?: if (file.length() in 1L..44_000L) {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
            val mime = RelayFrames.detectImageMime(bytes)
            if (mime != null && mime != "image/gif") {
                val fallback = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
                fallback.takeIf { it.length <= MAX_AVATAR_DATA_URL_CHARS }
            } else {
                null
            }
        } else {
            null
        }
        if (resolved != null) {
            synchronized(avatarCacheLock) {
                avatarCache[cacheKey] = resolved
            }
        }
        return resolved
    }

    private fun send(frame: JsonObject): Boolean = socket.send(frame.toString())

    private fun routeId(): String = "pr-${System.currentTimeMillis().toString(36)}-${(0..0xFFFFFF).random().toString(36)}"

    private suspend fun reconcile() {
        val expectedEpoch = runEpoch.get()
        mutex.withLock {
            if (!started || runEpoch.get() != expectedEpoch) return
            val current = account
            val token = accounts.token()
            if (!started || runEpoch.get() != expectedEpoch || current == null || token == null) {
                socket.stop()
                server.reset()
                _relayCapabilities.value = emptyList()
                pendingOnline = false
                helloApplied = false
                _state.value = RelayLiveState.Disabled
                return
            }
            if (socket.state.value == RelaySocketState.Online) {
                resendHelloLocked(current)
                return
            }
            socket.start(current, token) {
                helloFrame(current).toString()
            }
        }
    }

    private suspend fun helloFrame(current: RelayAccount): JsonObject = RelayFrames.hello(
        deviceId = current.deviceId,
        platform = "android",
        appVersion = appVersion,
        bots = if (_allowRemote.value) bots() else emptyList(),
        capabilities = if (_allowRemote.value) RELAY_CAPABILITIES else RELAY_CAPABILITIES_READONLY,
    )

    private suspend fun resendHelloLocked(current: RelayAccount) {
        socket.send(helloFrame(current).toString())
    }

    private fun onSocketState(value: RelaySocketState) {
        if (value == RelaySocketState.Online) {
            pendingOnline = true
            if (helloApplied && started && account != null) {
                _state.value = RelayLiveState.Online
            }
            return
        }
        pendingOnline = false
        helloApplied = false
        _relayCapabilities.value = emptyList()
        calls.failAll(RemoteCallError(RemoteCallCodes.DISCONNECTED, "relay disconnected"))
        if (value == RelaySocketState.Unauthorized || value == RelaySocketState.Stopped) {
            server.reset()
        }
        _state.value = when (value) {
            RelaySocketState.Stopped -> if (!started || account == null) {
                RelayLiveState.Disabled
            } else {
                RelayLiveState.Offline
            }

            RelaySocketState.Connecting -> RelayLiveState.Connecting
            RelaySocketState.Online -> RelayLiveState.Online
            RelaySocketState.Offline -> RelayLiveState.Offline
            RelaySocketState.Unauthorized -> RelayLiveState.Unauthorized
        }
    }

    private suspend fun onFrame(frame: RelayServerFrame) {
        when (frame) {
            is RelayServerFrame.HelloOk -> {
                _relayCapabilities.value = frame.capabilities
                val self = account?.deviceId
                devicesMutex.withLock {
                    val updated = mergeDevices(
                        incoming = frame.devices.filter { device -> device.deviceId != self },
                        previous = _devices.value,
                    )
                    _devices.value = updated
                    persistDevicesLocked(updated)
                }
                helloApplied = true
                if (pendingOnline || socket.state.value == RelaySocketState.Online) {
                    if (started && account != null) {
                        _state.value = RelayLiveState.Online
                    }
                }
                scope.launch(io) { runCatching { memorySync.refreshIfStale(0) } }
            }

            is RelayServerFrame.Presence -> {
                val self = account?.deviceId
                if (frame.deviceId == self) return
                devicesMutex.withLock {
                    val existing = _devices.value
                    val updated = mergePresenceLocked(frame, existing)
                    if (updated !== existing) {
                        _devices.value = updated
                        persistDevicesLocked(updated)
                    }
                }
            }

            is RelayServerFrame.MemoryChanged -> scope.launch(io) { runCatching { memorySync.refreshIfStale(0) } }
            is RelayServerFrame.SyncEvent -> {
                val syncEvent = frame.toEvent()
                if (!_syncEvents.tryEmit(syncEvent)) {
                    scope.launch { _syncEvents.emit(syncEvent) }
                }
            }
            is RelayServerFrame.Route -> if (_allowRemote.value) {
                scope.launch(io) { server.handle(frame) }
            } else {
                val code = if (frame.method.startsWith("session.") ||
                    frame.method.startsWith("file.") ||
                    frame.method.startsWith("workspace.") ||
                    frame.method == "bot.create" ||
                    frame.method == "bot.update" ||
                    frame.method == "bot.delete" ||
                    frame.method == "bot.models" ||
                    frame.method == "bot.set_model" ||
                    frame.method == "bot.set_thinking" ||
                    frame.method == "bot.commands" ||
                    frame.method == "bot.instructions.get" ||
                    frame.method == "bot.instructions.set" ||
                    frame.method == "bot.capabilities.get" ||
                    frame.method == "bot.capabilities.set" ||
                    frame.method == "artifact.read_chunk" ||
                    frame.method == "bot.reload"
                ) {
                    "permission_denied"
                } else {
                    "bot_not_found"
                }
                send(RelayFrames.routeError(frame.id, code, "remote use is disabled on this device"))
            }
            is RelayServerFrame.RouteOk -> calls.settle(frame.id, RemoteCallOutcome.Ok(frame.result))
            is RelayServerFrame.RouteError -> calls.settle(
                frame.id,
                RemoteCallOutcome.Failed(RemoteCallError(frame.code, frame.message)),
            )

            is RelayServerFrame.RouteEvent -> {
                val event = RemoteEvent(routeId = frame.id, seq = frame.seq, event = frame.event)
                if (!_remoteEvents.tryEmit(event)) {
                    scope.launch { _remoteEvents.emit(event) }
                }
            }

            else -> Unit
        }
    }

    private companion object {
        private const val CALL_TIMEOUT_MILLIS = 15_000L
        private const val REMOTE_EVENT_BUFFER = 2_048
        private const val SYNC_EVENT_BUFFER = 256
        private const val MAX_AVATAR_PX = 128
        private const val MAX_AVATAR_DATA_URL_CHARS = 60_000
        private const val MAX_AVATAR_CACHE = 32
        private const val CAPABILITY_BOT_SESSIONS = "bot-sessions"
        private const val CAPABILITY_SYNC_EVENTS = "sync-events"
        private val LEGACY_CAPABILITIES = setOf("remote-bot", "bot-sessions", "bot-images", "bot-approval")
        private const val BOT_SESSIONS = "bot.sessions"
        private const val BOT_HISTORY = "bot.history"
    }
}

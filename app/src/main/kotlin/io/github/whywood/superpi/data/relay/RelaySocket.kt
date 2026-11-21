package io.github.whywood.superpi.data.relay

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class RelaySocketState {
    Stopped,
    Connecting,
    Online,
    Offline,
    Unauthorized,
}

class RelaySocket(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoffSchedule: LongArray = BACKOFF,
) {
    private val _state = MutableStateFlow(RelaySocketState.Stopped)
    val state: StateFlow<RelaySocketState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<RelayServerFrame>(extraBufferCapacity = FRAME_BUFFER_CAPACITY)
    val frames: SharedFlow<RelayServerFrame> = _frames.asSharedFlow()

    private val lifecycleLock = Any()
    private var job: Job? = null
    private val generation = AtomicLong(0L)

    @Volatile
    private var attemptJob: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var running = false

    @Volatile
    private var retryWake: CompletableDeferred<Unit>? = null

    fun start(
        account: RelayAccount,
        token: String,
        hello: suspend () -> String?,
    ) {
        synchronized(lifecycleLock) {
            if (running) return
            running = true
            job = scope.launch(io) { connectLoop(account, token) { hello() } }
        }
    }

    fun stop() {
        val activeSocket: WebSocket?
        val wasOnline: Boolean
        val currentClient: OkHttpClient?
        val currentJob: Job?
        val currentAttemptJob: Job?
        val wake: CompletableDeferred<Unit>?
        synchronized(lifecycleLock) {
            running = false
            generation.incrementAndGet()
            wake = retryWake
            retryWake = null
            activeSocket = socket
            socket = null
            wasOnline = _state.value == RelaySocketState.Online
            currentClient = client
            client = null
            currentAttemptJob = attemptJob
            attemptJob = null
            currentJob = job
            job = null
        }
        wake?.complete(Unit)
        if (activeSocket != null) {
            if (wasOnline) {
                val closedCleanly = runCatching { activeSocket.close(1000, "closed") }.getOrDefault(false)
                if (!closedCleanly) runCatching { activeSocket.cancel() }
            } else {
                runCatching { activeSocket.cancel() }
            }
        }
        currentClient?.let(::shutdownClient)
        currentAttemptJob?.cancel()
        currentJob?.cancel()
        _state.value = RelaySocketState.Stopped
    }

    fun reconnectNow() {
        if (!running) return
        retryWake?.complete(Unit)
    }

    fun send(frame: String): Boolean = socket?.send(frame) ?: false

    private suspend fun connectLoop(
        account: RelayAccount,
        token: String,
        hello: suspend () -> String?,
    ) {
        var attempt = 0
        while (running) {
            _state.value = RelaySocketState.Connecting
            val activeGen = generation.incrementAndGet()
            val closed = CompletableDeferred<Boolean>()
            val helloOkReceived = AtomicBoolean(false)
            val attemptSupervisor = SupervisorJob(job)
            attemptJob = attemptSupervisor
            val attemptScope = CoroutineScope(io + attemptSupervisor)
            val inboundChannel = Channel<RelayServerFrame>(Channel.UNLIMITED)
            val frameForwarder = attemptScope.launch {
                for (frame in inboundChannel) {
                    _frames.emit(frame)
                }
            }
            val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
            val http = RelayTls.client(pin = pin, remote = true).newBuilder()
                .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build()
            if (!running || generation.get() != activeGen) {
                attemptSupervisor.cancel()
                shutdownClient(http)
                break
            }
            client = http
            val request = Request.Builder()
                .url(webSocketUrl(account.origin))
                .header("authorization", "Bearer $token")
                .build()
            lastFrameAt = System.currentTimeMillis()
            val helloTimeoutJob = AtomicReference<Job?>(null)
            val listener = object : WebSocketListener() {
                private fun isCurrent(webSocket: WebSocket): Boolean =
                    running && generation.get() == activeGen && (socket == null || socket === webSocket)

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!isCurrent(webSocket)) {
                        runCatching { webSocket.cancel() }
                        closed.complete(false)
                        return
                    }
                    socket = webSocket
                    lastFrameAt = System.currentTimeMillis()
                    val timeoutJob = attemptScope.launch {
                        delay(HELLO_TIMEOUT_MILLIS)
                        if (isCurrent(webSocket) && !helloOkReceived.get()) {
                            runCatching { webSocket.cancel() }
                            closed.complete(false)
                        }
                    }
                    helloTimeoutJob.getAndSet(timeoutJob)?.cancel()
                    attemptScope.launch {
                        val greeting = runCatching { hello() }.getOrNull()
                        if (greeting == null || !isCurrent(webSocket)) {
                            runCatching { webSocket.cancel() }
                            closed.complete(false)
                            return@launch
                        }
                        if (!webSocket.send(greeting)) {
                            runCatching { webSocket.cancel() }
                            closed.complete(false)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!isCurrent(webSocket)) return
                    lastFrameAt = System.currentTimeMillis()
                    val frame = RelayFrames.parse(text) ?: return
                    if (frame is RelayServerFrame.HelloOk && helloOkReceived.compareAndSet(false, true)) {
                        helloTimeoutJob.getAndSet(null)?.cancel()
                        attempt = 0
                        inboundChannel.trySend(frame)
                        if (isCurrent(webSocket)) {
                            _state.value = RelaySocketState.Online
                        }
                    } else {
                        inboundChannel.trySend(frame)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (generation.get() != activeGen) {
                        runCatching { webSocket.cancel() }
                        return
                    }
                    if (code == CLOSE_CODE_UNAUTHORIZED) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    runCatching { webSocket.close(code, reason) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    helloTimeoutJob.getAndSet(null)?.cancel()
                    if (generation.get() != activeGen) return
                    val code = response?.code
                    if (code == 401 || code == 403) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    closed.complete(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    helloTimeoutJob.getAndSet(null)?.cancel()
                    if (generation.get() != activeGen) return
                    if (code == CLOSE_CODE_UNAUTHORIZED) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    closed.complete(true)
                }
            }
            val active = http.newWebSocket(request, listener)
            if (running && generation.get() == activeGen) {
                socket = active
            } else {
                runCatching { active.cancel() }
                closed.complete(false)
            }
            try {
                closed.await()
            } finally {
                helloTimeoutJob.getAndSet(null)?.cancel()
                inboundChannel.close()
                frameForwarder.cancel()
                attemptSupervisor.cancel()
                if (attemptJob === attemptSupervisor) attemptJob = null
                if (socket === active) socket = null
                shutdownClient(http)
                if (client === http) client = null
            }
            if (!running) break
            _state.value = RelaySocketState.Offline
            val delayMillis = backoffSchedule[attempt.coerceAtMost(backoffSchedule.size - 1)]
            attempt = (attempt + 1).coerceAtMost(backoffSchedule.size - 1)
            val wake = CompletableDeferred<Unit>()
            retryWake = wake
            withTimeoutOrNull(delayMillis) { wake.await() }
            if (retryWake === wake) retryWake = null
        }
        if (_state.value != RelaySocketState.Unauthorized) _state.value = RelaySocketState.Stopped
    }

    private fun shutdownClient(http: OkHttpClient) {
        runCatching { http.dispatcher.executorService.shutdown() }
        runCatching { http.connectionPool.evictAll() }
    }

    private fun webSocketUrl(origin: String): String {
        val clean = origin.trimEnd('/')
        val secure = clean.startsWith("https://")
        val host = clean.substringAfter("://")
        return if (secure) "wss://$host/v1/ws" else "ws://$host/v1/ws"
    }

    private companion object {
        private const val CLOSE_CODE_UNAUTHORIZED = 4001
        private const val FRAME_BUFFER_CAPACITY = 2_048
        private const val PING_INTERVAL_SECONDS = 20L
        private const val HELLO_TIMEOUT_MILLIS = 20_000L
        private val BACKOFF = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    }
}

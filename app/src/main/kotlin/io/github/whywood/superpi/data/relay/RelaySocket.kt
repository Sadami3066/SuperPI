package io.github.whywood.superpi.data.relay

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    private var job: Job? = null

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
        if (running) return
        running = true
        job = scope.launch(io) { connectLoop(account, token) { hello() } }
    }

    fun stop() {
        running = false
        retryWake?.complete(Unit)
        retryWake = null
        socket?.close(1000, "closed")
        socket = null
        client?.let(::shutdownClient)
        client = null
        job?.cancel()
        job = null
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
            val closed = CompletableDeferred<Boolean>()
            val pin = account.caPem?.let { RelayTls.decodeBytes(it.toByteArray(Charsets.UTF_8)) }
            val http = RelayTls.client(pin = pin, remote = true).newBuilder()
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
            client = http
            val request = Request.Builder()
                .url(webSocketUrl(account.origin))
                .header("authorization", "Bearer $token")
                .build()
            lastFrameAt = System.currentTimeMillis()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    socket = webSocket
                    attempt = 0
                    lastFrameAt = System.currentTimeMillis()
                    _state.value = RelaySocketState.Online
                    scope.launch {
                        val greeting = runCatching { hello() }.getOrNull() ?: return@launch
                        webSocket.send(greeting)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastFrameAt = System.currentTimeMillis()
                    RelayFrames.parse(text)?.let { frame ->
                        if (!_frames.tryEmit(frame)) {
                            scope.launch { _frames.emit(frame) }
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == CLOSE_CODE_UNAUTHORIZED) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    runCatching { webSocket.close(code, reason) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    if (code == 401 || code == 403) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    closed.complete(false)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code == CLOSE_CODE_UNAUTHORIZED) {
                        _state.value = RelaySocketState.Unauthorized
                        running = false
                    }
                    closed.complete(true)
                }
            }
            val active = http.newWebSocket(request, listener)
            socket = active
            closed.await()
            socket = null
            shutdownClient(http)
            client = null
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
        val secure = origin.startsWith("https://")
        val host = origin.substringAfter("://")
        return if (secure) "wss://$host/v1/ws" else "ws://$host/v1/ws"
    }

    private companion object {
        private const val CLOSE_CODE_UNAUTHORIZED = 4001
        private const val FRAME_BUFFER_CAPACITY = 2_048
        private val BACKOFF = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    }
}

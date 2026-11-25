package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class FakeCall(
    val to: String,
    val method: String,
    val params: JsonObject,
    val timeoutMillis: Long,
    val routeId: String,
)

internal class FakeRemoteTransport(
    online: Boolean = true,
    capabilities: List<String> = DEFAULT_CAPABILITIES,
) : RemoteAgentTransport {
    private val events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 128)
    private val streamClosuresFlow = MutableSharedFlow<RemoteStreamClosed>(extraBufferCapacity = 64)
    private val syncEventsFlow = MutableSharedFlow<RemoteSyncEvent>(extraBufferCapacity = 64)
    private val onlineState = MutableStateFlow(if (online) setOf(DEVICE_ID) else emptySet())
    private val devicesFlow = MutableStateFlow(
        if (online) {
            listOf(
                RemoteDeviceSummary(
                    deviceId = DEVICE_ID,
                    name = "Desktop",
                    platform = "desktop",
                    online = true,
                    capabilities = capabilities,
                ),
            )
        } else {
            emptyList()
        },
    )
    private val relayOnlineState = MutableStateFlow(online)
    private val lastError = MutableStateFlow<RemoteCallError?>(null)
    private var callCount = 0

    override val remoteEvents: SharedFlow<RemoteEvent> = events.asSharedFlow()

    override val streamClosures: SharedFlow<RemoteStreamClosed> = streamClosuresFlow.asSharedFlow()

    override val onlineDevices: StateFlow<Set<String>> = onlineState.asStateFlow()

    override val syncEvents: SharedFlow<RemoteSyncEvent> = syncEventsFlow.asSharedFlow()

    override val devices: StateFlow<List<RemoteDeviceSummary>> = devicesFlow.asStateFlow()

    override val isRelayOnline: StateFlow<Boolean> = relayOnlineState.asStateFlow()

    val calls: MutableList<FakeCall> = mutableListOf()

    var failNext: RemoteCallError? = null

    var beforeCallReturn: (suspend (FakeCall) -> Unit)? = null

    var result: (FakeCall) -> JsonObject? = { call ->
        if (call.method == "bot.prompt") {
            buildJsonObject { put("sessionId", PEER_SESSION_ID) }
        } else {
            buildJsonObject {}
        }
    }

    override fun lastCallError(): RemoteCallError? = lastError.value

    override fun supportsPeerCapability(deviceId: String, capability: String): Boolean =
        devicesFlow.value.firstOrNull { it.deviceId == deviceId }?.capabilities?.contains(capability) == true

    fun setOnline(online: Boolean) {
        onlineState.value = if (online) setOf(DEVICE_ID) else emptySet()
        relayOnlineState.value = online
        devicesFlow.value = devicesFlow.value.map { it.copy(online = online) }
    }

    fun emitSyncEvent(event: RemoteSyncEvent) {
        syncEventsFlow.tryEmit(event)
    }

    fun emitStreamClosure(closed: RemoteStreamClosed) {
        streamClosuresFlow.tryEmit(closed)
    }

    override suspend fun call(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        onRouteId: (String) -> Unit,
    ): JsonObject? {
        callCount += 1
        val call = FakeCall(to, method, params, timeoutMillis, "route-$callCount")
        calls += call
        onRouteId(call.routeId)
        beforeCallReturn?.invoke(call)
        val failure = failNext
        if (failure != null) {
            failNext = null
            lastError.value = failure
            return null
        }
        val value = result(call)
        lastError.value = if (value == null) RemoteCallError("internal", "call failed") else null
        return value
    }

    fun emit(routeId: String, seq: Long, event: JsonObject) {
        events.tryEmit(RemoteEvent(routeId, seq, event))
    }

    fun latestRouteId(): String = calls.lastOrNull()?.routeId.orEmpty()

    companion object {
        const val DEVICE_ID = "device-1"
        const val PEER_SESSION_ID = "peer-1"
        val DEFAULT_CAPABILITIES = listOf(
            "remote-bot",
            "bot-sessions",
            "bot-manage",
            "session-manage",
            "workspace-files",
            "bot-controls",
            "session-controls",
            "sync-events",
            "bot-capabilities",
            "present-artifacts",
        )
    }
}

internal object FakeEvents {
    fun messageUpdate(type: String, delta: String): JsonObject = buildJsonObject {
        put("type", "message_update")
        put(
            "assistantMessageEvent",
            buildJsonObject {
                put("type", type)
                put("delta", delta)
            },
        )
    }

    fun textDelta(delta: String): JsonObject = messageUpdate("text_delta", delta)

    fun reasoningDelta(delta: String): JsonObject = messageUpdate("reasoning_delta", delta)

    fun toolCall(callId: String, name: String, args: JsonObject? = null): JsonObject = buildJsonObject {
        put("type", "tool_call")
        put("callId", callId)
        put("name", name)
        if (args != null) put("args", args)
    }

    fun toolResult(callId: String, result: String, isError: Boolean = false): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("callId", callId)
        put("result", result)
        put("isError", isError)
    }

    fun failure(message: String): JsonObject = buildJsonObject {
        put("type", "error")
        put("message", message)
    }

    fun agentStart(): JsonObject = buildJsonObject { put("type", "agent_start") }

    fun agentEnd(status: String? = null): JsonObject = buildJsonObject {
        put("type", "agent_end")
        if (status != null) put("status", status)
    }

    fun settled(): JsonObject = buildJsonObject { put("type", "agent_settled") }
}

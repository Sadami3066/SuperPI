package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.data.relay.RemoteEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

data class RemoteDeviceSummary(
    val deviceId: String,
    val name: String,
    val platform: String,
    val online: Boolean,
    val capabilities: List<String> = emptyList(),
)

data class RemoteSyncEvent(
    val deviceId: String,
    val kind: String,
    val botId: String,
    val sessionId: String? = null,
    val busy: Boolean? = null,
    val pendingApprovals: Int? = null,
    val provider: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
    val ts: Long = 0L,
)

private object EmptyTransportFlows {
    val syncEvents: SharedFlow<RemoteSyncEvent> = MutableSharedFlow<RemoteSyncEvent>().asSharedFlow()
    val devices: StateFlow<List<RemoteDeviceSummary>> = MutableStateFlow<List<RemoteDeviceSummary>>(emptyList()).asStateFlow()
    val isRelayOnline: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}

interface RemoteAgentTransport {
    val remoteEvents: SharedFlow<RemoteEvent>

    val onlineDevices: StateFlow<Set<String>>

    val syncEvents: SharedFlow<RemoteSyncEvent>
        get() = EmptyTransportFlows.syncEvents

    val devices: StateFlow<List<RemoteDeviceSummary>>
        get() = EmptyTransportFlows.devices

    val isRelayOnline: StateFlow<Boolean>
        get() = EmptyTransportFlows.isRelayOnline

    fun lastCallError(): RemoteCallError? = null

    fun supportsPeerCapability(deviceId: String, capability: String): Boolean = false

    fun emitSyncEvent(
        kind: String,
        botId: String,
        sessionId: String? = null,
        busy: Boolean? = null,
        pendingApprovals: Int? = null,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
    ) = Unit

    suspend fun call(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
        onRouteId: (String) -> Unit = {},
    ): JsonObject?

    suspend fun callWithOutcome(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long = CALL_TIMEOUT_MILLIS,
        onRouteId: (String) -> Unit = {},
    ): RemoteCallOutcome {
        val result = call(to, method, params, timeoutMillis, onRouteId)
        return if (result != null) {
            RemoteCallOutcome.Ok(result)
        } else {
            RemoteCallOutcome.Failed(lastCallError() ?: RemoteCallError("unknown", "remote call failed"))
        }
    }

    companion object {
        const val CALL_TIMEOUT_MILLIS = 15_000L
    }
}

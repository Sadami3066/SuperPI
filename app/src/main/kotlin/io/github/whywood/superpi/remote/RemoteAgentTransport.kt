package io.github.whywood.superpi.remote

import io.github.whywood.superpi.data.relay.RemoteCallError
import io.github.whywood.superpi.data.relay.RemoteCallOutcome
import io.github.whywood.superpi.data.relay.RemoteEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

interface RemoteAgentTransport {
    val remoteEvents: SharedFlow<RemoteEvent>

    val onlineDevices: StateFlow<Set<String>>

    fun lastCallError(): RemoteCallError? = null

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

package io.github.whywood.superpi.data.relay

import io.github.whywood.superpi.remote.RemoteAgentTransport
import io.github.whywood.superpi.remote.RemoteDeviceSummary
import io.github.whywood.superpi.remote.RemoteSyncEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject

class RelayAgentTransport(
    private val live: RelayLive,
    scope: CoroutineScope,
) : RemoteAgentTransport {
    override val remoteEvents: SharedFlow<RemoteEvent> = live.remoteEvents

    override val onlineDevices: StateFlow<Set<String>> = live.remoteBots
        .map { bots -> bots.filter { it.online }.map { it.deviceId }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val syncEvents: SharedFlow<RemoteSyncEvent> = live.syncEvents
        .map { event ->
            RemoteSyncEvent(
                deviceId = event.deviceId,
                kind = event.kind,
                botId = event.botId,
                sessionId = event.sessionId,
                busy = event.busy,
                pendingApprovals = event.pendingApprovals,
                provider = event.provider,
                modelId = event.modelId,
                thinkingLevel = event.thinkingLevel,
                ts = event.ts,
            )
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    override val devices: StateFlow<List<RemoteDeviceSummary>> = live.devices
        .map { list ->
            list.map { device ->
                RemoteDeviceSummary(
                    deviceId = device.deviceId,
                    name = device.name,
                    platform = device.platform,
                    online = device.online,
                    capabilities = device.capabilities,
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val isRelayOnline: StateFlow<Boolean> = live.state
        .map { it == RelayLiveState.Online }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override fun lastCallError(): RemoteCallError? = live.lastCallError.value

    override fun supportsPeerCapability(deviceId: String, capability: String): Boolean =
        live.supportsPeerCapability(deviceId, capability)

    override fun emitSyncEvent(
        kind: String,
        botId: String,
        sessionId: String?,
        busy: Boolean?,
        pendingApprovals: Int?,
        provider: String?,
        modelId: String?,
        thinkingLevel: String?,
    ) {
        live.emitSyncEvent(
            RelaySyncEvent(
                kind = kind,
                botId = botId,
                sessionId = sessionId,
                busy = busy,
                pendingApprovals = pendingApprovals,
                provider = provider,
                modelId = modelId,
                thinkingLevel = thinkingLevel,
            ),
        )
    }

    override suspend fun call(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        onRouteId: (String) -> Unit,
    ): JsonObject? = live.call(to, method, params, timeoutMillis, onRouteId)

    override suspend fun callWithOutcome(
        to: String,
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        onRouteId: (String) -> Unit,
    ): RemoteCallOutcome = live.callWithOutcome(to, method, params, timeoutMillis, onRouteId)
}

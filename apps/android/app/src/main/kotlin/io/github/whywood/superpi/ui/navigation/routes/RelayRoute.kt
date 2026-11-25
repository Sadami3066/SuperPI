package io.github.whywood.superpi.ui.navigation.routes

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.ui.relay.RelayScreen

@Composable
internal fun RelayRoute(
    dependencies: AppDependencies,
    onBack: () -> Unit,
) {
    val state by dependencies.relayController.state.collectAsStateWithLifecycle()
    val liveState by dependencies.relayLive.state.collectAsStateWithLifecycle()
    val allowRemote by dependencies.relayLive.allowRemote.collectAsStateWithLifecycle()
    val devices by dependencies.relayLive.devices.collectAsStateWithLifecycle()
    val syncState by dependencies.relayMemorySync.state.collectAsStateWithLifecycle()
    val model = Build.MODEL.orEmpty().trim()

    RelayScreen(
        state = state,
        liveState = liveState,
        allowRemote = allowRemote,
        devices = devices,
        defaultDeviceName = remember(model) { model.ifEmpty { "Android" } },
        syncState = syncState,
        onBack = onBack,
        onConnect = dependencies.relayController::signIn,
        onSignOut = dependencies.relayController::signOut,
        onDismissError = dependencies.relayController::dismissError,
        onToggleAllowRemote = dependencies.relayLive::setAllowRemote,
        onGeneratePairCode = dependencies.relayController::generatePairCode,
        onDismissPairCode = dependencies.relayController::dismissPairCode,
        onRevokeDevice = { deviceId ->
            dependencies.relayController.revokeDevice(deviceId) { revoked ->
                dependencies.relayLive.removeDevice(revoked)
            }
        },
        onRenameDevice = { deviceId, name ->
            dependencies.relayController.renameDevice(deviceId, name) { renamedId, cleanName ->
                dependencies.relayLive.updateDeviceName(renamedId, cleanName)
            }
        },
        onSyncMemoryNow = dependencies.relayMemorySync::triggerSyncNow,
        onResolveMemoryAccountSwitch = dependencies.relayMemorySync::triggerResolveAccountSwitch,
    )
}

package io.github.whywood.superpi.ui.navigation.routes

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.runtime.memory.MemoryAccountSwitchMode
import io.github.whywood.superpi.ui.memory.MemoryAccountSwitchDialog
import io.github.whywood.superpi.ui.relay.RelayScreen
import kotlinx.coroutines.launch

@Composable
internal fun RelayRoute(
    dependencies: AppDependencies,
    onBack: () -> Unit,
) {
    val state by dependencies.relayController.state.collectAsStateWithLifecycle()
    val liveState by dependencies.relayLive.state.collectAsStateWithLifecycle()
    val allowRemote by dependencies.relayLive.allowRemote.collectAsStateWithLifecycle()
    val devices by dependencies.relayLive.devices.collectAsStateWithLifecycle()
    val syncState by dependencies.memorySync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dismissedSwitchKey by rememberSaveable { mutableStateOf<String?>(null) }
    var resolvingAccountSwitch by remember { mutableStateOf(false) }
    val model = Build.MODEL.orEmpty().trim()

    LaunchedEffect(syncState.pendingAccountSwitch) {
        if (syncState.pendingAccountSwitch == null) {
            dismissedSwitchKey = null
        }
    }

    RelayScreen(
        state = state,
        liveState = liveState,
        allowRemote = allowRemote,
        devices = devices,
        defaultDeviceName = remember(model) { model.ifEmpty { "Android" } },
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
    )

    val switchPrompt = syncState.pendingAccountSwitch
    if (switchPrompt != null && dismissedSwitchKey != switchPrompt.accountKey) {
        fun handleSwitch(mode: MemoryAccountSwitchMode) {
            if (resolvingAccountSwitch) return
            resolvingAccountSwitch = true
            scope.launch {
                try {
                    dependencies.memorySync.resolveAccountSwitch(mode)
                } finally {
                    resolvingAccountSwitch = false
                }
            }
        }
        MemoryAccountSwitchDialog(
            localMemoryCount = switchPrompt.localMemoryCount,
            busy = resolvingAccountSwitch,
            onMerge = { handleSwitch(MemoryAccountSwitchMode.MERGE_ALL) },
            onClear = { handleSwitch(MemoryAccountSwitchMode.REPLACE_WITH_REMOTE) },
            onDismiss = { dismissedSwitchKey = switchPrompt.accountKey },
        )
    }
}

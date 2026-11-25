package io.github.whywood.superpi.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.whywood.superpi.R
import io.github.whywood.superpi.data.relay.RelayLiveState
import io.github.whywood.superpi.data.relay.RemoteCallCodes
import io.github.whywood.superpi.remote.RemoteAgentTransport
import io.github.whywood.superpi.remote.RemoteArtifactClient
import io.github.whywood.superpi.remote.RemoteArtifactResult
import io.github.whywood.superpi.ui.common.chat.RemotePresentContext
import java.io.File

@Composable
internal fun rememberRemotePresentContext(
    deviceId: String?,
    deviceName: String,
    botId: String?,
    attachmentRoot: File,
    isRemoteOnline: Boolean,
    relayState: RelayLiveState,
    remoteTransport: RemoteAgentTransport,
    remoteArtifactClient: RemoteArtifactClient,
): RemotePresentContext? {
    val peerUpgradeMsg = stringResource(R.string.drawer_remote_peer_upgrade)
    val actionFailedMsg = stringResource(R.string.drawer_remote_action_failed)
    val offlineMsg = stringResource(R.string.drawer_remote_device_offline)
    val timeoutMsg = stringResource(R.string.drawer_remote_timeout)
    return remember(
        deviceId,
        deviceName,
        botId,
        attachmentRoot,
        isRemoteOnline,
        relayState,
        peerUpgradeMsg,
        actionFailedMsg,
        offlineMsg,
        timeoutMsg,
    ) {
        if (deviceId.isNullOrBlank() || botId.isNullOrBlank()) {
            null
        } else {
            val supportsArtifacts = relayState == RelayLiveState.Online &&
                isRemoteOnline &&
                remoteTransport.supportsPeerCapability(
                    deviceId,
                    RemoteArtifactClient.CAP_PRESENT_ARTIFACTS,
                )
            object : RemotePresentContext {
                override val deviceName: String = deviceName
                override val supportsArtifacts: Boolean = supportsArtifacts

                override fun cachedFile(artifactId: String, name: String, size: Long?): File? =
                    remoteArtifactClient.cachedFile(attachmentRoot, artifactId, name, size)

                override suspend fun fetch(
                    artifactId: String,
                    name: String,
                    size: Long?,
                    onProgress: (Long, Long) -> Unit,
                ): Result<File> = when (
                    val res = remoteArtifactClient.fetch(
                        deviceId = deviceId,
                        botId = botId,
                        workspaceRoot = attachmentRoot,
                        artifactId = artifactId,
                        name = name,
                        expectedSize = size,
                        onProgress = onProgress,
                    )
                ) {
                    is RemoteArtifactResult.Ok -> Result.success(res.file)
                    RemoteArtifactResult.PeerUpgradeRequired ->
                        Result.failure(IllegalStateException(peerUpgradeMsg))
                    is RemoteArtifactResult.Error ->
                        Result.failure(
                            IllegalStateException(
                                when (res.code) {
                                    RemoteCallCodes.NOT_CONNECTED,
                                    RemoteCallCodes.DISCONNECTED,
                                    "device_offline",
                                    -> offlineMsg
                                    "capability_unavailable" -> peerUpgradeMsg
                                    RemoteCallCodes.TIMEOUT -> timeoutMsg
                                    else -> actionFailedMsg
                                },
                            ),
                        )
                }
            }
        }
    }
}

package io.github.whywood.superpi.ui.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.whywood.superpi.core.AppDependencies
import io.github.whywood.superpi.data.appHomeDirectory
import io.github.whywood.superpi.ui.files.FileBrowserScreen
import io.github.whywood.superpi.ui.files.RemoteFileBrowserScreen
import io.github.whywood.superpi.ui.navigation.AppDestination

@Composable
internal fun FilesRoute(
    rootPath: String,
    onOpenFile: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val privateRoot = remember(context) { appHomeDirectory(context) }
    FileBrowserScreen(
        initialPath = rootPath,
        privateRoot = privateRoot,
        onOpenFile = onOpenFile,
        onBack = onBack,
    )
}

@Composable
internal fun RemoteFilesRoute(
    destination: AppDestination.RemoteFiles,
    dependencies: AppDependencies,
    onBack: () -> Unit,
) {
    RemoteFileBrowserScreen(
        deviceId = destination.deviceId,
        deviceName = destination.deviceName,
        botId = destination.botId,
        botName = destination.botName,
        remoteFileClient = dependencies.remoteFileClient,
        transport = dependencies.remoteTransport,
        onBack = onBack,
    )
}

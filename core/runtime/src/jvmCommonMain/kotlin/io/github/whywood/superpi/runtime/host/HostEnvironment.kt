package io.github.whywood.superpi.runtime.host

import io.github.whywood.superpi.sdk.coding.tools.ShellExecutor
import okio.FileSystem

class HostAccessUnavailableException(message: String) : IllegalStateException(message)

data class ShellToolProfile(
    val toolDescription: String,
    val guidelines: List<String>,
)

data class HostCapabilities(
    val isRoot: Boolean = false,
    val hasSystemShell: Boolean = false,
    val canReadSystemData: Boolean = false,
    val bashAvailable: Boolean = false,
    val hasScreenControl: Boolean = false,
    val rootLinuxReady: Boolean = false,
)

interface HostEnvironment {
    val access: HostAccess
    val capabilities: HostCapabilities
    val sharedStorageEnabled: Boolean
    fun bash(cwd: String): ShellExecutor
    fun bashStreaming(cwd: String): StreamingShellExecutor? = null
    val shellToolProfile: ShellToolProfile? get() = null
    val files: FileSystem
    val system: ShellExecutor?
    val promptLines: List<String>
}

interface HostEnvironmentProvider {
    fun current(): HostEnvironment
}

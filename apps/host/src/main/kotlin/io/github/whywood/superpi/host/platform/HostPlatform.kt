package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import io.github.whywood.superpi.runtime.DetailedShellExecutor
import io.github.whywood.superpi.runtime.SecretStore
import io.github.whywood.superpi.runtime.host.HostEnvironment
import io.github.whywood.superpi.runtime.host.ShellToolProfile
import io.github.whywood.superpi.runtime.host.StreamingShellExecutor
import java.io.File

enum class OsKind(val id: String) {
    LINUX("linux"),
    WINDOWS("windows"),
    MACOS("macos"),
}

data class ShellProbeResult(
    val available: Boolean,
    val shellPath: String?,
    val shellName: String,
    val message: String,
)

data class OsProfile(
    val os: OsKind,
    val osName: String,
    val arch: String,
    val shellName: String,
    val shellPath: String,
    val promptLines: List<String>,
    val shellToolProfile: ShellToolProfile,
)

interface ShellBackend {
    fun executor(cwd: String): DetailedShellExecutor

    fun streamingExecutor(cwd: String): StreamingShellExecutor

    fun probe(): ShellProbeResult
}

interface ProcessTreeKiller {
    fun kill(process: Process, ownProcessGroup: Boolean = false)
}

/**
 * Canonical ~/.super-pi layout shared across Linux, Windows, and macOS.
 */
class PathLayout(
    val homeDir: File,
) {
    val settingsFile: File get() = File(homeDir, "settings.json")
    val botsFile: File get() = File(homeDir, "bots.json")
    val botPoliciesFile: File get() = File(homeDir, "bot-policies.json")
    val customProvidersFile: File get() = File(homeDir, "models.json")
    val mcpConfigFile: File get() = File(homeDir, "mcp.json")
    val mcpAuthFile: File get() = File(homeDir, "mcp-auth.json")
    val sessionMetaFile: File get() = File(homeDir, "session-meta.json")
    val memoryPreferencesFile: File get() = File(homeDir, "memory-preferences.json")
    val relayFile: File get() = File(homeDir, "relay.json")
    val secretsFile: File get() = File(homeDir, "secrets.enc")
    val secretKeyFile: File get() = File(homeDir, "master.key")
    val memoryDir: File get() = File(homeDir, "memory")
    val sessionsDir: File get() = File(homeDir, "sessions")
    val workspacesDir: File get() = File(homeDir, "workspaces")
    val defaultWorkspaceDir: File get() = File(workspacesDir, "default")
    val skillsRepoDir: File get() = File(homeDir, "skills-repo")
    val skillsDir: File get() = File(homeDir, "skills")
    val promptsDir: File get() = File(homeDir, "prompts")
    val extensionsDir: File get() = File(homeDir, "extensions")
    val timersDir: File get() = File(homeDir, "timers")
    val goalsDir: File get() = File(homeDir, "goals")
    val tasksDir: File get() = File(homeDir, "tasks")
    val superDir: File get() = File(homeDir, "super")
    val superWorkersDir: File get() = File(superDir, "workers")
    val botAvatarsDir: File get() = File(homeDir, "bot-avatars")
    val cacheDir: File get() = File(homeDir, "cache")
    val shellLogsDir: File get() = File(cacheDir, "shell-logs")
    val logsDir: File get() = File(cacheDir, "logs")
    val serveLockFile: File get() = File(cacheDir, "serve.lock")
    val replHistoryFile: File get() = File(cacheDir, "repl_history")
    val installationIdDir: File get() = homeDir

    fun ensureDirectories() {
        listOf(
            homeDir,
            memoryDir,
            sessionsDir,
            workspacesDir,
            defaultWorkspaceDir,
            skillsRepoDir,
            skillsDir,
            promptsDir,
            extensionsDir,
            timersDir,
            goalsDir,
            tasksDir,
            superDir,
            superWorkersDir,
            botAvatarsDir,
            cacheDir,
            shellLogsDir,
            logsDir,
        ).forEach { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                homeDir.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
            )
        }
    }

    fun normalizePath(raw: String): String =
        runCatching { File(raw).canonicalPath }.getOrDefault(File(raw).absolutePath)

    companion object {
        fun defaultHomeDir(): File {
            val envOverride = System.getenv(AppBrand.ENV_HOME)?.trim()?.takeIf { it.isNotEmpty() }
            if (envOverride != null) return File(envOverride).absoluteFile
            val userHome = System.getProperty("user.home").orEmpty()
                .ifBlank { System.getenv("USERPROFILE").orEmpty() }
                .ifBlank { "." }
            return File(userHome, AppBrand.HOME_DIR_NAME).absoluteFile
        }
    }
}

interface HostPlatform {
    val profile: OsProfile
    val shell: ShellBackend
    val processKiller: ProcessTreeKiller
    val paths: PathLayout

    fun createSecretStore(): SecretStore

    fun createHostEnvironment(): HostEnvironment

    fun registerChildProcess(process: Process, ownProcessGroup: Boolean = false)

    fun unregisterChildProcess(process: Process)

    fun cleanupAllChildProcesses()
}

object HostPlatforms {
    fun detectOs(): OsKind {
        val raw = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            "win" in raw -> OsKind.WINDOWS
            "mac" in raw || "darwin" in raw -> OsKind.MACOS
            else -> OsKind.LINUX
        }
    }

    fun detect(homeOverride: File? = null): HostPlatform {
        val homeDir = (homeOverride ?: PathLayout.defaultHomeDir()).absoluteFile
        val paths = PathLayout(homeDir)
        return when (detectOs()) {
            OsKind.LINUX -> LinuxHostPlatform(paths)
            OsKind.WINDOWS -> WindowsHostPlatform(paths)
            OsKind.MACOS -> MacosHostPlatform(paths)
        }
    }
}

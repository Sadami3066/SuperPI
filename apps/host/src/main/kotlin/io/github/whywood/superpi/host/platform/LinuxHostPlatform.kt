package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.runtime.host.ShellToolProfile
import java.io.File

class LinuxHostPlatform(
    paths: PathLayout,
    customShellPath: String? = null,
) : BaseHostPlatform(paths, PosixProcessTreeKiller()) {
    private val linuxShell = LinuxShellBackend(
        logsDir = paths.shellLogsDir,
        processKiller = processKiller,
        onRegisterProcess = { proc, ownGroup -> trackedProcesses[proc] = ownGroup },
        onUnregisterProcess = { proc -> trackedProcesses.remove(proc) },
        customShellPath = customShellPath,
    )

    override val shell: ShellBackend get() = linuxShell

    override val profile: OsProfile by lazy {
        val resolved = linuxShell.resolveShellPath() ?: "/bin/sh"
        val shellName = File(resolved).name
        val arch = System.getProperty("os.arch").orEmpty().ifBlank { "unknown" }
        val user = System.getProperty("user.name").orEmpty().ifBlank { "user" }
        val userHome = System.getProperty("user.home").orEmpty()
        OsProfile(
            os = OsKind.LINUX,
            osName = System.getProperty("os.name").orEmpty().ifBlank { "Linux" },
            arch = arch,
            shellName = shellName,
            shellPath = resolved,
            promptLines = listOf(
                "Host OS: Linux ($arch)",
                "Shell: $resolved ($shellName, GNU/POSIX userland)",
                "User: $user (home: $userHome)",
                "SuperPI Data Home: ${paths.homeDir.absolutePath}",
                "Commands run natively on the host Linux environment in the active bot working directory.",
            ),
            shellToolProfile = ShellToolProfile(
                toolDescription = "Execute a command in the host Linux $shellName shell for the current working directory. Returns merged stdout and stderr, ending with [exit: N]. Output is truncated to the last 2000 lines or 50KB (with complete log path saved when exceeded). Optionally provide timeout in seconds.",
                guidelines = listOf(
                    "Check the [exit: N] marker on every bash result; investigate failures before moving on.",
                    "Commands run directly in the host Linux $shellName environment; use standard POSIX/GNU tools.",
                    "Always provide a short plain-language description argument when running bash so the user sees what is happening.",
                ),
            ),
        )
    }

    companion object {
        internal fun envSecretFallback(prefix: String, id: String): String? =
            HostSecretEnv.envSecretFallback(prefix, id)

        internal fun envSecretEntries(prefix: String): Map<String, String> =
            HostSecretEnv.envSecretEntries(prefix)
    }
}

package io.github.whywood.superpi.host.platform

import io.github.whywood.superpi.host.brand.AppBrand
import java.io.File

internal interface HostServiceManager {
    val label: String

    fun install(paths: PathLayout, port: Int): Result<File>

    fun uninstall(paths: PathLayout): Result<Unit>

    fun start(paths: PathLayout): CommandExecOutput

    fun stop(paths: PathLayout): CommandExecOutput

    fun status(paths: PathLayout): CommandExecOutput

    fun logs(paths: PathLayout, follow: Boolean, lines: Int = 80): Int
}

internal object HostServiceManagers {
    fun forPlatform(platform: HostPlatform): HostServiceManager = when (platform.profile.os) {
        OsKind.LINUX -> LinuxServiceManager
        OsKind.MACOS -> MacosServiceManager
        OsKind.WINDOWS -> WindowsServiceManager
    }

    fun renderXmlText(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    fun tailLogFile(logFile: File, lines: Int, follow: Boolean): Int {
        if (!logFile.isFile) {
            System.err.println("Log file not found yet: ${logFile.absolutePath}")
            return 1
        }
        val initialLines = runCatching { logFile.readLines(Charsets.UTF_8).takeLast(lines.coerceAtLeast(1)) }
            .getOrDefault(emptyList())
        initialLines.forEach(::println)
        if (!follow) return 0
        var offset = logFile.length()
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(500)
                val len = logFile.length()
                if (len < offset) {
                    offset = 0L
                }
                if (len > offset) {
                    java.io.RandomAccessFile(logFile, "r").use { raf ->
                        raf.seek(offset)
                        val bytes = ByteArray((len - offset).coerceAtMost(256L * 1024L).toInt())
                        val read = raf.read(bytes)
                        if (read > 0) {
                            print(String(bytes, 0, read, Charsets.UTF_8))
                            System.out.flush()
                            offset += read
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return 0
    }

    fun defaultLogFile(paths: PathLayout): File = File(paths.logsDir, "${AppBrand.CLI_NAME}-service.log")
}

package io.github.whywood.superpi.host

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import io.github.whywood.superpi.host.cli.CliRuntimeEnv
import io.github.whywood.superpi.host.cli.SuperPiRootCommand
import io.github.whywood.superpi.host.platform.HostPlatform
import io.github.whywood.superpi.host.platform.HostPlatforms
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        SuperPiRootCommand().main(args)
    } catch (err: Throwable) {
        val message = err.message?.takeIf { it.isNotBlank() } ?: err::class.java.simpleName
        System.err.println("Fatal error: $message")
        exitProcess(1)
    }
}

internal fun executeCliForTest(
    args: List<String>,
    homeDir: File,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    platformFactory: (File?) -> HostPlatform = { HostPlatforms.detect(it) },
) {
    val env = CliRuntimeEnv(
        homeOverride = homeDir,
        out = out,
        err = err,
        platformFactory = platformFactory,
    )
    SuperPiRootCommand(env).parse(args)
}

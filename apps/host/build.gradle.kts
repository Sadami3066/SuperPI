import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "2.0.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.whywood.superpi.host.MainKt")
    applicationName = "super-pi"
}

val generateBrandBuildInfo = tasks.register("generateBrandBuildInfo") {
    val outDir = layout.buildDirectory.dir("generated/resources/brand")
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("super-pi-build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$appVersion\n")
    }
}

val syncWebUiResources = tasks.register("syncWebUiResources") {
    val webDistDir = rootProject.layout.projectDirectory.dir("apps/web/dist").asFile
    val outDir = layout.buildDirectory.dir("generated/resources/web-ui")
    outputs.dir(outDir)
    outputs.upToDateWhen { false }
    doLast {
        val target = outDir.get().asFile.resolve("web-ui")
        target.deleteRecursively()
        if (webDistDir.isDirectory && File(webDistDir, "index.html").isFile) {
            target.mkdirs()
            webDistDir.copyRecursively(target, overwrite = true)
        }
    }
}

sourceSets.named("main") {
    resources.srcDir(generateBrandBuildInfo)
    resources.srcDir(syncWebUiResources)
}

dependencies {
    implementation(project(":core:runtime-api"))
    implementation(project(":core:runtime"))
    implementation(project(":core:relay"))
    implementation(libs.pi.coding.agent.jvm)
    implementation(libs.androidx.sqlite.bundled.jvm)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.clikt)
    implementation(libs.jline)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

abstract class PackageHostDistTask : DefaultTask() {
    @get:InputDirectory
    abstract val installDir: DirectoryProperty

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val mainClassName: Property<String>

    @get:Input
    abstract val targetPlatform: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun packageBundle() {
        val target = targetPlatform.get()
        val hostTarget = detectHostTargetPlatform()
        if (target != hostTarget) {
            error("Cannot package '$target' on host '$hostTarget': jlink bundles the host JDK runtime. Run packageHostDist on a $target runner.")
        }
        val isWindows = target.startsWith("windows-")
        val hostIsWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val jlink = resolveJlink(hostIsWindows)
            ?: error("jlink not found (requires JDK 17+ with jlink in JAVA_HOME/bin or PATH)")
        val compressFlag = if (detectJlinkMajorVersion(jlink) >= 21) "--compress=zip-6" else "--compress=2"
        val root = outputDir.get().asFile
        val bundleName = "super-pi-${appVersion.get()}-$target"
        val stageDir = File(root, bundleName)
        val runtimeDir = File(stageDir, "runtime")
        val binDir = File(stageDir, "bin")
        val libDir = File(stageDir, "lib")
        stageDir.deleteRecursively()
        binDir.mkdirs()
        libDir.mkdirs()

        File(installDir.get().asFile, "lib").copyRecursively(libDir, overwrite = true)

        val jlinkModules = listOf(
            "java.base",
            "java.logging",
            "java.naming",
            "java.sql",
            "java.xml",
            "java.net.http",
            "java.desktop",
            "java.management",
            "jdk.crypto.ec",
            "jdk.unsupported",
            "jdk.zipfs",
            "jdk.charsets",
        ).joinToString(",")

        val jlinkProc = ProcessBuilder(
            jlink,
            "--add-modules",
            jlinkModules,
            "--strip-debug",
            "--no-man-pages",
            "--no-header-files",
            compressFlag,
            "--output",
            runtimeDir.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val jlinkOut = jlinkProc.inputStream.bufferedReader().readText()
        val jlinkExit = jlinkProc.waitFor()
        if (jlinkExit != 0) {
            error("jlink failed ($jlinkExit):\n$jlinkOut")
        }

        if (isWindows) {
            val bat = File(binDir, "super-pi.bat")
            bat.writeText(
                """@echo off
setlocal
for %%I in ("%~dp0..") do set "APP_HOME=%%~fI"
set "JAVA_BIN=%APP_HOME%\runtime\bin\java.exe"
set "JVM_OPTS=%SUPER_PI_JVM_OPTS%"
if not defined JVM_OPTS set "JVM_OPTS=-Xms64m -Xmx1g"
"%JAVA_BIN%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsuperpi.launcher="%APP_HOME%\bin\super-pi.bat" %JVM_OPTS% -cp "%APP_HOME%\lib\*" ${mainClassName.get()} %*
exit /b %errorlevel%
""".replace("\n", "\r\n"),
                Charsets.UTF_8,
            )
            val zipFile = File(root, "$bundleName.zip")
            zipFile.delete()
            zipDirectory(stageDir, zipFile)
            logger.lifecycle("Packaged self-contained Windows bundle: ${zipFile.absolutePath}")
        } else {
            val launcher = File(binDir, "super-pi")
            launcher.writeText(
                """#!/usr/bin/env bash
set -euo pipefail
SOURCE="${'$'}{BASH_SOURCE[0]}"
while [ -h "${'$'}SOURCE" ]; do
  DIR="$(cd -P "$(dirname "${'$'}SOURCE")" && pwd)"
  SOURCE="$(readlink "${'$'}SOURCE")"
  [[ "${'$'}SOURCE" != /* ]] && SOURCE="${'$'}DIR/${'$'}SOURCE"
done
APP_HOME="$(cd -P "$(dirname "${'$'}SOURCE")/.." && pwd)"
JAVA_BIN="${'$'}APP_HOME/runtime/bin/java"
exec "${'$'}JAVA_BIN" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsuperpi.launcher="${'$'}APP_HOME/bin/super-pi" ${'$'}{SUPER_PI_JVM_OPTS:--Xms64m -Xmx1g} -cp "${'$'}APP_HOME/lib/*" ${mainClassName.get()} "${'$'}@"
""".trimIndent() + "\n",
            )
            launcher.setExecutable(true, false)

            val tarFile = File(root, "$bundleName.tar.gz")
            tarFile.delete()
            val tarProc = ProcessBuilder(
                "tar",
                "-C",
                root.absolutePath,
                "-czf",
                tarFile.absolutePath,
                stageDir.name,
            )
                .redirectErrorStream(true)
                .start()
            val tarOut = tarProc.inputStream.bufferedReader().readText()
            val tarExit = tarProc.waitFor()
            if (tarExit != 0) {
                error("tar failed ($tarExit):\n$tarOut")
            }
            logger.lifecycle("Packaged self-contained bundle: ${tarFile.absolutePath}")
        }
    }

    private fun zipDirectory(sourceDir: File, outZip: File) {
        val basePath = sourceDir.parentFile.toPath()
        ZipOutputStream(outZip.outputStream().buffered()).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val rel = basePath.relativize(file.toPath()).toString().replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun resolveJlink(isWindows: Boolean): String? {
        val exeName = if (isWindows) "jlink.exe" else "jlink"
        val names = if (isWindows) listOf(exeName, "jlink") else listOf("jlink")
        val homes = listOfNotNull(
            System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() },
            System.getProperty("java.home")?.takeIf { it.isNotBlank() },
        )
        for (home in homes) {
            for (name in names) {
                val candidate = File(home, "bin/$name")
                if (candidate.isFile && (isWindows || candidate.canExecute())) return candidate.absolutePath
            }
        }
        val pathEntries = System.getenv("PATH")?.split(File.pathSeparatorChar).orEmpty()
        for (entry in pathEntries) {
            for (name in names) {
                val candidate = File(entry, name)
                if (candidate.isFile && (isWindows || candidate.canExecute())) return candidate.absolutePath
            }
        }
        return null
    }

    private fun detectJlinkMajorVersion(jlinkPath: String): Int {
        return runCatching {
            val p = ProcessBuilder(jlinkPath, "--version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            Regex("""(\d+)""").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 17
        }.getOrDefault(17)
    }

    companion object {
        fun detectHostTargetPlatform(): String {
            val rawOs = System.getProperty("os.name").orEmpty().lowercase()
            val osPart = when {
                "win" in rawOs -> "windows"
                "mac" in rawOs || "darwin" in rawOs -> "macos"
                else -> "linux"
            }
            val rawArch = System.getProperty("os.arch").orEmpty().lowercase()
            val archPart = when {
                rawArch in setOf("aarch64", "arm64") -> "arm64"
                rawArch in setOf("x86_64", "amd64", "x64") -> "x64"
                else -> rawArch.ifBlank { "x64" }
            }
            return "$osPart-$archPart"
        }
    }
}

val hostTargetPlatform = (findProperty("superpiTargetPlatform") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: PackageHostDistTask.detectHostTargetPlatform()

val packageHostDist = tasks.register<PackageHostDistTask>("packageHostDist") {
    group = "distribution"
    description = "Packages a self-contained super-pi bundle for the current OS/arch ($hostTargetPlatform) with a jlink runtime."
    dependsOn("installDist")
    installDir.set(layout.buildDirectory.dir("install/super-pi"))
    appVersion.set(project.version.toString())
    mainClassName.set(application.mainClass)
    targetPlatform.set(hostTargetPlatform)
    outputDir.set(layout.buildDirectory.dir("distributions/$hostTargetPlatform"))
}

tasks.register("packageLinuxDist") {
    group = "distribution"
    description = "Backward-compatible alias for packageHostDist."
    dependsOn(packageHostDist)
}

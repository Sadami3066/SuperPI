package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.TextContent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalQueryToolsTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("superpi-local-query-tools").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun lsListsSortedEntriesFollowsSymlinkAndRequiresAbsolutePath() = runBlocking {
        val targetDir = File(root, "real-dir").apply { mkdirs() }
        File(targetDir, "beta.txt").writeText("b")
        File(targetDir, "alpha").mkdirs()
        val linkDir = File(root, "link-dir")
        Files.createSymbolicLink(linkDir.toPath(), targetDir.toPath())

        val lsTool = superPiLsTool(FileSystem.SYSTEM)
        val result = lsTool.execute(
            "c1",
            buildJsonObject { put("path", linkDir.absolutePath) },
            null,
            null,
        ).textOutput()

        val lines = result.lines()
        assertEquals(
            listOf(
                "${linkDir.absolutePath}/alpha/",
                "${linkDir.absolutePath}/beta.txt",
            ),
            lines,
        )

        val relError = lsTool.execute(
            "c2",
            buildJsonObject { put("path", "relative/dir") },
            null,
            null,
        )
        assertEquals(true, relError.isError)
    }

    @Test
    fun grepSupportsLiteralRegexContextBinarySkipAndGitignore() = runBlocking {
        val repo = File(root, "repo").apply { mkdirs() }
        File(repo, ".gitignore").writeText("ignored/\n*.log\n")
        File(repo, "ignored").apply { mkdirs() }.let { File(it, "secret.kt").writeText("needle here") }
        File(repo, "debug.log").writeText("needle in log")
        File(repo, "bin.dat").writeBytes(byteArrayOf(0x4e, 0x00, 0x65, 0x65, 0x64, 0x6c, 0x65))
        File(repo, "src").apply { mkdirs() }.let { src ->
            File(src, "Main.kt").writeText("line1\nneedle target\nline3")
        }

        val grepTool = superPiGrepTool(FileSystem.SYSTEM)
        val output = grepTool.execute(
            "g1",
            buildJsonObject {
                put("pattern", "NEEDLE")
                put("path", repo.absolutePath)
                put("ignoreCase", true)
                put("context", 1)
            },
            null,
            null,
        ).textOutput()

        val mainPath = File(repo, "src/Main.kt").absolutePath
        assertTrue(output.contains("$mainPath-1- line1"))
        assertTrue(output.contains("$mainPath:2: needle target"))
        assertTrue(output.contains("$mainPath-3- line3"))
        assertFalse(output.contains("secret.kt"))
        assertFalse(output.contains("debug.log"))
        assertFalse(output.contains("bin.dat"))
    }

    @Test
    fun findFollowsSymlinkRespectsGitignoreAndSkipsVirtualRootDirs() = runBlocking {
        val project = File(root, "project").apply { mkdirs() }
        File(project, ".gitignore").writeText("build/\n")
        File(project, "build").apply { mkdirs() }.let { File(it, "Gen.kt").writeText("gen") }
        File(project, "app/sub").apply { mkdirs() }.let { File(it, "App.kt").writeText("app") }
        val symlinkRoot = File(root, "project-link")
        Files.createSymbolicLink(symlinkRoot.toPath(), project.toPath())

        val findTool = superPiFindTool(FileSystem.SYSTEM)
        val found = findTool.execute(
            "f1",
            buildJsonObject {
                put("pattern", "*.kt")
                put("path", symlinkRoot.absolutePath)
            },
            null,
            null,
        ).textOutput()
        assertEquals("${symlinkRoot.absolutePath}/app/sub/App.kt", found)

        val mappedRoot = File(root, "fake-root").apply { mkdirs() }
        File(mappedRoot, "proc").apply { mkdirs() }.let { File(it, "cpuinfo.txt").writeText("skip") }
        File(mappedRoot, "etc").apply { mkdirs() }.let { File(it, "hosts.txt").writeText("keep") }
        val virtualAwareFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            private fun remap(path: Path): Path {
                val raw = path.toString()
                return if (raw == "/") {
                    mappedRoot.absolutePath.toPath()
                } else {
                    (mappedRoot.absolutePath + raw).toPath()
                }
            }

            override fun metadataOrNull(path: Path): FileMetadata? =
                super.metadataOrNull(remap(path))

            override fun list(dir: Path): List<Path> =
                super.list(remap(dir)).map { child ->
                    val rel = child.toString().removePrefix(mappedRoot.absolutePath)
                    (if (rel.isEmpty()) "/" else rel).toPath()
                }
        }

        val rootFind = superPiFindTool(virtualAwareFs)
        val rootResults = rootFind.execute(
            "f2",
            buildJsonObject {
                put("pattern", "*.txt")
                put("path", "/")
            },
            null,
            null,
        ).textOutput()
        assertEquals("/etc/hosts.txt", rootResults)
    }

    @Test
    fun grepEmitsTrailingContextSkipsMidFileBinaryMatchesDisplayGlobAndIsolatesIoErrors() = runBlocking {
        val dir = File(root, "grep-edge").apply { mkdirs() }
        val srcDir = File(dir, "src").apply { mkdirs() }
        val trailFile = File(srcDir, "Trail.kt").apply {
            writeText("line1\nline2\nneedle hit\ntrail1\ntrail2\n")
        }
        val mainFile = File(srcDir, "Main.kt").apply {
            writeText("needle in main")
        }
        val midBinary = File(srcDir, "wide.dat").apply {
            val prefix = "a\n".repeat(2048).encodeToByteArray()
            writeBytes(prefix + byteArrayOf(0x00) + "\nneedle after nul\n".encodeToByteArray())
        }
        val badFile = File(srcDir, "aaa_bad.txt").apply {
            writeText("needle that throws before read")
        }

        val partialThrowFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): okio.Source {
                if (file.toString() == badFile.absolutePath) {
                    throw java.io.IOException("unreadable file")
                }
                return super.source(file)
            }
        }
        val grepTool = superPiGrepTool(partialThrowFs)

        val trailOut = grepTool.execute(
            "g-trail",
            buildJsonObject {
                put("pattern", "needle")
                put("path", dir.absolutePath)
                put("glob", "src/Trail.kt")
                put("context", 2)
            },
            null,
            null,
        ).textOutput()
        assertTrue(trailOut.contains("${trailFile.absolutePath}:3: needle hit"))
        assertTrue(trailOut.contains("${trailFile.absolutePath}-4- trail1"))
        assertTrue(trailOut.contains("${trailFile.absolutePath}-5- trail2"))

        val displayGlobOut = grepTool.execute(
            "g-disp",
            buildJsonObject {
                put("pattern", "needle")
                put("path", dir.absolutePath)
                put("glob", "*src/Main.kt")
            },
            null,
            null,
        ).textOutput()
        assertEquals("${mainFile.absolutePath}:1: needle in main", displayGlobOut)

        val isolated = grepTool.execute(
            "g-iso",
            buildJsonObject {
                put("pattern", "needle")
                put("path", dir.absolutePath)
            },
            null,
            null,
        )
        assertFalse(isolated.isError == true)
        val isolatedOut = isolated.textOutput()
        assertTrue(isolatedOut.contains("${mainFile.absolutePath}:1: needle in main"))
        assertTrue(isolatedOut.contains("${trailFile.absolutePath}:3: needle hit"))
        assertFalse(isolatedOut.contains(midBinary.name))
        assertFalse(isolatedOut.contains(badFile.name))
    }

    @Test
    fun grepAbortsCatastrophicBacktrackingRegexWithinBudget() = runBlocking {
        val dir = File(root, "grep-redos").apply { mkdirs() }
        val file = File(dir, "evil.txt").apply {
            writeText("a".repeat(60) + "!")
        }
        val grepTool = superPiGrepTool(FileSystem.SYSTEM)
        val started = System.currentTimeMillis()
        val result = grepTool.execute(
            "g-redos",
            buildJsonObject {
                put("pattern", "((a|b|a)+)+c")
                put("path", file.absolutePath)
            },
            null,
            null,
        )
        val elapsedMs = System.currentTimeMillis() - started
        assertTrue(result.isError == true)
        assertTrue(result.textOutput().contains("literal=true"))
        assertTrue("Expected fast bail-out, took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    private fun AgentToolResult.textOutput(): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}

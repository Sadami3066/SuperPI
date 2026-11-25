package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.ai.RunSignal
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.DEFAULT_MAX_BYTES
import io.github.whywood.superpi.sdk.coding.tools.formatSize
import io.github.whywood.superpi.sdk.coding.tools.textResult
import io.github.whywood.superpi.sdk.coding.tools.truncateHead
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

private const val LS_DEFAULT_LIMIT = 200
private const val LS_MAX_LIMIT = 500
private const val GREP_DEFAULT_LIMIT = 100
private const val GREP_MAX_LIMIT = 500
private const val FIND_DEFAULT_LIMIT = 200
private const val FIND_MAX_LIMIT = 1000
private const val MAX_SCAN_FILE_BYTES = 16L * 1024 * 1024
private const val MAX_GREP_LINE_CHARS = 500
private const val MAX_GREP_CONTEXT_LINES = 20
private const val MAX_WALK_FILES = 5000
private const val MAX_FIND_VISITED = 10000
private const val LINE_SIGNAL_CHECK_INTERVAL = 1024
private const val REGEX_SIGNAL_CHECK_INTERVAL = 4096
private const val MAX_REGEX_STEPS_PER_LINE = 2_000_000
private const val BINARY_SNIFF_BYTES = 8192
private val VIRTUAL_ROOT_DIRS = setOf("/proc", "/sys", "/dev", "/acct")
private val IGNORE_FILENAMES = listOf(".gitignore", ".ignore", ".fdignore")

private val lsParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put("description", "Explicit absolute directory path to list.")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", LS_MAX_LIMIT)
            put("description", "Maximum number of entries to return (default: $LS_DEFAULT_LIMIT, max: $LS_MAX_LIMIT).")
        }
    }
    put("required", buildJsonArray { add("path") })
}

private val grepParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "Search pattern (regex or literal string).")
        }
        putJsonObject("path") {
            put("type", "string")
            put("description", "Explicit absolute file or directory path to search.")
        }
        putJsonObject("glob") {
            put("type", "string")
            put("description", "Optional glob filter on file path/name, e.g. '*.kt' or '**/*.json'.")
        }
        putJsonObject("ignoreCase") {
            put("type", "boolean")
            put("description", "Case-insensitive search (default: false).")
        }
        putJsonObject("literal") {
            put("type", "boolean")
            put("description", "Treat pattern as literal string instead of regex (default: false).")
        }
        putJsonObject("context") {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", MAX_GREP_CONTEXT_LINES)
            put("description", "Number of context lines before and after each match (default: 0).")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", GREP_MAX_LIMIT)
            put("description", "Maximum number of matches to return (default: $GREP_DEFAULT_LIMIT, max: $GREP_MAX_LIMIT).")
        }
    }
    put("required", buildJsonArray {
        add("pattern")
        add("path")
    })
}

private val findParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "Glob pattern to match file paths or names, e.g. '*.pdf', '**/*.kt', or '*backup*'.")
        }
        putJsonObject("path") {
            put("type", "string")
            put("description", "Explicit absolute directory path to search.")
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", FIND_MAX_LIMIT)
            put("description", "Maximum number of matching paths to return (default: $FIND_DEFAULT_LIMIT, max: $FIND_MAX_LIMIT).")
        }
    }
    put("required", buildJsonArray {
        add("pattern")
        add("path")
    })
}

internal fun superPiLsTool(fs: FileSystem): CodingTool = CodingTool(
    name = "ls",
    description = "List entries in an explicit absolute Android directory path (supports Root access when enabled). Returns sorted absolute paths with '/' suffix for directories.",
    parameters = lsParameters,
    label = "ls",
    promptSnippet = "List directory contents",
    promptGuidelines = listOf(ABSOLUTE_PATH_GUIDELINE),
    prepareArguments = { args -> prepareAbsolutePathArg(args) },
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        runQueryTool {
            val pathStr = requireAbsolutePath(args.queryRequiredString("path"))
            val limit = (args.queryIntOrNull("limit") ?: LS_DEFAULT_LIMIT).coerceIn(1, LS_MAX_LIMIT)
            val inputPath = pathStr.toPath(normalize = true)
            val resolved = resolveTargetPath(fs, inputPath)
                ?: throw IllegalArgumentException("Path not found: $pathStr")
            if (!resolved.metadata.isDirectory) {
                throw IllegalArgumentException("Not a directory: $pathStr")
            }
            val entries = try {
                fs.list(resolved.path)
            } catch (error: Throwable) {
                throw IllegalArgumentException("Cannot read directory: ${error.message ?: pathStr}")
            }
            val sorted = entries.sortedWith(compareBy { it.name.lowercase() })
            val prefix = pathStr.trimEnd('/')
            val results = mutableListOf<String>()
            var limitReached = false
            for (entry in sorted) {
                signal?.throwIfAborted()
                if (results.size >= limit) {
                    limitReached = true
                    break
                }
                val meta = runCatching { fs.metadataOrNull(entry) }.getOrNull()
                val suffix = if (meta?.isDirectory == true) "/" else ""
                results += "$prefix/${entry.name}$suffix"
            }
            if (results.isEmpty()) {
                textResult("(empty directory)")
            } else {
                val truncation = truncateHead(results.joinToString("\n"), maxLines = Int.MAX_VALUE, maxBytes = DEFAULT_MAX_BYTES)
                val notices = mutableListOf<String>()
                if (limitReached) notices += "$limit entries limit reached. Use limit=${(limit * 2).coerceAtMost(LS_MAX_LIMIT)} for more"
                if (truncation.truncated) notices += "${formatSize(DEFAULT_MAX_BYTES)} limit reached"
                val output = if (notices.isEmpty()) truncation.text else truncation.text + "\n\n[" + notices.joinToString(". ") + "]"
                val details = buildJsonObject {
                    if (limitReached) put("entryLimitReached", limit)
                    if (truncation.truncated) {
                        putJsonObject("truncation") {
                            put("truncated", true)
                            truncation.truncatedBy?.let { put("truncatedBy", it) }
                        }
                    }
                }
                textResult(output, details.takeIf { it.isNotEmpty() })
            }
        }
    },
)

internal fun superPiGrepTool(
    fs: FileSystem,
    maxScanFileBytes: Long = MAX_SCAN_FILE_BYTES,
): CodingTool = CodingTool(
    name = "grep",
    description = "Search file contents in an explicit absolute path (supports Root access when enabled). Returns <absolute_path>:<line>: <text>. Respects .gitignore and skips binaries and kernel virtual filesystems.",
    parameters = grepParameters,
    label = "grep",
    promptSnippet = "Search file contents",
    promptGuidelines = listOf(ABSOLUTE_PATH_GUIDELINE),
    prepareArguments = { args -> prepareAbsolutePathArg(args) },
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        runQueryTool {
            val pattern = args.queryRequiredString("pattern")
            val pathStr = requireAbsolutePath(args.queryRequiredString("path"))
            val globPattern = args.queryStringOrNull("glob")?.takeIf { it.isNotEmpty() }
            val ignoreCase = args.queryBooleanOrNull("ignoreCase") ?: false
            val literal = args.queryBooleanOrNull("literal") ?: false
            val contextLines = (args.queryIntOrNull("context") ?: 0).coerceIn(0, MAX_GREP_CONTEXT_LINES)
            val limit = (args.queryIntOrNull("limit") ?: GREP_DEFAULT_LIMIT).coerceIn(1, GREP_MAX_LIMIT)
            val resolved = resolveTargetPath(fs, pathStr.toPath(normalize = true))
                ?: throw IllegalArgumentException("Path not found: $pathStr")
            val matcher = buildPatternMatcher(pattern, ignoreCase, literal, signal)
            val fileFilter = globPattern?.let { buildPathGlobMatcher(it) }
            val prefix = pathStr.trimEnd('/')
            val outputLines = mutableListOf<String>()
            var matchCount = 0
            var skippedLargeFiles = 0
            var linesTruncated = false
            var walkedLimitReached = false

            if (resolved.metadata.isDirectory) {
                walkedLimitReached = walkFilesBounded(
                    fs = fs,
                    root = resolved.path,
                    displayRoot = prefix,
                    maxFiles = MAX_WALK_FILES,
                    signal = signal,
                ) { file, displayPath, meta ->
                    val relative = displayPath.removePrefix("$prefix/").removePrefix("/")
                    if (fileFilter != null && !fileFilter(relative) && !fileFilter(displayPath)) {
                        return@walkFilesBounded true
                    }
                    val size = meta.size
                    if (size != null && size > maxScanFileBytes) {
                        skippedLargeFiles++
                        return@walkFilesBounded true
                    }
                    val scan = scanFileLines(
                        fs = fs,
                        file = file,
                        displayPath = displayPath,
                        matches = matcher,
                        contextLines = contextLines,
                        remaining = limit - matchCount,
                        signal = signal,
                        outputLines = outputLines,
                    )
                    matchCount += scan.matchesAdded
                    if (scan.linesTruncated) linesTruncated = true
                    matchCount < limit
                }
            } else {
                val size = resolved.metadata.size
                if (size != null && size > maxScanFileBytes) {
                    skippedLargeFiles++
                } else {
                    val scan = scanFileLines(
                        fs = fs,
                        file = resolved.path,
                        displayPath = pathStr,
                        matches = matcher,
                        contextLines = contextLines,
                        remaining = limit,
                        signal = signal,
                        outputLines = outputLines,
                    )
                    matchCount += scan.matchesAdded
                    if (scan.linesTruncated) linesTruncated = true
                }
            }

            if (matchCount == 0) {
                val extra = buildList {
                    if (skippedLargeFiles > 0) {
                        val maxBytes = maxScanFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        add("$skippedLargeFiles file(s) skipped: larger than ${formatSize(maxBytes)}")
                    }
                    if (walkedLimitReached) add("stopped after $MAX_WALK_FILES files; narrow the search path")
                }
                val text = if (extra.isEmpty()) "No matches found" else "No matches found (${extra.joinToString("; ")})"
                textResult(text)
            } else {
                val matchLimitReached = matchCount >= limit
                val truncation = truncateHead(outputLines.joinToString("\n"), maxLines = Int.MAX_VALUE, maxBytes = DEFAULT_MAX_BYTES)
                val notices = mutableListOf<String>()
                if (matchLimitReached) notices += "$limit matches limit reached. Use limit=${(limit * 2).coerceAtMost(GREP_MAX_LIMIT)} for more, or refine pattern"
                if (walkedLimitReached) notices += "Scanned max $MAX_WALK_FILES files; narrow path to search deeper"
                if (truncation.truncated) notices += "${formatSize(DEFAULT_MAX_BYTES)} limit reached"
                if (linesTruncated) notices += "Some lines truncated to $MAX_GREP_LINE_CHARS chars. Use read to inspect full lines"
                val output = if (notices.isEmpty()) truncation.text else truncation.text + "\n\n[" + notices.joinToString(". ") + "]"
                val details = buildJsonObject {
                    if (matchLimitReached) put("matchLimitReached", limit)
                    if (linesTruncated) put("linesTruncated", true)
                    if (truncation.truncated) {
                        putJsonObject("truncation") {
                            put("truncated", true)
                            truncation.truncatedBy?.let { put("truncatedBy", it) }
                        }
                    }
                }
                textResult(output, details.takeIf { it.isNotEmpty() })
            }
        }
    },
)

internal fun superPiFindTool(fs: FileSystem): CodingTool = CodingTool(
    name = "find",
    description = "Find files by glob pattern under an explicit absolute Android directory path (supports Root access when enabled). Respects .gitignore and skips kernel virtual filesystems.",
    parameters = findParameters,
    label = "find",
    promptSnippet = "Find files by pattern",
    promptGuidelines = listOf(ABSOLUTE_PATH_GUIDELINE),
    prepareArguments = { args -> prepareAbsolutePathArg(args) },
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        runQueryTool {
            val pattern = args.queryRequiredString("pattern")
            val pathStr = requireAbsolutePath(args.queryRequiredString("path"))
            val limit = (args.queryIntOrNull("limit") ?: FIND_DEFAULT_LIMIT).coerceIn(1, FIND_MAX_LIMIT)
            val resolved = resolveTargetPath(fs, pathStr.toPath(normalize = true))
                ?: throw IllegalArgumentException("Path not found: $pathStr")
            val matcher = buildPathGlobMatcher(pattern)
            val prefix = pathStr.trimEnd('/')
            val results = mutableListOf<String>()
            var walkedLimitReached = false
            if (resolved.metadata.isDirectory) {
                walkedLimitReached = walkFilesBounded(
                    fs = fs,
                    root = resolved.path,
                    displayRoot = prefix,
                    maxFiles = MAX_FIND_VISITED,
                    signal = signal,
                ) { _, displayPath, _ ->
                    val relative = displayPath.removePrefix("$prefix/").removePrefix("/")
                    if (matcher(relative) || matcher(displayPath)) {
                        results += displayPath
                        results.size < limit
                    } else {
                        true
                    }
                }
            } else if (matcher(resolved.path.name) || matcher(pathStr)) {
                results += pathStr
            }

            if (results.isEmpty()) {
                val text = if (walkedLimitReached) {
                    "No files found matching pattern (stopped after $MAX_FIND_VISITED entries; narrow the path)"
                } else {
                    "No files found matching pattern"
                }
                textResult(text)
            } else {
                val limitReached = results.size >= limit
                val truncation = truncateHead(results.joinToString("\n"), maxLines = Int.MAX_VALUE, maxBytes = DEFAULT_MAX_BYTES)
                val notices = mutableListOf<String>()
                if (limitReached) notices += "$limit results limit reached. Use limit=${(limit * 2).coerceAtMost(FIND_MAX_LIMIT)} for more, or refine pattern"
                if (walkedLimitReached) notices += "Scanned max $MAX_FIND_VISITED entries; narrow path to search deeper"
                if (truncation.truncated) notices += "${formatSize(DEFAULT_MAX_BYTES)} limit reached"
                val output = if (notices.isEmpty()) truncation.text else truncation.text + "\n\n[" + notices.joinToString(". ") + "]"
                val details = buildJsonObject {
                    if (limitReached) put("resultLimitReached", limit)
                    if (truncation.truncated) {
                        putJsonObject("truncation") {
                            put("truncated", true)
                            truncation.truncatedBy?.let { put("truncatedBy", it) }
                        }
                    }
                }
                textResult(output, details.takeIf { it.isNotEmpty() })
            }
        }
    },
)

private suspend fun runQueryTool(block: suspend () -> AgentToolResult): AgentToolResult = try {
    block()
} catch (error: RequestAbortedException) {
    throw error
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    textResult(error.message ?: "Query failed", isError = true)
}

private data class ResolvedPathTarget(val path: Path, val metadata: FileMetadata)

private fun resolveTargetPath(fs: FileSystem, rawPath: Path): ResolvedPathTarget? {
    val direct = runCatching { fs.metadataOrNull(rawPath) }.getOrNull() ?: return null
    if (direct.symlinkTarget == null) return ResolvedPathTarget(rawPath, direct)
    val canonical = runCatching { fs.canonicalize(rawPath) }.getOrNull() ?: return ResolvedPathTarget(rawPath, direct)
    val targetMeta = runCatching { fs.metadataOrNull(canonical) }.getOrNull() ?: return ResolvedPathTarget(rawPath, direct)
    return ResolvedPathTarget(canonical, targetMeta)
}

private fun prepareAbsolutePathArg(args: JsonObject): JsonObject {
    val raw = args.queryStringOrNull("path")
        ?: throw IllegalArgumentException("path is required and must be an explicit absolute path")
    return JsonObject(args + ("path" to JsonPrimitive(requireAbsolutePath(raw))))
}

private suspend fun walkFilesBounded(
    fs: FileSystem,
    root: Path,
    displayRoot: String,
    maxFiles: Int,
    signal: RunSignal?,
    onFile: suspend (Path, String, FileMetadata) -> Boolean,
): Boolean {
    val canonicalRoot = runCatching { fs.canonicalize(root).toString() }.getOrNull() ?: root.toString()
    val allowVirtual = VIRTUAL_ROOT_DIRS.any { v ->
        canonicalRoot == v || canonicalRoot.startsWith("$v/")
    }
    var visitedCount = 0
    var stoppedByBudget = false

    suspend fun recurse(
        dir: Path,
        canonicalDir: String,
        displayDir: String,
        inheritedRules: List<IgnoreRule>,
        relBase: String,
    ): Boolean {
        signal?.throwIfAborted()
        val rawChildren = runCatching { fs.list(dir) }.getOrNull()?.sortedWith(compareBy { it.name.lowercase() })
            ?: return true
        val localRules = readIgnoreRulesFromChildren(fs, rawChildren, relBase)
        val effectiveRules = if (localRules.isEmpty()) inheritedRules else inheritedRules + localRules
        val dirs = mutableListOf<Triple<Path, String, String>>()
        val dirDisplays = mutableListOf<String>()
        for (child in rawChildren) {
            visitedCount++
            if (visitedCount % 64 == 0) signal?.throwIfAborted()
            if (visitedCount > maxFiles) {
                stoppedByBudget = true
                return false
            }
            val name = child.name
            if (name == ".git") continue
            val displayChild = if (displayDir.isEmpty()) "/$name" else "$displayDir/$name"
            val canonicalChild = if (canonicalDir == "/") "/$name" else "$canonicalDir/$name"
            if (!allowVirtual && (displayChild in VIRTUAL_ROOT_DIRS || canonicalChild in VIRTUAL_ROOT_DIRS)) continue
            val meta = runCatching { fs.metadataOrNull(child) }.getOrNull() ?: continue
            val relPath = if (relBase.isEmpty()) name else "$relBase/$name"
            if (isPathIgnored(effectiveRules, relPath, meta.isDirectory)) continue
            if (meta.isDirectory) {
                dirs += Triple(child, canonicalChild, relPath)
                dirDisplays += displayChild
            } else if (meta.isRegularFile) {
                if (!onFile(child, displayChild, meta)) return false
            }
        }
        for (i in dirs.indices) {
            val (subDir, subCanonical, subRel) = dirs[i]
            if (!recurse(subDir, subCanonical, dirDisplays[i], effectiveRules, subRel)) return false
        }
        return true
    }

    recurse(root, canonicalRoot.trimEnd('/').ifEmpty { "/" }, displayRoot, emptyList(), "")
    return stoppedByBudget
}

private data class IgnoreRule(
    val basePath: String,
    val regex: Regex,
    val negated: Boolean,
    val directoryOnly: Boolean,
    val basenameOnly: Boolean,
)

private fun readIgnoreRulesFromChildren(fs: FileSystem, children: List<Path>, basePath: String): List<IgnoreRule> {
    val byName = children.associateBy { it.name }
    val rules = mutableListOf<IgnoreRule>()
    for (ignoreName in IGNORE_FILENAMES) {
        val file = byName[ignoreName] ?: continue
        val text = runCatching { fs.read(file) { readUtf8() } }.getOrNull() ?: continue
        rules += parseIgnoreRules(text, basePath)
    }
    return rules
}

private fun parseIgnoreRules(text: String, basePath: String): List<IgnoreRule> {
    val rules = mutableListOf<IgnoreRule>()
    for (rawLine in text.split("\n")) {
        var line = rawLine.removeSuffix("\r").trimEnd(' ', '\t')
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("\\#")) line = line.substring(1)
        var negated = false
        if (line.startsWith("!")) {
            negated = true
            line = line.substring(1)
        } else if (line.startsWith("\\!")) {
            line = line.substring(1)
        }
        if (line.isEmpty()) continue
        var directoryOnly = false
        if (line.endsWith("/")) {
            directoryOnly = true
            line = line.dropLast(1)
        }
        if (line.isEmpty()) continue
        val anchored = line.startsWith("/")
        val body = if (anchored) line.substring(1) else line
        if (body.isEmpty()) continue
        val basenameOnly = !anchored && !body.contains('/')
        rules += IgnoreRule(
            basePath = basePath,
            regex = Regex("^" + simpleGlobToRegex(body) + "$"),
            negated = negated,
            directoryOnly = directoryOnly,
            basenameOnly = basenameOnly,
        )
    }
    return rules
}

private fun isPathIgnored(rules: List<IgnoreRule>, relativePath: String, isDirectory: Boolean): Boolean {
    for (index in rules.indices.reversed()) {
        val rule = rules[index]
        if (rule.directoryOnly && !isDirectory) continue
        val target = if (rule.basenameOnly) {
            relativePath.substringAfterLast('/')
        } else {
            if (rule.basePath.isNotEmpty() && !relativePath.startsWith(rule.basePath + "/")) continue
            if (rule.basePath.isEmpty()) relativePath else relativePath.substring(rule.basePath.length + 1)
        }
        if (rule.regex.matches(target)) return !rule.negated
    }
    return false
}

private class ScanOutcome(val matchesAdded: Int, val linesTruncated: Boolean)

private suspend fun scanFileLines(
    fs: FileSystem,
    file: Path,
    displayPath: String,
    matches: (String) -> Boolean,
    contextLines: Int,
    remaining: Int,
    signal: RunSignal?,
    outputLines: MutableList<String>,
): ScanOutcome {
    if (remaining <= 0) return ScanOutcome(0, false)
    val source = runCatching { fs.source(file).buffer() }.getOrNull() ?: return ScanOutcome(0, false)
    val startOutputSize = outputLines.size
    val ring = ArrayDeque<Pair<Int, String>>()
    var afterRemaining = 0
    var lastEmitted = 0
    var added = 0
    var linesTruncated = false
    var binary = false
    try {
        val peek = source.peek()
        peek.request(BINARY_SNIFF_BYTES.toLong())
        val sniffBuffer = peek.buffer
        val sniffLimit = minOf(sniffBuffer.size, BINARY_SNIFF_BYTES.toLong())
        for (i in 0L until sniffLimit) {
            if (sniffBuffer[i] == 0.toByte()) {
                return ScanOutcome(0, false)
            }
        }
        var lineNumber = 0
        while (true) {
            val raw = source.readUtf8Line() ?: break
            lineNumber++
            if (lineNumber % LINE_SIGNAL_CHECK_INTERVAL == 0) signal?.throwIfAborted()
            val line = raw.removeSuffix("\r")
            if (line.indexOf('\u0000') >= 0) {
                binary = true
                break
            }
            val hit = if (added < remaining) matches(line) else false
            if (contextLines == 0) {
                if (!hit) continue
                val (text, cut) = truncateLineText(line)
                if (cut) linesTruncated = true
                outputLines += "$displayPath:$lineNumber: $text"
                added++
                if (added >= remaining) break
            } else {
                if (hit) {
                    for ((pendingNumber, pendingLine) in ring) {
                        if (pendingNumber <= lastEmitted) continue
                        val (text, cut) = truncateLineText(pendingLine)
                        if (cut) linesTruncated = true
                        outputLines += "$displayPath-$pendingNumber- $text"
                        lastEmitted = pendingNumber
                    }
                    val (text, cut) = truncateLineText(line)
                    if (cut) linesTruncated = true
                    outputLines += "$displayPath:$lineNumber: $text"
                    lastEmitted = lineNumber
                    added++
                    afterRemaining = contextLines
                    if (added >= remaining && afterRemaining <= 0) break
                } else if (afterRemaining > 0) {
                    val (text, cut) = truncateLineText(line)
                    if (cut) linesTruncated = true
                    outputLines += "$displayPath-$lineNumber- $text"
                    lastEmitted = lineNumber
                    afterRemaining--
                    if (added >= remaining && afterRemaining <= 0) break
                } else if (added >= remaining) {
                    break
                }
                ring.addLast(lineNumber to line)
                while (ring.size > contextLines) ring.removeFirst()
            }
        }
    } catch (aborted: RequestAbortedException) {
        throw aborted
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        while (outputLines.size > startOutputSize) outputLines.removeAt(outputLines.size - 1)
        return ScanOutcome(0, false)
    } finally {
        runCatching { source.close() }
    }
    if (binary) {
        while (outputLines.size > startOutputSize) outputLines.removeAt(outputLines.size - 1)
        return ScanOutcome(0, false)
    }
    return ScanOutcome(added, linesTruncated)
}

private fun truncateLineText(line: String): Pair<String, Boolean> {
    if (line.length <= MAX_GREP_LINE_CHARS) return line to false
    return line.take(MAX_GREP_LINE_CHARS) + "..." to true
}

private class RegexWorkLimitException : RuntimeException(
    "Regex search exceeded backtracking limit; simplify pattern or set literal=true",
)

private class RegexStepCounter(var count: Int = 0)

private class BoundedCharSequence(
    private val delegate: CharSequence,
    private val maxSteps: Int,
    private val signal: RunSignal?,
    private val counter: RegexStepCounter = RegexStepCounter(),
) : CharSequence {
    override val length: Int
        get() = delegate.length

    override fun get(index: Int): Char {
        counter.count += 1
        if (counter.count % REGEX_SIGNAL_CHECK_INTERVAL == 0) {
            signal?.throwIfAborted()
        }
        if (counter.count > maxSteps) {
            throw RegexWorkLimitException()
        }
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        BoundedCharSequence(delegate.subSequence(startIndex, endIndex), maxSteps, signal, counter)
}

private fun buildPatternMatcher(
    pattern: String,
    ignoreCase: Boolean,
    literal: Boolean,
    signal: RunSignal?,
): (String) -> Boolean {
    if (literal) {
        return { line -> line.contains(pattern, ignoreCase = ignoreCase) }
    }
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val regex = try {
        Regex(pattern, options)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid regex pattern: ${error.message}")
    }
    return { line -> regex.containsMatchIn(BoundedCharSequence(line, MAX_REGEX_STEPS_PER_LINE, signal)) }
}

private fun buildPathGlobMatcher(pattern: String): (String) -> Boolean {
    if (pattern.isEmpty()) return { false }
    val trimmed = pattern.removePrefix("./")
    if (!trimmed.contains('/')) {
        val regex = Regex("^" + simpleGlobToRegex(trimmed) + "$")
        return { path -> regex.matches(path.substringAfterLast('/')) }
    }
    val body = trimmed.removePrefix("/")
    val effective = if (trimmed.startsWith("/") || body.startsWith("**/") || body == "**") body else "**/$body"
    val regex = Regex("^" + simpleGlobToRegex(effective) + "$")
    return { path -> regex.matches(path.removePrefix("/")) }
}

private fun simpleGlobToRegex(glob: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < glob.length) {
        val ch = glob[i]
        when {
            ch == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                if (i + 2 < glob.length && glob[i + 2] == '/') {
                    sb.append("(?:.*/)?")
                    i += 3
                } else {
                    sb.append(".*")
                    i += 2
                }
            }
            ch == '*' -> {
                sb.append("[^/]*")
                i++
            }
            ch == '?' -> {
                sb.append("[^/]")
                i++
            }
            ch in ".^$+{}[]()|\\" -> {
                sb.append('\\').append(ch)
                i++
            }
            else -> {
                sb.append(ch)
                i++
            }
        }
    }
    return sb.toString()
}

private fun JsonObject.queryStringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.queryRequiredString(name: String): String =
    queryStringOrNull(name)?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required argument: $name")

private fun JsonObject.queryIntOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

private fun JsonObject.queryBooleanOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

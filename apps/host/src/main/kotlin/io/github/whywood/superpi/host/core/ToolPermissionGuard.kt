package io.github.whywood.superpi.host.core

import io.github.whywood.superpi.runtime.QuestionCenter
import io.github.whywood.superpi.runtime.QuestionOption
import io.github.whywood.superpi.runtime.QuestionOutcome
import io.github.whywood.superpi.runtime.SessionBinding
import io.github.whywood.superpi.runtime.UserQuestion
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class PermissionMode(val wire: String) {
    READ_ONLY("read_only"),
    ASK_COMMANDS("ask_commands"),
    WORKSPACE_AUTO("workspace_auto"),
    FULL_AUTO("full_auto"),
    ;

    companion object {
        fun parseWireOrNull(raw: String?): PermissionMode? {
            val clean = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.wire.equals(clean, ignoreCase = true) }
        }

        fun fromWire(raw: String?): PermissionMode = parseWireOrNull(raw) ?: WORKSPACE_AUTO
    }
}

/**
 * Unified 4-tier tool permission enforcement across all local sessions & workers:
 * - `read_only`: denies mutating file tools and shell/job execution tools.
 * - `ask_commands`: allows in-workspace edits, prompts via QuestionCenter before shell/job/outside-workspace writes.
 * - `workspace_auto`: auto-runs in-workspace edits and normal commands; prompts for destructive commands or outside-workspace writes.
 * - `full_auto`: executes without interactive prompts.
 */
object ToolPermissionGuard {
    private val FILE_MUTATION_TOOLS = setOf("write", "edit")
    private val SHELL_TOOLS = setOf("bash", "job", "job_start")
    private val GUARDED_TOOLS = FILE_MUTATION_TOOLS + SHELL_TOOLS

    private val DESTRUCTIVE_PATTERNS = listOf(
        Regex("""(?:^|[\s;|&(])(?:/(?:usr/)?bin/)?rm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+(/|~|\${'$'}HOME|\.\.)"""),
        Regex("""(?:^|[\s;|&(])(?:/(?:usr/)?bin/)?rm\s+(?:-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r|-r\s+-f|-f\s+-r|--recursive\s+--force|--force\s+--recursive)\b"""),
        Regex("""\bsudo\b"""),
        Regex("""\bgit\s+push\s+.*(--force(?:-with-lease)?|-f)\b"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\s+-[a-zA-Z]*f"""),
        Regex("""\b(mkfs|fdisk|parted|shutdown|reboot|poweroff|halt)\b"""),
        Regex("""\bdd\s+if="""),
        Regex("""\bchmod\s+-R\s+777\b"""),
        Regex("""\b(curl|wget)\b[^|]*\|\s*(sh|bash|zsh)\b"""),
    )

    fun isDestructiveCommand(command: String): Boolean {
        val norm = command.trim()
        if (norm.isEmpty()) return false
        return DESTRUCTIVE_PATTERNS.any { it.containsMatchIn(norm) }
    }

    fun isOutsideWorkspace(workspaceRoot: File, targetPath: String?): Boolean {
        val clean = targetPath?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val rootReal = runCatching { workspaceRoot.toPath().toRealPath() }.getOrNull() ?: return true
        val candidate = File(clean)
        val raw = if (candidate.isAbsolute) candidate.toPath().normalize() else rootReal.resolve(clean).normalize()
        // Walk up using NOFOLLOW_LINKS so broken/dangling symlinks are never silently skipped.
        var ancestor: Path? = raw
        val trailing = mutableListOf<Path>()
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor.fileName?.let { trailing.add(0, it) }
            ancestor = ancestor.parent
        }
        val existing = ancestor ?: return true
        if (Files.isSymbolicLink(existing)) {
            // If symlink is broken or points outside rootReal, treat as outside!
            val linkTargetReal = runCatching { existing.toRealPath() }.getOrNull() ?: return true
            if (!linkTargetReal.startsWith(rootReal)) return true
        }
        val resolvedReal = runCatching {
            var base = existing.toRealPath()
            for (seg in trailing) base = base.resolve(seg)
            base.normalize()
        }.getOrNull() ?: return true
        return !resolvedReal.startsWith(rootReal)
    }

    fun wrapTool(
        tool: CodingTool,
        binding: SessionBinding,
        workspaceRoot: File,
        modeProvider: () -> PermissionMode,
        questionCenter: QuestionCenter,
        planFirstCheck: (sessionId: String) -> Boolean = { false },
    ): CodingTool {
        if (tool.name !in GUARDED_TOOLS) return tool
        val original = tool
        return CodingTool(
            name = original.name,
            description = original.description,
            parameters = original.parameters,
            label = original.label,
            promptSnippet = original.promptSnippet,
            promptGuidelines = original.promptGuidelines,
            prepareArguments = original.prepareArguments,
            execute = { callId, args, signal, update ->
                val sessionId = binding.current()?.sessionId.orEmpty()
                val effectiveMode = if (sessionId.isNotBlank() && planFirstCheck(sessionId)) {
                    PermissionMode.READ_ONLY
                } else {
                    modeProvider()
                }
                val denial = checkAndApprove(
                    toolName = original.name,
                    args = args,
                    mode = effectiveMode,
                    workspaceRoot = workspaceRoot,
                    sessionId = sessionId,
                    callId = callId,
                    questionCenter = questionCenter,
                )
                if (denial != null) {
                    throw IllegalStateException(denial)
                }
                original.execute(callId, args, signal, update)
            },
        )
    }

    private suspend fun checkAndApprove(
        toolName: String,
        args: JsonObject,
        mode: PermissionMode,
        workspaceRoot: File,
        sessionId: String,
        callId: String,
        questionCenter: QuestionCenter,
    ): String? {
        // For the unified `job` tool, read-only queries (list/status/output/read) are safe;
        // only `start` (or missing/unknown) executes shell commands and `kill` mutates processes.
        val isJobTool = toolName == "job"
        val jobAction = if (isJobTool) {
            (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase(Locale.US).orEmpty()
        } else {
            ""
        }
        val isReadOnlyJobAction = isJobTool && jobAction in setOf("list", "status", "output", "read")
        if (isReadOnlyJobAction) return null

        if (mode == PermissionMode.FULL_AUTO) return null
        if (mode == PermissionMode.READ_ONLY) {
            return "Current permission mode is Read-Only; '$toolName' is not permitted. Switch permission mode or approve the plan first."
        }

        val isJobExec = isJobTool && jobAction != "kill"
        val isShellExec = toolName == "bash" || toolName == "job_start" || isJobExec
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull?.trim()
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim()
        val outsideWs = toolName in FILE_MUTATION_TOOLS && isOutsideWorkspace(workspaceRoot, path)
        val destructive = isShellExec && !command.isNullOrEmpty() && isDestructiveCommand(command)

        val needsPrompt = when (mode) {
            PermissionMode.ASK_COMMANDS -> isShellExec || outsideWs
            PermissionMode.WORKSPACE_AUTO -> destructive || outsideWs
            PermissionMode.READ_ONLY, PermissionMode.FULL_AUTO -> false
        }
        if (!needsPrompt) return null
        if (sessionId.isBlank()) {
            return "Approval required for '$toolName' but no active session is bound."
        }

        val detail = when {
            !command.isNullOrEmpty() -> command.take(240)
            !path.isNullOrEmpty() -> path.take(240)
            else -> toolName
        }
        val header = if (destructive) "高风险命令确认" else if (outsideWs) "工作区外写入确认" else "命令执行确认"
        val prompt = buildString {
            append("工具 `").append(toolName).append("` 请求执行：\n`")
            append(detail)
            append("`")
        }
        val outcome = questionCenter.ask(
            sessionId = sessionId,
            toolCallId = callId,
            questions = listOf(
                UserQuestion(
                    id = "perm-approve",
                    header = header,
                    question = prompt,
                    options = listOf(
                        QuestionOption(label = "允许执行", description = "本次放行该操作"),
                        QuestionOption(label = "拒绝", description = "取消该工具调用"),
                    ),
                    multiSelect = false,
                ),
            ),
        )
        return when (outcome) {
            is QuestionOutcome.Answered -> {
                val selected = outcome.answers.firstOrNull()?.selected?.firstOrNull()?.lowercase(Locale.US).orEmpty()
                val approved = selected.contains("允许") || selected.contains("allow") || selected.contains("yes")
                if (approved) null else "User denied permission for '$toolName'."
            }
            QuestionOutcome.Cancelled -> "Permission request was dismissed by the user."
        }
    }
}

package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.formatSize
import io.github.whywood.superpi.sdk.coding.tools.textResult
import io.github.whywood.superpi.sdk.coding.tools.truncateTail
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val JOB_ACTION_VALUES = buildJsonArray {
    add(JsonPrimitive("start"))
    add(JsonPrimitive("output"))
    add(JsonPrimitive("list"))
    add(JsonPrimitive("kill"))
}

private val jobParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put("enum", JOB_ACTION_VALUES)
            put("description", "start, output, list or kill")
        }
        putJsonObject("command") {
            put("type", "string")
            put("description", "Shell command to run in the background, required for start")
        }
        putJsonObject("label") {
            put("type", "string")
            put("description", "Short label shown in the jobs list (defaults to the command)")
        }
        putJsonObject("jobId") {
            put("type", "string")
            put("description", "Background job id from start or list; required for output and kill")
        }
        putJsonObject("wait") {
            put("type", "boolean")
            put("description", "For output: wait for new output or job completion instead of returning immediately")
        }
        putJsonObject("timeoutMs") {
            put("type", "integer")
            put("description", "For output with wait: maximum wait in milliseconds (default 30000, clamped to 1..600000)")
        }
        putJsonObject("reason") {
            put("type", "string")
            put("description", "Optional reason recorded on the job for kill")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("action")) })
}

fun jobTools(binding: SessionBinding, registry: JobRegistry): List<CodingTool> =
    listOf(jobTool(binding, registry))

private fun jobTool(binding: SessionBinding, registry: JobRegistry): CodingTool = CodingTool(
    name = "job",
    description = "Start, read, list or cancel background shell jobs in the current conversation. action=start returns a job id; action=output returns output since the previous read plus a status line; action=list shows this conversation's jobs; action=kill requests cancellation.",
    parameters = jobParameters,
    label = "job",
    execute = { _, args, signal, _ ->
        signal?.throwIfAborted()
        jobToolResult {
            when (val action = args.requiredJobString("action").lowercase()) {
                "start" -> jobStart(binding, registry, args)
                "output" -> jobOutput(binding, registry, args)
                "list" -> jobList(binding, registry, args)
                "kill" -> jobKill(binding, registry, args)
                else -> textResult("action must be start, output, list or kill.", isError = true)
            }
        }
    },
)

private suspend fun jobStart(binding: SessionBinding, registry: JobRegistry, args: JsonObject): AgentToolResult {
    validateActionArgs(
        label = "job(action=start)",
        args = args,
        parameters = jobParameters,
        allowedFields = setOf("action", "command", "label"),
        requiredFields = setOf("command"),
    )
    val session = activeJobSession(binding)
    val command = args.requiredJobString("command")
    val label = args.jobStringOrNull("label").orEmpty()
    val start = registry.start(session.sessionId, command, label, session.cwd)
    val jobId = start.id
    return if (jobId == null) {
        textResult(start.error ?: "failed to start job", isError = true)
    } else {
        textResult(
            "Started background job $jobId.\n\n" +
                "Use job with action=output and jobId=$jobId to read output, or action=kill to stop it.",
        )
    }
}

private suspend fun jobOutput(binding: SessionBinding, registry: JobRegistry, args: JsonObject): AgentToolResult {
    validateActionArgs(
        label = "job(action=output)",
        args = args,
        parameters = jobParameters,
        allowedFields = setOf("action", "jobId", "wait", "timeoutMs"),
        requiredFields = setOf("jobId"),
    )
    val session = activeJobSession(binding)
    val jobId = args.requiredJobString("jobId", "job_id")
    requireJobOwner(registry, session.sessionId, jobId)
    val wait = args.jobBoolOrNull("wait") ?: false
    require(wait || !args.containsKey("timeoutMs")) { "timeoutMs requires wait=true" }
    val timeout = args.jobLongOrNull("timeoutMs", "timeout_ms")
    val read = when {
        !wait -> registry.readIncremental(jobId)
        timeout == null -> registry.awaitRead(jobId, true)
        else -> registry.awaitRead(jobId, true, timeout)
    }
    val status = "[status: ${jobStatusText(read.status, read.exitCode)}]"
    val trimmed = read.output.trimEnd('\n')
    val tail = truncateTail(trimmed)
    val prefixes = buildList {
        if (read.dropped) add("[older output was dropped from the in-memory buffer]")
        if (tail.truncated) {
            val lines = if (tail.text.isEmpty()) 0 else tail.text.lineSequence().count()
            val bytes = tail.text.encodeToByteArray().size
            add("[output truncated to the last $lines lines (${formatSize(bytes)})]")
        }
    }
    val prefix = if (prefixes.isEmpty()) "" else prefixes.joinToString("\n") + "\n"
    val text = prefix + if (tail.text.isEmpty()) status else tail.text + "\n" + status
    return textResult(text, isError = read.status == JobStatus.FAILED)
}

private fun jobList(binding: SessionBinding, registry: JobRegistry, args: JsonObject): AgentToolResult {
    validateActionArgs("job(action=list)", args = args, parameters = jobParameters, allowedFields = setOf("action"))
    val session = activeJobSession(binding)
    val lines = registry.jobs.value
        .filter { it.sessionId == session.sessionId }
        .map { "${it.id} ${it.status.name.lowercase()} — ${it.label}" }
    return textResult(if (lines.isEmpty()) "no background jobs" else lines.joinToString("\n"))
}

private suspend fun jobKill(binding: SessionBinding, registry: JobRegistry, args: JsonObject): AgentToolResult {
    validateActionArgs(
        label = "job(action=kill)",
        args = args,
        parameters = jobParameters,
        allowedFields = setOf("action", "jobId", "reason"),
        requiredFields = setOf("jobId"),
    )
    val session = activeJobSession(binding)
    val jobId = args.requiredJobString("jobId", "job_id")
    requireJobOwner(registry, session.sessionId, jobId)
    val reason = args.jobStringOrNull("reason")
    val requested = registry.kill(jobId, reason)
    return textResult(if (requested) "Cancellation requested." else "The job has already finished.")
}

private fun requireJobOwner(registry: JobRegistry, sessionId: String, jobId: String) {
    require(registry.jobs.value.any { it.id == jobId && it.sessionId == sessionId }) { "Job not found in this conversation: $jobId" }
}

private fun activeJobSession(binding: SessionBinding): BoundSession =
    binding.current() ?: throw IllegalStateException("No session is bound")

private suspend fun jobToolResult(block: suspend () -> AgentToolResult): AgentToolResult =
    try {
        block()
    } catch (error: RequestAbortedException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        textResult(error.message ?: "background job operation failed", isError = true)
    }

private fun JsonObject.jobStringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.requiredJobString(vararg names: String): String =
    names.firstNotNullOfOrNull { name -> jobStringOrNull(name) }
        ?: throw IllegalArgumentException("Missing required argument: ${names.first()}")

private fun JsonObject.jobBoolOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.jobLongOrNull(vararg names: String): Long? {
    names.forEach { name ->
        val raw = (this[name] as? JsonPrimitive)?.contentOrNull ?: return@forEach
        return raw.toDoubleOrNull()?.toLong()
    }
    return null
}

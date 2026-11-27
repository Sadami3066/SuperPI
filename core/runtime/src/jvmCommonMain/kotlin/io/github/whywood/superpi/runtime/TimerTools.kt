package io.github.whywood.superpi.runtime

import io.github.whywood.superpi.sdk.ai.RequestAbortedException
import io.github.whywood.superpi.sdk.agent.AgentToolResult
import io.github.whywood.superpi.sdk.coding.tools.CodingTool
import io.github.whywood.superpi.sdk.coding.tools.textResult
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class TimerToolScope {
    SESSION_ONLY,
    SUPER_AND_WORKERS,
}

private val TOOL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

private val FREQUENCY_VALUES = buildJsonArray {
    add(JsonPrimitive("once"))
    add(JsonPrimitive("cron"))
}

private val TIMER_ACTION_VALUES = buildJsonArray {
    add(JsonPrimitive("create"))
    add(JsonPrimitive("list"))
    add(JsonPrimitive("update"))
    add(JsonPrimitive("delete"))
}

private fun buildTimerParameters(scope: TimerToolScope): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put("enum", TIMER_ACTION_VALUES)
            put("description", "create, list, update or delete")
        }
        putJsonObject("id") {
            put("type", "string")
            put("description", "Timer id from timer(action=list), required for update and delete")
        }
        putJsonObject("prompt") {
            put("type", "string")
            put("description", "Message sent to the conversation when the timer fires")
        }
        putJsonObject("frequency") {
            put("type", "string")
            put("enum", FREQUENCY_VALUES)
            put("description", "Trigger kind: once for a single date and time, cron for a five-field cron expression")
        }
        putJsonObject("name") {
            put("type", "string")
            put("description", "Display name for the timer (defaults to the prompt preview)")
        }
        putJsonObject("date") {
            put("type", "string")
            put("description", "Date in YYYY-MM-DD format, required for once")
        }
        putJsonObject("time") {
            put("type", "string")
            put("description", "Local time in HH:mm 24-hour format, required for once")
        }
        putJsonObject("cron") {
            put("type", "string")
            put("description", "Five-field cron expression in local time: minute hour day-of-month month day-of-week, e.g. 0 9 * * 1-5")
        }
        putJsonObject("enabled") {
            put("type", "boolean")
            put("description", "Whether the timer is enabled")
        }
        if (scope == TimerToolScope.SUPER_AND_WORKERS) {
            putJsonObject("workerId") {
                put("type", "string")
                put("description", "Optional worker id (e.g. wrk-...) to bind a created timer to that worker instead of Super router")
            }
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("action")) })
}

fun timerTools(
    service: TimerService,
    binding: SessionBinding,
    scope: TimerToolScope = TimerToolScope.SESSION_ONLY,
    targetResolver: (suspend (String) -> BoundSession?)? = null,
    hasCalendarTool: Boolean = true,
): List<CodingTool> = listOf(timerTool(service, binding, scope, targetResolver, hasCalendarTool))

private fun timerTool(
    service: TimerService,
    binding: SessionBinding,
    scope: TimerToolScope,
    targetResolver: (suspend (String) -> BoundSession?)?,
    hasCalendarTool: Boolean,
): CodingTool {
    val parameters = buildTimerParameters(scope)
    val boundSession = binding.current()
    val isWorker = boundSession != null && SuperRuntime.parseWorkerSessionId(boundSession.sessionId) != null
    val baseDescription = if (hasCalendarTool) {
        "Create, list, update or delete timers bound to this conversation. A timer triggers an automated conversation prompt on a schedule (unlike calendar, which manages events in the phone's Calendar app). frequency=once needs date/time; frequency=cron needs a five-field expression."
    } else {
        "Create, list, update or delete timers bound to this conversation. A timer triggers an automated conversation prompt on a schedule. frequency=once needs date/time; frequency=cron needs a five-field expression."
    }
    val fullDescription = buildString {
        append(baseDescription)
        if (scope == TimerToolScope.SUPER_AND_WORKERS) {
            append(" Lists and manages both Super router timers and worker timers; pass workerId on create to schedule a check directly on a specific worker.")
        } else if (isWorker) {
            append(" For recurring monitoring checks on a worker, if a scheduled check finds nothing new to report back to Super, reply ONLY with [NO_UPDATE].")
        }
    }
    return CodingTool(
        name = "timer",
        description = fullDescription,
        parameters = parameters,
        label = "timer",
        execute = { _, args, signal, _ ->
            signal?.throwIfAborted()
            timerToolResult {
                when (args.requiredString("action").lowercase()) {
                    "create" -> createTimer(service, binding, parameters, scope, targetResolver, args)
                    "list" -> listTimers(service, binding, parameters, scope, args)
                    "update" -> updateTimer(service, binding, parameters, scope, args)
                    "delete" -> deleteTimer(service, binding, parameters, scope, args)
                    else -> textResult("action must be create, list, update or delete.", isError = true)
                }
            }
        },
    )
}

private suspend fun createTimer(
    service: TimerService,
    binding: SessionBinding,
    parameters: JsonObject,
    scope: TimerToolScope,
    targetResolver: (suspend (String) -> BoundSession?)?,
    args: JsonObject,
): AgentToolResult {
    val allowed = buildSet {
        addAll(listOf("action", "prompt", "name", "frequency", "date", "time", "cron", "enabled"))
        if (scope == TimerToolScope.SUPER_AND_WORKERS) add("workerId")
    }
    validateActionArgs(
        label = "timer(action=create)",
        args = args,
        parameters = parameters,
        allowedFields = allowed,
        requiredFields = setOf("prompt", "frequency"),
    )
    val requestedWorkerId = if (scope == TimerToolScope.SUPER_AND_WORKERS) {
        args.stringOrNull("workerId")?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }
    val session = if (requestedWorkerId != null) {
        targetResolver?.invoke(requestedWorkerId)
            ?: throw IllegalArgumentException("Worker not found: $requestedWorkerId")
    } else {
        requireSession(binding)
    }
    val sessionFile = session.sessionFile
        ?: throw IllegalArgumentException("Current conversation is not saved yet; cannot create a timer")
    val prompt = args.requiredString("prompt")
    val frequency = args.requiredString("frequency")
    val name = args.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: promptPreview(prompt)
    val trigger = parseTrigger(args, base = null, requestedFrequency = frequency)
    val created = service.create(
        NewTimer(
            name = name,
            prompt = prompt,
            trigger = trigger,
            enabled = args.boolOrNull("enabled") ?: true,
            sessionId = session.sessionId,
            sessionFile = sessionFile,
            cwd = session.cwd,
        ),
    )
    return textResult(timerJson(created, includeTarget = scope == TimerToolScope.SUPER_AND_WORKERS).toString())
}

private suspend fun listTimers(
    service: TimerService,
    binding: SessionBinding,
    parameters: JsonObject,
    scope: TimerToolScope,
    args: JsonObject,
): AgentToolResult {
    validateActionArgs("timer(action=list)", args = args, parameters = parameters, allowedFields = setOf("action"))
    val session = requireSession(binding)
    val timers = service.list()
        .filter { belongsToScope(it.sessionId, session.sessionId, scope) }
        .sortedWith(compareBy({ it.nextRunAt ?: Long.MAX_VALUE }, { it.createdAt }))
    val result = buildJsonObject {
        putJsonArray("timers") {
            timers.forEach { add(timerJson(it, includeTarget = scope == TimerToolScope.SUPER_AND_WORKERS)) }
        }
    }
    return textResult(result.toString())
}

private suspend fun updateTimer(
    service: TimerService,
    binding: SessionBinding,
    parameters: JsonObject,
    scope: TimerToolScope,
    args: JsonObject,
): AgentToolResult {
    validateActionArgs(
        label = "timer(action=update)",
        args = args,
        parameters = parameters,
        allowedFields = setOf("action", "id", "prompt", "name", "frequency", "date", "time", "cron", "enabled"),
        requiredFields = setOf("id"),
    )
    val session = requireSession(binding)
    val id = args.requiredString("id")
    val existing = service.list()
        .firstOrNull { it.id == id && belongsToScope(it.sessionId, session.sessionId, scope) }
        ?: throw IllegalArgumentException("Timer not found: $id")
    val hasTriggerArgs = listOf("frequency", "date", "time", "cron").any { args.containsKey(it) }
    val trigger = if (hasTriggerArgs) {
        parseTrigger(args, base = existing.trigger, requestedFrequency = args.stringOrNull("frequency"))
    } else {
        null
    }
    require(args.keys.any { it !in setOf("id", "action") }) { "timer update requires at least one changed field" }
    val patch = TimerPatch(
        name = args.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() },
        prompt = args.stringOrNull("prompt")?.takeIf { it.isNotBlank() },
        trigger = trigger,
        enabled = args.boolOrNull("enabled"),
    )
    val updated = service.update(id, patch)
        ?: throw IllegalArgumentException("Timer not found: $id")
    return textResult(timerJson(updated, includeTarget = scope == TimerToolScope.SUPER_AND_WORKERS).toString())
}

private suspend fun deleteTimer(
    service: TimerService,
    binding: SessionBinding,
    parameters: JsonObject,
    scope: TimerToolScope,
    args: JsonObject,
): AgentToolResult {
    validateActionArgs(
        label = "timer(action=delete)",
        args = args,
        parameters = parameters,
        allowedFields = setOf("action", "id"),
        requiredFields = setOf("id"),
    )
    val session = requireSession(binding)
    val id = args.requiredString("id")
    val existing = service.list()
        .firstOrNull { it.id == id && belongsToScope(it.sessionId, session.sessionId, scope) }
        ?: throw IllegalArgumentException("Timer not found: $id")
    if (!service.delete(existing.id)) {
        throw IllegalArgumentException("Timer not found: $id")
    }
    val result = buildJsonObject {
        put("deleted", true)
        put("id", id)
    }
    return textResult(result.toString())
}

private fun belongsToScope(
    timerSessionId: String,
    currentSessionId: String,
    scope: TimerToolScope,
): Boolean = when (scope) {
    TimerToolScope.SESSION_ONLY -> timerSessionId == currentSessionId
    TimerToolScope.SUPER_AND_WORKERS -> SuperRuntime.isSuperManagedSessionId(timerSessionId)
}

private fun requireSession(binding: SessionBinding): BoundSession =
    binding.current() ?: throw IllegalArgumentException("Current conversation is not bound")

private suspend fun timerToolResult(block: suspend () -> AgentToolResult): AgentToolResult =
    try {
        block()
    } catch (error: RequestAbortedException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        textResult(error.message ?: "Timer operation failed", isError = true)
    }

private fun timerJson(timer: Timer, includeTarget: Boolean = false): JsonObject = buildJsonObject {
    put("id", timer.id)
    put("name", timer.name)
    if (includeTarget) {
        val workerId = SuperRuntime.parseWorkerSessionId(timer.sessionId)
        put("target", if (workerId != null) "worker" else "super")
        if (workerId != null) {
            put("workerId", workerId)
        }
    }
    put("trigger", timer.trigger.toJsonObject())
    if (timer.nextRunAt == null) {
        put("nextRunAt", JsonNull)
    } else {
        put("nextRunAt", timer.nextRunAt)
    }
    put("enabled", timer.enabled)
    if (timer.lastDeliveredAt == null) {
        put("lastDeliveredAt", JsonNull)
    } else {
        put("lastDeliveredAt", timer.lastDeliveredAt)
    }
    if (timer.lastError == null) {
        put("lastError", JsonNull)
    } else {
        put("lastError", timer.lastError.name)
    }
}

private fun parseTrigger(args: JsonObject, base: TimerTrigger?, requestedFrequency: String?): TimerTrigger {
    val frequency = requestedFrequency?.trim()?.lowercase()
        ?: args.stringOrNull("frequency")?.trim()?.lowercase()
        ?: when (base) {
            is TimerTrigger.Cron -> "cron"
            is TimerTrigger.Once -> "once"
            else -> null
        }
        ?: throw IllegalArgumentException("Missing frequency (once or cron)")
    if (frequency == "once" && args.containsKey("cron")) throw IllegalArgumentException("once does not accept cron")
    if (frequency == "cron" && (args.containsKey("date") || args.containsKey("time"))) throw IllegalArgumentException("cron does not accept date/time")
    return when (frequency) {
        "once" -> {
            val baseOnce = base as? TimerTrigger.Once
            val date = args.stringOrNull("date")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::parseDate)
                ?: baseOnce?.date
                ?: throw IllegalArgumentException("once requires date (YYYY-MM-DD)")
            val time = parseTimeArg(args, baseOnce?.time)
                ?: throw IllegalArgumentException("once requires time (HH:mm)")
            TimerTrigger.Once(date, time)
        }
        "cron" -> {
            val baseCron = base as? TimerTrigger.Cron
            val expression = args.stringOrNull("cron")?.trim()?.takeIf { it.isNotEmpty() }
                ?: baseCron?.expression
                ?: throw IllegalArgumentException("cron requires a five-field cron expression")
            TimerTrigger.Cron(expression)
        }
        else -> throw IllegalArgumentException("frequency must be once or cron")
    }
}

private fun parseTimeArg(args: JsonObject, fallback: LocalTime?): LocalTime? {
    val raw = args.stringOrNull("time")?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
    return parseTime(raw)
}

private fun parseTime(raw: String): LocalTime {
    val iso = runCatching { LocalTime.parse(raw) }.getOrNull()
    if (iso != null) return iso
    return try {
        LocalTime.parse(raw, TOOL_TIME_FORMAT)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("Invalid time: $raw (use HH:mm)")
    }
}

private fun parseDate(raw: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (error: DateTimeParseException) {
    throw IllegalArgumentException("Invalid date: $raw (use YYYY-MM-DD)")
}

private fun promptPreview(prompt: String): String {
    val normalized = prompt.trim().replace(Regex("\\s+"), " ")
    return if (normalized.length <= 28) normalized.ifEmpty { "Reminder" } else normalized.take(28) + "…"
}

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.requiredString(name: String): String =
    stringOrNull(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Missing required parameter: $name")

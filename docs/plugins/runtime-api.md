# Plugin Runtime API & Host Services

> Part of the [Plugin Developer Docs](README.md).

## Lifecycle and phases

The host keeps a phase per plugin (visible on the Plugins page and in the info dialog):

| Phase | Meaning |
|---|---|
| `INSTALLED` | Installed but not loaded (for example waiting for the next start) |
| `ACTIVE` | Enabled and running normally, all contributions in effect |
| `DEGRADED` | Enabled but `availability()` reports unavailable: skills are not enabled and the reason is visible; tools/prompts/UI/hooks remain at the plugin's discretion |
| `FAILED` | Load/enable/activation failed, reason in the error text |
| `DISABLED` | The user turned the plugin off ("user choice ≠ load success") |
| `PENDING_RESTART` | A new version is staged and activates after a restart |

Only loaded, running plugins (`ACTIVE`/`DEGRADED`) aggregate contributions; when `availability()` is unavailable, skills are disabled and the reason shown, **but the plugin's tools/prompts/UI/hooks are not cleared automatically** — unavailable capabilities should return empty from `contributions()`/`ui()` based on availability.

`SuperPIPlugin` lifecycle methods (`availability()` must be implemented; the rest have default empty implementations; callback exceptions are isolated by the host and never take the app down):

| Method | When it is called and what it must do |
|---|---|
| `availability(host)` | Called on every refresh; returning `Unavailable(reason)` puts the phase in `DEGRADED`, shows the reason and disables skills |
| `onEnable(host)` / `onDisable(host)` | The user enables or disables the plugin; `onEnable` is also called for enabled plugins at startup, and disabling calls `onDisable` first and then cancels the plugin scope |
| `onUpgrade(host, fromVersion)` | Called in the verify stage of new-version activation; `fromVersion` is the version currently in use |
| `skills(host)` | Provides skills; called on every refresh |
| `promptSections(host)` | Appends system prompt sections |
| `contributions(host)` | Provides global tools + hooks |
| `contributionsFor(host, session)` | Materialized once per session: returns the tools + hooks in effect for that session (hooks override the plugin's global hooks by `pluginId`); `session` is a live handle (nullable) used only to read session snapshots; defaults to delegating to `contributions` |
| `ui(host)` | Slot/page/composer action contributions |
| `uninstall(host)` | Before the user deletes the plugin, clean up the plugin's own external side effects (the host then deletes the install directory and skill overrides) |
| `close()` | Called on disable/uninstall/update/process exit (the host cancels the plugin scope first and waits up to 5s); do not call back asynchronously after `close` |

Plugins implementing `PromptOverridePlugin` (with the `prompt` capability declared in the manifest) also get `promptOverride(host)`: the host calls it once when materializing contributions, see "Prompt override".

Plugin sign-in, CLI installation, connection state and so on are all managed by the plugin itself: state goes into `host.dataDir`/`host.secrets()` and UI is drawn inside plugin pages; the host no longer provides install/connect/settings/status/action/detail row callbacks, and there is no model-facing plugin connection tool. After an action in a page changes capabilities, call `host.requestContributionsRefresh()`; the host re-materializes contributions and rebuilds sessions at a safe point (busy sessions take effect at the end of the current round), so tools/skills/prompts/pages update right after.

`host` is the plugin's only entry point (`PluginHost`): `manifest`, `context` (the app context injected by the host), `dataDir`, `assetsDir`, `scope`, `text`, 35 host service accessors (see "Host services") and `requestContributionsRefresh()`. Manifest types such as `manifest` are host-constructed read-only interfaces; plugins must not construct them.

### Per-plugin scope

- Each plugin has its own `SupervisorJob` and `CoroutineScope`, that is `host.scope`. Background coroutines in a plugin should hang off this scope (`host.scope.launch { ... }`).
- Disable, uninstall, update and process exit all follow the same path: cancel the scope → `close()` (5s timeout) → release the install handle → remove registrations/clear contributions → sync skills; an exception in any step is recorded and the remaining cleanup still runs.
- `close()` is not guaranteed to be called when the process is force-killed; state that must survive the process should be written to `host.dataDir` promptly.
- Re-enabling creates a new scope and a new `PluginHost`, and the plugin object is loaded again. **Do not cache host or scope in plugin instance fields**; always use the parameter passed to each callback.
- Coroutines must handle cancellation (`CancellationException` propagates upward, never swallowed); check `isActive` in long loops or at stage boundaries.
- Once a session run starts, the host freezes the hook set (see "Events and hooks"); a plugin disabled during that round may still receive the remaining events, so callbacks must tolerate changed state; put resource cleanup in `close()`/`onDisable`.

### One materialization

The host calls contribution methods at most once per plugin per refresh, aggregating the results into an immutable snapshot; tool assembly, prompts, skill sync, UI slots, conflict diagnostics and the Plugins page all consume the same snapshot and never call the plugin again on consumption paths.

- Every refresh calls `availability()` and `skills()`; when enabled and installed it also calls `contributions()`, `ui()` and `promptSections()`, and `promptOverride()` when the `prompt` capability is declared.
- `contributionsFor()` is called once per session (one session binding from establishment to close), and that session's tools and session hooks both come from that result; it is materialized again only after the session is rebuilt.
- Do not do heavy IO or depend on changing external state in these methods: put slow work on `host.scope` asynchronously (for example start polling in `onEnable` and rely on scope cancellation in `onDisable`/`close`).

### Failure recovery

A load/activation/enable failure puts the phase in `FAILED`, and an unavailable `availability()` puts it in `DEGRADED`. The row error text on the Plugins page shows the reason; the "⋯" menu at the end of the row offers recovery entries by state:

| Entry | Behavior |
|---|---|
| "Retry activation" | Clears the interrupted-activation marker and start counter, then activates again: a loaded plugin gets services injected again and `onEnable` called, an unloaded or auto-disabled plugin is loaded and enabled again; with a staged version the whole activation sequence reruns. On success it refreshes and clears the error; on failure it keeps the state and shows the new error text |
| "Roll back to <previousVersion>" | Appears when the record has a previous version: switches the installed version back, clears the staged record and deletes the failed version directory |
| Enable switch | Turning it off and on runs `onEnable` again, for manually recovering an auto-disabled plugin |

- Two consecutive failed starts on the same version disable the plugin automatically (phase `DISABLED`), and enabling it manually grants permission again and clears the start counter.
- A slot render crash only disables the matching Compose binding and falls back to the host view, without changing the plugin phase; rendering retries automatically after a plugin version upgrade. Only load failures go through retry/rollback/re-enable above.

## Tools

```kotlin
override fun contributions(host: PluginHost) = contributions(
    tools = listOf(
        pluginTool(
            name = "template_echo",
            label = "Template Echo",
            description = "Echo text back, streaming one partial update.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
                put("required", buildJsonArray { add(JsonPrimitive("text")) })
            },
            promptSnippet = "template_echo echoes text",
            promptGuidelines = listOf("Use it only in development demos."),
        ) { call ->
            val text = call.args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            call.partial(textResult("received ${text.length} chars"))
            textResult("echo: $text")
        },
    ),
)
```

| Field | Description |
|---|---|
| `name` | Global tool name, lowercase `snake_case`; must not conflict with host reserved names (see below), and when names collide across plugins the first registration wins |
| `label` | Tool card title |
| `description` | Tool description for the model; state what it does, when to use it and its boundaries |
| `parameters` | JSON Schema (`{"type":"object","properties":{...},"required":[...]}`), passed to the SDK as is |
| `promptSnippet` | One-line summary in the system prompt (optional) |
| `promptGuidelines` | Additional tool usage rules for the model (optional) |
| `exposure` | `PluginToolExposure.DIRECT` (default; the tool is resident and directly callable) or `DEFERRED` (the tool stays out of the resident list and the model first finds it with `tool_search`, then runs it with `tool_execute`) |
| `execute` | `suspend (PluginToolCall) -> PluginToolResult` |

#### Tool exposure

Tools are `DIRECT` by default: name, description and parameters appear in the model's resident tool list. When you have many tools, or tools used only occasionally, declare `PluginToolExposure.DEFERRED`:

```kotlin
pluginTool(
    name = "calendar_sync",
    label = "Calendar sync",
    description = "Sync calendar events in the given time range",
    parameters = syncSchema,
    exposure = PluginToolExposure.DEFERRED,
) { call -> ... }
```

`DEFERRED` tools are not resident in the system prompt and only appear in the host's `tool_search` catalog. The model calls `tool_search(query)` to get the tool name and parameter docs, then executes with `tool_execute(name, arguments)`; the host never loads the tool into the session. Whether to fold is up to the plugin; a plugin can also offer a switch in its pages and make it take effect on new sessions through `requestContributionsRefresh()` (the current run's contribution set is frozen when the run starts).

Host reserved tool names (conflicts are rejected at install time): the engine defaults `read`/`write`/`edit`/`bash`; app built-ins `ask_user_question`/`present`/`system_shell`/`get_goal`/`create_goal`/`update_goal`/`job_*`/`timer_*`/`miniapp_*`/`web_search`/`web_fetch`/`calendar`/`screen_*`/`virtual_screen`/`todo_write`; host on-demand tools `tool_search`/`tool_execute` and the SDK's `list_mcp_resources`/`list_mcp_resource_templates`/`read_mcp_resource`; plus every `mcp__` prefix.

`PluginToolCall`:

- `callId`: the call id, for logs and correlation only.
- `args: JsonObject`: arguments passed by the model.
- `sessionId`: the current session id resolved by the host in `execute`; an empty string when there is no active session.
- `signal: PluginRunSignal?`: cancellation signal; long tasks call `signal?.throwIfCancelled()` at stage boundaries, or register cleanup with `signal?.onCancel { ... }`; do not swallow cancellation exceptions.
- `partial(result)`: streaming intermediate result, shown when a live tool card exists; the return value of `execute` is the final result.

`PluginToolResult`:

| Constructor | Purpose |
|---|---|
| `textResult(text, details?, isError?)` | Plain text result; `details` is host-side structured metadata (for tool cards and logs, not model context), where `superpi.fileRefs` is a host reserved key that plugins must not read or write |
| `richResult(blocks, details?, isError?)` | Multi-block result: `textBlock(text)`, `imageBlock(data, mimeType)` (base64 goes into model image content), `fileRefBlock(path, mimeType?)` (content keeps one `file: <path> (<mime>)` text line for the model to read, and the host also writes `{path, mimeType}` into the reserved details key `superpi.fileRefs`; tool cards and history render a clickable file row that survives reopening the session) |
| Result fields | `PluginToolResult` is a read-only interface (`text`/`blocks`/`details`/`isError`), constructed with the two factories above; the old `PluginToolResult.Text`/`Rich` constructors were removed |

Fields you write into `details` other than `superpi.fileRefs` are passed through to the tool card and the custom message card as is; `superpi.fileRefs` is generated by the host from `fileRefBlock(...)` in `richResult(...)`, and plugins must not overwrite it.

`isError = true` marks a failed tool result (the model can retry or explain it); an exception thrown from `execute` (other than cancellation) also becomes an error result, so do not use exceptions to convey business errors.

#### Three layers of tool results: raw / guidance / card (2.0.0, plugin tools only)

Cards belong to plugin tools only: a `PluginTool` result may carry a card, and the host renders it in the answer area by `superpi.card`. Plugins that run a CLI through bash (such as Sun Yat-sen University, Feishu, WeCom) produce no cards and do not parse bash commands; their results stay CLI text that the model reads and answers from, and the host does no card handling for bash results at all.

The tool writes three pieces of data into the result details, and `open fun Cards.deliver(callId, result)` (plugin-api 2.0.0, host 2.0.0+) assembles them into model-visible text and display text:

| details key | Contents | Who uses it |
|---|---|---|
| `superpi.raw` | The full upstream output (verbatim; CLI results drop ANSI, `[exit: N]` and host hints, with no summarization or truncation) | `Cards.deliver` appends it to the "full return" section of the model text |
| `superpi.guidance` | Short guidance for the model: next step, failure reason, constraints that must be asked about, output file paths, pagination/login hints; do not restate facts already in raw such as names, prices or IDs | `Cards.deliver` appends it to the "usage instructions" section |
| `superpi.card` | `{ "customType": "...", "title": "...", "payload": {...} }` | `Cards.deliver` generates the "call present to show this card" instruction and writes `superpi.display` for the tool card to display |

- Plugins do not assemble card ids or the `present` instruction themselves, and must not put it into the card payload; the instruction in the model text is generated uniformly by `Cards.deliver` (the `pluginTool` factory calls it automatically, while plugins that implement `PluginTool` themselves must call it explicitly once; repeated calls are idempotent).
- The text of `textResult` contains only the plugin's own body (for example a list of mailbox facts); `raw`/`guidance` are no longer assembled by the host.
- For error results (`isError = true`), `Cards.deliver` drops the card and the `present` instruction, keeping only the body, the full return and the usage instructions.
- The card payload must be complete (including raw dirty strings, IDs, coordinates, image URLs, state, pagination tokens); trimming happens only in the default preview/expand of the rendering layer.
- Fetch image URLs with `host.images().load(url, maxEdgePx)` (the manifest must declare the `images` capability), which returns `RemoteImage.bitmap`; render it with `DdRemoteImage` from design 1.0.0, and use `DdImageViewer(bitmap)` for full screen. Images only go into host memory and the private cache; do not write them to shared storage or the plugin data directory.
- `plugin-api 2.0.0` + `design 1.0.0`: plugins using `host.images()` write `minHostVersion: "1.4.0"`; `plugin-api 2.0.0` is still additive within level 6.

Tools enter the session with enabled plugins by default; when you need session information, use the live handle from `contributionsFor`:

```kotlin
override fun contributionsFor(host: PluginHost, session: PluginSessionInfo?) = contributions(
    tools = listOf(
        pluginTool(
            name = "session_info",
            label = "Session Info",
            description = "Return the active session id and working directory.",
            parameters = buildJsonObject { put("type", "object") },
        ) { _ ->
            val snapshot = session?.snapshot()
            if (snapshot == null) {
                textResult("No session is bound.", isError = true)
            } else {
                textResult("session=${snapshot.sessionId} cwd=${snapshot.cwd}")
            }
        },
    ),
)
```

- `session` in `contributionsFor` is a **live session handle**: do not cache or copy its fields, and do not assume an active session exists when it is called.
- Inside a tool's `execute`, call `session?.snapshot()` for the latest `PluginSessionSnapshot(sessionId, file, cwd)`; it returns `null` when there is no active session.
- `session.supportsChatDelivery` says whether the session supports delivering custom cards (mini app one-off agents and similar scenarios do not).

## Skills and prompt sections

`pluginSkill(id, markdown, files)`:

- `id`: lowercase `[a-z][a-z0-9_-]*`; when global skill ids collide the first registration wins.
- `markdown`: the full SKILL.md content, which must have YAML frontmatter (`name`, `description`); the body is written for the model.
- `files`: attached files, built with `pluginSkillFile(path, content?, assetPath?)` — `path` is a relative path inside the skill directory, and `content` (inline string) and `assetPath` (`assets/<path>` inside the package) are mutually exclusive; `assetPath` is read as text on the host side.
- When the plugin can contribute, the host materializes skills into the agent skill directory and cleans them up on disable/uninstall using an ownership marker; do not write files yourself in `skills()`.

`promptSections(host)` returns a list of `promptSection(id, body, priority)` appended to the system prompt; sorted by `priority` ascending (smaller first), **ids must be unique within one plugin**.

### Prompt override (`PromptOverridePlugin`, experimental)

The experimental capability added in v6: after declaring the `prompt` capability in the manifest and implementing `PromptOverridePlugin`, a plugin can override the preamble and rules of the agent system prompt, not just append sections. The API is marked `@PluginExperimentalApi`, needs an explicit opt-in, and may change while experimental.

```kotlin
import io.github.whywood.superpi.plugin.api.promptOverride as promptOverrideOf

class MyPlugin : SuperPIPlugin, PromptOverridePlugin {
    override fun promptOverride(host: PluginHost): PromptOverride = promptOverrideOf(
        preamble = "You are a sync assistant.",
        rules = "- Confirm the target directory before syncing.",
        sections = listOf(promptSection("sync", "Report the file count after syncing completes.")),
        priority = 10,
    )
}
```

- The manifest must declare `capabilities: ["prompt", ...]`; otherwise the host does not call `promptOverride()`.
- Merge rules: among several plugins, `preamble`/`rules` come from the highest `priority` (conflicts are written to the host log); `sections` are concatenated by `priority` ascending and join the sections from `promptSections()` in the plugin section of the system prompt.
- Budget: `preamble`, `rules` and `sections` of a single plugin total 32 KiB, and all plugins together 64 KiB; a plugin that exceeds it has its whole contribution skipped and logged.
- Override applies only to the agent's preamble and rules; the workspace/environment sections generated by the host are not overridable and are always kept.
- Callback exceptions are isolated by the host, only logged, and never interrupt the session.
- Division of labor with `promptSections()`: `promptSections()` only appends sections and works for any plugin; prompt override needs the `prompt` capability and replaces the agent's default identity and rules.

## Events and hooks

Every `PluginHooks` callback is a `suspend` function with a default empty implementation, so implement only what you need. Hooks are returned from `contributions(host)` (global) or `contributionsFor(host, session)` (session level), and both feed the same dispatch pipeline. Observation events and decision types are host-implemented read-only interfaces; plugins must not implement or construct them.

**Session-level hooks**: the `Contributions` returned by `contributionsFor(host, session)` are the tools + hooks in effect for that session, where hooks override the global hooks of the same plugin by `pluginId`. The default implementation delegates to `contributions(host)`, so plugins that only return hooks from `contributions()` behave the same (still one set per plugin); when you only add session tools in `contributionsFor`, return `contributions(tools = ...)` (with `hooks` left at the default `PluginHooks.None`, the plugin's global hooks are used). When the session contribution returns the default `PluginHooks.None` (no hooks provided explicitly), the plugin's global hooks are used, so writing `contributionsFor` does not silently disable hooks. Each session is materialized once: one session binding calls `contributionsFor` once from establishment to close, and tools and session hooks share that result; a capability change (enable/disable, or a refresh after a page action) triggers a session rebuild at a safe point, and only then is it materialized again. Materialization for different sessions does not block each other and may run concurrently, so plugin implementations must not rely on shared mutable state across sessions.

**Run-level freeze**: when a run starts (`RunStartedEvent`), the host freezes the hook set in effect, and all observation events and transforms/gates in that run go through it: enabling or disabling plugins, refreshing contributions or adding plugins after the freeze has no effect on this run — a frozen plugin still receives the run's remaining events even if disabled; newly enabled plugins take effect from the next run. After the run ends (`RunFinishedEvent`; a failed run also arrives as `RunFinishedEvent(error)`) and the events of the run are dispatched, hooks unfreeze and the next run freezes the latest plugin snapshot.

Note: a plugin disabled during the freeze may still receive the run's remaining events, so callbacks must tolerate the plugin having been closed or state having changed; do not rely on callback timing for resource cleanup (put cleanup in `close()`/`onDisable`). The premise is one active run per session at a time.

A mini app one-off agent reuses the same prompt/input/tool hooks and session binding, but its session events do not go through the host session observation pipeline, so the 21 session observation events and run freezing cover only the main session run path; there are 6 more system events below.

### Observation events (notify only, cannot change the flow)

Dispatched serially in plugin registration order, with a 5s timeout per handler (`PluginHooks.OBSERVE_TIMEOUT_MS`); timeouts and exceptions are logged and dispatch continues without affecting the session. Session events are dispatched one by one in arrival order, and structured events take precedence over streaming deltas (the oldest deltas are dropped when overloaded).

| Callback (`PluginHooks` method) | Event type | Available fields | When |
|---|---|---|---|
| `sessionStarted(event: SessionStartedEvent)` | `SessionStartedEvent` | `sessionId`, `title?`, `workspace?` | Session created |
| `sessionClosed(event: SessionClosedEvent)` | `SessionClosedEvent` | `sessionId` | Session closed |
| `runStarted(event: RunStartedEvent)` | `RunStartedEvent` | `sessionId`, `runId` | An agent run starts |
| `runFinished(event: RunFinishedEvent)` | `RunFinishedEvent` | `sessionId`, `runId`, `error?` (`succeeded`) | Run finished |
| `turnStarted(event: TurnStartedEvent)` | `TurnStartedEvent` | `sessionId`, `turnId` | A model turn starts |
| `turnFinished(event: TurnFinishedEvent)` | `TurnFinishedEvent` | `sessionId`, `turnId`, `failed` | A model turn ends |
| `messageStarted(event: MessageStartedEvent)` | `MessageStartedEvent` | `sessionId`, `messageId`, `role` (`USER/ASSISTANT/TOOL/SYSTEM`) | A message starts |
| `messageDelta(event: MessageDeltaEvent)` | `MessageDeltaEvent` | `sessionId`, `messageId`, `delta` | Streaming delta |
| `messageFinished(event: MessageFinishedEvent)` | `MessageFinishedEvent` | `sessionId`, `messageId`, `text?` | A message completes |
| `toolExecutionUpdate(event: ToolExecutionUpdateEvent)` | `ToolExecutionUpdateEvent` | `sessionId`, `callId`, `toolName`, `phase` (`STARTED/PARTIAL/FINISHED/FAILED`), `partial?` (`PluginToolResult`) | Tool execution state |
| `compactionStarted(event: CompactionStartedEvent)` | `CompactionStartedEvent` | `sessionId` | Context compaction starts |
| `compactionFinished(event: CompactionFinishedEvent)` | `CompactionFinishedEvent` | `sessionId`, `tokensBefore`, `tokensAfter` | Compaction finishes |
| `compactionFailed(event: CompactionFailedEvent)` | `CompactionFailedEvent` | `sessionId`, `error` | Compaction failed |
| `modelChanged(event: ModelChangedEvent)` | `ModelChangedEvent` | `sessionId`, `model` (`provider/model`) | Model switched |
| `thinkingLevelChanged(event: ThinkingLevelChangedEvent)` | `ThinkingLevelChangedEvent` | `sessionId`, `level` | Thinking level switched |
| `retryScheduled(event: RetryScheduledEvent)` | `RetryScheduledEvent` | `sessionId`, `attempt`, `delayMs`, `reason?` | Retry scheduled |
| `retrySucceeded(event: RetrySucceededEvent)` | `RetrySucceededEvent` | `sessionId`, `attempt` | Retry succeeded |
| `retryExhausted(event: RetryExhaustedEvent)` | `RetryExhaustedEvent` | `sessionId`, `attempts`, `error` | Retries exhausted |
| `mcpStatusChanged(event: McpStatusChangedEvent)` | `McpStatusChangedEvent` | `server`, `status` (`OK/AUTH_REQUIRED/FAILED/UNKNOWN`) | MCP status changed |
| `mcpToolsChanged(event: McpToolsChangedEvent)` | `McpToolsChangedEvent` | `server`, `tools` (`List<String>`) | MCP tools changed |
| `busEvent(event: BusEventReceived)` | `BusEventReceived` | `event` (`PluginBusEvent`: `topic`/`publisher`/`payload`/`publishedAt`) | Plugin bus event |

21 session observation callbacks in total.

### System events (no session context)

The 6 observation events below are dispatched by the host when system-side events arrive; they are not bound to a session and do not go through run freezing. The target is every plugin that returned global hooks from `contributions()` and meets the gate. The per-handler timeout is 5s as well; timeouts and exceptions are logged and dispatch continues.

| Callback | Event type | Fields | Trigger and gate |
|---|---|---|---|
| `notificationReceived(event: NotificationReceivedEvent)` | `NotificationReceivedEvent` | `notification` (`PluginNotificationRecord`), `removed` | The notification listener receives or removes a notification; `notifications` + `components.notificationListener` |
| `notificationAction(event: NotificationActionEvent)` | `NotificationActionEvent` | `notificationId`, `actionId`, `payload` | The user taps an action button on a notification the plugin itself posted; only the `notifications` capability is needed |
| `backgroundTask(event: BackgroundTaskEvent)` | `BackgroundTaskEvent` | `taskId`, `kind`, `firedAtMs` | A background task registered by the plugin fires or replays at boot; `background` + `components.background` or `components.foregroundService` |
| `shareReceived(event: ShareReceivedEvent)` | `ShareReceivedEvent` | `text?`, `streams` (`SharedStream`: `path`/`mimeType`/`name`), `title?` | The user shares content to PI; `share` + `components.share` |
| `deepLinkReceived(event: DeepLinkReceivedEvent)` | `DeepLinkReceivedEvent` | `path`, `arguments` | A received deep link path matches a `components.deeplinks` declaration; `deeplink` |
| `appLifecycle(event: AppLifecycleEvent)` | `AppLifecycleEvent` | `foreground` | The app enters the foreground or background; `app` |

- System events go only to global hooks (the `PluginHooks` returned by `contributions()`); session-level hooks never receive them.
- When a gate is not met the event is neither dispatched nor reported: notification listening, accessibility, share, deep links and background tasks all additionally require the user to grant the matching access in the system or to agree to the component declaration at install time.

### Transforms and gates (can change the flow)

Executed serially in plugin registration order with a 2s timeout per handler (`PluginHooks.TRANSFORM_TIMEOUT_MS`); timeouts and exceptions follow the failure policy of each stage:

| Method | Input → output | Failure policy |
|---|---|---|
| `promptTransform(request: PromptRequest): String` | Session id + prompt → new prompt, chained | fail-open: keep the previous value |
| `inputTransform(request: InputRequest): InputDecision` | Input text + image paths → `inputContinue(text, imagePaths)` continues (chained), `inputHandled()` short-circuits and drops this input | fail-open: keep the current input |
| `beforeToolCall(request: ToolCallRequest): ToolCallDecision` | Tool call (including MCP/dynamic tools) → `toolContinue()` / `toolBlock(reason)` / `toolRewrite(args)` (later plugins continue from the rewritten args) | **fail-closed**: timeout/exception blocks the call with a reason |
| `context(request: PluginContextRequest): String?` | Returns retrieval/memory text to inject into the system prompt; several plugins concatenate in registration order | fail-open: skipped on timeout/exception; truncated at 8 KiB per plugin and 32 KiB in total |
| `afterToolExecution(request: ToolExecutionResult): ToolResultDecision` | Tool result → `resultKeep()` / `resultReplace(result)` (chained replacement) | keep: retain the previous result |

Notes:

- These callbacks sit on the session critical path and must return fast; move slow work to `host.scope` asynchronously, and do not do network or heavy IO inside them.
- Do not block a transform waiting for the user; gates that need user confirmation should only make a synchronous decision.
- The `imagePaths` returned by `inputTransform` really take effect: the images at those paths are read and replace or join this input (unreadable paths are ignored), and an empty list means no images.
- `PluginHooks.None` can be used as a placeholder; event order = load/registration order in the currently effective set (plugin install registration order).
- `CancellationException` caused by user cancellation propagates upward; do not swallow it.


## Host services

`PluginHost` exposes 35 service accessors (17 in 1.0, 18 added in 1.1.0), of which `components()` / `credentials()` / `accessibility()` are experimental APIs that need an explicit opt-in:

```kotlin
@OptIn(PluginExperimentalApi::class)
val components = host.components()
```

When the host does not provide a service it **throws `PluginServiceUnavailable`** (it does not return null), so plugins should `runCatching` and degrade. When a service is wired but currently unavailable (for example Linux not installed, ADB not authorized, permission denied), it usually says so through `available`/`enabled`/`permissionGranted` fields, by returning null/false, or with an explicit exception.

```kotlin
val linux = runCatching { host.linux() }.getOrNull()
if (linux == null || !linux.available) {
    return textResult("Linux environment unavailable", isError = true)
}
```

| Accessor | Main capabilities | When unavailable or failing |
|---|---|---|
| `linux()` | `available`, `exec(command, cwd, timeoutMs)`, `stream(command)` → `LinuxResult`/`LinuxChunk` | `available=false` when the runtime is not installed or enabled; `exec` records timeouts and output truncation; stderr merges into stdout (`LinuxResult.stderr` is always empty, so check `exitCode` and the end of the output for failures) |
| `screen()` | `status`, `startVirtual/stopVirtual`, `observe`, `screenshot`, `apps`, `launch`, `tap/tapNode/swipe/scroll/type/key`, `waitFor` | without permission the capabilities are marked unavailable and calls throw; `status` is a synchronous snapshot, and the first read starts a host-side background refresh (no stop interface) |
| `notifications()` | `enabled`, `notify(...)`, `notifyChecked(...)`, `updateProgress`, `cancel`; notification listening `listenerGranted`/`recent`/`received`/`removed`/`dismiss` (see "Notifications and notification listening") | with notification permission off, `enabled=false`: `notify` is silently ignored and `notifyChecked` returns null; tapping a notification opens the first page declared in the plugin manifest |
| `secrets()` | `get/put/remove/clear`, encrypted storage namespaced by plugin id | throws `PluginServiceUnavailable` when the host does not provide it |
| `network()` | `request(method,url,headers,body,timeoutMs)`, `download(url,targetPath,expectedSha256,onProgress)`; `get`/`post` extensions | network errors throw; response bodies cap at 2 MB and downloads at 512 MB |
| `files()` | Plugin data/workspace files: `rootDir`, `workspaceDir`, `list/readText/writeText/readBytes/writeBytes/delete/exists/resolve` | relative paths resolve against the plugin `dataDir`; absolute paths resolve per the current tier (the Root tier is the native file system, see "Execution identity and environment") |
| `sessions()` | `active`, `observe()` (including busy changes), `list()`, `prompt(text, sessionId?)`, `history(sessionId, limit)`, `stop(sessionId)`, `create(title?)`, `rename(sessionId, title)`, `delete(sessionId)`, `open(sessionId)`, `archive(sessionId, archived)` | with no active session `active=null` and `prompt` returns false; invalid ids return null/false |
| `jobs()` | `observe()`, `start(command,label,cwd,sessionId)`, `read(jobId)`, `kill(jobId)` | with no session or no host support, `start` returns null; there is no `list()`, so read once with `observe().first()` |
| `timers()` | `schedule(TimerRequest)` (built with `timerRequest(...)` + `timerOnce`/`timerCron`), `list()`, `cancel(id)` | invalid parameters or no host support return null or throw |
| `goals()` | `observe()`, `get/create/edit/pause/resume/complete/clear` | returns null for invalid goal state |
| `miniapp()` | Mini apps: `scan/load/create/edit/delete/run` | throws `PluginServiceUnavailable` when the host does not provide it |
| `mcp()` | `servers()`, `probe(name)`, `tools(server)`, `call(server,tool,arguments)`, `callStructured(server,tool,arguments)` | returns an error result or throws when the server does not exist; `callStructured` gives text blocks and the structured result together |
| `ui()` | Open URLs/sharing/Toast, pages and arguments, interactive pickers, Snackbar, `isDark` (see "UI extensions") | throws `PluginServiceUnavailable("ui.xxx")` for an individual unwired item; `openPage` only accepts this plugin's page ids, not `superpi://` deep links |
| `app()` | `info: AppInfo` (versionName/versionCode/pluginApiLevel/uiApiLevel/language/androidSdkInt/deviceModel/installationId) | always available |
| `bus()` | Plugin bus: `publish(event)` (built with `pluginBusEvent(...)`), `subscribe(topic?)` → `Flow<PluginBusEvent>` | throws `PluginServiceUnavailable` when the host does not provide it |
| `logs()` | `log(level, message, error?)` + `debug/info/warn/error` | always available |
| `chat()` | `sendMessage(...)`: write cards, start a new round, interrupt/queue; `sendMessageWithAttachments(..., attachments = listOf(chatAttachment(path, mimeType, name)))`; `postCustomMessage(...)` writes only a card | unknown sessions throw; a failed delivery returns false |

Extra models for sessions and chat: `sessions().history(...)` returns `PluginMessage(id, role, text, timestampMs, toolName?, images)`; chat attachments are built with `chatAttachment(path, mimeType?, name?)` and passed to `sendMessageWithAttachments(...)`.

Minimal usage of each service:

```kotlin
host.linux().exec("ls -la", cwd = host.dataDir.absolutePath)
host.screen().status.virtual.available
host.notifications().notify(pluginNotification(id = "sync", title = "Sync", body = "Done"))
host.secrets().put("token", value)
host.network().get("https://example.com/api")
host.files().writeText("state.json", "{}")
host.sessions().prompt("Continue", sessionId)
host.jobs().start("sleep 5", "demo", cwd = null)
host.timers().schedule(timerRequest("Reminder", "Drink water", timerOnce(System.currentTimeMillis() + 60_000)))
host.goals().get(sessionId)
host.miniapp().scan()
host.mcp().servers()
host.ui().toast("Copied")
host.app().info.versionName
host.bus().publish(pluginBusEvent(topic = "demo.sync", payload = buildJsonObject { }))
host.logs().info("plugin ready")
host.chat().sendMessage(call.sessionId, "Sync finished", chatMessageOptions(triggerTurn = false, display = chatDisplay(customType = "my.card", payload = buildJsonObject { }, text = "Card summary")))

host.settings().put("last-sync", "2026-11-13T10:00:00Z")
host.device().state.batteryPercent
host.clipboard().setText("Copied")
```

### Notifications and notification listening

Build notifications with `pluginNotification` / `pluginNotificationWithActions`; `notifyChecked` returns null when notification permission is off:

```kotlin
val handle = host.notifications().notifyChecked(
    pluginNotificationWithActions(
        id = "sync",
        title = "Syncing",
        body = "Processed 3/12",
        progress = 25,
        ongoing = true,
        actions = listOf(notificationAction("pause", "Pause"), notificationAction("cancel", "Cancel")),
        payload = buildJsonObject { put("task", "sync") },
    ),
)
host.notifications().updateProgress("sync", 50, "Processed 6/12")
host.notifications().cancel("sync")
```

- `notifyChecked` returns `PluginNotificationHandle(id, key)`; it returns null when `enabled=false`, while `notify` is silently ignored.
- Action buttons are handled by the host notification service; a plugin that declares the `notifications` capability receives `notificationId`/`actionId`/`payload` in the `notificationAction` hook, and does not need the notification listener component.
- Tapping the notification body opens the plugin's first page in `provides.pages` / `ui()`.

Notification listening needs `permissions: ["notifications.listen"]` + `components.notificationListener`, granted by the user under the system "Notification access" setting:

| Member | Description |
|---|---|
| `listenerGranted` | Whether notification access has been granted |
| `recent(limit = 50)` | Recent notification records |
| `received()` | `Flow<PluginNotificationRecord>` of new or removed records |
| `removed()` | `Flow<String>` of removed notification keys |
| `dismiss(key)` | Requests removal of a notification (where the system allows it) |

`PluginNotificationRecord` fields: `key`, `packageName`, `appLabel`, `title`, `text`, `bigText`, `subText`, `channelId`, `category`, `postedAtMs`, `ongoing`, `clearable`, `progress`, `actions`. Notification content touches other apps' privacy: request only the scope you really need, and do not persist notification content unrelated to this plugin.

### Execution identity and environment

The actual execution identity of `host.linux()` and `host.files()` is decided by the tier the user picked on the System access page (Normal / Wireless debugging (ADB) / Root); plugins cannot choose or raise it:

| Tier | `linux()` | `files()` |
|---|---|---|
| Normal (LOCAL) | PRoot Alpine inside the app sandbox, same UID as the app | The plugin `dataDir` and workspace; absolute paths are still constrained by the Android sandbox |
| Wireless debugging (ADB) | Same as above; system commands go through the host `system_shell` over ADB | Same as above |
| Root | Real root chroot (uid 0); Android paths are native (`/data`, `/system`, `/sdcard`) | Native file system, absolute paths apply directly |

- Code must run in all three tiers: do not escalate by yourself and do not assume the current tier is root; treat a non-zero exit code and failed file reads/writes as ordinary failures with a readable reason.
- The Root tier may be "preparing" or "preparation failed" at startup, in which case `linux()`/`files()` throw `IllegalStateException` (the message contains `Root Linux is being prepared` / `Root Linux preparation failed`); tell the user to retry later instead of crashing.
- The manifest no longer declares `requiresPrivilege`; when you need system capabilities, declare only the matching `capabilities` (such as `screen`) and the host injects the service under the tier the user picked.
- Plugins cannot escalate: `privilege().exec(...)` only runs within the tier the user currently selected (`AUTO` picks the currently available tier, and explicitly asking for `ROOT`/`ADB` when the tier is insufficient returns a failure); do not assume any tier is always available.
- Environment descriptions in prompts are generated by the host per tier; plugin `promptSections` must not hardcode assumptions such as "root acquired".

### Settings storage

`host.settings()` is key-value storage isolated by plugin id, good for small state that must survive across processes; keep secrets in `secrets()`:

| Method | Description |
|---|---|
| `get(key)` / `put(key, value)` / `remove(key)` / `clear()` | String key-values |
| `keys()` / `snapshot()` | All keys / a snapshot of all key-values |
| `getJson(key)` / `putJson(key, value)` | JSON object read and write |
| `observe(key? = null)` | `Flow<Map<String, String>>` of settings changes; an empty `key` observes everything |

```kotlin
host.settings().putJson("state", buildJsonObject { put("lastSync", now) })
val json = host.settings().getJson("state")
host.settings().observe("state").collect { state -> render(state) }
```

Always available; do not store large objects, and do not put user credentials in it (use `secrets()`).

### Devices and clipboard

`host.device()`:

| Member | Description |
|---|---|
| `info: DeviceInfo` | Vendor/brand/model/device codename/Android version/SDK/ABI/emulator flag/language/time zone/screen size and density/refresh rate/memory and storage totals/installation id |
| `state: DeviceState` | Battery/charging/power saving/network type and connectivity/screen on/lock state/language/time zone/uptime/free memory and storage/current execution tier |
| `observe(): Flow<DeviceState>` | State change stream |
| `vibrate(durationMs = 200)` | Vibrate once |
| `openSettings(section: DeviceSettings)` | Jump to a system settings page, returns whether the jump is possible |

`DeviceSettings`: `APP` / `NOTIFICATIONS` / `ACCESSIBILITY` / `NOTIFICATION_LISTENER` / `OVERLAY` / `USAGE_STATS` / `BATTERY_OPTIMIZATION` / `INSTALL_PACKAGES` / `STORAGE` / `DEVELOPER` / `WIRELESS_DEBUGGING` / `LOCATION`.

`host.clipboard()`: `text()` / `setText(text)` / `uri()` / `setUri(uri)` / `has()` / `clear()`. Background clipboard access is restricted on Android 10+, so reads return null or are ignored by the system; do not rely on the clipboard for cross-app transfer.

```kotlin
host.device().observe().collect { state -> if (state.batteryPercent < 20) warnLowBattery() }
host.device().vibrate(80)
host.clipboard().setText("Copied")
```

### Permissions and special grants

`host.permissions()`:

| Method | Description |
|---|---|
| `granted(permission)` | Queries the grant state of a `PluginPermission` constant |
| `suspend request(vararg permissions)` | Requests runtime permissions, returns "permission → granted" |
| `special(access)` | Queries a special grant: `PluginAccessState(granted, available, reason)` |
| `openSettings(access)` | Jumps to the matching system settings page, returns whether the jump is possible |

`PluginSpecialAccess`: `NOTIFICATION_LISTENER` / `ACCESSIBILITY` / `OVERLAY` / `USAGE_STATS` / `BATTERY_OPTIMIZATION` / `INSTALL_PACKAGES` / `MANAGE_STORAGE`. The manifest declarations for runtime permissions and special grants are in "Permission declarations".

```kotlin
if (!host.permissions().granted(PluginPermission.CAMERA)) {
    host.permissions().request(PluginPermission.CAMERA)
}
val overlay = host.permissions().special(PluginSpecialAccess.OVERLAY)
if (!overlay.granted) host.permissions().openSettings(PluginSpecialAccess.OVERLAY)
```

Special grants have no permission dialog, so you can only guide the user to system settings; do not repeatedly jump to settings at startup.

### App management

`host.packages()`:

| Method | Description |
|---|---|
| `suspend list(includeSystem = false)` | Installed app list |
| `suspend get(packageName)` | Single app info, null when not found |
| `suspend install(apkPath, silent = false)` | Install an APK; `silent` needs the Root/ADB tier |
| `suspend uninstall(packageName, keepData = false)` | Uninstall an app |
| `launch(packageName)` / `openAppInfo(packageName)` | Launch an app / open its app info page |
| `suspend icon(packageName, sizePx = 256)` | Read an app icon as PNG bytes |

`PluginPackageInfo`: package name/name/version/version code/system app flag/enabled flag/install and update times; `PackageInstallResult`: `accepted`, `pendingUserAction`, `error`.

```kotlin
val apps = host.packages().list()
val result = host.packages().install(apkPath)
if (!result.accepted) host.ui().snackbar(result.error ?: "Install failed")
```

`silent = true` only works in the Root/ADB tier; the Normal tier goes through the system installer and waits for the user to confirm.

### Root / ADB execution (privilege)

`host.privilege()` provides system-level execution within the three tiers and never goes beyond the tier the user currently selected:

| Member | Description |
|---|---|
| `access: HostAccessLevel` | The currently effective tier: `LOCAL` / `ADB` / `ROOT` |
| `root: RootState` / `adb: AdbState` | Whether root is granted, whether ADB is paired/connected, and the error reason |
| `suspend probeRoot()` | Actively probes the Root state |
| `suspend exec(command, level = AUTO, timeoutMs = 30_000)` | Runs a command, returns `exitCode`/`stdout`/`stderr`/`timedOut`/`truncated` |
| `suspend install(apkPath, silent = true)` / `uninstall(packageName)` | Silent install/uninstall (needs the matching tier) |
| `openAccessSettings()` / `openWirelessDebugging()` | Jump to System access / wireless debugging settings |
| `suspend startAdbPairing()` / `submitAdbPairingCode(code)` / `connectAdb()` | Wireless debugging pairing and connection |

`PrivilegeLevel`: `AUTO` / `ROOT` / `ADB`. `AUTO` picks the currently available tier; explicitly asking for a higher tier than currently available returns a failure and never escalates automatically.

```kotlin
val root = host.privilege().root
if (root.granted) {
    val result = host.privilege().exec("id", level = PrivilegeLevel.ROOT)
    host.logs().info("exit=${result.exitCode} out=${result.stdout.trim()}")
}
```

Check `exitCode`/`stderr` for command failures instead of string-matching the output; ADB pairing needs the user to turn on wireless debugging in the system and confirm the pairing code.

### Intents and dynamic broadcasts

`host.intents()`:

| Method | Description |
|---|---|
| `startActivity(intent)` / `sendBroadcast(intent)` | Start an Activity / send a broadcast |
| `suspend startActivityForResult(intent, timeoutMs = 120_000)` | Waits for a result, returns `PluginActivityResult(resultCode, data, cancelled, timedOut)` |
| `startService(intent)` / `stopService(intent)` | Start/stop a service (subject to system background limits) |
| `canResolve(intent)` / `suspend resolve(intent)` / `suspend queryActivities(intent)` | Resolve the target `ResolvedIntent(packageName, className, label, exported)` |

Dynamic broadcasts use `host.components().registerReceiver(filter)` (experimental API):

```kotlin
@OptIn(PluginExperimentalApi::class)
val handle = host.components().registerReceiver(IntentFilter(Intent.ACTION_BATTERY_CHANGED))
host.scope.launch { handle.events().collect { intent -> onBattery(intent) } }
// onDisable()/close(): handle.close()
```

- `PluginReceiverHandle`: `id`, `events(): Flow<Intent>`, `close()`; the plugin must `close()` before it is disabled or exits.
- Android 10+ restricts starting Activities/Services from the background; call them from a page or a foreground flow, and failures return false.
- Resolve and start only Intents that are visible to the user and relevant to the plugin's function; do not scan unrelated apps.

### Dedicated windows and foreground services (experimental)

Plugins cannot register Activity/Service themselves; when they need a dedicated window or a long-running service, use the host proxy:

```kotlin
@OptIn(PluginExperimentalApi::class)
host.components().startActivity(
    pluginActivityOptions(pageId = "scanner", arguments = buildJsonObject { }, newTask = true),
)
```

- `pluginActivityOptions(pageId, arguments, newTask)` lets the host open this plugin's page in `PluginHostActivity`; the page is still the `PluginPage` declared in `ui()`, and `pageContext()` can read the passed arguments.
- Foreground services: `host.background().startForeground(foregroundServiceOptions(id, title, text, ongoing, progress))`, `updateForeground(...)`, `stopForeground(id)`; needs `components.foregroundService: true` + the `background` capability.
- Proxy components serve plugin pages and foreground tasks only: you cannot customize the Activity layout, replace the host theme or register manifest components.

### Background tasks and boot callbacks

`host.background()`:

| Method | Description |
|---|---|
| `suspend schedule(id, kind, triggerAtMs?, intervalMs?, requiresNetwork = false, requiresCharging = false)` | Registers a task, returns whether it succeeded |
| `suspend cancel(id)` / `suspend tasks()` | Cancel / list tasks |
| `startForeground` / `updateForeground` / `stopForeground` | Foreground services |
| `batteryOptimizationIgnored` / `requestIgnoreBatteryOptimizations()` / `openBatteryOptimizationSettings()` | Battery optimization state and guidance |

`PluginBackgroundKind`:

| Value | Meaning |
|---|---|
| `ONCE` | One-shot trigger (trigger time or delay) |
| `PERIODIC` | Periodic task (WorkManager, system minimum interval about 15 minutes) |
| `EXACT` | Exact timing (AlarmManager, subject to system power saving and the exact alarm permission) |
| `BOOT` | Runs once after boot, also used to re-register periodic tasks after a restart |

```kotlin
host.background().schedule(
    id = "digest",
    kind = PluginBackgroundKind.PERIODIC,
    intervalMs = 6 * 60 * 60 * 1000,
    requiresNetwork = true,
)
```

When a task fires it dispatches the `backgroundTask` hook (`taskId`/`kind`/`firedAtMs`), requiring the `background` capability and `components.background: true`. Doze and power saving delay tasks; for reliable execution guide the user to ignore battery optimizations.

### Location

`host.location()`: `available`, `permissionGranted`, `lastKnown(): PluginLocation?`, `current(timeoutMs = 10_000): PluginLocation?`, `updates(minIntervalMs = 1_000, minDistanceM = 0f): Flow<PluginLocation>`, `geocode(query, limit = 5): List<PluginGeocode>`, `reverseGeocode(location): PluginGeocode?`.

`PluginLocation`: latitude/longitude/accuracy/source/time, with optional altitude/speed/bearing; `PluginGeocode`: lat/lon/name/country/administrative area/city. Needs the `location` capability + `location.coarse`/`location.fine` in `permissions`; unauthorized calls return null or an empty list.

```kotlin
val here = host.location().current() ?: return textResult("Location unavailable", isError = true)
val place = host.location().reverseGeocode(here)
```

### Contacts

`host.contacts()`: `permissionGranted`, `suspend list(limit = 0)`, `suspend search(query, limit = 20)`, `suspend get(id)`, `suspend create(draft)`, `suspend update(id, draft)`, `suspend delete(id)`; drafts are built with `pluginContactDraft(displayName, phones, emails, organization)`.

`PluginContact`: id/display name/phones/emails/organization/note/starred; `PluginContactField`: value/label/type. Needs the `contacts` capability + `contacts.read`/`contacts.write` in `permissions`; unauthorized calls throw `SecurityException`.

```kotlin
val contacts = host.contacts().search("Ann")
val created = host.contacts().create(pluginContactDraft(displayName = "Ann Lee", phones = listOf("13800000000")))
```

### SMS

`host.sms()`: `available`, `canRead`, `canSend`, `suspend threads(limit = 20)`, `suspend messages(threadId?, address?, limit = 50)`, `suspend send(address, text, subscriptionId?)`, `suspend markRead(threadId)`, `suspend delete(threadId)`.

`PluginSmsThread`: thread id/number/contact name/summary/time/unread count; `PluginSmsMessage`: id/thread id/number/body/time/received/read. Needs the `sms` capability + `sms.read`/`sms.send` in `permissions`.

- Sending is sensitive: confirm the content and number in the page first, and on failure return false with the reason (the default SMS app or carrier restrictions may block sending).
- Do not bulk-read or delete the user's messages; handle only the threads the user explicitly names.

```kotlin
val thread = host.sms().threads(limit = 5).firstOrNull() ?: return
host.sms().messages(threadId = thread.id, limit = 20)
if (host.sms().canSend) host.sms().send(thread.address, "Got it, I will reply later")
```

### Calendar

`host.calendar()`: `permissionGranted`, `suspend calendars()`, `suspend events(fromMs, toMs, calendarIds = emptyList(), limit = 200)`, `suspend get(eventId)`, `suspend create(draft)`, `suspend update(eventId, draft)`, `suspend delete(eventId)`; drafts are built with `pluginCalendarEventDraft(title, startMs, endMs, allDay = false, description?, location?, calendarId?, timeZone?, reminders = emptyList())`.

`PluginCalendar`: id/name/account/display name/color/visible/default; `PluginCalendarEvent`: id/calendar id/title/start and end/all-day/description/location/time zone/reminders. Needs the `calendar` capability + `calendar.read`/`calendar.write` in `permissions`.

```kotlin
val draft = pluginCalendarEventDraft(
    title = "Weekly sync",
    startMs = start,
    endMs = start + 3_600_000,
    reminders = listOf(10),
)
host.calendar().create(draft)
```

### Media

`host.media()`:

| Method | Description |
|---|---|
| `saveToDownloads(fileName, mimeType, bytes)` | Writes bytes into Downloads, returns the path |
| `saveFileToDownloads(path, fileName?, mimeType?)` | Copies an existing file into Downloads |
| `saveImageToGallery(fileName, mimeType, bytes)` | Writes an image into the system gallery |
| `scanFile(path)` | Triggers a media scan |
| `open(path)` / `share(path, mimeType?)` / `preview(path)` | Open/share/preview a file |
| `readImage(path, maxSizePx = 2_048)` / `thumbnail(path, maxSizePx = 512)` | Image decoding (with a size limit) |
| `importUri(uri, fileName?)` | Imports an external URI into the plugin directory, returns the local path |

Needs the `media` capability; whether extra grants are needed depends on the host implementation and the system version, and declaring `manage-storage` in `permissions` is required to access shared storage directly (the user enables "All files access" in system settings).

```kotlin
val saved = host.media().saveToDownloads("report.csv", "text/csv", bytes)
host.media().share(saved!!, "text/csv")
```

### Speech

`host.speech()`: `speakAvailable`, `recognizeAvailable`, `suspend speak(text)`, `stopSpeaking()`, `speaking(): Flow<Boolean>`, `suspend recognize(timeoutMs = 30_000): PluginSpeechResult(text, error)`, `openVoiceSettings()`.

Needs the `speech` capability; speech recognition also needs `microphone` in `permissions` (capability `ui`). With no TTS engine or recognition service, the matching `available=false` and calls return false or an error result.

```kotlin
if (host.speech().speakAvailable) host.speech().speak("Sync finished")
val heard = host.speech().recognize()
```

### Web

`host.web()`: `available`, `suspend search(query, limit = 8): List<PluginWebResult(title, url, snippet?)>`, `suspend fetch(url, maxChars = 20_000): PluginWebPage(url, title?, text, truncated)`.

Needs the `web` capability; search and fetch use the host's web service and parsing, and failures return an empty list or null. When you already use `network()` to request things yourself, prefer your own implementation (controllable timeouts and parsing).

```kotlin
val results = host.web().search("Android notification listener")
val page = results.firstOrNull()?.let { host.web().fetch(it.url) }
```

### Host credentials (experimental)

`host.credentials()` (`@PluginExperimentalApi`) reads credentials already configured in the host, for plugins to reuse with the user's knowledge:

| Method | Description |
|---|---|
| `providers(): List<PluginProviderCredential>` / `provider(id)` | Provider configuration: id/name/baseUrl/apiKey/authKind |
| `mcpTokens(): List<PluginMcpToken>` / `mcpToken(server)` | MCP tokens: server/url/accessToken/refreshToken/tokenType/expiresAtMs |
| `hostValues(): Map<String, String>` | Generic host credential key-values |

**Sensitivity**: these are the user's real credentials. Read them only while performing a user request, never persist them, never log them and never send them to third parties; the install confirmation page and the plugin capability list show this capability so users know the plugin reads host credentials.

```kotlin
@OptIn(PluginExperimentalApi::class)
val provider = host.credentials().providers().firstOrNull()
val mcp = host.credentials().mcpTokens().firstOrNull()
```

### Accessibility (experimental)

`host.accessibility()` (`@PluginExperimentalApi`):

| Member | Description |
|---|---|
| `enabled` | Whether the accessibility service is on |
| `events(): Flow<PluginAccessibilityEvent>` | Accessibility event stream: eventType/packageName/className/text/contentDescription/timeMs |
| `performGlobalAction(action)` | Global actions: `BACK`/`HOME`/`RECENTS`/`NOTIFICATIONS`/`QUICK_SETTINGS`/`POWER_DIALOG` |

Needs the `accessibility` capability + `permissions: ["accessibility"]` + `components.accessibility: true`, and the user must enable PI's service in the system accessibility settings. Read only the events the feature needs, and do not collect or send other apps' text.

```kotlin
@OptIn(PluginExperimentalApi::class)
if (host.accessibility().enabled) {
    host.accessibility().events().collect { event -> onAccessibilityEvent(event) }
    host.accessibility().performGlobalAction(PluginGlobalAction.BACK)
}
```

**Bus**: `host.bus().publish(pluginBusEvent(topic, payload))` broadcasts to all plugins (the host overrides `publisher` with the plugin id); `subscribe("topic")` returns a `Flow`, and the `PluginHooks.busEvent(...)` observation callback receives it too. Use events only for cross-plugin coordination and lightweight notifications, never for large payloads or synchronous RPC.


## Compatibility and migration

### 1.2.x → 1.3.0 (purely additive)

`plugin-api 2.0.0` does not raise the apiLevel: existing plugins need no code change or rebuild. To render cards on MCP/plugin tool results: raise the dependency to `plugin-api 2.0.0` and write `minHostVersion: "1.1.0"` in the manifest; then also implement `ToolResultRendererContributor` on the object returned from `ui()`, matching with `toolResultMatch(source/server/tool/toolPrefix)` and constructing with `toolResultRenderer`. When the agent calls an MCP tool through `tool_execute`, the host restores the inner tool identity automatically, so renderers match on the real MCP server/tool.

`plugin-api 2.0.0` / `design 1.0.0` do not raise the apiLevel either: new `PluginHost.images()` (returning `RemoteImage`) and `DdRemoteImage`; add `images` to the `plugin.json` capabilities and a plugin can load remote images in cards. Both are additive only, and old plugins need no rebuild.

### 1.1.x → 1.2.0 (purely additive)

`plugin-api 2.0.0` does not raise the apiLevel: existing plugins need no code change or rebuild. To use the new capabilities (theme packs, `openPageSheet`, page presentation mode, design 1.0.0 components): raise the dependencies to `plugin-api 2.0.0`/`design 1.0.0` and write `minHostVersion: "1.0.8"`; theme packs declare ids in `provides.themes` and add `assets/themes.json`.

### 1.0.x → 1.1.0 (purely additive)

`plugin-api 1.1.0` does not raise the apiLevel: published 1.0.x plugins keep running on host 1.0.4+ without code changes or a rebuild. Migration is needed only to use the new 1.1.0 capabilities (which need host 1.0.6+):

1. Raise the dependency to `1.1.0` (with `design 0.7.0`, carried through `plugin-api`'s `api()`, so plugin projects still declare only `plugin-api`).
2. Raise `minHostVersion` to `"1.0.6"`; plugins that only run on older hosts and do not call the new 1.1.0 APIs can keep their previous threshold (such as `"1.0.4"`).
3. Declare the new capabilities in `capabilities`; runtime permissions go in `permissions` and system components in `components`, declared in pairs with the matching capability (see "Permission declarations" and "Component declarations").
4. When using experimental APIs (`components()` / `credentials()` / `accessibility()`, `PromptOverridePlugin`), add `@OptIn(PluginExperimentalApi::class)`.
5. Wire page arguments, interactive pickers, notification actions and system events per the new sections; import design 0.7.0 components directly from `io.github.whywood.superpi.design`.
6. Bump the plugin version, sign again and update the marketplace entry; the runtime threshold is enforced by `minHostVersion` and the host's actual capabilities.

### Migrating from API level 5 to level 6

`plugin-api 1.0.0` is a breaking upgrade: since host 1.0.4 only `apiLevel = 6` is accepted, and level 5 plugins are rejected at install with an update prompt. Migrate the code using the mapping below, then rebuild and raise the manifest (`apiLevel: 6`, `minHostVersion: "1.0.4"`, `ui.apiLevel: 2`); sign again with the same publisher key so installed users can upgrade.

| Level 5 (`0.8.1`) form | Level 6 (`1.0.0`) form |
|---|---|
| `Contributions(...)` | `contributions(...)` |
| `PluginTool(...)` | `pluginTool(...)` (the constructor is no longer public) |
| `PluginToolResult.Text(...)` / `.Rich(...)` | `textResult(...)` / `richResult(...)` |
| `ToolBlock.Text` / `.Image` / `.FileRef` | `textBlock(...)` / `imageBlock(...)` / `fileRefBlock(...)` |
| `PluginSkill(...)` / `PluginSkillFile(...)` | `pluginSkill(...)` / `pluginSkillFile(...)` |
| `PromptSection(...)` | `promptSection(...)` |
| `LocalizedText.Literal(...)` (or `literal(...)`) | `localizedText(...)` |
| `IconRef.Asset(...)` | `assetIcon(...)` |
| `SlotBinding(...)` / `SlotContent.Compose { }` | `slotBinding(...)` / `composeSlot { }` |
| `PluginPage(...)` / `ComposerAction(...)` | `pluginPage(...)` / `composerAction(...)` |
| `PluginNotification(...)` | `pluginNotification(...)` |
| `TimerRequest(...)` + `TimerTrigger.Once/Cron` | `timerRequest(...)` + `timerOnce(...)` / `timerCron(...)` |
| `PluginBusEvent(...)` | `pluginBusEvent(...)` |
| `ChatMessageOptions(...)` / `ChatDisplay(...)` | `chatMessageOptions(...)` / `chatDisplay(...)` |
| Constructing `PluginAvailability` directly | `available()` / `unavailable(reason)` |
| `PluginEvent.X` (sealed subclass) | Top-level interface `XEvent` (e.g. `SessionStartedEvent`) |
| `InputDecision.Continue/Handled` | `inputContinue(...)` / `inputHandled()` |
| `ToolCallDecision.Continue/Block/Rewrite` | `toolContinue()` / `toolBlock(reason)` / `toolRewrite(args)` |
| `ToolResultDecision.Keep/Replace` | `resultKeep()` / `resultReplace(result)` |
| `PluginCapability` enum members | Value class constants (wire names unchanged, `plugin.json` needs no change) |
| `MessageRole` / `ToolPhase` and other enums | Value class constants (`USER` / `STARTED` and other names unchanged) |
| Objects implementing `PluginUiContributions` | Interface unchanged; build `bindings` / `pages` with the factories above |
| `host.text(source, *args)` | Unchanged (`vararg Any` kept, pass only strings/numbers) |

Manifest and project:

- `apiLevel: 6`, `minHostVersion: "1.0.4"`, `ui.apiLevel: 2`; `entryClass` and the entry class's no-arg constructor are unchanged.
- `capabilities` may add `prompt` (prompt override); all other wire names are unchanged.
- Dependencies become `compileOnly("io.github.sadami3066.superpi:plugin-api:<version>")` (`<version>` from the latest `SuperPI-Maven` release), rebuilt against the host Kotlin/Compose versions; any change to `.ddp` contents requires a new signature.
- Manifest parsing, validation and version comparison moved to the host (host repository `app/plugin/host/external/`), and the SDK no longer has `PluginManifestCodec` / `PluginValidation` / `PluginVersion`; `PluginManifest` / `Provides` / `UiManifest` / `PublisherInfo` are host-constructed read-only interfaces that plugins do not construct.
- The compatibility promise applies from 1.0.0: within level 6 only binary-compatible additions; deleting or changing public APIs requires a new `apiLevel` (see `plugin-api/COMPATIBILITY.md`).

## ABI and versions

- Currently `plugin-api 1.1.0` / API level 6: `PLUGIN_API_VERSION = "1.1.0"`, `PLUGIN_API_LEVEL = 7`, `BASELINE_API_LEVEL = 7`, `MIN_SUPPORTED_API_LEVEL = 7`. At load the host requires `plugin.apiLevel` to be within `MIN_SUPPORTED_API_LEVEL..PLUGIN_API_LEVEL` (currently 6..6); an omitted `apiLevel` is treated as `BASELINE_API_LEVEL` (6). Since 1.0.4 the host rejects level 5 plugins and asks for a rebuild with the new SDK.
- Compatibility matrix:

| apiLevel | plugin-api / design | Host | Notes |
|---|---|---|---|
| 5 | 0.8.1 / 0.6.1 | ≤1.0.3 | Historical contract, rejected from 1.0.4 |
| 6 | 1.0.x / 0.6.1 | 1.0.4+ | Factory construction, event interfaces, prompt override experimental capability |
| 6 | 1.1.x / 0.7.x | 1.0.6+ | Additive: 38 capabilities, 18 new service accessors, permissions/components, notification listening and system events, page arguments and interactive pickers, design 0.7.0 components |

- **Compatibility contract**: within one `apiLevel` only binary-compatible additions (new optional interfaces, methods with default implementations, value class constants, factory functions, optional manifest fields); deleting, renaming, changing signatures or changing runtime semantics requires a new `apiLevel`. Full rules in `plugin-api/COMPATIBILITY.md`.
- `minHostVersion` compares only `major.minor.patch` and ignores suffixes (`1.0.4-dev` counts as `1.0.4`), so a development build is not blocked by its own threshold; the host version comes from the app's `BuildConfig.VERSION_NAME` and is currently 1.0.6+; thresholds for older capabilities (such as `1.0.4`) stay valid.
- Gate: `./scripts/api-compat.sh` exports the public ABI of `plugin-api` and `design` and compares it with the `apps/android/plugin-api/compat/`, `design/compat/` baselines, failing on removals or signature changes; `--update` is only for a level bump or the first baseline. A breaking change requires bumping `apiLevel`, updating the baseline, and writing `plugin-api/CHANGELOG.md` plus the host release notes.
- Experimental APIs are marked `@PluginExperimentalApi` (`@RequiresOptIn`) and plugins must opt in explicitly; adjustments are allowed while experimental, and graduation only drops the annotation without changing signatures. Current experimental capabilities: `PromptOverridePlugin`, `components()`, `credentials()`, `accessibility()`.
- `design` is protected by the ABI gate together with `plugin-api`; plugins using design 0.7.0 components write `minHostVersion` according to the host version (1.0.6+).
- Plugins must depend on host libraries with `compileOnly`; the host being parent-first guarantees `plugin-api` and Compose exist only in the host.
- Compose makes no cross-version binary ABI promise: plugins must be rebuilt against the host Kotlin/Compose versions; a version mismatch only disables that plugin's UI, never rejects the plugin.

## Security model

- **Same process, same UID, no sandbox**: plugin code has the same permissions as app code and can read and write the app's private data, access system capabilities the host has been granted, reflect into host internals, start threads and kill the process. There is no trustworthy privilege narrowing within a process; signatures, `capabilities`, `permissions` and `components` are declarations, display and gating only, not a sandbox.
- **Installing means trusting**: the first-install confirmation shows the publisher, capabilities, permissions and component declarations, and states explicitly that "the plugin has all app permissions"; signatures only prove whether the package was tampered with and whether it is the same publisher, not whether the author is trustworthy.
- **Permissions and special grants**: the host requests runtime permissions uniformly; special grants such as notification access, accessibility, overlay, usage access, install unknown apps and all-files access must be enabled by the user in system settings, and a plugin can only guide the jump, never gain them silently.
- **Credentials (experimental)**: `credentials()` can read the host's real provider and MCP credentials; read them only while performing a user request, and never persist, log or send them. The capability page and the install confirmation show this capability.
- **Intended sources**: trusted first-party or personal plugin packages; use MCP for third-party tool sources, not `.ddp`.
- **Integrity**: the install record pins the whole-package sha256 and re-checks it before every load; the canonical digest verifies the signature against tampering; updates must use the same `keyId`; a failure keeps the last usable version.
- **What the host does not expose**: host internal encryption keys, the ADB pairing private key, pi SDK internal types. Plugins get the `PluginHost` contract and `host.context`; `host.context` is still the app's Context, and the risk of calling Android APIs directly is the plugin's to bear.
- **Native networking**: use `host.network()` for HTTP; unconventional protocols (IMAP/SMTP, WebSocket and so on) may connect directly through the JVM standard library, but must declare `network` in the manifest, keep TLS certificate validation and respect the system cleartext HTTP policy; never block the main thread in a page or tool.
- **Plugin author obligations**: do not read user data unrelated to this plugin; do not write credentials to logs; make actions that delete or overwrite user data reversible or confirm them explicitly in the page; do not end the process with `System.exit`/`Runtime.halt`.
- **Crashes**: tool/callback exceptions are isolated by the host into error/failure records; but a plugin that crashes the process on the main thread, in a Compose slot or during tool execution still takes the app down (there is no process-level isolation).


## Limits (explicitly out of scope for now)

- No third-party plugin store, review or sandbox: same-process plugins have no isolation, so trust comes only from trusted sources + install confirmation + signature/TOFU.
- Plugins cannot register Activity/Service/Receiver/permissions in the manifest: platform components come from the host static manifest; for a dedicated window use the `PluginHostActivity` proxy, for foreground services `PluginHostService`, for broadcasts a dynamic receiver, and for notification listening/accessibility/share/deep links the host proxy components (see "Component declarations").
- Plugins cannot use `res/` resource ids; copy, icons and skills all go through the manifest and assets.
- No declarative UI nodes are provided; UI extensions are free Compose only (the two slot families for tool cards/custom messages, full `PluginPage` content, composer actions).
- Native libraries are not loaded (`lib/**` only enters the digest and unpacking).
- No process-level isolation is promised for plugin crashes; `close()` and unfinished callbacks are not guaranteed to run when the system force-kills the process.
- Plugins cannot escalate privileges or change the execution tier the user selected; Root/ADB are available only within the tier the user has authorized.
- System limits still apply: background Activity starts on Android 10+, Doze/power saving delays for background tasks, the exact alarm permission, and system authorization and default app policies for notifications/SMS/phone.
- Known contract limits: `notifications().notify` has no receipt (use `notifyChecked` when you need one); `requestContributionsRefresh()` returns no handle; background refresh of `screen().status` cannot be stopped by a plugin; `jobs()` has no `list()`. Use the contract as it is; the interface is deliberately not expanded.


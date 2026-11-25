# Plugin UI Extensions & Design System

> Part of the [Plugin Developer Docs](README.md).

## UI extensions

The host fixes the main shell, navigation and chat skeleton; plugins can register **plugin pages**, attach Compose bindings to two slots, or contribute composer actions; they cannot replace a whole screen or register Activity/Service in the manifest. Plugin pages are the plugin's only self-drawn surface: they render inside the current navigation stack by default, and when a dedicated window is needed use the host-proxied plugin window (`components().startActivity`).

### Slot catalog

| slotId | Scenario |
|---|---|
| `tool.card.content:<toolName>` | Content area of the tool call card for the named tool |
| `chat.message:<customType>` | Content area for a custom message type |

`slotBinding(slotId, priority, content)`: only one binding renders per slot — the first renderable one by descending `priority` (same priority: plugin registration order), and the rest are overridden; when no binding is renderable the host falls back to the default view.

`SlotIds` provides helpers:

```kotlin
SlotIds.toolCard("template_echo")            // "tool.card.content:template_echo"
SlotIds.chatMessage("card")                  // "chat.message:card"
SlotIds.matches(slotId)                      // validates a full slot id
```

### Tool result renderers (1.3.0)

Slots are "registered by exact tool name", which suits a plugin's own tools. To render **MCP tools** or a batch of tools with different names with a plugin card, use tool result renderers:

```kotlin
class TravelPlugin : SuperPIPlugin {
    override fun ui(host: PluginHost): PluginUiContributions =
        object : PluginUiContributions, ToolResultRendererContributor {
            override val bindings = emptyList<SlotBinding>()

            override val toolResultRenderers = listOf(
                toolResultRenderer(
                    id = "amap-route",
                    match = toolResultMatch(
                        source = ToolResultSource.MCP,
                        server = "amap",
                        toolPrefix = "route_",
                    ),
                ) { state ->
                    val data = state.structuredContent as? JsonObject
                    DdGroupCard {
                        DdText("Route", style = DdType.heading)
                        DdText(data?.get("summary")?.jsonPrimitive?.content ?: state.result?.text.orEmpty())
                    }
                },
            )
        }
}
```

Match rules:

- `source`: `ToolResultSource.PLUGIN` / `MCP` / `BUILTIN`; empty means any;
- `server`: the MCP server name, which must match the name the user gave in MCP settings;
- `tool`: exact tool name match; `toolPrefix`: tool name prefix; both can be used together;
- only one renderer per tool: higher `priority` first, then the more specific match, then plugin registration order;
- when a plugin is disabled, the UI is incompatible or no renderer matches, the default tool card is used automatically; do not `require` inside a renderer.

`ToolResultState` provides:

| Field | Description |
|---|---|
| `callId` / `toolName` | The call id and the inner tool name; MCP tools called through `tool_execute` are restored to the real MCP tool name automatically |
| `source` / `server` | Tool source and MCP server name |
| `arguments` | Tool arguments; the `tool_execute` wrapper restores the inner arguments |
| `result` | The same `PluginToolResult` as the tool card slot (text/images/files/errors) |
| `structuredContent` | The `structuredContent` returned by the tool; MCP results are persisted by the host (256 KiB limit) and remain available for rendering after reopening the session; null when absent |

- MCP tools are called through `tool_search` + `tool_execute` by default, and renderers do not need to care about that wrapper.
- Structured content is for UI only and does not enter the model context; do not stuff images or large JSON into `details` expecting the host to render them — prefer `structuredContent`.
- Renderer content is subject to the same plugin UI compatibility constraints (`ui.apiLevel` / `kotlin` / `compose`) and retries automatically after a version upgrade; Compose exceptions have no crash isolation, just like slots, so test them yourself.
- Tool result renderers need `plugin-api 2.0.x` and host `2.0.0+`; plugins on the level 6 line keep using their 1.x SDK.

Pages and composer actions are contributions, not slots: `PluginUiContributions.pages` (built with `pluginPage(id, title, icon?, content)`) declares plugin pages with inline page content, and `PluginUiContributions.composerActions` (built with `composerAction(id, label, icon?)`) declares composer actions. `PluginUiContributions` is a read-only interface, `pages` / `composerActions` have default empty implementations, and plugins implement only `bindings` (plus the members they need). In the plugin page list, tapping a row that has `pages` enters the first page; composer action chips appear in the chat composer and open that plugin's page when tapped (the action id opens the matching page when it equals a page id, otherwise the first page).

### Pages and arguments

`pluginPage(id, title, icon?) { ... }` declares a page. Since 1.1.0 pages can be opened with arguments and return results:

```kotlin
host.ui().openPageWithArguments(
    pageId = "detail",
    arguments = buildJsonObject { put("id", "42") },
    newTask = false,
)
```

- With `newTask = true` the page opens in a dedicated host-proxied window (`PluginHostActivity`), which suits scanning, photography and long flows; `false` opens it inside the current navigation stack.
- Inside the page, read `PluginPageContext(pageId, arguments)` through `host.ui().pageContext()` or Compose's `LocalPluginPageContext.current`; it returns null when called outside a plugin page.
- Close a page with `host.ui().closePage(result)`, where `result` is optional JSON; closing the dedicated window returns to the host UI.
- You cannot open another plugin's page: `openPage(...)`/`openPageWithArguments(...)` only accept page ids declared by the calling plugin.

Since 1.2.0 a page can also open as a sheet stacked on the chat, for cases like "tap a chat card to see details" that should not leave the current session:

```kotlin
host.ui().openPageSheet(
    pageId = "detail",
    arguments = buildJsonObject { put("threadId", threadId) },
)
```

- The host carries the same `PluginPage.content()` in a bottom sheet and does not push a navigation page; swiping down, tapping the scrim, pressing back or the plugin calling `closePage()` all close the sheet and return to the chat.
- Inside the page, check the current presentation with `LocalPluginPageContext.current?.presentation == PluginPagePresentation.SHEET`; `pageContext().presentation` defaults to `PAGE`, and ordinary pages and dedicated windows are unaffected.
- Only one plugin page sheet is allowed at a time; opening a `DdSheet` inside it (for example a compose form or a picker) is allowed, and sheets close starting from the topmost one.
- When you need a full page, still use `openPage(...)`/`openPageWithArguments(...)`; a common pattern in plugin cards is "tap the content to open a sheet, open the full page from the footer".
- `isDark` follows the host's current theme (built-in or plugin theme), not just the system dark mode.

### Interaction and pickers

`UiService` provides host-style system interactions; plugins should not show their own Material dialogs. These methods need the page in the foreground, and calling them from the background or from tool callbacks may return null/false:

| Method | Returns | Description |
|---|---|---|
| `confirm(uiConfirmRequest(title, message?, confirmLabel?, cancelLabel?, destructive))` | `Boolean` | Confirmation dialog; `destructive` uses the danger color |
| `prompt(uiPromptRequest(title, message?, initial, placeholder?, multiline))` | `String?` | Text input; cancel returns null |
| `choose(uiChooseRequest(title, listOf(uiChoice(id, label, description?)), message?))` | `String?` | Single-choice list, returns the selected `id` |
| `pickDate(uiDatePickRequest(initialMs?, minMs?, maxMs?))` | `Long?` | Date picker |
| `pickTime(uiTimePickRequest(initialHour?, initialMinute?, is24Hour))` | `Int?` | Time picker, returns `hour * 60 + minute` |
| `pickFiles(uiFilePickRequest(mimeTypes, multiple, maxCount))` | `List<PluginPickedFile>` | System file picker; cancel returns an empty list |
| `pickImage(uiImagePickRequest(source = UiImageSource.GALLERY/CAMERA, frontCamera))` | `PluginPickedFile?` | Gallery or camera; cancel returns null |
| `scanQr()` | `String?` | Scan a code and return the text |
| `pickDirectory()` | `String?` | Pick a directory |
| `snackbar(message, actionLabel?, action?)` | — | Bottom light message, with one optional action |
| `shareFiles(paths, mimeType?)` | — | Share the plugin's own files (through the host FileProvider) |
| `isDark` | `Boolean` | Whether the current theme is dark |
| `openUrl` / `share(shareRequest(...))` / `toast(message)` | — | Open a link, share text, Toast (light feedback only) |

`PluginPickedFile` fields: `path` (a local path the host can read), `uri`, `name`, `sizeBytes`, `mimeType`.

```kotlin
val picked = host.ui().pickImage(uiImagePickRequest(source = UiImageSource.CAMERA))
val note = host.ui().prompt(uiPromptRequest(title = "Note", placeholder = "Optional")) ?: return
host.ui().snackbar("Imported", actionLabel = "View") { host.ui().openPage("home") }
```

- The host requests runtime permissions for camera/gallery/voice uniformly; plugins only declare `camera`/`microphone` in the manifest `permissions` (capability `ui`).
- `shareFiles` only accepts paths the plugin itself can read; to share another app's files, copy them into `host.dataDir` first.

### Compose rendering

`SlotContent` is a read-only interface built with the `composeSlot(render)` factory; `SlotState` depends on the slot family:

| Slot family | State | Fields |
|---|---|---|
| Tool card | `ToolCardSlotState` | callId/toolName/args/result (structured arguments and result) |
| Custom message | `CustomMessageSlotState` | messageId/customType/payload |
| Stateless | `NoSlotState` | — |

Plugin page content does not go through `SlotState`: the `content` of `pluginPage(...)` is a plain Composable (`@Composable () -> Unit`) that captures host or page state directly in its closure.

Compatibility checks (failing any one disables only that plugin's UI, never the plugin itself):

- Manifest `ui.apiLevel <= HOST_UI_API_LEVEL` (a missing `ui` block passes at the host level).
- If the manifest sets `ui.kotlin`, it must match the host Kotlin **major.minor version** (for example `2.4`).
- If the manifest sets `ui.compose`, it must match the host Compose runtime version **exactly** (not the Compose BOM version); the host also disables UI when it cannot detect its own Compose version. When unsure about the host version, leave both fields out and write only `ui.apiLevel`.
- When UI is disabled `pagesOf` returns empty and slots do not render, while the plugin's tools/skills/hooks still apply.

```kotlin
override fun ui(host: PluginHost) = object : PluginUiContributions {
    override val bindings = listOf(
        slotBinding(
            slotId = SlotIds.toolCard("template_echo"),
            content = composeSlot { state -> MyToolCard(state) },
        ),
    )

    override val pages = listOf(
        pluginPage(id = "home", title = localizedText("Home", "首页"), icon = assetIcon("asset:icon.png")) {
            MyPage(host)
        },
    )
}
```

- Slot and page content render on the Compose main thread; do not do blocking IO inside a composable — load data with `remember` + `LaunchedEffect` and handle cancellation.
- Slots and pages have no process-level isolation: an exception thrown by Compose content crashes the app (same as tool/callback exceptions), so plugin authors must verify it themselves. An incompatible binding only records the reason and falls back to the host view, leaving the plugin's other capabilities intact.
- External plugins cannot reference app resources; icons use `assetIcon("asset:<path inside package>")`, and page titles may omit the icon.

### Plugin UI uses only `:design`

Plugin UI uses the app's design system `io.github.whywood.superpi.design` directly. `plugin-api 2.0.2` already carries `design 1.1.0` through `api()`, so plugin projects normally declare only `plugin-api`; if you need an explicit pin, use `compileOnly("io.github.sadami3066.superpi:design:1.1.0")`. Components are identical to the app's and inventing styles is forbidden:

| Component | Purpose |
|---|---|
| `DdGroupCard` | White rounded group card (20dp radius, 16dp inner padding, hairline between rows) |
| `DdListRow` | List row inside a card (title/subtitle/trailing value/icon/clickable/trailing) |
| `DdSectionLabel` | Section label |
| `DdPrimaryButton` | Primary button (52dp tall, accent fill, at most one per screen) |
| `DdTextAction` | Text button (accent/inkSecondary for secondary actions) |
| `DdTextField` / `DdTextArea` | Text fields (52dp tall, accent border when focused) |
| `DdSwitch` | Switch (52x32, accent track) |
| `DdChip` | Tag/filter |
| `DdSheet` + `DdSheetHeader` | Bottom sheet and header |
| `DdDialog` | Centered confirmation dialog |
| `DdMenu` / `DdMenuItem` | Dropdown menu |
| `DdEmptyState` / `DdLoadingBlock` / `DdSpinner` | Empty and loading states |
| `DdHairline` | Divider |
| `DdType` | Type scale: `pageTitle`/`heading`/`body`/`bodySmall`/`label`/`button` |
| `superPiColors()` | Color tokens: `paper`/`surface`/`raised`/`ink`/`inkSecondary`/`inkTertiary`/`accent`/`accentSoft`/`danger`/`success`/`warning`/`hairline`/`wash` |

The current `design 1.1.0` SDK also includes:

| Component / API | Purpose |
|---|---|
| `DdMarkdown(text, live, onFileClick, onLinkClick, onImage)` | Markdown rendering (headings/lists/tables/code/images/footnotes); `live` for streaming text, `onImage` handles image taps |
| `DdHtmlText(html, onLinkClick?, imageLabel?, style, color, linkColor)` | Restricted HTML text |
| `DdHtmlBlocks(html, loader, onLinkClick, onImageClick, imageLabel, ...)` + `DdHtmlImageLoader` | HTML body with images: split by `<img>`, text blocks keep `DdHtmlText` formatting support; the loader returns `ImageBitmap` and shows a placeholder on failure |
| `DdWebView(url, state = DdWebViewState())` / `DdWebView(DdWebContent.Url/Html, ...)` | Embedded web page; `state` provides `canGoBack`/`goBack`/`reload`/`evaluateJavascript`/`dispose` |
| `DdImage(path / bytes / bitmap, ...)` / `DdRemoteImage(...)` | Local and remote images with placeholder, failure and rounded clipping; `DdRemoteImage` takes a loader and is backed by the host image cache |
| `DdImageViewer(path / bytes / bitmap, onDismiss)` | Full-screen image viewer (Dialog) |
| `DdPullToRefresh(isRefreshing, onRefresh) { ... }` | Pull-to-refresh container |
| `DdSnackbarHostState` + `DdSnackbarHost(state)` | In-page light messages; `showSnackbar(...)` returns a result |
| `DdFileRow(name, subtitle?, icon, onClick?, enabled, trailing)` | File/attachment row |
| `DdDatePicker(...)` / `DdTimePicker(...)` | Date and time picker sheets; copy comes from the caller |
| `DdSwipeRow(...)` + `DdSwipeAction(...)` | List row swipe actions: two anchors with snapping, only one row open at a time, haptics past the threshold |
| `DdInfoTooltip(text, contentDescription?, icon?)` | Info icon at the end of a row with a tap/long-press hint |
| `DdExpandToggle(expanded, collapsedLabel, expandedLabel, onToggle)` | Expand/collapse for long card lists: one line of text plus a rotating chevron, 44dp target; copy comes from the caller |
| `DdStatusRow(label, value, tone, busy)` | Status summary row with `DdStatusTone` |
| `DdNoticeBar(text, tone, actionLabel?, onAction?)` | Inline notice with `DdNoticeTone.INFO/SUCCESS/WARNING/DANGER` |
| `DdProgressBar(progress: Float?)` | Determinate or indeterminate progress; respects reduce motion |
| `DdSlider(...)` / `DdWordmark(...)` | Continuous-value slider and PI brand wordmark |
| `SuperPITheme(colors, fontStyle)` | Wraps a page in a custom `SuperPIColors` (the host already sets it, so plugins usually do not call it) |
| `DdTheme.lightPi()` / `DdTheme.darkPi()` | The default PI light and dark palettes; `lightClassicBlue()`, `lightSantorini()` and `darkOceanic()` remain available |
| `LocalDdMotionEnabled` | System "reduce motion" switch; custom animations skip when it is false |

```kotlin
if (items.size > PreviewRows) {
    DdExpandToggle(
        expanded = expanded,
        collapsedLabel = host.t("展开全部 ${items.size} 条", "Show all ${items.size}"),
        expandedLabel = host.t("收起", "Show less"),
        onToggle = { expanded = !expanded },
    )
}
```

```kotlin
DdGroupCard {
    DdStatusRow("Runtime", if (linuxAvailable) "Ready" else "Unavailable", if (linuxAvailable) DdStatusTone.SUCCESS else DdStatusTone.DANGER)
    DdStatusRow("Sign-in status", if (connected) "Connected" else "Not connected", if (connected) DdStatusTone.SUCCESS else DdStatusTone.NEUTRAL, busy = checking)
}
if (error != null) {
    DdNoticeBar(error, DdNoticeTone.DANGER, actionLabel = "Retry") { retry() }
}
DdProgressBar(progress = if (downloading) percent else null)
```

Import components such as `io.github.whywood.superpi.design.DdMarkdown` directly (or import the whole package); no extra dependency declaration is needed.

```kotlin
DdPullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true }) {
    DdMarkdown(
        text = state.markdown,
        onLinkClick = { host.ui().openUrl(it) },
        onImage = { image -> viewer = image.source },
    )
}
if (viewer != null) DdImageViewer(path = viewer, onDismiss = { viewer = null })
```

Date/time components use `java.time` (`LocalDate`/`LocalTime`). The current contract line writes `minHostVersion: "2.0.0"`; components added in 1.1.0 (including `DdExpandToggle` and `DdRemoteImage`) are available in the same SDK.

Conventions:

- Do not hardcode colors or type sizes; use only `DdType.*` and `superPiColors()`; do not use Material3's `Card`/`Button`/`OutlinedButton`/`TextButton`/`Switch`/`OutlinedTextField` directly — they look inconsistent with the app.
- The page is its own scroll container: `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp))`; do not add a title bar (the host already has a 64dp top bar), and add 24dp where bottom spacing is needed.
- State changes must be stable: do not clear known values into loading on init; keep old values while refreshing and mark only the affected area busy, to avoid page flicker.
- Show errors inline near where they happen (`DdType.bodySmall` + `danger` color) with the last part of the CLI/API output; do not show only an exit code and do not use a Toast.

```kotlin
@Composable
private fun StatusCard(host: PluginHost, status: Status) {
    val colors = superPiColors()
    DdGroupCard {
        DdListRow(title = "Runtime", value = status.runtime)
        DdListRow(title = "Sign-in status", value = if (status.authorized) "Connected" else "Not connected")
        if (status.cliVersion != null) {
            DdListRow(title = "CLI", value = status.cliVersion)
        }
    }
    if (status.error != null) {
        Text(status.error, color = colors.danger, style = DdType.bodySmall)
    }
}
```

### Plugin UI conventions

All official plugins converge on this skeleton in the same upgrade, and third-party plugins can follow it too:

- Page skeleton: 20dp horizontal, 12dp top, **24dp bottom**, 16dp between groups; the host already has a 64dp top bar, so do not repeat the title inside the page.
- Card inner padding: `DdGroupCard` only draws the card background and does not add horizontal padding for custom content. When placing `DdStatusRow`, `DdText`, `Row` or other custom content directly, wrap it in `Column(Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card))` (18dp) first so text never touches the card edges; `DdListRow` already carries 18dp through `LocalDdRowHorizontalPadding`, so do not wrap it in horizontal padding again; `DdNoticeBar` has its own padding. The page's outermost 20dp plus 18dp inside the card is normal double padding; do not remove it.
- Open with status: build a summary from `DdStatusRow` (runtime, component/CLI, account, sign-in status) with human-readable values ("Ready / Update available / Not connected"), never version numbers, paths or booleans; keep old values on refresh and mark only that row busy.
- Action hierarchy: at most one `DdPrimaryButton` per screen; secondary actions use text buttons or menus; irreversible actions such as sign-out and delete go through `host.ui().confirm(destructive = true)`.
- Feedback: show errors nearby with `DdNoticeBar` (danger color) plus "Retry", and retry must rerun the action that just failed, not just refresh the page; success/undo can use `snackbar` or a result card, and never report a path with a Toast alone.
- Progress: operations that take more than a second or two — install, download, sync — get a `DdProgressBar` plus stage text; offer cancel where possible, and stop in a retryable state after cancel.
- Subflows: sign-in, scanning, verification codes and WebView enter and leave as subpages with a transition, and system back leaves the subpage first; validate forms in place, handle the keyboard, and allow showing/hiding passwords.
- Motion: durations and curves come from `DdMotion` and respect `LocalDdMotionEnabled`; never cause layout jumps, and never hide state changes behind animation.
- Copy: Chinese and English in pairs, with no development terms such as CLI, paths, proot or version numbers; drop implementation details that need explaining, and use `InfoTooltip` for the genuinely necessary ones.
- Icons: use the consistent linear `DdIcons`, never emoji; add no one-off controls, and when `DdGroupCard`/`DdListRow`/`DdTextAction` fit, do not draw your own.

### Autonomous capabilities

Plugin tools are the agent's autonomous path and pages are the optional path for people; never build a capability only in the UI:

- When the user explicitly asks for an action, the agent should be able to call a tool to do it rather than send the user to tap a button on a plugin page; for example a mailbox provides `mail_draft` + `mail_send`, and sending does not need another tap.
- Tool execution does not need confirmation each time by default; do not add a "wait for the user to tap" gate inside a tool, or scheduled runs, background tasks and goal rounds get stuck. For more sensitive actions, write the consequences into the description/prompt so the model judges before calling, or let the user ask for it explicitly in the conversation.
- A UI upgrade must not delete or hide tools, and must not turn an existing capability into something only the UI can do; for every page change check "can the agent still do the same thing".
- Irreversible actions still need a trace: prefer the "create the object first (draft/preview), then execute" tool pairing, so the user can see in the chat history or a card what is about to happen.

### Custom message cards

Inside a tool execution, use `host.chat().sendMessage(...)` to write a message into the current session, optionally showing only a card, interrupting, or starting a new round:

```kotlin
val result = host.chat().sendMessage(
    sessionId = call.sessionId,
    text = "Sync finished, 12 files",
    options = chatMessageOptions(
        triggerTurn = false,
        display = chatDisplay(
            customType = "my.card",
            payload = buildJsonObject { put("title", "Sync finished") },
            text = "Card summary",
        ),
    ),
)
```

- `sessionId` comes from `PluginToolCall.sessionId` (resolved by the host during execute) or `session.snapshot()?.sessionId`.
- `options.triggerTurn = true` (default): when the session is idle, start a new round with `text` as the user message; when busy, interrupt by `options.deliverAs` (`STEER`, default) or queue (`FOLLOW_UP`), returning a result with `kind = ChatSendKind.QUEUED`.
- When `options.display` is non-null, write the card message first (equivalent to `postCustomMessage`), then trigger the turn; `triggerTurn = false` only writes the card.
- Returns `ChatSendResult` with `kind` of `ChatSendKind.STARTED`/`QUEUED`/`DISPLAYED`; an unknown session throws `IllegalArgumentException`.
- The card is written as a `custom_message` entry into the pi JSONL, can be restored after reopening the session, and renders through the `chat.message:<customType>` slot; `postCustomMessage` remains for writing only a card.
- A card delivered while a tool call is running renders inline as a node of that turn in occurrence order (not stacked at the end of the answer); a card delivered while the session is idle still appears as a standalone message at a turn boundary, and the order after reopening the session matches the live order.


## Pages and state

Plugin pages are the plugin's only interaction surface, and where the plugin keeps its own state:

- Pages are declared by `PluginUiContributions.pages`, and the `content` of `pluginPage(...)` is the page content (the host renders it directly, without slots); tapping a whole row in the list enters the first page, and pages switch with `host.ui().openPage(...)` / `openPageWithArguments(...)` (only the plugin's own pages can be opened).
- For a dedicated window or a long flow, use `openPageWithArguments(..., newTask = true)` or `components().startActivity(pluginActivityOptions(...))`: the host renders the same page inside `PluginHostActivity`, the page reads arguments from `pageContext()` / `LocalPluginPageContext`, and `closePage(result)` returns a JSON result when closing.
- Plugin data goes into `host.dataDir` and `host.settings()` (relative paths in `host.files()` root at `dataDir`), and secrets go into `host.secrets()`; do not cache state that must survive the process in a page.
- After an action in a page that affects tools or skills (install, sign-in, switches), call `host.requestContributionsRefresh()`: the host materializes again and triggers a session rebuild, and contributions apply at a safe point. For example, after installing a CLI in a page and returning to the tool list, the next session sees the new tools.
- Pages can call every declared host service; prefer `host.ui()` confirm/input/pickers (see "Interaction and pickers") over rolling your own Material dialogs, which look inconsistent with the app.
- `PluginPage.content` does not receive a `SlotState`; when you need the active session call `host.sessions().active`, and when you need page arguments read `pageContext()`.
- `host.requestContributionsRefresh()` is asynchronous and does not wait for completion; when the UI must confirm the result, use the plugin's own state marker and do not assume materialization has happened when it returns.
- After leaving a page the host cancels its coroutines such as `LaunchedEffect`; long tasks go on `host.scope`, never relying on the page lifecycle to stay alive.
- `network().get`/`post` and `PluginAvailability.isReady` are extension functions; with explicit imports you must import `get`/`post`/`isReady` separately (or import the whole `io.github.whywood.superpi.plugin.api.*` package).


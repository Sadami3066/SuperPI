# 08 · 附录：工具参数 description

[← 返回地图](README.md)

> **用途**：工具 schema 里每个参数说明的快速索引  
> **范围**：`put("description", "...")` 单行完整字符串  
> **注意**：多行字符串和字符串拼接只记录在正文对应工具条目，未在此展开

说明：以下是从当前源码中自动提取的、写在 `put("description", "...")` 中的单行参数说明。多行字符串、字符串拼接、运行时变量未完全展开；这些位置以正文和源码为准。

共 117 条。

| 位置 | 文本 |
|---|---|
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:29` | Questions to ask the user, one to five entries |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:35` | Unique short id for this question, echoed back in the answers |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:39` | Short header shown above the question, at most a few words |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:43` | The question text |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:47` | Whether the user may pick more than one option (default false) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:52` | Selectable options, at most eight. Omit to let the user type a free-form answer. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:58` | Option label; append (Recommended) to mark the recommended option |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/AskUserQuestionTool.kt:62` | Optional one-line explanation |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/DeferredTools.kt:86` | What the tool should do. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/DeferredTools.kt:93` | Maximum number of tools to return. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/DeferredTools.kt:165` | Deferred tool name returned by tool_search. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/DeferredTools.kt:172` | Arguments for the deferred tool. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:33` | get, create, edit, pause, resume, complete or blocked |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:37` | Id of the current goal from get_goal; required for change actions |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:41` | Current goal revision from get_goal; required for change actions |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:45` | Concrete objective, required for create and optional for edit |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:49` | Optional maximum goal rounds, 1-50; defaults to the configured limit |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/GoalTools.kt:53` | Concrete blocker description, required for action=blocked |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:29` | start, output, list or kill |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:33` | Shell command to run in the background, required for start |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:37` | Short label shown in the jobs list (defaults to the command) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:41` | Background job id from start or list; required for output and kill |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:45` | For output: wait for new output or job completion instead of returning immediately |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:49` | For output with wait: maximum wait in milliseconds (default 30000, clamped to 1..600000) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/JobTools.kt:53` | Optional reason recorded on the job for kill |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:24` | Display name of the mini app, 1-40 characters |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:28` | Short description shown in the app list, at most 300 characters |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:32` | Optional emoji icon, at most 8 characters |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:43` | Absolute path of the draft folder returned by miniapp_create; relative paths are rejected |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:59` | miniapp id, from miniapp_list |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:211` | Operation: get reads one key, set writes it, delete removes it, keys lists all keys |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:215` | miniapp id, from miniapp_list |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:219` | Data key for get, set and delete: 1-64 lowercase letters, digits, '-' or '_', starting with a letter or digit |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/MiniappTools.kt:222` | JSON value to store for action set, at most 256KB |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/PresentTool.kt:59` | One to four finished works to deliver to the user |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/PresentTool.kt:94` | Optional display title |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/SuperPIBashTool.kt:33` | Shell command to execute |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/SuperPIBashTool.kt:39` | Timeout in seconds (1-$BASH_MAX_TIMEOUT_SECONDS, optional, default 60) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/SystemShellTool.kt:23` | Shell command to run with Android shell privileges |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/SystemShellTool.kt:27` | Maximum run time in milliseconds (default 15000, clamped to 1..120000) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:42` | create, list, update or delete |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:46` | Timer id, required for update and delete |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:50` | Message sent to the current conversation when the timer fires |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:55` | Trigger kind: once for a single date and time, cron for a five-field cron expression |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:59` | Display name for the timer (defaults to the prompt preview) |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:63` | Date in YYYY-MM-DD format, required for once |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:67` | Local time in HH:mm 24-hour format, required for once |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:71` | Five-field cron expression in local time: minute hour day-of-month month day-of-week, e.g. 0 9 * * 1-5 |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TimerTools.kt:75` | Whether the timer is enabled |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TodoTools.kt:28` | The complete task list, replacing the previous list. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/TodoTools.kt:34` | Short task description. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/WorkspacePaths.kt:32` | Absolute path in the Android filesystem. |
| `apps/android/runtime/src/main/kotlin/io/github/whywood/superpi/runtime/memory/MemoryTools.kt:70` | Optional filter by category. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/web/WebTools.kt:38` | Maximum number of results to return. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/web/WebTools.kt:52` | HTTP or HTTPS URL of the page to fetch. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/web/WebTools.kt:56` | Maximum number of content characters to return. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:60` | Calendar event id for get, update or delete. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:64` | Target calendar id from action=calendars. Defaults to the primary writable calendar. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:68` | Event title for create, or a new title for update. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:86` | Whether the event lasts the whole day; defaults to false. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:90` | Optional event location; empty string clears it on update. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:94` | Optional event notes; empty string clears them on update. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:110` | Optional title filter for action=list. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/arrange/ArrangeTools.kt:114` | Maximum events for action=list, 1-$MAX_CALENDAR_LIMIT. Defaults to $DEFAULT_CALENDAR_LIMIT. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:306` | Maximum number of nodes to include, 1-$GUI_MAX_NODES. Defaults to $GUI_MAX_NODES. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:310` | Optional package whose window to read; other windows on that screen are then ignored for node reads and an error lists the visible windows when it is absent. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:314` | Keep only nodes whose visible text or content description contains this value, ignoring case, spaces and separators. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:318` | Keep only nodes whose content description contains this value, ignoring case, spaces and separators. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:322` | Keep only nodes with this view id, either full form (com.app:id/ok) or short form (ok). |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:326` | Keep only nodes with this view class, either full form (android.widget.Button) or simple form (Button). |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:330` | Maximum nodes to return, 1-50. Omit to return all matches. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:346` | Node ref from screen_observe on the same target screen; it is re-resolved by node identity when the node moves. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:350` | Visible text or content description to match case-insensitively as a substring, ignoring spaces and separators. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:354` | Content description to match case-insensitively as a substring, ignoring spaces and separators. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:358` | View resource id, either full form (com.app:id/ok) or short form (ok). |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:362` | View class name, either full form (android.widget.Button) or simple form (Button). |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:366` | Screen x coordinate in pixels; requires y and must lie inside the current screen. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:370` | Screen y coordinate in pixels; requires x and must lie inside the current screen. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:374` | Press duration in milliseconds, 0-$MAX_TAP_MILLIS. Values of 400 or more are treated as a long press. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:384` | Start x coordinate in pixels. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:388` | Start y coordinate in pixels. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:392` | End x coordinate in pixels. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:396` | End y coordinate in pixels. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:400` | Swipe duration in milliseconds, $MIN_SWIPE_MILLIS-$MAX_SWIPE_MILLIS. Defaults to $DEFAULT_SWIPE_MILLIS. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:438` | Number of swipe steps, 1-$MAX_SCROLL_AMOUNT. Defaults to $DEFAULT_SCROLL_AMOUNT. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:449` | Text to type. Text with non-ASCII characters needs accessibility plus a ref or fieldText. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:453` | Ref of the editable node from screen_observe on the same target screen; supports any Unicode text. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:457` | Visible text or content description of the editable node when no ref is known; requires accessibility. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:468` | Key name: ${SCREEN_KEYS.joinToString( |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:480` | Wait until this package owns the current window. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:484` | Wait until this text appears in a node, a content description or the window title. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:488` | Wait until this text is gone from nodes, content descriptions and the window title. |
| `apps/android/app/src/main/kotlin/io/github/whywood/superpi/screen/ScreenTools.kt:505` | Android package name from screen_list_apps; opens on the selected screen. |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/BashTool.kt:36` | Shell command to execute |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/BashTool.kt:40` | Timeout in seconds (optional, no default timeout) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/EditTool.kt:25` | Path to the file to edit (relative or absolute) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/EditTool.kt:29` | One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead. |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/EditTool.kt:35` | Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call. |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/EditTool.kt:39` | Replacement text for this targeted edit. |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/FindTool.kt:22` | Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts' |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/FindTool.kt:26` | Directory to search in (default: current directory) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/FindTool.kt:30` | Maximum number of results (default: 1000) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:29` | Search pattern (regex or literal string) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:33` | Directory or file to search (default: current directory) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:37` | Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts' |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:41` | Case-insensitive search (default: false) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:45` | Treat pattern as literal string instead of regex (default: false) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:49` | Number of lines to show before and after each match (default: 0) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/GrepTool.kt:53` | Maximum number of matches to return (default: 100) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/LsTool.kt:18` | Directory to list (default: current directory) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/LsTool.kt:22` | Maximum number of entries to return (default: $LS_DEFAULT_LIMIT) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/ReadTool.kt:32` | Path to the file to read (relative or absolute) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/ReadTool.kt:36` | Line number to start reading from (1-indexed) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/ReadTool.kt:40` | Maximum number of lines to read |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/WriteTool.kt:17` | Path to the file to write (relative or absolute) |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/tools/WriteTool.kt:21` | Content to write to the file |
| `SuperPI-KotlinSDK/pi-coding-agent/src/commonMain/kotlin/io/github/whywood/superpi/sdk/coding/mcp/McpToolSearch.kt:179` | Search query for deferred tools. |

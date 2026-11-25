# Architecture

**SuperPI** is a multi-surface monorepo: Kotlin/Compose on Android, a self-hosted TypeScript/Node 24 relay, and a shared wire protocol. `apps/desktop/` is being rebuilt and is not part of the 2.0.3 release.

```text
SuperPI/
├── apps/
│   ├── android/        # Gradle multi-module Android client + :plugin-api + :design
│   ├── desktop/        # Electron + React shell source (rewrite in progress, not in 2.0.3)
│   └── relay/          # Node 24 HTTP/WSS relay + SQLite Memory v3 store
├── packages/
│   └── protocol/       # Canonical wire frames, Memory v3 schemas, JSON test vectors
└── plugins/
    ├── official/       # Ten official .ddp modules + :cli-common
    ├── template/       # Standalone third-party plugin starter
    └── sample/         # Deterministic host E2E & binary compat fixture
```

## Android (`apps/android/`)

### Modules & Dependency Direction

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, single `MainActivity`, dependency graph, chat, settings, plugin manager, mini-app container, Super Mode UI, Memory screens, remote sessions |
| `:runtime` | Agent runtime: sessions, tools, Memory v3, Super Mode orchestration, jobs, timers, plugin hooks, mini-apps |
| `:runtime-api` | Pure JVM contracts decoupling runtime from the Android host |
| `:shell` | Alpine/proot user space plus Root chroot execution |
| `:design` | Shared Compose typography, `SuperPIColors` / `SuperPITheme`, components and icons; published as `design 1.1.0` |
| `:plugin-api` | Stable host-to-plugin ABI SDK; published as `plugin-api 2.0.2` (API level 7, host UI API level 3) |

Allowed dependency graph (verified on CI by `verifyModuleBoundaries` and `verifyPackageBoundaries`):

- `:app` → `:runtime`, `:shell`, `:design`, `:plugin-api`
- `:runtime` → `:runtime-api`
- `:plugin-api` → `:design`

### Runtime Invariants

- **JSONL authority**: pi session JSONL logs are the single source of truth; UI lists, pins, archives, and unread indicators are derived projections.
- **Dynamic environment resolution**: `HostEnvironmentProvider` resolves the active tier (`Normal` / `Wireless ADB` / `Root`) per tool call via `DynamicShellExecutor` / `DynamicFileSystem` rather than caching a snapshot at session creation.
- **Process panel vs Answer cards**: tool execution streams raw input/status/output inside the process panel; rich cards from plugin tools render only in the final answer area. Plugins that run a CLI through bash return plain text and produce no cards.
- **Super Mode isolation**: the router conversation and its persistent workers are separate session engines; the router only sees its own timeline plus the live worker registry. A bot owns at most five persistent worker slots (`SuperRuntime.MAX_WORKERS_PER_BOT`), and local execution is queued by `LocalWorkerPool`. Remote workers are addressed through the relay's `worker.*` routes.
- **In-app updater contract**: [UpdatePolicy.kt](../../apps/android/app/src/main/kotlin/io/github/whywood/superpi/data/update/UpdatePolicy.kt) polls GitHub `/releases/latest` of `Sadami3066/SuperPI`, prefers `PI-<version>.apk`, checks the asset SHA-256 digest, and displays the Release Markdown body verbatim. Non-Android releases (`relay-v*`, `plugin-api-v*`, `plugin-*`) publish with `--latest=false` so Android updates are never shadowed.

### Super Mode (`apps/android/runtime/superagent`)

- `SuperTaskStore` keeps the team whitelist, worker metadata, steps, artifacts, cards and pending questions in `files/agent/super/super_tasks.db`.
- `SuperThreadEngine` owns the router timeline at `files/agent/super/router/session.jsonl` with a restricted tool surface and an isolated question center.
- `LocalWorkerPool` runs local workers with FIFO queuing, a stall watchdog, read-only memory tools and `worker://` artifact staging.
- `SuperOrchestrator` merges local and remote worker milestones into bounded receipts and resolves zero-hop approvals; `RemoteWorkerGateway` is implemented by the app-side `RemoteWorkerClient`.
- The UI lives in `ui/superagent/`: coordinator chat, team management, worker monitor, inline approvals and queued follow-ups.

### Memory v3 (`apps/android/runtime/memory`)

- One SQLite database (`memory.db`, WAL, FTS5) stores `memory_blocks` (living profile), `entities` / `entity_relations` (people and relations), `memory_events` / `event_entities` (recent events and to-dos), proposals, audit log and sync state.
- `MemoryContextRenderer` renders the four injection budgets each turn — living profile, people and relations, recent events, and the deep memory index — with per-section quotas and Markdown export.
- `MemoryAgentService` schedules immediate and deep consolidation, retries failed ingestions, and coordinates a cross-device consolidation lease. `MemoryGuardAndAudit` protects pinned/confirmed items and keeps revertible activity history.
- `MemorySyncStorage` implements incremental push/pull, account switching, CAS acknowledgements and last-writer-wins merging. The wire schema lives in `packages/protocol/src/memory.ts`; the Android client is `data/relay/RelayMemorySync.kt`.
- Bot-level memory scope (`PERSONAL` / `WORK_ONLY` / `OFF`) is enforced at retrieval and sync time, not by page filtering.

## Relay & Protocol (`apps/relay`, `packages/protocol`)

- **`@superpi/protocol` `0.2.0`** defines canonical TypeScript types, strict frame validators, Memory v3 schemas, and language-agnostic JSON vectors under `vectors/`.
- **`@superpi/relay` `0.2.0`** uses Node 24 native type-stripping and `node:sqlite`. It handles device enrollment, CA fingerprint pinning, presence derived strictly from live WebSocket sessions, `route` / `route_event` forwarding, workspace file transfer, `present` artifact streaming, `super-worker` delegation, and Memory v3.
- **HTTP**: `POST /v3/memory/push`, `GET /v3/memory/pull`, `POST`/`DELETE /v3/memory/consolidation-lease`. Memory bodies are capped at 4 MiB; other JSON bodies at 64 KiB; body reads time out after 30 s (enrollment uses 15 s).
- **WebSocket**: frames are capped at 8 MiB; a single route result is capped at 4 MiB and returns a structured `payload_too_large` error without dropping the connection. Unknown `route_error.code` values normalize to `internal`. Presence and heartbeat are connection-derived.
- **Storage**: `relay.db` holds accounts, devices, pairing codes, Memory v3 records, a compacted change log, and consolidation leases. The server does not interpret conversations.

## Desktop (`apps/desktop`)

The Electron/React shell source remains in the monorepo, but it is being rebuilt and is not shipped with 2.0.3. Do not treat its current relay or memory client as part of the release contract.

## External Companion Repositories

- **`SuperPI-KotlinSDK`**: KMP agent runtime (`pi-ai`, `pi-agent`, `pi-mcp`, `pi-coding-agent`) consumed via `SuperPI-Maven` (or `-Psuperpi.localSdk=true`).
- **`SuperPI-Market`**: ECDSA-signed `registry.json` + `.sig` deployed to GitHub Pages, accepting community PRs for plugins, skills, and MCP servers.
- **`SuperPI-Maven`**: Static Maven repository for `SuperPI-KotlinSDK`, `plugin-api`, and `design`.
- **`SuperPI-Personal-Plugins`**: Personal and campus plugins (`sysu 2.7.4`, `wechat 0.5.3`, `zanao 1.0.19`).

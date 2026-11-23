# Architecture

**SuperPI** is organized as a multi-surface monorepo pairing Kotlin/Compose on Android with TypeScript/Node 24 on Desktop and Relay.

```text
SuperPI/
├── apps/
│   ├── android/        # Gradle multi-module Android client + :plugin-api + :design
│   ├── desktop/        # Electron + React + Vite wrapper over pi --mode rpc
│   └── relay/          # Node 24 HTTP/WSS relay + SQLite Memory v2 store
├── packages/
│   └── protocol/       # Canonical wire frames, Memory v2 schemas, JSON test vectors
└── plugins/
    ├── official/       # Gradle build for 10 official .ddp plugins + :cli-common
    ├── template/       # Standalone third-party plugin starter
    └── sample/         # Deterministic host E2E & binary compat fixture
```

## Android (`apps/android/`)

### Modules & Dependency Direction

| Module | Responsibility |
|---|---|
| `:app` | Compose UI, single `MainActivity`, dependency graph, chat, settings, plugin manager, miniapp container |
| `:runtime` | Agent runtime: sessions, tools, Memory v2 engine (ingest/dream/FTS), jobs, timers, plugin hooks |
| `:runtime-api` | Pure JVM contracts decoupling runtime from Android host services |
| `:shell` | Alpine/proot user-space bridge and ADB / Root `HostEnvironment` backends |
| `:design` | Shared Compose typography, color system (`SuperPIColors`), spacing, icons — also published to plugins |
| `:plugin-api` | Stable host-to-plugin ABI SDK — also published to `SuperPI-Maven` |

Allowed dependency graph (verified on CI by `verifyModuleBoundaries` & `verifyPackageBoundaries`):
- `:app` → `:runtime`, `:shell`, `:design`, `:plugin-api`
- `:runtime` → `:runtime-api`
- `:plugin-api` → `:design`

### Runtime Invariants

- **JSONL authority**: pi session JSONL logs are the single source of truth; UI lists, pins, archives, and unread indicators are derived projections.
- **Dynamic environment resolution**: `HostEnvironmentProvider` resolves active tier (`Normal` / `Wireless ADB` / `Root`) per tool call via `DynamicShellExecutor` / `DynamicFileSystem` rather than caching snapshots on session creation.
- **Process panel vs Answer cards**: tool execution streams raw input/status/output inside the process panel; rich cards provided by plugin tools render only in the final answer area.
- **In-app updater contract**: [UpdatePolicy.kt](../../apps/android/app/src/main/kotlin/io/github/whywood/superpi/data/update/UpdatePolicy.kt) polls GitHub `/releases/latest` of `Sadami3066/SuperPI`, prefers `PI-<version>.apk`, checks the asset SHA-256 digest, and displays the Release Markdown body verbatim. Non-Android releases (`desktop-v*`, `relay-v*`, `plugin-*-v*`) MUST publish with `--latest=false` so Android updates are never shadowed.

## TypeScript & Relay (`apps/desktop`, `apps/relay`, `packages/protocol`)

- **`@superpi/protocol`** (`packages/protocol`) defines canonical TypeScript types, strict validators, Memory v2 schemas, and language-agnostic JSON vectors under `vectors/`.
- **`@superpi/relay`** (`apps/relay`) uses Node 24 native type-stripping (`erasableSyntaxOnly: true`, no enum/namespace) + `node:sqlite`. It handles device enrollment, CA fingerprint pinning, presence derived strictly from active WebSocket sessions, `route` / `route_event` RPC forwarding, and Memory v2 (`/v2/memory/push`, `/v2/memory/pull`, `/v2/memory/dream-lease`).
- **`@superpi/desktop`** (`apps/desktop`) runs official `pi --mode rpc` child processes per local bot directory (and isolated dedicated `pi` child processes when serving inbound remote calls), syncing shared Memory v2 snapshots locally via `MemoryClient` and `extensions/superpi-memory.mjs`.

## External Companion Repositories

- **`SuperPI-KotlinSDK`**: KMP agent runtime (`pi-ai`, `pi-agent`, `pi-mcp`, `pi-coding-agent`) consumed via `SuperPI-Maven` (or `-Psuperpi.localSdk=true` composite build).
- **`SuperPI-Market`**: ECDSA-signed `registry.json` + `.sig` deployed to GitHub Pages, accepting community PRs for plugins, skills, and MCP servers.
- **`SuperPI-Maven`**: Static Maven repository updated by CI workflows in `SuperPI` and `SuperPI-KotlinSDK`.

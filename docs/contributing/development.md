# Developer Guide

For contributors modifying the Android app, Desktop client, Relay server, Wire Protocol, or Official Plugins. Plugin-only authors should start at [Plugin Developer Docs](../plugins/README.md).

## Toolchains

| Area | Requirements |
|---|---|
| **Android & Plugins** | JDK 17 (`jvmToolchain(17)`), Android SDK Platform 36 & Build-Tools `36.0.0` (~5.6 GB), ≥ 8 GB RAM, bash + `flock` (Linux, macOS, or WSL2) |
| **Desktop / Relay / Protocol** | Node.js ≥ 24, `pnpm` 12+, `pi` (`@earendil-works/pi-coding-agent`) on `PATH` for desktop integration tests |

## Quick Commands

### TypeScript Workspace (Repo Root)

```bash
pnpm install             # install dependencies and prepare Electron binary cache
pnpm typecheck           # tsc across @superpi/protocol, @superpi/relay, @superpi/desktop
pnpm test                # run vitest suites (including live Desktop <-> Relay integration tests)
pnpm build:desktop       # bundle main/preload (esbuild) and renderer (vite)
pnpm dev:relay           # run relay in watch mode
pnpm dev:desktop         # launch built Electron shell
```

### Android App (`apps/android/`)

Always invoke Gradle through `scripts/gradlew-locked` or `scripts/build.sh` so concurrent builds are serialized with `flock` and heap usage stays bounded:

```bash
cd apps/android
./scripts/dev-setup.sh            # first-time setup: verify JDK 17 & Android SDK 36, build debug APK
./scripts/build.sh quick          # :app:compileDebugKotlin
./scripts/build.sh test           # :app:testDebugUnitTest
./scripts/check.sh                # compile + unit tests + lint + module/package boundaries + APK
./scripts/check.sh --full         # full delivery gate + plugin-api & design ABI compatibility
./scripts/check.sh --device       # full check + install & launch on ADB device
./scripts/api-compat.sh           # verify plugin SDK ABI (--update regenerates baselines)
./scripts/build.sh stop           # stop Gradle/Kotlin daemons to reclaim RAM
```

### Local Co-Development with `SuperPI-KotlinSDK`

If you have `SuperPI-KotlinSDK` checked out next to this repository (`../SuperPI-KotlinSDK`), link source changes directly without publishing to Maven:

```bash
cd apps/android
./scripts/gradlew-locked -Psuperpi.localSdk=true :app:testDebugUnitTest
# or export SUPERPI_LOCAL_SDK=1
```

### Plugins (`plugins/`)

```bash
cd plugins/official
./scripts/check.sh                # unit tests + assemblePlugin + signPlugin + verifyPlugin (all 10 modules)

cd ../../plugins/sample
./gradlew verifyPlugin --no-daemon
```

Device E2E suites (run from `apps/android/` with an ADB device attached):

```bash
./apps/android/scripts/plugin-e2e.sh          # uses plugins/sample/build/plugin/sample.ddp
./apps/android/scripts/plugin-bundles-e2e.sh  # installs & updates official .ddp bundles
```

### All-in-One Local Verification

From repo root:

```bash
./scripts/check-all.sh
```

## Signing Keys & Local Secrets

Private signing keys and keystore credentials NEVER belong in any git repository. Store maintainer secrets outside all repos at `~/.config/superpi/secrets/` (or set `SUPERPI_SECRETS_DIR`):

- `~/.config/superpi/secrets/keystore.properties` & `android-release.keystore` — Android APK release signing (checked before `apps/android/keystore.properties`)
- `~/.config/superpi/secrets/plugin-signing.pem` & `.pub` — Official Ed25519 `.ddp` signing (override with `-PpluginSigningKey=...` or `SUPERPI_PLUGIN_SIGNING_KEY`)
- `~/.config/superpi/secrets/market-private.pem` — ECDSA key for signing `SuperPI-Market` `registry.json` locally

If `keystore.properties` is absent, Android `release` builds fall back to the debug keystore so anyone can compile `assembleRelease` locally; never distribute debug-signed release APKs.

## Conventions

- One logical change per commit (`type(scope): summary`).
- When replacing an architecture or UI path, delete the dead legacy code in the same change.
- User-facing Android strings belong in `apps/android/app/src/main/res/values*/strings_*.xml` (`values` zh + `values-en` en), never concatenated in Kotlin code.
- If updating `THIRD_PARTY_NOTICES.md` at repo root, keep `apps/android/app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md` byte-identical (`verifyDocFacts` enforces this).
- Prompt & context engineering map lives in [Prompt & Context Reference](context/README.md).

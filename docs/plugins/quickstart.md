# Plugin Quick Start

> Part of the [Plugin Developer Docs](README.md).

Development documentation for external plugin authors: project setup, contracts, packaging and signing, install and update, and every extension surface. Official plugins and the template are in [`plugins/official`](../../plugins/official) and [`plugins/template`](../../plugins/template). Product capabilities: [Getting Started](../user/getting-started.md); builds and modules: [Developer Guide](../contributing/development.md).

PI plugins are **Kotlin code plugins**: implement `SuperPIPlugin` from `plugin-api`, package as `.ddp`, and the host loads them inside the app process. Plugins install only from `.ddp`; nothing is compiled into the app.

**Security premise**: plugins share the app process and UID with no sandbox, and installing means trusting — plugin code is equivalent to app code and can read the app's private data, reflect into host internals and crash the app. Install only plugins you trust; use MCP for third-party tool sources.

## Quick start

`plugin-api` is the only contract artifact (Android library, package `io.github.whywood.superpi.plugin.api`): the current release is `2.0.1`, whose transitive dependency is `design 1.0.0` (take the latest release in `SuperPI-Maven` as authoritative); `PLUGIN_API_LEVEL = 7`, `BASELINE_API_LEVEL = 7`, `MIN_SUPPORTED_API_LEVEL = 7`, `HOST_UI_API_LEVEL = 3`.

`plugin-api 2.0.0` / `design 1.0.0` start the SuperPI contract line at API level 7: the Kotlin package, plugin interface and Maven group are renamed, plugin ids use `io.github.whywood.superpi.plugin.*`, and host UI API level is 3. Plugins must be rebuilt and declare `apiLevel: 7`; old 1.x plugins are rejected. Later 2.x releases stay binary-compatible within level 7. The compatibility promise is in `plugin-api/COMPATIBILITY.md`: within one `apiLevel` only binary-compatible additions, and deleting, renaming, changing signatures or changing semantics requires a new `apiLevel`; the host rejects plugins with an `apiLevel` above its own, and level 5 has not been accepted since 1.0.4.

Start a plugin project from [`plugins/template/`](../../plugins/template): a standalone Gradle Android project with an example tool, an example skill, one prompt section and the full `.ddp` packaging/signing/verification implementation. Copy or rename it as a starting point. Official plugin implementations are in [`plugins/official/`](../../plugins/official).

Prerequisites:

- JDK 17+ and the Android SDK; locate the SDK with the `ANDROID_HOME`/`ANDROID_SDK_ROOT` environment variables or `sdk.dir` in the project's `local.properties`.
- Plugins depend on the public Maven repository `https://sadami3066.github.io/SuperPI-Maven` (group `io.github.sadami3066.superpi`, currently `plugin-api 2.0.1` / `design 1.0.0`, take the latest release as authoritative); no local mavenLocal publish is needed.

`plugin-api` is the contract, and `design` is the UI design system shared with the app (plugin UI uses it directly, and `plugin-api` brings it in through `api()`). Publishing a new version:

```bash
apps/android/scripts/gradlew-locked :design:publishAllPublicationsToPagesRepository :plugin-api:publishAllPublicationsToPagesRepository
# artifacts land in apps/android/build/maven-repo/io/github/sadami3066/superpi/
```

Sync the artifacts into the matching directories of the `SuperPI-Maven` repository and push; Pages updates automatically. When the SDK version goes up, update `compileOnly` in each module's `build.gradle.kts` and the template version too.

Adding a plugin:

```bash
# 1. Copy the template and rename it (run from the plugin repository root)
cp -R template my-plugin && cd my-plugin
#    Change rootProject.name in settings.gradle.kts (it decides the .ddp file name)
#    Change namespace/applicationId/versionName in build.gradle.kts
#    Change id/version/entryClass/name/summary/provides in src/main/assets/plugin.json
#    Rename the package and entry class under src/main/kotlin

# 2. Package + sign + self-check
./gradlew assemblePlugin
./gradlew signPlugin
./gradlew verifyPlugin

# 3. Artifacts
#    build/plugin/<rootProject.name>.unsigned.ddp   unsigned (for troubleshooting)
#    build/plugin/<rootProject.name>.ddp            installable signed package
```

With the template unchanged, `rootProject.name` is `plugin-template` and the artifacts are `template/build/plugin/plugin-template.ddp` (unsigned: `plugin-template.unsigned.ddp`); renaming changes the file names too. The module builds only the `debug` variant, `release` runs no minify, and the `.ddp` is repackaged from the debug APK. When the template is one module of a multi-module project (for example `:template`), run tasks per module such as `:template:verifyPlugin`, and artifacts land in `<module>/build/plugin/<module>.ddp`.

Installing: transfer the `.ddp` to the phone, then open it with PI from a file manager, share the file to PI, or pick it with "Import plugin" on the Plugins page. The confirmation page shows name/version/size/whole-package sha256/publisher name and `keyId`, the tools/skills/pages/prompt override (when `prompt` is declared) it will provide, and the capability/permission/component declarations; the first import asks you to trust the publisher, and only after confirming does the host write the TOFU trust store, unpack, install and enable immediately (when `defaultEnabled` is true). Later upgrades must be signed with the same publisher key.

Device regression is driven by scripts at the host repository root (with the device connected): `scripts/plugin-bundles-e2e.sh` regresses install and activation for the five official plugin bundles; `scripts/plugin-e2e.sh` regresses install, run, disable and upgrade with the `sample` test fixture from the plugin repository. The scripts install the debug build with `adb install`, push the `.ddp` into the app's external private directory, need no root, and run on normal devices or emulators.

## Project and dependencies

A plugin is an ordinary Android application project; the artifact only needs `classes*.dex` and `assets/**` (see "`.ddp` package format"), and the APK's manifest, resources and signature do not take part.

| Item | Value |
|---|---|
| AGP | 9.4.1 |
| Kotlin (same version as the Compose compiler plugin) | 2.4.21 |
| Compose BOM | 2026.03.00 |
| kotlinx-coroutines | 1.11.0 |
| kotlinx-serialization-json | 1.12.0 |
| compileSdk / targetSdk | 36 |
| minSdk | 29 (a plugin dex minApi cannot be higher than the host) |
| JVM target | 17 |

Dependency rules:

- Libraries the host already provides can only be `compileOnly`: `io.github.sadami3066.superpi:plugin-api` (version from the latest `SuperPI-Maven` release; `api()` carries the matching `design:1.0.0`, and plugin UI uses its Dd components directly), Compose (`ui`/`foundation`/`material3` + BOM), `kotlinx-coroutines-core`, `kotlinx-serialization-json`.
- The host release applies R8 keep to plugin runtime dependencies (`plugin-api`, `design`, Kotlin, coroutines, Compose, plus `androidx.lifecycle`/`androidx.activity`/`androidx.savedstate` commonly used by plugin UI), so these libraries never lose classes in the minified build. Keep preserves ABI names only and is not a version compatibility promise: plugins still need a rebuild for each host SDK version, and host libraries outside the allowlist are not guaranteed to work.
- **Do not** use `implementation` for these libraries: the host is parent-first, `plugin-api` and Compose exist only in the host, and duplicates packed into the plugin trigger class conflicts.
- A plugin may bring its own third-party libraries, but they must be packed into `classes*.dex` and must avoid packages and class names the host already occupies.
- Expose only `plugin-api`; do not reflect into app internals (upgrades will break it).
- Native libraries: `lib/<abi>/**` in the `.ddp` enters the signature digest, but the host does not add it to the classloader's library search path, so `System.loadLibrary` does not work.
- No `res/`, no `R`: copy, icons and skill files all go through the manifest and assets.


## Debugging and common errors

Build/package/sign (copy `plugins/template/` or test directly inside `plugins/template/`, artifacts in `build/plugin/plugin-template.ddp`):

```bash
cd template && ./gradlew assemblePlugin signPlugin verifyPlugin

# Official plugin repository (plugins/official root)
./scripts/check.sh        # build, sign and verify all five official plugins
```

Troubleshooting paths:

- **Pre-install self-check** (`Signature OK` from `verifyPlugin` only proves the signature matches the digest and **does not mean it can be installed**; check each item before importing):
  - `id` matches the reverse-domain regex (at least two segments, e.g. `com.example.myplugin`);
  - `version` is `major.minor.patch[suffix]`; `apiLevel` is 6 (≤ the host `PLUGIN_API_LEVEL`); `minHostVersion` is not higher than the host version (write `1.0.6` for 1.1.0 capabilities, keep `1.0.4` when using only older ones);
  - `permissions`/`components` match actual use and pair with `capabilities` (validated at install);
  - the `entryClass` class name is valid, the class is in one of the root `classes*.dex` files and has a no-arg constructor;
  - `provides` matches the actual contributions (tools/skills/uiSlots/pages), and tool names do not conflict with host reserved names;
  - `provides.themes` matches the ids in `assets/themes.json` exactly, and theme fields/colors/contrast/size pass validation;
  - slot ids pass `SlotIds.matches` (`tool.card.content:<tool>`, `chat.message:<customType>`), and plugin page ids pass `pages` validation;
  - `assets/plugin.json` is at the right path in the package and there is at least one root `classes*.dex`.
- **Install rejected**: the confirmation page or a toast gives the reason; common ones are manifest validation codes (`INVALID_ID`/`INVALID_VERSION`/`INVALID_PROVIDES` and so on), signature errors (tampered package, public key not matching keyId, missing signature file), publisher mismatch (key changed on upgrade), tool name conflict (`provides.tools` conflicting with the host or an installed plugin), version conflict (not newer than the installed version), theme pack errors (`THEMES_INVALID`: schema/token/color/contrast/size/id mismatch).
- **Install rejected (permissions/components)**: `INVALID_PERMISSION` for unknown or duplicate permission values; `PERMISSION_WITHOUT_CAPABILITY` when a permission has no matching capability; `COMPONENT_WITHOUT_CAPABILITY` when `components` field types or structure are invalid; `COMPONENT_WITHOUT_CAPABILITY` when a component is enabled but its capability is missing; `INVALID_DEEPLINK` for malformed or duplicate deep link paths.
- **Calling a service throws `PluginServiceUnavailable`**: the manifest `capabilities` does not declare that service accessor; capabilities take effect at install time, cannot be added while running, and need a reinstall after adding.
- **Permissions or special grants not granted**: request runtime permissions with `permissions().request(...)`; special grants such as notification access, accessibility and overlay can only be guided with `permissions().openSettings(...)` for the user to enable in system settings, and only then does `special(...).granted` become true.
- **No notification listening data**: needs `permissions: ["notifications.listen"]`, `components.notificationListener: true` and the `notifications` capability, granted under the system "Notification access"; with `listenerGranted=false`, `recent`/`received` are empty.
- **Share/deep link/background task not firing**: check the capability, the `components` switch and `deeplinks` path matching (equal, or starting with `declared/`); system events go only to global hooks.
- **Background tasks delayed or not running**: WorkManager periodic tasks are affected by Doze and power saving (minimum about 15 minutes); for reliable execution request a battery optimization exemption and guide the user.
- **Experimental API compile error**: `components()`/`credentials()`/`accessibility()` and `PromptOverridePlugin` need `@OptIn(PluginExperimentalApi::class)`.
- **Page arguments unavailable**: `pageContext()`/`LocalPluginPageContext` are only valid while a plugin page renders, so read them inside the page content.
- **Compile errors with old APIs**: `Unresolved reference` on old symbols such as `PluginTool(` / `PluginToolResult.Text` / `PluginEvent.SessionStarted` / `literal(` / `IconRef.Asset` → replace factories and interfaces item by item per "Migrating from API level 5 to level 6".
- **Level 5 plugin rejected**: since 1.0.4 the host accepts only `apiLevel = 6`; old packages must be rebuilt with `plugin-api 1.0.0`, raise `apiLevel` and `minHostVersion`, and be signed again.
- **Load failure**: `ClassNotFoundException` → wrong `entryClass` or minified away; `NoSuchMethodException` → missing no-arg constructor; `NoClassDefFoundError` → host version mismatch or host dependencies packed into the bundle.
- **Behavior differs**: `host.logs()` goes to the host log (logcat tag `SuperPIPlugin/<pluginId>`); exceptions thrown by plugins are isolated by the host and shown in the plugin row error.
- **Tools/skills unchanged after refresh**: after changing state in a page, call `host.requestContributionsRefresh()`; a busy session is rebuilt only after the current round ends, and the current round does not change midway.
- **File rows not showing**: `fileRefBlock(...)` uses the path exactly as the plugin gives it, absolute; file rows are restored by the host from the reserved details key `superpi.fileRefs`, so do not write that key yourself; if `afterToolExecution` swaps the result with `resultReplace(...)` and drops the details, the file rows disappear with it.
- **Hooks not effective / events still arriving after disable**: hooks apply whether returned from `contributions()` or `contributionsFor()`; when only `contributionsFor` is written and it returns the default `PluginHooks.None`, the global hooks are used; session contributions are materialized once per session, and plugin enable/disable or refresh rebuilds the session at a safe point (after the current round) before materializing again; the current run dispatches by the frozen set, so a disabled or uninstalled plugin still receives the run's remaining events, which is expected.
- **Skills not appearing**: the plugin must be enabled and `availability()` must be `Available` (skills are not enabled when unavailable); SKILL.md must have frontmatter, `PluginSkill.id` must match `name`, and `files` paths must be relative and safe.
- **Slots/pages not showing**: the slot id must pass `SlotIds.matches`; `provides.uiSlots` must match the actual bindings (at runtime `ui()` is authoritative); the page must appear in `PluginUiContributions.pages`; check the manifest `ui` fields (`ui.apiLevel`/`ui.kotlin`/`ui.compose`) against the host versions; when incompatible the whole UI block is disabled, falling back to host default views or an unavailable notice, and `host.logs()` records the reason.
- **Update failed**: confirm the signature `keyId` matches the first install and the whole-package sha256 matches the record; a changed key can only be installed as a new publisher.

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

## Reference

- Contract source: `plugin-api/src/main/kotlin/io/github/whywood/superpi/plugin/api/` in the host repository; compatibility contract and changelog: `plugin-api/COMPATIBILITY.md`, `plugin-api/CHANGELOG.md`; ABI gate: `./scripts/api-compat.sh`
- Design system: `design/` in the host repository (design 0.7.0 components and usage, carried to plugins through `plugin-api`)
- Plugin projects: [`plugins/official/`](../../plugins/official) (official implementations) and [`plugins/template/`](../../plugins/template) (template and starter project)
- Product capabilities: [Getting Started](../user/getting-started.md); builds and modules: [Developer Guide](../contributing/development.md)

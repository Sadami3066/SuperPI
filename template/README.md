# Super PI Agent plugin template

A standalone Gradle Android project for building external Super PI Agent plugins and packaging them into signed `.ddp` bundles. Copy this directory, change the plugin id, entry class and package name, and you can build your own plugin.

## Requirements

- JDK 17
- Android SDK: compileSdk 36, minSdk 29
- Gradle 9.8.2 (wrapper included)
- Depends on `plugin-api:2.0.0` from <https://sadami3066.github.io/SuperPI-Maven>

Set `sdk.dir=/path/to/Android/Sdk` in `local.properties`, or set `ANDROID_HOME`.

## Build

```bash
./gradlew generateSigningKey  # first run: creates keys/plugin-signing.pem and .pub
./gradlew assemblePlugin      # build/plugin/plugin-template.unsigned.ddp
./gradlew signPlugin          # signs with the Ed25519 key in keys/, producing build/plugin/plugin-template.ddp
./gradlew verifyPlugin        # verifies the signature and manifest
```

`keys/` is gitignored — **never commit the private key**, and keep your own backup: if it is lost you can no longer publish updates under the same plugin name. `signPlugin` only reads the key and fails with generation instructions when it is missing. Use `-PpluginSigningKey=keys/plugin-signing` to point at another location (a PKCS#8 PEM path or project-relative path, without the `.pem`/`.pub` extension).

Changing the key changes the publisher identity: the marketplace treats it as a new publisher, and installed users cannot upgrade with the new key (PUBLISHER_MISMATCH).

To install, transfer the `.ddp` and use "Plugins → Import plugin" in the PI App.

## Turn it into your plugin

1. `rootProject.name` in `settings.gradle.kts`, plus `namespace` / `applicationId` and `versionName` in `build.gradle.kts`.
2. `src/main/assets/plugin.json`: `id` (dotted lowercase, e.g. `com.example.myplugin`), `version`, `entryClass`, `publisher.name` (the author name shown on the import page), name and description (`default` required, `zh`/`en` optional).
3. `src/main/kotlin/.../TemplatePlugin.kt`: change the package and class names, and implement `skills()` / `promptSections()` / `contributions()` as needed.
4. Before switching publishers, delete the old `keys/` and run `generateSigningKey` and `signPlugin` again; `signPlugin` counts the key files as task inputs, so a new key never reuses the old signature.

## Capabilities and dependencies

- Plugins may depend only on the Android SDK, `plugin-api` and the host-provided `compileOnly` libraries (Compose BOM/UI/foundation/material3, coroutines, kotlinx-serialization-json). **Do not use `implementation` for host libraries**, or host classes end up in your package.
- Declare the system capabilities you need in `plugin.json` under `capabilities` (e.g. `linux`, `files`, `network`, `ui`); `capabilities` is required, so write an empty array when you need none.
- Version alignment: Kotlin 2.4.21, Compose BOM 2026.03.00, AGP 9.4.1, JVM 17. Update this project when the host upgrades.

## Artifact layout

A `.ddp` is a repacked APK that keeps only what the plugin actually needs:

- `classes*.dex`
- `assets/**` (including `plugin.json`, icons and skill Markdown)
- `lib/**` (if there are native libraries)

The host never reads a plugin's `res/`, so build UI with Compose or declared UI pages.

## Files

| Path | Purpose |
|---|---|
| `build.gradle.kts` | Android configuration and the `generateSigningKey` / `assemblePlugin` / `signPlugin` / `verifyPlugin` tasks (including Ed25519 and the canonical entry digest) |
| `src/main/assets/plugin.json` | Plugin manifest |
| `src/main/assets/skills/template-skill/` | Example skill: `SKILL.md` + `references/format.md` |
| `src/main/kotlin/.../TemplatePlugin.kt` | Reference implementation: `availability`, skill, prompt section, `template_echo` tool |

The full contract and signing spec are in the repository's `docs/PLUGIN_DEV.md`; listing and distribution rules are in `MARKET_POLICY.md`.

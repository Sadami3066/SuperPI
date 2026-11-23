# Manifest & `.ddp` Packaging

> Part of the [Plugin Developer Docs](README.md).

## plugin.json manifest

The manifest always lives at `assets/plugin.json` inside the package and is parsed and validated by the host. Fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | string | Yes | Lowercase reverse domain, at least two segments, regex `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$` (e.g. `com.example.myplugin`); the host uses it as the install directory and unique identity |
| `version` | string | Yes | `major.minor.patch`, optionally with a `-dev`/`+build` suffix; the host records the installed version and decides upgrades |
| `apiLevel` | int | No (default 6) | Contract level the plugin requires; must be within `MIN_SUPPORTED_API_LEVEL..PLUGIN_API_LEVEL` (currently 6..6), otherwise loading is refused. Omitted means `BASELINE_API_LEVEL` (6); level 5 plugins have been rejected since host 1.0.4 |
| `minHostVersion` | string | No | Minimum host version. The host version comes from `BuildConfig.VERSION_NAME`; comparison looks only at `major.minor.patch` and ignores suffixes. Plugins using only capabilities that existed in 1.0.x can write `"1.0.4"`; plugins using 1.1.0 capabilities (including design 0.7.0 components) write `"1.0.6"`; plugins using design 0.8.0 components write `"1.0.7"`; plugins using design 1.0.0 components or plugin-api 2.0.0 capabilities write `"1.0.8"`; plugins using tool result renderers or other 1.3.0 capabilities write `"1.1.0"`; plugins using `host.images()`/`DdRemoteImage` write `"1.4.0"` |
| `entryClass` | string | Yes | Fully qualified name of the class implementing `SuperPIPlugin`; it must have a no-arg constructor |
| `name` | LocalizedText | Yes | Plugin name, with at least `default` |
| `summary` | LocalizedText | Yes | One-line description, with at least `default` |
| `description` | LocalizedText | No | Detail text |
| `homepage` | string | No | Must be an `http(s)://` link |
| `changelog` | string | No | Release notes |
| `icon` | string | No | Only `asset:<path inside package>` is supported, e.g. `asset:icon.png`; the list downsamples to display size and does not tint; a missing or broken icon falls back to a placeholder |
| `ui` | object | No | `{ "apiLevel": 2, "kotlin"?: "2.4", "compose"?: "1.10.5" }`; part of Compose slot compatibility, see "UI extensions" |
| `publisher` | object | No | `{ "name": ..., "keyId": ... }`. `name` is the display name (e.g. `milous`), shown directly in the import confirmation and installed info; when missing the UI falls back to the first 12 characters of the signing `keyId`. `keyId` is descriptive only: publisher identity and trust always come from the `keyId` in the signature file `META-INF/plugin-signature.json` |
| `provides` | object | No | Pre-install check of contributions, see below; must match the actual contributions |
| `capabilities` | string[] | Yes | Host services and capabilities the plugin needs (38 items), see below; missing or unknown entries refuse loading |
| `permissions` | string[] | No | System permissions the plugin will request, values in "Permission declarations"; must be declared in pairs with `capabilities` |
| `components` | object | No | System component abilities proxied by the host, see "Component declarations"; every enabled item requires the matching capability |
| `defaultEnabled` | bool | No (default true) | Whether the plugin is enabled by default after the first install |

The manifest does not support fields such as `source`/`installable`/`sizeLabel`/`tintIcon`: the source is always treated as an external `.ddp`, the size is shown by the host from the package, and icons always use asset images. Unknown fields are ignored, and a wrong scalar type degrades to the default before validation reports the error.

`provides` is used for the pre-install capability check and the confirmation page:

| Key | Contents |
|---|---|
| `tools` | Tool name list, lowercase `snake_case`, e.g. `template_echo`; must not conflict with host reserved tool names |
| `skills` | Skill id list, e.g. `template-skill` |
| `uiSlots` | Registered slot ids, e.g. `tool.card.content:template_echo`, `chat.message:card`; plugin pages do not use slots, see `pages` |
| `pages` | Plugin page id list |
| `themes` | Theme id list; one-to-one with `id` in `assets/themes.json`, see "Theme packs" |

`capabilities` declares the host services and capabilities the plugin actually uses; the install confirmation and plugin details show them, and the host injects only the declared services. **An undeclared service accessor throws `PluginServiceUnavailable` at runtime** (it does not return null). 1.3.0 has 38 items in four groups; accessors marked "experimental" need `@OptIn(PluginExperimentalApi::class)`.

**Base capabilities (since 1.0)**

| wire name | Accessor | What it does | When unavailable |
|---|---|---|---|
| `linux` | `host.linux()` | Linux environment command execution and streaming output | `available=false`; throws `IllegalStateException` while the Root tier is preparing or after preparation fails |
| `screen` | `host.screen()` | Screen observation, screenshots, tap/swipe/type and virtual screens | capability fields marked unavailable, calls throw |
| `notifications` | `host.notifications()` | Post/update/cancel notifications; notification listening and actions | with `enabled=false` notifications are silently ignored; listener methods return empty/false while listening is unauthorized |
| `secrets` | `host.secrets()` | Encrypted key-value storage isolated by plugin id | throws `PluginServiceUnavailable` when the host does not provide it |
| `network` | `host.network()` | HTTP requests, downloads and `get`/`post` extensions | network errors throw; response body capped at 2 MB, downloads at 512 MB |
| `files` | `host.files()` | Plugin data directory and workspace file access | relative paths root at `dataDir`; absolute paths change with the tier |
| `sessions` | `host.sessions()` | Session list/history/prompt/stop/create/rename/delete/archive | with no active session `active=null` and `prompt` returns false |
| `jobs` | `host.jobs()` | Start, read and kill background tasks | `start` returns null when there is no session |
| `timers` | `host.timers()` | One-shot and cron reminders | invalid parameters return null |
| `goals` | `host.goals()` | Goal create, edit, pause/resume/complete/clear | invalid state returns null |
| `miniapp` | `host.miniapp()` | Mini app scan, load, create, edit, run, delete | throws `PluginServiceUnavailable` when the host does not provide it |
| `mcp` | `host.mcp()` | MCP server probing, tool list and calls | returns an error result when the server does not exist |
| `ui` | `host.ui()` | Open URL/share/pages, interactive pickers, Snackbar, theme | throws `PluginServiceUnavailable("ui.xxx")` when not wired |
| `app` | `host.app()` | Read-only app version, language, device info; app foreground/background events | always available |
| `bus` | `host.bus()` | Lightweight event bus between plugins | throws `PluginServiceUnavailable` when the host does not provide it |
| `logs` | `host.logs()` | Write to the host log | always available |
| `chat` | `host.chat()` | Deliver messages/cards/attachments to a session | unknown sessions throw; a failed delivery returns false |
| `prompt` | no accessor | Enables `PromptOverridePlugin` prompt override (experimental) | `promptOverride()` is not called when undeclared |

**Devices and system (new in 1.1.0)**

| wire name | Accessor | What it does | When unavailable |
|---|---|---|---|
| `settings` | `host.settings()` | Plugin-level key-value/JSON settings persistence and change stream | always available (host storage) |
| `device` | `host.device()` | Device info, battery/network/screen state streams, vibration, jump to system settings pages | `observe` emits a default snapshot when state is unavailable; jumps return false when they fail |
| `clipboard` | `host.clipboard()` | Read and write clipboard text and URIs | returns null or ignores writes when background access is restricted |
| `permissions` | `host.permissions()` | Query/request runtime permissions, query special grant state and jump to system settings | `request` returns whether each permission was granted; `granted=false` while a special grant is off |
| `packages` | `host.packages()` | App list/details, install/uninstall APKs, launch, open app info, read icons | returns null for a missing package; silent install needs the Root/ADB tier, otherwise it returns waiting for user confirmation |
| `privilege` | `host.privilege()` | Current tier and Root/ADB state, run commands at a given tier, silent install/uninstall, wireless debugging pairing | `exec` returns non-zero/error when the tier is insufficient, and pairing interfaces return the failure reason |
| `intents` | `host.intents()` | Start any Intent, broadcast, service, resolve and query | returns false/null when nothing matches; background Activity starts are limited by the system |
| `components` | `host.components()` (experimental) | Open the host-proxied dedicated plugin window, register dynamic broadcast receivers | not injected when components is undeclared; failed windows/receivers return false |
| `background` | `host.background()` | WorkManager/Alarm background tasks, boot callbacks, foreground services, battery optimization | `schedule` returning false means the system refused; task firing is affected by Doze |
| `location` | `host.location()` | Last location, single fix, location update stream, geocoding | returns null when `available=false` or permission is not granted |
| `contacts` | `host.contacts()` | Query, search, create, update, delete contacts | throws `SecurityException` when permission is not granted; `permissionGranted=false` |
| `sms` | `host.sms()` | SMS thread/message read, send, mark read, delete | operations return false or throw when `canRead`/`canSend` is false |
| `calendar` | `host.calendar()` | Calendar and event read/write | throws `SecurityException` when permission is not granted; `permissionGranted=false` |
| `media` | `host.media()` | Save to Downloads/gallery, scan, open, share, preview, image read, URI import | returns null/false for unreadable paths; request `manage-storage` separately for all-files access |
| `speech` | `host.speech()` | TTS read-aloud and speech recognition | returns false/error results when `speakAvailable`/`recognizeAvailable` is false |
| `web` | `host.web()` | Host web search and body fetch | returns an empty list/null when `available=false` |
| `credentials` | `host.credentials()` (experimental) | Read host provider and MCP credential information | returns an empty list/null when the host is not configured; read-only, no writes |
| `accessibility` | `host.accessibility()` (experimental) | Accessibility event stream and global actions | with `enabled=false` the stream is empty and actions return false |
| `share` | no accessor | Receive system shares (with `components.share`) | `shareReceived` is not dispatched when the component is undeclared |
| `deeplink` | no accessor | Receive deep link paths (with `components.deeplinks`) | `deepLinkReceived` is not dispatched when the path does not match or is undeclared |

`capabilities` is an **availability boundary, not a security sandbox**: plugins still share the process and UID with the host and can bypass it through `host.context` or reflection; it exists so plugins declare intent and users see which capabilities will be used before installing, and cannot stop malicious code. Declare at least one; an empty array is legal but the plugin can barely work.

`tools`/`skills`/`pages` ids all match `^[a-z][A-Za-z0-9_-]*$` and **cannot contain dots** (a skill id is `template-guide`, not `template.guide`); the slot id rules for `uiSlots` come later.

### Permission declarations (`permissions`)

`permissions` lists the system permissions a plugin will request, with values from the `PluginPermission` constants (all strings). Install-time validation checks that permissions and capabilities agree: any permission needs the matching capability, otherwise `PERMISSION_WITHOUT_CAPABILITY`; unknown or duplicate values report `INVALID_PERMISSION`. The install confirmation page shows the permission list.

**Runtime permissions** (`permissions().request(...)` shows the system grant dialog):

| wire name | Permission | Purpose | Capability needed |
|---|---|---|---|
| `location.coarse` | `ACCESS_COARSE_LOCATION` | Approximate location | `location` |
| `location.fine` | `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | Precise location | `location` |
| `contacts.read` | `READ_CONTACTS` | Read contacts | `contacts` |
| `contacts.write` | `READ_CONTACTS` + `WRITE_CONTACTS` | Modify contacts | `contacts` |
| `sms.read` | `READ_SMS` | Read SMS | `sms` |
| `sms.send` | `SEND_SMS` | Send SMS | `sms` |
| `calendar.read` | `READ_CALENDAR` | Read calendars | `calendar` |
| `calendar.write` | `READ_CALENDAR` + `WRITE_CALENDAR` | Modify calendars | `calendar` |
| `camera` | `CAMERA` | Take photos (image picker) | `ui` |
| `microphone` | `RECORD_AUDIO` | Speech recognition | `ui` |
| `notifications.post` | `POST_NOTIFICATIONS` | Post notifications | `notifications` |

**Special grants** (no runtime dialog; the user enables them in system settings; query state with `permissions().special(...)` and jump with `openSettings(...)`):

| wire name | Setting | Purpose | Capability needed |
|---|---|---|---|
| `notifications.listen` | Notification access | Read/manage notifications (notification listening) | `notifications` |
| `accessibility` | Accessibility service | Read accessibility events, perform global actions | `accessibility` |
| `overlay` | Display over other apps | Show floating content | `permissions` |
| `usage-stats` | Usage access | Read app usage statistics | `permissions` |
| `battery-optimization` | Ignore battery optimizations | Keep background tasks unrestricted by the system | `permissions` |
| `install-packages` | Install unknown apps | Install/update APKs | `packages` |
| `manage-storage` | All files access | Read and write shared storage directly | `media` |

### Component declarations (`components`)

`components` declares the system component abilities that need a static host proxy. Plugins cannot register Activity/Service/Receiver themselves; the host relays through fixed proxy components. Every enabled item must have the matching capability, otherwise `COMPONENT_WITHOUT_CAPABILITY`; an invalid structure or boolean type also reports `COMPONENT_WITHOUT_CAPABILITY`.

| Field | Type | Purpose | Capability needed |
|---|---|---|---|
| `background` | bool | WorkManager/Alarm background tasks and re-registration on boot | `background` |
| `foregroundService` | bool | Host foreground service proxy, showing ongoing progress | `background` |
| `notificationListener` | bool | Notification listening: the `notificationReceived` callback and `recent`/`received`/`removed` queries | `notifications` (plus `permissions: ["notifications.listen"]`) |
| `accessibility` | bool | Accessibility proxy: `accessibility()` events and global actions | `accessibility` (plus `permissions: ["accessibility"]`) |
| `share` | bool | Receive system shares, firing `shareReceived` | `share` |
| `deeplinks` | string[] | Deep link paths to receive, e.g. `["sync", "sync/start"]`; matches "equal path or starts with `declared/`", max 128 characters, regex `^[a-z0-9][a-z0-9/._-]*$`, duplicates or invalid values report `INVALID_DEEPLINK` | `deeplink` |

Example:

```json
"capabilities": ["background", "share", "deeplink", "notifications"],
"permissions": ["notifications.listen"],
"components": {
  "foregroundService": true,
  "share": true,
  "deeplinks": ["sync"],
  "notificationListener": true
}
```

`LocalizedText` format:

```json
"name": { "default": "Plugin Template", "zh": "插件模板", "en": "Plugin Template" }
```

- `default` is the required fallback; `zh`/`en` are language tags, chosen by the app language, falling back to `default` when missing.
- A plain string shorthand (`"name": "Plugin Template"`) is also allowed and equals `default` only.
- `host.text(source, *args)` supports `String.format` placeholders (`%s`/`%d`), passing only strings/numbers (the signature keeps `vararg Any`; new code should not pass other types).
- `LocalizedText` and `IconRef` are read-only interfaces: build text with `localizedText(default, zh?, en?)` and icons with `assetIcon("asset:<path>")`; the level 5 `Literal`/`Resource` constructors were removed in v6.
- External plugins have no app resources; `host.text()` handles resolution and formatting.

### Theme packs (`assets/themes.json`)

Plugins can contribute fixed light or dark themes; the user switches them under Settings → Theme and they apply globally. Built-in themes are PI (the default, following the system light/dark setting), Classic Blue, Santorini and Oceanic. A theme pack only reads JSON from the install directory and never runs plugin code; when the plugin is disabled or the file is missing, the selection falls back to the default PI theme. `provides.themes` in `plugin.json` declares the theme ids and `assets/themes.json` defines them; the two must match exactly.

```json
{
  "schema": 1,
  "themes": [
    {
      "id": "aurora",
      "name": { "default": "Aurora", "zh": "极光", "en": "Aurora" },
      "dark": false,
      "base": "santorini",
      "tokens": {
        "paper": "#F7F8F8",
        "accent": "#7C3AED",
        "ink": "#1E3A47"
      }
    }
  ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `schema` | int | Yes | Fixed at `1` |
| `themes` | array | Yes | 1–24 themes, ids must not repeat |
| `id` | string | Yes | Theme id, regex `^[a-z][a-z0-9_-]*$`, max 64 characters; matches `provides.themes` |
| `name` | object | Yes | `default` is required, `zh`/`en` optional; chosen by app language, falling back to `default`; each entry max 64 characters |
| `dark` | bool | Yes | Theme light/dark; decides the color marker, status bar icons and the plugin's `ui.isDark` |
| `base` | string | Yes | `pi`, `classic`, `santorini` or `oceanic`; supplies defaults for tokens not overridden |
| `tokens` | object | Yes | Color overrides; keys must be in the allowlist below and values must be `#RRGGBB` or `#AARRGGBB` |

`base` is only a starting point: `pi` is the default PI palette, `classic` (Classic Blue) and `santorini` are light, and `oceanic` is dark; `dark` may differ from the `base` light/dark, and the final light/dark comes from `dark`. Tokens you do not write come from `base`.

Available tokens:

```
paper surface raised ink inkSecondary inkTertiary accent accentPressed onAccent
accentSoft danger success warning hairline wash composer scrim
codeBackground inlineCodeBackground codeKeyword codeString codeComment
codeNumber codeFunction codeType
```

Contrast is validated on the final palette:

| Pair | Minimum contrast |
|---|---|
| `ink` / `paper` | 4.5:1 |
| `ink` / `surface` | 4.5:1 |
| `inkSecondary` / `paper` | 4.5:1 |
| `onAccent` / `accent` | 3:1 |

The theme file is read and validated at install time; a file over 64 KiB, an invalid schema or field, an unknown token, a bad color format, insufficient contrast, duplicate ids, more than 24 themes, or `provides.themes` disagreeing with the file all reject the install with `THEMES_INVALID` and give the specific reason on the confirmation page or in the toast. A theme file that passes validation is unpacked into the install directory with `assets/**`, and the host only reads that file at startup, without running the plugin.

Validation error codes (run by the host at install time, `PluginValidation`):

| Error code | Meaning |
|---|---|
| `INVALID_ID` | The id is not in lowercase reverse-domain form |
| `INVALID_VERSION` | The version is not `major.minor.patch[suffix]` |
| `INVALID_MIN_HOST_VERSION` | minHostVersion cannot be parsed |
| `HOST_VERSION_UNSUPPORTED` | The host version is below minHostVersion |
| `API_LEVEL_UNSUPPORTED` | apiLevel is not within `MIN_SUPPORTED_API_LEVEL..host level` (level 5 is rejected here since 1.0.4) |
| `UI_API_LEVEL_UNSUPPORTED` | `ui.apiLevel` does not match the host `HOST_UI_API_LEVEL` (2) (**warning**: only Compose bindings are disabled, the plugin still loads) |
| `ENTRY_CLASS_MISSING` / `INVALID_ENTRY_CLASS` | Missing entry class or invalid class name |
| `MISSING_TEXT` / `INVALID_TEXT` | name/summary lacks `default`, or uses resource text |
| `INVALID_ICON` / `INVALID_URL` | Icon path or homepage is invalid |
| `INVALID_PROVIDES` | A provides entry is invalid or duplicated |
| `MISSING_CAPABILITIES` | The manifest has no `capabilities` field |
| `INVALID_CAPABILITY` | capabilities contains an unknown service name |
| `INVALID_UI_SLOT` | A slot id in uiSlots is unknown or lacks an instance suffix |
| `INVALID_SKILL` | A skill file returned by the plugin is invalid |
| `INVALID_PERMISSION` | `permissions` contains unknown or duplicate values |
| `PERMISSION_WITHOUT_CAPABILITY` | A declared permission has no matching capability |
| `COMPONENT_WITHOUT_CAPABILITY` | The `components` structure or field types are invalid |
| `COMPONENT_WITHOUT_CAPABILITY` | An enabled component has no matching capability |
| `INVALID_DEEPLINK` | A deep link path is malformed or duplicated |

## `.ddp` package format

A `.ddp` is just a zip (the extension does not take part in detection):

```
plugin.ddp
├── classes.dex                      # required, at least one; several load in order classes.dex, classes2.dex, ...
├── classes2.dex
├── assets/
│   ├── plugin.json                  # required, and only at this path
│   ├── icon.png                     # optional, referenced by the manifest icon
│   └── skills/...                   # optional, skill markdown and attached files
├── lib/<abi>/*.so                   # optional; enters the signature digest, not loaded today
└── META-INF/plugin-signature.json   # required for install
```

Constraints:

- Only four kinds of entries: dex/assets/lib/signature; directory entries are ignored.
- Entry names must use `/`, must not be absolute, and must not contain `..`, `\`, `:`, NUL; duplicate names are rejected.
- `assets/plugin.json` can only be at that one path.
- Whole package ≤ 100 MB, unpacked total ≤ 256 MB; `classes*.dex` indices must not repeat.
- dex comes from D8/R8 (AGP's `assembleDebug` already does this), and `minSdk = 29` guarantees the dex minApi is not higher than the host.
- The host loads `classes*.dex` with an in-memory ClassLoader; at install it copies the whole package to `files/plugins/<id>/<version>/bundle/` and unpacks `assets` into the same version directory.
- Plugin runtime data always lives in `files/plugins/<id>/data` (mapped to `host.dataDir`) and survives upgrades; `host.assetsDir` is the read-only assets unpacked for the current version.
- Version directories are kept per version: after an upgrade the previous version directory serves as a rollback point until the next successful upgrade or an uninstall.

## Build, sign and update

### Packaging and signing tasks

The template project's `build.gradle.kts` ships four packaging tasks; multi-module projects (such as the official plugin repository) factor this into a shared root `gradle/plugin-bundle.gradle.kts`.

| Task | Purpose |
|---|---|
| `generateSigningKey` | Generates `keys/plugin-signing.pem` and `.pub`; fails if they already exist and never overwrites |
| `assemblePlugin` | Repackages `build/outputs/apk/debug/<module>-debug.apk` into `build/plugin/<module>.unsigned.ddp`, keeping only `classes*.dex`, `assets/**`, `lib/**`, with fixed entry timestamps for reproducibility |
| `signPlugin` | Reads the Ed25519 key, signs the unsigned package and writes `META-INF/plugin-signature.json`; prints `publisher keyId`, whole-package sha256 and entry digests |
| `verifyPlugin` | Re-verifies the signature by the host rules and prints `Signature OK` |

Keys default to `keys/plugin-signing.pem`/`.pub` in the project root, generated by `generateSigningKey` (private key permissions narrowed to `600`, ignored by `.gitignore`); `signPlugin` only reads keys and fails with a hint on how to generate them when missing. A different prefix (without extension) can also be given through a Gradle property:

```bash
./gradlew -PpluginSigningKey=/secure/keys/my-plugin signPlugin
```

- The private key **never goes into version control and is never shared**; losing it means you can no longer publish updates under the same name.
- Changing the key means changing publisher identity, and installed users cannot upgrade with the new key.
- Repository keys under `keys/` are only for local development and signing official plugins.

### Signing algorithm (ddp-1)

Installing requires `META-INF/plugin-signature.json`, algorithm Ed25519 (the host prefers the platform Provider and falls back to BouncyCastle on older platforms).

**canonical entry digest**: sort every non-directory entry in the zip by the **UTF-8 byte order of the entry name**, excluding every entry that starts with `META-INF/`; each entry is one line:

```
<sha256-hex-lowercase><SP><uncompressed-size-decimal><SP><entry-name>\n
```

sha256 is computed over the **uncompressed contents** of the entry; lines are concatenated directly and the last line ends with `\n`; the whole UTF-8 byte sequence is the message to sign. The host recomputes it with the same algorithm when verifying.

**The signature file** has exactly 4 fields (unknown fields are ignored):

```json
{
  "algorithm": "Ed25519",
  "keyId": "ed25519:8bfc1ba034dbf5bc113d469ce1f1a6350bc4c104105aa92b104eda3896fad213",
  "publicKey": "<base64(std) of X.509 SubjectPublicKeyInfo DER>",
  "signature": "<base64(std) of the 64-byte Ed25519 signature over the canonical entry digest bytes>"
}
```

**keyId must be derived from the public key**: `keyId = "ed25519:" + lowercase hex(SHA-256(SPKI DER))`. The host requires the `keyId` in the signature to match the recomputed value; that value is also the publisher fingerprint and the TOFU trust store key.

### Install, trust and update

There is exactly one install entry: pick a local `.ddp` through "Import plugin" on the Plugins page. The host first reads the package and verifies the signature (rejecting outright on failure), and the confirmation page shows the manifest and publisher information; after the user confirms, it writes the TOFU trust store, unpacks into the version directory, records the install and loads and enables the plugin immediately.

- The first install is the only trust decision point: the confirmation page shows the publisher name/`keyId`, whole-package sha256, size and the capabilities it will provide, and states "the plugin has all app permissions". After confirmation `keyId → publicKey` goes into the TOFU trust store.
- Before every load the `bundleSha256` in the install record re-checks the package, and the canonical digest is recomputed to verify the signature; a mismatch rejects the plugin and puts the error text on its row.
- **Updates must use the same key**: a new version with the same `id` must be signed with the same `keyId`, otherwise it is rejected as a publisher mismatch.
- Update flow: pick the new package → verify sha256 + signature (same key) → stage into the new version directory; activate immediately when no session is busy, and when sessions are busy the phase stays `PENDING_RESTART` and activation happens automatically once all sessions finish (it also continues after a process restart); activation runs the prepare → verify → commit → swap sequence, and the verify stage calls `onUpgrade(fromVersion)` and pre-builds contributions; a prepare/verify failure discards the staged version and deletes the broken version directory while the old version stays usable; a commit write failure keeps the staged version for a retry on the next start.
- The install record keeps `previousVersion` as a rollback point. Recovery rules: an interrupted activation → the next start skips automatic activation and reports "last activation interrupted"; two consecutive failed starts on the same version → disable automatically (`enabled=false`), and the user enabling it manually grants permission again.

### Marketplace previews

The marketplace entry's `plugin.previews` field configures detail page previews; the `plugin.json` manifest itself does not contain it:

- A list of portrait 9:20 image URLs, 1080×2400 recommended; only `https://` is accepted, the same URL is deduplicated, at most 6 images.
- The marketplace detail page shows them as 144×320dp cards with a 9:20 zero-crop fit; tapping opens full screen with left/right swiping; without `previews` there is no preview block.

### Marketplace access labels

The marketplace entry's `plugin.requiredAccess` field declares the system access tier the plugin needs to run; the `plugin.json` manifest itself does not contain it:

- Values `normal` (default) / `adb` / `root`, matching the Normal, ADB and Root tiers.
- Label according to what the plugin really needs: mark a higher tier only when it truly needs system capabilities under ADB or Root (for example a tool that only runs with root), and never inflate it.
- The client shows a colored label for the current tier (Normal green / ADB yellow / Root red); when the current tier is below the requirement, the marketplace entry and the installed plugin row warn that some features may be unavailable; a missing field or a non-enum value is treated as `normal` for compatibility with older indexes.


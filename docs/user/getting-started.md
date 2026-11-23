# Getting Started with SuperPI

Applies to the current release. Where UI wording evolves, the app screen itself is authoritative.

## Android App

### Install & First Launch

- Requires **Android 10 or newer**.
- Download `PI-X.Y.Z.apk` from [GitHub Releases](https://github.com/Sadami3066/SuperPI/releases/latest).
- The first launch walks through onboarding (welcome, optional relay account, model providers, permissions, work environment, system access). You can skip any step and finish later in **Settings**.
- SuperPI works **local-first**: no central cloud account is required, and API keys stay encrypted on your phone.

### Updates

- Automatic check runs at most once per 24 hours; manual check lives at **Settings → About → Software update**.
- Updates install in place when signed by the same release key without deleting local sessions or credentials.
- If GitHub is unreachable in your network, use **Open in browser** on the update screen to download the APK manually.

### Models & Chat

- 42 built-in providers (API key or OAuth) plus custom OpenAI-compatible endpoints (`https://`; plain `http://` only for loopback/LAN).
- Models and thinking levels are selected per session; `/compact` summarizes long conversations, `/goal` tracks multi-step goals, `/skill:<name>` forces a skill into context.
- Attachments support up to 20 files (1 GB max per file); the context ring shows token usage and cost.

### Bots & Workspaces

- Each Bot in the drawer has its own name, avatar, summary, system instructions, workspace directory, sessions, and per-bot switches for skills, plugins, connectors, screen tools, and mini-apps.
- **PI** is the default bot and cannot be deleted; new bots start with capabilities off so you choose what to grant.

### System Access & Screen Control

| Tier | Capabilities | Notes |
|---|---|---|
| **Normal** | In-app Alpine/proot Linux, files, network, calendar, plugins | `proot` user space, not a security sandbox |
| **Wireless debugging (ADB)** | Normal + UI inspection, tap/scroll/type, background virtual display | Requires Developer Options → Wireless debugging pairing |
| **Root** | Native chroot shell with root privileges | Managed via system root authorization |

- **Virtual screen** defaults to background execution without hijacking the main display, and auto-closes after 15 minutes of inactivity. Protected payment/password screens block capture and return control to you.
- Devices without Wireless Debugging UI (some HarmonyOS/EMUI builds) can run `adb tcpip 5555` from USB once, then connect via **Local ADB**.

### Linux Environment

- Initialize Alpine once under **Settings → System access → Linux environment**.
- Install `bash`, `node`, `python`, or toolchains on demand; file tools expect absolute paths.

### Extensions & Mini-Apps

- **Plugins**: signed `.ddp` bundles imported via **Plugins → Import plugin** or installed from **SuperPI-Market** (`https://sadami3066.github.io/SuperPI-Market/`). Plugins pin the publisher's Ed25519 `keyId` on first install and share the app process (no sandbox).
- **Skills**: zip packages with `SKILL.md`, typed via `/`.
- **MCP**: Streamable HTTP servers with OAuth 2.1 and deferred `tool_search`.
- **Mini-Apps**: agent-built HTML/CSS/JS tools with per-app SQLite storage and optional home-screen shortcuts.

## Desktop Client (`apps/desktop`)

SuperPI Desktop wraps the unmodified upstream [pi](https://pi.dev) CLI (`pi --mode rpc`):

- Every Bot maps directly to a local working directory (instructions read from `AGENTS.md`).
- Local sessions, models, and credentials reuse pi's native store (`~/.pi/agent/`); SuperPI state stays under `~/.superpi/`.
- When paired with a relay, Desktop appears alongside your phones in the Bot drawer, supports bidirectional remote execution, and syncs shared Memory v2 context.

## Multi-Device Sync & Relay

Pair Android phones and Desktop clients through your own [Self-Hosted Relay](self-hosting-relay.md):

- **End-to-end trust**: pairing QR/links embed the relay CA SHA-256 fingerprint (`#fp=...`), pinned on first contact.
- **Cross-device Bots**: view online devices, start or manage bots remotely, attach workspace files, and stream `present` deliverables back on demand.
- **Memory v2**: item-level incremental sync across devices (`/v2/memory/push`, `/v2/memory/pull`), canonical entity merging, and lease-protected Deep Dream consolidation (`/v2/memory/dream-lease`).

## Data, Privacy & FAQ

- **Backup before uninstalling**: `android:allowBackup="false"` is set for security. Always export important chats via **Export session** before uninstalling or switching APK signing keys.
- **Wireless ADB disconnected after reboot?** Re-enable Wireless debugging in Developer Options and tap **Connect** (no re-pairing required unless keys were cleared).
- **Command not found?** Initialize Alpine Linux first and install packages inside it.
- **Plugin update rejected?** Publisher key mismatch blocks tampered updates; verify you are updating from the original publisher.

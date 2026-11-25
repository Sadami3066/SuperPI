# Getting Started with SuperPI

Applies to SuperPI 2.0.3. Where UI wording evolves, the app screen itself is authoritative.

## Android App

### Install & First Launch

- Requires **Android 10 or newer**.
- Download `PI-2.0.3.apk` (or the latest `PI-X.Y.Z.apk`) from [GitHub Releases](https://github.com/Sadami3066/SuperPI/releases/latest).
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
- Long conversations load in windows: use **Load earlier** at the top of the history instead of opening everything at once.

### Bots & Workspaces

- Each Bot in the drawer has its own name, avatar, summary, system instructions, workspace directory, sessions, and per-bot switches for skills, plugins, connectors, screen tools, and mini-apps.
- **PI** is the default bot and cannot be deleted; new bots start with capabilities off so you choose what to grant.
- Bot settings also choose the **memory scope**: all memory, work/study only, or off.

### Super Mode

- Open the drawer and use the mode switch at the top to choose **Super Assistant**. The same conversation becomes a coordinator that can create and drive helper workers.
- **Team**: pick which bots may be used as workers. Each bot can hold up to five persistent workers; the team page shows slot usage and lets you pause a bot.
- **Workers**: the coordinator splits work into stages, starts local workers or workers on paired devices, and streams progress back into the chat. Open a worker chip to watch its steps, answer its questions, send it a message, change its model, or stop it.
- **Queued follow-ups**: while a worker is waiting for approval or still running, keep typing in its chat; messages are queued and delivered in order when it resumes or finishes.
- **Cards and files**: worker results and generated files appear as answer cards; `present` artifacts from a remote worker can be opened or saved locally.
- **Archive & reset**: archives the coordinator conversation and starts a clean one; persistent workers stay available in the team list. Removing an idle worker clears its record and temporary files; a running worker must be stopped first.

### Memory v3

Open **Memory** from the drawer or **Settings → Memory**.

- **Profile**: the living profile — long-lived facts about you, with pinned blocks, archived blocks, and suggested new blocks you can accept or dismiss.
- **Relations**: people, places, projects and topics, with aliases, one-line summaries, typed relations, related events, and duplicate merging.
- **Timeline**: recent events, commitments, goals and to-dos, grouped by upcoming, today, this week, this month and earlier. Items can be marked done, edited, pinned or deleted.
- **Deep memory index**: a searchable index of older material; the assistant uses `memory_search` and related tools when it needs details beyond the four injected budgets.
- **Memory settings**: choose automatic or fixed models for quick/deep consolidation, tune how much memory is injected into a conversation, and set per-section quotas for profile, relations, events and the deep index. **Reset to automatic** restores the default split.
- **Activity**: every automatic or manual change is listed with a revert action; **Organize now** runs consolidation immediately, and **Export all** writes a Markdown archive.
- **Per-conversation control**: use **Don't record in memory** in a chat to exclude that conversation, and optionally remove the memories already produced from it.

### System Access & Screen Control

| Tier | Capabilities | Notes |
|---|---|---|
| **Normal** | In-app Alpine/proot Linux, files, network, calendar, plugins | `proot` user space, not a security sandbox |
| **Wireless debugging (ADB)** | Normal + UI inspection, tap/scroll/type, background virtual display | Requires Developer Options → Wireless debugging pairing |
| **Root** | Native chroot shell with root privileges | Managed via system root authorization |

- **Virtual screen** defaults to background execution without hijacking the main display, and auto-closes after 15 minutes of inactivity. Protected payment/password screens block capture and return control to you.
- Devices without Wireless Debugging UI (some HarmonyOS/EMUI builds) can run `adb tcpip 5555` from USB once, then connect via **Local ADB**.
- Switching back to Normal mode or turning off wireless debugging stops the in-app ADB shell service instead of leaving it running.

### Linux Environment

- Initialize Alpine once under **Settings → System access → Linux environment**.
- Install `bash`, `node`, `python`, or toolchains on demand; file tools expect absolute paths.

### Extensions & Mini-Apps

- **Plugins**: signed `.ddp` bundles imported via **Plugins → Import plugin** or installed from **SuperPI-Market** (`https://sadami3066.github.io/SuperPI-Market/`). Plugins pin the publisher's Ed25519 `keyId` on first install and share the app process (no sandbox).
- **Skills**: zip packages with `SKILL.md`, typed via `/`.
- **MCP**: Streamable HTTP servers with OAuth 2.1 and deferred `tool_search`.
- **Mini-Apps**: agent-built HTML/CSS/JS tools with per-app SQLite storage and optional home-screen shortcuts. Leaving a mini-app releases its speech playback and recognition resources.

### Timers & Reminders

- Create one-time, daily, weekday, weekly or cron reminders under **Timers**. A reminder can target any conversation.
- One-time reminders that fail to deliver show the reason inline and offer **Retry**; repeated transient failures back off instead of vanishing.
- Daily reminders are recalculated after a reboot, app update or time-zone change; expired one-time reminders are cleaned up automatically.

## Multi-Device Sync & Relay

Pair Android phones through your own [Self-Hosted Relay](self-hosting-relay.md):

- **End-to-end trust**: pairing QR/links embed the relay CA SHA-256 fingerprint (`#fp=...`), pinned on first contact.
- **Cross-device Bots**: view online devices, start or manage bots remotely, attach workspace files, and stream `present` deliverables back on demand.
- **Remote workers**: Super Mode can place a worker on a paired phone and monitor it from the coordinator, including approvals and queued follow-ups.
- **Memory v3**: structured blocks, entities, events, relations and proposals sync incrementally (`/v3/memory/push`, `/v3/memory/pull`); a cooldown-aware lease (`/v3/memory/consolidation-lease`) prevents two phones from consolidating at once.
- The relay never runs models or tools; it stores the account, device presence and shared memory, and routes messages between your devices.

## Desktop Client (`apps/desktop`)

The Electron/React desktop shell is being rebuilt. Its source remains in the repository, but 2.0.3 does not publish a desktop build and desktop behavior is not covered by this guide.

## Data, Privacy & FAQ

- **Backup before uninstalling**: `android:allowBackup="false"` is set for security. Always export important chats via **Export session** before uninstalling or switching APK signing keys.
- **Wireless ADB disconnected after reboot?** Re-enable Wireless debugging in Developer Options and tap **Connect** (no re-pairing required unless keys were cleared).
- **Command not found?** Initialize Alpine Linux first and install packages inside it.
- **Plugin update rejected?** Publisher key mismatch blocks tampered updates; verify you are updating from the original publisher.
- **Memory looks incomplete after switching accounts?** The first relay account change asks whether to merge or replace local memory; choose merge to keep both sides.

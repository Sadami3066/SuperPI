# SuperPI Desktop

The desktop shell for SuperPI, built on the [pi](https://pi.dev) coding agent.

SuperPI on a phone is a personal agent app. This is the same assistant on a computer: it runs the unmodified pi harness as a child process over its RPC mode, gives it bots that are simply working directories, and keeps chats, tool output and sessions on the machine.

## What it does

- **Bots are directories** — a bot is a working directory; its instructions are the `AGENTS.md` in that directory. The default bot is PI.
- **Runs pi, does not reimplement it** — the harness is the official TypeScript pi, driven through `pi --mode rpc` over JSONL. Upstream fixes arrive for free.
- **Local first** — sessions, credentials and model configuration come from pi itself; SuperPI keeps only its own state under `~/.superpi`.
- **Desktop-native surface** — a window, a bot drawer, tool cards folded into the conversation, keyboard-first composer.
- **Stays out of the way** — closing the window keeps SuperPI running in the tray; a second launch focuses the window that is already open, and quitting shuts the pi processes down in order.

Devices can later talk to each other through the relay service in [SuperPI-Remote](../SuperPI-Remote): the same bots appear in the drawer, and either device can be asked to run its own bot.

## Repository layout

```
apps/desktop/src/main/       Electron main process: window, IPC, bot store, pi RPC client
apps/desktop/src/preload/    contextBridge surface
apps/desktop/src/renderer/   React interface
apps/desktop/test/           main-process tests with a fake pi child
```

## Requirements

- Node.js 22 or newer
- `pi` on `PATH` (`npm install -g @earendil-works/pi-coding-agent`)
- Linux is the first target; the shell is cross-platform but only Linux is verified

## Run

```bash
pnpm install
pnpm build
pnpm start
```

Set `SUPERPI_PI_BIN` to use a specific pi binary.

If the app aborts with a SUID sandbox error on Linux, run `bash scripts/fix-sandbox.sh` once after installing; it marks Electron's sandbox helper as root-owned setuid. `pnpm start:nosandbox` is available as a fallback.

## Development

```bash
pnpm typecheck
pnpm test
pnpm build
```

## Design

The full design lives in the author's workspace at `../rewrite/DESKTOP-REMOTE.md`: process model, RPC integration rules, bot model, relay protocol and phases.

## License

Private. Not published.

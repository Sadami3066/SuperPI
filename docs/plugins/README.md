# SuperPI Plugin Developer Docs

SuperPI external plugins are Android Kotlin modules compiled against `io.github.sadami3066.superpi:plugin-api` and `design`, repackaged into signed `.ddp` archives, and loaded in-process by the host app after Ed25519 signature verification.

## Guides

1. [Quick Start](quickstart.md) — Copy `plugins/template/`, configure `SuperPI-Maven`, build/sign `.ddp`, and troubleshoot common Gradle errors.
2. [Manifest & `.ddp` Packaging](manifest-and-packaging.md) — `plugin.json` schema, permissions, components, theme packs (`assets/themes.json`), and `ddp-1` Ed25519 signing specification.
3. [Runtime API & Host Services](runtime-api.md) — Plugin lifecycle, `AgentTool` / `PluginSkill` / hooks, host capabilities (Linux, secrets, notifications, ADB/Root, location, contacts, calendar, media), ABI baselines, and security model.
4. [UI Extensions & Design System](ui-extensions.md) — Compose slot catalog, `ToolResultRendererContributor`, plugin pages, custom message cards, and shared `:design` tokens.

## Distribution & Market Governance

- [Marketplace Policy](market-policy.md) — Allowed content, prohibited behavior, signing trust model (TOFU), and takedowns.
- [PR Review Checklist](pr-review.md) — Checklist enforced on every submission to [Sadami3066/SuperPI-Market](https://github.com/Sadami3066/SuperPI-Market).

## Repository Locations

- Starter template: [`plugins/template/`](../../plugins/template/)
- Official plugins (reference implementations): [`plugins/official/`](../../plugins/official/)
- Host E2E sample fixture: [`plugins/sample/`](../../plugins/sample/)
- Published SDK artifacts: [Sadami3066/SuperPI-Maven](https://github.com/Sadami3066/SuperPI-Maven) (`https://sadami3066.github.io/SuperPI-Maven`)

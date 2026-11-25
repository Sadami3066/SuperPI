# SuperPI Documentation

Docs are organized by role so users, plugin authors, and core contributors each have one clear entry point.

## For Users

- [Getting Started](user/getting-started.md) — Android installation, Super Mode, Memory v3, system access tiers, Linux, mini-apps, multi-device sync, and FAQ.
- [Self-Hosting Relay](user/self-hosting-relay.md) — Deploying `apps/relay` with Docker or Node.js 24, TLS certificates, pairing links, and multi-device setup.

## For Plugin Authors

- [Plugin Overview](plugins/README.md) — How `.ddp` plugins work, starter template, and official reference plugins.
- [Quick Start](plugins/quickstart.md) — Set up the template, declare dependencies from `SuperPI-Maven`, build and debug.
- [Manifest & `.ddp` Packaging](plugins/manifest-and-packaging.md) — `plugin.json` schema, capabilities, permissions, themes, and Ed25519 signing spec (`ddp-1`).
- [Runtime API & Host Services](plugins/runtime-api.md) — Lifecycle, tools, skills, hooks, host capabilities, ABI rules, and compatibility.
- [UI Extensions & Design System](plugins/ui-extensions.md) — Compose slots, tool result cards, plugin pages, and shared `:design` tokens.
- [Marketplace Policy](plugins/market-policy.md) & [PR Review Checklist](plugins/pr-review.md) — Canonical rules for publishing to `SuperPI-Market`.

## For Contributors & Maintainers

- [Architecture](contributing/architecture.md) — Monorepo layout, Android modules, Super Mode, Memory v3, Relay/Protocol, plugins, and update contract.
- [Developer Guide](contributing/development.md) — Toolchains, Gradle locking, local SDK `includeBuild`, E2E scripts, and conventions.
- [Release Engineering](contributing/release.md) — Versioning, Secrets, and tag-driven CI releases (`v*`, `desktop-v*`, `relay-v*`, `plugin-api-v*`, `plugin-*-v*`, SDK `v*`).
- [Prompt & Context Reference](contributing/context/README.md) — Internal reference map of system prompts, tools, memory, and compaction formatting.

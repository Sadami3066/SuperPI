# Contributing to SuperPI

Thank you for contributing! This monorepo contains the Android app, Desktop client, self-hosted Relay, shared Wire Protocol, Official Plugins, and Plugin Template.

## Repository Map

- `apps/android/` — Android app, agent runtime, shell bridges, `:design`, `:plugin-api`
- `apps/desktop/` — Electron + React desktop client wrapping `pi --mode rpc`
- `apps/relay/` — Node.js 24 relay and Memory v2 server
- `packages/protocol/` — Shared WebSocket & Memory v2 protocol + JSON vectors
- `plugins/official/` — Official `.ddp` plugins (`feishu`, `wecom`, `mail`, `micloud`, `amap`, `rail`, `hotel`, `ride`, `themes`, `mcpui-demo`)
- `plugins/template/` & `plugins/sample/` — Third-party starter template & E2E fixture

Companion repositories:
- [SuperPI-KotlinSDK](https://github.com/Sadami3066/SuperPI-KotlinSDK) — Multiplatform `pi-ai`, `pi-agent`, `pi-mcp`, `pi-coding-agent`
- [SuperPI-Market](https://github.com/Sadami3066/SuperPI-Market) — Plugin, skill & MCP listing PRs

## Pull Request Guidelines

1. Keep each PR focused and commit messages concise (`type(scope): summary`).
2. Add or update tests alongside behavior changes.
3. Run the checks for the area you changed before pushing:
   - TypeScript (`apps/desktop`, `apps/relay`, `packages/protocol`): `pnpm typecheck && pnpm test && pnpm build:desktop`
   - Android (`apps/android`): `./apps/android/scripts/check.sh --full` (stop daemons afterwards with `./apps/android/scripts/build.sh stop`)
   - Plugins (`plugins/`): `(cd plugins/official && ./scripts/check.sh)` or `(cd plugins/sample && ./gradlew verifyPlugin --no-daemon)`
4. Never commit API keys, `.keystore` files, `.pem` signing keys, personal sessions, or `.superpi/` caches.
5. Update documentation under `docs/` when contracts or CLI/UI workflows change.

See [Developer Guide](docs/contributing/development.md) for environment setup and [Plugin Docs](docs/plugins/README.md) for plugin development.

## License

By submitting a contribution, you agree that it is licensed under [Apache-2.0](LICENSE).

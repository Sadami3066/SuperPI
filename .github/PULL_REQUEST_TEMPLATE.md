## Summary

Describe what changed and why. Link any related issues (e.g. `Fixes #123`).

## Affected Area

- [ ] Android App (`apps/android/`)
- [ ] Desktop (`apps/desktop/`)
- [ ] Relay / Protocol (`apps/relay/`, `packages/protocol/`)
- [ ] Plugins / Template (`plugins/`)
- [ ] Docs / CI (`.github/`, `docs/`)

## Verification

- [ ] `./apps/android/scripts/check.sh --full` (if Android changed)
- [ ] `pnpm typecheck && pnpm test && pnpm build:desktop` (if Desktop/Relay/Protocol changed)
- [ ] `(cd plugins/official && ./scripts/check.sh)` or `sample` `verifyPlugin` (if plugins changed)
- [ ] Updated relevant docs / CHANGELOG / `release-notes/` if user-facing

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> [1/4] TypeScript workspace (typecheck, test)"
pnpm check:ts

echo "==> [2/4] Shared Core & Android checks"
(cd apps/android && ./scripts/check.sh --full)

echo "==> [3/4] Host CLI & Daemon checks (hostOnly)"
./scripts/gradlew-locked -Psuperpi.hostOnly=true :apps:host:check --no-daemon

echo "==> [4/4] Official & sample plugins"
(cd plugins/official && ./scripts/check.sh "$@")
(cd plugins/sample && ./gradlew verifyPlugin --no-daemon)

echo "==> All monorepo checks passed"

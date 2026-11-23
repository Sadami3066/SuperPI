#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> [1/3] TypeScript workspace (typecheck, test, build desktop)"
pnpm typecheck
pnpm test
pnpm build:desktop

echo "==> [2/3] Android app & SDK contract checks"
(cd apps/android && ./scripts/check.sh --full)

echo "==> [3/3] Official & sample plugins"
(cd plugins/official && ./scripts/check.sh "$@")
(cd plugins/sample && ./gradlew verifyPlugin --no-daemon)

echo "==> All monorepo checks passed"

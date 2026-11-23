#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
target=$(find node_modules/.pnpm -path '*/electron/dist/chrome-sandbox' -type f | head -n 1)

if [[ -z "$target" ]]; then
  echo "chrome-sandbox not found; run pnpm install first" >&2
  exit 1
fi

sudo chown root:root "$target"
sudo chmod 4755 "$target"
ls -l "$target"

#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$ANDROID_DIR/../.." && pwd)"
cd "$ROOT_DIR"
source "$ANDROID_DIR/scripts/env.sh"

if sdk="$(superpi_android_sdk)"; then
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
fi

mode="${1:-}"
full=0
device=0
case "$mode" in
  "") ;;
  --full) full=1 ;;
  --device) full=1; device=1 ;;
  *)
    echo "usage: $0 [--full|--device]" >&2
    exit 2
    ;;
esac

if [[ "$full" -eq 0 ]]; then
  ./scripts/gradlew-locked check :apps:android:app:assembleDebug
else
  ./scripts/gradlew-locked -Psuperpi.strictBoundaries=true \
    check :apps:android:app:assembleDebug :apps:android:plugin-api:assembleRelease
  "$ANDROID_DIR/scripts/api-compat.sh"
fi

if [[ "$device" -eq 1 ]]; then
  superpi_require_adb
  serial="$(superpi_device_serial)"
  adb -s "$serial" wait-for-device
  superpi_install_apk "$serial" "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$serial" shell am start -n io.github.whywood.superpi/.MainActivity
fi

#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$ANDROID_DIR/../.." && pwd)"
cd "$ROOT_DIR"

UPDATE=0
if [[ "${1:-}" == "--update" ]]; then
  UPDATE=1
fi

missing=""
command -v unzip >/dev/null 2>&1 || missing="unzip"

JAVAP=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javap" ]]; then
  JAVAP="$JAVA_HOME/bin/javap"
elif command -v javap >/dev/null 2>&1; then
  JAVAP="javap"
else
  missing="${missing:+$missing }javap"
fi

if [[ -n "$missing" ]]; then
  echo "缺少命令：$missing（Ubuntu 安装 openjdk-17-jdk-headless / macOS 安装 JDK）" >&2
  exit 1
fi

dump_api() {
  local aar="$1" out="$2" tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/superpi-api.XXXXXX")"
  unzip -q -o "$aar" classes.jar -d "$tmp"
  local jar="$tmp/classes.jar"
  if command -v jar >/dev/null 2>&1; then
    jar tf "$jar"
  else
    unzip -Z1 "$jar"
  fi | grep '\.class$' | sed 's#/#.#g; s#\.class$##' | sort -u > "$tmp/classes.txt"
  : > "$out"
  while IFS= read -r cls; do
    "$JAVAP" -public -classpath "$jar" "$cls" | sed '/^Compiled from "/d' >> "$out"
  done < "$tmp/classes.txt"
  rm -rf "$tmp"
}

filter_allowed() {
  local removed="$1" allow="$2"
  if [[ ! -f "$allow" ]]; then
    printf '%s\n' "$removed"
    return 0
  fi
  comm -23 <(printf '%s\n' "$removed" | sort) <(grep -v '^#' "$allow" | grep -v '^$' | sort) || true
}

check_module() {
  local name="$1" aar="$2" baseline="$3" allow="$4" current
  current="$(mktemp "${TMPDIR:-/tmp}/superpi-api-current.XXXXXX")"
  dump_api "$aar" "$current"

  if [[ "$UPDATE" -eq 1 ]]; then
    mkdir -p "$(dirname "$baseline")"
    cp "$current" "$baseline"
    echo "[api] updated $name baseline: $baseline ($(wc -l < "$baseline") lines)"
    rm -f "$current"
    return 0
  fi

  if [[ ! -f "$baseline" ]]; then
    echo "[api] missing $name baseline: $baseline（先执行 ./apps/android/scripts/api-compat.sh --update）" >&2
    rm -f "$current"
    return 1
  fi

  local removed filtered added
  removed="$(comm -23 <(sort "$baseline") <(sort "$current"))"
  filtered="$(filter_allowed "$removed" "$allow")"
  if [[ -n "$filtered" ]]; then
    echo "[api] $name 出现不兼容改动（删除或签名变化）：" >&2
    printf '%s\n' "$filtered" | sed 's/^/  /' >&2
    echo "[api] 破坏性改动必须提升 PLUGIN_API_LEVEL 并更新基线；确需放行时写入 $allow" >&2
    rm -f "$current"
    return 1
  fi

  added="$(comm -13 <(sort "$baseline") <(sort "$current") || true)"
  if [[ -n "$added" ]]; then
    echo "[api] $name 新增 API $(printf '%s\n' "$added" | wc -l | tr -d ' ') 行（允许）"
  fi
  echo "[api] OK: $name ($(wc -l < "$current" | tr -d ' ') lines)"
  rm -f "$current"
}

./scripts/gradlew-locked :apps:android:plugin-api:assembleRelease :apps:android:design:assembleRelease

check_module \
  "plugin-api" \
  "$ANDROID_DIR/plugin-api/build/outputs/aar/plugin-api-release.aar" \
  "$ANDROID_DIR/plugin-api/compat/level-7.api" \
  "$ANDROID_DIR/plugin-api/compat/level-7.allowlist"

check_module \
  "design" \
  "$ANDROID_DIR/design/build/outputs/aar/design-release.aar" \
  "$ANDROID_DIR/design/compat/design.api" \
  "$ANDROID_DIR/design/compat/design.allowlist"

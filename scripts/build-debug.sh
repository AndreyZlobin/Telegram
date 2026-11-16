#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Optional machine-specific paths; this file is never committed.
if [[ -f .local/env.sh ]]; then
    source .local/env.sh
fi
if [[ -z "${JAVA_HOME:-}" ]] && [[ "$(uname -s)" == Darwin ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
if [[ -z "${JAVA_HOME:-}" ]] || [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo 'Set JAVA_HOME to a JDK 17 installation.' >&2
    exit 1
fi

# Upstream deliberately skips QuickJS's test262 submodule (update=none).
if [[ -n "$(git submodule status | sed -n '/^[-+U]/p')" ]] || \
   [[ -n "$(git submodule status --recursive | sed -n '/^[+U]/p')" ]]; then
    echo 'Initialize the pinned submodules first:' >&2
    echo 'git submodule update --init --recursive --depth=1 --jobs=6' >&2
    exit 1
fi

ABI="${BUILD_ABI:-arm64-v8a}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/build/fork-debug}"
mkdir -p "$OUTPUT_DIR"

./gradlew :TMessagesProj_App:assembleAfatDebug \
    "-Pandroid.injected.build.abi=$ABI" \
    "--max-workers=${BUILD_WORKERS:-6}" --console=plain "$@" \
    2>&1 | tee "$OUTPUT_DIR/build.log"

APK="$ROOT/TMessagesProj_App/build/outputs/apk/afat/debug/app.apk"
if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK" >&2
    exit 1
fi
cp "$APK" "$OUTPUT_DIR/telegram-fork-$ABI-debug.apk"
echo "APK: $OUTPUT_DIR/telegram-fork-$ABI-debug.apk"

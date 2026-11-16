#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export OUTPUT_DIR="${OUTPUT_DIR:-$ROOT/build/fork-phone}"
"$ROOT/scripts/build-debug.sh" -PforkProduction "$@"
mv "$OUTPUT_DIR/telegram-fork-${BUILD_ABI:-arm64-v8a}-debug.apk" \
    "$OUTPUT_DIR/telegram-tglock-${BUILD_ABI:-arm64-v8a}.apk"
echo "Phone APK: $OUTPUT_DIR/telegram-tglock-${BUILD_ABI:-arm64-v8a}.apk"

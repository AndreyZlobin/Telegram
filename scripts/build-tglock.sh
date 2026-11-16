#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ ! -f "$ROOT/.local/env.sh" ]] || source "$ROOT/.local/env.sh"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" && -f "$ROOT/local.properties" ]]; then
    SDK="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties")"
fi
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/27.2.12479018}"
case "$(uname -s)" in
    Darwin) HOST_TAG=darwin-x86_64 ;;
    Linux) HOST_TAG=linux-x86_64 ;;
    *) echo 'Build the native core on macOS or Linux.' >&2; exit 1 ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
[[ -d "$TOOLCHAIN" ]] || { echo 'Android NDK 27.2.12479018 not found.' >&2; exit 1; }
export PATH="$HOME/.cargo/bin:$PATH"
export CARGO_TARGET_DIR="$ROOT/native/tglock-android/target"
IFS=',' read -r -a ABIS <<< "${1:-arm64-v8a}"
for ABI in "${ABIS[@]}"; do
    case "$ABI" in
        arm64-v8a) TARGET=aarch64-linux-android; CLANG=aarch64-linux-android ;;
        armeabi-v7a) TARGET=armv7-linux-androideabi; CLANG=armv7a-linux-androideabi ;;
        x86) TARGET=i686-linux-android; CLANG=i686-linux-android ;;
        x86_64) TARGET=x86_64-linux-android; CLANG=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
    esac
    rustup target add "$TARGET"
    TARGET_KEY="$(echo "$TARGET" | tr '[:lower:]-' '[:upper:]_')"
    env "CARGO_TARGET_${TARGET_KEY}_LINKER=$TOOLCHAIN/${CLANG}23-clang" \
        "CC_${TARGET//-/_}=$TOOLCHAIN/${CLANG}23-clang" \
        "AR_${TARGET//-/_}=$TOOLCHAIN/llvm-ar" \
        "CARGO_TARGET_${TARGET_KEY}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --manifest-path "$ROOT/native/tglock-android/Cargo.toml" \
        --release --locked --target "$TARGET"
    mkdir -p "$ROOT/native/tglock-android/build/jniLibs/$ABI"
    cp "$CARGO_TARGET_DIR/$TARGET/release/libtglock_android.so" \
        "$ROOT/native/tglock-android/build/jniLibs/$ABI/"
done

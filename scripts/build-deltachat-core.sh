#!/usr/bin/env bash
# Builds the Delta Chat core (chatmail core's deltachat-ffi) from source for Android, plus
# Twill's small JNI bridge (native/deltachat/twill_deltachat_jni.c), into
#   deltachat/jniLibs/arm64-v8a/libdeltachat.so
#   deltachat/jniLibs/arm64-v8a/libtwill_deltachat.so
# where Gradle packages them. Twill loads them in-process and talks JSON-RPC through them.
#
# Needs: git, a Rust toolchain (rustup) and the Android NDK (found through
# ANDROID_NDK_LATEST_HOME, ANDROID_NDK_HOME, ANDROID_NDK_ROOT or $ANDROID_HOME/ndk/*).
# Takes 15-30 minutes the first time; CI caches the result. Does nothing when the output for
# this exact version and script is already present. Without these files the app still
# builds, and Delta Chat Settings says Delta Chat isn't included.
#
# To upgrade: change VERSION and COMMIT (the tag's full commit hash, checked after cloning).
set -euo pipefail

VERSION="2.63.0"
COMMIT="de6f46971002c64b293d6095e66d8a1b7a11fd43"
ABI="arm64-v8a"
RUST_TARGET="aarch64-linux-android"
API_LEVEL=26

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/deltachat/jniLibs/$ABI"
STAMP="$ROOT/deltachat/core-$ABI.stamp"
SRC="$ROOT/build/deltachat-core"
BRIDGE="$ROOT/native/deltachat/twill_deltachat_jni.c"

stamp_value() {
    { echo "$VERSION $COMMIT"; cat "$0" "$BRIDGE"; } | sha256sum | cut -d' ' -f1
}

if [[ -f "$OUT/libdeltachat.so" && -f "$OUT/libtwill_deltachat.so" && -f "$STAMP" \
    && "$(cat "$STAMP")" == "$(stamp_value)" ]]; then
    echo "Delta Chat core $VERSION already built"
    exit 0
fi

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK="$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)"
fi
[[ -d "$NDK" ]] || { echo "Android NDK not found" >&2; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/${RUST_TARGET}${API_LEVEL}-clang"
echo "Using NDK $NDK"

# Source at the pinned commit.
if [[ ! -d "$SRC/.git" ]]; then
    git clone --quiet --depth 1 --branch "v$VERSION" https://github.com/chatmail/core.git "$SRC"
fi
actual="$(git -C "$SRC" rev-parse HEAD)"
if [[ "$actual" != "$COMMIT" ]]; then
    echo "v$VERSION is $actual, expected $COMMIT" >&2
    exit 1
fi

# Core library. 16 KB page alignment keeps it loadable on 16 KB page-size devices.
rustup target add "$RUST_TARGET"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384"
(cd "$SRC" && cargo build --release --locked -p deltachat_ffi --target "$RUST_TARGET")

mkdir -p "$OUT"
cp "$SRC/target/$RUST_TARGET/release/libdeltachat.so" "$OUT/libdeltachat.so"

# Twill's JNI bridge, linked against the core.
"$CLANG" -shared -fPIC -O2 -s -Wall -Werror -Wno-unused-parameter \
    -Wl,-z,max-page-size=16384 \
    -I "$SRC/deltachat-ffi" \
    -o "$OUT/libtwill_deltachat.so" "$BRIDGE" \
    -L "$OUT" -ldeltachat

stamp_value > "$STAMP"
ls -l "$OUT"

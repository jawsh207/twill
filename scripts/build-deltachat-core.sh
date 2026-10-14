#!/usr/bin/env bash
# Builds the Delta Chat core (chatmail core's deltachat-ffi) from source for Android and links
# it, with Twill's small JNI bridge (native/deltachat/twill_deltachat_jni.c), into one library:
#   deltachat/jniLibs/arm64-v8a/libtwill_deltachat.so
# where Gradle packages it. Twill loads it in-process and talks JSON-RPC through it.
#
# The core is linked statically and only the bridge's functions are exported, so the linker
# drops the parts of the core Twill never calls (most of its C API). Relocations are packed
# (RELR, Android 9+). Together that saves about 2 MB over shipping the core's own library.
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

if [[ -f "$OUT/libtwill_deltachat.so" && -f "$STAMP" \
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

# Core library, as a static library. Symbols are kept until the final link strips them.
rustup target add "$RUST_TARGET"
# OpenSSL's Android build (vendored by the core) finds the NDK through these.
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
export PATH="$TOOLCHAIN:$PATH"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
# 16 KB page alignment keeps it loadable on 16 KB page-size devices.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --print native-static-libs"
export CARGO_PROFILE_RELEASE_STRIP=false
LOG="$(mktemp)"
(cd "$SRC" && cargo build --release --locked -p deltachat_ffi --target "$RUST_TARGET") 2>&1 \
    | tee "$LOG"
# The system libraries the static library needs (libc, liblog, ...).
NATIVE_LIBS="$(grep -o 'native-static-libs: .*' "$LOG" | head -1 | cut -d: -f2-)"
rm -f "$LOG"
[[ -n "$NATIVE_LIBS" ]] || { echo "native-static-libs not reported" >&2; exit 1; }

# The bridge with the core linked in. Only Java_* functions are exported; everything they
# don't reach is dropped (--gc-sections) and relocations are packed (RELR).
mkdir -p "$OUT"
rm -f "$OUT/libdeltachat.so"
EXPORTS="$(mktemp)"
printf '{ global: Java_*; local: *; };\n' > "$EXPORTS"
# shellcheck disable=SC2086
"$CLANG" -shared -fPIC -O2 -s -Wall -Werror -Wno-unused-parameter \
    -ffunction-sections -fdata-sections \
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL \
    -Wl,--version-script="$EXPORTS" -Wl,--no-undefined \
    -Wl,--pack-dyn-relocs=relr -Wl,--use-android-relr-tags \
    -I "$SRC/deltachat-ffi" \
    -o "$OUT/libtwill_deltachat.so" "$BRIDGE" \
    "$SRC/target/$RUST_TARGET/release/libdeltachat.a" $NATIVE_LIBS
rm -f "$EXPORTS"

stamp_value > "$STAMP"
ls -l "$OUT"

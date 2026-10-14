#!/usr/bin/env bash
# Experiment: how big are the Delta Chat core's parts, and how much does linking the core
# statically into Twill's JNI bridge (exporting only the bridge, dropping unused code) save?
# Usage: core-size-experiment.sh base|cflags   (cflags: C deps built with -Os + sections)
set -euo pipefail
VARIANT="$1"
VERSION="2.63.0"
COMMIT="de6f46971002c64b293d6095e66d8a1b7a11fd43"
RUST_TARGET="aarch64-linux-android"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/build/deltachat-core"
WORK="$ROOT/build/size"
mkdir -p "$WORK"

NDK="$ANDROID_NDK_LATEST_HOME"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CLANG="$TOOLCHAIN/${RUST_TARGET}26-clang"

git clone --quiet --depth 1 --branch "v$VERSION" https://github.com/chatmail/core.git "$SRC"
[[ "$(git -C "$SRC" rev-parse HEAD)" == "$COMMIT" ]]

rustup target add "$RUST_TARGET"
export ANDROID_NDK_ROOT="$NDK" ANDROID_NDK_HOME="$NDK"
export PATH="$TOOLCHAIN:$PATH"
export CC_aarch64_linux_android="$CLANG"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --print native-static-libs"
export CARGO_PROFILE_RELEASE_STRIP=false
if [[ "$VARIANT" == cflags ]]; then
    export CFLAGS_aarch64_linux_android="-Os -ffunction-sections -fdata-sections"
fi
(cd "$SRC" && cargo build --release --locked -p deltachat_ffi --target "$RUST_TARGET") 2>&1 \
    | tee "$WORK/build.log"

OUTDIR="$SRC/target/$RUST_TARGET/release"
NATIVE_LIBS="$(grep -o 'native-static-libs: .*' "$WORK/build.log" | head -1 | cut -d: -f2-)"
echo "native-static-libs:$NATIVE_LIBS"

# Today's setup: stripped libdeltachat.so + bridge linked against it.
llvm-strip -o "$WORK/libdeltachat.so" "$OUTDIR/libdeltachat.so"
"$CLANG" -shared -fPIC -O2 -s -Wl,-z,max-page-size=16384 -I "$SRC/deltachat-ffi" \
    -o "$WORK/libtwill_deltachat.so" "$ROOT/native/deltachat/twill_deltachat_jni.c" \
    -L "$WORK" -ldeltachat

# Merged: the core's static library linked into the bridge, only Java_* exported.
printf '{ global: Java_*; local: *; };\n' > "$WORK/exports.map"
# shellcheck disable=SC2086
"$CLANG" -shared -fPIC -O2 -s -ffunction-sections -fdata-sections \
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL \
    -Wl,--version-script="$WORK/exports.map" -Wl,--no-undefined -Wl,--icf=all \
    -I "$SRC/deltachat-ffi" -o "$WORK/merged.so" \
    "$ROOT/native/deltachat/twill_deltachat_jni.c" "$OUTDIR/libdeltachat.a" $NATIVE_LIBS

gz() { gzip -9 -c "$1" | wc -c; }
{
    echo "variant $VARIANT"
    echo "today: libdeltachat.so $(stat -c%s "$WORK/libdeltachat.so") (gz $(gz "$WORK/libdeltachat.so")) + bridge $(stat -c%s "$WORK/libtwill_deltachat.so")"
    echo "merged: $(stat -c%s "$WORK/merged.so") (gz $(gz "$WORK/merged.so"))"
    echo "merged exports: $(llvm-nm -D --defined-only "$WORK/merged.so" | wc -l), needed: $(llvm-readelf -d "$WORK/merged.so" | grep NEEDED | grep -o '\[.*\]' | tr '\n' ' ')"
    echo "--- crates (unstripped symbol bytes, today's library) ---"
    python3 "$ROOT/.github/ci/core-size-breakdown.py" llvm-nm "$OUTDIR/libdeltachat.so"
} | tee "$WORK/report.txt"

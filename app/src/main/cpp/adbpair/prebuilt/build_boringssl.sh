#!/bin/bash
# Reproduce the vendored BoringSSL static libs (R100 ③).
#
# Source: https://boringssl.googlesource.com/boringssl  (refs/heads/main)
# Snapshot used for the committed .a files: fetched 2026-11-07.
# NOTE: BoringSSL's FIPS/delocate Go helpers are NOT active for an Android
# build, so `go` is not required. The SPAKE2_* C API is still exported from
# <openssl/curve25519.h> (implementation file renamed spake25519.cc).
#
# Outputs: arm64-v8a/libcrypto.a + libssl.a (stripped with llvm-strip), and
#          include/openssl/*.h (copied into this prebuilt/include dir).
set -e
NDK=${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/26.1.10909125}
NINJA=${NINJA:-/opt/android-sdk/cmake/3.22.1/bin/ninja}
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK=${WORK:-/tmp/bssl-r100c}
mkdir -p "$WORK"
cd "$WORK"
curl -sSL "https://boringssl.googlesource.com/boringssl/+archive/refs/heads/main.tar.gz" -o src.tar.gz
mkdir -p src build-arm64
tar xzf src.tar.gz -C src
cmake -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -S "$WORK/src" -B "$WORK/build-arm64"
cmake --build "$WORK/build-arm64" --target crypto ssl -j8
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug \
  "$WORK/build-arm64/libcrypto.a" "$WORK/build-arm64/libssl.a"
cp "$WORK/build-arm64/libcrypto.a" "$WORK/build-arm64/libssl.a" "$HERE/arm64-v8a/"
rm -rf "$HERE/include/openssl"
mkdir -p "$HERE/include"
cp -r "$WORK/src/include/openssl" "$HERE/include/"
echo "done: $(du -sh "$HERE" | cut -f1)"

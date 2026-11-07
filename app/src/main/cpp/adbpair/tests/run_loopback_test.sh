#!/bin/bash
# R100 ③: loopback end-to-end test of the adb_pair protocol state machine.
# Requires a PIC-enabled host BoringSSL build:
#   cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
#     -DBUILD_SHARED_LIBS=OFF -S <boringssl-src> -B <bssl-host-build> && \
#   cmake --build <bssl-host-build> --target crypto ssl -j8
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BSSL_SRC=${BSSL_SRC:-/tmp/bssl/src}
BSSL_BUILD=${BSSL_BUILD:-/tmp/bssl/build-host}
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
OUT=${OUT:-/tmp/adbpair_loopback_test}

if [ ! -f "$BSSL_BUILD/libcrypto.a" ]; then
  echo "host BoringSSL not found at $BSSL_BUILD (set BSSL_BUILD)" >&2
  exit 2
fi

# ONE translation unit: the test includes adbpair_jni.cpp to reach DoPair().
g++ -std=c++17 -O2 -Wall -Wno-unused-parameter -Wno-maybe-uninitialized \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -I"$ROOT/include" -I"$BSSL_SRC/include" \
  "$HERE/loopback_pair_test.cpp" \
  "$ROOT/pairing_auth.cpp" "$ROOT/aes_128_gcm.cpp" \
  "$BSSL_BUILD/libssl.a" "$BSSL_BUILD/libcrypto.a" \
  -lpthread -ldl -o "$OUT"

echo "compiled: $OUT"
"$OUT"

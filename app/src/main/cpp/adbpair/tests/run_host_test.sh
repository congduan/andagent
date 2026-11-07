#!/bin/bash
# R100 ③: host-compiled C++ SPAKE2 known-vector test.
#
# Requires a host (x86_64 Linux) BoringSSL build. Build it with:
#   cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
#     -S <boringssl-src> -B <bssl-host-build> && \
#   cmake --build <bssl-host-build> --target crypto ssl -j8
# Override via BSSL_SRC / BSSL_BUILD env vars.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BSSL_SRC=${BSSL_SRC:-/tmp/bssl/src}
BSSL_BUILD=${BSSL_BUILD:-/tmp/bssl/build-host}
OUT=${OUT:-/tmp/adbpair_host_test}

if [ ! -f "$BSSL_BUILD/libcrypto.a" ]; then
  echo "host BoringSSL not found at $BSSL_BUILD/libcrypto.a (set BSSL_BUILD)" >&2
  exit 2
fi

g++ -std=c++17 -O2 -Wall -Wno-unused-parameter \
  -I"$ROOT/include" -I"$BSSL_SRC/include" \
  "$ROOT/tests/spake2_vectors_test.cpp" \
  "$ROOT/pairing_auth.cpp" "$ROOT/aes_128_gcm.cpp" \
  "$BSSL_BUILD/libssl.a" "$BSSL_BUILD/libcrypto.a" \
  -lpthread -ldl -o "$OUT"

echo "compiled: $OUT"
"$OUT"

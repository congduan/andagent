cmake_minimum_required(VERSION 3.22)
project("andagent" C CXX)

# NDK 26 links with 4 KB LOAD segments by default; Android 15+ 16 KB page-size
# devices require every shared object to use 16 KB LOAD alignment.
add_link_options("-Wl,-z,max-page-size=16384")

add_library(memexec SHARED memexec.c)
target_link_libraries(memexec log)

add_library(scriptinterrupt SHARED scriptinterrupt.c)
target_link_libraries(scriptinterrupt dl)

# ---- R100 ③: adb_pair native client ----------------------------------------
# AOSP libadb_pairing_auth sources (SPAKE2 over Ed25519 + AES-128-GCM) are
# compiled verbatim; TLS 1.3 / X.509 come from a vendored BoringSSL static
# build. Only arm64-v8a has the BoringSSL prebuilt (real Android 11+ phones);
# other ABIs get a tiny stub so System.loadLibrary still succeeds.
set(ADBPAIR_DIR "${CMAKE_CURRENT_SOURCE_DIR}/adbpair")

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_library(boringssl_crypto STATIC IMPORTED)
    set_target_properties(boringssl_crypto PROPERTIES
        IMPORTED_LOCATION "${ADBPAIR_DIR}/prebuilt/arm64-v8a/libcrypto.a")

    add_library(boringssl_ssl STATIC IMPORTED)
    set_target_properties(boringssl_ssl PROPERTIES
        IMPORTED_LOCATION "${ADBPAIR_DIR}/prebuilt/arm64-v8a/libssl.a")

    add_library(adbpair SHARED
        "${ADBPAIR_DIR}/pairing_auth.cpp"
        "${ADBPAIR_DIR}/aes_128_gcm.cpp"
        "${ADBPAIR_DIR}/adbpair_jni.cpp"
        "${ADBPAIR_DIR}/tls_client.cpp")
    target_include_directories(adbpair PRIVATE
        "${ADBPAIR_DIR}/include"
        "${ADBPAIR_DIR}/prebuilt/include")
    # libssl depends on libcrypto, so ssl must come first for static link order.
    target_link_libraries(adbpair boringssl_ssl boringssl_crypto log)
    set_target_properties(adbpair PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)
else()
    add_library(adbpair SHARED "${ADBPAIR_DIR}/adbpair_stub.c")
endif()

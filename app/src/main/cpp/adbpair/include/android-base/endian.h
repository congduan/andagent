/*
 * Minimal android-base/endian.h shim. The vendored AOSP sources only use
 * htonl/ntohl (32-bit), which <arpa/inet.h> provides on both Bionic and glibc.
 *
 * AOSP original: system/libbase/include/android-base/endian.h
 */
#pragma once

#include <arpa/inet.h>

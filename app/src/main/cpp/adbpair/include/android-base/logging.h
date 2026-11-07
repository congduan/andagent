/*
 * Minimal android-base/logging.h shim for the vendored AOSP libadb_pairing_auth
 * sources. Only the CHECK / LOG surface used by pairing_auth.cpp and
 * aes_128_gcm.cpp is provided. CHECK aborts on failure, matching AOSP
 * semantics (android-base/logging.h CHECK is fatal); LOG is a no-op sink.
 *
 * AOSP original: system/libbase/include/android-base/logging.h
 */
#pragma once

#include <cstdio>
#include <cstdlib>

namespace adb_pair_shim {
struct LogSink {
    template <typename T>
    LogSink& operator<<(const T&) { return *this; }
};
}  // namespace adb_pair_shim

#define LOG(severity) ::adb_pair_shim::LogSink()
#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            fprintf(stderr, "CHECK failed: %s (%s:%d)\n", #cond, __FILE__,       \
                    __LINE__);                                                   \
            abort();                                                             \
        }                                                                        \
    } while (0)
#define CHECK_EQ(a, b) CHECK((a) == (b))
#define CHECK_NE(a, b) CHECK((a) != (b))
#define CHECK_GT(a, b) CHECK((a) > (b))
#define CHECK_GE(a, b) CHECK((a) >= (b))
#define CHECK_LT(a, b) CHECK((a) < (b))
#define CHECK_LE(a, b) CHECK((a) <= (b))
#define CHECK_NOTNULL(p) CHECK((p) != nullptr)

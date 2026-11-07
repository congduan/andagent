/*
 * R100 ③: C++ known-vector test for the vendored AOSP adb pairing crypto.
 *
 * Build/run:  ./run_host_test.sh   (host g++ + host BoringSSL)
 *
 * VECTOR SOURCES (annotated, per brief requirement):
 *  [A1] AOSP packages/modules/adb/pairing_auth/tests/pairing_auth_test.cpp
 *       - "SamePasswords"      pswd {4f,5a,01,46}, plaintext {2a,2b,2c,ff,45,12,33}
 *       - "DifferentPasswords" client {01,02,03} / server {01,02,04}
 *       - "CorruptedPayload"   tampered ciphertext must fail to decrypt
 *       - "ValidPassword"      pswd "password", both SPAKE2 msgs non-empty
 *       These are the AOSP-sanctioned vectors for this construction.
 *  [A2] BoringSSL crypto/curve25519/spake25519_test.cc - its header comment
 *       explicitly says "TODO(agl): add tests with fixed vectors once SPAKE2
 *       is nailed down". Because SPAKE2 messages use fresh randomness there
 *       are NO fixed message/expected-K vectors anywhere; the definitive
 *       vectors are the two-party roundtrip / wrong-password failure cases
 *       above, which are ported verbatim here.
 *  [A3] Deterministic golden KAT below pins the AES-128-GCM layer: given a
 *       FIXED key_material, FIXED plaintext and sequence=0, the HKDF info
 *       "adb pairing_auth aes-128-gcm key" (aes_128_gcm.cpp:42) + all-zero
 *       nonce produce a fixed ciphertext. Captured from the vendored AOSP /
 *       BoringSSL reference on host; any drift in HKDF info, nonce layout or
 *       AEAD choice fails this test.
 *
 * NOTE: this tests SPAKE2 (Ed25519), the protocol AOSP adb pairing actually
 * uses. It is NOT SPAKE2+/P-256/RFC 9380 - see the battle report.
 */
#include <arpa/inet.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

// Bionic's availability attribute must not apply to host callers either.
#ifdef __INTRODUCED_IN
#undef __INTRODUCED_IN
#endif
#define __INTRODUCED_IN(x)
#include "adb/pairing/pairing_auth.h"
#include "adb/pairing/aes_128_gcm.h"

namespace {

int g_failures = 0;

void Expect(bool cond, const char* name) {
    printf("[%s] %s\n", cond ? "PASS" : "FAIL", name);
    if (!cond) ++g_failures;
}

using Bytes = std::vector<uint8_t>;

struct CtxDeleter {
    void operator()(PairingAuthCtx* p) const { pairing_auth_destroy(p); }
};
using CtxPtr = std::unique_ptr<PairingAuthCtx, CtxDeleter>;

Bytes MsgOf(PairingAuthCtx* ctx) {
    Bytes b(pairing_auth_msg_size(ctx));
    pairing_auth_get_spake2_msg(ctx, b.data());
    return b;
}

// Exchange SPAKE2 msgs and initialize both ciphers.
bool InitBoth(PairingAuthCtx* c, PairingAuthCtx* s) {
    Bytes cm = MsgOf(c), sm = MsgOf(s);
    if (cm.empty() || sm.empty()) return false;
    if (!pairing_auth_init_cipher(c, sm.data(), sm.size())) return false;
    if (!pairing_auth_init_cipher(s, cm.data(), cm.size())) return false;
    return true;
}

bool Encrypt(PairingAuthCtx* ctx, const Bytes& in, Bytes* out) {
    out->assign(pairing_auth_safe_encrypted_size(ctx, in.size()), 0);
    size_t n = 0;
    if (!pairing_auth_encrypt(ctx, in.data(), in.size(), out->data(), &n)) return false;
    out->resize(n);
    return !out->empty();
}

bool Decrypt(PairingAuthCtx* ctx, const Bytes& in, Bytes* out) {
    out->assign(pairing_auth_safe_decrypted_size(ctx, in.data(), in.size()), 0);
    size_t n = 0;
    if (!pairing_auth_decrypt(ctx, in.data(), in.size(), out->data(), &n)) return false;
    out->resize(n);
    return true;
}

std::string Hex(const Bytes& b) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (uint8_t x : b) {
        s += d[x >> 4];
        s += d[x & 15];
    }
    return s;
}

// [A1] SamePasswords
void TestSamePasswords() {
    const Bytes pswd{0x4f, 0x5a, 0x01, 0x46};
    const Bytes msg{0x2a, 0x2b, 0x2c, 0xff, 0x45, 0x12, 0x33};
    CtxPtr c(pairing_auth_client_new(pswd.data(), pswd.size()));
    CtxPtr s(pairing_auth_server_new(pswd.data(), pswd.size()));
    Expect(c && s, "[A1] SamePasswords: contexts created");
    if (!c || !s) return;
    Expect(InitBoth(c.get(), s.get()), "[A1] SamePasswords: cipher init both sides");

    Bytes enc, dec;
    bool e = Encrypt(c.get(), msg, &enc);
    Expect(e, "[A1] SamePasswords: client encrypt");
    bool d = Decrypt(s.get(), enc, &dec);
    Expect(d && dec == msg, "[A1] SamePasswords: server decrypt == plaintext");

    // reverse direction (AOSP test does both)
    Bytes enc2, dec2;
    Expect(Encrypt(s.get(), msg, &enc2), "[A1] SamePasswords: server encrypt");
    Expect(Decrypt(c.get(), enc2, &dec2) && dec2 == msg,
           "[A1] SamePasswords: client decrypt == plaintext");

    // Fixed numeric invariant: GCM adds exactly a 16-byte tag.
    Expect(enc.size() == msg.size() + 16,
           "[A1] SamePasswords: ciphertext == plaintext + 16-byte GCM tag");
    printf("       ciphertext(%zu) = %s\n", enc.size(), Hex(enc).c_str());
}

// [A1] DifferentPasswords -- the wrong-pairing-code failure path
void TestDifferentPasswords() {
    CtxPtr c(pairing_auth_client_new(Bytes{0x01, 0x02, 0x03}.data(), 3));
    CtxPtr s(pairing_auth_server_new(Bytes{0x01, 0x02, 0x04}.data(), 3));
    Expect(c && s, "[A1] DifferentPasswords: contexts created");
    if (!c || !s) return;
    Expect(InitBoth(c.get(), s.get()), "[A1] DifferentPasswords: SPAKE2 always completes");
    const Bytes msg{0x2a, 0x2b, 0x2c};
    Bytes enc, dec;
    Expect(Encrypt(c.get(), msg, &enc), "[A1] DifferentPasswords: client encrypt");
    Expect(!Decrypt(s.get(), enc, &dec),
           "[A1] DifferentPasswords: server decrypt FAILS (wrong code)");
    Expect(Encrypt(s.get(), msg, &enc), "[A1] DifferentPasswords: server encrypt");
    Expect(!Decrypt(c.get(), enc, &dec),
           "[A1] DifferentPasswords: client decrypt FAILS (wrong code)");
}

// [A1] CorruptedPayload -- tampered ciphertext must fail GCM auth
void TestCorruptedPayload() {
    const Bytes pswd{0x4f, 0x5a, 0x01, 0x46};
    const Bytes msg{0x2a, 0x2b, 0x2c, 0xff, 0x45, 0x12,
                    0x33, 0x45, 0x12, 0xea, 0xf2, 0xdb};
    CtxPtr c(pairing_auth_client_new(pswd.data(), pswd.size()));
    CtxPtr s(pairing_auth_server_new(pswd.data(), pswd.size()));
    Expect(InitBoth(c.get(), s.get()), "[A1] CorruptedPayload: cipher init");
    Bytes enc, dec;
    Expect(Encrypt(c.get(), msg, &enc), "[A1] CorruptedPayload: encrypt");
    Expect(Decrypt(s.get(), enc, &dec) && dec == msg,
           "[A1] CorruptedPayload: untampered decrypt OK");

    Bytes tampered = enc;
    tampered.push_back(0xaa);  // append (AOSP test)
    uint32_t* p = reinterpret_cast<uint32_t*>(tampered.data());
    uint32_t v = ntohl(*p);
    *p = htonl(v + 1);  // perturb first 4 bytes (AOSP test)
    Bytes bad;
    Expect(!Decrypt(s.get(), tampered, &bad),
           "[A1] CorruptedPayload: tampered ciphertext FAILS to decrypt");

    Bytes tampered2 = enc;
    p = reinterpret_cast<uint32_t*>(tampered2.data());
    v = ntohl(*p);
    *p = htonl(v - 1);
    Expect(!Decrypt(s.get(), tampered2, &bad),
           "[A1] CorruptedPayload: decremented ciphertext FAILS to decrypt");
}

// [A1] ValidPassword
void TestValidPassword() {
    const char* k = "password";
    Bytes pswd(k, k + sizeof(k));
    CtxPtr c(pairing_auth_client_new(pswd.data(), pswd.size()));
    CtxPtr s(pairing_auth_server_new(pswd.data(), pswd.size()));
    Expect(c && s, "[A1] ValidPassword: contexts created");
    if (!c || !s) return;
    Expect(pairing_auth_msg_size(c.get()) > 0 && pairing_auth_msg_size(s.get()) > 0,
           "[A1] ValidPassword: SPAKE2 msgs non-empty");
    printf("       SPAKE2 msg size = %zu bytes (client), %zu (server)\n",
           pairing_auth_msg_size(c.get()), pairing_auth_msg_size(s.get()));
}

// [A3] Deterministic AES-128-GCM golden KAT.
// Fixed key_material + fixed plaintext + sequence 0 -> fixed ciphertext.
// Golden captured from the vendored AOSP/BoringSSL reference implementation.
// Captured 2026-11-07 from the vendored AOSP aes_128_gcm.cpp + BoringSSL
// on host (fixed 64-byte key_material, 7-byte plaintext, sequence 0).
const char* kGoldenHex = "a89856a7b7b8a57b0ece16ca7179eab254b3cc61801cce";

void TestAesGcmGolden() {
    // Fixed key material (64 bytes) so HKDF output is deterministic.
    Bytes km(64);
    for (size_t i = 0; i < km.size(); ++i) km[i] = static_cast<uint8_t>(i * 7 + 3);
    const Bytes plain{0x2a, 0x2b, 0x2c, 0xff, 0x45, 0x12, 0x33};

    adb::pairing::Aes128Gcm a(km.data(), km.size());
    Bytes out(a.EncryptedSize(plain.size()));
    auto n = a.Encrypt(plain.data(), plain.size(), out.data(), out.size());
    Expect(n.has_value(), "[A3] AES-128-GCM golden: encrypt returns size");
    if (!n) return;
    out.resize(*n);
    printf("       KAT ciphertext = %s\n", Hex(out).c_str());
    if (*kGoldenHex) {
        Expect(Hex(out) == kGoldenHex, "[A3] AES-128-GCM golden: matches fixed vector");
    } else {
        printf("       (golden not yet pinned - run once to capture, then set kGoldenHex)\n");
    }

    // Roundtrip with a fresh decrypt context (sequence 0 on both sides).
    adb::pairing::Aes128Gcm b(km.data(), km.size());
    Bytes back(b.DecryptedSize(out.size()));
    auto m = b.Decrypt(out.data(), out.size(), back.data(), back.size());
    Expect(m.has_value() && *m == plain.size() &&
               memcmp(back.data(), plain.data(), plain.size()) == 0,
           "[A3] AES-128-GCM golden: fixed-key roundtrip");
}

}  // namespace

int main() {
    printf("=== R100 ③ SPAKE2 / AOSP adb pairing vector test ===\n");
    printf("protocol: SPAKE2 (Ed25519, BoringSSL) + AES-128-GCM, NOT SPAKE2+/P-256\n\n");
    TestValidPassword();
    TestSamePasswords();
    TestDifferentPasswords();
    TestCorruptedPayload();
    TestAesGcmGolden();
    printf("\n=== %d failure(s) ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}

/*
 * R100 ③: adb_pair native client (SPAKE2 wireless pairing, client role only).
 *
 * This file is the JNI glue + synchronous protocol driver. The cryptographic
 * core (SPAKE2 + AES-128-GCM) is the AOSP source, vendored verbatim:
 *   - pairing_auth.cpp   (libadb_pairing_auth)
 *   - aes_128_gcm.cpp    (libadb_pairing_auth)
 *
 * The TLS setup, the exported-keying-material password augmentation and the
 * 3-message pairing state machine below are line-by-line ports of AOSP:
 *   - packages/modules/adb/pairing_connection/pairing_connection.cpp
 *       SetupTlsConnection() / DoExchangeMsgs() / DoExchangePeerInfo()
 *   - packages/modules/adb/tls/tls_connection.cpp
 *       TlsConnectionImpl::DoHandshake() / ExportKeyingMaterial()
 *   - packages/modules/adb/crypto/x509_generator.cpp
 *       GenerateX509Certificate() / X509ToPEMString()
 *   - packages/modules/adb/client/adb_wifi.cpp (client-side PeerInfo content)
 *
 * Deviations from AOSP (documented in the battle report):
 *   - AOSP runs Start() on a std::thread with a callback; here the same state
 *     machine runs synchronously so a single JNI call returns the result.
 *   - AOSP feeds cert/priv-key PEM strings into TlsConnection; here the
 *     one-shot self-signed cert is generated in-process and handed to
 *     SSL_CTX_set_chain_and_key as DER (same bytes on the wire).
 *   - The 3 cosmetic X509v3 extensions from x509_generator.cpp are retained.
 *     Certificate verification is unconditionally accepted on both ends
 *     (SetCertVerifyCallback -> 1), exactly as AOSP does.
 *
 * Protocol reality (see battle report): AOSP adb pairing is **SPAKE2 over
 * Ed25519** via BoringSSL's SPAKE2_* API, NOT SPAKE2+/P-256/RFC 9380. The
 * password is the 6-digit code concatenated with 64 bytes of TLS 1.3
 * exporter output (label "adb-label", incl. NUL).
 */
#include <jni.h>

#include <algorithm>
#include <arpa/inet.h>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <vector>

#include <openssl/base.h>
#include <openssl/evp.h>
#include <openssl/mem.h>
#include <openssl/rsa.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

// Bionic tags the libadb_pairing_auth entry points __INTRODUCED_IN(30). We
// compile against minSdk 26 but the symbols are vendored locally (not the
// platform's), so the availability attribute must not apply to callers.
#ifdef __INTRODUCED_IN
#undef __INTRODUCED_IN
#endif
#define __INTRODUCED_IN(x)
#include "adb/pairing/pairing_auth.h"

namespace {

// ---- Constants copied from AOSP sources -----------------------------------
// pairing_connection/include/adb/pairing/pairing_connection.h
constexpr uint32_t kMaxPeerInfoSize = 8192;
// proto/pairing.proto
constexpr uint8_t kTypeSpake2Msg = 0;  // PairingPacket::SPAKE2_MSG
constexpr uint8_t kTypePeerInfo = 1;   // PairingPacket::PEER_INFO
// pairing_connection/include/adb/pairing/pairing_connection.h (PeerInfoType)
constexpr uint8_t kPeerInfoRsaPubKey = 0;   // ADB_RSA_PUB_KEY
constexpr uint8_t kPeerInfoDeviceGuid = 1;  // ADB_DEVICE_GUID
// pairing_connection.cpp
constexpr uint8_t kCurrentKeyHeaderVersion = 1;
constexpr uint32_t kMaxPayloadSize = kMaxPeerInfoSize * 2;
constexpr size_t kExportedKeySize = 64;
// tls/tls_connection.cpp: "adb-label", sizeof == 10 (includes the NUL)
constexpr char kExportedKeyLabel[] = "adb-label";
// crypto/x509_generator.cpp
constexpr long kCertLifetimeSeconds = 10L * 365 * 24 * 60 * 60;

constexpr int kTimeoutMs = 15000;

// pairing_connection.cpp: struct PairingPacketHeader / PeerInfo (packed)
struct PairingPacketHeader {
    uint8_t version;
    uint8_t type;
    uint32_t payload;
} __attribute__((packed));

struct PeerInfo {
    uint8_t type;
    uint8_t data[kMaxPeerInfoSize - 1];
} __attribute__((packed));

static_assert(sizeof(PairingPacketHeader) == 6, "PairingPacketHeader layout");
static_assert(sizeof(PeerInfo) == kMaxPeerInfoSize, "PeerInfo layout");

struct PairResult {
    bool ok = false;
    std::string reason;  // machine-readable failure code
    std::string guid;
};

// ---- JNI conversions ------------------------------------------------------
std::string Jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (!p) return {};
    std::string out(p);
    env->ReleaseStringUTFChars(s, p);
    return out;
}

std::vector<uint8_t> Jbytes(JNIEnv* env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

// ---- One-shot self-signed X.509 cert (AOSP x509_generator.cpp) ------------
struct CertKey {
    bssl::UniquePtr<EVP_PKEY> pkey;
    bssl::UniquePtr<X509> cert;
    bssl::UniquePtr<CRYPTO_BUFFER> cert_der;
};

// x509_generator.cpp: add_ext()
bool AddExt(X509* cert, int nid, const char* value) {
    size_t len = strlen(value) + 1;
    std::vector<char> mutable_value(value, value + len);
    X509V3_CTX context;
    X509V3_set_ctx_nodb(&context);
    X509V3_set_ctx(&context, cert, cert, nullptr, nullptr, 0);
    X509_EXTENSION* ex = X509V3_EXT_nconf_nid(nullptr, &context, nid, mutable_value.data());
    if (!ex) return false;
    X509_add_ext(cert, ex, -1);
    X509_EXTENSION_free(ex);
    return true;
}

// x509_generator.cpp: GenerateX509Certificate()
bool GenerateSelfSignedCert(CertKey* out) {
    bssl::UniquePtr<EVP_PKEY_CTX> pctx(EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, nullptr));
    if (!pctx || EVP_PKEY_keygen_init(pctx.get()) != 1 ||
        EVP_PKEY_CTX_set_rsa_keygen_bits(pctx.get(), 2048) != 1) {
        return false;
    }
    EVP_PKEY* raw = nullptr;
    if (EVP_PKEY_keygen(pctx.get(), &raw) != 1) return false;
    out->pkey.reset(raw);

    bssl::UniquePtr<X509> x509(X509_new());
    if (!x509) return false;
    X509_set_version(x509.get(), 2);
    ASN1_INTEGER_set(X509_get_serialNumber(x509.get()), 1);
    X509_gmtime_adj(X509_getm_notBefore(x509.get()), 0);
    X509_gmtime_adj(X509_getm_notAfter(x509.get()), kCertLifetimeSeconds);
    if (!X509_set_pubkey(x509.get(), out->pkey.get())) return false;

    X509_NAME* name = X509_get_subject_name(x509.get());
    if (!name) return false;
    // x509_generator.cpp: C=US, O=Android, CN=Adb
    X509_NAME_add_entry_by_txt(name, "C", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("US"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "O", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Android"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Adb"), -1, -1, 0);
    if (!X509_set_issuer_name(x509.get(), name)) return false;

    AddExt(x509.get(), NID_basic_constraints, "critical,CA:TRUE");
    AddExt(x509.get(), NID_key_usage, "critical,keyCertSign,cRLSign,digitalSignature");
    AddExt(x509.get(), NID_subject_key_identifier, "hash");

    if (X509_sign(x509.get(), out->pkey.get(), EVP_sha256()) <= 0) return false;

    // i2d for SSL_CTX_set_chain_and_key (AOSP uses PEM_read_bio -> DER buffer;
    // identical DER bytes, produced directly).
    int der_len = i2d_X509(x509.get(), nullptr);
    if (der_len <= 0) return false;
    std::vector<uint8_t> der(static_cast<size_t>(der_len));
    uint8_t* p = der.data();
    if (i2d_X509(x509.get(), &p) != der_len) return false;
    out->cert_der.reset(CRYPTO_BUFFER_new(der.data(), der.size(), nullptr));
    if (!out->cert_der) return false;
    out->cert = std::move(x509);
    return true;
}

// ---- TLS 1.3 client (AOSP tls_connection.cpp: DoHandshake) ----------------
struct TlsSession {
    bssl::UniquePtr<SSL_CTX> ctx;
    bssl::UniquePtr<SSL> ssl;
    ~TlsSession() {
        if (ssl) SSL_shutdown(ssl.get());
    }
};

// tls_connection.cpp: pairing_connection.cpp:181 SetCertVerifyCallback([]{return 1;})
int TrustAllCb(X509_STORE_CTX*, void*) { return 1; }

bool SetupTls(TlsSession* s, const CertKey& ck, int fd) {
    s->ctx.reset(SSL_CTX_new(TLS_method()));
    if (!s->ctx) return false;
    // tls_connection.cpp:220-222
    if (!SSL_CTX_set_min_proto_version(s->ctx.get(), TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(s->ctx.get(), TLS1_3_VERSION)) {
        return false;
    }
    SSL_CTX_set_cert_verify_callback(s->ctx.get(), TrustAllCb, nullptr);
    // tls_connection.cpp:252-256 (set_chain_and_key with a single DER cert)
    std::vector<CRYPTO_BUFFER*> chain = {ck.cert_der.get()};
    if (!SSL_CTX_set_chain_and_key(s->ctx.get(), chain.data(), chain.size(), ck.pkey.get(),
                                   nullptr)) {
        return false;
    }
    SSL_CTX_set_verify(s->ctx.get(), SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, nullptr);

    s->ssl.reset(SSL_new(s->ctx.get()));
    if (!s->ssl || SSL_set_fd(s->ssl.get(), fd) != 1) return false;
    SSL_set_connect_state(s->ssl.get());
    return SSL_do_handshake(s->ssl.get()) == 1;
}

// tls_connection.cpp: WriteFully / ReadFully
bool WriteFully(SSL* ssl, const uint8_t* p, size_t n) {
    while (n > 0) {
        int r = SSL_write(ssl, p, static_cast<int>(std::min<size_t>(n, INT_MAX)));
        if (r <= 0) return false;
        p += r;
        n -= static_cast<size_t>(r);
    }
    return true;
}

bool ReadFully(SSL* ssl, uint8_t* p, size_t n) {
    while (n > 0) {
        int r = SSL_read(ssl, p, static_cast<int>(std::min<size_t>(n, INT_MAX)));
        if (r <= 0) return false;
        p += r;
        n -= static_cast<size_t>(r);
    }
    return true;
}

// ---- TCP connect with timeout ---------------------------------------------
int ConnectWithTimeout(const std::string& host, int port) {
    struct addrinfo hints = {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    std::string port_str = std::to_string(port);
    if (getaddrinfo(host.c_str(), port_str.c_str(), &hints, &res) != 0) return -1;

    int fd = -1;
    for (struct addrinfo* ai = res; ai; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        int rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc == 0) {
            // connected immediately
        } else if (errno == EINPROGRESS) {
            struct pollfd pfd = {fd, POLLOUT, 0};
            int pr = poll(&pfd, 1, kTimeoutMs);
            if (pr <= 0) {
                close(fd);
                fd = -1;
                continue;
            }
            int soerr = 0;
            socklen_t sl = sizeof(soerr);
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &sl);
            if (soerr != 0) {
                close(fd);
                fd = -1;
                continue;
            }
        } else {
            close(fd);
            fd = -1;
            continue;
        }
        fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
        struct timeval tv = {kTimeoutMs / 1000, (kTimeoutMs % 1000) * 1000};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        break;
    }
    freeaddrinfo(res);
    return fd;
}

// ---- PairingPacket framing (pairing_connection.cpp: WriteHeader/ReadHeader)
bool WritePacket(SSL* ssl, uint8_t type, const uint8_t* data, size_t len) {
    PairingPacketHeader h = {};
    h.version = kCurrentKeyHeaderVersion;
    h.type = type;
    h.payload = htonl(static_cast<uint32_t>(len));
    if (!WriteFully(ssl, reinterpret_cast<const uint8_t*>(&h), sizeof(h))) return false;
    return len == 0 || WriteFully(ssl, data, len);
}

bool ReadPacket(SSL* ssl, PairingPacketHeader* h, std::vector<uint8_t>* payload) {
    if (!ReadFully(ssl, reinterpret_cast<uint8_t*>(h), sizeof(*h))) return false;
    if (h->version != kCurrentKeyHeaderVersion) return false;
    uint32_t n = ntohl(h->payload);
    if (n == 0 || n > kMaxPayloadSize) return false;
    payload->resize(n);
    return ReadFully(ssl, payload->data(), n);
}

// ---- Synchronous pairing state machine ------------------------------------
// Port of pairing_connection.cpp StartWorker/SetupTlsConnection/DoExchangeMsgs/
// DoExchangePeerInfo, flattened into one blocking call for JNI.
PairResult DoPair(const std::string& host, int port, const std::string& code,
                  const std::vector<uint8_t>& adb_pubkey, const std::string& device_name) {
    PairResult r;
    (void)device_name;  // AOSP protocol has no client-name field; used by Kotlin side only.

    int fd = ConnectWithTimeout(host, port);
    if (fd < 0) {
        r.reason = "NETWORK";
        return r;
    }

    CertKey ck;
    if (!GenerateSelfSignedCert(&ck)) {
        close(fd);
        r.reason = "CERT";
        return r;
    }

    TlsSession tls;
    if (!SetupTls(&tls, ck, fd)) {
        close(fd);
        r.reason = "TLS";
        return r;
    }

    // pairing_connection.cpp:190-198 - append TLS exporter material to the
    // password so the PAKE is bound to this exact TLS session.
    std::vector<uint8_t> ekm(kExportedKeySize);
    if (SSL_export_keying_material(tls.ssl.get(), ekm.data(), ekm.size(), kExportedKeyLabel,
                                   sizeof(kExportedKeyLabel), nullptr, 0, 0) != 1) {
        close(fd);
        r.reason = "TLS_EXPORT";
        return r;
    }
    std::vector<uint8_t> pswd(code.begin(), code.end());
    pswd.insert(pswd.end(), ekm.begin(), ekm.end());
    if (pswd.empty()) {
        close(fd);
        r.reason = "CODE";
        return r;
    }

    PairingAuthCtx* auth = pairing_auth_client_new(pswd.data(), pswd.size());
    if (!auth) {
        close(fd);
        r.reason = "SPAKE2";
        return r;
    }
    struct AuthGuard {
        PairingAuthCtx* p;
        ~AuthGuard() { pairing_auth_destroy(p); }
    } guard{auth};

    // pairing_connection.cpp:295-330 DoExchangeMsgs
    size_t msg_len = pairing_auth_msg_size(auth);
    if (msg_len == 0) {
        r.reason = "SPAKE2";
        return r;
    }
    std::vector<uint8_t> msg(msg_len);
    pairing_auth_get_spake2_msg(auth, msg.data());
    if (!WritePacket(tls.ssl.get(), kTypeSpake2Msg, msg.data(), msg.size())) {
        r.reason = "IO";
        return r;
    }

    PairingPacketHeader hdr = {};
    std::vector<uint8_t> their_msg;
    if (!ReadPacket(tls.ssl.get(), &hdr, &their_msg) || hdr.type != kTypeSpake2Msg) {
        r.reason = "PROTOCOL";
        return r;
    }
    // pairing_auth_init_cipher CHECK()s on empty input, so validate first.
    if (their_msg.empty() || !pairing_auth_init_cipher(auth, their_msg.data(), their_msg.size())) {
        r.reason = "PROTOCOL";
        return r;
    }

    // pairing_connection.cpp:332-405 DoExchangePeerInfo
    // adb_wifi.cpp:210-214 - client PeerInfo carries our ADB RSA public key.
    PeerInfo pi = {};  // 8192 zero bytes
    pi.type = kPeerInfoRsaPubKey;
    size_t copy = std::min<size_t>(adb_pubkey.size(), sizeof(pi.data));
    if (copy > 0) memcpy(pi.data, adb_pubkey.data(), copy);

    std::vector<uint8_t> enc(pairing_auth_safe_encrypted_size(auth, sizeof(pi)));
    size_t enc_len = 0;
    if (!pairing_auth_encrypt(auth, reinterpret_cast<uint8_t*>(&pi), sizeof(pi), enc.data(),
                              &enc_len)) {
        r.reason = "ENCRYPT";
        return r;
    }
    enc.resize(enc_len);
    if (!WritePacket(tls.ssl.get(), kTypePeerInfo, enc.data(), enc.size())) {
        r.reason = "IO";
        return r;
    }

    std::vector<uint8_t> their_enc;
    if (!ReadPacket(tls.ssl.get(), &hdr, &their_enc) || hdr.type != kTypePeerInfo) {
        r.reason = "PROTOCOL";
        return r;
    }
    std::vector<uint8_t> dec(pairing_auth_safe_decrypted_size(auth, their_enc.data(),
                                                              their_enc.size()));
    size_t dec_len = 0;
    // A GCM tag mismatch here is exactly the "wrong pairing code" case
    // (AOSP adb_wifi.cpp:240 "Failed: Wrong password or connection was dropped").
    if (dec.empty() || !pairing_auth_decrypt(auth, their_enc.data(), their_enc.size(), dec.data(),
                                             &dec_len)) {
        r.reason = "WRONG_CODE";
        return r;
    }
    if (dec_len != sizeof(PeerInfo)) {
        r.reason = "PROTOCOL";
        return r;
    }
    PeerInfo their = {};
    memcpy(&their, dec.data(), sizeof(their));
    if (their.type != kPeerInfoDeviceGuid) {
        r.reason = "PROTOCOL";
        return r;
    }
    r.ok = true;
    r.guid = std::string(reinterpret_cast<const char*>(their.data),
                         strnlen(reinterpret_cast<const char*>(their.data), sizeof(their.data)));
    return r;
}

// ---- In-process SPAKE2 self test (JNI boundary smoke / known behaviour) ----
// Behavioural vectors from AOSP pairing_auth_test.cpp:
//   SamePasswords    pswd {0x4f,0x5a,0x01,0x46}
//   DifferentPasswords  client {01,02,03} / server {01,02,04}
// BoringSSL's own SPAKE2 test (crypto/curve25519/spake25519_test.cc) has NO
// fixed known-answer vectors ("TODO: add tests with fixed vectors once SPAKE2
// is nailed down") - the authoritative vectors are these roundtrip/failure
// cases. See the battle report for the source annotations.
bool Spake2SelfTest() {
    const std::vector<uint8_t> pswd{0x4f, 0x5a, 0x01, 0x46};
    const std::vector<uint8_t> msg{0x2a, 0x2b, 0x2c, 0xff, 0x45, 0x12, 0x33};

    PairingAuthCtx* c = pairing_auth_client_new(pswd.data(), pswd.size());
    PairingAuthCtx* s = pairing_auth_server_new(pswd.data(), pswd.size());
    if (!c || !s) return false;
    struct G {
        PairingAuthCtx* a;
        PairingAuthCtx* b;
        ~G() {
            pairing_auth_destroy(a);
            pairing_auth_destroy(b);
        }
    } g{c, s};

    std::vector<uint8_t> cm(pairing_auth_msg_size(c)), sm(pairing_auth_msg_size(s));
    if (cm.empty() || sm.empty()) return false;
    pairing_auth_get_spake2_msg(c, cm.data());
    pairing_auth_get_spake2_msg(s, sm.data());
    if (!pairing_auth_init_cipher(c, sm.data(), sm.size()) ||
        !pairing_auth_init_cipher(s, cm.data(), cm.size())) {
        return false;
    }

    std::vector<uint8_t> enc(pairing_auth_safe_encrypted_size(c, msg.size()));
    size_t enc_len = 0;
    if (enc.empty() || !pairing_auth_encrypt(c, msg.data(), msg.size(), enc.data(), &enc_len)) {
        return false;
    }
    enc.resize(enc_len);
    std::vector<uint8_t> dec(pairing_auth_safe_decrypted_size(s, enc.data(), enc.size()));
    size_t dec_len = 0;
    if (dec.empty() || !pairing_auth_decrypt(s, enc.data(), enc.size(), dec.data(), &dec_len)) {
        return false;
    }
    dec.resize(dec_len);
    if (dec != msg) return false;

    // Wrong-password path: same SPAKE2 flow, different passwords -> decrypt
    // must fail (this is the "confirm 失败路径" for adb pairing).
    const std::vector<uint8_t> pswd2{0x4f, 0x5a, 0x01, 0x47};
    PairingAuthCtx* c2 = pairing_auth_client_new(pswd.data(), pswd.size());
    PairingAuthCtx* s2 = pairing_auth_server_new(pswd2.data(), pswd2.size());
    if (!c2 || !s2) return false;
    struct G2 {
        PairingAuthCtx* a;
        PairingAuthCtx* b;
        ~G2() {
            pairing_auth_destroy(a);
            pairing_auth_destroy(b);
        }
    } g2{c2, s2};
    std::vector<uint8_t> cm2(pairing_auth_msg_size(c2)), sm2(pairing_auth_msg_size(s2));
    pairing_auth_get_spake2_msg(c2, cm2.data());
    pairing_auth_get_spake2_msg(s2, sm2.data());
    if (!pairing_auth_init_cipher(c2, sm2.data(), sm2.size()) ||
        !pairing_auth_init_cipher(s2, cm2.data(), cm2.size())) {
        return false;
    }
    std::vector<uint8_t> enc2(pairing_auth_safe_encrypted_size(c2, msg.size()));
    size_t enc2_len = 0;
    if (!pairing_auth_encrypt(c2, msg.data(), msg.size(), enc2.data(), &enc2_len)) return false;
    enc2.resize(enc2_len);
    std::vector<uint8_t> dec2(pairing_auth_safe_decrypted_size(s2, enc2.data(), enc2.size()));
    size_t dec2_len = 0;
    if (dec2.empty() ||
        pairing_auth_decrypt(s2, enc2.data(), enc2.size(), dec2.data(), &dec2_len)) {
        return false;  // decryption unexpectedly succeeded with different codes
    }
    return true;
}

}  // namespace

// ---- JNI exports ----------------------------------------------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativePair(JNIEnv* env, jobject /*thiz*/, jstring host,
                                                   jint port, jstring code, jbyteArray adb_pubkey,
                                                   jstring device_name) {
    std::string h = Jstr(env, host);
    std::string c = Jstr(env, code);
    std::string d = Jstr(env, device_name);
    std::vector<uint8_t> pk = Jbytes(env, adb_pubkey);

    // Validate at the boundary; the AOSP sources use CHECK() (abort) internally,
    // so never feed them empty/garbage inputs.
    if (h.empty() || port <= 0 || port > 65535 || c.empty()) {
        return env->NewStringUTF("ERROR\nREASON=INVALID_ARGS\n");
    }

    PairResult r = DoPair(h, port, c, pk, d);
    std::string out;
    if (r.ok) {
        out = "OK\nGUID=" + r.guid + "\n";
    } else {
        out = "ERROR\nREASON=" + r.reason + "\n";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeSelfTest(JNIEnv* env, jobject /*thiz*/) {
    bool pass = Spake2SelfTest();
    return env->NewStringUTF(pass ? "PASS" : "FAIL");
}

// ---- Non-arm64 stub (no BoringSSL prebuilt for those ABIs) ----------------
// NOTE: this function lives in adbpair_stub.c for other ABIs. Keeping the
// symbol names identical lets one Kotlin native declaration cover all ABIs.

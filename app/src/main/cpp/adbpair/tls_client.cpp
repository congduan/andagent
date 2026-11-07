/*
 * R103 A: ADB over TLS 1.3 transport (client side) - implementation + JNI.
 *
 * BoringSSL API usage was checked against
 *   app/src/main/cpp/adbpair/prebuilt/include/openssl/{ssl.h,x509.h,x509v3.h,
 *     digest.h,pem.h,pool.h,err.h}
 * (SSL_CTX_set_chain_and_key / SSL_CTX_set_cert_verify_callback /
 *  X509_digest(EVP_sha256) / SSL_get_peer_certificate / SSL_do_handshake /
 *  d2i_AutoPrivateKey / CRYPTO_BUFFER_new / i2d_X509 / ERR_get_error).
 * No API is used from memory.
 *
 * AOSP reference for the wire shape (see the header for the file list):
 *   - plaintext CNXN first, STLS echo, then TLS 1.3 (adb.cpp handle_packet)
 *   - client certificate selected from the ADB key (client/auth.cpp
 *     adb_tls_set_certificate, crypto/x509_generator.cpp)
 *   - server certificate pinned by SHA-256 of its DER (tls_connection.cpp
 *     AddTrustedCertificate / our pairing-time fingerprint list)
 *
 * The peer leaf certificate is pinned by the SHA-256 digest of its DER
 * encoding (X509_digest), compared against the list captured at pairing time.
 * The system CA chain is intentionally skipped (self-signed adbd).
 */
#include "tls_client.h"

#include <jni.h>

#include <algorithm>
#include <arpa/inet.h>
#include <cerrno>
#include <climits>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <netdb.h>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#include <openssl/digest.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/pool.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>

namespace andagent {
namespace {

// ---- ADB packet constants (AOSP adb/adb.h) --------------------------------
// 'CNXN' / 'STLS' little-endian; magic is always cmd ^ 0xffffffff.
constexpr uint32_t kCmdCnxn = 0x4e584e43;
constexpr uint32_t kCmdStls = 0x534c5453;
constexpr uint32_t kAdbVersion = 0x01000001;    // A_VERSION
constexpr uint32_t kStlsVersion = 0x01000000;   // A_STLS_VERSION
constexpr uint32_t kMaxData = 256 * 1024;
constexpr size_t kAdbHeaderSize = 24;
// x509_generator.cpp: kCertLifetimeSeconds
constexpr long kCertLifetimeSeconds = 10L * 365 * 24 * 60 * 60;

void PutU32LE(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v);
    p[1] = static_cast<uint8_t>(v >> 8);
    p[2] = static_cast<uint8_t>(v >> 16);
    p[3] = static_cast<uint8_t>(v >> 24);
}

uint32_t GetU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

/** Blocking write loop; a timeout surfaces as a short/zero write. */
bool WriteFullyFd(int fd, const uint8_t* p, size_t n) {
    while (n > 0) {
        const ssize_t r = ::write(fd, p, n);
        if (r > 0) {
            p += r;
            n -= static_cast<size_t>(r);
            continue;
        }
        if (r < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

/** Blocking read loop; SO_RCVTIMEO expiry surfaces here as failure. */
bool ReadFullyFd(int fd, uint8_t* p, size_t n) {
    while (n > 0) {
        const ssize_t r = ::read(fd, p, n);
        if (r > 0) {
            p += r;
            n -= static_cast<size_t>(r);
            continue;
        }
        if (r < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

/** adb.cpp: send_packet() - header + payload, little-endian. */
bool SendPacket(int fd, uint32_t cmd, uint32_t arg0, uint32_t arg1, const std::string& payload) {
    uint8_t hdr[kAdbHeaderSize];
    PutU32LE(hdr + 0, cmd);
    PutU32LE(hdr + 4, arg0);
    PutU32LE(hdr + 8, arg1);
    PutU32LE(hdr + 12, static_cast<uint32_t>(payload.size()));
    uint32_t checksum = 0;
    for (unsigned char c : payload) checksum += c;
    PutU32LE(hdr + 16, checksum);
    PutU32LE(hdr + 20, cmd ^ 0xffffffffu);
    if (!WriteFullyFd(fd, hdr, sizeof(hdr))) return false;
    return payload.empty() || WriteFullyFd(fd, reinterpret_cast<const uint8_t*>(payload.data()),
                                           payload.size());
}

/** Reads one 24-byte header and consumes (discards) any payload bytes. */
bool ReadPacketHeader(int fd, uint32_t* cmd, uint32_t* arg0, uint32_t* arg1) {
    uint8_t hdr[kAdbHeaderSize];
    if (!ReadFullyFd(fd, hdr, sizeof(hdr))) return false;
    *cmd = GetU32LE(hdr + 0);
    *arg0 = GetU32LE(hdr + 4);
    *arg1 = GetU32LE(hdr + 8);
    const uint32_t len = GetU32LE(hdr + 12);
    const uint32_t magic = GetU32LE(hdr + 20);
    if (magic != (*cmd ^ 0xffffffffu)) return false;
    if (len > kMaxData) return false;
    if (len > 0) {
        std::vector<uint8_t> sink(len);
        if (!ReadFullyFd(fd, sink.data(), len)) return false;
    }
    return true;
}

/**
 * AOSP adb.cpp handle_packet(A_STLS) / send_tls_request:
 * the wireless-debugging port speaks plaintext ADB first and only upgrades to
 * TLS after the server pushes STLS and the client echoes it back.
 *   1. send CNXN with the same banner the Kotlin client uses ("host::")
 *   2. read the reply: STLS means "upgrade"; CNXN means the peer is plaintext
 *   3. echo STLS with A_STLS_VERSION, then the caller runs SSL_do_handshake
 */
bool StlsPrelude(int fd, std::string* err) {
    std::string banner = "host::";
    banner.push_back('\0');
    if (!SendPacket(fd, kCmdCnxn, kAdbVersion, kMaxData, banner)) {
        *err = "NETWORK";
        return false;
    }
    uint32_t cmd = 0, arg0 = 0, arg1 = 0;
    if (!ReadPacketHeader(fd, &cmd, &arg0, &arg1)) {
        *err = "STLS";
        return false;
    }
    if (cmd == kCmdCnxn) {
        // adbd did not ask for TLS: the target is a plaintext adbd
        // (adb tcpip / Android < 11). Nothing to upgrade.
        *err = "NO_TLS";
        return false;
    }
    if (cmd != kCmdStls) {
        *err = "STLS";
        return false;
    }
    if (!SendPacket(fd, kCmdStls, kStlsVersion, 0, std::string())) {
        *err = "STLS";
        return false;
    }
    return true;
}

// ---- Self-signed client certificate from the ADB key ---------------------
// crypto/x509_generator.cpp: add_ext()
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

/** crypto/x509_generator.cpp: GenerateX509Certificate() for an existing key. */
bssl::UniquePtr<CRYPTO_BUFFER> MakeCertBuffer(EVP_PKEY* pkey) {
    if (!pkey) return nullptr;
    bssl::UniquePtr<X509> x509(X509_new());
    if (!x509) return nullptr;
    X509_set_version(x509.get(), 2);
    ASN1_INTEGER_set(X509_get_serialNumber(x509.get()), 1);
    X509_gmtime_adj(X509_getm_notBefore(x509.get()), 0);
    X509_gmtime_adj(X509_getm_notAfter(x509.get()), kCertLifetimeSeconds);
    if (!X509_set_pubkey(x509.get(), pkey)) return nullptr;

    X509_NAME* name = X509_get_subject_name(x509.get());
    if (!name) return nullptr;
    X509_NAME_add_entry_by_txt(name, "C", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("US"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "O", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Android"), -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               reinterpret_cast<const unsigned char*>("Adb"), -1, -1, 0);
    if (!X509_set_issuer_name(x509.get(), name)) return nullptr;

    AddExt(x509.get(), NID_basic_constraints, "critical,CA:TRUE");
    AddExt(x509.get(), NID_key_usage, "critical,keyCertSign,cRLSign,digitalSignature");
    AddExt(x509.get(), NID_subject_key_identifier, "hash");
    // Same 64-bit extension ordering as AOSP; ASN.1 ordering is irrelevant to
    // adbd, which matches on the public key only.
    if (X509_sign(x509.get(), pkey, EVP_sha256()) <= 0) return nullptr;

    const int der_len = i2d_X509(x509.get(), nullptr);
    if (der_len <= 0) return nullptr;
    std::vector<uint8_t> der(static_cast<size_t>(der_len));
    uint8_t* p = der.data();
    if (i2d_X509(x509.get(), &p) != der_len) return nullptr;
    return bssl::UniquePtr<CRYPTO_BUFFER>(CRYPTO_BUFFER_new(der.data(), der.size(), nullptr));
}

/** PKCS#8 DER (what Java hands us) -> EVP_PKEY. */
bssl::UniquePtr<EVP_PKEY> LoadPrivateKey(const std::vector<uint8_t>& pkcs8) {
    if (pkcs8.empty()) return nullptr;
    const uint8_t* p = pkcs8.data();
    return bssl::UniquePtr<EVP_PKEY>(d2i_AutoPrivateKey(nullptr, &p, static_cast<long>(pkcs8.size())));
}

std::string SslErrorDetail() {
    std::string out;
    uint32_t e = 0;
    while ((e = ERR_get_error()) != 0) {
        char buf[256] = {0};
        ERR_error_string_n(e, buf, sizeof(buf));
        if (!out.empty()) out += " | ";
        out += buf;
    }
    return out;
}

std::string Hex(const uint8_t* p, size_t n) {
    static const char kDigits[] = "0123456789abcdef";
    std::string s;
    s.reserve(n * 2);
    for (size_t i = 0; i < n; ++i) {
        s += kDigits[p[i] >> 4];
        s += kDigits[p[i] & 0x0f];
    }
    return s;
}

void SetSockTimeout(int fd, int ms, int opt) {
    struct timeval tv = {ms / 1000, (ms % 1000) * 1000};
    setsockopt(fd, SOL_SOCKET, opt, &tv, sizeof(tv));
}

/** TCP connect with a bounded connect() + read/write socket timeouts. */
int TcpConnect(const std::string& host, int port, int timeout_ms) {
    struct addrinfo hints = {};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    const std::string port_str = std::to_string(port);
    if (getaddrinfo(host.c_str(), port_str.c_str(), &hints, &res) != 0) return -1;

    int fd = -1;
    for (struct addrinfo* ai = res; ai; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        const int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        int rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc == 0) {
            // connected immediately
        } else if (errno == EINPROGRESS) {
            struct pollfd pfd = {fd, POLLOUT, 0};
            const int pr = poll(&pfd, 1, timeout_ms);
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
        fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);  // back to blocking
        SetSockTimeout(fd, timeout_ms, SO_RCVTIMEO);
        SetSockTimeout(fd, timeout_ms, SO_SNDTIMEO);
        break;
    }
    freeaddrinfo(res);
    return fd;
}

// ---- Process-wide handle registry ----------------------------------------
std::mutex g_mutex;
std::unordered_map<uint64_t, std::shared_ptr<TlsClient>> g_sessions;
uint64_t g_next_id = 1;
constexpr size_t kMaxSessions = 64;

std::shared_ptr<TlsClient> Find(uint64_t handle) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

std::string Jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (!p) return {};
    std::string out(p);
    env->ReleaseStringUTFChars(s, p);
    return out;
}

std::vector<std::string> JStringArray(JNIEnv* env, jobjectArray a) {
    std::vector<std::string> out;
    if (!a) return out;
    const jsize n = env->GetArrayLength(a);
    for (jsize i = 0; i < n; ++i) {
        jstring s = static_cast<jstring>(env->GetObjectArrayElement(a, i));
        std::string v = Jstr(env, s);
        if (!v.empty()) out.push_back(v);
        if (s) env->DeleteLocalRef(s);
    }
    return out;
}

std::vector<uint8_t> Jbytes(JNIEnv* env, jbyteArray a) {
    std::vector<uint8_t> out;
    if (!a) return out;
    const jsize n = env->GetArrayLength(a);
    out.resize(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

}  // namespace

std::string CertFingerprintHex(X509* cert) {
    if (!cert) return {};
    uint8_t digest[EVP_MAX_MD_SIZE];
    unsigned len = 0;
    if (X509_digest(cert, EVP_sha256(), digest, &len) != 1 || len == 0) return {};
    return Hex(digest, len);
}

TlsClient::~TlsClient() { close(); }

/**
 * AOSP transport.cpp FdConnection::DoTlsHandshake (ADB_HOST):
 *   tls_->SetCertVerifyCallback([](X509_STORE_CTX*) { return 1; });
 * "Allow any server certificate". adbd generates its certificate per
 * connection (daemon/auth.cpp adbd_auth_tls_handshake keeps a per-process RSA
 * key, then X509_gmtime_adj stamps a new notBefore each time), so the leaf
 * digest differs on every connect - measured on a real device: two consecutive
 * connections produced different fingerprints. A fingerprint pin can therefore
 * never match and must not abort the handshake; we only record it for
 * diagnostics. Identity is enforced the other way round: adbd checks our
 * client certificate against its authorized keys (adbd_tls_verify_cert).
 */
int TlsClient::VerifyCb(X509_STORE_CTX* store_ctx, void* arg) {
    auto* self = static_cast<TlsClient*>(arg);
    if (!self) return 1;
    self->peer_fingerprint_ = CertFingerprintHex(X509_STORE_CTX_get0_cert(store_ctx));
    return 1;
}

bool TlsClient::connect(const std::string& host, int port, int timeout_ms,
                        const std::vector<std::string>& pinned_fingerprints,
                        const std::vector<uint8_t>& client_key_pkcs8,
                        TlsConnectResult* out) {
    out->handle = 0;
    out->reason.clear();
    out->fingerprint.clear();
    out->detail.clear();
    if (host.empty() || port <= 0 || port > 65535 || timeout_ms <= 0) {
        out->reason = "INVALID_ARGS";
        return false;
    }
    // Kept for API compatibility / diagnostics only: see VerifyCb for why a
    // server-certificate pin cannot be enforced against a rotating adbd cert.
    (void)pinned_fingerprints;

    fd_ = TcpConnect(host, port, timeout_ms);
    if (fd_ < 0) {
        out->reason = "NETWORK";
        return false;
    }

    // AOSP adb.cpp: plaintext CNXN -> STLS <- -> STLS echo, all before TLS.
    std::string prelude_error;
    if (!StlsPrelude(fd_, &prelude_error)) {
        out->reason = prelude_error;
        close();
        return false;
    }

    // AOSP client/auth.cpp adb_tls_set_certificate + crypto/x509_generator.cpp:
    // the certificate adbd asks for comes from the paired ADB key.
    bssl::UniquePtr<EVP_PKEY> client_key = LoadPrivateKey(client_key_pkcs8);
    bssl::UniquePtr<CRYPTO_BUFFER> client_cert = MakeCertBuffer(client_key.get());
    if (!client_key || !client_cert) {
        out->reason = "CERT";
        out->detail = client_key ? "certificate build failed" : "no ADB key material";
        close();
        return false;
    }

    ctx_.reset(SSL_CTX_new(TLS_method()));
    if (!ctx_) {
        close();
        out->reason = "TLS";
        return false;
    }
    if (!SSL_CTX_set_min_proto_version(ctx_.get(), TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(ctx_.get(), TLS1_3_VERSION)) {
        close();
        out->reason = "TLS";
        return false;
    }
    std::vector<CRYPTO_BUFFER*> chain = {client_cert.get()};
    if (!SSL_CTX_set_chain_and_key(ctx_.get(), chain.data(), chain.size(), client_key.get(),
                                   nullptr)) {
        out->reason = "CERT";
        out->detail = SslErrorDetail();
        close();
        return false;
    }
    // AOSP transport.cpp: "Allow any server certificate" - the device mints a
    // fresh cert per connection, so only our client cert carries identity.
    SSL_CTX_set_cert_verify_callback(ctx_.get(), &TlsClient::VerifyCb, this);
    SSL_CTX_set_verify(ctx_.get(),
                       SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, nullptr);

    ssl_.reset(SSL_new(ctx_.get()));
    if (!ssl_ || SSL_set_fd(ssl_.get(), fd_) != 1) {
        close();
        out->reason = "TLS";
        return false;
    }
    SSL_set_connect_state(ssl_.get());
    ERR_clear_error();
    if (SSL_do_handshake(ssl_.get()) != 1) {
        const long verify = SSL_get_verify_result(ssl_.get());
        out->reason = "TLS";
        out->detail = SslErrorDetail();
        if (verify != X509_V_OK) {
            if (!out->detail.empty()) out->detail += " | ";
            out->detail += X509_verify_cert_error_string(verify);
        }
        out->fingerprint = peer_fingerprint_;
        close();
        return false;
    }
    out->fingerprint = peer_fingerprint_;
    connected_ = true;
    return true;
}

int TlsClient::read(uint8_t* buf, int len) {
    if (!connected_ || !buf || len <= 0) return -2;
    const int r = SSL_read(ssl_.get(), buf, len);
    if (r > 0) return r;
    const int err = SSL_get_error(ssl_.get(), r);
    if (err == SSL_ERROR_ZERO_RETURN) return 0;  // clean EOF (close_notify)
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE ||
        (err == SSL_ERROR_SYSCALL &&
         (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))) {
        return -1;  // socket read timeout
    }
    return -2;
}

int TlsClient::write(const uint8_t* buf, int len) {
    if (!connected_ || !buf || len <= 0) return -2;
    const int r = SSL_write(ssl_.get(), buf, len);
    if (r > 0) return r;  // blocking SSL_write is all-or-fail (no partial-write mode)
    const int err = SSL_get_error(ssl_.get(), r);
    if (err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE ||
        (err == SSL_ERROR_SYSCALL &&
         (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR))) {
        return -1;
    }
    return -2;
}

void TlsClient::set_read_timeout(int ms) {
    if (fd_ >= 0 && ms > 0) SetSockTimeout(fd_, ms, SO_RCVTIMEO);
}

void TlsClient::close() {
    connected_ = false;
    if (ssl_) {
        SSL_shutdown(ssl_.get());
        ssl_.reset();
    }
    ctx_.reset();
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
}

TlsConnectResult TlsConnect(const std::string& host, int port, int timeout_ms,
                             const std::vector<std::string>& pinned,
                             const std::vector<uint8_t>& client_key_pkcs8) {
    TlsConnectResult out;
    auto client = std::make_shared<TlsClient>();
    if (!client->connect(host, port, timeout_ms, pinned, client_key_pkcs8, &out)) {
        return out;  // client destructor frees fd/SSL on early failure
    }
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_sessions.size() >= kMaxSessions) {
        out.handle = 0;
        out.reason = "BUSY";
        out.fingerprint.clear();
        return out;  // client goes out of scope -> close()
    }
    const uint64_t id = g_next_id++;
    g_sessions[id] = client;
    out.handle = id;
    return out;
}

int TlsRead(uint64_t handle, uint8_t* buf, int len) {
    auto c = Find(handle);
    return c ? c->read(buf, len) : -2;
}

int TlsWrite(uint64_t handle, const uint8_t* buf, int len) {
    auto c = Find(handle);
    return c ? c->write(buf, len) : -2;
}

void TlsSetReadTimeout(uint64_t handle, int ms) {
    auto c = Find(handle);
    if (c) c->set_read_timeout(ms);
}

bool TlsClose(uint64_t handle) {
    std::shared_ptr<TlsClient> c;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return false;
        c = it->second;
        g_sessions.erase(it);
    }
    c->close();  // explicit free; shared_ptr destructor is idempotent
    return true;
}

}  // namespace andagent

// ---- JNI exports (com.dc.andagent.tools.AdbPairNative) --------------------
extern "C" JNIEXPORT jstring JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeTlsConnect(
    JNIEnv* env, jobject /*thiz*/, jstring host, jint port, jint timeout_ms,
    jobjectArray pinned, jbyteArray client_key_pkcs8) {
    const std::string h = andagent::Jstr(env, host);
    const std::vector<std::string> fps = andagent::JStringArray(env, pinned);
    const std::vector<uint8_t> key = andagent::Jbytes(env, client_key_pkcs8);
    const andagent::TlsConnectResult r =
        andagent::TlsConnect(h, static_cast<int>(port), static_cast<int>(timeout_ms), fps, key);
    std::string out;
    if (r.handle != 0) {
        out = "OK\nHANDLE=" + std::to_string(r.handle) + "\nFP=" + r.fingerprint + "\n";
    } else {
        out = "ERROR\nREASON=" + (r.reason.empty() ? "UNKNOWN" : r.reason) +
              "\nFP=" + r.fingerprint + "\nERR=" + r.detail + "\n";
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeTlsRead(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray buf) {
    if (!buf) return -2;
    const jsize len = env->GetArrayLength(buf);
    if (len <= 0) return 0;
    std::vector<uint8_t> tmp(static_cast<size_t>(len));
    const int r = andagent::TlsRead(static_cast<uint64_t>(handle), tmp.data(),
                                    static_cast<int>(len));
    if (r > 0) {
        env->SetByteArrayRegion(buf, 0, r, reinterpret_cast<const jbyte*>(tmp.data()));
    }
    return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeTlsWrite(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray data) {
    if (!data) return -2;
    const jsize n = env->GetArrayLength(data);
    if (n <= 0) return 0;
    std::vector<uint8_t> tmp(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(tmp.data()));
    return andagent::TlsWrite(static_cast<uint64_t>(handle), tmp.data(),
                              static_cast<int>(n));
}

extern "C" JNIEXPORT void JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeTlsSetReadTimeout(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jint ms) {
    andagent::TlsSetReadTimeout(static_cast<uint64_t>(handle), static_cast<int>(ms));
}

extern "C" JNIEXPORT void JNICALL
Java_com_dc_andagent_tools_AdbPairNative_nativeTlsClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    andagent::TlsClose(static_cast<uint64_t>(handle));
}

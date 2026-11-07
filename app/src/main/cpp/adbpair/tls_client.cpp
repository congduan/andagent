/*
 * R103 A: ADB over TLS 1.3 transport (client side) - implementation + JNI.
 *
 * BoringSSL API usage was checked against
 *   app/src/main/cpp/adbpair/prebuilt/include/openssl/{ssl.h,x509.h,digest.h}
 * (SSL_CTX_set_cert_verify_callback / X509_STORE_CTX_get0_cert /
 *  X509_digest(EVP_sha256) / SSL_get_peer_certificate / SSL_do_handshake /
 *  SSL_get_error / TLS1_3_VERSION). No API is used from memory.
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
#include <openssl/evp.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

namespace andagent {
namespace {

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

/** Accept "AA:BB:..", uppercase, surrounding whitespace; emit bare lowercase hex. */
std::string NormalizeFingerprint(const std::string& in) {
    std::string s;
    s.reserve(in.size());
    for (char c : in) {
        if (c == ':' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
        if (c >= 'A' && c <= 'F') c = static_cast<char>(c - 'A' + 'a');
        s += c;
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

}  // namespace

std::string CertFingerprintHex(X509* cert) {
    if (!cert) return {};
    uint8_t digest[EVP_MAX_MD_SIZE];
    unsigned len = 0;
    if (X509_digest(cert, EVP_sha256(), digest, &len) != 1 || len == 0) return {};
    return Hex(digest, len);
}

TlsClient::~TlsClient() { close(); }

int TlsClient::VerifyCb(X509_STORE_CTX* store_ctx, void* arg) {
    auto* self = static_cast<TlsClient*>(arg);
    if (!self) return 0;
    X509* leaf = X509_STORE_CTX_get0_cert(store_ctx);
    const std::string fp = CertFingerprintHex(leaf);
    if (fp.empty()) return 0;
    self->peer_fingerprint_ = fp;
    if (self->pinned_.empty()) return 1;  // no identity captured -> trust-all (TLS 1.3 only)
    for (const auto& pinned : self->pinned_) {
        if (pinned == fp) return 1;
    }
    self->pin_mismatch_ = true;
    return 0;  // abort the handshake before any ADB bytes are sent
}

bool TlsClient::connect(const std::string& host, int port, int timeout_ms,
                        const std::vector<std::string>& pinned_fingerprints,
                        TlsConnectResult* out) {
    out->handle = 0;
    out->reason.clear();
    out->fingerprint.clear();
    if (host.empty() || port <= 0 || port > 65535 || timeout_ms <= 0) {
        out->reason = "INVALID_ARGS";
        return false;
    }
    pinned_.clear();
    for (const auto& p : pinned_fingerprints) {
        const std::string n = NormalizeFingerprint(p);
        if (!n.empty()) pinned_.push_back(n);
    }

    fd_ = TcpConnect(host, port, timeout_ms);
    if (fd_ < 0) {
        out->reason = "NETWORK";
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
    // Skip system CA chain; pin the pairing-time fingerprint instead.
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
    if (SSL_do_handshake(ssl_.get()) != 1) {
        out->reason = pin_mismatch_ ? "PIN_MISMATCH" : "TLS";
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
                             const std::vector<std::string>& pinned) {
    TlsConnectResult out;
    auto client = std::make_shared<TlsClient>();
    if (!client->connect(host, port, timeout_ms, pinned, &out)) {
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
    jobjectArray pinned) {
    const std::string h = andagent::Jstr(env, host);
    const std::vector<std::string> fps = andagent::JStringArray(env, pinned);
    const andagent::TlsConnectResult r =
        andagent::TlsConnect(h, static_cast<int>(port), static_cast<int>(timeout_ms), fps);
    std::string out;
    if (r.handle != 0) {
        out = "OK\nHANDLE=" + std::to_string(r.handle) + "\nFP=" + r.fingerprint + "\n";
    } else {
        out = "ERROR\nREASON=" + (r.reason.empty() ? "UNKNOWN" : r.reason) +
              "\nFP=" + r.fingerprint + "\n";
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

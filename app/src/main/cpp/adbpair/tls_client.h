/*
 * R103 A: ADB over TLS 1.3 transport (client side).
 *
 * Declaration of TlsClient (BoringSSL SSL_CTX/SSL over a blocking TCP fd) and
 * the process-wide handle registry the JNI shims talk to. Implementation and
 * the JNI exports live in tls_client.cpp.
 *
 * Design decision (see battle report): TLS is a pure transport-layer swap.
 * The ADB wire protocol (CNXN/AUTH/OPEN/WRTE/CLSE/sync:) is unchanged from
 * the plaintext path and stays in Kotlin (AdbClient/AdbProtocol/AdbSync), so
 * this file contains only: TCP connect, the plaintext CNXN/STLS prelude,
 * TLS 1.3 handshake with the ADB key certificate, peer-certificate SHA-256
 * fingerprint pinning, and byte-stream read/write. We deliberately do NOT
 * reimplement the ADB session in C++ (that was the discarded "option B").
 *
 * Wire reality (verified against a real adbd and AOSP sources):
 *   adb/adb.cpp handle_packet(A_STLS)  -> send_tls_request + adb_auth_tls_handshake
 *   adb/transport.cpp BlockingConnectionAdapter -> read thread stops on STLS
 *   adb/client/auth.cpp adb_tls_set_certificate -> client cert from the ADB key
 * The wireless-debugging main port is therefore NOT TLS-from-byte-zero:
 *   TCP -> CNXN(plaintext) -> STLS <- -> STLS echo -> TLS 1.3 (mTLS) -> CNXN
 * Sending a ClientHello first makes adbd reset the socket (reason "STLS").
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <openssl/base.h>
#include <openssl/ssl.h>

namespace andagent {

/** SHA-256 fingerprint = 32 bytes -> 64 lowercase hex chars. */
constexpr size_t kFingerprintHexSize = 64;

/** Result of a TLS connect attempt. handle == 0 means failure. */
struct TlsConnectResult {
    uint64_t handle = 0;
    /// Machine-readable failure code (empty on success). "STLS" = prelude,
    /// "NO_TLS" = plaintext adbd, "TLS" = handshake, "CERT" = local key/cert,
    /// "NETWORK" = TCP connect.
    std::string reason;
    std::string fingerprint;  // SHA-256 hex of the peer leaf cert (per-connection)
    std::string detail;       // BoringSSL error string (diagnostics only)
};

/**
 * One TLS 1.3 connection over a blocking TCP socket.
 *
 * Certificate verification policy: accept any server certificate, exactly like
 * AOSP (transport.cpp FdConnection::DoTlsHandshake, ADB_HOST branch:
 * "Allow any server certificate"). adbd mints a fresh self-signed certificate
 * per connection from a per-process RSA key (daemon/auth.cpp
 * adbd_auth_tls_handshake + X509_gmtime_adj), so the peer leaf digest changes
 * on every connect and can NOT be used as a stable identity binding. The
 * pinned_fingerprints argument is therefore accepted for diagnostics only.
 * Identity is one-way: adbd verifies OUR client certificate against its
 * authorized-key list (daemon/auth.cpp adbd_tls_verify_cert).
 *
 * Client authentication: the certificate adbd asks for must be derived from
 * the same ADB key registered during pairing, so connect() takes the PKCS#8
 * DER of that key and builds AOSP's self-signed cert from it
 * (crypto/x509_generator.cpp). Without it adbd answers
 * "certificate_required (116)".
 */
class TlsClient {
public:
    TlsClient() = default;
    ~TlsClient();
    TlsClient(const TlsClient&) = delete;
    TlsClient& operator=(const TlsClient&) = delete;

    bool connect(const std::string& host, int port, int timeout_ms,
                 const std::vector<std::string>& pinned_fingerprints,
                 const std::vector<uint8_t>& client_key_pkcs8,
                 TlsConnectResult* out);

    /** >0: bytes read; 0: clean EOF; -1: read timeout; -2: fatal error. */
    int read(uint8_t* buf, int len);
    /** >0: bytes written; -1: write timeout; -2: fatal error. */
    int write(const uint8_t* buf, int len);
    void set_read_timeout(int ms);
    bool alive() const { return connected_; }
    void close();

private:
    static int VerifyCb(X509_STORE_CTX* store_ctx, void* arg);

    bssl::UniquePtr<SSL_CTX> ctx_;
    bssl::UniquePtr<SSL> ssl_;
    int fd_ = -1;
    std::string peer_fingerprint_;
    bool connected_ = false;
};

/* ---- Process-wide handle registry (guarded internally) -------------------
 * The map owns shared_ptr<TlsClient> so a blocking read can outlive a close
 * from another thread without use-after-free. Handles are monotonic ids.
 * A hard cap prevents unbounded growth if a caller forgets nativeTlsClose. */

TlsConnectResult TlsConnect(const std::string& host, int port, int timeout_ms,
                             const std::vector<std::string>& pinned,
                             const std::vector<uint8_t>& client_key_pkcs8);
int TlsRead(uint64_t handle, uint8_t* buf, int len);
int TlsWrite(uint64_t handle, const uint8_t* buf, int len);
void TlsSetReadTimeout(uint64_t handle, int ms);
bool TlsClose(uint64_t handle);

/** Pure helper: lowercase-hex SHA-256 of an X509 leaf DER. Empty on failure. */
std::string CertFingerprintHex(X509* cert);

}  // namespace andagent

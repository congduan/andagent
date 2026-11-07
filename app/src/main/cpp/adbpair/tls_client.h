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
 * this file contains only: TCP connect, TLS 1.3 handshake, peer-certificate
 * SHA-256 fingerprint pinning, and byte-stream read/write. We deliberately do
 * NOT reimplement the ADB session in C++ (that was the discarded "option B").
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
    std::string reason;       // machine-readable failure code (empty on success)
    std::string fingerprint;  // SHA-256 hex of the peer leaf certificate
};

/**
 * One TLS 1.3 connection over a blocking TCP socket.
 *
 * Certificate verification policy: skip the system CA chain (self-signed adbd
 * environment) and instead pin the peer leaf certificate's SHA-256 digest to
 * the list captured at pairing time. An empty pinned list means "trust all"
 * (still TLS 1.3, but no identity binding) - used when a caller forces TLS on
 * a host that was never paired through adb_pair.
 */
class TlsClient {
public:
    TlsClient() = default;
    ~TlsClient();
    TlsClient(const TlsClient&) = delete;
    TlsClient& operator=(const TlsClient&) = delete;

    bool connect(const std::string& host, int port, int timeout_ms,
                 const std::vector<std::string>& pinned_fingerprints,
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
    std::vector<std::string> pinned_;
    std::string peer_fingerprint_;
    bool pin_mismatch_ = false;
    bool connected_ = false;
};

/* ---- Process-wide handle registry (guarded internally) -------------------
 * The map owns shared_ptr<TlsClient> so a blocking read can outlive a close
 * from another thread without use-after-free. Handles are monotonic ids.
 * A hard cap prevents unbounded growth if a caller forgets nativeTlsClose. */

TlsConnectResult TlsConnect(const std::string& host, int port, int timeout_ms,
                             const std::vector<std::string>& pinned);
int TlsRead(uint64_t handle, uint8_t* buf, int len);
int TlsWrite(uint64_t handle, const uint8_t* buf, int len);
void TlsSetReadTimeout(uint64_t handle, int ms);
bool TlsClose(uint64_t handle);

/** Pure helper: lowercase-hex SHA-256 of an X509 leaf DER. Empty on failure. */
std::string CertFingerprintHex(X509* cert);

}  // namespace andagent

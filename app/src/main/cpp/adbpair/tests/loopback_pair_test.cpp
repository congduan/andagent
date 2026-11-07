/*
 * R100 ③: loopback end-to-end test of the adb_pair protocol state machine.
 *
 * Spins up a local TLS 1.3 "adbd-like" server (BoringSSL, self-signed cert,
 * RFC 5705 exporter "adb-label", AOSP pairing_auth server role, PeerInfo
 * framing) and drives the real DoPair() client against it over 127.0.0.1.
 * This validates the whole wire path except a physical handset:
 *   TCP -> TLS 1.3 -> exporter password augmentation -> SPAKE2 msg exchange
 *   -> AES-128-GCM PeerInfo exchange (8192-byte packed struct, 6-byte header).
 *
 * Build/run: ./run_loopback_test.sh   (host g++ + PIC host BoringSSL)
 *
 * The server mirrors AOSP pairing_connection.cpp (write-then-read on both
 * sides, which is deadlock-free over TCP for these small messages).
 */
#include <jni.h>  // required by the included adbpair_jni.cpp

// Pull in the real JNI client implementation so we exercise DoPair() itself.
#include "../adbpair_jni.cpp"

#include <atomic>
#include <cstring>
#include <netinet/in.h>
#include <sys/socket.h>
#include <thread>
#include <unistd.h>

namespace {

// TLS 1.3 server side (AOSP tls_connection.cpp DoHandshake, server role).
bool SetupTlsServer(TlsSession* s, const CertKey& ck, int fd) {
    s->ctx.reset(SSL_CTX_new(TLS_method()));
    if (!s->ctx) return false;
    if (!SSL_CTX_set_min_proto_version(s->ctx.get(), TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(s->ctx.get(), TLS1_3_VERSION)) {
        return false;
    }
    SSL_CTX_set_cert_verify_callback(s->ctx.get(), TrustAllCb, nullptr);
    std::vector<CRYPTO_BUFFER*> chain = {ck.cert_der.get()};
    if (!SSL_CTX_set_chain_and_key(s->ctx.get(), chain.data(), chain.size(), ck.pkey.get(),
                                   nullptr)) {
        return false;
    }
    SSL_CTX_set_verify(s->ctx.get(), SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT, nullptr);
    s->ssl.reset(SSL_new(s->ctx.get()));
    if (!s->ssl || SSL_set_fd(s->ssl.get(), fd) != 1) return false;
    SSL_set_accept_state(s->ssl.get());
    return SSL_do_handshake(s->ssl.get()) == 1;
}

struct ServerResult {
    bool handshake = false;
    bool spake2_ok = false;
    bool peer_info_decrypted = false;
    std::string error;
};

// One pairing attempt on the server side.
ServerResult RunServer(int fd, const std::string& code) {
    ServerResult r;
    CertKey ck;
    if (!GenerateSelfSignedCert(&ck)) {
        r.error = "cert";
        return r;
    }
    TlsSession tls;
    if (!SetupTlsServer(&tls, ck, fd)) {
        r.error = "tls";
        return r;
    }
    r.handshake = true;

    // pairing_connection.cpp:190-198 (server side too): code + TLS exporter.
    std::vector<uint8_t> ekm(kExportedKeySize);
    if (SSL_export_keying_material(tls.ssl.get(), ekm.data(), ekm.size(), kExportedKeyLabel,
                                   sizeof(kExportedKeyLabel), nullptr, 0, 0) != 1) {
        r.error = "export";
        return r;
    }
    std::vector<uint8_t> pswd(code.begin(), code.end());
    pswd.insert(pswd.end(), ekm.begin(), ekm.end());

    PairingAuthCtx* auth = pairing_auth_server_new(pswd.data(), pswd.size());
    if (!auth) {
        r.error = "spake2_new";
        return r;
    }
    struct G {
        PairingAuthCtx* p;
        ~G() { pairing_auth_destroy(p); }
    } g{auth};

    // DoExchangeMsgs (AOSP): write our msg, then read theirs.
    size_t msg_len = pairing_auth_msg_size(auth);
    std::vector<uint8_t> msg(msg_len);
    pairing_auth_get_spake2_msg(auth, msg.data());
    if (!WritePacket(tls.ssl.get(), kTypeSpake2Msg, msg.data(), msg.size())) {
        r.error = "write_spake2";
        return r;
    }
    PairingPacketHeader hdr = {};
    std::vector<uint8_t> their_msg;
    if (!ReadPacket(tls.ssl.get(), &hdr, &their_msg) || hdr.type != kTypeSpake2Msg ||
        their_msg.empty() ||
        !pairing_auth_init_cipher(auth, their_msg.data(), their_msg.size())) {
        r.error = "spake2_exchange";
        return r;
    }
    r.spake2_ok = true;

    // DoExchangePeerInfo (AOSP): write OUR PeerInfo FIRST, then read theirs.
    // (AOSP writes before reading, so a wrong-code client still receives a
    // server PEER_INFO and fails with WRONG_CODE at GCM auth.)
    PeerInfo ours = {};
    ours.type = kPeerInfoDeviceGuid;
    const char* guid = "loopback-device-guid-1234";
    memcpy(ours.data, guid, strlen(guid));
    std::vector<uint8_t> enc(pairing_auth_safe_encrypted_size(auth, sizeof(ours)));
    size_t enc_len = 0;
    if (!pairing_auth_encrypt(auth, reinterpret_cast<uint8_t*>(&ours), sizeof(ours), enc.data(),
                              &enc_len)) {
        r.error = "encrypt_peerinfo";
        return r;
    }
    enc.resize(enc_len);
    if (!WritePacket(tls.ssl.get(), kTypePeerInfo, enc.data(), enc.size())) {
        r.error = "write_peerinfo";
        return r;
    }

    std::vector<uint8_t> their_enc;
    if (!ReadPacket(tls.ssl.get(), &hdr, &their_enc) || hdr.type != kTypePeerInfo) {
        r.error = "read_peerinfo";
        return r;
    }
    std::vector<uint8_t> dec(pairing_auth_safe_decrypted_size(auth, their_enc.data(),
                                                              their_enc.size()));
    size_t dec_len = 0;
    if (!dec.empty() &&
        pairing_auth_decrypt(auth, their_enc.data(), their_enc.size(), dec.data(), &dec_len) &&
        dec_len == sizeof(PeerInfo)) {
        r.peer_info_decrypted = true;
    }
    return r;
}

// Bind a loopback listener, return fd + chosen port.
int Listen(int* port_out) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = 0;
    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0 || listen(fd, 1) < 0) {
        close(fd);
        return -1;
    }
    socklen_t len = sizeof(addr);
    getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len);
    *port_out = ntohs(addr.sin_port);
    return fd;
}

PairResult OneRoundTrip(const std::string& server_code, const std::string& client_code,
                        ServerResult* srv_out) {
    int port = 0;
    int listen_fd = Listen(&port);
    if (listen_fd < 0) {
        PairResult r;
        r.reason = "LISTEN";
        return r;
    }

    std::atomic<bool> server_done{false};
    ServerResult srv;
    std::thread server([&] {
        int conn = accept(listen_fd, nullptr, nullptr);
        if (conn >= 0) {
            struct timeval tv = {15, 0};
            setsockopt(conn, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
            setsockopt(conn, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
            srv = RunServer(conn, server_code);
            close(conn);
        } else {
            srv.error = "accept";
        }
        server_done = true;
    });

    // Real client: DoPair() from adbpair_jni.cpp.
    std::vector<uint8_t> adb_pubkey(16, 0x41);  // dummy adbkey.pub payload
    PairResult client = DoPair("127.0.0.1", port, client_code, adb_pubkey, "AndAgent");

    if (server.joinable()) server.join();
    close(listen_fd);
    *srv_out = srv;
    return client;
}

}  // namespace

int main() {
    printf("=== R100 ③ adb_pair loopback protocol test ===\n\n");

    // Case 1: matching codes -> full success, device GUID returned.
    ServerResult srv1;
    PairResult r1 = OneRoundTrip("123456", "123456", &srv1);
    printf("[case 1: same code] client.ok=%d reason=%s guid=%s\n", r1.ok, r1.reason.c_str(),
           r1.guid.c_str());
    printf("                    server: handshake=%d spake2=%d peerinfo_dec=%d err=%s\n",
           srv1.handshake, srv1.spake2_ok, srv1.peer_info_decrypted, srv1.error.c_str());
    bool case1 = r1.ok && r1.guid == "loopback-device-guid-1234" && srv1.handshake &&
                 srv1.spake2_ok && srv1.peer_info_decrypted;
    printf("[%s] case 1: TLS 1.3 + exporter + SPAKE2 + PeerInfo roundtrip\n\n",
           case1 ? "PASS" : "FAIL");

    // Case 2: wrong code -> client must report WRONG_CODE (GCM auth failure).
    ServerResult srv2;
    PairResult r2 = OneRoundTrip("654321", "123456", &srv2);
    printf("[case 2: wrong code] client.ok=%d reason=%s\n", r2.ok, r2.reason.c_str());
    printf("                     server: peerinfo_dec=%d (expected false) err=%s\n",
           srv2.peer_info_decrypted, srv2.error.c_str());
    bool case2 = !r2.ok && r2.reason == "WRONG_CODE";
    printf("[%s] case 2: wrong pairing code fails at PeerInfo GCM auth\n\n",
           case2 ? "PASS" : "FAIL");

    int failures = (case1 ? 0 : 1) + (case2 ? 0 : 1);
    printf("=== %d failure(s) ===\n", failures);
    return failures == 0 ? 0 : 1;
}

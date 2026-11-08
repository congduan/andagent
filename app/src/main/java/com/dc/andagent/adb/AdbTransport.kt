package com.dc.andagent.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * R103 A：ADB 传输层抽象。
 *
 * 明文路径用 [TcpTransport]（现有 java.net.Socket 逻辑原样抽出）；配对过的
 * Android 11+ 无线调试主端口要求 TLS 1.3，用 [TlsTransport]（JNI 桥，见
 * cpp/adbpair/tls_client.cpp）。TLS 只是传输层替换——CNXN/AUTH/OPEN/WRTE/CLSE
 * 与 sync: 帧格式与明文完全一致，协议层仍在 AdbProtocol/AdbSync。
 *
 * 线上顺序（对照 AOSP adb/adb.cpp handle_packet(A_STLS) + client/auth.cpp
 * adb_tls_set_certificate，真机已验）：TCP → 明文 CNXN → 服务端 STLS →
 * 客户端回 STLS → TLS 1.3 握手（必须带配对时那把 ADB 私钥签出的证书）→
 * 再发 CNXN 进入 AUTH 流程。CNXN/STLS 前导与证书都由原生层完成。
 *
 * 接口以 InputStream/OutputStream 暴露（[AdbClient] 原本就持有这两个流），
 * 对 TcpTransport 直接是 socket 原生流、零适配开销；对 TlsTransport 由内部
 * 适配器把 JNI 字节读写映射成 Java 流语义。
 */
interface AdbTransport {
    fun open(host: String, port: Int, timeoutMs: Int)
    fun inputStream(): InputStream
    fun outputStream(): OutputStream
    fun setReadTimeout(ms: Int)
    fun isAlive(): Boolean
    fun close()
}

/** 现有明文 Socket 逻辑抽出，行为与 R100 ② 一致（默认传输）。 */
class TcpTransport : AdbTransport {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open(host: String, port: Int, timeoutMs: Int) {
        val s = Socket()
        socket = s // 先登记，连接失败时 close() 也能回收 fd
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun inputStream(): InputStream = input ?: throw IOException("TCP 未连接")
    override fun outputStream(): OutputStream = output ?: throw IOException("TCP 未连接")
    override fun setReadTimeout(ms: Int) {
        runCatching { socket?.soTimeout = ms }
    }

    override fun isAlive(): Boolean = socket?.let { it.isConnected && !it.isClosed } ?: false

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}

/* ---- JNI TLS 桥的可替换边界（JVM 单测用 fake 注入，生产用 AdbPairNative） ---- */

/** nativeTlsRead/nativeTlsWrite 的返回码（>0 = 字节数）。 */
const val TLS_READ_EOF = 0
const val TLS_READ_TIMEOUT = -1
const val TLS_READ_ERROR = -2

data class TlsConnectResult(
    val handle: Long? = null,
    val fingerprint: String? = null,
    val reason: String? = null,
    /** BoringSSL 原生错误串（诊断用，不参与文案分支）。 */
    val detail: String? = null,
)

/** 5 个 JNI 方法的 Kotlin 面；[TlsTransport] 只依赖此接口，便于脱离 .so 测试。 */
interface TlsNative {
    /**
     * @param pinned 仅诊断用：adbd 每次连接都重新生成服务端证书（AOSP transport.cpp
     * "Allow any server certificate"），指纹无法作为身份绑定，原生层只记录不校验。
     * @param clientKeyPkcs8 本机 ADB 私钥（PKCS#8 DER）。adbd 在 TLS 1.3 握手中
     * 要求客户端证书，该证书由这把钥匙签发（AOSP x509_generator.cpp），缺了会被
     * `alert certificate_required` 顶回。
     */
    fun connect(
        host: String,
        port: Int,
        timeoutMs: Int,
        pinned: List<String>,
        clientKeyPkcs8: ByteArray,
    ): TlsConnectResult

    fun read(handle: Long, buf: ByteArray): Int
    fun write(handle: Long, data: ByteArray): Int
    fun setReadTimeout(handle: Long, ms: Int)
    fun close(handle: Long)
}

/**
 * TLS 传输。[native] 默认由 AdbSessionHub 注入 AdbPairNative（tools 层），此处不反向依赖 tools。
 * [clientKeyPkcs8] 为本机 ADB 私钥，adbd 要求客户端证书时由原生层用它签发——这才是身份凭据。
 * [pinnedFingerprints] 仅诊断/展示：adbd 每次连接都换服务端证书，指纹不能当身份绑定用。
 */
class TlsTransport(
    private val pinnedFingerprints: List<String> = emptyList(),
    private val native: TlsNative,
    private val clientKeyPkcs8: ByteArray = ByteArray(0),
) : AdbTransport {
    private var handle: Long? = null

    override fun open(host: String, port: Int, timeoutMs: Int) {
        val r = native.connect(host, port, timeoutMs, pinnedFingerprints, clientKeyPkcs8)
        val id = r.handle ?: throw IOException(formatConnectError(r.reason, r.detail))
        handle = id
        runCatching { native.setReadTimeout(id, timeoutMs) }
    }

    override fun inputStream(): InputStream = TlsInputStream()
    override fun outputStream(): OutputStream = TlsOutputStream()

    override fun setReadTimeout(ms: Int) {
        handle?.let { runCatching { native.setReadTimeout(it, ms) } }
    }

    override fun isAlive(): Boolean = handle != null

    override fun close() {
        // 先置 null 再 close：在飞 read/write 拿到 null handle 返回 EOF/IOException，
        // 不再进入 native；等它们退出 monitor 后这里才真正 free（@Synchronized 互斥保证）。
        val h = handle
        handle = null
        h?.let { runCatching { native.close(it) } }
    }

    private inner class TlsInputStream : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        @Synchronized
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val h = handle ?: return -1
            val chunk = if (off == 0 && len == b.size) b else ByteArray(len)
            val n = native.read(h, chunk)
            return when {
                n > 0 -> {
                    if (chunk !== b) chunk.copyInto(b, off, 0, n)
                    n
                }
                n == TLS_READ_EOF -> -1
                n == TLS_READ_TIMEOUT -> throw SocketTimeoutException("TLS 读取超时")
                else -> throw IOException("TLS 读取失败（code=$n）")
            }
        }
    }

    private inner class TlsOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        // @Synchronized 与 read 互斥：disconnect 并发 close 时不会 free 在飞 SSL_read
        // 的句柄（use-after-free 崩溃根因——AdbSessionHub 与 AdbClient 是两把锁，覆盖不了）。
        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val h = handle ?: throw IOException("TLS 未连接")
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val n = native.write(h, data)
            if (n != data.size) throw IOException("TLS 写入失败（$n/${data.size} 字节）")
        }
    }

    companion object {
        /**
         * 纯函数：把 JNI 失败码翻译成用户文案，不退化成泛化 ERROR（R103 防失败条款）。
         * [detail] 是 BoringSSL 原始错误串，只在实现分支上附带，避免正常文案变噪声。
         *
         * 注：不再有 PIN_MISMATCH —— adbd 每次连接都重新生成服务端证书（AOSP
         * transport.cpp "Allow any server certificate"），指纹 pin 不可能成立；
         * 身份由 adbd 校验本机客户端证书（即本机 ADB 公钥）来保证。
         */
        fun formatConnectError(reason: String?, detail: String? = null): String {
            val base = when (reason) {
                "NETWORK" -> "❌ TLS 连接失败：无法连接目标主机:端口（网络不可达 / 端口错误 / 超时）。"
                "STLS" ->
                    "❌ ADB 主端口交互失败：明文 CNXN → STLS 前导没有走通（对端不是无线调试主端口，" +
                        "或端口被别的 adb 会话占用）。请确认填的是「无线调试」页的主端口，且没有其它主机" +
                        "同时连着该设备（adbd 同一时间只服务一个无线 TLS 客户端）。"
                "NO_TLS" ->
                    "❌ 目标端口是明文 adbd（回了 CNXN 而非 STLS）：该设备未走无线调试 TLS，" +
                        "请显式传 tls=false 改走明文 + AUTH。"
                "CERT" ->
                    "❌ 本机 ADB 私钥不可用：无法用它签发 adbd 要求的客户端证书。" +
                        "请重新执行 adb_pair 或重置本机 ADB 密钥后重试。"
                "TLS" -> "❌ TLS 1.3 握手失败：请确认目标是已配对的 Android 11+ 无线调试主端口，" +
                    "且本机 ADB 公钥已在设备白名单内（首次连接需先 adb_pair）。"
                "UNAVAILABLE", "UNSUPPORTED_ABI" ->
                    "❌ TLS 传输不可用：原生 BoringSSL 仅内置 arm64-v8a，需 arm64 真机；" +
                        "若目标是传统明文 adbd，可显式传 tls=false 走明文 + AUTH。"
                "JNI_EXCEPTION" -> "❌ TLS 传输 JNI 异常（原生层报错），请重试或改用 tls=false。"
                "BUSY" -> "❌ TLS 会话数达到上限，请先 adb_disconnect 释放旧连接。"
                "INVALID_ARGS" -> "❌ TLS 连接参数非法。"
                else -> "❌ TLS 连接失败：${reason ?: "未知错误"}；若设备未配对可用 tls=false 走明文 + AUTH。"
            }
            return if (detail.isNullOrBlank()) base else "$base\n原生错误：$detail"
        }
    }
}

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
)

/** 5 个 JNI 方法的 Kotlin 面；[TlsTransport] 只依赖此接口，便于脱离 .so 测试。 */
interface TlsNative {
    fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>): TlsConnectResult
    fun read(handle: Long, buf: ByteArray): Int
    fun write(handle: Long, data: ByteArray): Int
    fun setReadTimeout(handle: Long, ms: Int)
    fun close(handle: Long)
}

/**
 * TLS 传输。握手期把「配对时锁定的目标指纹」传给原生层做证书 pin；
 * [native] 默认由 AdbSessionHub 注入 AdbPairNative（tools 层），此处不反向依赖 tools。
 */
class TlsTransport(
    private val pinnedFingerprints: List<String> = emptyList(),
    private val native: TlsNative,
) : AdbTransport {
    private var handle: Long? = null

    override fun open(host: String, port: Int, timeoutMs: Int) {
        val r = native.connect(host, port, timeoutMs, pinnedFingerprints)
        val id = r.handle ?: throw IOException(formatConnectError(r.reason))
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
        handle?.let { runCatching { native.close(it) } }
        handle = null
    }

    private inner class TlsInputStream : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

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

        override fun write(b: ByteArray, off: Int, len: Int) {
            val h = handle ?: throw IOException("TLS 未连接")
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val n = native.write(h, data)
            if (n != data.size) throw IOException("TLS 写入失败（$n/${data.size} 字节）")
        }
    }

    companion object {
        /**
         * 纯函数：把 JNI 失败码翻译成用户文案。指纹不匹配 = 配对过期，明确引导重配对，
         * 不退化成泛化 ERROR（R103 防失败条款）。
         */
        fun formatConnectError(reason: String?): String = when (reason) {
            "PIN_MISMATCH" ->
                "❌ TLS 证书指纹不匹配：目标设备证书与配对时锁定的不一致（配对可能已过期 / " +
                    "设备重置了无线调试 / 连到了别的设备）。请在手机「无线调试 → 使用配对码配对设备」" +
                    "重新执行 adb_pair，再 adb_connect(host, port, tls=true)。"
            "NETWORK" -> "❌ TLS 连接失败：无法连接目标主机:端口（网络不可达 / 端口错误 / 超时）。"
            "TLS" -> "❌ TLS 1.3 握手失败：请确认目标是已配对的 Android 11+ 无线调试主端口。"
            "UNAVAILABLE", "UNSUPPORTED_ABI" ->
                "❌ TLS 传输不可用：原生 BoringSSL 仅内置 arm64-v8a，需 arm64 真机；" +
                    "若目标是传统明文 adbd，可显式传 tls=false 走明文 + AUTH。"
            "JNI_EXCEPTION" -> "❌ TLS 传输 JNI 异常（原生层报错），请重试或改用 tls=false。"
            "BUSY" -> "❌ TLS 会话数达到上限，请先 adb_disconnect 释放旧连接。"
            "INVALID_ARGS" -> "❌ TLS 连接参数非法。"
            else -> "❌ TLS 连接失败：${reason ?: "未知错误"}；若设备未配对可用 tls=false 走明文 + AUTH。"
        }
    }
}

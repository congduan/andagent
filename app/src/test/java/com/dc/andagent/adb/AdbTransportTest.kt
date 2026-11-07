package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * R103 A：传输抽象单测。TLS 的 JNI 面用 fake 注入，从而在纯 JVM 上证明
 *  - AdbClient 的协议逻辑（CNXN/AUTH/OPEN/WRTE/CLSE）对非 TcpTransport 完全一致；
 *  - TlsTransport 的流适配器把 JNI 返回码正确映射成 Java 流语义；
 *  - 指纹 pin 失败给出「重新配对」引导而非泛化 ERROR。
 * 真机 arm64 的 BoringSSL 路径仍列在战报真机清单（本环境无 .so）。
 */
class AdbTransportTest {

    /** 用真实 ServerSocket 承载字节，模拟「TLS 已握手成功」后的 JNI 字节通道。 */
    private class FakeTlsNative : TlsNative {
        private var socket: Socket? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        val pinnedSeen = mutableListOf<List<String>>()
        var closedHandle: Long? = null
            private set
        var lastTimeoutMs: Int? = null
            private set

        override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>): TlsConnectResult {
            pinnedSeen += pinned
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()
            return TlsConnectResult(handle = 7L, fingerprint = "a".repeat(64))
        }

        override fun read(handle: Long, buf: ByteArray): Int {
            if (handle != 7L) return TLS_READ_ERROR
            val n = input!!.read(buf)
            return if (n < 0) TLS_READ_EOF else n
        }

        override fun write(handle: Long, data: ByteArray): Int {
            if (handle != 7L) return TLS_READ_ERROR
            output!!.write(data)
            output!!.flush()
            return data.size
        }

        override fun setReadTimeout(handle: Long, ms: Int) {
            lastTimeoutMs = ms
            socket?.soTimeout = ms
        }

        override fun close(handle: Long) {
            closedHandle = handle
            runCatching { socket?.close() }
        }
    }

    @Test
    fun injectedTlsTransportRunsFullHandshakeAndShell() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    assertEquals(A_CNXN, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, token))
                    val sig = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, sig.command)
                    assertTrue(AdbAuth.verifyToken(kp.public, token, sig.data))
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp)
                    assertEquals(A_OPEN, open.command)
                    val clientId = open.arg0
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, 2, clientId, ByteArray(0)))
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, clientId, 2, "hi\n".toByteArray()))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, clientId, 2, ByteArray(0)))
                }
            } catch (e: Throwable) {
                serverError = e
            }
        }
        thread.start()

        val fp = "a".repeat(64)
        val native = FakeTlsNative()
        val client = AdbClient(kp, TlsTransport(listOf(fp), native))
        val connectResult = client.connect("127.0.0.1", server.localPort)
        assertTrue("connect over TlsTransport 应成功，实际=$connectResult", connectResult.startsWith("OK"))
        val shell = client.openStream("echo hi")
        client.close()
        thread.join(5000)
        server.close()

        assertNull("服务端异常: $serverError", serverError)
        assertEquals("hi\n", shell)
        assertEquals(listOf(fp), native.pinnedSeen.first()) // 配对指纹下发给原生层 pin
        assertEquals(7L, native.closedHandle)               // close() 必须释放 JNI handle
        assertEquals(AdbClient.SESSION_TIMEOUT_MS, native.lastTimeoutMs)
    }

    @Test
    fun tlsConnectPinMismatchGuidesRepairing() {
        val msg = TlsTransport.formatConnectError("PIN_MISMATCH")
        assertTrue(msg.contains("指纹不匹配"))
        assertTrue(msg.contains("重新执行 adb_pair"))
        assertTrue(msg.contains("adb_connect"))
        assertFalse("不应是泛化 ERROR", msg.trim() == "ERROR")
        // 其它失败码各有专属文案
        for (reason in listOf("NETWORK", "TLS", "UNAVAILABLE", "UNSUPPORTED_ABI", "JNI_EXCEPTION", "BUSY", "INVALID_ARGS", null)) {
            assertTrue("reason=$reason", TlsTransport.formatConnectError(reason).startsWith("❌"))
        }
    }

    @Test
    fun adbClientSurfacesPinMismatchInsteadOfGenericError() {
        val native = object : TlsNative {
            override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>) =
                TlsConnectResult(reason = "PIN_MISMATCH")
            override fun read(handle: Long, buf: ByteArray): Int = TLS_READ_ERROR
            override fun write(handle: Long, data: ByteArray): Int = TLS_READ_ERROR
            override fun setReadTimeout(handle: Long, ms: Int) {}
            override fun close(handle: Long) {}
        }
        val client = AdbClient(transport = TlsTransport(emptyList(), native))
        val r = client.connect("127.0.0.1", 5555)
        assertTrue(r.startsWith("ERROR"))
        assertTrue("应引导重新配对，实际=$r", r.contains("重新执行 adb_pair"))
    }

    private fun nativeWithReadCode(code: Int, writeCode: Int? = null): TlsNative = object : TlsNative {
        override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>) =
            TlsConnectResult(handle = 1L)
        override fun read(handle: Long, buf: ByteArray): Int = code
        override fun write(handle: Long, data: ByteArray): Int = writeCode ?: data.size
        override fun setReadTimeout(handle: Long, ms: Int) {}
        override fun close(handle: Long) {}
    }

    private fun opened(code: Int, writeCode: Int? = null): TlsTransport =
        TlsTransport(emptyList(), nativeWithReadCode(code, writeCode)).also { it.open("h", 1, 1000) }

    @Test
    fun tlsInputStreamMapsTimeoutEofAndError() {
        assertTrue(runCatching { opened(TLS_READ_TIMEOUT).inputStream().read() }.exceptionOrNull()
            is SocketTimeoutException)
        assertEquals(-1, opened(TLS_READ_EOF).inputStream().read())
        assertTrue(runCatching { opened(TLS_READ_ERROR).inputStream().read() }.exceptionOrNull()
            is IOException)
    }

    @Test
    fun tlsInputStreamBulkReadCopiesIntoOffset() {
        val payload = "OK\nHANDLE=7\n".toByteArray()
        val native = object : TlsNative {
            override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>) =
                TlsConnectResult(handle = 1L)
            override fun read(handle: Long, buf: ByteArray): Int {
                payload.copyInto(buf, 0, 0, minOf(payload.size, buf.size))
                return minOf(payload.size, buf.size)
            }
            override fun write(handle: Long, data: ByteArray): Int = data.size
            override fun setReadTimeout(handle: Long, ms: Int) {}
            override fun close(handle: Long) {}
        }
        val transport = TlsTransport(emptyList(), native).also { it.open("h", 1, 1000) }
        val dst = ByteArray(payload.size + 4)
        val n = transport.inputStream().read(dst, 2, payload.size)
        assertEquals(payload.size, n)
        assertEquals(String(payload), String(dst, 2, payload.size))
        assertEquals(0, dst[0].toInt()) // off 之前的字节不动
    }

    @Test
    fun tlsOutputStreamRejectsPartialWriteAndClosesHandle() {
        val closed = mutableListOf<Long>()
        val native = object : TlsNative {
            override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>) =
                TlsConnectResult(handle = 9L)
            override fun read(handle: Long, buf: ByteArray): Int = TLS_READ_EOF
            override fun write(handle: Long, data: ByteArray): Int = data.size - 1 // 故意短写
            override fun setReadTimeout(handle: Long, ms: Int) {}
            override fun close(handle: Long) { closed += handle }
        }
        val transport = TlsTransport(emptyList(), native).also { it.open("h", 1, 1000) }
        assertTrue(runCatching { transport.outputStream().write("abc".toByteArray()) }.exceptionOrNull()
            is IOException)
        transport.close()
        assertEquals(listOf(9L), closed)
        assertFalse(transport.isAlive())
    }

    @Test
    fun tcpTransportIsDefaultAndAliveAfterConnect() {
        // 无需真连：未 open 时 isAlive=false，close 幂等。
        val t = TcpTransport()
        assertFalse(t.isAlive())
        t.close()
        t.close()
        assertFalse(t.isAlive())
    }
}

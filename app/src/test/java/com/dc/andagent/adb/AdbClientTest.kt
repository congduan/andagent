package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * R100 ②：确定性单线程 mock ServerSocket 对端（双端共享小端 AdbProtocol/AdbSync，
 * 故天然一致），验证握手（含 RSAPUBLICKEY 授权→重连）、shell 流、SYNC pull/push。
 */
class AdbClientTest {

    private companion object {
        const val SERVER_ID = 2
    }

    @Test
    fun fullHandshakeAndShellEcho() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    // 1) CNXN
                    val cnxn = AdbProtocol.readMessage(inp)
                    assertEquals(A_CNXN, cnxn.command)
                    assertEquals(A_VERSION, cnxn.arg0)
                    assertEquals(AdbFeatures.hostBanner() + "\u0000", String(cnxn.data))
                    // 2) AUTH TOKEN
                    AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, token))
                    // 3) 客户端签名 → JDK 公钥验签
                    val sig = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, sig.command)
                    assertEquals(ADB_AUTH_SIGNATURE, sig.arg0)
                    assertTrue(AdbAuth.verifyToken(kp.public, token, sig.data))
                    // 4) CNXN
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    // 5) OPEN
                    val open = AdbProtocol.readMessage(inp)
                    assertEquals(A_OPEN, open.command)
                    assertTrue(String(open.data).startsWith("shell:echo hi"))
                    val clientId = open.arg0
                    // 6) OKAY
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, SERVER_ID, clientId, ByteArray(0)))
                    // 7) WRTE "hi\n"
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, clientId, SERVER_ID, "hi\n".toByteArray()))
                    // 8) 等客户端 A_OKAY ack（确定性锁步，无 sleep）
                    val ack = AdbProtocol.readMessage(inp)
                    assertEquals(A_OKAY, ack.command)
                    // 9) CLSE
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, clientId, SERVER_ID, ByteArray(0)))
                }
            } catch (e: Throwable) {
                serverError = e
            }
        }
        thread.start()

        val client = AdbClient(kp)
        val connectResult = client.connect("127.0.0.1", server.localPort)
        assertTrue("connect 应成功，实际=$connectResult", connectResult.startsWith("OK"))
        val output = client.openStream("echo hi")
        client.close()
        thread.join(5000)
        server.close()

        assertNull("服务端异常: $serverError", serverError)
        assertEquals("hi\n", output)
    }

    @Test
    fun peerDisconnectsImmediatelyReturnsErrorWithoutThrowing() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        val thread = Thread {
            try { server.accept().use { /* 接受后立刻关闭，不发任何帧 */ } } catch (_: Throwable) {}
        }
        thread.start()

        val client = AdbClient()
        val result = runCatching { client.connect("127.0.0.1", server.localPort) }
        client.close()
        thread.join(5000)
        server.close()

        assertTrue("connect 不得抛异常", result.isSuccess)
        val text = result.getOrThrow()
        assertTrue("需返回明确错误，实际=$text", text.startsWith("ERROR"))
        assertTrue(text.contains("connection closed") || text.contains("失败"))
    }

    @Test
    fun directCnxnWithoutAuth() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    val cnxn = AdbProtocol.readMessage(inp)
                    assertEquals(A_CNXN, cnxn.command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        val result = client.connect("127.0.0.1", server.localPort)
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
        assertTrue(result.startsWith("OK"))
    }

    @Test
    fun openStreamWithoutConnectReturnsError() {
        val client = AdbClient()
        val result = client.openStream("echo hi")
        assertTrue(result.startsWith("ERROR"))
        assertTrue(result.contains("未连接"))
    }

    /**
     * 首次连接签名不被信任 → 对端再发 TOKEN → 客户端回 RSAPUBLICKEY 公钥 →
     * 对端断开等用户授权（AUTH_REQUIRED）；用【同一密钥对】重连 → SIGNATURE 通过 → OK。
     * 对应简报「发公钥→弹框→用户允许后重新握手成功」。
     */
    @Test
    fun reconnectSucceedsAfterPublicKeyAuthorization() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { (it * 11 + 5).toByte() }
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                // ---- 连接 1：未授信 ----
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    val cnxn = AdbProtocol.readMessage(inp); assertEquals(A_CNXN, cnxn.command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, token))
                    val sig = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, sig.command); assertEquals(ADB_AUTH_SIGNATURE, sig.arg0)
                    assertTrue(AdbAuth.verifyToken(kp.public, token, sig.data))
                    // 签名不被信任 → 再发一个 TOKEN
                    AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20) { 9 }))
                    val pub = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, pub.command)
                    assertEquals(ADB_AUTH_RSAPUBLICKEY, pub.arg0)
                    val b64 = String(pub.data).substringBefore(' ').trim()
                    assertEquals("android_pubkey 应为 524 字节", 524, Base64.getDecoder().decode(b64).size)
                    // 断开：模拟设备弹授权框、等待用户点允许
                }
                // ---- 连接 2：用户已允许，公钥已授信 ----
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    val cnxn2 = AdbProtocol.readMessage(inp); assertEquals(A_CNXN, cnxn2.command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, token))
                    val sig2 = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, sig2.command); assertEquals(ADB_AUTH_SIGNATURE, sig2.arg0)
                    assertTrue(AdbAuth.verifyToken(kp.public, token, sig2.data))
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()

        val c1 = AdbClient(kp)
        val first = c1.connect("127.0.0.1", server.localPort)
        c1.close()
        assertTrue("首次应请求用户授权，实际=$first", first.startsWith("AUTH_REQUIRED"))
        val c2 = AdbClient(kp)
        val second = c2.connect("127.0.0.1", server.localPort)
        c2.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
        assertTrue("重连应成功，实际=$second", second.startsWith("OK"))
    }

    /** 发过公钥后仍反复收到 TOKEN（用户点了拒绝）→ 明确拒绝错误，不硬凑。 */
    @Test
    fun repeatedTokenAfterPublicKeyReportsUnauthorized() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    AdbProtocol.readMessage(inp) // CNXN
                    repeat(4) {
                        AdbProtocol.writeMessage(out, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20) { 1 }))
                        // 客户端在第 3 个 TOKEN 时判定未授权并主动断开：读取用 runCatching 吞 EOF。
                        runCatching { AdbProtocol.readMessage(inp) } // SIGNATURE / RSAPUBLICKEY
                    }
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        val result = client.connect("127.0.0.1", server.localPort)
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
        assertTrue("应明确报未授权，实际=$result", result.startsWith("ERROR") && result.contains("未授权"))
    }

    /** SYNC RECV 全链路：OPEN sync: → RECV → DATA/DONE（跨 WRTE 帧）→ 拿到文件字节。 */
    @Test
    fun syncPullReceivesFileOverSyncStream() {
        val payload = "hello from adbd".toByteArray()
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    val cnxn = AdbProtocol.readMessage(inp); assertEquals(A_CNXN, cnxn.command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp); assertEquals(A_OPEN, open.command)
                    assertTrue(String(open.data).startsWith("sync:"))
                    val L = open.arg0; val R = SERVER_ID
                    okHandshake(inp, out, L, R)
                    // 客户端的 RECV 请求 WRTE
                    val req = AdbProtocol.readMessage(inp)
                    assertEquals(A_WRTE, req.command)
                    val rr = SyncPacketReader(); rr.feed(req.data)
                    assertEquals(AdbSync.ID_RECV, rr.poll()!!.id)
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, R, L, ByteArray(0))) // 传输 ack
                    // DATA 响应（拆成两个 WRTE 帧，验证跨帧累积）
                    val dataPacket = AdbSync.encodeData(payload)
                    val mid = dataPacket.size / 2
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, dataPacket.copyOfRange(0, mid)))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, dataPacket.copyOfRange(mid, dataPacket.size)))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    // DONE
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, AdbSync.encode("DONE", ByteArray(0))))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, R, L, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        assertTrue(client.connect("127.0.0.1", server.localPort).startsWith("OK"))
        val result = client.syncPull("/sdcard/x.txt", 1L * 1024 * 1024)
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
        assertTrue("pull 应成功，实际=$result", result is AdbClient.AdbSyncResult.Pulled)
        assertEquals(String(payload), String((result as AdbClient.AdbSyncResult.Pulled).bytes))
    }

    /** SYNC SEND 全链路：SEND+DATA+DONE → 对端回 sync OKAY。 */
    @Test
    fun syncPushSendsFileOverSyncStream() {
        val payload = "push me".toByteArray()
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    AdbProtocol.readMessage(inp) // CNXN
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp); assertEquals(A_OPEN, open.command)
                    val L = open.arg0; val R = SERVER_ID
                    okHandshake(inp, out, L, R)
                    val req = AdbProtocol.readMessage(inp)
                    assertEquals(A_WRTE, req.command)
                    val rr = SyncPacketReader(); rr.feed(req.data)
                    val ids = mutableListOf<String>()
                    var p = rr.poll()
                    while (p != null) { ids += p.id; if (p.id == AdbSync.ID_DATA) assertEquals(String(payload), String(p.data)); p = rr.poll() }
                    assertEquals(listOf(AdbSync.ID_SEND, AdbSync.ID_DATA, AdbSync.ID_DONE), ids)
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, R, L, ByteArray(0))) // 传输 ack
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, AdbSync.encode("OKAY", ByteArray(0))))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, R, L, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        assertTrue(client.connect("127.0.0.1", server.localPort).startsWith("OK"))
        val result = client.syncPush("/data/local/tmp/p.txt", payload)
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
        assertTrue("push 应成功，实际=$result", result is AdbClient.AdbSyncResult.Pushed)
        assertEquals(payload.size.toLong(), (result as AdbClient.AdbSyncResult.Pushed).bytes)
    }

    /** mock 侧：读 OPEN 后回 OKAY（与 SyncStream.open 锁步）。 */
    private fun okHandshake(inp: InputStream, out: OutputStream, localId: Int, remoteId: Int) {
        // OPEN 已在调用方读过；这里只回 OKAY
        AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, remoteId, localId, ByteArray(0)))
    }

    /* ---------------- R105 收尾：A_OPEN 后 ACK 快速失败 ---------------- */

    /**
     * 纯 JVM 脚本式 fake transport：无 socket、单线程、确定性。inputStream 由
     * [input] 提供；[setReadTimeout] 全程记录，便于断言 ACK 窗口结束后读超时
     * 一定恢复为 SESSION_TIMEOUT_MS；outputStream 是 sink（只收客户端写帧，不回
     * 响应，故无需服务端线程）。connect() 与 openStream() 共用同一条流，正好模拟
     * 一条真实 TCP 连接上「握手帧读完后接着读 OPEN 响应」。
     */
    private class ScriptedTransport(private val input: InputStream) : AdbTransport {
        val timeouts = mutableListOf<Int>()
        private val sink = ByteArrayOutputStream()
        override fun open(host: String, port: Int, timeoutMs: Int) {}
        override fun inputStream(): InputStream = input
        override fun outputStream(): OutputStream = sink
        override fun setReadTimeout(ms: Int) { timeouts += ms }
        override fun isAlive(): Boolean = true
        override fun close() {}

        /** R124：把客户端写出的帧解回来，断言 CNXN 时序与 A_OPEN 服务名。 */
        fun writtenMessages(): List<AdbMessage> {
            val out = mutableListOf<AdbMessage>()
            val src = ByteArrayInputStream(sink.toByteArray())
            while (src.available() > 0) out += AdbProtocol.readMessage(src)
            return out
        }
    }

    /** 先吐出 [prefix]（握手用 CNXN 帧），之后每次 read 抛 SocketTimeoutException，
     * 模拟目标静默：熄屏/被占用/无线调试页退出。[delayMs] 防止忙等甩异常。 */
    private class SilentInputStream(prefix: ByteArray, private val delayMs: Long) : InputStream() {
        private val src = ByteArrayInputStream(prefix)
        override fun read(): Int = if (src.available() > 0) src.read() else silent()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            if (src.available() > 0) src.read(b, off, len) else silent()
        private fun silent(): Nothing {
            if (delayMs > 0) Thread.sleep(delayMs)
            throw SocketTimeoutException("fake adbd silent")
        }
    }

    private fun frameBytes(vararg msgs: AdbMessage): ByteArray {
        val bos = ByteArrayOutputStream()
        msgs.forEach { AdbProtocol.writeMessage(bos, it) }
        return bos.toByteArray()
    }

    private fun cnxnFrame() = AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0))

    @Test
    fun shellV2SelectedFromPeerFeaturesAndDecoded() {
        val localId = 1
        val packet = byteArrayOf(1, 3, 0, 0, 0) + "hi\n".toByteArray() +
            byteArrayOf(2, 4, 0, 0, 0) + "warn".toByteArray() +
            byteArrayOf(3, 1, 0, 0, 0, 7)
        val frames = frameBytes(
            AdbMessage(A_CNXN, A_VERSION, MAX_DATA, "device::features=shell_v2\u0000".toByteArray()),
            AdbMessage(A_OKAY, SERVER_ID, localId, ByteArray(0)),
            AdbMessage(A_WRTE, SERVER_ID, localId, packet.copyOfRange(0, 8)),
            AdbMessage(A_WRTE, SERVER_ID, localId, packet.copyOfRange(8, packet.size)),
            AdbMessage(A_CLSE, SERVER_ID, localId, ByteArray(0)),
        )
        val fake = ScriptedTransport(ByteArrayInputStream(frames))
        val client = AdbClient(transport = fake)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        assertEquals("hi\nwarn\n[exit code: 7]", client.openStream("echo hi"))
    }

    @Test
    fun shellOpenEofReportsFrameSequence() {
        val fake = ScriptedTransport(ByteArrayInputStream(frameBytes(cnxnFrame())))
        val client = AdbClient(transport = fake)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        val result = client.openStream("echo hi")
        assertTrue(result.startsWith("ERROR"))
        assertTrue(result.contains("帧序列：") && result.contains("TX OPEN"))
    }

    @Test
    fun openStreamFastFailsWithGuidanceWhenNoAckInWindow() {
        val fake = ScriptedTransport(SilentInputStream(frameBytes(cnxnFrame()), 10))
        // 注入 80ms 窗口：纯 JVM 单测不必真等 8s，行为与 8s 默认窗口完全一致。
        val client = AdbClient(transport = fake, ackWindowMs = 80)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))

        val start = System.currentTimeMillis()
        val result = client.openStream("echo hi")
        val elapsed = System.currentTimeMillis() - start

        assertTrue("应快速失败，实际=$result", result.startsWith("ERROR"))
        assertTrue("缺少『8 秒内未收到目标确认』", result.contains("8 秒内未收到目标确认"))
        assertTrue("缺少帧序列", result.contains("OPEN sent@") && result.contains("TX OPEN"))
        assertTrue("缺少①熄屏/锁屏引导", result.contains("熄屏"))
        assertTrue("缺少②被其它 adb 客户端占用引导", result.contains("其它 adb 客户端"))
        assertTrue("缺少③无线调试页退出引导", result.contains("无线调试"))
        assertTrue("应在 ACK 窗口内快速失败而非干等 90s，实际 ${elapsed}ms", elapsed < 3000)
        // ACK 探测窗口把读超时压到 1s；快速失败后必须恢复，不能泄漏到后续操作。
        assertTrue("ACK 窗口应设过 1s 读超时", fake.timeouts.contains(1_000))
        assertEquals("退出后读超时必须恢复为 SESSION_TIMEOUT_MS",
            AdbClient.SESSION_TIMEOUT_MS, fake.timeouts.last())
    }

    @Test
    fun openStreamReturnsOutputWhenAckArrivesInWindow() {
        val localId = 1 // connect() 不占用 localId，openStream 第一次取 1
        val frames = frameBytes(
            cnxnFrame(),
            AdbMessage(A_OKAY, SERVER_ID, localId, ByteArray(0)),
            AdbMessage(A_WRTE, localId, SERVER_ID, "hi\n".toByteArray()),
            AdbMessage(A_CLSE, localId, SERVER_ID, ByteArray(0)),
        )
        val fake = ScriptedTransport(ByteArrayInputStream(frames))
        val client = AdbClient(transport = fake, ackWindowMs = 80)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))

        // 正常 adbd 毫秒级回 OKAY → 收到 ACK 关闭快速失败窗口 → 正常返回输出。
        assertEquals("hi\n", client.openStream("echo hi"))
        assertEquals("退出后读超时必须恢复为 SESSION_TIMEOUT_MS",
            AdbClient.SESSION_TIMEOUT_MS, fake.timeouts.last())
        assertTrue("ACK 窗口应设过 1s 读超时", fake.timeouts.contains(1_000))
    }

    /* ---------------- R124：TLS 时序 / CNXN banner evidence / OPEN payload ---------------- */

    /**
     * 三方对照结论：TLS 后不重发我方 CNXN，只等 adbd 主动 CNXN（iadb-ios
     * ADBClient.swift:103-118 / cocool97 adb_message_device.rs:89-101 / AOSP
     * adb.cpp:407-424）。多发的 CNXN 会触发 adbd handle_offline → A_OPEN 静默丢弃。
     */
    @Test
    fun peerCnxnFirstSkipsClientCnxnAndWaitsForDeviceBanner() {
        val banner = "device::ro.product.name=OnePlus9;features=cmd,stat_v2\u0000"
        val frames = frameBytes(
            AdbMessage(A_CNXN, A_VERSION, MAX_DATA, banner.toByteArray()),
            AdbMessage(A_OKAY, SERVER_ID, 1, ByteArray(0)),
            AdbMessage(A_WRTE, SERVER_ID, 1, "pong\n".toByteArray()),
            AdbMessage(A_CLSE, SERVER_ID, 1, ByteArray(0)),
        )
        val fake = ScriptedTransport(ByteArrayInputStream(frames))
        val client = AdbClient(transport = fake, options = AdbConnectOptions(peerCnxnFirst = true))
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        assertFalse("peerCnxnFirst 不得发我方 CNXN（否则 adbd handle_offline）",
            fake.writtenMessages().any { it.command == A_CNXN })
        assertEquals("pong\n", client.openStream("echo pong"))
        assertTrue(client.peerBanner().contains("features=cmd,stat_v2"))
    }

    /** 默认（明文）时序仍由我方发起 CNXN，行为不回退。 */
    @Test
    fun legacyCnxnFirstStillSendsClientCnxn() {
        val fake = ScriptedTransport(ByteArrayInputStream(frameBytes(cnxnFrame())))
        val client = AdbClient(transport = fake)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        val sent = fake.writtenMessages()
        assertEquals(1, sent.count { it.command == A_CNXN })
        assertEquals(AdbFeatures.hostBanner() + "\u0000", String(sent.first { it.command == A_CNXN }.data))
    }

    /** R124 诊断：帧序列必须带 RX CNXN banner 全文 + A_OPEN 服务名全文（不止 len=）。 */
    @Test
    fun frameSequenceKeepsPeerBannerAndOpenServicePayload() {
        val banner = "device::ro.product.model=LE2120;features=shell_v2,cmd,stat_v2,ls_v2,abb\u0000"
        val fake = ScriptedTransport(
            SilentInputStream(frameBytes(AdbMessage(A_CNXN, A_VERSION, MAX_DATA, banner.toByteArray())), 5),
        )
        val client = AdbClient(transport = fake, ackWindowMs = 60)
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        val result = client.openStream("getprop ro.build.version.release")
        assertTrue(result.startsWith("ERROR"))
        assertTrue("缺 RX CNXN 全文: $result", result.contains("features=shell_v2,cmd,stat_v2,ls_v2,abb"))
        assertTrue("缺 A_OPEN 服务名: $result", result.contains("shell,v2:getprop ro.build.version.release"))
    }

    /** rawShellService 开关把服务串切成 iadb-ios 的 `shell,v2,raw:` 形式。 */
    @Test
    fun rawShellServiceOptionChangesOpenPayload() {
        val stdout = byteArrayOf(1, 3, 0, 0, 0) + "ok\n".toByteArray() // shell v2 stdout chunk
        val frames = frameBytes(
            AdbMessage(A_CNXN, A_VERSION, MAX_DATA, "device::features=shell_v2\u0000".toByteArray()),
            AdbMessage(A_OKAY, SERVER_ID, 1, ByteArray(0)),
            AdbMessage(A_WRTE, SERVER_ID, 1, stdout),
            AdbMessage(A_CLSE, SERVER_ID, 1, ByteArray(0)),
        )
        val fake = ScriptedTransport(ByteArrayInputStream(frames))
        val client = AdbClient(transport = fake, options = AdbConnectOptions(rawShellService = true))
        assertTrue(client.connect("127.0.0.1", 5555).startsWith("OK"))
        assertEquals("ok\n", client.openStream("echo ok"))
        val open = fake.writtenMessages().first { it.command == A_OPEN }
        assertEquals("shell,v2,raw:echo ok\u0000", String(open.data))
    }
}

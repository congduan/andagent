package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
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
}

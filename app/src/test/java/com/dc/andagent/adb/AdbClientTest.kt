package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** R100 ①：确定性单线程 mock ServerSocket 对端，验证完整握手 + shell 流。 */
class AdbClientTest {

    private companion object {
        const val SERVER_ID = 2
    }

    @Test
    fun fullHandshakeAndShellEcho() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val server = ServerSocket(0)
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
                    // 3) 客户端签名 → JDK 公钥验签（可证伪假设的往返验证）
                    val sig = AdbProtocol.readMessage(inp)
                    assertEquals(A_AUTH, sig.command)
                    assertEquals(ADB_AUTH_SIGNATURE, sig.arg0)
                    assertTrue(
                        "客户端签名必须能被 JDK 公钥验证",
                        AdbAuth.verifyToken(kp.public, token, sig.data),
                    )
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
        val server = ServerSocket(0)
        val thread = Thread {
            try {
                server.accept().use { /* 接受后立刻关闭，不发任何帧 */ }
            } catch (_: Throwable) {
            }
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
        val server = ServerSocket(0)
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
            } catch (e: Throwable) {
                serverError = e
            }
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
}

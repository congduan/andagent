package com.dc.andagent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket

/**
 * R100 ③：AdbScrcpy 纯协议函数 + AdbClient.openDuplex 裸流（照 AdbClientTest 的 mock
 * ServerSocket harness，双端共享小端 AdbProtocol 故天然一致）的 JVM 单测。
 */
class AdbScrcpyTest {

    // ---- 大端字节构造（对齐 scrcpy server 的 DataOutputStream 写头） ----
    private fun putIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun putLongBE(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * (7 - i))).toByte()
    }

    private fun videoHeader(codec: Int = AdbScrcpy.H264_CODEC, w: Int = 1080, h: Int = 1920): ByteArray {
        val b = ByteArray(AdbScrcpy.VIDEO_HEADER_SIZE)
        "test-device".toByteArray().copyInto(b, 0)
        putIntBE(b, 64, codec)
        putIntBE(b, 68, w)
        putIntBE(b, 72, h)
        return b
    }

    private fun packetHead(flags: Long, size: Int): ByteArray {
        val b = ByteArray(AdbScrcpy.PACKET_HEADER_SIZE)
        putLongBE(b, 0, flags)
        putIntBE(b, 8, size)
        return b
    }

    // ---- parseVideoHeader ----
    @Test
    fun parseVideoHeader_parsesValidHeader() {
        val h = AdbScrcpy.parseVideoHeader(videoHeader())
        assertNotNull(h)
        assertEquals(AdbScrcpy.H264_CODEC, h!!.codec)
        assertEquals(1080, h.width)
        assertEquals(1920, h.height)
        assertEquals("test-device", h.name)
    }

    @Test
    fun parseVideoHeader_rejectsBadCodec() {
        assertNull(AdbScrcpy.parseVideoHeader(videoHeader(codec = 0x11223344)))
    }

    @Test
    fun parseVideoHeader_rejectsBadSize() {
        assertNull(AdbScrcpy.parseVideoHeader(videoHeader(w = 0)))
        assertNull(AdbScrcpy.parseVideoHeader(videoHeader(h = 4097)))
        assertNull(AdbScrcpy.parseVideoHeader(videoHeader(w = -1)))
    }

    @Test
    fun parseVideoHeader_rejectsShortBytes() {
        assertNull(AdbScrcpy.parseVideoHeader(ByteArray(10)))
        assertNull(AdbScrcpy.parseVideoHeader(ByteArray(0)))
    }

    // ---- 帧解析：config 帧 / keyframe 位 / pts 提取 / size 越界拒绝 ----
    @Test
    fun parsePacketFrame_extractsConfigKeyFrameAndPts() {
        val pts = 123_456_789L
        // bit63 = config，bit62 = keyFrame，低 62 位 = pts
        val flags = (1L shl 63) or (1L shl 62) or pts
        val payload = ByteArray(5) { it.toByte() }
        val p = AdbScrcpy.parsePacketFrame(packetHead(flags, payload.size), payload)
        assertNotNull(p)
        assertEquals(pts, p!!.pts)
        assertTrue("bit63 应为 config", p.config)
        assertTrue("bit62 应为 keyFrame", p.keyFrame)
        assertArrayEquals(payload, p.bytes)
    }

    @Test
    fun parsePacketFrame_normalFrameHasNoConfigNoKeyFrame() {
        val pts = 42L
        val p = AdbScrcpy.parsePacketFrame(packetHead(pts, 3), ByteArray(3))
        assertNotNull(p)
        assertEquals(42L, p!!.pts)
        assertFalse(p.config)
        assertFalse(p.keyFrame)
    }

    @Test
    fun parsePacketFrame_configOnlyFrame() {
        // 仅 bit63：config=true，keyFrame=false，pts 取低 62 位
        val flags = (1L shl 63) or 7L
        val p = AdbScrcpy.parsePacketFrame(packetHead(flags, 2), ByteArray(2))
        assertNotNull(p)
        assertTrue(p!!.config)
        assertFalse(p.keyFrame)
        assertEquals(7L, p.pts)
    }

    @Test
    fun parsePacketFrame_rejectsSizeOutOfRangeOrMismatch() {
        val payload = ByteArray(1)
        assertNull("size=0 拒绝", AdbScrcpy.parsePacketFrame(packetHead(0L, 0), payload))
        assertNull("size 负拒绝", AdbScrcpy.parsePacketFrame(packetHead(0L, -1), payload))
        assertNull("size 超上限拒绝", AdbScrcpy.parsePacketFrame(packetHead(0L, AdbScrcpy.MAX_PACKET_BYTES + 1), payload))
        assertNull("payload 长度不符拒绝", AdbScrcpy.parsePacketFrame(packetHead(0L, 5), ByteArray(3)))
        assertNull("帧头不足拒绝", AdbScrcpy.parsePacketFrame(ByteArray(4), ByteArray(4)))
    }

    @Test
    fun framePayloadSize_validatesRange() {
        assertEquals(5, AdbScrcpy.framePayloadSize(packetHead(0L, 5)))
        assertNull(AdbScrcpy.framePayloadSize(packetHead(0L, 0)))
        assertNull(AdbScrcpy.framePayloadSize(packetHead(0L, AdbScrcpy.MAX_PACKET_BYTES + 1)))
        assertNull(AdbScrcpy.framePayloadSize(ByteArray(4)))
    }

    // ---- buildServerCommand 参数拼装 ----
    @Test
    fun buildServerCommand_assemblesParams() {
        val cmd = AdbScrcpy.buildServerCommand("deadbeef", 1280, 4_000_000)
        assertTrue(cmd.contains("CLASSPATH=/data/local/tmp/andagent-scrcpy-server-v2.7.jar"))
        assertTrue(cmd.contains("com.genymobile.scrcpy.Server 2.7"))
        assertTrue(cmd.contains("scid=deadbeef"))
        assertTrue(cmd.contains("log_level=info"))
        assertTrue(cmd.contains("audio=false"))
        assertTrue(cmd.contains("control=false"))
        assertTrue(cmd.contains("video_codec=h264"))
        assertTrue(cmd.contains("max_size=1280"))
        assertTrue(cmd.contains("video_bit_rate=4000000"))
        assertTrue(cmd.contains("tunnel_forward=true"))
        assertTrue(cmd.contains("send_dummy_byte=false"))
        assertTrue(cmd.contains(">/data/local/tmp/andagent_scrcpy.log 2>&1 &"))
    }

    @Test
    fun buildServerCommand_clampsSizeAndBitrate() {
        val low = AdbScrcpy.buildServerCommand("0000000a", 0, 1)
        assertTrue(low.contains("max_size=256"))
        assertTrue(low.contains("video_bit_rate=500000"))
        val high = AdbScrcpy.buildServerCommand("0000000a", 9999, 99_999_999)
        assertTrue(high.contains("max_size=1920"))
        assertTrue(high.contains("video_bit_rate=20000000"))
    }

    @Test
    fun mirrorServerCommandIncludesBoundedFrameRateAndVideoOnly() {
        val cmd = AdbScrcpy.buildServerCommand("deadbeef", 800, 4_000_000, 15)
        assertTrue(cmd.contains("max_size=800"))
        assertTrue(cmd.contains("video_bit_rate=4000000"))
        assertTrue(cmd.contains("max_fps=15"))
        assertTrue(cmd.contains("audio=false control=false"))
        assertTrue(cmd.contains("tunnel_forward=true"))
        assertTrue(AdbScrcpy.buildServerCommand("deadbeef", 800, 4_000_000, 100).contains("max_fps=60"))
    }

    // ---- scid 格式 / socket 名单点 ----
    @Test
    fun formatScid_is8LowercaseHex() {
        assertTrue(Regex("[0-9a-f]{8}").matches(AdbScrcpy.newScid()))
        assertEquals("0000000a", AdbScrcpy.formatScid(10))
        assertEquals("00000000", AdbScrcpy.formatScid(0))
        assertEquals("7fffffff", AdbScrcpy.formatScid(Int.MAX_VALUE))
    }

    @Test
    fun buildSocketName_singlePoint() {
        assertEquals("scrcpy_deadbeef", AdbScrcpy.buildSocketName("deadbeef"))
        assertEquals("localabstract:scrcpy_deadbeef", AdbScrcpy.buildSocketService("deadbeef"))
    }

    @Test
    fun parseSdkInt_parsesGetpropOutput() {
        assertEquals(33, AdbScrcpy.parseSdkInt("33\n"))
        assertEquals(21, AdbScrcpy.parseSdkInt(" 21 "))
        assertNull(AdbScrcpy.parseSdkInt("ERROR: 连接失败"))
        assertNull(AdbScrcpy.parseSdkInt(""))
    }

    // ---- AdbClient.openDuplex：happy path / OKAY 匹配 / readN / CLSE 关闭 ----
    @Test
    fun openDuplex_readsHeaderAndClosesCleanly() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    AdbProtocol.readMessage(inp) // CNXN
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp)
                    assertEquals(A_OPEN, open.command)
                    // 断言 OPEN payload 字节（NUL 结尾）正确。
                    assertEquals("localabstract:scrcpy_deadbeef\u0000", String(open.data))
                    val L = open.arg0; val R = 2
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, R, L, ByteArray(0)))
                    // 76B 视频头（AOSP：WRTE arg0=对端 id R、arg1=本地 id L）
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, videoHeader()))
                    val ack = AdbProtocol.readMessage(inp)
                    assertEquals(A_OKAY, ack.command) // WRTE 必须回 OKAY
                    val clse = AdbProtocol.readMessage(inp)
                    assertEquals(A_CLSE, clse.command) // close() 发 CLSE
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        assertTrue(client.connect("127.0.0.1", server.localPort).startsWith("OK"))
        val stream = client.openDuplex("localabstract:scrcpy_deadbeef")
        assertNotNull(stream)
        val header = stream!!.readN(AdbScrcpy.VIDEO_HEADER_SIZE)
        assertEquals(AdbScrcpy.H264_CODEC, AdbScrcpy.parseVideoHeader(header)!!.codec)
        stream.close()
        stream.close() // 幂等：重复调用不抛、不重复发 CLSE
        thread.join(5000)
        client.close()
        server.close()
        assertNull("服务端异常: $serverError", serverError)
    }

    @Test
    fun openDuplex_serviceRejectedReturnsNullWithoutThrowing() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    AdbProtocol.readMessage(inp) // CNXN
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp)
                    assertEquals(A_OPEN, open.command)
                    // abstract socket 未就绪 → adbd 回 CLSE(arg0=0) 拒绝
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, 0, open.arg0, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        assertTrue(client.connect("127.0.0.1", server.localPort).startsWith("OK"))
        val r = runCatching { client.openDuplex("localabstract:scrcpy_deadbeef") }
        assertTrue("openDuplex 不得抛异常", r.isSuccess)
        assertNull("服务被拒应返回 null", r.getOrNull())
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
    }

    @Test
    fun openDuplex_withoutConnectReturnsNull() {
        assertNull(AdbClient().openDuplex("localabstract:scrcpy_x"))
    }

    @Test
    fun openDuplex_readNThrowsIOExceptionWhenPeerClosesEarly() {
        val server = ServerSocket(0).apply { soTimeout = 10_000 }
        var serverError: Throwable? = null
        val thread = Thread {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    AdbProtocol.readMessage(inp) // CNXN
                    AdbProtocol.writeMessage(out, AdbMessage(A_CNXN, A_VERSION, MAX_DATA, ByteArray(0)))
                    val open = AdbProtocol.readMessage(inp)
                    val L = open.arg0; val R = 2
                    AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, R, L, ByteArray(0)))
                    // 只发 10 字节（不足 76B）再 CLSE → readN 缓冲不足抛 IOException
                    AdbProtocol.writeMessage(out, AdbMessage(A_WRTE, R, L, ByteArray(10)))
                    assertEquals(A_OKAY, AdbProtocol.readMessage(inp).command)
                    AdbProtocol.writeMessage(out, AdbMessage(A_CLSE, R, L, ByteArray(0)))
                }
            } catch (e: Throwable) { serverError = e }
        }
        thread.start()
        val client = AdbClient()
        assertTrue(client.connect("127.0.0.1", server.localPort).startsWith("OK"))
        val stream = client.openDuplex("localabstract:scrcpy_deadbeef")
        assertNotNull(stream)
        val r = runCatching { stream!!.readN(AdbScrcpy.VIDEO_HEADER_SIZE) }
        assertTrue("readN 遇 CLSE 且缓冲不足应抛 IOException", r.isFailure)
        assertTrue(r.exceptionOrNull() is IOException)
        client.close()
        thread.join(5000)
        server.close()
        assertNull("服务端异常: $serverError", serverError)
    }
}

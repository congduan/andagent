package com.dc.andagent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** R100 ①：帧编解码纯 JVM 断言（mock 对端共享同一实现，天然一致）。 */
class AdbProtocolTest {

    private fun roundtrip(msg: AdbMessage): AdbMessage {
        val bytes = AdbProtocol.encode(msg)
        assertEquals(HEADER_SIZE + msg.data.size, bytes.size)
        return AdbProtocol.readMessage(ByteArrayInputStream(bytes))
    }

    @Test
    fun headerEncodeDecodeRoundtrip() {
        val msg = AdbMessage(A_WRTE, 7, 9, "hello adb".toByteArray())
        val decoded = roundtrip(msg)
        assertEquals(msg.command, decoded.command)
        assertEquals(msg.arg0, decoded.arg0)
        assertEquals(msg.arg1, decoded.arg1)
        assertArrayEquals(msg.data, decoded.data)
    }

    @Test
    fun emptyDataRoundtrip() {
        val decoded = roundtrip(AdbMessage(A_OKAY, 1, 2, ByteArray(0)))
        assertEquals(A_OKAY, decoded.command)
        assertEquals(1, decoded.arg0)
        assertEquals(2, decoded.arg1)
        assertEquals(0, decoded.data.size)
    }

    @Test
    fun crcMismatchRejected() {
        val bytes = AdbProtocol.encode(AdbMessage(A_WRTE, 1, 2, "payload".toByteArray()))
        bytes[HEADER_SIZE] = (bytes[HEADER_SIZE].toInt() xor 0x01).toByte() // 篡改 1 字节 data
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(bytes)) }
        assertTrue("crc 错误必须被拒绝", r.isFailure)
        assertTrue(r.exceptionOrNull() is AdbProtocolException)
    }

    @Test
    fun badMagicRejected() {
        val bytes = AdbProtocol.encode(AdbMessage(A_CNXN, 1, 2, ByteArray(0)))
        bytes[20] = (bytes[20].toInt() xor 0x01).toByte() // 篡改 magic 字段
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(bytes)) }
        assertTrue("magic 错误必须被拒绝", r.isFailure)
        assertTrue(r.exceptionOrNull() is AdbProtocolException)
    }

    @Test
    fun oversizedLengthRejected() {
        // 手工构造头部：command=A_CNXN、magic 合法、data_length=256MB+1，无 body。
        val header = ByteArray(HEADER_SIZE)
        fun putU32(off: Int, v: Long) {
            header[off] = (v ushr 24).toByte()
            header[off + 1] = (v ushr 16).toByte()
            header[off + 2] = (v ushr 8).toByte()
            header[off + 3] = v.toByte()
        }
        putU32(0, A_CNXN.toLong() and 0xffffffffL)
        putU32(4, 0)
        putU32(8, 0)
        putU32(12, MAX_DATA_LENGTH + 1)
        putU32(16, 0) // crc 随意：长度校验在读 body 之前
        putU32(20, (A_CNXN xor -1).toLong() and 0xffffffffL)
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(header)) }
        assertTrue("超 256MB 必须被拒绝", r.isFailure)
        assertTrue(r.exceptionOrNull()?.message?.contains("too large") == true)
    }

    @Test
    fun truncatedHeaderRejected() {
        val bytes = AdbProtocol.encode(AdbMessage(A_WRTE, 1, 2, "xyz".toByteArray()))
        val short = bytes.copyOf(10) // 不足 24 字节头
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(short)) }
        assertTrue(r.isFailure)
    }

    @Test
    fun truncatedPayloadRejected() {
        val bytes = AdbProtocol.encode(AdbMessage(A_WRTE, 1, 2, "abcdefgh".toByteArray()))
        val short = bytes.copyOf(bytes.size - 3) // body 被截断
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(short)) }
        assertTrue(r.isFailure)
    }

    @Test
    fun writeMessageFlushesAndStreamsConsecutiveFrames() {
        val out = ByteArrayOutputStream()
        AdbProtocol.writeMessage(out, AdbMessage(A_OPEN, 1, 0, "shell:id\u0000".toByteArray()))
        AdbProtocol.writeMessage(out, AdbMessage(A_OKAY, 1, 2, ByteArray(0)))
        val inp = ByteArrayInputStream(out.toByteArray())
        val m1 = AdbProtocol.readMessage(inp)
        val m2 = AdbProtocol.readMessage(inp)
        assertEquals(A_OPEN, m1.command)
        assertEquals("shell:id\u0000", String(m1.data))
        assertEquals(A_OKAY, m2.command)
    }
}

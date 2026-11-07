package com.dc.andagent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** R100 ②：小端帧编解码纯 JVM 断言（线上字节序对齐 AOSP 真实 adbd）。 */
class AdbProtocolTest {

    private fun roundtrip(msg: AdbMessage): AdbMessage {
        val bytes = AdbProtocol.encode(msg)
        assertEquals(HEADER_SIZE + msg.data.size, bytes.size)
        return AdbProtocol.readMessage(ByteArrayInputStream(bytes))
    }

    @Test
    fun headerIsLittleEndianAndCommandIsAsciiOnWire() {
        // 命令字 0x4e584e43 小端写出 = "CNXN"；arg0=1 小端 = 01 00 00 00。
        val bytes = AdbProtocol.encode(AdbMessage(A_CNXN, 1, 2, byteArrayOf(1, 2, 3, 0xff.toByte())))
        assertArrayEquals("CNXN".toByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt() and 0xff)
        assertEquals(0, bytes[5].toInt() and 0xff)
        assertEquals(2, bytes[8].toInt() and 0xff)
        assertEquals(4, bytes[12].toInt() and 0xff) // data_length 小端 = 4
        // data_check = 1+2+3+255 = 261 = 0x105 → 05 01 00 00（累加和而非 CRC32）
        assertEquals(0x05, bytes[16].toInt() and 0xff)
        assertEquals(0x01, bytes[17].toInt() and 0xff)
    }

    @Test
    fun littleEndianCommandConstantsMatchAosp() {
        assertEquals(0x4e584e43, A_CNXN)
        assertEquals(0x48545541, A_AUTH)
        assertEquals(0x4e45504f, A_OPEN)
        assertEquals(0x59414b4f, A_OKAY)
        assertEquals(0x45545257, A_WRTE)
        assertEquals(0x45534c43, A_CLSE)
        assertEquals(0x434e5953, A_SYNC)
        assertEquals(1, ADB_AUTH_TOKEN)
        assertEquals(2, ADB_AUTH_SIGNATURE)
        assertEquals(3, ADB_AUTH_RSAPUBLICKEY)
        assertEquals(0x01000001, A_VERSION)
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
    fun dataCheckMismatchRejected() {
        val bytes = AdbProtocol.encode(AdbMessage(A_WRTE, 1, 2, "payload".toByteArray()))
        bytes[HEADER_SIZE] = (bytes[HEADER_SIZE].toInt() xor 0x01).toByte() // 篡改 1 字节 data
        val r = runCatching { AdbProtocol.readMessage(ByteArrayInputStream(bytes)) }
        assertTrue("data_check 错误必须被拒绝", r.isFailure)
        assertTrue(r.exceptionOrNull() is AdbProtocolException)
    }

    @Test
    fun zeroDataCheckAcceptedForModernPeer() {
        // 版本 ≥0x01000001 的对端忽略 data_check，发来的帧该字段为 0：必须放行。
        val bytes = AdbProtocol.encode(AdbMessage(A_WRTE, 1, 2, "payload".toByteArray()))
        bytes[16] = 0; bytes[17] = 0; bytes[18] = 0; bytes[19] = 0
        val decoded = AdbProtocol.readMessage(ByteArrayInputStream(bytes))
        assertArrayEquals("payload".toByteArray(), decoded.data)
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
        // 手工构造【小端】头部：command=A_CNXN、magic 合法、data_length=256MB+1，无 body。
        val header = ByteArray(HEADER_SIZE)
        fun putU32LE(off: Int, v: Long) {
            header[off] = v.toByte()
            header[off + 1] = (v ushr 8).toByte()
            header[off + 2] = (v ushr 16).toByte()
            header[off + 3] = (v ushr 24).toByte()
        }
        putU32LE(0, A_CNXN.toLong() and 0xffffffffL)
        putU32LE(4, 0)
        putU32LE(8, 0)
        putU32LE(12, MAX_DATA_LENGTH + 1)
        putU32LE(16, 0) // data_check=0：长度校验在读 body 之前
        putU32LE(20, (A_CNXN xor -1).toLong() and 0xffffffffL)
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

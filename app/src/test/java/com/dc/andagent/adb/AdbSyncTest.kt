package com.dc.andagent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R100 ②：SYNC 子协议编解码纯 JVM 断言（id + 小端长度 + data）。 */
class AdbSyncTest {

    @Test
    fun genericPacketIsIdPlusLittleEndianLengthPlusData() {
        val bytes = AdbSync.encode("DATA", byteArrayOf(9, 8, 7))
        assertEquals(11, bytes.size)
        assertArrayEquals("DATA".toByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(3, bytes[4].toInt() and 0xff) // len 小端 = 3
        assertEquals(0, bytes[5].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(9, 8, 7), bytes.copyOfRange(8, 11))
    }

    @Test
    fun sendRequestCarriesPathAndDecimalMode() {
        val bytes = AdbSync.encodeSend("/data/local/tmp/a.png", 420)
        assertEquals("SEND", String(bytes.copyOfRange(0, 4)))
        assertEquals(
            "/data/local/tmp/a.png,420",
            String(bytes.copyOfRange(8, bytes.size)),
        )
    }

    @Test
    fun recvRequestCarriesPath() {
        val bytes = AdbSync.encodeRecv("/sdcard/x.bin")
        assertEquals("RECV", String(bytes.copyOfRange(0, 4)))
        assertEquals("/sdcard/x.bin", String(bytes.copyOfRange(8, bytes.size)))
    }

    @Test
    fun donePacketCarriesMtimeAsLengthFieldWithoutData() {
        val bytes = AdbSync.encodeDone(0x01020304)
        assertEquals(8, bytes.size)
        assertEquals("DONE", String(bytes.copyOfRange(0, 4)))
        assertEquals(0x04, bytes[4].toInt() and 0xff)
        assertEquals(0x03, bytes[5].toInt() and 0xff)
        assertEquals(0x02, bytes[6].toInt() and 0xff)
        assertEquals(0x01, bytes[7].toInt() and 0xff)
    }

    @Test
    fun readerRoundtripsConsecutivePackets() {
        val reader = SyncPacketReader()
        reader.feed(AdbSync.encode("RECV", "/a".toByteArray()))
        reader.feed(AdbSync.encode("DATA", byteArrayOf(1, 2, 3)))
        reader.feed(AdbSync.encode("DONE", ByteArray(0)))
        val a = reader.poll()!!
        val b = reader.poll()!!
        val c = reader.poll()!!
        assertEquals("RECV", a.id)
        assertEquals("/a", String(a.data))
        assertEquals("DATA", b.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), b.data)
        assertEquals("DONE", c.id)
        assertEquals(0, c.data.size)
        assertNull(reader.poll())
    }

    @Test
    fun readerAccumulatesAcrossWrteFrameBoundaries() {
        val packet = AdbSync.encode("DATA", "hello sync".toByteArray())
        val reader = SyncPacketReader()
        // 逐字节喂入（模拟一个 SYNC 包被切成多个 WRTE 帧）
        for (i in packet.indices) {
            reader.feed(byteArrayOf(packet[i]))
            if (i < packet.size - 1) assertNull("未凑齐整包前不应返回", reader.poll())
        }
        val result = reader.poll()!!
        assertEquals("DATA", result.id)
        assertEquals("hello sync", String(result.data))
        assertNull(reader.poll())
    }

    @Test
    fun readerHandlesSeveralPacketsInOneFeed() {
        val a = AdbSync.encode("DATA", "one".toByteArray())
        val b = AdbSync.encode("DATA", "two".toByteArray())
        val reader = SyncPacketReader()
        reader.feed(a + b)
        assertEquals("one", String(reader.poll()!!.data))
        assertEquals("two", String(reader.poll()!!.data))
        assertNull(reader.poll())
    }

    @Test
    fun readerHandlesSendDoneWithMtimeAsValue() {
        // SEND 收尾 DONE 的 4 字节字段是 mtime（不是长度），固定 8 字节无 body。
        val reader = SyncPacketReader()
        reader.feed(AdbSync.encodeDone(0x5f5e0f00))
        val p = reader.poll()!!
        assertEquals("DONE", p.id)
        assertEquals(0, p.data.size)
        assertNull(reader.poll())
    }

    @Test
    fun readerRejectsInsaneLength() {
        val reader = SyncPacketReader()
        val header = ByteArray(8)
        "DATA".toByteArray().copyInto(header, 0)
        // u32 小端 = 2 MiB > SYNC_MAX_PACKET
        header[4] = 0; header[5] = 0; header[6] = 0x20; header[7] = 0
        reader.feed(header)
        val r = runCatching { reader.poll() }
        assertTrue("超上限 SYNC 包必须被拒绝", r.isFailure)
        assertTrue(r.exceptionOrNull() is AdbProtocolException)
    }

    @Test
    fun failMessageStripsTrailingNul() {
        val p = SyncPacket(AdbSync.ID_FAIL, "no such file\u0000".toByteArray())
        assertEquals("no such file", AdbSync.failMessage(p))
    }
}

package com.dc.andagent.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R74 收口刀：BLE GATT / SPP / USB bulk 纯函数编解码测试（纯 JVM，无 Android 框架调用）。
 * 覆盖 GATT properties/permissions 位解码、UUID 展开缩短、SPP UUID 校验、
 * CCCD 0x0001/0x0000 构造、hex 编解码、垃圾输入安全返 null、USB 控制字段解析。
 */
class BleGattSppCodecTest {

    /* ------------------------- BleUuids (复用基建) --------------------- */

    @Test
    fun expandsAndShortensUuid() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", BleUuids.expand("180D"))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", BleUuids.expand("0x180D"))
        assertEquals("180d", BleUuids.short("0000180d-0000-1000-8000-00805f9b34fb"))
        // 非 SIG base 的 128 位 UUID 保持原样
        assertEquals("12345678-1234-5678-1234-56789abcdef0", BleUuids.short("12345678-1234-5678-1234-56789abcdef0"))
    }

    @Test
    fun rejectsMalformedUuidExpansion() {
        assertNull(BleUuids.expand(null))
        assertNull(BleUuids.expand(""))
        assertNull(BleUuids.expand("zzzz"))
        assertNull(BleUuids.expand("123"))
        assertNull(BleUuids.expand("0000180d-0000-1000-8000-00805f9b34f")) // 31 字符
    }

    /* ---------------------------- GattCodec ---------------------------- */

    @Test
    fun decodesGattProperties() {
        assertEquals(listOf("read", "notify"), GattCodec.decodeProperties(0x12))  // READ|NOTIFY
        assertEquals(listOf("read", "write"), GattCodec.decodeProperties(0x0A))   // READ|WRITE
        assertEquals(
            listOf("broadcast", "read", "write_no_response", "write", "notify", "indicate", "signed_write", "extended_props"),
            GattCodec.decodeProperties(0xFF),
        )
    }

    @Test
    fun decodesGattPermissions() {
        assertEquals(listOf("read", "write"), GattCodec.decodePermissions(0x11))  // READ|WRITE
        assertTrue(GattCodec.decodePermissions(0x01).contains("read"))
        assertTrue(GattCodec.decodePermissions(0x10).contains("write"))
    }

    @Test
    fun propertyAndPermissionDecodeIgnoreUnknownBits() {
        // 未知高位不崩溃、不臆造名字
        assertEquals(emptyList<String>(), GattCodec.decodeProperties(0))
        assertEquals(emptyList<String>(), GattCodec.decodeProperties(0x100))
        assertEquals(emptyList<String>(), GattCodec.decodePermissions(0))
        assertEquals(emptyList<String>(), GattCodec.decodePermissions(0x200))
    }

    /* ---------------------------- CccdCodec ---------------------------- */

    @Test
    fun buildsCccdEnableDisableIndicate() {
        assertArrayEquals(byteArrayOf(0x01, 0x00), CccdCodec.enable())   // 0x0001 LE
        assertArrayEquals(byteArrayOf(0x00, 0x00), CccdCodec.disable())  // 0x0000 LE
        assertArrayEquals(byteArrayOf(0x02, 0x00), CccdCodec.indicate()) // 0x0002 LE
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", CccdCodec.UUID)
    }

    /* ----------------------------- SppCodec ---------------------------- */

    @Test
    fun sppDefaultUuidRoundTrips() {
        assertEquals(SppCodec.DEFAULT_UUID, SppCodec.parseUuid(SppCodec.DEFAULT_UUID))
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", SppCodec.DEFAULT_UUID)
    }

    @Test
    fun sppUuidAcceptsShortForms() {
        assertEquals(SppCodec.DEFAULT_UUID, SppCodec.parseUuid("1101"))
        assertEquals(SppCodec.DEFAULT_UUID, SppCodec.parseUuid("0x1101"))
        assertEquals("12345678-1234-5678-1234-56789abcdef0", SppCodec.parseUuid("12345678-1234-5678-1234-56789abcdef0"))
    }

    @Test
    fun sppUuidRejectsGarbage() {
        assertNull(SppCodec.parseUuid(null))
        assertNull(SppCodec.parseUuid(""))
        assertNull(SppCodec.parseUuid("xyz"))
        assertNull(SppCodec.parseUuid("123"))
        assertNull(SppCodec.parseUuid("0x"))
    }

    /* --------------------------- HexValueCodec ------------------------- */

    @Test
    fun hexEncodeDecodeRoundTrip() {
        val bytes = HexValueCodec.parseHex("0x01-02 AB")!!
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0xAB.toByte()), bytes)
        assertEquals("0102ab", HexValueCodec.hex(bytes))
        assertEquals("deadbeef", HexValueCodec.hex(HexValueCodec.parseHex("de:ad:be:ef")!!))
    }

    @Test
    fun hexParseRejectsGarbageSafely() {
        assertNull(HexValueCodec.parseHex(null))
        assertNull(HexValueCodec.parseHex(""))
        assertNull(HexValueCodec.parseHex("zz"))
        assertNull(HexValueCodec.parseHex("abc"))       // 奇数长度
        assertNull(HexValueCodec.parseHex("0x1g"))      // 非十六进制字符
    }

    @Test
    fun valueViewsProvideHexUtf8Base64() {
        val v = HexValueCodec.views("Hi".toByteArray(Charsets.UTF_8))
        assertEquals("4869", v.hex)
        assertEquals("Hi", v.utf8)
        assertEquals("SGk=", v.base64)
        assertEquals(2, v.length)
        assertEquals("", HexValueCodec.views(ByteArray(0)).utf8)
    }

    @Test
    fun parseIntFieldHandlesDecimalAndHex() {
        assertEquals(192, HexValueCodec.parseIntField("192"))
        assertEquals(192, HexValueCodec.parseIntField("0xC0"))
        assertEquals(192, HexValueCodec.parseIntField("c0"))
        assertEquals(0, HexValueCodec.parseIntField("0"))
        assertNull(HexValueCodec.parseIntField(null))
        assertNull(HexValueCodec.parseIntField(""))
        assertNull(HexValueCodec.parseIntField("z"))
    }

    @Test
    fun detectsControlTransferDirection() {
        assertTrue(HexValueCodec.isControlIn(0xC0))
        assertTrue(HexValueCodec.isControlIn(0x80))
        assertTrue(!HexValueCodec.isControlIn(0x40))
        assertTrue(!HexValueCodec.isControlIn(0x00))
    }
}

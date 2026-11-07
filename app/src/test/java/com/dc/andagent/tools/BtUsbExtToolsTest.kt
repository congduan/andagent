package com.dc.andagent.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R86: pure-logic coverage for the Bluetooth/USB extension helpers — no Android
 * framework calls, no permissions, no device.
 */
class BtUsbExtToolsTest {

    /* ------------------------------ BleUuids ---------------------------- */

    @Test
    fun expands16BitUuidToSigBase() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", BleUuids.expand("180D"))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", BleUuids.expand("0x180D"))
    }

    @Test
    fun expands32And128BitUuids() {
        assertEquals("1234abcd-0000-1000-8000-00805f9b34fb", BleUuids.expand("1234ABCD"))
        assertEquals(
            "0000180d-0000-1000-8000-00805f9b34fb",
            BleUuids.expand("0000180d00001000800000805f9b34fb"),
        )
    }

    @Test
    fun rejectsMalformedUuids() {
        assertNull(BleUuids.expand(null))
        assertNull(BleUuids.expand(""))
        assertNull(BleUuids.expand("zzzz"))
        assertNull(BleUuids.expand("123"))
        assertNull(BleUuids.expand("0000180d-0000-1000-8000-00805f9b34f")) // 判断长度
    }

    @Test
    fun shortUuidKeepsOnlySigPart() {
        assertEquals("180d", BleUuids.short("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals("12345678-1234-5678-1234-56789abcdef0", BleUuids.short("12345678-1234-5678-1234-56789abcdef0"))
    }

    /* ----------------------------- BleAdvCodec -------------------------- */

    @Test
    fun parsesServiceUuidList() {
        val uuids = BleAdvCodec.parseServiceUuids("180D, 0000180f-0000-1000-8000-00805f9b34fb,180D")
        assertEquals(2, uuids.size)
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", uuids[0])
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", uuids[1])
        assertTrue(BleAdvCodec.parseServiceUuids(null).isEmpty())
    }

    @Test
    fun rejectsBadServiceUuid() {
        try {
            BleAdvCodec.parseServiceUuids("180D,not-a-uuid")
            fail("应当抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("UUID"))
        }
    }

    @Test
    fun parsesManufacturerAndServiceData() {
        val (id, data) = BleAdvCodec.parseManufacturer("0x004C:0215AA")!!
        assertEquals(0x4C, id)
        assertArrayEquals(byteArrayOf(0x02, 0x15, 0xAA.toByte()), data)

        val (uuid, serviceData) = BleAdvCodec.parseServiceData("180D:0102")!!
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", uuid)
        assertArrayEquals(byteArrayOf(0x01, 0x02), serviceData)

        assertNull(BleAdvCodec.parseManufacturer(null))
        assertNull(BleAdvCodec.parseServiceData(" "))
    }

    @Test
    fun parsesLenientHex() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x40, 0x7F), BleAdvCodec.parseHex("90:40:7F"))
        assertArrayEquals(byteArrayOf(0x01, 0x02), BleAdvCodec.parseHex("0x01-0x02"))
        assertNull(BleAdvCodec.parseHex("904"))
        assertNull(BleAdvCodec.parseHex("zz"))
        assertNull(BleAdvCodec.parseHex(""))
    }

    @Test
    fun accountsLegacyPayloadBudget() {
        val empty = BleAdvCodec.Payload()
        assertEquals(6, BleAdvCodec.payloadBytes(empty, includeTxPower = true))
        assertEquals(3, BleAdvCodec.payloadBytes(empty, includeTxPower = false))

        val withUuid = BleAdvCodec.Payload(
            serviceUuids = listOf("0000180d-0000-1000-8000-00805f9b34fb"),
            manufacturerId = 0x4C,
            manufacturerData = byteArrayOf(1, 2),
        )
        assertEquals(3 + 3 + 4 + 6, BleAdvCodec.payloadBytes(withUuid, includeTxPower = true))
        assertNull(BleAdvCodec.validate(withUuid, true, nameBytes = 8, includeName = true))

        val tooBig = BleAdvCodec.Payload(
            serviceUuids = List(8) { "0000180d-0000-1000-8000-00805f9b34fb" },
        )
        val error = BleAdvCodec.validate(tooBig, true, nameBytes = 0, includeName = false)
        assertNotNull(error)
        assertTrue(error!!.contains("31"))
    }

    /* ---------------------------- BatteryCodec -------------------------- */

    @Test
    fun decodesBatteryLevel() {
        assertEquals(87, BatteryCodec.parse(byteArrayOf(87)).level)
        assertEquals(0, BatteryCodec.parse(byteArrayOf(0)).level)
        assertEquals(100, BatteryCodec.parse(byteArrayOf(100)).level)
    }

    @Test
    fun reportsUnknownBatteryValues() {
        val unknown = BatteryCodec.parse(byteArrayOf(0xFF.toByte()))
        assertNull(unknown.level)
        assertTrue(unknown.note.contains("未知"))

        val reserved = BatteryCodec.parse(byteArrayOf(101))
        assertNull(reserved.level)
        assertTrue(reserved.note.contains("101"))

        val empty = BatteryCodec.parse(null)
        assertNull(empty.level)
        assertTrue(empty.note.isNotEmpty())
    }

    /* ------------------------------ MidiCodec --------------------------- */

    @Test
    fun buildsChannelVoiceMessages() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 100), MidiCodec.noteOn(1, 60, 100))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 60, 64), MidiCodec.noteOff(2, 60, 64))
        assertArrayEquals(byteArrayOf(0xB3.toByte(), 7, 127), MidiCodec.controlChange(4, 7, 127))
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 5), MidiCodec.programChange(1, 5))
        assertArrayEquals(byteArrayOf(0xE0.toByte(), 0x00, 0x40), MidiCodec.pitchBend(1, 8192))
    }

    @Test
    fun clampsChannelAndData() {
        // 通道路数与数据字节都在合法区间内被钳制，不会产生越界 status。
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0, 127), MidiCodec.noteOn(0, -5, 999))
        assertArrayEquals(byteArrayOf(0x9F.toByte(), 127, 127), MidiCodec.noteOn(99, 200, 200))
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0x7F, 0x7F), MidiCodec.pitchBend(16, 99999))
    }

    @Test
    fun noteNamesUseScientificPitch() {
        assertEquals("C4", MidiCodec.noteName(60))
        assertEquals("C#4", MidiCodec.noteName(61))
        assertEquals("C-1", MidiCodec.noteName(0))
        assertEquals("G9", MidiCodec.noteName(127))
    }

    @Test
    fun parsesRawMidiHexOnly() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x40, 0x7F), MidiCodec.parseHex("90 40 7F"))
        assertNull(MidiCodec.parseHex("40 40 7F")) // 缺状态字节
        assertNull(MidiCodec.parseHex(""))
        assertNull(MidiCodec.parseHex(null))
    }

    @Test
    fun describesMessages() {
        assertEquals("note_on ch=1 note=60(C4) velocity=100", MidiCodec.describe(MidiCodec.noteOn(1, 60, 100)))
        assertEquals(
            "note_off ch=1 note=60(C4) velocity=0",
            MidiCodec.describe(MidiCodec.noteOn(1, 60, 0)),
        )
        assertEquals("program_change ch=1 program=5", MidiCodec.describe(MidiCodec.programChange(1, 5)))
        assertEquals("pitch_bend ch=1 value=8192", MidiCodec.describe(MidiCodec.pitchBend(1, 8192)))
        assertEquals("clock", MidiCodec.describe(byteArrayOf(0xF8.toByte())))
        assertEquals("sysex 2 bytes", MidiCodec.describe(byteArrayOf(0xF0.toByte(), 0x7E, 0xF7.toByte())))
    }

    /* ------------------------------ UsbClasses -------------------------- */

    @Test
    fun namesUsbClasses() {
        assertEquals("mass_storage", UsbClasses.className(0x08))
        assertEquals("hid", UsbClasses.className(0x03))
        assertEquals("class_0x99", UsbClasses.className(0x99))
    }

    @Test
    fun detectsCompositeKinds() {
        assertEquals("mtp", UsbClasses.kind(0x06, 0x01, 0x01))
        assertEquals("ptp", UsbClasses.kind(0x06, 0x01, 0x02))
        assertEquals("adb", UsbClasses.kind(0xFF, 0x42, 0x01))
        assertEquals("usb_midi", UsbClasses.kind(0x01, 0x03, 0x00))
        assertEquals("uvc_camera", UsbClasses.kind(0x0E, 0x01, 0x00))
        assertNull(UsbClasses.kind(0xFE, 0x02, 0x01))
    }

    @Test
    fun endpointTypesAndSummary() {
        assertEquals("bulk", UsbClasses.endpointType(2))
        assertEquals("interrupt", UsbClasses.endpointType(3))
        assertEquals("isochronous", UsbClasses.endpointType(1))
        // USB 规范里 MTP 设备类仍是 0x06 image（子类 1 / 协议 1 才是 MTP）。
        assertEquals("image(mtp)", UsbClasses.deviceClassSummary(0x06, 0x01, 0x01))
        assertEquals("audio", UsbClasses.deviceClassSummary(0x01, 0x02, 0x00))
    }
}

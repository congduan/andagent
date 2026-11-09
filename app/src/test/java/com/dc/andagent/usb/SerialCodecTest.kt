package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SerialCodecTest {
    @Test fun ch34xBaudRegisters() {
        // Values from the Ch34xSerialDriver BAUDBASE_FACTOR/divisor algorithm.
        assertEquals(0xb282 to 0x0c, SerialCodec.ch34xBaud(9600))
        assertEquals(0xcc83 to 0x08, SerialCodec.ch34xBaud(115200))
        assertEquals(0xf387 to 0x00, SerialCodec.ch34xBaud(921600))
        assertEquals(0xc3, SerialCodec.ch34xLcr(8, 1, "none"))
        assertEquals(0xdf, SerialCodec.ch34xLcr(8, 2, "even"))
    }
    @Test fun cp21xxConfigAndBaud() {
        assertEquals(0x0800, SerialCodec.cp21xxLineControl(8, 1, "none"))
        assertEquals(0x0722, SerialCodec.cp21xxLineControl(7, 2, "even"))
        assertArrayEquals(byteArrayOf(0x00, 0xc2.toByte(), 0x01, 0x00), SerialCodec.cp21xxBaud(115200))
    }
    @Test fun ftdiDivisorAndLineControl() {
        assertEquals(0x4138 to 0, SerialCodec.ftdiBaud(9600))
        assertEquals(0x001a to 0, SerialCodec.ftdiBaud(115200))
        assertEquals(0 to 0, SerialCodec.ftdiBaud(3000000))
        assertEquals(0x08, SerialCodec.ftdiLineControl(8, 1, "none"))
        assertEquals(0x1207, SerialCodec.ftdiLineControl(7, 2, "even"))
    }
    @Test fun cdcLineCodingLittleEndian() {
        assertArrayEquals(byteArrayOf(0x00, 0xc2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08),
            SerialCodec.cdcLineCoding(115200, 8, 1, "none"))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x25, 0, 0, 2, 2, 7),
            SerialCodec.cdcLineCoding(9600, 7, 2, "even"))
    }
}

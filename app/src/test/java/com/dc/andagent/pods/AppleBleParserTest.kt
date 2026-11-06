package com.dc.andagent.pods

import org.junit.Assert.*
import org.junit.Test

class AppleBleParserTest {
    private fun frame(model: Int = 0x1420, status: Int = 0x0a, battery: Int = 0x98,
                      flags: Int = 0x72, lid: Int = 0x08, connection: Int = 4): ByteArray =
        byteArrayOf(7, 9, 1, (model shr 8).toByte(), model.toByte(), status.toByte(),
            battery.toByte(), flags.toByte(), lid.toByte(), 0, connection.toByte())

    @Test fun pro2ModelAndBattery() {
        val s = AppleBleParser.parse(frame(), -45)!!
        assertEquals("AirPods Pro 2", s.model)
        assertEquals(90, s.batteryLeft); assertEquals(80, s.batteryRight); assertEquals(70, s.batteryCase)
    }
    @Test fun pro2WearBoth() { val s = AppleBleParser.parse(frame(), -40)!!; assertTrue(s.isLeftInEar!!); assertTrue(s.isRightInEar!!) }
    @Test fun pro2WearLeftOnly() { val s = AppleBleParser.parse(frame(status = 2), -40)!!; assertTrue(s.isLeftInEar!!); assertFalse(s.isRightInEar!!) }
    @Test fun pro2WearRightOnly() { val s = AppleBleParser.parse(frame(status = 8), -40)!!; assertFalse(s.isLeftInEar!!); assertTrue(s.isRightInEar!!) }
    @Test fun flippedSides() { val s = AppleBleParser.parse(frame(status = 0x42, battery = 0x98, flags = 0x71), -40)!!; assertEquals(80, s.batteryLeft); assertEquals(90, s.batteryRight); assertTrue(s.chargingL == true) }
    @Test fun chargingFlags() { val s = AppleBleParser.parse(frame(flags = 0x76), -40)!!; assertTrue(s.chargingL == true); assertFalse(s.chargingR == true); assertTrue(s.chargingCase == true) }
    @Test fun lidStates() { assertTrue(AppleBleParser.parse(frame(lid = 8), -40)!!.lidOpen!!); assertFalse(AppleBleParser.parse(frame(lid = 0), -40)!!.lidOpen!!) }
    @Test fun unavailableBattery() { val s = AppleBleParser.parse(frame(battery = 0xff, flags = 0xf0), -40)!!; assertNull(s.batteryLeft); assertNull(s.batteryRight); assertNull(s.batteryCase) }
    @Test fun airpods3() { assertEquals("AirPods 3", AppleBleParser.parse(frame(model = 0x1320), -40)!!.model) }
    @Test fun maxSingleDevice() { val s = AppleBleParser.parse(frame(model = 0x0a20), -40)!!; assertEquals("AirPods Max", s.model); assertNull(s.batteryLeft); assertEquals(80, s.batteryRight); assertNull(s.lidOpen) }
    @Test fun disconnected() { assertFalse(AppleBleParser.parse(frame(connection = 0), -40)!!.isConnected!!) }
    @Test fun connected() { assertTrue(AppleBleParser.parse(frame(connection = 5), -40)!!.isConnected!!) }
    @Test fun unknownConnection() { assertNull(AppleBleParser.parse(frame(connection = 0xff), -40)!!.isConnected) }
    @Test fun rejectsOtherMessage() { val b = frame(); b[0] = 0x10; assertNull(AppleBleParser.parse(b, -40)) }
    @Test fun pairingModePlainBattery() {
        val b = byteArrayOf(7, 14, 0, 0x13, 0x20, 0, 0, 0, 0, 0, 0, 0, 65, 70, 80, 0)
        val s = AppleBleParser.parse(b, -40)!!
        assertEquals("AirPods 3", s.model); assertEquals(70, s.batteryLeft)
        assertEquals(65, s.batteryRight); assertEquals(80, s.batteryCase)
        assertNull(s.isLeftInEar); assertNull(s.isConnected)
    }
    @Test fun rejectsTruncated() { assertNull(AppleBleParser.parse(frame().copyOf(8), -40)) }
    @Test fun rejectsUnknownModel() { assertNull(AppleBleParser.parse(frame(model = 0x9999), -40)) }
    @Test fun rssiAndTimestamp() { val s = AppleBleParser.parse(frame(), -63, now = 1234)!!; assertEquals(-63, s.rssi); assertEquals(1234L, s.timestamp) }
}

package com.dc.andagent.pods

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R148：ThermoHygroParsers 纯 JVM 字节样本断言。所有广播均由本文件手工构造，
 * 不依赖真机 BLE。覆盖 ATC1441 正/负温度、pvvx4/pvvx5、MiBeacon3 温+湿+电量、
 * MiBeacon5 加密标注；边界：短包截断、魔数错、湿度 0/100、垃圾字节不抛异常、
 * unknownCount/encryptedCount、华氏换算与 json 字段。
 */
class ThermoParsersTest {

    /* ----------------------------- 字节构造 helper ----------------------------- */

    private fun ba(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
    private fun u16le(v: Int) = ba(v and 0xFF, (v shr 8) and 0xFF)
    private fun u16be(v: Int) = ba((v shr 8) and 0xFF, v and 0xFF)
    private fun s16le(v: Int) = u16le(v and 0xFFFF)
    private fun s16be(v: Int) = u16be(v and 0xFFFF)
    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (b in parts) { b.copyInto(out, p); p += b.size }
        return out
    }
    private val MAC = ba(0xA4, 0xC1, 0x38, 0x11, 0x22, 0x33)

    /** ATC1441：13 字节，temp 大端补码。 */
    private fun atc1441(tempRaw: Int, hum: Int, batt: Int, mv: Int) = cat(
        MAC, s16be(tempRaw), ba(hum, batt), u16be(mv), ba(0x2A),
    )

    /** pvvx custom：18 字节，全小端，100 倍精度。 */
    private fun pvvx(magic: Int, tempRaw: Int, humRaw: Int, batt: Int, mv: Int) = cat(
        u16le(magic), ba(0x00), u16le(mv), MAC,
        s16le(tempRaw), u16le(humRaw), ba(batt, 0x01, 0x00),
    )

    /** MiBeacon：frame control + product id + counter + MAC(反序) + object TLV 段。 */
    private fun miBeacon(fc: Int, objects: ByteArray) = cat(
        u16le(fc), u16le(0x055B), ba(0x01), MAC, objects,
    )
    private fun obj(type: Int, value: ByteArray) = cat(ba(type, value.size), value)

    /* ------------------------------- 1. ATC1441 ------------------------------- */

    @Test fun atc1441PositiveTempAndFields() {
        val s = Atc1441Parser.parse(atc1441(235, 48, 87, 2980), -55, null, 1000L)!!
        assertEquals(23.5, s.temperatureC!!, 1e-6)
        assertEquals(48.0, s.humidityPercent!!, 1e-6)
        assertEquals(87, s.batteryPercent)
        assertEquals(2980, s.batteryMv)
        assertEquals(-55, s.rssi)
        assertEquals(1000L, s.ts)
        assertEquals("ATC1441", s.sourceFormat)
        assertEquals("A4:C1:38:11:22:33", s.mac)
        assertEquals(74.3, s.temperatureF()!!, 1e-6)
    }

    @Test fun atc1441NegativeTempTwoComplement() {
        val s = Atc1441Parser.parse(atc1441(-125, 60, 50, 2800), -60)!!
        assertEquals(-12.5, s.temperatureC!!, 1e-6)
    }

    @Test fun passedMacTakesPrecedence() {
        val s = Atc1441Parser.parse(atc1441(235, 48, 87, 2980), -55, "AA:BB:CC:DD:EE:FF")!!
        assertEquals("AA:BB:CC:DD:EE:FF", s.mac)
    }

    /* -------------------------------- 2. pvvx -------------------------------- */

    @Test fun pvvx4HundredthsPrecisionAndFields() {
        val s = PvvxParser.parse(pvvx(0x0845, 2356, 4812, 91, 3010), -48, null, 2000L)!!
        assertEquals(23.56, s.temperatureC!!, 1e-6)
        assertEquals(48.12, s.humidityPercent!!, 1e-6)
        assertEquals(91, s.batteryPercent)
        assertEquals(3010, s.batteryMv)
        assertEquals(-48, s.rssi)
        assertEquals(2000L, s.ts)
        assertEquals("pvvx4", s.sourceFormat)
    }

    @Test fun pvvx5FormatTagAndNegativeTemp() {
        val s = PvvxParser.parse(pvvx(0x0A45, -1234, 10000, 100, 3000), -50)!!
        assertEquals("pvvx5", s.sourceFormat)
        assertEquals(-12.34, s.temperatureC!!, 1e-6)
        assertEquals(100.0, s.humidityPercent!!, 1e-6)
    }

    /* ------------------------------ 3. MiBeacon ------------------------------ */

    @Test fun miBeacon3TemperatureHumidityBattery() {
        val objects = cat(
            obj(0x0D, s16le(235)),   // 23.5 C
            obj(0x04, u16le(456)),   // 45.6 %
            obj(0x0A, u16le(3000)),  // 3000 mV
        )
        val s = MiBeaconParser.parse(miBeacon(0x3050, objects), -62, null, 3000L)!!
        assertEquals("MiBeacon3", s.sourceFormat)
        assertEquals("LYWSD03MMC", s.name)
        assertEquals(23.5, s.temperatureC!!, 1e-6)
        assertEquals(45.6, s.humidityPercent!!, 1e-6)
        assertEquals(3000, s.batteryMv)
        assertNull(s.batteryPercent)
        assertEquals(-62, s.rssi)
    }

    @Test fun miBeacon5EncryptedAnnotatedNotDecrypted() {
        val frame = miBeacon(0x5058, obj(0x0D, s16le(250)))
        assertTrue(MiBeaconParser.isEncrypted(frame))
        assertNull(MiBeaconParser.parse(frame, -60))
        ThermoHygroParsers.resetCounters()
        assertNull(ThermoHygroParsers.decode(ThermoHygroParsers.UUID_FE95, frame, -60, null, 4000L))
        assertEquals(1, ThermoHygroParsers.encryptedCount)
        assertEquals(0, ThermoHygroParsers.unknownCount)
        assertTrue(ThermoHygroParsers.ENCRYPTED_NOTE.contains("pvvx"))
        assertTrue(ThermoHygroParsers.GUIDE.contains("ATC/pvvx"))
    }

    /* ------------------------------ 分发器 ------------------------------ */

    @Test fun dispatch181AByMagic() {
        ThermoHygroParsers.resetCounters()
        val a = ThermoHygroParsers.decode(ThermoHygroParsers.UUID_181A, atc1441(220, 50, 80, 2900), -50, null, 1L)!!
        assertEquals("ATC1441", a.sourceFormat)
        val p = ThermoHygroParsers.decode(ThermoHygroParsers.UUID_181A, pvvx(0x0845, 2200, 5000, 80, 2900), -50, null, 1L)!!
        assertEquals("pvvx4", p.sourceFormat)
        assertEquals(0, ThermoHygroParsers.unknownCount)
    }

    @Test fun unknownFormatCountedNotThrown() {
        ThermoHygroParsers.resetCounters()
        val truncatedPvvx = cat(u16le(0x0845), ByteArray(5)) // 有魔数但长度不足
        assertNull(ThermoHygroParsers.decode(ThermoHygroParsers.UUID_181A, truncatedPvvx, -50, null, 1L))
        assertEquals(1, ThermoHygroParsers.unknownCount)
        assertNull(ThermoHygroParsers.decode("0000ffff-0000-1000-8000-00805f9b34fb", atc1441(220, 50, 80, 2900), -50, null, 1L))
        assertEquals(1, ThermoHygroParsers.unknownCount) // 无关 UUID 不计入
    }

    /* ------------------------------- 边界 ------------------------------- */

    @Test fun truncatedFramesReturnNull() {
        assertNull(Atc1441Parser.parse(atc1441(235, 48, 87, 2980).copyOf(12), -50))
        assertNull(PvvxParser.parse(pvvx(0x0845, 2356, 4812, 91, 3010).copyOf(17), -50))
        assertNull(MiBeaconParser.parse(miBeacon(0x3050, obj(0x0D, s16le(235))).copyOf(10), -50))
    }

    @Test fun wrongMagicRejected() {
        assertNull(PvvxParser.versionOf(pvvx(0x0846, 2356, 4812, 91, 3010)))
        assertNull(PvvxParser.parse(pvvx(0x0846, 2356, 4812, 91, 3010), -50))
    }

    @Test fun humidityBoundariesZeroAndHundred() {
        assertEquals(0.0, Atc1441Parser.parse(atc1441(200, 0, 50, 2900), -50)!!.humidityPercent!!, 1e-6)
        assertEquals(100.0, Atc1441Parser.parse(atc1441(200, 100, 50, 2900), -50)!!.humidityPercent!!, 1e-6)
        assertEquals(0.0, PvvxParser.parse(pvvx(0x0845, 2000, 0, 50, 2900), -50)!!.humidityPercent!!, 1e-6)
        assertEquals(100.0, PvvxParser.parse(pvvx(0x0845, 2000, 10000, 50, 2900), -50)!!.humidityPercent!!, 1e-6)
    }

    @Test fun junkBytesNeverThrow() {
        for (n in 0..24) {
            val b = ByteArray(n) { (it * 37 and 0xFF).toByte() }
            ThermoHygroParsers.decode(ThermoHygroParsers.UUID_181A, b, -50, null, 1L)
            ThermoHygroParsers.decode(ThermoHygroParsers.UUID_FE95, b, -50, null, 1L)
            Atc1441Parser.parse(b, -50)
            PvvxParser.parse(b, -50)
            MiBeaconParser.parse(b, -50)
        }
    }

    @Test fun snapshotJsonCarriesFahrenheitAndFormat() {
        val j = Atc1441Parser.parse(atc1441(235, 48, 87, 2980), -55)!!.json()
        assertEquals(23.5, (j["temperatureC"] as JsonPrimitive).content.toDouble(), 1e-6)
        assertEquals(74.3, (j["temperatureF"] as JsonPrimitive).content.toDouble(), 1e-6)
        assertEquals("ATC1441", (j["sourceFormat"] as JsonPrimitive).content)
    }
}

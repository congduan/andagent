package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * R84: pure-logic coverage for the hardware-status helpers — no Android
 * framework, no permissions, no device.
 */
class HardwareStatusToolsTest {

    /* ---------------------------- SensorStats --------------------------- */

    @Test
    fun sensorStatsBasicMoments() {
        val s = SensorStats.of(listOf(1f, 2f, 3f, 4f))
        assertEquals(4, s.count)
        assertEquals(2.5, s.mean, 1e-9)
        assertEquals(sqrt(1.25), s.std, 1e-9)
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(4.0, s.max, 1e-9)
        assertEquals(sqrt(7.5), s.rms, 1e-9)
    }

    @Test
    fun sensorStatsEmptyAndRate() {
        val empty = SensorStats.of(emptyList())
        assertEquals(0, empty.count)
        assertEquals(0.0, empty.mean, 1e-9)
        assertEquals(200.0, SensorStats.rateHz(2000, 10_000L), 1e-9)
        assertEquals(0.0, SensorStats.rateHz(10, 0L), 1e-9)
    }

    @Test
    fun magnitudeKeepsGravity() {
        assertEquals(9.8, SensorStats.magnitude(0f, 0f, 9.8f), 1e-6)
        assertEquals(5.0, SensorStats.magnitude(3f, 4f, 0f), 1e-6)
    }

    /* -------------------------- SensorVibration ------------------------- */

    @Test
    fun steadyDeviceIsStill() {
        val magnitudes = List(100) { 9.81 }
        val v = SensorVibration.detect(magnitudes, 1000)!!
        assertEquals("still", v.label)
        assertEquals(0, v.peakCount)
    }

    @Test
    fun strongShakeIsDetected() {
        // Alternating ±6 m/s² around gravity: rms ≈ 6, verdict=strong.
        val magnitudes = List(200) { if (it % 2 == 0) 3.8 else 15.8 }
        val v = SensorVibration.detect(magnitudes, 1000)!!
        assertEquals("strong", v.label)
        assertTrue(v.peakCount > 50)
        assertTrue(v.peakRateHz > 0.0)
    }

    @Test
    fun vibrationNeedsEnoughSamples() {
        assertNull(SensorVibration.detect(listOf(9.8, 9.8), 1000))
        assertNull(SensorVibration.detect(List(100) { 9.8 }, 0))
    }

    /* ---------------------------- WaveformSpec -------------------------- */

    @Test
    fun presetsAreWellFormed() {
        assertTrue(WaveformSpec.presetNames().contains("heartbeat"))
        for (name in WaveformSpec.presetNames()) {
            val preset = WaveformSpec.preset(name)!!
            assertNull("preset $name", WaveformSpec.validate(preset.first, preset.second))
            assertEquals(preset.first.size, preset.second.size)
        }
    }

    @Test
    fun waveformParsing() {
        assertEquals(listOf(0L, 40L, 60L), WaveformSpec.parseTimings("0, 40, 60")!!.toList())
        assertEquals(listOf(0, 200, 0), WaveformSpec.parseAmplitudes("0,200,0")!!.toList())
        assertNull(WaveformSpec.parseTimings("0,abc"))
        assertNull(WaveformSpec.parseTimings(""))
        assertNull(WaveformSpec.parseAmplitudes("1,x"))
    }

    @Test
    fun waveformValidation() {
        assertNull(WaveformSpec.validate(longArrayOf(0, 100), intArrayOf(0, 255)))
        assertNotNull(WaveformSpec.validate(longArrayOf(0, -1), null))
        assertNotNull(WaveformSpec.validate(longArrayOf(), null))
        assertNotNull(WaveformSpec.validate(longArrayOf(0, 100), intArrayOf(0)))
        assertNotNull(WaveformSpec.validate(longArrayOf(0, 100), intArrayOf(0, 256)))
        assertNotNull(WaveformSpec.validate(longArrayOf(0, 20_000), null))
        assertNotNull(WaveformSpec.validate(LongArray(WaveformSpec.MAX_STEPS + 1) { if (it == 0) 0L else 1L }, null))
    }

    @Test
    fun repeatIndexIsClamped() {
        assertEquals(-1, WaveformSpec.repeatValue(null, 4))
        assertEquals(0, WaveformSpec.repeatValue(0, 4))
        assertEquals(3, WaveformSpec.repeatValue(3, 4))
        assertEquals(-1, WaveformSpec.repeatValue(4, 4))
        assertEquals(-1, WaveformSpec.repeatValue(-2, 4))
        assertEquals(220L, WaveformSpec.totalMs(longArrayOf(0, 80, 140)))
    }

    /* ------------------------------ PcmWav ------------------------------ */

    @Test
    fun wavHeaderStereo44k() {
        val h = PcmWav.header(44_100, 2, 16, 176_400)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(176_436, b.getInt(4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(44_100, b.getInt(24))
        assertEquals(44_100 * 2 * 2, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(176_400, b.getInt(40))
    }

    @Test
    fun wavHeaderMatchesLegacy16kMono() {
        // mic_record's fixed 16k/mono header must stay byte-identical.
        assertEquals(
            pcmWavHeader(32_000).toList(),
            PcmWav.header(16_000, 1, 16, 32_000).toList(),
        )
    }

    /* ----------------------------- PcmLevel ----------------------------- */

    @Test
    fun pcmLevelSilenceAndHalfScale() {
        assertEquals(-120.0, PcmLevel.measure(ByteArray(64)).peakDbfs, 1e-9)

        val half = ByteArray(4)
        // 16384 = 0x4000 → little-endian 0x00 0x40
        half[1] = 0x40
        half[3] = 0x40
        val level = PcmLevel.measure(half)
        assertEquals(2, level.samples.toInt())
        assertEquals(-6.0206, level.peakDbfs, 0.001)
        assertEquals(-6.0206, level.rmsDbfs, 0.001)
    }

    @Test
    fun pcmLevelNegativeSampleUsesMagnitude() {
        val bytes = byteArrayOf(0x00, 0x80.toByte()) // -32768
        val level = PcmLevel.measure(bytes)
        assertEquals(10.0 * kotlin.math.log10(1.0) + 0.0, level.peakDbfs, 1e-9)
        assertEquals(1, level.samples.toInt())
    }

    /* ---------------------------- ThermalZone --------------------------- */

    @Test
    fun thermalZoneParsing() {
        assertEquals(42.5, ThermalZone.parse("cpu-0-0", "42500\n")!!.celsius, 1e-9)
        assertEquals(42.0, ThermalZone.parse("cpu-0-0", "42")!!.celsius, 1e-9)
        assertEquals(31.2, ThermalZone.parse("battery", "31200")!!.celsius, 1e-9)
        assertEquals("cpu-0-0", ThermalZone.parse("cpu-0-0\n", "42500")!!.name)
        assertNull(ThermalZone.parse("x", "999999")) // 999.999 °C → implausible
        assertNull(ThermalZone.parse("x", "-99000"))
        assertNull(ThermalZone.parse("x", "not-a-number"))
    }

    @Test
    fun thermalZoneNameSanitising() {
        assertEquals("cpu 0", ThermalZone.cleanName("  cpu\u0000 0\n"))
        assertEquals(40, ThermalZone.cleanName("x".repeat(100)).length)
        assertEquals("zone", ThermalZone.parse("\u0001", "40000")!!.name)
    }

    /* ------------------------------ ProcStat ---------------------------- */

    private val procSample = """
        cpu  100 0 100 800 0 0 0 0 0 0
        cpu0 50 0 50 400 0 0 0 0 0 0
        cpu1 50 0 50 400 0 0 0 0 0 0
        intr 12345 0 0
    """.trimIndent()

    @Test
    fun procStatParsesPerCoreOnly() {
        val cores = ProcStat.parse(procSample)
        assertEquals(2, cores.size)
        assertEquals("cpu0", cores[0].name)
        assertEquals(500L, cores[0].total)
        assertEquals(400L, cores[0].idle)
    }

    @Test
    fun procStatBusyPercent() {
        val prev = ProcStat.parse(procSample)
        val next = ProcStat.parse(
            """
            cpu  200 0 200 1400 0 0 0 0 0 0
            cpu0 100 0 100 700 0 0 0 0 0 0
            cpu1 100 0 100 700 0 0 0 0 0 0
            """.trimIndent(),
        )
        val usage = ProcStat.busyPercent(prev, next)
        assertEquals(2, usage.size)
        assertEquals("cpu0", usage[0].first)
        assertEquals(25.0, usage[0].second, 1e-9)
        assertTrue(ProcStat.busyPercent(emptyList(), next).isEmpty())
    }

    /* ----------------------------- BatteryMath -------------------------- */

    @Test
    fun batteryCurrentNormalisation() {
        assertEquals(450.0, BatteryMath.currentMa(450_000).first!!, 1e-9)
        assertEquals("ua", BatteryMath.currentMa(450_000).second)
        assertEquals(-1250.0, BatteryMath.currentMa(-1250).first!!, 1e-9)
        assertEquals("ma", BatteryMath.currentMa(-1250).second)
        assertNull(BatteryMath.currentMa(20_000_000).first)
        assertEquals("implausible:20000000", BatteryMath.currentMa(20_000_000).second)
        assertEquals("unavailable", BatteryMath.currentMa(null).second)
    }

    @Test
    fun batteryWatts() {
        assertEquals(2.1, BatteryMath.watts(500.0, 4.2)!!, 1e-9)
        assertEquals(2.1, BatteryMath.watts(-500.0, 4.2)!!, 1e-9)
        assertNull(BatteryMath.watts(null, 4.2))
        assertNull(BatteryMath.watts(500.0, null))
    }
}

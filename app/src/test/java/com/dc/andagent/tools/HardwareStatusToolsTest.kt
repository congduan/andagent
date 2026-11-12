package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun sensorStatsRateHzFromSpan() {
        // 10 个等间隔样本、窗口 180ms：按 9 个间隔折算 = 50Hz（不再少算成 55.56Hz）。
        assertEquals(50.0, SensorStats.rateHzFromSpan(10, 180L), 1e-9)
        // 单样本 / 空样本无间隔可计 → 0.0，不虚报。
        assertEquals(0.0, SensorStats.rateHzFromSpan(1, 180L), 1e-9)
        assertEquals(0.0, SensorStats.rateHzFromSpan(0, 0L), 1e-9)
        // 窗口为 0 时同样不虚报（无除零 / Infinity）。
        assertEquals(0.0, SensorStats.rateHzFromSpan(10, 0L), 1e-9)
    }

    @Test
    fun sensorStatsRateHzFromSpanNsKeepsSubMillisecondPrecision() {
        // 二轮必修 B：窗口保留纳秒原值到除法，避免整除 1e6 丢精度。
        // 0.8ms 两样本：旧 ms 路径整除成 0ms→0Hz；ns 路径 = (2-1)*1e9/800_000 = 1250Hz。
        assertEquals(1250.0, SensorStats.rateHzFromSpanNs(2, 800_000L), 1e-9)
        // 1.9ms 两样本：旧 ms 路径整除成 1ms→1000Hz；ns 路径 = 10000/19 ≈ 526.315789...，Double 精度断言。
        assertEquals(526.3157894736842, SensorStats.rateHzFromSpanNs(2, 1_900_000L), 1e-9)
        // 与旧 ms 口径等价：10 样本 / 180ms(=180_000_000ns) → 50Hz。
        assertEquals(50.0, SensorStats.rateHzFromSpanNs(10, 180_000_000L), 1e-9)
        // 边界保护不变：count<=1 或 spanNs<=0 → 0.0（无间隔可计、无除零）。
        assertEquals(0.0, SensorStats.rateHzFromSpanNs(1, 800_000L), 1e-9)
        assertEquals(0.0, SensorStats.rateHzFromSpanNs(0, 0L), 1e-9)
        assertEquals(0.0, SensorStats.rateHzFromSpanNs(10, 0L), 1e-9)
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

    /* ------------------ R146 DirectChannelEventReader ------------------- */

    private fun ByteArray.putIntLe(off: Int, v: Int) {
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = (v ushr 8 and 0xFF).toByte()
        this[off + 2] = (v ushr 16 and 0xFF).toByte()
        this[off + 3] = (v ushr 24 and 0xFF).toByte()
    }

    private fun ByteArray.putLongLe(off: Int, v: Long) {
        putIntLe(off, (v and 0xFFFF_FFFFL).toInt())
        putIntLe(off + 4, (v ushr 32 and 0xFFFF_FFFFL).toInt())
    }

    /** 用 Kotlin stdlib 的 IEEE-754 位表示精确比对 float 读回，不做近似。 */
    private fun floatBits(f: Float): Int = f.toRawBits()

    private fun ByteArray.putFloatLe(off: Int, v: Float) =
        putIntLe(off, floatBits(v))

    /** 手工按 little-endian 逐字节拼一条 104B 事件（不借 ByteBuffer，真正校验内存序）。 */
    private fun leSlot(
        counter: Int,
        size: Int = DirectChannelEventReader.EVENT_SIZE,
        reportToken: Int = 0,
        type: Int = 1,
        tsNs: Long = 0L,
        floats: FloatArray = FloatArray(16) { 0f },
        reserved: Int = 0,
    ): ByteArray = ByteArray(DirectChannelEventReader.EVENT_SIZE).also { b ->
        b.putIntLe(0x00, size)
        b.putIntLe(0x04, reportToken)
        b.putIntLe(0x08, type)
        b.putIntLe(0x0C, counter)
        b.putLongLe(0x10, tsNs)
        for (i in 0 until 16) b.putFloatLe(0x18 + i * 4, floats[i])
        for (i in 0 until 4) b.putIntLe(0x58 + i * 4, reserved)
    }

    private fun bufferOf(vararg slots: ByteArray): ByteArray {
        val out = ByteArray(slots.sumOf { it.size })
        var off = 0
        for (s in slots) {
            s.copyInto(out, off)
            off += s.size
        }
        return out
    }

    @Test
    fun directChannelLittleEndianFields() {
        val floats = FloatArray(16)
        floats[0] = 1.5f
        floats[15] = -0.25f
        val one = leSlot(
            counter = 0x0102_0304,
            reportToken = 0x1122_3344,
            type = 9,
            tsNs = 0x0102_0304_0506_0708L,
            floats = floats,
        )
        // 手工字节断言：确证构造端用的是 little-endian（低位在前）。
        assertEquals(0x68.toByte(), one[0x00])          // size 104 -> 68 00 00 00
        assertEquals(0x00.toByte(), one[0x01])
        assertEquals(0x04.toByte(), one[0x0C])          // counter -> 04 03 02 01
        assertEquals(0x03.toByte(), one[0x0D])
        assertEquals(0x02.toByte(), one[0x0E])
        assertEquals(0x01.toByte(), one[0x0F])
        assertEquals(0x08.toByte(), one[0x10])          // ts -> 08 07 06 05 04 03 02 01
        assertEquals(0x01.toByte(), one[0x17])

        val events = DirectChannelEventReader.parse(one, 1)
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals(0x0102_0304L, e.counter)
        assertEquals(0x1122_3344, e.reportToken)
        assertEquals(9, e.type)
        assertEquals(0x0102_0304_0506_0708L, e.tsNs)
        assertEquals(0, e.slot)
        assertEquals(
            floatBits(1.5f),
            floatBits(e.values[0]),
        )
        assertEquals(
            floatBits(-0.25f),
            floatBits(e.values[15]),
        )
    }

    @Test
    fun directChannelSkipsBadSizeAndZeroCounter() {
        val badSize = leSlot(counter = 8, size = 96)
        val zeroCounter = leSlot(counter = 0, type = 1)
        val good = leSlot(counter = 7, type = 1, tsNs = 1000L)
        val buffer = bufferOf(badSize, zeroCounter, good)

        val events = DirectChannelEventReader.parse(buffer, 3)
        assertEquals(1, events.size)
        assertEquals(7L, events[0].counter)
        assertEquals(1000L, events[0].tsNs)
        assertEquals(2, events[0].slot)
        // 坏槽（size!=104，counter=8）不参与 maxCounter，否则 maxC 会被误判成 8。
        assertEquals(7L, DirectChannelEventReader.maxCounter(buffer, 3))
    }

    @Test
    fun directChannelUnsignedCounter() {
        // uint32 最大值：写入 Int -1，读回必须是 4294967295 而不是 -1/被判为小于 1。
        val one = leSlot(counter = 1, tsNs = 7L)
        val wrap = leSlot(counter = -1, tsNs = 42L)
        val buffer = bufferOf(one, wrap)
        assertEquals(0xFFFF_FFFFL, DirectChannelEventReader.maxCounter(buffer, 2))
        val events = DirectChannelEventReader.parse(buffer, 2)
        assertEquals(2, events.size)
        assertEquals(1L, events[0].counter)
        assertEquals(0xFFFF_FFFFL, events[1].counter)
    }

    @Test
    fun directChannelFloatReadbackAll16() {
        val floats = FloatArray(16) { i -> i * 1.25f - 3.5f }
        val e = DirectChannelEventReader.parse(leSlot(counter = 3, floats = floats), 1).single()
        assertEquals(16, e.values.size)
        for (i in 0 until 16) {
            assertEquals(
                floatBits(floats[i]),
                floatBits(e.values[i]),
            )
        }
    }

    @Test
    fun directChannelBoundaryExcludesNewerCounters() {
        // 先取边界=5，模拟硬件又并发写入 counter=6 的「半写/新写」槽：
        // 显式 boundary 下，counter>boundary 的槽必须被排除，防读到写一半的数据。
        val s5 = leSlot(counter = 5, tsNs = 50L)
        val s6 = leSlot(counter = 6, tsNs = 60L)
        val buffer = bufferOf(s5, s6)
        val events = DirectChannelEventReader.parse(buffer, 2, boundary = 5L)
        assertEquals(1, events.size)
        assertEquals(5L, events[0].counter)
        // 单阶段（boundary 默认就地取 maxC=6）则两槽都在边界内，全收。
        assertEquals(2, DirectChannelEventReader.parse(buffer, 2).size)
        assertEquals(6L, DirectChannelEventReader.maxCounter(buffer, 2))
    }

    @Test
    fun directChannelDuplicateCountersAllCollected() {
        // 同一 counter（硬件重复或 wrap）出现多次，全部收集、不去重。
        val buffer = bufferOf(
            leSlot(counter = 2, tsNs = 10L, reportToken = 11),
            leSlot(counter = 2, tsNs = 20L, reportToken = 11),
            leSlot(counter = 9, tsNs = 30L),            // maxC
            leSlot(counter = 2, tsNs = 40L, reportToken = 12),
            leSlot(counter = 5, tsNs = 50L),
        )
        assertEquals(9L, DirectChannelEventReader.maxCounter(buffer, 5))
        val events = DirectChannelEventReader.parse(buffer, 5)
        assertEquals(5, events.size)
        assertEquals(3, events.count { it.counter == 2L })
        // parse 现按 counter 稳定升序输出；重复 counter 保持物理槽相对顺序。
        assertEquals(listOf(2L, 2L, 2L, 5L, 9L), events.map { it.counter })
        assertEquals(listOf(10L, 20L, 40L, 50L, 30L), events.map { it.tsNs })
        assertEquals(listOf(0, 1, 3, 4, 2), events.map { it.slot })
    }

    @Test
    fun directChannelEmptyTruncatedAndSlotLimit() {
        assertTrue(DirectChannelEventReader.parse(ByteArray(0)).isEmpty())
        assertEquals(0L, DirectChannelEventReader.maxCounter(ByteArray(0)))
        assertEquals(0L, DirectChannelEventReader.maxCounter(ByteArray(0), 5))

        // 全部 counter==0（未写入）→ 无有效事件、无写入边界。
        val zeros = bufferOf(leSlot(0), leSlot(0))
        assertTrue(DirectChannelEventReader.parse(zeros, 2).isEmpty())
        assertEquals(0L, DirectChannelEventReader.maxCounter(zeros, 2))

        // slots 限定参与扫描的槽数，超出部分不读。
        val buf = bufferOf(leSlot(1), leSlot(2), leSlot(3))
        assertEquals(2L, DirectChannelEventReader.maxCounter(buf, 2))
        assertEquals(2, DirectChannelEventReader.parse(buf, 2).size)

        // 尾部非整槽字节不 crash，按整槽数读取。
        val partial = buf + byteArrayOf(1, 2, 3)
        assertEquals(3, DirectChannelEventReader.parse(partial).size)
        assertEquals(3L, DirectChannelEventReader.maxCounter(partial))
    }

    @Test
    fun directChannelRandomRoundTrip() {
        val rnd = java.util.Random(0x5EEDL)
        repeat(20) {
            val n = 1 + rnd.nextInt(40)
            val slots = ArrayList<ByteArray>(n)
            val expectedBySlot = HashMap<Int, Triple<Long, Long, FloatArray>>()
            for (i in 0 until n) {
                when (rnd.nextInt(5)) {
                    0 -> slots += leSlot(counter = 1 + rnd.nextInt(1000), size = 96) // 坏槽
                    1 -> slots += leSlot(counter = 0)                                  // 未写入
                    else -> {
                        val c = 1 + rnd.nextInt(1000)
                        val ts = rnd.nextLong()
                        val f = FloatArray(16) { rnd.nextFloat() * 20f - 10f }
                        slots += leSlot(counter = c, tsNs = ts, floats = f)
                        expectedBySlot[i] = Triple(c.toLong(), ts, f)
                    }
                }
            }
            val buffer = bufferOf(*slots.toTypedArray())
            val boundary = DirectChannelEventReader.maxCounter(buffer, n)
            val events = DirectChannelEventReader.parse(buffer, n, boundary)
            // 所有有效槽的 counter 都 <= maxC，故应全收（坏槽/未写入跳过，不 crash）。
            assertEquals(expectedBySlot.size, events.size)
            for (e in events) {
                assertTrue(e.counter in 1..boundary)
                val exp = expectedBySlot[e.slot]!!
                assertEquals(exp.first, e.counter)
                assertEquals(exp.second, e.tsNs)
                for (j in 0 until 16) {
                    assertEquals(
                        floatBits(exp.third[j]),
                        floatBits(e.values[j]),
                    )
                }
            }
        }
    }

    /* ------------- R146-fix 必修 2/3 新增纯函数与排序单测 ------------- */

    @Test
    fun directChannelParseSortsByCounterAscending() {
        // 槽序 [5,3,4,1,2] 的 counter 乱序物理布局（环形窗口/并发写导致的物理错序）。
        val buffer = bufferOf(
            leSlot(counter = 5, tsNs = 50L),
            leSlot(counter = 3, tsNs = 30L),
            leSlot(counter = 4, tsNs = 40L),
            leSlot(counter = 1, tsNs = 10L),
            leSlot(counter = 2, tsNs = 20L),
        )
        val events = DirectChannelEventReader.parse(buffer, 5)
        // 按 counter 升序恢复写入顺序（时间戳随之单调不减）。
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), events.map { it.counter })
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), events.map { it.tsNs })
        // 物理槽号随事件保留，供 detectWrap 判定回绕。
        assertEquals(listOf(3, 4, 1, 2, 0), events.map { it.slot })
    }

    @Test
    fun directChannelDetectWrapByPositionSignature() {
        // 8 槽中 5 个有效事件呈回绕布局：counter 4..8 写在物理槽 3,4,0,1,2，
        // 其余 3 槽 counter=0（未写入/坏槽）。最大 counter 槽(2) < 最小 counter 槽(3)。
        val events = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 6, tsNs = 60L),
                leSlot(counter = 7, tsNs = 70L),
                leSlot(counter = 8, tsNs = 80L),
                leSlot(counter = 4, tsNs = 40L),
                leSlot(counter = 5, tsNs = 50L),
                leSlot(counter = 0),
                leSlot(counter = 0),
                leSlot(counter = 0),
            ),
            8,
        )
        assertEquals(5, events.size)
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), events.map { it.counter })
        // events.size(5) < slots(8)，须由物理位置特征（而非「全槽写满」）检出回绕。
        assertTrue(DirectChannelEventReader.detectWrap(events, 8))
    }

    @Test
    fun directChannelDetectWrapFullNoWrapAndEmpty() {
        // 顺序写入、未写满：最大 counter 槽(4) > 最小 counter 槽(0) → 无回绕。
        val sequential = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 1), leSlot(counter = 2), leSlot(counter = 3),
                leSlot(counter = 4), leSlot(counter = 5),
            ),
            5,
        )
        assertEquals(5, sequential.size)
        assertFalse(DirectChannelEventReader.detectWrap(sequential, 8))
        // 全槽写满（events.size == slots）→ 保守视为可能已覆盖。
        assertTrue(DirectChannelEventReader.detectWrap(sequential, 5))
        // 空事件 → false。
        assertFalse(DirectChannelEventReader.detectWrap(emptyList(), 5))
    }

    @Test
    fun directChannelDetectWrapExactlyFullSequentialIsConservative() {
        // 必修 3 边界：恰好写满 slots 且顺序写入（物理槽号递增、counter 递增，无 b) 回绕特征）。
        // 仅凭快照无法区分「写满未覆盖」与「写满且已覆盖一轮」，按 a) 保守判为 true。
        // 逻辑-文档-文案三者一致：KDoc 注明「全槽=保守可能覆盖 possibleWrap」，note 只说
        // 「可能已回绕 / 若发生覆盖」，不谎称「更早事件已被覆盖」；本用例断言该保守判定。
        val slots = 4
        val events = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 1), leSlot(counter = 2),
                leSlot(counter = 3), leSlot(counter = 4),
            ),
            slots,
        )
        assertEquals(slots, events.size)
        // 顺序写入特征：最小 counter 在槽 0、最大 counter 在槽 3，无写指针回绕。
        assertEquals(0, events.single { it.counter == events.minOf { e -> e.counter } }.slot)
        assertEquals(3, events.single { it.counter == events.maxOf { e -> e.counter } }.slot)
        // 仅由「全槽写满」保守触发 true（possibleWrap，非确定覆盖）。
        assertTrue(DirectChannelEventReader.detectWrap(events, slots))
    }

    /* --------- R146 二轮必修 A：determinate-overwrite 判据单测 --------- */

    @Test
    fun hasWrapSignatureExactlyFullSequentialIsFalse() {
        // 二轮必修 A：恰好写满且顺序写入（条件 a 成立、条件 b 不成立）——无确定覆盖证据。
        // overwritten / truncated 据此为 false，possibleWrap(detectWrap) 仍为 true（保守「可能」）。
        // 旧实现把 detectWrap 直接当 overwritten 用，会在此场景虚报覆盖，本用例锁定修复。
        val slots = 4
        val sequential = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 1), leSlot(counter = 2),
                leSlot(counter = 3), leSlot(counter = 4),
            ),
            slots,
        )
        assertEquals(slots, sequential.size)
        assertFalse(DirectChannelEventReader.hasWrapSignature(sequential)) // 确定覆盖=false
        assertTrue(DirectChannelEventReader.detectWrap(sequential, slots))  // 可能回绕=true
        assertFalse(DirectChannelEventReader.hasWrapSignature(emptyList())) // 空=false
    }

    @Test
    fun hasWrapSignatureWrapLayoutIsTrueEvenWhenFull() {
        // 二轮必修 A：写指针回绕布局（最大 counter 槽 < 最小 counter 槽）——确定覆盖。
        // 关键：即便 events.size == slots（全槽写满），hasWrapSignature 仍须独立判出条件 b；
        // 而 detectWrap 因条件 a 短路恒 true。两函数分离，overwritten 才不虚报恰好写满场景。
        val slots = 5
        val wrappedFull = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 6, tsNs = 60L),
                leSlot(counter = 7, tsNs = 70L),
                leSlot(counter = 8, tsNs = 80L),
                leSlot(counter = 4, tsNs = 40L),
                leSlot(counter = 5, tsNs = 50L),
            ),
            slots,
        )
        assertEquals(slots, wrappedFull.size)
        assertTrue(DirectChannelEventReader.hasWrapSignature(wrappedFull)) // 确定覆盖=true
        assertTrue(DirectChannelEventReader.detectWrap(wrappedFull, slots))
        // 非写满的回绕布局（5 事件 / 8 槽，3 槽未写入）同样为 true。
        val partial = DirectChannelEventReader.parse(
            bufferOf(
                leSlot(counter = 6), leSlot(counter = 7), leSlot(counter = 8),
                leSlot(counter = 4), leSlot(counter = 5),
                leSlot(counter = 0), leSlot(counter = 0), leSlot(counter = 0),
            ),
            8,
        )
        assertEquals(5, partial.size)
        assertTrue(DirectChannelEventReader.hasWrapSignature(partial))
    }

    @Test
    fun directChannelRateLevelForHz() {
        assertEquals(DirectChannelEventReader.RATE_NORMAL, DirectChannelEventReader.rateLevelForHz(1))
        assertEquals(DirectChannelEventReader.RATE_NORMAL, DirectChannelEventReader.rateLevelForHz(110))
        assertEquals(DirectChannelEventReader.RATE_FAST, DirectChannelEventReader.rateLevelForHz(111))
        assertEquals(DirectChannelEventReader.RATE_FAST, DirectChannelEventReader.rateLevelForHz(440))
        assertEquals(DirectChannelEventReader.RATE_VERY_FAST, DirectChannelEventReader.rateLevelForHz(441))
        assertEquals(DirectChannelEventReader.RATE_VERY_FAST, DirectChannelEventReader.rateLevelForHz(500))
    }

    @Test
    fun directChannelNominalHzForLevel() {
        assertEquals(50, DirectChannelEventReader.nominalHzForLevel(DirectChannelEventReader.RATE_NORMAL))
        assertEquals(200, DirectChannelEventReader.nominalHzForLevel(DirectChannelEventReader.RATE_FAST))
        assertEquals(800, DirectChannelEventReader.nominalHzForLevel(DirectChannelEventReader.RATE_VERY_FAST))
    }
}

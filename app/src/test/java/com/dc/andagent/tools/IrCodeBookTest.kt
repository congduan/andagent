package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * R113 内置红外码表：协议编码位序、Pronto 往返、码表检索与用户码覆盖。
 *
 * 位序断言是独立于实现的（按 NEC/NECx/Sony SIRC/RC5 规范从 pattern 反解），
 * 因此能真正挡住「编出来的波形看起来对、实际遥控器不认」这类回归。
 */
class IrCodeBookTest {

    private val assetText: String by lazy { File("src/main/assets/ir/ir-codes.json").readText() }

    private class MemStore : IrCodeStore {
        val map = LinkedHashMap<String, String>()
        override fun records(): List<String> = map.values.toList()
        override fun put(key: String, json: String) {
            map[key] = json
        }

        override fun delete(key: String): Boolean = map.remove(key) != null
    }

    /* ---------------------------- 协议位序 ---------------------------- */

    @Test
    fun necFrameMatchesSpec() {
        val signal = IrProtocols.encode("nec1", 4, -1, 8)
        assertEquals(38000, signal.carrierHz)
        assertEquals(68 * 2, signal.pattern.size) // 32 位 × 2 + 引导 2 + 停止位/帧间隔 2，共 2 帧
        assertEquals(9000, signal.pattern[0])
        assertEquals(4500, signal.pattern[1])

        val bits = necBits(signal.pattern, 0)
        val address = bits.subList(0, 8)
        val addressInv = bits.subList(8, 16)
        val command = bits.subList(16, 24)
        val commandInv = bits.subList(24, 32)
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 0, 0), address)          // 4，LSB 先
        assertEquals(listOf(1, 1, 0, 1, 1, 1, 1, 1), addressInv)      // ~4
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0, 0), command)         // 8
        assertEquals(listOf(1, 1, 1, 0, 1, 1, 1, 1), commandInv)      // ~8

        // 第二帧与第一帧完全一致（连发）
        assertEquals(bits, necBits(signal.pattern, 1))
        assertTrue(signal.pattern.sumOf { it.toLong() } <= 2_000_000L)
    }

    @Test
    fun necxEncodesSixteenBitAddress() {
        val signal = IrProtocols.encode("necx2", 7, 7, 2) // 三星电视：地址 0x0707
        assertEquals(68 * 2, signal.pattern.size)
        val bits = necBits(signal.pattern, 0)
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0, 0) + listOf(1, 1, 1, 0, 0, 0, 0, 0), bits.subList(0, 16))
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0, 0), bits.subList(16, 24)) // 2
        assertEquals(listOf(1, 0, 1, 1, 1, 1, 1, 1), bits.subList(24, 32)) // ~2
    }

    @Test
    fun sonyFrameMatchesSpec() {
        val signal = IrProtocols.encode("sony12", 1, -1, 21) // 索尼电源
        assertEquals(40000, signal.carrierHz)
        assertEquals(26 * 3, signal.pattern.size) // 12 位 × 2 + 引导 2，默认连发 3 帧
        assertEquals(2400, signal.pattern[0])
        assertEquals(600, signal.pattern[1])
        val expected = listOf(1, 0, 1, 0, 1, 0, 0) + listOf(1, 0, 0, 0, 0) // 命令 21 + 设备 1，均 LSB 先
        assertEquals(expected, sonyBits(signal.pattern, 0))
        assertEquals(expected, sonyBits(signal.pattern, 1))
    }

    @Test
    fun rc5FrameMatchesManchesterSpec() {
        val signal = IrProtocols.encode("rc5", 0, -1, 12) // 飞利浦电源
        assertEquals(36000, signal.carrierHz)
        assertEquals(889, signal.pattern[0])
        // 起始位 1 + field 1 + toggle 0 + 系统号 00000 + 命令 001100（高位先）
        val expected = listOf(1, 1, 0) + listOf(0, 0, 0, 0, 0) + listOf(0, 0, 1, 1, 0, 0)
        assertEquals(expected, rc5Bits(signal.pattern))
    }

    @Test
    fun rejectsUnsupportedProtocolAndBadFrames() {
        assertThrows(IllegalArgumentException::class.java) { IrProtocols.encode("rc6", 0, -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { IrProtocols.encode("nec1", 4, -1, 8, 6) }
        assertThrows(IllegalArgumentException::class.java) { IrProtocols.encode("rc5", 0, -1, 200) }
        assertThrows(IllegalArgumentException::class.java) { validatePattern(intArrayOf(560)) }
    }

    @Test
    fun prontoExportRoundTrips() {
        val signal = IrProtocols.encode("necx2", 7, 7, 2)
        val pronto = signal.toProntoHex()
        assertTrue(pronto.startsWith("0000 "))
        val parsed = ProntoHex.parse(pronto)
        assertTrue(abs(parsed.carrierHz - signal.carrierHz) < 200)
        assertEquals(signal.pattern.size, parsed.pattern.size)
        for (i in signal.pattern.indices) {
            assertTrue("第 $i 项偏差过大：${signal.pattern[i]} vs ${parsed.pattern[i]}", abs(signal.pattern[i] - parsed.pattern[i]) <= 40)
        }
    }

    /* ---------------------------- 内置码表 ---------------------------- */

    @Test
    fun bundledAssetCompilesEveryCommand() {
        val file = File("src/main/assets/ir/ir-codes.json")
        assertTrue("内置码表缺失：${file.absolutePath}", file.exists())
        val entries = IrCodeBook.parseBuiltin(assetText)
        assertEquals(listOf("lg/tv", "samsung/tv", "sony/tv", "philips/tv"), entries.map { it.codeId })
        assertEquals(38000, entries.first { it.codeId == "lg/tv" }.carrierHz)
        assertEquals(40000, entries.first { it.codeId == "sony/tv" }.carrierHz)
        assertEquals(36000, entries.first { it.codeId == "philips/tv" }.carrierHz)
        for (entry in entries) {
            assertTrue("${entry.codeId} 按键为空", entry.commands.isNotEmpty())
            for ((name, code) in entry.commands) {
                val signal = code.signal(entry)
                assertTrue("${entry.codeId}/$name 载波异常", signal.carrierHz in 30_000..60_000)
                assertTrue("${entry.codeId}/$name 时长超限", signal.pattern.sumOf { it.toLong() } <= 2_000_000L)
                val parsed = ProntoHex.parse(signal.toProntoHex())
                assertEquals("${entry.codeId}/$name Pronto 丢失脉冲", signal.pattern.size, parsed.pattern.size)
            }
        }
    }

    @Test
    fun prontoCarrierWordMatchesReferenceCodes() {
        // 公开码库里的 Pronto 载波分频字：38k = 006D、40k = 0068、36k = 0073。
        fun pronto(codeId: String, command: String): String {
            val entry = IrCodeBook.parseBuiltin(assetText).first { it.codeId == codeId }
            return entry.command(command)!!.signal(entry).toProntoHex()
        }
        assertTrue(pronto("lg/tv", "power").startsWith("0000 006D "))
        assertTrue(pronto("sony/tv", "power").startsWith("0000 0068 "))
        assertTrue(pronto("philips/tv", "power").startsWith("0000 0073 "))
        // 单个 NEC 帧 = 34 对脉冲（0x0022），默认连发 2 帧。
        assertTrue(pronto("lg/tv", "power").startsWith("0000 006D 0000 0044 "))
    }

    @Test
    fun searchMatchesBrandsAndChineseAliases() {
        val book = IrCodeBook.load(assetText)
        assertEquals(1, book.search("三星", null, null, 5).size)
        assertEquals("samsung/tv", book.search("三星", null, null, 5).first().codeId)
        assertEquals("philips/tv", book.search("飞利浦", null, null, 5).first().codeId)
        assertEquals(4, book.search(null, "tv", "音量", 5).size) // 四家都有音量键
        assertEquals("sony/tv", book.search("sony", "tv", "电源", 5).first().codeId)
        assertEquals("vol_up", book.entry("lg")!!.command("音量+")!!.name)
        assertEquals("power", book.entry("LG", "电视")!!.command("电源")!!.name)
        assertTrue(book.search("不存在的品牌", null, null, 5).isEmpty())
    }

    /* ---------------------------- 用户码表 ---------------------------- */

    @Test
    fun userCodeOverridesBuiltinAndDeleteRestores() {
        val store = MemStore()
        val book = IrCodeBook.load(assetText, store)
        val custom = IrProtocols.encode("nec1", 4, -1, 30).toProntoHex()
        val key = book.save(IrUserCode(brand = "LG", device = "tv", command = "电源", pronto = custom, savedAt = 1))
        assertTrue("KV 键必须是 ASCII 安全名：$key", key.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        assertEquals(key, irCodeKey("lg", "TV", "power")) // 别名归一后同键

        val reloaded = IrCodeBook.load(assetText, store)
        val entry = reloaded.entry("LG", "tv")!!
        val code = entry.command("power")!!
        assertEquals("user", code.source)
        assertEquals(custom, code.signal(entry).toProntoHex())
        assertEquals(1, entry.commands.count { it.value.source == "user" })

        assertTrue(reloaded.delete(key))
        val restored = IrCodeBook.load(assetText, store).entry("LG", "tv")!!.command("power")!!
        assertEquals("builtin", restored.source)
    }

    @Test
    fun userCodeCanAddNewBrand() {
        val store = MemStore()
        val book = IrCodeBook.load(assetText, store)
        book.save(
            IrUserCode(
                brand = "客厅空调",
                device = "ac",
                command = "power",
                protocol = "nec1",
                deviceNo = 0x20,
                subdeviceNo = -1,
                function = 0x0C,
                savedAt = 1,
            ),
        )
        val reloaded = IrCodeBook.load(assetText, store)
        val entry = reloaded.entry("客厅空调", "ac")
        assertNotNull(entry)
        val signal = entry!!.command("power")!!.signal(entry)
        assertEquals(38_000, signal.carrierHz)
        assertEquals(IrProtocols.encode("nec1", 0x20, -1, 0x0C).pattern.toList(), signal.pattern.toList())
    }

    /* ------------------------------ 工具函数 ------------------------------ */

    /** NEC 位反解：每位的 space 是 560（0）或 1690（1）。 */
    private fun necBits(pattern: IntArray, frame: Int): List<Int> {
        val bits = ArrayList<Int>(32)
        var index = frame * 68 + 2
        repeat(32) {
            bits += if (pattern[index + 1] > 1000) 1 else 0
            index += 2
        }
        return bits
    }

    /** Sony 位反解：每位的 mark 是 1200（1）或 600（0）。 */
    private fun sonyBits(pattern: IntArray, frame: Int): List<Int> {
        val bits = ArrayList<Int>(12)
        var index = frame * 26 + 2
        repeat(12) {
            bits += if (pattern[index] > 900) 1 else 0
            index += 2
        }
        return bits
    }

    /** RC5 曼彻斯特反解：半位先空后满是 1，先满后空是 0（首半位按惯例被裁掉）。 */
    private fun rc5Bits(pattern: IntArray): List<Int> {
        // 相邻同电平半位在波形里被合并成一个 1778 µs，这里按 889 拆回半位序列。
        val halves = ArrayList<Boolean>() // true = mark
        pattern.forEachIndexed { index, duration ->
            val mark = index % 2 == 0
            repeat(maxOf(1, Math.round(duration / 889.0).toInt())) { halves += mark }
        }
        val full = ArrayList<Boolean>(halves.size + 1)
        full += false // 补回起始位被裁掉的首个半位（低电平）
        full.addAll(halves)
        val bits = ArrayList<Int>(14)
        for (i in 0 until 14) bits += if (full[2 * i]) 0 else 1
        return bits
    }
}

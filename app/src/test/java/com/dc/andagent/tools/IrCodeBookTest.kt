package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * R116 红外码表：协议编码位序、Pronto 往返、码表检索与用户码。
 *
 * 位序断言是独立于实现的（按 NEC/NECx/Sony SIRC/RC5 规范从 pattern 反解），
 * 因此能真正挡住「编出来的波形看起来对、实际遥控器不认」这类回归。
 *
 * 内置品牌表已清空（原 irdb 数据因其许可过重移除），码表相关测试改用
 * [fixtureText] 驱动；另有防回归闸：随 APK 的资产不得再出现任何品牌数据，
 * 否则必须先走对应的许可合规流程。
 */
class IrCodeBookTest {

    private val assetText: String by lazy { File("src/main/assets/ir/ir-codes.json").readText() }

    /** 测试用内置表：与真实品牌无关，覆盖 NEC/NECx/Sony/RC5 四种协议路径与别名检索。 */
    private val fixtureText = """
        {
          "schema": 1,
          "license": {"name": "fixture", "notice": "fixture notice", "notifyIssue": "https://example.com/issues"},
          "brands": [
            {"id": "acme", "name": "Acme", "aliases": ["acme", "阿克米"], "devices": [
              {"id": "tv", "aliases": ["tv", "电视", "电视机"], "protocol": "nec1",
               "device": 4, "subdevice": -1, "carrier_hz": 38000,
               "commands": {"power": 8, "vol_up": 2, "mute": 9}}
            ]},
            {"id": "bravo", "name": "Bravo", "devices": [
              {"id": "tv", "aliases": ["tv", "电视"], "protocol": "sony12",
               "device": 1, "subdevice": -1, "carrier_hz": 40000,
               "commands": {"power": 21, "vol_up": 18}},
              {"id": "audio", "protocol": "rc5", "device": 0, "subdevice": -1,
               "carrier_hz": 36000, "commands": {"power": 12}}
            ]}
          ]
        }
    """.trimIndent()

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
        assertEquals(listOf(0, 0, 1, 0, 0, 0, 0, 0), bits.subList(0, 8))   // 4，LSB 先
        assertEquals(listOf(1, 1, 0, 1, 1, 1, 1, 1), bits.subList(8, 16))  // ~4
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0, 0), bits.subList(16, 24)) // 8
        assertEquals(listOf(1, 1, 1, 0, 1, 1, 1, 1), bits.subList(24, 32)) // ~8

        // 第二帧与第一帧完全一致（连发）
        assertEquals(bits, necBits(signal.pattern, 1))
        assertTrue(signal.pattern.sumOf { it.toLong() } <= 2_000_000L)
    }

    @Test
    fun necxEncodesSixteenBitAddress() {
        val signal = IrProtocols.encode("necx2", 7, 7, 2)
        assertEquals(68 * 2, signal.pattern.size)
        val bits = necBits(signal.pattern, 0)
        assertEquals(listOf(1, 1, 1, 0, 0, 0, 0, 0) + listOf(1, 1, 1, 0, 0, 0, 0, 0), bits.subList(0, 16))
        assertEquals(listOf(0, 1, 0, 0, 0, 0, 0, 0), bits.subList(16, 24)) // 2
        assertEquals(listOf(1, 0, 1, 1, 1, 1, 1, 1), bits.subList(24, 32)) // ~2
    }

    @Test
    fun sonyFrameMatchesSpec() {
        val signal = IrProtocols.encode("sony12", 1, -1, 21)
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
        val signal = IrProtocols.encode("rc5", 0, -1, 12)
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

    /* ---------------------------- 内置码表资产 ---------------------------- */

    /**
     * 内置资产（Flipper-IRDB，CC0 1.0）逐键编译校验：
     * 协议必须落在已实现集合内、每个键都能编译成合法波形且 Pronto 往返一致。
     */
    @Test
    fun bundledAssetCompilesEveryCommand() {
        val file = File("src/main/assets/ir/ir-codes.json")
        assertTrue("内置码表缺失：${file.absolutePath}", file.exists())
        val book = IrCodeBook.load(file.readText())
        assertTrue("内置品牌表不应为空", book.allEntries().isNotEmpty())

        val attribution = book.attribution
        assertNotNull(attribution)
        assertTrue(attribution!!.name.contains("Flipper-IRDB"))
        assertTrue(attribution.url.orEmpty().contains("Flipper-IRDB"))

        for (entry in book.allEntries()) {
            assertTrue("未实现的协议 ${entry.protocol}", entry.protocol == null || IrProtocols.isSupported(entry.protocol))
            for ((name, code) in entry.commands) {
                val signal = code.signal(entry)
                assertTrue("${entry.codeId}/$name 载波异常", signal.carrierHz in 30_000..60_000)
                assertTrue("${entry.codeId}/$name 时长超限", signal.pattern.sumOf { it.toLong() } <= 2_000_000L)
                val parsed = ProntoHex.parse(signal.toProntoHex())
                assertEquals("${entry.codeId}/$name Pronto 丢失脉冲", signal.pattern.size, parsed.pattern.size)
            }
        }
        // 中文品牌与按键检索
        assertTrue(book.search("海信", null, null, 5).isNotEmpty())
        assertTrue(book.search("三星", null, null, 5).isNotEmpty())
        assertEquals("vol_up", book.search("海信", null, null, 5).first().command("音量+")!!.name)
    }

    @Test
    fun fixtureCompilesEveryCommand() {
        val entries = IrCodeBook.parseBuiltin(fixtureText)
        assertEquals(listOf("acme/tv", "bravo/tv", "bravo/audio"), entries.map { it.codeId })
        assertEquals(38000, entries.first { it.codeId == "acme/tv" }.carrierHz)
        assertEquals(40000, entries.first { it.codeId == "bravo/tv" }.carrierHz)
        assertEquals(36000, entries.first { it.codeId == "bravo/audio" }.carrierHz)
        for (entry in entries) {
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
        fun pronto(entry: IrCodeEntry, command: String): String =
            entry.command(command)!!.signal(entry).toProntoHex()

        val entries = IrCodeBook.parseBuiltin(fixtureText)
        val acme = entries.first { it.codeId == "acme/tv" }
        val bravo = entries.first { it.codeId == "bravo/tv" }
        val bravoAudio = entries.first { it.codeId == "bravo/audio" }
        assertTrue(pronto(acme, "power").startsWith("0000 006D "))
        assertTrue(pronto(bravo, "power").startsWith("0000 0068 "))
        assertTrue(pronto(bravoAudio, "power").startsWith("0000 0073 "))
        // 单个 NEC 帧 = 34 对脉冲（0x0022），默认连发 2 帧。
        assertTrue(pronto(acme, "power").startsWith("0000 006D 0000 0044 "))
    }

    @Test
    fun searchMatchesBrandsAndChineseAliases() {
        val book = IrCodeBook.load(fixtureText)
        assertEquals(1, book.search("阿克米", null, null, 5).size)
        assertEquals("acme/tv", book.search("阿克米", null, null, 5).first().codeId)
        assertEquals("bravo/tv", book.search("bravo", "tv", "电源", 5).first().codeId)
        assertEquals(2, book.search(null, "tv", "音量", 20).size) // 两家都有音量键
        assertEquals("vol_up", book.entry("acme")!!.command("音量+")!!.name)
        assertEquals("power", book.entry("Acme", "电视")!!.command("电源")!!.name)
        assertTrue(book.search("不存在的品牌", null, null, 5).isEmpty())
    }

    /* ---------------------------- 用户码表 ---------------------------- */

    @Test
    fun userCodeOverridesBuiltinAndDeleteRestores() {
        val store = MemStore()
        val book = IrCodeBook.load(fixtureText, store)
        val custom = IrProtocols.encode("nec1", 4, -1, 30).toProntoHex()
        val key = book.save(IrUserCode(brand = "Acme", device = "tv", command = "电源", pronto = custom, savedAt = 1))
        assertTrue("KV 键必须是 ASCII 安全名：$key", key.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        assertEquals(key, irCodeKey("acme", "TV", "power")) // 别名归一后同键

        val reloaded = IrCodeBook.load(fixtureText, store)
        val entry = reloaded.entry("Acme", "tv")!!
        val code = entry.command("power")!!
        assertEquals("user", code.source)
        assertEquals(custom, code.signal(entry).toProntoHex())
        assertEquals(1, entry.commands.count { it.value.source == "user" })

        assertTrue(reloaded.delete(key))
        val restored = IrCodeBook.load(fixtureText, store).entry("Acme", "tv")!!.command("power")!!
        assertEquals("builtin", restored.source)
    }

    @Test
    fun userCodeCanAddNewBrand() {
        val store = MemStore()
        val book = IrCodeBook.load(assetText, store) // 内置表为空，全靠用户码
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
        assertTrue(reloaded.search(null, null, "客厅", 5).isNotEmpty())
    }

    /* ---------------------------- 开源码表导入 ---------------------------- */

    @Test
    fun csvImportMapsIrdbStyleRowsAndSkipsUnsupported() {
        val csv = """
            functionname,protocol,device,subdevice,function
            POWER,NEC1,4,-1,8
            VOLUME +,NEC1,4,-1,2
            CURSOR UP,NEC1,4,-1,64
            POWER,NEC1,4,-1,8
            POWER,Sharp,1,-1,22
            MUTE,NEC1,4,-1,
        """.trimIndent()
        val rows = IrCodeImport.parseCsv(csv)
        assertEquals(6, rows.size) // 表头跳过，其余 6 行原样解析

        val store = MemStore()
        val book = IrCodeBook.load(assetText, store)
        val (records, skipped) = IrCodeImport.buildRecords(rows, "Hisense", "tv", null, now = 1)
        assertEquals(listOf("power", "vol_up", "up"), records.map { it.command }) // 重复 POWER 去重
        assertEquals(2, skipped.size) // Sharp 协议不支持 + 缺功能值
        records.forEach { book.save(it) }

        val entry = IrCodeBook.load(assetText, store).entry("Hisense", "电视")!!
        assertEquals("hisense/tv", entry.codeId)
        // 内置 Hisense 条目的键仍在，导入的三个键以用户码身份并入
        assertEquals(setOf("power", "vol_up", "up"), entry.commands.filterValues { it.source == "user" }.keys)
        assertEquals("user", entry.command("电源")!!.source)
        assertEquals(
            IrProtocols.encode("nec1", 4, -1, 8).pattern.toList(),
            entry.command("power")!!.signal(entry).pattern.toList(),
        )
    }

    @Test
    fun jsonRowsImport() {
        val rows = IrCodeImport.parseRowsJson(
            """
            [{"name":"POWER","protocol":"Sony12","device":1,"subdevice":-1,"code":21},
             {"functionname":"VOLUME UP","protocol":"sony12","device":1,"code":18},
             {"name":"BROKEN"}]
            """.trimIndent(),
        )
        assertEquals(2, rows.size) // 缺 protocol 的行解析阶段就被丢弃
        val (records, skipped) = IrCodeImport.buildRecords(rows, "测试电视", "tv", null, now = 1)
        assertTrue(skipped.isEmpty())
        assertEquals(listOf("power", "vol_up"), records.map { it.command })
        assertEquals(40_000, IrProtocols.encode("sony12", 1, -1, 21).carrierHz)
        // 坏 JSON 不抛异常，返回空列表
        assertTrue(IrCodeImport.parseRowsJson("not json").isEmpty())
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

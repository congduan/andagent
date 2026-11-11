package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * R143 ①: stub 下载/启动 → 增强命令集 → MD5 全流程（脚本化 fake 串口），
 * 以及 stub 失败自动降级 ROM、chip_id 不匹配降级、ROM-only 行为不变。
 */
class EspStubFlashFlowTest {

    /**
     * 脚本化 fake：按 esptool.js stubFlasher.ts / esploader.ts 的响应契约回包。
     * ohaiEnabled=false 模拟 stub 启动失败（无 OHAI）→ 触发降级。
     */
    private class StubScriptedSerial(
        private val ohaiEnabled: Boolean = true,
        private val memStatusReply: Boolean = true,
        private val chipId: Int = 5,   // ESP32-C3
    ) : EspSerialIo {
        private var pending = ByteArray(0)
        val ops = mutableListOf<Int>()
        val flashBegins = mutableListOf<ByteArray>()
        val flashDataSizes = mutableListOf<Int>()
        val baudPayloads = mutableListOf<ByteArray>()
        val memBlocks = mutableListOf<ByteArray>()
        val burnedBlocks = mutableListOf<ByteArray>()
        var memEndCount = 0
        var stubStarted = false

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            if (pending.isEmpty()) return 0
            val n = minOf(buf.size, pending.size)
            pending.copyInto(buf, 0, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }

        override fun write(frame: ByteArray): Int {
            val payload = EspFlashCodec.slipDecode(frame)
            val op = payload[1].toInt() and 0xff
            val data = payload.copyOfRange(8, payload.size)
            ops += op
            when (op) {
                EspCmd.SYNC -> pending += response(op, value = 0x12345678)
                EspCmd.GET_SECURITY_INFO -> {
                    val info = ByteArray(20).also { EspFlashCodec.putLe32(it, 12, chipId) }
                    pending += response(op, body = info + byteArrayOf(0, 0))
                }
                EspCmd.MEM_BEGIN -> pending += response(op)
                EspCmd.MEM_DATA -> {
                    memBlocks += data.copyOfRange(16, data.size)  // 16B <IIII> 头 + chunk
                    pending += response(op)
                }
                EspCmd.MEM_END -> {
                    memEndCount++
                    if (memStatusReply) pending += response(op)     // MEM_END 迟到的 status 应答
                    if (ohaiEnabled) {
                        stubStarted = true
                        // OHAI 裸 SLIP 帧（py loader.py:1520 / js esploader.ts:1516）
                        pending += EspFlashCodec.slipEncode(EspStubLoader.OHAI)
                    }
                }
                EspCmd.CHANGE_BAUDRATE -> {
                    baudPayloads += data
                    pending += response(op)
                }
                EspCmd.FLASH_BEGIN -> {
                    flashBegins += data
                    pending += response(op)
                }
                EspCmd.FLASH_DATA -> {
                    flashDataSizes += EspFlashCodec.le32(data, 0)
                    burnedBlocks += data.copyOfRange(16, data.size)
                    pending += response(op)
                }
                EspCmd.SPI_FLASH_MD5 -> {
                    val size = EspFlashCodec.le32(data, 4)
                    val image = burned(size)
                    val raw = MessageDigest.getInstance("MD5").digest(image)
                    // py loader.py:1635-1638：ROM 回 32B ASCII hex，stub 回 16B 原始 digest
                    pending += response(op, body = if (stubStarted) raw + byteArrayOf(0, 0)
                        else EspFlashCodec.md5Hex(image).toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0))
                }
                else -> pending += response(op)   // SPI_ATTACH / SPI_SET_PARAMS / FLASH_END / READ_REG
            }
            return frame.size
        }

        fun burned(size: Int): ByteArray {
            val all = ByteArray(burnedBlocks.sumOf { it.size })
            var pos = 0
            for (b in burnedBlocks) { b.copyInto(all, pos); pos += b.size }
            return all.copyOfRange(0, size)
        }

        private fun response(opRet: Int, value: Int = 0, body: ByteArray = byteArrayOf(0, 0)): ByteArray {
            val h = ByteArray(8)
            h[0] = 0x01; h[1] = opRet.toByte()
            EspFlashCodec.putLe16(h, 2, body.size)
            EspFlashCodec.putLe32(h, 4, value)
            return EspFlashCodec.slipEncode(h + body)
        }
    }

    /** 一个可直接喂给 EspFlasher 的小 stub（两段：text 2 块 + data 1 块）。 */
    private fun testStub(chipId: Int = 5): EspStub = EspStub(
        chipId, 0x403D1234,
        listOf(
            EspStubSegment(0x403C0000, ByteArray(EspCmd.ESP_RAM_BLOCK + 10) { (it and 0xff).toByte() }),
            EspStubSegment(0x3FCF0000, ByteArray(204) { (it xor 0x5a).toByte() }),
        ),
    )

    private fun stubProvider(stub: EspStub): (EspChip) -> EspStub? = { stub }

    // ---------- stub 成功路径 ----------
    @Test fun stubFullFlashSuccessUsesEnhancedCommandSet() {
        val image = ByteArray(40000) { (it % 251).toByte() }
        val serial = StubScriptedSerial()
        val r = EspFlasher(serial, { _, _ -> }, { }, stubProvider = stubProvider(testStub()))
            .flash(image, 0x10000, 4 * 1024 * 1024)

        assertEquals("stub", r.loader)
        assertEquals("ESP32-C3", r.chip)
        assertEquals(5, r.chipId)
        assertTrue("stub 模式 MD5 应校验通过", r.verified)
        assertEquals(EspFlashCodec.md5Hex(image), r.md5)
        assertEquals(40000, r.bytes)

        // 下载序列：MEM_BEGIN ×2（两段）+ MEM_DATA ×3（text 2 块 + data 1 块）+ MEM_END ×1
        assertEquals(2, serial.ops.count { it == EspCmd.MEM_BEGIN })
        assertEquals(3, serial.ops.count { it == EspCmd.MEM_DATA })
        assertEquals(1, serial.memEndCount)
        assertEquals(EspCmd.ESP_RAM_BLOCK + 10 + 204, serial.memBlocks.sumOf { it.size })
        // 第一段分 0x1800 + 10 两块，第二段 204B 一块
        assertEquals(listOf(EspCmd.ESP_RAM_BLOCK, 10, 204), serial.memBlocks.map { it.size })

        // stub 跳过 SPI_ATTACH / SPI_SET_PARAMS（stub 自初始化 SPI）
        assertFalse(serial.ops.contains(EspCmd.SPI_ATTACH))
        assertFalse(serial.ops.contains(EspCmd.SPI_SET_PARAMS))

        // FLASH_BEGIN：stub 模式追加第 5 字、块大小 0x4000
        val fb = serial.flashBegins.single()
        assertEquals(20, fb.size)
        assertEquals(EspCmd.STUB_FLASH_WRITE_SIZE, EspFlashCodec.le32(fb, 8))
        assertEquals(0, EspFlashCodec.le32(fb, 16))
        // 40000B / 0x4000 = 3 块
        assertEquals(3, r.blocks)
        assertEquals(listOf(0x4000, 0x4000, 0x4000), serial.flashDataSizes)
        // 末块补 0xFF 到 0x4000
        assertEquals(0xFF.toByte(), serial.burnedBlocks.last()[0x4000 - 1])

        // 末尾 FLASH_END + 硬复位仍在
        assertTrue(serial.ops.contains(EspCmd.FLASH_END))
        assertArrayEquals(image, serial.burned(40000))
    }

    @Test fun stubModeMd5Uses16RawBytesNotAsciiHex() {
        val image = ByteArray(2500) { (it % 7).toByte() }
        val serial = StubScriptedSerial(memStatusReply = false)
        val r = EspFlasher(serial, { _, _ -> }, { }, stubProvider = stubProvider(testStub()))
            .flash(image, 0x10000, 4 * 1024 * 1024)
        assertEquals("stub", r.loader)
        assertTrue("stub MD5(16B raw) 应校验通过", r.verified)
        // 确认 fake 回的是 16B 原始 digest（不是 32B ASCII）：由 stubStarted 决定，若误用 32B 则校验会失败
        assertEquals(1, r.blocks)
    }

    @Test fun stubChangeBaudrateSecondArgIsOldBaud() {
        // py loader.py:1644 / js esploader.ts:1553：stub 模式 second = romBaudrate，ROM 模式 second = 0
        var currentBaud = 115200
        val calls = mutableListOf<Int>()
        val serial = StubScriptedSerial()
        val r = EspFlasher(serial, { _, _ -> }, { },
            setBaud = { b -> calls += b; currentBaud = b },
            initialBaud = 115200,
            stubProvider = stubProvider(testStub()),
        ).flash(ByteArray(1024) { (it % 3).toByte() }, 0x10000, 4 * 1024 * 1024, baud = 921600)

        val p = serial.baudPayloads.single()
        assertEquals(8, p.size)
        assertEquals(921600, EspFlashCodec.le32(p, 0))
        assertEquals(115200, EspFlashCodec.le32(p, 4))   // 旧波特率，非 0
        assertEquals(listOf(921600, 115200), calls)
        assertEquals(921600, r.baud)
        assertEquals("stub", r.loader)
        assertTrue(r.verified)
    }

    // ---------- 失败自动降级 ROM ----------
    @Test fun stubStartFailureDegradesToRomWithoutFailingFlash() {
        val image = ByteArray(2500) { (it % 251).toByte() }
        val serial = StubScriptedSerial(ohaiEnabled = false)   // 无 OHAI → 启动失败
        val r = EspFlasher(serial, { _, _ -> }, { }, stubProvider = stubProvider(testStub()))
            .flash(image, 0x10000, 4 * 1024 * 1024)

        assertEquals("rom", r.loader)
        assertTrue("降级后 ROM 烧录仍应成功", r.verified)
        assertTrue(r.warning!!.contains("降级 ROM 模式"))
        // 降级后走了完全保留的 ROM 路径：SPI_ATTACH + SPI_SET_PARAMS、1KB 块、32B ASCII MD5
        assertTrue(serial.ops.contains(EspCmd.SPI_ATTACH))
        assertTrue(serial.ops.contains(EspCmd.SPI_SET_PARAMS))
        val fb = serial.flashBegins.last()
        // C3 ROM 非 ESP32/8266 → 追加第 5 字，故 20B；块大小 0x400
        assertEquals(20, fb.size)
        assertEquals(EspCmd.FLASH_WRITE_SIZE, EspFlashCodec.le32(fb, 8))
        assertEquals(3, r.blocks)                       // 2500B / 0x400 = 3
        assertEquals(listOf(0x400, 0x400, 0x400), serial.flashDataSizes.takeLast(3))
        assertArrayEquals(image, serial.burned(2500))
    }

    @Test fun noStubOrChipIdMismatchKeepsRomPathUnchanged() {
        val image = ByteArray(1024) { (it % 11).toByte() }
        // (a) stubProvider=null：与 R123 完全一致
        val a = StubScriptedSerial(ohaiEnabled = false)
        val ra = EspFlasher(a, { _, _ -> }, { }).flash(image, 0x10000, 4 * 1024 * 1024)
        assertEquals("rom", ra.loader)
        assertTrue(ra.verified)
        assertFalse(a.ops.contains(EspCmd.MEM_BEGIN))

        // (b) provider 返回 null（如 chip_id 不匹配 / 无该芯片 stub）→ 同样降级 ROM
        val b = StubScriptedSerial(ohaiEnabled = false)
        val rb = EspFlasher(b, { _, _ -> }, { }, stubProvider = { null })
            .flash(image, 0x10000, 4 * 1024 * 1024)
        assertEquals("rom", rb.loader)
        assertTrue(rb.verified)
        assertFalse(b.ops.contains(EspCmd.MEM_BEGIN))
    }

    @Test fun stubDownloadCommandFailureDegradesToRom() {
        // MEM_DATA 被拒（status=1）→ loadStub 抛错 → 降级 ROM；不把整个烧录搞挂
        val image = ByteArray(1024) { (it % 13).toByte() }
        val serial = object : EspSerialIo {
            private var pending = ByteArray(0)
            val ops = mutableListOf<Int>()
            val blocks = mutableListOf<ByteArray>()
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                if (pending.isEmpty()) return 0
                val n = minOf(buf.size, pending.size)
                pending.copyInto(buf, 0, 0, n); pending = pending.copyOfRange(n, pending.size)
                return n
            }
            override fun write(frame: ByteArray): Int {
                val p = EspFlashCodec.slipDecode(frame)
                val op = p[1].toInt() and 0xff
                val data = p.copyOfRange(8, p.size)
                ops += op
                val resp: ByteArray = when (op) {
                    EspCmd.SYNC -> response(op, value = 0x12345678)
                    EspCmd.GET_SECURITY_INFO -> {
                        val info = ByteArray(20).also { EspFlashCodec.putLe32(it, 12, 5) }
                        response(op, body = info + byteArrayOf(0, 0))
                    }
                    EspCmd.MEM_DATA -> response(op, body = byteArrayOf(1, 0x05))  // 失败
                    EspCmd.FLASH_DATA -> { blocks += data.copyOfRange(16, data.size); response(op) }
                    EspCmd.SPI_FLASH_MD5 -> {
                        val size = EspFlashCodec.le32(data, 4)
                        val all = ByteArray(blocks.sumOf { it.size })
                        var pos = 0; for (b in blocks) { b.copyInto(all, pos); pos += b.size }
                        val img = all.copyOfRange(0, size)
                        response(op, body = EspFlashCodec.md5Hex(img).toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0))
                    }
                    else -> response(op)
                }
                pending += resp
                return frame.size
            }
            private fun response(op: Int, value: Int = 0, body: ByteArray = byteArrayOf(0, 0)): ByteArray {
                val h = ByteArray(8); h[0] = 1; h[1] = op.toByte()
                EspFlashCodec.putLe16(h, 2, body.size); EspFlashCodec.putLe32(h, 4, value)
                return EspFlashCodec.slipEncode(h + body)
            }
        }
        val r = EspFlasher(serial, { _, _ -> }, { }, stubProvider = stubProvider(testStub()))
            .flash(image, 0x10000, 4 * 1024 * 1024)
        assertEquals("rom", r.loader)
        assertTrue(r.verified)
        assertTrue(r.warning!!.contains("降级 ROM 模式"))
    }
}

package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R123 ①: ESP32 ROM bootloader 烧录协议字节级断言 + 脚本化 fake 串口全流程。 */
class EspFlashCodecTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(Regex("\\s"), "")
        require(clean.length % 2 == 0)
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun responseFrame(opRet: Int, value: Int = 0, body: ByteArray = byteArrayOf(0, 0)): ByteArray {
        val header = ByteArray(8)
        header[0] = 0x01
        header[1] = opRet.toByte()
        EspFlashCodec.putLe16(header, 2, body.size)
        EspFlashCodec.putLe32(header, 4, value)
        return EspFlashCodec.slipEncode(header + body)
    }

    // ---------- SLIP ----------
    @Test fun slipEncodeEscapesReservedBytes() {
        // payload: 0xC0 0xDB 0x00 0xC0 → C0 [DB DC][DB DD][00][DB DC] C0
        val payload = hex("c0db00c0")
        assertArrayEquals(hex("c0 dbdc dbdd 00 dbdc c0".replace(" ", "")),
            EspFlashCodec.slipEncode(payload))
    }

    @Test fun slipDecodeRoundTrip() {
        val payload = hex("00 01 c0 db 7f c0 02".replace(" ", ""))
        assertArrayEquals(payload, EspFlashCodec.slipDecode(EspFlashCodec.slipEncode(payload)))
    }

    @Test fun slipDecodeRejectsBadEscape() {
        // (0xDB, 非 0xDD/0xDC) 必须报错
        assertThrows(IllegalArgumentException::class.java) {
            EspFlashCodec.slipDecode(hex("c0db00c0"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspFlashCodec.slipDecode(hex("c0db"))   // 转义序列不完整
        }
    }

    // ---------- 请求头 / SYNC ----------
    @Test fun requestHeaderEncodesLittleEndian() {
        // SYNC: 头 00 08 24 00 00000000 + data(36B)
        val req = EspFlashCodec.request(EspCmd.SYNC, EspFlashCodec.syncPayload())
        assertArrayEquals(hex("0008240000000000" + "07071220" + "55".repeat(32)), req)
        // 自定义 chk 亦按 u32 LE 进头部
        val req2 = EspFlashCodec.request(EspCmd.FLASH_DATA, byteArrayOf(0x01), chk = 0x12345678)
        assertArrayEquals(hex("000301007856341201"), req2)
        assertEquals(0x12345678, EspFlashCodec.le32(req2, 4))
    }

    @Test fun syncPayloadIsFixed36Bytes() {
        val p = EspFlashCodec.syncPayload()
        assertEquals(36, p.size)
        assertArrayEquals(hex("07071220" + "55".repeat(32)), p)
    }

    @Test fun checksumStartsAtEfAndXorsData() {
        assertEquals(0xEF, EspFlashCodec.checksum(byteArrayOf()))          // data 空 = 0xEF
        assertEquals(0xDF, EspFlashCodec.checksum(byteArrayOf(0x10, 0x20)))
        assertEquals(0xEF xor 0x01 xor 0x02 xor 0x03, EspFlashCodec.checksum(byteArrayOf(1, 2, 3)))
    }

    // ---------- 响应解析（OK / 错误状态） ----------
    @Test fun parseResponseOkAndErrorStatus() {
        val okFrame = responseFrame(EspCmd.FLASH_BEGIN, value = 0x12345678)
        val ok = EspFlashCodec.parseResponse(EspFlashCodec.slipDecode(okFrame))!!
        assertEquals(1, ok.direction)
        assertEquals(EspCmd.FLASH_BEGIN, ok.op)
        assertEquals(2, ok.len)
        assertEquals(0x12345678, ok.value)
        assertArrayEquals(byteArrayOf(0, 0), ok.body)

        val errFrame = responseFrame(EspCmd.FLASH_BEGIN, body = byteArrayOf(1, 0x05))
        val err = EspFlashCodec.parseResponse(EspFlashCodec.slipDecode(errFrame))!!
        assertArrayEquals(byteArrayOf(1, 0x05), err.body)   // status[0]!=0 → 调用方视为失败
        assertNull(EspFlashCodec.parseResponse(ByteArray(7)))  // 过短返回 null
    }

    // ---------- 命令 payload 编码 ----------
    @Test fun flashBeginAndDataParamsEncoding() {
        val fb = EspFlashCodec.flashBeginParams(0x3000, 2, 0x10000)
        assertEquals(16, fb.size)
        assertEquals(0x3000, EspFlashCodec.le32(fb, 0))
        assertEquals(2, EspFlashCodec.le32(fb, 4))
        assertEquals(0x400, EspFlashCodec.le32(fb, 8))
        assertEquals(0x10000, EspFlashCodec.le32(fb, 12))
        // 非 ESP32/ESP8266 ROM 追加第 5 个 <I>(0)
        val fbx = EspFlashCodec.flashBeginParams(0x3000, 2, 0x10000, extended = true)
        assertEquals(20, fbx.size)
        assertEquals(0, EspFlashCodec.le32(fbx, 16))

        val fd = EspFlashCodec.flashDataHeader(1024, 3)
        assertEquals(1024, EspFlashCodec.le32(fd, 0))
        assertEquals(3, EspFlashCodec.le32(fd, 4))
        assertEquals(0, EspFlashCodec.le32(fd, 8))
        assertEquals(0, EspFlashCodec.le32(fd, 12))

        assertArrayEquals(hex("00000000"), EspFlashCodec.flashEndParams(reboot = true))
        assertArrayEquals(hex("01000000"), EspFlashCodec.flashEndParams(reboot = false))
        assertArrayEquals(ByteArray(8), EspFlashCodec.spiAttachParams())

        val md5 = EspFlashCodec.md5Params(0x10000, 0x1000)
        assertEquals(0x10000, EspFlashCodec.le32(md5, 0))
        assertEquals(0x1000, EspFlashCodec.le32(md5, 4))

        val sp = EspFlashCodec.spiSetParams(0x400000)
        assertEquals(24, sp.size)
        assertEquals(0, EspFlashCodec.le32(sp, 0))
        assertEquals(0x400000, EspFlashCodec.le32(sp, 4))
        assertEquals(65536, EspFlashCodec.le32(sp, 8))
        assertEquals(4096, EspFlashCodec.le32(sp, 12))
        assertEquals(256, EspFlashCodec.le32(sp, 16))
        assertEquals(0xFFFF, EspFlashCodec.le32(sp, 20))
    }

    @Test fun flashDataChecksumGoesIntoHeader() {
        val block = ByteArray(1024) { (it and 0xff).toByte() }
        val chk = EspFlashCodec.checksum(block)
        val req = EspFlashCodec.request(EspCmd.FLASH_DATA,
            EspFlashCodec.flashDataHeader(1024, 0) + block, chk)
        assertEquals(EspCmd.FLASH_DATA, req[1].toInt() and 0xff)
        assertEquals(1040, EspFlashCodec.le16(req, 2))   // 16B 头 + 1024B block
        assertEquals(chk, EspFlashCodec.le32(req, 4))
    }

    // ---------- ESP8266 erase 公式 / 对齐 / MD5 / 芯片映射 ----------
    @Test fun esp8266EraseSizeCases() {
        // 对照 esptool ESP8266ROM.get_erase_size
        assertEquals(0x1000, EspFlashCodec.esp8266EraseSize(0, 0x1000))          // 1 sector
        assertEquals(0x8000, EspFlashCodec.esp8266EraseSize(0, 0x10000))         // 16 sectors → (16+1)/2
        assertEquals(0x10000, EspFlashCodec.esp8266EraseSize(0, 0x20000))        // 32 sectors → 32-16
        assertEquals(0x12000, EspFlashCodec.esp8266EraseSize(0x2000, 0x20000))   // head=14 → 32-14
    }

    @Test fun alignImageSectorAlignmentAndPad4() {
        val a = EspFlashCodec.alignImage(byteArrayOf(1, 2, 3), 0x10000)
        assertEquals(0x10000, a.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3, 0xFF.toByte()), a.data)

        val b = EspFlashCodec.alignImage(byteArrayOf(1, 2), 0x10023)   // bytesOver = 0x23
        assertEquals(0x10000, b.offset)
        assertEquals(35 + 4, b.data.size)
        for (i in 0 until 35) assertEquals(0xFF.toByte(), b.data[i])
        assertEquals(1, b.data[35].toInt() and 0xff)
        assertEquals(2, b.data[36].toInt() and 0xff)
        assertEquals(0xFF.toByte(), b.data[37])
        assertEquals(0xFF.toByte(), b.data[38])
    }

    @Test fun md5HexKnownVector() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", EspFlashCodec.md5Hex("abc".toByteArray()))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", EspFlashCodec.md5Hex(byteArrayOf()))
    }

    @Test fun chipNameMapping() {
        assertEquals("ESP32-C3", EspFlashCodec.chipNameForId(5))
        assertEquals("ESP32-S3", EspFlashCodec.chipNameForId(9))
        assertEquals("ESP32-C2", EspFlashCodec.chipNameForId(12))
        assertEquals("ESP32-C6", EspFlashCodec.chipNameForId(13))
        assertEquals("ESP32-H2", EspFlashCodec.chipNameForId(16))
        assertNull(EspFlashCodec.chipNameForId(999))
        assertEquals("ESP8266", EspFlashCodec.chipNameForMagic(0xFFF0C101L.toInt()))
        assertEquals("ESP32", EspFlashCodec.chipNameForMagic(0x00F01D83))
        assertEquals("ESP32-S2", EspFlashCodec.chipNameForMagic(0x000007C6))
        assertNull(EspFlashCodec.chipNameForMagic(0))
    }

    @Test fun resetStepSequences() {
        // 布尔约定：True=asserted（active-low 把线拉低）；对照 esp_pylib classic/hard reset
        assertEquals(listOf(
            EspReset.Step(false, true, 100),
            EspReset.Step(true, false, 50),
            EspReset.Step(false, false, 0),
        ), EspReset.enterBootloader())
        assertEquals(listOf(
            EspReset.Step(false, true, 100),
            EspReset.Step(false, false, 0),
        ), EspReset.hardReset())
    }

    // ---------- 脚本化 fake 串口 ----------
    private class ScriptedEspSerial(
        private val mode: Mode,
        private val changeBaudMode: ChangeBaudMode = ChangeBaudMode.OK,
        private val baudProbe: () -> Int = { 0 },
    ) : EspSerialIo {
        enum class Mode { ESP32_MAGIC, ESP32_S3_CHIPID, FLASH_BEGIN_STATUS_ERROR }
        enum class ChangeBaudMode { OK, FAIL }
        private var pending = ByteArray(0)
        private val blocks = mutableListOf<ByteArray>()
        val receivedOps = mutableListOf<Pair<Int, Int>>()   // (op, 收到请求时的波特率)
        val baudPayloads = mutableListOf<ByteArray>()       // CHANGE_BAUDRATE 的原始 payload

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
            receivedOps += op to baudProbe()
            if (op == EspCmd.CHANGE_BAUDRATE) baudPayloads += data
            val resp = when (op) {
                EspCmd.SYNC -> response(EspCmd.SYNC, value = 0x12345678)
                EspCmd.CHANGE_BAUDRATE -> when (changeBaudMode) {
                    ChangeBaudMode.OK -> response(EspCmd.CHANGE_BAUDRATE)
                    ChangeBaudMode.FAIL -> response(EspCmd.CHANGE_BAUDRATE, body = byteArrayOf(1, 0x05))
                }
                EspCmd.GET_SECURITY_INFO -> when (mode) {
                    Mode.ESP32_S3_CHIPID -> {
                        val info = ByteArray(20).also { EspFlashCodec.putLe32(it, 12, 9) } // chip_id=9 → S3
                        response(EspCmd.GET_SECURITY_INFO, body = info + byteArrayOf(0, 0))
                    }
                    else -> response(opRet = 0x00, body = byteArrayOf(1, 0x05)) // 命令不支持
                }
                EspCmd.READ_REG -> response(EspCmd.READ_REG, value = 0x00F01D83) // ESP32 magic
                EspCmd.FLASH_BEGIN -> if (mode == Mode.FLASH_BEGIN_STATUS_ERROR)
                    response(EspCmd.FLASH_BEGIN, body = byteArrayOf(1, 2)) else response(EspCmd.FLASH_BEGIN)
                EspCmd.FLASH_DATA -> {
                    blocks += data.copyOfRange(16, data.size) // 16B <IIII> 头 + 1024B block
                    response(EspCmd.FLASH_DATA)
                }
                EspCmd.SPI_FLASH_MD5 -> {
                    val size = EspFlashCodec.le32(data, 4)
                    val image = burned(size)
                    response(EspCmd.SPI_FLASH_MD5,
                        body = EspFlashCodec.md5Hex(image).toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0))
                }
                else -> response(op) // SPI_ATTACH / SPI_SET_PARAMS / FLASH_END → OK
            }
            pending += resp
            return frame.size
        }

        /** 取回已烧录的前 size 字节（拼接所有 FLASH_DATA 块）。 */
        fun burned(size: Int): ByteArray {
            val total = blocks.sumOf { it.size }
            val all = ByteArray(total)
            var pos = 0
            for (b in blocks) { b.copyInto(all, pos); pos += b.size }
            return all.copyOfRange(0, size)
        }

        private fun response(opRet: Int, value: Int = 0, body: ByteArray = byteArrayOf(0, 0)): ByteArray {
            val header = ByteArray(8)
            header[0] = 0x01
            header[1] = opRet.toByte()
            EspFlashCodec.putLe16(header, 2, body.size)
            EspFlashCodec.putLe32(header, 4, value)
            return EspFlashCodec.slipEncode(header + body)
        }
    }

    @Test fun detectChipViaSecurityInfoChipId() {
        val chip = EspFlasher(ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_S3_CHIPID),
            { _, _ -> }, { }).detectChip()
        assertEquals("ESP32-S3", chip.name)
        assertEquals(9, chip.chipId)
    }

    @Test fun detectChipViaMagicValue() {
        val chip = EspFlasher(ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_MAGIC),
            { _, _ -> }, { }).detectChip()
        assertEquals("ESP32", chip.name)
        assertNull(chip.chipId)
    }

    @Test fun scriptedFakeFullFlashSuccessWithMd5() {
        val image = ByteArray(2500) { (it % 251).toByte() }
        val controls = mutableListOf<Pair<Boolean, Boolean>>()
        val sleeps = mutableListOf<Long>()
        val r = EspFlasher(
            ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_MAGIC),
            { d, rts -> controls += d to rts },
            { sleeps += it },
        ).flash(image, 0x10000, 4 * 1024 * 1024)

        assertEquals("ESP32", r.chip)
        assertEquals(0x10000, r.offset)
        assertEquals(2500, r.bytes)
        assertEquals(3, r.blocks)
        assertEquals(EspFlashCodec.md5Hex(image), r.md5)
        assertTrue("MD5 应校验通过", r.verified)
        assertNull(r.mac)
        assertTrue(r.elapsedMs >= 0)
        // connect 的 enterBootloader + 末尾 hardReset 都触发了对应控制线/延时
        assertTrue(controls.contains(false to true))   // DTR=H,RTS=L（EN 低复位）
        assertTrue(controls.contains(true to false))   // DTR=L,RTS=H（出复位）
        assertTrue(sleeps.contains(100L))
        assertTrue(sleeps.contains(50L))
    }

    @Test fun errorStatusAbortsFlash() {
        val ex = assertThrows(EspFlashException::class.java) {
            EspFlasher(ScriptedEspSerial(ScriptedEspSerial.Mode.FLASH_BEGIN_STATUS_ERROR),
                { _, _ -> }, { }).flash(ByteArray(1024), 0x10000, 4 * 1024 * 1024)
        }
        assertTrue(ex.message!!.contains("失败"))
    }

    // ---------- CHANGE_BAUDRATE 切速 / ELF 自动转换（R123②） ----------
    /** 最小 32 位 LSB ELF：ELF 头(52) + phnum×32 program header + 段数据。 */
    private fun buildMinimalElf(entry: Int, loads: List<Pair<Int, ByteArray>>): ByteArray {
        val ehsize = 52
        val phoff = ehsize
        val dataOff = phoff + loads.size * 32
        val b = ByteArray(dataOff + loads.sumOf { it.second.size })
        b[0] = 0x7F; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 1; b[5] = 1; b[6] = 1                       // ELFCLASS32 / ELFDATA2LSB
        EspFlashCodec.putLe32(b, 0x18, entry)              // e_entry
        EspFlashCodec.putLe32(b, 0x1C, phoff)              // e_phoff
        EspFlashCodec.putLe16(b, 0x2A, 32)                 // e_phentsize
        EspFlashCodec.putLe16(b, 0x2C, loads.size)         // e_phnum
        var off = dataOff
        loads.forEachIndexed { i, (addr, data) ->
            val p = phoff + i * 32
            EspFlashCodec.putLe32(b, p + 0, 1)             // PT_LOAD
            EspFlashCodec.putLe32(b, p + 4, off)           // p_offset
            EspFlashCodec.putLe32(b, p + 8, addr)          // p_vaddr
            EspFlashCodec.putLe32(b, p + 12, addr)         // p_paddr (LMA)
            EspFlashCodec.putLe32(b, p + 16, data.size)    // p_filesz
            EspFlashCodec.putLe32(b, p + 20, data.size)    // p_memsz
            data.copyInto(b, off)
            off += data.size
        }
        return b
    }

    @Test fun flashChangeBaudrateSwitchesAfterSyncAndRestores() {
        var currentBaud = 115200
        val baudCalls = mutableListOf<Int>()
        val serial = ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_MAGIC,
            baudProbe = { currentBaud })
        val r = EspFlasher(
            serial,
            { _, _ -> }, { },
            setBaud = { b -> baudCalls += b; currentBaud = b },
            initialBaud = 115200,
        ).flash(ByteArray(1024) { (it % 7).toByte() }, 0x10000, 4 * 1024 * 1024, baud = 921600)

        // 0x0F 请求在旧波特率上应答，后续 FLASH 命令在新波特率上收发
        assertTrue(serial.receivedOps.contains(EspCmd.CHANGE_BAUDRATE to 115200))
        // payload=<II(new, old)> 8 字节（esptool change_baud 同构，ROM 读第一字）
        val baudPayload = serial.baudPayloads.single()
        assertEquals(8, baudPayload.size)
        assertEquals(921600, EspFlashCodec.le32(baudPayload, 0))
        assertEquals(115200, EspFlashCodec.le32(baudPayload, 4))
        assertTrue(serial.receivedOps.contains(EspCmd.FLASH_BEGIN to 921600))
        assertTrue(serial.receivedOps.any { it.first == EspCmd.FLASH_DATA && it.second == 921600 })
        assertTrue(serial.receivedOps.any { it.first == EspCmd.SPI_FLASH_MD5 && it.second == 921600 })
        // setBaud：先切 921600，MD5 校验后恢复 115200
        assertEquals(listOf(921600, 115200), baudCalls)
        assertEquals(921600, r.baud)
        assertEquals("bin", r.source)
        assertTrue("切速成功仍应 MD5 校验通过", r.verified)
    }

    @Test fun flashBaudrateSwitchFailureDegradesWithoutFailingFlash() {
        var currentBaud = 115200
        val baudCalls = mutableListOf<Int>()
        val serial = ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_MAGIC,
            changeBaudMode = ScriptedEspSerial.ChangeBaudMode.FAIL,
            baudProbe = { currentBaud })
        val r = EspFlasher(
            serial,
            { _, _ -> }, { },
            setBaud = { b -> baudCalls += b; currentBaud = b },
            initialBaud = 115200,
        ).flash(ByteArray(1024) { (it % 7).toByte() }, 0x10000, 4 * 1024 * 1024, baud = 921600)

        // 切速失败降级：全部命令仍在原波特率收发，从未真正切到 921600
        assertTrue(serial.receivedOps.all { it.second == 115200 })
        assertTrue(baudCalls.none { it == 921600 })
        assertTrue(baudCalls.contains(115200))
        assertEquals(115200, r.baud)
        assertEquals("bin", r.source)
        assertTrue(r.warning!!.contains("baud 切换失败已降级"))
        assertTrue("降级后烧录仍应成功", r.verified)
    }

    @Test fun flashElfInputReturnsSourceElfAndBurnsConvertedImage() {
        // 最小 ELF：单个 RAM 段（0x3FF80000, 4B）→ ESP32 扩展头镜像
        val elf = buildMinimalElf(0x40080934, listOf(0x3FF80000 to byteArrayOf(1, 2, 3, 4)))
        val converted = EspImage.fromElf(elf).toBinary(EspChip("ESP32", null))
        val serial = ScriptedEspSerial(ScriptedEspSerial.Mode.ESP32_MAGIC)
        val r = EspFlasher(serial, { _, _ -> }, { })
            .flash(elf, 0x10000, 4 * 1024 * 1024)

        assertEquals("elf", r.source)
        assertEquals(converted.size, r.bytes)
        assertArrayEquals(converted, serial.burned(converted.size))
        assertEquals(0xE9, converted[0].toInt() and 0xff)   // 烧的是 ESP image 而非裸 ELF
        assertTrue(r.verified)
    }
}

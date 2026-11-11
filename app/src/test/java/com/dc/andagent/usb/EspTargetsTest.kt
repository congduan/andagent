package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R143 ②③: targets/ 全芯片参数表 + 命令编码 + 时序/错误处理常量，逐项对照官方源码抄值断言。
 * 数据来自 /tmp/esptool-src（esptool HEAD 2026-11-09）与 /tmp/esptool-js-src（esptool-js 0.7.1 d5b779b），
 * 具体文件:行号见 EspTargets.kt 与战报。
 */
class EspTargetsTest {

    private fun hex(s: String): ByteArray = s.replace(Regex("\\s"), "").chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()

    // ---------- 全芯片参数表（官方值 vs 我们值）----------
    @Test fun officialChipIdsAndBootOffsets() {
        // esptool.py targets/*.py IMAGE_CHIP_ID / BOOTLOADER_FLASH_OFFSET
        val expected = mapOf(
            "ESP32" to (0 to 0x1000),
            "ESP32-S2" to (2 to 0x1000),
            "ESP32-S3" to (9 to 0x0),
            "ESP32-C3" to (5 to 0x0),
            "ESP32-C2" to (12 to 0x0),
            "ESP32-C6" to (13 to 0x0),
            "ESP32-H2" to (16 to 0x0),
            "ESP32-P4" to (18 to 0x2000),
        )
        for ((name, pair) in expected) {
            val p = EspTargets.byName(name)!!
            assertEquals("$name chip_id", pair.first, p.chipId)
            assertEquals("$name boot_off", pair.second, p.bootOff)
            assertEquals("$name rom block", 0x400, p.romFlashWriteSize)
            assertEquals("$name stub block", 0x4000, p.stubFlashWriteSize)
            assertTrue("$name extended header", p.usesExtendedHeader)
        }
        // ESP8266 无 GET_SECURITY_INFO chip_id、无扩展头、boot_off=0
        val e = EspTargets.ESP8266
        assertNull("ESP8266 chip_id", e.chipId)
        assertEquals(0, e.bootOff)
        assertFalse("ESP8266 V1 无扩展头", e.usesExtendedHeader)
        assertEquals(0xFFF0C101L.toInt(), e.magicValue)
    }

    @Test fun officialIromDromMemoryMaps() {
        // esptool.py targets/*.py IROM_MAP_*/DROM_MAP_*（H2 继承 C6）
        data class MapRow(val name: String, val iS: Long, val iE: Long, val dS: Long, val dE: Long)
        val rows = listOf(
            MapRow("ESP32", 0x400D0000L, 0x40400000L, 0x3F400000L, 0x3F800000L),
            MapRow("ESP32-S2", 0x40080000L, 0x40B80000L, 0x3F000000L, 0x3F3F0000L),
            MapRow("ESP32-S3", 0x42000000L, 0x44000000L, 0x3C000000L, 0x3E000000L),
            MapRow("ESP32-C3", 0x42000000L, 0x42800000L, 0x3C000000L, 0x3C800000L),
            MapRow("ESP32-C2", 0x42000000L, 0x42400000L, 0x3C000000L, 0x3C400000L),
            MapRow("ESP32-C6", 0x42000000L, 0x42800000L, 0x42800000L, 0x43000000L),
            MapRow("ESP32-H2", 0x42000000L, 0x42800000L, 0x42800000L, 0x43000000L),
            MapRow("ESP32-P4", 0x40000000L, 0x4C000000L, 0x40000000L, 0x4C000000L),
        )
        for (r in rows) {
            val p = EspTargets.byName(r.name)!!
            assertEquals("${r.name} IROM.start", r.iS, p.iromStart)
            assertEquals("${r.name} IROM.end", r.iE, p.iromEnd)
            assertEquals("${r.name} DROM.start", r.dS, p.dromStart)
            assertEquals("${r.name} DROM.end", r.dE, p.dromEnd)
            assertTrue("${r.name} IROM 地址在 MAP 内", p.isFlashAddr(r.iS))
            assertFalse("${r.name} MAP 之外不算 flash", p.isFlashAddr(0x1000L))
        }
        // ESP8266 MEMORY_MAP IROM（py esp8266.py:67 / js esp8266.ts:45）；
        // js 的 IROM_MAP_START/END(:19-20) 为较粗的 0x40200000..0x40300000，见战报。
        assertEquals(0x40201010L, EspTargets.ESP8266.iromStart)
        assertEquals(0x402E1010L, EspTargets.ESP8266.iromEnd)
    }

    @Test fun officialMagicValuesAndChipNameLookup() {
        assertEquals("ESP8266", EspFlashCodec.chipNameForMagic(0xFFF0C101L.toInt()))
        assertEquals("ESP32", EspFlashCodec.chipNameForMagic(0x00F01D83))
        assertEquals("ESP32-S2", EspFlashCodec.chipNameForMagic(0x000007C6))
        assertNull(EspFlashCodec.chipNameForMagic(0))
        // chip_id → 名称：R123 只有 5/9/12/13/16，R143 补齐 P4=18 并全表化
        assertEquals("ESP32-C3", EspFlashCodec.chipNameForId(5))
        assertEquals("ESP32-S3", EspFlashCodec.chipNameForId(9))
        assertEquals("ESP32-C2", EspFlashCodec.chipNameForId(12))
        assertEquals("ESP32-C6", EspFlashCodec.chipNameForId(13))
        assertEquals("ESP32-H2", EspFlashCodec.chipNameForId(16))
        assertEquals("ESP32-P4", EspFlashCodec.chipNameForId(18))
        assertEquals("ESP32", EspFlashCodec.chipNameForId(0))
        assertEquals("ESP32-S2", EspFlashCodec.chipNameForId(2))
        assertNull(EspFlashCodec.chipNameForId(999))
        assertNull(EspFlashCodec.chipNameForId(-2))   // UNKNOWN 不当有效识别
    }

    @Test fun stubKeysMatchEsptoolStubJsonNames() {
        // py loader.py:2245-2247 stub_json_name() = strip_chip_name(CHIP_NAME) + ".json"
        val expected = mapOf(
            "ESP32" to "esp32", "ESP32-S2" to "esp32s2", "ESP32-S3" to "esp32s3",
            "ESP32-C3" to "esp32c3", "ESP32-C2" to "esp32c2", "ESP32-C6" to "esp32c6",
            "ESP32-H2" to "esp32h2", "ESP32-P4" to "esp32p4", "ESP8266" to "esp8266",
        )
        for ((name, key) in expected) {
            assertEquals(name, key, EspTargets.byName(name)!!.stubKey)
            assertEquals(name, key, EspTargets.stubKeyFor(EspChip(name, null)))
        }
    }

    // ---------- 命令编码对照官方值 ----------
    @Test fun requestAndSyncEncodingMatchOfficial() {
        // py loader.py:606 <BBHI(0x00, op, len, chk)> + data；:723 SYNC payload 07 07 12 20 + 32×0x55
        val req = EspFlashCodec.request(EspCmd.SYNC, EspFlashCodec.syncPayload())
        assertArrayEquals(hex("0008240000000000" + "07071220" + "55".repeat(32)), req)
        assertEquals(0x20, req[11].toInt() and 0xff)   // SYNC payload 第 4 字节 0x20（esploader.ts:480）
        assertEquals(36, EspFlashCodec.syncPayload().size)
    }

    @Test fun memBeginMemDataMemFinishEncodingMatchOfficial() {
        // py loader.py:1146 <IIII>(size, blocks, blocksize, offset)
        val mb = EspFlashCodec.memBeginParams(0x180A, 2, 0x1800, 0x403C0000)
        assertArrayEquals(hex("0a180000" + "02000000" + "00180000" + "00003c40"), mb)
        // py loader.py:1154 <IIII>(len, seq, 0, 0) + data，chk = 0xEF XOR data
        val chunk = byteArrayOf(1, 2, 3, 4)
        val md = EspFlashCodec.flashDataHeader(chunk.size, 7) + chunk
        assertEquals(20, md.size)
        assertEquals(4, EspFlashCodec.le32(md, 0))
        assertEquals(7, EspFlashCodec.le32(md, 4))
        assertEquals(0xEF xor 1 xor 2 xor 3 xor 4, EspFlashCodec.checksum(chunk))
        // py loader.py:1165 <II>(int(entry==0), entry)
        assertArrayEquals(hex("00000000" + "34123d40"), EspFlashCodec.memFinishParams(0x403D1234))
    }

    @Test fun flashBeginEncodingRomVsStubAndExtendedFifthWord() {
        // py loader.py:1195-1201：ROM 非 ESP32/8266 追加第 5 字 0；stub 对 ESP32 也追加；
        // blockSize = ROM 0x400 / stub 0x4000。
        val rom = EspFlashCodec.flashBeginParams(0x3000, 2, 0x10000, extended = true)
        assertEquals(20, rom.size)
        assertEquals(0x400, EspFlashCodec.le32(rom, 8))
        assertEquals(0, EspFlashCodec.le32(rom, 16))
        val romEsp32 = EspFlashCodec.flashBeginParams(0x3000, 2, 0x10000, extended = false)
        assertEquals(16, romEsp32.size)
        val stub = EspFlashCodec.flashBeginParams(0x3000, 2, 0x10000, extended = true,
            blockSize = EspCmd.STUB_FLASH_WRITE_SIZE)
        assertEquals(20, stub.size)
        assertEquals(0x4000, EspFlashCodec.le32(stub, 8))
        assertEquals(0, EspFlashCodec.le32(stub, 16))
    }

    @Test fun md5RequestHasNo0x20LengthPrefixAndRespLenIsRom32Stub16() {
        // 常见错误点：MD5 请求 payload 就是 <IIII>(addr,size,0,0) = 16B，没有 0x20 长度前缀。
        // 0x20(32) 是 ROM 的「响应数据长度」字段（py loader.py:1624 RESP_DATA_LEN=32），不是请求前缀。
        val p = EspFlashCodec.md5Params(0x10000, 0x1000)
        assertEquals(16, p.size)
        assertArrayEquals(hex("00000100" + "00100000" + "00000000" + "00000000"), p)
        assertEquals(0x20, EspCmd.MD5_RESP_DATA_LEN_ROM)   // 32 = 响应长度
        assertEquals(16, EspCmd.MD5_RESP_DATA_LEN_STUB)    // stub 回 16B 原始 digest
        // SPI_FLASH_MD5 命令码 0x13（py loader.py:320）
        assertEquals(0x13, EspCmd.SPI_FLASH_MD5)
        // 请求帧头 len 字段 = 16（0x10），不是 0x20
        val frame = EspFlashCodec.request(EspCmd.SPI_FLASH_MD5, p)
        assertEquals(16, EspFlashCodec.le16(frame, 2))
    }

    @Test fun slipFrameAndCommandOpcodesMatchOfficial() {
        // esptool/loader.py:301-349 ESP_CMDS；esploader.ts:108-131
        assertEquals(0x02, EspCmd.FLASH_BEGIN)
        assertEquals(0x03, EspCmd.FLASH_DATA)
        assertEquals(0x04, EspCmd.FLASH_END)
        assertEquals(0x05, EspCmd.MEM_BEGIN)
        assertEquals(0x06, EspCmd.MEM_END)
        assertEquals(0x07, EspCmd.MEM_DATA)
        assertEquals(0x08, EspCmd.SYNC)
        assertEquals(0x0D, EspCmd.SPI_ATTACH)
        assertEquals(0x0F, EspCmd.CHANGE_BAUDRATE)
        // 帧 = 0xC0 + payload(转义) + 0xC0
        val frame = EspFlashCodec.requestFrame(EspCmd.MEM_END, EspFlashCodec.memFinishParams(0))
        assertEquals(0xC0, frame[0].toInt() and 0xff)
        assertEquals(0xC0, frame[frame.size - 1].toInt() and 0xff)
        assertArrayEquals(EspFlashCodec.request(EspCmd.MEM_END, EspFlashCodec.memFinishParams(0)),
            EspFlashCodec.slipDecode(frame))
    }

    // ---------- 时序 / 重试 / 错误处理常量 ----------
    @Test fun timingAndRetryConstantsMatchOfficial() {
        assertEquals(0x400, EspCmd.FLASH_WRITE_SIZE)              // py loader.py:357
        assertEquals(0x4000, EspCmd.STUB_FLASH_WRITE_SIZE)        // py loader.py:2234
        assertEquals(0x1800, EspCmd.ESP_RAM_BLOCK)                // py loader.py:355
        assertEquals(100, EspCmd.SYNC_TIMEOUT_MS)                 // py loader.py:99
        assertEquals(3000, EspCmd.DEFAULT_TIMEOUT_MS)             // py loader.py:93
        assertEquals(200, EspCmd.MEM_END_ROM_TIMEOUT_MS)          // py loader.py:107
        assertEquals(8, EspCmd.SYNC_RESPONSE_COUNT)               // py loader.py:732-734
        assertEquals(7, EspCmd.DEFAULT_CONNECT_ATTEMPTS)          // py loader.py:116
        assertEquals(3, EspCmd.WRITE_BLOCK_ATTEMPTS)              // py loader.py:118
        assertEquals(150, EspCmd.WRITE_BLOCK_RETRY_DELAY_MS)      // js esploader.ts:148
        assertEquals(40L, EspCmd.ERASE_REGION_TIMEOUT_PER_MB_SEC) // py loader.py:103
        assertEquals(8L, EspCmd.MD5_TIMEOUT_PER_MB_SEC)           // py loader.py:101
        assertEquals(0x05, EspCmd.ROM_INVALID_RECV_MSG)           // py loader.py:352
        // timeout_per_mb（py loader.py:134-139）：下限 3s，1MB 时 40s / 8s
        assertEquals(3000, EspFlashCodec.timeoutPerMb(40, 100))
        assertEquals(40000, EspFlashCodec.timeoutPerMb(40, 1_000_000))
        assertEquals(8000, EspFlashCodec.timeoutPerMb(8, 1_000_000))
    }

    // ---------- RESET 策略逐条对照 ----------
    @Test fun classicAndHardResetStepSequences() {
        // esp_pylib classic_bootloader_reset(serial_reset.py:322-329) / esptool.js ClassicReset(reset.ts:81-87)
        assertEquals(listOf(
            EspReset.Step(false, true, 100),
            EspReset.Step(true, false, 50),
            EspReset.Step(false, false, 0),
        ), EspReset.enterBootloader())
        // js esploader.ts:631-637 的 EXTRA_DELAY=550 变体
        assertEquals(550L, EspReset.enterBootloader(EspReset.CLASSIC_EXTRA_RESET_DELAY_MS)[1].sleepAfterMs)
        // hard_reset(serial_reset.py:438-442) / HardReset(reset.ts:150-159)
        assertEquals(listOf(
            EspReset.Step(false, true, 100),
            EspReset.Step(false, false, 0),
        ), EspReset.hardReset())
    }

    @Test fun usbJtagSerialResetMatchesOfficialPulseTrain() {
        // esptool.js reset.ts:111-127 UsbJtagSerialReset.reset() 与
        // esp_pylib serial_reset.py:380-391 usb_jtag_bootloader_reset() 合并后的 (DTR,RTS) 状态序列。
        assertEquals(listOf(
            EspReset.Step(false, false, 100),   // idle：RTS=H, DTR=H
            EspReset.Step(true, false, 100),   // 置 IO0：DTR=L(asserted), RTS=H
            EspReset.Step(false, true, 100),   // 复位：DTR=H, RTS=L(asserted)
            EspReset.Step(false, false, 0),    // 出复位
        ), EspReset.usbJtagSerialReset())
        assertEquals(100L, EspReset.USB_JTAG_SETTLE_MS)
        assertEquals(50L, EspReset.CLASSIC_RESET_DELAY_MS)
    }
}

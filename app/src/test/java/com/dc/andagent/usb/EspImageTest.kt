package com.dc.andagent.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** R123②③: EspImage ELF→ESP image 字节级断言，布局对照 esptool v5.6.0 bin_image.py。 */
class EspImageTest {

    /** 手工构造最小 32 位 LSB ELF：ELF 头(52) + phnum×32 program header + 各段数据。 */
    private fun buildElf(
        entry: Int,
        loads: List<Pair<Int, ByteArray>>,
        phentsize: Int = 32,
        nonElfMagic: Boolean = false,
        elfClass: Int = 1,
        elfData: Int = 1,
    ): ByteArray {
        val ehsize = 52
        val phoff = ehsize
        val dataOff = phoff + loads.size * phentsize
        val total = dataOff + loads.sumOf { it.second.size }
        val b = ByteArray(total)
        // e_ident
        b[0] = 0x7F
        b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        if (nonElfMagic) b[0] = 0x00
        b[4] = elfClass.toByte()   // EI_CLASS = ELFCLASS32
        b[5] = elfData.toByte()    // EI_DATA = ELFDATA2LSB
        b[6] = 1                   // EI_VERSION
        // ELF32 头
        EspFlashCodec.putLe16(b, 0x10, 2)           // e_type = ET_EXEC
        EspFlashCodec.putLe16(b, 0x12, 0x5E)        // e_machine = Xtensa
        EspFlashCodec.putLe32(b, 0x14, 1)           // e_version
        EspFlashCodec.putLe32(b, 0x18, entry)       // e_entry
        EspFlashCodec.putLe32(b, 0x1C, phoff)       // e_phoff
        EspFlashCodec.putLe32(b, 0x20, 0)           // e_shoff
        EspFlashCodec.putLe32(b, 0x24, 0)           // e_flags
        EspFlashCodec.putLe16(b, 0x28, ehsize)      // e_ehsize
        EspFlashCodec.putLe16(b, 0x2A, phentsize)   // e_phentsize
        EspFlashCodec.putLe16(b, 0x2C, loads.size)  // e_phnum
        EspFlashCodec.putLe16(b, 0x2E, 0)           // e_shentsize
        EspFlashCodec.putLe16(b, 0x30, 0)           // e_shnum
        EspFlashCodec.putLe16(b, 0x32, 0)           // e_shstrndx
        var off = dataOff
        loads.forEachIndexed { i, (addr, data) ->
            val p = phoff + i * phentsize
            EspFlashCodec.putLe32(b, p + 0, 1)           // p_type = PT_LOAD
            EspFlashCodec.putLe32(b, p + 4, off)         // p_offset
            EspFlashCodec.putLe32(b, p + 8, addr)        // p_vaddr
            EspFlashCodec.putLe32(b, p + 12, addr)       // p_paddr (LMA)
            EspFlashCodec.putLe32(b, p + 16, data.size)  // p_filesz
            EspFlashCodec.putLe32(b, p + 20, data.size)  // p_memsz
            EspFlashCodec.putLe32(b, p + 24, 5)          // p_flags
            EspFlashCodec.putLe32(b, p + 28, 4)          // p_align
            data.copyInto(b, off)
            off += data.size
        }
        return b
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    // ---------- fromElf 解析 ----------
    @Test fun fromElfParsesSegmentsAddressesAndEntry() {
        val dram = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val iram = byteArrayOf(0x11, 0x12, 0x13)
        val img = EspImage.fromElf(buildElf(0x40081234, listOf(0x3FF80000 to dram, 0x40078000 to iram)))

        assertEquals(0x40081234, img.entry)       // entry = e_entry，非 0
        assertEquals(2, img.segments.size)
        assertEquals(0x3FF80000, img.segments[0].addr)   // DRAM
        assertArrayEquals(dram, img.segments[0].data)
        assertEquals(0x40078000, img.segments[1].addr)   // IRAM
        assertArrayEquals(iram, img.segments[1].data)
    }

    @Test fun fromElfKeepsDebugSegmentsForChipAwareConversion() {
        // review 必修项③：地址过滤移到 toBinary 按 MAP 区间分流——原「越界即丢」会丢 C3/S3
        // 的 0x42xxxxxx IROM 段。fromElf 现在只按 p_type/p_filesz/p_paddr 收段，
        // 归类延迟到 toBinary 的 flash/RAM 分流（不在 MAP 区间的 RAM 段直接写入本体）。
        val img = EspImage.fromElf(buildElf(0x40081234, listOf(
            0x00010000 to byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
            0x3FF80000 to byteArrayOf(1, 2, 3, 4),
        )))
        assertEquals(2, img.segments.size)
    }

    @Test fun fromElfRejectsNonElf() {
        assertThrows(IllegalArgumentException::class.java) {
            EspImage.fromElf(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspImage.fromElf(buildElf(0x40081234, emptyList(), nonElfMagic = true))
        }
        assertFalse(EspImage.isElf(byteArrayOf(0, 1, 2, 3)))
        assertFalse(EspImage.isElf(byteArrayOf()))
        assertTrue(EspImage.isElf(buildElf(0, emptyList())))
    }

    @Test fun fromElfRejectsNon32BitOrNonLsb() {
        val loads = listOf(0x3FF80000 to byteArrayOf(1, 2, 3, 4))
        assertThrows(IllegalArgumentException::class.java) {
            EspImage.fromElf(buildElf(0x40081234, loads, elfClass = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EspImage.fromElf(buildElf(0x40081234, loads, elfData = 2))
        }
    }

    @Test fun fromElfRejectsZeroSegments() {
        // phnum=0
        assertThrows(IllegalArgumentException::class.java) {
            EspImage.fromElf(buildElf(0x40081234, emptyList()))
        }
    }

    @Test fun fromElfRejectsOutOfRangeSegmentData() {
        // p_offset+p_filesz 越出文件必须报错（防异常 ELF）
        val b = buildElf(0x40081234, listOf(0x3FF80000 to byteArrayOf(1, 2, 3, 4)))
        // 把 p_filesz 改大到越界
        EspFlashCodec.putLe32(b, 52 + 16, 0x7FFFFFFF)
        assertThrows(IllegalArgumentException::class.java) { EspImage.fromElf(b) }
    }

    // ---------- ESP8266 V1 布局（bin_image.py ESP8266ROMFirmwareImage.save()） ----------
    @Test fun toBinary8266V1Layout() {
        val img = EspImage.fromElf(buildElf(0x40008000, listOf(
            0x3FF80000 to byteArrayOf(0x01, 0x02, 0x03, 0x04),
            0x40108000 to byteArrayOf(0x11, 0x12, 0x13),  // IRAM（8266 是 RAM 段，非 IROM）
        )))
        val bin = img.toBinary(EspChip("ESP8266", null))

        // 公共头：0xE9, segCount, flash_mode=0, flash_size_freq=0, entry（V1 无扩展头）
        assertEquals(0xE9, bin[0].toInt() and 0xff)
        assertEquals(0, bin[2].toInt() and 0xff)
        assertEquals(0, bin[3].toInt() and 0xff)
        assertEquals(0x40008000, EspFlashCodec.le32(bin, 4))
        // 无扩展头：bin[8] 直接是第一个段头 addr=0x3FF80000
        assertEquals(0x3FF80000, EspFlashCodec.le32(bin, 8))
        // checksum 末字节、总长 ≡0 mod16、无 SHA256
        assertEquals(0, bin.size % 16)
        // 校验 checksum：XOR 两个段数据（各按 4 对齐）
        var chk = 0xEF
        for (b in byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x11, 0x12, 0x13, 0x00)) chk = chk xor (b.toInt() and 0xff)
        assertEquals(chk, bin[bin.size - 1].toInt() and 0xff)
    }

    // ---------- ESP32 V2 布局（bin_image.py ESP32FirmwareImage.save()） ----------
    /** 逐字节复刻 esptool ESP32FirmwareImage.save() 期望值。 */
    @Test fun toBinaryEsp32LayoutMatchesEsptoolSave() {
        // 场景：DRAM 段 4B @0x3FF80000（RAM）、IROM 段 4B @0x400D0000（flash，addr%64K==0）
        val img = EspImage.fromElf(buildElf(0x40080934, listOf(
            0x3FF80000 to byteArrayOf(0x01, 0x02, 0x03, 0x04),
            0x400D0000 to byteArrayOf(0x11, 0x12, 0x13, 0x14),
        )))
        val bin = img.toBinary(EspChip("ESP32", 0x00F01D83))

        // ---- 头 8B ----
        assertEquals(0xE9, bin[0].toInt() and 0xff)
        assertEquals(0x40080934, EspFlashCodec.le32(bin, 4))
        // ---- 16B 扩展头（save_extended_header：<BBBBHBHH + 4B padding + 1B digest）----
        assertEquals(0xEE, bin[8].toInt() and 0xff)          // wp_pin = WP_PIN_DISABLED
        assertEquals(0, bin[9].toInt() and 0xff)             // clk/q
        assertEquals(0, bin[10].toInt() and 0xff)            // d/cs
        assertEquals(0, bin[11].toInt() and 0xff)            // hd/wp
        assertEquals(0, EspFlashCodec.le16(bin, 12))         // chip_id = 0（ESP32，u16）
        assertEquals(0, bin[14].toInt() and 0xff)            // min_rev（u8）
        assertEquals(0, EspFlashCodec.le16(bin, 15))         // min_rev_full（u16）
        assertEquals(0, EspFlashCodec.le16(bin, 17))         // max_rev_full（u16）
        for (i in 19..22) assertEquals(0, bin[i].toInt() and 0xff)   // padding
        assertEquals(1, bin[23].toInt() and 0xff)            // append_digest = 1

        // ---- 段序列（file_pos=24 起）----
        // DRAM 4B 经 split_image 优先拆作垫段：addr=0、len=4、data 01020304
        assertEquals(0, EspFlashCodec.le32(bin, 24))
        assertEquals(4, EspFlashCodec.le32(bin, 28))
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), bin.copyOfRange(32, 36))
        // 0x00 垫段：写完 8B 段头后须 file_pos%65536 == 0x400D0000%65536 == 0
        assertEquals(0, EspFlashCodec.le32(bin, 36))
        assertEquals(65492, EspFlashCodec.le32(bin, 40))
        assertEquals(0, bin[44].toInt() and 0xff)
        assertEquals(0, bin[65535].toInt() and 0xff)
        // flash 段头 @65536
        assertEquals(0x400D0000, EspFlashCodec.le32(bin, 65536))
        // save_flash_segment ESP32 特例：段尾距页尾 12 <0x24 → 补 0x24-12=24×0x00，段长 28
        assertEquals(28, EspFlashCodec.le32(bin, 65540))
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14), bin.copyOfRange(65544, 65548))
        assertEquals(0, bin[65548].toInt() and 0xff)         // 补 0x00 第一字节

        // ---- checksum：align 16 → 总长 65584（+32 digest = 65616）≡0 mod16 ----
        assertEquals(0, bin.size % 16)
        val checksumPos = bin.size - 1 - 32                  // checksum 在 SHA256 之前
        assertEquals(65583, checksumPos)
        // 期望 checksum：0xEF XOR(01020304) XOR(0x00 垫段) XOR(11121314 + 24×0)
        var expected = 0xEF
        for (b in byteArrayOf(0x01, 0x02, 0x03, 0x04)) expected = expected xor (b.toInt() and 0xff)
        for (b in byteArrayOf(0x11, 0x12, 0x13, 0x14)) expected = expected xor (b.toInt() and 0xff)
        assertEquals(expected, bin[checksumPos].toInt() and 0xff)
        // ---- SHA256：对 bin[0 until bin.size-32] 做 SHA-256，等于末 32 字节
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bin.copyOfRange(0, bin.size - 32))
        assertArrayEquals(digest, bin.copyOfRange(bin.size - 32, bin.size))
        // ---- segment 计数回填（header[1]，含垫片段）：DRAM 垫段 + 0x00 垫段 + flash = 3
        assertEquals(3, bin[1].toInt() and 0xff)
    }

    @Test fun toBinaryC3UsesExtendedHeaderAnd42Map() {
        // review 必修项③：0x42000000 IROM 段（C3/S3/C6/H2/C2）必须归为 flash 段而非被丢弃
        val img = EspImage.fromElf(buildElf(0x4200_0000 + 0x10, listOf(
            0x3FC80000 to byteArrayOf(1, 2, 3, 4),        // C3 DRAM（不在 8266/ESP32 MAP，但也不在 C3 MAP → RAM 段）
            0x42000010 to byteArrayOf(0x11, 0x12, 0x13, 0x14),  // C3 IROM → flash 段，addr%64K=16
        )))
        val bin = img.toBinary(EspChip("ESP32-C3", 5))
        // 扩展头 chip_id=5（u16 @12）、append_digest=1（@23）
        assertEquals(5, EspFlashCodec.le16(bin, 12))
        assertEquals(1, bin[23].toInt() and 0xff)
        // DRAM 4B 经 split_image 作垫段 @24
        assertEquals(0, EspFlashCodec.le32(bin, 24))
        assertEquals(4, EspFlashCodec.le32(bin, 28))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bin.copyOfRange(32, 36))
        // 0x00 垫段 @36（65508B），使 flash 段头落在 65552
        assertEquals(0, EspFlashCodec.le32(bin, 36))
        assertEquals(65508, EspFlashCodec.le32(bin, 40))
        // flash 段 @0x42000010：C3 非 ESP32 无 0x24 特例，段长 4
        assertEquals(0x42000010, EspFlashCodec.le32(bin, 65552))
        assertEquals(4, EspFlashCodec.le32(bin, 65556))
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14), bin.copyOfRange(65560, 65564))
        // SHA256 末 32B
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bin.copyOfRange(0, bin.size - 32))
        assertArrayEquals(digest, bin.copyOfRange(bin.size - 32, bin.size))
    }

    @Test fun toBinaryRamSegmentSplitsAsPadding() {
        // RAM 段 >8B 时优先拆作垫片（split_image）：DRAM 32B，IROM addr%64K=16
        val ramData = ByteArray(32) { (it + 1).toByte() }
        val img = EspImage.fromElf(buildElf(0x40000000, listOf(
            0x3FF80000 to ramData,
            0x400D0010 to byteArrayOf(0x11, 0x12, 0x13, 0x14),
        )))
        val bin = img.toBinary(EspChip("ESP32", 0))
        // RAM 32B 优先拆作垫段 @24
        assertEquals(0, EspFlashCodec.le32(bin, 24))
        assertEquals(32, EspFlashCodec.le32(bin, 28))
        assertArrayEquals(ramData, bin.copyOfRange(32, 64))
        // 0x00 垫段 @64（65480B）
        assertEquals(0, EspFlashCodec.le32(bin, 64))
        assertEquals(65480, EspFlashCodec.le32(bin, 68))
        // flash 段头 @65552：ESP32 0x24 特例补 8×0x00，段长 12
        assertEquals(0x400D0010, EspFlashCodec.le32(bin, 65552))
        assertEquals(12, EspFlashCodec.le32(bin, 65556))
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14), bin.copyOfRange(65560, 65564))
    }

    @Test fun toBinaryRejectsSameFlashPageOverlap() {
        // 同 64KB 页两个 flash 段 = 链接脚本错误，必须报错（bin_image.py :838）
        val img = EspImage.fromElf(buildElf(0x40000000, listOf(
            0x400D0000 to byteArrayOf(1, 2, 3, 4),
            0x400D0010 to byteArrayOf(5, 6, 7, 8),
        )))
        assertThrows(IllegalArgumentException::class.java) {
            img.toBinary(EspChip("ESP32", 0))
        }
    }

    @Test fun toBinaryP4UsesExtendedHeaderAndWideMap() {
        // R143：补齐 ESP32-P4（官方 esptool/targets/esp32p4.py:17,27-30 / js src/targets/esp32p4.ts:7,9-12）。
        // 0x48000010 落在 P4 的 IROM/DROM (0x40000000..0x4C000000) 内，应归 flash 段。
        val img = EspImage.fromElf(buildElf(0x40000000 + 0x20, listOf(
            0x4FF00000 to byteArrayOf(1, 2, 3, 4),                    // P4 DRAM（不在 MAP → RAM 垫片段）
            0x48000010 to byteArrayOf(0x11, 0x12, 0x13, 0x14),        // P4 IROM → flash 段
        )))
        val bin = img.toBinary(EspChip("ESP32-P4", 18))
        // 扩展头 chip_id=18（u16 @12）、append_digest=1（@23）
        assertEquals(18, EspFlashCodec.le16(bin, 12))
        assertEquals(1, bin[23].toInt() and 0xff)
        // DRAM 4B 经 split_image 作垫段 @24
        assertEquals(0, EspFlashCodec.le32(bin, 24))
        assertEquals(4, EspFlashCodec.le32(bin, 28))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bin.copyOfRange(32, 36))
        // flash 段头落在 65552（与 C3 同构的 64KB 对齐），段长 4（P4 非 ESP32 无 0x24 特例）
        assertEquals(0x48000010, EspFlashCodec.le32(bin, 65552))
        assertEquals(4, EspFlashCodec.le32(bin, 65556))
        assertArrayEquals(byteArrayOf(0x11, 0x12, 0x13, 0x14), bin.copyOfRange(65560, 65564))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bin.copyOfRange(0, bin.size - 32))
        assertArrayEquals(digest, bin.copyOfRange(bin.size - 32, bin.size))
    }

    @Test fun toBinaryUnknownChipUsesWideMap() {
        // 未知芯片：UNKNOWN flavor 用 S3 宽区间，0x42 段仍进 flash 路径
        val img = EspImage.fromElf(buildElf(0x40000000, listOf(
            0x3FF80000 to byteArrayOf(1, 2, 3, 4),
            0x42000010 to byteArrayOf(0x11, 0x12, 0x13, 0x14),
        )))
        val bin = img.toBinary(EspChip("UNKNOWN", null))
        // UNKNOWN flavor chipId=-2 → 扩展头 chip_id 写 0xFFFE；0x42 段仍按 flash 路径（含 SHA256）
        assertEquals(0xFFFE, EspFlashCodec.le16(bin, 12))
        assertEquals(1, bin[23].toInt() and 0xff)
        assertEquals(0x42000010, EspFlashCodec.le32(bin, 65552))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bin.copyOfRange(0, bin.size - 32))
        assertArrayEquals(digest, bin.copyOfRange(bin.size - 32, bin.size))
    }

    @Test fun esp8266RejectsIromOnlyBody() {
        // 全部段都是 ESP8266 IROM（0x40201010..0x402E1010）→ 本体无段可写，报错
        val img = EspImage.fromElf(buildElf(0x40201010, listOf(
            0x40201010 to byteArrayOf(1, 2, 3, 4),
        )))
        assertThrows(IllegalArgumentException::class.java) {
            img.toBinary(EspChip("ESP8266", null))
        }
    }

}

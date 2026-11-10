package com.dc.andagent.usb

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * ELF → ESP firmware image（纯 JVM，不 import android.*）。
 *
 * 字节布局逐条对照 /tmp/esptool-src（esptool v5.6.0）：
 *  - 公共头/段/checksum（ESP8266 V1 路径，bin_image.py:524-559）：
 *      write_common_header(:390) `<BBBBI` = magic(0xE9), segment 数, flash_mode, flash_size_freq, entrypoint；
 *      save_segment(:328) `<II` addr+len + data（len 已 4 对齐，:336 强制）；append_checksum(:385) =
 *      align_file_position(16) 补 `15-(pos%16)` 个 0x00 再写 1 字节 checksum（末字节，总长 ≡0 mod16）；
 *      checksum = 0xEF 起 XOR 段数据（loader.py:576，不含 `<II` 头）。
 *  - ESP32 系路径（ESP32FirmwareImage，bin_image.py:708-1052）：
 *      save() 在公共头后写 16B 扩展头 save_extended_header(:1032)：`<BBBBHBHH` + 4×B + B =
 *      wp_pin(0xEE=WP_PIN_DISABLED :721), clk/q, d/cs, hd/wp 驱动(全 0), chip_id(u16), min_rev(u16),
 *      min_rev_full(u16), max_rev_full(u16), 4×0x00 padding, append_digest(1)。
 *      段排序：flash 段（地址落在 IROM/DROM MAP 区间，is_flash_addr :771）按 addr 升序在前，
 *      RAM 段在后；写 flash 段前用 get_alignment_data_needed(:847) 补垫——写入 8B 段头后须满足
 *      file_pos % 65536 == segment.addr % 65536（IROM_ALIGN=65536 :725），垫片本身也是段
 *      （addr=0，参与 checksum 与 segment 计数）；RAM 段经 split_image(:165) 优先作垫片，
 *      不足补 0x00 段。ESP32 的 save_flash_segment(:352) 对跨页尾 <0x24 字节的段补 0x00。
 *      最后 append_checksum + 整个文件（不含 digest 本身）SHA-256 32B（:976-985）。
 *  - 芯片 MAP 区间与 IMAGE_CHIP_ID（targets 目录 *.py）：
 *      ESP32      IROM 0x400D0000..0x40400000 DROM 0x3F400000..0x3F800000 chip_id=0  boot_off=0x1000
 *      ESP32-S2   IROM 0x40080000..0x40B80000 DROM 0x3F000000..0x3F3F0000 chip_id=2  boot_off=0x1000
 *      ESP32-S3   IROM 0x42000000..0x44000000 DROM 0x3C000000..0x3E000000 chip_id=9  boot_off=0x0
 *      ESP32-C3   IROM 0x42000000..0x42800000 DROM 0x3C000000..0x3C800000 chip_id=5  boot_off=0x0
 *      ESP32-C2   IROM 0x42000000..0x42400000 DROM 0x3C000000..0x3C400000 chip_id=12 boot_off=0x0
 *      ESP32-C6   IROM 0x42000000..0x42800000 DROM 0x42800000..0x43000000 chip_id=13 boot_off=0x0
 *      ESP32-H2   同 C6（继承 C6 MAP）                              chip_id=16 boot_off=0x0
 *  - ELF 解析（ELFFile :1294）：32 位 LSB ELF，PT_LOAD(p_type=1) 取 p_paddr(LMA)/p_offset/p_filesz。
 *
 * 未复刻（记录为边界）：ram_only_header 变体、secure_pad、.flash.appdesc/.dram0.bootdesc 重排
 * （需要 ELF 段名，本实现从 program header 读不到 section 名）、elf_sha256 段内补丁（esp-idf
 * 应用镜像的 .flash.appdesc SHA 偏移依赖 section 布局）。烧录 offset 仍由调用方给定。
 */
class EspImage private constructor(
    val entry: Int,
    val segments: List<Segment>,
) {
    data class Segment(val addr: Int, val data: ByteArray)

    /** 芯片镜像参数（MAP 区间/chip_id/扩展头）。 */
    internal enum class ChipFlavor(
        val chipName: String?,
        val chipId: Int,
        val iromStart: Long, val iromEnd: Long,
        val dromStart: Long, val dromEnd: Long,
    ) {
        ESP8266("ESP8266", -1, 0, 0, 0, 0),           // V1 路径，无扩展头、无 flash 段概念
        ESP32("ESP32", 0, 0x400D0000, 0x40400000, 0x3F400000, 0x3F800000),
        ESP32_S2("ESP32-S2", 2, 0x40080000, 0x40B80000, 0x3F000000, 0x3F3F0000),
        ESP32_S3("ESP32-S3", 9, 0x42000000, 0x44000000, 0x3C000000, 0x3E000000),
        ESP32_C3("ESP32-C3", 5, 0x42000000, 0x42800000, 0x3C000000, 0x3C800000),
        ESP32_C2("ESP32-C2", 12, 0x42000000, 0x42400000, 0x3C000000, 0x3C400000),
        ESP32_C6("ESP32-C6", 13, 0x42000000, 0x42800000, 0x42800000, 0x43000000),
        ESP32_H2("ESP32-H2", 16, 0x42000000, 0x42800000, 0x42800000, 0x43000000),
        UNKNOWN(null, -2, 0x42000000, 0x44000000, 0x3C000000, 0x3E000000), // 未知芯片按 S3 宽区间
        ;

        val usesExtendedHeader: Boolean get() = this != ESP8266

        fun isFlashAddr(addr: Long): Boolean =
            (addr >= iromStart && addr < iromEnd) || (addr >= dromStart && addr < dromEnd)
    }

    /** 按 EspChip（芯片名优先，其次 chipId）选镜像 flavor。 */
    internal fun flavorFor(chip: EspChip?): ChipFlavor {
        if (chip == null) return ChipFlavor.ESP8266
        ChipFlavor.entries.firstOrNull { it.chipName != null && it.chipName == chip.name }?.let { return it }
        val id = chip.chipId ?: return ChipFlavor.UNKNOWN
        return ChipFlavor.entries.firstOrNull { it.chipId == id } ?: ChipFlavor.UNKNOWN
    }

    /**
     * 按 esptool ESP32FirmwareImage.save()（或 ESP8266 V1 save()）拼出可烧录 bin。
     * ramOnlyHeader/securePad 不支持（见类注释边界）。
     */
    fun toBinary(chip: EspChip? = null): ByteArray {
        val flavor = flavorFor(chip)
        val out = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        var checksum = 0xEF                       // ESP_CHECKSUM_MAGIC
        var totalSegments = 0

        fun writeSegment(addr: Int, data: ByteArray, intoChecksum: Boolean) {
            require(data.size % 4 == 0) { "段长非 4 对齐：${data.size}" }
            val header = ByteArray(8)
            EspFlashCodec.putLe32(header, 0, addr)
            EspFlashCodec.putLe32(header, 4, data.size)
            body.write(header)
            body.write(data)
            if (intoChecksum) for (b in data) checksum = checksum xor (b.toInt() and 0xff)
            totalSegments++
        }

        // ---- 公共头 8B（write_common_header）----
        out.write(0xE9)                           // ESP_IMAGE_MAGIC
        out.write(0)                              // segment 数占位，末尾回填 totalSegments
        out.write(0)                              // flash_mode = qio(0)
        out.write(0)                              // flash_size_freq = 0
        val entryBytes = ByteArray(4)
        EspFlashCodec.putLe32(entryBytes, 0, entry)
        out.write(entryBytes)                     // entrypoint = e_entry

        // ---- 16B 扩展头（ESP32 系，save_extended_header）----
        if (flavor.usesExtendedHeader) {
            // 流式顺序写 u16 LE（EspFlashCodec.putLe16 只接受 ByteArray，此处 out 是流）。
            fun writeLe16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
            out.write(0xEE)                       // wp_pin = WP_PIN_DISABLED
            out.write(0); out.write(0); out.write(0)   // clk/q, d/cs, hd/wp 驱动全 0
            writeLe16(flavor.chipId)              // chip_id（u16）
            out.write(0)                          // min_rev（u8，esptool `<BBBBHBHH`）
            writeLe16(0)                          // min_rev_full（u16）
            writeLe16(0)                          // max_rev_full（u16）
            repeat(4) { out.write(0) }            // padding
            out.write(1)                          // append_digest = 1
        }

        // ---- 段写入 ----
        if (!flavor.usesExtendedHeader) {
            // ESP8266 V1：IROM 段不出现在本体（esptool 单独输出 <elf>-0x00000.bin 等，
            // 本体只收非 IROM 段）；ROM 直接从 IRAM 加载的用法超出本轮范围，如实过滤。
            val normal = segments.filter { !isIrom8266(it.addr) }
            require(normal.isNotEmpty()) { "ELF 无可写入本体的段（IROM 段由 ROM 直接映射，需单独 bin）" }
            for (seg in normal) writeSegment(seg.addr, pad4(seg.data), intoChecksum = true)
            appendChecksum(out, body, checksum)
            return patchCountAndFinish(out, totalSegments, includeDigest = false)
        }

        // ESP32 系：flash 段按 addr 升序 + 64KB 页对齐垫片；RAM 段剩余的直接写。
        val iromAlign = 65536
        val flashSegs = segments.filter { flavor.isFlashAddr(it.addr.toLong() and 0xFFFF_FFFFL) }
            .sortedBy { it.addr }
        val ramSegs = segments.filter { !flavor.isFlashAddr(it.addr.toLong() and 0xFFFF_FFFFL) }
            .sortedBy { it.addr }
        require(flashSegs.isNotEmpty() || ramSegs.isNotEmpty()) { "ELF 无有效段" }

        // 同 64KB 页内两个 flash 段 = 链接脚本问题（save() :838 显式报错）。
        for (i in 1 until flashSegs.size) {
            require(flashSegs[i].addr / iromAlign != flashSegs[i - 1].addr / iromAlign) {
                "段 0x${flashSegs[i].addr.toString(16)} 与 0x${flashSegs[i - 1].addr.toString(16)} " +
                    "落在同一 ${iromAlign / 1024}KB flash 映射页，无法生成镜像（bin_image.py save()）"
            }
        }

        val ramQueue = ArrayDeque(ramSegs)
        for (flashSeg in flashSegs) {
            // get_alignment_data_needed(:847)：写完 8B 段头后须 file_pos % 65536 == addr % 65536。
            val alignPast = (flashSeg.addr % iromAlign) - 8
            var padLen = (iromAlign - (bodyTotal(out, body) % iromAlign)) + alignPast
            if (padLen == 0 || padLen == iromAlign) padLen = 0
            padLen -= 8                            // 垫片自身也有 8B 头
            if (padLen < 0) padLen += iromAlign
            if (padLen > 0) {
                // RAM 段优先拆作垫片（split_image）；不足或 <8B 补 0x00 段。
                var remaining = padLen
                while (remaining > 8 && ramQueue.isNotEmpty()) {
                    val take = minOf(remaining, ramQueue.first().data.size)
                    val head = ramQueue.first()
                    if (take >= head.data.size) {
                        ramQueue.removeFirst()
                        writeSegment(0, pad4(head.data), intoChecksum = true)
                        remaining -= pad4(head.data).size
                    } else {
                        ramQueue[0] = Segment(head.addr + take, head.data.copyOfRange(take, head.data.size))
                        writeSegment(0, pad4(head.data.copyOfRange(0, take)), intoChecksum = true)
                        remaining -= pad4(head.data.copyOfRange(0, take)).size
                    }
                }
                if (remaining > 0) {
                    val padSeg = ByteArray(pad4Size(remaining))  // 0x00 填充（bin_image.py:901）
                    writeSegment(0, padSeg, intoChecksum = true)
                }
            }
            // ESP32 save_flash_segment(:356)：跨页尾 <0x24 的段补 0x00（仅原版 ESP32）。
            var segData = pad4(flashSeg.data)
            if (flavor == ChipFlavor.ESP32) {
                val endPos = bodyTotal(out, body) + segData.size + 8
                val remainder = endPos % iromAlign
                if (remainder in 1 until 0x24) {
                    segData = segData + ByteArray(0x24 - remainder)
                }
            }
            writeSegment(flashSeg.addr, segData, intoChecksum = true)
        }
        for (seg in ramQueue) writeSegment(seg.addr, pad4(seg.data), intoChecksum = true)

        appendChecksum(out, body, checksum)
        return patchCountAndFinish(out, totalSegments, includeDigest = true)
    }

    // ---------- 内部 ----------

    private fun isIrom8266(addr: Int): Boolean =
        addr >= 0x40201010L && addr < 0x402E1010L   // ESP8266 MEMORY_MAP IROM（targets/esp8266.py:67）

    /** bodyTotal = 已落 out 的字节数 + body 缓冲字节数。 */
    private fun bodyTotal(out: ByteArrayOutputStream, body: ByteArrayOutputStream): Int = out.size() + body.size()

    private fun pad4Size(n: Int): Int = if (n % 4 == 0) n else n + (4 - n % 4)

    private fun pad4(data: ByteArray): ByteArray {
        val mod = data.size % 4
        return if (mod == 0) data else data + ByteArray(4 - mod)
    }

    /** 把 body 缓冲并入 out：补 16 字节对齐间隙后写 checksum（esptool append_checksum）。 */
    private fun appendChecksum(out: ByteArrayOutputStream, body: ByteArrayOutputStream, checksum: Int) {
        body.writeTo(out)
        val gap = 15 - (out.size() % 16)
        repeat(gap) { out.write(0) }
        out.write(checksum and 0xff)
    }

    /**
     * 回填 segment 数（header[1]，:989-995「kinda hacky」——计数含垫片段，不含 digest）。
     * 顺序对齐 esptool：先回填计数，再对整文件（不含 digest 本身）算 SHA-256 追加。
     */
    private fun patchCountAndFinish(out: ByteArrayOutputStream, count: Int, includeDigest: Boolean): ByteArray {
        require(count <= 0xFF) { "segment 数 $count 超过 255（u8 头字段）" }
        val all = out.toByteArray()
        all[1] = count.toByte()
        return if (includeDigest) all + MessageDigest.getInstance("SHA-256").digest(all) else all
    }

    companion object {
        /** 文件头是否为 ELF magic（供 esp_flash 判定是否走 ELF→image）。 */
        fun isElf(bytes: ByteArray): Boolean =
            bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0x7F &&
                bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

        /** 解析 32 位 LSB ELF 的 PT_LOAD 段（p_paddr 为加载地址）。 */
        fun fromElf(bytes: ByteArray): EspImage {
            require(bytes.size >= 52) { "ELF 过短：${bytes.size} < 52（ELF32 头）" }
            require(isElf(bytes)) { "非 ELF 文件（magic 不符 0x7F 'E' 'L' 'F'）" }
            require((bytes[4].toInt() and 0xff) == 1) { "仅支持 32 位 ELF（EI_CLASS=1）" }
            require((bytes[5].toInt() and 0xff) == 1) { "仅支持小端 ELF（EI_DATA=1）" }
            val entry = u32(bytes, 0x18)       // e_entry
            val phoff = u32(bytes, 0x1C)       // e_phoff
            val phentsize = u16(bytes, 0x2A)   // e_phentsize
            val phnum = u16(bytes, 0x2C)       // e_phnum
            require(phentsize >= 32) { "e_phentsize=$phentsize < 32（ELF32 program header）" }
            require(phnum <= 64) { "e_phnum=$phnum 过多（上限 64，防异常 ELF 超量解析）" }

            val segments = mutableListOf<Segment>()
            val fileSize = bytes.size.toLong()
            for (i in 0 until phnum) {
                val p = phoff + i.toLong() * phentsize
                require(p + 32 <= fileSize) { "program header[$i] 越界（e_phoff=0x${phoff.toString(16)}）" }
                val o = p.toInt()
                if (u32(bytes, o) != 1L) continue              // p_type == PT_LOAD(1)
                val pOffset = u32(bytes, o + 4)                // p_offset
                val pPaddr = u32(bytes, o + 12)                // p_paddr (LMA)
                val pFilesz = u32(bytes, o + 16)               // p_filesz
                if (pFilesz <= 0L || pPaddr == 0L) continue
                require(pOffset + pFilesz <= fileSize) {
                    "segment[$i] 数据越界（p_offset=$pOffset, p_filesz=$pFilesz, 文件=${bytes.size}）"
                }
                require(pFilesz <= MAX_SEGMENT_BYTES) { "segment[$i] 过大：$pFilesz > $MAX_SEGMENT_BYTES（防异常 ELF）" }
                segments += Segment(pPaddr.toInt(), bytes.copyOfRange(pOffset.toInt(), (pOffset + pFilesz).toInt()))
                require(segments.size <= 16) { "PT_LOAD 段超过 16（esptool verify() 上限）" }
            }
            require(segments.isNotEmpty()) { "ELF 中无有效 PT_LOAD 段（p_filesz=0 或 p_paddr=0）" }
            return EspImage(entry.toInt(), segments)
        }

        /** 单段字节数上限（16MB，save_segment :342 的 16MB 硬限）。 */
        const val MAX_SEGMENT_BYTES = 16L * 1024 * 1024

        private fun u16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

        private fun u32(b: ByteArray, off: Int): Long =
            EspFlashCodec.le32(b, off).toLong() and 0xFFFF_FFFFL
    }
}

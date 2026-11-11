package com.dc.andagent.usb

/**
 * 全芯片参数表（纯 JVM，不 import android.*）。
 *
 * 逐项抄自官方实现，供 EspFlashCodec / EspImage / stub 装载共同引用，避免多处硬编码漂移。
 * 引用记号为「仓库:文件:行号」；py = /tmp/esptool-src（esptool HEAD 2026-11-09），
 * js = /tmp/esptool-js-src（esptool-js 0.7.1 / d5b779b）。
 *
 *  - IMAGE_CHIP_ID：py esptool/targets/<chip>.py 顶部类常量；js src/targets/<chip>.ts 顶部。
 *  - IROM/DROM：py esptool/targets/<chip>.py 的 IROM_MAP_START/END、DROM_MAP_START/END；
 *    js src/targets/<chip>.ts 的 IROM/DROM MAP 常量（或 MEMORY_MAP 的 DROM 段）。
 *  - BOOTLOADER_FLASH_OFFSET：py esptool/targets/<chip>.py（未覆写时继承 esptool/loader.py:397 的 0x0）；
 *    js src/targets/<chip>.ts。
 *  - MAGIC_VALUE：py esptool/targets/<chip>.py（READ_REG(0x40001000) 识别，仅 ESP8266/ESP32/S2 用）。
 *  - FLASH_WRITE_SIZE：ROM = py esptool/loader.py:357 0x400 / js esploader.ts:205 默认 0x4000
 *    （js 构造默认 0x4000 但各 ROM target 覆写为 0x400，如 src/targets/esp32.ts:56）、
 *    STUB = py esptool/loader.py:2234 0x4000。
 *  - stubKey：py esptool/loader.py:2245-2247 stub_json_name() = strip_chip_name(CHIP_NAME) + ".json"，
 *    实际文件在 py esptool/targets/stub_flasher/2/ 目录（v1.4.1）；js src/targets/stub_flasher/ 下的 .json（v1.2.2）。
 *
 * 上游另有 ESP32-C5/C61/H4/H21/E22/S31（chip_id 23/20/28/25/31/32，见各 targets 文件），
 * 本轮未为其打包 stub、也未逐项核对 MAP，故 detectChip 仍会把它们报成 UNKNOWN 并走 ROM 路径；
 * 战报如实登记该边界。
 */
data class EspChipParams(
    val name: String,
    /** IMAGE_CHIP_ID；null = 该芯片 ROM 不支持 GET_SECURITY_INFO 的 chip_id（走 magic/12B 路径）。 */
    val chipId: Int?,
    val iromStart: Long,
    val iromEnd: Long,
    val dromStart: Long,
    val dromEnd: Long,
    /** BOOTLOADER_FLASH_OFFSET。 */
    val bootOff: Int,
    /** READ_REG(0x40001000) 的 MAGIC_VALUE；null = 不用 magic 识别。 */
    val magicValue: Int?,
    /** ESP32 系固件镜像需要 16B 扩展头；ESP8266 V1 不需要。 */
    val usesExtendedHeader: Boolean,
    /** esptool stub_flasher/2/<stubKey>.json 基名；null = 未打包该芯片 stub。 */
    val stubKey: String?,
    val romFlashWriteSize: Int = 0x400,
    val stubFlashWriteSize: Int = 0x4000,
) {
    /** esptool bin_image.py ESP32FirmwareImage.is_flash_addr：段地址落在 IROM/DROM 即 flash 段。 */
    fun isFlashAddr(addr: Long): Boolean =
        (addr >= iromStart && addr < iromEnd) || (addr >= dromStart && addr < dromEnd)
}

object EspTargets {

    // py esptool/targets/esp8266.py:13,23,61,63-68（MEMORY_MAP IROM）/ js src/targets/esp8266.ts:5,16,41-45
    // 注：js 的 IROM_MAP_START/END(:19-20) 是较粗的 0x40200000..0x40300000，实际 MEMORY_MAP IROM
    // 与 py 一致为 0x40201010..0x402E1010（EspImage.isIrom8266 用的就是这个精确区间）。
    val ESP8266 = EspChipParams(
        name = "ESP8266", chipId = null,
        iromStart = 0x40201010L, iromEnd = 0x402E1010L,
        dromStart = 0L, dromEnd = 0L,
        bootOff = 0x0, magicValue = 0xFFF0C101L.toInt(),
        usesExtendedHeader = false, stubKey = "esp8266",
    )

    // py esptool/targets/esp32.py:17,18,30-34,106,28 / js src/targets/esp32.ts:5,6,15-18,56,57
    val ESP32 = EspChipParams(
        name = "ESP32", chipId = 0,
        iromStart = 0x400D0000L, iromEnd = 0x40400000L,
        dromStart = 0x3F400000L, dromEnd = 0x3F800000L,
        bootOff = 0x1000, magicValue = 0x00F01D83,
        usesExtendedHeader = true, stubKey = "esp32",
    )

    // py esptool/targets/esp32s2.py:16,17,27-30,32（boot_off 继承 ESP32ROM:106=0x1000）
    // js src/targets/esp32s2.ts:6,7,8-11,126,127
    val ESP32_S2 = EspChipParams(
        name = "ESP32-S2", chipId = 2,
        iromStart = 0x40080000L, iromEnd = 0x40B80000L,
        dromStart = 0x3F000000L, dromEnd = 0x3F3F0000L,
        bootOff = 0x1000, magicValue = 0x000007C6,
        usesExtendedHeader = true, stubKey = "esp32s2",
    )

    // py esptool/targets/esp32s3.py:16,17,27-30,44 / js src/targets/esp32s3.ts:6,7,18,32-33,37
    val ESP32_S3 = EspChipParams(
        name = "ESP32-S3", chipId = 9,
        iromStart = 0x42000000L, iromEnd = 0x44000000L,
        dromStart = 0x3C000000L, dromEnd = 0x3E000000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32s3",
    )

    // py esptool/targets/esp32c3.py:16,17,27-30,42 / js src/targets/esp32c3.ts:6,7,16,26-27,31
    val ESP32_C3 = EspChipParams(
        name = "ESP32-C3", chipId = 5,
        iromStart = 0x42000000L, iromEnd = 0x42800000L,
        dromStart = 0x3C000000L, dromEnd = 0x3C800000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32c3",
    )

    // py esptool/targets/esp32c2.py:15,16,26-29（继承 ESP32C3ROM:42 boot_off=0）
    // js src/targets/esp32c2.ts:6,7,16,26-27,31
    val ESP32_C2 = EspChipParams(
        name = "ESP32-C2", chipId = 12,
        iromStart = 0x42000000L, iromEnd = 0x42400000L,
        dromStart = 0x3C000000L, dromEnd = 0x3C400000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32c2",
    )

    // py esptool/targets/esp32c6.py:14,15,25-28,30 / js src/targets/esp32c6.ts:6,7,16,26-27,31
    val ESP32_C6 = EspChipParams(
        name = "ESP32-C6", chipId = 13,
        iromStart = 0x42000000L, iromEnd = 0x42800000L,
        dromStart = 0x42800000L, dromEnd = 0x43000000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32c6",
    )

    // py esptool/targets/esp32h2.py:11,12（class ESP32H2ROM(ESP32C6ROM) 继承 C6 全部 MAP/boot_off）
    // js src/targets/esp32h2.ts:6,7,16,30-31,35
    val ESP32_H2 = EspChipParams(
        name = "ESP32-H2", chipId = 16,
        iromStart = 0x42000000L, iromEnd = 0x42800000L,
        dromStart = 0x42800000L, dromEnd = 0x43000000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32h2",
    )

    // py esptool/targets/esp32p4.py:16,17,27-30,32 / js src/targets/esp32p4.ts:6,7,9-12,14
    // 注：py esp32p4.py:368-371 对 chip revision < 300 改选 esp32p4-rev1.json；本实现不做 revision 探测，
    // 只打包默认 esp32p4.json，选错时 OHAI 校验失败会自动降级 ROM（见战报）。
    val ESP32_P4 = EspChipParams(
        name = "ESP32-P4", chipId = 18,
        iromStart = 0x40000000L, iromEnd = 0x4C000000L,
        dromStart = 0x40000000L, dromEnd = 0x4C000000L,
        bootOff = 0x2000, magicValue = null,
        usesExtendedHeader = true, stubKey = "esp32p4",
    )

    /** 未知芯片兜底：沿用 R123 的 S3 宽区间（非官方值，仅保证 ELF 转换不丢 0x42 段）。 */
    val UNKNOWN = EspChipParams(
        name = "UNKNOWN", chipId = -2,
        iromStart = 0x42000000L, iromEnd = 0x44000000L,
        dromStart = 0x3C000000L, dromEnd = 0x3E000000L,
        bootOff = 0x0, magicValue = null,
        usesExtendedHeader = true, stubKey = null,
    )

    val ALL: List<EspChipParams> = listOf(
        ESP8266, ESP32, ESP32_S2, ESP32_S3, ESP32_C3,
        ESP32_C2, ESP32_C6, ESP32_H2, ESP32_P4, UNKNOWN,
    )

    fun byName(name: String): EspChipParams? = ALL.firstOrNull { it.name == name }
    fun byChipId(id: Int): EspChipParams? = ALL.firstOrNull { it.chipId == id }
    fun byMagic(magic: Int): EspChipParams? = ALL.firstOrNull { it.magicValue == magic }

    /**
     * chip_id ↔ stub 版本匹配校验（防把 A 芯片 stub 灌进 B 芯片）：
     * 先按芯片名取目标参数，再核对 GET_SECURITY_INFO 读回的 chip_id 与 IMAGE_CHIP_ID 是否一致；
     * 不一致或无该芯片 stub 时返回 null，调用方据此降级 ROM。
     */
    fun stubKeyFor(chip: EspChip): String? {
        val params = byName(chip.name) ?: return null
        val id = chip.chipId
        if (id != null && params.chipId != null && id != params.chipId) return null
        return params.stubKey
    }
}

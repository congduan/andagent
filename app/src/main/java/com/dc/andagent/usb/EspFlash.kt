package com.dc.andagent.usb

import java.security.MessageDigest

/**
 * ESP32 ROM bootloader 烧录协议核心（纯 JVM，不 import android.*）。
 *
 * 协议常量与字节布局对照 /tmp/esptool-src v5.x（esptool/loader.py、esptool/cmds.py）
 * 与 esp_pylib 1.2.1（serial_reset.py）。串口字节 IO 与控制线/延时均由调用方注入，
 * 便于 JVM 单测用脚本化 fake 串口跑通全流程。
 */

/** 命令表（esptool ESPLoader.ESP_CMDS）。 */
object EspCmd {
    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val FLASH_END = 0x04
    const val MEM_BEGIN = 0x05             // esptool/loader.py:306（"LOAD_RAM" 实为 MEM_* 序列，非 0x0D）
    const val MEM_END = 0x06               // esptool/loader.py:307
    const val MEM_DATA = 0x07
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D            // esptool/loader.py:314（0x0D 是 SPI_ATTACH，不是 LOAD_RAM）
    const val CHANGE_BAUDRATE = 0x0F
    const val SPI_FLASH_MD5 = 0x13
    const val GET_SECURITY_INFO = 0x14

    const val CHECKSUM_MAGIC = 0xEF
    const val FLASH_WRITE_SIZE = 0x400        // ROM 1KB：esptool/loader.py:357
    const val STUB_FLASH_WRITE_SIZE = 0x4000  // stub 16KB：esptool/loader.py:2234 StubMixin
    const val ESP_RAM_BLOCK = 0x1800          // stub 下载块：esptool/loader.py:355 / esploader.ts:108
    const val FLASH_SECTOR_SIZE = 0x1000      // 4KB
    const val ROM_INVALID_RECV_MSG = 0x05     // 不支持该命令时 ROM 回的 status[1]

    // ---- 时序 / 重试（对照官方常量）----
    const val SYNC_TIMEOUT_MS = 100          // esptool/loader.py:99 SYNC_TIMEOUT=0.1
    const val DEFAULT_TIMEOUT_MS = 3000      // esptool/loader.py:93 / esploader.ts:139
    const val MEM_END_ROM_TIMEOUT_MS = 200   // esptool/loader.py:107 MEM_END_ROM_TIMEOUT=0.2 / esploader.ts:991
    const val SYNC_RESPONSE_COUNT = 8        // ROM 连回 8 个 SYNC：esptool/loader.py:732-734 / esploader.ts:492-495
    const val DEFAULT_CONNECT_ATTEMPTS = 7   // esptool/loader.py:116 / esploader.ts:678
    const val WRITE_BLOCK_ATTEMPTS = 3       // esptool/loader.py:118 / esploader.ts:147
    const val WRITE_BLOCK_RETRY_DELAY_MS = 150  // esploader.ts:148 / :1136（py 无此延时，loader.py:1213-1234）
    const val ERASE_REGION_TIMEOUT_PER_MB_SEC = 40L  // esptool/loader.py:103（js 用 30s/MB，见战报）
    const val MD5_TIMEOUT_PER_MB_SEC = 8L           // esptool/loader.py:101 / esploader.ts:142
    const val MD5_RESP_DATA_LEN_ROM = 32     // 0x20：ROM 回 32B ASCII hex，esptool/loader.py:1624
    const val MD5_RESP_DATA_LEN_STUB = 16    // stub 回 16B 原始 digest，esptool/loader.py:1625
    const val OHAI_FIRST_BYTE = 0x4F         // "OHAI" 首字节，esptool/loader.py:1520

    const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000
}

/** 串口字节 IO（注入式；android 侧用 UsbSerialPort 适配 read/write）。 */
interface EspSerialIo {
    fun read(buf: ByteArray, timeoutMs: Int): Int
    fun write(buf: ByteArray): Int
}

/** 烧录失败统一异常。 */
class EspFlashException(message: String) : Exception(message)

/** 纯字节编解码：SLIP、请求/响应头、命令 payload、校验、对齐、芯片映射。 */
object EspFlashCodec {
    // ---------- 小端序工具 ----------
    fun putLe16(out: ByteArray, off: Int, v: Int) {
        out[off] = v.toByte(); out[off + 1] = (v ushr 8).toByte()
    }

    fun putLe32(out: ByteArray, off: Int, v: Int) {
        out[off] = v.toByte(); out[off + 1] = (v ushr 8).toByte()
        out[off + 2] = (v ushr 16).toByte(); out[off + 3] = (v ushr 24).toByte()
    }

    fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    fun packI(v: Int): ByteArray = ByteArray(4).also { putLe32(it, 0, v) }

    fun packII(a: Int, b: Int): ByteArray = ByteArray(8).also {
        putLe32(it, 0, a); putLe32(it, 4, b)
    }

    fun packIIII(a: Int, b: Int, c: Int, d: Int): ByteArray = ByteArray(16).also {
        putLe32(it, 0, a); putLe32(it, 4, b); putLe32(it, 8, c); putLe32(it, 12, d)
    }

    fun packIIIIII(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): ByteArray = ByteArray(24).also {
        putLe32(it, 0, a); putLe32(it, 4, b); putLe32(it, 8, c)
        putLe32(it, 12, d); putLe32(it, 16, e); putLe32(it, 20, f)
    }

    // ---------- SLIP ----------
    /** 帧 = 0xC0 + payload(0xDB→DB DD, 0xC0→DB DC) + 0xC0。 */
    fun slipEncode(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 2)
        out.add(0xC0.toByte())
        for (b in payload) {
            when (b.toInt() and 0xff) {
                0xDB -> { out.add(0xDB.toByte()); out.add(0xDD.toByte()) }
                0xC0 -> { out.add(0xDB.toByte()); out.add(0xDC.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(0xC0.toByte())
        return out.toByteArray()
    }

    /** 解一帧（含首尾 0xC0），返回还原后的 payload；遇 (0xDB, 非 DD/DC) 报错。 */
    fun slipDecode(frame: ByteArray): ByteArray {
        require(frame.size >= 2 && (frame[0].toInt() and 0xff) == 0xC0) { "SLIP 帧缺少起始 0xC0" }
        val out = ArrayList<Byte>(frame.size)
        var i = 1
        while (i < frame.size) {
            val v = frame[i].toInt() and 0xff
            when (v) {
                0xC0 -> return out.toByteArray()
                0xDB -> {
                    require(i + 1 < frame.size) { "SLIP 转义序列不完整" }
                    val n = frame[i + 1].toInt() and 0xff
                    when (n) {
                        0xDD -> out.add(0xDB.toByte())
                        0xDC -> out.add(0xC0.toByte())
                        else -> throw IllegalArgumentException("非法 SLIP 转义 0xDB,0x${n.toString(16)}")
                    }
                    i += 2
                }
                else -> { out.add(frame[i]); i++ }
            }
        }
        throw IllegalArgumentException("SLIP 帧缺少结束 0xC0")
    }

    // ---------- 请求 / 校验 / 响应 ----------
    /** 请求头 8B：0x00, op(u8), len(u16 LE), chk(u32 LE) + data。 */
    fun request(op: Int, data: ByteArray, chk: Int = 0): ByteArray {
        val out = ByteArray(8 + data.size)
        out[0] = 0x00
        out[1] = op.toByte()
        putLe16(out, 2, data.size)
        putLe32(out, 4, chk)
        data.copyInto(out, 8)
        return out
    }

    fun requestFrame(op: Int, data: ByteArray, chk: Int = 0): ByteArray =
        slipEncode(request(op, data, chk))

    /** checksum = 0xEF XOR data 全字节（data 空 = 0xEF）；仅数据类命令（FLASH_DATA 等）使用。 */
    fun checksum(data: ByteArray, state: Int = EspCmd.CHECKSUM_MAGIC): Int {
        var s = state
        for (b in data) s = s xor (b.toInt() and 0xff)
        return s
    }

    /** SYNC payload：07 07 12 20 + 32×0x55。 */
    fun syncPayload(): ByteArray = ByteArray(36).also {
        it[0] = 0x07; it[1] = 0x07; it[2] = 0x12; it[3] = 0x20
        for (i in 4 until 36) it[i] = 0x55
    }

    data class EspResponse(val direction: Int, val op: Int, val len: Int, val value: Int, val body: ByteArray)

    /** 响应头 8B：0x01, op_ret(u8), len(u16 LE), val(u32 LE) + body。direction!=1 由调用方筛掉。 */
    fun parseResponse(payload: ByteArray): EspResponse? {
        if (payload.size < 8) return null
        return EspResponse(
            payload[0].toInt() and 0xff,
            payload[1].toInt() and 0xff,
            le16(payload, 2),
            le32(payload, 4),
            payload.copyOfRange(8, payload.size),
        )
    }

    // ---------- 命令 payload ----------
    /**
     * FLASH_BEGIN <IIII>(eraseSize, numBlocks, blockSize, offset)。
     * extended：追加第 5 个 <I>(0)。esptool/loader.py:1199-1201 的条件是
     *   `IS_STUB or CHIP_NAME not in ("ESP32","ESP8266")` —— 即 stub 模式对 ESP32/ESP8266
     *   也追加；ROM 模式仅非 ESP32/ESP8266 追加（js esploader.ts:1040-1042 则 ROM 一律追加，见战报）。
     * blockSize：ROM 0x400 / stub 0x4000。
     */
    fun flashBeginParams(
        eraseSize: Int, numBlocks: Int, offset: Int,
        extended: Boolean = false, blockSize: Int = EspCmd.FLASH_WRITE_SIZE,
    ): ByteArray {
        val base = packIIII(eraseSize, numBlocks, blockSize, offset)
        return if (extended) base + packI(0) else base
    }

    fun flashDataHeader(size: Int, seq: Int): ByteArray = packIIII(size, seq, 0, 0)

    fun flashEndParams(reboot: Boolean): ByteArray = packI(if (reboot) 0 else 1)

    fun md5Params(addr: Int, size: Int): ByteArray = packIIII(addr, size, 0, 0)

    // ---------- stub 下载命令 payload ----------
    /** MEM_BEGIN <IIII>(size, blocks, blocksize, offset)：esptool/loader.py:1146 / esploader.ts:951-954。 */
    fun memBeginParams(size: Int, blocks: Int, blocksize: Int, offset: Int): ByteArray =
        packIIII(size, blocks, blocksize, offset)

    /**
     * MEM_END <II>(int(entrypoint==0), entrypoint)：esptool/loader.py:1165 / esploader.ts:989-991。
     * 注意与 FLASH_END 的单字 <I> 不同；stub 的 entry 非 0，故第一字为 0。
     */
    fun memFinishParams(entry: Int): ByteArray = packII(if (entry == 0) 1 else 0, entry)

    /** 字节 → 小写 hex（stub 的 MD5 回 16B 原始 digest，需转 32 字符 hex 再比对）。 */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** esptool timeout_per_mb（loader.py:134-139 / esploader.ts:1009-1016）：max(3s, 每秒/MB×size)。 */
    fun timeoutPerMb(secondsPerMb: Long, sizeBytes: Int): Int =
        maxOf(EspCmd.DEFAULT_TIMEOUT_MS.toLong(), secondsPerMb * sizeBytes / 1000).toInt()

    /** SPI_ATTACH：<I>(hspi=0) + BBBB(is_legacy=0,0,0,0)，共 8 个 0x00。 */
    fun spiAttachParams(): ByteArray = ByteArray(8)

    /** SPI_SET_PARAMS：<IIIIII> = (0, 总字节, 65536, 4096, 256, 0xFFFF)。 */
    fun spiSetParams(totalSize: Int): ByteArray = packIIIIII(0, totalSize, 65536, 4096, 256, 0xFFFF)

    fun readRegParams(addr: Int): ByteArray = packI(addr)

    // ---------- 对齐 / 校验 / 芯片识别 ----------
    data class AlignedImage(val offset: Int, val data: ByteArray)

    /** ROM 路径对齐：镜像本体 pad 到 4 字节；offset 非扇区对齐时地址下对齐 0x1000、头部补 0xFF。 */
    fun alignImage(image: ByteArray, offset: Int): AlignedImage {
        val mod = image.size % 4
        val padded = if (mod == 0) image else image + ByteArray(4 - mod) { 0xFF.toByte() }
        val bytesOver = offset % EspCmd.FLASH_SECTOR_SIZE
        if (bytesOver == 0) return AlignedImage(offset, padded)
        return AlignedImage(offset - bytesOver, ByteArray(bytesOver) { 0xFF.toByte() } + padded)
    }

    /** ESP8266 ROM erase bug 工作量公式（esptool ESP8266ROM.get_erase_size）。 */
    fun esp8266EraseSize(offset: Int, size: Int): Int {
        val sector = EspCmd.FLASH_SECTOR_SIZE
        val numSectors = (size + sector - 1) / sector
        val startSector = offset / sector
        var head = 16 - startSector % 16
        if (numSectors < head) head = numSectors
        return if (numSectors < 2 * head) (numSectors + 1) / 2 * sector else (numSectors - head) * sector
    }

    fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** GET_SECURITY_INFO chip_id → 芯片名（全表见 EspTargets；UNKNOWN 不当作有效识别）。 */
    fun chipNameForId(chipId: Int): String? =
        EspTargets.byChipId(chipId)?.takeIf { it.name != "UNKNOWN" }?.name

    /** READ_REG(0x40001000) magic → 芯片名。 */
    fun chipNameForMagic(magic: Int): String? = EspTargets.byMagic(magic)?.name
}

/** 复位进 bootloader / 硬复位的 DTR/RTS 步序列（纯数据，供注入的 control/sleep 执行）。
 * 布尔约定：True=asserted（active-low 电路把线拉低）。
 * 对照 esp_pylib classic_bootloader_reset / hard_reset：
 *  物理 DTR=H,RTS=L(EN 低,复位) → (dtr=false,rts=true)；sleep 100ms；
 *  物理 DTR=L,RTS=H(出复位) → (dtr=true,rts=false)；sleep 50ms；
 *  物理 DTR=H(释放 IO0) → (dtr=false,rts=false)。
 */
object EspReset {
    data class Step(val dtr: Boolean, val rts: Boolean, val sleepAfterMs: Long)

    /** esptool.js DEFAULT_RESET_DELAY=50 / EXTRA_DELAY=550（esploader.ts:631-637）。 */
    const val CLASSIC_RESET_DELAY_MS = 50L
    const val CLASSIC_EXTRA_RESET_DELAY_MS = 550L
    /** esp_pylib classic_bootloader_reset enter_boot_delay=0.1（serial_reset.py:322-329）。 */
    const val CLASSIC_ENTER_BOOT_MS = 100L
    /** UsbJtagSerialReset 各段 settle_delay=0.1（reset.ts:111-127）。 */
    const val USB_JTAG_SETTLE_MS = 100L

    /**
     * Classic reset（D0|R1|W100|D1|R0|W{resetDelay}|D0）——
     * 逐条对照 esp_pylib serial_reset.py:322-329 classic_bootloader_reset 与 esptool.js reset.ts:81-87 ClassicReset：
     *  DTR=H,RTS=L（EN 低进复位）→ 100ms → DTR=L,RTS=H（IO0 低、出复位）→ resetDelay → DTR=H（释放 IO0）。
     * 布尔约定：True=asserted（active-low 电路把线拉低）。
     */
    fun enterBootloader(resetDelayMs: Long = CLASSIC_RESET_DELAY_MS): List<Step> = listOf(
        Step(dtr = false, rts = true, sleepAfterMs = CLASSIC_ENTER_BOOT_MS),
        Step(dtr = true, rts = false, sleepAfterMs = resetDelayMs),
        Step(dtr = false, rts = false, sleepAfterMs = 0),
    )

    /** 硬复位：RTS=L 100ms → RTS=H（EN 由低拉高重启）；对照 serial_reset.py:438-442 / reset.ts:150-159。 */
    fun hardReset(): List<Step> = listOf(
        Step(dtr = false, rts = true, sleepAfterMs = 100),
        Step(dtr = false, rts = false, sleepAfterMs = 0),
    )

    /**
     * USB-Serial-JTAG 复位（内部 USB 桥，C3/S3/C6/P4 等）：
     * 逐条对照 esptool.js reset.ts:111-127 UsbJtagSerialReset.reset() 与
     * esp_pylib serial_reset.py:380-391 usb_jtag_bootloader_reset()：
     *  (DTR=H,RTS=H) idle 100ms → (DTR=L,RTS=H) 置 IO0 100ms →
     *  (DTR=H,RTS=L) 复位 100ms → (DTR=H,RTS=H) 出复位。
     * 本实现只把它作为纯数据提供；工具层仍走 classic（选择 USB-JTAG 需 VID/PID，
     * 而 Kotlin USB 通道零改动，不引入新参数），战报登记该未接线边界。
     */
    fun usbJtagSerialReset(): List<Step> = listOf(
        Step(dtr = false, rts = false, sleepAfterMs = USB_JTAG_SETTLE_MS),
        Step(dtr = true, rts = false, sleepAfterMs = USB_JTAG_SETTLE_MS),
        Step(dtr = false, rts = true, sleepAfterMs = USB_JTAG_SETTLE_MS),
        Step(dtr = false, rts = false, sleepAfterMs = 0),
    )
}

data class EspChip(val name: String, val chipId: Int?)

data class EspFlashResult(
    val chip: String,
    val chipId: Int?,
    val mac: String?,
    val offset: Int,
    val bytes: Int,
    val blocks: Int,
    val md5: String,
    val verified: Boolean,
    val elapsedMs: Long,
    val warning: String?,
    val baud: Int?,        // 实际生效烧录波特率（切速成功=目标 baud，降级/未切=initialBaud）
    val source: String,    // "elf"（ELF 自动转 ESP image）| "bin"（原样烧录）
    val loader: String = "rom",   // "stub" | "rom"：本次实际生效的加载器（stub 失败自动降级为 rom）
)

private data class EspReply(val value: Int, val body: ByteArray)
private data class EspCommandResult(val value: Int, val data: ByteArray)

/** 流式 SLIP 解帧：跨多次 read 累积，复用 EspFlashCodec.slipDecode 做单帧还原。 */
private class SlipReader {
    private val frames = ArrayDeque<ByteArray>()
    private var frame: MutableList<Byte>? = null

    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val v = buf[i].toInt() and 0xff
            val f = frame
            if (f == null) {
                if (v == 0xC0) frame = mutableListOf(0xC0.toByte())
                // 帧外噪声（如 ROM boot log）直接丢弃
            } else {
                f.add(buf[i])
                if (v == 0xC0) {
                    if (f.size >= 2) frames.add(EspFlashCodec.slipDecode(f.toByteArray()))
                    frame = null
                }
            }
        }
    }

    fun poll(): ByteArray? = if (frames.isEmpty()) null else frames.removeFirst()
    fun pushFirst(f: ByteArray) { frames.addFirst(f) }
    fun reset() { frames.clear(); frame = null }
}

/**
 * ROM / Stub bootloader 烧录状态机：connect → detect → [runStub] → changeBaud → flash → verify → reset。
 * control = setDtrRts(dtr,rts)，sleepMs = 毫秒延时，均由调用方注入（android 侧适配）。
 * stubProvider 为 null（默认）时行为与 R123 完全一致（纯 ROM）；非 null 时尝试装载 stub，
 * 任一步失败自动复位回 ROM 模式（现有 ROM 路径完全保留，调用方无感）。
 */
class EspFlasher(
    private val io: EspSerialIo,
    private val control: (Boolean, Boolean) -> Unit,
    private val sleepMs: (Long) -> Unit,
    private val setBaud: ((Int) -> Unit)? = null,   // 切换串口波特率（android 侧在 session.lock 下 applyBaud）
    private val initialBaud: Int? = null,           // open 时波特率（结束后恢复）
    private val stubProvider: ((EspChip) -> EspStub?)? = null,  // 按芯片取已打包的 stub（android 侧读 assets）
) {
    private val slip = SlipReader()

    /** 全流程：返回结构化结果供工具层拼 JSON。loader="stub"|"rom" 标明实际生效的加载器。 */
    fun flash(image: ByteArray, offset: Int, flashSizeBytes: Int, baud: Int? = null): EspFlashResult {
        val start = System.nanoTime()
        connect()
        val chip = detectChip()
        var warning = if (chip.name == "UNKNOWN") "未知芯片，按 ESP32 家族流程继续" else null
        val isEsp8266 = chip.name == "ESP8266"
        // ROM 模式 FLASH_BEGIN 第 5 字条件（esptool/loader.py:1200）：非 ESP32/ESP8266 追加。
        val romExtended = !isEsp8266 && chip.name != "ESP32"

        // ELF 自动转 ESP image：detectChip 之后按芯片选镜像 flavor（source=elf）；否则原样（source=bin）。
        // 先把这段纯 CPU 转换做完，避免 ELF 非法时留下半加载的 stub 在芯片里。
        val (bin, source) = if (EspImage.isElf(image)) {
            EspImage.fromElf(image).toBinary(chip) to "elf"
        } else {
            image to "bin"
        }

        // ---- Stub loader（任务 1）：对照 esptool/loader.py:1466-1527 / esploader.ts:1473-1524 ----
        // 顺序与官方一致：detect → runStub → changeBaud → flash。失败自动复位回 ROM 模式。
        var isStub = false
        if (stubProvider != null) {
            val stub = runCatching { stubProvider!!(chip) }.getOrNull()
            if (stub != null) {
                try {
                    loadStub(stub)
                    isStub = true
                } catch (e: EspFlashException) {
                    warning = listOfNotNull(warning, "stub 下载/启动失败，已降级 ROM 模式").joinToString("；")
                    // 复位重连，清掉可能半加载的 stub 状态，再走完全保留的 ROM 路径。
                    runCatching { connect() }
                    isStub = false
                }
            }
        }

        // CHANGE_BAUDRATE：SYNC 已在 connect() 成功，命令应答在旧波特率，收到响应后才切本端。
        // baud 为 null/≤0、与 initialBaud 相同、或没注入 setBaud/initialBaud 时行为与现状完全一致（不切速）。
        var effectiveBaud = initialBaud
        val targetBaud = baud?.takeIf { it > 0 && it != initialBaud && setBaud != null && initialBaud != null }
        if (targetBaud != null) {
            try {
                // payload=<II(new, second)>：esptool/loader.py:1644-1647 / esploader.ts:1553-1555。
                // ROM 模式 second=0；stub 模式 second=旧波特率（romBaudrate）。
                val secondArg = if (isStub) (initialBaud ?: 0) else 0
                val buf = ByteArray(8)
                EspFlashCodec.putLe32(buf, 0, targetBaud)
                EspFlashCodec.putLe32(buf, 4, secondArg)
                checkCommand(EspCmd.CHANGE_BAUDRATE, buf)
                setBaud!!(targetBaud)
                sleepMs(50)
                effectiveBaud = targetBaud
            } catch (e: Exception) {
                // 切速失败（命令被拒 EspFlashException 或本端 setBaud 异常）：降级回原波特率继续烧，
                // 不因此失败整个烧录。
                initialBaud?.let { runCatching { setBaud!!(it) } }
                effectiveBaud = initialBaud
                warning = listOfNotNull(warning, "baud 切换失败已降级").joinToString("；")
            }
        }

        val aligned = EspFlashCodec.alignImage(bin, offset)
        val data = aligned.data
        val alignedOffset = aligned.offset

        // stub 启动时已自行初始化 SPI flash：跳过 SPI_ATTACH / SPI_SET_PARAMS
        // （js esploader.ts writeFlash 不发这两条；py cmds.py:2088 的 SPI_ATTACH 亦仅 ROM 分支）。
        // ROM 路径两条命令完全保留。
        if (!isEsp8266 && !isStub) {
            checkCommand(EspCmd.SPI_ATTACH, EspFlashCodec.spiAttachParams())
            checkCommand(EspCmd.SPI_SET_PARAMS, EspFlashCodec.spiSetParams(flashSizeBytes))
        }

        // ROM 0x400 / stub 0x4000（py loader.py:357 / :2234）。
        val writeSize = if (isStub) EspCmd.STUB_FLASH_WRITE_SIZE else EspCmd.FLASH_WRITE_SIZE
        val numBlocks = (data.size + writeSize - 1) / writeSize
        // py esp8266.py:160-181 get_erase_size 是 ROM 的擦除 bug 工作量；stub 无此 bug（:183-184 返回 size）。
        val eraseSize = if (isEsp8266 && !isStub) EspFlashCodec.esp8266EraseSize(alignedOffset, data.size)
            else data.size
        // py loader.py:1188-1193：stub 用 DEFAULT_TIMEOUT，ROM 才按擦除量放大（40s/MB）。
        val beginTimeout = if (isStub) EspCmd.DEFAULT_TIMEOUT_MS
            else EspFlashCodec.timeoutPerMb(EspCmd.ERASE_REGION_TIMEOUT_PER_MB_SEC, data.size)
        // py loader.py:1200：stub 模式对 ESP32/ESP8266 也追加第 5 字。
        val beginExtended = isStub || romExtended
        checkCommand(EspCmd.FLASH_BEGIN,
            EspFlashCodec.flashBeginParams(eraseSize, numBlocks, alignedOffset, beginExtended, writeSize),
            timeoutMs = beginTimeout)

        for (seq in 0 until numBlocks) {
            // 末块补 0xFF 到 writeSize（py cmds.py:1827-1828 / js esploader.ts:1934-1939）。
            val block = ByteArray(writeSize) { 0xFF.toByte() }
            val src = seq * writeSize
            val len = minOf(writeSize, data.size - src)
            data.copyInto(block, 0, src, src + len)
            val payload = EspFlashCodec.flashDataHeader(writeSize, seq) + block
            // 写块失败重试 WRITE_BLOCK_ATTEMPTS 次；js esploader.ts:1136 每次间隔 150ms（py 无间隔）。
            for (attempt in 0 until EspCmd.WRITE_BLOCK_ATTEMPTS) {
                try {
                    checkCommand(EspCmd.FLASH_DATA, payload, chk = EspFlashCodec.checksum(block))
                    break
                } catch (e: EspFlashException) {
                    if (attempt == EspCmd.WRITE_BLOCK_ATTEMPTS - 1) throw e
                    sleepMs(EspCmd.WRITE_BLOCK_RETRY_DELAY_MS.toLong())
                }
            }
        }

        // FLASH_END：reboot=false（payload=1，留在下载模式供 MD5 校验）。
        // 注：py cmds.py:1895-1897 明确「ROM 跳过 flash_finish」，本实现 ROM 路径沿用 R123 现状不改
        // （简报要求「现有路径完全保留」），该差异在战报登记。
        checkCommand(EspCmd.FLASH_END, EspFlashCodec.flashEndParams(reboot = false))

        val expected = EspFlashCodec.md5Hex(data)
        // MD5 响应长度：ROM 32B ASCII hex（0x20）/ stub 16B 原始 digest（py loader.py:1624-1638）。
        val md5RespLen = if (isStub) EspCmd.MD5_RESP_DATA_LEN_STUB else EspCmd.MD5_RESP_DATA_LEN_ROM
        val md5Timeout = EspFlashCodec.timeoutPerMb(EspCmd.MD5_TIMEOUT_PER_MB_SEC, data.size)
        val reply = checkCommand(EspCmd.SPI_FLASH_MD5,
            EspFlashCodec.md5Params(alignedOffset, data.size),
            respDataLen = md5RespLen, timeoutMs = md5Timeout)
        val got = if (isStub) EspFlashCodec.hex(reply.data)
            else String(reply.data, Charsets.US_ASCII).lowercase()
        val verified = got == expected

        // FLASH_END/MD5 校验完成后恢复 open 时波特率（恢复失败不抛，仅 warning）。
        if (effectiveBaud != initialBaud) {
            initialBaud?.let { orig ->
                if (runCatching { setBaud!!(orig) }.isFailure) {
                    warning = listOfNotNull(warning, "恢复原波特率失败").joinToString("；")
                }
            }
        }

        applyReset(EspReset.hardReset())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        return EspFlashResult(chip.name, chip.chipId, null, offset, data.size, numBlocks,
            expected, verified, elapsedMs, warning, effectiveBaud, source,
            loader = if (isStub) "stub" else "rom")
    }

    /** 芯片识别：GET_SECURITY_INFO(chip_id) → 12B(S2) → READ_REG magic → 未知但继续。 */
    fun detectChip(): EspChip {
        try {
            val d20 = checkCommand(EspCmd.GET_SECURITY_INFO, ByteArray(0), respDataLen = 20).data
            val chipId = EspFlashCodec.le32(d20, 12)
            return EspChip(EspFlashCodec.chipNameForId(chipId) ?: "UNKNOWN", chipId)
        } catch (e: EspFlashException) { /* 20B 失败：S2(12B) 或命令不支持 */ }

        try {
            checkCommand(EspCmd.GET_SECURITY_INFO, ByteArray(0), respDataLen = 12)
            return EspChip("ESP32-S2", null)
        } catch (e: EspFlashException) { /* 继续 magic 路径 */ }

        val magic = try {
            checkCommand(EspCmd.READ_REG,
                EspFlashCodec.readRegParams(EspCmd.CHIP_DETECT_MAGIC_REG_ADDR),
                respDataLen = 0).value
        } catch (e: EspFlashException) { -1 }
        return EspChip(EspFlashCodec.chipNameForMagic(magic) ?: "UNKNOWN", null)
    }

    // ---------- 内部协议 ----------
    /**
     * 复位进 bootloader + SYNC：外层 DEFAULT_CONNECT_ATTEMPTS=7（py loader.py:116 / js esploader.ts:658），
     * 每轮 5 次 SYNC（js esploader.ts:531）；奇数轮用 EXTRA_DELAY=550ms 复位（js esploader.ts:631-637）。
     * SYNC 成功后把 ROM 连回的额外 7 个 SYNC 应答从缓冲里消费掉（py loader.py:732-734 /
     * js esploader.ts:492-495）；command() 的 100 帧 op 过滤本就能容忍，这里只是显式对齐。
     */
    private fun connect() {
        var lastErr: Exception? = null
        for (attempt in 0 until EspCmd.DEFAULT_CONNECT_ATTEMPTS) {
            val delay = if (attempt % 2 == 0) EspReset.CLASSIC_RESET_DELAY_MS
                else EspReset.CLASSIC_EXTRA_RESET_DELAY_MS
            applyReset(EspReset.enterBootloader(delay))
            slip.reset()
            repeat(5) {
                try {
                    command(EspCmd.SYNC, EspFlashCodec.syncPayload(), timeoutMs = EspCmd.SYNC_TIMEOUT_MS)
                    drainExtraSyncResponses()
                    return
                } catch (e: EspFlashException) { lastErr = e }
            }
        }
        throw EspFlashException("无法连接 ROM bootloader：${lastErr?.message}")
    }

    /** 非阻塞消费已缓冲的额外 SYNC 应答；遇到非 SYNC 帧放回队首（对齐 8 响应计数）。 */
    private fun drainExtraSyncResponses() {
        repeat(EspCmd.SYNC_RESPONSE_COUNT - 1) {
            val extra = slip.poll() ?: return
            val r = EspFlashCodec.parseResponse(extra)
            if (r == null || r.op != EspCmd.SYNC) {
                slip.pushFirst(extra)
                return
            }
        }
    }

    /**
     * 装载 stub（任务 1 核心）：展开纯函数下载状态机逐条发送，最后读 OHAI。
     * py loader.py:1466-1527 / js esploader.ts:1473-1524；
     * MEM_END 后 ROM 常不回包（py loader.py:1160-1176 用短超时并忽略错误），
     * 故该条 fire-and-forget，随后在若干帧内找裸 SLIP 包 "OHAI"（首字节 0x4F）。
     */
    private fun loadStub(stub: EspStub) {
        for (op in EspStubLoader.buildLoadSequence(stub)) {
            if (op.awaitReply) {
                checkCommand(op.op, op.data, chk = op.chk, timeoutMs = EspCmd.DEFAULT_TIMEOUT_MS)
            } else {
                io.write(EspFlashCodec.requestFrame(op.op, op.data, op.chk))
            }
        }
        var ohai = false
        for (i in 0 until 12) {
            val frame = try {
                readFrame(EspCmd.DEFAULT_TIMEOUT_MS)
            } catch (e: EspFlashException) {
                break
            }
            // MEM_END 迟到的 status 包（8B 头）/ 其它噪声：跳过，继续等 OHAI。
            if (EspStubLoader.isOhai(frame)) { ohai = true; break }
        }
        if (!ohai) throw EspFlashException("stub 启动失败：未收到 OHAI 应答")
        slip.reset()
    }

    private fun applyReset(steps: List<EspReset.Step>) {
        for (s in steps) {
            control(s.dtr, s.rts)
            if (s.sleepAfterMs > 0) sleepMs(s.sleepAfterMs)
        }
    }

    /** 发一帧请求，最多读 100 帧直到 op_ret==op；超时/非法 SLIP 立刻失败。 */
    private fun command(op: Int, data: ByteArray, chk: Int = 0, timeoutMs: Int): EspReply {
        io.write(EspFlashCodec.requestFrame(op, data, chk))
        repeat(100) {
            val frame = readFrame(timeoutMs)
            if (frame.size < 8) return@repeat
            val resp = EspFlashCodec.parseResponse(frame) ?: return@repeat
            if (resp.direction != 1) return@repeat
            if (resp.op == op) return EspReply(resp.value, resp.body)
            // 命令不支持：ROM 回 op_ret!=op 且 status[1]==0x05（ROM_INVALID_RECV_MSG）。
            // ROM 会把该错误响应重发 8 次（py loader.py:644-657 drain_input_buffer /
            // js esploader.ts:366-370 drainInput），先清空缓冲再抛，避免污染后续命令。
            if (resp.body.size >= 2 && (resp.body[0].toInt() and 0xff) != 0 &&
                (resp.body[1].toInt() and 0xff) == EspCmd.ROM_INVALID_RECV_MSG) {
                slip.reset()
                throw EspFlashException("命令 0x${op.toString(16)} 不被 ROM 支持")
            }
        }
        throw EspFlashException("响应与请求不匹配（100 帧内）")
    }

    /** 校验 2 个状态字节（status[0]!=0 即失败，status[1]=原因）；返回 val 与 respDataLen 段数据。 */
    private fun checkCommand(
        op: Int, data: ByteArray, chk: Int = 0, respDataLen: Int = 0,
        timeoutMs: Int = EspCmd.DEFAULT_TIMEOUT_MS,
    ): EspCommandResult {
        val reply = command(op, data, chk, timeoutMs)
        val b = reply.body
        // 状态字节在期望数据段之后；响应过短时退化为头 2 字节（对齐 esptool check_command）。
        val statusPos = if (b.size >= respDataLen + 2) respDataLen else 0
        val s0 = b.getOrElse(statusPos) { 0.toByte() }.toInt() and 0xff
        val s1 = b.getOrElse(statusPos + 1) { 0.toByte() }.toInt() and 0xff
        if (s0 != 0) throw EspFlashException(
            "命令 0x${op.toString(16)} 失败：status=0x${s0.toString(16)},reason=0x${s1.toString(16)}")
        if (b.size < respDataLen + 2) throw EspFlashException(
            "命令 0x${op.toString(16)} 响应过短：${b.size} < ${respDataLen + 2}")
        val out = if (respDataLen > 0) b.copyOfRange(0, respDataLen) else ByteArray(0)
        return EspCommandResult(reply.value, out)
    }

    private fun readFrame(timeoutMs: Int): ByteArray {
        while (true) {
            slip.poll()?.let { return it }
            val buf = ByteArray(4096)
            val n = io.read(buf, timeoutMs)
            if (n <= 0) throw EspFlashException("串口无数据（超时 ${timeoutMs}ms）")
            slip.feed(buf, n)
        }
    }
}

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
    const val MEM_BEGIN = 0x05
    const val MEM_DATA = 0x07
    const val SYNC = 0x08
    const val WRITE_REG = 0x09
    const val READ_REG = 0x0A
    const val SPI_SET_PARAMS = 0x0B
    const val SPI_ATTACH = 0x0D
    const val CHANGE_BAUDRATE = 0x0F
    const val SPI_FLASH_MD5 = 0x13
    const val GET_SECURITY_INFO = 0x14

    const val CHECKSUM_MAGIC = 0xEF
    const val FLASH_WRITE_SIZE = 0x400        // 1KB
    const val FLASH_SECTOR_SIZE = 0x1000      // 4KB
    const val ROM_INVALID_RECV_MSG = 0x05     // 不支持该命令时 ROM 回的 status[1]
    const val SYNC_TIMEOUT_MS = 100
    const val DEFAULT_TIMEOUT_MS = 3000
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
    fun flashBeginParams(eraseSize: Int, numBlocks: Int, offset: Int, extended: Boolean = false): ByteArray {
        val base = packIIII(eraseSize, numBlocks, EspCmd.FLASH_WRITE_SIZE, offset)
        // esptool：ESP32/ESP8266 ROM 用 4×u32，其余芯片 ROM 追加第 5 个 <I>(0)。
        return if (extended) base + packI(0) else base
    }

    fun flashDataHeader(size: Int, seq: Int): ByteArray = packIIII(size, seq, 0, 0)

    fun flashEndParams(reboot: Boolean): ByteArray = packI(if (reboot) 0 else 1)

    fun md5Params(addr: Int, size: Int): ByteArray = packIIII(addr, size, 0, 0)

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

    /** GET_SECURITY_INFO chip_id → 芯片名（本轮范围：S3/C3/C6/H2/C2）。 */
    fun chipNameForId(chipId: Int): String? = when (chipId) {
        5 -> "ESP32-C3"
        9 -> "ESP32-S3"
        12 -> "ESP32-C2"
        13 -> "ESP32-C6"
        16 -> "ESP32-H2"
        else -> null
    }

    /** READ_REG(0x40001000) magic → 芯片名。 */
    fun chipNameForMagic(magic: Int): String? = when (magic) {
        0xFFF0C101L.toInt() -> "ESP8266"
        0x00F01D83 -> "ESP32"
        0x000007C6 -> "ESP32-S2"
        else -> null
    }
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

    fun enterBootloader(): List<Step> = listOf(
        Step(dtr = false, rts = true, sleepAfterMs = 100),
        Step(dtr = true, rts = false, sleepAfterMs = 50),
        Step(dtr = false, rts = false, sleepAfterMs = 0),
    )

    /** 硬复位：RTS=L 100ms → RTS=H（EN 由低拉高重启）。 */
    fun hardReset(): List<Step> = listOf(
        Step(dtr = false, rts = true, sleepAfterMs = 100),
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
    fun reset() { frames.clear(); frame = null }
}

/**
 * ROM bootloader 烧录状态机：connect → detect → attach/setParams → flash → verify → reset。
 * control = setDtrRts(dtr,rts)，sleepMs = 毫秒延时，均由调用方注入（android 侧适配）。
 */
class EspFlasher(
    private val io: EspSerialIo,
    private val control: (Boolean, Boolean) -> Unit,
    private val sleepMs: (Long) -> Unit,
    private val setBaud: ((Int) -> Unit)? = null,   // 切换串口波特率（android 侧在 session.lock 下 applyBaud）
    private val initialBaud: Int? = null,           // open 时波特率（结束后恢复）
) {
    private val slip = SlipReader()

    /** 全流程：返回结构化结果供工具层拼 JSON。 */
    fun flash(image: ByteArray, offset: Int, flashSizeBytes: Int, baud: Int? = null): EspFlashResult {
        val start = System.nanoTime()
        connect()
        val chip = detectChip()
        var warning = if (chip.name == "UNKNOWN") "未知芯片，按 ESP32 家族流程继续" else null
        val isEsp8266 = chip.name == "ESP8266"
        val extended = !isEsp8266 && chip.name != "ESP32"

        // ELF 自动转 ESP image：detectChip 之后按芯片选镜像 flavor（source=elf）；否则原样（source=bin）。
        val (bin, source) = if (EspImage.isElf(image)) {
            EspImage.fromElf(image).toBinary(chip) to "elf"
        } else {
            image to "bin"
        }

        // CHANGE_BAUDRATE：SYNC 已在 connect() 成功，命令应答在旧波特率，收到响应后才切本端。
        // baud 为 null/≤0、与 initialBaud 相同、或没注入 setBaud/initialBaud 时行为与现状完全一致（不切速）。
        var effectiveBaud = initialBaud
        val targetBaud = baud?.takeIf { it > 0 && it != initialBaud && setBaud != null && initialBaud != null }
        if (targetBaud != null) {
            try {
                // payload=<II(baud, 0)>：esptool v5.6.0 loader.py change_baud 发 8B；ROM 模式第二字固定 0
                // （只有 stub 模式才传旧波特率，见官方 serial-protocol 文档）。
                val buf = ByteArray(8)
                EspFlashCodec.putLe32(buf, 0, targetBaud)
                EspFlashCodec.putLe32(buf, 4, 0)
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

        if (!isEsp8266) {
            checkCommand(EspCmd.SPI_ATTACH, EspFlashCodec.spiAttachParams())
            checkCommand(EspCmd.SPI_SET_PARAMS, EspFlashCodec.spiSetParams(flashSizeBytes))
        }

        val numBlocks = (data.size + EspCmd.FLASH_WRITE_SIZE - 1) / EspCmd.FLASH_WRITE_SIZE
        val eraseSize = if (isEsp8266) EspFlashCodec.esp8266EraseSize(alignedOffset, data.size) else data.size
        // ROM 前置擦除慢：max(3s, 40s×MB)。
        val beginTimeout = maxOf(EspCmd.DEFAULT_TIMEOUT_MS.toLong(), 40L * data.size.toLong() / 1000).toInt()
        checkCommand(EspCmd.FLASH_BEGIN,
            EspFlashCodec.flashBeginParams(eraseSize, numBlocks, alignedOffset, extended),
            timeoutMs = beginTimeout)

        for (seq in 0 until numBlocks) {
            val block = ByteArray(EspCmd.FLASH_WRITE_SIZE) { 0xFF.toByte() }
            val src = seq * EspCmd.FLASH_WRITE_SIZE
            val len = minOf(EspCmd.FLASH_WRITE_SIZE, data.size - src)
            data.copyInto(block, 0, src, src + len)
            val payload = EspFlashCodec.flashDataHeader(EspCmd.FLASH_WRITE_SIZE, seq) + block
            var ok = false
            // 写块失败最多重试 3 次（esptool WRITE_BLOCK_ATTEMPTS）。
            for (attempt in 0 until 3) {
                try {
                    checkCommand(EspCmd.FLASH_DATA, payload, chk = EspFlashCodec.checksum(block))
                    ok = true; break
                } catch (e: EspFlashException) {
                    if (attempt == 2) throw e
                }
            }
            if (!ok) throw EspFlashException("FLASH_DATA seq=$seq 失败")
        }

        // FLASH_END：reboot=false（payload=1，留在下载模式供 MD5 校验）。
        checkCommand(EspCmd.FLASH_END, EspFlashCodec.flashEndParams(reboot = false))

        val expected = EspFlashCodec.md5Hex(data)
        val md5Timeout = maxOf(EspCmd.DEFAULT_TIMEOUT_MS.toLong(), 8L * data.size.toLong() / 1000).toInt()
        val reply = checkCommand(EspCmd.SPI_FLASH_MD5,
            EspFlashCodec.md5Params(alignedOffset, data.size),
            respDataLen = 32, timeoutMs = md5Timeout)
        val got = String(reply.data, Charsets.US_ASCII).lowercase()
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
            expected, verified, elapsedMs, warning, effectiveBaud, source)
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
    private fun connect() {
        var lastErr: Exception? = null
        repeat(5) {
            applyReset(EspReset.enterBootloader())
            slip.reset()
            repeat(5) {
                try {
                    command(EspCmd.SYNC, EspFlashCodec.syncPayload(), timeoutMs = EspCmd.SYNC_TIMEOUT_MS)
                    return
                } catch (e: EspFlashException) { lastErr = e }
            }
        }
        throw EspFlashException("无法连接 ROM bootloader：${lastErr?.message}")
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
            if (resp.body.size >= 2 && (resp.body[0].toInt() and 0xff) != 0 &&
                (resp.body[1].toInt() and 0xff) == EspCmd.ROM_INVALID_RECV_MSG) {
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

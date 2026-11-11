package com.dc.andagent.usb

/**
 * Flasher stub 容器解析 + 下载状态机（纯 JVM，不 import android.*）。
 *
 * 协议复刻 esptool.py/esptool.js 的 run_stub / runStub：
 *  - py esptool/loader.py:1466-1527 run_stub()：逐段 mem_begin/mem_block，最后 mem_finish(entry)，
 *    再读一个裸 SLIP 包，内容必须等于 b"OHAI"（:1520）；
 *  - py esptool/loader.py:1120-1176 mem_begin/mem_block/mem_finish；:1529-1537 _upload_segment；
 *  - js src/esploader.ts:1473-1524 runStub()：stub=[decodedText, decodedData]，
 *    每段 memBegin(length, blocks, ESP_RAM_BLOCK, offs) + memBlock(...)，memFinish(entry)，
 *    transport.read() != "OHAI" 即失败；
 *  - js src/esploader.ts:923-954 memBegin、:975-982 memBlock、:988-992 memFinish。
 *
 * 容器（app/src/main/assets/esp_stub/<key>.bin）是把官方 JSON 的 base64 text/data 解码后重新封装，
 * 36 字节小端头：magic "ESTB"、schema version(u32)、entry、text_start、text_len、data_start、
 * data_len、chip_id、stub 版本标签；随后是 text 原始字节、data 原始字节。固件本体未修改。
 */

/** stub 的一个 RAM 加载段（address = 官方 JSON 的 text_start / data_start）。 */
data class EspStubSegment(val address: Int, val data: ByteArray)

/** 解析后的 stub：入口地址 + 段列表 + 容器内记录的 chip_id（0xFFFFFFFF→-1 表示未知）。 */
data class EspStub(
    val chipId: Int,
    val entry: Int,
    val segments: List<EspStubSegment>,
) {
    val textLength: Int get() = segments.sumOf { it.data.size }
}

/** 单条待发送的下载命令（纯数据，供 EspFlasher 执行 / 单测断言）。 */
data class EspLoadOp(
    val op: Int,
    val data: ByteArray,
    val chk: Int = 0,
    /** false = 发完不等应答（MEM_END 后 ROM 常不回包，直接去读 OHAI）。 */
    val awaitReply: Boolean = true,
)

object EspStubAssets {
    const val ASSET_DIR = "esp_stub"
    const val HEADER_SIZE = 36
    const val SCHEMA_VERSION = 1
    /** esp-flasher-stub v1.4.1 的编码标签，用于 stub 版本匹配校验。 */
    const val STUB_VERSION_TAG = 0x00010401
    const val UNKNOWN_CHIP_ID = -1          // 0xFFFFFFFF（ESP8266 等无 GET_SECURITY_INFO chip_id）

    const val SOURCE_URL =
        "https://github.com/espressif/esptool/tree/master/esptool/targets/stub_flasher/2"
    const val UPSTREAM_RELEASE =
        "https://github.com/espressif/esp-flasher-stub/releases/tag/v1.4.1"

    private val MAGIC = byteArrayOf(0x45, 0x53, 0x54, 0x42)   // "ESTB"

    /** 解析容器；magic/schema/版本/长度任一不符即抛 IllegalArgumentException。 */
    fun parse(blob: ByteArray): EspStub {
        require(blob.size >= HEADER_SIZE) { "stub 容器过短：${blob.size} < $HEADER_SIZE" }
        for (i in MAGIC.indices) {
            require(blob[i] == MAGIC[i]) { "stub 容器 magic 不符（非 ESTB）" }
        }
        require(EspFlashCodec.le32(blob, 4) == SCHEMA_VERSION) { "stub 容器 schema version 不符" }
        val entry = EspFlashCodec.le32(blob, 8)
        val textStart = EspFlashCodec.le32(blob, 12)
        val textLen = EspFlashCodec.le32(blob, 16)
        val dataStart = EspFlashCodec.le32(blob, 20)
        val dataLen = EspFlashCodec.le32(blob, 24)
        val chipId = EspFlashCodec.le32(blob, 28)
        require(EspFlashCodec.le32(blob, 32) == STUB_VERSION_TAG) {
            "stub 版本标签不符（期望 v1.4.1 / 0x${STUB_VERSION_TAG.toString(16)}）"
        }
        require(textLen >= 0 && dataLen >= 0) { "stub 段长为负" }
        val expected = HEADER_SIZE.toLong() + textLen + dataLen
        require(blob.size.toLong() == expected) {
            "stub 容器长度不符：${blob.size} != $expected"
        }

        val segments = ArrayList<EspStubSegment>(2)
        if (textLen > 0) segments += EspStubSegment(textStart, blob.copyOfRange(HEADER_SIZE, HEADER_SIZE + textLen))
        if (dataLen > 0) {
            val off = HEADER_SIZE + textLen
            segments += EspStubSegment(dataStart, blob.copyOfRange(off, off + dataLen))
        }
        require(segments.isNotEmpty()) { "stub 容器无任何段" }
        return EspStub(chipId, entry, segments)
    }

    /**
     * chip_id + stub 版本匹配校验：容器记录的 IMAGE_CHIP_ID 必须等于该芯片官方值
     * （EspTargets.byName(chip.name).chipId），且 GET_SECURITY_INFO 读回的 chip_id 也要一致；
     * 任一不符返回 false，调用方据此降级 ROM。
     */
    fun matchesChip(stub: EspStub, chip: EspChip): Boolean {
        val params = EspTargets.byName(chip.name) ?: return false
        val detected = chip.chipId
        if (detected != null && params.chipId != null && detected != params.chipId) return false
        val expectedId = params.chipId ?: UNKNOWN_CHIP_ID
        return stub.chipId == expectedId
    }

    /** 解析 + 匹配一步到位；异常/不匹配一律返回 null（安全降级）。 */
    fun load(chip: EspChip, blob: ByteArray): EspStub? = runCatching {
        val stub = parse(blob)
        if (matchesChip(stub, chip)) stub else null
    }.getOrNull()
}

/** stub 下载状态机（纯函数）：把 EspStub 展开成有序的 MEM_BEGIN/MEM_DATA/MEM_END 命令序列。 */
object EspStubLoader {
    /** stub 启动后回的裸 SLIP 包头字节 0x4F='O'（"OHAI"，py loader.py:1520 / js esploader.ts:1516）。 */
    val OHAI: ByteArray = byteArrayOf(0x4F, 0x48, 0x41, 0x49)

    fun isOhai(payload: ByteArray): Boolean = payload.contentEquals(OHAI)

    /**
     * 对照 py loader.py:1529-1537 _upload_segment + :1158-1176 mem_finish：
     *  每段先 MEM_BEGIN<IIII(size, blocks, ESP_RAM_BLOCK, addr)>，
     *  再按 ESP_RAM_BLOCK 切块发 MEM_DATA<IIII(len, seq, 0, 0)> + 块数据 + XOR checksum，
     *  最后一条 MEM_END<II(int(entry==0), entry)> 标 awaitReply=false（ROM 常不回包）。
     */
    fun buildLoadSequence(stub: EspStub, ramBlock: Int = EspCmd.ESP_RAM_BLOCK): List<EspLoadOp> {
        val ops = ArrayList<EspLoadOp>()
        for (seg in stub.segments) {
            val blocks = (seg.data.size + ramBlock - 1) / ramBlock
            ops += EspLoadOp(
                EspCmd.MEM_BEGIN,
                EspFlashCodec.memBeginParams(seg.data.size, blocks, ramBlock, seg.address),
            )
            for (seq in 0 until blocks) {
                val from = seq * ramBlock
                val to = minOf(from + ramBlock, seg.data.size)
                val chunk = seg.data.copyOfRange(from, to)
                ops += EspLoadOp(
                    EspCmd.MEM_DATA,
                    EspFlashCodec.flashDataHeader(chunk.size, seq) + chunk,
                    chk = EspFlashCodec.checksum(chunk),
                )
            }
        }
        ops += EspLoadOp(
            EspCmd.MEM_END,
            EspFlashCodec.memFinishParams(stub.entry),
            awaitReply = false,
        )
        return ops
    }
}

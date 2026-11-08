package com.dc.andagent.tools

/**
 * R115: ISO-DEP (ISO/IEC 7816-4 command APDU) + FeliCa frame helpers.
 *
 * Pure Kotlin on purpose: hex normalization, APDU case validation, status-word
 * decoding, sensitive-application detection and FeliCa frame building live here
 * so they run under plain JVM unit tests (see NfcApduCodecTest) without a device.
 * The Android bindings (IsoDep.transceive / NfcF.transceive) stay in NfcApduTools.
 *
 * References: ISO/IEC 7816-4 (APDU structure, status words), ISO/IEC 7816-5
 * (AID registration), EMV Book 1, ICAO Doc 9303 (eMRTD LDS application) and
 * JIS X 6319-4 (FeliCa command frames). Only the wire formats are implemented;
 * no third-party library is linked.
 */

private val HEX_ONLY = Regex("^[0-9a-fA-F]+$")

/**
 * Normalize a hex blob typed by the model: strips 0x prefix, whitespace, ':', '-'
 * and '_' separators, then uppercases. Returns null when the result is empty,
 * odd-length or contains non-hex characters.
 */
fun normalizeHexBlob(text: String): String? {
    var s = text.trim()
    if (s.startsWith("0x", true)) s = s.substring(2)
    s = s.filterNot { it.isWhitespace() || it == ':' || it == '-' || it == '_' || it == '.' }
    if (s.isEmpty() || s.length % 2 != 0 || !HEX_ONLY.matches(s)) return null
    return s.uppercase()
}

/** Parsed frame list plus the raw tokens that failed to parse. */
data class FrameSet(val frames: List<ByteArray>, val invalid: List<String>)

/**
 * Split a user/model supplied hex blob into frames. Frames may be separated by
 * newlines, ',', ';' or '|'; '#' and '//' start comments. Hex digits never
 * collide with those separators, so a comma is unambiguous here.
 */
fun parseHexFrames(text: String): FrameSet {
    val frames = mutableListOf<ByteArray>()
    val invalid = mutableListOf<String>()
    text.lineSequence().forEach { line ->
        val stripped = line.substringBefore('#').substringBefore("//")
        for (token in stripped.split(',', ';', '|')) {
            val t = token.trim()
            if (t.isEmpty()) continue
            val norm = normalizeHexBlob(t)
            if (norm == null) invalid.add(t)
            else frames.add(ByteArray(norm.length / 2) { i -> norm.substring(i * 2, i * 2 + 2).toInt(16).toByte() })
        }
    }
    return FrameSet(frames, invalid)
}

/* ------------------------------ command APDU ------------------------------ */

/**
 * One parsed command APDU. [kind] follows ISO/IEC 7816-4 cases:
 * "1" (no data, no Le), "2" (no data + Le), "3" (data, no Le),
 * "4" (data + Le), "3E"/"4E" (extended length variants).
 */
data class ApduInfo(
    val bytes: ByteArray,
    val hex: String,
    val cla: Int,
    val ins: Int,
    val p1: Int,
    val p2: Int,
    val lc: Int,
    val le: Int?,
    val data: ByteArray,
    val kind: String,
    val error: String?,
) {
    val valid: Boolean get() = error == null
}

/**
 * Parse and validate a command APDU, including short/extended Lc/Le consistency
 * checks. Never throws: malformed input is reported through [ApduInfo.error].
 */
fun parseApdu(bytes: ByteArray): ApduInfo {
    fun u(i: Int) = bytes[i].toInt() and 0xff
    fun malformed(reason: String, kind: String = "?") =
        ApduInfo(bytes, bytesToHex(bytes), 0, 0, 0, 0, 0, null, ByteArray(0), kind, reason)

    if (bytes.size < 4) {
        return malformed("命令 APDU 至少 4 字节（CLA INS P1 P2），当前 ${bytes.size} 字节")
    }
    val cla = u(0)
    val ins = u(1)
    val p1 = u(2)
    val p2 = u(3)
    fun ok(lc: Int, le: Int?, data: ByteArray, kind: String) =
        ApduInfo(bytes, bytesToHex(bytes), cla, ins, p1, p2, lc, le, data, kind, null)

    if (bytes.size == 4) return ok(0, null, ByteArray(0), "1")
    if (bytes.size == 5) {
        val le = u(4)
        return ok(0, if (le == 0) 256 else le, ByteArray(0), "2")
    }

    val b4 = u(4)
    if (b4 != 0) {
        val lc = b4
        val end = 5 + lc
        return when (bytes.size) {
            end -> ok(lc, null, bytes.copyOfRange(5, end), "3")
            end + 1 -> {
                val le = u(end)
                ok(lc, if (le == 0) 256 else le, bytes.copyOfRange(5, end), "4")
            }
            else -> malformed("Lc=$lc 要求总长 $end 或 ${end + 1} 字节，实际 ${bytes.size} 字节", "4")
        }
    }

    // b4 == 0x00 → extended length encoding.
    if (bytes.size < 7) return malformed("扩展长度 APDU 至少 7 字节，当前 ${bytes.size} 字节", "3E")
    val lc = (u(5) shl 8) or u(6)
    if (lc == 0) return malformed("扩展长度 APDU 的 Lc 不能为 0", "3E")
    val end = 7 + lc
    return when (bytes.size) {
        end -> ok(lc, null, bytes.copyOfRange(7, end), "3E")
        end + 2 -> {
            val le = (u(end) shl 8) or u(end + 1)
            ok(lc, if (le == 0) 65536 else le, bytes.copyOfRange(7, end), "4E")
        }
        else -> malformed("扩展 Lc=$lc 要求总长 $end 或 ${end + 2} 字节，实际 ${bytes.size} 字节", "4E")
    }
}

/** SELECT by AID command (`00 A4 04 00 Lc <aid>`), used by the `aid` shortcut. */
fun selectAidApdu(aid: ByteArray): ByteArray {
    val out = ByteArray(5 + aid.size)
    out[0] = 0x00
    out[1] = 0xA4.toByte()
    out[2] = 0x04
    out[3] = 0x00
    out[4] = aid.size.toByte()
    aid.copyInto(out, 5)
    return out
}

/** Applied AID (uppercase hex) when [bytes] is a SELECT-by-AID/DDF-Name command. */
fun appliedAidHex(bytes: ByteArray): String? {
    val info = parseApdu(bytes)
    if (!info.valid || info.ins != 0xA4 || info.p1 != 0x04 || info.data.isEmpty()) return null
    return bytesToHex(info.data)
}

/**
 * ISO/IEC 7816-4 instructions that mutate the card or can lock credentials.
 * Returned string is the user-facing reason; null when the instruction is read-only.
 */
fun apduDangerReason(info: ApduInfo): String? {
    if (!info.valid) return null
    return when (info.ins) {
        0x20 -> "VERIFY（校验 PIN/口令，连续错误会锁卡）"
        0x24 -> "CHANGE REFERENCE DATA（修改 PIN/口令）"
        0x2C -> "RESET RETRY COUNTER（重置重试计数）"
        0xD0, 0xD6 -> "写入/更新二进制（修改卡内数据）"
        0x0C, 0xDC -> "写入/删除记录"
        0x0E -> "ERASE BINARY（擦除数据）"
        0xE2 -> "APPEND RECORD（追加记录）"
        0xDA -> "PUT DATA（写入对象数据）"
        0xDE -> "DELETE DATA（删除数据对象）"
        0xE0 -> "CREATE FILE（新建文件）"
        0xE4 -> "DELETE FILE（删除文件）"
        0xE6 -> "TERMINATE DF（终止目录）"
        0xE8 -> "TERMINATE EF（终止文件）"
        else -> null
    }
}

/** Status word (SW1/SW2) explanation for the common ISO 7816-4 codes. */
fun describeStatusWord(sw1: Int, sw2: Int): String = when {
    sw1 == 0x90 && sw2 == 0x00 -> "成功 (9000)"
    sw1 == 0x61 -> "成功，仍有 $sw2 字节待取（GET RESPONSE）"
    sw1 == 0x6C -> "Le 不正确，应以 Le=$sw2 重试"
    sw1 == 0x62 && sw2 == 0x82 -> "EOF：读取越过文件末尾"
    sw1 == 0x62 && sw2 == 0x83 -> "所选文件已失效"
    sw1 == 0x63 && sw2 == 0x00 -> "验证失败（如 PIN 错误）"
    sw1 == 0x63 && (sw2 and 0xF0) == 0xC0 -> "验证失败，还可重试 ${sw2 and 0x0F} 次"
    sw1 == 0x63 && sw2 == 0x81 -> "回读数据与写入不一致"
    sw1 == 0x65 && sw2 == 0x81 -> "写入失败（存储/内存错误）"
    sw1 == 0x67 && sw2 == 0x00 -> "长度错误（Lc/Le 不符）"
    sw1 == 0x68 && sw2 == 0x00 -> "CLA 不支持（安全消息/逻辑通道）"
    sw1 == 0x69 && sw2 == 0x82 -> "安全状态不满足（需先认证/校验）"
    sw1 == 0x69 && sw2 == 0x83 -> "认证方法已锁定"
    sw1 == 0x69 && sw2 == 0x85 -> "使用条件不满足"
    sw1 == 0x69 && sw2 == 0x86 -> "命令不允许（无当前文件或权限不足）"
    sw1 == 0x69 && sw2 == 0x88 -> "安全消息数据错误"
    sw1 == 0x6A && sw2 == 0x80 -> "数据域参数不正确"
    sw1 == 0x6A && sw2 == 0x81 -> "该功能不支持"
    sw1 == 0x6A && sw2 == 0x82 -> "文件或应用未找到（未 SELECT 或 AID 不在卡上）"
    sw1 == 0x6A && sw2 == 0x86 -> "P1/P2 参数不正确"
    sw1 == 0x6A && sw2 == 0x87 -> "Lc 与数据长度不一致"
    sw1 == 0x6A && sw2 == 0x88 -> "引用的数据未找到"
    sw1 == 0x6D && sw2 == 0x00 -> "指令 INS 不支持"
    sw1 == 0x6E && sw2 == 0x00 -> "类 CLA 不支持"
    sw1 == 0x6F && sw2 == 0x00 -> "无精确诊断信息"
    else -> "状态字 0x%04X（未收录，按 ISO 7816-4 解读）".format((sw1 shl 8) or sw2)
}

/* --------------------------- sensitive applications --------------------------- */

/**
 * An NFC application whose data is privacy-sensitive: travel documents
 * (identity + biometrics) and payment credentials. Selecting one of these needs
 * the strong privacy gate (explicit user consent + recorded purpose).
 */
data class SensitiveApp(val aidPrefix: String, val label: String, val kind: String)

/** Registered AIDs worth gating. Prefix matching is used: cards may append a RID extension. */
val SENSITIVE_APPS: List<SensitiveApp> = listOf(
    SensitiveApp("A0000002471001", "ICAO eMRTD 电子护照（身份与生物特征）", "travelDocument"),
    SensitiveApp("A0000002472001", "eMRTD/eID 扩展应用（护照/电子身份）", "travelDocument"),
    SensitiveApp("A000000632010105", "中国电子身份证 eID", "identity"),
    SensitiveApp("A0000000031010", "EMV Visa 支付应用", "payment"),
    SensitiveApp("A0000000032010", "EMV Visa Electron 支付应用", "payment"),
    SensitiveApp("A0000000033010", "EMV Visa Interlink 支付应用", "payment"),
    SensitiveApp("A0000000041010", "EMV MasterCard 支付应用", "payment"),
    SensitiveApp("A0000000043060", "EMV Maestro 支付应用", "payment"),
    SensitiveApp("A0000000651010", "EMV JCB 支付应用", "payment"),
    SensitiveApp("A000000333010101", "PBOC/银联借贷记支付应用", "payment"),
    SensitiveApp("A000000333010102", "PBOC/银联电子现金应用", "payment"),
    SensitiveApp("A000000333010103", "PBOC/银联准贷记支付应用", "payment"),
)

/** AID used for the "same card was already opened as sensitive" memo entry. */
val STICKY_SENSITIVE_APP = SensitiveApp("MEMO", "同一张卡此前已 SELECT 过证件/支付类应用", "identity")

/** Match a selected AID against [SENSITIVE_APPS]; both directions of prefix match. */
fun matchSensitiveApp(aidHex: String): SensitiveApp? {
    val aid = aidHex.uppercase()
    if (aid.length < 10) return null // shorter than 5 bytes: too generic to classify
    return SENSITIVE_APPS.firstOrNull { aid.startsWith(it.aidPrefix) || (it.aidPrefix.startsWith(aid) && aid.length >= 10) }
}

/** EMV/PBOC GET PROCESSING OPTIONS (CLA 80 INS A8) is only used by payment apps. */
private const val EMV_GPO_LABEL = "EMV/PBOC 支付应用（GET PROCESSING OPTIONS）"

/** All sensitive applications touched by a batch of command APDUs (deduplicated). */
fun sensitiveAppsIn(frames: List<ByteArray>): List<SensitiveApp> {
    val found = LinkedHashMap<String, SensitiveApp>()
    for (frame in frames) {
        val info = parseApdu(frame)
        if (!info.valid) continue
        appliedAidHex(frame)?.let { matchSensitiveApp(it)?.let { app -> found[app.aidPrefix] = app } }
        if (info.cla == 0x80 && info.ins == 0xA8) {
            found["EMV-GPO"] = SensitiveApp("EMV-GPO", EMV_GPO_LABEL, "payment")
        }
    }
    return found.values.toList()
}

/* --------------------------- historical bytes (ATS) --------------------------- */

/**
 * Parse ISO/IEC 7816-4 compact TLV objects from ISO-DEP historical bytes.
 *
 * Android hands back the card's historical bytes for most ISO-DEP tags, but on
 * some devices it returns the raw ATS instead, so a leading interface-byte block
 * is skipped when the first byte is an ATS length byte. Returns null when the
 * bytes do not form a clean compact-TLV chain.
 */
fun parseCompactTlv(historical: ByteArray): List<Pair<Int, ByteArray>>? {
    if (historical.isEmpty()) return null
    val candidates = mutableListOf<Int>()
    for (i in historical.indices) {
        // 0x80 is the ISO 7816-4 category indicator; compact TLVs start right after it.
        if (historical[i].toInt() and 0xff == 0x80) candidates.add(i + 1)
    }
    for (start in candidates.distinct()) {
        if (start >= historical.size) continue
        parseCompactTlvChain(historical, start)?.let { return it }
    }
    return null
}

private fun parseCompactTlvChain(bytes: ByteArray, start: Int): List<Pair<Int, ByteArray>>? {
    val out = mutableListOf<Pair<Int, ByteArray>>()
    var i = start
    while (i < bytes.size) {
        val tag = bytes[i].toInt() and 0xff
        if (tag == 0x00) { // padding
            if ((i until bytes.size).all { (bytes[it].toInt() and 0xff) == 0x00 }) return out
            return null
        }
        if (i + 1 >= bytes.size) return null
        val len = bytes[i + 1].toInt() and 0xff
        if (i + 2 + len > bytes.size) return null
        out.add(tag to bytes.copyOfRange(i + 2, i + 2 + len))
        i += 2 + len
    }
    return out.ifEmpty { null }
}

/** Human label for a compact TLV tag found in ISO-DEP historical bytes. */
fun compactTlvName(tag: Int): String = when (tag) {
    0x01 -> "国家/地区标识"
    0x02 -> "发卡机构标识"
    0x03 -> "卡片服务数据"
    0x04 -> "初始访问数据（AID 列表）"
    0x05 -> "发卡方专有数据"
    0x06 -> "持卡人姓名"
    0x07 -> "卡片能力"
    0x08 -> "卡片状态信息"
    else -> "TLV 标签 0x%02X".format(tag)
}

/* --------------------------------- FeliCa --------------------------------- */

/** FeliCa service code as it appears on the wire: 2 bytes, little-endian. */
fun felicaServiceBytes(serviceCode: Int): ByteArray = byteArrayOf(
    (serviceCode and 0xFF).toByte(),
    ((serviceCode shr 8) and 0xFF).toByte(),
)

/**
 * FeliCa 2-byte block list element: block number in the low 8 bits, service
 * index in the high nibble of the second byte (little-endian on the wire).
 */
fun felicaBlockListElement(serviceIndex: Int, blockNumber: Int): ByteArray = byteArrayOf(
    (blockNumber and 0xFF).toByte(),
    (((serviceIndex and 0x0F) shl 4) or ((blockNumber shr 8) and 0x0F)).toByte(),
)

/**
 * Build a "Read Without Encryption" (0x06) frame.
 * Layout: LEN | 06 | IDm(8) | serviceCount(1) | serviceCode(2*n) | blockCount(1) | blockList(2*m)
 */
fun buildFelicaReadWithoutEncryption(
    idm: ByteArray,
    serviceCodes: List<Int>,
    blocks: List<Pair<Int, Int>>,
): ByteArray {
    val blockList = blocks.flatMap { (serviceIndex, blockNumber) ->
        felicaBlockListElement(serviceIndex, blockNumber).toList()
    }
    return buildFelicaReadWithoutEncryption(idm, serviceCodes, blockList.toByteArray(), blocks.size)
}

/**
 * Same frame, but with caller-supplied block-list bytes (debug escape hatch for
 * cards that negotiate a different element width than the default 2 bytes).
 */
fun buildFelicaReadWithoutEncryption(
    idm: ByteArray,
    serviceCodes: List<Int>,
    blockList: ByteArray,
    blockCount: Int,
): ByteArray {
    require(idm.size == 8) { "FeliCa IDm 必须是 8 字节" }
    val body = mutableListOf<Byte>()
    body.add(0x06)
    idm.forEach { body.add(it) }
    body.add(serviceCodes.size.toByte())
    serviceCodes.forEach { code -> felicaServiceBytes(code).forEach { body.add(it) } }
    body.add(blockCount.toByte())
    blockList.forEach { body.add(it) }
    return byteArrayOf(body.size.toByte()) + body.toByteArray()
}

/**
 * FeliCa status flags (Read/Write Without Encryption responses). 0x00/0x00 is
 * success; detailed failure codes vary per card and are reported raw.
 */
data class FelicaStatus(val sf1: Int, val sf2: Int) {
    val ok: Boolean get() = sf1 == 0 && sf2 == 0
    val text: String
        get() = if (ok) "正常" else "错误（SF1=0x%02X, SF2=0x%02X；可对照 FeliCa 规格解读）".format(sf1, sf2)
}

/** Status flags of a response frame when its shape is a status-bearing reply. */
fun felicaStatusOf(frame: ByteArray): FelicaStatus? {
    if (frame.size < 12) return null
    val cmd = frame[1].toInt() and 0xFF
    if (cmd != 0x07 && cmd != 0x09) return null
    return FelicaStatus(frame[10].toInt() and 0xFF, frame[11].toInt() and 0xFF)
}

/** Parsed Read Without Encryption reply. */
data class FelicaReadReply(val status: FelicaStatus?, val blocks: List<ByteArray>, val error: String?)

/**
 * Parse a 0x07 reply: LEN | 07 | IDm(8) | SF1 | SF2 | blockCount | blockData(16*n).
 */
fun parseFelicaReadReply(frame: ByteArray, expectedBlocks: Int): FelicaReadReply {
    if (frame.size < 13) return FelicaReadReply(null, emptyList(), "响应 ${frame.size} 字节，缺少状态字/块数据")
    if ((frame[1].toInt() and 0xFF) != 0x07) {
        return FelicaReadReply(null, emptyList(), "响应码 0x%02X 不是 0x07（读无加密）".format(frame[1].toInt() and 0xFF))
    }
    val status = FelicaStatus(frame[10].toInt() and 0xFF, frame[11].toInt() and 0xFF)
    if (!status.ok) return FelicaReadReply(status, emptyList(), null)
    val declared = frame[12].toInt() and 0xFF
    val available = (frame.size - 13) / 16
    val count = minOf(declared, available)
    val blocks = (0 until count).map { frame.copyOfRange(13 + it * 16, 13 + it * 16 + 16) }
    val error = when {
        count < declared -> "声明 $declared 块，但响应只带 $count 块"
        count < expectedBlocks -> "只返回 $count 块（请求 $expectedBlocks 块），可能超出该服务的块范围"
        else -> null
    }
    return FelicaReadReply(status, blocks, error)
}

/** FeliCa command code (second byte of a frame), or null when too short. */
fun felicaCommandOf(frame: ByteArray): Int? =
    if (frame.size >= 2) frame[1].toInt() and 0xFF else null

/** FeliCa commands that modify card data or change authentication state. */
fun felicaDangerReason(frame: ByteArray): String? = when (felicaCommandOf(frame)) {
    0x08 -> "Write Without Encryption（写入数据）"
    0x16 -> "Write（带认证写入）"
    0x12 -> "Authentication2（卡片認証，可能改变认证状态）"
    0x1C -> "Set Personal Info / 写入发行方数据"
    else -> null
}

/** Human label for the well-known FeliCa command codes. */
fun felicaCommandName(cmd: Int): String = when (cmd) {
    0x00 -> "Polling"
    0x01 -> "Polling 响应"
    0x02 -> "Request Service"
    0x03 -> "Request Service 响应"
    0x04 -> "Request Response"
    0x05 -> "Request Response 响应"
    0x06 -> "Read Without Encryption"
    0x07 -> "Read Without Encryption 响应"
    0x08 -> "Write Without Encryption"
    0x09 -> "Write Without Encryption 响应"
    0x0C -> "Request System Code"
    0x0D -> "Request System Code 响应"
    0x10 -> "Authentication1"
    0x11 -> "Authentication1 响应"
    0x12 -> "Authentication2"
    0x13 -> "Authentication2 响应"
    0x14 -> "Read"
    0x15 -> "Read 响应"
    0x16 -> "Write"
    0x17 -> "Write 响应"
    else -> "命令 0x%02X".format(cmd)
}

/**
 * Fix the leading length byte when it is missing or wrong. Models frequently
 * drop it; a FeliCa frame is always `LEN (body length) | body`, so prepending the
 * body length is unambiguous when the first byte is a command code.
 */
fun normalizeFelicaFrame(frame: ByteArray): Pair<ByteArray, String?> {
    if (frame.isEmpty()) return frame to "空帧"
    val declared = frame[0].toInt() and 0xFF
    if (declared == frame.size - 1) return frame to null
    val firstLooksLikeCommand = declared <= 0x20
    return if (firstLooksLikeCommand) {
        (byteArrayOf(frame.size.toByte()) + frame) to "已自动补上长度字节（0x%02X）".format(frame.size)
    } else {
        frame to "长度字节 0x%02X 与实际长度 %d 不符".format(declared, frame.size)
    }
}

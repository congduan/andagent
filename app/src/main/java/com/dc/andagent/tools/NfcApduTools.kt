package com.dc.andagent.tools

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream

/**
 * R115: ISO-DEP APDU passthrough + FeliCa frames.
 *
 * Android's NFC stack already exposes the exact primitives needed
 * ([IsoDep.transceive] for ISO/IEC 7816-4 command APDUs, [NfcF.transceive] for
 * JIS X 6319-4 frames), so no native library is added. This covers the classes
 * the NDEF/MIFARE/UL/HCE tools cannot reach: transit-card balances, access-control
 * cards and DESFire-style applications (ISO-DEP), electronic passports (eMRTD,
 * ISO-DEP) and Japanese FeliCa cards (Suica/PASO/ICOCA, IDm/PMm + block reads).
 *
 * Privacy: travel documents and payment applications sit behind a strong gate —
 * `confirm_privacy=true` plus a user-supplied `purpose` is mandatory, and the
 * opening is remembered per tag UID so later calls on the same card stay gated.
 * Commands that mutate the card or can lock credentials require `confirm=true`.
 */

private const val TAG_TIMEOUT = "ERROR: 等待 NFC 贴卡超时或已有读写任务（60 秒）"
private const val MAX_APDUS = 64
private const val MAX_RESPONSE_BYTES = 64 * 1024
private const val MAX_APDU_BYTES = 4096
private const val MAX_FELICA_FRAMES = 16
private const val MAX_FELICA_BLOCKS = 32
private const val MAX_FELICA_SERVICES = 16

private const val APDU_COMPLIANCE =
    "仅用于自有卡片或持卡人明确授权：证件与支付类数据属个人敏感信息，读取后请勿外传、勿留存。"

class NfcApduTool(private val session: NfcSession, override val name: String) : Tool {
    override val description: String = when (name) {
        "nfc_iso_dep_info" ->
            "等待贴卡（最多 60 秒）并连接 ISO-DEP（ISO 14443-4），返回 ATR/ATS 历史字节、HiLayer 响应、" +
                "最大传输长度、是否支持扩展长度 APDU，并尝试解析历史字节里的 compact TLV（发卡方/持卡人/AID）。" +
                "用于确认卡片是否走 APDU 通道（DESFire、公交卡、门禁、电子护照等）。只读。"
        "nfc_iso_dep_transceive" ->
            "等待贴卡并透传 ISO-DEP APDU（IsoDep.transceive）：apdu 可写多条命令（hex，换行/分号/逗号分隔，" +
                "如 00A4040007A0000002471001）；aid 可先自动 SELECT（00A40400 Lc AID）；响应 SW=61xx 自动追加 " +
                "GET RESPONSE。返回每条命令的状态字、含义、数据与耗时。可读公交卡余额、门禁卡、DESFire、电子护照。" +
                "贴卡前先告诉用户。隐私强闸：命中证件（eMRTD/eID）或支付类 AID 时必须带 confirm_privacy=true 且 " +
                "purpose 写明用户授权的用途，并已当面取得同意，否则返回 CONFIRM_REQUIRED；含写入/擦除/校验 PIN " +
                "类指令需 confirm=true。$APDU_COMPLIANCE"
        "nfc_felica_info" ->
            "等待贴 FeliCa 卡（NfcF：Suica/PASMO/ICOCA 等日系卡与门禁），返回 IDm、PMm、系统码、厂商码、" +
                "卡型线索与最大传输长度。只读。"
        "nfc_felica_transceive" ->
            "等待贴 FeliCa 卡并透传原始帧（NfcF.transceive，hex；帧首长度字节漏写时自动补）。" +
                "返回原始响应 hex、长度、命令名与状态标志。写入/认证类命令（0x08/0x12/0x16/0x1C）需 confirm=true。"
        "nfc_felica_read_blocks" ->
            "等待贴 FeliCa 卡，用 Read Without Encryption(0x06) 读块：service_codes 为 2 字节服务码 hex（如 008B），" +
                "blocks 为块号或 serviceIndex:blockNo（如 0 或 0:0,0:1）。返回每块 16 字节 hex/ASCII、状态标志与帧内容。" +
                "日系交通卡余额常见于系统码 0x0003 的服务码 0x008B 块 0（字段位置随卡种不同，需按返回数据核对）。只读。"
        else -> "NFC ISO-DEP / FeliCa 工具"
    }

    override val parameters = when (name) {
        "nfc_iso_dep_transceive" -> schemaOf(
            "apdu" to strParam("命令 APDU，hex（可带 0x/空格/冒号）；多条用换行、分号或逗号分隔。与 aid 至少给一个"),
            "aid" to strParam("可选：先自动 SELECT 的 AID（hex，5-16 字节），等价 00A40400 Lc AID"),
            "timeout_ms" to intParam("单条 APDU 超时毫秒，默认 5000，范围 500-60000"),
            "auto_get_response" to boolParam("SW=61xx 时自动追加 GET RESPONSE，默认 true"),
            "confirm" to boolParam("含写入/擦除/校验 PIN 等指令时必须 true"),
            "confirm_privacy" to boolParam("命中证件/支付类 AID 时必须 true（须已取得用户当面同意）"),
            "purpose" to strParam("命中隐私敏感 AID 时必填：用户授权的用途（如实转述用户的话，不要自行编造）"),
        )
        "nfc_felica_transceive" -> schemaOf(
            "frame" to strParam("FeliCa 帧 hex（含首字节长度；漏写会自动补）。多帧用换行/分号/逗号分隔"),
            "timeout_ms" to intParam("超时毫秒，默认 5000，范围 500-60000"),
            "confirm" to boolParam("含写入/认证类命令（0x08/0x12/0x16/0x1C）时必须 true"),
            required = listOf("frame"),
        )
        "nfc_felica_read_blocks" -> schemaOf(
            "service_codes" to strParam("服务码，2 字节 hex，多个用逗号分隔，如 008B 或 008B,0009"),
            "blocks" to strParam("块列表：blockNo 或 serviceIndex:blockNo，多个用逗号分隔，如 0 或 0:0,0:1"),
            "timeout_ms" to intParam("超时毫秒，默认 5000，范围 500-60000"),
            "block_list_hex" to strParam("可选：直接给出 block list 元素 hex（覆盖默认 2 字节编码），调试用"),
            required = listOf("service_codes", "blocks"),
        )
        else -> schemaOf()
    }

    /** APDU / raw-frame passthrough can always change card state, so it is flagged. */
    override val destructive: Boolean = name in setOf("nfc_iso_dep_transceive", "nfc_felica_transceive")

    override suspend fun execute(args: JsonObject): String {
        val state = session.status()
        if (state.startsWith("ERROR:")) return state
        return runCatching {
            withContext(Dispatchers.IO) {
                when (name) {
                    "nfc_iso_dep_info" -> isoDepInfo(session.awaitTag() ?: return@withContext TAG_TIMEOUT)
                    "nfc_iso_dep_transceive" -> isoDepTransceive(session.awaitTag() ?: return@withContext TAG_TIMEOUT, args)
                    "nfc_felica_info" -> felicaInfo(session.awaitTag() ?: return@withContext TAG_TIMEOUT)
                    "nfc_felica_transceive" -> felicaTransceive(session.awaitTag() ?: return@withContext TAG_TIMEOUT, args)
                    "nfc_felica_read_blocks" -> felicaReadBlocks(session.awaitTag() ?: return@withContext TAG_TIMEOUT, args)
                    else -> "ERROR: unknown tool '$name'"
                }
            }
        }.getOrElse { "ERROR: NFC 操作失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* ------------------------------- ISO-DEP ------------------------------- */

    private fun isoDepInfo(tag: Tag): String {
        val iso = IsoDep.get(tag) ?: return noIsoDep(tag)
        var historical = ByteArray(0)
        var hiLayer = ByteArray(0)
        var maxLen = 0
        var extended = false
        try {
            iso.timeout = 5000
            iso.connect()
            historical = iso.historicalBytes ?: ByteArray(0)
            hiLayer = iso.hiLayerResponse ?: ByteArray(0)
            maxLen = iso.maxTransceiveLength
            extended = iso.isExtendedLengthApduSupported
        } finally {
            runCatching { iso.close() }
        }
        val tlv = parseCompactTlv(historical)
        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            put("rfTech", rfTechLabel(tag.techList))
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            put("isoDep", true)
            put("historicalBytesHex", bytesToHex(historical))
            put("historicalBytesAscii", asciiOf(historical))
            put("hiLayerResponseHex", bytesToHex(hiLayer))
            put("maxTransceiveLength", maxLen)
            put("extendedLengthApduSupported", extended)
            if (tlv != null) {
                putJsonArray("historicalTlv") {
                    tlv.forEach { (tlvTag, value) ->
                        add(buildJsonObject {
                            put("tag", "0x%02X".format(tlvTag))
                            put("name", compactTlvName(tlvTag))
                            put("valueHex", bytesToHex(value))
                            if (tlvTag == 0x02) put("valueText", asciiOf(value).trim())
                            if (tlvTag == 0x06) {
                                // Cardholder name TLV: 0x01 (latin1) | 2n bytes (2 chars each).
                                val name = value.drop(1)
                                put("valueText", String(name.toByteArray(), Charsets.ISO_8859_1)
                                    .map { if (it.code in 0x20..0x7E) it else ' ' }.joinToString("").trim())
                            }
                        })
                    }
                }
            }
            put("note", if (tlv != null) "historicalTlv 为按 ISO 7816-4 compact TLV 的推测解析，字段语义随卡种变化。"
            else "历史字节不是可解析的 compact TLV 链，原样返回 hex。")
            put("next", "用 nfc_iso_dep_transceive 发命令（SELECT AID / READ BINARY / DESFire 原生指令等）。")
        }.toString()
    }

    private fun isoDepTransceive(tag: Tag, args: JsonObject): String {
        val apduText = args.strArg("apdu")
        val aidText = args.strArg("aid")
        if (apduText == null && aidText == null) return "ERROR: 需要 apdu（hex 命令）或 aid（自动 SELECT）"

        val parsed = apduText?.let { parseHexFrames(it) }
        if (parsed != null && parsed.invalid.isNotEmpty()) {
            return "ERROR: 无法解析的 APDU hex：${parsed.invalid.take(3).joinToString(" / ")}"
        }
        val frames = mutableListOf<ByteArray>()
        if (aidText != null) {
            val aid = normalizeHexBlob(aidText) ?: return "ERROR: aid 不是合法 hex"
            if (aid.length !in 10..32) return "ERROR: aid 应为 5-16 字节（10-32 位 hex），当前 ${aid.length / 2} 字节"
            frames.add(selectAidApdu(ByteArray(aid.length / 2) { i -> aid.substring(i * 2, i * 2 + 2).toInt(16).toByte() }))
        }
        parsed?.frames?.let { frames.addAll(it) }
        if (frames.isEmpty()) return "ERROR: apdu 为空"
        if (frames.size > MAX_APDUS) return "ERROR: 单次最多 $MAX_APDUS 条命令（当前 ${frames.size}）"

        val infos = frames.map { parseApdu(it) }
        infos.firstOrNull { !it.valid }?.let { return "ERROR: APDU ${it.hex} 格式不合法：${it.error}" }
        infos.firstOrNull { it.bytes.size > MAX_APDU_BYTES }?.let {
            return "ERROR: APDU 过长（${it.bytes.size} 字节，上限 $MAX_APDU_BYTES）"
        }

        // Strong privacy gate: travel documents / payment applications.
        val detected = sensitiveAppsIn(frames).toMutableList()
        if (isSensitiveTag(tag.id)) detected.add(STICKY_SENSITIVE_APP)
        if (detected.isNotEmpty()) {
            val purpose = args.strArg("purpose")?.take(200)
            val consent = args.boolArg("confirm_privacy") == true
            if (!consent || purpose.isNullOrBlank()) {
                return "CONFIRM_REQUIRED: 本次 APDU 命中隐私敏感应用（${detected.joinToString("、") { it.label }}）。" +
                    "证件/支付类应用含身份、生物特征或金融数据，必须由用户当场明确同意：请先说明要读什么、为什么读，" +
                    "取得同意后再带 confirm_privacy=true 与 purpose=<用户授权的用途> 重试；purpose 必须如实转述用户，" +
                    "不得自行编造。$APDU_COMPLIANCE"
            }
            markSensitiveTag(tag.id)
        }

        // Mutation / credential gate.
        val dangers = infos.mapNotNull { apduDangerReason(it) }
        if (dangers.isNotEmpty() && args.boolArg("confirm") != true) {
            return "CONFIRM_REQUIRED: 本次包含会改变卡片状态或可能锁卡的指令（${dangers.distinct().joinToString("；")}）。" +
                "请向用户说明影响（写入不可逆、PIN 连续错误会锁卡），取得同意后带 confirm=true 重试。"
        }

        val iso = IsoDep.get(tag) ?: return noIsoDep(tag)
        val timeout = (args.intArg("timeout_ms") ?: 5000).coerceIn(500, 60_000)
        val autoGetResponse = args.boolArg("auto_get_response") != false
        val results = mutableListOf<JsonObject>()
        var totalBytes = 0
        var stopped: String? = null
        try {
            iso.timeout = timeout
            iso.connect()
            for (info in infos) {
                if (totalBytes >= MAX_RESPONSE_BYTES) {
                    stopped = "累计响应超过 $MAX_RESPONSE_BYTES 字节，已停止后续命令"
                    break
                }
                val one = try {
                    runApdu(iso, info, autoGetResponse)
                } catch (e: Exception) {
                    ApduResult(
                        apduHex = info.hex,
                        responseHex = "",
                        dataHex = "",
                        sw1 = -1,
                        sw2 = -1,
                        swText = "未收到响应",
                        bytes = 0,
                        elapsedMs = 0,
                        chained = 0,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                }
                totalBytes += one.bytes
                results.add(one.toJson())
                if (one.error != null) {
                    stopped = "第 ${results.size} 条命令失败：${one.error}（贴卡是否移动/离开读卡区？）"
                    break
                }
            }
        } finally {
            runCatching { iso.close() }
        }

        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            put("rfTech", rfTechLabel(tag.techList))
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            put("apduCount", infos.size)
            put("timeoutMs", timeout)
            put("autoGetResponse", autoGetResponse)
            if (detected.isNotEmpty()) {
                putJsonObject("privacyGate") {
                    put("sensitive", true)
                    putJsonArray("apps") {
                        detected.forEach { app ->
                            add(buildJsonObject {
                                put("aidPrefix", app.aidPrefix)
                                put("label", app.label)
                                put("kind", app.kind)
                            })
                        }
                    }
                    put("purpose", args.strArg("purpose")?.take(200) ?: "")
                    put("note", "用户已同意本次读取；结果含个人敏感数据，勿外传、勿留存。")
                }
            }
            putJsonArray("results") { results.forEach { add(it) } }
            stopped?.let { put("stopped", it) }
            put("note", "SW=61xx 时已自动追加 00C00000<Le> GET RESPONSE 并拼接数据；响应 hex 含末尾状态字。")
        }.toString()
    }

    private fun runApdu(iso: IsoDep, info: ApduInfo, autoGetResponse: Boolean): ApduResult {
        val started = System.nanoTime()
        var chained = 0
        var last = iso.transceive(info.bytes) ?: ByteArray(0)
        val collected = ByteArrayOutputStream()
        if (autoGetResponse) {
            var guard = 0
            while (last.size >= 2 && (last[last.size - 2].toInt() and 0xFF) == 0x61 && guard < 8) {
                collected.write(last, 0, last.size - 2) // drop the 61xx trailer, keep payload
                val le: Byte = last[last.size - 1]
                last = iso.transceive(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, le)) ?: ByteArray(0)
                chained++
                guard++
            }
        }
        collected.write(last, 0, last.size)
        val response = collected.toByteArray()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (response.size < 2) {
            return ApduResult(info.hex, bytesToHex(response), "", -1, -1, "无状态字", response.size, elapsed, chained,
                "响应不足 2 字节，没有状态字")
        }
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        val data = response.copyOfRange(0, response.size - 2)
        return ApduResult(info.hex, bytesToHex(response), bytesToHex(data), sw1, sw2,
            describeStatusWord(sw1, sw2), response.size, elapsed, chained, null)
    }

    private data class ApduResult(
        val apduHex: String,
        val responseHex: String,
        val dataHex: String,
        val sw1: Int,
        val sw2: Int,
        val swText: String,
        val bytes: Int,
        val elapsedMs: Long,
        val chained: Int,
        val error: String?,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("apdu", apduHex)
            put("ok", error == null && sw1 == 0x90 && sw2 == 0x00)
            put("sw", if (sw1 >= 0) "0x%04X".format((sw1 shl 8) or sw2) else "")
            put("swMeaning", swText)
            put("responseHex", responseHex)
            put("dataHex", dataHex)
            put("dataBytes", (bytes - 2).coerceAtLeast(0))
            put("elapsedMs", elapsedMs)
            if (chained > 0) put("getResponseChained", chained)
            error?.let { put("error", it) }
        }
    }

    private fun noIsoDep(tag: Tag): String =
        "ERROR: 此卡不支持 ISO-DEP（IsoDep），techList=${tag.techList.joinToString()}；请用 nfc_read 看 NDEF，" +
            "或用 MIFARE/FeliCa 专用工具"

    /* -------------------------------- FeliCa -------------------------------- */

    private fun felicaInfo(tag: Tag): String {
        val f = NfcF.get(tag) ?: return noFeliCa(tag)
        var manufacturer = ByteArray(0)
        var systemCode = ByteArray(0)
        var maxLen = 0
        try {
            f.timeout = 5000
            f.connect()
            // NfcF.getIDm()/getPMm() are @hide; the public API exposes Tag.id (= IDm)
            // and getManufacturer() (the PMm bytes from the last polling response).
            manufacturer = f.getManufacturer() ?: ByteArray(0)
            systemCode = f.getSystemCode() ?: ByteArray(0)
            maxLen = f.maxTransceiveLength
        } finally {
            runCatching { f.close() }
        }
        val idm = tag.id
        val systemInt = littleEndian16(systemCode)
        val manufacturerCode = littleEndian16(manufacturer.take(2).toByteArray())
        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            put("idm", bytesToHex(idm))
            put("manufacturerHex", bytesToHex(manufacturer))
            put("systemCodeHex", bytesToHex(systemCode))
            put("systemCode", if (systemCode.size >= 2) "0x%04X".format(systemInt) else "")
            put("manufacturerCode", if (manufacturer.size >= 2) "0x%04X".format(manufacturerCode) else "")
            put("manufacturerName", if (manufacturerCode == 0x0001) "Sony" else "未收录")
            put("maxTransceiveLength", maxLen)
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            felicaSystemHint(systemInt)?.let { put("cardTypeHint", it) }
            put("note", "IDm 取自 Tag.id（Android 不公开 NfcF.getIDm）；manufacturerHex 为 public API 暴露的 PMm 字节。" +
                "日系卡常见布局：交通系 IC 卡系统码 0x0003；余额/履历用 nfc_felica_read_blocks 读服务码 0x008B 等，" +
                "字段位置随卡种不同，需按返回块数据核对。")
            put("next", "用 nfc_felica_read_blocks 读块，或用 nfc_felica_transceive 发原始帧（Request Service 0x02 可探查可用服务）。")
        }.toString()
    }

    private fun felicaTransceive(tag: Tag, args: JsonObject): String {
        val text = args.strArg("frame") ?: return "ERROR: 缺少 frame（FeliCa 帧 hex）"
        val parsed = parseHexFrames(text)
        if (parsed.invalid.isNotEmpty()) return "ERROR: 无法解析的帧 hex：${parsed.invalid.take(3).joinToString(" / ")}"
        if (parsed.frames.isEmpty()) return "ERROR: frame 为空"
        if (parsed.frames.size > MAX_FELICA_FRAMES) return "ERROR: 单次最多 $MAX_FELICA_FRAMES 帧"
        if (parsed.frames.any { it.size > 254 }) return "ERROR: 单帧不能超过 254 字节（FeliCa LEN 为 1 字节）"

        val prepared = parsed.frames.map { normalizeFelicaFrame(it) }
        val dangers = prepared.mapNotNull { (frame, _) -> felicaDangerReason(frame) }
        if (dangers.isNotEmpty() && args.boolArg("confirm") != true) {
            return "CONFIRM_REQUIRED: 帧中包含写入/认证类命令（${dangers.distinct().joinToString("；")}），会修改卡片数据。" +
                "请说明影响并取得用户同意后带 confirm=true 重试。"
        }

        val f = NfcF.get(tag) ?: return noFeliCa(tag)
        val timeout = (args.intArg("timeout_ms") ?: 5000).coerceIn(500, 60_000)
        val results = mutableListOf<JsonObject>()
        var stopped: String? = null
        try {
            f.timeout = timeout
            f.connect()
            for ((frame, fixNote) in prepared) {
                val started = System.nanoTime()
                val response = try {
                    f.transceive(frame)
                } catch (e: Exception) {
                    results.add(buildJsonObject {
                        put("frameHex", bytesToHex(frame))
                        put("ok", false)
                        put("error", e.message ?: e.javaClass.simpleName)
                        fixNote?.let { put("frameNote", it) }
                    })
                    stopped = "第 ${results.size} 帧失败：${e.message ?: e.javaClass.simpleName}"
                    break
                } ?: ByteArray(0)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                val status = felicaStatusOf(response)
                results.add(buildJsonObject {
                    put("frameHex", bytesToHex(frame))
                    put("frameCommand", felicaCommandName(felicaCommandOf(frame) ?: -1))
                    put("responseHex", bytesToHex(response))
                    put("responseBytes", response.size)
                    put("elapsedMs", elapsed)
                    if (response.size >= 2) {
                        put("responseCommand", felicaCommandName(response[1].toInt() and 0xFF))
                        put("responseLengthOk", (response[0].toInt() and 0xFF) == response.size - 1)
                    }
                    if (status != null) {
                        put("statusFlag1", "0x%02X".format(status.sf1))
                        put("statusFlag2", "0x%02X".format(status.sf2))
                        put("statusMeaning", status.text)
                        put("ok", status.ok)
                    } else {
                        put("ok", response.isNotEmpty())
                    }
                    fixNote?.let { put("frameNote", it) }
                })
            }
        } finally {
            runCatching { f.close() }
        }

        return buildJsonObject {
            put("uid", bytesToHex(tag.id))
            putJsonArray("techList") { tag.techList.forEach { add(it) } }
            put("frameCount", prepared.size)
            put("timeoutMs", timeout)
            putJsonArray("results") { results.forEach { add(it) } }
            stopped?.let { put("stopped", it) }
            put("note", "FeliCa 状态标志 SF1/SF2 均为 0x00 表示正常；其余组合随卡种定义不同，按原始 hex 解读。")
        }.toString()
    }

    private fun felicaReadBlocks(tag: Tag, args: JsonObject): String {
        val serviceText = args.strArg("service_codes") ?: return "ERROR: 缺少 service_codes（2 字节 hex，如 008B）"
        val blockText = args.strArg("blocks") ?: return "ERROR: 缺少 blocks（如 0 或 0:0,0:1）"
        val services = parseFelicaServiceCodes(serviceText)
            ?: return "ERROR: service_codes 必须是最多 $MAX_FELICA_SERVICES 个 2 字节 hex 服务码（如 008B）"
        val blocks = parseFelicaBlocks(blockText)
            ?: return "ERROR: blocks 必须是 'blockNo' 或 'serviceIndex:blockNo'（块号 0-255，服务索引 0-15），最多 $MAX_FELICA_BLOCKS 个"
        val blockListOverride = args.strArg("block_list_hex")?.let { raw ->
            normalizeHexBlob(raw)?.let { hex -> ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                ?: return "ERROR: block_list_hex 不是合法 hex"
        }

        val f = NfcF.get(tag) ?: return noFeliCa(tag)
        val timeout = (args.intArg("timeout_ms") ?: 5000).coerceIn(500, 60_000)
        try {
            f.timeout = timeout
            f.connect()
            val idm = tag.id
            if (idm.size != 8) return "ERROR: 该卡的 Tag.id 不是 8 字节 FeliCa IDm（实际 ${idm.size} 字节）"
            val systemCode = f.getSystemCode() ?: ByteArray(0)
            val sent = if (blockListOverride != null) {
                buildFelicaReadWithoutEncryption(idm, services, blockListOverride, blocks.size)
            } else {
                buildFelicaReadWithoutEncryption(idm, services, blocks)
            }
            val response = f.transceive(sent) ?: ByteArray(0)
            val reply = parseFelicaReadReply(response, blocks.size)
            val status = reply.status
            return buildJsonObject {
                put("uid", bytesToHex(tag.id))
                put("idm", bytesToHex(idm))
                put("systemCode", if (systemCode.size >= 2) "0x%04X".format(littleEndian16(systemCode)) else "")
                putJsonArray("serviceCodes") { services.forEach { add("0x%04X".format(it)) } }
                putJsonArray("requestedBlocks") {
                    blocks.forEach { (serviceIndex, blockNumber) ->
                        add(buildJsonObject { put("serviceIndex", serviceIndex); put("block", blockNumber) })
                    }
                }
                put("requestFrameHex", bytesToHex(sent))
                put("responseHex", bytesToHex(response))
                if (status != null) {
                    put("statusFlag1", "0x%02X".format(status.sf1))
                    put("statusFlag2", "0x%02X".format(status.sf2))
                    put("statusMeaning", status.text)
                    put("ok", status.ok)
                } else {
                    put("ok", false)
                }
                put("blockCount", reply.blocks.size)
                putJsonArray("blocks") {
                    reply.blocks.forEachIndexed { index, data ->
                        add(buildJsonObject {
                            put("index", index)
                            val pair = blocks.getOrNull(index)
                            if (pair != null) {
                                put("serviceIndex", pair.first)
                                put("block", pair.second)
                            }
                            put("hex", bytesToHex(data))
                            put("ascii", asciiOf(data))
                        })
                    }
                }
                reply.error?.let { put("error", it) }
                put("note", "Read Without Encryption 只读。日系交通卡余额常见于服务码 0x008B 块 0（不同卡种字段位置不同，按 hex 核对）。")
            }.toString()
        } finally {
            runCatching { f.close() }
        }
    }

    private fun noFeliCa(tag: Tag): String =
        "ERROR: 此卡不支持 FeliCa（NfcF），techList=${tag.techList.joinToString()}；日系卡请确认卡片贴近 NFC 天线，" +
            "ISO-DEP 卡请用 nfc_iso_dep_transceive"

    private fun parseFelicaServiceCodes(text: String): List<Int>? {
        val codes = text.split(',', ';', ' ', '\n', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (codes.isEmpty() || codes.size > MAX_FELICA_SERVICES) return null
        return codes.map { token ->
            val hex = normalizeHexBlob(token) ?: return null
            if (hex.length != 4) return null
            hex.toInt(16)
        }
    }

    private fun parseFelicaBlocks(text: String): List<Pair<Int, Int>>? {
        val tokens = text.split(',', ';', ' ', '\n', '|').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > MAX_FELICA_BLOCKS) return null
        return tokens.map { token ->
            val parts = token.split(':')
            val serviceIndex: Int
            val block: Int
            when (parts.size) {
                1 -> {
                    serviceIndex = 0
                    block = parts[0].trim().toIntOrNull() ?: return null
                }
                2 -> {
                    serviceIndex = parts[0].trim().toIntOrNull() ?: return null
                    block = parts[1].trim().toIntOrNull() ?: return null
                }
                else -> return null
            }
            if (serviceIndex !in 0..15 || block !in 0..255) return null
            serviceIndex to block
        }
    }

    /* -------------------------------- shared -------------------------------- */

    private fun rfTechLabel(techs: Array<String>): String = when {
        techs.contains("android.nfc.tech.NfcA") && techs.contains("android.nfc.tech.MifareClassic") ->
            "ISO 14443-A / MIFARE Classic"
        techs.contains("android.nfc.tech.NfcA") -> "ISO 14443-A（ISO-DEP / 14443-4）"
        techs.contains("android.nfc.tech.NfcB") -> "ISO 14443-B（ISO-DEP）"
        techs.contains("android.nfc.tech.NfcF") -> "FeliCa（JIS X 6319-4）"
        techs.contains("android.nfc.tech.NfcV") -> "ISO 15693（Vicinity）"
        else -> techs.joinToString()
    }

    private fun felicaSystemHint(systemCode: Int): String? = when (systemCode) {
        0x0003 -> "交通系 IC 卡（Suica/PASMO/ICOCA/Kitaca/SAPICA 等）"
        0x00FE -> "FeliCa 共通領域（共通コマンド）"
        0x12FC -> "電子マネー（楽天Edy 等）"
        else -> null
    }

    private fun littleEndian16(bytes: ByteArray): Int {
        if (bytes.size < 2) return 0
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }

    companion object {
        /** Keep the gate sticky per card so a later APDU on the same card cannot bypass it. */
        private const val SENSITIVE_TTL_MS = 5 * 60 * 1000L
        private val sensitiveTags = HashMap<String, Long>()

        private fun markSensitiveTag(uid: ByteArray) {
            synchronized(sensitiveTags) { sensitiveTags[bytesToHex(uid)] = System.currentTimeMillis() + SENSITIVE_TTL_MS }
        }

        private fun isSensitiveTag(uid: ByteArray): Boolean {
            val key = bytesToHex(uid)
            val until = synchronized(sensitiveTags) { sensitiveTags[key] } ?: return false
            if (until < System.currentTimeMillis()) {
                synchronized(sensitiveTags) { sensitiveTags.remove(key) }
                return false
            }
            return true
        }
    }
}

package com.dc.andagent.tools

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.os.Build
import java.nio.charset.Charset

/**
 * R145: 碰一碰 NFC → 自动创建对话并分析 NFC 数据。
 *
 * 拆成两层以便 JVM 单测（不依赖 android.nfc 对象）：
 *  - [extractNfcTag] 读 Intent 里的 android.nfc 对象，转成纯数据 [NfcTagPayload]；
 *  - [describeNfcPayload] 是纯函数，把 [NfcTagPayload] 组装成中文提示词，
 *    可直接用 JUnit 精确断言各类型文本。
 * [buildNfcPrompt] 是两者组合的对外入口。完整的读卡仍由既有 nfc_read 工具负责，
 * 本文件只做「提示词预览 + 注入」，不改动 NfcTool / NfcSession / queueDeeplink。
 */

/** 单条 NDEF 记录的纯数据表示（不含任何 android.nfc 对象）。 */
data class NfcRecordData(
    val kind: NfcRecordKind,
    val text: String? = null,   // TEXT RTD 解码后的文本
    val uri: String? = null,    // URI RTD 解码后的 URI
    val typeHex: String = "",   // 其它类型记录的 type 字节摘要（大写 hex）
    val payloadSize: Int = 0,   // payload 字节数
)

enum class NfcRecordKind { TEXT, URI, OTHER }

/** 一个 tag intent 的纯数据快照（不含任何 android.nfc 对象）。 */
data class NfcTagPayload(
    val idHex: String,                    // tag id，":" 分隔大写 hex，如 "04:A3:..:FF"
    val techs: List<String>,              // 短技术名，如 ["MifareClassic", "NfcA"]
    val records: List<NfcRecordData>? = null, // 有 NDEF 消息时为记录列表；无 NDEF 为 null
    val mifareSize: Int? = null,          // MifareClassic 容量（字节）
    val ndefMaxSize: Int? = null,         // Ndef.maxSize
) {
    /** 裸 tag 提示词里报告的容量：优先 Mifare 容量，其次 NDEF maxSize。 */
    val capacityBytes: Int? get() = mifareSize ?: ndefMaxSize
}

/**
 * 纯函数：把 [payload] 组装成用户语言（中文）的自动提示词。
 * - 有 NDEF 记录时优先：TEXT 取文本、URI 取 URI、其它给 type 字节摘要；
 * - 无 NDEF 的裸 tag：tag id、技术列表、容量；
 * - 空 / 无法解析返回 null（不注入）。
 */
fun describeNfcPayload(payload: NfcTagPayload): String? {
    val records = payload.records
    if (!records.isNullOrEmpty()) {
        val body = records.joinToString("；") { r ->
            when (r.kind) {
                NfcRecordKind.TEXT -> "NDEF 文本记录：\"${r.text.orEmpty()}\""
                NfcRecordKind.URI -> "URI 记录：${r.uri.orEmpty()}"
                NfcRecordKind.OTHER -> otherRecordFragment(r)
            }
        }
        // URI 记录的引导语与示例对齐，其余（文本 / 其它）统一引导 nfc_read 深读。
        val suffix = if (records.first().kind == NfcRecordKind.URI) "请读取并分析。"
        else "请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。"
        return "NFC 标签已贴上：检测到 $body。$suffix"
    }

    // 无 NDEF：裸 tag。
    if (payload.idHex.isEmpty() && payload.techs.isEmpty()) return null
    val techStr = payload.techs.joinToString(", ", "[", "]")
    val cap = payload.capacityBytes
    val capStr = if (cap != null) "，容量 ${cap}B" else ""
    return "NFC 标签已贴上：tag id=${payload.idHex}，技术=$techStr$capStr。请用 nfc_read 读取数据并分析这张卡的类型与内容。"
}

private fun otherRecordFragment(r: NfcRecordData): String {
    val typeSummary = r.typeHex.ifEmpty { "空" }
    return "NDEF 记录（type 字节=$typeSummary，payload ${r.payloadSize}B）"
}

/* ------------------------------ Android 提取层 ------------------------------ */

private val NFC_ACTIONS = setOf(
    NfcAdapter.ACTION_NDEF_DISCOVERED,
    NfcAdapter.ACTION_TECH_DISCOVERED,
    NfcAdapter.ACTION_TAG_DISCOVERED,
)

private fun parcelTag(intent: Intent): Tag? =
    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    else @Suppress("DEPRECATION") intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)

private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

/** 轻量读取 tag id 作为去重键（":" 分隔大写 hex）。extract 与 dedup 共用。 */
private fun readTagIdHex(intent: Intent): String {
    val id: ByteArray = runCatching {
        parcelTag(intent)?.id ?: intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
    }.getOrNull() ?: ByteArray(0)
    return id.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
}

/** 去重键：按 tag id（贴着不动会连续 dispatch，同 id 只注入一次）。 */
fun nfcTagDedupKey(intent: Intent): String = readTagIdHex(intent)

/**
 * 从 intent 提取 NFC tag 的纯数据快照。
 * 非 NFC action / 无 TAG / 任何异常都返回 null，绝不抛（never-crash）。
 * 不调用 Tag.connect()：读卡是 nfc_read 工具的职责，这里只用系统已解析的
 * EXTRA_NDEF_MESSAGES 与 tag 元数据（id / techList / 容量 getter），不做主线程 I/O。
 */
fun extractNfcTag(intent: Intent?): NfcTagPayload? {
    if (intent == null) return null
    val action = intent.action ?: return null
    if (action !in NFC_ACTIONS) return null
    return runCatching {
        val tag = parcelTag(intent)
        val idHex = readTagIdHex(intent)
        val techs = tag?.techList?.map { it.substringAfterLast('.') } ?: emptyList()

        // NDEF 消息：优先系统已解析的 EXTRA_NDEF_MESSAGES（ACTION_NDEF_DISCOVERED 携带）。
        val rawMsgs = @Suppress("DEPRECATION") intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val records = rawMsgs
            ?.mapNotNull { it as? NdefMessage }
            ?.flatMap { msg -> msg.records.map { describeRecord(it) } }
            ?.takeIf { it.isNotEmpty() }

        // MifareClassic 容量（纯 getter，不 connect；失败/不支持则省略）。
        val mifareSize = if (tag != null && "MifareClassic" in techs)
            runCatching { MifareClassic.get(tag)?.size }.getOrNull()
        else null
        val ndefMaxSize = if (tag != null) runCatching { Ndef.get(tag)?.maxSize }.getOrNull() else null

        NfcTagPayload(
            idHex = idHex,
            techs = techs,
            records = records,
            mifareSize = mifareSize,
            ndefMaxSize = ndefMaxSize,
        )
    }.getOrNull()
}

private fun describeRecord(rec: NdefRecord): NfcRecordData {
    val wellKnown = rec.tnf == NdefRecord.TNF_WELL_KNOWN
    return when {
        wellKnown && rec.type.contentEquals(NdefRecord.RTD_TEXT) ->
            NfcRecordData(NfcRecordKind.TEXT, text = decodeTextRecord(rec.payload), payloadSize = rec.payload.size)
        wellKnown && rec.type.contentEquals(NdefRecord.RTD_URI) ->
            NfcRecordData(NfcRecordKind.URI, uri = decodeUriRecord(rec.payload), payloadSize = rec.payload.size)
        else ->
            NfcRecordData(NfcRecordKind.OTHER, typeHex = rec.type.toHexUpper(), payloadSize = rec.payload.size)
    }
}

/** TEXT RTD：首字节 bit7=UTF-16、低 6 位=语言码长度，其后为文本。 */
private fun decodeTextRecord(payload: ByteArray): String {
    if (payload.isEmpty()) return ""
    val status = payload[0].toInt() and 0xFF
    val langLen = status and 0x3F
    if (payload.size < 1 + langLen) return ""
    val charset: Charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
    return runCatching { String(payload, 1 + langLen, payload.size - 1 - langLen, charset) }.getOrDefault("")
}

/** URI RTD：首字节为 NFC Forum 前缀表索引，其后为 UTF-8 URI 主体。 */
private val URI_PREFIXES = arrayOf(
    "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
    "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://",
    "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:",
    "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://", "tcpobex://",
    "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:", "urn:epc:pat:",
    "urn:epc:raw:", "urn:epc:", "urn:nfc:",
)

private fun decodeUriRecord(payload: ByteArray): String {
    if (payload.isEmpty()) return ""
    val idx = payload[0].toInt() and 0xFF
    val prefix = URI_PREFIXES.getOrNull(idx) ?: ""
    val rest = runCatching { String(payload, 1, payload.size - 1, Charsets.UTF_8) }.getOrDefault("")
    return prefix + rest
}

/** R145 spec 入口：Intent → 中文提示词（extract → describe 两层组合）。 */
fun buildNfcPrompt(intent: Intent): String? = extractNfcTag(intent)?.let(::describeNfcPayload)

/* --------------------------------- 去重窗口 --------------------------------- */

/**
 * 贴着不动会连续 dispatch 同一张 tag；3 秒窗口内同 key 只放行一次。
 * 时钟（毫秒）可注入，纯 JVM、无 Android 依赖，便于单测。
 */
class NfcTagDedup(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val windowMs: Long = 3_000L,
) {
    private var lastKey: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    @Synchronized
    fun shouldInject(key: String): Boolean {
        val t = nowMs()
        if (key == lastKey && t - lastAtMs < windowMs) return false
        lastKey = key
        lastAtMs = t
        return true
    }
}

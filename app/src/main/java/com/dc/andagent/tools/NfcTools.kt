package com.dc.andagent.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.nio.charset.Charset
import kotlin.coroutines.resume

/** One foreground NFC operation at a time, owned by MainActivity's lifecycle. */
class NfcSession(private val activity: Activity, private val prompt: (String) -> Unit) {
    private val adapter by lazy { NfcAdapter.getDefaultAdapter(activity) }
    private val handler = Handler(Looper.getMainLooper())
    private var pending: ((Tag?) -> Unit)? = null
    private var timeout: Runnable? = null
    private var resumed = false

    fun status(): String = when {
        adapter == null -> "ERROR: 设备不支持 NFC"
        adapter?.isEnabled != true -> "ERROR: NFC 未开启，请在系统设置中开启"
        else -> "NFC 已开启"
    }

    fun hasHce(): Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    fun nfcEnabled(): Boolean = adapter?.isEnabled == true

    suspend fun awaitTag(): Tag? = suspendCancellableCoroutine { cont ->
        handler.post {
            if (!cont.isActive) return@post
            if (pending != null) { cont.resume(null); return@post }
            pending = { tag -> if (cont.isActive) cont.resume(tag) }
            timeout = Runnable { finish(null) }.also { handler.postDelayed(it, 60_000) }
            prompt("请在 60 秒内将 NFC 卡片贴近手机")
            if (resumed) enable()
            cont.invokeOnCancellation { handler.post { finish(null) } }
        }
    }

    fun onResume() { resumed = true; if (pending != null) enable() }
    fun onPause() { resumed = false; disable() }
    fun onNewIntent(intent: Intent?) {
        if (pending == null || intent == null) return
        if (intent.action !in setOf(NfcAdapter.ACTION_TAG_DISCOVERED, NfcAdapter.ACTION_TECH_DISCOVERED, NfcAdapter.ACTION_NDEF_DISCOVERED)) return
        val tag = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            // R145c: 标记该 tag 已被正在进行的 nfc_read/write 前台读取消费。
            // injectNfcTagIfAny 随后拿到同一个 intent，buildNfcPrompt 见标记便跳过碰卡缓存写入，
            // 避免缓存后台读卡与前台读卡同时 connect 同一个 Tag（手动贴卡路径零破坏）。
            intent.putExtra(NFC_CONSUMED_BY_READ_EXTRA, true)
            finish(tag)
        }
    }
    fun close() { finish(null); disable() }
    private fun finish(tag: Tag?) {
        val callback = pending ?: return
        pending = null
        timeout?.let(handler::removeCallbacks)
        timeout = null
        disable()
        callback(tag)
    }
    private fun enable() = runCatching {
        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val dispatch = PendingIntent.getActivity(activity, 0, intent, flags)
        val techs = arrayOf(
            arrayOf("android.nfc.tech.Ndef"), arrayOf("android.nfc.tech.NdefFormatable"),
            arrayOf("android.nfc.tech.NfcA"), arrayOf("android.nfc.tech.NfcB"),
            arrayOf("android.nfc.tech.NfcF"), arrayOf("android.nfc.tech.NfcV"),
            arrayOf("android.nfc.tech.IsoDep"), arrayOf("android.nfc.tech.MifareClassic"),
            arrayOf("android.nfc.tech.MifareUltralight"),
        )
        adapter?.enableForegroundDispatch(activity, dispatch, null, techs)
    }
    private fun disable() = runCatching { adapter?.disableForegroundDispatch(activity) }
}

private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

/**
 * R145c: 把 nfc_read 的完整读卡结果抽成顶层函数，供 [NfcTool.read] 与碰卡缓存
 * [readCachedTagJson] 共用（复用同一套解析，缓存与实时读卡 JSON 结构一致）。
 * 需要 connect 读取 NDEF 消息，调用方负责在卡在场时执行；异常由调用方 runCatching 兜底。
 */
private fun buildNfcReadJson(tag: Tag): JsonObject {
    val ndef = Ndef.get(tag)
    val message = if (ndef != null) {
        try { ndef.connect(); ndef.ndefMessage } finally { runCatching { ndef.close() } }
    } else null
    return buildJsonObject {
        put("uid", tag.id.hex())
        put("idSize", tag.id.size)
        putJsonArray("techList") { tag.techList.forEach { add(it) } }
        put("isNdef", ndef != null)
        if (ndef != null) {
            put("maxSize", ndef.maxSize)
            put("writable", ndef.isWritable)
            put("canMakeReadOnly", runCatching { ndef.canMakeReadOnly() }.getOrDefault(false))
        }
        if (message != null) {
            put("size", message.toByteArray().size)
            putJsonArray("records") { message.records.forEach { add(buildNfcRecordJson(it)) } }
        }
    }
}

private fun buildNfcRecordJson(record: NdefRecord): JsonObject = buildJsonObject {
    put("tnf", record.tnf.toInt()); put("typeHex", record.type.hex()); put("idHex", record.id.hex())
    put("payloadHex", record.payload.hex()); put("size", record.payload.size)
    if (record.tnf == NdefRecord.TNF_WELL_KNOWN) when {
        record.type.contentEquals(NdefRecord.RTD_TEXT) && record.payload.isNotEmpty() -> {
            val n = record.payload[0].toInt() and 0x3f
            if (record.payload.size >= 1 + n) {
                val charset = if (record.payload[0].toInt() and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
                put("kind", "text"); put("language", String(record.payload, 1, n, Charsets.US_ASCII))
                put("text", String(record.payload, 1 + n, record.payload.size - 1 - n, charset))
            }
        }
        record.type.contentEquals(NdefRecord.RTD_URI) -> { put("kind", "uri"); put("uri", record.toUri()?.toString() ?: "") }
        record.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> {
            put("kind", "smart_poster")
            runCatching { NdefMessage(record.payload).records }.getOrNull()?.let { nested ->
                putJsonArray("records") { nested.forEach { add(buildNfcRecordJson(it)) } }
            }
        }
    }
    when (record.tnf) {
        NdefRecord.TNF_MIME_MEDIA -> {
            put("kind", "mime")
            put("mimeType", String(record.type, Charsets.US_ASCII))
            put("payloadText", runCatching { String(record.payload, Charsets.UTF_8) }.getOrDefault("").take(500))
        }
        NdefRecord.TNF_EXTERNAL_TYPE -> {
            put("kind", "external")
            put("externalType", String(record.type, Charsets.US_ASCII))
        }
        NdefRecord.TNF_UNKNOWN -> put("kind", "unknown")
    }
}

/**
 * R145c: 碰卡缓存专用读取。尽力做一次完整 read；connect 失败（卡片已移开）则
 * [buildCachedTagJson] 降级为只缓存 uid/techList 并如实标 failedToConnect=true。
 * 全程 runCatching，Tag getter 异常也兜底，绝不抛（防失败条款）。
 */
internal fun readCachedTagJson(tag: Tag): String {
    val base = runCatching { buildNfcReadJson(tag) }.getOrNull()
    return runCatching {
        buildCachedTagJson(base, tag.id.hex(), tag.id.size, tag.techList.toList()).toString()
    }.getOrElse {
        buildCachedTagJson(null, "", 0, emptyList()).toString()
    }
}

class NfcTool(private val session: NfcSession, override val name: String) : Tool {
    override val description = when (name) {
        "nfc_status" -> "查询设备 NFC 是否支持及是否开启。"
        "nfc_read" -> "读取 NFC UID、技术和 NDEF Text/URI/Smart Poster 记录。碰卡拉起对话时该次贴卡数据已缓存，调用即得、无需再贴卡；无缓存时等待贴卡最多 60 秒（调用前先告诉用户）。force=true 跳过缓存强制重新贴卡读取实时数据。"
        "nfc_write_text" -> "等待贴卡并写入 NDEF 文本。会覆盖卡片原有 NDEF 内容，先告知用户。"
        "nfc_write_uri" -> "等待贴卡并写入 NDEF URI。会覆盖卡片原有 NDEF 内容，先告知用户。"
        "nfc_write_raw" -> "等待贴卡并写入自定义 NDEF 记录：MIME、external type 或原始 payload（base64/hex）。"
        "nfc_hce_status" -> "查询设备是否支持 HCE 卡模拟，以及 AndAgent 注册的 AID。"
        else -> "NFC 工具"
    }
    override val parameters = when (name) {
        "nfc_read" -> schemaOf("force" to boolParam("true：跳过碰卡缓存，强制等待重新贴卡读取实时数据；默认直接消费碰卡缓存"))
        "nfc_write_text" -> schemaOf("text" to strParam("要写入的文本"), required = listOf("text"))
        "nfc_write_uri" -> schemaOf("uri" to strParam("要写入的 URI"), required = listOf("uri"))
        "nfc_write_raw" -> schemaOf(
            "kind" to strParam("mime|external|raw，默认 raw"),
            "payload" to strParam("payload：base64: 开头或 hex: 开头，纯文本可直接给字符串"),
            "type" to strParam("mime 时为 MIME 类型；external 时为 domain:type"),
            required = listOf("payload"),
        )
        else -> schemaOf()
    }
    override val destructive get() = name.startsWith("nfc_write")
    override suspend fun execute(args: JsonObject): String {
        if (name == "nfc_hce_status") return hceStatus()
        // R145c: 碰卡拉起的那次对话优先消费缓存，零二次贴卡。缓存是快照，不依赖当前
        // NFC 是否开启，故放在 status() 检查之前；无缓存 / force 时回落到原 awaitTag() 路径。
        // review 必修 1：takeAwait 短等（默认 3s）在途后台读卡落地——begin 占位后 AI 立即
        // 调 nfc_read 也能拿到数据；force=true 清缓存（在途线程 complete 因代际不符被丢弃，
        // review 必修 2），强制走重新贴卡。
        if (name == "nfc_read") {
            if (args.boolArg("force") == true) NfcTagCache.clear()
            else NfcTagCache.takeAwait()?.let { return it }
        }
        val state = session.status()
        if (name == "nfc_status" || state.startsWith("ERROR:")) return state
        val value = if (name == "nfc_write_text") args.strArg("text") else if (name == "nfc_write_uri") args.strArg("uri") else null
        if (name in setOf("nfc_write_text", "nfc_write_uri") && value == null) return "ERROR: 缺少要写入的内容"
        if (name == "nfc_write_raw" && args.strArg("payload") == null) return "ERROR: 缺少 payload"
        if (name == "nfc_write_uri" && runCatching { android.net.Uri.parse(value).scheme }.getOrNull().isNullOrBlank()) return "ERROR: URI 缺少 scheme"
        val tag = session.awaitTag() ?: return "ERROR: 等待 NFC 贴卡超时或已有读写任务（60 秒）"
        return runCatching {
            when (name) {
                "nfc_read" -> read(tag)
                "nfc_write_raw" -> write(tag, rawRecord(args))
                "nfc_write_text" -> write(tag, NdefRecord.createTextRecord("zh", value!!))
                else -> write(tag, NdefRecord.createUri(value!!))
            }
        }.getOrElse { "ERROR: NFC ${if (name == "nfc_read") "读取" else "写入"}失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun hceStatus(): String = buildJsonObject {
        put("hceSupported", session.hasHce())
        put("nfcEnabled", session.nfcEnabled())
        put("aid", "F0010203040506")
        put("service", "com.dc.andagent.tools.AndagentHceService")
        put("note", "设备作卡片模拟：读卡器 SELECT 该 AID 后返回 AndAgent 标识；如不支持 HCE 请降级为普通 NDEF 读写")
    }.toString()

    private fun rawRecord(args: JsonObject): NdefRecord {
        val raw = args.strArg("payload") ?: error("缺少 payload")
        val bytes = when {
            raw.startsWith("base64:") -> Base64.getDecoder().decode(raw.removePrefix("base64:").trim())
            raw.startsWith("hex:") -> raw.removePrefix("hex:").filter { !it.isWhitespace() }
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else -> raw.toByteArray(Charsets.UTF_8)
        }
        if (bytes.isEmpty()) error("payload 为空")
        if (bytes.size > 512) error("payload 超过 512 字节，NDEF 写入可能失败")
        val type = args.strArg("type")
        return when (args.strArg("kind")?.lowercase() ?: "raw") {
            "mime" -> NdefRecord.createMime(type ?: "application/octet-stream", bytes)
            "external" -> {
                val parts = (type ?: "").split(":", limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank())
                    error("external 需要 type 形如 domain:typename")
                NdefRecord.createExternal(parts[0], parts[1], bytes)
            }
            else -> NdefRecord(NdefRecord.TNF_UNKNOWN, byteArrayOf(), byteArrayOf(), bytes)
        }
    }
    private fun read(tag: Tag): String = buildNfcReadJson(tag).toString()
    private fun write(tag: Tag, record: NdefRecord): String {
        val message = NdefMessage(arrayOf(record))
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return "ERROR: NFC 卡片只读，无法写入"
                if (ndef.maxSize < message.toByteArray().size) return "ERROR: NFC 卡片容量不足（需要 ${message.toByteArray().size} 字节，可用 ${ndef.maxSize} 字节）"
                ndef.writeNdefMessage(message)
            } finally { runCatching { ndef.close() } }
        } else {
            val formatable = NdefFormatable.get(tag) ?: return "ERROR: 此 NFC 卡片不支持 NDEF 写入"
            try { formatable.connect(); formatable.format(message) } finally { runCatching { formatable.close() } }
        }
        return "WRITTEN: NFC 已写入（UID=${tag.id.hex()}，${message.toByteArray().size} 字节）"
    }
}

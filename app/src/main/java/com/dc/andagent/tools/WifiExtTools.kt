package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * R122 — Wi-Fi 三件套（短距感知刀的 Wi-Fi 部分，接 R87 / R81 / R121）。
 *
 *  a) wifi_easy_connect  Wi-Fi Easy Connect（DPP）：能力探测 + 拉起系统 DPP 扫码器（本机作
 *                        enrollee 扫 QR 免密入网）+ DPP URI 解析/复刻二维码
 *  b) wifi_direct        Wi-Fi Direct（WifiP2pManager，无路由器 P2P）：发现/建组/加入/解散，
 *                        API 29+ 可指定 SSID/密码让对端用普通 Wi-Fi 直接加入（补 R81 互传的
 *                        「无共同 Wi-Fi」场景）
 *  c) wifi_rtt           Wi-Fi RTT 测距（802.11mc FTM，米级）：对周边 responder AP 测距
 *
 * 能力边界（工具输出里也如实报告，不虚报成功）：
 *  - DPP 的 configurator 角色（手机给 IoT 设备下发凭据）是 @SystemApi，普通应用不可用；
 *    公开 API 只能当 enrollee 扫码入网。
 *  - Wi-Fi Direct / RTT 都需要精确定位权限（targetSdk 28 不需要 NEARBY_WIFI_DEVICES），
 *    且定位开关关闭时发现/扫描结果常为空。
 *  - RTT 需要 FEATURE_WIFI_RTT 硬件，isAvailable() 为 false 时是系统状态而非权限问题。
 */
val WIFI_EXT_TOOLS = listOf(
    "wifi_easy_connect",
    "wifi_direct",
    "wifi_rtt",
)

/* ---------------------------- pure helpers ---------------------------- */

/** 802.11 band/channel ↔ frequency，DPP URI 的 C 属性与 P2P 频率共用（纯函数）。 */
object WifiChannels {
    const val BAND_2G = 81
    const val BAND_60G = 180

    /** DPP 规范的 band 编号：81=2.4G，115/118/121/124=5G 分段，131=6G，180=60G。 */
    fun bandName(band: Int?): String? = when (band) {
        BAND_2G -> "2.4GHz"
        115, 118, 121, 124 -> "5GHz"
        131 -> "6GHz"
        BAND_60G -> "60GHz"
        else -> null
    }

    fun frequencyOf(band: Int?, channel: Int?): Int? {
        if (channel == null || channel <= 0) return null
        return when (band) {
            BAND_2G -> if (channel == 14) 2484 else 2407 + 5 * channel
            115, 118, 121, 124 -> 5000 + 5 * channel
            131 -> 5950 + 5 * channel
            else -> null
        }
    }

    /** "2437MHz (2.4GHz ch6)"；解析不出 band 时至少给 channel。 */
    fun channelText(band: Int?, channel: Int?): String? {
        if (channel == null) return null
        val name = bandName(band)
        val mhz = frequencyOf(band, channel)
        return buildString {
            if (mhz != null) append("${mhz}MHz ")
            if (name != null) append("$name ") else append("band$band ")
            append("ch$channel")
        }.trim()
    }

    /** 2412 → "ch1"（2.4G），5180 → "ch36"（5G）；60GHz 与非常规频率返回 null。 */
    fun channelOf(frequency: Int?): Int? {
        if (frequency == null) return null
        return when {
            frequency == 2484 -> 14
            frequency in 2412..2472 && (frequency - 2407) % 5 == 0 -> (frequency - 2407) / 5
            frequency in 5160..5885 && frequency % 5 == 0 -> (frequency - 5000) / 5
            frequency in 5955..7115 && frequency % 5 == 0 -> (frequency - 5950) / 5
            else -> null
        }
    }
}

/** DPP URI（Wi-Fi Easy Connect bootstrap URI）解析（纯函数）。 */
object DppCodec {
    const val PREFIX = "DPP:"
    const val MAX_URI_LENGTH = 1024

    // SubjectPublicKeyInfo 的常见长度：P-256=91, P-384=120, P-521=158。
    private val KNOWN_KEY_SIZES = setOf(91, 120, 158)

    data class Parsed(
        val raw: String,
        val version: Int?,
        val band: Int?,
        val channel: Int?,
        val channelText: String?,
        val mac: String?,
        val publicKey: String?,
        val publicKeyBytes: Int?,
        val host: String?,
        val attributes: Map<String, String>,
        val warnings: List<String>,
    ) {
        /** 带公钥（K）的 URI 属于配置方（configurator）引导码：扫它的一方作为 enrollee 入网。 */
        val isConfiguratorBootstrap: Boolean get() = !publicKey.isNullOrBlank()

        /** 单行摘要，方便塞进对话流。 */
        fun summary(): String {
            val parts = mutableListOf<String>()
            version?.let { parts.add("v$it") }
            channelText?.let { parts.add(it) }
            mac?.let { parts.add("MAC $it") }
            host?.let { parts.add("host $it") }
            if (publicKeyBytes != null) parts.add("公钥 ${publicKeyBytes}B")
            return parts.joinToString("，")
        }
    }

    fun isDppUri(text: String?): Boolean =
        text?.trim()?.uppercase(Locale.US)?.startsWith(PREFIX) == true

    /**
     * 解析并校验 DPP URI。格式非法时抛 IllegalArgumentException（中文报错）；
     * 可疑但不致命的问题（缺 C/K、未知属性、公钥长度异常）进 warnings。
     */
    fun parse(raw: String): Parsed {
        val text = raw.trim()
        require(text.isNotEmpty()) { "DPP URI 为空" }
        require(text.length <= MAX_URI_LENGTH) { "DPP URI 过长（${text.length} 字节，上限 $MAX_URI_LENGTH）" }
        require(text.uppercase(Locale.US).startsWith(PREFIX)) { "不是 DPP URI：应以 DPP: 开头（收到 ${text.take(12)}…）" }
        require(text.all { it.code in 0x21..0x7E }) { "DPP URI 只能包含可见 ASCII 字符，可能混入了空格/换行或全角字符" }
        val body = text.substring(PREFIX.length).trimEnd(';')
        require(body.isNotEmpty()) { "DPP URI 没有属性段" }
        val warnings = mutableListOf<String>()
        val attributes = LinkedHashMap<String, String>()
        for (segment in body.split(';')) {
            val seg = segment.trim()
            if (seg.isEmpty()) continue
            val idx = seg.indexOf(':')
            if (idx <= 0) {
                warnings += "忽略无法解析的属性段「$seg」"
                continue
            }
            val key = seg.substring(0, idx).trim().uppercase(Locale.US)
            val value = seg.substring(idx + 1).trim()
            if (attributes.containsKey(key)) warnings += "属性 $key 重复，采用最后一次的值"
            attributes[key] = value
        }
        require(attributes.isNotEmpty()) { "DPP URI 未解析出任何属性" }

        val version = attributes["V"]?.let { v ->
            v.toIntOrNull() ?: run { warnings += "V 属性不是数字：$v"; null }
        }
        if (version != null && version !in 1..2) warnings += "未知 DPP 版本 $version（公开规范为 1/2）"

        var band: Int? = null
        var channel: Int? = null
        attributes["C"]?.let { c ->
            val pieces = c.split('/')
            band = pieces.getOrNull(0)?.trim()?.toIntOrNull()
            channel = pieces.getOrNull(1)?.trim()?.toIntOrNull()
            when {
                band == null -> warnings += "C 属性无法解析：$c（应为 band/channel，如 81/6）"
                channel == null -> warnings += "C 属性缺少信道：$c（应为 band/channel，如 81/6）"
                band !in setOf(81, 115, 118, 121, 124, 131, 180) ->
                    warnings += "未知频段编号 $band（常见 81=2.4G / 115…=5G / 131=6G）"
            }
        } ?: run { warnings += "缺少 C（信道）属性：系统扫码器可能拒绝该 URI" }

        attributes["M"]?.let { m ->
            if (!MAC_PATTERN.matches(m)) warnings += "M 不是合法 MAC：$m"
        } ?: run { warnings += "缺少 M（MAC）属性" }

        var keyBytes: Int? = null
        val publicKey = attributes["K"]?.let { k ->
            val decoded = decodeKey(k)
            if (decoded == null) {
                warnings += "K 不是合法的 base64/base64url 编码"
                null
            } else {
                keyBytes = decoded.size
                if (decoded.size !in KNOWN_KEY_SIZES) {
                    warnings += "K 解码后 ${decoded.size} 字节，不是常见 P-256/P-384/P-521 公钥长度（91/120/158），可能被截断"
                }
                k
            }
        } ?: run { warnings += "缺少 K（公钥）属性：缺少配置方公钥，无法用于 DPP 引导"; null }

        attributes["H"]?.let { h -> if (h.length > 253) warnings += "H（主机名）超过 253 字符" }

        return Parsed(
            raw = text,
            version = version ?: 1,
            band = band,
            channel = channel,
            channelText = WifiChannels.channelText(band, channel),
            mac = attributes["M"]?.uppercase(Locale.US)?.replace('-', ':'),
            publicKey = publicKey,
            publicKeyBytes = keyBytes,
            host = attributes["H"],
            attributes = attributes,
            warnings = warnings,
        )
    }

    private val MAC_PATTERN = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    /** base64url 优先，兼容 v1 的标准 base64（含 padding / +/）。失败返回 null。 */
    fun decodeKey(value: String): ByteArray? {
        val trimmed = value.trim()
        return runCatching { Base64.getUrlDecoder().decode(trimmed) }
            .recoverCatching { Base64.getDecoder().decode(trimmed) }
            .getOrNull()
    }
}

/** Wi-Fi Direct（WifiP2pManager）状态/原因与群组凭据校验（纯函数）。 */
object WifiP2pCodec {
    /** ActionListener.onFailure 的 reason 常量。 */
    const val REASON_ERROR = 0
    const val REASON_P2P_UNSUPPORTED = 1
    const val REASON_BUSY = 2
    const val REASON_NO_SERVICE_REQUESTS = 3

    fun reasonName(reason: Int): String = when (reason) {
        REASON_ERROR -> "ERROR"
        REASON_P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        REASON_BUSY -> "BUSY"
        REASON_NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "UNKNOWN($reason)"
    }

    fun reasonText(reason: Int): String = when (reason) {
        REASON_ERROR -> "系统错误（Wi-Fi 状态异常或被系统拒绝，稍后重试）"
        REASON_P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
        REASON_BUSY -> "系统繁忙（有连接操作进行中，等几秒再试）"
        REASON_NO_SERVICE_REQUESTS -> "没有登记服务请求"
        else -> "未知错误码 $reason"
    }

    /** WifiP2pDevice.status 常量。 */
    fun deviceStatusName(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "unknown($status)"
    }

    /** null=通过；否则返回中文错误说明。SSID 限 32 字节 UTF-8。 */
    fun validateNetworkName(ssid: String?): String? {
        if (ssid.isNullOrBlank()) return null
        val bytes = ssid.toByteArray(Charsets.UTF_8)
        if (bytes.size > 32) return "群组 SSID 超过 32 字节（UTF-8，实际 ${bytes.size} 字节）"
        if (ssid != ssid.trim()) return "群组 SSID 不能以空格开头/结尾"
        return null
    }

    /** WPA2 口令 8..63 个可打印 ASCII；null=通过。 */
    fun validatePassphrase(pass: String?): String? {
        if (pass.isNullOrBlank()) return null
        if (pass.length < 8) return "口令至少 8 位（当前 ${pass.length}）"
        if (pass.length > 63) return "口令最多 63 位（当前 ${pass.length}）"
        if (pass.any { it.code !in 0x20..0x7E }) return "口令只能包含可打印 ASCII 字符"
        return null
    }

    /** DIRECT-<前缀>-<token>，超 32 字节时截断 token。 */
    fun defaultNetworkName(prefix: String, token: String): String {
        val clean = prefix.filter { it.code in 0x20..0x7E }.take(8).ifEmpty { "AndAgent" }
        var name = "DIRECT-$clean-$token"
        while (name.toByteArray(Charsets.UTF_8).size > 32 && token.isNotEmpty()) {
            name = "DIRECT-$clean-${token.take(token.length - 1)}"
        }
        return name
    }

    fun frequencyLabel(mhz: Int?): String? {
        if (mhz == null || mhz <= 0) return null
        return WifiChannels.channelOf(mhz)?.let { ch ->
            val band = when {
                mhz < 2484 -> "2.4GHz"
                mhz < 5000 -> "5GHz"
                mhz < 5900 -> "5GHz"
                else -> "6GHz"
            }
            "$band ch$ch (${mhz}MHz)"
        } ?: "${mhz}MHz"
    }
}

/** RTT 测距结果统计（纯函数）。 */
object RttCodec {
    const val STATUS_SUCCESS = 0
    const val STATUS_FAIL = 1
    const val STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC = 2

    fun statusName(status: Int): String = when (status) {
        STATUS_SUCCESS -> "success"
        STATUS_FAIL -> "fail"
        STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC -> "not_80211mc"
        else -> "unknown($status)"
    }

    fun failureCodeName(code: Int): String = when (code) {
        RangingResultCallback.STATUS_CODE_FAIL -> "fail"
        RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE -> "rtt_not_available"
        else -> "unknown($code)"
    }

    fun distanceText(mm: Int): String = String.format(Locale.US, "%.2f m", mm / 1000.0)

    /** 标准差 → 可靠性评级：±5cm 内 high，±1.5m 内 medium，其余 low。 */
    fun reliability(stdDevMm: Int): String = when {
        stdDevMm <= 500 -> "high"
        stdDevMm <= 1500 -> "medium"
        else -> "low"
    }

    data class Stats(
        val count: Int,
        val meanMm: Int?,
        val medianMm: Int?,
        val minMm: Int?,
        val maxMm: Int?,
        val stdDevMm: Int?,
    )

    fun stats(values: List<Int>): Stats {
        if (values.isEmpty()) return Stats(0, null, null, null, null, null)
        val sorted = values.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else ((sorted[n / 2 - 1] + sorted[n / 2]) / 2.0).toInt()
        val mean = sorted.sum() / n
        val variance = sorted.sumOf { (it - mean).toDouble() } / n
        return Stats(
            count = n,
            meanMm = mean,
            medianMm = median,
            minMm = sorted.first(),
            maxMm = sorted.last(),
            stdDevMm = kotlin.math.sqrt(variance).toInt(),
        )
    }
}

/* -------------------------- wifi_easy_connect -------------------------- */

private const val DPP_SCANNER_ACTION = "android.settings.WIFI_DPP_ENROLLEE_QR_CODE_SCANNER"

@SuppressLint("MissingPermission")
class WifiEasyConnectTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String = "wifi_easy_connect",
) : Tool {

    override val description: String =
        "Wi-Fi Easy Connect（DPP）免密入网。action=status 查询能力（设备是否支持 DPP、系统扫码器是否可用）；" +
            "action=scan 拉起系统 DPP 扫码器：本机作为 enrollee 扫配置方显示的二维码，免输密码加入 Wi-Fi" +
            "（典型：路由器/另一台手机显示 DPP QR）；action=uri 解析一段 DPP URI（band/信道/MAC/公钥等结构化信息），" +
            "可 qr=true 把该 URI 复刻成二维码 PNG 供另一台手机扫。诚实边界：Android 公开 API 只提供 enrollee 角色，" +
            "本机无法作为 configurator 给 IoT 设备下发凭据（那是 @SystemApi）；ESPHome 等设备配网请走设备自身的" +
            " Improv/AP 模式流程。需要 Android 10（API 29）+。"

    override val parameters = schemaOf(
        "action" to strParam("status（默认）| scan | uri"),
        "uri" to strParam("action=uri 时必填：DPP URI 文本，如 DPP:C:81/6;M:aa:bb:cc:dd:ee:ff;K:...;;"),
        "qr" to boolParam("action=uri 时是否同时生成二维码 PNG（存 Pictures/AndAgent，返回 savedPath）"),
    )

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (args.strArg("action") ?: "status") {
                "status" -> status()
                "scan" -> scan()
                "uri" -> parseUri(args)
                else -> "ERROR: action 只能是 status / scan / uri"
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun dppIntent(): Intent = Intent(DPP_SCANNER_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun scannerAvailable(): Boolean = runCatching {
        context.packageManager.resolveActivity(dppIntent(), 0) != null
    }.getOrDefault(false)

    private suspend fun status(): String {
        val sdk = Build.VERSION.SDK_INT
        val pm = context.packageManager
        val easyConnect = if (sdk >= 29) {
            runCatching { wifiManager?.isEasyConnectSupported() }.getOrNull()
        } else null
        val dppAkm = if (sdk >= 33) {
            runCatching { wifiManager?.isEasyConnectDppAkmSupported() }.getOrNull()
        } else null
        val scanner = scannerAvailable()
        val notes = mutableListOf<String>()
        if (sdk < 29) notes += "Android 版本低于 10，DPP 不可用"
        if (easyConnect == false) notes += "设备不支持 Wi-Fi Easy Connect（isEasyConnectSupported=false）"
        if (easyConnect == true && !scanner) {
            notes += "设备声明支持 DPP，但没有解析到系统扫码界面（部分 ROM 裁剪），scan 会降级打开 Wi-Fi 设置"
        }
        notes += "公开 API 只有 enrollee 角色：本机能扫码入网，不能给 IoT 设备下发凭据（configurator 是 @SystemApi）"
        return buildJsonObject {
            put("action", "status")
            put("apiLevel", sdk)
            put("wifiSupported", pm.hasSystemFeature(PackageManager.FEATURE_WIFI))
            put("easyConnectSupported", easyConnect)
            put("dppAkmSupported", dppAkm)
            put("systemScannerAvailable", scanner)
            put("configuratorSupported", false)
            put("wifiEnabled", runCatching { wifiManager?.isWifiEnabled }.getOrNull())
            putJsonArray("notes") { notes.forEach { add(it) } }
        }.toString()
    }

    private suspend fun scan(): String {
        if (Build.VERSION.SDK_INT < 29) {
            return "ERROR: DPP 需要 Android 10（API 29）及以上，当前 API ${Build.VERSION.SDK_INT}"
        }
        val supported = runCatching { wifiManager?.isEasyConnectSupported() }.getOrNull()
        if (supported == false) {
            return "ERROR: 该设备不支持 Wi-Fi Easy Connect（DPP），无法扫码入网；请用系统 Wi-Fi 列表手动输入密码"
        }
        if (runCatching { wifiManager?.isWifiEnabled }.getOrNull() == false) {
            return "ERROR: Wi-Fi 未开启，请先开启 Wi-Fi 再扫码"
        }
        return withContext(Dispatchers.Main) {
            if (scannerAvailable()) {
                runCatching { context.startActivity(dppIntent()) }
                    .onFailure { return@withContext "ERROR: 打开系统 DPP 扫码器失败：${it.message}" }
                "已拉起系统 DPP 扫码器：请对准配置方（路由器/另一台手机）显示的 Wi-Fi Easy Connect 二维码，" +
                    "扫中后本机免密入网；结果请在 wifi_info 里确认"
            } else {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { return@withContext "ERROR: 系统没有 DPP 扫码界面，且无法打开 Wi-Fi 设置：${it.message}" }
                "该 ROM 裁剪了系统 DPP 扫码界面，已降级打开 Wi-Fi 设置页；" +
                    "请用「设置 → 网络和互联网 → Wi-Fi → 添加网络 → 扫码」（如有）或厂商应用完成扫码"
            }
        }
    }

    private fun parseUri(args: JsonObject): String {
        val raw = args.strArg("uri") ?: return "ERROR: 缺少 uri（DPP URI 文本）"
        val parsed = DppCodec.parse(raw)
        val out = buildJsonObject {
            put("action", "uri")
            put("version", parsed.version)
            put("band", parsed.band)
            put("channel", parsed.channel)
            parsed.channelText?.let { put("channelText", it) }
            parsed.mac?.let { put("mac", it) }
            parsed.publicKeyBytes?.let { put("publicKeyBytes", it) }
            parsed.host?.let { put("host", it) }
            put("isConfiguratorBootstrap", parsed.isConfiguratorBootstrap)
            put("attributes", JsonObject(parsed.attributes.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            putJsonArray("warnings") { parsed.warnings.forEach { add(it) } }
            put("summary", parsed.summary())
            put("hint", "该 URI 是配置方引导码：另一台手机扫它后会作为 enrollee 免密入网（本机也可用 action=scan 拉起系统扫码器）")
        }
        if (args.boolArg("qr") != true) return out.toString()

        val size = 640
        val matrix = QrCodec.encode(parsed.raw, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
        val savedPath = ImageEditEngine.save(context, bitmap, png = true)
        val thumbnail = ImageEditEngine.thumbnail(bitmap)
        bitmap.recycle()
        return buildJsonObject {
            put("action", "uri")
            put("version", parsed.version)
            put("band", parsed.band)
            put("channel", parsed.channel)
            parsed.channelText?.let { put("channelText", it) }
            parsed.mac?.let { put("mac", it) }
            put("isConfiguratorBootstrap", parsed.isConfiguratorBootstrap)
            putJsonArray("warnings") { parsed.warnings.forEach { add(it) } }
            put("summary", parsed.summary())
            put("savedPath", savedPath)
            put("thumbnailBase64", thumbnail)
            put("thumbnailMime", "image/png")
        }.toString()
    }
}

/* ----------------------------- wifi_direct ----------------------------- */

@SuppressLint("MissingPermission")
class WifiDirectTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String = "wifi_direct",
) : Tool {

    override val description: String =
        "Wi-Fi Direct（WifiP2pManager，无路由器点对点）。action=status 查本机 P2P 状态与当前群组；" +
            "action=discover 发现附近 Wi-Fi Direct 设备（seconds 秒窗口）；action=connect 与指定设备建组连接" +
            "（address=MAC 或 name=名称模糊匹配）；action=create_group 自建群组——API 29+ 可指定 ssid/passphrase，" +
            "另一台手机用「普通 Wi-Fi 连接」加入该热点即可（无需双方都支持 P2P 发现）；action=group 查群组与" +
            "连接信息（群主的 groupOwnerIp 可供 HTTP 直连，如 R81 互传）；action=listen/stop_listen 让本机保持" +
            "可被发现；action=remove_group 断开并解散群组。需要精确定位权限且定位开关打开（系统限制，否则发现" +
            "不到设备）。破坏性：会改变网络连接状态。"

    override val parameters = schemaOf(
        "action" to strParam("status（默认）| discover | connect | create_group | group | listen | stop_listen | remove_group"),
        "address" to strParam("对端设备 MAC（connect 用；优先于 name）"),
        "name" to strParam("对端设备名称模糊匹配（connect 用，先 discover 再选）"),
        "ssid" to strParam("create_group/connect：群组 SSID（API 29+，≤32 字节；缺省由系统随机生成）"),
        "passphrase" to strParam("create_group/connect：群组口令（API 29+，8-63 位可打印 ASCII）"),
        "frequency" to intParam("create_group：群组工作频率 MHz，如 2437（2.4G ch6）或 5180；缺省自动"),
        "seconds" to intParam("discover 扫描窗口秒数，默认 12，范围 3..60"),
        "timeout_s" to intParam("connect/create_group 等待建组超时秒数，默认 30，范围 5..120"),
    )

    override val destructive: Boolean = true

    /** listen 的会话级持有（Channel 必须存活，否则 P2P 框架会自动断开）。 */
    private companion object {
        var listenChannel: WifiP2pManager.Channel? = null
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args.strArg("action") ?: "status"
        return withContext(Dispatchers.Main) {
            runCatching { dispatch(action, args) }
                .getOrElse { "ERROR: wifi_direct $action 失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private val manager: WifiP2pManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private fun newChannel(m: WifiP2pManager): WifiP2pManager.Channel? =
        runCatching { m.initialize(context, Looper.getMainLooper(), null) }.getOrNull()

    private fun closeChannel(channel: WifiP2pManager.Channel?) {
        (channel as? AutoCloseable)?.let { runCatching { it.close() } }
    }

    private suspend fun dispatch(action: String, args: JsonObject): String {
        val m = manager ?: return "ERROR: Wi-Fi P2P 服务不可用"
        val supported = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        }.getOrDefault(true)
        if (!supported) return "ERROR: 设备不支持 Wi-Fi Direct（缺 FEATURE_WIFI_DIRECT）"
        val wifiOn = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
        }.getOrNull() ?: true
        if (!wifiOn) return "ERROR: Wi-Fi 未开启，Wi-Fi Direct 依赖 Wi-Fi 射频"

        return when (action) {
            "status" -> status(m)
            "discover" -> discover(m, args)
            "connect" -> connect(m, args)
            "create_group" -> createGroup(m, args)
            "group" -> group(m)
            "listen" -> listen(m)
            "stop_listen" -> stopListen(m)
            "remove_group" -> removeGroup(m)
            else -> "ERROR: action 只能是 status/discover/connect/create_group/group/listen/stop_listen/remove_group"
        }
    }

    /* ----------------------------- actions ----------------------------- */

    private suspend fun status(m: WifiP2pManager): String {
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val group = requestGroupInfo(m, channel)
            val info = requestConnectionInfo(m, channel)
            return groupJson(m, channel, group, info, "status").toString()
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    private suspend fun discover(m: WifiP2pManager, args: JsonObject): String {
        val seconds = (args.intArg("seconds") ?: 12).coerceIn(3, 60)
        val permission = ensureWifiPermission() ?: return permissionError()
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val started = awaitAction(m, channel, 10_000) { m.discoverPeers(channel, it) }
            if (started == null) return "ERROR: discoverPeers 无响应（10 秒超时），请重试"
            if (!started.first) return "ERROR: 启动发现失败：${WifiP2pCodec.reasonText(started.second)}"
            delay(seconds * 1000L)
            val peers = requestPeers(m, channel)
            runCatching { awaitAction(m, channel, 5_000) { m.stopPeerDiscovery(channel, it) } }
            val devices = peers?.deviceList.orEmpty().sortedByDescending { it.status == WifiP2pDevice.AVAILABLE }
            return buildJsonObject {
                put("action", "discover")
                put("seconds", seconds)
                put("count", devices.size)
                putJsonArray("peers") {
                    devices.forEach { d ->
                        add(buildJsonObject {
                            put("name", d.deviceName ?: "")
                            put("address", d.deviceAddress ?: "")
                            put("status", WifiP2pCodec.deviceStatusName(d.status))
                            put("isGroupOwner", d.isGroupOwner)
                            put("wpsPbc", d.wpsPbcSupported())
                            put("wpsDisplay", d.wpsDisplaySupported())
                            put("wpsKeypad", d.wpsKeypadSupported())
                            d.primaryDeviceType?.let { put("primaryDeviceType", it) }
                        })
                    }
                }
                put("locationPermission", permission)
                if (devices.isEmpty()) {
                    put("hint", "没扫到设备：对端需先开启 Wi-Fi（或同为 discover/listen 状态）；定位开关关闭时系统会隐藏结果")
                } else {
                    put("hint", "用 action=connect + address 连接；或在对端 create_group 后本机用普通 Wi-Fi 连它的 SSID")
                }
            }.toString()
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    private suspend fun connect(m: WifiP2pManager, args: JsonObject): String {
        ensureWifiPermission() ?: return permissionError()
        val address = args.strArg("address")?.let { MacAddress.fromString(normalizeMac(it)) }
        var nameHint: String? = null
        val resolvedAddress = address ?: args.strArg("name")?.let { name ->
            val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
            val peers = requestPeers(m, channel)
            closeChannel(channel)
            val hit = peers?.deviceList.orEmpty().firstOrNull {
                it.deviceName?.contains(name, ignoreCase = true) == true
            }
            if (hit == null) return "ERROR: 没有名称包含「$name」的已发现设备；先 action=discover 再重试"
            nameHint = hit.deviceName
            runCatching { MacAddress.fromString(normalizeMac(hit.deviceAddress)) }.getOrNull()
                ?: return "ERROR: 设备 MAC 非法：${hit.deviceAddress}"
        } ?: return "ERROR: 缺少 address 或 name（connect 需要知道连谁；先 discover）"

        val ssidError = WifiP2pCodec.validateNetworkName(args.strArg("ssid"))
        if (ssidError != null) return "ERROR: $ssidError"
        val passError = WifiP2pCodec.validatePassphrase(args.strArg("passphrase"))
        if (passError != null) return "ERROR: $passError"

        val config = buildConfig(args, resolvedAddress)
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val result = awaitAction(m, channel, 15_000) { m.connect(channel, config, it) }
            if (result == null) return "ERROR: connect 无响应（15 秒超时），请重试"
            if (!result.first) return "ERROR: 发起连接失败：${WifiP2pCodec.reasonText(result.second)}"

            val timeoutMs = ((args.intArg("timeout_s") ?: 30).coerceIn(5, 120)) * 1000L
            val formed = awaitGroupFormed(m, channel, timeoutMs)
            val group = formed.first
            val info = formed.second
            return if (info?.groupFormed == true || group != null) {
                val json = groupJson(m, channel, group, info, "connect")
                // JsonObject 不可变：合并出带 connectedName 的新对象
                val merged = nameHint?.let { n ->
                    JsonObject(json.toMutableMap().apply { put("connectedName", JsonPrimitive(n)) })
                } ?: json
                merged.toString()
            } else {
                "ERROR: 连接请求已受理但 ${timeoutMs / 1000} 秒内未建组；对端可能弹出了授权框（请去对端确认），稍后用 action=group 查看"
            }
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    private suspend fun createGroup(m: WifiP2pManager, args: JsonObject): String {
        ensureWifiPermission() ?: return permissionError()
        val ssidError = WifiP2pCodec.validateNetworkName(args.strArg("ssid"))
        if (ssidError != null) return "ERROR: $ssidError"
        val passError = WifiP2pCodec.validatePassphrase(args.strArg("passphrase"))
        if (passError != null) return "ERROR: $passError"

        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val config = buildConfig(args, null)
            val result = if (config != null && Build.VERSION.SDK_INT >= 29) {
                awaitAction(m, channel, 15_000) { m.createGroup(channel, config, it) }
            } else {
                awaitAction(m, channel, 15_000) { m.createGroup(channel, it) }
            }
            if (result == null) return "ERROR: createGroup 无响应（15 秒超时），请重试"
            if (!result.first) return "ERROR: 建组失败：${WifiP2pCodec.reasonText(result.second)}"

            val timeoutMs = ((args.intArg("timeout_s") ?: 20).coerceIn(5, 120)) * 1000L
            val deadline = System.currentTimeMillis() + timeoutMs
            var group = requestGroupInfo(m, channel)
            while (group?.networkName == null && System.currentTimeMillis() < deadline) {
                delay(400)
                group = requestGroupInfo(m, channel)
            }
            if (group?.networkName == null) {
                return "ERROR: 建组请求成功但 ${timeoutMs / 1000} 秒内未拿到群组信息；请稍后用 action=group 查看"
            }
            return groupJson(m, channel, group, requestConnectionInfo(m, channel), "create_group").toString()
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    private suspend fun group(m: WifiP2pManager): String {
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val group = requestGroupInfo(m, channel)
            val info = requestConnectionInfo(m, channel)
            return if (group == null && info?.groupFormed != true) {
                buildJsonObject {
                    put("action", "group")
                    put("inGroup", false)
                    put("hint", "当前没有 Wi-Fi Direct 群组；用 create_group 建组或 connect 连接对端")
                }.toString()
            } else {
                groupJson(m, channel, group, info, "group").toString()
            }
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    private suspend fun listen(m: WifiP2pManager): String {
        ensureWifiPermission() ?: return permissionError()
        listenChannel?.let { old ->
            runCatching { awaitAction(m, old, 5_000) { m.stopListening(old, it) } }
            closeChannel(old)
        }
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        val result = awaitAction(m, channel, 10_000) { m.startListening(channel, it) }
        if (result == null) {
            closeChannel(channel)
            return "ERROR: startListening 无响应（10 秒超时）"
        }
        if (!result.first) {
            closeChannel(channel)
            return "ERROR: 进入监听模式失败：${WifiP2pCodec.reasonText(result.second)}"
        }
        listenChannel = channel
        return buildJsonObject {
            put("action", "listen")
            put("listening", true)
            put("hint", "本机已进入可被发现状态；对端用 wifi_direct(discover) 即可扫到。退出用 stop_listen（或 remove_group）")
        }.toString()
    }

    private suspend fun stopListen(m: WifiP2pManager): String {
        val channel = listenChannel
        if (channel == null) {
            return buildJsonObject { put("action", "stop_listen"); put("listening", false) }.toString()
        }
        runCatching { awaitAction(m, channel, 5_000) { m.stopListening(channel, it) } }
        closeChannel(channel)
        listenChannel = null
        return buildJsonObject {
            put("action", "stop_listen")
            put("listening", false)
        }.toString()
    }

    private suspend fun removeGroup(m: WifiP2pManager): String {
        val channel = newChannel(m) ?: return "ERROR: 无法初始化 Wi-Fi P2P 通道"
        try {
            val result = awaitAction(m, channel, 10_000) { m.removeGroup(channel, it) }
            if (result == null) return "ERROR: removeGroup 无响应（10 秒超时）"
            if (!result.first) return "ERROR: 解散群组失败：${WifiP2pCodec.reasonText(result.second)}（可能本来就没有群组）"
            return buildJsonObject {
                put("action", "remove_group")
                put("removed", true)
                put("hint", "已断开并解散 Wi-Fi Direct 群组")
            }.toString()
        } finally {
            if (listenChannel == null) closeChannel(channel)
        }
    }

    /* ---------------------------- internals ---------------------------- */

    private fun buildConfig(args: JsonObject, address: MacAddress?): WifiP2pConfig? {
        val ssid = args.strArg("ssid")
        val passphrase = args.strArg("passphrase")
        val frequency = args.intArg("frequency")
        if (Build.VERSION.SDK_INT >= 29 && (ssid != null || passphrase != null || frequency != null)) {
            val builder = WifiP2pConfig.Builder()
            address?.let { builder.setDeviceAddress(it) }
            ssid?.let { builder.setNetworkName(it) }
            passphrase?.let { builder.setPassphrase(it) }
            frequency?.let {
                if (it in 2412..2484) builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                else if (it in 5160..5885) builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            }
            return builder.build()
        }
        if (address == null) return null
        @Suppress("DEPRECATION")
        return WifiP2pConfig().apply { deviceAddress = address.toString() }
    }

    private fun normalizeMac(raw: String): String =
        raw.trim().uppercase(Locale.US).replace('-', ':')

    private fun permissionError(): String =
        "ERROR: 未授予精确定位权限。Android 要求 Wi-Fi Direct 发现/建组必须持有位置权限，且定位开关需打开"

    private suspend fun ensureWifiPermission(): Boolean? {
        val granted = permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        return if (granted) true else null
    }

    private suspend fun awaitAction(
        m: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long,
        call: (WifiP2pManager.ActionListener) -> Unit,
    ): Pair<Boolean, Int>? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            try {
                call(object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(true to 0) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false to reason) }
                })
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false to -1)
            }
        }
    }

    private suspend fun requestPeers(m: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pDeviceList? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    m.requestPeers(channel) { peers -> if (cont.isActive) cont.resume(peers) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun requestGroupInfo(m: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    m.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun requestConnectionInfo(m: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pInfo? =
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                try {
                    m.requestConnectionInfo(channel) { info -> if (cont.isActive) cont.resume(info) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /** 轮询直到群组建立（或超时）；返回最后的群组与连接信息。 */
    private suspend fun awaitGroupFormed(
        m: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeoutMs: Long,
    ): Pair<WifiP2pGroup?, WifiP2pInfo?> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var group: WifiP2pGroup? = null
        var info: WifiP2pInfo? = null
        while (System.currentTimeMillis() < deadline) {
            group = requestGroupInfo(m, channel)
            info = requestConnectionInfo(m, channel)
            if (info?.groupFormed == true) break
            delay(500)
        }
        return group to info
    }

    private fun groupJson(
        m: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        group: WifiP2pGroup?,
        info: WifiP2pInfo?,
        action: String,
    ): JsonObject = buildJsonObject {
        put("action", action)
        put("inGroup", group != null || info?.groupFormed == true)
        if (group != null) {
            group.networkName?.let { put("ssid", it) }
            // 只有群主能读到真实口令；客户端会拿到 "*"（系统打码），原样透传并注明。
            group.passphrase?.let {
                put("passphrase", it)
                put("passphraseVisible", it != "*")
            }
            put("isGroupOwner", group.isGroupOwner)
            put("frequency", group.frequency)
            WifiP2pCodec.frequencyLabel(group.frequency)?.let { put("frequencyLabel", it) }
            group.`interface`?.let { put("interface", it) }
            put("networkId", group.networkId)
            group.owner?.let { owner ->
                putJsonObject("owner") {
                    put("name", owner.deviceName ?: "")
                    put("address", owner.deviceAddress ?: "")
                }
            }
            putJsonArray("clients") {
                group.clientList.forEach { c ->
                    add(buildJsonObject {
                        put("name", c.deviceName ?: "")
                        put("address", c.deviceAddress ?: "")
                        put("status", WifiP2pCodec.deviceStatusName(c.status))
                    })
                }
            }
        }
        if (info?.groupFormed == true) {
            put("groupFormed", true)
            put("isGroupOwner", info.isGroupOwner)
            info.groupOwnerAddress?.hostAddress?.let { put("groupOwnerIp", it) }
        }
        if (group?.isGroupOwner == true && !group.passphrase.isNullOrBlank() && group.passphrase != "*") {
            put(
                "hint",
                "本机是群主：另一台手机可直接用「普通 Wi-Fi」连接 ssid/passphrase 加入（无需 P2P）；" +
                    "连上后 http_request 到 groupOwnerIp 即可与本机通信",
            )
        } else if (info?.groupFormed == true) {
            put("hint", "已连接群组；向群主（groupOwnerIp）发起 http_request 即可互传")
        }
    }
}

/* ------------------------------ wifi_rtt ------------------------------ */

@SuppressLint("MissingPermission")
class WifiRttTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String = "wifi_rtt",
) : Tool {

    override val description: String =
        "Wi-Fi RTT 测距（IEEE 802.11mc FTM，对周边 AP 米级测距）。action=status 查硬件支持与当前可用性；" +
            "action=range 对周边 AP 测距：默认从 Wi-Fi 扫描结果中挑 802.11mc responder（按信号强度取前 limit 个），" +
            "也可用 bssid 指定某个 AP；include_non_mc=true 时把非 802.11mc AP 也加进来（仅 Android 12+/API 31+，" +
            "单边 RTT，精度差）。返回每个 AP 的距离/标准差/RSSI/成功测量次数与可靠性评级。需要精确定位权限、" +
            "定位开关打开且硬件具备 FEATURE_WIFI_RTT；不支持时如实报错，不要编造距离。"

    override val parameters = schemaOf(
        "action" to strParam("status（默认）| range"),
        "bssid" to strParam("range：只测指定 AP（格式 aa:bb:cc:dd:ee:ff，大小写均可）"),
        "limit" to intParam("range：最多测几个 AP，默认 4，范围 1..8"),
        "timeout_ms" to intParam("range：等待测距结果毫秒数，默认 8000，范围 2000..20000"),
        "include_non_mc" to boolParam("range：是否把非 802.11mc 的 AP 按单边 RTT 加入（API 31+），默认 false"),
    )

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT < 28) {
                "ERROR: Wi-Fi RTT 需要 Android 9（API 28）及以上，当前 API ${Build.VERSION.SDK_INT}"
            } else when (args.strArg("action") ?: "status") {
                "status" -> status()
                "range" -> range(args)
                else -> "ERROR: action 只能是 status / range"
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private val rttManager: WifiRttManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun locationEnabled(): Boolean = runCatching {
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled ?: false
        else (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false) ||
            (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false)
    }.getOrDefault(true)

    private suspend fun status(): String {
        val supported = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        }.getOrDefault(false)
        val rtt = rttManager
        val available = if (supported && rtt != null) {
            runCatching { rtt.isAvailable() }.getOrNull()
        } else null
        val notes = mutableListOf<String>()
        if (!supported) notes += "设备不支持 Wi-Fi RTT（缺 FEATURE_WIFI_RTT 硬件特性）"
        if (available == false) notes += "RTT 当前不可用（Wi-Fi 断开/关闭或系统暂时禁用，稍后再试）"
        return buildJsonObject {
            put("action", "status")
            put("apiLevel", Build.VERSION.SDK_INT)
            put("supported", supported)
            put("available", available)
            put("wifiEnabled", runCatching { wifiManager?.isWifiEnabled }.getOrNull())
            put("locationEnabled", locationEnabled())
            putJsonArray("notes") { notes.forEach { add(it) } }
        }.toString()
    }

    private suspend fun range(args: JsonObject): String {
        val supported = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        }.getOrDefault(false)
        if (!supported) return "ERROR: 设备不支持 Wi-Fi RTT（缺 FEATURE_WIFI_RTT 硬件特性），无法测距"
        val rtt = rttManager ?: return "ERROR: Wi-Fi RTT 服务不可用"
        val available = runCatching { rtt.isAvailable() }.getOrNull()
        if (available != true) {
            return "ERROR: Wi-Fi RTT 当前不可用（isAvailable=false）：Wi-Fi 需开启且已连接，系统可能暂时禁用了测距，稍后重试"
        }
        if (!permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
            return "ERROR: 未授予精确定位权限。Android 要求 RTT 测距必须持有位置权限"
        }
        val warnings = mutableListOf<String>()
        if (!locationEnabled()) warnings += "系统定位开关已关闭：多数机型此时拿不到扫描结果"

        val wm = wifiManager ?: return "ERROR: Wi-Fi 服务不可用"
        val limit = (args.intArg("limit") ?: 4).coerceIn(1, 8)
        val timeoutMs = (args.intArg("timeout_ms") ?: 8_000).coerceIn(2_000, 20_000)
        val includeNonMc = args.boolArg("include_non_mc") == true
        val bssid = args.strArg("bssid")?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        bssid?.let {
            if (!Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(it)) return "ERROR: bssid 格式非法：$it"
        }

        val scanResults = runCatching { wm.scanResults }.getOrElse { e ->
            return "ERROR: 读取 Wi-Fi 扫描结果失败：${e.message ?: e.javaClass.simpleName}（需要定位权限且定位开关打开）"
        }
        if (scanResults.isEmpty()) {
            return buildJsonObject {
                put("action", "range")
                put("ok", false)
                put("error", "Wi-Fi 扫描结果为空：请确认已连接 Wi-Fi、定位开关打开；可稍后重试（系统限流 30 秒左右）")
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }.toString()
        }

        val mcResults = scanResults.filter { runCatching { it.is80211mcResponder() }.getOrDefault(false) }
        val candidates: List<ScanResult> = if (bssid != null) {
            val hit = scanResults.firstOrNull { it.BSSID?.uppercase(Locale.US) == bssid }
            if (hit == null) {
                return buildJsonObject {
                    put("action", "range")
                    put("ok", false)
                    put("error", "扫描结果里没有 BSSID $bssid：请靠近该 AP 后重试，或省略 bssid 测全部可见 responder")
                    put("scannedAps", scanResults.size)
                }.toString()
            }
            listOf(hit)
        } else if (mcResults.isNotEmpty()) {
            mcResults.sortedByDescending { it.level }.take(limit)
        } else {
            emptyList()
        }

        val builder = RangingRequest.Builder()
        val mcTargets = candidates.filter { runCatching { it.is80211mcResponder() }.getOrDefault(false) }
        val nonMcTargets = candidates.filterNot { it in mcTargets }
        mcTargets.forEach { builder.addAccessPoint(it) }
        if (nonMcTargets.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 31 && includeNonMc) {
                nonMcTargets.forEach { builder.addNon80211mcCapableAccessPoint(it) }
                warnings += "包含 ${nonMcTargets.size} 个非 802.11mc AP：单边 RTT，精度明显更差（可达数十米误差）"
            } else if (nonMcTargets.isNotEmpty() && mcTargets.isEmpty()) {
                return buildJsonObject {
                    put("action", "range")
                    put("ok", false)
                    put("error", "扫描结果中没有 802.11mc responder，无法测距；" +
                        (if (Build.VERSION.SDK_INT >= 31) "可传 include_non_mc=true 用单边 RTT（精度差）" else "且系统低于 Android 12，无法测非 mc AP"))
                    put("scannedAps", scanResults.size)
                }.toString()
            }
        }
        val request = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            return "ERROR: 构造测距请求失败：${e.message}"
        }
        if (mcTargets.isEmpty() && nonMcTargets.isEmpty()) {
            return "ERROR: 没有可测距的候选 AP"
        }

        // (failureCode, results)；failureCode=0 表示成功回调
        val outcome = withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine<Pair<Int, MutableList<RangingResult>?>> { cont ->
                try {
                    rtt.startRanging(request, mainExecutor(), object : RangingResultCallback() {
                        override fun onRangingResults(results: MutableList<RangingResult>) {
                            if (cont.isActive) cont.resume(0 to results)
                        }

                        override fun onRangingFailure(code: Int) {
                            if (cont.isActive) cont.resume(code to null)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(-1 to null)
                }
            }
        } ?: return buildJsonObject {
            put("action", "range")
            put("ok", false)
            put("error", "测距超时（${timeoutMs}ms 无回调）：AP 可能不支持 FTM 或信号太弱")
            putJsonArray("warnings") { warnings.forEach { add(it) } }
        }.toString()

        if (outcome.first != 0 || outcome.second == null) {
            return buildJsonObject {
                put("action", "range")
                put("ok", false)
                put("error", "测距失败（onRangingFailure=${RttCodec.failureCodeName(outcome.first)}）：" +
                    "系统拒绝了本次请求（权限/位置开关/频率不支持）")
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }.toString()
        }
        val ranged = outcome.second ?: emptyList()

        val results = ranged.map { r ->
            buildJsonObject {
                put("mac", r.macAddress?.toString()?.uppercase(Locale.US))
                put("status", RttCodec.statusName(r.status))
                if (r.status == RttCodec.STATUS_SUCCESS) {
                    put("distanceMm", r.distanceMm)
                    put("distanceText", RttCodec.distanceText(r.distanceMm))
                    put("stdDevMm", r.distanceStdDevMm)
                    put("reliability", RttCodec.reliability(r.distanceStdDevMm))
                }
                put("rssi", r.rssi)
                putJsonObject("measurements") {
                    put("attempted", r.numAttemptedMeasurements)
                    put("successful", r.numSuccessfulMeasurements)
                }
                put("ieee80211mc", r.is80211mcMeasurement)
            }
        }
        val distances = ranged.filter { it.status == RttCodec.STATUS_SUCCESS }.map { it.distanceMm }
        return buildJsonObject {
            put("action", "range")
            put("ok", distances.isNotEmpty())
            put("count", results.size)
            putJsonArray("results") { results.forEach { add(it) } }
            putJsonArray("warnings") { warnings.forEach { add(it) } }
            if (distances.isNotEmpty()) {
                val stats = RttCodec.stats(distances)
                putJsonObject("summary") {
                    put("succeeded", stats.count)
                    stats.medianMm?.let { put("medianText", RttCodec.distanceText(it)) }
                    stats.meanMm?.let { put("meanText", RttCodec.distanceText(it)) }
                    stats.minMm?.let { put("nearestText", RttCodec.distanceText(it)) }
                    stats.maxMm?.let { put("farthestText", RttCodec.distanceText(it)) }
                    stats.stdDevMm?.let { put("stdDevText", "±${RttCodec.distanceText(it)}") }
                }
            } else {
                put("note", "没有任何 AP 返回成功测量：目标 AP 多半不支持 802.11mc FTM responder")
            }
        }.toString()
    }

    private fun mainExecutor(): Executor = ContextCompat.getMainExecutor(context)
}

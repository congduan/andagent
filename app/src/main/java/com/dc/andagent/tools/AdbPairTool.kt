package com.dc.andagent.tools

import com.dc.andagent.adb.TLS_READ_ERROR
import com.dc.andagent.adb.TlsConnectResult
import com.dc.andagent.adb.TlsNative
import kotlinx.serialization.json.JsonObject

/**
 * R100 ③: Android 11+ 无线调试「配对码配对」(SPAKE2 over Ed25519, AOSP adb pairing).
 *
 * 协议事实（逐文件对照 AOSP packages/modules/adb，见战报）：
 *  - TCP → TLS 1.3（双方自签证书、客户端校验证书回调恒放行）→ SPAKE2 → PeerInfo
 *  - SPAKE2 口令 = 6 位配对码 + TLS exporter("adb-label") 的 64 字节，绑定本次 TLS 会话
 *  - 客户端 PeerInfo 携带本机 ADB RSA 公钥；设备返回 ADB_DEVICE_GUID
 *  - 配对端口与 adb_connect 用的「无线调试主端口」是两个不同端口
 *
 * 原生实现见 app/src/main/cpp/adbpair/（AOSP pairing_auth/aes_128_gcm 源码直采 +
 * BoringSSL）。Kotlin 侧只留薄壳：参数校验 → JNI → 结果文案。
 */
object AdbPairNative : TlsNative {
    /** 非 arm64 或 JVM 单测环境没有 .so，加载失败时所有调用走降级文案，不崩溃。 */
    private val loaded: Boolean = try {
        System.loadLibrary("adbpair")
        true
    } catch (t: Throwable) {
        false
    }

    val available: Boolean get() = loaded

    private external fun nativePair(
        host: String, port: Int, code: String, adbPubKey: ByteArray, deviceName: String,
    ): String

    private external fun nativeSelfTest(): String

    // ---- R103 A：TLS 1.3 传输层（cpp/adbpair/tls_client.cpp）----
    private external fun nativeTlsConnect(
        host: String, port: Int, timeoutMs: Int, pinned: Array<String>,
    ): String

    private external fun nativeTlsRead(handle: Long, buf: ByteArray): Int
    private external fun nativeTlsWrite(handle: Long, data: ByteArray): Int
    private external fun nativeTlsSetReadTimeout(handle: Long, ms: Int)
    private external fun nativeTlsClose(handle: Long)

    /** 宽 JNI 边界：任何 UnsatisfiedLinkError / 原生异常都收敛成字符串。 */
    fun pair(host: String, port: Int, code: String, adbPubKey: ByteArray, deviceName: String): String =
        if (!loaded) "ERROR\nREASON=UNAVAILABLE\n"
        else runCatching { nativePair(host, port, code, adbPubKey, deviceName) }
            .getOrDefault("ERROR\nREASON=JNI_EXCEPTION\n")

    /** JNI 边界 smoke：真机 arm64 应返回 PASS，其它环境返回降级串。 */
    fun selfTest(): String =
        if (!loaded) "UNSUPPORTED_ABI_OR_JVM"
        else runCatching { nativeSelfTest() }.getOrDefault("JNI_EXCEPTION")

    /**
     * 纯函数：解析 nativeTlsConnect 原始返回串（"OK\nHANDLE=..\nFP=..\n" 或
     * "ERROR\nREASON=..\nFP=..\n"），便于 JVM 单测脱离 .so 覆盖。
     */
    fun parseTlsConnect(raw: String): TlsConnectResult {
        val fields = HashMap<String, String>()
        for (line in raw.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) fields[line.substring(0, i)] = line.substring(i + 1)
        }
        return if (raw.startsWith("OK")) {
            TlsConnectResult(
                handle = fields["HANDLE"]?.toLongOrNull(),
                fingerprint = fields["FP"]?.takeIf { it.isNotBlank() },
            )
        } else {
            TlsConnectResult(reason = fields["REASON"] ?: "UNKNOWN")
        }
    }

    override fun connect(host: String, port: Int, timeoutMs: Int, pinned: List<String>): TlsConnectResult =
        if (!loaded) TlsConnectResult(reason = "UNAVAILABLE")
        else runCatching { parseTlsConnect(nativeTlsConnect(host, port, timeoutMs, pinned.toTypedArray())) }
            .getOrDefault(TlsConnectResult(reason = "JNI_EXCEPTION"))

    override fun read(handle: Long, buf: ByteArray): Int =
        if (!loaded) TLS_READ_ERROR
        else runCatching { nativeTlsRead(handle, buf) }.getOrDefault(TLS_READ_ERROR)

    override fun write(handle: Long, data: ByteArray): Int =
        if (!loaded) TLS_READ_ERROR
        else runCatching { nativeTlsWrite(handle, data) }.getOrDefault(TLS_READ_ERROR)

    override fun setReadTimeout(handle: Long, ms: Int) {
        if (loaded) runCatching { nativeTlsSetReadTimeout(handle, ms) }
    }

    override fun close(handle: Long) {
        if (loaded) runCatching { nativeTlsClose(handle) }
    }
}

object AdbPairTool {
    const val NAME = "adb_pair"
    const val DEFAULT_DEVICE_NAME = "AndAgent"

    val description =
        "Android 11+ 无线调试「使用配对码配对设备」：用手机「无线调试 → 使用配对码配对设备」页显示的 IP、配对端口、6 位配对码完成 SPAKE2 配对。host / pairing_port / pairing_code 必填，device_name 可选（默认 AndAgent）。配对成功后设备记住本机 ADB 公钥；再用 adb_connect(host, adb_port) 直连——注意 adb_port 是「无线调试」页的主端口，与本次配对端口不同。配对码错误、超时、TLS 失败分别明确区分。仅 arm64-v8a 真机可用（原生 BoringSSL 预编译仅此 ABI）。"

    val parameters = schemaOf(
        "host" to strParam("目标手机 IP 或主机名"),
        "pairing_port" to intParam("手机「使用配对码配对设备」弹窗里的配对端口（不是无线调试主端口）"),
        "pairing_code" to strParam("手机显示的 6 位配对码"),
        "device_name" to strParam("本机名称，默认 AndAgent（仅用于结果展示，AOSP 协议无此字段）"),
        required = listOf("host", "pairing_port", "pairing_code"),
    )

    /** 纯函数：边界校验，便于 JVM 单测覆盖。 */
    fun validate(host: String?, port: Int?, code: String?): String? {
        if (host.isNullOrBlank()) return "ERROR: 缺少 host"
        if (host.length > 253) return "ERROR: host 过长"
        if (AdbToolRules.validateHost(host) != null) return "ERROR: ${AdbToolRules.validateHost(host)}"
        if (port == null || !AdbToolRules.validPort(port)) return "ERROR: pairing_port 必须在 1..65535"
        if (code.isNullOrEmpty()) return "ERROR: 缺少 pairing_code"
        if (code.length != 6 || !code.all { it.isDigit() }) return "ERROR: pairing_code 必须是 6 位数字"
        return null
    }

    /** 纯函数：解析 JNI 原始结果的 key=value 行（OK / ERROR 通用）。 */
    fun fields(raw: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (line in raw.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    /** 纯函数：从 nativePair 的 OK 结果取设备证书指纹；缺失/非法返回 null。 */
    fun pairingFingerprint(raw: String): String? =
        fields(raw)["CERT_FP"]?.takeIf { AdbToolRules.isValidFingerprint(it) }

    /** 纯函数：把 JNI 原始结果翻译成用户文案（含 adb_connect 引导）。 */
    fun format(raw: String, host: String, pairingPort: Int, deviceName: String): String {
        val fields = fields(raw)
        return when {
            raw.startsWith("OK") -> buildString {
                append("✅ 配对成功：目标设备 GUID=${fields["GUID"].orEmpty()}，本机名称=$deviceName。")
                appendLine()
                append("设备已记住本机 ADB 公钥，并已锁定目标证书指纹，下一步用 adb_connect(host=\"$host\", port=<adb_port>) 直连：")
                appendLine()
                append("adb_port 是手机「无线调试」页显示的主端口（通常 5555 或随机端口），与本次配对端口 $pairingPort 不同，请勿混用。")
                appendLine()
                append("本机已记录该 host 的配对指纹：adb_connect 会自动尝试 TLS 1.3 传输（配对过的 Android 11+ 主端口要求 TLS）；")
                append("若连到的是传统明文 adbd，可显式传 tls=false。TLS 直连本环境未做真机联调（见战报清单）。")
            }
            else -> when (fields["REASON"]) {
                "WRONG_CODE" ->
                    "❌ 配对失败：配对码错误或连接被中断（SPAKE2 派生密钥不一致，PeerInfo 的 GCM 校验失败）。\n" +
                        "配对码每次都会刷新，请在手机「无线调试 → 使用配对码配对设备」重新查看 6 位配对码与配对端口后重试。"
                "NETWORK" ->
                    "❌ 配对失败：无法连接 $host:$pairingPort（网络不可达 / 端口错误 / 15s 超时）。\n" +
                        "请确认手机与本机在同一 Wi-Fi、无线调试已开启、填的是「配对端口」而非主端口。"
                "TLS", "TLS_EXPORT" ->
                    "❌ 配对失败：TLS 1.3 握手或 exporter 密钥导出失败。\n" +
                        "请确认目标是 Android 11+ 的「使用配对码配对设备」端口，且未与其它主机同时配对。"
                "PROTOCOL", "IO", "ENCRYPT", "SPAKE2", "CERT" ->
                    "❌ 配对失败：协议交互异常（${fields["REASON"]}）。请重试；若持续失败，确认手机系统版本与配对弹窗未超时。"
                "UNSUPPORTED_ABI" ->
                    "❌ 配对失败：本机 CPU 架构非 arm64-v8a，未内置原生配对库（BoringSSL 预编译仅 arm64）。"
                "UNAVAILABLE" ->
                    "❌ 配对失败：原生配对库未加载。adb_pair 需在 arm64-v8a 真机上运行（JVM 单测环境无 .so）。"
                "INVALID_ARGS" -> "❌ 配对失败：参数非法。"
                else -> "❌ 配对失败：${raw.trim().ifEmpty { "未知错误" }}"
            }
        }
    }

    /**
     * [onPaired]：配对成功时回调（host, 设备证书指纹 hex 或 null），由 AdbTool
     * 接到 AdbSessionHub.rememberPairing 写入 KV。默认空实现，保持既有调用方签名兼容。
     */
    fun execute(
        args: JsonObject,
        adbPubKey: () -> ByteArray,
        onPaired: (host: String, fingerprint: String?) -> Unit = { _, _ -> },
    ): String {
        val host = args.strArg("host")
        val port = args.intArg("pairing_port")
        val code = args.strArg("pairing_code")
        val deviceName = args.strArg("device_name")?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_NAME
        validate(host, port, code)?.let { return it }

        val pk = runCatching { adbPubKey() }.getOrElse {
            return "ERROR: 读取本机 ADB 公钥失败：${it.message}"
        }
        val raw = AdbPairNative.pair(host!!, port!!, code!!, pk, deviceName)
        if (raw.startsWith("OK")) onPaired(host, pairingFingerprint(raw))
        return format(raw, host, port, deviceName)
    }
}

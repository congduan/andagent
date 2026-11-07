package com.dc.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * R100 ② / R103 A：ADB 目标元数据（纯 JVM，可脱离 Android 单测）。key 固定 "host:port"。
 * [tls] 标记该目标主端口需 TLS 1.3 传输；[fingerprint] 是配对时记录的设备证书
 * SHA-256 hex（64 位小写），连接期由原生层做 pin 校验。两者缺省值保证旧 KV
 * 记录（只有 host/port）向后兼容。
 */
data class AdbTarget(
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    val fingerprint: String? = null,
) {
    val key: String get() = "$host:$port"
}

/** 已保存目标的注册表（纯 JVM，@Synchronized）。跨工具调用与 app 重启由 KV 持久化恢复。 */
class AdbSessionRegistry {
    private val entries = LinkedHashMap<String, AdbTarget>()

    @Synchronized fun add(target: AdbTarget) { entries[target.key] = target }

    @Synchronized fun remove(key: String): AdbTarget? = entries.remove(key)

    /** target 参数可为 "host:port"、"host" 或精确 key。 */
    @Synchronized fun resolve(ref: String): AdbTarget? =
        entries[ref] ?: entries.values.firstOrNull { it.host.equals(ref, ignoreCase = true) }

    @Synchronized fun list(): List<AdbTarget> = entries.values.toList()

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun contains(key: String): Boolean = entries.containsKey(key)

    @Synchronized fun replaceAll(targets: List<AdbTarget>) {
        entries.clear()
        targets.forEach { entries[it.key] = it }
    }
}

/** R100 ②：ADB 工具纯规则——破坏性命令确认闸、大小上限、参数校验、KV 序列化。 */
object AdbToolRules {
    const val MAX_TARGETS = 16
    const val DEFAULT_PORT = 5555

    /** 复用现成 guard：命中任一破坏性命令规则 → 返回危险摘要，否则 null。 */
    fun dangerOf(cmd: String): String? = SshCommandGuard.dangerOf(cmd)

    /**
     * 破坏性命令确认闸（对齐 ssh_exec）：命中 danger 且未 confirm → 返回 CONFIRM_REQUIRED
     * 文案；未命中或已 confirm → null（放行）。
     */
    fun confirmGate(cmd: String, confirm: Boolean): String? {
        val danger = dangerOf(cmd) ?: return null
        if (confirm) return null
        return buildString {
            appendLine("CONFIRM_REQUIRED: 检测到破坏性命令「$danger」。")
            appendLine("命令: ${cmd.take(300)}")
            append("请先向用户说明该命令的影响与目标手机，用户确认后以 confirm=true 重试。")
        }
    }

    /** 对齐 ssh_download 的 max_mb 语义（默认 512，钳制 1..4096）。 */
    fun maxMb(value: Int?): Int = SshTransferRules.maxMb(value)

    fun validateHost(host: String): String? = when {
        host.isBlank() || host.length > 253 || !host.matches(Regex("[^\\s/\\\\]+")) -> "host 格式无效"
        else -> null
    }

    fun validPort(port: Int): Boolean = port in 1..65535

    /** Android 远程路径：绝对路径、无 NUL/换行、不含 ".." 段。 */
    fun validateRemotePath(path: String): String? = when {
        path.isBlank() || path.contains('\u0000') || path.contains('\n') -> "远程路径无效"
        path.split('/').any { it == ".." } -> "远程路径不能包含 .."
        !path.startsWith("/") -> "远程路径必须以 / 开头（绝对路径）"
        else -> null
    }

    /** 复用 ssh_download 的文件名白名单（防路径穿越/控制字符）。 */
    fun safeName(name: String): String = SshTransferRules.safeName(name)

    fun encodeTargets(targets: List<AdbTarget>): JsonArray = JsonArray(
        targets.map { buildJsonObject {
            put("host", it.host)
            put("port", it.port)
            // 仅写非缺省字段，保持旧 KV 记录字节稳定、AdbTarget 相等性不变。
            if (it.tls) put("tls", true)
            if (!it.fingerprint.isNullOrBlank()) put("fingerprint", it.fingerprint)
        } },
    )

    fun decodeTargets(el: JsonElement?): List<AdbTarget> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val host = o["host"]?.jsonPrimitive?.contentOrNull
            val port = o["port"]?.jsonPrimitive?.intOrNull
            if (host.isNullOrBlank() || port == null || port !in 1..65535) null
            else {
                val tls = o["tls"]?.jsonPrimitive?.contentOrNull?.equals("true", true) == true
                val fp = o["fingerprint"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { isValidFingerprint(it) }
                AdbTarget(host, port, tls, fp)
            }
        }
    }

    /** 64 位十六进制（大小写均可）；非法指纹不参与 pin，避免把脏值喂给原生层。 */
    fun isValidFingerprint(fp: String?): Boolean =
        fp != null && fp.length == 64 &&
            fp.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * R103 A：配对注册表（host -> 设备证书指纹，null 表示已配对但未取到指纹）。
     * 用 JsonObject 存，值为 "" 时解码回 null，但 key 仍在 → isPaired 仍为真，
     * 可自动尝试 TLS（原生层 pinned 空列表 = 信任自签、仅 TLS 1.3）。
     */
    fun encodePairings(pairings: Map<String, String?>): JsonObject = buildJsonObject {
        for ((host, fp) in pairings) put(host, fp ?: "")
    }

    fun decodePairings(el: JsonElement?): Map<String, String?> {
        val o = el as? JsonObject ?: return emptyMap()
        return o.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull?.takeIf { isValidFingerprint(it) }
        }
    }
}

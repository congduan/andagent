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
 * R100 ②：ADB 目标元数据（纯 JVM，可脱离 Android 单测）。key 固定 "host:port"。
 */
data class AdbTarget(val host: String, val port: Int) {
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
        targets.map { buildJsonObject { put("host", it.host); put("port", it.port) } },
    )

    fun decodeTargets(el: JsonElement?): List<AdbTarget> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val host = o["host"]?.jsonPrimitive?.contentOrNull
            val port = o["port"]?.jsonPrimitive?.intOrNull
            if (host.isNullOrBlank() || port == null || port !in 1..65535) null
            else AdbTarget(host, port)
        }
    }
}

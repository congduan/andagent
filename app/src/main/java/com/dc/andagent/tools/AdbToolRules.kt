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

/**
 * 已保存目标的注册表（纯 JVM，@Synchronized）。跨工具调用与 app 重启由 KV 持久化恢复。
 *
 * R121f：key 仍是 "host:port"，但语义改为 **host 级唯一**——同一台目标机只保留一条
 * 条目。换端口重连（无线调试主端口每次进设置页都会变）时由 [mergeHost] 把条目迁移到
 * 新 host:port 并移除同 host 的旧端口条目，避免 adbd 单客户端限制下多连接互顶。
 */
class AdbSessionRegistry {
    private val entries = LinkedHashMap<String, AdbTarget>()

    @Synchronized fun add(target: AdbTarget) { entries[target.key] = target }

    @Synchronized fun remove(key: String): AdbTarget? = entries.remove(key)

    /**
     * R121f 核心：同一 host 只保留 [target] 这一条（迁移到新 host:port）。返回被移除的
     * 同 host 旧 key 列表，供 Hub 关闭对应 live 连接、诊断日志使用。
     * 配对指纹由 Hub 的 host 级 pairings 表（不由本表）保存，故迁移不丢凭据；connect()
     * 每次据 isPaired/fingerprintFor(host) 重算 tls/指纹写入本条目。
     */
    @Synchronized fun mergeHost(target: AdbTarget): List<String> {
        val stale = entries.keys.filter { k ->
            k != target.key && entries[k]!!.host.equals(target.host, ignoreCase = true)
        }
        stale.forEach { entries.remove(it) }
        entries[target.key] = target
        return stale
    }

    /** R121f：按 host 移除该目标机全部端口条目（断开时一次清干净），返回被移除的目标。 */
    @Synchronized fun removeHost(host: String): List<AdbTarget> {
        val victims = entries.values.filter { it.host.equals(host, ignoreCase = true) }
        victims.forEach { entries.remove(it.key) }
        return victims
    }

    /** R121f 自愈：返回含多条目（>1）的 host 分组（老用户 KV 可能残留同 host 多端口）。 */
    @Synchronized fun duplicateHostGroups(): List<List<AdbTarget>> =
        entries.values.groupBy { it.host.lowercase() }.values.filter { it.size > 1 }.toList()

    /**
     * target 参数可为 "host:port"、"host"；并兼容端口迁移后的【旧 host:port 引用】——
     * 形如 "host:port" 且端口为纯数字时，按 host 前缀收敛到该 host 当前唯一条目，
     * 保证老引用不断链（R121f）。
     */
    @Synchronized fun resolve(ref: String): AdbTarget? {
        entries[ref]?.let { return it }
        entries.values.firstOrNull { it.host.equals(ref, ignoreCase = true) }?.let { return it }
        val hostPart = ref.substringBeforeLast(':')
        val portPart = ref.substringAfterLast(':')
        if (hostPart != ref && portPart.isNotEmpty() && portPart.all { it in '0'..'9' }) {
            return entries.values.firstOrNull { it.host.equals(hostPart, ignoreCase = true) }
        }
        return null
    }

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
     * R103 必修 1：TLS 目标必须锁定非空指纹。空指纹会让原生层 pinned 为空
     * （= 信任任意自签证书），故 tls=true 而指纹空白时必须由 AdbSessionHub 拒绝，
     * 原生层 VerifyCb 另做 NO_PIN 纵深防御。纯函数便于 JVM 单测。
     */
    fun tlsPinMissing(tls: Boolean, fingerprint: String?): Boolean =
        tls && fingerprint.isNullOrBlank()

    /**
     * R103 A：配对注册表（host -> 设备证书指纹，null 表示已配对但未取到指纹）。
     * 用 JsonObject 存，值为 "" 时解码回 null，但 key 仍在 → isPaired 仍为真。
     * 注意：空指纹不再走 TLS（Kotlin AdbSessionHub 守门 + 原生 VerifyCb 拒绝），
     * 需重新 adb_pair 锁定指纹或显式 tls=false。
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

    /**
     * R121f：adbd 无线调试同一时间只服务一个 TLS 客户端。给定同 host 的 live key 集合，
     * 返回应被 close 的旧端口 key；keeperKey=null 时表示该 host 全部 live 都要清（建连前清场）。
     * 纯函数，便于 JVM 单测覆盖「client 重连清理旧 live」的决策逻辑。
     */
    fun staleLiveKeys(liveKeys: Collection<String>, host: String, keeperKey: String?): List<String> =
        liveKeys.filter { k ->
            (keeperKey == null || !k.equals(keeperKey, ignoreCase = true)) &&
                k.startsWith("$host:", ignoreCase = true)
        }

    /** R121f：adb_devices 展示「已连接时长」的格式化（纯函数）。 */
    fun formatDuration(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return when {
            s < 60L -> "${s}s"
            s < 3600L -> "${s / 60L}m${s % 60L}s"
            else -> "${s / 3600L}h${(s % 3600L) / 60L}m"
        }
    }

    /** R121f：识别 AdbClient.openStream 的 ACK 8s 快速失败文案（连接已被 adbd 抛弃的信号）。 */
    fun isShellAckStall(result: String): Boolean =
        result.contains("8 秒内未收到目标确认") || result.contains("ADB shell 无响应")
}

/**
 * R122：`adb_input` 的纯 shell 命令构造（纯 JVM，脱离 Android 可单测）。
 *
 * 协议事实（AOSP frameworks/base/cmds/input/src/com/android/commands/input/Input.java）：
 *  - `input text` 的参数在设备侧 shell 里会被按空格切分，空格必须编码为 `%s`（源码
 *    sendText 用 `%` 作转义符，遇到 `s` 还原成空格）；
 *  - `input text` 走 KeyCharacterMap.VIRTUAL_KEYBOARD，非 ASCII（中文/emoji）注入不可靠，
 *    故明确拒绝，不假装成功；
 *  - `input tap/swipe/draganddrop/keyevent` 的坐标/时长/keycode 都是纯数字，校验后直接透传。
 *
 * text 统一用单引号包裹并把内部单引号按 `'\''` 结束重启转义（对齐
 * AdbScrcpyToolSpec.buildServerPushCommands 的单引号安全注释），shell 元字符不会被解释。
 */
object AdbInputCodec {
    const val DEFAULT_DURATION_MS = 300
    private val ACTIONS = setOf("tap", "swipe", "key", "text", "drag")

    /**
     * Codex review 必修 3：`input` 命令本身无 stdout，openStream 在非零退出时只在尾部附
     * `[exit code: n]`（AdbClient.kt:280），在「ACK 后 EOF」时可能返回**空串**——只看输出
     * 内容会把失败当成功。解法与 AdbChunkPull.shellDone 同构：命令追加 `&& echo MARK`
     * 完成标记（真实跑完才输出），判定 = 无失败形态 + 带标记。
     */
    const val DONE_MARK = "AA_INPUT_CMD_DONE"

    fun outputOk(output: String): Boolean =
        !output.trimStart().startsWith("ERROR") &&
            !Regex("""\[exit code: (\d+)]\s*$""").containsMatchIn(output.takeLast(120)) &&
            output.contains(DONE_MARK)

    data class Built(val command: String?, val error: String?)

    /** 用单引号包裹任意文本：内部单引号以 `'\''` 结束重启，杜绝 shell 注入。 */
    fun shellSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun build(
        action: String?,
        x: Int?, y: Int?, x2: Int?, y2: Int?,
        durationMs: Int?, keycode: Int?, text: String?,
    ): Built {
        val a = action?.trim()?.lowercase()
        if (a == null || a !in ACTIONS) return Built(null, "ERROR: action 仅支持 tap|swipe|key|text|drag")
        return when (a) {
            "tap" -> {
                if (x == null || y == null) return Built(null, "ERROR: action=tap 需要 x 与 y")
                Built("input tap $x $y", null)
            }
            "swipe", "drag" -> {
                if (x == null || y == null || x2 == null || y2 == null) {
                    return Built(null, "ERROR: action=$a 需要 x/y/x2/y2")
                }
                val d = durationMs ?: DEFAULT_DURATION_MS
                if (d < 0) return Built(null, "ERROR: duration_ms 必须 >= 0")
                val sub = if (a == "drag") "draganddrop" else "swipe"
                Built("input $sub $x $y $x2 $y2 $d", null)
            }
            "key" -> {
                if (keycode == null) return Built(null, "ERROR: action=key 需要 keycode")
                if (keycode !in 0..65535) return Built(null, "ERROR: keycode 必须在 0..65535")
                Built("input keyevent $keycode", null)
            }
            "text" -> {
                if (text.isNullOrEmpty()) return Built(null, "ERROR: action=text 需要 text")
                if (text.length > 200) return Built(null, "ERROR: text 过长（上限 200 字符）")
                if (text.any { it.code !in 0x20..0x7E }) {
                    return Built(null, "ERROR: text 仅支持可打印 ASCII（AOSP input text 对中文/emoji 等非 ASCII 不可靠，请改用剪贴板）")
                }
                if (text.contains('%')) {
                    return Built(null, "ERROR: text 不能含 %（AOSP input text 以 % 作转义符，空格会自动转成 %s）；如需空格直接写空格")
                }
                Built("input text " + shellSingleQuote(text.replace(" ", "%s")), null)
            }
            else -> Built(null, "ERROR: 未知 action")
        }
    }
}

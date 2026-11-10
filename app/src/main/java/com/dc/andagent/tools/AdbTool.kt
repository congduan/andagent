package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.dc.andagent.adb.AdbAuth
import com.dc.andagent.adb.AdbClient
import com.dc.andagent.adb.AdbConnectOptions
import com.dc.andagent.adb.AdbShellPull
import com.dc.andagent.adb.AdbTransport
import com.dc.andagent.adb.AndroidKeystoreKeyWrapper
import com.dc.andagent.adb.TcpTransport
import com.dc.andagent.adb.TlsTransport
import com.dc.andagent.adb.adbKeyFile
import com.dc.andagent.shell.devtoolsScreenshotJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/** 注册进 MainActivity 工具表的 ADB 工具族（R100 ② 7 个 + R103 scrcpy 2 个 + 配对 1 个 + R122 adb_input）。 */
val ADB_TOOLS = listOf(
    "adb_connect", "adb_devices", "adb_disconnect",
    "adb_shell", "adb_screencap", "adb_pull", "adb_push",
    "adb_input",
    AdbPairTool.NAME,
    AdbQrPairTool.NAME,
) + AdbScrcpyToolSpec.NAMES

/**
 * R100 ②：ADB 会话中枢（进程内单例）。7 个 AdbTool 实例共享同一份状态：
 *  - [registry] 已保存目标（纯 JVM 注册表，由 KV 持久化，app 重启后恢复）
 *  - [live] 当前活跃的 [AdbClient]（socket 不可跨进程/重启存活）
 *  - [keyPair] 本机 RSA 密钥（filesDir/adb/adbkey，AndroidKeystore 加密），授权一次后长期复用
 */
object AdbSessionHub {
    private const val TAG = "AdbSessionHub"
    private const val NS = "adb"
    private const val KEY = "targets"
    /** R103 A：配对时记录的设备证书指纹（host -> SHA-256 hex，null = 已配对但未取到）。仅记录用途。 */
    private const val PAIRINGS_KEY = "pairings"
    const val RECONNECT_TIMEOUT_MS = 3000

    /**
     * R124 真机 A/B 开关（只改这几行，协议层不动）：
     *  - [TLS_PEER_CNXN_FIRST]=true：TLS 后【不】重发我方 CNXN，只等 adbd 主动 CNXN
     *    （三方对照一致的正确时序）；置 false 复现 R121f 旧时序做对照。
     *  - [TLS_RAW_SHELL_SERVICE]=true：用 `shell,v2,raw:`（对齐 iadb-ios）。
     *  - banner features：改 AdbFeatures.ACTIVE_FEATURES（MINIMAL / WIRELESS / PC_FULL）。
     */
    private const val TLS_PEER_CNXN_FIRST = true
    private const val TLS_RAW_SHELL_SERVICE = false

    private fun connectOptions(tls: Boolean) = AdbConnectOptions(
        peerCnxnFirst = tls && TLS_PEER_CNXN_FIRST,
        rawShellService = tls && TLS_RAW_SHELL_SERVICE,
    )

    val registry = AdbSessionRegistry()

    /** 资源中心用：目标是否有存活连接（R133c 面板状态标注）。 */
    fun isLive(key: String): Boolean = synchronized(lock) { live[key]?.isAlive() == true }
    private val live = LinkedHashMap<String, AdbClient>()
    /** R121f：每条 live 连接的建立时刻（ms），供 adb_devices 展示连接时长。 */
    private val liveSince = LinkedHashMap<String, Long>()
    private val pairings = LinkedHashMap<String, String?>()
    private val lock = Any()
    /** R127：per-target pull 串行闸（见 [withPullSerial]），按 host 归一。 */
    private val pullGates = ConcurrentHashMap<String, Any>()
    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        if (ctx == null) synchronized(lock) {
            if (ctx == null) {
                ctx = context.applicationContext
                loadPersisted()
            }
        }
    }

    private fun kv(): KvStore = KvStore(ctx!!)

    private fun loadPersisted() {
        val el = runCatching { kv().get(NS, KEY) }.getOrNull()
        registry.replaceAll(AdbToolRules.decodeTargets(el))
        pairings.clear()
        pairings.putAll(AdbToolRules.decodePairings(runCatching { kv().get(NS, PAIRINGS_KEY) }.getOrNull()))
    }

    private fun persist() {
        runCatching { kv().set(NS, KEY, AdbToolRules.encodeTargets(registry.list())) }
    }

    private fun persistPairings() {
        runCatching { kv().set(NS, PAIRINGS_KEY, AdbToolRules.encodePairings(pairings)) }
    }

    private val keyPair: KeyPair by lazy {
        val c = ctx!!
        val file = adbKeyFile(c)
        val wrapper = AndroidKeystoreKeyWrapper()
        if (file.isFile) {
            runCatching { AdbAuth.load(file, wrapper) }.getOrElse { createKey(file, wrapper) }
        } else createKey(file, wrapper)
    }

    private fun createKey(file: File, wrapper: AndroidKeystoreKeyWrapper): KeyPair {
        val kp = AdbAuth.generateKeyPair()
        AdbAuth.save(kp, file, wrapper)
        return kp
    }

    /** 记录 live 连接与其建立时刻（R121f：adb_devices 连接时长）。 */
    private fun putLive(key: String, c: AdbClient) {
        live[key] = c
        liveSince[key] = System.currentTimeMillis()
    }

    /** 摘除 live 连接（同时清掉时长记录），返回被摘除的连接供调用方 close。 */
    private fun dropLive(key: String): AdbClient? {
        liveSince.remove(key)
        return live.remove(key)
    }

    /**
     * R121f：adbd 单客户端——关闭并移除同 host 除 [keepKey]（null=全清）外的所有 live 连接。
     * 换端口重连 / 配对成功 / 自动重连前调用，避免历史端口残留连接互顶。返回被关闭的 key。
     */
    private fun closeOtherLive(host: String, keepKey: String?): List<String> {
        val stale = AdbToolRules.staleLiveKeys(live.keys, host, keepKey)
        stale.forEach { k -> runCatching { dropLive(k)?.close() } }
        return stale
    }

    /**
     * 按目标元数据构造传输：TLS 目标走 TlsTransport（JNI 桥），否则明文 TcpTransport。
     * TLS 分支必须带上本机 ADB 私钥（PKCS#8 DER）——adbd 在握手期要客户端证书。
     */
    private fun newClientFor(t: AdbTarget): AdbClient =
        AdbClient(
            keyPair,
            if (t.tls) {
                TlsTransport(listOfNotNull(t.fingerprint), AdbPairNative, keyPair.private.encoded)
            } else TcpTransport(),
            options = connectOptions(t.tls),
        )

    /**
     * R103 A：配对成功后由 AdbPairTool 回调，把 host + 设备证书指纹写入 KV。
     * 指纹只作记录（adbd 每次连接的证书都不同，无法当身份绑定，AOSP 客户端也
     * 直接 "Allow any server certificate"）；真正的凭据是本机 ADB 私钥。
     */
    fun rememberPairing(host: String, fingerprint: String?) {
        synchronized(lock) {
            pairings[host] = fingerprint
            persistPairings()
            // R121f：配对成功=目标机在线，历史端口必然失效——同 host 只保留一条占位条目
            // （登记过的旧端口条目合并掉），并关闭该 host 全部 live（含孤儿连接），
            // 避免 adb_devices 多条目 + 旧连接占 adbd 名额。后续 adb_connect 会把占位
            // 条目迁移到新主端口。
            val sameHost = registry.list().filter { it.host.equals(host, ignoreCase = true) }
            if (sameHost.isNotEmpty()) {
                val keeper = sameHost.last().copy(tls = true, fingerprint = fingerprint)
                registry.mergeHost(keeper)
            }
            closeOtherLive(host, null)
            persist()
        }
    }

    /** 该 host 是否曾配对成功（决定 adb_connect 是否自动尝试 TLS）。 */
    fun isPaired(host: String): Boolean = synchronized(lock) { pairings.containsKey(host) }

    /** 该 host 访问时抓到的设备证书指纹（仅记录；adbd 每次连接都换证书，不参与校验）。 */
    fun fingerprintFor(host: String): String? = synchronized(lock) { pairings[host] }

    /** 解析目标引用，供 scrcpy 等独立会话取 host/port。 */
    fun target(ref: String): AdbTarget? = synchronized(lock) { registry.resolve(ref) }

    /** 连接并登记目标；返回 AdbClient.connect 的原始结果串。tls=true 走 TLS 1.3 传输。 */
    fun connect(host: String, port: Int, tls: Boolean = false, pinnedFp: String? = null): String =
        synchronized(lock) {
            val key = "$host:$port"
            // 同 host 已有条目时属于「迁移到新端口」，不占用新的目标名额。
            if (registry.size() >= AdbToolRules.MAX_TARGETS && !registry.contains(key) &&
                registry.list().none { it.host.equals(host, ignoreCase = true) }) {
                return "ERROR: 已保存目标数达上限 ${AdbToolRules.MAX_TARGETS}，请先 adb_disconnect"
            }
            // R121f：adbd 单客户端——建新连接前先关掉同 host 全部旧 live（历史端口残留），
            // 释放 adbd 名额，否则新连接握手时旧连接被 kick，OPEN 已发却永远等不到 OKAY。
            closeOtherLive(host, null)
            val transport: AdbTransport =
                if (tls) {
                    TlsTransport(listOfNotNull(pinnedFp), AdbPairNative, keyPair.private.encoded)
                } else TcpTransport()
            val c = AdbClient(keyPair, transport, options = connectOptions(tls))
            val r0 = c.connect(host, port)
            // R124 诊断：把目标机 CNXN banner 全文（features 权威数据）随连接结果留痕。
            val banner = c.peerBanner()
            val r = if (r0.startsWith("OK") && banner.isNotEmpty()) "$r0\n对端 CNXN banner：$banner" else r0
            if (r.startsWith("OK")) putLive(key, c) else c.close()
            // OK / AUTH_REQUIRED 都登记（便于 adb_devices 看到、重试）；纯网络 ERROR 不登记。
            if (r.startsWith("OK") || r.startsWith("AUTH_REQUIRED")) {
                // host 级合并：条目迁移到新 host:port，移除同 host 旧端口条目，
                // 并清掉旧 key 上可能残留的 live（大小写不一致时的兜底）。
                val stale = registry.mergeHost(AdbTarget(host, port, tls, pinnedFp))
                stale.forEach { k -> runCatching { dropLive(k)?.close() } }
                closeOtherLive(host, key)
                persist()
            }
            r
        }

    fun disconnect(host: String): String = synchronized(lock) {
        val t = registry.resolve(host) ?: return "ERROR: 目标不存在：$host（用 adb_devices 查看）"
        // R121f：按 host 一次清干净——移除该目标机全部端口条目（含老 KV 残留），
        // 并关闭该 host 全部 live（含 registry 里已不存在的孤儿旧端口连接）。
        val victims = registry.removeHost(t.host)
        closeOtherLive(t.host, null)
        persist()
        "已断开 ADB 目标 ${t.host}（清理 ${victims.size} 个端口条目）"
    }

    /** Snapshot only connected targets; never reconnect while listing resources. */
    fun liveTargets(): List<AdbTarget> = synchronized(lock) {
        registry.list().filter { live[it.key]?.isAlive() == true }
    }

    /** R100 ③：配对时把现有 ADB RSA 公钥（adbkey.pub 格式）交给原生层放进 PeerInfo。 */
    fun adbPublicKey(): ByteArray = AdbAuth.encodePublicKey(keyPair.public)

    /** 操作工具取用：必要时（重连/app 重启后 live 空）自动连接（沿用目标的传输类型）。 */
    fun client(ref: String): AdbClient? = synchronized(lock) {
        val t = registry.resolve(ref) ?: return null
        // R121f：adbd 单客户端——同 host 只保留 t.key 这一条 live，重连前先关闭其它旧端口连接。
        closeOtherLive(t.host, t.key)
        val existing = live[t.key]
        if (existing != null && existing.isAlive()) return existing
        // 死连接（含被 adbd kick 的假活）：先关闭再重建，避免占 adbd 名额 / 旧 socket 泄漏。
        if (existing != null) {
            dropLive(t.key)
            runCatching { existing.close() }
        }
        val c = newClientFor(t)
        val r = c.connect(t.host, t.port)
        if (r.startsWith("OK")) {
            putLive(t.key, c)
            return c
        }
        c.close()
        null
    }

    /**
     * R127：同一 target 的并发 pull 串行化（**排队等待**，不拒绝第二个请求）。
     *
     * 对齐 R125 教训与真机实录「并行拉取相互干扰」：分块路径复用固定临时前缀
     * /data/local/tmp/andagent_part_*，两个并发 pull 会互相覆盖彼此的分块文件。
     * AdbClient 自身的 lock 只覆盖单次 `sync:` 流，跨「stat→split→N 次 RECV→rm」
     * 的多步序列必须由工具层串行化。按 host 归一，使 "host" 与 "host:port" 引用
     * 落到同一把闸；`computeIfAbsent` 原子取锁避免竞态产生两把不同的锁。
     */
    fun <T> withPullSerial(ref: String, block: () -> T): T {
        val host = (registry.resolve(ref)?.host ?: ref).lowercase()
        val gate = pullGates.computeIfAbsent(host) { Any() }
        return synchronized(gate) { block() }
    }

    /**
     * R121f：shell 的 ACK 8s 快速失败说明 adbd 已不认这条 transport（被新连接 kick 掉了）。
     * 主动 close 该【具体的】连接并从 live 摘除——留着无意义还会占 adbd 名额，且下次
     * client() 会自动重建。按对象身份匹配（而非按 ref 查表），避免误关并发 adb_connect
     * 在 8s 窗口内重建的新连接。返回是否命中该失败特征（供调用方追加重试提示）。
     */
    fun resetStalled(result: String, stalled: AdbClient): Boolean {
        if (!AdbToolRules.isShellAckStall(result)) return false
        synchronized(lock) {
            val key = live.entries.firstOrNull { it.value === stalled }?.key
            if (key != null) dropLive(key)
            runCatching { stalled.close() }
        }
        return true
    }

    /**
     * R100 ③ / R103 A：创建【独立】AdbClient（复用共享 keyPair，但不放入 live 池）。
     * scrcpy 会话用它开常驻视频裸流（openDuplex 的读循环会长期占用连接），避免与共享池其它
     * 工具锁步互踩。传 ref 时按目标的 TLS 标记构造对应传输；返回未连接实例；调用方负责
     * connect(host, port) 并在会话结束后 close()。复用同一 keyPair → 已授权公钥仍然授信。
     */
    fun newClient(ref: String? = null): AdbClient {
        val t = ref?.let { registry.resolve(it) }
        return if (t != null) newClientFor(t) else AdbClient(keyPair, TcpTransport())
    }

    /** adb_devices：列出已保存目标 + 状态（是否有 live 连接 + 连接时长）；离线目标做一次 best-effort 重连。 */
    fun devices(): String = synchronized(lock) {
        // R121f 断言式自愈：修复后同 host 不应再有多条目；老用户 KV 残留的历史端口则
        // Log.w 并自动合并为单条目，同时关掉旧端口 live（首次 adb_devices 不崩且收敛）。
        val dupGroups = registry.duplicateHostGroups()
        if (dupGroups.isNotEmpty()) {
            android.util.Log.w(
                TAG,
                "adb_devices: 检测到同 host 多端口残留 " +
                    dupGroups.joinToString { g -> "${g.first().host}=${g.map { it.port }}" } +
                    "，自动合并为单条目",
            )
            dupGroups.forEach { group ->
                val keeper = group.firstOrNull { live[it.key]?.isAlive() == true } ?: group.last()
                registry.mergeHost(keeper)
                closeOtherLive(keeper.host, keeper.key)
            }
            persist()
        }
        val targets = registry.list()
        if (targets.isEmpty()) {
            if (pairings.isNotEmpty()) return "已配对待连接：${pairings.keys.joinToString()}。请从目标机无线调试主页读取主端口，调用 adb_connect(host, port)；配对端口不能用作主端口。"
            return "没有已保存的 ADB 目标。用 adb_connect(host, port=5555) 连接目标手机（无线调试端口从手机「无线调试」页读取）。"
        }
        val sb = StringBuilder("已保存 ADB 目标 ${targets.size} 个：\n")
        for (t in targets) {
            val c = live[t.key]
            val alive = c != null && c.isAlive()
            var status = if (alive) {
                "connected（有 live 连接，已连接 ${AdbToolRules.formatDuration(System.currentTimeMillis() - (liveSince[t.key] ?: System.currentTimeMillis()))}）"
            } else "offline（无 live 连接）"
            if (!alive) {
                // R121f：重连前按单客户端约束清掉同 host 其它端口残留 + 本条目死连接。
                closeOtherLive(t.host, t.key)
                runCatching { dropLive(t.key)?.close() }
                val nc = newClientFor(t)
                val r = nc.connect(t.host, t.port, RECONNECT_TIMEOUT_MS)
                if (r.startsWith("OK")) {
                    putLive(t.key, nc)
                    status = "connected(已重连，有 live 连接)"
                } else {
                    runCatching { nc.close() }
                }
            }
            sb.append("- ${t.host}:${t.port}  $status\n")
        }
        pairings.keys.filter { host -> targets.none { it.host.equals(host, true) } }.forEach { host ->
            sb.append("- $host  paired(待连接主端口)\n")
        }
        sb.append("提示：首次连接需在手机点「允许」授权；adb_shell/pull/push/screencap 会自动重连离线目标。")
        sb.toString().trimEnd()
    }
}

/** R100 ②：ADB 工具实现。网络/密钥/文件操作均在 Dispatchers.IO，never-crash 收敛为 ERROR 文本。 */
class AdbTool(private val context: Context, override val name: String) : Tool {
    init { AdbSessionHub.init(context) }

    override val description = when (name) {
        "adb_connect" ->
            "连接目标手机的 adbd（无线调试主端口）。host 必填；port 默认 5555。tls 可选：默认自动——用 adb_pair 配对过的 host 自动走 TLS 1.3 传输（Android 11+ 配对后主端口要求 TLS：先明文 CNXN→STLS 升级，再做带客户端证书的 mTLS，身份由本机 ADB 密钥保证），未配对或传统明文 adbd 走明文 TCP + AUTH RSA；也可显式传 tls=true/false 覆盖。首次连接会向手机发送本机公钥并弹「允许调试」授权框，点「允许」后重新调用本工具一次即握手成功。adb_pair 用的是「配对端口」，与这里的主端口不同。"
        "adb_devices" ->
            "列出已保存的 ADB 目标（host/port/状态）；app 重启后仍能看到，并对离线目标尝试重连。"
        "adb_disconnect" -> "断开并移除已保存的 ADB 目标（host 或 host:port）。"
        "adb_shell" ->
            "在目标手机执行 shell 命令（ADB OPEN shell:cmd 流），返回 UTF-8 边界截断后的 stdout/stderr。破坏性命令（rm -rf、mkfs、dd、reboot、systemctl restart …）返回 CONFIRM_REQUIRED，需向用户说明并以 confirm=true 重试。"
        "adb_screencap" ->
            "在目标手机执行 screencap -p 到 /data/local/tmp，再 pull 回本机相册 Pictures/AndAgent，返回 savedPath 与缩略图。"
        "adb_pull" ->
            "从目标手机拉取单个文件到本机 Download/AndAgentSsh（或 app_private），返回 savedPath。max_mb 对齐 ssh_download（默认 512，钳制 1..4096）。"
        "adb_push" ->
            "把本机文件上传到目标手机（SYNC SEND）。local_path/remote_path 必填；max_mb 对齐 ssh_download。"
        "adb_input" ->
            "向 ADB 目标机注入输入（纯 shell：input tap/swipe/draganddrop/keyevent/text）。target 必填（先用 adb_connect 登记）；action=tap（x/y）、swipe（x/y/x2/y2，duration_ms 默认 300）、drag（x/y/x2/y2，duration_ms 默认 300）、key（keycode，0..65535）、text（要键入的 ASCII 文本）。text 会做 shell 单引号转义并把空格转成 %s（AOSP input 命令要求），中文/emoji 等非 ASCII 如实拒绝并提示改用剪贴板；不做原生控制通道注入。破坏性：改变目标机输入/界面状态，走 web 确认闸。"
        AdbPairTool.NAME -> AdbPairTool.description
        AdbQrPairTool.NAME -> AdbQrPairTool.description
        in AdbScrcpyToolSpec.NAMES -> AdbScrcpyToolSpec.description(name)
        else -> "ADB 工具"
    }

    /** 安全边界：shell/pull/push + 录制 adb_record 标 destructive（web 确认闸 + 提示词覆盖）。 */
    override val destructive = name in setOf(
        "adb_shell", "adb_pull", "adb_push", "adb_input", AdbScrcpyToolSpec.RECORD,
    )

    override val parameters = when (name) {
        "adb_connect" -> schemaOf(
            "host" to strParam("目标手机 IP 或主机名"),
            "port" to intParam("无线调试主端口，默认 5555"),
            "tls" to boolParam("是否走 TLS 1.3；默认自动：adb_pair 配对过的 host 自动 TLS，未配对走明文"),
            required = listOf("host"),
        )
        "adb_devices" -> schemaOf()
        "adb_disconnect" -> schemaOf("host" to strParam("目标 host 或 host:port"), required = listOf("host"))
        "adb_shell" -> schemaOf(
            "target" to strParam("目标 host 或 host:port"),
            "command" to strParam("要执行的 shell 命令"),
            "confirm" to boolParam("破坏性命令确认后传 true"),
            required = listOf("target", "command"),
        )
        "adb_screencap" -> schemaOf("target" to strParam("目标 host 或 host:port"), required = listOf("target"))
        "adb_pull" -> schemaOf(
            "target" to strParam("目标 host 或 host:port"),
            "remote_path" to strParam("目标手机上的绝对路径"),
            "dir" to strParam("downloads（默认）或 app_private"),
            "filename" to strParam("可选保存文件名"),
            "max_mb" to intParam("大小上限 MB，默认 512，钳制 1..4096"),
            required = listOf("target", "remote_path"),
        )
        "adb_push" -> schemaOf(
            "target" to strParam("目标 host 或 host:port"),
            "local_path" to strParam("本机文件绝对路径"),
            "remote_path" to strParam("目标手机上的绝对路径"),
            "max_mb" to intParam("大小上限 MB，默认 512，钳制 1..4096"),
            required = listOf("target", "local_path", "remote_path"),
        )
        "adb_input" -> schemaOf(
            "target" to strParam("目标 host 或 host:port"),
            "action" to strParam("tap|swipe|key|text|drag"),
            "x" to intParam("tap/swipe/drag 起点 X 坐标"),
            "y" to intParam("tap/swipe/drag 起点 Y 坐标"),
            "x2" to intParam("swipe/drag 终点 X 坐标"),
            "y2" to intParam("swipe/drag 终点 Y 坐标"),
            "duration_ms" to intParam("swipe/drag 时长毫秒，默认 300"),
            "keycode" to intParam("key 的 Android keyevent 数字（0..65535）"),
            "text" to strParam("text 要键入的 ASCII 文本"),
            required = listOf("target", "action"),
        )
        AdbPairTool.NAME -> AdbPairTool.parameters
        AdbQrPairTool.NAME -> AdbQrPairTool.parameters
        in AdbScrcpyToolSpec.NAMES -> AdbScrcpyToolSpec.parameters(name)
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (name == AdbQrPairTool.NAME) AdbQrPairTool.execute(context, args)
        else runCatching { dispatch(args) }
            .getOrElse { e -> "ERROR: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun dispatch(args: JsonObject): String {
        return when (name) {
            "adb_connect" -> doConnect(args)
            "adb_devices" -> AdbSessionHub.devices()
            "adb_disconnect" -> {
                val host = args.strArg("host") ?: return "ERROR: 缺少 host"
                AdbSessionHub.disconnect(host)
            }
            "adb_shell" -> doShell(args)
            "adb_input" -> doInput(args)
            "adb_screencap" -> doScreencap(args)
            "adb_pull" -> doPull(args)
            "adb_push" -> doPush(args)
            AdbPairTool.NAME -> AdbPairTool.execute(args, { AdbSessionHub.adbPublicKey() }) { host, fp ->
                AdbSessionHub.rememberPairing(host, fp)
            }
            in AdbScrcpyToolSpec.NAMES -> AdbScrcpyToolSpec.execute(context, name, args)
            else -> "ERROR: 未知 ADB 工具"
        }
    }

    private fun doConnect(args: JsonObject): String {
        val host = args.strArg("host") ?: return "ERROR: 缺少 host"
        AdbToolRules.validateHost(host)?.let { return "ERROR: $it" }
        val port = args.intArg("port") ?: AdbToolRules.DEFAULT_PORT
        if (!AdbToolRules.validPort(port)) return "ERROR: port 必须在 1..65535"
        // 默认自动：配对过的 host 走 TLS，未配对走明文；显式 tls=true/false 覆盖。
        val useTls = args.boolArg("tls") ?: AdbSessionHub.isPaired(host)
        val pinned = if (useTls) AdbSessionHub.fingerprintFor(host) else null
        val r = AdbSessionHub.connect(host, port, useTls, pinned)
        return buildString {
            appendLine(r)
            when {
                r.startsWith("OK") -> append("下一步：adb_devices 查看；adb_shell / adb_screencap / adb_pull / adb_push 操作。")
                r.startsWith("AUTH_REQUIRED") -> append("目标已登记；在手机点「允许」后重新 adb_connect(host, port) 即完成握手。")
                else -> append("请确认目标手机「无线调试」已开启、端口正确且与本机在同一网络。")
            }
        }
    }

    private fun doShell(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        val cmd = args.strArg("command") ?: return "ERROR: 缺少 command"
        if (cmd.length > 8000) return "ERROR: 命令过长（上限 8000 字符）"
        // 破坏性命令走 SshCommandGuard 同款分级确认（复用现成 guard）。
        AdbToolRules.confirmGate(cmd, args.boolArg("confirm") == true)?.let { return it }
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        val danger = AdbToolRules.dangerOf(cmd)
        val out = c.openStream(cmd)
        // R121f：ACK 8s 快速失败时连接已被 adbd 抛弃，主动重置以便下次重建。
        val reset = AdbSessionHub.resetStalled(out, c)
        return buildString {
            appendLine("target=$target")
            appendLine("command=${cmd.take(300)}")
            danger?.let { appendLine("confirmed=$it") }
            append("--- output ---\n$out")
            if (reset) append("\n连接已重置，请直接重试（会重建连接）")
        }
    }

    /** R122：纯 shell 输入注入，命令由 AdbInputCodec 构造（单引号转义 / %s / 参数校验）。 */
    private fun doInput(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        val built = AdbInputCodec.build(
            action = args.strArg("action"),
            x = args.intArg("x"),
            y = args.intArg("y"),
            x2 = args.intArg("x2"),
            y2 = args.intArg("y2"),
            durationMs = args.intArg("duration_ms"),
            keycode = args.intArg("keycode"),
            text = args.strArg("text"),
        )
        val cmd = built.command ?: return built.error ?: "ERROR: 构造输入命令失败"
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        // Codex review 必修 3：input 命令本身无 stdout，openStream 在「非零退出」时只在
        // 尾部附 [exit code: n]、在「ACK 后 EOF」时可能返回空串——两种形态都曾被当成
        // 普通输出虚报成功。追加 `&& echo` 完成标记（复用 doScreencap/AdbChunkPull 在
        // 真机已实证的形态），标记缺失或带退出码即如实报 ERROR。
        val out = c.openStream("(" + cmd + ") && echo " + AdbInputCodec.DONE_MARK)
        val ok = AdbInputCodec.outputOk(out)
        // R121f：ACK 8s 快速失败时连接已被 adbd 抛弃，主动重置以便下次重建。
        val reset = AdbSessionHub.resetStalled(out, c)
        if (!ok && !reset) {
            return "ERROR: input 命令未确认执行成功（目标机退出码非零或连接提前断开）；" +
                "command=${cmd.take(300)}\n--- output ---\n${out.take(500)}"
        }
        return buildString {
            appendLine("target=$target")
            appendLine("command=${cmd.take(300)}")
            if (!ok && reset) appendLine("status=UNKNOWN：连接被重置，命令是否生效未知，请重试或adb_shell验证")
            append("--- output ---\n$out")
            if (reset) append("\n连接已重置，请直接重试（会重建连接）")
        }
    }

    private fun doScreencap(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        // R127：分块路径复用共享临时前缀，screencap 内部也走 syncPull，一并纳入串行闸。
        return AdbSessionHub.withPullSerial(target) { screencapLocked(target) }
    }

    private fun screencapLocked(target: String): String {
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        val remote = "/data/local/tmp/andagent_sc_${System.currentTimeMillis()}.png"
        // screencap 无 stdout：追加 echo 让 openStream 能凭输出判定命令真实完成，
        // 避免 TLS 上 CLSE 延迟时读循环干等（体感「半天没返回」）。
        val shot = c.openStream("screencap -p $remote && echo SC_OK")
        if (shot.startsWith("ERROR")) return "ERROR: screencap 执行失败：$shot"
        return when (val r = c.syncPull(remote, SCREENCAP_MAX_BYTES)) {
            is AdbClient.AdbSyncResult.Pulled -> screencapResult(context, target, remote, r.bytes, r.evidence)
            is AdbClient.AdbSyncResult.Failed -> {
                // R130：sync: 被拒时走 shell dd|base64 分块（绕开 sync 与 256KB 输出窗口，
                // 截图 PNG ~830KB 也在此通道内分块拿全）。证据见 AdbShellPull 注释。
                when (val fb = AdbShellPull.pull(remote, SCREENCAP_MAX_BYTES) { cmd -> c.openStream(cmd) }) {
                    is AdbClient.AdbSyncResult.Pulled ->
                        screencapResult(context, target, remote, fb.bytes, fb.evidence)
                    is AdbClient.AdbSyncResult.Failed ->
                        "ERROR: 拉取截图失败：sync=${r.reason}；shell 分块回退失败：${fb.reason}" +
                            (if (fb.evidence.isNotEmpty()) "\n${fb.evidence}" else "")
                    is AdbClient.AdbSyncResult.Pushed -> "ERROR: 拉取截图回退返回意外结果"
                }
            }
            is AdbClient.AdbSyncResult.Pushed -> "ERROR: 拉取截图返回意外结果"
        }
    }

    private fun screencapResult(
        context: Context,
        target: String,
        remote: String,
        bytes: ByteArray,
        evidence: String = "",
    ): String {
        runCatching { AdbSessionHub.client(target)?.openStream("rm -f $remote") }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val json = Json.parseToJsonElement(devtoolsScreenshotJson(context, base64)).jsonObject
        return buildJsonObject {
            json.forEach { (k, v) -> put(k, v) }
            put("target", target)
            put("remotePath", remote)
            put("bytes", bytes.size)
            if (evidence.isNotEmpty()) put("evidence", evidence)
        }.toString()
    }

    private fun doPull(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        // R127：同 target 并发 pull 串行化（排队），保护共享分块临时前缀。
        return AdbSessionHub.withPullSerial(target) { pullLocked(args, target) }
    }

    private fun pullLocked(args: JsonObject, target: String): String {
        val remote = args.strArg("remote_path") ?: return "ERROR: 缺少 remote_path"
        AdbToolRules.validateRemotePath(remote)?.let { return "ERROR: $it" }
        val maxBytes = AdbToolRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        return when (val r = c.syncPull(remote, maxBytes)) {
            is AdbClient.AdbSyncResult.Pulled -> {
                val name = runCatching {
                    AdbToolRules.safeName(args.strArg("filename") ?: remote.substringAfterLast('/'))
                }.getOrElse { return "ERROR: 文件名无效：${it.message}" }
                val dir = args.strArg("dir") ?: "downloads"
                if (dir !in listOf("downloads", "app_private")) return "ERROR: dir 只能是 downloads 或 app_private"
                val saved = runCatching { saveBytes(name, r.bytes, dir) }
                    .getOrElse { return "ERROR: 保存失败：${it.message}" }
                buildString {
                    append("savedPath=$saved\nbytes=${r.bytes.size}\nremote=$remote\ntarget=$target")
                    // 分块诊断（大文件分块模式文案 + 逐块留痕）由结果携带，工具层只统一追加。
                    if (r.evidence.isNotEmpty()) append("\n${r.evidence}")
                }
            }
            is AdbClient.AdbSyncResult.Failed -> buildString {
                append("ERROR: 拉取失败：${r.reason}")
                if (r.evidence.isNotEmpty()) append("\n${r.evidence}")
                // R130：sync: 被拒（或修后仍有未知 OEM 时序）时降级到 shell dd|base64
                // 分块通道，绕开 sync 与 256KB 输出窗口，大文件也能拉（慢但可用）。
                // sync 优先路径保留：健康设备仍走快速 SYNC RECV。name/dir 与正常路径
                // 同一套校验（safeName / dir 枚举），不用未校验的远程文件名兜底。
                val dir = args.strArg("dir") ?: "downloads"
                if (dir !in listOf("downloads", "app_private")) {
                    append("\nshell 回退失败：dir 只能是 downloads 或 app_private")
                } else {
                    val name = runCatching {
                        AdbToolRules.safeName(args.strArg("filename") ?: remote.substringAfterLast('/'))
                    }.getOrElse { "INVALID:${it.message}" }
                    if (name.startsWith("INVALID:")) {
                        append("\nshell 回退失败：文件名无效（${name.removePrefix("INVALID:")}）")
                    } else {
                        when (val fb = AdbShellPull.pull(remote, maxBytes) { cmd -> c.openStream(cmd) }) {
                            is AdbClient.AdbSyncResult.Pulled -> {
                                val saved = runCatching { saveBytes(name, fb.bytes, dir) }
                                    .getOrElse {
                                        append("\n⚠️ 此设备 sync: 通道不可用，已降级到 shell 分块通道（慢），但保存失败：${it.message}")
                                        return@buildString
                                    }
                                append("\n✅ 此设备 sync: 通道不可用，已自动降级到 shell dd|base64 分块通道：$saved（${fb.bytes.size} 字节）")
                                if (fb.evidence.isNotEmpty()) append("\n${fb.evidence}")
                            }
                            is AdbClient.AdbSyncResult.Failed -> {
                                append("\nshell 分块回退失败：${fb.reason}")
                                if (fb.evidence.isNotEmpty()) append("\n${fb.evidence}")
                            }
                            is AdbClient.AdbSyncResult.Pushed -> append("\nshell 分块回退返回意外结果")
                        }
                    }
                }
            }
            is AdbClient.AdbSyncResult.Pushed -> "ERROR: 拉取返回意外结果"
        }
    }

    private fun doPush(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        val local = args.strArg("local_path") ?: return "ERROR: 缺少 local_path"
        val remote = args.strArg("remote_path") ?: return "ERROR: 缺少 remote_path"
        AdbToolRules.validateRemotePath(remote)?.let { return "ERROR: $it" }
        val file = File(local)
        if (!file.isFile || !file.canRead()) return "ERROR: 本地文件不存在或不可读：$local"
        val maxBytes = AdbToolRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        if (file.length() > maxBytes) {
            return "ERROR: 文件 ${file.length()} 字节超过 ${maxBytes / 1_048_576}MB 上限（可用 max_mb 调整，最高 4096）"
        }
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        val data = runCatching { file.readBytes() }.getOrElse { return "ERROR: 读取本地文件失败：${it.message}" }
        return when (val r = c.syncPush(remote, data)) {
            is AdbClient.AdbSyncResult.Pushed ->
                "uploadedPath=$remote\nbytes=${r.bytes}\ntarget=$target"
            is AdbClient.AdbSyncResult.Failed -> "ERROR: 推送失败：${r.reason}"
            is AdbClient.AdbSyncResult.Pulled -> "ERROR: 推送返回意外结果"
        }
    }

    private fun noTarget(target: String): String =
        "ERROR: 目标不可达：$target（用 adb_devices 查看状态；首次连接先在手机点「允许」授权，再 adb_connect）"

    /** 落盘到 Download/AndAgentSsh（MediaStore）或 app_private（filesDir/adb_downloads）。 */
    private fun saveBytes(name: String, bytes: ByteArray, dir: String): String {
        if (dir == "app_private") {
            val root = File(context.filesDir, "adb_downloads").apply { mkdirs() }
            val target = File(root, name)
            require(!target.exists()) { "目标文件已存在：${target.absolutePath}" }
            target.writeBytes(bytes)
            return target.absolutePath
        }
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/AndAgentSsh"
        if (Build.VERSION.SDK_INT < 29) {
            val root = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            val target = File(root, name)
            require(!target.exists()) { "目标文件已存在：${target.absolutePath}" }
            target.writeBytes(bytes)
            return target.absolutePath
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { bytes.inputStream().use { input -> input.copyTo(it) } }
                ?: error("无法写入下载文件")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "/sdcard/$rel/$name"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private companion object {
        const val SCREENCAP_MAX_BYTES = 64L * 1024 * 1024
    }
}

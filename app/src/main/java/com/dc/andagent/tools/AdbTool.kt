package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.dc.andagent.adb.AdbAuth
import com.dc.andagent.adb.AdbClient
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

/** 注册进 MainActivity 工具表的 ADB 工具族（R100 ② 7 个 + R103 scrcpy 2 个 + 配对 1 个）。 */
val ADB_TOOLS = listOf(
    "adb_connect", "adb_devices", "adb_disconnect",
    "adb_shell", "adb_screencap", "adb_pull", "adb_push",
    AdbPairTool.NAME,
) + AdbScrcpyToolSpec.NAMES

/**
 * R100 ②：ADB 会话中枢（进程内单例）。7 个 AdbTool 实例共享同一份状态：
 *  - [registry] 已保存目标（纯 JVM 注册表，由 KV 持久化，app 重启后恢复）
 *  - [live] 当前活跃的 [AdbClient]（socket 不可跨进程/重启存活）
 *  - [keyPair] 本机 RSA 密钥（filesDir/adb/adbkey，AndroidKeystore 加密），授权一次后长期复用
 */
object AdbSessionHub {
    private const val NS = "adb"
    private const val KEY = "targets"
    /** R103 A：配对时记录的设备证书指纹（host -> SHA-256 hex，null = 已配对但未取到）。仅记录用途。 */
    private const val PAIRINGS_KEY = "pairings"
    const val RECONNECT_TIMEOUT_MS = 3000

    val registry = AdbSessionRegistry()
    private val live = LinkedHashMap<String, AdbClient>()
    private val pairings = LinkedHashMap<String, String?>()
    private val lock = Any()
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
            // 已保存的目标同步标记 TLS，便于自动重连 / adb_devices 走 TLS 通道。
            registry.list().filter { it.host.equals(host, ignoreCase = true) }.forEach {
                registry.add(it.copy(tls = true, fingerprint = fingerprint))
            }
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
            if (registry.size() >= AdbToolRules.MAX_TARGETS && !registry.contains(key)) {
                return "ERROR: 已保存目标数达上限 ${AdbToolRules.MAX_TARGETS}，请先 adb_disconnect"
            }
            val transport: AdbTransport =
                if (tls) {
                    TlsTransport(listOfNotNull(pinnedFp), AdbPairNative, keyPair.private.encoded)
                } else TcpTransport()
            val c = AdbClient(keyPair, transport)
            val r = c.connect(host, port)
            if (r.startsWith("OK")) {
                live[key] = c
            } else {
                c.close()
                live.remove(key)
            }
            // OK / AUTH_REQUIRED 都登记（便于 adb_devices 看到、重试）；纯网络 ERROR 不登记。
            if (r.startsWith("OK") || r.startsWith("AUTH_REQUIRED")) {
                registry.add(AdbTarget(host, port, tls, pinnedFp))
                persist()
            }
            r
        }

    fun disconnect(host: String): String = synchronized(lock) {
        val t = registry.resolve(host) ?: return "ERROR: 目标不存在：$host（用 adb_devices 查看）"
        live.remove(t.key)?.close()
        registry.remove(t.key)
        persist()
        "已断开 ADB 目标 ${t.host}:${t.port}"
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
        val existing = live[t.key]
        if (existing != null && existing.isAlive()) return existing
        val c = newClientFor(t)
        val r = c.connect(t.host, t.port)
        if (r.startsWith("OK")) {
            live[t.key] = c
            return c
        }
        c.close()
        live.remove(t.key)
        null
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

    /** adb_devices：列出已保存目标 + 状态；离线目标做一次 best-effort 重连。 */
    fun devices(): String = synchronized(lock) {
        val targets = registry.list()
        if (targets.isEmpty()) {
            return "没有已保存的 ADB 目标。用 adb_connect(host, port=5555) 连接目标手机（无线调试端口从手机「无线调试」页读取）。"
        }
        val sb = StringBuilder("已保存 ADB 目标 ${targets.size} 个：\n")
        for (t in targets) {
            val c = live[t.key]
            val alive = c != null && c.isAlive()
            var status = if (alive) "connected" else "offline"
            if (!alive) {
                val nc = newClientFor(t)
                val r = nc.connect(t.host, t.port, RECONNECT_TIMEOUT_MS)
                if (r.startsWith("OK")) {
                    live[t.key] = nc
                    status = "connected(已重连)"
                } else {
                    runCatching { nc.close() }
                }
            }
            sb.append("- ${t.host}:${t.port}  $status\n")
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
        AdbPairTool.NAME -> AdbPairTool.description
        in AdbScrcpyToolSpec.NAMES -> AdbScrcpyToolSpec.description(name)
        else -> "ADB 工具"
    }

    /** 安全边界：shell/pull/push + 录制 adb_record 标 destructive（web 确认闸 + 提示词覆盖）。 */
    override val destructive = name in setOf(
        "adb_shell", "adb_pull", "adb_push", AdbScrcpyToolSpec.RECORD,
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
        AdbPairTool.NAME -> AdbPairTool.parameters
        in AdbScrcpyToolSpec.NAMES -> AdbScrcpyToolSpec.parameters(name)
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { dispatch(args) }
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
        return buildString {
            appendLine("target=$target")
            appendLine("command=${cmd.take(300)}")
            danger?.let { appendLine("confirmed=$it") }
            append("--- output ---\n$out")
        }
    }

    private fun doScreencap(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
        val c = AdbSessionHub.client(target) ?: return noTarget(target)
        val remote = "/data/local/tmp/andagent_sc_${System.currentTimeMillis()}.png"
        // screencap 无 stdout：追加 echo 让 openStream 能凭输出判定命令真实完成，
        // 避免 TLS 上 CLSE 延迟时读循环干等（体感「半天没返回」）。
        val shot = c.openStream("screencap -p $remote && echo SC_OK")
        if (shot.startsWith("ERROR")) return "ERROR: screencap 执行失败：$shot"
        return when (val r = c.syncPull(remote, SCREENCAP_MAX_BYTES)) {
            is AdbClient.AdbSyncResult.Pulled -> {
                // 清理目标机上的临时文件（内部操作，不经过用户确认闸）。
                runCatching { c.openStream("rm -f $remote") }
                val base64 = Base64.encodeToString(r.bytes, Base64.NO_WRAP)
                // 复用 devtools_screenshot 公共段：base64 PNG → 相册 + 缩略图（等同 image_save 落相册）。
                val json = Json.parseToJsonElement(devtoolsScreenshotJson(context, base64)).jsonObject
                buildJsonObject {
                    json.forEach { (k, v) -> put(k, v) }
                    put("target", target)
                    put("remotePath", remote)
                    put("bytes", r.bytes.size)
                }.toString()
            }
            is AdbClient.AdbSyncResult.Failed -> "ERROR: 拉取截图失败：${r.reason}"
            is AdbClient.AdbSyncResult.Pushed -> "ERROR: 拉取截图返回意外结果"
        }
    }

    private fun doPull(args: JsonObject): String {
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"
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
                "savedPath=$saved\nbytes=${r.bytes.size}\nremote=$remote\ntarget=$target"
            }
            is AdbClient.AdbSyncResult.Failed -> "ERROR: 拉取失败：${r.reason}"
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

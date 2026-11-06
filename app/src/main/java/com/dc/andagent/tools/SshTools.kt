package com.dc.andagent.tools

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

val SSH_TOOLS = listOf("ssh_connect", "ssh_exec", "ssh_list", "ssh_close")

/**
 * R91: SSH 远程运维（JSch，方案 B）。
 *
 * 会话池跨工具调用存活：ssh_connect 建连后把 JSch [Session] 放进
 * [SshSessionPool]，ssh_exec 按 session_id（或 nickname）取用。known_hosts
 * 存在应用私有目录，采用 TOFU（首次使用即信任）：首次连接只探测并返回
 * SHA256 指纹，用户确认后写入 known_hosts；之后 StrictHostKeyChecking=yes，
 * 指纹不匹配立即拒连。
 */
class SshTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "ssh_connect" ->
            "连接远程服务器（SSH，密码或私钥认证）。首次连接会返回主机密钥 SHA256 指纹并要求确认，确认后以 confirm=true 重试；成功后返回 session_id，供 ssh_exec 使用。"
        "ssh_exec" ->
            "在已连接的 SSH 会话执行命令，返回退出码与截断后的 stdout/stderr。rm -rf、mkfs、dd、systemctl restart、reboot 等破坏性命令需要 confirm=true，必须先向用户说明影响并取得确认。"
        "ssh_list" -> "列出当前活跃的 SSH 会话：session_id、主机、用户、连接时长。"
        else -> "断开并清理指定 SSH 会话。"
    }

    override val destructive = name == "ssh_connect"

    override val parameters = when (name) {
        "ssh_connect" -> schemaOf(
            "host" to strParam("主机名或 IP"),
            "port" to intParam("端口，默认 22"),
            "user" to strParam("登录用户名"),
            "auth" to strParam("认证方式：password（密码）或 key（私钥）"),
            "password" to strParam("密码认证时的密码"),
            "key_path" to strParam("私钥认证时私钥文件的绝对路径"),
            "key_passphrase" to strParam("私钥口令（私钥加密时需要）"),
            "nickname" to strParam("可选：会话别名，之后可代替 session_id 引用"),
            "confirm" to boolParam("首次连接确认指纹后传 true"),
            required = listOf("host", "user", "auth"),
        )
        "ssh_exec" -> schemaOf(
            "session_id" to strParam("ssh_connect 返回的 session_id，或连接时的 nickname"),
            "cmd" to strParam("要执行的命令"),
            "timeout_s" to intParam("超时秒数，默认 30，上限 300"),
            "confirm" to boolParam("破坏性命令确认后传 true"),
            required = listOf("session_id", "cmd"),
        )
        "ssh_list" -> schemaOf()
        else -> schemaOf("session_id" to strParam("要断开的 session_id 或 nickname"), required = listOf("session_id"))
    }

    private val appContext = context.applicationContext

    override suspend fun execute(args: JsonObject): String = when (name) {
        "ssh_connect" -> connect(args)
        "ssh_exec" -> exec(args)
        "ssh_list" -> list()
        "ssh_close" -> close(args)
        else -> "ERROR: 未知 SSH 工具"
    }

    /* ----------------------------- connect ----------------------------- */

    private suspend fun connect(args: JsonObject): String = withContext(Dispatchers.IO) {
        val host = args.strArg("host") ?: return@withContext "ERROR: 缺少 host"
        if (host.length > 253 || !host.matches(Regex("[^\\s/\\\\]+"))) return@withContext "ERROR: host 格式无效"
        val port = args.intArg("port") ?: 22
        if (port !in 1..65535) return@withContext "ERROR: port 必须在 1..65535"
        val user = args.strArg("user") ?: return@withContext "ERROR: 缺少 user"
        if (user.length > 64 || !user.matches(Regex("[^\\s]+"))) return@withContext "ERROR: user 格式无效"
        val auth = (args.strArg("auth") ?: "password").lowercase()
        if (auth != "password" && auth != "key") return@withContext "ERROR: auth 只能是 password 或 key"
        val password = args.strArg("password").orEmpty()
        val keyPath = args.strArg("key_path").orEmpty()
        val passphrase = args.strArg("key_passphrase").orEmpty()
        val nickname = args.strArg("nickname")
        if (nickname != null && (nickname.length > 32 || !nickname.matches(Regex("[^\\s]+")))) {
            return@withContext "ERROR: nickname 不能包含空白且最长 32 字符"
        }
        if (auth == "password" && password.isEmpty()) return@withContext "ERROR: password 认证需要 password"
        if (auth == "key" && keyPath.isEmpty()) return@withContext "ERROR: key 认证需要 key_path"
        if (SshSessionPool.size() >= MAX_SESSIONS) return@withContext "ERROR: 活跃会话已达上限 $MAX_SESSIONS，请先 ssh_close 释放"

        val keyId = knownHostId(host, port)
        val knownKeys = readKnownHosts()
        val remembered = knownKeys[keyId]
        val confirm = args.boolArg("confirm") == true

        // TOFU: unknown host → probe, show fingerprint, do not keep a session.
        if (remembered == null) {
            val probe = try {
                openSession(host, port, user, auth, password, keyPath, passphrase, strict = false)
            } catch (e: Exception) {
                return@withContext connectError(e)
            }
            val hostKey = probe.hostKey ?: run {
                probe.disconnect()
                return@withContext "ERROR: 服务器未提供主机密钥"
            }
            val keyType = hostKey.type
            // JSch 0.2.x 的 HostKey.getKey() 返回 base64 文本，指纹按解码后的公钥 blob 计算。
            val keyBase64 = hostKey.key
            val fingerprint = fingerprintOf(keyBase64)
            probe.disconnect()

            val pending = SshSessionPool.pendingFingerprint(keyId)
            if (!confirm) {
                SshSessionPool.setPendingFingerprint(keyId, fingerprint)
                return@withContext buildString {
                    appendLine("CONFIRM_REQUIRED: 首次连接 $user@$host:$port，主机密钥尚未信任。")
                    appendLine("主机密钥: $keyType $fingerprint")
                    appendLine("请向用户展示该 SHA256 指纹并确认来源可信；用户确认后以 confirm=true 重试。")
                    append("若指纹与服务器管理员提供的不一致，请拒绝连接。")
                }
            }
            if (pending == null || pending != fingerprint) {
                return@withContext "ERROR: 本次探测到的指纹与确认提示不一致（可能中间人攻击或服务端刚刚更换密钥），已拒绝连接。请重新发起 ssh_connect 获取新指纹。"
            }
            appendKnownHost(keyId, keyType, keyBase64)
            SshSessionPool.clearPendingFingerprint(keyId)
        }

        val session = try {
            openSession(host, port, user, auth, password, keyPath, passphrase, strict = true)
        } catch (e: Exception) {
            return@withContext connectError(e, knownHost = remembered != null)
        }
        val hostKey = session.hostKey
        val fingerprint = hostKey?.key?.let(::fingerprintOf) ?: "(未知)"
        val keyType = hostKey?.type ?: "(未知)"
        val info = try {
            SshSessionPool.registry.add(host, port, user, nickname)
        } catch (e: IllegalArgumentException) {
            session.disconnect()
            return@withContext "ERROR: ${e.message}"
        }
        SshSessionPool.put(info.id, SshSessionPool.Live(session))
        buildString {
            appendLine("已连接 $user@$host:$port${nickname?.let { "（$it）" } ?: ""}")
            appendLine("session_id=${info.id}")
            appendLine("host_key=$keyType $fingerprint")
            append("下一步：ssh_exec 执行命令；ssh_list 查看会话；ssh_close 断开。")
        }
    }

    private fun openSession(
        host: String,
        port: Int,
        user: String,
        auth: String,
        password: String,
        keyPath: String,
        passphrase: String,
        strict: Boolean,
    ): Session {
        val jsch = JSch()
        val knownHosts = knownHostsFile()
        if (knownHosts.isFile && knownHosts.length() > 0) jsch.setKnownHosts(knownHosts.absolutePath)
        if (auth == "key") {
            val key = File(keyPath)
            if (!key.isFile || !key.canRead()) throw IllegalArgumentException("私钥文件不存在或不可读：$keyPath")
            if (key.length() > MAX_KEY_BYTES) throw IllegalArgumentException("私钥文件异常大（>1MB）：$keyPath")
            jsch.addIdentity(key.absolutePath, passphrase.ifEmpty { null })
        }
        val session = jsch.getSession(user, host, port)
        if (auth == "password") session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", if (strict) "yes" else "no")
        session.setConfig(
            "PreferredAuthentications",
            if (auth == "key") "publickey" else "password,keyboard-interactive",
        )
        session.setConfig("ConnectTimeout", "10000")
        session.setConfig("ServerAliveInterval", "15000")
        session.setConfig("ServerAliveCountMax", "3")
        session.connect(10_000)
        return session
    }

    private fun connectError(e: Exception, knownHost: Boolean = false): String {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("auth fail") || lower.contains("auth cancel") ->
                "ERROR: SSH 认证失败，请检查用户名、密码或私钥。"
            lower.contains("unknownhostkey") || lower.contains("unknown host key") ->
                "ERROR: 服务器提供的主机密钥类型不在 known_hosts 中（可能服务端更换了密钥算法）。如确认无误，请删除应用私有目录 ssh/known_hosts 中该主机的记录后重连。"
            lower.contains("changed") || lower.contains("reject hostkey") || (knownHost && lower.contains("hostkey")) ->
                "ERROR: 主机密钥不匹配，可能遭受中间人攻击或服务器更换了密钥。已拒绝连接；请核对服务器指纹后再清理 known_hosts。"
            lower.contains("timeout") || lower.contains("timed out") ->
                "ERROR: 连接超时（10 秒）。请检查网络、端口与防火墙。"
            lower.contains("connection refused") ->
                "ERROR: 连接被拒绝，请确认 SSH 服务已启动且端口正确。"
            else -> "ERROR: SSH 连接失败：${msg.ifBlank { e.javaClass.simpleName }}"
        }
    }

    /* ------------------------------- exec ------------------------------ */

    private suspend fun exec(args: JsonObject): String = withContext(Dispatchers.IO) {
        val ref = args.strArg("session_id") ?: return@withContext "ERROR: 缺少 session_id"
        val live = SshSessionPool.resolve(ref)
            ?: return@withContext "ERROR: 会话不存在或已断开：$ref（用 ssh_list 查看活跃会话）"
        val cmd = args.strArg("cmd") ?: return@withContext "ERROR: 缺少 cmd"
        if (cmd.length > MAX_COMMAND_CHARS) return@withContext "ERROR: 命令过长（上限 $MAX_COMMAND_CHARS 字符）"
        val timeoutS = (args.intArg("timeout_s") ?: 30).coerceIn(1, 300)
        val danger = SshCommandGuard.dangerOf(cmd)
        if (danger != null && args.boolArg("confirm") != true) {
            return@withContext buildString {
                appendLine("CONFIRM_REQUIRED: 检测到破坏性命令「$danger」。")
                appendLine("命令: ${cmd.take(300)}")
                append("请先向用户说明该命令的影响与目标主机，用户确认后以 confirm=true 重试。")
            }
        }

        val session = live.session
        val out = CappedOutputStream(STDOUT_LIMIT)
        val err = CappedOutputStream(STDERR_LIMIT)
        var exit = -1
        var timedOut = false
        try {
            synchronized(session) {
                if (!session.isConnected) return@withContext "ERROR: 会话已断开：$ref"
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(cmd)
                channel.setInputStream(null)
                channel.setOutputStream(out)
                channel.setErrStream(err)
                channel.connect(10_000)
                val deadline = System.currentTimeMillis() + timeoutS * 1000L
                while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                timedOut = !channel.isClosed
                exit = if (timedOut) -1 else channel.exitStatus
                channel.disconnect()
            }
        } catch (e: Exception) {
            return@withContext "ERROR: 命令执行失败：${e.message ?: e.javaClass.simpleName}"
        }
        buildString {
            appendLine("exit_code=$exit")
            if (timedOut) appendLine("timeout=true（超过 ${timeoutS}s，已强制断开通道）")
            if (danger != null) appendLine("confirmed=$danger")
            appendLine("--- stdout ---")
            appendLine(SshCommandGuard.clipUtf8(out.toByteArray(), STDOUT_LIMIT, out.total))
            append("--- stderr ---\n")
            append(SshCommandGuard.clipUtf8(err.toByteArray(), STDERR_LIMIT, err.total))
        }
    }

    /* ------------------------------- list ------------------------------ */

    private fun list(): String {
        SshSessionPool.pruneDisconnected()
        val items = SshSessionPool.registry.list()
        if (items.isEmpty()) return "没有活跃的 SSH 会话"
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("活跃 SSH 会话 ${items.size} 个：")
            items.forEach { info ->
                val mins = ((now - info.startedAt) / 60_000L).coerceAtLeast(0)
                append("- ${info.id}")
                info.nickname?.let { append("（$it）") }
                append("  ${info.user}@${info.host}:${info.port}  已连接 ${mins} 分钟")
                append('\n')
            }
        }.trimEnd()
    }

    /* ------------------------------ close ------------------------------ */

    private fun close(args: JsonObject): String {
        val ref = args.strArg("session_id") ?: return "ERROR: 缺少 session_id"
        val info = SshSessionPool.registry.resolve(ref)
            ?: return "ERROR: 会话不存在：$ref"
        SshSessionPool.remove(info.id)?.session?.disconnect()
        return "已断开 SSH 会话 ${info.id}（${info.user}@${info.host}:${info.port}）"
    }

    /* ---------------------------- storage ------------------------------ */

    private fun knownHostsFile(): File = File(File(appContext.filesDir, "ssh"), "known_hosts")

    /** JSch known_hosts 的主机字段：非 22 端口写成 [host]:port。 */
    private fun knownHostId(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

    private fun readKnownHosts(): Map<String, String> {
        val file = knownHostsFile()
        if (!file.isFile) return emptyMap()
        return file.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 3)
                if (parts.size >= 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun appendKnownHost(keyId: String, type: String, keyBase64: String) {
        val file = knownHostsFile()
        file.parentFile?.mkdirs()
        // 同一主机重复行会让 JSch 取旧值，先清掉旧记录再写。
        val kept = if (file.isFile) {
            file.readLines().filter { it.isNotBlank() && it.split(Regex("\\s+")).firstOrNull() != keyId }
        } else {
            emptyList()
        }
        file.writeText((kept + "$keyId $type $keyBase64").joinToString("\n", postfix = "\n"))
    }

    private fun fingerprintOf(base64Key: String): String {
        val blob = runCatching { Base64.getDecoder().decode(base64Key) }.getOrElse { base64Key.toByteArray() }
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val MAX_KEY_BYTES = 1_048_576L
        private const val MAX_COMMAND_CHARS = 8_000
        private const val STDOUT_LIMIT = 8 * 1024
        private const val STDERR_LIMIT = 4 * 1024
    }
}

/* ------------------------------- pool -------------------------------- */

data class SshSessionInfo(
    val id: String,
    val host: String,
    val port: Int,
    val user: String,
    val nickname: String?,
    val startedAt: Long,
)

/** 会话元数据（纯 Kotlin，可脱离 JSch 单测）。 */
class SshSessionRegistry(private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = LinkedHashMap<String, SshSessionInfo>()
    private val random = SecureRandom()

    @Synchronized
    fun add(host: String, port: Int, user: String, nickname: String?): SshSessionInfo {
        if (nickname != null && entries.values.any { it.nickname == nickname }) {
            throw IllegalArgumentException("nickname 已被占用：$nickname")
        }
        val info = SshSessionInfo(newId(), host, port, user, nickname, clock())
        entries[info.id] = info
        return info
    }

    @Synchronized fun remove(id: String): SshSessionInfo? = entries.remove(id)

    @Synchronized fun get(id: String): SshSessionInfo? = entries[id]

    @Synchronized fun byNickname(nickname: String): SshSessionInfo? =
        entries.values.firstOrNull { it.nickname == nickname }

    /** 先按 id，再按 nickname 解析；未命中返回 null。 */
    @Synchronized fun resolve(ref: String): SshSessionInfo? = entries[ref] ?: byNickname(ref)

    @Synchronized fun list(): List<SshSessionInfo> = entries.values.toList()

    @Synchronized fun size(): Int = entries.size

    @Synchronized fun clear() = entries.clear()

    private fun newId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        while (true) {
            val id = "ssh-" + (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            if (id !in entries) return id
        }
    }
}

/**
 * 跨工具调用存活的 SSH 会话池。JSch [Session] 与元数据分开存放，
 * 断开时同时清理两者；外部掉线的会话在 list/exec 时惰性剔除。
 */
object SshSessionPool {
    data class Live(val session: Session)

    val registry = SshSessionRegistry()
    private val live = LinkedHashMap<String, Live>()
    private val pending = HashMap<String, String>()

    @Synchronized fun put(id: String, session: Live) {
        live[id] = session
    }

    @Synchronized fun remove(id: String): Live? {
        registry.remove(id)
        return live.remove(id)
    }

    @Synchronized fun resolve(ref: String): Live? {
        val info = registry.resolve(ref) ?: return null
        return live[info.id]
    }

    @Synchronized fun size(): Int = registry.size()

    @Synchronized fun pendingFingerprint(keyId: String): String? = pending[keyId]

    @Synchronized fun setPendingFingerprint(keyId: String, fingerprint: String) {
        pending[keyId] = fingerprint
    }

    @Synchronized fun clearPendingFingerprint(keyId: String) {
        pending.remove(keyId)
    }

    /** 剔除 JSch 已判定断开的会话，返回剩余数量。 */
    @Synchronized fun pruneDisconnected(): Int {
        val dead = live.filterValues { !it.session.isConnected }.keys.toList()
        dead.forEach { id ->
            live.remove(id)?.session?.disconnect()
            registry.remove(id)
        }
        return registry.size()
    }
}

/* --------------------------- command guard --------------------------- */

/**
 * 破坏性命令分级：命中任一条即要求 confirm=true。
 * 纯 Kotlin、无 Android 依赖，便于单测。
 */
object SshCommandGuard {
    private data class Rule(val label: String, val pattern: Regex)

    private val RULES = listOf(
        Rule("递归/强制删除（rm -r/-f）", Regex("""\brm\s+(?:[^\n;&|]*\s)?(?:-[A-Za-z]*[rRfF]\b|--recursive\b|--force\b)""")),
        Rule("格式化文件系统（mkfs）", Regex("""\bmkfs(?:\.[A-Za-z0-9]+)?\b""")),
        Rule("裸设备写入（dd/tee/重定向）", Regex("""\bdd\s+[^\n;&|]*\bif\s*=|\bdd\s+[^\n;&|]*\bof\s*=\s*/dev/|\btee\s+(?:-a\s+)?/dev/(?:sd|hd|vd|nvme|mmcblk|xvd)|>\s*/dev/(?:sd|hd|vd|nvme|mmcblk|xvd)""")),
        Rule("擦除设备签名（wipefs）", Regex("""\bwipefs\b""")),
        Rule("停止/重启系统服务（systemctl stop|restart|disable）", Regex("""\bsystemctl\s+(?:stop|restart|disable|mask|kill|isolate)\b""")),
        Rule("重启/关机（reboot|shutdown|poweroff|halt）", Regex("""\b(?:reboot|shutdown|poweroff|halt|init\s+[06])\b""")),
        Rule("全局可写权限（chmod -R 777）", Regex("""\bchmod\s+(?:-[A-Za-z]*R[A-Za-z]*\s+)?777\b""")),
        Rule("删除数据库（DROP DATABASE）", Regex("""\bdrop\s+(?:database|schema)\b""", RegexOption.IGNORE_CASE)),
        Rule("清空/截断文件（truncate）", Regex("""\btruncate\b""")),
        Rule("删除容器/镜像（docker rm|rmi|prune）", Regex("""\bdocker\s+(?:rm\b|rmi\b|volume\s+rm\b|system\s+prune\b)""")),
        Rule("清空防火墙规则（iptables -F / nft flush）", Regex("""\biptables\s+-F\b|\bnft\s+flush\s+ruleset\b""")),
        Rule("删除用户/组（userdel|groupdel）", Regex("""\b(?:userdel|groupdel)\b""")),
        Rule("结束 init 进程（kill -9 1）", Regex("""\bkill\s+-9\s+1\b""")),
        Rule("fork 炸弹", Regex(""":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""")),
    )

    /** 命中返回危险摘要，未命中返回 null。 */
    fun dangerOf(cmd: String): String? = RULES.firstOrNull { it.pattern.containsMatchIn(cmd) }?.label

    /** 按 UTF-8 字节截断，避免切断多字节字符；totalBytes 是未截断前的原始字节数。 */
    fun clipUtf8(bytes: ByteArray, limit: Int, totalBytes: Long = bytes.size.toLong()): String {
        if (bytes.isEmpty() && totalBytes == 0L) return "(空)"
        val truncated = totalBytes > limit || bytes.size > limit
        if (!truncated) return String(bytes, Charsets.UTF_8)
        var end = minOf(limit, bytes.size)
        while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xC0) == 0x80) end--
        val text = String(bytes, 0, end, Charsets.UTF_8)
        return "$text\n[已截断：原始 $totalBytes 字节，仅显示前 $end 字节]"
    }
}

/** 有界输出缓冲：远端疯狂输出时只保留前 [limit] 字节，避免 OOM。 */
private class CappedOutputStream(private val limit: Int) : java.io.OutputStream() {
    private val buffer = ByteArrayOutputStream()
    var total: Long = 0L
        private set

    override fun write(b: Int) {
        total += 1
        if (buffer.size() < limit) buffer.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        total += len
        val room = limit - buffer.size()
        if (room > 0) buffer.write(b, off, minOf(room, len))
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

package com.dc.andagent.tools

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.PublicKey
import java.io.StringReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

val SSH_TOOLS = listOf("ssh_connect", "ssh_exec", "ssh_list", "ssh_close")

/** R91.5: 凭据元数据工具（只出元数据，永不回 secret）。 */
val SSH_CREDENTIAL_TOOLS = listOf("credential_list", "credential_delete")

/**
 * R98: SSH 远程运维（sshj）。
 *
 * 会话池跨工具调用存活：ssh_connect 建连后把 sshj [SSHClient] 放进
 * [SshSessionPool]，ssh_exec 按 session_id（或 nickname）取用。known_hosts
 * 存在应用私有目录，采用 TOFU（首次使用即信任）：首次连接只探测并返回
 * SHA256 指纹，用户确认后写入 known_hosts；之后 StrictHostKeyChecking=yes，
 * 指纹不匹配立即拒连。
 */
class SshTool(
    private val context: Context,
    override val name: String,
    credentialStore: SshCredentialStore? = null,
) : Tool {
    private val injectedCredentials = credentialStore
    override val description = when (name) {
        "ssh_connect" ->
            "连接远程服务器。凭据用 credential=nickname 引用本机加密保存的密码/私钥（密码与私钥永不进入对话）。首次连接会返回主机密钥 SHA256 指纹并要求确认，确认后以 confirm=true 重试；成功后返回 session_id，供 ssh_exec 使用。"
        "ssh_exec" ->
            "在已连接的 SSH 会话执行命令，返回退出码与截断后的 stdout/stderr。rm -rf、mkfs、dd、systemctl restart、reboot 等破坏性命令需要 confirm=true，必须先向用户说明影响并取得确认。"
        "ssh_list" -> "列出当前活跃的 SSH 会话：session_id、主机、用户、连接时长。"
        else -> "断开并清理指定 SSH 会话。"
    }

    override val destructive = name == "ssh_connect"

    override val parameters = when (name) {
        "ssh_connect" -> schemaOf(
            "credential" to strParam("已保存凭据的 nickname（推荐）：主机、端口、用户名、认证方式与密码/私钥都从本机加密存储读取，密码永不进入对话"),
            "host" to strParam("主机名或 IP（使用 credential 时可省略；两者都无则返回录入引导）"),
            "port" to intParam("端口，默认 22"),
            "user" to strParam("登录用户名（使用 credential 时可省略）"),
            "auth" to strParam("兼容旧 key_path 模式：key"),
            "key_path" to strParam("兼容旧模式：私钥文件绝对路径（不推荐，路径会进入对话；建议改用 credential）"),
            "nickname" to strParam("可选：会话别名，之后可代替 session_id 引用"),
            "confirm" to boolParam("首次连接确认指纹后传 true"),
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

    /** 生产用真实 Keystore 存储；单测可注入假 cipher 的实例。 */
    private val credentials: SshCredentialStore by lazy {
        injectedCredentials ?: SshCredentialStore(appContext)
    }

    override suspend fun execute(args: JsonObject): String = try {
        when (name) {
            "ssh_connect" -> connect(args)
            "ssh_exec" -> exec(args)
            "ssh_list" -> list()
            "ssh_close" -> close(args)
            else -> "ERROR: 未知 SSH 工具"
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // never-crash 纪律（review 必修项②）：凭据读取、known_hosts IO、指纹编码
        // 等任何异常都不允许逃逸出工具边界，统一收敛为 ERROR 返回。
        "ERROR: ${e.message ?: e.javaClass.simpleName}"
    }

    /* ----------------------------- connect ----------------------------- */

    private suspend fun connect(args: JsonObject): String = withContext(Dispatchers.IO) {
        // R91.5 铁律：凭据永不过模型。旧习惯带 password/key_passphrase 一律拒绝，
        // 且不回显参数值——拒绝文案是固定字符串。
        if (args.strArg("password") != null || args.strArg("key_passphrase") != null) {
            return@withContext PLAINTEXT_SECRET_REFUSAL
        }
        val credentialName = args.strArg("credential")
        val credential = credentialName?.let { nick ->
            credentials.get(nick) ?: return@withContext missingCredentialMessage(nick)
        }
        val nickname = args.strArg("nickname")
        if (nickname != null && (nickname.length > 32 || !nickname.matches(Regex("[^\\s]+")))) {
            return@withContext "ERROR: nickname 不能包含空白且最长 32 字符"
        }

        val host: String
        val port: Int
        val user: String
        val auth: String
        val secret: String
        val keyPath: String
        if (credential != null) {
            // 参数显式给了与凭据不同的目标时必须拒绝：不能把一份已存凭据发到另一台主机。
            credentialConflict(args, credential)?.let { return@withContext it }
            host = credential.meta.host
            port = credential.meta.port
            user = credential.meta.user
            auth = credential.meta.authType
            secret = credential.secret
            keyPath = ""
        } else {
            host = args.strArg("host") ?: return@withContext missingCredentialMessage(null)
            port = args.intArg("port") ?: 22
            user = args.strArg("user") ?: return@withContext missingCredentialMessage(null)
            // 兼容旧 key_path 模式：路径敏感度低于密码，保留但战报登记残留风险。
            keyPath = args.strArg("key_path").orEmpty()
            if (keyPath.isEmpty()) return@withContext missingCredentialMessage(null)
            auth = "key"
            secret = ""
        }
        if (host.length > 253 || !host.matches(Regex("[^\\s/\\\\]+"))) return@withContext "ERROR: host 格式无效"
        if (port !in 1..65535) return@withContext "ERROR: port 必须在 1..65535"
        if (user.length > 64 || !user.matches(Regex("[^\\s]+"))) return@withContext "ERROR: user 格式无效"
        if (auth != "password" && auth != "key") return@withContext "ERROR: 认证方式只能是 password 或 key"
        if (auth == "password" && secret.isEmpty()) return@withContext missingCredentialMessage(credentialName)
        if (auth == "key" && keyPath.isEmpty() && secret.isEmpty()) return@withContext missingCredentialMessage(credentialName)
        if (SshSessionPool.size() >= MAX_SESSIONS) return@withContext "ERROR: 活跃会话已达上限 $MAX_SESSIONS，请先 ssh_close 释放"

        val keyId = knownHostId(host, port)
        val remembered = SshHostKeys.hasHost(knownHostsFile(), host, port)
        val confirm = args.boolArg("confirm") == true
        if (!remembered) {
            var probedKey: PublicKey? = null
            val probe = SSHClient()
            probe.connectTimeout = 10_000
            probe.timeout = 10_000
            probe.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    probedKey = key
                    return true
                }
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            try { probe.connect(host, port) }
            catch (e: Exception) { runCatching { probe.disconnect() }; return@withContext connectError(e) }
            finally { runCatching { probe.disconnect() } }
            val hostKey = probedKey ?: return@withContext "ERROR: 服务器未提供主机密钥"
            val keyType = SshHostKeys.type(hostKey)
            val fingerprint = SshHostKeys.fingerprint(hostKey)
            if (!confirm) {
                SshSessionPool.setPendingFingerprint(keyId, fingerprint)
                return@withContext buildString {
                    appendLine("CONFIRM_REQUIRED: 首次连接 $user@$host:$port，主机密钥尚未信任。")
                    appendLine("主机密钥: $keyType $fingerprint")
                    appendLine("请向用户展示该 SHA256 指纹并确认来源可信；用户确认后以 confirm=true 重试。")
                    append("若指纹与服务器管理员提供的不一致，请拒绝连接。")
                }
            }
            if (!SshSessionPool.consumePendingFingerprintIfMatches(keyId, fingerprint)) {
                return@withContext "ERROR: 本次探测到的指纹与确认提示不一致（可能中间人攻击或服务端刚刚更换密钥），已拒绝连接。请重新发起 ssh_connect 获取新指纹。"
            }
            appendKnownHost(keyId, keyType, SshHostKeys.base64(hostKey))
        }

        var acceptedKey: PublicKey? = null
        val session = try {
            openSession(host, port, user, auth, secret, keyPath) { hostname, remotePort, key ->
                val accepted = SshHostKeys.verify(knownHostsFile(), hostname, remotePort, key)
                if (accepted) acceptedKey = key
                accepted
            }
        } catch (e: Exception) {
            return@withContext connectError(e, knownHost = true)
        }
        val hostKey = acceptedKey
        val fingerprint = hostKey?.let(SshHostKeys::fingerprint) ?: "(未知)"
        val keyType = hostKey?.let(SshHostKeys::type) ?: "(未知)"
        val info = try {
            SshSessionPool.registry.add(host, port, user, nickname)
        } catch (e: IllegalArgumentException) {
            session.disconnect()
            return@withContext "ERROR: ${e.message}"
        }
        try {
            SshSessionPool.put(info.id, SshSessionPool.Live(session), appContext)
        } catch (e: Exception) {
            SshSessionPool.registry.remove(info.id)
            session.disconnect()
            return@withContext "ERROR: 无法启动 SSH 后台保活：${e.message ?: e.javaClass.simpleName}"
        }
        buildString {
            appendLine("已连接 $user@$host:$port${nickname?.let { "（$it）" } ?: ""}")
            appendLine("session_id=${info.id}")
            appendLine("host_key=$keyType $fingerprint")
            appendLine("已开启后台保活")
            credentialName?.let { appendLine("credential=$it（密码/私钥未进入对话）") }
            if (credential == null && keyPath.isNotEmpty()) {
                appendLine("注意：本次使用旧的 key_path 文件路径认证（兼容模式）；路径已出现在对话中，建议改为在「设置 → SSH 凭据」保存后以 credential 引用。")
            }
            append("下一步：ssh_exec 执行命令；ssh_list 查看会话；ssh_close 断开。")
            // R124 a: 连接成功后检测电池优化白名单；未白名单时尾部附双语提示。
            BackgroundSurvival.whitelistHint(appContext)?.let { appendLine(); append(it) }
        }
    }

    /** credential 与显式参数冲突时返回错误（避免把凭据发到另一台主机）。 */
    private fun credentialConflict(args: JsonObject, credential: SshCredential): String? {
        val name = credential.meta.nickname
        val explicitHost = args.strArg("host")
        if (explicitHost != null && !explicitHost.equals(credential.meta.host, ignoreCase = true)) {
            return "ERROR: 凭据「$name」绑定 host=${credential.meta.host}，与参数 host=$explicitHost 不一致。为避免把已保存凭据发往其他主机，已取消连接。"
        }
        val explicitUser = args.strArg("user")
        if (explicitUser != null && explicitUser != credential.meta.user) {
            return "ERROR: 凭据「$name」绑定 user=${credential.meta.user}，与参数 user=$explicitUser 不一致。请只传 credential，或使用对应凭据。"
        }
        val explicitPort = args.intArg("port")
        if (explicitPort != null && explicitPort != credential.meta.port) {
            return "ERROR: 凭据「$name」绑定 port=${credential.meta.port}，与参数 port=$explicitPort 不一致。"
        }
        val explicitAuth = args.strArg("auth")?.lowercase()
        if (explicitAuth != null && explicitAuth != credential.meta.authType) {
            return "ERROR: 凭据「$name」的认证方式是 ${credential.meta.authType}，与参数 auth=$explicitAuth 不一致。"
        }
        return null
    }

    /** 没有可用凭据时的引导：告诉 agent 让用户在原生界面录入，别索要密码。 */
    private fun missingCredentialMessage(nickname: String?): String = buildString {
        if (nickname != null) appendLine("ERROR: 凭据「$nickname」不存在或无法解密（文件损坏 / Keystore 密钥已失效）。")
        appendLine("需要用户提供凭据，但密码不能通过对话传递。")
        appendLine("请在手机上打开「设置 → SSH 凭据」（或浏览器访问 andagent://credentials）录入 nickname / 主机 / 端口 / 用户名 / 密码或私钥；录入内容只在原生界面与加密存储里。")
        append("也可以直接对用户说「请设置 ${nickname ?: "myvps"} 的凭据」，等用户在原生界面保存后再重试 ssh_connect（credential=${nickname ?: "<nickname>"}）。")
    }

    private fun openSession(
        host: String, port: Int, user: String, auth: String, secret: String, keyPath: String,
        verifier: (String, Int, PublicKey) -> Boolean,
    ): SSHClient {
        val client = SSHClient()
        client.connectTimeout = 10_000
        // R97b: 10s read timeout 对大文件 SFTP 传输太短（一次 socket read 可能合法
        // 超过 10s——弱网长肥管道），但完全无超时会在服务器/网络异常时永久卡死
        // （用户报腾讯云「基本都卡住」）。取 45s：正常传输单次 read 远小于此，
        // 真死锁 45s 内必然中止抛 SocketTimeoutException。
        client.timeout = 45_000
        // sshj 三层超时各自独立：transport / connection / sftp engine。
        // client.timeout 只传播到 transport；ConnectionImpl 在 SSHClient 构造时
        // 拷贝 transport 的**当时值**且不联动，漏设时 channel window 扩容
        // （Window.Remote）与 global request 用默认值等待，大输出/慢网络容易失败。
        // 三层显式统一为 45s（连接前设置，connect 会再覆盖 transport 为同值）。
        client.connection.setTimeoutMs(45_000)
        // 压缩对广域网吞吐有额外收益（R97b 的 1MB 窗口 + zlib 组合）
        client.useCompression()
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey) = verifier(hostname, port, key)
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                SshHostKeys.algorithms(knownHostsFile(), hostname, port)
        })
        try {
            client.connect(host, port)
            SshAuthenticator.authenticate(client, user, auth, secret, keyPath, MAX_KEY_BYTES)
            client.connection.keepAlive.keepAliveInterval = 15
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    private fun connectError(e: Exception, knownHost: Boolean = false): String {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("auth fail") || lower.contains("auth cancel") ->
                "ERROR: SSH 认证失败，请检查用户名、密码或私钥。"
            lower.contains("unknownhostkey") || lower.contains("unknown host key") ->
                "ERROR: 服务器提供的主机密钥类型不在 known_hosts 中（可能服务端更换了密钥算法）。如确认无误，请删除应用私有目录 ssh/known_hosts 中该主机的记录后重连。"
            lower.contains("changed") || lower.contains("reject hostkey") || (knownHost && (lower.contains("hostkey") || lower.contains("host key"))) ->
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
        val cancelKey = SshTransferCancel.key(ref, cmd)
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
        var deadSession = false
        var failure: String? = null
        try {
            synchronized(session) {
                if (!session.isConnected) {
                    deadSession = true
                } else {
                    if (SshTransferCancel.isCancelled(cancelKey)) throw IOException("已被用户中断")
                    session.startSession().use { channel ->
                        channel.exec(cmd).use { command ->
                            val stdout = Thread { command.inputStream.use { it.copyTo(out) } }
                            val stderr = Thread { command.errorStream.use { it.copyTo(err) } }
                            stdout.start(); stderr.start()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS.toLong())
                            while (command.isOpen && System.nanoTime() < deadline) {
                                if (SshTransferCancel.isCancelled(cancelKey)) {
                                    command.close()
                                    break
                                }
                                // sshj 的 Channel.join(timeout) 没有布尔返回值：通道仍打开时
                                // 它直接抛 ConnectionException(TimeoutException)「Timeout expired:
                                // 200 MILLISECONDS」。裸调用会让任何超过 200ms 的命令立刻报超时，
                                // 所以只吞掉「等待超时」，真正的连接错误继续上报。
                                try {
                                    command.join(200, TimeUnit.MILLISECONDS)
                                } catch (e: ConnectionException) {
                                    if (e.cause !is java.util.concurrent.TimeoutException) throw e
                                }
                            }
                            timedOut = command.isOpen
                            if (timedOut) command.close()
                            stdout.join(1_000); stderr.join(1_000)
                            exit = if (timedOut) -1 else command.exitStatus ?: -1
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 失败原因必须保留：执行异常不能伪装成「通道提前关闭」的正常输出。
            failure = e.message ?: e.javaClass.simpleName
            if (!session.isConnected) deadSession = true
        }
        val cancelled = SshTransferCancel.isCancelled(cancelKey)
        SshTransferCancel.clear(cancelKey)
        // 锁序纪律：removeByRef 拿池锁再等 session 锁，绝不能在持有 session 锁时
        // 调用——断会话早退与异常路径都已退出 synchronized 块，在此统一清理。
        if (deadSession) SshSessionPool.removeByRef(ref)
        if (cancelled) return@withContext "ERROR: 已被用户中断"
        if (deadSession && exit == -1 && !timedOut && failure != null) {
            // R124 d: 断线自愈收口——不做后台静默重连（凭据解密触发面扩大），
            // 改为引导模型用已加密保存的凭据重新 ssh_connect，等效恢复且不过任何凭据。
            return@withContext "ERROR: 会话已断开（$ref）：命令执行失败：$failure\n${BackgroundSurvival.SSH_RECONNECT_HINT}"
        }
        if (deadSession && exit == -1 && !timedOut) {
            return@withContext "ERROR: 会话已断开：$ref\n${BackgroundSurvival.SSH_RECONNECT_HINT}"
        }
        if (failure != null && exit == -1 && !timedOut) {
            return@withContext buildString {
                appendLine("ERROR: 命令执行失败：$failure（已捕获的部分输出如下）")
                appendLine("--- stdout ---")
                appendLine(SshCommandGuard.clipUtf8(out.toByteArray(), STDOUT_LIMIT, out.total))
                append("--- stderr ---\n")
                append(SshCommandGuard.clipUtf8(err.toByteArray(), STDERR_LIMIT, err.total))
            }
        }
        // exec 正常结束（无 exit_code 且未超时——如通道被服务端提前关闭）也返回
        // 已捕获的输出，而不是落到 withContext lambda 的 Unit。
        return@withContext if (exit != -1 || timedOut) buildString {
            appendLine("exit_code=$exit")
            if (timedOut) appendLine("timeout=true（超过 ${timeoutS}s，已强制断开通道）")
            if (danger != null) appendLine("confirmed=$danger")
            appendLine("--- stdout ---")
            appendLine(SshCommandGuard.clipUtf8(out.toByteArray(), STDOUT_LIMIT, out.total))
            append("--- stderr ---\n")
            append(SshCommandGuard.clipUtf8(err.toByteArray(), STDERR_LIMIT, err.total))
        } else buildString {
            appendLine("exit_code=-1（通道在返回退出码前关闭）")
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
        // R91b: 会话关闭必须级联停掉该会话的全部端口转发（teardown 内执行）。
        val tunnels = SshTunnels.countForSession(info.id)
        // close() 先摘登记再断开：不会因为在该会话上跑着大文件传输而阻塞其它工具。
        SshSessionPool.close(info.id)
        return buildString {
            append("已断开 SSH 会话 ${info.id}（${info.user}@${info.host}:${info.port}）")
            if (tunnels > 0) append("，并停止 $tunnels 条端口转发")
        }
    }

    /* ---------------------------- storage ------------------------------ */

    private fun knownHostsFile(): File = File(File(appContext.filesDir, "ssh"), "known_hosts")

    /** OpenSSH known_hosts 的主机字段：非 22 端口写成 [host]:port。 */
    private fun knownHostId(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

    private fun appendKnownHost(keyId: String, type: String, keyBase64: String) {
        val file = knownHostsFile()
        file.parentFile?.mkdirs()
        // 同一主机重复行会保留旧值，先清掉旧记录再写。
        val kept = if (file.isFile) {
            file.readLines().filter { it.isNotBlank() && it.split(Regex("\\s+")).firstOrNull() != keyId }
        } else {
            emptyList()
        }
        file.writeText((kept + "$keyId $type $keyBase64").joinToString("\n", postfix = "\n"))
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val MAX_KEY_BYTES = 1_048_576L
        private const val MAX_COMMAND_CHARS = 8_000
        private const val STDOUT_LIMIT = 8 * 1024
        private const val STDERR_LIMIT = 4 * 1024

        /** 旧习惯带着 password / key_passphrase 调用时的固定拒绝文案。 */
        const val PLAINTEXT_SECRET_REFUSAL =
            "出于安全，密码不再通过对话传递。请用 nickname 引用已保存的凭据，或在设置 → SSH 凭据 中录入。"
    }
}

/** Authenticates only after the host key has been verified. */
internal object SshAuthenticator {
    fun authenticate(client: SSHClient, user: String, auth: String, secret: String, keyPath: String, maxKeyBytes: Long) {
        if (auth == "password") {
            client.authPassword(user, secret)
            return
        }
        val key = if (keyPath.isNotEmpty()) {
            val file = File(keyPath)
            require(file.isFile && file.canRead()) { "私钥文件不存在或不可读：$keyPath" }
            require(file.length() <= maxKeyBytes) { "私钥文件异常大（>1MB）：$keyPath" }
            client.loadKeys(file.absolutePath)
        } else {
            require(secret.isNotEmpty()) { "缺少私钥内容" }
            require(secret.toByteArray(Charsets.UTF_8).size <= maxKeyBytes) { "私钥内容异常大（>1MB）" }
            val format = KeyProviderUtil.detectKeyFileFormat(StringReader(secret), false)
            val provider = when (format) {
                KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
                KeyFormat.OpenSSH -> OpenSSHKeyFile()
                KeyFormat.PKCS8 -> PKCS8KeyFile()
                else -> throw IllegalArgumentException("不支持的私钥格式：$format")
            }
            provider.init(StringReader(secret))
            provider
        }
        client.authPublickey(user, key)
    }
}

/** OpenSSH known_hosts key encoding and exact host/key verification. */
internal object SshHostKeys {
    fun type(key: PublicKey): String = KeyType.fromKey(key).toString()
    fun blob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData
    fun base64(key: PublicKey): String = Base64.getEncoder().encodeToString(blob(key))
    fun fingerprint(key: PublicKey): String = "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob(key)))

    private fun hostId(host: String, port: Int) = if (port == 22) host else "[$host]:$port"
    private fun entries(file: File): List<List<String>> = if (file.isFile) file.readLines().mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        fields.takeIf { it.size >= 3 && !it[0].startsWith('#') && !it[0].startsWith('@') }
    } else emptyList()

    private fun matches(pattern: String, id: String): Boolean = pattern.split(',').any { item ->
        if (item == id) return@any true
        if (!item.startsWith("|1|")) return@any false
        val parts = item.split('|')
        if (parts.size != 4) return@any false
        runCatching {
            val salt = Base64.getDecoder().decode(parts[2])
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(salt, "HmacSHA1"))
            MessageDigest.isEqual(mac.doFinal(id.toByteArray(Charsets.UTF_8)), Base64.getDecoder().decode(parts[3]))
        }.getOrDefault(false)
    }

    fun hasHost(file: File, host: String, port: Int): Boolean = entries(file).any { matches(it[0], hostId(host, port)) }

    fun algorithms(file: File, host: String, port: Int): List<String> = entries(file)
        .filter { matches(it[0], hostId(host, port)) }.map { it[1] }

    fun verify(file: File, host: String, port: Int, key: PublicKey): Boolean {
        val id = hostId(host, port)
        val expected = base64(key)
        return entries(file).any { matches(it[0], id) && it[1] == type(key) && it[2] == expected }
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

/** 会话元数据（纯 Kotlin，可脱离 sshj 单测）。 */
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
 * 跨工具调用存活的 SSH 会话池。sshj [SSHClient] 与元数据分开存放，
 * 断开时同时清理两者；外部掉线的会话在 list/exec 时惰性剔除。
 */
object SshSessionPool {
    data class Live(val session: SSHClient) {
        var sftpChannel: SFTPClient? = null
    }

    val registry = SshSessionRegistry()
    private val live = LinkedHashMap<String, Live>()
    private val pending = HashMap<String, String>()

    /** 最近活跃的 SSH 会话 id（通知点击 deeplink 用）；无活跃会话返回 null。 */
    fun presenceLatest(): String? = presence.latest
    private var appContext: Context? = null
    private val presence = SshSessionPresence()

    @Synchronized fun put(id: String, session: Live, context: Context) {
        appContext = context.applicationContext
        val tag = "ssh:$id"
        // presence 仍驱动通知 deeplink 的 latest；保活引用计数改由 KeepAliveRefs 统一（SSH+ADB 共用）。
        val first = presence.put(id)
        try {
            // R124 收口（Codex review 必修 3）：acquire 也纳入 try——前台服务启动抛异常时
            // 上抛给调用方决定首条连接是否拒绝；幂等：服务已在跑时只是再走一次 onStartCommand。
            // 旧实现只在“从空到非空”调一次，服务一旦被系统停掉就再也拉不起来，保活静默失效。
            // R124 二轮 review 必修 2：start 动作（ensureStarted）由 KeepAliveRefs.acquire
            // 在锁内统一执行——此处不再二次裸调（重复调用无意义，且其异常会误判成功路径）。
            // R124 三轮 review 必修：acquire 失败（start 抛异常）时**保留引用登记、不回滚、
            // 也不拒会话**——引用代表「该会话需要保活」这个事实，与会话同生共死（remove 时
            // release）；此刻服务拉不起来但引用在位，后续任何 acquire/repair（含服务死亡
            // markServiceDead 后）都会自愈重拉。若此处回滚引用却仍接纳会话，其它会话关闭
            // 后引用归零误停保活，而这条新会话实际还在用——正是二轮要堵的洞的镜像。
            KeepAliveRefs.acquire(tag)
        } catch (e: Exception) {
            // never-crash：保活重启失败不拒绝 SSH 连接本身（连接可用性优先于保活），
            // 引用已在 acquire 内登记；后续任何 acquire/repair（含服务死亡复位后）自愈重拉。
            if (first) {
                // R124 四轮 review：首条连接（从无到有）失败时该会话并未被接纳（live 未写入），
                // 上抛拒绝前必须摘除 presence 与引用——否则拒绝后残留孤儿引用：计数永不归零、
                // 保活服务永不停（引用语义 = 「会话需要保活」，而此刻并没有任何会话在池里）。
                presence.remove(id)
                runCatching { KeepAliveRefs.release(tag) }
                throw e
            }
        }
        live[id] = session
    }

    /**
     * 只摘除登记（registry / live / presence），不做断链与通道清理。
     * 必须与 [close] 分离：清理要拿会话锁，而 SFTP 传输会长时间持有它，
     * 放在池锁内会堵住所有其它 ssh_* 调用。
     */
    @Synchronized fun remove(id: String): Live? {
        registry.remove(id)
        val removed = live.remove(id)
        presence.remove(id)
        // R124 b: SSH+ADB 共用引用计数——只有两类会话都清空才停服。
        // R124 收口（Codex review 必修 2）：release 返回 true 时已在 KeepAliveRefs 锁内
        // 串行执行 stop 动作，此处不再二次 stopService——两次 stop 之间 ADB 可能已取得
        // 引用并拉起服务，二次 stop 会误停仍被 ADB 使用的保活。
        KeepAliveRefs.release("ssh:$id")
        return removed
    }

    /**
     * 关闭会话：先摘登记，再在池锁外 [teardown]。
     * 旧实现把 `synchronized(session)` 套在 `remove()`（池锁）里，而一次大文件
     * SFTP 传输会持续持锁数分钟——「断开」于是要排队等传输跑完，且期间
     * ssh_exec / ssh_list / 资源中心快照全部堵在池锁上（资源中心是同步 JS 桥
     * 调用，会直接卡死页面）。
     */
    fun close(id: String): Live? = remove(id)?.also { teardown(id, it) }

    /** 关闭会话，ref 支持 session_id 与 nickname（与 ssh_exec / ssh_close 语义一致）。 */
    fun closeByRef(ref: String): Live? = registry.resolve(ref)?.let { close(it.id) }

    /** 断开连接并清理随会话存活的通道；调用方不得持有池锁。 */
    private fun teardown(id: String, entry: Live) {
        // 先 disconnect：立刻打断在途 ssh_exec / SFTP 传输，让它们尽快从会话锁里退出。
        runCatching { entry.session.disconnect() }
        // R91b: 会话消失必须级联停掉该会话的全部端口转发——sshj 的 disconnect 只关
        // 本地 listener，SshTunnels 里的登记与远端 forwarding 句柄仍需显式摘除，
        // 否则 ssh_tunnel_list 会留下指向死会话的僵尸条目。
        SshTunnels.stopSession(id)
        synchronized(entry.session) {
            runCatching { entry.sftpChannel?.close() }
            entry.sftpChannel = null
            SshTransferTool.forgetHome(entry.session)
        }
    }

    fun removeByRef(ref: String): Live? = closeByRef(ref)

    @Synchronized fun resolve(ref: String): Live? {
        val info = registry.resolve(ref) ?: return null
        return live[info.id]
    }

    @Synchronized fun size(): Int = registry.size()

    @Synchronized fun pendingFingerprint(keyId: String): String? = pending[keyId]

    @Synchronized fun setPendingFingerprint(keyId: String, fingerprint: String) {
        pending[keyId] = fingerprint
    }

    /**
     * 原子核对并消费 TOFU 待确认指纹：仅当 pending 与本次探测指纹一致时
     * 清除 pending 并返回 true。一致即消费（写库成功后无需重复确认），
     * 不一致保留待确认值，避免并发探测相互覆盖后拿陈旧指纹通过核对。
     */
    @Synchronized
    fun consumePendingFingerprintIfMatches(keyId: String, fingerprint: String): Boolean {
        if (pending[keyId] == fingerprint) {
            pending.remove(keyId)
            return true
        }
        return false
    }

    @Synchronized fun clearPendingFingerprint(keyId: String) {
        pending.remove(keyId)
    }

    /** 剔除 sshj 已判定断开的会话，返回剩余数量。锁只用于取快照，断开在锁外做。 */
    fun pruneDisconnected(): Int {
        val dead = synchronized(this) { live.filterValues { !it.session.isConnected }.keys.toList() }
        dead.forEach { close(it) }
        return size()
    }
}

/** Pure transition logic for the foreground service's reference count. */
class SshSessionPresence {
    private val ids = HashSet<String>()
    @Volatile var latest: String? = null
        private set
    fun put(id: String): Boolean { val wasEmpty = ids.isEmpty(); ids.add(id); latest = id; return wasEmpty && ids.isNotEmpty() }
    fun remove(id: String): Boolean { val existed = ids.remove(id); if (existed && latest == id) latest = ids.firstOrNull(); return existed && ids.isEmpty() }
    fun size(): Int = ids.size
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
        Rule("停止/重启系统服务（systemctl stop|restart|disable）", Regex("""\bsystemctl\s+(?:-{1,2}[A-Za-z-]+\s+)*(?:stop|restart|disable|mask|kill|isolate)\b""")),
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

    /**
     * 按 UTF-8 字节截断，避免切断多字节字符；totalBytes 是未截断前的原始字节数。
     * 正向扫描字符边界：只保留完整落在 limit 内的字符，任何情况下输出都是
     * 合法 UTF-8（不产生 U+FFFD），包括 buffer 恰好停在字符中间的边界情形。
     */
    fun clipUtf8(bytes: ByteArray, limit: Int, totalBytes: Long = bytes.size.toLong()): String {
        if (bytes.isEmpty() && totalBytes == 0L) return "(空)"
        val truncated = totalBytes > limit || bytes.size > limit
        if (!truncated) return String(bytes, Charsets.UTF_8)
        var end = 0
        while (end < bytes.size) {
            val b = bytes[end].toInt() and 0xFF
            val len = when {
                b and 0x80 == 0 -> 1          // 0xxxxxxx
                b and 0xE0 == 0xC0 -> 2       // 110xxxxx
                b and 0xF0 == 0xE0 -> 3       // 1110xxxx
                b and 0xF8 == 0xF0 -> 4       // 11110xxx
                else -> -1                    // 非法首字节/孤立续字节：停在有效前缀
            }
            if (len <= 0 || end + len > limit || end + len > bytes.size) break
            end += len
        }
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

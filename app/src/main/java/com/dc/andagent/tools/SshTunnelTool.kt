package com.dc.andagent.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom

val SSH_TUNNEL_TOOLS = listOf("ssh_tunnel_start", "ssh_tunnel_list", "ssh_tunnel_stop")

/**
 * R91b: SSH 端口转发（L/R/D）。
 *
 * 端口校验、上限与登记簿是纯 Kotlin（[SshTunnelRules] / [SshTunnelRegistry] /
 * [SshTunnelPool]），可脱离 sshj 单测；只有 [SshTunnelTool] 里的建链部分接触
 * sshj。转发工具不接触凭据——schema 里没有 password/secret，任何凭据仍只在
 * ssh_connect 阶段由 SshCredentialStore 解密使用。
 *
 * API 口径（sshj 0.41.2 实测，jar 反查而非凭记忆）：
 * - L：`SSHClient.newLocalPortForwarder(Parameters, ServerSocket).listen()`
 * - R：`SSHClient.remotePortForwarder.bind(Forward(port), ConnectListener)`
 * - D：0.41.2 的 jar 里没有 DynamicPortForwarder / SOCKS 相关类，
 *   `net.schmizz.sshj.connection.channel.direct` 只有 DirectTCPIPChannel；
 *   因此 D 按简报的可证伪条款返回固定错误（见 [SshTunnelTool.D_MODE_UNSUPPORTED]），
 *   不做伪实现。
 */
class SshTunnelTool(override val name: String) : Tool {
    override val description = when (name) {
        "ssh_tunnel_start" ->
            "在已连接的 SSH 会话上建立端口转发。mode=L 本地转发：把服务器可达的 remote_host:remote_port 映射到手机 127.0.0.1:local_port；mode=R 远程转发：把手机侧 local_host:local_port（默认 127.0.0.1）暴露到服务器 remote_port；mode=D 动态 SOCKS5 本版 sshj 不支持，会返回固定错误与替代方案。端口只允许 1024..65535，每会话最多 8 条、全局最多 16 条；本地监听只绑 127.0.0.1。对外暴露端口属敏感操作，需用户确认。"
        "ssh_tunnel_list" -> "列出活跃的 SSH 端口转发：tunnel_id、会话、模式、端点信息与建立时长。"
        else -> "停止 SSH 端口转发：按 tunnel_id 停单条；all=true 且 confirm=true 时停止指定会话的全部转发。"
    }

    override val destructive = name == "ssh_tunnel_start"

    override val parameters = when (name) {
        "ssh_tunnel_start" -> schemaOf(
            "session_id" to strParam("ssh_connect 返回的 session_id，或连接时的 nickname"),
            "mode" to strParam("转发模式：L（本地转发）/ R（远程转发）/ D（动态 SOCKS5，本版不支持）"),
            "local_port" to intParam("手机侧端口，只允许 1024..65535（拒绝 <1024 特权端口）"),
            "remote_host" to strParam("L 模式：经 SSH 服务器到达的目标主机（如 10.0.0.5）"),
            "remote_port" to intParam("L 模式：目标端口；R 模式：SSH 服务器侧监听端口，均只允许 1024..65535"),
            "local_host" to strParam("R 模式：手机侧目标主机，默认 127.0.0.1"),
            required = listOf("session_id", "mode"),
        )
        "ssh_tunnel_list" -> schemaOf()
        else -> schemaOf(
            "tunnel_id" to strParam("ssh_tunnel_start 返回的 tunnel_id"),
            "session_id" to strParam("all=true 时必填：会话 id 或 nickname"),
            "all" to boolParam("true 时停止该会话全部转发（需同时 confirm=true）"),
            "confirm" to boolParam("all=true 时确认停止该会话全部转发"),
        )
    }

    override suspend fun execute(args: JsonObject): String = try {
        when (name) {
            "ssh_tunnel_start" -> start(args)
            "ssh_tunnel_list" -> list()
            "ssh_tunnel_stop" -> stop(args)
            else -> "ERROR: 未知 SSH 转发工具"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // never-crash：监听/绑定/sshj 的任何异常都不允许逃逸工具边界。
        "ERROR: ${e.message ?: e.javaClass.simpleName}"
    }

    /* ------------------------------- start ------------------------------ */

    private suspend fun start(args: JsonObject): String = withContext(Dispatchers.IO) {
        val ref = args.strArg("session_id") ?: return@withContext "ERROR: 缺少 session_id"
        val mode = SshTunnelRules.mode(args.strArg("mode"))
            ?: return@withContext "ERROR: mode 必须是 L（本地转发）、R（远程转发）或 D（动态 SOCKS5）"
        // 可证伪条款：jar 勘察结论是 0.41.2 无 SOCKS5 监听 API，固定错误而不是伪造实现。
        if (mode == "D") return@withContext D_MODE_UNSUPPORTED

        val session = SshSessionPool.registry.resolve(ref)
            ?: return@withContext "ERROR: 会话不存在或已断开：$ref（用 ssh_list 查看活跃会话）"
        val live = SshSessionPool.resolve(session.id)
            ?: return@withContext "ERROR: 会话不存在或已断开：$ref（用 ssh_list 查看活跃会话）"
        if (!live.session.isConnected) {
            SshSessionPool.removeByRef(session.id)
            return@withContext "ERROR: 会话已断开：${session.id}（请重新 ssh_connect）"
        }

        val localPort: Int
        val remotePort: Int
        val localHost: String
        val remoteHost: String?
        if (mode == "L") {
            val localPortArg = args.intArg("local_port")
            val remotePortArg = args.intArg("remote_port")
            SshTunnelRules.portError(localPortArg, "local_port")?.let { return@withContext "ERROR: $it" }
            SshTunnelRules.portError(remotePortArg, "remote_port")?.let { return@withContext "ERROR: $it" }
            val targetHost = args.strArg("remote_host")
            SshTunnelRules.hostError(targetHost, "remote_host")?.let { return@withContext "ERROR: $it" }
            localPort = requireNotNull(localPortArg)
            remotePort = requireNotNull(remotePortArg)
            localHost = LOOPBACK
            remoteHost = requireNotNull(targetHost)
        } else {
            val localPortArg = args.intArg("local_port")
            val remotePortArg = args.intArg("remote_port")
            SshTunnelRules.portError(remotePortArg, "remote_port")?.let { return@withContext "ERROR: $it" }
            SshTunnelRules.portError(localPortArg, "local_port")?.let { return@withContext "ERROR: $it" }
            localPort = requireNotNull(localPortArg)
            remotePort = requireNotNull(remotePortArg)
            localHost = args.strArg("local_host") ?: LOOPBACK
            SshTunnelRules.hostError(localHost, "local_host")?.let { return@withContext "ERROR: $it" }
            remoteHost = null
        }

        // 上限先查一次给出明确文案；register 内还有原子复检（并发下的最终闸）。
        SshTunnels.limitError(session.id)?.let { return@withContext "ERROR: $it" }
        // R91b review SHOULD：L 模式监听线程意外死亡时经此摘除登记（见 startLocal finally 块）。
        // 二轮 review 必修：监听线程可能早于 register() 返回就退出（onDead 读到 null 漏清），
        // 且 registeredId 跨线程无可见性——用 AtomicBoolean dead + AtomicReference id 闭环：
        // ①线程先死：dead=true，登记完成后补查发现即回收；②线程后死：onDead 读到 id 正常回收。
        val listenerDead = java.util.concurrent.atomic.AtomicBoolean(false)
        val registeredId = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val handle = try {
            if (mode == "L") {
                startLocal(live.session, localPort, remoteHost!!, remotePort) { _ ->
                    listenerDead.set(true)
                    registeredId.get()?.let { runCatching { SshTunnels.stop(it) } }
                }
            } else {
                startRemote(live.session, localHost, localPort, remotePort)
            }
        } catch (e: Exception) {
            return@withContext "ERROR: ${bindError(e, mode, localPort, remotePort)}"
        }

        val info = try {
            SshTunnels.register(session.id, mode, localHost, localPort, remoteHost, remotePort, handle)
        } catch (e: IllegalArgumentException) {
            runCatching { handle() }
            return@withContext "ERROR: ${e.message}"
        }
        registeredId.set(info.id)
        // 补查：监听线程在本协程 register 与赋值之间死亡的，这里兜底回收。
        if (listenerDead.get()) {
            SshTunnels.stop(info.id)
            return@withContext "ERROR: 本地监听端口 $localPort 在建立后立即退出，转发未生效（端口可能被占用或被安全软件拦截）"
        }

        // R91b review 必修：register 与并发 ssh_close 的竞态——close 的级联清理
        // （teardown → SshTunnels.stopSession）可能发生在本协程 resolve 之后、
        // register 之前，此时刚登记的转发不在级联范围内，会留下指向死会话的
        // 僵尸条目（L 模式还挂着本地监听，且再也无法用 all=true 停掉）。
        // 登记成功后复查：会话登记已被摘除或连接已断，立即回收刚建的转发。
        val closedDuringSetup = SshSessionPool.registry.get(session.id) == null || !live.session.isConnected
        if (closedDuringSetup) {
            SshTunnels.stop(info.id)
            return@withContext "ERROR: 会话在建立端口转发期间被断开：${session.id}（请重新 ssh_connect 后再建转发）"
        }

        buildString {
            appendLine("已建立端口转发 ${info.id}")
            when (mode) {
                "L" -> appendLine("mode=L  127.0.0.1:${info.localPort} -> ${info.remoteHost}:${info.remotePort}（经 ${session.id}）")
                else -> appendLine("mode=R  ${session.host}:${info.remotePort} -> ${info.localHost}:${info.localPort}（经 ${session.id}）")
            }
            appendLine("该会话转发 ${SshTunnels.countForSession(session.id)}/${SshTunnelRules.MAX_PER_SESSION} 条，全局 ${SshTunnels.size()}/${SshTunnelRules.MAX_TOTAL} 条")
            if (mode == "L") {
                appendLine("用法：手机上的应用访问 127.0.0.1:${info.localPort} 即到达 ${info.remoteHost}:${info.remotePort}；监听仅绑定 loopback，局域网其它设备无法借用。")
            } else {
                appendLine("用法：连到 ${session.host}:${info.remotePort} 的流量会经 SSH 回到手机侧的 ${info.localHost}:${info.localPort}。远程监听地址由 sshj/sshd 默认策略决定。")
            }
            append("停止：ssh_tunnel_stop tunnel_id=${info.id}")
        }
    }

    /** 本地转发：先绑定 127.0.0.1（拒绝 LAN 借用手机当跳板），再起 accept 线程。 */
    private fun startLocal(client: SSHClient, localPort: Int, remoteHost: String, remotePort: Int, onDead: (String) -> Unit): () -> Unit {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), localPort))
            val forwarder = client.newLocalPortForwarder(
                Parameters(LOOPBACK, localPort, remoteHost, remotePort), server,
            )
            val thread = Thread({
                // 停止路径会 close() 监听 socket，accept 抛异常属正常退出。
                try {
                    forwarder.listen()
                } catch (_: Exception) {
                    // never-crash：监听线程异常不向调用方抛出。
                } finally {
                    // R91b review SHOULD：listen() 意外退出（非主动 close）时监听已死，
                    // 登记却仍显示活跃会误导模型。经 onDead 摘除登记；stop 路径已先
                    // remove 登记，onDead 里 remove 返回 null 即幂等无害。
                    runCatching { onDead("L") }
                }
            }, "ssh-tunnel-L-$localPort").apply { isDaemon = true }
            thread.start()
            return {
                // LocalPortForwarder.close() 关监听与 accept 线程；已建立的转发通道随
                // SSH 会话断开结束（ssh_close 会先 disconnect）。
                runCatching { forwarder.close() }
                runCatching { server.close() }
            }
        } catch (e: Exception) {
            runCatching { server.close() }
            throw e
        }
    }

    /** 远程转发：sshj 默认绑定地址（不暴露参数），入站连接转回手机侧 local_host:local_port。 */
    private fun startRemote(client: SSHClient, localHost: String, localPort: Int, remotePort: Int): () -> Unit {
        val forwarder = client.remotePortForwarder
        val forward = forwarder.bind(
            RemotePortForwarder.Forward(remotePort),
            SocketForwardingConnectListener(InetSocketAddress(localHost, localPort)),
        )
        return { runCatching { forwarder.cancel(forward) } }
    }

    private fun bindError(e: Exception, mode: String, localPort: Int, remotePort: Int?): String {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("administratively prohibited") ->
                "服务器禁止端口转发（sshd 的 AllowTcpForwarding/GatewayPorts 限制）：${msg.ifBlank { e.javaClass.simpleName }}"
            mode == "L" && (e is BindException || lower.contains("address already in use")) ->
                "本地端口 $localPort 已被占用或不可用，请换一个端口后重试"
            mode == "R" ->
                "服务器无法监听端口 $remotePort（可能被占用、无权限或 sshd 禁止转发）：${msg.ifBlank { e.javaClass.simpleName }}"
            else -> "建立端口转发失败：${msg.ifBlank { e.javaClass.simpleName }}"
        }
    }

    /* -------------------------------- list ------------------------------ */

    private fun list(): String {
        // 先剔除已断会话：close→teardown 会级联停掉转发并摘除登记。
        SshSessionPool.pruneDisconnected()
        val items = SshTunnels.list()
        if (items.isEmpty()) return "没有活跃的 SSH 端口转发"
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("活跃 SSH 端口转发 ${items.size} 条（每会话上限 ${SshTunnelRules.MAX_PER_SESSION}，全局上限 ${SshTunnelRules.MAX_TOTAL}）：")
            items.forEach { info ->
                val mins = ((now - info.startedAt) / 60_000L).coerceAtLeast(0)
                val session = SshSessionPool.registry.get(info.sessionId)
                append("- ${info.id}  mode=${info.mode}  ")
                when (info.mode) {
                    "L" -> append("127.0.0.1:${info.localPort} -> ${info.remoteHost}:${info.remotePort}")
                    else -> append("${session?.host ?: info.sessionId}:${info.remotePort} -> ${info.localHost}:${info.localPort}")
                }
                append("  会话=${info.sessionId}")
                session?.nickname?.let { append("（$it）") }
                append("  已建立 ${mins} 分钟\n")
            }
        }.trimEnd()
    }

    /* -------------------------------- stop ------------------------------ */

    private fun stop(args: JsonObject): String {
        if (args.boolArg("all") == true) {
            val ref = args.strArg("session_id")
                ?: return "ERROR: all=true 时必须提供 session_id（停止该会话全部转发）"
            val session = SshSessionPool.registry.resolve(ref)
                ?: return "ERROR: 会话不存在或已断开：$ref"
            if (args.boolArg("confirm") != true) {
                val count = SshTunnels.countForSession(session.id)
                if (count == 0) return "会话 ${session.id} 没有活跃的端口转发"
                return "CONFIRM_REQUIRED: 将停止会话 ${session.id} 的全部 $count 条端口转发，正在使用这些隧道的连接会中断。请向用户确认后带 confirm=true 重试。"
            }
            val stopped = SshTunnels.stopSession(session.id)
            return if (stopped == 0) "会话 ${session.id} 没有活跃的端口转发"
            else "已停止会话 ${session.id} 的 $stopped 条端口转发"
        }
        val id = args.strArg("tunnel_id")
            ?: return "ERROR: 缺少 tunnel_id（停单条）；或用 all=true + session_id + confirm=true 停止整个会话的转发"
        val info = SshTunnels.get(id)
            ?: return "ERROR: 端口转发不存在或已停止：$id（用 ssh_tunnel_list 查看）"
        return if (SshTunnels.stop(id)) "已停止端口转发 ${info.id}（mode=${info.mode}）"
        else "ERROR: 端口转发不存在或已停止：$id"
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /**
         * D 模式固定错误。依据：sshj 0.41.2 jar 的
         * `net.schmizz.sshj.connection.channel.forwarded` 只有 RemotePortForwarder /
         * SocketForwardingConnectListener / X11Forwarder，`direct` 包只有
         * DirectTCPIPChannel，没有 DynamicPortForwarder；`SSHClient` 也没有
         * newDynamicPortForwarder。自建 SOCKS5 握手 + Direct channel 转发明显超出本轮。
         */
        const val D_MODE_UNSUPPORTED =
            "ERROR: 本版不支持 D（动态 SOCKS5）转发：sshj 0.41.2 没有 SOCKS5 监听 API（无 DynamicPortForwarder），自建握手超出本轮范围。替代方案：① 多个目标分别用 L 模式建本地转发；② 在服务器上运行 ssh -D 1080 或 microsocks/dante 等 SOCKS5 代理，再用 L 模式把该 SOCKS 端口映射到手机 127.0.0.1。"
    }
}

/* ------------------------------- rules ------------------------------- */

/** 端口/主机校验纯函数：错误文案直接面向模型与用户。 */
object SshTunnelRules {
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535
    const val MAX_PER_SESSION = 8
    const val MAX_TOTAL = 16

    private val MODES = setOf("L", "R", "D")
    private val ID_PATTERN = Regex("^tun-[a-z0-9]{8}$")

    fun portError(port: Int?, label: String): String? = when {
        port == null -> "缺少 $label"
        port in MIN_PORT..MAX_PORT -> null
        // 拒绝特权端口：Android 应用即使能 bind，也不应把手机变成低端口跳板。
        port in 1 until MIN_PORT -> "$label=$port 是特权端口（<1024）。为避免提权风险，只允许 $MIN_PORT..$MAX_PORT"
        else -> "$label=$port 超出 $MIN_PORT..$MAX_PORT 范围"
    }

    fun hostError(host: String?, label: String): String? = when {
        host.isNullOrBlank() -> "缺少 $label"
        host.length > 253 -> "$label 过长（上限 253 字符）"
        host.any { it.isWhitespace() || it == '/' || it == '\\' || it == '\u0000' } -> "$label 格式无效"
        else -> null
    }

    fun mode(raw: String?): String? = raw?.trim()?.uppercase()?.takeIf { it in MODES }

    fun validId(id: String): Boolean = ID_PATTERN.matches(id)
}

/* ------------------------------ registry ----------------------------- */

data class SshTunnelInfo(
    val id: String,
    val sessionId: String,
    val mode: String,
    /** L/D：手机侧监听地址（恒为 127.0.0.1）；R：手机侧目标地址。 */
    val localHost: String,
    val localPort: Int,
    /** L：经服务器到达的目标主机；R/D：null。 */
    val remoteHost: String?,
    /** L：目标端口；R：服务器侧监听端口；D：null。 */
    val remotePort: Int?,
    val startedAt: Long,
)

/** 转发元数据与上限（纯 Kotlin，可脱离 sshj 单测）。 */
class SshTunnelRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val entries = LinkedHashMap<String, SshTunnelInfo>()

    @Synchronized
    fun add(
        sessionId: String,
        mode: String,
        localHost: String,
        localPort: Int,
        remoteHost: String?,
        remotePort: Int?,
    ): SshTunnelInfo {
        limitError(sessionId)?.let { throw IllegalArgumentException(it) }
        val info = SshTunnelInfo(newId(), sessionId, mode, localHost, localPort, remoteHost, remotePort, clock())
        entries[info.id] = info
        return info
    }

    /** 上限预检：返回错误文案，未超限返回 null。register 内会再原子复检一次。 */
    @Synchronized
    fun limitError(sessionId: String): String? = when {
        entries.values.count { it.sessionId == sessionId } >= SshTunnelRules.MAX_PER_SESSION ->
            "每个会话最多 ${SshTunnelRules.MAX_PER_SESSION} 条端口转发，请先 ssh_tunnel_stop 停掉不用的转发"
        entries.size >= SshTunnelRules.MAX_TOTAL ->
            "全局端口转发已达上限 ${SshTunnelRules.MAX_TOTAL} 条，请先停止其它会话的转发"
        else -> null
    }

    @Synchronized fun remove(id: String): SshTunnelInfo? = entries.remove(id)

    @Synchronized fun removeSession(sessionId: String): List<SshTunnelInfo> {
        val removed = entries.values.filter { it.sessionId == sessionId }
        removed.forEach { entries.remove(it.id) }
        return removed
    }

    @Synchronized fun get(id: String): SshTunnelInfo? = entries[id]

    @Synchronized fun list(): List<SshTunnelInfo> = entries.values.toList()

    @Synchronized fun countForSession(sessionId: String): Int =
        entries.values.count { it.sessionId == sessionId }

    @Synchronized fun size(): Int = entries.size

    private fun newId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        while (true) {
            val id = "tun-" + (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            if (id !in entries) return id
        }
    }
}

/**
 * 登记簿 + 句柄表。关闭动作以 lambda 注入，池本身不 import sshj，级联清理可单测。
 * 关闭动作在锁外执行（远端 cancel 是网络调用，不能持池锁等 RTT）。
 */
class SshTunnelPool(private val registry: SshTunnelRegistry = SshTunnelRegistry()) {
    private val handles = LinkedHashMap<String, () -> Unit>()

    @Synchronized
    fun register(
        sessionId: String,
        mode: String,
        localHost: String,
        localPort: Int,
        remoteHost: String?,
        remotePort: Int?,
        close: () -> Unit,
    ): SshTunnelInfo {
        val info = registry.add(sessionId, mode, localHost, localPort, remoteHost, remotePort)
        handles[info.id] = close
        return info
    }

    @Synchronized
    fun limitError(sessionId: String): String? = registry.limitError(sessionId)

    fun stop(id: String): Boolean {
        val close = synchronized(this) {
            if (registry.remove(id) == null) null else handles.remove(id)
        } ?: return false
        runCatching(close)
        return true
    }

    /** 级联停止：会话断开时清掉它名下全部转发，返回停止条数。 */
    fun stopSession(sessionId: String): Int {
        val closing = synchronized(this) {
            registry.removeSession(sessionId).mapNotNull { handles.remove(it.id) }
        }
        closing.forEach { runCatching(it) }
        return closing.size
    }

    fun get(id: String): SshTunnelInfo? = registry.get(id)
    fun list(): List<SshTunnelInfo> = registry.list()
    fun countForSession(sessionId: String): Int = registry.countForSession(sessionId)
    fun size(): Int = registry.size()
}

/** 全局单例：与 SshSessionPool 同生命周期。 */
val SshTunnels = SshTunnelPool()

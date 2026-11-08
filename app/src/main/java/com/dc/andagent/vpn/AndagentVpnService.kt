package com.dc.andagent.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dc.andagent.MainActivity
import com.dc.andagent.R
import com.dc.andagent.i18n.tr
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 纯应用权限的本地 VPN（VpnService，无需 root）。
 *
 * 工作方式：
 *  - 建立 tun 只接管 `10.111.222.0/24` 这一个网段，并把系统 DNS 指向
 *    `10.111.222.1`（tun 自己）。因此**只有 DNS 查询**进入用户态，
 *    正常 TCP/UDP 流量仍走物理网卡 —— 不会因为 VPN 断网，也不需要在
 *    用户态实现完整的 TCP/IP 栈。
 *  - 每个 DNS 查询先查 hosts 规则：命中直接本地应答（0.0.0.0 / NXDOMAIN），
 *    未命中则用 protect() 过的 socket 转发到上游 DNS 并把应答写回 tun。
 *  - 按应用分流用 VpnService.Builder 的 addAllowedApplication /
 *    addDisallowedApplication（纯应用权限）。
 *  - 每个经过的 IP 报文同时写入 pcap 缓冲，供 vpn_diag 导出。
 *
 * 已知边界：App 内置硬编码 DNS（DoH/DoT 或直连 8.8.8.8）不经过系统
 * resolver，因此不会被拦截；系统"私人 DNS"开启时同理。
 */
class AndagentVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "andagent_vpn"
        const val NOTIFICATION_ID = 4310

        const val TUN_ADDRESS = "10.111.222.1"
        const val TUN_NETWORK = "10.111.222.0"
        const val TUN_PREFIX = 24

        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 2500
        private const val UPSTREAM_CACHE_MS = 30_000L

        private val DEFAULT_UPSTREAM = listOf("223.5.5.5", "1.1.1.1")

        @Volatile
        var instance: AndagentVpnService? = null
            private set

        val running: Boolean
            get() = instance?.runningFlag() == true

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AndagentVpnService::class.java))
        }

        fun stop(context: Context) {
            instance?.shutdown()
            context.stopService(Intent(context, AndagentVpnService::class.java))
        }
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var input: FileInputStream? = null

    @Volatile
    private var output: FileOutputStream? = null

    @Volatile
    private var rules: Set<String> = emptySet()

    @Volatile
    private var blockStyle: String = DnsPacket.STYLE_ZERO

    @Volatile
    private var cachedUpstream: List<String> = emptyList()

    @Volatile
    private var cachedUpstreamAt: Long = 0L

    @Volatile
    private var lastError: String? = null

    private var loopThread: Thread? = null
    private var pool = Executors.newFixedThreadPool(4)
    private var sessionStart = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpnForeground()
        if (tun == null) {
            val error = establish()
            if (error != null) {
                lastError = error
                VpnTelemetry.endSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    fun runningFlag(): Boolean = running.get() && tun != null

    fun error(): String? = lastError

    fun sessionSeconds(): Long {
        val start = if (sessionStart > 0) sessionStart else VpnTelemetry.startedAt
        if (start <= 0) return 0
        return ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
    }

    fun activeRuleCount(): Int = rules.size

    fun privateDnsActive(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            manager.getLinkProperties(network)?.isPrivateDnsActive == true
        }
    }

    /** 规则或分流变更后调用，无需重建 tun。 */
    fun reloadRules() {
        val store = VpnStore.from(this)
        rules = store.activeRules()
        blockStyle = store.blockStyle
        cachedUpstream = emptyList()
        cachedUpstreamAt = 0
    }

    fun shutdown() {
        running.set(false)
        VpnTelemetry.endSession()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tun?.close() }
        input = null
        output = null
        tun = null
        loopThread?.interrupt()
        loopThread = null
        runCatching { pool.shutdownNow() }
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /* ------------------------------ engine ------------------------------ */

    private fun establish(): String? {
        if (VpnService.prepare(this) != null) {
            return tr("VPN 未授权：请在系统弹窗中允许 AndAgent 创建 VPN", "VPN not authorized: allow AndAgent to create a VPN in the system prompt")
        }
        val store = VpnStore.from(this)
        reloadRules()
        VpnTelemetry.beginSession()
        sessionStart = System.currentTimeMillis()

        val builder = Builder()
            .setSession("AndAgent")
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addDnsServer(TUN_ADDRESS)
            .addRoute(TUN_NETWORK, TUN_PREFIX)

        val packages = store.packages
        if (packages.isNotEmpty()) {
            when (store.splitMode) {
                VpnStore.SPLIT_INCLUDE -> packages.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                }
                VpnStore.SPLIT_EXCLUDE -> packages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                }
            }
        }

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            VpnTelemetry.endSession()
            return tr("建立 VPN 失败：${e.message ?: e.javaClass.simpleName}", "Failed to establish VPN: ${e.message ?: e.javaClass.simpleName}")
        } ?: run {
            VpnTelemetry.endSession()
            return tr("VPN 建立被系统拒绝（可能已在其它 VPN 中被占用）", "The system refused to establish the VPN (another VPN may be active)")
        }

        tun = descriptor
        input = FileInputStream(descriptor.fileDescriptor)
        output = FileOutputStream(descriptor.fileDescriptor)
        pool = Executors.newFixedThreadPool(4)
        running.set(true)
        loopThread = Thread({ readLoop() }, "andagent-vpn-tun").apply {
            isDaemon = true
            start()
        }
        return null
    }

    private fun readLoop() {
        val stream = input ?: return
        val buffer = ByteArray(32767)
        while (running.get()) {
            val length = try {
                stream.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (length <= 0) {
                if (!running.get()) break
                continue
            }
            VpnTelemetry.onPacket(length)
            try {
                handle(buffer, length)
            } catch (_: Exception) {
                // 单个报文解析失败不能让整个循环挂掉
            }
        }
    }

    private fun handle(buffer: ByteArray, length: Int) {
        val udp = IpPackets.parseUdp(buffer, length) ?: return
        if (udp.dstPort != DNS_PORT) return
        val payload = buffer.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        val question = DnsPacket.parse(payload) ?: return

        val now = System.currentTimeMillis()
        VpnTelemetry.capture(now, buffer.copyOf(length), outbound = true)

        val rule = HostsRules.matchedRule(question.name, rules)
        if (rule != null) {
            val answer = DnsPacket.blockAnswer(payload, question, blockStyle)
            reply(udp, answer)
            VpnTelemetry.recordDns(
                DnsEvent(
                    ts = now,
                    name = question.name,
                    qtype = DnsPacket.qtypeName(question.qtype),
                    clientIp = udp.srcText,
                    result = "blocked",
                    elapsedMs = 0,
                    rule = rule,
                ),
            )
            return
        }

        // 上游解析放到线程池，避免慢 DNS 卡住 tun 读取循环
        val startedNs = System.nanoTime()
        val task = { resolve(udp, payload, question, startedNs, now) }
        try {
            pool.execute(task)
        } catch (_: Exception) {
            reply(udp, DnsPacket.serverFailure(payload, question))
        }
    }

    private fun resolve(udp: UdpDatagram, query: ByteArray, question: DnsQuestion, startedNs: Long, receivedAt: Long) {
        var response: ByteArray? = null
        var server: String? = null
        for (candidate in upstreamServers()) {
            response = queryUpstream(candidate, query) ?: continue
            server = candidate
            break
        }
        val elapsed = (System.nanoTime() - startedNs) / 1_000_000
        val answer = response ?: DnsPacket.serverFailure(query, question)
        reply(udp, answer)
        VpnTelemetry.recordDns(
            DnsEvent(
                ts = receivedAt,
                name = question.name,
                qtype = DnsPacket.qtypeName(question.qtype),
                clientIp = udp.srcText,
                result = if (response != null) "forwarded" else "failed",
                elapsedMs = elapsed,
                answer = response?.let { DnsPacket.firstAnswerIp(it) },
                rule = server,
            ),
        )
    }

    private fun queryUpstream(server: String, query: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            // 必须先 protect 再 bind，否则报文会重新进入自己的 tun 形成回环
            socket = DatagramSocket(null)
            if (!protect(socket)) return null
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val address = InetAddress.getByName(server)
            socket.send(DatagramPacket(query, query.size, address, DNS_PORT))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun reply(udp: UdpDatagram, payload: ByteArray) {
        if (udp.ipVersion != 4) return
        val packet = runCatching {
            IpPackets.buildUdpReplyIpv4(udp.srcIp, udp.srcPort, udp.dstIp, udp.dstPort, payload)
        }.getOrNull() ?: return
        synchronized(writeLock) {
            try {
                output?.write(packet)
                VpnTelemetry.capture(System.currentTimeMillis(), packet, outbound = false)
            } catch (_: Exception) {
                VpnTelemetry.onWriteFailure()
            }
        }
    }

    private fun upstreamServers(): List<String> {
        val configured = VpnStore.from(this).upstreamList()
        if (configured.isNotEmpty()) return configured
        val now = System.currentTimeMillis()
        val cached = cachedUpstream
        if (cached.isNotEmpty() && now - cachedUpstreamAt < UPSTREAM_CACHE_MS) return cached
        val discovered = discoverSystemDns()
        if (discovered.isNotEmpty()) {
            cachedUpstream = discovered
            cachedUpstreamAt = now
            return discovered
        }
        return DEFAULT_UPSTREAM
    }

    private fun discoverSystemDns(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        for (network in manager.allNetworks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val properties = manager.getLinkProperties(network) ?: continue
            val servers = properties.dnsServers
                .mapNotNull { it.hostAddress }
                .filter { it.isNotEmpty() && it != TUN_ADDRESS && it != "0.0.0.0" && it != "::" }
            if (servers.isNotEmpty()) return servers.take(2)
        }
        return emptyList()
    }

    /* --------------------------- notification --------------------------- */

    private fun startVpnForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    tr("本地 VPN", "Local VPN"),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_andagent)
            .setContentTitle(tr("AndAgent 本地 VPN", "AndAgent local VPN"))
            .setContentText(tr("DNS 层广告拦截与流量诊断已开启", "DNS-level ad blocking and traffic diagnostics are on"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}

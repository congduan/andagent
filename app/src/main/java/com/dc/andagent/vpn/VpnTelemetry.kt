package com.dc.andagent.vpn

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/** 一条 DNS 处理记录（环形缓冲，供 vpn_diag 读取）。 */
data class DnsEvent(
    val ts: Long,
    val name: String,
    val qtype: String,
    val clientIp: String,
    val result: String,
    val elapsedMs: Long,
    val answer: String? = null,
    val rule: String? = null,
)

/**
 * VPN 运行期的计数器 + 最近的 DNS 事件 + pcap 抓包缓冲。
 *
 * 全部是进程内单例，service 停止后保留统计，便于"刚断开也能看诊断"，
 * `reset()` 由 vpn_start 调用。
 */
object VpnTelemetry {

    private const val MAX_EVENTS = 1000
    private const val MAX_CAPTURE_PACKETS = 4000
    private const val MAX_CAPTURE_BYTES = 8L * 1024 * 1024

    private val lock = Any()

    private val packetsIn = AtomicLong()
    private val bytesIn = AtomicLong()
    private val dnsQueries = AtomicLong()
    private val dnsBlocked = AtomicLong()
    private val dnsForwarded = AtomicLong()
    private val dnsFailed = AtomicLong()
    private val writeFailures = AtomicLong()

    @Volatile
    var startedAt: Long = 0L
        private set

    @Volatile
    var stoppedAt: Long = 0L
        private set

    private val events = ArrayDeque<DnsEvent>()
    private val captured = ArrayDeque<CapturedPacket>()
    private var capturedBytes = 0L

    private data class CapturedPacket(val tsMs: Long, val ip: ByteArray, val outbound: Boolean)

    fun beginSession() {
        synchronized(lock) {
            startedAt = System.currentTimeMillis()
            stoppedAt = 0L
            events.clear()
            captured.clear()
            capturedBytes = 0L
        }
        packetsIn.set(0)
        bytesIn.set(0)
        dnsQueries.set(0)
        dnsBlocked.set(0)
        dnsForwarded.set(0)
        dnsFailed.set(0)
        writeFailures.set(0)
    }

    fun endSession() {
        stoppedAt = System.currentTimeMillis()
    }

    fun onPacket(bytes: Int) {
        packetsIn.incrementAndGet()
        bytesIn.addAndGet(bytes.toLong())
    }

    fun onWriteFailure() {
        writeFailures.incrementAndGet()
    }

    fun recordDns(event: DnsEvent) {
        dnsQueries.incrementAndGet()
        when (event.result) {
            "blocked" -> dnsBlocked.incrementAndGet()
            "forwarded" -> dnsForwarded.incrementAndGet()
            else -> dnsFailed.incrementAndGet()
        }
        synchronized(lock) {
            events.addLast(event)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    /** 记录一个完整 IP 报文，供 pcap 导出。 */
    fun capture(tsMs: Long, ip: ByteArray, outbound: Boolean) {
        synchronized(lock) {
            if (captured.size >= MAX_CAPTURE_PACKETS || capturedBytes + ip.size > MAX_CAPTURE_BYTES) return
            captured.addLast(CapturedPacket(tsMs, ip, outbound))
            capturedBytes += ip.size
        }
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "packetsIn" to packetsIn.get(),
        "bytesIn" to bytesIn.get(),
        "dnsQueries" to dnsQueries.get(),
        "dnsBlocked" to dnsBlocked.get(),
        "dnsForwarded" to dnsForwarded.get(),
        "dnsFailed" to dnsFailed.get(),
        "writeFailures" to writeFailures.get(),
        "capturedPackets" to synchronized(lock) { captured.size.toLong() },
    )

    fun recent(limit: Int = 50): List<DnsEvent> = synchronized(lock) {
        events.toList().takeLast(limit.coerceIn(1, MAX_EVENTS))
    }

    /** 清空日志与抓包缓冲，保留累计计数。 */
    fun clearLogs() {
        synchronized(lock) {
            events.clear()
            captured.clear()
            capturedBytes = 0L
        }
    }

    /** 最近 N 条里命中次数最多的域名，用于"谁在偷偷连广告"。 */
    fun topBlocked(limit: Int = 20): List<Pair<String, Int>> = synchronized(lock) {
        events.asSequence()
            .filter { it.result == "blocked" }
            .groupingBy { it.name }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit.coerceIn(1, 200))
            .map { it.key to it.value }
            .toList()
    }

    /**
     * 导出标准 pcap（LINKTYPE_RAW，Wireshark 可直接打开）。
     * @return 写入的报文数；文件无法写入时抛异常。
     */
    fun exportPcap(file: File): Int {
        val packets = synchronized(lock) { captured.toList() }
        file.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file)).use { out ->
            val header = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.putInt(0xA1B2C3D4.toInt())
            header.putShort(2)
            header.putShort(4)
            header.putInt(0)
            header.putInt(0)
            header.putInt(65535)
            header.putInt(101) // LINKTYPE_RAW
            out.write(header.array())
            for (packet in packets) {
                val sec = packet.tsMs / 1000
                val usec = (packet.tsMs % 1000) * 1000
                val record = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                record.putInt(sec.toInt())
                record.putInt(usec.toInt())
                record.putInt(packet.ip.size)
                record.putInt(packet.ip.size)
                out.write(record.array())
                out.write(packet.ip)
            }
        }
        return packets.size
    }
}

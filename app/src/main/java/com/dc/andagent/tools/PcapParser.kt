package com.dc.andagent.tools

import java.io.InputStream
import java.io.PushbackInputStream

/**
 * R144：纯 JVM 流式 pcap / pcapng 解析器（零第三方依赖，仅 kotlin.io + java.io）。
 *
 * - 容器：pcap（大小端 / 纳秒变体）、pcapng（SHB/IDB/EPB/SPB + 未知块按 total length 跳过）。
 * - 逐包 / 逐块读取，内存占用 O(单包)，不整读文件（100MB 抓包不 OOM）。
 * - 包数 / 字节数上限（默认 50 万包 / 500MB），超限停止并标注 truncated。
 * - 所有字段读取走 bounds-checked helper（[readU8]/[readU16]/[readU32] / [Cursor]），
 *   越界抛 [PcapFormatException] 而非 ArrayIndexOutOfBoundsException。
 * - 边界：不做 TCP 流重组（跨段 TLS ClientHello 标注 partial 后跳过）；QUIC v1 SNI 为
 *   best-effort 简化提取（真实 QUIC Initial 载荷是加密的，不做初始密钥派生 / 解密）；
 *   IPv4/IPv6 分片仅计数不重组；DNS over TCP 只解单段首条消息。
 */
class PcapFormatException(message: String) : RuntimeException(message)

/* ---------------------- bounds-checked 读取 helper（纯函数，便于单测） ---------------------- */

/** 读 1 字节（0..255），越界抛 [PcapFormatException]。limit 允许小于 buf.size 以约束子区。 */
fun readU8(buf: ByteArray, off: Int, limit: Int = buf.size): Int {
    if (off < 0 || off + 1 > limit) throw PcapFormatException("readU8 越界: off=$off limit=$limit")
    return buf[off].toInt() and 0xFF
}

/** 读 2 字节无符号整数（0..65535）。 */
fun readU16(buf: ByteArray, off: Int, le: Boolean, limit: Int = buf.size): Int {
    if (off < 0 || off + 2 > limit) throw PcapFormatException("readU16 越界: off=$off limit=$limit")
    val a = buf[off].toInt() and 0xFF
    val b = buf[off + 1].toInt() and 0xFF
    return if (le) (a or (b shl 8)) else ((a shl 8) or b)
}

/** 读 4 字节无符号整数（0..0xFFFFFFFF，用 Long 承载）。 */
fun readU32(buf: ByteArray, off: Int, le: Boolean, limit: Int = buf.size): Long {
    if (off < 0 || off + 4 > limit) throw PcapFormatException("readU32 越界: off=$off limit=$limit")
    val a = buf[off].toLong() and 0xFF
    val b = buf[off + 1].toLong() and 0xFF
    val c = buf[off + 2].toLong() and 0xFF
    val d = buf[off + 3].toLong() and 0xFF
    return if (le) (a or (b shl 8) or (c shl 16) or (d shl 24))
    else ((a shl 24) or (b shl 16) or (c shl 8) or d)
}

/** 带游标的有界读取器；协议字段一律网络序（big-endian），容器头用文件字节序。 */
private class Cursor(
    val buf: ByteArray,
    val le: Boolean,
    var pos: Int = 0,
    val limit: Int = buf.size,
) {
    fun remaining(): Int = limit - pos
    fun u8(): Int = readU8(buf, pos, limit).also { pos++ }
    fun u16(): Int = readU16(buf, pos, le, limit).also { pos += 2 }
    fun u32(): Long = readU32(buf, pos, le, limit).also { pos += 4 }
    fun skip(n: Int) {
        if (n < 0 || pos + n > limit) throw PcapFormatException("Cursor.skip 越界: n=$n pos=$pos limit=$limit")
        pos += n
    }
    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > limit) throw PcapFormatException("Cursor.bytes 越界: n=$n pos=$pos limit=$limit")
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }
}

/* ------------------------------- 输出数据结构 ------------------------------- */

data class PcapSession(
    val a: String,
    val b: String,
    val protocol: String,
    var bytes: Long = 0,
    var packets: Int = 0,
    var syn: Int = 0,
    var fin: Int = 0,
    var rst: Int = 0,
)

data class PcapSummary(
    val packets: Long,
    val bytes: Long,
    val durationUs: Long,
    val firstTimestampUs: Long?,
    val lastTimestampUs: Long?,
    val protocolDistribution: Map<String, Long>,
    val topSessions: List<PcapSession>,
    val domains: List<String>,
    val fragmentCount: Long,
    val malformedPackets: Long,
    val tlsPartialCount: Long,
    val quicCount: Long,
)

data class PcapDnsRecord(
    val timestampUs: Long,
    val domain: String,
    val type: String,
    val resultIp: String?,
    val rcode: Int,
    val response: Boolean,
)

data class PcapHttpRecord(
    val timestampUs: Long,
    val kind: String, // "request" | "response" | "tls" | "quic"
    val method: String? = null,
    val host: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val sni: String? = null,
    val partial: Boolean = false,
)

class PcapResult(
    val summary: PcapSummary,
    val dns: List<PcapDnsRecord>,
    val http: List<PcapHttpRecord>,
    val truncated: Boolean,
    val truncatedReason: String?,
)

/** 解析配置：全局上限 + 各工具需要的明细条数上限（0 = 不收集该明细，但仍解码取域名/协议分布）。 */
data class PcapParseConfig(
    val maxPackets: Int = 500_000,
    val maxBytes: Long = 500L * 1024 * 1024,
    /** R144 修2(a)：pcapng 全块（含未知/IDB 块）累计字节上限，默认 500MB。 */
    val maxStreamBytes: Long = 500L * 1024 * 1024,
    /** R144 修2(c)：IDB 接口表 / DNS 域名去重表的独立数量上限。 */
    val maxInterfaces: Int = 64,
    val maxDomains: Int = 50_000,
    val dnsLimit: Int = 0,
    val httpLimit: Int = 0,
    val topN: Int = 20,
)

object PcapParser {

    private const val MAX_PACKET = 8 * 1024 * 1024       // 单包硬上限，防恶意 incl_len 撑爆内存
    private const val MAX_BLOCK = 64 * 1024 * 1024        // pcapng 单块硬上限
    private const val MAX_SESSIONS = 100_000              // 会话表上限，防端口扫描类文件 OOM
    private const val MAX_CONSECUTIVE_NON_PACKET = 10_000 // R144 修2(b)：连续不产生数据包的块上限
    val SUPPORTED_LINKTYPES = setOf(1, 101, 113, 276)
    const val SUPPORTED_LINKTYPES_TEXT = "Ethernet(1)、Linux SLL(113)、Linux SLL2(276)、RAW(101)"

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE")

    /** 解析入口：读 4 字节魔数判容器 → 回退 → 分派。EOF 干净结束 / 中途截断都优雅返回。 */
    fun parse(input: InputStream, config: PcapParseConfig = PcapParseConfig()): PcapResult {
        val push = PushbackInputStream(input, 8)
        val magic = ByteArray(4)
        val n = push.readUpTo(magic, 0, 4)
        if (n < 4) throw PcapFormatException("文件过短或为空，无法识别魔数")
        val pcapng = magic[0] == 0x0A.toByte() && magic[1] == 0x0D.toByte() &&
            magic[2] == 0x0D.toByte() && magic[3] == 0x0A.toByte()
        val pcap = !pcapng && isPcapMagic(magic)
        if (!pcap && !pcapng) {
            throw PcapFormatException("魔数不符：不是 pcap/pcapng 文件（前 4 字节 " +
                magic.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) } + "）")
        }
        push.unread(magic)
        return if (pcapng) parsePcapng(push, config) else parsePcap(push, config)
    }

    private fun isPcapMagic(m: ByteArray): Boolean =
        eqMagic(m, 0xa1, 0xb2, 0xc3, 0xd4) || eqMagic(m, 0xd4, 0xc3, 0xb2, 0xa1) ||
            eqMagic(m, 0xa1, 0xb2, 0x3c, 0x4d) || eqMagic(m, 0x4d, 0x3c, 0xb2, 0xa1)

    private fun eqMagic(m: ByteArray, a: Int, b: Int, c: Int, d: Int): Boolean =
        (m[0].toInt() and 0xFF) == a && (m[1].toInt() and 0xFF) == b &&
            (m[2].toInt() and 0xFF) == c && (m[3].toInt() and 0xFF) == d

    /** 从 pcap 全局头 4 字节魔数判定 (littleEndian, nano)。 */
    private fun pcapEndianness(m: ByteArray): Pair<Boolean, Boolean> = when {
        eqMagic(m, 0xa1, 0xb2, 0xc3, 0xd4) -> false to false   // BE, 微秒
        eqMagic(m, 0xd4, 0xc3, 0xb2, 0xa1) -> true to false    // LE, 微秒
        eqMagic(m, 0xa1, 0xb2, 0x3c, 0x4d) -> false to true    // BE, 纳秒
        eqMagic(m, 0x4d, 0x3c, 0xb2, 0xa1) -> true to true     // LE, 纳秒
        else -> throw PcapFormatException("魔数不符：不是合法的 pcap 文件")
    }

    /* --------------------------------- pcap --------------------------------- */

    private fun parsePcap(push: PushbackInputStream, config: PcapParseConfig): PcapResult {
        val gh = ByteArray(24)
        if (push.readUpTo(gh, 0, 24) < 24) throw PcapFormatException("pcap 全局头不完整（不足 24 字节）")
        val (le, nano) = pcapEndianness(gh)
        val snaplen = readU32(gh, 16, le).toInt()
        val linktype = readU32(gh, 20, le).toInt()
        val cap = if (snaplen in 1..MAX_PACKET) snaplen else MAX_PACKET
        val state = ParseState(config)
        var truncated = false
        var reason: String? = null
        while (true) {
            if (state.packets >= config.maxPackets.toLong()) { truncated = true; reason = "packet limit (${config.maxPackets})"; break }
            if (state.bytes >= config.maxBytes) { truncated = true; reason = "byte limit (${config.maxBytes} bytes)"; break }
            val rh = ByteArray(16)
            val hn = push.readUpTo(rh, 0, 16)
            if (hn == 0) break                                   // 干净 EOF（记录边界）
            if (hn < 16) { truncated = true; reason = "packet record header truncated"; break }
            val tsSec = readU32(rh, 0, le)
            val tsUsec = readU32(rh, 4, le)
            val rawIncl = readU32(rh, 8, le)
            // R144 修1：必须按记录头声明的 incl_len 精确消费整条记录。旧实现只读取
            // 被 cap 收敛后的长度，剩余包体字节残留，下一轮会被当成记录头 → 包体错位。
            // incl_len 超过 snaplen/单包上限时该记录畸形：用 skipFully 把声明长度的全
            // 部字节吃掉（含收敛差额）再继续对齐；中途 EOF 则按 truncated 收尾。
            // 正常记录（incl_len ≤ cap）读满后照旧解码，结果与现状一致。
            if (rawIncl > cap.toLong()) {
                state.malformedPackets++               // 收敛的畸形记录计入 malformed
                if (!push.skipFully(rawIncl)) { truncated = true; reason = "packet data truncated"; break }
                continue
            }
            val inclLen = rawIncl.toInt()
            val data = ByteArray(inclLen)
            val got = push.readUpTo(data, 0, inclLen)
            if (got < inclLen) { truncated = true; reason = "packet data truncated"; break }
            val tsMicros = tsSec * 1_000_000L + if (nano) tsUsec / 1000L else tsUsec
            state.onPacket(data, linktype, tsMicros)
        }
        return state.build(truncated, reason)
    }

    /* -------------------------------- pcapng -------------------------------- */

    private class Iface(val linktype: Int, val snaplen: Int, val tsMultiplier: Double)

    /** SHB 内 byte-order magic：磁盘字节 1A2B3C4D → 大端；4D3C2B1A → 小端。 */
    private fun bomLittleEndian(buf: ByteArray, off: Int): Boolean {
        if (off + 4 > buf.size) throw PcapFormatException("SHB byte-order magic 越界")
        val be = eqMagic(buf.copyOfRange(off, off + 4), 0x1a, 0x2b, 0x3c, 0x4d)
        val le = eqMagic(buf.copyOfRange(off, off + 4), 0x4d, 0x3c, 0x2b, 0x1a)
        return when {
            le -> true
            be -> false
            else -> true // 兜底按最常见的小端处理，不阻断解析
        }
    }

    private fun parsePcapng(push: PushbackInputStream, config: PcapParseConfig): PcapResult {
        // 首块必须是 SHB：先读 12 字节（type+len+byte-order magic）以定字节序，再补全整块。
        val shbHead = ByteArray(12)
        if (push.readUpTo(shbHead, 0, 12) < 12) throw PcapFormatException("pcapng SHB 不完整")
        if (!eqMagic(shbHead, 0x0a, 0x0d, 0x0d, 0x0a)) throw PcapFormatException("pcapng 首块不是 SHB")
        var le = bomLittleEndian(shbHead, 8)
        val shbTotal = readU32(shbHead, 4, le).toInt()
        if (shbTotal < 28 || shbTotal > MAX_BLOCK) throw PcapFormatException("SHB 长度异常: $shbTotal")
        val shbRest = ByteArray(shbTotal - 12)
        if (push.readUpTo(shbRest, 0, shbRest.size) < shbRest.size) throw PcapFormatException("SHB 被截断")

        val state = ParseState(config)
        var truncated = false
        var reason: String? = null
        // R144 修2：畸形文件用大量「不产生数据包」的块（未知块 / IDB / 异常块）绕过
        // 原本只按成功数据包累计的 packet/byte 上限，且每块可分配最多 64MB。补三道护栏：
        //  (a) 每个块（任意类型）按块总长（含 padding）累计进全局字节上限；
        //  (b) 连续不产生数据包的块计数上限；
        //  (c) interfaces/domains 表容量上限（在 ParseState 内封顶并标注）。
        var streamBytes = shbTotal.toLong()                    // 首块 SHB 已消费
        var consecutiveNonPacket = 0
        while (true) {
            if (state.packets >= config.maxPackets.toLong()) { truncated = true; reason = "packet limit (${config.maxPackets})"; break }
            if (state.bytes >= config.maxBytes) { truncated = true; reason = "byte limit (${config.maxBytes} bytes)"; break }
            val head = ByteArray(8)
            val hn = push.readUpTo(head, 0, 8)
            if (hn == 0) break                                   // 干净 EOF（块边界）
            if (hn < 8) { truncated = true; reason = "block header truncated"; break }
            val btype = readU32(head, 0, le).toInt()
            val blen = readU32(head, 4, le).toInt()
            if (blen < 12 || blen > MAX_BLOCK) { truncated = true; reason = "block length invalid: $blen"; break }
            // (a) 任意类型块都按块总长（含 padding）累计，超限立即收尾（读 body 前判断）
            streamBytes += blen
            if (streamBytes > config.maxStreamBytes) {
                truncated = true; reason = "block byte limit (${config.maxStreamBytes} bytes)"; break
            }
            val bodyAndTrailer = ByteArray(blen - 8)
            if (push.readUpTo(bodyAndTrailer, 0, bodyAndTrailer.size) < bodyAndTrailer.size) {
                truncated = true; reason = "block body truncated"; break
            }
            val contentLen = blen - 12                           // 去掉尾部重复的 4 字节长度
            val packetsBefore = state.packets
            when (btype) {
                0x0A0D0D0A -> {                                   // 新 section SHB：更新字节序并重置接口表
                    le = bomLittleEndian(bodyAndTrailer, 0)
                    state.interfaces.clear()
                }
                0x00000001 -> parseIdb(bodyAndTrailer, contentLen, le, state)
                0x00000006 -> parseEpb(bodyAndTrailer, contentLen, le, state)
                0x00000003 -> parseSpb(bodyAndTrailer, contentLen, le, state)
                else -> Unit                                      // 未知块：按 total length 跳过（前向兼容核心）
            }
            // (b) 连续不产生数据包的块（未知/IDB/异常）设上限，防大量小块耗时间
            if (state.packets > packetsBefore) {
                consecutiveNonPacket = 0
            } else if (++consecutiveNonPacket > MAX_CONSECUTIVE_NON_PACKET) {
                truncated = true; reason = "consecutive non-packet block limit ($MAX_CONSECUTIVE_NON_PACKET)"; break
            }
        }
        return state.build(truncated, reason)
    }

    private fun parseIdb(buf: ByteArray, contentLen: Int, le: Boolean, state: ParseState) {
        if (contentLen < 8) return
        val linktype = readU16(buf, 0, le)
        val snaplen = readU32(buf, 4, le).toInt()
        var tsMultiplier = 1.0                                   // 缺省 if_tsresol = 10^-6 s（微秒）
        var off = 8
        while (off + 4 <= contentLen) {
            val code = readU16(buf, off, le)
            val len = readU16(buf, off + 2, le)
            if (code == 0) break                                 // opt_endofopt
            val valOff = off + 4
            if (valOff + len > contentLen) break
            if (code == 9 && len >= 1) {                         // if_tsresol
                val b = buf[valOff].toInt() and 0xFF
                var denom = 1.0
                if ((b and 0x80) != 0) repeat(b and 0x7f) { denom *= 2.0 }  // 2^-n 秒
                else repeat(b) { denom *= 10.0 }                             // 10^-b 秒
                tsMultiplier = 1_000_000.0 / denom
            }
            off = valOff + ((len + 3) / 4) * 4                   // option value 4 字节对齐
        }
        // R144 修2(c)：interfaces 表容量上限，超限不再新增并标注。
        if (state.interfaces.size >= state.config.maxInterfaces) {
            state.markTableCapped("interfaces ≤ ${state.config.maxInterfaces}")
            return
        }
        state.interfaces.add(Iface(linktype, snaplen, tsMultiplier))
    }

    private fun parseEpb(buf: ByteArray, contentLen: Int, le: Boolean, state: ParseState) {
        if (contentLen < 20) return
        val ifaceId = readU32(buf, 0, le).toInt()
        val tsHigh = readU32(buf, 4, le)
        val tsLow = readU32(buf, 8, le)
        val capLen = readU32(buf, 12, le).toInt()
        val iface = state.interfaces.getOrNull(ifaceId) ?: Iface(1, 65535, 1.0)
        val take = minOf(capLen, contentLen - 20).coerceAtLeast(0)
        val pkt = buf.copyOfRange(20, 20 + take)
        val ticks = (tsHigh shl 32) or tsLow
        val tsMicros = (ticks.toDouble() * iface.tsMultiplier).toLong()
        state.onPacket(pkt, iface.linktype, tsMicros)
    }

    private fun parseSpb(buf: ByteArray, contentLen: Int, le: Boolean, state: ParseState) {
        if (contentLen < 4) return
        val origLen = readU32(buf, 0, le).toInt()
        val iface = state.interfaces.getOrNull(0) ?: Iface(1, 65535, 1.0)
        val take = minOf(origLen, iface.snaplen, contentLen - 4).coerceAtLeast(0)
        val pkt = buf.copyOfRange(4, 4 + take)
        state.onPacket(pkt, iface.linktype, null)               // SPB 无时间戳
    }

    /* --------------------------- 逐包解码与聚合状态 --------------------------- */

    private data class IpInfo(val version: Int, val proto: Int, val src: String, val dst: String, val fragment: Boolean)

    private class ParseState(val config: PcapParseConfig) {
        var packets = 0L
        var bytes = 0L
        var firstTs: Long? = null
        var lastTs: Long? = null
        val protoDist = LinkedHashMap<String, Long>()
        val sessions = LinkedHashMap<String, PcapSession>()
        val domains = LinkedHashSet<String>()
        val interfaces = ArrayList<Iface>()
        val dnsRecords = ArrayList<PcapDnsRecord>()
        val httpRecords = ArrayList<PcapHttpRecord>()
        var fragmentCount = 0L
        var malformedPackets = 0L
        var tlsPartialCount = 0L
        var quicCount = 0L
        // R144 修2(c)：表容量被封顶时标注，build() 据此把结果标为 truncated。
        var tableCapped = false
        var tableCapReason: String? = null

        fun protoInc(key: String) { protoDist[key] = (protoDist[key] ?: 0L) + 1L }
        fun markTableCapped(what: String) {
            if (!tableCapped) { tableCapped = true; tableCapReason = "table limit ($what)" }
        }
        fun addDomain(d: String?) {
            if (d.isNullOrBlank()) return
            // R144 修2(c)：domains 去重表容量上限，超限不再新增并标注。
            if (domains.size >= config.maxDomains) { markTableCapped("domains ≤ ${config.maxDomains}"); return }
            domains.add(d)
        }
        fun addDns(r: PcapDnsRecord) { if (dnsRecords.size < config.dnsLimit) dnsRecords.add(r) }
        fun addHttp(r: PcapHttpRecord) { if (httpRecords.size < config.httpLimit) httpRecords.add(r) }

        private fun updateTs(ts: Long?) {
            if (ts == null) return
            if (firstTs == null || ts < firstTs!!) firstTs = ts
            if (lastTs == null || ts > lastTs!!) lastTs = ts
        }

        fun onPacket(pkt: ByteArray, linktype: Int, tsMicros: Long?) {
            packets++
            bytes += pkt.size
            updateTs(tsMicros)
            // 不支持的链路层：明确报错，工具层返回 ERROR（列出支持的类型）。此检查在 try 之外，
            // 保证异常能透传到 parse() 而不是被单包兜底吞掉。
            if (linktype !in SUPPORTED_LINKTYPES) {
                throw PcapFormatException("不支持的链路层类型 linktype=$linktype；支持：$SUPPORTED_LINKTYPES_TEXT")
            }
            try {
                decodePacket(pkt, linktype, tsMicros)
            } catch (e: PcapFormatException) {
                malformedPackets++                                  // 单包畸形：跳过，不中断整份文件
            }
        }

        private fun decodePacket(pkt: ByteArray, linktype: Int, ts: Long?) {
            val c = Cursor(pkt, false)                              // 协议字段一律网络序
            val ipVersion = parseLink(c, linktype)
            if (ipVersion == 0) { protoInc("Other"); return }
            protoInc(if (ipVersion == 4) "IPv4" else "IPv6")
            val ip = parseIp(c, ipVersion) ?: return
            if (ip.fragment) fragmentCount++
            when (ip.proto) {
                6 -> parseTcp(c, ip, pkt.size, ts)
                17 -> parseUdp(c, ip, pkt.size, ts)
                1, 58 -> { protoInc("ICMP"); addSession(ip, 0, 0, "ICMP", pkt.size, 0) }
                else -> { protoInc("Other"); addSession(ip, 0, 0, "IP-${ip.proto}", pkt.size, 0) }
            }
        }

        /** 返回 IP 版本（4/6/0=非 IP 帧），并把游标推进到 IP 头起点。 */
        private fun parseLink(c: Cursor, linktype: Int): Int = when (linktype) {
            1 -> {                                                   // Ethernet II
                c.skip(12)                                           // dst+src MAC
                var et = c.u16()
                if (et == 0x8100) { c.skip(2); et = c.u16() }       // VLAN 单层跳过
                when (et) { 0x0800 -> 4; 0x86DD -> 6; else -> 0 }
            }
            113 -> {                                                 // Linux SLL：protocol 在偏移 14
                c.skip(14)
                when (c.u16()) { 0x0800 -> 4; 0x86DD -> 6; else -> 0 }
            }
            276 -> {                                                 // Linux SLL2：protocol 在偏移 0，头共 20B
                val proto = c.u16()
                c.skip(18)                                           // 补足到偏移 20
                when (proto) { 0x0800 -> 4; 0x86DD -> 6; else -> 0 }
            }
            101 -> {                                                 // RAW：直接 IP，按版本 nibble 判
                val v = readU8(c.buf, c.pos, c.limit) shr 4
                if (v == 4 || v == 6) v else 0
            }
            else -> throw PcapFormatException("不支持的链路层类型 linktype=$linktype；支持：$SUPPORTED_LINKTYPES_TEXT")
        }

        /** 解析 IP 头并把游标推进到传输层起点；扩展头（IPv6）简单遍历。 */
        private fun parseIp(c: Cursor, version: Int): IpInfo? {
            if (version == 4) {
                if (c.remaining() < 20) return null
                val b0 = c.u8()
                val ihl = (b0 and 0x0F) * 4
                c.skip(1)                                            // TOS/DSCP
                c.u16()                                              // total length
                c.skip(2)                                            // identification
                val frag = c.u16()                                   // flags + fragment offset
                c.skip(1)                                            // TTL
                val proto = c.u8()
                c.skip(2)                                            // checksum
                val src = ipv4(c.bytes(4))
                val dst = ipv4(c.bytes(4))
                if (ihl > 20) c.skip(ihl - 20)                       // IP options
                val fragment = (frag and 0x2000) != 0 || (frag and 0x1FFF) != 0
                return IpInfo(4, proto, src, dst, fragment)
            }
            // IPv6
            if (c.remaining() < 40) return null
            c.skip(4)                                                // version / traffic class / flow label
            c.u16()                                                  // payload length（仅推进游标）
            var next = c.u8()                                        // next header
            c.skip(1)                                                // hop limit
            val src = ipv6(c.bytes(16))
            val dst = ipv6(c.bytes(16))
            var off = c.pos
            var fragment = false
            var guard = 0
            while (guard++ < 16) {
                when (next) {
                    0, 43, 60 -> {                                   // hop-by-hop / routing / dest opts
                        if (off + 2 > c.limit) return null
                        val nh = c.buf[off].toInt() and 0xFF
                        val hlen = ((c.buf[off + 1].toInt() and 0xFF) + 1) * 8
                        off += hlen
                        next = nh
                    }
                    44 -> {                                           // fragment：固定 8 字节
                        if (off + 8 > c.limit) return null
                        fragment = true
                        val nh = c.buf[off].toInt() and 0xFF
                        off += 8
                        next = nh
                    }
                    else -> break
                }
            }
            if (off > c.limit) return null
            c.pos = off
            return IpInfo(6, next, src, dst, fragment)
        }

        private fun parseTcp(c: Cursor, ip: IpInfo, frameBytes: Int, ts: Long?) {
            if (c.remaining() < 20) { protoInc("TCP"); addSession(ip, 0, 0, "TCP", frameBytes, 0); return }
            val sport = c.u16()
            val dport = c.u16()
            c.u32()                                                  // seq（提取但不参与聚合）
            c.skip(4)                                                // ack
            val doff = ((c.u8() shr 4) * 4)
            val flags = c.u8()
            c.skip(6)                                                // window + checksum + urgent
            if (doff > 20) c.skip(doff - 20)                         // TCP options
            val app = c.bytes(c.remaining())
            protoInc("TCP")
            addSession(ip, sport, dport, "TCP", frameBytes, flags)
            val isDns = sport == 53 || dport == 53
            val isTls = sport == 443 || dport == 443
            when {
                isDns -> {
                    // DNS over TCP：2 字节长度前缀，单段只解首条（不重组）。
                    if (app.size >= 2) {
                        val len = readU16(app, 0, false)
                        if (2 + len <= app.size) parseDns(app.copyOfRange(2, 2 + len), ts)
                    }
                }
                looksLikeHttp(app) -> parseHttp(app, ts)
                isTls || looksLikeTlsClientHello(app) -> parseTls(app, ts)
            }
        }

        private fun parseUdp(c: Cursor, ip: IpInfo, frameBytes: Int, ts: Long?) {
            if (c.remaining() < 8) { protoInc("UDP"); addSession(ip, 0, 0, "UDP", frameBytes, 0); return }
            val sport = c.u16()
            val dport = c.u16()
            val udpLen = c.u16()
            c.skip(2)                                                // checksum
            val take = (udpLen - 8).coerceAtLeast(0).coerceAtMost(c.remaining())
            val app = c.bytes(take)
            protoInc("UDP")
            addSession(ip, sport, dport, "UDP", frameBytes, 0)
            when {
                sport == 53 || dport == 53 -> parseDns(app, ts)
                (sport == 443 || dport == 443) && looksLikeQuicLongHeader(app) -> parseQuic(app, ts)
            }
        }

        private fun addSession(ip: IpInfo, sport: Int, dport: Int, proto: String, frameBytes: Int, flags: Int) {
            val e1 = "${ip.src}:$sport"
            val e2 = "${ip.dst}:$dport"
            val (a, b) = if (e1 <= e2) e1 to e2 else e2 to e1
            val key = "$a<->$b/$proto"
            if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(key)) return
            val s = sessions.getOrPut(key) { PcapSession(a, b, proto) }
            s.bytes += frameBytes
            s.packets++
            if ((flags and 0x02) != 0) s.syn++
            if ((flags and 0x01) != 0) s.fin++
            if ((flags and 0x04) != 0) s.rst++
        }

        /* ------------------------------ DNS ------------------------------ */

        private fun parseDns(msg: ByteArray, ts: Long?) {
            if (msg.size < 12) return
            val flags = readU16(msg, 2, false)
            val qr = (flags and 0x8000) != 0
            val rcode = flags and 0x000F
            val qd = readU16(msg, 4, false)
            val an = readU16(msg, 6, false)
            val c = Cursor(msg, false)
            c.pos = 12
            try {
                repeat(qd) {
                    val (name, next) = decodeDnsName(c)
                    c.pos = next
                    val qtype = c.u16()
                    c.skip(2)                                        // qclass
                    protoInc("DNS")
                    addDomain(name)
                    addDns(PcapDnsRecord(ts ?: 0L, name, qtypeName(qtype), null, rcode, qr))
                }
                repeat(an) {
                    val (name, next) = decodeDnsName(c)
                    c.pos = next
                    val type = c.u16()
                    c.skip(2)                                        // class
                    c.skip(4)                                        // ttl
                    val rdlen = c.u16()
                    val rdataStart = c.pos
                    val rdata = c.bytes(rdlen)
                    val resultIp = when (type) {
                        1 -> if (rdlen == 4) ipv4(rdata) else null
                        28 -> if (rdlen == 16) ipv6(rdata) else null
                        5 -> {                                        // CNAME 内也可能含压缩指针
                            val c2 = Cursor(msg, false); c2.pos = rdataStart
                            runCatching { decodeDnsName(c2).first }.getOrNull()
                        }
                        else -> null
                    }
                    protoInc("DNS")
                    addDomain(name)
                    if (type == 5) addDomain(resultIp)
                    addDns(PcapDnsRecord(ts ?: 0L, name, qtypeName(type), resultIp, rcode, qr))
                }
            } catch (e: PcapFormatException) {
                malformedPackets++                                   // DNS 段在本包内不完整：跳过
            }
        }

        /** DNS 名称解码：普通 label + 0xC0 压缩指针（带循环/越界保护）。返回 (name, 名称字段之后的偏移)。 */
        private fun decodeDnsName(c: Cursor): Pair<String, Int> {
            val buf = c.buf
            var pos = c.pos
            var next = -1
            var jumps = 0
            val labels = ArrayList<String>()
            while (true) {
                if (pos >= c.limit) throw PcapFormatException("DNS name 越界: pos=$pos limit=${c.limit}")
                val len = buf[pos].toInt() and 0xFF
                when {
                    len == 0 -> { pos++; if (next < 0) next = pos; break }
                    (len and 0xC0) == 0xC0 -> {                       // 压缩指针
                        if (pos + 1 >= c.limit) throw PcapFormatException("DNS 压缩指针越界")
                        val ptr = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                        if (next < 0) next = pos + 2
                        pos = ptr
                        if (++jumps > 64) throw PcapFormatException("DNS 压缩指针循环")
                    }
                    (len and 0xC0) != 0 -> throw PcapFormatException("DNS 不支持的标签类型: 0x${"%02x".format(len)}")
                    else -> {
                        if (pos + 1 + len > c.limit) throw PcapFormatException("DNS 标签越界")
                        labels.add(String(buf, pos + 1, len, Charsets.US_ASCII))
                        pos += 1 + len
                    }
                }
            }
            return labels.joinToString(".") to next
        }

        private fun qtypeName(t: Int): String = when (t) {
            1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 12 -> "PTR"; 15 -> "MX"; 16 -> "TXT"
            28 -> "AAAA"; 33 -> "SRV"; 64 -> "SVCB"; 65 -> "HTTPS"; 255 -> "ANY"
            else -> "TYPE$t"
        }

        /* ------------------------------ HTTP ------------------------------ */

        private fun looksLikeHttp(app: ByteArray): Boolean {
            if (app.isEmpty()) return false
            val head = String(app, 0, minOf(app.size, 12), Charsets.ISO_8859_1)
            return head.substringBefore(' ') in HTTP_METHODS || head.startsWith("HTTP/")
        }

        private fun parseHttp(app: ByteArray, ts: Long?) {
            protoInc("HTTP")
            var end = app.size
            var i = 0
            while (i + 3 < app.size) {
                if (app[i] == 0x0d.toByte() && app[i + 1] == 0x0a.toByte() &&
                    app[i + 2] == 0x0d.toByte() && app[i + 3] == 0x0a.toByte()) { end = i; break }
                i++
            }
            val header = String(app, 0, end, Charsets.ISO_8859_1)
            val lines = header.replace("\r", "").split("\n")
            val first = lines.firstOrNull() ?: return
            if (first.startsWith("HTTP/")) {
                val code = first.split(" ").getOrNull(1)?.toIntOrNull()
                addHttp(PcapHttpRecord(ts ?: 0L, "response", status = code))
            } else {
                val parts = first.split(" ")
                val host = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                addDomain(host)
                addHttp(PcapHttpRecord(ts ?: 0L, "request", method = parts.getOrNull(0), host = host, path = parts.getOrNull(1)))
            }
        }

        /* ------------------------------- TLS ------------------------------- */

        private fun looksLikeTlsClientHello(app: ByteArray): Boolean =
            app.size >= 6 && (app[0].toInt() and 0xFF) == 0x16 && (app[5].toInt() and 0xFF) == 0x01

        private fun parseTls(app: ByteArray, ts: Long?) {
            protoInc("TLS")
            val c = Cursor(app, false)
            if (c.remaining() < 5) { tlsPartialCount++; addHttp(PcapHttpRecord(ts ?: 0L, "tls", partial = true)); return }
            if (c.u8() != 0x16) { addHttp(PcapHttpRecord(ts ?: 0L, "tls")); return }  // 非 handshake 记录
            c.skip(2)                                                // record version
            val recLen = c.u16()
            if (5 + recLen > app.size) {                            // 跨 TCP 段，需重组：跳过并标注 partial
                tlsPartialCount++
                addHttp(PcapHttpRecord(ts ?: 0L, "tls", partial = true))
                return
            }
            if (c.remaining() < 4 || c.u8() != 0x01) { addHttp(PcapHttpRecord(ts ?: 0L, "tls")); return }
            c.skip(3)                                                // handshake length
            try {
                c.skip(2)                                            // client_version
                c.skip(32)                                           // random
                val sidLen = c.u8(); c.skip(sidLen)
                val csLen = c.u16(); c.skip(csLen)
                val compLen = c.u8(); c.skip(compLen)
                var sni: String? = null
                if (c.remaining() >= 2) {
                    val extLen = c.u16()
                    val extEnd = minOf(c.limit, c.pos + extLen)
                    while (c.pos + 4 <= extEnd) {
                        val et = c.u16()
                        val el = c.u16()
                        val eEnd = c.pos + el
                        if (eEnd > c.limit) break
                        if (et == 0 && el >= 5) {                    // server_name extension 0x0000
                            c.u16()                                   // server_name_list_length
                            if (c.remaining() >= 3) {
                                val nameType = c.u8()
                                val hostLen = c.u16()
                                if (nameType == 0 && hostLen > 0 && c.remaining() >= hostLen) {
                                    sni = String(c.buf, c.pos, hostLen, Charsets.US_ASCII)
                                    c.skip(hostLen)
                                }
                            }
                        }
                        c.pos = eEnd
                    }
                }
                addDomain(sni)
                addHttp(PcapHttpRecord(ts ?: 0L, "tls", sni = sni))
            } catch (e: PcapFormatException) {
                tlsPartialCount++
                addHttp(PcapHttpRecord(ts ?: 0L, "tls", partial = true))
            }
        }

        /* ------------------------------- QUIC ------------------------------ */

        private fun looksLikeQuicLongHeader(app: ByteArray): Boolean =
            app.isNotEmpty() && (app[0].toInt() and 0xC0) == 0xC0

        /** 简化：仅识别 v1 Long Header 初包，best-effort 在载荷里搜明文 SNI 扩展（真实载荷加密，不解密）。 */
        private fun parseQuic(app: ByteArray, ts: Long?) {
            quicCount++
            protoInc("QUIC")
            if (app.size < 5) { addHttp(PcapHttpRecord(ts ?: 0L, "quic", partial = true)); return }
            val version = readU32(app, 1, false)                     // QUIC 网络序
            if (version != 1L) { addHttp(PcapHttpRecord(ts ?: 0L, "quic", partial = true)); return } // 仅 v1
            val sni = findSni(app, 5)
            addDomain(sni)
            addHttp(PcapHttpRecord(ts ?: 0L, "quic", sni = sni, partial = sni == null))
        }

        /** 扫明文 SNI 扩展结构：00 00 <extLen> <listLen> 00 <hostLen> <host>。best-effort。 */
        private fun findSni(buf: ByteArray, from: Int): String? {
            var i = maxOf(0, from)
            while (i + 9 <= buf.size) {
                if ((buf[i].toInt() and 0xFF) == 0 && (buf[i + 1].toInt() and 0xFF) == 0) {
                    val nameType = buf[i + 6].toInt() and 0xFF
                    if (nameType == 0) {
                        val hostLen = readU16(buf, i + 7, false)
                        if (hostLen > 0 && i + 9 + hostLen <= buf.size) {
                            val host = String(buf, i + 9, hostLen, Charsets.US_ASCII)
                            if (host.all { it.code in 0x20..0x7e }) return host
                        }
                    }
                }
                i++
            }
            return null
        }

        /* ------------------------------ helpers ----------------------------- */

        private fun ipv4(b: ByteArray): String =
            (0..3).joinToString(".") { (b[it].toInt() and 0xFF).toString() }

        private fun ipv6(b: ByteArray): String {
            val g = (0 until 16 step 2).map { ((b[it].toInt() and 0xFF) shl 8) or (b[it + 1].toInt() and 0xFF) }
            return g.joinToString(":") { it.toString(16) }
        }

        fun build(truncated: Boolean, reason: String?): PcapResult {
            // R144 修2(c)：表被封顶也算一种截断，标注在结果里（正常文件不触发）。
            val finalTruncated = truncated || tableCapped
            val finalReason = reason ?: tableCapReason
            val dur = if (firstTs != null && lastTs != null) (lastTs!! - firstTs!!).coerceAtLeast(0L) else 0L
            val dist = protoDist.entries.sortedByDescending { it.value }.associate { it.key to it.value }
            val top = sessions.values.sortedByDescending { it.bytes }.take(config.topN).map { it.copy() }
            val summary = PcapSummary(
                packets = packets, bytes = bytes, durationUs = dur,
                firstTimestampUs = firstTs, lastTimestampUs = lastTs,
                protocolDistribution = dist, topSessions = top, domains = domains.toList(),
                fragmentCount = fragmentCount, malformedPackets = malformedPackets,
                tlsPartialCount = tlsPartialCount, quicCount = quicCount,
            )
            return PcapResult(summary, dnsRecords.toList(), httpRecords.toList(), finalTruncated, finalReason)
        }
    }
}

/** 尽力读满 len 字节；返回实际读到的字节数（0 表示已到 EOF，0<n<len 表示中途截断）。 */
private fun InputStream.readUpTo(buf: ByteArray, off: Int, len: Int): Int {
    var total = 0
    while (total < len) {
        val n = read(buf, off + total, len - total)
        if (n < 0) break
        if (n == 0) {                                                // 规避个别流 read() 返回 0 造成死循环
            val one = read()
            if (one < 0) break
            buf[off + total] = one.toByte()
            total++
        } else {
            total += n
        }
    }
    return total
}

/** R144 修1：尽力跳过 len 字节（循环 read 丢弃，复用 8KB 缓冲）；全部跳过返回 true，中途 EOF 返回 false。 */
private fun InputStream.skipFully(len: Long): Boolean {
    var remaining = len
    val scratch = ByteArray(8192)
    while (remaining > 0) {
        val want = minOf(remaining, scratch.size.toLong()).toInt()
        val got = readUpTo(scratch, 0, want)
        if (got < want) return false                                // readUpTo 仅在 EOF 时少读
        remaining -= got
    }
    return true
}

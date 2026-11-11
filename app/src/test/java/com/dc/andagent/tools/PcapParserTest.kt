package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * R144：PcapParser 纯 JVM 字节样本断言。所有抓包均由本文件手工构造，无需真实 pcap 文件。
 * 覆盖：pcap LE/BE/纳秒、pcapng（EPB + 未知块跳过 + SPB）、Ethernet(VLAN)/SLL/SLL2/RAW、
 * IPv4/IPv6、TCP/UDP、DNS(0xC0 压缩指针)/HTTP/TLS-SNI/QUIC-SNI、截断优雅停止、未知链路层报错、
 * bounds-checked reader 抛 PcapFormatException。
 */
class PcapParserTest {

    /* ----------------------------- 字节构造 helper ----------------------------- */

    private fun ba(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
    private fun u16le(v: Int): ByteArray = ba(v and 0xFF, (v shr 8) and 0xFF)
    private fun u16be(v: Int): ByteArray = ba((v shr 8) and 0xFF, v and 0xFF)
    private fun u24be(v: Int): ByteArray = ba((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    private fun u32le(v: Long): ByteArray = ba(
        (v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(),
        ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt(),
    )
    private fun u32be(v: Long): ByteArray = ba(
        ((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(),
        ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt(),
    )
    private fun w16(v: Int, le: Boolean) = if (le) u16le(v) else u16be(v)
    private fun w32(v: Long, le: Boolean) = if (le) u32le(v) else u32be(v)

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (b in parts) { b.copyInto(out, p); p += b.size }
        return out
    }

    private fun pad4(b: ByteArray): ByteArray {
        val rem = b.size % 4
        return if (rem == 0) b else cat(b, ByteArray(4 - rem))
    }

    private val IP4_SRC = ba(192, 168, 1, 10)
    private val IP4_DST = ba(10, 0, 0, 20)
    private val IP6_SRC = ByteArray(16) { (it + 1).toByte() }
    private val IP6_DST = ByteArray(16) { (0x20 + it).toByte() }

    /* ------------------------------- 链路层 ------------------------------- */

    private fun ethernet(payload: ByteArray, ethertype: Int = 0x0800): ByteArray =
        cat(ByteArray(12), u16be(ethertype), payload)

    private fun sll(payload: ByteArray, ethertype: Int = 0x0800): ByteArray =
        cat(u16be(0), u16be(1), u16be(6), ByteArray(8), u16be(ethertype), payload)

    private fun sll2(payload: ByteArray, ethertype: Int = 0x0800): ByteArray =
        cat(u16be(ethertype), u16be(0), u32be(1), u16be(1), ba(0), ba(6), ByteArray(8), payload)

    /* ------------------------------- 网络层 ------------------------------- */

    private fun ipv4(payload: ByteArray, proto: Int, src: ByteArray, dst: ByteArray, frag: Int = 0): ByteArray {
        val total = 20 + payload.size
        return cat(
            ba(0x45, 0x00), u16be(total), u16be(0), u16be(frag), ba(0x40, proto),
            u16be(0), src, dst, payload,
        )
    }

    private fun ipv6(payload: ByteArray, nextHeader: Int, src: ByteArray, dst: ByteArray): ByteArray =
        cat(ba(0x60, 0, 0, 0), u16be(payload.size), ba(nextHeader, 64), src, dst, payload)

    /* ------------------------------- 传输层 ------------------------------- */

    private fun tcp(sport: Int, dport: Int, flags: Int, payload: ByteArray): ByteArray =
        cat(u16be(sport), u16be(dport), u32be(1), u32be(0), ba(0x50, flags), u16be(0), u16be(0), u16be(0), payload)

    private fun udp(sport: Int, dport: Int, payload: ByteArray): ByteArray =
        cat(u16be(sport), u16be(dport), u16be(8 + payload.size), u16be(0), payload)

    /* ------------------------------- 应用层 ------------------------------- */

    private fun qname(vararg labels: String): ByteArray {
        val parts = ArrayList<ByteArray>()
        for (l in labels) {
            val b = l.toByteArray(Charsets.US_ASCII)
            parts.add(ba(b.size)); parts.add(b)
        }
        parts.add(ba(0))
        return cat(*parts.toTypedArray())
    }

    /** 标准查询：id=0x1234 flags=0x0100 QD=1。 */
    private fun dnsQuery(name: ByteArray, qtype: Int = 1): ByteArray =
        cat(u16be(0x1234), u16be(0x0100), u16be(1), u16be(0), u16be(0), u16be(0), name, u16be(qtype), u16be(1))

    /** 响应：Question 在偏移 12，Answer NAME 用 0xC0 0x0C 压缩指针回指 Question。 */
    private fun dnsResponseCompressed(name: ByteArray, ip: ByteArray): ByteArray {
        val question = cat(name, u16be(1), u16be(1))
        val answer = cat(ba(0xC0, 0x0C), u16be(1), u16be(1), u32be(60), u16be(4), ip)
        return cat(u16be(0x1234), u16be(0x8180), u16be(1), u16be(1), u16be(0), u16be(0), question, answer)
    }

    private fun httpGet(host: String, path: String): ByteArray =
        "GET $path HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private fun httpResponse(code: Int): ByteArray =
        "HTTP/1.1 $code OK\r\nContent-Type: text/plain\r\n\r\nbody".toByteArray(Charsets.US_ASCII)

    /** 结构合法的最小 TLS ClientHello（含 SNI 扩展 0x0000）。 */
    private fun tlsClientHello(sni: String): ByteArray {
        val host = sni.toByteArray(Charsets.US_ASCII)
        val serverNameList = cat(ba(0), u16be(host.size), host)
        val extData = cat(u16be(serverNameList.size), serverNameList)
        val sniExt = cat(u16be(0), u16be(extData.size), extData)
        val extensions = cat(u16be(sniExt.size), sniExt)
        val body = cat(u16be(0x0303), ByteArray(32), ba(0), u16be(2), u16be(0x002f), ba(1), ba(0), extensions)
        val hs = cat(ba(0x01), u24be(body.size), body)
        return cat(ba(0x16, 0x03, 0x01), u16be(hs.size), hs)
    }

    /** 简化 QUIC v1 初包：long header + 一段明文 SNI 扩展（真实 QUIC 载荷加密，仅测 best-effort 路径）。 */
    private fun quicInitialWithSni(sni: String): ByteArray {
        val host = sni.toByteArray(Charsets.US_ASCII)
        val serverNameList = cat(ba(0), u16be(host.size), host)
        val extData = cat(u16be(serverNameList.size), serverNameList)
        val sniExt = cat(u16be(0), u16be(extData.size), extData)
        return cat(ba(0xC3), u32be(1), ba(0), ba(0), sniExt)
    }

    /* ------------------------------- pcap ------------------------------- */

    private fun pcapGlobal(linktype: Int, le: Boolean, nano: Boolean = false, snaplen: Int = 65535): ByteArray {
        val magic = when {
            le && nano -> ba(0x4d, 0x3c, 0xb2, 0xa1)
            le -> ba(0xd4, 0xc3, 0xb2, 0xa1)
            nano -> ba(0xa1, 0xb2, 0x3c, 0x4d)
            else -> ba(0xa1, 0xb2, 0xc3, 0xd4)
        }
        return cat(magic, w16(2, le), w16(4, le), w32(0, le), w32(0, le), w32(snaplen.toLong(), le), w32(linktype.toLong(), le))
    }

    private fun pcapRecord(packet: ByteArray, le: Boolean, tsSec: Long): ByteArray =
        cat(w32(tsSec, le), w32(0, le), w32(packet.size.toLong(), le), w32(packet.size.toLong(), le), packet)

    private fun pcap(
        vararg packets: ByteArray,
        le: Boolean = true,
        linktype: Int = 1,
        nano: Boolean = false,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        parts.add(pcapGlobal(linktype, le, nano))
        packets.forEachIndexed { i, p -> parts.add(pcapRecord(p, le, 1L + i)) }
        return cat(*parts.toTypedArray())
    }

    /* ------------------------------- pcapng ------------------------------ */

    /** pcapng 块：blockType(4) + totalLen(4) + body + trailing totalLen(4)。type 用段字节序写。 */
    private fun block(type: Int, body: ByteArray, le: Boolean): ByteArray {
        val total = 12 + body.size
        return cat(w32(type.toLong(), le), w32(total.toLong(), le), body, w32(total.toLong(), le))
    }

    private fun shb(le: Boolean): ByteArray {
        val bom = if (le) ba(0x4d, 0x3c, 0x2b, 0x1a) else ba(0x1a, 0x2b, 0x3c, 0x4d)
        val body = cat(bom, w16(1, le), w16(0, le), ByteArray(8) { 0xff.toByte() })
        return block(0x0A0D0D0A, body, le)
    }

    private fun idb(linktype: Int, snaplen: Int, le: Boolean): ByteArray =
        block(0x00000001, cat(w16(linktype, le), w16(0, le), w32(snaplen.toLong(), le)), le)

    private fun epb(ifaceId: Int, tsHigh: Long, tsLow: Long, packet: ByteArray, le: Boolean): ByteArray {
        val body = cat(
            w32(ifaceId.toLong(), le), w32(tsHigh, le), w32(tsLow, le),
            w32(packet.size.toLong(), le), w32(packet.size.toLong(), le), pad4(packet),
        )
        return block(0x00000006, body, le)
    }

    private fun spb(packet: ByteArray, le: Boolean): ByteArray =
        block(0x00000003, cat(w32(packet.size.toLong(), le), pad4(packet)), le)

    private fun unknownBlock(le: Boolean): ByteArray =
        block(0x00000BAD, ba(0xde, 0xad, 0xbe, 0xef), le)

    /* -------------------------------- 断言 -------------------------------- */

    private fun parse(data: ByteArray, cfg: PcapParseConfig = PcapParseConfig()): PcapResult =
        PcapParser.parse(ByteArrayInputStream(data), cfg)

    private fun dnsCfg() = PcapParseConfig(dnsLimit = 50)
    private fun httpCfg() = PcapParseConfig(httpLimit = 50)
    private fun allCfg() = PcapParseConfig(dnsLimit = 50, httpLimit = 50, topN = 10)

    @Test
    fun pcapLittleEndian_ethernetIpv4TcpHttp() {
        val pkt = ethernet(ipv4(tcp(1234, 80, 0x02, httpGet("example.com", "/index.html")), 6, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, le = true), allCfg())
        assertEquals(1L, r.summary.packets)
        assertFalse(r.truncated)
        assertEquals(1L, r.summary.protocolDistribution["IPv4"])
        assertEquals(1L, r.summary.protocolDistribution["TCP"])
        assertTrue(r.summary.topSessions.isNotEmpty())
        assertTrue(r.http.any { it.kind == "request" && it.method == "GET" && it.host == "example.com" && it.path == "/index.html" })
        assertTrue(r.summary.domains.contains("example.com"))
    }

    @Test
    fun pcapBigEndian_udpDns() {
        val pkt = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("www", "example", "com"))), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, le = false), dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "www.example.com" && it.type == "A" && !it.response && it.rcode == 0 })
    }

    @Test
    fun pcapNanoMagicAccepted() {
        val pkt = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("ns", "test"))), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, le = true, nano = true), dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "ns.test" })
        assertNotNull(r.summary.firstTimestampUs)
    }

    @Test
    fun pcapng_epbAndSkipUnknownBlock() {
        val p1 = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("one", "test"))), 17, IP4_SRC, IP4_DST))
        val p2 = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("two", "test"))), 17, IP4_SRC, IP4_DST))
        val data = cat(
            shb(true), idb(1, 65535, true),
            epb(0, 0, 1_000_000, p1, true),
            unknownBlock(true),                       // 未知块按 total length 跳过
            epb(0, 0, 2_000_000, p2, true),
        )
        val r = parse(data, dnsCfg())
        assertEquals(2L, r.summary.packets)
        assertFalse(r.truncated)
        assertTrue(r.dns.any { it.domain == "one.test" })
        assertTrue(r.dns.any { it.domain == "two.test" })
        assertTrue(r.summary.durationUs >= 1_000_000L)
    }

    @Test
    fun pcapng_spb() {
        val pkt = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("spb", "test"))), 17, IP4_SRC, IP4_DST))
        val data = cat(shb(true), idb(1, 65535, true), spb(pkt, true))
        val r = parse(data, dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "spb.test" })
    }

    @Test
    fun dnsCompressionPointer_0xC0() {
        val name = qname("www", "example", "com")
        val msg = dnsResponseCompressed(name, ba(1, 2, 3, 4))
        val pkt = ethernet(ipv4(udp(53, 44444, msg), 17, IP4_DST, IP4_SRC))
        val r = parse(pcap(pkt), dnsCfg())
        val ans = r.dns.firstOrNull { it.response && it.resultIp != null }
        assertNotNull("应有 Answer 记录", ans)
        assertEquals("www.example.com", ans!!.domain)   // 0xC0 指针必须解成 Question 的 QNAME
        assertEquals("A", ans.type)
        assertEquals("1.2.3.4", ans.resultIp)
        assertEquals(0, ans.rcode)
        assertTrue(r.summary.domains.contains("www.example.com"))
    }

    @Test
    fun dnsOverTcp_skips2ByteLengthPrefix() {
        val msg = dnsQuery(qname("tcp", "dns", "test"))
        val framed = cat(u16be(msg.size), msg)
        val pkt = ethernet(ipv4(tcp(50000, 53, 0x18, framed), 6, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt), dnsCfg())
        assertTrue(r.dns.any { it.domain == "tcp.dns.test" && it.type == "A" })
    }

    @Test
    fun httpRequestAndResponse() {
        val req = ethernet(ipv4(tcp(1234, 80, 0x18, httpGet("api.test", "/v1/data")), 6, IP4_SRC, IP4_DST))
        val resp = ethernet(ipv4(tcp(80, 1234, 0x18, httpResponse(404)), 6, IP4_DST, IP4_SRC))
        val r = parse(pcap(req, resp), httpCfg())
        assertTrue(r.http.any { it.kind == "request" && it.method == "GET" && it.path == "/v1/data" && it.host == "api.test" })
        assertTrue(r.http.any { it.kind == "response" && it.status == 404 })
    }

    @Test
    fun tlsClientHello_extractsSni() {
        val pkt = ethernet(ipv4(tcp(50000, 443, 0x02, tlsClientHello("secure.example.com")), 6, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt), httpCfg())
        val tls = r.http.firstOrNull { it.kind == "tls" }
        assertNotNull("应有 TLS-SNI 记录", tls)
        assertEquals("secure.example.com", tls!!.sni)
        assertFalse(tls.partial)
        assertTrue(r.summary.domains.contains("secure.example.com"))
    }

    @Test
    fun tlsClientHelloSplitAcrossSegmentsMarkedPartial() {
        val ch = tlsClientHello("split.example.com")
        // 伪造一个 record length 远大于本段数据量的 ClientHello → 视为需跨段重组
        val broken = ch.copyOf()
        broken[3] = 0x07; broken[4] = 0xff.toByte()  // recLen = 0x07ff >> 实际长度
        val pkt = ethernet(ipv4(tcp(50000, 443, 0x02, broken), 6, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt), httpCfg())
        assertTrue(r.summary.tlsPartialCount >= 1L)
        assertTrue(r.http.any { it.kind == "tls" && it.partial && it.sni == null })
    }

    @Test
    fun quicV1_extractsSniBestEffort() {
        val pkt = ethernet(ipv4(udp(50000, 443, quicInitialWithSni("quic.example.com")), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt), httpCfg())
        val q = r.http.firstOrNull { it.kind == "quic" }
        assertNotNull("应有 QUIC 记录", q)
        assertEquals("quic.example.com", q!!.sni)
        assertEquals(1L, r.summary.quicCount)
    }

    @Test
    fun sllLinkLayer() {
        val pkt = sll(ipv4(udp(1234, 53, dnsQuery(qname("sll", "test"))), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, linktype = 113), dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "sll.test" })
    }

    @Test
    fun sll2LinkLayer() {
        val pkt = sll2(ipv4(udp(1234, 53, dnsQuery(qname("sll2", "test"))), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, linktype = 276), dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "sll2.test" })
    }

    @Test
    fun rawLinkLayer() {
        val pkt = ipv4(udp(1234, 53, dnsQuery(qname("raw", "test"))), 17, IP4_SRC, IP4_DST)
        val r = parse(pcap(pkt, linktype = 101), dnsCfg())
        assertEquals(1L, r.summary.packets)
        assertTrue(r.dns.any { it.domain == "raw.test" })
    }

    @Test
    fun vlanSingleLayer() {
        val inner = ipv4(udp(1234, 53, dnsQuery(qname("vlan", "test"))), 17, IP4_SRC, IP4_DST)
        val frame = cat(ByteArray(12), u16be(0x8100), u16be(0x0064), u16be(0x0800), inner)
        val r = parse(pcap(frame), dnsCfg())
        assertTrue(r.dns.any { it.domain == "vlan.test" })
    }

    @Test
    fun ipv6UdpDns() {
        val inner = udp(1234, 53, dnsQuery(qname("v6", "test")))
        val pkt = ethernet(ipv6(inner, 17, IP6_SRC, IP6_DST), ethertype = 0x86DD)
        val r = parse(pcap(pkt), dnsCfg())
        assertEquals(1L, r.summary.protocolDistribution["IPv6"])
        assertTrue(r.dns.any { it.domain == "v6.test" })
        assertTrue(r.summary.topSessions.first().protocol == "UDP")
    }

    @Test
    fun ipv6ExtensionHeaderWalk_hopByHopThenUdp() {
        // hop-by-hop 扩展头：nextHeader=17(UDP)，hdrExtLen=0 → 8 字节
        val ext = cat(ba(17), ba(0), ByteArray(6))
        val inner = udp(1234, 53, dnsQuery(qname("ext", "test")))
        val body = cat(ext, inner)
        val pkt = ethernet(ipv6(body, 0, IP6_SRC, IP6_DST), ethertype = 0x86DD)
        val r = parse(pcap(pkt), dnsCfg())
        assertTrue(r.dns.any { it.domain == "ext.test" })
    }

    @Test
    fun ipv4FragmentCounted() {
        val pkt = ethernet(ipv4(udp(1234, 9999, ba(1, 2, 3, 4)), 17, IP4_SRC, IP4_DST, frag = 0x2000))
        val r = parse(pcap(pkt))
        assertEquals(1L, r.summary.fragmentCount)
    }

    @Test
    fun tcpFlagCountersAggregateToSession() {
        val syn = ethernet(ipv4(tcp(1234, 80, 0x02, ByteArray(0)), 6, IP4_SRC, IP4_DST))
        val fin = ethernet(ipv4(tcp(1234, 80, 0x01, ByteArray(0)), 6, IP4_SRC, IP4_DST))
        val rst = ethernet(ipv4(tcp(1234, 80, 0x04, ByteArray(0)), 6, IP4_SRC, IP4_DST))
        val r = parse(pcap(syn, fin, rst), PcapParseConfig(topN = 5))
        val s = r.summary.topSessions.first()
        assertEquals(3, s.packets)
        assertEquals(1, s.syn)
        assertEquals(1, s.fin)
        assertEquals(1, s.rst)
    }

    @Test
    fun truncatedPacketRecord_stopsGracefully() {
        val gh = pcapGlobal(1, true)
        val recHeader = cat(w32(1, true), w32(0, true), w32(50, true), w32(50, true))
        val partial = ba(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)  // 只给 10 字节，记录声称 50
        val r = parse(cat(gh, recHeader, partial))
        assertTrue("应标注 truncated", r.truncated)
        assertNotNull(r.truncatedReason)
        assertEquals(0L, r.summary.packets)
    }

    @Test
    fun truncatedBlockHeader_pcapngStopsGracefully() {
        val data = cat(shb(true), idb(1, 65535, true), ba(0x06, 0x00, 0x00, 0x00, 0x1c)) // 半个块头
        val r = parse(data)
        assertTrue(r.truncated)
    }

    @Test
    fun badMagic_returnsPcapFormatException() {
        val data = ba(0xde, 0xad, 0xbe, 0xef, 0, 0, 0, 0)
        assertThrows(PcapFormatException::class.java) { parse(data) }
    }

    @Test
    fun unknownLinktype_returnsClearError() {
        val pkt = ethernet(ipv4(udp(1234, 9999, ba(1, 2, 3, 4)), 17, IP4_SRC, IP4_DST))
        val ex = assertThrows(PcapFormatException::class.java) { parse(pcap(pkt, linktype = 999)) }
        assertTrue(ex.message!!.contains("不支持"))
        assertTrue(ex.message!!.contains("Ethernet(1)"))
    }

    @Test
    fun boundsCheckedReadersThrowPcapFormatException() {
        assertThrows(PcapFormatException::class.java) { readU8(ByteArray(0), 0) }
        assertThrows(PcapFormatException::class.java) { readU16(ByteArray(1), 0, false) }
        assertThrows(PcapFormatException::class.java) { readU32(ByteArray(2), 0, true) }
        assertThrows(PcapFormatException::class.java) { readU32(ByteArray(4), 2, true) }
    }

    @Test
    fun pcapPathWhitelist() {
        assertNull(PcapPathRules.validate("/sdcard/Download/capture.pcap"))
        assertNull(PcapPathRules.validate("/storage/emulated/0/Download/c.pcapng"))
        assertNotNull(PcapPathRules.validate("/data/local/tmp/c.pcap"))
        assertNotNull(PcapPathRules.validate("../etc/passwd"))
        assertNotNull(PcapPathRules.validate("relative/c.pcap"))
    }

    @Test
    fun packetAndByteLimitsMarkTruncated() {
        val pkt = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("limit", "test"))), 17, IP4_SRC, IP4_DST))
        val r = parse(pcap(pkt, pkt), PcapParseConfig(maxPackets = 1, maxBytes = 500L * 1024 * 1024))
        assertTrue(r.truncated)
        assertTrue(r.truncatedReason!!.contains("packet limit"))
        assertEquals(1L, r.summary.packets)
    }

    // ------------------------- R144 修1 / 修2 回归用例 -------------------------

    @Test
    fun pcapOversizedRecord_consumesFullDeclaredLen_thenParsesFollowingRecord() {
        // snaplen=100 → cap=100；构造一条 incl_len=200（> cap，被收敛）的畸形记录，
        // 文件里确实放了 200 字节包体；其后紧跟一条正常 DNS 记录。旧实现只消费 100
        // 字节，残留的 100 字节被下一轮当成记录头 → 后续记录错位、无法解析。
        val oversizedBody = ByteArray(200) { 0x7f }
        val oversizedRecord = cat(
            w32(1, true), w32(0, true), w32(200, true), w32(200, true), oversizedBody,
        )
        val good = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("after", "test"))), 17, IP4_SRC, IP4_DST))
        val goodRecord = pcapRecord(good, true, 2L)
        val data = cat(pcapGlobal(1, true, snaplen = 100), oversizedRecord, goodRecord)
        val r = parse(data, dnsCfg())
        // 畸形记录按声明长度整体消费，后续记录对齐后可正常解析。
        assertTrue(r.dns.any { it.domain == "after.test" })
        assertEquals(1L, r.summary.packets)          // 仅正常记录计入 packets
        assertTrue(r.summary.malformedPackets >= 1L) // 畸形记录计入 malformed
        assertFalse(r.truncated)                     // 未提前 EOF，干净收尾
    }

    @Test
    fun pcapng_consecutiveUnknownBlocksHitGlobalByteLimit() {
        // 连续未知块（不产生数据包）也按块总长累计进全局字节上限，超限立即 truncated。
        val unknown = unknownBlock(true)              // blen=16 的未知块
        val blocks = Array(20) { unknown }
        val data = cat(shb(true), *blocks)
        val r = parse(data, PcapParseConfig(maxStreamBytes = 100))
        assertTrue(r.truncated)
        assertTrue(r.truncatedReason!!.contains("byte limit"))
    }

    @Test
    fun pcapng_interfaceAndDomainTableCaps() {
        val cfg = PcapParseConfig(maxInterfaces = 4, maxDomains = 3, dnsLimit = 50)
        // 6 个 IDB（> maxInterfaces=4），后接 6 个携带不同域名的 EPB（> maxDomains=3）。
        val idbs = Array(6) { idb(1, 65535, true) }
        val epbs = (1..6).map { i ->
            val pkt = ethernet(ipv4(udp(1234, 53, dnsQuery(qname("d$i", "test"))), 17, IP4_SRC, IP4_DST))
            epb(0, 0, i.toLong(), pkt, true)
        }
        val data = cat(shb(true), *idbs, *epbs.toTypedArray())
        val r = parse(data, cfg)
        // domains 表封顶：只保留 maxDomains 个；表被封顶标注为 truncated。
        assertEquals(3, r.summary.domains.size)
        assertTrue(r.truncated)
        // interfaces 封顶先触发，原因里记录 interfaces。
        assertTrue(r.truncatedReason!!.contains("interfaces"))
    }
}

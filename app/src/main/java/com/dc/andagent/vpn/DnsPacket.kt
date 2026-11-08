package com.dc.andagent.vpn

/**
 * DNS 报文的最小解析 / 应答构造（纯 Kotlin，可 JVM 单测）。
 *
 * 只处理查询报文（QR=0、opcode=0、单问题）——这已经覆盖系统 resolver
 * 发给 VPN DNS 的全部请求。拦截应答按 qtype 分别构造：
 *  - A     → NOERROR + 1 条 A 记录（默认 0.0.0.0）
 *  - 其他  → NOERROR + 空答案（AAAA 无记录，客户端会退回 IPv4 或直接失败）
 *  - nxdomain 风格 → RCODE=3
 */
data class DnsQuestion(
    val id: Int,
    val name: String,
    val qtype: Int,
    val qclass: Int,
    /** 问题区结束位置（从报文起点算，含 QNAME+QTYPE+QCLASS）。 */
    val questionEnd: Int,
)

object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val CLASS_IN = 1

    const val STYLE_ZERO = "zero"
    const val STYLE_NXDOMAIN = "nxdomain"

    private const val HEADER_SIZE = 12
    private const val FLAG_QR = 0x8000
    private const val OPCODE_MASK = 0x7800

    fun parse(msg: ByteArray, offset: Int = 0, length: Int = msg.size - offset): DnsQuestion? {
        if (length < HEADER_SIZE || offset < 0 || offset + length > msg.size) return null
        val flags = u16(msg, offset + 2)
        if (flags and FLAG_QR != 0) return null // 响应报文不处理
        if (flags and OPCODE_MASK != 0) return null // 只支持标准查询
        val qdCount = u16(msg, offset + 4)
        if (qdCount < 1) return null

        val end = offset + length
        var p = offset + HEADER_SIZE
        val name = StringBuilder()
        var guard = 0
        while (true) {
            if (p >= end || guard++ > 128) return null
            val len = msg[p].toInt() and 0xFF
            if (len == 0) {
                p++
                break
            }
            // 问题区的 QNAME 不允许压缩指针
            if (len and 0xC0 != 0) return null
            if (p + 1 + len > end) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(msg, p + 1, len, Charsets.US_ASCII))
            p += 1 + len
        }
        val qname = name.toString().lowercase()
        if (qname.isEmpty()) return null
        if (p + 4 > end) return null
        return DnsQuestion(
            id = u16(msg, offset),
            name = qname,
            qtype = u16(msg, p),
            qclass = u16(msg, p + 2),
            questionEnd = p + 4 - offset,
        )
    }

    /** 本地拦截应答：命中规则的域名不走上游。 */
    fun blockAnswer(msg: ByteArray, q: DnsQuestion, style: String = STYLE_ZERO): ByteArray {
        val nxdomain = style.equals(STYLE_NXDOMAIN, ignoreCase = true)
        val answerA = !nxdomain && q.qtype == TYPE_A && q.qclass == CLASS_IN
        val questionLength = (q.questionEnd - HEADER_SIZE).coerceAtLeast(0)
        val out = ByteArray(HEADER_SIZE + questionLength + if (answerA) 16 else 0)
        writeHeader(out, q.id, if (nxdomain) 3 else 0, answerCount = if (answerA) 1 else 0)
        System.arraycopy(msg, HEADER_SIZE, out, HEADER_SIZE, questionLength)
        if (answerA) {
            var p = HEADER_SIZE + questionLength
            out[p++] = 0xC0.toByte() // 名字指针 → offset 12 的 QNAME
            out[p++] = 0x0C
            putU16(out, p, TYPE_A); p += 2
            putU16(out, p, CLASS_IN); p += 2
            putU16(out, p, 0); p += 2
            putU16(out, p, 60); p += 2 // TTL 60s
            putU16(out, p, 4); p += 2 // RDLENGTH
            // RDATA = 0.0.0.0（数组默认零值）
        }
        return out
    }

    /** 上游全部失败时的 SERVFAIL 应答。 */
    fun serverFailure(msg: ByteArray, q: DnsQuestion): ByteArray {
        val questionLength = (q.questionEnd - HEADER_SIZE).coerceAtLeast(0)
        val out = ByteArray(HEADER_SIZE + questionLength)
        writeHeader(out, q.id, rcode = 2, answerCount = 0)
        System.arraycopy(msg, HEADER_SIZE, out, HEADER_SIZE, questionLength)
        return out
    }

    /** 从上游应答里取第一个 A 记录（只用于日志展示）。 */
    fun firstAnswerIp(msg: ByteArray): String? {
        if (msg.size < HEADER_SIZE) return null
        val flags = u16(msg, 2)
        if (flags and FLAG_QR == 0) return null
        val qd = u16(msg, 4)
        val an = u16(msg, 6)
        if (an < 1) return null
        var p = HEADER_SIZE
        var i = 0
        while (i < qd) {
            while (p < msg.size) {
                val len = msg[p].toInt() and 0xFF
                p++
                if (len == 0) break
                if (len and 0xC0 != 0) {
                    p++
                    break
                }
                p += len
            }
            p += 4
            i++
        }
        i = 0
        while (i < an && p + 12 <= msg.size) {
            if (msg[p].toInt() and 0xC0 != 0) {
                p += 2
            } else {
                while (p < msg.size) {
                    val len = msg[p].toInt() and 0xFF
                    p++
                    if (len == 0) break
                    p += len
                }
            }
            if (p + 10 > msg.size) return null
            val type = u16(msg, p)
            val rdLength = u16(msg, p + 8)
            val rdStart = p + 10
            if (rdStart + rdLength > msg.size) return null
            if (type == TYPE_A && rdLength == 4) {
                return IpPackets.ipText(msg.copyOfRange(rdStart, rdStart + 4))
            }
            p = rdStart + rdLength
            i++
        }
        return null
    }

    fun qtypeName(type: Int): String = when (type) {
        TYPE_A -> "A"
        TYPE_AAAA -> "AAAA"
        5 -> "CNAME"
        15 -> "MX"
        16 -> "TXT"
        33 -> "SRV"
        65 -> "HTTPS"
        255 -> "ANY"
        else -> "T$type"
    }

    private fun writeHeader(out: ByteArray, id: Int, rcode: Int, answerCount: Int) {
        putU16(out, 0, id)
        out[2] = 0x81.toByte() // QR=1, opcode=0, AA=0, TC=0, RD=1
        out[3] = (0x80 or (rcode and 0x0F)).toByte() // RA=1 + RCODE
        putU16(out, 4, 1) // QDCOUNT
        putU16(out, 6, answerCount)
        putU16(out, 8, 0)
        putU16(out, 10, 0)
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value shr 8).toByte()
        buf[offset + 1] = value.toByte()
    }
}

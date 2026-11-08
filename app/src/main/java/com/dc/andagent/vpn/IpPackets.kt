package com.dc.andagent.vpn

/**
 * tun 设备里读到的原始 IP 报文解析 / 回包构造（纯 Kotlin，可 JVM 单测）。
 *
 * 只覆盖 DNS 代理需要的部分：IPv4/IPv6 + UDP。TCP 报文不解析（VPN 只路由
 * DNS 网段，正常 TCP 流量不会进入 tun）。
 */
data class UdpDatagram(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    /** 载荷（DNS 报文）相对报文起点的偏移。 */
    val payloadOffset: Int,
    val payloadLength: Int,
    val ipVersion: Int,
) {
    val srcText: String get() = IpPackets.ipText(srcIp)
    val dstText: String get() = IpPackets.ipText(dstIp)
}

object IpPackets {

    private const val PROTO_UDP = 17

    fun parseUdp(buf: ByteArray, length: Int): UdpDatagram? {
        if (length < 20) return null
        return when ((buf[0].toInt() shr 4) and 0x0F) {
            4 -> parseV4(buf, length)
            6 -> parseV6(buf, length)
            else -> null
        }
    }

    private fun parseV4(buf: ByteArray, length: Int): UdpDatagram? {
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != PROTO_UDP) return null
        val frag = u16(buf, 6)
        // 非首片或还有后续分片都跳过：DNS 请求基本不会分片
        if (frag and 0x1FFF != 0 || frag and 0x2000 != 0) return null
        val declared = u16(buf, 2)
        val total = if (declared in 1..length) declared else length
        val udpLen = u16(buf, ihl + 4)
        if (udpLen < 8) return null
        val payloadOffset = ihl + 8
        var payloadLength = udpLen - 8
        if (payloadOffset + payloadLength > total) payloadLength = total - payloadOffset
        if (payloadLength <= 0) return null
        return UdpDatagram(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            ipVersion = 4,
        )
    }

    private fun parseV6(buf: ByteArray, length: Int): UdpDatagram? {
        if (length < 48) return null
        // 只处理"下一个头就是 UDP"的情况；扩展头链直接放弃
        if ((buf[6].toInt() and 0xFF) != PROTO_UDP) return null
        val udpLen = u16(buf, 44)
        if (udpLen < 8) return null
        val payloadOffset = 48
        var payloadLength = udpLen - 8
        if (payloadOffset + payloadLength > length) payloadLength = length - payloadOffset
        if (payloadLength <= 0) return null
        return UdpDatagram(
            srcIp = buf.copyOfRange(8, 24),
            dstIp = buf.copyOfRange(24, 40),
            srcPort = u16(buf, 40),
            dstPort = u16(buf, 42),
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            ipVersion = 6,
        )
    }

    /**
     * 构造发回给客户端的 IPv4 + UDP 报文。
     *
     * @param clientIp 原始请求源地址（回包的目的地址）
     * @param serverIp 原始请求目的地址（回包的源地址）
     */
    fun buildUdpReplyIpv4(
        clientIp: ByteArray,
        clientPort: Int,
        serverIp: ByteArray,
        serverPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(clientIp.size == 4 && serverIp.size == 4) { "IPv4 地址必须是 4 字节" }
        val total = 28 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        putU16(out, 2, total)
        putU16(out, 4, 0)
        putU16(out, 6, 0x4000) // DF
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        System.arraycopy(serverIp, 0, out, 12, 4)
        System.arraycopy(clientIp, 0, out, 16, 4)
        putU16(out, 10, checksum(out, 0, 20))
        putU16(out, 20, serverPort)
        putU16(out, 22, clientPort)
        putU16(out, 24, 8 + payload.size)
        putU16(out, 26, 0) // UDP 校验和 0 = 不校验（IPv4 允许）
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    fun ipText(raw: ByteArray): String = when (raw.size) {
        4 -> raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
        16 -> ipv6Text(raw)
        else -> ""
    }

    private fun ipv6Text(raw: ByteArray): String {
        val parts = IntArray(8) {
            ((raw[it * 2].toInt() and 0xFF) shl 8) or (raw[it * 2 + 1].toInt() and 0xFF)
        }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (parts[i] == 0) {
                var j = i
                while (j < 8 && parts[j] == 0) j++
                if (j - i > bestLen) {
                    bestStart = i
                    bestLen = j - i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) return parts.joinToString(":") { Integer.toHexString(it) }
        val head = parts.take(bestStart).joinToString(":") { Integer.toHexString(it) }
        val tail = parts.drop(bestStart + bestLen).joinToString(":") { Integer.toHexString(it) }
        return "$head::$tail"
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun putU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value shr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    /** 16 位反码求和校验（RFC 1071）。 */
    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}

package com.dc.andagent.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for the tun packet parser / reply builder. */
class IpPacketsTest {

    @Test
    fun parsesIpv4UdpDatagram() {
        val payload = "hello".toByteArray()
        val packet = ipv4Udp(
            src = bytes(10, 111, 222, 1),
            dst = bytes(192, 168, 1, 2),
            srcPort = 53,
            dstPort = 5353,
            payload = payload,
        )
        val udp = IpPackets.parseUdp(packet, packet.size)
        assertNotNull(udp)
        assertEquals(4, udp!!.ipVersion)
        assertEquals("10.111.222.1", udp.srcText)
        assertEquals("192.168.1.2", udp.dstText)
        assertEquals(53, udp.srcPort)
        assertEquals(5353, udp.dstPort)
        assertArrayEquals(payload, packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength))
    }

    @Test
    fun rejectsTcpPackets() {
        val packet = ipv4Udp(bytes(10, 0, 0, 1), bytes(10, 0, 0, 2), 1234, 53, ByteArray(4))
        packet[9] = 6 // TCP
        assertNull(IpPackets.parseUdp(packet, packet.size))
    }

    @Test
    fun rejectsNonFirstFragments() {
        val packet = ipv4Udp(bytes(10, 0, 0, 1), bytes(10, 0, 0, 2), 1234, 53, ByteArray(4))
        packet[7] = 0x01 // fragment offset = 1
        assertNull(IpPackets.parseUdp(packet, packet.size))
    }

    @Test
    fun parsesIpv6UdpDatagram() {
        val packet = ipv6Udp(53, 4321, "dns".toByteArray())
        val udp = IpPackets.parseUdp(packet, packet.size)
        assertNotNull(udp)
        assertEquals(6, udp!!.ipVersion)
        assertEquals(53, udp.srcPort)
        assertEquals(4321, udp.dstPort)
        assertEquals("2001:db8::1", udp.srcText)
    }

    @Test
    fun replySwapsEndpointsAndKeepsPayload() {
        val client = bytes(10, 111, 222, 5)
        val server = bytes(10, 111, 222, 1)
        val payload = bytes(1, 2, 3, 4, 5, 6)
        val reply = IpPackets.buildUdpReplyIpv4(
            clientIp = client,
            clientPort = 40000,
            serverIp = server,
            serverPort = 53,
            payload = payload,
        )
        val parsed = IpPackets.parseUdp(reply, reply.size)
        assertNotNull(parsed)
        assertEquals("10.111.222.1", parsed!!.srcText)
        assertEquals("10.111.222.5", parsed.dstText)
        assertEquals(53, parsed.srcPort)
        assertEquals(40000, parsed.dstPort)
        assertArrayEquals(payload, reply.copyOfRange(parsed.payloadOffset, parsed.payloadOffset + parsed.payloadLength))
    }

    @Test
    fun replyHasValidIpv4HeaderChecksum() {
        val reply = IpPackets.buildUdpReplyIpv4(
            clientIp = bytes(10, 111, 222, 5),
            clientPort = 40000,
            serverIp = bytes(10, 111, 222, 1),
            serverPort = 53,
            payload = ByteArray(20),
        )
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((reply[i].toInt() and 0xFF) shl 8) or (reply[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals("IPv4 头校验和必须补码求和为 0xFFFF", 0xFFFF, sum)
    }

    @Test
    fun ipTextFormatsIpv4AndIpv6() {
        assertEquals("192.168.0.1", IpPackets.ipText(byteArrayOf(192.toByte(), 168.toByte(), 0, 1)))
        assertEquals("::1", IpPackets.ipText(ByteArray(16).also { it[15] = 1 }))
        assertNull(IpPackets.parseUdp(ByteArray(4), 4))
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ipv4Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[2] = (total shr 8).toByte()
        out[3] = total.toByte()
        out[8] = 64
        out[9] = 17
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        out[20] = (srcPort shr 8).toByte()
        out[21] = srcPort.toByte()
        out[22] = (dstPort shr 8).toByte()
        out[23] = dstPort.toByte()
        out[24] = ((8 + payload.size) shr 8).toByte()
        out[25] = (8 + payload.size).toByte()
        System.arraycopy(payload, 0, out, 28, payload.size)
        return out
    }

    /** 2001:db8::1 → 2001:db8::5 */
    private fun ipv6Udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 48 + payload.size
        val out = ByteArray(total)
        out[0] = 0x60
        out[4] = ((8 + payload.size) shr 8).toByte()
        out[5] = (8 + payload.size).toByte()
        out[6] = 17
        out[7] = 64
        out[8] = 0x20
        out[9] = 0x01
        out[10] = 0x0D
        out[11] = 0xB8.toByte()
        out[23] = 0x01
        out[24] = 0x20
        out[25] = 0x01
        out[26] = 0x0D
        out[27] = 0xB8.toByte()
        out[39] = 0x05
        out[40] = (srcPort shr 8).toByte()
        out[41] = srcPort.toByte()
        out[42] = (dstPort shr 8).toByte()
        out[43] = dstPort.toByte()
        out[44] = ((8 + payload.size) shr 8).toByte()
        out[45] = (8 + payload.size).toByte()
        System.arraycopy(payload, 0, out, 48, payload.size)
        return out
    }
}

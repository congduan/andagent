package com.dc.andagent.vpn

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the DNS parser / local answer builder. */
class DnsPacketTest {

    @Test
    fun parsesStandardQuery() {
        val packet = query("Ads.Example.COM", DnsPacket.TYPE_A)
        val question = DnsPacket.parse(packet)
        assertNotNull(question)
        assertEquals("ads.example.com", question!!.name)
        assertEquals(DnsPacket.TYPE_A, question.qtype)
        assertEquals(DnsPacket.CLASS_IN, question.qclass)
        assertEquals(0x1234, question.id)
    }

    @Test
    fun ignoresResponsePackets() {
        val packet = query("example.com", DnsPacket.TYPE_A)
        packet[2] = 0x81.toByte() // 置 QR 位
        assertNull(DnsPacket.parse(packet))
    }

    @Test
    fun ignoresTruncatedPacket() {
        assertNull(DnsPacket.parse(query("example.com", DnsPacket.TYPE_A).copyOf(10)))
    }

    @Test
    fun blockAnswerReturnsZeroIpForAQuery() {
        val packet = query("ads.example.com", DnsPacket.TYPE_A)
        val question = DnsPacket.parse(packet)!!
        val answer = DnsPacket.blockAnswer(packet, question, DnsPacket.STYLE_ZERO)

        assertEquals(0x1234, u16(answer, 0))
        assertEquals(0x8180, u16(answer, 2))
        assertEquals(1, u16(answer, 4))
        assertEquals(1, u16(answer, 6))
        assertEquals(12 + (question.questionEnd - 12) + 16, answer.size)

        val record = 12 + (question.questionEnd - 12)
        assertEquals(0xC00C, u16(answer, record))
        assertEquals(DnsPacket.TYPE_A, u16(answer, record + 2))
        assertEquals(DnsPacket.CLASS_IN, u16(answer, record + 4))
        assertEquals(60, u16(answer, record + 8))
        assertEquals(4, u16(answer, record + 10))
        for (i in 0 until 4) {
            assertEquals("RDATA 应为 0.0.0.0", 0, answer[record + 12 + i].toInt())
        }
    }

    @Test
    fun blockAnswerUsesNxdomainWhenConfigured() {
        val packet = query("ads.example.com", DnsPacket.TYPE_A)
        val question = DnsPacket.parse(packet)!!
        val answer = DnsPacket.blockAnswer(packet, question, DnsPacket.STYLE_NXDOMAIN)
        assertEquals(0x8183, u16(answer, 2))
        assertEquals(0, u16(answer, 6))
    }

    @Test
    fun blockAnswerSkipsAaaaRecord() {
        val packet = query("ads.example.com", DnsPacket.TYPE_AAAA)
        val question = DnsPacket.parse(packet)!!
        val answer = DnsPacket.blockAnswer(packet, question, DnsPacket.STYLE_ZERO)
        assertEquals(0x8180, u16(answer, 2))
        assertEquals(0, u16(answer, 6))
        assertEquals(12 + (question.questionEnd - 12), answer.size)
    }

    @Test
    fun serverFailureSetsRcode2() {
        val packet = query("example.com", DnsPacket.TYPE_A)
        val question = DnsPacket.parse(packet)!!
        val answer = DnsPacket.serverFailure(packet, question)
        assertEquals(0x8182, u16(answer, 2))
        assertEquals(0, u16(answer, 6))
    }

    @Test
    fun firstAnswerIpReadsTheARecord() {
        val answer = buildAnswerWithIp("example.com", bytes(93, 184, 216, 34))
        assertEquals("93.184.216.34", DnsPacket.firstAnswerIp(answer))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun qtypeNamesAreReadable() {
        assertEquals("A", DnsPacket.qtypeName(DnsPacket.TYPE_A))
        assertEquals("AAAA", DnsPacket.qtypeName(DnsPacket.TYPE_AAAA))
        assertEquals("T99", DnsPacket.qtypeName(99))
    }

    /* ------------------------------ helpers ------------------------------ */

    private fun query(name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(type shr 8)
        out.write(type and 0xFF)
        out.write(0)
        out.write(1)
        return out.toByteArray()
    }

    /** 构造一个带 A 记录的上游应答，用于校验 firstAnswerIp。 */
    private fun buildAnswerWithIp(name: String, ip: ByteArray): ByteArray {
        val question = query(name, DnsPacket.TYPE_A)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
        out.write(question, 12, question.size - 12)
        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04))
        out.write(ip)
        return out.toByteArray()
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    @Test
    fun blockAnswerKeepsTheQuestionSection() {
        val packet = query("very.long.sub.domain.example.com", DnsPacket.TYPE_A)
        val question = DnsPacket.parse(packet)!!
        val answer = DnsPacket.blockAnswer(packet, question, DnsPacket.STYLE_ZERO)
        val questionLength = question.questionEnd - 12
        assertTrue(questionLength > 0)
        for (i in 0 until questionLength) {
            assertEquals(packet[12 + i], answer[12 + i])
        }
    }
}

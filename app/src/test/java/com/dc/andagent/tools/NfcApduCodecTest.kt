package com.dc.andagent.tools

import org.junit.Assert.*
import org.junit.Test

/** Pure-logic tests for the R115 ISO-DEP APDU / FeliCa helpers (no Android calls). */
class NfcApduCodecTest {

    /* ------------------------------ hex + frames ------------------------------ */

    @Test
    fun normalizeHexBlobAcceptsCommonShapesAndRejectsJunk() {
        assertEquals("AABBCC", normalizeHexBlob("aa bb cc"))
        assertEquals("AABBCC", normalizeHexBlob("0xAA:BB-CC"))
        assertEquals("0011", normalizeHexBlob(" 0X0011 "))
        assertNull(normalizeHexBlob("ABC"))        // odd length
        assertNull(normalizeHexBlob("GG0011"))     // non-hex
        assertNull(normalizeHexBlob("   "))        // empty
    }

    @Test
    fun parseHexFramesSplitsAndReportsInvalidTokens() {
        val set = parseHexFrames(
            """
            00A4040007A0000002471001   # SELECT eMRTD
            00B0000000; 00B0000010
            00B0GG0000
            """.trimIndent(),
        )
        assertEquals(3, set.frames.size)
        assertEquals("00A4040007A0000002471001", bytesToHex(set.frames[0]))
        assertEquals("00B0000000", bytesToHex(set.frames[1]))
        assertEquals("00B0000010", bytesToHex(set.frames[2]))
        assertEquals(listOf("00B0GG0000"), set.invalid)

        val empty = parseHexFrames("# comment only\n// another\n")
        assertTrue(empty.frames.isEmpty())
        assertTrue(empty.invalid.isEmpty())
    }

    /* ---------------------------------- APDUs ---------------------------------- */

    @Test
    fun parseApduClassifiesIso7816Cases() {
        val case1 = parseApdu(hex("00A40000"))
        assertTrue(case1.valid)
        assertEquals("1", case1.kind)
        assertEquals(0xA4, case1.ins)
        assertNull(case1.le)

        val case2 = parseApdu(hex("00B0000010"))
        assertEquals("2", case2.kind)
        assertEquals(0x10, case2.le)
        assertEquals(256, parseApdu(hex("00B0000000")).le) // Le=0x00 → 256

        val case3 = parseApdu(hex("00D6000008" + "0011223344556677"))
        assertEquals("3", case3.kind)
        assertEquals(8, case3.lc)
        assertEquals(8, case3.data.size)
        assertNull(case3.le)

        val case4 = parseApdu(hex("00D6000008" + "0011223344556677" + "00"))
        assertEquals("4", case4.kind)
        assertEquals(256, case4.le)

        val case3e = parseApdu(hex("00D60000" + "00" + "0008" + "0011223344556677"))
        assertEquals("3E", case3e.kind)
        assertEquals(8, case3e.lc)

        val case4e = parseApdu(hex("00D60000" + "00" + "0008" + "0011223344556677" + "0000"))
        assertEquals("4E", case4e.kind)
        assertEquals(65536, case4e.le)
    }

    @Test
    fun parseApduRejectsInconsistentLengthsWithoutThrowing() {
        val bad = parseApdu(hex("00B0000010FF")) // Lc=0x10 declared, 1 stray byte
        assertFalse(bad.valid)
        assertNotNull(bad.error)

        val short = parseApdu(hex("00A4"))
        assertFalse(short.valid)
        assertEquals("?", short.kind)
    }

    @Test
    fun selectAidAndAppliedAidRoundTrip() {
        val aid = hex("A0000002471001")
        val select = selectAidApdu(aid)
        assertEquals("00A4040007A0000002471001", bytesToHex(select))
        assertEquals(12, select.size)
        assertEquals("A0000002471001", appliedAidHex(select))
        assertNull(appliedAidHex(hex("00B0000000")))
        assertNull(appliedAidHex(selectAidApdu(ByteArray(0))))
    }

    @Test
    fun statusWordDecodingCoversTheCommonCases() {
        assertTrue(describeStatusWord(0x90, 0x00).startsWith("成功"))
        assertTrue(describeStatusWord(0x6A, 0x82).contains("未找到"))
        assertTrue(describeStatusWord(0x61, 0x10).contains("GET RESPONSE"))
        assertTrue(describeStatusWord(0x63, 0xC3).contains("还可重试 3 次"))
        assertTrue(describeStatusWord(0x6D, 0x00).contains("INS"))
        assertTrue(describeStatusWord(0x12, 0x34).contains("0x1234"))
    }

    @Test
    fun dangerReasonsGateWritesAndPinOperationsOnly() {
        assertEquals(null, apduDangerReason(parseApdu(hex("00B0000000"))))
        assertTrue(apduDangerReason(parseApdu(hex("00D6000008" + "0011223344556677")))!!.contains("写入"))
        assertTrue(apduDangerReason(parseApdu(hex("0020008008" + "3132333435363738")))!!.contains("VERIFY"))
        assertTrue(apduDangerReason(parseApdu(hex("0024008008" + "3132333435363738")))!!.contains("CHANGE REFERENCE DATA"))
        assertTrue(apduDangerReason(parseApdu(hex("00E40000")))!!.contains("DELETE FILE"))
    }

    /* ---------------------------- sensitive applications ---------------------------- */

    @Test
    fun sensitiveApplicationsMatchByAidPrefix() {
        val emrtd = sensitiveAppsIn(listOf(selectAidApdu(hex("A0000002471001"))))
        assertEquals(1, emrtd.size)
        assertEquals("travelDocument", emrtd[0].kind)

        // Visa AID with a longer RID extension still matches.
        val visa = matchSensitiveApp("A000000003101001")
        assertNotNull(visa)
        assertEquals("payment", visa!!.kind)

        val pboc = matchSensitiveApp("A00000033301010101")
        assertNotNull(pboc)

        // Selecting the bare UnionPay RID still addresses the payment app family → gated.
        assertEquals("payment", matchSensitiveApp("A000000333")!!.kind)

        // Too short / unregistered AIDs are not classified.
        assertNull(matchSensitiveApp("A0000003"))
        assertNull(matchSensitiveApp("F0010203040506"))
        assertTrue(sensitiveAppsIn(listOf(selectAidApdu(hex("F0010203040506")))).isEmpty())
    }

    @Test
    fun emvGetProcessingOptionsIsSensitiveEvenWithoutSelect() {
        val apps = sensitiveAppsIn(listOf(hex("80A800000483020000")))
        assertEquals(1, apps.size)
        assertEquals("payment", apps[0].kind)
    }

    /* --------------------------- historical bytes (ATS) --------------------------- */

    @Test
    fun compactTlvParsesCategoryIndicatorChains() {
        val tlv = parseCompactTlv(byteArrayOf(0x80.toByte(), 0x02, 0x02, 0x41, 0x47))
        assertNotNull(tlv)
        assertEquals(1, tlv!!.size)
        assertEquals(0x02, tlv[0].first)
        assertEquals("AG", String(tlv[0].second, Charsets.US_ASCII))
        assertEquals("发卡机构标识", compactTlvName(0x02))

        assertNull(parseCompactTlv(byteArrayOf(0x12, 0x34)))
        assertNull(parseCompactTlv(ByteArray(0)))
        // Truncated TLV (declares 4 bytes, only 1 present) must not parse.
        assertNull(parseCompactTlv(byteArrayOf(0x80.toByte(), 0x02, 0x04, 0x41)))
    }

    /* ---------------------------------- FeliCa ---------------------------------- */

    @Test
    fun felicaServiceAndBlockListEncodingIsLittleEndian() {
        assertArrayEquals(byteArrayOf(0x8B.toByte(), 0x00), felicaServiceBytes(0x008B))
        assertArrayEquals(byteArrayOf(0x00, 0x00), felicaBlockListElement(0, 0))
        assertArrayEquals(byteArrayOf(0x0C, 0x10), felicaBlockListElement(1, 12))
    }

    @Test
    fun felicaReadFrameLayoutMatchesReadWithoutEncryption() {
        val idm = hex("0102030405060708")
        val frame = buildFelicaReadWithoutEncryption(idm, listOf(0x008B), listOf(0 to 0))
        assertEquals(16, frame.size)
        assertEquals(0x0F, frame[0].toInt() and 0xFF) // LEN = body length
        assertEquals(0x06, frame[1].toInt() and 0xFF) // Read Without Encryption
        assertArrayEquals(idm, frame.copyOfRange(2, 10))
        assertEquals(0x01, frame[10].toInt() and 0xFF) // one service
        assertEquals(0x8B, frame[11].toInt() and 0xFF)
        assertEquals(0x00, frame[12].toInt() and 0xFF)
        assertEquals(0x01, frame[13].toInt() and 0xFF) // one block
        assertEquals(0x00, frame[14].toInt() and 0xFF)
        assertEquals(0x00, frame[15].toInt() and 0xFF)
    }

    @Test
    fun felicaReadReplyParsesStatusAndBlocks() {
        val block = ByteArray(16) { it.toByte() }
        val response = byteArrayOf(0x1C) + byteArrayOf(0x07) + hex("0102030405060708") +
            byteArrayOf(0x00, 0x00, 0x01) + block
        val reply = parseFelicaReadReply(response, 1)
        assertTrue(reply.status!!.ok)
        assertEquals(1, reply.blocks.size)
        assertArrayEquals(block, reply.blocks[0])
        assertNull(reply.error)

        val failed = response.copyOf()
        failed[10] = 0xFF.toByte()
        val bad = parseFelicaReadReply(failed, 1)
        assertFalse(bad.status!!.ok)
        assertTrue(bad.blocks.isEmpty())
        assertTrue(bad.status!!.text.contains("0xFF"))

        assertNull(parseFelicaReadReply(byteArrayOf(0x01, 0x07), 1).status)
        assertNotNull(parseFelicaReadReply(byteArrayOf(0x01, 0x07), 1).error)
        assertNotNull(parseFelicaReadReply(byteArrayOf(0x03, 0x03, 0x00), 1).error)
    }

    @Test
    fun felicaStatusAndCommandHelpers() {
        val readReply = byteArrayOf(0x1C) + byteArrayOf(0x07) + ByteArray(8) + byteArrayOf(0x00, 0x00, 0x01)
        val status = felicaStatusOf(readReply)
        assertNotNull(status)
        assertTrue(status!!.ok)

        assertNull(felicaStatusOf(byteArrayOf(0x0E, 0x01))) // polling reply has no status flags
        assertEquals(0x06, felicaCommandOf(byteArrayOf(0x04, 0x06, 0x00)))
        assertNull(felicaCommandOf(byteArrayOf(0x04)))

        assertEquals("Read Without Encryption", felicaCommandName(0x06))
        assertTrue(felicaDangerReason(byteArrayOf(0x0A, 0x08, 0x00))!!.contains("写入"))
        assertNull(felicaDangerReason(byteArrayOf(0x0A, 0x06, 0x00)))
    }

    @Test
    fun felicaFrameLengthByteIsRepairedOnlyWhenObviouslyMissing() {
        val good = byteArrayOf(0x05, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00)
        val (unchanged, noNote) = normalizeFelicaFrame(good)
        assertSame(good, unchanged)
        assertNull(noNote)

        val missingLen = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x02)
        val (fixed, note) = normalizeFelicaFrame(missingLen)
        assertEquals(7, fixed.size)
        assertEquals(0x06, fixed[0].toInt() and 0xFF)
        assertNotNull(note)

        val wrongLen = byteArrayOf(0x30, 0x08, 0x00)
        val (kept, warn) = normalizeFelicaFrame(wrongLen)
        assertSame(wrongLen, kept)
        assertTrue(warn!!.contains("不符"))
    }

    private fun hex(text: String): ByteArray = hexToBytes(text.replace(" ", ""))!!
}

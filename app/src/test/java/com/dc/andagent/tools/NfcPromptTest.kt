package com.dc.andagent.tools

import org.junit.Assert.*
import org.junit.Test

/**
 * R145: buildNfcPrompt 纯逻辑测试（无 Android 框架调用）。
 * describeNfcPayload 直接吃纯数据 [NfcTagPayload]，可精确断言各类型提示词文本；
 * NfcTagDedup 用注入时钟验证 3 秒去重窗口。extractNfcTag / buildNfcPrompt(intent)
 * 依赖 android.nfc，由真机路径覆盖，不在 JVM 单测里调用。
 */
class NfcPromptTest {

    /* ----------------------- describeNfcPayload: NDEF ----------------------- */

    @Test
    fun ndefTextRecordPromptMatchesBrief() {
        val payload = NfcTagPayload(
            idHex = "04:A3:FF",
            techs = listOf("NfcA", "Ndef"),
            records = listOf(NfcRecordData(NfcRecordKind.TEXT, text = "欢迎光临", payloadSize = 12)),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 NDEF 文本记录：\"欢迎光临\"。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun ndefUriRecordPromptMatchesBrief() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA", "Ndef"),
            records = listOf(NfcRecordData(NfcRecordKind.URI, uri = "https://example.com/nfc", payloadSize = 20)),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 URI 记录：https://example.com/nfc。请读取并分析。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun ndefOtherRecordGivesTypeByteSummary() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA", "Ndef"),
            records = listOf(NfcRecordData(NfcRecordKind.OTHER, typeHex = "616263", payloadSize = 8)),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 NDEF 记录（type 字节=616263，payload 8B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun ndefOtherRecordEmptyTypeHexStillRenders() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA"),
            records = listOf(NfcRecordData(NfcRecordKind.OTHER, typeHex = "", payloadSize = 0)),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 NDEF 记录（type 字节=空，payload 0B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun multipleRecordsAreJoinedAndSuffixFollowsFirstRecord() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA", "Ndef"),
            records = listOf(
                NfcRecordData(NfcRecordKind.TEXT, text = "你好", payloadSize = 6),
                NfcRecordData(NfcRecordKind.URI, uri = "https://x", payloadSize = 9),
                NfcRecordData(NfcRecordKind.OTHER, typeHex = "FF", payloadSize = 3),
            ),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 NDEF 文本记录：\"你好\"；URI 记录：https://x；NDEF 记录（type 字节=FF，payload 3B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun uriFirstUsesShortSuffixEvenWithFollowingRecords() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA", "Ndef"),
            records = listOf(
                NfcRecordData(NfcRecordKind.URI, uri = "tel:123", payloadSize = 4),
                NfcRecordData(NfcRecordKind.TEXT, text = "名片", payloadSize = 6),
            ),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 URI 记录：tel:123；NDEF 文本记录：\"名片\"。请读取并分析。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun ndefTakesPriorityOverBareTagMetadata() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("MifareClassic", "NfcA"),
            records = listOf(NfcRecordData(NfcRecordKind.TEXT, text = "T", payloadSize = 1)),
            mifareSize = 1024,
        )
        val prompt = describeNfcPayload(payload)!!
        assertTrue(prompt.startsWith("NFC 标签已贴上：检测到 NDEF 文本记录"))
        assertFalse(prompt.contains("tag id="))
        assertFalse(prompt.contains("容量"))
    }

    /* --------------------- describeNfcPayload: 裸 tag --------------------- */

    @Test
    fun bareMifareTagPromptMatchesBrief() {
        val payload = NfcTagPayload(
            idHex = "04:A3:..:FF",
            techs = listOf("MifareClassic", "NfcA"),
            mifareSize = 1024,
        )
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3:..:FF，技术=[MifareClassic, NfcA]，容量 1024B。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun bareTagFallsBackToNdefMaxSizeForCapacity() {
        val payload = NfcTagPayload(
            idHex = "04:A3",
            techs = listOf("NfcA", "Ndef"),
            ndefMaxSize = 888,
        )
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA, Ndef]，容量 888B。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun bareTagWithoutCapacityOmitsCapacityClause() {
        val payload = NfcTagPayload(idHex = "04:A3", techs = listOf("NfcA"))
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun capacityBytesPrefersMifareOverNdefMaxSize() {
        assertEquals(1024, NfcTagPayload("x", listOf("MifareClassic"), null, 1024, 716).capacityBytes)
        assertEquals(716, NfcTagPayload("x", listOf("Ndef"), null, null, 716).capacityBytes)
        assertNull(NfcTagPayload("x", listOf("NfcA"), null, null, null).capacityBytes)
    }

    @Test
    fun emptyUnparseablePayloadReturnsNull() {
        assertNull(describeNfcPayload(NfcTagPayload(idHex = "", techs = emptyList(), records = null)))
        assertNull(describeNfcPayload(NfcTagPayload(idHex = "", techs = emptyList(), records = emptyList())))
    }

    @Test
    fun techsOnlyStillProducesBarePrompt() {
        val payload = NfcTagPayload(idHex = "", techs = listOf("NfcA"), records = null)
        assertEquals(
            "NFC 标签已贴上：tag id=，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            describeNfcPayload(payload),
        )
    }

    /* ------------------------------ NfcTagDedup ------------------------------ */

    @Test
    fun dedupSuppressesSameTagIdWithin3Seconds() {
        var now = 0L
        val dedup = NfcTagDedup(nowMs = { now }, windowMs = 3_000L)
        assertTrue(dedup.shouldInject("04:A3:FF"))   // t=0 首次放行
        now = 2_999L
        assertFalse(dedup.shouldInject("04:A3:FF"))  // 同 id、2999ms < 3000 → 抑制
    }

    @Test
    fun dedupAllowsSameTagIdOnceWindowExpires() {
        var now = 0L
        val dedup = NfcTagDedup(nowMs = { now }, windowMs = 3_000L)
        assertTrue(dedup.shouldInject("04:A3:FF"))
        now = 1_000L
        assertFalse(dedup.shouldInject("04:A3:FF"))  // 窗口内抑制
        now = 3_000L
        assertTrue(dedup.shouldInject("04:A3:FF"))   // 恰好 3s → 放行
    }

    @Test
    fun dedupAllowsDifferentTagIdImmediately() {
        var now = 0L
        val dedup = NfcTagDedup(nowMs = { now }, windowMs = 3_000L)
        assertTrue(dedup.shouldInject("AA"))
        assertTrue(dedup.shouldInject("BB"))         // 不同 tag → 立即放行
        assertFalse(dedup.shouldInject("BB"))        // 新 id 自身窗口内再贴 → 抑制
    }

    @Test
    fun dedupBoundaryExactly3SecondsIsAllowed() {
        var now = 0L
        val dedup = NfcTagDedup(nowMs = { now }, windowMs = 3_000L)
        assertTrue(dedup.shouldInject("AA"))
        now = 3_000L
        assertTrue(dedup.shouldInject("AA"))         // t-lastAt == window → 不抑制
    }

    @Test
    fun dedupHandlesEmptyKeyWithoutCrashing() {
        var now = 0L
        val dedup = NfcTagDedup(nowMs = { now }, windowMs = 3_000L)
        assertTrue(dedup.shouldInject(""))
        now = 1L
        assertFalse(dedup.shouldInject(""))          // 空 id（无 id 的 tag）同样去重
    }
}

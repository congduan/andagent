package com.dc.andagent.tools

import android.content.Intent
import android.nfc.NfcAdapter
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R145 / R145c: NFC 提示词逻辑测试。
 * describeNfcPayload 直接吃纯数据 [NfcTagPayload]，可精确断言各类型提示词文本；
 * NfcTagDedup 用注入时钟验证 3 秒去重窗口。review 建议 2 后 describeNfcPayload 增加
 * cached 参数：false → 原 R145 文案（无 CACHED_TAP_HINT），true → 带缓存提示。
 * [NfcPromptBuildIntentTest] 用 Robolectric 构造纯 Intent 覆盖 buildNfcPrompt 的无 Tag 分支；
 * 真机 Tag 对象路径（有 Tag → 带 hint）仍由真机覆盖。
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
            "NFC 标签已贴上：检测到 NDEF 文本记录：\"欢迎光临\"。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：检测到 URI 记录：https://example.com/nfc。请读取并分析$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：检测到 NDEF 记录（type 字节=616263，payload 8B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：检测到 NDEF 记录（type 字节=空，payload 0B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：检测到 NDEF 文本记录：\"你好\"；URI 记录：https://x；NDEF 记录（type 字节=FF，payload 3B）。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：检测到 URI 记录：tel:123；NDEF 文本记录：\"名片\"。请读取并分析$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：tag id=04:A3:..:FF，技术=[MifareClassic, NfcA]，容量 1024B。请用 nfc_read 读取数据并分析这张卡的类型与内容$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA, Ndef]，容量 888B。请用 nfc_read 读取数据并分析这张卡的类型与内容$CACHED_TAP_HINT。",
            describeNfcPayload(payload),
        )
    }

    @Test
    fun bareTagWithoutCapacityOmitsCapacityClause() {
        val payload = NfcTagPayload(idHex = "04:A3", techs = listOf("NfcA"))
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容$CACHED_TAP_HINT。",
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
            "NFC 标签已贴上：tag id=，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容$CACHED_TAP_HINT。",
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

    /* --------------------- R145c: 缓存提示词 / 缓存 JSON --------------------- */

    @Test
    fun cachedTapHintLiteralIsPresentInAllPromptKinds() {
        val kinds = listOf(
            NfcTagPayload("04:A3", listOf("NfcA"), records = listOf(NfcRecordData(NfcRecordKind.TEXT, text = "T"))),
            NfcTagPayload("04:A3", listOf("NfcA"), records = listOf(NfcRecordData(NfcRecordKind.URI, uri = "https://x"))),
            NfcTagPayload("04:A3", listOf("MifareClassic")),
        )
        kinds.forEach { payload ->
            val prompt = describeNfcPayload(payload)!!
            assertTrue(
                "prompt 缺少 R145c 缓存提示：$prompt",
                prompt.contains("碰卡数据已缓存，调用即得，无需再贴卡"),
            )
        }
    }

    @Test
    fun cachedJsonFullReadPreservesBaseFieldsAndMarksSource() {
        // 模拟一次完整 read(tag) 的结果：uid/techList/records 全量字段。
        val base = buildJsonObject {
            put("uid", "04A3FF")
            put("idSize", 3)
            put("isNdef", true)
            put("maxSize", 716)
            putJsonArray("techList") { add("android.nfc.tech.Ndef") }
            putJsonArray("records") { add("rec") }
        }
        val json = buildCachedTagJson(base, "04A3FF", 3, listOf("android.nfc.tech.Ndef"))
        assertEquals("04A3FF", json["uid"]!!.jsonPrimitive.content)
        assertEquals(3, json["idSize"]!!.jsonPrimitive.int)
        assertTrue(json["isNdef"]!!.jsonPrimitive.boolean)
        assertEquals(716, json["maxSize"]!!.jsonPrimitive.int)
        assertEquals(1, json["techList"]!!.jsonArray.size)
        assertEquals(1, json["records"]!!.jsonArray.size)
        assertEquals("cached-from-tap", json["source"]!!.jsonPrimitive.content)
        assertFalse(json["failedToConnect"]!!.jsonPrimitive.boolean)
        assertFalse(json.containsKey("note"))       // 成功态不带降级说明
    }

    @Test
    fun cachedJsonDegradedMarksFailedToConnectAndKeepsStaticFields() {
        // connect 失败 → 仅缓存静态信息，如实标注 failedToConnect + 引导 force 重读。
        val json = buildCachedTagJson(
            null, "04:A3:FF", 3,
            listOf("android.nfc.tech.MifareClassic", "android.nfc.tech.NfcA"),
        )
        assertEquals("cached-from-tap", json["source"]!!.jsonPrimitive.content)
        assertTrue(json["failedToConnect"]!!.jsonPrimitive.boolean)
        assertEquals("04:A3:FF", json["uid"]!!.jsonPrimitive.content)
        assertEquals(3, json["idSize"]!!.jsonPrimitive.int)
        assertEquals(2, json["techList"]!!.jsonArray.size)
        assertEquals("android.nfc.tech.NfcA", json["techList"]!!.jsonArray[1].jsonPrimitive.content)
        assertTrue(json["note"]!!.jsonPrimitive.content.contains("force=true"))
        assertFalse(json.containsKey("records"))    // 降级态不含 NDEF 全量字段
        assertFalse(json.containsKey("maxSize"))
    }

    /* ------------- review 建议 2：提示语按缓存占位是否成功选择文案 ------------- */

    @Test
    fun noTagUsesLegacyR145PromptWithoutCachedHint() {
        // cached=false（intent 无 Tag 对象、cacheNfcTagFromIntent 返回 false）→ 原 R145 文案，
        // 不带 CACHED_TAP_HINT，避免对对话谎称「数据已缓存」。
        val bare = NfcTagPayload(idHex = "04:A3", techs = listOf("NfcA"))
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            describeNfcPayload(bare, cached = false),
        )
        assertFalse(describeNfcPayload(bare, cached = false)!!.contains(CACHED_TAP_HINT))

        val ndef = NfcTagPayload(
            idHex = "04:A3", techs = listOf("NfcA"),
            records = listOf(NfcRecordData(NfcRecordKind.TEXT, text = "T")),
        )
        assertEquals(
            "NFC 标签已贴上：检测到 NDEF 文本记录：\"T\"。请用 nfc_read 读取完整数据并分析这条标签的用途与内容结构。",
            describeNfcPayload(ndef, cached = false),
        )
        assertFalse(describeNfcPayload(ndef, cached = false)!!.contains(CACHED_TAP_HINT))
    }

    @Test
    fun cachedTapStillCarriesHintWhenCached() {
        // cached=true（占位/复用成功）→ 带 CACHED_TAP_HINT 的 R145c 文案不变。
        val bare = NfcTagPayload(idHex = "04:A3", techs = listOf("NfcA"))
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[NfcA]。请用 nfc_read 读取数据并分析这张卡的类型与内容$CACHED_TAP_HINT。",
            describeNfcPayload(bare, cached = true),
        )
        assertTrue(describeNfcPayload(bare, cached = true)!!.contains(CACHED_TAP_HINT))
        // 默认值仍为 true：既有单测的 describeNfcPayload(payload) 调用不受影响。
        assertTrue(describeNfcPayload(bare)!!.contains(CACHED_TAP_HINT))
    }
}

/**
 * review 建议 2 端到端：无 Tag 对象的 intent（只有 EXTRA_ID）经 [buildNfcPrompt] 时，
 * [cacheNfcTagFromIntent] 返回 false → 注入不带 [CACHED_TAP_HINT] 的原 R145 文案。
 * 仅用 Robolectric 构造纯 Intent（不构造 android.nfc.Tag 对象），真机 Tag 路径仍由真机覆盖。
 */
@RunWith(RobolectricTestRunner::class)
class NfcPromptBuildIntentTest {

    @Before
    fun reset() {
        NfcTagCache.clear()
    }

    @Test
    fun noTagObjectIntentFallsBackToLegacyPromptWithoutCachedHint() {
        val intent = Intent(NfcAdapter.ACTION_TAG_DISCOVERED).apply {
            putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(0x04.toByte(), 0xA3.toByte()))
        }
        // 无 Tag 对象 → 无法建立缓存占位 → false。
        assertFalse(cacheNfcTagFromIntent(intent, "04:A3"))
        val prompt = buildNfcPrompt(intent)
        assertNotNull("无 Tag intent 仍应注入旧 R145 提示词", prompt)
        assertFalse("无 Tag intent 不得谎报缓存：$prompt", prompt!!.contains(CACHED_TAP_HINT))
        assertFalse(prompt.contains("碰卡数据已缓存"))
        // 「无 Tag intent → 旧文案」显式精确断言。
        assertEquals(
            "NFC 标签已贴上：tag id=04:A3，技术=[]。请用 nfc_read 读取数据并分析这张卡的类型与内容。",
            prompt,
        )
    }
}

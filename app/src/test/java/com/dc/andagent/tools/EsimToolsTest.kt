package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R122: eSIM 管理刀纯函数校验。
 * 覆盖激活码解析（LPA: 剥离与结构校验）、ICCID 掩码、EuiccManager 回调
 * result code / detailed code 映射与受限说明文案。
 */
class EsimToolsTest {

    /* --------------------------- 激活码解析 --------------------------- */

    @Test
    fun `activation code strips lpa prefix and parses fields`() {
        val code = EsimCodec.parseActivationCode("LPA:1\$smdp.example.com\$MATCH-ID-001")!!
        assertEquals("1\$smdp.example.com\$MATCH-ID-001", code.raw)
        assertEquals("smdp.example.com", code.smdpAddress)
        assertEquals("MATCH-ID-001", code.matchingId)
    }

    @Test
    fun `activation code accepts bare form and missing matching id`() {
        val bare = EsimCodec.parseActivationCode("1\$smdp.example.com\$ABC")!!
        assertEquals("1\$smdp.example.com\$ABC", bare.raw)
        assertEquals("ABC", bare.matchingId)

        val noMatch = EsimCodec.parseActivationCode("LPA:1\$smdp.example.com")!!
        assertNull(noMatch.matchingId)
    }

    @Test
    fun `activation code trims whitespace`() {
        val code = EsimCodec.parseActivationCode("  LPA:1\$smdp.example.com\$ID  ")!!
        assertEquals("1\$smdp.example.com\$ID", code.raw)
    }

    @Test
    fun `activation code rejects malformed input`() {
        assertNull(EsimCodec.parseActivationCode(""))
        assertNull(EsimCodec.parseActivationCode("LPA:"))
        assertNull(EsimCodec.parseActivationCode("LPA:1\$"))
        assertNull(EsimCodec.parseActivationCode("just some text"))
        assertNull(EsimCodec.parseActivationCode("1\$"))
        assertNull(EsimCodec.parseActivationCode("\$smdp.example.com"))
    }

    /* ----------------------------- ICCID 掩码 ----------------------------- */

    @Test
    fun `iccid is masked except last four digits`() {
        assertEquals("***************1234", EsimCodec.maskIccid("8986012345678901234"))
        assertEquals("****0000", EsimCodec.maskIccid("12340000"))
        assertNull(EsimCodec.maskIccid(null))
        assertNull(EsimCodec.maskIccid(""))
        assertNull(EsimCodec.maskIccid("   "))
    }

    /* --------------------------- 回调结果码映射 --------------------------- */

    @Test
    fun `result codes map to readable names`() {
        assertEquals("ok", EsimCodec.resultCodeName(0))
        assertEquals("resolvable_errors", EsimCodec.resultCodeName(1))
        assertEquals("non_resolvable_errors", EsimCodec.resultCodeName(2))
        assertEquals("unknown(9)", EsimCodec.resultCodeName(9))
    }

    @Test
    fun `detailed codes map to readable names`() {
        assertEquals("ok", EsimCodec.detailedCodeName(0))
        assertEquals("generic_error", EsimCodec.detailedCodeName(-1))
        assertEquals("install_error", EsimCodec.detailedCodeName(-6))
        assertEquals("disallowed_by_policy", EsimCodec.detailedCodeName(-7))
        assertEquals("cancelled_by_user", EsimCodec.detailedCodeName(-8))
        assertEquals("no_detailed_code", EsimCodec.detailedCodeName(Int.MIN_VALUE))
        assertEquals("unknown(42)", EsimCodec.detailedCodeName(42))
    }

    /* ----------------------------- 受限说明 ----------------------------- */

    @Test
    fun `restricted hints explain the two known limits`() {
        val disabled = EsimCodec.restrictedHint("euicc_disabled")
        assert(disabled.contains("国行"))
        assert(disabled.contains("isEnabled=false"))

        val denied = EsimCodec.restrictedHint("permission_denied")
        assert(denied.contains("WRITE_EMBEDDED_SUBSCRIPTIONS"))
        assert(denied.contains("运营商"))
    }
}

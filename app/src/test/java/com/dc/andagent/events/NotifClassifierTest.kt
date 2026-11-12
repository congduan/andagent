package com.dc.andagent.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R117① 通知智能分类纯 JVM 单测。 */
class NotifClassifierTest {

    private fun c(title: String, text: String, pkg: String = "com.example.app") =
        NotifClassifier.classify(title, text, pkg)

    @Test fun verificationChineseExtractsFirstCodeGroup() {
        val r = c("登录验证码", "您的验证码是 482913，5 分钟内有效")
        assertEquals("verification_code", r.category)
        assertEquals("482913", r.code)
    }

    @Test fun verificationEnglishExtractsCode() {
        val r = c("Verification code", "Your code is 90517, valid for 10 min")
        assertEquals("verification_code", r.category)
        assertEquals("90517", r.code)
    }

    @Test fun verificationKeywordCaseInsensitive() {
        val r = c("oNe-TiMe CoDe", "YOUR OTP IS 7788")
        assertEquals("verification_code", r.category)
        assertEquals("7788", r.code)
    }

    @Test fun eightDigitGroupExtractedWithoutYearHeuristic() {
        // 8 位纯数字组即使形如年份开头日期也照规则提取（关键词门槛已是第一道闸，不额外启发式）。
        val r = c("校验码", "动态码 20261113 有效，请勿转发")
        assertEquals("verification_code", r.category)
        assertEquals("20261113", r.code)
    }

    @Test fun redPacketClassifiedWithoutCode() {
        val r = c("微信红包", "你收到了一个红包")
        assertEquals("red_packet", r.category)
        assertNull(r.code)
    }

    @Test fun redPacketKeywordCaseInsensitive() {
        val r = c("Lucky Money", "You got a RED PACKET")
        assertEquals("red_packet", r.category)
        assertNull(r.code)
    }

    @Test fun expressWithPickupCodeExtractsFollowingGroup() {
        val r = c("快递通知", "包裹已到，取件码 5830，请到驿站取件")
        assertEquals("express", r.category)
        assertEquals("5830", r.code)
    }

    @Test fun expressPickupCodeEnglishNoSpace() {
        val r = c("Parcel", "pickup code:47291 at locker")
        assertEquals("express", r.category)
        assertEquals("47291", r.code)
    }

    @Test fun expressWithoutPickupKeywordHasNullCode() {
        val r = c("快递通知", "您的包裹已到达驿站，请及时领取")
        assertEquals("express", r.category)
        assertNull(r.code)
    }

    @Test fun expressEnglishKeywords() {
        val r = c("Delivery", "Your parcel has arrived")
        assertEquals("express", r.category)
        assertNull(r.code)
    }

    @Test fun verificationWinsOverRedPacketWhenBothPresent() {
        val r = c("红包验证码", "口令 482913，快来领红包")
        assertEquals("verification_code", r.category)
        assertEquals("482913", r.code)
    }

    @Test fun unrelatedNotificationIsMessage() {
        val r = c("会议提醒", "下午三点开会")
        assertEquals("message", r.category)
        assertNull(r.code)
    }

    @Test fun emptyTitleAndTextIsMessage() {
        val r = c("", "")
        assertEquals("message", r.category)
        assertNull(r.code)
    }

    @Test fun longTwelveDigitRunIsNotExtracted() {
        // 12 位长数字串不构成独立 4-8 位数字组，验证码关键词命中也不应误提。
        val r = c("验证码", "订单号 123456789012 请勿回复")
        assertEquals("message", r.category)
        assertNull(r.code)
    }

    @Test fun senderIsAlwaysNullNoFabrication() {
        assertNull(c("登录验证码", "482913").sender)
        assertNull(c("微信红包", "红包").sender)
        assertNull(c("快递", "取件码 5830").sender)
        assertNull(c("会议", "开会").sender)
    }

    @Test fun pickupCodeIgnoresNumberBeyondThirtyCharWindow() {
        // 取件码关键词后跟远处数字组（>30 字符之外）→ 超出近邻窗口，不提取。
        val filler = "请于今日营业时间内携带本人身份证件到驿站领取并当面核对包裹信息是否完好无误"
        val r = c("快递通知", "取件码 $filler 5830")
        assertEquals("express", r.category)
        assertNull(r.code)
    }

    @Test fun verificationThreeDigitAmountFallsThroughToMessage() {
        // 验证码关键词后是金额「128 元」（3 位，不构成独立 4-8 位数字组）→ 不返回验证码，落 message。
        val r = c("支付验证码", "本次消费金额 128 元，如有疑问请联系客服")
        assertEquals("message", r.category)
        assertNull(r.code)
    }

    @Test fun verificationPrefersNearbyCodeOverDistantDate() {
        // 远处日期(8 位)出现在关键词之前，是全文首组数字；近邻窗口应从关键词结束处只取近邻真码。
        val r = c("账单提醒", "账单日期 20261113 您的登录验证码 482913 请勿转发")
        assertEquals("verification_code", r.category)
        assertEquals("482913", r.code)
    }

    @Test fun windowEdgeTruncatingCodeGroupIsNotExtracted() {
        // 二轮 review 必修：数字组起点在 40 字符窗口内、终点跨出窗口 → 不构成窗口内完整数字组。
        // haystack = "验证码"(0-2) + " "(3) + 38 个汉字(4-41) + "48291320"(42-49)；keywordEnd=3，windowEnd=43：
        // 起点 42 在窗口内、终点 49 在窗口外，旧逻辑（只查起点）会误提，新逻辑要求整组在窗口内 → 拒绝。
        val text = "您".repeat(38) + "48291320"
        val r = c("验证码", text)
        assertEquals("message", r.category)
        assertNull(r.code)
    }

    @Test fun pickupWindowEdgeTruncatingNumberNotExtracted() {
        // 二轮 review 必修：取件码 30 字符窗口同样要求整组终点在窗口内。
        // haystack = "快递通知 "(0-4) + "取件码"(5-7) + " "(8) + 29 个汉字(9-37) + "13812345678"(38-48)；
        // 取件码 keywordEnd=8，windowEnd=38：号码起点 38…—— 修正：起点 37 在窗口内、终点 44 在窗口外 → 拒绝。
        // （11 位连号本身不成组，用 8 位码构造：29 汉字改为 28 汉字 + "48291320"）
        val text = "取件码 " + "请".repeat(28) + "48291320"
        // haystack = "快递通知 "(0-4) + text；"取件码"占 text 索引 0-2 → haystack 5-7，keywordEnd=8，windowEnd=38；
        // " "(8)，28 汉字占 9-36，"48291320" 占 37-44：起点 37<38 在窗口内、终点 44≥38 在窗口外 → 拒绝。
        val r = c("快递通知", text)
        assertEquals("express", r.category)
        assertNull(r.code)
    }

    @Test fun pickupCodeFullyInsideWindowStillExtracts() {
        // 对照：同样 8 位码完整落在取件码 30 字符窗口内 → 正常提取（防上面两例「拒绝过宽」回归）。
        val r = c("快递通知", "取件码 请到驿站前台服务台并出示本短信后输入 48291320 取件")
        assertEquals("express", r.category)
        assertEquals("48291320", r.code)
    }

    @Test fun verificationSecondKeywordOccurrenceNearCodeStillExtracts() {
        // 二轮 review 建议：首个关键词命中（标题，窗口外无码）落空后，正文后段再次出现关键词紧邻真码 → 依次检查命中可提取。
        // haystack = "验证码"(0-2)+" "(3)+38 字正文(4-41)+"，"(42)+"动态码"(43-45)+" "(46)+"639201"(47-52)；
        // 首个 keywordEnd=3 窗口 [3,43) 内无完整数字组 → 继续；"动态码" keywordEnd=46 窗口内提取 639201。
        val longBody = "您好，您反馈的业务工单已经处理完毕，具体处理结果与详情说明如下，请查收并知悉"
        val r = c("验证码", "$longBody，动态码 639201 请勿泄露")
        assertEquals("verification_code", r.category)
        assertEquals("639201", r.code)
    }
}

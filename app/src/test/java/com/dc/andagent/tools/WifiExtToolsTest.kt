package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * R122: Wi-Fi 三件套（DPP / Wi-Fi Direct / RTT）纯逻辑覆盖——不触 Android 框架、
 * 不需要权限与真机。
 */
class WifiExtToolsTest {

    /* ---------------------------- WifiChannels ---------------------------- */

    @Test
    fun maps24GHzChannelsToFrequencies() {
        assertEquals(2412, WifiChannels.frequencyOf(81, 1))
        assertEquals(2437, WifiChannels.frequencyOf(81, 6))
        assertEquals(2472, WifiChannels.frequencyOf(81, 13))
        assertEquals(2484, WifiChannels.frequencyOf(81, 14))
    }

    @Test
    fun maps5And6GHzChannels() {
        assertEquals(5180, WifiChannels.frequencyOf(115, 36))
        assertEquals(5500, WifiChannels.frequencyOf(118, 100))
        assertEquals(5975, WifiChannels.frequencyOf(131, 5))
    }

    @Test
    fun rejectsUnknownBandsAndChannels() {
        assertNull(WifiChannels.frequencyOf(null, 6))
        assertNull(WifiChannels.frequencyOf(999, 6))
        assertNull(WifiChannels.frequencyOf(81, null))
        assertNull(WifiChannels.frequencyOf(81, 0))
        assertNull(WifiChannels.bandName(999))
    }

    @Test
    fun roundTripsFrequencyToChannel() {
        assertEquals(6, WifiChannels.channelOf(2437))
        assertEquals(14, WifiChannels.channelOf(2484))
        assertEquals(36, WifiChannels.channelOf(5180))
        assertEquals(5, WifiChannels.channelOf(5975))
        assertEquals(2, WifiChannels.channelOf(2417)) // ch2：2412..2472 内 5MHz 间隔，roundtrip 成立
        assertNull(WifiChannels.channelOf(2410)) // 真·非法间隔（2410-2407=3，非 5 倍数且低于首信道）
        assertNull(WifiChannels.channelOf(60_000))
    }

    @Test
    fun channelTextIncludesBandAndFrequency() {
        assertTrue(WifiChannels.channelText(81, 6)!!.contains("2437MHz"))
        assertTrue(WifiChannels.channelText(81, 6)!!.contains("2.4GHz"))
        assertTrue(WifiChannels.channelText(81, 6)!!.contains("ch6"))
        assertTrue(WifiChannels.channelText(115, 36)!!.contains("5180MHz"))
        assertNull(WifiChannels.channelText(81, null))
    }

    /* ------------------------------ DppCodec ------------------------------ */

    /** 91 字节占位公钥（长度对齐 P-256 SubjectPublicKeyInfo），内容不参与校验。 */
    private fun fakeKey(bytes: Int = 91): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes))

    private fun uri(vararg attrs: String): String =
        "DPP:" + attrs.joinToString(";") + ";;"

    @Test
    fun parsesFullConfiguratorUri() {
        val parsed = DppCodec.parse(uri("C:81/6", "M:aa:bb:cc:dd:ee:ff", "K:${fakeKey()}", "V:2"))
        assertEquals(2, parsed.version)
        assertEquals(81, parsed.band)
        assertEquals(6, parsed.channel)
        assertEquals("2437MHz 2.4GHz ch6", parsed.channelText)
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.mac)
        assertEquals(91, parsed.publicKeyBytes)
        assertTrue(parsed.isConfiguratorBootstrap)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test
    fun defaultsVersionTo1WhenAttributeMissing() {
        val parsed = DppCodec.parse(uri("C:81/1", "M:AA-BB-CC-DD-EE-FF", "K:${fakeKey()}"))
        assertEquals(1, parsed.version)
        // MAC 用横线也接受，但统一输出冒号大写
        assertEquals("AA:BB:CC:DD:EE:FF", parsed.mac)
    }

    @Test
    fun keepsUnknownAttributesAndReportsDuplicates() {
        val parsed = DppCodec.parse(uri("C:81/6", "C:81/11", "M:aa:bb:cc:dd:ee:ff", "K:${fakeKey()}", "X:custom"))
        assertEquals(11, parsed.channel)
        assertTrue(parsed.warnings.any { it.contains("C 属性重复") || (it.contains("重复") && it.contains("C")) })
        assertEquals("custom", parsed.attributes["X"])
    }

    @Test
    fun warnsWhenChannelOrKeyMissing() {
        val noChannel = DppCodec.parse(uri("M:aa:bb:cc:dd:ee:ff", "K:${fakeKey()}"))
        assertTrue(noChannel.warnings.any { it.contains("C（信道）") })
        assertNull(noChannel.channel)

        val noKey = DppCodec.parse(uri("C:81/6", "M:aa:bb:cc:dd:ee:ff"))
        assertTrue(noKey.warnings.any { it.contains("K（公钥）") })
        assertFalse(noKey.isConfiguratorBootstrap)
    }

    @Test
    fun warnsOnBadKeyEncodingAndOddKeySizes() {
        val badBase64 = DppCodec.parse(uri("C:81/6", "M:aa:bb:cc:dd:ee:ff", "K:!!!not-base64!!!"))
        assertTrue(badBase64.warnings.any { it.contains("base64") })

        val oddSize = DppCodec.parse(uri("C:81/6", "M:aa:bb:cc:dd:ee:ff", "K:${fakeKey(50)}"))
        assertTrue(oddSize.warnings.any { it.contains("50 字节") })
    }

    @Test
    fun rejectsNonDppAndMalformedUris() {
        val bad = listOf(
            "",
            "WIFI:T:WPA;S:home;;",
            "DPP:",
            "DPP:;;",
            "dpp C:81/6;;", // 缺前缀冒号
            "DPP:${"A".repeat(1100)};;", // 超长
            "DPP:C:81/6; K:包含 空格;;", // 含空格
        )
        bad.forEach { text ->
            try {
                DppCodec.parse(text)
                throw AssertionError("应拒绝：$text")
            } catch (expected: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun rejectsGarbageSegmentsButKeepsParsing() {
        val parsed = DppCodec.parse(uri("C:81/6", "garbage-without-colon", "M:aa:bb:cc:dd:ee:ff", "K:${fakeKey()}"))
        assertTrue(parsed.warnings.any { it.contains("无法解析的属性段") })
        assertEquals(6, parsed.channel)
    }

    @Test
    fun isDppUriDetection() {
        assertTrue(DppCodec.isDppUri("dpp:c:81/6;;"))
        assertTrue(DppCodec.isDppUri("  DPP:C:81/6;;  "))
        assertFalse(DppCodec.isDppUri("WIFI:T:WPA;;"))
        assertFalse(DppCodec.isDppUri(null))
    }

    @Test
    fun decodesBothBase64UrlAndStandardKeys() {
        val raw = ByteArray(91) { it.toByte() }
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        val standard = Base64.getEncoder().encodeToString(raw)
        assertEquals(91, DppCodec.decodeKey(urlSafe)?.size)
        assertEquals(91, DppCodec.decodeKey(standard)?.size)
        assertNull(DppCodec.decodeKey("###"))
    }

    @Test
    fun summaryJoinsKeyFacts() {
        val parsed = DppCodec.parse(uri("C:81/6", "M:aa:bb:cc:dd:ee:ff", "K:${fakeKey()}", "H:living-room-ap"))
        val summary = parsed.summary()
        assertTrue(summary.contains("v1"))
        assertTrue(summary.contains("2437MHz"))
        assertTrue(summary.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(summary.contains("living-room-ap"))
        assertTrue(summary.contains("公钥 91B"))
    }

    /* ---------------------------- WifiP2pCodec ---------------------------- */

    @Test
    fun mapsActionListenerReasons() {
        assertEquals("ERROR", WifiP2pCodec.reasonName(0))
        assertEquals("P2P_UNSUPPORTED", WifiP2pCodec.reasonName(1))
        assertEquals("BUSY", WifiP2pCodec.reasonName(2))
        assertEquals("NO_SERVICE_REQUESTS", WifiP2pCodec.reasonName(3))
        assertEquals("UNKNOWN(9)", WifiP2pCodec.reasonName(9))
        assertTrue(WifiP2pCodec.reasonText(1).contains("不支持"))
        assertTrue(WifiP2pCodec.reasonText(2).contains("繁忙"))
    }

    @Test
    fun validatesGroupPassphrase() {
        assertNull(WifiP2pCodec.validatePassphrase(null))
        assertNull(WifiP2pCodec.validatePassphrase(""))
        assertNull(WifiP2pCodec.validatePassphrase("12345678"))
        assertNull(WifiP2pCodec.validatePassphrase("a".repeat(63)))
        assertTrue(WifiP2pCodec.validatePassphrase("1234567")!!.contains("至少 8 位"))
        assertTrue(WifiP2pCodec.validatePassphrase("a".repeat(64))!!.contains("63"))
        assertTrue(WifiP2pCodec.validatePassphrase("密码123456")!!.contains("ASCII"))
    }

    @Test
    fun validatesGroupNetworkName() {
        assertNull(WifiP2pCodec.validateNetworkName(null))
        assertNull(WifiP2pCodec.validateNetworkName("DIRECT-AndAgent-1234"))
        assertTrue(WifiP2pCodec.validateNetworkName("中".repeat(11))!!.contains("32 字节")) // 33 UTF-8 字节
        assertTrue(WifiP2pCodec.validateNetworkName(" 空格开头")!!.contains("空格"))
        assertTrue(WifiP2pCodec.validateNetworkName("空格结尾 ")!!.contains("空格"))
    }

    @Test
    fun buildsSafeDefaultNetworkName() {
        val name = WifiP2pCodec.defaultNetworkName("AndAgent", "abcd1234")
        assertTrue(name.startsWith("DIRECT-AndAgent-"))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 32)
        // 极端长的 prefix 也必须收敛到 32 字节内
        val long = WifiP2pCodec.defaultNetworkName("超长中文名不安全字符", "x".repeat(40))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 32)
    }

    @Test
    fun labelsFrequencies() {
        assertEquals("2.4GHz ch6 (2437MHz)", WifiP2pCodec.frequencyLabel(2437))
        assertEquals("5GHz ch36 (5180MHz)", WifiP2pCodec.frequencyLabel(5180))
        assertEquals("2436MHz", WifiP2pCodec.frequencyLabel(2436)) // 非法 2.4G 间隔只给数值
        assertNull(WifiP2pCodec.frequencyLabel(null))
        assertNull(WifiP2pCodec.frequencyLabel(0))
    }

    /* ------------------------------ RttCodec ------------------------------ */

    @Test
    fun mapsRangingStatuses() {
        assertEquals("success", RttCodec.statusName(0))
        assertEquals("fail", RttCodec.statusName(1))
        assertEquals("not_80211mc", RttCodec.statusName(2))
        assertEquals("unknown(7)", RttCodec.statusName(7))
        assertEquals("fail", RttCodec.failureCodeName(1))
        assertEquals("rtt_not_available", RttCodec.failureCodeName(2))
        assertEquals("unknown(9)", RttCodec.failureCodeName(9))
    }

    @Test
    fun formatsDistances() {
        assertEquals("1.00 m", RttCodec.distanceText(1000))
        assertEquals("0.42 m", RttCodec.distanceText(420))
        assertEquals("12.35 m", RttCodec.distanceText(12345))
    }

    @Test
    fun gradesReliabilityByStdDev() {
        assertEquals("high", RttCodec.reliability(0))
        assertEquals("high", RttCodec.reliability(500))
        assertEquals("medium", RttCodec.reliability(1000))
        assertEquals("medium", RttCodec.reliability(1500))
        assertEquals("low", RttCodec.reliability(1501))
        assertEquals("low", RttCodec.reliability(40_000))
    }

    @Test
    fun computesDistanceStats() {
        val stats = RttCodec.stats(listOf(1000, 1000, 3000, 3000))
        assertEquals(4, stats.count)
        assertEquals(2000, stats.meanMm)
        assertEquals(2000, stats.medianMm)
        assertEquals(1000, stats.minMm)
        assertEquals(3000, stats.maxMm)
        assertEquals(1000, stats.stdDevMm)
    }

    @Test
    fun statsHandleOddCountAndEmptyInput() {
        assertEquals(3000, RttCodec.stats(listOf(1000, 3000, 5000)).medianMm)
        val empty = RttCodec.stats(emptyList())
        assertEquals(0, empty.count)
        assertNull(empty.meanMm)
        assertNull(empty.medianMm)
        assertNull(empty.stdDevMm)
    }

    /* --------------------------- registration list ------------------------ */

    @Test
    fun registersExactlyTheThreeWifiTools() {
        assertEquals(listOf("wifi_easy_connect", "wifi_direct", "wifi_rtt"), WIFI_EXT_TOOLS)
    }
}

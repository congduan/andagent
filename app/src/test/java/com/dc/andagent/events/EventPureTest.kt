package com.dc.andagent.events

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R117②: 只测抽出的纯 JVM 逻辑；Android 依赖（ClipboardManager / NetworkCallback 等）不强行 mock。 */
class EventPureTest {

    @Test fun clipPreviewHandlesNullAndEmpty() {
        assertEquals(0 to "", EventPure.clipPreview(null))
        assertEquals(0 to "", EventPure.clipPreview(""))
    }

    @Test fun clipPreviewKeepsShortTextWhole() {
        assertEquals(3 to "abc", EventPure.clipPreview("abc"))
        assertEquals("密码123".length to "密码123", EventPure.clipPreview("密码123"))
    }

    @Test fun clipPreviewTruncatesAt120AndNeverDropsLength() {
        val text = "x".repeat(121)
        val (length, preview) = EventPure.clipPreview(text)
        assertEquals(121, length)
        assertEquals(text.take(EventPure.CLIP_PREVIEW_MAX), preview)
        assertEquals(EventPure.CLIP_PREVIEW_MAX, preview.length)
    }

    @Test fun clipPreviewAtExactBoundary() {
        val text = "a".repeat(120)
        val (length, preview) = EventPure.clipPreview(text)
        assertEquals(120, length)
        assertEquals(text, preview)
    }

    @Test fun clipPreviewLongUnicodeIsStillCappedAt120Chars() {
        val text = "密".repeat(500)
        val (length, preview) = EventPure.clipPreview(text)
        assertEquals(500, length)
        assertEquals(120, preview.length)
        assertEquals(text.take(120), preview)
        // 500 字符全文绝不出现在预览里（预览只是前 120）。
        assertFalse(preview.length >= text.length)
    }

    @Test fun clipEventBodyCarriesOnlyLengthAndPreview() {
        val (length, preview) = EventPure.clipPreview("s3cr3t".repeat(40))
        val event = EventStore.event("clip", buildJsonObject {
            put("length", length)
            put("preview", preview)
        })
        assertEquals("clip", event["event"]?.jsonPrimitive?.content)
        assertEquals(length, event["length"]?.jsonPrimitive?.content?.toInt())
        assertEquals(preview, event["preview"]?.jsonPrimitive?.content)
        assertTrue((event["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L) > 0L)
        // 没有全文/text 字段，也不含超过 120 字符的内容。
        assertFalse(event.containsKey("text"))
        assertTrue((event["preview"]?.jsonPrimitive?.content?.length ?: Int.MAX_VALUE) <= 120)
    }

    @Test fun sysTriggerConfigRejectsNoEnabledTrigger() {
        assertNull(EventPure.sysTriggerConfig(null, null))
        assertNull(EventPure.sysTriggerConfig(null, false))
        assertNull(EventPure.sysTriggerConfig("   ", null))
        assertNull(EventPure.sysTriggerConfig("   ", false))
    }

    @Test fun sysTriggerConfigEnablesWifiOnly() {
        val config = EventPure.sysTriggerConfig("HomeWiFi", null)
        assertNotNull(config)
        assertEquals(true, config!!.wifi)
        assertEquals(false, config.power)
        assertEquals("HomeWiFi", config.ssid)
    }

    @Test fun sysTriggerConfigEnablesPowerOnly() {
        val config = EventPure.sysTriggerConfig(null, true)
        assertNotNull(config)
        assertEquals(false, config!!.wifi)
        assertEquals(true, config.power)
        assertNull(config.ssid)
    }

    @Test fun sysTriggerConfigEnablesBothAndTrimsSsid() {
        val config = EventPure.sysTriggerConfig("  HomeWiFi  ", true)
        assertNotNull(config)
        assertEquals(true, config!!.wifi)
        assertEquals(true, config.power)
        assertEquals("HomeWiFi", config.ssid)
    }

    @Test fun sysTriggerConfigPowerFalseWithSsidIsStillValid() {
        val config = EventPure.sysTriggerConfig("HomeWiFi", false)
        assertNotNull(config)
        assertEquals(true, config!!.wifi)
        assertEquals(false, config.power)
    }

    @Test fun normalizeSsidStripsQuotes() {
        assertEquals("HomeWiFi", EventPure.normalizeSsid("\"HomeWiFi\""))
        assertEquals("HomeWiFi", EventPure.normalizeSsid("  \"HomeWiFi\"  "))
        assertEquals("HomeWiFi", EventPure.normalizeSsid("HomeWiFi"))
    }

    @Test fun normalizeSsidTreatsUnknownPlaceholdersAsUnknown() {
        assertNull(EventPure.normalizeSsid("\"<unknown ssid>\""))
        assertNull(EventPure.normalizeSsid("<unknown ssid>"))
        assertNull(EventPure.normalizeSsid("<unknown>"))
        assertNull(EventPure.normalizeSsid("\"<UNKNOWN SSID>\""))
    }

    @Test fun normalizeSsidTreatsBlankAsUnknown() {
        assertNull(EventPure.normalizeSsid(null))
        assertNull(EventPure.normalizeSsid(""))
        assertNull(EventPure.normalizeSsid("   "))
        assertNull(EventPure.normalizeSsid("\"\""))
    }

    // ---- Codex review 必修修复回归 ----

    @Test fun clipPreviewSensitiveContentIsFlaggedEvenWhenShort() {
        // 必修1：≤120 字符的短密文也要被敏感启发式命中（此前 preview 即全文，短密码会落普通 KV）。
        // "MyP@ss123" 无任何关键词，启发式按设计不命中（已知边界：无上下文的裸密码无法识别），
        // 改用带关键词的短密文样本验证「短 + 关键词」路径。
        assertTrue(EventPure.clipLooksSensitive("pwd: MyP@ss123"))
        assertTrue(EventPure.clipLooksSensitive("验证码 582901"))
        assertTrue(EventPure.clipLooksSensitive("bearer eyJhbGciOi"))
        assertTrue(EventPure.clipLooksSensitive("github_pat_11AAAA token copy"))
        assertTrue(EventPure.clipLooksSensitive("API_KEY=sk-abc123"))
        // 必修C（Codex 必修1 CONCERN）：补 OTP/TOTP/验证码英文词，敏感启发式覆盖一次性码。
        assertTrue(EventPure.clipLooksSensitive("OTP 482913"))
        assertTrue(EventPure.clipLooksSensitive("Your verification code is 993 214"))
        assertTrue(EventPure.clipLooksSensitive("TOTP: 881 207"))
        assertFalse(EventPure.clipLooksSensitive("今天下午三点开会，记得带笔记本"))
        assertFalse(EventPure.clipLooksSensitive("https://example.com/some/regular/page"))
    }

    @Test fun sysTriggerConfigWifiAnyExplicitlyEnablesAnyWifi() {
        // 必修2：无 SSID 时必须显式 wifi_any=true 才开 WiFi 触发器；缺省仍不开。
        assertNull(EventPure.sysTriggerConfig(null, null, null))
        assertNull(EventPure.sysTriggerConfig(null, null, false))
        val any = EventPure.sysTriggerConfig(null, null, true)
        assertNotNull(any)
        assertEquals(true, any!!.wifi)
        assertNull(any.ssid)
        val both = EventPure.sysTriggerConfig(null, true, true)
        assertNotNull(both)
        assertEquals(true, both!!.wifi)
        assertEquals(true, both.power)
        // 命名 SSID：wifi_any=false 照样开。
        val named = EventPure.sysTriggerConfig("HomeWiFi", null, false)
        assertNotNull(named)
        assertEquals("HomeWiFi", named!!.ssid)
        // 二轮必修2：wifi_any=true 与 wifi_ssid 同传语义冲突（名义任意、实际按 SSID 过滤）→ 互斥拒绝。
        assertNull(EventPure.sysTriggerConfig("HomeWiFi", null, true))
        assertNull(EventPure.sysTriggerConfig("HomeWiFi", true, true))
        // 必修A（二轮必修2 补强）：空白 SSID 归一化为 null，与 wifi_any=true 同传按任意 WiFi 处理，
        // 不再误把空串存成 sys_ssid 过滤器导致任意 WiFi 监听永不落事件。
        val blankAny = EventPure.sysTriggerConfig("", null, true)
        assertNotNull(blankAny)
        assertNull(blankAny!!.ssid)
        val blankBoth = EventPure.sysTriggerConfig("  ", true, true)
        assertNotNull(blankBoth)
        assertNull(blankBoth!!.ssid)
        assertEquals(true, blankBoth.power)
        // 空白 SSID + 无任何有效触发器 → 返回 null。
        assertNull(EventPure.sysTriggerConfig("", null, false))
    }

    @Test fun shouldEmitWifiConnectedFilterMatrix() {
        // 二轮必修3补强：过滤判定抽纯函数后直接测四象限。
        // 任意 WiFi 模式（filter=null）：unknown 也照发。
        assertTrue(EventPure.shouldEmitWifiConnected(null, "HomeWiFi"))
        assertTrue(EventPure.shouldEmitWifiConnected(null, null))
        // 指定 SSID 过滤：unknown（无定位权限）不落盘——未证实命中不落盘。
        assertFalse(EventPure.shouldEmitWifiConnected("HomeWiFi", null))
        // 指定 SSID 过滤：其他网络不落盘；目标网络才落盘。
        assertFalse(EventPure.shouldEmitWifiConnected("HomeWiFi", "OfficeNet"))
        assertTrue(EventPure.shouldEmitWifiConnected("HomeWiFi", "HomeWiFi"))
    }

    @Test fun wifiConnectedEventPayloadShape() {
        // 必修3（事件体形状）：命中时带 ssid、无 ssidUnknown；unknown 命中（任意模式）时带 ssidUnknown。
        val hit = EventStore.event("wifi_connected", buildJsonObject { put("ssid", "HomeWiFi") })
        assertEquals("HomeWiFi", hit["ssid"]?.jsonPrimitive?.content)
        assertFalse(hit.containsKey("ssidUnknown"))
        val unknown = EventStore.event("wifi_connected", buildJsonObject { put("ssidUnknown", true) })
        assertEquals("true", unknown["ssidUnknown"]?.jsonPrimitive?.content)
        assertFalse(unknown.containsKey("ssid"))
    }
}

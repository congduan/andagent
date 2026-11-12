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
}

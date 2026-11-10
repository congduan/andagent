package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R124 a/d: 厂商 ROM 归一与电池白名单文案的纯 JVM 单测（不触碰 Android 运行时）。 */
class BackgroundSurvivalTest {

    @Test fun vendorGuideNormalizesXiaomiAndRedmiToMiui() {
        val xiaomi = BackgroundSurvival.vendorGuide("Xiaomi")
        assertTrue(xiaomi.contains("MIUI") || xiaomi.contains("HyperOS"))
        assertTrue(xiaomi.contains("自启动"))
        assertEquals(xiaomi, BackgroundSurvival.vendorGuide("redmi"))
        assertEquals(xiaomi, BackgroundSurvival.vendorGuide("XIAOMI"))
        assertEquals(xiaomi, BackgroundSurvival.vendorGuide("  Redmi  "))
    }

    @Test fun vendorGuideNormalizesHuaweiAndHonorToEmui() {
        val huawei = BackgroundSurvival.vendorGuide("HUAWEI")
        assertTrue(huawei.contains("EMUI") || huawei.contains("MagicOS"))
        assertTrue(huawei.contains("启动管理"))
        assertEquals(huawei, BackgroundSurvival.vendorGuide("Honor"))
        assertEquals(huawei, BackgroundSurvival.vendorGuide("honor"))
    }

    @Test fun vendorGuideNormalizesColorOsFamily() {
        val oppo = BackgroundSurvival.vendorGuide("OPPO")
        assertTrue(oppo.contains("ColorOS"))
        assertEquals(oppo, BackgroundSurvival.vendorGuide("realme"))
        assertEquals(oppo, BackgroundSurvival.vendorGuide("OnePlus"))
    }

    @Test fun vendorGuideNormalizesVivoAndSamsung() {
        val vivo = BackgroundSurvival.vendorGuide("vivo")
        assertTrue(vivo.contains("OriginOS") || vivo.contains("Funtouch"))
        assertEquals(vivo, BackgroundSurvival.vendorGuide("iQOO"))
        val samsung = BackgroundSurvival.vendorGuide("samsung")
        assertTrue(samsung.contains("后台使用限制") || samsung.contains("Sleeping apps"))
        assertTrue(samsung.contains("Samsung"))
    }

    @Test fun unknownManufacturerFallsBackToGenericGuide() {
        for (m in listOf("Google", "unknown", "", "  ")) {
            val g = BackgroundSurvival.vendorGuide(m)
            assertTrue("generic guide for '$m'", g.contains("通用") || g.contains("Generic"))
        }
    }

    @Test fun everyGuideIsNonEmptyAndBilingual() {
        for (m in listOf("Xiaomi", "HUAWEI", "OPPO", "vivo", "samsung", "Google", "")) {
            val g = BackgroundSurvival.vendorGuide(m)
            assertTrue("empty guide for '$m'", g.isNotBlank())
            assertTrue("missing English half for '$m'", g.contains(" / "))
        }
    }

    @Test fun whitelistHintOnlyWhenNotIgnoring() {
        assertNull(BackgroundSurvival.hintFor(true)) // 已白名单 → 不提示
        val hint = BackgroundSurvival.hintFor(false)
        assertNotNull(hint)
        assertTrue(hint!!.contains("电池优化"))
        assertTrue(hint.contains("battery_whitelist"))
        assertTrue(hint.contains("Tip")) // 中英双语
        assertFalse(hint.isBlank())
    }

    @Test fun sshReconnectHintUsesSavedCredentialAndNoSecretParams() {
        val hint = BackgroundSurvival.SSH_RECONNECT_HINT
        assertTrue(hint.contains("credential=<nickname>"))
        assertTrue(hint.contains("ssh_connect"))
        assertTrue(hint.contains("无需重输"))
        assertFalse(hint.contains("password="))
        assertFalse(hint.contains("key_path"))
    }
}

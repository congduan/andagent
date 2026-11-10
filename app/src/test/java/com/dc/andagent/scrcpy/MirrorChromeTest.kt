package com.dc.andagent.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R137 投屏浮窗 UI 决策纯逻辑单测：状态枚举、尺寸护栏、工具条显隐（含 passthrough）、
 * 浮层高度上限、badge 文案与显隐。防失败条款在此锁定，视觉层只消费这些结论。
 */
class MirrorChromeTest {

    @Test fun statusExposesThreeConnectionStates() {
        assertEquals(
            listOf("CONNECTING", "CONNECTED", "DISCONNECTED"),
            MirrorChrome.Status.entries.map { it.name },
        )
    }

    @Test fun dimensionTokensMatchDesignSpec() {
        assertEquals(32, MirrorChrome.MIRROR_HEADER_DP)
        assertEquals(40, MirrorChrome.HEADER_BUTTON_DP)
        assertEquals(32, MirrorChrome.TOOL_BUTTON_DP)
        assertEquals(12, MirrorChrome.TOOLBAR_RADIUS_DP)
        assertEquals(8, MirrorChrome.BADGE_RADIUS_DP)
        assertEquals(2000L, MirrorChrome.BADGE_FADE_DELAY_MS)
    }

    /** 防失败条款：工具条浮层在 passthrough（拖动模式）下自动隐藏，避免误触。 */
    @Test fun toolbarVisibleOnlyWhenExpandedAndNotPassthrough() {
        assertFalse("折叠且非拖动：隐藏", MirrorChrome.toolbarVisible(expanded = false, passthrough = false))
        assertTrue("展开且非拖动：显示", MirrorChrome.toolbarVisible(expanded = true, passthrough = false))
        assertFalse("展开但拖动模式：强制隐藏", MirrorChrome.toolbarVisible(expanded = true, passthrough = true))
        assertFalse("折叠且拖动模式：隐藏", MirrorChrome.toolbarVisible(expanded = false, passthrough = true))
    }

    /** 防失败条款：工具条覆盖视频底部不可遮挡关键区域超过 44dp。 */
    @Test fun toolbarOverlayHeightIsCappedAt44Dp() {
        assertEquals(44, MirrorChrome.MAX_TOOLBAR_OVERLAY_DP)
        assertEquals(44, MirrorChrome.clampedToolbarHeightDp(MirrorChrome.MAX_TOOLBAR_OVERLAY_DP))
        // 任意超额请求都被钳到 44dp，负值钳到 0。
        assertEquals(44, MirrorChrome.clampedToolbarHeightDp(999))
        assertEquals(44, MirrorChrome.clampedToolbarHeightDp(45))
        assertEquals(32, MirrorChrome.clampedToolbarHeightDp(32))
        assertEquals(0, MirrorChrome.clampedToolbarHeightDp(0))
        assertEquals(0, MirrorChrome.clampedToolbarHeightDp(-10))
    }

    @Test fun badgeTextPrioritisesViewOnlyThenPassthrough() {
        assertEquals("仅观看", MirrorChrome.badgeText(controlAvailable = false, passthrough = false))
        assertEquals("仅观看", MirrorChrome.badgeText(controlAvailable = false, passthrough = true))
        assertEquals("拖动模式", MirrorChrome.badgeText(controlAvailable = true, passthrough = true))
        assertEquals("可操控", MirrorChrome.badgeText(controlAvailable = true, passthrough = false))
    }

    /** 正常可操控态 badge 不再常驻；仅观看 / 拖动模式才浮出。 */
    @Test fun badgeVisibleOnlyForNonNormalStates() {
        assertFalse("可操控且非拖动：不常驻", MirrorChrome.badgeVisible(controlAvailable = true, passthrough = false))
        assertTrue("仅观看：浮出", MirrorChrome.badgeVisible(controlAvailable = false, passthrough = false))
        assertTrue("仅观看+拖动：浮出", MirrorChrome.badgeVisible(controlAvailable = false, passthrough = true))
        assertTrue("拖动模式：浮出", MirrorChrome.badgeVisible(controlAvailable = true, passthrough = true))
    }
}

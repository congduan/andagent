package com.dc.andagent.scrcpy

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R137 投屏浮窗视觉层结构冒烟测试：在 Robolectric 下真实构造 Window（不调用 show/addView），
 * 锁定「工具条是覆盖在视频底部的浮层而非独立行」「标题栏 32dp」「分隔线 1dp」
 * 「浮层高度 ≤ 44dp」等结构性护栏。拖动/passthrough/SurfaceView 逻辑不在本测覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FloatingMirrorWindowTest {

    private fun Any.privateField(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)

    private fun newWindow() = FloatingMirrorWindow(
        RuntimeEnvironment.getApplication(),
        onSurfaceReady = {}, onSurfaceGone = {}, onReconnect = {}, onClose = {},
        onControl = {}, onQualitySwitch = {},
    )

    private fun density() = RuntimeEnvironment.getApplication().resources.displayMetrics.density
    private fun dp(v: Int) = (v * density()).toInt()

    @Test fun constructsWithoutThrowing() {
        assertNotNull(newWindow())
    }

    @Test fun rootIsHeaderDividerVideoStack() {
        val root = newWindow().privateField("root") as LinearLayout
        assertEquals(3, root.childCount)
        // 标题栏 32dp、分隔线 1dp、其余是视频 FrameLayout。
        assertEquals(dp(32), (root.getChildAt(0).layoutParams as LinearLayout.LayoutParams).height)
        assertEquals(dp(1).coerceAtLeast(1), (root.getChildAt(1).layoutParams as LinearLayout.LayoutParams).height)
        assertTrue(root.getChildAt(2) is FrameLayout)
    }

    @Test fun toolbarIsAnOverlayInsideVideoNotASeparateRow() {
        val w = newWindow()
        val video = (w.privateField("root") as LinearLayout).getChildAt(2) as FrameLayout
        val overlay = w.privateField("toolbarOverlay") as View
        // 工具条浮层必须是 video 的直接子 view，且 gravity 含 BOTTOM。
        var child = false
        for (i in 0 until video.childCount) if (video.getChildAt(i) === overlay) child = true
        assertTrue("工具条应作为视频内浮层", child)
        val lp = overlay.layoutParams as FrameLayout.LayoutParams
        assertTrue("浮层贴视频底部", lp.gravity and Gravity.BOTTOM != 0)
    }

    @Test fun toolbarOverlayHeightIsCappedAt44Dp() {
        val px = newWindow().privateField("toolbarOverlayPx") as Int
        assertTrue("浮层高度不超过 44dp", px <= dp(44) + 1)
    }
}

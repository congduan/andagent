package com.dc.andagent.scrcpy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MirrorToolbarTest {
    private fun intAt(msg: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(msg, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    @Test fun systemKeyEmitsDownUpPairWith14ByteType0Messages() {
        for (kc in listOf(MirrorToolbar.KEY_BACK, MirrorToolbar.KEY_HOME, MirrorToolbar.KEY_APP_SWITCH)) {
            val msgs = MirrorToolbar.systemKey(kc)
            assertEquals(2, msgs.size)
            val down = msgs[0]
            val up = msgs[1]
            // 每条 14B：type(1) + action(1) + keycode(4) + repeat(4) + metaState(4)
            assertEquals(14, down.size)
            assertEquals(14, up.size)
            // type 字节 = 0
            assertEquals(0, down[0].toInt())
            assertEquals(0, up[0].toInt())
            // action 字节：DOWN 在前、UP 在后，必须成对
            assertEquals(ScrcpyControl.DOWN, down[1].toInt())
            assertEquals(ScrcpyControl.UP, up[1].toInt())
            // keycode 以大端写入 bytes 2..5
            assertEquals(kc, intAt(down, 2))
            assertEquals(kc, intAt(up, 2))
        }
    }

    @Test fun systemKeyUsesAndroidKeyEventConstants() {
        assertEquals(4, MirrorToolbar.KEY_BACK)
        assertEquals(3, MirrorToolbar.KEY_HOME)
        assertEquals(187, MirrorToolbar.KEY_APP_SWITCH)
    }

    @Test fun systemKeyDefaultsRepeatAndMetaStateToZero() {
        val down = MirrorToolbar.systemKey(MirrorToolbar.KEY_BACK)[0]
        assertEquals(0, intAt(down, 6))  // repeat
        assertEquals(0, intAt(down, 10)) // metaState
    }

    @Test fun qualityTogglesBetweenSdAndHd() {
        assertEquals(800, MirrorToolbar.QUALITY_SD)
        assertEquals(1200, MirrorToolbar.QUALITY_HD)
        assertEquals(MirrorToolbar.QUALITY_HD, MirrorToolbar.nextQuality(MirrorToolbar.QUALITY_SD))
        assertEquals(MirrorToolbar.QUALITY_SD, MirrorToolbar.nextQuality(MirrorToolbar.QUALITY_HD))
    }

    @Test fun qualityLabelReflectsTier() {
        assertEquals("标清 800", MirrorToolbar.qualityLabel(MirrorToolbar.QUALITY_SD))
        assertEquals("高清 1200", MirrorToolbar.qualityLabel(MirrorToolbar.QUALITY_HD))
    }

    /** 工具条显隐状态机：折叠↔展开，箭头与浮窗额外高度同步切换。 */
    @Test fun toolbarVisibilityTogglesBetweenCollapsedAndExpanded() {
        val toolbarPx = 64
        var expanded = false
        // 初始折叠：工具条隐藏（无额外高度）、标题栏显示向下箭头。
        assertFalse(expanded)
        assertEquals(0, MirrorToolbar.extraHeightPx(expanded, toolbarPx))
        assertEquals("⌄", MirrorToolbar.toggleLabel(expanded))

        // 点击展开：工具条显示（占一行高度）、箭头翻向上。
        expanded = MirrorToolbar.toggled(expanded)
        assertTrue(expanded)
        assertEquals(toolbarPx, MirrorToolbar.extraHeightPx(expanded, toolbarPx))
        assertEquals("⌃", MirrorToolbar.toggleLabel(expanded))

        // 再点收回折叠：恢复隐藏、0 额外高度、向下箭头（幂等往返）。
        expanded = MirrorToolbar.toggled(expanded)
        assertFalse(expanded)
        assertEquals(0, MirrorToolbar.extraHeightPx(expanded, toolbarPx))
        assertEquals("⌄", MirrorToolbar.toggleLabel(expanded))
    }

    /** 切换对任意传给浮窗的 toolbarPx 都成立，且取反两次回到原态。 */
    @Test fun toolbarToggleIsInvolutiveForAnyToolbarHeight() {
        for (px in listOf(0, 1, 32, 64, 120)) {
            assertTrue(MirrorToolbar.toggled(false))
            assertFalse(MirrorToolbar.toggled(true))
            assertEquals(0, MirrorToolbar.extraHeightPx(false, px))
            assertEquals(px, MirrorToolbar.extraHeightPx(true, px))
        }
    }
}

package com.dc.andagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingBoundsTest {
    @Test fun moveKeepsHandleOnScreen() {
        val start = FloatingBounds(10, 20, 300, 200)
        assertEquals(FloatingBounds(-244, 0, 300, 200), start.moved(-1000, -1000, 400, 800, 56))
        assertEquals(FloatingBounds(344, 744, 300, 200), start.moved(1000, 1000, 400, 800, 56))
    }
    @Test fun resizeClampsBothDimensions() {
        val start = FloatingBounds(10, 20, 300, 200)
        assertEquals(FloatingBounds(10, 20, 160, 160),
            start.resized(-1000, -1000, 160, 160, 1000, 1000, 400, 800, 56))
        assertEquals(FloatingBounds(10, 20, 1000, 1000),
            start.resized(1000, 1000, 160, 160, 1000, 1000, 400, 800, 56))
    }

    /** F2：先移动到左边界（x=-244、宽300、右缘=56），再缩窄到 160。
     *  旧实现只改宽不重夹 x，右缘会变成 -84 整个浮窗离屏（拖不回）；
     *  修正后 x 重夹到 -(160-56)=-104，右缘保持 56（贴边角不跳变），至少 grab 在屏内。 */
    @Test fun moveToLeftEdgeThenShrinkStaysOnScreen() {
        val grab = 56
        val screenW = 400
        val screenH = 800
        val atLeft = FloatingBounds(10, 20, 300, 200).moved(-1000, 0, screenW, screenH, grab)
        assertEquals(FloatingBounds(-244, 20, 300, 200), atLeft)

        val shrunk = atLeft.resized(-140, 0, 160, 160, 1000, 1000, screenW, screenH, grab)
        assertEquals(160, shrunk.width)
        assertEquals(-(160 - grab), shrunk.x)
        assertTrue("缩窄后仍须至少 grab 宽度在屏内", shrunk.x + shrunk.width >= grab)
        assertTrue("缩窄后左边界不得越界", shrunk.x >= -(shrunk.width - grab))
        assertEquals("右缘（拖动角）保持 56 不跳变", grab, shrunk.x + shrunk.width)
    }

    /** 右侧边界缩窄：左缘（贴边角）不动，仍留 grab 宽度在屏内。 */
    @Test fun moveToRightEdgeThenShrinkStaysOnScreen() {
        val grab = 56
        val screenW = 400
        val screenH = 800
        val atRight = FloatingBounds(10, 20, 300, 200).moved(1000, 0, screenW, screenH, grab)
        assertEquals(screenW - grab, atRight.x)

        val shrunk = atRight.resized(-140, 0, 160, 160, 1000, 1000, screenW, screenH, grab)
        assertEquals(screenW - grab, shrunk.x)
        assertTrue("缩窄后左缘仍有 grab 可见", screenW - shrunk.x >= grab)
    }
}

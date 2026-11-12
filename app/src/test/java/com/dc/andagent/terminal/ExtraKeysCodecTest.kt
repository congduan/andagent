package com.dc.andagent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R83②：附加键行纯逻辑单测（payload / CTRL 粘滞三态往返 / 字号钳制越界）。
 * 不触碰 Android / pty / JNI。
 */
class ExtraKeysCodecTest {

    @Test
    fun `arrow keys map to CSI sequences`() {
        assertEquals("\u001B[A", ExtraKeysCodec.payload(ExtraKeyAction.UP))
        assertEquals("\u001B[B", ExtraKeysCodec.payload(ExtraKeyAction.DOWN))
        assertEquals("\u001B[C", ExtraKeysCodec.payload(ExtraKeyAction.RIGHT))
        assertEquals("\u001B[D", ExtraKeysCodec.payload(ExtraKeyAction.LEFT))
    }

    @Test
    fun `arrow keys send SS3 sequences in application cursor mode`() {
        // 用 "\u001B" + "O" 显式拼出 SS3 前缀，避免 \u001BOA 字面量的转义歧义。
        val escO = "\u001B" + "O"
        assertEquals(escO + "A", ExtraKeysCodec.payload(ExtraKeyAction.UP, true))
        assertEquals(escO + "B", ExtraKeysCodec.payload(ExtraKeyAction.DOWN, true))
        assertEquals(escO + "C", ExtraKeysCodec.payload(ExtraKeyAction.RIGHT, true))
        assertEquals(escO + "D", ExtraKeysCodec.payload(ExtraKeyAction.LEFT, true))
        // false 仍发 CSI（现状），显式覆盖另一分支。
        assertEquals("\u001B[A", ExtraKeysCodec.payload(ExtraKeyAction.UP, false))
        assertEquals("\u001B[B", ExtraKeysCodec.payload(ExtraKeyAction.DOWN, false))
        assertEquals("\u001B[C", ExtraKeysCodec.payload(ExtraKeyAction.RIGHT, false))
        assertEquals("\u001B[D", ExtraKeysCodec.payload(ExtraKeyAction.LEFT, false))
    }

    @Test
    fun `ESC TAB and CTRL are unaffected by application cursor mode`() {
        assertEquals("\u001B", ExtraKeysCodec.payload(ExtraKeyAction.ESC, true))
        assertEquals("\u0009", ExtraKeysCodec.payload(ExtraKeyAction.TAB, true))
        assertNull(ExtraKeysCodec.payload(ExtraKeyAction.CTRL, true))
        assertEquals("\u001B", ExtraKeysCodec.payload(ExtraKeyAction.ESC, false))
        assertEquals("\u0009", ExtraKeysCodec.payload(ExtraKeyAction.TAB, false))
        assertNull(ExtraKeysCodec.payload(ExtraKeyAction.CTRL, false))
    }

    @Test
    fun `ESC and TAB are single control bytes 27 and 9`() {
        assertEquals("\u001B", ExtraKeysCodec.payload(ExtraKeyAction.ESC))
        assertEquals("\u0009", ExtraKeysCodec.payload(ExtraKeyAction.TAB))
        assertEquals(27, ExtraKeysCodec.payload(ExtraKeyAction.ESC)!![0].code)
        assertEquals(9, ExtraKeysCodec.payload(ExtraKeyAction.TAB)!![0].code)
    }

    @Test
    fun `CTRL has no payload because it toggles the latch`() {
        assertNull(ExtraKeysCodec.payload(ExtraKeyAction.CTRL))
    }

    @Test
    fun `control latch roundtrip off to on to consumed back to off`() {
        val codec = ExtraKeysCodec()
        assertFalse(codec.isControlLatched())
        assertFalse(codec.readControlKey())            // 态1 OFF：读为 false
        assertTrue(codec.onControlKey())               // 态2 ON（粘滞开）
        assertTrue(codec.isControlLatched())
        assertTrue(codec.readControlKey())             // 被 readControlKey 消费：返回 true
        assertFalse(codec.isControlLatched())
        assertFalse(codec.readControlKey())            // 态3 回到 OFF：读为 false
    }

    @Test
    fun `pressing control again turns the latch off`() {
        val codec = ExtraKeysCodec()
        assertTrue(codec.onControlKey())               // ON
        assertFalse(codec.onControlKey())              // 再按关 → OFF
        assertFalse(codec.readControlKey())
    }

    @Test
    fun `latch is one-shot and survives re-arm`() {
        val codec = ExtraKeysCodec()
        codec.onControlKey()
        assertTrue(codec.readControlKey())
        assertFalse(codec.readControlKey())            // 一次性，第二次读为 false
        codec.onControlKey()
        assertTrue(codec.readControlKey())             // 重新置位后可再消费
    }

    @Test
    fun `clampFontSize keeps every in-range value`() {
        for (dp in ExtraKeysCodec.MIN_FONT_DP..ExtraKeysCodec.MAX_FONT_DP) {
            assertEquals(dp, ExtraKeysCodec.clampFontSize(dp))
        }
    }

    @Test
    fun `clampFontSize clamps below-min values`() {
        assertEquals(5, ExtraKeysCodec.clampFontSize(Int.MIN_VALUE))
        assertEquals(5, ExtraKeysCodec.clampFontSize(-100))
        assertEquals(5, ExtraKeysCodec.clampFontSize(0))
        assertEquals(5, ExtraKeysCodec.clampFontSize(4))
    }

    @Test
    fun `clampFontSize clamps above-max values`() {
        assertEquals(30, ExtraKeysCodec.clampFontSize(31))
        assertEquals(30, ExtraKeysCodec.clampFontSize(1000))
        assertEquals(30, ExtraKeysCodec.clampFontSize(Int.MAX_VALUE))
    }

    @Test
    fun `font size bounds and default match the brief`() {
        assertEquals(5, ExtraKeysCodec.MIN_FONT_DP)
        assertEquals(30, ExtraKeysCodec.MAX_FONT_DP)
        assertEquals(13, ExtraKeysCodec.DEFAULT_FONT_DP)
    }
}

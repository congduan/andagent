package com.dc.andagent.scrcpy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrcpyControlTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val point = ScrcpyControl.Point(0x1234, 0x5678)

    @Test fun touchMessagesMatchV27Serializer() {
        // v2.7 app/src/control_msg.c: type, action, pointerId, position(12), pressure(2), buttons(8).
        val common = "ff ff ff ff ff ff ff fe 00 00 12 34 00 00 56 78 04 38 07 80"
        assertArrayEquals(hex("02 00 $common ff ff 00 00 00 00 00 00 00 00"), ScrcpyControl.touch(ScrcpyControl.DOWN, point, 1080, 1920))
        assertArrayEquals(hex("02 02 $common ff ff 00 00 00 00 00 00 00 00"), ScrcpyControl.touch(ScrcpyControl.MOVE, point, 1080, 1920))
        assertArrayEquals(hex("02 01 $common 00 00 00 00 00 00 00 00 00 00"), ScrcpyControl.touch(ScrcpyControl.UP, point, 1080, 1920))
    }

    @Test fun scrollAndKeycodeMatchV27Serializer() {
        // Scroll values are signed i16 fixed point, not i32.
        assertArrayEquals(hex("03 00 00 12 34 00 00 56 78 04 38 07 80 40 00 c0 00 00 00 00 00"),
            ScrcpyControl.scroll(point, 1080, 1920, 0.5f, -0.5f))
        // Keycode places action before keycode, then repeat and metaState.
        assertArrayEquals(hex("00 01 00 00 00 04 00 00 00 02 00 00 00 08"),
            ScrcpyControl.keycode(1, 4, 2, 8))
    }

    @Test fun mapPointAccountsForLetterboxingAndBounds() {
        assertEquals(ScrcpyControl.Point(540, 960), ScrcpyControl.mapPoint(120f, 67.5f, 240, 135, 1080, 1920))
        assertEquals(ScrcpyControl.Point(0, 0), ScrcpyControl.mapPoint(82.03125f, 0f, 240, 135, 1080, 1920))
        assertNull(ScrcpyControl.mapPoint(10f, 50f, 240, 135, 1080, 1920))
        assertNull(ScrcpyControl.mapPoint(10f, 50f, 0, 135, 1080, 1920))
    }
}

package com.dc.andagent.scrcpy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/** Wire format from scrcpy v2.7 app/src/control_msg.c and server ControlMessageReader.java. */
object ScrcpyControl {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val CANCEL = 3
    const val FINGER_ID = -2L // SC_POINTER_ID_GENERIC_FINGER; -1 is mouse

    data class Point(val x: Int, val y: Int)

    /** MediaCodec fits the decoded image inside SurfaceView, preserving aspect ratio. */
    fun mapPoint(x: Float, y: Float, viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): Point? {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
        val scale = min(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val drawnWidth = videoWidth * scale
        val drawnHeight = videoHeight * scale
        val left = (viewWidth - drawnWidth) / 2f
        val top = (viewHeight - drawnHeight) / 2f
        if (x < left || x > left + drawnWidth || y < top || y > top + drawnHeight) return null
        return Point(
            (((x - left) / drawnWidth) * videoWidth).toInt().coerceIn(0, videoWidth - 1),
            (((y - top) / drawnHeight) * videoHeight).toInt().coerceIn(0, videoHeight - 1),
        )
    }

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)

    fun touch(action: Int, point: Point, width: Int, height: Int): ByteArray = buffer(32).apply {
        put(2); put(action.toByte()); putLong(FINGER_ID)
        putInt(point.x); putInt(point.y); putShort(width.toShort()); putShort(height.toShort())
        putShort(if (action == UP || action == CANCEL) 0.toShort() else 0xffff.toShort())
        putInt(0); putInt(0)
    }.array()

    /** Signed i16 fixed point in [-1, 1], matching scrcpy's sc_float_to_i16fp(). */
    private fun scrollFixed(value: Float): Short =
        if (value >= 1f) 0x7fff.toShort() else (value.coerceIn(-1f, 1f) * 32768f).toInt().toShort()

    fun scroll(point: Point, width: Int, height: Int, horizontal: Float, vertical: Float): ByteArray = buffer(21).apply {
        put(3); putInt(point.x); putInt(point.y); putShort(width.toShort()); putShort(height.toShort())
        putShort(scrollFixed(horizontal)); putShort(scrollFixed(vertical)); putInt(0)
    }.array()

    fun keycode(action: Int, keycode: Int, repeat: Int = 0, metaState: Int = 0): ByteArray = buffer(14).apply {
        put(0); put(action.toByte()); putInt(keycode); putInt(repeat); putInt(metaState)
    }.array()
}

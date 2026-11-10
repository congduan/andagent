package com.dc.andagent.tools

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max

/** Affine map from TextureView's default stretched buffer coordinates to visible pixels. */
internal data class PreviewTransform(val rotation: Int, val mirror: Boolean, val values: FloatArray) {
    fun map(x: Float, y: Float): Pair<Float, Float> =
        (values[0] * x + values[1] * y + values[2]) to
            (values[3] * x + values[4] * y + values[5])
}

internal fun previewTransform(viewW: Int, viewH: Int, bufferW: Int, bufferH: Int,
                              sensor: Int, display: Int, front: Boolean): PreviewTransform {
    require(viewW > 0 && viewH > 0 && bufferW > 0 && bufferH > 0)
    val rotation = cameraPreviewRotationDegrees(display, sensor, front)
    val rotated = rotation == 90 || rotation == 270
    val scale = max(viewW.toFloat() / if (rotated) bufferH else bufferW,
        viewH.toFloat() / if (rotated) bufferW else bufferH)
    val radians = rotation * PI / 180.0
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val mirror = if (front) -1f else 1f
    // TextureView first stretches the actual Camera2 output buffer to viewW x viewH.
    // Undo that stretch, rotate by sensor/display orientation, crop uniformly, then mirror front in display space.
    val a = mirror * scale * c * bufferW / viewW
    val b = mirror * scale * -s * bufferH / viewH
    val d = scale * s * bufferW / viewW
    val e = scale * c * bufferH / viewH
    return PreviewTransform(rotation, front, floatArrayOf(a, b,
        viewW / 2f - a * viewW / 2f - b * viewH / 2f,
        d, e, viewH / 2f - d * viewW / 2f - e * viewH / 2f, 0f, 0f, 1f))
}

/** R139b：旋转角唯一权威公式=Camera2Basic 的 cameraPreviewRotationDegrees。现代设备 HAL 对
 *  SurfaceTexture 直接输出已烤入 sensor 补偿的 buffer（尺寸表含竖向条目，真机实锤），此时以
 *  sensor=0 代入同一公式（back 得 −display、front 得 +display：竖屏 0 角即正立、180° 倒屏
 *  仍被补偿、前摄方向符号与 Camera2Basic 一致）；传统全横表设备 halPreRotates=false 行为与
 *  R139 之前真机验证过的版本逐位相同。检测信号是尺寸表指纹，与视图宽高完全解耦。 */
internal fun previewRotationForBuffer(displayDegrees: Int, sensorOrientation: Int, front: Boolean, halPreRotates: Boolean): Int =
    cameraPreviewRotationDegrees(displayDegrees, if (halPreRotates) 0 else sensorOrientation, front)

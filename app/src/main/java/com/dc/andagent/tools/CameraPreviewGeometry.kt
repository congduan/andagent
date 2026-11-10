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

/** R139:SurfaceTexture buffer 自带方向(现代设备列竖向条目),旋转角由 buffer 方向 vs 视图方向决定,幅度方向取 sensor。 */
internal fun previewRotationForBuffer(bufferW: Int, bufferH: Int, viewW: Int, viewH: Int, sensorOrientation: Int, front: Boolean): Int {
    if (bufferW <= 0 || bufferH <= 0 || viewW <= 0 || viewH <= 0) return 0
    val needsTranspose = (bufferW > bufferH) != (viewW > viewH)
    if (!needsTranspose) return 0
    return ((sensorOrientation % 360) + 360) % 360
}

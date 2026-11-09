package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test fun sensorDisplayAndLensDetermineEveryQuarterTurn() {
        for (sensor in listOf(0, 90, 180, 270)) for (display in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val transform = previewTransform(400, 300, 1280, 720, sensor, display, front)
            val expected = if (front) (sensor + display) % 360 else (sensor - display + 360) % 360
            assertEquals("sensor=$sensor display=$display front=$front", expected, transform.rotation)
            assertEquals(front, transform.mirror)
            val center = transform.map(200f, 150f)
            assertEquals(200f, center.first, 0.001f)
            assertEquals(150f, center.second, 0.001f)
            val v = transform.values
            val determinant = v[0] * v[4] - v[1] * v[3]
            assertTrue(if (front) determinant < 0 else determinant > 0)
        }
    }

    @Test fun actualBufferAspectIsCroppedWithoutStretching() {
        val portrait = previewTransform(300, 400, 1280, 720, 90, 0, false)
        val horizontal = portrait.map(200f, 200f)
        val center = portrait.map(150f, 200f)
        assertEquals(center.first, horizontal.first, 0.001f)
        assertTrue(horizontal.second > center.second)
        val front = previewTransform(300, 400, 1280, 720, 90, 0, true)
        val f = front.map(200f, 200f)
        assertEquals(300f - horizontal.first, f.first, 0.001f)
    }

    /** 0/90/270 旋转在矩阵上有可区分的形状：0 为对角阵，90/270 为转置（反对角）阵。 */
    @Test fun zeroNinetyAndTwoSeventyRotationsHaveDistinctMatrixShapes() {
        // cameraPreviewRotationDegrees(display, sensor, false) = (sensor - display + 360) % 360
        val t0 = previewTransform(400, 300, 1280, 720, 90, 90, false)
        val t90 = previewTransform(400, 300, 1280, 720, 90, 0, false)
        val t270 = previewTransform(400, 300, 1280, 720, 0, 90, false)
        assertEquals(0, t0.rotation)
        assertEquals(90, t90.rotation)
        assertEquals(270, t270.rotation)
        // values = [a, b, tx, d, e, ty, 0, 0, 1]；0° 时无剪切项 b/d。
        assertEquals(0f, t0.values[1], 0.001f)
        assertEquals(0f, t0.values[3], 0.001f)
        // 90/270° 时对角项 a/e 归零，剪切项 b/d 撑起旋转（转置）。
        for (t in listOf(t90, t270)) {
            assertEquals(0f, t.values[0], 0.001f)
            assertEquals(0f, t.values[4], 0.001f)
            assertTrue(kotlin.math.abs(t.values[1]) > 0.01f)
            assertTrue(kotlin.math.abs(t.values[3]) > 0.01f)
        }
        // 90 与 270 的旋转方向相反（b/d 符号整体取反）。
        assertEquals(-t90.values[1], t270.values[1], 0.001f)
        assertEquals(-t90.values[3], t270.values[3], 0.001f)
    }

    /** 前摄镜像：同一 0° 旋转下，x 关于视图水平中线翻转，y 不变，行列式为负。 */
    @Test fun frontCameraMirrorsHorizontally() {
        val back = previewTransform(400, 300, 1280, 720, 0, 0, false)
        val front = previewTransform(400, 300, 1280, 720, 0, 0, true)
        assertEquals(0, back.rotation)
        assertEquals(0, front.rotation)
        assertFalse(back.mirror)
        assertTrue(front.mirror)
        val b = back.map(100f, 50f)
        val f = front.map(100f, 50f)
        assertEquals(400f - b.first, f.first, 0.001f)
        assertEquals(b.second, f.second, 0.001f)
        val detBack = back.values[0] * back.values[4] - back.values[1] * back.values[3]
        val detFront = front.values[0] * front.values[4] - front.values[1] * front.values[3]
        assertTrue(detBack > 0)
        assertTrue(detFront < 0)
    }

    /** 越界夹取：非正的视图/缓冲尺寸被 require 拦截，不产生除零或 NaN 变换。 */
    @Test fun rejectsOutOfBoundsDimensions() {
        assertThrows(IllegalArgumentException::class.java) { previewTransform(0, 300, 1280, 720, 90, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { previewTransform(400, -1, 1280, 720, 90, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { previewTransform(400, 300, 0, 720, 90, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { previewTransform(400, 300, 1280, 0, 90, 0, false) }
    }

    /** 越界夹取：超出常规 0..270 档位的 display/sensor 角度经 % 360 收敛回合法 1/4 圈档位，
     *  矩阵保持有限且中心点仍映射到中心（不因越界角度产生偏移/NaN）。 */
    @Test fun outOfRangeDegreesClampToQuarterTurnsAndKeepCenterFixed() {
        // 450° 越界：前摄 (90+450)%360=180，后摄 (90-450+360)%360=0，均夹取到合法档位。
        assertEquals(180, previewTransform(400, 300, 1280, 720, 90, 450, true).rotation)
        assertEquals(0, previewTransform(400, 300, 1280, 720, 90, 450, false).rotation)
        for (sensor in listOf(0, 90, 360, 450)) for (display in listOf(0, 90, 360)) for (front in listOf(false, true)) {
            val t = previewTransform(400, 300, 1280, 720, sensor, display, front)
            assertTrue("sensor=$sensor display=$display front=$front rotation=${t.rotation}",
                t.rotation in setOf(0, 90, 180, 270))
            t.values.forEach { assertFalse("matrix 含 NaN/Inf", it.isNaN() || it.isInfinite()) }
            val center = t.map(200f, 150f)
            assertEquals(200f, center.first, 0.001f)
            assertEquals(150f, center.second, 0.001f)
        }
    }
}

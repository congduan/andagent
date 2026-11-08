package com.dc.andagent.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * R110: 相机手动/深度能力的纯函数层（无 Android 硬件依赖，可在 JVM 单测直接跑）。
 *
 * 覆盖四类换算：
 *  - 手动曝光/ISO/对焦：范围裁剪、EV→补偿档、屈光度↔厘米；
 *  - 变焦：zoom ratio 裁剪与中心裁切区域（Bayer 对齐取偶）；
 *  - 物理镜头角色：由焦距与最近对焦距离推断 超广角/广角/长焦/微距；
 *  - 深度与连续帧：DEPTH16 统计/伪彩/点云、YUV 亮度网格与帧间差分、RAW10/12 解包、Bayer 预览。
 */
internal object CameraProCodec {

    /** 最近对焦距离 ≤ 12.5cm 视为微距（屈光度 = 100 / 距离cm）。 */
    const val MACRO_MIN_DIOPTER = 8f

    /** 最短焦距与次短焦距之比达到该值才判为独立超广角镜头。 */
    const val ULTRA_WIDE_RATIO = 1.25f

    /** 最长焦距与次长焦距之比达到该值才判为独立长焦镜头。 */
    const val TELE_RATIO = 1.35f

    /* ---------------------------- 手动曝光 / 对焦 ---------------------------- */

    fun clampIso(requested: Int, min: Int, max: Int): Int {
        val lo = min.coerceAtMost(max)
        val hi = max.coerceAtLeast(min)
        return requested.coerceIn(lo, hi)
    }

    fun clampExposureNs(requested: Long, minNs: Long, maxNs: Long): Long {
        val lo = minNs.coerceAtMost(maxNs)
        val hi = maxNs.coerceAtLeast(minNs)
        return requested.coerceIn(lo, hi)
    }

    /** EV 转 AE 补偿档位：step 为 CONTROL_AE_COMPENSATION_STEP（典型 1/3 或 1/6）。 */
    fun evSteps(ev: Float, step: Float, minSteps: Int, maxSteps: Int): Int {
        val s = if (step > 0f && step.isFinite()) step else 1f / 3f
        val steps = (ev / s).roundToInt()
        val lo = minSteps.coerceAtMost(maxSteps)
        val hi = maxSteps.coerceAtLeast(minSteps)
        return steps.coerceIn(lo, hi)
    }

    /** 对焦距离（厘米）→ 屈光度；<=0 或非有限值返回 null（无法手动对焦）。 */
    fun distanceCmToDiopter(cm: Float): Float? =
        if (!cm.isFinite() || cm <= 0f) null else 100f / cm

    /** 屈光度 → 对焦距离（厘米）；0 表示无穷远。 */
    fun diopterToDistanceCm(diopter: Float): Float? {
        if (!diopter.isFinite()) return null
        if (diopter <= 0f) return null
        return 100f / diopter
    }

    /* -------------------------------- 变焦 -------------------------------- */

    fun clampZoom(requested: Float, maxZoom: Float): Float {
        val hi = if (maxZoom.isFinite() && maxZoom >= 1f) maxZoom else 1f
        val z = if (requested.isFinite()) requested else 1f
        return z.coerceIn(1f, hi)
    }

    data class CropRegion(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * 中心裁切数码变焦区域。宽高与偏移都取偶数：Bayer 传感器的裁剪区域若落在
     * 奇数列，CFA 相位会翻转导致拍出的照片偏色。
     */
    fun zoomCropRegion(activeWidth: Int, activeHeight: Int, zoom: Float): CropRegion {
        val z = if (zoom.isFinite()) zoom.coerceIn(1f, 100f) else 1f
        val w = evenAtLeast2((activeWidth / z).roundToInt().coerceIn(1, activeWidth))
        val h = evenAtLeast2((activeHeight / z).roundToInt().coerceIn(1, activeHeight))
        val x = evenDown((activeWidth - w) / 2)
        val y = evenDown((activeHeight - h) / 2)
        return CropRegion(x, y, w, h)
    }

    private fun evenAtLeast2(v: Int): Int = max(2, v and 0xFFFFFFFE.toInt())

    private fun evenDown(v: Int): Int = (v and 0xFFFFFFFE.toInt()).coerceAtLeast(0)

    /* ----------------------------- 物理镜头角色 ----------------------------- */

    data class LensCandidate(val id: String, val focalMm: Float, val minFocusDiopter: Float)

    /**
     * 依据焦距与最近对焦距离给物理镜头分配角色，返回与入参等长且顺序一致的列表。
     * 角色：ultrawide / wide / tele / macro（macro 优先级最高，代表近摄能力而非焦段）。
     */
    fun assignLensRoles(candidates: List<LensCandidate>): List<String> {
        val n = candidates.size
        val roles = MutableList(n) { "wide" }
        if (n == 0) return roles
        val order = candidates.indices.sortedBy { candidates[it].focalMm }
        if (n >= 2) {
            val shortest = order.first()
            val second = order[1]
            if (candidates[second].focalMm >= candidates[shortest].focalMm * ULTRA_WIDE_RATIO) {
                roles[shortest] = "ultrawide"
            }
            val longest = order.last()
            val prev = order[n - 2]
            if (candidates[longest].focalMm >= candidates[prev].focalMm * TELE_RATIO) {
                roles[longest] = "tele"
            }
        }
        for (i in 0 until n) {
            if (candidates[i].minFocusDiopter >= MACRO_MIN_DIOPTER) roles[i] = "macro"
        }
        return roles
    }

    /* ------------------------------ 深度图 ------------------------------ */

    data class DepthStats(
        val total: Int,
        val valid: Int,
        val minMm: Int,
        val maxMm: Int,
        val meanMm: Double,
        val medianMm: Int,
        val p05Mm: Int,
        val p95Mm: Int,
    ) {
        val coverage: Double get() = if (total <= 0) 0.0 else valid.toDouble() / total
    }

    /**
     * DEPTH16 统计：0 与非正值视为无效像素。用 1mm 直方图求中位/分位，
     * 避免对 200 万像素做排序（O(n) 而非 O(n log n)）。
     */
    fun depthStats(depthMm: IntArray): DepthStats {
        val hist = IntArray(HIST_BUCKETS)
        var valid = 0
        var sum = 0.0
        var min = Int.MAX_VALUE
        var max = 0
        for (v in depthMm) {
            if (v <= 0) continue
            val bucket = if (v >= HIST_BUCKETS) HIST_BUCKETS - 1 else v
            hist[bucket]++
            valid++
            sum += v
            if (v < min) min = v
            if (v > max) max = v
        }
        if (valid == 0) {
            return DepthStats(depthMm.size, 0, 0, 0, 0.0, 0, 0, 0)
        }
        return DepthStats(
            total = depthMm.size,
            valid = valid,
            minMm = min,
            maxMm = max,
            meanMm = sum / valid,
            medianMm = percentile(hist, valid, 0.5),
            p05Mm = percentile(hist, valid, 0.05),
            p95Mm = percentile(hist, valid, 0.95),
        )
    }

    private const val HIST_BUCKETS = 65536

    private fun percentile(hist: IntArray, valid: Int, q: Double): Int {
        val target = max(1, (valid * q).roundToInt())
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return hist.size - 1
    }

    /**
     * 深度伪彩（RGB8）：近暖远冷，无效像素深灰。
     * nearMm/farMm 由调用方按实际统计给出，超出范围的像素夹紧。
     */
    fun colorizeDepth(depthMm: IntArray, nearMm: Int, farMm: Int): ByteArray {
        val span = max(1, farMm - nearMm).toFloat()
        val out = ByteArray(depthMm.size * 3)
        var p = 0
        for (v in depthMm) {
            if (v <= 0) {
                out[p++] = 40
                out[p++] = 40
                out[p++] = 40
                continue
            }
            val t = ((v - nearMm) / span).coerceIn(0f, 1f)
            // jet 三段线性近似：0→红（近）, 0.5→绿, 1→蓝（远）
            val r = (1f - 2f * t).coerceIn(0f, 1f)
            val b = (2f * t - 1f).coerceIn(0f, 1f)
            val g = (1f - abs(2f * t - 1f)).coerceIn(0f, 1f)
            out[p++] = (r * 255f).roundToInt().toByte()
            out[p++] = (g * 255f).roundToInt().toByte()
            out[p++] = (b * 255f).roundToInt().toByte()
        }
        return out
    }

    /**
     * 由深度图与针孔内参生成点云（单位 mm，z 前向）。stride>1 时按网格抽样，
     * 再用 [maxPoints] 二次抽稀，保证文件体积可控。
     */
    fun pointCloud(
        depthMm: IntArray,
        width: Int,
        height: Int,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        stride: Int = 1,
        maxPoints: Int = 20000,
    ): FloatArray {
        val step = max(1, stride)
        val stepX = max(1, width / step)
        val stepY = max(1, height / step)
        var estimate = stepX.toLong() * stepY
        if (estimate <= 0L) return FloatArray(0)
        val decimate = if (estimate > maxPoints) (estimate / maxPoints + 1).toInt() else 1
        val fxSafe = if (fx.isFinite() && fx > 0f) fx else 1f
        val fySafe = if (fy.isFinite() && fy > 0f) fy else 1f
        val buf = ArrayList<Float>(minOf(estimate, maxPoints.toLong()).toInt() * 3)
        var y = 0
        var yCount = 0
        while (y < height) {
            if (yCount % decimate == 0) {
                var x = 0
                var xCount = 0
                while (x < width) {
                    if (xCount % decimate == 0) {
                        val z = depthMm[y * width + x]
                        if (z > 0) {
                            val xf = (x - cx) * z / fxSafe
                            val yf = (y - cy) * z / fySafe
                            buf.add(xf)
                            buf.add(yf)
                            buf.add(z.toFloat())
                        }
                    }
                    x += step
                    xCount++
                }
            }
            y += step
            yCount++
        }
        return buf.toFloatArray()
    }

    /* ---------------------------- 连续帧分析 ---------------------------- */

    /**
     * 把 Y 平面降采样成固定网格（每格取均值），供帧间差分/亮度趋势统计。
     * 输入允许 rowStride 有填充。
     */
    fun lumaGrid(
        y: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        gridW: Int = 64,
        gridH: Int = 48,
    ): IntArray {
        val gw = max(1, gridW)
        val gh = max(1, gridH)
        val out = IntArray(gw * gh)
        if (width <= 0 || height <= 0) return out
        for (gy in 0 until gh) {
            val y0 = gy * height / gh
            val y1 = max(y0 + 1, (gy + 1) * height / gh)
            for (gx in 0 until gw) {
                val x0 = gx * width / gw
                val x1 = max(x0 + 1, (gx + 1) * width / gw)
                var sum = 0L
                var count = 0
                var yy = y0
                while (yy < y1 && yy < height) {
                    val rowBase = yy * rowStride
                    var xx = x0
                    while (xx < x1 && xx < width) {
                        sum += y[rowBase + xx].toInt() and 0xFF
                        count++
                        xx++
                    }
                    yy++
                }
                out[gy * gw + gx] = if (count == 0) 0 else (sum / count).toInt()
            }
        }
        return out
    }

    /** 两帧亮度网格的平均绝对差（0..255），作为运动强度指标。 */
    fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var sum = 0L
        for (i in 0 until n) sum += abs(a[i] - b[i])
        return sum.toDouble() / n
    }

    /** 运动强度分级，供模型判断场景（静止/轻微/明显/剧烈）。 */
    fun motionLevel(meanDiff: Double): String = when {
        meanDiff < 1.0 -> "static"
        meanDiff < 4.0 -> "low"
        meanDiff < 12.0 -> "medium"
        else -> "high"
    }

    /** 在可用帧率范围里挑最贴近目标 fps 的一档（同等时优先窄区间）。 */
    fun pickFpsRange(ranges: List<IntArray>?, wantFps: Int): IntArray? {
        if (ranges.isNullOrEmpty()) return null
        val want = wantFps.coerceIn(1, 240)
        val fitting = ranges.filter { it.size >= 2 && it[1] >= want && it[0] <= want }
        if (fitting.isNotEmpty()) {
            return fitting.minWithOrNull(compareBy({ it[1] }, { it[1] - it[0] }))
        }
        return ranges.filter { it.size >= 2 }.minByOrNull { abs(it[1] - want) }
    }

    /* ------------------------------- RAW ------------------------------- */

    /** RAW16（RAW_SENSOR）：16 bit 小端，pixelStride 通常为 2，rowStride 可能带填充。 */
    fun readRaw16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        out: IntArray = IntArray(width * height),
    ): IntArray {
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val ps = if (pixelStride > 0) pixelStride else 2
        for (y in 0 until height) {
            val rowBase = y * rowStride
            for (x in 0 until width) {
                val idx = rowBase + x * ps
                out[y * width + x] = if (idx + 1 < buf.limit()) buf.getShort(idx).toInt() and 0xFFFF else 0
            }
        }
        return out
    }

    /**
     * RAW10 解包：4 像素打包在 5 字节里，低 2 位集中在第 5 字节（高→低像素）。
     * 每行按 rowStride 跳转，行内按 5 字节一组。
     */
    fun unpackRaw10(
        src: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        out: IntArray = IntArray(width * height),
    ): IntArray {
        for (y in 0 until height) {
            val rowBase = y * rowStride
            var x = 0
            while (x < width) {
                val base = rowBase + (x / 4) * 5
                if (base + 4 >= src.size) break
                val b0 = src[base].toInt() and 0xFF
                val b1 = src[base + 1].toInt() and 0xFF
                val b2 = src[base + 2].toInt() and 0xFF
                val b3 = src[base + 3].toInt() and 0xFF
                val b4 = src[base + 4].toInt() and 0xFF
                val row = y * width
                if (x < width) out[row + x] = (b0 shl 2) or (b4 and 0x03)
                if (x + 1 < width) out[row + x + 1] = (b1 shl 2) or ((b4 shr 2) and 0x03)
                if (x + 2 < width) out[row + x + 2] = (b2 shl 2) or ((b4 shr 4) and 0x03)
                if (x + 3 < width) out[row + x + 3] = (b3 shl 2) or ((b4 shr 6) and 0x03)
                x += 4
            }
        }
        return out
    }

    /** RAW12 解包：2 像素打包在 3 字节里（第 2 字节高 4 位给第 2 像素）。 */
    fun unpackRaw12(
        src: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        out: IntArray = IntArray(width * height),
    ): IntArray {
        for (y in 0 until height) {
            val rowBase = y * rowStride
            var x = 0
            while (x < width) {
                val base = rowBase + (x / 2) * 3
                if (base + 2 >= src.size) break
                val b0 = src[base].toInt() and 0xFF
                val b1 = src[base + 1].toInt() and 0xFF
                val b2 = src[base + 2].toInt() and 0xFF
                val row = y * width
                if (x < width) out[row + x] = (b0 shl 4) or (b1 and 0x0F)
                if (x + 1 < width) out[row + x + 1] = (b2 shl 4) or ((b1 shr 4) and 0x0F)
                x += 2
            }
        }
        return out
    }

    /** SENSOR_INFO_COLOR_FILTER_ARRANGEMENT → DNG CFAPattern（0=R,1=G,2=B，TL/TR/BL/BR）。 */
    fun cfaPattern(arrangement: Int): IntArray = when (arrangement) {
        1 -> intArrayOf(0, 1, 1, 2) // RGGB
        2 -> intArrayOf(1, 0, 2, 1) // GRBG
        3 -> intArrayOf(1, 2, 0, 1) // GBRG
        4 -> intArrayOf(2, 1, 1, 0) // BGGR
        else -> intArrayOf(0, 1, 1, 2)
    }

    /**
     * Bayer → 半分辨率灰度预览（每个 2x2 取两个 green 像素均值），
     * 按 [blackLevel, whiteLevel] 归一化并做 gamma 1/2.2 提亮，便于肉眼/模型确认取景。
     */
    fun rawPreviewGray(
        samples: IntArray,
        width: Int,
        height: Int,
        cfa: IntArray,
        blackLevel: Int,
        whiteLevel: Int,
    ): ByteArray {
        val pw = max(1, width / 2)
        val ph = max(1, height / 2)
        val out = ByteArray(pw * ph)
        val span = max(1, whiteLevel - blackLevel).toFloat()
        // 2x2 中 green 的位置：CFA 的 4 个条目里值等于 1 的两个索引
        val greens = ArrayList<Int>(2)
        for (i in 0 until minOf(4, cfa.size)) if (cfa[i] == 1) greens.add(i)
        val greenIdx = if (greens.size == 2) intArrayOf(greens[0], greens[1]) else intArrayOf(1, 2)
        for (py in 0 until ph) {
            for (px in 0 until pw) {
                val x0 = px * 2
                val y0 = py * 2
                var sum = 0
                var n = 0
                for (idx in greenIdx) {
                    val gx = x0 + (idx % 2)
                    val gy = y0 + (idx / 2)
                    if (gx >= width || gy >= height) continue
                    sum += samples[gy * width + gx]
                    n++
                }
                val raw = if (n == 0) 0 else sum / n
                val norm = ((raw - blackLevel) / span).coerceIn(0f, 1f)
                out[py * pw + px] = (Math.pow(norm.toDouble(), 1.0 / 2.2) * 255.0).roundToInt()
                    .coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** 深度/RAW 用到的 3x3 求逆（行主序）。矩阵奇异或含非法值返回 null。 */
    fun invert3x3(m: FloatArray): FloatArray? {
        if (m.size < 9) return null
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        listOf(a, b, c, d, e, f, g, h, i).forEach { if (!it.isFinite()) return null }
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (!det.isFinite() || abs(det) < 1e-9) return null
        val inv = 1.0 / det
        return floatArrayOf(
            ((e * i - f * h) * inv).toFloat(),
            ((c * h - b * i) * inv).toFloat(),
            ((b * f - c * e) * inv).toFloat(),
            ((f * g - d * i) * inv).toFloat(),
            ((a * i - c * g) * inv).toFloat(),
            ((c * d - a * f) * inv).toFloat(),
            ((d * h - e * g) * inv).toFloat(),
            ((b * g - a * h) * inv).toFloat(),
            ((a * e - b * d) * inv).toFloat(),
        )
    }
}

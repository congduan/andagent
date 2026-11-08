package com.dc.andagent.tools

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.*

/** All transforms return a new bitmap; the caller owns each intermediate. */
sealed class ImageOp {
    data class Crop(val x: Int?, val y: Int?, val w: Int?, val h: Int?, val aspect: String?, val anchor: String) : ImageOp()
    data class Rotate(val degrees: Float) : ImageOp()
    data object FlipH : ImageOp()
    data object FlipV : ImageOp()
    data class Resize(val width: Int?, val height: Int?, val keepAspect: Boolean) : ImageOp()
    data class Brightness(val value: Int) : ImageOp()
    data class Contrast(val value: Int) : ImageOp()
    data class Saturation(val value: Int) : ImageOp()
    data class Sharpness(val value: Int) : ImageOp()
    data class Blur(val radius: Int) : ImageOp()
    data object Grayscale : ImageOp()
    data object Sepia : ImageOp()
    data object Invert : ImageOp()
    data class Border(val widthPx: Int, val color: Int, val style: String) : ImageOp()
    data class Watermark(val text: String, val position: String, val alpha: Int) : ImageOp()
    data class ComposeOver(val source: String, val x: Int, val y: Int, val alpha: Int) : ImageOp()
    /** R18: .cube 3D LUT (or a built-in preset) with strength blending. */
    data class Lut(val preset: String?, val cube: String?, val strength: Float) : ImageOp()
    // ---- R109 ----
    /** Mirror. axis: horizontal|h|x, vertical|v|y, or both. */
    data class Flip(val axis: String) : ImageOp()
    /** Arbitrary-angle rotation that fills the exposed corners with a solid background. */
    data class RotateFree(val degrees: Float, val background: Int) : ImageOp()
    /** Text with size/color/alpha, a 9-grid anchor or explicit x/y (baseline), and rotation. */
    data class TextDraw(val text: String, val sizePx: Int, val color: Int, val alpha: Int, val x: Int?, val y: Int?, val anchor: String, val rotation: Float) : ImageOp()
    /** Gaussian-ish separable blur restricted to a rect; a null rect component means full image. */
    data class GaussianBlur(val radius: Int, val x: Int?, val y: Int?, val w: Int?, val h: Int?) : ImageOp()
    /** Darken towards the corners by blending to [color]; strength 0..1. */
    data class Vignette(val strength: Float, val color: Int) : ImageOp()
    /** Fast per-channel percentile contrast stretch + mean-to-middle luminance correction. */
    data object AutoEnhance : ImageOp()
}

object ImageEditEngine {
    const val MAX_INPUT = 20L * 1024 * 1024
    const val MAX_EDGE = 2048
    /** Upper bound on the summed input pixels held at once by a collage (~128 MB ARGB_8888). */
    const val MAX_COLLAGE_INPUT_PIXELS = 32_000_000L

    data class SourceInfo(val width: Int, val height: Int, val format: String, val sizeBytes: Long, val exifOrientation: Int)

    private fun checkSize(width: Int, height: Int) {
        require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608) { "输出尺寸过大（最大 4096 边、约 8 百万像素）" }
    }
    /**
     * Dimensions the edit pipeline actually operates on: decode() downsamples to
     * MAX_EDGE and physically rotates pixels for EXIF orientations 5..8 (which
     * swaps width/height). image_info used to report the raw stored size, so crop
     * coordinates derived from it could fall outside the decoded bitmap.
     */
    internal fun effectiveDims(srcW: Int, srcH: Int, orientation: Int): Pair<Int, Int> {
        var sample = 1
        while (max(srcW, srcH) / sample > MAX_EDGE) sample *= 2
        val w = (srcW + sample - 1) / sample
        val h = (srcH + sample - 1) / sample
        return if (orientation in 5..8) h to w else w to h
    }
    /** Summed input pixels of a collage; used to bound the memory held at once. */
    internal fun totalPixels(images: List<Bitmap>): Long = images.sumOf { it.width.toLong() * it.height }
    internal fun collageInputAllowed(totalPixels: Long): Boolean = totalPixels <= MAX_COLLAGE_INPUT_PIXELS
    private fun open(context: Context, source: String): ByteArray {
        val raw = when {
            source.startsWith("data:image/") || source.startsWith("base64,") -> {
                val encoded = source.substringAfter("base64,")
                require(encoded.length <= MAX_INPUT * 4 / 3 + 16) { "图片超过 20MB" }
                Base64.decode(encoded, Base64.DEFAULT)
            }
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() }
                ?: error("无法读取图片 URI")
            source.toLongOrNull() != null -> {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, source.toLong())
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("相册图片不存在或无读取权限")
            }
            else -> File(source).inputStream().use { it.readBytes() }
        }
        require(raw.isNotEmpty() && raw.size <= MAX_INPUT) { "图片为空或超过 20MB" }
        return raw
    }
    fun info(context: Context, source: String): SourceInfo {
        val bytes = open(context, source)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrElse { error("图片解码失败：${it.message}") }
        require(options.outWidth > 0 && options.outHeight > 0) { "文件不是有效图片或已损坏" }
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val (w, h) = effectiveDims(options.outWidth, options.outHeight, orientation)
        return SourceInfo(w, h, options.outMimeType ?: "unknown", bytes.size.toLong(), orientation)
    }
    fun decode(context: Context, source: String, maxEdge: Int = MAX_EDGE, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        val bytes = open(context, source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }.getOrElse { error("图片解码失败：${it.message}") }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "文件不是有效图片或已损坏" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        // OCR 等分析型消费方需要比编辑链路更高的分辨率，但仍要有像素上限护栏（ARGB_8888 常驻内存）。
        while (sample < 32 && (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > maxPixels) sample *= 2
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }.getOrNull()
            ?: error("图片解码失败或内存不足")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(270f)
        }
        if (orientation == 1 || orientation !in 2..8) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
    fun apply(source: Bitmap, ops: List<ImageOp>, loader: ((String) -> Bitmap)? = null): Bitmap {
        var current = source
        for (op in ops) {
            val next = transform(current, op, loader)
            if (next !== current) current.recycle()
            current = next
        }
        return current
    }
    private fun transform(b: Bitmap, op: ImageOp, loader: ((String) -> Bitmap)?): Bitmap = when (op) {
        is ImageOp.Crop -> {
            val ratio = op.aspect?.split(':')?.takeIf { it.size == 2 }?.let { it[0].toFloatOrNull()?.div(it[1].toFloatOrNull() ?: 0f) }
            val cw: Int; val ch: Int
            if (ratio != null && ratio.isFinite() && ratio > 0) {
                cw = min(b.width, (b.height * ratio).roundToInt()).coerceAtLeast(1)
                ch = min(b.height, (cw / ratio).roundToInt()).coerceAtLeast(1)
            } else { cw = op.w ?: b.width; ch = op.h ?: b.height }
            require(cw > 0 && ch > 0 && cw <= b.width && ch <= b.height) { "裁剪范围无效" }
            val x = op.x ?: when (op.anchor.lowercase()) { "left", "top-left", "bottom-left" -> 0; "right", "top-right", "bottom-right" -> b.width - cw; else -> (b.width - cw) / 2 }
            val y = op.y ?: when (op.anchor.lowercase()) { "top", "top-left", "top-right" -> 0; "bottom", "bottom-left", "bottom-right" -> b.height - ch; else -> (b.height - ch) / 2 }
            require(x >= 0 && y >= 0 && x + cw <= b.width && y + ch <= b.height) { "裁剪坐标超出图片" }
            Bitmap.createBitmap(b, x, y, cw, ch)
        }
        is ImageOp.Rotate -> {
            require(op.degrees.isFinite()) { "旋转角度无效" }
            val m = Matrix().apply { setRotate(op.degrees) }
            val box = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); m.mapRect(box)
            val w = ceil(box.width() - .001f).toInt(); val h = ceil(box.height() - .001f).toInt(); checkSize(w, h)
            m.postTranslate(-box.left, -box.top)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(b, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        }
        ImageOp.FlipH, ImageOp.FlipV -> {
            val m = Matrix().apply { setScale(if (op == ImageOp.FlipH) -1f else 1f, if (op == ImageOp.FlipV) -1f else 1f) }
            Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        is ImageOp.Resize -> {
            val w = op.width ?: if (op.keepAspect && op.height != null) (b.width * op.height.toFloat() / b.height).roundToInt() else b.width
            val h = op.height ?: if (op.keepAspect && op.width != null) (b.height * op.width.toFloat() / b.width).roundToInt() else b.height
            val (rw, rh) = if (op.keepAspect && op.width != null && op.height != null) {
                val scale = min(w.toFloat() / b.width, h.toFloat() / b.height); Pair((b.width * scale).roundToInt(), (b.height * scale).roundToInt())
            } else Pair(w, h)
            checkSize(rw, rh); Bitmap.createScaledBitmap(b, rw, rh, true)
        }
        is ImageOp.Brightness -> color(b, ColorMatrix().apply { set(floatArrayOf(1f,0f,0f,0f,op.value*2.55f, 0f,1f,0f,0f,op.value*2.55f, 0f,0f,1f,0f,op.value*2.55f, 0f,0f,0f,1f,0f)) })
        is ImageOp.Contrast -> { val s = (1f + op.value / 100f).coerceAtLeast(0f); val t = 128f * (1f - s); color(b, ColorMatrix(floatArrayOf(s,0f,0f,0f,t, 0f,s,0f,0f,t, 0f,0f,s,0f,t, 0f,0f,0f,1f,0f))) }
        is ImageOp.Saturation -> color(b, ColorMatrix().apply { setSaturation((1f + op.value / 100f).coerceAtLeast(0f)) })
        ImageOp.Grayscale -> color(b, ColorMatrix().apply { setSaturation(0f) })
        ImageOp.Sepia -> color(b, ColorMatrix(floatArrayOf(.393f,.769f,.189f,0f,0f, .349f,.686f,.168f,0f,0f, .272f,.534f,.131f,0f,0f, 0f,0f,0f,1f,0f)))
        ImageOp.Invert -> color(b, ColorMatrix(floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)))
        is ImageOp.Sharpness -> convolve(b, op.value / 100f)
        is ImageOp.Blur -> {
            val factor = (1 + op.radius.coerceIn(0, 100) / 2).coerceAtMost(min(b.width, b.height))
            if (factor <= 1) b else Bitmap.createScaledBitmap(b, max(1, b.width / factor), max(1, b.height / factor), true).let { small ->
                Bitmap.createScaledBitmap(small, b.width, b.height, true).also { small.recycle() }
            }
        }
        is ImageOp.Border -> border(b, op)
        is ImageOp.Watermark -> Bitmap.createBitmap(b).also { out ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = op.alpha.coerceIn(0, 255); textSize = max(16f, b.width / 30f); setShadowLayer(2f, 1f, 1f, Color.BLACK) }
            val x = when (op.position) { "top-left", "bottom-left" -> 16f; "center" -> (b.width - paint.measureText(op.text)) / 2f; else -> b.width - paint.measureText(op.text) - 16f }
            val y = when (op.position) { "top-left", "top-right" -> paint.textSize + 16f; "center" -> b.height / 2f; else -> b.height - 16f }
            Canvas(out).drawText(op.text, x, y, paint)
        }
        is ImageOp.ComposeOver -> {
            val overlay = requireNotNull(loader) { "贴图源不可用" }(op.source)
            try { Bitmap.createBitmap(b).also { Canvas(it).drawBitmap(overlay, op.x.toFloat(), op.y.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = op.alpha.coerceIn(0,255) }) } }
            finally { overlay.recycle() }
        }
        is ImageOp.Lut -> lut(b, op)
        is ImageOp.Flip -> flip(b, op.axis)
        is ImageOp.RotateFree -> rotateFree(b, op.degrees, op.background)
        is ImageOp.TextDraw -> textDraw(b, op)
        is ImageOp.GaussianBlur -> gaussianBlur(b, op)
        is ImageOp.Vignette -> vignette(b, op.strength, op.color)
        ImageOp.AutoEnhance -> autoEnhance(b)
    }

    private data class CubeLut(val size: Int, val data: FloatArray)

    private fun parseCube(text: String): CubeLut {
        require(text.length <= 2_000_000) { ".cube 文件过大" }
        var size = 0
        val values = ArrayList<Float>(4096 * 3)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore("#").trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            if (parts[0].equals("LUT_3D_SIZE", true)) {
                size = parts.getOrNull(1)?.toIntOrNull() ?: error("LUT_3D_SIZE 无效")
                require(size in 2..64) { "LUT_3D_SIZE 须在 2..64" }
                continue
            }
            if (parts[0].startsWith("DOMAIN_") || parts[0].equals("TITLE", true)) continue
            require(size > 0) { ".cube 缺少 LUT_3D_SIZE" }
            val r = parts.getOrNull(0)?.toFloatOrNull() ?: continue
            val g = parts.getOrNull(1)?.toFloatOrNull() ?: error(".cube 数据行缺少 G")
            val bl = parts.getOrNull(2)?.toFloatOrNull() ?: error(".cube 数据行缺少 B")
            values += r; values += g; values += bl
        }
        require(values.size == size * size * size * 3) { ".cube 数据量应为 ${size * size * size * 3}，实际 ${values.size}" }
        return CubeLut(size, values.toFloatArray())
    }

    /** Built-in tone presets: identity grid passed through a simple tone curve. */
    private fun presetCube(name: String): CubeLut {
        val n = 16
        val data = FloatArray(n * n * n * 3)
        fun clamp(v: Float) = v.coerceIn(0f, 1f)
        for (bi in 0 until n) for (gi in 0 until n) for (ri in 0 until n) {
            val r = ri / (n - 1f); val g = gi / (n - 1f); val b = bi / (n - 1f)
            val (nr, ng, nb) = when (name.lowercase()) {
                "warm" -> Triple(clamp(r * 1.06f + .02f), clamp(g * 1.01f), clamp(b * .93f))
                "cool" -> Triple(clamp(r * .93f), clamp(g * 1.01f), clamp(b * 1.07f + .02f))
                "vintage" -> {
                    val l = (r + g + b) / 3f
                    Triple(clamp(r * .88f + l * .12f + .06f), clamp(g * .86f + l * .1f + .04f), clamp(b * .78f + l * .12f))
                }
                "cinematic" -> {
                    fun s(v: Float) = clamp((v - .5f) * 1.14f + .5f)
                    Triple(clamp(s(r) + .02f), s(g), clamp(s(b) - .02f + (1f - luma(r, g, b)) * .04f))
                }
                "bw" -> { val l = luma(r, g, b); Triple(l, l, l) }
                else -> error("未知内置滤镜：$name（可用 warm/cool/vintage/cinematic/bw）")
            }
            val at = ((bi * n + gi) * n + ri) * 3
            data[at] = nr; data[at + 1] = ng; data[at + 2] = nb
        }
        return CubeLut(n, data)
    }

    private fun luma(r: Float, g: Float, b: Float) = .2126f * r + .7152f * g + .0722f * b

    private fun lut(b: Bitmap, op: ImageOp.Lut): Bitmap {
        val text = op.cube
        val table = when {
            text != null && text.contains("LUT_3D_SIZE", true) -> parseCube(text)
            text != null -> parseCube(File(text).readText(Charsets.UTF_8))
            else -> presetCube(op.preset ?: "warm")
        }
        val strength = op.strength.coerceIn(0f, 1f)
        if (strength <= 0f) return b
        val n = table.size
        val data = table.data
        val w = b.width; val h = b.height
        val pixels = IntArray(w * h)
        b.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c ushr 16 and 255) / 255f
            val g = (c ushr 8 and 255) / 255f
            val bl = (c and 255) / 255f
            val a = c ushr 24 and 255
            var sr = 0f; var sg = 0f; var sb = 0f
            val x = r * (n - 1); val y = g * (n - 1); val z = bl * (n - 1)
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val z0 = floor(z).toInt()
            val x1 = min(x0 + 1, n - 1); val y1 = min(y0 + 1, n - 1); val z1 = min(z0 + 1, n - 1)
            val fx = x - x0; val fy = y - y0; val fz = z - z0
            for (dz in 0..1) for (dy in 0..1) for (dx in 0..1) {
                val xi = if (dx == 0) x0 else x1
                val yi = if (dy == 0) y0 else y1
                val zi = if (dz == 0) z0 else z1
                val weight = (if (dx == 0) 1f - fx else fx) *
                    (if (dy == 0) 1f - fy else fy) *
                    (if (dz == 0) 1f - fz else fz)
                val at = ((zi * n + yi) * n + xi) * 3
                sr += data[at] * weight; sg += data[at + 1] * weight; sb += data[at + 2] * weight
            }
            val nr = (r + (sr - r) * strength).coerceIn(0f, 1f)
            val ng = (g + (sg - g) * strength).coerceIn(0f, 1f)
            val nb = (bl + (sb - bl) * strength).coerceIn(0f, 1f)
            pixels[i] = (a shl 24) or ((nr * 255).roundToInt() shl 16) or ((ng * 255).roundToInt() shl 8) or (nb * 255).roundToInt()
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
    // ---------------------- R109 pixel kernels ----------------------
    // Pure IntArray transforms (no Canvas/Skia rasterization) so JVM unit tests
    // assert exact pixel results. textDraw is the one Canvas-based op.

    private fun pixelsOf(b: Bitmap): IntArray {
        val p = IntArray(b.width * b.height)
        b.getPixels(p, 0, b.width, 0, 0, b.width, b.height)
        return p
    }
    private fun bitmapOf(p: IntArray, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888)
    private fun channel(c: Int, shift: Int) = c ushr shift and 255
    private fun pack(a: Float, r: Float, g: Float, b: Float): Int =
        (a.roundToInt().coerceIn(0, 255) shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    private fun flip(b: Bitmap, axis: String): Bitmap {
        val w = b.width; val h = b.height
        val src = pixelsOf(b); val dst = IntArray(w * h)
        val a = axis.lowercase()
        val sx = a == "horizontal" || a == "h" || a == "x" || a == "both"
        val sy = a == "vertical" || a == "v" || a == "y" || a == "both"
        for (y in 0 until h) for (x in 0 until w) {
            val nx = if (sx) w - 1 - x else x
            val ny = if (sy) h - 1 - y else y
            dst[y * w + x] = src[ny * w + nx]
        }
        return bitmapOf(dst, w, h)
    }

    /**
     * Bilinear in PREMULTIPLIED-alpha space (review #6): interpolating straight
     * RGBA lets fully transparent neighbors (often RGB=0, black) bleed into
     * visible edge pixels, producing dark/color fringes on rotate_free.
     */
    private fun bilinear(src: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val tx = fx - x0; val ty = fy - y0
        val p0 = src[y0 * w + x0]; val p1 = src[y0 * w + x1]
        val p2 = src[y1 * w + x0]; val p3 = src[y1 * w + x1]
        fun pm(p: Int, shift: Int): Float = (channel(p, shift) * (p ushr 24 and 255)) / 255f
        fun lerp(a: Float, b: Float, t: Float) = a * (1 - t) + b * t
        fun row(shift: Int) =
            lerp(lerp(pm(p0, shift), pm(p1, shift), tx), lerp(pm(p2, shift), pm(p3, shift), tx), ty)
        val a = row(24)
        fun unpm(shift: Int): Float = if (a <= 0f) 0f else row(shift) * 255f / a
        return pack(a, unpm(16), unpm(8), unpm(0))
    }

    /** Rotate by any angle, filling exposed corners with [background] (bilinear inverse map). */
    private fun rotateFree(b: Bitmap, degrees: Float, background: Int): Bitmap {
        require(degrees.isFinite()) { "旋转角度无效" }
        val rad = degrees * PI.toFloat() / 180f
        val rawC = cos(rad); val rawS = sin(rad)
        val c = if (abs(rawC) < 1e-6f) 0f else rawC
        val s = if (abs(rawS) < 1e-6f) 0f else rawS
        val w = b.width; val h = b.height
        val nw = ceil(abs(w * c) + abs(h * s)).toInt().coerceAtLeast(1)
        val nh = ceil(abs(w * s) + abs(h * c)).toInt().coerceAtLeast(1)
        checkSize(nw, nh)
        val src = pixelsOf(b)
        val dst = IntArray(nw * nh).also { it.fill(background) }
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val ncx = (nw - 1) / 2f; val ncy = (nh - 1) / 2f
        for (jy in 0 until nh) for (ix in 0 until nw) {
            val dx = ix - ncx; val dy = jy - ncy
            val fx = dx * c + dy * s + cx
            val fy = -dx * s + dy * c + cy
            if (fx >= 0f && fy >= 0f && fx <= w - 1f && fy <= h - 1f) {
                dst[jy * nw + ix] = bilinear(src, w, h, fx, fy)
            }
        }
        return bitmapOf(dst, nw, nh)
    }

    /** Separable gaussian kernel applied only inside a rect; outside pixels are untouched. */
    private fun gaussianBlur(b: Bitmap, op: ImageOp.GaussianBlur): Bitmap {
        val w = b.width; val h = b.height
        val r = op.radius.coerceIn(1, 15)
        val sigma = r / 2f + 0.5f
        val k = FloatArray(2 * r + 1)
        var ksum = 0f
        for (i in -r..r) { val v = exp(-(i * i).toFloat() / (2 * sigma * sigma)); k[i + r] = v; ksum += v }
        for (i in k.indices) k[i] /= ksum
        val rx = (op.x ?: 0).coerceIn(0, w - 1)
        val ry = (op.y ?: 0).coerceIn(0, h - 1)
        val rw = (op.w ?: (w - rx)).coerceIn(1, w - rx)
        val rh = (op.h ?: (h - ry)).coerceIn(1, h - ry)
        val rRight = rx + rw; val rBottom = ry + rh
        val src = pixelsOf(b); val out = src.copyOf()
        // Premultiplied-alpha accumulation (review #6): straight-RGBA blur lets
        // transparent black neighbors darken visible pixels inside the region.
        val tmp = IntArray(rw * rh)
        for (jy in ry until rBottom) for (ix in rx until rRight) {
            var a = 0f; var rr = 0f; var g = 0f; var bl = 0f
            for (d in -r..r) {
                val sx = (ix + d).coerceIn(0, w - 1)
                val c = src[jy * w + sx]; val wt = k[d + r]; val al = channel(c, 24)
                a += al * wt; rr += channel(c, 16) * al / 255f * wt; g += channel(c, 8) * al / 255f * wt; bl += channel(c, 0) * al / 255f * wt
            }
            tmp[(jy - ry) * rw + (ix - rx)] = pack(a, rr, g, bl)
        }
        for (jy in ry until rBottom) for (ix in rx until rRight) {
            var a = 0f; var rr = 0f; var g = 0f; var bl = 0f
            for (d in -r..r) {
                val sy = (jy - ry + d).coerceIn(0, rh - 1)
                val cc = tmp[sy * rw + (ix - rx)]; val wt = k[d + r]
                a += channel(cc, 24) * wt; rr += channel(cc, 16) * wt; g += channel(cc, 8) * wt; bl += channel(cc, 0) * wt
            }
            val inv = if (a <= 0f) 0f else 255f / a
            out[jy * w + ix] = pack(a, rr * inv, g * inv, bl * inv)
        }
        return bitmapOf(out, w, h)
    }

    /** Blend towards [color] with a radial falloff: center unchanged, corners dark. */
    private fun vignette(b: Bitmap, strength: Float, color: Int): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return b
        val w = b.width; val h = b.height
        val src = pixelsOf(b); val out = IntArray(w * h)
        val cx = (w - 1) / 2f; val cy = (h - 1) / 2f
        val maxD = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val tr = Color.red(color); val tg = Color.green(color); val tb = Color.blue(color)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x - cx) / maxD; val dy = (y - cy) / maxD
            val f = (s * (dx * dx + dy * dy)).coerceIn(0f, 1f)
            val c = src[y * w + x]
            out[y * w + x] = (c and -0x1000000) or
                pack(0f, channel(c, 16) * (1 - f) + tr * f,
                    channel(c, 8) * (1 - f) + tg * f,
                    channel(c, 0) * (1 - f) + tb * f)
        }
        return bitmapOf(out, w, h)
    }

    /** Fast per-channel 1%/99% percentile contrast stretch + mean-to-middle gamma. */
    private fun autoEnhance(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val src = pixelsOf(b); val out = IntArray(w * h)
        val hist = Array(3) { IntArray(256) }
        for (c in src) {
            hist[0][channel(c, 16)]++; hist[1][channel(c, 8)]++; hist[2][channel(c, 0)]++
        }
        val target = (src.size / 100L).coerceAtLeast(1)
        fun percentile(ch: Int, low: Boolean): Int {
            var acc = 0L
            val range = if (low) 0..255 else 255 downTo 0
            for (v in range) { acc += hist[ch][v]; if (acc >= target) return v }
            return if (low) 0 else 255
        }
        val lo = IntArray(3) { percentile(it, true) }
        val hi = IntArray(3) { percentile(it, false) }
        // Zero-dynamic-range channels (flat color images) must keep their value —
        // stretching them mapped everything to black (review #4).
        fun stretch(v: Int, ch: Int) =
            if (hi[ch] <= lo[ch]) v
            else ((v - lo[ch]) * 255f / (hi[ch] - lo[ch])).roundToInt().coerceIn(0, 255)
        var lumSum = 0L
        for (i in src.indices) {
            val c = src[i]
            val r = stretch(channel(c, 16), 0); val g = stretch(channel(c, 8), 1); val bl = stretch(channel(c, 0), 2)
            lumSum += (r * 299 + g * 587 + bl * 114) / 1000
            out[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or bl
        }
        val mean = (lumSum / src.size).toInt().coerceIn(1, 254)
        if (mean !in 108..148) {
            val gamma = (ln(128f / 255f) / ln(mean / 255f)).coerceIn(0.5f, 2f)
            val lut = IntArray(256) { (255f * (it / 255f).pow(gamma)).roundToInt().coerceIn(0, 255) }
            for (i in out.indices) {
                val c = out[i]
                out[i] = (c and -0x1000000) or (lut[channel(c, 16)] shl 16) or
                    (lut[channel(c, 8)] shl 8) or lut[channel(c, 0)]
            }
        }
        return bitmapOf(out, w, h)
    }

    /** Baseline-left draw point for a 9-grid anchor (or explicit x/y), clamped to canvas. */
    internal fun anchorPoint(anchor: String, w: Int, h: Int, textW: Float, textH: Float,
                             margin: Float, x: Int?, y: Int?): Pair<Float, Float> {
        val a = anchor.lowercase()
        val ax = when (a) {
            "top-left", "center-left", "bottom-left", "left" -> margin
            "top-center", "center", "bottom-center" -> (w - textW) / 2f
            else -> w - textW - margin
        }
        val ay = when (a) {
            "top-left", "top-center", "top-right", "top" -> margin + textH
            "center-left", "center", "center-right" -> h / 2f + textH / 2f
            else -> h - margin
        }
        val px = (x?.toFloat() ?: ax).coerceIn(0f, (w - textW).coerceAtLeast(0f))
        val py = (y?.toFloat() ?: ay).coerceIn(textH.coerceAtMost(h.toFloat()), h.toFloat())
        return px to py
    }

    // Self-contained 5x7 bitmap font: Robolectric has no font rasterizer, and a
    // pure-pixel glyph keeps JVM tests deterministic and device-independent.
    // CJK/unknown chars fall back to a solid block; the existing `watermark` op
    // still uses the system font for full Unicode coverage.
    private val font5x7: Map<Char, IntArray> = mapOf(
        'A' to intArrayOf(0x0E,0x11,0x11,0x1F,0x11,0x11,0x11),
        'B' to intArrayOf(0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E),
        'C' to intArrayOf(0x0E,0x11,0x10,0x10,0x10,0x11,0x0E),
        'D' to intArrayOf(0x1E,0x11,0x11,0x11,0x11,0x11,0x1E),
        'E' to intArrayOf(0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F),
        'F' to intArrayOf(0x1F,0x10,0x10,0x1E,0x10,0x10,0x10),
        'G' to intArrayOf(0x0E,0x11,0x10,0x17,0x11,0x11,0x0F),
        'H' to intArrayOf(0x11,0x11,0x11,0x1F,0x11,0x11,0x11),
        'I' to intArrayOf(0x1F,0x04,0x04,0x04,0x04,0x04,0x1F),
        'J' to intArrayOf(0x07,0x02,0x02,0x02,0x02,0x12,0x0C),
        'K' to intArrayOf(0x11,0x12,0x14,0x18,0x14,0x12,0x11),
        'L' to intArrayOf(0x10,0x10,0x10,0x10,0x10,0x10,0x1F),
        'M' to intArrayOf(0x11,0x1B,0x15,0x15,0x11,0x11,0x11),
        'N' to intArrayOf(0x11,0x19,0x15,0x13,0x11,0x11,0x11),
        'O' to intArrayOf(0x0E,0x11,0x11,0x11,0x11,0x11,0x0E),
        'P' to intArrayOf(0x1E,0x11,0x11,0x1E,0x10,0x10,0x10),
        'Q' to intArrayOf(0x0E,0x11,0x11,0x11,0x15,0x12,0x0D),
        'R' to intArrayOf(0x1E,0x11,0x11,0x1E,0x14,0x12,0x11),
        'S' to intArrayOf(0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E),
        'T' to intArrayOf(0x1F,0x04,0x04,0x04,0x04,0x04,0x04),
        'U' to intArrayOf(0x11,0x11,0x11,0x11,0x11,0x11,0x0E),
        'V' to intArrayOf(0x11,0x11,0x11,0x11,0x11,0x0A,0x04),
        'W' to intArrayOf(0x11,0x11,0x11,0x15,0x15,0x1B,0x11),
        'X' to intArrayOf(0x11,0x11,0x0A,0x04,0x0A,0x11,0x11),
        'Y' to intArrayOf(0x11,0x11,0x0A,0x04,0x04,0x04,0x04),
        'Z' to intArrayOf(0x1F,0x01,0x02,0x04,0x08,0x10,0x1F),
        '0' to intArrayOf(0x0E,0x11,0x13,0x15,0x19,0x11,0x0E),
        '1' to intArrayOf(0x04,0x0C,0x04,0x04,0x04,0x04,0x0E),
        '2' to intArrayOf(0x0E,0x11,0x01,0x02,0x04,0x08,0x1F),
        '3' to intArrayOf(0x1F,0x02,0x04,0x02,0x01,0x11,0x0E),
        '4' to intArrayOf(0x02,0x06,0x0A,0x12,0x1F,0x02,0x02),
        '5' to intArrayOf(0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E),
        '6' to intArrayOf(0x06,0x08,0x10,0x1E,0x11,0x11,0x0E),
        '7' to intArrayOf(0x1F,0x01,0x02,0x04,0x08,0x08,0x08),
        '8' to intArrayOf(0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E),
        '9' to intArrayOf(0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C),
        ' ' to intArrayOf(0,0,0,0,0,0,0),
        '-' to intArrayOf(0,0,0,0x1F,0,0,0),
        '.' to intArrayOf(0,0,0,0,0,0x0C,0x0C),
        ':' to intArrayOf(0,0x0C,0x0C,0,0x0C,0x0C,0),
    )
    private val fontBlock = intArrayOf(0x1F,0x1F,0x1F,0x1F,0x1F,0x1F,0x1F)

    private fun textDraw(b: Bitmap, op: ImageOp.TextDraw): Bitmap {
        val w = b.width; val h = b.height
        val out = pixelsOf(b)
        val scale = (op.sizePx / 8f).roundToInt().coerceIn(1, 64)
        val cell = 5 * scale; val cellH = 7 * scale; val gap = scale
        val textW = (op.text.length * (cell + gap) - gap).toFloat().coerceAtLeast(1f)
        val textH = cellH.toFloat()
        val (px, py) = anchorPoint(op.anchor, w, h, textW, textH, 16f, op.x, op.y)
        val left = px; val top = py - textH
        val alpha = op.alpha.coerceIn(0, 255) / 255f
        val tr = Color.red(op.color); val tg = Color.green(op.color); val tb = Color.blue(op.color)
        val rot = op.rotation * PI.toFloat() / 180f
        val cr = cos(rot); val sr = sin(rot)
        val cx = left + textW / 2f; val cy = top + textH / 2f
        // parseOps caps text length; extra guard here so direct callers can't OOM
        // via a giant string. Non-latin glyphs (CJK etc.) fall back to fontBlock —
        // reject them instead of silently rendering solid boxes (R109 review #3).
        val glyphs = op.text.take(64).uppercase()
        require(glyphs.all { it == ' ' || font5x7.containsKey(it) }) { "text_draw 暂不支持该字符（仅限 ASCII 可打印字符），中文请用带字体的渲染方案" }
        for (ci in glyphs.indices) {
            val glyph = font5x7[glyphs[ci]] ?: fontBlock
            val gx0 = left + ci * (cell + gap)
            for (gy in 0 until 7) {
                val row = glyph[gy]
                for (gx in 0 until 5) {
                    if (((row shr (4 - gx)) and 1) == 0) continue
                    for (oy in 0 until scale) for (ox in 0 until scale) {
                        val vx = gx0 + gx * scale + ox
                        val vy = top + gy * scale + oy
                        val rx = cx + (vx - cx) * cr - (vy - cy) * sr
                        val ry = cy + (vx - cx) * sr + (vy - cy) * cr
                        val ix = rx.roundToInt(); val iy = ry.roundToInt()
                        if (ix in 0 until w && iy in 0 until h) {
                            val i = iy * w + ix
                            val c = out[i]
                            // Source-over compositing (review #2): the old blend kept
                            // the destination alpha, so text on transparent pixels
                            // stayed invisible. Mix destination color weighted by its
                            // alpha, include the pen color's own alpha, and derive the
                            // output alpha so text shows on any base.
                            val da = channel(c, 24) / 255f
                            val outA = alpha + da * (1 - alpha)
                            val mixR = if (outA <= 0f) 0f else (tr * alpha + channel(c, 16) * da * (1 - alpha)) / outA
                            val mixG = if (outA <= 0f) 0f else (tg * alpha + channel(c, 8) * da * (1 - alpha)) / outA
                            val mixB = if (outA <= 0f) 0f else (tb * alpha + channel(c, 0) * da * (1 - alpha)) / outA
                            out[i] = pack(outA * 255f, mixR, mixG, mixB)
                        }
                    }
                }
            }
        }
        return bitmapOf(out, w, h)
    }

    private fun color(b: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        // Apply the ColorMatrix manually: Robolectric's shadow Paint/ColorFilter
        // does not perform real pixel math (unit tests would compare stale pixels).
        val m = matrix.array
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = c ushr 16 and 255; val g = c ushr 8 and 255; val bl = c and 255; val a = c ushr 24 and 255
            // Standard 4x5 ColorMatrix: out = M[off]*R + M[off+1]*G + M[off+2]*B + M[off+3]*A + M[off+4] (offset already in 0..255; multiplying again clamps everything to white/black).
            fun row(off: Int) = (m[off] * r + m[off + 1] * g + m[off + 2] * bl + m[off + 3] * a + m[off + 4]).roundToInt().coerceIn(0, 255)
            val nr = row(0); val ng = row(5); val nb = row(10)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        return out
    }
    private fun convolve(b: Bitmap, strength: Float): Bitmap {
        if (strength <= 0) return b
        val w = b.width; val h = b.height; val src = IntArray(w*h); val dst = IntArray(w*h)
        b.getPixels(src, 0, w, 0, 0, w, h)
        fun channel(v: Int, shift: Int) = v ushr shift and 255
        for (y in 0 until h) for (x in 0 until w) {
            val i = y*w+x; val c = src[i]; val near = intArrayOf(src[y*w+max(0,x-1)], src[y*w+min(w-1,x+1)], src[max(0,y-1)*w+x], src[min(h-1,y+1)*w+x])
            fun sharpen(shift: Int) = (channel(c,shift)*(1+4*strength)-near.sumOf { channel(it,shift) }*strength).roundToInt().coerceIn(0,255)
            dst[i] = (c and -0x1000000) or (sharpen(16) shl 16) or (sharpen(8) shl 8) or sharpen(0)
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }
    private fun border(b: Bitmap, op: ImageOp.Border): Bitmap {
        val pad = op.widthPx; require(pad in 0..512) { "边框宽度须为 0..512" }
        val bottom = if (op.style == "polaroid") pad * 3 else pad
        checkSize(b.width + 2*pad, b.height + pad + bottom)
        return Bitmap.createBitmap(b.width+2*pad, b.height+pad+bottom, Bitmap.Config.ARGB_8888).also { out ->
            val c = Canvas(out); c.drawColor(if (op.style == "polaroid") Color.WHITE else op.color)
            if (op.style == "rounded") {
                val path = Path().apply { addRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad+b.width).toFloat(), (pad+b.height).toFloat()), pad.toFloat(), pad.toFloat(), Path.Direction.CW) }
                c.save(); c.clipPath(path); c.drawBitmap(b, pad.toFloat(), pad.toFloat(), null); c.restore()
            } else c.drawBitmap(b, pad.toFloat(), pad.toFloat(), null)
        }
    }
    fun thumbnail(b: Bitmap, edge: Int = 640): String {
        val s = min(1f, edge.toFloat()/max(b.width,b.height)); val small = if (s < 1f) Bitmap.createScaledBitmap(b, max(1,(b.width*s).roundToInt()), max(1,(b.height*s).roundToInt()), true) else b
        val bytes = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.PNG, 90, bytes); if (small !== b) small.recycle()
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }
    /**
     * 是否存在真实透明像素。Bitmap.createBitmap 出来的 ARGB_8888 一律 hasAlpha()=true
     * （哪怕全不透明），不能用它决定 JPEG/PNG，否则透明图编辑后存成 JPEG 会变黑底。
     */
    internal fun reallyHasAlpha(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        for (p in pixels) if (p ushr 24 and 255 != 255) return true
        return false
    }
    fun save(context: Context, bitmap: Bitmap, png: Boolean): String {
        val ext = if (png) "png" else "jpg"; val name = "andagent_edit_${System.currentTimeMillis()}.$ext"
        val bytes = ByteArrayOutputStream(); bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, bytes)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/${if (png) "png" else "jpeg"}"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AndAgent"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            val resolver = context.contentResolver; val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("相册保存失败")
            try { resolver.openOutputStream(uri)?.use { bytes.writeTo(it) } ?: error("无法写入图片"); values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); resolver.update(uri, values, null, null) }
            catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            return uri.toString()
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AndAgent"); require(dir.exists() || dir.mkdirs()) { "无法创建相册目录" }
        val file = File(dir, name); file.outputStream().use { bytes.writeTo(it) }
        context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)))
        return file.absolutePath
    }
    fun collage(images: List<Bitmap>, layout: String, slots: List<Rect>?, spacing: Int, background: Int, corners: Int): Bitmap {
        require(images.isNotEmpty() && images.size <= 16) { "拼图需要 1..16 张图片" }
        require(collageInputAllowed(totalPixels(images))) { "拼图输入图片总像素过大（最多 32MP），请先缩小或分批拼图" }
        require(spacing in 0..100 && corners in 0..100) { "间距或圆角无效" }
        val cells = if (layout == "free") { require(slots != null && slots.size == images.size) { "free 布局需要每张图片的 x/y/w/h" }; slots }
            else {
                val cols = when (layout) { "hstack" -> images.size; "vstack" -> 1; "grid2x2" -> 2; else -> error("未知拼图布局") }
                val rows = (images.size + cols - 1)/cols; val cellW = min(640, MAX_EDGE / cols); val cellH = min(640, MAX_EDGE / rows)
                images.indices.map { i -> Rect(spacing+(i%cols)*(cellW+spacing), spacing+(i/cols)*(cellH+spacing), spacing+(i%cols)*(cellW+spacing)+cellW, spacing+(i/cols)*(cellH+spacing)+cellH) }
            }
        val width = cells.maxOf { it.right } + spacing; val height = cells.maxOf { it.bottom } + spacing; checkSize(width,height)
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { out ->
            val canvas = Canvas(out); canvas.drawColor(background)
            images.forEachIndexed { i, b -> val r = cells[i]; require(r.left >= 0 && r.top >= 0 && r.width() > 0 && r.height() > 0) { "拼图位置无效" }
                canvas.save(); if (corners > 0) canvas.clipPath(Path().apply { addRoundRect(RectF(r), corners.toFloat(), corners.toFloat(), Path.Direction.CW) })
                val scale = max(r.width().toFloat()/b.width, r.height().toFloat()/b.height); val sw = r.width()/scale; val sh = r.height()/scale
                val src = RectF((b.width-sw)/2,(b.height-sh)/2,(b.width+sw)/2,(b.height+sh)/2)
                canvas.drawBitmap(b, Rect(src.left.toInt(),src.top.toInt(),src.right.toInt(),src.bottom.toInt()), r, Paint(Paint.FILTER_BITMAP_FLAG)); canvas.restore()
            }
        }
    }
}

private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.i(key: String) = s(key)?.toIntOrNull()
private fun JsonObject.f(key: String) = s(key)?.toFloatOrNull()
private fun JsonObject.flag(key: String, default: Boolean = false) = s(key)?.toBooleanStrictOrNull() ?: default
private fun parseColor(value: String?): Int = try { Color.parseColor(value ?: "#ffffff") } catch (_: Exception) { error("颜色格式无效") }
internal fun parseOps(json: String): List<ImageOp> {
    val array = Json.parseToJsonElement(json) as? JsonArray ?: error("ops_json 应为 JSON 数组")
    require(array.size <= 32) { "最多 32 个编辑步骤" }
    return array.map { element -> val o = element as? JsonObject ?: error("每个编辑步骤须为对象")
        when (o.s("op")?.lowercase()) {
            "crop" -> ImageOp.Crop(o.i("x"),o.i("y"),o.i("w") ?: o.i("width"),o.i("h") ?: o.i("height"),o.s("aspect"),o.s("anchor") ?: "center")
            "rotate" -> ImageOp.Rotate(o.f("degrees") ?: error("缺少 degrees"))
            "fliph", "flip_h" -> ImageOp.FlipH
            "flipv", "flip_v" -> ImageOp.FlipV
            "resize" -> ImageOp.Resize(o.i("width"),o.i("height"),o.flag("keepAspect",true))
            "brightness" -> ImageOp.Brightness((o.i("value") ?: 0).coerceIn(-100,100))
            "contrast" -> ImageOp.Contrast((o.i("value") ?: 0).coerceIn(-100,100))
            "saturation" -> ImageOp.Saturation((o.i("value") ?: 0).coerceIn(-100,100))
            "sharpness" -> ImageOp.Sharpness((o.i("value") ?: 0).coerceIn(0,100))
            "blur" -> ImageOp.Blur((o.i("radius") ?: 0).coerceIn(0,100))
            "grayscale" -> ImageOp.Grayscale
            "sepia" -> ImageOp.Sepia
            "invert" -> ImageOp.Invert
            "border" -> ImageOp.Border(o.i("widthPx") ?: o.i("width") ?: 12, parseColor(o.s("color")), o.s("style") ?: "solid")
            "watermark" -> ImageOp.Watermark(o.s("text") ?: error("缺少水印文字"),o.s("position") ?: "bottom-right",o.i("alpha") ?: 180)
            "composeover", "compose_over" -> ImageOp.ComposeOver(o.s("source") ?: o.s("overlayId") ?: error("缺少贴图源"),o.i("x") ?: 0,o.i("y") ?: 0,o.i("alpha") ?: 255)
            "lut" -> ImageOp.Lut(o.s("preset"),o.s("cube"),(o.f("strength") ?: 1f).coerceIn(0f,1f))
            "flip" -> ImageOp.Flip(o.s("axis") ?: "horizontal")
            "rotate_free", "rotatefree" -> ImageOp.RotateFree(o.f("degrees") ?: error("缺少 degrees"), parseColor(o.s("color") ?: o.s("background") ?: "#ffffffff"))
            "text_draw", "textdraw" -> run { val t = o.s("text") ?: error("缺少文字"); require(t.length <= 64) { "文字最长 64 字符" }; require(t.all { it.code in 32..126 }) { "text_draw 暂不支持该字符（仅限 ASCII 可打印字符），中文请用带字体的渲染方案" }; val rot = (o.f("rotation") ?: o.f("angle") ?: 0f); require(rot.isFinite()) { "rotation 无效" }; ImageOp.TextDraw(t, (o.i("sizePx") ?: o.i("size") ?: 48).coerceIn(1,512), parseColor(o.s("color") ?: "#ffffffff"), (o.i("alpha") ?: 255).coerceIn(0,255), o.i("x"), o.i("y"), o.s("anchor") ?: "center", rot) }
            "gaussian_blur", "gaussianblur", "blur_region" -> ImageOp.GaussianBlur((o.i("radius") ?: 4).coerceIn(1,15), o.i("x"), o.i("y"), o.i("w") ?: o.i("width"), o.i("h") ?: o.i("height"))
            "vignette" -> ImageOp.Vignette((o.f("strength") ?: 0.5f).coerceIn(0f,1f), parseColor(o.s("color") ?: "#ff000000"))
            "auto_enhance", "autoenhance" -> ImageOp.AutoEnhance
            else -> error("未知图片操作：${o.s("op")}")
        }
    }
}

class ImageEditTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "image_edit" -> "按顺序编辑图片并保存到 Pictures/AndAgent。缩略图由工具卡片展示，savedPath 可作为下一轮 source。"
        "image_preview" -> "预览图片编辑效果，不保存；缩略图由工具卡片展示。"
        "image_info" -> "读取图片宽高、格式、字节数、EXIF 方向；编辑时自动转正。"
        else -> "把 1..16 张图片拼成一张并保存，可选 grid2x2、hstack、vstack 或 free 布局；缩略图由工具卡片展示。"
    }
    override val parameters = when (name) {
        "image_info" -> schemaOf("source" to strParam("content:// URI、相册 id、本地路径或 base64"), required=listOf("source"))
        "image_collage" -> schemaOf("ids_or_uris" to strParam("图片源 JSON 数组"),"layout" to strParam("grid2x2 | hstack | vstack | free"),"output" to strParam("可选：png 或 jpeg"),"slots_json" to strParam("free 布局每张图片的 {x,y,w,h} JSON 数组"),"spacing" to intParam("像素间距"),"background" to strParam("背景颜色 #RRGGBB"),"corners" to intParam("圆角像素"),required=listOf("ids_or_uris","layout"))
        else -> schemaOf("source" to strParam("content:// URI、相册 id、本地路径或 base64；上次 savedPath 可直接用"),"ops_json" to strParam("操作 JSON 数组。原有：crop/rotate/flip_h/flip_v/resize/brightness/contrast/saturation/sharpness/blur/grayscale/sepia/invert/border/watermark/compose_over/lut。R109 新增：flip{axis:h|v|both}、rotate_free{degrees,color}、text_draw{text,sizePx,color,alpha,anchor 九宫格或 x/y,rotation}、gaussian_blur{radius,x,y,w,h 区域}、vignette{strength,color}、auto_enhance。示例 [{\"op\":\"crop\",\"aspect\":\"16:9\"},{\"op\":\"vignette\",\"strength\":0.6}]"),required=listOf("source"))
    }
    override suspend fun execute(args: JsonObject): String = runCatching {
        if (name == "image_info") {
            val info = ImageEditEngine.info(context, args.s("source") ?: error("缺少 source"))
            return@runCatching buildJsonObject { put("width",info.width);put("height",info.height);put("format",info.format);put("sizeBytes",info.sizeBytes);put("exifOrientation",info.exifOrientation) }.toString()
        }
        if (name == "image_collage") {
            val sources = Json.parseToJsonElement(args.s("ids_or_uris") ?: error("缺少 ids_or_uris")) as? JsonArray ?: error("图片源须为 JSON 数组")
            // Bound before decoding (review #5): cap source count and running pixel
            // total so oversized batches fail fast instead of exhausting memory.
            require(sources.size in 1..16) { "拼图数量须为 1..16 张" }
            var decoded = 0L
            val images = sources.map { raw ->
                val b = ImageEditEngine.decode(context, raw.jsonPrimitive.content)
                decoded += b.width.toLong() * b.height.toLong()
                require(ImageEditEngine.collageInputAllowed(decoded)) { "拼图输入总像素过大（上限 ${ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS / 1_000_000}MP）" }
                b
            }
            try {
                val slots = args.s("slots_json")?.let { (Json.parseToJsonElement(it) as JsonArray).map { e -> val o=e.jsonObject; val x=o.i("x")?:0; val y=o.i("y")?:0; Rect(x,y,x+(o.i("w")?:0),y+(o.i("h")?:0)) } }
                val b = ImageEditEngine.collage(images,args.s("layout") ?: "grid2x2",slots,args.i("spacing")?:8,parseColor(args.s("background") ?: "#ffffff"),args.i("corners")?:0)
                try { return@runCatching result(context,b,null,true,args.s("output") == "png" || ImageEditEngine.reallyHasAlpha(b)) } finally { b.recycle() }
            } finally { images.forEach { it.recycle() } }
        }
        val source = args.s("source") ?: error("缺少 source")
        val ops = parseOps(args.s("ops_json") ?: "[]")
        val original = ImageEditEngine.decode(context,source)
        val before = ImageEditEngine.thumbnail(original)
        val edited = ImageEditEngine.apply(original,ops) { ImageEditEngine.decode(context,it) }
        try { result(context,edited,before,name == "image_edit", ImageEditEngine.reallyHasAlpha(edited)) }
        finally { edited.recycle() }
    }.getOrElse { "ERROR: 图片处理失败：${it.message ?: it.javaClass.simpleName}" }
    private fun result(context: Context, bitmap: Bitmap, before: String?, save: Boolean, png: Boolean): String {
        // Keep a single copy of the thumbnail: previewFence used to duplicate it
        // (and the "before" image) inside a fenced JSON string, tripling the
        // tool result. The UI renders thumbnailBase64 directly now.
        val thumbnail = ImageEditEngine.thumbnail(bitmap)
        val path = if (save) ImageEditEngine.save(context,bitmap,png) else null
        return buildJsonObject {
            if (path != null) put("savedPath",path) else put("savedPath",JsonNull)
            put("thumbnailBase64",thumbnail);put("thumbnailMime","image/png")
            if (before != null) put("sourceThumbnailBase64",before)
            put("width",bitmap.width);put("height",bitmap.height)
        }.toString()
    }
}

package com.dc.andagent.tools

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEditEngineTest {
    private fun sample(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.rgb(60, 100, 140))
    }

    @Test fun cropAndRotateDimensions() {
        val cropped = ImageEditEngine.apply(sample(), listOf(ImageOp.Crop(10, 20, 60, 40, null, "center")))
        assertEquals(60, cropped.width)
        assertEquals(40, cropped.height)
        val rotated = ImageEditEngine.apply(cropped, listOf(ImageOp.Rotate(90f)))
        assertEquals(40, rotated.width)
        assertEquals(60, rotated.height)
        rotated.recycle()
    }

    @Test fun brightnessRaisesPixelValue() {
        val source = sample()
        val original = Color.red(source.getPixel(50, 50))
        val brighter = ImageEditEngine.apply(source, listOf(ImageOp.Brightness(30)))
        assertTrue(Color.red(brighter.getPixel(50, 50)) > original)
        brighter.recycle()
    }

    @Test fun grayscaleMakesChannelsEqual() {
        val gray = ImageEditEngine.apply(sample(), listOf(ImageOp.Grayscale))
        val pixel = gray.getPixel(50, 50)
        assertEquals(Color.red(pixel), Color.green(pixel))
        assertEquals(Color.green(pixel), Color.blue(pixel))
        gray.recycle()
    }

    private fun pngBase64(w: Int, h: Int): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.rgb(60, 100, 140))
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, bos)
        b.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    // R109 §A-1: image_info must report the decode() edit coordinate system
    // (MAX_EDGE downsampling + EXIF 90/270 width/height swap), not raw stored size.
    @Test fun effectiveDimsDownsampleAndExifSwap() {
        assertEquals(100 to 50, ImageEditEngine.effectiveDims(100, 50, 1))
        // 4032x3024 @ orientation 6 (rotate 90) -> sampled 2016x1512, then swapped.
        assertEquals(1512 to 2016, ImageEditEngine.effectiveDims(4032, 3024, 6))
        // orientation 3 (rotate 180) keeps the sampled dimensions unswapped.
        assertEquals(2016 to 1512, ImageEditEngine.effectiveDims(4032, 3024, 3))
        // orientation 8 (rotate 270) also swaps.
        assertEquals(1000 to 2000, ImageEditEngine.effectiveDims(4000, 2000, 8))
    }

    @Test fun infoReportsEditCoordinateSize() {
        val info = ImageEditEngine.info(RuntimeEnvironment.getApplication(), pngBase64(3000, 10))
        assertEquals(1500, info.width)
        assertEquals(5, info.height)
        assertEquals(1, info.exifOrientation)
        assertTrue(info.sizeBytes > 0)
    }

    // R109 §A-2: collage bounds the summed input pixels held in memory at once.
    @Test fun collageInputBudgetGuard() {
        val imgs = List(3) { sample() }
        assertEquals(30000L, ImageEditEngine.totalPixels(imgs))
        assertTrue(ImageEditEngine.collageInputAllowed(ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS))
        assertFalse(ImageEditEngine.collageInputAllowed(ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS + 1))
        val out = ImageEditEngine.collage(imgs, "hstack", null, 4, Color.WHITE, 0)
        assertTrue(out.width > 0 && out.height > 0)
        out.recycle()
        imgs.forEach { it.recycle() }
    }

    // R109 §A-5: lowercase DOMAIN_ keywords are skipped (data rows fall through
    // the `?: continue` guard) and a valid identity LUT round-trips a pixel.
    @Test fun lutLowercaseDomainAndIdentityRoundTrip() {
        val cube = """
            TITLE "identity"
            LUT_3D_SIZE 2
            domain_min 0.0 0.0 0.0
            domain_max 1.0 1.0 1.0
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val src = sample()
        val original = src.getPixel(50, 50)
        val out = ImageEditEngine.apply(src, listOf(ImageOp.Lut(null, cube, 1f)))
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        val edited = out.getPixel(50, 50)
        assertTrue(kotlin.math.abs(Color.red(original) - Color.red(edited)) <= 2)
        assertTrue(kotlin.math.abs(Color.green(original) - Color.green(edited)) <= 2)
        assertTrue(kotlin.math.abs(Color.blue(original) - Color.blue(edited)) <= 2)
        out.recycle()
    }

    // ---------------------- R109 §B new ops ----------------------

    private fun quad(): Bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).also { b ->
        val p = IntArray(100 * 80)
        for (y in 0 until 80) for (x in 0 until 100) {
            p[y * 100 + x] = when {
                x < 50 && y < 40 -> Color.RED
                x >= 50 && y < 40 -> Color.GREEN
                x < 50 -> Color.BLUE
                else -> Color.YELLOW
            }
        }
        b.setPixels(p, 0, 100, 0, 0, 100, 80)
    }
    private fun quadPixels(): IntArray = quad().let { b ->
        val p = IntArray(100 * 80); b.getPixels(p, 0, 100, 0, 0, 100, 80); b.recycle(); p
    }

    @Test fun flipMirrorsPixels() {
        val ref = quadPixels()
        val h = ImageEditEngine.apply(quad(), listOf(ImageOp.Flip("h")))
        assertEquals(ref[99], h.getPixel(0, 0))            // (0,0) <- (99,0)
        assertEquals(ref[79 * 100 + 99], h.getPixel(0, 79))
        h.recycle()
        val v = ImageEditEngine.apply(quad(), listOf(ImageOp.Flip("v")))
        assertEquals(ref[79 * 100], v.getPixel(0, 0))      // (0,0) <- (0,79)
        v.recycle()
        val both = ImageEditEngine.apply(quad(), listOf(ImageOp.Flip("both")))
        assertEquals(ref[79 * 100 + 99], both.getPixel(0, 0))
        both.recycle()
    }

    @Test fun rotateFreePixelsAndBackground() {
        val ref = quadPixels()
        val r180 = ImageEditEngine.apply(quad(), listOf(ImageOp.RotateFree(180f, Color.WHITE)))
        assertEquals(100, r180.width); assertEquals(80, r180.height)
        assertEquals(ref[79 * 100 + 99], r180.getPixel(0, 0)) // 180° swaps diagonals
        r180.recycle()
        val r45 = ImageEditEngine.apply(quad(), listOf(ImageOp.RotateFree(45f, Color.MAGENTA)))
        assertTrue(r45.width > 100 && r45.height > 80)
        assertEquals(Color.MAGENTA, r45.getPixel(0, 0))       // exposed corner = background fill
        r45.recycle()
    }

    private fun redVariance(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val v = Color.red(bmp.getPixel(x, y)).toDouble()
            sum += v; sum2 += v * v; n++
        }
        val m = sum / n
        return sum2 / n - m * m
    }

    @Test fun gaussianBlurRegionOnly() {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val ref = IntArray(100 * 100)
        for (y in 0 until 100) for (x in 0 until 100) {
            val c = if (x % 2 == 0) Color.BLACK else Color.WHITE
            b.setPixel(x, y, c); ref[y * 100 + x] = c
        }
        val before = redVariance(b, 20, 20, 80, 80)
        val out = ImageEditEngine.apply(b, listOf(ImageOp.GaussianBlur(4, 20, 20, 60, 60)))
        val after = redVariance(out, 20, 20, 80, 80)
        assertTrue("region variance should drop (before=$before after=$after)", after < before)
        // outside the rect the pixels must be byte-for-byte unchanged
        assertEquals(ref[0], out.getPixel(0, 0))
        assertEquals(ref[99 * 100 + 99], out.getPixel(99, 99))
        out.recycle()
    }

    @Test fun vignetteDarkensCorners() {
        val out = ImageEditEngine.apply(
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) },
            listOf(ImageOp.Vignette(1f, Color.BLACK)))
        val center = Color.red(out.getPixel(50, 50))
        val corner = Color.red(out.getPixel(0, 0))
        assertTrue("center should stay bright ($center)", center > 240)
        assertTrue("corner should be dark ($corner)", corner < 30)
        assertTrue(center > corner)
        out.recycle()
    }

    @Test fun autoEnhanceWidensDynamicRange() {
        val b = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) b.setPixel(x, y, Color.rgb(100 + x % 11, 100, 100))
        val out = ImageEditEngine.apply(b, listOf(ImageOp.AutoEnhance))
        var min = 255; var max = 0
        for (y in 0 until 100) for (x in 0 until 100) {
            val r = Color.red(out.getPixel(x, y)); min = minOf(min, r); max = maxOf(max, r)
        }
        assertTrue("contrast stretch should widen span from 10 to >100 (got ${max - min})", max - min > 100)
        out.recycle()
    }

    @Test fun textDrawAnchorGridAndOverride() {
        val (w, h, tw, th, m) = listOf(200f, 100f, 40f, 20f, 10f)
        val tl = ImageEditEngine.anchorPoint("top-left", w.toInt(), h.toInt(), tw, th, m, null, null)
        val c = ImageEditEngine.anchorPoint("center", w.toInt(), h.toInt(), tw, th, m, null, null)
        val br = ImageEditEngine.anchorPoint("bottom-right", w.toInt(), h.toInt(), tw, th, m, null, null)
        assertTrue(tl.first < c.first && c.first < br.first)
        assertTrue(tl.second < br.second)
        val ex = ImageEditEngine.anchorPoint("top-left", w.toInt(), h.toInt(), tw, th, m, 50, 60)
        assertEquals(50f, ex.first); assertEquals(60f, ex.second)
        val cl = ImageEditEngine.anchorPoint("top-left", w.toInt(), h.toInt(), tw, th, m, -100, -100)
        assertEquals(0f, cl.first); assertTrue(cl.second >= th)
    }

    @Test fun textDrawWritesTextColor() {
        val src = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        val out = ImageEditEngine.apply(src, listOf(ImageOp.TextDraw("IIIII", 16, Color.WHITE, 255, null, null, "center", 0f)))
        assertEquals(160, out.width)
        var bright = 0
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (Color.red(out.getPixel(x, y)) > 128) bright++
        }
        assertTrue("drawText produced no bright pixels (Robolectric rasterization unavailable?)", bright > 0)
        out.recycle()
    }

    @Test fun parseOpsRecognizesR109Ops() {
        val ops = parseOps("[{\"op\":\"flip\",\"axis\":\"v\"},{\"op\":\"rotate_free\",\"degrees\":30,\"color\":\"#ff112233\"},{\"op\":\"text_draw\",\"text\":\"hi\",\"sizePx\":24},{\"op\":\"gaussian_blur\",\"radius\":5,\"x\":1,\"y\":2,\"w\":10,\"h\":10},{\"op\":\"vignette\",\"strength\":0.7},{\"op\":\"auto_enhance\"}]")
        assertEquals(6, ops.size)
        assertEquals("v", (ops[0] as ImageOp.Flip).axis)
        assertEquals(30f, (ops[1] as ImageOp.RotateFree).degrees, 0.001f)
        assertEquals("hi", (ops[2] as ImageOp.TextDraw).text)
        assertEquals(10, (ops[3] as ImageOp.GaussianBlur).w)
        assertTrue(ops[4] is ImageOp.Vignette)
        assertTrue(ops[5] is ImageOp.AutoEnhance)
    }

    // R109 review follow-ups (main-controller fixes):
    @Test fun autoEnhanceKeepsFlatColorImages() {
        val src = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.rgb(100, 150, 200))
        val out = ImageEditEngine.apply(src, listOf(ImageOp.AutoEnhance))
        assertEquals(Color.rgb(100, 150, 200), out.getPixel(25, 25))
        out.recycle()
    }

    @Test fun parseOpsRejectsNonAsciiAndOverlongTextDraw() {
        val ex1 = runCatching { parseOps("[{\"op\":\"text_draw\",\"text\":\"你好\"}]") }
        assertTrue(ex1.isFailure)
        val long = "a".repeat(65)
        val ex2 = runCatching { parseOps("[{\"op\":\"text_draw\",\"text\":\"$long\"}]") }
        assertTrue(ex2.isFailure)
        // JSON cannot express NaN/Infinity numerically; "nan" text falls back to
        // the 0f default via toFloatOrNull, and the engine still guards isFinite.
    }

    @Test fun textDrawVisibleOnTransparentBase() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888) // fully transparent
        val out = ImageEditEngine.apply(src, listOf(ImageOp.TextDraw("TEST", 48, Color.WHITE, 255, null, null, "center", 0f)))
        var lit = 0
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (out.getPixel(x, y) != 0) lit++
        }
        assertTrue("text on transparent base produced no visible pixels", lit > 0)
        out.recycle()
    }

    @Test fun rotateFreeTransparentCornersHaveNoDarkFringe() {
        // Opaque white center on a transparent base: bilinear edge pixels must not
        // darken toward black (premultiplied interpolation).
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val white = Color.WHITE
        for (y in 40 until 60) for (x in 40 until 60) src.setPixel(x, y, white)
        val out = ImageEditEngine.apply(src, listOf(ImageOp.RotateFree(10f, 0x00000000)))
        var minA = 255
        var fringe = false
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val p = out.getPixel(x, y)
            val a = Color.alpha(p)
            if (a in 1..254 && Color.red(p) < a - 24 && a > 40) fringe = true // RGB far below alpha => dark bleed
            if (a < minA) minA = a
        }
        assertFalse("dark fringe on rotated transparent edges", fringe)
        out.recycle()
    }

    @Test fun parseOpsCapsStepsAt32Still() {
        val ops = parseOps("[" + (1..32).joinToString(",") { "{\"op\":\"invert\"}" } + "]")
        assertEquals(32, ops.size)
        val ex = runCatching { parseOps("[" + (1..33).joinToString(",") { "{\"op\":\"invert\"}" } + "]") }
        assertTrue(ex.isFailure)
    }
}

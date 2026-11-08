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
}

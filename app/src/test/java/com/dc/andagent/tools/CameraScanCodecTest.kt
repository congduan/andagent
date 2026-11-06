package com.dc.andagent.tools

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R72: JVM tests for the pure camera-scan codec helpers (no Android hardware). */
class CameraScanCodecTest {

    private fun plane(width: Int, height: Int, rowStride: Int = width, fill: (Int) -> Byte): CameraScanCodec.YPlane =
        CameraScanCodec.YPlane(ByteArray(rowStride * height) { i -> fill(i) }, width, height, rowStride)

    @Test
    fun `luma copies contiguous buffer unchanged`() {
        val w = 4
        val h = 3
        val p = plane(w, h) { i -> (i % 251).toByte() }
        val out = CameraScanCodec.luma(p)
        assertEquals(w * h, out.size)
        for (row in 0 until h) {
            for (col in 0 until w) {
                assertEquals(p.data[row * w + col], out[row * w + col])
            }
        }
    }

    @Test
    fun `luma handles padded rowStride`() {
        val w = 4
        val h = 2
        val stride = 8
        val p = plane(w, h, stride) { i -> ((i % stride) * 8).toByte() }
        val out = CameraScanCodec.luma(p)
        assertEquals(w * h, out.size)
        // Row 0 picks cols 0..3 of the padded row; row 1 picks cols 8..11.
        for (col in 0 until w) {
            assertEquals(p.data[col], out[col])
            assertEquals(p.data[stride + col], out[w + col])
        }
    }

    @Test
    fun `zxing decodes a synthetic QR from grayscale source`() {
        // Generate a QR matrix via zxing itself, expand to an 8x-scaled grayscale image,
        // then decode through GrayLuminanceSource — proves the source is ZXing-compatible.
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            "ANDAGENT-R72", BarcodeFormat.QR_CODE, 0, 0,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
        )
        val scale = 8
        val quiet = 4
        val dim = matrix.width + quiet * 2
        val imgW = dim * scale
        val imgH = dim * scale
        val luma = ByteArray(imgW * imgH) { 0xFF.toByte() } // white background (QR is dark-on-light)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    for (sy in 0 until scale) {
                        for (sx in 0 until scale) {
                            val px = (x + quiet) * scale + sx
                            val py = (y + quiet) * scale + sy
                            luma[py * imgW + px] = 0x00.toByte() // dark modules
                        }
                    }
                }
            }
        }
        val source = GrayLuminanceSource(luma, imgW, imgH)
        assertEquals(imgW, source.width)
        assertEquals(imgH, source.height)
        val res = com.google.zxing.MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true),
        )
        assertEquals("ANDAGENT-R72", res.text)
    }

    @Test
    fun `row accessor returns one row`() {
        val w = 3
        val h = 2
        val p = plane(w, h) { i -> (i * 10).toByte() }
        val src = CameraScanCodec.toZxingSource(p)
        val row = src.getRow(1, null)
        assertEquals(w, row.size)
        assertEquals(p.data[w + 0], row[0])
        assertEquals(p.data[w + 2], row[2])
    }

    @Test
    fun `rotate counter clockwise transposes dimensions`() {
        val w = 4
        val h = 2
        val p = plane(w, h) { 0x7F.toByte() }
        val rotated = CameraScanCodec.toZxingSource(p).rotateCounterClockwise()
        assertEquals(h, rotated.width)
        assertEquals(w, rotated.height)
    }

    @Test
    fun `parseFormats maps known names and drops unknown`() {
        assertEquals(emptyList<BarcodeFormat>(), CameraScanCodec.parseFormats(null))
        assertEquals(emptyList<BarcodeFormat>(), CameraScanCodec.parseFormats(""))
        assertEquals(emptyList<BarcodeFormat>(), CameraScanCodec.parseFormats("bogus,,xyz"))
        assertEquals(
            listOf(BarcodeFormat.QR_CODE, BarcodeFormat.EAN_13),
            CameraScanCodec.parseFormats("qr, EAN13"),
        )
        assertEquals(
            listOf(BarcodeFormat.QR_CODE, BarcodeFormat.CODE_128),
            CameraScanCodec.parseFormats("QR_CODE;code128"),
        )
        assertEquals(
            listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.ITF),
            CameraScanCodec.parseFormats("pdf417 aztec itf"),
        )
    }

    @Test
    fun `crop shifts window`() {
        val w = 6
        val h = 4
        val p = plane(w, h) { i -> (i and 0xFF).toByte() }
        val src = CameraScanCodec.toZxingSource(p) as GrayLuminanceSource
        val cropped = src.crop(2, 1, 3, 2)
        assertEquals(3, cropped.width)
        assertEquals(2, cropped.height)
        val matrix = cropped.matrix
        assertEquals(p.data[1 * w + 2], matrix[0])
        assertEquals(p.data[2 * w + 2], matrix[3])
    }

    @Test
    fun `decode failure on blank image returns NotFoundException not crash`() {
        val p = plane(8, 8) { 0x40.toByte() } // uniform gray: nothing to binarize into code
        val reader = com.google.zxing.MultiFormatReader()
        var thrown: Exception? = null
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(CameraScanCodec.toZxingSource(p))))
        } catch (e: Exception) {
            thrown = e
        } finally {
            reader.reset()
        }
        assertTrue(thrown is com.google.zxing.NotFoundException)
    }
}

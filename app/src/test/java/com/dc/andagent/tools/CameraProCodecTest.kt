package com.dc.andagent.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * R110: 相机手动/深度能力的纯函数校验。
 * 覆盖范围裁剪、变焦裁切对齐、物理镜头角色、深度统计/点云、连续帧亮度差分、
 * RAW10/12 解包，以及 PNG/DNG/PLY 三个写入器的字节结构。
 */
class CameraProCodecTest {

    /* ------------------------------ 手动曝光 ------------------------------ */

    @Test
    fun `iso and exposure are clamped into hardware range`() {
        assertEquals(100, CameraProCodec.clampIso(10, 100, 3200))
        assertEquals(3200, CameraProCodec.clampIso(9999, 100, 3200))
        assertEquals(800, CameraProCodec.clampIso(800, 100, 3200))
        assertEquals(1_000L, CameraProCodec.clampExposureNs(1L, 1_000L, 500_000_000L))
        assertEquals(500_000_000L, CameraProCodec.clampExposureNs(Long.MAX_VALUE, 1_000L, 500_000_000L))
    }

    @Test
    fun `ev converts to ae compensation steps with clamping`() {
        // 1/3 档步长：-1.5EV → -4 档（Math.round(-4.5) = -4）；0.5EV → 2 档
        assertEquals(-4, CameraProCodec.evSteps(-1.5f, 1f / 3f, -12, 12))
        assertEquals(2, CameraProCodec.evSteps(0.5f, 1f / 3f, -12, 12))
        assertEquals(12, CameraProCodec.evSteps(9f, 1f / 3f, -12, 12))
        // 非法步长退回 1/3，不抛异常
        assertEquals(3, CameraProCodec.evSteps(1f, 0f, -12, 12))
    }

    @Test
    fun `diopter and centimetre convert both ways`() {
        val d = CameraProCodec.distanceCmToDiopter(10f)!!
        assertEquals(10f, d, 1e-4f)
        assertEquals(10f, CameraProCodec.diopterToDistanceCm(10f)!!, 1e-4f)
        assertNull(CameraProCodec.distanceCmToDiopter(0f))
        assertNull(CameraProCodec.distanceCmToDiopter(Float.NaN))
        assertNull(CameraProCodec.diopterToDistanceCm(0f)) // 0 = 无穷远
    }

    @Test
    fun `zoom crop region is centred and bayer aligned`() {
        val full = CameraProCodec.zoomCropRegion(4000, 3000, 1f)
        assertEquals(CameraProCodec.CropRegion(0, 0, 4000, 3000), full)

        val half = CameraProCodec.zoomCropRegion(4000, 3000, 2f)
        assertEquals(2000, half.width)
        assertEquals(1500, half.height)
        assertEquals(1000, half.x)
        assertEquals(750, half.y)

        // 奇数尺寸/奇数偏移都要落在偶数列，避免 CFA 相位翻转
        val odd = CameraProCodec.zoomCropRegion(4001, 3001, 3.3f)
        assertEquals(0, odd.x % 2)
        assertEquals(0, odd.y % 2)
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
        // 非法 zoom 不放大也不抛
        assertEquals(4000, CameraProCodec.zoomCropRegion(4000, 3000, Float.NaN).width)
        assertEquals(4000, CameraProCodec.zoomCropRegion(4000, 3000, 0f).width)
    }

    /* ------------------------------ 物理镜头 ------------------------------ */

    @Test
    fun `lens roles distinguish ultrawide tele and macro`() {
        val candidates = listOf(
            CameraProCodec.LensCandidate("0", 4.3f, 0f),
            CameraProCodec.LensCandidate("2", 1.8f, 0f),
            CameraProCodec.LensCandidate("3", 6.0f, 0f),
            CameraProCodec.LensCandidate("4", 3.5f, 25f), // 4cm 最近对焦 → 微距
        )
        val roles = CameraProCodec.assignLensRoles(candidates)
        assertEquals("wide", roles[0])
        assertEquals("ultrawide", roles[1])
        assertEquals("tele", roles[2])
        assertEquals("macro", roles[3])
    }

    @Test
    fun `single lens and identical focals stay wide`() {
        assertEquals(
            listOf("wide"),
            CameraProCodec.assignLensRoles(listOf(CameraProCodec.LensCandidate("0", 4.3f, 0f))),
        )
        val same = listOf(
            CameraProCodec.LensCandidate("0", 4.3f, 0f),
            CameraProCodec.LensCandidate("1", 4.3f, 0f),
        )
        assertEquals(listOf("wide", "wide"), CameraProCodec.assignLensRoles(same))
        assertTrue(CameraProCodec.assignLensRoles(emptyList()).isEmpty())
    }

    /* ------------------------------ 深度图 ------------------------------ */

    @Test
    fun `depth stats ignore zero and report percentiles`() {
        val depth = IntArray(10).also {
            it[0] = 0
            it[1] = 0
            it[2] = 100
            it[3] = 200
            it[4] = 300
            it[5] = 400
            it[6] = 500
            it[7] = 600
            it[8] = 700
            it[9] = 800
        }
        val stats = CameraProCodec.depthStats(depth)
        assertEquals(10, stats.total)
        assertEquals(8, stats.valid)
        assertEquals(0.8, stats.coverage, 1e-6)
        assertEquals(100, stats.minMm)
        assertEquals(800, stats.maxMm)
        assertEquals(450.0, stats.meanMm, 1e-6)
        assertEquals(400, stats.medianMm)
        assertEquals(100, stats.p05Mm)
        assertEquals(800, stats.p95Mm)

        val empty = CameraProCodec.depthStats(IntArray(4))
        assertEquals(0, empty.valid)
        assertEquals(0.0, empty.coverage, 1e-6)
    }

    @Test
    fun `depth colormap marks invalid dark and gradients near to far`() {
        val rgb = CameraProCodec.colorizeDepth(intArrayOf(0, 500, 4000), 500, 4000)
        assertEquals(40, rgb[0].toInt() and 0xFF) // 无效像素深灰
        // 近处偏红、远处偏蓝
        val nearR = rgb[3].toInt() and 0xFF
        val nearB = rgb[5].toInt() and 0xFF
        val farR = rgb[6].toInt() and 0xFF
        val farB = rgb[8].toInt() and 0xFF
        assertTrue("near=$nearR/$nearB far=$farR/$farB", nearR > nearB && farB > farR)
    }

    @Test
    fun `point cloud projects depth with pinhole intrinsics`() {
        // 2x2 深度图，中心在 (1,1)，内参 fx=fy=100 → 1000mm 处像素偏移 1px 对应 10mm
        val depth = intArrayOf(1000, 1000, 1000, 0)
        val points = CameraProCodec.pointCloud(
            depth, 2, 2, fx = 100f, fy = 100f, cx = 1f, cy = 1f, stride = 1, maxPoints = 100,
        )
        assertEquals(9, points.size) // 3 个有效点
        assertEquals(-10f, points[0], 1e-3f) // x=(0-1)*1000/100
        assertEquals(-10f, points[1], 1e-3f) // y=(0-1)*1000/100
        assertEquals(1000f, points[2], 1e-3f) // z

        // maxPoints 抽稀生效
        val many = IntArray(64 * 64) { 800 }
        val thinned = CameraProCodec.pointCloud(many, 64, 64, 100f, 100f, 32f, 32f, 1, maxPoints = 100)
        assertTrue(thinned.size / 3 <= 400)
    }

    /* ---------------------------- 连续帧分析 ---------------------------- */

    @Test
    fun `luma grid averages blocks and honours row stride`() {
        val w = 4
        val h = 2
        val stride = 8
        val y = ByteArray(stride * h) { if (it % 2 == 0) 100 else 0 } // 每行填充干扰
        for (row in 0 until h) for (x in 0 until w) y[row * stride + x] = 50
        val grid = CameraProCodec.lumaGrid(y, w, h, stride, gridW = 2, gridH = 1)
        assertEquals(2, grid.size)
        assertEquals(50, grid[0])
        assertEquals(50, grid[1])
    }

    @Test
    fun `frame diff and motion level`() {
        val a = IntArray(4) { 10 }
        val b = IntArray(4) { 20 }
        assertEquals(10.0, CameraProCodec.meanAbsDiff(a, b), 1e-6)
        assertEquals(0.0, CameraProCodec.meanAbsDiff(a, a), 1e-6)
        assertEquals("static", CameraProCodec.motionLevel(0.2))
        assertEquals("low", CameraProCodec.motionLevel(2.0))
        assertEquals("medium", CameraProCodec.motionLevel(8.0))
        assertEquals("high", CameraProCodec.motionLevel(30.0))
    }

    @Test
    fun `fps range picks closest supported bracket`() {
        val ranges = listOf(
            intArrayOf(15, 30),
            intArrayOf(30, 30),
            intArrayOf(24, 24),
            intArrayOf(60, 60),
        )
        val picked = CameraProCodec.pickFpsRange(ranges, 30)!!
        assertEquals(30, picked[0])
        assertEquals(30, picked[1])
        // 20fps 只能落在 15-30 区间内（24-24 的下界高于目标）
        assertEquals(30, CameraProCodec.pickFpsRange(ranges, 20)!![1])
        assertEquals(15, CameraProCodec.pickFpsRange(ranges, 20)!![0])
        // 无匹配区间时退化为最近上界
        assertEquals(60, CameraProCodec.pickFpsRange(ranges, 55)!![1])
        assertNull(CameraProCodec.pickFpsRange(null, 30))
    }

    /* -------------------------------- RAW -------------------------------- */

    @Test
    fun `raw10 unpacks four pixels from five bytes`() {
        // 第 5 字节低 2 位依次归属 p0..p3（bit0-1 → p0, bit2-3 → p1, bit4-5 → p2, bit6-7 → p3）
        val bytes = byteArrayOf(
            0.toByte(), 0.toByte(), 0.toByte(), 1.toByte(), 0b1110_0100.toByte(),
        )
        val out = CameraProCodec.unpackRaw10(bytes, 4, 1, 5)
        assertArrayEquals(intArrayOf(0, 1, 2, 7), out)
        // 已知值的组合：0xAB<<2 | 0x3 = 0x2AF
        val bytes2 = byteArrayOf(0xAB.toByte(), 0, 0, 0, 0x03)
        assertEquals(0x2AF, CameraProCodec.unpackRaw10(bytes2, 4, 1, 5)[0])
    }

    @Test
    fun `raw12 unpacks two pixels from three bytes`() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56)
        val out = CameraProCodec.unpackRaw12(bytes, 2, 1, 3)
        assertEquals(0x124, out[0]) // 0x12<<4 | 0x4
        assertEquals(0x563, out[1]) // 0x56<<4 | 0x3
    }

    @Test
    fun `raw16 reads little endian with padded row stride`() {
        val w = 2
        val h = 2
        val stride = 8 // 2 padding pixels per row
        val buf = ByteBuffer.allocate(stride * h).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0, 0x0102)
        buf.putShort(2, 0x0304)
        buf.putShort(stride, 0x0506)
        buf.putShort(stride + 2, 0x0708)
        val out = CameraProCodec.readRaw16(buf, w, h, stride, 2)
        assertArrayEquals(intArrayOf(0x0102, 0x0304, 0x0506, 0x0708), out)
    }

    @Test
    fun `cfa pattern maps every arrangement`() {
        assertArrayEquals(intArrayOf(0, 1, 1, 2), CameraProCodec.cfaPattern(1)) // RGGB
        assertArrayEquals(intArrayOf(1, 0, 2, 1), CameraProCodec.cfaPattern(2)) // GRBG
        assertArrayEquals(intArrayOf(1, 2, 0, 1), CameraProCodec.cfaPattern(3)) // GBRG
        assertArrayEquals(intArrayOf(2, 1, 1, 0), CameraProCodec.cfaPattern(4)) // BGGR
        assertArrayEquals(intArrayOf(0, 1, 1, 2), CameraProCodec.cfaPattern(99))
    }

    @Test
    fun `raw preview halves resolution and normalises levels`() {
        val w = 4
        val h = 4
        val white = IntArray(w * h) { 1023 }
        val gray = CameraProCodec.rawPreviewGray(white, w, h, CameraProCodec.cfaPattern(1), 64, 1023)
        assertEquals(4, gray.size) // 2x2
        assertEquals(255.toByte(), gray[0])

        val black = CameraProCodec.rawPreviewGray(IntArray(w * h) { 64 }, w, h, CameraProCodec.cfaPattern(1), 64, 1023)
        assertEquals(0.toByte(), black[0])
    }

    @Test
    fun `invert3x3 handles identity and singular matrices`() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertArrayEquals(identity, CameraProCodec.invert3x3(identity)!!, 1e-6f)
        assertNull(CameraProCodec.invert3x3(floatArrayOf(1f, 2f, 3f, 2f, 4f, 6f, 1f, 1f, 1f)))
        assertNull(CameraProCodec.invert3x3(floatArrayOf(1f, 2f)))
        val scaled = CameraProCodec.invert3x3(floatArrayOf(2f, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 5f))!!
        assertEquals(0.5f, scaled[0], 1e-6f)
        assertEquals(0.25f, scaled[4], 1e-6f)
        assertEquals(0.2f, scaled[8], 1e-6f)
    }

    /* ------------------------------ PNG 写入器 ------------------------------ */

    private data class PngChunk(val type: String, val data: ByteArray)

    private fun parsePng(bytes: ByteArray): Pair<ByteArray, List<PngChunk>> {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertArrayEquals(signature, bytes.copyOfRange(0, 8))
        var pos = 8
        val chunks = mutableListOf<PngChunk>()
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(pos + 8, pos + 8 + len)
            val crc = CRC32().apply {
                update(bytes, pos + 4, 4 + len)
            }
            val stored = ByteBuffer.wrap(bytes, pos + 8 + len, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            assertEquals("chunk $type CRC", crc.value, stored)
            chunks.add(PngChunk(type, data))
            pos += 12 + len
            if (type == "IEND") break
        }
        val idat = ByteArrayOutputStream()
        chunks.filter { it.type == "IDAT" }.forEach { idat.write(it.data) }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray() to chunks
    }

    @Test
    fun `png gray16 round trips scanlines and header`() {
        val samples = intArrayOf(0, 1000, 65535, 42)
        val png = PngWriter.gray16(2, 2, samples)
        val (raw, chunks) = parsePng(png)
        val ihdr = chunks.first { it.type == "IHDR" }.data
        assertEquals(2, ByteBuffer.wrap(ihdr, 0, 4).int)
        assertEquals(2, ByteBuffer.wrap(ihdr, 4, 4).int)
        assertEquals(16, ihdr[8].toInt())
        assertEquals(0, ihdr[9].toInt()) // grayscale
        assertEquals(2 * 2 * 2 + 2, raw.size) // 每行 1 字节 filter
        // 第 1 行：[filter][0x00 0x00][0x03 0xE8]
        assertEquals(0, raw[0].toInt())
        assertEquals(0, raw[1].toInt())
        assertEquals(0, raw[2].toInt())
        assertEquals(0x03, raw[3].toInt() and 0xFF)
        assertEquals(0xE8, raw[4].toInt() and 0xFF)
        // 第 2 行：[filter][0xFF 0xFF][0x00 0x2A]
        assertEquals(0, raw[5].toInt())
        assertEquals(0xFF, raw[6].toInt() and 0xFF)
        assertEquals(0xFF, raw[7].toInt() and 0xFF)
        assertEquals(0x2A, raw[9].toInt() and 0xFF)
    }

    @Test
    fun `png rgb8 keeps pixel order`() {
        val rgb = byteArrayOf(1, 2, 3, 4, 5, 6)
        val (raw, chunks) = parsePng(PngWriter.rgb8(2, 1, rgb))
        val ihdr = chunks.first { it.type == "IHDR" }.data
        assertEquals(8, ihdr[8].toInt())
        assertEquals(2, ihdr[9].toInt()) // RGB
        assertEquals(7, raw.size)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6), raw)
    }

    @Test
    fun `png gray8 keeps pixel order`() {
        val gray = byteArrayOf(7, 8, 9, 10)
        val (raw, chunks) = parsePng(PngWriter.gray8(2, 2, gray))
        assertEquals(8, chunks.first { it.type == "IHDR" }.data[8].toInt())
        assertArrayEquals(byteArrayOf(0, 7, 8, 0, 9, 10), raw)
    }

    /* ------------------------------ DNG 写入器 ------------------------------ */

    private fun dngMeta(width: Int, height: Int) = DngWriter.Meta(
        width = width,
        height = height,
        cfaPattern = intArrayOf(0, 1, 1, 2),
        blackLevel = 64,
        whiteLevel = 1023,
        uniqueModel = "TestCam",
        colorMatrix = floatArrayOf(1.5f, -0.2f, 0f, -0.1f, 1.4f, 0f, 0f, 0f, 1.2f),
        iso = 200,
        exposureNs = 8_000_000L,
    )

    @Test
    fun `dng has tiff header and required tags`() {
        val samples = intArrayOf(100, 200, 300, 4000)
        val dng = DngWriter.write(samples, dngMeta(2, 2))
        assertEquals('I'.code.toByte(), dng[0])
        assertEquals('I'.code.toByte(), dng[1])
        assertEquals(42, DngWriter.read16(dng, 2))
        val tags = DngWriter.readTags(dng)
        // 基线 TIFF
        assertEquals(2, ByteBuffer.wrap(tags[256]).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(2, ByteBuffer.wrap(tags[257]).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(16, ByteBuffer.wrap(tags[258]).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertEquals(1, ByteBuffer.wrap(tags[259]).order(ByteOrder.LITTLE_ENDIAN).short.toInt()) // 未压缩
        assertEquals(
            32803,
            ByteBuffer.wrap(tags[262]).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF,
        ) // CFA
        assertEquals(1, ByteBuffer.wrap(tags[277]).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertEquals(64, ByteBuffer.wrap(tags[50714]).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertEquals(1023, ByteBuffer.wrap(tags[50717]).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertArrayEquals(byteArrayOf(0, 1, 1, 2), tags[33422])
        assertEquals("TestCam\u0000", String(tags[50708]!!, Charsets.US_ASCII))
        // DNGVersion 1.4
        assertArrayEquals(byteArrayOf(1, 4, 0, 0), tags[50706])
        // 色彩矩阵 9 个 SRATIONAL
        assertEquals(9 * 8, tags[50721]!!.size)
        // StripByteCounts = 宽*高*2，StripOffsets 指向真实数据且内容与 samples 一致
        assertEquals(
            (2 * 2 * 2).toLong(),
            ByteBuffer.wrap(tags[279]).order(ByteOrder.LITTLE_ENDIAN).int.toLong(),
        )
        val offset = ByteBuffer.wrap(tags[273]).order(ByteOrder.LITTLE_ENDIAN).int
        val stripBytes = dng.copyOfRange(offset, offset + 8)
        val expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { expected.putShort(it.toShort()) }
        assertArrayEquals(expected.array(), stripBytes)
    }

    @Test
    fun `dng omits color matrix when unavailable`() {
        val meta = dngMeta(1, 1).copy(colorMatrix = null, iso = 0, exposureNs = 0L)
        val tags = DngWriter.readTags(DngWriter.write(intArrayOf(500), meta))
        assertNull(tags[50721])
        assertNull(tags[50778])
        assertNull(tags[34855])
        // AsShotNeutral 始终写入，避免部分解码器直接拒收
        assertNotNull(tags[50728])
        assertEquals(3 * 8, tags[50728]!!.size)
    }

    /* ------------------------------ PLY 写入器 ------------------------------ */

    @Test
    fun `ply header and vertices are well formed`() {
        val points = floatArrayOf(0f, 1f, 2f, -3.5f, 4.25f, 5f)
        val colors = byteArrayOf(10, 20, 30, 40, 50, 60)
        val ply = PlyWriter.asciiPointCloud(points, colors, "unit test")
        val lines = ply.trim().split("\n")
        assertEquals("ply", lines[0])
        assertEquals("format ascii 1.0", lines[1])
        assertEquals("comment unit test", lines[2])
        assertEquals("element vertex 2", lines[3])
        assertTrue(lines.contains("end_header"))
        val first = lines[lines.indexOf("end_header") + 1]
        assertTrue(first.startsWith("0.00 1.00 2.00 10 20 30"))
        val second = lines[lines.indexOf("end_header") + 2]
        assertTrue(second.startsWith("-3.50 4.25 5.00 40 50 60"))
    }
}

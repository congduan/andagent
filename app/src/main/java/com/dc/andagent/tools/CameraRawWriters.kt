package com.dc.andagent.tools

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.roundToInt

/**
 * R110: 相机输出的二进制容器写入器（纯 Kotlin，无 Android 依赖 → JVM 单测可校验字节结构）。
 *
 * - [PngWriter]：16 位灰度（深度图/RAW 直方数据）与 8 位 RGB 预览；
 * - [DngWriter]：最小合规 DNG（TIFF/EP，未压缩单 strip + CFA + 黑/白电平 + 色彩矩阵）；
 * - [PlyWriter]：ASCII PLY 点云（深度图转 3D 点）。
 */
internal object PngWriter {

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun gray8(width: Int, height: Int, gray: ByteArray): ByteArray {
        require(gray.size >= width * height) { "像素数量不足" }
        val raw = ByteArray((width + 1) * height)
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter: none
            System.arraycopy(gray, y * width, raw, p, width)
            p += width
        }
        return build(width, height, 8, 0, raw)
    }

    fun gray16(width: Int, height: Int, samples: IntArray): ByteArray {
        require(samples.size >= width * height) { "像素数量不足" }
        val raw = ByteArray((width * 2 + 1) * height)
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            for (x in 0 until width) {
                val v = samples[y * width + x].coerceIn(0, 65535)
                raw[p++] = ((v shr 8) and 0xFF).toByte()
                raw[p++] = (v and 0xFF).toByte()
            }
        }
        return build(width, height, 16, 0, raw)
    }

    fun rgb8(width: Int, height: Int, rgb: ByteArray): ByteArray {
        require(rgb.size >= width * height * 3) { "像素数量不足" }
        val raw = ByteArray((width * 3 + 1) * height)
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0
            System.arraycopy(rgb, y * width * 3, raw, p, width * 3)
            p += width * 3
        }
        return build(width, height, 8, 2, raw)
    }

    private fun build(width: Int, height: Int, bitDepth: Int, colorType: Int, raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2 + 256)
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        put32(ihdr, 0, width.toLong())
        put32(ihdr, 4, height.toLong())
        ihdr[8] = bitDepth.toByte()
        ihdr[9] = colorType.toByte()
        ihdr[10] = 0 // deflate
        ihdr[11] = 0 // adaptive filtering
        ihdr[12] = 0 // no interlace
        out.write(chunk("IHDR", ihdr))
        out.write(chunk("IDAT", deflate(raw)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 4 + 64)
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArray(12 + data.size)
        put32(out, 0, data.size.toLong())
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(data, 0, out, 8, data.size)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        put32(out, 8 + data.size, crc.value)
        return out
    }

    internal fun put32(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = ((value shr 24) and 0xFF).toByte()
        dst[offset + 1] = ((value shr 16) and 0xFF).toByte()
        dst[offset + 2] = ((value shr 8) and 0xFF).toByte()
        dst[offset + 3] = (value and 0xFF).toByte()
    }
}

/**
 * 最小 DNG 写入器（TIFF 小端 + 单 strip 未压缩 CFA 数据）。
 * 只写 Adobe DNG 规范里「打开即可解码」必需/强建议的标签；缺项在 JSON 里如实标注。
 */
internal object DngWriter {

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    data class Meta(
        val width: Int,
        val height: Int,
        /** 0=R,1=G,2=B，顺序 TL/TR/BL/BR */
        val cfaPattern: IntArray,
        val blackLevel: Int,
        val whiteLevel: Int,
        val uniqueModel: String,
        /** XYZ→相机原生色彩空间（DNG ColorMatrix1），9 个元素行主序；null 则省略该标签 */
        val colorMatrix: FloatArray? = null,
        val orientation: Int = 1,
        val make: String = "AndAgent",
        val software: String = "AndAgent RAW",
        val iso: Int = 0,
        val exposureNs: Long = 0L,
    )

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    fun write(samples: IntArray, meta: Meta): ByteArray {
        val w = meta.width
        val h = meta.height
        require(samples.size >= w * h) { "samples 数量不足" }
        val strip = ByteArray(w * h * 2)
        var p = 0
        for (i in 0 until w * h) {
            val v = samples[i].coerceIn(0, 65535)
            strip[p++] = (v and 0xFF).toByte()
            strip[p++] = ((v shr 8) and 0xFF).toByte()
        }

        val entries = ArrayList<Entry>(28)
        val stripOffsetEntry = Entry(273, TYPE_LONG, 1, le32(0))
        entries.add(Entry(254, TYPE_LONG, 1, le32(0)))            // NewSubfileType
        entries.add(Entry(256, TYPE_LONG, 1, le32(w.toLong())))    // ImageWidth
        entries.add(Entry(257, TYPE_LONG, 1, le32(h.toLong())))    // ImageLength
        entries.add(Entry(258, TYPE_SHORT, 1, le16(16)))           // BitsPerSample
        entries.add(Entry(259, TYPE_SHORT, 1, le16(1)))            // Compression = none
        entries.add(Entry(262, TYPE_SHORT, 1, le16(32803)))        // Photometric = CFA
        entries.add(Entry(271, TYPE_ASCII, meta.make.length + 1, ascii(meta.make)))
        entries.add(Entry(272, TYPE_ASCII, meta.uniqueModel.length + 1, ascii(meta.uniqueModel)))
        entries.add(stripOffsetEntry)                              // StripOffsets
        entries.add(Entry(274, TYPE_SHORT, 1, le16(meta.orientation))) // Orientation
        entries.add(Entry(277, TYPE_SHORT, 1, le16(1)))            // SamplesPerPixel
        entries.add(Entry(278, TYPE_LONG, 1, le32(h.toLong())))    // RowsPerStrip
        entries.add(Entry(279, TYPE_LONG, 1, le32(strip.size.toLong()))) // StripByteCounts
        entries.add(Entry(284, TYPE_SHORT, 1, le16(1)))            // PlanarConfiguration
        entries.add(Entry(305, TYPE_ASCII, meta.software.length + 1, ascii(meta.software)))
        entries.add(Entry(33421, TYPE_SHORT, 2, le16Array(intArrayOf(2, 2)))) // CFARepeatPatternDim
        // CFAPattern 固定 4 项（TL/TR/BL/BR），入参不合法时退回 RGGB
        val cfa = if (meta.cfaPattern.size >= 4) meta.cfaPattern else intArrayOf(0, 1, 1, 2)
        entries.add(Entry(33422, TYPE_BYTE, 4, ByteArray(4) { cfa[it].toByte() }))
        if (meta.exposureNs > 0L) {
            entries.add(Entry(33434, TYPE_RATIONAL, 1, rationals(floatArrayOf(meta.exposureNs / 1_000_000_000f))))
        }
        if (meta.iso > 0) entries.add(Entry(34855, TYPE_SHORT, 1, le16(meta.iso))) // PhotographicSensitivity
        entries.add(Entry(50706, TYPE_BYTE, 4, byteArrayOf(1, 4, 0, 0)))            // DNGVersion 1.4
        entries.add(Entry(50707, TYPE_BYTE, 4, byteArrayOf(1, 1, 0, 0)))            // DNGBackwardVersion
        entries.add(Entry(50708, TYPE_ASCII, meta.uniqueModel.length + 1, ascii(meta.uniqueModel)))
        entries.add(Entry(50714, TYPE_SHORT, 1, le16(meta.blackLevel.coerceIn(0, 65535)))) // BlackLevel
        entries.add(Entry(50717, TYPE_SHORT, 1, le16(meta.whiteLevel.coerceIn(0, 65535)))) // WhiteLevel
        meta.colorMatrix?.let { m ->
            if (m.size >= 9) {
                entries.add(Entry(50721, TYPE_SRATIONAL, 9, srationals(m)))          // ColorMatrix1
                entries.add(Entry(50778, TYPE_SHORT, 1, le16(21)))                   // CalibrationIlluminant1 = D65
            }
        }
        entries.add(Entry(50728, TYPE_RATIONAL, 3, rationals(floatArrayOf(1f, 1f, 1f)))) // AsShotNeutral（缺省等能）
        entries.sortBy { it.tag }

        val ifdSize = 2 + 12 * entries.size + 4
        var extraOffset = 8 + ifdSize
        val extras = java.util.IdentityHashMap<Entry, Int>()
        for (e in entries) {
            if (e.data.size > 4) {
                extraOffset = (extraOffset + 1) and 1.inv() // 2 字节对齐（向上）
                extras[e] = extraOffset
                extraOffset += e.data.size
            }
        }
        val stripOffset = (extraOffset + 1) and 1.inv()
        val out = ByteArray(stripOffset + strip.size)
        out[0] = 'I'.code.toByte()
        out[1] = 'I'.code.toByte()
        put16(out, 2, 42)
        put32(out, 4, 8L)

        var pos = 8
        put16(out, pos, entries.size)
        pos += 2
        for (e in entries) {
            put16(out, pos, e.tag)
            put16(out, pos + 2, e.type)
            put32(out, pos + 4, e.count.toLong())
            if (e === stripOffsetEntry) {
                put32(out, pos + 8, stripOffset.toLong())
            } else {
                val off = extras[e]
                if (off != null) {
                    put32(out, pos + 8, off.toLong())
                    System.arraycopy(e.data, 0, out, off, e.data.size)
                } else {
                    System.arraycopy(e.data, 0, out, pos + 8, e.data.size)
                }
            }
            pos += 12
        }
        put32(out, pos, 0L) // 无下一个 IFD
        System.arraycopy(strip, 0, out, stripOffset, strip.size)
        return out
    }

    /** 读取 IFD 标签（单测与调用方自检用）：tag → 原始值字节。 */
    fun readTags(dng: ByteArray): Map<Int, ByteArray> {
        require(dng.size >= 8) { "不是 TIFF 文件" }
        require(dng[0] == 'I'.code.toByte() && dng[1] == 'I'.code.toByte()) { "仅支持小端 TIFF" }
        val ifdOffset = read32(dng, 4).toInt()
        val count = read16(dng, ifdOffset)
        val tags = LinkedHashMap<Int, ByteArray>()
        for (i in 0 until count) {
            val base = ifdOffset + 2 + i * 12
            val tag = read16(dng, base)
            val type = read16(dng, base + 2)
            val n = read32(dng, base + 4).toInt()
            val size = typeSize(type) * n
            tags[tag] = if (size <= 4) {
                dng.copyOfRange(base + 8, base + 8 + size)
            } else {
                val off = read32(dng, base + 8).toInt()
                dng.copyOfRange(off, minOf(dng.size, off + size))
            }
        }
        return tags
    }

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE, TYPE_ASCII -> 1
        TYPE_SHORT -> 2
        TYPE_LONG -> 4
        TYPE_RATIONAL, TYPE_SRATIONAL -> 8
        else -> 1
    }

    internal fun read16(src: ByteArray, offset: Int): Int =
        (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)

    internal fun read32(src: ByteArray, offset: Int): Long =
        (src[offset].toLong() and 0xFF) or
            ((src[offset + 1].toLong() and 0xFF) shl 8) or
            ((src[offset + 2].toLong() and 0xFF) shl 16) or
            ((src[offset + 3].toLong() and 0xFF) shl 24)

    private fun put16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun put32(dst: ByteArray, offset: Int, value: Long) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value shr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value shr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun le16(v: Int): ByteArray = ByteArray(2).also { put16(it, 0, v) }

    private fun le16Array(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v -> put16(out, i * 2, v) }
        return out
    }

    private fun le32(v: Long): ByteArray = ByteArray(4).also { put32(it, 0, v) }

    private fun ascii(text: String): ByteArray {
        val raw = text.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(raw.size + 1)
        System.arraycopy(raw, 0, out, 0, raw.size)
        return out
    }

    private fun rationals(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 8)
        values.forEachIndexed { i, v ->
            val (num, den) = toRational(v)
            put32(out, i * 8, num)
            put32(out, i * 8 + 4, den)
        }
        return out
    }

    private fun srationals(values: FloatArray): ByteArray = rationals(values)

    internal fun toRational(v: Float): Pair<Long, Long> {
        if (!v.isFinite()) return 0L to 1L
        val den = 1_000_000L
        val num = (v.toDouble() * den).roundToInt().toLong()
        return num to den
    }
}

/** ASCII PLY 点云：x/y/z 为 mm，可选 RGB 顶点色。 */
internal object PlyWriter {

    fun asciiPointCloud(points: FloatArray, colors: ByteArray?, comment: String): String {
        val count = points.size / 3
        val withColor = colors != null && colors.size >= count * 3
        val sb = StringBuilder(count * 32 + 256)
        sb.append("ply\n")
        sb.append("format ascii 1.0\n")
        if (comment.isNotBlank()) sb.append("comment ").append(comment.replace('\n', ' ')).append('\n')
        sb.append("element vertex ").append(count).append('\n')
        sb.append("property float x\nproperty float y\nproperty float z\n")
        if (withColor) {
            sb.append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
        }
        sb.append("end_header\n")
        for (i in 0 until count) {
            sb.append(fmt(points[i * 3])).append(' ')
                .append(fmt(points[i * 3 + 1])).append(' ')
                .append(fmt(points[i * 3 + 2]))
            if (withColor) {
                sb.append(' ').append(colors!![i * 3].toInt() and 0xFF)
                    .append(' ').append(colors!![i * 3 + 1].toInt() and 0xFF)
                    .append(' ').append(colors!![i * 3 + 2].toInt() and 0xFF)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Float): String {
        if (!v.isFinite()) return "0"
        return String.format(java.util.Locale.US, "%.2f", v)
    }
}

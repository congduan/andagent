package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** R118: PdfCodec / WallpaperTarget 纯函数回归（不碰 android 类）。 */
class PdfCodecTest {

    private fun ln(text: String, style: PdfCodec.LineStyle = PdfCodec.LineStyle.BODY) =
        PdfCodec.Line(text, style)

    /* -------------------- 行样式解析 -------------------- */

    @Test
    fun `styleOf maps known styles and falls back to body`() {
        assertEquals(PdfCodec.LineStyle.TITLE, PdfCodec.styleOf("title"))
        assertEquals(PdfCodec.LineStyle.HEADING, PdfCodec.styleOf(" HeAdInG "))
        assertEquals(PdfCodec.LineStyle.BODY, PdfCodec.styleOf("body"))
        assertEquals(PdfCodec.LineStyle.BODY, PdfCodec.styleOf("bogus"))
        assertEquals(PdfCodec.LineStyle.BODY, PdfCodec.styleOf(null))
    }

    @Test
    fun `font size bold and gap per style`() {
        assertEquals(22, PdfCodec.fontSize(PdfCodec.LineStyle.TITLE))
        assertEquals(16, PdfCodec.fontSize(PdfCodec.LineStyle.HEADING))
        assertEquals(11, PdfCodec.fontSize(PdfCodec.LineStyle.BODY))
        assertTrue(PdfCodec.isBold(PdfCodec.LineStyle.TITLE))
        assertTrue(PdfCodec.isBold(PdfCodec.LineStyle.HEADING))
        assertFalse(PdfCodec.isBold(PdfCodec.LineStyle.BODY))
        assertEquals(18, PdfCodec.gapAfter(PdfCodec.LineStyle.TITLE))
        assertEquals(10, PdfCodec.gapAfter(PdfCodec.LineStyle.HEADING))
        assertEquals(4, PdfCodec.gapAfter(PdfCodec.LineStyle.BODY))
    }

    @Test
    fun `parseLines handles string and object forms`() {
        val elements: List<Any?> = listOf(
            "plain body line",
            mapOf("text" to "A Title", "style" to "title"),
            mapOf("text" to "Heading", "style" to "heading"),
            mapOf("text" to "no style"),
        )
        val lines = PdfCodec.parseLines(elements)
        assertEquals(
            listOf(
                ln("plain body line"),
                ln("A Title", PdfCodec.LineStyle.TITLE),
                ln("Heading", PdfCodec.LineStyle.HEADING),
                ln("no style"),
            ),
            lines,
        )
    }

    @Test
    fun `parseLines skips bad elements`() {
        val elements: List<Any?> = listOf(
            42,
            mapOf("style" to "title"),      // 无 text
            null,
            mapOf("text" to 123),           // text 非字符串
            "ok",
        )
        assertEquals(listOf(ln("ok")), PdfCodec.parseLines(elements))
    }

    @Test
    fun `parseLineElement returns null for bad element`() {
        assertNull(PdfCodec.parseLineElement(3.14))
        assertNull(PdfCodec.parseLineElement(null))
        assertNull(PdfCodec.parseLineElement(mapOf("style" to "title")))
        assertNotNull(PdfCodec.parseLineElement("valid"))
    }

    @Test
    fun `linesFromBody splits CR LF CRLF`() {
        val lines = PdfCodec.linesFromBody("a\nb\r\nc\rd")
        assertEquals(listOf("a", "b", "c", "d"), lines.map { it.text })
        assertTrue(lines.all { it.style == PdfCodec.LineStyle.BODY })
    }

    @Test
    fun `buildInput requires one of lines or body`() {
        val missing = PdfCodec.buildInput(null, null, null)
        assertNotNull(missing.error)
        assertTrue(missing.error!!.contains("二选一"))
        assertTrue(missing.lines.isEmpty())
        // 显式给空 lines 数组视为「已提供」，由 validateTextBudget 再拦空内容。
        assertNull(PdfCodec.buildInput(emptyList<Any>(), null, null).error)
    }

    @Test
    fun `buildInput prepends title as TITLE line`() {
        val r = PdfCodec.buildInput(null, "one\ntwo", "My Title")
        assertNull(r.error)
        assertEquals(3, r.lines.size)
        assertEquals(ln("My Title", PdfCodec.LineStyle.TITLE), r.lines[0])
        assertEquals(ln("one"), r.lines[1])
        assertEquals(ln("two"), r.lines[2])
    }

    @Test
    fun `buildInput prefers lines over body when both present`() {
        val r = PdfCodec.buildInput(listOf("fromLines"), "fromBody", null)
        assertEquals(listOf(ln("fromLines")), r.lines)
    }

    /* ---------------------- 分页计算 ---------------------- */

    @Test
    fun `paginate empty returns no pages`() {
        assertTrue(PdfCodec.paginate(emptyList(), 100).isEmpty())
    }

    @Test
    fun `paginate exact fill stays on one page`() {
        val groups = PdfCodec.paginate(listOf(60, 40), 100)
        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1), groups[0])
    }

    @Test
    fun `paginate boundary spills to next page`() {
        val groups = PdfCodec.paginate(listOf(60, 40, 1), 100)
        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1), groups[0])
        assertEquals(listOf(2), groups[1])
    }

    @Test
    fun `paginate single oversized block occupies its own page`() {
        val groups = PdfCodec.paginate(listOf(500, 10), 100)
        assertEquals(2, groups.size)
        assertEquals(listOf(0), groups[0])
        assertEquals(listOf(1), groups[1])
    }

    @Test
    fun `paginate many equal blocks yields multiple full pages`() {
        val groups = PdfCodec.paginate(List(7) { 25 }, 100)
        assertEquals(2, groups.size)
        assertEquals(4, groups[0].size)
        assertEquals(3, groups[1].size)
    }

    /* -------------------- 文件名 sanitizer -------------------- */

    @Test
    fun `sanitize keeps chinese letters digits underscore hyphen`() {
        assertEquals("报告2026_v1-最终", PdfCodec.sanitizeBaseName("报告2026_v1-最终", 0L))
    }

    @Test
    fun `sanitize replaces illegal chars with underscore`() {
        assertEquals("a_b_c_d", PdfCodec.sanitizeBaseName("a/b:c?d.pdf", 0L))
        assertEquals("my_report", PdfCodec.sanitizeBaseName("my report", 0L))
        assertEquals("___", PdfCodec.sanitizeBaseName("!!!", 0L))
    }

    @Test
    fun `sanitize empty or blank uses timestamp`() {
        assertEquals("AndAgent_123", PdfCodec.sanitizeBaseName("", 123L))
        assertEquals("AndAgent_123", PdfCodec.sanitizeBaseName("   ", 123L))
        assertEquals("AndAgent_123", PdfCodec.sanitizeBaseName(null, 123L))
    }

    @Test
    fun `sanitize turns path separators into underscores (no traversal)`() {
        assertEquals("_sdcard_Download_report", PdfCodec.sanitizeBaseName("/sdcard/Download/report.PDF", 0L))
        assertEquals("___evil", PdfCodec.sanitizeBaseName("../evil", 0L))
        assertFalse(PdfCodec.sanitizeBaseName("a/../../b\\c", 0L).contains("/"))
        assertFalse(PdfCodec.sanitizeBaseName("a/../../b\\c", 0L).contains("\\"))
    }

    @Test
    fun `sanitize truncates overlong name`() {
        assertEquals(PdfCodec.MAX_NAME_LEN, PdfCodec.sanitizeBaseName("a".repeat(200), 0L).length)
    }

    @Test
    fun `pdfFileName appends pdf extension`() {
        assertEquals("report.pdf", PdfCodec.pdfFileName("report", 0L))
        assertEquals("AndAgent_9.pdf", PdfCodec.pdfFileName(null, 9L))
    }

    @Test
    fun `uniqueName appends numeric suffix on collision without overwriting`() {
        val existing = setOf("a.pdf", "a_1.pdf")
        assertEquals("a_2.pdf", PdfCodec.uniqueName("a.pdf") { it in existing })
    }

    @Test
    fun `uniqueName returns base when free and handles no-extension names`() {
        assertEquals("a.pdf", PdfCodec.uniqueName("a.pdf") { false })
        assertEquals("a_1", PdfCodec.uniqueName("a") { it == "a" })
    }

    /* ---------------- fit 页内矩形 / 页尺寸 ---------------- */

    @Test
    fun `fitRect landscape is limited by width and centered vertically`() {
        val r = PdfCodec.fitRect(2000, 1000, 595, 842, 40)
        assertEquals(40, r.left)
        assertEquals(515, r.right - r.left)                 // 铺满内容宽度
        assertTrue("居中留上下白边", r.top > 40 && r.bottom < 842 - 40)
        assertTrue("保持 2:1 纵横比", abs((r.right - r.left).toDouble() / (r.bottom - r.top) - 2.0) < 0.05)
    }

    @Test
    fun `fitRect portrait is limited by height and centered horizontally`() {
        val r = PdfCodec.fitRect(1000, 2000, 595, 842, 40)
        assertEquals(40, r.top)
        assertEquals(762, r.bottom - r.top)                 // 铺满内容高度
        assertTrue("居中留左右白边", r.left > 40 && r.right < 595 - 40)
        assertTrue("保持 1:2 纵横比", abs((r.right - r.left).toDouble() / (r.bottom - r.top) - 0.5) < 0.05)
    }

    @Test
    fun `fitRect respects margin and topOffset`() {
        val base = PdfCodec.fitRect(1000, 1000, 595, 842, 40)
        assertTrue(base.left >= 40 && base.top >= 40 && base.right <= 595 - 40 && base.bottom <= 842 - 40)
        val header = PdfCodec.fitRect(1000, 1000, 595, 842, 40, 40)
        assertTrue("头部预留下移", header.top > base.top)
        assertEquals(base.left, header.left)
    }

    @Test
    fun `fitPageDims scales long edge preserving aspect`() {
        assertEquals(1600 to 1200, PdfCodec.fitPageDims(4000, 3000))
        assertEquals(1200 to 1600, PdfCodec.fitPageDims(3000, 4000))
        assertEquals(800 to 1200, PdfCodec.fitPageDims(800, 1200))   // 未超 1600 → 原样
        assertEquals(800 to 1600, PdfCodec.fitPageDims(1000, 2000))  // 长边 2000 → 缩到 1600
    }

    /* -------------------- 预算护栏 -------------------- */

    @Test
    fun `validateTextBudget rejects empty content`() {
        assertNotNull(PdfCodec.validateTextBudget(emptyList()))
    }

    @Test
    fun `validateTextBudget rejects too many lines by returning error`() {
        val lines = (1..1001).map { ln("x") }
        val err = PdfCodec.validateTextBudget(lines)
        assertNotNull(err)
        assertTrue(err!!.contains("行数"))
    }

    @Test
    fun `validateTextBudget rejects overlong single line by returning error`() {
        val err = PdfCodec.validateTextBudget(listOf(ln("a".repeat(5001))))
        assertNotNull(err)
        assertTrue(err!!.contains("单行"))
    }

    @Test
    fun `validateTextBudget rejects over total bytes by returning error`() {
        val lines = (1..1000).map { ln("a".repeat(300)) }   // 300000B > 200KB
        val err = PdfCodec.validateTextBudget(lines)
        assertNotNull(err)
        assertTrue(err!!.contains("总输入"))
    }

    @Test
    fun `validateTextBudget accepts within limits`() {
        assertNull(PdfCodec.validateTextBudget((1..10).map { ln("line $it") }))
        assertNull(PdfCodec.validateTextBudget(listOf(ln("a".repeat(5000)))))
    }

    @Test
    fun `validateImageBudget rejects too many pages by returning error`() {
        val err = PdfCodec.validateImageBudget(0, 31)
        assertNotNull(err)
        assertTrue(err!!.contains("页数"))
    }

    @Test
    fun `validateImageBudget rejects over pixels by returning error`() {
        val err = PdfCodec.validateImageBudget(32_000_001L, 5)
        assertNotNull(err)
        assertTrue(err!!.contains("像素"))
    }

    @Test
    fun `validateImageBudget honors custom limits`() {
        assertNotNull(PdfCodec.validateImageBudget(100, 5, maxPixels = 50))
        assertNotNull(PdfCodec.validateImageBudget(100, 5, maxPages = 2))
    }

    @Test
    fun `validateImageBudget accepts boundary values`() {
        assertNull(PdfCodec.validateImageBudget(32_000_000L, 30))
    }

    /* ------------- wallpaper_set target 归一化 ------------- */

    @Test
    fun `wallpaper target normalize maps known values and falls back to home`() {
        assertEquals("home", WallpaperTarget.normalize(null))
        assertEquals("home", WallpaperTarget.normalize(""))
        assertEquals("home", WallpaperTarget.normalize("HOME"))
        assertEquals("home", WallpaperTarget.normalize(" home "))
        assertEquals("home", WallpaperTarget.normalize("bogus"))
        assertEquals("lock", WallpaperTarget.normalize("lock"))
        assertEquals("lock", WallpaperTarget.normalize(" LOCK "))
        assertEquals("both", WallpaperTarget.normalize("both"))
        assertEquals("both", WallpaperTarget.normalize("Both"))
    }
}

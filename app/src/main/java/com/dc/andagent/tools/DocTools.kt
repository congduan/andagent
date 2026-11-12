package com.dc.andagent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** R118: PDF 输出（文本排版 + 图片集拼页），产物落 Download/AndAgentDocs/，只新建不覆盖。 */
val PDF_TOOLS = listOf("pdf_generate", "pdf_from_images")

private const val D_GENERATE =
    "把文本内容排版成单列 A4 PDF 并保存到 Download/AndAgentDocs/。lines 为 JSON 数组（元素可为字符串或 {text, style:title|heading|body}），body 为按行拆分的纯文本，二者必居其一；title 为可选首页大标题，file_name 为可选文件名。单行 ≤5000 字符、总输入 ≤200KB、≤1000 行。"
private const val D_FROM_IMAGES =
    "把图片集合拼成 PDF，一图一页，保存到 Download/AndAgentDocs/。ids_or_uris 为图片源 JSON 数组（content:// URI、相册 id、本地路径或 base64）；page_size=fit 页尺寸贴合图片、a4 固定 A4 页并留 40pt 边距居中；title 为可选首页头部文字，file_name 为可选文件名。最多 30 页、总像素 32MP，JPEG 质量 88。"

private const val P_TITLE = "首页大标题，可选"
private const val P_LINES = "JSON 数组：元素为字符串（body 样式）或 {text, style:title|heading|body}；与 body 二选一"
private const val P_BODY = "纯文本按行拆成 body 段落，与 lines 二选一、必居其一"
private const val P_FILENAME = "可选文件名，不含扩展名；非法字符转 _，重名自动加序号"
private const val P_IDS = "图片源 JSON 数组（content:// URI、相册 id、本地路径或 base64）"
private const val P_PAGESIZE = "fit|a4，默认 fit"
private const val P_IMG_TITLE = "首页头部文字，可选"

private fun arrayParam(description: String, items: JsonObject? = null): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    if (items != null) put("items", items)
}

class PdfTool(
    private val context: Context,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "pdf_generate" -> D_GENERATE
        "pdf_from_images" -> D_FROM_IMAGES
        else -> "PDF 工具"
    }

    override val parameters: JsonObject = when (name) {
        "pdf_generate" -> schemaOf(
            "title" to strParam(P_TITLE),
            "lines" to arrayParam(P_LINES),
            "body" to strParam(P_BODY),
            "file_name" to strParam(P_FILENAME),
        )
        "pdf_from_images" -> schemaOf(
            "ids_or_uris" to arrayParam(P_IDS, buildJsonObject { put("type", "string") }),
            "page_size" to strParam(P_PAGESIZE),
            "title" to strParam(P_IMG_TITLE),
            "file_name" to strParam(P_FILENAME),
            required = listOf("ids_or_uris"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "pdf_generate" -> generate(args)
                "pdf_from_images" -> fromImages(args)
                else -> error("未知 PDF 工具 $name")
            }
        }.getOrElse { errorJson(it.message ?: it.javaClass.simpleName) }
    }

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private fun okJson(fileName: String, savedPath: String, pages: Int, bytes: Int): String = buildJsonObject {
        put("ok", true)
        put("file_name", fileName)
        put("savedPath", savedPath)
        put("pages", pages)
        put("bytes", bytes)
    }.toString()

    /* ------------------------- pdf_generate ------------------------- */

    private fun generate(args: JsonObject): String {
        val linesArr = args["lines"] as? JsonArray
        val body = args.strArg("body")
        val title = args.strArg("title")
        val fileNameRaw = args.strArg("file_name")

        val input = PdfCodec.buildInput(linesArr?.toPlainList(), body, title)
        input.error?.let { error(it) }
        PdfCodec.validateTextBudget(input.lines)?.let { error(it) }

        val doc = PdfDocument()
        val bytes: ByteArray
        val pages: Int
        try {
            pages = renderTextPdf(doc, input.lines)
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            bytes = out.toByteArray()
        } finally {
            doc.close()
        }
        return savePdf(fileNameRaw, bytes, pages)
    }

    private fun textPaintFor(style: PdfCodec.LineStyle): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PdfCodec.fontSize(style).toFloat()
        isFakeBoldText = PdfCodec.isBold(style)
        color = Color.BLACK
    }

    private fun renderTextPdf(doc: PdfDocument, lines: List<PdfCodec.Line>): Int {
        val contentWidth = PdfCodec.PAGE_WIDTH - 2 * PdfCodec.MARGIN
        val contentHeight = PdfCodec.PAGE_HEIGHT - 2 * PdfCodec.MARGIN
        val blocks = lines.map { line ->
            val paint = textPaintFor(line.style)
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, paint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            layout to (layout.height + PdfCodec.gapAfter(line.style))
        }
        val groups = PdfCodec.paginate(blocks.map { it.second }, contentHeight)
        for ((pageIdx, group) in groups.withIndex()) {
            val info = PdfDocument.PageInfo.Builder(PdfCodec.PAGE_WIDTH, PdfCodec.PAGE_HEIGHT, pageIdx + 1).create()
            val page = doc.startPage(info)
            val canvas = page.canvas
            var y = PdfCodec.MARGIN
            for (indexInAll in group) {
                val (layout, blockHeight) = blocks[indexInAll]
                canvas.save()
                canvas.translate(PdfCodec.MARGIN.toFloat(), y.toFloat())
                layout.draw(canvas)
                canvas.restore()
                y += blockHeight
            }
            doc.finishPage(page)
        }
        return groups.size
    }

    /* ------------------------ pdf_from_images ----------------------- */

    private fun fromImages(args: JsonObject): String {
        val idsArr = args["ids_or_uris"] as? JsonArray
            ?: error("缺少 ids_or_uris（图片源 JSON 数组）")
        val sources = idsArr.toStringList()
        require(sources.isNotEmpty()) { "ids_or_uris 为空" }
        val pageSize = when (args.strArg("page_size")?.lowercase()) {
            "a4" -> "a4"
            else -> "fit"
        }
        val title = args.strArg("title")?.take(120)
        val fileNameRaw = args.strArg("file_name")

        // 页数上限先判，像素预算在解码循环里累加判定（复用 32MP 护栏常量）。
        PdfCodec.validateImageBudget(0, sources.size, ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS)
            ?.let { error(it) }

        val doc = PdfDocument()
        val bytes: ByteArray
        var totalPixels = 0L
        var pages = 0
        try {
            for ((i, src) in sources.withIndex()) {
                val bitmap = ImageEditEngine.decode(context, src)
                var flat: Bitmap? = null
                try {
                    totalPixels += bitmap.width.toLong() * bitmap.height
                    PdfCodec.validateImageBudget(
                        totalPixels, sources.size, ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS,
                    )?.let { error(it) }

                    // JPEG q88：去 alpha / 控制 PDF 内嵌图片体积，再回解码用于绘制。
                    val jpeg = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, jpeg)) { "JPEG 压缩失败" }
                    val encoded = jpeg.toByteArray()
                    flat = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                        ?: error("JPEG 回解码失败")

                    val (pageW, pageH) = if (pageSize == "a4") {
                        PdfCodec.PAGE_WIDTH to PdfCodec.PAGE_HEIGHT
                    } else {
                        PdfCodec.fitPageDims(flat.width, flat.height)
                    }
                    val margin = if (pageSize == "a4") PdfCodec.MARGIN else 0
                    val header = if (i == 0 && title != null) PdfCodec.HEADER_HEIGHT else 0
                    val rect = PdfCodec.fitRect(flat.width, flat.height, pageW, pageH, margin, header)

                    val info = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                    val page = doc.startPage(info)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    if (header > 0 && title != null) {
                        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = 14f
                            isFakeBoldText = true
                            color = Color.BLACK
                        }
                        canvas.drawText(title, (margin + 4).toFloat(), (margin + 20).toFloat(), tp)
                    }
                    val dst = RectF(
                        rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                    )
                    canvas.drawBitmap(flat, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                    pages++
                } finally {
                    flat?.recycle()
                    bitmap.recycle()
                }
            }
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            bytes = out.toByteArray()
        } finally {
            doc.close()
        }
        return savePdf(fileNameRaw, bytes, pages)
    }

    /* ---------------------------- save ------------------------------ */

    private fun savePdf(fileNameRaw: String?, bytes: ByteArray, pages: Int): String {
        val desired = PdfCodec.pdfFileName(fileNameRaw, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= 29) {
            val relative = "${Environment.DIRECTORY_DOWNLOADS}/AndAgentDocs"
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val actual = PdfCodec.uniqueName(desired) { candidate ->
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(candidate, relative),
                    null,
                )?.use { it.moveToFirst() } ?: false
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, actual)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("MediaStore 拒绝创建 PDF 文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开 PDF 输出流")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return okJson(actual, "/sdcard/$relative/$actual", pages, bytes.size)
        }
        // API 26-28：直写公共 Download 需 WRITE_EXTERNAL_STORAGE，未授权时明确报错与引导，不虚报成功。
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            error("旧版 Android（<10）写公共 Download 需要「存储」写权限（WRITE_EXTERNAL_STORAGE）；请在系统设置授予后重试")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgentDocs")
        require(dir.isDirectory || dir.mkdirs()) { "无法创建输出目录 ${dir.absolutePath}" }
        val actual = PdfCodec.uniqueName(desired) { candidate -> File(dir, candidate).exists() }
        val file = File(dir, actual)
        file.writeBytes(bytes)
        return okJson(actual, file.absolutePath, pages, bytes.size)
    }

    private fun JsonArray.toPlainList(): List<Any?> = map { el ->
        when (el) {
            is JsonPrimitive -> el.contentOrNull
            is JsonObject -> el.entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull }
            else -> null
        }
    }

    private fun JsonArray.toStringList(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

/**
 * R118 PDF 纯函数核心：不碰任何 android 类，供 JVM 单测直接覆盖。
 * 预算护栏一律「返回错误字符串」而非抛异常，调用方（PdfTool）再转成 {"ok":false,...}。
 */
object PdfCodec {
    const val MAX_LINES = 1000
    const val MAX_LINE_CHARS = 5000
    const val MAX_TOTAL_BYTES = 200 * 1024
    const val MAX_IMAGES = 30
    /** 与 ImageEditEngine.MAX_COLLAGE_INPUT_PIXELS 保持一致（PdfTool 传入引擎常量做实际判定）。 */
    const val MAX_IMAGE_PIXELS = 32_000_000L

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 40
    const val HEADER_HEIGHT = 40
    const val MAX_NAME_LEN = 80

    private val NAME_ILLEGAL = Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]")

    enum class LineStyle { TITLE, HEADING, BODY }
    data class Line(val text: String, val style: LineStyle)
    data class PdfRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DocInput(val lines: List<Line>, val error: String?)

    fun styleOf(raw: String?): LineStyle = when (raw?.trim()?.lowercase()) {
        "title" -> LineStyle.TITLE
        "heading" -> LineStyle.HEADING
        else -> LineStyle.BODY
    }

    fun fontSize(style: LineStyle): Int = when (style) {
        LineStyle.TITLE -> 22
        LineStyle.HEADING -> 16
        LineStyle.BODY -> 11
    }

    fun isBold(style: LineStyle): Boolean = style != LineStyle.BODY

    fun gapAfter(style: LineStyle): Int = when (style) {
        LineStyle.TITLE -> 18
        LineStyle.HEADING -> 10
        LineStyle.BODY -> 4
    }

    /** string 与 {text,style} 双形态；坏元素（无 text 的对象、数字、null）返回 null 被跳过。 */
    fun parseLineElement(el: Any?): Line? = when (el) {
        is String -> Line(el, LineStyle.BODY)
        is Map<*, *> -> {
            val text = el["text"] as? String ?: return null
            Line(text, styleOf(el["style"] as? String))
        }
        else -> null
    }

    fun parseLines(elements: List<*>): List<Line> = elements.mapNotNull { parseLineElement(it) }

    fun linesFromBody(body: String): List<Line> =
        body.split(Regex("\r\n|\n|\r")).map { Line(it, LineStyle.BODY) }

    /** lines 优先；二者皆无报错；title 追加为首页 TITLE 行。 */
    fun buildInput(lines: List<*>?, body: String?, title: String?): DocInput {
        if (lines == null && body == null) {
            return DocInput(emptyList(), "需要 lines 或 body（二选一，必居其一）")
        }
        val parsed = if (lines != null) parseLines(lines) else linesFromBody(body!!)
        val withTitle = if (title.isNullOrBlank()) parsed else listOf(Line(title, LineStyle.TITLE)) + parsed
        return DocInput(withTitle, null)
    }

    /** 返回错误字符串或 null；不抛异常。 */
    fun validateTextBudget(lines: List<Line>): String? {
        if (lines.isEmpty()) return "没有可排版的内容（lines/body 为空）"
        if (lines.size > MAX_LINES) return "行数 ${lines.size} 超过上限 $MAX_LINES"
        var total = 0
        for (line in lines) {
            if (line.text.length > MAX_LINE_CHARS) {
                return "单行长度 ${line.text.length} 超过上限 $MAX_LINE_CHARS"
            }
            total += line.text.toByteArray(Charsets.UTF_8).size
        }
        if (total > MAX_TOTAL_BYTES) {
            return "总输入 $total 字节超过上限 $MAX_TOTAL_BYTES 字节（200KB）"
        }
        return null
    }

    /** 返回错误字符串或 null；不抛异常。maxPixels/maxPages 可由调用方传入引擎常量覆盖。 */
    fun validateImageBudget(
        totalPixels: Long,
        pageCount: Int,
        maxPixels: Long = MAX_IMAGE_PIXELS,
        maxPages: Int = MAX_IMAGES,
    ): String? {
        if (pageCount > maxPages) return "页数 $pageCount 超过上限 $maxPages"
        if (totalPixels > maxPixels) return "图片总像素 $totalPixels 超过上限 $maxPixels（32MP）"
        return null
    }

    /** 按块高贪心分页；恰好填满不再开空页；单块超高也独占一页。 */
    fun paginate(blocks: List<Int>, contentHeight: Int): List<List<Int>> {
        if (blocks.isEmpty()) return emptyList()
        val pages = mutableListOf<MutableList<Int>>()
        var current = mutableListOf<Int>()
        var used = 0
        for ((index, height) in blocks.withIndex()) {
            if (current.isNotEmpty() && used + height > contentHeight) {
                pages += current
                current = mutableListOf()
                used = 0
            }
            current += index
            used += height
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    /** 只留字母数字下划线连字符中文，非法字符转 _；空/超长分别用时间戳/截断。
     *  路径分隔符本身也是非法字符→_，故结果不含 / 或 \，不存在目录穿越。 */
    fun sanitizeBaseName(raw: String?, nowMillis: Long): String {
        if (raw.isNullOrBlank()) return "AndAgent_$nowMillis"
        var name = raw.trim()
        if (name.endsWith(".pdf", ignoreCase = true)) name = name.dropLast(4)
        name = NAME_ILLEGAL.replace(name, "_").take(MAX_NAME_LEN)
        return name.ifEmpty { "AndAgent_$nowMillis" }
    }

    fun pdfFileName(raw: String?, nowMillis: Long): String = "${sanitizeBaseName(raw, nowMillis)}.pdf"

    /** 重名加 _N 序号后缀，不覆盖；exists 由调用方提供（MediaStore 查询 / File.exists）。 */
    fun uniqueName(base: String, exists: (String) -> Boolean): String {
        if (!exists(base)) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        for (i in 1..999) {
            val candidate = "${stem}_$i$ext"
            if (!exists(candidate)) return candidate
        }
        return "${stem}_1000$ext"
    }

    /** fit 模式页尺寸：贴合图片纵横比，长边封顶 maxEdge。 */
    fun fitPageDims(imgW: Int, imgH: Int, maxEdge: Int = 1600): Pair<Int, Int> {
        require(imgW > 0 && imgH > 0) { "图片尺寸无效" }
        val longest = maxOf(imgW, imgH)
        if (longest <= maxEdge) return imgW to imgH
        val scale = maxEdge.toDouble() / longest
        return maxOf(1, (imgW * scale).roundToInt()) to maxOf(1, (imgH * scale).roundToInt())
    }

    /** 等比缩放进内容区并居中；topOffset 为首页标题预留的头部空白。 */
    fun fitRect(
        imgW: Int,
        imgH: Int,
        pageW: Int,
        pageH: Int,
        margin: Int,
        topOffset: Int = 0,
    ): PdfRect {
        require(imgW > 0 && imgH > 0) { "图片尺寸无效" }
        val contentW = (pageW - 2 * margin).coerceAtLeast(1)
        val contentH = (pageH - 2 * margin - topOffset).coerceAtLeast(1)
        val scale = minOf(contentW.toDouble() / imgW, contentH.toDouble() / imgH)
        val w = (imgW * scale).roundToInt().coerceAtLeast(1)
        val h = (imgH * scale).roundToInt().coerceAtLeast(1)
        val left = margin + (contentW - w) / 2
        val top = margin + topOffset + (contentH - h) / 2
        return PdfRect(left, top, left + w, top + h)
    }
}

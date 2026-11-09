package com.dc.andagent.tools

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import android.util.Base64
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Files: download / search / write / read / list.
 *
 * Android 10+ uses MediaStore (scoped storage); Android 8/9 falls back to the
 * legacy public directories with WRITE_EXTERNAL_STORAGE. Everything returns a
 * Chinese error string on failure instead of throwing.
 */

private object FileHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val READ_CAP = 256 * 1024
private const val READ_MAX_LINES = 2000
private const val READ_MAX_LINE_CHARS = 20_000
private const val BINARY_PROBE = 8192

/** Hard cap for a single file_write payload. */
internal const val WRITE_CAP = 4 * 1024 * 1024

/** Hard cap for read-modify-write in file_edit. */
internal const val EDIT_CAP = 2 * 1024 * 1024

private fun dirRoot(token: String?): String? = when (token?.lowercase()) {
    null, "", "downloads", "download" -> Environment.DIRECTORY_DOWNLOADS
    "documents", "document" -> Environment.DIRECTORY_DOCUMENTS
    "movies", "movie" -> Environment.DIRECTORY_MOVIES
    "music" -> Environment.DIRECTORY_MUSIC
    "pictures", "photos" -> Environment.DIRECTORY_PICTURES
    "dcim" -> Environment.DIRECTORY_DCIM
    else -> null
}

private fun sanitizeName(raw: String?): String? {
    val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
    if (base.isBlank() || base == "." || base == "..") return null
    return base.take(160)
}

private fun nameFromUrl(url: String): String {
    val path = runCatching { URI(url).path }.getOrNull().orEmpty()
    val last = path.substringAfterLast('/')
    return sanitizeName(last)?.takeIf { it.contains('.') }
        ?: "download_${System.currentTimeMillis()}.bin"
}

private fun mimeFor(name: String, header: String?): String {
    val fromHeader = header?.substringBefore(';')?.trim()
    if (!fromHeader.isNullOrBlank()) return fromHeader
    return when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "kt", "java", "js", "ts", "html", "css" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

private suspend fun ensureLegacyWrite(permissions: PermissionBroker): Boolean {
    if (Build.VERSION.SDK_INT >= 29) return true
    return runCatching {
        permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }.getOrDefault(false)
}

private suspend fun ensureRead(permissions: PermissionBroker): Boolean {
    val names = if (Build.VERSION.SDK_INT >= 33) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return runCatching { permissions.ensure(names) }.getOrDefault(false)
}

private fun uniqueFile(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (candidate.exists()) {
        candidate = File(dir, "$stem ($i)$ext")
        i++
    }
    return candidate
}

/* ------------------------------------------------------------------ */
/* shared read/write primitives (file_read / file_write / file_edit)    */
/* ------------------------------------------------------------------ */

/**
 * One resolvable file: a plain [File] (app-private, legacy public dirs,
 * Termux home ...) or a content URI (MediaStore / SAF).
 *
 * All three file tools go through this so `path`, `dir`+`name` and
 * `content://` share a single set of rules instead of each tool growing its
 * own.
 */
internal sealed class FileTarget {
    abstract val displayPath: String
    abstract fun openStream(): InputStream
    abstract fun exists(): Boolean
    abstract fun writeText(text: String): Long
}

/**
 * Plain filesystem target. Writes go to a sibling `.tmp` file and are renamed
 * into place, so an interrupted write can never truncate the original.
 */
internal class LocalTarget(val file: File) : FileTarget() {
    override val displayPath: String get() = file.absolutePath

    override fun openStream(): InputStream = file.inputStream()

    override fun exists(): Boolean = file.isFile

    override fun writeText(text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val parent = file.parentFile ?: error("目标没有父目录：${file.path}")
        if (!parent.exists() && !parent.mkdirs()) error("无法创建目录：${parent.path}")
        if (file.isDirectory) error("目标是目录：${file.path}")
        val tmp = File(parent, ".${file.name}.andagent.tmp")
        tmp.writeBytes(bytes)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            error("无法覆盖：${file.path}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("无法写入：${file.path}")
        }
        return bytes.size.toLong()
    }
}

/** content:// target (SAF document or an already-resolved MediaStore row). */
internal class UriTarget(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val displayPath: String,
) : FileTarget() {

    override fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: error("无法打开：$displayPath")

    override fun exists(): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    override fun writeText(text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = resolver.openOutputStream(uri, "wt")
            ?: resolver.openOutputStream(uri)
            ?: error("无法写入：$displayPath")
        out.use { it.write(bytes) }
        return bytes.size.toLong()
    }
}

/**
 * A public file addressed through MediaStore.
 *
 * Rows are looked up by (DISPLAY_NAME, RELATIVE_PATH) so writing the same name
 * twice edits that row instead of letting MediaStore auto-suffix a second copy
 * — the old insert-only path produced `notes.txt`, `notes (1).txt`, ...
 */
internal class MediaStoreTarget(
    private val context: Context,
    private val collection: Uri,
    private val relativePath: String,
    private val displayName: String,
    override val displayPath: String,
) : FileTarget() {

    private fun findUri(): Uri? = runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, "$relativePath/"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
        }
    }.getOrNull()

    override fun exists(): Boolean = findUri() != null

    override fun openStream(): InputStream {
        val uri = findUri() ?: error("文件不存在：$displayPath")
        return context.contentResolver.openInputStream(uri) ?: error("无法打开：$displayPath")
    }

    override fun writeText(text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val resolver = context.contentResolver

        findUri()?.let { existing ->
            runCatching {
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
                    ?: error("无法打开输出流")
            }.onSuccess { return bytes.size.toLong() }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(displayName, null))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore 拒绝创建：$relativePath/$displayName")
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法打开输出流")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bytes.size.toLong()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            throw it
        }
    }
}

private val BLOCKED_ROOTS = listOf("/system", "/vendor", "/proc", "/dev", "/sys", "/apex", "/data/data")
private const val APP_DATA_PREFIX = "/data/user/0/com.dc.andagent"

private fun isBlockedPath(path: String): Boolean =
    BLOCKED_ROOTS.any { path.startsWith(it) } && !path.startsWith(APP_DATA_PREFIX)

/**
 * Normalises a user-supplied relative path. Inner `/` separators survive so
 * `notes/2026/x.md` stays nested; `..` and blank segments are rejected.
 */
internal fun sanitizeRelPath(raw: String?): String? {
    val cleaned = (raw ?: "").trim().replace('\\', '/').trimStart('/')
    if (cleaned.isBlank()) return null
    val parts = cleaned.split('/').filter { it.isNotBlank() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    return parts.joinToString("/").take(240)
}

/** Maps `/sdcard/Documents/a/b.md` → (`Documents`, `a/b.md`). */
private fun splitPublicPath(path: String): Pair<String, String>? {
    val ext = Environment.getExternalStorageDirectory().absolutePath
    val rel = when {
        path.startsWith("$ext/") -> path.removePrefix("$ext/")
        path.startsWith("/sdcard/") -> path.removePrefix("/sdcard/")
        else -> return null
    }
    val root = dirRoot(rel.substringBefore('/')) ?: return null
    return root to rel.substringAfter('/', "")
}

private fun mediaStoreCollection(root: String): Uri =
    if (root == Environment.DIRECTORY_DOWNLOADS) {
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

/** downloads keeps the historical `Download/andagent/` home; others are flat. */
private fun mediaStoreTarget(context: Context, root: String, rel: String): MediaStoreTarget {
    val parent = rel.substringBeforeLast('/', "")
    val display = rel.substringAfterLast('/')
    val base = if (root == Environment.DIRECTORY_DOWNLOADS) "$root/andagent" else root
    val relative = if (parent.isBlank()) base else "$base/$parent"
    return MediaStoreTarget(
        context = context,
        collection = mediaStoreCollection(root),
        relativePath = relative,
        displayName = display,
        displayPath = "/sdcard/$relative/$display",
    )
}

/**
 * Single entry point for `path` / `dir`+`name` resolution.
 *
 * [forWrite] loosens the "must already exist" rule for app-private and public
 * targets while keeping the path-traversal and system-directory guards.
 */
internal fun resolveTarget(
    context: Context,
    path: String?,
    dir: String?,
    name: String?,
    forWrite: Boolean = false,
): FileTarget? {
    if (!path.isNullOrBlank()) {
        if (path.startsWith("content://")) {
            val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return null
            return UriTarget(context.contentResolver, uri, path)
        }
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (isBlockedPath(file.path)) return null
        if (file.isFile && file.canRead() && (!forWrite || file.canWrite())) return LocalTarget(file)

        // Scoped storage: /sdcard/... is not writable through java.io.File but
        // the same logical path stays addressable through MediaStore.
        if (Build.VERSION.SDK_INT >= 29) {
            splitPublicPath(file.path)?.let { (root, rel) ->
                if (rel.isNotBlank()) {
                    val target = mediaStoreTarget(context, root, rel)
                    if (forWrite || target.exists()) return target
                }
            }
        }
        return if (forWrite) LocalTarget(file) else null
    }

    val rel = sanitizeRelPath(name) ?: return null
    val token = dir?.lowercase()

    if (token == "app_private" || token == "private" || token == "app") {
        val base = File(context.filesDir, "andagent")
        val file = runCatching { File(base, rel).canonicalFile }.getOrNull() ?: return null
        val basePath = runCatching { base.canonicalPath }.getOrDefault(base.absolutePath)
        if (file.path != basePath && !file.path.startsWith("$basePath/")) return null
        return when {
            forWrite -> LocalTarget(file)
            file.isFile -> LocalTarget(file)
            else -> null
        }
    }

    val root = dirRoot(token ?: "downloads") ?: return null
    if (Build.VERSION.SDK_INT >= 29) {
        val target = mediaStoreTarget(context, root, rel)
        return if (forWrite || target.exists()) target else null
    }
    val file = File(Environment.getExternalStoragePublicDirectory(root), rel)
    return when {
        forWrite -> LocalTarget(file)
        file.isFile && file.canRead() -> LocalTarget(file)
        else -> null
    }
}

/* ------------------------------------------------------------------ */
/* file_download                                                       */
/* ------------------------------------------------------------------ */

class FileDownloadTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_download"
    override val description =
        "用 HTTP GET 下载文件到公共下载目录 Downloads/andagent/，支持大文件和重定向。" +
            "参数 url 必填，filename 可选；返回保存路径与大小。"
    override val parameters = schemaOf(
        "url" to strParam("下载地址（http/https）"),
        "filename" to strParam("可选：保存的文件名，默认从 URL 推断"),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): String {
        val url = args.strArg("url") ?: return "ERROR: 缺少 url"
        val name = sanitizeName(args.strArg("filename")) ?: nameFromUrl(url)
        val request = runCatching {
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "*/*")
                .get()
                .build()
        }.getOrElse { return "ERROR: 下载地址无效：${it.message ?: "无法解析 URL"}" }

        val response = runCatching {
            FileHttp.client.newCall(request).execute()
        }.getOrElse { return "ERROR: 下载失败：${it.message ?: it.javaClass.simpleName}" }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return "ERROR: 下载失败：HTTP ${resp.code} ${resp.message}"
            }
            val body = resp.body ?: return "ERROR: 下载失败：响应没有内容"
            val mime = mimeFor(name, resp.header("Content-Type"))

            return if (Build.VERSION.SDK_INT >= 29) {
                saveWithMediaStore(resp, name, mime)
            } else {
                if (!ensureLegacyWrite(permissions)) {
                    return "ERROR: 需要存储写入权限才能保存到 Downloads，请在系统设置中授予"
                }
                saveLegacy(resp, name, mime)
            }
        }
    }

    private fun saveWithMediaStore(
        response: okhttp3.Response,
        name: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/andagent")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return "ERROR: 无法在 Downloads 创建文件（MediaStore 拒绝）"
        return runCatching {
            val bytes = response.body!!.byteStream().use { input ->
                resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                    ?: error("无法打开输出流")
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actual = queryDisplayName(uri) ?: name
            buildJsonObject {
                put("saved", true)
                put("name", actual)
                put("path", "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/andagent/$actual")
                put("uri", uri.toString())
                put("bytes", bytes)
                put("sizeMB", "%.2f".format(bytes / 1e6))
            }.toString()
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            "ERROR: 写入 Downloads 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun saveLegacy(response: okhttp3.Response, name: String, mime: String): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "andagent",
        )
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建目录 ${dir.path}"
        val file = uniqueFile(dir, name)
        return runCatching {
            val bytes = response.body!!.byteStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
            buildJsonObject {
                put("saved", true)
                put("name", file.name)
                put("path", file.path)
                put("bytes", bytes)
                put("sizeMB", "%.2f".format(bytes / 1e6))
            }.toString()
        }.getOrElse {
            runCatching { file.delete() }
            "ERROR: 写入 Downloads 失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}

/* ------------------------------------------------------------------ */
/* file_write                                                          */
/* ------------------------------------------------------------------ */

class FileWriteTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_write"
    override val description =
        "写入文本文件，覆盖同名文件（不会生成 `x (1).txt` 副本）。" +
            "dir: downloads（Download/andagent/）、documents（Documents/）或 app_private（应用私有目录）；" +
            "name 可含子目录（如 notes/2026.md）。也可直接用 path 给绝对路径。返回路径与字节数。"
    override val parameters = schemaOf(
        "dir" to strParam("保存位置：downloads | documents | app_private，默认 downloads"),
        "name" to strParam("文件名或相对路径，可含子目录，例如 notes/2026.md"),
        "path" to strParam("可选：绝对路径，优先于 dir+name"),
        "content" to strParam("文件内容（UTF-8）"),
        required = listOf("content"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val element = args["content"] as? kotlinx.serialization.json.JsonPrimitive
            ?: return@withContext "ERROR: 缺少 content"
        val content = element.contentOrNull ?: ""
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > WRITE_CAP) {
            return@withContext "ERROR: 内容 ${bytes.size} 字节超过 ${WRITE_CAP / 1024 / 1024}MB 上限，" +
                "请改用 file_edit 做局部修改"
        }

        val path = args.strArg("path")
        if (path.isNullOrBlank() && args.strArg("name").isNullOrBlank()) {
            return@withContext "ERROR: 需要 name（或 path）"
        }
        if (Build.VERSION.SDK_INT < 29 && !ensureLegacyWrite(permissions)) {
            return@withContext "ERROR: 需要存储写入权限才能保存到公共目录，请在系统设置中授予"
        }

        val target = resolveTarget(context, path, args.strArg("dir"), args.strArg("name"), forWrite = true)
            ?: return@withContext "ERROR: 无法解析目标路径（name 不能为空、不能含 ..、不能写入系统目录）"

        runCatching {
            val existed = target.exists()
            target.writeText(content)
            buildJsonObject {
                put("saved", true)
                put("path", target.displayPath)
                put("bytes", bytes.size)
                put("lines", content.count { it == '\n' } + 1)
                put("overwritten", existed)
            }.toString()
        }.getOrElse { "ERROR: 写入失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

/* ------------------------------------------------------------------ */
/* file_read                                                           */
/* ------------------------------------------------------------------ */

class FileReadTool(private val context: Context) : Tool {
    override val name = "file_read"
    override val description =
        "读取文本文件，按行窗口返回，每行带行号前缀（如 `    12\t内容`）。" +
            "可用 path（绝对路径或 content://），也可用 dir（downloads|documents|app_private）+ name（可含子目录）。" +
            "用 offset/limit 翻页，单次最多 $READ_MAX_LINES 行或 256KB；二进制文件返回明确错误。" +
            "传给 file_edit 的 old_string 时请去掉行号前缀。"
    override val parameters = schemaOf(
        "path" to strParam("文件绝对路径或 content:// URI"),
        "dir" to strParam("与 name 搭配：downloads | documents | app_private"),
        "name" to strParam("文件相对路径（与 dir 搭配，可含子目录）"),
        "offset" to intParam("起始行号，从 1 开始，默认 1"),
        "limit" to intParam("最多返回行数，默认 $READ_MAX_LINES，上限 $READ_MAX_LINES"),
        "numbered" to boolParam("是否加行号前缀，默认 true"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val target = resolveTarget(
            context = context,
            path = args.strArg("path"),
            dir = args.strArg("dir"),
            name = args.strArg("name"),
        ) ?: return@withContext "ERROR: 找不到文件（请检查 path 或 dir+name，公共目录需要存储读取权限）"

        val offset = (args.intArg("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.intArg("limit") ?: READ_MAX_LINES).coerceIn(1, READ_MAX_LINES)
        val numbered = args.boolArg("numbered") ?: true

        runCatching {
            target.openStream().use { raw ->
                val input = BufferedInputStream(raw, 64 * 1024)
                input.mark(BINARY_PROBE)
                val head = ByteArray(BINARY_PROBE)
                val headLen = readUpTo(input, head)
                input.reset()
                if (looksBinary(head, headLen)) {
                    return@use "ERROR: 这不是文本文件（检测到二进制内容），已拒绝读取"
                }
                readWindow(input, target.displayPath, offset, limit, numbered)
            }
        }.getOrElse { "ERROR: 读取文件失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Reads a line window. Bounds are checked per line (not per byte) so a
     * multi-byte character is never sliced in half, and `nextOffset` lets the
     * model page through a file instead of guessing after `hasMore`.
     */
    private fun readWindow(
        input: InputStream,
        displayPath: String,
        offset: Int,
        limit: Int,
        numbered: Boolean,
    ): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        val out = StringBuilder()
        var lineNo = 0
        var returned = 0
        var bytes = 0L
        var hasMore = false
        var encodingIssues = false
        var lineClipped = false

        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (lineNo < offset) continue
            if (returned >= limit || bytes >= READ_CAP) {
                hasMore = true
                break
            }
            val safe = if (line.length > READ_MAX_LINE_CHARS) {
                lineClipped = true
                line.substring(0, READ_MAX_LINE_CHARS)
            } else {
                line
            }
            if (safe.indexOf('\uFFFD') >= 0) encodingIssues = true
            if (returned > 0) out.append('\n')
            if (numbered) out.append(lineNo.toString().padStart(6)).append('\t')
            out.append(safe)
            returned++
            bytes += line.encodeToByteArray().size + 1
        }

        if (returned == 0) {
            return "ERROR: offset=$offset 超出文件行数（共 $lineNo 行）"
        }

        return buildJsonObject {
            put("path", displayPath)
            put("offset", offset)
            put("returnedLines", returned)
            put("hasMore", hasMore)
            if (hasMore) put("nextOffset", offset + returned)
            put("bytes", bytes)
            if (encodingIssues) put("encodingIssues", true)
            if (lineClipped) put("lineClipped", true)
            put("content", out.toString())
        }.toString()
    }

    private fun looksBinary(head: ByteArray, len: Int): Boolean {
        if (len <= 0) return false
        var control = 0
        for (i in 0 until len) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) control++
        }
        return control * 10 > len * 3
    }
}

/* ------------------------------------------------------------------ */
/* file_search                                                         */
/* ------------------------------------------------------------------ */

class FileSearchTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_search"
    override val description =
        "按文件名搜索用户文件（关键词或 *.pdf 这类通配符），返回路径、大小、类型、修改时间，最多 50 条。" +
            "可选 dir 限定 downloads|documents|movies|music|pictures。"
    override val parameters = schemaOf(
        "query" to strParam("文件名关键词或通配符，例如 报告、*.pdf、IMG_2026*"),
        "dir" to strParam("可选：限定目录 downloads|documents|movies|music|pictures"),
        required = listOf("query"),
    )

    private data class Hit(
        val name: String,
        val path: String,
        val size: Long,
        val mime: String,
        val modified: Long,
        val uri: String?,
    )

    override suspend fun execute(args: JsonObject): String {
        val query = args.strArg("query") ?: return "ERROR: 缺少 query"
        val dirToken = args.strArg("dir")
        val root = dirRoot(dirToken)
        if (dirToken != null && root == null) {
            return "ERROR: dir 只能是 downloads|documents|movies|music|pictures"
        }
        ensureRead(permissions)

        val pattern = query.trim()
        val glob = pattern.contains('*') || pattern.contains('?')
        val hits = LinkedHashMap<String, Hit>()

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { queryMediaStore(pattern, glob, root, hits) }
        }
        runCatching { queryFileSystem(pattern, glob, root, hits) }

        val items = hits.values.sortedByDescending { it.modified }.take(50)
        if (items.isEmpty()) return "没有找到匹配「$pattern」的文件"
        return buildJsonObject {
            put("count", items.size)
            put("items", buildJsonArray {
                for (item in items) {
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", item.path)
                        put("sizeBytes", item.size)
                        put("sizeMB", "%.2f".format(item.size / 1e6))
                        put("mime", item.mime)
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.modified)))
                        item.uri?.let { put("uri", it) }
                    })
                }
            })
        }.toString()
    }

    private fun queryMediaStore(
        pattern: String,
        glob: Boolean,
        root: String?,
        hits: MutableMap<String, Hit>,
    ) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val like = if (glob) globToLike(pattern) else "%${escapeLike(pattern)}%"
        var selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val selectionArgs = mutableListOf(like)
        if (root != null) {
            selection += " AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            selectionArgs += "$root/%"
        }
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs.toTypedArray(),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (c.moveToNext() && hits.size < 200) {
                val name = c.getString(iName) ?: continue
                val rel = c.getString(iRel) ?: ""
                val path = "/sdcard/$rel$name"
                val uri = ContentUris.withAppendedId(collection, c.getLong(iId)).toString()
                hits[path] = Hit(
                    name = name,
                    path = path,
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: "application/octet-stream",
                    modified = c.getLong(iDate) * 1000,
                    uri = uri,
                )
            }
        }
    }

    private fun queryFileSystem(
        pattern: String,
        glob: Boolean,
        root: String?,
        hits: MutableMap<String, Hit>,
    ) {
        val roots = if (root != null) {
            listOfNotNull(
                runCatching {
                    File(Environment.getExternalStoragePublicDirectory(root).absolutePath)
                }.getOrNull(),
            )
        } else {
            listOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            )
        }
        val matcher = if (glob) globToRegex(pattern) else null
        var scanned = 0
        for (base in roots) {
            if (!base.exists() || !base.canRead()) continue
            runCatching {
                base.walkTopDown()
                    .onEnter { it == base || it.parentFile?.canRead() == true }
                    .take(4_000)
                    .forEach { file ->
                        scanned++
                        if (scanned > 8_000) return@runCatching
                        if (!file.isFile) return@forEach
                        val name = file.name
                        val matches = if (matcher != null) {
                            matcher.matches(name)
                        } else {
                            name.contains(pattern, ignoreCase = true)
                        }
                        if (!matches) return@forEach
                        hits.putIfAbsent(
                            file.absolutePath,
                            Hit(
                                name = name,
                                path = file.absolutePath,
                                size = file.length(),
                                mime = mimeFor(name, null),
                                modified = file.lastModified(),
                                uri = null,
                            ),
                        )
                    }
            }
        }
    }

    private fun escapeLike(text: String): String = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun globToLike(glob: String): String = buildString {
        append("%")
        glob.forEach { ch ->
            when (ch) {
                '*' -> append('%')
                '?' -> append('_')
                '%', '_', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
        append("%")
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        glob.forEach { ch ->
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(ch.toString()))
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }
}

/* ------------------------------------------------------------------ */
/* file_list                                                           */
/* ------------------------------------------------------------------ */

/**
 * R111: file_list 行。fromFs 区分文件系统扫描（true）与 MediaStore 查询（false），
 * 供 [filterGhostEntries] 做幽灵条目去重（顶层 internal，纯函数可 JVM 单测）。
 */
internal data class FileListRow(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val directory: Boolean,
    val fromFs: Boolean,
)

/** R111: 归一外部存储路径——MediaStore 用 /sdcard/…，文件系统用 /storage/emulated/0/…，统一去掉存储根前缀。 */
internal fun ghostNormalizedPath(path: String): String {
    val p = path.replace('\\', '/')
    return when {
        p.startsWith("/storage/emulated/0/") -> p.removePrefix("/storage/emulated/0/")
        p.startsWith("/sdcard/") -> p.removePrefix("/sdcard/")
        p.startsWith("/mnt/sdcard/") -> p.removePrefix("/mnt/sdcard/")
        else -> p.trimStart('/')
    }
}

/**
 * R111: file_list 幽灵条目去重（纯函数）：
 * 1) 按归一化 path 分组，同 path 冲突时保留 size>0 的那行（真实文件）；
 * 2) 全为 0 时保留文件系统行（directory 标志优先）；
 * 3) MediaStore 行 size=0 且 modified≤0（1970/epoch）且同名文件系统行存在时丢弃该幽灵行。
 */
internal fun filterGhostEntries(rows: List<FileListRow>): List<FileListRow> {
    val groups = LinkedHashMap<String, MutableList<FileListRow>>()
    rows.forEach { r -> groups.getOrPut(ghostNormalizedPath(r.path)) { mutableListOf() }.add(r) }

    return groups.map { (_, group) ->
        val fsNames = group.filter { it.fromFs }.map { it.name }.toSet()
        // 丢弃被同名文件系统行覆盖的 epoch 幽灵 MediaStore 行
        val visible = group.filterNot { r ->
            !r.fromFs && r.size == 0L && r.modified <= 0L && r.name in fsNames
        }.ifEmpty { group }

        val sized = visible.filter { it.size > 0 }
        if (sized.isNotEmpty())
            sized.sortedWith(compareByDescending<FileListRow> { it.fromFs }.thenByDescending { it.size }).first()
        else
            visible.sortedWith(compareByDescending<FileListRow> { it.fromFs }.thenByDescending { it.directory }).first()
    }
}

class FileListTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "file_list"
    override val description =
        "列出 Download/andagent/（默认）或指定公共目录下的文件，返回名称、大小、修改时间，最多 100 条。" +
            "dir 可选 downloads|documents|movies|music|pictures，subdir 可指定子目录。"
    override val parameters = schemaOf(
        "dir" to strParam("目录：downloads（默认）|documents|movies|music|pictures"),
        "subdir" to strParam("可选：子目录名；downloads 默认 andagent，其他默认根目录"),
    )

    override suspend fun execute(args: JsonObject): String {
        val token = (args.strArg("dir") ?: "downloads").lowercase()
        val root = dirRoot(token) ?: return "ERROR: 不支持的 dir：$token"
        val subdir = args.strArg("subdir") ?: if (token == "downloads" || token == "download") "andagent" else ""
        ensureRead(permissions)

        val prefix = listOf(root, subdir)
            .filter { it.isNotBlank() }
            .joinToString("/")
        // R111: 两路先收集成 List 再过 filterGhostEntries 归一去重（旧 key 不同必然重复）。
        val rows = mutableListOf<FileListRow>()

        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { queryMediaStore(prefix, rows) }
        }
        runCatching { queryFileSystem(root, subdir, rows) }

        val items = filterGhostEntries(rows).sortedByDescending { it.modified }.take(100)
        if (items.isEmpty()) return "目录 $prefix/ 为空或无读取权限"
        return buildJsonObject {
            put("dir", "/sdcard/$prefix/")
            put("count", items.size)
            put("items", buildJsonArray {
                for (item in items) {
                    add(buildJsonObject {
                        put("name", item.name)
                        put("path", item.path)
                        put("sizeBytes", item.size)
                        put("sizeMB", "%.2f".format(item.size / 1e6))
                        put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(item.modified)))
                        put("directory", item.directory)
                    })
                }
            })
        }.toString()
    }

    private fun queryMediaStore(prefix: String, rows: MutableList<FileListRow>) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$prefix/%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            var count = 0
            while (c.moveToNext() && count++ < 300) {
                val name = c.getString(iName) ?: continue
                val rel = c.getString(iRel) ?: "$prefix/"
                val path = "/sdcard/$rel$name"
                rows += FileListRow(
                    name = name,
                    path = path,
                    size = c.getLong(iSize),
                    modified = c.getLong(iDate) * 1000,
                    directory = false,
                    fromFs = false,
                )
            }
        }
    }

    private fun queryFileSystem(root: String, subdir: String, rows: MutableList<FileListRow>) {
        val base = File(
            Environment.getExternalStoragePublicDirectory(root),
            subdir,
        )
        if (!base.exists() || !base.canRead()) return
        runCatching {
            base.listFiles()?.take(500)?.forEach { file ->
                if (file.isDirectory) {
                    rows += FileListRow(file.name + "/", file.absolutePath, 0, file.lastModified(), true, true)
                } else {
                    rows += FileListRow(file.name, file.absolutePath, file.length(), file.lastModified(), false, true)
                }
            }
        }
    }
}

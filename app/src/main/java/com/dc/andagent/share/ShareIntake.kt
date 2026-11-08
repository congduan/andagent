package com.dc.andagent.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * R111: 系统分享（ACTION_SEND）归一化入口。
 *
 * 只做两件事：把分享意图解析成 [SharePayload]，以及把图片流复制进应用私有
 * 目录。入队与 UI 一律复用 #14 的 deeplink 队列（见 AgentBridge.queueShare），
 * 图片本体按 [SharePayload.imageUri] 落盘后的绝对路径交给 R88 图片输入链路。
 */
object ShareIntake {

    /** 单张分享图片的落盘上限：防止超大文件把私有目录撑爆。 */
    const val MAX_IMAGE_BYTES = 48L * 1024 * 1024

    /** 分享文本上限（比 deeplink 的 2000 宽一些：浏览器分享常带标题+正文）。 */
    const val MAX_TEXT_CHARS = 4000

    /** 落盘目录（filesDir 下），R88 图片链路按绝对路径读取。 */
    private const val DIR = "shared_images"

    /** 只保留最近 N 张分享图片，避免长期积累。 */
    internal const val KEEP_IMAGES = 20

    data class SharePayload(
        val text: String,
        val imageUri: Uri?,
        val mime: String?,
    )

    /**
     * 解析 ACTION_SEND（text/plain 与 image 类 MIME）。纯解析、不碰流，主线程可安全调用；
     * 不是分享意图、或既没有文本也没有图片时返回 null。
     */
    fun parse(intent: Intent?): SharePayload? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val mime = intent.type?.lowercase()
        val text = shareText(intent)
        val imageUri = streamUri(intent)?.takeIf { isImage(mime, it) }
        if (text.isEmpty() && imageUri == null) return null
        return SharePayload(text, imageUri, mime)
    }

    /**
     * 把分享的图片流复制进 filesDir/shared_images，返回绝对路径；失败（超大、
     * 流打不开等）返回 null，调用方按「只有文字」降级，不 crash。
     */
    fun persistImage(
        context: Context,
        uri: Uri,
        mime: String?,
        maxBytes: Long = MAX_IMAGE_BYTES,
    ): String? = runCatching {
        val dir = directory(context).apply { mkdirs() }
        val name = displayName(context, uri)
        val ext = extensionFor(mime, name)
        val target = File(dir, "${System.currentTimeMillis()}-${System.nanoTime() % 100000}.$ext")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开分享的图片流")
        try {
            input.use { copyCapped(it, target, maxBytes) }
        } catch (e: Throwable) {
            runCatching { target.delete() }
            throw e
        }
        prune(dir, KEEP_IMAGES)
        target.absolutePath
    }.getOrNull()

    /** 落盘目录：R88 图片链路与清理逻辑共用。 */
    fun directory(context: Context): File = File(context.filesDir, DIR)

    /** 图片扩展名：优先 MIME，其次原文件名，最后回落 jpg（不允许路径/控制字符）。 */
    internal fun extensionFor(mime: String?, name: String?): String {
        val fromMime = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase()
            ?.takeIf { isSafeExt(it) }
        val fromName = name?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { isSafeExt(it) }
        return fromMime ?: fromName ?: "jpg"
    }

    private fun isSafeExt(ext: String): Boolean =
        ext.length in 2..5 && ext.all { it.isLetterOrDigit() }

    private fun isImage(mime: String?, uri: Uri): Boolean {
        if (mime != null) return mime.startsWith("image/")
        val path = uri.lastPathSegment ?: return false
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun shareText(intent: Intent): String {
        val raw = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
            ?: intent.data?.toString()?.takeIf { it.startsWith("http") }
            ?: return ""
        return clean(raw).take(MAX_TEXT_CHARS)
    }

    /** 去掉控制字符（保留换行/制表），折叠连续空行为最多两行。 */
    private fun clean(raw: String): String {
        val filtered = raw.filter { it == '\n' || it == '\t' || !it.isISOControl() }
        return filtered.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } else {
            uri.lastPathSegment
        }
    }.getOrNull()

    private fun copyCapped(input: InputStream, target: File, maxBytes: Long) {
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) throw IOException("分享的图片超过 ${maxBytes / (1024 * 1024)}MB 上限")
                out.write(buffer, 0, read)
            }
        }
    }

    /** 只保留最近 [keep] 张，其余删除（按修改时间）。 */
    internal fun prune(dir: File, keep: Int) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}

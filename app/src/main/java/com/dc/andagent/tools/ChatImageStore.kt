package com.dc.andagent.tools

import android.content.Context
import android.graphics.Bitmap
import com.dc.andagent.share.ShareIntake
import java.io.File
import java.io.FileOutputStream

/**
 * R88: 对话内图片附件存储。
 *
 * 一次导入做三件事：解码（复用 [ImageEditEngine.decode]，自动 EXIF 转正 + 采样）、
 * 压到长边 ≤ [MAX_EDGE] 的 JPEG（控制视觉模型 token）、落盘到
 * `<externalFilesDir>/chat-images/`——这个目录由 `LocalMediaHandler` 的 `/local/`
 * 路由对外提供，所以 WebView 直接用 `<img src="/local/chat-images/x.jpg">` 渲染，
 * 消息里只存相对路径（会话 KV 单值 64KB 上限，绝不能塞 base64）。
 *
 * 模型侧需要 base64：由 [readBase64] 按路径读回（web 侧带进程内缓存）。
 */
object ChatImageStore {

    /** 视觉模型的经验最优长边（Claude/OpenAI 都建议 ≤1568）。 */
    const val MAX_EDGE = 1568
    const val JPEG_QUALITY = 80

    /** 单次导入的解码上限，防止超大图把内存打爆。 */
    const val MAX_SOURCE_BYTES = 40L * 1024 * 1024

    /** 目录内保留的文件数（超出按修改时间删最旧的）。 */
    internal const val KEEP_FILES = 200

    private const val DIR = "chat-images"

    data class Imported(
        /** 会话里存的相对路径：`chat-images/x.jpg`。 */
        val path: String,
        /** 渲染 URL：`/local/chat-images/x.jpg`。 */
        val url: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    fun dir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, DIR)

    /**
     * 导入一张图片：`content://`（相册选择、相机输出）、或应用自己落盘的路径
     * （`chat-images/`、`shared_images/`——R111 系统分享）。其它路径一律拒绝：
     * web 层不能拿这个接口读任意本地文件。
     */
    fun import(context: Context, source: String): Imported {
        require(source.isNotBlank()) { "缺少图片来源" }
        require(allowedSource(context, source)) { "不允许的图片来源" }
        val bitmap = ImageEditEngine.decode(context, source, MAX_EDGE)
        val width = bitmap.width
        val height = bitmap.height
        val target = File(dir(context), uniqueName())
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "图片编码失败" }
            }
        } finally {
            bitmap.recycle()
        }
        prune(dir(context), KEEP_FILES)
        return Imported(
            path = "$DIR/${target.name}",
            url = "/local/$DIR/${target.name}",
            width = width,
            height = height,
            bytes = target.length(),
        )
    }

    /** 按相对路径读回 base64（模型消息用）；越界路径或文件缺失返回 null。 */
    fun readBase64(context: Context, path: String): String? = runCatching {
        val name = requireRelativeName(path)
        val file = File(dir(context), name)
        require(file.isFile) { "图片不存在" }
        require(file.length() <= MAX_SOURCE_BYTES) { "图片过大" }
        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** `chat-images/x.jpg` → `x.jpg`；带路径分隔/`..`/空名一律拒绝。 */
    internal fun requireRelativeName(path: String): String {
        val name = path.removePrefix("$DIR/")
        require(name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != "..") {
            "非法的图片路径"
        }
        return name
    }

    /** 只接受 content:// 与应用自己的两个落盘目录。 */
    internal fun allowedSource(context: Context, source: String): Boolean {
        if (source.startsWith("content://")) return true
        val target = runCatching { File(source).canonicalFile }.getOrNull() ?: return false
        val roots = listOf(dir(context).canonicalFile, ShareIntake.directory(context).canonicalFile)
        return roots.any { root -> target.path.startsWith(root.path + File.separator) }
    }

    private fun uniqueName(): String =
        "img_${System.currentTimeMillis()}_${(System.nanoTime() % 100000).toString().padStart(5, '0')}.jpg"

    /** 只保留最近 [keep] 个文件。 */
    internal fun prune(dir: File, keep: Int) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(keep).forEach { runCatching { it.delete() } }
    }
}

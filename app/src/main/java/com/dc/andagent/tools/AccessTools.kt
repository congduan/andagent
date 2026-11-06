package com.dc.andagent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64

/**
 * File access trio:
 *  - open_folder: open a system file manager on a directory (no permission
 *    needed via DocumentsUI / StorageRoot intent; degrades gracefully).
 *  - file_picker: launch SAF picker (ACTION_OPEN_DOCUMENT); the picked URI is
 *    NOT readable by us unless the user also grants persistable permission —
 *    we open the picker so the USER chooses a file, and report the picked
 *    Uri back through the result receiver for the agent to reference.
 *  - image_save: decode base64 image and insert into MediaStore images
 *    (Pictures/AndAgent), visible in gallery apps.
 */
class OpenFolderTool(private val context: Context) : Tool {
    override val name = "open_folder"
    override val description =
        "打开系统文件管理器并定位到指定目录（downloads/documents/pictures/movies 或自定义路径）。只浏览不修改。"

    override val parameters = schemaOf(
        "dir" to strParam("downloads | documents | pictures | movies | alarms | notifications 或绝对路径"),
    )

    override suspend fun execute(args: JsonObject): String {
        val dir = args.strArg("dir") ?: "downloads"
        val dirFile = when (dir.lowercase()) {
            "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            "documents" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            "pictures" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            "movies" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            "alarms" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS)
            "notifications" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS)
            else -> File(dir)
        }
        if (!dirFile.exists()) return "ERROR: 目录不存在：${dirFile.absolutePath}"

        // Primary: DocumentsUI on a directory URI (works on 9+, no permission).
        val uris = listOf(
            // DocumentsUI "open documents tree" style — shows the dir listing.
            runCatching {
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(dirFile.toURI().toString()), "resource/folder")
            }.getOrNull(),
            Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" },
        )
        for (i in uris.indices) {
            val intent = uris[i] ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return "OPENED: 已打开${if (i == 0) "文件管理器" else "文档选择器"}（${dirFile.absolutePath}）"
            } catch (_: Exception) { /* try next */ }
        }
        return "ERROR: 设备上没有可用的文件管理器"
    }
}

/**
 * SAF file picker launcher. Results arrive via the FilePickResultReceiver;
 * the tool blocks until the user picks (or 120s timeout).
 */
class FilePickTool(
    private val resultBridge: FilePickResultBridge,
) : Tool {
    override val name = "file_pick"
    override val description =
        "打开系统文件选择器让用户挑选一个文件。返回所选文件的 Uri（content://），可配合 file_read 等使用。用户取消或超时（120s）会明确返回。"

    override val parameters = schemaOf(
        "mime" to strParam("MIME 过滤，如 image/*、application/pdf；默认 */* 全部"),
        "multiple" to boolParam("是否允许多选，默认 false"),
    )

    override suspend fun execute(args: JsonObject): String {
        val mime = args.strArg("mime") ?: "*/*"
        val multiple = args.boolArg("multiple") ?: false
        val id = "pick_${java.util.UUID.randomUUID()}"
        val latch = java.util.concurrent.CountDownLatch(1)
        val kind = if (multiple) FilePickResultBridge.Kind.MULTIPLE else FilePickResultBridge.Kind.SINGLE
        if (!resultBridge.begin(id, kind, latch)) return "ERROR: 文件选择器不可用或已有选择请求进行中"
        try {
            resultBridge.launcher?.invoke(id, mime, multiple, false)
                ?: resultBridge.fail(id, "文件选择器不可用")
        } catch (e: Exception) {
            resultBridge.fail(id, "无法打开文件选择器：${e.message}")
        }
        val ok = latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        val failure = resultBridge.failures[id]
        val uris = resultBridge.results[id]
        resultBridge.finish(id)
        if (!ok) return "ERROR: 文件选择超时（120s）或用户未响应"
        if (failure != null) return "ERROR: $failure"
        if (uris == null) return "ERROR: 选择器未返回结果"
        if (uris.isEmpty()) return "CANCELLED: 用户取消了文件选择"
        return buildJsonObject {
            put("count", uris.size)
            put("uris", kotlinx.serialization.json.buildJsonArray {
                for (u in uris) add(kotlinx.serialization.json.JsonPrimitive(u))
            })
        }.toString()
    }
}

class ImageSaveTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "image_save"
    override val description =
        "把 base64 编码的图片保存到系统相册（Pictures/AndAgent 目录），保存后立即在相册应用可见。"

    override val parameters = schemaOf(
        "base64" to strParam("图片的 base64 编码（含 data:image/...;base64, 前缀也可）"),
        "filename" to strParam("可选：文件名（省略 .png 等后缀会自动补）"),
        "display_name" to strParam("可选：相册中显示的名称"),
    )

    override suspend fun execute(args: JsonObject): String {
        val b64 = args.strArg("base64") ?: return "ERROR: 缺少 base64"
        if (!permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
            // 10+ scoped storage: WRITE_EXTERNAL_STORAGE is not strictly
            // required for MediaStore inserts of own contributions; continue
            // and let MediaStore throw if truly denied.
        }
        val cleaned = b64.substringAfter("base64,", b64).trim()
        val bytes = runCatching { Base64.getDecoder().decode(cleaned) }.getOrElse {
            return "ERROR: base64 解码失败（${it.message ?: "格式错误"}）"
        }
        if (bytes.size < 8) return "ERROR: 图片数据为空"

        // Sniff type from magic bytes.
        val ext = when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size > 5 && bytes.slice(0..2).map { it.toChar() }.joinToString("") == "GIF" -> "gif"
            bytes.size > 11 && bytes.slice(8..11).map { it.toChar() }.joinToString("") == "WEBP" -> "webp"
            else -> "png"
        }
        val name = (args.strArg("filename") ?: "andagent_${System.currentTimeMillis()}").let {
            if (it.contains('.')) it else "$it.$ext"
        }
        val display = args.strArg("display_name") ?: name

        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, display)
                put(MediaStore.Images.Media.MIME_TYPE, "image/$ext")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AndAgent")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: return "ERROR: " + tr("MediaStore 插入失败", "MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return "ERROR: " + tr("无法写入图片数据", "could not write image data")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "WRITTEN: " + tr(
                "图片已保存到相册 Pictures/AndAgent/$display（${bytes.size / 1024}KB，$ext）",
                "image saved to Pictures/AndAgent/$display (${bytes.size / 1024}KB, $ext)",
            )
        } catch (e: SecurityException) {
            "ERROR: " + tr(
                "相册写入被系统拒绝（${e.message}）；Android 10+ 通常无需 WRITE_EXTERNAL_STORAGE，此错误请反馈",
                "gallery write denied by the system (${e.message}); Android 10+ normally needs no WRITE_EXTERNAL_STORAGE — please report this",
            )
        } catch (e: Exception) {
            "ERROR: " + tr("保存失败（${e.message ?: e.javaClass.simpleName}）", "save failed (${e.message ?: e.javaClass.simpleName})")
        }
    }
}

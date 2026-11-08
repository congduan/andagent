package com.dc.andagent.tools

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Album photo access:
 *  - album_photos: query photos (MediaStore) by folder/keyword/time window,
 *    returns id + content:// uri + thumbnail base64 for inline preview.
 *  - album_open: open one photo in the system gallery/viewer (ACTION_VIEW).
 */
class AlbumPhotosTool(private val context: Context) : Tool {
    override val name = "album_photos"
    override val description =
        "查询相册照片列表（返回 id、content:// uri 与缩略图，缩略图由工具卡片渲染，不需粘贴到回复）。按相册文件夹/关键词/时间窗过滤，默认最新 8 张。"

    override val parameters = schemaOf(
        "bucket" to strParam("相册文件夹名（如 Screenshots、Camera），省略则全部"),
        "keyword" to strParam("文件名关键词"),
        "limit" to intParam("返回数量，默认 8，上限 20"),
        "thumbnail" to strParam("是否附带缩略图 base64（true/false），默认 true"),
    )

    override suspend fun execute(args: JsonObject): String {
        val bucket = args.strArg("bucket")
        val keyword = args.strArg("keyword")
        val limit = (args.intArg("limit") ?: 8).coerceIn(1, 20)
        val wantThumb = (args.strArg("thumbnail") ?: "true").equals("true", true)

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val selection = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        if (!bucket.isNullOrBlank()) { selection.add("bucket_display_name LIKE ?"); selectionArgs.add("%$bucket%") }
        // MediaStore 列名是 _display_name（DISPLAY_NAME 常量值），裸 "display_name" 在
        // 部分 ROM/DB 上查询报错或静默不命中——统一用常量值，与 projection 一致。
        if (!keyword.isNullOrBlank()) { selection.add("${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"); selectionArgs.add("%$keyword%") }

        val rows = mutableListOf<Map<String, String>>()
        resolver.query(
            collection, projection,
            if (selection.isEmpty()) null else selection.joinToString(" AND "),
            if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext() && rows.size < limit) {
                val photoId = c.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, photoId.toString())
                val entry = mutableMapOf<String, String>().apply {
                    put("id", photoId.toString())
                    put("uri", uri.toString())
                    put("name", c.getString(nameCol) ?: "")
                    put("bucket", c.getString(bucketCol) ?: "")
                    put("sizeBytes", c.getLong(sizeCol).toString())
                    put("dateTaken", c.getLong(dateCol).toString())
                }
                if (wantThumb) {
                    val thumb = decodeThumbnail(uri, 320)
                    if (thumb != null) entry["thumbnailBase64"] = thumb
                }
                rows.add(entry)
            }
        } ?: return "ERROR: 无法查询 MediaStore"

        if (rows.isEmpty()) {
            return buildJsonObject {
                put("count", 0)
                put("note", "没有匹配的照片；bucket 名可先用 album_stats 查")
            }.toString()
        }
        return buildJsonObject {
            put("count", rows.size)
            putJsonArray("photos") { for (r in rows) add(buildJsonObject {
                for ((k, v) in r) put(k, v)
            }) }
        }.toString()
    }

    /**
     * 缩略图：先走 BitmapFactory 直接解码；解不出来（HEIC/RAW/云相册未下载等
     * 平台能解码但 Skia 直接 decodeByteArray 失败的图）走 MediaStore.loadThumbnail
     * 平台管线兜底（API 29+，缩略图由 media server 生成且已转正）。两者都失败返回 null。
     */
    private fun decodeThumbnail(uri: Uri, maxPx: Int): String? =
        decodeViaBitmapFactory(uri, maxPx) ?: mediaStoreThumbnail(uri, maxPx)

    private fun decodeViaBitmapFactory(uri: Uri, maxPx: Int): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(1)
        val upright = if (orientation in 2..8) {
            val matrix = android.graphics.Matrix()
            when (orientation) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                6 -> matrix.setRotate(90f)
                7 -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                8 -> matrix.setRotate(270f)
            }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        val out = ByteArrayOutputStream()
        upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        upright.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    /** BitmapFactory 解不出来时的平台管线兜底；API < 29 或仍失败返回 null。 */
    private fun mediaStoreThumbnail(uri: Uri, maxPx: Int): String? = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@runCatching null
        val bmp = context.contentResolver.loadThumbnail(uri, android.util.Size(maxPx, maxPx), null)
        try {
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
    }.getOrNull()
}

class AlbumOpenTool(private val context: Context) : Tool {
    override val name = "album_open"
    override val description =
        "在系统图库/看图应用中打开一张照片（按相册 id 或 content:// uri）。只查看不修改。"

    override val parameters = schemaOf(
        "id" to strParam("相册照片 id（album_photos 返回的 id）"),
        "uri" to strParam("或直接给 content:// uri"),
    )

    override suspend fun execute(args: JsonObject): String {
        val id = args.strArg("id")
        val uriStr = args.strArg("uri")
        val uri = when {
            !uriStr.isNullOrBlank() -> Uri.parse(uriStr)
            !id.isNullOrBlank() ->
                Uri.withAppendedPath(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
            else -> return "ERROR: 缺少 id 或 uri"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "OPENED: 已在图库中打开照片（$uri）"
        } catch (e: Exception) {
            "ERROR: 无法打开图库（${e.message ?: e.javaClass.simpleName}）"
        }
    }
}

// helper: bool from string (Tool.kt helpers only expose strArg/intArg/longArg/boolArg—reuse boolArg)
private fun JsonObject.boolArg2(key: String): String? = strArg(key)

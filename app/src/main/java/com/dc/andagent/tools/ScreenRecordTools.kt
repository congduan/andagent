package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen recording through MediaProjection only (R28). One system consent
 * dialog replaces the previous shell-uid `screenrecord` backend (R107).
 */
class ScreenRecordTool(private val context: Context) : Tool {
    override val name = "screen_record"
    override val description =
        "开始录屏（默认 MP4 到 Movies/AndAgent）。使用 MediaProjection：首次会弹出一次系统授权，之后全自动；" +
            "duration 秒，默认 30，上限 300。立即返回文件路径；可随时调用 screen_record_stop 提前结束。"
    override val parameters = schemaOf(
        "duration" to intParam("录制秒数，默认 30，最大 300"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val duration = (args.intArg("duration") ?: 30).coerceIn(1, 300)
        when (ensureCaptureChannel(context)) {
            CaptureChannel.PROJECTION ->
                projectionStartRecording(context, duration, 8_000_000)
            CaptureChannel.NEED_CONSENT ->
                "ERROR: " + tr(
                    "屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）",
                    "screen capture not granted: tap \"Start now\" in the system prompt and retry (or grant it in Settings → Screen capture)",
                )
        }
    }
}

class ScreenRecordStopTool(private val context: Context) : Tool {
    override val name = "screen_record_stop"
    override val description = "停止正在进行的录屏（MediaProjection），并让系统媒体库扫描已保存的视频。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        projectionStopRecording(context)?.let { return it }
        return tr("没有正在进行的录屏", "No recording in progress")
    }
}

class ScreenRecordListTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "screen_record_list"
    override val description = "列出已录制的屏幕视频（Movies/AndAgent/andagent_*.mp4），返回文件名、大小、时长与时间。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val read = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        runCatching { permissions.ensure(listOf(read)) }

        val rows = runCatching { queryMediaStore() }.getOrNull()
        if (!rows.isNullOrEmpty()) {
            return buildJsonObject {
                put("count", rows.size)
                put("items", buildJsonArray {
                    for (row in rows.take(50)) {
                        add(buildJsonObject {
                            put("name", row.name)
                            put("path", row.path)
                            put("sizeMB", "%.1f".format(row.size / 1e6))
                            put("sizeBytes", row.size)
                            put("durationSeconds", row.durationMs / 1000)
                            put("modified", row.modified)
                        })
                    }
                })
            }.toString()
        }
        return tr("没有找到录屏文件（Movies/AndAgent/andagent_*.mp4）", "No screen recordings found (Movies/AndAgent/andagent_*.mp4)")
    }

    private data class Row(
        val name: String,
        val path: String,
        val size: Long,
        val durationMs: Long,
        val modified: String,
    )

    private fun queryMediaStore(): List<Row> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val rows = mutableListOf<Row>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("andagent_%"),
            "${MediaStore.Video.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDuration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iModified = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            while (c.moveToNext()) {
                val rel = c.getString(iRel) ?: "Movies/"
                val name = c.getString(iName) ?: continue
                rows.add(
                    Row(
                        name = name,
                        path = "/sdcard/$rel$name",
                        size = c.getLong(iSize),
                        durationMs = c.getLong(iDuration),
                        modified = stamp.format(Date(c.getLong(iModified) * 1000)),
                    ),
                )
            }
        }
        return rows
    }
}

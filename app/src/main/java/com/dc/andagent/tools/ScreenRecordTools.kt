package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import com.dc.andagent.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen recording through Shizuku's shell uid: no MediaProjection consent
 * dialog, the recording is written by `screenrecord` directly.
 *
 * All three tools degrade to a Chinese error string when Shizuku is not
 * granted; Android < 12 also gets a clear "not supported" message.
 */

private object ActiveRecordings {
    val paths = ConcurrentHashMap.newKeySet<String>()
}

private fun scanRecordings(context: Context, paths: Collection<String>) {
    if (paths.isEmpty()) return
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                paths.toTypedArray(),
                arrayOf("video/mp4"),
                null,
            )
        }
    }, 1_500)
}

class ScreenRecordTool(
    private val context: Context,
    private val shizuku: ShizukuShell,
) : Tool {
    override val name = "screen_record"
    override val description =
        "开始录屏（默认 MP4 到 Movies/AndAgent）。优先 MediaProjection（一次系统弹窗授权后全自动，Android 8+ 可用）；" +
            "未授权且有 Shizuku 时以 shell 身份调用 screenrecord（需 Android 12+）。" +
            "duration 秒，默认 30，上限 300。立即返回文件路径；可随时调用 screen_record_stop 提前结束。"
    override val parameters = schemaOf(
        "duration" to intParam("录制秒数，默认 30，最大 300"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val duration = (args.intArg("duration") ?: 30).coerceIn(1, 300)
        when (ensureCaptureChannel(context, shizuku)) {
            CaptureChannel.PROJECTION ->
                projectionStartRecording(context, duration, 8_000_000)
            CaptureChannel.NEED_CONSENT ->
                "ERROR: " + tr(
                    "屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）",
                    "screen capture not granted: tap \"Start now\" in the system prompt and retry (or grant it in Settings → Screen capture)",
                )
            CaptureChannel.SCRCPY -> shizukuRecord(duration)
        }
    }

    /** Legacy shizuku screenrecord path (Android 12+, shell uid). */
    private fun shizukuRecord(duration: Int): String {
        if (Build.VERSION.SDK_INT < 31) {
            return "ERROR: " + tr(
                "系统版本过低：screenrecord 录屏需要 Android 12（API 31）及以上；或先授权屏幕捕获（MediaProjection）",
                "Android version too old: screenrecord needs Android 12 (API 31) or later; or grant screen capture (MediaProjection) first",
            )
        }
        if (!shizuku.isGranted()) return "ERROR: ${ShizukuShell.SHIZUKU_REQUIRED}"

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val path = "/sdcard/Movies/andagent_$stamp.mp4"
        val command = "screenrecord --time-limit $duration $path"

        val earlyExit = AtomicReference<Pair<Int, String>?>(null)
        val process = shizuku.start(command) { code, output ->
            earlyExit.set(code to output)
            ActiveRecordings.paths.remove(path)
            scanRecordings(context, listOf(path))
        } ?: return "ERROR: " + tr(
            "无法启动录屏进程：Shizuku 授权状态可能已失效，请到设置页刷新",
            "could not start the recording process: Shizuku access may have expired; refresh it in Settings",
        )

        ActiveRecordings.paths.add(path)
        // Catch immediate failures (permission denied, unknown option, busy
        // encoder) before telling the user recording has started.
        Thread.sleep(700)
        earlyExit.get()?.let { (code, output) ->
            ActiveRecordings.paths.remove(path)
            return "ERROR: " + tr(
                "录屏启动失败（exit=$code）：${output.take(800).ifBlank { "无输出" }}",
                "recording failed to start (exit=$code): ${output.take(800).ifBlank { "no output" }}",
            )
        }

        return buildJsonObject {
            put("started", true)
            put("path", path)
            put("durationSeconds", duration)
            put(
                "message",
                tr(
                    "录屏已开始：$path，时长 ${duration}s。可调用 screen_record_stop 提前停止，或 screen_record_list 查看。",
                    "Recording started: $path (${duration}s). Call screen_record_stop to stop early, or screen_record_list to view it.",
                ),
            )
            put("pid", runCatching { process.hashCode() }.getOrDefault(0))
        }.toString()
    }
}

class ScreenRecordStopTool(private val context: Context, private val shizuku: ShizukuShell) : Tool {
    override val name = "screen_record_stop"
    override val description = "停止正在进行的录屏（MediaProjection 或 Shizuku screenrecord），并让系统媒体库扫描已保存的视频。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        // Projection recording first (tracker is authoritative for that path).
        projectionStopRecording(context)?.let { return it }
        if (!shizuku.isGranted()) return "ERROR: ${ShizukuShell.SHIZUKU_REQUIRED}"
        val result = shizuku.run("pkill -INT screenrecord")
        val pending = ActiveRecordings.paths.toList()
        ActiveRecordings.paths.clear()
        scanRecordings(context, pending)
        if (!result.ok && result.stdout.isBlank() && result.stderr.isBlank()) {
            return tr("没有正在进行的录屏（screenrecord 进程不存在）", "No recording in progress (no screenrecord process)")
        }
        return buildString {
            append(tr("已发送停止信号（exit=${result.exitCode}）", "Stop signal sent (exit=${result.exitCode})"))
            if (pending.isNotEmpty()) {
                append(tr("\n文件：", "\nFile: "))
                append(pending.joinToString(", "))
                append(tr("\n媒体库扫描已安排，稍后可在相册看到。", "\nMedia library scan scheduled; the video will appear in your gallery shortly."))
            }
            if (result.stderr.isNotBlank()) append("\n[stderr] ").append(result.stderr)
        }
    }
}

class ScreenRecordListTool(
    private val context: Context,
    private val shizuku: ShizukuShell,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "screen_record_list"
    override val description = "列出已录制的屏幕视频（/sdcard/Movies/andagent_*.mp4），返回文件名、大小、时长与时间。"
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

        // MediaStore may not have indexed a fresh recording yet; ask the shell.
        if (shizuku.isGranted()) {
            val listing = shizuku.run("ls -ln /sdcard/Movies/andagent_*.mp4 2>/dev/null | tail -n 50")
            if (listing.ok && listing.stdout.isNotBlank()) {
                return "recordings (shell):\n${listing.stdout}"
            }
        }
        return tr("没有找到录屏文件（/sdcard/Movies/andagent_*.mp4）", "No screen recordings found (/sdcard/Movies/andagent_*.mp4)")
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

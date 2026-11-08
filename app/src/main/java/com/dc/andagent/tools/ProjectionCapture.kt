package com.dc.andagent.tools

import com.dc.andagent.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dc.andagent.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * R28: MediaProjection capture path — screenshot / recording that needs only
 * the one-time system consent dialog (no external privilege backend).
 *
 * Flow: ProjectionConsentActivity (trampoline, same pattern as
 * FilePickActivity) collects resultCode+data → ProjectionService (foreground,
 * type=mediaProjection) is started and consumes the consent → tools use the
 * held MediaProjection for VirtualDisplay capture until the user stops it.
 *
 * On Android 14+ getMediaProjection() must run AFTER
 * startForeground(type=MEDIA_PROJECTION), so the pending consent is stored
 * statically and consumed inside the service — never kept as a raw token and
 * re-materialized lazily (SecurityException).
 *
 * The pure-logic parts ([ProjectionRouting], [ProjectionRecordings]) are
 * unit-testable without Android.
 */

/* ------------------------- pure logic (unit tested) --------------------- */

/** Which implementation a screen tool should use right now. */
enum class CaptureChannel { PROJECTION, NEED_CONSENT }

/**
 * Routing order per R28 brief: authorized MediaProjection first; otherwise
 * trigger the consent flow.
 */
object ProjectionRouting {
    fun route(projectionReady: Boolean): CaptureChannel =
        if (projectionReady) CaptureChannel.PROJECTION else CaptureChannel.NEED_CONSENT
}

/**
 * Tracks the single active projection recording. Pure class so the start /
 * stop / idempotency rules can be asserted in a JVM test.
 */
class ProjectionRecordings {
    data class Active(val path: String, val pendingUri: String?)

    @Volatile
    private var current: Active? = null
    private val lock = Any()

    /** Returns false when a recording is already running. */
    fun start(path: String, pendingUri: String?): Boolean = synchronized(lock) {
        if (current != null) return false
        current = Active(path, pendingUri)
        true
    }

    /** The stopped recording, or null when nothing was running. */
    fun stop(): Active? = synchronized(lock) {
        val old = current
        current = null
        old
    }

    fun active(): Active? = synchronized(lock) { current }
}

/* --------------------------- consent handshake -------------------------- */

/** Static bridge between the consent activity and the blocked tool thread. */
object ProjectionConsentBridge {
    const val EXTRA_REQUEST_ID = "requestId"

    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, Boolean>()

    fun newRequest(): String {
        val id = java.util.UUID.randomUUID().toString()
        pending[id] = CountDownLatch(1)
        return id
    }

    fun report(id: String, granted: Boolean) {
        results[id] = granted
        pending.remove(id)?.countDown()
    }

    /** Await used from tool executor threads; false on timeout or denial. */
    fun await(id: String, timeoutMs: Long): Boolean {
        val latch = pending[id] ?: return results[id] == true
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) results[id] == true else false
    }
}

/**
 * Transparent trampoline that shows the system MediaProjection consent
 * dialog and reports the answer. On OK it also starts the foreground
 * service, which consumes the consent on Android 14+ before
 * getMediaProjection().
 */
class ProjectionConsentActivity : android.app.Activity() {
    companion object {
        const val REQUEST_CONSENT = 4301

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, ProjectionConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ProjectionConsentBridge.EXTRA_REQUEST_ID, requestId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CONSENT)
        } catch (_: Exception) {
            finishWith(false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT) return
        val granted = resultCode == RESULT_OK && data != null
        if (granted) ProjectionService.acceptConsent(this, resultCode, data!!)
        finishWith(granted)
    }

    private fun finishWith(granted: Boolean) {
        getIntent()?.getStringExtra(ProjectionConsentBridge.EXTRA_REQUEST_ID)?.let {
            ProjectionConsentBridge.report(it, granted)
        }
        finish()
    }
}

/* ---------------------------- foreground service ------------------------ */

/**
 * Foreground service (type mediaProjection) holding the live MediaProjection
 * so capture tools can reuse it without a new consent dialog. The user can
 * stop it any time (settings card / tool); the system cast-stop flows in
 * through the projection callback and tears the service down.
 */
class ProjectionService : Service() {

    companion object {
        const val CHANNEL_ID = "andagent_projection"
        const val NOTIFICATION_ID = 4302

        // Pending consent, consumed once the foreground state is up.
        @Volatile
        private var pendingResultCode: Int? = null
        @Volatile
        private var pendingData: Intent? = null

        @Volatile
        var instance: ProjectionService? = null
            private set

        /** True when a live MediaProjection is held by the running service. */
        val ready: Boolean
            get() = instance?.projection != null

        /** Store the consent and bring up the foreground service. */
        fun acceptConsent(context: Context, resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingData = data
            start(context)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ProjectionService::class.java),
            )
        }

        fun stop(context: Context) {
            instance?.stopProjection()
            context.stopService(Intent(context, ProjectionService::class.java))
        }
    }

    var projection: MediaProjection? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startProjectionForeground()
        consumePending()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        consumePending()
        return START_NOT_STICKY
    }

    private fun consumePending() {
        val code = pendingResultCode
        val data = pendingData
        if (code != null && data != null) {
            pendingResultCode = null
            pendingData = null
            createProjection(code, data)
        }
    }

    private fun startProjectionForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("屏幕捕获", "Screen capture"), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(tr("AndAgent 屏幕捕获", "AndAgent screen capture"))
            .setContentText(tr("截屏 / 录屏 / 系统内录通道已就绪，可在设置中停止。", "Screenshot / recording / system-audio channel ready; stop it in Settings."))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection?.stop()
            val mediaProjection = manager.getMediaProjection(resultCode, data)
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    projection = null
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mediaProjection
        } catch (_: Exception) {
            projection = null
            stopSelf()
        }
    }

    fun stopProjection() {
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        stopProjection()
        if (instance === this) instance = null
        super.onDestroy()
    }
}

/* ------------------------------ capture APIs ---------------------------- */

private fun displayRotationDegrees(rotation: Int): Float = when (rotation) {
    android.view.Surface.ROTATION_90 -> 90f
    android.view.Surface.ROTATION_180 -> 180f
    android.view.Surface.ROTATION_270 -> 270f
    else -> 0f
}

private fun thumbnailUnder200k(jpeg: ByteArray): ByteArray {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return ByteArray(0)
    val scaled = Bitmap.createScaledBitmap(
        bitmap, minOf(640, bitmap.width),
        (bitmap.height * minOf(640, bitmap.width) / bitmap.width).coerceAtLeast(1), true,
    )
    var quality = 75
    var bytes: ByteArray
    do {
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bytes = out.toByteArray()
        quality -= 10
    } while (bytes.size > 200_000 && quality >= 25)
    scaled.recycle()
    bitmap.recycle()
    return bytes
}

private fun saveToPictures(context: Context, jpeg: ByteArray, name: String): String? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AndAgent")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: return null
        "/sdcard/Pictures/AndAgent/$name"
    } else {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AndAgent")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(jpeg)
        file.absolutePath
    }
}.getOrNull()

/**
 * Screenshot through the live projection: mirror the main display into an
 * RGBA ImageReader, take one frame, rotate per display rotation, save JPEG
 * to MediaStore Pictures/AndAgent (pre-29: direct file) and return the
 * screen_capture protocol (path + thumbnailBase64 + mime).
 */
suspend fun projectionCaptureFrame(context: Context): String = withContext(Dispatchers.IO) {
    val projection = ProjectionService.instance?.projection
        ?: return@withContext "ERROR: " + tr("MediaProjection 未就绪", "MediaProjection is not ready")
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    val display = windowManager.defaultDisplay
    val real = android.graphics.Point()
    @Suppress("DEPRECATION")
    display.getRealSize(real)
    @Suppress("DEPRECATION")
    val degrees = displayRotationDegrees(display.rotation)
    val metrics = context.resources.displayMetrics

    val reader = ImageReader.newInstance(real.x, real.y, PixelFormat.RGBA_8888, 2)
    var virtual: VirtualDisplay? = null
    try {
        val imageRef = AtomicReference<Image?>(null)
        reader.setOnImageAvailableListener({ r ->
            if (imageRef.get() == null) {
                runCatching { r.acquireLatestImage() }.getOrNull()?.let { imageRef.set(it) }
            }
        }, Handler(Looper.getMainLooper()))
        virtual = projection.createVirtualDisplay(
            "andagent-shot", real.x, real.y, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
        val deadline = System.currentTimeMillis() + 6_000
        var image: Image? = null
        while (System.currentTimeMillis() < deadline) {
            image = imageRef.getAndSet(null)
            if (image != null) break
            Thread.sleep(60)
        }
        if (image == null) return@withContext "ERROR: " + tr("屏幕帧未到达（display 镜像超时）", "no screen frame arrived (display mirroring timed out)")
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (cropped !== padded) padded.recycle()
            val oriented = if (degrees != 0f) {
                val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
            } else {
                cropped
            }
            val jpeg = ByteArrayOutputStream().also { out ->
                oriented.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }.toByteArray()
            if (oriented !== cropped) oriented.recycle()
            cropped.recycle()
            val name = "andagent_proj_${System.currentTimeMillis()}.jpg"
            val path = saveToPictures(context, jpeg, name)
                ?: return@withContext "ERROR: " + tr("无法保存截屏文件", "could not save the screenshot file")
            buildJsonObject {
                put("path", path)
                put("thumbnailBase64", Base64.encodeToString(thumbnailUnder200k(jpeg), Base64.NO_WRAP))
                put("mime", "image/jpeg")
                put("channel", "projection")
            }.toString()
        } finally {
            image.close()
        }
    } catch (e: Exception) {
        "ERROR: " + tr(
                "MediaProjection 截屏失败：${e.message ?: e.javaClass.simpleName}",
                "MediaProjection screenshot failed: ${e.message ?: e.javaClass.simpleName}",
            )
    } finally {
        runCatching { virtual?.release() }
        runCatching { reader.close() }
    }
}

/* ------------------------------ recording ------------------------------- */

/** Singleton tracker used by the record/stop tools. */
private val projectionRecordings = ProjectionRecordings()

/**
 * Resolve the capture channel per routing order and provision it:
 * projection ready → PROJECTION; otherwise show the system consent dialog and
 * wait (blocking, tool executor threads only). The wait stays under the
 * WebView 60s tool timeout; if the user grants later, the next call routes to
 * PROJECTION automatically.
 */
suspend fun ensureCaptureChannel(context: Context): CaptureChannel {
    val channel = ProjectionRouting.route(ProjectionService.ready)
    if (channel != CaptureChannel.NEED_CONSENT) return channel
    val id = ProjectionConsentBridge.newRequest()
    try {
        context.startActivity(ProjectionConsentActivity.intent(context, id))
    } catch (_: Exception) {
        ProjectionConsentBridge.report(id, false)
        return CaptureChannel.NEED_CONSENT
    }
    return if (ProjectionConsentBridge.await(id, 55_000)) CaptureChannel.PROJECTION
    else CaptureChannel.NEED_CONSENT
}

fun projectionRecordingActive(): Boolean = projectionRecordings.active() != null

/** Status JSON for the settings card (R28). */
@Suppress("UNUSED_PARAMETER")
fun projectionStatusJson(context: Context): String = buildJsonObject {
    val ready = ProjectionService.ready
    put("channel", if (ready) "projection" else "none")
    put("ready", ready)
    put("recording", projectionRecordingActive())
    put("androidSdk", Build.VERSION.SDK_INT)
    put("notification", if (ProjectionService.instance != null) "active" else "stopped")
}.toString()

private object ActiveRecorder {
    var recorder: MediaRecorder? = null
    var virtual: VirtualDisplay? = null
    var pfd: android.os.ParcelFileDescriptor? = null
}

private val mainHandler = Handler(Looper.getMainLooper())
private var autoStopRunnable: Runnable? = null

/**
 * Start an MP4 recording via MediaRecorder + VirtualDisplay. Returns
 * "started" JSON immediately; screen_record_stop (or the duration timer)
 * stops it.
 */
suspend fun projectionStartRecording(context: Context, durationS: Int, bitRate: Int): String =
    withContext(Dispatchers.IO) {
        val projection = ProjectionService.instance?.projection
            ?: return@withContext "ERROR: " + tr("MediaProjection 未就绪", "MediaProjection is not ready")
        if (projectionRecordings.active() != null) {
            return@withContext "ERROR: " + tr(
            "已有进行中的投影录屏，请先调用 screen_record_stop",
            "a projection recording is already running; call screen_record_stop first",
        )
        }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val display = windowManager.defaultDisplay
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(real)
        @Suppress("DEPRECATION")
        val orientationDegrees = when (display.rotation) {
            android.view.Surface.ROTATION_90 -> 270
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 90
            else -> 0
        }
        // Encoders want even dimensions; cap the long edge at 1280.
        var w = real.x
        var h = real.y
        if (maxOf(w, h) > 1280) {
            val scale = 1280f / maxOf(w, h)
            w = (w * scale).toInt() and 0x7FFFFFFE
            h = (h * scale).toInt() and 0x7FFFFFFE
        } else {
            w = w and 0x7FFFFFFE
            h = h and 0x7FFFFFFE
        }
        val name = "andagent_proj_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        var uri: android.net.Uri? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            val recorder =
                if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                else @Suppress("DEPRECATION") MediaRecorder()
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(bitRate.coerceIn(1_000_000, 20_000_000))
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(w, h)
            recorder.setOrientationHint(orientationDegrees)
            val path: String
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AndAgent")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error(tr("无法创建 Movies 视频", "could not create the Movies video"))
                pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                    ?: error(tr("无法打开视频文件", "could not open the video file"))
                recorder.setOutputFile(pfd.fileDescriptor)
                path = "/sdcard/Movies/AndAgent/$name"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "AndAgent",
                )
                dir.mkdirs()
                val file = File(dir, name)
                recorder.setOutputFile(file.absolutePath)
                path = file.absolutePath
            }
            recorder.prepare()
            val virtual = projection.createVirtualDisplay(
                "andagent-record", w, h, context.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.surface, null, null,
            )
            recorder.start()
            if (!projectionRecordings.start(path, uri?.toString())) {
                error(tr("已有进行中的投影录屏", "a projection recording is already running"))
            }
            ActiveRecorder.recorder = recorder
            ActiveRecorder.virtual = virtual
            ActiveRecorder.pfd = pfd
            autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
            val task = Runnable { runCatching { projectionStopRecording(context) } }
            autoStopRunnable = task
            mainHandler.postDelayed(task, durationS * 1000L)
            buildJsonObject {
                put("started", true)
                put("path", path)
                put("durationSeconds", durationS)
                put("channel", "projection")
                put(
                    "message",
                    tr(
                        "录屏已开始：$path，时长 ${durationS}s。可调用 screen_record_stop 提前停止，或 screen_record_list 查看。",
                        "Recording started: $path (${durationS}s). Call screen_record_stop to stop early, or screen_record_list to view it.",
                    ),
                )
            }.toString()
        } catch (e: Exception) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            runCatching { pfd?.close() }
            releaseRecorder()
            projectionRecordings.stop()
            "ERROR: " + tr(
                "MediaProjection 录屏启动失败：${e.message ?: e.javaClass.simpleName}",
                "MediaProjection recording failed to start: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

private fun releaseRecorder() {
    runCatching { ActiveRecorder.recorder?.stop() }
    runCatching { ActiveRecorder.recorder?.release() }
    runCatching { ActiveRecorder.virtual?.release() }
    runCatching { ActiveRecorder.pfd?.close() }
    ActiveRecorder.recorder = null
    ActiveRecorder.virtual = null
    ActiveRecorder.pfd = null
}

/**
 * Stop the projection recording; returns a screen_record_stop compatible
 * result string (null when nothing is active).
 */
fun projectionStopRecording(context: Context): String? {
    val active = projectionRecordings.stop() ?: return null
    autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
    autoStopRunnable = null
    val recorder = ActiveRecorder.recorder
    val virtual = ActiveRecorder.virtual
    val pfd = ActiveRecorder.pfd
    ActiveRecorder.recorder = null
    ActiveRecorder.virtual = null
    ActiveRecorder.pfd = null
    var error: String? = null
    try {
        recorder?.stop()
    } catch (e: Exception) {
        error = e.message ?: e.javaClass.simpleName
    } finally {
        runCatching { recorder?.release() }
        runCatching { virtual?.release() }
    }
    val uriText = active.pendingUri
    if (uriText != null) {
        val uri = runCatching { android.net.Uri.parse(uriText) }.getOrNull()
        if (error != null) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            runCatching { pfd?.close() }
            return "ERROR: " + tr(
                "录屏停止失败（$error），已丢弃未完成的视频",
                "stopping the recording failed ($error); the unfinished video was discarded",
            )
        }
        runCatching {
            val uriNonNull = uri ?: return@runCatching
            context.contentResolver.update(
                uriNonNull,
                android.content.ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null, null,
            )
        }
        runCatching { pfd?.close() }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    context, arrayOf(active.path), arrayOf("video/mp4"), null,
                )
            }
        }, 1_500)
    }
    return buildString {
        append(tr("已停止投影录屏\n文件：", "Projection recording stopped\nFile: ")).append(active.path)
        append(tr("\n媒体库扫描已安排，稍后可在相册看到。", "\nMedia library scan scheduled; the video will appear in your gallery shortly."))
    }
}

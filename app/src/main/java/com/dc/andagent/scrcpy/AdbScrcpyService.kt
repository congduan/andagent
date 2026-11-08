package com.dc.andagent.scrcpy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dc.andagent.tools.AdbScrcpyToolSpec
import com.dc.andagent.tools.AdbSessionHub
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class AdbScrcpyService : Service() {
    private var window: FloatingMirrorWindow? = null
    private var target = ""
    private var maxSize = 800
    private var fps = 15
    @Volatile private var generation = 0
    @Volatile private var active: AdbScrcpyToolSpec.SessionHandle? = null
    @Volatile private var worker: Thread? = null
    private var surface: SurfaceHolder? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "adb-mirror-control") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences("adb_mirror", MODE_PRIVATE)
        target = intent?.getStringExtra(EXTRA_TARGET) ?: prefs.getString(EXTRA_TARGET, null).orEmpty()
        maxSize = intent?.getIntExtra(EXTRA_SIZE, 800) ?: prefs.getInt(EXTRA_SIZE, 800)
        fps = intent?.getIntExtra(EXTRA_FPS, 15) ?: prefs.getInt(EXTRA_FPS, 15)
        if (target.isBlank() || !Settings.canDrawOverlays(this)) {
            ready?.countDown()
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(guard) { running = true }
        prefs.edit().putString(EXTRA_TARGET, target).putInt(EXTRA_SIZE, maxSize).putInt(EXTRA_FPS, fps).apply()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "ADB 浮窗投屏", NotificationManager.IMPORTANCE_LOW),
            )
            val stop = PendingIntent.getService(
                this, 0, Intent(this, AdbScrcpyService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("ADB 投屏中：$target")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
                .build()
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ) else startForeground(NOTIFICATION_ID, notification)
            if (window == null) {
                window = FloatingMirrorWindow(
                    this, ::onSurfaceReady, ::onSurfaceGone, ::reconnect,
                    { stopSelf() }, ::sendControl,
                ).also { it.show() }
            }
            synchronized(guard) { visible = true }
            ready?.countDown()
        } catch (e: Exception) {
            synchronized(guard) { visible = false }
            ready?.countDown()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun onSurfaceReady(holder: SurfaceHolder) {
        surface = holder
        reconnect()
    }

    private fun onSurfaceGone() {
        surface = null
        cancelWorker()
    }

    private fun cancelWorker() {
        generation++
        active?.client?.close() // interrupts a blocking packet read; worker owns session.stop/codec.release
        worker?.interrupt()
    }

    private fun reconnect() {
        val holder = surface ?: return
        if (!holder.surface.isValid) return
        cancelWorker()
        val token = generation
        window?.showConnecting()
        worker = Thread({ stream(holder, token) }, "adb-mirror-video").also { it.start() }
    }

    private fun sendControl(bytes: ByteArray) {
        val token = generation
        val handle = active ?: return
        if (controlWriter.isShutdown) return
        controlWriter.execute {
            if (token != generation || active !== handle || !handle.session.controlAvailable) return@execute
            if (!handle.session.injectControl(bytes)) {
                Log.w("AdbScrcpyService", "control socket disconnected; video continues in view-only mode")
                mainHandler.post { if (token == generation) window?.setControl(false, handle.session.width, handle.session.height) }
            }
        }
    }

    private fun stream(holder: SurfaceHolder, token: Int) {
        var handle: AdbScrcpyToolSpec.SessionHandle? = null
        var codec: MediaCodec? = null
        try {
            AdbSessionHub.init(applicationContext)
            handle = try {
                AdbScrcpyToolSpec.openSession(applicationContext, target, maxSize, 4_000_000, fps, control = true)
            } catch (e: Exception) {
                if (!e.message.orEmpty().contains("CONTROL_SOCKET_FAILED")) throw e
                Log.w("AdbScrcpyService", "control socket unavailable; retrying view-only session", e)
                AdbScrcpyToolSpec.openSession(applicationContext, target, maxSize, 4_000_000, fps)
            }
            if (token != generation || !holder.surface.isValid) return
            active = handle
            val session = handle.session
            mainHandler.post {
                if (token == generation) window?.setControl(session.controlAvailable, session.width, session.height)
            }
            // scrcpy sends codec config before video. Read SPS/PPS before configuring the decoder.
            var csd: Pair<ByteArray, ByteArray>? = null
            while (token == generation && csd == null) {
                val packet = session.nextPacket()
                if (packet.config) csd = AdbScrcpyToolSpec.findSpsPps(packet.bytes)
            }
            if (token != generation || !holder.surface.isValid || csd == null) return
            val format = MediaFormat.createVideoFormat("video/avc", session.width, session.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
            }
            codec = MediaCodec.createDecoderByType("video/avc")
            codec.configure(format, holder.surface, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            while (token == generation && holder.surface.isValid) {
                val packet = session.nextPacket()
                if (packet.config) continue
                var input = codec.dequeueInputBuffer(500_000)
                while (input < 0 && token == generation) {
                    drain(codec, info)
                    input = codec.dequeueInputBuffer(500_000)
                }
                if (input < 0) break
                codec.getInputBuffer(input)!!.apply { clear(); put(packet.bytes) }
                codec.queueInputBuffer(input, 0, packet.bytes.size, packet.pts, 0)
                if (drain(codec, info) && token == generation) mainHandler.post { if (token == generation) window?.showVideo() }
            }
        } catch (_: Exception) {
            // Socket EOF, decoder errors and server exits all offer manual reconnect.
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (active === handle) active = null
            handle?.close()
            if (token == generation) mainHandler.post { if (token == generation) window?.showDisconnected() }
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        var rendered = false
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return rendered
            codec.releaseOutputBuffer(index, true)
            rendered = true
        }
    }

    override fun onDestroy() {
        cancelWorker()
        controlWriter.shutdownNow()
        window?.remove()
        window = null
        getSharedPreferences("adb_mirror", MODE_PRIVATE).edit().clear().apply()
        synchronized(guard) { running = false; visible = false; ready?.countDown(); ready = null }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "adb_mirror"
        private const val NOTIFICATION_ID = 9331
        private const val ACTION_STOP = "com.dc.andagent.scrcpy.STOP"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_SIZE = "max_size"
        private const val EXTRA_FPS = "fps"
        private val guard = Any()
        private var running = false
        @Volatile private var visible = false
        private var ready: CountDownLatch? = null

        fun start(context: Context, target: String, maxSize: Int, fps: Int): String {
            AdbSessionHub.init(context.applicationContext)
            if (AdbSessionHub.target(target) == null) return "ERROR: 目标不可达：$target（先用 adb_connect 登记）"
            if (!Settings.canDrawOverlays(context)) {
                val uri = Uri.parse("package:${context.packageName}")
                val settings = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(settings) }
                return "ERROR: 缺少悬浮窗权限；请在打开的系统设置中允许 AndAgent 显示在其他应用上层，然后重试"
            }
            val latch: CountDownLatch
            synchronized(guard) {
                if (running) return "已在投屏中"
                running = true
                latch = CountDownLatch(1)
                ready = latch
            }
            return try {
                context.startForegroundService(Intent(context, AdbScrcpyService::class.java).apply {
                    putExtra(EXTRA_TARGET, target); putExtra(EXTRA_SIZE, maxSize); putExtra(EXTRA_FPS, fps)
                })
                latch.await(5, TimeUnit.SECONDS)
                synchronized(guard) { ready = null }
                if (visible) "已显示投屏浮窗：$target（正在连接视频）" else "ERROR: 投屏服务未能启动"
            } catch (e: Exception) {
                synchronized(guard) { running = false; ready = null }
                "ERROR: 启动投屏失败：${e.message}"
            }
        }

        fun stop(context: Context): String {
            synchronized(guard) { if (!running) return "当前没有投屏" }
            context.startService(Intent(context, AdbScrcpyService::class.java).setAction(ACTION_STOP))
            return "已停止投屏"
        }
    }
}

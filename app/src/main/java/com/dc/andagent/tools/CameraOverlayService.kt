package com.dc.andagent.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.hardware.display.DisplayManager
import com.dc.andagent.ui.FloatingDragHelper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dc.andagent.MainActivity
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Validated in dp before any WindowManager operation. */
internal data class CameraOverlaySpec(val camera: String, val widthDp: Int, val heightDp: Int, val x: Int, val y: Int) {
    init {
        require(camera == "front" || camera == "back") { "camera 必须是 front 或 back" }
        require(widthDp in 160..1000 && heightDp in 120..1000) { "宽高范围分别为 160..1000 和 120..1000 dp" }
        require(x in -2000..2000 && y in -2000..2000) { "x/y 范围为 -2000..2000 dp" }
    }
}

internal class CameraStartGate {
    private var foreground = false
    fun foregroundStarted() { foreground = true }
    fun requireForeground() { check(foreground) { "必须先启动相机前台服务" } }
}

internal fun overlayPermissionError(canDraw: Boolean): String? =
    if (canDraw) null else "ERROR: 尚未授予悬浮窗权限；已打开授权页。授权后重试，或调用 open_settings(panel=overlay)"

internal fun CameraOverlaySpec.withArgs(args: JsonObject): CameraOverlaySpec {
    fun number(key: String, fallback: Int): Int = args.strArg(key)?.toIntOrNull()
        ?: if (args.containsKey(key)) throw IllegalArgumentException("$key 必须是整数") else fallback
    return CameraOverlaySpec(args.strArg("camera") ?: camera, number("width_dp", widthDp),
        number("height_dp", heightDp), number("x", x), number("y", y))
}

/** Foreground is established in onCreate, before any Camera2 call. */
class CameraOverlayService : Service() {
    companion object {
        private const val CHANNEL = "andagent_camera_overlay"
        private const val NOTICE = 4401
        private const val HIDE = "com.dc.andagent.CAMERA_OVERLAY_HIDE"
        @Volatile var instance: CameraOverlayService? = null
            private set
        @Volatile var lastError: String? = null
            private set
        fun hide(context: Context) { context.stopService(Intent(context, CameraOverlayService::class.java)) }
        internal fun intent(context: Context, spec: CameraOverlaySpec) = Intent(context, CameraOverlayService::class.java).apply {
            putExtra("camera", spec.camera); putExtra("width", spec.widthDp); putExtra("height", spec.heightDp)
            putExtra("x", spec.x); putExtra("y", spec.y)
        }

        /**
         * 无悬浮窗权限时的应用内模式：相机管线照常运行（Camera2 打开+preview
         * session），但渲染目标不是 WindowManager 浮窗，而是宿主传入的应用内
         * content FrameLayout（decorView 叠加）。inAppContent 非空时 onStartCommand
         * 走应用内挂载分支。
         */
        /** 默认规格：480×360dp，水平居中、y=100dp（专业浮窗默认应出现在视觉中心线附近）。 */
        internal fun defaultSpec(context: Context): CameraOverlaySpec {
            val dm = context.resources.displayMetrics
            val screenWdp = (dm.widthPixels / dm.density).toInt()
            return CameraOverlaySpec("back", 480, 360, ((screenWdp - 480) / 2).coerceAtLeast(0), 100)
        }

        @Volatile var inAppContent: FrameLayout? = null
            private set
        /** internal spec 由服务内部调用（同模块可见性），public 修饰仅为 Kotlin 对象成员默认。 */
        internal fun startInApp(activity: android.app.Activity, content: FrameLayout, spec: CameraOverlaySpec) {
            inAppContent = content
            val ctx = activity.applicationContext
            android.util.Log.i("CameraOverlay", "in-app overlay mode start (${spec.camera})")
            ctx.startService(intent(ctx, spec))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val startGate = CameraStartGate()
    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var windows: WindowManager
    private var root: FrameLayout? = null
    private var texture: TextureView? = null
    private var layout: WindowManager.LayoutParams? = null
    @Volatile private var spec = CameraOverlaySpec("back", 480, 360, 0, 80)
    internal fun specSnapshot(): CameraOverlaySpec = spec
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var generation = 0
    private var dragHelper: FloatingDragHelper? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sensorOrientation = 90
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) { if (displayId == android.view.Display.DEFAULT_DISPLAY) main.post { reapplyPreviewTransform() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        lastError = null
        try {
            startCameraForeground()
            cameraThread = HandlerThread("camera-overlay").apply { start() }
            cameraHandler = Handler(cameraThread.looper)
            windows = getSystemService(WINDOW_SERVICE) as WindowManager
            getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener, main)
        } catch (e: Exception) {
            fail("前台服务启动失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HIDE) { stopSelf(); return START_NOT_STICKY }
        if (!::cameraHandler.isInitialized) { stopSelf(); return START_NOT_STICKY }
        // 应用内模式：无悬浮窗权限也继续（渲染挂在宿主 content，不走 WindowManager）。
        val inApp = inAppContent != null
        if (!inApp && (!Settings.canDrawOverlays(this) || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)) {
            fail("悬浮窗或相机权限已撤销")
            return START_NOT_STICKY
        }
        if (inApp && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("相机权限未授予")
            return START_NOT_STICKY
        }
        val fallback = defaultSpec(this)
        val requested = runCatching { CameraOverlaySpec(
            intent?.getStringExtra("camera") ?: "back", intent?.getIntExtra("width", fallback.widthDp) ?: fallback.widthDp,
            intent?.getIntExtra("height", fallback.heightDp) ?: fallback.heightDp,
            intent?.getIntExtra("x", fallback.x) ?: fallback.x,
            intent?.getIntExtra("y", fallback.y) ?: fallback.y,
        ) }.getOrElse { fail(it.message ?: "参数无效"); return START_NOT_STICKY }
        val cameraChanged = requested.camera != spec.camera
        spec = requested
        runCatching {
            if (inApp) {
                // 应用内模式：TextureView 挂到宿主 content（decorView 叠加），不经 WindowManager。
                val host = inAppContent ?: error("应用内宿主已丢失")
                if (root == null) {
                    root = buildInAppOverlay(host)
                } else updateLayout()
            } else {
                if (root == null) createOverlay() else updateLayout()
            }
            if (cameraChanged || device == null) openWhenReady()
        }.onFailure { fail("浮窗创建失败: ${it.message ?: it.javaClass.simpleName}") }
        return START_NOT_STICKY
    }

    /** 应用内模式：TextureView 挂到宿主 content（decorView 叠加），不经 WindowManager。 */
    private fun buildInAppOverlay(host: FrameLayout): FrameLayout {
        val frame = FrameLayout(this).apply { setBackgroundColor(com.dc.andagent.ui.OverlayChrome.BG) }
        val preview = TextureView(this)
        preview.surfaceTextureListener = previewListener()
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        texture = preview
        root = frame
        // createOverlay 里 layout 的参数由 WindowManager 用；应用内模式无 updateLayout 意义。
        return frame
    }

    /** Reapply after surface, layout, or display rotation changes. Buffer dimensions are the Camera2 output size. */
    private fun reapplyPreviewTransform() {
        val view = texture ?: return
        if (!view.isAvailable || view.width <= 0 || view.height <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
        val transform = previewTransform(view.width, view.height, bufferWidth, bufferHeight,
            sensorOrientation, displayRotationDegrees(), spec.camera == "front")
        view.setTransform(android.graphics.Matrix().apply { setValues(transform.values) })
    }

    /** 默认显示屏旋转角（服务无 Activity，经 DisplayManager 取默认屏）。 */
    private fun displayRotationDegrees(): Int = when (
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
    ) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun startCameraForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "相机浮窗", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, CameraOverlayService::class.java).setAction(HIDE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AndAgent 相机预览").setContentText("相机浮窗正在显示")
            .setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", close).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTICE, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTICE, notice)
        startGate.foregroundStarted()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun createOverlay() {
        val frame = FrameLayout(this).apply {
            elevation = dp(8).toFloat()
            com.dc.andagent.ui.OverlayChrome.applyRounded(this)
            com.dc.andagent.ui.OverlayChrome.background(this)
        }
        val preview = TextureView(this)
        // 统一浮窗外壳（OverlayChrome.Header）：不透明标题栏 + 明显的 ✕ 关闭，与投屏/应用内浮窗一致；
        // 预览区从标题栏下方开始，比例按实际预览区计算。
        val headerRow = com.dc.andagent.ui.OverlayChrome.Header(this, "相机预览") { stopSelf() }
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply {
            topMargin = dp(com.dc.andagent.ui.OverlayChrome.HEADER_DP)
        })
        frame.addView(headerRow, FrameLayout.LayoutParams(-1, dp(com.dc.andagent.ui.OverlayChrome.HEADER_DP), Gravity.TOP))
        headerRow.elevation = dp(12).toFloat()
        // Overlay windows may start at an edge or under a cutout; keep the chrome controls in safe bounds.
        frame.setOnApplyWindowInsetsListener { view, insets ->
            // Type.systemBars()/getInsets(int) 是 API 30+ 接口；minSdk 26 的旧系统直接返回原 insets（无安全区 padding，不崩溃）。
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        frame.requestApplyInsets()
        val resize = android.widget.TextView(this).apply {
            text = "◢"; textSize = 23f; gravity = Gravity.CENTER
            setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT)
            setBackgroundColor(com.dc.andagent.ui.OverlayChrome.HEADER_BG)
            elevation = dp(16).toFloat()
            contentDescription = "调整相机浮窗大小"
        }
        frame.addView(resize, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END))
        val params = WindowManager.LayoutParams(dp(spec.widthDp), dp(spec.heightDp), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = dp(spec.x); y = dp(spec.y)
        }
        dragHelper = FloatingDragHelper(this, windows, frame, params, "camera_${spec.camera}",
            minHeightDp = 120, onChange = {
                val dm = resources.displayMetrics
                spec = spec.copy(widthDp = (params.width / dm.density).toInt().coerceIn(160, 1000),
                    heightDp = (params.height / dm.density).toInt().coerceIn(120, 1000),
                    x = (params.x / dm.density).toInt().coerceIn(-2000, 2000),
                    y = (params.y / dm.density).toInt().coerceIn(-2000, 2000))
                texture?.post { reapplyPreviewTransform() }
            }).also { it.restore(); it.bindDrag(headerRow.title); it.bindResize(resize) }
        windows.addView(frame, params)
        com.dc.andagent.ui.OverlayChrome.enter(frame)
        root = frame; texture = preview; layout = params
        preview.surfaceTextureListener = previewListener()
    }

    /** 系统浮窗与应用内模式共用：surface 就绪/变化时打开相机，销毁时释放。 */
    private fun previewListener() = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { reapplyPreviewTransform(); openWhenReady() }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { reapplyPreviewTransform(); openWhenReady() }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { cameraHandler.post { closeCamera() }; return true }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private fun updateLayout() {
        layout?.let { it.width = dp(spec.widthDp); it.height = dp(spec.heightDp); it.x = dp(spec.x); it.y = dp(spec.y); root?.let { view -> windows.updateViewLayout(view, it) } }
    }

    private fun openWhenReady() {
        startGate.requireForeground()
        val view = texture ?: return
        if (!view.isAvailable) return
        val surfaceTexture = view.surfaceTexture ?: return
        val width = view.width.coerceAtLeast(1); val height = view.height.coerceAtLeast(1)
        val camera = spec.camera
        cameraHandler.post {
            runCatching {
                closeCamera()
                val token = generation
                val manager = getSystemService(CameraManager::class.java)
                val facing = if (camera == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                    ?: error("设备没有${if (camera == "front") "前" else "后"}置摄像头")
                val sizes = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)
                // 传感器旋转补偿：90/270 时缓冲以转置方向显示，按转置后的视图比例匹配
                // （防预览拉伸变形），同比值的候选里再取面积最接近的。
                val rotation = cameraPreviewRotationDegrees(
                    displayRotationDegrees(),
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    camera == "front",
                )
                val viewRatio = width.toDouble() / height
                val targetRatio = if (rotation == 90 || rotation == 270) 1.0 / viewRatio else viewRatio
                val size = sizes?.groupBy { kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) }
                    ?.minByOrNull { it.key }?.value?.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - width.toLong() * height) }
                bufferWidth = size?.width ?: width; bufferHeight = size?.height ?: height
                sensorOrientation = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                surfaceTexture.setDefaultBufferSize(bufferWidth, bufferHeight)
                main.post { reapplyPreviewTransform() }
                val surface = Surface(surfaceTexture)
                previewSurface = surface
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(cameraDevice: CameraDevice) {
                        if (token != generation) { cameraDevice.close(); return }
                        device = cameraDevice
                        runCatching {
                            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    if (token != generation) { captureSession.close(); return }
                                    session = captureSession
                                    runCatching {
                                        val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }.build()
                                        captureSession.setRepeatingRequest(request, null, cameraHandler)
                                    }.onFailure { cameraFailure(it) }
                                }
                                override fun onConfigureFailed(captureSession: CameraCaptureSession) { runCatching { captureSession.close() }; if (token == generation) cameraFailure(IllegalStateException("预览会话配置失败")) }
                            }, cameraHandler)
                        }.onFailure { cameraFailure(it) }
                    }
                    override fun onDisconnected(cameraDevice: CameraDevice) { runCatching { cameraDevice.close() }; if (token == generation) cameraFailure(IllegalStateException("相机已断开")) }
                    override fun onError(cameraDevice: CameraDevice, error: Int) { runCatching { cameraDevice.close() }; if (token == generation) cameraFailure(IllegalStateException("相机错误 $error")) }
                }, cameraHandler)
            }.onFailure { cameraFailure(it) }
        }
    }

    private fun closeCamera() {
        generation++
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { previewSurface?.release() }; previewSurface = null
    }

    private fun cameraFailure(error: Throwable) { main.post { fail("相机预览失败: ${error.message ?: error.javaClass.simpleName}") } }
    private fun fail(message: String) { lastError = message; stopSelf() }

    override fun onDestroy() {
        // 应用内模式：清空宿主引用，防止相机挂在已销毁的 decorView 上继续运行。
        inAppContent = null
        root?.let { r ->
            val parent = r.parent as? ViewGroup
            if (parent != null) parent.removeView(r) else runCatching { windows.removeViewImmediate(r) }
        }
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationListener)
        dragHelper = null
        root = null; texture = null; layout = null
        if (::cameraHandler.isInitialized) { cameraHandler.post { closeCamera(); cameraThread.quitSafely() } }
        if (instance === this) instance = null
        super.onDestroy()
    }
}

class CameraOverlayTool(private val context: Context, private val permissions: PermissionBroker, override val name: String, private val activityProvider: () -> android.app.Activity? = { null }) : Tool {
    override val description = when (name) {
        "camera_overlay_show" -> "在其他应用上层打开原生相机预览浮窗；需悬浮窗和相机授权。"
        "camera_overlay_update" -> "调整相机预览浮窗位置、尺寸或前后摄。"
        else -> "关闭相机浮窗并释放相机。"
    }
    override val parameters = if (name == "camera_overlay_hide") schemaOf() else schemaOf(
        "camera" to strParam("back 或 front"), "width_dp" to intParam("宽度 dp，160..1000；默认 480"),
        "height_dp" to intParam("高度 dp，120..1000；默认 360"), "x" to intParam("左上角 x，dp"), "y" to intParam("左上角 y，dp"),
    )

    override suspend fun execute(args: JsonObject): String {
        if (name == "camera_overlay_hide") {
            CameraOverlayService.hide(context)
            return "HIDDEN: 相机浮窗已关闭"
        }
        val current = CameraOverlayService.instance?.currentSpec()
        if (name == "camera_overlay_update" && current == null) return "ERROR: 相机浮窗未显示；先调用 camera_overlay_show"
        val spec = runCatching { (current ?: CameraOverlayService.defaultSpec(context)).withArgs(args) }
            .getOrElse { return "ERROR: ${it.message}" }
        val overlayError = overlayPermissionError(Settings.canDrawOverlays(context))
        if (overlayError != null) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            // 无悬浮窗权限：降级为应用内伪浮窗（decorView 叠加，仅本 app 内可见）。
            // 相机预览仍真实渲染（TextureView 在 app 内），授权真实浮窗后可无缝升级。
            val activity = activityProvider()
            if (activity != null) {
                // contentHook 挂载相机渲染；onDismiss 在浮窗关闭时停止相机服务。
                com.dc.andagent.tools.InAppOverlayHost.show(
                    activity, "相机预览（应用内浮窗·授权后升级系统级）", spec.widthDp, spec.heightDp, spec.x, spec.y,
                    { content -> CameraOverlayService.startInApp(activity, content, spec) },
                    { CameraOverlayService.hide(activity) },
                )
                return "OK: 无悬浮窗权限，已以应用内伪浮窗显示相机预览（仅本应用内可见）。授权悬浮窗后自动升级系统级浮窗。"
            }
            return overlayError
        }
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val available = runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val facing = if (spec.camera == "front") CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            manager.cameraIdList.any { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
        }
            .getOrElse { return "ERROR: 无法查询摄像头（${it.message ?: it.javaClass.simpleName}）" }
        if (!available) return "ERROR: 设备没有${if (spec.camera == "front") "前" else "后"}置摄像头"
        return runCatching {
            ContextCompat.startForegroundService(context, CameraOverlayService.intent(context, spec))
            buildJsonObject { put("status", "OPENING"); put("camera", spec.camera); put("widthDp", spec.widthDp); put("heightDp", spec.heightDp); put("x", spec.x); put("y", spec.y) }.toString()
        }.getOrElse { "ERROR: 无法启动相机前台服务（${it.message ?: it.javaClass.simpleName}）；请保持 AndAgent 在前台重试" }
    }
}

private fun CameraOverlayService.currentSpec(): CameraOverlaySpec = specSnapshot()

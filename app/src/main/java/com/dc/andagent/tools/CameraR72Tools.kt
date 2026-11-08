package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.TotalCaptureResult
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/* ======================= pure codec helpers (JVM-testable) ======================= */

/**
 * R72: YUV_420_888 → grayscale (luminance) byte array in a camera-orientation
 * agnostic way. Handles both contiguous (rowStride == width) and padded
 * buffers. Pure function: no Android hardware dependency beyond the plain
 * data classes below, so it is unit-testable on the JVM.
 */
internal object CameraScanCodec {

    /** Plain view of a Y plane, decoupled from android.ImageReader for testability. */
    data class YPlane(val data: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** Extract per-pixel luminance into a packed width*height buffer. */
    fun luma(plane: YPlane): ByteArray {
        val out = ByteArray(plane.width * plane.height)
        for (row in 0 until plane.height) {
            val rowStart = row * plane.rowStride
            val outStart = row * plane.width
            if (plane.rowStride == plane.width) {
                System.arraycopy(plane.data, rowStart, out, outStart, plane.width)
            } else {
                for (col in 0 until plane.width) {
                    out[outStart + col] = plane.data[rowStart + col]
                }
            }
        }
        return out
    }

    /** Build a ZXing luminance source straight from the grayscale buffer (no RGB expansion). */
    fun toZxingSource(plane: YPlane): com.google.zxing.LuminanceSource =
        GrayLuminanceSource(luma(plane), plane.width, plane.height)

    /** Map user-facing format names to ZXing BarcodeFormats; unknown names are dropped. */
    fun parseFormats(raw: String?): List<com.google.zxing.BarcodeFormat> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ';', ' ')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .mapNotNull { name ->
                when (name) {
                    "QR", "QRCODE", "QR_CODE" -> com.google.zxing.BarcodeFormat.QR_CODE
                    "EAN13", "EAN_13" -> com.google.zxing.BarcodeFormat.EAN_13
                    "EAN8", "EAN_8" -> com.google.zxing.BarcodeFormat.EAN_8
                    "UPCA", "UPC_A", "UPC" -> com.google.zxing.BarcodeFormat.UPC_A
                    "CODE128", "CODE_128" -> com.google.zxing.BarcodeFormat.CODE_128
                    "CODE39", "CODE_39" -> com.google.zxing.BarcodeFormat.CODE_39
                    "ITF" -> com.google.zxing.BarcodeFormat.ITF
                    "PDF417" -> com.google.zxing.BarcodeFormat.PDF_417
                    "AZTEC" -> com.google.zxing.BarcodeFormat.AZTEC
                    "DATABAR", "DATA_BAR" -> com.google.zxing.BarcodeFormat.RSS_14
                    else -> null
                }
            }
            .distinct()
    }
}

/** Grayscale-direct ZXing source: avoids expanding Y into an RGB int[]. */
internal class GrayLuminanceSource(
    private val luma: ByteArray,
    width: Int,
    height: Int,
    private val left: Int = 0,
    private val top: Int = 0,
    private val dataWidth: Int = width,
    private val dataHeight: Int = height,
) : com.google.zxing.LuminanceSource(width, height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val r = row ?: ByteArray(width)
        System.arraycopy(luma, (top + y) * dataWidth + left, r, 0, width)
        return r
    }

    override fun getMatrix(): ByteArray {
        if (left == 0 && top == 0 && dataWidth == width && dataHeight == height) return luma
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(luma, (top + y) * dataWidth + left, out, y * width, width)
        }
        return out
    }

    override fun crop(left: Int, top: Int, width: Int, height: Int): com.google.zxing.LuminanceSource =
        GrayLuminanceSource(luma, width, height, this.left + left, this.top + top, dataWidth, dataHeight)

    override fun isCropSupported() = true

    override fun rotateCounterClockwise(): com.google.zxing.LuminanceSource {
        val rotated = ByteArray(luma.size)
        for (y in 0 until dataHeight) {
            for (x in 0 until dataWidth) {
                val src = y * dataWidth + x
                val dst = (dataWidth - 1 - x) * dataHeight + y
                rotated[dst] = luma[src]
        }
        }
        return GrayLuminanceSource(rotated, dataHeight, dataWidth)
    }

    override fun isRotateSupported() = true
}

/* ======================= camera2 open/close plumbing ======================= */

/** Blocking camera open used by all three tools. Returns null with reason on failure. */
internal class CameraHandle(
    val device: CameraDevice,
    val characteristics: CameraCharacteristics,
    val thread: HandlerThread,
    val handler: Handler,
) {
    fun close() {
        runCatching { device.close() }
        runCatching { thread.quitSafely() }
    }
}

internal fun resolveCameraId(manager: CameraManager, facing: String): Pair<String, CameraCharacteristics>? {
    val want = if (facing.equals("front", true)) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
    var fallback: Pair<String, CameraCharacteristics>? = null
    for (id in manager.cameraIdList) {
        val c = manager.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_FACING)
        if (f == want) return id to c
        if (f == CameraCharacteristics.LENS_FACING_BACK || f == CameraCharacteristics.LENS_FACING_FRONT) fallback = id to c
    }
    return fallback
}

/**
 * R72: events handed from camera callbacks to the waiting tool coroutine.
 *
 * Wraps the payload so the queue never receives `null` (SynchronousQueue /
 * blocking queues reject null with NPE inside camera callbacks — a crash we
 * must never propagate). `offer(timeout)` is used everywhere so a late
 * callback after the waiter timed out can never park a camera thread forever.
 */
internal data class CamEvent<T>(val value: T?)

/** Offer with a short timeout and swallow rejection (late callback after timeout). */
internal fun <T> java.util.concurrent.BlockingQueue<CamEvent<T>>.post(value: T?) {
    runCatching { offer(CamEvent(value), 2, TimeUnit.SECONDS) }
}

internal fun openCameraBlocking(
    manager: CameraManager,
    cameraId: String,
    timeoutMs: Long = 4000,
): CameraHandle? {
    val thread = HandlerThread("andagent-camera").apply { start() }
    val handler = Handler(thread.looper)
    val q = java.util.concurrent.LinkedBlockingQueue<CamEvent<CameraDevice?>>()
    try {
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { q.post(camera) }
            override fun onDisconnected(camera: CameraDevice) { runCatching { camera.close() }; q.post(null) }
            override fun onError(camera: CameraDevice, error: Int) { runCatching { camera.close() }; q.post(null) }
        }, handler)
    } catch (_: Throwable) {
        thread.quitSafely()
        return null
    }
    val device: CameraDevice? = try {
        q.poll(timeoutMs, TimeUnit.MILLISECONDS)?.value
    } catch (_: InterruptedException) { null }
    if (device == null) {
        thread.quitSafely()
        return null
    }
    val characteristics = try { manager.getCameraCharacteristics(cameraId) } catch (_: Throwable) {
        runCatching { device.close() }
        thread.quitSafely()
        return null
    }
    return CameraHandle(device, characteristics, thread, handler)
}

/** Pick the largest (or smallest) JPEG output size for still capture. */
internal fun pickJpegSize(characteristics: CameraCharacteristics, largest: Boolean = true): Size? {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null
    return sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }.takeIf { largest }
        ?: sizes.minByOrNull { it.width.toLong() * it.height.toLong() }
}

/* ======================= MediaStore publish (mirrors MicRecordTool) ======================= */

internal fun publishImage(context: Context, file: File): String? = runCatching {
    if (Build.VERSION.SDK_INT < 29) {
        // Legacy devices: make the file visible to gallery apps via a media scan
        // (no MediaStore insert path on 26-28 without legacy external storage).
        runCatching {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        }
        return@runCatching null
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AndAgent")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
        ?: return@runCatching null
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: error("openOutputStream null")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}.getOrNull()

internal fun publishVideo(context: Context, file: File): String? = runCatching {
    if (Build.VERSION.SDK_INT < 29) {
        runCatching {
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
        return@runCatching null
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AndAgent")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
        ?: return@runCatching null
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: error("openOutputStream null")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        null
    }
}.getOrNull()

/* ======================= camera_capture ======================= */

class CameraCaptureTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_capture"
    override val description =
        "用相机拍一张照片（camera2 静态拍摄）：可选前后置、物理镜头（超广角/长焦/微距）、变焦、分辨率、闪光与 JPEG 质量；支持手动 ISO/快门/曝光补偿/对焦距离（需设备 MANUAL_SENSOR 能力），照片落相册 Pictures/AndAgent 并返回本地路径。"
    override val parameters = schemaOf(
        "camera" to strParam("back（默认）或 front"),
        "lens" to strParam("可选物理镜头：wide（默认，逻辑主摄）/ultrawide（超广角）/tele（长焦）/macro（微距），也可直接传物理摄像头 id"),
        "zoom" to numParam("可选变焦倍数（1.0 起）。有 CONTROL_ZOOM_RATIO 的设备走光学变焦比（HAL 自动切镜头），否则用中心裁切数码变焦"),
        "resolution" to strParam("可选，格式 WxH，如 1920x1080；缺省选该摄像头最大 JPEG 输出"),
        "flash" to boolParam("可选，是否强制开闪光灯（仅硬件支持时生效）"),
        "quality" to intParam("JPEG 质量 1-100，默认 95"),
        "iso" to intParam("可选手动 ISO（需 MANUAL_SENSOR 能力）；给出后自动曝光关闭"),
        "exposure_ns" to intParam("可选手动快门时间（纳秒，需 MANUAL_SENSOR 能力），如 1000000 = 1ms"),
        "ev" to numParam("可选曝光补偿（EV，如 -1.5 / 0.5），仅在自动曝光时生效"),
        "focus" to strParam("对焦模式：auto（默认连续对焦）/macro/infinity/manual"),
        "focus_distance_cm" to numParam("focus=manual 时的对焦距离（厘米）；<10 可近摄"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val facing = args.strArg("camera") ?: "back"
        val quality = (args.intArg("quality") ?: 95).coerceIn(1, 100)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"

        val pro = openProCamera(manager, facing, args.strArg("lens"))
            ?: return "ERROR: 打开摄像头失败（被占用或已禁用）"
        val handle = pro.handle
        try {
            val characteristics = handle.characteristics
            val warnings = pro.notes.toMutableList()
            val requested = parseSizeArg(args.strArg("resolution"))
            val largest = pickJpegSize(characteristics, largest = true)
            val all = try {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList<Size>()
            } catch (_: Throwable) { emptyList<Size>() }
            val matched = requested?.let { r -> all.firstOrNull { it.width == r.width && it.height == r.height } }
            if (requested != null && matched == null) {
                warnings.add("不支持分辨率 ${requested.width}x${requested.height}，已改用最大 JPEG 输出")
            }
            val size: Size = matched ?: largest ?: Size(1280, 720)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val wantFlash = args.boolArg("flash") == true && flashAvailable
            if (args.boolArg("flash") == true && !flashAvailable) warnings.add("该摄像头没有闪光灯")

            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val manualSensor = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            // 手动曝光计划：只有 MANUAL_SENSOR 能力时才关闭 AE，否则如实降级并告知。
            val isoArg = args.intArg("iso")
            val exposureArg = args.longArg("exposure_ns")
            var manualIso: Int? = null
            var manualExposureNs: Long? = null
            if (isoArg != null || exposureArg != null) {
                if (!manualSensor) {
                    warnings.add("该摄像头缺少 MANUAL_SENSOR 能力，已忽略手动 ISO/快门")
                } else {
                    manualIso = isoArg?.let { v ->
                        isoRange?.let { r -> CameraProCodec.clampIso(v, r.lower, r.upper) } ?: v
                    }
                    manualExposureNs = exposureArg?.let { v ->
                        exposureRange?.let { r -> CameraProCodec.clampExposureNs(v, r.lower, r.upper) } ?: v
                    }
                }
            }

            // 曝光补偿计划（与手动曝光互斥）
            var evSteps: Int? = null
            val evArg = args.floatArg("ev")
            if (manualIso == null && manualExposureNs == null && evArg != null) {
                val compRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
                if (compRange == null) {
                    warnings.add("该摄像头不支持曝光补偿")
                } else {
                    evSteps = CameraProCodec.evSteps(evArg, step, compRange.lower, compRange.upper)
                }
            }

            // 对焦计划
            val canManualFocus = afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)
            val minFocusDiopter = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            var afMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            var focusDiopter: Float? = null
            var focusDistanceCm: Float? = null
            when ((args.strArg("focus") ?: "auto").lowercase()) {
                "infinity" -> {
                    if (canManualFocus) {
                        afMode = CameraMetadata.CONTROL_AF_MODE_OFF
                        focusDiopter = 0f
                    } else {
                        warnings.add("该摄像头不支持手动对焦，无法锁定无穷远")
                    }
                }
                "manual" -> {
                    val diopter = args.floatArg("focus_distance_cm")?.let { CameraProCodec.distanceCmToDiopter(it) }
                    when {
                        !canManualFocus -> warnings.add("该摄像头不支持手动对焦（CONTROL_AF_AVAILABLE_MODES 无 OFF）")
                        diopter == null -> warnings.add("focus=manual 需要 focus_distance_cm（厘米）且 >0")
                        else -> {
                            val d = if (minFocusDiopter > 0f) diopter.coerceIn(0f, minFocusDiopter) else 0f
                            afMode = CameraMetadata.CONTROL_AF_MODE_OFF
                            focusDiopter = d
                            focusDistanceCm = CameraProCodec.diopterToDistanceCm(d)
                        }
                    }
                }
                "macro" -> {
                    if (afModes.contains(CameraMetadata.CONTROL_AF_MODE_MACRO)) {
                        afMode = CameraMetadata.CONTROL_AF_MODE_MACRO
                    } else {
                        warnings.add("该摄像头不支持 AF_MODE_MACRO，已用连续对焦")
                    }
                }
            }

            // 变焦计划：优先光学变焦比（HAL 跨物理镜头切换），退化为中心裁切。
            val zoomArg = args.floatArg("zoom")
            var zoomRatio: Float? = null
            var zoomCrop: Rect? = null
            var zoomMode = "none"
            if (zoomArg != null && zoomArg > 1f) {
                if (Build.VERSION.SDK_INT >= 30) {
                    val range = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                    if (range != null) {
                        zoomRatio = zoomArg.coerceIn(range.lower, range.upper)
                        zoomMode = "zoom_ratio"
                    }
                }
                if (zoomRatio == null) {
                    val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                    val z = CameraProCodec.clampZoom(zoomArg, maxZoom)
                    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (active != null) {
                        val crop = CameraProCodec.zoomCropRegion(active.width(), active.height(), z)
                        zoomCrop = Rect(
                            active.left + crop.x,
                            active.top + crop.y,
                            active.left + crop.x + crop.width,
                            active.top + crop.y + crop.height,
                        )
                        zoomRatio = z
                        zoomMode = "digital_crop"
                    } else {
                        warnings.add("该摄像头不支持变焦")
                    }
                }
            }

            val reader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val captured = java.util.concurrent.LinkedBlockingQueue<CamEvent<ByteArray?>>()
            val resultRef = java.util.concurrent.atomic.AtomicReference<TotalCaptureResult?>(null)
            var sessionRef: CameraCaptureSession? = null
            try {
                val sessionOpen = java.util.concurrent.LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                reader.setOnImageAvailableListener({ reader ->
                    val image = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
                    try {
                        val buf = image.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        captured.post(bytes)
                    } catch (_: Throwable) {
                        captured.post(null)
                    } finally {
                        runCatching { image.close() }
                    }
                }, handle.handler)

                handle.device.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                    handle.handler,
                )
                val session: CameraCaptureSession = sessionOpen.poll(4000, TimeUnit.MILLISECONDS)?.value
                    ?: error("会话创建超时")
                sessionRef = session

                val request = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, afMode)
                    focusDiopter?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                    if (wantFlash) set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                    if (manualIso != null || manualExposureNs != null) {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        manualIso?.let { set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                        manualExposureNs?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                        set(CaptureRequest.SENSOR_FRAME_DURATION, max(manualExposureNs ?: 0L, 33_000_000L))
                    } else {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                        evSteps?.let { set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it) }
                    }
                    if (zoomMode == "zoom_ratio") zoomRatio?.let { set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
                    zoomCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                }
                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                        resultRef.set(result)
                    }

                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                        captured.post(null)
                    }
                }, handle.handler)

                val jpeg = captured.poll(8000, TimeUnit.MILLISECONDS)?.value ?: error("拍摄超时或失败")
                val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: error("JPEG 解码失败")
                // Sensor images come out landscape; rotate per SENSOR_ORIENTATION so the
                // saved photo matches how the user holds the phone (portraits upright).
                val rotated = rotateBitmap(raw, sensorOrientation)
                val out = captureDir(context).resolve("cam_${System.currentTimeMillis()}.jpg")
                out.parentFile?.mkdirs()
                java.io.FileOutputStream(out).use { f ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, quality, f)
                }
                if (rotated !== raw) raw.recycle()
                val outW = rotated.width
                val outH = rotated.height
                rotated.recycle()

                val result = resultRef.get()
                val actualIso = result?.get(CaptureResult.SENSOR_SENSITIVITY)
                val actualExposureNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val actualFocusDiopter = result?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                val actualZoom = if (Build.VERSION.SDK_INT >= 30) result?.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
                val uri = publishImage(context, out)
                return buildJsonObject {
                    put("status", "WRITTEN")
                    put("path", out.absolutePath)
                    put("uri", uri ?: "")
                    put("galleryVisible", uri != null)
                    put("width", outW)
                    put("height", outH)
                    put("size", out.length())
                    put("facing", facing)
                    put("cameraId", pro.lensId)
                    put("lensRole", pro.lenses.firstOrNull { it.id == pro.lensId }?.role ?: "wide")
                    put("zoomMode", zoomMode)
                    zoomRatio?.let { put("zoomApplied", it) }
                    manualIso?.let { put("isoRequested", it) }
                    manualExposureNs?.let { put("exposureRequestedNs", it) }
                    evSteps?.let { put("evSteps", it) }
                    put("focusMode", when {
                        focusDiopter == null -> "auto"
                        focusDiopter == 0f -> "infinity"
                        else -> "manual"
                    })
                    focusDistanceCm?.let { put("focusDistanceCm", it) }
                    actualIso?.let { put("isoActual", it) }
                    actualExposureNs?.let { put("exposureActualNs", it) }
                    actualFocusDiopter?.let { put("focusDiopterActual", it) }
                    actualZoom?.let { put("zoomActual", it) }
                    put("lenses", buildJsonArray {
                        pro.lenses.forEach { lens ->
                            add(buildJsonObject {
                                put("id", lens.id)
                                put("role", lens.role)
                                put("focalMm", lens.focalMm)
                                lens.aperture?.let { put("aperture", it) }
                                lens.minFocusDistanceCm?.let { put("minFocusDistanceCm", it) }
                            })
                        }
                    })
                    if (warnings.isNotEmpty()) putJsonArray("warnings") { warnings.forEach { add(it) } }
                }.toString()
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
            }
        } catch (e: Throwable) {
            return "ERROR: 拍照失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            handle.close()
        }
    }
}

private fun captureDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "captures").apply { mkdirs() }

internal fun rotateBitmap(b: Bitmap, degrees: Int): Bitmap {
    val norm = ((degrees % 360) + 360) % 360
    if (norm == 0 || b.isRecycled) return b
    val m = Matrix().apply { postRotate(norm.toFloat()) }
    return try {
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    } catch (_: Throwable) {
        b
    }
}

/* ======================= camera_record ======================= */

class CameraRecordTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_record"
    override val description =
        "用相机录制一段视频（MediaRecorder，MPEG-4/H264+AAC）：可选前后置、时长与画质（hd/sd），录完自动保存到 Movies/AndAgent 并返回路径。"
    override val parameters = schemaOf(
        "camera" to strParam("back（默认）或 front"),
        "seconds" to intParam("录制时长（秒），默认 30，范围 1-600"),
        "quality" to strParam("hd（默认）或 sd；硬件不支持时自动降级"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val audioOk = permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))
        val facing = args.strArg("camera") ?: "back"
        val seconds = (args.intArg("seconds") ?: 30).coerceIn(1, 600)
        val wantHd = !(args.strArg("quality") ?: "hd").equals("sd", true)
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"

        val resolved = try { resolveCameraId(manager, facing) } catch (_: Throwable) { null }
            ?: return "ERROR: 未找到可用摄像头"
        val handle = openCameraBlocking(manager, resolved.first) ?: return "ERROR: 打开摄像头失败（被占用或已禁用）"
        try {
            val characteristics = handle.characteristics
            val profile: CamcorderProfile = pickProfile(resolved.first, wantHd)
            // Recorder surface must be a supported preview output (SurfaceTexture); else fall back.
            val previewSizes: List<Size> = try {
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
            } catch (_: Throwable) { emptyList() }
            var w = profile.videoFrameWidth
            var h = profile.videoFrameHeight
            val supported = previewSizes.firstOrNull { it.width == w && it.height == h }
            if (supported == null && previewSizes.isNotEmpty()) {
                val alt = previewSizes.filter { it.width <= w && it.height <= h }.maxByOrNull { it.width.toLong() * it.height }
                val chosen = alt ?: previewSizes.minByOrNull { it.width.toLong() * it.height }!!
                w = chosen.width
                h = chosen.height
            }

            val out = File(recordingDir(context), "cam_${System.currentTimeMillis()}.mp4")
            out.parentFile?.mkdirs()
            // MediaRecorder(context) is API 31+; the no-arg constructor works on all
            // supported levels (deprecated but functional, minSdk 26).
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            val stopped = AtomicBoolean(false)
            var sessionRef: CameraCaptureSession? = null
            try {
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                if (audioOk) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setOutputFile(out.absolutePath)
                recorder.setVideoEncodingBitRate(profile.videoBitRate)
                recorder.setVideoFrameRate(profile.videoFrameRate.coerceIn(10, 30))
                recorder.setVideoSize(w, h)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (audioOk) {
                    recorder.setAudioEncodingBitRate(profile.audioBitRate)
                    recorder.setAudioSamplingRate(profile.audioSampleRate)
                    recorder.setAudioChannels(profile.audioChannels)
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                val orientationHint = when (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) {
                    90 -> 90
                    270 -> 270
                    180 -> 180
                    else -> 0
                }
                recorder.setOrientationHint(if (facing.equals("front", true)) (orientationHint + 180) % 360 else orientationHint)
                recorder.prepare()

                val surface = recorder.surface
                val sessionOpen = java.util.concurrent.LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                handle.device.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                    handle.handler,
                )
                val session = sessionOpen.poll(4000, TimeUnit.MILLISECONDS)?.value ?: error("会话创建超时")
                sessionRef = session
                val request = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                session.setRepeatingRequest(request.build(), null, handle.handler)

                recorder.start()
                delay(seconds * 1000L)
                stopped.set(true)
                val stopOk = runCatching { recorder.stop() }.isSuccess
                runCatching { sessionRef?.close() }

                if (!stopOk) error("录制中途出错，文件可能不完整")
                if (!out.exists() || out.length() == 0L) error("录像文件为空")
                val uri = publishVideo(context, out)
                return buildJsonObject {
                    put("status", "WRITTEN")
                    put("path", out.absolutePath)
                    put("uri", uri ?: "")
                    put("galleryVisible", uri != null)
                    put("durationMs", seconds * 1000L)
                    put("size", out.length())
                    put("width", w)
                    put("height", h)
                    put("audio", audioOk)
                    put("facing", facing)
                }.toString()
            } finally {
                runCatching { if (!stopped.get()) recorder.stop() }
                runCatching { recorder.release() }
                runCatching { sessionRef?.close() }
            }
        } catch (e: Throwable) {
            return "ERROR: 录像失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            handle.close()
        }
    }

    private fun pickProfile(cameraId: String, wantHd: Boolean): CamcorderProfile {
        val id = cameraId.toIntOrNull()
        if (id != null) {
            // Device-specific profile when available (API 11+ overload takes int cameraId).
            val specific = runCatching {
                if (wantHd) CamcorderProfile.get(id, CamcorderProfile.QUALITY_HIGH) else CamcorderProfile.get(id, CamcorderProfile.QUALITY_LOW)
            }.getOrNull()
            if (specific != null) return specific
        }
        return runCatching {
            if (wantHd) CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH) else CamcorderProfile.get(CamcorderProfile.QUALITY_LOW)
        }.getOrElse { CamcorderProfile.get(CamcorderProfile.QUALITY_LOW) }
    }
}

private fun recordingDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

/* ======================= camera_scan ======================= */

private const val TAG = "CameraScan"

/** 预览纹理旋转角核心推导（sensor orientation vs display rotation，与 Camera2Basic 一致）。 */
internal fun cameraPreviewRotationDegrees(displayDegrees: Int, sensorOrientation: Int, front: Boolean): Int =
    if (front) (sensorOrientation + displayDegrees) % 360 else (sensorOrientation - displayDegrees + 360) % 360

/** 计算预览纹理的旋转角（sensor orientation vs display rotation，与 Camera2Basic 推导一致）。 */
internal fun scanPreviewRotationDegrees(activity: Activity, characteristics: CameraCharacteristics, facing: String): Int {
    val displayDegrees = when (activity.windowManager.defaultDisplay?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    return cameraPreviewRotationDegrees(displayDegrees, sensor, facing.equals("front", true))
}

/** 扫码预览变换：缓冲以统一比例铺满视图（center-crop），旋转 90/270 时仍正确。 */
internal fun applyScanPreviewTransform(view: TextureView, bufferW: Int, bufferH: Int, rotationDeg: Int) {
    if (view.width <= 0 || view.height <= 0 || bufferW <= 0 || bufferH <= 0) return
    val vw = view.width.toFloat(); val vh = view.height.toFloat()
    val m = Matrix()
    val viewRect = RectF(0f, 0f, vw, vh)
    val bufferRect = RectF(0f, 0f, bufferW.toFloat(), bufferH.toFloat())
    val cx = vw / 2f; val cy = vh / 2f
    if (rotationDeg == 90 || rotationDeg == 270) {
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())
    }
    m.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
    if (rotationDeg != 0) m.postRotate(rotationDeg.toFloat(), cx, cy)
    view.setTransform(m)
}

/** 为预览 TextureView 选缓冲尺寸、设变换并创建并入会话的 Surface；失败返回 null（走无预览扫码）。 */
internal fun prepScanPreviewSurface(
    activity: Activity,
    view: TextureView?,
    characteristics: CameraCharacteristics,
    facing: String,
): Surface? {
    if (view == null) return null
    val texture = view.surfaceTexture ?: return null
    return try {
        val sizes = try {
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.toList()
        } catch (_: Throwable) { null } ?: emptyList()
        val buf = sizes.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - 1280L * 720L) } ?: Size(1280, 720)
        texture.setDefaultBufferSize(buf.width, buf.height)
        val rotation = scanPreviewRotationDegrees(activity, characteristics, facing)
        view.post { applyScanPreviewTransform(view, buf.width, buf.height, rotation) }
        Surface(texture)
    } catch (_: Throwable) { null }
}

/** 扫码期间的应用内预览浮窗：复用 InAppOverlayHost，TextureView 就绪后并入扫码会话。 */
internal class ScanPreviewOverlay(private val activity: Activity) {
    private val ready = java.util.concurrent.LinkedBlockingQueue<CamEvent<TextureView>>()
    val userClosed = AtomicBoolean(false)

    var view: TextureView? = null
        private set

    /** 展示方形预览窗并等待 surface 就绪（≤4s）；失败自动收起并返回 false。 */
    fun show(sizeDp: Int): Boolean {
        val dm = activity.resources.displayMetrics
        val screenWdp = (dm.widthPixels / dm.density).toInt()
        val screenHdp = (dm.heightPixels / dm.density).toInt()
        val size = sizeDp.coerceIn(240, (screenWdp - 24).coerceAtLeast(240))
        val shown = InAppOverlayHost.show(
            activity, "扫码中…", size, size,
            ((screenWdp - size) / 2).coerceAtLeast(0),
            ((screenHdp - size) / 2).coerceAtLeast(0),
            { content -> mount(content) },
            { userClosed.set(true) },
        )
        if (!shown) return false
        view = ready.poll(4000, TimeUnit.MILLISECONDS)?.value
        if (view == null) {
            android.util.Log.i(TAG, "overlay texture not ready within 4s")
            dismiss(); return false
        }
        return true
    }

    private fun mount(content: FrameLayout) {
        android.util.Log.i(TAG, "overlay mount (attached=${content.isAttachedToWindow})")
        val preview = TextureView(activity)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                android.util.Log.i(TAG, "overlay texture available ${w}x${h}")
                ready.post(preview)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                android.util.Log.i(TAG, "overlay texture destroyed")
                userClosed.set(true); return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        content.addView(preview, FrameLayout.LayoutParams(-1, -1))
    }

    fun dismiss() { runCatching { InAppOverlayHost.dismiss() } }
}

class CameraScanTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    private val activityProvider: () -> Activity? = { null },
) : Tool {
    override val name = "camera_scan"
    override val description =
        "用相机连续取景识别条码/二维码（ZXing）：QR/EAN/Code128/PDF417 等，命中即返回文本与码制；可选限时与格式过滤。识别期间应用内会显示实时扫码预览窗（用户可点 ✕ 取消）。"
    override val parameters = schemaOf(
        "seconds" to intParam("最长取景识别时长（秒），默认 20，范围 1-120"),
        "formats" to strParam("可选，逗号分隔码制：qr,ean13,ean8,upc,code128,code39,itf,pdf417,aztec,databar；缺省全格式"),
        "camera" to strParam("back（默认）或 front"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val seconds = (args.intArg("seconds") ?: 20).coerceIn(1, 120)
        val formats = CameraScanCodec.parseFormats(args.strArg("formats"))
        val facing = args.strArg("camera") ?: "back"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"

        val resolved = try { resolveCameraId(manager, facing) } catch (_: Throwable) { null }
            ?: return "ERROR: 未找到可用摄像头"
        val handle = openCameraBlocking(manager, resolved.first) ?: return "ERROR: 打开摄像头失败（被占用或已禁用）"
        // 应用内扫码预览：有前台 Activity 且 surface 就绪才显示，否则无预览降级（离屏扫码）。
        val activity = activityProvider()?.takeIf { !it.isFinishing && it.window != null }
        android.util.Log.i(TAG, "preview: activity=${if (activity != null) "yes" else "no"}")
        var overlay: ScanPreviewOverlay? = null
        var previewSurface: Surface? = null
        if (activity != null) {
            val o = ScanPreviewOverlay(activity)
            if (o.show(400)) {
                previewSurface = prepScanPreviewSurface(activity, o.view, handle.characteristics, facing)
                android.util.Log.i(TAG, "preview: show ok, surface=${if (previewSurface != null) "yes" else "no"}")
                if (previewSurface != null) overlay = o else o.dismiss()
            } else {
                android.util.Log.i(TAG, "preview: overlay show failed/timeout")
            }
        }
        try {
            val characteristics = handle.characteristics
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = try { map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() } catch (_: Throwable) { null } ?: emptyList()
            val size: Size = yuvSizes.filter { it.width >= 640 }.minByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes.minByOrNull { it.width.toLong() * it.height }
                ?: Size(640, 480)

            val reader = android.media.ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            val hit = java.util.concurrent.LinkedBlockingQueue<CamEvent<String>>()
            val lastFormat = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val deadline = System.currentTimeMillis() + seconds * 1000L
            // MultiFormatReader is stateful and NOT thread-safe; give every scan its
            // own instance and pass the user's format filter via decode hints.
            val scanReader = com.google.zxing.MultiFormatReader()
            val hints = mutableMapOf<com.google.zxing.DecodeHintType, Any>()
            if (formats.isNotEmpty()) hints[com.google.zxing.DecodeHintType.POSSIBLE_FORMATS] = formats
            hints[com.google.zxing.DecodeHintType.TRY_HARDER] = true
            scanReader.setHints(hints)

            var sessionRef: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ r ->
                    val image = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
                    try {
                        val p = image.planes[0]
                        val buf = p.buffer
                        val data = ByteArray(buf.remaining())
                        buf.get(data)
                        val plane = CameraScanCodec.YPlane(data, image.width, image.height, p.rowStride)
                        val source = CameraScanCodec.toZxingSource(plane)
                        val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                        var text: String? = null
                        var fmt: String? = null
                        try {
                            val res = scanReader.decodeWithState(bitmap)
                            text = res?.text
                            fmt = res?.barcodeFormat?.toString()
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { scanReader.reset() } catch (_: Throwable) {}
                        }
                        if (text != null) {
                            lastFormat.set(fmt)
                            hit.post(text)
                        }
                    } catch (_: Throwable) {
                    } finally {
                        runCatching { image.close() }
                    }
                }, handle.handler)

                // 优先 预览+YUV 双输出；HAL 不支持双流、预览 surface 中途被遗弃
                // （abandoned）或会话失效时，整体重试下一组 target（仅 YUV 离屏扫码）。
                val targetSets = ArrayList<List<Surface>>()
                previewSurface?.let { targetSets.add(listOf(it, reader.surface)) }
                targetSets.add(listOf(reader.surface))
                var session: CameraCaptureSession? = null
                for (targets in targetSets) {
                    val sessionOpen = java.util.concurrent.LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                    try {
                        handle.device.createCaptureSession(
                            targets,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                                override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                            },
                            handle.handler,
                        )
                        session = sessionOpen.poll(6000, TimeUnit.MILLISECONDS)?.value
                    } catch (e: Throwable) {
                        android.util.Log.i(TAG, "createCaptureSession(${targets.size} targets) failed: ${e.message}")
                        session = null
                    }
                    if (session != null) {
                        try {
                            val request = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                previewSurface?.let { addTarget(it) }
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            session.setRepeatingRequest(request.build(), null, handle.handler)
                            android.util.Log.i(TAG, "session ok with ${targets.size} target(s)")
                            break
                        } catch (e: Throwable) {
                            // surface 被遗弃/会话失效：收尾后重试下一组 target。
                            android.util.Log.i(TAG, "repeating request failed: ${e.message}")
                            runCatching { session.close() }
                            session = null
                        }
                    }
                    if (previewSurface != null) {
                        // 双流不可用：收起预览浮窗，继续无预览扫码。
                        runCatching { previewSurface?.release() }
                        previewSurface = null
                        overlay?.dismiss()
                        android.util.Log.i(TAG, "dual-stream rejected, fallback to yuv-only scan")
                    } else break
                }
                sessionRef = session ?: error("会话创建超时")

                // 命中即返回；预览窗被用户（✕ 或返回退出）关闭时提前取消。
                var text: String? = null
                while (text == null && System.currentTimeMillis() < deadline && overlay?.userClosed?.get() != true) {
                    text = hit.poll(300, TimeUnit.MILLISECONDS)?.value
                }
                val elapsed = seconds - ((deadline - System.currentTimeMillis()) / 1000.0)
                return when {
                    text != null -> buildJsonObject {
                        put("status", "OK")
                        put("format", lastFormat.get() ?: "")
                        put("text", text)
                        put("previewShown", overlay != null)
                        put("seconds_elapsed", elapsed.coerceAtLeast(0.0))
                    }.toString()
                    overlay?.userClosed?.get() == true -> "ERROR: 扫码已取消（预览窗已关闭）"
                    else -> "ERROR: 未在 $seconds 秒内识别到码"
                }
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
                runCatching { scanReader.reset() }
            }
        } catch (e: Throwable) {
            return "ERROR: 扫码失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            handle.close()
            // 相机设备完全关闭后再释放预览 surface / 收起浮窗，避免 HAL 仍持有
            // surface 时被释放报 "surface was abandoned"。
            runCatching { previewSurface?.release() }
            overlay?.dismiss()
        }
    }
}

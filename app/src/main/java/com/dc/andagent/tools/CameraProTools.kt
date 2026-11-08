package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.util.Size
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* ======================= 物理镜头发现（超广角/长焦/微距） ======================= */

internal data class ProLens(
    val id: String,
    val role: String,
    val focalMm: Float,
    val aperture: Float?,
    val minFocusDistanceCm: Float?,
)

/**
 * 逻辑相机 + 其物理镜头组的角色推断（API 28+ 才有 physicalCameraIds）。
 * 角色只用于「选哪颗镜头」与展示，不改变 HAL 自身的多摄调度策略。
 */
internal fun describeProLenses(manager: CameraManager, baseId: String): List<ProLens> {
    val base = runCatching { manager.getCameraCharacteristics(baseId) }.getOrNull() ?: return emptyList()
    val ids = LinkedHashSet<String>()
    ids.add(baseId)
    if (Build.VERSION.SDK_INT >= 28) {
        runCatching { ids.addAll(base.physicalCameraIds) }
    }
    data class Hit(val candidate: CameraProCodec.LensCandidate, val c: CameraCharacteristics)

    val hits = ids.mapNotNull { id ->
        val c = if (id == baseId) base else runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: return@mapNotNull null
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            ?: return@mapNotNull null
        val diopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        Hit(CameraProCodec.LensCandidate(id, focal, diopter), c)
    }
    if (hits.isEmpty()) return emptyList()
    val roles = CameraProCodec.assignLensRoles(hits.map { it.candidate })
    return hits.mapIndexed { i, hit ->
        ProLens(
            id = hit.candidate.id,
            role = roles[i],
            focalMm = hit.candidate.focalMm,
            aperture = hit.c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            minFocusDistanceCm = hit.c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.let { CameraProCodec.diopterToDistanceCm(it) },
        )
    }
}

internal fun lensSummary(lenses: List<ProLens>): String =
    if (lenses.isEmpty()) "无附加镜头信息"
    else lenses.joinToString(", ") { "${it.role}(#${it.id} ${it.focalMm}mm)" }

private fun normalizeLensName(raw: String): String = when (raw.trim().lowercase()) {
    "uw", "ultra_wide", "ultra-wide", "ultrawide", "wide_angle", "0.5x" -> "ultrawide"
    "telephoto", "zoom", "2x", "3x", "5x" -> "tele"
    "closeup", "close-up", "micro", "macro" -> "macro"
    "main", "primary", "auto", "back", "front" -> "wide"
    else -> raw.trim().lowercase()
}

internal data class ProCamera(
    val handle: CameraHandle,
    val lensId: String,
    val logicalId: String,
    val lenses: List<ProLens>,
    val notes: List<String>,
)

/**
 * 按 facing + 镜头角色打开相机。物理 id 单独打开失败（多数机型只允许逻辑相机统一调度）
 * 时回退逻辑主摄并记一条 note，绝不静默换成另一颗镜头。
 */
internal fun openProCamera(manager: CameraManager, facing: String, lensArg: String?): ProCamera? {
    val base = resolveCameraId(manager, facing) ?: return null
    val lenses = describeProLenses(manager, base.first)
    val notes = mutableListOf<String>()
    val want = lensArg?.takeIf { it.isNotBlank() }?.let { normalizeLensName(it) }
    var targetId = base.first
    if (!want.isNullOrEmpty() && want != "wide") {
        val match = lenses.firstOrNull { it.id == want } ?: lenses.firstOrNull { it.role == want }
        when {
            match == null -> notes.add("未找到 $want 镜头，已回退主摄；可用：${lensSummary(lenses)}")
            match.id != base.first -> targetId = match.id
        }
    }
    var handle = openCameraBlocking(manager, targetId)
    if (handle == null && targetId != base.first) {
        notes.add("物理摄像头 #$targetId 无法单独打开，已回退逻辑主摄（多数机型限定逻辑相机统一调度）")
        handle = openCameraBlocking(manager, base.first)
        targetId = base.first
    }
    if (handle == null) return null
    return ProCamera(handle, targetId, base.first, lenses, notes)
}

internal fun proCaptureDir(context: Context, sub: String): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "captures/$sub").apply { mkdirs() }

internal fun parseSizeArg(raw: String?): Size? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("(\\d+)\\s*[xX*]\\s*(\\d+)").find(raw) ?: return null
    val w = m.groupValues[1].toIntOrNull() ?: return null
    val h = m.groupValues[2].toIntOrNull() ?: return null
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
    return Size(w, h)
}

/* ======================= 厂商相机扩展（Camera Extensions） ======================= */

/**
 * camera2 的扩展能力查询（API 29+）。真正建立扩展会话需要 API 31+：
 * SessionConfiguration 的 sessionType 直接使用扩展类型值（EXTENSION_HDR/NIGHT/...），
 * 这一层就是 CameraX `camera-extensions` 内部走的同一条 HAL 通路。
 */
internal object CameraExtensionProbe {

    fun supported(manager: CameraManager, cameraId: String): List<Int> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return runCatching {
            manager.getCameraExtensionCharacteristics(cameraId).supportedExtensions?.map { it.toInt() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun name(ext: Int): String = when (ext) {
        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "auto"
        CameraExtensionCharacteristics.EXTENSION_HDR -> "hdr"
        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "night"
        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "bokeh"
        CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "face_retouch"
        else -> "ext_$ext"
    }

    fun fromName(raw: String?): Int? = when (raw?.trim()?.lowercase()) {
        null, "", "auto", "automatic" -> CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
        "hdr" -> CameraExtensionCharacteristics.EXTENSION_HDR
        "night", "lowlight" -> CameraExtensionCharacteristics.EXTENSION_NIGHT
        "bokeh", "portrait" -> CameraExtensionCharacteristics.EXTENSION_BOKEH
        "face_retouch", "beauty" -> CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH
        else -> null
    }

    fun jpegSizes(manager: CameraManager, cameraId: String, ext: Int): List<Size> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        return runCatching {
            manager.getCameraExtensionCharacteristics(cameraId)
                .getExtensionSupportedSizes(ext, ImageFormat.JPEG)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 扩展的预计拍摄延迟（毫秒区间），API 31+ 才有。 */
    fun latencyMs(manager: CameraManager, cameraId: String, ext: Int, size: Size): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            val range = manager.getCameraExtensionCharacteristics(cameraId)
                .getEstimatedCaptureLatencyRangeMillis(ext, size, ImageFormat.JPEG)
            if (range == null) null else "${range.lower}-${range.upper}"
        }.getOrNull()
    }
}

/* ======================= camera_raw ======================= */

private data class RawFrame(
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val bytes: ByteArray,
)

private fun decodeRawFrame(frame: RawFrame, format: Int): IntArray? = when (format) {
    ImageFormat.RAW10 -> CameraProCodec.unpackRaw10(frame.bytes, frame.width, frame.height, frame.rowStride)
    ImageFormat.RAW12 -> CameraProCodec.unpackRaw12(frame.bytes, frame.width, frame.height, frame.rowStride)
    else -> CameraProCodec.readRaw16(
        ByteBuffer.wrap(frame.bytes).order(ByteOrder.LITTLE_ENDIAN),
        frame.width,
        frame.height,
        frame.rowStride,
        frame.pixelStride,
    )
}

private fun blackLevelOf(c: CameraCharacteristics): Int =
    runCatching { c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0) }
        .getOrNull() ?: 64

private fun dngColorMatrix(c: CameraCharacteristics): FloatArray? {
    val transform = runCatching { c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) }.getOrNull() ?: return null
    // SENSOR_COLOR_TRANSFORM1 是「相机原生 RGB → XYZ」，DNG ColorMatrix1 要的是反方向（XYZ → 相机）。
    val rgbToXyz = FloatArray(9)
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            val element = runCatching { transform.getElement(col, row) }.getOrNull() ?: return null
            rgbToXyz[row * 3 + col] = if (element.denominator == 0) {
                0f
            } else {
                element.numerator.toFloat() / element.denominator
            }
        }
    }
    return CameraProCodec.invert3x3(rgbToXyz)
}

/**
 * camera_raw：RAW_SENSOR（Bayer）→ DNG（未压缩 CFA + 黑/白电平 + CFA 排布 + ColorMatrix1），
 * 可选半分辨率灰度预览与裸数据 sidecar。设备无 RAW 能力时明确报错而不是降级成 JPEG。
 */
class CameraRawTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_raw"
    override val description =
        "拍摄 RAW（Bayer）并写 DNG：可选前后置/镜头/分辨率，返回黑电平、白电平、CFA 排布、色彩矩阵与文件路径；设备不支持 RAW 时如实报错。"
    override val parameters = schemaOf(
        "camera" to strParam("back（默认）或 front"),
        "lens" to strParam("wide（默认）/ultrawide/tele/macro，或物理摄像头 id"),
        "resolution" to strParam("可选，格式 WxH；缺省取该摄像头最大 RAW 输出"),
        "preview" to boolParam("是否同时输出半分辨率灰度预览 JPEG，默认 true"),
        "keep_raw" to boolParam("是否额外保存 16bit 小端裸数据 .raw，默认 false"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"
        val pro = openProCamera(manager, args.strArg("camera") ?: "back", args.strArg("lens"))
            ?: return "ERROR: 打开摄像头失败（未找到或被占用）"
        try {
            val c = pro.handle.characteristics
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                return "ERROR: 摄像头 #${pro.lensId} 不支持 RAW（缺少 REQUEST_AVAILABLE_CAPABILITIES_RAW），请用 camera_capture 拍 JPEG"
            }
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return "ERROR: 该摄像头未提供输出配置"
            var format = ImageFormat.RAW_SENSOR
            var sizes = runCatching { map.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() }.getOrNull() ?: emptyList()
            if (sizes.isEmpty()) {
                val raw10 = runCatching { map.getOutputSizes(ImageFormat.RAW10)?.toList() }.getOrNull() ?: emptyList()
                val raw12 = runCatching { map.getOutputSizes(ImageFormat.RAW12)?.toList() }.getOrNull() ?: emptyList()
                if (raw10.isNotEmpty()) {
                    format = ImageFormat.RAW10
                    sizes = raw10
                } else if (raw12.isNotEmpty()) {
                    format = ImageFormat.RAW12
                    sizes = raw12
                }
            }
            if (sizes.isEmpty()) return "ERROR: 摄像头没有可用的 RAW 输出尺寸"
            val requested = parseSizeArg(args.strArg("resolution"))
            val size = requested?.let { r -> sizes.firstOrNull { it.width == r.width && it.height == r.height } }
                ?: sizes.maxByOrNull { it.width.toLong() * it.height }!!

            val reader = ImageReader.newInstance(size.width, size.height, format, 2)
            val frames = LinkedBlockingQueue<CamEvent<RawFrame?>>()
            val resultRef = AtomicReference<TotalCaptureResult?>(null)
            var sessionRef: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ r ->
                    val image = runCatching { r.acquireNextImage() }.getOrNull()
                    if (image != null) {
                        try {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            frames.post(RawFrame(image.width, image.height, plane.rowStride, plane.pixelStride, bytes))
                        } catch (_: Throwable) {
                            frames.post(null)
                        } finally {
                            runCatching { image.close() }
                        }
                    }
                }, pro.handle.handler)

                val sessionOpen = LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                pro.handle.device.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                    pro.handle.handler,
                )
                val session = sessionOpen.poll(4000, TimeUnit.MILLISECONDS)?.value
                    ?: error("会话创建超时")
                sessionRef = session
                val request = pro.handle.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        resultRef.set(result)
                    }

                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        frames.post(null)
                    }
                }, pro.handle.handler)

                val frame = frames.poll(8000, TimeUnit.MILLISECONDS)?.value ?: error("RAW 拍摄超时或失败")
                val samples = decodeRawFrame(frame, format) ?: error("RAW 数据解码失败")

                val arrangement = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 1
                val cfa = CameraProCodec.cfaPattern(arrangement)
                val black = blackLevelOf(c)
                val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val colorMatrix = dngColorMatrix(c)
                val iso = resultRef.get()?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val exposureNs = resultRef.get()?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                val stamp = System.currentTimeMillis()
                val dir = proCaptureDir(context, "raw")
                val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

                val dngBytes = DngWriter.write(
                    samples,
                    DngWriter.Meta(
                        width = frame.width,
                        height = frame.height,
                        cfaPattern = cfa,
                        blackLevel = black,
                        whiteLevel = white,
                        uniqueModel = "$model camera ${pro.lensId}".trim(),
                        colorMatrix = colorMatrix,
                        iso = iso,
                        exposureNs = exposureNs,
                    ),
                )
                val dng = File(dir, "raw_$stamp.dng")
                dng.writeBytes(dngBytes)

                var rawPath: String? = null
                if (args.boolArg("keep_raw") == true) {
                    val rawBytes = ByteArray(samples.size * 2)
                    for (i in samples.indices) {
                        val v = samples[i]
                        rawBytes[i * 2] = (v and 0xFF).toByte()
                        rawBytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    val raw = File(dir, "raw_$stamp.raw")
                    raw.writeBytes(rawBytes)
                    rawPath = raw.absolutePath
                }

                var previewPath: String? = null
                var previewUri: String? = null
                if (args.boolArg("preview") != false) {
                    val gray = CameraProCodec.rawPreviewGray(samples, frame.width, frame.height, cfa, black, white)
                    val pw = max(1, frame.width / 2)
                    val ph = max(1, frame.height / 2)
                    val pixels = IntArray(pw * ph)
                    for (i in pixels.indices) {
                        val v = gray[i].toInt() and 0xFF
                        pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                    }
                    val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                    bmp.setPixels(pixels, 0, pw, 0, 0, pw, ph)
                    val rotated = rotateBitmap(bmp, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                    val file = File(dir, "raw_${stamp}_preview.jpg")
                    FileOutputStream(file).use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (rotated !== bmp) bmp.recycle()
                    rotated.recycle()
                    previewPath = file.absolutePath
                    previewUri = publishImage(context, file)
                }

                val metaJson = buildJsonObject {
                    put("width", frame.width)
                    put("height", frame.height)
                    put("rawFormat", CameraInfoTool.decodeFormat(format))
                    put("cfa", buildJsonArray { cfa.forEach { add(it) } })
                    put("colorFilterArrangement", arrangement)
                    put("blackLevel", black)
                    put("whiteLevel", white)
                    put("iso", iso)
                    put("exposureNs", exposureNs)
                    put("lens", pro.lensId)
                    put("logicalCamera", pro.logicalId)
                    put("colorMatrix", buildJsonArray { colorMatrix?.forEach { add(it) } })
                    put("dngBytes", dng.length())
                    put("dngPath", dng.absolutePath)
                }
                File(dir, "raw_$stamp.json").writeText(metaJson.toString())

                return buildJsonObject {
                    put("status", "WRITTEN")
                    put("dngPath", dng.absolutePath)
                    put("dngBytes", dng.length())
                    if (rawPath != null) put("rawPath", rawPath)
                    if (previewPath != null) put("previewPath", previewPath)
                    if (previewUri != null) put("previewUri", previewUri)
                    put("width", frame.width)
                    put("height", frame.height)
                    put("rawFormat", CameraInfoTool.decodeFormat(format))
                    put("blackLevel", black)
                    put("whiteLevel", white)
                    put("cfaPattern", buildJsonArray { cfa.forEach { add(it) } })
                    put("hasColorMatrix", colorMatrix != null)
                    put("iso", iso)
                    put("exposureNs", exposureNs)
                    put("lens", pro.lensId)
                    put("lenses", buildJsonArray {
                        pro.lenses.forEach { lens ->
                            add(buildJsonObject {
                                put("id", lens.id)
                                put("role", lens.role)
                                put("focalMm", lens.focalMm)
                                lens.aperture?.let { put("aperture", it) }
                            })
                        }
                    })
                    if (pro.notes.isNotEmpty()) putJsonArray("notes") { pro.notes.forEach { add(it) } }
                }.toString()
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
            }
        } catch (e: Throwable) {
            return "ERROR: RAW 拍摄失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            pro.handle.close()
        }
    }
}

/* ======================= camera_depth ======================= */

/**
 * camera_depth：DEPTH16（毫米）深度图 → 16 位深度 PNG + 伪彩预览 + 统计 + 可选 PLY 点云。
 * 深度输出在 HAL 里属于独立物理相机（ToF/双目），因此候选集要把 physicalCameraIds 一起枚举。
 */
class CameraDepthTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_depth"
    override val description =
        "抓取 DEPTH16 深度图（毫米）：自动挑支持 DEPTH_OUTPUT 的摄像头，输出 16 位深度 PNG、伪彩预览、有效像素统计，可选 PLY 点云；无深度摄像头时如实报错。"
    override val parameters = schemaOf(
        "camera" to strParam("可选，摄像头 id；缺省优先后置深度摄像头"),
        "seconds" to intParam("等待首帧的超时秒数，默认 6，范围 1-30"),
        "resolution" to strParam("可选，格式 WxH；缺省取最大深度输出"),
        "color_preview" to boolParam("是否输出伪彩 PNG 预览，默认 true"),
        "point_cloud" to boolParam("是否导出 PLY 点云，默认 false"),
        "max_points" to intParam("点云上限，默认 20000，最大 200000"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"
        val candidates = depthCapableCameras(manager)
        if (candidates.isEmpty()) {
            return "ERROR: 本机没有支持 DEPTH_OUTPUT 的摄像头（需要 ToF/双目等深度硬件）；可用 camera_info 查看每颗摄像头的 capabilities"
        }
        val wantedId = args.strArg("camera")
        val chosen = candidates.firstOrNull { wantedId != null && it.first == wantedId }
            ?: candidates.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: candidates.first()
        val cameraId = chosen.first
        val characteristics = chosen.second
        val depthSizes = chosen.third
        val requested = parseSizeArg(args.strArg("resolution"))
        val size = requested?.let { r -> depthSizes.firstOrNull { it.width == r.width && it.height == r.height } }
            ?: depthSizes.maxByOrNull { it.width.toLong() * it.height }!!
        val seconds = (args.intArg("seconds") ?: 6).coerceIn(1, 30)

        val handle = openCameraBlocking(manager, cameraId)
            ?: return "ERROR: 打开深度摄像头 #$cameraId 失败（可能被占用）"
        try {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.DEPTH16, 2)
            val frames = LinkedBlockingQueue<CamEvent<IntArray?>>()
            var sessionRef: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ r ->
                    val image = runCatching { r.acquireLatestImage() }.getOrNull()
                    if (image != null) {
                        try {
                            val plane = image.planes[0]
                            val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            val w = image.width
                            val h = image.height
                            val ps = max(2, plane.pixelStride)
                            val rs = plane.rowStride
                            val depth = IntArray(w * h)
                            for (y in 0 until h) {
                                val rowBase = y * rs
                                for (x in 0 until w) {
                                    val idx = rowBase + x * ps
                                    depth[y * w + x] = if (idx + 1 < buffer.limit()) {
                                        buffer.getShort(idx).toInt() and 0xFFFF
                                    } else 0
                                }
                            }
                            frames.post(depth)
                        } catch (_: Throwable) {
                            frames.post(null)
                        } finally {
                            runCatching { image.close() }
                        }
                    }
                }, handle.handler)

                val sessionOpen = LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                handle.device.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                    handle.handler,
                )
                val session = sessionOpen.poll(4000, TimeUnit.MILLISECONDS)?.value ?: error("会话创建超时")
                sessionRef = session
                val request = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                }
                session.setRepeatingRequest(request.build(), null, handle.handler)

                val depth = frames.poll(seconds * 1000L, TimeUnit.MILLISECONDS)?.value
                    ?: error("${seconds}s 内没有拿到深度帧（该摄像头 DEPTH_OUTPUT 可能仅对系统相机开放）")
                val stats = CameraProCodec.depthStats(depth)
                val stamp = System.currentTimeMillis()
                val dir = proCaptureDir(context, "depth")
                val depthPng = File(dir, "depth_$stamp.png")
                depthPng.writeBytes(PngWriter.gray16(size.width, size.height, depth))

                var colorPath: String? = null
                var colorUri: String? = null
                val colorRgb = CameraProCodec.colorizeDepth(
                    depth,
                    nearMm = if (stats.valid > 0) stats.p05Mm else 0,
                    farMm = if (stats.valid > 0) stats.p95Mm else 1,
                )
                if (args.boolArg("color_preview") != false) {
                    val file = File(dir, "depth_${stamp}_color.png")
                    file.writeBytes(PngWriter.rgb8(size.width, size.height, colorRgb))
                    colorPath = file.absolutePath
                }

                val intrinsics = depthIntrinsics(characteristics, size.width, size.height)
                var cloudPath: String? = null
                var cloudPoints = 0
                if (args.boolArg("point_cloud") == true) {
                    val maxPoints = (args.intArg("max_points") ?: 20000).coerceIn(1000, 200000)
                    val stride = max(1, (size.width / 512.0).roundToInt())
                    val points = CameraProCodec.pointCloud(
                        depth, size.width, size.height,
                        intrinsics[0], intrinsics[1], intrinsics[2], intrinsics[3],
                        stride = stride, maxPoints = maxPoints,
                    )
                    cloudPoints = points.size / 3
                    if (cloudPoints > 0) {
                        val file = File(dir, "cloud_$stamp.ply")
                        file.writeText(
                            PlyWriter.asciiPointCloud(
                                points,
                                colorRgb,
                                "andagent camera_depth ${size.width}x${size.height} mm units",
                            ),
                        )
                        cloudPath = file.absolutePath
                    }
                }

                return buildJsonObject {
                    put("status", "OK")
                    put("cameraId", cameraId)
                    put("resolution", "${size.width}x${size.height}")
                    put("depthPath", depthPng.absolutePath)
                    if (colorPath != null) put("colorPath", colorPath)
                    if (cloudPath != null) {
                        put("pointCloudPath", cloudPath)
                        put("points", cloudPoints)
                    }
                    put("unit", "mm")
                    put("validPixels", stats.valid)
                    put("coverage", stats.coverage)
                    put("minMm", stats.minMm)
                    put("maxMm", stats.maxMm)
                    put("meanMm", stats.meanMm)
                    put("medianMm", stats.medianMm)
                    put("p05Mm", stats.p05Mm)
                    put("p95Mm", stats.p95Mm)
                    put("intrinsics", buildJsonObject {
                        put("fx", intrinsics[0])
                        put("fy", intrinsics[1])
                        put("cx", intrinsics[2])
                        put("cy", intrinsics[3])
                    })
                }.toString()
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
            }
        } catch (e: Throwable) {
            return "ERROR: 深度采集失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            handle.close()
        }
    }

    private fun depthCapableCameras(
        manager: CameraManager,
    ): List<Triple<String, CameraCharacteristics, List<Size>>> {
        val ids = LinkedHashSet<String>()
        runCatching { ids.addAll(manager.cameraIdList) }
        if (Build.VERSION.SDK_INT >= 28) {
            for (id in manager.cameraIdList) {
                runCatching {
                    manager.getCameraCharacteristics(id).physicalCameraIds.forEach { ids.add(it) }
                }
            }
        }
        val out = ArrayList<Triple<String, CameraCharacteristics, List<Size>>>()
        for (id in ids) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)) continue
            val sizes = runCatching {
                c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.DEPTH16)?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            if (sizes.isNotEmpty()) out.add(Triple(id, c, sizes))
        }
        return out
    }

    /** [fx, fy, cx, cy]（像素单位），按深度图分辨率把 LENS_INTRINSIC_CALIBRATION 缩放。 */
    private fun depthIntrinsics(c: CameraCharacteristics, depthW: Int, depthH: Int): FloatArray {
        val calibration = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val reference = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val refW = reference?.width() ?: 0
        if (calibration != null && calibration.size >= 4 && refW > 0) {
            val scale = depthW.toFloat() / refW
            return floatArrayOf(
                calibration[0] * scale,
                calibration[1] * scale,
                calibration[2] * scale,
                calibration[3] * scale,
            )
        }
        val focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return if (focalMm > 0f && physical != null && physical.width > 0f) {
            val fx = focalMm * depthW / physical.width
            floatArrayOf(fx, fx, depthW / 2f, depthH / 2f)
        } else {
            floatArrayOf(0f, 0f, depthW / 2f, depthH / 2f)
        }
    }
}

/* ======================= camera_stream ======================= */

/**
 * camera_stream：ImageAnalysis 连续帧（YUV_420_888 repeating request）。
 *
 * 一次调用内做实时 CV 的可观测统计：亮度网格均值、帧间差分（运动强度）、
 * 可选 ZXing 条码命中与抽帧落盘。用于「持续盯着画面」而不是单帧拍照。
 */
class CameraStreamTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_stream"
    override val description =
        "连续取景分析（ImageAnalysis/重复请求）：统计帧数、实际 fps、亮度均值与帧间运动强度，可选条码识别与抽帧落盘，用于实时 CV 观察。"
    override val parameters = schemaOf(
        "camera" to strParam("back（默认）或 front"),
        "lens" to strParam("可选镜头：wide（默认）/ultrawide/tele/macro"),
        "seconds" to intParam("采集时长（秒），默认 3，范围 1-60"),
        "fps" to intParam("目标帧率上限，默认 30，范围 1-60"),
        "op" to strParam("分析内容：stats（默认，亮度+运动）/qr（条码）/frames（抽帧）/all"),
        "save_frames" to intParam("抽帧保存数量，默认 0，最大 20"),
        "resolution" to strParam("可选，格式 WxH；缺省取 ≤1280x720 的最大 YUV 尺寸"),
        "quality" to intParam("抽帧 JPEG 质量 1-100，默认 85"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"
        val pro = openProCamera(manager, args.strArg("camera") ?: "back", args.strArg("lens"))
            ?: return "ERROR: 打开摄像头失败（未找到或被占用）"
        try {
            val c = pro.handle.characteristics
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return "ERROR: 该摄像头未提供输出配置"
            val yuvSizes = runCatching { map.getOutputSizes(ImageFormat.YUV_420_888)?.toList() }
                .getOrNull() ?: emptyList()
            if (yuvSizes.isEmpty()) return "ERROR: 该摄像头不支持 YUV_420_888 输出"
            val requested = parseSizeArg(args.strArg("resolution"))
            val size = requested?.let { r -> yuvSizes.firstOrNull { it.width == r.width && it.height == r.height } }
                ?: yuvSizes.filter { it.width <= 1280 && it.height <= 720 }
                    .maxByOrNull { it.width.toLong() * it.height }
                ?: yuvSizes.minByOrNull { it.width.toLong() * it.height }!!

            val seconds = (args.intArg("seconds") ?: 3).coerceIn(1, 60)
            val wantFps = (args.intArg("fps") ?: 30).coerceIn(1, 60)
            val op = (args.strArg("op") ?: "stats").lowercase()
            val wantQr = op == "qr" || op == "all"
            val wantFrames = op == "frames" || op == "all"
            val saveFrames = (args.intArg("save_frames") ?: 0).coerceIn(0, 20)
            val quality = (args.intArg("quality") ?: 85).coerceIn(1, 100)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            val frameCount = AtomicInteger(0)
            val lumaSum = AtomicInteger(0)
            val lumaMin = AtomicInteger(255)
            val lumaMax = AtomicInteger(0)
            val motionSum = AtomicInteger(0)
            val motionPeak = AtomicInteger(0)
            val qrText = AtomicReference<String?>(null)
            val qrFormat = AtomicReference<String?>(null)
            val saved = mutableListOf<String>()
            val prevGrid = AtomicReference<IntArray?>(null)
            val scanReader = com.google.zxing.MultiFormatReader().apply {
                setHints(
                    mapOf(
                        com.google.zxing.DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }
            val framesDir = proCaptureDir(context, "stream")
            val stamp = System.currentTimeMillis()
            val lastSaveAt = AtomicReference(0L)
            var sessionRef: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ r ->
                    val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val data = ByteArray(plane.buffer.remaining())
                        plane.buffer.get(data)
                        val index = frameCount.incrementAndGet()
                        val grid = CameraProCodec.lumaGrid(data, image.width, image.height, plane.rowStride, 48, 36)
                        var sum = 0L
                        var lo = 255
                        var hi = 0
                        for (v in grid) {
                            sum += v
                            if (v < lo) lo = v
                            if (v > hi) hi = v
                        }
                        val mean = (sum / grid.size).toInt()
                        lumaSum.addAndGet(mean)
                        lumaMin.set(min(lumaMin.get(), lo))
                        lumaMax.set(max(lumaMax.get(), hi))
                        prevGrid.getAndSet(grid)?.let { prev ->
                            val diff = CameraProCodec.meanAbsDiff(prev, grid)
                            motionSum.addAndGet((diff * 100).toInt())
                            motionPeak.set(max(motionPeak.get(), (diff * 100).toInt()))
                        }
                        if (wantQr && qrText.get() == null && index % 5 == 0) {
                            try {
                                val source = CameraScanCodec.toZxingSource(
                                    CameraScanCodec.YPlane(data, image.width, image.height, plane.rowStride),
                                )
                                val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
                                synchronized(scanReader) {
                                    val res = scanReader.decodeWithState(bitmap)
                                    if (res != null) {
                                        qrText.set(res.text)
                                        qrFormat.set(res.barcodeFormat?.toString())
                                    }
                                }
                            } catch (_: Throwable) {
                                // 未命中是常态，不打断取景
                            } finally {
                                runCatching { scanReader.reset() }
                            }
                        }
                        if (wantFrames && saved.size < saveFrames) {
                            val now = System.currentTimeMillis()
                            if (now - lastSaveAt.get() >= 250L) {
                                lastSaveAt.set(now)
                                val jpeg = grayJpeg(data, image.width, image.height, plane.rowStride, quality)
                                if (jpeg != null) {
                                    val file = File(framesDir, "stream_${stamp}_${saved.size}.jpg")
                                    runCatching { file.writeBytes(jpeg) }
                                    if (file.exists() && file.length() > 0) {
                                        synchronized(saved) { saved.add(file.absolutePath) }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // 单帧异常不影响整体统计
                    } finally {
                        runCatching { image.close() }
                    }
                }, pro.handle.handler)

                val sessionOpen = LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                pro.handle.device.createCaptureSession(
                    listOf(reader.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                    pro.handle.handler,
                )
                val session = sessionOpen.poll(4000, TimeUnit.MILLISECONDS)?.value ?: error("会话创建超时")
                sessionRef = session
                val request = pro.handle.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?.map { intArrayOf(it.lower, it.upper) }
                    CameraProCodec.pickFpsRange(ranges, wantFps)?.let { range ->
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(range[0], range[1]))
                    }
                }
                val started = System.currentTimeMillis()
                session.setRepeatingRequest(request.build(), null, pro.handle.handler)
                kotlinx.coroutines.delay(seconds * 1000L)
                runCatching { session.stopRepeating() }
                val elapsed = max(1L, System.currentTimeMillis() - started)
                val frames = frameCount.get()
                if (frames <= 0) return "ERROR: ${seconds}s 内没有拿到任何 YUV 帧（相机可能被其它应用占用）"
                val avgLuma = lumaSum.get().toDouble() / frames
                val motionMean = motionSum.get().toDouble() / 100.0 / max(1, frames - 1)
                val motionMax = motionPeak.get() / 100.0

                return buildJsonObject {
                    put("status", "OK")
                    put("frames", frames)
                    put("seconds", elapsed / 1000.0)
                    put("avgFps", frames * 1000.0 / elapsed)
                    put("resolution", "${size.width}x${size.height}")
                    put("cameraId", pro.lensId)
                    put("luma", buildJsonObject {
                        put("mean", avgLuma)
                        put("min", lumaMin.get())
                        put("max", lumaMax.get())
                    })
                    put("motion", buildJsonObject {
                        put("mean", motionMean)
                        put("peak", motionMax)
                        put("level", CameraProCodec.motionLevel(motionMean))
                    })
                    qrText.get()?.let { text ->
                        put("qr", buildJsonObject {
                            put("text", text)
                            put("format", qrFormat.get() ?: "")
                        })
                    }
                    if (saved.isNotEmpty()) {
                        putJsonArray("framesSaved") { saved.forEach { add(it) } }
                    }
                    if (pro.notes.isNotEmpty()) putJsonArray("notes") { pro.notes.forEach { add(it) } }
                }.toString()
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
                runCatching { scanReader.reset() }
            }
        } catch (e: Throwable) {
            return "ERROR: 连续帧分析失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            pro.handle.close()
        }
    }

    /** Y 平面 → 灰度 JPEG（NV21 的 UV 填 128），避免引入 YUV→RGB 的额外拷贝。 */
    private fun grayJpeg(y: ByteArray, width: Int, height: Int, rowStride: Int, quality: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val nv21 = ByteArray(width * height * 3 / 2)
        for (row in 0 until height) {
            val src = row * rowStride
            if (src + width > y.size) break
            System.arraycopy(y, src, nv21, row * width, width)
        }
        java.util.Arrays.fill(nv21, width * height, nv21.size, 0x80.toByte())
        val out = ByteArrayOutputStream()
        val ok = runCatching {
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), quality, out)
        }.getOrDefault(false)
        return if (ok) out.toByteArray() else null
    }
}

/* ======================= camera_extension ======================= */

/**
 * camera_extension：厂商相机扩展（夜景/HDR/人像虚化/美颜）。
 *
 * 能力查询走 CameraExtensionCharacteristics（API 29+），真正建扩展会话需要 API 31+
 * 的 SessionConfiguration：sessionType 直接用扩展类型值 —— CameraX 的
 * camera-extensions 内部也是走同一条 HAL 通路，这里不额外引入 CameraX 依赖。
 */
class CameraExtensionTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "camera_extension"
    override val description =
        "查询/使用厂商相机扩展：night（夜景）/hdr/bokeh（人像虚化）/face_retouch（美颜）/auto，返回支持的扩展、预计延迟与文件路径；extension=info 只看能力。"
    override val parameters = schemaOf(
        "camera" to strParam("back（默认）或 front"),
        "extension" to strParam("auto（默认）/night/hdr/bokeh/face_retouch/info"),
        "resolution" to strParam("可选，格式 WxH；缺省取该扩展支持的最大 JPEG 尺寸"),
        "quality" to intParam("JPEG 质量 1-100，默认 95（扩展会话通常只支持 JPEG 输出）"),
    )

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.CAMERA))) return "ERROR: 相机权限未授予"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"
        val base = resolveCameraId(manager, args.strArg("camera") ?: "back")
            ?: return "ERROR: 未找到可用摄像头"
        val cameraId = base.first
        if (Build.VERSION.SDK_INT < 29) {
            return "ERROR: 设备 API ${Build.VERSION.SDK_INT} 无相机扩展能力查询（需 Android 10/API 29+）"
        }
        val supported = CameraExtensionProbe.supported(manager, cameraId)
        val rawName = args.strArg("extension") ?: "auto"
        val infoOnly = rawName.equals("info", true)
        if (infoOnly) return capabilityJson(manager, cameraId, supported)
        val ext = CameraExtensionProbe.fromName(rawName)
            ?: return "ERROR: 未知扩展 $rawName；可用：auto/night/hdr/bokeh/face_retouch/info"
        if (supported.isEmpty()) return "ERROR: 摄像头 #$cameraId 未声明任何厂商相机扩展"
        if (!supported.contains(ext)) {
            return "ERROR: 摄像头 #$cameraId 不支持 ${CameraExtensionProbe.name(ext)} 扩展；支持：${
                supported.joinToString(", ") { CameraExtensionProbe.name(it) }
            }"
        }
        if (Build.VERSION.SDK_INT < 31) {
            return "ERROR: 建立扩展会话需要 Android 12/API 31+（当前 API ${Build.VERSION.SDK_INT}）；" +
                "该机型的 ${CameraExtensionProbe.name(ext)} 能力只能由系统相机 App 或 CameraX 使用"
        }

        val extChars = runCatching { manager.getCameraExtensionCharacteristics(cameraId) }.getOrNull()
            ?: return "ERROR: 读取 CameraExtensionCharacteristics 失败"
        val sizes = CameraExtensionProbe.jpegSizes(manager, cameraId, ext)
        if (sizes.isEmpty()) return "ERROR: ${CameraExtensionProbe.name(ext)} 扩展没有可用的 JPEG 尺寸"
        val requested = parseSizeArg(args.strArg("resolution"))
        val size = requested?.let { r -> sizes.firstOrNull { it.width == r.width && it.height == r.height } }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height }!!
        val quality = (args.intArg("quality") ?: 95).coerceIn(1, 100)
        val latency = CameraExtensionProbe.latencyMs(manager, cameraId, ext, size)

        val handle = openCameraBlocking(manager, cameraId)
            ?: return "ERROR: 打开摄像头失败（被占用或已禁用）"
        try {
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val captured = LinkedBlockingQueue<CamEvent<ByteArray?>>()
            var sessionRef: CameraCaptureSession? = null
            try {
                reader.setOnImageAvailableListener({ r ->
                    val image = runCatching { r.acquireLatestImage() }.getOrNull()
                    if (image != null) {
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
                    }
                }, handle.handler)

                val sessionOpen = LinkedBlockingQueue<CamEvent<CameraCaptureSession?>>()
                val executor = Executor { command -> handle.handler.post(command) }
                val outputs = listOf(OutputConfiguration(reader.surface))
                val configuration = SessionConfiguration(
                    ext,
                    outputs,
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) { sessionOpen.post(s) }
                        override fun onConfigureFailed(s: CameraCaptureSession) { sessionOpen.post(null) }
                    },
                )
                val configured = runCatching { handle.device.createCaptureSession(configuration) }.isSuccess
                if (!configured) {
                    return "ERROR: 该机型拒绝以 ${CameraExtensionProbe.name(ext)} 扩展会话类型创建会话" +
                        "（部分厂商仅通过 CameraX/系统相机暴露扩展）"
                }
                val session = sessionOpen.poll(6000, TimeUnit.MILLISECONDS)?.value
                    ?: error("扩展会话创建超时")
                sessionRef = session
                val request = handle.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    val allowed = runCatching { extChars.getAvailableCaptureRequestKeys(ext) }.getOrNull()
                    if (allowed == null || allowed.contains(CaptureRequest.CONTROL_MODE)) {
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    }
                    if (allowed == null || allowed.contains(CaptureRequest.CONTROL_AF_MODE)) {
                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                }
                session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        r: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        captured.post(null)
                    }
                }, handle.handler)

                val jpeg = captured.poll(15000, TimeUnit.MILLISECONDS)?.value ?: error("扩展拍摄超时或失败")
                val stamp = System.currentTimeMillis()
                val dir = proCaptureDir(context, "extension")
                val out = File(dir, "ext_${CameraExtensionProbe.name(ext)}_$stamp.jpg")
                out.writeBytes(jpeg)
                val uri = publishImage(context, out)
                return buildJsonObject {
                    put("status", "WRITTEN")
                    put("extension", CameraExtensionProbe.name(ext))
                    put("path", out.absolutePath)
                    put("uri", uri ?: "")
                    put("galleryVisible", uri != null)
                    put("width", size.width)
                    put("height", size.height)
                    put("quality", quality)
                    put("size", out.length())
                    put("cameraId", cameraId)
                    if (latency != null) put("estimatedLatencyMs", latency)
                    putJsonArray("supportedExtensions") {
                        supported.forEach { add(CameraExtensionProbe.name(it)) }
                    }
                }.toString()
            } finally {
                runCatching { sessionRef?.close() }
                runCatching { reader.close() }
            }
        } catch (e: Throwable) {
            return "ERROR: 扩展拍摄失败（${e.message ?: e.javaClass.simpleName}）"
        } finally {
            handle.close()
        }
    }

    private fun capabilityJson(manager: CameraManager, cameraId: String, supported: List<Int>): String {
        val entries = buildJsonArray {
            supported.forEach { ext ->
                val sizes = CameraExtensionProbe.jpegSizes(manager, cameraId, ext)
                val biggest = sizes.maxByOrNull { it.width.toLong() * it.height }
                add(buildJsonObject {
                    put("extension", CameraExtensionProbe.name(ext))
                    put("value", ext)
                    put("jpegSizes", buildJsonArray { sizes.forEach { add("${it.width}x${it.height}") } })
                    if (biggest != null) {
                        CameraExtensionProbe.latencyMs(manager, cameraId, ext, biggest)?.let {
                            put("estimatedLatencyMs", it)
                        }
                    }
                })
            }
        }
        return buildJsonObject {
            put("status", "INFO")
            put("cameraId", cameraId)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("extensionSessionsSupported", Build.VERSION.SDK_INT >= 31)
            put("extensions", entries)
        }.toString()
    }
}

/** 供 MainActivity 之外的调用方（如 camera_info）复用的能力查询对象。 */
internal fun extensionProbeSupported(manager: CameraManager, cameraId: String): List<Int> =
    CameraExtensionProbe.supported(manager, cameraId)

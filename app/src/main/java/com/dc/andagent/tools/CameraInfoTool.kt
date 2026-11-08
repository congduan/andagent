package com.dc.andagent.tools

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.content.Context
import android.graphics.ImageFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** R68: camera_info——枚举全部摄像头硬件参数（免权限，CameraCharacteristics 公开元数据）。 */
class CameraInfoTool(private val context: Context, override val name: String) : Tool {
    override val description =
        "枚举全部摄像头：数量、朝向、传感器分辨率、输出格式与分辨率、光圈、焦距、能力、OIS、闪光灯、物理摄像头组、帧率范围；另含变焦范围、最近对焦距离、物理镜头角色（超广角/长焦/微距）、深度与 RAW 尺寸、厂商相机扩展（夜景/HDR）。"

    override val parameters: JsonObject = schemaOf()

    @Suppress("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: CameraManager 不可用"
        val ids = manager.cameraIdList
        if (ids.isEmpty()) return "ERROR: 设备没有可用摄像头"
        val cameras = buildJsonArray {
            for (id in ids) {
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                val pixelArray: android.util.Size? = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val streamMap: android.hardware.camera2.params.StreamConfigurationMap? =
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val outputs: List<Pair<Int, List<android.util.Size>>> = streamMap?.outputFormats
                    ?.map { fmt -> fmt to (streamMap.getOutputSizes(fmt)?.toList() ?: emptyList()) }
                    ?.take(20)
                    ?: emptyList()
                val formats = buildJsonArray {
                    outputs.forEach { (fmt, sizes) ->
                        add(buildJsonObject {
                            put("format", decodeFormat(fmt))
                            put("sizes", buildJsonArray {
                                sizes.distinctBy { it.width to it.height }.forEach { add("${it.width}x${it.height}") }
                            })
                        })
                    }
                }
                val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.map { decodeCapability(it) } ?: emptyList()
                val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { "[${it.lower}-${it.upper}]" } ?: emptyList()
                val physical: List<String> = if (android.os.Build.VERSION.SDK_INT >= 28)
                    c.physicalCameraIds.toList() else emptyList()
                val apertures: FloatArray? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val focal: FloatArray? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val oisModes: IntArray? = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                val zoomRatioRange = if (android.os.Build.VERSION.SDK_INT >= 30)
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                val minFocusDiopter = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val hyperfocalDiopter = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
                val depthSizes = runCatching { streamMap?.getOutputSizes(ImageFormat.DEPTH16)?.toList() }
                    .getOrNull() ?: emptyList()
                val rawSizes = runCatching { streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList() }
                    .getOrNull() ?: emptyList()
                val extensions = CameraExtensionProbe.supported(manager, id)
                val lenses = describeProLenses(manager, id)
                add(buildJsonObject {
                    put("id", id)
                    put("facing", facing)
                    put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                    if (pixelArray != null) put("sensorPixels", "${pixelArray.width}x${pixelArray.height}")
                    put("apertures", buildJsonArray { apertures?.forEach { add("f/$it") } })
                    put("focalLengthsMm", buildJsonArray { focal?.forEach { add(it.toDouble()) } })
                    put("capabilities", buildJsonArray { capabilities.forEach { add(it) } })
                    put("ois", oisModes?.contains(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true)
                    put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                    putJsonArray("fpsRanges") { fpsRanges.forEach { add(it) } }
                    put("physicalCameraIds", buildJsonArray { physical.forEach { add(it) } })
                    putJsonArray("outputFormats") { formats.forEach { add(it) } }
                    // R110: 手动/深度/扩展能力
                    put("logicalMultiCamera", physical.isNotEmpty())
                    put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                    if (zoomRatioRange != null) {
                        put("zoomRatioRange", "${zoomRatioRange.lower}-${zoomRatioRange.upper}")
                    }
                    put("minFocusDistanceCm", minFocusDiopter?.let { CameraProCodec.diopterToDistanceCm(it) } ?: 0f)
                    put("hyperfocalDistanceCm", hyperfocalDiopter?.let { CameraProCodec.diopterToDistanceCm(it) } ?: 0f)
                    putJsonArray("depthSizes") { depthSizes.forEach { add("${it.width}x${it.height}") } }
                    putJsonArray("rawSizes") { rawSizes.forEach { add("${it.width}x${it.height}") } }
                    putJsonArray("extensions") { extensions.forEach { add(CameraExtensionProbe.name(it)) } }
                    putJsonArray("lensRoles") {
                        lenses.forEach { lens ->
                            add(buildJsonObject {
                                put("id", lens.id)
                                put("role", lens.role)
                                put("focalMm", lens.focalMm)
                                lens.aperture?.let { put("aperture", it) }
                                lens.minFocusDistanceCm?.let { put("minFocusDistanceCm", it) }
                            })
                        }
                    }
                })
            }
        }
        return buildJsonObject {
            put("count", ids.size)
            put("cameras", cameras)
        }.toString()
    }

    internal companion object {
        /** HAL 输出格式：android-34 未将其公开为 ImageFormat 常量，值取自 HAL_PIXEL_FORMAT_*。 */
        private const val FORMAT_BLOB = 33 // HAL_PIXEL_FORMAT_BLOB
        /** Y16 同样未公开为 ImageFormat 常量（Y8 有），取值与 Y8 同一 fourcc 编码规则。 */
        private const val FORMAT_Y16 = 540422489 // HAL_PIXEL_FORMAT_Y16（fourcc " 61Y"，同 Y8 编码规则）

        /**
         * R69: 输出格式解码。全部引用 SDK 常量，避免 R68 手写魔数与 SDK 实际值错位。
         * 未知格式保留 `format_<数值>`，便于日志/模型侧识别而不是猜。
         */
        fun decodeFormat(fmt: Int): String = when (fmt) {
            ImageFormat.YUV_420_888 -> "YUV_420_888"
            ImageFormat.PRIVATE -> "PRIVATE"
            ImageFormat.JPEG -> "JPEG"
            ImageFormat.JPEG_R -> "JPEG_R"
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.RAW12 -> "RAW12"
            ImageFormat.HEIC -> "HEIC"
            ImageFormat.Y8 -> "Y8"
            ImageFormat.DEPTH16 -> "DEPTH16"
            ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
            FORMAT_BLOB -> "BLOB"
            FORMAT_Y16 -> "Y16"
            else -> "format_$fmt"
        }

        /** R69: REQUEST_AVAILABLE_CAPABILITIES_* 解码，值全部来自 CameraMetadata 常量。 */
        fun decodeCapability(cap: Int): String = when (cap) {
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
            else -> "capability_$cap"
        }
    }
}

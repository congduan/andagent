package com.dc.andagent.tools

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** R68: camera_info——枚举全部摄像头硬件参数（免权限，CameraCharacteristics 公开元数据）。 */
class CameraInfoTool(private val context: Context, override val name: String) : Tool {
    override val description =
        "枚举全部摄像头：数量、朝向、传感器分辨率、输出格式与分辨率、光圈、焦距、能力、OIS、闪光灯、物理摄像头组、帧率范围。"

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
                })
            }
        }
        return buildJsonObject {
            put("count", ids.size)
            put("cameras", cameras)
        }.toString()
    }

    private fun decodeFormat(fmt: Int): String = when (fmt) {
        0x11 -> "YUV_420_888"; 0x21 -> "PRIVATE"; 0x22 -> "JPEG"; 0x23 -> "RAW_SENSOR"
        0x20 -> "RAW10"; 0x3A -> "RAW12"; 0x25 -> "HEIC"; 0x100 -> "Y8"; 0x27 -> "Y16"
        else -> "format_$fmt"
    }

    private fun decodeCapability(cap: Int): String = when (cap) {
        0 -> "BACKWARD_COMPATIBLE"; 1 -> "MANUAL_SENSOR"; 2 -> "MANUAL_POST_PROCESSING"
        3 -> "RAW"; 4 -> "PRIVATE_REPROCESSING"; 5 -> "READ_SENSOR_SETTINGS"
        6 -> "BURST_CAPTURE"; 7 -> "YUV_REPROCESSING"; 8 -> "DEPTH_OUTPUT"
        9 -> "CONSTRAINED_HIGH_SPEED_VIDEO"; 10 -> "FACE_PRIORITY"
        12 -> "LOGICAL_MULTI_CAMERA"; 13 -> "MONOCHROME"; 14 -> "SECURE_IMAGE_DATA"
        15 -> "SYSTEM_CAMERA"; 16 -> "OFFLINE_PROCESSING"; 17 -> "ULTRA_WIDE_BANDSENSOR"
        18 -> "REMOTE_REPROCESSING"; 19 -> "DYNAMIC_DEPTH_TEN_BIT"; 20 -> "COLOR_SPACE_PROFILES"
        else -> "capability_$cap"
    }
}

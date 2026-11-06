package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R69: 锁定 CameraInfoTool 的格式/能力解码与 SDK 实际常量值一致。
 *
 * 期望值全部取自 android-34 android.jar 的 ConstantValue（javap 核实），
 * 一旦实现回退成手写魔数或错位映射，这里会直接失败。
 */
class CameraInfoToolTest {

    @Test
    fun decodeFormatMatchesImageFormatConstants() {
        val expected = mapOf(
            35 to "YUV_420_888",        // ImageFormat.YUV_420_888
            34 to "PRIVATE",            // ImageFormat.PRIVATE
            256 to "JPEG",              // ImageFormat.JPEG
            4101 to "JPEG_R",           // ImageFormat.JPEG_R
            32 to "RAW_SENSOR",         // ImageFormat.RAW_SENSOR
            37 to "RAW10",              // ImageFormat.RAW10
            38 to "RAW12",              // ImageFormat.RAW12
            33 to "BLOB",               // HAL_PIXEL_FORMAT_BLOB（ImageFormat 未公开）
            1212500294 to "HEIC",       // ImageFormat.HEIC
            538982489 to "Y8",          // ImageFormat.Y8
            540422489 to "Y16",         // HAL_PIXEL_FORMAT_Y16（ImageFormat 未公开）
            1144402265 to "DEPTH16",    // ImageFormat.DEPTH16
            1768253795 to "DEPTH_JPEG", // ImageFormat.DEPTH_JPEG
        )
        expected.forEach { (value, name) ->
            assertEquals("ImageFormat $value 应解码为 $name", name, CameraInfoTool.decodeFormat(value))
        }
    }

    @Test
    fun decodeCapabilityMatchesCameraMetadataConstants() {
        // 索引即 REQUEST_AVAILABLE_CAPABILITIES_* 的实际值 0..20
        val expected = listOf(
            "BACKWARD_COMPATIBLE",             // 0
            "MANUAL_SENSOR",                   // 1
            "MANUAL_POST_PROCESSING",          // 2
            "RAW",                             // 3
            "PRIVATE_REPROCESSING",            // 4
            "READ_SENSOR_SETTINGS",            // 5
            "BURST_CAPTURE",                   // 6
            "YUV_REPROCESSING",                // 7
            "DEPTH_OUTPUT",                    // 8
            "CONSTRAINED_HIGH_SPEED_VIDEO",    // 9
            "MOTION_TRACKING",                 // 10
            "LOGICAL_MULTI_CAMERA",            // 11
            "MONOCHROME",                      // 12
            "SECURE_IMAGE_DATA",               // 13
            "SYSTEM_CAMERA",                   // 14
            "OFFLINE_PROCESSING",              // 15
            "ULTRA_HIGH_RESOLUTION_SENSOR",    // 16
            "REMOSAIC_REPROCESSING",           // 17
            "DYNAMIC_RANGE_TEN_BIT",           // 18
            "STREAM_USE_CASE",                 // 19
            "COLOR_SPACE_PROFILES",            // 20
        )
        assertEquals(21, expected.size)
        expected.forEachIndexed { cap, name ->
            assertEquals("capability $cap 应解码为 $name", name, CameraInfoTool.decodeCapability(cap))
        }
    }

    @Test
    fun unknownValuesFallBackWithoutThrowing() {
        assertEquals("format_9999", CameraInfoTool.decodeFormat(9999))
        assertEquals("capability_9999", CameraInfoTool.decodeCapability(9999))
    }
}

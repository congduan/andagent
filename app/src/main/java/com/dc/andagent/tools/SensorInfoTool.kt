package com.dc.andagent.tools

import android.content.Context
import android.hardware.fingerprint.FingerprintManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** R68: sensor_info——指纹硬件能力（只读枚举；认证流程不适合 AI 驱动）。 */
class SensorInfoTool(private val context: Context, override val name: String) : Tool {
    override val description =
        "读取指纹硬件信息：传感器是否存在、是否已录入指纹。不做认证（认证是系统 UI 流程）。"

    override val parameters: JsonObject = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val fm = context.getSystemService(FingerprintManager::class.java)
            ?: return buildJsonObject {
                put("hardwareDetected", false)
                put("note", "此设备无 FingerprintManager 服务")
            }.toString()
        val hardware = try {
            fm.isHardwareDetected
        } catch (_: SecurityException) { false }
        val enrolled: Boolean? = try {
            fm.hasEnrolledFingerprints()
        } catch (_: SecurityException) { null }
        return buildJsonObject {
            put("hardwareDetected", hardware)
            put("enrolled", enrolled)
        }.toString()
    }
}

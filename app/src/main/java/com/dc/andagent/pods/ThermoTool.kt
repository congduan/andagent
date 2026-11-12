package com.dc.andagent.pods

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.strParam
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

val THERMO_TOOLS = listOf(
    "thermo_scan", "thermo_status", "thermo_monitor_start", "thermo_monitor_stop", "thermo_history",
)

/** R148：温湿度计 5 工具，工具层模式对齐 PodsTool。 */
class ThermoTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description = when (name) {
        "thermo_scan" -> "扫描附近 BLE 温湿度计（小米/青萍 LYWSD03MMC 等）的明文广播，无需配对连接即返回温度（摄氏+华氏）、湿度、电量、RSSI 与广播格式（ATC1441/pvvx4/pvvx5/MiBeacon3/MiBeacon5）。加密广播不破解，如实标注并计入计数。"
        "thermo_status" -> "查询最近缓存的温湿度计快照列表（温度、湿度、电量、数据年龄秒）与后台监控 running/scanError 状态。"
        "thermo_monitor_start" -> "开启前台服务低功耗连续监听温湿度广播，按 MAC 缓存最新值并记录时间序列（环形缓冲 256 条）。"
        "thermo_monitor_stop" -> "停止温湿度前台监听。"
        else -> "查询指定时间窗内的温湿度时间序列 JSON，可按 MAC 过滤。"
    }

    override val parameters = when (name) {
        "thermo_scan" -> schemaOf("duration_ms" to intParam("扫描窗口毫秒，默认 8000，范围 1000-30000"))
        "thermo_monitor_start" -> schemaOf("interval_ms" to intParam("历史采样间隔毫秒，默认 5000，范围 1000-60000"))
        "thermo_history" -> schemaOf(
            "minutes" to intParam("历史分钟数，默认 30，范围 1-1440"),
            "mac" to strParam("可选：指定设备 MAC 只看该设备，省略返回全部"),
        )
        else -> schemaOf()
    }

    /** 只申请 BLUETOOTH_SCAN（neverForLocation），绝不触发定位权限弹窗。 */
    private suspend fun permission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) permissions.ensure(listOf(Manifest.permission.BLUETOOTH_SCAN))
        else true

    private fun counters(obj: JsonObjectBuilder, showGuide: Boolean) {
        obj.put("unknownCount", ThermoHygroParsers.unknownCount)
        obj.put("encryptedCount", ThermoHygroParsers.encryptedCount)
        if (ThermoHygroParsers.encryptedCount > 0) obj.put("note", ThermoHygroParsers.ENCRYPTED_NOTE)
        if (showGuide) obj.put("guide", ThermoHygroParsers.GUIDE)
    }

    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        return when (name) {
        "thermo_scan" -> {
            if (!permission()) return "ERROR: 蓝牙扫描权限被拒绝"
            val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner ?: return "ERROR: 蓝牙未开启或不支持 BLE"
            val seen = mutableMapOf<String, ThermoSnapshot>()
            val error = AtomicInteger(0)
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    ThermoMonitor.decodeResult(result).forEach { s ->
                        synchronized(seen) { seen[s.mac ?: "unknown"] = s }
                        ThermoMonitor.accept(s)
                    }
                }
                override fun onScanFailed(code: Int) { error.set(code) }
            }
            try {
                scanner.startScan(ThermoMonitor.filters(), ThermoMonitor.settings(lowPower = false), cb)
                delay((args.intArg("duration_ms") ?: 8000).coerceIn(1000, 30000).toLong())
                if (error.get() != 0) "ERROR: BLE 扫描失败，代码 ${error.get()}"
                else {
                    val devices = synchronized(seen) { seen.values.sortedByDescending { it.rssi } }
                    buildJsonObject {
                        put("devices", buildJsonArray { devices.forEach { add(it.json()) } })
                        counters(this, devices.isEmpty())
                    }.toString()
                }
            } catch (e: Exception) { "ERROR: BLE 扫描失败：${e.message}" }
            finally { runCatching { scanner.stopScan(cb) } }
        }
        "thermo_status" -> {
            val snaps = ThermoMonitor.snapshots()
            buildJsonObject {
                put("devices", buildJsonArray {
                    snaps.forEach { s ->
                        add(buildJsonObject {
                            s.json().forEach { (k, v) -> put(k, v) }
                            put("ageSeconds", (System.currentTimeMillis() - s.ts) / 1000)
                        })
                    }
                })
                put("running", ThermoMonitor.running)
                put("scanError", ThermoMonitor.scanError)
                counters(this, snaps.isEmpty())
            }.toString()
        }
        "thermo_monitor_start" -> {
            if (!permission()) return "ERROR: 蓝牙扫描权限被拒绝"
            val interval = (args.intArg("interval_ms") ?: 5000).coerceIn(1000, 60000)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ThermoService::class.java).putExtra("interval", interval.toLong()),
                )
                "已请求启动温湿度前台监听（间隔 ${interval}ms）；thermo_status 读最新值，thermo_history 读时间序列。"
            } catch (e: Exception) { "ERROR: 无法启动监听：${e.message}" }
        }
        "thermo_monitor_stop" -> {
            context.stopService(Intent(context, ThermoService::class.java))
            ThermoMonitor.stop()
            "温湿度监听已停止"
        }
        "thermo_history" -> {
            val minutes = (args.intArg("minutes") ?: 30).coerceIn(1, 1440)
            val mac = args.strArg("mac")
            buildJsonObject {
                put("minutes", minutes)
                put("mac", mac)
                put("history", ThermoMonitor.historyJson(minutes, mac = mac))
                put("running", ThermoMonitor.running)
                counters(this, false)
            }.toString()
        }
        else -> "ERROR: 未知工具"
        }
    }
}

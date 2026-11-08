package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val BT_CONNECT = "android.permission.BLUETOOTH_CONNECT"
private const val BT_SCAN = "android.permission.BLUETOOTH_SCAN"
private fun adapter(context: Context) = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
private suspend fun btPermission(broker: PermissionBroker, scan: Boolean = false): Boolean {
    val names = if (Build.VERSION.SDK_INT >= 31) {
        if (scan) listOf(BT_SCAN, Manifest.permission.ACCESS_FINE_LOCATION) else listOf(BT_CONNECT)
    } else if (scan) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
    return broker.ensure(names)
}
private fun denied(broker: PermissionBroker, vararg names: String): String =
    if (names.any { broker.statusOf(it) == "permanently_denied" }) "ERROR: 权限被永久拒绝，请去系统设置开启" else "ERROR: 用户拒绝了蓝牙/定位权限"

@SuppressLint("MissingPermission")
class BtScanTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "bt_scan"
    override val description = "扫描附近蓝牙设备，返回名称、MAC 和 RSSI；扫描时请求蓝牙扫描与定位权限。"
    override val parameters = schemaOf("duration" to intParam("扫描秒数，默认 10，范围 1~30"))
    override suspend fun execute(args: JsonObject): String {
        if (!btPermission(permissions, true)) return denied(permissions, BT_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        val scanner = adapter(context)?.bluetoothLeScanner ?: return "ERROR: 蓝牙未开启或设备不支持 BLE 扫描"
        val found = ConcurrentHashMap<String, Pair<String, Int>>()
        val error = AtomicInteger(0)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                runCatching { found[device.address] = (device.name ?: result.scanRecord?.deviceName ?: "未知设备") to result.rssi }
            }
            override fun onScanFailed(errorCode: Int) { error.set(errorCode) }
        }
        return try {
            scanner.startScan(callback)
            delay((args.intArg("duration") ?: 10).coerceIn(1, 30) * 1000L)
            if (error.get() != 0) "ERROR: 蓝牙扫描失败，代码 ${error.get()}" else buildJsonObject {
                put("devices", buildJsonArray { found.toSortedMap().forEach { (mac, value) -> add(buildJsonObject {
                    put("name", value.first); put("mac", mac); put("rssi", value.second)
                }) } })
            }.toString()
        } catch (e: Exception) { "ERROR: 蓝牙扫描失败：${e.message}" }
        finally { runCatching { scanner.stopScan(callback) } }
    }
}

@SuppressLint("MissingPermission")
class BtPairedListTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "bt_paired_list"
    override val description = "列出已配对蓝牙设备的名称、MAC 与类型。"
    override val parameters = schemaOf()
    override suspend fun execute(args: JsonObject): String {
        if (!btPermission(permissions)) return denied(permissions, BT_CONNECT)
        return runCatching { buildJsonObject { put("devices", buildJsonArray {
            adapter(context)?.bondedDevices?.forEach { d -> add(buildJsonObject {
                put("name", d.name ?: "未知设备"); put("mac", d.address); put("type", d.type)
            }) }
        }) }.toString() }.getOrElse { "ERROR: 读取配对设备失败：${it.message}" }
    }
}

@SuppressLint("MissingPermission")
class BtConnectTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "bt_connect"
    override val description = "对已配对 BLE 设备尝试 GATT 连接；未配对设备弹出系统配对框。并非所有蓝牙音频设备支持 GATT。"
    override val parameters = schemaOf("mac" to strParam("设备 MAC 地址"), required = listOf("mac"))
    override suspend fun execute(args: JsonObject): String {
        if (!btPermission(permissions)) return denied(permissions, BT_CONNECT)
        val mac = args.strArg("mac") ?: return "ERROR: 缺少 mac"
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return "ERROR: MAC 地址格式无效"
        val device = runCatching { adapter(context)?.getRemoteDevice(mac) }.getOrNull() ?: return "ERROR: 蓝牙不可用"
        return try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                if (device.createBond()) "已弹出系统配对请求，请在系统界面确认；连接状态需稍后再查" else "ERROR: 无法发起配对"
            } else {
                val state = AtomicInteger(-1)
                val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        state.set(if (status == BluetoothGatt.GATT_SUCCESS) newState else -2)
                    }
                }) ?: return "ERROR: GATT 连接无法启动，设备可能仅支持经典蓝牙"
                delay(8000)
                val connected = state.get() == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                if (connected) { activeGatts[mac]?.close(); activeGatts[mac] = gatt }
                else gatt.close()
                if (connected) "已连接 GATT：$mac；音频/串口配置仍需系统蓝牙界面" else "ERROR: GATT 未连接，设备可能仅支持经典蓝牙；请在系统蓝牙界面连接"
            }
        } catch (e: Exception) { "ERROR: 蓝牙连接失败：${e.message}" }
    }
    companion object { private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>() }
}

class WifiConnectTool(private val context: Context) : Tool {
    override val name = "wifi_connect"
    override val description = "打开系统 Wi-Fi 面板，请用户手动选择指定网络。"
    override val parameters = schemaOf("ssid" to strParam("希望连接的 Wi-Fi 名称"), required = listOf("ssid"))
    override suspend fun execute(args: JsonObject): String {
        val ssid = args.strArg("ssid") ?: return "ERROR: 缺少 ssid"
        return withContext(Dispatchers.Main) {
            runCatching {
                val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "已打开 Wi-Fi 面板，请在面板中选择 $ssid 并完成连接"
            }.getOrElse { "ERROR: 无法打开 Wi-Fi 设置：${it.message}" }
        }
    }
}

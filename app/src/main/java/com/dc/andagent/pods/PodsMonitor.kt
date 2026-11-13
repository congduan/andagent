package com.dc.andagent.pods

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.dc.andagent.R
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.tools.KvStore
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.schemaOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** Keeps only short-lived snapshots. Address is used to separate nearby devices, never exported. */
object PodsMonitor {
    private val lock = Any()
    private val latest = mutableMapOf<String, PodSnapshot>()
    private val history = ArrayDeque<PodSnapshot>()
    private const val CAPACITY = 512
    @Volatile var running = false
        private set
    @Volatile var scanError = 0
        private set
    private var callback: ScanCallback? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var lastPersisted = 0L
    private var intervalMs = 5000L

    private fun key(s: PodSnapshot) = "${s.modelId}:${s.address ?: "unknown"}"
    fun accept(s: PodSnapshot, context: Context? = null) {
        synchronized(lock) {
            latest.entries.removeAll { s.timestamp - it.value.timestamp > 10 * 60_000L }
            val key = key(s)
            val old = latest[key]
            latest[key] = s
            if (old == null || old.copy(rssi = s.rssi, timestamp = s.timestamp) != s || s.timestamp - lastPersisted >= intervalMs) {
                history.addLast(s)
                while (history.size > CAPACITY) history.removeFirst()
            }
            if (context != null && s.timestamp - lastPersisted >= intervalMs) {
                lastPersisted = s.timestamp
                runCatching {
                    val store = KvStore(context)
                    store.set("airpods", "latest", s.json())
                    store.set("airpods", "history", historyJson(30, 100))
                }
            }
        }
    }
    fun snapshots(since: Long = 0) = synchronized(lock) {
        latest.values.filter { it.timestamp >= since }.sortedWith(compareByDescending<PodSnapshot> { it.isConnected == true }.thenByDescending { it.rssi })
    }
    // 已连接设备广播稀疏（约 2 分钟一包），窗口放宽到 30 分钟；ageSeconds 由调用方展示数据新旧。
    fun primary() = snapshots(System.currentTimeMillis() - 30 * 60_000L).firstOrNull()
    fun historyJson(minutes: Int, limit: Int = CAPACITY) = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        buildJsonArray { history.filter { it.timestamp >= cutoff }.takeLast(limit).forEach { add(it.json()) } }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, interval: Long): Boolean {
        synchronized(lock) {
            if (running) { intervalMs = interval; return true }
            val ble = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return false
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val bytes = result.scanRecord?.getManufacturerSpecificData(0x004c) ?: return
                    AppleBleParser.parse(bytes, result.rssi, result.device?.address)?.let { accept(it, context) }
                }
                override fun onScanFailed(errorCode: Int) { scanError = errorCode; running = false }
            }
            return try {
                ble.startScan(filters(), settings(), cb)
                scanner = ble; callback = cb; intervalMs = interval; scanError = 0; running = true; true
            } catch (_: Exception) { false }
        }
    }
    @SuppressLint("MissingPermission")
    fun stop() = synchronized(lock) {
        callback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        callback = null; scanner = null; running = false
    }
    // R149b：不要按首字节 0x07 过滤——佩戴/使用中的 AirPods 广播常以其它
    // Continuity 帧打头（0x02 near-info 等），0x07 状态帧在链的后段；按前缀过滤
    // 会把整包丢掉（「戴在耳朵上扫不出来」的根因）。只按 company id 过滤，
    // AppleBleParser.parse 的链式解析自己找 0x07 帧。
    fun filters() = listOf(ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf(), byteArrayOf()).build())
    // monitor 常驻监听用 LOW_POWER（已连接设备广播稀疏，约 2 分钟一包；
    // LOW_LATENCY 高占空比扫描不可长期跑）。显式 scan 工具用低延迟短窗。
    fun settings(lowPower: Boolean = true) = ScanSettings.Builder()
        .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build()
}

class PodsService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!PodsMonitor.running) { stopSelf(); return }
            handler.postDelayed(this, 5000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { PodsMonitor.stop(); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("airpods_monitor", "AirPods 监听", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, PodsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, "airpods_monitor")
            .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle("AirPods 广播监听中")
            .setContentText("监听电量与佩戴状态").setOngoing(true)
            .addAction(0, "停止", stop).build()
        startForeground(6501, notification)
        if (!PodsMonitor.start(this, intent?.getLongExtra("interval", 5000) ?: 5000)) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(check); handler.postDelayed(check, 5000)
        return START_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(check); PodsMonitor.stop(); super.onDestroy() }
    companion object { const val ACTION_STOP = "com.dc.andagent.pods.STOP" }
}

val PODS_TOOLS = listOf("airpods_scan", "airpods_status", "airpods_battery", "airpods_monitor_start", "airpods_monitor_stop", "airpods_history")

class PodsTool(private val context: Context, private val permissions: PermissionBroker, override val name: String) : Tool {
    override val description = when (name) {
        "airpods_scan" -> "扫描附近 AirPods/Beats 的公开 BLE 广播，返回型号、电量、佩戴、盒盖和广播推断连接态。"
        "airpods_status" -> "查询最近 30 秒内主用耳机广播快照。"
        "airpods_battery" -> "查询主用耳机左、右、盒电量与充电状态。"
        "airpods_monitor_start" -> "开启前台服务持续监听 AirPods 广播；最新值和历史写入 airpods KV 命名空间。"
        "airpods_monitor_stop" -> "停止 AirPods 前台监听。"
        else -> "读取监听期间最近一段时间的 AirPods 电量与佩戴历史。"
    }
    override val parameters = when (name) {
        "airpods_scan" -> schemaOf("duration_ms" to intParam("扫描毫秒，默认 3000，范围 1000-30000"))
        "airpods_monitor_start" -> schemaOf("interval_ms" to intParam("KV 保存间隔毫秒，默认 5000，范围 1000-60000"))
        "airpods_history" -> schemaOf("duration_min" to intParam("历史分钟数，默认 30，范围 1-30"))
        else -> schemaOf()
    }
    private suspend fun permission(): Boolean = permissions.ensure(if (Build.VERSION.SDK_INT >= 31)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION))
    @SuppressLint("MissingPermission")
    override suspend fun execute(args: JsonObject): String {
        return when (name) {
        "airpods_scan" -> {
            if (!permission()) return "ERROR: 蓝牙扫描权限被拒绝"
            val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner
                ?: return "ERROR: 蓝牙未开启或不支持 BLE"
            val seen = mutableMapOf<String, PodSnapshot>()
            val error = AtomicInteger(0)
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) {
                    val bytes = result.scanRecord?.getManufacturerSpecificData(0x004c) ?: return
                    AppleBleParser.parse(bytes, result.rssi, result.device?.address)?.let { s ->
                        synchronized(seen) { seen["${s.modelId}:${s.address}"] = s }
                        PodsMonitor.accept(s)
                    }
                }
                override fun onScanFailed(code: Int) { error.set(code) }
            }
            try {
                scanner.startScan(PodsMonitor.filters(), PodsMonitor.settings(lowPower = false), cb)
                delay((args.intArg("duration_ms") ?: 3000).coerceIn(1000, 30000).toLong())
                if (error.get() != 0) "ERROR: BLE 扫描失败，代码 ${error.get()}" else buildJsonObject {
                    put("devices", buildJsonArray { synchronized(seen) { seen.values.sortedByDescending { it.rssi }.forEach { add(it.json()) } } })
                }.toString()
            } catch (e: Exception) { "ERROR: BLE 扫描失败：${e.message}" }
            finally { runCatching { scanner.stopScan(cb) } }
        }
        "airpods_status" -> PodsMonitor.primary()?.let { s ->
                val ageSec = (System.currentTimeMillis() - s.timestamp) / 1000
                buildJsonObject { s.json().let { j -> j.forEach { (k, v) -> put(k, v) } }; put("ageSeconds", ageSec) }.toString()
            }
            ?: "没有缓存到 AirPods 广播。注意：耳机连接到手机后广播会变稀疏（约 2 分钟一包），请开启 airpods_monitor_start 持续监听；或打开盒盖触发密集广播后重试。"
        "airpods_battery" -> PodsMonitor.primary()?.let { s -> buildJsonObject {
            put("model", s.model); put("batteryLeft", s.batteryLeft); put("batteryRight", s.batteryRight)
            put("batteryCase", s.batteryCase); put("chargingL", s.chargingL); put("chargingR", s.chargingR)
            put("chargingCase", s.chargingCase); put("timestamp", s.timestamp)
        }.toString() } ?: "没有缓存到 AirPods 电量。请开启 airpods_monitor_start 持续监听，或打开盒盖触发广播后重试。"
        "airpods_monitor_start" -> {
            if (!permission()) return "ERROR: 蓝牙扫描权限被拒绝"
            val interval = (args.intArg("interval_ms") ?: 5000).coerceIn(1000, 60000)
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, PodsService::class.java).putExtra("interval", interval.toLong()))
                "已请求启动 AirPods 前台监听；airpods/latest 和 airpods/history 可由 KV 读取。"
            } catch (e: Exception) { "ERROR: 无法启动监听：${e.message}" }
        }
        "airpods_monitor_stop" -> { context.stopService(Intent(context, PodsService::class.java)); PodsMonitor.stop(); "AirPods 监听已停止" }
        "airpods_history" -> buildJsonObject {
            put("history", PodsMonitor.historyJson((args.intArg("duration_min") ?: 30).coerceIn(1, 30)))
        }.toString()
        else -> "ERROR: 未知工具"
        }
    }
}

package com.dc.andagent.pods

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import com.dc.andagent.R
import kotlinx.serialization.json.buildJsonArray
import java.util.ArrayDeque

/**
 * R148：BLE 温湿度计广播监控器，架构复刻 PodsMonitor（扫描 + 快照缓存 + 环形历史 +
 * 低功耗连续扫描），仅把解析器换成 ThermoHygroParsers。
 * 零新增权限：复用 manifest 里 BLUETOOTH_SCAN（neverForLocation），不申请定位权限。
 */
object ThermoMonitor {
    private val lock = Any()
    private val latest = mutableMapOf<String, ThermoSnapshot>()
    private val history = ArrayDeque<ThermoSnapshot>()
    private const val CAPACITY = 256

    @Volatile var running = false
        private set
    @Volatile var scanError = 0
        private set

    private var callback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var lastHistory = 0L
    private var intervalMs = 5000L
    private var filterFallbackUsed = false

    private val SERVICE_181A = ParcelUuid.fromString(ThermoHygroParsers.UUID_181A)
    private val SERVICE_FE95 = ParcelUuid.fromString(ThermoHygroParsers.UUID_FE95)

    private fun key(s: ThermoSnapshot) = s.mac ?: "unknown"

    fun accept(s: ThermoSnapshot) {
        synchronized(lock) {
            latest.entries.removeAll { s.ts - it.value.ts > 10 * 60_000L }
            val k = key(s)
            val old = latest[k]
            latest[k] = s
            // 读数变化立即记一条；读数不变也按 intervalMs 周期采样，形成时间序列。
            if (old == null || old.copy(rssi = s.rssi, ts = s.ts) != s || s.ts - lastHistory >= intervalMs) {
                history.addLast(s)
                while (history.size > CAPACITY) history.removeFirst()
                lastHistory = s.ts
            }
        }
    }

    fun snapshots(since: Long = 0) = synchronized(lock) {
        latest.values.filter { it.ts >= since }.sortedByDescending { it.rssi }
    }

    fun historyJson(minutes: Int, limit: Int = CAPACITY, mac: String? = null) = synchronized(lock) {
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        buildJsonArray {
            history.filter { it.ts >= cutoff && (mac == null || it.mac?.equals(mac, ignoreCase = true) == true) }
                .takeLast(limit).forEach { add(it.json()) }
        }
    }

    /** 宽 filter：0x181A（ATC1441/pvvx）与 0xFE95（MiBeacon）两条 service data 并行。 */
    fun filters() = listOf(
        ScanFilter.Builder().setServiceData(SERVICE_181A, byteArrayOf(), byteArrayOf()).build(),
        ScanFilter.Builder().setServiceData(SERVICE_FE95, byteArrayOf(), byteArrayOf()).build(),
    )

    // 常驻监听用 LOW_POWER；显式 scan 工具用低延迟短窗。
    fun settings(lowPower: Boolean = true) = ScanSettings.Builder()
        .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0)
        .build()

    /** 把一条 ScanResult 里的两类 service data 交给解析分发器，返回快照列表（不写缓存）。 */
    fun decodeResult(result: ScanResult): List<ThermoSnapshot> {
        val record = result.scanRecord ?: return emptyList()
        val ts = System.currentTimeMillis()
        val out = mutableListOf<ThermoSnapshot>()
        runCatching {
            record.getServiceData(SERVICE_181A)?.let { d ->
                ThermoHygroParsers.decode(ThermoHygroParsers.UUID_181A, d, result.rssi, null, ts)?.let(out::add)
            }
            record.getServiceData(SERVICE_FE95)?.let { d ->
                ThermoHygroParsers.decode(ThermoHygroParsers.UUID_FE95, d, result.rssi, null, ts)?.let(out::add)
            }
        }
        return out
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, interval: Long = 5000L): Boolean {
        synchronized(lock) {
            if (running) { intervalMs = interval; return true }
            val ble = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.bluetoothLeScanner ?: return false
            filterFallbackUsed = false
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    decodeResult(result).forEach(::accept)
                }
                override fun onScanFailed(errorCode: Int) {
                    // 部分芯片不支持 service data 硬件过滤，退化为无 filter 扫描（回调内再按 UUID 分流）。
                    if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED && !filterFallbackUsed) {
                        filterFallbackUsed = true
                        runCatching { ble.startScan(null, settings(), this) }
                    } else { scanError = errorCode; running = false }
                }
            }
            return try {
                ble.startScan(filters(), settings(), cb)
                scanner = ble; callback = cb; intervalMs = interval; scanError = 0; running = true; true
            } catch (_: Exception) {
                try {
                    filterFallbackUsed = true
                    ble.startScan(null, settings(), cb)
                    scanner = ble; callback = cb; intervalMs = interval; scanError = 0; running = true; true
                } catch (_: Exception) { false }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() = synchronized(lock) {
        callback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        callback = null; scanner = null; running = false
    }
}

/** 前台服务让低功耗连续扫描在后台存活；与 PodsService 同构。 */
class ThermoService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!ThermoMonitor.running) { stopSelf(); return }
            handler.postDelayed(this, 5000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { ThermoMonitor.stop(); stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("thermo_monitor", "温湿度广播监听", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ThermoService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, "thermo_monitor")
            .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle("温湿度广播监听中")
            .setContentText("扫描附近温湿度计的明文广播").setOngoing(true)
            .addAction(0, "停止", stop).build()
        startForeground(6502, notification)
        if (!ThermoMonitor.start(this, intent?.getLongExtra("interval", 5000) ?: 5000)) { stopSelf(); return START_NOT_STICKY }
        handler.removeCallbacks(check); handler.postDelayed(check, 5000)
        return START_STICKY
    }
    override fun onDestroy() { handler.removeCallbacks(check); ThermoMonitor.stop(); super.onDestroy() }
    companion object { const val ACTION_STOP = "com.dc.andagent.pods.THERMO_STOP" }
}

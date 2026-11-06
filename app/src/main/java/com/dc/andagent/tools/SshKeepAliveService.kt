package com.dc.andagent.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dc.andagent.MainActivity

/** Holds network and CPU awake while at least one SSH session is in the pool. */
class SshKeepAliveService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "SSH 会话", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = notification(this, null, null)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(ID, notification)
        try {
            if (wifiLock == null) {
                wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "andagent:ssh").also { it.acquire() }
            }
            if (wakeLock == null) {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "andagent:ssh").also { it.acquire() }
            }
        } catch (e: Exception) {
            releaseLocks()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { releaseLocks() } finally { super.onDestroy() }
    }

    private fun releaseLocks() {
        try { wifiLock?.takeIf { it.isHeld }?.release() } finally {
            wifiLock = null
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    companion object {
        private const val CHANNEL = "ssh_keep_alive"
        private const val ID = 9210
        private fun notification(context: Context, file: String?, percent: Int?) =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("SSH 会话运行中")
                .setContentText(if (file == null) "保持网络连接，点击返回 AndAgent" else "正在下载 $file")
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .apply { if (percent != null) setProgress(100, percent, false) }
                .build()
        fun progress(context: Context, file: String?, percent: Int?) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(ID, notification(context, file, percent))
        }
        fun ensureStarted(context: Context) {
            val intent = Intent(context, SshKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
        fun stop(context: Context) { context.stopService(Intent(context, SshKeepAliveService::class.java)) }
    }
}

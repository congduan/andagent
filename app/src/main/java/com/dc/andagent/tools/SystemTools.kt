package com.dc.andagent.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.Uri
import com.dc.andagent.MainActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dc.andagent.R
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Battery level/charging/health details from BatteryManager + the sticky intent. */
class BatteryDetailTool(private val context: Context) : Tool {
    override val name = "battery_detail"
    override val description = "读取电池详情：电量百分比、充电状态、供电方式、健康度、温度、电压、电池技术。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        return runCatching {
            val bm = context.getSystemService(BatteryManager::class.java)
            val sticky = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
            val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
            val technology = sticky?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: ""

            buildJsonObject {
                put("percent", if (capacity in 0..100) capacity else percent)
                put("status", statusText(status))
                put("charging", bm?.isCharging ?: (status == BatteryManager.BATTERY_STATUS_CHARGING))
                put("plugged", pluggedText(plugged))
                put("health", healthText(health))
                put("temperatureC", "%.1f".format(temperature / 10.0))
                put("voltageV", "%.3f".format(voltage / 1000.0))
                put("technology", technology)
                bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { microAmps ->
                    // Some OEMs report µA, others mA; only expose a plausible range.
                    val milliamps = microAmps / 1000.0
                    if (milliamps in -20_000.0..20_000.0) {
                        put("currentMa", "%.0f".format(milliamps))
                    }
                }
            }.toString()
        }.getOrElse { "ERROR: 读取电池信息失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun statusText(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun pluggedText(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "unplugged"
        else -> "other"
    }

    private fun healthText(health: Int): String = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        else -> "unknown"
    }
}

/**
 * Wi-Fi status. Android requires location permission (and an enabled location
 * toggle) to read SSID/BSSID; when unavailable we still report network type/IP.
 */
class WifiInfoTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "wifi_info"
    override val description =
        "读取 Wi-Fi 信息：SSID、信号强度、IP 地址、链路速度。定位权限被拒时降级为只报告网络类型。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val ip = network?.let { cm?.getLinkProperties(it) }
                ?.linkAddresses
                ?.firstOrNull { it.address is java.net.Inet4Address && !it.address.isLoopbackAddress }
                ?.address?.hostAddress

            if (type != "wifi") {
                return@runCatching buildJsonObject {
                    put("type", type)
                    if (ip != null) put("ip", ip) else put("ip", JsonNull)
                    put("note", if (type == "none") "当前没有可用网络" else "当前未连接 Wi-Fi")
                }.toString()
            }

            val granted = permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val info = if (granted) wifi?.connectionInfo else null
            val ssid = info?.ssid
                ?.trim('"')
                ?.takeUnless { it.isBlank() || it == "<unknown ssid>" || it.contains("unknown", true) }
            @Suppress("DEPRECATION")
            val rssi = info?.rssi ?: 0
            @Suppress("DEPRECATION")
            val frequency = info?.frequency ?: 0
            @Suppress("DEPRECATION")
            val linkSpeed = info?.linkSpeed ?: -1

            buildJsonObject {
                put("type", "wifi")
                if (ip != null) put("ip", ip) else put("ip", JsonNull)
                put("wifiEnabled", wifi?.isWifiEnabled ?: false)
                if (granted && ssid != null) {
                    put("ssid", ssid)
                    put("rssiDbm", rssi)
                    put("signalLevel", signalText(rssi))
                    if (frequency > 0) put("frequencyMHz", frequency)
                    if (linkSpeed > 0) put("linkSpeedMbps", linkSpeed)
                } else {
                    put("ssid", JsonNull)
                    put(
                        "note",
                        "定位权限未授予或系统定位关闭，无法读取 SSID/信号强度；已降级为网络类型 + IP",
                    )
                }
            }.toString()
        }.getOrElse { "ERROR: 读取 Wi-Fi 信息失败：${it.message ?: it.javaClass.simpleName}" }
    }

    @Suppress("DEPRECATION")
    private fun signalText(rssi: Int): String {
        val level = WifiManager.calculateSignalLevel(rssi, 5)
        return "$level/5 (${rssi}dBm)"
    }
}

class ClipboardWriteTool(private val context: Context) : Tool {
    override val name = "clipboard_write"
    override val description = "把文本写入系统剪贴板，方便用户粘贴到其他应用。"
    override val parameters = schemaOf(
        "text" to strParam("要复制的文本"),
        "label" to strParam("可选：剪贴板标签，默认 AndAgent"),
        required = listOf("text"),
    )

    override suspend fun execute(args: JsonObject): String {
        val text = args.strArg("text") ?: return "ERROR: " + tr("缺少 text", "missing text")
        val label = args.strArg("label") ?: "AndAgent"
        return runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
                ?: return@runCatching "ERROR: " + tr("系统剪贴板不可用", "system clipboard unavailable")
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            tr("已写入剪贴板（${text.length} 字）", "Copied to clipboard (${text.length} chars)")
        }.getOrElse {
            "ERROR: " + tr("写入剪贴板失败：${it.message ?: it.javaClass.simpleName}", "could not write to clipboard: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}

class ClipboardReadTool(private val context: Context) : Tool {
    override val name = "clipboard_read"
    override val description = "读取系统剪贴板文本。Android 10+ 只有当前应用窗口有焦点时才能读取，失败会返回明确原因。"
    override val parameters = schemaOf()
    private val maxChars = 8_000

    override suspend fun execute(args: JsonObject): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return "ERROR: " + tr("系统剪贴板不可用", "system clipboard unavailable")
        if (!cm.hasPrimaryClip()) return "ERROR: " + tr("剪贴板为空", "clipboard is empty")
        val clip = cm.primaryClip
            ?: return "ERROR: " + tr(
                "无法读取剪贴板（Android 10+ 要求应用窗口有焦点）",
                "could not read clipboard (Android 10+ requires the app window to have focus)",
            )
        if (clip.itemCount == 0) return "ERROR: " + tr("剪贴板为空", "clipboard is empty")
        val text = runCatching {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }.getOrElse {
            return "ERROR: " + tr("读取剪贴板失败：${it.message ?: it.javaClass.simpleName}", "could not read clipboard: ${it.message ?: it.javaClass.simpleName}")
        }
        if (text.isEmpty()) return "ERROR: " + tr("剪贴板内容不是文本，或为空", "clipboard content is not text or is empty")
        return buildJsonObject {
            put("text", if (text.length > maxChars) text.take(maxChars) else text)
            put("chars", text.length)
            put("truncated", text.length > maxChars)
        }.toString()
    }
}

class NotifySendTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "notify_send"
    override val description = "发送一条本地通知（大文本 / 进度条样式），可指定点击后跳回对话的 prompt 与通知 id（更新进度）。"
    override val parameters = schemaOf(
        "title" to strParam("通知标题"),
        "text" to strParam("通知内容"),
        "progress" to intParam("进度值 0..progress_max；提供后显示进度条"),
        "progress_max" to intParam("进度最大值，默认 100"),
        "indeterminate" to boolParam("不确定进度（转圈），默认 false"),
        "deeplink" to strParam("点击通知后送入对话的 prompt（生成 andagent:// 深链）"),
        "id" to intParam("通知 id；同一 id 重复调用可更新进度条"),
        required = listOf("title", "text"),
    )

    override suspend fun execute(args: JsonObject): String {
        val title = args.strArg("title") ?: return "ERROR: " + tr("缺少 title", "missing title")
        val text = args.strArg("text") ?: return "ERROR: " + tr("缺少 text", "missing text")
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = permissions.ensure(listOf(Manifest.permission.POST_NOTIFICATIONS))
            if (!granted) return "ERROR: " + tr("通知权限未授予，请在系统设置中开启", "notification permission not granted; enable it in system settings")
        }
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
                ?: return@runCatching "ERROR: " + tr("通知服务不可用", "notification service unavailable")
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, tr("AndAgent 通知", "AndAgent notifications"), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = tr("来自 AndAgent 的本地提醒", "Local reminders from AndAgent") },
            )
            val id = args.intArg("id") ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val progress = args.intArg("progress")
            val max = (args.intArg("progress_max") ?: 100).coerceAtLeast(1)
            val indeterminate = args.boolArg("indeterminate") == true
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_andagent)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .apply {
                    if (progress != null || indeterminate) {
                        setProgress(max, (progress ?: 0).coerceIn(0, max), indeterminate)
                    }
                    args.strArg("deeplink")?.let { prompt ->
                        val uri = Uri.parse("andagent://send?prompt=${Uri.encode(prompt)}&auto=1")
                        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                        setContentIntent(PendingIntent.getActivity(context, id, intent, flags))
                    }
                }
                .build()
            manager.notify(id, notification)
            tr("已发送通知「$title」（id=$id）", "Notification \"$title\" sent (id=$id)")
        }.getOrElse {
            "ERROR: " + tr("发送通知失败：${it.message ?: it.javaClass.simpleName}", "could not send notification: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    companion object {
        private const val CHANNEL_ID = "andagent"
    }
}

class AppListTool(private val context: Context) : Tool {
    override val name = "app_list"
    override val description =
        "列出已安装应用（包名、应用名、版本、是否系统应用），按安装时间倒序，最多 200 个。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        return runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(0)
                .sortedByDescending { it.firstInstallTime }
                .take(200)
            val apps = buildJsonArray {
                for (pkg in packages) {
                    val appInfo = pkg.applicationInfo ?: continue
                    add(buildJsonObject {
                        put("package", pkg.packageName)
                        put("name", appInfo.loadLabel(pm).toString())
                        put("version", pkg.versionName ?: "")
                        put(
                            "system",
                            (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                        put("installedAt", pkg.firstInstallTime)
                    })
                }
            }
            buildJsonObject {
                put("count", apps.size)
                put("apps", apps)
            }.toString()
        }.getOrElse { "ERROR: 读取应用列表失败：${it.message ?: it.javaClass.simpleName}" }
    }
}

package com.dc.andagent.tools

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.WallpaperManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.TelephonyManager
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** R12 connection/media + R13 device-management coverage (15 tools). */
private fun quoteArg(s: String): String = "'" + s.replace("'", "'\\''") + "'"

val SYSTEM_API_TOOLS = listOf(
    "telephony_info", "data_usage", "audio_state", "audio_set_volume",
    "media_library", "ringtone_set", "wallpaper_set", "vibrate",
    "torch", "screen_state", "brightness_set", "volume_dnd",
    "installed_app_details", "usage_stats", "screen_events", "biometric_check",
)

/** R118: wallpaper_set target 归一化（纯函数，供 JVM 单测直接覆盖，非法值回退 home）。 */
object WallpaperTarget {
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "lock" -> "lock"
        "both" -> "both"
        else -> "home"
    }
}

class SystemApiTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "telephony_info" -> "读取移动网络状态：运营商、SIM 状态、网络类型、漫游、国家码（不读取 IMEI/手机号）。"
        "data_usage" -> "读取设备累计流量（总/移动网络/本应用收发的字节数）。"
        "audio_state" -> "读取音频状态：铃声模式、各音量流当前值与最大值、是否正在播放音乐。"
        "audio_set_volume" -> "设置指定音量流的音量（music/ring/alarm/notification/system/voice）。"
        "media_library" -> "查询 MediaStore 媒体集合：audio（音乐）、video（影片）、files（全部文件）。"
        "ringtone_set" -> "列出系统铃声或设置来电铃声（需要 WRITE_SETTINGS）。"
        "wallpaper_set" -> "把本地图片或 content:// 图片设为壁纸，可指定 target=home(桌面)/lock(锁屏)/both(两者)，默认 home；部分 ROM 不支持锁屏壁纸，失败时如实标注 lock_failed。"
        "vibrate" -> "让设备振动，可指定时长或波形。"
        "torch" -> "开启或关闭手电筒。"
        "screen_state" -> "读取屏幕状态（亮屏/熄屏、亮度、休眠超时）。"
        "brightness_set" -> "设置屏幕亮度 0-255（需要 WRITE_SETTINGS）。"
        "volume_dnd" -> "设置勿扰模式：all/priority/alarms/silent（需要勿扰权限）。"
        "installed_app_details" -> "查询单个已安装应用的详细信息：版本、目标 SDK、权限、安装时间、APK 体积。"
        "usage_stats" -> "查询近 N 天应用前台使用时长（需要「使用情况访问」权限）。"
        "screen_events" -> "查询近 days 天应用前台切换统计：前台切换次数（按相邻前台包变化计，同包重恢复不计）、回到桌面会话估计(sessionEstimate)、最近前台活动历史（需系统「使用情况访问」权限）；sessionEstimate 为「切回桌面」转场代理，非真实解锁次数。Query app foreground-switch stats (needs Usage Access): switches counted by adjacent foreground-package changes (same-package re-resumes excluded), sessionEstimate is a return-to-home transition proxy, not a real unlock count; recent is the recent foreground activity history."
        "biometric_check" -> "检查设备生物识别能力及是否已录入（不弹出认证框）。"
        else -> "系统状态工具"
    }

    override val parameters: JsonObject = when (name) {
        "audio_set_volume" -> schemaOf(
            "stream" to strParam("music|ring|alarm|notification|system|voice，默认 music"),
            "level" to intParam("绝对音量值（0..max）"),
            "percent" to intParam("按最大音量百分比 0..100；与 level 二选一"),
        )
        "media_library" -> schemaOf(
            "kind" to strParam("audio|video|files，默认 audio"),
            "limit" to intParam("返回条数，默认 20，上限 100"),
        )
        "ringtone_set" -> schemaOf(
            "uri" to strParam("铃声 content:// URI；省略则列出可用铃声"),
            "type" to strParam("ringtone|notification|alarm，默认 ringtone"),
        )
        "wallpaper_set" -> schemaOf(
            "source" to strParam("本地文件路径或 content:// 图片 URI"),
            "target" to strParam("home|lock|both，默认 home"),
            required = listOf("source"),
        )
        "vibrate" -> schemaOf(
            "duration_ms" to intParam("振动时长，默认 200ms，上限 5000"),
            "pattern" to strParam("逗号分隔的波形毫秒序列，如 0,100,50,100；提供时忽略 duration_ms"),
            "repeat" to intParam("波形重复起始索引，-1 不重复，默认 -1"),
        )
        "torch" -> schemaOf("on" to boolParam("true 开启，false 关闭"), required = listOf("on"))
        "screen_state" -> schemaOf()
        "brightness_set" -> schemaOf("value" to intParam("亮度 0-255"), required = listOf("value"))
        "volume_dnd" -> schemaOf("mode" to strParam("all|priority|alarms|silent"), required = listOf("mode"))
        "installed_app_details" -> schemaOf("package" to strParam("应用包名"), required = listOf("package"))
        "usage_stats" -> schemaOf(
            "days" to intParam("查询最近 N 天，默认 1，上限 7"),
            "limit" to intParam("返回应用数，默认 20"),
        )
        "screen_events" -> schemaOf(
            "days" to intParam("查询最近 N 天，默认 1，范围 1-7"),
            "history_limit" to intParam("最近前台活动历史条数，默认 50，上限 200"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf(
        "audio_set_volume", "ringtone_set", "wallpaper_set", "torch",
        "brightness_set", "volume_dnd",
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "telephony_info" -> telephonyInfo()
                "data_usage" -> dataUsage()
                "audio_state" -> audioState()
                "audio_set_volume" -> audioSetVolume(args)
                "media_library" -> mediaLibrary(args)
                "ringtone_set" -> ringtoneSet(args)
                "wallpaper_set" -> wallpaperSet(args)
                "vibrate" -> vibrate(args)
                "torch" -> torch(args)
                "screen_state" -> screenState(args)
                "brightness_set" -> brightnessSet(args)
                "volume_dnd" -> volumeDnd(args)
                "installed_app_details" -> appDetails(args)
                "usage_stats" -> usageStats(args)
                "screen_events" -> screenEvents(args)
                "biometric_check" -> biometricCheck()
                else -> "ERROR: 未知系统工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* -------------------------- R12 connectivity ------------------------- */

    private suspend fun telephonyInfo(): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_PHONE_STATE)))
            return "ERROR: 未授予 READ_PHONE_STATE 权限"
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return "ERROR: 电话服务不可用"
        val simState = when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_READY -> "ready"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
            else -> "unknown"
        }
        return buildJsonObject {
            put("operator", tm.networkOperatorName ?: "")
            put("operatorCode", tm.networkOperator ?: "")
            put("countryIso", tm.networkCountryIso ?: "")
            put("simCountryIso", tm.simCountryIso ?: "")
            put("simState", simState)
            put("phoneType", when (tm.phoneType) { TelephonyManager.PHONE_TYPE_GSM -> "gsm"; TelephonyManager.PHONE_TYPE_CDMA -> "cdma"; else -> "none_or_sip" })
            put("networkType", if (Build.VERSION.SDK_INT >= 24) tm.dataNetworkType else tm.networkType)
            put("roaming", tm.isNetworkRoaming)
        }.toString()
    }

    private fun dataUsage(): String = buildJsonObject {
        put("totalRxBytes", TrafficStats.getTotalRxBytes())
        put("totalTxBytes", TrafficStats.getTotalTxBytes())
        put("mobileRxBytes", TrafficStats.getMobileRxBytes())
        put("mobileTxBytes", TrafficStats.getMobileTxBytes())
        put("mobileRxPackets", TrafficStats.getMobileRxPackets())
        put("mobileTxPackets", TrafficStats.getMobileTxPackets())
        put("uidRxBytes", TrafficStats.getUidRxBytes(Process.myUid()))
        put("uidTxBytes", TrafficStats.getUidTxBytes(Process.myUid()))
    }.toString()

    private fun audioStreams(): Map<String, Int> = mapOf(
        "music" to AudioManager.STREAM_MUSIC,
        "ring" to AudioManager.STREAM_RING,
        "alarm" to AudioManager.STREAM_ALARM,
        "notification" to AudioManager.STREAM_NOTIFICATION,
        "system" to AudioManager.STREAM_SYSTEM,
        "voice" to AudioManager.STREAM_VOICE_CALL,
    )

    private fun audioManager(): AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun audioState(): String {
        val am = audioManager()
        return buildJsonObject {
            put("ringerMode", when (am.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            put("mode", when (am.mode) {
                AudioManager.MODE_RINGTONE -> "ringtone"; AudioManager.MODE_IN_CALL -> "in_call"
                AudioManager.MODE_IN_COMMUNICATION -> "in_communication"; else -> "normal"
            })
            put("musicActive", am.isMusicActive)
            put("speakerphoneOn", am.isSpeakerphoneOn)
            putJsonArray("streams") {
                audioStreams().forEach { (label, stream) ->
                    add(buildJsonObject {
                        put("stream", label)
                        put("level", am.getStreamVolume(stream))
                        put("max", am.getStreamMaxVolume(stream))
                    })
                }
            }
        }.toString()
    }

    private fun audioSetVolume(args: JsonObject): String {
        val am = audioManager()
        val streamName = args.strArg("stream") ?: "music"
        val stream = audioStreams()[streamName] ?: return "ERROR: stream 仅支持 music|ring|alarm|notification|system|voice"
        val max = am.getStreamMaxVolume(stream)
        val level = args.intArg("level") ?: args.intArg("percent")?.let { max * it / 100 }
            ?: return "ERROR: 需要 level 或 percent"
        if (level !in 0..max) return "ERROR: 音量须在 0..$max"
        am.setStreamVolume(stream, level, 0)
        return "WRITTEN: $streamName 音量已设为 $level/$max（实际 ${am.getStreamVolume(stream)}）"
    }

    private suspend fun mediaLibrary(args: JsonObject): String {
        val kind = args.strArg("kind") ?: "audio"
        if (kind !in setOf("audio", "video", "files")) return "ERROR: kind 仅支持 audio|video|files"
        val permission = when {
            Build.VERSION.SDK_INT >= 33 -> when (kind) {
                "audio" -> Manifest.permission.READ_MEDIA_AUDIO
                else -> Manifest.permission.READ_MEDIA_VIDEO
            }
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (!permissions.ensure(listOf(permission))) return "ERROR: 媒体读取权限未授予"
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val collection = when (kind) {
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (kind != "files") projection += MediaStore.Audio.Media.DURATION
        val rows = buildJsonArray {
            context.contentResolver.query(
                collection, projection.toTypedArray(), null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < limit) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: ""
                    val mime = cursor.getString(2) ?: ""
                    val size = cursor.getLong(3)
                    val added = cursor.getLong(4)
                    val duration = if (kind != "files") cursor.getLong(5) else 0L
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("mime", mime)
                        put("size", size)
                        put("addedAt", added)
                        put("durationMs", duration)
                        put("uri", "$collection/$id")
                    })
                }
            }
        }
        return buildJsonObject { put("kind", kind); put("count", rows.size); putJsonArray("items") { rows.forEach { add(it) } } }.toString()
    }

    private suspend fun ringtoneSet(args: JsonObject): String {
        val type = when (args.strArg("type") ?: "ringtone") {
            "notification" -> RingtoneManager.TYPE_NOTIFICATION
            "alarm" -> RingtoneManager.TYPE_ALARM
            else -> RingtoneManager.TYPE_RINGTONE
        }
        val target = args.strArg("uri")
        if (target == null) {
            val manager = RingtoneManager(context).apply { setType(type) }
            val list = buildJsonArray {
                manager.cursor?.use { cursor ->
                    var i = 0
                    while (cursor.moveToNext() && i++ < 50) {
                        add(buildJsonObject {
                            put("title", cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                            put("uri", manager.getRingtoneUri(cursor.position).toString())
                        })
                    }
                }
            }
            return buildJsonObject { put("type", type); put("count", list.size); put("ringtones", list) }.toString()
        }
        val uri = Uri.parse(target)
        if (uri.scheme !in setOf("content", "file", "android.resource")) return "ERROR: 铃声 URI 无效"
        if (Settings.System.canWrite(context)) {
            RingtoneManager.setActualDefaultRingtoneUri(context, type, uri)
            return "WRITTEN: 铃声已设置为 $target"
        }
        return "ERROR: 需要 WRITE_SETTINGS 授权；请先在系统设置中授予「修改系统设置」"
    }

    private fun wallpaperSet(args: JsonObject): String {
        val source = args.strArg("source") ?: return "ERROR: 缺少 source"
        val target = WallpaperTarget.normalize(args.strArg("target"))
        val stream = when {
            source.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(source))
            source.startsWith("/") -> File(source).takeIf { it.isFile }?.inputStream()
            else -> null
        } ?: return "ERROR: 无法打开图片（需要本地绝对路径或 content:// URI）"
        val bitmap = stream.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
        } ?: return "ERROR: 图片解码失败"
        val wm = WallpaperManager.getInstance(context)
        var homeOk = false
        var lockFailed: String? = null
        try {
            if (target == "home" || target == "both") {
                wm.setBitmap(bitmap)
                homeOk = true
            }
            if (target == "lock" || target == "both") {
                try {
                    // API 24+；minSdk 26 无需门槛分支。第 3 参 allowBackup 为 boolean（不能传 null）。
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                } catch (e: IllegalArgumentException) {
                    lockFailed = e.message ?: "该 ROM 不支持锁屏壁纸"
                }
            }
        } finally {
            bitmap.recycle()
        }
        return when {
            lockFailed != null && homeOk ->
                "WRITTEN: 桌面壁纸已更新，但锁屏壁纸设置失败（$lockFailed）；部分 ROM 不支持锁屏壁纸（lock_failed）"
            lockFailed != null ->
                "ERROR: 锁屏壁纸设置失败（$lockFailed）；该 ROM 可能不支持锁屏壁纸（lock_failed）"
            else -> "WRITTEN: 壁纸已更新（target=$target，$source）"
        }
    }

    private fun vibrate(args: JsonObject): String {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return "ERROR: 设备不支持振动"
        if (!vibrator.hasVibrator()) return "ERROR: 设备不支持振动"
        val pattern = args.strArg("pattern")?.split(",")?.mapNotNull { it.trim().toLongOrNull() }
        val effect = if (!pattern.isNullOrEmpty()) {
            val repeat = args.intArg("repeat") ?: -1
            VibrationEffect.createWaveform(pattern.toLongArray(), repeat)
        } else {
            val duration = (args.intArg("duration_ms") ?: 200).coerceIn(1, 5000).toLong()
            VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
        return "WRITTEN: 已触发振动"
    }

    /* -------------------------- R13 device mgmt -------------------------- */

    private fun torch(args: JsonObject): String {
        val on = args.boolArg("on") ?: return "ERROR: 缺少 on"
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "ERROR: 相机服务不可用"
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                .getOrDefault(false)
        } ?: return "ERROR: 设备没有可用的闪光灯"
        manager.setTorchMode(cameraId, on)
        return "TOGGLED: 手电筒已${if (on) "开启" else "关闭"}"
    }

    private suspend fun screenState(args: JsonObject): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cr = context.contentResolver
        val brightness = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1)
        val timeout = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(-1)
        return buildJsonObject {
            put("interactive", pm.isInteractive)
            put("brightness", brightness)
            put("screenOffTimeoutMs", timeout)
        }.toString()
    }

    private suspend fun brightnessSet(args: JsonObject): String {
        val value = args.intArg("value") ?: return "ERROR: 缺少 value"
        if (value !in 0..255) return "ERROR: 亮度须在 0..255"
        if (Settings.System.canWrite(context)) {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            return "WRITTEN: 屏幕亮度已设为 $value"
        }
        return "ERROR: 需要 WRITE_SETTINGS 授权；请先在系统设置中授予「修改系统设置」"
    }

    private fun volumeDnd(args: JsonObject): String {
        val mode = args.strArg("mode") ?: return "ERROR: 缺少 mode"
        val nm = context.getSystemService(NotificationManager::class.java) ?: return "ERROR: 通知服务不可用"
        if (!nm.isNotificationPolicyAccessGranted)
            return "ERROR: 未授予「勿扰模式访问」权限，请先在系统设置中开启"
        val filter = when (mode) {
            "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "silent" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return "ERROR: mode 仅支持 all|priority|alarms|silent"
        }
        nm.setInterruptionFilter(filter)
        return "WRITTEN: 勿扰模式已切换为 $mode"
    }

    private fun appDetails(args: JsonObject): String {
        val pkg = args.strArg("package") ?: return "ERROR: 缺少 package"
        @Suppress("DEPRECATION")
        val info = runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }
            .getOrNull() ?: return "ERROR: 未找到应用 $pkg"
        val apkSize = runCatching { File(info.applicationInfo.sourceDir).length() }.getOrDefault(0L)
        return buildJsonObject {
            put("package", info.packageName)
            put("versionName", info.versionName ?: "")
            put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            put("targetSdk", info.applicationInfo.targetSdkVersion)
            put("minSdk", if (Build.VERSION.SDK_INT >= 24) info.applicationInfo.minSdkVersion else 0)
            put("firstInstallTime", info.firstInstallTime)
            put("lastUpdateTime", info.lastUpdateTime)
            put("apkBytes", apkSize)
            putJsonArray("permissions") { info.requestedPermissions?.forEach { add(it) } }
        }.toString()
    }

    private fun usageStats(args: JsonObject): String {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val allowed = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        if (!allowed) return "ERROR: 需要「使用情况访问」权限，请先在系统设置中为 AndAgent 开启"
        val days = (args.intArg("days") ?: 1).coerceIn(1, 7)
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val end = System.currentTimeMillis()
        val begin = end - days * 86_400_000L
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            .orEmpty()
            .groupBy { it.packageName }
            .map { (pkg, entries) -> pkg to entries.sumOf { it.totalTimeInForeground } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
        return buildJsonObject {
            put("days", days)
            putJsonArray("apps") {
                stats.forEach { (pkg, millis) -> add(buildJsonObject { put("package", pkg); put("foregroundMs", millis) }) }
            }
        }.toString()
    }

    // R117①：UsageEvents 前台切换事件统计。复用 usage_stats 同款 AppOps 检查；
    // sessionEstimate 是「回到桌面」启发式代理，非真实解锁计数（见 UsageEventStats 的 note）。
    private fun screenEvents(args: JsonObject): String {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val allowed = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        if (!allowed) return "ERROR: 需要「使用情况访问」权限，请先在系统设置中为 AndAgent 开启"
        val days = (args.intArg("days") ?: 1).coerceIn(1, 7)
        val historyLimit = (args.intArg("history_limit") ?: 50).coerceIn(1, 200)
        val now = System.currentTimeMillis()
        val begin = now - days * 86_400_000L
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // 遍历中即时过滤：仅 ACTIVITY_RESUMED 事件以 (pkg, ts) 入列，paused/其它类型读完即弃，
        // 避免 7 天高活跃设备把全类型事件全量装入 List 导致内存/耗时风险。
        val events = mutableListOf<Pair<String, Long>>()
        val usageEvents = manager.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEventStats.ACTIVITY_RESUMED) {
                events.add((event.packageName ?: "") to event.timeStamp)
            }
        }
        // CATEGORY_HOME 解析桌面包名；无 launcher 时 resolveActivity 返回 null → sessionEstimate 置 null 不虚报。
        val launcherPkg = runCatching {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .resolveActivity(context.packageManager)?.packageName
        }.getOrNull()
        return UsageEventStats.aggregate(events, launcherPkg, now, days, historyLimit).toString()
    }

    private fun biometricCheck(): String {
        if (Build.VERSION.SDK_INT < 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return buildJsonObject {
                put("apiLevel", Build.VERSION.SDK_INT)
                put("available", false)
                put("reason", "生物识别能力检测需要 Android 10+")
                put("secureLockScreen", pm.isInteractive)
            }.toString()
        }
        val manager = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            ?: return "ERROR: 生物识别服务不可用"
        val code = manager.canAuthenticate()
        val state = when (code) {
            android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> "enrolled"
            android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
            android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
            android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
            else -> "unknown"
        }
        return buildJsonObject {
            put("available", code == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS)
            put("state", state)
            put("code", code)
        }.toString()
    }
}

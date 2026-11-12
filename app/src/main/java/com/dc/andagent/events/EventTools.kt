package com.dc.andagent.events

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dc.andagent.R
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.boolArg
import com.dc.andagent.tools.boolParam
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.strParam
import com.dc.andagent.tools.KvStore
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.ArrayDeque

val EVENT_TOOLS = listOf("media_sessions", "media_events_on", "media_events_off", "media_events_read", "media_send_key", "notif_events_on", "notif_events_off", "notif_events_read", "notif_permission", "audio_events_on", "audio_events_off", "audio_events_read", "clip_events_on", "clip_events_off", "clip_events_read", "dnd_read", "dnd_set", "dnd_permission", "sys_triggers_on", "sys_triggers_off", "sys_triggers_read")

/** R117②: 剪贴板只认 app 前台，监听挂在 MainActivity，不属于前台服务管理的通道。 */
internal const val CLIP_CHANNEL = "clip"
internal const val SYS_CHANNEL = "sys"
internal const val CLIP_EVENTS_NAMESPACE = "clip_events"
internal const val SYS_EVENTS_NAMESPACE = "sys_events"

/** Codex 必修1：剪贴板预览敏感内容启发式（密码/令牌/验证码等关键词命中即不落 preview）。 */
private val SENSITIVE_CLIP_HINT = Regex(
    "(?i)(password|passwd|pwd|secret|token|api[ _-]?key|access[ _-]?key|authorization|bearer|credential|otp|totp|verification[ _-]?code|verify[ _-]?code|one[ _-]?time[ _-]?code|confirm[ _-]?code|security[ _-]?code|验证码|校验码|授权码|动态码|口令|密钥|密码)",
)

private const val CLIP_READ_NOTE = "preview 仅为前 120 字符预览；内容疑似密码/令牌/验证码时不落 preview（只记长度）。剪贴板全文从不落盘。仅 app 前台时生效，Android 10+ 系统限制后台无法接收剪贴板变更。"
private const val SYS_READ_NOTE = "ssidUnknown=true 表示设备无定位权限、未能读取 SSID。指定 wifi_ssid 时只有确认连上目标 SSID 才记 wifi_connected（无定位权限时目标 SSID 触发器无法证实命中）。事件只写入 KV，不自动执行任务。"
private const val DND_GUIDE = "请先在系统「设置 → 通知 → 勿扰访问权限」中允许 AndAgent，或调用 dnd_permission 直接跳转设置页；未授权时 dnd_set 返回 ERROR，不会静默切换。"

/** Channels owned by [EventMonitorService]; "geofence" was added by R76, "sys" by R117②. */
internal val EVENT_CHANNELS = listOf("media", "notif", "audio", GEOFENCE_CHANNEL, SYS_CHANNEL)

internal object EventPure {
    const val CLIP_PREVIEW_MAX = 120

    /** 剪贴板事件只留「长度 + 前 120 字符预览」，绝不落全文（可能含密码）。 */
    fun clipPreview(text: String?): Pair<Int, String> {
        val value = text.orEmpty()
        return value.length to value.take(CLIP_PREVIEW_MAX)
    }

    /** Codex 必修1：疑似凭据（密码/令牌/验证码等）即使 ≤120 字符也不落 preview，防短密文进普通 KV。 */
    fun clipLooksSensitive(text: String?): Boolean =
        SENSITIVE_CLIP_HINT.containsMatchIn(text.orEmpty())

    /** sys_triggers_on 参数校验：至少一个有效触发器，否则返回 null（调用方报 ERROR）。 */
    fun sysTriggerConfig(wifiSsid: String?, power: Boolean?, wifiAny: Boolean? = null): SysTriggerConfig? {
        // 二轮必修2补强：空串/全空白 SSID 归一化为 null——避免空串入库后被当成「指定 SSID 过滤」永不命中。
        val trimmed = wifiSsid?.trim()?.takeIf { it.isNotEmpty() }
        val namedWifi = trimmed != null
        // Codex 必修2：wifi_any=true 显式开启「任意 WiFi 连接」触发器；不再把缺省 SSID 悄悄当任意。
        // 二轮必修2：wifi_any=true 与 wifi_ssid 互斥——两者同传语义冲突（名义任意、实际按 SSID 过滤），直接拒绝。
        if (namedWifi && wifiAny == true) return null
        val wifi = namedWifi || wifiAny == true
        val pwr = power == true
        if (!wifi && !pwr) return null
        return SysTriggerConfig(wifi, trimmed, pwr)
    }

    /**
     * 二轮必修3补强：wifi_connected 事件过滤纯函数（工具层用同一判定，测试直接断言）。
     * 指定 SSID 过滤时读不到 SSID（unknown）或读到其他 SSID 都不落事件——未证实命中绝不落盘；
     * 任意 WiFi 模式（filter 为 null）时 unknown 也照发。
     */
    fun shouldEmitWifiConnected(filterSsid: String?, ssid: String?): Boolean = when {
        filterSsid == null -> true
        ssid == null -> false
        else -> ssid == filterSsid
    }

    /** WifiInfo.getSSID() 常态带引号；无定位权限时返回 "<unknown ssid>" 等占位，统一判为未知。 */
    fun normalizeSsid(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"")?.trim()
        if (value.isNullOrEmpty()) return null
        if (value.equals("<unknown ssid>", ignoreCase = true) || value.equals("<unknown>", ignoreCase = true)) return null
        return value
    }

    fun dndModeName(filter: Int): String = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
        else -> "UNKNOWN"
    }
}

internal data class SysTriggerConfig(val wifi: Boolean, val ssid: String?, val power: Boolean)

/** 供 MainActivity 的剪贴板 listener 写入事件体；序列化只发生在 EventTools 内。 */
internal object ClipEvents {
    fun log(context: Context, text: String?) {
        val (length, preview) = EventPure.clipPreview(text)
        // Codex 必修1：疑似凭据（密码/令牌/验证码）不落 preview，只记长度——120 字符内的短密文也不进普通 KV。
        val safePreview = if (EventPure.clipLooksSensitive(text)) null else preview
        EventStore.put(context, CLIP_EVENTS_NAMESPACE, EventStore.event("clip", buildJsonObject {
            put("length", length)
            safePreview?.let { put("preview", it) }
        }))
    }
}

/**
 * MainActivity 处于 resumed 时设置的同步钩子：clip_events_on/off 由后台线程池执行，
 * 借此即时挂/摘 listener，而不必等下一次 onResume。onPause 会置空，杜绝后台注册。
 */
internal object ClipboardForeground {
    @Volatile var sync: (() -> Unit)? = null
}

internal object EventHistory {
    private const val MAX = 100
    fun accept(old: JsonArray?, event: JsonObject): JsonArray {
        val items = (old?.takeLast(MAX - 1) ?: emptyList()).toMutableList()
        items.add(event)
        while (items.size > 1 && JsonArray(items).toString().toByteArray(Charsets.UTF_8).size > 60_000) items.removeAt(0)
        return JsonArray(items)
    }
    fun read(items: JsonArray?, since: Long, pkg: String? = null): JsonArray = buildJsonArray {
        items.orEmpty().filter { item ->
            val obj = item as? JsonObject ?: return@filter false
            (obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0) >= since &&
                (pkg == null || obj["package"]?.jsonPrimitive?.content == pkg)
        }.forEach { add(it) }
    }
    fun excluded(pkg: String, own: String, configured: Set<String>) = pkg == own || pkg == "com.android.systemui" || pkg in configured
}

internal object EventStore {
    fun put(context: Context, namespace: String, event: JsonObject) {
        runCatching {
            val kv = KvStore(context)
            val history = EventHistory.accept(kv.get(namespace, "history") as? JsonArray, event)
            kv.setAll(namespace, mapOf("latest" to event, "history" to history))
        }
    }
    fun read(context: Context, namespace: String, minutes: Int, pkg: String? = null, note: String? = null): String {
        val history = KvStore(context).get(namespace, "history") as? JsonArray
        return buildJsonObject {
            put("events", EventHistory.read(history, System.currentTimeMillis() - minutes * 60_000L, pkg))
            note?.let { put("note", it) }
        }.toString()
    }
    fun event(type: String, extra: JsonObject = buildJsonObject {}): JsonObject = buildJsonObject {
        put("timestamp", System.currentTimeMillis()); put("event", type)
        extra.forEach { (key, value) -> put(key, value) }
    }
}

internal object EventFlags {
    private const val PREF = "event_listeners"
    fun enabled(context: Context, channel: String) = context.getSharedPreferences(PREF, 0).getBoolean(channel, false)
    fun set(context: Context, channel: String, value: Boolean) = context.getSharedPreferences(PREF, 0).edit().putBoolean(channel, value).apply()
    fun excludes(context: Context): Set<String> = context.getSharedPreferences(PREF, 0).getStringSet("exclude_apps", emptySet()) ?: emptySet()
    fun setExcludes(context: Context, excludes: Set<String>) = context.getSharedPreferences(PREF, 0).edit().putStringSet("exclude_apps", excludes).apply()
    fun granted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    fun sysWifi(context: Context) = context.getSharedPreferences(PREF, 0).getBoolean("sys_wifi", false)
    fun sysPower(context: Context) = context.getSharedPreferences(PREF, 0).getBoolean("sys_power", false)
    fun sysSsid(context: Context): String? = context.getSharedPreferences(PREF, 0).getString("sys_ssid", null)
    fun setSysTriggers(context: Context, wifi: Boolean, ssid: String?, power: Boolean) =
        context.getSharedPreferences(PREF, 0).edit().putBoolean("sys_wifi", wifi).putString("sys_ssid", ssid).putBoolean("sys_power", power).apply()
}

class EventNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { EventMonitorService.instance?.refreshMedia() }
    override fun onListenerDisconnected() { EventMonitorService.instance?.refreshMedia() }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!EventFlags.enabled(this, "notif") || EventHistory.excluded(sbn.packageName, packageName, EventFlags.excludes(this))) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.take(200) ?: ""
        val text = extras.getCharSequence("android.text")?.toString()?.take(500) ?: ""
        // R117①：通知智能分类，event JSON 追加 category（始终有）、code（仅提取到时写键）；既有字段不动。
        val classification = NotifClassifier.classify(title, text, sbn.packageName)
        EventStore.put(this, "notif_events", EventStore.event("posted", buildJsonObject {
            put("package", sbn.packageName)
            put("title", title)
            put("text", text)
            put("category", classification.category)
            classification.code?.let { put("code", it) }
        }))
    }
}

class EventMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private var mediaListening = false
    private var audioListening = false
    private var geofenceEngine: GeofenceEngine? = null
    private var connectivityManager: ConnectivityManager? = null
    private var wifiManager: WifiManager? = null
    private var lastWifiEventKey: String? = null
    private val knownAudioIds = mutableSetOf<Int>()
    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions -> syncSessions(sessions ?: emptyList()) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { logDevices("added", devices.filter { knownAudioIds.add(it.id) }) }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { logDevices("removed", devices.filter { knownAudioIds.remove(it.id) }) }
    }
    private val sysWifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { onWifiConnected() }
        override fun onLost(network: Network) { lastWifiEventKey = null }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EVENT_CHANNELS.forEach { EventFlags.set(this, it, false) }
            EventFlags.set(this, CLIP_CHANNEL, false)
            stopSelf(); return START_NOT_STICKY
        }
        if (EVENT_CHANNELS.none { EventFlags.enabled(this, it) }) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("event_monitor", "系统事件监听", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(6502, NotificationCompat.Builder(this, "event_monitor")
            .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle("系统事件监听中")
            .setContentText("媒体、通知、音频设备、地理围栏与系统触发事件").setOngoing(true).addAction(0, "停止全部", stop).build())
        refreshMedia(); refreshAudio(); refreshGeofence(); refreshTriggers()
        return START_STICKY
    }
    fun refreshMedia() {
        if (!EventFlags.enabled(this, "media") || !EventFlags.granted(this)) { clearMedia(); return }
        try {
            val manager = getSystemService(MediaSessionManager::class.java)
            mediaManager = manager
            if (!mediaListening) {
                manager.addOnActiveSessionsChangedListener(activeListener, ComponentName(this, EventNotificationListener::class.java), handler)
                mediaListening = true
            }
            syncSessions(manager.getActiveSessions(ComponentName(this, EventNotificationListener::class.java)))
        } catch (_: SecurityException) { clearMedia() }
    }
    private fun syncSessions(sessions: List<MediaController>) {
        val incoming = sessions.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it !in incoming }.forEach { token ->
            callbacks.remove(token)?.let { (controller, callback) ->
                controller.unregisterCallback(callback)
                EventStore.put(this, "media_events", mediaEvent("removed", controller))
            }
        }
        sessions.filter { it.sessionToken !in callbacks }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val type = when (state?.state) { PlaybackState.STATE_PLAYING -> "play"; PlaybackState.STATE_PAUSED -> "pause"; else -> "playback" }
                    EventStore.put(this@EventMonitorService, "media_events", mediaEvent(type, controller))
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) { EventStore.put(this@EventMonitorService, "media_events", mediaEvent("track_changed", controller)) }
                override fun onSessionDestroyed() { refreshMedia() }
            }
            callbacks[controller.sessionToken] = controller to callback
            controller.registerCallback(callback, handler)
            EventStore.put(this, "media_events", mediaEvent("added", controller))
        }
    }
    private fun clearMedia() {
        callbacks.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }; callbacks.clear()
        if (mediaListening) mediaManager?.removeOnActiveSessionsChangedListener(activeListener)
        mediaListening = false
    }
    private fun mediaEvent(type: String, controller: MediaController) = EventStore.event(type, buildJsonObject {
        put("package", controller.packageName); put("state", controller.playbackState?.state ?: PlaybackState.STATE_NONE)
        put("positionMs", controller.playbackState?.position ?: 0)
        put("title", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        put("artist", controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
        put("album", controller.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
    })
    private fun refreshAudio() {
        val enabled = EventFlags.enabled(this, "audio")
        if (enabled && !audioListening) {
            audioManager = getSystemService(AudioManager::class.java)
            knownAudioIds.clear()
            audioManager?.getDevices(AudioManager.GET_DEVICES_ALL)?.forEach { knownAudioIds.add(it.id) }
            audioManager?.registerAudioDeviceCallback(deviceCallback, handler)
            audioListening = true
        } else if (!enabled && audioListening) {
            audioManager?.unregisterAudioDeviceCallback(deviceCallback); audioListening = false
        }
    }
    private fun logDevices(type: String, devices: List<AudioDeviceInfo>) {
        devices.forEach { device -> EventStore.put(this, "audio_events", EventStore.event(type, buildJsonObject {
            put("deviceId", device.id); put("deviceType", device.type)
            put("product", device.productName?.toString() ?: "")
            put("direction", if (device.isSink) "output" else "input")
        })) }
    }
    private fun refreshGeofence() {
        if (!EventFlags.enabled(this, GEOFENCE_CHANNEL)) {
            geofenceEngine?.stop(); geofenceEngine = null; return
        }
        // Every start command re-reads the persisted fences so replacements and
        // individual disarm operations take effect without a new service.
        geofenceEngine?.stop()
        val engine = GeofenceEngine(this)
        val started = engine.start()
        geofenceEngine = engine
        // No fences (KV corrupt or all disarmed): keep lastError for diagnostics
        // but drop the flag so the service does not idle behind a notification.
        // Other channels keep the service alive when they are still enabled.
        if (!started && engine.fences.isEmpty()) {
            EventFlags.set(this, GEOFENCE_CHANNEL, false)
            if (EVENT_CHANNELS.none { EventFlags.enabled(this, it) }) stopSelf()
        }
    }
    fun geofenceListening(): Boolean = geofenceEngine?.listening == true
    fun geofenceError(): String? = geofenceEngine?.lastError

    /** 二轮必修5：sys 触发器注册诊断——refreshTriggers 结束时统一发布（WiFi/充电分别收集，注册成功清空）。 */
    @Volatile var lastSysError: String? = null
    /** 二轮必修5：refreshTriggers 完成代号——sys_triggers_on 等待本次（而非上次）刷新完成，固定 sleep 不可靠。 */
    @Volatile var sysRefreshSeq: Int = 0
    @Volatile private var wifiRegistered = false
    @Volatile private var powerReceiver: BroadcastReceiver? = null
    fun sysWifiListening(): Boolean = wifiRegistered
    fun sysPowerListening(): Boolean = powerReceiver != null

    /** R117②: 每次 startCommand 重读 sys 配置，与 refreshGeofence 同模式；只落事件，不联动任务。 */
    private fun refreshTriggers() {
        val enabled = EventFlags.enabled(this, SYS_CHANNEL)
        val wantWifi = enabled && EventFlags.sysWifi(this)
        val wantPower = enabled && EventFlags.sysPower(this)
        if (connectivityManager == null) connectivityManager = getSystemService(ConnectivityManager::class.java)
        var wifiErr: String? = null
        var powerErr: String? = null
        if (wantWifi && !wifiRegistered) {
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val cm = connectivityManager
            if (cm == null) {
                wifiErr = "ConnectivityManager 不可用"
            } else runCatching {
                cm.registerNetworkCallback(request, sysWifiCallback, handler)
                wifiRegistered = true
                lastWifiEventKey = null
            }.onFailure { wifiErr = it.message ?: it.javaClass.simpleName }
        } else if (!wantWifi && wifiRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(sysWifiCallback) }
            wifiRegistered = false; lastWifiEventKey = null
        }
        if (wantPower && powerReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_POWER_CONNECTED -> EventStore.put(this@EventMonitorService, SYS_EVENTS_NAMESPACE, EventStore.event("power_connected"))
                        Intent.ACTION_POWER_DISCONNECTED -> EventStore.put(this@EventMonitorService, SYS_EVENTS_NAMESPACE, EventStore.event("power_disconnected"))
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            runCatching {
                ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                powerReceiver = receiver
            }.onFailure { powerErr = it.message ?: it.javaClass.simpleName }
        } else if (!wantPower && powerReceiver != null) {
            runCatching { unregisterReceiver(powerReceiver) }
            powerReceiver = null
        }
        // 二轮必修5：WiFi/充电分别收集、结束时统一发布——修复「WiFi 失败写 lastSysError 后充电成功立即清空」。
        lastSysError = listOfNotNull(
            wifiErr?.let { "WiFi 触发器注册失败：$it" },
            powerErr?.let { "充电触发器注册失败：$it" },
        ).joinToString("；").ifEmpty { null }
        sysRefreshSeq++
    }

    private fun onWifiConnected() {
        @Suppress("DEPRECATION")
        val rawSsid = wifiManager?.connectionInfo?.ssid
        val ssid = EventPure.normalizeSsid(rawSsid)
        val filter = EventFlags.sysSsid(this)
        // 二轮必修3补强：过滤判定收敛到 EventPure.shouldEmitWifiConnected（纯函数可测）。
        // 指定 SSID 过滤时读不到 SSID（unknown）绝不记 wifi_connected——未证实命中不落盘。
        if (!EventPure.shouldEmitWifiConnected(filter, ssid)) return
        val key = ssid ?: "unknown"
        if (key == lastWifiEventKey) return
        lastWifiEventKey = key
        // 已知 SSID 但与目标不符：不落事件，绝不伪造命中。
        if (ssid != null && filter != null && filter != ssid) return
        EventStore.put(this, SYS_EVENTS_NAMESPACE, EventStore.event("wifi_connected", buildJsonObject {
            if (ssid != null) put("ssid", ssid) else put("ssidUnknown", true)
        }))
    }

    override fun onDestroy() {
        clearMedia(); if (audioListening) audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        geofenceEngine?.stop(); geofenceEngine = null
        if (wifiRegistered) runCatching { connectivityManager?.unregisterNetworkCallback(sysWifiCallback) }
        wifiRegistered = false; lastWifiEventKey = null
        powerReceiver?.let { runCatching { unregisterReceiver(it) } }; powerReceiver = null
        instance = null; super.onDestroy()
    }
    companion object { const val ACTION_STOP = "com.dc.andagent.events.STOP"; @Volatile var instance: EventMonitorService? = null }
}

class EventTool(private val context: Context, override val name: String) : Tool {
    /** 二轮必修5：sys_triggers_on 失败回滚——清 flag/配置并触发服务重刷新（真正撤销已注册监听），服务不存在时才 stopService。 */
    private fun rollbackSysTriggers(context: Context, reason: String) {
        EventFlags.set(context, SYS_CHANNEL, false)
        EventFlags.setSysTriggers(context, false, null, false)
        val svc = EventMonitorService.instance
        if (svc != null) {
            if (EVENT_CHANNELS.any { EventFlags.enabled(context, it) }) {
                ContextCompat.startForegroundService(context, Intent(context, EventMonitorService::class.java))
            } else {
                context.stopService(Intent(context, EventMonitorService::class.java))
            }
        }
    }

    override val description: String = when (name) {
        "media_sessions" -> "列出活动媒体会话的播放状态和曲目；需要通知使用权限。"
        "media_events_on" -> "监听活动媒体会话的播放、暂停和曲目变化，写入 media_events KV；需要通知使用权限。"
        "media_events_off" -> "停止媒体事件监听。"
        "media_events_read" -> "读取最近媒体事件。"
        "media_send_key" -> "向当前媒体键接收方发送 play、pause、toggle、next 或 prev。"
        "notif_events_on" -> "监听通知并在本地写入 notif_events KV；需要通知使用权限。"
        "notif_events_off" -> "停止记录通知事件。"
        "notif_events_read" -> "读取最近通知事件，可按包名过滤。"
        "notif_permission" -> "打开系统通知使用权限设置。"
        "audio_events_on" -> "监听音频设备添加和移除，写入 audio_events KV。"
        "audio_events_off" -> "停止音频设备监听。"
        "audio_events_read" -> "读取最近音频设备事件。"
        "clip_events_on" -> "开启剪贴板事件监听，写入 clip_events KV；仅 app 前台时生效（Android 10+ 系统限制，后台无法接收剪贴板变更）。不记录全文，只保存文本长度与前 120 字符预览；内容疑似密码/令牌/验证码时不落预览（只记长度）。"
        "clip_events_off" -> "停止剪贴板事件监听。"
        "clip_events_read" -> "读取最近剪贴板事件；preview 仅为前 120 字符预览且疑似密码/令牌/验证码时不落预览（只记长度），剪贴板全文从不落盘。仅 app 前台时生效，Android 10+ 系统限制。"
        "dnd_read" -> "读取勿扰/铃声模式：currentMode（ALL/PRIORITY/NONE/ALARMS/UNKNOWN）、ringerMode（normal/silent/vibrate）、notificationPolicyAccessGranted；未授权时给出设置指引。"
        "dnd_set" -> "设置勿扰模式 mode=off/priority/none/alarms，需勿扰访问权限；未授权返回 ERROR（可调用 dnd_permission 跳转设置页）。"
        "dnd_permission" -> "打开系统「勿扰访问权限」设置页，供用户授权后使用 dnd_set。"
        "sys_triggers_on" -> "开启系统事件触发器并写入 sys_events KV：wifi_any=true 监听任意 WiFi 连接、wifi_ssid 指定目标 SSID（需有定位权限才能读取 SSID 确认命中，无定位权限时不会记命中；与 wifi_any 互斥）、power=true 监听充电插拔；至少给一个。只落事件，不自动执行任务。"
        "sys_triggers_off" -> "关闭全部系统事件触发器（WiFi 与充电）。"
        "sys_triggers_read" -> "读取最近系统触发事件（wifi_connected/power_connected/power_disconnected）；ssidUnknown=true 表示无定位权限，未能读取 SSID（指定 SSID 的触发器此期间不记命中）。"
        else -> "读取最近音频设备事件。"
    }
    override val destructive: Boolean = name == "dnd_set"
    override val parameters = when (name) {
        "media_events_read", "audio_events_read", "clip_events_read", "sys_triggers_read" -> schemaOf("duration_min" to intParam("最近分钟数，默认 10，范围 1-1440"))
        "notif_events_read" -> schemaOf("duration_min" to intParam("最近分钟数，默认 10，范围 1-1440"), "package" to strParam("可选包名"))
        "notif_events_on" -> schemaOf("exclude_apps" to buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "额外跳过的应用包名") })
        "media_send_key" -> schemaOf("keycode" to strParam("play、pause、toggle、next、prev"), required = listOf("keycode"))
        "dnd_set" -> schemaOf("mode" to strParam("off|priority|none|alarms"), required = listOf("mode"))
        "sys_triggers_on" -> schemaOf(
            "wifi_any" to boolParam("监听任意 WiFi 连接事件（不指定 SSID）"),
            "wifi_ssid" to strParam("可选：只在连上该 SSID 时触发；需有定位权限才能读取 SSID 确认命中（无定位权限时不会记命中）。与 wifi_any 互斥"),
            "power" to boolParam("是否监听充电插拔事件（ACTION_POWER_CONNECTED/DISCONNECTED）"),
        )
        else -> schemaOf()
    }
    override suspend fun execute(args: JsonObject): String { return try {
        when (name) {
            "notif_permission" -> {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "已打开通知使用权限设置，请授权 AndAgent 后重新开启监听。"
            }
            "media_sessions" -> {
                if (!EventFlags.granted(context)) return "ERROR: 需要先用 notif_permission 授予通知使用权限"
                val manager = context.getSystemService(MediaSessionManager::class.java)
                val sessions = manager.getActiveSessions(ComponentName(context, EventNotificationListener::class.java))
                buildJsonObject { put("sessions", buildJsonArray { sessions.forEach { c -> add(buildJsonObject {
                    put("package", c.packageName); put("state", c.playbackState?.state ?: PlaybackState.STATE_NONE)
                    put("positionMs", c.playbackState?.position ?: 0)
                    put("title", c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                    put("artist", c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
                    put("album", c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                    put("queue", buildJsonArray { c.queue?.take(20)?.forEach { q -> add(buildJsonObject { put("id", q.queueId); put("title", q.description.title?.toString() ?: "") }) } })
                }) } }) }.toString()
            }
            "media_send_key" -> {
                val code = when (args.strArg("keycode")?.lowercase()) {
                    "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    "toggle", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> return "ERROR: keycode 仅支持 play、pause、toggle、next、prev"
                }
                val audio = context.getSystemService(AudioManager::class.java)
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                "已发送媒体按键；目标播放器是否响应需在设备上确认。"
            }
            "media_events_read", "notif_events_read", "audio_events_read" -> EventStore.read(context, name.removeSuffix("_read"), (args.intArg("duration_min") ?: 10).coerceIn(1, 1440), if (name == "notif_events_read") args.strArg("package") else null)
            "media_events_on", "notif_events_on", "audio_events_on" -> {
                val channel = name.substringBefore('_')
                if (channel != "audio" && !EventFlags.granted(context)) return "ERROR: 需要先用 notif_permission 授予通知使用权限"
                if (channel == "notif") {
                    val values = args["exclude_apps"]
                    if (values != null && (values !is JsonArray || values.any { it !is kotlinx.serialization.json.JsonPrimitive || !it.isString })) return "ERROR: exclude_apps 必须是包名字符串数组"
                    if (values is JsonArray) EventFlags.setExcludes(context, values.map { it.jsonPrimitive.content }.toSet())
                }
                EventFlags.set(context, channel, true)
                ContextCompat.startForegroundService(context, Intent(context, EventMonitorService::class.java))
                "$channel 事件监听已开启；KV 的 ${channel}_events/latest 和 history 可读取。"
            }
            "media_events_off", "notif_events_off", "audio_events_off" -> {
                val channel = name.substringBefore('_'); EventFlags.set(context, channel, false)
                if (EVENT_CHANNELS.any { EventFlags.enabled(context, it) }) EventMonitorService.instance?.let { ContextCompat.startForegroundService(context, Intent(context, EventMonitorService::class.java)) }
                else context.stopService(Intent(context, EventMonitorService::class.java))
                "$channel 事件监听已停止"
            }
            "clip_events_read", "sys_triggers_read" -> {
                val clip = name == "clip_events_read"
                EventStore.read(
                    context,
                    if (clip) CLIP_EVENTS_NAMESPACE else SYS_EVENTS_NAMESPACE,
                    (args.intArg("duration_min") ?: 10).coerceIn(1, 1440),
                    note = if (clip) CLIP_READ_NOTE else SYS_READ_NOTE,
                )
            }
            "clip_events_on", "clip_events_off" -> {
                val on = name == "clip_events_on"
                EventFlags.set(context, CLIP_CHANNEL, on)
                Handler(Looper.getMainLooper()).post { ClipboardForeground.sync?.invoke() }
                if (on) "剪贴板事件监听已开启；仅 app 前台时生效（Android 10+ 系统限制后台无法接收），事件写入 clip_events KV 的 latest/history，只含 length 与 ≤120 字符 preview，不落全文。"
                else "剪贴板事件监听已停止"
            }
            "dnd_permission" -> {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "已打开「勿扰访问权限」设置页，请授权 AndAgent 后使用 dnd_set。"
            }
            "dnd_read" -> {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return "ERROR: 通知服务不可用"
                val am = context.getSystemService(AudioManager::class.java)
                buildJsonObject {
                    put("currentMode", EventPure.dndModeName(nm.currentInterruptionFilter))
                    put("ringerMode", when (am?.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "silent"
                        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                        else -> "normal"
                    })
                    put("notificationPolicyAccessGranted", nm.isNotificationPolicyAccessGranted)
                    put("guide", DND_GUIDE)
                }.toString()
            }
            "dnd_set" -> {
                val mode = args.strArg("mode")?.lowercase() ?: return "ERROR: 缺少 mode"
                val nm = context.getSystemService(NotificationManager::class.java) ?: return "ERROR: 通知服务不可用"
                if (!nm.isNotificationPolicyAccessGranted) return "ERROR: 未授予「勿扰访问权限」，不会切换。$DND_GUIDE"
                val filter = when (mode) {
                    "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                    "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    else -> return "ERROR: mode 仅支持 off|priority|none|alarms"
                }
                // 可证伪条款：无 policy access 时可能抛 SecurityException，也可能静默失败——两条分支都要回 ERROR，绝不报成功。
                try {
                    nm.setInterruptionFilter(filter)
                } catch (e: Exception) {
                    return "ERROR: setInterruptionFilter 失败（${e.message ?: e.javaClass.simpleName}）；$DND_GUIDE"
                }
                // Codex 必修4：回读校验唯一成功通道——严格等于目标值才算成功；抛错/null/未知值一律 ERROR，绝不虚报。
                val applied = try { nm.currentInterruptionFilter } catch (e: Exception) { null }
                val known = setOf(
                    NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    NotificationManager.INTERRUPTION_FILTER_NONE, NotificationManager.INTERRUPTION_FILTER_ALARMS,
                )
                if (applied == null || applied !in known)
                    return "ERROR: 切换后无法回读勿扰状态（可能个别 ROM 异步生效，请用 dnd_read 复核），不报成功；$DND_GUIDE"
                if (applied != filter)
                    return "ERROR: 系统未接受勿扰模式切换（目标 $mode，当前仍为 ${EventPure.dndModeName(applied)}）；$DND_GUIDE"
                "WRITTEN: 勿扰模式已切换为 $mode（系统 UI 可随时手动改回）。"
            }
            "sys_triggers_on" -> {
                val ssid = (args.strArg("wifi_ssid") ?: args.strArg("ssid"))?.trim()
                val wifiAny = args.boolArg("wifi_any") == true
                val config = EventPure.sysTriggerConfig(ssid, args.boolArg("power"), wifiAny)
                    ?: return "ERROR: 参数无效或互斥（wifi_any 与 wifi_ssid 不能同传）；至少提供一个触发器：wifi_any=true（任意 WiFi 连接）、wifi_ssid（指定 SSID）或 power=true（充电插拔）"
                if (config.wifi && config.ssid.isNullOrBlank() && !wifiAny)
                    return "ERROR: wifi_ssid 为空时若要监听任意 WiFi 连接，请显式传 wifi_any=true"
                EventFlags.setSysTriggers(context, config.wifi, config.ssid, config.power)
                EventFlags.set(context, SYS_CHANNEL, true)
                // 二轮必修5：等服务跑完本次 refreshTriggers 后核对注册结果——用 sysRefreshSeq 代号等待本次刷新，
                // 注册失败/未生效回滚 flag 并触发服务重刷新（真正撤销已注册监听），不虚报 ok=true。
                // 基线须在 startForegroundService 之前捕获：否则本次刷新已完成时 baseline 会被写成新值，
                // 等待循环空转 4 秒后误回滚。
                val svc = EventMonitorService.instance
                val baseline = svc?.sysRefreshSeq ?: -1
                // 既有服务需 seq 超过原值；冷启动 baseline=-1 时新服务 seq 须达到 1（首次 refreshTriggers 跑完），
                // 统一用 maxOf(baseline, 0) 作阈值，避免 seq=0 时 0 > -1 提前 break、在 refresh 完成前核对。
                val threshold = maxOf(baseline, 0)
                // 注：基线与派发之间既有服务被其它路径触发刷新的极窄窗口可接受——flag 已写好，
                // 任何一次 refresh 读的都是新配置，监听状态核对仍正确；Int 回绕需 2^31 次刷新，记为已接受边界。
                ContextCompat.startForegroundService(context, Intent(context, EventMonitorService::class.java))
                var seq = baseline
                var waited = 0
                while (waited < 4000) {
                    val cur = EventMonitorService.instance
                    seq = cur?.sysRefreshSeq ?: -1
                    if (cur != null && seq > threshold) break
                    delay(100); waited += 100
                }
                val live = EventMonitorService.instance
                if (live == null || seq <= threshold) {
                    rollbackSysTriggers(context, "前台服务未在预期时间内完成触发器注册")
                    return "ERROR: 前台服务未在预期时间内完成触发器注册；已回滚触发器开关（可重试或改用其它触发器）"
                }
                val err = live.lastSysError
                val wifiOk = !config.wifi || live.sysWifiListening()
                val powerOk = !config.power || live.sysPowerListening()
                if (err != null || !wifiOk || !powerOk) {
                    val reason = err ?: listOfNotNull(
                        if (!wifiOk) "WiFi 监听注册未生效" else null,
                        if (!powerOk) "充电监听注册未生效" else null,
                    ).joinToString("、")
                    rollbackSysTriggers(context, reason)
                    return "ERROR: $reason；已回滚触发器开关（可重试或改用其它触发器）"
                }
                buildJsonObject {
                    put("ok", true)
                    put("wifi", config.wifi)
                    config.ssid?.let { put("wifi_ssid_filter", it) }
                    put("power", config.power)
                    put("kvNamespace", SYS_EVENTS_NAMESPACE)
                    put("note", "事件写入 ${SYS_EVENTS_NAMESPACE}/latest 与 history，可用 sys_triggers_read 或 live 轮询；无定位权限时指定 SSID 的触发器不记命中（不伪造）。只落事件，不自动执行任务（R119 联动留下一轮）。")
                }.toString()
            }
            "sys_triggers_off" -> {
                EventFlags.set(context, SYS_CHANNEL, false)
                EventFlags.setSysTriggers(context, false, null, false)
                if (EVENT_CHANNELS.any { EventFlags.enabled(context, it) }) EventMonitorService.instance?.let { ContextCompat.startForegroundService(context, Intent(context, EventMonitorService::class.java)) }
                else context.stopService(Intent(context, EventMonitorService::class.java))
                "系统触发器（WiFi 与充电）已全部关闭"
            }
            else -> "ERROR: 未知工具"
        }
    } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" }
    }
}

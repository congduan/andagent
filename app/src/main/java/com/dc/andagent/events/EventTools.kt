package com.dc.andagent.events

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.strParam
import com.dc.andagent.tools.KvStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.ArrayDeque

val EVENT_TOOLS = listOf("media_sessions", "media_events_on", "media_events_off", "media_events_read", "media_send_key", "notif_events_on", "notif_events_off", "notif_events_read", "notif_permission", "audio_events_on", "audio_events_off", "audio_events_read")

/** Channels owned by [EventMonitorService]; "geofence" was added by R76. */
internal val EVENT_CHANNELS = listOf("media", "notif", "audio", GEOFENCE_CHANNEL)

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
    fun read(context: Context, namespace: String, minutes: Int, pkg: String? = null): String {
        val history = KvStore(context).get(namespace, "history") as? JsonArray
        return buildJsonObject {
            put("events", EventHistory.read(history, System.currentTimeMillis() - minutes * 60_000L, pkg))
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
}

class EventNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { EventMonitorService.instance?.refreshMedia() }
    override fun onListenerDisconnected() { EventMonitorService.instance?.refreshMedia() }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!EventFlags.enabled(this, "notif") || EventHistory.excluded(sbn.packageName, packageName, EventFlags.excludes(this))) return
        val extras = sbn.notification.extras
        EventStore.put(this, "notif_events", EventStore.event("posted", buildJsonObject {
            put("package", sbn.packageName)
            put("title", extras.getCharSequence("android.title")?.toString()?.take(200) ?: "")
            put("text", extras.getCharSequence("android.text")?.toString()?.take(500) ?: "")
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
    private val knownAudioIds = mutableSetOf<Int>()
    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions -> syncSessions(sessions ?: emptyList()) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { logDevices("added", devices.filter { knownAudioIds.add(it.id) }) }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { logDevices("removed", devices.filter { knownAudioIds.remove(it.id) }) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EVENT_CHANNELS.forEach { EventFlags.set(this, it, false) }
            stopSelf(); return START_NOT_STICKY
        }
        if (EVENT_CHANNELS.none { EventFlags.enabled(this, it) }) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("event_monitor", "系统事件监听", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(6502, NotificationCompat.Builder(this, "event_monitor")
            .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle("系统事件监听中")
            .setContentText("媒体、通知、音频设备与地理围栏事件").setOngoing(true).addAction(0, "停止全部", stop).build())
        refreshMedia(); refreshAudio(); refreshGeofence()
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
    override fun onDestroy() {
        clearMedia(); if (audioListening) audioManager?.unregisterAudioDeviceCallback(deviceCallback)
        geofenceEngine?.stop(); geofenceEngine = null
        instance = null; super.onDestroy()
    }
    companion object { const val ACTION_STOP = "com.dc.andagent.events.STOP"; @Volatile var instance: EventMonitorService? = null }
}

class EventTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
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
        else -> "读取最近音频设备事件。"
    }
    override val parameters = when (name) {
        "media_events_read", "audio_events_read" -> schemaOf("duration_min" to intParam("最近分钟数，默认 10，范围 1-1440"))
        "notif_events_read" -> schemaOf("duration_min" to intParam("最近分钟数，默认 10，范围 1-1440"), "package" to strParam("可选包名"))
        "notif_events_on" -> schemaOf("exclude_apps" to buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "额外跳过的应用包名") })
        "media_send_key" -> schemaOf("keycode" to strParam("play、pause、toggle、next、prev"), required = listOf("keycode"))
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
            else -> "ERROR: 未知工具"
        }
    } catch (e: Exception) { "ERROR: ${e.message ?: e.javaClass.simpleName}" }
    }
}

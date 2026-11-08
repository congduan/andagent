package com.dc.andagent.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.dc.andagent.R
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** R14 automation coverage (7 tools). */
val AUTOMATION_TOOLS = listOf(
    "alarm_wait", "countdown", "calendar_reminder_check",
    "hotspot", "airplane", "tts", "screenshot_quick",
)

class AutomationTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "alarm_wait" -> "读取系统下一个闹钟的时间与剩余毫秒数，便于安排后续动作。"
        "countdown" -> "进程内倒计时：到点发本地通知。action=start|list|cancel。"
        "calendar_reminder_check" -> "查询未来 N 天带提醒的日历事件（事件标题、时间、提前分钟数）。"
        "hotspot" -> "查询便携热点状态或打开系统热点设置页（普通应用无法直接开关热点，需在系统设置中操作）。"
        "airplane" -> "读取飞行模式状态；切换会跳转系统设置页由用户操作。"
        "tts" -> "用系统 TTS 朗读一段文本。"
        "screenshot_quick" -> "快速截取当前屏幕（等同 screen_capture，返回缩略图与保存路径）。"
        else -> "自动化工具"
    }

    override val parameters: JsonObject = when (name) {
        "countdown" -> schemaOf(
            "action" to strParam("start|list|cancel，默认 start"),
            "seconds" to intParam("倒计时秒数 1..86400（start 时必填）"),
            "label" to strParam("倒计时备注"),
            "id" to strParam("cancel 时指定 countdown id"),
        )
        "calendar_reminder_check" -> schemaOf(
            "days" to intParam("查询未来天数，默认 3，上限 30"),
            "limit" to intParam("返回条数，默认 20"),
        )
        "hotspot" -> schemaOf("action" to strParam("status|open_settings|toggle，默认 status"))
        "airplane" -> schemaOf("action" to strParam("status|on|off，默认 status"))
        "tts" -> schemaOf(
            "text" to strParam("要朗读的文本"),
            "language" to strParam("BCP-47 语言标签，如 zh-CN、en-US"),
            "rate" to strParam("语速 0.5..2.0，默认 1.0"),
            required = listOf("text"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("hotspot", "airplane", "tts")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "alarm_wait" -> alarmWait()
                "countdown" -> countdown(args)
                "calendar_reminder_check" -> calendarReminders(args)
                "hotspot" -> hotspot(args)
                "airplane" -> airplane(args)
                "tts" -> tts(args)
                "screenshot_quick" -> ScreenCaptureTool(context).execute(args)
                else -> "ERROR: 未知自动化工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun alarmWait(): String {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return "ERROR: 闹钟服务不可用"
        val next = manager.nextAlarmClock ?: return buildJsonObject {
            put("hasAlarm", false)
            put("message", "系统未设置闹钟")
        }.toString()
        return buildJsonObject {
            put("hasAlarm", true)
            put("triggerAt", next.triggerTime)
            put("inMs", next.triggerTime - System.currentTimeMillis())
            put("hasShowIntent", next.showIntent != null)
        }.toString()
    }

    private suspend fun countdown(args: JsonObject): String {
        val action = args.strArg("action") ?: "start"
        if (action == "list") {
            return buildJsonObject { putJsonArray("countdowns") { pending.keys.forEach { add(it) } } }.toString()
        }
        if (action == "cancel") {
            val id = args.strArg("id") ?: return "ERROR: cancel 需要 id"
            val task = pending.remove(id) ?: return "ERROR: 未找到倒计时 $id"
            handler.removeCallbacks(task)
            return "DELETED: 已取消倒计时 $id"
        }
        if (action != "start") return "ERROR: action 仅支持 start|list|cancel"
        val seconds = args.intArg("seconds") ?: return "ERROR: 缺少 seconds"
        if (seconds !in 1..86_400) return "ERROR: seconds 需在 1..86400 之间"
        if (Build.VERSION.SDK_INT >= 33 && !permissions.ensure(listOf(Manifest.permission.POST_NOTIFICATIONS)))
            return "ERROR: 通知权限未授予"
        val label = args.strArg("label") ?: "倒计时"
        val id = "cd-${System.currentTimeMillis()}"
        val task = Runnable {
            pending.remove(id)
            fireCountdownNotification(label)
        }
        pending[id] = task
        handler.postDelayed(task, seconds * 1000L)
        return buildJsonObject {
            put("id", id)
            put("seconds", seconds)
            put("firesAt", System.currentTimeMillis() + seconds * 1000L)
            put("label", label)
            put("note", "倒计时仅在应用进程存活时有效")
        }.toString()
    }

    private fun fireCountdownNotification(label: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AndAgent 倒计时", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "倒计时结束提醒" },
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_andagent)
            .setContentTitle("倒计时结束")
            .setContentText(label)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private suspend fun calendarReminders(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_CALENDAR))) return "ERROR: 日历读取权限未授予"
        val days = (args.intArg("days") ?: 3).coerceIn(1, 30)
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val events = mutableListOf<Triple<Long, String, Long>>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.HAS_ALARM),
            "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.HAS_ALARM} = 1",
            arrayOf(now.toString(), end.toString()),
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count++ < limit) {
                events += Triple(cursor.getLong(0), cursor.getString(1) ?: "", cursor.getLong(2))
            }
        }
        val rows = buildJsonArray {
            events.forEach { (eventId, title, start) ->
                val minutes = context.contentResolver.query(
                    CalendarContract.Reminders.CONTENT_URI,
                    arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                    "${CalendarContract.Reminders.EVENT_ID} = ?",
                    arrayOf(eventId.toString()), null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
                add(buildJsonObject {
                    put("eventId", eventId)
                    put("title", title)
                    put("startAt", start)
                    put("minutesBefore", minutes ?: -1)
                })
            }
        }
        return buildJsonObject { put("days", days); put("count", rows.size); put("events", rows) }.toString()
    }

    private suspend fun hotspot(args: JsonObject): String {
        val action = args.strArg("action") ?: "status"
        if (action !in setOf("status", "open_settings", "toggle")) {
            return "ERROR: action 仅支持 status|open_settings|toggle"
        }
        if (action != "status") {
            return runCatching {
                context.startActivity(
                    Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "OPENED: 已打开系统热点设置页，请手动开关热点"
            }.getOrElse { "ERROR: 无法打开热点设置页：${it.message}" }
        }
        return buildJsonObject {
            put("supported", false)
            put("hint", "普通应用无法读取热点状态；可调用 open_settings 打开系统热点设置页")
        }.toString()
    }

    private suspend fun airplane(args: JsonObject): String {
        val action = args.strArg("action") ?: "status"
        val current = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) }.getOrDefault(0)
        if (action == "status") return buildJsonObject { put("airplaneModeOn", current == 1); put("raw", current) }.toString()
        if (action !in setOf("on", "off")) return "ERROR: action 仅支持 status|on|off"
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "OPENED: 已打开系统飞行模式设置页，请手动切换（应用无系统级开关权限）"
        }.getOrElse { "ERROR: 无法打开飞行模式设置页：${it.message}" }
    }

    private suspend fun tts(args: JsonObject): String {
        val text = args.strArg("text") ?: return "ERROR: 缺少 text"
        if (text.length > 2000) return "ERROR: 文本过长（上限 2000 字）"
        val language = args.strArg("language") ?: "zh-CN"
        val rate = (args.strArg("rate")?.toFloatOrNull() ?: 1.0f).coerceIn(0.5f, 2.0f)
        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context) { status ->
                val tts = engine
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    tts?.shutdown()
                    if (cont.isActive) cont.resume("ERROR: 系统 TTS 引擎初始化失败，请检查是否已安装语音引擎")
                    return@TextToSpeech
                }
                runCatching {
                    tts.language = Locale.forLanguageTag(language)
                    tts.setSpeechRate(rate)
                    @Suppress("DEPRECATION")
                    val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "andagent-tts")
                    if (cont.isActive) cont.resume(
                        if (result == TextToSpeech.SUCCESS) "WRITTEN: 已开始朗读 ${text.length} 字（$language）"
                        else "ERROR: TTS 朗读请求被拒绝（code=$result）",
                    )
                }.getOrElse { if (cont.isActive) cont.resume("ERROR: TTS 朗读失败：${it.message}") }
                handler.postDelayed({ tts.shutdown() }, 30_000L)
            }
            cont.invokeOnCancellation { engine?.shutdown() }
        }
    }

    companion object {
        private const val CHANNEL_ID = "andagent_countdown"
        private val handler = Handler(Looper.getMainLooper())
        private val pending = ConcurrentHashMap<String, Runnable>()
    }
}

package com.dc.andagent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.net.Uri
import com.dc.andagent.R
import com.dc.andagent.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

val SHORTCUT_TOOLS = listOf("shortcut_create", "shortcut_list", "shortcut_delete")

internal data class TaskShortcut(
    val id: String,
    val name: String,
    val prompt: String,
    val emoji: String,
    val createdAt: Long,
)

/** Metadata for shortcuts requested by this app; launcher ownership remains with Android. */
internal class ShortcutStore(context: Context) {
    private val prefs = context.getSharedPreferences("andagent_shortcuts", Context.MODE_PRIVATE)

    fun list(): List<TaskShortcut> {
        val rows = JSONArray(prefs.getString("shortcuts", "[]") ?: "[]")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            TaskShortcut(
                row.getString("id"), row.getString("name"), row.getString("prompt"),
                row.optString("emoji"), row.getLong("createdAt"),
            )
        }
    }

    fun save(record: TaskShortcut) {
        write(list().filterNot { it.id == record.id } + record)
    }

    fun delete(id: String) {
        write(list().filterNot { it.id == id })
    }

    private fun write(records: List<TaskShortcut>) {
        val rows = JSONArray()
        records.forEach { record ->
            rows.put(JSONObject().put("id", record.id).put("name", record.name)
                .put("prompt", record.prompt).put("emoji", record.emoji)
                .put("createdAt", record.createdAt))
        }
        check(prefs.edit().putString("shortcuts", rows.toString()).commit()) { "快捷方式记录保存失败" }
    }
}

internal object ShortcutFactory {
    fun id(name: String, prompt: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((name + "\u0000" + prompt).toByteArray(Charsets.UTF_8))
        return "task_" + bytes.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun intent(context: Context, prompt: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("andagent://send?prompt=${Uri.encode(prompt)}&auto=1"))
            .setPackage(context.packageName)

    fun emojiBitmap(emoji: String): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val radius = 40f
        val background = Color.rgb(0x0b, 0x0d, 0x11)
        val pixels = IntArray(size * size) { index ->
            val x = index % size + 0.5f
            val y = index / size + 0.5f
            val nearestX = x.coerceIn(radius, size - radius)
            val nearestY = y.coerceIn(radius, size - radius)
            val dx = x - nearestX
            val dy = y - nearestY
            if (dx * dx + dy * dy <= radius * radius) background else Color.TRANSPARENT
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = background
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        paint.color = Color.WHITE
        paint.textSize = 108f
        paint.textAlign = Paint.Align.CENTER
        val baseline = size / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(emoji, size / 2f, baseline, paint)
        return bitmap
    }
}

class ShortcutTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "shortcut_create" -> "请求在桌面添加一键任务图标；点击后打开 AndAgent 并自动发送预置 prompt。启动器会弹出确认框。"
        "shortcut_list" -> "列出本应用请求创建的桌面一键任务图标及 ID。"
        "shortcut_delete" -> "停用指定桌面快捷方式并移除本地记录；Android 不允许应用直接移除已 pin 图标，用户需长按图标拖到卸载才能彻底移除。"
        else -> "桌面快捷方式工具"
    }

    override val parameters: JsonObject = when (name) {
        "shortcut_create" -> schemaOf(
            "name" to strParam("桌面显示名"),
            "prompt" to strParam("点击图标后自动发送的指令"),
            "emoji" to strParam("可选：图标上的单个 emoji 字符"),
            "mode" to strParam("可选：pin（默认，请求固定到桌面，启动器弹确认框）或 dynamic（动态快捷方式，无需启动器确认，槽位有限会被挤掉）"),
            required = listOf("name", "prompt"),
        )
        "shortcut_delete" -> schemaOf("id" to strParam("shortcut_list 返回的 ID"), required = listOf("id"))
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "shortcut_create" -> create(args)
            "shortcut_list" -> list()
            "shortcut_delete" -> delete(args)
            else -> "ERROR: " + tr("未知快捷方式工具 $name", "unknown shortcut tool $name")
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun create(args: JsonObject): String {
        val label = args.strArg("name")?.trim() ?: return "ERROR: " + tr("缺少 name", "missing name")
        val prompt = args.strArg("prompt") ?: return "ERROR: " + tr("缺少 prompt", "missing prompt")
        val emoji = args.strArg("emoji")?.trim().orEmpty()
        val mode = args.strArg("mode")?.trim().orEmpty()
        if (mode.isNotEmpty() && mode != "pin" && mode != "dynamic") {
            return "ERROR: " + tr("mode 仅支持 pin 或 dynamic", "mode must be pin or dynamic")
        }
        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return "ERROR: " + tr("当前设备没有快捷方式服务", "no shortcut service on this device")
        val id = ShortcutFactory.id(label, prompt)
        val icon = if (emoji.isEmpty()) Icon.createWithResource(context, R.mipmap.ic_launcher)
            else Icon.createWithBitmap(ShortcutFactory.emojiBitmap(emoji))
        val shortcut = ShortcutInfo.Builder(context, id)
            .setShortLabel(label)
            .setIntent(ShortcutFactory.intent(context, prompt))
            .setIcon(icon)
            .build()
        val store = ShortcutStore(context)
        val existing = store.list().firstOrNull { it.id == id }
        val saved = {
            store.save(TaskShortcut(id, label, prompt, emoji, existing?.createdAt ?: System.currentTimeMillis()))
        }
        if (mode == "dynamic") {
            return createDynamic(manager, shortcut, id, label, saved)
        }

        if (!manager.isRequestPinShortcutSupported) {
            return "ERROR: " + tr("启动器不支持 pin，请手动创建", "the launcher does not support pinning; create it manually")
        }
        if (manager.pinnedShortcuts.any { it.id == id }) {
            check(manager.updateShortcuts(listOf(shortcut))) { tr("更新快捷方式失败", "failed to update the shortcut") }
            saved()
            return tr("已更新桌面快捷方式「$label」（id=$id）", "Home screen shortcut \"$label\" updated (id=$id)")
        }
        if (!manager.requestPinShortcut(shortcut, null)) {
            return "ERROR: " + tr("启动器未接受 pin 请求，请手动创建", "the launcher rejected the pin request; create it manually")
        }
        saved()
        return tr(
            "已请求添加桌面快捷方式「$label」（id=$id）；请在启动器确认",
            "Requested to add home screen shortcut \"$label\" (id=$id); confirm it in the launcher",
        )
    }

    /** R77: dynamic shortcut path — no launcher confirmation needed. */
    private fun createDynamic(
        manager: ShortcutManager,
        shortcut: ShortcutInfo,
        id: String,
        label: String,
        saved: () -> Unit,
    ): String {
        val dynamicIds = manager.dynamicShortcuts.map { it.id }
        if (id in dynamicIds) {
            check(manager.updateShortcuts(listOf(shortcut))) { tr("更新快捷方式失败", "failed to update the shortcut") }
            saved()
            return tr("已更新动态快捷方式「$label」（id=$id）", "Dynamic shortcut \"$label\" updated (id=$id)")
        }
        // Slot full → drop the oldest dynamic shortcut to make room.
        val max = manager.maxShortcutCountPerActivity
        val current = manager.dynamicShortcuts
        var evicted: ShortcutInfo? = null
        if (current.size >= max) {
            // ShortcutInfo has no per-shortcut creation time; lastChangedTimestamp (API 25+) is the closest stable ordering.
            val evict: ShortcutInfo? = current.minByOrNull { it: ShortcutInfo -> it.lastChangedTimestamp }
            if (evict == null) {
                return "ERROR: " + tr("动态快捷方式槽位已满且无法腾位", "dynamic shortcut slots are full and cannot be freed")
            }
            manager.removeDynamicShortcuts(listOf(evict.id))
            evicted = evict
        }
        if (!manager.addDynamicShortcuts(listOf(shortcut))) {
            // Codex R77 must-fix: the slot was already freed above — restore the
            // evicted shortcut best-effort so a rate-limited add doesn't lose it.
            evicted?.let { runCatching { manager.addDynamicShortcuts(listOf(it)) } }
            return "ERROR: " + tr("动态快捷方式添加失败（速率限制或槽位不足）", "failed to add the dynamic shortcut (rate limit or no free slot)")
        }
        saved()
        return tr(
            "已添加动态快捷方式「$label」（id=$id）；无需启动器确认，长按应用图标可见",
            "Dynamic shortcut \"$label\" added (id=$id); no launcher confirmation needed — visible via long-press on the app icon",
        )
    }

    private fun list(): String = buildJsonArray {
        ShortcutStore(context).list().forEach { record ->
            add(buildJsonObject {
                put("id", record.id)
                put("name", record.name)
                put("prompt", record.prompt)
                put("createdAt", record.createdAt)
                put(
                    "launcherManaged",
                    tr(
                        "图标由启动器管理；shortcut_delete 仅停用，长按图标拖到卸载可彻底移除",
                        "Icons are managed by the launcher; shortcut_delete only disables them — long-press the icon and drag it to uninstall to remove it completely",
                    ),
                )
            })
        }
    }.toString()

    private fun delete(args: JsonObject): String {
        val id = args.strArg("id") ?: return "ERROR: " + tr("缺少 id", "missing id")
        val store = ShortcutStore(context)
        if (store.list().none { it.id == id }) {
            return "ERROR: " + tr("快捷方式记录不存在：$id", "shortcut record not found: $id")
        }
        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return "ERROR: " + tr("当前设备没有快捷方式服务", "no shortcut service on this device")
        manager.disableShortcuts(listOf(id))
        store.delete(id)
        return tr(
            "已停用快捷方式 $id，并移除本地记录；长按桌面图标拖到卸载可彻底移除",
            "Shortcut $id disabled and its local record removed; long-press the icon and drag it to uninstall to remove it completely",
        )
    }
}

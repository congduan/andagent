package com.dc.andagent.live

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Small floating canvas for live results; permission flow matches the camera overlay. */
class LiveOverlayHost(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = mutableMapOf<String, View>()

    fun prepare(): Boolean {
        if (Settings.canDrawOverlays(activity)) return true
        main.post {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")))
        }
        return false
    }

    fun update(id: String, value: JsonElement) {
        main.post {
            if (!Settings.canDrawOverlays(activity) || activity.isFinishing) return@post
            val manager = activity.getSystemService(WindowManager::class.java)
            val view = windows[id] as? ResultCanvas ?: ResultCanvas(activity).also {
                val density = activity.resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    (320 * density).toInt(), (180 * density).toInt(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 100 + windows.size * 30 }
                manager.addView(it, params)
                windows[id] = it
            }
            view.value = value
            view.invalidate()
        }
    }

    fun remove(id: String) { main.post { windows.remove(id)?.let { activity.getSystemService(WindowManager::class.java).removeView(it) } } }
    fun close() { main.post { windows.keys.toList().forEach(::remove) } }

    private class ResultCanvas(activity: Activity) : View(activity) {
        var value: JsonElement = JsonPrimitive("")
        private val density = activity.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            // 与 OverlayChrome token 对齐：card-bg 圆角底 + 9% 白描边。
            val r = 16f * density
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = com.dc.andagent.ui.OverlayChrome.BG })
            canvas.drawRoundRect(density / 2f, density / 2f, width - density / 2f, height - density / 2f, r, r,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = density; color = com.dc.andagent.ui.OverlayChrome.BORDER
                })
            paint.color = com.dc.andagent.ui.OverlayChrome.ACCENT; paint.textSize = 14f * density
            canvas.drawText("Live", 14f * density, 20f * density, paint)
            paint.color = com.dc.andagent.ui.OverlayChrome.TEXT; paint.textSize = 11f * density
            val lines = value.toString().chunked(42).take(5)
            lines.forEachIndexed { index, line -> canvas.drawText(line, 14f * density, 36f * density + index * 16f * density, paint) }
        }
    }
}

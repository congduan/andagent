package com.dc.andagent.scrcpy

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A small draggable video surface. The decoder is started only after surfaceChanged. */
class FloatingMirrorWindow(
    context: Context,
    private val onSurfaceReady: (SurfaceHolder) -> Unit,
    private val onSurfaceGone: () -> Unit,
    private val onReconnect: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val width = (240 * density).toInt()
    private val params = WindowManager.LayoutParams(
        width, (width * 9 / 16) + (28 * density).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.OPAQUE,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * density).toInt(); y = (80 * density).toInt() }
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
    private val surface = SurfaceView(context)
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE); setBackgroundColor(0xcc222222.toInt()); gravity = Gravity.CENTER
        text = "正在连接…"; visibility = View.VISIBLE
        setOnClickListener { onReconnect() }
    }
    private var shown = false

    init {
        val bar = LinearLayout(context).apply { setBackgroundColor(0xff252525.toInt()); gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply { text = "ADB 投屏"; setTextColor(Color.WHITE); setPadding(8, 0, 0, 0) }
        val close = TextView(context).apply {
            text = "关闭"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, (28 * density).toInt(), 1f))
        bar.addView(close, LinearLayout.LayoutParams((48 * density).toInt(), (28 * density).toInt()))
        bar.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        if (shown) wm.updateViewLayout(root, params)
                        return true
                    }
                }
                return true
            }
        })
        val video = FrameLayout(context)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        video.addView(message, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar)
        root.addView(video, LinearLayout.LayoutParams(-1, width * 9 / 16))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = onSurfaceReady(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()
        })
    }

    fun show() { wm.addView(root, params); shown = true }
    fun showConnecting() { message.text = "正在连接…"; message.visibility = View.VISIBLE }
    fun showDisconnected() { message.text = "连接断开，点击重连"; message.visibility = View.VISIBLE }
    fun showVideo() { message.visibility = View.GONE }
    fun remove() { if (shown) { shown = false; wm.removeView(root) } }
}

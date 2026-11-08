package com.dc.andagent.scrcpy

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/** Floating video surface with a separate touch layer above the decoded image. */
class FloatingMirrorWindow(
    context: Context,
    private val onSurfaceReady: (SurfaceHolder) -> Unit,
    private val onSurfaceGone: () -> Unit,
    private val onReconnect: () -> Unit,
    private val onClose: () -> Unit,
    private val onControl: (ByteArray) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val width = (240 * density).toInt()
    private val screenW = context.resources.displayMetrics.widthPixels
    private val screenH = context.resources.displayMetrics.heightPixels
    private val grab = (56 * density).toInt()
    private val params = WindowManager.LayoutParams(
        width, (width * 9 / 16) + (28 * density).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * density).toInt(); y = (80 * density).toInt() }
    private val border = GradientDrawable().apply {
        setColor(com.dc.andagent.ui.OverlayChrome.BG)
        cornerRadius = 16 * density
    }
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = border }
    private val surface = SurfaceView(context)
    private val touchLayer = View(context).apply { visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE); setBackgroundColor(0xCC121721.toInt()); gravity = Gravity.CENTER
        text = "正在连接…"; visibility = View.VISIBLE
        setOnClickListener { onReconnect() }
    }
    private val badge = TextView(context).apply {
        setTextColor(Color.WHITE); setBackgroundColor(0xaa555555.toInt())
        setPadding(6, 2, 6, 2); text = "仅观看"; visibility = View.GONE
    }
    private var shown = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var controlAvailable = false
    private var passthrough = false
    private var touching = false
    private var scrolling = false
    private var lastX = 0f
    private var lastY = 0f
    private var scrollY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0

    init {
        updateBorder()
        com.dc.andagent.ui.OverlayChrome.applyRounded(root)
        root.elevation = 8 * density
        val bar = LinearLayout(context).apply { setBackgroundColor(com.dc.andagent.ui.OverlayChrome.HEADER_BG); gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(context).apply { text = "ADB 投屏"; setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT); setPadding((12 * density).toInt(), 0, 0, 0) }
        val close = TextView(context).apply {
            text = "✕"; setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT_DIM); textSize = 16f; gravity = Gravity.CENTER
            setOnClickListener { onClose() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, (28 * density).toInt(), 1f))
        bar.addView(close, LinearLayout.LayoutParams((48 * density).toInt(), (28 * density).toInt()))
        val longPress = Runnable { passthrough = !passthrough; updateBorder(); badge.text = if (passthrough) "拖动模式" else "可操控"; badge.visibility = View.VISIBLE }
        bar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.rawX; dragY = event.rawY; startX = params.x; startY = params.y
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong()); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.rawX - dragX, event.rawY - dragY) > slop) handler.removeCallbacks(longPress)
                    moveWindow(event.rawX, event.rawY); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(longPress); true }
                else -> true
            }
        }
        val video = FrameLayout(context)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        video.addView(message, FrameLayout.LayoutParams(-1, -1))
        video.addView(touchLayer, FrameLayout.LayoutParams(-1, -1))
        video.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        touchLayer.setOnTouchListener { _, event -> handleTouch(event) }
        root.addView(bar)
        root.addView(video, LinearLayout.LayoutParams(-1, width * 9 / 16))
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = onSurfaceReady(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()
        })
    }

    private fun moveWindow(rawX: Float, rawY: Float) {
        // 边界夹取：浮窗至少保留 56dp 在屏幕内。
        params.x = (startX + (rawX - dragX).toInt()).coerceIn(-(width - grab), screenW - grab)
        params.y = (startY + (rawY - dragY).toInt()).coerceIn(0, screenH - grab)
        if (shown) wm.updateViewLayout(root, params)
    }

    private fun point(x: Float, y: Float): ScrcpyControl.Point? =
        ScrcpyControl.mapPoint(x, y, touchLayer.width, touchLayer.height, videoWidth, videoHeight)

    private fun emit(action: Int, x: Float, y: Float) {
        point(x, y)?.let { onControl(ScrcpyControl.touch(action, it, videoWidth, videoHeight)) }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (passthrough || !controlAvailable) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragX = event.rawX; dragY = event.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> moveWindow(event.rawX, event.rawY)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrolling = false
                touching = point(event.x, event.y) != null
                lastX = event.x; lastY = event.y
                if (touching) emit(ScrcpyControl.DOWN, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (touching) emit(ScrcpyControl.UP, lastX, lastY)
                touching = false; scrolling = true
                scrollY = (event.getY(0) + event.getY(1)) / 2f
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    val cy = (event.getY(0) + event.getY(1)) / 2f
                    val cx = (event.getX(0) + event.getX(1)) / 2f
                    val delta = cy - scrollY
                    if (abs(delta) >= 8f) {
                        point(cx, cy)?.let { onControl(ScrcpyControl.scroll(it, videoWidth, videoHeight, 0f, -delta / 80f)) }
                        scrollY = cy
                    }
                } else if (touching && event.pointerCount == 1 && hypot(event.x - lastX, event.y - lastY) >= 8f) {
                    emit(ScrcpyControl.MOVE, event.x, event.y)
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> { scrolling = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // UP must still reach the server when the finger leaves the letterboxed image.
                if (touching) {
                    val end = if (point(event.x, event.y) != null) event.x to event.y else lastX to lastY
                    emit(ScrcpyControl.UP, end.first, end.second)
                }
                touching = false; scrolling = false
            }
        }
        return true
    }

    private fun updateBorder() {
        border.setStroke(1, if (controlAvailable && !passthrough) 0xff4caf50.toInt() else 0xff777777.toInt())
    }

    fun setControl(available: Boolean, w: Int = 0, h: Int = 0) {
        controlAvailable = available
        videoWidth = w; videoHeight = h
        touching = false; scrolling = false
        updateBorder()
        badge.text = when { !available -> "仅观看"; passthrough -> "拖动模式"; else -> "可操控" }
        badge.visibility = if (available && !passthrough) View.GONE else View.VISIBLE
    }

    fun show() { wm.addView(root, params); com.dc.andagent.ui.OverlayChrome.enter(root); shown = true }
    fun showConnecting() { setControl(false); touchLayer.visibility = View.GONE; message.text = "正在连接…"; message.visibility = View.VISIBLE }
    fun showDisconnected() { setControl(false); touchLayer.visibility = View.GONE; message.text = "连接断开，点击重连"; message.visibility = View.VISIBLE }
    fun showVideo() { message.visibility = View.GONE; touchLayer.visibility = View.VISIBLE }
    fun remove() { handler.removeCallbacksAndMessages(null); if (shown) { shown = false; wm.removeView(root) } }
}

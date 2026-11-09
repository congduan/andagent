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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dc.andagent.ui.FloatingDragHelper
import kotlin.math.abs
import kotlin.math.hypot

/** Floating video surface with a separate touch layer above the decoded image. */
class FloatingMirrorWindow(
    private val context: Context,
    private val onSurfaceReady: (SurfaceHolder) -> Unit,
    private val onSurfaceGone: () -> Unit,
    private val onReconnect: () -> Unit,
    private val onClose: () -> Unit,
    private val onControl: (ByteArray) -> Unit,
    private val onQualitySwitch: (Int) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val width = (240 * density).toInt()
    private val headerPx = (28 * density).toInt()
    private val videoPx = width * 9 / 16
    private val toolbarPx = (32 * density).toInt()
    private var baseHeight = headerPx + videoPx
    private val params = WindowManager.LayoutParams(
        width, baseHeight,
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
    private lateinit var dragHelper: FloatingDragHelper
    private lateinit var videoLayout: LinearLayout.LayoutParams
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

    // 工具条：默认折叠，标题栏 ⌄/⌃ 展开一行按键。
    private var toolbarExpanded = false
    private var currentMaxSize = MirrorToolbar.QUALITY_SD
    private val toolbarToggle = TextView(context).apply {
        text = "⌄"; setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT); textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { toggleToolbar() }
    }
    private val backButton = toolButton("◀ 返回") { injectSystemKey(MirrorToolbar.KEY_BACK) }
    private val homeButton = toolButton("● 主页") { injectSystemKey(MirrorToolbar.KEY_HOME) }
    private val overviewButton = toolButton("▦ 多任务") { injectSystemKey(MirrorToolbar.KEY_APP_SWITCH) }
    private val qualityButton = toolButton(MirrorToolbar.qualityLabel(currentMaxSize)) { onQualityClick() }
    private val systemButtons = listOf(backButton, homeButton, overviewButton)
    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(com.dc.andagent.ui.OverlayChrome.HEADER_BG)
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        for (b in systemButtons + qualityButton) addView(b, LinearLayout.LayoutParams(0, toolbarPx, 1f))
    }

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
        bar.addView(title, LinearLayout.LayoutParams(0, headerPx, 1f))
        bar.addView(toolbarToggle, LinearLayout.LayoutParams((36 * density).toInt(), headerPx))
        bar.addView(close, LinearLayout.LayoutParams((48 * density).toInt(), headerPx))
        // 标题拖动完全交给 dragHelper.bindDrag；长按手势通过 bindDrag 的 onLongPress 回调保留。
        val longPress = {
            passthrough = !passthrough; updateBorder(); refreshToolbarState()
            badge.text = if (passthrough) "拖动模式" else "可操控"; badge.visibility = View.VISIBLE
        }
        val video = FrameLayout(context)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        video.addView(message, FrameLayout.LayoutParams(-1, -1))
        video.addView(touchLayer, FrameLayout.LayoutParams(-1, -1))
        video.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        touchLayer.setOnTouchListener { _, event -> handleTouch(event) }
        root.addView(bar)
        root.addView(toolbar, LinearLayout.LayoutParams(-1, toolbarPx))
        videoLayout = LinearLayout.LayoutParams(-1, videoPx)
        root.addView(video, videoLayout)
        val resize = TextView(context).apply {
            text = "◢"; textSize = 23f; gravity = Gravity.CENTER
            setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT)
            setBackgroundColor(com.dc.andagent.ui.OverlayChrome.HEADER_BG)
            elevation = 16 * density
            contentDescription = "调整投屏浮窗大小"
        }
        video.addView(resize, FrameLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt(), Gravity.BOTTOM or Gravity.END))
        root.setOnApplyWindowInsetsListener { view, insets ->
            // Type.systemBars()/getInsets(int) 是 API 30+ 接口；minSdk 26 的旧系统直接返回原 insets（无安全区 padding，不崩溃）。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        root.requestApplyInsets()
        dragHelper = FloatingDragHelper(context, wm, root, params, "mirror_${context.getSharedPreferences("adb_mirror", Context.MODE_PRIVATE).getString("target", "default")}",
            onChange = {
                baseHeight = params.height - if (toolbarExpanded) toolbarPx else 0
                videoLayout.height = (baseHeight - headerPx).coerceAtLeast(1)
                video.layoutParams = videoLayout
            }).also { it.restore(); it.bindDrag(title, longPress); it.bindResize(resize) }
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = onSurfaceReady(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()
        })
        refreshToolbarState()
        applyToolbar()
    }

    private fun toolButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT)
        textSize = 11f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun toggleToolbar() {
        toolbarExpanded = MirrorToolbar.toggled(toolbarExpanded)
        applyToolbar()
    }

    /** 展开/折叠时同步浮窗高度，避免固定 params 高度挤压视频或裁掉工具条。 */
    private fun applyToolbar() {
        toolbar.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
        toolbarToggle.text = MirrorToolbar.toggleLabel(toolbarExpanded)
        params.height = baseHeight + MirrorToolbar.extraHeightPx(toolbarExpanded, toolbarPx)
        if (shown) wm.updateViewLayout(root, params)
    }

    private fun systemKeysEnabled(): Boolean = controlAvailable && !passthrough

    /** 系统键仅在可操控时点亮；仅观看/拖动模式置灰且点击无效。 */
    private fun refreshToolbarState() {
        val enabled = systemKeysEnabled()
        for (b in systemButtons) {
            b.isEnabled = enabled
            b.alpha = if (enabled) 1f else 0.4f
        }
    }

    private fun injectSystemKey(keycode: Int) {
        if (!systemKeysEnabled()) return
        for (bytes in MirrorToolbar.systemKey(keycode)) onControl(bytes)
    }

    private fun onQualityClick() = onQualitySwitch(MirrorToolbar.nextQuality(currentMaxSize))

    /** service 接线：切换成功后回写当前档位，保持按钮文案与会话一致。 */
    fun setQuality(maxSize: Int) {
        currentMaxSize = maxSize
        qualityButton.text = MirrorToolbar.qualityLabel(maxSize)
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
                MotionEvent.ACTION_MOVE -> dragHelper.dragBy(startX, startY, (event.rawX - dragX).toInt(), (event.rawY - dragY).toInt())
                MotionEvent.ACTION_UP -> dragHelper.persist()
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
        refreshToolbarState()
    }

    fun show() { wm.addView(root, params); com.dc.andagent.ui.OverlayChrome.enter(root); shown = true }
    fun showConnecting() { setControl(false); touchLayer.visibility = View.GONE; message.text = "正在连接…"; message.visibility = View.VISIBLE }
    fun showDisconnected() { setControl(false); touchLayer.visibility = View.GONE; message.text = "连接断开，点击重连"; message.visibility = View.VISIBLE }
    fun showVideo() { message.visibility = View.GONE; touchLayer.visibility = View.VISIBLE }
    fun remove() { handler.removeCallbacksAndMessages(null); if (shown) { shown = false; wm.removeView(root) } }
}

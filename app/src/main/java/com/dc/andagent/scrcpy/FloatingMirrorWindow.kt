package com.dc.andagent.scrcpy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import android.widget.ProgressBar
import android.widget.TextView
import com.dc.andagent.ui.FloatingDragHelper
import com.dc.andagent.ui.OverlayChrome
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
    private fun dp(v: Int) = (v * density).toInt()

    private val width = (240 * density).toInt()
    private val headerPx = dp(MirrorChrome.MIRROR_HEADER_DP)              // 32dp
    private val dividerPx = dp(1).coerceAtLeast(1)                         // 1dp header 分隔线
    private val videoPx = width * 9 / 16
    // 工具条浮层高度：经 MirrorChrome 钳制，始终 ≤ 44dp（不遮挡视频关键区域超限）。
    private val toolbarOverlayPx = dp(MirrorChrome.clampedToolbarHeightDp(MirrorChrome.MAX_TOOLBAR_OVERLAY_DP))
    private val baseHeight = headerPx + dividerPx + videoPx
    private val params = WindowManager.LayoutParams(
        width, baseHeight,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(80) }
    private val border = GradientDrawable().apply {
        setColor(OverlayChrome.BG)
        cornerRadius = 16 * density
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = border
    }
    private val surface = SurfaceView(context)
    private val touchLayer = View(context).apply { visibility = View.GONE }
    private val handler = Handler(Looper.getMainLooper())

    // ── 状态层：居中竖排（旋转圆环 + 13sp 文案），底 CONNECTING_BG ──
    private val messageSpinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(OverlayChrome.ACCENT)
    }
    private val messageText = TextView(context).apply {
        text = "正在连接…"
        setTextColor(OverlayChrome.TEXT_DIM)
        textSize = 13f
        gravity = Gravity.CENTER
    }
    private val messageLayer = FrameLayout(context).apply {
        setBackgroundColor(OverlayChrome.CONNECTING_BG)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(messageSpinner, LinearLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(messageText, LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL
            })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    // ── badge：顶部左侧内浮层，圆角 8dp，出现 2s 后自动淡出，不再常驻 ──
    private val badge = TextView(context).apply {
        setTextColor(OverlayChrome.TEXT)
        textSize = 12f
        background = OverlayChrome.roundedRect(context, OverlayChrome.TOOLBAR_BG, MirrorChrome.BADGE_RADIUS_DP)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        text = "仅观看"
        visibility = View.GONE
    }
    private val fadeBadge = Runnable {
        badge.animate().alpha(0f).setDuration(280)
            .withEndAction { badge.visibility = View.GONE; badge.alpha = 1f }.start()
    }

    private var shown = false
    private lateinit var dragHelper: FloatingDragHelper
    private lateinit var videoLayout: LinearLayout.LayoutParams
    private var videoWidth = 0
    private var videoHeight = 0
    private var controlAvailable = false
    private var passthrough = false
    private var status = MirrorChrome.Status.CONNECTING
    private var touching = false
    private var scrolling = false
    private var lastX = 0f
    private var lastY = 0f
    private var scrollY = 0f
    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0

    // ── 工具条浮层（展开覆盖视频底部，不占独立行、不挤压视频高度）──
    private var toolbarExpanded = false
    private var currentMaxSize = MirrorToolbar.QUALITY_SD
    private var toolbarAnimator: ValueAnimator? = null
    private val toolbarToggle = TextView(context).apply {
        text = "⌄"
        setTextColor(OverlayChrome.TEXT)
        textSize = 16f
        gravity = Gravity.CENTER
        background = OverlayChrome.ripple(context, 10)
        setOnClickListener { toggleToolbar() }
    }
    // 导航组：图标化字符 + 32dp 圆形触摸热区 + ripple；字形缺字体时仍是 unicode，不引图标库。
    private val backButton = navButton("◀", "返回") { injectSystemKey(MirrorToolbar.KEY_BACK) }
    private val homeButton = navButton("●", "主页") { injectSystemKey(MirrorToolbar.KEY_HOME) }
    private val overviewButton = navButton("▦", "多任务") { injectSystemKey(MirrorToolbar.KEY_APP_SWITCH) }
    // 画质组：右侧单独分组，切换后文案随之更新。
    private val qualityButton = TextView(context).apply {
        text = MirrorToolbar.qualityLabel(currentMaxSize)
        setTextColor(OverlayChrome.TEXT)
        textSize = 11f
        gravity = Gravity.CENTER
        background = OverlayChrome.ripple(context, 8)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onQualityClick() }
    }
    private val systemButtons = listOf(backButton, homeButton, overviewButton)

    // 标题栏状态点：绿=已连接 / 黄=连接中 / 红=断开。
    private val statusDotDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val statusDot = View(context).apply { background = statusDotDrawable }

    private val toolbarOverlay = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = OverlayChrome.roundedRect(context, OverlayChrome.TOOLBAR_BG, MirrorChrome.TOOLBAR_RADIUS_DP)
        setPadding(dp(8), 0, dp(8), 0)
        visibility = View.GONE
    }
    // 导航组与画质组之间的 1dp 竖分隔线（30% 透明度）。
    private val groupDivider = View(context).apply { setBackgroundColor(OverlayChrome.DIVIDER) }

    init {
        updateBorder()
        OverlayChrome.applyRounded(root)
        root.elevation = 8 * density

        // ── 标题栏 32dp：状态点 + 投屏标题 + 工具条开关/关闭（各 40dp 点击区 + 涟漪）──
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(OverlayChrome.HEADER_BG)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        bar.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        val title = TextView(context).apply {
            text = "投屏"
            setTextColor(OverlayChrome.TEXT)
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(OverlayChrome.TEXT_DIM)
            textSize = 16f
            gravity = Gravity.CENTER
            background = OverlayChrome.ripple(context, 10)
            setOnClickListener { onClose() }
        }
        bar.addView(toolbarToggle, LinearLayout.LayoutParams(dp(MirrorChrome.HEADER_BUTTON_DP), LinearLayout.LayoutParams.MATCH_PARENT))
        bar.addView(close, LinearLayout.LayoutParams(dp(MirrorChrome.HEADER_BUTTON_DP), LinearLayout.LayoutParams.MATCH_PARENT))

        // header 背景与视频区之间的 1dp 分隔线。
        val headerDivider = View(context).apply { setBackgroundColor(OverlayChrome.DIVIDER) }

        // ── 工具条浮层内容：导航组 | 竖分隔线 | 弹性 spacer | 画质组 ──
        val navGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (b in systemButtons) addView(
                b, LinearLayout.LayoutParams(dp(MirrorChrome.TOOL_BUTTON_DP), dp(MirrorChrome.TOOL_BUTTON_DP)),
            )
        }
        toolbarOverlay.addView(navGroup, LinearLayout.LayoutParams(-2, -2))
        toolbarOverlay.addView(groupDivider, LinearLayout.LayoutParams(dp(1), dp(20)).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        toolbarOverlay.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        toolbarOverlay.addView(qualityButton, LinearLayout.LayoutParams(-2, -2))

        // ── 视频 FrameLayout：surface / touch / badge / 工具条浮层 / 状态层 / 缩放把手 ──
        val video = FrameLayout(context)
        video.addView(surface, FrameLayout.LayoutParams(-1, -1))
        video.addView(touchLayer, FrameLayout.LayoutParams(-1, -1))
        video.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(8); leftMargin = dp(8)
        })
        // 浮层贴视频底部，rightMargin 让出右下角 44dp 缩放把手，避免与按钮重叠。
        video.addView(toolbarOverlay, FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM).apply { rightMargin = dp(44) })
        video.addView(messageLayer, FrameLayout.LayoutParams(-1, -1))
        touchLayer.setOnTouchListener { _, event -> handleTouch(event) }
        messageLayer.setOnClickListener { onReconnect() }

        root.addView(bar, LinearLayout.LayoutParams(-1, headerPx))
        root.addView(headerDivider, LinearLayout.LayoutParams(-1, dividerPx))
        videoLayout = LinearLayout.LayoutParams(-1, videoPx)
        root.addView(video, videoLayout)

        val resize = TextView(context).apply {
            text = "◢"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(OverlayChrome.TEXT)
            setBackgroundColor(OverlayChrome.HEADER_BG)
            elevation = 16 * density
            contentDescription = "调整投屏浮窗大小"
        }
        video.addView(resize, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END))

        root.setOnApplyWindowInsetsListener { view, insets ->
            // Type.systemBars()/getInsets(int) 是 API 30+ 接口；minSdk 26 的旧系统直接返回原 insets（无安全区 padding，不崩溃）。
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            }
            insets
        }
        root.requestApplyInsets()

        val longPress = {
            passthrough = !passthrough
            updateBorder()
            refreshToolbarState()
            applyToolbar(animate = true)   // passthrough 下工具条浮层自动收起（防误触）
            syncBadge()
        }
        dragHelper = FloatingDragHelper(
            context, wm, root, params,
            "mirror_${context.getSharedPreferences("adb_mirror", Context.MODE_PRIVATE).getString("target", "default")}",
            onChange = {
                videoLayout.height = (params.height - headerPx - dividerPx).coerceAtLeast(1)
                video.layoutParams = videoLayout
            },
        ).also { it.restore(); it.bindDrag(title, longPress); it.bindResize(resize) }

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = onSurfaceReady(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurfaceGone()
        })

        renderStatus()
        refreshToolbarState()
        applyToolbar(animate = false)
    }

    private fun navButton(glyph: String, desc: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = glyph
        contentDescription = desc
        setTextColor(OverlayChrome.TEXT)
        textSize = 15f
        gravity = Gravity.CENTER
        background = OverlayChrome.ripple(context, MirrorChrome.TOOL_BUTTON_DP / 2, circle = true)
        setOnClickListener { onClick() }
    }

    private fun toggleToolbar() {
        toolbarExpanded = MirrorToolbar.toggled(toolbarExpanded)
        applyToolbar(animate = true)
    }

    /**
     * 工具条显隐：浮层覆盖视频底部，展开/收起只改自身高度（120ms 渐变），
     * 不改 params.height —— 不再挤压视频高度，视觉更沉浸。
     */
    private fun applyToolbar(animate: Boolean) {
        toolbarToggle.text = MirrorToolbar.toggleLabel(toolbarExpanded)
        val shown = MirrorChrome.toolbarVisible(toolbarExpanded, passthrough)
        val targetH = MirrorToolbar.extraHeightPx(shown, toolbarOverlayPx)
        if (!animate) {
            toolbarAnimator?.cancel()
            setToolbarHeight(targetH)
            toolbarOverlay.visibility = if (shown) View.VISIBLE else View.GONE
            return
        }
        animateToolbarHeight(targetH, shown)
    }

    private fun animateToolbarHeight(target: Int, show: Boolean) {
        toolbarAnimator?.cancel()
        if (show) toolbarOverlay.visibility = View.VISIBLE
        val from = (toolbarOverlay.layoutParams?.height ?: 0).coerceAtLeast(0)
        toolbarAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = 120
            addUpdateListener { setToolbarHeight(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!show) toolbarOverlay.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun setToolbarHeight(h: Int) {
        val lp = toolbarOverlay.layoutParams ?: return
        lp.height = h
        toolbarOverlay.layoutParams = lp
    }

    private fun systemKeysEnabled(): Boolean = controlAvailable && !passthrough

    /** 系统键仅在可操控时点亮；仅观看 / 拖动模式置灰且点击无效。 */
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
        border.setStroke(1, if (controlAvailable && !passthrough) OverlayChrome.STATUS_CONNECTED else OverlayChrome.BORDER_IDLE)
    }

    private fun renderStatus() {
        statusDotDrawable.setColor(
            when (status) {
                MirrorChrome.Status.CONNECTING -> OverlayChrome.STATUS_CONNECTING
                MirrorChrome.Status.CONNECTED -> OverlayChrome.STATUS_CONNECTED
                MirrorChrome.Status.DISCONNECTED -> OverlayChrome.STATUS_DISCONNECTED
            },
        )
        when (status) {
            MirrorChrome.Status.CONNECTING -> {
                messageSpinner.visibility = View.VISIBLE
                messageText.text = "正在连接…"
                messageLayer.visibility = View.VISIBLE
                touchLayer.visibility = View.GONE
            }
            MirrorChrome.Status.DISCONNECTED -> {
                messageSpinner.visibility = View.GONE
                messageText.text = "连接断开，点击重连"
                messageLayer.visibility = View.VISIBLE
                touchLayer.visibility = View.GONE
            }
            MirrorChrome.Status.CONNECTED -> {
                messageLayer.visibility = View.GONE
                touchLayer.visibility = View.VISIBLE
            }
        }
    }

    private fun showBadge(text: String) {
        handler.removeCallbacks(fadeBadge)
        badge.animate().cancel()
        badge.alpha = 1f
        badge.text = text
        badge.visibility = View.VISIBLE
        handler.postDelayed(fadeBadge, MirrorChrome.BADGE_FADE_DELAY_MS)
    }

    private fun hideBadge() {
        handler.removeCallbacks(fadeBadge)
        badge.animate().cancel()
        badge.alpha = 1f
        badge.visibility = View.GONE
    }

    /** 仅连接态且处于非正常状态（仅观看/拖动模式）时浮出 badge，2s 后淡出；可操控态不再常驻。 */
    private fun syncBadge() {
        val show = status == MirrorChrome.Status.CONNECTED && MirrorChrome.badgeVisible(controlAvailable, passthrough)
        if (show) {
            val text = MirrorChrome.badgeText(controlAvailable, passthrough)
            if (badge.visibility != View.VISIBLE || badge.text.toString() != text) showBadge(text)
        } else hideBadge()
    }

    fun setControl(available: Boolean, w: Int = 0, h: Int = 0) {
        controlAvailable = available
        videoWidth = w; videoHeight = h
        touching = false; scrolling = false
        updateBorder()
        syncBadge()
        refreshToolbarState()
    }

    fun show() { wm.addView(root, params); OverlayChrome.enter(root); shown = true }
    fun showConnecting() { status = MirrorChrome.Status.CONNECTING; renderStatus(); setControl(false) }
    fun showDisconnected() { status = MirrorChrome.Status.DISCONNECTED; renderStatus(); setControl(false) }
    fun showVideo() { status = MirrorChrome.Status.CONNECTED; renderStatus(); syncBadge() }
    fun remove() {
        handler.removeCallbacksAndMessages(null)
        toolbarAnimator?.cancel()
        badge.animate().cancel()
        if (shown) { shown = false; wm.removeView(root) }
    }
}

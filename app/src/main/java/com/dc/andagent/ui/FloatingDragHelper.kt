package com.dc.andagent.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** 可拖动把手（标题栏）的最小可见宽度（dp）。位置夹取始终保留这么宽的一段在屏内，防止拖出屏幕后拖不回。 */
private const val HANDLE_MIN_VISIBLE_DP = 56

/**
 * Window coordinates are pixels; stored geometry is dp so it survives density changes.
 *
 * 位置夹取口径（moved / resized 共用）：grab = HANDLE_MIN_VISIBLE_DP 对应像素，即把手的最小可见宽度。
 * x 夹到 [-(width - grab), screenW - grab]、y 夹到 [0, screenH - grab]，使浮窗左右/上下都至少有
 * grab 宽度留在屏内。标题栏横贯浮窗顶部，这段可见条带就是可抓回浮窗的把手区域 —— 这是「拖到
 * 边缘后拖不回」不发生的保证。改尺寸后同样按此口径重夹，避免宽度变化把原位置挤出屏幕。
 */
internal data class FloatingBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun moved(dx: Int, dy: Int, screenW: Int, screenH: Int, grab: Int): FloatingBounds = copy(
        x = (x + dx).coerceIn(-(width - grab), screenW - grab),
        y = (y + dy).coerceIn(0, screenH - grab),
    )
    /** 改尺寸后按 moved 同口径重夹位置：保持贴边一侧（左边界时右缘）不跳变，
     *  且至少留 grab 宽度在屏内，避免缩窄后整个浮窗离屏。 */
    fun resized(dx: Int, dy: Int, minW: Int, minH: Int, maxW: Int, maxH: Int,
                screenW: Int, screenH: Int, grab: Int): FloatingBounds =
        copy(
            width = (width + dx).coerceIn(minW, maxW),
            height = (height + dy).coerceIn(minH, maxH),
        ).moved(0, 0, screenW, screenH, grab)
}

internal class FloatingDragHelper(
    private val context: Context,
    private val wm: WindowManager,
    private val root: View,
    private val params: WindowManager.LayoutParams,
    private val key: String,
    private val minWidthDp: Int = 160,
    private val minHeightDp: Int = 160,
    private val maxDp: Int = 1000,
    private val onChange: () -> Unit = {},
) {
    private val density get() = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).roundToInt()
    private val grabPx get() = px(HANDLE_MIN_VISIBLE_DP)
    private val prefs = context.getSharedPreferences("floating_geometry", Context.MODE_PRIVATE)
    private var savedSmall: FloatingBounds? = null
    private var enlarged = false
    private var initial = FloatingBounds(0, 0, 0, 0)
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    fun restore() {
        if (!prefs.contains("$key.w")) return
        val dm = context.resources.displayMetrics
        val b = FloatingBounds(px(prefs.getInt("$key.x", 0)), px(prefs.getInt("$key.y", 0)),
            px(prefs.getInt("$key.w", minWidthDp)), px(prefs.getInt("$key.h", minHeightDp)))
        apply(b.copy(width = b.width.coerceIn(px(minWidthDp), px(maxDp)),
            height = b.height.coerceIn(px(minHeightDp), px(maxDp)))
            .moved(0, 0, dm.widthPixels, dm.heightPixels, grabPx))
    }

    private fun bounds() = FloatingBounds(params.x, params.y, params.width, params.height)
    private fun apply(b: FloatingBounds) {
        params.x = b.x; params.y = b.y; params.width = b.width; params.height = b.height
        if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
        onChange()
    }
    fun persist() = save()
    private fun save() {
        val b = bounds()
        prefs.edit().putInt("$key.x", (b.x / density).roundToInt())
            .putInt("$key.y", (b.y / density).roundToInt())
            .putInt("$key.w", (b.width / density).roundToInt())
            .putInt("$key.h", (b.height / density).roundToInt()).apply()
    }

    /** 供外部触摸层（投屏拖动模式）复用的拖动：与 bind 的 ACTION_MOVE 共用 moved() 夹取口径。
     *  startX/startY 为 ACTION_DOWN 记录的窗口坐标，dx/dy 为相对按下点的位移；仅移动不持久化，
     *  由调用方在 ACTION_UP 时 persist()。 */
    fun dragBy(startX: Int, startY: Int, dx: Int, dy: Int) {
        val dm = context.resources.displayMetrics
        apply(FloatingBounds(startX, startY, params.width, params.height)
            .moved(dx, dy, dm.widthPixels, dm.heightPixels, grabPx))
    }

    /** onLongPress 供标题把手的额外手势（如投屏切换拖动模式）；缩放把手不传，避免误触。
     *  双击放大/恢复只挂在标题把手；缩放把手不响应双击，否则双击 ◢ 会把浮窗跳到全屏（几何损坏）。 */
    fun bindDrag(handle: View, onLongPress: (() -> Unit)? = null) = bind(handle, false, onLongPress, ::toggleLarge)
    fun bindResize(handle: View) = bind(handle, true, null, null)
    private fun bind(handle: View, resize: Boolean, onLongPress: (() -> Unit)?, onDoubleTap: (() -> Unit)?) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap?.invoke(); return onDoubleTap != null }
            override fun onLongPress(e: MotionEvent) { onLongPress?.invoke() }
        })
        handle.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; initial = bounds(); moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (dx != 0 || dy != 0) moved = true
                    val dm = context.resources.displayMetrics
                    apply(if (resize)
                        initial.resized(dx, dy, px(minWidthDp), px(minHeightDp), px(maxDp), px(maxDp),
                            dm.widthPixels, dm.heightPixels, grabPx)
                    else
                        initial.moved(dx, dy, dm.widthPixels, dm.heightPixels, grabPx))
                    true
                }
                MotionEvent.ACTION_UP -> { if (moved) save() else handle.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
    }
    private fun toggleLarge() {
        val dm = context.resources.displayMetrics
        if (!enlarged) {
            savedSmall = bounds()
            // 全屏目标 = 当前屏幕像素尺寸，不受 maxDp 钳制（大屏/平板 1000dp 覆盖不满会被截断全屏）。
            apply(FloatingBounds(0, 0, dm.widthPixels, dm.heightPixels))
        } else savedSmall?.let(::apply)
        enlarged = !enlarged
        if (!enlarged) save()
    }
}

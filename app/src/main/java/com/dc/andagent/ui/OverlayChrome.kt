package com.dc.andagent.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 浮窗统一设计 token，与 web 前端 styles.css 的 CSS 变量对齐：
 * --card-bg #0b0e14 / --border rgba(255,255,255,.09) / --accent #7f9bff / --radius 16px。
 * 所有原生浮窗（相机、浏览器悬浮层、ADB 投屏、Live 结果）共用同一外壳。
 */
object OverlayChrome {
    val BG: Int = 0xFF0B0E14.toInt()        // --card-bg
    val HEADER_BG: Int = 0xFF121721.toInt() // 标题栏：card-bg 加深一档
    val BORDER: Int = 0x17FFFFFF.toInt()    // --border 9% 白
    val TEXT: Int = 0xFFE8EDF5.toInt()
    val TEXT_DIM: Int = 0xFFA7B7CA.toInt()  // 与桌面 widget 状态文字同源
    val ACCENT: Int = 0xFF7F9BFF.toInt()    // --accent
    const val RADIUS_DP = 16
    const val HEADER_DP = 40

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    /** 圆角裁剪 + 阴影轮廓（配合 view.elevation 使用）。 */
    fun applyRounded(view: View, radiusDp: Int = RADIUS_DP) {
        view.clipToOutline = true
        val r = dp(view.context, radiusDp).toFloat()
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) { outline.setRoundRect(0, 0, v.width, v.height, r) }
        }
    }

    /** 恢复系统默认轮廓（退出圆角模式）。 */
    fun clearRounded(view: View) {
        view.clipToOutline = false
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /** 浮窗底：card-bg 圆角 + 1dp 描边。 */
    fun background(view: View, strokeColor: Int = BORDER, radiusDp: Int = RADIUS_DP) {
        view.background = GradientDrawable().apply {
            setColor(BG)
            cornerRadius = dp(view.context, radiusDp).toFloat()
            setStroke(dp(view.context, 1), strokeColor)
        }
    }

    /** 入场动画：scale 0.92→1 + fade，180ms。 */
    fun enter(view: View) {
        view.scaleX = 0.92f; view.scaleY = 0.92f; view.alpha = 0f
        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** 统一标题栏：标题（可作拖拽把手）+ ✕ 关闭。高度由调用方用 HEADER_DP 控制。 */
    class Header(context: Context, titleText: String, onClose: () -> Unit) : LinearLayout(context) {
        val title: TextView = TextView(context).apply {
            text = titleText
            setTextColor(TEXT)
            textSize = 14f
            isSingleLine = true
            // 子项高度撑满标题栏时 LinearLayout 的 CENTER_VERTICAL 不生效，
            // 文字/按钮要各自设置 gravity 才能垂直居中。
            gravity = Gravity.CENTER_VERTICAL
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(HEADER_BG)
            setPadding(dp(context, 12), 0, dp(context, 4), 0)
            addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(context).apply {
                text = "✕"
                setTextColor(TEXT_DIM)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
                setOnClickListener { onClose() }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }
}

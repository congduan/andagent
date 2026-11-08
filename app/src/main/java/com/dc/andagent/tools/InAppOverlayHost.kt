package com.dc.andagent.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 无悬浮窗权限时的应用内伪浮窗：把同款 UI（标题栏 + 内容区）作为普通 View
 * 叠加在主 Activity 的 decorView 上。随 Activity 生命周期销毁，只在本 app
 * 内可见——权限授予后由真实 CameraOverlayService 接管。
 *
 * 单例管理：show() 先移除旧的（避免叠加），dismiss() 由 hide 工具、✕ 按钮或
 * Activity 销毁触发，并通过 onDismiss 回调通知宿主清理（如停止相机服务）。
 */
object InAppOverlayHost {

    private val main = Handler(Looper.getMainLooper())
    private var current: View? = null
    private var title: TextView? = null
    private var contentHook: ((FrameLayout) -> Unit)? = null
    private var dismissHook: (() -> Unit)? = null

    /** 内容挂载回调：宿主（如相机预览）把渲染 View 塞进 content 区域。 */
    fun attachContent(hook: (FrameLayout) -> Unit) { contentHook = hook }

    @SuppressLint("InflateParams")
    fun show(
        activityContext: Context,
        titleText: String,
        widthDp: Int,
        heightDp: Int,
        xDp: Int,
        yDp: Int,
        contentHook: (FrameLayout) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        val activity = activityContext as? android.app.Activity ?: return false
        if (activity.isFinishing || activity.window == null) return false
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        main.post {
            dismiss()
            dismissHook = onDismiss
            val frame = FrameLayout(activity).apply {
                com.dc.andagent.ui.OverlayChrome.applyRounded(this)
                com.dc.andagent.ui.OverlayChrome.background(this)
                elevation = dp(8).toFloat()
            }
            val content = FrameLayout(activity)
            val headerRow = com.dc.andagent.ui.OverlayChrome.Header(activity, titleText) { dismiss() }
            val header = headerRow.title
            frame.addView(content, FrameLayout.LayoutParams(-1, -1))
            frame.addView(headerRow, FrameLayout.LayoutParams(-1, com.dc.andagent.ui.OverlayChrome.dp(activity, com.dc.andagent.ui.OverlayChrome.HEADER_DP), Gravity.TOP))
            // 应用内伪浮窗位置：decorView 内绝对定位（近似系统浮窗 x/y）。
            frame.translationX = dp(xDp).toFloat()
            frame.translationY = dp(yDp).toFloat()
            val lp = ViewGroup.LayoutParams(dp(widthDp), dp(heightDp))
            activity.window.decorView.let { root ->
                (root as? ViewGroup)?.addView(frame, lp) ?: return@post
            }
            com.dc.andagent.ui.OverlayChrome.enter(frame)
            contentHook(content)
            current = frame
            title = header
        }
        return true
    }

    fun setTitle(text: String) { main.post { title?.text = text } }

    /** 移除浮窗视图并触发 onDismiss 回调（宿主清理，如停止相机服务）。 */
    fun dismiss() { main.post {
        val hook = dismissHook
        current?.let { v -> (v.parent as? ViewGroup)?.removeView(v) }
        current = null
        title = null
        dismissHook = null
        hook?.invoke()
    } }

    fun isShowing(): Boolean = current != null
}

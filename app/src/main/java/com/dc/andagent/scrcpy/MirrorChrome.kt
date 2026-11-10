package com.dc.andagent.scrcpy

/**
 * R137 投屏浮窗 UI 决策的纯逻辑（不含任何 Android 框架调用，可直接在 JVM 单测覆盖）：
 * 状态枚举、尺寸常量、工具条显隐护栏、浮层高度上限、badge 文案与显隐。
 * 视觉层 [FloatingMirrorWindow] 只消费这里的结论，颜色值仍统一取自 ui.OverlayChrome token。
 */
object MirrorChrome {
    enum class Status { CONNECTING, CONNECTED, DISCONNECTED }

    // 尺寸 token（浮窗专属，区别于 OverlayChrome 的通用 RADIUS_DP / HEADER_DP）
    const val MIRROR_HEADER_DP = 32
    const val HEADER_BUTTON_DP = 40
    const val TOOL_BUTTON_DP = 32
    const val TOOLBAR_RADIUS_DP = 12
    const val BADGE_RADIUS_DP = 8
    const val BADGE_FADE_DELAY_MS = 2000L
    /** 工具条覆盖视频底部不可遮挡关键区域超过 44dp（防失败条款）。 */
    const val MAX_TOOLBAR_OVERLAY_DP = 44

    /**
     * 工具条浮层只在「展开且非拖动模式」时可见。
     * passthrough（拖动模式）下强制隐藏，避免与手势拖动误触（防失败条款）。
     */
    fun toolbarVisible(expanded: Boolean, passthrough: Boolean): Boolean = expanded && !passthrough

    /** 浮层目标高度上限钳制，始终 ≤ [MAX_TOOLBAR_OVERLAY_DP]（防失败条款）。 */
    fun clampedToolbarHeightDp(requestedDp: Int): Int = requestedDp.coerceIn(0, MAX_TOOLBAR_OVERLAY_DP)

    /** badge 文案：仅观看优先于拖动模式，正常可操控态为「可操控」。 */
    fun badgeText(controlAvailable: Boolean, passthrough: Boolean): String = when {
        !controlAvailable -> "仅观看"
        passthrough -> "拖动模式"
        else -> "可操控"
    }

    /** 可操控且非拖动模式时 badge 不再常驻，仅观看 / 拖动模式才浮出。 */
    fun badgeVisible(controlAvailable: Boolean, passthrough: Boolean): Boolean = !(controlAvailable && !passthrough)
}

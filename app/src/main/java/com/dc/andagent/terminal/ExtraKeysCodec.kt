package com.dc.andagent.terminal

/**
 * R83②：附加键行的纯 JVM 逻辑（按键→写入 payload、CTRL 粘滞状态机、字号钳制）。
 * 不依赖 Android，供 JVM 单测覆盖。
 */
enum class ExtraKeyAction { ESC, TAB, CTRL, UP, DOWN, LEFT, RIGHT }

class ExtraKeysCodec {

    /** CTRL 是否处于粘滞（等待下一次 readControlKey 消费）状态。 */
    private var controlLatched = false

    /**
     * 按下 CTRL（附加键行按钮或音量下键）：切换粘滞；再按一次即「再按关」。
     * 返回切换后的状态，供 UI 高亮按钮。
     */
    fun onControlKey(): Boolean {
        controlLatched = !controlLatched
        return controlLatched
    }

    /**
     * TerminalViewClient.readControlKey() 的实现：返回当前粘滞状态并消费（一次性）。
     * TerminalView.java:718（onKeyDown）与 :786（inputCodePoint）都会调用，
     * 由 `||` 短路保证同一次按键只消费一次（软/硬件键盘 Ctrl 组合键与粘滞 Ctrl 语义一致）。
     */
    fun readControlKey(): Boolean {
        val value = controlLatched
        controlLatched = false
        return value
    }

    fun isControlLatched(): Boolean = controlLatched

    companion object {
        const val MIN_FONT_DP = 5
        const val MAX_FONT_DP = 30
        const val DEFAULT_FONT_DP = 13

        /**
         * ESC=codepoint 27，TAB=codepoint 9；CTRL 无 payload（切换粘滞）。
         * 箭头键默认发 CSI（ESC [ A/B/C/D）；应用光标模式（DECCKM，vim 等 TUI）下按
         * vendored TerminalView.java:843 / KeyHandler.java:163-170 改发 SS3（ESC O A/B/C/D），
         * 与真实方向键一致。ESC/TAB/CTRL 不受该模式影响。
         * ESC/TAB 的单字符经 TerminalOutput.write(String) 按 UTF-8 编码为单字节，
         * 与 writeCodePoint(false, 27|9) 等价（TerminalSession.java:182 实证，A6）。
         */
        fun payload(action: ExtraKeyAction, applicationCursorKeys: Boolean = false): String? = when (action) {
            ExtraKeyAction.ESC -> "\u001B"
            ExtraKeyAction.TAB -> "\u0009"
            ExtraKeyAction.UP -> if (applicationCursorKeys) "\u001BOA" else "\u001B[A"
            ExtraKeyAction.DOWN -> if (applicationCursorKeys) "\u001BOB" else "\u001B[B"
            ExtraKeyAction.RIGHT -> if (applicationCursorKeys) "\u001BOC" else "\u001B[C"
            ExtraKeyAction.LEFT -> if (applicationCursorKeys) "\u001BOD" else "\u001B[D"
            ExtraKeyAction.CTRL -> null
        }

        /** 字号钳制到 5..30 dp。 */
        fun clampFontSize(dp: Int): Int = dp.coerceIn(MIN_FONT_DP, MAX_FONT_DP)
    }
}

package com.dc.andagent.terminal

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.dc.andagent.R

/**
 * R83②：附加键行视图（代码构建 8 个按钮，不在 layout XML 里塞 Button）。
 * 点击回调 onKey(action)；CTRL 粘滞状态由 setControlLatched 高亮。
 */
class ExtraKeysView(context: Context) : LinearLayout(context) {

    /** 回调接口：按键→action，由 Activity 把 payload 写进当前活跃 session。 */
    fun interface OnKeyListener {
        fun onKey(action: ExtraKeyAction)
    }

    private var listener: OnKeyListener = OnKeyListener {}

    fun setOnKeyListener(listener: OnKeyListener) {
        this.listener = listener
    }

    private val ctrlButton: Button

    private val normalBg = Color.parseColor("#1E222A")
    private val latchedBg = Color.parseColor("#2962FF")
    private val textColor = Color.parseColor("#E6E8EC")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#13161C"))

        addKey("ESC", ExtraKeyAction.ESC, R.string.terminal_key_esc)
        addKey("TAB", ExtraKeyAction.TAB, R.string.terminal_key_tab)
        ctrlButton = addKey("CTRL", ExtraKeyAction.CTRL, R.string.terminal_key_ctrl)
        addKey("↑", ExtraKeyAction.UP, R.string.terminal_key_up)
        addKey("↓", ExtraKeyAction.DOWN, R.string.terminal_key_down)
        addKey("←", ExtraKeyAction.LEFT, R.string.terminal_key_left)
        addKey("→", ExtraKeyAction.RIGHT, R.string.terminal_key_right)
        setControlLatched(false)
    }

    private fun addKey(label: String, action: ExtraKeyAction, descRes: Int): Button {
        val button = Button(context).apply {
            text = label
            contentDescription = context.getString(descRes)
            isAllCaps = false
            textSize = 12f
            minimumWidth = 0
            minimumHeight = 0
            setPadding(2, 8, 2, 8)
            setTextColor(textColor)
            setBackgroundColor(normalBg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { listener.onKey(action) }
        }
        addView(button)
        return button
    }

    /** CTRL 粘滞状态变化时刷新按钮高亮。 */
    fun setControlLatched(latched: Boolean) {
        ctrlButton.setBackgroundColor(if (latched) latchedBg else normalBg)
        ctrlButton.isSelected = latched
    }
}

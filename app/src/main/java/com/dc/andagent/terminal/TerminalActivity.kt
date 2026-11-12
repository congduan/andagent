package com.dc.andagent.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dc.andagent.R
import com.dc.andagent.shell.AndagentTermuxPaths
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * R83②：独立全屏终端页（真 pty）第二轮 —— 附加键行、多 tab、音量键修饰键、字号持久化。
 *
 * 每个 tab 持有自己的 TerminalSession + TerminalView + TerminalClient，全部叠放在
 * terminalContainer（FrameLayout）内：切 tab = bringToFront()，其余视图保持 VISIBLE 且
 * 尺寸不变，故并行会话的 pty winsize 不丢、进程照常运行。
 *
 * 生命周期沿用 R83① 最简口径：onPause 只落盘不杀会话；finish() → onDestroy 对每个
 * pid>0 的会话 finishIfRunning()（SIGKILL），保留 a2c1efd 的 pid>0 铁律（防 kill(0)
 * 误杀应用进程组）。scrollback 不恢复、pty 进程无法跨进程死亡存活，如实标注。
 */
class TerminalActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var terminalContainer: FrameLayout
    private lateinit var tabContainer: LinearLayout
    private lateinit var extraKeysSlot: FrameLayout
    private lateinit var extraKeysView: ExtraKeysView

    private lateinit var prefs: TerminalPrefs
    private val tabState = TerminalTabState()
    private val codec = ExtraKeysCodec() // 全 Activity 共享的 CTRL 粘滞状态

    /** tabId → 该 tab 的会话 / 视图 / client。 */
    private data class TabBinding(
        val id: Int,
        val session: TerminalSession,
        val view: TerminalView,
        val client: TerminalClient,
    )

    private val bindings = LinkedHashMap<Int, TabBinding>()

    private var fontSizeDp: Int = ExtraKeysCodec.DEFAULT_FONT_DP
    private lateinit var defaultTitle: String
    private lateinit var shellPath: String
    private lateinit var homePath: String
    private lateinit var envArray: Array<String>

    private val activeChipBg = Color.parseColor("#2962FF")
    private val normalChipBg = Color.parseColor("#1E222A")
    private val chipTextColor = Color.parseColor("#E6E8EC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        statusView = findViewById(R.id.terminal_status)
        terminalContainer = findViewById(R.id.terminal_container)
        tabContainer = findViewById(R.id.terminal_tab_container)
        extraKeysSlot = findViewById(R.id.terminal_extra_keys_slot)

        defaultTitle = getString(R.string.terminal_title)
        prefs = TerminalPrefs.from(this)
        fontSizeDp = prefs.readFontSizeDp()

        findViewById<Button>(R.id.terminal_font_minus).setOnClickListener { changeFontSize(-FONT_STEP) }
        findViewById<Button>(R.id.terminal_font_plus).setOnClickListener { changeFontSize(FONT_STEP) }
        findViewById<Button>(R.id.terminal_close).setOnClickListener { finish() }

        extraKeysView = ExtraKeysView(this)
        extraKeysSlot.addView(
            extraKeysView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        extraKeysView.setOnKeyListener { action -> onExtraKey(action) }

        val paths = AndagentTermuxPaths(this)
        if (!paths.bash.canExecute()) {
            statusView.text = getString(R.string.terminal_not_ready)
            return
        }
        paths.home.mkdirs()
        shellPath = paths.bash.path
        homePath = paths.home.path
        envArray = TerminalSessionConfig.buildEnvironment(paths.environment)

        // 按持久化的数量重建 tab（只恢复数量，不恢复 scrollback / pty 进程）。
        val ids = tabState.initialize(prefs.readTabCount())
        for (id in ids) createTab(id)
        applyFontSize()
        prefs.writeTabCount(tabState.size)
        refreshTabBar()
        activateCurrentTab()
    }

    // ---------------- tab 生命周期 ----------------

    private fun createTab(id: Int) {
        val view = TerminalView(this, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#0B0D11"))
        }
        val client = TerminalClient(view)
        view.setTerminalViewClient(client)
        val session = TerminalSession(
            shellPath,
            homePath,
            // termux.c 的 execvp(cmd, argv) 直接消费该数组，argv[0] 必须是程序名。
            arrayOf(shellPath, "-i", "--noprofile", "--norc"),
            envArray,
            TRANSCRIPT_ROWS,
            client,
        )
        view.attachSession(session)
        terminalContainer.addView(view)
        bindings[id] = TabBinding(id, session, view, client)
    }

    private fun addTab() {
        val newId = tabState.add() ?: return // 到达上限 5，静默忽略
        createTab(newId)
        applyFontSize()
        prefs.writeTabCount(tabState.size)
        activateCurrentTab()
    }

    private fun selectTab(index: Int) {
        if (!tabState.select(index)) return
        activateCurrentTab()
    }

    private fun closeTab(index: Int) {
        when (val result = tabState.close(index)) {
            is TerminalTabState.CloseResult.LastTab -> {
                // 末位 tab：不改状态，直接 finish()，会话由 onDestroy 统一回收。
                prefs.writeTabCount(tabState.size)
                finish()
            }
            is TerminalTabState.CloseResult.Removed -> {
                bindings.remove(result.closedId)?.let { binding ->
                    if (binding.session.getPid() > 0) binding.session.finishIfRunning()
                    terminalContainer.removeView(binding.view)
                    binding.view.removeCallbacks(showKeyboardRunnable)
                }
                prefs.writeTabCount(tabState.size)
                activateCurrentTab()
            }
            is TerminalTabState.CloseResult.Invalid -> Unit
        }
    }

    /** 把活跃 tab 的视图提到最前、聚焦、刷新 tab 条与状态行、唤起键盘。 */
    private fun activateCurrentTab() {
        val binding = tabState.activeTabId?.let { bindings[it] } ?: return
        binding.view.bringToFront()
        binding.view.requestFocus()
        refreshTabBar()
        updateStatus()
        binding.view.post(showKeyboardRunnable)
    }

    /** tab 条：每个 tab 一个 chip（短按切换、长按关闭）+ "+"，整体动态重建。 */
    private fun refreshTabBar() {
        tabContainer.removeAllViews()
        val chipWidth = (44 * resources.displayMetrics.density).toInt()
        for ((index, tab) in tabState.tabs.withIndex()) {
            val tabId = tab.id
            val active = index == tabState.activeIndex
            val chip = Button(this).apply {
                text = (index + 1).toString()
                contentDescription = getString(R.string.terminal_tab_label, index + 1) + ". " +
                    getString(R.string.terminal_tab_hint)
                isAllCaps = false
                minimumWidth = 0
                minimumHeight = 0
                setPadding(4, 6, 4, 6)
                setTextColor(chipTextColor)
                setBackgroundColor(if (active) activeChipBg else normalChipBg)
                isSelected = active
                setOnClickListener {
                    val idx = tabState.tabs.indexOfFirst { it.id == tabId }
                    if (idx >= 0) selectTab(idx)
                }
                setOnLongClickListener {
                    val idx = tabState.tabs.indexOfFirst { it.id == tabId }
                    if (idx >= 0) closeTab(idx)
                    true
                }
            }
            tabContainer.addView(chip, LinearLayout.LayoutParams(chipWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val addButton = Button(this).apply {
            text = getString(R.string.terminal_new_tab_label)
            contentDescription = getString(R.string.terminal_new_tab)
            isAllCaps = false
            isEnabled = !tabState.isAtCap
            setTextColor(chipTextColor)
            setBackgroundColor(normalChipBg)
            setOnClickListener { addTab() }
        }
        tabContainer.addView(addButton, LinearLayout.LayoutParams(chipWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ---------------- 活跃会话 / 状态 ----------------

    private fun activeSession(): TerminalSession? = tabState.activeTabId?.let { bindings[it] }?.session
    private fun activeView(): TerminalView? = tabState.activeTabId?.let { bindings[it] }?.view
    private fun hasActiveSession(): Boolean = activeSession() != null

    private fun updateStatus() {
        statusView.text = TerminalSessionConfig.sessionTitle(activeSession()?.title, defaultTitle)
    }

    /**
     * CTRL 粘滞被 TerminalViewClient.readControlKey() 消费后回调：只熄灭附加键行 CTRL 高亮，
     * 不动 codec 状态（codec 已在 readControlKey 内自行清位），避免用户误判「再按一次是取消」。
     */
    private fun onControlLatchConsumed() {
        extraKeysView.setControlLatched(false)
    }

    /** 附加键行 / 音量上键回调：payload 写进当前活跃 session。 */
    private fun onExtraKey(action: ExtraKeyAction) {
        if (action == ExtraKeyAction.CTRL) {
            extraKeysView.setControlLatched(codec.onControlKey())
            return
        }
        // 方向键按应用光标模式在 CSI/SS3 间切换，与真实方向键（TerminalView.java:843）一致。
        val applicationCursorKeys = activeSession()?.emulator?.isCursorKeysApplicationMode() ?: false
        val payload = ExtraKeysCodec.payload(action, applicationCursorKeys) ?: return
        activeSession()?.write(payload)
    }

    // ---------------- 字号 ----------------

    private fun applyFontSize() {
        val px = (fontSizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(MIN_FONT_PX)
        for (binding in bindings.values) binding.view.setTextSize(px)
    }

    private fun changeFontSize(delta: Int) {
        val next = ExtraKeysCodec.clampFontSize(fontSizeDp + delta)
        if (next == fontSizeDp) return
        fontSizeDp = next
        applyFontSize()
        prefs.writeFontSizeDp(fontSizeDp)
    }

    /** TerminalViewClient.onScale：A4 证伪后的自持状态实现，pinch 结果同样真实落盘。 */
    private fun handleScale(scale: Float): Float = when {
        scale >= SCALE_UP -> {
            changeFontSize(PINCH_STEP)
            1f // 重置累计缩放因子，下一次 pinch 事件重新累积
        }
        scale <= SCALE_DOWN -> {
            changeFontSize(-PINCH_STEP)
            1f
        }
        else -> scale
    }

    // ---------------- 键盘 / 音量键 ----------------

    /** 建议 1（Codex review）：具名 Runnable，销毁时可撤销。 */
    private val showKeyboardRunnable = Runnable { showKeyboard() }

    private fun showKeyboard() {
        val view = activeView() ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * 音量下键 = CTRL（粘滞，同 CTRL 按钮）；音量上键 = ESC（直接写入 payload）。
     * 会话活跃时返回 true 吞掉音量事件；无会话（bash 未就绪）时不拦截，交回系统调音量。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (hasActiveSession()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.repeatCount == 0) {
                        extraKeysView.setControlLatched(codec.onControlKey())
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.repeatCount == 0) onExtraKey(ExtraKeyAction.ESC)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hasActiveSession() &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------------- 生命周期 ----------------

    override fun onPause() {
        prefs.writeFontSizeDp(fontSizeDp)
        prefs.writeTabCount(tabState.size)
        super.onPause()
    }

    override fun onDestroy() {
        // 保留 pid>0 铁律：initializeEmulator 之前 mShellPid 为 0，finishIfRunning()
        // 会 kill(0, SIGKILL) 误杀应用进程组。回收所有 tab 的会话。
        for (binding in bindings.values) {
            if (binding.session.getPid() > 0) binding.session.finishIfRunning()
            binding.view.removeCallbacks(showKeyboardRunnable)
        }
        bindings.clear()
        super.onDestroy()
    }

    private fun clipboard(): ClipboardManager? =
        getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * 每个 tab 一个 client 实例，绑定各自的 view（onTextChanged → view.onScreenUpdated）。
     * 故意不让 Activity 自身实现：TerminalViewClient.onKeyUp(Int,KeyEvent) 会与
     * Activity 的同名方法撞车并改变系统按键分发（R83① 既有结论）。
     * CTRL 粘滞由共享的 codec 提供，readControlKey() 返回并消费。
     */
    private inner class TerminalClient(private val view: TerminalView) :
        TerminalSessionClient, TerminalViewClient {

        // ---------------- TerminalSessionClient ----------------

        override fun onTextChanged(changedSession: TerminalSession) {
            view.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            if (changedSession === activeSession()) updateStatus()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession === activeSession()) {
                statusView.text = TerminalSessionConfig.exitMessage(finishedSession.exitStatus)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession) {
            val clip = clipboard()?.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this@TerminalActivity)?.toString() ?: return
            session.write(text)
        }

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {
            view.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun getTerminalCursorStyle(): Int? = null

        // ---------------- TerminalViewClient ----------------

        override fun onScale(scale: Float): Float = handleScale(scale)

        override fun onSingleTapUp(e: MotionEvent) {
            view.requestFocus()
            showKeyboard()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean {
            val consumed = codec.readControlKey()
            if (consumed) onControlLatchConsumed() // 只刷 CTRL 按钮 UI，不动 codec 状态
            return consumed
        }
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
        override fun onEmulatorSet() {}

        // ---------------- 两个接口共用的日志出口 ----------------

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, e.message ?: tag, e) }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 2000
        const val FONT_STEP = 2
        const val PINCH_STEP = 1
        const val MIN_FONT_PX = 8
        const val SCALE_UP = 1.15f
        const val SCALE_DOWN = 0.85f
    }
}

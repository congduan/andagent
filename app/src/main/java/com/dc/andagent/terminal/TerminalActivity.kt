package com.dc.andagent.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import com.dc.andagent.R
import com.dc.andagent.shell.AndagentTermuxPaths
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * R83①: 独立全屏终端页（真 pty）。
 *
 * 与 R27 的对话流 TerminalCard 不同，这里使用 vendored 的 termux
 * terminal-emulator：JNI.createSubprocess() fork 出挂到 /dev/ptmx 的 bash，
 * 能跑 vim/htop 等 TUI。会话是独立进程，不与 shell_exec 的长驻 bash 管道
 * 会话共享 cd/env 状态（状态共享属 R83② 评估项，本轮不承诺）。
 *
 * 生命周期取最简口径：onPause 不杀会话；finish() → onDestroy 调
 * finishIfRunning() 发 SIGKILL，pty waiter 的 JNI.waitFor() 回收进程并
 * close(fd)。不做后台保活（那是 R124 的 FGS 链）。
 */
class TerminalActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private lateinit var statusView: TextView
    private var session: TerminalSession? = null
    private val client = TerminalClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        statusView = findViewById(R.id.terminal_status)
        terminalView = findViewById(R.id.terminal_view)
        findViewById<Button>(R.id.terminal_close).setOnClickListener { finish() }

        val defaultTitle = getString(R.string.terminal_title)
        statusView.text = defaultTitle
        // TerminalRenderer 把该值当像素用，按屏幕密度换算成 ~13dp。
        terminalView.setTextSize((13 * resources.displayMetrics.density).toInt().coerceAtLeast(12))
        terminalView.setTerminalViewClient(client)

        val paths = AndagentTermuxPaths(this)
        if (!paths.bash.canExecute()) {
            statusView.text = getString(R.string.terminal_not_ready)
            return
        }
        paths.home.mkdirs()

        val shellPath = paths.bash.path
        val newSession = TerminalSession(
            shellPath,
            paths.home.path,
            // termux.c 的 execvp(cmd, argv) 直接消费该数组，argv[0] 必须是程序名。
            // -i 显式交互；--noprofile/--norc 避开引用官方 com.termux 绝对路径的 rc。
            arrayOf(shellPath, "-i", "--noprofile", "--norc"),
            TerminalSessionConfig.buildEnvironment(paths.environment),
            TRANSCRIPT_ROWS,
            client,
        )
        session = newSession
        terminalView.attachSession(newSession)
        terminalView.requestFocus()
        terminalView.post(showKeyboardRunnable)
    }

    /** 建议 1（Codex review）：以具名 Runnable 挂到视图队列，销毁时可 removeCallbacks 撤销。 */
    private val showKeyboardRunnable = Runnable { showKeyboard() }

    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun clipboard(): ClipboardManager? =
        getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun onDestroy() {
        // 必修项（Codex review）：initializeEmulator 由视图首次 onSizeChanged 触发，
        // 之前 mShellPid 保持字段默认值 0，而 isRunning() 判 `!= -1`——
        // 此时 finishIfRunning() 会 kill(0, SIGKILL) 杀掉 app 自己的进程组。
        // 只有真实 fork 出的 pid（>0）才允许回收。
        session?.let { if (it.getPid() > 0) it.finishIfRunning() }
        session = null
        super.onDestroy()
        // 建议项：destroy 后视图仍可能在队列里排着 showKeyboard，执行前检查有效性。
        terminalView.removeCallbacks(showKeyboardRunnable)
    }

    /**
     * 一个 client 同时实现两个接口：两者的 log* 方法签名完全一致，其余方法互不
     * 冲突。故意不让 Activity 自己实现——TerminalViewClient.onKeyUp(Int,KeyEvent)
     * 会与 Activity 的同名方法撞车并改变系统按键分发。
     */
    private inner class TerminalClient : TerminalSessionClient, TerminalViewClient {

        // ---------------- TerminalSessionClient ----------------

        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            statusView.text = TerminalSessionConfig.sessionTitle(
                changedSession.title, getString(R.string.terminal_title)
            )
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            statusView.text = TerminalSessionConfig.exitMessage(finishedSession.exitStatus)
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
            terminalView.onScreenUpdated()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun getTerminalCursorStyle(): Int? = null

        // ---------------- TerminalViewClient ----------------

        override fun onScale(scale: Float): Float = scale

        override fun onSingleTapUp(e: MotionEvent) {
            terminalView.requestFocus()
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
        override fun readControlKey(): Boolean = false
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
    }
}

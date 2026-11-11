package com.dc.andagent.terminal

/**
 * R83①: TerminalActivity 的纯逻辑（不依赖 Android，供 JVM 单测覆盖）。
 */
object TerminalSessionConfig {

    /** JNI createSubprocess() 内部 clearenv() 后只 putenv 传入的数组，故 TERM
     * 必须由这里补上，否则 vim/htop 等 TUI 会因未知终端类型直接退出。 */
    const val TERM = "xterm-256color"

    /**
     * 把 AndagentTermuxPaths.environment 转成 JNI 需要的 "KEY=value" 数组。
     * 保留全部原有键（尤其 PREFIX/PATH，与 shell_exec 同一 $PREFIX），并强制
     * 覆盖 TERM，保证 TUI 可用。
     */
    fun buildEnvironment(base: Map<String, String>): Array<String> =
        (base + ("TERM" to TERM))
            .map { (key, value) -> "$key=$value" }
            .toTypedArray()

    /** 会话退出后状态行文案。exitStatus 为 WEXITSTATUS 或 -signal。 */
    fun exitMessage(exitStatus: Int): String = "会话已退出 (exit=$exitStatus)"

    /** 状态行标题：OSC 标题为空时回退到应用标题。 */
    fun sessionTitle(title: String?, fallback: String): String =
        title?.takeIf { it.isNotBlank() } ?: fallback
}

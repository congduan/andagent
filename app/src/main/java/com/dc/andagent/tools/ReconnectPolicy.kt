package com.dc.andagent.tools

/**
 * R124 d: 断线重连的指数退避策略。纯 JVM、无 Android 依赖，便于单测。
 *
 * 序列 1s / 4s / 16s，之后封顶 16s；最多 [MAX_ATTEMPTS] 次。
 * 本轮收口口径：SSH 不做后台静默重连（凭据解密触发面扩大，R91.5 铁律优先），
 * ADB 走 client() 按需自动重连；本对象作为「断线自愈」策略单独落地并单测覆盖，
 * 供后续把退避循环接入具体重连器时使用（当前生产路径未接入，见轮次战报）。
 */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 5
    const val MAX_DELAY_MS = 16_000L
    private val DELAYS = longArrayOf(1_000L, 4_000L, MAX_DELAY_MS)

    /** attempt 从 0 开始：0→1s，1→4s，≥2→16s 封顶；负数按 0 处理。 */
    fun delayMs(attempt: Int): Long = DELAYS[attempt.coerceIn(0, DELAYS.lastIndex)]
}

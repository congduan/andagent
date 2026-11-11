package com.dc.andagent.terminal

import android.content.Context
import android.content.SharedPreferences

/**
 * R83②：终端页独立偏好（SharedPreferences 文件 terminal_prefs）。
 * 刻意不碰 KvStore / andagent_settings 构造链，只存字号 dp 与 tab 数量。
 */
class TerminalPrefs(private val prefs: SharedPreferences) {

    companion object {
        const val PREFS_NAME = "terminal_prefs"
        const val DEFAULT_FONT_DP = ExtraKeysCodec.DEFAULT_FONT_DP
        const val DEFAULT_TAB_COUNT = 1

        private const val KEY_FONT_SIZE_DP = "font_size_dp"
        private const val KEY_TAB_COUNT = "tab_count"

        fun from(context: Context): TerminalPrefs =
            TerminalPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** 读回时钳制到 5..30 dp，防损坏值。 */
    fun readFontSizeDp(): Int =
        ExtraKeysCodec.clampFontSize(prefs.getInt(KEY_FONT_SIZE_DP, DEFAULT_FONT_DP))

    fun writeFontSizeDp(dp: Int) {
        prefs.edit().putInt(KEY_FONT_SIZE_DP, ExtraKeysCodec.clampFontSize(dp)).apply()
    }

    /** 读回时钳制到 1..MAX_TABS，防损坏值。 */
    fun readTabCount(): Int =
        prefs.getInt(KEY_TAB_COUNT, DEFAULT_TAB_COUNT).coerceIn(1, TerminalTabState.MAX_TABS)

    fun writeTabCount(count: Int) {
        prefs.edit().putInt(KEY_TAB_COUNT, count.coerceIn(1, TerminalTabState.MAX_TABS)).apply()
    }
}

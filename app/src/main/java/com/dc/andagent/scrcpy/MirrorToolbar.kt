package com.dc.andagent.scrcpy

/** 浮窗工具条的可测纯逻辑：系统键映射、成对注入序列与画质档位。 */
object MirrorToolbar {
    // Android KeyEvent 标准值
    const val KEY_BACK = 4
    const val KEY_HOME = 3
    const val KEY_APP_SWITCH = 187

    // 画质档位（scrcpy maxSize 最大边长）
    const val QUALITY_SD = 800
    const val QUALITY_HD = 1200

    /** 系统键必须 DOWN/UP 成对注入，顺序不可颠倒。 */
    fun systemKey(keycode: Int): List<ByteArray> = listOf(
        ScrcpyControl.keycode(ScrcpyControl.DOWN, keycode),
        ScrcpyControl.keycode(ScrcpyControl.UP, keycode),
    )

    /** 两档画质切换：标清 800 ↔ 高清 1200。 */
    fun nextQuality(current: Int): Int = if (current == QUALITY_HD) QUALITY_SD else QUALITY_HD

    fun qualityLabel(maxSize: Int): String = if (maxSize >= QUALITY_HD) "高清 1200" else "标清 800"
}

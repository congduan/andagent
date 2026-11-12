package com.dc.andagent.terminal

/**
 * R147：终端字体资产路径解析 + never-crash 回退逻辑（纯 JVM，可单测）。
 *
 * Android 侧 `Typeface.createFromAsset` 的调用由 [TerminalActivity] 注入 loader，
 * 因此回退分支能在不依赖 `android.graphics.Typeface` 的 JVM 单测里完整覆盖。
 */
object TerminalFonts {

    const val ASSET_DIR = "fonts"

    /** 主字体（JetBrains Mono Regular，SIL OFL 1.1）。 */
    const val REGULAR_FILE = "JetBrainsMono-Regular.ttf"

    /** 粗体字重，随包保留（SIL OFL 1.1）。termux renderer 用单画笔的
     *  `Paint.setFakeBoldText` 渲染 SGR bold（与 Termux 官方一致），不切换真粗
     *  Typeface，故本资产当前不参与渲染，仅随包提供。 */
    const val BOLD_FILE = "JetBrainsMono-Bold.ttf"

    /** SIL Open Font License 1.1 许可证副本。 */
    const val LICENSE_FILE = "OFL.txt"

    /** 字体资产路径解析：文件名 → assets 相对路径（纯函数，可单测）。 */
    fun assetPath(fileName: String): String = "$ASSET_DIR/$fileName"

    /**
     * 资产加载异常时回退：loader 正常返回则采用；返回 null 或抛 {@link Exception} 则回退到
     * [fallback]。注意不捕获 `OutOfMemoryError` 等 `Error`（解码超大损坏字体时仍可能上抛，
     * 由调用方 TerminalActivity 的创建时机保证不影响主流程）。泛型化以便纯 JVM 单测覆盖
     * 三条分支，不引用 `android.graphics.Typeface`。
     */
    fun <T : Any> loadOrFallback(fallback: T, loader: () -> T?): T =
        try {
            loader() ?: fallback
        } catch (e: Exception) {
            fallback
        }
}

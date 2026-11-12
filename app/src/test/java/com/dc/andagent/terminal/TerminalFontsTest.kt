package com.dc.andagent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * R147：TerminalFonts 纯 JVM 单测 —— 字体资产路径解析 + never-crash 回退三条分支。
 * 不引用 android.graphics.Typeface，可在纯 JVM 下运行。
 */
class TerminalFontsTest {

    @Test
    fun `assetPath resolves font and license files under fonts directory`() {
        assertEquals("fonts/JetBrainsMono-Regular.ttf", TerminalFonts.assetPath(TerminalFonts.REGULAR_FILE))
        assertEquals("fonts/JetBrainsMono-Bold.ttf", TerminalFonts.assetPath(TerminalFonts.BOLD_FILE))
        assertEquals("fonts/OFL.txt", TerminalFonts.assetPath(TerminalFonts.LICENSE_FILE))
    }

    @Test
    fun `assetPath does not depend on a leading slash or directory prefix`() {
        assertEquals("fonts/anything.ttf", TerminalFonts.assetPath("anything.ttf"))
    }

    @Test
    fun `loadOrFallback returns the loaded value`() {
        val loaded = Any()
        val fallback = Any()
        assertSame(loaded, TerminalFonts.loadOrFallback(fallback) { loaded })
    }

    @Test
    fun `loadOrFallback falls back when loader returns null`() {
        val fallback = "MONOSPACE"
        assertEquals(fallback, TerminalFonts.loadOrFallback(fallback) { null })
    }

    @Test
    fun `loadOrFallback falls back when loader throws`() {
        val fallback = "MONOSPACE"
        assertEquals(
            fallback,
            TerminalFonts.loadOrFallback(fallback) { throw RuntimeException("asset missing") },
        )
    }

    @Test
    fun `loadOrFallback is never-crash across repeated failing loads`() {
        val fallback = "MONOSPACE"
        repeat(5) { i ->
            assertEquals(
                fallback,
                TerminalFonts.loadOrFallback(fallback) { throw IllegalStateException("fail #$i") },
            )
        }
    }
}

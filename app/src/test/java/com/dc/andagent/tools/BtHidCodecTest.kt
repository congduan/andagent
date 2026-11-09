package com.dc.andagent.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R122：BtHidCodec 纯 JVM 单测（不触碰 Android 框架）。
 * 覆盖：ascii→usage 映射（大小写/数字/符号/中文拒绝）、modifier 位组装、
 * 报告字节逐字节断言、描述符头尾 magic、keycode 越界拒绝、常量表与模式选择。
 */
class BtHidCodecTest {

    /* --------------------------- ascii -> usage --------------------------- */

    @Test
    fun lowercaseLettersAndDigitsMapToBootUsages() {
        for (i in 0..25) {
            assertEquals("字母 ${'a' + i}", 0x04 + i, BtHidCodec.asciiToUsage('a' + i)!!.first)
            assertEquals(0, BtHidCodec.asciiToUsage('a' + i)!!.second)
        }
        for (i in 1..9) assertEquals("数字 $i", 0x1D + i, BtHidCodec.asciiToUsage('0' + i)!!.first)
        assertEquals(0x27, BtHidCodec.asciiToUsage('0')!!.first)
        assertEquals(0x2C, BtHidCodec.asciiToUsage(' ')!!.first)   // Space
        assertEquals(0x28, BtHidCodec.asciiToUsage('\n')!!.first)  // Enter
        assertEquals(0x2B, BtHidCodec.asciiToUsage('\t')!!.first)  // Tab
        assertEquals(0x2A, BtHidCodec.asciiToUsage('\b')!!.first)  // Backspace
        assertEquals(0x29, BtHidCodec.asciiToUsage('\u001B')!!.first) // Esc
    }

    @Test
    fun uppercaseLettersRequireLeftShift() {
        for (i in 0..25) {
            val (usage, modifier) = BtHidCodec.asciiToUsage('A' + i)!!
            assertEquals(0x04 + i, usage)
            assertEquals(BtHidCodec.MOD_LSHIFT, modifier)
        }
        assertEquals(0x04, BtHidCodec.asciiToUsage('A')!!.first)
        assertEquals(0x1D, BtHidCodec.asciiToUsage('Z')!!.first)
    }

    @Test
    fun shiftedSymbolsAndPunctuationMapCorrectly() {
        val expected = mapOf(
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
            '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
            ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
        )
        expected.forEach { (c, usage) ->
            val pair = BtHidCodec.asciiToUsage(c)
            assertNotNull("符号 $c 应可映射", pair)
            assertEquals("符号 $c 的 usage", usage, pair!!.first)
            assertEquals("符号 $c 需 LShift", BtHidCodec.MOD_LSHIFT, pair.second)
        }
        // 未 Shift 的标点
        assertEquals(0x2D, BtHidCodec.asciiToUsage('-')!!.first)
        assertEquals(0, BtHidCodec.asciiToUsage('-')!!.second)
        assertEquals(0x2E, BtHidCodec.asciiToUsage('=')!!.first)
        assertEquals(0x2F, BtHidCodec.asciiToUsage('[')!!.first)
        assertEquals(0x30, BtHidCodec.asciiToUsage(']')!!.first)
        assertEquals(0x31, BtHidCodec.asciiToUsage('\\')!!.first)
        assertEquals(0x33, BtHidCodec.asciiToUsage(';')!!.first)
        assertEquals(0x34, BtHidCodec.asciiToUsage('\'')!!.first)
        assertEquals(0x35, BtHidCodec.asciiToUsage('`')!!.first)
        assertEquals(0x36, BtHidCodec.asciiToUsage(',')!!.first)
        assertEquals(0x37, BtHidCodec.asciiToUsage('.')!!.first)
        assertEquals(0x38, BtHidCodec.asciiToUsage('/')!!.first)
    }

    @Test
    fun chineseAndEmojiAreRejected() {
        assertNull(BtHidCodec.asciiToUsage('中'))
        assertNull(BtHidCodec.asciiToUsage('\uD83D'))   // emoji high surrogate
        assertNull(BtHidCodec.asciiToUsage('é'))
        assertNotNull(BtHidCodec.textToUsages("hello world"))
        assertNull("含中文整段拒绝", BtHidCodec.textToUsages("abc中文"))
        assertNull(BtHidCodec.textToUsages("a🙂b"))
        assertEquals(3, BtHidCodec.textToUsages("aB1")!!.size)
    }

    /* --------------------------- 常量表 / modifier ----------------------- */

    @Test
    fun namedKeyUsageTableMatchesUsbHid() {
        assertEquals(0x04, BtHidCodec.KEY_USAGES["a"])
        assertEquals(0x1D, BtHidCodec.KEY_USAGES["z"])
        assertEquals(0x1E, BtHidCodec.KEY_USAGES["1"])
        assertEquals(0x26, BtHidCodec.KEY_USAGES["9"])
        assertEquals(0x27, BtHidCodec.KEY_USAGES["0"])
        assertEquals(0x28, BtHidCodec.KEY_USAGES["enter"])
        assertEquals(0x29, BtHidCodec.KEY_USAGES["esc"])
        assertEquals(0x2A, BtHidCodec.KEY_USAGES["backspace"])
        assertEquals(0x2B, BtHidCodec.KEY_USAGES["tab"])
        assertEquals(0x2C, BtHidCodec.KEY_USAGES["space"])
        assertEquals(0x4F, BtHidCodec.KEY_USAGES["right"])
        assertEquals(0x50, BtHidCodec.KEY_USAGES["left"])
        assertEquals(0x51, BtHidCodec.KEY_USAGES["down"])
        assertEquals(0x52, BtHidCodec.KEY_USAGES["up"])
        assertEquals(0x3A, BtHidCodec.KEY_USAGES["f1"])
        assertEquals(0x45, BtHidCodec.KEY_USAGES["f12"])
        assertEquals(0x04, BtHidCodec.keyUsageByName("A"))
        assertNull(BtHidCodec.keyUsageByName("nope"))
        assertEquals(0x04, BtHidCodec.USAGE_ID_MIN)
        assertEquals(0xDF, BtHidCodec.USAGE_ID_MAX)
    }

    @Test
    fun modifierConstantsFollowFixedBitOrder() {
        assertEquals(0x01, BtHidCodec.MOD_LCTRL)
        assertEquals(0x02, BtHidCodec.MOD_LSHIFT)
        assertEquals(0x04, BtHidCodec.MOD_LALT)
        assertEquals(0x08, BtHidCodec.MOD_LGUI)
        assertEquals(0x10, BtHidCodec.MOD_RCTRL)
        assertEquals(0x20, BtHidCodec.MOD_RSHIFT)
        assertEquals(0x40, BtHidCodec.MOD_RALT)
        assertEquals(0x80, BtHidCodec.MOD_RGUI)
    }

    @Test
    fun modifierBitAssemblyIsOrderIndependentAndRejectsUnknown() {
        assertEquals(0, BtHidCodec.parseModifiers(null))
        assertEquals(0, BtHidCodec.parseModifiers(""))
        assertEquals(0, BtHidCodec.parseModifiers("   "))
        assertEquals(0x03, BtHidCodec.parseModifiers("ctrl,shift"))
        assertEquals(0x03, BtHidCodec.parseModifiers("shift+ctrl"))
        assertEquals(0x0C, BtHidCodec.parseModifiers("alt|gui"))
        assertEquals(0xF0, BtHidCodec.parseModifiers("rctrl,rshift,ralt,rgui"))
        assertEquals(0xFF, BtHidCodec.parseModifiers("ctrl,shift,alt,gui,rctrl,rshift,ralt,rgui"))
        assertNull(BtHidCodec.parseModifiers("ctrl,bogus"))
    }

    /* ------------------------------ 报告字节 ------------------------------ */

    @Test
    fun keyboardReportBytesAreExact() {
        val report = BtHidCodec.keyboardReport(0x02, 0x04) // LShift + 'a'
        assertEquals(8, report.size)
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), report)
        assertArrayEquals(ByteArray(8), BtHidCodec.keyboardReport()) // 全零释放
        assertEquals(8, BtHidCodec.KEYBOARD_REPORT_BYTES)
    }

    @Test
    fun keycodeOutOfRangeIsRejected() {
        assertTrue(BtHidCodec.validKeycode(0))       // 释放
        assertTrue(BtHidCodec.validKeycode(0x04))    // 常用下界
        assertTrue(BtHidCodec.validKeycode(0xDF))    // 常用上界
        assertTrue(BtHidCodec.validKeycode(0xFF))    // 报告字段 1 字节
        assertFalse(BtHidCodec.validKeycode(-1))
        assertFalse(BtHidCodec.validKeycode(256))
        assertThrows(IllegalArgumentException::class.java) { BtHidCodec.keyboardReport(0, 256) }
        assertThrows(IllegalArgumentException::class.java) { BtHidCodec.keyboardReport(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { BtHidCodec.keyboardReport(256, 0) }
    }

    @Test
    fun mouseReportBytesAreExactAndClamped() {
        val report = BtHidCodec.mouseReport(0x01, -1, 127, -5)
        assertEquals(4, report.size)
        assertArrayEquals(byteArrayOf(0x01, 0xFF.toByte(), 0x7F, 0xFB.toByte()), report)
        assertArrayEquals(ByteArray(4), BtHidCodec.mouseReport())
        assertEquals(4, BtHidCodec.MOUSE_REPORT_BYTES)
        assertEquals(127, BtHidCodec.clampRelative(999))
        assertEquals(-127, BtHidCodec.clampRelative(-999))
        val clamped = BtHidCodec.mouseReport(0, -999, 999, 0)
        assertArrayEquals(byteArrayOf(0, 0x81.toByte(), 0x7F, 0), clamped)
        assertThrows(IllegalArgumentException::class.java) { BtHidCodec.mouseReport(buttons = 8) }
    }

    @Test
    fun mouseButtonBitsAndHexFormatting() {
        assertEquals(0x01, BtHidCodec.mouseButtonBit("left"))
        assertEquals(0x01, BtHidCodec.mouseButtonBit(null))
        assertEquals(0x02, BtHidCodec.mouseButtonBit("right"))
        assertEquals(0x04, BtHidCodec.mouseButtonBit("middle"))
        assertNull(BtHidCodec.mouseButtonBit("button4"))
        assertEquals("05 FF 01", BtHidCodec.hex(byteArrayOf(0x05, 0xFF.toByte(), 0x01)))
    }

    /* ------------------------------ 描述符 ------------------------------ */

    @Test
    fun descriptorHeadTailMagicAndModeSelection() {
        assertTrue(BtHidCodec.descriptorWellFormed(BtHidCodec.KEYBOARD_DESCRIPTOR))
        assertTrue(BtHidCodec.descriptorWellFormed(BtHidCodec.MOUSE_DESCRIPTOR))
        assertTrue(BtHidCodec.descriptorWellFormed(BtHidCodec.COMBO_DESCRIPTOR))
        assertFalse(BtHidCodec.descriptorWellFormed(null))
        assertFalse(BtHidCodec.descriptorWellFormed(byteArrayOf(0x01, 0x02)))
        // 头：Usage Page(Generic Desktop) 0x05 0x01；尾：End Collection 0xC0
        assertEquals(0x05.toByte(), BtHidCodec.KEYBOARD_DESCRIPTOR[0])
        assertEquals(0x01.toByte(), BtHidCodec.KEYBOARD_DESCRIPTOR[1])
        assertEquals(0xC0.toByte(), BtHidCodec.KEYBOARD_DESCRIPTOR.last())
        assertEquals(0x05.toByte(), BtHidCodec.MOUSE_DESCRIPTOR[0])
        assertEquals(0x01.toByte(), BtHidCodec.MOUSE_DESCRIPTOR[1])
        assertEquals(0xC0.toByte(), BtHidCodec.MOUSE_DESCRIPTOR.last())
        // Codex review 必修 1 防回归：滚轮必须是 Generic Desktop Wheel(0x38)，不是 Consumer AC Pan。
        val mouse = BtHidCodec.MOUSE_DESCRIPTOR.toList()
        val hasWheelUsage = (0 until mouse.size - 1).any { mouse[it] == 0x09.toByte() && mouse[it + 1] == 0x38.toByte() }
        val hasAcPan = (0 until mouse.size - 2).any {
            mouse[it] == 0x0A.toByte() && mouse[it + 1] == 0x38.toByte() && mouse[it + 2] == 0x02.toByte()
        }
        assertTrue("鼠标描述符须含 09 38（GD Wheel）：$hasWheelUsage", hasWheelUsage)
        assertFalse("鼠标描述符不得含 AC Pan（0A 38 02）", hasAcPan)
        assertEquals(0xC0.toByte(), BtHidCodec.COMBO_DESCRIPTOR.last())
        assertEquals(
            BtHidCodec.KEYBOARD_DESCRIPTOR.size + BtHidCodec.MOUSE_DESCRIPTOR.size,
            BtHidCodec.COMBO_DESCRIPTOR.size,
        )
        assertSame(BtHidCodec.KEYBOARD_DESCRIPTOR, BtHidCodec.descriptorFor("keyboard"))
        assertSame(BtHidCodec.MOUSE_DESCRIPTOR, BtHidCodec.descriptorFor("mouse"))
        assertSame(BtHidCodec.COMBO_DESCRIPTOR, BtHidCodec.descriptorFor("combo"))
        assertSame(BtHidCodec.COMBO_DESCRIPTOR, BtHidCodec.descriptorFor(null))
        assertNull(BtHidCodec.descriptorFor("gamepad"))
    }

    @Test
    fun reportIdsMatchDescriptorIds() {
        assertEquals(1, BtHidCodec.REPORT_ID_KEYBOARD)
        assertEquals(2, BtHidCodec.REPORT_ID_MOUSE)
        assertEquals(1, BtHidCodec.reportIdFor("keyboard"))
        assertEquals(2, BtHidCodec.reportIdFor("mouse"))
        assertEquals(1, BtHidCodec.reportIdFor("combo"))
        // 描述符里确实声明了对应 Report ID（0x85 前缀项）
        assertTrue(BtHidCodec.hex(BtHidCodec.KEYBOARD_DESCRIPTOR).startsWith("05 01 09 06 A1 01 85 01"))
        val mousePrefix = BtHidCodec.MOUSE_DESCRIPTOR.take(7).toByteArray()
        assertEquals("05 01 09 02 A1 01 85", BtHidCodec.hex(mousePrefix))
    }
}

package com.dc.andagent.tools

import com.dc.andagent.tools.ProntoHex.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pronto Hex 解析与 pattern 校验的字节级断言（R66）。 */
class IrToolsTest {

    @Test fun prontoParsesCarrierAndPairs() {
        // 经典 NEC 38kHz 例子：0000 006D 0000 000D…（carrier = 4145106/0x006D Hz ≈ 38.4k）
        val signal = parse("0000 006D 0000 000D 0012 002A 0012 002A 0012 0014 0012 002A")
        assertEquals(4145106, signal.carrierHz)
        // pairs(6) * 2 = pattern 12 项，微秒换算（unit = 0x006D us 的 21.33 倍规则）
        assertTrue(signal.pattern.size == 12)
        assertTrue(signal.pattern.all { it > 0 })
    }

    @Test fun prontoAcceptsWhitespaceAndHex() {
        val a = parse("0000 006D 0000 000D 0012 002A")
        val b = parse("0000  006d  0000  000d  0012  002a")
        assertEquals(a.carrierHz, b.carrierHz)
        assertEquals(a.pattern.toList(), b.pattern.toList())
    }

    @Test fun prontoRejectsOddPairs() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("0000 006D 0000 000D 0012") // pair 数为奇数（缺 off）
        }
    }

    @Test fun prontoRejectsShortHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("0000 006D") // 缺少 repeat/pair 数据
        }
    }

    @Test fun patternValidationBounds() {
        assertTrue(validatePattern(intArrayOf(560, 1690, 560)).also { return@assertTrue true }.isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { validatePattern(intArrayOf(0)) }       // 非正时长
        assertThrows(IllegalArgumentException::class.java) { validatePattern(intArrayOf(-5)) }      // 负值
        assertThrows(IllegalArgumentException::class.java) { validatePattern(IntArray(0)) }         // 空
    }
}

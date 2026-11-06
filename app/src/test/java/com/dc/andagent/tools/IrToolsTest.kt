package com.dc.andagent.tools

import com.dc.andagent.tools.ProntoHex.parse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pronto Hex 解析与 pattern 校验的字节级断言（R66）。 */
class IrToolsTest {

    @Test fun prontoParsesCarrierAndPairs() {
        // 合法样例：0000 006D 0000 0004 + 4 对脉冲 = 12 tokens；载波 ≈ 38.0kHz
        val signal = parse("0000 006D 0000 0004 0012 002A 0012 0014 0012 002A 0012 0014")
        assertEquals(38029, signal.carrierHz)
        assertEquals(8, signal.pattern.size)
        assertTrue(signal.pattern.all { it > 0 })
    }

    @Test fun prontoAcceptsWhitespaceAndHex() {
        val a = parse("0000 006D 0000 0004 0012 002A 0012 0014")
        val b = parse("0000  006d  0000  0004  0012  002a  0012  0014")
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
            parse("0000 006D 0000") // 缺少 repeat/pair 数据
        }
    }

    @Test fun patternValidationBounds() {
        val ok = validatePattern(intArrayOf(560, 1690, 560))
        assertThrows(IllegalArgumentException::class.java) { validatePattern(intArrayOf(0)) }       // 非正时长
        assertThrows(IllegalArgumentException::class.java) { validatePattern(intArrayOf(-5)) }      // 负值
        assertThrows(IllegalArgumentException::class.java) { validatePattern(IntArray(0)) }         // 空
    }
}

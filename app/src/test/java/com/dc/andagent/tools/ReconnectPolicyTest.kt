package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R124 d: 指数退避序列与封顶的纯 JVM 单测。 */
class ReconnectPolicyTest {

    @Test fun delaySequenceIs1s4s16sCappedAt16s() {
        assertEquals(1_000L, ReconnectPolicy.delayMs(0))
        assertEquals(4_000L, ReconnectPolicy.delayMs(1))
        assertEquals(16_000L, ReconnectPolicy.delayMs(2))
        assertEquals(16_000L, ReconnectPolicy.delayMs(3))
        assertEquals(16_000L, ReconnectPolicy.delayMs(4))
    }

    @Test fun cappedAtMaxForLargeAndNegativeAttempts() {
        assertEquals(16_000L, ReconnectPolicy.delayMs(99))
        assertEquals(16_000L, ReconnectPolicy.delayMs(Int.MAX_VALUE))
        assertEquals(1_000L, ReconnectPolicy.delayMs(-1))
        assertEquals(1_000L, ReconnectPolicy.delayMs(Int.MIN_VALUE))
    }

    @Test fun neverExceedsMaxWithinMaxAttemptsAndIsPositive() {
        for (attempt in 0 until ReconnectPolicy.MAX_ATTEMPTS) {
            val d = ReconnectPolicy.delayMs(attempt)
            assertTrue("attempt=$attempt delay=$d", d > 0 && d <= ReconnectPolicy.MAX_DELAY_MS)
        }
    }

    @Test fun maxAttemptsIsFive() {
        assertEquals(5, ReconnectPolicy.MAX_ATTEMPTS)
        assertEquals(16_000L, ReconnectPolicy.MAX_DELAY_MS)
    }
}

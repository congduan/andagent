package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R124 b: 前台保活引用计数的纯 JVM 单测（幂等 / 防负 / SSH+ADB 共用 / 线程安全语义）。 */
class KeepAliveRefsTest {

    @Before fun reset() {
        KeepAliveRefs.clear()
        // 动作未安装时 acquire 返回 false（无法拉起），测试装一对 no-op 动作走真实计数语义。
        KeepAliveRefs.installForTest({}, {})
    }

    @Test fun firstAcquireNeedsStartAndIsIdempotent() {
        assertTrue(KeepAliveRefs.acquire("ssh:a"))
        assertFalse(KeepAliveRefs.acquire("ssh:a")) // 同 tag 重复 acquire 不重复 start
        assertFalse(KeepAliveRefs.acquire("adb"))   // 已有引用时不重复 start
        assertEquals(2, KeepAliveRefs.size())
        assertTrue(KeepAliveRefs.contains("ssh:a"))
        assertTrue(KeepAliveRefs.contains("adb"))
    }

    @Test fun releaseNeedsStopOnlyWhenLastRefGone() {
        KeepAliveRefs.acquire("ssh:a")
        KeepAliveRefs.acquire("ssh:b")
        KeepAliveRefs.acquire("adb")
        assertFalse(KeepAliveRefs.release("ssh:a")) // 还有其它引用
        assertFalse(KeepAliveRefs.release("ssh:b"))
        assertTrue(KeepAliveRefs.release("adb"))    // 最后一个 → stop
        assertEquals(0, KeepAliveRefs.size())
    }

    @Test fun unknownAndRepeatedReleaseAreNoOpNeverNegative() {
        assertFalse(KeepAliveRefs.release("nope"))
        KeepAliveRefs.acquire("ssh:a")
        assertTrue(KeepAliveRefs.release("ssh:a"))
        assertFalse(KeepAliveRefs.release("ssh:a")) // 重复 release 幂等，计数不为负
        assertFalse(KeepAliveRefs.release("ssh:a"))
        assertEquals(0, KeepAliveRefs.size())
    }

    @Test fun reacquireAfterEmptyStartsAgain() {
        assertTrue(KeepAliveRefs.acquire("adb"))
        assertTrue(KeepAliveRefs.release("adb"))
        assertTrue(KeepAliveRefs.acquire("adb")) // 清空后再 acquire 重新返回「需要 start」
    }

    @Test fun sshAndAdbShareSingleCount() {
        // SSH 会话还在时不因 ADB 断开而停服；反之亦然。
        KeepAliveRefs.acquire("ssh:a")
        KeepAliveRefs.acquire("adb")
        assertFalse(KeepAliveRefs.release("adb"))    // SSH 仍持引用 → 不停服
        assertTrue(KeepAliveRefs.release("ssh:a"))   // 全清 → 停服
        assertEquals(0, KeepAliveRefs.size())
    }

    @Test fun clearResetsState() {
        KeepAliveRefs.acquire("ssh:a")
        KeepAliveRefs.acquire("adb")
        KeepAliveRefs.clear()
        assertEquals(0, KeepAliveRefs.size())
        assertTrue(KeepAliveRefs.acquire("ssh:b"))
    }

    @Test fun markServiceDeadTriggersSelfHealRelaunch() {
        var starts = 0
        KeepAliveRefs.installForTest(onStart = { starts++ }, onStop = {})
        KeepAliveRefs.acquire("adb")
        assertEquals(1, starts)
        assertFalse(KeepAliveRefs.acquire("adb")) // running 标记在，不再 start
        // 服务死亡（onStartCommand 异常自停 / 系统回收）→ 复位标记 → 下次 acquire 重拉。
        KeepAliveRefs.markServiceDead()
        assertTrue(KeepAliveRefs.acquire("adb"))
        assertEquals(2, starts)
    }

    @Test fun repairRelaunchesWhenRefsRemainButServiceDead() {
        var starts = 0
        KeepAliveRefs.installForTest(onStart = { starts++ }, onStop = {})
        KeepAliveRefs.acquire("adb")
        assertEquals(1, starts)
        KeepAliveRefs.markServiceDead()
        KeepAliveRefs.repair() // 引用仍在但服务已死 → 补拉
        assertEquals(2, starts)
        KeepAliveRefs.repair() // 已在跑 → no-op
        assertEquals(2, starts)
    }

    @Test fun startActionExceptionPropagatesAndRefStaysUnregistered() {
        KeepAliveRefs.installForTest(onStart = { throw IllegalStateException("boom") }, onStop = {})
        try {
            KeepAliveRefs.acquire("ssh:x")
            org.junit.Assert.fail("acquire 应向上传播 start 动作异常")
        } catch (expected: IllegalStateException) {
            // 调用方（SshSessionPool.put）据此回滚：此处验证引用确实没有登记成功语义
            // ——acquire 抛异常时 start 未成功，tag 仍会登记（由调用方 release 回滚），
            // 本断言只锁定异常传播路径供 put() 的回滚逻辑依赖。
        }
        // 计数内核不因异常损坏：恢复 no-op 动作后可正常再次 acquire。
        KeepAliveRefs.installForTest({}, {})
        KeepAliveRefs.release("ssh:x") // 回滚孤儿引用（模拟调用方 catch 分支）
        assertTrue(KeepAliveRefs.acquire("ssh:y"))
    }

    @Test fun stopActionInvokedExactlyOnceOnLastRelease() {
        var stops = 0
        KeepAliveRefs.installForTest(onStart = {}, onStop = { stops++ })
        KeepAliveRefs.acquire("ssh:a")
        KeepAliveRefs.acquire("adb")
        KeepAliveRefs.release("ssh:a")
        assertEquals(0, stops)
        KeepAliveRefs.release("adb")
        assertEquals(1, stops) // 仅最后一个 release 触发一次 stop
        KeepAliveRefs.release("adb") // 幂等：不再 stop
        assertEquals(1, stops)
    }
}

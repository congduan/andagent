package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTransferRulesTest {
    @org.junit.Test fun cancellationStopsProgressAndCanBeCleared() {
        val key = SshTransferCancel.key("session", "/tmp/a")
        val progress = SshProgress(10_000_000, 10_000_000, cancelled = { SshTransferCancel.isCancelled(key) })
        progress.count(1_000_000)
        SshTransferCancel.request(key)
        try {
            progress.count(1_000_000)
            throw AssertionError("transfer continued")
        } catch (e: java.io.IOException) {
            org.junit.Assert.assertEquals("已被用户中断", e.message)
        } finally {
            SshTransferCancel.clear(key)
        }
        org.junit.Assert.assertFalse(SshTransferCancel.isCancelled(key))
    }
    @Test fun presenceStartsOnceAndStopsOnlyWhenEmpty() {
        val p = SshSessionPresence()
        assertTrue(p.put("a"))
        assertFalse(p.put("a"))
        assertFalse(p.put("b"))
        assertFalse(p.remove("missing"))
        assertFalse(p.remove("a"))
        assertEquals(1, p.size())
        assertTrue(p.remove("b"))
        assertFalse(p.remove("b"))
        assertTrue(p.put("c"))
    }

    @Test fun remotePathsExpandHomeAndRejectTraversal() {
        assertEquals("/home/u", SshTransferRules.remotePath("~", "/home/u"))
        assertEquals("/home/u/a.log", SshTransferRules.remotePath("~/a.log", "/home/u"))
        assertEquals("/home/u/sub/a.log", SshTransferRules.remotePath("sub/a.log", "/home/u"))
        assertEquals("/var/log/a", SshTransferRules.remotePath("/var/log/a", "/home/u"))
        for (path in listOf("../secret", "~/../secret", "/var/../etc/passwd", "~other/file")) {
            try { SshTransferRules.remotePath(path, "/home/u"); throw AssertionError("accepted $path") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun sizeCapClampsAndProgressThrottles() {
        assertEquals(512, SshTransferRules.maxMb(null))
        assertEquals(1, SshTransferRules.maxMb(-100))
        assertEquals(4096, SshTransferRules.maxMb(Int.MAX_VALUE))
        val p = SshProgress(10_000_000, 10_000_000)
        p.init(0, "a", "b", 10_000_000)
        assertTrue(p.count(100_000))
        assertTrue(p.lines.isEmpty())
        assertTrue(p.count(500_000)) // 6%, first report
        assertEquals(1, p.lines.size)
        assertTrue(p.count(100_000))
        assertEquals(1, p.lines.size)
        assertFalse(p.count(10_000_000))
        assertTrue(p.exceeded)
    }

    @Test fun safeNameRejectsControlChars() {
        assertEquals("a.log", SshTransferRules.safeName("a.log"))
        for (bad in listOf("a\nb", "a\tb", "a\u0000b", "a\u0001b", "a\u001fb")) {
            try { SshTransferRules.safeName(bad); throw AssertionError("accepted control-char name") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun notificationRequiresTwoSecondsAndTenPercentWhileLinesContinue() {
        var time = 0L
        val reports = mutableListOf<Int>()
        val progress = SshProgress(1_000_000, 1_000_000, reports::add) { time }
        assertTrue(progress.count(100_000))
        assertEquals(1, progress.lines.size)
        assertTrue(reports.isEmpty())
        time = 2_000
        assertTrue(progress.count(100_000))
        assertEquals(listOf(20), reports)
        time = 2_100
        assertTrue(progress.count(100_000))
        assertEquals(3, progress.lines.size)
        assertEquals(listOf(20), reports)
        time = 4_100
        assertTrue(progress.count(100_000))
        assertEquals(listOf(20, 40), reports)
        assertFalse(SshTransferRules.shouldNotify(4_100, 40, 6_100, 49))
    }
}

package com.dc.andagent.live

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveLoopRunnerTest {
    @Test fun pauseAndResumeChangeTickFlow() {
        val calls = AtomicInteger()
        val runner = LiveLoopRunner({ _, result -> calls.incrementAndGet(); result("42"); "null" },
            { _, _, _ -> }, {})
        try {
            val id = runner.start("pause", "live.result(42)", 200, "dialog")["id"]!!.jsonPrimitive.content
            Thread.sleep(260)
            assertEquals("paused", runner.pause(id, true)["state"]!!.jsonPrimitive.content)
            val before = calls.get()
            Thread.sleep(420)
            assertEquals(before, calls.get())
            assertEquals("running", runner.pause(id, false)["state"]!!.jsonPrimitive.content)
            Thread.sleep(260)
            assertTrue(calls.get() > before)
        } finally { runner.shutdown() }
    }

    @Test fun stopWaitsForCurrentTickAndPreventsNextTick() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val runner = LiveLoopRunner({ _, result ->
            if (calls.incrementAndGet() > 1) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            result("1"); "null"
        }, { _, _, _ -> }, {})
        try {
            val id = runner.start("stop", "live.result(1)", 200, "dialog")["id"]!!.jsonPrimitive.content
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals("stopped", runner.stop(id)["state"]!!.jsonPrimitive.content)
            release.countDown()
            Thread.sleep(450)
            assertEquals(2, calls.get())
        } finally { release.countDown(); runner.shutdown() }
    }

    @Test fun fiveConsecutiveFailuresAutoStop() {
        val calls = AtomicInteger()
        val notice = CountDownLatch(1)
        val runner = LiveLoopRunner({ _, _ -> calls.incrementAndGet(); "ERROR: failed" },
            { _, _, _ -> }, { notice.countDown() })
        try {
            val id = runner.start("fail", "throw 1", 200, "dialog")["id"]!!.jsonPrimitive.content
            assertTrue(notice.await(3, TimeUnit.SECONDS))
            val state = runner.list()["loops"].toString()
            assertTrue(state.contains(id))
            assertTrue(state.contains("stopped"))
            assertEquals(5, calls.get())
        } finally { runner.shutdown() }
    }

    @Test fun firstFailureIsReturnedAndHistoryKeepsFiveLatestTicks() {
        val runner = LiveLoopRunner({ _, _ -> "ERROR: ReferenceError: missing at line 2" }, { _, _, _ -> }, {})
        try {
            val started = runner.start("errors", "missing", 200, "dialog")
            val id = started["id"]!!.jsonPrimitive.content
            // Codex R77 must-fix #5: the first tick runs asynchronously off the
            // start() lock, so firstTickFailed is not guaranteed to be visible
            // when start() returns — poll for it. Cap below 5 ticks (~1000ms)
            // so the loop cannot auto-stop before we pause it.
            var snap = started
            val deadline = System.currentTimeMillis() + 700
            while (snap["firstTickFailed"]?.jsonPrimitive?.content != "true" && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
                snap = runner.pause(id, true)
            }
            assertEquals("true", snap["firstTickFailed"]!!.jsonPrimitive.content)
            assertTrue(snap["warning"]!!.jsonPrimitive.content.contains("ReferenceError"))
            // A tick already past its paused-check may still land after pause()
            // returns (entry-check race). Settle: wait until ticks stop changing.
            var frozenTicks = snap["ticks"]!!.jsonPrimitive.content
            var stable = false
            for (attempt in 0 until 10) {
                Thread.sleep(150)
                val now = runner.pause(id, true)["ticks"]!!.jsonPrimitive.content
                if (now == frozenTicks) { stable = true; break }
                frozenTicks = now
            }
            org.junit.Assert.assertTrue("ticks never stabilized", stable)
            // While paused the loop cannot tick: ticks and error history are frozen.
            Thread.sleep(450)
            val resumed = runner.pause(id, false)
            assertEquals(frozenTicks, resumed["ticks"]!!.jsonPrimitive.content)
            val history = resumed["errorHistory"]!!.jsonArray
            assertTrue(history.size in 1..5)
            assertTrue(history.last().jsonPrimitive.content.contains("#1"))
            // History never grows past the 5-entry cap while running.
            Thread.sleep(1200)
            val listed = runner.list()["loops"]!!.jsonArray.first().jsonObject
            assertTrue(listed["errorHistory"]!!.jsonArray.size in 1..5)
        } finally { runner.shutdown() }
    }
}

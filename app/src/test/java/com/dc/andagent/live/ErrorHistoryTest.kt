package com.dc.andagent.live

import com.dc.andagent.script.ScriptError
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorHistoryTest {
    private val stack = "TypeError: x\n    at <eval> (andagent-script.js:7:3)"

    @Test fun ringBufferRollsAfterFiveEntries() {
        val history = ErrorHistory()
        for (tick in 1L..7L) history.add(tick, ScriptError.fromJs("failure #$tick", stack))
        val entries = history.snapshot()
        assertEquals(5, entries.size)
        assertEquals(listOf(7L, 6L, 5L, 4L, 3L), entries.map { it["tick"]!!.jsonPrimitive.content.toLong() })
        assertTrue("oldest ticks must be evicted", entries.none { it["tick"]!!.jsonPrimitive.content.toLong() < 3 })
    }

    @Test fun everyEntryCarriesTickTimestampAndStructuredError() {
        val history = ErrorHistory()
        history.add(1, ScriptError.fromJs("bad", stack))
        val entry = history.snapshot().single()
        assertEquals(1L, entry["tick"]!!.jsonPrimitive.content.toLong())
        assertTrue(entry["timestamp"]!!.jsonPrimitive.content.contains("T"))
        val error = entry["error"]!!.jsonObject
        assertEquals("bad", error["message"]!!.jsonPrimitive.content)
        assertEquals(stack, error["stack"]!!.jsonPrimitive.content)
        assertEquals("andagent-script.js", error["scriptName"]!!.jsonPrimitive.content)
        assertEquals(7, error["line"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, error["col"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun capacityIsConfigurable() {
        val history = ErrorHistory(2)
        repeat(4) { history.add(it.toLong(), ScriptError("e$it")) }
        assertEquals(2, history.snapshot().size)
        assertEquals(3L, history.snapshot().first()["tick"]!!.jsonPrimitive.content.toLong())
    }
}

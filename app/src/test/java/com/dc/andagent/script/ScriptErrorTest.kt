package com.dc.andagent.script

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptErrorTest {
    private val quickJsStack = """
        ReferenceError: missing is not defined
            at <eval> (andagent-script.js:2:5)
            at andagent-script.js:3:1
    """.trimIndent()

    @Test fun jsErrorKeepsAllPassthroughFields() {
        val error = ScriptError.fromJs("ReferenceError: missing is not defined", quickJsStack)
        assertEquals("ReferenceError: missing is not defined", error.message)
        assertEquals(quickJsStack, error.stack)
        assertEquals("andagent-script.js", error.scriptName)
        assertEquals(2, error.line)
        assertEquals(5, error.col)

        val json = error.toJson()
        assertEquals("ReferenceError: missing is not defined", json["message"]!!.jsonPrimitive.content)
        assertEquals(quickJsStack, json["stack"]!!.jsonPrimitive.content)
        assertEquals("andagent-script.js", json["scriptName"]!!.jsonPrimitive.content)
        assertEquals(2, json["line"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, json["col"]!!.jsonPrimitive.content.toInt())
        assertEquals("andagent-script.js:2:5 · ReferenceError: missing is not defined", error.display())
    }

    @Test fun throwableKeepsFullStackInsteadOfMessageOnly() {
        val error = ScriptError.fromThrowable(IllegalStateException("boom"))
        assertEquals("boom", error.message)
        assertTrue(error.stack!!.contains("java.lang.IllegalStateException: boom"))
        assertTrue(error.stack!!.contains("ScriptErrorTest"))
        assertNull(error.scriptName)
    }

    @Test fun fromErrorResultAcceptsStructuredPayload() {
        val payload = "ERROR: " + ScriptError.fromJs("bad", quickJsStack).toJson()
        val error = ScriptError.fromErrorResult(payload)
        assertEquals("bad", error.message)
        assertEquals("andagent-script.js", error.scriptName)
        assertEquals(2, error.line)
        assertEquals(5, error.col)
    }

    @Test fun fromErrorResultFallsBackToLegacyPlainString() {
        val error = ScriptError.fromErrorResult("ERROR: tick 未调用 live.result(data)")
        assertEquals("tick 未调用 live.result(data)", error.message)
        assertNull(error.scriptName)
        assertNull(error.line)
    }
}

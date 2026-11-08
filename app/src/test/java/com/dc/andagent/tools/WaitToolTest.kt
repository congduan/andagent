package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitToolTest {
    private val tool = WaitTool()

    @Test fun schemaDescribesBothDurationUnits() {
        assertEquals("wait", tool.name)
        val props = tool.parameters.jsonObject.getValue("properties").jsonObject
        assertTrue(props.containsKey("seconds"))
        assertTrue(props.containsKey("duration_ms"))
    }

    @Test fun rejectsMissingOrInvalidDuration() = runBlocking {
        assertTrue(tool.execute(JsonObject(emptyMap())).startsWith("ERROR:"))
        assertTrue(tool.execute(args("duration_ms" to JsonPrimitive(0))).startsWith("ERROR:"))
        assertTrue(tool.execute(args("duration_ms" to JsonPrimitive(60_001))).startsWith("ERROR:"))
        assertTrue(tool.execute(args("seconds" to JsonPrimitive("abc"))).startsWith("ERROR:"))
        assertTrue(tool.execute(args("seconds" to JsonPrimitive(61.0))).startsWith("ERROR:"))
    }

    @Test fun waitsRequestedMilliseconds() = runBlocking {
        val startedAt = System.nanoTime()
        val result = Json.parseToJsonElement(tool.execute(args("duration_ms" to JsonPrimitive(20L)))).jsonObject
        assertEquals(20L, result.getValue("waited_ms").jsonPrimitive.long)
        assertTrue((System.nanoTime() - startedAt) / 1_000_000L >= 20L)
    }

    @Test fun secondsAcceptsDecimalsAndMillisecondsTakePrecedence() = runBlocking {
        val fromSeconds = Json.parseToJsonElement(tool.execute(args("seconds" to JsonPrimitive(0.02)))).jsonObject
        assertEquals(20L, fromSeconds.getValue("waited_ms").jsonPrimitive.long)
        val both = Json.parseToJsonElement(tool.execute(args(
            "seconds" to JsonPrimitive(2.0),
            "duration_ms" to JsonPrimitive(1L),
        ))).jsonObject
        assertEquals(1L, both.getValue("waited_ms").jsonPrimitive.long)
    }

    private fun args(vararg pairs: Pair<String, JsonPrimitive>): JsonObject = JsonObject(pairs.toMap())
}

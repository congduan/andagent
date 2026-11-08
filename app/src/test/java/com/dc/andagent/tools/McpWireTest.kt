package com.dc.andagent.tools

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpWireTest {
    private val id = JsonPrimitive("req-1")

    @Test fun parsesPlainJsonResponse() {
        val response = McpWire.parseResponse(
            """{"jsonrpc":"2.0","id":"req-1","result":{"tools":[]}}""",
            id,
        )
        assertEquals("req-1", response?.get("id")?.toString()?.trim('"'))
        assertEquals("""{"tools":[]}""", response?.get("result").toString())
    }

    @Test fun parsesSseResponseAndPicksMatchingId() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

            data: {"jsonrpc":"2.0","id":"req-1","result":{"ok":true}}
        """.trimIndent()
        val response = McpWire.parseResponse(body, id)
        assertEquals("""{"ok":true}""", response?.get("result").toString())
    }

    @Test fun returnsNullForEmptyOrGarbage() {
        assertNull(McpWire.parseResponse("", id))
        assertNull(McpWire.parseResponse("event: ping\n", id))
    }
}

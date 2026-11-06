package com.dc.andagent.events

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventHistoryTest {
    @Test fun boundedHistoryAndFiltering() {
        var history = EventHistory.accept(null, event(100, "a"))
        repeat(110) { history = EventHistory.accept(history, event(101L + it, if (it % 2 == 0) "a" else "b")) }
        assertEquals(100, history.size)
        assertEquals(5, EventHistory.read(history, 200, "a").size)
        assertEquals(0, EventHistory.read(history, 300).size)
    }
    @Test fun eventFormatHasTimestampAndPayload() {
        val result = EventStore.event("pause", buildJsonObject { put("package", "player.app"); put("title", "Song") })
        assertTrue((result["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L) > 0)
        assertEquals("pause", result["event"]?.jsonPrimitive?.content)
        assertEquals("player.app", result["package"]?.jsonPrimitive?.content)
        assertEquals("Song", result["title"]?.jsonPrimitive?.content)
    }
    @Test fun excludesOwnSystemAndConfiguredPackages() {
        assertTrue(EventHistory.excluded("com.dc.andagent", "com.dc.andagent", emptySet()))
        assertTrue(EventHistory.excluded("com.android.systemui", "com.dc.andagent", emptySet()))
        assertTrue(EventHistory.excluded("example.app", "com.dc.andagent", setOf("example.app")))
        assertFalse(EventHistory.excluded("other.app", "com.dc.andagent", emptySet()))
    }
    private fun event(timestamp: Long, pkg: String) = buildJsonObject {
        put("timestamp", timestamp); put("event", "posted"); put("package", pkg)
    }
}

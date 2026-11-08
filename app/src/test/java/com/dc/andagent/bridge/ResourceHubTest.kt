package com.dc.andagent.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceHubTest {
    @Test fun snapshotKeepsGroupsAndFields() {
        val json = ResourceHub.snapshot(listOf(
            listOf(ResourceItem("ssh", "ssh-1", "server", "host:22", 123L)),
            listOf(ResourceItem("cron", "cron-1", "backup", "0 1 * * *", canClose = false)),
        ))
        val rows = Json.parseToJsonElement(json).jsonArray
        assertEquals(2, rows.size)
        assertEquals("ssh", rows[0].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("123", rows[0].jsonObject.getValue("aliveSince").jsonPrimitive.content)
        assertEquals("false", rows[1].jsonObject.getValue("canClose").jsonPrimitive.content)
    }

    @Test fun closeRoutesByKindAndRejectsUnknown() {
        var closed = ""
        val routes = mapOf("adb" to { id: String -> closed = id; "OK" })
        assertEquals("OK", ResourceHub.close("adb", "device.local", routes))
        assertEquals("device.local", closed)
        assertEquals("ERROR: 不支持的资源类型：cron", ResourceHub.close("cron", "1", routes))
    }
}

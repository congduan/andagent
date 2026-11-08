package com.dc.andagent.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // R110-review 必修项②：ADB 资源 id 必须是精确的 host:port key（防同 host
    // 多端口 React key 重复、close 经 registry.resolve 误关首项）。纯函数防回归锁。
    @Test fun adbItemSerializesPreciseHostPortKey() {
        val json = ResourceHub.snapshot(listOf(
            listOf(ResourceItem("adb", "h:5555", "h", "h:5555")),
        ))
        val row = Json.parseToJsonElement(json).jsonArray[0].jsonObject
        assertEquals("h:5555", row.getValue("id").jsonPrimitive.content)
    }

    // R110-review 必修项①：parseAction 主帧凭证（恒定时间比对）+ 纯 JVM 解析。
    @Test fun parseActionAcceptsValidCloseAndList() {
        val close = ResourceHub.parseAction(
            """{"action":"close","kind":"adb","id":"h:5555","__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("close", "adb", "h:5555"), close)

        val list = ResourceHub.parseAction(
            """{"action":"list","__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("list", null, null), list)
    }

    @Test fun parseActionRejectsWrongOrMissingToken() {
        assertNull(ResourceHub.parseAction("""{"action":"list","__token":"wrong"}""", "tok-110"))
        assertNull(ResourceHub.parseAction("""{"action":"list"}""", "tok-110"))
    }

    @Test fun parseActionRejectsBadJsonAndMissingAction() {
        assertNull(ResourceHub.parseAction("not json", "tok-110"))
        assertNull(ResourceHub.parseAction("""{"__token":"tok-110"}""", "tok-110"))
    }
}

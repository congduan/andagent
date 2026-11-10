package com.dc.andagent.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

    // R133c：offline 资源快照序列化 offline 标志，且 offline 项 aliveSince 保持 null
    // （不回填）。回填发生在 AgentBridge.hubList（依赖 Android 环境，非纯函数），
    // 此处只锁 ResourceHub.snapshot 纯函数层的序列化结果。
    @Test fun offlineItemSnapshotKeepsOfflineFlagAndNullAliveSince() {
        val json = ResourceHub.snapshot(listOf(
            listOf(
                ResourceItem("adb", "h:5555", "h", "h:5555（未连接）", aliveSince = null, offline = true),
                ResourceItem("ssh", "ssh-1", "server", "host:22", 123L),
            ),
        ))
        val rows = Json.parseToJsonElement(json).jsonArray
        val offline = rows[0].jsonObject
        assertEquals("true", offline.getValue("offline").jsonPrimitive.content)
        assertEquals(JsonNull, offline.getValue("aliveSince"))
        // 默认项 offline=false（默认参数不破坏既有序列化）。
        assertEquals("false", rows[1].jsonObject.getValue("offline").jsonPrimitive.content)
    }

    // R110-review 必修项①：parseAction 主帧凭证（恒定时间比对）+ 纯 JVM 解析。
    @Test fun parseActionAcceptsValidCloseAndList() {
        val close = ResourceHub.parseAction(
            """{"action":"close","kind":"adb","id":"h:5555","__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("close", "adb", "h:5555", null), close)

        val list = ResourceHub.parseAction(
            """{"action":"list","__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("list", null, null, null), list)
    }

    // R133c-review 必修项①：close 携带确认时 offline 状态；list 无此键为 null；
    // 非 bool 值（脏 payload）降级 null，不抛异常。
    @Test fun parseActionCarriesConfirmedOffline() {
        val close = ResourceHub.parseAction(
            """{"action":"close","kind":"adb","id":"h:1","confirmed_offline":true,"__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("close", "adb", "h:1", true), close)

        val closeOffline = ResourceHub.parseAction(
            """{"action":"close","kind":"adb","id":"h:1","confirmed_offline":false,"__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("close", "adb", "h:1", false), closeOffline)

        val dirty = ResourceHub.parseAction(
            """{"action":"close","kind":"adb","id":"h:1","confirmed_offline":"junk","__token":"tok-110"}""", "tok-110")
        assertEquals(HubAction("close", "adb", "h:1", null), dirty)
    }

    // R133c-review 必修项①：CHANGED 前缀判定纯函数层——confirmedOffline 与执行时
    // 状态（live=!offline）不一致才翻转。锁住四象限真值表，防后续改判定符号。
    @Test fun closeFlipDetectionTruthTable() {
        fun flipped(confirmedOffline: Boolean?, live: Boolean) =
            confirmedOffline != null && confirmedOffline != !live
        // 确认 offline、执行时仍 offline → 不翻转
        assertEquals(false, flipped(true, live = false))
        // 确认 offline、执行时已 live（移除→只断开）→ 翻转
        assertEquals(true, flipped(true, live = true))
        // 确认 live、执行时仍 live → 不翻转
        assertEquals(false, flipped(false, live = true))
        // 确认 live、执行时已 offline（断开→删保存信息）→ 翻转
        assertEquals(true, flipped(false, live = false))
        // 未携带状态（旧入口/其他 kind）→ 永不翻转
        assertEquals(false, flipped(null, live = true))
        assertEquals(false, flipped(null, live = false))
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

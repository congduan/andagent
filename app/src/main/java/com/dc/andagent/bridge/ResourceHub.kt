package com.dc.andagent.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

data class ResourceItem(
    val kind: String, val id: String, val title: String, val subtitle: String,
    val aliveSince: Long? = null, val canClose: Boolean = true,
)

/**
 * R110-review 必修项①：资源中心唯一入口的解析结果。纯 JVM 数据类，可脱离 Android 单测。
 * [kind]/[id] 允许为 null（close 时由上层判空报错）；[action] 缺失或凭证不符时
 * [ResourceHub.parseAction] 直接返回 null。
 */
data class HubAction(val action: String, val kind: String?, val id: String?)

/** Pure composition: callers collect each pool's snapshot before entering here. */
object ResourceHub {
    /**
     * R110-review 必修项①：解析 args JSON 并校验主帧凭证。
     * __token 用 MessageDigest.isEqual 恒定时间比对（与 kvSettingCall 同模式）；
     * 任何解析失败 / __token 缺失或不符 / action 缺失 → null。kind/id 允许为 null，
     * 由上层在 close 时判空。jsonObject/jsonPrimitive 读取均用 kotlinx，坏结构不抛异常。
     */
    fun parseAction(raw: String, expectedToken: String): HubAction? {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        fun str(key: String): String? = runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val token = str("__token") ?: return null
        if (!MessageDigest.isEqual(token.toByteArray(), expectedToken.toByteArray())) return null
        val action = str("action") ?: return null
        return HubAction(action, str("kind"), str("id"))
    }

    fun snapshot(groups: List<List<ResourceItem>>): String = JsonArray(groups.flatten().map { item ->
        buildJsonObject {
            put("kind", item.kind); put("id", item.id); put("title", item.title)
            put("subtitle", item.subtitle); if (item.aliveSince == null) put("aliveSince", JsonNull) else put("aliveSince", item.aliveSince)
            put("canClose", item.canClose)
        }
    }).toString()

    fun close(kind: String, id: String, routes: Map<String, (String) -> String>): String =
        routes[kind]?.invoke(id) ?: "ERROR: 不支持的资源类型：$kind"
}

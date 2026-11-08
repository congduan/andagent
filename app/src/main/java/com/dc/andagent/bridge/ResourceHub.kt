package com.dc.andagent.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ResourceItem(
    val kind: String, val id: String, val title: String, val subtitle: String,
    val aliveSince: Long? = null, val canClose: Boolean = true,
)

/** Pure composition: callers collect each pool's snapshot before entering here. */
object ResourceHub {
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

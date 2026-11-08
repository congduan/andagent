package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 技能索引的一条记录。
 *
 * 官方索引 / 自定义索引 / GitHub 搜索三路结果最终都归一化成这个结构，
 * 由 [SkillRegistry.search] 合并去重后交给 UI 与模型。
 */
internal data class SkillIndexEntry(
    val name: String?,
    val description: String?,
    val source: String,
    val version: String? = null,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val tags: List<String> = emptyList(),
    val author: String? = null,
) {
    /** 仓库级归并键：用于和「已安装」比对（同一仓库的不同子路径视为同源）。 */
    val key: String get() = SkillSource.key(source)

    /** 条目级去重键：同一仓库里的多个技能必须保留为不同条目。 */
    val identity: String get() = source.trim().trimEnd('/').substringBefore('#').substringBefore('?').lowercase()

    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return listOfNotNull(name, description, author).any { it.contains(q, ignoreCase = true) } ||
            tags.any { it.contains(q, ignoreCase = true) } ||
            source.contains(q, ignoreCase = true)
    }
}

/**
 * 索引 JSON 解析。兼容两种形态：
 * - 顶层数组：`[{name, source, version, rating, ...}]`
 * - 顶层对象：`{"version": 1, "skills": [...]}`（官方索引用这个，便于以后加元字段）
 */
internal object SkillIndex {
    fun parse(text: String): List<SkillIndexEntry> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["skills"] as? JsonArray ?: root["items"] as? JsonArray
            else -> null
        } ?: return emptyList()
        return array.mapNotNull { parseEntry(it) }.distinctBy { it.identity }
    }

    fun parseEntry(element: JsonElement): SkillIndexEntry? {
        val obj = element as? JsonObject ?: return null
        val source = (obj["source"] ?: obj["repo"] ?: obj["url"] ?: obj["repository"])
            ?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = (obj["name"] ?: obj["id"])?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val tags = (obj["tags"] as? JsonArray)?.mapNotNull { it.stringOrNull() } ?: emptyList()
        return SkillIndexEntry(
            name = name,
            description = obj["description"]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            version = (obj["version"] ?: obj["latestVersion"])?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            rating = (obj["rating"] ?: obj["score"])?.doubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0,
            ratingCount = (obj["ratingCount"] ?: obj["ratings"] ?: obj["downloads"])?.intOrNull()?.coerceAtLeast(0) ?: 0,
            tags = tags,
            author = (obj["author"] ?: obj["owner"])?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
}

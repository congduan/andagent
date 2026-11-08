package com.dc.andagent.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * R96: 用户可配置的自定义搜索引擎。
 * 模板必须含 {query} 占位符且以 https:// 开头；{query} 用 URLEncoder 编码后替换，其余部分原样保留。
 */
@Serializable
data class CustomEngine(
    val name: String,
    val urlTemplate: String,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
)

/** 模板校验：null = 合法，否则返回中文错误原因。 */
fun validateTemplate(template: String): String? = when {
    !template.contains("{query}") -> "模板必须包含 {query} 占位符"
    !template.startsWith("https://") -> "模板必须以 https:// 开头（防明文）"
    else -> null
}

/** 用编码后的 query 替换 {query}，模板其余部分原样保留（不引入其它占位符解析）。 */
fun buildSearchUrl(template: String, query: String): String =
    template.replace("{query}", URLEncoder.encode(query, "UTF-8"))

/** 过滤出启用且模板合法的引擎。 */
fun usableEngines(engines: List<CustomEngine>): List<CustomEngine> =
    engines.filter { it.enabled && validateTemplate(it.urlTemplate) == null }

/** 默认引擎：isDefault 优先，否则首个；无可用引擎返回 null（走内置 Bing+DDG 回落）。 */
fun defaultEngine(usable: List<CustomEngine>): CustomEngine? =
    usable.firstOrNull { it.isDefault } ?: usable.firstOrNull()

/** SearXNG 风格 JSON：results[].title/url/content|snippet|abstract；兼容 Google CSE 的 items[].title/link/snippet。 */
fun parseJsonResults(body: String, engineName: String, limit: Int): List<WebResult> {
    val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
    val results = (root["results"] as? JsonArray ?: root["items"] as? JsonArray) ?: return emptyList()
    return results.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val title = obj.fieldText("title")
        val url = obj.fieldText("url").ifBlank { obj.fieldText("link") }
        if (title.isBlank() || !url.startsWith("http")) return@mapNotNull null
        val snippet = listOf("content", "snippet", "abstract")
            .map { obj.fieldText(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        WebResult(title.trim().take(200), url.trim(), snippet.take(500), engineName)
    }.distinctBy { it.url }.take(limit)
}

private val looseAnchor = Regex(
    "<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val nextAnchor = Regex("<a\\b", RegexOption.IGNORE_CASE)

/** 通用 HTML 模式：宽松提取 <a href="http(s)...">title</a> + 邻近文本做摘要（不复用 bing 专用 b_algo 正则）。 */
fun parseHtmlResults(html: String, engineName: String, limit: Int): List<WebResult> =
    looseAnchor.findAll(html).mapNotNull { m ->
        val url = WebHtml.decode(m.groupValues[1]).trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        val title = WebHtml.plain(m.groupValues[2])
        if (title.isBlank()) return@mapNotNull null
        val from = m.range.last + 1
        val tail = html.substring(from, minOf(html.length, from + 400))
        val window = nextAnchor.find(tail)?.let { tail.substring(0, it.range.first) } ?: tail
        val snippet = WebHtml.plain(window).take(200)
        WebResult(title.take(200), url, snippet, engineName)
    }.distinctBy { it.url }.take(limit).toList()

/** 双模式：先试 JSON（SearXNG / content-type JSON），0 结果或解析失败转 HTML 宽松解析。 */
fun parseCustomResults(body: String, engineName: String, limit: Int): List<WebResult> {
    val fromJson = runCatching { parseJsonResults(body, engineName, limit) }.getOrDefault(emptyList())
    return fromJson.ifEmpty { parseHtmlResults(body, engineName, limit) }
}

/** 回落时给内置结果数组首元素附上 fallback_from（对齐现有 diagnostic 字段做法）。 */
fun attachFallbackFrom(builtinJson: String, engineName: String): String {
    if (builtinJson.startsWith("ERROR")) return builtinJson
    return runCatching {
        val arr = Json.parseToJsonElement(builtinJson) as? JsonArray ?: return builtinJson
        if (arr.isEmpty()) return builtinJson
        val first = arr[0] as? JsonObject ?: return builtinJson
        val patched = buildJsonObject {
            first.forEach { (key, value) -> put(key, value) }
            put("fallback_from", engineName)
        }
        val out = ArrayList<JsonElement>(arr.size)
        out.add(patched)
        for (i in 1 until arr.size) out.add(arr[i])
        JsonArray(out).toString()
    }.getOrDefault(builtinJson)
}

private fun JsonObject.fieldText(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

/**
 * 读写 namespace=secure / key=search_engines（单键存 JSON 数组，≤64KB 天然够用）。
 * R96 review 必修项1：模板可能含 Google CSE API key 等敏感值，走 secure 命名空间——
 * AndroidKeyStore AES-256-GCM 落盘加密（R55 底座），kv_export all 永不含 secure，
 * kv_import 一律拒绝 secure——封死备份/导出/模型 kv_get 读平面。
 * 主构造用 load/save lambda，便于 JVM 单测注入（KvStore 依赖 SharedPreferences，不能在纯 JVM 测）。
 */
class SearchEngineStore(
    private val readRaw: () -> String?,
    private val writeRaw: (String) -> Unit = {},
) {
    constructor(kvStore: KvStore) : this(
        { kvStore.get(NAMESPACE, KEY)?.toString() },
        { raw -> kvStore.set(NAMESPACE, KEY, json.parseToJsonElement(raw)) },
    )

    fun load(): List<CustomEngine> {
        val raw = readRaw()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { decode(raw) }.getOrElse {
            // 兼容 value 被存成 JSON 字符串（而非数组本体）的历史写法。
            runCatching { decode(json.decodeFromString<String>(raw)) }.getOrDefault(emptyList())
        }
    }

    fun save(engines: List<CustomEngine>) = writeRaw(json.encodeToString(engines))

    private fun decode(s: String): List<CustomEngine> = json.decodeFromString(s)

    companion object {
        /** R96 review：secure 命名空间 = R55 加密底座，导出/备份永不含。 */
        const val NAMESPACE = KvStore.SECURE_NAMESPACE
        const val KEY = "engines"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

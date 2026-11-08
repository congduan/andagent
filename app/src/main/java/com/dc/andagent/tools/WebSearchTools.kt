package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val ANDROID_CHROME_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

data class WebResult(val title: String, val url: String, val snippet: String, val engine: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("title", title); put("url", url); put("snippet", snippet); put("engine", engine)
    }
}

/** Lightweight HTML handling for search snippets and readable page text. */
internal object WebHtml {
    private val resultStart = Regex("<li\\b[^>]*class\\s*=\\s*['\"][^'\"]*\\bb_algo\\b[^'\"]*['\"][^>]*>", RegexOption.IGNORE_CASE)
    private val header = Regex("<div\\b[^>]*class\\s*=\\s*['\"][^'\"]*\\bb_algoheader\\b[^'\"]*['\"][^>]*>(.*?)</div>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val anchor = Regex("<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val h2 = Regex("<h2\\b[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val paragraph = Regex("<p\\b[^>]*>(.*?)</p>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…")

    fun decode(s: String): String = Regex("&(#(?:x[0-9a-fA-F]+|[0-9]+)|[a-zA-Z]+);").replace(s) { m ->
        val key = m.groupValues[1]
        if (key.startsWith("#")) runCatching {
            val n = if (key.startsWith("#x", true)) key.drop(2).toInt(16) else key.drop(1).toInt()
            String(Character.toChars(n))
        }.getOrDefault(m.value) else entities[key.lowercase()] ?: m.value
    }

    fun plain(s: String): String = decode(s.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun bing(html: String, limit: Int): List<WebResult> {
        val starts = resultStart.findAll(html).toList()
        return starts.mapNotNull { match ->
            val end = html.indexOf("</li>", match.range.last + 1).takeIf { it >= 0 } ?: html.length
            val block = html.substring(match.range.first, end)
            val heading = header.find(block)?.groupValues?.get(1) ?: block
            // The attribution/cite link precedes the real result link in Bing's current layout.
            val resultAnchor = anchor.findAll(heading).firstOrNull { h2.containsMatchIn(it.groupValues[2]) }
                ?: h2.find(heading)?.let { anchor.find(it.groupValues[1]) }
            val url = resultAnchor?.groupValues?.get(1)?.let(::decode)?.trim() ?: return@mapNotNull null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            val title = plain(h2.find(resultAnchor.groupValues[2])?.groupValues?.get(1) ?: resultAnchor.groupValues[2])
            if (title.isBlank()) return@mapNotNull null
            val snippet = plain(paragraph.find(block)?.groupValues?.get(1) ?: "")
            WebResult(title, url, snippet, "bing")
        }.distinctBy { it.url }.take(limit)
    }

    fun readable(html: String, maxChars: Int): String {
        var s = html
        for (tag in listOf("script", "style", "svg", "noscript", "nav", "footer", "header")) {
            s = s.replace(Regex("<$tag\\b[^>]*>.*?</$tag\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        }
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(Regex("<(?:br|/p|/div|/article|/section|/h[1-6]|/li)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        return decode(s.replace(Regex("<[^>]*>"), " "))
            .replace(Regex("[ \\t\\r\\f]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim().take(maxChars)
    }
}

class WebSearchTool(
    override val name: String,
    private val engineStore: SearchEngineStore = SearchEngineStore({ null }, {}),
    private val fetcher: ((String) -> String)? = null,
) : Tool {
    /** 生产入口：复用 MainActivity 传入的 KvStore（共享 andagent_kv prefs，不新建存储实例）。 */
    constructor(name: String, kvStore: KvStore) : this(name, SearchEngineStore(kvStore))

    override val description = if (name == "web_search")
        "无需 API key 搜索互联网。默认 Bing 网页搜索为主、DuckDuckGo 即时答案补充；可在「设置 → 搜索引擎」配置自定义引擎（含自建 SearXNG），用 engine 参数指定名称可只走该引擎；自定义引擎不可达或 0 结果时自动回落内置 Bing+DDG 并附 fallback_from。返回标题、链接、摘要及来源。时效性问题先搜索。"
    else "读取 HTTPS 网页正文，去除脚本、样式和标签；最多返回 max_chars 字符。"
    override val parameters = if (name == "web_search") schemaOf(
        "query" to strParam("搜索词"), "limit" to intParam("结果上限，默认 8，最多 20"),
        "mkt" to strParam("Bing 市场，默认 zh-CN"),
        "engine" to strParam("可选：指定设置页配置的自定义搜索引擎名称；省略则用默认/首个启用的自定义引擎；指定了不存在的名称会直接报错（不发请求）；无配置时忽略"),
        required = listOf("query"),
    ) else schemaOf("url" to strParam("HTTPS 网页地址"), "max_chars" to intParam("最大正文字数，默认 8000，最多 20000"), required = listOf("url"))

    // R96 review 必修项2：禁 OkHttp 自动跟随重定向（followRedirects=false）——
    // 默认实现会在校验前就随 30x 跳到下一跳，模板含 API key 时 key 可能已发往明文
    // http 目标。改为逐跳手动跟随：仅对 3xx 解析 Location，发起下一跳前校验仍是 https。
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    override suspend fun execute(args: JsonObject): String = runCatching {
        if (name == "web_search") search(args) else read(args)
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    /** 测试可注入 fetcher，生产为 null 时走真实 OkHttp。 */
    private fun fetch(url: String): String = fetcher?.invoke(url) ?: realFetch(url)

    private fun realFetch(url: String): String {
        // 逐跳手动跟随：Location 相对路径按当前 URL 解析，非 https 下一跳直接拒绝。
        var currentUrl = url
        repeat(MAX_REDIRECTS) {
            val call = http.newCall(Request.Builder().url(currentUrl).header("User-Agent", ANDROID_CHROME_UA)
                .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.8").build())
            call.execute().use { response ->
                if (!currentUrl.startsWith("https://", ignoreCase = true)) error("拒绝非 HTTPS 请求：$currentUrl")
                val loc = response.header("Location")
                val isRedirect = response.code in 300..399
                if (!isRedirect) {
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("空响应")
                    // Bound memory for unusually large pages.
                    val bytes = body.byteStream().use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (out.size() < 1_000_000) {
                            val n = stream.read(buf, 0, minOf(buf.size, 1_000_000 - out.size()))
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                    return String(bytes, body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                }
                // R96: 3xx 必须带 Location，缺失视为协议错误而非静默吞掉。
                if (loc.isNullOrBlank()) error("HTTP ${response.code} 重定向缺少 Location")
                currentUrl = java.net.URI(currentUrl).resolve(loc).toString()
                if (!currentUrl.startsWith("https://", ignoreCase = true)) error("重定向到非 HTTPS 页面")
            }
        }
        error("重定向次数超过 $MAX_REDIRECTS")
    }

    private companion object {
        /** 与浏览器实践对齐的重定向上限；逐跳 https 校验见 [realFetch]。 */
        const val MAX_REDIRECTS = 5
    }

    private fun search(args: JsonObject): String {
        val query = args.strArg("query") ?: error("缺少 query")
        val limit = (args.intArg("limit") ?: 8).coerceIn(1, 20)
        val mkt = args.strArg("mkt")?.takeIf { it.matches(Regex("[A-Za-z]{2}-[A-Za-z]{2}")) } ?: "zh-CN"
        // R96 review 必修项3：显式指定 engine 且配置里没有时，明确报错——
        // 不允许静默改用默认引擎（请求会发往用户未指定的第三方引擎）。
        val usable = usableEngines(engineStore.load())
        val requested = args.strArg("engine")?.trim()?.takeIf { it.isNotEmpty() }
        if (requested != null) {
            val hit = usable.firstOrNull { it.name.equals(requested, ignoreCase = true) }
                ?: return "ERROR: 未找到名为「$requested」的自定义搜索引擎（设置 → 搜索引擎 中配置）；web_search 未执行，未产生网络请求"
            // R96 review 必修项3 编译收口：searchVia 回落内置搜索需带 mkt（与默认引擎分支一致）。
            return searchVia(hit, query, limit, mkt)
        }
        val engine = defaultEngine(usable)
        // 无配置 / 全部停用 / 模板非法 → 现有 Bing+DDG 路径原样，不叠加自定义引擎。
        if (engine == null) return builtinSearch(query, limit, mkt)
        return searchVia(engine, query, limit, mkt)
    }

    /** 经单个引擎搜索：不可达或 0 结果回落内置 Bing+DDG，首元素附 fallback_from 提示。 */
    private fun searchVia(engine: CustomEngine, query: String, limit: Int, mkt: String): String {
        val custom = runCatching {
            val body = fetch(buildSearchUrl(engine.urlTemplate, query))
            parseCustomResults(body, engine.name, limit)
        }
        val customResults = custom.getOrNull().orEmpty()
        if (customResults.isNotEmpty()) {
            // 自定义引擎可用 → 只走该引擎，结果 engine 字段为自定义引擎名。
            return JsonArray(customResults.map { it.toJson() }).toString()
        }
        return attachFallbackFrom(builtinSearch(query, limit, mkt), engine.name)
    }

    /** 内置 Bing HTML + DDG IA 路径（逻辑与改造前一致；fetch 经注入点便于 JVM 测）。 */
    private fun builtinSearch(query: String, limit: Int, mkt: String): String {
        val q = URLEncoder.encode(query, "UTF-8")
        var diagnostic = ""
        val bing = runCatching {
            val html = fetch("https://www.bing.com/search?q=$q&count=$limit&mkt=$mkt")
            val found = WebHtml.bing(html, limit)
            if (found.isEmpty()) diagnostic = "Bing 页面无可解析结果；b_algo 块数=${Regex("<li\\b[^>]*\\bb_algo\\b", RegexOption.IGNORE_CASE).findAll(html).count()}"
            found
        }.getOrElse { diagnostic = "Bing: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
        val ddg = runCatching {
            val data = Json.parseToJsonElement(fetch("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")) as JsonObject
            val results = mutableListOf<WebResult>()
            val abstract = data.text("AbstractText")
            val abstractUrl = data.text("AbstractURL")
            if (abstract.isNotBlank() && abstractUrl.startsWith("http")) results += WebResult(data.text("Heading").ifBlank { query }, abstractUrl, abstract, "ddg_ia")
            fun addTopics(items: JsonArray?) {
                items?.forEach { item ->
                    val obj = item as? JsonObject ?: return@forEach
                    val text = obj.text("Text")
                    val url = obj.text("FirstURL")
                    if (text.isNotBlank() && url.startsWith("http")) results += WebResult(text.substringBefore(" - ").take(120), url, text.take(500), "ddg_ia")
                    addTopics(obj["Topics"] as? JsonArray)
                }
            }
            addTopics(data["RelatedTopics"] as? JsonArray)
            results
        }.getOrElse { if (diagnostic.isNotBlank()) diagnostic += "; "; diagnostic += "DDG: ${it.message ?: it.javaClass.simpleName}"; emptyList() }
        val results = (bing + ddg).distinctBy { it.url }.take(limit)
        if (results.isEmpty()) return "ERROR: 搜索无结果。$diagnostic"
        return JsonArray(results.mapIndexed { index, result ->
            if (index == 0 && bing.isEmpty() && diagnostic.isNotBlank()) buildJsonObject {
                result.toJson().forEach { (key, value) -> put(key, value) }
                put("diagnostic", diagnostic)
            } else result.toJson()
        }).toString()
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun read(args: JsonObject): String {
        val url = args.strArg("url") ?: error("缺少 url")
        val uri = java.net.URI(url)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "仅支持 HTTPS 网页" }
        val maxChars = (args.intArg("max_chars") ?: 8000).coerceIn(1, 20_000)
        val content = WebHtml.readable(fetch(url), maxChars)
        if (content.isBlank()) error("网页无可读正文")
        return buildJsonObject { put("url", url); put("text", content) }.toString()
    }
}

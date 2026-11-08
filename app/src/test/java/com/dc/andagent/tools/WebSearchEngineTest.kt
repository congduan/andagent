package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * R96: 自定义搜索引擎纯函数 + 行为锁。
 * 用 SearchEngineStore 的 load/save lambda 与 WebSearchTool 的 fetcher 注入，
 * 全程不依赖 SharedPreferences / 真实网络，可在纯 JVM 跑。
 */
class WebSearchEngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val SEARX_TEMPLATE = "https://searx.example/search?q={query}&format=json"
    private val SEARX_CONFIG =
        """[{"name":"MySearX","urlTemplate":"https://searx.example/search?q={query}&format=json","enabled":true,"isDefault":true}]"""
    private val TWO_ENGINE_CONFIG = """[
        {"name":"Alpha","urlTemplate":"https://alpha.example/s?q={query}","enabled":true,"isDefault":true},
        {"name":"Beta","urlTemplate":"https://beta.example/s?q={query}","enabled":true,"isDefault":false}
    ]"""
    private val BING_HTML = """
        <li class="b_algo"><div class="b_algoheader"><a href="https://example.com/a"><h2>Result A</h2></a></div><p>Snippet A</p></li>
        <li class="b_algo"><div class="b_algoheader"><a href="https://example.com/b"><h2>Result B</h2></a></div><p>Snippet B</p></li>
    """
    private val DDG_EMPTY = """{"AbstractText":"","AbstractURL":"","Heading":"","RelatedTopics":[]}"""
    private val SEARX_JSON = """{"results":[
        {"title":"Kotlin Lang","url":"https://kotlinlang.org/","content":"Kotlin programming language"},
        {"title":"Kotlin Docs","url":"https://kotlinlang.org/docs/","content":"Reference docs"}
    ]}"""
    private val HTML_FIXTURE = """
        <html><body>
        <div class="result"><a href="https://one.example/page">First &amp; Best</a><p>A short summary here.</p></div>
        <div class="result"><a href="https://two.example/page">Second Result</a><span>Another nearby text snippet.</span></div>
        <a href="/relative">Ignored relative</a>
        <a href="ftp://bad.example">Ignored ftp</a>
        </body></html>
    """

    private fun argsOf(vararg pairs: Pair<String, String>) = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    private fun storeReturning(raw: String?) = SearchEngineStore({ raw }, {})

    /** 记录被请求的 URL；自定义引擎命中返回给定 body，Bing/DDG 返回可解析的内置结果。 */
    private fun fakeFetch(customBody: (String) -> String, seen: MutableList<String>): (String) -> String = { url ->
        seen += url
        when {
            !url.startsWith("https://www.bing.com/") && !url.startsWith("https://api.duckduckgo.com/") -> customBody(url)
            url.startsWith("https://www.bing.com/") -> BING_HTML
            else -> DDG_EMPTY
        }
    }

    private fun firstEngine(out: String): String? =
        (Json.parseToJsonElement(out) as? JsonArray)?.firstOrNull()?.jsonObject?.get("engine")?.jsonPrimitive?.contentOrNull

    private fun firstFallbackFrom(out: String): String? =
        (Json.parseToJsonElement(out) as? JsonArray)?.firstOrNull()?.jsonObject?.get("fallback_from")?.jsonPrimitive?.contentOrNull

    /* ------------------------- 模板校验 / URL 编码 ------------------------- */

    @Test fun templateRejectsMissingQuery() {
        assertNotNull(validateTemplate("https://searx.example/search?q=fixed"))
    }

    @Test fun templateRejectsHttp() {
        assertNotNull(validateTemplate("http://searx.example/search?q={query}"))
    }

    @Test fun templateAcceptsHttpsSearxng() {
        assertNull(validateTemplate(SEARX_TEMPLATE))
    }

    @Test fun buildUrlEncodesQueryAndKeepsRestIntact() {
        assertEquals(
            "https://searx.example/search?q=a+b%26c&format=json",
            buildSearchUrl(SEARX_TEMPLATE, "a b&c"),
        )
    }

    /* ---------------------------- JSON 解析模式 ---------------------------- */

    @Test fun jsonParseSearxngShape() {
        val results = parseCustomResults(SEARX_JSON, "MySearX", 8)
        assertEquals(2, results.size)
        assertEquals("Kotlin Lang", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Kotlin programming language", results[0].snippet)
        assertEquals("MySearX", results[0].engine)
    }

    @Test fun jsonParseLooseFieldNames() {
        val body = """{"results":[
            {"title":"A","url":"https://a.example/","snippet":"from snippet"},
            {"title":"B","url":"https://b.example/","abstract":"from abstract"},
            {"title":"C","url":"https://c.example/","content":"from content"}
        ]}"""
        val results = parseJsonResults(body, "X", 8)
        assertEquals(3, results.size)
        assertEquals("from snippet", results[0].snippet)
        assertEquals("from abstract", results[1].snippet)
        assertEquals("from content", results[2].snippet)
    }

    @Test fun jsonParseDropsInvalidAndDeduplicates() {
        val body = """{"results":[
            {"title":"A","url":"https://a.example/","content":"x"},
            {"title":"dup","url":"https://a.example/","content":"y"},
            {"title":"","url":"https://empty.example/","content":"z"},
            {"title":"bad","url":"ftp://x/","content":"w"}
        ]}"""
        val results = parseJsonResults(body, "X", 8)
        assertEquals(1, results.size)
        assertEquals("https://a.example/", results[0].url)
    }

    /* ---------------------------- HTML 宽松模式 ---------------------------- */

    @Test fun htmlLooseParseExtractsAnchorsWithSnippets() {
        val results = parseCustomResults(HTML_FIXTURE, "MyEngine", 8)
        assertEquals(2, results.size)
        assertEquals("https://one.example/page", results[0].url)
        assertEquals("First & Best", results[0].title)
        assertTrue(results[0].snippet.contains("A short summary"))
        assertEquals("MyEngine", results[0].engine)
        assertEquals("https://two.example/page", results[1].url)
    }

    /* ------------------------------ 引擎选择 ------------------------------ */

    @Test fun defaultEnginePrefersDefault() {
        val engines = json.decodeFromString<List<CustomEngine>>(TWO_ENGINE_CONFIG)
        assertEquals("Alpha", defaultEngine(usableEngines(engines))?.name)
    }

    @Test fun defaultEngineFirstEnabledWhenNoDefault() {
        val engines = listOf(
            CustomEngine("Off", SEARX_TEMPLATE, enabled = false, isDefault = true),
            CustomEngine("On", SEARX_TEMPLATE, enabled = true, isDefault = false),
        )
        assertEquals("On", defaultEngine(usableEngines(engines))?.name)
    }

    @Test fun usableEnginesByNameIsCaseInsensitive() {
        val engines = json.decodeFromString<List<CustomEngine>>(TWO_ENGINE_CONFIG)
        assertEquals("Beta", usableEngines(engines).firstOrNull { it.name.equals("beta", ignoreCase = true) }?.name)
    }

    @Test fun defaultEngineReturnsNullWhenAllDisabled() {
        val engines = listOf(CustomEngine("Off", SEARX_TEMPLATE, enabled = false, isDefault = true))
        assertNull(defaultEngine(usableEngines(engines)))
    }

    @Test fun usableEnginesSkipsInvalidTemplate() {
        val engines = listOf(
            CustomEngine("Bad", "http://x/{query}", enabled = true, isDefault = true),
            CustomEngine("Good", SEARX_TEMPLATE, enabled = true),
        )
        assertEquals("Good", defaultEngine(usableEngines(engines))?.name)
    }

    /* --------------------------- Store 序列化边界 --------------------------- */

    @Test fun storeLoadsJsonArray() {
        val store = storeReturning(SEARX_CONFIG)
        assertEquals(1, store.load().size)
        assertEquals("MySearX", store.load().first().name)
        assertTrue(store.load().first().isDefault)
    }

    @Test fun storeReturnsEmptyOnNullAndMalformed() {
        assertTrue(storeReturning(null).load().isEmpty())
        assertTrue(storeReturning("not json").load().isEmpty())
    }

    @Test fun storeRoundTripSavesJsonArray() {
        var saved: String? = null
        val store = SearchEngineStore({ null }, { saved = it })
        store.save(listOf(CustomEngine("N", SEARX_TEMPLATE)))
        assertNotNull(saved)
        assertTrue(saved!!.startsWith("[{"))
        assertEquals("N", json.decodeFromString<List<CustomEngine>>(saved!!).first().name)
    }

    /* ------------------------------ fallback ------------------------------ */

    @Test fun attachFallbackFromAddsFieldToFirstElement() {
        val builtin = JsonArray(
            listOf(
                WebResult("A", "https://a.example/", "s", "bing").toJson(),
                WebResult("B", "https://b.example/", "s", "ddg_ia").toJson(),
            ),
        ).toString()
        val out = attachFallbackFrom(builtin, "MySearX")
        assertEquals("MySearX", firstFallbackFrom(out))
        assertEquals("bing", firstEngine(out))
        // 第二个元素保持原样，不带 fallback_from。
        val second = (Json.parseToJsonElement(out) as JsonArray)[1].jsonObject
        assertNull(second["fallback_from"])
    }

    @Test fun attachFallbackFromLeavesErrorStringUntouched() {
        assertEquals("ERROR: 搜索无结果。", attachFallbackFrom("ERROR: 搜索无结果。", "X"))
    }

    /* --------------------- 经注入 load+fetcher 的行为锁 --------------------- */

    @Test fun zeroCustomResultsFallsBackToBuiltinWithFallbackFrom() = runBlocking {
        val seen = mutableListOf<String>()
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(SEARX_CONFIG),
            fetcher = fakeFetch({ """{"results":[]}""" }, seen),
        )
        val out = tool.execute(argsOf("query" to "kotlin"))
        assertEquals("bing", firstEngine(out))
        assertEquals("MySearX", firstFallbackFrom(out))
        assertTrue(seen.any { it.startsWith("https://searx.example/") })
        assertTrue(seen.any { it.startsWith("https://www.bing.com/") })
    }

    @Test fun customEngineNetworkErrorFallsBackToBuiltinWithFallbackFrom() = runBlocking {
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(SEARX_CONFIG),
            fetcher = fakeFetch({ throw IOException("offline") }, mutableListOf()),
        )
        val out = tool.execute(argsOf("query" to "kotlin"))
        assertEquals("bing", firstEngine(out))
        assertEquals("MySearX", firstFallbackFrom(out))
    }

    @Test fun workingCustomEngineUsesOnlyThatEngine() = runBlocking {
        val seen = mutableListOf<String>()
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(SEARX_CONFIG),
            fetcher = fakeFetch({ SEARX_JSON }, seen),
        )
        val out = tool.execute(argsOf("query" to "kotlin"))
        assertEquals("MySearX", firstEngine(out))
        assertNull(firstFallbackFrom(out))
        assertFalse(out.contains("\"bing\""))
        assertFalse(seen.any { it.startsWith("https://www.bing.com/") })
    }

    @Test fun engineArgSelectsNamedCustomEngine() = runBlocking {
        val seen = mutableListOf<String>()
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(TWO_ENGINE_CONFIG),
            fetcher = fakeFetch({ SEARX_JSON }, seen),
        )
        val out = tool.execute(argsOf("query" to "kotlin", "engine" to "Beta"))
        assertEquals("Beta", firstEngine(out))
        assertTrue(seen.any { it.startsWith("https://beta.example/") })
        assertFalse(seen.any { it.startsWith("https://alpha.example/") })
    }

    /** R96 review 必修项3：指名 engine 不存在 → 明确报错，绝不静默改用默认引擎发起请求。 */
    @Test fun unknownEngineArgFailsWithoutAnyRequest() = runBlocking {
        val seen = mutableListOf<String>()
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(TWO_ENGINE_CONFIG),
            fetcher = fakeFetch({ SEARX_JSON }, seen),
        )
        val out = tool.execute(argsOf("query" to "kotlin", "engine" to "NoSuchEngine"))
        assertTrue(out.startsWith("ERROR:"))
        assertTrue(out.contains("NoSuchEngine"))
        assertTrue(seen.isEmpty())
    }

    @Test fun noConfigKeepsBuiltinPathUnchanged() = runBlocking {
        val seen = mutableListOf<String>()
        val tool = WebSearchTool(
            name = "web_search", engineStore = storeReturning(null),
            fetcher = fakeFetch({ SEARX_JSON }, seen),
        )
        val out = tool.execute(argsOf("query" to "kotlin"))
        assertEquals("bing", firstEngine(out))
        assertNull(firstFallbackFrom(out))
        assertFalse(seen.any { it.startsWith("https://searx.example/") })
        val arr = Json.parseToJsonElement(out) as JsonArray
        assertEquals(2, arr.size)
    }
}

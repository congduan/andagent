package com.dc.andagent.shell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R25: /json 解析与 CDP 消息封装的静态 JSON 断言（JVM 纯逻辑，不触碰 socket）。 */
class DevToolsClientTest {

    // 真机 WebView /json 返回形状（截取自 chrome devtools；字段顺序无关）。
    private val jsonBody = """
        [ {
           "description": "",
           "devtoolsFrontendUrl": "/devtools/inspector.html?ws=...",
           "id": "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4",
           "title": "AndAgent",
           "type": "page",
           "url": "https://appassets.androidplatform.net/assets/web/index.html",
           "webSocketDebuggerUrl": "ws://1a2b3c4d-1234-5678-90ab-cdef12345678.tools.chrome.devtools.local/devtools/page/A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4"
        }, {
           "description": "",
           "id": "F1E2D3C4B5A6F1E2D3C4B5A6F1E2D3C4",
           "title": "示例页面",
           "type": "page",
           "url": "https://example.com/",
           "webSocketDebuggerUrl": "ws://localhost/devtools/page/F1E2D3C4B5A6F1E2D3C4B5A6F1E2D3C4"
        }, {
           "title": "ServiceWorker",
           "type": "service_worker",
           "url": "https://example.com/sw.js",
           "webSocketDebuggerUrl": "ws://localhost/devtools/page/S1S2S3S4S5S6",
           "id": "S1S2S3S4S5S6"
        } ]
    """.trimIndent()

    @Test
    fun parsesPagesAndKeepsAllTargetTypes() {
        val pages = parseDevToolsPages(jsonBody)
        assertEquals(3, pages.size)
        val first = pages[0]
        assertEquals("A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4", first.id)
        assertEquals("page", first.type)
        assertEquals("AndAgent", first.title)
        assertEquals("https://appassets.androidplatform.net/assets/web/index.html", first.url)
        assertTrue(first.webSocketDebuggerUrl.endsWith("/devtools/page/A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4"))
        // 非 page 的 target 保留，由 agent 自行选择（DB GUI/嵌入页等场景）。
        assertEquals("service_worker", pages[2].type)
        assertEquals("https://example.com/sw.js", pages[2].url)
    }

    @Test
    fun parseRejectsNonArrayAndSkipsEntriesWithoutId() {
        // /json 返回必须是数组：对象体/非 JSON 体 → 抛错（错误串会带线索进工具结果）。
        assertTrue(runCatching { parseDevToolsPages("{\"error\":\"no pages\"}") }.isFailure)
        assertTrue(runCatching { parseDevToolsPages("not json") }.isFailure)
        val partial = """[ {"title":"no id"}, {"id":"X1","type":"page","url":"about:blank","title":"t","webSocketDebuggerUrl":"ws://h/devtools/page/X1"} ]"""
        val pages = parseDevToolsPages(partial)
        assertEquals(1, pages.size)
        assertEquals("X1", pages[0].id)
    }

    @Test
    fun buildsCdpRequestEnvelope() {
        val noParams = buildCdpRequest(1, "Runtime.enable", null)
        assertEquals("""{"id":1,"method":"Runtime.enable"}""", noParams)

        val withParams = Json.parseToJsonElement(
            buildCdpRequest(7, "Runtime.evaluate",
                Json.parseToJsonElement("""{"expression":"1+1","returnByValue":true}""").jsonObject),
        ).jsonObject
        assertEquals(7, withParams["id"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Runtime.evaluate", withParams["method"]?.jsonPrimitive?.content)
        val params = withParams["params"]?.jsonObject
        assertEquals("1+1", params?.get("expression")?.jsonPrimitive?.content)
        assertEquals("true", params?.get("returnByValue")?.jsonPrimitive?.content)
    }

    @Test
    fun extractsWebSocketPathAndFallsBackToPageId() {
        assertEquals(
            "/devtools/page/A1B2C3D4",
            wsDebuggerPath("ws://whatever.tools.chrome.devtools.local/devtools/page/A1B2C3D4", "unused"),
        )
        // http 形式（部分 WebView 版本返回 http:// 而非 ws://）
        assertEquals("/devtools/page/X9", wsDebuggerPath("http://localhost/devtools/page/X9", "unused"))
        // 无 path / 空 URL → 回退
        assertEquals("/devtools/page/FALLBACK", wsDebuggerPath("ws://host-only", "FALLBACK"))
        assertEquals("/devtools/page/FALLBACK", wsDebuggerPath("", "FALLBACK"))
    }

    @Test
    fun formatsConsoleEvents() {
        val called = Json.parseToJsonElement(
            """{"type":"error","args":[
                 {"type":"string","value":"boom"},
                 {"type":"number","value":42},
                 {"type":"object","className":"TypeError","description":"TypeError: x is not a function"}]}""",
        ).jsonObject
        assertEquals(
            "error: boom 42 TypeError: x is not a function",
            formatConsoleEvent("Runtime.consoleAPICalled", called),
        )

        val thrown = Json.parseToJsonElement(
            """{"exceptionDetails":{"text":"Uncaught","exception":{"type":"object","description":"ReferenceError: y is not defined\n    at <anonymous>:1:1"}}}""",
        ).jsonObject
        assertEquals(
            "exception: ReferenceError: y is not defined\n    at <anonymous>:1:1",
            formatConsoleEvent("Runtime.exceptionThrown", thrown),
        )

        // 其他事件不产生 console 行。
        assertNull(formatConsoleEvent("Network.loadingFinished", Json.parseToJsonElement("{}").jsonObject))
        assertNull(formatConsoleEvent("Runtime.exceptionThrown", Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun consoleEntryGroupsLevelsAndCarriesLineNumbers() {
        val warn = Json.parseToJsonElement(
            """{"type":"warning","args":[{"type":"string","value":"deprecated"}]}""",
        ).jsonObject
        assertEquals(ConsoleLevel.WARN, consoleEntry("Runtime.consoleAPICalled", warn)!!.level)

        val log = Json.parseToJsonElement(
            """{"type":"log","args":[{"type":"string","value":"ready"}]}""",
        ).jsonObject
        assertEquals(ConsoleLevel.LOG, consoleEntry("Runtime.consoleAPICalled", log)!!.level)

        val thrown = Json.parseToJsonElement(
            """{"exceptionDetails":{"text":"Uncaught","lineNumber":4,"exception":{"type":"object","description":"TypeError: bad"}}}""",
        ).jsonObject
        val exc = consoleEntry("Runtime.exceptionThrown", thrown)!!
        assertEquals(ConsoleLevel.ERROR, exc.level)
        assertEquals(5, exc.lineNumber) // CDP 0-based → 1-based

        val withStack = Json.parseToJsonElement(
            """{"type":"error","args":[{"type":"string","value":"oops"}],"stackTrace":{"callFrames":[{"lineNumber":9,"url":"x.js"}]}}""",
        ).jsonObject
        assertEquals(10, consoleEntry("Runtime.consoleAPICalled", withStack)!!.lineNumber)

        assertNull(consoleEntry("Network.loadingFinished", Json.parseToJsonElement("{}").jsonObject))
        assertNull(consoleEntry("Log.entryAdded", Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun logEntryAddedReplaysPageLoadErrors() {
        // Log.enable 补发 attach 前的历史条目 → 页面加载期报错可被捕获。
        val params = Json.parseToJsonElement(
            """{"entry":{"source":"javascript","level":"error","text":"Uncaught ReferenceError: x is not defined","url":"https://appassets.androidplatform.net/local/page_1.html","lineNumber":11}}""",
        ).jsonObject
        val e = consoleEntry("Log.entryAdded", params)!!
        assertEquals(ConsoleLevel.ERROR, e.level)
        assertEquals("error", e.tag)
        assertEquals(12, e.lineNumber)
        assertTrue(e.text.contains("Uncaught ReferenceError"))
        assertTrue(e.text.contains("page_1.html"))
        val legacy = formatConsoleEvent("Log.entryAdded", params)!!
        assertTrue(legacy.startsWith("error:"))
        assertTrue(legacy.contains("(line 12)"))

        val warning = Json.parseToJsonElement(
            """{"entry":{"level":"warning","text":"slow network"}}""",
        ).jsonObject
        assertEquals(ConsoleLevel.WARN, consoleEntry("Log.entryAdded", warning)!!.level)
    }

    @Test
    fun scansDevToolsSocketNamesFromProcNetUnix() {
        val content = listOf(
            "Num       RefCount Protocol Flags    Stype Path",
            "0000000000000000: 00000002 00000000 00010000 0001 1 /dev/socket/xyz",
            "0000000000000000: 00000003 00000000 00010000 0005 1 @webview_devtools_remote_12345",
            "0000000000000000: 00000002 00000000 00010000 0001 1 @chrome_devtools_remote",
            "0000000000000000: 00000002 00000000 00010000 0001 3 @webview_devtools_remote_12345",
        ).joinToString("\n")
        assertEquals(
            listOf("webview_devtools_remote_12345", "chrome_devtools_remote"),
            scanDevToolsSocketNames(content),
        )
    }
}

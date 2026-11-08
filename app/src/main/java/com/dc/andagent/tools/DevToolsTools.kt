package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.shell.ConsoleEntry
import com.dc.andagent.shell.ConsoleLevel
import com.dc.andagent.shell.DevToolsClient
import com.dc.andagent.shell.DevToolsPage
import com.dc.andagent.shell.devtoolsScreenshotJson
import com.dc.andagent.shell.devtoolsStatusJson
import com.dc.andagent.shell.setDevToolsDebugging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * R25: DevTools 桥接工具。agent 经 CDP 程序化检查/控制本 app 的 WebView
 *（含 web_embed 与 DB GUI 打开的页面）。
 */
class DevToolsTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "devtools_status" -> "查询 WebView 远程调试开关状态与可调试页面列表（序号/标题/URL）。"
        "devtools_debugging" -> "运行时开关 WebView 远程调试（release 包同样生效，持久化到设置）。开启后 devtools_* 工具与 USB chrome://inspect 均可用。"
        "devtools_eval" -> "在指定页面执行 JavaScript 并返回结果（awaitPromise，可做 DOM 检查/抓取内容）。page 为 devtools_status 的序号或 URL/标题关键词，缺省优先 web_embed 生成的 /local/ 本地页面；web_embed 打开的页面可用此工具检查。"
        "devtools_console" -> "读取指定页面的 console 输出（log/warn/error 与未捕获异常）。从 attach 起实时监听 Runtime + Log 域，Log.enable 会补发页面加载期报错，无需先 devtools_eval 制造输出；按 error/warn/log 分组返回，错误带行号。"
        "devtools_errors" -> "只返回指定页面的 error 级 console/异常（含加载期报错，按行号）。排查「生成网页→嵌入→查错→修」工作流里的加载失败很方便。"
        else -> "截取指定页面当前画面为 PNG 存入相册 Pictures/AndAgent；缩略图由工具卡片展示。"
    }
    override val parameters = when (name) {
        "devtools_status" -> schemaOf()
        "devtools_debugging" -> schemaOf("enable" to boolParam("true 开启 / false 关闭"), required = listOf("enable"))
        "devtools_console" ->
            schemaOf(
                "page" to strParam("页面序号或 URL/标题关键词，缺省优先 web_embed 生成的 /local/ 本地页面"),
                "lines" to intParam("返回最近行数，默认 50，上限 300"),
            )
        "devtools_errors" ->
            schemaOf(
                "page" to strParam("页面序号或 URL/标题关键词，缺省优先 web_embed 生成的 /local/ 本地页面"),
                "lines" to intParam("返回最近 error 行数，默认 50，上限 300"),
            )
        "devtools_screenshot" ->
            schemaOf("page" to strParam("页面序号或 URL/标题关键词，缺省优先 web_embed 生成的 /local/ 本地页面"))
        else ->
            schemaOf(
                "page" to strParam("页面序号或 URL/标题关键词，缺省优先 web_embed 生成的 /local/ 本地页面"),
                "js" to strParam("要执行的 JavaScript 表达式或语句"),
                required = listOf("js"),
            )
    }

    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "devtools_status" -> devtoolsStatusJson(context)
            "devtools_debugging" -> setDevToolsDebugging(context, args.boolArg("enable") ?: error("缺少 enable"))
            "devtools_eval" -> eval(args)
            "devtools_console" -> console(args)
            "devtools_errors" -> errors(args)
            else -> screenshot(args)
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun pageJson(page: DevToolsPage): JsonObject = buildJsonObject {
        put("type", page.type)
        put("title", page.title.ifBlank { page.url })
        put("url", page.url)
    }

    private suspend fun eval(args: JsonObject): String {
        val js = args.strArg("js") ?: error("缺少 js")
        val page = DevToolsClient.resolvePage(args.strArg("page"))
        // R96 review 二轮必修项1：appassets 域页面按白名单拦模型注入——主 shell
        //（持有 __kvSettingToken）与 dbgui（挂 DbGuiBridge）一律拒；/local/ 暂存页
        // 跑在无桥的独立 WebView，放行以保住 R101 网页开发闭环。同源 iframe 通道
        // 已在 WebViewCard 封死（appassets 域拒绝进卡片）。
        if (!DevToolsClient.canModelExecutePage(page)) {
            error("安全策略：禁止在 app 内部页面（${page.url.take(120)}）执行模型提供的 JS——该上下文可访问应用凭据或特权桥。本地网页（/local/）不受限。")
        }
        val resp = DevToolsClient.evaluate(page, js)
        val result = resp["result"] as? JsonObject
        val exception = result?.get("exceptionDetails") as? JsonObject
        val remote = result?.get("result") as? JsonObject
        return buildJsonObject {
            put("page", pageJson(page))
            resp["error"]?.let { put("cdpError", it.toString().take(300)) }
            if (exception != null) {
                put(
                    "exception",
                    buildJsonObject {
                        put("text", (exception["text"]?.jsonPrimitive?.contentOrNull ?: "Uncaught"))
                        (exception["exception"] as? JsonObject)?.get("description")?.jsonPrimitive?.contentOrNull
                            ?.take(2000)?.let { put("description", it) }
                        exception["lineNumber"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }?.let { put("line", it + 1) }
                    },
                )
            } else if (remote != null) {
                put("type", remote["type"]?.jsonPrimitive?.contentOrNull ?: "undefined")
                val value = remote["value"]
                if (value is JsonPrimitive) {
                    val raw = value.contentOrNull ?: value.toString()
                    if (raw.length > 20_000) put("value", raw.take(20_000) + "…(截断，原 ${raw.length} 字符)")
                    else put("value", raw)
                } else if (value != null) {
                    val raw = value.toString()
                    if (raw.length > 20_000) put("value", raw.take(20_000) + "…(截断，原 ${raw.length} 字符)")
                    else put("value", value)
                } else {
                    put("description", remote["description"]?.jsonPrimitive?.contentOrNull?.take(2000) ?: "undefined")
                }
            }
        }.toString()
    }

    /** 单条 console 的结构化 JSON：level 供卡片按 error(红)/warn(黄)/log(灰) 着色，错误带 line。 */
    private fun entryJson(e: ConsoleEntry): JsonObject = buildJsonObject {
        put(
            "level",
            when (e.level) {
                ConsoleLevel.ERROR -> "error"
                ConsoleLevel.WARN -> "warn"
                ConsoleLevel.LOG -> "log"
            },
        )
        put("text", e.legacyLine())
        e.lineNumber?.let { put("line", it) }
    }

    private suspend fun console(args: JsonObject): String {
        val lines = (args.intArg("lines") ?: 50).coerceIn(1, 300)
        val page = DevToolsClient.resolvePage(args.strArg("page"))
        val entries = DevToolsClient.consoleLines(page, lines)
        val errors = entries.filter { it.level == ConsoleLevel.ERROR }
        val warns = entries.filter { it.level == ConsoleLevel.WARN }
        val logs = entries.filter { it.level == ConsoleLevel.LOG }
        return buildJsonObject {
            put("page", pageJson(page))
            put("count", entries.size)
            put("levels", buildJsonObject {
                put("error", errors.size)
                put("warn", warns.size)
                put("log", logs.size)
            })
            if (entries.isEmpty()) {
                put("note", "暂无 console 输出（已从 attach 起实时监听 Runtime + Log 域；Log.enable 会补发页面加载期日志，可稍后重读）")
            } else {
                // 按级别分组（error 红 / warn 黄 / log 灰），同时保留按时间顺序的扁平 entries。
                put("groups", buildJsonObject {
                    put("error", JsonArray(errors.map { entryJson(it) }))
                    put("warn", JsonArray(warns.map { entryJson(it) }))
                    put("log", JsonArray(logs.map { entryJson(it) }))
                })
                put("entries", JsonArray(entries.map { entryJson(it) }))
            }
        }.toString()
    }

    private suspend fun errors(args: JsonObject): String {
        val lines = (args.intArg("lines") ?: 50).coerceIn(1, 300)
        val page = DevToolsClient.resolvePage(args.strArg("page"))
        val entries = DevToolsClient.consoleErrors(page, lines)
        return buildJsonObject {
            put("page", pageJson(page))
            put("count", entries.size)
            put("errors", JsonArray(entries.map { entryJson(it) }))
            if (entries.isEmpty()) {
                put("note", "未发现 error 级 console/异常（加载期报错由 Log.enable 补发）")
            }
        }.toString()
    }

    private suspend fun screenshot(args: JsonObject): String {
        val page = DevToolsClient.resolvePage(args.strArg("page"))
        // R96 review 二轮必修项1：与 eval 同口径——不向模型回传 app 内部同源页画面
        // （截屏可包含设置页渲染的密钥/凭据内容）。
        if (!DevToolsClient.canModelExecutePage(page)) {
            error("安全策略：禁止截取 app 内部同源页面（${page.url.take(120)}）——画面可能包含应用凭据。")
        }
        val base64 = DevToolsClient.captureScreenshot(page)
        val shot = Json.parseToJsonElement(devtoolsScreenshotJson(context, base64)).jsonObject
        return buildJsonObject {
            shot.forEach { (k, v) -> put(k, v) }
            put("page", pageJson(page))
        }.toString()
    }

}

/** Top-level so MainActivity can register the family. */
val DEVTOOLS_TOOLS = listOf(
    "devtools_status",
    "devtools_debugging",
    "devtools_eval",
    "devtools_console",
    "devtools_errors",
    "devtools_screenshot",
)

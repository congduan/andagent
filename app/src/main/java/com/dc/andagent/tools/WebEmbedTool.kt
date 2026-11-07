package com.dc.andagent.tools

import android.net.Uri
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * R101：内嵌浏览器。除 HTTPS 网页外，支持本地 HTML 文件（file:/// / content:// /
 * /sdcard/... 绝对路径）——AI 生成网页存到 Download/andagent/web/ 后用本工具打开，
 * 前端会把本地文件交给原生内嵌 WebView（openEmbeddedWebview → stageLocalHtml），
 * 该 WebView 是本进程 DevTools target，可被 devtools_status 枚举、devtools_eval 执行。
 */
class WebEmbedTool : Tool {
    override val name = "web_embed"
    override val description =
        "在对话卡片中提供内嵌浏览器：支持 HTTPS 网页，以及本地 HTML 文件（file:///、content:// 或 /sdcard/... 绝对路径）。本地 HTML 会在原生内嵌 WebView 打开（stageLocalHtml 路径），可用 devtools_console / devtools_errors 查加载期报错、devtools_eval 验证 DOM（需 DevTools 调试开启）。"
    override val parameters = schemaOf(
        "uri" to strParam("HTTPS 网页地址，或本地 HTML 的 file:///、content:// URI / 绝对路径"),
        required = listOf("uri"),
    )

    override suspend fun execute(args: JsonObject): String {
        val raw = args.strArg("uri") ?: return "ERROR: 缺少 uri"
        // 裸绝对路径补成 file://，让 openEmbeddedWebview 的 file 分支可读取。
        val uri = if (!raw.contains("://") && raw.startsWith("/")) "file://$raw" else raw
        val parsed = Uri.parse(uri)
        val local = when (parsed.scheme) {
            "https" -> {
                if (parsed.host.isNullOrBlank()) return "ERROR: HTTPS 网页缺少 host"
                false
            }
            "file", "content" -> true
            else -> return "ERROR: 内嵌浏览器支持 HTTPS 网页与本地 HTML 文件（file:/// 或 content:// 或绝对路径）"
        }
        return buildJsonObject {
            put("type", "webview")
            put("uri", uri)
            put("local", local)
        }.toString()
    }
}

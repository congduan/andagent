package com.dc.andagent.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.webkit.WebView
import com.dc.andagent.tools.ImageEditEngine
import com.dc.andagent.ui.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** /json 列出的一个 DevTools target（主 WebView、内嵌浏览器、DB GUI 各算一个）。 */
data class DevToolsPage(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String,
)

/** console 级别分组（error 红 / warn 黄 / log 灰）。 */
enum class ConsoleLevel { ERROR, WARN, LOG }

/**
 * R101：结构化 console 条目。环形缓冲从 attach（Runtime/Log.enable）起实时记录，
 * 不再依赖「先 devtools_eval 制造输出」。tag 保留原事件前缀以兼容旧单行文本。
 */
data class ConsoleEntry(
    val level: ConsoleLevel,
    val tag: String,
    val text: String,
    val lineNumber: Int?,
) {
    /** 兼容旧 devtools_console 的单行文本（如 "error: xxx"、"exception: xxx"）。 */
    fun legacyLine(): String =
        "$tag: " + if (lineNumber != null) "$text (line $lineNumber)" else text
}

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** 主 WebView 的 shell 页面 URL（与 MainActivity.SHELL_ORIGIN 加载地址一致）。 */
/** R96 review 三轮必修项2：判定统一走 URI 解析（parseAppAssetsUrl），常量留作文档与测试基线。 */
private const val APPASSETS_HOST = "appassets.androidplatform.net"
private val MAIN_SHELL_URL = "https://$APPASSETS_HOST/assets/web/index.html"

/** 解析 /json 响应。保留全部 target 类型（page/service_worker 等）由 agent 自行选择。 */
fun parseDevToolsPages(body: String): List<DevToolsPage> {
    val arr = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonArray
        ?: throw IOException("devtools /json 返回的不是 JSON 数组")
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        DevToolsPage(
            id = id,
            type = o.str("type") ?: "page",
            title = o.str("title") ?: "",
            url = o.str("url") ?: "",
            webSocketDebuggerUrl = o.str("webSocketDebuggerUrl") ?: "",
        )
    }
}

/** CDP 请求封装：{"id":N,"method":...,"params":{...}}。 */
fun buildCdpRequest(id: Int, method: String, params: JsonObject?): String =
    buildJsonObject {
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

/** 从 webSocketDebuggerUrl 取 path（去掉伪造的 host），空则回退 /devtools/page/<id>。 */
fun wsDebuggerPath(webSocketDebuggerUrl: String, pageId: String): String =
    "/" + webSocketDebuggerUrl.substringAfter("://", "").substringAfter("/", "")
        .ifBlank { "devtools/page/$pageId" }

/** CDP RemoteObject → 文本（console 参数等）。 */
fun renderRemoteObject(el: kotlinx.serialization.json.JsonElement): String {
    if (el is kotlinx.serialization.json.JsonPrimitive) return el.contentOrNull ?: el.toString()
    val o = el as? JsonObject ?: return el.toString().take(200)
    o["value"]?.let { v ->
        if (v is kotlinx.serialization.json.JsonPrimitive) return v.contentOrNull ?: v.toString()
    }
    return o["description"]?.jsonPrimitive?.contentOrNull
        ?: o["type"]?.jsonPrimitive?.contentOrNull ?: "object"
}

/**
 * R101：CDP 事件 → 结构化 [ConsoleEntry]。
 *  - Runtime.consoleAPICalled：type→级别，args→文本，stackTrace 首帧→行号
 *  - Runtime.exceptionThrown：一律 error，exceptionDetails.lineNumber→行号
 *  - Log.entryAdded：entry.level→级别，entry.text/url/lineNumber；Log.enable 会补发
 *    attach 前的历史条目，故能捕获「页面加载期」报错
 * 其他事件返回 null。
 */
fun consoleEntry(method: String, params: JsonObject): ConsoleEntry? = when (method) {
    "Runtime.consoleAPICalled" -> {
        val type = params.str("type") ?: "log"
        val args = (params["args"] as? JsonArray ?: JsonArray(emptyList()))
            .joinToString(" ") { renderRemoteObject(it) }
        val level = when (type.lowercase()) {
            "error" -> ConsoleLevel.ERROR
            "warning", "warn" -> ConsoleLevel.WARN
            else -> ConsoleLevel.LOG
        }
        val frames = (params["stackTrace"] as? JsonObject)?.get("callFrames") as? JsonArray
        val frame = frames?.firstOrNull() as? JsonObject
        val line = frame?.get("lineNumber")?.jsonPrimitive?.intOrNull?.let { it + 1 }
        ConsoleEntry(level, type, args, line)
    }
    "Runtime.exceptionThrown" -> {
        val d = params["exceptionDetails"] as? JsonObject ?: return null
        val text = d.str("text") ?: ""
        val desc = (d["exception"] as? JsonObject)?.str("description") ?: ""
        val line = d["lineNumber"]?.jsonPrimitive?.intOrNull?.let { it + 1 }
        ConsoleEntry(ConsoleLevel.ERROR, "exception", desc.ifBlank { text }, line)
    }
    "Log.entryAdded" -> {
        val e = params["entry"] as? JsonObject ?: return null
        val levelStr = e.str("level") ?: "log" // verbose / info / warning / error
        val level = when (levelStr.lowercase()) {
            "error" -> ConsoleLevel.ERROR
            "warning", "warn" -> ConsoleLevel.WARN
            else -> ConsoleLevel.LOG
        }
        val text = e.str("text") ?: ""
        val url = e.str("url")
        val line = e["lineNumber"]?.jsonPrimitive?.intOrNull?.let { it + 1 }
        val full = if (url != null) "$text [$url]" else text
        ConsoleEntry(level, levelStr, full, line)
    }
    else -> null
}

/** 兼容旧调用/测试：结构化条目 → 单行文本。 */
fun formatConsoleEvent(method: String, params: JsonObject): String? =
    consoleEntry(method, params)?.legacyLine()

/** /proc/net/unix 内容 → devtools socket 名（abstract 前导 @ 去掉，绝对路径保留）。 */
fun scanDevToolsSocketNames(content: String): List<String> =
    content.lineSequence()
        .map { it.substringAfterLast(' ').trim() }
        .filter { it.contains("devtools_remote") }
        .map { it.removePrefix("@") }
        .distinct()
        .toList()

/**
 * Chrome DevTools Protocol 客户端（app 自己的 WebView，无需 root）。
 *
 * setWebContentsDebuggingEnabled(true) 会在本进程暴露抽象 Unix socket
 * `webview_devtools_remote_<pid>`（同 uid 可直连）。HTTP GET /json 拿页面列表，
 * 每个 target 再经 webSocketDebuggerUrl 走 CDP。OkHttp 提供 WebSocket 传输：
 * 自定义 SocketFactory 把 java.net.Socket 桥接到 LocalSocket，/json 请求与
 * WebSocket 共用同一条 Unix socket。console 监听是事件而非响应，解析分轨。
 */
object DevToolsClient {
    private const val LOCAL_HOST = "http://127.0.0.1:9222"
    private const val CONSOLE_CAP = 300
    private const val MAX_SESSIONS = 8
    private const val COMMAND_TIMEOUT_MS = 25_000L

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // 每次请求都拨一条新 LocalSocket，不池化复用（server 也会随手关闭连接）。
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .build()

    private val sessions = ConcurrentHashMap<String, TargetSession>()
    @Volatile private var socketName: String? = null

    /** 本进程 pid 命名优先，/proc/net/unix 扫描兜底（覆盖 WebView 版本命名差异）。 */
    fun socketCandidates(): List<String> =
        linkedSetOf("webview_devtools_remote_${Process.myPid()}")
            .apply {
                runCatching {
                    addAll(scanDevToolsSocketNames(File("/proc/net/unix").readText()))
                }
            }
            .toList()

    /**
     * GET /json。成功后缓存 socket 名；全部失败时错误串带 /proc/net/unix 线索。
     * R96 review 必修项1：过滤主 shell 页面（appassets.androidplatform.net/assets/web/index.html）。
     * 该页面主帧 window 持有设置通道凭证 __kvSettingToken（onPageFinished 注入），
     * 若暴露给 devtools_eval，模型可在主帧执行任意 JS 读 token 并直调
     * AndroidBridge.kvSettingCall 读 secure/engines（含 CSE API key）——违反
     * 「凭据永不过模型」铁律。过滤后模型工具通道只触达 web_embed / dbgui 等
     * 无凭证页面；resolvePage 缺省「第一个 page target」也不会再选中主 shell。
     */
    fun listPages(): List<DevToolsPage> {
        socketName?.let { cached -> runCatching { return filterMainShellPages(fetchPages(cached)) } }
        val candidates = runCatching { socketCandidates() }.getOrElse { emptyList() }
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            try {
                return filterMainShellPages(fetchPages(candidate)).also { socketName = candidate }
            } catch (e: Exception) {
                android.util.Log.e("DevToolsClient", "连接 $candidate 失败", e)
                val top = e.stackTrace.firstOrNull()
                    ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                errors += "${candidate}: ${e.message ?: e.javaClass.simpleName}" +
                    if (top != null) " @ $top" else ""
            }
        }
        val hints = candidates.filter { it.contains("devtools") }.take(6).joinToString(" | ")
        error(
            "无法连接 DevTools socket（已尝试 ${candidates.joinToString(", ")}）。" +
                "线索: ${hints.ifBlank { "/proc/net/unix 无 devtools 条目" }}。" +
                "若调试未开启，请先调用 devtools_debugging(enable=true) 或在设置页开启 DevTools。" +
                if (errors.isNotEmpty()) " 详情: ${errors.joinToString("; ").take(400)}" else "",
        )
    }

    /**
     * R96 review 必修项1：主 shell 页面判定。路径精确 /assets/web/index.html
     * （MainActivity SHELL_ORIGIN 唯一加载入口；SPA 无路由，URL 恒定）。
     * web_embed 本地页走 /local/ 路由、远程页走外部 https，均不受影响。过滤放在
     * listPages 出口，resolvePage/devtools_status/eval/console/errors/screenshot
     * 全部消费同一份列表。
     *
     * R96 review 三轮必修项2：字符串 equals 挡不住 URL 规范化变体（`:443` 默认
     * 端口、大小写、编码）——改按解析后的 scheme/host/port/path 判定。CDP 回报
     * URL 恒为绝对 https；解析失败按主 shell 处理（fail-closed，宁误伤不漏判）。
     * query/hash 后缀仍是主 shell（同页面）。
     */
    fun isMainShellPage(page: DevToolsPage): Boolean =
        parseAppAssetsUrl(page.url) { scheme, host, port, path ->
            scheme == "https" && host == APPASSETS_HOST && port == 443 && path == "/assets/web/index.html"
        } ?: true

    /** appassets 域解析（scheme 小写 / host 小写去尾点 / 默认端口归一 443 / 路径解码）。 */
    private inline fun <T> parseAppAssetsUrl(raw: String, judge: (scheme: String, host: String, port: Int, path: String) -> T): T? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (host != APPASSETS_HOST) return null
        val port = if (uri.port == -1) if (scheme == "https") 443 else -1 else uri.port
        val path = runCatching { URLDecoder.decode(uri.rawPath ?: "/", "UTF-8") }.getOrDefault(uri.rawPath ?: "/")
        return judge(scheme, host, port, path)
    }

    /**
     * R96 review 二轮必修项1（纵深防御）：
     * - /local/ 暂存页的真实载体是「无 AndroidBridge 的原生内嵌 WebView」（桥只挂
     *   主 webView；嵌入浏览器不注入 token），eval 上下文够不到主帧与凭据——因此
     *   /local/ 放行，保住 R101「生成→嵌入→devtools_eval 验证 DOM」闭环。
     *   危险通道是对话卡片 iframe（WebViewCard 允许任意 https src 且带
     *   allow-same-origin）：模型把 appassets URL 当普通网页嵌入，同源 iframe 可读
     *   parent.__kvSettingToken 并继承 AndroidBridge 直调 kvSettingCall——该路径
     *   已在 web 层 WebViewCard 封死（appassets 域拒绝进卡片 iframe）。
     * - appassets 域其余页面（主 shell / /assets/dbgui/ 挂 DbGuiBridge / 资产物
     *   误注册 target）拒模型页面执行：eval/screenshot 同样可能触达桥或凭据。
     */
    fun isLocalStagedPage(page: DevToolsPage): Boolean =
        parseAppAssetsUrl(page.url) { scheme, host, port, path ->
            scheme == "https" && host == APPASSETS_HOST && port == 443 && path.startsWith("/local/")
        } ?: false

    fun isAppAssetsPage(page: DevToolsPage): Boolean =
        parseAppAssetsUrl(page.url) { scheme, host, _port, _path ->
            scheme == "https" && host == APPASSETS_HOST
        } ?: true

    /** devtools_eval / screenshot 等页面级执行白名单：非 appassets 页 + /local/ 暂存页。 */
    fun canModelExecutePage(page: DevToolsPage): Boolean =
        !isAppAssetsPage(page) || isLocalStagedPage(page)

    private fun filterMainShellPages(pages: List<DevToolsPage>): List<DevToolsPage> =
        pages.filterNot { isMainShellPage(it) }

    private fun fetchPages(name: String): List<DevToolsPage> {
        val request = Request.Builder()
            .url("$LOCAL_HOST/json")
            .header("Connection", "close")
            .build()
        baseClient.newBuilder().socketFactory(DevToolsSocketFactory(name)).build()
            .newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                return parseDevToolsPages(resp.body?.string() ?: throw IOException("空响应"))
            }
    }

    /** 页面序号或 url/title 关键词 → 具体页面；null 时默认第一个 type=page 的 target。 */
    fun resolvePage(selector: String?): DevToolsPage {
        val pages = listPages()
        selector?.toIntOrNull()?.let { idx ->
            return pages.getOrNull(idx)
                ?: error("页面序号 $idx 超出范围（共 ${pages.size} 个），请用 devtools_status 查看")
        }
        if (!selector.isNullOrBlank()) {
            val needle = selector.lowercase()
            return pages.firstOrNull {
                it.url.lowercase().contains(needle) || it.title.lowercase().contains(needle)
            } ?: error("没有匹配 “$selector” 的页面；请用 devtools_status 查看列表")
        }
        return pages.firstOrNull { it.type == "page" } ?: pages.firstOrNull()
        ?: error("当前无可调试页面")
    }

    /** Runtime.evaluate（awaitPromise + returnByValue），返回完整 CDP 响应对象。 */
    suspend fun evaluate(page: DevToolsPage, js: String): JsonObject =
        session(page).command(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", js)
                put("returnByValue", true)
                put("awaitPromise", true)
                put("userGesture", true)
            },
        )

    /** Page.captureScreenshot → PNG base64。 */
    suspend fun captureScreenshot(page: DevToolsPage): String {
        val resp = session(page).command(
            "Page.captureScreenshot",
            buildJsonObject { put("format", "png") },
        )
        return resp["result"]?.jsonObject?.str("data")
            ?: throw IOException("截图失败：${resp["error"] ?: resp}")
    }

    /** 读取某页的结构化 console 环形缓冲；首次调用建立连接并 enable Runtime + Log。 */
    suspend fun consoleLines(page: DevToolsPage, max: Int): List<ConsoleEntry> {
        val s = session(page)
        // 等 Runtime.enable 落地（最多 800ms），顺便接住 Log.enable 补发的历史条目。
        runCatching { withTimeout(800) { s.awaitEnabled() } }
        return s.consoleLines(max)
    }

    /** devtools_errors：只返回 error 级条目（含 Log.enable 补发的加载期报错）。 */
    suspend fun consoleErrors(page: DevToolsPage, max: Int): List<ConsoleEntry> =
        consoleLines(page, CONSOLE_CAP)
            .filter { it.level == ConsoleLevel.ERROR }
            .takeLast(max)

    fun shutdown() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun session(page: DevToolsPage): TargetSession {
        if (socketName == null) listPages() // 建立缓存；失败会抛出带线索的错误
        val name = socketName!!
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(page.id)) {
            sessions.keys.firstOrNull()?.let { old -> sessions.remove(old)?.close() }
        }
        return sessions.compute(page.id) { _, existing ->
            if (existing != null && existing.page.url == page.url) existing
            else TargetSession(name, page).also { existing?.close() }
        }!!
    }

    /**
     * 一个 target 的 CDP 会话：响应按 id 匹配 pending 表，method 事件进 console 环形缓冲。
     */
    private class TargetSession(private val socketName: String, val page: DevToolsPage) {
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val enabled = CompletableDeferred<Unit>()
        /** enable 控制命令的响应 id（不进 pending）；Runtime.enable 的 id 用于 awaitEnabled。 */
        private val enableIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        @Volatile private var runtimeEnableId = -1
        private val consoleBuffer = ArrayDeque<ConsoleEntry>()
        @Volatile private var socket: WebSocket? = null

        fun consoleLines(max: Int): List<ConsoleEntry> =
            synchronized(consoleBuffer) { consoleBuffer.toList().takeLast(max) }

        private fun appendConsole(entry: ConsoleEntry?) {
            if (entry == null) return
            synchronized(consoleBuffer) {
                consoleBuffer.addLast(entry)
                while (consoleBuffer.size > CONSOLE_CAP) consoleBuffer.removeFirst()
            }
        }

        private fun connect(): WebSocket = socket ?: synchronized(this) {
            socket ?: baseClient.newBuilder().socketFactory(DevToolsSocketFactory(socketName)).build()
                .newWebSocket(
                    Request.Builder()
                        .url("ws://127.0.0.1:9222${wsDebuggerPath(page.webSocketDebuggerUrl, page.id)}")
                        .build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            // R101：从 attach 起就开 console 事件流。Runtime 提供 consoleAPICalled /
                            // exceptionThrown；Log.enable 会补发 attach 前的历史日志（含页面加载期报错）。
                            // 两条 enable 的响应按 id 认领，不进 pending。
                            val rid = nextId.getAndIncrement()
                            val lid = nextId.getAndIncrement()
                            enableIds.add(rid); enableIds.add(lid)
                            runtimeEnableId = rid
                            webSocket.send(buildCdpRequest(rid, "Runtime.enable", null))
                            webSocket.send(buildCdpRequest(lid, "Log.enable", null))
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                            val id = obj["id"]?.jsonPrimitive?.intOrNull
                            if (id != null) {
                                if (enableIds.remove(id)) {
                                    if (id == runtimeEnableId) enabled.complete(Unit)
                                } else {
                                    pending.remove(id)?.complete(obj)
                                }
                                return
                            }
                            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
                            appendConsole(
                                consoleEntry(method, obj["params"] as? JsonObject ?: JsonObject(emptyMap())),
                            )
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            drop("连接断开: ${t.message ?: t.javaClass.simpleName}")
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            drop("已关闭: $reason")
                        }
                    },
                ).also { socket = it }
        }

        private fun drop(reason: String) {
            socket = null
            enableIds.clear()
            pending.values.forEach { it.completeExceptionally(IOException("DevTools $reason")) }
            pending.clear()
        }

        fun close() {
            socket?.close(1000, null)
            socket = null
            drop("session closed")
        }

        /** 挂起直到 Runtime.enable 的响应到达（或连接断开）。 */
        suspend fun awaitEnabled() = enabled.await()

        suspend fun command(method: String, params: JsonObject?): JsonObject {
            val id = nextId.getAndIncrement()
            val deferred = CompletableDeferred<JsonObject>()
            pending[id] = deferred
            try {
                connect().send(buildCdpRequest(id, method, params))
                return withTimeout(COMMAND_TIMEOUT_MS) { deferred.await() }
            } catch (e: Exception) {
                pending.remove(id)
                // 命令超时/发送失败：废弃连接，下一次调用重建。
                close()
                throw IllegalStateException("CDP 命令 $method 失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}

/* --------------------- OkHttp ↔ LocalSocket 桥接 --------------------- */

/**
 * 把 java.net.Socket 桥接到 LocalSocket。OkHttp 4.12 的连接路径只用到
 * createSocket() → connect(addr, timeout) → setSoTimeout → Okio.source/sink →
 * isClosed/close，其余选项对 UDS 无意义，静默忽略。
 */
private class LocalBridgeSocket(private val name: String) : java.net.Socket() {
    private val isPath = name.startsWith("/")
    private val local = LocalSocket()
    @Volatile private var connectedFlag = false
    @Volatile private var closedFlag = false

    override fun connect(address: java.net.SocketAddress?, timeout: Int) {
        val ns = if (isPath) LocalSocketAddress.Namespace.FILESYSTEM else LocalSocketAddress.Namespace.ABSTRACT
        val endpoint = LocalSocketAddress(name, ns)
        // Android 17 (API 37) 的 connect(endpoint, timeout) 直接抛 UnsupportedOperationException，
        // 回退到无超时重载；本地 socket 连接是即时的，超时参数无实际意义。
        try {
            local.connect(endpoint, timeout)
        } catch (e: UnsupportedOperationException) {
            local.connect(endpoint)
        }
        connectedFlag = true
    }

    override fun connect(address: java.net.SocketAddress?) = connect(address, 0)
    override fun isConnected() = connectedFlag
    override fun isBound(): Boolean = connectedFlag
    override fun isClosed() = closedFlag
    override fun getInputStream() = local.getInputStream()
    override fun getOutputStream() = local.getOutputStream()

    override fun close() {
        closedFlag = true
        runCatching { local.close() }
    }

    override fun shutdownInput() { runCatching { local.shutdownInput() } }
    override fun shutdownOutput() { runCatching { local.shutdownOutput() } }
    override fun setSoTimeout(timeout: Int) { if (connectedFlag) runCatching { local.soTimeout = timeout } }
    override fun getSoTimeout(): Int = if (connectedFlag) runCatching { local.soTimeout }.getOrDefault(0) else 0
    override fun setTcpNoDelay(on: Boolean) {}
    override fun getTcpNoDelay() = false
    override fun setKeepAlive(on: Boolean) {}
}

private class DevToolsSocketFactory(private val name: String) : SocketFactory() {
    override fun createSocket(): java.net.Socket = LocalBridgeSocket(name)
    override fun createSocket(host: String?, port: Int): java.net.Socket = createSocket()
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket = createSocket()
    override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = createSocket()
    override fun createSocket(host: java.net.InetAddress?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket = createSocket()
}

/* ------------------------ 调试开关（进程级） ------------------------ */

/** 进程内视角的“现在是否可调试”：SP 显式开启（任何构建）或 debug 构建。 */
fun debuggingEnabledNow(context: Context): Boolean =
    SettingsStore(context).devtoolsDebugging ||
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)

/** 主线程切换 WebView.setWebContentsDebuggingEnabled 并持久化到 SP。返回 ack JSON。 */
fun setDevToolsDebugging(context: Context, enable: Boolean): String {
    val failure = runOnMain { WebView.setWebContentsDebuggingEnabled(enable) }
    return if (failure != null) {
        buildJsonObject {
            put("enabled", debuggingEnabledNow(context))
            put("message", "ERROR: 切换调试失败: ${failure.message ?: failure.javaClass.simpleName}")
        }.toString()
    } else {
        SettingsStore(context).devtoolsDebugging = enable
        val debugBuild = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        buildJsonObject {
            put("enabled", debuggingEnabledNow(context))
            put(
                "message",
                when {
                    enable -> "已开启 WebView 远程调试（release 包同样生效）；USB 连接电脑可在 chrome://inspect 查看"
                    debugBuild -> "已写入关闭偏好；当前为 debug 构建，仍默认开启调试"
                    else -> "已关闭 WebView 远程调试"
                },
            )
        }.toString()
    }
}

/** 在主线程执行 [block]，返回抛出的异常（或 null）。最多等 5 秒。 */
private fun <R> runOnMain(block: () -> R): Throwable? {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return runCatching(block).exceptionOrNull()
    }
    val latch = CountDownLatch(1)
    var failure: Throwable? = null
    Handler(Looper.getMainLooper()).post {
        try {
            failure = runCatching(block).exceptionOrNull()
        } finally {
            latch.countDown()
        }
    }
    latch.await(5, TimeUnit.SECONDS)
    return failure
}

/** 调试开关状态 + 可调试页面列表（给 devtools_status 工具与设置页共用）。 */
fun devtoolsStatusJson(context: Context): String {
    val enabled = debuggingEnabledNow(context)
    val debugBuild = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val pages = runCatching { DevToolsClient.listPages() }
    return buildJsonObject {
        put("enabled", enabled)
        put("debugBuild", debugBuild)
        put("socketHint", "webview_devtools_remote_${Process.myPid()}")
        pages.exceptionOrNull()?.message?.take(300)?.let { put("error", it) }
        put(
            "pages",
            JsonArray(
                pages.getOrDefault(emptyList()).mapIndexed { i, p ->
                    buildJsonObject {
                        put("index", i)
                        put("type", p.type)
                        put("title", p.title.ifBlank { p.url })
                        put("url", p.url)
                    }
                },
            ),
        )
    }.toString()
}

/** devtools_screenshot 公共段：CDP base64 PNG → 存相册 + 缩略图（复用图片围栏预览）。 */
fun devtoolsScreenshotJson(context: Context, base64: String): String {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException("截图数据无法解码为 PNG")
    return try {
        buildJsonObject {
            put("savedPath", ImageEditEngine.save(context, bitmap, true))
            put("thumbnailBase64", ImageEditEngine.thumbnail(bitmap))
            put("thumbnailMime", "image/png")
            put("width", bitmap.width)
            put("height", bitmap.height)
        }.toString()
    } finally {
        bitmap.recycle()
    }
}

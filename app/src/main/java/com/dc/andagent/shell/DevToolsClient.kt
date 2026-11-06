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

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

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

/** Runtime.consoleAPICalled / exceptionThrown 事件 → 一行 console 文本；其他事件返回 null。 */
fun formatConsoleEvent(method: String, params: JsonObject): String? = when (method) {
    "Runtime.consoleAPICalled" -> {
        val type = params.str("type") ?: "log"
        val args = (params["args"] as? JsonArray ?: JsonArray(emptyList()))
            .joinToString(" ") { renderRemoteObject(it) }
        "$type: $args"
    }
    "Runtime.exceptionThrown" -> {
        val d = params["exceptionDetails"] as? JsonObject ?: return null
        val text = d.str("text") ?: ""
        val desc = (d["exception"] as? JsonObject)?.str("description") ?: ""
        "exception: ${desc.ifBlank { text }}"
    }
    else -> null
}

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

    /** GET /json。成功后缓存 socket 名；全部失败时错误串带 /proc/net/unix 线索。 */
    fun listPages(): List<DevToolsPage> {
        socketName?.let { cached -> runCatching { return fetchPages(cached) } }
        val candidates = runCatching { socketCandidates() }.getOrElse { emptyList() }
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            try {
                return fetchPages(candidate).also { socketName = candidate }
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

    /** 读取某页的 console 环形缓冲；首次调用会建立连接并挂 Runtime.enable。 */
    suspend fun consoleLines(page: DevToolsPage, max: Int): List<String> {
        val s = session(page)
        // 等 Runtime.enable 落地（最多 800ms），顺便接住连接后立刻到达的事件。
        runCatching { withTimeout(800) { s.awaitEnabled() } }
        return s.consoleLines(max)
    }

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
        @Volatile private var enableCommandId = -1
        private val consoleBuffer = ArrayDeque<String>()
        @Volatile private var socket: WebSocket? = null

        fun consoleLines(max: Int): List<String> =
            synchronized(consoleBuffer) { consoleBuffer.toList().takeLast(max) }

        private fun appendConsole(line: String?) {
            if (line == null) return
            synchronized(consoleBuffer) {
                consoleBuffer.addLast(line)
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
                            // 打开 console 事件流；其响应用 enableCommandId 认领，不进 pending。
                            enableCommandId = nextId.getAndIncrement()
                            webSocket.send(buildCdpRequest(enableCommandId, "Runtime.enable", null))
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                            val id = obj["id"]?.jsonPrimitive?.intOrNull
                            if (id != null) {
                                if (id == enableCommandId) enabled.complete(Unit)
                                else pending.remove(id)?.complete(obj)
                                return
                            }
                            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
                            appendConsole(
                                formatConsoleEvent(method, obj["params"] as? JsonObject ?: JsonObject(emptyMap())),
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

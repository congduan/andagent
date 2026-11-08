package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val MCP_PROTOCOL_VERSION = "2025-06-18"
private val MCP_JSON = "application/json; charset=utf-8".toMediaType()
private val MCP_ACCEPT = "application/json, text/event-stream"

internal data class McpServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** true 时信任该服务端工具，不再逐次要求用户确认（写入远端）。 */
    val trust: Boolean = false,
)

internal data class McpRemoteTool(
    val name: String,
    val description: String,
    val schema: JsonElement,
)

/** Streamable HTTP 响应解析（普通 JSON / SSE 单行 data:），独立出来便于单测。 */
internal object McpWire {
    fun parseResponse(body: String, id: JsonElement): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        }
        var fallback: JsonObject? = null
        for (line in trimmed.lineSequence()) {
            val data = line.trim().removePrefix("data:").trim()
            if (!data.startsWith("{")) continue
            val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            if (obj["id"] == id || obj["result"] != null || obj["error"] != null) return obj
            if (fallback == null) fallback = obj
        }
        return fallback
    }
}

/**
 * MCP（Model Context Protocol）客户端：
 * - 持久化 HTTP MCP 服务端配置；
 * - 拉取 tools/list 并把每个远端工具注册进本机工具表（`mcp_<server>_<tool>`）；
 * - 提供 mcp_* 管理工具。
 *
 * 只实现 Streamable HTTP（POST + JSON/SSE 响应）；stdio 传输在 Android 上
 * 需要托管子进程，不在此列。
 */
class McpRegistry(private val context: Context, private val tools: MutableMap<String, Tool>) {
    companion object {
        val MANAGER_TOOLS = listOf(
            "mcp_list", "mcp_add", "mcp_remove", "mcp_enable",
            "mcp_tools", "mcp_call", "mcp_reload",
            "mcp_server_start", "mcp_server_stop", "mcp_server_status",
        )
        private val validName = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
    }

    private val prefs = context.getSharedPreferences("mcp", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val dynamic = mutableSetOf<String>()
    private val remote = mutableMapOf<String, List<McpRemoteTool>>()
    private val sessions = mutableMapOf<String, String>()
    private val initialized = mutableSetOf<String>()
    private val protocols = mutableMapOf<String, String>()
    private val errors = mutableMapOf<String, String>()
    private val server = AndagentMcpServer(context, tools)

    init {
        MANAGER_TOOLS.forEach { tools[it] = McpTool(this, it) }
    }

    /* ------------------------------ config ------------------------------ */

    private fun loadServers(): MutableList<McpServerConfig> {
        val raw = prefs.getString("servers", null)?.takeIf { it.isNotBlank() } ?: return mutableListOf()
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return mutableListOf()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { validName.matches(it) } ?: return@mapNotNull null
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.toMap() ?: emptyMap()
            McpServerConfig(
                name = name,
                url = url,
                headers = headers,
                enabled = (obj["enabled"] as? JsonPrimitive)?.contentOrNull != "false",
                trust = (obj["trust"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }.toMutableList()
    }

    private fun saveServers(list: List<McpServerConfig>) {
        val array = buildJsonArray {
            list.forEach { cfg ->
                add(buildJsonObject {
                    put("name", cfg.name); put("url", cfg.url); put("enabled", cfg.enabled); put("trust", cfg.trust)
                    put("headers", buildJsonObject { cfg.headers.forEach { (k, v) -> put(k, v) } })
                })
            }
        }
        prefs.edit().putString("servers", array.toString()).apply()
    }

    @Synchronized fun listServers(): String = JsonArray(loadServers().map { cfg ->
        buildJsonObject {
            put("name", cfg.name)
            put("url", cfg.url)
            put("enabled", cfg.enabled)
            put("trust", cfg.trust)
            put("toolCount", remote[cfg.name]?.size ?: 0)
            put("lastError", errors[cfg.name]?.let(::JsonPrimitive) ?: JsonNull)
        }
    }).toString()

    /** UI/桥入口：value 是 `{url, headers?, trust?}`。 */
    @Synchronized fun addFromJson(name: String, payload: String): String {
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrElse { error("配置必须是 JSON 对象") }
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: error("缺少 url")
        val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap()
        val trust = (obj["trust"] as? JsonPrimitive)?.contentOrNull == "true"
        return add(name, url, headers, trust)
    }

    @Synchronized fun add(name: String, url: String, headers: Map<String, String>? = null, trust: Boolean = false): String {
        require(validName.matches(name)) { "服务名只能包含字母、数字、下划线和连字符" }
        require(url.startsWith("https://") || url.startsWith("http://")) { "MCP 地址必须是 HTTP(S)" }
        val list = loadServers()
        require(list.none { it.name.equals(name, ignoreCase = true) }) { "MCP 服务已存在：$name" }
        val cfg = McpServerConfig(name, url.trim(), headers.orEmpty(), enabled = true, trust = trust)
        saveServers(list + cfg)
        runCatching { reloadServer(cfg) }.onFailure { errors[name] = it.message ?: it.javaClass.simpleName }
        return listServers()
    }

    @Synchronized fun remove(name: String, confirm: Boolean): String {
        require(confirm) { "删除 MCP 服务需 confirm=true" }
        val list = loadServers()
        require(list.any { it.name == name }) { "MCP 服务不存在" }
        unregister(name)
        saveServers(list.filterNot { it.name == name })
        sessions.remove(name); initialized.remove(name); protocols.remove(name); errors.remove(name); remote.remove(name)
        return listServers()
    }

    @Synchronized fun enable(name: String, on: Boolean): String {
        val list = loadServers()
        val cfg = list.firstOrNull { it.name == name } ?: error("MCP 服务不存在")
        unregister(name)
        initialized.remove(name); sessions.remove(name)
        saveServers(list.map { if (it.name == name) it.copy(enabled = on) else it })
        if (on) runCatching { reloadServer(cfg.copy(enabled = true)) }
            .onFailure { errors[name] = it.message ?: it.javaClass.simpleName }
        return listServers()
    }

    /* ------------------------------ client ------------------------------ */

    @Synchronized fun reload(): String {
        val list = loadServers()
        list.forEach { unregister(it.name); initialized.remove(it.name) }
        errors.clear()
        val failed = mutableListOf<String>()
        list.filter { it.enabled }.forEach { cfg ->
            runCatching { reloadServer(cfg) }
                .onFailure { errors[cfg.name] = it.message ?: it.javaClass.simpleName }
        }
        errors.forEach { (name, message) -> failed += "$name：$message" }
        return buildJsonObject {
            put("toolCount", dynamic.size)
            put("servers", JsonArray(loadServers().map { JsonPrimitive(it.name) }))
            put("failed", JsonArray(failed.map { JsonPrimitive(it) }))
        }.toString()
    }

    private fun reloadServer(cfg: McpServerConfig) {
        initialize(cfg)
        val listed = listRemoteTools(cfg)
        remote[cfg.name] = listed
        listed.forEach { tool ->
            val global = globalName(cfg.name, tool.name)
            if (tools.containsKey(global)) return@forEach
            tools[global] = McpRemoteToolWrapper(global, tool.description, tool.schema, !cfg.trust, this, cfg.name, tool.name)
            dynamic.add(global)
        }
        errors.remove(cfg.name)
    }

    private fun unregister(serverName: String) {
        val prefix = "mcp_${sanitize(serverName)}_"
        dynamic.filter { it.startsWith(prefix) }.forEach { tools.remove(it); dynamic.remove(it) }
    }

    private fun globalName(serverName: String, toolName: String): String =
        "mcp_${sanitize(serverName)}_${sanitize(toolName)}".take(64)

    private fun sanitize(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").ifBlank { "tool" }

    private fun initialize(cfg: McpServerConfig) {
        if (initialized.contains(cfg.name)) return
        val result = rpc(
            cfg,
            "initialize",
            buildJsonObject {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put("clientInfo", buildJsonObject { put("name", "andagent"); put("version", BuildConfig.VERSION_NAME) })
            },
            JsonPrimitive(1),
        )
        protocols[cfg.name] = result["protocolVersion"]?.jsonPrimitive?.contentOrNull ?: MCP_PROTOCOL_VERSION
        initialized.add(cfg.name)
        runCatching { notify(cfg, "notifications/initialized", null) }
    }

    private fun listRemoteTools(cfg: McpServerConfig): List<McpRemoteTool> {
        val result = rpc(cfg, "tools/list", buildJsonObject { }, JsonPrimitive(2))
        return (result["tools"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpRemoteTool(
                name = name,
                description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                schema = obj["inputSchema"] ?: obj["parameters"] ?: jsonSchema(),
            )
        }
    }

    /** 公共调用入口：管理工具、UI 调试都走这里。 */
    @Synchronized fun callTool(serverName: String, toolName: String, arguments: JsonObject): String {
        val cfg = loadServers().firstOrNull { it.name == serverName } ?: error("MCP 服务不存在")
        require(cfg.enabled) { "MCP 服务已停用" }
        initialize(cfg)
        val result = rpc(
            cfg,
            "tools/call",
            buildJsonObject { put("name", toolName); put("arguments", arguments) },
            JsonPrimitive(System.nanoTime().toString()),
        )
        val content = (result["content"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        val text = content.joinToString("\n").ifBlank {
            result["structuredContent"]?.toString() ?: result.toString()
        }
        if (result["isError"]?.jsonPrimitive?.contentOrNull == "true") error(text)
        return text
    }

    @Synchronized fun toolsOf(serverName: String): String {
        require(loadServers().any { it.name == serverName }) { "MCP 服务不存在" }
        val listed = remote[serverName].orEmpty()
        return JsonArray(listed.map { tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.schema)
            }
        }).toString()
    }

    /* --------------------------- JSON-RPC HTTP --------------------------- */

    private fun rpc(cfg: McpServerConfig, method: String, params: JsonElement?, id: JsonElement): JsonObject {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val builder = Request.Builder()
            .url(cfg.url)
            .header("Content-Type", "application/json")
            .header("Accept", MCP_ACCEPT)
            .header("MCP-Protocol-Version", protocols[cfg.name] ?: MCP_PROTOCOL_VERSION)
        cfg.headers.forEach { (key, value) -> builder.header(key, value) }
        sessions[cfg.name]?.let { builder.header("Mcp-Session-Id", it) }
        http.newCall(builder.post(payload.toString().toRequestBody(MCP_JSON)).build()).execute().use { response ->
            response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessions[cfg.name] = it }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(200).ifBlank { response.message }}")
            }
            val message = parseRpcBody(body, id) ?: error("MCP 响应缺少 JSON-RPC 结果")
            (message["error"] as? JsonObject)?.let { err ->
                throw IllegalStateException(err["message"]?.jsonPrimitive?.contentOrNull ?: "MCP 调用失败")
            }
            return (message["result"] as? JsonObject) ?: JsonObject(emptyMap())
        }
    }

    private fun notify(cfg: McpServerConfig, method: String, params: JsonElement?) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        val builder = Request.Builder()
            .url(cfg.url)
            .header("Content-Type", "application/json")
            .header("Accept", MCP_ACCEPT)
            .header("MCP-Protocol-Version", protocols[cfg.name] ?: MCP_PROTOCOL_VERSION)
        cfg.headers.forEach { (key, value) -> builder.header(key, value) }
        sessions[cfg.name]?.let { builder.header("Mcp-Session-Id", it) }
        http.newCall(builder.post(payload.toString().toRequestBody(MCP_JSON)).build()).execute().close()
    }

    /** 兼容普通 JSON 与 SSE（`data: {...}`）两种 Streamable HTTP 响应。 */
    private fun parseRpcBody(body: String, id: JsonElement): JsonObject? = McpWire.parseResponse(body, id)

    /* ----------------------------- reverse ------------------------------ */

    fun startServer(port: Int, exposeAll: Boolean): String = server.start(port, exposeAll)
    fun stopServer(): String = server.stop()
    fun serverStatus(): String = server.status()
}

private class McpRemoteToolWrapper(
    override val name: String,
    override val description: String,
    override val parameters: JsonElement,
    override val destructive: Boolean,
    private val registry: McpRegistry,
    private val serverName: String,
    private val remoteName: String,
) : Tool {
    override suspend fun execute(args: JsonObject): String = registry.callTool(serverName, remoteName, args)
}

class McpTool(private val registry: McpRegistry, override val name: String) : Tool {
    /** 启动反向 server（对外暴露本机工具）与删除配置需要用户确认。 */
    override val destructive: Boolean = name in setOf("mcp_server_start", "mcp_remove")
    override val description = when (name) {
        "mcp_list" -> "列出已配置的 MCP 服务（HTTP），包含已注册工具数量与最近错误。"
        "mcp_add" -> "添加 MCP 服务并立即拉取工具列表：name + url，可选 headers（JSON 对象字符串）与 trust。"
        "mcp_remove" -> "删除 MCP 服务，需 confirm=true。"
        "mcp_enable" -> "启用或停用 MCP 服务。"
        "mcp_tools" -> "查看某个 MCP 服务暴露的远端工具。"
        "mcp_call" -> "直接调用某个 MCP 服务的远端工具（server/tool/arguments）。"
        "mcp_reload" -> "重新连接所有已启用的 MCP 服务并刷新工具注册。"
        "mcp_server_start" -> "把 andagent 工具反向暴露为 MCP server（仅 127.0.0.1，需 Bearer token）。"
        "mcp_server_stop" -> "停止反向 MCP server。"
        else -> "查看反向 MCP server 状态（端口、地址、token、工具数）。"
    }
    override val parameters = when (name) {
        "mcp_add" -> schemaOf(
            "name" to strParam("服务名（字母开头）"),
            "url" to strParam("MCP Streamable HTTP 端点"),
            "headers" to strParam("可选：请求头 JSON 对象，如 {\"Authorization\":\"Bearer x\"}"),
            "trust" to boolParam("信任该服务端工具（不再逐次确认）"),
            required = listOf("name", "url"),
        )
        "mcp_remove" -> schemaOf("name" to strParam("服务名"), "confirm" to boolParam("确认删除"), required = listOf("name"))
        "mcp_enable" -> schemaOf("name" to strParam("服务名"), "enabled" to boolParam("启用"), required = listOf("name", "enabled"))
        "mcp_tools" -> schemaOf("name" to strParam("服务名"), required = listOf("name"))
        "mcp_call" -> schemaOf(
            "server" to strParam("服务名"),
            "tool" to strParam("远端工具名"),
            "arguments" to strParam("参数 JSON 对象"),
            required = listOf("server", "tool"),
        )
        "mcp_server_start" -> schemaOf(
            "port" to intParam("本地端口，默认 8765"),
            "all" to boolParam("暴露写入类工具（默认只读）"),
        )
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "mcp_list" -> registry.listServers()
            "mcp_add" -> {
                val headers = when (val raw = args["headers"]) {
                    is JsonObject -> raw.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
                    is JsonPrimitive -> {
                        val obj = runCatching { Json.parseToJsonElement(raw.content).jsonObject }
                            .getOrElse { error("headers 必须是 JSON 对象") }
                        obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
                    }
                    else -> null
                }
                registry.add(
                    name = args.strArg("name") ?: error("缺少 name"),
                    url = args.strArg("url") ?: error("缺少 url"),
                    headers = headers,
                    trust = args.boolArg("trust") == true,
                )
            }
            "mcp_remove" -> registry.remove(args.strArg("name") ?: error("缺少 name"), args.boolArg("confirm") == true)
            "mcp_enable" -> registry.enable(args.strArg("name") ?: error("缺少 name"), args.boolArg("enabled") == true)
            "mcp_tools" -> registry.toolsOf(args.strArg("name") ?: error("缺少 name"))
            "mcp_call" -> {
                val arguments = when (val raw = args["arguments"]) {
                    is JsonObject -> raw
                    is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                    else -> JsonObject(emptyMap())
                }
                registry.callTool(
                    args.strArg("server") ?: error("缺少 server"),
                    args.strArg("tool") ?: error("缺少 tool"),
                    arguments,
                )
            }
            "mcp_reload" -> registry.reload()
            "mcp_server_start" -> registry.startServer(args.intArg("port") ?: 8765, args.boolArg("all") == true)
            "mcp_server_stop" -> registry.stopServer()
            else -> registry.serverStatus()
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}

/**
 * 反向 MCP server：把本机工具表通过 loopback HTTP 暴露给其它 agent / 桌面客户端。
 *
 * - 只绑定 127.0.0.1，必须带 `Authorization: Bearer <token>`；
 * - 默认不暴露 destructive 工具与所有 mcp_* 工具（避免递归与外部绕过确认）；
 * - 外部客户端可用 `adb forward tcp:8765 tcp:8765` 接入。
 */
internal class AndagentMcpServer(
    private val context: Context,
    private val tools: Map<String, Tool>,
) {
    private val prefs = context.getSharedPreferences("mcp_server", Context.MODE_PRIVATE)
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var port = 8765
    @Volatile private var exposeAll = false

    private fun token(): String {
        prefs.getString("token", null)?.takeIf { it.length >= 16 }?.let { return it }
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("token", token).apply()
        return token
    }

    @Synchronized fun start(requestedPort: Int, all: Boolean): String {
        require(requestedPort in 1024..65535) { "端口需在 1024-65535" }
        if (running) stop()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort))
        socket = ss
        port = requestedPort
        exposeAll = all
        running = true
        Thread({
            while (running && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(client) } }
            }
        }, "mcp-server").apply { isDaemon = true }.start()
        return status()
    }

    @Synchronized fun stop(): String {
        running = false
        runCatching { socket?.close() }
        socket = null
        return status()
    }

    fun status(): String = buildJsonObject {
        put("running", running)
        put("port", port)
        put("url", "http://127.0.0.1:$port/mcp")
        put("token", token())
        put("all", exposeAll)
        put("toolCount", exposed().size)
        put("hint", "外部客户端可先执行 adb forward tcp:$port tcp:$port，再连接 http://127.0.0.1:$port/mcp")
    }.toString()

    /**
     * 默认暴露面：排除 mcp_*（防递归）与已知的高危工具族。
     * 远端调用不经过本机确认弹窗，因此宁可漏放也不误放；`all=true` 才完整开放。
     */
    private fun exposed(): List<Tool> = tools.values.filter { tool ->
        if (tool.name.startsWith("mcp_")) return@filter false
        if (exposeAll) return@filter true
        !tool.destructive && DENY_PREFIXES.none { tool.name.startsWith(it) }
    }

    private companion object {
        val DENY_PREFIXES = listOf(
            "shell", "termux", "cmd", "exec",
            "file_", "zip_", "adb_", "ssh_", "scrcpy", "sftp_",
            "intent_", "credential_", "android_reflect", "web_embed",
            "email_", "sms_", "phone_", "call_log_", "contacts_add", "contacts_delete",
            "nfc_", "otg_", "ir_", "ble_", "bt_", "kv_", "db_", "sql", "store_",
            "http_request", "lan_scan", "fdroid_", "download_", "upload",
            "projection", "camera_", "record_", "audio_", "mic_",
            "ui_", "a11y_", "settings_", "media_", "notif_", "clipboard_",
        )
    }

    private fun handle(client: java.net.Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val request = readRequest(socket.getInputStream()) ?: return
            val out = socket.getOutputStream()
            if (request.method.equals("OPTIONS", true)) {
                respond(out, 204, "", extra = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Headers" to "authorization, content-type, mcp-protocol-version, mcp-session-id",
                    "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
                ))
                return
            }
            if (!request.method.equals("POST", true) || !request.path.substringBefore('?').trimEnd('/').endsWith("/mcp")) {
                respond(out, 404, """{"error":"not found"}""")
                return
            }
            val auth = request.headers["authorization"].orEmpty()
            if (auth != "Bearer ${token()}") {
                respond(out, 401, """{"error":"unauthorized"}""", extra = mapOf("WWW-Authenticate" to "Bearer"))
                return
            }
            val message = runCatching { Json.parseToJsonElement(request.body).jsonObject }.getOrNull()
            if (message == null) {
                respond(out, 400, """{"jsonrpc":"2.0","error":{"code":-32700,"message":"parse error"},"id":null}""")
                return
            }
            val response = dispatch(message)
            if (response == null) respond(out, 202, "") else respond(out, 200, response.toString())
        }
    }

    private fun dispatch(message: JsonObject): JsonObject? {
        val id = message["id"] ?: JsonNull
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32600, "missing method")
        // 通知（无 id）：只确认收到，不返回 JSON-RPC 响应。
        if (!message.containsKey("id")) return null
        return when (method) {
            "initialize" -> result(id, buildJsonObject {
                val requested = (message["params"] as? JsonObject)?.get("protocolVersion")
                put("protocolVersion", requested ?: JsonPrimitive(MCP_PROTOCOL_VERSION))
                put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                put("serverInfo", buildJsonObject { put("name", "andagent"); put("version", BuildConfig.VERSION_NAME) })
            })
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject {
                put("tools", buildJsonArray {
                    exposed().forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.parameters)
                        })
                    }
                })
            })
            "tools/call" -> {
                val params = message["params"] as? JsonObject ?: return error(id, -32602, "missing params")
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull ?: return error(id, -32602, "missing tool name")
                val tool = exposed().firstOrNull { it.name == toolName } ?: return error(id, -32602, "unknown tool: $toolName")
                val arguments = when (val raw = params["arguments"]) {
                    is JsonObject -> raw
                    is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                    else -> JsonObject(emptyMap())
                }
                runCatching { runBlocking { tool.execute(arguments) } }
                    .onSuccess { text ->
                        return result(id, buildJsonObject {
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text.take(200_000)) }) })
                            put("isError", false)
                        })
                    }
                    .onFailure { e ->
                        return result(id, buildJsonObject {
                            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "ERROR: ${e.message ?: e.javaClass.simpleName}") }) })
                            put("isError", true)
                        })
                    }
                @Suppress("UNREACHABLE_CODE") error(id, -32603, "unreachable")
            }
            "resources/list" -> result(id, buildJsonObject { put("resources", JsonArray(emptyList())) })
            "prompts/list" -> result(id, buildJsonObject { put("prompts", JsonArray(emptyList())) })
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun result(id: JsonElement, value: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("result", value)
    }

    private fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) break
            if (head.size() > 64 * 1024) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val at = line.indexOf(':')
            if (at <= 0) null else line.substring(0, at).trim().lowercase() to line.substring(at + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4 * 1024 * 1024) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(requestLine[0], requestLine[1], headers, String(body, 0, read, Charsets.UTF_8))
    }

    private fun respond(out: OutputStream, code: Int, body: String, extra: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(
                when (code) {
                    200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
                    401 -> "Unauthorized"; 404 -> "Not Found"; else -> "OK"
                }
            ).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (bytes.isNotEmpty()) out.write(bytes)
        out.flush()
    }
}

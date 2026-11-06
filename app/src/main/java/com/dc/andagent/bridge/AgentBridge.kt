package com.dc.andagent.bridge

import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dc.andagent.widget.WidgetApi
import com.dc.andagent.widget.data.WidgetDataScheduler
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.JsonFixer
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.cron.CronStore
import android.provider.Settings
import com.dc.andagent.ui.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.File
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * JS <-> Kotlin bridge.
 *
 * All JS calls arrive on WebView's JavaBridge thread; all JS dispatches are
 * posted to the main thread (evaluateJavascript is not thread-safe). Network
 * I/O runs on OkHttp's dispatcher, tool execution on a small executor.
 *
 * Events are pushed as a single JSON string into
 * `window.__andagent.receive(json)`.
 */
class AgentBridge(
    private val webViewProvider: () -> WebView?,
    private val settings: SettingsStore,
    private val tools: Map<String, Tool>,
    private val memoryStore: com.dc.andagent.memory.MemoryStore,
    private val skillRegistry: com.dc.andagent.tools.SkillRegistry? = null,
    private val permissions: PermissionBroker,
    private val appContext: Context? = null,
    private val appVersion: String = "",
    private val shizukuStatus: () -> String = { "{\"status\":\"unavailable\"}" },
    private val shizukuRequest: () -> String = { "{\"requested\":false}" },
    private val healthPermissionRequest: () -> String = { "{\"requested\":false}" },
    private val termuxStatus: () -> String = { "none" },
    private val termuxGrant: suspend () -> String = { "ERROR: Shizuku 不可用" },
    // R27: 对话流内嵌终端——与 shell_exec 共享同一条长驻 bash 会话。
    private val termuxTerminalExec: (String) -> String = { "ERROR: 终端通道不可用" },
    private val termuxTerminalWrite: (String) -> String = { "ERROR: 终端通道不可用" },
    private val termuxTerminalPoll: () -> String = { "" },
    // R25: 与同名 @JavascriptInterface 方法错开命名，避免调用点歧义。
    private val devtoolsStatusJson: () -> String = { "{\"enabled\":false,\"error\":\"devtools 桥不可用\"}" },
    private val devtoolsSetDebugging: (Boolean) -> String = { "ERROR: devtools 桥不可用" },
    // R28: MediaProjection 屏幕捕获（设置页卡片）。
    private val projectionStatusJson: () -> String = { "{\"channel\":\"none\",\"ready\":false}" },
    private val projectionRequest: () -> String = { "{\"requested\":false,\"message\":\"屏幕捕获授权不可用\"}" },
    private val projectionStop: () -> String = { "ERROR: 屏幕捕获通道不可用" },
    // R39: 主题切换——WebView 把解析后的深浅色回报给原生层（背景/状态栏同步）。
    private val themeChange: (Boolean) -> Unit = {},
    private val embeddedWebviewOpen: (String) -> String = { "ERROR: 浏览器不可用" },
    private val dbGuiOpen: (String) -> String = { "ERROR: 数据库浏览器不可用" },
    // R91.5: SSH 凭据只在原生界面录入；WebView 只能打开界面 / 看元数据 / 删除。
    private val sshCredentialOpen: () -> String = { "ERROR: SSH 凭据界面不可用" },
    private val sshCredentialListJson: () -> String = { "[]" },
    private val sshCredentialRemove: (String) -> String = { "ERROR: SSH 凭据存储不可用" },
    // R20: 八域 UI 命令（Toast/震动/沉浸/方向/导航/分享/安全/输入法/状态）。
    private val uiCommands: UiCommands? = null,
) {
    /** 统一兜底：UI 通道缺失或 UiCommands 内部异常都变成 "ERROR:" 字符串。 */
    private fun ui(call: (UiCommands) -> String): String = runCatching {
        uiCommands?.let(call) ?: "ERROR: UI 通道不可用"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val toolExecutor = Executors.newFixedThreadPool(2)
    private val calls = ConcurrentHashMap<String, Call>()
    // R39: 工具任务注册表——cancelTool(reqId) 中断正在执行的工具调用。
    private val toolFutures = ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
    // R39: 最近一次 shell_exec 的完整结果（终端卡片「查看 agent 输出」用）。
    @Volatile
    private var lastShellExecResult: String = ""

    @Volatile
    private var released = false

    /** {"version":"0.6.0"} — shown on the home screen. */
    @JavascriptInterface
    fun getAppVersion(): String =
        buildJsonObject { put("version", appVersion) }.toString()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE: keep the stream open
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun emitNotice(message: String) { emit(buildJsonObject { put("type", "notice"); put("message", message) }) }

    /* ------------------------- deep links (#14) ------------------------- */

    private val deeplinkQueue = java.util.ArrayDeque<JsonObject>()

    /**
     * Queue an `andagent://send?prompt=...&auto=1` intent and wake the page.
     * The payload is pulled by JS through [consumeDeeplink], so links that
     * launched the app before the bundle mounted are not lost.
     */
    fun queueDeeplink(uri: Uri) {
        val prompt = (uri.getQueryParameter("prompt") ?: uri.getQueryParameter("text"))
            ?.trim()?.take(2000)?.takeIf { it.isNotEmpty() } ?: return
        val auto = uri.getQueryParameter("auto") in setOf("1", "true", "yes")
        synchronized(deeplinkQueue) {
            deeplinkQueue.addLast(buildJsonObject {
                put("uri", uri.toString())
                put("prompt", prompt)
                put("auto", auto)
            })
            // Bound memory if the app is opened repeatedly without a WebView.
            while (deeplinkQueue.size > 20) deeplinkQueue.removeFirst()
        }
        emit(buildJsonObject { put("type", "deeplink.flush") })
    }

    /** JS-facing: drain queued deep links as a JSON array. */
    @JavascriptInterface
    fun consumeDeeplink(): String {
        val list = synchronized(deeplinkQueue) {
            val out = ArrayList<JsonObject>(deeplinkQueue)
            deeplinkQueue.clear()
            out
        }
        return JsonArray(list).toString()
    }

    @JavascriptInterface
    fun openEmbeddedWebview(uri: String): String = runCatching { embeddedWebviewOpen(uri) }
        .getOrElse { "ERROR: 打开内嵌浏览器失败：${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun dbGuiOpen(name: String): String = runCatching { dbGuiOpen.invoke(name) }
        .getOrElse { "ERROR: 打开数据库浏览器失败：${it.message ?: it.javaClass.simpleName}" }

    /* ------------------------ R91.5 SSH 凭据 -------------------------- */

    /**
     * 打开原生存放界面（密码/私钥在这里输入，永不经过 WebView 或模型）。
     * 设置页按钮与 `andagent://credentials` deeplink 共用此入口。
     */
    @JavascriptInterface
    fun sshCredentialsOpen(): String = runCatching { sshCredentialOpen() }
        .getOrElse { "ERROR: 打开 SSH 凭据界面失败：${it.message ?: it.javaClass.simpleName}" }

    /** 已保存凭据的元数据（nickname/host/port/user/authType），没有任何 secret。 */
    @JavascriptInterface
    fun sshCredentialsList(): String = runCatching { sshCredentialListJson() }
        .getOrElse { "[]" }

    @JavascriptInterface
    fun sshCredentialDelete(nickname: String): String = runCatching { sshCredentialRemove(nickname) }
        .getOrElse { "ERROR: 删除 SSH 凭据失败：${it.message ?: it.javaClass.simpleName}" }

    /* --------------------------- R20 UI 命令 ---------------------------- */

    @JavascriptInterface
    fun uiToast(message: String, long: Boolean): String = ui { it.toast(message, long) }

    @JavascriptInterface
    fun uiHaptic(pattern: String): String = ui { it.haptic(pattern) }

    @JavascriptInterface
    fun uiKeepScreenOn(enabled: Boolean): String = ui { it.keepScreenOn(enabled) }

    @JavascriptInterface
    fun uiImmersive(enabled: Boolean): String = ui { it.immersive(enabled) }

    @JavascriptInterface
    fun uiOrientation(mode: String): String = ui { it.orientation(mode) }

    @JavascriptInterface
    fun navBack(): String = ui { it.navBack() }

    @JavascriptInterface
    fun navMinimize(): String = ui { it.navMinimize() }

    @JavascriptInterface
    fun uiShare(text: String, subject: String): String = ui { it.share(text, subject) }

    @JavascriptInterface
    fun uiSecureFlag(enabled: Boolean): String = ui { it.secureFlag(enabled) }

    @JavascriptInterface
    fun uiKeyboard(show: Boolean): String = ui { it.keyboard(show) }

    @JavascriptInterface
    fun uiStatus(): String = ui { it.status() }

    @JavascriptInterface
    fun uiScreenMetrics(): String = ui { it.screenMetrics() }

    @JavascriptInterface
    fun webReload(): String = ui { it.reload() }

    /* ----------------------------- settings ----------------------------- */

    @JavascriptInterface
    fun getSettings(): String {
        // Pick up edits to Download/config.json without restarting the app.
        settings.reloadExternalConfig()
        return buildJsonObject {
            put("baseUrl", settings.baseUrl)
            put("apiKey", settings.apiKey)
            put("model", settings.model)
            put("systemPrompt", settings.systemPrompt)
            put("transcribeModel", settings.transcribeModel)
            put("transcribeBaseUrl", settings.transcribeBaseUrl)
            put("ttsBaseUrl", settings.ttsBaseUrl)
            put("ttsModel", settings.ttsModel)
            put("ttsVoice", settings.ttsVoice)
        }.toString()
    }

    @JavascriptInterface
    fun saveSettings(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        obj["baseUrl"]?.jsonPrimitive?.contentOrNull?.let { settings.baseUrl = it }
        obj["apiKey"]?.jsonPrimitive?.contentOrNull?.let { settings.apiKey = it }
        obj["model"]?.jsonPrimitive?.contentOrNull?.let { settings.model = it }
        obj["systemPrompt"]?.jsonPrimitive?.contentOrNull?.let { settings.systemPrompt = it }
        obj["transcribeModel"]?.jsonPrimitive?.contentOrNull?.let { settings.transcribeModel = it }
        obj["transcribeBaseUrl"]?.jsonPrimitive?.contentOrNull?.let { settings.transcribeBaseUrl = it }
        obj["ttsBaseUrl"]?.jsonPrimitive?.contentOrNull?.let { settings.ttsBaseUrl = it }
        obj["ttsModel"]?.jsonPrimitive?.contentOrNull?.let { settings.ttsModel = it }
        obj["ttsVoice"]?.jsonPrimitive?.contentOrNull?.let { settings.ttsVoice = it }
    }

    @JavascriptInterface
    fun memoryPrompt(query: String): String = runCatching { memoryStore.prompt(query) }.getOrDefault("")

    @JavascriptInterface
    fun memoryCall(name: String, raw: String): String {
        val tool = tools[name] ?: return "ERROR: 未知工具"
        if (name !in com.dc.andagent.memory.MEMORY_TOOLS) return "ERROR: 仅支持记忆工具"
        val args = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "ERROR: 参数不是 JSON 对象"
        return runCatching { runBlocking { tool.execute(args) } }.getOrElse { "ERROR: ${it.message}" }
    }

    /**
     * R53/R54: 前端 KV 通道（会话持久化 + 备份）。放行 KV_TOOLS 全量（含
     * kv_export/kv_import）：前端已占用 sessions 命名空间，导入会话备份会覆盖
     * 它，但 kv_import 的 replace 模式仍强制 confirm=true，merge 语义与
     * kv_set 同级；导出只读。参数与返回值与工具执行结果一致；写失败（如
     * 64KB 上限）以 "ERROR:" 前缀返回，前端据此回退 localStorage，绝不静默
     * 吞写失败。
     */
    @JavascriptInterface
    fun kvCall(name: String, raw: String): String {
        if (name !in com.dc.andagent.tools.KV_TOOLS) return "ERROR: 仅支持 KV 工具"
        val tool = tools[name] ?: return "ERROR: 未知工具"
        val args = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "ERROR: 参数不是 JSON 对象"
        return runCatching { runBlocking { tool.execute(args) } }.getOrElse { "ERROR: ${it.message}" }
    }

    /* --------------------------- permissions ---------------------------- */

    /**
     * Ask for runtime permissions on demand from JS (JSON array of manifest
     * permission names, or a comma/space separated list). The Activity Result
     * API is only touched on the main thread inside [PermissionBroker]; the
     * outcome is pushed back as a `permission.result` event.
     */
    @JavascriptInterface
    fun requestPermissions(names: String) {
        val requested = runCatching {
            val element = json.parseToJsonElement(names)
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else {
                null
            }
        }.getOrNull() ?: names.split(',', ' ', '\n').filter { it.isNotBlank() }

        if (requested.isEmpty()) {
            emitPermissionResult(emptyMap())
            return
        }
        permissions.request(requested) { granted -> emitPermissionResult(granted) }
    }

    private fun emitPermissionResult(granted: Map<String, Boolean>) {
        emit(buildJsonObject {
            put("type", "permission.result")
            put("granted", buildJsonObject {
                granted.forEach { (name, ok) -> put(name, ok) }
            })
        })
    }

    /* ------------------------- privilege / health ----------------------- */

    /** {"status":"granted"|"not_granted"|"unavailable", ...} for Settings.tsx. */
    @JavascriptInterface
    fun shizukuStatus(): String = runCatching { shizukuStatus() }.getOrElse {
        buildJsonObject {
            put("status", "unavailable")
            put("note", it.message ?: "Shizuku 状态读取失败")
        }.toString()
    }

    /** Main-thread posted by MainActivity; returns an immediate ack JSON. */
    @JavascriptInterface
    fun shizukuRequest(): String = runCatching { shizukuRequest() }.getOrElse {
        buildJsonObject {
            put("requested", false)
            put("message", it.message ?: "Shizuku 授权请求失败")
        }.toString()
    }

    /** Activity Result API is only touched on the main thread in MainActivity. */
    @JavascriptInterface
    fun healthPermissionRequest(): String = runCatching { healthPermissionRequest() }.getOrElse {
        buildJsonObject {
            put("requested", false)
            put("message", it.message ?: "Health Connect 授权请求失败")
        }.toString()
    }

    /**
     * Per-permission state without prompting: "granted" | "denied" |
     * "permanently_denied" (user checked "don't ask again" — the system
     * dialog would not show; user must flip it in app settings).
     */
    @JavascriptInterface
    fun permissionStatus(names: String): String {
        val requested = runCatching {
            val element = json.parseToJsonElement(names)
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive.contentOrNull }
            } else {
                null
            }
        }.getOrNull() ?: names.split(',', ' ', '\n').filter { it.isNotBlank() }
        return buildJsonObject {
            requested.forEach { name ->
                val state = runCatching { permissions.statusOf(name) }.getOrElse { "unknown" }
                put(name, state)
            }
        }.toString()
    }

    /** Deep link to the system app-settings page (for permanently denied perms). */
    @JavascriptInterface
    fun openAppSettings() {
        permissions.openAppSettings()
    }

    @JavascriptInterface
    fun termuxStatus(): String = termuxStatus()

    /** R25: WebView 远程调试状态（开关 + 可调试页面列表），给设置页用。 */
    @JavascriptInterface
    fun devtoolsStatus(): String = runCatching { devtoolsStatusJson() }
        .getOrElse { "{\"enabled\":false,\"error\":\"${it.message ?: it.javaClass.simpleName}\"}" }

    /** R25: 设置页开关 → 运行时切换 setWebContentsDebuggingEnabled + SP 持久化。 */
    @JavascriptInterface
    fun devtoolsSetEnabled(enabled: Boolean): String = runCatching { devtoolsSetDebugging(enabled) }
        .getOrElse { "ERROR: 切换 DevTools 失败：${it.message ?: it.javaClass.simpleName}" }

    /** R28: 屏幕捕获通道状态 {"channel":"projection"|"shizuku"|"none","ready":bool,"recording":bool}。 */
    @JavascriptInterface
    fun projectionStatus(): String = runCatching { projectionStatusJson() }
        .getOrElse { "{\"channel\":\"none\",\"ready\":false,\"error\":\"${it.message ?: it.javaClass.simpleName}\"}" }

    /** R28: 发起系统屏幕捕获授权弹窗（透明 trampoline Activity），返回即时 ack。 */
    @JavascriptInterface
    fun projectionRequest(): String = runCatching { projectionRequest() }
        .getOrElse { buildJsonObject {
            put("requested", false)
            put("message", it.message ?: "屏幕捕获授权请求失败")
        }.toString() }

    /** R28: 停止进行中的投影录屏并释放 MediaProjection。 */
    @JavascriptInterface
    fun projectionStop(): String = runCatching { projectionStop() }
        .getOrElse { "ERROR: 停止屏幕捕获失败：${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun termuxGrantViaShizuku(): String = runCatching { runBlocking { termuxGrant() } }
        .getOrElse { "ERROR: Termux 授权失败：${it.message ?: it.javaClass.simpleName}" }

    /** R27: 终端执行一行命令（写入共享 bash stdin，带 tty 弱 marker）。同步返回状态。 */
    @JavascriptInterface
    fun termuxTerminalExec(reqId: String, command: String): String =
        runCatching { termuxTerminalExec(command.take(4000)) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    /** R27: 终端裸写入 stdin（交互程序应答），不带换行时前端补 \n。 */
    @JavascriptInterface
    fun termuxTerminalWrite(reqId: String, input: String): String =
        runCatching { termuxTerminalWrite(input.take(4000)) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    /** R27: 拉取自上次 poll 以来的终端增量输出（≤16KB），空串表示无新增。 */
    @JavascriptInterface
    fun termuxTerminalPoll(reqId: String): String =
        runCatching { termuxTerminalPoll() }.getOrElse { "" }

    @JavascriptInterface
    fun exportConversation(title: String, markdown: String): String {
        val context = appContext ?: return "ERROR: Android 环境不可用"
        if (markdown.isBlank()) return "ERROR: 会话内容为空"
        if (markdown.length > 5_000_000) return "ERROR: 导出内容超过 5MB"
        val filename = (title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(64)
            .ifBlank { "对话" }) + "-${System.currentTimeMillis()}.md"
        return runCatching {
            if (Build.VERSION.SDK_INT < 29) {
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent")
                if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录")
                val file = java.io.File(dir, filename)
                file.writeText(markdown)
                file.absolutePath
            } else {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/andagent/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: error("MediaStore 无法创建文件")
                try {
                    resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(markdown) }
                        ?: error("无法打开导出文件")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/andagent/$filename"
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
        }.onSuccess { path -> emit(buildJsonObject { put("type", "conversation.exported"); put("path", path) }) }
            .getOrElse { "ERROR: 导出失败：${it.message ?: it.javaClass.simpleName}" }
    }

    @JavascriptInterface
    fun widgetList(): String = runCatching { WidgetApi.list(requireNotNull(appContext)) }.getOrDefault("[]")

    @JavascriptInterface
    fun cronList(): String = runCatching {
        JsonArray(CronStore(requireNotNull(appContext)).list().map { it.json() }).toString()
    }.getOrDefault("[]")

    @JavascriptInterface
    fun cronToggle(id: String, enabled: Boolean): String = runCatching {
        val context = requireNotNull(appContext)
        var found = false
        CronStore(context).update { tasks ->
            val index = tasks.indexOfFirst { it.id == id }
            if (index >= 0) {
                found = true
                val task = tasks[index]
                tasks[index] = task.copy(enabled = enabled, failures = if (enabled) 0 else task.failures,
                    nextRun = if (enabled) com.dc.andagent.cron.CronSchedule.parse(task.schedule).next(System.currentTimeMillis()) else null)
            }
        }
        require(found) { "任务不存在" }
        CronScheduler.rebuild(context)
        "OK"
    }.getOrElse { "ERROR: ${it.message}" }

    @JavascriptInterface
    fun cronDelete(id: String): String = runCatching {
        val context = requireNotNull(appContext)
        var found = false
        CronStore(context).update { found = it.removeAll { task -> task.id == id } }
        require(found) { "任务不存在" }
        CronScheduler.rebuild(context)
        "OK"
    }.getOrElse { "ERROR: ${it.message}" }

    @JavascriptInterface
    fun cronPauseAll(): String = runCatching {
        val context = requireNotNull(appContext)
        CronStore(context).update { tasks ->
            for (i in tasks.indices) tasks[i] = tasks[i].copy(enabled = false, nextRun = null)
        }
        CronScheduler.rebuild(context)
        "OK"
    }.getOrElse { "ERROR: ${it.message}" }

    @JavascriptInterface
    fun cronExactAllowed(): Boolean = appContext?.let(CronScheduler::exactAllowed) ?: false

    @JavascriptInterface
    fun cronOpenExactSettings(): String = runCatching {
        val context = requireNotNull(appContext)
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = android.content.Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        "OK"
    }.getOrElse { "ERROR: ${it.message}" }

    @JavascriptInterface
    fun widgetSave(specJson: String, id: String): String = runCatching {
        WidgetApi.save(requireNotNull(appContext), specJson, id.ifBlank { null })
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetPin(id: String): String = runCatching {
        WidgetApi.pin(requireNotNull(appContext), id)
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetRefresh(id: String): String = runCatching {
        val context = requireNotNull(appContext)
        val record = com.dc.andagent.widget.data.WidgetStore(context).read().records.first { it.id == id }
        WidgetDataScheduler.requestNow(context, id, record.spec)
        "已安排刷新"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun widgetDelete(id: String): String = runCatching {
        WidgetApi.delete(requireNotNull(appContext), id)
        "已删除"
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    @JavascriptInterface
    fun extensionTools(): String = kotlinx.serialization.json.JsonArray(tools.values.filter { it.name.startsWith("skill_") }.map { tool ->
        buildJsonObject { put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters) }
    }).toString()

    @JavascriptInterface
    fun skillPrompt(): String = skillRegistry?.prompt().orEmpty()

    @JavascriptInterface
    fun skillManage(action: String, name: String, value: String): String = runCatching {
        val registry = requireNotNull(skillRegistry)
        when (action) {
            "list" -> registry.list()
            "reload" -> registry.reload()
            "enable" -> registry.enable(name, value == "true")
            "delete" -> registry.delete(name, value == "true")
            "install" -> registry.install(value)
            "search" -> registry.search(value)
            "index" -> { registry.setIndex(value); "OK" }
            else -> "ERROR: 未知操作"
        }
    }.getOrElse { "ERROR: ${it.message}" }

    /* ------------------------------ tools ------------------------------- */

    /**
     * Server-side speech-to-text: POST the recorded file to
     * <baseUrl>/audio/transcriptions (OpenAI-compatible), return the text.
     * Runs on OkHttp's dispatcher; result comes back via the tool channel
     * semantics (single JSON payload event, not SSE).
     */
    @JavascriptInterface
    fun transcribeAudio(reqId: String, filePath: String, language: String) {
        toolExecutor.execute {
            val file = java.io.File(filePath)
            if (!file.exists() || file.length() == 0L) {
                emitToolResult(reqId, false, "ERROR: 音频文件不存在或为空: $filePath")
                return@execute
            }
            val key = settings.apiKey
            if (key.isBlank()) {
                emitToolResult(reqId, false, "ERROR: 未配置 API Key，无法调用转写服务")
                return@execute
            }
            val url = (settings.transcribeBaseUrl.ifBlank { settings.baseUrl }).trimEnd('/') + "/audio/transcriptions"
            val sttModel = settings.transcribeModel.ifBlank { "whisper-1" }
            val body = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    file.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", sttModel)
                .addFormDataPart("language", language.ifBlank { "zh" })
                .addFormDataPart("response_format", "json")
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            val result = runCatching {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty().take(8000)
                    if (resp.isSuccessful) {
                        val parsed = runCatching {
                            json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                        }.getOrNull()
                        parsed ?: text
                    } else {
                        "ERROR: 转写失败 HTTP ${resp.code}: $text"
                    }
                }
            }.getOrElse { "ERROR: 转写请求异常（${it.message ?: it.javaClass.simpleName}）" }
            emitToolResult(reqId, !result.startsWith("ERROR:"), result)
        }
    }

    /**
     * Save a base64-encoded audio blob (from the WebView recorder) to an app
     * file so it can be POSTed to the transcription endpoint. Returns the
     * absolute path as JSON.
     */
    @JavascriptInterface
    fun saveAudioBase64(reqId: String, base64: String): String {
        val ctx = appContext ?: run {
            return buildJsonObject { put("ok", false); put("message", "ERROR: bridge 无 context") }.toString()
        }
        val result = runCatching {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val out = java.io.File(
                ctx.getExternalFilesDir(null) ?: ctx.filesDir,
                "voice_${System.currentTimeMillis()}.wav",
            )
            out.writeBytes(bytes)
            out.absolutePath
        }.getOrElse { "ERROR: 保存音频失败（${it.message ?: it.javaClass.simpleName}）" }
        return if (result.startsWith("ERROR:")) {
            buildJsonObject { put("ok", false); put("message", result) }.toString()
        } else {
            buildJsonObject { put("ok", true); put("path", result) }.toString()
        }
    }

    /** R39: WebView 回报解析后的主题（true=夜间），原生侧同步背景与系统栏。 */
    @JavascriptInterface
    fun reportTheme(isDark: Boolean) {
        runCatching { themeChange(isDark) }
    }

    /** R39: 最近一次 shell_exec 的完整结果；空串表示尚未执行过。 */
    @JavascriptInterface
    fun lastShellResult(): String = lastShellExecResult

    /**
     * R39: 中断正在执行的工具调用。future.cancel(true) 打断工具线程——
     * TermuxSession 的 timedWait / TermuxAppBackend 的 latch.await 等阻塞点
     * 会立即抛 InterruptedException；但已在后台运行的子进程与 QuickJS
     * 原生循环不受影响（详见战报限制节）。
     */
    @JavascriptInterface
    fun cancelTool(reqId: String) {
        val future = toolFutures.remove(reqId) ?: return
        if (future.cancel(true)) {
            emitToolResult(reqId, false, "ERROR: 已被用户中断")
        }
    }

    @JavascriptInterface
    fun toolExec(reqId: String, name: String, argsJson: String) {
        val future = toolExecutor.submit {
            if (name == "widget_save_and_pin") {
                val result = runCatching {
                    val args = JsonFixer.parseObject(argsJson)
                    val raw = args["spec_json"]?.jsonPrimitive?.content ?: error("缺少 spec_json")
                    val context = requireNotNull(appContext)
                    val id = WidgetApi.save(context, raw)
                    "已保存组件 $id。${WidgetApi.pin(context, id)}"
                }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                emitToolResult(reqId, !result.startsWith("ERROR:"), result)
                return@submit
            }
            val tool = tools[name]
            if (tool == null) {
                emitToolResult(reqId, false, "ERROR: unknown tool '$name'")
                return@submit
            }
            val args = runCatching {
                JsonFixer.parseObject(argsJson.ifBlank { "{}" })
            }.getOrElse {
                emitToolResult(reqId, false, "ERROR: bad arguments JSON: ${it.message}")
                return@submit
            }
            val result = runCatching { runBlocking { tool.execute(args) } }
                .recoverCatching { e ->
                    if (e is java.util.concurrent.CancellationException ||
                        e is InterruptedException
                    ) {
                        "ERROR: 已被用户中断"
                    } else {
                        throw e
                    }
                }
                .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            if (name == "shell_exec") {
                lastShellExecResult = (args["cmd"]?.jsonPrimitive?.contentOrNull?.let { "$ $it\n" } ?: "") + result
            }
            emitToolResult(reqId, !result.startsWith("ERROR:"), mediaPreviewResult(name, result))
        }
        toolFutures[reqId] = future
        // 先注册再查完成态，堵住「任务瞬间跑完 + cancel 永远 miss」的窗口。
        if (future.isDone) toolFutures.remove(reqId, future)
    }

    private fun mediaPreviewResult(toolName: String, raw: String): String {
        if (toolName !in setOf("file_download", "screen_record_h264", "screen_record_stop") || raw.startsWith("ERROR:")) return raw
        return runCatching {
            val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val path = obj?.get("path")?.jsonPrimitive?.contentOrNull
                ?: Regex("/(?:sdcard|storage/emulated/0)/Movies/andagent_[^\\s,，]+\\.mp4").find(raw)?.value
                ?: return@runCatching raw
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("mp3", "m4a", "mp4", "webm", "ogg", "wav", "flac")) return@runCatching raw
            val ctx = appContext ?: return@runCatching raw
            val root = ctx.getExternalFilesDir(null) ?: return@runCatching raw
            val source = File(path).canonicalFile
            val allowed = listOf("/sdcard/Download/andagent/", "/storage/emulated/0/Download/andagent/", "/sdcard/Movies/andagent_", "/storage/emulated/0/Movies/andagent_")
            if (allowed.none { path.startsWith(it) }) return@runCatching raw
            val target = File(root, "media-previews/${System.currentTimeMillis()}_${source.name}")
            target.parentFile?.mkdirs()
            val input = obj?.get("uri")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("content://") }?.let { ctx.contentResolver.openInputStream(Uri.parse(it)) }
                ?: source.inputStream()
            input.use { stream -> target.outputStream().use { output -> stream.copyTo(output) } }
            if (obj == null) "$raw\n预览文件：${target.absolutePath}"
            else buildJsonObject { obj.forEach { (key, value) -> put(key, value) }; put("previewPath", target.absolutePath) }.toString()
        }.getOrDefault(raw)
    }

    private fun emitToolResult(reqId: String, ok: Boolean, result: String) {
        emit(buildJsonObject {
            put("type", "tool.result")
            put("id", reqId)
            put("ok", ok)
            put("result", result)
        })
    }

    /* --------------------------- LLM transport -------------------------- */

    @JavascriptInterface
    fun llmFetchStart(reqId: String, url: String, headersJson: String, body: String) {
        llmRequestStart(reqId, url, headersJson, body, "POST")
    }

    /** Model setup also needs GET /models; keep the original streaming entry compatible. */
    @JavascriptInterface
    fun llmRequestStart(reqId: String, url: String, headersJson: String, body: String, method: String) {
        require(method == "GET" || method == "POST") { "Unsupported model request method" }
        val headers = runCatching { json.parseToJsonElement(headersJson).jsonObject }.getOrNull()
        val builder = Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            if (key.lowercase() !in HOP_BY_HOP) {
                value.jsonPrimitive.contentOrNull?.let { builder.header(key, it) }
            }
        }
        if (method == "GET") builder.get() else builder.post(body.toRequestBody(JSON_MEDIA))

        val call = http.newCall(builder.build())
        calls[reqId] = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(reqId)
                if (call.isCanceled()) {
                    emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                } else {
                    emitError(reqId, e.message ?: "network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val pump = ChunkPump(mainHandler) { lines ->
                    emit(buildJsonObject {
                        put("type", "llm.chunks")
                        put("id", reqId)
                        put("lines", JsonArray(lines.map { JsonPrimitive(it) }))
                    })
                }
                try {
                    response.use { resp ->
                        emit(buildJsonObject {
                            put("type", "llm.headers")
                            put("id", reqId)
                            put("status", resp.code)
                            put("statusText", resp.message)
                            put("headers", buildJsonObject {
                                resp.headers.names().forEach { name ->
                                    put(name, resp.headers.values(name).joinToString(","))
                                }
                            })
                        })

                        val source = resp.body?.source()
                        if (source != null) {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (!call.isCanceled()) pump.add(line)
                            }
                        }
                    }
                    pump.finish {
                        emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                    }
                } catch (e: IOException) {
                    pump.finish {
                        if (call.isCanceled()) {
                            emit(buildJsonObject { put("type", "llm.done"); put("id", reqId) })
                        } else {
                            emitError(reqId, e.message ?: "stream error")
                        }
                    }
                } finally {
                    calls.remove(reqId)
                }
            }
        })
    }

    @JavascriptInterface
    fun llmCancel(reqId: String) {
        calls.remove(reqId)?.cancel()
    }

    private fun emitError(reqId: String, message: String) {
        emit(buildJsonObject {
            put("type", "llm.error")
            put("id", reqId)
            put("message", message)
        })
    }

    /* ---------------------------- plumbing ----------------------------- */

    private fun emit(event: JsonObject) {
        if (released) return
        val payload = event.toString()
        // JsonPrimitive(payload).toString() is a correctly escaped JS string literal.
        val script = "window.__andagent&&window.__andagent.receive(${JsonPrimitive(payload)})"
        mainHandler.post {
            if (!released) webViewProvider()?.evaluateJavascript(script, null)
        }
    }

    fun shutdown() {
        released = true
        calls.values.forEach { it.cancel() }
        calls.clear()
        toolFutures.clear()
        toolExecutor.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Coalesces SSE lines for ~16ms so a fast token stream does not flood the
     * main thread with one evaluateJavascript call per line.
     */
    private class ChunkPump(
        private val handler: Handler,
        private val sink: (List<String>) -> Unit,
    ) {
        private val lock = Any()
        private val buffer = ArrayList<String>(64)
        private var scheduled = false

        fun add(line: String) {
            val needSchedule: Boolean
            synchronized(lock) {
                buffer.add(line)
                needSchedule = !scheduled
                scheduled = true
            }
            if (needSchedule) handler.postDelayed({ flush() }, 16L)
        }

        fun flush() {
            val batch: List<String>
            synchronized(lock) {
                if (buffer.isEmpty()) {
                    scheduled = false
                    return
                }
                batch = buffer.toList()
                buffer.clear()
                scheduled = false
            }
            sink(batch)
        }

        /** Flush the tail, then run [after] on the main thread — keeps chunks ahead of done. */
        fun finish(after: () -> Unit) {
            handler.post {
                flush()
                after()
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val HOP_BY_HOP = setOf(
            "host", "content-length", "connection", "transfer-encoding",
            "accept-encoding", "keep-alive", "upgrade", "proxy-connection",
        )
    }
}

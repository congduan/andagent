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
import com.dc.andagent.tools.KvTool
import com.dc.andagent.tools.JsonFixer
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.SshTransferCancel
import com.dc.andagent.tools.SshTransferEvent
import com.dc.andagent.tools.SshSessionPool
import com.dc.andagent.tools.AdbSessionHub
import com.dc.andagent.tools.SafTreeStore
import com.dc.andagent.scrcpy.AdbScrcpyService
import com.dc.andagent.cron.CronScheduler
import com.dc.andagent.cron.CronStore
import com.dc.andagent.cron.CronA2uiInbox
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
import java.security.SecureRandom
import java.security.MessageDigest

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
    private val mcpRegistry: com.dc.andagent.tools.McpRegistry? = null,
    private val permissions: PermissionBroker,
    private val appContext: Context? = null,
    private val appVersion: String = "",
    private val healthPermissionRequest: () -> String = { "{\"requested\":false}" },
    private val termuxStatus: () -> String = { "none" },
    private val termuxGrant: suspend () -> String = { "ERROR: 未获 Termux RUN_COMMAND 权限" },
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
    // R39 修复：系统夜间状态真值（"dark"/"light"）。WebView 的
    // prefers-color-scheme 取自宿主主题，应用是深色主题时会恒为 dark。
    private val systemThemeProvider: () -> String = { "light" },
    private val embeddedWebviewOpen: (String) -> String = { "ERROR: 浏览器不可用" },
    private val embeddedResourceVisible: () -> Boolean = { false },
    private val embeddedResourceClose: (String) -> String = { "ERROR: 浏览器不可用" },
    private val dbGuiOpen: (String) -> String = { "ERROR: 数据库浏览器不可用" },
    // R91.5: SSH 凭据只在原生界面录入/编辑；WebView 只能打开界面 / 看元数据 / 删除。
    private val sshCredentialOpen: () -> String = { "ERROR: SSH 凭据界面不可用" },
    private val sshCredentialEditOpen: (String) -> String = { "ERROR: SSH 凭据界面不可用" },
    private val sshCredentialListJson: () -> String = { "[]" },
    private val sshCredentialRemove: (String) -> String = { "ERROR: SSH 凭据存储不可用" },
    // R102: 外部非私有存储镜像——换签名重装后据此恢复数据。
    private val externalStateStatusJson: () -> String = { "{\"enabled\":false,\"path\":\"\",\"writable\":false}" },
    private val externalStateSync: () -> String = { "ERROR: 外部备份不可用" },
    private val externalStateRestore: () -> String = { "ERROR: 外部备份不可用" },
    private val externalStateSetEnabled: (Boolean) -> String = { "ERROR: 外部备份不可用" },
    private val externalStateWriteConfig: () -> String = { "ERROR: 外部备份不可用" },
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
    private val sshToolKeys = ConcurrentHashMap<String, String>()
    // R39: 最近一次 shell_exec 的完整结果（终端卡片「查看 agent 输出」用）。
    @Volatile
    private var lastShellExecResult: String = ""

    @Volatile
    private var released = false

    // R105: publish the live bridge so a background CronWorker can flush an
    // a2ui form into the WebView without holding an Activity reference here.
    init { liveInstance = this }

    // R96 review 必修项：设置通道主帧能力凭证。@JavascriptInterface 会把
    // AndroidBridge 注入所有 iframe（canvas/HTML 卡片 sandbox opaque origin、
    // web_embed 跨源网页），键名白名单挡不住 iframe 直调。token 仅由 MainActivity
    // 在 onPageFinished 经 evaluateJavascript（只作用于主帧）注入主帧 window，
    // iframe 读不到 parent.window，故拿不到该凭证；kvSettingCall 校验不符即拒绝。
    @Volatile
    private var settingToken: String = newSettingToken()

    /** {"version":"0.6.0"} — shown on the home screen. */
    @JavascriptInterface
    fun getAppVersion(): String =
        buildJsonObject { put("version", appVersion) }.toString()

    /**
     * 语言设置由 web 层持有（localStorage），同步给原生，让工具结果与
     * 原生界面跟界面语言一致。
     */
    @JavascriptInterface
    fun setUiLanguage(mode: String): String {
        com.dc.andagent.i18n.Lang.setMode(appContext, mode)
        return "OK"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE: keep the stream open
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val activeTransfers = ConcurrentHashMap<String, ResourceItem>()
    private val resourceObservedAt = ConcurrentHashMap<String, Long>()

    fun emitNotice(message: String) { emit(buildJsonObject { put("type", "notice"); put("message", message) }) }

    fun emitTransferProgress(event: SshTransferEvent) {
        if (event.type == "ssh.transfer.done") activeTransfers.remove(event.key)
        else activeTransfers.putIfAbsent(event.key, ResourceItem("ssh_transfer", event.key,
            event.name, event.direction, System.currentTimeMillis()))
        emit(buildJsonObject {
            put("type", event.type); put("key", event.key); put("name", event.name)
            put("direction", event.direction); put("percent", event.percent)
            put("bytes", event.bytes); put("total", event.total)
            if (event.type == "ssh.transfer.done") {
                put("ok", event.ok)
                event.savedPath?.let { put("savedPath", it) }
            }
        })
    }

    @JavascriptInterface
    fun cancelSshTransfer(key: String) { if (key.isNotBlank()) SshTransferCancel.request(key) }

    /**
     * R110-review 必修项①：资源中心唯一入口。此前 list/close 两个独立
     * @JavascriptInterface 会被注入所有 iframe，canvas/HTML 卡片 sandbox、
     * web_embed 跨源页可直调，绕过面板 appConfirm 关闭资源、读取连接元数据。
     * 与 kvSettingCall 同模式：args JSON 内携带仅注入主帧 window 的 __token，
     * ResourceHub.parseAction 做恒定时间比对，不符/解析失败立即拒绝，再按 action
     * 分发到私有 list/close 路由（不再暴露给 iframe）。
     */
    @JavascriptInterface
    fun resource_hub_call(raw: String): String {
        val action = ResourceHub.parseAction(raw, settingToken) ?: return "ERROR: 资源中心凭证无效"
        return when (action.action) {
            "list" -> hubList()
            "close" -> {
                val kind = action.kind
                val id = action.id
                if (kind == null || id == null) "ERROR: 关闭操作缺少 kind/id"
                else hubClose(kind, id)
            }
            else -> "ERROR: 未知操作"
        }
    }

    private fun hubList(): String = runCatching {
        // R110-review 建议项：传输异常退出不发 ssh.transfer.done 会永久残留，
        // 快照前清理超过 24h 的陈旧条目（兜底，不依赖 done 事件）。
        activeTransfers.values.removeAll { System.currentTimeMillis() - (it.aliveSince ?: 0L) > 24L * 3600 * 1000 }
        val context = requireNotNull(appContext)
        // Each source returns its own snapshot; no lock is held across source calls.
        SshSessionPool.pruneDisconnected()
        val ssh = SshSessionPool.registry.list().map {
            ResourceItem("ssh", it.id, it.nickname ?: "${it.user}@${it.host}", "${it.host}:${it.port}", it.startedAt)
        }
        // R133c：资源中心显示【全部已保存目标】而非仅 live——连接断开后目标从面板
        // 消失造成「资源不显示」（真机反馈）。subtitle 标注连接状态。
        // R110-review 必修项②：id 用 AdbTarget.key（host:port），避免同 host
        // 多端口时 React key 重复、close 经 registry.resolve 误关首项。
        val adb = AdbSessionHub.registry.list().map { t ->
            val alive = AdbSessionHub.isLive(t.key)
            ResourceItem("adb", t.key, t.host, "${t.host}:${t.port}（${if (alive) "已连接" else "未连接"}）", if (alive) System.currentTimeMillis() else null)
        }
        val saf = SafTreeStore.list(context).map { ResourceItem("saf", it, it.substringAfterLast('/'), it) }
        val embedded = if (embeddedResourceVisible()) listOf(ResourceItem("embedded", "browser", "WebView", "embedded")) else emptyList()
        val transfer = activeTransfers.values.toList()
        val scrcpy = if (AdbScrcpyService.isRunning()) listOf(ResourceItem("scrcpy", "service", "scrcpy", "投屏服务")) else emptyList()
        val cron = CronStore(context).list().filter { it.enabled }.map {
            ResourceItem("cron", it.id, it.name, it.schedule)
        }
        val groups = listOf(ssh, adb, saf, embedded, transfer, scrcpy, cron)
        val keys = groups.flatten().map { "${it.kind}:${it.id}" }.toSet()
        resourceObservedAt.keys.retainAll(keys)
        ResourceHub.snapshot(groups.map { group -> group.map { item ->
            item.copy(aliveSince = item.aliveSince ?: resourceObservedAt.computeIfAbsent("${item.kind}:${item.id}") { System.currentTimeMillis() })
        } })
    }.getOrElse {
        // 失败必须与“没有资源”区分：此前返回 "[]"，面板会把读取失败显示成空态。
        "ERROR: 资源中心读取失败：${it.message ?: it.javaClass.simpleName}"
    }

    private fun hubClose(kind: String, id: String): String = runCatching {
        val context = requireNotNull(appContext)
        ResourceHub.close(kind, id, mapOf(
            "ssh" to { ref ->
                // closeByRef：先摘登记再断开，ref 同时支持 session_id 与 nickname。
                if (SshSessionPool.closeByRef(ref) == null) "ERROR: SSH 会话不存在" else "OK"
            },
            "adb" to { host -> AdbSessionHub.disconnect(host) },
            "saf" to { uri ->
                require(uri in SafTreeStore.list(context)) { "授权树不存在" }
                val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri }
                if (grant != null) {
                    val flags = (if (grant.isReadPermission) android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (grant.isWritePermission) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    if (flags != 0) context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), flags)
                }
                SafTreeStore.remove(context, uri); "OK"
            },
            "embedded" to { embeddedResourceClose(it) },
            "ssh_transfer" to { key ->
                if (!activeTransfers.containsKey(key)) "ERROR: 传输不存在" else {
                    SshTransferCancel.request(key); "OK"
                }
            },
            "scrcpy" to { AdbScrcpyService.stop(context) },
            "cron" to { taskId -> cronToggle(taskId, false) },
        ))
    }.getOrElse { "ERROR: ${it.message}" }

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
        enqueueDeeplink(buildJsonObject {
            put("uri", uri.toString())
            put("prompt", prompt)
            put("auto", auto)
        })
    }

    /**
     * R111: 系统分享（ACTION_SEND）走同一条队列——浏览器分享的文字/链接填进输入框
     * （auto=false，让用户补一句再发），相册分享的图片带上落盘路径 [imagePath]，
     * 由 R88 图片输入链路按路径读取附件。
     */
    fun queueShare(text: String, imagePath: String?) {
        val prompt = text.trim().take(4000)
        if (prompt.isEmpty() && imagePath == null) return
        enqueueDeeplink(buildJsonObject {
            put("uri", "share://intent")
            put("prompt", prompt)
            put("auto", false)
            put("source", "share")
            imagePath?.let { put("image", it) }
        })
    }

    private fun enqueueDeeplink(payload: JsonObject) {
        synchronized(deeplinkQueue) {
            deeplinkQueue.addLast(payload)
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

    /* ------------------- R105: cron a2ui form handoff ----------------- */

    /** Signal the WebView to drain queued cron a2ui forms (idempotent). */
    internal fun flushA2ui() {
        emit(buildJsonObject { put("type", "a2ui.flush") })
    }

    /** JS-facing: drain queued a2ui fences as a JSON array of strings. */
    @JavascriptInterface
    fun consumeA2uiForms(): String {
        // R105 review 必修项 1：持久化收件箱是唯一事实源——pushA2uiForm 只写
        // inbox（不再同时入内存队列，否则 drain 会返回同一 fence 两次），
        // 进程被杀后表单仍可读回。drain 即删除（消费语义）：JS 侧
        // appendA2uiForm 会把消息写进会话 KV。
        val persisted = appContext?.let { CronA2uiInbox.drainAll(it) } ?: emptyList()
        return JsonArray(persisted.map { JsonPrimitive(it) }).toString()
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

    /** 只把 nickname 传给原生编辑界面；secret 不经 WebView，也不会回显。 */
    @JavascriptInterface
    fun sshCredentialEdit(nickname: String): String = runCatching { sshCredentialEditOpen(nickname) }
        .getOrElse { "ERROR: 打开 SSH 凭据编辑界面失败：${it.message ?: it.javaClass.simpleName}" }

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

    /* ------------------- R102 外部存储（换签名重装恢复） ------------------- */

    /** {"enabled","degraded","path","writable","hasSnapshot","lastSyncAt",...} */
    @JavascriptInterface
    fun externalStateStatus(): String = runCatching { externalStateStatusJson() }.getOrElse {
        buildJsonObject {
            put("enabled", false)
            put("path", "")
            put("writable", false)
            put("error", it.message ?: "外部备份状态读取失败")
        }.toString()
    }

    /** 立即把白名单数据镜像到公共目录，返回单行结果。 */
    @JavascriptInterface
    fun externalStateSync(): String = runCatching { externalStateSync() }.getOrElse {
        "ERROR: 备份失败：${it.message ?: it.javaClass.simpleName}"
    }

    /** 从外部快照恢复（会覆盖本机数据，调用方必须已确认）。 */
    @JavascriptInterface
    fun externalStateRestore(): String = runCatching { externalStateRestore() }.getOrElse {
        "ERROR: 恢复失败：${it.message ?: it.javaClass.simpleName}"
    }

    @JavascriptInterface
    fun externalStateSetEnabled(enabled: Boolean): String = runCatching { externalStateSetEnabled(enabled) }
        .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    /** 把当前模型配置写成 Download/config.json（含 API Key，跨安装可用）。 */
    @JavascriptInterface
    fun externalStateWriteConfig(): String = runCatching { externalStateWriteConfig() }.getOrElse {
        "ERROR: 写入配置失败：${it.message ?: it.javaClass.simpleName}"
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
     *
     * R96: secure 命名空间是加密敏感配置存储，对模型/QuickJS/canvas/live iframe
     * 一律拒绝（本方法走 KvTool 实例默认全拒策略）。设置页读写 secure/engines
     * 必须走专用窄通道 [kvSettingCall]——本方法不留任何 secure 例外，堵住
     * CanvasCard 等 RPC 转发路径把模型生成代码的 KV 请求送达敏感配置的绕过面。
     */
    @JavascriptInterface
    fun kvCall(name: String, raw: String): String {
        if (name !in com.dc.andagent.tools.KV_TOOLS) return "ERROR: 仅支持 KV 工具"
        val tool = tools[name] ?: return "ERROR: 未知工具"
        val args = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "ERROR: 参数不是 JSON 对象"
        return runCatching { runBlocking { tool.execute(args) } }.getOrElse { "ERROR: ${it.message}" }
    }

    /**
     * R96: 设置页专用 secure 通道（仅 R96 搜索引擎配置页调用）。只放行
     * kv_get/kv_set 的 secure/engines 单键读写，其余 secure 操作与工具名一律拒绝；
     * 与 [kvCall]（全拒）严格分离，避免 canvas/live iframe 的 RPC 转发借道读写
     * 敏感配置。响应形状与 kv_get/kv_set 完全一致。
     *
     * R96 review 必修项：@JavascriptInterface 会把 AndroidBridge 注入所有 iframe，
     * 模型生成代码可直接调本方法读含 Google CSE key 的 secure/engines 配置或
     * kv_set 篡改。故调用方必须在 args JSON 内携带 `__token`（主帧专用凭证，
     * 仅经 evaluateJavascript 注入主帧 window；iframe opaque origin 无 parent
     * 访问权、跨源同理，读不到该值）。token 校验在函数入口完成，不符立即拒绝；
     * 通过后从 args 移除该键，避免 KvTool 收到未知参数，再走 namespace/key 校验
     * 与 executeWithSecureAccess。不新增任何暴露 token 的 @JavascriptInterface。
     */
    @JavascriptInterface
    fun kvSettingCall(name: String, raw: String): String {
        if (name != "kv_get" && name != "kv_set") return "ERROR: 设置通道仅支持 kv_get/kv_set"
        val tool = tools[name] as? KvTool ?: return "ERROR: 未知工具"
        val parsed = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "ERROR: 参数不是 JSON 对象"
        // R96 review 必修项：恒定时间比对主帧凭证，缺失/不匹配一律拒绝。
        val token = parsed.strArg("__token")
        if (token == null || !MessageDigest.isEqual(token.toByteArray(), settingToken.toByteArray()))
            return "ERROR: 设置通道凭证无效"
        val args = JsonObject(parsed.filterKeys { it != "__token" })
        if (args.strArg("namespace") != "secure" || args.strArg("key") != "engines" || args["items_json"] != null)
            return "ERROR: 设置通道仅限 secure/engines 单键读写"
        return runCatching {
            runBlocking { tool.executeWithSecureAccess(args) { key -> key == "engines" } }
        }.getOrElse { "ERROR: ${it.message}" }
    }

    /**
     * R96 review 必修项：每次页面加载（onPageFinished）轮换主帧凭证，返回注入
     * 脚本体。token 经 JsonPrimitive 编码成合法 JS 字符串字面量防注入；脚本只能
     * 由 MainActivity 用 evaluateJavascript 注入主帧，绝不新增 @JavascriptInterface
     * 出口暴露 token。每次导航/刷新都会触发 onPageFinished，token 随之轮换。
     */
    fun rotateSettingTokenAndInjectionScript(): String {
        settingToken = newSettingToken()
        return "window.__kvSettingToken=${JsonPrimitive(settingToken).toString()};"
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

    /** R28: 屏幕捕获通道状态 {"channel":"projection"|"none","ready":bool,"recording":bool}。 */
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
    fun termuxGrantPermission(): String = runCatching { runBlocking { termuxGrant() } }
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
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(64)
            .ifBlank { "对话" }
        val filename = if (safeTitle.matches(Regex("conversations-batch-\\d{4}-\\d{2}-\\d{2}"))) {
            "$safeTitle.md"
        } else {
            "$safeTitle-${System.currentTimeMillis()}.md"
        }
        return runCatching {
            if (Build.VERSION.SDK_INT < 29) {
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent")
                if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录")
                val target = java.io.File(dir, filename)
                val file = if (target.exists()) java.io.File(dir, "$safeTitle-${System.currentTimeMillis()}.md") else target
                file.writeText(markdown)
                file.absolutePath
            } else {
                val resolver = context.contentResolver
                // R93 review 必修项：MediaStore 允许同名文件共存，固定 DISPLAY_NAME 会让
                // 重复导出悄悄叠加成 多份同名文件且返回路径不可信。先查重生成唯一名，
                // 并以 IS_PENDING 查询到的真实 DISPLAY_NAME 报告路径。
                val finalName = run {
                    var candidate = filename
                    var n = 2
                    while (mediaStoreNameExists(resolver, candidate)) {
                        val dot = filename.lastIndexOf('.')
                        val stem = if (dot > 0) filename.substring(0, dot) else filename
                        val ext = if (dot > 0) filename.substring(dot) else ".md"
                        candidate = "$stem-$n$ext"
                        n++
                    }
                    candidate
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
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
                    // 以 MediaStore 落库后的实际名称为准（insert 可能对非法字符做改写）
                    val actualName = queryMediaStoreDisplayName(resolver, uri) ?: finalName
                    "/sdcard/${Environment.DIRECTORY_DOWNLOADS}/andagent/$actualName"
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
        }.onSuccess { path -> emit(buildJsonObject { put("type", "conversation.exported"); put("path", path) }) }
            .getOrElse { "ERROR: 导出失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** R93: 查询 MediaStore Downloads/andagent/ 下是否已存在同名文件（排除 IS_PENDING 项）。 */
    private fun mediaStoreNameExists(resolver: android.content.ContentResolver, name: String): Boolean =
        runCatching {
            resolver.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/andagent/"),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    /** R93: 读取 MediaStore 条目落库后的实际 DISPLAY_NAME，失败返回 null。 */
    private fun queryMediaStoreDisplayName(resolver: android.content.ContentResolver, uri: android.net.Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

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
    fun extensionTools(): String = kotlinx.serialization.json.JsonArray(tools.values.filter { it.name.startsWith("skill_") || it.name.startsWith("mcp_") || it.name.startsWith("my_") }.map { tool ->
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
            "rate" -> registry.rate(name, value.toIntOrNull() ?: error("评分需为 0-5"))
            "share" -> shareSkill(registry.share(name))
            "update" -> registry.update(name)
            else -> "ERROR: 未知操作"
        }
    }.getOrElse { "ERROR: ${it.message}" }

    /** 一键分享：有 ZIP 就带附件，同时始终附带深链文本。 */
    private fun shareSkill(payload: String): String {
        val context = appContext ?: return "ERROR: 上下文不可用"
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "ERROR: 分享数据无效"
        val text = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val file = obj["file"]?.jsonPrimitive?.contentOrNull?.let { java.io.File(it) }?.takeIf { it.isFile }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (file != null) "application/zip" else "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "分享技能").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return text
    }

    @JavascriptInterface
    fun mcpManage(action: String, name: String, value: String): String = runCatching {
        val registry = requireNotNull(mcpRegistry)
        when (action) {
            "list" -> registry.listServers()
            "add" -> registry.addFromJson(name, value)
            "remove" -> registry.remove(name, value == "true")
            "enable" -> registry.enable(name, value == "true")
            "tools" -> registry.toolsOf(name)
            "reload" -> registry.reload()
            "serverStart" -> {
                val obj = runCatching { Json.parseToJsonElement(value).jsonObject }.getOrNull()
                registry.startServer(
                    port = (obj?.get("port")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 8765),
                    exposeAll = obj?.get("all")?.jsonPrimitive?.contentOrNull == "true",
                )
            }
            "serverStop" -> registry.stopServer()
            "serverStatus" -> registry.serverStatus()
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

    /* --------------------- R88: 对话内图片附件（多模态） --------------- */

    /**
     * 导入一张图片为对话附件：解码 + EXIF 转正 + 压到长边 ≤1568 JPEG，落盘到
     * `chat-images/`，返回 `{ok,path,url,width,height,bytes,mimeType}`。
     * `source` 只接受 content:// 或应用自己的落盘目录（见 ChatImageStore）。
     */
    @JavascriptInterface
    fun chatImageImport(source: String): String {
        val ctx = appContext
            ?: return buildJsonObject { put("ok", false); put("message", "bridge 无 context") }.toString()
        return runCatching {
            val image = com.dc.andagent.tools.ChatImageStore.import(ctx, source)
            buildJsonObject {
                put("ok", true)
                put("path", image.path)
                put("url", image.url)
                put("width", image.width)
                put("height", image.height)
                put("bytes", image.bytes)
                put("mimeType", "image/jpeg")
            }.toString()
        }.getOrElse {
            buildJsonObject {
                put("ok", false)
                put("message", it.message ?: it.javaClass.simpleName)
            }.toString()
        }
    }

    /** 按相对路径读回附件 base64（模型消息用）；失败返回空串。 */
    @JavascriptInterface
    fun chatImageBase64(path: String): String {
        val ctx = appContext ?: return ""
        return com.dc.andagent.tools.ChatImageStore.readBase64(ctx, path).orEmpty()
    }

    /** R39: WebView 回报解析后的主题（true=夜间），原生侧同步背景与系统栏。 */
    @JavascriptInterface
    fun reportTheme(isDark: Boolean) {
        runCatching { themeChange(isDark) }
    }

    /** R39 修复：系统当前深浅色（"dark"/"light"），供 Web 侧「跟随系统」取值。 */
    @JavascriptInterface
    fun systemTheme(): String =
        runCatching { systemThemeProvider() }.getOrDefault("light")

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
        sshToolKeys.remove(reqId)?.let { SshTransferCancel.request(it) }
        toolFutures.remove(reqId)?.cancel(true)
        // 无条件回一条中断结果：future 可能已经结束（cancel 返回 false）或根本
        // 不存在，静默 return 会让 JS 侧一直等这个 reqId——卡片永远「执行中」，
        // 而且映射已被删，第二次点停止彻底无效。重复回包在 JS 侧是幂等的。
        emitToolResult(reqId, false, "ERROR: 已被用户中断")
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
            if (name == "ssh_download" || name == "ssh_upload" || name == "ssh_exec") {
                val ref = args["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val path = if (name == "ssh_exec") args["cmd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    else args["remote_path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                sshToolKeys[reqId] = SshTransferCancel.key(ref, path)
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
            sshToolKeys.remove(reqId)
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
        if (liveInstance === this) liveInstance = null
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

        // R105: durable handoff of a2ui fences from cron. The persisted inbox
        // (filesDir/cron_a2ui, see CronA2uiInbox) is the single source of
        // truth — it survives Activity recreation AND process death; the old
        // in-process ArrayDeque is retired (draining both would deliver the
        // same fence twice). WorkManager has no AgentBridge instance, so the
        // enqueue lives here and JS consumes via consumeA2uiForms().
        @Volatile
        private var liveInstance: AgentBridge? = null

        /**
         * CronWorker -> WebView handoff. Enqueue durably; when [flushNow] and a
         * WebView is live, emit a flush immediately so a foreground form
         * renders without a tap. Drains are idempotent (the inbox clears).
         */
        fun pushA2uiForm(context: Context, formJson: String, flushNow: Boolean) {
            CronA2uiInbox.enqueue(context, formJson)
            if (flushNow) liveInstance?.flushA2ui()
        }

        // R96 review 必修项：128-bit 随机主帧凭证，hex 编码 32 字符。
        private val settingTokenRandom = SecureRandom()
        private fun newSettingToken(): String {
            val bytes = ByteArray(16)
            settingTokenRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

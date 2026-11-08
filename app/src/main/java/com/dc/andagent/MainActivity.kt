package com.dc.andagent

import android.Manifest
import android.app.Dialog
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import android.nfc.NfcAdapter
import com.dc.andagent.tools.NfcSession
import com.dc.andagent.tools.NfcTool
import com.dc.andagent.tools.IrTool
import com.dc.andagent.tools.IR_TOOLS
import com.dc.andagent.tools.NfcMifareTool
import com.dc.andagent.tools.WebEmbedTool
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import org.json.JSONObject
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dc.andagent.bridge.AgentBridge
import com.dc.andagent.bridge.DbGuiBridge
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.bridge.UiCommands
import com.dc.andagent.shell.devtoolsStatusJson
import com.dc.andagent.shell.setDevToolsDebugging
import com.dc.andagent.shell.ShizukuShell
import com.dc.andagent.shell.TermuxEnv
import com.dc.andagent.script.QuickJsEngine
import com.dc.andagent.live.LiveLoopRunner
import com.dc.andagent.live.LiveOverlayHost
import com.dc.andagent.tools.LiveTool
import com.dc.andagent.tools.LIVE_TOOLS
import com.dc.andagent.tools.KvStore
import com.dc.andagent.script.JsEvalTool
import com.dc.andagent.tools.IntentTool
import com.dc.andagent.tools.INTENT_TOOLS
import com.dc.andagent.tools.ScriptFsTool
import com.dc.andagent.tools.CronTool
import com.dc.andagent.tools.SqliteTool
import com.dc.andagent.tools.KvTool
import com.dc.andagent.tools.KV_TOOLS
import com.dc.andagent.tools.SHORTCUT_TOOLS
import com.dc.andagent.tools.ShortcutTool
import com.dc.andagent.tools.TILE_TOOLS
import com.dc.andagent.tools.TileTool
import com.dc.andagent.tools.AccessibilityTool
import com.dc.andagent.tools.DbGuiTool
import com.dc.andagent.tools.TermuxTool
import com.dc.andagent.tools.SSH_TOOLS
import com.dc.andagent.tools.SSH_TRANSFER_TOOLS
import com.dc.andagent.tools.SshTransferTool
import com.dc.andagent.tools.SSH_CREDENTIAL_TOOLS
import com.dc.andagent.tools.SshTool
import com.dc.andagent.tools.SshCredentialActivity
import com.dc.andagent.tools.SshCredentialStore
import com.dc.andagent.tools.SshCredentialTool
import com.dc.andagent.tools.ADB_TOOLS
import com.dc.andagent.tools.AdbTool
import com.dc.andagent.tools.ScreenCaptureTool
import com.dc.andagent.tools.ProjectionConsentActivity
import com.dc.andagent.tools.ProjectionConsentBridge
import com.dc.andagent.tools.ProjectionService
import com.dc.andagent.tools.projectionStatusJson
import com.dc.andagent.tools.ScreenRecordH264Tool
import com.dc.andagent.tools.InputTool
import com.dc.andagent.tools.BtScanTool
import com.dc.andagent.tools.BtPairedListTool
import com.dc.andagent.tools.BtConnectTool
import com.dc.andagent.tools.WifiConnectTool
import com.dc.andagent.tools.ShellConnectivityTool
import com.dc.andagent.tools.AlarmSetTool
import com.dc.andagent.tools.AlbumStatsTool
import com.dc.andagent.tools.AppListTool
import com.dc.andagent.tools.BatteryDetailTool
import com.dc.andagent.tools.CalendarAddTool
import com.dc.andagent.tools.CalendarDeleteTool
import com.dc.andagent.tools.CalendarEventsTool
import com.dc.andagent.tools.CalendarListTool
import com.dc.andagent.tools.ClipboardReadTool
import com.dc.andagent.tools.ClipboardWriteTool
import com.dc.andagent.tools.ClipboardWriteRichTool
import com.dc.andagent.tools.ClipboardReadRichTool
import com.dc.andagent.tools.ClipboardClearTool
import com.dc.andagent.tools.DeviceInfoTool
import com.dc.andagent.tools.FileDownloadTool
import com.dc.andagent.tools.FileEditTool
import com.dc.andagent.tools.FileListTool
import com.dc.andagent.tools.FileReadTool
import com.dc.andagent.tools.FileSearchTool
import com.dc.andagent.tools.DocumentCreateTool
import com.dc.andagent.tools.FileWriteTool
import com.dc.andagent.tools.GetTimeTool
import com.dc.andagent.tools.NotifySendTool
import com.dc.andagent.tools.ScreenRecordListTool
import com.dc.andagent.tools.ScreenRecordStopTool
import com.dc.andagent.tools.ScreenRecordTool
import com.dc.andagent.tools.SensorListTool
import com.dc.andagent.tools.SensorReadTool
import com.dc.andagent.tools.StepsHistoryTool
import com.dc.andagent.tools.MicListTool
import com.dc.andagent.tools.OrientationTool
import com.dc.andagent.tools.OtgTool
import com.dc.andagent.tools.MicRecordTool
import com.dc.andagent.tools.SpeechRecognizeTool
import com.dc.andagent.tools.CameraInfoTool
import com.dc.andagent.tools.CameraCaptureTool
import com.dc.andagent.tools.CameraRecordTool
import com.dc.andagent.tools.CameraScanTool
import com.dc.andagent.tools.SensorInfoTool
import com.dc.andagent.tools.CameraOverlayTool
import com.dc.andagent.tools.LocationGetTool
import com.dc.andagent.tools.LocationStatusTool
import com.dc.andagent.tools.ImageSaveTool
import com.dc.andagent.tools.ImageEditTool
import com.dc.andagent.tools.FilePickResultBridge
import com.dc.andagent.tools.SafTreeStore
import com.dc.andagent.tools.FilePickTool
import com.dc.andagent.tools.OpenFolderTool
import com.dc.andagent.tools.AlbumOpenTool
import com.dc.andagent.tools.AlbumPhotosTool
import com.dc.andagent.tools.OpenSettingsTool
import com.dc.andagent.tools.OpenUriTool
import com.dc.andagent.widget.WidgetGenerateTool
import com.dc.andagent.widget.WidgetSaveAndPinTool
import com.dc.andagent.tools.PhoneCallTool
import com.dc.andagent.tools.SmsSendTool
import com.dc.andagent.tools.StepsTodayTool
import com.dc.andagent.tools.StorageScanTool
import com.dc.andagent.tools.TimerSetTool
import com.dc.andagent.widget.data.WidgetToolRuntime
import com.dc.andagent.widget.data.WidgetDataScheduler
import com.dc.andagent.widget.data.WidgetStore
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.SystemApiTool
import com.dc.andagent.tools.SYSTEM_API_TOOLS
import com.dc.andagent.tools.AutomationTool
import com.dc.andagent.tools.AUTOMATION_TOOLS
import com.dc.andagent.tools.TtsFileTool
import com.dc.andagent.tools.ExternalDataTool
import com.dc.andagent.tools.EXTERNAL_DATA_TOOLS
import com.dc.andagent.tools.FileOpsTool
import com.dc.andagent.tools.FILE_OPS_TOOLS
import com.dc.andagent.tools.ZipTool
import com.dc.andagent.tools.ZIP_TOOLS
import com.dc.andagent.tools.EmailTool
import com.dc.andagent.tools.EMAIL_TOOLS
import com.dc.andagent.tools.StoreTool
import com.dc.andagent.tools.STORE_TOOLS
import com.dc.andagent.tools.DevToolsTool
import com.dc.andagent.tools.WebSearchTool
import com.dc.andagent.tools.HttpTool
import com.dc.andagent.tools.HTTP_TOOLS
import com.dc.andagent.tools.DEVTOOLS_TOOLS
import com.dc.andagent.tools.WifiInfoTool
import com.dc.andagent.tools.R35Tool
import com.dc.andagent.tools.R35_TOOLS
import com.dc.andagent.tools.HardwareStatusTool
import com.dc.andagent.tools.HARDWARE_STATUS_TOOLS
import com.dc.andagent.tools.BtUsbExtTool
import com.dc.andagent.tools.BT_USB_EXT_TOOLS
import com.dc.andagent.tools.AndroidReflectTool
import com.dc.andagent.tools.RestrictedReflectExecutor
import com.dc.andagent.tools.ApiToolFactory
import com.dc.andagent.ui.SettingsStore
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicReference

/**
 * Full-screen WebView for the pi agent + assistant-ui conversation.
 * Tools remain exposed to conversation content through [AgentBridge].
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var nfcSession: NfcSession
    private var embeddedDialog: Dialog? = null
    private var dbGuiDialog: Dialog? = null
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var localMedia: LocalMediaHandler
    private lateinit var bridge: AgentBridge
    private lateinit var skillRegistry: com.dc.andagent.tools.SkillRegistry
    private lateinit var permissionBroker: PermissionBroker
    private lateinit var shizuku: ShizukuShell
    private lateinit var quickJsEngine: QuickJsEngine
    private lateinit var liveQuickJsEngine: QuickJsEngine
    private lateinit var liveLoopRunner: LiveLoopRunner
    private lateinit var liveOverlayHost: LiveOverlayHost
    /** R102: 外部非私有存储镜像（换签名重装后恢复数据）。 */
    private var externalState: com.dc.andagent.state.ExternalStateStore? = null
    private val scriptToolExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private val healthPermissionResult = AtomicReference<Set<String>?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 沉浸式顶栏：状态栏高度（CSS px = dp），由 insets 监听刷新后注入页面。 */
    private var safeTopCssPx = 0f
    private var publishedSafeTop = -1f
    private var safeTopScript: ScriptHandler? = null

    private companion object {
        /** WebView 页面同源（loadUrl 与 WebViewAssetLoader 用的域名）。 */
        const val SHELL_ORIGIN = "https://appassets.androidplatform.net"
    }

    /**
     * Tool-level on-demand Health Connect authorization: launches the
     * consent screen and suspends until the user answers (or 90s timeout).
     * Called from StepsTools when a read hits the permission wall, so the
     * dialog appears exactly when the agent needs it.
     */
    private val healthPermissionRequester: suspend () -> Boolean = {
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    val status = runCatching { HealthConnectClient.getSdkStatus(this) }
                        .getOrElse { HealthConnectClient.SDK_UNAVAILABLE }
                    if (status != HealthConnectClient.SDK_AVAILABLE) {
                        cont.resume(false)
                        return@post
                    }
                    healthPermissionResult.set(null)
                    healthPermissionLauncher.launch(
                        setOf(HealthPermission.getReadPermission(StepsRecord::class)),
                    )
                    // Poll the AtomicReference set by the launcher callback.
                    val deadline = System.currentTimeMillis() + 90_000
                    fun poll() {
                        if (!cont.isActive) return
                        val granted = healthPermissionResult.get()
                        if (granted != null) {
                            cont.resume(
                                granted.contains(
                                    HealthPermission.getReadPermission(StepsRecord::class),
                                ),
                            )
                        } else if (System.currentTimeMillis() > deadline) {
                            cont.resume(false)
                        } else {
                            mainHandler.postDelayed(::poll, 300)
                        }
                    }
                    poll()
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Tools/suspend callers observe the real grant state; a denial is
            // surfaced as an error string by each tool, never as a crash.
            if (::permissionBroker.isInitialized) permissionBroker.onResult(result)
        }

    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted: Set<String> ->
        healthPermissionResult.set(granted)
    }

    private val singleFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        FilePickResultBridge.reportNext(FilePickResultBridge.Kind.SINGLE, listOfNotNull(uri?.toString()))
    }

    private val multipleFileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        FilePickResultBridge.reportNext(FilePickResultBridge.Kind.MULTIPLE, uris.map { it.toString() })
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            SafTreeStore.add(this, uri.toString())
        }
        FilePickResultBridge.reportNext(FilePickResultBridge.Kind.TREE, listOfNotNull(uri?.toString()))
    }

    private val filePickLaunchCallback: (String, String, Boolean, Boolean) -> Unit = { id, mime, multiple, tree ->
        runOnUiThread {
            try {
                if (isFinishing || isDestroyed) {
                    FilePickResultBridge.fail(id, "主界面不可用")
                } else if (tree) {
                    treeLauncher.launch(null)
                } else if (multiple) {
                    multipleFileLauncher.launch(arrayOf(mime))
                } else {
                    singleFileLauncher.launch(arrayOf(mime))
                }
            } catch (e: Exception) {
                FilePickResultBridge.fail(id, "无法打开选择器：${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R98: sshj 依赖 BC 的 X25519/Curve25519 KeyAgreement，Android 内置 provider
        // 没有——必须在首次 SSH 连接前注册完整 BouncyCastleProvider。注意 Android
        // 系统已注册了精简版同名 "BC" provider，直接 insertProviderAt 会因重名静默
        // 返回 -1（review 必修项①），所以先 removeProvider 再 insert，并校验注册
        // 结果与目标算法真正可用。
        runCatching {
            if (java.security.Security.getProvider("BC")?.get("KeyAgreement.X25519") == null) {
                java.security.Security.removeProvider("BC")
                val inserted = java.security.Security.insertProviderAt(
                    org.bouncycastle.jce.provider.BouncyCastleProvider(), 2)
                if (inserted <= 0 ||
                    java.security.Security.getProvider("BC")?.get("KeyAgreement.X25519") == null) {
                    android.util.Log.w("AndAgent", "BouncyCastle BC provider registration failed (inserted=$inserted); SSH X25519 key exchange may not work")
                }
            }
        }
        FilePickResultBridge.launcher = filePickLaunchCallback

        val settingsStore = SettingsStore(this)
        val appContext = applicationContext
        // R102: 外部镜像在 Activity 就绪后立刻补一次，保证卸载前已有最新快照。
        val externalStateStore = com.dc.andagent.state.ExternalStateStore(appContext, settingsStore)
        externalState = externalStateStore
        externalStateStore.autoSync(force = true)
        permissionBroker = PermissionBroker(this, permissionLauncher)
        shizuku = ShizukuShell(appContext).also { it.init() }

        val termuxEnv = TermuxEnv(appContext, shizuku)
        val btConnect = BtConnectTool(appContext, permissionBroker)
        nfcSession = NfcSession(this) { message ->
            if (::bridge.isInitialized) bridge.emitNotice(message)
        }
        val memoryPrefs = appContext.getSharedPreferences("andagent_memory", android.content.Context.MODE_PRIVATE)
        val memoryStore = com.dc.andagent.memory.MemoryStore(
            { memoryPrefs.getString("entries", null) },
            { value ->
                // A lost write must surface as an error, never as a silent "saved".
                require(memoryPrefs.edit().putString("entries", value).commit()) { "记忆写入持久化失败" }
            },
        )
        val tools: MutableMap<String, Tool> = java.util.concurrent.ConcurrentHashMap()
        quickJsEngine = QuickJsEngine(tools, scriptToolExecutor, appContext)
        liveQuickJsEngine = QuickJsEngine(tools, scriptToolExecutor, appContext)
        liveOverlayHost = LiveOverlayHost(this)
        val liveKv = KvStore(appContext)
        liveLoopRunner = LiveLoopRunner(liveQuickJsEngine::evalLive, { id, consumer, value ->
            when (consumer) {
                "kv" -> liveKv.set("live", id, value)
                "overlay" -> liveOverlayHost.update(id, value)
            }
        }, { message ->
            if (::bridge.isInitialized) bridge.emitNotice(message)
            runCatching {
                val manager = appContext.getSystemService(android.app.NotificationManager::class.java)
                manager.createNotificationChannel(android.app.NotificationChannel("andagent_live", "实时循环", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                manager.notify(4300, androidx.core.app.NotificationCompat.Builder(appContext, "andagent_live")
                    .setSmallIcon(R.drawable.ic_stat_andagent).setContentTitle("实时循环已停止")
                    .setContentText(message).setAutoCancel(true).build())
            }
        }, liveOverlayHost::remove)
        tools.putAll(LIVE_TOOLS.associateWith { LiveTool(liveLoopRunner, it, liveOverlayHost::prepare) })
        tools.putAll(mapOf(
            "get_time" to GetTimeTool(),
            "nfc_status" to NfcTool(nfcSession, "nfc_status"),
            "nfc_read" to NfcTool(nfcSession, "nfc_read"),
            "nfc_write_text" to NfcTool(nfcSession, "nfc_write_text"),
            "nfc_write_uri" to NfcTool(nfcSession, "nfc_write_uri"),
            "nfc_write_raw" to NfcTool(nfcSession, "nfc_write_raw"),
            "nfc_hce_status" to NfcTool(nfcSession, "nfc_hce_status"),
            // R38: MIFARE Classic/Ultralight deep read-write (Android TagTechnology API).
            "nfc_mf_dump" to NfcMifareTool(nfcSession, appContext, "nfc_mf_dump"),
            "nfc_mf_write_block" to NfcMifareTool(nfcSession, appContext, "nfc_mf_write_block"),
            "nfc_ul_read" to NfcMifareTool(nfcSession, appContext, "nfc_ul_read"),
            "nfc_ul_write_page" to NfcMifareTool(nfcSession, appContext, "nfc_ul_write_page"),
            "nfc_keys_manage" to NfcMifareTool(nfcSession, appContext, "nfc_keys_manage"),
            "web_embed" to WebEmbedTool(),
            "device_info" to DeviceInfoTool(appContext),
            "storage_scan" to StorageScanTool(appContext),
            "album_stats" to AlbumStatsTool(appContext),
            "album_photos" to AlbumPhotosTool(appContext),
            "album_open" to AlbumOpenTool(appContext),
            "calendar_list" to CalendarListTool(appContext, permissionBroker),
            "calendar_events" to CalendarEventsTool(appContext, permissionBroker),
            "calendar_add" to CalendarAddTool(appContext, permissionBroker),
            "calendar_delete" to CalendarDeleteTool(appContext, permissionBroker),
            "alarm_set" to AlarmSetTool(appContext),
            "timer_set" to TimerSetTool(appContext),
            "battery_detail" to BatteryDetailTool(appContext),
            "wifi_info" to WifiInfoTool(appContext, permissionBroker),
            "termux_setup" to TermuxTool(termuxEnv, "termux_setup"),
            "shell_exec" to TermuxTool(termuxEnv, "shell_exec"),
            "pkg_install" to TermuxTool(termuxEnv, "pkg_install"),
            "apt_status" to TermuxTool(termuxEnv, "apt_status"),
            "bt_scan" to BtScanTool(appContext, permissionBroker),
            "bt_paired_list" to BtPairedListTool(appContext, permissionBroker),
            "bt_connect" to btConnect,
            "wifi_connect" to WifiConnectTool(appContext),
            "wifi_toggle" to ShellConnectivityTool(shizuku, "wifi_toggle"),
            "bt_toggle" to ShellConnectivityTool(shizuku, "bt_toggle"),
            "wifi_connect_strong" to ShellConnectivityTool(shizuku, "wifi_connect_strong"),
            "wifi_saved_list" to ShellConnectivityTool(shizuku, "wifi_saved_list"),
            "wifi_forget" to ShellConnectivityTool(shizuku, "wifi_forget"),
            "bt_connect_direct" to ShellConnectivityTool(shizuku, "bt_connect_direct", btConnect),
            "clipboard_write" to ClipboardWriteTool(appContext),
            "clipboard_read" to ClipboardReadTool(appContext),
            // R39: 富剪贴板（HTML/URI/图片/清空）。
            "clipboard_write_rich" to ClipboardWriteRichTool(appContext),
            "clipboard_read_rich" to ClipboardReadRichTool(appContext),
            "clipboard_clear" to ClipboardClearTool(appContext),
            "notify_send" to NotifySendTool(appContext, permissionBroker),
            "app_list" to AppListTool(appContext),
            // R3a: Shizuku-backed screen recording
            "screen_capture" to ScreenCaptureTool(appContext, shizuku),
            "screen_record_h264" to ScreenRecordH264Tool(appContext, shizuku),
            "input_tap" to InputTool(shizuku, "input_tap"),
            "input_swipe" to InputTool(shizuku, "input_swipe"),
            "input_text" to InputTool(shizuku, "input_text"),
            "screen_record" to ScreenRecordTool(appContext, shizuku),
            "screen_record_stop" to ScreenRecordStopTool(appContext, shizuku),
            "screen_record_list" to ScreenRecordListTool(appContext, shizuku, permissionBroker),
            // R3a: files
            "file_download" to FileDownloadTool(appContext, permissionBroker),
            "tts_to_file" to TtsFileTool(appContext),
            "file_search" to FileSearchTool(appContext, permissionBroker),
            "file_write" to FileWriteTool(appContext, permissionBroker),
            "file_read" to FileReadTool(appContext),
            "file_edit" to FileEditTool(appContext),
            "file_list" to FileListTool(appContext, permissionBroker),
            // R3a: sensors + health
            "sensor_list" to SensorListTool(appContext),
            "sensor_read" to SensorReadTool(appContext),
            "steps_today" to StepsTodayTool(appContext, healthPermissionRequester),
            "open_settings" to OpenSettingsTool(appContext),
            "open_uri" to OpenUriTool(appContext),
            "widget_generate" to WidgetGenerateTool(appContext),
            "widget_save_and_pin" to WidgetSaveAndPinTool(appContext),
            "mic_record" to MicRecordTool(appContext, permissionBroker),
            "speech_recognize" to SpeechRecognizeTool(appContext, permissionBroker),
            "camera_overlay_show" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_show", activityProvider = { this.takeIf { !it.isFinishing } }),
            "camera_overlay_update" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_update", activityProvider = { this.takeIf { !it.isFinishing } }),
            "camera_overlay_hide" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_hide"),
            "camera_info" to CameraInfoTool(appContext, "camera_info"),
            "camera_capture" to CameraCaptureTool(appContext, permissionBroker),
            "camera_record" to CameraRecordTool(appContext, permissionBroker),
            "camera_scan" to CameraScanTool(appContext, permissionBroker),
            "mic_list" to MicListTool(appContext),
            "screen_orientation_get" to OrientationTool({ this }, "screen_orientation_get"),
            "screen_orientation_set" to OrientationTool({ this }, "screen_orientation_set"),
            "otg_status" to OtgTool(appContext, "otg_status").withBroker(permissionBroker),
            "otg_request" to OtgTool(appContext, "otg_request").withBroker(permissionBroker),
            "otg_file_ops" to OtgTool(appContext, "otg_file_ops").withBroker(permissionBroker),
            "otg_transfer" to OtgTool(appContext, "otg_transfer").withBroker(permissionBroker),
            "phone_call" to PhoneCallTool(appContext, permissionBroker),
            "sms_send" to SmsSendTool(appContext, permissionBroker),
            "location_get" to LocationGetTool(appContext, permissionBroker),
            "location_status" to LocationStatusTool(appContext),
            "open_folder" to OpenFolderTool(appContext),
            "file_pick" to FilePickTool(FilePickResultBridge),
            "image_save" to ImageSaveTool(appContext, permissionBroker),
            "image_edit" to ImageEditTool(appContext, "image_edit"),
            "image_preview" to ImageEditTool(appContext, "image_preview"),
            "image_info" to ImageEditTool(appContext, "image_info"),
            "image_collage" to ImageEditTool(appContext, "image_collage"),
            "steps_history" to StepsHistoryTool(appContext, healthPermissionRequester),
            "js_eval" to JsEvalTool(quickJsEngine),
            "script_save" to ScriptFsTool(appContext, quickJsEngine, "script_save"),
            "script_list" to ScriptFsTool(appContext, quickJsEngine, "script_list"),
            "script_read" to ScriptFsTool(appContext, quickJsEngine, "script_read"),
            "script_run" to ScriptFsTool(appContext, quickJsEngine, "script_run"),
            "cron_create" to CronTool(appContext, "cron_create"),
            "cron_list" to CronTool(appContext, "cron_list"),
            "cron_toggle" to CronTool(appContext, "cron_toggle"),
            "cron_delete" to CronTool(appContext, "cron_delete"),
            "cron_run_now" to CronTool(appContext, "cron_run_now"),
            "script_delete" to ScriptFsTool(appContext, quickJsEngine, "script_delete"),
            "script_check" to ScriptFsTool(appContext, quickJsEngine, "script_check"),
            "json_fix" to ScriptFsTool(appContext, quickJsEngine, "json_fix"),
            "db_create" to SqliteTool(appContext, "db_create"),
            "db_list" to SqliteTool(appContext, "db_list"),
            "db_tables" to SqliteTool(appContext, "db_tables"),
            "db_schema" to SqliteTool(appContext, "db_schema"),
            "db_exec" to SqliteTool(appContext, "db_exec"),
            "db_query" to SqliteTool(appContext, "db_query"),
            "db_drop" to SqliteTool(appContext, "db_drop"),
            "db_gui" to DbGuiTool { name -> dbGuiOpen(name) },
            "docx_create" to DocumentCreateTool(appContext, permissionBroker, "docx"),
            "pptx_create" to DocumentCreateTool(appContext, permissionBroker, "pptx"),
            "xlsx_create" to DocumentCreateTool(appContext, permissionBroker, "xlsx"),
        ))
        // R51: Android KV 持久化底座（namespace + key，JSON 值 + 可选 TTL）。
        tools.putAll(KV_TOOLS.associateWith { KvTool(appContext, it) })
        tools.putAll(SHORTCUT_TOOLS.associateWith { ShortcutTool(appContext, it) })
        // R77: quick-settings tile config tools.
        tools.putAll(TILE_TOOLS.associateWith { TileTool(appContext, it) })
        // R71: accessibility-service control automation (no Shizuku needed).
        tools.putAll(
            listOf(
                "a11y_status",
                "a11y_screen",
                "a11y_click",
                "a11y_long_click",
                "a11y_scroll",
                "a11y_text",
                "a11y_action",
            ).associateWith { AccessibilityTool(appContext, it) },
        )
        tools.putAll(IR_TOOLS.associateWith { IrTool(appContext, it) })
        tools.putAll(com.dc.andagent.events.EVENT_TOOLS.associateWith { com.dc.andagent.events.EventTool(appContext, it) })
        tools.putAll(com.dc.andagent.pods.PODS_TOOLS.associateWith { com.dc.andagent.pods.PodsTool(appContext, permissionBroker, it) })
        // R12/R13 system APIs + R14 automation.
        tools.putAll(SYSTEM_API_TOOLS.associateWith { SystemApiTool(appContext, permissionBroker, shizuku, it) })
        tools.putAll(AUTOMATION_TOOLS.associateWith { AutomationTool(appContext, permissionBroker, shizuku, it) })
        tools.putAll(EXTERNAL_DATA_TOOLS.associateWith { ExternalDataTool(appContext, permissionBroker, it) })
        tools.putAll(FILE_OPS_TOOLS.associateWith { FileOpsTool(appContext, it) })
        tools.putAll(ZIP_TOOLS.associateWith { ZipTool(it) })
        tools.putAll(EMAIL_TOOLS.associateWith { EmailTool(appContext, it) })
        tools.putAll(STORE_TOOLS.associateWith { StoreTool(shizuku, it) })
        tools.putAll(DEVTOOLS_TOOLS.associateWith { DevToolsTool(appContext, it) })
        tools.putAll(INTENT_TOOLS.associateWith { IntentTool(appContext, it) })
        // R96: 复用 R51 起就在的 KV 底座实例（共享 andagent_kv prefs），供 web_search 读自定义引擎配置。
        tools.putAll(listOf("web_search", "web_read").associateWith { WebSearchTool(it, liveKv) })
        // R73: http_request (generic client, http:// allowed for LAN devices) + lan_scan (LAN probe).
        tools.putAll(HTTP_TOOLS.associateWith { HttpTool(it, appContext) })
        // R91: SSH 远程运维（JSch 会话池，TOFU known_hosts，破坏性命令分级）。
        tools.putAll(SSH_TOOLS.associateWith { SshTool(appContext, it) })
        tools.putAll(SSH_TRANSFER_TOOLS.associateWith { SshTransferTool(appContext, it) { event ->
            if (::bridge.isInitialized) bridge.emitTransferProgress(event)
        } })
        // R91.5: 凭据元数据（nickname/host/user），secret 只留在 Keystore 加密文件里。
        tools.putAll(SSH_CREDENTIAL_TOOLS.associateWith { SshCredentialTool(appContext, it) })
        // R100 ②: ADB 真实对接（连接/设备/断开/shell/截图/pull/push，共享 AdbSessionHub 状态）。
        tools.putAll(ADB_TOOLS.associateWith { AdbTool(appContext, it) })
        // R35: 联系人新建/通话记录/应用卸载/APK 提取/二维码/传感器录制/文件加密。
        tools.putAll(R35_TOOLS.associateWith { R35Tool(appContext, permissionBroker, shizuku, it) })
        // R84: 传感器与机身状态（高频/批量采样、心率、运动触发、热状态、电池电气量、波形触感、参数化录音、音频路由）。
        tools.putAll(HARDWARE_STATUS_TOOLS.associateWith { HardwareStatusTool(appContext, permissionBroker, it) })
        // R86: 蓝牙扩展刀（BLE 外设广播、A2DP 媒体路由、标准 GATT 电量、USB 拓扑、USB MIDI）。
        tools.putAll(BT_USB_EXT_TOOLS.associateWith { BtUsbExtTool(appContext, permissionBroker, it) })
        // R42: the ad-hoc bridge and declarations share one restricted executor.
        tools["android_reflect"] = AndroidReflectTool(RestrictedReflectExecutor(appContext))
        tools.putAll(com.dc.andagent.memory.MEMORY_TOOLS.associateWith { com.dc.andagent.memory.MemoryTool(memoryStore, it) })
        ApiToolFactory.register(appContext, tools)

        skillRegistry = com.dc.andagent.tools.SkillRegistry(appContext, tools, quickJsEngine)
        val skillNames = listOf("skill_install", "skill_list", "skill_reload", "skill_enable", "skill_delete", "skill_search", "skill_index_set", "skill_info")
        skillNames.forEach { tools[it] = com.dc.andagent.tools.SkillTool(skillRegistry, it) }
        skillRegistry.reload()

        WidgetToolRuntime.attach(tools, quickJsEngine)

        bridge = AgentBridge(
            webViewProvider = { webView },
            appContext = appContext,
            settings = settingsStore,
            tools = tools,
            memoryStore = memoryStore,
            skillRegistry = skillRegistry,
            permissions = permissionBroker,
            appVersion = BuildConfig.VERSION_NAME,
            shizukuStatus = { shizuku.statusJson() },
            shizukuRequest = { shizuku.requestPermission() },
            healthPermissionRequest = { requestHealthPermission() },
            termuxStatus = { termuxEnv.status() },
            termuxTerminalExec = { command -> termuxEnv.terminalExec(command) },
            termuxTerminalWrite = { input -> termuxEnv.terminalWrite(input) },
            termuxTerminalPoll = { termuxEnv.terminalPoll() },
            devtoolsStatusJson = { devtoolsStatusJson(appContext) },
            devtoolsSetDebugging = { enable -> setDevToolsDebugging(appContext, enable) },
            projectionStatusJson = { projectionStatusJson(appContext, shizuku) },
            projectionRequest = { requestProjectionConsent() },
            projectionStop = {
                val stopped = com.dc.andagent.tools.projectionStopRecording(appContext)
                ProjectionService.stop(appContext)
                stopped ?: "没有进行中的投影录屏；已停止屏幕捕获服务"
            },
            embeddedWebviewOpen = { uri -> openEmbeddedWebview(uri) },
            dbGuiOpen = { name -> dbGuiOpen(name) },
            // R91.5: 凭据录入只走原生界面；WebView 拿到的永远只是元数据。
            sshCredentialOpen = {
                runOnUiThread { startActivity(Intent(this, SshCredentialActivity::class.java)) }
                "已打开 SSH 凭据界面：请在原生页面输入密码/私钥，内容不会进入对话。"
            },
            sshCredentialEditOpen = { nickname ->
                runOnUiThread {
                    startActivity(Intent(this, SshCredentialActivity::class.java)
                        .putExtra(SshCredentialActivity.EXTRA_EDIT_NICKNAME, nickname))
                }
                "已打开「$nickname」的编辑界面：只预填元数据，密码/私钥不回显，留空即保留原凭据。"
            },
            sshCredentialListJson = {
                runCatching { SshCredentialStore(appContext).listJson() }.getOrDefault("[]")
            },
            sshCredentialRemove = { nickname ->
                runCatching {
                    if (!SshCredentialStore(appContext).delete(nickname)) "ERROR: 凭据不存在：$nickname"
                    else "已删除 SSH 凭据「$nickname」"
                }.getOrElse { "ERROR: 删除失败：${it.message ?: it.javaClass.simpleName}" }
            },
            // R102: 外部非私有存储镜像——换签名重装后从这里恢复。
            externalStateStatusJson = { externalStateStore.statusJson() },
            externalStateSync = { externalStateStore.syncNow() },
            externalStateRestore = { externalStateStore.restoreNow() },
            externalStateSetEnabled = { enabled -> externalStateStore.setEnabled(enabled) },
            externalStateWriteConfig = { externalStateStore.writeConfigJson() },
            uiCommands = UiCommands(this) { if (::webView.isInitialized) webView else null },
            themeChange = { dark -> runOnUiThread { applyUiTheme(dark) } },
            systemThemeProvider = { if (systemPrefersDark()) "dark" else "light" },
            termuxGrant = {
                if (!shizuku.isGranted()) "ERROR: 请先启动 Shizuku 并在设置页授权 AndAgent"
                else {
                    val result = shizuku.run("pm grant com.dc.andagent com.termux.permission.RUN_COMMAND")
                    if (!result.ok) "ERROR: pm grant 失败：${result.stderr.ifBlank { result.stdout.ifBlank { "exit=${result.exitCode}" } }}"
                    else if (appContext.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        "ERROR: pm grant 已返回成功，但权限状态仍未生效；请检查 Termux 安装及签名"
                    else "Termux RUN_COMMAND 已授权；${termuxEnv.status()}"
                }
            },
        )

        localMedia = LocalMediaHandler(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/local/", localMedia)
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(themeBackgroundColor())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.textZoom = 100
            addJavascriptInterface(bridge, "AndroidBridge")
            // Without a WebChromeClient WebView denies every PermissionRequest,
            // so JS getUserMedia() fails even with RECORD_AUDIO granted.
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    val shellOrigin = request.origin?.host == "appassets.androidplatform.net"
                    val wanted = request.resources.toList().filter {
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                    }
                    if (!shellOrigin || wanted.isEmpty()) {
                        runCatching { request.deny() }
                        return
                    }
                    // Chromium asks the host app before checking Android's own
                    // grant: obtain it on demand, then answer the pending
                    // getUserMedia() promise on the main thread.
                    val androidPerms = wanted.mapNotNull {
                        when (it) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                            else -> null
                        }
                    }
                    permissionBroker.request(androidPerms) { result ->
                        runCatching {
                            val granted = wanted.filter { webResource ->
                                val androidPerm = when (webResource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                                    else -> Manifest.permission.CAMERA
                                }
                                result[androidPerm] == true
                            }.toTypedArray()
                            if (granted.isNotEmpty() && !isFinishing && !isDestroyed) {
                                request.grant(granted)
                            } else {
                                request.deny()
                            }
                        }
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = localMedia.intercept(request) ?: assetLoader.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url: Uri = request.url
                    if (url.host == "appassets.androidplatform.net") return false
                    // Embedded cards use sandboxed iframes: let their own HTTPS
                    // navigations stay inside the iframe instead of escaping to
                    // an external browser, and block non-HTTPS subframes.
                    if (!request.isForMainFrame) return url.scheme != "https"
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    }.getOrDefault(true)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // 重新加载/跳转后要补写一次；没有文档级注入能力的旧版
                    // WebView 只有这条路径（首帧后顶栏会补上避让高度）。
                    runCatching { view.evaluateJavascript(safeTopScriptBody(), null) }
                    // R96 review 必修项：每次导航/刷新轮换主帧凭证，只经
                    // evaluateJavascript（只作用于主帧）把 token 注入主帧 window，
                    // iframe（opaque origin / 跨源）读不到 parent.window，故取不到。
                    // 全包裹 runCatching：注入失败不得影响页面加载（never-crash 纪律）。
                    runCatching {
                        if (::bridge.isInitialized) {
                            view.evaluateJavascript(bridge.rotateSettingTokenAndInjectionScript(), null)
                        }
                    }
                }
            }
        }

        // R25: debug 构建自动开；release 包须用户在设置页显式开启（SP 持久化）。
        if (settingsStore.devtoolsDebugging ||
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val webViewContainer = FrameLayout(this).apply {
            addView(webView)
        }
        setContentView(webViewContainer)
        installImmersiveChrome(webViewContainer)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        applyUiTheme(isDarkThemeStored())

        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        requestMediaPermissions()
        handleDeeplink(intent)
    }

    override fun onResume() { super.onResume(); if (::nfcSession.isInitialized) nfcSession.onResume() }

    /**
     * R39: 主题同步——WebView 在 applyTheme()/setTheme() 时经 bridge 回报
     * 解析后的深浅色；这里持久化到 SP 并刷新 WebView 背景、系统栏与导航条，
     * 避免下次冷启动闪错色。夜间为默认。
     */
    private fun isDarkThemeStored(): Boolean =
        getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", systemPrefersDark())

    /**
     * 系统当前的夜间设置。不能用 WebView 的 prefers-color-scheme 判断——
     * 它跟随宿主 Activity 主题（本项目是深色 Theme.Material），恒报 dark。
     */
    private fun systemPrefersDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** 系统切换日夜（manifest 已声明 uiMode，不重建 Activity）：通知 Web 侧重新解析。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::webView.isInitialized) return
        val dark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        webView.evaluateJavascript("window.__andagentSystemTheme && window.__andagentSystemTheme($dark)", null)
    }

    /**
     * 沉浸式状态栏：内容（顶栏 / 抽屉 / 设置面板）一直铺到屏幕顶端，状态栏透明，
     * 底色由页面自己的顶栏渐变提供——系统色块一旦和顶栏不同色，屏幕上就会多出
     * 一条明显的分界带，这也正是"顶栏不沉浸"的观感来源。
     *
     * 只让出顶部：导航栏与键盘都留在窗口之外。
     * targetSdk=28 走兼容行为，因此这里用 legacy 布局 flag 而不是
     * setDecorFitsSystemWindows(false)。
     *
     * 代价是页面必须自己避开状态栏：WebView 里 env(safe-area-inset-*) 恒为 0，
     * 所以这里把真实高度换算成 CSS px（与 dp 等值）写成 --safe-top 注入页面，
     * 由 styles.css 的顶栏 / 抽屉 / 全屏卡消费。底部则由 [applyBottomInset]
     * 从窗口 insets 直接给 WebView 让位（新版系统 adjustResize 已不生效）。
     */
    private fun installImmersiveChrome(container: View) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            publishSafeTop(statusBarTopPx(view, insets))
            applyBottomInset(view, insets)
            // 不消费 insets：WebView 继续保持满屏（含状态栏区域）。
            insets
        }
        ViewCompat.requestApplyInsets(container)
        // 首次布局后再兜一次：部分镜像上内容视图会提前消费窗口 insets，
        // 监听器那次可能拿到 0，这里从窗口根 insets 直接取。
        container.post {
            publishSafeTop(statusBarTopPx(container, null))
            ViewCompat.getRootWindowInsets(container)?.let { applyBottomInset(container, it) }
        }
    }

    /**
     * 让 WebView 的布局区让出键盘与导航栏。
     *
     * 新版系统（Android 15+ 的强制 edge-to-edge）不再为输入法收缩窗口，页面
     * 布局视口始终是整屏高度，只把键盘盖在底部。这时系统只能靠平移可视区
     * （visualViewport.offsetTop）去露出被聚焦的输入框，顶栏会跟着被推出屏幕，
     * 表现为"对话滚动/输入时标题栏没有固定在顶部"。给容器加底部 padding 后，
     * WebView 的 CSS 视口高度等于真实可视高度，页面按可见区域重排，顶栏常驻。
     */
    private fun applyBottomInset(view: View, insets: WindowInsetsCompat) {
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        val bottom = maxOf(ime, bars)
        if (view.paddingBottom != bottom) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
        }
    }

    /**
     * 状态栏高度（px）。监听器给的 insets 与窗口根 insets 取较大值：
     * 前者在某些机型上被上层内容视图吃掉，后者（API 23+）始终可读。
     */
    private fun statusBarTopPx(view: View, insets: WindowInsetsCompat?): Float {
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val fromListener = insets?.getInsets(types)?.top ?: 0
        val fromWindow = ViewCompat.getRootWindowInsets(view)?.getInsets(types)?.top ?: 0
        return maxOf(fromListener, fromWindow).toFloat()
    }

    /** 注入脚本：把状态栏高度写到 <html> 的行内 style 上（覆盖 CSS 里的 env() 兜底）。 */
    private fun safeTopScriptBody(): String {
        val px = kotlin.math.round(safeTopCssPx.coerceAtLeast(0f) * 100f) / 100f
        return "document.documentElement&&document.documentElement.style" +
            ".setProperty('--safe-top','${px}px')"
    }

    /**
     * 状态栏高度变化（首次布局、旋转、刘海差异）时刷新注入值。优先注册为
     * 文档级脚本：脚本在页面解析前执行，首帧就带着正确避让，顶栏不会先画歪
     * 再跳一下；老版本 WebView 没有该能力时退回 onPageFinished 补写。
     */
    private fun publishSafeTop(insetPx: Float) {
        if (!::webView.isInitialized) return
        val cssPx = (insetPx / resources.displayMetrics.density).coerceAtLeast(0f)
        if (kotlin.math.abs(cssPx - publishedSafeTop) < 0.5f) return
        publishedSafeTop = cssPx
        safeTopCssPx = cssPx
        val script = safeTopScriptBody()
        runCatching { safeTopScript?.remove() }
        safeTopScript = if (runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            }.getOrDefault(false)
        ) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(SHELL_ORIGIN))
            }.getOrNull()
        } else {
            null
        }
        // 已经加载完成的页面立即生效（旋转等场景不会再触发首帧）。
        runCatching { webView.evaluateJavascript(script, null) }
    }

    private fun applyUiTheme(dark: Boolean) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("dark", dark).apply()
        val bg = Color.parseColor(if (dark) "#0b0d11" else "#f5f7fa")
        if (::webView.isInitialized) webView.setBackgroundColor(bg)
        // 状态栏透明（沉浸式）：颜色由页面顶栏自己给，系统栏不再另刷一层底色。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /** R39: 弹窗内嵌 WebView 用当前主题色做底色（防白闪）。 */
    private fun themeBackgroundColor(): Int =
        Color.parseColor(if (isDarkThemeStored()) "#0b0d11" else "#f5f7fa")

    override fun onPause() {
        if (::nfcSession.isInitialized) nfcSession.onPause()
        // R102: 退到后台就镜像一次——卸载前已经落盘，不依赖卸载时机。
        externalState?.autoSync(force = true)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::nfcSession.isInitialized) nfcSession.onNewIntent(intent)
        handleDeeplink(intent)
    }

    /** andagent://send?prompt=...&auto=1, plus widget refresh / credentials / session links (#14, R91.5, R97). */
    private fun handleDeeplink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("andagent", true)) return
        // R91.5: andagent://credentials 直接打开原生凭据界面（不携带任何凭据内容）。
        if (data.host.equals("credentials", true)) {
            startActivity(Intent(this, SshCredentialActivity::class.java))
            return
        }
        // R97: andagent://session/<id>——SSH 通知点击回到对应会话。切会话由 web 侧
        // 消费（复用 Drawer onSwitch 同一链路），这里只负责把 id 广播进 WebView。
        if (data.host.equals("session", true)) {
            val id = data.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('andagent:session-switch',{detail:${JSONObject.quote(id)}}));null",
                    null,
                )
            }
            return
        }
        if (::bridge.isInitialized) bridge.queueDeeplink(data)
        if (data.host == "refresh") {
            val id = data.pathSegments.firstOrNull() ?: return
            val record = runCatching { WidgetStore(this).read().records.find { it.id == id } }.getOrNull() ?: return
            WidgetDataScheduler.requestNow(this, record.id, record.spec)
        }
    }

    private fun openEmbeddedWebview(raw: String): String {
        val uri = Uri.parse(raw)
        // https: remote pages. Local HTML files: load their bytes through the
        // appassets/local route (file:/// would be blocked by allowFileAccess=false,
        // and content:// needs a grant — copying into the route sidesteps both).
        val loadUrl: String = when (uri.scheme) {
            "https" -> raw
            "file" -> {
                val f = File(uri.path ?: return "ERROR: file uri 缺少路径")
                if (!f.isFile) return "ERROR: 本地文件不存在：${f.absolutePath}"
                stageLocalHtml(f) ?: return "ERROR: 本地文件读取失败"
            }
            "content" -> {
                val name = (uri.lastPathSegment ?: "embedded").substringAfterLast('/').ifBlank { "embedded" }
                val staged = File(java.io.File(getExternalFilesDir(null), "webview-local"), name)
                    .let { if (it.extension != "html" && it.extension != "htm") java.io.File(it.parentFile, it.nameWithoutExtension + ".html") else it }
                runCatching {
                    staged.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input -> staged.outputStream().use { input.copyTo(it) } }
                        ?: return "ERROR: 无法读取 content uri"
                }.getOrElse { return "ERROR: 读取失败（${it.message}）" }
                stageLocalHtml(staged) ?: return "ERROR: 本地文件读取失败"
            }
            else -> return "ERROR: 内嵌浏览器支持 https 网页与本地 HTML 文件（file:/// 或 content://）；其他 scheme 请用 open_uri"
        }
        runOnUiThread {
            embeddedDialog?.dismiss()
            val browser = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(themeBackgroundColor())
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = localMedia.intercept(request) ?: assetLoader.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https" && request.url.scheme != "appassets"
                }
            }
            val dialog = Dialog(this)
            // 子页统一：左上角「← 返回」（关闭浮层 = 出栈一层，与系统返回一致）。
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(4, 4, 16, 4)
            }
            header.addView(TextView(this).apply {
                text = "←"
                contentDescription = "返回"
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(28, 16, 28, 16)
                setOnClickListener { dialog.dismiss() }
            })
            header.addView(TextView(this).apply { text = uri.host; textSize = 16f; setSingleLine(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header); addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
            dialog.setContentView(container)
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            dialog.setOnDismissListener { browser.stopLoading(); browser.loadUrl("about:blank"); (browser.parent as? ViewGroup)?.removeView(browser); browser.destroy(); if (embeddedDialog === dialog) embeddedDialog = null }
            embeddedDialog = dialog
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser.loadUrl(loadUrl)
        }
        return "OPENED"
    }

    /** Copy a local HTML file into the appassets/local route (readable by the
     *  embedded WebView) and return the https-style URL to load. */
    private fun stageLocalHtml(source: File): String? = runCatching {
        val base = File(getExternalFilesDir(null), "webview-local")
        base.mkdirs()
        val dest = File(base, "page_${System.currentTimeMillis()}.html")
        source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        val encoded = java.net.URLEncoder.encode(dest.name, "UTF-8")
        "https://appassets.androidplatform.net/local/$encoded"
    }.getOrNull()

    private fun dbGuiOpen(name: String): String {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,64}"))) return "ERROR: 数据库名只允许字母数字下划线，最长 64 字符"
        if (!getDatabasePath("andagent_${name}.db").isFile) return "ERROR: 数据库不存在，请先调用 db_list"
        runOnUiThread {
            dbGuiDialog?.dismiss()
            val dialog = Dialog(this)
            val browser = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(themeBackgroundColor())
                addJavascriptInterface(DbGuiBridge(this@MainActivity, name) { runOnUiThread { dialog.dismiss() } }, "DbGuiBridge")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.url.host != "appassets.androidplatform.net"
                }
            }
            dialog.setContentView(browser)
            dialog.setOnDismissListener {
                browser.stopLoading()
                browser.loadUrl("about:blank")
                browser.removeJavascriptInterface("DbGuiBridge")
                (browser.parent as? ViewGroup)?.removeView(browser)
                browser.destroy()
                if (dbGuiDialog === dialog) dbGuiDialog = null
            }
            dbGuiDialog = dialog
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser.loadUrl("https://appassets.androidplatform.net/assets/dbgui/index.html")
        }
        return "已打开数据库浏览器：$name"
    }

    override fun onDestroy() {
        if (FilePickResultBridge.launcher === filePickLaunchCallback) FilePickResultBridge.launcher = null
        dbGuiDialog?.dismiss()
        embeddedDialog?.dismiss()
        if (::nfcSession.isInitialized) nfcSession.close()
        bridge.shutdown()
        if (::liveLoopRunner.isInitialized) liveLoopRunner.shutdown()
        if (::liveQuickJsEngine.isInitialized) liveQuickJsEngine.shutdown()
        if (::liveOverlayHost.isInitialized) liveOverlayHost.close()
        com.dc.andagent.shell.DevToolsClient.shutdown()
        if (::quickJsEngine.isInitialized) { WidgetToolRuntime.detach(quickJsEngine); quickJsEngine.shutdown() }
        // R102: 最后一次镜像。shutdown() 允许已提交的任务跑完，不会截断写入。
        externalState?.autoSync(force = true)
        externalState?.shutdown()
        scriptToolExecutor.shutdownNow()
        if (::shizuku.isInitialized) shizuku.shutdown()
        runCatching { safeTopScript?.remove() }
        safeTopScript = null
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Health Connect uses the Activity Result API; the bridge only posts here.
     * Returns an immediate JSON ack / error for Settings.tsx.
     */
    private fun requestHealthPermission(): String {
        val status = runCatching { HealthConnectClient.getSdkStatus(this) }.getOrElse { -1 }
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> return buildJsonObject {
                put("requested", false)
                put("message", "未安装 Health Connect。请先安装应用（market://details?id=com.google.android.apps.healthdata）")
            }.toString()
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return buildJsonObject {
                put("requested", false)
                put("message", "Health Connect 需要更新，请先在应用商店更新")
            }.toString()
        }
        val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
        runOnUiThread {
            runCatching { healthPermissionLauncher.launch(permissions) }
        }
        return buildJsonObject {
            put("requested", true)
            put("message", "已打开 Health Connect 授权页，请允许读取步数")
        }.toString()
    }

    /**
     * R28: 设置页「授权屏幕捕获」——弹出系统 MediaProjection 同意框（透明
     * trampoline Activity），返回即时 ack JSON；结果由工具调用与状态轮询消费。
     */
    private fun requestProjectionConsent(): String {
        val id = ProjectionConsentBridge.newRequest()
        return try {
            startActivity(ProjectionConsentActivity.intent(this, id))
            buildJsonObject {
                put("requested", true)
                put("message", "已弹出系统屏幕捕获授权，请在弹窗中点「立即开始」")
            }.toString()
        } catch (e: Throwable) {
            ProjectionConsentBridge.report(id, false)
            buildJsonObject {
                put("requested", false)
                put("message", "无法发起屏幕捕获授权：${e.message ?: e.javaClass.simpleName}")
            }.toString()
        }
    }

    /** Startup grants: album reads plus mic (WebView voice input + mic_record). */
    private fun requestMediaPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // Queue through the broker so it never races an on-demand request.
            permissionBroker.request(missing) { }
        }
    }
}

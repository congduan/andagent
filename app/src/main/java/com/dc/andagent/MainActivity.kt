package com.dc.andagent

import android.Manifest
import android.app.Dialog
import android.graphics.Bitmap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.FrameLayout
import android.nfc.NfcAdapter
import com.dc.andagent.tools.NfcApduTool
import com.dc.andagent.tools.NfcSession
import com.dc.andagent.tools.NfcTool
import com.dc.andagent.tools.buildNfcPrompt
import com.dc.andagent.tools.nfcTagDedupKey
import com.dc.andagent.tools.NfcTagDedup
import com.dc.andagent.tools.IrTool
import com.dc.andagent.tools.IR_TOOLS
import com.dc.andagent.tools.IR_CODE_TOOLS
import com.dc.andagent.tools.IrCodeTool
import com.dc.andagent.tools.NfcMifareTool
import com.dc.andagent.tools.WebEmbedTool
import android.content.ClipboardManager
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
import android.webkit.WebSettings
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
import com.dc.andagent.events.CLIP_CHANNEL
import com.dc.andagent.events.ClipEvents
import com.dc.andagent.events.ClipboardForeground
import com.dc.andagent.events.EventFlags
import com.dc.andagent.bridge.DbGuiBridge
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.bridge.UiCommands
import com.dc.andagent.shell.devtoolsStatusJson
import com.dc.andagent.shell.setDevToolsDebugging
import com.dc.andagent.shell.TermuxEnv
import com.dc.andagent.share.ShareIntake
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
import com.dc.andagent.tools.SSH_TUNNEL_TOOLS
import com.dc.andagent.tools.SshTransferTool
import com.dc.andagent.tools.SshTunnelTool
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
import com.dc.andagent.tools.BtScanTool
import com.dc.andagent.tools.BtPairedListTool
import com.dc.andagent.tools.BtConnectTool
import com.dc.andagent.tools.WifiConnectTool
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
import com.dc.andagent.tools.WaitTool
import com.dc.andagent.tools.NotifySendTool
import com.dc.andagent.tools.ScreenRecordListTool
import com.dc.andagent.tools.ScreenRecordStopTool
import com.dc.andagent.tools.ScreenRecordTool
import com.dc.andagent.tools.SensorListTool
import com.dc.andagent.tools.SensorReadTool
import com.dc.andagent.tools.DspTools
import com.dc.andagent.tools.StepsHistoryTool
import com.dc.andagent.tools.MicListTool
import com.dc.andagent.tools.DisplayModeTool
import com.dc.andagent.tools.OrientationTool
import com.dc.andagent.tools.OtgTool
import com.dc.andagent.tools.MicRecordTool
import com.dc.andagent.tools.SystemAudioRecordTool
import com.dc.andagent.tools.AudioEffectTool
import com.dc.andagent.tools.AudioFocusTool
import com.dc.andagent.tools.SpatialAudioTool
import com.dc.andagent.tools.SystemAudioRecordStopTool
import com.dc.andagent.tools.SystemAudioRecorder
import com.dc.andagent.tools.SpeechRecognizeTool
import com.dc.andagent.tools.CameraInfoTool
import com.dc.andagent.tools.CameraCaptureTool
import com.dc.andagent.tools.CameraRecordTool
import com.dc.andagent.tools.CameraScanTool
import com.dc.andagent.tools.CameraRawTool
import com.dc.andagent.tools.CameraDepthTool
import com.dc.andagent.tools.CameraStreamTool
import com.dc.andagent.tools.CameraExtensionTool
import com.dc.andagent.tools.OcrTextTool
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
import com.dc.andagent.tools.ExerciseSessionTool
import com.dc.andagent.tools.HeartRateTool
import com.dc.andagent.tools.HealthWeeklyReportTool
import com.dc.andagent.tools.NutritionTool
import com.dc.andagent.tools.OxygenSaturationTool
import com.dc.andagent.tools.SleepTool
import com.dc.andagent.tools.WeightTool
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
import com.dc.andagent.tools.PcapTool
import com.dc.andagent.tools.PCAP_TOOLS
import com.dc.andagent.tools.MqttTool
import com.dc.andagent.tools.MQTT_TOOLS
import com.dc.andagent.tools.DiscoveryTool
import com.dc.andagent.tools.DISCOVERY_TOOLS
import com.dc.andagent.tools.DEVTOOLS_TOOLS
import com.dc.andagent.tools.WifiInfoTool
import com.dc.andagent.tools.WifiEasyConnectTool
import com.dc.andagent.tools.WifiDirectTool
import com.dc.andagent.tools.WifiRttTool
import com.dc.andagent.tools.WIFI_EXT_TOOLS
import com.dc.andagent.tools.R35Tool
import com.dc.andagent.tools.R35_TOOLS
import com.dc.andagent.tools.HardwareStatusTool
import com.dc.andagent.tools.HARDWARE_STATUS_TOOLS
import com.dc.andagent.tools.BtUsbExtTool
import com.dc.andagent.tools.SerialTool
import com.dc.andagent.tools.USB_SERIAL_TOOLS
import com.dc.andagent.tools.EspFlashTool
import com.dc.andagent.tools.ESP_FLASH_TOOLS
import com.dc.andagent.tools.BT_USB_EXT_TOOLS
import com.dc.andagent.tools.BtHidTool
import com.dc.andagent.tools.BT_HID_TOOLS
import com.dc.andagent.tools.BleGattSppTool
import com.dc.andagent.tools.BLE_GATT_SPP_TOOLS
import com.dc.andagent.tools.VpnTool
import com.dc.andagent.tools.VPN_TOOLS
import com.dc.andagent.tools.CellularTool
import com.dc.andagent.tools.CELLULAR_TOOLS
import com.dc.andagent.tools.PdfTool
import com.dc.andagent.tools.PDF_TOOLS
import com.dc.andagent.tools.EsimTool
import com.dc.andagent.tools.ESIM_TOOLS
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
    /** R145: 碰一碰 NFC 注入去重（同 tag id 3 秒窗口内只注入一次）。 */
    private val nfcTagDedup = NfcTagDedup()
    private var embeddedDialog: Dialog? = null
    private var dbGuiDialog: Dialog? = null
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var localMedia: LocalMediaHandler
    private lateinit var bridge: AgentBridge
    private lateinit var skillRegistry: com.dc.andagent.tools.SkillRegistry
    private lateinit var mcpRegistry: com.dc.andagent.tools.McpRegistry
    private lateinit var selfExtManager: com.dc.andagent.tools.SelfExtManager
    private lateinit var permissionBroker: PermissionBroker

    private lateinit var quickJsEngine: QuickJsEngine
    private lateinit var liveQuickJsEngine: QuickJsEngine
    private lateinit var liveLoopRunner: LiveLoopRunner
    private lateinit var liveOverlayHost: LiveOverlayHost
    /** R102: 外部非私有存储镜像（换签名重装后恢复数据）。 */
    private var externalState: com.dc.andagent.state.ExternalStateStore? = null
    private val scriptToolExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    private val healthPermissionResult = AtomicReference<Set<String>?>(null)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * R117②: 剪贴板变更监听只在 app 前台（本 Activity resumed）时挂载。
     * Android 10+（API 29+）系统限制后台应用收不到 OnPrimaryClipChangedListener，
     * 故不进 EventMonitorService，也不声称后台可感知。
     */
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private fun syncClipListener() {
        if (EventFlags.enabled(this, CLIP_CHANNEL)) attachClipListener() else detachClipListener()
    }

    private fun attachClipListener() {
        if (clipListener != null) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            // 「停止全部」或 clip_events_off 可能只清 flag 而不走 onPause；每次回调复核开关。
            if (!EventFlags.enabled(this, CLIP_CHANNEL)) return@OnPrimaryClipChangedListener
            val clip = runCatching { manager.primaryClip }.getOrNull()
            val text = if (clip != null && clip.itemCount > 0)
                runCatching { clip.getItemAt(0).coerceToText(this)?.toString() }.getOrNull()
            else null
            // 只落 length + ≤120 字符 preview，绝不落全文（剪贴板可能含密码）。
            ClipEvents.log(this, text)
        }
        runCatching { manager.addPrimaryClipChangedListener(listener) }.onSuccess { clipListener = listener }
    }

    private fun detachClipListener() {
        clipListener?.let { listener ->
            runCatching { getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(listener) }
        }
        clipListener = null
    }

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
                        com.dc.andagent.tools.HEALTH_READ_PERMISSIONS,
                    )
                    // Poll the AtomicReference set by the launcher callback.
                    val deadline = System.currentTimeMillis() + 90_000
                    fun poll() {
                        if (!cont.isActive) return
                        val granted = healthPermissionResult.get()
                        if (granted != null) {
                            // User may pick only a subset; the per-tool check
                            // in HealthTools decides whether the read proceeds.
                            cont.resume(granted.isNotEmpty())
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

        val termuxEnv = TermuxEnv(appContext)
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
            "wait" to WaitTool(),
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
            // R115: ISO-DEP APDU passthrough (transit/access/DESFire/eMRTD) + FeliCa frames.
            "nfc_iso_dep_info" to NfcApduTool(nfcSession, "nfc_iso_dep_info"),
            "nfc_iso_dep_transceive" to NfcApduTool(nfcSession, "nfc_iso_dep_transceive"),
            "nfc_felica_info" to NfcApduTool(nfcSession, "nfc_felica_info"),
            "nfc_felica_transceive" to NfcApduTool(nfcSession, "nfc_felica_transceive"),
            "nfc_felica_read_blocks" to NfcApduTool(nfcSession, "nfc_felica_read_blocks"),
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
            "clipboard_write" to ClipboardWriteTool(appContext),
            "clipboard_read" to ClipboardReadTool(appContext),
            // R39: 富剪贴板（HTML/URI/图片/清空）。
            "clipboard_write_rich" to ClipboardWriteRichTool(appContext),
            "clipboard_read_rich" to ClipboardReadRichTool(appContext),
            "clipboard_clear" to ClipboardClearTool(appContext),
            "notify_send" to NotifySendTool(appContext, permissionBroker),
            "app_list" to AppListTool(appContext),
            // R28: MediaProjection-backed screen capture / recording
            "screen_capture" to ScreenCaptureTool(appContext),
            "screen_record_h264" to ScreenRecordH264Tool(appContext),
            "screen_record" to ScreenRecordTool(appContext),
            "screen_record_stop" to ScreenRecordStopTool(appContext),
            "screen_record_list" to ScreenRecordListTool(appContext, permissionBroker),
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
            "audio_spectrum" to DspTools(appContext, permissionBroker, "audio_spectrum"),
            "audio_level" to DspTools(appContext, permissionBroker, "audio_level"),
            "steps_today" to StepsTodayTool(appContext, healthPermissionRequester),
            "open_settings" to OpenSettingsTool(appContext),
            "open_uri" to OpenUriTool(appContext),
            "widget_generate" to WidgetGenerateTool(appContext),
            "widget_save_and_pin" to WidgetSaveAndPinTool(appContext),
            "mic_record" to MicRecordTool(appContext, permissionBroker),
            // R114: 音频效果链（EQ/Bass/Reverb/Virtualizer）、AudioFocus 抢占与让出、API 32 空间音频。
            "audio_effect" to AudioEffectTool(appContext),
            "audio_focus" to AudioFocusTool(appContext),
            "spatial_audio" to SpatialAudioTool(appContext),
            // 系统内录：AudioPlaybackCapture + MediaProjection（Android 10+），录设备正在播的声音。
            "system_audio_record" to SystemAudioRecordTool(appContext, permissionBroker),
            "system_audio_record_stop" to SystemAudioRecordStopTool(),
            "speech_recognize" to SpeechRecognizeTool(appContext, permissionBroker),
            "camera_overlay_show" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_show", activityProvider = { this.takeIf { !it.isFinishing } }),
            "camera_overlay_update" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_update", activityProvider = { this.takeIf { !it.isFinishing } }),
            "camera_overlay_hide" to CameraOverlayTool(appContext, permissionBroker, "camera_overlay_hide"),
            "camera_info" to CameraInfoTool(appContext, "camera_info"),
            "camera_capture" to CameraCaptureTool(appContext, permissionBroker),
            "camera_record" to CameraRecordTool(appContext, permissionBroker),
            "camera_scan" to CameraScanTool(appContext, permissionBroker, activityProvider = { this.takeIf { !it.isFinishing } }),
            "camera_raw" to CameraRawTool(appContext, permissionBroker),
            "camera_depth" to CameraDepthTool(appContext, permissionBroker),
            "camera_stream" to CameraStreamTool(appContext, permissionBroker),
            "camera_extension" to CameraExtensionTool(appContext, permissionBroker),
            // OCR：ML Kit 中文 bundled 模型（随 APK 出厂，离线推理，零权限）。
            "ocr_text" to OcrTextTool(appContext),
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
            // Health Connect 全量：心率/睡眠/血氧/体重/运动/营养 + 周报聚合
            "health_heart_rate" to HeartRateTool(appContext, healthPermissionRequester),
            "health_sleep" to SleepTool(appContext, healthPermissionRequester),
            "health_spo2" to OxygenSaturationTool(appContext, healthPermissionRequester),
            "health_weight" to WeightTool(appContext, healthPermissionRequester),
            "health_exercise" to ExerciseSessionTool(appContext, healthPermissionRequester),
            "health_nutrition" to NutritionTool(appContext, healthPermissionRequester),
            "health_weekly_report" to HealthWeeklyReportTool(appContext, healthPermissionRequester),
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
        // R71: accessibility-service control automation (no extra privilege needed).
        tools.putAll(
            listOf(
                "a11y_status",
                "screen_lock_now",
                "a11y_screen",
                "a11y_click",
                "a11y_long_click",
                "a11y_scroll",
                "a11y_text",
                "a11y_action",
            ).associateWith { AccessibilityTool(appContext, it) },
        )
        // R87c: display_mode（刷新率/HDR 读取 + 窗口级刷新率切换）。
        tools.put("display_mode", DisplayModeTool(appContext) { this })
        tools.putAll(IR_TOOLS.associateWith { IrTool(appContext, it) })
        // R113: 内置红外码表（assets/ir/ir-codes.json + ir_codes KV 用户码）。
        tools.putAll(IR_CODE_TOOLS.associateWith { IrCodeTool(appContext, liveKv, it) })
        tools.putAll(com.dc.andagent.events.EVENT_TOOLS.associateWith { com.dc.andagent.events.EventTool(appContext, it) })
        // R76: 地理围栏（前台定位监听，事件写 geofence_events KV）。
        tools.putAll(com.dc.andagent.events.GEOFENCE_TOOLS.associateWith { com.dc.andagent.events.GeofenceTool(appContext, permissionBroker, it) })
        tools.putAll(com.dc.andagent.pods.PODS_TOOLS.associateWith { com.dc.andagent.pods.PodsTool(appContext, permissionBroker, it) })
        // R148: BLE 温湿度计明文广播监控（扫描即读，无需配对）。
        tools.putAll(com.dc.andagent.pods.THERMO_TOOLS.associateWith { com.dc.andagent.pods.ThermoTool(appContext, permissionBroker, it) })
        // R12/R13 system APIs + R14 automation.
        tools.putAll(SYSTEM_API_TOOLS.associateWith { SystemApiTool(appContext, permissionBroker, it) })
        tools.putAll(AUTOMATION_TOOLS.associateWith { AutomationTool(appContext, permissionBroker, it) })
        tools.putAll(EXTERNAL_DATA_TOOLS.associateWith { ExternalDataTool(appContext, permissionBroker, it) })
        tools.putAll(FILE_OPS_TOOLS.associateWith { FileOpsTool(appContext, it) })
        tools.putAll(ZIP_TOOLS.associateWith { ZipTool(it) })
        tools.putAll(EMAIL_TOOLS.associateWith { EmailTool(appContext, it) })
        tools.putAll(STORE_TOOLS.associateWith { StoreTool(appContext, it) })
        tools.putAll(DEVTOOLS_TOOLS.associateWith { DevToolsTool(appContext, it) })
        tools.putAll(INTENT_TOOLS.associateWith { IntentTool(appContext, it) })
        // R96: 复用 R51 起就在的 KV 底座实例（共享 andagent_kv prefs），供 web_search 读自定义引擎配置。
        tools.putAll(listOf("web_search", "web_read").associateWith { WebSearchTool(it, liveKv) })
        // R73: http_request (generic client, http:// allowed for LAN devices) + lan_scan (LAN probe).
        tools.putAll(HTTP_TOOLS.associateWith { HttpTool(it, appContext) })
        // R144: pcap/pcapng 抓包分析三工具（只读，流式解析，纯 JVM 核心）。
        tools.putAll(PCAP_TOOLS.associateWith { PcapTool(it) })
        // R144 (Roadmap R78 a): MQTT 三工具 mqtt_pub / mqtt_sub / mqtt_status（LAN broker 发布与订阅窗口）。
        tools.putAll(MQTT_TOOLS.associateWith { MqttTool(appContext, it) })
        // R121: 语义化局域网发现（SSDP/UPnP + mDNS/DNS-SD）：电视/音箱/打印机/HA 识别 + UPnP 描述文档。
        tools.putAll(DISCOVERY_TOOLS.associateWith { DiscoveryTool(it, appContext) })
        // R122: Wi-Fi 三件套（DPP 免密入网 / Wi-Fi Direct 无路由器 P2P / RTT 测距）。
        tools.putAll(WIFI_EXT_TOOLS.associateWith { name ->
            when (name) {
                "wifi_easy_connect" -> WifiEasyConnectTool(appContext, permissionBroker, name)
                "wifi_direct" -> WifiDirectTool(appContext, permissionBroker, name)
                else -> WifiRttTool(appContext, permissionBroker, name)
            }
        })
        // R91: SSH 远程运维（JSch 会话池，TOFU known_hosts，破坏性命令分级）。
        tools.putAll(SSH_TOOLS.associateWith { SshTool(appContext, it) })
        tools.putAll(SSH_TRANSFER_TOOLS.associateWith { SshTransferTool(appContext, it) { event ->
            if (::bridge.isInitialized) bridge.emitTransferProgress(event)
        } })
        // R148: SSH 端口转发三工具（L/R/D；D 本版 sshj 不支持，返回固定错误与替代方案）。
        tools.putAll(SSH_TUNNEL_TOOLS.associateWith { SshTunnelTool(it) })
        // R91.5: 凭据元数据（nickname/host/user），secret 只留在 Keystore 加密文件里。
        tools.putAll(SSH_CREDENTIAL_TOOLS.associateWith { SshCredentialTool(appContext, it) })
        // R100 ②: ADB 真实对接（连接/设备/断开/shell/截图/pull/push，共享 AdbSessionHub 状态）。
        tools.putAll(ADB_TOOLS.associateWith { AdbTool(appContext, it) })
        // R35: 联系人新建/通话记录/应用卸载/APK 提取/二维码/传感器录制/文件加密。
        tools.putAll(R35_TOOLS.associateWith { R35Tool(appContext, permissionBroker, it) })
        // R84: 传感器与机身状态（高频/批量采样、心率、运动触发、热状态、电池电气量、波形触感、参数化录音、音频路由）。
        tools.putAll(HARDWARE_STATUS_TOOLS.associateWith { HardwareStatusTool(appContext, permissionBroker, it) })
        // R86: 蓝牙扩展刀（BLE 外设广播、A2DP 媒体路由、标准 GATT 电量、USB 拓扑、USB MIDI）。
        tools.putAll(BT_USB_EXT_TOOLS.associateWith { BtUsbExtTool(appContext, permissionBroker, it) })
        // R74 收口刀: BLE GATT 通用读写/通知 + 经典蓝牙 SPP 串口 + USB bulk 原始传输（8 工具）。
        tools.putAll(BLE_GATT_SPP_TOOLS.associateWith { BleGattSppTool(appContext, permissionBroker, it) })
        // R122: 蓝牙 HID 设备模式（本机作键盘/鼠标注入到已配对目标，API 28+）。
        tools.putAll(BT_HID_TOOLS.associateWith { BtHidTool(appContext, it) })
        tools.putAll(USB_SERIAL_TOOLS.associateWith { SerialTool(appContext, it) })
        // R123 ①: ESP32 ROM bootloader 烧录（复用 USB_SERIAL_TOOLS 的已打开串口）。
        tools.putAll(ESP_FLASH_TOOLS.associateWith { EspFlashTool(appContext, it) })
        // R120: 本地 VPN（VpnService，纯应用权限）：DNS ads 拦截 + 分应用分流 + 流量统计 + pcap 诊断。
        tools.putAll(VPN_TOOLS.associateWith { VpnTool(appContext, permissionBroker, it) })
        // R113: 蜂窝深度（CellInfo/RSRP 诊断、requestNetworkScan 邻区扫描、5G NR 频段/带宽、流量配额）。
        tools.putAll(CELLULAR_TOOLS.associateWith { CellularTool(appContext, permissionBroker, it) })
        // R118: PDF 输出（文本排版 / 图片集拼页），落 Download/AndAgentDocs/；无需权限 broker。
        tools.putAll(PDF_TOOLS.associateWith { PdfTool(appContext, it) })
        // R122: eSIM 管理（EuiccManager：状态/下载/启用/停用/删除；LPA 缺失或权限被拒时如实降级）。
        tools.putAll(ESIM_TOOLS.associateWith { EsimTool(appContext, permissionBroker, it) })
        // R42: the ad-hoc bridge and declarations share one restricted executor.
        tools["android_reflect"] = AndroidReflectTool(RestrictedReflectExecutor(appContext))
        tools.putAll(com.dc.andagent.memory.MEMORY_TOOLS.associateWith { com.dc.andagent.memory.MemoryTool(memoryStore, it) })
        ApiToolFactory.register(appContext, tools)

        skillRegistry = com.dc.andagent.tools.SkillRegistry(appContext, tools, quickJsEngine)
        val skillNames = listOf(
            "skill_install", "skill_list", "skill_reload", "skill_enable", "skill_delete",
            "skill_search", "skill_index_set", "skill_info", "skill_rate", "skill_share", "skill_update",
        )
        skillNames.forEach { tools[it] = com.dc.andagent.tools.SkillTool(skillRegistry, it) }
        skillRegistry.reload()

        // MCP 客户端 + 反向 server：管理工具立即注册，远端工具在后台拉取，避免阻塞首帧。
        mcpRegistry = com.dc.andagent.tools.McpRegistry(appContext, tools)
        Thread({ runCatching { mcpRegistry.reload() } }, "mcp-bootstrap").apply { isDaemon = true }.start()

        // 自我扩展：agent 生成的 my_* 工具（持久化于 filesDir/selfext/，schema 走 extensionTools()）。
        selfExtManager = com.dc.andagent.tools.SelfExtManager(appContext, tools, quickJsEngine)
        listOf(
            "my_tool_create", "my_tool_list", "my_tool_read", "my_tool_update",
            "my_tool_delete", "my_tool_test", "my_tool_enable", "my_tool_share",
        ).forEach { tools[it] = com.dc.andagent.tools.SelfExtTool(selfExtManager, it) }
        selfExtManager.reload()

        WidgetToolRuntime.attach(tools, quickJsEngine)

        bridge = AgentBridge(
            webViewProvider = { webView },
            appContext = appContext,
            settings = settingsStore,
            tools = tools,
            memoryStore = memoryStore,
            skillRegistry = skillRegistry,
            mcpRegistry = mcpRegistry,
            permissions = permissionBroker,
            appVersion = BuildConfig.VERSION_NAME,
            healthPermissionRequest = { requestHealthPermission() },
            termuxStatus = { termuxEnv.status() },
            termuxTerminalExec = { command -> termuxEnv.terminalExec(command) },
            termuxTerminalWrite = { input -> termuxEnv.terminalWrite(input) },
            termuxTerminalPoll = { termuxEnv.terminalPoll() },
            devtoolsStatusJson = { devtoolsStatusJson(appContext) },
            devtoolsSetDebugging = { enable -> setDevToolsDebugging(appContext, enable) },
            projectionStatusJson = { projectionStatusJson(appContext) },
            projectionRequest = { requestProjectionConsent() },
            projectionStop = {
                // 先收尾系统内录：MediaProjection 一旦销毁，采集线程只能报错退出。
                val audio = SystemAudioRecorder.stop()
                val stopped = com.dc.andagent.tools.projectionStopRecording(appContext)
                ProjectionService.stop(appContext)
                val prefix = if (audio != null) "系统内录已停止；" else ""
                prefix + (stopped ?: "没有进行中的投影录屏；已停止屏幕捕获服务")
            },
            embeddedWebviewOpen = { uri -> openEmbeddedWebview(uri) },
            embeddedResourceVisible = { synchronized(embeddedLock) { embeddedLayer != null && embeddedBrowser != null } },
            embeddedResourceClose = { action ->
                if (action != "browser" && action != "browser:hide") "ERROR: 浏览器不存在" else {
                    // R110-review 必修项③：JavaBridge 线程等主线程真实执行结果，
                    // 不再立即返回 "OK" 虚报成功。await 在锁外等，防死锁。
                    val latch = java.util.concurrent.CountDownLatch(1)
                    val result = arrayOfNulls<String>(1)
                    runOnUiThread {
                        try {
                            synchronized(embeddedLock) {
                                if (action == "browser:hide") embeddedLayer?.visibility = View.GONE
                                else {
                                    embeddedLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
                                    embeddedBrowser?.destroy()
                                    embeddedBrowser = null
                                    embeddedLayer = null
                                    embeddedHeader = null
                                }
                                result[0] = "OK"
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                    if (latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) result[0] ?: "ERROR: 操作超时，请重试"
                    else "ERROR: 操作超时，请重试"
                }
            },
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
                if (appContext.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    "Termux RUN_COMMAND 已授权；${termuxEnv.status()}"
                } else {
                    "ERROR: 应用内无法自行授予该权限；请在电脑执行：" +
                        "adb shell pm grant com.dc.andagent com.termux.permission.RUN_COMMAND"
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
            // R133c：assets 打包页面禁用 HTTP 磁盘缓存——覆盖安装新版本后 WebView
            // 可能继续用缓存的旧 index-*.js（hash 不同导致旧 JS 调不存在的新桥方法，
            // 报「桥不可用」）。file:///android_asset 与 https 同源加载下 LOAD_DEFAULT
            // 的缓存语义按 URL 失效性判断，覆盖安装不更新缓存 → 必须显式 NO_CACHE。
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
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
        // 后注册的 callback 先收到返回事件：浏览器浮窗可见时优先消费（层级高于主页面）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val layer = embeddedLayer
                if (layer == null || layer.visibility != View.VISIBLE) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }
                val browser = embeddedBrowser
                when {
                    embeddedSmallWindow -> toggleEmbeddedSize()
                    browser?.canGoBack() == true -> browser.goBack()
                    else -> hideEmbeddedLayer()
                }
            }
        })
        applyUiTheme(isDarkThemeStored())

        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        requestMediaPermissions()
        handleDeeplink(intent)
        handleShareIntent(intent)
        // R145: 从系统 NFC 弹窗冷启动进来时，launch intent 就带 TAG/NDEF extras。
        injectNfcTagIfAny(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::nfcSession.isInitialized) nfcSession.onResume()
        // R105: 解锁/回前台时补渲染待填的 cron a2ui 表单（幂等，消费后队列清空）。
        if (::bridge.isInitialized) bridge.flushA2ui()
        // R117②: 仅当 clip 开关开启时挂监听；on 状态持久化，重启 app 前台自动恢复。
        ClipboardForeground.sync = ::syncClipListener
        syncClipListener()
    }

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
        // R117②: 退到后台即摘掉剪贴板 listener，杜绝任何后台注册。
        ClipboardForeground.sync = null
        detachClipListener()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::nfcSession.isInitialized) nfcSession.onNewIntent(intent)
        handleDeeplink(intent)
        handleShareIntent(intent)
        injectNfcTagIfAny(intent)
    }

    /**
     * R145: 碰一碰 NFC → 自动创建对话并分析 NFC 数据。
     * tag intent（用户从系统 NFC 弹窗选了 AndAgent，或 app 在前台收到 dispatch）真正
     * 进入本 app 时才触发：解析 tag → 构造中文提示词 → 复用 andagent://send deeplink
     * 通道（auto=1，与 R77 磁贴一致）自动发新对话，引导 AI 调 nfc_read 读完整数据。
     * 正在进行的 nfc_read 前台读取不受影响（此时 engine.send 会因 running 自动 no-op）。
     * 解析全程 runCatching，无 NFC 适配器的设备静默不触发，never-crash。
     */
    private fun injectNfcTagIfAny(intent: Intent?) {
        if (intent == null || !::bridge.isInitialized) return
        val prompt = buildNfcPrompt(intent) ?: return
        if (!nfcTagDedup.shouldInject(nfcTagDedupKey(intent))) return
        bridge.queueDeeplink(
            Uri.parse("andagent://send?prompt=${Uri.encode(prompt)}&auto=1"),
        )
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
        // 技能生态: andagent://skill?source=... 一键安装（分享链接的落地页）。
        if (data.host.equals("skill", true)) {
            val source = data.getQueryParameter("source")?.takeIf { it.isNotBlank() } ?: return
            android.app.AlertDialog.Builder(this)
                .setTitle("安装技能")
                .setMessage(source)
                .setPositiveButton("安装") { _, _ ->
                    Thread({
                        val result = runCatching { skillRegistry.install(source) }
                            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                        runOnUiThread { android.widget.Toast.makeText(this, result, android.widget.Toast.LENGTH_LONG).show() }
                    }, "skill-install").apply { isDaemon = true }.start()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // R105: cron a2ui 降级通知的「点开填写」动作。表单已在 durable 队列里，
        // 此处只触发一次 flush，由 onResume/mount drain 幂等消费，避免重复注入。
        if (data.host.equals("a2ui", true)) {
            if (::bridge.isInitialized) bridge.flushA2ui()
            return
        }
        if (::bridge.isInitialized) bridge.queueDeeplink(data)
        if (data.host == "refresh") {
            val id = data.pathSegments.firstOrNull() ?: return
            val record = runCatching { WidgetStore(this).read().records.find { it.id == id } }.getOrNull() ?: return
            WidgetDataScheduler.requestNow(this, record.id, record.spec)
        }
    }

    /**
     * R111: 系统分享（浏览器 / 相册 / 其它 App 的 ACTION_SEND）。文本与链接直接入
     * deeplink 队列；图片先复制到 filesDir/shared_images（content:// 的临时读权限
     * 只在本进程有效，落盘后 R88 图片输入链路才能按路径读），再入队。
     * 解析在主线程只读 Intent 字段，图片复制走后台线程，避免大图卡首帧。
     */
    private fun handleShareIntent(intent: Intent?) {
        val payload = runCatching { ShareIntake.parse(intent) }.getOrNull() ?: return
        val imageUri = payload.imageUri
        if (imageUri == null) {
            if (::bridge.isInitialized) bridge.queueShare(payload.text, null)
            return
        }
        val context = applicationContext
        val text = payload.text
        val mime = payload.mime
        Thread({
            val path = runCatching { ShareIntake.persistImage(context, imageUri, mime) }.getOrNull()
            if (path == null) {
                // 失败（超大/流打不开）时不能静默吞掉用户分享的内容。
                mainHandler.post {
                    runCatching {
                        android.widget.Toast.makeText(
                            this,
                            com.dc.andagent.i18n.tr("分享的图片读取失败", "Could not read the shared image"),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            if (path != null || text.isNotEmpty()) {
                if (::bridge.isInitialized) bridge.queueShare(text, path)
            }
        }, "share-intake").apply { isDaemon = true }.start()
    }

    // R109 用户方案：embedded WebView 单例常驻「页面内悬浮层」（主窗口 overlay，
    // 非 system overlay 免权限）。不销毁 → DevTools target 恒定存活；小窗/全屏两态。
    private var embeddedLayer: ViewGroup? = null
    private var embeddedBrowser: WebView? = null
    private var embeddedHeader: ViewGroup? = null
    /** 小窗/全屏两态标记与切换按钮（主线程读写）。 */
    private var embeddedSmallWindow = false
    private var embeddedCollapseBtn: TextView? = null
    private var embeddedProgress: ProgressBar? = null
    private var embeddedError: TextView? = null
    /** 上次加载的 URL：同 URL 重开时复用页面状态，不强制 reload。 */
    private var lastEmbeddedUrl: String? = null
    /** R110-review 必修项③：三字段主线程写、JavaBridge 线程读，统一用此锁同步。 */
    private val embeddedLock = Any()

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
            synchronized(embeddedLock) {
                // R109 用户方案：单例 WebView 常驻悬浮层。首次创建，此后复用（loadUrl 换页）；
                // 「关闭」= 隐藏悬浮层（WebView 存活，DevTools target 不消失）。
                val browser = embeddedBrowser ?: WebView(this).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(themeBackgroundColor())
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = localMedia.intercept(request) ?: assetLoader.shouldInterceptRequest(request.url)
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https" && request.url.scheme != "appassets"
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            embeddedError?.visibility = android.view.View.GONE
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                            if (request.isForMainFrame) showEmbeddedError(error.description?.toString() ?: "加载失败")
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            embeddedProgress?.let { p ->
                                p.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
                                p.progress = newProgress
                            }
                        }
                    }
                }.also { embeddedBrowser = it }

                val layer = embeddedLayer ?: buildEmbeddedLayer(browser).also { embeddedLayer = it }
                // header 标题更新（host 或本地文件名）
                embeddedHeader?.let { h ->
                    (h.getChildAt(1) as? TextView)?.text = if (uri.scheme == "https") uri.host else uri.lastPathSegment?.substringAfterLast('/') ?: "local"
                }
                if (layer.parent == null) {
                    (findViewById(android.R.id.content) as? ViewGroup)?.addView(layer)
                }
                layer.visibility = android.view.View.VISIBLE
                browser.onResume()
                // 同 URL 复用已加载页面（保留导航状态），不同 URL 才重新加载。
                if (lastEmbeddedUrl != loadUrl) {
                    browser.loadUrl(loadUrl)
                    lastEmbeddedUrl = loadUrl
                }
            }
        }
        return "OPENED"
    }

    /** 悬浮层容器：标题栏（⬇⬆ 两态切换 / ✕ 隐藏）+ 进度条 + WebView + 错误条。 */
    private fun buildEmbeddedLayer(browser: WebView): ViewGroup {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(com.dc.andagent.ui.OverlayChrome.HEADER_BG)
            setPadding(dp(4), dp(2), dp(8), dp(2))
        }
        fun headerButton(text: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
            this.text = text
            contentDescription = desc
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT_DIM)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }
        val collapse = headerButton("⬇", "收起为小窗") { toggleEmbeddedSize() }
        embeddedCollapseBtn = collapse
        header.addView(collapse)
        header.addView(TextView(this).apply { textSize = 15f; setSingleLine(true); setTextColor(com.dc.andagent.ui.OverlayChrome.TEXT); text = "embedded" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        header.addView(progress, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        embeddedProgress = progress
        header.addView(headerButton("✕", "隐藏悬浮层") { hideEmbeddedLayer() })
        synchronized(embeddedLock) { embeddedHeader = header }

        // 错误条：主框架加载失败时置顶显示，点击重试。
        val error = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xEE8B2E2E.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = android.view.View.GONE
            setOnClickListener { visibility = android.view.View.GONE; embeddedBrowser?.reload() }
        }
        embeddedError = error
        val browserHost = FrameLayout(this)
        browserHost.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        browserHost.addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeBackgroundColor())
            addView(header)
            addView(browserHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    /** ⬇/⬆ 在小窗与全屏之间切换（修复原"收起后无法恢复全屏"的死代码问题）。 */
    private fun toggleEmbeddedSize() {
        if (embeddedSmallWindow) setEmbeddedFullscreen() else setEmbeddedSmallWindow()
        embeddedCollapseBtn?.text = if (embeddedSmallWindow) "⬆" else "⬇"
        embeddedCollapseBtn?.contentDescription = if (embeddedSmallWindow) "恢复全屏" else "收起为小窗"
    }

    /** 隐藏悬浮层：视图 GONE 并暂停 WebView（停掉页面媒体/定时器，省电）；WebView 存活保留 DevTools target。 */
    private fun hideEmbeddedLayer() {
        synchronized(embeddedLock) {
            embeddedLayer?.visibility = android.view.View.GONE
            embeddedBrowser?.onPause()
        }
    }

    private fun showEmbeddedError(message: String) {
        embeddedError?.let { e ->
            e.text = "加载失败：$message（点击重试）"
            e.visibility = android.view.View.VISIBLE
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 小窗态：右下角 62% 宽悬浮块（仍在本窗口内，免悬浮窗权限）。 */
    private fun setEmbeddedSmallWindow() {
        synchronized(embeddedLock) {
            embeddedSmallWindow = true
            embeddedLayer?.layoutParams = (embeddedLayer?.layoutParams ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)).also {
                val dm = resources.displayMetrics
                val w = (dm.widthPixels * 0.62f).toInt()
                it as FrameLayout.LayoutParams
                it.width = w
                it.height = (w * 16 / 9)
                it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                it.setMargins(dp(12), dp(12), dp(12), dp(140))
            }
            // 小窗态启用统一浮窗外壳：圆角 + 描边 + 阴影。
            embeddedLayer?.let { l ->
                com.dc.andagent.ui.OverlayChrome.applyRounded(l)
                com.dc.andagent.ui.OverlayChrome.background(l)
                l.elevation = dp(8).toFloat()
            }
            embeddedLayer?.setPadding(0, 0, 0, 0)
            embeddedLayer?.requestLayout()
        }
    }

    /** 全屏态（默认）。 */
    private fun setEmbeddedFullscreen() {
        synchronized(embeddedLock) {
            embeddedSmallWindow = false
            embeddedLayer?.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, 0, 0, 0) }
            // 全屏态退出外壳样式：恢复直角与主题底色（全屏铺满窗口）。
            embeddedLayer?.let { l ->
                com.dc.andagent.ui.OverlayChrome.clearRounded(l)
                l.elevation = 0f
                l.background = null
                l.setBackgroundColor(themeBackgroundColor())
            }
            // 全屏态吃满窗口（edge-to-edge），header 会顶进状态栏、底沿贴手势条——
            // 用 insets padding 让出安全区（关闭按钮不被遮挡）。小窗态已用 margin 避开，无需处理。
            embeddedLayer?.setOnApplyWindowInsetsListener { view, insets ->
                val sys = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                        or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                view.setPadding(sys.left, sys.top, sys.right, sys.bottom)
                insets
            }
            embeddedLayer?.requestApplyInsets()
            embeddedLayer?.requestLayout()
        }
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
        // 应用内伪浮窗挂在 decorView 上：Activity 销毁时同步清理（onDismiss 会停止相机服务）。
        com.dc.andagent.tools.InAppOverlayHost.dismiss()
        dbGuiDialog?.dismiss()
        synchronized(embeddedLock) {
            embeddedBrowser?.destroy()
            embeddedBrowser = null
            embeddedLayer = null
            embeddedHeader = null
        }
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
        val permissions = com.dc.andagent.tools.HEALTH_READ_PERMISSIONS
        runOnUiThread {
            runCatching { healthPermissionLauncher.launch(permissions) }
        }
        return buildJsonObject {
            put("requested", true)
            put("message", "已打开 Health Connect 授权页，请允许读取步数、心率、睡眠、血氧、体重、运动与营养数据")
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

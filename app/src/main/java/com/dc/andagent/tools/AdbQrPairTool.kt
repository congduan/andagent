package com.dc.andagent.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Settings AdbQrCode accepts T:ADB. SSID is an mDNS instance, not a Wi-Fi network. */
internal object AdbQrPayload {
    fun escape(value: String): String = buildString {
        value.forEach { ch ->
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':') append('\\')
            append(ch)
        }
    }

    fun build(ssid: String, password: String): String {
        require(ssid.isNotEmpty() && password.isNotEmpty())
        return "WIFI:T:ADB;S:${escape(ssid)};P:${escape(password)};;"
    }

    fun random(): Pair<String, String> {
        val random = SecureRandom()
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = CharArray(10) { alphabet[random.nextInt(alphabet.length)] }.concatToString()
        val password = CharArray(10) { ('0'.code + random.nextInt(10)).toChar() }.concatToString()
        return "studio-$suffix" to password
    }
}

internal object AdbQrSession {
    data class Pending(val ssid: String, val password: String, val seconds: Int, val result: CompletableDeferred<String>)
    @Volatile var pending: Pending? = null
}

object AdbQrPairTool {
    const val NAME = "adb_pair_qr"
    val description = "显示 ADB 无线调试二维码供目标手机扫码；自动发现目标机配对服务并用 SPAKE2 配对。目标手机需 Android 11+，与本机处于同一局域网。"
    val parameters = schemaOf("seconds" to intParam("扫码等待秒数，默认 120，范围 30..300"))

    suspend fun execute(context: Context, args: JsonObject): String {
        if (!AdbPairNative.available) return "ERROR: 原生 ADB 配对库不可用；需 arm64-v8a 真机。"
        val seconds = (args.intArg("seconds") ?: 120).coerceIn(30, 300)
        val (ssid, password) = AdbQrPayload.random()
        val session = AdbQrSession.Pending(ssid, password, seconds, CompletableDeferred())
        synchronized(AdbQrSession) {
            if (AdbQrSession.pending != null) return "ERROR: 已有二维码配对正在进行"
            AdbQrSession.pending = session
        }
        return try {
            context.startActivity(Intent(context, AdbQrPairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeoutOrNull((seconds + 25L) * 1000) { session.result.await() }
                ?: "ERROR: 超时未配对，请确保目标机在扫码页且同一网络。"
        } catch (e: Exception) {
            "ERROR: 无法打开二维码配对页：${e.message ?: e.javaClass.simpleName}"
        } finally {
            synchronized(AdbQrSession) { if (AdbQrSession.pending === session) AdbQrSession.pending = null }
        }
    }
}

/** Full-screen, inset-aware QR screen. The phone starts the server after scanning. */
class AdbQrPairActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var started = 0L
    private var pending: AdbQrSession.Pending? = null
    private lateinit var status: TextView
    private lateinit var countdown: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = AdbQrSession.pending ?: run { finish(); return }
        pending = session
        started = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply { text = "ADB 扫码配对"; textSize = 25f; setTextColor(Color.BLACK); gravity = Gravity.CENTER }
        val matrix = QrCodec.encode(AdbQrPayload.build(session.ssid, session.password), 768, ErrorCorrectionLevel.M)
        val pixels = IntArray(768 * 768) { index -> if (matrix.get(index % 768, index / 768)) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 768, 768, Bitmap.Config.ARGB_8888)
        val qr = ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true }
        status = TextView(this).apply { text = "在目标手机打开：无线调试 → 使用二维码配对设备，然后扫描此码"; textSize = 18f; setTextColor(Color.BLACK); gravity = Gravity.CENTER }
        countdown = TextView(this).apply { textSize = 16f; setTextColor(Color.DKGRAY); gravity = Gravity.CENTER }
        val close = Button(this).apply { text = "取消"; setOnClickListener { complete("ERROR: 用户取消二维码配对") } }
        root.addView(title)
        root.addView(qr, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(countdown)
        root.addView(close)
        setContentView(root)
        discover(session)
        tick()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun tick() {
        if (done.get()) return
        val seconds = pending?.seconds ?: return
        val remain = seconds - ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
        countdown.text = "剩余 ${remain.coerceAtLeast(0)} 秒"
        if (remain <= 0) complete("ERROR: 超时未配对，请确保目标机在扫码页且同一网络。")
        else handler.postDelayed(::tick, 1000)
    }

    private fun discover(session: AdbQrSession.Pending) {
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        lock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("andagent-adb-qr")?.apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { complete("ERROR: mDNS 发现启动失败 ($code)，请检查 Wi-Fi。") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (done.get() || info.serviceName != session.ssid) return
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (done.get() || resolved.serviceName != session.ssid) return
                        val host = resolved.host?.hostAddress ?: return
                        val port = resolved.port.takeIf { it in 1..65535 } ?: return
                        if (!pairing.compareAndSet(false, true)) return
                        runOnUiThread { status.text = "已发现 $host:$port，正在安全配对…" }
                        Thread {
                            val raw = runCatching {
                                AdbPairNative.pair(host, port, session.password, AdbSessionHub.adbPublicKey(), "AndAgent")
                            }.getOrElse { "ERROR\nREASON=${it.javaClass.simpleName}\n" }
                            if (raw.startsWith("OK")) {
                                if (done.get()) return@Thread
                                AdbSessionHub.rememberPairing(host, AdbPairTool.pairingFingerprint(raw))
                                complete("✅ 配对成功：${AdbPairTool.fields(raw)["GUID"].orEmpty()} ($host)。请在目标机无线调试主页查看主端口，然后调用 adb_connect(host=\"$host\", port=<主端口>)；配对端口 $port 不能用于连接。")
                            } else complete("ERROR: 目标机已被发现，但 SPAKE2 配对失败（${AdbPairTool.fields(raw)["REASON"] ?: "协议错误"}）。请确认两机在同一 Wi-Fi 后重新扫码。")
                        }.start()
                    }
                })
            }
        }
        discovery = listener
        try { manager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { complete("ERROR: mDNS 发现失败：${e.message}") }
    }

    private fun complete(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { complete(message) }; return }
        if (!done.compareAndSet(false, true)) return
        status.text = message
        pending?.result?.complete(message)
        handler.postDelayed({ finish() }, if (message.startsWith("✅")) 3000 else 1000)
    }

    override fun onDestroy() {
        discovery?.let { listener -> runCatching { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) } }
        lock?.let { runCatching { it.release() } }
        handler.removeCallbacksAndMessages(null)
        if (!done.get()) pending?.result?.complete("ERROR: 二维码配对页已关闭")
        super.onDestroy()
    }
}

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
import android.util.Log
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

internal object AdbQrProgress {
    val pairingServiceTypes = listOf("_adb-tls-pairing._tcp")
    enum class Stage(val label: String) {
        QR_READY("二维码已显示"), DISCOVERING("mDNS 发现"), SERVICE_FOUND("发现配对服务"),
        RESOLVING("解析配对服务"), CONNECTING("连接配对端口 / TLS / SPAKE2"), COMPLETE("配对完成")
    }

    fun advance(current: Stage, next: Stage): Stage = if (next.ordinal > current.ordinal) next else current

    fun timeout(stage: Stage, seconds: Int): String =
        "ERROR: 扫码配对超时（${seconds} 秒，阶段：${stage.label}）。" + when (stage) {
            Stage.QR_READY, Stage.DISCOVERING -> "未发现目标机广播；确认目标机已扫码、两机同一 Wi-Fi，并检查路由器 AP 隔离。"
            Stage.SERVICE_FOUND, Stage.RESOLVING -> "发现广播但无法解析服务地址；检查 Wi-Fi / mDNS。"
            Stage.CONNECTING -> "已取得配对地址，但连接或握手未完成；检查两机互访及目标机无线调试状态。"
            Stage.COMPLETE -> "请查看配对结果。"
        }
}

/** Settings AdbQrCode accepts T:ADB. SSID is an mDNS instance, not a Wi-Fi network. */
internal object AdbQrPayload {
    /** AOSP QR mode uses S as the mDNS instance name and P as the SPAKE2 password. */
    fun passwordForService(instanceName: String, ssid: String, password: String): String? =
        password.takeIf {
            instanceName == ssid || instanceName.startsWith(ssid) || unescapeDnsSd(instanceName) == ssid
        }

    private fun unescapeDnsSd(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                if (i + 3 < value.length && value.substring(i + 1, i + 4).all { it in '0'..'9' }) {
                    val code = value.substring(i + 1, i + 4).toInt()
                    if (code in 0..255) { append(code.toChar()); i += 4; continue }
                }
                append(value[i + 1]); i += 2
            } else { append(value[i]); i++ }
        }
    }

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
    data class Pending(val ssid: String, val password: String, val seconds: Int, val result: CompletableDeferred<String>) {
        @Volatile var stage = AdbQrProgress.Stage.QR_READY
        @Volatile var nativeStage = "未进入 native"
        @Volatile var lastFailure = "无"
        private val foundNames = mutableListOf<String>()
        private var matched = 0
        private var mismatched = 0
        private var failures = 0
        @Synchronized fun failure(reason: String) { failures++; lastFailure = reason }
        @Synchronized fun found(name: String, matches: Boolean) {
            foundNames += name
            if (matches) matched++ else mismatched++
        }
        @Synchronized fun evidence(): String = buildString {
            append("发现 ${foundNames.size} 个服务，匹配 $matched 个，不匹配 $mismatched 个，实例名=")
            append(foundNames.joinToString(prefix = "[", postfix = "]"))
            if (foundNames.isNotEmpty() && matched == 0) append("，均不匹配期望 S=$ssid")
            else append("，期望 S=$ssid")
            append("；失败计数=$failures，最后停留阶段=${stage.label}，nativeStage=$nativeStage，最后失败=$lastFailure")
        }
    }
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
            withTimeoutOrNull((seconds + 50L) * 1000) { session.result.await() }
                ?: "ERROR: 扫码配对页未返回结果。${session.evidence()}"
        } catch (e: Exception) {
            session.failure("页面启动失败: ${e.javaClass.simpleName}")
            Log.e("AdbQrPair", "stage=QR_READY activity start failed", e)
            "ERROR: 无法打开二维码配对页：${e.message ?: e.javaClass.simpleName}。${session.evidence()}"
        } finally {
            synchronized(AdbQrSession) { if (AdbQrSession.pending === session) AdbQrSession.pending = null }
        }
    }
}

/** Full-screen, inset-aware QR screen. The phone starts the server after scanning. */
class AdbQrPairActivity : Activity() {
    private val tag = "AdbQrPair"
    private val handler = Handler(Looper.getMainLooper())
    private val done = AtomicBoolean(false)
    private val pairing = AtomicBoolean(false)
    private val resolving = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var started = 0L
    private var pending: AdbQrSession.Pending? = null
    private var resolveAttempts = 0
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
        Log.i(tag, "stage=QR_READY service=${session.ssid} timeout=${session.seconds}s")
        discover(session)
        tick()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun stage(next: AdbQrProgress.Stage, detail: String = "") {
        pending?.let { it.stage = AdbQrProgress.advance(it.stage, next) }
        Log.i(tag, "stage=${next.name} $detail")
    }

    private fun tick() {
        if (done.get()) return
        val seconds = pending?.seconds ?: return
        val limit = seconds + if (pairing.get()) 40 else 0
        val remain = limit - ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
        countdown.text = "剩余 ${remain.coerceAtLeast(0)} 秒"
        if (remain <= 0) complete(AdbQrProgress.timeout(pending?.stage ?: AdbQrProgress.Stage.QR_READY, limit))
        else handler.postDelayed(::tick, 1000)
    }

    private fun discover(session: AdbQrSession.Pending) {
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        lock = try {
            (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("andagent-adb-qr")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            Log.e(tag, "stage=DISCOVERING multicast lock failed", e)
            null
        }
        Log.i(tag, "multicastLock=${lock?.isHeld == true}")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { stage(AdbQrProgress.Stage.DISCOVERING, "type=$type") }
            override fun onDiscoveryStopped(type: String) { Log.i(tag, "discovery stopped type=$type") }
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                session.failure("发现启动失败: type=$type code=$code")
                Log.e(tag, "discovery start failed type=$type code=$code")
                complete("ERROR: 阶段 mDNS 发现：启动失败 ($code)，请检查 Wi-Fi。")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) { Log.w(tag, "discovery stop failed code=$code") }
            override fun onServiceLost(info: NsdServiceInfo) { Log.i(tag, "service lost name=${info.serviceName}") }
            override fun onServiceFound(info: NsdServiceInfo) {
                val matches = AdbQrPayload.passwordForService(info.serviceName, session.ssid, session.password) != null
                session.found(info.serviceName, matches)
                Log.i(tag, "service found serviceName=${info.serviceName} expectedS=${session.ssid} matches=$matches type=${info.serviceType}")
                if (!matches) { session.failure("实例名不匹配"); return }
                if (done.get()) { Log.i(tag, "service ignored: already finished"); return }
                stage(AdbQrProgress.Stage.SERVICE_FOUND)
                resolve(info)
            }

            private fun resolve(info: NsdServiceInfo) {
                if (done.get() || pairing.get()) { Log.i(tag, "resolve skipped: done=${done.get()} pairing=${pairing.get()}"); return }
                if (!resolving.compareAndSet(false, true)) { Log.i(tag, "resolve skipped: another resolution active"); return }
                resolveAttempts++
                stage(AdbQrProgress.Stage.RESOLVING, "attempt=$resolveAttempts")
                try { manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        resolving.set(false)
                        session.failure("解析失败 code=$code")
                        Log.w(tag, "stage=RESOLVING failed code=$code attempt=$resolveAttempts")
                        if (resolveAttempts < 2) handler.postDelayed({ resolve(info) }, 1000)
                        else complete("ERROR: 阶段服务解析：mDNS 解析失败 ($code)，已重试；请检查同一 Wi-Fi 和 AP 隔离。")
                    }
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        resolving.set(false)
                        if (done.get()) { Log.i(tag, "resolved service ignored: already finished"); return }
                        Log.i(tag, "stage=RESOLVING resolved name=${resolved.serviceName} host=${resolved.host?.hostAddress} port=${resolved.port}")
                        if (AdbQrPayload.passwordForService(resolved.serviceName, session.ssid, session.password) == null) {
                            session.failure("解析后实例名不匹配: ${resolved.serviceName}")
                            Log.w(tag, "resolved service mismatch name=${resolved.serviceName} expectedS=${session.ssid}")
                            return
                        }
                        val host = resolved.host?.hostAddress
                        val port = resolved.port
                        if (host.isNullOrBlank() || port !in 1..65535) {
                            session.failure("解析地址或端口无效")
                            Log.e(tag, "resolved service has invalid host=$host port=$port")
                            complete("ERROR: 阶段服务解析：目标机广播缺少有效地址或配对端口。")
                            return
                        }
                        if (!pairing.compareAndSet(false, true)) { Log.i(tag, "pair skipped: another pairing active"); return }
                        stage(AdbQrProgress.Stage.CONNECTING, "host=$host port=$port")
                        session.nativeStage = "调用中"
                        runOnUiThread { status.text = "已发现 $host:$port，正在安全配对…" }
                        Thread {
                            val raw = runCatching {
                                AdbPairNative.pair(host, port, session.password, AdbSessionHub.adbPublicKey(), "AndAgent")
                            }.getOrElse { "ERROR\nREASON=${it.javaClass.simpleName}\n" }
                            val fields = AdbPairTool.fields(raw)
                            session.nativeStage = fields["STAGE"] ?: if (raw.startsWith("OK")) "完成" else "未返回 STAGE"
                            if (!raw.startsWith("OK")) session.failure(fields["REASON"] ?: "协议错误")
                            Log.i(tag, "stage=PAIR_RESULT success=${raw.startsWith("OK")} nativeStage=${fields["STAGE"]} reason=${fields["REASON"]}")
                            if (raw.startsWith("OK")) {
                                if (done.get()) return@Thread
                                AdbSessionHub.rememberPairing(host, AdbPairTool.pairingFingerprint(raw))
                                stage(AdbQrProgress.Stage.COMPLETE)
                                complete("✅ 配对成功：${AdbPairTool.fields(raw)["GUID"].orEmpty()} ($host)。请在目标机无线调试主页查看主端口，然后调用 adb_connect(host=\"$host\", port=<主端口>)；配对端口 $port 不能用于连接。")
                            } else complete("ERROR: 阶段连接 / 握手（原生阶段 ${fields["STAGE"] ?: "未知"}）：${fields["REASON"] ?: "协议错误"}；目标机 $host:$port。请确认两机可互访后重新扫码。")
                        }.start()
                    }
                }) } catch (e: Exception) {
                    resolving.set(false)
                    session.failure("解析异常: ${e.javaClass.simpleName}")
                    Log.e(tag, "stage=RESOLVING exception", e)
                    complete("ERROR: 阶段服务解析：${e.javaClass.simpleName}。")
                }
            }
        }
        discovery = listener
        try { manager.discoverServices(AdbQrProgress.pairingServiceTypes.single(), NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) {
            session.failure("发现异常: ${e.javaClass.simpleName}")
            Log.e(tag, "stage=DISCOVERING exception", e)
            complete("ERROR: 阶段 mDNS 发现：${e.message}")
        }
    }

    private fun complete(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { runOnUiThread { complete(message) }; return }
        if (!done.compareAndSet(false, true)) return
        Log.i(tag, "stage=FINISHED result=${if (message.startsWith("✅")) "success" else "failure"} last=${pending?.stage}")
        val report = if (message.startsWith("✅")) message else "$message ${pending?.evidence().orEmpty()}"
        status.text = report
        pending?.result?.complete(report)
        handler.postDelayed({ finish() }, if (message.startsWith("✅")) 3000 else 1000)
    }

    override fun onDestroy() {
        discovery?.let { listener -> runCatching { (getSystemService(NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) } }
        lock?.let { runCatching { it.release() } }
        handler.removeCallbacksAndMessages(null)
        if (!done.get()) pending?.result?.complete("ERROR: 二维码配对页已关闭。${pending?.evidence().orEmpty()}")
        super.onDestroy()
    }
}

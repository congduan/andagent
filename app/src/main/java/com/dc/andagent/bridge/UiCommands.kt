package com.dc.andagent.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** R20: supported screen-orientation requests (wire values accepted from JS). */
enum class UiOrientation(val wire: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape"),
    AUTO("auto"),
}

/** R20: haptic presets exposed to the WebView. */
enum class UiHaptic { CLICK, DOUBLE, HEAVY }

/**
 * Pure parsers live outside the class so they can be unit-tested without an
 * Android runtime. Unknown/blank values return null — callers turn that into
 * an "ERROR:" string instead of throwing.
 */
fun parseOrientationMode(raw: String?): UiOrientation? =
    when (raw?.trim()?.lowercase()) {
        "portrait" -> UiOrientation.PORTRAIT
        "landscape" -> UiOrientation.LANDSCAPE
        "auto" -> UiOrientation.AUTO
        else -> null
    }

fun parseHapticPattern(raw: String?): UiHaptic? =
    when (raw?.trim()?.lowercase()) {
        "click", "tap", "light" -> UiHaptic.CLICK
        "double", "double_click" -> UiHaptic.DOUBLE
        "heavy", "strong" -> UiHaptic.HEAVY
        else -> null
    }

/** A soft keyboard is assumed visible when it covers >15% of the window. */
fun keyboardVisibleFromInset(rootHeight: Int, visibleBottom: Int): Boolean {
    if (rootHeight <= 0) return false
    val covered = rootHeight - visibleBottom
    return covered > rootHeight * 0.15
}

/**
 * R20: UI/platform commands consumed by [AgentBridge].
 *
 * Every public method is synchronous for the JS bridge, executes window/View
 * work on the main thread, and never throws: failures come back as
 * "ERROR: ..." strings. A null/destroyed Activity yields an error payload.
 */
class UiCommands(
    private val activity: Activity?,
    private val webViewProvider: () -> WebView? = { null },
) {
    // Lazy: constructing UiCommands without an Activity must stay side-effect free.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun toast(message: String, long: Boolean): String {
        val text = message.trim().take(500)
        if (text.isEmpty()) return "ERROR: toast 内容为空"
        return onMain { a ->
            Toast.makeText(a, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            ok("toast")
        }
    }

    fun haptic(pattern: String): String {
        val parsed = parseHapticPattern(pattern) ?: return "ERROR: 不支持的震动模式 $pattern"
        return onMain { a ->
            val vibrator = a.getSystemService(Vibrator::class.java)
                ?: return@onMain "ERROR: 设备不支持震动"
            if (!vibrator.hasVibrator()) return@onMain "ERROR: 设备不支持震动"
            if (Build.VERSION.SDK_INT >= 29) {
                val effectId = when (parsed) {
                    UiHaptic.CLICK -> VibrationEffect.EFFECT_CLICK
                    UiHaptic.DOUBLE -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    UiHaptic.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    when (parsed) {
                        UiHaptic.CLICK -> 15L
                        UiHaptic.DOUBLE -> 35L
                        UiHaptic.HEAVY -> 55L
                    },
                )
            }
            ok("haptic", "pattern" to parsed.name.lowercase())
        }
    }

    fun keepScreenOn(enabled: Boolean): String = onMain { a ->
        if (enabled) {
            a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ok("keepScreenOn", "enabled" to enabled)
    }

    fun immersive(enabled: Boolean): String = onMain { a ->
        @Suppress("DEPRECATION")
        val decor = a.window.decorView
        if (enabled) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        } else {
            // 退出全屏只恢复状态栏/导航栏，保留宿主 Activity 的沉浸式基线
            // （内容仍铺到状态栏之后）；否则系统栏会在顶栏上方重新压出一条
            // 与页面不同色的色带，而 --safe-top 的避让高度还留着。
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
        ok("immersive", "enabled" to enabled)
    }

    fun orientation(mode: String): String {
        val parsed = parseOrientationMode(mode) ?: return "ERROR: 不支持的方向模式 $mode"
        return onMain { a ->
            a.requestedOrientation = when (parsed) {
                UiOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                UiOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                UiOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_USER
            }
            ok("orientation", "mode" to parsed.wire)
        }
    }

    fun navBack(): String = onMain { a ->
        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        val handled = a.dispatchKeyEvent(down) or a.dispatchKeyEvent(up)
        if (!handled) "ERROR: 当前页面未处理返回键" else ok("navBack")
    }

    fun navMinimize(): String = onMain { a ->
        a.moveTaskToBack(true)
        ok("navMinimize")
    }

    fun share(text: String, subject: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "ERROR: 分享内容为空"
        val body = trimmed.take(20_000)
        val title = subject.trim().take(200).ifBlank { "分享" }
        return onMain { a ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
            a.startActivity(Intent.createChooser(send, title))
            // Review fix: surface truncation instead of silently dropping text.
            ok("share", "truncated" to (body.length < trimmed.length))
        }
    }

    fun secureFlag(enabled: Boolean): String = onMain { a ->
        if (enabled) {
            a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        ok("secureFlag", "enabled" to enabled)
    }

    fun keyboard(show: Boolean): String = onMain { a ->
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@onMain "ERROR: 输入法服务不可用"
        val target = webViewProvider() ?: a.currentFocus ?: a.window.decorView
        if (show) {
            target.requestFocus()
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(target.windowToken, 0)
        }
        ok("keyboard", "visible" to show)
    }

    fun status(): String = onMain { a ->
        @Suppress("DEPRECATION")
        val decor = a.window.decorView
        val flags = a.window.attributes.flags
        val frame = Rect()
        decor.getWindowVisibleDisplayFrame(frame)
        @Suppress("DEPRECATION")
        val keyboardVisible = keyboardVisibleFromInset(decor.rootView.height, frame.bottom)
        buildJsonObject {
            put("ok", true)
            put("activityAlive", !a.isFinishing && !a.isDestroyed)
            put("keyboardVisible", keyboardVisible)
            @Suppress("DEPRECATION")
            put("immersive", (decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0)
            put("keepScreenOn", (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
            put("secure", (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
            put("orientation", currentOrientation(a))
            put("requestedOrientation", requestedMode(a.requestedOrientation))
            put("webViewReady", webViewProvider() != null)
        }.toString()
    }

    fun screenMetrics(): String = onMain { a ->
        val metrics = a.resources.displayMetrics
        val configuration = a.resources.configuration
        buildJsonObject {
            put("ok", true)
            put("width", metrics.widthPixels)
            put("height", metrics.heightPixels)
            put("widthDp", (metrics.widthPixels / metrics.density).toInt())
            put("heightDp", (metrics.heightPixels / metrics.density).toInt())
            put("density", metrics.density.toDouble())
            put("densityDpi", metrics.densityDpi)
            put("orientation", currentOrientation(a))
            put("configurationOrientation", configuration.orientation)
        }.toString()
    }

    fun reload(): String = onMain {
        val view = webViewProvider() ?: return@onMain "ERROR: WebView 不可用"
        view.reload()
        ok("webReload")
    }

    /* ------------------------------ plumbing ------------------------------ */

    private fun ok(action: String, vararg extra: Pair<String, Any?>): String = buildJsonObject {
        put("ok", true)
        put("action", action)
        extra.forEach { (key, value) ->
            when (value) {
                null -> put(key, null as String?)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }.toString()

    private fun currentOrientation(a: Activity): String =
        if (a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"

    private fun requestedMode(requested: Int): String = when (requested) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        -> "portrait"
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
        -> "landscape"
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        ActivityInfo.SCREEN_ORIENTATION_USER,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR,
        ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
        -> "auto"
        else -> "locked"
    }

    /**
     * Runs [block] on the main thread (directly when already there) and waits
     * at most 2s. Exceptions are converted to "ERROR:" payloads so the
     * JavaBridge thread never sees a throwable.
     */
    private fun onMain(block: (Activity) -> String): String {
        val current = activity ?: return "ERROR: Activity 不可用"
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching {
                if (current.isFinishing || current.isDestroyed) "ERROR: Activity 已结束"
                else block(current)
            }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
        }
        var result = "ERROR: UI 命令超时"
        val latch = CountDownLatch(1)
        // R20 review fix: exactly one of "started on main" / "timed out" wins
        // via CAS on a single state flag — a task that begins after the 2s
        // timeout must not run its side effects (share dialog / FLAG_SECURE /
        // nav) behind the caller's back, and a caller must not report a
        // timeout for a task that already began.
        val state = java.util.concurrent.atomic.AtomicInteger(0) // 0=pending 1=started 2=cancelled
        val task = Runnable {
            if (!state.compareAndSet(0, 1)) { // lost the race: already cancelled
                latch.countDown()
                return@Runnable
            }
            result = runCatching {
                if (current.isFinishing || current.isDestroyed) "ERROR: Activity 已结束"
                else block(current)
            }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            latch.countDown()
        }
        val posted = runCatching { mainHandler.post(task) }.getOrDefault(false)
        if (!posted) return "ERROR: 无法投递 UI 任务"
        return if (runCatching { latch.await(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            result
        } else if (state.compareAndSet(0, 2)) {
            runCatching { mainHandler.removeCallbacks(task) }
            "ERROR: UI 命令超时"
        } else {
            // Task already started on main — side effects are in flight, so
            // wait a little more and report the real outcome instead of a
            // misleading timeout. Only read `result` after the latch fires
            // (countDown happens-after the write, guaranteeing visibility);
            // if the task is still running past the deadline, say so
            // explicitly instead of guessing.
            if (runCatching { latch.await(3, TimeUnit.SECONDS) }.getOrDefault(false)) {
                result
            } else {
                "ERROR: UI 命令超时（任务已在主线程执行，副作用可能已发生）"
            }
        }
    }
}

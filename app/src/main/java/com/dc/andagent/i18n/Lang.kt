package com.dc.andagent.i18n

import android.content.Context
import java.util.Locale

/**
 * UI language shared between the WebView and the native layer.
 *
 * The web layer owns the setting (localStorage) because the language switch
 * lives in the settings panel; it pushes the resolved mode here through
 * `setUiLanguage` so tool results and native screens use the same language.
 * Before the first sync (or when the mode is "system") the device locale
 * decides, which matches what the WebView picks for `navigator.language`.
 */
object Lang {
    const val SYSTEM = "system"
    const val ZH = "zh"
    const val EN = "en"

    private const val PREFS = "andagent_settings"
    private const val KEY = "ui_language"

    @Volatile
    private var mode: String = SYSTEM

    /** Load the persisted mode; call once from Application.onCreate. */
    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = normalize(prefs.getString(KEY, SYSTEM))
    }

    /** Persist the mode pushed by the web layer ("system" | "zh" | "en"). */
    fun setMode(context: Context?, value: String?) {
        val next = normalize(value)
        mode = next
        context?.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY, next)?.apply()
    }

    fun mode(): String = mode

    fun isZh(): Boolean = when (mode) {
        ZH -> true
        EN -> false
        else -> Locale.getDefault().language == "zh"
    }

    /** Pick the string matching the current UI language. */
    fun t(zh: String, en: String): String = if (isZh()) zh else en

    private fun normalize(value: String?): String = when (value) {
        ZH, EN -> value
        else -> SYSTEM
    }
}

/** Shorthand for [Lang.t] used by tool results and native screens. */
fun tr(zh: String, en: String): String = Lang.t(zh, en)

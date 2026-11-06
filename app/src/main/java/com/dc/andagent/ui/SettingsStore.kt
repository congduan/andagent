package com.dc.andagent.ui

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Settings persisted in SharedPreferences.
 *
 * No active endpoint, model ID or API key is compiled into this store: they are loaded
 * at runtime from `Download/config.json` on the device (see
 * [reloadExternalConfig]). If that file is missing, the fields stay empty and
 * must be filled in through the Settings panel.
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("andagent_settings", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SettingsStore"
        const val CONFIG_FILE_NAME = "config.json"

        private const val DEFAULT_BASE_URL = ""
        private const val DEFAULT_API_KEY = ""
        private const val DEFAULT_MODEL = ""
        private val CONFIG_FIELDS = mapOf(
            "baseUrl" to "base_url",
            "apiKey" to "api_key",
            "model" to "model",
            "systemPrompt" to "system_prompt",
            "transcribeModel" to "transcribe_model",
            "transcribeBaseUrl" to "transcribe_base_url",
            "ttsBaseUrl" to "tts_base_url",
            "ttsModel" to "tts_model",
            "ttsVoice" to "tts_voice",
        )

        const val DEFAULT_SYSTEM_PROMPT =
            "你是 AndAgent，一个运行在 Android 手机上的智能助手。\n" +
                "回答使用简体中文，简洁、直接、可执行；需要数据时先调用工具，不要编造。\n" +
                "可用工具覆盖：设备/存储/相册状态、日历（calendar_*）、闹钟与计时器、电池、Wi-Fi、剪贴板、本地通知、已安装应用。\n" +
                "网络搜索：web_search（无需 key）/ web_read（读网页正文）；问时效性问题先用搜索。\n" +
                "截屏/录屏：优先 screen_capture / screen_record（已授权 MediaProjection 时全自动）；" +
                "未授权时说明需要用户在系统弹窗中点一次「立即开始」。\n" +
                "SSH 运维：ssh_connect 可连接远程服务器执行运维命令（首次连接需确认主机指纹），" +
                "ssh_exec 执行命令，破坏性命令（rm -rf、systemctl restart、mkfs 等）会请求确认。\n" +
                "写入类操作（日历新增/删除）执行前先向用户说明将要修改的内容。"
    }

    init {
        reloadExternalConfig()
    }

    /**
     * Import settings from `Download/config.json`. Supported keys: baseUrl,
     * apiKey, model, systemPrompt (all optional). Values in the file take
     * precedence, so editing the file and reopening Settings applies them.
     * Missing/unreadable/invalid files are ignored without failing startup.
     */
    fun reloadExternalConfig(): Boolean {
        val raw = readConfigFile()
        if (raw == null) {
            Log.w(TAG, "No readable $CONFIG_FILE_NAME in Download/ or app external files")
            return false
        }
        val config = runCatching { JSONObject(raw) }.getOrElse {
            Log.w(TAG, "Invalid JSON in $CONFIG_FILE_NAME: ${it.javaClass.simpleName}")
            return false
        }
        val editor = prefs.edit()
        var changed = 0
        for ((key, prefKey) in CONFIG_FIELDS) {
            if (!config.has(key)) continue
            val value = config.optString(key, "")
            if (prefs.getString(prefKey, null) != value) {
                editor.putString(prefKey, value)
                changed++
            }
        }
        if (changed > 0) {
            editor.apply()
            Log.i(TAG, "Imported $changed settings from $CONFIG_FILE_NAME")
        }
        return true
    }

    /**
     * Prefer the public Downloads directory (user-editable). Modern Android
     * may deny direct File access there, so fall back to MediaStore and then to
     * the app's private external files dir, which needs no storage permission.
     */
    private fun readConfigFile(): String? {
        listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CONFIG_FILE_NAME),
            File("/sdcard/Download", CONFIG_FILE_NAME),
            File("/storage/emulated/0/Download", CONFIG_FILE_NAME),
        ).forEach { file ->
            if (runCatching { file.isFile && file.canRead() }.getOrDefault(false)) {
                runCatching { file.readText() }.getOrNull()?.let { return it }
            }
        }
        readFromMediaStore()?.let { return it }
        return appContext.getExternalFilesDir(null)?.let { dir ->
            val file = File(dir, CONFIG_FILE_NAME)
            if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        }
    }

    private fun readFromMediaStore(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cursor = runCatching {
            appContext.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(CONFIG_FILE_NAME),
                null,
            )
        }.getOrNull() ?: return null
        return cursor.use {
            if (!it.moveToFirst()) return@use null
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val itemUri = ContentUris.withAppendedId(collection, id)
            runCatching {
                appContext.contentResolver.openInputStream(itemUri)?.bufferedReader()?.use { reader -> reader.readText() }
            }.getOrNull()
        }
    }

    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL)!!
        set(value) = prefs.edit().putString("base_url", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", DEFAULT_API_KEY)!!
        set(value) = prefs.edit().putString("api_key", value).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!
        set(value) = prefs.edit().putString("model", value).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT)!!
        set(value) = prefs.edit().putString("system_prompt", value).apply()

    /** R25: WebView 远程调试（release 包下用户在设置页显式开启才持久化）。 */
    var devtoolsDebugging: Boolean
        get() = prefs.getBoolean("devtools_debugging", false)
        set(value) = prefs.edit().putBoolean("devtools_debugging", value).apply()

    /** 语音转写（STT）：模型 id，如 whisper-1 / glm-asr / fun-asr；空 = whisper-1 兼容默认。 */
    var transcribeModel: String
        get() = prefs.getString("transcribe_model", "")!!
        set(value) = prefs.edit().putString("transcribe_model", value).apply()

    /** 语音转写端点：缺省跟随 baseUrl（同 host 的 /audio/transcriptions）。 */
    var transcribeBaseUrl: String
        get() = prefs.getString("transcribe_base_url", "")!!
        set(value) = prefs.edit().putString("transcribe_base_url", value).apply()

    /** 语音合成（TTS）：端点 + 模型（OpenAI 兼容 /audio/speech）。 */
    var ttsBaseUrl: String
        get() = prefs.getString("tts_base_url", "")!!
        set(value) = prefs.edit().putString("tts_base_url", value).apply()

    var ttsModel: String
        get() = prefs.getString("tts_model", "")!!
        set(value) = prefs.edit().putString("tts_model", value).apply()

    var ttsVoice: String
        get() = prefs.getString("tts_voice", "")!!
        set(value) = prefs.edit().putString("tts_voice", value).apply()
}

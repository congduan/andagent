package com.dc.andagent.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import com.dc.andagent.BuildConfig
import com.dc.andagent.tools.KvStore
import com.dc.andagent.ui.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R102：把"换签名重装会丢"的数据镜像到外部非私有存储。
 *
 * 背景：签名不一致时系统要求先卸载，`/data/data` 与
 * `/sdcard/Android/data/<pkg>` 全部清空，App 没有机会在卸载时导出。
 * 因此策略是**持续镜像**：启动 / 退到后台 / 用户手动点击时把白名单数据
 * 写到 `/sdcard/Download/andagent/state/`，重装后从那里恢复。
 *
 * 落点优先级：
 * 1. `Download/andagent/state`（公共目录，卸载不删；需要 legacy 存储权限）
 * 2. `<外部私有>/state`（权限未授予时降级，卸载会删，status 里标记 degraded）
 *
 * 明确**不备份**：API Key（本机 `Download/config.json` 已有跨安装机制）、
 * SSH 凭据与 adb 私钥、KV `secure` 命名空间（Android Keystore 绑 uid+签名，
 * 换签名后必然解不开）、Termux prefix（体积大且可重建）。
 */
class ExternalStateStore(
    private val context: Context,
    private val settings: SettingsStore,
) {
    companion object {
        private const val TAG = "ExternalStateStore"
        private const val SCHEMA = 1
        private const val APP_DIR = "andagent"
        private const val STATE_DIR = "state"
        private const val SNAPSHOT_DIR = "snapshot"
        private const val MANIFEST = "manifest.json"
        private const val SETTINGS = "settings.json"
        private const val PREFS = "andagent_external_state"
        /** 单个文件上限：脚本/数据库之外的意外大文件不进入快照。 */
        private const val MAX_FILE_BYTES = 16L * 1024 * 1024
        /** 自动同步节流：手动与 onPause 的同步不受此限制。 */
        private const val AUTO_SYNC_INTERVAL_MS = 60_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "external-state").apply { isDaemon = true }
    }
    private val lock = Any()
    private val queued = AtomicBoolean(false)

    /** 默认开启：用户的诉求就是"别再丢数据"；不可写时自动降级而不是失败。 */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        private set(value) = prefs.edit().putBoolean("enabled", value).apply()

    private var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0L)
        set(value) = prefs.edit().putLong("last_sync_at", value).apply()

    /* ------------------------------ 公共 API ------------------------------ */

    fun setEnabled(value: Boolean): String {
        enabled = value
        if (!value) return "已关闭外部备份：新数据不再镜像到公共目录"
        val result = syncNow()
        return if (result.startsWith("ERROR")) result else "已开启外部备份。$result"
    }

    /** JS 桥：完整状态（路径/可写性/上次同步/快照统计）。 */
    fun statusJson(): String {
        val root = resolveRoot()
        val snapshot = File(root.dir, SNAPSHOT_DIR)
        val manifest = readManifest(snapshot)
        return buildJsonObject {
            put("enabled", enabled)
            put("degraded", root.degraded)
            put("path", root.dir.absolutePath)
            put("writable", root.writable)
            put("permission", hasLegacyWritePermission())
            put("hasSnapshot", manifest != null)
            // 全新安装 + 外部已有快照 = 典型"换签名重装"场景，提示用户恢复。
            put("pendingRestore", manifest != null && !hasLocalData())
            put("lastSyncAt", lastSyncAt)
            put("snapshotAt", manifest?.exportedAt ?: 0L)
            put("files", manifest?.files ?: 0)
            put("bytes", manifest?.bytes ?: 0L)
            put("note", root.note)
            put("excluded", buildJsonArray {
                add(JsonPrimitive("API Key（请用 Download/config.json）"))
                add(JsonPrimitive("SSH 凭据 / adb 私钥"))
                add(JsonPrimitive("KV secure 命名空间（Keystore 绑定签名）"))
                add(JsonPrimitive("Termux prefix（体积大，可重建）"))
            })
        }.toString()
    }

    /** 立即全量镜像。返回可直接展示给用户的单行结果。 */
    fun syncNow(): String {
        if (!enabled) return "ERROR: 外部备份已关闭"
        return synchronized(lock) {
            runCatching {
                val root = resolveRoot()
                if (!root.writable) return@runCatching "ERROR: 外部目录不可写：${root.dir.absolutePath}${root.note}"
                val stateDir = root.dir
                val snapshot = File(stateDir, SNAPSHOT_DIR)
                // 换签名重装后的首次启动：本机是空的，绝不能拿空数据盖掉旧快照。
                if (snapshot.isDirectory && readManifest(snapshot) != null && !hasLocalData()) {
                    return@runCatching "已跳过备份：本机暂无数据，外部快照保持原样，可在下方「从快照恢复」"
                }
                val now = System.currentTimeMillis()
                val staging = File(stateDir, ".staging-$now")
                removeQuietly(staging)

                val entries = buildSnapshot(staging)
                val bytes = entries.sumOf { it.size }
                File(staging, MANIFEST).writeText(
                    buildJsonObject {
                        put("schema", SCHEMA)
                        put("packageName", appContext.packageName)
                        put("versionName", BuildConfig.VERSION_NAME)
                        put("exportedAt", now)
                        put("files", entries.size)
                        put("bytes", bytes)
                        put("items", buildJsonArray {
                            entries.forEach { entry ->
                                add(buildJsonObject {
                                    put("path", entry.path)
                                    put("size", entry.size)
                                    put("sha256", entry.sha256)
                                })
                            }
                        })
                    }.toString(),
                )

                // 先备份旧快照再切换，避免中途失败留下一个半新半旧的目录。
                if (snapshot.exists()) {
                    val trash = File(stateDir, ".trash-$now")
                    removeQuietly(trash)
                    if (!snapshot.renameTo(trash)) removeQuietly(snapshot)
                    else removeQuietly(trash)
                }
                if (!staging.renameTo(snapshot)) {
                    removeQuietly(staging)
                    return@runCatching "ERROR: 快照切换失败：${snapshot.absolutePath}"
                }
                lastSyncAt = now
                "已备份 ${entries.size} 项（${formatBytes(bytes)}）→ ${snapshot.absolutePath}"
            }.getOrElse {
                Log.w(TAG, "sync failed", it)
                "ERROR: 备份失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    /**
     * 从外部快照恢复。恢复前先把当前内部状态另存为 `.undo-<ts>`，
     * 误点也能回退。
     */
    fun restoreNow(): String {
        return synchronized(lock) {
            runCatching {
                val root = resolveRoot()
                val snapshot = File(root.dir, SNAPSHOT_DIR)
                val manifest = readManifest(snapshot)
                    ?: return@runCatching "ERROR: 没有可用快照：${snapshot.absolutePath}"
                if (!snapshot.isDirectory) return@runCatching "ERROR: 快照目录不存在"

                val undoDir = File(root.dir, ".undo-${System.currentTimeMillis()}")
                runCatching { buildSnapshot(undoDir) }

                var files = 0
                snapshot.walkTopDown().forEach { source ->
                    if (!source.isFile) return@forEach
                    val relative = source.relativeTo(snapshot).invariantSeparatorsPath
                    if (relative == MANIFEST) return@forEach
                    if (relative == SETTINGS) {
                        restoreSettings(source)
                        files++
                        return@forEach
                    }
                    if (relative.startsWith("kv/")) {
                        restoreKv(source)
                        files++
                        return@forEach
                    }
                    val target = targetFor(relative) ?: return@forEach
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    files++
                }
                lastSyncAt = System.currentTimeMillis()
                "已从 ${manifest.exportedAt} 的快照恢复 $files 个文件；恢复前的状态保存在 ${undoDir.absolutePath}"
            }.getOrElse {
                Log.w(TAG, "restore failed", it)
                "ERROR: 恢复失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    /**
     * 把当前模型配置写成 `Download/config.json`（含 API Key），
     * 这是项目已有的跨安装配置来源。写入前把旧文件备份为 `.bak`。
     */
    fun writeConfigJson(): String {
        return runCatching {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!hasLegacyWritePermission()) {
                return@runCatching "ERROR: 需要先授予「存储」权限才能在 Download 写配置"
            }
            downloads.mkdirs()
            val target = File(downloads, SettingsStore.CONFIG_FILE_NAME)
            if (target.isFile) {
                runCatching { target.copyTo(File(downloads, "${SettingsStore.CONFIG_FILE_NAME}.bak"), overwrite = true) }
            }
            target.writeText(
                buildJsonObject {
                    put("baseUrl", settings.baseUrl)
                    put("apiKey", settings.apiKey)
                    put("model", settings.model)
                    put("systemPrompt", settings.systemPrompt)
                    put("transcribeModel", settings.transcribeModel)
                    put("transcribeBaseUrl", settings.transcribeBaseUrl)
                    put("ttsBaseUrl", settings.ttsBaseUrl)
                    put("ttsModel", settings.ttsModel)
                    put("ttsVoice", settings.ttsVoice)
                }.toString(),
            )
            "已写入 ${target.absolutePath}（含 API Key，请自行保管）"
        }.getOrElse { "ERROR: 写入配置失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 后台自动同步；同一时刻只排一个任务，[force] 跳过节流。 */
    fun autoSync(force: Boolean = false) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < AUTO_SYNC_INTERVAL_MS) return
        if (!queued.compareAndSet(false, true)) return
        runCatching {
            io.execute {
                try {
                    syncNow()
                } finally {
                    queued.set(false)
                }
            }
        }.onFailure { queued.set(false) }
    }

    fun shutdown() {
        runCatching { io.shutdown() }
    }

    /* ------------------------------ 快照构建 ------------------------------ */

    private data class Entry(val path: String, val size: Long, val sha256: String)

    private data class Root(val dir: File, val writable: Boolean, val degraded: Boolean, val note: String)

    private data class ManifestInfo(val exportedAt: Long, val files: Int, val bytes: Long)

    private fun resolveRoot(): Root {
        val preferred = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$APP_DIR/$STATE_DIR",
        )
        if (hasLegacyWritePermission() && ensureWritable(preferred)) {
            return Root(preferred, true, false, "下载目录下的公共位置，卸载/换签名重装后仍然保留")
        }
        val publicReason = if (hasLegacyWritePermission()) {
            "公共目录不可写，已降级"
        } else {
            "未授予存储权限，已降级"
        }
        val fallback = appContext.getExternalFilesDir(null)?.let { File(it, STATE_DIR) }
        if (fallback != null && ensureWritable(fallback)) {
            return Root(fallback, true, true, "$publicReason；该位置随卸载删除，请授予存储权限")
        }
        val internal = File(appContext.filesDir, STATE_DIR)
        return Root(internal, ensureWritable(internal), true, "$publicReason；当前落在应用私有目录，卸载即丢失")
    }

    /**
     * 本机是否已经有值得备份/会被覆盖的数据。
     *
     * 全新安装（换签名重装）时这里为 false，用来把"空数据覆盖旧快照"这条
     * 最危险的路堵死；用户一旦开始使用（填了模型、建了技能或会话）就恢复
     * 正常镜像。
     */
    private fun hasLocalData(): Boolean = runCatching {
        if (File(appContext.filesDir, "cron.json").isFile) return@runCatching true
        if (File(appContext.filesDir, "widget-library.json").isFile) return@runCatching true
        if (File(appContext.filesDir, "skills").listFiles()?.isNotEmpty() == true) return@runCatching true
        if (appContext.getDatabasePath("probe").parentFile?.listFiles()
                ?.any { it.name.startsWith("andagent_") } == true
        ) return@runCatching true
        val external = appContext.getExternalFilesDir(null)
        if (external != null) {
            if (File(external, "scripts").listFiles()?.isNotEmpty() == true) return@runCatching true
            if (File(external, "webview-local").listFiles()?.isNotEmpty() == true) return@runCatching true
        }
        if (settings.baseUrl.isNotBlank() || settings.model.isNotBlank() || settings.apiKey.isNotBlank()) {
            return@runCatching true
        }
        KvStore(appContext).namespaces().isNotEmpty()
    }.getOrDefault(false)

    private fun ensureWritable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".probe")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    private fun hasLegacyWritePermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildSnapshot(target: File): List<Entry> {
        removeQuietly(target)
        target.mkdirs()
        val entries = mutableListOf<Entry>()

        // 1) filesDir 白名单（其余如 usr/ home/ apt/ adb/ 私钥一律不动）
        val filesDirWhitelist = listOf(
            "cron.json",
            "widget-library.json",
            "widget-data.json",
            "skills",
            "ssh/known_hosts",
        )
        for (relative in filesDirWhitelist) {
            copyTree(
                source = File(appContext.filesDir, relative),
                target = File(target, relative),
                relative = relative,
                entries = entries,
            )
        }

        // 2) SQLite（db_gui 生成的库；WAL/SHM 一并带上）
        val databasesDir = appContext.getDatabasePath("probe").parentFile
        databasesDir?.listFiles()?.forEach { file ->
            if (!file.name.startsWith("andagent_")) return@forEach
            if (!file.isFile) return@forEach
            copyFile(file, File(target, "db/${file.name}"), "db/${file.name}", entries)
        }

        // 3) 外部私有目录里用户会改的内容（换成外部落点后才能真正活下来）
        val externalDir = appContext.getExternalFilesDir(null)
        if (externalDir != null) {
            for (relative in listOf("scripts", "webview-local")) {
                copyTree(
                    source = File(externalDir, relative),
                    target = File(target, "external/$relative"),
                    relative = "external/$relative",
                    entries = entries,
                )
            }
        }

        // 4) 设置（不含 API Key）
        runCatching {
            val out = File(target, SETTINGS)
            out.writeText(
                buildJsonObject {
                    put("schema", SCHEMA)
                    put("baseUrl", settings.baseUrl)
                    put("model", settings.model)
                    put("systemPrompt", settings.systemPrompt)
                    put("transcribeModel", settings.transcribeModel)
                    put("transcribeBaseUrl", settings.transcribeBaseUrl)
                    put("ttsBaseUrl", settings.ttsBaseUrl)
                    put("ttsModel", settings.ttsModel)
                    put("ttsVoice", settings.ttsVoice)
                }.toString(),
            )
            entries += Entry(SETTINGS, out.length(), sha256(out))
        }

        // 5) KV 命名空间（会话历史就在这里；secure 命名空间跳过）
        runCatching {
            val kv = KvStore(appContext)
            for (info in kv.namespaces()) {
                if (info.namespace == KvStore.SECURE_NAMESPACE) continue
                val payload = runCatching { kv.export(info.namespace) }.getOrNull() ?: continue
                val relative = "kv/${info.namespace}.json"
                val out = File(target, relative)
                out.parentFile?.mkdirs()
                out.writeText(payload)
                entries += Entry(relative, out.length(), sha256(out))
            }
        }.onFailure { Log.w(TAG, "kv export skipped", it) }

        return entries
    }

    private fun copyTree(source: File, target: File, relative: String, entries: MutableList<Entry>) {
        if (!source.exists()) return
        if (source.isFile) {
            copyFile(source, target, relative, entries)
            return
        }
        val children = source.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            copyTree(child, File(target, child.name), "$relative/${child.name}", entries)
        }
    }

    private fun copyFile(source: File, target: File, relative: String, entries: MutableList<Entry>) {
        if (!source.isFile) return
        val size = source.length()
        if (size > MAX_FILE_BYTES) {
            Log.i(TAG, "skip oversized file: $relative ($size bytes)")
            return
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        entries += Entry(relative, target.length(), sha256(target))
    }

    private fun targetFor(relative: String): File? = when {
        relative.startsWith("external/") -> {
            val base = appContext.getExternalFilesDir(null) ?: return null
            File(base, relative.removePrefix("external/"))
        }
        relative.startsWith("db/") -> {
            val dir = appContext.getDatabasePath("probe").parentFile ?: return null
            File(dir, relative.removePrefix("db/"))
        }
        relative.contains("..") -> null
        else -> File(appContext.filesDir, relative)
    }

    private fun restoreSettings(source: File) {
        val obj = runCatching { json.parseToJsonElement(source.readText()).jsonObject }.getOrNull() ?: return
        fun text(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        text("baseUrl")?.let { settings.baseUrl = it }
        text("model")?.let { settings.model = it }
        text("systemPrompt")?.let { settings.systemPrompt = it }
        text("transcribeModel")?.let { settings.transcribeModel = it }
        text("transcribeBaseUrl")?.let { settings.transcribeBaseUrl = it }
        text("ttsBaseUrl")?.let { settings.ttsBaseUrl = it }
        text("ttsModel")?.let { settings.ttsModel = it }
        text("ttsVoice")?.let { settings.ttsVoice = it }
    }

    private fun restoreKv(source: File) {
        val kv = runCatching { KvStore(appContext) }.getOrNull() ?: return
        runCatching { kv.importJson(source.readText(), KvStore.IMPORT_MODE_MERGE) }
            .onFailure { Log.w(TAG, "kv import failed: ${source.name}", it) }
    }

    private fun readManifest(snapshot: File): ManifestInfo? {
        val file = File(snapshot, MANIFEST)
        if (!file.isFile) return null
        return runCatching {
            val obj: JsonObject = json.parseToJsonElement(file.readText()).jsonObject
            val pkg = obj["packageName"]?.jsonPrimitive?.contentOrNull
            if (pkg != null && pkg != appContext.packageName) return null
            ManifestInfo(
                exportedAt = obj["exportedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                files = (obj["files"]?.jsonPrimitive?.contentOrNull ?: "0").toIntOrNull() ?: 0,
                bytes = obj["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.getOrNull()
    }

    private fun sha256(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun removeQuietly(dir: File) {
        runCatching { if (dir.exists()) dir.deleteRecursively() }
    }
}

package com.dc.andagent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** #12: F-Droid index search + APK download + system-installer install. */
val STORE_TOOLS = listOf("fdroid_search", "fdroid_download", "app_install")

private const val FDROID_INDEX = "https://f-droid.org/repo/index-v1.json"
private const val FDROID_REPO = "https://f-droid.org/repo/"

private object FdroidIndex {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    @Volatile private var cached: JsonObject? = null
    @Volatile private var loadedAt = 0L

    fun get(): JsonObject {
        val now = System.currentTimeMillis()
        cached?.let { if (now - loadedAt < 3_600_000L) return it }
        val request = Request.Builder().url(FDROID_INDEX).header("User-Agent", "AndAgent/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("F-Droid 索引请求失败：HTTP ${response.code}")
            val body = response.body?.string() ?: error("F-Droid 索引为空")
            val parsed = Json.parseToJsonElement(body).jsonObject
            cached = parsed
            loadedAt = now
            return parsed
        }
    }

    fun apk(url: String): ByteArray {
        val request = Request.Builder().url(url).header("User-Agent", "AndAgent/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK 下载失败：HTTP ${response.code}")
            return response.body?.bytes() ?: error("APK 内容为空")
        }
    }

    /** Localized name/summary with en-US fallback. */
    fun text(app: JsonObject, field: String): String {
        val localized = app["localized"]?.jsonObject ?: return app[field]?.jsonPrimitive?.contentOrNull ?: ""
        for (tag in listOf("zh-CN", "zh", "en-US", "en")) {
            localized[tag]?.jsonObject?.get(field)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return localized.values.firstNotNullOfOrNull { it.jsonObject[field]?.jsonPrimitive?.contentOrNull } ?: ""
    }

    fun versions(index: JsonObject, pkg: String): JsonArray =
        index["packages"]?.jsonObject?.get(pkg)?.jsonArray ?: JsonArray(emptyList())
}

class StoreTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "fdroid_search" -> "在 F-Droid 官方仓库索引中按关键词搜索开源应用（包名、名称、简介、最新版本）。"
        "fdroid_download" -> "从 F-Droid 仓库下载指定应用的最新 APK 到公共下载目录并校验 SHA-256。"
        "app_install" -> "打开系统安装器安装指定 APK，由用户在系统弹窗中确认。"
        else -> "应用商店工具"
    }

    override val parameters: JsonObject = when (name) {
        "fdroid_search" -> schemaOf(
            "keyword" to strParam("应用名/包名/简介关键词"),
            "limit" to intParam("返回数量，默认 15，上限 50"),
            required = listOf("keyword"),
        )
        "fdroid_download" -> schemaOf("package" to strParam("F-Droid 包名"), required = listOf("package"))
        "app_install" -> schemaOf(
            "path" to strParam("APK 绝对路径"),
            "confirm" to boolParam("必须设为 true 才会安装"),
            required = listOf("path", "confirm"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("app_install", "fdroid_download")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "fdroid_search" -> search(args)
                "fdroid_download" -> download(args)
                "app_install" -> install(args)
                else -> "ERROR: 未知应用商店工具 $name"
            }
        }.getOrElse { "ERROR: $name 失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun search(args: JsonObject): String {
        val keyword = args.strArg("keyword") ?: return "ERROR: 缺少 keyword"
        val limit = (args.intArg("limit") ?: 15).coerceIn(1, 50)
        val index = FdroidIndex.get()
        val apps = index["apps"]?.jsonObject ?: return "ERROR: F-Droid 索引格式异常"
        val needle = keyword.lowercase()
        var matches = 0
        val rows = buildJsonArray {
            for ((pkg, raw) in apps) {
                if (matches >= limit) break
                val app = raw.jsonObject
                val appName = FdroidIndex.text(app, "name")
                val summary = FdroidIndex.text(app, "summary")
                if (needle !in pkg.lowercase() && needle !in appName.lowercase() && needle !in summary.lowercase()) continue
                matches++
                val versions = FdroidIndex.versions(index, pkg)
                val latest = versions.maxByOrNull { it.jsonObject["versionCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }
                add(buildJsonObject {
                    put("package", pkg)
                    put("name", appName)
                    put("summary", summary.take(200))
                    put("license", app["license"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("versions", versions.size)
                    put("latestVersion", latest?.jsonObject?.get("versionName")?.jsonPrimitive?.contentOrNull ?: "")
                    put("apkName", latest?.jsonObject?.get("apkName")?.jsonPrimitive?.contentOrNull ?: "")
                })
            }
        }
        return buildJsonObject { put("keyword", keyword); put("count", rows.size); put("apps", rows) }.toString()
    }

    private fun download(args: JsonObject): String {
        val pkg = args.strArg("package") ?: return "ERROR: 缺少 package"
        val index = FdroidIndex.get()
        if (index["apps"]?.jsonObject?.containsKey(pkg) != true) return "ERROR: F-Droid 仓库中没有 $pkg"
        val versions = FdroidIndex.versions(index, pkg)
        val latest = versions.maxByOrNull { it.jsonObject["versionCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L }
            ?: return "ERROR: $pkg 没有可用版本"
        val info = latest.jsonObject
        val apkName = info["apkName"]?.jsonPrimitive?.contentOrNull ?: return "ERROR: 版本缺少 apkName"
        if (!apkName.matches(Regex("[A-Za-z0-9_.-]+\\.apk"))) return "ERROR: 非法的 APK 文件名"
        val bytes = FdroidIndex.apk(FDROID_REPO + apkName)
        val expected = info["hash"]?.jsonPrimitive?.contentOrNull
        if (!expected.isNullOrBlank()) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, true)) return "ERROR: APK 校验失败（期望 $expected，实际 $actual）"
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgent")
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建下载目录"
        val target = File(dir, apkName)
        target.writeBytes(bytes)
        return buildJsonObject {
            put("package", pkg)
            put("apkName", apkName)
            put("path", target.absolutePath)
            put("bytes", bytes.size)
            put("sha256Verified", !expected.isNullOrBlank())
            put("version", info["versionName"]?.jsonPrimitive?.contentOrNull ?: "")
        }.toString()
    }

    private suspend fun install(args: JsonObject): String {
        if (args.boolArg("confirm") != true) return "ERROR: 安装应用需要 confirm=true"
        val path = args.strArg("path") ?: return "ERROR: 缺少 path"
        val apk = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return "ERROR: 路径无效"
        if (!apk.isFile || !apk.name.endsWith(".apk", true)) return "ERROR: 不是有效的 APK 文件：$path"
        // R144b：Android 8+ 未知来源安装授权检查——未授权时 ACTION_VIEW 会静默失败。
        // 引导用户到本应用的「安装未知应用」设置页（一次性授权）。
        val canInstall = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        if (!canInstall) {
            // R144 修4：startActivity 成功/失败分开回报——不再吞掉异常后固定谎报「已打开设置页」。
            val opened = runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            if (opened.isSuccess) {
                return "ERROR: 缺少「安装未知应用」授权——已打开设置页，请开启「允许来自此来源的应用」后重新 app_install（一次性授权）"
            }
            val ex = opened.exceptionOrNull()
            val summary = ex?.message ?: ex?.javaClass?.simpleName ?: "未知异常"
            return "ERROR: 缺少『安装未知应用』授权，且自动打开设置页失败（$summary）。请手动：设置 → 应用 → AndAgent → 安装未知应用 → 允许，然后重试 app_install"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            "OPENED: 已打开系统安装器，请手动确认安装 ${apk.name}"
        }.getOrElse { "ERROR: 无法打开安装器：${it.message}" }
    }
}

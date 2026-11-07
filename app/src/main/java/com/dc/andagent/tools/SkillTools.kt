package com.dc.andagent.tools

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.dc.andagent.script.QuickJsEngine
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

internal data class SkillManifest(val name: String, val description: String, val prompt: String, val allow: Set<String>, val body: String)

internal object SkillFormat {
    private val validName = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,63}")
    fun parse(text: String): SkillManifest {
        val normalized = text.replace("\r\n", "\n")
        require(normalized.startsWith("---\n")) { "SKILL.md 缺少 YAML frontmatter" }
        val end = normalized.indexOf("\n---\n", 4)
        require(end >= 0) { "SKILL.md frontmatter 未闭合" }
        val lines = normalized.substring(4, end).lines()
        val fields = mutableMapOf<String, String>()
        var multi: String? = null
        for (line in lines) {
            if (line.startsWith("  ") && multi != null) {
                fields[multi] = fields.getValue(multi) + line.removePrefix("  ") + "\n"
                continue
            }
            val at = line.indexOf(':')
            if (at < 1) continue
            val key = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            if (key !in setOf("name", "description", "tools", "prompt")) continue
            multi = if (value == "|") key else null
            fields[key] = if (multi != null) "" else value.trim('"', '\'')
        }
        val name = fields["name"].orEmpty()
        require(validName.matches(name)) { "技能名只能包含字母、数字、下划线和连字符" }
        val allow = fields["tools"].orEmpty().removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.toSet()
        require(allow.all { validName.matches(it) }) { "无效工具白名单" }
        return SkillManifest(name, fields["description"].orEmpty(), fields["prompt"].orEmpty(), allow, normalized.substring(end + 5).trim())
    }
}

/** GitHub 安装来源：[file] 非空表示指向单个文件，直接按字节抓取。 */
internal data class SkillRepo(val owner: String, val name: String, val branch: String?, val path: String?, val file: String?) {
    val url: String get() = "https://github.com/$owner/$name"
    /** 归并同一仓库的不同写法（含 tree/blob 子路径）。 */
    val key: String get() = "github:$owner/$name".lowercase()
}

internal object SkillSource {
    private val github = Regex("^https?://(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(?:/(tree|blob)/([^/]+)(?:/(.*))?)?$")

    fun parseGitHub(source: String): SkillRepo? {
        val url = source.trim().substringBefore('#').substringBefore('?').trimEnd('/')
        val match = github.find(url) ?: return null
        val owner = match.groupValues[1]
        val name = match.groupValues[2].removeSuffix(".git")
        if (owner.isEmpty() || name.isEmpty()) return null
        val kind = match.groupValues[3]
        val branch = match.groupValues[4].ifBlank { null }
        val path = match.groupValues[5].trim('/').ifBlank { null }
        val raw = if (kind == "blob") "https://raw.githubusercontent.com/$owner/$name/${branch.orEmpty()}/${path.orEmpty()}" else null
        return SkillRepo(owner, name, branch, path, raw)
    }

    /** 用 owner/name 归并 GitHub 地址；其它来源按去掉尾斜杠与 .git 的文本比较。 */
    fun key(source: String): String =
        parseGitHub(source)?.key ?: source.trim().trimEnd('/').removeSuffix(".git").lowercase()
}

class SkillRegistry(private val context: Context, private val tools: MutableMap<String, Tool>, private val engine: QuickJsEngine) {
    private val root = File(context.filesDir, "skills").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("skills", Context.MODE_PRIVATE)
    private val installed = linkedMapOf<String, Pair<SkillManifest, File>>()
    private val dynamic = mutableSetOf<String>()
    private val http = OkHttpClient()

    @Synchronized fun reload(): String {
        dynamic.forEach { tools.remove(it) }; dynamic.clear(); installed.clear()
        val userRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "andagent/skills")
        for (dir in root.listFiles().orEmpty().filter { it.isDirectory } + userRoot.listFiles().orEmpty().filter { it.isDirectory }) {
            runCatching {
                val file = File(dir, "SKILL.md")
                require(file.isFile && file.length() <= 100_000)
                val manifest = SkillFormat.parse(file.readText())
                if (installed.containsKey(manifest.name)) return@runCatching
                installed[manifest.name] = manifest to dir
                if (!enabled(manifest.name)) return@runCatching
                val main = File(dir, "main.js")
                if (main.isFile && main.length() <= 100_000) {
                    val source = main.readText()
                    val meta = Json.parseToJsonElement(engine.evalSkill(source, null, null, emptySet())).jsonArray
                    for (item in meta) {
                        val entry = item.jsonObject
                        val local = entry["name"]?.jsonPrimitive?.content.orEmpty()
                        if (!Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}").matches(local)) continue
                        val toolName = "skill_${manifest.name.replace('-', '_')}_$local"
                        if (tools.containsKey(toolName)) continue
                        tools[toolName] = SkillDynamicTool(toolName, entry["description"]?.jsonPrimitive?.content.orEmpty(), entry["params"] ?: jsonSchema(), source, local, manifest.allow, engine)
                        dynamic.add(toolName)
                    }
                }
            }
        }
        return list()
    }
    fun enabled(name: String) = prefs.getBoolean("enabled_$name", true)
    @Synchronized fun list(): String = JsonArray(installed.map { (name, pair) -> buildJsonObject {
        put("name", name); put("description", pair.first.description); put("enabled", enabled(name))
        put("toolCount", dynamic.count { it.startsWith("skill_${name.replace('-', '_')}_") })
        put("source", pair.second.absolutePath)
    } }).toString()
    @Synchronized fun prompt(): String = installed.filter { enabled(it.key) }.values.joinToString("\n\n") { (m, _) -> "## Skill: ${m.name}\n${m.prompt}\n${m.body}" }
    @Synchronized fun info(name: String): String {
        val (m, dir) = installed[name] ?: return "ERROR: 技能不存在"
        return buildJsonObject { put("name", m.name); put("description", m.description); put("prompt", m.prompt); put("body", m.body); put("tools", JsonArray(m.allow.map(::JsonPrimitive))); put("source", dir.path); put("enabled", enabled(name)) }.toString()
    }
    @Synchronized fun enable(name: String, on: Boolean): String {
        require(installed.containsKey(name)) { "技能不存在" }
        prefs.edit().putBoolean("enabled_$name", on).apply()
        reload(); return list()
    }
    @Synchronized fun delete(name: String, confirm: Boolean): String {
        require(confirm) { "删除技能需 confirm=true" }
        val dir = installed[name]?.second ?: error("技能不存在")
        require(dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "用户侧技能是只读的" }
        require(dir.deleteRecursively()) { "删除失败" }
        reload(); return list()
    }
    fun install(source: String): String {
        val repo = SkillSource.parseGitHub(source)
        return when {
            repo == null -> "已安装技能：${installBytes(readSource(source), source)}"
            repo.file != null -> "已安装技能：${installBytes(readSource(repo.file), source)}"
            else -> installFromRepo(repo)
        }
    }

    /** 抓取待安装字节：HTTP(S) 直链 / content URI / 本地文件或目录（目录会打包成 ZIP）。 */
    private fun readSource(source: String): ByteArray = when {
        source.startsWith("http://") || source.startsWith("https://") -> readUrl(source)
        source.startsWith("content:") -> context.contentResolver.openInputStream(Uri.parse(source))?.use { it.readBytes() } ?: error("URI 不可读")
        else -> {
            val file = File(source.removePrefix("file://"))
            if (file.isDirectory) {
                val md = File(file, "SKILL.md")
                require(md.isFile) { "目录缺少 SKILL.md" }
                val temp = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(temp).use { zip ->
                    listOf("SKILL.md", "main.js").forEach { name ->
                        val child = File(file, name)
                        if (child.isFile) { zip.putNextEntry(java.util.zip.ZipEntry(name)); child.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                    }
                }
                temp.toByteArray()
            } else file.readBytes()
        }
    }

    private fun readUrl(url: String, accept: String? = null): ByteArray = http.newCall(
        Request.Builder().url(url).apply { if (accept != null) header("Accept", accept) }.build()
    ).execute().use { response ->
        require(response.isSuccessful) { "HTTP ${response.code}" }
        response.body?.byteStream()?.use { it.readNBytes(2_000_001) } ?: error("空响应")
    }

    /** 单文件 / ZIP 技能包安装：SKILL.md 或打包了 SKILL.md 的 ZIP。 */
    private fun installBytes(bytes: ByteArray, source: String): String {
        require(bytes.size <= 2_000_000) { "技能包超过 2MB" }
        val staging = File(root, ".stage_${System.nanoTime()}").apply { mkdirs() }
        try {
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.substringAfter("/", entry.name)
                        val target = File(staging, relative).canonicalFile
                        require(target.path.startsWith(staging.canonicalPath + File.separator)) { "ZIP 路径越界" }
                        if (!entry.isDirectory && (relative == "SKILL.md" || relative == "main.js")) target.outputStream().use { zip.copyTo(it) }
                    }
                }
            } else File(staging, "SKILL.md").writeBytes(bytes)
            return commit(staging, source)
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    /** 校验暂存目录里的 SKILL.md，落盘后重新扫描；返回技能名。 */
    private fun commit(staging: File, source: String): String {
        val manifest = SkillFormat.parse(File(staging, "SKILL.md").readText())
        val dest = File(root, manifest.name)
        require(!dest.exists()) { "技能已存在：${manifest.name}" }
        require(staging.renameTo(dest)) { "安装失败" }
        prefs.edit().putString("source_${manifest.name}", source).apply()
        reload()
        return manifest.name
    }

    /**
     * 从 GitHub 仓库安装：拉取默认分支文件树，找出所有 SKILL.md 所在目录，
     * 逐个抓取 SKILL.md（以及可选的 main.js）落盘。一次最多 8 个，已安装的跳过。
     */
    private fun installFromRepo(repo: SkillRepo): String {
        val branch = repo.branch ?: defaultBranch(repo)
        val prefix = repo.path
        val dirs = repoTree(repo, branch).filter { it.endsWith("SKILL.md", ignoreCase = true) }
            .map { it.substringBeforeLast('/', "") }
            .filter { prefix == null || it == prefix || it.startsWith("$prefix/") }
            .distinct()
            .sortedWith(compareBy({ it.count { ch -> ch == '/' } }, { it }))
            .take(8)
        require(dirs.isNotEmpty()) { "仓库中没有找到 SKILL.md" }
        val ok = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (dir in dirs) {
            runCatching { installRepoDir(repo, branch, dir) }.onSuccess { name ->
                if (name == null) skipped += dir.ifEmpty { repo.name } else ok += name
            }.onFailure { failed += "${dir.ifEmpty { repo.name }}：${it.message ?: it.javaClass.simpleName}" }
        }
        val message = buildString {
            if (ok.isNotEmpty()) append("已安装技能：").append(ok.joinToString("、"))
            if (skipped.isNotEmpty()) { if (isNotEmpty()) append("；"); append("已存在：").append(skipped.joinToString("、")) }
            if (failed.isNotEmpty()) { if (isNotEmpty()) append("；"); append("失败：").append(failed.joinToString("；")) }
            if (isEmpty()) append("没有可安装的技能")
        }
        if (ok.isEmpty() && failed.isNotEmpty()) throw IllegalStateException(message)
        return message
    }

    /** 安装仓库里的单个技能目录；已存在时返回 null。 */
    private fun installRepoDir(repo: SkillRepo, branch: String, dir: String): String? {
        val base = "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/" + if (dir.isEmpty()) "" else "$dir/"
        val staging = File(root, ".stage_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(staging, "SKILL.md").writeBytes(readUrl(base + "SKILL.md"))
            runCatching { readUrl(base + "main.js") }.getOrNull()
                ?.takeIf { it.size <= 100_000 }?.let { File(staging, "main.js").writeBytes(it) }
            val name = SkillFormat.parse(File(staging, "SKILL.md").readText()).name
            if (installed.containsKey(name) || File(root, name).exists()) return null
            return commit(staging, repo.url)
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    private fun defaultBranch(repo: SkillRepo): String {
        val key = "branch_${repo.owner}/${repo.name}"
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val branch = runCatching {
            val body = readUrl("https://api.github.com/repos/${repo.owner}/${repo.name}", "application/vnd.github+json")
            Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["default_branch"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "main"
        prefs.edit().putString(key, branch).apply()
        return branch
    }

    /** 仓库文件清单；API 不可用（限流/无网）时退化为「根目录 SKILL.md」单点尝试。 */
    private fun repoTree(repo: SkillRepo, branch: String): List<String> = runCatching {
        val body = readUrl("https://api.github.com/repos/${repo.owner}/${repo.name}/git/trees/$branch?recursive=1", "application/vnd.github+json")
        Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["tree"]?.jsonArray.orEmpty()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "blob" }
            .mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
    }.getOrElse { listOf("SKILL.md") }

    private fun installedSourceKeys(): Set<String> =
        installed.keys.mapNotNull { prefs.getString("source_$it", null)?.let { s -> SkillSource.key(s) } }.toSet()

    fun search(query: String): String {
        val results = mutableListOf<JsonElement>()
        val installedSources = installedSourceKeys()
        fun isInstalled(source: String?, name: String?): Boolean =
            (name != null && installed.containsKey(name)) || (source != null && SkillSource.key(source) in installedSources)

        val github = "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode("$query topic:claude-skills", "UTF-8") + "&per_page=20"
        runCatching {
            val body = String(readUrl(github, "application/vnd.github+json"), Charsets.UTF_8)
            Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().forEach { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull
                val repo = obj["html_url"]?.jsonPrimitive?.contentOrNull
                results.add(buildJsonObject {
                    put("name", name?.let(::JsonPrimitive) ?: JsonNull)
                    put("repo", repo?.let(::JsonPrimitive) ?: JsonNull)
                    put("description", obj["description"] ?: JsonNull)
                    put("source", repo?.let(::JsonPrimitive) ?: JsonNull)
                    put("installed", isInstalled(repo, name))
                })
            }
        }
        prefs.getString("index_url", null)?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                val body = String(readUrl(url), Charsets.UTF_8)
                Json.parseToJsonElement(body).jsonArray.filter { it.toString().contains(query, true) }.take(20).forEach { item ->
                    val obj = runCatching { item.jsonObject }.getOrNull() ?: return@forEach
                    val name = (obj["name"] ?: obj["id"])?.jsonPrimitive?.contentOrNull
                    val source = (obj["source"] ?: obj["url"] ?: obj["repo"])?.jsonPrimitive?.contentOrNull
                    results.add(buildJsonObject {
                        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
                        put("description", obj["description"] ?: JsonNull)
                        put("repo", source?.let(::JsonPrimitive) ?: JsonNull)
                        put("source", source?.let(::JsonPrimitive) ?: JsonNull)
                        put("installed", isInstalled(source, name))
                    })
                }
            }
        }
        return JsonArray(results).toString()
    }
    fun setIndex(url: String) { require(url.startsWith("https://")); prefs.edit().putString("index_url", url).apply() }
}

private class SkillDynamicTool(override val name: String, override val description: String, override val parameters: JsonElement,
    private val source: String, private val local: String, private val allow: Set<String>, private val engine: QuickJsEngine) : Tool {
    override suspend fun execute(args: JsonObject): String = engine.evalSkill(source, local, args, allow)
}

class SkillTool(private val registry: SkillRegistry, override val name: String) : Tool {
    override val description = when (name) {
        "skill_install" -> "安装技能：支持 GitHub 仓库地址（自动查找仓库内的 SKILL.md）、SKILL.md 或 ZIP 直链、本地路径、file_pick URI。"
        "skill_list" -> "列出已安装技能。"
        "skill_reload" -> "重新扫描技能目录并注册动态工具。"
        "skill_enable" -> "启用或停用技能。"
        "skill_delete" -> "删除内部技能，需 confirm=true。"
        "skill_search" -> "搜索 GitHub 技能仓库和自定义索引，返回可直接用于 skill_install 的 source。"
        "skill_index_set" -> "设置自定义技能 JSON 索引 URL。"
        else -> "查看技能详情。"
    }
    override val parameters = when (name) {
        "skill_install" -> schemaOf("source" to strParam("URL、路径或 content URI"), required = listOf("source"))
        "skill_enable" -> schemaOf("name" to strParam("技能名"), "enabled" to boolParam("启用"), required = listOf("name", "enabled"))
        "skill_delete" -> schemaOf("name" to strParam("技能名"), "confirm" to boolParam("确认删除"), required = listOf("name"))
        "skill_search" -> schemaOf("query" to strParam("关键词"), required = listOf("query"))
        "skill_index_set" -> schemaOf("url" to strParam("HTTPS JSON 索引 URL"), required = listOf("url"))
        "skill_info" -> schemaOf("name" to strParam("技能名"), required = listOf("name"))
        else -> schemaOf()
    }
    override suspend fun execute(args: JsonObject): String = runCatching { when (name) {
        "skill_install" -> registry.install(args.strArg("source") ?: error("缺少 source"))
        "skill_list" -> registry.list()
        "skill_reload" -> registry.reload()
        "skill_enable" -> registry.enable(args.strArg("name") ?: error("缺少 name"), args.boolArg("enabled") == true)
        "skill_delete" -> registry.delete(args.strArg("name") ?: error("缺少 name"), args.boolArg("confirm") == true)
        "skill_search" -> registry.search(args.strArg("query") ?: error("缺少 query"))
        "skill_index_set" -> { registry.setIndex(args.strArg("url") ?: error("缺少 url")); "OK" }
        else -> registry.info(args.strArg("name") ?: error("缺少 name"))
    } }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}

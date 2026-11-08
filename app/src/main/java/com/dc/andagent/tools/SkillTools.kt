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

internal data class SkillManifest(
    val name: String,
    val description: String,
    val prompt: String,
    val allow: Set<String>,
    val body: String,
    /** 技能版本（frontmatter `version`，缺省为空：旧技能没有版本概念）。 */
    val version: String = "",
    val author: String = "",
)

internal object SkillFormat {
    private val validName = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,63}")
    private val validVersion = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,31}")
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
            if (key !in setOf("name", "description", "tools", "prompt", "version", "author")) continue
            multi = if (value == "|") key else null
            fields[key] = if (multi != null) "" else value.trim('"', '\'')
        }
        val name = fields["name"].orEmpty()
        require(validName.matches(name)) { "技能名只能包含字母、数字、下划线和连字符" }
        val allow = fields["tools"].orEmpty().removePrefix("[").removeSuffix("]")
            .split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }.toSet()
        require(allow.all { validName.matches(it) }) { "无效工具白名单" }
        val version = fields["version"].orEmpty().trim().takeIf { validVersion.matches(it) }.orEmpty()
        return SkillManifest(
            name = name,
            description = fields["description"].orEmpty(),
            prompt = fields["prompt"].orEmpty(),
            allow = allow,
            body = normalized.substring(end + 5).trim(),
            version = version,
            author = fields["author"].orEmpty().take(64),
        )
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

/** 官方索引：仓库根目录的 skills-index.json（远端）+ 同内容 assets 备份（离线）。 */
private const val OFFICIAL_INDEX_URL = "https://raw.githubusercontent.com/congduan/andagent/main/skills-index.json"
private const val OFFICIAL_INDEX_ASSET = "skills-index.json"
private const val OFFICIAL_INDEX_TTL_MS = 6 * 60 * 60 * 1000L

class SkillRegistry(private val context: Context, private val tools: MutableMap<String, Tool>, private val engine: QuickJsEngine) {
    private val root = File(context.filesDir, "skills").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("skills", Context.MODE_PRIVATE)
    private val installed = linkedMapOf<String, Pair<SkillManifest, File>>()
    private val dynamic = mutableSetOf<String>()
    private val http = OkHttpClient()

    /** 官方索引（随包内置 + 远端缓存），以及由它派生的评分/最新版本元数据。 */
    private var indexEntries: List<SkillIndexEntry> = emptyList()

    @Synchronized fun reload(): String {
        dynamic.forEach { tools.remove(it) }; dynamic.clear(); installed.clear()
        indexEntries = loadIndexEntries()
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

    private fun userRating(name: String): Int = prefs.getInt("rating_$name", 0)

    private fun installedSource(name: String, dir: File): String =
        prefs.getString("source_$name", null)?.takeIf { it.isNotBlank() } ?: dir.absolutePath

    /** 索引里同源（优先）或同名（兜底）的元数据。 */
    private fun indexFor(name: String, source: String?): SkillIndexEntry? {
        val key = source?.takeIf { it.isNotBlank() }?.let { SkillSource.key(it) }
        return indexEntries.firstOrNull { key != null && it.key == key } ?: indexEntries.firstOrNull { it.name == name }
    }

    @Synchronized fun list(): String = JsonArray(installed.map { (name, pair) ->
        val (manifest, dir) = pair
        val source = installedSource(name, dir)
        val index = indexFor(name, source)
        buildJsonObject {
            put("name", name)
            put("description", manifest.description)
            put("enabled", enabled(name))
            put("toolCount", dynamic.count { it.startsWith("skill_${name.replace('-', '_')}_") })
            put("source", source)
            put("version", manifest.version)
            put("author", manifest.author)
            put("userRating", userRating(name))
            put("rating", index?.rating ?: 0.0)
            put("ratingCount", index?.ratingCount ?: 0)
            put("latestVersion", index?.version)
            put("updateAvailable", manifest.version.isNotBlank() && index?.version != null && manifest.version != index.version)
        }
    }).toString()

    @Synchronized fun prompt(): String = installed.filter { enabled(it.key) }.values.joinToString("\n\n") { (m, _) -> "## Skill: ${m.name}\n${m.prompt}\n${m.body}" }

    @Synchronized fun info(name: String): String {
        val (m, dir) = installed[name] ?: return "ERROR: 技能不存在"
        val source = installedSource(name, dir)
        val index = indexFor(name, source)
        return buildJsonObject {
            put("name", m.name); put("description", m.description); put("prompt", m.prompt); put("body", m.body)
            put("tools", JsonArray(m.allow.map(::JsonPrimitive))); put("source", source); put("enabled", enabled(name))
            put("version", m.version); put("author", m.author)
            put("userRating", userRating(name))
            put("rating", index?.rating ?: 0.0); put("ratingCount", index?.ratingCount ?: 0)
            put("latestVersion", index?.version)
            put("updateAvailable", m.version.isNotBlank() && index?.version != null && m.version != index.version)
        }.toString()
    }

    @Synchronized fun enable(name: String, on: Boolean): String {
        require(installed.containsKey(name)) { "技能不存在" }
        prefs.edit().putBoolean("enabled_$name", on).apply()
        reload(); return list()
    }

    /** 本地评分（0 清除），立即写入并回传技能详情。 */
    @Synchronized fun rate(name: String, stars: Int): String {
        require(installed.containsKey(name)) { "技能不存在" }
        require(stars in 0..5) { "评分范围 0-5" }
        prefs.edit().putInt("rating_$name", stars).apply()
        return info(name)
    }

    @Synchronized fun delete(name: String, confirm: Boolean): String {
        require(confirm) { "删除技能需 confirm=true" }
        val dir = installed[name]?.second ?: error("技能不存在")
        require(dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "用户侧技能是只读的" }
        require(dir.deleteRecursively()) { "删除失败" }
        reload(); return list()
    }

    /**
     * 一键分享：生成 `andagent://skill?source=...` 深链 + 技能 ZIP 包。
     * 深链让另一台 andagent 一键安装；ZIP 则覆盖离线/本地技能。
     */
    fun share(name: String): String {
        val (manifest, dir) = installed[name] ?: error("技能不存在")
        val source = installedSource(name, dir)
        // 只有 HTTP(S) 来源才能生成跨设备可用的深链；本地路径靠附带的 ZIP 包。
        val deepLink = if (source.startsWith("http://") || source.startsWith("https://")) {
            "andagent://skill?source=" + java.net.URLEncoder.encode(source, "UTF-8")
        } else ""
        val file = runCatching { exportArchive(manifest, dir) }.getOrNull()
        val text = buildString {
            append("安装技能「").append(manifest.name).append('」')
            if (manifest.version.isNotBlank()) append(" v").append(manifest.version)
            append("\n来源：").append(source)
            if (deepLink.isNotEmpty()) append("\n一键安装：").append(deepLink)
        }
        return buildJsonObject {
            put("name", manifest.name); put("version", manifest.version); put("source", source)
            put("deepLink", deepLink); put("text", text)
            if (file != null) put("file", file.absolutePath)
        }.toString()
    }

    private fun exportArchive(manifest: SkillManifest, dir: File): File {
        val target = File(context.cacheDir, "shared_skills").apply { mkdirs() }
        val version = manifest.version.ifBlank { "0" }
        val out = File(target, "${manifest.name}-$version.zip")
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            listOf("SKILL.md", "main.js").forEach { child ->
                val file = File(dir, child)
                if (!file.isFile || file.length() > 100_000) return@forEach
                zip.putNextEntry(java.util.zip.ZipEntry(child))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }
    fun install(source: String): String {
        val repo = SkillSource.parseGitHub(source)
        return when {
            repo == null -> "已安装技能：${installBytes(readSource(source), source)}"
            repo.file != null -> "已安装技能：${installBytes(readSource(repo.file), source)}"
            else -> installFromRepo(repo)
        }
    }

    /**
     * 按记录的来源原地更新（仅 GitHub 来源）。
     * 先下载到 staging 并校验技能名，再备份旧目录、原子替换；失败时回滚。
     */
    @Synchronized fun update(name: String): String {
        val (current, dir) = installed[name] ?: error("技能不存在")
        require(dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "用户侧技能是只读的" }
        val source = prefs.getString("source_$name", null)?.takeIf { it.isNotBlank() }
            ?: error("没有来源记录，请重新安装覆盖")
        val repo = SkillSource.parseGitHub(source) ?: error("仅支持 GitHub 来源自动更新，请重新安装覆盖")
        val branch = repo.branch ?: defaultBranch(repo)
        val base = when {
            repo.file != null -> repo.file.substringBeforeLast('/') + "/"
            else -> "https://raw.githubusercontent.com/${repo.owner}/${repo.name}/$branch/" +
                (repo.path?.let { "$it/" } ?: "")
        }
        val staging = File(root, ".stage_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(staging, "SKILL.md").writeBytes(readUrl(base + "SKILL.md"))
            runCatching { readUrl(base + "main.js") }.getOrNull()
                ?.takeIf { it.size <= 100_000 }?.let { File(staging, "main.js").writeBytes(it) }
            val manifest = SkillFormat.parse(File(staging, "SKILL.md").readText())
            require(manifest.name == name) { "来源技能名已变为 ${manifest.name}，请删除后重新安装" }
            val backup = File(root, ".backup_${System.nanoTime()}")
            require(dir.renameTo(backup)) { "更新失败：无法移走旧版本" }
            if (!staging.renameTo(dir)) {
                backup.renameTo(dir)
                error("更新失败：无法写入新版本")
            }
            backup.deleteRecursively()
            prefs.edit().putString("source_$name", source).apply()
            reload()
            val version = manifest.version.ifBlank { "无版本号" }
            return "已更新技能：$name（${current.version.ifBlank { "旧版" }} → $version）"
        } finally {
            if (staging.exists()) staging.deleteRecursively()
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
            // 记录到具体目录，update() 才能回放到同一个 SKILL.md（monorepo 必需）。
            val sourceUrl = if (dir.isEmpty()) repo.url else "${repo.url}/tree/$branch/$dir"
            return commit(staging, sourceUrl)
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

    /** 内置索引（assets）+ 上次拉取的官方索引缓存；离线也能搜到官方条目。 */
    private fun loadIndexEntries(): List<SkillIndexEntry> {
        val cached = prefs.getString("official_cache", null)?.takeIf { it.isNotBlank() }
        val raw = cached ?: runCatching {
            context.assets.open(OFFICIAL_INDEX_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        return SkillIndex.parse(raw)
    }

    /** 远端官方索引 6 小时刷新一次；失败静默沿用缓存，不阻塞搜索。 */
    private fun refreshOfficialIndex() {
        val now = System.currentTimeMillis()
        val fresh = now - prefs.getLong("official_at", 0L) < OFFICIAL_INDEX_TTL_MS
        if (fresh && prefs.getString("official_cache", null) != null) return
        runCatching {
            val body = String(readUrl(OFFICIAL_INDEX_URL), Charsets.UTF_8)
            val entries = SkillIndex.parse(body)
            require(entries.isNotEmpty()) { "官方索引为空" }
            prefs.edit().putString("official_cache", body).putLong("official_at", now).apply()
            indexEntries = entries
        }
    }

    fun search(query: String): String {
        refreshOfficialIndex()
        val results = mutableListOf<JsonElement>()
        // 条目级去重（保留同一仓库里的不同技能）与仓库级去重（避免 GitHub 兜底重复整仓）。
        val seenEntries = mutableSetOf<String>()
        val seenRepos = mutableSetOf<String>()
        val installedSources = installedSourceKeys()
        fun isInstalled(source: String?, name: String?): Boolean =
            (name != null && installed.containsKey(name)) || (source != null && SkillSource.key(source) in installedSources)

        fun addResult(entry: SkillIndexEntry, fallbackName: String? = null) {
            if (!seenEntries.add(entry.identity)) return
            seenRepos.add(entry.key)
            val name = entry.name ?: fallbackName
            results.add(buildJsonObject {
                put("name", name?.let(::JsonPrimitive) ?: JsonNull)
                put("description", entry.description?.let(::JsonPrimitive) ?: JsonNull)
                put("repo", JsonPrimitive(entry.source))
                put("source", JsonPrimitive(entry.source))
                put("version", entry.version?.let(::JsonPrimitive) ?: JsonNull)
                put("rating", JsonPrimitive(entry.rating))
                put("ratingCount", JsonPrimitive(entry.ratingCount))
                put("author", entry.author?.let(::JsonPrimitive) ?: JsonNull)
                put("installed", isInstalled(entry.source, name))
            })
        }

        // 1) 官方索引 + 自定义索引：先本地，保证离线可用且排序稳定。
        indexEntries.filter { it.matches(query) }.take(20).forEach { addResult(it) }
        prefs.getString("index_url", null)?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                val body = String(readUrl(url), Charsets.UTF_8)
                SkillIndex.parse(body).filter { it.matches(query) }.take(20).forEach { addResult(it) }
            }
        }

        // 2) GitHub 仓库搜索兜底（无网/限流时静默跳过）。
        if (query.isNotBlank()) {
            val github = "https://api.github.com/search/repositories?q=" +
                java.net.URLEncoder.encode("$query topic:claude-skills", "UTF-8") + "&per_page=20"
            runCatching {
                val body = String(readUrl(github, "application/vnd.github+json"), Charsets.UTF_8)
                Json.parseToJsonElement(body).jsonObject["items"]?.jsonArray.orEmpty().forEach { item ->
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                    val repo = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val repoKey = SkillSource.key(repo)
                    if (repoKey in seenRepos || !seenEntries.add(repo.lowercase())) return@forEach
                    val index = indexEntries.firstOrNull { it.key == repoKey }
                    results.add(buildJsonObject {
                        put("name", name?.let(::JsonPrimitive) ?: JsonNull)
                        put("repo", JsonPrimitive(repo))
                        put("description", obj["description"] ?: JsonNull)
                        put("source", JsonPrimitive(repo))
                        put("version", index?.version?.let(::JsonPrimitive) ?: JsonNull)
                        put("rating", JsonPrimitive(index?.rating ?: 0.0))
                        put("ratingCount", JsonPrimitive(index?.ratingCount ?: 0))
                        put("author", index?.author?.let(::JsonPrimitive) ?: JsonNull)
                        put("installed", isInstalled(repo, name))
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
    /** 覆盖安装会替换技能代码，执行前需用户确认。 */
    override val destructive: Boolean = name == "skill_update"
    override val description = when (name) {
        "skill_install" -> "安装技能：支持 GitHub 仓库地址（自动查找仓库内的 SKILL.md）、SKILL.md 或 ZIP 直链、本地路径、file_pick URI。"
        "skill_list" -> "列出已安装技能。"
        "skill_reload" -> "重新扫描技能目录并注册动态工具。"
        "skill_enable" -> "启用或停用技能。"
        "skill_delete" -> "删除内部技能，需 confirm=true。"
        "skill_search" -> "搜索官方索引、GitHub 技能仓库和自定义索引，返回版本、评分与可直接用于 skill_install 的 source。"
        "skill_index_set" -> "设置自定义技能 JSON 索引 URL。"
        "skill_rate" -> "给技能打分（0-5，0 表示清除），评分保存在本机。"
        "skill_share" -> "生成技能的一键分享深链（andagent://skill?source=...）并导出 ZIP 包路径。"
        "skill_update" -> "按记录的 GitHub 来源更新已安装技能（先暂存校验，再原子替换，失败回滚）。"
        else -> "查看技能详情。"
    }
    override val parameters = when (name) {
        "skill_install" -> schemaOf("source" to strParam("URL、路径或 content URI"), required = listOf("source"))
        "skill_enable" -> schemaOf("name" to strParam("技能名"), "enabled" to boolParam("启用"), required = listOf("name", "enabled"))
        "skill_delete" -> schemaOf("name" to strParam("技能名"), "confirm" to boolParam("确认删除"), required = listOf("name"))
        "skill_search" -> schemaOf("query" to strParam("关键词"), required = listOf("query"))
        "skill_index_set" -> schemaOf("url" to strParam("HTTPS JSON 索引 URL"), required = listOf("url"))
        "skill_rate" -> schemaOf("name" to strParam("技能名"), "stars" to intParam("0-5 星"), required = listOf("name", "stars"))
        "skill_share" -> schemaOf("name" to strParam("技能名"), required = listOf("name"))
        "skill_update" -> schemaOf("name" to strParam("技能名"), required = listOf("name"))
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
        "skill_rate" -> registry.rate(args.strArg("name") ?: error("缺少 name"), args.intArg("stars") ?: error("缺少 stars"))
        "skill_share" -> registry.share(args.strArg("name") ?: error("缺少 name"))
        "skill_update" -> registry.update(args.strArg("name") ?: error("缺少 name"))
        else -> registry.info(args.strArg("name") ?: error("缺少 name"))
    } }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}

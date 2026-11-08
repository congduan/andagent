package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.script.QuickJsEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * 自我扩展（P1）：agent 生成的新工具，持久化于 filesDir/selfext/<name>/：
 *   tool.json — 元数据（name/description/parameters JSON Schema/allow/version/reason）
 *   main.js   — exports.tools = [{ name: "run", handler: function(args){...} }]
 * 执行走 QuickJsEngine.evalSkill 的 handler 路径，受声明时的工具白名单约束；
 * 注册复用 SkillRegistry 的 dynamic 模式，schema 经 AgentBridge.extensionTools() 对模型可见。
 */
class SelfExtManager(private val context: Context, private val tools: MutableMap<String, Tool>, private val engine: QuickJsEngine) {

    internal data class Manifest(
        val name: String,
        val description: String,
        val parameters: JsonObject,
        val allow: List<String>,
        val version: Int,
        val created: String,
        val reason: String,
    )

    private val root = File(context.filesDir, "selfext").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("selfext", Context.MODE_PRIVATE)
    private val dynamic = mutableSetOf<String>()
    private val installed = linkedMapOf<String, Pair<Manifest, File>>()

    @Synchronized fun reload(): String {
        dynamic.forEach { tools.remove(it) }; dynamic.clear(); installed.clear()
        for (dir in root.listFiles().orEmpty().filter { it.isDirectory }) runCatching {
            val meta = File(dir, "tool.json")
            require(meta.isFile && meta.length() <= 20_000) { "tool.json 缺失或过大" }
            val manifest = parseManifest(meta.readText())
            if (installed.containsKey(manifest.name)) return@runCatching
            val main = File(dir, "main.js")
            require(main.isFile && main.length() <= 100_000) { "main.js 缺失或超过 100KB" }
            installed[manifest.name] = manifest to dir
            if (!enabled(manifest.name)) return@runCatching
            tools[manifest.name] = DynamicSelfExtTool(manifest.name, manifest.description, manifest.parameters, main.readText(), manifest.allow, engine, this)
            dynamic.add(manifest.name)
        }
        return list()
    }

    fun enabled(name: String) = prefs.getBoolean("enabled_$name", true)

    @Synchronized fun create(name: String, description: String, code: String, allowArg: String?, parametersJson: String?, reason: String?): String {
        require(VALID_NAME.matches(name)) { "工具名必须是 my_ 前缀的小写字母/数字/下划线（如 my_quote_cn2en）" }
        require(name !in META_NAMES) { "不能使用保留名 $name" }
        require(!installed.containsKey(name) && !File(root, name).exists()) { "工具已存在：$name（修改请用 my_tool_update）" }
        require(installed.size < MAX_TOOLS) { "自建工具已达上限 $MAX_TOOLS，请先删除不用的（my_tool_delete）" }
        require(description.isNotBlank()) { "缺少 description" }
        val allow = parseAllow(allowArg)
        val parameters = parseParameters(parametersJson)
        validateCode(code)
        val dir = File(root, name)
        val staging = File(root, ".stage_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(staging, "main.js").writeText(code, Charsets.UTF_8)
            File(staging, "tool.json").writeText(manifestJson(name, description, allow, parameters, 1, reason), Charsets.UTF_8)
            require(staging.renameTo(dir)) { "写入失败" }
        } finally { if (staging.exists()) staging.deleteRecursively() }
        reload()
        return info(name)
    }

    @Synchronized fun update(name: String, code: String?, description: String?, allowArg: String?, parametersJson: String?, confirm: Boolean): String {
        require(confirm) { "更新自建工具需 confirm=true" }
        val (current, dir) = installed[name] ?: error("自建工具不存在：$name")
        val newCode = code?.takeIf { it.isNotBlank() } ?: File(dir, "main.js").readText()
        val newDescription = description?.takeIf { it.isNotBlank() } ?: current.description
        val allow = if (allowArg != null) parseAllow(allowArg) else current.allow
        val parameters = if (parametersJson != null) parseParameters(parametersJson) else current.parameters
        validateCode(newCode)
        File(dir, "main.js").writeText(newCode, Charsets.UTF_8)
        File(dir, "tool.json").writeText(manifestJson(name, newDescription, allow, parameters, current.version + 1, current.reason), Charsets.UTF_8)
        reload()
        return info(name)
    }

    @Synchronized fun delete(name: String, confirm: Boolean): String {
        require(confirm) { "删除自建工具需 confirm=true" }
        val dir = installed[name]?.second ?: error("自建工具不存在：$name")
        require(dir.deleteRecursively()) { "删除失败" }
        reload()
        return list()
    }

    @Synchronized fun enable(name: String, on: Boolean): String {
        require(installed.containsKey(name)) { "自建工具不存在：$name" }
        prefs.edit().putBoolean("enabled_$name", on).apply()
        reload(); return list()
    }

    /** 用测试参数运行 handler（不要求工具处于启用状态）。 */
    fun test(name: String, argsJson: String?): String {
        val (manifest, dir) = installed[name] ?: error("自建工具不存在：$name")
        val args = if (argsJson.isNullOrBlank()) JsonObject(emptyMap()) else runCatching {
            Json.parseToJsonElement(argsJson).jsonObject
        }.getOrElse { error("args_json 不是 JSON 对象：${it.message}") }
        return engine.evalSkill(File(dir, "main.js").readText(), HANDLER, args, manifest.allow.toSet())
    }

    @Synchronized fun readCode(name: String): String {
        val (_, dir) = installed[name] ?: error("自建工具不存在：$name")
        return File(dir, "main.js").readText()
    }

    @Synchronized fun list(): String = JsonArray(installed.map { (name, pair) ->
        val (m, dir) = pair
        buildJsonObject {
            put("name", name); put("description", m.description)
            put("allow", JsonArray(m.allow.map(::JsonPrimitive)))
            put("version", m.version); put("enabled", enabled(name))
            put("created", m.created); put("reason", m.reason)
            put("path", dir.absolutePath)
        }
    }).toString()

    @Synchronized fun info(name: String): String {
        val (m, dir) = installed[name] ?: error("自建工具不存在：$name")
        return buildJsonObject {
            put("name", m.name); put("description", m.description)
            put("parameters", m.parameters)
            put("allow", JsonArray(m.allow.map(::JsonPrimitive)))
            put("version", m.version); put("enabled", enabled(name))
            put("created", m.created); put("reason", m.reason)
            put("path", dir.absolutePath)
        }.toString()
    }

    /* --------------------------- internals --------------------------- */

    private fun manifestJson(name: String, description: String, allow: List<String>, parameters: JsonObject, version: Int, reason: String?): String =
        buildJsonObject {
            put("name", name); put("description", description.take(500))
            put("parameters", parameters)
            put("allow", JsonArray(allow.map(::JsonPrimitive)))
            put("version", version); put("created", Instant.now().toString())
            put("reason", reason.orEmpty().take(500))
        }.toString()

    private fun parseManifest(text: String): Manifest {
        val obj = Json.parseToJsonElement(text).jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(VALID_NAME.matches(name) && name !in META_NAMES) { "无效的工具名 $name" }
        val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(description.isNotBlank()) { "缺少 description" }
        val parameters = (obj["parameters"] as? JsonObject) ?: jsonSchema()
        val allow = obj["allow"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val version = obj["version"]?.jsonPrimitive?.intOrNull ?: 1
        val created = obj["created"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return Manifest(name, description, parameters, allow, version, created, reason)
    }

    /** 白名单：JSON 数组或逗号分隔；只能引用当前已注册且未被禁止的工具。 */
    private fun parseAllow(raw: String?): List<String> {
        val items: List<String> = if (raw.isNullOrBlank()) emptyList() else runCatching {
            Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
        }.getOrElse { raw.split(',').map { it.trim().trim('"', '\'') } }
        val cleaned = items.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val forbidden = cleaned.filter { it in FORBIDDEN_EXACT || FORBIDDEN_PREFIXES.any { prefix -> it.startsWith(prefix) } }
        require(forbidden.isEmpty()) { "白名单禁止包含：${forbidden.joinToString()}" }
        val unknown = cleaned.filter { it !in tools }
        require(unknown.isEmpty()) { "未知工具：${unknown.joinToString()}（白名单只能引用当前已注册的工具）" }
        return cleaned
    }

    private fun parseParameters(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return jsonSchema()
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrElse { error("parameters_json 不是合法 JSON：${it.message}") }
        return (element as? JsonObject) ?: error("parameters_json 必须是 JSON Schema 对象")
    }

    /** 语法检查 + 导出格式检查（顺带拦截 handler 外的顶层工具调用：meta 提取时空白名单）。 */
    private fun validateCode(code: String) {
        require(code.length <= 100_000) { "代码超过 100KB" }
        val check = engine.check(code)
        if (!check.contains("\"valid\":true")) error("JS 语法检查失败：$check")
        val meta = engine.evalSkill(code, null, null, emptySet())
        val arr = runCatching { Json.parseToJsonElement(meta).jsonArray }.getOrElse {
            error("代码必须以 exports.tools = [{ name: \"run\", handler: function(args){...} }] 形式导出：$meta")
        }
        require(arr.size == 1) { "每个自建工具只导出一个 handler（当前 ${arr.size} 个）" }
        val local = arr[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(local == HANDLER) { "导出的工具名必须是 \"$HANDLER\"（当前 \"$local\"）" }
    }

    companion object {
        const val HANDLER = "run"
        const val MAX_TOOLS = 20
        val VALID_NAME = Regex("my_[a-z][a-z0-9_]{0,47}")
        val META_NAMES = setOf(
            "my_tool_create", "my_tool_list", "my_tool_read", "my_tool_update",
            "my_tool_delete", "my_tool_test", "my_tool_enable",
        )
        private val FORBIDDEN_EXACT = setOf("js_eval", "json_fix")
        private val FORBIDDEN_PREFIXES = listOf("my_", "skill_", "mcp_", "cron_", "live_", "script_")
    }
}

/** 已注册的自建工具：执行 = evalSkill 的 handler 路径（声明时工具白名单）。 */
private class DynamicSelfExtTool(
    override val name: String,
    override val description: String,
    override val parameters: JsonElement,
    private val source: String,
    private val allow: List<String>,
    private val engine: QuickJsEngine,
    private val manager: SelfExtManager,
) : Tool {
    override suspend fun execute(args: JsonObject): String =
        if (!manager.enabled(name)) "ERROR: 自建工具已停用：$name"
        else engine.evalSkill(source, SelfExtManager.HANDLER, args, allow.toSet())
}

class SelfExtTool(private val registry: SelfExtManager, override val name: String) : Tool {
    override val description = when (name) {
        "my_tool_create" -> "创建并注册新的自建工具（自我扩展）。code 必须是 exports.tools = [{ name: \"run\", handler: function(args){...} }]；handler 内用 andagent.tool(name, JSON.stringify(args)) 调用 allow 白名单里的现有工具，andagent.kv/fs/db 可存取状态，return 结果（可 JSON 序列化）。顶层代码保持纯净、只在 handler 写逻辑。parameters_json 是参数 JSON Schema；allow 是可调用的现有工具白名单（逗号分隔或 JSON 数组，禁止 skill_/mcp_/cron_/live_/script_/my_ 前缀）。创建成功后下一轮即可直接调用该 my_* 工具，重启后仍在。"
        "my_tool_list" -> "列出全部自建工具（my_*）及元数据。"
        "my_tool_read" -> "读取自建工具的 JS 源码（修改前先读）。"
        "my_tool_update" -> "修改自建工具的代码/描述/白名单/参数 Schema（只传要改的字段），需 confirm=true。"
        "my_tool_delete" -> "删除自建工具，需 confirm=true。"
        "my_tool_test" -> "用测试参数运行自建工具并返回 handler 输出，不改变注册状态。"
        "my_tool_enable" -> "启用或停用自建工具。"
        else -> "自建工具管理。"
    }
    override val parameters = when (name) {
        "my_tool_create" -> schemaOf(
            "name" to strParam("工具名，my_ 前缀小写标识符"),
            "description" to strParam("给模型看的工具描述（何时用、返回什么）"),
            "code" to strParam("JS 源码，exports.tools = [{ name: \"run\", handler: function(args){...} }]"),
            "allow" to strParam("可调用的现有工具白名单，逗号分隔或 JSON 数组"),
            "parameters_json" to strParam("参数 JSON Schema 对象字符串"),
            "reason" to strParam("为什么现有工具不够、要新建这个工具"),
            required = listOf("name", "description", "code"),
        )
        "my_tool_read" -> schemaOf("name" to strParam("工具名"), required = listOf("name"))
        "my_tool_update" -> schemaOf(
            "name" to strParam("工具名"),
            "code" to strParam("新 JS 源码"),
            "description" to strParam("新描述"),
            "allow" to strParam("新白名单"),
            "parameters_json" to strParam("新参数 JSON Schema"),
            "confirm" to boolParam("确认更新"),
            required = listOf("name", "confirm"),
        )
        "my_tool_delete" -> schemaOf("name" to strParam("工具名"), "confirm" to boolParam("确认删除"), required = listOf("name", "confirm"))
        "my_tool_test" -> schemaOf("name" to strParam("工具名"), "args_json" to strParam("测试参数 JSON 对象"), required = listOf("name"))
        "my_tool_enable" -> schemaOf("name" to strParam("工具名"), "enabled" to boolParam("启用"), required = listOf("name", "enabled"))
        else -> schemaOf()
    }
    override suspend fun execute(args: JsonObject): String = runCatching { when (name) {
        "my_tool_create" -> registry.create(
            args.strArg("name") ?: error("缺少 name"),
            args.strArg("description") ?: error("缺少 description"),
            args.strArg("code") ?: error("缺少 code"),
            args.strArg("allow"),
            args.strArg("parameters_json"),
            args.strArg("reason"),
        )
        "my_tool_list" -> registry.list()
        "my_tool_read" -> registry.readCode(args.strArg("name") ?: error("缺少 name"))
        "my_tool_update" -> registry.update(
            args.strArg("name") ?: error("缺少 name"),
            args.strArg("code"),
            args.strArg("description"),
            args.strArg("allow"),
            args.strArg("parameters_json"),
            args.boolArg("confirm") == true,
        )
        "my_tool_delete" -> registry.delete(args.strArg("name") ?: error("缺少 name"), args.boolArg("confirm") == true)
        "my_tool_test" -> registry.test(args.strArg("name") ?: error("缺少 name"), args.strArg("args_json"))
        "my_tool_enable" -> registry.enable(args.strArg("name") ?: error("缺少 name"), args.boolArg("enabled") == true)
        else -> "ERROR: 未知元工具 $name"
    } }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
}

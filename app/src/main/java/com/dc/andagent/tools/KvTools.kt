package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Eight KV tools backed by [KvStore]. */
val KV_TOOLS = listOf(
    "kv_get", "kv_set", "kv_delete", "kv_keys", "kv_namespaces", "kv_clear",
    "kv_export", "kv_import",
)

/** R96: secure 命名空间默认拒绝时的一句式错误文案。 */
const val KV_SECURE_DENIED_ERROR =
    "ERROR: secure 命名空间不开放给工具调用（敏感配置存储；读写走原生设置页）"

/**
 * R96: secure 命名空间访问门禁（纯策略判定，不依赖 Context/KvStore，便于 JVM 单测）。
 * 返回拒绝时的错误文案，null 表示放行。命中 secure 时仅 kv_get/kv_set 的「单键」
 * 操作可能被 [secureAccess] 放行（前端设置页经 AgentBridge 注入「仅 engines」策略）；
 * 批量写入/删除/列举/清空/导出 secure 一律拒绝。kv_import 由 KvStore.parseBackup
 * 拒绝 secure，kv_namespaces 单独隐藏 secure，二者不走本门禁。
 */
fun kvSecureDenied(
    toolName: String,
    args: JsonObject,
    secureAccess: (String?) -> Boolean,
): String? {
    val namespace = args.strArg("namespace")
    val key = args.strArg("key")
    val targetsSecure = when (toolName) {
        "kv_export" -> namespace == KvStore.SECURE_NAMESPACE || args.boolArg("include_secure") == true
        "kv_get", "kv_set", "kv_delete", "kv_keys", "kv_clear" ->
            namespace == KvStore.SECURE_NAMESPACE
        else -> false
    }
    if (!targetsSecure) return null
    val singleKeyReadWrite = toolName in setOf("kv_get", "kv_set") &&
        key != null && args["items_json"] == null
    return if (singleKeyReadWrite && secureAccess(key)) null else KV_SECURE_DENIED_ERROR
}

class KvTool(
    private val context: Context,
    override val name: String,
    // R96: 注入的 secure 访问策略（key -> 是否放行）。默认恒 false = 全拒绝；
    // 模型/QuickJS 经 execute 走此默认策略，前端桥经 executeWithSecureAccess 覆盖。
    private val secureAccess: (String?) -> Boolean = { _ -> false },
) : Tool {
    // R96 便捷构造：allowSecure=true 全量放行 secure，仅供 JVM 测试/受信任内部使用。
    constructor(context: Context, name: String, allowSecure: Boolean) :
        this(context, name, { _ -> allowSecure })

    private val store = KvStore(context, KeystoreCipher())

    override val description: String = when (name) {
        "kv_get" -> "读取 KV 键值（namespace 默认 default），返回值与过期时间；不存在时返回 found=false。"
        "kv_set" -> "写入 KV 键值。value_json 为任意 JSON 值字符串；ttl_ms 为可选过期毫秒数；items_json 可传对象批量写入。"
        "kv_delete" -> "删除 KV 键；键不存在时返回错误。"
        "kv_keys" -> "列出命名空间下的键名及剩余 TTL 秒数，可选 prefix 前缀过滤。"
        "kv_namespaces" -> "列出所有 KV 命名空间及其键数量。"
        "kv_clear" -> "清空整个命名空间的所有键，必须 confirm=true。"
        "kv_export" -> "按命名空间导出 KV 备份 JSON（省略 namespace 或传 all 导出全部，all 永不包含 secure 敏感命名空间；全部导出上限 512KB）。只读操作。"
        "kv_import" -> "导入 kv_export 输出的 JSON 备份。merge（默认）同名键覆盖、其余保留；replace 先清空目标命名空间，必须 confirm=true。"
        else -> "KV 存储工具"
    }

    override val parameters: JsonObject = when (name) {
        "kv_get" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名，仅字母数字._-，最长 64"),
            required = listOf("key"),
        )
        "kv_set" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名，仅字母数字._-，最长 64"),
            "value_json" to strParam("任意 JSON 值字符串，如 \"text\"、42、true、{\"a\":1}、[1,2]"),
            "ttl_ms" to intParam("可选：过期毫秒数，正整数；省略表示永久"),
            "items_json" to strParam("可选：{\"key\":值} JSON 对象，用于批量写入；与 key/value_json 二选一"),
            required = emptyList(),
        )
        "kv_delete" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "key" to strParam("键名"),
            required = listOf("key"),
        )
        "kv_keys" -> schemaOf(
            "namespace" to strParam("命名空间，默认 default"),
            "prefix" to strParam("可选：键名前缀过滤"),
            required = emptyList(),
        )
        "kv_namespaces" -> schemaOf()
        "kv_clear" -> schemaOf(
            "namespace" to strParam("要清空的命名空间"),
            "confirm" to boolParam("必须设为 true 才会清空"),
            required = listOf("namespace"),
        )
        "kv_export" -> schemaOf(
            "namespace" to strParam("命名空间；省略或 all 导出全部（all 永不包含 secure）"),
            "include_expired" to boolParam("是否包含已过期键，默认 false"),
            "include_secure" to boolParam("仅当显式导出 secure 命名空间时需设为 true，默认 false"),
            required = emptyList(),
        )
        "kv_import" -> schemaOf(
            "data_json" to strParam("kv_export 输出的 JSON 字符串"),
            "mode" to strParam("merge（默认，同名覆盖）或 replace（先清空目标命名空间）"),
            "confirm" to boolParam("mode=replace 时必须为 true"),
            required = listOf("data_json"),
        )
        else -> schemaOf()
    }

    // kv_import 仅在 replace 时具破坏性，但 destructive 是静态标记，这里取保守口径：
    // replace 需要 confirm=true（工具内校验），merge 也允许覆盖同名键。
    override val destructive: Boolean = name == "kv_clear" || name == "kv_import"

    // R96: 模型/QuickJS 通道走实例默认策略（拒绝一切 secure）。
    override suspend fun execute(args: JsonObject): String = dispatch(args, secureAccess)

    /**
     * R96: 前端设置页（AgentBridge.kvCall）专用通道——用调用方策略覆盖实例默认策略，
     * 仅放行 secure/engines 单键读写；其余 secure 操作仍返回 KV_SECURE_DENIED_ERROR。
     */
    suspend fun executeWithSecureAccess(args: JsonObject, policy: (String?) -> Boolean): String =
        dispatch(args, policy)

    private fun dispatch(args: JsonObject, policy: (String?) -> Boolean): String {
        kvSecureDenied(name, args, policy)?.let { return it }
        return runCatching {
            when (name) {
                "kv_get" -> get(args)
                "kv_set" -> set(args)
                "kv_delete" -> delete(args)
                "kv_keys" -> keys(args)
                "kv_namespaces" -> namespaces()
                "kv_clear" -> clear(args)
                "kv_export" -> export(args)
                "kv_import" -> importBackup(args)
                else -> "ERROR: 未知 KV 工具 $name"
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun get(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val key = args.strArg("key") ?: return "ERROR: 缺少 key"
        val entry = store.read(namespace, key)
            ?: return buildJsonObject {
                put("found", false)
                put("namespace", namespace)
                put("key", key)
                put("message", "键不存在或已过期")
            }.toString()
        return buildJsonObject {
            put("found", true)
            put("namespace", namespace)
            put("key", key)
            put("value", entry.value)
            put("expiresAt", entry.expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    private fun set(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val ttlRaw = args["ttl_ms"]?.let { (it as? JsonPrimitive)?.content }
        val ttlMs = ttlRaw?.let {
            it.toLongOrNull() ?: return "ERROR: ttl_ms 必须是整数毫秒"
        }
        KvStore.ttlError(ttlMs)?.let { return "ERROR: $it" }

        val itemsRaw = args.strArg("items_json")
        val key = args.strArg("key")
        val valueRaw = args.strArg("value_json")
        if (itemsRaw != null) {
            if (key != null || valueRaw != null) return "ERROR: items_json 与 key/value_json 不能同时使用"
            val items = runCatching { Json.parseToJsonElement(itemsRaw) as? JsonObject }
                .getOrNull() ?: return "ERROR: items_json 必须是 JSON 对象"
            if (items.isEmpty()) return "ERROR: items_json 不能为空对象"
            val expiry = store.setAll(namespace, items, ttlMs)
            return buildJsonObject {
                put("ok", true)
                put("namespace", namespace)
                put("count", items.size)
                put("expiresAt", expiry?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString()
        }
        if (key == null) return "ERROR: 缺少 key"
        if (valueRaw == null) return "ERROR: 缺少 value_json"
        val value = runCatching { Json.parseToJsonElement(valueRaw) }
            .getOrElse { return "ERROR: value_json 不是合法 JSON：${it.message ?: "解析失败"}" }
        val expiry = store.set(namespace, key, value, ttlMs)
        return buildJsonObject {
            put("ok", true)
            put("namespace", namespace)
            put("key", key)
            put("expiresAt", expiry?.let { JsonPrimitive(it) } ?: JsonNull)
        }.toString()
    }

    private fun delete(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val key = args.strArg("key") ?: return "ERROR: 缺少 key"
        if (!store.delete(namespace, key)) return "ERROR: 键不存在：$namespace:$key"
        return buildJsonObject {
            put("deleted", true)
            put("namespace", namespace)
            put("key", key)
        }.toString()
    }

    private fun keys(args: JsonObject): String {
        val namespace = KvStore.namespaceOrDefault(args.strArg("namespace"))
        val prefix = args.strArg("prefix")
        val items = store.keys(namespace, prefix)
        return buildJsonObject {
            put("namespace", namespace)
            put("count", items.size)
            put("keys", buildJsonArray {
                for (item in items) add(buildJsonObject {
                    put("key", item.key)
                    put("expiresAt", item.expiresAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("ttlRemainingSeconds", item.ttlRemainingSeconds?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
        }.toString()
    }

    private fun namespaces(): String {
        // R96: secure 为系统敏感命名空间，不向工具/模型暴露其存在（实现取简：直接隐藏）。
        val items = store.namespaces().filter { it.namespace != KvStore.SECURE_NAMESPACE }
        return buildJsonObject {
            put("count", items.size)
            put("namespaces", buildJsonArray {
                for (item in items) add(buildJsonObject {
                    put("namespace", item.namespace)
                    put("keys", item.keys)
                })
            })
        }.toString()
    }

    private fun clear(args: JsonObject): String {
        KvStore.clearConfirmError(args.boolArg("confirm"))?.let { return it }
        val namespace = args.strArg("namespace") ?: return "ERROR: 缺少 namespace"
        val removed = store.clear(namespace)
        return buildJsonObject {
            put("cleared", true)
            put("namespace", namespace)
            put("removed", removed)
        }.toString()
    }

    /** Read-only: returns the backup envelope built by [KvStore.export]. */
    private fun export(args: JsonObject): String =
        store.export(
            namespace = args.strArg("namespace"),
            includeExpired = args.boolArg("include_expired") == true,
            includeSecure = args.boolArg("include_secure") == true,
        )

    private fun importBackup(args: JsonObject): String {
        val data = args.strArg("data_json") ?: return "ERROR: 缺少 data_json"
        val mode = KvStore.normalizeImportMode(args.strArg("mode"))
        KvStore.importModeError(mode)?.let { return it }
        KvStore.importConfirmError(mode, args.boolArg("confirm"))?.let { return it }
        val result = store.importJson(data, mode, args.boolArg("confirm"))
        return buildJsonObject {
            put("ok", true)
            put("mode", mode)
            put("namespaces", result.namespaces)
            put("imported", result.imported)
            put("skipped", result.skipped)
        }.toString()
    }
}

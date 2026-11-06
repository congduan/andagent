package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** 只含元数据的凭据视图：可安全展示给界面 / agent，永远不含 secret。 */
data class SshCredentialMeta(
    val nickname: String,
    val host: String,
    val port: Int,
    val user: String,
    val authType: String,
)

/** 完整凭据（含解密后的 secret）；只在 [SshTool] 内部使用。 */
data class SshCredential(
    val meta: SshCredentialMeta,
    val secret: String,
)

/**
 * R91.5: SSH 凭据库（凭据永不过模型）。
 *
 * 密码 / 私钥只由原生界面录入，落盘前用 Android Keystore 里的
 * AES-256-GCM 密钥加密（[KeystoreCipher]，`setUserAuthenticationRequired=false`
 * ——不打断 agent 流程，且密钥不可导出）。文件是
 * `filesDir/ssh/credentials.enc`，格式：
 *
 * ```json
 * {"version":1,"entries":{"<nickname>":{"host":"…","port":22,"user":"…",
 *  "authType":"password|key","secretCipher":"<base64>","iv":"<base64>"}}}
 * ```
 *
 * 加密与文件系统都可注入，便于在纯 JVM 上验证「密文里没有明文」。
 * 解密失败 / 文件损坏一律降级为「凭据不存在」，绝不抛异常到工具层。
 */
class SshCredentialStore internal constructor(
    private val dir: File,
    private val cipher: KvCipher,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "ssh"),
        KeystoreCipher(),
    )

    /** 只读元数据列表（按 nickname 排序），不触碰 secret。 */
    @Synchronized
    fun list(): List<SshCredentialMeta> =
        readEntries().mapNotNull { (nickname, entry) -> metaOf(nickname, entry) }
            .sortedBy { it.nickname }

    /** 元数据 JSON（供 bridge / 摘要卡片）；永不包含 secret。 */
    fun listJson(): String = buildJsonArray {
        list().forEach { meta ->
            add(buildJsonObject {
                put("nickname", meta.nickname)
                put("host", meta.host)
                put("port", meta.port)
                put("user", meta.user)
                put("authType", meta.authType)
            })
        }
    }.toString()

    /** 解密读取；凭据不存在或密文不可读时返回 null。 */
    @Synchronized
    fun get(nickname: String): SshCredential? {
        if (!isValidNickname(nickname)) return null
        val entry = readEntries()[nickname] ?: return null
        val meta = metaOf(nickname, entry) ?: return null
        val secret = decryptSecret(entry) ?: return null
        if (secret.isEmpty()) return null
        return SshCredential(meta, secret)
    }

    /**
     * 写入（覆盖同名）凭据并返回确认信息，**不含明文**。
     * 加密先于落盘：Keystore 失败时抛异常且不产生半截文件。
     */
    @Synchronized
    fun put(nickname: String, host: String, port: Int, user: String, authType: String, secret: String): String {
        require(isValidNickname(nickname)) { "nickname 不能包含空白且最长 32 字符" }
        require(host.isNotBlank() && host.none { it.isWhitespace() }) { "host 格式无效" }
        require(port in 1..65535) { "port 必须在 1..65535" }
        require(user.isNotBlank() && user.none { it.isWhitespace() }) { "user 格式无效" }
        require(authType == "password" || authType == "key") { "authType 只能是 password 或 key" }
        require(secret.isNotBlank()) { "密码 / 私钥不能为空" }
        require(secret.length <= MAX_SECRET_CHARS) { "密码 / 私钥过长（上限 $MAX_SECRET_CHARS 字符）" }

        val (iv, cipherText) = encryptSecret(secret)
        val entries = readEntries()
        entries[nickname] = buildJsonObject {
            put("host", host)
            put("port", port)
            put("user", user)
            put("authType", authType)
            put("secretCipher", cipherText)
            put("iv", iv)
        }
        writeEntries(entries)
        val kind = if (authType == "key") "私钥" else "密码"
        return "已保存 SSH 凭据「$nickname」：$user@$host:$port（$kind 认证）。" +
            "密码/私钥已用 Android Keystore 加密写入 ssh/credentials.enc，不会进入对话，也无法从文件中读出明文。"
    }

    @Synchronized
    fun delete(nickname: String): Boolean {
        if (!isValidNickname(nickname)) return false
        val entries = readEntries()
        if (entries.remove(nickname) == null) return false
        writeEntries(entries)
        return true
    }

    /** 全清，返回删除条数。 */
    @Synchronized
    fun wipeAll(): Int {
        val entries = readEntries()
        val count = entries.size
        if (file.exists() && !file.delete()) throw IllegalStateException("无法删除 ssh/credentials.enc")
        return count
    }

    /* ----------------------------- internals ----------------------------- */

    private val file: File get() = File(dir, FILE_NAME)

    private fun readEntries(): LinkedHashMap<String, JsonObject> {
        val f = file
        if (!f.isFile) return LinkedHashMap()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            ?: return LinkedHashMap()
        if (text.isBlank()) return LinkedHashMap()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val entries = root?.get("entries") as? JsonObject
        if (entries == null) {
            // 损坏文件不静默覆盖：改名留档，下一次写入从空库开始。
            quarantine(f)
            return LinkedHashMap()
        }
        val out = LinkedHashMap<String, JsonObject>()
        entries.forEach { (nickname, value) ->
            (value as? JsonObject)?.let { out[nickname] = it }
        }
        return out
    }

    private fun writeEntries(entries: Map<String, JsonObject>) {
        dir.mkdirs()
        val payload = buildJsonObject {
            put("version", VERSION)
            put("entries", JsonObject(entries))
        }.toString()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("写入 ssh/$FILE_NAME 失败")
            }
        }
    }

    private fun metaOf(nickname: String, entry: JsonObject): SshCredentialMeta? {
        if (!isValidNickname(nickname)) return null
        val host = entry.string("host") ?: return null
        val user = entry.string("user") ?: return null
        val authType = entry.string("authType") ?: "password"
        val port = runCatching { entry["port"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: 22
        return SshCredentialMeta(nickname, host, port, user, authType)
    }

    /** 返回 (iv base64, ciphertext base64)。 */
    private fun encryptSecret(secret: String): Pair<String, String> {
        val payload = runCatching { cipher.encrypt(secret) }.getOrElse {
            throw IllegalStateException("加密失败（Android Keystore 不可用）：${it.message ?: it.javaClass.simpleName}")
        }
        val separator = payload.indexOf(':')
        return if (separator <= 0 || separator == payload.lastIndex) {
            "" to payload
        } else {
            payload.substring(0, separator) to payload.substring(separator + 1)
        }
    }

    private fun decryptSecret(entry: JsonObject): String? {
        val cipherText = entry.string("secretCipher") ?: return null
        val iv = entry.string("iv").orEmpty()
        val payload = if (iv.isEmpty()) cipherText else "$iv:$cipherText"
        return runCatching { cipher.decrypt(payload) }.getOrNull()
    }

    private fun quarantine(f: File) {
        val target = File(f.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
        runCatching { f.renameTo(target) }
    }

    companion object {
        const val FILE_NAME = "credentials.enc"
        private const val VERSION = 1

        /** 与 ssh_connect 的 nickname 校验保持一致：无空白、≤32 字符。 */
        private val NICKNAME = Regex("^[^\\s]{1,32}$")
        private const val MAX_SECRET_CHARS = 16_384

        fun isValidNickname(nickname: String): Boolean = NICKNAME.matches(nickname)
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * R91.5: `credential_list` / `credential_delete`。
 *
 * 只暴露 nickname / host / port / user / authType 元数据；secret 没有
 * 任何工具侧出口。删除需显式 confirm（与仓库其它破坏性工具的确认闸一致）。
 */
class SshCredentialTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "credential_list" ->
            "列出已保存的 SSH 凭据元数据（nickname、host、port、user、认证方式）。密码与私钥永不返回；ssh_connect 用 credential=nickname 引用。"
        else ->
            "删除已保存的 SSH 凭据（仅删除本机加密存储，不可撤销）。必须 confirm=true；先向用户确认昵称。"
    }

    override val destructive = name == "credential_delete"

    override val parameters = when (name) {
        "credential_list" -> schemaOf()
        else -> schemaOf(
            "nickname" to strParam("要删除的凭据 nickname"),
            "confirm" to boolParam("必须为 true 才会删除"),
            required = listOf("nickname", "confirm"),
        )
    }

    private val appContext = context.applicationContext

    override suspend fun execute(args: JsonObject): String {
        val store = SshCredentialStore(appContext)
        if (name == "credential_list") {
            val items = store.list()
            if (items.isEmpty()) {
                return "还没有保存的 SSH 凭据。请让用户在「设置 → SSH 凭据」中录入（密码只在原生界面输入，不会进入对话）。"
            }
            return buildString {
                appendLine("已保存 SSH 凭据 ${items.size} 个（仅元数据）：")
                items.forEach {
                    appendLine("- ${it.nickname}  ${it.user}@${it.host}:${it.port}  ${it.authType}")
                }
                append("ssh_connect 时传 credential=<nickname> 引用；密码/私钥不会返回给对话。")
            }
        }
        if (args.boolArg("confirm") != true) {
            return "ERROR: 删除凭据需要用户确认后传 confirm=true"
        }
        val nickname = args.strArg("nickname") ?: return "ERROR: 缺少 nickname"
        return if (store.delete(nickname)) {
            "已删除 SSH 凭据「$nickname」"
        } else {
            "ERROR: 凭据不存在：$nickname"
        }
    }
}

package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Telephony
import androidx.core.content.FileProvider
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #10c external data access: providers, FileProvider and SAF document trees. */
val EXTERNAL_DATA_TOOLS = listOf(
    "contacts_search", "sms_read", "provider_query", "file_share",
    "saf_tree_pick", "saf_tree_list", "saf_tree_read", "saf_tree_write",
)

/** Persisted ACTION_OPEN_DOCUMENT_TREE grants, survives process restarts. */
object SafTreeStore {
    private const val PREF = "andagent_saf_trees"
    private const val KEY = "uris"

    fun add(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        current.add(uri)
        prefs.edit().putStringSet(KEY, current).apply()
    }

    fun list(context: Context): List<String> =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet()).orEmpty().toList()

    fun remove(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        current.remove(uri)
        prefs.edit().putStringSet(KEY, current).apply()
    }
}

/**
 * R111: SAF 目录树选择的可复用结果。saf_tree_pick 工具与 otg_request(saf) 共用
 * [ExternalDataTool.pickTree]；拆出结构化结果让调用方能直接拿到 root URI，同时
 * 不改变 saf_tree_pick 工具自身的对外文本输出格式。
 */
internal sealed class TreePickResult {
    /** 授权成功：uri 为可直接使用的 tree URI；text 为原工具输出文本（保持旧格式）。 */
    data class Granted(val uri: String, val text: String) : TreePickResult()
    /** 取消 / 超时 / 失败：text 为原工具输出文本（CANCELLED: / ERROR: 开头）。 */
    data class Rejected(val text: String) : TreePickResult()
}

class ExternalDataTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "contacts_search" -> "按姓名或号码搜索通讯录，返回姓名、电话、邮箱（需要 READ_CONTACTS）。"
        "sms_read" -> "只读查询短信收件箱/发件箱，按号码或时间过滤（需要 READ_SMS）。"
        "provider_query" -> "通用只读 ContentProvider 查询：指定 content:// URI、列、条件，返回最多 100 行。"
        "file_share" -> "通过 FileProvider 把应用内文件生成 content:// URI 并打开系统分享面板。"
        "saf_tree_pick" -> "打开系统目录选择器并申请 SAF DocumentsTree 持久授权。"
        "saf_tree_list" -> "列出已授权的 SAF 目录树，或列出某个目录树下的文件。"
        "saf_tree_read" -> "读取 SAF 文档（document URI）文本内容，最多 100KB。"
        "saf_tree_write" -> "在已授权的 SAF 目录树中新建文档并写入文本。"
        else -> "外部数据工具"
    }

    override val parameters: JsonObject = when (name) {
        "contacts_search" -> schemaOf(
            "keyword" to strParam("姓名或号码关键词，省略返回前 50 位联系人"),
            "limit" to intParam("返回数量，默认 20，上限 50"),
        )
        "sms_read" -> schemaOf(
            "address" to strParam("按号码过滤"),
            "keyword" to strParam("正文关键词"),
            "days" to intParam("仅查询最近 N 天，默认 7，上限 90"),
            "limit" to intParam("返回数量，默认 20，上限 100"),
        )
        "provider_query" -> schemaOf(
            "uri" to strParam("content:// URI"),
            "projection" to strParam("逗号分隔的列名，省略返回全部列"),
            "selection" to strParam("SQL WHERE 条件（只读）"),
            "selection_args" to strParam("条件参数 JSON 字符串数组"),
            "sort" to strParam("排序，如 date DESC"),
            "limit" to intParam("最多返回行数，默认 20，上限 100"),
            required = listOf("uri"),
        )
        "file_share" -> schemaOf(
            "path" to strParam("要分享的文件绝对路径（限应用私有目录 / 公共下载目录）"),
            "mime" to strParam("MIME 类型，默认按扩展名推断"),
            required = listOf("path"),
        )
        "saf_tree_pick" -> schemaOf()
        "saf_tree_list" -> schemaOf("tree_uri" to strParam("目录树 URI；省略则列出所有已授权树"))
        "saf_tree_read" -> schemaOf("document_uri" to strParam("SAF 文档 URI"), required = listOf("document_uri"))
        "saf_tree_write" -> schemaOf(
            "tree_uri" to strParam("目录树 URI（省略则使用第一个已授权树）"),
            "filename" to strParam("新文档文件名"),
            "content" to strParam("要写入的文本"),
            "mime" to strParam("MIME 类型，默认 text/plain"),
            required = listOf("filename", "content"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("file_share", "saf_tree_write")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "contacts_search" -> contacts(args)
                "sms_read" -> sms(args)
                "provider_query" -> providerQuery(args)
                "file_share" -> fileShare(args)
                "saf_tree_pick" -> safTreePick()
                "saf_tree_list" -> safTreeList(args)
                "saf_tree_read" -> safTreeRead(args)
                "saf_tree_write" -> safTreeWrite(args)
                else -> "ERROR: 未知外部数据工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* -------------------------- #15 contacts ---------------------------- */

    private suspend fun contacts(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_CONTACTS)))
            return "ERROR: 通讯录读取权限未授予"
        val keyword = args.strArg("keyword")?.trim()
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 50)
        val selection = if (keyword.isNullOrEmpty()) null else "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (keyword.isNullOrEmpty()) null else arrayOf("%$keyword%")
        val rows = buildJsonArray {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER),
                selection, selectionArgs, "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < limit) {
                    val id = cursor.getLong(0)
                    val display = cursor.getString(1) ?: ""
                    val numbers = buildJsonArray {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id.toString()), null,
                        )?.use { phone -> while (phone.moveToNext()) phone.getString(0)?.let { add(it) } }
                    }
                    val emails = buildJsonArray {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                            arrayOf(id.toString()), null,
                        )?.use { mail -> while (mail.moveToNext()) mail.getString(0)?.let { add(it) } }
                    }
                    add(buildJsonObject {
                        put("id", id)
                        put("name", display)
                        put("phones", numbers)
                        put("emails", emails)
                    })
                }
            }
        }
        return buildJsonObject { put("count", rows.size); put("contacts", rows) }.toString()
    }

    /* ---------------------------- #16 SMS ------------------------------- */

    private suspend fun sms(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_SMS)))
            return "ERROR: 短信读取权限未授予"
        val days = (args.intArg("days") ?: 7).coerceIn(1, 90)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val conditions = mutableListOf("${Telephony.Sms.DATE} >= ?")
        val values = mutableListOf(since.toString())
        args.strArg("address")?.let { conditions += "${Telephony.Sms.ADDRESS} LIKE ?"; values += "%$it%" }
        args.strArg("keyword")?.let { conditions += "${Telephony.Sms.BODY} LIKE ?"; values += "%$it%" }
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val rows = buildJsonArray {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
                conditions.joinToString(" AND "), values.toTypedArray(), "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < limit) {
                    add(buildJsonObject {
                        put("id", cursor.getLong(0))
                        put("address", cursor.getString(1) ?: "")
                        put("body", (cursor.getString(2) ?: "").take(1000))
                        put("date", cursor.getLong(3))
                        put("type", cursor.getInt(4))
                        put("read", cursor.getInt(5))
                    })
                }
            }
        }
        return buildJsonObject { put("days", days); put("count", rows.size); put("messages", rows) }.toString()
    }

    /* --------------------- generic read-only provider -------------------- */

    private fun providerQuery(args: JsonObject): String {
        val raw = args.strArg("uri") ?: return "ERROR: 缺少 uri"
        val uri = Uri.parse(raw)
        if (uri.scheme != "content") return "ERROR: 仅支持 content:// URI 的只读查询"
        val projection = args.strArg("projection")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toTypedArray()
        val selectionArgs = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(args.strArg("selection_args") ?: "[]") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toTypedArray()
        }.getOrNull() ?: emptyArray()
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val rows = buildJsonArray {
            context.contentResolver.query(uri, projection, args.strArg("selection"), selectionArgs, args.strArg("sort"))?.use { cursor ->
                var count = 0
                val columns = cursor.columnNames.toList()
                while (cursor.moveToNext() && count++ < limit) {
                    add(buildJsonObject {
                        columns.forEachIndexed { index, column ->
                            val value = when (cursor.getType(index)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                                android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index).toString()
                                android.database.Cursor.FIELD_TYPE_BLOB -> "<blob ${cursor.getBlob(index)?.size ?: 0} bytes>"
                                else -> cursor.getString(index)?.take(500)
                            }
                            put(column, value ?: "")
                        }
                    })
                }
            }
        }
        return buildJsonObject { put("uri", raw); put("count", rows.size); put("rows", rows) }.toString()
    }

    /* --------------------------- FileProvider ---------------------------- */

    private fun fileShare(args: JsonObject): String {
        val path = args.strArg("path") ?: return "ERROR: 缺少 path"
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return "ERROR: 路径无效"
        if (!file.isFile) return "ERROR: 文件不存在：$path"
        val roots = listOfNotNull(
            context.filesDir.canonicalFile,
            context.getExternalFilesDir(null)?.canonicalFile,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile,
        )
        if (roots.none { file.path.startsWith(it.path + File.separator) })
            return "ERROR: 仅可分享应用私有目录或公共下载目录内的文件"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = args.strArg("mime") ?: when (file.extension.lowercase()) {
            "txt", "md", "json", "csv", "log" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"
            "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(chooser)
            "OPENED: 已生成分享 URI $uri（$mime）"
        }.getOrElse { "ERROR: 无法打开分享面板：${it.message}" }
    }

    /* ------------------------------ SAF trees ---------------------------- */

    private fun safTreePick(): String = when (val outcome = pickTree()) {
        is TreePickResult.Granted -> outcome.text
        is TreePickResult.Rejected -> outcome.text
    }

    /**
     * R111: SAF 目录树选择的可复用实现，供 saf_tree_pick 工具与 otg_request(saf) 共用。
     * 成功时 Granted 携带 tree URI（可直接喂给 otg_transfer / otg_file_ops 的 root），
     * 失败时 Rejected 携带原样错误/取消文本，saf_tree_pick 的对外输出格式不变。
     */
    internal fun pickTree(): TreePickResult {
        val id = "tree_${java.util.UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        if (!FilePickResultBridge.begin(id, FilePickResultBridge.Kind.TREE, latch))
            return TreePickResult.Rejected("ERROR: 目录选择器不可用或已有选择请求进行中")
        try {
            FilePickResultBridge.launcher?.invoke(id, "", false, true)
                ?: FilePickResultBridge.fail(id, "目录选择器不可用")
        } catch (e: Exception) {
            FilePickResultBridge.fail(id, "无法打开目录选择器：${e.message}")
        }
        val ok = latch.await(120, TimeUnit.SECONDS)
        val failure = FilePickResultBridge.failures[id]
        val uri = FilePickResultBridge.results[id]?.firstOrNull()
        FilePickResultBridge.finish(id)
        if (!ok) return TreePickResult.Rejected("ERROR: 目录选择超时（120s）")
        if (failure != null) return TreePickResult.Rejected("ERROR: $failure")
        if (uri == null) return TreePickResult.Rejected("CANCELLED: 用户取消了目录选择")
        return TreePickResult.Granted(uri, "WRITTEN: 已获得目录树的持久授权：$uri")
    }

    private fun safTreeList(args: JsonObject): String {
        val granted = SafTreeStore.list(context)
        val treeUri = args.strArg("tree_uri")
        if (treeUri == null) {
            return buildJsonObject { put("count", granted.size); putJsonArray("trees") { granted.forEach { add(it) } } }.toString()
        }
        if (treeUri !in granted) return "ERROR: 此目录树尚未持久化授权，请先调用 saf_tree_pick"
        val uri = Uri.parse(treeUri)
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
        val rows = buildJsonArray {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count++ < 200) {
                    add(buildJsonObject {
                        put("id", cursor.getString(0) ?: "")
                        put("name", cursor.getString(1) ?: "")
                        put("mime", cursor.getString(2) ?: "")
                        put("size", if (cursor.isNull(3)) 0L else cursor.getLong(3))
                        put("modified", if (cursor.isNull(4)) 0L else cursor.getLong(4))
                        put("uri", DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0) ?: "").toString())
                    })
                }
            }
        }
        return buildJsonObject { put("treeUri", treeUri); put("count", rows.size); put("files", rows) }.toString()
    }

    private fun safTreeRead(args: JsonObject): String {
        val raw = args.strArg("document_uri") ?: return "ERROR: 缺少 document_uri"
        val uri = Uri.parse(raw)
        if (uri.scheme != "content") return "ERROR: document_uri 必须是 content:// URI"
        if (SafTreeStore.list(context).isEmpty())
            return "ERROR: 尚无授权的目录树，请先调用 saf_tree_pick"
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = ByteArray(100 * 1024)
            val n = stream.read(bytes)
            if (n <= 0) "" else String(bytes, 0, n, Charsets.UTF_8)
        } ?: return "ERROR: 无法读取文档"
        return buildJsonObject {
            put("uri", raw)
            put("length", text.length)
            put("truncated", text.length >= 100 * 1024)
            put("text", text)
        }.toString()
    }

    private fun safTreeWrite(args: JsonObject): String {
        val granted = SafTreeStore.list(context)
        val raw = args.strArg("tree_uri") ?: granted.firstOrNull()
            ?: return "ERROR: 尚无授权的目录树，请先调用 saf_tree_pick"
        if (raw !in granted) return "ERROR: 此目录树尚未持久化授权"
        val filename = args.strArg("filename") ?: return "ERROR: 缺少 filename"
        if (filename.contains('/') || filename.contains('\\')) return "ERROR: filename 不能包含路径分隔符"
        val content = args.strArg("content") ?: return "ERROR: 缺少 content"
        val treeUri = Uri.parse(raw)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val created = DocumentsContract.createDocument(
            context.contentResolver, parent, args.strArg("mime") ?: "text/plain", filename,
        ) ?: return "ERROR: 文档创建失败（目录可能不允许写入）"
        context.contentResolver.openOutputStream(created)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: return "ERROR: 无法写入文档内容"
        return "WRITTEN: 已写入 $created（${content.length} 字）"
    }
}

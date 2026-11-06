package com.dc.andagent.tools

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.dc.andagent.i18n.tr
import kotlinx.serialization.json.*
import java.io.File

/** App-private, named SQLite databases shared by the seven db_* tools. */
class SqliteTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "db_create" -> "创建用户 SQLite 数据库；已存在则直接返回路径。"
        "db_list" -> "列出用户 SQLite 数据库、大小和表数。"
        "db_tables" -> "列出数据库中的表、列数和行数。"
        "db_schema" -> "查看表结构。"
        "db_exec" -> "在用户数据库执行参数化 SQL；删除整表或无 WHERE 删除需要 confirm=true。值用 ? 和 params_json 绑定。"
        "db_query" -> "只读 SELECT 查询，返回列名和最多 limit 行。值用 ? 和 params_json 绑定。"
        else -> "删除整个用户数据库；需要 confirm=true。"
    }
    override val parameters = when (name) {
        "db_create", "db_tables", "db_drop" -> schemaOf("name" to strParam("数据库名，仅字母数字下划线，最长 64"), "confirm" to boolParam("删除数据库时确认"), required = listOf("name"))
        "db_schema" -> schemaOf("name" to strParam("数据库名"), "table" to strParam("表名"), required = listOf("name", "table"))
        "db_exec" -> schemaOf("name" to strParam("数据库名"), "sql" to strParam("SQL；值使用 ? 占位符"), "params_json" to strParam("按 ? 顺序绑定的 JSON 数组"), "confirm" to boolParam("确认危险删除"), required = listOf("name", "sql"))
        "db_query" -> schemaOf("name" to strParam("数据库名"), "sql" to strParam("SELECT SQL"), "params_json" to strParam("按 ? 顺序绑定的 JSON 数组"), "limit" to intParam("返回行数，默认 100，最多 1000"), required = listOf("name", "sql"))
        else -> schemaOf()
    }
    private val ident = Regex("[a-zA-Z0-9_]+")
    private val maxBytes = 50L * 1024 * 1024
    private fun valid(value: String?) = value != null && value.length <= 64 && ident.matches(value)
    private fun file(dbName: String) = context.getDatabasePath("andagent_${dbName}.db")
    private fun error(e: Throwable) = "ERROR: SQLite 错误：${e.message ?: e.javaClass.simpleName}"
    private fun existing(dbName: String): File? = file(dbName).takeIf { it.isFile }
    private fun open(dbName: String, readOnly: Boolean = false): SQLiteDatabase = SQLiteDatabase.openDatabase(
        file(dbName).path, null, if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE,
    )
    private fun params(args: JsonObject): List<JsonElement> {
        val raw = args.strArg("params_json") ?: return emptyList()
        return Json.parseToJsonElement(raw).let { it as? JsonArray ?: throw IllegalArgumentException("params_json 必须是 JSON 数组") }
    }
    private fun bindValue(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" -> 1L
            value.content == "false" -> 0L
            else -> value.longOrNull ?: value.doubleOrNull ?: value.content
        }
        else -> throw IllegalArgumentException("参数只支持字符串、数字、布尔值或 null")
    }
    private fun rows(cursor: Cursor, limit: Int): JsonObject = cursor.use { c ->
        val columns = c.columnNames.toList()
        var count = 0
        val out = buildJsonArray {
            while (count < limit && c.moveToNext()) {
                count++
                add(buildJsonArray {
                for (i in columns.indices) add(when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JsonNull
                    Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(c.getDouble(i))
                    Cursor.FIELD_TYPE_BLOB -> JsonPrimitive("<BLOB ${c.getBlob(i).size} bytes>")
                    else -> JsonPrimitive(c.getString(i))
                })
                })
            }
        }
        buildJsonObject {
            put("columns", JsonArray(columns.map(::JsonPrimitive)))
            put("rows", out)
            put("truncated", c.moveToNext())
        }
    }
    private fun query(db: SQLiteDatabase, sql: String, values: List<JsonElement>, limit: Int): JsonObject {
        val cursor = db.rawQueryWithFactory({ _, driver, editTable, query ->
            values.forEachIndexed { index, value ->
                val slot = index + 1
                when (val v = bindValue(value)) {
                    null -> query.bindNull(slot)
                    is Long -> query.bindLong(slot, v)
                    is Double -> query.bindDouble(slot, v)
                    else -> query.bindString(slot, v.toString())
                }
            }
            android.database.sqlite.SQLiteCursor(driver, editTable, query)
        }, sql, emptyArray(), null)
        return rows(cursor, limit)
    }
    // Strip literals and comments before checking commands. Values are still bound separately.
    private fun command(sql: String): String {
        val cleaned = sql.replace(Regex("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|--[^\\n]*|/\\*[\\s\\S]*?\\*/"), " ").trim()
        if (cleaned.removeSuffix(";").contains(';')) throw IllegalArgumentException("一次只允许一条 SQL")
        if (Regex("\\b(ATTACH|DETACH|VACUUM|PRAGMA|LOAD_EXTENSION)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleaned))
            throw IllegalArgumentException("禁止执行危险 SQL 指令")
        return cleaned.removeSuffix(";").trim()
    }
    override suspend fun execute(args: JsonObject): String = runCatching {
        if (name == "db_list") {
            val dir = context.getDatabasePath("andagent_probe.db").parentFile
            val dbs = dir?.listFiles()?.filter { it.isFile && it.name.matches(Regex("andagent_[a-zA-Z0-9_]+\\.db")) }.orEmpty()
            return@runCatching buildJsonArray {
                dbs.forEach { f ->
                    val count = runCatching { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                    } }.getOrDefault(0)
                    add(buildJsonObject { put("name", f.name.removePrefix("andagent_").removeSuffix(".db")); put("path", f.path); put("sizeBytes", f.length()); put("tableCount", count) })
                }
            }.toString()
        }
        val dbName = args.strArg("name")
        if (!valid(dbName)) {
            return@runCatching "ERROR: " + tr(
                "表/库名只允许字母数字下划线，长度最多 64",
                "table/database names allow only letters, digits and underscores, up to 64 characters",
            )
        }
        val safeName = dbName!!
        val f = file(safeName)
        if (name == "db_create") {
            val existed = f.isFile
            f.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(f, null).use { }
            return@runCatching buildJsonObject {
                put("path", f.path)
                put("existed", existed)
                put("message", if (existed) tr("已存在，直接可用", "already exists and is ready") else tr("创建成功", "created"))
            }.toString()
        }
        if (existing(safeName) == null) {
            return@runCatching "ERROR: " + tr("数据库不存在，请先调用 db_create", "database does not exist; call db_create first")
        }
        if (name == "db_drop") {
            if (args.boolArg("confirm") != true) {
                return@runCatching tr(
                    "需要确认删除整个数据库 $safeName；确认后以 confirm=true 重试",
                    "deleting the whole database $safeName needs confirmation; retry with confirm=true",
                )
            }
            val deleted = context.deleteDatabase(f.name)
            return@runCatching buildJsonObject { put("deleted", deleted); put("name", safeName) }.toString()
        }
        if (name == "db_tables" || name == "db_schema") return@runCatching open(safeName, true).use { db ->
            if (name == "db_tables") {
                val result = buildJsonArray {
                    db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c ->
                        while (c.moveToNext()) {
                            val table = c.getString(0)
                            if (!valid(table)) continue
                            val cols = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { it.count }
                            val count = db.rawQuery("SELECT count(*) FROM \"$table\"", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }
                            add(buildJsonObject { put("table", table); put("columnCount", cols); put("rowCount", count) })
                        }
                    }
                }
                result.toString()
            } else {
                val table = args.strArg("table")
                if (!valid(table)) "ERROR: 表/库名只允许字母数字下划线，长度最多 64"
                else buildJsonArray {
                    db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c -> while (c.moveToNext()) add(buildJsonObject {
                        put("name", c.getString(1)); put("type", c.getString(2)); put("notNull", c.getInt(3) != 0)
                        put("default", if (c.isNull(4)) JsonNull else JsonPrimitive(c.getString(4))); put("primaryKey", c.getInt(5) != 0)
                    }) }
                }.toString()
            }
        }
        val sql = args.strArg("sql") ?: return@runCatching "ERROR: 缺少 sql"
        val inspected = command(sql)
        val values = params(args)
        if (name == "db_query") {
            if (!inspected.startsWith("SELECT", true)) return@runCatching "ERROR: db_query 只允许 SELECT"
            return@runCatching open(safeName, true).use { query(it, sql, values, (args.intArg("limit") ?: 100).coerceIn(1, 1000)).toString() }
        }
        if (Regex("^DROP\\s+TABLE\\b|^DELETE\\s+FROM\\b(?![\\s\\S]*\\bWHERE\\b)", RegexOption.IGNORE_CASE).containsMatchIn(inspected) && args.boolArg("confirm") != true)
            return@runCatching "需要确认危险删除；确认后以 confirm=true 重试"
        if (f.length() >= maxBytes && Regex("^(INSERT|UPDATE|CREATE|REPLACE)", RegexOption.IGNORE_CASE).containsMatchIn(inspected))
            return@runCatching "ERROR: 数据库已达到 50MB 上限"
        val first = inspected.substringBefore(' ').uppercase()
        if (first in setOf("SELECT", "WITH", "EXPLAIN"))
            return@runCatching open(safeName, true).use { query(it, sql, values, 100).toString() }
        open(safeName).use { db ->
            db.beginTransaction()
            try {
                db.execSQL(sql, values.map(::bindValue).toTypedArray())
                val affected = db.rawQuery("SELECT changes()", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                val pages = db.rawQuery("PRAGMA page_count", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val pageSize = db.rawQuery("PRAGMA page_size", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (pages * pageSize > maxBytes) throw IllegalStateException("数据库超过 50MB 上限，写入已回滚")
                db.setTransactionSuccessful()
                buildJsonObject { put("affectedRows", affected) }.toString()
            } finally { db.endTransaction() }
        }
    }.getOrElse { error(it) }
}

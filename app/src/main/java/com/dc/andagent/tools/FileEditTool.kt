package com.dc.andagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Surgical text edit: the agent supplies the exact `old_string` it saw in
 * `file_read` and the replacement. This mirrors the `Edit` tool shipped by
 * top-tier coding agents — an exact literal match keeps the change verifiable,
 * and a non-unique match is refused instead of silently patching the wrong
 * occurrence.
 *
 * Preferred over `file_write` for anything except a brand-new file: a full
 * rewrite burns tokens and destroys changes the model never saw.
 */
class FileEditTool(private val context: Context) : Tool {
    override val name = "file_edit"
    override val description =
        "精确替换文件中的文本：把 old_string 换成 new_string。" +
            "old_string 必须与文件内容完全一致且在文件中唯一（重复时报错并给出行号）；" +
            "需要一次替换多处时设 replace_all=true，删除内容时 new_string 传空字符串。" +
            "修改已有文件请优先用它，而不是 file_write 整体重写。"
    override val parameters = schemaOf(
        "path" to strParam("文件绝对路径或 content:// URI"),
        "dir" to strParam("与 name 搭配：downloads | documents | app_private"),
        "name" to strParam("文件相对路径（与 dir 搭配，可含子目录）"),
        "old_string" to strParam("要替换的原文本，需与文件内容完全一致"),
        "new_string" to strParam("替换后的文本；空字符串表示删除该片段"),
        "replace_all" to boolParam("为 true 时替换所有匹配，默认 false"),
        required = listOf("old_string", "new_string"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val oldText = args.strArg("old_string")
            ?: return@withContext "ERROR: 缺少 old_string"
        val newElement = args["new_string"] as? JsonPrimitive
            ?: return@withContext "ERROR: 缺少 new_string（删除内容请传空字符串）"
        val newText = newElement.contentOrNull
            ?: return@withContext "ERROR: new_string 不能为 null（删除内容请传空字符串）"
        if (oldText == newText) {
            return@withContext "ERROR: old_string 与 new_string 相同，无需修改"
        }
        val replaceAll = args.boolArg("replace_all") == true

        val target = resolveTarget(
            context = context,
            path = args.strArg("path"),
            dir = args.strArg("dir"),
            name = args.strArg("name"),
        ) ?: return@withContext "ERROR: 找不到文件（请检查 path 或 dir+name）"

        runCatching {
            val bytes = target.openStream().use { readAtMost(it, EDIT_CAP + 1) }
            if (bytes.size > EDIT_CAP) {
                return@runCatching "ERROR: 文件超过 ${EDIT_CAP / 1024 / 1024}MB，" +
                    "file_edit 只适合精确修改，请用 file_read 定位后再改"
            }
            val original = String(bytes, Charsets.UTF_8)

            // Models often paste the `file_read` view including its line-number
            // prefix; strip it once before giving up on the match.
            val needle = when {
                original.contains(oldText) -> oldText
                else -> stripLineNumbers(oldText).takeIf { it != oldText && original.contains(it) }
                    ?: return@runCatching notFound(original, oldText)
            }

            val hits = indexOfAll(original, needle)
            if (hits.isEmpty()) return@runCatching notFound(original, needle)
            if (hits.size > 1 && !replaceAll) {
                val lines = hits.take(8).joinToString(", ") { lineOf(original, it).toString() }
                return@runCatching "ERROR: old_string 在文件中出现 ${hits.size} 次" +
                    "（第 $lines 行），请补充上下文使其唯一，或设 replace_all=true"
            }

            val updated = if (replaceAll) {
                replaceAllLiteral(original, needle, newText)
            } else {
                val at = hits.first()
                original.substring(0, at) + newText + original.substring(at + needle.length)
            }
            target.writeText(updated)

            val changedLine = lineOf(original, hits.first())
            buildJsonObject {
                put("path", target.displayPath)
                put("replaced", if (replaceAll) hits.size else 1)
                put("bytes", updated.toByteArray(Charsets.UTF_8).size)
                put("linesBefore", original.count { it == '\n' } + 1)
                put("linesAfter", updated.count { it == '\n' } + 1)
                put("firstChangedLine", changedLine)
                put("preview", preview(updated, changedLine))
            }.toString()
        }.getOrElse { "ERROR: 编辑失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun readAtMost(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(64 * 1024)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun indexOfAll(text: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val hits = ArrayList<Int>()
        var at = text.indexOf(needle)
        while (at >= 0) {
            hits.add(at)
            at = text.indexOf(needle, at + needle.length)
        }
        return hits
    }

    /** Literal (non-regex) replacement; `$` and `\` in the replacement are safe. */
    private fun replaceAllLiteral(text: String, needle: String, replacement: String): String {
        val sb = StringBuilder(text.length)
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) {
                sb.append(text, from, text.length)
                break
            }
            sb.append(text, from, at).append(replacement)
            from = at + needle.length
        }
        return sb.toString()
    }

    private fun lineOf(text: String, index: Int): Int =
        text.take(index.coerceIn(0, text.length)).count { it == '\n' } + 1

    /** `file_read` returns `    12\tcontent`; drop that prefix when present. */
    private fun stripLineNumbers(text: String): String {
        val pattern = Regex("^\\s*\\d+\\t")
        val lines = text.split('\n')
        if (lines.none { pattern.containsMatchIn(it) }) return text
        return lines.joinToString("\n") { pattern.replaceFirst(it, "") }
    }

    private fun notFound(text: String, needle: String): String {
        val first = needle.lineSequence().firstOrNull()?.trim().orEmpty()
        val hint = if (first.length < 2) {
            ""
        } else {
            val line = text.lineSequence().indexOfFirst { it.contains(first) }
            if (line >= 0) "；第 ${line + 1} 行包含相似内容，请先 file_read 该区域再重试" else ""
        }
        return "ERROR: 文件中不存在 old_string（请确认缩进与换行完全一致）$hint"
    }

    private fun preview(text: String, line: Int, span: Int = 2): String {
        val lines = text.split('\n')
        val from = (line - span).coerceAtLeast(1)
        val to = (line + span).coerceAtMost(lines.size)
        val sb = StringBuilder()
        for (i in from..to) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(i.toString().padStart(6)).append('\t').append(lines[i - 1])
        }
        return sb.toString()
    }
}

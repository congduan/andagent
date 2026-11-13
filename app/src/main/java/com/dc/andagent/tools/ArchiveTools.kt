package com.dc.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * R152e 压缩格式补全 —— 「接口占位 + 主控决策」。
 *
 * 官方 zstd / libarchive 均不发布 Android arm64-v8a 预编译 .so（GitHub release
 * assets 无 android/arm64 产物）；NDK 源码交叉编译属重构建，按用户「预编译 so
 * 拿不到则转占位、不强做」执行：工具接口 / 参数 / 路径白名单 / zip-slip 防护就位，
 * 实际解包与压缩后端留待主控决策（补 .so 或 NDK 构建）后接入。当前一律如实返回
 * 「未集成」，不伪报完成。
 *
 * zip-slip 防护是必修项，不依赖任何原生库，已用纯 JVM 实现并单测覆盖（ArchiveToolsTest）。
 */
val ARCHIVE_TOOLS = listOf("archive_list", "archive_extract", "archive_create", "zstd_compress")

private val ARCHIVE_ALLOWED_ROOTS = listOf("/sdcard/", "/storage/emulated/0/", "/mnt/sdcard/")

object ArchivePathRules {
    /** 路径白名单：仅 /sdcard 及其等价存储根下的绝对路径。 */
    fun validatePath(path: String): String? {
        if (!ARCHIVE_ALLOWED_ROOTS.any { path.startsWith(it) }) {
            return "路径必须在 /sdcard 白名单内：$path"
        }
        if (path.contains("..")) return "路径不允许包含 .. ：$path"
        return null
    }

    /**
     * zip-slip 防护：把归档条目名解析到 destDir 下，逐条校验 canonical path 前缀。
     * 绝对路径 / .. 段 / 反斜杠穿越 / 解析后越出 destDir / 空名 / 解析为 destDir 本身
     * 一律返回 null（拒绝）。
     */
    fun safeEntryFile(destDir: String, entryName: String): File? {
        if (entryName.isEmpty()) return null
        val n = entryName.replace('\\', '/')
        if (n.startsWith("/") || n.split('/').any { it == ".." }) return null
        val dest = File(destDir).canonicalFile
        val target = File(dest, n).canonicalFile
        if (target == dest || !target.path.startsWith(dest.path + File.separator)) return null
        return target
    }
}

private fun arrayParam(desc: String): JsonElement = buildJsonObject {
    put("type", "array")
    put("description", desc)
    put("items", buildJsonObject { put("type", "string") })
}

class ArchiveTool(override val name: String) : Tool {

    override val description: String = when (name) {
        "archive_list" ->
            "列出归档条目（接口占位：libarchive 原生后端未集成，当前如实返回未支持，不伪报成功）。目标支持面 zip/rar/rar5/7z/tar/gz/zst/xz/lz4 待后端接入。"
        "archive_extract" ->
            "解包归档到 dest_dir（destructive；接口占位：libarchive 未集成）。zip-slip 路径穿越防护已启用：含 ../ 或解析后越出 dest_dir 的条目逐条拒绝，校验先于后端调用。"
        "archive_create" ->
            "打包 entries（接口占位：libarchive/libzstd 未集成）。format=zip|tar|zst 待后端接入。"
        else ->
            "zstd 高速压缩文件（接口占位：libzstd 未集成）。"
    }

    override val parameters: JsonElement = when (name) {
        "archive_list" -> schemaOf(
            "path" to strParam("归档绝对路径（/sdcard/...）"),
            required = listOf("path"),
        )
        "archive_extract" -> schemaOf(
            "path" to strParam("归档绝对路径（/sdcard/...）"),
            "dest_dir" to strParam("解包目标目录绝对路径（/sdcard/...）"),
            "entries" to arrayParam("可选，只解这些条目名；含 ../ 的越界条目会被拒绝"),
            required = listOf("path", "dest_dir"),
        )
        "archive_create" -> schemaOf(
            "path" to strParam("输出归档绝对路径（/sdcard/...）"),
            "entries" to arrayParam("要打包的源文件绝对路径列表"),
            "format" to strParam("zip|tar|zst，默认 zip"),
            required = listOf("path", "entries"),
        )
        else -> schemaOf(
            "path" to strParam("待压缩文件绝对路径（/sdcard/...）"),
            "level" to intParam("压缩级别 1..19，默认 3"),
            required = listOf("path"),
        )
    }

    override val destructive: Boolean = name == "archive_extract"

    override suspend fun execute(args: JsonObject): String {
        val path = args.strArg("path") ?: return "ERROR: 缺少 path"
        ArchivePathRules.validatePath(path)?.let { return "ERROR: $it" }

        if (name == "archive_extract") {
            val dest = args.strArg("dest_dir") ?: return "ERROR: 缺少 dest_dir"
            ArchivePathRules.validatePath(dest)?.let { return "ERROR: $it" }
            val entries = (args["entries"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            } ?: emptyList()
            val rejected = entries.filter { ArchivePathRules.safeEntryFile(dest, it) == null }
            if (rejected.isNotEmpty()) return "ERROR: zip-slip 拒绝以下越界条目：$rejected"
        }

        return "ERROR: R152e 接口占位——原生 libarchive/libzstd 未集成（官方无 Android arm64 预编译 .so，本机未做 NDK 源码构建）；待主控决策后接入，当前不伪报成功。"
    }
}

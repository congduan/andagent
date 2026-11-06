package com.dc.andagent.tools

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import com.dc.andagent.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/**
 * R39: 富剪贴板——文本之外的 HTML / URI / 图片写入、全 MIME 读取与清空。
 *
 * Android 10+ 限制：应用有窗口焦点时才能读剪贴板（与 clipboard_read 一致）；
 * clearPrimaryClip 实际只有默认输入法能调用，前台应用以「写入空 clip」替代。
 */
class ClipboardWriteRichTool(private val context: Context) : Tool {
    override val name = "clipboard_write_rich"
    override val description =
        "写入富内容到系统剪贴板：kind=text|html|uri|image。html 传 HTML 字符串（Android 10 以下降级纯文本）；" +
            "uri 传 content:// 或 https:// 链接；image 传图片 base64（可含 data:image/...;base64, 前缀，png/jpg），" +
            "写入后可在支持粘贴图片的应用（聊天/文档）直接粘贴。"

    override val parameters = schemaOf(
        "kind" to strParam("text | html | uri | image"),
        "data" to strParam("写入内容：文本/HTML/URI/图片 base64"),
        "label" to strParam("可选：剪贴板标签，默认 AndAgent"),
        required = listOf("kind", "data"),
    )

    override suspend fun execute(args: JsonObject): String {
        val kind = normalizeKind(args.strArg("kind"))
            ?: return "ERROR: " + tr(
                "不支持的 kind：${args.strArg("kind")}（可选 text|html|uri|image）",
                "Unsupported kind: ${args.strArg("kind")} (use text|html|uri|image)",
            )
        val data = args.strArg("data") ?: return "ERROR: " + tr("缺少 data", "missing data")
        val label = args.strArg("label") ?: "AndAgent"
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return "ERROR: " + tr("系统剪贴板不可用", "system clipboard unavailable")
        return runCatching {
            when (kind) {
                "text" -> {
                    cm.setPrimaryClip(ClipData.newPlainText(label, data))
                    tr("已写入剪贴板文本（${data.length} 字）", "Copied text to clipboard (${data.length} chars)")
                }
                "html" -> {
                    val clip = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        ClipData.newHtmlText(label, data, data)
                    } else {
                        ClipData.newPlainText(label, data)
                    }
                    cm.setPrimaryClip(clip)
                    val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
                        "text/html"
                    } else {
                        tr("text（系统不支持 HTML clip，已降级）", "text (HTML clip unsupported, downgraded)")
                    }
                    tr("已写入剪贴板 HTML（$mode，${data.length} 字）", "Copied HTML to clipboard ($mode, ${data.length} chars)")
                }
                "uri" -> {
                    val uri = android.net.Uri.parse(data)
                    cm.setPrimaryClip(ClipData.newRawUri(label, uri))
                    tr("已写入剪贴板 URI：$data", "Copied URI to clipboard: $data")
                }
                else -> { // image
                    val raw = data.substringAfter("base64,", data)
                    val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
                    val mime = detectImageMime(bytes)
                    val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
                    val file = File(dir, "clipboard_${System.currentTimeMillis()}.${if (mime == "image/jpeg") "jpg" else "png"}")
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    cm.setPrimaryClip(ClipData.newRawUri(label, uri))
                    tr(
                        "已写入剪贴板图片（$mime，${bytes.size} 字节，粘贴到聊天/文档即可插入）",
                        "Copied image to clipboard ($mime, ${bytes.size} bytes; paste into chat or a document)",
                    )
                }
            }
        }.getOrElse {
            "ERROR: " + tr("写入剪贴板失败：${it.message ?: it.javaClass.simpleName}", "could not write to clipboard: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    companion object {
        /** kind 白名单归一化；未知返回 null。 */
        fun normalizeKind(raw: String?): String? =
            raw?.lowercase(Locale.US)?.takeIf { it in setOf("text", "html", "uri", "image") }

        /** JPEG magic（FFD8FF）→ image/jpeg，其余按 PNG 处理（写入链路只出这两种）。 */
        fun detectImageMime(bytes: ByteArray): String =
            if (bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
                "image/jpeg" else "image/png"
    }
}

class ClipboardReadRichTool(private val context: Context) : Tool {
    override val name = "clipboard_read_rich"
    override val description =
        "读取系统剪贴板全部内容类型：纯文本、HTML、URI（含粘贴的图片，返回 content:// 与 MIME）、并给出内容预览。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return "ERROR: " + tr("系统剪贴板不可用", "system clipboard unavailable")
        if (!cm.hasPrimaryClip()) return "ERROR: " + tr("剪贴板为空", "clipboard is empty")
        val clip = cm.primaryClip
            ?: return "ERROR: " + tr(
                "无法读取剪贴板（Android 10+ 要求应用窗口有焦点）",
                "could not read clipboard (Android 10+ requires the app window to have focus)",
            )
        if (clip.itemCount == 0) return "ERROR: " + tr("剪贴板为空", "clipboard is empty")
        val desc = clip.description
        val item = clip.getItemAt(0)
        return buildJsonObject {
            put("mimes", kotlinx.serialization.json.JsonArray(
                (0 until desc.mimeTypeCount).map {
                    kotlinx.serialization.json.JsonPrimitive(desc.getMimeType(it))
                }))
            put("text", runCatching { item.coerceToText(context)?.toString().orEmpty() }.getOrDefault("")
                .take(2000))
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                item.htmlText?.let { put("html", it.take(2000)) }
            }
            item.uri?.let { uri ->
                val resolved = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                buildJsonObject {
                    put("uri", uri.toString())
                    resolved?.let { put("mime", it) }
                    // content:// 图片：附尺寸/大小等基本信息（不读像素）。
                    if (resolved?.startsWith("image/") == true) {
                        runCatching {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                                put("bytes", fd.length)
                            }
                        }
                    }
                }.let { put("uriItem", it) }
            }
            put("label", desc.label?.toString() ?: "")
        }.toString()
    }
}

class ClipboardClearTool(private val context: Context) : Tool {
    override val name = "clipboard_clear"
    override val description =
        "清空系统剪贴板。Android 10 起系统只允许默认输入法调用 clearPrimaryClip；前台应用以覆盖写入一个空 clip 替代，效果等同（原内容不可再粘贴）。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
            ?: return "ERROR: " + tr("系统剪贴板不可用", "system clipboard unavailable")
        if (!cm.hasPrimaryClip()) return tr("剪贴板本来就是空的", "Clipboard is already empty")
        return runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                // 先尝试真清空（少数 ROM/旧版本允许）；被拒则回退空 clip 覆盖。
                val cleared = runCatching { cm.clearPrimaryClip(); true }.getOrDefault(false)
                if (cleared) return@runCatching tr("已清空剪贴板（clearPrimaryClip）", "Clipboard cleared (clearPrimaryClip)")
            }
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
            tr(
                "已清空剪贴板（Android 10+ 限制 clear 仅默认输入法可用，已用空 clip 覆盖，原内容不可再粘贴）",
                "Clipboard cleared (Android 10+ only lets the default IME clear it; an empty clip overwrote the content, so it can no longer be pasted)",
            )
        }.getOrElse {
            "ERROR: " + tr("清空剪贴板失败：${it.message ?: it.javaClass.simpleName}", "could not clear clipboard: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}

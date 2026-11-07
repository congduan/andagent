package com.dc.andagent.tools

import android.service.quicksettings.TileService
import com.dc.andagent.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val TILE_TOOLS = listOf("tile_set", "tile_get", "tile_clear")

/**
 * R77: quick-settings tile configuration tools. The tile itself lives in
 * AndagentTileService; these tools manage its stored prompt/label.
 * Same non-destructive posture as the pin shortcut tools (no destructive flag).
 */
class TileTool(private val context: android.content.Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "tile_set" -> "配置下拉快捷栏磁贴：设置显示名与点击后自动发送的 prompt（复用 andagent://send deeplink）。已有磁贴需用户先从快捷栏添加；配置后自动请求系统刷新。"
        "tile_get" -> "读取当前下拉快捷栏磁贴配置（显示名/prompt），未配置返回 configured=false。"
        "tile_clear" -> "清除下拉快捷栏磁贴配置；磁贴回落为打开主界面。"
        else -> "快捷磁贴工具"
    }

    override val parameters: JsonObject = when (name) {
        "tile_set" -> schemaOf(
            "label" to strParam("磁贴显示名，1-32 字符，无控制字符"),
            "prompt" to strParam("点击磁贴后自动发送的指令，1-2000 字符"),
            required = listOf("label", "prompt"),
        )
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = runCatching {
        when (name) {
            "tile_set" -> set(args)
            "tile_get" -> get()
            "tile_clear" -> clear()
            else -> "ERROR: " + tr("未知磁贴工具 $name", "unknown tile tool $name")
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private fun set(args: JsonObject): String {
        val label = args.strArg("label")?.trim()
            ?: return "ERROR: " + tr("缺少 label", "missing label")
        val prompt = args.strArg("prompt")
            ?: return "ERROR: " + tr("缺少 prompt", "missing prompt")
        if (!TileConfig.validLabel(label)) {
            return "ERROR: " + tr(
                "label 需为 1-32 字符且不含控制字符",
                "label must be 1-32 characters with no control characters",
            )
        }
        if (!TileConfig.validPrompt(prompt)) {
            return "ERROR: " + tr(
                "prompt 需为 1-2000 字符且不含控制字符",
                "prompt must be 1-2000 characters with no control characters",
            )
        }
        TileStore(context).write(label, prompt)
        AndagentTileService.requestRefresh(context)
        return tr(
            "磁贴已配置为「$label」；若快捷栏还没有该磁贴，请在系统快捷栏编辑中添加 AndAgent",
            "Tile configured as \"$label\"; if the tile is not in quick settings yet, add AndAgent via the quick-settings editor",
        )
    }

    private fun get(): String {
        // Codex R77 must-fix #4: read() must not throw on corrupted prefs.
        val config = runCatching { TileStore(context).read() }.getOrNull()
            ?: return buildJsonObject { put("configured", false) }.toString()
        return buildJsonObject {
            put("configured", true)
            put("label", config.label)
            put("prompt", config.prompt)
            put("updatedAt", config.updatedAt)
        }.toString()
    }

    private fun clear(): String {
        // Codex R77 must-fix #4: clear unconditionally so a corrupted config
        // can always be repaired via tile_clear.
        val had = runCatching { TileStore(context).read() != null }.getOrDefault(false)
        TileStore(context).clearUnchecked()
        AndagentTileService.requestRefresh(context)
        return if (had) {
            tr("磁贴配置已清除；磁贴点击回落为打开主界面", "Tile config cleared; tapping the tile now opens the main screen")
        } else {
            tr("磁贴本就未配置", "Tile was not configured")
        }
    }
}

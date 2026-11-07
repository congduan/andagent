package com.dc.andagent.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.PendingIntent
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dc.andagent.R

/** R77: persisted config for the QS task tile. Pure JVM-testable validation. */
internal object TileConfig {
    const val MAX_LABEL = 32
    const val MAX_PROMPT = 2000

    fun validLabel(label: String): Boolean =
        label.isNotEmpty() && label.length <= MAX_LABEL && label.none { it.isISOControl() }

    fun validPrompt(prompt: String): Boolean =
        // isNotBlank: whitespace-only prompts would be silently dropped by the
        // deeplink queue (queueDeeplink trims), making the tile tap a no-op.
        prompt.isNotBlank() && prompt.length <= MAX_PROMPT && prompt.none { it.isISOControl() }
}

/** SharedPreferences-backed store (andagent_tiles) for the single QS tile config. */
internal class TileStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("andagent_tiles", Context.MODE_PRIVATE)

    data class Config(val label: String, val prompt: String, val updatedAt: Long)

    fun read(): Config? {
        // Codex R77 must-fix #4: a corrupted prefs entry (wrong value type,
        // e.g. after a backup/restore) throws ClassCastException — treat it as
        // unconfigured instead of crashing the tile or the tool.
        val label = runCatching { prefs.getString("label", null) }.getOrNull() ?: return null
        val prompt = runCatching { prefs.getString("prompt", null) }.getOrNull() ?: return null
        if (!TileConfig.validLabel(label) || !TileConfig.validPrompt(prompt)) return null
        return Config(label, prompt, prefs.getLong("updatedAt", 0L))
    }

    fun write(label: String, prompt: String) {
        check(
            prefs.edit()
                .putString("label", label)
                .putString("prompt", prompt)
                .putLong("updatedAt", System.currentTimeMillis())
                .commit()
        ) { "磁贴配置保存失败" }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "磁贴配置清除失败" }
    }

    /** Best-effort clear for the repair path — never crash on commit failure. */
    fun clearUnchecked() {
        runCatching { prefs.edit().clear().commit() }
    }
}

/**
 * R77: quick-settings tile that fires the configured prompt as an
 * `andagent://send?prompt=..&auto=1` deeplink (reuses the existing deeplink
 * queue in MainActivity). No config configured → opens the main activity.
 * Never-crash discipline: the QS tile lifecycle is quirky, everything is wrapped.
 */
class AndagentTileService : TileService() {

    override fun onStartListening() {
        runCatching { refresh() }
    }

    override fun onClick() {
        runCatching {
            // Codex R77 must-fix #2: the QS tile can be tapped on the lock
            // screen — complete the unlock challenge before doing anything
            // (especially before firing an auto-send deeplink).
            if (!isLocked) { tap(); return }
            unlockAndRun { tap() }
        }
    }

    private fun tap() {
        runCatching {
            val config = TileStore(applicationContext).read()
            val intent: Intent = if (config != null) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("andagent://send?prompt=${Uri.encode(config.prompt)}&auto=1"),
                ).setPackage(packageName)
            } else {
                packageManager.getLaunchIntentForPackage(packageName) ?: return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Codex R77 must-fix #1: the plain-Intent overload of
            // startActivityAndCollapse throws UnsupportedOperationException on
            // Android 14 (API 34)+; use the PendingIntent overload there.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val config = TileStore(applicationContext).read()
        if (config != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = config.label
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_label)
        }
        tile.updateTile()
    }

    companion object {
        /** Ask the system to re-listen so the tile label/state refreshes after tile_set. */
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, AndagentTileService::class.java),
                )
            }
        }
    }
}

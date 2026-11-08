package com.dc.andagent.tools

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Spatializer
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.ArrayDeque

/**
 * 音频效果链 / 音频焦点 / 空间音频（Android 12 Spatializer）。
 *
 * 三条主线：
 *  1) audio_effect —— 用 MediaPlayer 播放本地音频，并把 Equalizer / BassBoost /
 *     PresetReverb / Virtualizer 挂到该播放器的 audio session 上；播放期间可继续
 *     `set` 调参，`stop` 收尾。音效绑定 session（不是全局输出混音），不影响其他 App。
 *  2) audio_focus —— AudioFocusRequest 的申请/让出/状态：记录每次 focus 回调，
 *     被抢占时自动暂停（LOSS/LOSS_TRANSIENT）或压低音量（CAN_DUCK），恢复后自动回弹。
 *  3) spatial_audio —— API 32 Spatializer 能力探测与开关；getSpatializer() 在部分
 *     版本需要 MODIFY_AUDIO_ROUTING（系统签名），无权限时如实返回 RESTRICTED。
 *
 * 录音侧的 UNPROCESSED 原始音频源见 HardwareStatusTools.audio_record_ex。
 */

/* ------------------------- 纯逻辑（JVM 可单测） ------------------------- */

/** 参数解析/裁剪规则；不触碰 Android 框架实例，便于 JVM 单测。 */
object AudioFxRules {
    val EFFECT_NAMES = setOf("eq", "bass", "reverb", "virtualizer")
    val DEFAULT_EFFECTS = setOf("eq", "bass")
    val DEFAULT_EQ_PRESETS = listOf(
        "Normal", "Classical", "Dance", "Flat", "Folk",
        "Heavy Metal", "Hip Hop", "Jazz", "Pop", "Rock",
    )
    val REVERB_PRESETS = listOf(
        "none", "smallroom", "mediumroom", "largeroom", "mediumhall", "largehall", "plate",
    )

    /** 去空格、小写、只留字母数字，用于匹配预设/枚举名。 */
    fun normalize(raw: String?): String =
        (raw ?: "").trim().lowercase().filter { it.isLetterOrDigit() }

    /** "eq,bass" → {"eq","bass"}；空值用默认；含未知项返回 null。 */
    fun parseEffects(raw: String?): Set<String>? {
        if (raw.isNullOrBlank()) return DEFAULT_EFFECTS
        val names = raw.split(',', '|', ' ', ';')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
        if (names.isEmpty()) return DEFAULT_EFFECTS
        if (names.any { it !in EFFECT_NAMES }) return null
        return names.toSet()
    }

    /**
     * "60:4,230:-2" → [(60, 400), (230, -200)]（Hz, 毫贝）。
     * 单段格式错误、频率越界（20..20000Hz）或增益越界（-15..15dB）返回 null。
     */
    fun parseBands(raw: String?): List<Pair<Int, Int>>? {
        if (raw.isNullOrBlank()) return null
        val out = mutableListOf<Pair<Int, Int>>()
        for (part in raw.split(',')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val hz = piece.substringBefore(':').trim().toIntOrNull()
                ?.takeIf { it in 20..20_000 } ?: return null
            val db = piece.substringAfter(':', "").trim().toDoubleOrNull()
                ?.takeIf { it in -15.0..15.0 } ?: return null
            out += hz to Math.round(db * 100).toInt()
        }
        return out.ifEmpty { null }
    }

    fun clampStrength(value: Int?): Int = (value ?: 500).coerceIn(0, 1000)

    fun clampVolume(value: Int?): Float = ((value ?: 100).coerceIn(0, 100)) / 100f

    /** 预设名 → Equalizer 预设索引；支持 "heavy_metal" 这类别名和数字索引。 */
    fun presetIndex(raw: String?, names: List<String>): Int? {
        if (raw.isNullOrBlank()) return null
        raw.trim().toIntOrNull()?.let { index -> return index.takeIf { it in names.indices } }
        val wanted = normalize(raw)
        if (wanted.isEmpty()) return null
        val exact = names.indexOfFirst { normalize(it) == wanted }
        if (exact >= 0) return exact
        return names.indexOfFirst {
            val n = normalize(it)
            n.isNotEmpty() && (n.contains(wanted) || wanted.contains(n))
        }.takeIf { it >= 0 }
    }

    /** 最近的均衡器频段索引；centersHz 为空时返回 0。 */
    fun nearestBand(centersHz: List<Int>, targetHz: Int): Int {
        if (centersHz.isEmpty()) return 0
        var best = 0
        var delta = Long.MAX_VALUE
        centersHz.forEachIndexed { index, hz ->
            val d = Math.abs(hz.toLong() - targetHz)
            if (d < delta) {
                delta = d
                best = index
            }
        }
        return best
    }

    fun reverbPresetId(name: String?): Short? = when (normalize(name)) {
        "none", "off" -> PresetReverb.PRESET_NONE
        "smallroom" -> PresetReverb.PRESET_SMALLROOM
        "mediumroom" -> PresetReverb.PRESET_MEDIUMROOM
        "largeroom" -> PresetReverb.PRESET_LARGEROOM
        "mediumhall" -> PresetReverb.PRESET_MEDIUMHALL
        "largehall" -> PresetReverb.PRESET_LARGEHALL
        "plate" -> PresetReverb.PRESET_PLATE
        else -> null
    }

    fun reverbPresetName(id: Short): String = when (id) {
        PresetReverb.PRESET_NONE -> "none"
        PresetReverb.PRESET_SMALLROOM -> "smallroom"
        PresetReverb.PRESET_MEDIUMROOM -> "mediumroom"
        PresetReverb.PRESET_LARGEROOM -> "largeroom"
        PresetReverb.PRESET_MEDIUMHALL -> "mediumhall"
        PresetReverb.PRESET_LARGEHALL -> "largehall"
        PresetReverb.PRESET_PLATE -> "plate"
        else -> "preset_$id"
    }

    fun focusGainType(name: String?): Int? = when (normalize(name ?: "gain")) {
        "gain", "permanent" -> AudioManager.AUDIOFOCUS_GAIN
        "gaintransient" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        "gaintransientmayduck", "mayduck" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        "gaintransientexclusive", "exclusive" -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        else -> null
    }

    fun gainTypeName(gain: Int): String = when (gain) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> "gain_transient"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> "gain_transient_may_duck"
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> "gain_transient_exclusive"
        else -> "gain_$gain"
    }

    fun focusChangeLabel(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
        else -> "change_$change"
    }

    fun focusRequestResult(code: Int): String = when (code) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
        else -> "result_$code"
    }

    fun usageValue(name: String?): Int = when (normalize(name ?: "media")) {
        "voicecommunication", "call" -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        "assistant" -> AudioAttributes.USAGE_ASSISTANT
        "game" -> AudioAttributes.USAGE_GAME
        "notification" -> AudioAttributes.USAGE_NOTIFICATION
        "alarm" -> AudioAttributes.USAGE_ALARM
        "navigation" -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        else -> AudioAttributes.USAGE_MEDIA
    }

    fun usageName(raw: String?): String = when (normalize(raw ?: "media")) {
        "voicecommunication", "call" -> "voice_communication"
        "assistant" -> "assistant"
        "game" -> "game"
        "notification" -> "notification"
        "alarm" -> "alarm"
        "navigation" -> "navigation"
        else -> "media"
    }

    /** MediaPlayer 播放的 AudioAttributes；空间化行为只在 API 32+ 存在。 */
    fun audioAttributes(spatial: String?): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        if (Build.VERSION.SDK_INT >= 32) {
            builder.setSpatializationBehavior(
                if (normalize(spatial) in setOf("never", "off", "none")) {
                    AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
                } else {
                    AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO
                },
            )
        }
        return builder.build()
    }
}

/** 一次播放的效果链参数；null 字段表示「沿用当前值」（action=set 时）。 */
data class AudioFxChain(
    val effects: Set<String>? = null,
    val eqPreset: String? = null,
    val eqBands: List<Pair<Int, Int>>? = null,
    val bassStrength: Int? = null,
    val reverbPreset: String? = null,
    val virtualizerStrength: Int? = null,
    val volume: Float? = null,
    val spatial: String? = null,
)

/* ------------------------------- 效果链 -------------------------------- */

private class FxHandles(val sessionId: Int) {
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var reverb: PresetReverb? = null
    var virtualizer: Virtualizer? = null

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { reverb?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        reverb = null
        virtualizer = null
    }
}

/**
 * 单例播放 + 效果链。播放不阻塞工具调用：play 立即返回会话信息，音频在后台继续，
 * 由 stop / 播放结束 / 音频焦点丢失收尾。
 */
object AudioFxEngine {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var handles: FxHandles? = null
    private var chain: AudioFxChain = AudioFxChain()
    private var baseVolume = 1f
    private var ducked = false
    private var pausedByFocus = false
    private var startedAtMs = 0L
    private var appContext: Context? = null

    fun isPlaying(): Boolean = synchronized(lock) {
        runCatching { player?.isPlaying == true }.getOrDefault(false)
    }

    fun play(
        context: Context,
        source: String,
        requested: AudioFxChain,
        loop: Boolean,
        volume: Float,
        focusNote: String? = null,
    ): String {
        val ctx = context.applicationContext
        synchronized(lock) { teardownLocked() }

        val file = if (source.startsWith("content://") || source.startsWith("file://")) null else File(source)
        if (file != null && !file.isFile) return "ERROR: 音频文件不存在或不是普通文件：$source"

        val mediaPlayer = if (Build.VERSION.SDK_INT >= 31) MediaPlayer(ctx) else @Suppress("DEPRECATION") MediaPlayer()
        try {
            mediaPlayer.setAudioAttributes(AudioFxRules.audioAttributes(requested.spatial))
            if (file != null) {
                mediaPlayer.setDataSource(file.absolutePath)
            } else {
                mediaPlayer.setDataSource(ctx, Uri.parse(source))
            }
            mediaPlayer.isLooping = loop
            mediaPlayer.setOnCompletionListener {
                synchronized(lock) { if (player === mediaPlayer) teardownLocked() }
                AudioFocusHub.releaseForPlayback(ctx)
            }
            mediaPlayer.prepare()
        } catch (e: Exception) {
            runCatching { mediaPlayer.release() }
            return "ERROR: 无法播放 $source（${e.message ?: e.javaClass.simpleName}）"
        }

        val sessionId = runCatching { mediaPlayer.audioSessionId }.getOrDefault(0)
        if (sessionId <= 0) {
            runCatching { mediaPlayer.release() }
            return "ERROR: 未能分配 audio session，无法挂载音效链"
        }

        val merged = requested.copy(
            effects = requested.effects ?: AudioFxRules.DEFAULT_EFFECTS,
            volume = volume.coerceIn(0f, 1f),
        )
        val errors = mutableListOf<String>()
        val newHandles = FxHandles(sessionId)
        attachEffects(newHandles, merged, errors)

        synchronized(lock) {
            player = mediaPlayer
            handles = newHandles
            chain = merged
            baseVolume = merged.volume ?: 1f
            ducked = false
            pausedByFocus = false
            startedAtMs = SystemClock.elapsedRealtime()
            appContext = ctx
            applyVolumeLocked()
        }
        try {
            mediaPlayer.start()
        } catch (e: Exception) {
            synchronized(lock) { teardownLocked() }
            return "ERROR: 播放启动失败（${e.message ?: e.javaClass.simpleName}）"
        }
        return buildJsonObject {
            put("status", "PLAYING")
            put("sessionId", sessionId)
            put("source", source)
            put("loop", loop)
            put("durationMs", runCatching { mediaPlayer.duration }.getOrDefault(-1))
            put("volume", Math.round((merged.volume ?: 1f) * 100))
            putJsonArray("effects") { currentEffectStatus(newHandles).forEach { add(it) } }
            if (focusNote != null) put("focus", focusNote)
            if (errors.isNotEmpty()) putJsonArray("warnings") { errors.forEach { add(it) } }
            put("hint", "音效挂在本次播放的 audio session 上；可用 action=set 实时调参，action=stop 停止")
        }.toString()
    }

    fun update(requested: AudioFxChain): String {
        val mp: MediaPlayer?
        val newHandles: FxHandles
        val merged: AudioFxChain
        val errors = mutableListOf<String>()
        synchronized(lock) {
            mp = player ?: return "ERROR: 当前没有正在播放的音频；先用 action=play 指定文件"
            val sessionId = handles?.sessionId ?: runCatching { mp.audioSessionId }.getOrDefault(0)
            if (sessionId <= 0) return "ERROR: 当前播放没有可用的 audio session"
            merged = merge(chain, requested)
            handles?.release()
            newHandles = FxHandles(sessionId)
            handles = newHandles
            chain = merged
            requested.volume?.let { baseVolume = it.coerceIn(0f, 1f) }
            applyVolumeLocked()
        }
        attachEffects(newHandles, merged, errors)
        if (!synchronized(lock) { handles === newHandles }) {
            newHandles.release()
            return "ERROR: 播放已在调参过程中停止"
        }
        return buildJsonObject {
            put("status", "UPDATED")
            put("sessionId", newHandles.sessionId)
            put("playing", runCatching { mp?.isPlaying == true }.getOrDefault(false))
            putJsonArray("effects") { currentEffectStatus(newHandles).forEach { add(it) } }
            if (requested.spatial != null) {
                put("spatialNote", "空间化行为在创建播放器时确定，spatial 修改需要重新 play 才生效")
            }
            if (errors.isNotEmpty()) putJsonArray("warnings") { errors.forEach { add(it) } }
        }.toString()
    }

    fun stop(reason: String = "user"): String {
        val (sessionId, released, ctx) = synchronized(lock) {
            val h = handles
            Triple(
                h?.sessionId ?: 0,
                buildList {
                    if (h?.equalizer != null) add("eq")
                    if (h?.bassBoost != null) add("bass")
                    if (h?.reverb != null) add("reverb")
                    if (h?.virtualizer != null) add("virtualizer")
                },
                appContext,
            ).also { teardownLocked() }
        }
        AudioFocusHub.releaseForPlayback(ctx)
        return buildJsonObject {
            put("status", "STOPPED")
            put("reason", reason)
            put("sessionId", sessionId)
            putJsonArray("released") { released.forEach { add(it) } }
        }.toString()
    }

    /** 焦点回调：被抢占时暂停/压低，恢复后回弹。 */
    fun onFocusChange(change: Int) {
        var stopNow = false
        synchronized(lock) {
            val mp = player ?: return
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> stopNow = true
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    runCatching { if (mp.isPlaying) mp.pause() }
                    pausedByFocus = true
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    applyVolumeLocked()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false
                    applyVolumeLocked()
                    if (pausedByFocus) runCatching { mp.start() }
                    pausedByFocus = false
                }
            }
        }
        if (stopNow) stop("focus_lost")
    }

    fun status(): String {
        val focusHeld = AudioFocusHub.isHeld()
        synchronized(lock) {
            val mp = player
            val h = handles
            return buildJsonObject {
                put("status", if (mp != null) "ACTIVE" else "IDLE")
                put("sessionId", h?.sessionId ?: 0)
                put("playing", runCatching { mp?.isPlaying == true }.getOrDefault(false))
                put("ducked", ducked)
                put("pausedByFocus", pausedByFocus)
                if (mp != null) {
                    put("positionMs", runCatching { mp.currentPosition }.getOrDefault(0))
                    put("durationMs", runCatching { mp.duration }.getOrDefault(-1))
                    put("elapsedMs", SystemClock.elapsedRealtime() - startedAtMs)
                    put("volume", Math.round(baseVolume * 100))
                }
                put("effectsRequested", chain.effects?.joinToString(",") ?: "")
                putJsonArray("effects") { h?.let { currentEffectStatus(it) }?.forEach { add(it) } }
                put("focusHeld", focusHeld)
            }.toString()
        }
    }

    /* ---------------------------- internals ---------------------------- */

    private fun merge(current: AudioFxChain, next: AudioFxChain): AudioFxChain = AudioFxChain(
        effects = next.effects ?: current.effects,
        eqPreset = next.eqPreset ?: current.eqPreset,
        eqBands = next.eqBands ?: current.eqBands,
        bassStrength = next.bassStrength ?: current.bassStrength,
        reverbPreset = next.reverbPreset ?: current.reverbPreset,
        virtualizerStrength = next.virtualizerStrength ?: current.virtualizerStrength,
        volume = next.volume ?: current.volume,
        spatial = next.spatial ?: current.spatial,
    )

    private fun attachEffects(target: FxHandles, wanted: AudioFxChain, errors: MutableList<String>) {
        val effects = wanted.effects ?: AudioFxRules.DEFAULT_EFFECTS
        if ("eq" in effects) {
            target.equalizer = runCatching { Equalizer(0, target.sessionId) }
                .onFailure { errors += "eq 不可用：${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }
        if ("bass" in effects) {
            target.bassBoost = runCatching { BassBoost(0, target.sessionId) }
                .onFailure { errors += "bass 不可用：${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }
        if ("reverb" in effects) {
            target.reverb = runCatching { PresetReverb(0, target.sessionId) }
                .onFailure { errors += "reverb 不可用：${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }
        if ("virtualizer" in effects) {
            target.virtualizer = runCatching { Virtualizer(0, target.sessionId) }
                .onFailure { errors += "virtualizer 不可用：${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
        }

        target.equalizer?.let { eq ->
            runCatching {
                wanted.eqPreset?.let { raw ->
                    val names = (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
                    val index = AudioFxRules.presetIndex(raw, names)
                    if (index != null) {
                        eq.usePreset(index.toShort())
                    } else {
                        errors += "eq 预设「$raw」不在设备预设中（可用：${names.joinToString("/")}）"
                    }
                }
                wanted.eqBands?.forEach { (hz, millibel) ->
                    val centers = (0 until eq.numberOfBands.toInt()).map { eq.getCenterFreq(it.toShort()) / 1000 }
                    val band = AudioFxRules.nearestBand(centers, hz).toShort()
                    val range = eq.bandLevelRange
                    eq.setBandLevel(band, millibel.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                }
                eq.enabled = true
            }.onFailure { errors += "eq 配置失败：${it.message ?: it.javaClass.simpleName}" }
        }

        target.bassBoost?.let { bass ->
            runCatching {
                if (bass.strengthSupported) {
                    bass.setStrength(AudioFxRules.clampStrength(wanted.bassStrength).toShort())
                }
                bass.enabled = true
            }.onFailure { errors += "bass 配置失败：${it.message ?: it.javaClass.simpleName}" }
        }

        target.reverb?.let { reverb ->
            runCatching {
                reverb.setPreset(
                    AudioFxRules.reverbPresetId(wanted.reverbPreset ?: "mediumroom")
                        ?: PresetReverb.PRESET_MEDIUMROOM,
                )
                reverb.enabled = true
            }.onFailure { errors += "reverb 配置失败：${it.message ?: it.javaClass.simpleName}" }
        }

        target.virtualizer?.let { virtualizer ->
            runCatching {
                virtualizer.setStrength(AudioFxRules.clampStrength(wanted.virtualizerStrength).toShort())
                virtualizer.enabled = true
            }.onFailure { errors += "virtualizer 配置失败：${it.message ?: it.javaClass.simpleName}" }
        }
    }

    private fun currentEffectStatus(h: FxHandles): kotlinx.serialization.json.JsonArray = buildJsonArray {
        h.equalizer?.let { eq ->
            add(buildJsonObject {
                put("name", "eq")
                put("hasControl", runCatching { eq.hasControl() }.getOrDefault(false))
                put("enabled", runCatching { eq.enabled }.getOrDefault(false))
                put("presetIndex", runCatching { eq.currentPreset.toInt() }.getOrDefault(-1))
                put("preset", runCatching { eq.getPresetName(eq.currentPreset) }.getOrDefault(""))
                put("numberOfBands", runCatching { eq.numberOfBands.toInt() }.getOrDefault(0))
                putJsonArray("bands") {
                    runCatching {
                        for (i in 0 until eq.numberOfBands.toInt()) {
                            add(buildJsonObject {
                                put("band", i)
                                put("centerHz", eq.getCenterFreq(i.toShort()) / 1000)
                                put("levelDb", eq.getBandLevel(i.toShort()) / 100.0)
                            })
                        }
                    }
                }
            })
        }
        h.bassBoost?.let { bass ->
            add(buildJsonObject {
                put("name", "bass")
                put("hasControl", runCatching { bass.hasControl() }.getOrDefault(false))
                put("enabled", runCatching { bass.enabled }.getOrDefault(false))
                put("strength", runCatching { bass.roundedStrength.toInt() }.getOrDefault(0))
                put("strengthSupported", runCatching { bass.strengthSupported }.getOrDefault(true))
            })
        }
        h.reverb?.let { reverb ->
            add(buildJsonObject {
                put("name", "reverb")
                put("hasControl", runCatching { reverb.hasControl() }.getOrDefault(false))
                put("enabled", runCatching { reverb.enabled }.getOrDefault(false))
                put("preset", runCatching { AudioFxRules.reverbPresetName(reverb.preset) }.getOrDefault(""))
            })
        }
        h.virtualizer?.let { virtualizer ->
            add(buildJsonObject {
                put("name", "virtualizer")
                put("hasControl", runCatching { virtualizer.hasControl() }.getOrDefault(false))
                put("enabled", runCatching { virtualizer.enabled }.getOrDefault(false))
                put("strength", runCatching { virtualizer.roundedStrength.toInt() }.getOrDefault(0))
            })
        }
    }

    private fun applyVolumeLocked() {
        val level = if (ducked) baseVolume * 0.2f else baseVolume
        runCatching { player?.setVolume(level, level) }
    }

    private fun teardownLocked() {
        runCatching { player?.stop() }
        handles?.release()
        runCatching { player?.release() }
        player = null
        handles = null
        ducked = false
        pausedByFocus = false
        startedAtMs = 0L
    }
}

/* ------------------------------ 音频焦点 ------------------------------- */

private data class FocusEvent(val label: String, val detail: String, val atMs: Long)

/** AudioFocusRequest 的一次申请结果。 */
class AudioFocusOutcome(val granted: Boolean, val json: String)

/** AudioFocusRequest 的持有者；记录每次回调，供 status 复盘「抢占/让出」。 */
object AudioFocusHub {
    private const val MAX_EVENTS = 24
    private const val OWNER_PLAYBACK = "audio_effect"
    private val lock = Any()
    private val events = ArrayDeque<FocusEvent>(MAX_EVENTS)
    private var request: AudioFocusRequest? = null
    private var gainType = 0
    private var requestedAtMs = 0L
    private var owner: String? = null
    private var lastResult: String = "idle"

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        record(AudioFxRules.focusChangeLabel(change), "system", SystemClock.elapsedRealtime())
        AudioFxEngine.onFocusChange(change)
    }

    fun isHeld(): Boolean = synchronized(lock) { request != null }

    fun request(
        context: Context,
        gainName: String?,
        usageName: String?,
        pauseOnDuck: Boolean,
        requester: String,
    ): AudioFocusOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AudioFocusOutcome(false, "ERROR: 音频服务不可用")
        val gain = AudioFxRules.focusGainType(gainName)
            ?: return AudioFocusOutcome(
                false,
                "ERROR: gain 仅支持 gain|gain_transient|gain_transient_may_duck|gain_transient_exclusive",
            )
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioFxRules.usageValue(usageName))
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = runCatching {
            AudioFocusRequest.Builder(gain)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(pauseOnDuck)
                .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
                .build()
        }.getOrElse {
            return AudioFocusOutcome(
                false,
                "ERROR: 构造 AudioFocusRequest 失败（${it.message ?: it.javaClass.simpleName}）",
            )
        }

        val previous = synchronized(lock) {
            val old = request
            request = null
            owner = null
            old
        }
        previous?.let { runCatching { am.abandonAudioFocusRequest(it) } }

        val code = runCatching { am.requestAudioFocus(req) }.getOrElse {
            return AudioFocusOutcome(
                false,
                "ERROR: 申请音频焦点失败（${it.message ?: it.javaClass.simpleName}）",
            )
        }
        val result = AudioFxRules.focusRequestResult(code)
        val granted = code == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            synchronized(lock) {
                request = req
                gainType = gain
                requestedAtMs = SystemClock.elapsedRealtime()
                owner = requester
                lastResult = result
            }
        }
        record("request_${AudioFxRules.gainTypeName(gain)}", "app($requester)", SystemClock.elapsedRealtime())
        val json = buildJsonObject {
            put("status", if (granted) "GRANTED" else "NOT_GRANTED")
            put("result", result)
            put("gain", AudioFxRules.gainTypeName(gain))
            put("usage", AudioFxRules.usageName(usageName))
            put("pauseOnDuck", pauseOnDuck)
            put("owner", if (granted) requester else "")
            put("playbackActive", AudioFxEngine.isPlaying())
            putJsonArray("events") { eventSnapshot() }
            if (!granted) {
                put(
                    "hint",
                    "系统未授予焦点：可能被通话/其他播放器占用或被延迟；可等对方让出后重试，不要谎称已获得焦点",
                )
            }
        }.toString()
        return AudioFocusOutcome(granted, json)
    }

    /**
     * 让出焦点。requester 非空时只让出自己持有的请求（播放结束自动调用），
     * 这样手动 request 的焦点不会被正在停止的播放器顺手释放。
     */
    fun abandon(context: Context?, requester: String? = null): String {
        val held: AudioFocusRequest?
        val heldOwner: String?
        synchronized(lock) {
            held = request
            heldOwner = owner
        }
        if (held == null) {
            return buildJsonObject {
                put("status", "IDLE")
                put("note", "当前没有持有的音频焦点")
                putJsonArray("events") { eventSnapshot() }
            }.toString()
        }
        if (requester != null && heldOwner != null && heldOwner != requester) {
            return buildJsonObject {
                put("status", "NOT_ABANDONED")
                put("owner", heldOwner)
                put("note", "焦点由 $heldOwner 持有，未替它让出")
                putJsonArray("events") { eventSnapshot() }
            }.toString()
        }
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val code = if (am == null) {
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            runCatching { am.abandonAudioFocusRequest(held) }
                .getOrElse { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        }
        val released = code == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        synchronized(lock) {
            request = null
            gainType = 0
            requestedAtMs = 0L
            owner = null
            lastResult = if (released) "released" else "abandon_failed"
        }
        if (released) {
            record("abandon", "app(${heldOwner ?: "manual"})", SystemClock.elapsedRealtime())
        }
        return buildJsonObject {
            put("status", if (released) "RELEASED" else "RELEASED_LOCAL")
            put("result", AudioFxRules.focusRequestResult(code))
            if (!released) put("note", "系统未确认让出（本地状态已清除）")
            putJsonArray("events") { eventSnapshot() }
            put("playbackActive", AudioFxEngine.isPlaying())
        }.toString()
    }

    /** 播放停止时只释放播放器自己申请的焦点。 */
    fun releaseForPlayback(context: Context?) {
        val held = synchronized(lock) { request != null && owner == OWNER_PLAYBACK }
        if (!held) return
        abandon(context, OWNER_PLAYBACK)
    }

    fun status(): String {
        val now = SystemClock.elapsedRealtime()
        val playback = AudioFxEngine.isPlaying()
        synchronized(lock) {
            return buildJsonObject {
                put("status", if (request != null) "HELD" else "IDLE")
                put("held", request != null)
                put("gain", if (request != null) AudioFxRules.gainTypeName(gainType) else "")
                put("owner", owner ?: "")
                put("heldMs", if (request != null) now - requestedAtMs else 0L)
                put("lastResult", lastResult)
                put("playbackActive", playback)
                putJsonArray("events") { eventSnapshot() }
                put(
                    "note",
                    "events 记录系统回调：loss* 表示被其他 App/通话抢占（播放自动暂停或压低音量），gain 表示恢复",
                )
            }.toString()
        }
    }

    private fun record(label: String, detail: String, atMs: Long) {
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(FocusEvent(label, detail, atMs))
        }
    }

    private fun JsonArrayBuilder.eventSnapshot() {
        synchronized(lock) {
            val origin = events.firstOrNull()?.atMs ?: 0L
            events.forEach { e ->
                add(buildJsonObject {
                    put("event", e.label)
                    put("detail", e.detail)
                    put("atMs", if (origin > 0) e.atMs - origin else e.atMs)
                })
            }
        }
    }
}

/* ------------------------------- 工具 --------------------------------- */

/** audio_effect：MediaPlayer + 效果链（EQ/Bass/Reverb/Virtualizer）。 */
class AudioEffectTool(private val context: Context) : Tool {
    override val name = "audio_effect"
    override val description =
        "播放本地音频并挂载真实音效链：均衡器（预设或逐频段增益）、低音增强、混响、虚拟环绕。" +
            "音效绑定本次播放的 audio session，不影响其他应用；播放期间可 action=set 实时调参，" +
            "action=stop 停止，action=list 查看设备支持的音效与预设。"
    override val parameters = schemaOf(
        "action" to strParam("status（默认）| list | play | set | stop"),
        "path" to strParam("action=play 必填：本地音频绝对路径或 content:// URI（如录音产物）"),
        "effects" to strParam("逗号分隔的启用项：eq,bass,reverb,virtualizer，play 默认 eq,bass"),
        "eq_preset" to strParam("均衡器预设：flat/rock/pop/jazz/classical/dance/folk/heavy_metal/hip_hop/normal，或预设索引"),
        "eq_bands" to strParam("逐频段增益：60:4,230:-2（Hz:dB，20-20000Hz，-15..15dB）"),
        "bass_strength" to intParam("低音强度 0..1000，默认 500"),
        "reverb_preset" to strParam("混响预设：none/smallroom/mediumroom/largeroom/mediumhall/largehall/plate，默认 mediumroom"),
        "virtualizer_strength" to intParam("虚拟环绕强度 0..1000，默认 500"),
        "volume" to intParam("播放音量百分比 0..100，默认 100"),
        "loop" to boolParam("循环播放，默认 false"),
        "spatial" to strParam("空间化行为：auto（默认，交给系统）| never（禁止上混，API 32+）"),
        "request_focus" to boolParam("播放时申请音频焦点，默认 true"),
    )
    override val destructive: Boolean = true

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        when ((args.strArg("action") ?: "status").lowercase()) {
            "status" -> AudioFxEngine.status()
            "list" -> effectCatalog()
            "stop" -> AudioFxEngine.stop()
            "play" -> {
                val source = args.strArg("path") ?: return@withContext "ERROR: action=play 需要 path"
                val (chain, error) = parseChain(args)
                if (error != null) return@withContext "ERROR: $error"
                if (chain.spatial != null &&
                    AudioFxRules.normalize(chain.spatial) !in setOf("auto", "never", "off", "none")
                ) {
                    return@withContext "ERROR: spatial 仅支持 auto 或 never"
                }
                var focusNote: String? = null
                if (args.boolArg("request_focus") ?: true) {
                    val outcome = AudioFocusHub.request(
                        context,
                        gainName = "gain",
                        usageName = "media",
                        pauseOnDuck = false,
                        requester = "audio_effect",
                    )
                    if (!outcome.granted) focusNote = "未获得音频焦点；播放继续但可能被其他声音抢占"
                }
                val played = AudioFxEngine.play(
                    context,
                    source,
                    chain,
                    loop = args.boolArg("loop") ?: false,
                    volume = AudioFxRules.clampVolume(args.intArg("volume")),
                    focusNote = focusNote,
                )
                // 播放没起来就不要留着焦点（文件不存在 / 解码失败 / 启动异常）。
                if (played.startsWith("ERROR")) AudioFocusHub.releaseForPlayback(context)
                played
            }
            "set" -> {
                val (chain, error) = parseChain(args)
                if (error != null) return@withContext "ERROR: $error"
                AudioFxEngine.update(chain)
            }
            else -> "ERROR: action 仅支持 status|list|play|set|stop"
        }
    }

    /** chain + error；error 非空表示参数非法。 */
    private fun parseChain(args: JsonObject): Pair<AudioFxChain, String?> {
        val effectsRaw = args.strArg("effects")
        val effects = AudioFxRules.parseEffects(effectsRaw)
            ?: return AudioFxChain() to "effects 仅支持 eq,bass,reverb,virtualizer 的组合"
        val bandsRaw = args.strArg("eq_bands")
        val bands = AudioFxRules.parseBands(bandsRaw)
        if (bandsRaw != null && bands == null) {
            return AudioFxChain() to "eq_bands 格式应为 60:4,230:-2（Hz:dB，-15..15dB）"
        }
        val reverb = args.strArg("reverb_preset")
        if (reverb != null && AudioFxRules.reverbPresetId(reverb) == null) {
            return AudioFxChain() to "reverb_preset 仅支持 ${AudioFxRules.REVERB_PRESETS.joinToString("|")}"
        }
        return AudioFxChain(
            effects = if (effectsRaw == null) null else effects,
            eqPreset = args.strArg("eq_preset"),
            eqBands = bands,
            bassStrength = args.intArg("bass_strength"),
            reverbPreset = reverb,
            virtualizerStrength = args.intArg("virtualizer_strength"),
            volume = args.intArg("volume")?.let { AudioFxRules.clampVolume(it) },
            spatial = args.strArg("spatial"),
        ) to null
    }

    private fun effectCatalog(): String {
        val descriptors = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { AudioEffect.queryEffects().toList() }.getOrNull()
        } else null
        val presets = runCatching {
            val eq = Equalizer(0, 0)
            try {
                (0 until eq.numberOfPresets.toInt()).map { eq.getPresetName(it.toShort()) }
            } finally {
                runCatching { eq.release() }
            }
        }.getOrNull()
        return buildJsonObject {
            put("status", "OK")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("querySupported", descriptors != null)
            if (descriptors == null) {
                put("note", "AudioEffect.queryEffects() 需要 Android 9+ 与 MODIFY_AUDIO_SETTINGS；当前不可用")
            }
            putJsonArray("effects") {
                descriptors.orEmpty().forEach { d ->
                    add(buildJsonObject {
                        put("name", d.name)
                        put("implementor", d.implementor)
                        put("uuid", d.uuid.toString())
                        put("type", d.type.toString())
                        put("connectMode", d.connectMode)
                    })
                }
            }
            putJsonArray("eqPresets") { (presets ?: AudioFxRules.DEFAULT_EQ_PRESETS).forEach { add(it) } }
            putJsonArray("reverbPresets") { AudioFxRules.REVERB_PRESETS.forEach { add(it) } }
            put("playing", AudioFxEngine.isPlaying())
            put("hint", "eq 预设名由设备实现提供，列表仅供参考；status 里的 preset 才是实际值")
        }.toString()
    }
}

/** audio_focus：申请/让出/查看音频焦点。 */
class AudioFocusTool(private val context: Context) : Tool {
    override val name = "audio_focus"
    override val description =
        "音频焦点管理：request 申请焦点（永久/短暂/允许 duck/独占），abandon 主动让出，status 查看持有者与最近回调。" +
            "被通话或其他播放器抢占时，本应用播放会自动暂停（LOSS_TRANSIENT）或压低音量（CAN_DUCK），恢复后回弹。"
    override val parameters = schemaOf(
        "action" to strParam("status（默认）| request | abandon"),
        "gain" to strParam("request 时的焦点类型：gain（默认）| gain_transient | gain_transient_may_duck | gain_transient_exclusive"),
        "usage" to strParam("用途：media（默认）| voice_communication | assistant | game | notification | alarm | navigation"),
        "pause_on_duck" to boolParam("被 duck 时改为暂停，默认 false"),
    )
    override val destructive: Boolean = true

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        when ((args.strArg("action") ?: "status").lowercase()) {
            "status" -> AudioFocusHub.status()
            "request" -> AudioFocusHub.request(
                context,
                gainName = args.strArg("gain"),
                usageName = args.strArg("usage"),
                pauseOnDuck = args.boolArg("pause_on_duck") ?: false,
                requester = "manual",
            ).json
            "abandon" -> AudioFocusHub.abandon(context)
            else -> "ERROR: action 仅支持 status|request|abandon"
        }
    }
}

/** spatial_audio：Android 12+ Spatializer 能力探测与开关。 */
class SpatialAudioTool(private val context: Context) : Tool {
    override val name = "spatial_audio"
    override val description =
        "空间音频（Android 12 / API 32 Spatializer）：查看设备是否支持、系统是否开启、沉浸音频等级与兼容输出设备；" +
            "enable/disable 请求开启或关闭空间化。普通应用可能因缺少 MODIFY_AUDIO_ROUTING 只能读状态，工具会如实标注 RESTRICTED。"
    override val parameters = schemaOf(
        "action" to strParam("status（默认）| enable | disable"),
    )
    override val destructive: Boolean = true

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 32) {
            return@withContext buildJsonObject {
                put("status", "UNSUPPORTED")
                put("sdkInt", Build.VERSION.SDK_INT)
                put("reason", "Spatializer 需要 Android 12（API 32）及以上")
                put("hint", "系统「设置 → 声音与振动」里可能仍有厂商自带的空间音频开关，但应用层无法通过 Spatializer API 控制")
            }.toString()
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@withContext "ERROR: 音频服务不可用"
        val result = runCatching { am.spatializer }
        val spatializer = result.getOrNull()
        if (spatializer == null) {
            val error = result.exceptionOrNull()
            return@withContext buildJsonObject {
                put("status", "RESTRICTED")
                put("sdkInt", Build.VERSION.SDK_INT)
                put("reason", "无法获取 Spatializer：${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}")
                put(
                    "hint",
                    "AudioManager.getSpatializer() 在部分版本要求 MODIFY_AUDIO_ROUTING（系统签名权限）。" +
                        "普通应用仍可通过 AudioAttributes 的空间化行为让系统决定是否上混",
                )
            }.toString()
        }

        val action = (args.strArg("action") ?: "status").lowercase()
        var applied: Boolean? = null
        var actionNote: String? = null
        when (action) {
            "status" -> Unit
            "enable", "disable" -> {
                val attempt = setEnabledByReflection(spatializer, action == "enable")
                applied = attempt.first
                actionNote = attempt.second
            }
            else -> return@withContext "ERROR: action 仅支持 status|enable|disable"
        }

        val available = runCatching { spatializer.isAvailable }.getOrDefault(false)
        val enabled = runCatching { spatializer.isEnabled }.getOrDefault(false)
        val immersive = runCatching { spatializer.immersiveAudioLevel }.getOrDefault(-1)
        val headTracking = runCatching { spatializer.isHeadTrackerAvailable }.getOrDefault(false)
        val attributes = AudioFxRules.audioAttributes("auto")
        val stereo = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val canBeSpatialized = runCatching {
            spatializer.canBeSpatialized(attributes, stereo)
        }.getOrDefault(false)
        val outputs = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        buildJsonObject {
            put("status", "OK")
            put("sdkInt", Build.VERSION.SDK_INT)
            put("available", available)
            put("enabled", enabled)
            put("immersiveLevel", immersiveLevelName(immersive))
            put("headTrackerAvailable", headTracking)
            put("canBeSpatialized", canBeSpatialized)
            if (actionNote != null) {
                put("action", action)
                put("applied", applied ?: false)
                put("actionNote", actionNote)
            }
            put("playbackActive", AudioFxEngine.isPlaying())
            putJsonArray("outputs") {
                outputs.forEach { info ->
                    add(buildJsonObject {
                        put("type", info.type)
                        put("typeName", deviceTypeName(info.type))
                        put("name", info.productName?.toString() ?: "")
                    })
                }
            }
            if (!available) {
                put(
                    "note",
                    "设备没有可用的空间化实现（或当前输出设备不支持）：不要声称已开启空间音频，可提示用户换耳机/在系统设置查看",
                )
            }
            if (action != "status") {
                put(
                    "shellNote",
                    "Spatializer 的开启/关闭在公开 SDK 中不可用（setEnabled 属 @SystemApi，需 MODIFY_AUDIO_ROUTING）；" +
                        "这里做反射尝试，失败时只能如实报告状态",
                )
            }
        }.toString()
    }

    /**
     * 公开 android.jar 的 Spatializer 只有 isEnabled/isAvailable/canBeSpatialized 等查询方法，
     * 没有 setEnabled（@SystemApi）。这里尽力反射；失败就如实说明，不假装切换成功。
     */
    private fun setEnabledByReflection(spatializer: Spatializer, enabled: Boolean): Pair<Boolean, String> =
        runCatching {
            val method = Spatializer::class.java.getDeclaredMethod("setEnabled", java.lang.Boolean.TYPE)
            method.isAccessible = true
            val result = method.invoke(spatializer, enabled) as? Boolean ?: false
            if (result) {
                true to if (enabled) "已请求开启空间化" else "已请求关闭空间化"
            } else {
                false to "系统拒绝该请求：可能空间音频总开关未开、当前输出设备不支持，或应用缺少 MODIFY_AUDIO_ROUTING"
            }
        }.getOrElse {
            false to "公开 SDK 没有 Spatializer.setEnabled，反射调用失败" +
                "（${it.message ?: it.javaClass.simpleName}）；只能读状态，开启/关闭请走系统设置里的厂商空间音频开关"
        }

    private fun immersiveLevelName(level: Int): String = when (level) {
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE -> "none"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_MULTICHANNEL -> "multichannel"
        Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_OTHER -> "other"
        -1 -> "unknown"
        else -> "level_$level"
    }

    /** AudioDeviceAttributes.type 与 AudioDeviceInfo.type 共用同一套常量值。 */
    private fun deviceTypeName(type: Int): String = when (type) {
        1 -> "earpiece"
        2 -> "speaker"
        3 -> "wired_headset"
        4 -> "wired_headphones"
        7 -> "bluetooth_sco"
        8 -> "bluetooth_a2dp"
        11 -> "usb_device"
        15 -> "builtin_mic"
        22 -> "usb_headset"
        23 -> "hearing_aid"
        26 -> "ble_headset"
        27 -> "ble_speaker"
        else -> "type_$type"
    }
}

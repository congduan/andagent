package com.dc.andagent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.MemoryFile
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * R84 — 传感器与机身状态刀（硬件暴露补全 ①）。
 *
 *  a) sensor_stream     高频连续采样（默认 200Hz，最多 500Hz），多传感器并行落 CSV，
 *                       附统计量 + 振动强度判定（振动检测/姿态监测的数据底座）
 *  b) sensor_batch      硬件 FIFO batching（maxReportLatency），低功耗采长序列
 *  c) heart_rate        TYPE_HEART_RATE（穿戴/带背光心率模组的机型）
 *  d) significant_motion 运动触发传感器（省电，一次唤醒一次事件）
 *  e) thermal_status    PowerManager 温控等级 + /sys/class/thermal 温区 + 电池温度 + 每核占用
 *  f) battery_electrical BatteryManager 电流/电压/功率实时读数
 *  g) vibrate_waveform  API 26 amplitude 波形触感（内置 5 组预设）
 *  h) audio_record_ex   录音源/采样率/声道参数化（wav 可测电平）
 *  i) audio_route       扬声器/听筒/蓝牙/有线输出切换
 *
 * 与既有工具的分工：sensor_read/sensor_record(R35) 面向低频单点/单传感器，
 * 本刀面向高频、多传感器、批量与机身电气量；mic_record 仍是固定 16k 单声道快捷入口。
 */
val HARDWARE_STATUS_TOOLS = listOf(
    "sensor_stream",
    "sensor_batch",
    "heart_rate",
    "significant_motion",
    "thermal_status",
    "battery_electrical",
    "vibrate_waveform",
    "audio_record_ex",
    "audio_route",
)

/* ---------------------------- pure helpers ---------------------------- */

/** Numeric summary of one sensor channel (JVM-testable). */
object SensorStats {
    data class Summary(
        val count: Int,
        val mean: Double,
        val std: Double,
        val min: Double,
        val max: Double,
        val rms: Double,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("count", count)
            put("mean", round(mean))
            put("std", round(std))
            put("min", round(min))
            put("max", round(max))
            put("rms", round(rms))
        }

        private fun round(v: Double): Double = if (v.isNaN() || v.isInfinite()) 0.0
        else Math.round(v * 1000.0) / 1000.0
    }

    fun of(values: List<Float>): Summary {
        if (values.isEmpty()) return Summary(0, 0.0, 0.0, 0.0, 0.0, 0.0)
        var sum = 0.0
        var sq = 0.0
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (v in values) {
            val d = v.toDouble()
            sum += d
            sq += d * d
            if (d < min) min = d
            if (d > max) max = d
        }
        val n = values.size
        val mean = sum / n
        var variance = 0.0
        for (v in values) {
            val d = v - mean
            variance += d * d
        }
        return Summary(n, mean, sqrt(variance / n), min, max, sqrt(sq / n))
    }

    fun magnitude(x: Float, y: Float, z: Float): Double =
        sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)

    fun rateHz(count: Int, durationMs: Long): Double =
        if (durationMs <= 0) 0.0 else Math.round(count * 100_000.0 / durationMs) / 100.0

    /**
     * R146 必修 2：按「样本间隔数」折算实测采样率（纯函数，可单测）。
     * count<=1 或窗口非正时返回 0.0（无间隔可计，不虚报、无除零）；否则以 count-1 个
     * 间隔除以首末事件窗口 spanMs，避免 count/span 把 10 个等间隔 50Hz 样本少算成 55.56Hz。
     * count 为该传感器事件总数（values 可能被 MAX_STREAM_ROWS 截断，但 count 准确）。
     */
    fun rateHzFromSpan(count: Int, spanMs: Long): Double =
        if (count <= 1 || spanMs <= 0) 0.0 else (count - 1) * 1000.0 / spanMs

    /**
     * R146 二轮必修 B：窗口以纳秒原值到除法折算实测采样率（纯函数，可单测）。
     * 旧 [rateHzFromSpan] 先把纳秒差整除 1e6 成毫秒，会在亚毫秒窗口丢精度甚至归零
     * （0.8ms→0ms 报 0Hz、1.9ms→1ms 报 1000Hz）。本函数直接收 spanNs，保留全部精度。
     * count<=1 或窗口非正时返回 0.0（无间隔可计，不虚报、无除零）。
     * channel 模式的 perSensor 窗口用此函数；全局 windowMs 输出仍可用毫秒（仅展示）。
     */
    fun rateHzFromSpanNs(count: Int, spanNs: Long): Double =
        if (count <= 1 || spanNs <= 0) 0.0 else (count - 1) * 1e9 / spanNs
}

/**
 * Vibration/shake verdict from the magnitude trace of a 3-axis motion sensor.
 * The magnitude keeps gravity in the baseline, so the deviation from the mean
 * is a cheap high-pass proxy: still ≈ 0, walking ≈ 0.3–2, shaking ≫ 2.
 */
object SensorVibration {
    data class Verdict(
        val rmsDeviation: Double,
        val peakCount: Int,
        val peakRateHz: Double,
        val label: String,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("rmsDeviation", Math.round(rmsDeviation * 1000.0) / 1000.0)
            put("peakCount", peakCount)
            put("peakRateHz", Math.round(peakRateHz * 100.0) / 100.0)
            put("verdict", label)
        }
    }

    fun detect(magnitudes: List<Double>, durationMs: Long): Verdict? {
        if (magnitudes.size < 4 || durationMs <= 0) return null
        val mean = magnitudes.average()
        var sq = 0.0
        for (m in magnitudes) {
            val d = m - mean
            sq += d * d
        }
        val rms = sqrt(sq / magnitudes.size)
        // A local maximum only counts when its deviation is meaningful relative
        // to the trace's own energy (half-RMS keeps square-ish shakes detected
        // while ignoring sensor noise on a still device).
        val threshold = maxOf(0.15, rms * 0.5)
        var peaks = 0
        for (i in 1 until magnitudes.size - 1) {
            val m = magnitudes[i]
            if (m > magnitudes[i - 1] && m >= magnitudes[i + 1] && abs(m - mean) > threshold) peaks++
        }
        val label = when {
            rms < 0.15 -> "still"
            rms < 1.0 -> "light"
            rms < 3.0 -> "moderate"
            else -> "strong"
        }
        return Verdict(rms, peaks, peaks * 1000.0 / durationMs, label)
    }
}

/** Amplitude-waveform validation and presets (pure, unit-testable). */
object WaveformSpec {
    const val MAX_TOTAL_MS = 10_000L
    const val MAX_STEPS = 64

    private val PRESETS: Map<String, Pair<LongArray, IntArray>> = mapOf(
        "tick" to (longArrayOf(0, 12) to intArrayOf(0, 180)),
        "double_click" to (longArrayOf(0, 18, 70, 18) to intArrayOf(0, 200, 0, 160)),
        "heartbeat" to (
            longArrayOf(0, 40, 60, 30, 220, 40, 60, 30) to
                intArrayOf(0, 200, 0, 120, 0, 200, 0, 120)
            ),
        "escalation" to (
            longArrayOf(0, 40, 40, 60, 40, 90, 40, 140) to
                intArrayOf(0, 60, 0, 120, 0, 180, 0, 255)
            ),
        "sos" to (
            longArrayOf(0, 60, 80, 60, 80, 60, 200, 120, 80, 120, 80, 120) to
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
            ),
    )

    fun presetNames(): List<String> = PRESETS.keys.sorted()

    fun preset(name: String): Pair<LongArray, IntArray>? = PRESETS[name.lowercase()]

    fun parseTimings(raw: String?): LongArray? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',').map { it.trim() }
        if (parts.isEmpty()) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        return values.toLongArray()
    }

    fun parseAmplitudes(raw: String?): IntArray? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val values = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        return values.toIntArray()
    }

    /** null → valid; otherwise a human-readable error. */
    fun validate(timings: LongArray, amplitudes: IntArray?): String? {
        if (timings.isEmpty()) return "波形不能为空"
        if (timings.size > MAX_STEPS) return "波形步数上限 $MAX_STEPS"
        if (timings.any { it < 0 }) return "波形时长不能为负"
        if (timings.sum() > MAX_TOTAL_MS) return "波形总时长上限 ${MAX_TOTAL_MS}ms"
        if (amplitudes != null) {
            if (amplitudes.size != timings.size) return "amplitudes 数量必须与 timings 一致"
            if (amplitudes.any { it !in 0..255 }) return "振幅须在 0..255"
        }
        return null
    }

    fun repeatValue(raw: Int?, size: Int): Int {
        val r = raw ?: -1
        return if (r in -1 until size) r else -1
    }

    fun totalMs(timings: LongArray): Long = timings.sum()
}

/** Generic PCM WAV header (pure). [pcmBytes] is the data-chunk length. */
object PcmWav {
    fun header(sampleRate: Int, channels: Int, bitsPerSample: Int, dataBytes: Long): ByteArray {
        require(sampleRate > 0) { "采样率必须为正" }
        require(channels in 1..2) { "声道数只支持 1/2" }
        require(bitsPerSample == 8 || bitsPerSample == 16) { "位深只支持 8/16" }
        require(dataBytes in 0..0xFFFF_FFFFL - 36) { "PCM 数据超出 WAV 4GB 上限" }
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt((dataBytes + 36).toInt())
        b.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(byteRate)
        b.putShort((channels * bitsPerSample / 8).toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes.toInt())
        return b.array()
    }
}

/** Peak/RMS level of 16-bit little-endian PCM, in dBFS (pure). */
object PcmLevel {
    data class Level(val peakDbfs: Double, val rmsDbfs: Double, val samples: Long)

    class Accumulator {
        private var peak = 0
        private var sumSquares = 0.0
        private var count = 0L

        fun add(bytes: ByteArray, length: Int = bytes.size) {
            var i = 0
            val end = minOf(length, bytes.size) - 1
            while (i < end) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                val sample = (hi shl 8) or lo
                val a = abs(sample)
                if (a > peak) peak = a
                sumSquares += sample.toDouble() * sample
                count++
                i += 2
            }
        }

        fun result(): Level {
            if (count == 0L) return Level(-120.0, -120.0, 0)
            return Level(dbfs(peak.toDouble()), dbfs(sqrt(sumSquares / count)), count)
        }

        private fun dbfs(amplitude: Double): Double =
            if (amplitude <= 0.0) -120.0 else maxOf(-120.0, 20.0 * log10(amplitude / 32768.0))
    }

    fun measure(pcm: ByteArray): Level = Accumulator().apply { add(pcm) }.result()
}

/** /sys/class/thermal zone parsing (pure). */
object ThermalZone {
    data class Zone(val name: String, val celsius: Double) {
        fun toJson(): JsonObject = buildJsonObject {
            put("name", name)
            put("celsius", Math.round(celsius * 10.0) / 10.0)
        }
    }

    fun cleanName(raw: String): String =
        raw.trim().replace(Regex("[\\u0000-\\u001F\\u007F]"), "").take(40)

    /**
     * Kernel thermal zones report millidegrees Celsius ("42500"), but a few
     * vendor drivers report whole degrees ("42"). Values ≥ 1000 are scaled.
     */
    fun parse(name: String, raw: String): Zone? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        val celsius = if (abs(v) >= 1000.0) v / 1000.0 else v
        if (celsius !in -40.0..150.0) return null
        val clean = cleanName(name).ifBlank { "zone" }
        return Zone(clean, celsius)
    }
}

/** /proc/stat parsing and busy-percent deltas (pure). */
object ProcStat {
    data class Core(val name: String, val total: Long, val idle: Long)

    fun parse(text: String): List<Core> = text.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("cpu") && !it.startsWith("cpu ") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 5) return@mapNotNull null
            val values = parts.drop(1).map { it.toLongOrNull() ?: return@mapNotNull null }
            val total = values.sum()
            // idle = idle + iowait
            val idle = values[3] + (values.getOrNull(4) ?: 0L)
            Core(parts[0], total, idle)
        }
        .toList()

    fun busyPercent(prev: List<Core>, next: List<Core>): List<Pair<String, Double>> {
        if (prev.isEmpty() || next.isEmpty()) return emptyList()
        val before = prev.associateBy { it.name }
        return next.mapNotNull { cur ->
            val old = before[cur.name] ?: return@mapNotNull null
            val dTotal = cur.total - old.total
            val dIdle = cur.idle - old.idle
            if (dTotal <= 0) return@mapNotNull null
            cur.name to Math.round((dTotal - dIdle) * 1000.0 / dTotal) / 10.0
        }
    }
}

/** Battery current/voltage normalisation (pure). */
object BatteryMath {
    const val MAX_PLAUSIBLE_MA = 15_000.0

    /** Some OEMs report µA, others mA. Values ≥ 20000 are treated as µA. */
    fun currentMa(raw: Long?): Pair<Double?, String> {
        if (raw == null) return null to "unavailable"
        val micro = abs(raw) >= 20_000
        val ma = if (micro) raw / 1000.0 else raw.toDouble()
        if (abs(ma) > MAX_PLAUSIBLE_MA) return null to "implausible:$raw"
        return Math.round(ma * 10.0) / 10.0 to if (micro) "ua" else "ma"
    }

    /** Watts = A × V (current is mA, voltage is V); magnitude only. */
    fun watts(currentMa: Double?, voltageV: Double?): Double? {
        if (currentMa == null || voltageV == null) return null
        return Math.round(abs(currentMa) * voltageV * 10.0) / 10_000.0
    }
}

/* ------------------------------- shared ------------------------------- */

private data class SensorKind(val type: Int, val label: String, val axes: Int)

private class StreamChannel(val key: String, val label: String, val axes: Int) {
    // Written by the sensor handler thread, read after unregister on the caller
    // thread — both collections stay synchronized so a late callback can never
    // race the summary pass.
    val values: MutableList<FloatArray> = java.util.Collections.synchronizedList(mutableListOf())
    val count = java.util.concurrent.atomic.AtomicInteger(0)
}

private class StreamTarget(val channel: StreamChannel, val sensor: Sensor)

private val SENSOR_KINDS: Map<String, SensorKind> = linkedMapOf(
    "accelerometer" to SensorKind(Sensor.TYPE_ACCELEROMETER, "加速度计", 3),
    "linear_acceleration" to SensorKind(Sensor.TYPE_LINEAR_ACCELERATION, "线性加速度", 3),
    "gravity" to SensorKind(Sensor.TYPE_GRAVITY, "重力", 3),
    "gyroscope" to SensorKind(Sensor.TYPE_GYROSCOPE, "陀螺仪", 3),
    "magnetometer" to SensorKind(Sensor.TYPE_MAGNETIC_FIELD, "磁力计", 3),
    "rotation_vector" to SensorKind(Sensor.TYPE_ROTATION_VECTOR, "旋转矢量", 5),
    "light" to SensorKind(Sensor.TYPE_LIGHT, "环境光", 1),
    "pressure" to SensorKind(Sensor.TYPE_PRESSURE, "气压计", 1),
    "proximity" to SensorKind(Sensor.TYPE_PROXIMITY, "接近传感器", 1),
    "humidity" to SensorKind(Sensor.TYPE_RELATIVE_HUMIDITY, "相对湿度", 1),
    "ambient_temp" to SensorKind(Sensor.TYPE_AMBIENT_TEMPERATURE, "环境温度", 1),
)

private fun sensorCsvDir(context: Context): File {
    val public = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "AndAgent",
    )
    if (public.isDirectory) return public
    if (public.mkdirs()) return public
    // No public storage access → fall back to the app-private external dir.
    return File(context.getExternalFilesDir(null) ?: context.filesDir, "sensor").apply { mkdirs() }
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Parametrised recordings live next to mic_record's files but stay addressable by name prefix. */
private fun audioRecordDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

/** Mirror mic_record's MediaStore publish path (same MIME/relative-path conventions). */
private fun publishRecording(context: Context, file: File, format: String, durationMs: Long): String? = runCatching {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Audio.Media.MIME_TYPE, if (format == "wav") "audio/wav" else "audio/mp4")
        put(MediaStore.Audio.Media.DURATION, durationMs)
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/AndAgent")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
    }
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val uri = resolver.insert(collection, values) ?: error("MediaStore 插入失败")
    try {
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
            ?: error("MediaStore 写入失败")
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        uri.toString()
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}.getOrNull()

private fun accuracyLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable"
    SensorManager.SENSOR_STATUS_NO_CONTACT -> "no_contact"
    else -> "unknown"
}

private fun audioDeviceTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    AudioDeviceInfo.TYPE_HDMI -> "hdmi"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
    AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
    AudioDeviceInfo.TYPE_DOCK -> "dock"
    AudioDeviceInfo.TYPE_FM -> "fm"
    else -> "type_$type"
}

private fun audioSourceValue(name: String): Int? = when (name.lowercase()) {
    "mic", "default" -> MediaRecorder.AudioSource.DEFAULT
    "voice_recognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    "voice_communication" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    "camcorder" -> MediaRecorder.AudioSource.CAMCORDER
    "voice_call" -> MediaRecorder.AudioSource.VOICE_CALL
    "unprocessed" -> MediaRecorder.AudioSource.UNPROCESSED
    // VOICE_PERFORMANCE 需要 Android 10+；低版本直接报不支持而不是在录音时崩。
    "voice_performance" -> MediaRecorder.AudioSource.VOICE_PERFORMANCE.takeIf { Build.VERSION.SDK_INT >= 29 }
    else -> null
}

/**
 * AudioManager 对 UNPROCESSED 原始录音源的公开能力声明（Android 7+）。
 * null 表示系统未给结论（按支持处理，交给录音初始化失败兜底）。
 */
private fun unprocessedSupported(context: Context): Boolean? {
    val raw = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
    }.getOrNull()?.trim() ?: return null
    return when {
        raw.equals("true", true) -> true
        raw.equals("false", true) -> false
        else -> null
    }
}

/* -------------------------------- tools ------------------------------- */

class HardwareStatusTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "sensor_stream" ->
            "高频连续采样一个或多个运动/环境传感器（默认 200Hz，最高 500Hz）落 CSV 到 Downloads/AndAgent，" +
                "返回每轴均值/标准差/极值与振动强度判定（still/light/moderate/strong），用于振动检测、姿态监测。" +
                "mode=channel 走 SensorDirectChannel 硬件 FIFO 直读共享内存（API26+ 省电高频，200Hz+ 可持续），设备/传感器不支持时自动回落 polling 并标注 fallback。"
        "sensor_batch" ->
            "用硬件 FIFO 批量上传方式采样（maxReportLatency），低功耗采长序列；报告 FIFO 容量、平均批大小与批延迟。" +
                "设备无 FIFO 时自动退回连续采样并如实标注。"
        "heart_rate" ->
            "读取心率传感器（TYPE_HEART_RATE，多见于穿戴设备或带背部心率模组的机型）：平均/最低/最高 bpm 与信号质量。" +
                "手机通常没有该硬件，缺失时如实报错。"
        "significant_motion" ->
            "等待「显著运动」硬件触发（加速度触发，超低功耗，一次事件）：默认最多等 60s，返回是否触发与等待时长。"
        "thermal_status" ->
            "读取机身热状态：PowerManager 温控等级、/sys/class/thermal 各温区温度、电池温度、每 CPU 核心占用率；" +
                "并尝试读取系统 HardwarePropertiesManager 的 CPU/GPU/电池/外壳温度。"
        "battery_electrical" ->
            "读取电池电气量：瞬时/平均电流、电压、充放电功率、电量计（charge counter）、最大充电电压/电流、温度与健康度。"
        "vibrate_waveform" ->
            "播放振幅波形触感（API 26 amplitude control）：内置 tick/double_click/heartbeat/escalation/sos 预设，" +
                "也可自定义 timings+amplitudes；无振幅硬件的设备降级为开关波形。"
        "audio_record_ex" ->
            "参数化录音：录音源（mic/voice_recognition/voice_communication/camcorder/unprocessed）、采样率 8k–48k、单/双声道，" +
                "wav 直接 PCM 落盘并返回峰值/RMS 电平；m4a 走 AAC。unprocessed 走 MediaRecorder/AudioRecord 的 " +
                "UNPROCESSED 原始源（关闭 AGC/降噪/均衡），设备不支持时按 fallback 退回 voice_recognition。"
        "audio_route" ->
            "查询或切换音频输出路由：speaker（扬声器）/earpiece（听筒）/bluetooth/wired/usb/auto（恢复默认）。" +
                "返回当前模式、通信设备与全部可用输出设备。"
        else -> "硬件状态工具"
    }

    override val parameters: JsonObject = when (name) {
        "sensor_stream" -> schemaOf(
            "sensors" to strParam("逗号分隔：accelerometer（默认）/linear_acceleration/gravity/gyroscope/magnetometer/rotation_vector/light/pressure/proximity/humidity/ambient_temp"),
            "hz" to intParam("采样频率，默认 200，范围 1..500（>200Hz 在 Android 12+ 需要 HIGH_SAMPLING_RATE_SENSORS）"),
            "duration_ms" to intParam("采样时长毫秒，默认 5000，范围 200..30000"),
            "save_csv" to boolParam("是否落 CSV 到 Downloads/AndAgent，默认 true"),
            "mode" to strParam("采样模式：polling（默认，应用层回调轮询，零破坏）/ channel（SensorDirectChannel 硬件 FIFO 直读共享内存，省电高频 200Hz+，需 Android 8.0+/API26+；设备或传感器不支持时自动回落 polling 并返回 fallback=true）"),
        )
        "sensor_batch" -> schemaOf(
            "sensors" to strParam("逗号分隔的传感器名，默认 accelerometer"),
            "hz" to intParam("采样频率，默认 50，范围 1..200"),
            "duration_ms" to intParam("采样时长毫秒，默认 10000，范围 500..30000"),
            "max_latency_ms" to intParam("最大上报延迟（批窗口），默认 10000，范围 0..60000；越大越省电"),
        )
        "heart_rate" -> schemaOf(
            "seconds" to intParam("采样时长秒，默认 8，范围 2..30"),
        )
        "significant_motion" -> schemaOf(
            "timeout_s" to intParam("最长等待秒数，默认 60，范围 1..300"),
        )
        "thermal_status" -> schemaOf(
            "cpu_window_ms" to intParam("CPU 占用率采样窗口毫秒，默认 300，范围 100..2000"),
        )
        "battery_electrical" -> schemaOf()
        "vibrate_waveform" -> schemaOf(
            "preset" to strParam("预设：${WaveformSpec.presetNames().joinToString("|")}；与 timings 二选一"),
            "timings" to strParam("逗号分隔毫秒序列，如 0,40,60,30（首个通常为 0 延迟）"),
            "amplitudes" to strParam("与 timings 等长的振幅 0..255，如 0,200,0,120；省略则用最大振幅"),
            "amplitude" to intParam("单次振动振幅 0..255（无 timings/preset 时生效），默认 255"),
            "duration_ms" to intParam("单次振动时长，默认 200，范围 1..5000"),
            "repeat" to intParam("波形重复起始索引，-1 不重复（默认）"),
        )
        "audio_record_ex" -> schemaOf(
            "source" to strParam("录音源：mic（默认）/voice_recognition/voice_communication/camcorder/voice_call/unprocessed/voice_performance"),
            "seconds" to intParam("录音时长秒，默认 10，范围 1..300"),
            "sample_rate" to intParam("采样率 Hz，默认 44100，范围 8000..48000"),
            "channels" to intParam("声道数 1（默认）或 2"),
            "format" to strParam("wav（默认，PCM16）或 m4a（AAC）"),
            "fallback" to boolParam("source=unprocessed 且设备不支持时退回 voice_recognition，默认 true；false 则直接报错"),
        )
        "audio_route" -> schemaOf(
            "action" to strParam("list（默认）| set | reset"),
            "route" to strParam("action=set 时必填：speaker|earpiece|bluetooth|wired|usb"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("vibrate_waveform", "audio_route")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "sensor_stream" -> sensorStream(args)
                "sensor_batch" -> sensorBatch(args)
                "heart_rate" -> heartRate(args)
                "significant_motion" -> significantMotion(args)
                "thermal_status" -> thermalStatus(args)
                "battery_electrical" -> batteryElectrical()
                "vibrate_waveform" -> vibrateWaveform(args)
                "audio_record_ex" -> audioRecordEx(args)
                "audio_route" -> audioRoute(args)
                else -> "ERROR: 未知硬件工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* --------------------------- sensor stream -------------------------- */

    private fun resolveSensors(raw: String?): Pair<List<StreamChannel>, List<String>> {
        val requested = (raw ?: "accelerometer").split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        val channels = mutableListOf<StreamChannel>()
        val unknown = mutableListOf<String>()
        for (key in requested) {
            val kind = SENSOR_KINDS[key]
            if (kind == null) {
                unknown += key
                continue
            }
            channels += StreamChannel(key, kind.label, kind.axes)
        }
        return channels to unknown
    }

    private suspend fun sensorStream(args: JsonObject): String {
        // R146: mode=polling 为默认（零破坏）；mode=channel 走 SensorDirectChannel 硬件 FIFO 直读。
        val mode = args.strArg("mode")?.trim()?.lowercase() ?: "polling"
        if (mode == "channel") {
            // SensorDirectChannel 是 API 26 引入的公开 API；minSdk 26 下不可达，仍显式护栏，
            // 避免日后下调 minSdk 时静默走进空路径。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return "ERROR: channel 模式需要 Android 8.0+ (API26+)"
            }
            return sensorStreamChannel(args)
        }
        return sensorStreamPolling(args)
    }

    /** 默认 polling 路径：应用层 SensorEventListener 回调轮询（R84 原逻辑，零破坏）。 */
    private suspend fun sensorStreamPolling(
        args: JsonObject,
        noteOverride: String? = null,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): String {
        val (channels, unknown) = resolveSensors(args.strArg("sensors"))
        if (unknown.isNotEmpty()) {
            return "ERROR: 不支持的传感器 ${unknown.joinToString(",")}（可选：${SENSOR_KINDS.keys.joinToString("|")}）"
        }
        if (channels.isEmpty()) return "ERROR: 至少指定一个传感器"
        val hz = (args.intArg("hz") ?: 200).coerceIn(1, 500)
        val durationMs = (args.longArg("duration_ms") ?: 5000L).coerceIn(200L, 30_000L)
        val saveCsv = args.boolArg("save_csv") ?: true
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return "ERROR: 传感器服务不可用"

        val targets = mutableListOf<StreamTarget>()
        val missing = mutableListOf<String>()
        for (channel in channels) {
            val sensor = manager.getDefaultSensor(SENSOR_KINDS.getValue(channel.key).type)
            if (sensor == null) missing += channel.key else targets += StreamTarget(channel, sensor)
        }
        if (targets.isEmpty()) return "ERROR: 设备不支持 ${missing.joinToString(",")} 传感器"

        val thread = HandlerThread("andagent-sensor-stream").apply { start() }
        val handler = Handler(thread.looper)
        val periodUs = 1_000_000 / hz
        val rows = java.util.Collections.synchronizedList(ArrayList<List<String>>(minOf(hz * 30, 30_000)))
        val truncated = AtomicBoolean(false)
        val startedNs = AtomicBoolean(false)
        val baseTs = java.util.concurrent.atomic.AtomicLong(0)

        val listeners = targets.map { target ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (rows.size >= MAX_STREAM_ROWS) {
                        truncated.set(true)
                        return
                    }
                    val ts = event.timestamp
                    if (!startedNs.getAndSet(true)) baseTs.set(ts)
                    val tMs = (ts - baseTs.get()) / 1_000_000.0
                    val v = event.values
                    target.channel.count.incrementAndGet()
                    if (target.channel.values.size < MAX_STREAM_ROWS) {
                        target.channel.values += v.copyOf(minOf(3, v.size))
                    }
                    rows += listOf(
                        String.format(Locale.US, "%.3f", tMs),
                        target.channel.key,
                        v.getOrElse(0) { 0f }.toString(),
                        v.getOrElse(1) { 0f }.toString(),
                        v.getOrElse(2) { 0f }.toString(),
                        event.accuracy.toString(),
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            handler.post { manager.registerListener(listener, target.sensor, periodUs, handler) }
            listener
        }
        try {
            delay(durationMs)
        } finally {
            listeners.forEach { runCatching { manager.unregisterListener(it) } }
            thread.quitSafely()
        }
        if (rows.isEmpty()) {
            return "ERROR: 采样期间没有收到传感器数据（模拟器或该传感器不可用）"
        }

        val snapshot = synchronized(rows) { rows.toList() }
        val csv = SensorCsv.build(
            listOf("t_ms", "sensor", "x", "y", "z", "accuracy"),
            snapshot,
        )
        val stats = buildJsonObject {
            for (target in targets) {
                val channel = target.channel
                if (channel.count.get() == 0) continue
                put(channel.key, channelStats(channel, durationMs))
            }
        }
        val vibration = vibrationOf(targets.map { it.channel }, durationMs)

        var path = ""
        if (saveCsv) {
            val dir = sensorCsvDir(context)
            val suffix = targets.joinToString("-") { it.channel.key }
            val file = File(dir, "sensor_stream_${suffix}_${stamp()}.csv")
            file.writeText(csv, Charsets.UTF_8)
            path = file.absolutePath
        }

        return buildJsonObject {
            put("status", "OK")
            put("sensors", buildJsonArray { targets.forEach { add(it.channel.key) } })
            put("missing", buildJsonArray { missing.forEach { add(it) } })
            put("hz", hz)
            put("samplingPeriodUs", periodUs)
            put("durationMs", durationMs)
            put("count", snapshot.size)
            put("truncated", truncated.get())
            put("path", path)
            put("stats", stats)
            vibration?.let { put("vibration", it.toJson()) }
            put("preview", csv.lineSequence().take(5).joinToString("\n"))
            put("note", noteOverride ?: POLLING_NOTE)
            extra()
        }.toString()
    }

    /**
     * R146 mode=channel：SensorDirectChannel 硬件 FIFO 直读共享内存。
     *
     * 公开 API 实际签名（android.jar / compileSdk 34 反编译核实，与简报假设不同，见战报）：
     *   SensorManager.createDirectChannel(MemoryFile) -> SensorDirectChannel
     *   SensorDirectChannel.configure(Sensor, rate) -> int（>0 成功，0=STOP，<0 失败）
     *   SensorDirectChannel.close()
     *   rate 常量：RATE_STOP=0 / RATE_NORMAL=1 / RATE_FAST=2 / RATE_VERY_FAST=3
     *   Sensor.isDirectChannelTypeSupported(int) / getHighestDirectReportRateLevel()（均为实例方法）
     * 简报里的 registerSensorListener(...)/closeDirectChannel/SENSOR_RATE_FAST 在公开 SDK 中不存在，
     * 故不凭印象写 JNI/隐藏 API，改走上述公开路径；事件布局仍以 developer.android.com 的 104B LE 为准。
     */
    private suspend fun sensorStreamChannel(args: JsonObject): String {
        val (channels, unknown) = resolveSensors(args.strArg("sensors"))
        if (unknown.isNotEmpty()) {
            return "ERROR: 不支持的传感器 ${unknown.joinToString(",")}（可选：${SENSOR_KINDS.keys.joinToString("|")}）"
        }
        if (channels.isEmpty()) return "ERROR: 至少指定一个传感器"
        val hz = (args.intArg("hz") ?: 200).coerceIn(1, 500)
        val durationMs = (args.longArg("duration_ms") ?: 5000L).coerceIn(200L, 30_000L)
        val saveCsv = args.boolArg("save_csv") ?: true
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return "ERROR: 传感器服务不可用"

        val targets = mutableListOf<StreamTarget>()
        val missing = mutableListOf<String>()
        for (channel in channels) {
            val sensor = manager.getDefaultSensor(SENSOR_KINDS.getValue(channel.key).type)
            if (sensor == null) missing += channel.key else targets += StreamTarget(channel, sensor)
        }
        if (targets.isEmpty()) return "ERROR: 设备不支持 ${missing.joinToString(",")} 传感器"

        // 只挑声明了 direct report 能力的传感器；全部不支持则回落 polling。
        val supported = targets.filter { t ->
            runCatching {
                t.sensor.isDirectChannelTypeSupported(SensorDirectChannel.TYPE_MEMORY_FILE) &&
                    t.sensor.highestDirectReportRateLevel >= SensorDirectChannel.RATE_NORMAL
            }.getOrDefault(false)
        }
        if (supported.isEmpty()) {
            return fallbackToPolling(args, "设备/传感器未声明 direct report 能力")
        }

        // R146 必修 1：所有 configure 的传感器统一用「共同支持的最低档」，避免某个只支持
        // NORMAL 的传感器被谎报成 FAST/VERY_FAST。DirectChannel configure 本就是 per-channel
        // 全局速率，统一档位语义更干净；输出 rateLevel/hz/nominalHz/samplingPeriodUs 均基于此档。
        // 档位标称：RATE_NORMAL=50Hz / RATE_FAST=200Hz / RATE_VERY_FAST=800Hz，
        // 实际速率约为标称的 55%~220%（Android 官方口径）。
        val mapped = DirectChannelEventReader.rateLevelForHz(hz)
        val commonLevel = minOf(mapped, supported.minOf { it.sensor.highestDirectReportRateLevel })
        // 理论上不可能（supported 已过滤 >=RATE_NORMAL，mapped 最低也是 RATE_NORMAL），防御回落。
        if (commonLevel < SensorDirectChannel.RATE_NORMAL) {
            return fallbackToPolling(
                args,
                "共同支持档位=$commonLevel 不足 RATE_NORMAL（最低档），无法进行 direct report",
            )
        }
        val nominalHz = DirectChannelEventReader.nominalHzForLevel(commonLevel)

        val slots = DIRECT_CHANNEL_SLOTS
        val size = DirectChannelEventReader.EVENT_SIZE * slots
        val memoryFile = try {
            MemoryFile("andagent-direct-channel", size).also {
                // 禁止 ashmem 回收，保证采样快照可读。
                runCatching { it.allowPurging(false) }
            }
        } catch (e: Exception) {
            return fallbackToPolling(args, "创建共享内存失败：${e.message}")
        }

        var ch: SensorDirectChannel? = null
        val configured = mutableListOf<StreamTarget>()
        var events: List<DirectChannelEventReader.Event> = emptyList()
        var failure: String? = null
        try {
            val direct = manager.createDirectChannel(memoryFile)
                ?: error("createDirectChannel 返回 null")
            ch = direct
            for (t in supported) {
                // 必修 1：全部 configure 到同一档位 commonLevel（per-channel 全局速率）。
                val ret = direct.configure(t.sensor, commonLevel)
                if (ret > 0) configured += t
            }
            if (configured.isEmpty()) {
                failure = "configure 全部返回失败（设备拒绝 direct report）"
            } else {
                delay(durationMs)
                // 停写 → 关通道 → 读快照，避免读到「新写一半」的槽。
                for (t in configured) {
                    runCatching { direct.configure(t.sensor, SensorDirectChannel.RATE_STOP) }
                }
                direct.close()
                ch = null
                val buffer = ByteArray(size)
                memoryFile.readBytes(buffer, 0, 0, size)
                val boundary = DirectChannelEventReader.maxCounter(buffer, slots)
                events = DirectChannelEventReader.parse(buffer, slots, boundary)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "direct channel 采样失败：${e.message}"
        } finally {
            runCatching { ch?.close() }
            runCatching { memoryFile.close() }
        }
        if (failure != null) return fallbackToPolling(args, failure)

        // 事件按 type 字段（0x08 传感器类型）归属到对应 StreamChannel，
        // 复用既有 channelStats / vibrationOf / SensorCsv 管线。
        val byType = configured.associate { SENSOR_KINDS.getValue(it.channel.key).type to it }
        // R146 二轮必修 A：possibleWrap（a∨b，保守「可能已回绕」）与确定覆盖判据分离。
        // overwritten / truncated 仅在写指针回绕特征（条件 b）成立时为 true；
        // 恰好写满未覆盖（仅条件 a）时保持 false + possibleWrap=true，不虚报。
        val possibleWrap = DirectChannelEventReader.detectWrap(events, slots)
        val overwritten = DirectChannelEventReader.hasWrapSignature(events)
        // R146 必修 2：环形窗口按存留事件真实时序计时，不拿存留数除完整 durationMs（展示用毫秒）。
        val windowMs = if (events.isEmpty()) 0L else
            (events.maxOf { it.tsNs } - events.minOf { it.tsNs }) / 1_000_000L
        val snapshot = ArrayList<List<String>>(minOf(events.size, MAX_STREAM_ROWS))
        var baseTs: Long? = null
        for (e in events) {
            if (baseTs == null) baseTs = e.tsNs
            val target = byType[e.type]
            if (target != null) {
                target.channel.count.incrementAndGet()
                if (target.channel.values.size < MAX_STREAM_ROWS) {
                    target.channel.values += e.values.copyOf(minOf(target.channel.axes, e.values.size))
                }
            }
            val tMs = (e.tsNs - baseTs!!) / 1_000_000.0
            snapshot += listOf(
                String.format(Locale.US, "%.3f", tMs),
                target?.channel?.key ?: "type_${e.type}",
                e.counter.toString(),
                e.values.getOrElse(0) { 0f }.toString(),
                e.values.getOrElse(1) { 0f }.toString(),
                e.values.getOrElse(2) { 0f }.toString(),
                e.reportToken.toString(),
            )
        }
        val csv = SensorCsv.build(
            listOf("t_ms", "sensor", "counter", "x", "y", "z", "token"),
            snapshot,
        )
        val stats = buildJsonObject {
            for (t in configured) {
                if (t.channel.count.get() == 0) continue
                // 必修 2：measuredHz 用该传感器自身事件的窗口（按 e.type 过滤），
                // 分母为样本间隔数，避免全局 windowMs 跨传感器失真 + 少算一个间隔。
                // 二轮必修 B：窗口传纳秒原值（不再整除 1e6 成毫秒），由 rateHzFromSpanNs 保留精度。
                val type = SENSOR_KINDS.getValue(t.channel.key).type
                val own = events.filter { it.type == type }
                val perSensorSpanNs = if (own.size > 1)
                    maxOf(0L, own.maxOf { it.tsNs } - own.minOf { it.tsNs })
                else 0L
                put(t.channel.key, channelStats(t.channel, windowMs, perSensorSpanNs))
            }
        }
        // 必修 2：vibrationOf 仍用全局 windowMs（幅值统计，窗口口径影响极小）；
        // windowMs==0 时 SensorVibration.detect 直接返回 null，无除零，行为与现在一致。
        val vibration = vibrationOf(configured.map { it.channel }, windowMs)

        var path = ""
        if (saveCsv) {
            val dir = sensorCsvDir(context)
            val suffix = configured.joinToString("-") { it.channel.key }
            val file = File(dir, "sensor_stream_${suffix}_${stamp()}.csv")
            file.writeText(csv, Charsets.UTF_8)
            path = file.absolutePath
        }

        val tokens = events.map { it.reportToken }.distinct()
        return buildJsonObject {
            put("status", "OK")
            put("mode", "channel")
            put("fallback", false)
            put("sensors", buildJsonArray { configured.forEach { add(it.channel.key) } })
            put("missing", buildJsonArray { missing.forEach { add(it) } })
            put("unsupported", buildJsonArray {
                (targets - configured.toSet()).forEach { add(it.channel.key) }
            })
            put("requestedHz", hz)
            put("hz", nominalHz)              // 兼容保留 hz 键，值改为档位标称值
            put("rateLevel", directRateLabel(commonLevel))
            put("nominalHz", nominalHz)
            put("samplingPeriodUs", 1_000_000 / nominalHz)
            put("durationMs", durationMs)
            put("count", events.size)
            put("events", events.size)
            put("slots", slots)
            put("windowMs", windowMs)
            // 二轮必修 A：possibleWrap = detectWrap（a∨b，保守「可能已回绕」，兼容旧语义）；
            // overwritten 仅在写指针回绕特征（条件 b）成立时为 true（确定覆盖）。
            // truncated 改为「实际截断」语义：channel 快照无行数截断（snapshot 全量进 CSV），
            // 旧事件丢失只可能来自环形覆盖，故与 overwritten 同判据（回绕特征），
            // 恰好写满未覆盖时二者均为 false，不虚报。
            put("possibleWrap", possibleWrap)
            put("overwritten", overwritten)
            put("reportToken", buildJsonArray { tokens.forEach { add(it) } })
            put("channelLevel", directRateLabel(commonLevel))
            put("truncated", overwritten)
            put("path", path)
            put("stats", stats)
            vibration?.let { put("vibration", it.toJson()) }
            put("preview", csv.lineSequence().take(5).joinToString("\n"))
            put(
                "note",
                "mode=channel：SensorDirectChannel 共享内存快照（104B/条 little-endian，$slots 槽）；" +
                    "事件按 counter 升序恢复写入顺序，按 type 归属传感器，counter 最大值判定写入边界；" +
                    "hz 映射 RATE_NORMAL(50)/RATE_FAST(200)/RATE_VERY_FAST(800) 三档（多传感器取共同支持的最低档，如实输出），" +
                    "实际速率为档位标称的 55%~220%（实测见 stats.measuredHz，按各传感器自身事件窗口的样本间隔数计算；windowMs 为存留事件首末时间差）；" +
                    (if (possibleWrap)
                        "环形缓冲可能已回绕（possibleWrap=true：全槽写满或写指针回绕特征，快照不足以区分写满未覆盖与已覆盖）；" +
                            (if (overwritten)
                                "检测到写指针回绕特征，更早事件已被覆盖，结果仅含最近 $slots 槽窗口；"
                            else
                                "若发生覆盖，更早事件会被丢弃（本次未检测到确定覆盖特征）；")
                    else "") +
                    POLLING_NOTE,
            )
        }.toString()
    }

    /** 把 direct-channel rate 档位字符串化，便于结构化输出。 */
    private fun directRateLabel(level: Int): String = when (level) {
        SensorDirectChannel.RATE_STOP -> "DIRECT_REPORT_STOP"
        SensorDirectChannel.RATE_NORMAL -> "DIRECT_REPORT_NORMAL"
        SensorDirectChannel.RATE_FAST -> "DIRECT_REPORT_FAST"
        SensorDirectChannel.RATE_VERY_FAST -> "DIRECT_REPORT_VERY_FAST"
        else -> "DIRECT_REPORT_$level"
    }

    /** channel 路径不可用时如实回落 polling，并标注 fallback=true。 */
    private suspend fun fallbackToPolling(args: JsonObject, reason: String): String =
        sensorStreamPolling(
            args,
            noteOverride = "mode=channel 不可用（$reason），已自动回落 polling（行为兼容，结果按 polling 口径解读）；" + POLLING_NOTE,
            extra = {
                put("mode", "channel")
                put("fallback", true)
                put("channelUnsupported", true)
                put("channelReason", reason)
            },
        )

    /**
     * [perSensorSpanNs] 非空时（channel 模式）用 [SensorStats.rateHzFromSpanNs] 按该传感器
     * 自身事件窗口（纳秒原值）+ 样本间隔数计算 measuredHz；为空时（polling 模式）保持既有 rateHz 口径不变。
     */
    private fun channelStats(
        channel: StreamChannel,
        durationMs: Long,
        perSensorSpanNs: Long? = null,
    ): JsonObject = buildJsonObject {
        val samples = synchronized(channel.values) { channel.values.toList() }
        val width = samples.firstOrNull()?.size ?: 0
        val axisNames = listOf("x", "y", "z")
        put("samples", channel.count.get())
        put("measuredHz", if (perSensorSpanNs != null)
            SensorStats.rateHzFromSpanNs(channel.count.get(), perSensorSpanNs)
        else SensorStats.rateHz(channel.count.get(), durationMs))
        if (width >= 3) {
            put("magnitude", SensorStats.of(samples.map { SensorStats.magnitude(it[0], it[1], it[2]).toFloat() }).toJson())
        }
        for (i in 0 until minOf(width, axisNames.size)) {
            put(axisNames[i], SensorStats.of(samples.map { it[i] }).toJson())
        }
    }

    private fun vibrationOf(channels: List<StreamChannel>, durationMs: Long): SensorVibration.Verdict? {
        val motion = channels.firstOrNull { it.key == "accelerometer" }
            ?: channels.firstOrNull { it.key == "linear_acceleration" }
            ?: return null
        val magnitudes = motion.values
            .filter { it.size >= 3 }
            .map { SensorStats.magnitude(it[0], it[1], it[2]) }
        if (magnitudes.size < 4) return null
        return SensorVibration.detect(magnitudes, durationMs)
    }

    /* ---------------------------- sensor batch -------------------------- */

    private suspend fun sensorBatch(args: JsonObject): String {
        val (channels, unknown) = resolveSensors(args.strArg("sensors"))
        if (unknown.isNotEmpty()) {
            return "ERROR: 不支持的传感器 ${unknown.joinToString(",")}（可选：${SENSOR_KINDS.keys.joinToString("|")}）"
        }
        if (channels.isEmpty()) return "ERROR: 至少指定一个传感器"
        val hz = (args.intArg("hz") ?: 50).coerceIn(1, 200)
        val durationMs = (args.longArg("duration_ms") ?: 10_000L).coerceIn(500L, 30_000L)
        val latencyMs = (args.intArg("max_latency_ms") ?: 10_000).coerceIn(0, 60_000)
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return "ERROR: 传感器服务不可用"
        val channel = channels.first()
        val sensor = manager.getDefaultSensor(SENSOR_KINDS.getValue(channel.key).type)
            ?: return "ERROR: 设备不支持 ${channel.key} 传感器"

        val thread = HandlerThread("andagent-sensor-batch").apply { start() }
        val handler = Handler(thread.looper)
        val rows = java.util.Collections.synchronizedList(ArrayList<List<String>>(2048))
        val events = java.util.concurrent.atomic.AtomicInteger(0)
        val callbacks = java.util.concurrent.atomic.AtomicInteger(0)
        val bursts = java.util.concurrent.atomic.AtomicInteger(0)
        val lastDeliveryNs = java.util.concurrent.atomic.AtomicLong(0)
        val maxLatencyMs = java.util.concurrent.atomic.AtomicLong(0)
        val baseTs = java.util.concurrent.atomic.AtomicLong(0)
        val truncated = AtomicBoolean(false)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (rows.size >= MAX_STREAM_ROWS) {
                    truncated.set(true)
                    return
                }
                val nowNs = SystemClock.elapsedRealtimeNanos()
                callbacks.incrementAndGet()
                val prev = lastDeliveryNs.getAndSet(nowNs)
                if (prev == 0L || nowNs - prev > BURST_GAP_NS) bursts.incrementAndGet()
                val latencyMsNow = (nowNs - event.timestamp) / 1_000_000L
                if (latencyMsNow > maxLatencyMs.get()) maxLatencyMs.set(latencyMsNow)
                if (baseTs.get() == 0L) baseTs.set(event.timestamp)
                val tMs = (event.timestamp - baseTs.get()) / 1_000_000.0
                val v = event.values
                events.incrementAndGet()
                rows += listOf(
                    String.format(Locale.US, "%.3f", tMs),
                    channel.key,
                    v.getOrElse(0) { 0f }.toString(),
                    v.getOrElse(1) { 0f }.toString(),
                    v.getOrElse(2) { 0f }.toString(),
                    event.accuracy.toString(),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = runCatching {
            manager.registerListener(listener, sensor, 1_000_000 / hz, latencyMs * 1000, handler)
        }.getOrDefault(false)
        if (!registered) {
            thread.quitSafely()
            return "ERROR: 注册批量采样失败（传感器不可用或参数越界）"
        }
        try {
            delay(durationMs)
        } finally {
            runCatching { manager.unregisterListener(listener) }
            thread.quitSafely()
        }
        if (rows.isEmpty()) {
            return "ERROR: 批量采样期间没有收到数据（模拟器或该传感器不可用）"
        }

        val snapshot = synchronized(rows) { rows.toList() }
        val csv = SensorCsv.build(listOf("t_ms", "sensor", "x", "y", "z", "accuracy"), snapshot)
        val dir = sensorCsvDir(context)
        val file = File(dir, "sensor_batch_${channel.key}_${stamp()}.csv")
        file.writeText(csv, Charsets.UTF_8)
        val eventCount = events.get()
        val callbackCount = callbacks.get().coerceAtLeast(1)
        val burstCount = bursts.get().coerceAtLeast(1)
        val fifoMax = sensor.fifoMaxEventCount
        val fifoReserved = sensor.fifoReservedEventCount

        return buildJsonObject {
            put("status", "OK")
            put("sensor", channel.key)
            put("hz", hz)
            put("durationMs", durationMs)
            put("maxLatencyMs", latencyMs)
            put("count", eventCount)
            put("measuredHz", SensorStats.rateHz(eventCount, durationMs))
            put("callbacks", callbackCount)
            put("avgEventsPerCallback", Math.round(eventCount * 10.0 / callbackCount) / 10.0)
            put("bursts", burstCount)
            put("avgEventsPerBurst", Math.round(eventCount * 10.0 / burstCount) / 10.0)
            put("maxDeliveryLatencyMs", maxLatencyMs.get())
            put("fifoMaxEventCount", fifoMax)
            put("fifoReservedEventCount", fifoReserved)
            put("hardwareFifo", fifoMax > 0)
            put("wakeUpSensor", sensor.isWakeUpSensor)
            put("reportingMode", when (sensor.reportingMode) {
                Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
                Sensor.REPORTING_MODE_ON_CHANGE -> "on_change"
                Sensor.REPORTING_MODE_ONE_SHOT -> "one_shot"
                Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special_trigger"
                else -> "unknown"
            })
            put("truncated", truncated.get())
            put("path", file.absolutePath)
            put("preview", csv.lineSequence().take(5).joinToString("\n"))
            put("note", if (fifoMax > 0) "硬件 FIFO 可用：事件在传感器侧缓存后成批上报，批越大越省电"
            else "该传感器无硬件 FIFO（fifoMaxEventCount=0）：已退回连续上报，省电收益有限")
        }.toString()
    }

    /* ------------------------------ heart rate -------------------------- */

    private suspend fun heartRate(args: JsonObject): String {
        val seconds = (args.intArg("seconds") ?: 8).coerceIn(2, 30)
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return "ERROR: 传感器服务不可用"
        val sensors = manager.getSensorList(Sensor.TYPE_HEART_RATE)
        if (sensors.isEmpty()) {
            return "ERROR: 设备没有心率传感器（TYPE_HEART_RATE）。手机通常不配备，需要穿戴设备或带背部心率模组的机型"
        }
        if (!permissions.ensure(listOf(Manifest.permission.BODY_SENSORS))) {
            return "ERROR: 未授予 BODY_SENSORS（身体传感器）权限，无法读取心率"
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: return "ERROR: 心率传感器不可用"

        val thread = HandlerThread("andagent-heart-rate").apply { start() }
        val handler = Handler(thread.looper)
        val reads = java.util.Collections.synchronizedList(mutableListOf<Float>())
        var lastAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = event.values.firstOrNull() ?: return
                reads += v
                lastAccuracy = event.accuracy
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                lastAccuracy = accuracy
            }
        }
        try {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            delay(seconds * 1000L)
        } finally {
            runCatching { manager.unregisterListener(listener) }
            thread.quitSafely()
        }
        val values = synchronized(reads) { reads.toList() }.filter { it > 0f }
        if (values.isEmpty()) {
            return buildJsonObject {
                put("status", "NO_DATA")
                put("available", true)
                put("sensor", sensor.name)
                put("vendor", sensor.vendor)
                put("seconds", seconds)
                put("note", "心率传感器存在但 ${seconds}s 内没有读数：请确认贴紧皮肤/佩戴状态，或该模组需要专用 App 激活")
            }.toString()
        }
        val stats = SensorStats.of(values)
        return buildJsonObject {
            put("status", "OK")
            put("available", true)
            put("sensor", sensor.name)
            put("vendor", sensor.vendor)
            put("seconds", seconds)
            put("count", stats.count)
            put("bpm", Math.round(stats.mean))
            put("min", Math.round(stats.min))
            put("max", Math.round(stats.max))
            put("std", Math.round(stats.std * 10.0) / 10.0)
            put("accuracy", accuracyLabel(lastAccuracy))
            put("note", "心率随佩戴贴合度波动；accuracy 为传感器自报信号质量")
        }.toString()
    }

    /* -------------------------- significant motion ---------------------- */

    private suspend fun significantMotion(args: JsonObject): String {
        val timeoutS = (args.intArg("timeout_s") ?: 60).coerceIn(1, 300)
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return "ERROR: 传感器服务不可用"
        val sensor = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            ?: return "ERROR: 设备不支持显著运动传感器（TYPE_SIGNIFICANT_MOTION）"
        if (Build.VERSION.SDK_INT >= 29 &&
            !permissions.ensure(listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        ) {
            return "ERROR: Android 10+ 读取运动触发传感器需要「身体活动」权限（ACTIVITY_RECOGNITION）"
        }
        val started = SystemClock.elapsedRealtime()
        val triggered = suspendCancellableCoroutine<Boolean> { cont ->
            val done = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val listener = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent) {
                    if (!done.compareAndSet(false, true)) return
                    runCatching { manager.cancelTriggerSensor(this, sensor) }
                    if (cont.isActive) cont.resume(true)
                }
            }
            val requested = runCatching { manager.requestTriggerSensor(listener, sensor) }
                .getOrDefault(false)
            if (!requested) {
                if (cont.isActive) cont.resume(false)
            } else {
                handler.postDelayed({
                    if (done.compareAndSet(false, true)) {
                        runCatching { manager.cancelTriggerSensor(listener, sensor) }
                        if (cont.isActive) cont.resume(false)
                    }
                }, timeoutS * 1000L)
                cont.invokeOnCancellation {
                    runCatching { manager.cancelTriggerSensor(listener, sensor) }
                }
            }
        }
        val waitedMs = SystemClock.elapsedRealtime() - started
        return buildJsonObject {
            put("status", if (triggered) "TRIGGERED" else "TIMEOUT")
            put("available", true)
            put("sensor", sensor.name)
            put("triggered", triggered)
            put("waitedMs", waitedMs)
            put("timeoutS", timeoutS)
            put("note", if (triggered) "已捕获一次显著运动（硬件触发，单次事件）"
            else "等待 ${timeoutS}s 未触发：设备静止或传感器未唤醒；可延长 timeout_s")
        }.toString()
    }

    /* ----------------------------- thermal ------------------------------ */

    private fun thermalStatus(args: JsonObject): String {
        val windowMs = (args.intArg("cpu_window_ms") ?: 300).coerceIn(100, 2000)
        val pm = context.getSystemService(PowerManager::class.java)

        val zones = mutableListOf<ThermalZone.Zone>()
        runCatching {
            File("/sys/class/thermal").listFiles()?.forEach { dir ->
                if (!dir.name.startsWith("thermal_zone")) return@forEach
                val name = runCatching { File(dir, "type").readText() }.getOrNull() ?: dir.name
                val raw = runCatching { File(dir, "temp").readText() }.getOrNull() ?: return@forEach
                ThermalZone.parse(name, raw)?.let { zones += it }
            }
        }
        val sortedZones = zones.distinctBy { it.name }.sortedByDescending { it.celsius }

        val batteryTempC = runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (raw == Int.MIN_VALUE) null else raw / 10.0
        }.getOrNull()

        val cpu = runCatching {
            val before = ProcStat.parse(File("/proc/stat").readText())
            Thread.sleep(windowMs.toLong())
            val after = ProcStat.parse(File("/proc/stat").readText())
            ProcStat.busyPercent(before, after)
        }.getOrDefault(emptyList())

        val thermalCode = if (Build.VERSION.SDK_INT >= 29) pm?.currentThermalStatus else null
        val hardware = hardwarePropertiesSnapshot()

        return buildJsonObject {
            put("status", "OK")
            if (thermalCode != null) {
                put("thermalStatus", buildJsonObject {
                    put("code", thermalCode)
                    put("label", thermalLabel(thermalCode))
                })
            } else {
                put("thermalStatus", buildJsonObject {
                    put("code", -1)
                    put("label", "unsupported")
                    put("note", "PowerManager.getCurrentThermalStatus 需要 Android 10+")
                })
            }
            put("zoneCount", zones.size)
            putJsonArray("thermalZones") {
                sortedZones.take(15).forEach { add(it.toJson()) }
            }
            if (batteryTempC != null) {
                put("batteryTemperatureC", Math.round(batteryTempC * 10.0) / 10.0)
            }
            put("cpuWindowMs", windowMs)
            putJsonArray("cpuUsage") {
                cpu.forEach { (core, busy) ->
                    add(buildJsonObject {
                        put("core", core)
                        put("busyPercent", busy)
                    })
                }
            }
            if (hardware != null) put("hardwareProperties", hardware)
            put("note", "温区来自 /sys/class/thermal（部分机型对应用沙箱不可读，此时 zoneCount 为 0）；" +
                "hardwareProperties 为系统 HardwarePropertiesManager 反射结果，被非 SDK 限制拦截时缺省")
        }.toString()
    }

    private fun thermalLabel(code: Int): String = when (code) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown"
    }

    /**
     * HardwarePropertiesManager is a hidden system service: reflect into it and
     * degrade silently when the non-SDK API policy blocks the call.
     */
    private fun hardwarePropertiesSnapshot(): JsonObject? = runCatching {
        val service = context.getSystemService("hardware_properties") ?: return null
        val method = service.javaClass.getMethod(
            "getDeviceTemperatures",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val devices = linkedMapOf(
            0 to "cpu",
            1 to "gpu",
            2 to "battery",
            3 to "skin",
            4 to "usb",
        )
        val body = buildJsonObject {
            for ((id, label) in devices) {
                val current = (method.invoke(service, id, 0) as? FloatArray)?.firstOrNull()
                val throttling = (method.invoke(service, id, 1) as? FloatArray)?.firstOrNull()
                val shutdown = (method.invoke(service, id, 2) as? FloatArray)?.firstOrNull()
                if (current == null && throttling == null && shutdown == null) continue
                put(label, buildJsonObject {
                    current?.let { put("currentC", Math.round(it * 10.0) / 10.0) }
                    throttling?.let { put("throttlingC", Math.round(it * 10.0) / 10.0) }
                    shutdown?.let { put("shutdownC", Math.round(it * 10.0) / 10.0) }
                })
            }
        }
        if (body.isEmpty()) null else body
    }.getOrNull()

    /* --------------------------- battery electrical --------------------- */

    private fun batteryElectrical(): String {
        val bm = context.getSystemService(BatteryManager::class.java)
            ?: return "ERROR: 电池服务不可用"
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1

        val currentNowRaw = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }
            .getOrNull()?.takeIf { it != Int.MIN_VALUE }?.toLong()
        val currentAvgRaw = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) }
            .getOrNull()?.takeIf { it != Int.MIN_VALUE }?.toLong()
        val chargeCounter = if (Build.VERSION.SDK_INT >= 28) runCatching {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull()?.takeIf { it != Long.MIN_VALUE && it != 0L } else null
        val energyCounter = if (Build.VERSION.SDK_INT >= 28) runCatching {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }.getOrNull()?.takeIf { it != Long.MIN_VALUE && it != 0L } else null

        val (currentMa, unit) = BatteryMath.currentMa(currentNowRaw)
        val (averageMa, _) = BatteryMath.currentMa(currentAvgRaw)
        val voltageV = if (voltageMv > 0) voltageMv / 1000.0 else null
        val watts = BatteryMath.watts(currentMa, voltageV)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || currentMa != null && currentMa > 0

        // EXTRA_MAX_CHARGING_* 是隐藏常量（SDK 无公开字段），按 AOSP 的字面量读取。
        val maxChargingVoltage = if (Build.VERSION.SDK_INT >= 28) {
            sticky?.getIntExtra("max_charging_voltage", 0)?.takeIf { it > 0 }
        } else null
        val maxChargingCurrent = if (Build.VERSION.SDK_INT >= 28) {
            sticky?.getIntExtra("max_charging_current", 0)?.takeIf { it > 0 }
        } else null

        return buildJsonObject {
            put("status", "OK")
            put("percent", if (percent >= 0) percent else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("batteryStatus", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                else -> "unknown"
            })
            put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "unplugged"
                else -> "other"
            })
            put("voltageV", voltageV?.let { Math.round(it * 1000.0) / 1000.0 } ?: 0.0)
            currentMa?.let { put("currentMa", it) }
            averageMa?.let { put("averageCurrentMa", it) }
            currentNowRaw?.let { put("rawCurrent", it) }
            put("rawCurrentUnit", unit)
            watts?.let {
                put("powerW", it)
                put("powerDirection", if (charging) "input" else "output")
            }
            chargeCounter?.let { put("chargeCounterUah", it) }
            energyCounter?.let { put("energyCounterNwh", it) }
            put("temperatureC", Math.round(temperature / 10.0 * 10.0) / 10.0)
            put("health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                else -> "unknown"
            })
            maxChargingVoltage?.let { put("maxChargingVoltageV", Math.round(it / 1000.0 * 100.0) / 100.0) }
            maxChargingCurrent?.let { put("maxChargingCurrentMa", Math.round(it / 1000.0 * 10.0) / 10.0) }
            put("note", "电流符号因厂商而异：负值通常表示放电；rawCurrentUnit=ua 表示已从微安归一为毫安")
        }.toString()
    }

    /* ---------------------------- vibrate wave -------------------------- */

    private fun vibrateWaveform(args: JsonObject): String {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return "ERROR: 设备不支持振动"
        if (!vibrator.hasVibrator()) return "ERROR: 设备不支持振动"
        val amplitudeSupported = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)

        val presetName = args.strArg("preset")?.lowercase()
        val timingsArg = args.strArg("timings")
        val amplitudesArg = args.strArg("amplitudes")

        var timings: LongArray
        var amplitudes: IntArray?
        var effectName: String
        when {
            timingsArg != null -> {
                timings = WaveformSpec.parseTimings(timingsArg)
                    ?: return "ERROR: timings 解析失败，应为逗号分隔毫秒序列，如 0,40,60,30"
                amplitudes = if (amplitudesArg != null) {
                    WaveformSpec.parseAmplitudes(amplitudesArg)
                        ?: return "ERROR: amplitudes 解析失败，应为逗号分隔 0..255"
                } else null
                effectName = "custom"
            }
            presetName != null -> {
                val preset = WaveformSpec.preset(presetName)
                    ?: return "ERROR: 未知预设「$presetName」，可选：${WaveformSpec.presetNames().joinToString("|")}"
                timings = preset.first
                amplitudes = preset.second
                effectName = presetName
            }
            else -> {
                val duration = (args.intArg("duration_ms") ?: 200).coerceIn(1, 5000).toLong()
                val amplitude = (args.intArg("amplitude") ?: 255).coerceIn(0, 255)
                timings = longArrayOf(0, duration)
                amplitudes = intArrayOf(0, amplitude)
                effectName = "one_shot"
            }
        }
        WaveformSpec.validate(timings, amplitudes)?.let { return "ERROR: $it" }
        val repeat = WaveformSpec.repeatValue(args.intArg("repeat"), timings.size)

        val applied = amplitudes != null && amplitudeSupported
        val effect = if (applied) {
            VibrationEffect.createWaveform(timings, amplitudes, repeat)
        } else {
            VibrationEffect.createWaveform(timings, repeat)
        }
        vibrator.vibrate(effect)
        return buildJsonObject {
            put("status", "WRITTEN")
            put("effect", effectName)
            put("timings", buildJsonArray { timings.forEach { add(it) } })
            if (amplitudes != null) put("amplitudes", buildJsonArray { amplitudes.forEach { add(it) } })
            put("amplitudeApplied", applied)
            put("amplitudeSupported", amplitudeSupported)
            put("repeat", repeat)
            put("durationMs", WaveformSpec.totalMs(timings))
            put("note", if (applied) "已按振幅波形振动"
            else if (amplitudes != null) "设备无振幅控制（hasAmplitudeControl=false），已按开关波形播放"
            else "未提供 amplitudes，按最大振幅播放")
        }.toString()
    }

    /* -------------------------- audio record ex ------------------------- */

    private suspend fun audioRecordEx(args: JsonObject): String {
        val requestedSourceName = (args.strArg("source") ?: "mic").lowercase()
        var source = audioSourceValue(requestedSourceName)
            ?: return "ERROR: 不支持的录音源「$requestedSourceName」（mic/voice_recognition/voice_communication/camcorder/voice_call/unprocessed/voice_performance）"
        var sourceName = requestedSourceName
        val allowFallback = args.boolArg("fallback") ?: true
        val unprocessedSupport = if (requestedSourceName == "unprocessed") unprocessedSupported(context) else null
        var sourceFallback = false
        var sourceNote: String? = null
        if (requestedSourceName == "unprocessed" && unprocessedSupport == false) {
            if (!allowFallback) {
                return "ERROR: 本机 AudioManager 报告不支持 UNPROCESSED 原始录音源" +
                    "（PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=false）；可改用 source=mic 或 fallback=true"
            }
            sourceName = "voice_recognition"
            source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            sourceFallback = true
            sourceNote = "本机不支持 UNPROCESSED，已退回 voice_recognition（音频仍经过系统处理）"
        }
        val seconds = (args.intArg("seconds") ?: 10).coerceIn(1, 300)
        val sampleRate = (args.intArg("sample_rate") ?: 44_100).coerceIn(8000, 48_000).let {
            // AudioRecord/MediaRecorder only accept a handful of rates; snap to the nearest common one.
            listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000).minByOrNull { r -> Math.abs(r - it) } ?: 44_100
        }
        val channels = if (args.intArg("channels") == 2) 2 else 1
        val format = (args.strArg("format") ?: "wav").lowercase()
        if (format !in setOf("wav", "m4a")) return "ERROR: format 仅支持 wav 或 m4a"
        if (!permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))) return "ERROR: 麦克风权限未授予"

        val canPublish = Build.VERSION.SDK_INT >= 29 ||
            permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val out = File(audioRecordDir(context), "rec_ex_${requestedSourceName}_${stamp()}.$format")
        return try {
            if (format == "wav") {
                val recorded = try {
                    recordWavEx(out, source, sampleRate, channels, seconds)
                } catch (e: Exception) {
                    if (!sourceFallback && allowFallback && requestedSourceName == "unprocessed") {
                        out.delete()
                        source = MediaRecorder.AudioSource.VOICE_RECOGNITION
                        sourceName = "voice_recognition"
                        sourceFallback = true
                        sourceNote = "UNPROCESSED 初始化失败（${e.message ?: e.javaClass.simpleName}），已退回 voice_recognition"
                        recordWavEx(out, source, sampleRate, channels, seconds)
                    } else {
                        throw e
                    }
                }
                val uri = if (canPublish) publishRecording(context, out, "wav", recorded.durationMs) else null
                buildJsonObject {
                    put("status", "WRITTEN")
                    put("path", out.absolutePath)
                    put("format", "wav")
                    put("requestedSource", requestedSourceName)
                    put("source", sourceName)
                    put("rawSource", sourceName == "unprocessed")
                    put("sourceFallback", sourceFallback)
                    unprocessedSupport?.let { put("unprocessedSupported", it) }
                    sourceNote?.let { put("sourceNote", it) }
                    put("sampleRate", sampleRate)
                    put("channels", channels)
                    put("bits", 16)
                    put("durationMs", recorded.durationMs)
                    put("size", out.length())
                    put("peakDbfs", Math.round(recorded.level.peakDbfs * 10.0) / 10.0)
                    put("rmsDbfs", Math.round(recorded.level.rmsDbfs * 10.0) / 10.0)
                    put("uri", uri ?: "")
                    put("galleryVisible", uri != null)
                    put("note", if (recorded.level.peakDbfs <= -119) "全程静音（peak≈-120 dBFS）：检查录音源是否被占用或权限/隐私开关" else "PCM16 单文件，可直接用 ```audio 围栏播放")
                }.toString()
            } else {
                val durationMs = try {
                    recordM4aEx(out, source, sampleRate, channels, seconds)
                } catch (e: Exception) {
                    if (!sourceFallback && allowFallback && requestedSourceName == "unprocessed") {
                        out.delete()
                        source = MediaRecorder.AudioSource.VOICE_RECOGNITION
                        sourceName = "voice_recognition"
                        sourceFallback = true
                        sourceNote = "UNPROCESSED 初始化失败（${e.message ?: e.javaClass.simpleName}），已退回 voice_recognition"
                        recordM4aEx(out, source, sampleRate, channels, seconds)
                    } else {
                        throw e
                    }
                }
                val uri = if (canPublish) publishRecording(context, out, "m4a", durationMs) else null
                buildJsonObject {
                    put("status", "WRITTEN")
                    put("path", out.absolutePath)
                    put("format", "m4a")
                    put("requestedSource", requestedSourceName)
                    put("source", sourceName)
                    put("rawSource", sourceName == "unprocessed")
                    put("sourceFallback", sourceFallback)
                    unprocessedSupport?.let { put("unprocessedSupported", it) }
                    sourceNote?.let { put("sourceNote", it) }
                    put("sampleRate", sampleRate)
                    put("channels", channels)
                    put("durationMs", durationMs)
                    put("size", out.length())
                    put("uri", uri ?: "")
                    put("galleryVisible", uri != null)
                    put("note", "AAC 编码无法读取电平；需要电平诊断请用 format=wav")
                }.toString()
            }
        } catch (e: Exception) {
            out.delete()
            "ERROR: 录音失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }

    private data class WavResult(val durationMs: Long, val level: PcmLevel.Level)

    private fun recordWavEx(
        out: File,
        source: Int,
        sampleRate: Int,
        channels: Int,
        seconds: Int,
    ): WavResult {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { "AudioRecord 不支持 ${sampleRate}Hz/${channels}ch PCM16" }
        val bufferSize = maxOf(minBuf * 2, sampleRate * channels)
        val audio = AudioRecord(source, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        require(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败（录音源或参数不被支持）" }
        val level = PcmLevel.Accumulator()
        val bytesPerSecond = sampleRate * channels * 2
        try {
            RandomAccessFile(out, "rw").use { file ->
                file.write(PcmWav.header(sampleRate, channels, 16, 0))
                val buf = ByteArray(maxOf(minBuf, 4096))
                var count = 0L
                audio.startRecording()
                val start = System.nanoTime()
                val end = start + seconds * 1_000_000_000L
                while (System.nanoTime() < end && !Thread.currentThread().isInterrupted) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n < 0) error("AudioRecord.read 错误 $n")
                    if (n > 0) {
                        file.write(buf, 0, n)
                        level.add(buf, n)
                        count += n
                    }
                }
                audio.stop()
                file.seek(0)
                file.write(PcmWav.header(sampleRate, channels, 16, count))
                val durationMs = if (bytesPerSecond > 0) count * 1000 / bytesPerSecond else 0L
                return WavResult(durationMs, level.result())
            }
        } finally {
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
            audio.release()
        }
    }

    private fun recordM4aEx(
        out: File,
        source: Int,
        sampleRate: Int,
        channels: Int,
        seconds: Int,
    ): Long {
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        var started = false
        return try {
            mr.setAudioSource(source)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(channels)
            mr.setAudioSamplingRate(sampleRate)
            mr.setAudioEncodingBitRate(if (channels == 2) 128_000 else 96_000)
            mr.setOutputFile(out.absolutePath)
            mr.prepare()
            mr.start()
            started = true
            val start = System.nanoTime()
            Thread.sleep(seconds * 1000L)
            mr.stop()
            started = false
            (System.nanoTime() - start) / 1_000_000
        } finally {
            if (started) runCatching { mr.stop() }
            mr.release()
        }
    }

    /* ------------------------------ audio route ------------------------- */

    private fun audioRoute(args: JsonObject): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "ERROR: 音频服务不可用"
        val action = (args.strArg("action") ?: "list").lowercase()
        when (action) {
            "list" -> return routeSnapshot(am, "list", null, null)
            "reset", "auto" -> {
                val failure = runCatching {
                    if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.isSpeakerphoneOn = false
                    am.mode = AudioManager.MODE_NORMAL
                    null
                }.getOrElse { it.message ?: it.javaClass.simpleName }
                if (failure != null) return "ERROR: 恢复默认路由失败：$failure"
                return routeSnapshot(am, "reset", null, "已清除通信设备并恢复 MODE_NORMAL", true)
            }
            "set" -> {
                val route = (args.strArg("route") ?: return "ERROR: action=set 需要 route").lowercase()
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val device = matchOutput(devices, route)
                    ?: return "ERROR: 未找到可用的 $route 输出设备（可用：${devices.joinToString(",") { audioDeviceTypeName(it.type) }}）"
                val applied = runCatching { applyRoute(am, route, device) }
                    .getOrElse { return "ERROR: 切换路由失败：${it.message ?: it.javaClass.simpleName}" }
                return routeSnapshot(am, "set", route, applied.second, applied.first)
            }
            else -> return "ERROR: action 仅支持 list|set|reset"
        }
    }

    private fun routeSnapshot(
        am: AudioManager,
        action: String,
        requested: String?,
        note: String?,
        applied: Boolean? = null,
    ): String {
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
        val communicationDevice = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.communicationDevice?.let { audioDeviceTypeName(it.type) } }.getOrNull()
        } else null
        return buildJsonObject {
            put("status", if (applied == false) "NOT_APPLIED" else "OK")
            put("action", action)
            requested?.let { put("requested", it) }
            applied?.let { put("applied", it) }
            put("mode", when (am.mode) {
                AudioManager.MODE_NORMAL -> "normal"
                AudioManager.MODE_RINGTONE -> "ringtone"
                AudioManager.MODE_IN_CALL -> "in_call"
                AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
                else -> "mode_${am.mode}"
            })
            put("speakerphoneOn", am.isSpeakerphoneOn)
            put("bluetoothScoOn", am.isBluetoothScoOn)
            communicationDevice?.let { put("communicationDevice", it) }
            putJsonArray("outputs") {
                devices.forEach { info ->
                    add(buildJsonObject {
                        put("type", audioDeviceTypeName(info.type))
                        put("name", info.productName?.toString() ?: "")
                        put("id", info.id)
                    })
                }
            }
            if (!note.isNullOrBlank()) put("note", note)
            put("routeScope", "communication")
            put("routeHint", "Android 公开 API 只提供通话/通信路由；媒体播放的 A2DP/USB 走向由系统按已连接设备自动选择，切到 bluetooth 后请确认设备已连接")
        }.toString()
    }

    private fun matchOutput(devices: Array<AudioDeviceInfo>, route: String): AudioDeviceInfo? = when (route) {
        "speaker" -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        "earpiece" -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        "bluetooth" -> devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
        "wired" -> devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        "usb" -> devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        else -> null
    }

    /** Returns (applied, note): `applied=false` means the system refused the switch. */
    private fun applyRoute(am: AudioManager, route: String, device: AudioDeviceInfo): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT >= 31) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = am.setCommunicationDevice(device)
            am.isSpeakerphoneOn = route == "speaker"
            return if (ok) true to ""
            else false to "系统拒绝切换（设备可能不可用、被通话占用或被其他 App 抢占）"
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        return when (route) {
            "speaker" -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = true
                true to "Android 12 以下用扬声器开关路由"
            }
            "earpiece" -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
                true to "Android 12 以下用扬声器开关路由（关闭即听筒）"
            }
            "bluetooth" -> {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                true to "Android 12 以下用蓝牙 SCO；若耳机只支持 A2DP，媒体仍按系统默认走 A2DP"
            }
            else -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
                true to "Android 12 以下有线/USB 输出由系统自动选择，已恢复默认通信路由"
            }
        }
    }

    private companion object {
        const val MAX_STREAM_ROWS = 120_000
        const val BURST_GAP_NS = 5_000_000L
        // R146: SensorDirectChannel 共享内存槽位数（104B × 2048 ≈ 208KB）。
        const val DIRECT_CHANNEL_SLOTS = 2048
        const val POLLING_NOTE =
            "数值同时含原始 axes 列（含重力的加速度约 9.8 m/s²）；vibration 基于合成幅值相对均值的偏离估计"
    }
}

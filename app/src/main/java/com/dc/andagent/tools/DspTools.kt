package com.dc.andagent.tools

import android.content.Context
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File

/**
 * R152d: audio DSP tools — spectrum / level over the existing 16k mono PCM
 * capture pipeline (HardwareStatusTools.recordWavEx reused as-is, zero new
 * permissions; RECORD_AUDIO runtime grant already wired for mic tools).
 */
class DspTools(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description = when (name) {
        "audio_spectrum" ->
            "录一段环境音并做 FFT 频谱分析：返回主频与 top 峰值频率列表（Hz）——判断蜂鸣器频率、电机异响、音调等。录前先告知用户。"
        "audio_level" ->
            "测量环境噪声水平约 1 秒：返回峰值/平均响度（dBFS）。"
        else -> "音频 DSP 工具"
    }

    override val parameters = schemaOf(
        "duration_ms" to intParam("采集时长毫秒：spectrum 500..10000（默认 2000）；level 200..5000（默认 1000）"),
    )

    override val destructive = false

    override suspend fun execute(args: JsonObject): String {
        if (!permissions.ensure(listOf(android.Manifest.permission.RECORD_AUDIO))) {
            return "ERROR: 麦克风权限未授予"
        }
        return withContext(Dispatchers.IO) {
            runCatching { dispatch(args) }.getOrElse {
                "ERROR: 音频分析失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    private fun dispatch(args: JsonObject): String {
        val sampleRate = 16_000
        val defaultMs = if (name == "audio_level") 1000 else 2000
        val minMs = if (name == "audio_level") 200 else 500
        val maxMs = if (name == "audio_level") 5000 else 10_000
        val ms = (args.intArg("duration_ms") ?: defaultMs).coerceIn(minMs, maxMs)
        val pcm = capturePcm(sampleRate, ms) ?: return "ERROR: 无法初始化录音（录音源占用或参数不支持）"
        return when (name) {
            "audio_level" -> levelJson(pcm, ms)
            else -> spectrumJson(pcm, sampleRate, ms)
        }
    }

    /** 16k mono PCM16 capture via AudioRecord（与 mic_record 同源参数）。 */
    private fun capturePcm(sampleRate: Int, ms: Int): ByteArray? {
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val bufferSize = maxOf(minBuf * 2, sampleRate * 2)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize,
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return null
        }
        val totalBytes = sampleRate * 2 * ms / 1000
        val out = ByteArray(totalBytes)
        var filled = 0
        try {
            audio.startRecording()
            val buf = ByteArray(maxOf(minBuf, 4096))
            val deadline = System.nanoTime() + ms * 1_000_000L
            while (filled < totalBytes && System.nanoTime() < deadline) {
                val want = minOf(buf.size, totalBytes - filled)
                val n = audio.read(buf, 0, want)
                if (n < 0) break
                if (n > 0) {
                    System.arraycopy(buf, 0, out, filled, n)
                    filled += n
                }
            }
        } finally {
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
            audio.release()
        }
        return if (filled >= 64 * 2) out.copyOf(filled) else null
    }

    private fun levelJson(pcm: ByteArray, ms: Int): String {
        val peakDbfs = PcmLevel.measure(pcm).peakDbfs
        val rmsDbfs = AudioDsp.rmsDbfs(pcm)
        return buildJsonObject {
            put("durationMs", ms)
            put("peakDbfs", (peakDbfs * 10).toInt() / 10.0)
            put("rmsDbfs", (rmsDbfs * 10).toInt() / 10.0)
            put("noiseVerdict", when {
                rmsDbfs < -60 -> "安静"
                rmsDbfs < -40 -> "正常室内"
                rmsDbfs < -25 -> "较吵"
                else -> "非常吵"
            })
        }.toString()
    }

    private fun spectrumJson(pcm: ByteArray, sampleRate: Int, ms: Int): String {
        val spec = AudioDsp.spectrum(pcm, sampleRate)
            ?: return "ERROR: 样本不足（<64 样本），请加长 duration_ms"
        val peaks = AudioDsp.topPeaks(spec, 8)
        val dominant = AudioDsp.dominantFrequency(spec)
        val rms = AudioDsp.rmsDbfs(pcm)
        return buildJsonObject {
            put("durationMs", ms)
            put("sampleRate", sampleRate)
            put("binHz", (spec.binHz * 100).toInt() / 100.0)
            put("dominantHz", (dominant * 10).toInt() / 10.0)
            put("rmsDbfs", (rms * 10).toInt() / 10.0)
            putJsonArray("peaks") {
                peaks.forEach { p ->
                    add(buildJsonObject {
                        put("hz", (p.frequencyHz * 10).toInt() / 10.0)
                        put("magnitude", (p.magnitude * 1e6).toInt() / 1e6)
                    })
                }
            }
            put("hint", "主频 dominantHz 为环境中最强音源频率；蜂鸣器常用 1k/2k/4k Hz，市电变压器嗡鸣约 100/120 Hz")
        }.toString()
    }
}

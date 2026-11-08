package com.dc.andagent.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile

private const val WAV_RATE = 16_000
private const val WAV_CHANNELS = 1
private const val WAV_BITS = 16

/** Standard 44-byte PCM WAV header. Kept pure so byte layout can be unit-tested. */
fun pcmWavHeader(pcmBytes: Long): ByteArray {
    require(pcmBytes in 0..0xFFFF_FFFFL - 36)
    val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray(Charsets.US_ASCII))
    b.putInt((pcmBytes + 36).toInt())
    b.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    b.putInt(16)
    b.putShort(1)
    b.putShort(WAV_CHANNELS.toShort())
    b.putInt(WAV_RATE)
    b.putInt(WAV_RATE * WAV_CHANNELS * WAV_BITS / 8)
    b.putShort((WAV_CHANNELS * WAV_BITS / 8).toShort())
    b.putShort(WAV_BITS.toShort())
    b.put("data".toByteArray(Charsets.US_ASCII))
    b.putInt(pcmBytes.toInt())
    return b.array()
}

private fun recordingDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings").apply { mkdirs() }

class MicRecordTool(private val context: Context, private val permissions: PermissionBroker) : Tool {
    override val name = "mic_record"
    override val description = "录制麦克风音频到 M4A 或 16kHz/16bit/单声道 WAV 文件；返回路径、时长、格式和音频库 URI。"
    override val parameters = schemaOf(
        "seconds" to intParam("录音时长（秒），默认 10，范围 1-300"),
        "format" to strParam("m4a（默认）或 wav"),
    )

    override suspend fun execute(args: JsonObject): String {
        val seconds = (args.intArg("seconds") ?: 10).coerceIn(1, 300)
        val format = (args.strArg("format") ?: "m4a").lowercase()
        if (format !in setOf("m4a", "wav")) return "ERROR: format 必须是 m4a 或 wav"
        if (!permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))) return "ERROR: 麦克风权限未授予"
        val canPublish = Build.VERSION.SDK_INT >= 29 || permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val out = File(recordingDir(context), "mic_${System.currentTimeMillis()}.$format")
        return try {
            val durationMs = if (format == "wav") recordWav(out, seconds) else recordM4a(out, seconds)
            val uri = if (canPublish) publishAudio(context, out, format, durationMs) else null
            buildJsonObject {
                put("status", "WRITTEN")
                put("path", out.absolutePath)
                put("duration", durationMs / 1000.0)
                put("durationMs", durationMs)
                put("format", format)
                put("size", out.length())
                put("uri", uri ?: "")
                put("galleryVisible", uri != null)
                put("thumbnail", "")
            }.toString()
        } catch (e: Exception) {
            out.delete()
            "ERROR: 录音失败（${e.message ?: e.javaClass.simpleName}）"
        }
    }

    private fun recordM4a(out: File, seconds: Int): Long {
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        var started = false
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
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

    private fun recordWav(out: File, seconds: Int): Long {
        val min = AudioRecord.getMinBufferSize(WAV_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "AudioRecord 不支持 16kHz PCM" }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, WAV_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
        require(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        try {
            RandomAccessFile(out, "rw").use { file ->
                file.write(pcmWavHeader(0))
                val buf = ByteArray(maxOf(min, 4096))
                var count = 0L
                audio.startRecording()
                val start = System.nanoTime()
                val end = start + seconds * 1_000_000_000L
                while (System.nanoTime() < end && !Thread.currentThread().isInterrupted) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n < 0) error("AudioRecord.read 错误 $n")
                    if (n > 0) { file.write(buf, 0, n); count += n }
                }
                audio.stop()
                file.seek(0)
                file.write(pcmWavHeader(count))
                return count * 1000 / (WAV_RATE * WAV_CHANNELS * WAV_BITS / 8)
            }
        } finally {
            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { audio.stop() }
            audio.release()
        }
    }
}

/** 把录音复制进系统媒体库（Music/AndAgent）；返回 uri，失败返回 null。系统内录复用。 */
internal fun publishAudio(context: Context, file: File, format: String, durationMs: Long): String? = runCatching {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
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
        resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: error("MediaStore 写入失败")
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

class MicListTool(private val context: Context) : Tool {
    override val name = "mic_list"
    override val description = "列出 AndAgent 录音（麦克风 mic_* 与系统内录 sys_*），包括应用文件和音频库中可见的录音。"
    override val parameters = schemaOf()
    override suspend fun execute(args: JsonObject): String {
        val rows = linkedMapOf<String, JsonObject>()
        val directories = listOf(recordingDir(context), context.getExternalFilesDir(null) ?: context.filesDir).distinct()
        directories.flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.isFile && (it.name.startsWith("mic_") || it.name.startsWith("sys_")) && it.extension.lowercase() in setOf("m4a", "wav") }.forEach { file ->
            val duration = runCatching {
                if (file.extension.equals("wav", true)) {
                    // mic_* 是 16kHz/单声道（32000 B/s），sys_* 是 44.1kHz/立体声。
                    val bytesPerSecond = if (file.name.startsWith(SystemAudioCaptureRules.FILE_PREFIX)) {
                        SystemAudioCaptureRules.BYTES_PER_SECOND
                    } else {
                        32_000
                    }
                    (file.length() - 44).coerceAtLeast(0) * 1000 / bytesPerSecond
                }
                else MediaMetadataRetriever().let { retriever ->
                    try { retriever.setDataSource(file.absolutePath); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
                    finally { retriever.release() }
                }
            }.getOrDefault(0L)
            rows[file.name] = buildJsonObject { put("name", file.name); put("path", file.absolutePath); put("durationMs", duration); put("size", file.length()); put("format", file.extension.lowercase()); put("time", file.lastModified()); put("source", "file") }
        }
        runCatching {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media._ID),
                "(${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?)",
                arrayOf("mic_%", "sys_%"), "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 500) {
                    val name = cursor.getString(0) ?: continue
                    if (!name.endsWith(".m4a") && !name.endsWith(".wav")) continue
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(4))
                    rows[name] = buildJsonObject {
                        rows[name]?.forEach { (key, value) -> put(key, value) }
                        put("name", name); put("durationMs", cursor.getLong(1)); put("size", cursor.getLong(2)); put("format", name.substringAfterLast('.')); put("time", cursor.getLong(3) * 1000); put("uri", uri.toString()); put("galleryVisible", true)
                    }
                }
            }
        }
        return buildJsonObject { put("count", rows.size); put("recordings", buildJsonArray { rows.values.sortedByDescending { it["time"].toString().toLongOrNull() ?: 0 }.forEach { add(it) } }) }.toString()
    }
}

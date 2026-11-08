package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 系统内录（Android 10 / API 29+）：AudioPlaybackCaptureConfiguration + MediaProjection。
 *
 * 与 mic_record 的区别：mic_record 采的是麦克风（外放声音要经过空气），本工具采的是
 * 系统混音里的播放流，直接拿到别的 App 正在播的音频（无回声、无环境噪声、可后台）。
 *
 * 三个硬性条件（缺一无声）：
 *  1. RECORD_AUDIO 运行时权限 —— 播放捕获同样要求录音权限；
 *  2. 一个由用户同意的 MediaProjection —— 这里复用 R28 的 ProjectionService（前台服务，
 *     type=mediaProjection），既拿到 token 又满足后台采集的前台服务要求；
 *  3. 目标 App 没有关闭内录（AudioAttributes.setAllowedCapturePolicy 或 manifest
 *     allowAudioPlaybackCapture=false）—— 关掉后只能录到静音，故返回 peakDbfs 供判断。
 *
 * 采集端是 AudioRecord（MediaRecorder 不支持 setAudioPlaybackCaptureConfig），因此
 * 落盘分两条：WAV 直接写 PCM16，M4A 走 MediaCodec(AAC) + MediaMuxer。
 */

/* --------------------------- 纯逻辑（可单测） --------------------------- */

/** 系统内录的参数规则：版本门槛、格式归一化、文件命名、PCM 时长换算。 */
object SystemAudioCaptureRules {
    /** AudioPlaybackCapture 自 Android 10（API 29）起可用。 */
    const val MIN_SDK = 29
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val DEFAULT_SECONDS = 30
    const val MAX_SECONDS = 1_800
    const val DEFAULT_FORMAT = "m4a"
    const val FILE_PREFIX = "sys_"

    /** 每秒 PCM 字节数（44.1kHz × 立体声 × 16bit）。 */
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

    /** "m4a"/"wav"，未知格式返回 null。空值按默认 m4a 处理。 */
    fun normalizeFormat(raw: String?): String? = when ((raw ?: DEFAULT_FORMAT).trim().lowercase()) {
        "", "m4a", "mp4", "aac" -> "m4a"
        "wav", "wave", "pcm" -> "wav"
        else -> null
    }

    fun clampSeconds(value: Int?): Int = (value ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)

    fun fileName(format: String, timestampMs: Long): String =
        FILE_PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestampMs)) + "." + format

    /** PCM 字节数 → 时长（毫秒）。 */
    fun pcmDurationMs(pcmBytes: Long): Long = pcmBytes * 1000 / BYTES_PER_SECOND

    /** 低于该峰值基本可判定「没捕到播放流」（-90 dBFS ≈ 几乎全零）。 */
    const val SILENCE_DBFS = -90.0
}

/** 一次内录请求。 */
data class SystemAudioRequest(
    val seconds: Int,
    val format: String,
    /** 只录该包名应用的音频；null 表示所有应用。 */
    val appPackage: String?,
    /** 是否把成品复制到系统媒体库 Music/AndAgent（否则只留在应用私有目录）。 */
    val publish: Boolean,
)

/* ------------------------------ 采集会话 ------------------------------- */

/**
 * 单例持有当前内录会话。启动即返回（后台线程读 AudioRecord），
 * 由 system_audio_record_stop、seconds 超时或系统「停止投屏」收尾。
 */
object SystemAudioRecorder {
    private const val READ_BUFFER_MIN = 4_096
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val STOP_WAIT_MS = 8_000L

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var job: CaptureJob? = null

    @Volatile
    private var autoStopTask: Runnable? = null

    fun isRecording(): Boolean = job != null

    /** 启动内录；返回即时 JSON（成功后由 stop 返回成品信息）。 */
    fun start(context: Context, request: SystemAudioRequest): String {
        if (Build.VERSION.SDK_INT < SystemAudioCaptureRules.MIN_SDK) {
            return "ERROR: " + tr(
                "系统内录需要 Android 10（API 29）及以上，当前设备为 API ${Build.VERSION.SDK_INT}",
                "system audio capture needs Android 10 (API 29)+; this device is API ${Build.VERSION.SDK_INT}",
            )
        }
        val projection = awaitProjection()
            ?: return "ERROR: " + tr(
                "MediaProjection 未就绪：请先完成系统投屏授权（设置 → 屏幕捕获）",
                "MediaProjection is not ready: grant the system capture consent first (Settings → Screen capture)",
            )
        if (job != null) {
            return "ERROR: " + tr(
                "已有系统内录进行中，请先调用 system_audio_record_stop",
                "a system audio recording is already running; call system_audio_record_stop first",
            )
        }
        val app = context.applicationContext
        val uid = request.appPackage?.let { uidOf(app, it) }
        if (request.appPackage != null && uid == null) {
            return "ERROR: " + tr("找不到应用包名：${request.appPackage}", "app package not found: ${request.appPackage}")
        }
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, SystemAudioCaptureRules.fileName(request.format, System.currentTimeMillis()))
        var record: AudioRecord? = null
        var sink: AudioSink? = null
        var created: CaptureJob? = null
        return try {
            record = buildPlaybackRecord(projection, uid)
            sink = if (request.format == "wav") WavSink(file) else AacMp4Sink(file)
            val newJob = CaptureJob(app, projection, record, sink, file, request) {
                // 自愈：即使 stop() 等超时了，收尾完成后也要释放单例槽位。
                synchronized(lock) { job = null }
            }
            synchronized(lock) {
                if (job != null) {
                    record.release()
                    sink.abort()
                    return "ERROR: " + tr("已有系统内录进行中", "a system audio recording is already running")
                }
                job = newJob
                created = newJob
            }
            newJob.launch()
            scheduleAutoStop(request.seconds)
            buildJsonObject {
                put("started", true)
                put("path", file.absolutePath)
                put("format", request.format)
                put("sampleRate", SystemAudioCaptureRules.SAMPLE_RATE)
                put("channels", SystemAudioCaptureRules.CHANNELS)
                put("bitDepth", SystemAudioCaptureRules.BITS_PER_SAMPLE)
                put("durationSeconds", request.seconds)
                put("app", request.appPackage ?: "all")
                put("channel", "playback-capture")
                put(
                    "message",
                    tr(
                        "系统内录已开始（${request.format}，${request.seconds}s 自动停止）：${file.absolutePath}。" +
                            "可调用 system_audio_record_stop 提前结束；DRM 保护或已关闭内录的应用只能录到静音。",
                        "System audio recording started (${request.format}, auto-stop in ${request.seconds}s): ${file.absolutePath}. " +
                            "Call system_audio_record_stop to stop early; DRM-protected or opted-out apps capture as silence.",
                    ),
                )
            }.toString()
        } catch (e: Exception) {
            // launch 之后才失败时，别把 job 留成「僵尸单例」堵住后续启动。
            synchronized(lock) { if (job != null && job === created) job = null }
            runCatching { sink?.abort() ?: file.delete() }
            runCatching { record?.release() }
            "ERROR: " + tr(
                "系统内录启动失败：${e.message ?: e.javaClass.simpleName}",
                "system audio capture failed to start: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /**
     * 停止并收尾；没有进行中的内录时返回 null。
     * 清空 [job] 放在收尾之后，避免收尾期间被新的 start 顶掉（同时只会有一个 AudioRecord）。
     */
    fun stop(): String? {
        val current = synchronized(lock) { job } ?: return null
        cancelAutoStop()
        current.stopAsync()
        val result = current.await(STOP_WAIT_MS)
        synchronized(lock) { if (job === current) job = null }
        return result
            ?: "ERROR: " + tr("系统内录停止超时（采集线程未退出）", "stopping system audio capture timed out")
    }

    /** 设置页/诊断用的即时状态。 */
    fun statusJson(): String = buildJsonObject {
        val current = job
        put("supported", Build.VERSION.SDK_INT >= SystemAudioCaptureRules.MIN_SDK)
        put("androidSdk", Build.VERSION.SDK_INT)
        put("projection", ProjectionService.ready)
        put("recording", current != null)
        put("format", current?.request?.format ?: "")
        put("path", current?.file?.absolutePath ?: "")
        put("elapsedMs", current?.elapsedMs() ?: 0L)
        put("peakDbfs", current?.peakDbfs ?: -120.0)
        put("app", current?.request?.appPackage ?: "all")
        put("sampleRate", SystemAudioCaptureRules.SAMPLE_RATE)
        put("channels", SystemAudioCaptureRules.CHANNELS)
    }.toString()

    private fun scheduleAutoStop(seconds: Int) {
        cancelAutoStop()
        val task = Runnable {
            // 收尾要写文件/入库，放到后台线程，别占主线程。
            Thread({ runCatching { stop() } }, "andagent-sys-audio-stop").start()
        }
        autoStopTask = task
        handler.postDelayed(task, seconds * 1000L)
    }

    /**
     * 刚同意授权时 ProjectionService 是异步起来的（startForegroundService → onCreate →
     * getMediaProjection），这里等一小会儿，避免授权成功却读到 null projection。
     * 只在工具的后台线程调用。
     */
    private fun awaitProjection(timeoutMs: Long = 3_000): MediaProjection? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            ProjectionService.instance?.projection?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            runCatching { Thread.sleep(50) }
        }
    }

    private fun cancelAutoStop() {
        autoStopTask?.let { handler.removeCallbacks(it) }
        autoStopTask = null
    }

    @Suppress("DEPRECATION")
    private fun uidOf(context: Context, packageName: String): Int? = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    /**
     * 构造采集端：只接受系统混音里被允许捕获的播放流（MEDIA/GAME/UNKNOWN），
     * 给了包名时再按 UID 收窄到单个应用。
     */
    @Suppress("NewApi")
    private fun buildPlaybackRecord(projection: MediaProjection, uid: Int?): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
            addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            addMatchingUsage(AudioAttributes.USAGE_GAME)
            addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            if (uid != null) addMatchingUid(uid)
        }.build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SystemAudioCaptureRules.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "设备不支持 44.1kHz 立体声 PCM 采集" }
        val bufferBytes = maxOf(
            minBuffer * 2,
            SystemAudioCaptureRules.BYTES_PER_SECOND / 4,
            READ_BUFFER_MIN,
        )
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SystemAudioCaptureRules.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord 初始化失败（可能未授权或被系统拒绝）"
        }
        return record
    }

    /** 单次内录：独立线程读 AudioRecord → sink → 收尾入库。 */
    private class CaptureJob(
        private val context: Context,
        private val projection: MediaProjection,
        private val record: AudioRecord,
        private val sink: AudioSink,
        val file: File,
        val request: SystemAudioRequest,
        private val onFinished: () -> Unit,
    ) {
        @Volatile
        private var stopRequested = false

        @Volatile
        var peakDbfs: Double = -120.0
            private set

        @Volatile
        private var result: String? = null

        private val done = CountDownLatch(1)
        private val startedElapsed = SystemClock.elapsedRealtime()

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                // 用户在系统里「停止投屏」/别的应用抢占投屏 → 结束采集，正常收尾。
                stopRequested = true
            }
        }

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedElapsed

        fun launch() {
            // Android 14+ 要求先注册回调再启用采集；多回调是允许的（ProjectionService 也注册了）。
            runCatching { projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper())) }
            Thread({ runLoop() }, "andagent-sys-audio").apply { isDaemon = true }.start()
        }

        fun stopAsync() {
            stopRequested = true
        }

        fun await(timeoutMs: Long): String? {
            runCatching { done.await(timeoutMs, TimeUnit.MILLISECONDS) }
            return result
        }

        private fun runLoop() {
            val buffer = ByteArray(maxOf(READ_BUFFER_MIN, SystemAudioCaptureRules.BYTES_PER_SECOND / 10))
            val level = PcmLevel.Accumulator()
            var pcmBytes = 0L
            var failure: String? = null
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord 未能进入录音状态")
                }
                while (!stopRequested) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        failure = "AudioRecord.read=$read"
                        break
                    }
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    level.add(buffer, read)
                    pcmBytes += read
                }
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { projection.unregisterCallback(projectionCallback) }
                val measured = level.result()
                peakDbfs = measured.peakDbfs
                result = finalize(pcmBytes, failure)
                onFinished()
                done.countDown()
            }
        }

        private fun finalize(pcmBytes: Long, failure: String?): String {
            if (pcmBytes <= 0) {
                runCatching { sink.abort() }
                return "ERROR: " + tr(
                    "没有捕获到系统音频${failure?.let { "（$it）" } ?: ""}：请确认目标应用正在播放声音，" +
                        "且没有关闭内录（DRM 内容无法内录）",
                    "no system audio was captured${failure?.let { " ($it)" } ?: ""}: make sure the target app is playing " +
                        "and has not disabled playback capture (DRM content cannot be captured)",
                )
            }
            val durationMs = try {
                val mediaMs = sink.finish()
                if (mediaMs > 0) mediaMs else SystemAudioCaptureRules.pcmDurationMs(pcmBytes)
            } catch (e: Exception) {
                runCatching { sink.abort() }
                return "ERROR: " + tr(
                    "音频封装失败：${e.message ?: e.javaClass.simpleName}",
                    "audio muxing failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            val uri = if (request.publish) publishAudio(context, file, request.format, durationMs) else null
            val silent = peakDbfs <= SystemAudioCaptureRules.SILENCE_DBFS
            return buildJsonObject {
                put("status", "WRITTEN")
                put("path", file.absolutePath)
                put("format", request.format)
                put("duration", durationMs / 1000.0)
                put("durationMs", durationMs)
                put("size", file.length())
                put("sampleRate", SystemAudioCaptureRules.SAMPLE_RATE)
                put("channels", SystemAudioCaptureRules.CHANNELS)
                put("peakDbfs", Math.round(peakDbfs * 10.0) / 10.0)
                put("silent", silent)
                put("app", request.appPackage ?: "all")
                put("uri", uri ?: "")
                put("galleryVisible", uri != null)
                if (silent) {
                    put(
                        "warning",
                        tr(
                            "整段几乎没有声音（峰值 ≤ -90dBFS）：目标应用可能开启了 DRM 保护或关闭了内录，也可能录制期间没有播放。",
                            "the recording is almost silent (peak ≤ -90dBFS): the app may use DRM or disable playback capture, " +
                                "or nothing was playing.",
                        ),
                    )
                }
                failure?.let { put("warningDetail", it) }
            }.toString()
        }
    }
}

/* ------------------------------- 落盘 Sink ----------------------------- */

/** 采集数据的落盘目标：写一帧 PCM，最后收尾并给媒体时长。 */
private interface AudioSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** 收尾并返回媒体时长（毫秒）；无可写数据时抛异常，由调用方 abort。 */
    fun finish(): Long

    /** 丢弃半成品并删文件。 */
    fun abort()
}

/** WAV：44 字节头 + PCM16 LE，收尾时回填 data 长度。 */
private class WavSink(private val file: File) : AudioSink {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(PcmWav.header(SystemAudioCaptureRules.SAMPLE_RATE, SystemAudioCaptureRules.CHANNELS, 16, 0))
    }
    private var pcmBytes = 0L
    private var finished = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        raf.write(bytes, offset, length)
        pcmBytes += length
    }

    override fun finish(): Long {
        if (!finished) {
            finished = true
            raf.seek(0)
            raf.write(
                PcmWav.header(
                    SystemAudioCaptureRules.SAMPLE_RATE,
                    SystemAudioCaptureRules.CHANNELS,
                    16,
                    pcmBytes,
                ),
            )
            runCatching { raf.close() }
        }
        return SystemAudioCaptureRules.pcmDurationMs(pcmBytes)
    }

    override fun abort() {
        finished = true
        runCatching { raf.close() }
        runCatching { file.delete() }
    }
}

/** M4A：PCM16 → MediaCodec(AAC-LC 128kbps) → MediaMuxer(MPEG_4)。 */
private class AacMp4Sink(private val file: File) : AudioSink {
    private companion object {
        const val FRAME_BYTES = SystemAudioCaptureRules.CHANNELS * SystemAudioCaptureRules.BITS_PER_SAMPLE / 8
        const val BIT_RATE = 128_000
        const val DEQUEUE_TIMEOUT_US = 10_000L

        /** 连续拿不到输入缓冲的上限（≈2s），超过即判定编码器卡死。 */
        const val MAX_STALL = 200
        val EMPTY = ByteArray(0)
    }

    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var frames = 0L
    private var leftover = EMPTY
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SystemAudioCaptureRules.SAMPLE_RATE,
            SystemAudioCaptureRules.CHANNELS,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        // 按 4 字节（一帧立体声）对齐，余数留到下次，避免编码器吃到半帧。
        val chunk = if (leftover.isEmpty()) {
            ByteArray(length).also { System.arraycopy(bytes, offset, it, 0, length) }
        } else {
            leftover + bytes.copyOfRange(offset, offset + length)
        }
        val usable = chunk.size - chunk.size % FRAME_BYTES
        leftover = if (usable == chunk.size) EMPTY else chunk.copyOfRange(usable, chunk.size)
        var pos = 0
        var stall = 0
        while (pos < usable) {
            val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                // 编码器卡死时不要忙等：超时后交给上层收尾报错。
                if (++stall > MAX_STALL) throw IllegalStateException("编码器输入缓冲长时间不可用")
                continue
            }
            stall = 0
            val buffer = encoder.getInputBuffer(index) ?: continue
            buffer.clear()
            val room = buffer.remaining() / FRAME_BYTES * FRAME_BYTES
            val count = minOf(room, usable - pos)
            if (count <= 0) {
                drain(endOfStream = false)
                continue
            }
            buffer.put(chunk, pos, count)
            encoder.queueInputBuffer(index, 0, count, ptsUs(), 0)
            frames += count / FRAME_BYTES
            pos += count
            drain(endOfStream = false)
        }
    }

    override fun finish(): Long {
        try {
            var index = -1
            var tries = 0
            while (tries++ < 100) {
                index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) break
                drain(endOfStream = false)
            }
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (drain(endOfStream = true)) break
                }
            }
            if (!muxing) throw IllegalStateException("编码器没有产出音频帧")
        } finally {
            release()
        }
        return frames * 1000 / SystemAudioCaptureRules.SAMPLE_RATE
    }

    override fun abort() {
        release()
        runCatching { file.delete() }
    }

    private fun ptsUs(): Long = frames * 1_000_000L / SystemAudioCaptureRules.SAMPLE_RATE

    /** 取走编码输出；返回 true 表示见到 EOS。 */
    private fun drain(endOfStream: Boolean): Boolean {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) DEQUEUE_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxing) {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                }
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && muxing && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        muxer.writeSampleData(track, buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
                else -> return false
            }
        }
    }

    private fun release() {
        if (released) return
        released = true
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        if (muxing) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        muxing = false
    }
}

/* -------------------------------- 工具 -------------------------------- */

/**
 * system_audio_record：录制手机内部正在播放的音频。
 * 首次需要一次性系统投屏授权（复用 ProjectionService），之后直接开录。
 */
class SystemAudioRecordTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "system_audio_record"
    override val description =
        "录制手机正在播放的内部音频（系统内录，Android 10+，AudioPlaybackCapture + MediaProjection，" +
            "无需 root 也不需要外放）。默认存 M4A(AAC 128kbps)，可指定只录某个应用。立即返回文件路径；" +
            "可用 system_audio_record_stop 提前结束，到秒数自动停止。DRM 保护或声明禁止内录的应用只能录到静音。"

    override val parameters = schemaOf(
        "seconds" to intParam("自动停止的秒数，默认 30，范围 1-1800"),
        "format" to strParam("m4a（默认，AAC）或 wav（PCM16 44.1kHz 立体声）"),
        "app" to strParam("只录该包名的应用（如 com.tencent.mm）；留空录制所有应用"),
        "publish" to boolParam("是否复制到媒体库 Music/AndAgent，默认 true"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < SystemAudioCaptureRules.MIN_SDK) {
            return@withContext "ERROR: " + tr(
                "系统内录需要 Android 10（API 29）及以上，当前设备为 API ${Build.VERSION.SDK_INT}；" +
                    "请改用 mic_record 录麦克风",
                "system audio capture needs Android 10 (API 29)+; this device is API ${Build.VERSION.SDK_INT}. " +
                    "Use mic_record to record the microphone instead",
            )
        }
        val format = SystemAudioCaptureRules.normalizeFormat(args.strArg("format"))
            ?: return@withContext "ERROR: " + tr("format 必须是 m4a 或 wav", "format must be m4a or wav")
        val seconds = SystemAudioCaptureRules.clampSeconds(args.intArg("seconds"))
        val app = args.strArg("app")?.trim()?.takeIf { it.isNotEmpty() }
        val publish = args.boolArg("publish") ?: true
        if (!permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))) {
            return@withContext "ERROR: " + tr(
                "内录系统声音同样需要麦克风权限（RECORD_AUDIO），请在系统弹窗中允许",
                "playback capture also needs the RECORD_AUDIO permission; please allow it",
            )
        }
        if (ensureCaptureChannel(context) != CaptureChannel.PROJECTION) {
            return@withContext "ERROR: " + tr(
                "系统内录未授权：请在系统投屏授权弹窗点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）",
                "system audio capture not authorized: accept the system capture prompt and retry " +
                    "(or grant it in Settings → Screen capture)",
            )
        }
        SystemAudioRecorder.start(context, SystemAudioRequest(seconds, format, app, publish))
    }
}

/** system_audio_record_stop：停止内录并返回成品信息。 */
class SystemAudioRecordStopTool : Tool {
    override val name = "system_audio_record_stop"
    override val description = "停止正在进行的系统内录音频，完成封装与媒体库入库，返回路径、时长、大小与峰值电平。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        SystemAudioRecorder.stop()
            ?: tr("没有正在进行的系统内录", "No system audio recording in progress")
    }
}

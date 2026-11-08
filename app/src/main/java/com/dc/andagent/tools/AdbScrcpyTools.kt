package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import com.dc.andagent.adb.AdbScrcpy
import com.dc.andagent.adb.AdbScrcpySession
import com.dc.andagent.adb.AdbClient
import com.dc.andagent.shell.devtoolsScreenshotJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * R103 B：ADB 通道 scrcpy 工具层（重建，参照 tools/ScrcpyTools.kt 的 Shizuku 版结构）。
 *
 * 协议助手 AdbScrcpy.kt（76B 视频头 / 12B 帧头 / server 推送 / app_process 拉起）
 * 完整保留、直接复用；本文件只做工具注册 + 会话编排 + 帧消费：
 *  - server 推送：vendor 里的 scrcpy-server-v2.7.jar 经 base64 解码写入目标机
 *    /data/local/tmp（走 adb_shell，见 [buildServerPushCommands]）；
 *    AdbScrcpySession.start() 会再探测一次，缺失则用成熟的 syncPush 兜底。
 *  - adb_screencast：拉起 server → 解码首个 H.264 帧 → 存相册（复用
 *    devtoolsScreenshotJson 的相册 + 缩略图公共段）。
 *  - adb_record：MediaMuxer 直接写 H.264 流成 MP4（MediaCodec 解码路径按简报简化，
 *    参照 ScrcpyTools.kt 209-255 行逻辑）。
 *
 * 会话一律用 AdbSessionHub.newClient(target) 的独立连接（长读循环不与共享 live 池
 * 锁步互踩，见 AdbClient.openDuplex 的并发约束），并按目标 tls 标记走对应传输。
 */
object AdbScrcpyToolSpec {
    const val SCREENCAST = "adb_screencast"
    const val RECORD = "adb_record"
    const val MIRROR_START = "adb_mirror_start"
    const val MIRROR_STOP = "adb_mirror_stop"
    val NAMES = listOf(SCREENCAST, RECORD, MIRROR_START, MIRROR_STOP)

    private const val B64_TMP = "/data/local/tmp/andagent_scrcpy_server.b64"
    private const val PUSH_CHUNK = 2048
    private const val DEFAULT_MAX_SIZE = 1280
    private const val DEFAULT_BIT_RATE = 4_000_000
    private const val DEFAULT_DURATION_S = 30
    private const val DECODE_TIMEOUT_MS = 12_000L

    fun description(name: String): String = when (name) {
        SCREENCAST ->
            "经 ADB 通道用 scrcpy server 截取目标手机一帧屏幕：拉起 server → 解码首个 H.264 帧 → " +
                "存相册，返回 savedPath / 缩略图。target 必填（先用 adb_connect 登记）；可选 max_size / bit_rate。"
        RECORD ->
            "经 ADB 通道用 scrcpy server 录制目标手机屏幕到 Movies/AndAgent MP4" +
                "（MediaMuxer 直接写 H.264 流，无需 MediaCodec 解码）。target 必填；" +
                "duration_s 默认 30 秒（1..180），可选 bit_rate / max_size。"
        MIRROR_START -> "在浮窗实时投屏已连接目标手机；target 必填，可选 max_size（默认 800）与 fps（默认 15）。"
        MIRROR_STOP -> "停止 ADB 浮窗投屏。"
        else -> "ADB scrcpy 工具"
    }

    fun parameters(name: String): JsonObject = when (name) {
        SCREENCAST -> schemaOf(
            "target" to strParam("目标 host 或 host:port（先用 adb_connect 登记）"),
            "max_size" to intParam("最大边长，默认 1280（钳制 256..1920）"),
            "bit_rate" to intParam("码率 bps，默认 4000000"),
            required = listOf("target"),
        )
        RECORD -> schemaOf(
            "target" to strParam("目标 host 或 host:port（先用 adb_connect 登记）"),
            "duration_s" to intParam("录制时长秒数，默认 30（钳制 1..180）"),
            "bit_rate" to intParam("码率 bps，默认 4000000"),
            "max_size" to intParam("最大边长，默认 1280"),
            required = listOf("target"),
        )
        MIRROR_START -> schemaOf(
            "target" to strParam("目标 host 或 host:port（先用 adb_connect 登记）"),
            "max_size" to intParam("最大边长，默认 800（钳制 256..1920）"),
            "fps" to intParam("帧率，默认 15（钳制 1..60）"),
            required = listOf("target"),
        )
        else -> schemaOf()
    }

    /* ---- 纯函数（对齐 AdbScrcpyTest.kt 的协议助手测试，可脱离 Android 单测） ---- */

    fun clampMaxSize(v: Int?): Int = (v ?: DEFAULT_MAX_SIZE).coerceIn(256, 1920)
    fun clampBitRate(v: Int?): Int = (v ?: DEFAULT_BIT_RATE).coerceIn(500_000, 20_000_000)
    fun clampDuration(v: Int?): Int = (v ?: DEFAULT_DURATION_S).coerceIn(1, 180)
    fun clampMirrorSize(v: Int?): Int = (v ?: 800).coerceIn(256, 1920)
    fun clampFps(v: Int?): Int = (v ?: 15).coerceIn(1, 60)

    fun validateTarget(target: String?): String? =
        if (target.isNullOrBlank()) "ERROR: 缺少 target" else null

    /** `ls -l` 输出判定 server jar 是否已在目标机（对齐 AdbScrcpySession.serverExists）。 */
    fun serverPresent(lsOutput: String): Boolean =
        !lsOutput.startsWith("ERROR") &&
            lsOutput.lineSequence().any {
                it.trimStart().startsWith("-") && it.contains("andagent-scrcpy-server")
            }

    /**
     * 构造「base64 解码写入目标机」的 adb_shell 命令序列。base64 字符集不含单引号，
     * 故每块用单引号包裹是安全的；分块避免单条 A_OPEN 负载过长。
     * 纯函数，便于 JVM 单测断言分块数与收尾命令。
     */
    fun buildServerPushCommands(base64: String, chunkSize: Int = PUSH_CHUNK): List<String> {
        if (base64.isEmpty() || chunkSize <= 0) return emptyList()
        val cmds = mutableListOf<String>()
        cmds += "rm -f $B64_TMP ${AdbScrcpy.SERVER_JAR_PATH}"
        var off = 0
        while (off < base64.length) {
            val end = minOf(base64.length, off + chunkSize)
            cmds += "printf %s '${base64.substring(off, end)}' >> $B64_TMP"
            off = end
        }
        cmds += "base64 -d $B64_TMP > ${AdbScrcpy.SERVER_JAR_PATH} && rm -f $B64_TMP"
        return cmds
    }

    /** 按 0x000001 / 0x00000001 起始码切分 H.264 NAL 单元（照抄 ScrcpyTools.nalUnits）。 */
    fun nalUnits(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                (bytes[i + 2] == 1.toByte() ||
                    (bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()))
            ) {
                starts += i
                i += if (bytes[i + 2] == 1.toByte()) 3 else 4
            } else {
                i++
            }
        }
        return starts.mapIndexed { idx, start ->
            bytes.copyOfRange(start, starts.getOrElse(idx + 1) { bytes.size })
        }
    }

    private fun nalType(unit: ByteArray): Int {
        if (unit.size <= 4) return -1
        val off = if (unit[2] == 1.toByte()) 3 else 4
        return if (off < unit.size) unit[off].toInt() and 31 else -1
    }

    /** 从 config 帧提取 SPS(7) / PPS(8)，供 MediaMuxer.addTrack 的 csd-0/csd-1。 */
    fun findSpsPps(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
        val units = nalUnits(bytes)
        val sps = units.firstOrNull { nalType(it) == 7 }
        val pps = units.firstOrNull { nalType(it) == 8 }
        return if (sps != null && pps != null) sps to pps else null
    }

    /* ---- 工具编排（Android 框架部分，真机路径，JVM 不可测） ---- */

    class SessionHandle(val client: AdbClient, val session: AdbScrcpySession, private val host: String, private val port: Int) {
        fun close() {
            runCatching {
                session.stop {
                    if (!client.isAlive()) client.connect(host, port, AdbSessionHub.RECONNECT_TIMEOUT_MS)
                }
            }
            runCatching { client.close() }
        }
    }

    /** 截图、录制和浮窗共用同一条经过验证的 server 推送与 video socket 启动链路。 */
    fun openSession(context: Context, target: String, maxSize: Int, bitRate: Int, fps: Int? = null, control: Boolean = false): SessionHandle {
        val t = AdbSessionHub.target(target)
            ?: error("目标不可达：$target（先用 adb_connect 登记，adb_devices 查看）")
        val client = AdbSessionHub.newClient(target)
        try {
            val cr = client.connect(t.host, t.port)
            if (!cr.startsWith("OK")) error("连接失败：$cr")
            if (!serverPresent(client.openStream("ls -l ${AdbScrcpy.SERVER_JAR_PATH}"))) {
                val b64 = Base64.encodeToString(
                    context.assets.open(AdbScrcpy.SERVER_ASSET).use { it.readBytes() }, Base64.NO_WRAP,
                )
                for (cmd in buildServerPushCommands(b64)) runCatching { client.openStream(cmd) }
            }
            val controlClient = if (control) AdbSessionHub.newClient(target) else null
            val session = AdbScrcpySession(context, client, controlClient, t.host, t.port)
            try {
                session.start(maxSize, bitRate, fps)?.let { error(it) }
                return SessionHandle(client, session, t.host, t.port)
            } catch (e: Exception) {
                runCatching { session.stop() }
                throw e
            }
        } catch (e: Exception) {
            client.close()
            throw e
        }
    }

    /** 非 suspend：AdbTool.execute 已在 Dispatchers.IO，长会话直接阻塞即可。 */
    fun execute(context: Context, name: String, args: JsonObject): String {
        if (name == MIRROR_STOP) return com.dc.andagent.scrcpy.AdbScrcpyService.stop(context)
        val target = args.strArg("target") ?: return validateTarget(null)!!
        if (name == MIRROR_START) return com.dc.andagent.scrcpy.AdbScrcpyService.start(
            context, target, clampMirrorSize(args.intArg("max_size")), clampFps(args.intArg("fps")),
        )
        return runCatching {
            openSession(context, target, clampMaxSize(args.intArg("max_size")), clampBitRate(args.intArg("bit_rate"))).let { handle ->
                try {
                    when (name) {
                        SCREENCAST -> screencast(context, handle.session, target)
                        RECORD -> record(context, handle.session, clampDuration(args.intArg("duration_s")), target)
                        else -> "ERROR: 未知 scrcpy 工具"
                    }
                } finally { handle.close() }
            }
        }.getOrElse { "ERROR: ${it.message}" }
    }

    /** 解码首个 H.264 帧为 JPEG（MediaCodec + ImageReader），对齐 ScreenCaptureTool.scrcpyCapture。 */
    private fun screencast(context: Context, session: AdbScrcpySession, target: String): String {
        val jpeg = decodeFirstFrame(session)
            ?: return "ERROR: 解码器未产生图像（12s 超时）；可调用 adb_screencap 兜底"
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val json = Json.parseToJsonElement(devtoolsScreenshotJson(context, base64)).jsonObject
        return buildJsonObject {
            json.forEach { (k, v) -> put(k, v) }
            put("target", target)
            put("codec", "h264")
            put("bytes", jpeg.size)
        }.toString()
    }

    private fun decodeFirstFrame(session: AdbScrcpySession): ByteArray? {
        val reader = ImageReader.newInstance(session.width, session.height, ImageFormat.YUV_420_888, 2)
        val codec = MediaCodec.createDecoderByType("video/avc")
        var jpeg: ByteArray? = null
        try {
            codec.configure(
                MediaFormat.createVideoFormat("video/avc", session.width, session.height),
                reader.surface, null, 0,
            )
            codec.start()
            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            val info = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadline && jpeg == null) {
                val packet = session.nextPacket()
                val inIndex = codec.dequeueInputBuffer(500_000)
                if (inIndex >= 0) {
                    codec.getInputBuffer(inIndex)!!.apply { clear(); put(packet.bytes) }
                    codec.queueInputBuffer(
                        inIndex, 0, packet.bytes.size, packet.pts,
                        if (packet.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
                    )
                }
                val outIndex = codec.dequeueOutputBuffer(info, 500_000)
                if (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true)
                    Thread.sleep(80)
                    reader.acquireLatestImage()?.use { jpeg = imageJpeg(it) }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            reader.close()
        }
        return jpeg
    }

    /** MediaMuxer 直接写 H.264 流（参照 ScrcpyTools.scrcpyRecord 209-255 行）。 */
    private fun record(context: Context, session: AdbScrcpySession, durationS: Int, target: String): String {
        if (Build.VERSION.SDK_INT < 29) {
            return "ERROR: 该录制方式需要 Android 10+（MediaMuxer 文件描述符路径）"
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "andagent_adb_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AndAgent")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "ERROR: 无法创建 Movies 视频"
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: return "ERROR: 无法打开视频文件"
            pfd.use {
                val muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1
                var firstPts = -1L
                var samples = 0
                try {
                    val deadline = System.currentTimeMillis() + durationS * 1000L
                    while (System.currentTimeMillis() < deadline) {
                        val packet = session.nextPacket()
                        if (packet.config) {
                            val csd = findSpsPps(packet.bytes)
                            if (csd != null && track < 0) {
                                val format = MediaFormat.createVideoFormat(
                                    "video/avc", session.width, session.height,
                                )
                                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
                                format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
                                track = muxer.addTrack(format)
                                muxer.start()
                            }
                        } else if (track >= 0) {
                            if (firstPts < 0) firstPts = packet.pts
                            val info = MediaCodec.BufferInfo().apply {
                                set(
                                    0, packet.bytes.size, (packet.pts - firstPts).coerceAtLeast(0),
                                    if (packet.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                                )
                            }
                            muxer.writeSampleData(track, ByteBuffer.wrap(packet.bytes), info)
                            samples++
                        }
                    }
                    if (track < 0 || samples == 0) error("未收到可录制的 H.264 视频帧")
                } finally {
                    try {
                        if (track >= 0) muxer.stop()
                    } finally {
                        muxer.release()
                    }
                }
            }
            context.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null,
            )
            return "WRITTEN: 已保存 MP4 到 $uri\ntarget=$target\nduration=$durationS s"
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            return "ERROR: 录制失败：${e.message}"
        }
    }

    /** YUV_420_888 → NV21 → JPEG（ScrcpyTools.kt 私有实现的等价副本，不改 Shizuku 通道）。 */
    private fun imageJpeg(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val nv21 = ByteArray(w * h * 3 / 2)
        fun get(plane: Image.Plane, row: Int, col: Int): Byte =
            plane.buffer.get(row * plane.rowStride + col * plane.pixelStride)
        for (row in 0 until h) for (col in 0 until w) nv21[row * w + col] = get(y, row, col)
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            val pos = w * h + row * w + 2 * col
            nv21[pos] = get(v, row, col)
            nv21[pos + 1] = get(u, row, col)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), 85, out)
        return out.toByteArray()
    }
}

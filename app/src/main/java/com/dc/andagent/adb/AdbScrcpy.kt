package com.dc.andagent.adb

import android.content.Context
import java.io.IOException

/**
 * R100 ③：ADB 通道 scrcpy 协议助手（纯 JVM/Kotlin，供 JVM 单测直接调用）。
 *
 * 协议与 tools/ScrcpyTools.kt（Shizuku 通道）保持一致：
 *  - 视频头 76 字节 = 64B 设备名(NUL 截断) + BE int codec(0x68323634 "h264") + BE int w + BE int h；
 *  - 帧头 12 字节 = BE long flags + BE int size，随后 size 字节 payload；
 *  - flags 位含义照抄 ScrcpySession.next()：pts=flags&0x3fff...、config=flags<0(bit63)、
 *    keyFrame=flags&(1<<62)!=0(bit62)。
 * 大端字节序来自 ScrcpyTools 的 DataInputStream.readInt/readLong（Java server 写头默认大端）。
 */
object AdbScrcpy {
    const val SERVER_ASSET = "vendor/scrcpy-server-v2.7.jar"
    const val SERVER_JAR_PATH = "/data/local/tmp/andagent-scrcpy-server-v2.7.jar"
    const val SERVER_LOG_PATH = "/data/local/tmp/andagent_scrcpy.log"
    /** 每会话 pidfile：`echo $! >`（server 后台进程 pid）写入，stop() 兜底 kill 用。 */
    const val PKILL_PATTERN = "andagent-scrcpy-server"

    const val H264_CODEC = 0x68323634 // ASCII "h264"
    const val VIDEO_HEADER_SIZE = 64 + 4 + 4 + 4
    const val PACKET_HEADER_SIZE = 8 + 4
    const val MAX_PACKET_BYTES = 2_000_000
    const val CONNECT_RETRIES = 10
    const val CONNECT_RETRY_DELAY_MS = 500L
    const val MIN_DEVICE_SDK = 21 // Android 5.0：scrcpy 2.7 server 门槛（目标机）

    data class Header(val name: String, val codec: Int, val width: Int, val height: Int)

    /** 数据结构对齐 ScrcpySession.Packet：pts / config / keyFrame / bytes。 */
    data class Packet(val pts: Long, val config: Boolean, val keyFrame: Boolean, val bytes: ByteArray)

    /** 解析 76B 视频头；字节不足 / codec 非 h264 / 尺寸不在 1..4096 → null。 */
    fun parseVideoHeader(bytes: ByteArray): Header? {
        if (bytes.size < VIDEO_HEADER_SIZE) return null
        var nameLen = 0
        while (nameLen < 64 && bytes[nameLen] != 0.toByte()) nameLen++
        val name = String(bytes, 0, nameLen, Charsets.UTF_8)
        val codec = readIntBE(bytes, 64)
        val width = readIntBE(bytes, 68)
        val height = readIntBE(bytes, 72)
        if (codec != H264_CODEC || width !in 1..4096 || height !in 1..4096) return null
        return Header(name, codec, width, height)
    }

    /** 从 12B 帧头取 payload 长度；头不足或 size 越界（1..MAX_PACKET_BYTES）→ null（拒绝）。 */
    fun framePayloadSize(head: ByteArray): Int? {
        if (head.size < PACKET_HEADER_SIZE) return null
        val size = readIntBE(head, 8)
        return if (size in 1..MAX_PACKET_BYTES) size else null
    }

    /** 解析一帧（12B 头 + payload）；size 越界或与 payload 长度不符 → null。 */
    fun parsePacketFrame(head: ByteArray, payload: ByteArray): Packet? {
        if (head.size < PACKET_HEADER_SIZE) return null
        val flags = readLongBE(head, 0)
        val size = readIntBE(head, 8)
        if (size !in 1..MAX_PACKET_BYTES) return null
        if (size != payload.size) return null
        return Packet(
            flags and 0x3fffffffffffffffL, // pts：低 62 位
            flags < 0, // config：bit63
            flags and (1L shl 62) != 0L, // keyFrame：bit62
            payload,
        )
    }

    /** scid 格式化为 8 位小写 hex（照抄 ScrcpySession.open 的 "%08x"）。 */
    fun formatScid(value: Int): String = "%08x".format(value and 0x7fffffff)

    fun newScid(): String = formatScid(java.util.Random().nextInt(Int.MAX_VALUE))

    /**
     * 假设单点：scrcpy 2.7 server 在目标机监听的 abstract socket 名 = "scrcpy_" + scid。
     * 真机阶段若与 server 实际行为不符，只改此函数即可（配合 buildSocketService）。
     */
    fun buildSocketName(scid: String): String = "scrcpy_$scid"

    /** ADB OPEN 的 service：adbd 把该流桥接到目标机 abstract socket（等价 adb forward 设备侧机制）。 */
    fun buildSocketService(scid: String): String = "localabstract:${buildSocketName(scid)}"

    /**
     * 后台启动 server 的完整 shell 命令（参数照抄 ScrcpyTools，tunnel_forward=true）。
     * 重定向日志到文件 + & 后台化 → 承载它的 shell 流立即返回，openStream 不会挂住。
     * `echo $$ > pidfile`：注意 $$ 在 adb shell 里是 **shell 进程** pid，而 app_process 是它的
     * 直接子进程——仍可作为进程组锚点（kill 掉 shell 会带走孤儿链不可靠，故仅作
     * pkill -f scid= 之外的兜底信号）。pidfile 按会话 scid 命名，避免并发互踩。
     */
    fun buildServerCommand(scid: String, maxSize: Int, bitRate: Int): String =
        "CLASSPATH=$SERVER_JAR_PATH app_process / com.genymobile.scrcpy.Server 2.7 " +
            "scid=$scid log_level=info audio=false control=false video_codec=h264 " +
            "max_size=${maxSize.coerceIn(256, 1920)} video_bit_rate=${bitRate.coerceIn(500_000, 20_000_000)} " +
            "tunnel_forward=true send_dummy_byte=false " +
            ">$SERVER_LOG_PATH 2>&1 & echo \$! > ${pidFilePath(scid)}"

    /** 每会话 pidfile 路径（scid 已是 8 位 hex，可安全拼路径）。 */
    fun pidFilePath(scid: String): String = "/data/local/tmp/andagent_scrcpy_$scid.pid"

    /** getprop ro.build.version.sdk 输出 → SDK int；无法解析返回 null（调用方如实报错）。 */
    fun parseSdkInt(output: String): Int? =
        output.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    private fun readLongBE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xffL)
        return v
    }
}

/**
 * R100 ③：经 ADB 独立连接跑 scrcpy server 采集目标机视频。
 *
 * [client] 由调用方经 AdbSessionHub.newClient() 新建并 connect，生命周期由调用方管理：
 * [stop] 只关流 + 发清理 shell，调用方在 finally 里最后 close() 底层 socket。
 * 会话使用独立连接，避免长读循环与共享 live 池其它工具锁步互踩（见 AdbClient.openDuplex）。
 */
class AdbScrcpySession(private val context: Context, private val client: AdbClient) {
    private var stream: AdbClient.AdbDuplexStream? = null
    private var scid: String? = null
    var width = 0; private set
    var height = 0; private set

    /** 幂等推送 server jar：先 ls -l 探测，已存在则跳过；失败则重传。 */
    fun pushServer(): String? {
        if (serverExists()) return null
        val bytes = runCatching {
            context.assets.open(AdbScrcpy.SERVER_ASSET).use { it.readBytes() }
        }.getOrElse { return "读取 scrcpy server 资产失败：${it.message}" }
        return when (val r = client.syncPush(AdbScrcpy.SERVER_JAR_PATH, bytes)) {
            is AdbClient.AdbSyncResult.Pushed -> null
            is AdbClient.AdbSyncResult.Failed -> "推送 scrcpy server 失败：${r.reason}"
            is AdbClient.AdbSyncResult.Pulled -> "推送 scrcpy server 返回意外结果"
        }
    }

    /** ls -l 探测：正常长列表含 "-rw..." 行且带文件名；缺失时是 "ls: ... No such file..."。 */
    private fun serverExists(): Boolean {
        val out = client.openStream("ls -l ${AdbScrcpy.SERVER_JAR_PATH}")
        if (out.startsWith("ERROR")) return false
        return out.lineSequence().any {
            it.trimStart().startsWith("-") && it.contains("andagent-scrcpy-server")
        }
    }

    /**
     * 目标机版本检测 → 推送 server → 后台启动 → 轮询 openDuplex → 读视频头。
     * 成功返回 null（对齐 ScrcpySession.open），失败返回中文错误。
     */
    fun start(maxSize: Int, bitRate: Int): String? {
        // 目标机系统版本门槛（scrcpy 2.7 server 需 Android 5.0 / API 21+）。
        val sdkOut = client.openStream("getprop ro.build.version.sdk")
        val sdk = AdbScrcpy.parseSdkInt(sdkOut)
            ?: return "无法确认目标机 Android 版本（getprop 返回：${sdkOut.take(120)}）；scrcpy 2.7 server 需 Android 5.0 / API 21+"
        if (sdk < AdbScrcpy.MIN_DEVICE_SDK) {
            return "目标机 Android 版本过低（API $sdk）；scrcpy 2.7 server 需 Android 5.0 / API 21+"
        }

        pushServer()?.let { return it }

        val id = AdbScrcpy.newScid()
        scid = id
        val launch = client.openStream(AdbScrcpy.buildServerCommand(id, maxSize, bitRate))
        if (launch.startsWith("ERROR")) return "启动 scrcpy server 失败：$launch"

        val service = AdbScrcpy.buildSocketService(id)
        repeat(AdbScrcpy.CONNECT_RETRIES) {
            val s = client.openDuplex(service)
            if (s != null) {
                val head = runCatching { s.readN(AdbScrcpy.VIDEO_HEADER_SIZE) }.getOrNull()
                val header = head?.let { AdbScrcpy.parseVideoHeader(it) }
                if (header != null) {
                    stream = s
                    width = header.width
                    height = header.height
                    return null
                }
                runCatching { s.close() } // 握上了但头无效 → 关流重试
            }
            Thread.sleep(AdbScrcpy.CONNECT_RETRY_DELAY_MS)
        }
        return "连接 scrcpy video socket / 读取视频头失败（重试 ${AdbScrcpy.CONNECT_RETRIES} 次）；service=$service，server 可能未启动或 socket 名不符"
    }

    /** 读下一帧；流关闭 / 长度非法抛 IOException，由工具层 runCatching 收敛。 */
    fun nextPacket(): AdbScrcpy.Packet {
        val s = stream ?: throw IOException("scrcpy 会话未启动")
        val head = s.readN(AdbScrcpy.PACKET_HEADER_SIZE)
        val size = AdbScrcpy.framePayloadSize(head)
            ?: throw IOException("scrcpy 视频包长度无效（帧头越界）")
        val payload = s.readN(size)
        return AdbScrcpy.parsePacketFrame(head, payload)
            ?: throw IOException("scrcpy 视频包解析失败（payload 长度不符）")
    }

    /**
     * 关流 + 按本会话 scid 精确清理目标机，全包 runCatching，幂等。
     * Codex 必修①：不再无条件 `pkill -f andagent-scrcpy-server`（CLASSPATH 是 env 而非 argv，
     * pkill -f 未必能匹配；且会误杀并发会话的 server）。改为 scid 定向两步：
     *  1) `pkill -f scid=<id>`——scid=xxx 在 app_process 的 argv 里，精确匹配本会话进程；
     *  2) 兜底按 pidfile（server 启动后自写 /data/local/tmp/andagent_scrcpy_<scid>.pid，
     *     见 buildServerCommand 的 `echo $$ >`）kill；
     * 拿不到 scid（会话未启动）则跳过远程清理，只关流。
     */
    fun stop() {
        runCatching { stream?.close() }
        stream = null
        val id = scid
        if (id != null) {
            runCatching { client.openStream("pkill -f scid=$id") }
            val pid = client.openStream("cat ${AdbScrcpy.pidFilePath(id)}")
                .trim().lineSequence().firstOrNull()?.trim().orEmpty()
            if (pid.toIntOrNull() != null && pid.toInt() > 0) {
                runCatching { client.openStream("kill $pid") }
            }
            runCatching { client.openStream("rm -f ${AdbScrcpy.pidFilePath(id)}") }
        }
        runCatching { client.openStream("rm -f ${AdbScrcpy.SERVER_LOG_PATH}") }
        scid = null
    }
}

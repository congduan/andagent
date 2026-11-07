package com.dc.andagent.adb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair

/**
 * R100 ② / R103 A：ADB 客户端（纯 JVM/Kotlin）——CNXN → AUTH → OPEN/WRTE/CLSE / sync:。
 *
 * 传输层由 [transport] 注入：默认 [TcpTransport]（明文），配对过的 Android 11+
 * 主端口用 [TlsTransport]（TLS 1.3）。TLS 只是传输层替换，线上帧格式不变，故
 * 本类的协议逻辑对两种传输完全一致（R103 防失败条款）。
 *
 * 线上帧小端（AdbProtocol）。never-crash 纪律（对齐 SshTools.kt）：对外方法全部
 * runCatching 包裹，异常统一转成可读结果，绝不向调用方抛异常。
 *
 * AUTH 握手按 AOSP 流程：CNXN → 对端 AUTH(TOKEN=1) → 回 SIGNATURE=2（私钥签名）；
 * 若公钥未授信，对端再发 AUTH(TOKEN=1) → 回 RSAPUBLICKEY=3（android_pubkey 公钥，
 * 目标弹授权框）→ 用户允许后（同连接 CNXN 或重新 adb_connect）走 SIGNATURE 成功。
 *
 * 文件传输走 OPEN "sync:" + SYNC 子协议（AdbSync），WRTE 帧承载 SYNC 包。
 */
class AdbClient(
    private val keyPair: KeyPair = AdbAuth.generateKeyPair(),
    private val transport: AdbTransport = TcpTransport(),
) {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1
    /** 单条 TCP 连接上的流是锁步协议：所有对外操作串行化，避免并发工具调用互相踩帧。 */
    private val lock = Any()

    private sealed class Handshake {
        object Connected : Handshake()
        object AuthRequired : Handshake()
        data class Error(val message: String) : Handshake()
    }

    /** 连接结果：OK / 需要用户在手机上授权 / 明确错误。 */
    fun connect(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String = synchronized(lock) {
        runCatching {
            close() // 释放上一次连接（若有）
            transport.open(host, port, timeoutMs)
            input = transport.inputStream()
            output = transport.outputStream()

            write(AdbMessage(A_CNXN, A_VERSION, MAX_DATA, "host::\u0000".toByteArray()))
            when (val r = handshake()) {
                is Handshake.Connected -> {
                    // 握手期可能把读超时调到 AUTH_WAIT_MS；连上后放宽，避免长 shell/传输被连接超时误杀。
                    transport.setReadTimeout(SESSION_TIMEOUT_MS)
                    "OK: ADB 连接已建立（${host}:${port}）"
                }
                is Handshake.AuthRequired -> {
                    close()
                    "AUTH_REQUIRED: 已向目标发送本机公钥，请在手机弹出的「允许 USB 调试 / 无线调试」授权框点「允许」，然后重新调用 adb_connect(host, port)。"
                }
                is Handshake.Error -> {
                    close()
                    "ERROR: ${r.message}"
                }
            }
        }.getOrElse { e ->
            close()
            "ERROR: ADB 连接失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 握手状态机：
     *  - 首个 AUTH(TOKEN)：用私钥签名回 SIGNATURE=2。
     *  - 再次 AUTH(TOKEN)（签名被拒、公钥未授信）：发 RSAPUBLICKEY=3 公钥。
     *  - 发公钥后再收到 AUTH(TOKEN)/超时/静默：视为「等待用户授权」，提示重连。
     *  - 收到 CLSE 且未发过公钥：明确报错（授权被拒 / 调试未开启）。
     */
    private fun handshake(): Handshake {
        var sentSignature = false
        var sentPublicKey = false
        for (i in 0 until HANDSHAKE_MAX_MESSAGES) {
            val msg = try {
                read()
            } catch (e: EOFException) {
                return if (sentPublicKey) Handshake.AuthRequired
                else Handshake.Error("connection closed")
            } catch (e: IOException) {
                // 含 SocketTimeoutException：发过公钥后设备静默等用户点允许。
                return if (sentPublicKey) Handshake.AuthRequired
                else Handshake.Error(e.message ?: "读取超时")
            }
            when (msg.command) {
                A_CNXN -> return Handshake.Connected
                A_AUTH -> when (msg.arg0) {
                    ADB_AUTH_TOKEN -> {
                        when {
                            !sentSignature -> {
                                val sig = AdbAuth.signToken(keyPair.private, msg.data)
                                write(AdbMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig))
                                sentSignature = true
                            }
                            !sentPublicKey -> {
                                write(
                                    AdbMessage(
                                        A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0,
                                        AdbAuth.encodePublicKey(keyPair.public),
                                    ),
                                )
                                sentPublicKey = true
                                // 授权框等待用户操作：缩短读超时，尽快返回「需授权」。
                                runCatching { transport.setReadTimeout(AUTH_WAIT_MS) }
                            }
                            else -> return Handshake.Error(
                                "设备未授权本公钥（请在手机授权框点「允许」，且不要点「拒绝」/「一律不允许」）",
                            )
                        }
                    }
                    else -> return Handshake.Error("AUTH 期望 TOKEN(1)，实际 arg0=${msg.arg0}")
                }
                A_CLSE -> return if (sentPublicKey) Handshake.AuthRequired
                else Handshake.Error("握手期被对端关闭（授权被拒或调试未开启）")
                else -> Unit // 忽略未知命令
            }
        }
        return if (sentPublicKey) Handshake.AuthRequired
        else Handshake.Error("握手未在 $HANDSHAKE_MAX_MESSAGES 条消息内完成")
    }

    /** 打开 shell 流，收 WRTE 数据直到 A_CLSE 或对端关闭；输出按 UTF-8 字符边界截断（对齐 ssh_exec）。 */
    fun openStream(command: String): String = synchronized(lock) {
        runCatching {
            if (input == null) error("未连接：请先调用 connect()")
            val localId = nextLocalId++
            write(AdbMessage(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray()))

            val out = ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val msg = try {
                    read()
                } catch (e: EOFException) {
                    break // 对端关闭 = 流结束
                }
                when (msg.command) {
                    A_OKAY -> Unit // OPEN 确认 / WRTE 的 ack
                    A_WRTE -> {
                        total += msg.data.size
                        val room = MAX_OUTPUT_BYTES - out.size()
                        if (room > 0) out.write(msg.data, 0, minOf(room, msg.data.size))
                        // 每个 WRTE 都要回 A_OKAY（arg0=本地 id，arg1=对端 id）；对端已关时忽略写失败。
                        runCatching { write(AdbMessage(A_OKAY, localId, msg.arg0, ByteArray(0))) }
                    }
                    A_CLSE -> {
                        runCatching { write(AdbMessage(A_CLSE, localId, msg.arg0, ByteArray(0))) }
                        break
                    }
                    else -> Unit // 忽略未知命令
                }
            }
            clipUtf8(out.toByteArray(), MAX_OUTPUT_BYTES, total)
        }.getOrElse { e ->
            "ERROR: ADB shell 执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * R100 ③：打开【裸流】（scrcpy 视频单向流）——只做 OPEN 握手，返回可持续读取的流对象，
     * 不像 [openStream] 那样消费到 CLSE（后者不适合跑常驻进程）。
     *
     * never-crash：未连接 / 服务被拒（CLSE）/ 读写异常一律返回 null，绝不向调用方抛异常。
     *
     * 并发约束（重要）：一条 ADB TCP 连接上的流是锁步协议，返回的流会持续占用该连接的读循环。
     * scrcpy 会话【必须】使用独立 AdbClient 连接（见 AdbSessionHub.newClient()），不要在共享
     * live 池的 client 上开裸流，也不要在流存活期间对该 client 调用 openStream/syncPush 等方法，
     * 否则帧会互相踩。视频是单向流，写侧仅需 OPEN 握手，故不提供通用写 API。
     */
    fun openDuplex(service: String): AdbDuplexStream? = synchronized(lock) {
        runCatching {
            if (input == null) {
                null // 未连接：先 connect()
            } else {
                val localId = nextLocalId++
                // ADB OPEN 的 service 串以 NUL 结尾（对齐 openStream/SyncStream）。
                write(AdbMessage(A_OPEN, localId, 0, "$service\u0000".toByteArray()))
                var remoteId: Int? = null
                var rejected = false
                for (i in 0 until HANDSHAKE_MAX_MESSAGES) {
                    if (remoteId != null || rejected) break
                    val msg = read()
                    when (msg.command) {
                        A_OKAY -> remoteId = msg.arg0 // arg0 = 对端 id
                        A_CLSE -> rejected = true // 服务被拒绝 / 打开失败
                        else -> Unit // 忽略未知命令
                    }
                }
                if (rejected) null else remoteId?.let { AdbDuplexStream(localId, it) }
            }
        }.getOrElse { null }
    }

    /**
     * R100 ③：单条 ADB 裸流（视频单向）。见 [openDuplex] 的并发约束。
     * 生命周期：读完调用 [close] 发 CLSE 关流；底层 socket 由调用方（独立 client）在会话结束时 close()。
     */
    inner class AdbDuplexStream(private val localId: Int, private val remoteId: Int) {
        private val buffer = ByteArrayOutputStream()
        @Volatile private var closed = false

        /**
         * 阻塞读满 n 字节。专用读循环：收 WRTE 时按 remoteId 匹配、把 payload 累积进缓冲并回 OKAY；
         * 收 CLSE/EOF 且缓冲不足时抛 IOException（由会话 runCatching 收敛）；支持跨 WRTE 帧累积。
         */
        fun readN(n: Int): ByteArray = synchronized(lock) {
            if (n < 0) throw IOException("readN 长度无效：$n")
            while (buffer.size() < n) {
                if (closed) throw IOException("ADB 裸流已关闭，仅剩 ${buffer.size()}/$n 字节")
                val msg = read() // 可能抛 EOFException / IOException
                when (msg.command) {
                    A_WRTE -> {
                        // Codex 必修④：按两端 ID 严格匹配（AOSP：WRTE arg0=对端 id、arg1=本地 id），
                        // 只累积本流数据帧；不匹配的迟到帧（重试残留）静默丢弃、不回 OKAY。
                        if (msg.arg0 == remoteId && msg.arg1 == localId) {
                            buffer.write(msg.data)
                            // 对端 WRTE 必须回传输 OKAY（本地 id, 对端 id），否则对端停发。
                            runCatching { write(AdbMessage(A_OKAY, localId, msg.arg0, ByteArray(0))) }
                        }
                    }
                    A_OKAY -> Unit // 本流不写，忽略
                    A_CLSE ->
                        // Codex 必修④：仅本流的 CLSE 才关流（arg0/arg1 任一命中本地 id，
                        // 兼容 adbd 两种回填约定），其它流的迟到 CLSE 忽略。
                        if (msg.arg0 == localId || msg.arg1 == localId) closed = true
                    else -> Unit
                }
            }
            val all = buffer.toByteArray()
            val result = all.copyOfRange(0, n)
            buffer.reset()
            if (all.size > n) buffer.write(all, n, all.size - n) // 余量留回缓冲供下次 readN
            result
        }

        /** 发 CLSE 关流、幂等（重复调用只发一次）；不关闭底层 socket。 */
        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                runCatching { write(AdbMessage(A_CLSE, localId, remoteId, ByteArray(0))) }
            }
        }
    }

    /** 拉取远程文件（SYNC RECV）；maxBytes 为大小上限。 */
    fun syncPull(remotePath: String, maxBytes: Long): AdbSyncResult = synchronized(lock) {
        runCatching {
            val s = SyncStream()
            try {
                s.open()
                s.sendSync(AdbSync.encodeRecv(remotePath))
                val out = ByteArrayOutputStream()
                var total = 0L
                var result: AdbSyncResult =
                    AdbSyncResult.Failed("SYNC RECV 未收到 DONE（流提前关闭，已收 $total 字节）")
                loop@ while (true) {
                    val packet = s.nextPacket() ?: break
                    when (packet.id) {
                        AdbSync.ID_DATA -> {
                            total += packet.data.size
                            if (total > maxBytes) {
                                result = AdbSyncResult.Failed(
                                    "远程文件超过 ${maxBytes / 1_048_576}MB 上限（已收 $total 字节）",
                                )
                                break@loop
                            }
                            out.write(packet.data)
                        }
                        AdbSync.ID_DONE -> {
                            result = AdbSyncResult.Pulled(out.toByteArray())
                            break@loop
                        }
                        AdbSync.ID_FAIL -> {
                            result = AdbSyncResult.Failed(AdbSync.failMessage(packet))
                            break@loop
                        }
                        else -> Unit // 忽略 STAT/DENT 等
                    }
                }
                result
            } finally {
                s.close()
            }
        }.getOrElse { e -> AdbSyncResult.Failed(e.message ?: e.javaClass.simpleName) }
    }

    /** 推送本地字节到远程（SYNC SEND）；返回写入字节数。 */
    fun syncPush(
        remotePath: String,
        data: ByteArray,
        mode: Int = AdbSync.MODE_DEFAULT,
    ): AdbSyncResult = synchronized(lock) {
        runCatching {
            val s = SyncStream()
            try {
                s.open()
                val seq = ByteArrayOutputStream()
                seq.write(AdbSync.encodeSend(remotePath, mode))
                var off = 0
                while (off < data.size) {
                    val n = minOf(AdbSync.SYNC_DATA_MAX, data.size - off)
                    seq.write(AdbSync.encodeData(data.copyOfRange(off, off + n)))
                    off += n
                }
                seq.write(AdbSync.encodeDone((System.currentTimeMillis() / 1000).toInt()))
                s.sendSync(seq.toByteArray())
                var result: AdbSyncResult = AdbSyncResult.Failed("SYNC SEND 未收到 OKAY/FAIL（流提前关闭）")
                loop@ while (true) {
                    val packet = s.nextPacket() ?: break
                    when (packet.id) {
                        AdbSync.ID_OKAY -> {
                            result = AdbSyncResult.Pushed(data.size.toLong())
                            break@loop
                        }
                        AdbSync.ID_FAIL -> {
                            result = AdbSyncResult.Failed(AdbSync.failMessage(packet))
                            break@loop
                        }
                        else -> Unit
                    }
                }
                result
            } finally {
                s.close()
            }
        }.getOrElse { e -> AdbSyncResult.Failed(e.message ?: e.javaClass.simpleName) }
    }

    /** SYNC 结果：拉取字节 / 推送字节数 / 失败原因。 */
    sealed class AdbSyncResult {
        data class Pulled(val bytes: ByteArray) : AdbSyncResult()
        data class Pushed(val bytes: Long) : AdbSyncResult()
        data class Failed(val reason: String) : AdbSyncResult()
    }

    /** 单个 "sync:" 流：管理 OPEN/WRTE/OKAY/CLSE 与 SYNC 包跨帧累积。 */
    private inner class SyncStream {
        val localId: Int = nextLocalId++
        var remoteId = -1
            private set
        val reader = SyncPacketReader()
        var acksNeeded = 0
            private set
        @Volatile var closed = false

        /** OPEN "sync:" → 等 OKAY（arg0=对端 id）或 CLSE（arg0=0 表示拒绝）。 */
        fun open() {
            if (input == null) error("未连接：请先调用 connect()")
            write(AdbMessage(A_OPEN, localId, 0, "sync:\u0000".toByteArray()))
            while (true) {
                val msg = read()
                when (msg.command) {
                    A_OKAY -> { remoteId = msg.arg0; return }
                    A_CLSE -> error(if (msg.arg0 == 0) "sync: 服务被拒绝" else "sync: 服务被对端关闭")
                    else -> Unit
                }
            }
        }

        /** 把 SYNC 请求字节按 MAX_DATA 切成 WRTE 帧，每帧等一个传输 OKAY（流量控制）。 */
        fun sendSync(bytes: ByteArray) {
            var off = 0
            while (off < bytes.size && !closed) {
                val n = minOf(MAX_DATA, bytes.size - off)
                write(AdbMessage(A_WRTE, localId, remoteId, bytes.copyOfRange(off, off + n)))
                off += n
                acksNeeded++
                while (acksNeeded > 0 && !closed) {
                    val msg = try {
                        read()
                    } catch (e: IOException) {
                        closed = true; break
                    }
                    handle(msg)
                }
            }
        }

        /** 取下一个完整 SYNC 包；流关闭且缓冲空时返回 null。 */
        fun nextPacket(): SyncPacket? {
            while (true) {
                val p = reader.poll()
                if (p != null) return p
                if (closed) return null
                val msg = try {
                    read()
                } catch (e: IOException) {
                    closed = true; return null
                }
                handle(msg)
            }
        }

        private fun handle(msg: AdbMessage) {
            when (msg.command) {
                A_WRTE -> {
                    reader.feed(msg.data)
                    // 对端 WRTE 必须回传输 OKAY（本地 id, 对端 id）。
                    runCatching { write(AdbMessage(A_OKAY, localId, msg.arg0, ByteArray(0))) }
                }
                A_OKAY -> if (acksNeeded > 0) acksNeeded--
                A_CLSE -> closed = true
                else -> Unit
            }
        }

        fun close() {
            if (remoteId >= 0) {
                runCatching { write(AdbMessage(A_CLSE, localId, remoteId, ByteArray(0))) }
            }
        }
    }

    private fun write(msg: AdbMessage) {
        val out = output ?: error("输出流不可用")
        out.write(AdbProtocol.encode(msg))
        out.flush()
    }

    private fun read(): AdbMessage =
        AdbProtocol.readMessage(input ?: error("输入流不可用"))

    /** 粗粒度存活判断；底层连接假活由下一次读超时兜底。 */
    fun isAlive(): Boolean = transport.isAlive()

    /** 全路径释放，幂等（重复调用安全）；transport.close() 负责回收 fd / JNI handle。 */
    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { transport.close() }
        input = null
        output = null
    }

    /** 对齐 ssh_exec 的 clipUtf8：按 UTF-8 字符边界截断，输出恒为合法 UTF-8。 */
    private fun clipUtf8(bytes: ByteArray, limit: Int, totalBytes: Long): String {
        if (bytes.isEmpty() && totalBytes == 0L) return "(空)"
        val truncated = totalBytes > limit || bytes.size > limit
        if (!truncated) return String(bytes, Charsets.UTF_8)
        var end = 0
        while (end < bytes.size) {
            val b = bytes[end].toInt() and 0xFF
            val len = when {
                b and 0x80 == 0 -> 1
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> -1
            }
            if (len <= 0 || end + len > limit || end + len > bytes.size) break
            end += len
        }
        val text = String(bytes, 0, end, Charsets.UTF_8)
        return "$text\n[已截断：原始 $totalBytes 字节，仅显示前 $end 字节]"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000
        /** 对齐 ssh_exec 的 STDOUT_LIMIT（8KB）。 */
        const val MAX_OUTPUT_BYTES = 8 * 1024
        const val HANDSHAKE_MAX_MESSAGES = 8
        const val AUTH_WAIT_MS = 3000
        /** 建连后的读超时：shell/文件传输可能长时间无输出，不沿用 connect 的短超时。 */
        const val SESSION_TIMEOUT_MS = 30_000
    }
}

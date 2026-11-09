package com.dc.andagent.adb

import com.dc.andagent.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.KeyPair

/**
 * R124 连接选项（真机 A/B 开关，见 AdbSessionHub.TLS_PEER_CNXN_FIRST）。
 *
 * [peerCnxnFirst] = true：TLS 建连后【不主动发 CNXN】，先等 adbd 在 TLS 握手完成后
 * 主动发来的 CNXN。三方对照一致：iadb-ios ADBClient.swift:103-118（handleSTLS 里
 * upgradeToTLS 后直接 receiveMessage 等 .connect）、cocool97/adb_client
 * adb_message_device.rs:89-101（upgrade_connection 后 read_handshake_message 等 CNXN）。
 * 我们在 TLS 后多发一次 CNXN 会被 adbd 的 handle_new_connection 当作新连接：
 * 该函数首行 handle_offline(t)（adb.cpp:408）把 t->online 清 0（adb.cpp:217），
 * use_tls 分支只 send_tls_request 不会重新 online（adb.cpp:420-424），于是后续
 * A_OPEN 命中 `if (!t->online) break`（adb.cpp:502）被静默丢弃——
 * 这正是 ColorOS 上「A_OPEN 后零响应，连 CLSE 都没有」的成因。
 */
data class AdbConnectOptions(
    val peerCnxnFirst: Boolean = false,
    /** 用 `shell,v2,raw:` 显式禁 PTY（对齐 iadb-ios ADBClient.swift:218）。 */
    val rawShellService: Boolean = false,
)

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
    /** A_OPEN 后的 ACK 快速失败窗口；可注入以便纯 JVM 单测不必真等 8s。 */
    private val ackWindowMs: Long = ACK_WINDOW_MS,
    /** R124：TLS 时序与 shell 服务串开关（见 [AdbConnectOptions]）。 */
    private val options: AdbConnectOptions = AdbConnectOptions(),
) {
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1
    private var peerFeatures: Set<String> = emptySet()
    /** R124：目标机 CNXN banner 全文（features 权威数据），写入帧序列 evidence。 */
    private var peerBannerText: String = ""
    private val recentFrames = ArrayDeque<String>()
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

            peerFeatures = emptySet()
            peerBannerText = ""
            recentFrames.clear()
            // R124：TLS 传输在 open() 内已完成「明文 CNXN → STLS → TLS 握手」，
            // adbd 握手成功后会主动发 CNXN；此时绝不重发我方 CNXN（见 AdbConnectOptions）。
            // 明文 TcpTransport 仍须由我方发起 CNXN。
            if (options.peerCnxnFirst) {
                runCatching { transport.setReadTimeout(PEER_CNXN_WAIT_MS) }
            } else {
                write(AdbMessage(A_CNXN, A_VERSION, MAX_DATA, (AdbFeatures.hostBanner() + "\u0000").toByteArray()))
            }
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
                A_CNXN -> {
                    peerFeatures = AdbFeatures.parseBanner(msg.data)
                    // R124：banner 全文留痕——目标机 features 列表是权威数据。
                    peerBannerText = AdbFeatures.bannerText(msg.data)
                    return Handshake.Connected
                }
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
            val v2 = AdbFeatures.useShellV2(peerFeatures)
            write(
                AdbMessage(
                    A_OPEN, localId, 0,
                    (AdbFeatures.shellService(command, v2, options.rawShellService) + "\u0000").toByteArray(),
                ),
            )
            val openAt = System.currentTimeMillis()

            // A_OPEN 后的 ACK 快速失败：正常 adbd 毫秒级回 OKAY/WRTE/CLSE。
            // 8s 内三无（目标熄屏锁屏、被其它 adb 客户端占用、无线调试页已退出）
            // 是最常见实战成因——给出可操作引导而不是干等 90s 会话超时。
            var gotAck = false
            val ackDeadline = System.currentTimeMillis() + ackWindowMs
            // 暂存握手放宽的读超时，ACK 到达后恢复，避免影响后续长输出读取。
            val savedTimeout = SESSION_TIMEOUT_MS
            runCatching { transport.setReadTimeout(1_000) }
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val decoder = if (v2) ShellV2Decoder() else null
            var total = 0L
            var exitCode: Int? = null
            try {
                while (true) {
                    if (!gotAck && System.currentTimeMillis() > ackDeadline) {
                        error(
                            "ADB shell 无响应（8 秒内未收到目标确认）。最常见原因：" +
                                "①目标手机熄屏/锁屏（亮屏解锁后重试）；" +
                                "②目标机正被其它 adb 客户端（PC adb 等）占用——adbd 同一时间只服务一个无线客户端；" +
                                "③目标机「无线调试」页已退出导致端口失效——重新进入该页查看当前端口并 adb_connect 重连；" +
                                "④若下方帧序列出现 RX A_AUTH TOKEN：目标机仍视本机为未授权设备（配对信任未生效）——重启目标机「无线调试」后重新配对。 " +
                                " 帧序列：OPEN sent@$openAt; no OKAY/WRTE/CLSE by ${System.currentTimeMillis()}; ${frameSummary()}"
                        )
                    }
                    val msg = try {
                        read()
                    } catch (e: SocketTimeoutException) {
                        if (!gotAck) continue // ACK 窗口内继续轮询 deadline
                        throw e
                    } catch (e: EOFException) {
                        if (!gotAck) error("ADB shell OPEN 后连接关闭")
                        break // 对端关闭 = 流结束
                    }
                    when (msg.command) {
                        A_OKAY -> {
                            if (!gotAck) { gotAck = true; runCatching { transport.setReadTimeout(savedTimeout) } }
                            Unit // OPEN 确认 / WRTE 的 ack
                        }
                        A_WRTE -> {
                            if (!gotAck) { gotAck = true; runCatching { transport.setReadTimeout(savedTimeout) } }
                            val chunks = decoder?.feed(msg.data) ?: listOf(ShellV2Decoder.Chunk(1, msg.data))
                            for (chunk in chunks) {
                                when (chunk.id) {
                                    1, 2 -> {
                                        total += chunk.data.size
                                        val sink = if (chunk.id == 2) err else out
                                        val room = MAX_OUTPUT_BYTES - out.size() - err.size()
                                        if (room > 0) sink.write(chunk.data, 0, minOf(room, chunk.data.size))
                                    }
                                    3 -> if (chunk.data.size == 1) exitCode = chunk.data[0].toInt() and 255
                                }
                            }
                            // 每个 WRTE 都要回 A_OKAY（arg0=本地 id，arg1=对端 id）；对端已关时忽略写失败。
                            runCatching { write(AdbMessage(A_OKAY, localId, msg.arg0, ByteArray(0))) }
                        }
                        A_CLSE -> {
                            gotAck = true
                            runCatching { write(AdbMessage(A_CLSE, localId, msg.arg0, ByteArray(0))) }
                            break
                        }
                        else -> Unit // 忽略未知命令
                    }
                }
            } finally {
                // 无论 ACK / CLSE / EOF / 超时快速失败 / 异常退出，都把读超时恢复为
                // 会话值：否则 1s 的 ACK 探测窗口会泄漏到本连接后续 openStream/sync
                // 长读（CLSE 先到、EOF、fast-fail、post-ACK 超时这几条路径原本漏恢复）。
                runCatching { transport.setReadTimeout(savedTimeout) }
            }
            val text = clipUtf8(out.toByteArray() + err.toByteArray(), MAX_OUTPUT_BYTES, total)
            if (exitCode != null && exitCode != 0) "$text\n[exit code: $exitCode]" else text
        }.getOrElse { e ->
            val message = e.message ?: e.javaClass.simpleName
            "ERROR: ADB shell 执行失败：$message" +
                if ("帧序列：" in message || recentFrames.isEmpty()) "" else " 帧序列：${frameSummary()}"
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

        /** Send one control message and honor ADB WRTE/OKAY flow control. */
        fun writeBytes(bytes: ByteArray) = synchronized(lock) {
            if (closed) throw IOException("ADB 裸流已关闭")
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + MAX_DATA, bytes.size)
                write(AdbMessage(A_WRTE, localId, remoteId, bytes.copyOfRange(offset, end)))
                while (true) {
                    val msg = read()
                    if (msg.command == A_CLSE && (msg.arg0 == localId || msg.arg1 == localId)) {
                        closed = true
                        throw IOException("ADB 控制流已关闭")
                    }
                    if (msg.arg0 == remoteId && msg.arg1 == localId) {
                        when (msg.command) {
                            A_OKAY -> break
                            A_WRTE -> write(AdbMessage(A_OKAY, localId, remoteId, ByteArray(0)))
                        }
                    }
                }
                offset = end
            }
        }

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
        traceFrame("TX", msg)
        out.write(AdbProtocol.encode(msg))
        out.flush()
    }

    private fun read(): AdbMessage =
        AdbProtocol.readMessage(input ?: error("输入流不可用")).also { traceFrame("RX", it) }

    private fun traceFrame(direction: String, msg: AdbMessage) {
        // R121e：AUTH/CNXN/STLS 纳入追踪——若 adbd 在 TLS 后仍发 A_AUTH TOKEN，
        // 说明它视本机为未授权设备（配对信任未生效），这是「连接成功但服务无响应」
        // 的决定性证据；此前只追 OPEN/OKAY/WRTE/CLSE 会漏掉这个信号。
        // R124：真正把 A_STLS 加入集合（此前注释写了 STLS 但集合里没有），并对
        // CNXN/OPEN 记录 payload 全文——OPEN 的服务名与对端 CNXN 的 features 是
        // 三方对照的权威证据，只记 len= 时用户帧序列里看不到具体内容。
        if (msg.command !in setOf(A_OPEN, A_OKAY, A_WRTE, A_CLSE, A_AUTH, A_CNXN, A_STLS)) return
        val name = String(byteArrayOf(msg.command.toByte(), (msg.command ushr 8).toByte(),
            (msg.command ushr 16).toByte(), (msg.command ushr 24).toByte()), Charsets.US_ASCII)
        val payload = if (msg.command == A_CNXN || msg.command == A_OPEN) {
            " payload='${AdbFeatures.bannerText(msg.data)}'"
        } else ""
        val line = "${System.currentTimeMillis()} $direction $name arg0=${msg.arg0} arg1=${msg.arg1} " +
            "len=${msg.data.size}$payload"
        recentFrames.addLast(line)
        if (recentFrames.size > 32) recentFrames.removeFirst()
        if (BuildConfig.DEBUG) runCatching { android.util.Log.d("AdbClientFrames", line) }
    }

    /** R124：目标机 CNXN banner 全文（含 features），供上层日志/evidence 读取。 */
    fun peerBanner(): String = synchronized(lock) { peerBannerText }

    private fun frameSummary(): String {
        val frames = recentFrames.takeLast(8).joinToString(" | ")
        return if (peerBannerText.isEmpty()) frames
        else "$frames | RX CNXN banner='$peerBannerText'"
    }

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
        /** A_OPEN 后的 ACK 快速失败窗口：正常 adbd 毫秒级回 OKAY/WRTE/CLSE，
         * 超过该窗口仍三无则给出可操作引导，不干等 SESSION_TIMEOUT_MS。 */
        const val ACK_WINDOW_MS = 8_000L
        /** 对齐 ssh_exec 的 STDOUT_LIMIT（8KB）。 */
        const val MAX_OUTPUT_BYTES = 8 * 1024
        const val HANDSHAKE_MAX_MESSAGES = 8
        const val AUTH_WAIT_MS = 3000
        /** R124：peerCnxnFirst 模式下等对端主动 CNXN 的窗口（iadb-ios 用 30s，取 15s）。 */
        const val PEER_CNXN_WAIT_MS = 15_000
        /** 建连后的读超时：shell/文件传输可能长时间无输出，不沿用 connect 的短超时。 */
        const val SESSION_TIMEOUT_MS = 90_000
    }
}

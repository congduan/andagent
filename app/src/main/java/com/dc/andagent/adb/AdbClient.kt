package com.dc.andagent.adb

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair

/**
 * R100 ①：最小 ADB 客户端——TCP → CNXN → AUTH(TOKEN/SIGNATURE) → OPEN/WRTE/CLSE。
 * 纯 JVM 可实现。never-crash 纪律（对齐 SshTools.kt）：对外方法全部 runCatching 包裹，
 * 异常统一转成可读的 "ERROR: ..." 字符串返回，绝不向调用方抛异常。
 */
class AdbClient(
    private val keyPair: KeyPair = AdbAuth.generateKeyPair(),
) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1

    fun connect(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String = runCatching {
        close() // 释放上一次连接（若有）
        val s = Socket()
        socket = s // 先登记，确保连接失败时 getOrElse->close() 能回收 fd
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        input = s.getInputStream()
        output = s.getOutputStream()

        write(AdbMessage(A_CNXN, A_VERSION, MAX_DATA, "host::\u0000".toByteArray()))
        handshake()
        "OK: ADB 连接已建立（${host}:${port}）"
    }.getOrElse { e ->
        close()
        "ERROR: ADB 连接失败：${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * 握手：对端回 A_CNXN 即完成；回 A_AUTH(TOKEN=1) 则用私钥签名 token 并回
     * A_AUTH(SIGNATURE=2, data=sig)，再等 A_CNXN。RSAPUBLICKEY(3) 授权弹窗流程
     * 留第②/③轮（本轮 mock 对端信任该公钥，不触发）。
     */
    private fun handshake() {
        for (i in 0 until HANDSHAKE_MAX_MESSAGES) {
            val msg = read()
            when (msg.command) {
                A_CNXN -> return
                A_AUTH -> when (msg.arg0) {
                    ADB_AUTH_TOKEN -> {
                        val sig = AdbAuth.signToken(keyPair.private, msg.data)
                        write(AdbMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig))
                    }
                    else -> error("AUTH 期望 TOKEN(1)，实际 arg0=${msg.arg0}")
                }
                else -> error("握手期收到意外命令 0x${msg.command.toString(16)}")
            }
        }
        error("握手未在 $HANDSHAKE_MAX_MESSAGES 条消息内完成")
    }

    /** 打开 shell 流，收 WRTE 数据直到 A_CLSE 或对端关闭；输出上限 64KB 截断。 */
    fun openStream(command: String): String = runCatching {
        if (input == null) error("未连接：请先调用 connect()")
        val localId = nextLocalId++
        write(AdbMessage(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray()))

        val out = ByteArrayOutputStream()
        var total = 0
        var truncated = false
        while (true) {
            val msg = try {
                read()
            } catch (e: EOFException) {
                break // 对端关闭 = 流结束
            }
            when (msg.command) {
                A_OKAY -> Unit // OPEN 确认 / WRTE 的 ack，无需动作
                A_WRTE -> {
                    val data = msg.data
                    val remaining = MAX_OUTPUT_BYTES - total
                    if (remaining > 0) {
                        val take = minOf(remaining, data.size)
                        out.write(data, 0, take)
                        total += take
                        if (take < data.size) truncated = true
                    } else {
                        truncated = true
                    }
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
        val text = out.toString(Charsets.UTF_8.name())
        if (truncated) "$text\n[输出已截断至 64KB]" else text
    }.getOrElse { e ->
        "ERROR: ADB shell 执行失败：${e.message ?: e.javaClass.simpleName}"
    }

    private fun write(msg: AdbMessage) {
        val out = output ?: error("输出流不可用")
        out.write(AdbProtocol.encode(msg))
        out.flush()
    }

    private fun read(): AdbMessage =
        AdbProtocol.readMessage(input ?: error("输入流不可用"))

    /** 全路径释放，幂等（重复调用安全）。 */
    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val HANDSHAKE_MAX_MESSAGES = 8
    }
}

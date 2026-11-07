package com.dc.andagent.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * R100 ②：ADB 传输协议帧编解码（纯 JVM/Kotlin，无 Android 依赖）。
 *
 * 帧头固定 24 字节，6 个 32 位字段全部按【小端】写入（真实 adbd 的线上字节序）：
 *   command(4) arg0(4) arg1(4) data_length(4) data_check(4) magic(4)
 *   magic = command xor 0xffffffff。
 *
 * data_check 是 AOSP 定义的 payload 字节累加和（wrapping u32），不是 CRC32。
 * 但协议版本 ≥ 0x01000001（我们 CNXN 通告的 A_VERSION_SKIP_CHECKSUM）时双方
 * 都忽略该字段——对端发来的帧 data_check 会是 0。因此编码仍写累加和（兼容旧版），
 * 解码遇到 data_check==0 时跳过校验；非 0 时按累加和校验（防篡改）。
 *
 * 命令字为小端数值：线上字节恰为可读 ASCII 标签，例如 0x4e584e43 小端写出即
 * 43 4e 58 4e = "CNXN"。① 轮用大端 mock 自洽，本轮按 AOSP 改小端以对接真实 adbd。
 */

// ---- 命令字（小端数值；线上字节 = ASCII 标签）----
const val A_CNXN = 0x4e584e43
const val A_AUTH = 0x48545541
const val A_OPEN = 0x4e45504f
const val A_OKAY = 0x59414b4f
const val A_WRTE = 0x45545257
const val A_CLSE = 0x45534c43
const val A_SYNC = 0x434e5953

// ---- CNXN 参数 ----
/** A_VERSION_SKIP_CHECKSUM：≥ 此版本双方忽略 data_check。 */
const val A_VERSION = 0x01000001
const val MAX_DATA = 4096

// ---- AUTH arg0（AOSP：TOKEN=1 / SIGNATURE=2 / RSAPUBLICKEY=3）----
const val ADB_AUTH_TOKEN = 1
const val ADB_AUTH_SIGNATURE = 2
const val ADB_AUTH_RSAPUBLICKEY = 3

const val HEADER_SIZE = 24
/** 疯帧防线：data_length 超过 256MB 在读 body 之前直接拒绝。 */
const val MAX_DATA_LENGTH = 256L * 1024 * 1024

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray,
)

class AdbProtocolException(message: String) : IOException(message)

object AdbProtocol {

    /** 编码为完整帧（24B 头 + data），全小端。 */
    fun encode(msg: AdbMessage): ByteArray {
        val check = dataCheckOf(msg.data)
        val magic = msg.command xor -1
        val out = ByteArray(HEADER_SIZE + msg.data.size)
        putIntLE(out, 0, msg.command)
        putIntLE(out, 4, msg.arg0)
        putIntLE(out, 8, msg.arg1)
        putIntLE(out, 12, msg.data.size)
        putIntLE(out, 16, check)
        putIntLE(out, 20, magic)
        msg.data.copyInto(out, HEADER_SIZE)
        return out
    }

    fun writeMessage(output: OutputStream, msg: AdbMessage) {
        output.write(encode(msg))
        output.flush()
    }

    /**
     * 从流中读一帧：先读满 24B 头，校验 magic 与 data_length 上限，再按长度读 body。
     * data_check==0（≥0x01000001 对端忽略校验）时跳过校验，非 0 时按累加和校验。
     * 干净 EOF（对端无任何字节地关闭）抛 EOFException。
     */
    fun readMessage(input: InputStream): AdbMessage {
        val header = ByteArray(HEADER_SIZE)
        val first = input.read()
        if (first < 0) throw EOFException("connection closed")
        header[0] = first.toByte()
        readExact(input, header, 1, HEADER_SIZE - 1)

        val command = getIntLE(header, 0)
        val arg0 = getIntLE(header, 4)
        val arg1 = getIntLE(header, 8)
        val dataLength = getUIntLE(header, 12)
        val dataCheck = getIntLE(header, 16)
        val magic = getIntLE(header, 20)

        if (magic != (command xor -1)) {
            throw AdbProtocolException(
                "bad magic: 0x${magic.toString(16)} for command 0x${command.toString(16)}",
            )
        }
        if (dataLength > MAX_DATA_LENGTH) {
            throw AdbProtocolException("data_length too large: $dataLength (> $MAX_DATA_LENGTH)")
        }

        val data = ByteArray(dataLength.toInt())
        readExact(input, data, 0, data.size)

        // 0 = 现代对端（版本 ≥0x01000001）忽略 data_check；否则按 AOSP 字节和校验。
        if (dataCheck != 0) {
            val check = dataCheckOf(data)
            if (check != dataCheck) {
                throw AdbProtocolException(
                    "data_check mismatch: expected 0x${dataCheck.toString(16)}, got 0x${check.toString(16)}",
                )
            }
        }
        return AdbMessage(command, arg0, arg1, data)
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var pos = off
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, pos, remaining)
            if (n < 0) throw AdbProtocolException("truncated frame: need $remaining more bytes")
            pos += n
            remaining -= n
        }
    }

    /** AOSP data_check：payload 字节的无符号累加和（wrapping u32）。 */
    private fun dataCheckOf(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xff)
        return sum
    }

    private fun putIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun getIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    /** 无符号 32 位读取，避免 2^31~2^32 的长度被读成负数绕过上限。 */
    private fun getUIntLE(b: ByteArray, off: Int): Long =
        getIntLE(b, off).toLong() and 0xffffffffL
}

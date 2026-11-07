package com.dc.andagent.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * R100 ①：ADB 传输协议帧编解码（纯 JVM/Kotlin，无 Android 依赖，mock 对端共享本实现）。
 *
 * 帧头固定 24 字节，6 个 32 位字段全部按大端（网络字节序）写入：
 *   command(4) arg0(4) arg1(4) data_length(4) data_crc32(4) magic(4)
 *   magic = command xor 0xffffffff；data_crc32 = CRC32(data)。
 *
 * 命令字取自简报：按大端 ASCII 拼写（"CNXN"=0x434e584e …），故大端写出时线上字节
 * 恰为可读 ASCII 标签。注：真实 adbd 帧头为小端，其命令字数值是本常量的字节序反转
 * （CNXN=0x4e584e43）。本轮为纯 mock 对端 PoC，双端共享编解码自洽即可；若第②/③轮
 * 对接真实 adbd/scrcpy，需把编解码整体改小端并同步换成小端命令字常量——详见战报。
 */

// ---- 命令字（大端 ASCII 拼写）----
const val A_CNXN = 0x434e584e
const val A_AUTH = 0x41555448
const val A_OPEN = 0x4f50454e
const val A_OKAY = 0x4f4b4159
const val A_WRTE = 0x57525445
const val A_CLSE = 0x434c5345

// ---- CNXN 参数 ----
const val A_VERSION = 0x01000001
const val MAX_DATA = 4096

// ---- AUTH arg0（真实 adbd：TOKEN=1 / SIGNATURE=2 / RSAPUBLICKEY=3）----
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

    /** 编码为完整帧（24B 头 + data）。 */
    fun encode(msg: AdbMessage): ByteArray {
        val crc = crc32(msg.data)
        val magic = msg.command xor -1
        val out = ByteArray(HEADER_SIZE + msg.data.size)
        putIntBE(out, 0, msg.command)
        putIntBE(out, 4, msg.arg0)
        putIntBE(out, 8, msg.arg1)
        putIntBE(out, 12, msg.data.size)
        putIntBE(out, 16, crc)
        putIntBE(out, 20, magic)
        msg.data.copyInto(out, HEADER_SIZE)
        return out
    }

    fun writeMessage(output: OutputStream, msg: AdbMessage) {
        output.write(encode(msg))
        output.flush()
    }

    /**
     * 从流中读一帧：先读满 24B 头，校验 magic 与 data_length 上限，再按长度读 body，
     * 最后校验 CRC32。干净 EOF（对端无任何字节地关闭）抛 EOFException。
     */
    fun readMessage(input: InputStream): AdbMessage {
        val header = ByteArray(HEADER_SIZE)
        val first = input.read()
        if (first < 0) throw EOFException("connection closed")
        header[0] = first.toByte()
        readExact(input, header, 1, HEADER_SIZE - 1)

        val command = getIntBE(header, 0)
        val arg0 = getIntBE(header, 4)
        val arg1 = getIntBE(header, 8)
        val dataLength = getUIntBE(header, 12)
        val dataCrc32 = getIntBE(header, 16)
        val magic = getIntBE(header, 20)

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

        val crc = crc32(data)
        if (crc != dataCrc32) {
            throw AdbProtocolException(
                "crc32 mismatch: expected 0x${dataCrc32.toString(16)}, got 0x${crc.toString(16)}",
            )
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

    private fun crc32(data: ByteArray): Int =
        CRC32().apply { update(data, 0, data.size) }.value.toInt()

    private fun putIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun getIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    /** 无符号 32 位读取，避免 2^31~2^32 的长度被读成负数绕过上限。 */
    private fun getUIntBE(b: ByteArray, off: Int): Long =
        getIntBE(b, off).toLong() and 0xffffffffL
}

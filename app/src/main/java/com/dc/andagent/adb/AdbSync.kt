package com.dc.andagent.adb

/**
 * R100 ②：ADB SYNC 子协议编解码（纯 JVM，无 Android 依赖）。
 *
 * 请求/响应包通用格式：id(4 字节 ASCII) + length(4 字节小端) + length 字节 data。
 * 无线字节序对齐 AOSP file_sync_protocol：id 直接写 ASCII（如 "SEND"/"DATA"），
 * length 为 u32 小端（注意与 24B 帧头同样是小端，但两者是不同层）。
 *
 * 唯一例外：SEND 的收尾 `DONE` 包 4 字节字段是 mtime（无后续 data）——见 [encodeDone]。
 * 我们只用 RECV（对端的 DONE 长度=0，通用解析即可覆盖），不为 SEND 读响应包。
 *
 * SEND/RECV 的 DATA 包长度上限 64KiB，但单个 WRTE 帧受 CNXN 协商的 max payload
 * （本客户端 4096B）限制，故一个 SYNC 包会跨多个 WRTE 帧——[SyncPacketReader]
 * 负责跨帧累积字节。
 */
data class SyncPacket(val id: String, val data: ByteArray)

object AdbSync {
    const val SYNC_DATA_MAX = 64 * 1024
    /** 默认推送权限 0644 = 十进制 420（AOSP SEND 的 "<path>,<mode>" mode 为十进制）。 */
    const val MODE_DEFAULT = 420
    /** 疯包防线：单个 SYNC 包 body 超过 1MiB 直接拒绝。 */
    const val SYNC_MAX_PACKET = 1024 * 1024

    const val ID_STAT = "STAT"
    const val ID_SEND = "SEND"
    const val ID_RECV = "RECV"
    const val ID_DATA = "DATA"
    const val ID_DONE = "DONE"
    const val ID_OKAY = "OKAY"
    const val ID_FAIL = "FAIL"
    const val ID_DENT = "DENT"
    const val ID_QUIT = "QUIT"

    /** 通用包：id(4) + len(4 LE) + data。 */
    fun encode(id: String, data: ByteArray): ByteArray {
        require(id.length == 4) { "SYNC id 必须是 4 字节：$id" }
        val out = ByteArray(8 + data.size)
        id.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        putIntLE(out, 4, data.size)
        data.copyInto(out, 8)
        return out
    }

    /** `SEND` 请求：data = "<remotePath>,<mode>"。 */
    fun encodeSend(remotePath: String, mode: Int = MODE_DEFAULT): ByteArray =
        encode(ID_SEND, "$remotePath,$mode".toByteArray(Charsets.UTF_8))

    /** `RECV` 请求：data = 远程路径。 */
    fun encodeRecv(remotePath: String): ByteArray =
        encode(ID_RECV, remotePath.toByteArray(Charsets.UTF_8))

    fun encodeStat(remotePath: String): ByteArray =
        encode(ID_STAT, remotePath.toByteArray(Charsets.UTF_8))

    fun encodeData(data: ByteArray): ByteArray = encode(ID_DATA, data)

    /**
     * SEND 收尾 `DONE`：4 字节字段是 mtime（u32 小端），无后续 data。
     * 仅供本机作为 SEND 发起方写出；不要喂给 [SyncPacketReader]。
     */
    fun encodeDone(mtime: Int = 0): ByteArray {
        val out = ByteArray(8)
        ID_DONE.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        putIntLE(out, 4, mtime)
        return out
    }

    /** `FAIL` 包 data 是错误明文（可能带尾 0）。 */
    fun failMessage(packet: SyncPacket): String =
        String(packet.data, Charsets.UTF_8).trimEnd('\u0000')

    private fun putIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }
}

/**
 * 流式 SYNC 包解析器：WRTE 帧边界与 SYNC 包边界不对其，需跨帧累积。
 * feed() 追加 WRTE payload；poll() 在凑够一个完整包（8B 头 + body）时返回它。
 * 对端 RECV 的 `DONE` 包 len=0，走通用 id+len+data 解析即可覆盖。
 */
class SyncPacketReader {
    private var buf = ByteArray(1024)
    private var size = 0

    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        ensureCapacity(size + bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    /** 返回下一个完整包；不足 8B 头或 body 不全时返回 null。 */
    fun poll(): SyncPacket? {
        if (size < 8) return null
        val id = String(buf, 0, 4, Charsets.US_ASCII)
        // DONE 例外：4 字节字段是 mtime（SEND 收尾）或 0（RECV 收尾），不是 data 长度，
        // 该包固定 8 字节、无 body——必须在长度上限检查之前特判（mtime 会远超上限）。
        if (id == AdbSync.ID_DONE) {
            consume(8)
            return SyncPacket(id, ByteArray(0))
        }
        val len = getIntLE(buf, 4)
        if (len < 0 || len > AdbSync.SYNC_MAX_PACKET) {
            throw AdbProtocolException("SYNC 包长度异常：$len (id=$id)")
        }
        if (size < 8 + len) return null
        val data = buf.copyOfRange(8, 8 + len)
        consume(8 + len)
        return SyncPacket(id, data)
    }

    private fun consume(n: Int) {
        val remaining = size - n
        if (remaining > 0) buf.copyInto(buf, 0, n, size) // System.arraycopy 处理重叠
        size = remaining
    }

    private fun ensureCapacity(need: Int) {
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap *= 2
        buf = buf.copyOf(cap)
    }

    private fun getIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
}

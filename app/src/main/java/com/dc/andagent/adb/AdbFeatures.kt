package com.dc.andagent.adb

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * CNXN feature names describe capabilities this client actually supports.
 *
 * R124 三方对照（scrcpy / iadb-ios / cocool97 adb_client / AOSP）后，banner features
 * 做成可切换集合，供 ColorOS 真机 A/B：
 *  - [FEATURES_MINIMAL]：R120 起用的单 feature（本仓库旧值）。
 *  - [FEATURES_WIRELESS]：iadb-ios（已验证的无线调试客户端）用的集合，
 *    见 ADBMessage.swift:116 `host::features=shell_v2,stat_v2,ls_v2`。
 *  - [FEATURES_PC_FULL]：PC adb 的完整集合，见 AOSP transport.cpp:1197-1237
 *    `supported_features()`；**刻意不含 delayed_ack**——adbd 协商出 delayed_ack 后会
 *    拒绝 `A_OPEN(arg1=0)`（adb.cpp:507-512），而本客户端 OPEN 的 arg1 固定为 0。
 *    另注 sendrecv_v2* 会改变 adbd 对 sync: 的期望，仅适合真机 A/B，不作默认。
 */
object AdbFeatures {
    const val SHELL_V2 = "shell_v2"

    val FEATURES_MINIMAL = listOf(SHELL_V2)
    val FEATURES_WIRELESS = listOf(SHELL_V2, "stat_v2", "ls_v2")
    val FEATURES_PC_FULL = listOf(
        "shell_v2", "cmd", "stat_v2", "ls_v2", "fixed_push_mkdir", "apex", "abb",
        "fixed_push_symlink_timestamp", "abb_exec", "remount_shell", "track_app",
        "sendrecv_v2", "sendrecv_v2_brotli", "sendrecv_v2_lz4", "sendrecv_v2_zstd",
        "sendrecv_v2_dry_run_send", "openscreen_mdns", "devicetracker_proto_format",
        "devraw", "app_info", "server_status",
    )

    /** R124 真机 A/B 开关：改这一行即可切换 CNXN banner 的 features 列表。 */
    val ACTIVE_FEATURES: List<String> = FEATURES_WIRELESS

    fun hostBanner(features: List<String> = ACTIVE_FEATURES): String =
        "host::features=${features.joinToString(",")}"

    fun parseBanner(bytes: ByteArray): Set<String> {
        val banner = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        val properties = banner.substringAfter("::", "")
        return properties.split(';').firstOrNull { it.startsWith("features=") }
            ?.removePrefix("features=")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun useShellV2(peerFeatures: Set<String>): Boolean = SHELL_V2 in peerFeatures

    // AOSP daemon/services.cpp ShellService 只把 ':' 前的参数段按 ',' 拆开解析
    // （services.cpp:86-122）；命令段原样传递，引号与逗号无需转义。
    // 三方对照：iadb-ios 用 `shell,v2,raw:`（ADBClient.swift:218/278）显式禁 PTY；
    // AOSP 对非空命令本就默认 kRaw（services.cpp:103），故 raw 做成可选项。
    fun shellService(command: String, v2: Boolean, raw: Boolean = false): String =
        when {
            v2 && raw -> "shell,v2,raw:$command"
            v2 -> "shell,v2:$command"
            else -> "shell:$command"
        }

    /**
     * R124 诊断：把 CNXN/OPEN 的 payload 解码成可打印单行（NUL 终止、控制字符转义、
     * 限量），用于把目标机 features 列表全文写进帧序列 evidence。
     */
    fun bannerText(bytes: ByteArray, limit: Int = 512): String {
        val sb = StringBuilder()
        for (ch in bytes.toString(Charsets.UTF_8)) {
            if (ch == '\u0000') break // AOSP StripTrailingNulls：NUL 之后不属于服务名/banner
            sb.append(
                when {
                    ch == '\n' -> "\\n"
                    ch == '\r' -> "\\r"
                    ch == '\t' -> "\\t"
                    Character.isISOControl(ch.code) -> "\\u%04x".format(ch.code)
                    else -> ch.toString()
                },
            )
            if (sb.length >= limit) break
        }
        return sb.toString()
    }
}

/** AOSP shell_protocol.h: one byte ID followed by a four-byte little-endian length. */
class ShellV2Decoder {
    private val pending = ByteArrayOutputStream()
    data class Chunk(val id: Int, val data: ByteArray)

    fun feed(bytes: ByteArray): List<Chunk> {
        pending.write(bytes)
        val source = pending.toByteArray()
        val chunks = mutableListOf<Chunk>()
        var offset = 0
        while (source.size - offset >= 5) {
            val size = (source[offset + 1].toLong() and 255) or
                ((source[offset + 2].toLong() and 255) shl 8) or
                ((source[offset + 3].toLong() and 255) shl 16) or
                ((source[offset + 4].toLong() and 255) shl 24)
            if (size > MAX_CHUNK) throw IOException("shell v2 chunk too large: $size")
            if (source.size - offset - 5 < size) break
            val end = offset + 5 + size.toInt()
            chunks += Chunk(source[offset].toInt() and 255, source.copyOfRange(offset + 5, end))
            offset = end
        }
        pending.reset()
        pending.write(source, offset, source.size - offset)
        return chunks
    }

    companion object { const val MAX_CHUNK = 16 * 1024 * 1024 }
}

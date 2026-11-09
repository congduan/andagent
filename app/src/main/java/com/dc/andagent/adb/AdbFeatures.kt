package com.dc.andagent.adb

import java.io.ByteArrayOutputStream
import java.io.IOException

/** CNXN feature names describe capabilities this client actually supports. */
object AdbFeatures {
    const val SHELL_V2 = "shell_v2"
    private val supported = listOf(SHELL_V2)

    fun hostBanner(): String = "host::features=${supported.joinToString(",")}"

    fun parseBanner(bytes: ByteArray): Set<String> {
        val banner = bytes.toString(Charsets.UTF_8).trimEnd('\u0000')
        val properties = banner.substringAfter("::", "")
        return properties.split(';').firstOrNull { it.startsWith("features=") }
            ?.removePrefix("features=")?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    fun useShellV2(peerFeatures: Set<String>): Boolean = SHELL_V2 in peerFeatures

    // AOSP daemon/services.cpp splits only the argument segment before ':'.
    // The command segment is passed verbatim; quotes and commas need no service-name escaping.
    fun shellService(command: String, v2: Boolean): String =
        (if (v2) "shell,v2:" else "shell:") + command
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

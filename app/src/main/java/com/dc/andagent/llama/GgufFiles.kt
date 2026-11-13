package com.dc.andagent.llama

import java.io.File
import java.io.FileInputStream

internal object GgufFiles {
    fun safeName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9._-]{1,100}")) &&
        name != "." && name != ".." && !name.startsWith('.')

    fun valid(file: File): Boolean {
        if (!file.isFile || file.length() < 32) return false
        return FileInputStream(file).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
        }
    }
}

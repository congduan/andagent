package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/** R103 必修 3/5：OTG export 白名单前缀 + import 输入流关闭（纯 JVM 可测层）。 */
class OtgToolsTest {

    /** 包装流：记录 close；关闭后 / 指定调用次数时 read 会抛异常，用于检出泄漏。 */
    private class TrackingInput(
        private val data: ByteArray,
        private val throwOnCall: Int = -1,
    ) : InputStream() {
        var closed = false
            private set
        private var pos = 0
        private var calls = 0

        private fun beforeRead() {
            if (closed) throw IOException("read after close")
            calls++
            if (throwOnCall > 0 && calls >= throwOnCall) throw IOException("boom")
        }

        override fun read(): Int {
            beforeRead()
            return if (pos >= data.size) -1 else data[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            beforeRead()
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos)
            data.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }

        override fun close() { closed = true }
    }

    // ---- 必修 3：白名单 canonical 前缀 -------------------------------
    @Test
    fun exportWhitelistedOnlyAllowsPathsInsideDirectories() {
        val dirs = listOf(
            File("/sdcard/Download/AndAgentSsh"),
            File("/data/data/com.dc.andagent/files/ssh_downloads"),
        )
        assertTrue(exportWhitelisted("/sdcard/Download/AndAgentSsh/a.bin", dirs))
        assertTrue(exportWhitelisted("/sdcard/Download/AndAgentSsh/sub/a.bin", dirs))
        assertTrue(exportWhitelisted("/data/data/com.dc.andagent/files/ssh_downloads/x", dirs))
        assertFalse("目录自身不算命中", exportWhitelisted("/sdcard/Download/AndAgentSsh", dirs))
        assertFalse("白名单外路径必须拒绝", exportWhitelisted("/sdcard/Download/Other/secret.txt", dirs))
        assertFalse("前缀碰撞必须拒绝", exportWhitelisted("/sdcard/Download/AndAgentSshEvil/a.bin", dirs))
        assertFalse("系统路径必须拒绝", exportWhitelisted("/etc/passwd", dirs))
    }

    // ---- 必修 5：import 输入流全路径关闭 -------------------------------
    @Test
    fun importInputClosedOnSuccess() {
        val s = TrackingInput("hello".toByteArray())
        val r = useOtgImportInput(s) { inp ->
            val buf = ByteArray(16)
            assertEquals(5, inp.read(buf))
            "OK"
        }
        assertEquals("OK", r)
        assertTrue("成功路径输入流必须关闭", s.closed)
    }

    @Test
    fun importInputClosedOnException() {
        val s = TrackingInput("hello".toByteArray(), throwOnCall = 2)
        val thrown = runCatching {
            useOtgImportInput(s) { inp ->
                inp.read(ByteArray(16))   // 第 1 次：成功
                inp.read(ByteArray(16))   // 第 2 次：流抛 IOException（模拟超限 / IO 异常）
                "unused"
            }
        }.exceptionOrNull()
        assertTrue("消费抛异常应原样抛出，实际=$thrown", thrown is IOException)
        assertTrue("异常 / 超限路径输入流必须关闭", s.closed)
    }

    @Test
    fun importInputNullReturnsNull() {
        assertNull(useOtgImportInput(null) { "unused" })
    }
}

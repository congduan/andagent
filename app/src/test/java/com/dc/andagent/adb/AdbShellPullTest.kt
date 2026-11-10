package com.dc.andagent.adb

import com.dc.andagent.adb.AdbClient.AdbSyncResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * R130：shell dd|base64 分块降级的纯 JVM 断言（无 socket / 无 Android）。
 * fake shell 模拟 toybox base64 的 76 列换行，按 `skip=N` 返回对应块的 base64，
 * 驱动「逐块独立 padding → 不能拼 base64 字符串再 decode」与终检契约。
 */
class AdbShellPullTest {

    private val remote = "/sdcard/DCIM/a.mp4"

    /** 模拟 `stat -c %s` 与设备侧 `(dd ... | base64) && echo MARK`，带 76 列换行。 */
    private class FakeShell(
        private val data: ByteArray,
        private val failAtIndex: Int = -1,      // 1-based；-1=全部成功
        private val statOutput: String? = null, // null=正常返回 data.size
    ) {
        val commands = mutableListOf<String>()
        fun shell(cmd: String): String {
            commands += cmd
            return when {
                cmd.startsWith("stat ") -> statOutput ?: "${data.size}\n"
                cmd.contains("dd if=") -> {
                    val idx = Regex("""skip=(\d+)""").find(cmd)!!.groupValues[1].toInt()
                    if (idx + 1 == failAtIndex) return "dd: read error\n[exit code: 1]\n"
                    val from = idx * AdbShellPull.CHUNK_BYTES
                    val to = minOf(from + AdbShellPull.CHUNK_BYTES, data.size)
                    val b64 = Base64.getEncoder().encodeToString(data.copyOfRange(from, to))
                    b64.chunked(76).joinToString("\n") + "\n" + AdbChunkPull.DONE_MARK + "\n"
                }
                else -> AdbChunkPull.DONE_MARK + "\n"
            }
        }
    }

    @Test
    fun chunkCmdUsesDdSkipCountAndSuppressesSummary() {
        val cmd = AdbShellPull.chunkCmd(remote, 7)
        assertTrue("dd 必须存在", cmd.contains("dd if="))
        assertTrue("必须单引号转义路径", cmd.contains(AdbChunkPull.shellQuote(remote)))
        assertTrue("必须按块 skip", cmd.contains("skip=7"))
        assertTrue("每块只读一块", cmd.contains("count=1"))
        assertTrue("bs 必须为块大小", cmd.contains("bs=${AdbShellPull.CHUNK_BYTES}"))
        assertTrue("dd 摘要须进 /dev/null 防止污染 base64", cmd.contains("2>/dev/null"))
        assertTrue("base64 无 FILE 参数（读 stdin）", cmd.contains("| base64"))
        assertTrue("必须带完成标记", cmd.endsWith("&& echo ${AdbChunkPull.DONE_MARK}"))
        assertEquals("stat -c %s ${AdbChunkPull.shellQuote(remote)}", AdbShellPull.sizeCmd(remote))
    }

    @Test
    fun decodeChunkToleratesWrappingAndDoneMark() {
        val raw = "hello adb shell".toByteArray()
        val b64 = Base64.getEncoder().encodeToString(raw)
        val wrapped = b64.chunked(76).joinToString("\n") + "\n" + AdbChunkPull.DONE_MARK + "\n"
        assertArrayEquals(raw, AdbShellPull.decodeChunk(wrapped))
    }

    @Test
    fun decodeChunkRejectsErrorExitCodeTruncationAndBadChars() {
        val b64 = Base64.getEncoder().encodeToString("x".toByteArray())
        val good = "$b64\n${AdbChunkPull.DONE_MARK}\n"
        assertTrue(AdbShellPull.decodeChunk("ERROR: ADB shell 执行失败") == null)
        assertTrue("缺完成标记必须失败（防虚报成功）", AdbShellPull.decodeChunk(b64) == null)
        assertTrue("带 [exit code] 必须失败", AdbShellPull.decodeChunk("$good\n[exit code: 1]") == null)
        assertTrue("带 [已截断] 必须失败（不吞半截文件）", AdbShellPull.decodeChunk("$good\n[已截断：原始 300000 字节]") == null)
        assertTrue("非法字符必须失败", AdbShellPull.decodeChunk("$b64@@\n${AdbChunkPull.DONE_MARK}") == null)
        assertTrue("空块必须失败", AdbShellPull.decodeChunk("${AdbChunkPull.DONE_MARK}\n") == null)
    }

    @Test
    fun pullConcatenatesMultipleChunksAndMatchesStatSize() {
        // 2 整块 + 100 字节 → 3 块，最后一块短；逐块独立 padding，拼接必须逐字节相等。
        val size = AdbShellPull.CHUNK_BYTES * 2 + 100
        val data = ByteArray(size) { (it % 251).toByte() }
        val fake = FakeShell(data)
        val r = AdbShellPull.pull(remote, 8L * 1024 * 1024) { fake.shell(it) }
        assertTrue("pull 应成功，实际=$r", r is AdbSyncResult.Pulled)
        assertArrayEquals(data, (r as AdbSyncResult.Pulled).bytes)
        assertEquals("stat + 3 块 = 4 条命令", 4, fake.commands.size)
        assertTrue(r.evidence.contains("共 3 块"))
        assertTrue(fake.commands[1].contains("skip=0"))
        assertTrue(fake.commands[3].contains("skip=2"))
    }

    @Test
    fun pullZeroByteFileReturnsEmpty() {
        val fake = FakeShell(ByteArray(0))
        val r = AdbShellPull.pull(remote, 1024L) { fake.shell(it) }
        assertTrue(r is AdbSyncResult.Pulled)
        assertEquals(0, (r as AdbSyncResult.Pulled).bytes.size)
        assertEquals("空文件只需 stat，不应发 dd", 1, fake.commands.size)
    }

    @Test
    fun pullRejectsOversizeBeforeAnyChunk() {
        val data = ByteArray(1024)
        val fake = FakeShell(data)
        val r = AdbShellPull.pull(remote, 512L) { fake.shell(it) }
        assertTrue(r is AdbSyncResult.Failed)
        assertTrue((r as AdbSyncResult.Failed).reason.contains("上限"))
        assertEquals("超限只探测尺寸，不发 dd", 1, fake.commands.size)
    }

    @Test
    fun pullStopsOnMidChunkFailureWithoutReturningPartialFile() {
        val data = ByteArray(AdbShellPull.CHUNK_BYTES * 2) { it.toByte() }
        val fake = FakeShell(data, failAtIndex = 2)
        val r = AdbShellPull.pull(remote, 8L * 1024 * 1024) { fake.shell(it) }
        assertTrue(r is AdbSyncResult.Failed)
        assertTrue((r as AdbSyncResult.Failed).reason.contains("第 2/2 块"))
        assertTrue("失败不得返回 Pulled（不落半截文件）", (r as AdbSyncResult.Failed).reason.isNotEmpty())
    }

    @Test
    fun pullRejectsUnparseableStat() {
        val fake = FakeShell(ByteArray(10), statOutput = "stat: No such file\n")
        val r = AdbShellPull.pull(remote, 1024L) { fake.shell(it) }
        assertTrue(r is AdbSyncResult.Failed)
        assertTrue((r as AdbSyncResult.Failed).reason.contains("无法探测"))
    }

    @Test
    fun chunkCountRoundsUp() {
        assertEquals(0, AdbShellPull.chunkCount(0))
        assertEquals(1, AdbShellPull.chunkCount(1))
        assertEquals(1, AdbShellPull.chunkCount(AdbShellPull.CHUNK_BYTES.toLong()))
        assertEquals(2, AdbShellPull.chunkCount(AdbShellPull.CHUNK_BYTES.toLong() + 1))
    }
}

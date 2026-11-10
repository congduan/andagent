package com.dc.andagent.adb

import com.dc.andagent.adb.AdbClient.AdbSyncResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 与 toybox `split -a 3` 一致的字母后缀：i=0→aaa, 1→aab, ... */
private fun suffix3(i: Int): String {
    val a = 'a'.code
    return "${(a + (i / 676) % 26).toChar()}${(a + (i / 26) % 26).toChar()}${(a + i % 26).toChar()}"
}

private fun dfOutput(availableKb: Long): String =
    "Filesystem     1K-blocks      Used Available Use% Mounted on\n" +
        "/dev/block/dm-9   6000000   4000000  $availableKb  67% /data\n"

/**
 * R127：大文件分块拉取的纯函数 + 编排断言（纯 JVM，无 socket / 无 Android）。
 * fake Port 的 `ls -1` 故意返回**乱序**分块名，以驱动 sortParts 的枚举排序验证；
 * 记录所有 shell 命令，便于断言「失败清理」「重入先 rm 后 split」。
 */
class AdbChunkPullTest {

    /** 可注入失败块、df 可用空间、split 报错、rm 清理失败的 fake Port。
     *  padToTotal>0 时把最后一块替换为零字节数组，使各块总和恰为该值——驱动「拼接终检
     *  必须等于 stat 尺寸」的成功路径（Codex review 必修 4 落地后的新契约）。
     *  rmFailAt 为 1-based 的 rm 调用序号（1=重入清理，2=收尾清理），命中则该次 rm
     *  返回带 [exit code: 1] 的失败输出——驱动「清理结果必须进返回值」断言（必修 1）。 */
    private class FakePort(
        partBytes: List<ByteArray>,
        private val failAtIndex: Int = -1,             // 1-based；-1=全部成功
        private val availableKb: Long = 100L * 1024,  // 默认充足
        private val splitError: Boolean = false,
        padToTotal: Long = 0,
        private val rmFailAt: Int = -1,               // 1-based rm 调用序号；-1=全部成功
    ) : AdbChunkPull.Port {
        /** padToTotal 生效后的实际分块字节（公开供测试断言拼接结果）。 */
        val effective: List<ByteArray> = if (padToTotal > 0) {
            val others = partBytes.dropLast(1).sumOf { it.size }.toLong()
            partBytes.dropLast(1) + ByteArray((padToTotal - others).toInt())
        } else partBytes
        val commands = mutableListOf<String>()
        // 正确顺序的全路径（aaa..）
        val partPaths = effective.indices.map { AdbChunkPull.PART_GLOB_BASE + suffix3(it) }
        private var rmCallCount = 0

        /**
         * shellDone 对齐：实现侧把关键命令包成 `(cmd) && echo AA_CHUNK_CMD_DONE`（二轮
         * review 必修：防空输出 EOF 虚报成功），故 fake 需剥离该包装后按裸命令前缀分发，
         * 成功回执同样携带完成标记。`doneOut("")` = 命令成功但无输出（真机 rm/split 形态）。
         */
        private fun doneOut(raw: String): String =
            if (raw.isEmpty()) AdbChunkPull.DONE_MARK
            else raw + (if (raw.endsWith("\n")) "" else "\n") + AdbChunkPull.DONE_MARK
        private fun bare(cmd: String): String =
            cmd.removePrefix("(").substringBefore(") && echo ").trimStart()

        override fun shell(cmd: String): String {
            commands += cmd
            val c = bare(cmd)
            return when {
                c.startsWith("df -k") -> doneOut(dfOutput(availableKb))
                c.startsWith("ls -1") -> doneOut(partPaths.asReversed().joinToString("\n")) // 乱序
                c.startsWith("split") -> if (splitError) "partial write\n[exit code: 1]" else doneOut("")
                c.startsWith("rm -f") -> {
                    rmCallCount++
                    if (rmCallCount == rmFailAt) "rm: cleanup failed\n[exit code: 1]" else doneOut("")
                }
                else -> ""
            }
        }

        override fun pullSingle(remotePath: String, maxBytes: Long): AdbSyncResult {
            val idx0 = partPaths.indexOf(remotePath)
            if (idx0 < 0) return AdbSyncResult.Failed("未知分块 $remotePath")
            val idx1 = idx0 + 1
            if (idx1 == failAtIndex) return AdbSyncResult.Failed("模拟第 $idx1 块失败")
            return AdbSyncResult.Pulled(effective[idx0])
        }

        fun rmCount(): Int = commands.count { bare(it).startsWith("rm -f") }
        fun has(prefix: String): Boolean = commands.any { bare(it).startsWith(prefix) }
        fun indexOf(prefix: String): Int = commands.indexOfFirst { bare(it).startsWith(prefix) }
    }

    private val bigSize = 14L * 1024 * 1024
    private val bigCap = 512L * 1024 * 1024

    /* ---------------- 纯函数：阈值 / 分块计算 / 文案 ---------------- */

    @Test
    fun shouldChunkUses4MbThreshold() {
        assertFalse(AdbChunkPull.shouldChunk(471L * 1024))              // 真机 471K 小文件
        assertFalse(AdbChunkPull.shouldChunk(4L * 1024 * 1024))         // 恰好 4MB 不触发
        assertTrue(AdbChunkPull.shouldChunk(4L * 1024 * 1024 + 1))
        assertTrue(AdbChunkPull.shouldChunk(14L * 1024 * 1024))
        assertTrue(AdbChunkPull.shouldChunk(10L * 1024 * 1024, 8L * 1024 * 1024))
        assertFalse(AdbChunkPull.shouldChunk(10L * 1024 * 1024, 16L * 1024 * 1024))
    }

    @Test
    fun chunkCountRoundsUp() {
        assertEquals(1, AdbChunkPull.chunkCount(1))
        assertEquals(1, AdbChunkPull.chunkCount(AdbChunkPull.CHUNK_SIZE_BYTES))
        assertEquals(2, AdbChunkPull.chunkCount(AdbChunkPull.CHUNK_SIZE_BYTES + 1))
        assertEquals(7, AdbChunkPull.chunkCount(14L * 1024 * 1024))       // 14MB/2MB
        assertEquals(2048, AdbChunkPull.chunkCount(4096L * 1024 * 1024))  // 4GB 上限
    }

    @Test
    fun formatMbIsLocaleFree() {
        assertEquals("0.4", AdbChunkPull.formatMb(471L * 1024))
        assertEquals("14.0", AdbChunkPull.formatMb(14L * 1024 * 1024))
        assertEquals("1.5", AdbChunkPull.formatMb(1_572_864L))
        assertEquals("0.0", AdbChunkPull.formatMb(0))
    }

    @Test
    fun concatReassemblesInGivenOrder() {
        val parts = listOf("abc".toByteArray(), "DE".toByteArray(), byteArrayOf(1, 2, 3))
        assertArrayEquals("abcDE".toByteArray() + byteArrayOf(1, 2, 3), AdbChunkPull.concat(parts))
        assertArrayEquals(ByteArray(0), AdbChunkPull.concat(emptyList()))
    }

    /* ---------------- 纯函数：枚举排序（字典序 vs 数字序） ---------------- */

    @Test
    fun sortPartsAlphabeticSuffixesUseLexicographicOrder() {
        // toybox split 默认字母后缀（定宽），字典序即正确拼接顺序，>20 块也成立。
        val b = AdbChunkPull.PART_GLOB_BASE
        val names = listOf(b + "ab", b + "ba", b + "aa", b + "ac", b + "az")
        assertEquals(
            listOf(b + "aa", b + "ab", b + "ac", b + "az", b + "ba"),
            AdbChunkPull.sortParts(names),
        )
    }

    @Test
    fun sortPartsNumericSuffixesUseNumericOrder() {
        // GNU -d / --numeric-suffixes（Android toybox 不支持）的字典序陷阱：10 会排在 2 前。
        // 纯数字后缀时按数值排序，规避该陷阱。
        val b = AdbChunkPull.PART_GLOB_BASE
        val names = listOf(b + "10", b + "2", b + "1", b + "20", b + "3")
        assertEquals(
            listOf(b + "1", b + "2", b + "3", b + "10", b + "20"),
            AdbChunkPull.sortParts(names),
        )
    }

    @Test
    fun sortPartsThreeLetterSuffixesStayInOrder() {
        val b = AdbChunkPull.PART_GLOB_BASE
        val names = (0 until 30).map { b + suffix3(it) }.asReversed()
        assertEquals((0 until 30).map { b + suffix3(it) }, AdbChunkPull.sortParts(names))
    }

    /* ---------------- 纯函数：split/ls/df/stat 输出解析 ---------------- */

    @Test
    fun parseSizeHandlesStatOutputAndErrors() {
        assertEquals(14680064L, AdbChunkPull.parseSize("14680064\n"))
        assertEquals(471000L, AdbChunkPull.parseSize("  471000\n"))
        assertEquals(15L, AdbChunkPull.parseSize("15"))
        assertNull(AdbChunkPull.parseSize("ERROR: ADB shell 执行失败"))
        assertNull(AdbChunkPull.parseSize("(空)"))
        assertNull(AdbChunkPull.parseSize("stat: '/x': No such file or directory"))
    }

    @Test
    fun parsePartsFiltersNoiseAndLiteralGlob() {
        val a = AdbChunkPull.PART_GLOB_BASE + "aaa"
        val b = AdbChunkPull.PART_GLOB_BASE + "aab"
        val out = "$a\n$b\n${AdbChunkPull.PART_GLOB}\nls: ... No such file or directory\n"
        assertEquals(listOf(a, b), AdbChunkPull.parseParts(out))
        assertEquals(emptyList<String>(), AdbChunkPull.parseParts("ERROR: ..."))
    }

    @Test
    fun parseDfAvailableKbFindsDataLine() {
        assertEquals(360000L, AdbChunkPull.parseDfAvailableKb(dfOutput(360000L)))
        assertNull(AdbChunkPull.parseDfAvailableKb("ERROR: df"))
        assertNull(AdbChunkPull.parseDfAvailableKb("Filesystem 1K-blocks Used Available Use% Mounted on"))
    }

    /* ---------------- 纯函数：命令构造 / 单引号注入安全 ---------------- */

    @Test
    fun commandBuildersUseToyboxFlagsAndSafeQuoting() {
        val remote = "/sdcard/DCIM/a b'c.mp4"
        assertTrue(AdbChunkPull.sizeCmd(remote).startsWith("stat -c %s "))
        assertTrue(AdbChunkPull.sizeCmd(remote).contains(AdbChunkPull.shellQuote(remote)))
        val split = AdbChunkPull.splitCmd(remote)
        // split/df/ls/rm 经 shellDone 包装成 "(cmd) && echo AA_CHUNK_CMD_DONE"（二轮必修：
        // 防空输出 EOF 虚报成功），断言核内心体 + 完成标记尾。
        assertTrue(split.contains("split -b ${AdbChunkPull.CHUNK_SIZE_BYTES} -a ${AdbChunkPull.SUFFIX_LEN} "))
        assertTrue(split.contains(AdbChunkPull.PART_GLOB_BASE))
        assertTrue(split.endsWith("&& echo ${AdbChunkPull.DONE_MARK}"))
        assertEquals("(df -k ${AdbChunkPull.PART_DIR}) && echo ${AdbChunkPull.DONE_MARK}", AdbChunkPull.dfCmd())
        assertEquals("(ls -1 ${AdbChunkPull.PART_GLOB}) && echo ${AdbChunkPull.DONE_MARK}", AdbChunkPull.lsCmd())
        assertEquals("(rm -f ${AdbChunkPull.PART_GLOB}) && echo ${AdbChunkPull.DONE_MARK}", AdbChunkPull.rmCmd())
    }

    @Test
    fun shellQuoteEscapesEmbeddedSingleQuotes() {
        val q = AdbChunkPull.shellQuote("a'b")
        assertTrue(q.startsWith("'") && q.endsWith("'"))
        assertTrue("须用 '\\'' 结束重启转义单引号，实际=$q", q.contains("'\\''"))
        assertEquals("'plain'", AdbChunkPull.shellQuote("plain"))
    }

    @Test
    fun isLargeSyncEarlyCloseMatchesKnownSymptoms() {
        assertTrue(AdbChunkPull.isLargeSyncEarlyClose("SYNC RECV 未收到 DONE（流提前关闭，已收 0 字节）"))
        assertTrue(AdbChunkPull.isLargeSyncEarlyClose("sync: 服务被对端关闭（流提前关闭，已收 0 字节）"))
        assertFalse(AdbChunkPull.isLargeSyncEarlyClose("远程文件超过 1MB 上限（已收 2000000 字节）"))
        assertFalse(AdbChunkPull.isLargeSyncEarlyClose("SYNC FAIL no such file"))
        // R130：纯 OPEN 阶段拒绝不再触发 sync 分块（分块只会重复 N 次同样被拒的 OPEN），
        // 应直接落工具层 shell 降级。真「流提前关闭」仍走分块。
        assertFalse(AdbChunkPull.isLargeSyncEarlyClose("sync: 服务被对端关闭"))
        assertFalse(AdbChunkPull.isLargeSyncEarlyClose("sync: 服务被拒绝"))
    }

    /* ---------------- 编排：成功 / 失败清理 / 重入 / 诊断 ---------------- */

    @Test
    fun chunkedSuccessReassemblesInSortedOrderAndCleansUp() {
        // 分块字节数总和 pad 到 14MB（= stat 尺寸），驱动「拼接终检 == stat」成功契约；
        // 乱序 ls 仍须按正确顺序拼接。
        val fake = FakePort((0 until 7).map { i -> "chunk$i;".toByteArray() }, padToTotal = bigSize)
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        assertTrue("应成功，实际=$result", result is AdbSyncResult.Pulled)
        val pulled = result as AdbSyncResult.Pulled
        assertArrayEquals(AdbChunkPull.concat(fake.effective), pulled.bytes)
        assertEquals(bigSize, pulled.bytes.size.toLong())
        assertTrue(pulled.evidence.contains("大文件分块模式（文件 14.0 MB，14680064 字节）"))
        assertTrue(pulled.evidence.contains("枚举到 7 块"))
        assertTrue(pulled.evidence.contains("第 1/7 块 ok"))
        assertTrue(pulled.evidence.contains("第 7/7 块 ok"))
        assertTrue(fake.has("df -k"))
        assertTrue(fake.has("split"))
        assertTrue(fake.has("ls -1"))
        assertTrue("重入清理 + finally 清理", fake.rmCount() >= 2)
        assertTrue("重入清理须在 split 之前", fake.indexOf("rm -f") < fake.indexOf("split"))
    }

    /** Codex review 必修 4 防回归：拼接字节数与 stat 尺寸不符必须拒绝（截断文件不落盘）。 */
    @Test
    fun chunkedFinalSizeMismatchIsRejected() {
        val fake = FakePort((0 until 7).map { i -> "chunk$i;".toByteArray() }) // 总和 != 14MB
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)
        assertTrue(result is AdbSyncResult.Failed)
        val failed = result as AdbSyncResult.Failed
        assertTrue(failed.reason.contains("与远程文件大小"))
        assertTrue(failed.evidence.contains("终检失败"))
    }

    /** Codex review 必修 4 防回归：枚举块数远少于预估（split 部分写入）必须在拼接前中止。 */
    @Test
    fun chunkedPartCountShortfallIsRejectedBeforePull() {
        // 只给 1 块（预估 7 块），块内字节数碰巧凑成 14MB 也必须在逐块拉取前拒绝。
        val fake = FakePort(listOf(ByteArray(14 * 1024 * 1024)))
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)
        assertTrue(result is AdbSyncResult.Failed)
        val failed = result as AdbSyncResult.Failed
        assertTrue(failed.reason.contains("分块数不符"))
        assertFalse("拒绝后不应逐块拉取", failed.evidence.contains("第 1/1 块 ok"))
    }

    /** Codex review 必修 4 防回归：openStream 的 `[exit code: n]` 尾巴必须判为 split 失败。 */
    @Test
    fun shellFailedDetectsExitCodeMarker() {
        assertTrue(AdbChunkPull.shellFailed("some output\n[exit code: 1]"))
        assertTrue(AdbChunkPull.shellFailed("ERROR: anything"))
        assertFalse(AdbChunkPull.shellFailed("clean output"))
        assertFalse(AdbChunkPull.shellFailed(""))
    }

    @Test
    fun chunkedFailureCleansUpAndReportsCompletedCount() {
        val partBytes = (0 until 7).map { byteArrayOf((65 + it).toByte()) }
        val fake = FakePort(partBytes, failAtIndex = 3)
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        assertTrue("应失败，实际=$result", result is AdbSyncResult.Failed)
        val failed = result as AdbSyncResult.Failed
        assertTrue(failed.reason.contains("第 3/7 块"))
        assertTrue(failed.reason.contains("已完成 2 块"))
        assertTrue(failed.reason.contains("临时分块已清理"))
        assertTrue(failed.evidence.contains("第 3/7 块失败（已完成 2 块）"))
        assertTrue("失败也必须清理分块", fake.rmCount() >= 1)
    }

    @Test
    fun chunkedDfInsufficientAbortsBeforeSplit() {
        val fake = FakePort(listOf(byteArrayOf(1)), availableKb = 1) // 仅 1KB 可用
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("空间不足"))
        assertFalse("空间不足时不应 split", fake.has("split"))
    }

    @Test
    fun chunkedCapRejectsBeforeSplitAndDf() {
        val fake = FakePort(listOf(byteArrayOf(1)))
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, 1L * 1024 * 1024, fake)
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("超过 1MB 上限"))
        assertFalse(fake.has("split"))
        assertFalse(fake.has("df -k"))
    }

    @Test
    fun chunkedInMemoryCapRejectsBeforeSplitEvenWithHugeCap() {
        // Codex review 必修 4：max_mb 给到 4096MB，但文件超分块内存上限 → 早拒，
        // 不 split、不进拼接（防 concat Int 溢出与双份常驻内存顶爆堆）。
        val size = AdbChunkPull.MAX_IN_MEMORY_BYTES + 1L
        val fake = FakePort(listOf(byteArrayOf(1)))
        val result = AdbChunkPull.chunked("/sdcard/x.iso", size, 4096L * 1024 * 1024, fake)
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("内存上限"))
        assertFalse("内存超限不应 split", fake.has("split"))
        assertFalse(fake.has("df -k"))
        // 恰好等于上限的文件不被早拒（边界）。
        val ok = AdbChunkPull.chunked("/sdcard/x.mp4", AdbChunkPull.MAX_IN_MEMORY_BYTES, 4096L * 1024 * 1024, fake)
        // 该 fake 只返回 1 字节块，最终会因终检不符失败，但绝不能是「内存上限」这一条。
        assertFalse((ok as AdbSyncResult.Failed).reason.contains("内存上限"))
    }

    @Test
    fun chunkedAccumulatedBytesAlsoClampedByInMemoryCap() {
        // Codex review 二轮必修 4：stat 报的尺寸在阈值内（10MB，块数守卫也按 5 块通过），
        // 但每块实际返回 64MB → 逐块累计量超内存上限时必须中止（此前 assembled 只对
        // maxBytes 检查，MAX_IN_MEMORY_BYTES 不生效，仍会溢出 concat）。
        val fake = FakePort(List(5) { ByteArray(64 * 1024 * 1024) })
        val result = AdbChunkPull.chunked(
            "/sdcard/growing.bin",
            10L * 1024 * 1024,               // stat 尺寸：阈值内且与 5 块一致，通过全部前置检查
            4096L * 1024 * 1024,             // maxBytes 调到 4GB：只有内存上限能拦住
            fake,
        )
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("上限（已收"))
        // 4 块累计 256MB 不超（等于上限放行），第 5 块后 320MB > 256MB 被拒。
        assertEquals(2, fake.rmCount()) // 重入清理 + 失败后收尾清理都执行
        assertTrue(fake.has("split"))
    }

    @Test
    fun chunkedSplitErrorIsReportedAndCleaned() {
        val fake = FakePort(listOf(byteArrayOf(1)), splitError = true)
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("split 分块命令失败"))
        assertTrue("split 失败也须清理", fake.rmCount() >= 1)
    }

    @Test
    fun chunkedNoPartsEnumeratedIsReportedAndCleaned() {
        val fake = FakePort(emptyList())
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)
        assertTrue(result is AdbSyncResult.Failed)
        assertTrue((result as AdbSyncResult.Failed).reason.contains("未产生分块"))
        assertTrue(fake.rmCount() >= 1)
    }

    /* -------- 必修 1：清理结果必须进返回值，不许虚报「已清理」 -------- */

    /** 收尾 rm 失败：失败文案按实际结果生成清理警告，且 finally 的警告必须进入返回 evidence。 */
    @Test
    fun chunkedCleanupFailureIsReportedInReturnedFailureNotFalselyClaimedCleaned() {
        val partBytes = (0 until 7).map { byteArrayOf((65 + it).toByte()) }
        // 第 1 次 rm（重入清理）成功，第 2 次 rm（收尾清理）失败：驱动清理文案分支。
        val fake = FakePort(partBytes, failAtIndex = 3, rmFailAt = 2)
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        assertTrue("应失败，实际=$result", result is AdbSyncResult.Failed)
        val failed = result as AdbSyncResult.Failed
        assertTrue(failed.reason.contains("第 3/7 块"))
        assertTrue(failed.reason.contains("已完成 2 块"))
        assertTrue("rm 失败须按实际结果生成清理文案，实际=${failed.reason}",
            failed.reason.contains("警告：临时分块清理失败"))
        assertTrue(failed.reason.contains("/data/local/tmp 残留 andagent_part_*"))
        assertFalse("rm 失败时不得虚报「临时分块已清理」，实际=${failed.reason}",
            failed.reason.contains("临时分块已清理"))
        assertTrue("finally 的收尾清理警告须进入返回值 evidence，实际=${failed.evidence}",
            failed.evidence.contains("收尾清理 rm 失败"))
    }

    /** 成功路径（Pulled）下收尾 rm 失败：警告同样必须进入返回值 evidence，不虚报已清理。 */
    @Test
    fun chunkedSuccessWithCleanupFailureReflectsWarningInEvidence() {
        val fake = FakePort(
            (0 until 7).map { i -> "chunk$i;".toByteArray() },
            padToTotal = bigSize,
            rmFailAt = 2,
        )
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        assertTrue("拉取本身应成功，实际=$result", result is AdbSyncResult.Pulled)
        val pulled = result as AdbSyncResult.Pulled
        assertEquals(bigSize, pulled.bytes.size.toLong())
        assertTrue("收尾清理警告须进入成功返回值 evidence，实际=${pulled.evidence}",
            pulled.evidence.contains("收尾清理 rm 失败"))
        assertFalse("rm 失败时 evidence 不得含「临时分块已清理」虚报，实际=${pulled.evidence}",
            pulled.evidence.contains("临时分块已清理"))
    }

    /* -------- 必修 2：重入清理顺序（先 rm → 再 df → 再 split） -------- */

    /** 重入 rm 失败：直接返回可操作错误，不继续 df/split/ls（旧块不得混入本次枚举）。 */
    @Test
    fun chunkedReentryCleanupFailureAbortsBeforeDfAndSplit() {
        val fake = FakePort(listOf(byteArrayOf(1)), rmFailAt = 1)
        val result = AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        assertTrue(result is AdbSyncResult.Failed)
        val failed = result as AdbSyncResult.Failed
        assertTrue("须给出可操作的重入清理失败错误，实际=${failed.reason}",
            failed.reason.contains("重入清理失败"))
        assertTrue(failed.reason.contains("andagent_part_*"))
        assertFalse("重入清理失败后不应 df", fake.has("df -k"))
        assertFalse("重入清理失败后不应 split", fake.has("split"))
        assertFalse("重入清理失败后不应枚举", fake.has("ls -1"))
    }

    /** 重入 rm 须排在 df 之前（旧块占满 tmp 时 df 不再先拒绝），df 再排在 split 之前。 */
    @Test
    fun chunkedReentryCleanupRunsBeforeDfAndSplit() {
        val fake = FakePort((0 until 7).map { i -> "chunk$i;".toByteArray() }, padToTotal = bigSize)
        AdbChunkPull.chunked("/sdcard/x.mp4", bigSize, bigCap, fake)

        val rmIdx = fake.indexOf("rm -f")
        val dfIdx = fake.indexOf("df -k")
        val splitIdx = fake.indexOf("split")
        assertTrue("重入清理须在 df 之前（rm=$rmIdx df=$dfIdx）",
            rmIdx >= 0 && dfIdx >= 0 && rmIdx < dfIdx)
        assertTrue("df 须在 split 之前（df=$dfIdx split=$splitIdx）",
            dfIdx >= 0 && splitIdx >= 0 && dfIdx < splitIdx)
    }
}

package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

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

    // ---- R111 (c): destination 路径规划 --------------------------------
    @Test
    fun importPlanMapsDestinationsAndFallsBackToDownloads() {
        assertEquals(ImportCollection.DOWNLOADS, importPlan(null).collection)
        assertEquals("Download/AndAgentOtg", importPlan(null).relativePath)
        assertEquals(ImportCollection.DOWNLOADS, importPlan("downloads").collection)
        assertEquals("Download/AndAgentOtg", importPlan("downloads").relativePath)
        assertEquals(ImportCollection.MOVIES, importPlan("movies").collection)
        assertEquals("Movies/AndAgentOtg", importPlan("movies").relativePath)
        assertEquals(ImportCollection.PICTURES, importPlan("pictures").collection)
        assertEquals("DCIM/AndAgentOtg", importPlan("pictures").relativePath)
        // 未知值一律回落 downloads（现状不变），大小写不敏感
        assertEquals(ImportCollection.DOWNLOADS, importPlan("weird").collection)
        assertEquals(ImportCollection.MOVIES, importPlan("MOVIES").collection)
    }

    // ---- R111 (c): 名称清洗 --------------------------------------------
    @Test
    fun sanitizeNameStripsIllegalCharsAndBlankFallsBack() {
        assertEquals("a_b.mp4", sanitizeName("a/b.mp4"))
        assertEquals("a_b_c.mp4", sanitizeName("a:b*c.mp4"))
        assertEquals("otg_file", sanitizeName(""))
        assertEquals("clip.mp4", sanitizeName("clip.mp4"))
    }

    // ---- R111 review 必修 2：MIME 与 collection 一致性 -------------------
    @Test
    fun resolveImportPlanFallsBackToDownloadsOnMimeMismatch() {
        // mimeFor 不认识的扩展名落 octet-stream，塞 Video/Images 会被 MediaProvider 拒绝 → 回落 DOWNLOADS
        assertEquals(ImportCollection.DOWNLOADS, resolveImportPlan("movies", "application/octet-stream").collection)
        assertEquals("Download/AndAgentOtg", resolveImportPlan("pictures", "application/octet-stream").relativePath)
        // 类型匹配时维持原 plan
        assertEquals(ImportCollection.MOVIES, resolveImportPlan("movies", "video/mp4").collection)
        assertEquals(ImportCollection.PICTURES, resolveImportPlan("pictures", "image/jpeg").collection)
        // 交叉不匹配也回落
        assertEquals(ImportCollection.DOWNLOADS, resolveImportPlan("movies", "image/jpeg").collection)
        assertEquals(ImportCollection.DOWNLOADS, resolveImportPlan("pictures", "video/mp4").collection)
        // downloads 不受影响
        assertEquals(ImportCollection.DOWNLOADS, resolveImportPlan("downloads", "application/octet-stream").collection)
        assertEquals(ImportCollection.DOWNLOADS, resolveImportPlan(null, "video/mp4").collection)
    }

    // ---- R111 (c): auto_cleanup 语义（仅 export 且白名单内） -----------
    @Test
    fun shouldAutoCleanupOnlyForExportInsideWhitelist() {
        val dirs = listOf(File("/sdcard/Download/AndAgentOtg"), File("/sdcard/Download/AndAgentSsh"))
        assertTrue(shouldAutoCleanup("export", true, "/sdcard/Download/AndAgentOtg/a.mp4", dirs))
        assertFalse("import 一律忽略", shouldAutoCleanup("import", true, "/sdcard/Download/AndAgentOtg/a.mp4", dirs))
        assertFalse("开关关闭", shouldAutoCleanup("export", false, "/sdcard/Download/AndAgentOtg/a.mp4", dirs))
        assertFalse("白名单外不删", shouldAutoCleanup("export", true, "/sdcard/Download/Other/a.mp4", dirs))
        assertFalse("目录自身不算命中", shouldAutoCleanup("export", true, "/sdcard/Download/AndAgentOtg", dirs))
    }

    // ---- R111 (c): 目录递归路径规划 / 名称清洗 / 尺寸预算 ---------------
    @Test
    fun planRecursiveUploadBuildsRemotePathsCleansNamesAndBudgets() {
        val plans = planRecursiveUpload(
            baseParts = listOf("backup", "videos"),
            entries = listOf(
                UploadEntry("a.mp4", 100),
                UploadEntry("sub/b:ad.mp4", 200),
                UploadEntry("huge.mp4", 9999),
            ),
            maxBytes = 1000,
        )
        assertEquals(3, plans.size)
        assertEquals(listOf("backup", "videos", "a.mp4"), plans[0].remoteParts)
        assertFalse(plans[0].oversize)
        // 相对路径分段 + 名称清洗（":" → "_"）
        assertEquals(listOf("backup", "videos", "sub", "b_ad.mp4"), plans[1].remoteParts)
        assertFalse(plans[1].oversize)
        // 单文件尺寸预算：超限标记 oversize（由调用方跳过）
        assertTrue(plans[2].oversize)
        assertEquals(listOf("backup", "videos", "huge.mp4"), plans[2].remoteParts)
    }

    @Test
    fun planRecursiveUploadEmptyEntriesProducesNoTasks() {
        assertTrue(planRecursiveUpload(listOf("dir"), emptyList(), 1024).isEmpty())
    }

    // ---- R111 (d): 幽灵条目过滤（临时目录构造真实行） -------------------
    private fun tempFile(name: String, bytes: Int): File {
        val dir = Files.createTempDirectory("otg-r111").toFile()
        dir.deleteOnExit()
        return File(dir, name).apply { writeBytes(ByteArray(bytes)); deleteOnExit() }
    }

    @Test
    fun ghostNormalizedPathUnifiesStorageRootPrefixes() {
        assertEquals("Download/andagent/a.mp4", ghostNormalizedPath("/sdcard/Download/andagent/a.mp4"))
        assertEquals("Download/andagent/a.mp4", ghostNormalizedPath("/storage/emulated/0/Download/andagent/a.mp4"))
        assertEquals("Download/andagent/a.mp4", ghostNormalizedPath("/mnt/sdcard/Download/andagent/a.mp4"))
    }

    @Test
    fun filterGhostEntriesDropsEpochGhostShadowedByRealFile() {
        val real = tempFile("clip.mp4", 4096)
        // 同一物理文件：MediaStore 用 /sdcard、文件系统用 /storage/emulated/0，归一后同 key
        val ghost = FileListRow("clip.mp4", "/sdcard/Movies/AndAgentOtg/clip.mp4", 0L, 0L, false, false)
        val fsRow = FileListRow(real.name, "/storage/emulated/0/Movies/AndAgentOtg/clip.mp4", real.length(), real.lastModified(), false, true)
        val out = filterGhostEntries(listOf(ghost, fsRow))
        assertEquals("幽灵行与真实行应归一为一条", 1, out.size)
        assertEquals(4096L, out[0].size)
        assertTrue("保留带真实 size 的文件系统行", out[0].fromFs)
    }

    @Test
    fun filterGhostEntriesKeepsSizeGreaterThanZeroRow() {
        val now = System.currentTimeMillis()
        // MediaStore 行有真实 size，文件系统行 size=0 → 保留 size>0 的那行
        val media = FileListRow("x.mp4", "/sdcard/Movies/x.mp4", 500L, now, false, false)
        val fsEmpty = FileListRow("x.mp4", "/storage/emulated/0/Movies/x.mp4", 0L, now, false, true)
        val out = filterGhostEntries(listOf(media, fsEmpty))
        assertEquals(1, out.size)
        assertEquals(500L, out[0].size)
        assertFalse(out[0].fromFs)
    }

    @Test
    fun filterGhostEntriesDropsEpochGhostEvenWhenFsRowAlsoEmpty() {
        val now = System.currentTimeMillis()
        // 规则 3：同名文件系统行存在时，epoch MediaStore 幽灵行丢弃（即便 FS 也 size=0）
        val ghost = FileListRow("e.mp4", "/sdcard/Movies/e.mp4", 0L, 0L, false, false)
        val fsEmpty = FileListRow("e.mp4", "/storage/emulated/0/Movies/e.mp4", 0L, now, false, true)
        val out = filterGhostEntries(listOf(ghost, fsEmpty))
        assertEquals(1, out.size)
        assertTrue("全为 0 时保留文件系统行", out[0].fromFs)
        assertEquals(now, out[0].modified)
    }

    @Test
    fun filterGhostEntriesPrefersFileSystemDirectoryWhenAllZero() {
        val now = System.currentTimeMillis()
        val mediaGhost = FileListRow("sub", "/sdcard/Movies/sub", 0L, 0L, false, false)
        val fsDir = FileListRow("sub/", "/storage/emulated/0/Movies/sub", 0L, now, true, true)
        val out = filterGhostEntries(listOf(mediaGhost, fsDir))
        assertEquals(1, out.size)
        assertTrue(out[0].fromFs)
        assertTrue("directory 标志优先", out[0].directory)
    }

    @Test
    fun filterGhostEntriesKeepsDistinctFilesSeparate() {
        val a = tempFile("a.mp4", 10)
        val b = tempFile("b.mp4", 20)
        val rows = listOf(
            FileListRow("a.mp4", "/sdcard/Movies/a.mp4", a.length(), a.lastModified(), false, false),
            FileListRow("b.mp4", "/sdcard/Movies/b.mp4", b.length(), b.lastModified(), false, false),
        )
        assertEquals(2, filterGhostEntries(rows).size)
    }

    // ---- R111 review 必修 1：递归导出逐文件白名单复检（真实符号链接） -----
    @Test
    fun fileInsideWhitelistRejectsSymlinkEscapingDirectory() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
            return  // 非 unix 宿主跳过
        val dir = Files.createTempDirectory("otg-r111-symlink").toFile().apply { deleteOnExit() }
        val dirs = listOf(dir)
        // 普通文件：白名单内 → 放行
        val real = File(dir, "ok.mp4").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        assertTrue(fileInsideWhitelist(dir, "ok.mp4", dirs))
        // 子目录内文件也放行
        val sub = File(dir, "sub").apply { mkdirs(); deleteOnExit() }
        File(sub, "nested.mp4").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        assertTrue(fileInsideWhitelist(dir, "sub/nested.mp4", dirs))
        // 符号链接指向白名单外（/tmp 下另一文件）→ 拒绝
        val outside = Files.createTempFile("otg-r111-outside", ".mp4").toFile().apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val link = try {
            Files.createSymbolicLink(java.nio.file.Path.of(dir.absolutePath, "escape.mp4"), outside.toPath()).toFile()
        } catch (e: java.io.IOException) {
            null  // 宿主不允许符号链接时跳过
        }
        if (link != null) {
            link.deleteOnExit()
            assertFalse("符号链接越界必须拒绝", fileInsideWhitelist(dir, "escape.mp4", dirs))
        }
        // 悬空链接（目标不存在）→ 拒绝
        val dangling = try {
            Files.createSymbolicLink(java.nio.file.Path.of(dir.absolutePath, "dangling.mp4"), java.nio.file.Path.of("/nonexistent/otg-r111/target.mp4")).toFile()
        } catch (e: java.io.IOException) {
            null
        }
        if (dangling != null) {
            dangling.deleteOnExit()
            assertFalse("悬空链接必须拒绝", fileInsideWhitelist(dir, "dangling.mp4", dirs))
        }
        // 相对路径穿越（.. 逃出白名单）→ 拒绝
        assertFalse(fileInsideWhitelist(dir, "../escape.mp4", dirs))
    }

    // ---- R111 review 二轮必修：目录符号链接环不得卡死遍历 -----------------
    @Test
    fun collectUploadEntriesSkipsSymlinkDirectoriesAndTerminates() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
            return
        val dir = Files.createTempDirectory("otg-r111-cycle").toFile().apply { deleteOnExit() }
        val real = File(dir, "keep.mp4").apply { writeBytes(ByteArray(5)); deleteOnExit() }
        File(dir, "sub").apply { mkdirs(); deleteOnExit() }
        File(File(dir, "sub"), "inner.mp4").apply { writeBytes(ByteArray(7)); deleteOnExit() }
        // 目录符号链接指回自身 → 无环遍历必须在有限时间内终止
        val loop = try {
            Files.createSymbolicLink(java.nio.file.Path.of(dir.absolutePath, "loop"), dir.toPath()).toFile()
        } catch (e: java.io.IOException) {
            null
        }
        if (loop != null) {
            loop.deleteOnExit()
            val entries = collectUploadEntries(dir)
            val names = entries.map { it.localRel }.toSet()
            // 真实文件仍在（含子目录），环目录内容不被重复收集
            assertTrue(names.contains("keep.mp4"))
            assertTrue(names.contains("sub/inner.mp4"))
            assertEquals("环目录必须整体跳过", 2, entries.size)
        }
        // 普通子目录正常递归
        assertEquals(2, collectUploadEntries(dir).size)
    }

    // ---- R111 review 二轮必修 2：realPathInsideWhitelist 返回真实路径供打开/删源身份复检 ----
    @Test
    fun realPathInsideWhitelistReturnsRealPathAndRejectsEscapes() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
            return  // 非 unix 宿主跳过
        val dir = Files.createTempDirectory("otg-r111-realpath").toFile().apply { deleteOnExit() }
        val dirs = listOf(dir)
        val real = File(dir, "ok.mp4").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val realPath = realPathInsideWhitelist(dir, "ok.mp4", dirs)
        assertTrue("白名单内文件返回真实路径", realPath != null && realPath.endsWith("ok.mp4"))
        // 返回的路径必须是 toRealPath 产物（canonical 一致，删源复检以此为锚）
        assertEquals(realPath, File(realPath!!).canonicalFile.absolutePath)
        // 符号链接越界返回 null（不放行）
        val outside = Files.createTempFile("otg-r111-outside2", ".mp4").toFile().apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val link = try {
            Files.createSymbolicLink(java.nio.file.Path.of(dir.absolutePath, "escape2.mp4"), outside.toPath()).toFile()
        } catch (e: java.io.IOException) { null }
        if (link != null) {
            link.deleteOnExit()
            assertNull("符号链接越界必须返回 null", realPathInsideWhitelist(dir, "escape2.mp4", dirs))
        }
        // 悬空链接返回 null
        assertNull(realPathInsideWhitelist(dir, "missing.mp4", dirs))
        // 相对路径穿越返回 null
        assertNull(realPathInsideWhitelist(dir, "../x.mp4", dirs))
        assertTrue(real.exists())
    }
}

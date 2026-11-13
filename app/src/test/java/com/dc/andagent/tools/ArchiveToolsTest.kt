package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R152e 必修项：zip-slip 路径穿越拒绝（纯 JVM，不依赖原生库）。 */
class ArchiveToolsTest {

    private val dest = "/sdcard/Download/out"

    @Test
    fun `path whitelist accepts sdcard roots`() {
        assertNull(ArchivePathRules.validatePath("/sdcard/Download/a.zip"))
        assertNull(ArchivePathRules.validatePath("/storage/emulated/0/a.zip"))
        assertNull(ArchivePathRules.validatePath("/mnt/sdcard/a.zip"))
    }

    @Test
    fun `path whitelist rejects outside and dotdot`() {
        assertNotNull(ArchivePathRules.validatePath("/data/local/tmp/a.zip"))
        assertNotNull(ArchivePathRules.validatePath("/etc/passwd"))
        assertNotNull(ArchivePathRules.validatePath("/sdcard/../etc/passwd"))
    }

    @Test
    fun `zip-slip rejects parent traversal entries`() {
        assertNull(ArchivePathRules.safeEntryFile(dest, "../evil.txt"))
        assertNull(ArchivePathRules.safeEntryFile(dest, "a/../../evil.txt"))
        assertNull(ArchivePathRules.safeEntryFile(dest, "..\\evil.txt"))
        assertNull(ArchivePathRules.safeEntryFile(dest, "a/..\\..\\evil.txt"))
    }

    @Test
    fun `zip-slip rejects absolute and empty entries`() {
        assertNull(ArchivePathRules.safeEntryFile(dest, "/etc/passwd"))
        assertNull(ArchivePathRules.safeEntryFile(dest, ""))
        assertNull(ArchivePathRules.safeEntryFile(dest, "."))
        assertNull(ArchivePathRules.safeEntryFile(dest, ".."))
    }

    @Test
    fun `zip-slip accepts nested in-root entries`() {
        assertEquals(
            "/sdcard/Download/out/dir/file.txt",
            ArchivePathRules.safeEntryFile(dest, "dir/file.txt")?.path,
        )
        assertNotNull(ArchivePathRules.safeEntryFile(dest, "a/b/c.bin"))
        assertNotNull(ArchivePathRules.safeEntryFile(dest, "file.txt"))
    }

    @Test
    fun `tool metadata and destructive flag`() {
        assertEquals(
            listOf("archive_list", "archive_extract", "archive_create", "zstd_compress"),
            ARCHIVE_TOOLS,
        )
        assertTrue(ArchiveTool("archive_extract").destructive)
        assertTrue(!ArchiveTool("archive_list").destructive)
        assertTrue(!ArchiveTool("archive_create").destructive)
        assertTrue(!ArchiveTool("zstd_compress").destructive)
        assertTrue(ArchiveTool("archive_extract").description.contains("zip-slip"))
    }
}
